#!/usr/bin/env python3
"""Build a read-only view of the Android Lua sources for the browser worker.

No downloads, package installation, or Android writes are performed here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
SIM = ROOT / "simulator"
CPP = ROOT / "android/app/src/main/cpp"
BOOTSTRAP = ROOT / "android/app/src/main/java/moe/kiwi/thinkos/plugin/LuaBootstrap.kt"
LOCK = SIM / "native/sources.lock.json"
JNI_OPERATIONS = {
    "CallBooleanMethod", "CallDoubleMethod", "CallIntMethod", "CallLongMethod", "CallObjectMethod",
    "DeleteGlobalRef", "DeleteLocalRef", "ExceptionCheck", "ExceptionClear", "FindClass",
    "GetArrayLength", "GetMethodID", "GetObjectArrayElement", "GetObjectClass", "GetStringChars",
    "GetStringLength", "GetStringRegion", "IsInstanceOf", "NewGlobalRef", "NewObject",
    "NewObjectArray", "NewString", "NewStringUTF", "PopLocalFrame", "PushLocalFrame",
    "ReleaseStringChars", "SetObjectArrayElement",
}


def source_paths() -> list[Path]:
    sources = [CPP / "tos_bridge.c", CPP / "lua-pattern-budget.patch", CPP / "PATCHES.md", BOOTSTRAP]
    sources += sorted((CPP / "lua").glob("*.c")) + sorted((CPP / "lua").glob("*.h"))
    sources += [CPP / "json" / name for name in ("yyjson.c", "yyjson.h", "LICENSE", "SOURCE.json")]
    return sorted(sources)


def source_manifest() -> dict:
    return {"version": 1, "files": {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                                  for path in source_paths()}}


def verify_sources() -> None:
    if not LOCK.is_file():
        raise ValueError("missing native/sources.lock.json; review sources before --update-lock")
    current, locked = source_manifest(), json.loads(LOCK.read_text())
    if current != locked:
        before, after = locked.get("files", {}), current["files"]
        changed = sorted(path for path in before.keys() | after.keys() if before.get(path) != after.get(path))
        raise ValueError("Android source drift; review and rerun conformance before updating the lock: " + ", ".join(changed))
    bridge = (CPP / "tos_bridge.c").read_text()
    actual = set(re.findall(r"\(\*[a-zA-Z]+\)->([A-Za-z_0-9]+)", bridge))
    if actual != JNI_OPERATIONS:
        raise ValueError(f"JNI adapter surface changed: added={actual-JNI_OPERATIONS}, removed={JNI_OPERATIONS-actual}")
    config = (CPP / "lua/luaconf.h").read_text()
    if not re.search(r"^#define LUA_32BITS\s+0\s*$", config, re.M):
        raise ValueError("Lua must retain signed int64 and double numbers on wasm32")


def extract_bootstrap(source: str) -> str:
    """Recognize this one literal declaration; reject Kotlin code/interpolation.

    This intentionally isn't a general Kotlin parser. Unsupported edits fail
    closed rather than silently producing a different trusted runtime.
    """
    pattern = (r'\A\s*package moe\.kiwi\.thinkos\.plugin\s+'
               r'(?:/\*.*?\*/\s*)?internal object LuaBootstrap\s*\{\s*'
               r'val code\s*=\s*"""(.*?)"""\.trimIndent\(\)\s*\}\s*\Z')
    match = re.fullmatch(pattern, source, re.S)
    if match is None or source.count('"""') != 2:
        raise ValueError("unsupported LuaBootstrap declaration: expected one raw literal with trimIndent()")
    body = match.group(1)
    if re.search(r"\$(?:\{|[^\W\d]\w*)", body, re.UNICODE):
        raise ValueError("Kotlin interpolation is forbidden in the extracted Lua bootstrap")
    # Kotlin trimIndent normalizes line endings and drops blank first/last lines.
    lines = re.split(r"\r\n|\n|\r", body)
    nonblank = [line for line in lines if line.strip()]
    indent = min((len(line)-len(line.lstrip()) for line in nonblank), default=0)
    first, last = 0, len(lines)
    if lines and not lines[0].strip(): first += 1
    if last > first and not lines[-1].strip(): last -= 1
    return "\n".join(line[indent:] if line.strip() else "" for line in lines[first:last])


