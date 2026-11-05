#!/usr/bin/env python3
"""Apps, Back and the edge swipe on the device (ADR-040): how an app is entered and left, and what the swipe from the left edge does.

    python3 tools/tests/nav_back_device.py [--output artifacts/nav]

Everything is real injected input (taps, swipes, key events) and what the log, the UIAutomator tree and the system say. It leaves the
device on the home page. It pushes the `nav-probe` development plugin (an app that takes Back for itself, and one that
declares it and then does nothing) and removes it again. It plays no media and changes no setting.

It does not measure performance. The clock check (`clock_accuracy_device.py`) is a separate run and is announced before it starts.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
import struct
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools/tests'))
sys.path.insert(0, str(ROOT / 'tools'))
from device_hierarchy import dump_hierarchy
from media_device import APP, Device
from package_plugins import package_plugin
from plugin_push import publish_directory, remove_plugin


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', type=Path, default=ROOT / 'artifacts/nav')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    device.start_log()
    results = []
    original_match = re.search(r'data="(\{.*\})"', device.debug('ui_state'))
    original_debug = json.loads(original_match.group(1)) if original_match else {}

    def check(name, ok, detail=''):
        results.append(bool(ok))
        print(('PASS ' if ok else 'FAIL ') + name + ('  ' + str(detail) if detail != '' else ''), flush=True)

    locale = device.shell('getprop', 'persist.sys.locale').strip() or 'en-US'
    table = 'zh-Hant' if re.search('TW|HK|Hant', locale) else 'zh-Hans' if locale.startswith('zh') else locale.split('-')[0]
    if not (ROOT / 'shared/i18n/system' / (table + '.json')).exists(): table = 'en'
    system = json.loads((ROOT / 'shared/i18n/system' / (table + '.json')).read_text(encoding='utf-8'))
    brightness_label = system['media_brightness']

    def tree():
        path = '/sdcard/thinkos-nav-check.xml'
        dump_hierarchy(args.serial, path)
        xml = device.shell('cat', path)
        device.shell('rm', '-f', path)
        return ET.fromstring(xml)

    def bounds(node): return list(map(int, re.findall(r'\d+', node.get('bounds'))))

    def center_of(text, include_description=True):
        root = tree()
        viewport = bounds(root.find('node'))
        for node in root.iter('node'):
            if node.get('text') == text or (include_description and node.get('content-desc') == text):
                l, t, r, b = bounds(node)
                x, y = (l + r) // 2, (t + b) // 2
                if r > l and b > t and viewport[0] <= x < viewport[2] and viewport[1] <= y < viewport[3]:
                    return x, y
        return None

    def tap_label(text, scroll=False):
        for _ in range(10 if scroll else 1):
            point = center_of(text)
            if point is not None:
                tap(*point)
                settle(.6)
                return
            if scroll:
                swipe(width // 2, int(height * .82), width // 2, int(height * .3), 350)
        raise AssertionError('Missing visible label: ' + text)

    def has_editor():
        return any(n.get('class') == 'android.widget.EditText' for n in tree().iter('node'))

    def wide_settings():
        labels = {value for key, value in system.items() if key.startswith('setting_section_')}
        return any(n.get('selected') == 'true' and n.get('text') in labels for n in tree().iter('node'))

    def sheet_open():
        return any(node.get('content-desc') == brightness_label for node in tree().iter('node'))

    def wait(test, seconds=6):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            value = test()
            if value is not None and value is not False: return value
            time.sleep(0.4)
        return None

    def page():
        out = device.shell('am', 'broadcast', '-n', APP + '/.DebugReceiver', '--es', 'command', 'benchmark_state')
        match = re.search(r'data="([^"]*)"', out)
        return match.group(1).split('|')[0] if match else ''

    def pages_since(mark):
        return [(m.group(1), m.group(2), m.group(3)) for line in device.log_since(mark)
                for m in [re.search(r'page=(\S+) from=(\S+) reason=(\S+)', line)] if m]

    def swipe(x1, y1, x2, y2, ms=220): device.shell('input', 'swipe', int(x1), int(y1), int(x2), int(y2), ms)
    def key(code): device.shell('input', 'keyevent', code)
    def tap(x, y): device.shell('input', 'tap', int(x), int(y))
    def shot(name): device.screenshot(args.output / (name + '.png'))
    def settle(seconds=1.4): time.sleep(seconds)

    def on(expected, seconds=5): return wait(lambda: page() == expected or None, seconds) is not None

    pushed = False
    try:
        # A clean start; the attention is held so the display stays on while the checks run.
        device.shell('am', 'force-stop', APP)
        time.sleep(1.0)
        device.shell('am', 'start', '-W', '-n', APP + '/.MainActivity')
        time.sleep(4.0)
        device.debug('hold_attention', value='ENGAGED')
        device.debug('theme', value='DARK')
        time.sleep(1.5)
        left, top, right, bottom = bounds(tree().find('node'))
        width, height = right - left, bottom - top
        edge = 6                                   # well inside the strip, and the digitizer must report it
        tile_y = int(height * .30)
        media_x, tv_x, cast_x = int(width * .20), int(width * .50), int(width * .82)

        with tempfile.TemporaryDirectory() as out:
            directory, _ = package_plugin(ROOT / 'tools/tests/fixtures/nav-probe', Path(out))
            publish_directory(directory, args.serial)
            pushed = True
        time.sleep(2.5)

        # --- the page sequence: Back does nothing, the edge swipe is a page swipe ---------------------------------
        device.debug('page', value='tiles.apps')
        settle()
        check('the apps page is on screen', page() == 'tiles.apps', page())
        mark = device.log_mark()
        key('KEYCODE_BACK')
        settle()
        check('Back on a page of the sequence does nothing', page() == 'tiles.apps' and not pages_since(mark), (page(), pages_since(mark)))
        mark = device.log_mark()
        swipe(edge, height // 2, int(width * .8), height // 2)
        settle()
        moved = pages_since(mark)
        check('a swipe from the left edge on a page of the sequence still turns the page (no dead strip)',
              bool(moved) and moved[-1][2] == 'swipe' and moved[-1][0] != 'tiles.apps', moved)

        # --- opening an app ----------------------------------------------------------------------------------------------
        device.debug('page', value='tiles.apps')
        settle()
        mark = device.log_mark()
        tap(cast_x, tile_y)
        check('a tile opens the app it belongs to', on('cast.home'), page())
        opened = pages_since(mark)
        check('and the log says it was opened from the apps page', ('cast.home', 'tiles.apps', 'open') in opened, opened)
        settle(1.2)
        shot('cast-home')

        # --- inside an app: sideways swipes are the app's -------------------------------------------------------------
        mark = device.log_mark()
        swipe(int(width * .75), height // 2, int(width * .2), height // 2)
        settle()
        swipe(int(width * .2), height // 2, int(width * .75), height // 2)
        settle()
        check('sideways swipes inside an app do not change the page', page() == 'cast.home' and not pages_since(mark), (page(), pages_since(mark)))

        # --- the edge swipe ---------------------------------------------------------------------------------------------
        mark = device.log_mark()
        swipe(edge, height // 2, int(width * .12), height // 2, 1800)       # slow and short: not far enough
        settle()
        check('a swipe from the edge that stops short comes back to the app', page() == 'cast.home' and not pages_since(mark), (page(), pages_since(mark)))
        mark = device.log_mark()
        swipe(int(width * .3), height // 2, int(width * .9), height // 2)
        settle()
        check('a swipe that does not start at the edge is not Back', page() == 'cast.home' and not pages_since(mark), (page(), pages_since(mark)))
        mark = device.log_mark()
        swipe(edge, height // 2, int(width * .65), height // 2)
        check('a swipe from the left edge goes back to where the app was opened from', on('tiles.apps'), page())
        check('and the log says why', ('tiles.apps', 'cast.home', 'back') in pages_since(mark), pages_since(mark))

        # --- the top-left corner is shared with the control sheet ------------------------------------------------------
        device.debug('page', value='tiles.apps')
        settle()
        tap(cast_x, tile_y)
        on('cast.home')
        settle(1.2)
        mark = device.log_mark()
        swipe(edge, edge, int(width * .65), edge + 20)
        check('from the top-left corner, right is Back', on('tiles.apps'), (page(), pages_since(mark)))
        tap(cast_x, tile_y)
        on('cast.home')
        settle(1.2)
        swipe(width // 2, edge, width // 2, int(height * .45))
        check('and down, from the same strip, is still the control sheet', wait(sheet_open, 4) is not None)
        key('KEYCODE_BACK')
        gone = wait(lambda: not sheet_open() or None, 4)
        check('Back closes the sheet first and does not leave the app', gone is not None and page() == 'cast.home', page())

        # --- the settings page is a layer too ------------------------------------------------------------------------
        device.shell('input', 'swipe', width // 2, height // 3, width // 2, height // 3, 900)    # a long press
        settle(1.8)
        check('a long press opens the settings', page().startswith('settings'), page())
        mark = device.log_mark()
        key('KEYCODE_BACK')
        check('Back closes the settings and returns to the app', on('cast.home'), page())
        device.shell('input', 'swipe', width // 2, height // 3, width // 2, height // 3, 900)
        settle(1.8)
        swipe(edge, height // 2, int(width * .65), height // 2)
        check('the edge swipe closes the settings too', on('cast.home'), page())

        # Native editors and a settings plugin's own scope stack consume the same Back path.
        clock_text = json.loads((ROOT / 'plugins/clock/i18n' / (table + '.json')).read_text())
        settings_text = json.loads((ROOT / 'plugins/settings/i18n' / (table + '.json')).read_text())
        gallery_text = json.loads((ROOT / 'plugins/gallery/i18n' / (table + '.json')).read_text())
        for scope, label, name in (
                ('clock', clock_text['setting.zone1'], 'timezone'),
                ('system', system['setting_location_name'], 'place')):
            for gesture in (False, True):
                device.debug('page', value='home.main')
                device.debug('settings', value=scope)
                settle()
                portrait_category = scope == 'system' and not wide_settings()
                if scope == 'system': tap_label(system['setting_section_location'])
                tap_label(label, scroll=True)
                check(name + ' picker is open', has_editor())
                shot(name + ('-edge' if gesture else '-back'))
                if gesture: swipe(edge, height // 2, int(width * .65), height // 2)
                else: key('KEYCODE_BACK')
                settle()
                check(('edge swipe' if gesture else 'Back') + ' closes only the ' + name + ' picker',
                      page() == 'settings.form' and not has_editor(), page())
                key('KEYCODE_BACK')
                if portrait_category:
                    check('Back from the location category returns to categories', center_of(system['setting_section_display']) is not None)
                    key('KEYCODE_BACK')
                check('the next Back leaves the settings page', on('home.main'), page())

        for gesture in (False, True):
            device.debug('page', value='home.main')
            device.debug('settings', value='system')
            settle()
            tap_label(system['setting_section_display'])
            tap_label(system['setting_theme_mode'], scroll=True)
            expanded = lambda: any((n.get('text') or '').startswith('✓') for n in tree().iter('node'))
            check('the choice is expanded', expanded())
            if gesture: swipe(edge, height // 2, int(width * .65), height // 2)
            else: key('KEYCODE_BACK')
            settle()
            check('Back first collapses the choice', page() == 'settings.form' and not expanded(), page())

        for gesture in (False, True):
            device.debug('page', value='home.main')
            device.debug('settings', value='system')
            settle()
            tap_label(system['setting_section_plugins'])
            portrait_category = not wide_settings()
            tap_label(gallery_text['name'], scroll=True)
            check('the gallery scope is on screen', center_of(gallery_text['setting.source']) is not None)
            if gesture: swipe(edge, height // 2, int(width * .65), height // 2)
            else: key('KEYCODE_BACK')
            settle()
            check('Back from a child scope returns to the plugin list', page() == 'settings.form' and center_of(gallery_text['name']) is not None, page())
            if portrait_category:
                key('KEYCODE_BACK')
                check('Back from the plugin list returns to categories', center_of(system['setting_section_display']) is not None)
            key('KEYCODE_BACK')
            check('Back from the entry scope leaves settings', on('home.main'), page())

        weather_text = json.loads((ROOT / 'plugins/weather/i18n' / (table + '.json')).read_text())
        device.debug('page', value='weather.detail')
        settle()
        swipe(width // 2, height // 3, width // 2, height // 3, 900)
        settle()
        check('a long press on weather enters its scope directly', center_of(weather_text['name']) is not None)
        key('KEYCODE_BACK')
        check('Back from a directly entered weather scope leaves settings', on('weather.detail'), page())

        # Android's native text dialog owns Back (including first hiding its keyboard).
        player_text = json.loads((ROOT / 'plugins/player/i18n' / (table + '.json')).read_text())
        device.debug('page', value='home.main')
        device.debug('settings', value='player')
        settle()
        tap_label(player_text['setting.playlist_url'], scroll=True)
        check('the native text dialog is open', has_editor())
        for _ in range(2):
            if has_editor(): key('KEYCODE_BACK'); settle(.6)
        check('Back dismisses the text dialog and keeps settings open', page() == 'settings.form' and not has_editor(), page())
        key('KEYCODE_BACK')
        on('home.main')

        device.debug('settings', value='clock')
        settle()
        tap_label(clock_text['setting.zone1'], scroll=True)
        check('a picker is open before the settings idle timeout', has_editor())
        time.sleep(31)
        check('the settings idle timeout still closes an open picker', on('home.main') and not has_editor(), page())

        device.debug('page', value='aero.main')
        settle()
        start = center_of(system['aero.start'])
        if start is None:
            # This ROM excludes the navigation-bar area from accessibility even
            # while immersive mode draws there. The fixed Aero taskbar is visible
            # in the full screenshot; use its documented 56 x 52 dp Start control.
            shot('aero-before-menu')
            screen_w, screen_h = struct.unpack('>II', (args.output / 'aero-before-menu.png').read_bytes()[16:24])
            density = int(re.search(r'(\d+)', device.shell('wm', 'density')).group(1)) / 160
            start = (int(34 * density), int(screen_h - 26 * density))
        tap(*start)
        settle()
        menu_open = lambda: center_of(system['setting_system_name'], include_description=False) is not None
        check('the Aero menu is open', menu_open())
        shot('aero-menu-open')
        swipe(edge, height // 2, int(width * .65), height // 2)
        settle()
        check('the edge swipe closes the Aero menu without leaving its page',
              page() == 'aero.main' and not menu_open(), page())

        # --- one layer at a time ---------------------------------------------------------------------------------------
        device.debug('page', value='tiles.apps')
        settle()
        device.debug('page', value='nav-probe.levels')       # a jump starts from the page sequence, as a tile would
        on('nav-probe.levels')
        settle()
        tap(width // 2, 80)                                   # the probe opens another app: one app on top of another
        check('an app can open another app on top of itself', on('nav-probe.plain'), page())
        key('KEYCODE_BACK')
        settle()
        first = page()
        key('KEYCODE_BACK')
        settle()
        check('Back peels the layers off one at a time, down to the page the apps were opened from',
              first == 'nav-probe.levels' and page() == 'tiles.apps', (first, page()))

        # --- the Home key empties everything ----------------------------------------------------------------------------
        device.debug('page', value='tiles.apps')
        settle()
        tap(media_x, tile_y)
        on('player.library')
        settle(1.0)
        mark = device.log_mark()
        key('KEYCODE_HOME')
        check('the Home key leaves any app for the home page', on('home.main', 8), page())
        check('and the log says why', any(p[2] == 'home' for p in pages_since(mark)), pages_since(mark))
        device.debug('page', value='clock.calendar')
        on('clock.calendar')
        key('KEYCODE_HOME')
        check('Home also returns from the page sequence with an empty navigation stack', on('home.main', 8), page())

        # --- an app that wants Back for itself ----------------------------------------------------------------------
        device.debug('page', value='tiles.apps')
        settle()
        device.debug('page', value='nav-probe.levels')
        on('nav-probe.levels')
        settle()
        spot = center_of('depth 1')
        check('the probe page shows its first level', spot is not None)
        tap(*(spot or (width // 2, height // 2)))      # one level deeper: the probe takes Back for itself now
        settle()
        mark = device.log_mark()
        key('KEYCODE_BACK')
        settle()
        lines = [l for l in device.log_since(mark) if 'probe back depth=1' in l]
        check('an app that declared it wants Back is told, and the screen stays', bool(lines) and page() == 'nav-probe.levels', (page(), len(lines)))
        key('KEYCODE_BACK')
        check('once it gave Back up, the next one leaves', on('tiles.apps'), page())

        # --- one that declares it and then does nothing must not trap the user -------------------------------------------------
        device.debug('page', value='nav-probe.silent')
        on('nav-probe.silent')
        settle()
        key('KEYCODE_BACK')
        settle(0.8)
        check('the first Back goes to the app, which does nothing', page() == 'nav-probe.silent', page())
        time.sleep(2.2)
        key('KEYCODE_BACK')
        check('after two seconds of silence the next Back leaves anyway', on('tiles.apps'), page())

        # --- sleeping empties the layers ------------------------------------------------------------------------------------
        device.debug('page', value='tiles.apps')
        settle()
        tap(cast_x, tile_y)
        on('cast.home')
        device.debug('hold_attention', value='ASLEEP')
        settle(2.0)
        asleep = page()
        device.debug('hold_attention', value='ENGAGED')
        settle(2.0)
        check('going to sleep leaves the app, and waking goes home', asleep != 'cast.home' and page() == 'home.main', (asleep, page()))
    finally:
        device.debug('hold_attention', value='ENGAGED')
        device.debug('page', value='home.main')
        if pushed:
            try: remove_plugin('nav-probe', args.serial)
            except Exception as error: print('could not remove nav-probe:', error)
        for command, key_name in (('hold_attention', 'attention'), ('theme', 'theme')):
            value = original_debug.get(key_name)
            if value is None: device.debug(command)
            else: device.debug(command, value=value)
        (args.output / 'logcat.txt').write_text('\n'.join(device.log_since(0)) + '\n')
        device.stop_log()
    passed = sum(results)
    print(f'\n{passed}/{len(results)} checks passed')
    sys.exit(0 if passed == len(results) else 1)


if __name__ == '__main__':
    main()
