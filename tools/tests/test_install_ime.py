import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('install_ime', ROOT / 'tools/install_ime.py')
install_ime = importlib.util.module_from_spec(spec)
sys.modules['install_ime'] = install_ime
spec.loader.exec_module(install_ime)

TRIME = """package: name='com.osfans.trime' versionCode='20261101' versionName='3.3.13' platformBuildVersionName='17'
sdkVersion:'21'
targetSdkVersion:'37'
uses-permission: name='android.permission.VIBRATE'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission-sdk-23: name='android.permission.FOREGROUND_SERVICE'
application-label:'Trime'
native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'
"""
F_DROID = '5bc77b5d6d6cb89cbc736d7ddc151fa79f62c45b8aac5413f502b46cd9d0792c'
GOOGLE = 'f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83'


class InstallImeTest(unittest.TestCase):
    def info(self, text=TRIME):
        return install_ime.parse_badging(text)

    def test_badging_gives_identity_sdk_abis_and_permissions(self):
        info = self.info()
        self.assertEqual(info['package'], 'com.osfans.trime')
        self.assertEqual((info['version_code'], info['version_name'], info['min_sdk']), (20261101, '3.3.13', 21))
        self.assertIn('armeabi-v7a', info['abis'])
        self.assertEqual(info['permissions'], ['android.permission.FOREGROUND_SERVICE', 'android.permission.VIBRATE',
                                               'android.permission.WAKE_LOCK'])

    def test_certificate_lines_are_normalised(self):
        colon = ':'.join(F_DROID[i:i + 2].upper() for i in range(0, 64, 2))
        output = 'Verifies\nSigner #1 certificate DN: CN=FDroid\nSigner #1 certificate SHA-256 digest: ' + colon + '\n'
        self.assertEqual(install_ime.parse_certificates(output), {F_DROID})

    def test_the_expected_package_and_signer_pass(self):
        self.assertEqual(install_ime.judge(self.info(), {F_DROID}, True), [])

    def test_a_good_signer_of_a_different_package_is_refused(self):
        wrong = TRIME.replace('com.osfans.trime', 'com.google.android.inputmethod.pinyin')
        self.assertTrue(install_ime.judge(self.info(wrong), {F_DROID}, True))
        self.assertEqual(install_ime.judge(self.info(wrong), {GOOGLE}, True), [])

    def test_an_unknown_signer_or_package_or_broken_signature_is_refused(self):
        self.assertTrue(install_ime.judge(self.info(), {'00' * 32}, True))
        self.assertTrue(install_ime.judge(self.info(), set(), True))
        self.assertTrue(install_ime.judge(self.info(), {F_DROID}, False))
        other = TRIME.replace('com.osfans.trime', 'evil.keylogger')
        self.assertTrue(install_ime.judge(self.info(other), {F_DROID}, True))

    def test_the_device_limits_are_enforced(self):
        high = TRIME.replace("sdkVersion:'21'", "sdkVersion:'28'")
        self.assertTrue(any('minSdk' in p for p in install_ime.judge(self.info(high), {F_DROID}, True)))
        sixty_four = TRIME.replace(" 'armeabi-v7a'", '')
        self.assertTrue(any('native code' in p for p in install_ime.judge(self.info(sixty_four), {F_DROID}, True)))
        pure_java = TRIME.replace("native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'\n", '')
        self.assertEqual(install_ime.judge(self.info(pure_java), {F_DROID}, True), [])

    def test_the_allow_list_has_sixty_four_hex_digit_fingerprints(self):
        for package, (fingerprints, source) in install_ime.TRUSTED.items():
            self.assertTrue(source, package)
            for value in fingerprints:
                self.assertRegex(value, r'^[0-9a-f]{64}$')


if __name__ == '__main__':
    unittest.main()
