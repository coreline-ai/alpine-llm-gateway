"""Adapter for the normalized Android Host Bridge SSE protocol."""

from __future__ import annotations

import json
from typing import Iterator

from .base import ProviderError, stream_request
from .openai_compatible import OpenAICompatibleProvider
from ..protocol import CompletionDelta, CompletionRequest, Usage


class AndroidHostBridgeProvider(OpenAICompatibleProvider):
    """OpenAI-compatible completion plus normalized, redacted Host Bridge streaming."""

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
                raise ProviderError("host bridge returned invalid SSE JSON") from error
            if not isinstance(value, dict):
                raise ProviderError("host bridge returned invalid SSE event")
            event_type = value.get("type")
            if event_type == "start":
                continue
            if event_type == "delta":
                usage = value.get("usage") or {}
                if not isinstance(usage, dict):
                    raise ProviderError("host bridge returned invalid usage")
                yield CompletionDelta(
                    text=str(value.get("text") or ""),
                    finish_reason=value.get("finish_reason"),
                    usage=Usage(usage.get("prompt_tokens"), usage.get("completion_tokens")),
                )
                continue
            if event_type == "done":
                return
            if event_type == "error":
                raise ProviderError(
                    "host bridge stream failed",
                    retryable=bool(value.get("retryable", False)),
                )
            raise ProviderError("host bridge returned an unknown SSE event")
