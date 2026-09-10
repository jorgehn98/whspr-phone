import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from io import StringIO
from pathlib import Path
from unittest.mock import patch

from scripts.common import adb_target, read_local_sdk, run, select_device

sys.path.insert(0, str(Path(__file__).parents[1]))
from install import launch_failed


class CommonTests(unittest.TestCase):
    def test_read_local_sdk_decodes_gradle_escaping(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "local.properties").write_text("sdk.dir=/opt/Android\\ SDK\n", encoding="utf-8")
            self.assertEqual(read_local_sdk(root), Path("/opt/Android SDK"))

    def test_select_device_prefers_explicit_serial(self):
        self.assertEqual(select_device("phone", ["other"]), "phone")

    def test_select_device_uses_only_connected_device(self):
        self.assertEqual(select_device(None, ["phone"]), "phone")

    def test_select_device_rejects_multiple_devices(self):
        with self.assertRaisesRegex(RuntimeError, "varios Android"):
            select_device(None, ["one", "two"])

    def test_adb_target_adds_serial_only_when_present(self):
        self.assertEqual(adb_target(Path("adb"), "phone"), ["adb", "-s", "phone"])
        self.assertEqual(adb_target(Path("adb"), None), ["adb"])

    def test_device_cli_exposes_serial_and_microphone_arguments(self):
        script = Path(__file__).parents[1] / "verify-device.py"
        result = subprocess.run([str(script), "--help"], text=True, capture_output=True, check=True)
        self.assertIn("--serial", result.stdout)
        self.assertIn("--require-microphone", result.stdout)

    def test_run_preserves_captured_command_diagnostic(self):
        completed = subprocess.CompletedProcess(["adb"], 1, stdout="", stderr="device unauthorized\n")
        diagnostic = StringIO()
        with patch("scripts.common.subprocess.run", return_value=completed), redirect_stderr(diagnostic):
            with self.assertRaises(subprocess.CalledProcessError):
                run(["adb"], capture=True)
        self.assertIn("device unauthorized", diagnostic.getvalue())

    def test_launch_failure_checks_stderr(self):
        self.assertTrue(launch_failed("Starting: Intent\n", "Error: Activity class does not exist\n"))
        self.assertFalse(launch_failed("Status: ok\n", ""))


if __name__ == "__main__":
    unittest.main()
