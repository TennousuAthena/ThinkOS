#!/usr/bin/env python3
"""Generate an Android old-subject-hash CA store from pinned local Mozilla data.

Requires OpenSSL 3+ and Python's standard library. Never downloads, accesses a
device, or changes the host trust store. See docs/41-ca-trust.md.
"""
import argparse
import base64
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from email.utils import parsedate_to_datetime
from functools import lru_cache
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'system/thinkos-certs'
BUNDLE_URL = 'https://curl.se/ca/cacert-{date}.pem'
NSS_URL = 'https://raw.githubusercontent.com/mozilla-firefox/firefox/refs/heads/release/security/nss/lib/ckfw/builtins/certdata.txt'
PEM = re.compile(r'-----BEGIN CERTIFICATE-----\n([A-Za-z0-9+/=\n]+)-----END CERTIFICATE-----')
FILE_NAME = re.compile(r'[0-9a-f]{8}\.(0|[1-9][0-9]*)')


def bounded_read(path, maximum):
    with Path(path).open('rb') as stream:
        raw = stream.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError('Input is too large: ' + str(path))
    return raw


def nss_objects(raw):
    """Read NSS attributes as data, without evaluating its strings or octal."""
    objects, current = [], None
    lines = iter(raw.decode('utf-8').splitlines())
    for line in lines:
        if not line or line.startswith('#') or line == 'BEGINDATA':
            continue
        match = re.fullmatch(r'(CKA_[A-Z0-9_]+) ([A-Z0-9_]+)(?: (.*))?', line)
        if match is None:
            raise ValueError('Malformed NSS attribute')
        name, kind, value = match.groups()
        if name == 'CKA_CLASS':
            current = {}
            objects.append(current)
        if current is None or name in current:
            raise ValueError('Missing NSS object or duplicate attribute')
        if kind == 'MULTILINE_OCTAL':
            octal = []
            for part in lines:
                if part == 'END':
                    break
                if not re.fullmatch(r'(?:\\[0-3][0-7]{2})+', part):
                    raise ValueError('Malformed NSS octal data')
                octal.extend(int(item, 8) for item in part.split('\\')[1:])
            else:
                raise ValueError('Unterminated NSS octal data')
            value = bytes(octal)
        elif kind == 'UTF8':
            value = json.loads(value)
            if not isinstance(value, str):
                raise ValueError('Malformed NSS label')
        elif value is None:
            raise ValueError('Missing NSS attribute value')
        current[name] = value
    return objects


def nss_policy(raw):
    certificates, trusts = {}, {}
    for obj in nss_objects(raw):
        if obj['CKA_CLASS'] == 'CKO_CERTIFICATE':
            der = obj.get('CKA_VALUE')
            if not isinstance(der, bytes):
                raise ValueError('Missing NSS certificate bytes')
            key = hashlib.sha1(der).digest()  # NSS trust-object identifier, not an integrity pin.
            if key in certificates:
                raise ValueError('Duplicate NSS certificate')
            certificates[key] = obj
        elif obj['CKA_CLASS'] == 'CKO_NSS_TRUST':
            key = obj.get('CKA_CERT_SHA1_HASH')
            if not isinstance(key, bytes) or len(key) != 20 or key in trusts:
                raise ValueError('Missing/duplicate NSS trust identity')
            trusts[key] = obj
    policies = {}
    for key, cert in certificates.items():
        trust = trusts.get(key)
        if trust is None:
            raise ValueError('NSS certificate lacks its trust object')
        value = cert.get('CKA_NSS_SERVER_DISTRUST_AFTER', 'CK_FALSE')
        if value != 'CK_FALSE' and not isinstance(value, bytes):
            raise ValueError('Missing/malformed NSS server distrust-after')
        distrust = None
        if isinstance(value, bytes):
            distrust = datetime.strptime(value.decode('ascii'), '%y%m%d%H%M%SZ').replace(tzinfo=timezone.utc).isoformat()
        der = cert['CKA_VALUE']
        policies[hashlib.sha256(der).hexdigest()] = {
            'name': cert['CKA_LABEL'],
            'der_sha256': hashlib.sha256(der).hexdigest(),
            'server_trust': trust.get('CKA_TRUST_SERVER_AUTH'),
            'server_distrust_after': distrust,
        }
    if not policies:
        raise ValueError('Empty NSS certificate data')
    return policies


