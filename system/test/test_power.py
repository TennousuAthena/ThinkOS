#!/usr/bin/env python3
"""powerd on this machine: the decision logic by replaying sample files, the whole path against fake sysfs files.
   python3 system/test/test_power.py   (builds with system/build-power.sh --host first)"""
import os
import subprocess
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
BINARY = os.path.join(HERE, '..', 'build', 'powerd-host')
INTERVAL = 20  # ms, the default; the replay clock steps this much per sample


def steady(n, base=3_990_000, wobble=30_000):
    # the rail as measured on the device: about 3.97 to 4.05 V, never a clean line
    return [base + ((i * 7919) % (2 * wobble + 1)) - wobble for i in range(n)]


def replay(samples, *extra):
    with tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False) as f:
        f.write('\n'.join(str(s) for s in samples) + '\n')
    try:
        r = subprocess.run([BINARY, '--replay', f.name, *extra], capture_output=True, text=True, timeout=20)
    finally:
        os.unlink(f.name)
    events = []
    for line in r.stdout.splitlines():
        parts = line.split()
        if parts and parts[0] == 'EVENT':
            events.append((parts[1], int(parts[-1].split('=')[1])))
    return r.returncode, events, r.stderr


def names(events):
    return [e[0] for e in events]


class Logic(unittest.TestCase):
    def test_steady_rail_never_trips(self):
        rc, events, _ = replay(steady(2000))
        self.assertEqual(rc, 0)
        self.assertEqual(names(events), ['armed'])

    def test_one_or_two_low_samples_are_noise(self):
        rc, events, _ = replay(steady(100) + [3_700_000] + steady(20) + [3_700_000, 3_650_000] + steady(100))
        self.assertEqual(names(events), ['armed'])

    def test_three_low_samples_in_a_row_trip(self):
        rc, events, _ = replay(steady(100) + [3_790_000, 3_700_000, 3_600_000] + [3_500_001] * 5)
        self.assertEqual(names(events)[:2], ['armed', 'tripped'])
        # samples 0..99 are steady, 100 and 101 are low but not yet three in a row: the third low one is sample 102
        self.assertEqual(dict(events)['tripped'], 102 * INTERVAL)

    def test_a_good_sample_resets_the_count(self):
        rc, events, _ = replay(steady(100) + [3_790_000, 3_790_000, 3_990_000, 3_790_000, 3_790_000, 3_990_000] + steady(50))
        self.assertEqual(names(events), ['armed'])

    def test_a_very_low_sample_trips_at_once(self):
        rc, events, _ = replay(steady(100) + [3_450_000] + [3_300_000] * 3)
        self.assertEqual(names(events)[:2], ['armed', 'tripped'])

    def test_voltage_comes_back_then_reboot(self):
        low = [3_700_000] * 3 + [3_600_000] * 10
        recovered = [3_990_000] * 60
        rc, events, _ = replay(steady(100) + low + recovered)
        self.assertEqual(names(events), ['armed', 'tripped', 'reboot'])
        # tripped on sample 102, ten more low ones follow, the first good one is 11 samples later, and 1000 ms is 50 samples more
        self.assertEqual(dict(events)['reboot'] - dict(events)['tripped'], (10 + 1 + 50) * INTERVAL)

    def test_a_flicker_does_not_count_as_recovered(self):
        low = [3_700_000] * 3
        flicker = ([3_990_000] * 40 + [3_700_000]) * 3
        rc, events, _ = replay(steady(100) + low + flicker)
        self.assertEqual(names(events), ['armed', 'tripped'])

    def test_falling_further_powers_off(self):
        rc, events, _ = replay(steady(100) + [3_700_000] * 3 + [3_600_000] * 5 + [3_300_000] * 3)
        self.assertEqual(names(events), ['armed', 'tripped', 'poweroff'])

    def test_reads_failing_after_a_trip_power_off(self):
        rc, events, _ = replay(steady(100) + [3_700_000] * 3 + ['x'] * 40)
        self.assertEqual(names(events), ['armed', 'tripped', 'poweroff'])

    def test_reads_failing_before_a_trip_do_nothing(self):
        rc, events, _ = replay(steady(100) + ['x'] * 200 + steady(10))
        self.assertEqual(names(events), ['armed'])

    def test_hovering_in_between_powers_off_after_the_hold_time(self):
        rc, events, _ = replay(steady(100) + [3_700_000] * 3 + [3_850_000] * 1700)
        self.assertEqual(names(events), ['armed', 'tripped', 'poweroff'])
        self.assertEqual(dict(events)['poweroff'] - dict(events)['tripped'], 30_000)

    def test_nothing_happens_after_the_last_action(self):
        rc, events, _ = replay(steady(100) + [3_700_000] * 3 + [3_300_000] * 3 + [3_990_000] * 200 + [3_300_000] * 10)
        self.assertEqual(names(events), ['armed', 'tripped', 'poweroff'])

    def test_a_rail_that_is_not_ours_is_left_alone(self):
        rc, events, _ = replay([3_600_000] * 100)
        self.assertEqual(names(events), ['refused'])
        rc, events, _ = replay([3_850_000] * 100)
        self.assertEqual(names(events), ['refused'])

    def test_arming_survives_read_errors(self):
        rc, events, _ = replay(['x', 'x'] + steady(100))
        self.assertEqual(names(events), ['armed'])

    def test_thresholds_can_be_changed(self):
        rc, events, _ = replay(steady(100) + [3_900_000] * 3, '--trip-uv', '3950000', '--recover-uv', '4000000', '--margin-uv', '0')
        self.assertEqual(names(events), ['armed', 'tripped'])

    def test_thresholds_must_be_in_order(self):
        r = subprocess.run([BINARY, '--replay', '/dev/null', '--trip-uv', '3400000'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)
        r = subprocess.run([BINARY, '--bogus'], capture_output=True, text=True)
        self.assertEqual(r.returncode, 2)

    def test_garbage_samples_are_not_samples(self):
        rc, events, _ = replay(steady(100) + ['0', '-5', 'abc', '99999999999'] * 50 + steady(10))
        self.assertEqual(names(events), ['armed'])


class FakeBoard:
    """The files powerd touches, in a temp directory. The rail file is overwritten in place, like a sysfs attribute."""

    def __init__(self):
        self.dir = tempfile.mkdtemp(prefix='powerd-')
        p = lambda n: os.path.join(self.dir, n)
        self.vph, self.sysrq, self.led, self.mounts, self.marker = p('vph_pwr'), p('sysrq'), p('brightness'), p('mounts'), p('marker')
        self.fd = os.open(self.vph, os.O_RDWR | os.O_CREAT)
        for f in (self.sysrq, self.led, self.marker):
            open(f, 'w').close()
        open(self.mounts, 'w').write('/dev/block/x /data ext4 rw,seclabel,nosuid 0 0\n/dev/block/y /system ext4 ro,seclabel 0 0\n')
        self.set(3_990_000)

    def set(self, uv):
        os.pwrite(self.fd, ('Result:%d Raw:95a8'.ljust(40) % uv).encode() + b'\n', 0)

    def read(self, name):
        return open(getattr(self, name)).read()

    def args(self, *extra):
        return [BINARY, '--vph', self.vph, '--sysrq', self.sysrq, '--led', self.led, '--mounts', self.mounts,
                '--wakelock', os.path.join(self.dir, 'wake_lock'), '--reboot-cmd', 'echo reboot >> ' + self.marker,
                '--interval-ms', '5', '--duration', '30', *extra]


class Process:
    def __init__(self, argv):
        self.p = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.events = []
        self.lock = threading.Condition()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.p.stdout:
            parts = line.split()
            if parts and parts[0] == 'EVENT':
                with self.lock:
                    self.events.append(parts[1])
                    self.lock.notify_all()

    def wait_for(self, name, timeout=5):
        end = time.time() + timeout
        with self.lock:
            while name not in self.events:
                left = end - time.time()
                if left <= 0:
                    return False
                self.lock.wait(left)
        return True

    def stop(self):
        self.p.terminate()
        try:
            self.p.wait(5)
        except subprocess.TimeoutExpired:
            self.p.kill()
        return self.p.stderr.read()


class WholePath(unittest.TestCase):
    def setUp(self):
        self.board = FakeBoard()
        self.procs = []

    def tearDown(self):
        for p in self.procs:
            p.stop()
        os.close(self.board.fd)

    def start(self, *extra):
        p = Process(self.board.args(*extra))
        self.procs.append(p)
        self.assertTrue(p.wait_for('armed'), 'did not arm')
        return p

    def test_power_fails_for_good(self):
        p = self.start('--no-sync')
        self.board.set(3_700_000)
        self.assertTrue(p.wait_for('tripped'))
        time.sleep(0.8)  # the fake /data stays read-write, so powerd waits half a second for it
        self.assertEqual(self.board.read('sysrq'), 'u')
        self.assertEqual(self.board.read('led'), '0')
        self.board.set(3_300_000)
        self.assertTrue(p.wait_for('poweroff'))
        time.sleep(0.1)
        self.assertEqual(self.board.read('sysrq'), 'uo')
        self.assertEqual(self.board.read('marker'), '')

    def test_it_was_only_a_dip(self):
        p = self.start('--no-sync', '--recover-ms', '100')
        self.board.set(3_700_000)
        self.assertTrue(p.wait_for('tripped'))
        self.board.set(3_990_000)
        self.assertTrue(p.wait_for('reboot'))
        time.sleep(0.2)
        self.assertEqual(self.board.read('marker'), 'reboot\n')
        self.assertEqual(self.board.read('sysrq'), 'u')

    def test_flushes_with_a_real_sync(self):
        p = self.start()
        self.board.set(3_700_000)
        self.assertTrue(p.wait_for('tripped'))
        time.sleep(0.8)
        log = p.stop()
        self.assertIn('sync done', log)
        self.assertEqual(self.board.read('sysrq'), 'u')

    def test_waits_until_data_is_read_only(self):
        p = self.start('--no-sync')
        self.board.set(3_700_000)
        self.assertTrue(p.wait_for('tripped'))
        open(self.board.mounts, 'w').write('/dev/block/x /data ext4 ro,seclabel 0 0\n')
        time.sleep(0.3)
        log = p.stop()
        self.assertIn('/data is read-only', log)

    def test_dry_run_changes_nothing(self):
        p = self.start('--dry-run')
        self.board.set(3_300_000)
        self.assertTrue(p.wait_for('tripped'))
        self.assertTrue(p.wait_for('poweroff'))
        time.sleep(0.1)
        self.assertEqual(self.board.read('sysrq'), '')
        self.assertEqual(self.board.read('led'), '')
        self.assertEqual(self.board.read('marker'), '')

    def test_a_sysrq_that_is_not_there_is_survivable(self):
        p = Process(self.board.args('--no-sync', '--sysrq', os.path.join(self.board.dir, 'nope')))
        self.procs.append(p)
        self.assertTrue(p.wait_for('armed'))
        self.board.set(3_300_000)
        self.assertTrue(p.wait_for('tripped'))
        log = p.stop()
        self.assertIn('no ' + os.path.join(self.board.dir, 'nope'), log)

    def test_no_rail_means_exit_3(self):
        r = subprocess.run([BINARY, '--vph', os.path.join(self.board.dir, 'missing')], capture_output=True, text=True, timeout=5)
        self.assertEqual(r.returncode, 3)

    def test_probe_prints_the_rail(self):
        r = subprocess.run([BINARY, '--probe', '--vph', self.board.vph, '--interval-ms', '10', '--duration', '1'],
                           capture_output=True, text=True, timeout=10)
        rows = [l.split() for l in r.stdout.splitlines()]
        self.assertGreater(len(rows), 50)
        self.assertEqual({r[1] for r in rows}, {'3990000'})
        self.assertNotIn('EVENT', r.stdout)

    def test_probe_exits_when_the_reader_goes_away(self):
        p = subprocess.Popen([BINARY, '--probe', '--vph', self.board.vph, '--interval-ms', '5'], stdout=subprocess.PIPE)
        p.stdout.readline()
        p.stdout.close()
        try:
            p.wait(5)
        except subprocess.TimeoutExpired:
            p.kill()
            self.fail('--probe kept running with nobody reading')

    def test_trace_exits_when_the_reader_goes_away(self):
        p = subprocess.Popen(self.board.args('--trace', '--no-sync'), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        p.stdout.readline()
        p.stdout.close()
        try:
            p.wait(5)
        except subprocess.TimeoutExpired:
            p.kill()
            self.fail('--trace kept running with nobody reading')

    def test_stops_on_sigterm(self):
        p = self.start()
        t = time.time()
        p.stop()
        self.assertLess(time.time() - t, 2)
        self.assertIsNotNone(p.p.returncode)


if __name__ == '__main__':
    unittest.main(verbosity=1)
