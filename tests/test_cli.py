import unittest

from alpine_llm.cli import _headers


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


if __name__ == "__main__":
    unittest.main()
