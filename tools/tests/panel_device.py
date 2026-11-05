#!/usr/bin/env python3
"""The pull-down control sheet on the device (ADR-035): the gesture, the controls, the way back.

    python3 tools/tests/panel_device.py [--output artifacts/panel]

Pulls the sheet down with real injected swipes and reads what the UIAutomator tree, the log and the system say. It changes the
media volume, the brightness cap and the mute switch while it runs, and puts them back; it leaves Android's Wi-Fi screen again
with the Home key. It does not change Wi-Fi.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools'))
from device_hierarchy import dump_hierarchy
from media_device import APP, Device


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/panel')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    device.start_log()
    results = []

    locale = device.shell('getprop', 'persist.sys.locale').strip() or 'en-US'
    table = 'zh-Hant' if re.search('TW|HK|Hant', locale) else 'zh-Hans' if locale.startswith('zh') else locale.split('-')[0]
    if not (ROOT / 'shared/i18n/system' / (table + '.json')).exists(): table = 'en'
    system = json.loads((ROOT / 'shared/i18n/system' / (table + '.json')).read_text(encoding='utf-8'))
    plugin = json.loads((ROOT / 'plugins/panel/i18n' / (table + '.json')).read_text(encoding='utf-8'))

    def check(name, ok, detail=''):
        results.append(bool(ok))
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    def tree():
        path = '/sdcard/thinkos-panel-check.xml'
        dump_hierarchy(args.serial, path)
        xml = device.shell('cat', path)
        device.shell('rm', '-f', path)
        return ET.fromstring(xml)

    def bounds(node): return list(map(int, re.findall(r'\d+', node.get('bounds'))))

    def center(node):
        left, top, right, bottom = bounds(node)
        return (left + right) // 2, (top + bottom) // 2

    def find(desc, root=None):
        for node in (root if root is not None else tree()).iter('node'):
            if node.get('content-desc') == desc: return node
        return None

    def wait(test, seconds=8):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            value = test()
            if value is not None and value is not False: return value
            time.sleep(0.5)
        return None

    def swipe(x1, y1, x2, y2, ms=250): device.shell('input', 'swipe', int(x1), int(y1), int(x2), int(y2), ms)

    def shot(name): device.screenshot(args.output / (name + '.png'))

    def screen():
        left, top, right, bottom = bounds(tree().find('node'))
        return right - left, bottom - top

    brightness_label = system['media_brightness']
    volume_label = system['media_volume']

    def open_now(): return find(brightness_label) is not None

    def sheet_open(): return wait(lambda: open_now() or None, 4)

    def levels(**values):
        mark = device.log_mark()
        device.debug('levels', **values)
        for _ in range(20):
            for line in device.log_since(mark):
                m = re.search(r'DEBUG levels brightness=([0-9.]+) volume=([0-9.]+)', line)
                if m: return float(m.group(1)), float(m.group(2))
            time.sleep(0.2)
        return None

    def focus():
        out = device.shell('dumpsys', 'window', 'windows')
        m = re.search(r'mCurrentFocus=Window\{\S+ \S+ ([^ }]+)', out)
        return m.group(1) if m else ''

    def pages_since(mark):
        return [m.group(1) for line in device.log_since(mark) for m in [re.search(r'page=(\S+) from=\S+ reason=(\S+)', line)] if m]

    def close_sheet():
        if open_now():
            device.shell('input', 'keyevent', 'KEYCODE_HOME')
            wait(lambda: not open_now() or None, 4)

    original_levels = levels()
    original_idle = None
    try:
        # A clean start, so ThinkOS has exactly one task: every Home key press below adds a second one, and lock-task only locks one.
        device.shell('am', 'force-stop', APP)
        time.sleep(1.0)
        device.shell('am', 'start', '-W', '-n', APP + '/.MainActivity')
        wait(lambda: 'mLockTaskModeState=LOCKED' in device.shell('dumpsys', 'activity', 'activities') or None, 10)
        device.debug('hold_attention', value='ENGAGED')
        time.sleep(1.0)
        width, height = screen()
        edge = 6

        # The Back key is the way out of an Android screen (the navigation bar keeps it there). ThinkOS can be resumed before its
        # task is in front on that way back, and then Android refuses to start the lock: it has to try again from the window focus.
        swipe(width // 2, edge, width // 2, int(height * .45))
        sheet_open()
        footer = next((n for n in tree().iter('node') if n.get('text') == plugin['network.footer']), None)
        if footer is not None: device.tap(*center(footer))
        check('the network line opens Android\'s Wi-Fi screen', footer is not None and wait(lambda: 'settings' in focus() or None, 8), focus())
        time.sleep(2.5)
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        check('the Back key comes back to ThinkOS', wait(lambda: APP in focus() or None, 8), focus())
        check('and the kiosk lock is on again', wait(lambda: 'mLockTaskModeState=LOCKED' in device.shell('dumpsys', 'activity', 'activities') or None, 6))
        device.shell('input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(1.0)
        check('the sheet is not there before the first pull', not open_now())

        # ---- the pull ----
        mark = device.log_mark()
        swipe(width // 2, edge, width // 2, int(height * .45))
        check('a pull from the top edge opens the sheet', sheet_open())
        shot('open')
        root = tree()
        check('the sheet shows the brightness and volume sliders', find(brightness_label, root) is not None and find(volume_label, root) is not None)
        check('the Wi-Fi, mute and settings buttons are there', find(plugin['mute'], root) is not None and find(plugin['settings'], root) is not None)
        check('nothing was media-playing, so the media row is collapsed', find(system['media.play'], root) is None and find(system['media.pause'], root) is None)
        check('the page under the sheet was not changed', not pages_since(mark), pages_since(mark))

        # ---- sliders ----
        slider = find(volume_label)
        left, top, right, bottom = bounds(slider)
        track_left = left + (right - left) * .12
        track_right = left + (right - left) * .90
        y = (top + bottom) // 2
        swipe(track_left, y, track_right, y, 400)
        time.sleep(.5)
        after = levels()
        check('dragging the volume slider changes the media volume', after and after[1] >= .8, after)
        swipe(track_right, y, track_left + 4, y, 400)
        time.sleep(.5)
        after = levels()
        check('dragging it back lowers it again', after and after[1] <= .2, after)
        slider = find(brightness_label)
        left, top, right, bottom = bounds(slider)
        y = (top + bottom) // 2
        swipe(left + (right - left) * .12, y, left + (right - left) * .9, y, 400)
        time.sleep(.5)
        after = levels()
        check('dragging the brightness slider raises the brightness cap', after and after[0] >= .95, after)
        check('and the sheet is still open after all that touching', open_now())
        shot('sliders')

        # ---- the mute button ----
        mute = find(plugin['mute'])
        was = mute.get('checked') == 'true'
        device.tap(*center(mute))
        now = wait(lambda: (find(plugin['mute']).get('checked') == 'true') != was or None, 4)
        check('tapping mute flips the button', now)
        mute = find(plugin['mute'])
        device.tap(*center(mute))
        wait(lambda: (find(plugin['mute']).get('checked') == 'true') == was or None, 4)
        check('and tapping again puts it back', (find(plugin['mute']).get('checked') == 'true') == was)

        # ---- closing ----
        swipe(width // 2, int(height * .40), width // 2, 20)
        check('a pull up closes the sheet', wait(lambda: not open_now() or None, 4))
        swipe(width // 2, edge, width // 2, int(height * .45))
        sheet_open()
        device.shell('input', 'keyevent', 'KEYCODE_HOME')
        check('the Home key closes the sheet', wait(lambda: not open_now() or None, 4))
        swipe(width // 2, edge, width // 2, int(height * .45))
        sheet_open()
        x, y = width // 2, int(height * .93)
        device.tap(x, y)
        check('a tap on the dimmed page closes it', wait(lambda: not open_now() or None, 4))

        # ---- gestures that must stay the page's ----
        swipe(width // 2, int(height * .30), width // 2, int(height * .70))
        time.sleep(.8)
        check('a vertical swipe that starts below the edge zone leaves the sheet shut', not open_now())
        mark = device.log_mark()
        swipe(int(width * .15), edge, int(width * .85), edge + 2, 200)
        time.sleep(1.2)
        check('a sideways swipe along the top edge still turns the page', pages_since(mark), pages_since(mark))
        check('and does not open the sheet', not open_now())
        mark = device.log_mark()
        swipe(int(width * .85), edge, int(width * .15), edge + 2, 200)
        time.sleep(1.2)
        check('and so does the swipe back', pages_since(mark), pages_since(mark))

        # ---- the buttons that leave ----
        swipe(width // 2, edge, width // 2, int(height * .45))
        sheet_open()
        mark = device.log_mark()
        device.tap(*center(find(plugin['settings'])))
        wait(lambda: 'settings.form' in ' '.join(pages_since(mark)) or None, 4)
        check('the settings button opens the settings page', 'settings.form' in ' '.join(pages_since(mark)), pages_since(mark))
        check('and the sheet goes away', wait(lambda: not open_now() or None, 4))
        shot('settings')
        device.shell('input', 'keyevent', 'KEYCODE_HOME')
        time.sleep(1.2)

        swipe(width // 2, edge, width // 2, int(height * .45))
        sheet_open()
        root = tree()
        footer = next((n for n in root.iter('node') if n.get('text') == plugin['network.footer']), None)
        check('the network line names the network setting', footer is not None)
        if footer is not None:
            device.tap(*center(footer))
            check('tapping it opens Android\'s Wi-Fi screen', wait(lambda: 'settings' in focus() or None, 8), focus())
            shot('android-wifi')
            device.shell('input', 'keyevent', 'KEYCODE_HOME')
            check('Home comes back to ThinkOS', wait(lambda: APP in focus() or None, 8), focus())
            time.sleep(1.0)
            lock = device.shell('dumpsys', 'activity', 'activities')
            check('and the kiosk lock is on again', 'mLockTaskModeState=LOCKED' in lock)

        # ---- the lock comes back ----
        # Lock-task can be refused on the way back from an Android screen (the task is not in front yet when ThinkOS resumes);
        # whatever the reason it is off, ThinkOS puts it back as soon as its window has the focus again.
        device.shell('am', 'task', 'lock', 'stop')
        check('(the test switched the lock off)', wait(lambda: 'mLockTaskModeState=NONE' in device.shell('dumpsys', 'activity', 'activities') or None, 4))
        device.debug('maintenance')
        time.sleep(1.5)
        device.shell('input', 'keyevent', 'KEYCODE_BACK')            # closes the recovery menu: the window has the focus again
        check('the lock comes back when the window has the focus again',
              wait(lambda: 'mLockTaskModeState=LOCKED' in device.shell('dumpsys', 'activity', 'activities') or None, 6))

        # ---- the settings that govern it ----
        device.debug('setting', key='panel.idle_s', value='5')
        swipe(width // 2, edge, width // 2, int(height * .45))
        sheet_open()
        time.sleep(8)
        check('the sheet closes by itself when nobody touches it', not open_now())
        device.debug('setting', key='panel.idle_s', value='15')
        device.debug('setting', key='panel.enabled', value='false')
        time.sleep(.5)
        swipe(width // 2, edge, width // 2, int(height * .45))
        time.sleep(1.2)
        check('with panel.enabled off, the pull does nothing', not open_now())
    finally:
        device.debug('setting', key='panel.enabled', value='true')
        device.debug('setting', key='panel.idle_s', value='15')
        if original_levels: levels(brightness=original_levels[0], volume=original_levels[1])
        device.debug('hold_attention')
        device.shell('input', 'keyevent', 'KEYCODE_HOME', check=False)
        device.stop_log()
    failed = results.count(False)
    print(f'\n{len(results) - failed}/{len(results)} checks passed')
    sys.exit(1 if failed else 0)


if __name__ == '__main__':
    main()
