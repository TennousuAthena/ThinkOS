#!/usr/bin/env python3
"""拔电实验：量电源线拔掉以后系统主供电轨（VPH_PWR）怎么掉、设备还能撑多久（docs/40-power-loss.md §5）。

  python3 -I tools/power_trace.py start [--out DIR] [--rate-ms 2] [--mode probe|guard]
  python3 -I tools/power_trace.py stop

start：把 powerd 推到设备的 /data/local/tmp（重启即消失，不持久），在设备上起两路采样，经 Wi-Fi 发回这台 Mac：
  1. 供电轨：powerd --probe（每行 毫秒 µV 读取耗时）；--mode guard 则是真的上膛运行（sync、紧急只读、灭背光），
     并在每个样本旁边输出轨电压（--trace），拔线时看它收尾要多久
  2. 其它通道（usbin、dcin、battery），每 0.5 秒一行，用来弄清楚 USB 线和电源线各自供什么
然后一直收到设备断开（电源线拔了，设备死了）或 Ctrl-C，打印摘要：几毫秒掉到几伏，最后一个样本是什么时候。

要拔线的是人。拔之前先把 USB 数据线拔掉（它可能也在给板子供电，会让实验失真）；设备上的采样走 Wi-Fi，不依赖 adb。
设备序列号默认 HUA0BWUT，可用 ANDROID_SERIAL 覆盖。
"""
import argparse
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERIAL = os.environ.get('ANDROID_SERIAL', 'HUA0BWUT')
VADC6 = '/sys/devices/soc/qpnp-vadc-6/vph_pwr'
VADC16 = '/sys/devices/soc/qpnp-vadc-16'


def adb(*args, timeout=30):
    env = dict(os.environ, ADB_MDNS='0')
    return subprocess.run(['adb', '-s', SERIAL, *args], capture_output=True, text=True, timeout=timeout, env=env)


def shell(command, timeout=30):
    return adb('shell', command, timeout=timeout).stdout


def lan_address():
    """The address of this machine that the device can reach: whatever route leads to the device's Wi-Fi address."""
    out = shell('ip -4 addr show wlan0')
    device = next((l.split()[1].split('/')[0] for l in out.splitlines() if l.strip().startswith('inet ')), None)
    if not device:
        sys.exit('设备没有 Wi-Fi 地址，实验需要它')
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect((device, 9))
    mine = s.getsockname()[0]
    s.close()
    return device, mine


class Recorder(threading.Thread):
    """Accepts one connection and writes every line with the time it arrived."""

    def __init__(self, path):
        super().__init__(daemon=True)
        self.server = socket.create_server(('', 0))
        self.port = self.server.getsockname()[1]
        self.path = path
        self.rows = []
        self.connected = threading.Event()
        self.closed = threading.Event()
        self.last_arrival = 0.0

    def run(self):
        conn, _ = self.server.accept()
        self.connected.set()
        with open(self.path, 'w') as out:
            for raw in conn.makefile('rb'):
                now = time.time()
                line = raw.decode(errors='replace').rstrip('\n')
                self.rows.append((now, line))
                self.last_arrival = now
                out.write('%.6f %s\n' % (now, line))
                out.flush()
        self.closed.set()


def parse_vph(rows):
    """[(arrival, device_ms, µV)] from the probe's “毫秒 µV 耗时” lines (guard mode adds a leading T)."""
    out = []
    for arrival, line in rows:
        parts = line.split()
        if parts and parts[0] == 'T':
            parts = parts[1:]
        if len(parts) >= 2 and parts[1] != 'x':
            try:
                out.append((arrival, float(parts[0]), int(parts[1])))
            except ValueError:
                pass
    return out


def crossing(samples, volts, after_ms):
    for _, ms, uv in samples:
        if ms >= after_ms and uv < volts * 1_000_000:
            return ms
    return None


