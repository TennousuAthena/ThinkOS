#!/usr/bin/env python3
"""Host tests for the external recovery guard's boot launchers and the installer that keeps them current.

Nothing here touches a device: the launcher and customize.sh texts are copied into a temporary tree with /data/adb pointed at it,
fake guard copies stand in for the real binary, and a fake `date` stands in for the clock.
"""
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest

SYSTEM = Path(__file__).resolve().parents[1]
MODULE = SYSTEM / 'thinkos-updater'


def script(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    path.chmod(0o755)


class Launcher(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='guard-launcher-')
        self.home = Path(self.temp.name)
        self.guard = self.home / 'adb/thinkos/guard'
        self.log = self.home / 'runs.log'
        self.tick = self.home / 'tick'
        bin_dir = self.home / 'bin'
        # A clock that moves 'step' seconds on every reading, so a copy can "run" for as long as a test needs.
        script(bin_dir / 'date', '#!/bin/sh\nstep=$(cat "%s.step" 2>/dev/null || echo 0)\nn=$(cat "%s" 2>/dev/null || echo 1000)\n'
               'echo $((n + step)) > "%s"\necho $((n + step))\n' % (self.tick, self.tick, self.tick))
        self.env = dict(os.environ, PATH=str(bin_dir) + os.pathsep + os.environ['PATH'])

    def tearDown(self):
        self.temp.cleanup()

    def launcher(self, name):
        text = (MODULE / 'guard' / name).read_text().replace('/data/adb', str(self.home / 'adb'))
        path = self.home / name
        script(path, text)
        return path

    def fake(self, slot, exit_code, label=None):
        path = self.guard / slot / 'updater-guard' if slot != 'legacy' else self.guard / 'updater-guard'
        script(path, '#!/bin/sh\necho "%s $@" >> "%s"\nexit %d\n' % (label or slot, self.log, exit_code))

    def run_launcher(self, name='post-fs-data.sh', step=0):
        (self.home / 'tick.step').write_text(str(step))
        result = subprocess.run(['/bin/sh', str(self.launcher(name))], capture_output=True, text=True, env=self.env, stdin=subprocess.DEVNULL)
        runs = self.log.read_text().splitlines() if self.log.exists() else []
        return result.returncode, runs

    def test_the_current_guard_runs_alone_and_its_answer_is_passed_on(self):
        self.fake('current', 3)
        self.fake('previous', 0)
        self.fake('legacy', 0)
        self.assertEqual(self.run_launcher(), (3, ['current --guard-early']))

    def test_the_watch_launcher_asks_for_the_watch_mode(self):
        self.fake('current', 0)
        self.assertEqual(self.run_launcher('service.sh'), (0, ['current --guard-watch']))

    def test_a_missing_current_falls_back_to_previous_and_then_the_old_single_file(self):
        self.fake('previous', 0)
        self.fake('legacy', 0)
        self.assertEqual(self.run_launcher(), (0, ['previous --guard-early']))
        (self.guard / 'previous/updater-guard').unlink()
        self.log.unlink()
        self.assertEqual(self.run_launcher(), (0, ['legacy --guard-early']))

    def test_a_copy_that_cannot_run_is_skipped(self):
        self.fake('current', 127)
        self.fake('previous', 0)
        self.assertEqual(self.run_launcher(), (0, ['current --guard-early', 'previous --guard-early']))

    def test_a_crash_right_away_is_skipped_but_a_late_one_is_not_retried(self):
        self.fake('current', 139)
        self.fake('previous', 0)
        self.assertEqual(self.run_launcher(step=0), (0, ['current --guard-early', 'previous --guard-early']))
        self.log.unlink()
        # 20 s pass between the two clock readings: the guard did its work, then died. Another copy must not start over it.
        self.assertEqual(self.run_launcher(step=20), (139, ['current --guard-early']))

    def test_the_guards_own_small_answers_never_trigger_a_fallback(self):
        for code in (1, 2, 9, 125):
            self.log.unlink(missing_ok=True)
            self.fake('current', code)
            self.fake('previous', 0)
            self.assertEqual(self.run_launcher(), (code, ['current --guard-early']))

    def test_nothing_installed_is_not_an_error(self):
        self.assertEqual(self.run_launcher(), (0, []))
        self.assertEqual(self.run_launcher('service.sh'), (0, []))

    def test_a_non_executable_copy_is_not_run(self):
        self.fake('current', 0)
        (self.guard / 'current/updater-guard').chmod(0o644)
        self.fake('previous', 0)
        self.assertEqual(self.run_launcher(), (0, ['previous --guard-early']))


class Installer(unittest.TestCase):
    """customize.sh with Magisk's helpers stubbed and /data/adb pointed at a temporary tree."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='guard-installer-')
        self.home = Path(self.temp.name)
        self.adb = self.home / 'adb'
        self.modpath = self.home / 'modpath'
        (self.modpath / 'bin').mkdir(parents=True)
        (self.modpath / 'guard').mkdir()
        (self.modpath / 'bin/updaterd').write_bytes(b'packaged-updater-binary')
        (self.modpath / 'guard/policy').write_bytes(b'thinkos-guard-policy 1\nhealth_ms=900000\npoll_ms=5000\n')
        for name in ('post-fs-data.sh', 'service.sh'):
            (self.modpath / 'guard' / name).write_text((MODULE / 'guard' / name).read_text())
        self.messages = self.home / 'messages'

    def tearDown(self):
        self.temp.cleanup()

    def install(self):
        text = (MODULE / 'customize.sh').read_text().replace('/data/adb', str(self.adb))
        script_path = self.home / 'run.sh'
        script_path.write_text(
            'ARCH=arm; API=27; MODPATH=%s\n'
            'abort() { echo "ABORT $*" >> %s; exit 1; }\n'
            'ui_print() { echo "$*" >> %s; }\n'
            'set_perm() { chmod "$4" "$1"; }\n'
            'set_perm_recursive() { :; }\n' % (self.modpath, self.messages, self.messages) + text)
        result = subprocess.run(['/bin/sh', str(script_path)], capture_output=True, text=True, stdin=subprocess.DEVNULL)
        self.assertEqual(result.returncode, 0, result.stderr)

    def launcher(self, directory):
        return self.adb / directory / 'thinkos-guard.sh'

    def test_a_first_install_seeds_the_guard_and_both_launchers(self):
        self.install()
        seeded = self.adb / 'thinkos/guard/current/updater-guard'
        self.assertEqual(seeded.read_bytes(), b'packaged-updater-binary')
        self.assertEqual(stat.S_IMODE(seeded.stat().st_mode), 0o755)
        self.assertEqual(stat.S_IMODE((self.adb / 'thinkos/guard').stat().st_mode), 0o700)
        for directory in ('post-fs-data.d', 'service.d'):
            self.assertIn('# thinkos-guard-launcher 2', self.launcher(directory).read_text())
            self.assertEqual(stat.S_IMODE(self.launcher(directory).stat().st_mode), 0o755)
            self.assertFalse(self.launcher(directory).with_suffix('.sh.new').exists())

    def test_the_policy_is_placed_once_and_an_existing_one_is_the_owners(self):
        self.install()
        policy = self.adb / 'thinkos/guard/policy'
        self.assertEqual(policy.read_text(), 'thinkos-guard-policy 1\nhealth_ms=900000\npoll_ms=5000\n')
        self.assertEqual(stat.S_IMODE(policy.stat().st_mode), 0o600)
        policy.write_text('thinkos-guard-policy 1\nhealth_ms=300000\npoll_ms=2000\n')
        self.install()
        self.assertIn('health_ms=300000', policy.read_text())

    def test_the_packaged_policy_is_what_the_schema_generator_renders(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('gen_guard_policy', SYSTEM / 'gen_guard_policy.py')
        generator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(generator)
        self.assertEqual(generator.render(), b'thinkos-guard-policy 1\nhealth_ms=900000\npoll_ms=5000\n')

    def test_a_later_install_does_not_touch_the_guard_copies(self):
        self.install()
        seeded = self.adb / 'thinkos/guard/current/updater-guard'
        seeded.write_bytes(b'promoted-by-the-updater')
        (self.adb / 'thinkos/guard/previous').mkdir()
        (self.adb / 'thinkos/guard/previous/updater-guard').write_bytes(b'older')
        self.install()
        self.assertEqual(seeded.read_bytes(), b'promoted-by-the-updater')
        self.assertEqual((self.adb / 'thinkos/guard/previous/updater-guard').read_bytes(), b'older')

    def test_an_owner_installed_single_file_guard_is_not_shadowed_by_a_seeded_copy(self):
        (self.adb / 'thinkos/guard').mkdir(parents=True)
        legacy = self.adb / 'thinkos/guard/updater-guard'
        legacy.write_bytes(b'owner-installed')
        legacy.chmod(0o755)
        self.install()
        self.assertFalse((self.adb / 'thinkos/guard/current').exists())
        self.assertEqual(legacy.read_bytes(), b'owner-installed')

    def test_the_old_unversioned_launcher_is_upgraded_and_a_newer_one_is_kept(self):
        for directory in ('post-fs-data.d', 'service.d'):
            self.launcher(directory).parent.mkdir(parents=True)
            self.launcher(directory).write_text('#!/system/bin/sh\nexec /data/adb/thinkos/guard/updater-guard --guard-early\n')
        self.install()
        self.assertIn('# thinkos-guard-launcher 2', self.launcher('post-fs-data.d').read_text())
        self.assertIn('--guard-watch', self.launcher('service.d').read_text())
        newer = '#!/system/bin/sh\n# thinkos-guard-launcher 9\nexit 0\n'
        self.launcher('post-fs-data.d').write_text(newer)
        self.install()
        self.assertEqual(self.launcher('post-fs-data.d').read_text(), newer)

    def test_the_installed_launchers_really_are_the_packaged_ones(self):
        self.install()
        self.assertEqual(self.launcher('post-fs-data.d').read_text(), (MODULE / 'guard/post-fs-data.sh').read_text())
        self.assertEqual(self.launcher('service.d').read_text(), (MODULE / 'guard/service.sh').read_text())

    def test_it_still_refuses_the_wrong_architecture_or_api(self):
        text = (MODULE / 'customize.sh').read_text()
        self.assertIn('requires 32-bit ARM', text)
        self.assertIn('qualified only for Android API 27', text)


if __name__ == '__main__':
    unittest.main()
