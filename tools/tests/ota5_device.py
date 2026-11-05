#!/usr/bin/env python3
"""MIT. OTA-5 signed-input plan, spare preflight and explicit normal public UI runner.

Default plan performs host verification only. --execute-preflight adds fixed
read-only ADB observations, without starting instrumentation or an HTTP server.
--execute-ui adds public settings/native CHECK/DOWNLOAD; --execute-install also
accepts native INSTALL and observes a real new-process 600-second health trial.
No private keys, reboot, permission/Owner change, journal clear/raw restore or
automatic/fault/crash/rollback driver exists. Default and preflight remain read-only.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
import ota_lan
import release
from release_tools import no_symlink_path
from ota5_origin import Ota5Origin

APP = 'moe.kiwi.thinkos'
PHYSICAL = re.compile(r'[A-Za-z0-9_.:-]{1,100}')
MAX_OBSERVATION = 64 * 1024
PUBLIC = ('keys.json', 'system-keys.json', 'updater_keys.h', 'key-inventory.json', 'apk-certificate.der')
PENDING = ('Production-source UI check/download/install and real 600-second health are not run.',
           'Automatic installation, signed semantic refusal samples and commit-bound crash/rollback remain pending.',
           'No claim is made about current transaction state, device-to-LAN reachability or preservation across an update.')


def require(value, message):
    if not value:
        raise ValueError(message)
    return value


def _spare(serial, physical):
    for value in (serial, physical):
        require(isinstance(value, str) and PHYSICAL.fullmatch(value), 'Select explicit bounded spare identities')
        require(value.upper() not in ('HUA0BWUT', 'UNKNOWN', 'NULL', '0'), 'Protected or unknown device is refused')


def _snapshot(path, output, limit):
    path = no_symlink_path(Path(path))
    require(path.suffix.lower() not in ('.pem', '.key', '.p12', '.jks', '.keystore'), 'Private-material inputs are not accepted')
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        ota_lan._copy_open(descriptor, path.name, output, limit)
    finally:
        os.close(descriptor)
    return output



def settings_schema(raw, *, limit=1024 * 1024):
    """APK settings schema and stored layers permit finite decimals. Signed
    release/catalog/ledger parsing continues to use the integer-only parser.
    """
    require(limit in (1024 * 1024, 2 * 1024 * 1024) and isinstance(raw, bytes) and len(raw) <= limit,
            'Settings document exceeds its byte bound')
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'Duplicate settings schema member')
            result[key] = value
        return result
    def finite(token):
        value = float(token)
        require(math.isfinite(value), 'Non-finite settings schema number')
        return value
    def invalid(token):
        raise ValueError('Non-finite settings schema number')
    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs,
                      parse_float=finite, parse_constant=invalid)


def _json(path):
    raw = path.read_bytes()
    return release.parse_document(raw)


def _observation(raw):
    """Accept the saved complete instrumentation Bundle or its exact result JSON.
    Never execute instrumentation here: binding the target Application during a
    live trial can change native recovery state before a test phase can inspect it.
    """
    text = raw.decode('utf-8')
    if text.lstrip().startswith('{'):
        result = release.parse_document(raw)
    else:
        rows = [row[len('INSTRUMENTATION_RESULT: result='):] for row in text.splitlines()
                if row.startswith('INSTRUMENTATION_RESULT: result=')]
        require(len(rows) == 1, 'Exactly one saved production-roots result is required')
        result = release.parse_document(rows[0].encode('utf-8'))
    require(isinstance(result, dict) and result.get('phase') == 'production_roots' and result.get('complete') is True,
            'Saved production-roots phase was not complete')
    require(type(result.get('sdk')) is int and result['sdk'] == 27, 'Observation must come from API27')
    checks = result.get('checks')
    require(isinstance(checks, list) and len(checks) == 1 and isinstance(checks[0], dict) and checks[0].get('passed') is True,
            'Saved root observation checks did not pass')
    value = result.get('production_roots')
    require(isinstance(value, dict) and value.get('schema') == 1 and value.get('complete') is True and
            value.get('scope') == 'installed_apk_assets_and_production_key_loaders_only', 'Unexpected root observation')
    require(value.get('count_basis') == 'validated_APK_asset_declarations_after_actual_loaders' and
            value.get('declared_role_counts') == {'channel': 2, 'registry': 2, 'system': 2} and
            value.get('update_keys_load_ok') is True and value.get('system_keys_load_ok') is True and
            value.get('six_roles_independent') is True and value.get('reg_fixture_namespace_present') is False and
            value.get('apk_stable_during_probe') is True and value.get('failures') == [], 'Production root facts are incomplete')
    require(all(value.get(key) is False for key in ('probe_writes_files', 'probe_changes_settings',
            'probe_starts_runtime', 'probe_calls_installer')), 'Observation scope differs')
    return value


class ReadOnlyDevice:
    """No generic adb/shell method: all supported commands are fixed observations."""
    def __init__(self, serial, *, run=subprocess.run):
        self.serial, self.run = serial, run

    def _read(self, *arguments):
        result = self.run(['adb', '-s', self.serial, 'shell', '-T', shlex.join(arguments)],
                          capture_output=True, timeout=30, env={**os.environ, 'ADB_MDNS': '0'})
        require(result.returncode == 0, 'Read-only ADB observation failed; never retry automatically')
        require(len(result.stdout) <= 2 * 1024 * 1024 and len(result.stderr) <= 65536, 'Device observation exceeds bound')
        return result.stdout.decode('utf-8')

    def property(self, name):
        require(name in ('ro.serialno', 'ro.build.version.sdk', 'ro.product.cpu.abi'), 'Unknown read-only property')
        return self._read('getprop', name).strip()

    def installed_sha(self):
        raw = self._read('pm', 'path', APP).strip()
        require(re.fullmatch(r'package:/data/app/[A-Za-z0-9_./=-]{1,300}\.apk', raw) and '..' not in raw,
                'Expected one installed unsplit production APK')
        value = self._read('sha256sum', raw[len('package:'):]).split()
        require(len(value) == 2 and re.fullmatch('[a-f0-9]{64}', value[0]), 'Installed APK hash is unavailable')
        return value[0]

    def owner_and_home(self):
        owner = self._read('dumpsys', 'device_policy')
        components = []
        rows = owner.splitlines()
        for index, row in enumerate(rows):
            if re.fullmatch(r'\s*Device Owner:\s*', row):
                components.extend(re.findall(r'admin=ComponentInfo\{([^}]+)\}', '\n'.join(rows[index:index + 4])))
        expected = {APP + '/.admin.ThinkOSAdminReceiver', APP + '/' + APP + '.admin.ThinkOSAdminReceiver'}
        require(len(components) == 1 and components[0] in expected, 'Known production Device Owner required for this preflight')
        home = self._read('cmd', 'package', 'resolve-activity', '--brief', '-a', 'android.intent.action.MAIN',
                          '-c', 'android.intent.category.HOME')
        rows = [row.strip() for row in home.splitlines() if re.fullmatch(r'[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+', row.strip())]
        require(rows in ([APP + '/.MainActivity'], [APP + '/' + APP + '.MainActivity']), 'Known production HOME required')

    def quiet(self):
        for stream in range(1, 6):
            raw = self._read('media', 'volume', '--stream', str(stream), '--get')
            require(re.findall(r'\bvolume is ([0-9]+)\b', raw) == ['0'], 'Every observed audio stream must be zero')


class PreparedScenario:
    def __init__(self, origin, baseline, target, public_hashes, observation, observed_sha, serial, physical):
        self.origin, self.baseline, self.target = origin, baseline, target
        self.public_hashes, self.observation_sha256 = public_hashes, observed_sha
        self.serial, self.physical = serial, physical
        self.observation = observation
        self.input_hashes = {}
        self.setting_defaults = None
        self.target_setting_defaults = None
        self.catalog_bytes = None
        self.mirror = None

    def report(self):
        facts = {'baseline': self.baseline, 'target': self.target, 'public': self.public_hashes,
                 'observation': self.observation_sha256, 'serial': self.serial, 'physical': self.physical,
                 'input_files': self.input_hashes}
        reviewed = hashlib.sha256(release.json_bytes(facts)).hexdigest()
        return {'input_sha256': reviewed, 'schema': 1, 'stage': 'planned', 'scope': 'signed_inputs_and_read_only_spare_preflight_only',
                'baseline': dict(self.baseline), 'target': dict(self.target), 'public_sha256': dict(self.public_hashes),
                'roots_observation_sha256': self.observation_sha256, 'owner_public_hashes_compared': True,
                'device_observed': False, 'device_changed': False, 'origin_started': False, 'installed': False,
                'ota5_device_acceptance_complete': False, 'pending': list(PENDING)}

    def preflight(self, device):
        _spare(self.serial, self.physical)
        actual = device.property('ro.serialno')
        _spare(self.serial, actual)
        require(actual == self.physical, 'Selected endpoint differs from the explicit physical spare')
        require(device.property('ro.build.version.sdk') == '27' and device.property('ro.product.cpu.abi') == 'armeabi-v7a',
                'Only the API27 armv7 spare is supported')
        require(device.installed_sha() == self.baseline['sha256'], 'Installed APK is not the exact observed signed baseline')
        device.owner_and_home()
        device.quiet()
        # Repeat identity after all observations. Unknown results never authorize
        # a retry, UI action, source write or a subsequent installation.
        require(device.property('ro.serialno') == actual and device.installed_sha() == self.baseline['sha256'],
                'Physical device or installed package changed during preflight')
        result = self.report()
        result.update(stage='preflight_complete', device_observed=True)
        return result


def prepare(args, *, source_root=ROOT, tools=None):
    _spare(args.serial, args.physical_serial)
    with tempfile.TemporaryDirectory(prefix='thinkos-ota5-preflight-snapshots-') as name:
        work = Path(name).resolve()
        ota_lan._snapshot_flat(no_symlink_path(args.public_dir), work / 'public', PUBLIC)
        trust, signer, public_hashes = ota_lan._trusted_public(work / 'public', source_root)
        result_path = _snapshot(args.lan_result, work / 'lan-result.json', MAX_OBSERVATION)
        observed_path = _snapshot(args.roots_observation, work / 'roots-observation.json', MAX_OBSERVATION)
        manifest = _json(result_path)
        require(isinstance(manifest, dict), 'Expected the executed LAN result object')
        mirror = ota_lan.lan_root(manifest['mirror'].rsplit('/', 1)[0])
        address = urlsplit(mirror)
        # No source is started during plan or preflight. The retained origin is
        # available to a later explicit production UI driver, with these same
        # authenticated immutable bytes; HTTP never receives signing controls.
        origin = Ota5Origin(args.lan_dir, manifest, bind=address.hostname, port=address.port or 80)
        raw = origin._files[manifest['channel'] + '.json']
        catalog_path = work / 'catalog.json'; catalog_path.write_bytes(raw)
        release.verify(catalog_path, origin._files[manifest['channel'] + '.json.sig'], list(trust['keys']['channel'].values()))
        catalog = release.validate_catalog(release.parse_document(raw))
        require(catalog['expires'] > int(time.time() * 1000), 'Prepared catalog is expired')
        target = catalog['release']
        require(target['modules'] == [], 'This first OTA-5 preflight accepts APK-only catalogs; module rehearsal is separate')
        baseline_path = _snapshot(args.baseline_apk, work / 'baseline.apk', ota_lan.MAX_APK)
        tools = release.sdk_tools() if tools is None else tools
        ota_lan._archive(baseline_path, arm_libraries=True)
        baseline = release.inspect_apk(baseline_path, signer, args.baseline_version, debuggable=args.baseline_kind == 'owner-debug', tools=tools)
        release.packaged_public_trust(baseline_path, trust)
        with zipfile.ZipFile(baseline_path) as archive:
            require('assets/update/debug-keys.json' not in archive.namelist(), 'Baseline APK contains extra debug trust')
        observed = _observation(observed_path.read_bytes())
        apk = observed.get('apk', {})
        require(isinstance(apk, dict) and apk.get('package') == APP and apk.get('debuggable') is (args.baseline_kind == 'owner-debug') and
                all(apk.get(key) == value for key, value in baseline.items()), 'Root observation APK identity differs from the actual signed baseline')
        assets = observed.get('assets', {})
        require(isinstance(assets, dict), 'Expected fixed asset observations')
        for public in ('keys.json', 'system-keys.json'):
            asset = assets.get(public, {})
            require(isinstance(asset, dict) and asset.get('present') is True and asset.get('sha256') == public_hashes[public] and
                    asset.get('bytes') == len(trust['files'][public]), 'Observed roots differ from owner public exports')
        require(isinstance(assets.get('debug-keys.json'), dict) and assets['debug-keys.json'].get('present') is False, 'Debug roots cannot qualify')
        require(target['code'] > baseline['code'] and target['signer'] == signer, 'Catalog is not a forward same-owner update')
        apk_name = target['urls'][0].rsplit('/', 1)[-1]
        target_path = work / 'target.apk'; target_path.write_bytes(origin._files[apk_name])
        ota_lan._archive(target_path, arm_libraries=True)
        actual = release.inspect_apk(target_path, signer, target['version'], debuggable=False, tools=tools)
        require(all(actual[key] == target[key] for key in actual), 'Signed target tuple differs from the actual verified APK')
        release.packaged_public_trust(target_path, trust)
        target_summary = {key: actual[key] for key in ('version', 'code', 'sha256', 'size', 'signer', 'sdk')}
        target_summary.update(channel=catalog['channel'], seq=catalog['seq'], catalog_sha256=hashlib.sha256(raw).hexdigest())
        prepared = PreparedScenario(origin, baseline, target_summary, public_hashes, observed,
                                hashlib.sha256(observed_path.read_bytes()).hexdigest(), args.serial, args.physical_serial)
        prepared.input_hashes = {'lan_result': hashlib.sha256(result_path.read_bytes()).hexdigest(),
            'origin_files': dict(origin._hashes), 'baseline_kind': args.baseline_kind}
        prepared.catalog_bytes = raw
        prepared.mirror = manifest['mirror']
        for path, attribute in ((baseline_path, 'setting_defaults'), (target_path, 'target_setting_defaults')):
            with zipfile.ZipFile(path) as archive:
                matches = [info for info in archive.infolist() if info.filename == 'assets/settings/system.json']
                if matches:
                    require(len(matches) == 1 and matches[0].file_size <= 1024*1024, 'Actual settings schema is not bounded/unique')
                    schema = settings_schema(archive.read(matches[0]))
                    require(schema.get('scope') == 'system' and isinstance(schema.get('fields'), dict), 'Actual APK settings schema is malformed')
                    setattr(prepared, attribute, {key: value['default'] for key,value in schema['fields'].items() if 'default' in value})
        return prepared


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('--serial', required=True)
    result.add_argument('--physical-serial', required=True)
    result.add_argument('--lan-dir', type=Path, required=True)
    result.add_argument('--lan-result', type=Path, required=True)
    result.add_argument('--public-dir', type=Path, required=True)
    result.add_argument('--baseline-apk', type=Path, required=True)
    result.add_argument('--baseline-version', required=True)
    result.add_argument('--baseline-kind', choices=('release', 'owner-debug'), default='release')
    result.add_argument('--roots-observation', type=Path, required=True)
    modes = result.add_mutually_exclusive_group()
    modes.add_argument('--execute-preflight', action='store_true')
    modes.add_argument('--execute-ui', action='store_true')
    modes.add_argument('--execute-install', action='store_true')
    result.add_argument('--hierarchy-jar', type=Path)
    result.add_argument('--output', type=Path)
    result.add_argument('--authorize-spare')
    result.add_argument('--reviewed-sha256')
    result.add_argument('--plan-install', action='store_true', help='Review the install/real-health plan without ADB')
    return result


def main(argv=None, *, device_factory=ReadOnlyDevice, prepare_scenario=prepare, ui_execute=None):
    args = parser().parse_args(argv)
    require(not(args.execute_preflight and (args.hierarchy_jar is not None or args.plan_install)),
            'Read-only preflight and UI-plan options must be separate invocations')
    prepared = prepare_scenario(args)
    if args.execute_ui or args.execute_install or args.plan_install or args.hierarchy_jar is not None:
        import ota5_ui
        require(args.hierarchy_jar is not None, 'UI plan/execute requires a fixed prebuilt hierarchy jar')
        result = prepared.report()
        result['ui_plan'] = ota5_ui.ui_plan(prepared, args)
        if args.execute_ui or args.execute_install:
            require(args.output is not None, 'Explicit UI execution requires a private output directory')
            result = (ui_execute or ota5_ui.execute)(prepared, args)
    else:
        result = prepared.preflight(device_factory(args.serial)) if args.execute_preflight else prepared.report()
    print(json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(',', ':')))
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (ValueError, OSError, KeyError, TypeError, RuntimeError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print('OTA-5 preflight stopped: ' + type(error).__name__, file=sys.stderr)
        sys.exit(1)