EXPORTS = [
    "malloc", "free", "tos_frame_push", "tos_frame_pop", "tos_host_exception",
    "tos_value_bool", "tos_value_int", "tos_value_double", "tos_value_string", "tos_value_array",
    "tos_value_map", "tos_array_set", "tos_map_set", "tos_value_kind", "tos_value_length",
    "tos_value_i64", "tos_value_f64", "tos_value_chars", "tos_value_get", "tos_value_key",
    "tos_step_status", "tos_step_task", "tos_step_message", "tos_step_method", "tos_step_metric",
    "tos_shim_objects", "tos_vm_create", "tos_vm_start", "tos_vm_resume", "tos_vm_bind",
    "tos_vm_cancel", "tos_vm_memory", "tos_vm_destroy",
]


def build(output: Path, emcc: str | None = None) -> None:
    verify_sources()
    bootstrap = extract_bootstrap(BOOTSTRAP.read_text())
    local_compiler = SIM / ".toolchains/emsdk/upstream/emscripten/emcc"
    compiler = emcc or os.environ.get("EMCC") or (str(local_compiler) if local_compiler.is_file() else shutil.which("emcc"))
    if not compiler:
        raise RuntimeError("Emscripten emcc is unavailable. Install a verified local toolchain; this command never downloads one.")
    environment = os.environ.copy()
    if Path(compiler).resolve() == local_compiler.resolve():
        environment["EM_CONFIG"] = str(SIM / ".toolchains/emsdk/.emscripten")
        environment["EM_CACHE"] = str(SIM / ".toolchains/emsdk/upstream/emscripten/cache")
    version = subprocess.run([compiler, "--version"], check=True, capture_output=True, text=True, env=environment)
    if not re.search(r"\b6\.0\.13\b", version.stdout):
        raise RuntimeError("The reviewed simulator toolchain is Emscripten 6.0.13; refusing an unreviewed version")
    lua = sorted(path for path in (CPP / "lua").glob("*.c") if path.name not in {"lua.c", "luac.c"})
    output.mkdir(parents=True, exist_ok=True)
    # Build atomically in a sibling directory so a failed compiler cannot replace
    # the last usable runtime with a partially written .js/.wasm pair.
    with tempfile.TemporaryDirectory(prefix=".runtime-build-", dir=output.parent) as directory:
        staging = Path(directory)
        command = [compiler, "-O2", "-std=c11", "-Wall", "-Wextra", "-Wno-unused-parameter",
                   "-I" + str(SIM / "native/include"), "-I" + str(SIM / "native"),
                   "-I" + str(ROOT), "-I" + str(CPP / "lua"),
                   str(SIM / "native/tos_wasm.c"), str(SIM / "native/shim.c"),
                   *map(str, lua), str(CPP / "json/yyjson.c"),
                   "-DYYJSON_DISABLE_FILE=1", "-DYYJSON_DISABLE_UTILS=1",
                   "-DYYJSON_READER_DEPTH_LIMIT=25", "-DYYJSON_WRITER_DEPTH_LIMIT=25",
                   "-sMODULARIZE=1", "-sEXPORT_ES6=1", "-sENVIRONMENT=worker,node",
                   "-sSUPPORT_LONGJMP=emscripten", "-sFILESYSTEM=0", "-sALLOW_MEMORY_GROWTH=1",
                   "-sINITIAL_MEMORY=33554432", "-sMAXIMUM_MEMORY=134217728", "-sSTACK_SIZE=1048576",
                   "-sABORTING_MALLOC=0", "-sEMIT_EMSCRIPTEN_LICENSE=1",
                   "-sEXPORTED_FUNCTIONS=" + json.dumps(["_" + name for name in EXPORTS]),
                   "-sEXPORTED_RUNTIME_METHODS=['UTF8ToString','HEAPU16']", "-o", str(staging / "tos.js")]
        subprocess.run(command, check=True, cwd=ROOT, env=environment)
        (staging / "bootstrap.lua").write_text(bootstrap)
        (staging / "sources.json").write_text(json.dumps(source_manifest(), indent=2) + "\n")
        shutil.copyfile(SIM / "native/LICENSES.txt", staging / "LICENSES.txt")
        for path in staging.iterdir():
            os.replace(path, output / path.name)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify sources and bootstrap without compiling")
    parser.add_argument("--update-lock", action="store_true", help="explicitly accept reviewed source hashes")
    parser.add_argument("--output", type=Path, default=SIM / "public/runtime")
    parser.add_argument("--emcc", help="path to a verified installed Emscripten compiler")
    args = parser.parse_args()
    try:
        if args.update_lock:
            LOCK.write_text(json.dumps(source_manifest(), indent=2) + "\n")
        verify_sources()
        extract_bootstrap(BOOTSTRAP.read_text())
        if not args.check and not args.update_lock:
            build(args.output, args.emcc)
        print("ThinkOS native source lock and bootstrap verified" if args.check or args.update_lock else f"Built {args.output}")
        return 0
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
