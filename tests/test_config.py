import json
import os
from pathlib import Path
import tempfile
import unittest

from alpine_llm.config import Settings


class SettingsTests(unittest.TestCase):
    def test_capability_can_be_loaded_from_a_bounded_file(self):
        with tempfile.TemporaryDirectory() as directory:
            capability = Path(directory, "bridge.capability")
            capability.write_text("short-lived-capability\n", encoding="utf-8")
            config = Path(directory, "config.json")
            config.write_text(json.dumps({"api_key_file": str(capability)}), encoding="utf-8")

            settings = Settings.from_file(str(config))

            self.assertEqual("short-lived-capability", settings.api_key)

    def test_capability_file_takes_precedence_without_exporting_oauth_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            capability = Path(directory, "bridge.capability")
            capability.write_text("bridge-only", encoding="utf-8")
            config = Path(directory, "config.json")
            config.write_text(json.dumps({"api_key_file": str(capability)}), encoding="utf-8")
            with unittest.mock.patch.dict(os.environ, {"LLM_API_KEY": "upstream-oauth-token"}):
                settings = Settings.from_file(str(config))

            self.assertEqual("bridge-only", settings.api_key)

    def test_capability_file_rejects_empty_multiline_and_oversized_values(self):
        with tempfile.TemporaryDirectory() as directory:
            capability = Path(directory, "bridge.capability")
            config = Path(directory, "config.json")
            config.write_text(json.dumps({"api_key_file": str(capability)}), encoding="utf-8")
            for value in (b"", b"first\nsecond", b"x" * (8 * 1024 + 1)):
                with self.subTest(size=len(value)):
                    capability.write_bytes(value)
                    with self.assertRaisesRegex(ValueError, "credential file"):
                        Settings.from_file(str(config))

    def test_allow_passthrough_must_be_a_boolean(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "config.json")
            path.write_text(json.dumps({"allow_passthrough": "false"}), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "allow_passthrough must be a boolean"):
                Settings.from_file(str(path))

    def test_numeric_limits_must_be_positive(self):
        for field in (
            "max_input_bytes",
            "max_output_tokens",
            "max_messages",
            "max_response_bytes",
            "max_stream_event_bytes",
            "max_stream_bytes",
            "timeout_seconds",
        ):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "positive"):
                Settings(**{field: 0})

    def test_retry_and_circuit_settings_are_validated(self):
        invalid = (
            ({"provider_retry_max_attempts": 0}, "between 1 and 10"),
            ({"provider_retry_max_attempts": 11}, "between 1 and 10"),
            ({"provider_retry_initial_backoff_seconds": -1}, "must not be negative"),
            (
                {
                    "provider_retry_initial_backoff_seconds": 2,
                    "provider_retry_max_backoff_seconds": 1,
                },
                "must be at least",
            ),
            ({"provider_retry_jitter_ratio": -0.1}, "between 0 and 1"),
            ({"provider_retry_jitter_ratio": 1.1}, "between 0 and 1"),
            ({"provider_circuit_failure_threshold": 0}, "must be positive"),
            ({"provider_circuit_recovery_seconds": 0}, "must be positive"),
        )
        for values, message in invalid:
            with self.subTest(values=values), self.assertRaisesRegex(ValueError, message):
                Settings(**values)


if __name__ == "__main__":
    unittest.main()
