"""The icons taken from Iconfont: the generated Android resources match assets/icons, and the sources are plain and registered."""
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = json.loads((ROOT / 'assets/icons/glyphs.json').read_text(encoding='utf-8'))


class GlyphTests(unittest.TestCase):
    def test_generated_resources_are_up_to_date(self):
        done = subprocess.run([sys.executable, '-I', str(ROOT / 'tools/gen_glyphs.py'), '--check'], capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)

    def test_every_icon_is_registered_with_its_iconfont_id(self):
        self.assertTrue(MANIFEST['glyphs'])
        for name, entry in MANIFEST['glyphs'].items():
            self.assertIsInstance(entry['iconfont_id'], int, name)
            self.assertTrue((ROOT / 'assets/icons' / entry['file']).is_file(), name)

    def test_sources_are_one_plain_path(self):
        for name, entry in MANIFEST['glyphs'].items():
            svg = (ROOT / 'assets/icons' / entry['file']).read_text(encoding='utf-8')
            self.assertEqual(len(re.findall(r'<path\b', svg)), 1, name)
            self.assertIsNone(re.search(r'<(script|style|image|use|foreignObject|text)\b', svg, re.I), name)
            self.assertIn('viewBox="0 0 1024 1024"', svg, name)

    def _names_in(self, block):
        source = (ROOT / 'android/app/src/main/java/moe/kiwi/thinkos/ui/IconView.kt').read_text(encoding='utf-8')
        start = source.index(f'val {block} =')
        return set(re.findall(r'"([a-z][a-z-]*)"', source[start:source.index(')', start)]))

    def test_every_weather_icon_has_a_vector(self):
        self.assertEqual(self._names_in('weatherNames') - set(MANIFEST['glyphs']), set())

    def test_a_name_is_drawn_in_code_or_comes_from_iconfont_never_both(self):
        self.assertEqual(self._names_in('drawnNames') & set(MANIFEST['glyphs']), set())

    def test_the_licence_is_recorded(self):
        notices = (ROOT / 'THIRD_PARTY_NOTICES.md').read_text(encoding='utf-8')
        self.assertIn('Lucide', notices)
        self.assertIn('ISC License', notices)


if __name__ == '__main__':
    unittest.main()
