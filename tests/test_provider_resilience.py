from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from email.utils import format_datetime
from io import BytesIO
from threading import Barrier
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

from alpine_llm.providers.base import (
    ProviderCircuitBreaker,
    ProviderError,
    RetryPolicy,
    _retry_after_seconds,
    json_request,
    stream_request,
)


class FakeResponse(BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()


class BrokenStream:
    def __init__(self):
        self.closed = False

    def readline(self, limit):
        raise URLError("stream disconnected with provider-secret")

    def close(self):
        self.closed = True


def http_error(code: int, *, headers=None, body=b'{"error":{"message":"temporary"}}'):
    return HTTPError(
        "https://provider.example/completions",
        code,
        "Provider Error",
        headers or {},
        BytesIO(body),
    )


class ProviderRetryTests(unittest.TestCase):
    def setUp(self):
        self.no_wait_policy = RetryPolicy(
            max_attempts=3,
            initial_backoff_seconds=0,
            max_backoff_seconds=0,
            jitter_ratio=0,
        )

    def test_retryable_status_is_retried_until_success(self):
        with (
            patch(
                "alpine_llm.providers.base.urlopen",
                side_effect=[http_error(503), FakeResponse(b'{"ok":true}')],
            ) as open_request,
            patch("alpine_llm.providers.base.time.sleep") as sleep,
        ):
            response = json_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                retry_policy=self.no_wait_policy,
            )
        self.assertEqual({"ok": True}, response)
        self.assertEqual(2, open_request.call_count)
        sleep.assert_called_once_with(0)

    def test_non_retryable_status_is_not_retried(self):
        for status in (400, 401, 403, 404, 501):
            with (
                self.subTest(status=status),
                patch(
                    "alpine_llm.providers.base.urlopen",
                    side_effect=http_error(status),
                ) as open_request,
            ):
                with self.assertRaises(ProviderError) as raised:
                    json_request(
                        "https://provider.example/completions",
                        headers={},
                        body={},
                        timeout=1,
                        retry_policy=self.no_wait_policy,
                    )
                self.assertFalse(raised.exception.retryable)
                self.assertEqual(1, open_request.call_count)

    def test_supported_retryable_statuses_are_retried(self):
        for status in (408, 429, 500, 502, 503, 504):
            with (
                self.subTest(status=status),
                patch(
                    "alpine_llm.providers.base.urlopen",
                    side_effect=[http_error(status), FakeResponse(b'{"ok":true}')],
                ) as open_request,
                patch("alpine_llm.providers.base.time.sleep"),
            ):
                response = json_request(
                    "https://provider.example/completions",
                    headers={},
                    body={},
                    timeout=1,
                    retry_policy=self.no_wait_policy,
                )
                self.assertEqual({"ok": True}, response)
                self.assertEqual(2, open_request.call_count)

    def test_retry_after_is_bounded_by_max_backoff(self):
        policy = RetryPolicy(
            max_attempts=2,
            initial_backoff_seconds=0.5,
            max_backoff_seconds=4,
            jitter_ratio=0,
        )
        with (
            patch(
                "alpine_llm.providers.base.urlopen",
                side_effect=[
                    http_error(429, headers={"Retry-After": "30"}),
                    FakeResponse(b'{"ok":true}'),
                ],
            ),
            patch("alpine_llm.providers.base.time.sleep") as sleep,
        ):
            json_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                retry_policy=policy,
            )
        sleep.assert_called_once_with(4)

    def test_retry_after_http_date_is_parsed(self):
        now = datetime(2026, 7, 31, 12, 0, tzinfo=timezone.utc)
        headers = {"Retry-After": format_datetime(now + timedelta(seconds=7))}
        self.assertEqual(7, _retry_after_seconds(headers, now=now))
        self.assertIsNone(_retry_after_seconds({"Retry-After": "not-a-date"}, now=now))

    def test_response_limit_and_invalid_json_are_not_retried(self):
        cases = (
            (FakeResponse(b'{"too":"large"}'), 4, "response exceeds limit"),
            (FakeResponse(b"not-json"), 1024, "invalid JSON"),
        )
        for response, limit, message in cases:
            with (
                self.subTest(message=message),
                patch(
                    "alpine_llm.providers.base.urlopen",
                    return_value=response,
                ) as open_request,
            ):
                with self.assertRaisesRegex(ProviderError, message):
                    json_request(
                        "https://provider.example/completions",
                        headers={},
                        body={},
                        timeout=1,
                        max_response_bytes=limit,
                        retry_policy=self.no_wait_policy,
                    )
                self.assertEqual(1, open_request.call_count)

    def test_stream_retries_only_while_opening(self):
        with (
            patch(
                "alpine_llm.providers.base.urlopen",
                side_effect=[http_error(502), FakeResponse(b"data: one\n\n")],
            ) as open_request,
            patch("alpine_llm.providers.base.time.sleep"),
        ):
            events = list(stream_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                retry_policy=self.no_wait_policy,
            ))
        self.assertEqual([(None, "one")], events)
        self.assertEqual(2, open_request.call_count)

    def test_stream_disconnect_after_open_is_not_retried_or_leaked(self):
        stream = BrokenStream()
        with patch(
            "alpine_llm.providers.base.urlopen",
            return_value=stream,
        ) as open_request:
            events = stream_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                retry_policy=self.no_wait_policy,
            )
            with self.assertRaises(ProviderError) as raised:
                list(events)
        self.assertTrue(raised.exception.retryable)
        self.assertNotIn("provider-secret", str(raised.exception))
        self.assertEqual(1, open_request.call_count)
        self.assertTrue(stream.closed)


