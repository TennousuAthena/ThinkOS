"""Read-only source guards and executable native adapter conformance."""
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("thinkos_build_wasm", ROOT / "simulator/scripts/build_wasm.py")
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


class NativeSources(unittest.TestCase):
    def test_sources_are_locked(self):
        build.verify_sources()

    def test_bootstrap_matches_current_literal(self):
        source = build.BOOTSTRAP.read_text()
        extracted = build.extract_bootstrap(source)
        self.assertEqual(extracted, source.split('"""')[1].strip())
        self.assertTrue(extracted.startswith("local host, await"))
        self.assertIn('tos={config=immutable(host("config")),json=json}', extracted)

    def test_bootstrap_rejects_expressions_and_interpolation(self):
        source = build.BOOTSTRAP.read_text()
        for changed in (source.replace(".trimIndent()", ".trimMargin()"),
                        source.replace("local host", "${runSomething()} local host"),
                        source.replace("local host", "$host local host"),
                        source.replace('val code = """', 'val code = other + """'),
                        source + 'val another = """hidden"""'):
            with self.subTest(changed=changed[:60]), self.assertRaises(ValueError):
                build.extract_bootstrap(changed)

    def test_trim_indent_and_bare_dollar(self):
        source = 'package moe.kiwi.thinkos.plugin\ninternal object LuaBootstrap {\nval code = """\n    a\n      b\n    ^end$\n""".trimIndent()\n}'
        self.assertEqual(build.extract_bootstrap(source), "a\n  b\n^end$")

    @unittest.skipUnless(shutil.which("clang"), "clang unavailable")
    def test_production_bridge_via_simulator_jni(self):
        cpp = ROOT / "android/app/src/main/cpp"
        native = ROOT / "simulator/native"
        lua = sorted(path for path in (cpp / "lua").glob("*.c") if path.name not in {"lua.c", "luac.c"})
        with tempfile.TemporaryDirectory(prefix="thinkos-simulator-native-") as directory:
            output = Path(directory)
            bootstrap = output / "bootstrap.lua"
            bootstrap.write_text(build.extract_bootstrap(build.BOOTSTRAP.read_text()))
            command = [shutil.which("clang"), "-std=c11", "-O2", "-Wall", "-Wextra", "-Wno-unused-parameter",
                       "-I"+str(native / "include"), "-I"+str(native), "-I"+str(ROOT), "-I"+str(cpp / "lua"),
                       str(native / "tos_wasm.c"), str(native / "shim.c"), str(native / "conformance.c"),
                       *map(str, lua), str(cpp / "json/yyjson.c"), "-lm", "-o", str(output / "conformance")]
            compiled = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run([str(output / "conformance"), str(bootstrap)], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("PASS simulator JNI bridge", result.stdout)


if __name__ == "__main__":
    unittest.main()
