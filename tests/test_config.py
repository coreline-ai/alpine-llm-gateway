import json
from pathlib import Path
import tempfile
import unittest

from alpine_llm.config import Settings


class SettingsTests(unittest.TestCase):
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
