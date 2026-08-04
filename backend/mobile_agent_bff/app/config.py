from __future__ import annotations

import os
from dataclasses import dataclass
from urllib.parse import urlparse


def _csv(value: str) -> tuple[str, ...]:
    return tuple(item.strip() for item in value.split(",") if item.strip())


@dataclass(frozen=True)
class Settings:
    oidc_issuer: str
    oidc_audience: str
    oidc_allowed_azp: tuple[str, ...]
    openai_api_key: str = ""
    anthropic_api_key: str = ""
    xai_api_key: str = ""
    openai_models: tuple[str, ...] = ()
    anthropic_models: tuple[str, ...] = ()
    xai_models: tuple[str, ...] = ()
    request_timeout_seconds: float = 120.0
    max_prompt_chars: int = 120_000
    max_concurrent_requests: int = 4

    @classmethod
    def from_env(cls) -> "Settings":
        settings = cls(
            oidc_issuer=os.getenv(
                "OIDC_ISSUER",
                "https://auth.invalid/realms/mobileagent",
            ).rstrip("/"),
            oidc_audience=os.getenv("OIDC_AUDIENCE", "mobile-agent-bff"),
            oidc_allowed_azp=_csv(
                os.getenv("OIDC_ALLOWED_AZP", "mobile-agent-native")
            ),
            openai_api_key=os.getenv("OPENAI_API_KEY", ""),
            anthropic_api_key=os.getenv("ANTHROPIC_API_KEY", ""),
            xai_api_key=os.getenv("XAI_API_KEY", ""),
            openai_models=_csv(os.getenv("OPENAI_MODELS", "")),
            anthropic_models=_csv(os.getenv("ANTHROPIC_MODELS", "")),
            xai_models=_csv(os.getenv("XAI_MODELS", "")),
            request_timeout_seconds=float(
                os.getenv("REQUEST_TIMEOUT_SECONDS", "120")
            ),
            max_prompt_chars=int(os.getenv("MAX_PROMPT_CHARS", "120000")),
            max_concurrent_requests=int(
                os.getenv("MAX_CONCURRENT_REQUESTS", "4")
            ),
        )
        settings.validate()
        return settings

    def validate(self) -> None:
        issuer = urlparse(self.oidc_issuer)
        if issuer.scheme != "https" or not issuer.hostname or issuer.query or issuer.fragment:
            raise ValueError("OIDC_ISSUER must be a clean HTTPS URL")
        if not self.oidc_audience or not self.oidc_allowed_azp:
            raise ValueError("OIDC audience and allowed authorized parties are required")
        if self.request_timeout_seconds <= 0 or self.max_prompt_chars <= 0:
            raise ValueError("request limits must be positive")
        if self.max_concurrent_requests not in range(1, 33):
            raise ValueError("MAX_CONCURRENT_REQUESTS must be between 1 and 32")

    def models_for(self, provider: str) -> tuple[str, ...]:
        return {
            "openai": self.openai_models,
            "anthropic": self.anthropic_models,
            "xai": self.xai_models,
        }.get(provider, ())
