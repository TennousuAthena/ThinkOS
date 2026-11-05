"""Host-only parsing/fixture checks; importing this module never contacts ADB."""
import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_layer_device as probe


class RenderLayerProbeTest(unittest.TestCase):
    def test_paired_declarations_differ_only_in_pan_boolean(self):
        on = probe.fixture_documents(True)
        off = probe.fixture_documents(False)
        self.assertEqual(on[:2], off[:2])
        self.assertEqual(1, len(on[1]['pages']))
        self.assertNotIn('pages', on[0])
        changed = copy.deepcopy(on[2])
        changed['slots']['stage']['pan'] = False
        self.assertEqual(changed, off[2])
        with self.assertRaises(RuntimeError):
            probe.fixture_documents('false')

    def test_only_owned_ids_or_their_exact_stage_prefix_are_selected(self):
        for name in (probe.PROBE, '.previous-' + probe.PROBE, '.stage-' + probe.PROBE + '-ab'):
            self.assertTrue(probe.owned_dev_name(name))
        for name in ('gallery', 'public-reader', probe.PROBE + '-other', '.previous-' + probe.PROBE + '-other'):
            self.assertFalse(probe.owned_dev_name(name))

    def test_cached_url_metadata_is_not_executed_or_printed(self):
        url = 'https://example.invalid/photo.jpg?id=one'
        raw = json.dumps({'bing': json.dumps({'items': [{'src': url}, {'src': url}, {'src': 'file:/private'}]})}).encode()
        self.assertEqual([url], probe.gallery_urls(raw))
        self.assertEqual([], probe.gallery_urls(b'{'))
        self.assertFalse(probe.valid_url('https://user:secret@example.invalid/a'))
        self.assertFalse(probe.valid_url('https://example.invalid/a\n'))

    def test_native_signals_are_matched_to_this_process(self):
        raw = 'F libc: Fatal signal 11 (SIGSEGV), code 1 in tid 6522 (RenderThread), pid 6511 (moe.kiwi.thinko)'
        result = probe.crash_evidence([raw], 6511)
        self.assertTrue(result['native'])
        self.assertTrue(result['render_thread'])
        self.assertFalse(probe.crash_evidence([raw], 6883)['native'])

    def test_adjacent_unrelated_tombstones_cannot_be_combined(self):
        lines = ['pid: 6511, tid: 6522, name: RenderThread  >>> other.app <<<',
                 'pid: 6883, tid: 6883, name: main  >>> ' + probe.APP + ' <<<']
        self.assertFalse(probe.crash_evidence(lines, 6511)['native'])
        self.assertTrue(probe.crash_evidence(lines, 6883)['native'])
        self.assertFalse(probe.crash_evidence(lines, 6883)['render_thread'])

    def test_java_failure_is_recorded_separately(self):
        result = probe.crash_evidence(['E AndroidRuntime: Process: ' + probe.APP + ', PID: 6511'], 6511)
        self.assertTrue(result['java'])
        self.assertFalse(result['native'])


if __name__ == '__main__':
    unittest.main()
