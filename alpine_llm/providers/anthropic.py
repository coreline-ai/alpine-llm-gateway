"""Anthropic Messages API adapter."""

from __future__ import annotations

import json
from typing import Any, Iterator

from .base import ProviderError, json_request, stream_request, text_content
from ..protocol import CompletionDelta, CompletionRequest, CompletionResult, Usage


class AnthropicProvider:
    def __init__(self, base_url: str, api_key: str, timeout: float = 120.0):
        self.base_url = base_url.rstrip("/")
        if self.base_url.endswith("/v1"):
            self.base_url = self.base_url[:-3].rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def complete(self, request: CompletionRequest) -> CompletionResult:
        response = json_request(
            f"{self.base_url}/v1/messages",
            headers=self._headers(),
            body=self._body(request, stream=False),
            timeout=self.timeout,
        )
        usage = response.get("usage", {})
        return CompletionResult(
            model=str(response.get("model", request.model)),
            text=text_content(response.get("content")),
            finish_reason=str(response.get("stop_reason") or "stop"),
            usage=Usage(usage.get("input_tokens"), usage.get("output_tokens")),
        )

    def stream(self, request: CompletionRequest) -> Iterator[CompletionDelta]:
        input_tokens: int | None = None
        for event, data in stream_request(
            f"{self.base_url}/v1/messages",
            headers=self._headers(),
            body=self._body(request, stream=True),
            timeout=self.timeout,
        ):
            try:
                value = json.loads(data)
            except json.JSONDecodeError:
                continue
            event = event or value.get("type")
            if event == "message_start":
                input_tokens = (value.get("message", {}).get("usage", {}) or {}).get("input_tokens")
            elif event == "content_block_delta":
                delta = value.get("delta", {})
                yield CompletionDelta(text=str(delta.get("text", "")))
            elif event == "message_delta":
                delta = value.get("delta", {})
                usage = value.get("usage", {})
                yield CompletionDelta(
                    finish_reason=delta.get("stop_reason"),
                    usage=Usage(input_tokens, usage.get("output_tokens")),
                )

    def _headers(self) -> dict[str, str]:
        return {
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
            "x-api-key": self.api_key,
            "anthropic-version": "2023-06-01",
        }

    @staticmethod
    def _body(request: CompletionRequest, *, stream: bool) -> dict[str, Any]:
        messages = [message.to_dict() for message in request.messages if message.role != "system"]
        body: dict[str, Any] = {
            "model": request.model,
            "messages": messages,
            "max_tokens": request.max_tokens,
            "stream": stream,
        }
        system_messages = [message.content for message in request.messages if message.role == "system"]
        if request.system:
            system_messages.insert(0, request.system)
        if system_messages:
            body["system"] = "\n\n".join(text_content(item) for item in system_messages)
        if request.temperature is not None:
            body["temperature"] = request.temperature
        return body
