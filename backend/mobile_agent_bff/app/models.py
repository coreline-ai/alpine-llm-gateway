from __future__ import annotations

from enum import StrEnum
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class ProviderName(StrEnum):
    OPENAI = "openai"
    ANTHROPIC = "anthropic"
    XAI = "xai"


class ChatMessage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    role: Literal["system", "user", "assistant"]
    content: str = Field(min_length=1, max_length=80_000)


class ChatStreamRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: str = Field(pattern=r"^[A-Za-z0-9_-]{8,80}$")
    provider: ProviderName
    model: str = Field(pattern=r"^[A-Za-z0-9._:/-]{1,120}$")
    messages: list[ChatMessage] = Field(min_length=1, max_length=128)
    temperature: float | None = Field(default=None, ge=0, le=2)

    @model_validator(mode="after")
    def require_user_message(self) -> "ChatStreamRequest":
        if not any(message.role == "user" for message in self.messages):
            raise ValueError("at least one user message is required")
        return self

    @property
    def prompt_chars(self) -> int:
        return sum(len(message.content) for message in self.messages)


class AuthPrincipal(BaseModel):
    model_config = ConfigDict(extra="forbid")

    subject: str
    authorized_party: str
    expires_at: int
    issued_at: int
    jwt_id: str | None = None


class ProviderDescription(BaseModel):
    name: ProviderName
    configured: bool
    models: tuple[str, ...]
