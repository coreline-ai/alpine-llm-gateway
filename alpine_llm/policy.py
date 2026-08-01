"""Request validation and model allowlisting."""

from __future__ import annotations

from dataclasses import dataclass

from .protocol import CompletionRequest, ProtocolError


class PolicyError(ValueError):
    """Raised when a request violates the local gateway policy."""


@dataclass(frozen=True)
class Policy:
    allowed_models: tuple[str, ...]
    default_model: str
    allow_passthrough: bool = False
    max_input_bytes: int = 1_048_576
    max_output_tokens: int = 4096
    max_messages: int = 64

    def resolve_model(self, requested: str) -> str:
        model = self.default_model if requested == "auto" else requested
        if not model:
            raise PolicyError("no default model is configured")
        configured_models = set(self.allowed_models)
        if self.default_model:
            configured_models.add(self.default_model)
        if model not in configured_models and not self.allow_passthrough:
            raise PolicyError(f"model '{model}' is not allowed")
        return model

    def validate(self, request: CompletionRequest) -> CompletionRequest:
        if len(request.messages) > self.max_messages:
            raise PolicyError(f"too many messages; maximum is {self.max_messages}")
        if request.encoded_size() > self.max_input_bytes:
            raise PolicyError(f"request is too large; maximum is {self.max_input_bytes} bytes")
        if request.temperature is not None and not 0.0 <= request.temperature <= 2.0:
            raise ProtocolError("temperature must be between 0 and 2")
        model = self.resolve_model(request.model)
        return request.with_values(
            model=model,
            max_tokens=min(request.max_tokens, self.max_output_tokens),
        )
