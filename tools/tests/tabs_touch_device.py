#!/usr/bin/env python3
"""Verify one synchronous tab click and native gesture/accessibility handling on API 27."""
import argparse
import os
from pathlib import Path
import re
import time

from responsiveness_device import (APP, Device, OPTIONAL_PATHS, PATHS, PLAYER_PAGE, Run, broadcast_json,
                                   capture, center, node_bounds, parse_latency_report, restore, write_json)
from device_touch import drag, touch_jar


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', 'HUA0BWUT'))
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    device = Device(args.serial)
    run = Run(device, 0, Path())
    original = broadcast_json(device, 'ui_state')
    assert not original['onboardingOpen'] and not original['safeMode']
    journal = capture(device, original, PATHS+OPTIONAL_PATHS)
    report = {'complete': False, 'checks': {}, 'tabs': {}}

    def observe(name):
        time.sleep(.5)
        trace = parse_latency_report(device.debug('latency_report'))
        write_json(args.output/(name+'.json'), trace)
        return [tap for tap in trace['taps'] if tap['plugin'] == 'player' and tap['gestureId']]

    def check(name, condition):
        report['checks'][name] = bool(condition)
        print(('PASS ' if condition else 'FAIL ') + name, flush=True)
        assert condition, name

    def recent_bounds():
        return node_bounds(run.player_tab('recent'))

    def selected(name):
        return run.player_tab(name).get('selected') == 'true'

    def choose_music():
        if not selected('music'):
            device.tap(*center(node_bounds(run.player_tab('music'))))
            time.sleep(.5)
        assert selected('music'), 'Could not select Music before checking Recent'

    def reset():
        device.debug('latency_reset')

    try:
        device.shell('am', 'start', '-W', '-n', APP+'/.MainActivity')
        device.debug('locale', value='zh-CN')
        device.debug('hold_attention', value='AWAKE')
        device.shell('am', 'broadcast', '-n', APP+'/.DebugReceiver', '--es', 'command', 'orientation', '--ei', 'value', 1)
        run.local_library()
        choose_music()
        device.debug('latency_on')
        left, top, right, bottom = recent_bounds()
        x, y = center((left, top, right, bottom))

        reset()
        device.tap(x, y)
        taps = observe('one-tap')
        check('one UP emits exactly one event synchronously', len(taps) == 1 and taps[0]['duringUpDispatch'] and selected('recent'))
        choose_music()

        reset()
        device.shell('input', 'swipe', x, y, max(28, x-220), y, 350)
        check('horizontal swipe does not select the tab', not observe('horizontal') and
              run.stage()['page'] == PLAYER_PAGE and selected('music'))

        reset()
        device.shell('input', 'swipe', x, y, x, y, 850)
        check('long press opens settings without a tab event', not observe('long-press') and
              run.settled({'page':'settings.form'})['page'] == 'settings.form')
        device.shell('input', 'keyevent', 'KEYCODE_BACK')
        run.settled({'page':PLAYER_PAGE})
        check('tab selection was preserved after the long press', selected('music'))

        for name, position in [('two-fingers', (x, y)), ('two-fingers-across-boundary', (right-8, y))]:
            reset()
            drag(device, position, position, chord=True, hold_ms=120)
            check(name+' does not select the tab', not observe(name) and selected('music'))

        reset()
        jar = touch_jar()
        remote = '/data/local/tmp/thinkos-tabs-accessibility.jar'
        device.push(jar, remote)
        try:
            result = device.shell('uiautomator', 'runtest', remote, '-c', 'ThinkTouch#testClickNode',
                                  '-e', 'label', run.player['variant.recent'])
            (args.output/'accessibility.txt').write_text(result)
        finally:
            device.shell('rm', '-f', remote)
        check('UiAutomator ACTION_CLICK selects the tab', 'OK (1 test)' in result and selected('recent'))
        check('accessibility click has no touch gesture', not observe('accessibility'))

        density = int(re.findall(r'([0-9]+)', device.shell('wm', 'density'))[-1]) / 160
        tab_nodes = [node for node in run.tree().iter('node') if node.get('clickable') == 'true' and
                     (node.get('text') or '') in {value for key, value in run.player.items() if key.startswith(('group.', 'variant.'))}]
        assert tab_nodes, 'No visible tabs for checking the back edge'
        report['tabs'] = {node.get('text'): node_bounds(node) for node in tab_nodes}
        edge = next((bounds for bounds in report['tabs'].values() if bounds[0] < 24*density), None)
        if edge:
            reset()
            stage = run.stage()
            x, y = edge[0]+1, center(edge)[1]
            device.shell('input', 'swipe', x, y, int(stage['width']*.65), y, 350)
            check('left edge belongs to Back when a tab overlaps it', not observe('left-edge') and run.stage()['page'] != PLAYER_PAGE)
        else:
            report['left_edge'] = 'No tab overlaps the left 24 dp'
        report['complete'] = True
    finally:
        device.debug('latency_off')
        try:
            report['recovery'] = restore(journal, device)
        finally:
            write_json(args.output/'summary.json', report)


if __name__ == '__main__':
    main()
