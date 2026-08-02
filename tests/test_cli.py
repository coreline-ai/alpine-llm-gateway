import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import BytesIO, StringIO
from unittest.mock import patch

from pathlib import Path
import tempfile

from alpine_llm.cli import _headers, _resolve_session_token, _stream_request


class FakeResponse(BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()


class CliHeaderTests(unittest.TestCase):
    def test_session_token_becomes_bearer_authorization(self):
        self.assertEqual(
            {
                "Content-Type": "application/json",
                "Accept": "text/event-stream",
                "Authorization": "Bearer bridge-capability",
            },
            _headers("bridge-capability", accept="text/event-stream"),
        )

    def test_no_authorization_without_session_token(self):
        self.assertNotIn("Authorization", _headers(None))

    def test_session_token_can_be_resolved_from_capability_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "bridge.capability")
            path.write_text("file-capability\n", encoding="utf-8")
            self.assertEqual("file-capability", _resolve_session_token(None, str(path)))

    def test_stream_error_event_returns_failure(self):
        response = FakeResponse(
            b'data: {"type":"start"}\n\n'
            b'data: {"type":"error","message":"provider stream failed"}\n\n'
            b'data: [DONE]\n\n'
        )
        stdout = StringIO()
        stderr = StringIO()
        with patch("alpine_llm.cli.urlopen", return_value=response):
            with redirect_stdout(stdout), redirect_stderr(stderr):
                status = _stream_request(
                    "http://127.0.0.1:8787/v1/chat/completions",
                    {"model": "fake", "messages": []},
                    "jsonl",
                    None,
                    None,
                )
        self.assertEqual(1, status)
        self.assertIn('"type": "error"', stdout.getvalue())
        self.assertIn("gateway stream failed", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
