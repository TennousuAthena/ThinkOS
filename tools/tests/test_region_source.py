import sys
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'shared/gen'))
import schema_tools as tools


class RegionSourceContractTests(unittest.TestCase):
    def field(self, default='', allow_empty=True):
        value = {'type': 'choice', 'label': 'Region', 'default': default, 'source': 'region'}
        if allow_empty:
            value['allowEmpty'] = True
        return value

    def test_manifest_enum_and_new_source_api_are_registered(self):
        self.assertIn('region', tools.SCHEMAS['manifest.json']['$defs']['setting']['properties']['source']['enum'])
        self.assertEqual(10, tools.SCHEMAS['layouts.json']['x-thinkos-api']['settingSources']['region'])
        tools.validate_settings({'country': self.field()})
        tools.validate_public_contract({'id': 'region-test', 'api': 10, 'settings': {'country': self.field()}})
        with self.assertRaises(tools.ValidationError):
            tools.validate_public_contract({'id': 'region-test', 'api': 9, 'settings': {'country': self.field()}})

    def test_legacy_sources_stay_compatible(self):
        for source in ('timezone', 'place'):
            field = {**self.field(), 'source': source}
            tools.validate_settings({'country': field})
            tools.validate_public_contract({'id': 'region-test', 'api': 1, 'settings': {'country': field}})

    def test_region_default_is_a_string_code_or_explicitly_allowed_empty(self):
        for value in ('DE', 'de', ''):
            tools.validate_settings({'country': self.field(value)})
        for value in (True, 0, 'D', 'DEU', ' D', '../DE'):
            with self.assertRaises(tools.ValidationError):
                tools.validate_settings({'country': self.field(value)})
        with self.assertRaises(tools.ValidationError):
            tools.validate_settings({'country': self.field('', False)})
