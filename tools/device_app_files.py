"""Read ThinkOS app bytes with remote exit-status checking (ADB shell v2)."""
import shlex
import subprocess
import base64
from pathlib import PurePosixPath


def read_app_file(serial, path):
    command = shlex.join(["run-as", "moe.kiwi.thinkos", "cat", str(path)])
    result = subprocess.run(["adb", "-s", serial, "shell", "-T", command], capture_output=True, timeout=25)
    if result.returncode == 0:
        return result.stdout
    if not result.stdout and b"No such file or directory" in result.stderr:
        return None
    raise RuntimeError(result.stderr.decode(errors="replace") or "Unable to read app file")


def restore_app_file(serial, path, content):
    """Restore bytes (or the original absence) while the application is stopped."""
    if content is None:
        command = shlex.join(["run-as", "moe.kiwi.thinkos", "rm", "-f", str(path)])
        data = None
    else:
        script = "mkdir -p " + shlex.quote(str(PurePosixPath(path).parent)) + " && base64 -d > " + shlex.quote(str(path))
        command = shlex.join(["run-as", "moe.kiwi.thinkos", "sh", "-c", script])
        data = base64.b64encode(content)
    subprocess.run(["adb", "-s", serial, "shell", "-T", command], input=data,
                   capture_output=True, check=True, timeout=25)
