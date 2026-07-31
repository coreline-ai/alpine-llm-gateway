"""Provider construction."""

from __future__ import annotations

from .anthropic import AnthropicProvider
from .base import Provider, ProviderError
from .gemini import GeminiProvider
from .openai_compatible import OpenAICompatibleProvider
from ..config import Settings


def create_provider(settings: Settings) -> Provider:
    provider = settings.provider.lower().replace("_", "-")
    if provider in {"openai", "openai-compatible", "openrouter", "xai", "kimi"}:
        return OpenAICompatibleProvider(settings.base_url, settings.api_key, settings.timeout_seconds)
    if provider in {"anthropic", "claude"}:
        return AnthropicProvider(settings.base_url, settings.api_key, settings.timeout_seconds)
    if provider in {"gemini", "google"}:
        return GeminiProvider(settings.base_url, settings.api_key, settings.timeout_seconds)
    raise ProviderError(f"unsupported provider: {settings.provider}")
