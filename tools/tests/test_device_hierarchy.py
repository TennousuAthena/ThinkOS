import subprocess
import sys
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device_hierarchy import dump_hierarchy, visible_nodes


class DeviceHierarchyTest(unittest.TestCase):
    def test_hidden_and_offscreen_duplicates_cannot_win_a_control_lookup(self):
        root = ET.fromstring('''<hierarchy><node bounds="[0,0][800,1202]">
          <node text="Address" bounds="[0,0][0,0]"/>
          <node text="Address" bounds="[30,-200][400,-160]"/>
          <node text="Address" bounds="[30,1400][400,1440]"/>
          <node text="Address" bounds="[30,400][400,440]"/>
        </node></hierarchy>''')
        matches = [n.get('bounds') for n in visible_nodes(root) if n.get('text') == 'Address']
        self.assertEqual(['[30,400][400,440]'], matches)

    @patch('device_hierarchy.time.sleep')
    @patch('device_hierarchy.hierarchy_jar', return_value=Path('/tmp/thinkos-hierarchy-test/hierarchy.jar'))
    @patch('device_hierarchy.subprocess.run')
    def test_success_exit_without_snapshot_is_retried_without_reusing_old_data(self, run, jar, sleep):
        sources = []

        def execute(command, **kwargs):
            if 'cp' in command:
                sources.append(command[-2])
                return subprocess.CompletedProcess(command, 1 if len(sources) == 1 else 0, b'', b'missing snapshot')
            return subprocess.CompletedProcess(command, 0, b'', b'')
        run.side_effect = execute
        dump_hierarchy('device', '/sdcard/result.xml')
        self.assertEqual(2, len(set(sources)))
        for source in sources:
            self.assertTrue(any(call.args[0][-3:] == ['rm', '-f', source] for call in run.call_args_list))

    @patch('device_hierarchy.time.sleep')
    @patch('device_hierarchy.hierarchy_jar', return_value=Path('/tmp/thinkos-hierarchy-test/hierarchy.jar'))
    @patch('device_hierarchy.subprocess.run')
    def test_permanent_failure_reports_original_dumper_error(self, run, jar, sleep):
        def execute(command, **kwargs):
            return subprocess.CompletedProcess(command, 1 if 'runtest' in command else 0,
                                                b'null root node' if 'runtest' in command else b'', b'')
        run.side_effect = execute
        with self.assertRaisesRegex(RuntimeError, 'null root node'):
            dump_hierarchy('device', '/sdcard/result.xml')
        self.assertEqual(3, sum('runtest' in call.args[0] for call in run.call_args_list))
        self.assertFalse(any('cp' in call.args[0] for call in run.call_args_list))


if __name__ == '__main__':
    unittest.main()
