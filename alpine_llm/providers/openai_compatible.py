"""OpenAI Chat Completions compatible provider."""

from __future__ import annotations

import json
from typing import Any, Iterator

from .base import (
    DEFAULT_MAX_RESPONSE_BYTES,
    DEFAULT_MAX_STREAM_BYTES,
    DEFAULT_MAX_STREAM_EVENT_BYTES,
    ProviderCircuitBreaker,
    ProviderError,
    RetryPolicy,
    json_request,
    stream_request,
    text_content,
)
from ..protocol import CompletionDelta, CompletionRequest, CompletionResult, Usage


class OpenAICompatibleProvider:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        timeout: float = 120.0,
        max_response_bytes: int = DEFAULT_MAX_RESPONSE_BYTES,
        max_stream_event_bytes: int = DEFAULT_MAX_STREAM_EVENT_BYTES,
        max_stream_bytes: int = DEFAULT_MAX_STREAM_BYTES,
        retry_policy: RetryPolicy | None = None,
        circuit_breaker: ProviderCircuitBreaker | None = None,
    ):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self.max_response_bytes = max_response_bytes
        self.max_stream_event_bytes = max_stream_event_bytes
        self.max_stream_bytes = max_stream_bytes
        self.retry_policy = retry_policy or RetryPolicy()
        self.circuit_breaker = circuit_breaker or ProviderCircuitBreaker()

    def complete(self, request: CompletionRequest) -> CompletionResult:
        response = json_request(
            f"{self.base_url}/chat/completions",
            headers=self._headers(),
            body=self._body(request, stream=False),
            timeout=self.timeout,
            max_response_bytes=self.max_response_bytes,
            retry_policy=self.retry_policy,
            circuit_breaker=self.circuit_breaker,
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
            max_event_bytes=self.max_stream_event_bytes,
            max_stream_bytes=self.max_stream_bytes,
            max_error_bytes=self.max_response_bytes,
            retry_policy=self.retry_policy,
            circuit_breaker=self.circuit_breaker,
        ):
            if data == "[DONE]":
                return
            try:
                value = json.loads(data)
            except json.JSONDecodeError as error:
                raise ProviderError("provider returned invalid SSE JSON") from error
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
