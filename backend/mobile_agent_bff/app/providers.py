from __future__ import annotations

import asyncio
import json
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any

import httpx

from .models import ChatStreamRequest, ProviderName


DEFAULT_MAX_PROVIDER_EVENT_BYTES = 1 * 1024 * 1024
DEFAULT_MAX_PROVIDER_STREAM_BYTES = 32 * 1024 * 1024


@dataclass(frozen=True)
class ProviderEvent:
    type: str
    payload: dict[str, Any]


class ProviderStreamError(Exception):
    def __init__(self, code: str, status_code: int = 502):
        super().__init__(code)
        self.code = code
        self.status_code = status_code


class ProviderAdapter(ABC):
    name: ProviderName

    def __init__(
        self,
        api_key: str,
        models: tuple[str, ...],
        timeout: float,
        client: httpx.AsyncClient | None = None,
        *,
        max_event_bytes: int = DEFAULT_MAX_PROVIDER_EVENT_BYTES,
        max_stream_bytes: int = DEFAULT_MAX_PROVIDER_STREAM_BYTES,
    ):
        if max_event_bytes <= 0 or max_stream_bytes <= 0:
            raise ValueError("Provider stream limits must be positive")
        if max_event_bytes > max_stream_bytes:
            raise ValueError("Provider event limit must not exceed stream limit")
        self.api_key = api_key
        self.models = models
        self.client = client or httpx.AsyncClient(timeout=timeout, follow_redirects=False)
        self._owns_client = client is None
        self.max_event_bytes = max_event_bytes
        self.max_stream_bytes = max_stream_bytes

    @property
    def configured(self) -> bool:
        return bool(self.api_key and self.models)

    def validate(self, request: ChatStreamRequest) -> None:
        if not self.configured:
            raise ProviderStreamError("provider_not_configured", 503)
        if request.model not in self.models:
            raise ProviderStreamError("model_not_allowed", 400)

    @abstractmethod
    async def stream(
        self,
        request: ChatStreamRequest,
        cancel: asyncio.Event,
    ) -> AsyncIterator[ProviderEvent]:
        raise NotImplementedError

    async def close(self) -> None:
        if self._owns_client:
            await self.client.aclose()

    async def _events(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str],
        body: dict[str, Any],
        cancel: asyncio.Event,
    ) -> AsyncIterator[tuple[str | None, dict[str, Any]]]:
        try:
            async with self.client.stream(method, url, headers=headers, json=body) as response:
                if response.status_code >= 400:
                    raise ProviderStreamError(
                        _status_error(response.status_code),
                        _normalized_status(response.status_code),
                    )
                content_type = response.headers.get("content-type", "")
                if content_type.partition(";")[0].strip().lower() != "text/event-stream":
                    raise ProviderStreamError("provider_stream_invalid")
                decoder = _BoundedSseDecoder(
                    max_event_bytes=self.max_event_bytes,
                    max_stream_bytes=self.max_stream_bytes,
                )
                async for chunk in response.aiter_bytes():
                    if cancel.is_set():
                        return
                    for event_type, raw_data in decoder.feed(chunk):
                        if raw_data == "[DONE]":
                            return
                        yield event_type, _json_object(raw_data)
                for event_type, raw_data in decoder.finish():
                    if raw_data == "[DONE]":
                        return
                    yield event_type, _json_object(raw_data)
        except ProviderStreamError:
            raise
        except httpx.HTTPError as error:
            raise ProviderStreamError("provider_unavailable", 503) from error


class _BoundedSseDecoder:
    """Incremental SSE framing with strict UTF-8 and raw byte limits."""

    def __init__(self, *, max_event_bytes: int, max_stream_bytes: int):
        self.max_event_bytes = max_event_bytes
        self.max_stream_bytes = max_stream_bytes
        self.total_bytes = 0
        self.event_bytes = 0
        self.buffer = bytearray()
        self.event_type: str | None = None
        self.data_lines: list[str] = []

    def feed(self, chunk: bytes) -> list[tuple[str | None, str]]:
        self.total_bytes += len(chunk)
        if self.total_bytes > self.max_stream_bytes:
            raise ProviderStreamError("provider_stream_too_large")
        self.buffer.extend(chunk)
        events: list[tuple[str | None, str]] = []
        while True:
            newline = self.buffer.find(b"\n")
            if newline < 0:
                if self.event_bytes + len(self.buffer) > self.max_event_bytes:
                    raise ProviderStreamError("provider_stream_too_large")
                return events
            raw_line = bytes(self.buffer[:newline])
            del self.buffer[: newline + 1]
            events.extend(self._accept_line(raw_line, newline + 1))

    def finish(self) -> list[tuple[str | None, str]]:
        events: list[tuple[str | None, str]] = []
        if self.buffer:
            raw_line = bytes(self.buffer)
            self.buffer.clear()
            events.extend(self._accept_line(raw_line, len(raw_line)))
        pending = self._dispatch()
        if pending is not None:
            events.append(pending)
        return events

    def _accept_line(
        self,
        raw_line: bytes,
        consumed_bytes: int,
    ) -> list[tuple[str | None, str]]:
        self.event_bytes += consumed_bytes
        if self.event_bytes > self.max_event_bytes:
            raise ProviderStreamError("provider_stream_too_large")
        if raw_line.endswith(b"\r"):
            raw_line = raw_line[:-1]
        try:
            line = raw_line.decode("utf-8", errors="strict")
        except UnicodeDecodeError as error:
            raise ProviderStreamError("provider_stream_invalid") from error
        if line == "":
            pending = self._dispatch()
            return [] if pending is None else [pending]
        if line.startswith(":"):
            return []
        field, separator, raw_value = line.partition(":")
        value = raw_value[1:] if separator and raw_value.startswith(" ") else raw_value
        if field == "event":
            self.event_type = value
        elif field == "data":
            self.data_lines.append(value)
        return []

    def _dispatch(self) -> tuple[str | None, str] | None:
        pending = None
        if self.data_lines:
            pending = (self.event_type, "\n".join(self.data_lines))
        self.event_type = None
        self.data_lines.clear()
        self.event_bytes = 0
        return pending