def tlv(raw, at):
    start = at
    if at + 2 > len(raw):
        raise ValueError('Truncated DER')
    tag, length = raw[at:at + 2]
    at += 2
    if length & 128:
        count = length & 127
        if count == 0 or count > 4 or at + count > len(raw) or raw[at] == 0:
            raise ValueError('Invalid DER length')
        length = int.from_bytes(raw[at:at + count], 'big')
        at += count
        if length < 128:
            raise ValueError('Nonminimal DER length')
    end = at + length
    if end > len(raw):
        raise ValueError('Truncated DER value')
    return tag, at, end, raw[start:end]


def names(der):
    tag, body, end, _ = tlv(der, 0)
    if tag != 0x30 or end != len(der):
        raise ValueError('Invalid certificate DER sequence')
    tag, at, tbs_end, _ = tlv(der, body)
    if tag != 0x30:
        raise ValueError('Invalid certificate body')
    if der[at] == 0xa0:
        at = tlv(der, at)[2]
    fields = []
    for _ in range(5):  # serial, signature, issuer, validity, subject
        _, _, at, raw = tlv(der, at)
        if at > tbs_end:
            raise ValueError('Invalid certificate fields')
        fields.append(raw)
    return fields[2], fields[4]


def pem_bytes(der):
    encoded = base64.b64encode(der).decode('ascii')
    return ('-----BEGIN CERTIFICATE-----\n' + '\n'.join(encoded[i:i + 64] for i in range(0, len(encoded), 64))
            + '\n-----END CERTIFICATE-----\n').encode('ascii')


def openssl(*args, data=None):
    result = subprocess.run(['openssl', *args], input=data, capture_output=True, timeout=15,
                            env=dict(os.environ, LC_ALL='C'))
    if result.returncode:
        raise ValueError('OpenSSL rejected certificate: ' + result.stderr.decode('utf-8', 'replace')[-500:])
    return result.stdout.decode('utf-8')


@lru_cache(maxsize=512)
def inspect_certificate(der, as_of):
    issuer, subject = names(der)
    if issuer != subject:
        raise ValueError('Certificate is not self-issued')
    output = openssl('x509', '-inform', 'DER', '-noout', '-subject_hash_old', '-startdate', '-enddate',
                     '-ext', 'basicConstraints', data=der)
    if not re.search(r'\bCA:TRUE\b', output):
        raise ValueError('Certificate is not a CA')
    values = dict(re.findall(r'^(notBefore|notAfter)=(.+)$', output, re.M))
    before = datetime.strptime(values['notBefore'], '%b %d %H:%M:%S %Y GMT').replace(tzinfo=timezone.utc)
    after = datetime.strptime(values['notAfter'], '%b %d %H:%M:%S %Y GMT').replace(tzinfo=timezone.utc)
    if not before <= as_of < after:
        raise ValueError('Certificate is expired or not yet valid')
    # Do not just compare issuer/subject: actually verify its own signature,
    # including the trust anchor's signature, using no machine trust sources.
    with tempfile.TemporaryDirectory(prefix='thinkos-root-') as directory:
        path = Path(directory) / 'root.crt'
        path.write_bytes(pem_bytes(der))
        openssl('verify', '-check_ss_sig', '-trusted', str(path), '-no-CAfile', '-no-CApath', '-no-CAstore',
                '-attime', str(int(as_of.timestamp())), str(path))
    old_hash = f'{int.from_bytes(hashlib.md5(subject).digest()[:4], "little"):08x}'
    if output.splitlines()[0] != old_hash:
        raise ValueError('Old subject hash disagrees with OpenSSL')
    return old_hash, before.isoformat(), after.isoformat()


