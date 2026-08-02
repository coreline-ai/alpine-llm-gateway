"""Provider adapter interfaces and HTTP helpers."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
import json
import random
import threading
import time
from typing import Any, Callable, Iterable, Iterator, Protocol, TypeVar
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from ..protocol import CompletionDelta, CompletionRequest, CompletionResult


DEFAULT_MAX_RESPONSE_BYTES = 8 * 1024 * 1024
DEFAULT_MAX_STREAM_EVENT_BYTES = 1 * 1024 * 1024
DEFAULT_MAX_STREAM_BYTES = 32 * 1024 * 1024
RETRYABLE_STATUS_CODES = frozenset({408, 429, 500, 502, 503, 504})


@dataclass(frozen=True)
class RetryPolicy:
    max_attempts: int = 3
    initial_backoff_seconds: float = 0.5
    max_backoff_seconds: float = 8.0
    jitter_ratio: float = 0.2

    def __post_init__(self) -> None:
        if not 1 <= self.max_attempts <= 10:
            raise ValueError("max_attempts must be between 1 and 10")
        if self.initial_backoff_seconds < 0:
            raise ValueError("initial_backoff_seconds must not be negative")
        if self.max_backoff_seconds < self.initial_backoff_seconds:
            raise ValueError("max_backoff_seconds must be at least initial_backoff_seconds")
        if not 0 <= self.jitter_ratio <= 1:
            raise ValueError("jitter_ratio must be between 0 and 1")

    def delay_seconds(self, failed_attempt: int, retry_after_seconds: float | None = None) -> float:
        if retry_after_seconds is not None:
            return min(self.max_backoff_seconds, max(0.0, retry_after_seconds))
        base = min(
            self.max_backoff_seconds,
            self.initial_backoff_seconds * (2 ** max(0, failed_attempt - 1)),
        )
        spread = base * self.jitter_ratio
        return min(
            self.max_backoff_seconds,
            max(0.0, random.uniform(base - spread, base + spread)),
        )


class ProviderError(RuntimeError):
    def __init__(
        self,
        message: str,
        *,
        status_code: int | None = None,
        retryable: bool = False,
        retry_after_seconds: float | None = None,
    ):
        super().__init__(message)
        self.status_code = status_code
        self.retryable = retryable
        self.retry_after_seconds = retry_after_seconds


class ProviderCircuitBreaker:
    """Thread-safe consecutive-failure circuit breaker for one Provider instance."""

    def __init__(
        self,
        failure_threshold: int = 5,
        recovery_timeout_seconds: float = 30.0,
        *,
        clock: Callable[[], float] | None = None,
    ):
        if failure_threshold <= 0:
            raise ValueError("failure_threshold must be positive")
        if recovery_timeout_seconds <= 0:
            raise ValueError("recovery_timeout_seconds must be positive")
        self.failure_threshold = failure_threshold
        self.recovery_timeout_seconds = recovery_timeout_seconds
        self._clock = clock or time.monotonic
        self._lock = threading.Lock()
        self._state = "closed"
        self._failures = 0
        self._opened_at: float | None = None

    @property
    def state(self) -> str:
        with self._lock:
            return self._state

    def before_request(self) -> None:
        with self._lock:
            if self._state == "closed":
                return
            now = self._clock()
            if self._state == "open":
                assert self._opened_at is not None
                if now - self._opened_at >= self.recovery_timeout_seconds:
                    self._state = "half-open"
                    return
            raise ProviderError("provider circuit is open", retryable=True)

    def record_success(self) -> None:
        with self._lock:
            self._state = "closed"
            self._failures = 0
            self._opened_at = None

    def record_failure(self, *, retryable: bool) -> None:
        with self._lock:
            if not retryable:
                self._state = "closed"
                self._failures = 0
                self._opened_at = None
                return
            if self._state == "half-open":
                self._open_locked()
                return
            self._failures += 1
            if self._failures >= self.failure_threshold:
                self._open_locked()

    def _open_locked(self) -> None:
        self._state = "open"
        self._opened_at = self._clock()


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
    max_response_bytes: int = DEFAULT_MAX_RESPONSE_BYTES,
    retry_policy: RetryPolicy | None = None,
    circuit_breaker: ProviderCircuitBreaker | None = None,
) -> dict[str, Any]:
    encoded_body = json.dumps(body, ensure_ascii=False).encode("utf-8")

    def attempt() -> bytes:
        request = Request(url, data=encoded_body, headers=headers, method="POST")
        try:
            with urlopen(request, timeout=timeout) as response:
                return _read_limited(response, max_response_bytes)
        except HTTPError as error:
            retryable = error.code in RETRYABLE_STATUS_CODES
            try:
                raw = _read_limited(error, max_response_bytes)
            except ProviderError as limit_error:
                raise ProviderError(str(limit_error), status_code=error.code) from error
            finally:
                error.close()
            raise ProviderError(
                _error_text(raw, error.reason),
                status_code=error.code,
                retryable=retryable,
                retry_after_seconds=_retry_after_seconds(error.headers),
            ) from error
        except (URLError, TimeoutError, OSError) as error:
            raise ProviderError("provider connection failed", retryable=True) from error

    raw = _with_retries(
        attempt,
        retry_policy=retry_policy,
        circuit_breaker=circuit_breaker,
    )
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
    max_event_bytes: int = DEFAULT_MAX_STREAM_EVENT_BYTES,
    max_stream_bytes: int = DEFAULT_MAX_STREAM_BYTES,
    max_error_bytes: int = DEFAULT_MAX_RESPONSE_BYTES,
    retry_policy: RetryPolicy | None = None,
    circuit_breaker: ProviderCircuitBreaker | None = None,
) -> Iterable[tuple[str | None, str]]:
    encoded_body = json.dumps(body, ensure_ascii=False).encode("utf-8")

    def attempt() -> Any:
        request = Request(url, data=encoded_body, headers=headers, method="POST")
        try:
            return urlopen(request, timeout=timeout)
        except HTTPError as error:
            retryable = error.code in RETRYABLE_STATUS_CODES
            try:
                raw = _read_limited(error, max_error_bytes)
            except ProviderError as limit_error:
                raise ProviderError(str(limit_error), status_code=error.code) from error
            finally:
                error.close()
            raise ProviderError(
                _error_text(raw, error.reason),
                status_code=error.code,
                retryable=retryable,
                retry_after_seconds=_retry_after_seconds(error.headers),
            ) from error
        except (URLError, TimeoutError, OSError) as error:
            raise ProviderError("provider connection failed", retryable=True) from error

    response = _with_retries(
        attempt,
        retry_policy=retry_policy,
        circuit_breaker=circuit_breaker,
    )

    def events() -> Iterator[tuple[str | None, str]]:
        event_name: str | None = None
        data_lines: list[str] = []
        event_bytes = 0
        total_bytes = 0
        try:
            try:
                while True:
                    raw_line = response.readline(max_event_bytes + 1)
                    if not raw_line:
                        break
                    total_bytes += len(raw_line)
                    event_bytes += len(raw_line)
                    if len(raw_line) > max_event_bytes or event_bytes > max_event_bytes:
                        raise ProviderError("provider stream event exceeds limit")
                    if total_bytes > max_stream_bytes:
                        raise ProviderError("provider stream exceeds limit")
                    try:
                        line = raw_line.decode("utf-8", errors="strict").rstrip("\r\n")
                    except UnicodeDecodeError as error:
                        raise ProviderError("provider stream contains invalid UTF-8") from error
                    if not line:
                        if data_lines:
                            yield event_name, "\n".join(data_lines)
                        event_name = None
                        data_lines = []
                        event_bytes = 0
                        continue
                    if line.startswith("event:"):
                        event_name = line[6:].strip()
                    elif line.startswith("data:"):
                        data_lines.append(line[5:].lstrip())
            except (URLError, TimeoutError, OSError) as error:
                raise ProviderError("provider stream connection failed", retryable=True) from error
            if data_lines:
                yield event_name, "\n".join(data_lines)
        except ProviderError as error:
            if circuit_breaker is not None:
                circuit_breaker.record_failure(retryable=error.retryable)
            raise
        finally:
            response.close()

    return events()


T = TypeVar("T")


def _with_retries(
    operation: Callable[[], T],
    *,
    retry_policy: RetryPolicy | None,
    circuit_breaker: ProviderCircuitBreaker | None,
) -> T:
    policy = retry_policy or RetryPolicy(max_attempts=1)
    if circuit_breaker is not None:
        circuit_breaker.before_request()
    for attempt_number in range(1, policy.max_attempts + 1):
        try:
            result = operation()
        except ProviderError as error:
            if not error.retryable or attempt_number >= policy.max_attempts:
                if circuit_breaker is not None:
                    circuit_breaker.record_failure(retryable=error.retryable)
                raise
            time.sleep(policy.delay_seconds(attempt_number, error.retry_after_seconds))
        else:
            if circuit_breaker is not None:
                circuit_breaker.record_success()
            return result
    raise AssertionError("retry loop exited unexpectedly")


def _retry_after_seconds(headers: Any, *, now: datetime | None = None) -> float | None:
    if headers is None:
        return None
    value = headers.get("Retry-After")
    if value is None:
        return None
    value = str(value).strip()
    try:
        return max(0.0, float(value))
    except ValueError:
        pass
    try:
        target = parsedate_to_datetime(value)
        if target is None:
            return None
        if target.tzinfo is None:
            target = target.replace(tzinfo=timezone.utc)
        current = now or datetime.now(timezone.utc)
        return max(0.0, (target - current).total_seconds())
    except (TypeError, ValueError, OverflowError):
        return None


def _read_limited(input_stream: Any, limit: int) -> bytes:
    if limit <= 0:
        raise ValueError("response limit must be positive")
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = input_stream.read(min(64 * 1024, limit - total + 1))
        if not chunk:
            break
        total += len(chunk)
        if total > limit:
            raise ProviderError("provider response exceeds limit")
        chunks.append(chunk)
    return b"".join(chunks)


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
