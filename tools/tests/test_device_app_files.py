import subprocess
import sys
from pathlib import Path
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device_app_files import read_app_file


class DeviceAppFilesTest(unittest.TestCase):
    @patch("device_app_files.subprocess.run")
    def test_valid_bytes_are_preserved_using_shell_exit_status(self, run):
        raw = '{"name":"香港"}\n'.encode()
        run.return_value = subprocess.CompletedProcess([], 0, raw, b"")
        self.assertEqual(raw, read_app_file("device", "files/settings/local.json"))
        command = run.call_args.args[0]
        self.assertEqual(["shell", "-T"], command[3:5])
        self.assertNotIn("exec-out", command)

    @patch("device_app_files.subprocess.run")
    def test_missing_file_is_absence_instead_of_error_text_to_restore(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, b"", b"cat: missing: No such file or directory\n")
        self.assertIsNone(read_app_file("device", "files/native-failures.json"))

    @patch("device_app_files.subprocess.run")
    def test_permission_failure_cannot_be_mistaken_for_empty_data(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, b"", b"run-as: Permission denied\n")
        with self.assertRaisesRegex(RuntimeError, "Permission denied"):
            read_app_file("device", "files/native-failures.json")

    @patch("device_app_files.subprocess.run")
    def test_offline_device_cannot_be_mistaken_for_missing_data(self, run):
        run.return_value = subprocess.CompletedProcess([], 1, b"", b"error: device offline\n")
        with self.assertRaisesRegex(RuntimeError, "device offline"):
            read_app_file("device", "files/native-failures.json")
