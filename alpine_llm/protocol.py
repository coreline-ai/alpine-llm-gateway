"""Provider-neutral request and response types."""

from __future__ import annotations

from dataclasses import dataclass, field
import json
from typing import Any


class ProtocolError(ValueError):
    """Raised when a request does not match the public protocol."""


@dataclass(frozen=True)
class Message:
    role: str
    content: Any

    @classmethod
    def from_dict(cls, value: Any) -> "Message":
        if not isinstance(value, dict):
            raise ProtocolError("each message must be an object")
        role = value.get("role")
        if role not in {"system", "user", "assistant", "tool"}:
            raise ProtocolError("message role must be system, user, assistant, or tool")
        if "content" not in value:
            raise ProtocolError("message content is required")
        return cls(role=role, content=value["content"])

    def to_dict(self) -> dict[str, Any]:
        return {"role": self.role, "content": self.content}


@dataclass(frozen=True)
class CompletionRequest:
    model: str
    messages: tuple[Message, ...]
    system: str | None = None
    max_tokens: int = 1024
    temperature: float | None = None
    stream: bool = False
    response_format: dict[str, Any] | None = None

    @classmethod
    def from_dict(cls, value: Any) -> "CompletionRequest":
        if not isinstance(value, dict):
            raise ProtocolError("request must be a JSON object")
        model = value.get("model") or "auto"
        if not isinstance(model, str) or not model.strip():
            raise ProtocolError("model must be a non-empty string")

        raw_messages = value.get("messages")
        if not isinstance(raw_messages, list) or not raw_messages:
            raise ProtocolError("messages must be a non-empty array")
        messages = tuple(Message.from_dict(item) for item in raw_messages)

        max_tokens = value.get("max_tokens", 1024)
        if isinstance(max_tokens, bool) or not isinstance(max_tokens, int) or max_tokens < 1:
            raise ProtocolError("max_tokens must be a positive integer")

        temperature = value.get("temperature")
        if temperature is not None:
            if isinstance(temperature, bool) or not isinstance(temperature, (int, float)):
                raise ProtocolError("temperature must be a number")
            temperature = float(temperature)

        response_format = value.get("response_format")
        if response_format is not None and not isinstance(response_format, dict):
            raise ProtocolError("response_format must be an object")

        system = value.get("system")
        if system is not None and not isinstance(system, str):
            raise ProtocolError("system must be a string")

        stream = value.get("stream", False)
        if not isinstance(stream, bool):
            raise ProtocolError("stream must be a boolean")

        return cls(
            model=model.strip(),
            messages=messages,
            system=system,
            max_tokens=max_tokens,
            temperature=temperature,
            stream=stream,
            response_format=response_format,
        )

    def with_values(self, **changes: Any) -> "CompletionRequest":
        values = {
            "model": self.model,
            "messages": self.messages,
            "system": self.system,
            "max_tokens": self.max_tokens,
            "temperature": self.temperature,
            "stream": self.stream,
            "response_format": self.response_format,
        }
        values.update(changes)
        return CompletionRequest(**values)

    def encoded_size(self) -> int:
        body = {
            "model": self.model,
            "messages": [message.to_dict() for message in self.messages],
            "system": self.system,
            "max_tokens": self.max_tokens,
            "temperature": self.temperature,
            "response_format": self.response_format,
        }
        return len(json.dumps(body, ensure_ascii=False).encode("utf-8"))


@dataclass(frozen=True)
class Usage:
    input_tokens: int | None = None
    output_tokens: int | None = None

    def to_dict(self) -> dict[str, int]:
        result: dict[str, int] = {}
        if self.input_tokens is not None:
            result["input_tokens"] = self.input_tokens
        if self.output_tokens is not None:
            result["output_tokens"] = self.output_tokens
        return result


@dataclass(frozen=True)
class CompletionResult:
    model: str
    text: str
    finish_reason: str = "stop"
    usage: Usage = field(default_factory=Usage)

    def to_openai_dict(self, request_id: str) -> dict[str, Any]:
        return {
            "id": request_id,
            "object": "chat.completion",
            "model": self.model,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": self.text},
                    "finish_reason": self.finish_reason,
                }
            ],
            "usage": {
                "prompt_tokens": self.usage.input_tokens or 0,
                "completion_tokens": self.usage.output_tokens or 0,
                "total_tokens": (self.usage.input_tokens or 0) + (self.usage.output_tokens or 0),
            },
        }


@dataclass(frozen=True)
class CompletionDelta:
    text: str = ""
    finish_reason: str | None = None
    usage: Usage = field(default_factory=Usage)
