"""Local HTTP gateway server."""

from __future__ import annotations

from dataclasses import dataclass
import json
import secrets
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Iterator

from . import __protocol_version__, __version__
from .config import Settings
from .policy import Policy, PolicyError
from .protocol import CompletionDelta, CompletionRequest, ProtocolError
from .providers.base import ProviderError
from .providers.factory import create_provider


@dataclass
class GatewayService:
    settings: Settings

    def __post_init__(self) -> None:
        self.policy = Policy(
            allowed_models=self.settings.allowed_models,
            default_model=self.settings.default_model,
            allow_passthrough=self.settings.allow_passthrough,
            max_input_bytes=self.settings.max_input_bytes,
            max_output_tokens=self.settings.max_output_tokens,
            max_messages=self.settings.max_messages,
        )
        self.provider = create_provider(self.settings)

    def validate(self, request: CompletionRequest) -> CompletionRequest:
        return self.policy.validate(request)

    def complete(self, request: CompletionRequest):
        return self.provider.complete(self.validate(request))

    def stream(self, request: CompletionRequest) -> Iterator[CompletionDelta]:
        return self.provider.stream(self.validate(request))

    def models(self) -> list[dict[str, Any]]:
        if self.settings.model_catalog:
            return [dict(item) for item in self.settings.model_catalog]
        return [
            {
                "id": model,
                "display_name": model,
                "provider": self.settings.provider,
                "modalities": ["text_input", "text_output"],
            }
            for model in self.settings.allowed_models
        ]


def make_handler(service: GatewayService):
    class Handler(BaseHTTPRequestHandler):
        server_version = f"AlpineLLMGateway/{__version__}"

        def log_message(self, format: str, *args: Any) -> None:
            return

        def do_GET(self) -> None:  # noqa: N802
            if self.path == "/healthz":
                self._json(200, {
                    "status": "ok",
                    "provider": service.settings.provider,
                    "version": __version__,
                    "protocol_version": __protocol_version__,
                })
                return
            if self.path == "/v1/models":
                self._json(200, {"object": "list", "data": service.models()})
                return
            self._json(404, {"error": {"code": "not_found", "message": "route not found"}})

        def do_POST(self) -> None:  # noqa: N802
            if self.path != "/v1/chat/completions":
                self._json(404, {"error": {"code": "not_found", "message": "route not found"}})
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                self._json(400, {"error": {
                    "code": "invalid_request",
                    "message": "Content-Length must be an integer",
                }})
                return
            if length <= 0 or length > service.settings.max_input_bytes * 2:
                self._json(413, {"error": {"code": "request_too_large", "message": "request body is too large"}})
                return
            try:
                raw = self.rfile.read(length)
                request = service.validate(
                    CompletionRequest.from_dict(json.loads(raw.decode("utf-8"))),
                )
                if request.stream:
                    self._stream(request)
                else:
                    result = service.provider.complete(request)
                    self._json(200, result.to_openai_dict("chatcmpl_" + secrets.token_hex(8)))
            except (json.JSONDecodeError, UnicodeDecodeError, ProtocolError, PolicyError) as error:
                self._json(400, {"error": {"code": "invalid_request", "message": str(error)}})
            except ProviderError as error:
                status = error.status_code if error.status_code and 400 <= error.status_code < 600 else 502
                self._json(status, {"error": {
                    "code": "provider_error",
                    "message": "provider request failed",
                    "retryable": error.retryable,
                }})
            except Exception:  # pragma: no cover - defensive server guard
                self._json(500, {"error": {
                    "code": "internal_error",
                    "message": "internal gateway error",
                }})

        def _stream(self, request: CompletionRequest) -> None:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            request_id = "chatcmpl_" + secrets.token_hex(8)
            try:
                self._event({"id": request_id, "type": "start", "model": request.model})
                for delta in service.provider.stream(request):
                    self._event({
                        "id": request_id,
                        "type": "delta",
                        "text": delta.text,
                        "finish_reason": delta.finish_reason,
                        "usage": delta.usage.to_dict(),
                    })
                self._event({"id": request_id, "type": "done", "finish_reason": "stop"})
                self._stream_done()
            except ProviderError as error:
                self._stream_error(
                    request_id,
                    code="provider_error",
                    message="provider stream failed",
                    retryable=error.retryable,
                )
            except (BrokenPipeError, ConnectionResetError):
                return
            except Exception:
                self._stream_error(
                    request_id,
                    code="internal_error",
                    message="gateway stream failed",
                    retryable=False,
                )

        def _event(self, value: dict[str, Any]) -> None:
            payload = ("data: " + json.dumps(value, ensure_ascii=False) + "\n\n").encode("utf-8")
            self.wfile.write(payload)
            self.wfile.flush()

        def _stream_done(self) -> None:
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()

        def _stream_error(
            self,
            request_id: str,
            *,
            code: str,
            message: str,
            retryable: bool,
        ) -> None:
            try:
                self._event({
                    "id": request_id,
                    "type": "error",
                    "code": code,
                    "message": message,
                    "retryable": retryable,
                })
                self._stream_done()
            except OSError:
                return

        def _json(self, status: int, value: dict[str, Any]) -> None:
            payload = json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

    return Handler


def serve(settings: Settings) -> None:
    service = GatewayService(settings)
    server = ThreadingHTTPServer((settings.host, settings.port), make_handler(service))
    print(f"alpine-llm-gatewayd listening on http://{settings.host}:{settings.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