class ProviderCircuitBreakerTests(unittest.TestCase):
    def test_circuit_opens_after_consecutive_exhausted_requests(self):
        breaker = ProviderCircuitBreaker(failure_threshold=2, recovery_timeout_seconds=30)
        policy = RetryPolicy(
            max_attempts=1,
            initial_backoff_seconds=0,
            max_backoff_seconds=0,
            jitter_ratio=0,
        )
        with patch(
            "alpine_llm.providers.base.urlopen",
            side_effect=[http_error(503), http_error(503)],
        ) as open_request:
            for _ in range(2):
                with self.assertRaises(ProviderError):
                    json_request(
                        "https://provider.example/completions",
                        headers={},
                        body={},
                        timeout=1,
                        retry_policy=policy,
                        circuit_breaker=breaker,
                    )
            self.assertEqual("open", breaker.state)
            with self.assertRaisesRegex(ProviderError, "circuit is open"):
                json_request(
                    "https://provider.example/completions",
                    headers={},
                    body={},
                    timeout=1,
                    retry_policy=policy,
                    circuit_breaker=breaker,
                )
        self.assertEqual(2, open_request.call_count)

    def test_recovery_allows_one_half_open_probe(self):
        now = [0.0]
        breaker = ProviderCircuitBreaker(
            failure_threshold=1,
            recovery_timeout_seconds=5,
            clock=lambda: now[0],
        )
        breaker.record_failure(retryable=True)
        self.assertEqual("open", breaker.state)

        now[0] = 5.0
        breaker.before_request()
        self.assertEqual("half-open", breaker.state)
        with self.assertRaisesRegex(ProviderError, "circuit is open"):
            breaker.before_request()

        breaker.record_success()
        self.assertEqual("closed", breaker.state)
        breaker.before_request()

    def test_failed_half_open_probe_reopens_circuit(self):
        now = [0.0]
        breaker = ProviderCircuitBreaker(
            failure_threshold=1,
            recovery_timeout_seconds=5,
            clock=lambda: now[0],
        )
        breaker.record_failure(retryable=True)
        now[0] = 5.0
        breaker.before_request()
        breaker.record_failure(retryable=True)
        self.assertEqual("open", breaker.state)

    def test_only_one_concurrent_half_open_probe_is_allowed(self):
        now = [0.0]
        breaker = ProviderCircuitBreaker(
            failure_threshold=1,
            recovery_timeout_seconds=5,
            clock=lambda: now[0],
        )
        breaker.record_failure(retryable=True)
        now[0] = 5.0
        barrier = Barrier(8)

        def try_probe():
            barrier.wait()
            try:
                breaker.before_request()
                return True
            except ProviderError:
                return False

        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(lambda _: try_probe(), range(8)))
        self.assertEqual(1, sum(results))
        self.assertEqual("half-open", breaker.state)

    def test_stream_disconnect_counts_as_circuit_failure_without_retry(self):
        breaker = ProviderCircuitBreaker(failure_threshold=1, recovery_timeout_seconds=30)
        policy = RetryPolicy(
            max_attempts=3,
            initial_backoff_seconds=0,
            max_backoff_seconds=0,
            jitter_ratio=0,
        )
        with patch(
            "alpine_llm.providers.base.urlopen",
            return_value=BrokenStream(),
        ) as open_request:
            events = stream_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                retry_policy=policy,
                circuit_breaker=breaker,
            )
            with self.assertRaises(ProviderError):
                list(events)
            self.assertEqual("open", breaker.state)
            with self.assertRaisesRegex(ProviderError, "circuit is open"):
                stream_request(
                    "https://provider.example/completions",
                    headers={},
                    body={},
                    timeout=1,
                    retry_policy=policy,
                    circuit_breaker=breaker,
                )
        self.assertEqual(1, open_request.call_count)


if __name__ == "__main__":
    unittest.main()
