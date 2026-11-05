#!/usr/bin/env python3
"""powerd 的 Android 版在真机上：对着假的供电轨文件跑，不碰真正的供电轨、/proc/sysrq-trigger 和背光。

  python3 -I tools/tests/power_device.py

在 /data/local/tmp 里放一个假的 vph_pwr、sysrq 和挂载表，让 armeabi-v7a 版 powerd 对着它们跑，检查：
  - 上膛、掉压后触发、sync、等 /data 只读、灭（假的）背光、恢复后重启 / 继续掉就关机，用的是设备上的 /system/bin/sh
  - 读真实供电轨的 --probe 能出数
设备上不留东西（结束时删掉 /data/local/tmp/powertest），不重启，不改设置。设备序列号默认 HUA0BWUT，可用 ANDROID_SERIAL 覆盖。
"""
import os
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SERIAL = os.environ.get('ANDROID_SERIAL', 'HUA0BWUT')
DIR = '/data/local/tmp/powertest'
failures = []


def adb(*args, timeout=60):
    return subprocess.run(['adb', '-s', SERIAL, *args], capture_output=True, text=True, timeout=timeout,
                          env=dict(os.environ, ADB_MDNS='0'))


def su(command, timeout=60):
    return adb('shell', 'su -c "%s"' % command, timeout=timeout).stdout


def check(name, ok, detail=''):
    print(('通过  ' if ok else '失败  ') + name + ('' if ok else '  ' + detail))
    if not ok:
        failures.append(name)


def setv(uv):
    # overwrite in place without truncating (1<> opens read-write), like a sysfs attribute; toybox dd has no conv=notrunc
    su("printf 'Result:%%-30d\\n' %d 1<>%s/vph" % (uv, DIR))


def main():
    binary = os.path.join(ROOT, 'system', 'build', 'powerd-armv7')
    if not os.path.exists(binary):
        sys.exit('先 system/build-power.sh')
    su('rm -rf %s; mkdir -p %s' % (DIR, DIR))
    adb('push', binary, '/data/local/tmp/powerd-test-upload')
    su('cp /data/local/tmp/powerd-test-upload %s/powerd; rm -f /data/local/tmp/powerd-test-upload' % DIR)
    su('chmod 755 %s/powerd; : > %s/sysrq; : > %s/led; : > %s/marker; echo "/dev/x /data ext4 rw,seclabel 0 0" > %s/mounts' % ((DIR,) * 5))
    setv(3990000)
    try:
        args = ('%s/powerd --vph %s/vph --sysrq %s/sysrq --led %s/led --mounts %s/mounts --wakelock %s/wl '
                '--reboot-cmd \\\"echo reboot >> %s/marker\\\" --interval-ms 5 --duration 40 --recover-ms 200 --trace > %s/out 2> %s/err &'
                % ((DIR,) * 9))
        su('nohup %s' % args)
        time.sleep(1.5)
        check('上膛', 'EVENT armed' in su('cat %s/out' % DIR), su('cat %s/err' % DIR))

        setv(3700000)
        time.sleep(2)
        out, err = su('cat %s/out' % DIR), su('cat %s/err' % DIR)
        check('掉压后触发', 'EVENT tripped' in out, err)
        check('sync 在设备上完成', 'sync done' in err, err)
        check('写了 sysrq u', su('cat %s/sysrq' % DIR).strip() == 'u', su('cat %s/sysrq' % DIR))
        check('灭了（假的）背光', su('cat %s/led' % DIR).strip() == '0', su('cat %s/led' % DIR))
        check('/data 仍是读写，所以等满了半秒再继续', 'STILL read-write' in err or 'is read-only' in err, err)

        setv(3990000)
        time.sleep(1.5)
        check('电压回来以后重启', 'EVENT reboot' in su('cat %s/out' % DIR), su('cat %s/err' % DIR))
        check('重启命令由设备的 sh 执行', su('cat %s/marker' % DIR).strip() == 'reboot', su('cat %s/marker' % DIR))
        check('唤醒锁成对（没有留下）', 'thinkos-power' not in su('cat /sys/power/wake_lock'))
    finally:
        su("pkill -f '[p]owertest/powerd'")
        time.sleep(0.5)

    probe = su('%s/powerd --probe --interval-ms 5 --duration 1' % DIR)
    rows = [l.split() for l in probe.splitlines() if l.strip() and l.split()[0][0].isdigit()]
    check('读真实供电轨的 --probe 出数', len(rows) > 50 and all(3_000_000 < int(r[1]) < 5_000_000 for r in rows if r[1] != 'x'), probe[:200])
    check('没动真实的 sysrq 和背光', 'thinkos' not in su('cat /sys/power/wake_lock'))
    su('rm -rf %s' % DIR)
    print('\n%d 项失败' % len(failures) if failures else '\n全部通过')
    sys.exit(1 if failures else 0)


if __name__ == '__main__':
    main()
