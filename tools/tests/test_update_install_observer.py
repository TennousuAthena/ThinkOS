import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('guard_observer', Path(__file__).with_name('update_install_device.py'))
observer = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(observer)


class UpdateInstallObserverTest(unittest.TestCase):
    def test_application_wrapper_causes_and_recovery_stack_are_one_actual_crash_per_pid(self):
        marker = 'java.lang.IllegalStateException: Deliberate debug update-trial startup crash'
        lines = [f'1793767324.306 {pid} {pid} E {tag}: {prefix}{marker}'
                 for pid in (1737, 1820, 1899) for tag in ('AndroidRuntime', 'ThinkOS')
                 for prefix in ('java.lang.RuntimeException: Unable to create application: ', 'Caused by: ')]
        lines += ['1793767341.198 1969 1969 I ThinkOS: Update guard ROLLBACK attempts=4',
                  '1793767347.497 2185 2185 I ThinkOS: Startup received android.intent.action.MY_PACKAGE_REPLACED']
        self.assertEqual({1737, 1820, 1899}, observer.crash_processes(lines))
        self.assertEqual({1737}, observer.crash_processes(lines[:4]))
