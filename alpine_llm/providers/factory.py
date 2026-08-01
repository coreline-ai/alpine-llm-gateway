"""Provider construction."""

from __future__ import annotations

from .anthropic import AnthropicProvider
from .base import Provider, ProviderCircuitBreaker, ProviderError, RetryPolicy
from .gemini import GeminiProvider
from .openai_compatible import OpenAICompatibleProvider
from ..config import Settings


def create_provider(settings: Settings) -> Provider:
    provider = settings.provider.lower().replace("_", "-")
    retry_policy = RetryPolicy(
        max_attempts=settings.provider_retry_max_attempts,
        initial_backoff_seconds=settings.provider_retry_initial_backoff_seconds,
        max_backoff_seconds=settings.provider_retry_max_backoff_seconds,
        jitter_ratio=settings.provider_retry_jitter_ratio,
    )
    circuit_breaker = ProviderCircuitBreaker(
        failure_threshold=settings.provider_circuit_failure_threshold,
        recovery_timeout_seconds=settings.provider_circuit_recovery_seconds,
    )
    if provider in {"openai", "openai-compatible", "openrouter", "xai", "kimi"}:
        return OpenAICompatibleProvider(
            settings.base_url,
            settings.api_key,
            settings.timeout_seconds,
            settings.max_response_bytes,
            settings.max_stream_event_bytes,
            settings.max_stream_bytes,
            retry_policy,
            circuit_breaker,
        )
    if provider in {"anthropic", "claude"}:
        return AnthropicProvider(
            settings.base_url,
            settings.api_key,
            settings.timeout_seconds,
            settings.max_response_bytes,
            settings.max_stream_event_bytes,
            settings.max_stream_bytes,
            retry_policy,
            circuit_breaker,
        )
    if provider in {"gemini", "google"}:
        return GeminiProvider(
            settings.base_url,
            settings.api_key,
            settings.timeout_seconds,
            settings.max_response_bytes,
            settings.max_stream_event_bytes,
            settings.max_stream_bytes,
            retry_policy,
            circuit_breaker,
        )
    raise ProviderError(f"unsupported provider: {settings.provider}")
