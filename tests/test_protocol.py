import unittest

from alpine_llm.protocol import CompletionRequest, ProtocolError


class ProtocolTests(unittest.TestCase):
    def test_valid_request(self):
        request = CompletionRequest.from_dict({
            "model": "auto",
            "messages": [{"role": "user", "content": "hello"}],
            "max_tokens": 10,
        })
        self.assertEqual(request.model, "auto")
        self.assertEqual(request.messages[0].role, "user")

    def test_missing_messages_is_rejected(self):
        with self.assertRaises(ProtocolError):
            CompletionRequest.from_dict({"model": "gpt"})

    def test_invalid_temperature_is_rejected(self):
        with self.assertRaises(ProtocolError):
            CompletionRequest.from_dict({
                "messages": [{"role": "user", "content": "hello"}],
                "temperature": "hot",
            })
