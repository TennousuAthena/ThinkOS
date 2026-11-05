#!/usr/bin/env python3
"""Cross-check the production C verifier with OpenSSL using disposable local keys."""
import hashlib
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / 'thinkos-updater'


def command(*args):
    return subprocess.check_output(list(map(str, args)), stderr=subprocess.STDOUT)


class ModuleVerifier(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='thinkos-verifier-')
        cls.directory = Path(cls.temporary.name)
        cls.binary = cls.directory / 'verify'
        command('clang', '-Wall', '-Wextra', '-Werror', '-O1', '-g',
                '-fsanitize=address,undefined', '-fno-omit-frame-pointer', '-o', cls.binary,
                MODULE/'test/verify_driver.c', MODULE/'src/module_verify.c',
                MODULE/'src/micro_ecc.c', MODULE/'vendor/sha-2/sha-256.c')
        cls.keys = []
        cls.public = []
        for i in range(3):
            key = cls.directory / ('ephemeral-'+str(i)+'.pem')
            command('openssl', 'genpkey', '-algorithm', 'EC', '-pkeyopt', 'ec_paramgen_curve:P-256', '-out', key)
            key.chmod(0o600)
            der = command('openssl', 'pkey', '-in', key, '-pubout', '-outform', 'DER')
            assert len(der) == 91 and der[-65] == 4
            cls.keys.append(key); cls.public.append(der[-64:])

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='case-', dir=self.directory)
        self.home = Path(self.temp.name)
        self.package = self.home/'payload'
        self.package.write_bytes(b'authenticated package bytes')
        self.manifest = self.document()

    def tearDown(self):
        self.temp.cleanup()

    def document(self, module='thinkos-cast', kind='magisk', code='2', version='v1.1', size=None):
        return ('thinkos-module 1\nid='+module+'\nkind='+kind+'\ncode='+code+
                '\nversion='+version+'\nsha256='+hashlib.sha256(self.package.read_bytes()).hexdigest()+
                '\nsize='+str(self.package.stat().st_size if size is None else size)+'\n').encode()

    def signed(self, raw, key=0):
        manifest = self.home/'manifest'
        signature = self.home/'signature'
        manifest.write_bytes(raw)
        command('openssl', 'dgst', '-sha256', '-sign', self.keys[key], '-out', signature, manifest)
        return signature.read_bytes()

    def verify(self, raw, signature=None, roots=None, package=False):
        signature = self.signed(raw) if signature is None else signature
        files = [self.home/name for name in ('raw','der','roots')]
        for path, value in zip(files, (raw, signature, b''.join(self.public[:2]) if roots is None else roots)):
            path.write_bytes(value)
        process = subprocess.run([str(self.binary),'verify',*map(str,files),*([str(self.package)] if package else [])], capture_output=True)
        self.assertNotIn(b'ERROR: AddressSanitizer', process.stderr)
        self.assertNotIn(b'runtime error:', process.stderr)
        self.assertIn(process.returncode,(0,1),process.stderr.decode(errors='replace'))
        fields = dict(line.split('=',1) for line in process.stdout.decode().splitlines() if '=' in line)
        result = int(fields['result'])
        if result: self.assertEqual(fields.get('output_zero'),'1')
        return result, process.returncode, fields

    def test_active_backup_and_openssl_agree(self):
        for key in (0,1):
            with self.subTest(key=key):
                signature=self.signed(self.manifest,key)
                self.assertEqual(self.verify(self.manifest,signature,package=True)[:2],(0,0))
                public=self.home/'public.pem'; raw=self.home/'original'; der=self.home/'original.sig'
                raw.write_bytes(self.manifest); der.write_bytes(signature)
                public.write_bytes(command('openssl','pkey','-in',self.keys[key],'-pubout'))
                self.assertIn(b'Verified OK',command('openssl','dgst','-sha256','-verify',public,'-signature',der,raw))

    def test_authentication_precedes_parsing(self):
        signature=self.signed(self.manifest)
        self.assertEqual(self.verify(self.manifest.replace(b'code=2',b'code=3'),signature)[0],-1)
        self.assertEqual(self.verify(self.manifest,self.signed(self.manifest,2))[0],-1)
        unknown=self.document(module='untrusted-module')
        self.assertEqual(self.verify(unknown,self.signed(unknown,2))[0],-1)
        self.assertEqual(self.verify(unknown)[0],-3)

    def test_public_roots_are_strict(self):
        for roots in (b'',b'\0'*64,b'\xff'*64,self.public[0]*2,self.public[0]+b'\0'*64):
            with self.subTest(length=len(roots),digest=hashlib.sha256(roots).hexdigest()):
                self.assertEqual(self.verify(self.manifest,roots=roots)[0],-1)

    def test_der_scalar_and_encoding_boundaries(self):
        valid=self.signed(self.manifest)
        order=bytes.fromhex('ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551')
        def integer(value):return b'\x02'+bytes([len(value)])+value
        def sequence(left,right):
            body=integer(left)+integer(right)
            return b'\x30'+bytes([len(body)])+body
        invalid=[b'',valid[:-1],valid+b'\0',b'\x30\x81'+valid[1:],
                 b'\x31'+valid[1:],b'\x30\x00',sequence(b'\0',b'\1'),sequence(b'\1',b'\0'),
                 sequence(b'\x80',b'\1'),sequence(b'\0\1',b'\1'),sequence(b'\0'+order,b'\1'),
                 sequence(b'\x01'*34,b'\1'),b'\x30\x06\x02\x00\x02\x02\x01\x01']
        for signature in invalid:
            with self.subTest(signature=signature.hex()):self.assertEqual(self.verify(self.manifest,signature)[0],-1)

    def test_signed_malformed_manifests_are_rejected(self):
        lines=self.manifest.splitlines(keepends=True)
        malformed=[self.manifest[:-1],self.manifest+b'\n',b''.join(lines[:3]+lines[4:]),
                   b''.join(lines[:3]+[lines[3]]+lines[3:]),b''.join([lines[1],lines[0]]+lines[2:]),
                   self.manifest.replace(b'\n',b'\r\n'),self.manifest.replace(b'code=2',b'code=\x002'),
                   self.manifest.replace(b'code=2',b'code=\t2'),self.manifest.replace(b'v1.1',b'v\xff')]
        malformed += [self.document(code=value) for value in ('0','-1','+1','02','2e1','2147483648','18446744073709551616')]
        malformed += [self.document(version=value) for value in ('','../escape','two words','v'*49)]
        malformed += [self.document(size=value) for value in ('0','-1','+1','01','1e1','16777217','18446744073709551616')]
        malformed += [self.manifest.replace(b'sha256=',b'sha256=g'), self.document(kind='vector-apk')]
        for raw in malformed:
            with self.subTest(raw=repr(raw)):self.assertNotEqual(self.verify(raw)[0],0)

    def test_all_fixed_id_kind_pairs_and_limits(self):
        ids=('thinkos-updater','thinkos-cast','thinkos-immersive','thinkos-shell','thinkos-certs','thinkos-power','timesync')
        for module in ids:
            kind='vector-apk' if module=='thinkos-immersive' else 'magisk'
            with self.subTest(module=module):
                self.assertEqual(self.verify(self.document(module,kind,code='2147483647',size='16777216'))[0],0)
                wrong='magisk' if kind=='vector-apk' else 'vector-apk'
                self.assertNotEqual(self.verify(self.document(module,wrong))[0],0)

    def test_hash_boundaries_and_offset(self):
        for size in (1,55,56,63,64,65,16383,16384,16385,1024*1024):
            self.package.write_bytes(bytes(range(256))*(size//256)+bytes(range(size%256)))
            result=command(self.binary,'hash',self.package,size).decode()
            self.assertIn('result=0\n',result)
            self.assertIn('sha256='+hashlib.sha256(self.package.read_bytes()).hexdigest(),result)
            openssl=command('openssl','dgst','-sha256',self.package).decode().strip().split()[-1]
            self.assertIn('sha256='+openssl,result)
        for path,size in ((self.package,0),(self.package,self.package.stat().st_size-1),(self.home,1)):
            process=subprocess.run([str(self.binary),'hash',str(path),str(size)],capture_output=True)
            self.assertEqual(process.returncode,1)

    def test_package_bytes_must_match_signed_digest_and_size(self):
        signature=self.signed(self.manifest)
        self.package.write_bytes(b'wrong' + self.package.read_bytes()[5:])
        self.assertEqual(self.verify(self.manifest,signature,package=True)[1],1)
        self.package.write_bytes(b'wrong size')
        self.assertEqual(self.verify(self.manifest,signature,package=True)[1],1)


if __name__=='__main__':unittest.main()
