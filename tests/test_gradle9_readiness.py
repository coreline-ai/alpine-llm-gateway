import importlib.util
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "gradle9_readiness", ROOT / "scripts/verify-gradle9-readiness.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(MODULE)


class Gradle9ReadinessTest(unittest.TestCase):
    def test_project_and_external_warnings_are_separated(self):
        report = MODULE.audit(
            "build.gradle.kts:12: deprecated API\n"
            "A plugin feature is deprecated and incompatible with Gradle 9\n"
        )
        self.assertEqual(2, report["warning_count"])
        self.assertEqual(1, report["project_owned_warning_count"])
        self.assertFalse(report["gradle9_ready"])


if __name__ == "__main__":
    unittest.main()