def _json_object(raw_data: str) -> dict[str, Any]:
    try:
        payload = json.loads(raw_data)
    except json.JSONDecodeError as error:
        raise ProviderStreamError("provider_stream_invalid") from error
    if not isinstance(payload, dict):
        raise ProviderStreamError("provider_stream_invalid")
    return payload


class OpenAIAdapter(ProviderAdapter):
    name = ProviderName.OPENAI
    endpoint = "https://api.openai.com/v1/responses"

    async def stream(self, request, cancel):
        self.validate(request)
        body: dict[str, Any] = {
            "model": request.model,
            "input": [message.model_dump() for message in request.messages],
            "stream": True,
        }
        if request.temperature is not None:
            body["temperature"] = request.temperature
        async for event_name, payload in self._events(
            "POST",
            self.endpoint,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            body=body,
            cancel=cancel,
        ):
            kind = payload.get("type") or event_name
            if kind == "response.output_text.delta" and isinstance(payload.get("delta"), str):
                yield ProviderEvent("delta", {"text": payload["delta"]})
            elif kind == "response.completed":
                usage = (payload.get("response") or {}).get("usage")
                if isinstance(usage, dict):
                    yield ProviderEvent("usage", _usage(usage))
            elif kind in {"error", "response.failed"}:
                raise ProviderStreamError("provider_stream_error")


class AnthropicAdapter(ProviderAdapter):
    name = ProviderName.ANTHROPIC
    endpoint = "https://api.anthropic.com/v1/messages"

    async def stream(self, request, cancel):
        self.validate(request)
        system = "\n\n".join(
            message.content for message in request.messages if message.role == "system"
        )
        body: dict[str, Any] = {
            "model": request.model,
            "messages": [
                message.model_dump()
                for message in request.messages
                if message.role in {"user", "assistant"}
            ],
            "max_tokens": 4096,
            "stream": True,
        }
        if system:
            body["system"] = system
        if request.temperature is not None:
            body["temperature"] = request.temperature
        async for event_name, payload in self._events(
            "POST",
            self.endpoint,
            headers={
                "x-api-key": self.api_key,
                "anthropic-version": "2023-06-01",
                "Content-Type": "application/json",
            },
            body=body,
            cancel=cancel,
        ):
            kind = payload.get("type") or event_name
            delta = payload.get("delta")
            if kind == "content_block_delta" and isinstance(delta, dict):
                if isinstance(delta.get("text"), str):
                    yield ProviderEvent("delta", {"text": delta["text"]})
            elif kind in {"message_start", "message_delta"}:
                usage = payload.get("usage") or (payload.get("message") or {}).get("usage")
                if isinstance(usage, dict):
                    yield ProviderEvent("usage", _usage(usage))
            elif kind == "error":
                raise ProviderStreamError("provider_stream_error")


class XaiAdapter(ProviderAdapter):
    name = ProviderName.XAI
    endpoint = "https://api.x.ai/v1/chat/completions"

    async def stream(self, request, cancel):
        self.validate(request)
        body: dict[str, Any] = {
            "model": request.model,
            "messages": [message.model_dump() for message in request.messages],
            "stream": True,
            "stream_options": {"include_usage": True},
        }
        if request.temperature is not None:
            body["temperature"] = request.temperature
        async for _, payload in self._events(
            "POST",
            self.endpoint,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            body=body,
            cancel=cancel,
        ):
            choices = payload.get("choices")
            if isinstance(choices, list) and choices:
                delta = choices[0].get("delta") if isinstance(choices[0], dict) else None
                if isinstance(delta, dict) and isinstance(delta.get("content"), str):
                    yield ProviderEvent("delta", {"text": delta["content"]})
            usage = payload.get("usage")
            if isinstance(usage, dict):
                yield ProviderEvent("usage", _usage(usage))
            if "error" in payload:
                raise ProviderStreamError("provider_stream_error")


def _usage(value: dict[str, Any]) -> dict[str, int]:
    aliases = {
        "inputTokens": ("input_tokens", "prompt_tokens"),
        "outputTokens": ("output_tokens", "completion_tokens"),
        "totalTokens": ("total_tokens",),
    }
    result: dict[str, int] = {}
    for output, candidates in aliases.items():
        for candidate in candidates:
            if isinstance(value.get(candidate), int):
                result[output] = value[candidate]
                break
    return result


def _status_error(status_code: int) -> str:
    if status_code == 401:
        return "provider_authentication_failed"
    if status_code == 403:
        return "provider_access_denied"
    if status_code == 404:
        return "provider_model_not_found"
    if status_code == 429:
        return "provider_rate_limited"
    if status_code >= 500:
        return "provider_unavailable"
    return "provider_request_rejected"


def _normalized_status(status_code: int) -> int:
    if status_code in {400, 401, 403, 404, 429}:
        return status_code
    return 503 if status_code >= 500 else 502
