"""Bounded parsers for the API-27 responsiveness probes."""
import json
import math
import re

REPORT_BYTES = 64 * 1024
MAX_ROWS = 64
MAX_SAMPLES = 256
MAX_NS = 2**63 - 1


def _number(value):
    if type(value) not in (int, float) or not 0 <= value < MAX_NS or not math.isfinite(value):
        raise ValueError('Invalid nonnegative latency value')
    return value


def _integer(value):
    if type(value) is not int:
        raise ValueError('Expected a latency count or timestamp in whole milliseconds')
    return _number(value)


def _list(value, limit):
    if not isinstance(value, list) or len(value) > limit:
        raise ValueError('Latency array exceeds bounds or is missing')
    return value


def _row(value, fields):
    if not isinstance(value, dict) or not fields <= value.keys():
        raise ValueError('Missing latency fields')
    return value


def parse_latency_report(raw):
    if len(raw) > REPORT_BYTES * 2 + 1024:
        raise ValueError('Broadcast exceeds size limit')
    match = re.search(r'^Broadcast completed: result=0, data="(.*)"s*$', raw, re.MULTILINE)
    if match is None:
        raise ValueError('Missing latency_report broadcast result')
    payload = match[1]
    try:
        report = json.loads(payload)
    except json.JSONDecodeError:
        payload = json.loads('"' + payload + '"')
        report = json.loads(payload)
    if len(payload.encode('utf-8')) > REPORT_BYTES:
        raise ValueError('Latency report exceeds size limit')
    _row(report, {'frames', 'dropped', 'gestures', 'taps'})
    _integer(report['frames'])
    _integer(report['dropped'])
    for gesture in _list(report['gestures'], MAX_ROWS):
        _row(gesture, {'id', 'kind', 'down', 'complete', 'marks'})
        _integer(gesture['id'])
        _integer(gesture['down'])
        if gesture['kind'] not in ('tap', 'drag-h', 'drag-v', 'stack', 'panel', 'back', 'up', 'slider', 'seek'):
            raise ValueError('Unknown gesture kind')
        if gesture['kind'] != 'tap':
            _row(gesture, {'firstMove', 'firstApplied', 'followLagMs', 'frameIntervalsMs', 'unpairedMoves'})
        if type(gesture['complete']) is not bool or gesture['complete'] != ('end' in gesture):
            raise ValueError('Unrecognized gesture completion')
        if not isinstance(gesture['marks'], dict) or len(gesture['marks']) > 16:
            raise ValueError('Invalid gesture marks')
        for key in ('end', 'gcBlocking', 'gcTotal', 'firstMove', 'unpairedMoves'):
            if key in gesture:
                _integer(gesture[key])
        for key in ('firstApplied', 'firstSwap', 'pressSwap', 'wakeSwap', 'launchSwap'):
            if key in gesture:
                _number(gesture[key])
        for key in ('followLagMs', 'frameIntervalsMs', 'launchIntervalsMs', 'followPositionErrorPx'):
            if key in gesture:
                for value in _list(gesture[key], MAX_SAMPLES):
                    _number(value)
        for values in gesture['marks'].values():
            for value in _list(values, MAX_SAMPLES):
                _number(value)
        for start, end in [('down', 'end'), ('down', 'pressSwap'), ('down', 'wakeSwap'), ('end', 'launchSwap'),
                           ('down', 'firstMove'), ('firstMove', 'firstApplied'), ('firstApplied', 'firstSwap')]:
            if end in gesture and (start not in gesture or gesture[end] < gesture[start]):
                raise ValueError('Gesture timestamps are out of order')
    for tap in _list(report['taps'], MAX_ROWS):
        _row(tap, {'seq', 'plugin', 'gestureId'})
        if not isinstance(tap['plugin'], str) or not 1 <= len(tap['plugin']) <= 128:
            raise ValueError('Invalid plugin id')
        for key in ('seq', 'gestureId', 'up'):
            if key in tap:
                _integer(tap[key])
        for key in ('queueMs', 'luaMs', 'publishWaitMs', 'publishAfterLuaMs', 'deliverMs', 'diffMs', 'bindMs', 'swap'):
            if key in tap:
                _number(tap[key])
        if 'swap' in tap and ('up' not in tap or tap['swap'] < tap['up']):
            raise ValueError('Tap timestamps are out of order')
    return report


def parse_framestats(raw):
    if len(raw) > 2 * 1024 * 1024:
        raise ValueError('framestats exceeds size limit')
    active = False
    columns = None
    frames = []
    required = ('Flags', 'IntendedVsync', 'Vsync', 'SwapBuffers', 'FrameCompleted')
    for line in raw.splitlines():
        line = line.strip()
        if line == '---PROFILEDATA---':
            active = not active
            columns = None
            continue
        if not active or not line:
            continue
        values = line.rstrip(',').split(',')
        if values[0] == 'Flags':
            if len(values) > 64 or any(name not in values for name in required):
                raise ValueError('Incomplete framestats header')
            columns = values
            continue
        if columns is None or len(values) != len(columns) or len(frames) >= 4096:
            raise ValueError('Invalid framestats row')
        if any(not value.isascii() or not value.isdecimal() or len(value) > 19 for value in values):
            raise ValueError('Invalid framestats timestamp')
        row = dict(zip(columns, map(int, values)))
        if any(value > MAX_NS for value in row.values()):
            raise ValueError('framestats timestamp exceeds range')
        start, swap, complete = (row[name] for name in ('IntendedVsync', 'SwapBuffers', 'FrameCompleted'))
        if row['Flags'] or complete == MAX_NS or complete == 0:
            continue
        if not 0 < start <= row['Vsync'] <= swap <= complete:
            raise ValueError('framestats timestamps are out of order')
        frames.append(row)
    if active:
        raise ValueError('Unclosed framestats profile')
    return frames
