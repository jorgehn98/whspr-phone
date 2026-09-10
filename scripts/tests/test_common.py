import tempfile
import unittest
from pathlib import Path
import subprocess
from scripts.common import adb_target, read_local_sdk, select_device


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


if __name__ == "__main__":
    unittest.main()
