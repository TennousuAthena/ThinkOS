import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from latency_report import parse_framestats, parse_latency_report


REPORT = {'frames': 1, 'dropped': 0, 'gestures': [
    {'id': 1, 'kind': 'drag-h', 'down': 20, 'end': 47, 'complete': True, 'marks': {'newPage': [2.25]},
     'firstMove': 27, 'firstApplied': 29.5, 'firstSwap': 41.2, 'followLagMs': [14.2],
     'frameIntervalsMs': [], 'unpairedMoves': 0}], 'taps': []}


def broadcast(report, escaped=False):
    payload = json.dumps(report)
    if escaped:
        payload = json.dumps(payload)[1:-1]
    return 'Broadcasting: Intent {}\nBroadcast completed: result=0, data="' + payload + '"\n'


class LatencyReportTests(unittest.TestCase):
    def test_both_resultdata_quoting_styles(self):
        for escaped in (False, True):
            self.assertEqual(parse_latency_report(broadcast(REPORT, escaped)), REPORT)

    def test_rejects_malformed_payload_and_limits(self):
        for raw in ('not JSON', broadcast('a string'), broadcast({'frames': 3}), 'x' * 140_000):
            with self.subTest(raw=raw[:80]), self.assertRaises(ValueError):
                parse_latency_report(raw)
        for change in ({'firstMove': -2}, {'firstSwap': 23}, {'followLagMs': [0] * 257},
                       {'followLagMs': [float('nan')]}, {'marks': {'bind': 'slow'}}):
            malformed = copy.deepcopy(REPORT)
            malformed['gestures'][0].update(change)
            with self.subTest(change=change), self.assertRaises(ValueError):
                parse_latency_report(broadcast(malformed))


class FramestatsTests(unittest.TestCase):
    HEADER = 'Flags,IntendedVsync,Vsync,SwapBuffers,FrameCompleted,'

    def profile(self, rows):
        return f'Window: test\n---PROFILEDATA---\n{self.HEADER}\n{rows}\n---PROFILEDATA---\n'

    def test_accepts_oreo_rows_and_skips_invalid_frame_flags_and_pending_frames(self):
        rows = self.profile('0,10,15,20,25,\n1,27,28,29,42,\n0,40,45,50,9223372036854775807,')
        self.assertEqual(parse_framestats(rows), [dict(Flags=0, IntendedVsync=10, Vsync=15, SwapBuffers=20, FrameCompleted=25)])

    def test_rejects_bad_rows(self):
        for row in ('0,10,15,20,19,', '0,10,15,20,', '0,10,15,20,-25,', '0,10,15,20,9223372036854775808,'):
            with self.subTest(row=row), self.assertRaises(ValueError):
                parse_framestats(self.profile(row))