def render(bundle, certdata, sha256, retrieved_on, as_of=None):
    as_of = as_of or datetime.now(timezone.utc)
    if not re.fullmatch('[0-9a-f]{64}', sha256) or hashlib.sha256(bundle).hexdigest() != sha256:
        raise ValueError('Bundle does not match its pinned SHA-256')
    text = bundle.decode('utf-8')
    nss_hash = re.findall(r'^## SHA256: ([0-9a-f]{64})$', text, re.M)
    if len(nss_hash) != 1 or hashlib.sha256(certdata).hexdigest() != nss_hash[0]:
        raise ValueError('NSS certdata SHA-256 does not match the curl bundle header')
    source_date = re.findall(r'^## Certificate data from Mozilla as of: (.+)$', text, re.M)
    if len(source_date) != 1:
        raise ValueError('Missing source bundle date')
    converted = parsedate_to_datetime(source_date[0]).date().isoformat()
    date.fromisoformat(retrieved_on)
    policies = nss_policy(certdata)
    entries = re.findall(r'(?m)^([^\n]+)\n={3,}\n(?:#[^\n]*\n)*(-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----)', text)
    if not entries or len(entries) != text.count('-----BEGIN CERTIFICATE-----') or len(entries) > 512:
        raise ValueError('Malformed or empty source certificate bundle')
    selected, seen, seen_names = [], set(), set()
    for name, pem in entries:
        match = PEM.fullmatch(pem)
        if match is None:
            raise ValueError('Malformed source PEM')
        der = base64.b64decode(match[1].replace('\n', ''), validate=True)
        digest = hashlib.sha256(der).hexdigest()
        if digest in seen or name in seen_names:
            raise ValueError('Duplicate source certificate')
        seen.add(digest)
        seen_names.add(name)
        # Validate even a certificate that will be excluded by NSS policy.
        old_hash, before, after = inspect_certificate(der, as_of)
        policy = policies.get(digest)
        if policy is None:
            raise ValueError('Source certificate missing from NSS data')
        if policy['server_trust'] != 'CKT_NSS_TRUSTED_DELEGATOR' or policy['server_distrust_after']:
            continue
        selected.append((old_hash, digest, der, {'name': name, 'der_sha256': digest,
                        'not_before': before, 'not_after': after}))
    files, certificates, next_index = {}, [], defaultdict(int)
    for old_hash, _, der, item in sorted(selected):
        name = f'{old_hash}.{next_index[old_hash]}'
        next_index[old_hash] += 1
        files[name] = pem_bytes(der)
        certificates.append(dict(item, file=name, pem_sha256=hashlib.sha256(files[name]).hexdigest()))
    excluded = []
    for digest, policy in sorted(policies.items()):
        if policy['server_trust'] != 'CKT_NSS_TRUSTED_DELEGATOR' or policy['server_distrust_after']:
            excluded.append(dict(policy, in_bundle=digest in seen))
    manifest = {'schema': 1, 'source_url': BUNDLE_URL.format(date=converted), 'source_sha256': sha256,
                'source_date': converted, 'retrieved_on': retrieved_on, 'license': 'MPL-2.0',
                'certdata_url': NSS_URL, 'certdata_sha256': nss_hash[0],
                'naming': 'MD5(subject DER), first 4 bytes little endian; contiguous collision suffixes',
                'certificate_count': len(certificates), 'certificates': certificates, 'excluded': excluded}
    return files, manifest


def differences(old, new, as_of):
    previous = {item['der_sha256']: item for item in old['certificates']}
    current = {item['der_sha256']: item for item in new['certificates']}
    return {
        'added': [current[key]['name'] for key in sorted(current.keys() - previous.keys())],
        'removed': [previous[key]['name'] for key in sorted(previous.keys() - current.keys())],
        'expiring_within_one_year': [item['name'] for item in new['certificates']
                                   if as_of <= datetime.fromisoformat(item['not_after']) <= as_of + timedelta(days=365)],
        'excluded': [item for item in new['excluded'] if item['in_bundle']],
    }


