"""Configuration loading with environment-variable secret support."""

from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
from typing import Any


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
    timeout_seconds: float = 120.0
    allow_passthrough: bool = False

    @classmethod
    def from_file(cls, path: str | None = None) -> "Settings":
        raw: dict[str, Any] = {}
        if path:
            raw = json.loads(Path(path).read_text(encoding="utf-8"))
        api_key_env = str(raw.get("api_key_env", "LLM_API_KEY"))
        api_key = os.environ.get(api_key_env, raw.get("api_key", ""))
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
            timeout_seconds=float(raw.get("timeout_seconds", 120.0)),
            allow_passthrough=bool(raw.get("allow_passthrough", False)),
        )