def summary(rec, other):
    s = parse_vph(rec.rows)
    print('\n=== 摘要 ===')
    print('供电轨样本 %d 个' % len(s))
    if len(s) < 20:
        print('样本太少，没法分析')
        return
    steady = [uv for _, ms, uv in s if ms < s[-1][1] - 5000][:2000] or [uv for _, _, uv in s[:200]]
    base = sorted(steady)[len(steady) // 2]
    print('平时：中位 %.3f V，最低 %.3f V，最高 %.3f V' % (base / 1e6, min(steady) / 1e6, max(steady) / 1e6))
    # the fall starts at the last sample that was still within the steady band
    floor = min(steady)
    start_ms = s[0][1]
    for _, ms, uv in s:
        if uv < floor - 30_000:
            start_ms = ms
            break
    end_ms = s[-1][1]
    print('最后一个样本：%.3f V，设备时间 %.0f ms' % (s[-1][2] / 1e6, end_ms))
    if start_ms == s[0][1]:
        print('没有看到电压下掉（拔线了吗？还是设备靠别的电源撑着？）')
        return
    print('开始下掉：设备时间 %.0f ms；到最后一个样本 %.0f ms' % (start_ms, end_ms - start_ms))
    for v in (3.95, 3.9, 3.85, 3.8, 3.7, 3.6, 3.5, 3.4, 3.3, 3.2, 3.0):
        t = crossing(s, v, start_ms)
        print('  掉到 %.2f V 以下：%s' % (v, '%.0f ms 后' % (t - start_ms) if t is not None else '没有'))
    print('提示：最后一个样本只是“最后收到的”，网络里可能还有没发出来的，真正撑的时间比这更长一点。')
    if other.rows:
        print('\n其它通道最后几行（usbin dcin battery usb-online）：')
        for _, line in other.rows[-4:]:
            print('  ' + line)


def start(args):
    out = os.path.abspath(args.out or os.path.join(ROOT, 'artifacts', 'power', time.strftime('trace-%Y%m%d-%H%M%S')))
    os.makedirs(out, exist_ok=True)
    binary = os.path.join(ROOT, 'system', 'build', 'powerd-armv7')
    if not os.path.exists(binary):
        sys.exit('先 system/build-power.sh')
    device, mine = lan_address()
    print('设备 %s，这台 Mac %s，输出 %s' % (device, mine, out))

    vph, other = Recorder(os.path.join(out, 'vph.txt')), Recorder(os.path.join(out, 'channels.txt'))
    vph.start()
    other.start()

    adb('push', binary, '/data/local/tmp/powerd')
    shell('chmod 755 /data/local/tmp/powerd')
    stop(None)
    if args.mode == 'guard':
        probe = '/data/local/tmp/powerd --trace --interval-ms %d 2>&1 | nc %s %d' % (args.rate_ms, mine, vph.port)
    else:
        probe = '/data/local/tmp/powerd --probe --interval-ms %d | nc %s %d' % (args.rate_ms, mine, vph.port)
    channels = ('while :; do echo "$(cat %s/usbin) $(cat %s/dcin) $(cat /sys/class/power_supply/battery/voltage_now) '
                '$(cat /sys/class/power_supply/usb/online)"; sleep 0.5; done | nc %s %d' % (VADC16, VADC16, mine, other.port))
    with tempfile.TemporaryDirectory() as tmp:
        for name, body in (('power-trace-probe.sh', probe), ('power-trace-channels.sh', channels)):
            path = os.path.join(tmp, name)
            open(path, 'w').write('#!/system/bin/sh\n' + body + '\n')
            adb('push', path, '/data/local/tmp/' + name)
    shell('sync')
    for name in ('power-trace-probe.sh', 'power-trace-channels.sh'):
        shell("su -c 'nohup sh /data/local/tmp/%s >/dev/null 2>&1 &'" % name)
    if not vph.connected.wait(15) or not other.connected.wait(15):
        sys.exit('设备没有连回来：检查 Mac 和设备是否在同一个网段，防火墙是否挡了入站连接')
    print('采样已开始。现在可以：1) 拔掉 USB 数据线  2) 过 20 秒以上再拔电源线（圆口）。Ctrl-C 结束。\n')

    try:
        shown = 0.0
        while not vph.closed.is_set():
            time.sleep(0.5)
            now = time.time()
            if vph.rows and now - vph.last_arrival > 4:
                # a device that lost power never closes the connection: no data for a few seconds means it is gone
                print('\n\n设备 %.1f 秒没有数据了，认为已经断电。' % (now - vph.last_arrival))
                break
            if now - shown >= 2 and vph.rows:
                shown = now
                s = parse_vph(vph.rows[-50:])
                tail = other.rows[-1][1] if other.rows else ''
                print('\r%d 个样本，轨 %.3f V，距上次 %.1f 秒，通道 %s    ' % (len(vph.rows), s[-1][2] / 1e6 if s else 0, now - vph.last_arrival, tail[:60]), end='', flush=True)
        time.sleep(1)
    except KeyboardInterrupt:
        pass
    try:
        stop(None)  # if the device is still alive (nobody pulled the plug), do not leave the samplers running
    except Exception:
        pass
    summary(vph, other)
    print('\n数据在', out)


def stop(_):
    # [p]attern: the shell running this command has the pattern in its own command line, and must not match itself
    shell("su -c \"pkill -f '[p]ower-trace'; pkill -f '[p]owerd --'\"")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)
    a = sub.add_parser('start')
    a.add_argument('--out')
    a.add_argument('--rate-ms', type=int, default=2)
    a.add_argument('--mode', choices=['probe', 'guard'], default='probe')
    a.set_defaults(fn=start)
    b = sub.add_parser('stop')
    b.set_defaults(fn=stop)
    args = p.parse_args()
    args.fn(args)


if __name__ == '__main__':
    main()
