"""Google Gemini generateContent adapter."""

from __future__ import annotations

import json
from typing import Any, Iterator
from urllib.parse import quote

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


class GeminiProvider:
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
        url = self._url(request.model, stream=False)
        response = json_request(
            url,
            headers={"Content-Type": "application/json"},
            body=self._body(request),
            timeout=self.timeout,
            max_response_bytes=self.max_response_bytes,
            retry_policy=self.retry_policy,
            circuit_breaker=self.circuit_breaker,
        )
        text = self._text(response)
        usage = response.get("usageMetadata", {})
        return CompletionResult(
            model=request.model,
            text=text,
            usage=Usage(usage.get("promptTokenCount"), usage.get("candidatesTokenCount")),
        )

    def stream(self, request: CompletionRequest) -> Iterator[CompletionDelta]:
        for _, data in stream_request(
            self._url(request.model, stream=True),
            headers={"Content-Type": "application/json"},
            body=self._body(request),
            timeout=self.timeout,
            max_event_bytes=self.max_stream_event_bytes,
            max_stream_bytes=self.max_stream_bytes,
            max_error_bytes=self.max_response_bytes,
            retry_policy=self.retry_policy,
            circuit_breaker=self.circuit_breaker,
        ):
            try:
                value = json.loads(data)
            except json.JSONDecodeError:
                continue
            usage = value.get("usageMetadata", {})
            yield CompletionDelta(
                text=self._text(value),
                usage=Usage(usage.get("promptTokenCount"), usage.get("candidatesTokenCount")),
            )

    def _url(self, model: str, *, stream: bool) -> str:
        model_path = quote(model, safe="")
        if stream:
            return f"{self.base_url}/models/{model_path}:streamGenerateContent?alt=sse&key={quote(self.api_key)}"
        return f"{self.base_url}/models/{model_path}:generateContent?key={quote(self.api_key)}"

    @staticmethod
    def _body(request: CompletionRequest) -> dict[str, Any]:
        contents: list[dict[str, Any]] = []
        system_parts: list[dict[str, str]] = []
        if request.system:
            system_parts.append({"text": request.system})
        for message in request.messages:
            if message.role == "system":
                system_parts.append({"text": text_content(message.content)})
                continue
            role = "model" if message.role == "assistant" else "user"
            content = message.content
            parts = content if isinstance(content, list) else [{"text": str(content)}]
            contents.append({"role": role, "parts": parts})
        body: dict[str, Any] = {"contents": contents}
        if system_parts:
            body["systemInstruction"] = {"parts": system_parts}
        generation: dict[str, Any] = {"maxOutputTokens": request.max_tokens}
        if request.temperature is not None:
            generation["temperature"] = request.temperature
        body["generationConfig"] = generation
        return body

    @staticmethod
    def _text(value: dict[str, Any]) -> str:
        candidates = value.get("candidates") or []
        if not candidates:
            return ""
        return text_content((candidates[0].get("content") or {}).get("parts"))
