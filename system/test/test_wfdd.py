#!/usr/bin/env python3
"""wfdd against a fake wpa_supplicant, on this machine.   python3 system/test/test_wfdd.py   (builds with system/build.sh --host first)"""
import os
import socket
import subprocess
import sys
import tempfile
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from fake_wpa import FakeWpa

BINARY = os.path.join(HERE, '..', 'build', 'wfdd-host')


def ask(path, line, raw=None, timeout=5):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    s.connect(path)
    s.sendall(raw if raw is not None else (line + '\n').encode())
    data = b''
    try:
        while True:
            chunk = s.recv(4096)
            if not chunk: break
            data += chunk
    except socket.timeout:
        pass
    s.close()
    return data.decode(errors='replace').strip()


class Daemon(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix='wfdd-')
        self.wpa = FakeWpa(self.dir).start()
        self.sock = os.path.join(self.dir, 'wfdd.sock')
        self.packages = os.path.join(self.dir, 'packages.list')
        self.write_packages('com.other.app 10001 0 /data/user/0/x default\nmoe.kiwi.thinkos 10123 1 /data/user/0/y default\n')
        self.start_daemon(['--allow-uid', str(os.getuid())])

    def write_packages(self, text):
        with open(self.packages, 'w') as f: f.write(text)

    def start_daemon(self, extra, check=1):
        self.proc = subprocess.Popen([BINARY, '--listen', self.sock, '--wpa-socket', self.wpa.path, '--wpa-uid', '-1',
                                      '--packages-list', self.packages, '--check', str(check)] + extra,
                                     stderr=subprocess.PIPE)
        # A binary that was just built can take seconds to run the first time (the system checks it); give it that long, and never block on its output.
        for _ in range(300):
            if os.path.exists(self.sock): return
            time.sleep(0.05)
        self.proc.kill(); self.proc.wait(5)
        self.fail('the daemon did not start: ' + self.proc.stderr.read().decode())

    def tearDown(self):
        if self.proc.poll() is None:
            self.proc.terminate()
            self.proc.wait(5)
        self.wpa.stop()

    def test_hello_and_idle_status(self):
        self.assertEqual(ask(self.sock, 'HELLO'), 'OK thinkos-wfdd 1')
        self.assertEqual(ask(self.sock, 'STATUS'), 'OK sink=off port=0 wpa=up wfd=- name=')

    def test_sink_on_sets_exactly_what_it_should(self):
        self.assertEqual(ask(self.sock, 'SINK_ON 7236 ThinkOS Cast'), 'OK')
        self.assertEqual(self.wpa.wifi_display, '1')
        self.assertEqual(self.wpa.subelem, '000600111c440032')          # primary sink, session available, port 7236, 50 Mbps
        self.assertEqual(self.wpa.device_name, 'ThinkOS Cast')
        self.assertEqual(self.wpa.ext_listen, (500, 2500))
        self.assertEqual(ask(self.sock, 'STATUS'), 'OK sink=on port=7236 wpa=up wfd=000600111c440032 name=ThinkOS Cast')

    def test_another_control_port_goes_into_the_element(self):
        ask(self.sock, 'SINK_ON 8000 X')
        self.assertEqual(self.wpa.subelem, '000600111f400032')

    def test_sink_off_revokes_everything(self):
        ask(self.sock, 'SINK_ON 7236 ThinkOS')
        self.assertEqual(ask(self.sock, 'SINK_OFF'), 'OK')
        self.assertEqual(self.wpa.wifi_display, '0')
        self.assertIsNone(self.wpa.ext_listen)
        self.assertIn('P2P_GROUP_REMOVE *', self.wpa.log)
        self.assertTrue(ask(self.sock, 'STATUS').startswith('OK sink=off'))

    def test_bad_requests_never_reach_wpa_supplicant(self):
        before = len(self.wpa.log)
        cases = {
            'SINK_ON': 'ERR bad-request',
            'SINK_ON abc ThinkOS': 'ERR bad-port',
            'SINK_ON 80 ThinkOS': 'ERR bad-port',
            'SINK_ON 70000 ThinkOS': 'ERR bad-port',
            'SINK_ON 7236': 'ERR bad-port',
            'SINK_ON 7236 ': 'ERR bad-name',
            'SINK_ON 7236 a"b': 'ERR bad-name',
            'SINK_ON 7236 a\\b': 'ERR bad-name',
            'SINK_ON 7236 `id`': 'ERR bad-name',
            'SINK_ON 7236 $HOME': 'ERR bad-name',
            'SINK_ON 7236 ' + 'x' * 33: 'ERR bad-name',
            'SINK_ON 7236  lead': 'ERR bad-name',
            'sink_on 7236 ThinkOS': 'ERR unknown',
            'P2P_FIND': 'ERR unknown',
            'SET wifi_display 0': 'ERR unknown',
        }
        for line, expected in cases.items():
            self.assertEqual(ask(self.sock, line), expected, line)
        self.assertEqual(len(self.wpa.log), before)
        self.assertEqual(self.wpa.device_name, 'Android_test')

    def test_a_newline_in_the_name_is_not_a_second_command(self):
        # The request is one line; whatever follows the first newline is never read.
        reply = ask(self.sock, '', raw=b'SINK_ON 7236 ThinkOS\nSET device_name evil\n')
        self.assertEqual(reply, 'OK')
        self.assertEqual(self.wpa.device_name, 'ThinkOS')

    def test_oversize_and_stalled_requests_are_dropped(self):
        self.assertEqual(ask(self.sock, '', raw=b'A' * 1000 + b'\n'), 'ERR bad-request')
        t0 = time.monotonic()
        self.assertEqual(ask(self.sock, '', raw=b'SINK_ON 7236 Thi', timeout=6), 'ERR bad-request')    # never finishes the line
        self.assertLess(time.monotonic() - t0, 4)
        self.assertEqual(ask(self.sock, 'HELLO'), 'OK thinkos-wfdd 1')                                     # and the daemon is still there

    def test_a_slow_drip_does_not_hold_the_daemon(self):
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.connect(self.sock)
        t0 = time.monotonic()
        try:
            for ch in b'SINK_ON 7236 ThinkOS':                   # one byte every 0.3 s would take 6 s
                s.send(bytes([ch])); time.sleep(0.3)
        except BrokenPipeError:
            pass                                                   # the daemon gave up on us, as it should
        self.assertLess(time.monotonic() - t0, 3.5)
        s.settimeout(2)
        self.assertEqual(s.recv(100).decode().strip(), 'ERR bad-request')
        s.close()
        self.assertEqual(ask(self.sock, 'HELLO'), 'OK thinkos-wfdd 1')

    def test_names_with_non_ascii_text_are_accepted(self):
        self.assertEqual(ask(self.sock, '', raw='SINK_ON 7236 客厅屏幕\n'.encode()), 'OK')
        self.assertEqual(self.wpa.device_name, '客厅屏幕')

    def test_wpa_supplicant_refusing_is_reported(self):
        self.wpa.refuse.add('WFD_SUBELEM_SET')
        self.assertEqual(ask(self.sock, 'SINK_ON 7236 ThinkOS'), 'ERR wpa-refused')

    def test_wpa_supplicant_silent_is_reported_and_bounded(self):
        self.wpa.silent = True
        t0 = time.monotonic()
        self.assertEqual(ask(self.sock, 'SINK_ON 7236 ThinkOS', timeout=8), 'ERR wpa-silent')
        self.assertLess(time.monotonic() - t0, 4)

    def test_wpa_supplicant_gone_is_reported(self):
        self.wpa.stop()
        self.assertEqual(ask(self.sock, 'SINK_ON 7236 ThinkOS'), 'ERR wpa-down')
        self.assertIn('wpa=down', ask(self.sock, 'STATUS'))

    def test_the_element_comes_back_after_wpa_supplicant_restarts(self):
        ask(self.sock, 'SINK_ON 7236 ThinkOS')
        self.wpa.reset_state()
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline and self.wpa.subelem == '':
            time.sleep(0.2)
        self.assertEqual((self.wpa.wifi_display, self.wpa.subelem, self.wpa.device_name), ('1', '000600111c440032', 'ThinkOS'))

    def test_nothing_is_set_again_while_it_is_still_there(self):
        ask(self.sock, 'SINK_ON 7236 ThinkOS')
        time.sleep(0.3)
        count = len([c for c in self.wpa.log if c.startswith('SET ') or c.startswith('WFD_SUBELEM_SET')])
        time.sleep(3.5)
        self.assertEqual(len([c for c in self.wpa.log if c.startswith('SET ') or c.startswith('WFD_SUBELEM_SET')]), count)

    def test_stopping_the_daemon_revokes_the_sink(self):
        ask(self.sock, 'SINK_ON 7236 ThinkOS')
        self.proc.terminate()
        self.proc.wait(5)
        self.assertEqual(self.wpa.wifi_display, '0')

    def test_a_stranger_is_refused(self):
        self.proc.terminate(); self.proc.wait(5)
        os.unlink(self.sock) if os.path.exists(self.sock) else None
        self.start_daemon([])                                    # no --allow-uid: this user is neither root nor the app
        before = len(self.wpa.log)
        self.assertEqual(ask(self.sock, 'SINK_ON 7236 ThinkOS'), 'ERR denied')
        self.assertEqual(ask(self.sock, 'STATUS'), 'ERR denied')
        self.assertEqual(len(self.wpa.log), before)

    def test_the_app_is_found_by_package_name(self):
        self.proc.terminate(); self.proc.wait(5)
        self.start_daemon(['--package', 'moe.kiwi.thinkos'])
        self.assertEqual(ask(self.sock, 'HELLO'), 'ERR denied')                    # uid 10123 is not this user
        self.write_packages('moe.kiwi.thinkos %d 1 /data/user/0/y default\n' % os.getuid())
        self.assertEqual(ask(self.sock, 'HELLO'), 'OK thinkos-wfdd 1')             # re-read on every connection

    def test_a_missing_package_list_refuses_everyone_but_root(self):
        self.proc.terminate(); self.proc.wait(5)
        self.start_daemon(['--packages-list', os.path.join(self.dir, 'none')])
        self.assertEqual(ask(self.sock, 'HELLO'), 'ERR denied')


if __name__ == '__main__':
    if not os.path.exists(BINARY):
        sys.exit('build it first: system/build.sh --host')
    unittest.main(verbosity=2)
