"""Provider adapter interfaces and HTTP helpers."""

from __future__ import annotations

from dataclasses import dataclass
import json
from typing import Any, Iterable, Iterator, Protocol
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from ..protocol import CompletionDelta, CompletionRequest, CompletionResult


class ProviderError(RuntimeError):
    def __init__(self, message: str, *, status_code: int | None = None, retryable: bool = False):
        super().__init__(message)
        self.status_code = status_code
        self.retryable = retryable


class Provider(Protocol):
    def complete(self, request: CompletionRequest) -> CompletionResult:
        ...

    def stream(self, request: CompletionRequest) -> Iterator[CompletionDelta]:
        ...


def json_request(
    url: str,
    *,
    headers: dict[str, str],
    body: dict[str, Any],
    timeout: float,
) -> dict[str, Any]:
    request = Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read()
    except HTTPError as error:
        raw = error.read()
        raise ProviderError(_error_text(raw, error.reason), status_code=error.code, retryable=error.code >= 500) from error
    except URLError as error:
        raise ProviderError(f"provider connection failed: {error.reason}", retryable=True) from error
    try:
        return json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ProviderError("provider returned invalid JSON") from error


def stream_request(
    url: str,
    *,
    headers: dict[str, str],
    body: dict[str, Any],
    timeout: float,
) -> Iterable[tuple[str | None, str]]:
    request = Request(
        url,
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers=headers,
        method="POST",
    )
    try:
        response = urlopen(request, timeout=timeout)
    except HTTPError as error:
        raw = error.read()
        raise ProviderError(_error_text(raw, error.reason), status_code=error.code, retryable=error.code >= 500) from error
    except URLError as error:
        raise ProviderError(f"provider connection failed: {error.reason}", retryable=True) from error

    def events() -> Iterator[tuple[str | None, str]]:
        event_name: str | None = None
        data_lines: list[str] = []
        try:
            for raw_line in response:
                line = raw_line.decode("utf-8", errors="replace").rstrip("\r\n")
                if not line:
                    if data_lines:
                        yield event_name, "\n".join(data_lines)
                    event_name = None
                    data_lines = []
                    continue
                if line.startswith("event:"):
                    event_name = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].lstrip())
            if data_lines:
                yield event_name, "\n".join(data_lines)
        finally:
            response.close()

    return events()


def _error_text(raw: bytes, fallback: str) -> str:
    try:
        value = json.loads(raw.decode("utf-8"))
        if isinstance(value, dict):
            error = value.get("error", value)
            if isinstance(error, dict) and error.get("message"):
                return str(error["message"])
        return json.dumps(value, ensure_ascii=False)
    except Exception:
        return raw.decode("utf-8", errors="replace") or str(fallback)


def text_content(value: Any) -> str:
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        parts: list[str] = []
        for item in value:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
        return "".join(parts)
    return ""
