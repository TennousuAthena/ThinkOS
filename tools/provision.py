#!/usr/bin/env python3
"""Owner-reviewed local provisioning. Plans never run device commands.

Only --apply-app executes writes: a verified release APK, an explicitly selected
language grant, the battery whitelist and app launch. EDL, resets, Device Owner,
persistent modules and reboots are always manual, even with --apply-app.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import json
import os
from pathlib import Path
import re
import stat
import struct
import subprocess
import xml.etree.ElementTree as ET
import zipfile
from zoneinfo import ZoneInfo

import release as release_tool
from release_tools import (digest, exclusive_write, no_symlink_path,
                           outside_repository, regular, validate_keyring)

EXPECTED_LINEAGE = '15.1-20240531-UNOFFICIAL-starfire'
PROPERTIES = ('ro.lineage.version', 'ro.build.display.id', 'ro.build.fingerprint',
              'ro.product.cpu.abilist', 'ro.build.version.sdk', 'ro.build.version.release')
PACKAGE = 'moe.kiwi.thinkos'
ADMIN = PACKAGE + '/.admin.ThinkOSAdminReceiver'
# Sources and full digests were recorded locally in docs/19 §1. This tool does
# not download, redistribute, or silently replace any of these owner resources.
RESOURCES = {
    'CD-18781Y.200628.084.zip': '4356762c02e6d74ee4bb0ef83c9e4dba910453bb5f14fa3ac9ed321ec4bfa686',
    'lineage-15.1-20240531-UNOFFICIAL-starfire.zip': 'aaeb4e387a3b1395c7567d60b34b78ef6cacab9e90f9576946b69fb49e9d7660',
    'lineage-15.1-20240531-UNOFFICIAL-starfire-recovery.img': 'a1ee1abae40e9370fa97c7b9b50befa66ba0042859f4f89527de8e3cdba6588e',
    'magisk_patched.img': 'f919a8fa3428cc8264f15e54082daf361805923ec0b595a64dd3d923d08f703f',
    'Magisk-v27.0.apk': 'f511bd33d3242911d05b0939f910a3133ef2ba0e0ff1e098128f9f3cd0c16610',
    'timesync-v1.0.zip': 'd9dc4e6fa17371aeffdc5acf3a55ccce19b0d835fc784880687b16912a05d28c',
    'busybox-ndk.zip': 'd6b0baeb4f224e994113d29c2d47e6df2413b6cd65fd8b88c2b7d53e0db26c09',
    'vector.zip': 'b368ffb3424f6984db74e9491e47855dd93c8be2dddf7e1f893a8c7513abb428',
}
REQUIRED_IMAGES = {'boot', 'recovery', 'system', 'userdata'}
OPTIONAL_IMAGES = {'cache', 'aboot', 'vendor'}
SCRUB_CHECKS = {'thinkos_data', 'wifi', 'adb_keys', 'bluetooth', 'temporary_logs', 'test_apps', 'device_owner'}


def local_file(path, limit=None):
    return regular(no_symlink_path(Path(path)), limit)


def checked_file(path, expected, limit=None):
    if not isinstance(expected, str) or not re.fullmatch(r'[a-f0-9]{64}', expected):
        raise ValueError('Expected a full lowercase SHA-256 digest')
    path = local_file(path, limit)
    if digest(path) != expected:
        raise ValueError('SHA-256 mismatch: ' + path.name)
    return path


def read_json(path, limit=1024 * 1024):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON field: ' + key)
            result[key] = value
        return result
    return json.loads(local_file(path, limit).read_bytes(), object_pairs_hook=unique)


def resources(directory):
    directory = outside_repository(Path(directory))
    if not directory.is_dir():
        raise ValueError('Local resource directory is unavailable')
    result = []
    for name, value in RESOURCES.items():
        checked_file(directory / name, value)
        result.append({'file': name, 'sha256': value, 'source_record': 'docs/19-provisioning-for-agent.md §1'})
    return result


def safe_zip(path):
    archive = zipfile.ZipFile(path)
    names = archive.namelist()
    if len(names) > 4096 or len(names) != len(set(names)) or sum(i.file_size for i in archive.infolist()) > 256 * 1024 * 1024:
        archive.close()
        raise ValueError('Oversized or duplicate archive entries')
    try:
        if any(stat.S_ISLNK(i.external_attr >> 16) for i in archive.infolist()):
            raise ValueError('Archive symlinks are refused')
        release_tool.public_archive(archive)
    except Exception:
        archive.close()
        raise
    return archive


def release_inputs(args, verify_signature=False):
    apk = checked_file(args.apk, args.apk_sha256, 64 * 1024 * 1024)
    if not re.fullmatch(r'[a-f0-9]{64}', args.signer_sha256) or args.signer_sha256 == release_tool.DEBUG_CERTIFICATE:
        raise ValueError('A reviewed owner certificate SHA-256 is required')
    with safe_zip(apk) as archive:
        libraries = [name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')]
        if not libraries or any(not name.startswith('lib/armeabi-v7a/') for name in libraries):
            raise ValueError('APK native libraries must be armv7 only')
        if 'assets/update/debug-keys.json' in archive.namelist():
            raise ValueError('Debug trust roots are forbidden in a release APK')
    updater = checked_file(args.updater, args.updater_sha256, 64 * 1024 * 1024)
    with safe_zip(updater) as archive:
        if 'module.prop' not in archive.namelist() or archive.getinfo('module.prop').file_size > 4096:
            raise ValueError('Updater module.prop is absent or oversized')
        properties = dict(line.split('=', 1) for line in archive.read('module.prop').decode('utf-8').splitlines()
                          if '=' in line and not line.startswith('#'))
        if properties.get('id') != 'thinkos-updater' or not re.fullmatch(r'[1-9][0-9]{0,9}', properties.get('versionCode', '')):
            raise ValueError('Unexpected updater identity/versionCode')
        if not re.fullmatch(r'v[0-9]+\.[0-9]+', properties.get('version', '')):
            raise ValueError('Unexpected updater version')
        if 'bin/updaterd' not in archive.namelist():
            raise ValueError('Updater daemon is absent')
        elf = archive.read('bin/updaterd')
        if len(elf) < 20 or elf[:6] != b'\x7fELF\x01\x01' or int.from_bytes(elf[18:20], 'little') != 40:
            raise ValueError('Updater must contain a 32-bit little-endian ARM ELF')
    manifest = local_file(args.updater_manifest, 2048)
    expected = release_tool.module_manifest('thinkos-updater', 'magisk', int(properties['versionCode']),
                                           properties['version'], args.updater_sha256, updater.stat().st_size)
    if manifest.read_bytes() != expected:
        raise ValueError('Updater raw manifest differs from its exact package identity/hash/size')
    signature = local_file(args.updater_signature, 1024).read_bytes()
    if not signature or signature[0] != 0x30:
        raise ValueError('Updater signature must be ASN.1 DER ECDSA')
    system = read_json(args.system_keyring, 64 * 1024)
    if not isinstance(system, dict) or set(system) != {'schema', 'active', 'backup'} or type(system['schema']) is not int or system['schema'] != 1:
        raise ValueError('System keyring must be flat schema/active/backup')
    keys = validate_keyring({'schema': 1, 'system': {slot: system[slot] for slot in ('active', 'backup')}}, ('system',))
    if verify_signature:
        release_tool.verify(manifest, signature, list(keys['system'].values()))
    info = {'apk': {'path': str(apk), 'sha256': args.apk_sha256, 'signer': args.signer_sha256,
                    'metadata': 'pending aapt/apksigner verification; no version is assumed'},
            'updater': {'path': str(updater), 'sha256': args.updater_sha256,
                        'version': properties['version'], 'code': int(properties['versionCode']),
                        'signature': 'verified' if verify_signature else 'pending host OpenSSL verification'}}
    if verify_signature:
        info['apk'].update(release_tool.inspect_apk(apk, args.signer_sha256))
        info['apk']['metadata'] = 'verified from the actual signed APK'
    return info


def step(name, command=None, *, manual=False, note=None):
    value = {'step': name, 'manual': manual}
    if command is not None:
        value['command'] = [str(item) for item in command]
    if note:
        value['note'] = note
    return value


def app_steps(serial, args):
    adb = ['adb', '-s', serial]
    result = [step('verify-system', command) for command in commands(serial)]
    result.append(step('install-release-apk', adb + ['install', '-r', str(args.apk)]))
    if args.language_permission == 'grant':
        result.append(step('grant-language-permission', adb + ['shell', 'pm', 'grant', PACKAGE,
                                                              'android.permission.CHANGE_CONFIGURATION']))
    else:
        result.append(step('skip-language-permission', note='Android language settings remain available; no grant is claimed.'))
    result += [step('battery-whitelist', adb + ['shell', 'dumpsys', 'deviceidle', 'whitelist', '+' + PACKAGE]),
               step('start-thinkos', adb + ['shell', 'am', 'start', '-n', PACKAGE + '/.MainActivity'])]
    return result


def installation_plan(args, serials):
    inputs = release_inputs(args)
    stages = [step('host-release-preflight', manual=False,
                   note='Before any write: aapt/apksigner check actual version, package, API27, non-debuggable owner signer; OpenSSL verifies the raw updater manifest.')]
    if args.operation == 'install':
        pins = resources(args.resources)
        material = args.resources
        stages += [step('verify-external-resources', note=json.dumps(pins)),
                   step('manual-firmware-baseline', manual=True,
                        note='Follow docs/19 §§2–4 only after separate owner authorization. Factory-stock and golden-image paths differ; never automatically run stock qfil.'),
                   step('manual-stock-recovery-if-authorized', ['edl', '--loader=' + str(material / 'image/prog_emmc_firehose_8953_ddr.mbn'),
                        'qfil', material / 'image/rawprogram_unsparse.xml', material / 'image/patch0.xml', material / 'image'], manual=True,
                        note='Destructive stock-recovery path only, not golden deployment. Extract only from the verified stock ZIP and independently verify the loader/XML first.'),
                   step('manual-recovery-lineage-magisk', manual=True,
                        note='Use only the pinned Lineage/recovery/Magisk27 patched boot. Recovery wipe/sideload, partition writes and reboot are manual.'),
                   step('manual-aboot-for-recorded-stock-path', ['edl', 'w', 'aboot', material / 'image/emmc_appsboot.mbn'], manual=True),
                   step('manual-recovery-write', ['edl', 'w', 'recovery', material / 'lineage-15.1-20240531-UNOFFICIAL-starfire-recovery.img'], manual=True),
                   step('manual-enter-recovery-and-wipe', manual=True, note='Owner enters recovery and formats data only after separate authorization; entry method must be confirmed on that device.'),
                   step('manual-lineage-sideload', ['adb', '-s', serials[0], 'sideload', material / 'lineage-15.1-20240531-UNOFFICIAL-starfire.zip'], manual=True,
                        note='Manual firmware work is one device at a time; repeat the reviewed baseline per device, then use batch for app deployment.'),
                   step('manual-pinned-magisk-boot-write', ['edl', 'w', 'boot', material / 'magisk_patched.img'], manual=True),
                   step('manual-reset-after-boot-write', ['edl', 'reset'], manual=True),
                   step('manual-magisk-manager-install', ['adb', '-s', serials[0], 'install', '-r', material / 'Magisk-v27.0.apk'], manual=True),
                   step('manual-framework-and-system-modules', manual=True,
                        note='Review Magisk, Zygisk, Vector, cast and immersive modules; install using the documented owner workflow, then manually reboot. No app-side su.')]
    for serial in serials:
        stages.extend(app_steps(serial, args))
        adb = ['adb', '-s', serial]
        stages += [step('updater-copy-for-owner', adb + ['push', str(args.updater), '/sdcard/Download/thinkos-updater-owner.zip'], manual=True),
                   step('updater-owner-install', adb + ['shell', 'su', '-c', 'magisk --install-module /sdcard/Download/thinkos-updater-owner.zip'], manual=True),
                   step('updater-owner-reboot', manual=True, note='Owner reboots, waits for adb, checks updater STATUS/version/compiled public roots; no reboot is executed here.')]
        if args.device_owner == 'enable':
            stages.append(step('enable-device-owner', adb + ['shell', 'dpm', 'set-device-owner', ADMIN], manual=True,
                               note='Explicit owner choice. Confirm no previous owner/accounts, data implications and tested release surrender path before running.'))
        else:
            stages.append(step('device-owner-' + args.device_owner, manual=True,
                               note='Inspect existing policy; this choice never clears an existing Device Owner. Preserve or skip is not a claim that management is enabled.'))
        stages += [step('offline-onboarding-and-system-acceptance', manual=True,
                        note='Disconnect Server/network, verify first-run onboarding, global Back/Home/panel, volume 0, installed module health and recovery/uninstall route; record evidence, do not set onboarding.done.'),
                   step('gpl-installation-information', manual=True,
                        note='Deliver GPLv3/FFmpeg notices + exact corresponding source/build instructions and docs/installation-guide.md; owner verifies release uninstall and self-built install.')]
    return {'schema': 1, 'dry_run': not args.apply_app, 'operation': args.operation, 'inputs': inputs,
            'language_permission': args.language_permission, 'device_owner': args.device_owner,
            'serials': serials, 'steps': stages, 'complete': False,
            'qualification': 'Plan only; firmware, management, modules, reboot and physical acceptance remain manual.'}


def checks(properties):
    abis = properties.get('ro.product.cpu.abilist', '').split(',')
    return {
        'lineage_version': properties.get('ro.lineage.version') == EXPECTED_LINEAGE,
        'api_27': properties.get('ro.build.version.sdk') == '27',
        'armv7_only': 'armeabi-v7a' in abis and set(abis) <= {'armeabi-v7a', 'armeabi'} and len(abis) == len(set(abis)),
    }


def commands(serial):
    return [['adb', '-s', serial, 'shell', 'getprop', key] for key in PROPERTIES]


def verify(serial):
    properties = {}
    try:
        for key, command in zip(PROPERTIES, commands(serial)):
            reply = subprocess.run(command, capture_output=True, text=True, timeout=15,
                                   env=dict(os.environ, ADB_MDNS='0'), check=True)
            if len(reply.stdout) > 4096:
                raise ValueError('Oversized property: ' + key)
            properties[key] = reply.stdout.strip()
        result = checks(properties)
        return {'serial': serial, 'ok': all(result.values()), 'checks': result, 'properties': properties}
    except (OSError, subprocess.SubprocessError, ValueError) as error:
        return {'serial': serial, 'ok': False, 'properties': properties,
                'error': str(error)[:1600]}


def apply_apps(args, serials):
    # All host checks finish before even the first adb property read. Never
    # uninstall on signature mismatch or silently downgrade/reset user data.
    inputs = release_inputs(args, verify_signature=True)
    def install(serial):
        report = verify(serial)
        report['applied'] = []
        if not report['ok']:
            return report
        try:
            for item in app_steps(serial, args):
                if item['step'].startswith('verify-') or 'command' not in item:
                    continue
                reply = subprocess.run(item['command'], capture_output=True, text=True, timeout=120,
                                       env=dict(os.environ, ADB_MDNS='0'), check=True)
                if 'Failure [' in reply.stdout or (item['step'] == 'install-release-apk' and 'Success' not in reply.stdout):
                    raise ValueError('Device did not confirm ' + item['step'])
                report['applied'].append(item['step'])
        except (OSError, subprocess.SubprocessError, ValueError) as error:
            report.update(ok=False, error=str(error)[:1600])
        return report
    with ThreadPoolExecutor(max_workers=min(4, len(serials))) as pool:
        devices = list(pool.map(install, serials))
    return {'schema': 1, 'operation': args.operation, 'dry_run': False, 'app_phase_only': True,
            'inputs': inputs, 'devices': devices, 'ok': all(item['ok'] for item in devices), 'complete': False,
            'manual_pending': ['firmware/reference image qualification', 'Device Owner inspection/choice',
                               'updater/module installation and reboot', 'offline/device acceptance and GPL install route']}


def gpt(path):
    value = read_json(path)
    if set(value) != {'schema', 'sector_size', 'disk_sectors', 'partitions'} or value['schema'] != 1 or value['sector_size'] != 512:
        raise ValueError('Expected schema1, 512-byte normalized GPT record')
    if type(value['disk_sectors']) is not int or not 0 < value['disk_sectors'] < 2**40:
        raise ValueError('Invalid GPT disk size')
    entries = value['partitions']
    if not isinstance(entries, list) or not 4 <= len(entries) <= 256:
        raise ValueError('Invalid GPT partition count')
    result = {}
    for item in entries:
        if not isinstance(item, dict) or set(item) != {'name', 'physical_partition', 'start_sector', 'sectors'}:
            raise ValueError('Invalid GPT partition fields')
        name = item['name']
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]{0,63}', name) or name in result:
            raise ValueError('Invalid or duplicate GPT partition name')
        if any(type(item[key]) is not int for key in ('physical_partition', 'start_sector', 'sectors')):
            raise ValueError('GPT geometry must use integers')
        if item['physical_partition'] != 0 or item['start_sector'] < 34 or item['sectors'] <= 0 or item['start_sector'] + item['sectors'] > value['disk_sectors'] - 33:
            raise ValueError('Unsupported or out-of-bounds GPT geometry')
        result[name] = dict(item, sector_size=512)
    ordered = sorted(result.values(), key=lambda item: item['start_sector'])
    if any(a['start_sector'] + a['sectors'] > b['start_sector'] for a, b in zip(ordered, ordered[1:])):
        raise ValueError('Overlapping GPT partitions')
    if not REQUIRED_IMAGES <= result.keys():
        raise ValueError('GPT lacks required reference partitions')
    return {'disk_sectors': value['disk_sectors'], 'partitions': result}


def expanded_size(path, image_format):
    """Validate sparse structure without expanding it or trusting its header."""
    if image_format == 'raw':
        with path.open('rb') as stream:
            if stream.read(4) == b'\x3a\xff\x26\xed':
                raise ValueError('Sparse image was mislabeled raw')
        return path.stat().st_size
    if image_format != 'android-sparse':
        raise ValueError('Only raw or Android sparse images are supported')
    size = path.stat().st_size
    with path.open('rb') as stream:
        header = stream.read(28)
        if len(header) != 28:
            raise ValueError('Truncated sparse header')
        magic, major, minor, file_header, chunk_header, block, blocks, chunks, _ = struct.unpack('<I4H4I', header)
        if magic != 0xed26ff3a or major != 1 or minor != 0 or file_header != 28 or chunk_header != 12 or block < 512 or block % 512 or not 0 < blocks * block <= 64 * 1024**3 or not 0 < chunks <= 1000000:
            raise ValueError('Unsupported sparse header')
        used = 0
        for _ in range(chunks):
            raw = stream.read(12)
            if len(raw) != 12:
                raise ValueError('Truncated sparse chunk')
            kind, reserved, count, total = struct.unpack('<2H2I', raw)
            payload = {0xcac1: count * block, 0xcac2: 4, 0xcac3: 0, 0xcac4: 4}.get(kind)
            if reserved or payload is None or total != 12 + payload or (kind == 0xcac4 and count != 0) or (kind != 0xcac4 and count <= 0):
                raise ValueError('Malformed sparse chunk')
            used += count
            if used > blocks or stream.tell() + payload > size:
                raise ValueError('Sparse chunk exceeds image bounds')
            stream.seek(payload, 1)
        if used != blocks or stream.tell() != size:
            raise ValueError('Sparse image block count or trailing bytes mismatch')
    return blocks * block


def golden_plan(args):
    directory = outside_repository(Path(args.images))
    if not directory.is_dir():
        raise ValueError('Golden image directory is unavailable')
    loader = checked_file(args.loader, args.loader_sha256, 4 * 1024 * 1024)
    reference = gpt(args.reference_gpt)
    if reference != gpt(args.target_gpt):
        raise ValueError('Target GPT differs from reference; no flash plan is produced')
    manifest = read_json(args.manifest)
    if manifest.get('schema') != 1 or manifest.get('lineage') != EXPECTED_LINEAGE or manifest.get('reference_gpt_sha256') != digest(local_file(args.reference_gpt)):
        raise ValueError('Golden manifest version/reference GPT mismatch')
    if manifest.get('device_owner') not in ('none', 'preserved') or manifest['device_owner'] != args.device_owner:
        raise ValueError('Explicit Device Owner choice must match the captured image')
    if manifest.get('scrubbed') != {key: True for key in SCRUB_CHECKS}:
        raise ValueError('All manual privacy/identity cleanup attestations are required')
    images = manifest.get('images')
    if not isinstance(images, dict) or not REQUIRED_IMAGES <= images.keys() or set(images) - REQUIRED_IMAGES - OPTIONAL_IMAGES:
        raise ValueError('Only boot/recovery/system/userdata/cache and explicitly reviewed aboot/vendor are allowed')
    if 'aboot' in images and (not args.include_aboot or manifest.get('aboot_required_verified') is not True):
        raise ValueError('aboot requires explicit inclusion and recorded owner verification')
    if 'vendor' in images and (not args.include_vendor or not re.fullmatch(r'[a-f0-9]{64}', manifest.get('vendor_stock_sha256', '')) or manifest['vendor_stock_sha256'] == images['vendor'].get('sha256')):
        raise ValueError('vendor requires explicit inclusion and a recorded differing stock digest')
    root = ET.Element('data')
    verified = []
    for name in sorted(images):
        entry = images[name]
        if not isinstance(entry, dict) or set(entry) != {'file', 'sha256', 'size', 'format'}:
            raise ValueError('Invalid image manifest entry')
        filename = entry['file']
        if not isinstance(filename, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,127}', filename):
            raise ValueError('Images must be direct local basenames')
        image = checked_file(directory / filename, entry['sha256'], 64 * 1024**3)
        if type(entry['size']) is not int or image.stat().st_size != entry['size']:
            raise ValueError('Image size mismatch')
        geometry = reference['partitions'].get(name)
        if geometry is None or expanded_size(image, entry['format']) != geometry['sectors'] * geometry['sector_size']:
            raise ValueError('Image expanded size must exactly match the captured GPT partition: ' + name)
        ET.SubElement(root, 'program', {'SECTOR_SIZE_IN_BYTES': str(geometry['sector_size']),
            'file_sector_offset': '0', 'filename': filename, 'label': name,
            'num_partition_sectors': str(geometry['sectors']), 'physical_partition_number': str(geometry['physical_partition']),
            'start_sector': str(geometry['start_sector']), 'sparse': str(entry['format'] == 'android-sparse').lower()})
        verified.append(dict(entry, partition=name))
    xml = ET.tostring(root, encoding='unicode') + '\n'
    output = outside_repository(Path(args.output_xml))
    if output.parent != directory:
        raise ValueError('Review XML must be directly inside the external image directory')
    if args.write_plan:
        exclusive_write(output, xml.encode('utf-8'))
    return {'schema': 1, 'dry_run': not args.write_plan, 'operation': 'golden-plan', 'complete': False,
            'gpt_match': True, 'images': verified, 'device_owner': args.device_owner,
            'privacy': 'Owner attestations only; credentials/calibration/unique identities require physical acceptance.',
            'rawprogram': xml, 'xml_written': args.write_plan,
            'steps': [step('manual-edl-port-and-gpt-confirmation', manual=True,
                          note='One device only. Owner compares original printgpt logs and normalized geometry; this tool does not talk to EDL.'),
                      step('manual-qfil-review', ['edl', '--loader=' + str(loader), 'qfil', output, '<owner-reviewed-no-partition-patch.xml>', directory], manual=True,
                           note='Sparse write and wl --skip are unverified. Never pass stock patch0.xml or full stock rawprogram; verify local edl syntax before owner execution.'),
                      step('manual-reboot-and-two-device-acceptance', manual=True,
                           note='Owner resets, verifies module/Vector health, onboarding, distinct cast/Wi-Fi/Bluetooth identity and calibration, then records actual timing.')],
            'qualification': 'No capture/flash/reboot was executed; XML creation alone is not an accepted golden image.'}


def capture_plan(args):
    directory = outside_repository(Path(args.images))
    loader = checked_file(args.loader, args.loader_sha256, 4 * 1024 * 1024)
    prefix = ['edl', '--loader=' + str(loader)]
    return {'schema': 1, 'operation': 'capture-plan', 'dry_run': True, 'complete': False,
            'images_directory': str(directory), 'device_owner': args.device_owner,
            'steps': [step('manual-reference-device-completion', manual=True,
                          note='Qualify ROM/Magisk/Zygisk/Vector/cast/immersive/updater; remove test apps. Inspect ownership before data reset.'),
                      step('manual-device-owner-choice', manual=True,
                           note='none: owner removes management before capture; preserved: owner explicitly accepts policy cloning. This tool never sets/clears management.'),
                      step('manual-app-data-reset', ['adb', '-s', args.serial, 'shell', 'pm', 'clear', PACKAGE], manual=True,
                           note='Owner only, after backup. Confirm no settings/local.json or files/cast identity/pairing remains. Do not launch ThinkOS again before capture.'),
                      step('manual-privacy-cleanup', manual=True,
                           note='Confirm API27 paths before deleting Wi-Fi/adb keys/Bluetooth/tmp/log data. No guessed recursive rm commands are provided. See docs/golden-image-guide.md.'),
                      step('manual-enter-edl-and-record-gpt', prefix + ['printgpt'], manual=True,
                           note='Save full original output outside the repository and transcribe/independently review the normalized geometry; do not infer geometry from split stock images.')]
                      + [step('manual-capture-' + name, prefix + ['r', name, directory / (name + '.img')], manual=True)
                         for name in sorted(REQUIRED_IMAGES)]
                      + [step('manual-sparse-and-manifest', manual=True,
                              note='Keep original raw images; owner may convert userdata with local img2simg, compare expanded digest/size, hash every artifact, and record privacy attestations. Sparse device writes remain unverified.')],
            'qualification': 'Read commands are review material only; no device was contacted or data reset.'}


def add_serials(parser, required=False):
    parser.add_argument('--serial', action='append', required=required,
                        help='ADB serial; repeat for a batch (verify defaults to ANDROID_SERIAL or HUA0BWUT)')


def add_release(parser):
    add_serials(parser, required=True)
    for name in ('apk', 'updater', 'updater-manifest', 'updater-signature', 'system-keyring'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('apk-sha256', 'signer-sha256', 'updater-sha256'):
        parser.add_argument('--' + name, required=True)
    parser.add_argument('--language-permission', choices=('grant', 'skip'), required=True)
    parser.add_argument('--device-owner', choices=('enable', 'skip', 'preserve'), required=True)
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument('--dry-run', action='store_true', help='Default: only read local resources and print the full plan; no subprocess/file write')
    actions.add_argument('--apply-app', action='store_true', help='Explicit app phase only; never flash, reset, install modules, set owner or reboot')
    actions.add_argument('--check-host', action='store_true', help='Run only local aapt/apksigner/OpenSSL preflight and print actual metadata; never contact a device')
    parser.add_argument('--output', type=Path, help='Host JSON report for --apply-app only; ignored in dry-run')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='operation', required=True)
    read = subparsers.add_parser('verify', help='Read fixed properties; repeat --serial to verify a batch')
    add_serials(read)
    read.add_argument('--output', type=Path, help='Write a host-side JSON report')
    read.add_argument('--dry-run', action='store_true', help='Print commands; never invoke adb or write an output file')
    source = subparsers.add_parser('resources', help='Verify local pinned resources; never download or contact a device')
    source.add_argument('--directory', required=True, type=Path)
    source.add_argument('--dry-run', action='store_true', help='Local read/hash only, as in normal resources mode')
    fresh = subparsers.add_parser('install', help='Full manual-baseline/release installation plan; default dry-run')
    add_release(fresh)
    fresh.add_argument('--resources', required=True, type=Path)
    batch = subparsers.add_parser('batch', help='After manual ROM/image setup, plan or explicitly apply one release to several serials')
    add_release(batch)
    capture = subparsers.add_parser('capture-plan', help='Print manual cleanup/capture steps; no device execution is supported')
    capture.add_argument('--images', required=True, type=Path)
    capture.add_argument('--loader', required=True, type=Path)
    capture.add_argument('--loader-sha256', required=True)
    capture.add_argument('--serial', required=True)
    capture.add_argument('--device-owner', choices=('none', 'preserved'), required=True)
    capture.add_argument('--dry-run', action='store_true')
    golden = subparsers.add_parser('golden-plan', help='Validate images/GPT and render a restricted review XML; never execute EDL')
    for name in ('images', 'manifest', 'reference-gpt', 'target-gpt', 'output-xml'):
        golden.add_argument('--' + name, required=True, type=Path)
    golden.add_argument('--device-owner', choices=('none', 'preserved'), required=True)
    golden.add_argument('--loader', required=True, type=Path)
    golden.add_argument('--loader-sha256', required=True)
    golden.add_argument('--include-aboot', action='store_true')
    golden.add_argument('--include-vendor', action='store_true')
    mode = golden.add_mutually_exclusive_group()
    mode.add_argument('--dry-run', action='store_true', help='Default: no XML or other file is written')
    mode.add_argument('--write-plan', action='store_true', help='Create a new local review XML only, refusing overwrite; never flash')
    args = parser.parse_args(argv)
    if args.operation in ('resources', 'capture-plan', 'golden-plan'):
        try:
            if args.operation == 'resources':
                report = {'schema': 1, 'local_read_only': True, 'resources': resources(args.directory), 'ok': True}
            elif args.operation == 'capture-plan':
                if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,127}', args.serial):
                    raise ValueError('Invalid ADB serial')
                report = capture_plan(args)
            else:
                report = golden_plan(args)
            print(json.dumps(report, ensure_ascii=False, indent=2))
            return 0
        except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile) as error:
            parser.error(str(error))
    serials = list(dict.fromkeys(args.serial or [os.environ.get('ANDROID_SERIAL', 'HUA0BWUT')]))
    if any(not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,127}', serial) for serial in serials):
        parser.error('Invalid ADB serial')
    if args.operation in ('install', 'batch'):
        try:
            plan = installation_plan(args, serials)
            if args.check_host:
                plan['inputs'] = release_inputs(args, verify_signature=True)
                plan.update(dry_run=False, host_only=True)
                print(json.dumps(plan, ensure_ascii=False, indent=2))
                return 0
            if not args.apply_app:
                print(json.dumps(plan, ensure_ascii=False, indent=2))
                return 0
            if args.output and (no_symlink_path(args.output).exists() or not args.output.parent.is_dir()):
                raise ValueError('App-phase report must be a new file in an existing host directory')
            report = apply_apps(args, serials)
            text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
            if args.output:
                exclusive_write(no_symlink_path(args.output), text.encode('utf-8'))
            print(text, end='')
            return 0 if report['ok'] else 1
        except (OSError, ValueError, RuntimeError, KeyError, TypeError, zipfile.BadZipFile) as error:
            parser.error(str(error))
    if args.dry_run:
        print(json.dumps({'dry_run': True, 'commands': [command for serial in serials for command in commands(serial)]}, indent=2))
        return 0
    with ThreadPoolExecutor(max_workers=min(4, len(serials))) as pool:
        devices = list(pool.map(verify, serials))
    report = {'read_only': True, 'checked_at': datetime.now(ZoneInfo('Asia/Hong_Kong')).isoformat(),
              'expected_lineage': EXPECTED_LINEAGE, 'devices': devices, 'ok': all(item['ok'] for item in devices)}
    text = json.dumps(report, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text)
    print(text, end='')
    return 0 if report['ok'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