def installation_layout(certificates, baseline=None):
    """Keep every old root in merge mode, including same-subject renewals.

    NSS alone cannot choose merge filenames: two roots may have identical
    subjects but different DER. Preserve occupied old slots and allocate the
    first free contiguous suffix; verify that the supplied old store has no gaps.
    """
    occupied = dict(baseline or {})
    groups = defaultdict(set)
    for name in occupied:
        if FILE_NAME.fullmatch(name) is None:
            raise ValueError('Invalid baseline CA filename')
        prefix, index = name.split('.')
        groups[prefix].add(int(index))
    if any(indices != set(range(max(indices) + 1)) for indices in groups.values()):
        raise ValueError('Baseline CA suffixes have gaps')
    result = []
    for item in sorted(certificates, key=lambda c: (c['file'].split('.')[0], c['der_sha256'])):
        prefix = item['file'].split('.')[0]
        matches = [name for name, digest in occupied.items() if name.startswith(prefix+'.') and digest == item['der_sha256']]
        if matches:
            target = min(matches, key=lambda name: int(name.split('.')[1]))
        else:
            index = 0
            while f'{prefix}.{index}' in occupied:
                index += 1
            target = f'{prefix}.{index}'
            occupied[target] = item['der_sha256']
        result.append({'source_file': item['file'], 'file': target,
                       'der_sha256': item['der_sha256'], 'pem_sha256': item['pem_sha256']})
    return result


def write_output(module, files, manifest, check=False):
    directory = module / 'system/etc/security/cacerts'
    metadata = module / 'certs-source.json'
    for path in (module, module / 'system', module / 'system/etc', module / 'system/etc/security', directory, metadata):
        if path.is_symlink():
            raise ValueError('Refusing output symlink: ' + str(path))
    existing = list(directory.iterdir()) if directory.exists() else []
    if any(not item.is_file() or item.is_symlink() or FILE_NAME.fullmatch(item.name) is None for item in existing):
        raise ValueError('Unexpected file in generated store')
    raw = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    if check:
        if {path.name for path in existing} != set(files) or metadata.read_bytes() != raw:
            raise ValueError('Committed CA metadata/file set differs')
        if any((directory / name).read_bytes() != value or (directory / name).stat().st_mode & 0o777 != 0o644
               for name, value in files.items()):
            raise ValueError('Committed CA content/permissions differ')
        return
    directory.mkdir(parents=True, exist_ok=True)
    for name, value in files.items():
        path = directory / name
        path.write_bytes(value)
        path.chmod(0o644)
    for path in existing:
        if path.name not in files:
            path.unlink()
    metadata.write_bytes(raw)
    metadata.chmod(0o644)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--certdata', type=Path, help='Defaults to certdata.txt beside the bundle')
    parser.add_argument('--retrieved-on', required=True, help='ISO date; explicit so output is reproducible')
    parser.add_argument('--as-of', type=date.fromisoformat, help='Check validity at noon UTC on this date')
    parser.add_argument('--output', type=Path, default=MODULE, help='Module source directory')
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--diff', type=Path, help='Previous certs-source.json')
    args = parser.parse_args(argv)
    try:
        as_of = (datetime.combine(args.as_of, datetime.min.time(), timezone.utc) + timedelta(hours=12)
                 if args.as_of else datetime.now(timezone.utc))
        files, manifest = render(bounded_read(args.source, 1024 * 1024),
                                 bounded_read(args.certdata or args.source.with_name('certdata.txt'), 4 * 1024 * 1024),
                                 args.sha256, args.retrieved_on, as_of)
        old = json.loads(bounded_read(args.diff, 1024 * 1024)) if args.diff else {'certificates': []}
        diff = differences(old, manifest, as_of)
        write_output(args.output, files, manifest, args.check)
        print(json.dumps({'checked': args.check, 'certificate_count': len(files), **diff}, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        parser.exit(1, str(error) + '\n')


if __name__ == '__main__':
    main()
