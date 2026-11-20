from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gestures import edge_swipe, fling, slow_drag, tap, validate_path


class GesturesTests(unittest.TestCase):
    def test_fling_is_sampled_and_seeded(self):
        one = fling((10, 500), (500, 500), 250, seed=9, jitter=2)
        two = fling((10, 500), (500, 500), 250, seed=9, jitter=2)
        self.assertEqual(one, two)
        self.assertNotEqual(one, fling((10, 500), (500, 500), 250, seed=5, jitter=2))
        self.assertEqual(len(one), 31)
        self.assertEqual(one[0], (0, 10, 500))
        self.assertEqual(one[-1], (250, 500, 500))
        self.assertTrue(all(before[0] < after[0] for before, after in zip(one, one[1:])))

    def test_reverse_and_edges(self):
        path = slow_drag((650, 500), (150, 500), 1500, reverse_at=.5, rate=60)
        self.assertEqual(path[45], (750, 150, 500))
        self.assertEqual(path[-1], (1500, 650, 500))
        self.assertEqual(edge_swipe('bottom', 400, 300, 250)[0], (0, 400, 1273))
        self.assertEqual(tap(0, 0, 40), [(0, 0, 0), (40, 0, 0)])

    def test_rejects_invalid_paths_and_sampling(self):
        for bad in ([], [(1, 0, 0), (2, 0, 0)], [(0, 0, 0), (0, 0, 0)], [(0, 0, 0), (10001, 0, 0)],
                    [(0, 0, 0), (1, 800, 0)], [(0, -1, 0), (1, 0, 0)], [(0, 0, 0), (1, 0, float('inf'))],
                    [(tick, 0, 0) for tick in range(2001)]):
            with self.subTest(path=bad[:2]), self.assertRaises(ValueError):
                validate_path(bad, size=(800, 1280))
        with self.assertRaises(ValueError):
            slow_drag((1, 1), (2, 2), 10000, rate=1000)
