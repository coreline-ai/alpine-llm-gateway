import unittest

from alpine_llm.policy import Policy, PolicyError
from alpine_llm.protocol import CompletionRequest


class PolicyTests(unittest.TestCase):
    def setUp(self):
        self.policy = Policy(
            allowed_models=("gpt-test",),
            default_model="gpt-test",
            max_input_bytes=2000,
            max_output_tokens=100,
            max_messages=2,
        )

    def test_auto_resolves_to_default(self):
        request = CompletionRequest.from_dict({
            "model": "auto",
            "messages": [{"role": "user", "content": "hello"}],
            "max_tokens": 1000,
        })
        validated = self.policy.validate(request)
        self.assertEqual(validated.model, "gpt-test")
        self.assertEqual(validated.max_tokens, 100)

    def test_unknown_model_is_rejected(self):
        request = CompletionRequest.from_dict({
            "model": "secret-model",
            "messages": [{"role": "user", "content": "hello"}],
        })
        with self.assertRaises(PolicyError):
            self.policy.validate(request)

    def test_message_limit_is_enforced(self):
        request = CompletionRequest.from_dict({
            "messages": [
                {"role": "user", "content": "one"},
                {"role": "assistant", "content": "two"},
                {"role": "user", "content": "three"},
            ]
        })
        with self.assertRaises(PolicyError):
            self.policy.validate(request)

    def test_empty_allowlist_rejects_unknown_model_by_default(self):
        policy = Policy(allowed_models=(), default_model="gpt-default")
        request = CompletionRequest.from_dict({
            "model": "gpt-other",
            "messages": [{"role": "user", "content": "hello"}],
        })
        with self.assertRaises(PolicyError):
            policy.validate(request)

    def test_default_model_is_allowed_without_explicit_allowlist(self):
        policy = Policy(allowed_models=(), default_model="gpt-default")
        request = CompletionRequest.from_dict({
            "model": "auto",
            "messages": [{"role": "user", "content": "hello"}],
        })
        self.assertEqual("gpt-default", policy.validate(request).model)

    def test_passthrough_explicitly_allows_unknown_model(self):
        policy = Policy(
            allowed_models=(),
            default_model="gpt-default",
            allow_passthrough=True,
        )
        request = CompletionRequest.from_dict({
            "model": "gpt-dynamic",
            "messages": [{"role": "user", "content": "hello"}],
        })
        self.assertEqual("gpt-dynamic", policy.validate(request).model)
