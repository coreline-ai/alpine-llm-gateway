"""Configuration loading with environment-variable secret support."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
from typing import Any


MAX_CREDENTIAL_FILE_BYTES = 8 * 1024


@dataclass(frozen=True)
class Settings:
    host: str = "127.0.0.1"
    port: int = 8787
    provider: str = "openai-compatible"
    base_url: str = "https://api.openai.com/v1"
    api_key: str = ""
    default_model: str = ""
    allowed_models: tuple[str, ...] = ()
    model_catalog: tuple[dict[str, Any], ...] = ()
    max_input_bytes: int = 1_048_576
    max_output_tokens: int = 4096
    max_messages: int = 64
    max_response_bytes: int = 8 * 1024 * 1024
    max_stream_event_bytes: int = 1 * 1024 * 1024
    max_stream_bytes: int = 32 * 1024 * 1024
    timeout_seconds: float = 120.0
    provider_retry_max_attempts: int = 3
    provider_retry_initial_backoff_seconds: float = 0.5
    provider_retry_max_backoff_seconds: float = 8.0
    provider_retry_jitter_ratio: float = 0.2
    provider_circuit_failure_threshold: int = 5
    provider_circuit_recovery_seconds: float = 30.0
    allow_passthrough: bool = False

    def __post_init__(self) -> None:
        if not self.host:
            raise ValueError("host must not be empty")
        if not 1 <= self.port <= 65535:
            raise ValueError("port must be between 1 and 65535")
        if not self.provider:
            raise ValueError("provider must not be empty")
        if not self.base_url:
            raise ValueError("base_url must not be empty")
        if self.max_input_bytes <= 0:
            raise ValueError("max_input_bytes must be positive")
        if self.max_output_tokens <= 0:
            raise ValueError("max_output_tokens must be positive")
        if self.max_messages <= 0:
            raise ValueError("max_messages must be positive")
        if self.max_response_bytes <= 0:
            raise ValueError("max_response_bytes must be positive")
        if self.max_stream_event_bytes <= 0:
            raise ValueError("max_stream_event_bytes must be positive")
        if self.max_stream_bytes <= 0:
            raise ValueError("max_stream_bytes must be positive")
        if self.timeout_seconds <= 0:
            raise ValueError("timeout_seconds must be positive")
        if not 1 <= self.provider_retry_max_attempts <= 10:
            raise ValueError("provider_retry_max_attempts must be between 1 and 10")
        if self.provider_retry_initial_backoff_seconds < 0:
            raise ValueError("provider_retry_initial_backoff_seconds must not be negative")
        if self.provider_retry_max_backoff_seconds < self.provider_retry_initial_backoff_seconds:
            raise ValueError(
                "provider_retry_max_backoff_seconds must be at least "
                "provider_retry_initial_backoff_seconds",
            )
        if not 0 <= self.provider_retry_jitter_ratio <= 1:
            raise ValueError("provider_retry_jitter_ratio must be between 0 and 1")
        if self.provider_circuit_failure_threshold <= 0:
            raise ValueError("provider_circuit_failure_threshold must be positive")
        if self.provider_circuit_recovery_seconds <= 0:
            raise ValueError("provider_circuit_recovery_seconds must be positive")
        if not isinstance(self.allow_passthrough, bool):
            raise ValueError("allow_passthrough must be a boolean")

    @classmethod
    def from_file(cls, path: str | None = None) -> "Settings":
        raw: dict[str, Any] = {}
        if path:
            raw = json.loads(Path(path).read_text(encoding="utf-8"))
        api_key_file = str(
            raw.get("api_key_file", os.environ.get("ALPINE_LLM_CREDENTIAL_FILE", "")),
        ).strip()
        if api_key_file:
            api_key = _read_credential_file(api_key_file)
        else:
            api_key_env = str(raw.get("api_key_env", "LLM_API_KEY"))
            api_key = str(os.environ.get(api_key_env, raw.get("api_key", "")))
        allowed = tuple(str(item) for item in raw.get("allowed_models", []))
        catalog = tuple(item for item in raw.get("model_catalog", []) if isinstance(item, dict))
        default_model = str(raw.get("default_model", os.environ.get("LLM_MODEL", "")))
        if not allowed and default_model:
            allowed = (default_model,)
        return cls(
            host=str(raw.get("host", os.environ.get("LLM_HOST", "127.0.0.1"))),
            port=int(raw.get("port", os.environ.get("LLM_PORT", 8787))),
            provider=str(raw.get("provider", os.environ.get("LLM_PROVIDER", "openai-compatible"))),
            base_url=str(raw.get("base_url", os.environ.get("LLM_BASE_URL", "https://api.openai.com/v1"))),
            api_key=api_key,
            default_model=default_model,
            allowed_models=allowed,
            model_catalog=catalog,
            max_input_bytes=int(raw.get("max_input_bytes", 1_048_576)),
            max_output_tokens=int(raw.get("max_output_tokens", 4096)),
            max_messages=int(raw.get("max_messages", 64)),
            max_response_bytes=int(raw.get("max_response_bytes", 8 * 1024 * 1024)),
            max_stream_event_bytes=int(raw.get("max_stream_event_bytes", 1 * 1024 * 1024)),
            max_stream_bytes=int(raw.get("max_stream_bytes", 32 * 1024 * 1024)),
            timeout_seconds=float(raw.get("timeout_seconds", 120.0)),
            provider_retry_max_attempts=int(raw.get("provider_retry_max_attempts", 3)),
            provider_retry_initial_backoff_seconds=float(
                raw.get("provider_retry_initial_backoff_seconds", 0.5),
            ),
            provider_retry_max_backoff_seconds=float(
                raw.get("provider_retry_max_backoff_seconds", 8.0),
            ),
            provider_retry_jitter_ratio=float(raw.get("provider_retry_jitter_ratio", 0.2)),
            provider_circuit_failure_threshold=int(
                raw.get("provider_circuit_failure_threshold", 5),
            ),
            provider_circuit_recovery_seconds=float(
                raw.get("provider_circuit_recovery_seconds", 30.0),
            ),
            allow_passthrough=_boolean(raw, "allow_passthrough", default=False),
        )


def _boolean(raw: dict[str, Any], name: str, *, default: bool) -> bool:
    value = raw.get(name, default)
    if not isinstance(value, bool):
        raise ValueError(f"{name} must be a boolean")
    return value


def _read_credential_file(path: str) -> str:
    credential = Path(path)
    if not credential.is_file():
        raise ValueError("credential file is unavailable")
    with credential.open("rb") as source:
        raw = source.read(MAX_CREDENTIAL_FILE_BYTES + 1)
    if len(raw) > MAX_CREDENTIAL_FILE_BYTES:
        raise ValueError("credential file is too large")
    try:
        value = raw.decode("utf-8").strip()
    except UnicodeDecodeError as error:
        raise ValueError("credential file is invalid") from error
    if not value or "\x00" in value or "\n" in value or "\r" in value:
        raise ValueError("credential file is invalid")
    return value
