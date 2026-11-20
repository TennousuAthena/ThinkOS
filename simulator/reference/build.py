#!/usr/bin/env python3
"""Compile a read-only allowlist of production Kotlin to JS and a JVM reference runner.

No Android files, build configuration or caches are changed. Compiler jars are read
from the existing Gradle cache; the JS standard library is a fixed local dependency.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
VERSION = '2.2.0'
STDLIB_JS_SHA256 = 'd68bcaa6ca7d6b69ed3a263323811eadb46ecebc4d6b3cd89eb3a0ad971686b9'
ORIGINAL = ROOT / 'android/app/src/main/java/moe/kiwi/thinkos'
SOURCES = ['ui/RenderGeometry.kt', 'ui/PanelGeometry.kt', 'ui/TileColor.kt', 'core/NavStack.kt']

def jar(group, name, version):
    folder = Path.home() / '.gradle/caches/modules-2/files-2.1' / group / name / version
    matches = sorted(folder.glob('*/*.jar'))
    if len(matches) != 1:
        raise SystemExit(f'Expected one cached {group}:{name}:{version}; found {len(matches)}. Install the documented Kotlin compiler dependencies first.')
    return matches[0]

def run(*args):
    result = subprocess.run(list(map(str, args)), cwd=HERE, capture_output=True, text=True)
    if result.returncode:
        print(result.stdout, end='')
        print(result.stderr, end='', file=sys.stderr)
        raise SystemExit(result.returncode)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--jvm-only', action='store_true')
    args = parser.parse_args()
    java = os.environ.get('JAVA_HOME')
    if not java and sys.platform == 'darwin':
        result = subprocess.run(['/usr/libexec/java_home', '-v', '17'], capture_output=True, text=True)
        java = result.stdout.strip() if result.returncode == 0 else ''
    executable = str(Path(java) / 'bin/java') if java else 'java'
    stdlib = jar('org.jetbrains.kotlin', 'kotlin-stdlib', VERSION)
    annotations = jar('org.jetbrains', 'annotations', '23.0.0')
    compiler = [jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', VERSION), stdlib,
                jar('org.jetbrains.kotlin', 'kotlin-script-runtime', VERSION),
                jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
                jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'),
                jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'), annotations]
    common = [ORIGINAL / source for source in SOURCES] + [HERE / 'Facade.kt']
    before = {source: hashlib.sha256((ORIGINAL / source).read_bytes()).hexdigest() for source in SOURCES}
    build = HERE / 'build'
    build.mkdir(exist_ok=True)
    jvm = build / 'reference.jar'
    command = [executable, '-cp', os.pathsep.join(map(str, compiler))]
    run(*command, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', os.pathsep.join(map(str, [stdlib, annotations])), '-d', jvm, *common, HERE / 'ReferenceMain.kt')
    (build / 'jvm.json').write_text(json.dumps({'java': executable, 'classpath': os.pathsep.join(map(str, [jvm, stdlib])), 'main': 'thinkos.simulator.reference.ReferenceMainKt'}))
    if not args.jvm_only:
        klib = HERE / '.cache' / f'kotlin-stdlib-js-{VERSION}.klib'
        if not klib.is_file():
            raise SystemExit(f'Missing {klib}. Download the fixed Kotlin/JS standard library from Maven Central first; see README.md.')
        if hashlib.sha256(klib.read_bytes()).hexdigest() != STDLIB_JS_SHA256:
            raise SystemExit('Kotlin/JS standard library checksum mismatch.')
        generated = HERE / 'generated'
        generated.mkdir(exist_ok=True)
        run(*command, 'org.jetbrains.kotlin.cli.js.K2JSCompiler', '-Xir-produce-klib-file', '-ir-output-dir', build, '-ir-output-name', 'thinkos-reference', '-libraries', klib, *common, HERE / 'Exports.kt')
        run(*command, 'org.jetbrains.kotlin.cli.js.K2JSCompiler', '-Xir-produce-js', '-Xgenerate-dts', '-Xir-dce', '-Xinclude=' + str(build / 'thinkos-reference.klib'), '-ir-output-dir', generated, '-ir-output-name', 'thinkos-reference', '-libraries', klib, '-module-kind', 'es', '-main', 'noCall')
        (generated / 'thinkos-reference.d.mts').write_bytes((generated / 'thinkos-reference.d.ts').read_bytes())
    after = {source: hashlib.sha256((ORIGINAL / source).read_bytes()).hexdigest() for source in SOURCES}
    if before != after:
        raise SystemExit('Production Kotlin changed during reference compilation; retry from a stable snapshot.')
    (build / 'source-hashes.json').write_text(json.dumps(before, indent=2) + '\n')
    print('Built unchanged production Kotlin as JVM' + ('' if args.jvm_only else ' and JS') + '.')

if __name__ == '__main__':
    main()
