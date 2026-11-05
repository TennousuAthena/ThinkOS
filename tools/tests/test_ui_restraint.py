"""ADR-044: reviewable, device-free guardrails for the factory UI, not third-party designs."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SIZES = {14, 18, 32, 72}
# Each exception describes the actual drawing, not a blanket permission for a file.
TEXT_EXCEPTIONS = {
    'ClockView.kt': ('', 'Clock face numerals and labels scale with the clock canvas'),
    'AeroClockView.kt': ('', 'Clock face painted on the Aero canvas'),
    'NowPlaying.kt': (r'side \* \.(?:9|066)f', 'Cover placeholder drawn as an image'),
    'MediaWidgets.kt': (r'(?:s \* 1\.0f|radius \* \.95f|size$)', 'Transport icon glyphs and video subtitles'),
}
SHADOW_EXCEPTIONS = {
    'AeroHomeView.kt': 'Windows 7 desktop (ADR-026)',
    'ImageComponents.kt': 'Readable photo captions and credits',
    'PageView.kt': 'photoNotice branch paints only text over photos',
}
# ADR-044 permits legal notices in full; this is the first-screen license notice.
LONG_TEXT = {'maintenance_diag_body', 'form_place_attribution', 'legal_notice_gpl', 'legal_notice_mit'}
EMOJI = re.compile('[\U0001f000-\U0001faff\u2600-\u27bf]')


def strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from strings(child)


def text_problems(table, chinese=False):
    errors = []
    for key, value in table.items():
        for text in strings(value):
            if re.search('[·•]', text): errors.append(key + ': separator')
            # Checkmarks/music note are control symbols; pictographs are not prose.
            if any(char != '✓' for char in EMOJI.findall(text)): errors.append(key + ': emoji')
            if chinese:
                if re.search('[请您你！]', text) or text.endswith('。'): errors.append(key + ': tone')
                if re.search('此刻|专用设备|无线局域网|发送端|看门狗', text): errors.append(key + ': terminology')
                # A setting warning is a sentence, not the setting's label (UI-03: max 20).
                limit = 12 if key.startswith('setting') and not key.endswith('_warn') else 20
                if key not in LONG_TEXT and len(text) > limit: errors.append(key + ': length')
    return errors


def without_comments(source):
    source = re.sub(r'/\*.*?\*/', '', source, flags=re.S)
    return re.sub(r'(?m)^\s*//.*$', '', source)


def kotlin_problems(source, filename):
    errors = []
    source = without_comments(source)
    if re.search(r'\b(?:ITALIC|MONOSPACE|SERIF|setAllCaps|createFromAsset)\b', source): errors.append('font')
    # A place's elevation is data. Only View elevation assignments and shadow APIs draw shadows.
    if re.search(r'\b(?:setShadowLayer|setElevation|BlurMaskFilter)\s*\(|\belevation\s*=', source) and filename not in SHADOW_EXCEPTIONS:
        errors.append('shadow')
    exception = TEXT_EXCEPTIONS.get(filename)
    for expression in re.findall(r'\btextSize\s*=\s*([^;\n]+)', source):
        if exception and (not exception[0] or re.search(exception[0], expression)): continue
        for number in re.findall(r'(?<![\w.])(\d+(?:\.\d+)?)(?:f)?\b', expression):
            if float(number) not in SIZES: errors.append('size: ' + expression)
    for number in re.findall(r'\b(?:text|label)\((\d+(?:\.\d+)?)f[,)]', source):
        if float(number) not in SIZES: errors.append('helper size: ' + number)
    for number in re.findall(r'\b(?:titleMaxSp|titleMinSp|artistSp)\s*=\s*(\d+(?:\.\d+)?)f', source):
        if float(number) not in SIZES: errors.append('plan size: ' + number)
    if 'setAutoSizeTextTypeUniformWithConfiguration' in source and filename not in ('ClockView.kt', 'AeroClockView.kt'):
        errors.append('continuous autosizing')
    return errors


class UiRestraintTest(unittest.TestCase):
    def test_system_categories_have_distinct_registered_vectors(self):
        sections = json.loads((ROOT / 'shared/schema/settings/system.json').read_text())['sections']
        glyphs = json.loads((ROOT / 'assets/icons/glyphs.json').read_text())['glyphs']
        vocabulary = json.loads((ROOT / 'shared/schema/plugin/layouts.json').read_text())['$defs']['formIcon']['enum']
        expected = {'display': 'monitor', 'mode': 'moon-star', 'location': 'map-pin', 'desktop': 'house', 'media': 'cast',
                    'system': 'sliders-horizontal', 'plugins': 'puzzle'}
        actual = {section['id']: section['icon'] for section in sections}
        for key, icon in expected.items():
            self.assertEqual(icon, actual[key], key)
        self.assertEqual(len(sections), len(set(actual.values())))
        self.assertLessEqual(set(actual.values()), set(glyphs))
        self.assertEqual(set(glyphs), set(vocabulary))

    def test_shadow_guard_accepts_location_data_and_rejects_drawing_apis(self):
        self.assertEqual([], kotlin_problems('val altitude = place.elevation\nval key = "elevation"', 'PlacePicker.kt'))
        for source in ('view.elevation = 8f', 'elevation = 8f', 'setElevation(8f)', 'paint.setShadowLayer(2f, 0f, 0f, 0)', 'BlurMaskFilter(4f, style)'):
            self.assertIn('shadow', kotlin_problems(source, 'PlainView.kt'), source)

    def test_factory_string_tables(self):
        problems = []
        paths = list((ROOT / 'shared/i18n/system').glob('*.json')) + list((ROOT / 'plugins').glob('*/i18n/*.json'))
        for path in paths:
            data = json.loads(path.read_text())
            if path.parent.parent.name == 'welcome' and path.stem == 'zh-Hans':
                # WEL-1 explicitly requires the native greeting. It is a salutation,
                # not explanatory prose addressed to the user; all other copy stays strict.
                self.assertEqual('你好', data['hello'])
                data = {key: value for key, value in data.items() if key != 'hello'}
            problems += [str(path.relative_to(ROOT)) + ': ' + p for p in text_problems(data, path.stem == 'zh-Hans')]
        self.assertEqual([], problems)

    def test_welcome_copy_matches_the_setup_contract(self):
        folder = ROOT / 'plugins/welcome/i18n'
        tables = {path.stem: json.loads(path.read_text()) for path in folder.glob('*.json')}
        self.assertEqual({'zh-Hans', 'zh-Hant', 'en', 'es', 'fr', 'pt', 'ru', 'ar', 'hi', 'bn', 'ur'}, set(tables))
        chinese = tables['zh-Hans']
        for locale, table in tables.items():
            self.assertEqual(set(chinese), set(table), locale)
        self.assertEqual('不收集任何数据', chinese['privacy.body'])
        self.assertEqual('夜间自动安装，可能重启一次', chinese['updates.body'])
        self.assertFalse(any('没有网络也能用' in value or '选好后返回' in value for value in chinese.values()))
        manifest = json.loads((ROOT / 'plugins/welcome/manifest.json').read_text())
        self.assertIn('system.settings', manifest['permissions'])
        for name in ('hello', 'gestures'):
            view = json.loads((ROOT / 'plugins/welcome/views' / (name + '.json')).read_text())
            self.assertEqual('focus', view['layout'])

    def test_factory_scripts_and_views(self):
        problems = []
        for path in (ROOT / 'plugins').rglob('*'):
            if path.suffix not in ('.lua', '.json') or 'i18n' in path.parts: continue
            source = path.read_text()
            if re.search('[·•]', source): problems.append(str(path.relative_to(ROOT)) + ': separator')
            if path.suffix == '.json':
                for text in strings(json.loads(source)):
                    if re.search(r'\|\s*upper\b', text): problems.append(str(path.relative_to(ROOT)) + ': uppercase')
                    if any(char != '✓' for char in EMOJI.findall(text)): problems.append(str(path.relative_to(ROOT)) + ': emoji')
        self.assertEqual([], problems)

    def test_factory_settings_have_no_help_and_only_bool_warnings(self):
        for path in [ROOT / 'shared/schema/settings/system.json', *(ROOT / 'plugins').glob('*/manifest.json')]:
            document = json.loads(path.read_text())
            for key, field in document.get('fields', document.get('settings', {})).items():
                self.assertNotIn('help', field, str(path) + ':' + key)
                if 'warn' in field:
                    self.assertEqual('bool', field['type'], key)
                    text = field['warn']
                    if text.startswith('@'):
                        folder = ROOT / 'shared/i18n/system' if 'fields' in document else path.parent / 'i18n'
                        strings = json.loads((folder / 'zh-Hans.json').read_text())
                        lookup = text[1:].replace('.', '_') if 'fields' in document else text[1:]
                        text = strings[lookup]
                    self.assertLessEqual(len(text), 20, key)

    def test_native_text_and_fonts(self):
        problems = []
        for directory in ('ui', 'shell'):
            for path in (ROOT / 'android/app/src/main/java/moe/kiwi/thinkos' / directory).rglob('*.kt'):
                problems += [str(path.relative_to(ROOT)) + ': ' + p for p in kotlin_problems(path.read_text(), path.name)]
        fonts = [str(path) for base in ('android/app/src/main/res', 'android/app/src/main/assets', 'assets')
                 for path in (ROOT / base).rglob('*') if path.suffix.lower() in ('.ttf', '.otf', '.woff', '.woff2')]
        self.assertEqual([], problems + fonts)

    def test_deliberate_violations_are_detected(self):
        fixture = ROOT / 'tools/tests/fixtures/ui-restraint-invalid'
        errors = text_problems(json.loads((fixture / 'zh-Hans.json').read_text()), chinese=True)
        for kind in ('separator', 'emoji', 'tone', 'terminology', 'length'):
            self.assertTrue(any(kind in error for error in errors), kind)
        errors = kotlin_problems((fixture / 'Bad.kt').read_text(), 'Bad.kt')
        for kind in ('size', 'font', 'shadow', 'continuous autosizing'):
            self.assertTrue(any(kind in error for error in errors), kind)


if __name__ == '__main__':
    unittest.main()
