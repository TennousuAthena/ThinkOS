#!/usr/bin/env python3
"""Root-side updater integration tests using private disposable keys and a fixed fake executor.

No device, Magisk installation, privileged host path or restart is used. Run via run-updater.sh.
The target ROM's fixed /sbin/magisk -> magisk32 entry is checked as argv, never executed here.
"""
import array
import errno
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest

SYSTEM = Path(__file__).resolve().parents[1]


def command(*args):
    return subprocess.run(list(map(str, args)), capture_output=True, check=True).stdout


def public_xy(key):
    der = command('openssl', 'pkey', '-in', key, '-pubout', '-outform', 'DER')
    if len(der) != 91 or der[-65] != 4:
        raise AssertionError('Unexpected P-256 SPKI')
    return der[-64:]


class Updater(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.suite = tempfile.TemporaryDirectory(prefix='thinkos-updater-suite-')
        cls.directory = Path(cls.suite.name)
        cls.keys = []
        for index in range(3):
            key = cls.directory / ('test-key-' + str(index) + '.pem')
            command('openssl', 'genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256', '-out', key)
            key.chmod(0o600)
            cls.keys.append(key)
        header = cls.directory / 'test-public.h'
        rows = ['{' + ','.join('0x%02x' % byte for byte in public_xy(key)) + '}' for key in cls.keys[:2]]
        header.write_text('#define UP_SYSTEM_KEY_COUNT 2\nstatic const unsigned char up_system_keys[][64] = {' + ','.join(rows) + '};\n')
        command('bash', SYSTEM / 'build-updater.sh', '--host', '--keys-header', header)
        cls.binary = cls.directory / 'updaterd-keys'
        shutil.copy2(SYSTEM / 'build/updaterd-host', cls.binary)
        command('bash', SYSTEM / 'build-updater.sh', '--host')
        cls.keyless = cls.directory / 'updaterd-keyless'
        shutil.copy2(SYSTEM / 'build/updaterd-host', cls.keyless)
        # Compile the actual production metadata helper with synthetic stat
        # records; rejecting foreign uid/gid must not require privileged chown.
        harness = cls.directory / 'vector-metadata.c'
        harness.write_text('#define main updaterd_production_entry\n#include ' +
            json.dumps(str(SYSTEM / 'thinkos-updater/src/updaterd.c')) + '\n#undef main\n' + r'''
int main(int argc,char **argv) {
 if (argc==3 && !strcmp(argv[1],"--no-shebang")) {
  char *args[]={argv[2],NULL}, output[64];
  return execute_until("no-shebang-fixture",argv[2],args,output,sizeof(output),probe_deadline())==-1 && !output[0] ? 0 : 40;
 }
#ifdef UPDATER_HOST_TEST
 if (argc==3 && !strcmp(argv[1],"--pm-path")) {
  test_exec=argv[2]; char path[PATH_MAX];
  return !package_path(APP,path,sizeof(path)) && !strcmp(path,"/data/app/thinkos-app-old/base.apk") ? 0 : 41;
 }
#endif
 if (argc!=1) return 42;
 struct stat value; memset(&value,0,sizeof(value));
 value.st_uid=0; value.st_gid=0; value.st_mode=S_IFREG|0660;
 if (!vector_db_metadata(&value,0,0)) return 1;
 value.st_mode=S_IFREG|0600; if (!vector_db_metadata(&value,0,0)) return 2;
 value.st_mode=S_IFREG|0664; if (!vector_db_metadata(&value,0,0)) return 3;
 value.st_mode=S_IFREG|0666; if (vector_db_metadata(&value,0,0)) return 4;
 value.st_mode=S_IFREG|0662; if (vector_db_metadata(&value,0,0)) return 5;
 value.st_mode=S_IFREG|0660; value.st_uid=1; if (vector_db_metadata(&value,0,0)) return 6;
 value.st_uid=0; value.st_gid=1; if (vector_db_metadata(&value,0,0)) return 7;
 value.st_mode=S_IFREG|0640; if (vector_db_metadata(&value,0,0)) return 8;
 value.st_gid=0;
 const mode_t types[]={S_IFDIR,S_IFLNK,S_IFIFO,S_IFSOCK};
 for (size_t i=0;i<sizeof(types)/sizeof(types[0]);++i) {
  value.st_mode=types[i]|0660; if (vector_db_metadata(&value,0,0)) return 9;
 }
 return 0;
}
''')
        cls.metadata_binary = cls.directory / 'vector-metadata'
        sources = [SYSTEM / 'thinkos-updater/src' / name for name in ('guard_policy.c', 'module_verify.c', 'micro_ecc.c')]
        command(os.environ.get('CC') or 'clang', '-Wall', '-Wextra', '-Werror', '-O1', '-o', cls.metadata_binary,
                harness, *sources, SYSTEM / 'thinkos-updater/vendor/sha-2/sha-256.c')
        cls.pm_path_binary = cls.directory / 'updater-pm-path'
        command(os.environ.get('CC') or 'clang', '-Wall', '-Wextra', '-Werror', '-O1', '-DUPDATER_HOST_TEST',
                '-o', cls.pm_path_binary, harness, *sources, SYSTEM / 'thinkos-updater/vendor/sha-2/sha-256.c')

    @classmethod
    def tearDownClass(cls):
        cls.suite.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='thinkos-updater-')
        self.home = Path(self.temp.name)
        self.root = self.home / 'private'
        self.root.mkdir(mode=0o700)
        self.modules = self.home / 'modules'
        self.modules.mkdir()
        self.updates = self.home / 'modules_update'
        self.updates.mkdir()
        self.packages = self.home / 'packages.list'
        self.packages.write_text('moe.kiwi.thinkos 10074 0 /unused default none 0 0\n')
        self.tzdata = self.home / 'rom-tzdata'
        names = sorted(('America/Denver', 'Asia/Hong_Kong', 'Etc/GMT', 'Etc/UTC', 'Europe/Kiev'))
        index = 24
        data = index + len(names) * 52
        records = [name.encode().ljust(40, b'\0') + struct.pack('>III', n * 44, 44, 0) for n, name in enumerate(names)]
        self.tzdata.write_bytes(b'tzdata2021a\0' + struct.pack('>III', index, data, data + len(names) * 44) + b''.join(records) + (b'TZif' + b'\0' * 40) * len(names))
        (self.root / 'host-timezone').write_text('Asia/Hong_Kong')
        (self.root / 'host-boot-completed').write_text('1')
        for name in ('host-app', 'host-immersive'):
            (self.root / name).mkdir(mode=0o700)
            (self.root / name / 'base.apk').write_bytes(b'private-old-apk-' + name.encode())
        self.vector = self.home / 'vector.db'
        self.vector.write_bytes(b'old-vector-database')
        os.chown(self.vector, -1, os.getgid())  # Host fixtures model production root:root with our own identity.
        (self.root / 'host-pm-code').write_text('1')
        (self.root / 'host-pm-path').write_text('/data/app/thinkos-immersive-old/base.apk')
        (self.root / 'host-vector-state').write_text('1|/data/app/thinkos-immersive-old/base.apk\n')
        module = self.modules / 'thinkos-cast'
        module.mkdir()
        (module / 'module.prop').write_text('id=thinkos-cast\nversion=v1.0\nversionCode=1\n')
        (module / 'service.sh').write_text('old service\n')
        self.executor = self.home / 'fake-exec'
        # This subprocess is a stdlib-only fixture, not a second application.
        # The suite's python -I is not inherited across the C execve. Disable
        # ambient site/.pth startup inside every actual fake-exec process.
        self.executor.write_text('#!' + sys.executable + ' -S\n' +
            'import sys\nif not sys.flags.no_site:sys.exit(98)\n' +
            'import json,os,pathlib,re,time\n' +
            'root=pathlib.Path(' + repr(str(self.root)) + ')\n' +
            'updates=pathlib.Path(' + repr(str(self.updates)) + ')\n' + r'''
action=sys.argv[1]
program=sys.argv[2]
original_argv=sys.argv[3:]
pm_actions={"package-info","package-path","install-apk","rollback-apk","grant-language"}
is_pm=action in pm_actions
with (root/"program-invocations.jsonl").open("a") as out:
 out.write(json.dumps({"action":action,"program":program,"argv":original_argv})+"\n")
if is_pm:
 if program!="/system/bin/app_process" or original_argv[:3]!=["/system/bin/app_process","/system/bin","com.android.commands.pm.Pm"]:sys.exit(96)
 semantic=original_argv[3:]
else:
 known={"set-timezone":"/system/bin/service","install-module":"/sbin/magisk",
        "reboot":"/system/bin/reboot","guard-reboot":"/system/bin/reboot",
        "vector-state":"/system/bin/sqlite3","vector-backup":"/system/bin/sqlite3",
        "vector-sync":"/system/bin/sqlite3","vector-restore":"/system/bin/sqlite3",
        "guard-vector-disable":"/system/bin/sqlite3"}
 if program!=known.get(action) or not original_argv or original_argv[0]!=program:sys.exit(96)
 semantic=original_argv[1:]
expected_env={"PATH":"/system/bin:/system/xbin:/data/adb/magisk","LANG":"C","ANDROID_ROOT":"/system","ANDROID_DATA":"/data"}
if is_pm:expected_env["CLASSPATH"]="/system/framework/pm.jar"
fixed_env={name:os.environ.get(name) for name in expected_env}
# Interpreter/runtime keys are added after execve, not inherited from daemon
# envp. A minimal-env probe of the actual macOS venv interpreter also observed
# __CF_USER_TEXT_ENCODING; do not excuse that key on any other host platform.
runtime_env={"LC_CTYPE","__PYVENV_LAUNCHER__"}
if sys.platform=="darwin":runtime_env.add("__CF_USER_TEXT_ENCODING")
unexpected_env=sorted(set(os.environ)-set(expected_env)-runtime_env)
with (root/"child-environments.jsonl").open("a") as out:
 out.write(json.dumps({"action":action,"fixed":fixed_env,"unexpected":unexpected_env,"no_site":bool(sys.flags.no_site)})+"\n")
if fixed_env!=expected_env or unexpected_env:sys.exit(97)
# Preserve every existing assertion on the actual PM subcommand/flags/package
# after checking the real executable, Java prefix and dedicated classpath.
sys.argv=[sys.argv[0],action]+semantic
read_only=action in ("package-info","package-path","vector-state")
with (root/("read-executions.jsonl" if read_only else "executions.jsonl")).open("a") as out:
 out.write(json.dumps(sys.argv[1:])+"\n")
if (root/"slow-action").exists() and (root/"slow-action").read_text()==action:time.sleep(10)
if (root/"probe-delays.json").exists():time.sleep(json.loads((root/"probe-delays.json").read_text()).get(action,0))
if (root/"fail-action").exists() and (root/"fail-action").read_text()==action:sys.exit(9)
if action=="package-info":
 override=root/"host-pm-output"
 if override.exists():sys.stdout.buffer.write(override.read_bytes())
 elif (root/"host-pm-code").read_text()!="absent":
  print("package:"+(root/"host-pm-path").read_text()+"=moe.kiwi.thinkos.immersive versionCode:"+(root/"host-pm-code").read_text())
if action=="package-path":
 if sys.argv[2:]!=["path","moe.kiwi.thinkos"]:sys.exit(95)
 print("package:/data/app/thinkos-app-old/base.apk")
if action=="vector-state":
 source=root/("host-vector-output" if (root/"host-vector-output").exists() else "host-vector-state")
 sys.stdout.buffer.write(source.read_bytes())
if action=="install-module":
 fields=dict(line.split("=",1) for line in (root/"transaction/manifest.new").read_text().splitlines() if "=" in line)
 staged=updates/fields["id"];staged.mkdir()
 (staged/"module.prop").write_text("id="+fields["id"]+"\nversion=v1.1\nversionCode="+fields["code"]+"\n")
 (staged/"service.sh").write_text("new service\n")
if action in ("install-apk","rollback-apk") and "moe.kiwi.thinkos.immersive" in sys.argv:
 if action=="install-apk":
  fields=dict(line.split("=",1) for line in (root/"transaction/manifest.new").read_text().splitlines() if "=" in line)
  (root/"host-pm-code").write_text(fields["code"])
  (root/"host-pm-path").write_text("/data/app/thinkos-immersive-new-"+fields["code"]+"/base.apk")
 else:
  (root/"host-pm-code").write_text("1")
  (root/"host-pm-path").write_text("/data/app/thinkos-immersive-restored/base.apk")
if action=="vector-backup":
 (root/"transaction/vector.db").write_bytes(b"consistent-backup")
 (root/"transaction/vector-state.before").write_bytes((root/"host-vector-state").read_bytes())
if action=="vector-restore":
 (root/"host-vector-state").write_bytes((root/"transaction/vector-state.before").read_bytes())
if action=="vector-sync":
 match=re.search(r"apk_path='([^']+)'",sys.argv[-1])
 if not match:sys.exit(8)
 enabled=(root/"host-vector-state").read_text().split("|",1)[0]
 if "enabled=1" in sys.argv[-1]:enabled="1"
 (root/"host-vector-state").write_text(enabled+"|"+match[1]+"\n")
 print("1")
if action=="guard-vector-disable":
 path=(root/"host-vector-state").read_text().split("|",1)[1]
 (root/"host-vector-state").write_text("0|"+path)
 print("1")
if action=="set-timezone":
 value=sys.argv[-1]
 mismatch=root/"timezone-mismatch"
 if mismatch.exists() and (value!="Asia/Hong_Kong" or mismatch.read_text()=="always"):value="Etc/GMT"
 (root/"host-timezone").write_text(value)
''')
        self.executor.chmod(0o700)
        self.address = str(self.home / 'socket')
        self.process = None
        self.start()

    def tearDown(self):
        self.stop()
        self.temp.cleanup()

    def start(self, binary=None, override=True, sdk='27', boot=None, now=None, peer='10074', environment=None):
        args = [binary or self.binary, '--root', self.root, '--listen', self.address,
                '--packages-list', self.packages, '--modules', self.modules,
                '--modules-update', self.updates, '--vector-db', self.vector,
                '--test-exec', self.executor, '--test-sdk', sdk, '--test-tzdata', self.tzdata]
        if override:
            args += ['--test-peer-uid', peer]
        if boot is not None:
            args += ['--test-boot-id', boot]
        if now is not None:
            args += ['--test-now-ms', str(now)]
        self.process = subprocess.Popen(list(map(str, args)), stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment)
        until = time.monotonic() + 5
        while time.monotonic() < until:
            if self.process.poll() is not None:
                self.fail('Daemon exited during start: ' + self.process.communicate()[1].decode())
            if Path(self.address).exists():
                try:
                    if self.request('HELLO') == 'OK thinkos-updater 1':
                        return
                except OSError:
                    pass
            time.sleep(.02)
        self.fail('Daemon did not start')

    def stop(self):
        if self.process:
            self.process.terminate()
            try:
                self.process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.communicate(timeout=5)
            self.process = None

    def connect(self):
        peer = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        peer.settimeout(6)
        peer.connect(self.address)
        return peer

    def request(self, text, files=(), raw=False):
        with self.connect() as peer:
            descriptors = [os.open(path, os.O_RDONLY) for path in files]
            try:
                ancillary = [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', descriptors))] if descriptors else []
                peer.sendmsg([text if isinstance(text, bytes) else text.encode() + b'\n'], ancillary)
                data = b''
                while not data.endswith(b'\n'):
                    chunk = peer.recv(4096)
                    if not chunk:
                        break
                    data += chunk
                return data if raw else data.decode().rstrip('\n')
            finally:
                for descriptor in descriptors:
                    os.close(descriptor)

    def status(self):
        result = self.request('STATUS')
        self.assertTrue(result.startswith('OK '), result)
        return json.loads(result[3:])

    def signed(self, module='thinkos-cast', kind='magisk', code=2, payload=b'signed package bytes', key=0, declared_hash=None, declared_size=None):
        token = str(time.monotonic_ns())
        manifest = self.home / (token + '.txt')
        signature = self.home / (token + '.sig')
        package = self.home / (token + '.package')
        package.write_bytes(payload)
        document = ('thinkos-module 1\nid=' + module + '\nkind=' + kind + '\ncode=' + str(code) +
                    '\nversion=v1.1\nsha256=' + (declared_hash or hashlib.sha256(payload).hexdigest()) +
                    '\nsize=' + str(len(payload) if declared_size is None else declared_size) + '\n')
        manifest.write_text(document)
        command('openssl', 'dgst', '-sha256', '-sign', self.keys[key], '-out', signature, manifest)
        return manifest, signature, package

    def executions(self):
        # Only fixed read probes use the separate log. Invalid signatures/keyless
        # builds must still have an exactly empty mutation execution log.
        path = self.root / 'executions.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def read_executions(self):
        path = self.root / 'read-executions.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def child_environments(self):
        path = self.root / 'child-environments.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def program_invocations(self):
        path = self.root / 'program-invocations.jsonl'
        return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

    def module_status(self, module='thinkos-cast'):
        return next(item for item in self.status()['modules'] if item['id'] == module)

    def activate_boot(self, module='thinkos-cast', code=2, boot='actual-new-boot', reboot=True, now=None):
        if reboot:
            self.assertEqual(self.request('REBOOT'), 'OK')
        self.stop()
        if module != 'thinkos-immersive':
            installed = self.modules / module
            installed.mkdir(exist_ok=True)
            (installed / 'module.prop').write_text('id=' + module + '\nversion=v1.1\nversionCode=' + str(code) + '\n')
            pending = self.updates / module
            if pending.exists():
                shutil.rmtree(pending)
        self.start(boot=boot, now=now)

    def guard_policy(self):
        path = self.root / 'guard'
        path.mkdir(mode=0o700, exist_ok=True)
        policy = path / 'policy'
        policy.write_text('thinkos-guard-policy 1\nhealth_ms=900000\npoll_ms=5000\n')
        policy.chmod(0o600)
        return policy

    def guard(self, mode, boot, now, success=True, binary=None):
        args = [binary or self.binary, '--root', self.root, '--listen', self.address,
                '--packages-list', self.packages, '--modules', self.modules,
                '--modules-update', self.updates, '--vector-db', self.vector,
                '--test-exec', self.executor, '--test-peer-uid', '0', '--test-sdk', '27',
                '--test-tzdata', self.tzdata, '--test-boot-id', boot,
                '--test-now-ms', str(now), '--guard-mode', mode]
        result = subprocess.run(list(map(str, args)), capture_output=True, timeout=10)
        self.assertEqual(result.returncode == 0, success, result.stderr.decode())
        return result

    def test_live_authentication_is_rechecked(self):
        self.assertEqual(self.request('HELLO'), 'OK thinkos-updater 1')
        self.packages.write_text('moe.kiwi.thinkos 10075 0 /unused default none 0 0\n')
        self.assertEqual(self.request('HELLO'), 'ERR denied')
        self.assertFalse((self.root / 'state.bin').exists())

    def test_real_peer_credentials_allow_the_actual_uid(self):
        self.stop()
        self.packages.write_text('moe.kiwi.thinkos ' + str(os.getuid()) + ' 0 /unused default none 0 0\n')
        self.start(override=False)
        self.assertEqual(self.request('HELLO'), 'OK thinkos-updater 1')

    def test_keyless_build_has_no_mutation(self):
        self.stop()
        self.start(self.keyless)
        for name in ('BEGIN', 'HEALTHY', 'REBOOT', 'ROLLBACK'):
            self.assertEqual(self.request(name), 'ERR keys-missing')
        self.assertEqual(self.status()['keys'], 0)
        self.assertEqual(self.executions(), [])
        self.assertEqual(self.vector.read_bytes(), b'old-vector-database')
        self.assertFalse((self.root / 'state.bin').exists())

    def test_descriptor_counts_and_line_bounds(self):
        self.assertEqual(self.request('STAGE'), 'ERR bad-fds')
        self.assertEqual(self.request('INSTALL_APK'), 'ERR bad-fds')
        self.assertEqual(self.request('HELLO', [self.root / 'host-app/base.apk']), 'ERR bad-fds')
        self.assertEqual(self.request(b'HELLO\x00\n'), 'ERR bad-request')
        self.assertEqual(self.request(b'x' * 257 + b'\n'), 'ERR bad-request')
        self.assertEqual(self.request('RUN /system/bin/id'), 'ERR unknown')
        files = [self.root / 'host-app/base.apk'] * 12
        self.assertEqual(self.request('STAGE', files), 'ERR bad-request')
        self.assertEqual(self.request('HELLO'), 'OK thinkos-updater 1')

    def test_pipe_and_writable_descriptors_are_rejected(self):
        read_fd, write_fd = os.pipe()
        writable = os.open(self.root / 'host-app/base.apk', os.O_RDWR)
        try:
            for descriptor in (read_fd, writable):
                with self.connect() as peer:
                    peer.sendmsg([b'INSTALL_APK\n'], [(socket.SOL_SOCKET, socket.SCM_RIGHTS, array.array('i', [descriptor]))])
                    self.assertEqual(peer.recv(1024), b'ERR bad-request\n')
        finally:
            os.close(read_fd); os.close(write_fd); os.close(writable)

    def test_entire_line_has_one_two_second_deadline(self):
        began = time.monotonic()
        with self.connect() as peer:
            peer.sendall(b'H')
            self.assertEqual(peer.recv(1024), b'ERR bad-request\n')
        self.assertLess(time.monotonic() - began, 4)

    def test_stage_and_healthy_advance_a_durable_floor(self):
        files = self.signed()
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual((self.root / 'transaction/previous.apk').read_bytes(), b'private-old-apk-host-app')
        self.assertEqual(self.request('BEGIN'), 'ERR busy')
        self.assertEqual(self.request('STAGE', files), 'OK')
        self.assertEqual([row[0] for row in self.executions()], ['install-module'])
        self.assertEqual([row for row in self.program_invocations() if row['action'] == 'install-module'], [{
            'action': 'install-module', 'program': '/sbin/magisk',
            'argv': ['/sbin/magisk', '--install-module', str(self.root / 'transaction/thinkos-cast.payload')]}])
        self.assertEqual(self.status()['modules'][1]['staged'], 2)
        self.assertFalse(self.module_status()['active'])
        self.assertEqual(self.request('HEALTHY'), 'ERR not-active')
        self.activate_boot()
        self.assertEqual(self.request('HEALTHY'), 'OK')
        self.stop(); self.start()
        self.assertEqual(self.status()['modules'][1]['floor'], 2)
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', files), 'ERR old')

    def test_bad_signature_unknown_id_hash_and_size_do_not_execute(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        cases = [(self.signed(key=2), 'ERR bad-signature'),
                 (self.signed(module='untrusted-plugin'), 'ERR unknown-id'),
                 (self.signed(declared_hash='0' * 64), 'ERR bad-hash'),
                 (self.signed(declared_size=16 * 1024 * 1024 + 1), 'ERR too-big'),
                 (self.signed(code=1), 'ERR old')]
        for files, expected in cases:
            self.assertEqual(self.request('STAGE', files), expected)
        self.assertEqual(self.executions(), [])
        self.assertEqual(self.status()['modules'][1]['staged'], 0)

    def test_backup_root_symlinks_are_not_followed(self):
        outside = self.home / 'outside'
        outside.write_text('do not touch')
        (self.modules / 'thinkos-cast/link').symlink_to(outside)
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertTrue((self.root / 'transaction/thinkos-cast/link').is_symlink())
        self.assertEqual(self.request('STAGE', self.signed()), 'OK')
        (self.modules / 'thinkos-cast/service.sh').write_text('changed')
        self.assertEqual(self.request('ROLLBACK'), 'OK')
        self.assertEqual((self.modules / 'thinkos-cast/service.sh').read_text(), 'old service\n')
        self.assertEqual(outside.read_text(), 'do not touch')
        self.assertFalse(self.status()['transaction'])

    def test_application_installation_is_pinned_and_rollback_is_once(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        apk = self.home / 'candidate.apk'; apk.write_bytes(b'candidate fixture')
        self.assertEqual(self.request('INSTALL_APK', [apk]), 'OK')
        args = self.executions()[-1]
        self.assertEqual(args[0], 'install-apk')
        self.assertEqual(args[1:5], ['install', '-r', '-p', 'moe.kiwi.thinkos'])
        self.assertNotIn('-d', args)
        self.assertEqual(self.request('ROLLBACK'), 'OK')
        self.assertIn('-d', self.executions()[-1])
        self.assertEqual(self.request('ROLLBACK'), 'ERR no-transaction')

    def test_failed_installer_cannot_be_marked_healthy(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        (self.root / 'fail-action').write_text('install-module')
        self.assertEqual(self.request('STAGE', self.signed()), 'ERR install-failed')
        self.assertEqual(self.request('HEALTHY'), 'ERR no-transaction')
        self.assertEqual(self.request('ROLLBACK'), 'OK')
        self.assertEqual(self.status()['modules'][1]['floor'], 0)

    def test_vector_database_backup_and_path_are_synchronised(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        files = self.signed(module='thinkos-immersive', kind='vector-apk')
        self.assertEqual(self.request('STAGE', files), 'OK')
        actions = [row[0] for row in self.executions()]
        self.assertEqual(actions, ['vector-backup', 'install-apk', 'vector-sync'])
        self.assertEqual(self.request('ROLLBACK'), 'OK')
        actions = [row[0] for row in self.executions()]
        self.assertEqual(actions[-3:], ['rollback-apk', 'vector-restore', 'vector-sync'])

    def test_reboot_requires_staged_module_and_is_rate_limited(self):
        self.assertEqual(self.request('REBOOT'), 'ERR no-transaction')
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('REBOOT'), 'ERR no-transaction')
        self.assertEqual(self.request('STAGE', self.signed()), 'OK')
        self.assertEqual(self.request('REBOOT'), 'OK')
        self.activate_boot(reboot=False)
        self.assertEqual(self.request('HEALTHY'), 'OK')
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', self.signed(code=3)), 'OK')
        self.assertEqual(self.request('REBOOT'), 'ERR rate-limited')
        self.assertEqual([row[0] for row in self.executions()].count('reboot'), 1)

    def test_status_reports_actual_magisk_activation_and_canonical_versions(self):
        prop = self.modules / 'thinkos-cast/module.prop'
        self.assertEqual(self.module_status()['code'], 1)
        self.assertTrue(self.module_status()['active'])
        for marker in ('disable', 'remove', 'update'):
            with self.subTest(marker=marker):
                path = self.modules / 'thinkos-cast' / marker
                path.touch()
                self.assertEqual(self.module_status()['code'], 1)
                self.assertFalse(self.module_status()['active'])
                path.unlink()
        pending = self.updates / 'thinkos-cast'
        pending.mkdir()
        self.assertFalse(self.module_status()['active'])
        pending.rmdir()
        pending.symlink_to(self.home / 'nonexistent-pending-target')
        self.assertFalse(self.module_status()['active'])
        pending.unlink()
        for value in ('0', '01', '+1', '-1', '2147483648', '1 trailing', '1\nversionCode=2'):
            with self.subTest(code=value):
                prop.write_text('id=thinkos-cast\nversionCode=' + value + '\n')
                status = self.module_status()
                self.assertEqual(status['code'], -1)
                self.assertFalse(status['active'])
        prop.write_text('id=thinkos-cast\nversionCode=1\n')
        prop.chmod(0o666)
        self.assertEqual(self.module_status()['code'], -1)
        self.assertFalse(self.module_status()['active'])
        self.assertEqual(self.executions(), [])

    def test_healthy_requires_recorded_reboot_new_boot_and_completed_boot(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', self.signed()), 'OK')
        prop = self.modules / 'thinkos-cast/module.prop'
        prop.write_text('id=thinkos-cast\nversionCode=2\n')
        shutil.rmtree(self.updates / 'thinkos-cast')
        self.assertTrue(self.module_status()['active'])
        before = (self.root / 'state.bin').read_bytes()
        self.assertEqual(self.request('HEALTHY'), 'ERR not-active')  # Even target bytes cannot replace REBOOT evidence.
        self.assertEqual((self.root / 'state.bin').read_bytes(), before)
        self.assertEqual(self.request('REBOOT'), 'OK')
        self.assertEqual(self.request('HEALTHY'), 'ERR not-active')  # Same real boot still cannot commit.
        self.stop()
        (self.root / 'host-boot-completed').write_text('0')
        self.start(boot='new-but-not-complete', now=1000)
        self.assertEqual(self.request('HEALTHY'), 'ERR not-active')
        self.assertEqual(self.module_status()['floor'], 0)
        (self.root / 'host-boot-completed').write_text('1')
        self.assertEqual(self.request('HEALTHY'), 'OK')
        self.assertEqual(self.module_status()['floor'], 2)

    def test_healthy_does_not_commit_unknown_wrong_disabled_or_pending_module(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', self.signed()), 'OK')
        self.activate_boot(code=3)
        before = (self.root / 'state.bin').read_bytes()
        self.assertEqual(self.request('HEALTHY'), 'ERR not-active')  # Installed code must equal the signed target.
        prop = self.modules / 'thinkos-cast/module.prop'
        prop.write_text('id=thinkos-cast\nversionCode=unknown\n')
        self.assertEqual(self.request('HEALTHY'), 'ERR not-active')
        self.assertEqual(self.module_status()['code'], -1)
        prop.write_text('id=thinkos-cast\nversionCode=2\n')
        for marker in ('disable', 'remove', 'update'):
            with self.subTest(marker=marker):
                path = self.modules / 'thinkos-cast' / marker
                path.touch()
                self.assertEqual(self.request('HEALTHY'), 'ERR not-active')
                self.assertEqual((self.root / 'state.bin').read_bytes(), before)
                self.assertEqual(self.module_status()['floor'], 0)
                path.unlink()
        pending = self.updates / 'thinkos-cast'
        pending.mkdir()
        self.assertEqual(self.request('HEALTHY'), 'ERR not-active')
        self.assertEqual((self.root / 'state.bin').read_bytes(), before)
        pending.rmdir()
        self.assertEqual(self.request('HEALTHY'), 'OK')
        self.assertEqual(self.module_status()['floor'], 2)

    def test_apk_only_transaction_remains_healthy_without_a_reboot(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        apk = self.home / 'application-only.apk'
        apk.write_bytes(b'new application-only fixture')
        self.assertEqual(self.request('INSTALL_APK', [apk]), 'OK')
        self.assertEqual(self.request('REBOOT'), 'ERR no-transaction')
        self.assertEqual(self.request('HEALTHY'), 'OK')
        self.assertFalse(self.status()['transaction'])
        self.assertTrue(all(item['floor'] == 0 for item in self.status()['modules']))
        self.assertNotIn('reboot', [row[0] for row in self.executions()])

    def test_fixed_android_roots_and_environment_whitelist_survive_poisoned_parent(self):
        self.stop()
        environment = os.environ.copy()
        environment.update({
            'PATH': str(self.home / 'never-run-bin'), 'LANG': 'not_a_locale',
            'ANDROID_ROOT': str(self.home / 'not-system'), 'ANDROID_DATA': str(self.home / 'not-data'),
            'BOOTCLASSPATH': str(self.home / 'never-load-boot.jar'), 'CLASSPATH': str(self.home / 'never-load.jar'),
            'ENV': str(self.home / 'never-source.sh'), 'BASH_ENV': str(self.home / 'never-source-bash.sh'),
            'PYTHONPATH': str(self.home / 'never-import'), 'LD_LIBRARY_PATH': str(self.home / 'never-load-library'),
            'UPDATER_CHILD_POISON': 'must-not-reach-fixed-programs',
        })
        self.start(environment=environment)
        self.assertEqual(self.module_status('thinkos-immersive')['code'], 1)
        self.assertTrue(self.module_status('thinkos-immersive')['active'])
        self.assertEqual(self.request('GRANT_LANGUAGE'), 'OK')
        self.assertEqual(self.request('BEGIN'), 'OK')
        candidate = self.home / 'environment-candidate.apk'
        candidate.write_bytes(b'host-only candidate')
        self.assertEqual(self.request('INSTALL_APK', [candidate]), 'OK')
        expected = {'PATH': '/system/bin:/system/xbin:/data/adb/magisk', 'LANG': 'C',
                    'ANDROID_ROOT': '/system', 'ANDROID_DATA': '/data'}
        samples = self.child_environments()
        self.assertTrue({'package-info', 'vector-state', 'grant-language', 'install-apk'} <= {row['action'] for row in samples})
        pm_actions = {'package-info', 'package-path', 'grant-language', 'install-apk', 'rollback-apk'}
        self.assertTrue(all(row['fixed'] == {**expected, **({'CLASSPATH': '/system/framework/pm.jar'}
                            if row['action'] in pm_actions else {})} and row['unexpected'] == [] for row in samples))

    def test_fixed_android_roots_do_not_depend_on_parent_android_environment(self):
        self.stop()
        self.start(environment={'PATH': '/not-used-by-fixed-execve', 'LANG': 'C'})
        status = self.module_status('thinkos-immersive')
        self.assertEqual(status['code'], 1)
        self.assertTrue(status['active'])
        self.assertTrue(all(row['fixed']['ANDROID_ROOT'] == '/system' and row['fixed']['ANDROID_DATA'] == '/data' and
                            not row['unexpected'] for row in self.child_environments()))
        self.assertEqual(self.executions(), [])
        self.assertFalse((self.root / 'state.bin').exists())

    def test_pm_uses_real_fixed_java_prefix_and_private_classpath_for_every_operation(self):
        # HELLO means socket/peer/journal readiness, not a cached active result.
        # These synchronous fixture files must already agree before the first
        # STATUS. Do not warm up, poll STATUS, sleep, or retry a failed probe.
        apk = (self.root / 'host-pm-path').read_text()
        self.assertEqual((self.root / 'host-pm-code').read_text(), '1')
        self.assertEqual((self.root / 'host-vector-state').read_text(), '1|' + apk + '\n')
        self.assertFalse((self.root / 'slow-action').exists())
        self.assertFalse((self.root / 'probe-delays.json').exists())
        began = time.monotonic()
        status = self.module_status('thinkos-immersive')
        elapsed = time.monotonic() - began
        reads = self.read_executions()
        evidence = json.dumps({'elapsed_ms': round(elapsed * 1000, 3), 'component': status,
                               'reads': reads, 'invocations': self.program_invocations(),
                               'environments': self.child_environments()}, sort_keys=True)
        self.assertEqual(status['code'], 1, evidence)
        self.assertIs(status['active'], True, evidence)
        # Same host scheduler tolerance as the explicit shared-budget tests;
        # production's 1800ms PM+SQLite deadline and 2s client stay untouched.
        self.assertLess(elapsed, 2.5, evidence)
        self.assertEqual([row[0] for row in reads], ['package-info', 'vector-state'], evidence)
        print(json.dumps({'mod1_first_status': {'elapsed_ms': round(elapsed * 1000, 3),
                                              'code': status['code'], 'active': status['active'],
                                              'read_actions': [row[0] for row in reads]}}), flush=True)
        # Invoke actual package_path(APP) with the existing HOST-only executor;
        # the helper itself has no test APK-path shortcut.
        self.assertEqual(command(self.pm_path_binary, '--pm-path', self.executor), b'')
        self.assertEqual(self.request('GRANT_LANGUAGE'), 'OK')
        self.assertEqual(self.request('BEGIN'), 'OK')
        candidate = self.home / 'pm-entry-candidate.apk'
        candidate.write_bytes(b'host-only PM launcher fixture')
        self.assertEqual(self.request('INSTALL_APK', [candidate]), 'OK')
        self.assertEqual(self.request('ROLLBACK'), 'OK')
        pm_actions = {'package-info', 'package-path', 'grant-language', 'install-apk', 'rollback-apk'}
        invocations = self.program_invocations()
        self.assertTrue(pm_actions <= {row['action'] for row in invocations})
        for row in invocations:
            self.assertNotEqual(row['program'], '/system/bin/pm')  # AOSP8.1 script has no shebang.
            if row['action'] in pm_actions:
                self.assertEqual(row['program'], '/system/bin/app_process')
                self.assertEqual(row['argv'][:3], ['/system/bin/app_process', '/system/bin', 'com.android.commands.pm.Pm'])
        for row in self.child_environments():
            self.assertIs(row['no_site'], True)
            if row['action'] in pm_actions:
                self.assertEqual(row['fixed'].get('CLASSPATH'), '/system/framework/pm.jar')
            else:
                self.assertNotIn('CLASSPATH', row['fixed'])
            self.assertEqual(row['unexpected'], [])
        self.assertIn(['package-path', 'path', 'moe.kiwi.thinkos'], self.read_executions())
        self.assertEqual(self.executions(), [
            ['grant-language', 'grant', 'moe.kiwi.thinkos', 'android.permission.CHANGE_CONFIGURATION'],
            ['install-apk', 'install', '-r', '-p', 'moe.kiwi.thinkos', str(self.root / 'transaction/application.apk')],
            ['rollback-apk', 'install', '-r', '-d', '-p', 'moe.kiwi.thinkos', str(self.root / 'transaction/previous.apk')]])

    def test_real_execve_rejects_no_shebang_script_and_never_falls_back_to_a_shell(self):
        script = self.home / 'no-shebang-pm'
        script.write_text("# Script without an interpreter header\nprintf 'unexpected-shell-fallback\\n'\n")
        script.chmod(0o700)
        # This is a real host kernel ENOEXEC, not a fake response. The C harness
        # then uses the same production execute_until and must keep that failure.
        with self.assertRaises(OSError) as failure:
            subprocess.run([str(script)], capture_output=True, check=True, timeout=5)
        self.assertEqual(failure.exception.errno, errno.ENOEXEC)
        self.assertEqual(command(self.metadata_binary, '--no-shebang', script), b'')

    def test_vector_metadata_rejects_foreign_identity_without_privileged_host_files(self):
        self.assertEqual(command(self.metadata_binary), b'')

    def test_vector_group_writable_database_supports_status_backup_sync_and_restore(self):
        self.vector.chmod(0o660)
        self.assertTrue(self.module_status('thinkos-immersive')['active'])
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', self.signed(module='thinkos-immersive', kind='vector-apk')), 'OK')
        self.assertEqual([row[0] for row in self.executions()], ['vector-backup', 'install-apk', 'vector-sync'])
        self.assertTrue(self.module_status('thinkos-immersive')['active'])
        self.assertEqual(self.request('ROLLBACK'), 'OK')
        self.assertEqual([row[0] for row in self.executions()][-3:], ['rollback-apk', 'vector-restore', 'vector-sync'])
        self.assertTrue(self.module_status('thinkos-immersive')['active'])

    def test_vector_world_writable_symlink_and_fifo_are_rejected_before_sqlite(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        files = self.signed(module='thinkos-immersive', kind='vector-apk')
        self.vector.chmod(0o666)
        self.assertFalse(self.module_status('thinkos-immersive')['active'])
        self.assertEqual(self.request('STAGE', files), 'ERR vector-unavailable')
        self.vector.unlink()
        outside = self.home / 'outside-vector.db'
        outside.write_bytes(b'preserve external database')
        self.vector.symlink_to(outside)
        self.assertFalse(self.module_status('thinkos-immersive')['active'])
        self.assertEqual(self.request('STAGE', files), 'ERR vector-unavailable')
        self.assertEqual(outside.read_bytes(), b'preserve external database')
        self.vector.unlink()
        os.mkfifo(self.vector, 0o600)
        began = time.monotonic()
        self.assertFalse(self.module_status('thinkos-immersive')['active'])
        self.assertLess(time.monotonic() - began, 2.5)
        self.assertEqual(self.request('STAGE', files), 'ERR vector-unavailable')
        self.assertNotIn('vector-state', [row[0] for row in self.read_executions()])
        self.assertEqual(self.executions(), [])

    def test_immersive_uses_package_manager_code_and_fixed_read_only_commands(self):
        bogus = self.modules / 'thinkos-immersive'
        bogus.mkdir()
        (bogus / 'module.prop').write_text('id=thinkos-immersive\nversionCode=999\n')
        status = self.module_status('thinkos-immersive')
        self.assertEqual(status['code'], 1)
        self.assertTrue(status['active'])
        rows = self.read_executions()
        self.assertEqual(rows[0], ['package-info', 'list', 'packages', '-f', '--show-versioncode', '--user', '0', 'moe.kiwi.thinkos.immersive'])
        self.assertEqual(rows[1][0], 'vector-state')
        self.assertEqual(rows[1][1:7], ['-batch', '-noheader', '-separator', '|', '-init', '/dev/null'])
        self.assertEqual(rows[1][-2], str(self.vector))
        self.assertEqual(rows[1][-1], "SELECT enabled,apk_path FROM modules WHERE module_pkg_name COLLATE BINARY='moe.kiwi.thinkos.immersive';")
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', self.signed(module='thinkos-immersive', kind='vector-apk', code=1)), 'ERR old')
        self.assertEqual(self.executions(), [])
        self.assertEqual(self.request('STAGE', self.signed(module='thinkos-immersive', kind='vector-apk', code=2)), 'OK')
        self.assertEqual(self.module_status('thinkos-immersive')['code'], 2)
        self.assertEqual(self.module_status('thinkos-immersive')['staged'], 2)

    def test_immersive_full_package_name_match_does_not_accept_filter_lookalikes(self):
        path = (self.root / 'host-pm-path').read_text()
        output = self.root / 'host-pm-output'
        other = 'package:' + path + '=moe.kiwi.thinkos.immersive.lookalike versionCode:999\n'
        output.write_text(other)
        self.assertEqual(self.module_status('thinkos-immersive')['code'], 0)
        self.assertFalse(self.module_status('thinkos-immersive')['active'])
        output.write_text(other + 'package:' + path + '=moe.kiwi.thinkos.immersive versionCode:1\n')
        self.assertEqual(self.module_status('thinkos-immersive')['code'], 1)
        self.assertTrue(self.module_status('thinkos-immersive')['active'])
        output.write_text('')
        self.assertEqual(self.module_status('thinkos-immersive')['code'], 0)
        self.assertFalse(self.module_status('thinkos-immersive')['active'])
        self.assertEqual(self.executions(), [])

    def test_immersive_unknown_noncanonical_and_unsafe_package_output_cannot_stage(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        files = self.signed(module='thinkos-immersive', kind='vector-apk')
        path = (self.root / 'host-pm-path').read_text()
        output = self.root / 'host-pm-output'
        values = [('package:' + path + '=moe.kiwi.thinkos.immersive versionCode:' + value + '\n').encode()
                  for value in ('0', '01', '+1', '-1', '2147483648', '1 trailing', 'unknown')]
        values += [('package:' + value + '=moe.kiwi.thinkos.immersive versionCode:1\n').encode()
                   for value in ("/data/app/unsafe'quote/base.apk", '/data/app/../other/base.apk',
                                 '/data/app//other/base.apk', '/data/app/./other/base.apk', '/system/app/other/base.apk')]
        valid = ('package:' + path + '=moe.kiwi.thinkos.immersive versionCode:1\n').encode()
        values += [valid + valid, valid.rstrip(b'\n'), valid + b'\0ignored', b'x' * 20000]
        for value in values:
            with self.subTest(output=value[:100]):
                output.write_bytes(value)
                status = self.module_status('thinkos-immersive')
                self.assertEqual(status['code'], -1)
                self.assertFalse(status['active'])
                self.assertEqual(self.request('STAGE', files), 'ERR installed-state')
                self.assertEqual(self.module_status('thinkos-immersive')['floor'], 0)
        self.assertEqual(self.executions(), [])

    def test_vector_health_requires_enabled_exact_apk_path_and_actual_reboot(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', self.signed(module='thinkos-immersive', kind='vector-apk')), 'OK')
        self.assertEqual(self.request('HEALTHY'), 'ERR not-active')
        self.activate_boot(module='thinkos-immersive', boot='vector-actual-new-boot')
        path = (self.root / 'host-pm-path').read_text()
        before = (self.root / 'state.bin').read_bytes()
        output = self.root / 'host-vector-output'
        for value in ('0|' + path + '\n', '1|/data/app/old/base.apk\n', '1|' + path.upper() + '\n',
                      '1|' + path + '\n1|' + path + '\n', '', '1|' + path):
            with self.subTest(vector=value):
                output.write_text(value)
                self.assertFalse(self.module_status('thinkos-immersive')['active'])
                self.assertEqual(self.request('HEALTHY'), 'ERR not-active')
                self.assertEqual((self.root / 'state.bin').read_bytes(), before)
        output.unlink()
        self.assertTrue(self.module_status('thinkos-immersive')['active'])
        self.assertEqual(self.request('HEALTHY'), 'OK')
        self.assertEqual(self.module_status('thinkos-immersive')['floor'], 2)

    def test_status_probe_timeout_uses_real_monotonic_when_guard_clock_is_frozen(self):
        self.stop()
        self.start(now=1000)
        (self.root / 'slow-action').write_text('package-info')
        began = time.monotonic()
        status = self.module_status('thinkos-immersive')
        self.assertLess(time.monotonic() - began, 2.5)
        self.assertEqual(status['code'], -1)
        self.assertFalse(status['active'])
        self.assertEqual(self.request('HELLO'), 'OK thinkos-updater 1')
        self.assertEqual(self.executions(), [])

    def test_status_sqlite_shares_the_total_real_probe_budget(self):
        self.stop()
        self.start(now=1000)
        (self.root / 'probe-delays.json').write_text(json.dumps({'package-info': 1.05, 'vector-state': 1.05}))
        began = time.monotonic()
        status = self.module_status('thinkos-immersive')
        self.assertLess(time.monotonic() - began, 2.5)
        self.assertEqual(status['code'], 1)
        self.assertFalse(status['active'])
        self.assertEqual([row[0] for row in self.read_executions()], ['package-info', 'vector-state'])
        self.assertEqual(self.request('HELLO'), 'OK thinkos-updater 1')
        self.assertEqual(self.executions(), [])

    def test_healthy_timeout_cannot_advance_a_floor_with_frozen_test_clock(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', self.signed(module='thinkos-immersive', kind='vector-apk')), 'OK')
        self.activate_boot(module='thinkos-immersive', boot='vector-trial-boot', now=1000)
        (self.root / 'slow-action').write_text('vector-state')
        before = (self.root / 'state.bin').read_bytes()
        began = time.monotonic()
        self.assertEqual(self.request('HEALTHY'), 'ERR not-active')
        self.assertLess(time.monotonic() - began, 2.5)
        self.assertEqual((self.root / 'state.bin').read_bytes(), before)
        (self.root / 'slow-action').unlink()
        self.assertEqual(self.module_status('thinkos-immersive')['floor'], 0)
        self.assertEqual(self.request('HEALTHY'), 'OK')

    def test_corrupt_or_symlinked_private_state_does_not_reset_floors(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.stop()
        path = self.root / 'state.bin'
        path.write_bytes(b'corrupt retained state')
        result = subprocess.run([str(self.binary), '--root', str(self.root), '--listen', self.address], capture_output=True, timeout=5)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(path.read_bytes(), b'corrupt retained state')

    def test_timezone_and_language_setup_work_without_distribution_keys(self):
        self.stop()
        self.start(self.keyless)
        self.assertEqual(self.status()['setup'], {'timezone': True, 'language': True, 'device_owner': False})
        self.assertEqual(self.request('SET_TIMEZONE Etc/UTC'), 'OK')
        self.assertEqual((self.root / 'host-timezone').read_text(), 'Etc/UTC')
        grant = self.request('GRANT_LANGUAGE')
        diagnostic = None
        if grant != 'OK':
            # Only these fixed fake logs contain program/argv, known fixed env
            # values and semantic calls. Never enumerate the directory or read
            # signatures/private keys. Missing/partial logs are useful evidence.
            observed = {'daemon_exit': self.process.poll() if self.process else None}
            for name in ('program-invocations.jsonl', 'child-environments.jsonl', 'executions.jsonl'):
                try:
                    with (self.root / name).open('rb') as stream:
                        stream.seek(0, os.SEEK_END)
                        stream.seek(max(0, stream.tell() - 4096))
                        observed[name] = stream.read(4096).decode('utf-8', errors='replace').splitlines()[-2:]
                except OSError as error:
                    observed[name] = {'read_error': type(error).__name__}
            diagnostic = 'fixed fake diagnostic: ' + json.dumps(observed, ensure_ascii=True)
        self.assertEqual(grant, 'OK', diagnostic)
        self.assertEqual(self.executions(), [
            ['set-timezone', 'call', 'alarm', '3', 's16', 'Etc/UTC'],
            ['grant-language', 'grant', 'moe.kiwi.thinkos', 'android.permission.CHANGE_CONFIGURATION']])
        self.assertFalse((self.root / 'state.bin').exists())

    def test_timezone_unknown_and_malformed_ids_never_execute_service(self):
        for value in ('Mars/Unknown', 'Europe/Kyiv', '../Etc/UTC', 'Etc/UTC other', '', 'A' * 65, 'GMT+08:00'):
            self.assertEqual(self.request('SET_TIMEZONE ' + value), 'ERR zone-unknown', value)
        for raw in (b'SET_TIMEZONE Etc/UTC\x00\n', b'SET_TIMEZONE Etc/UTC\r\n', b'SET_TIMEZONE \xe4\xb8\xad\n'):
            self.assertEqual(self.request(raw), 'ERR bad-request')
        self.assertEqual((self.root / 'host-timezone').read_text(), 'Asia/Hong_Kong')
        self.assertEqual(self.executions(), [])

    def test_timezone_success_requires_property_readback_and_restores_mismatch(self):
        (self.root / 'timezone-mismatch').write_text('new-only')
        self.assertEqual(self.request('SET_TIMEZONE Etc/UTC'), 'ERR zone-unknown')
        self.assertEqual((self.root / 'host-timezone').read_text(), 'Asia/Hong_Kong')
        self.assertEqual([row[-1] for row in self.executions()], ['Etc/UTC', 'Asia/Hong_Kong'])

    def test_timezone_restore_failure_is_explicit(self):
        (self.root / 'timezone-mismatch').write_text('always')
        self.assertEqual(self.request('SET_TIMEZONE Etc/UTC'), 'ERR zone-restore-failed')
        self.assertEqual((self.root / 'host-timezone').read_text(), 'Etc/GMT')

    def test_timezone_database_is_bounded_and_validated_independently_of_app(self):
        for malformed in (b'not a database', self.tzdata.read_bytes()[:24],
                          b'tzdata2021a\0' + struct.pack('>III', 24, 0xffffffff, 0xffffffff)):
            self.tzdata.write_bytes(malformed)
            self.assertEqual(self.request('SET_TIMEZONE Etc/UTC'), 'ERR zone-unknown')
        self.assertEqual(self.executions(), [])

    def test_setup_is_api27_only_and_never_enables_device_owner(self):
        self.stop()
        self.start(sdk='28')
        self.assertEqual(self.request('SET_TIMEZONE Etc/UTC'), 'ERR unsupported')
        self.assertEqual(self.request('GRANT_LANGUAGE'), 'ERR unsupported')
        self.assertEqual(self.request('DEVICE_OWNER_ON'), 'ERR unavailable')
        self.assertEqual(self.status()['setup'], {'timezone': False, 'language': False, 'device_owner': False})
        self.assertEqual(self.executions(), [])

    def test_setup_requires_peer_authentication_and_has_no_general_grant(self):
        self.assertEqual(self.request('GRANT_LANGUAGE other.permission'), 'ERR unknown')
        self.assertEqual(self.request('SET_TIMEZONE'), 'ERR unknown')
        self.packages.write_text('moe.kiwi.thinkos 10075 0 /unused default none 0 0\n')
        self.assertEqual(self.request('SET_TIMEZONE Etc/UTC'), 'ERR denied')
        self.assertEqual(self.request('GRANT_LANGUAGE'), 'ERR denied')
        self.assertEqual(self.executions(), [])

    def test_guard_requires_private_policy_and_root_peer(self):
        self.assertEqual(self.request('BEGIN'), 'OK')
        token = self.status()['id']
        self.assertEqual(self.request('GUARD_ROLLBACK ' + token), 'ERR denied')
        self.stop()
        self.guard('tick', 'boot-a', 1000, success=False)
        self.assertEqual([row[0] for row in self.executions()], [])
        self.assertFalse((self.root / 'guard-state.bin').exists())

    def test_guard_waits_full_health_window_and_only_reboots_once_after_timeout(self):
        self.guard_policy()
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', self.signed()), 'OK')
        self.assertEqual(self.request('REBOOT'), 'OK')
        self.stop()
        self.guard('tick', 'trial-boot', 1000)
        self.guard('tick', 'trial-boot', 900999)
        self.assertNotIn('guard-reboot', [row[0] for row in self.executions()])
        self.guard('tick', 'trial-boot', 901000)
        self.assertEqual([row[0] for row in self.executions()].count('guard-reboot'), 1)
        self.guard('tick', 'trial-boot', 902000)
        self.guard('early', 'after-rollback-boot', 1000, success=False)  # boot_completed must be zero for early
        self.assertEqual([row[0] for row in self.executions()].count('guard-reboot'), 1)
        self.start()
        self.assertFalse(self.status()['transaction'])
        self.assertEqual(self.status()['result'], 2)

    def test_guard_early_restores_failed_boot_then_finishes_without_new_daemon(self):
        self.guard_policy()
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.assertEqual(self.request('STAGE', self.signed()), 'OK')
        self.assertEqual(self.request('REBOOT'), 'OK')
        self.stop()
        self.guard('tick', 'old-complete-boot', 1000)
        (self.root / 'host-boot-completed').write_text('0')
        self.guard('early', 'first-trial-boot', 100)
        (self.modules / 'thinkos-cast/service.sh').write_text('broken candidate')
        self.guard('early', 'second-trial-boot', 100)
        self.assertEqual((self.modules / 'thinkos-cast/service.sh').read_text(), 'old service\n')
        self.assertNotIn('guard-reboot', [row[0] for row in self.executions()])
        (self.root / 'host-boot-completed').write_text('1')
        # A stable keyless external binary may recover the already-private backup;
        # it still cannot BEGIN a new distribution transaction.
        self.guard('tick', 'second-trial-boot', 200, binary=self.keyless)
        self.assertEqual([row[0] for row in self.executions()].count('guard-reboot'), 1)
        self.start(self.keyless)
        self.assertFalse(self.status()['transaction'])
        self.assertEqual(self.request('BEGIN'), 'ERR keys-missing')

    def test_guard_stale_token_cannot_race_healthy_and_a_new_begin(self):
        self.guard_policy()
        self.assertEqual(self.request('BEGIN'), 'OK')
        first = self.status()['id']
        self.assertEqual(self.request('STAGE', self.signed()), 'OK')
        self.activate_boot()
        self.assertEqual(self.request('HEALTHY'), 'OK')
        self.assertEqual(self.request('BEGIN'), 'OK')
        second = self.status()['id']
        self.assertNotEqual(first, second)
        self.stop()
        self.start(peer='0', boot='same-boot', now=2000000)
        self.assertEqual(self.request('GUARD_ROLLBACK ' + first), 'ERR no-transaction')
        self.assertEqual(self.status()['id'], second)
        self.assertEqual(self.request('GUARD_ROLLBACK ' + second), 'ERR guard-state')
        self.assertNotIn('guard-reboot', [row[0] for row in self.executions()])

    def test_guard_policy_corruption_stops_automatic_actions(self):
        policy = self.guard_policy()
        self.assertEqual(self.request('BEGIN'), 'OK')
        self.stop()
        for data in ('', 'thinkos-guard-policy 1\nhealth_ms=1\npoll_ms=5000\n',
                     'thinkos-guard-policy 1\nhealth_ms=900000\npoll_ms=0\n'):
            policy.write_text(data)
            self.guard('tick', 'boot-a', 1000, success=False)
        self.assertNotIn('guard-reboot', [row[0] for row in self.executions()])


if __name__ == '__main__':
    unittest.main()
