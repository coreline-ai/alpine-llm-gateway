import unittest

from alpine_llm.config import Settings
from alpine_llm.protocol import CompletionRequest
from alpine_llm.providers.anthropic import AnthropicProvider
from alpine_llm.providers.factory import create_provider
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

    def test_factory_applies_provider_response_limits(self):
        provider = create_provider(Settings(
            default_model="test-model",
            max_response_bytes=101,
            max_stream_event_bytes=102,
            max_stream_bytes=103,
            provider_retry_max_attempts=4,
            provider_retry_initial_backoff_seconds=1.5,
            provider_retry_max_backoff_seconds=6.0,
            provider_retry_jitter_ratio=0.1,
            provider_circuit_failure_threshold=7,
            provider_circuit_recovery_seconds=45,
        ))
        self.assertEqual(101, provider.max_response_bytes)
        self.assertEqual(102, provider.max_stream_event_bytes)
        self.assertEqual(103, provider.max_stream_bytes)
        self.assertEqual(4, provider.retry_policy.max_attempts)
        self.assertEqual(1.5, provider.retry_policy.initial_backoff_seconds)
        self.assertEqual(6.0, provider.retry_policy.max_backoff_seconds)
        self.assertEqual(0.1, provider.retry_policy.jitter_ratio)
        self.assertEqual(7, provider.circuit_breaker.failure_threshold)
        self.assertEqual(45, provider.circuit_breaker.recovery_timeout_seconds)

    def test_factory_builds_all_supported_provider_adapters(self):
        cases = (
            ("openai-compatible", OpenAICompatibleProvider),
            ("anthropic", AnthropicProvider),
            ("gemini", GeminiProvider),
        )
        for provider_name, provider_type in cases:
            with self.subTest(provider=provider_name):
                provider = create_provider(Settings(
                    provider=provider_name,
                    default_model="test-model",
                    provider_retry_max_attempts=2,
                ))
                self.assertIsInstance(provider, provider_type)
                self.assertEqual(2, provider.retry_policy.max_attempts)
                self.assertEqual("closed", provider.circuit_breaker.state)
