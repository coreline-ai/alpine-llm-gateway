"""OpenAI Chat Completions compatible provider."""

from __future__ import annotations

import json
from typing import Any, Iterator

from .base import ProviderError, json_request, stream_request, text_content
from ..protocol import CompletionDelta, CompletionRequest, CompletionResult, Usage


class OpenAICompatibleProvider:
    def __init__(self, base_url: str, api_key: str, timeout: float = 120.0):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def complete(self, request: CompletionRequest) -> CompletionResult:
        response = json_request(
            f"{self.base_url}/chat/completions",
            headers=self._headers(),
            body=self._body(request, stream=False),
            timeout=self.timeout,
        )
        choices = response.get("choices") or []
        if not choices:
            raise ProviderError("provider returned no choices")
        choice = choices[0]
        message = choice.get("message", {})
        usage = response.get("usage", {})
        return CompletionResult(
            model=str(response.get("model", request.model)),
            text=text_content(message.get("content")),
            finish_reason=str(choice.get("finish_reason") or "stop"),
            usage=Usage(usage.get("prompt_tokens"), usage.get("completion_tokens")),
        )

    def stream(self, request: CompletionRequest) -> Iterator[CompletionDelta]:
        for _, data in stream_request(
            f"{self.base_url}/chat/completions",
            headers=self._headers(),
            body=self._body(request, stream=True),
            timeout=self.timeout,
        ):
            if data == "[DONE]":
                return
            try:
                value = json.loads(data)
            except json.JSONDecodeError:
                continue
            choices = value.get("choices") or []
            choice = choices[0] if choices else {}
            delta = choice.get("delta", {})
            usage = value.get("usage") or {}
            yield CompletionDelta(
                text=text_content(delta.get("content")),
                finish_reason=choice.get("finish_reason"),
                usage=Usage(usage.get("prompt_tokens"), usage.get("completion_tokens")),
            )

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json", "Accept": "text/event-stream"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        return headers

    @staticmethod
    def _body(request: CompletionRequest, *, stream: bool) -> dict[str, Any]:
        body: dict[str, Any] = {
            "model": request.model,
            "messages": [message.to_dict() for message in request.messages],
            "max_tokens": request.max_tokens,
            "stream": stream,
        }
        if request.temperature is not None:
            body["temperature"] = request.temperature
        if request.response_format is not None:
            body["response_format"] = request.response_format
        if request.system:
            body["messages"] = [{"role": "system", "content": request.system}, *body["messages"]]
        return body
