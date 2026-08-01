from io import BytesIO
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from alpine_llm.providers.base import ProviderError, json_request, stream_request


class FakeResponse(BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()


class ProviderLimitTests(unittest.TestCase):
    def test_json_response_at_exact_limit_is_allowed(self):
        raw = b'{"a":1}'
        response = FakeResponse(raw)
        with patch("alpine_llm.providers.base.urlopen", return_value=response):
            value = json_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                max_response_bytes=len(raw),
            )
        self.assertEqual({"a": 1}, value)

    def test_json_response_limit_is_enforced(self):
        response = FakeResponse(b'{"value":"too large"}')
        with patch("alpine_llm.providers.base.urlopen", return_value=response):
            with self.assertRaisesRegex(ProviderError, "response exceeds limit"):
                json_request(
                    "https://provider.example/completions",
                    headers={},
                    body={},
                    timeout=1,
                    max_response_bytes=8,
                )

    def test_http_error_body_limit_preserves_status_without_body(self):
        error = HTTPError(
            "https://provider.example/completions",
            503,
            "Unavailable",
            {},
            BytesIO(b'provider-secret-error-body'),
        )
        with patch("alpine_llm.providers.base.urlopen", side_effect=error):
            with self.assertRaises(ProviderError) as raised:
                json_request(
                    "https://provider.example/completions",
                    headers={},
                    body={},
                    timeout=1,
                    max_response_bytes=8,
                )
        self.assertEqual(503, raised.exception.status_code)
        self.assertNotIn("provider-secret", str(raised.exception))

    def test_multiline_sse_is_parsed_within_limits(self):
        response = FakeResponse(b"event: message\ndata: one\ndata: two\n\n")
        with patch("alpine_llm.providers.base.urlopen", return_value=response):
            events = list(stream_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                max_event_bytes=64,
                max_stream_bytes=128,
            ))
        self.assertEqual([("message", "one\ntwo")], events)

    def test_sse_at_exact_event_and_total_limit_is_allowed(self):
        raw = b"data: one\n\n"
        response = FakeResponse(raw)
        with patch("alpine_llm.providers.base.urlopen", return_value=response):
            events = list(stream_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                max_event_bytes=len(raw),
                max_stream_bytes=len(raw),
            ))
        self.assertEqual([(None, "one")], events)

    def test_sse_event_limit_is_enforced(self):
        response = FakeResponse(b"data: 1234\ndata: 5678\n\n")
        with patch("alpine_llm.providers.base.urlopen", return_value=response):
            events = stream_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                max_event_bytes=20,
                max_stream_bytes=128,
            )
            with self.assertRaisesRegex(ProviderError, "event exceeds limit"):
                list(events)

    def test_sse_total_limit_is_enforced(self):
        response = FakeResponse(b"data: one\n\ndata: two\n\n")
        with patch("alpine_llm.providers.base.urlopen", return_value=response):
            events = stream_request(
                "https://provider.example/completions",
                headers={},
                body={},
                timeout=1,
                max_event_bytes=64,
                max_stream_bytes=16,
            )
            with self.assertRaisesRegex(ProviderError, "stream exceeds limit"):
                list(events)


if __name__ == "__main__":
    unittest.main()
