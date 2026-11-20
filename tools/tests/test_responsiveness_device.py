import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from http_fixture import load_body, MAX_BODY
from responsiveness_device import assess, compare, stats, summarize


class CompareTests(unittest.TestCase):
    def test_p95_regression_requires_more_than_twenty_percent(self):
        def report(**metrics):
            return {'scenarios': {'S1': {'metrics': {key: {'p95': value} for key, value in metrics.items()}}}}

        baseline = report(close=10, worse=10, missing=7, improved=50)
        actual = report(close=12, worse=12.01, improved=45, additional=30)
        self.assertEqual(compare(baseline, actual), [
            {'scenario': 'S1', 'metric': 'worse', 'baseline_p95': 10, 'current_p95': 12.01},
            {'scenario': 'S1', 'metric': 'missing', 'baseline_p95': 7, 'current_p95': None},
        ])

    def test_nearest_rank_and_missing_samples(self):
        self.assertIsNone(stats([]))
        self.assertEqual(stats(reversed(range(1, 21))), {'n': 20, 'p50': 10, 'p95': 19, 'max': 20})

    def test_revised_follow_gaps_and_clock_verdicts(self):
        metrics = {'follow_ms': [35] * 20, 'drag_gap_count': [0] * 18 + [1, 1],
                   'clock_awake_late_ms': [39] * 19 + [80], 'clock_whole_period_late': [0],
                   'clock_max_ms': [80], 'clock_skipped': [0]}
        _, draft, provisional, coverage = assess(metrics, 'S1')
        self.assertEqual(set(draft), {'follow_ms', 'drag_gap_count', 'clock_max_ms'})
        self.assertEqual(provisional, [])
        self.assertEqual(coverage['drag_gap_count'], {'zero': 18, 'total': 20})
        metrics['clock_awake_late_ms'][-1] = 81
        metrics['clock_whole_period_late'] = [1]
        self.assertEqual(set(assess(metrics, 'S1')[2]), {'clock_awake_late_ms', 'clock_whole_period_late'})

    def test_weather_load_uses_separate_round_trip_target(self):
        metrics = {'plugin_ms': [79], 'publishWaitMs': [12]}
        self.assertEqual(set(assess(metrics, 'S10')[2]), {'plugin_ms', 'publishWaitMs'})
        self.assertEqual(assess(metrics, 'S14')[2], [])

    def test_gfx_only_windows_do_not_claim_input_latency(self):
        frames = [{'FrameCompleted': 22_000_000, 'IntendedVsync': 10_000_000, 'Vsync': 10_000_000},
                  {'FrameCompleted': 37_000_000, 'IntendedVsync': 27_000_000, 'Vsync': 27_000_000}]
        result = summarize([{'label': 'horizontal', 'framestats': frames}], 'S1')
        self.assertEqual(result['metrics'], {'gfx_frame_ms': stats([12, 10]), 'gfx_gap_ms': stats([17])})
        self.assertEqual(result['measurement_errors'], [])

    def test_dropped_or_unpaired_records_cannot_report_zero_gap_success(self):
        dropped = {'label': 'lost', 'expected_kind': 'drag-h', 'expected_plugin': None,
                   'trace': {'gestures': [], 'dropped': 3}}
        broken = {'label': 'wrong page', 'functional_error': 'expected clock.face'}
        result = summarize([dropped, broken])
        self.assertEqual(result['metrics'], {})
        self.assertEqual(len(result['measurement_errors']), 1)
        self.assertEqual(len(result['functional_failures']), 1)

    def test_stage_marks_do_not_mix_with_input_to_frame_latency(self):
        gesture = {'complete': True, 'kind': 'tap', 'down': 100, 'end': 180,
                   'pressSwap': 122, 'launchSwap': 300, 'launchIntervalsMs': [16.7], 'gcBlocking': 0,
                   'marks': {'press': [0.5], 'launch': [0]}}
        sample = {'label': 'launch', 'expected_kind': 'tap', 'expected_plugin': None, 'clock': 'n=0',
                  'trace': {'gestures': [gesture], 'dropped': 0}}
        result = summarize([sample])
        self.assertEqual(result['metrics']['press_ms'], stats([22]))
        self.assertEqual(result['metrics']['launch_ms'], stats([120]))
        self.assertEqual(result['metrics']['mark_press_ms'], stats([0.5]))
        self.assertEqual(result['metrics']['mark_launch_ms'], stats([0]))


class FixtureBodyTests(unittest.TestCase):
    def test_size_limit_and_invalid_json(self):
        body = b'{"weather": []}'
        valid = load_body(body, MAX_BODY)
        self.assertEqual(len(valid), MAX_BODY)
        self.assertEqual(json.loads(valid), {'weather': []})
        malformed = load_body(body, len(body), malformed=True)
        with self.assertRaises(json.JSONDecodeError):
            json.loads(malformed)
        for limit in (0, MAX_BODY + 1):
            with self.assertRaises(ValueError):
                load_body(body, limit)
