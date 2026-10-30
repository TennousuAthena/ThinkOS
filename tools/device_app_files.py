"""Read ThinkOS app bytes with remote exit-status checking (ADB shell v2)."""
import shlex
import subprocess


def read_app_file(serial, path):
    command = shlex.join(["run-as", "moe.kiwi.thinkos", "cat", str(path)])
    result = subprocess.run(["adb", "-s", serial, "shell", "-T", command], capture_output=True, timeout=25)
    if result.returncode == 0:
        return result.stdout
    if not result.stdout and b"No such file or directory" in result.stderr:
        return None
    raise RuntimeError(result.stderr.decode(errors="replace") or "Unable to read app file")
