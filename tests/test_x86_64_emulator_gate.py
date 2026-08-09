from __future__ import annotations

import json
import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GATE = ROOT / "scripts" / "runtime" / "run-x86_64-emulator-gate.sh"


class X86EmulatorGateTests(unittest.TestCase):
    def test_skip_report_never_persists_adb_serial(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            temporary = Path(directory)
            fake_adb = temporary / "adb"
            fake_adb.write_text(
                """#!/usr/bin/env bash
set -euo pipefail
if [[ \"${1:-}\" == \"devices\" ]]; then
  printf 'List of devices attached\\nfixture-device-0001\\tdevice\\n'
  exit 0
fi
if [[ \"${1:-}\" == \"-s\" && \"${5:-}\" == \"ro.product.cpu.abilist\" ]]; then
  printf 'arm64-v8a\\n'
  exit 0
fi
if [[ \"${1:-}\" == \"-s\" && \"${5:-}\" == \"ro.kernel.qemu\" ]]; then
  printf '1\\n'
  exit 0
fi
exit 64
""",
                encoding="utf-8",
            )
            fake_adb.chmod(fake_adb.stat().st_mode | stat.S_IXUSR)
            report = temporary / "x86-gate.json"
            environment = os.environ | {
                "ADB": str(fake_adb),
                "X86_64_GATE_REPORT": str(report),
            }

            completed = subprocess.run(
                [str(GATE), "--allow-skip"],
                cwd=ROOT,
                env=environment,
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, completed.returncode, completed.stderr)
            payload = json.loads(report.read_text(encoding="utf-8"))
            serialized = json.dumps(payload, sort_keys=True)
            self.assertEqual(3, payload["schema_version"])
            self.assertEqual("SKIP_NO_X86_64_EMULATOR", payload["status"])
            self.assertEqual("NO_CONNECTED_X86_64_EMULATOR", payload["reason"])
            self.assertEqual("android_emulator", payload["device_class"])
            self.assertNotIn("serial", payload)
            self.assertNotIn("fixture-device-0001", serialized)

    def test_gate_script_has_valid_bash_syntax(self) -> None:
        completed = subprocess.run(
            ["bash", "-n", str(GATE)],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, completed.returncode, completed.stderr)


if __name__ == "__main__":
    unittest.main()
