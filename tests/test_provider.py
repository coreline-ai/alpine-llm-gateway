import unittest

from alpine_llm.protocol import CompletionRequest
from alpine_llm.providers.anthropic import AnthropicProvider
from alpine_llm.providers.gemini import GeminiProvider
from alpine_llm.providers.openai_compatible import OpenAICompatibleProvider


class ProviderRequestTests(unittest.TestCase):
    def setUp(self):
        self.request = CompletionRequest.from_dict({
            "model": "test-model",
            "system": "be concise",
            "messages": [{"role": "user", "content": "hello"}],
            "max_tokens": 10,
            "temperature": 0.2,
        })

    def test_openai_body(self):
        body = OpenAICompatibleProvider._body(self.request, stream=True)
        self.assertEqual(body["messages"][0]["role"], "system")
        self.assertTrue(body["stream"])

    def test_anthropic_body(self):
        body = AnthropicProvider._body(self.request, stream=False)
        self.assertEqual(body["system"], "be concise")
        self.assertEqual(body["messages"][0]["role"], "user")

    def test_gemini_body(self):
        body = GeminiProvider._body(self.request)
        self.assertEqual(body["systemInstruction"]["parts"][0]["text"], "be concise")
        self.assertEqual(body["contents"][0]["role"], "user")
