from __future__ import annotations

import asyncio
import json
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from dataclasses import dataclass
from typing import Any

import httpx

from .models import ChatStreamRequest, ProviderName


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
    ):
        self.api_key = api_key
        self.models = models
        self.client = client or httpx.AsyncClient(timeout=timeout, follow_redirects=False)
        self._owns_client = client is None

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
                event_type: str | None = None
                data_lines: list[str] = []
                async for line in response.aiter_lines():
                    if cancel.is_set():
                        return
                    if line == "":
                        if not data_lines:
                            event_type = None
                            continue
                        raw_data = "\n".join(data_lines)
                        data_lines.clear()
                        if raw_data == "[DONE]":
                            return
                        try:
                            payload = json.loads(raw_data)
                        except json.JSONDecodeError as error:
                            raise ProviderStreamError("provider_stream_invalid") from error
                        if not isinstance(payload, dict):
                            raise ProviderStreamError("provider_stream_invalid")
                        yield event_type, payload
                        event_type = None
                    elif line.startswith("event:"):
                        event_type = line[6:].strip()
                    elif line.startswith("data:"):
                        data_lines.append(line[5:].lstrip())
                if data_lines:
                    try:
                        payload = json.loads("\n".join(data_lines))
                    except json.JSONDecodeError as error:
                        raise ProviderStreamError("provider_stream_invalid") from error
                    if isinstance(payload, dict):
                        yield event_type, payload
        except ProviderStreamError:
            raise
        except (httpx.TimeoutException, httpx.NetworkError) as error:
            raise ProviderStreamError("provider_unavailable", 503) from error


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
