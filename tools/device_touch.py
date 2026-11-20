"""Inject and hold an API-27 touch through a temporary shell UiAutomator helper."""
import hashlib
import os
from pathlib import Path
import queue
import secrets
import subprocess
import tempfile
import threading
import zipfile

from gestures import validate_path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'tools/tests/android/ThinkTouch.java'


def touch_jar():
    digest = hashlib.sha256(SOURCE.read_bytes()).hexdigest()[:12]
    directory = Path(tempfile.gettempdir())/('thinkos-touch-'+digest)
    jar = directory/'touch.jar'
    if jar.exists(): return jar
    props = dict(line.split('=', 1) for line in (ROOT/'android/local.properties').read_text().splitlines() if '=' in line)
    sdk = Path(os.environ.get('ANDROID_HOME', props['sdk.dir']))
    platform = sdk/'platforms/android-35'
    junit = next((Path.home()/'.gradle/caches/modules-2/files-2.1/junit/junit/4.13.2').glob('*/*.jar'))
    classes, dex = directory/'classes', directory/'dex'
    classes.mkdir(parents=True, exist_ok=True); dex.mkdir(exist_ok=True)
    subprocess.run(['javac', '-source', '8', '-target', '8', '-cp',
                    os.pathsep.join(map(str, [platform/'android.jar', platform/'uiautomator.jar', junit])),
                    '-d', str(classes), str(SOURCE)], capture_output=True, check=True)
    subprocess.run([str(sdk/'build-tools/35.0.0/d8'), '--min-api', '27', '--lib', str(platform/'android.jar'),
                    '--output', str(dex), str(classes/'ThinkTouch.class')], capture_output=True, check=True)
    with zipfile.ZipFile(jar, 'w') as archive: archive.write(dex/'classes.dex', 'classes.dex')
    return jar


def drag(device, start, end, *, cancel=False, observe=lambda: None, chord=False, hold_ms=4000):
    if not 0 <= hold_ms <= 10000: raise ValueError('Hold outside test limit')
    if chord and cancel: raise ValueError('Use the chord helper\'s bounded pair release')
    jar = touch_jar()
    remote = '/data/local/tmp/'+jar.parent.name+'.jar'
    device.push(jar, remote)
    command = device.base+['shell', 'uiautomator', 'runtest', remote, '-c', 'ThinkTouch#'+('testChord' if chord else 'testDrag')]
    for key, value in dict(x1=start[0], y1=start[1], x2=end[0], y2=end[1], hold=hold_ms,
                           finish='cancel' if cancel else 'up').items():
        command += ['-e', key, str(value)]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    lines, ready = [], queue.Queue()
    def read():
        for line in process.stdout:
            lines.append(line)
            if 'THINKOS_TOUCH_HOLD' in line: ready.put(True)
        ready.put(False)
    reader = threading.Thread(target=read, daemon=True); reader.start()
    try:
        if not ready.get(timeout=35): raise RuntimeError('Touch helper did not hold a pointer: '+''.join(lines))
        result = observe()  # No hierarchy dump here: another UiAutomation session would cancel the pointer.
        process.wait(timeout=15); reader.join(timeout=2)
        output = ''.join(lines)
        if process.returncode or 'FAILURES' in output or 'OK (1 test)' not in output:
            raise RuntimeError('Touch helper failed: '+output)
        return result, output
    finally:
        if process.poll() is None:
            # Give the helper's bounded hold time and finally block time to release the pointer.
            try: process.wait(timeout=12)
            except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=5)


def path(device, points, *, finish='up', prepare_page=None, settle_delay=0):
    """Inject a timed path through Android's input dispatcher exactly once."""
    if finish not in ('up', 'cancel'):
        raise ValueError('Expected up or cancel')
    points = validate_path(points)
    prefix = '/data/local/tmp/thinkos-path-' + secrets.token_hex(8)
    remote_file, remote_jar = prefix + '.txt', prefix + '.jar'
    with tempfile.TemporaryDirectory(prefix='thinkos-path-') as directory:
        local = Path(directory) / 'points.txt'
        local.write_text(''.join(f'{at} {x:.4f} {y:.4f}\n' for at, x, y in points), encoding='ascii')
        try:
            device.push(touch_jar(), remote_jar)
            device.push(local, remote_file)
            command = device.base + ['shell', 'uiautomator', 'runtest', remote_jar, '-c', 'ThinkTouch#testPath',
                                     '-e', 'path', remote_file, '-e', 'finish', finish]
            if prepare_page is not None:
                command += ['-e', 'prepare_page', prepare_page, '-e', 'settle_delay', str(settle_delay)]
            result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=45)
            if result.returncode or 'OK (1 test)' not in result.stdout or 'FAILURES' in result.stdout:
                raise RuntimeError('Touch path failed: ' + result.stdout)
            return result.stdout
        finally:
            device.shell('rm', '-f', remote_file, remote_jar)


def set_editor_text(device, text):
    remote = '/data/local/tmp/thinkos-editor-' + secrets.token_hex(8) + '.jar'
    try:
        device.push(touch_jar(), remote)
        result = device.shell('uiautomator', 'runtest', remote, '-c', 'ThinkTouch#testSetEditorText', '-e', 'text', text)
        if 'OK (1 test)' not in result or 'FAILURES' in result:
            raise AssertionError('The focused native editor did not accept ACTION_SET_TEXT')
    finally:
        device.shell('rm', '-f', remote)
