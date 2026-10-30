import copy
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import unittest
import hashlib

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('package_plugins', ROOT / 'tools/package_plugins.py')
packages = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packages)
import sys
sys.path.insert(0, str(ROOT / 'shared' / 'gen'))
import schema_tools as tools

class PluginPackageTests(unittest.TestCase):
    def test_api_three_gallery_exposes_bing_and_local_through_shared_interfaces(self):
        manifest, _ = packages.validate_plugin(ROOT / 'plugins/gallery')
        self.assertEqual(4, manifest['api'])
        self.assertEqual(['library', 'network'], manifest['permissions'])
        self.assertEqual('bing', manifest['settings']['source']['default'])
        self.assertEqual({'bing', 'local', 'all'}, {x['value'] for x in manifest['settings']['source']['choices']})
        self.assertEqual('https://www.bing.com/HPImageArchive.aspx', manifest['settings']['endpoint']['default'])
        self.assertEqual(12, manifest['settings']['pan_seconds']['default'])
        self.assertEqual('random', manifest['settings']['pan_direction']['default'])
        tools.validate_view({'layout': 'stage', 'slots': {'stage': {
            'type': 'image', 'src': 'https://www.bing.com/photo.jpg', 'pan_seconds': 12, 'pan_direction': 'random'
        }}}, 3)
        for name in ('slideshow', 'preview'):
            view = json.loads((ROOT / 'plugins/gallery/views' / (name+'.json')).read_text())
            tools.validate_view(view, 3)
            with self.assertRaises(tools.ValidationError): tools.validate_view(view, 2)
        for component in [
            {'type': 'image', 'src': '{{cover}}', 'fit': 'cover'},
            {'type': 'carousel', 'items': 'photos', 'interval': 30, 'duration': 800, 'tap': 'private'},
            {'type': 'image', 'src': '{{cover}}', 'pan_seconds': 0},
            {'type': 'image', 'src': '{{cover}}', 'pan_direction': 'arbitrary'},
            {'type': 'video'}, {'type': 'controls'},
        ]:
            with self.assertRaises(tools.ValidationError):
                tools.validate_view({'layout': 'stage', 'slots': {'stage': component}}, 3)

    def test_native_component_fixture_uses_api_two_without_location_permission(self):
        manifest, _ = packages.validate_plugin(ROOT / 'tools/tests/fixtures/component-probe')
        self.assertEqual(2, manifest['api'])
        self.assertEqual([], manifest['permissions'])

    def test_api_two_vocabulary_is_gated_by_manifest_api(self):
        declarations = [
            {'layout': 'focus', 'slots': {'hero': {'type': 'progress', 'value': '{{progress}}', 'max': 100}}},
            {'layout': 'focus', 'slots': {'hero': {'children': [{'type': 'chart', 'values': [1, None, 4]}]}}},
            {'layout': 'stage', 'slots': {'stage': {'type': 'daylight', 'path': 'sun'}}},
            {'layout': 'split', 'slots': {'lead': {'type': 'moon', 'path': 'moon'}, 'main': {'type': 'list', 'items': '{{rows}}', 'item': {'title': '{{.title}}'}}}},
            {'template': 'glance', 'children': [{'type': 'text', 'value': 'Tap', 'tap': 'open', 'weight': 1}]},
        ]
        for view in declarations:
            tools.validate_view(view, 2)
            with self.assertRaises(tools.ValidationError):
                tools.validate_view(view, 1)
        tools.validate_view({'layout': 'focus', 'slots': {'hero': {'type': 'clock'}}}, 1)

    def test_api_two_rejects_styles_coordinates_and_invalid_weights(self):
        for component in [
            {'type': 'progress', 'value': 1, 'color': 'red'},
            {'type': 'progress', 'value': 1, 'weight': -1},
            {'type': 'progress', 'value': 1, 'weight': float('nan')},
            {'type': 'chart', 'values': [1], 'style': 'gradient'},
            {'type': 'daylight', 'path': 'sun', 'latitude': 52},
            {'type': 'moon', 'path': 'moon', 'hemisphere': 'east'},
        ]:
            with self.assertRaises(tools.ValidationError):
                tools.validate_view({'layout': 'focus', 'slots': {'hero': component}}, 2)
    def test_tile_packer_uses_first_fit_in_both_orientations(self):
        self.assertEqual({1}, packages.tile_drops(["large", "wide"], 6, 4))
        self.assertEqual(set(), packages.tile_drops(["large", "wide"], 4, 6))
        self.assertEqual({1, 2}, packages.tile_drops(["large"]*3, 6, 4))

    def test_api_four_tiles_contract(self):
        tile = {'template': 'tile', 'faces': [{'label': 'Weather', 'value': '{{temp}}'}]}
        page = {'layout': 'tiles', 'slots': {'tiles': [{'widget': 'clock.tile', 'size': 'wide'}]}}
        for view in [tile, page]:
            tools.validate_view(view, 4)
            for api in (1, 2, 3):
                with self.assertRaises(tools.ValidationError): tools.validate_view(view, api)
        for color in ['#2D89EF', '{{c}}']:
            tools.validate_view(dict(tile, color=color), 4)
            tools.validate_view({'layout': 'tiles', 'slots': {'tiles': [dict(page['slots']['tiles'][0], color=color)]}}, 4)
        for color in ['red', '#FFF', '#2D89EF80']:
            with self.assertRaises(tools.ValidationError): tools.validate_view(dict(tile, color=color), 4)
            with self.assertRaises(tools.ValidationError): tools.validate_view({'layout': 'tiles', 'slots': {'tiles': [dict(page['slots']['tiles'][0], color=color)]}}, 4)
        for content in [{'type': 'list', 'items': '{{rows}}', 'item': {'title': '{{.name}}'}}, {'type': 'image', 'src': '{{src}}'}, {'type': 'carousel', 'items': 'photos', 'interval': 30, 'duration': 800}]:
            with self.assertRaises(tools.ValidationError): tools.validate_view(dict(tile, content=content), 4)
        with self.assertRaises(tools.ValidationError): tools.validate_view({'layout': 'tiles', 'slots': {'tiles': [{'widget': 'clock.tile', 'size': 'huge'}]}}, 4)

    def test_api_five_aero_is_fixed_bounded_vocabulary(self):
        page = {'layout': 'aero', 'slots': {'windows': [{'widget': 'clock.desktop'}]}}
        tools.validate_view(page, 5)
        for api in range(1, 5):
            with self.assertRaises(tools.ValidationError): tools.validate_view(page, api)
        for windows in [[], [{'widget': 'clock.desktop'}] * 4, [{'widget': 'clock.desktop', 'color': '#ffffff'}]]:
            with self.assertRaises(tools.ValidationError): tools.validate_view({'layout': 'aero', 'slots': {'windows': windows}}, 5)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.plugin = self.root / 'plugin'
        shutil.copytree(ROOT / 'plugins/clock', self.plugin)

    def manifest(self, edit):
        path = self.plugin / 'manifest.json'
        data = json.loads(path.read_text())
        edit(data)
        path.write_text(json.dumps(data))

    def test_builtin_packages_validate_and_are_reproducible(self):
        for name in ('home', 'clock', 'weather', 'gallery', 'tiles', 'aero', 'settings'):
            first, digest = packages.package_plugin(ROOT / 'plugins' / name, self.root / 'a')
            second, again = packages.package_plugin(ROOT / 'plugins' / name, self.root / 'b')
            self.assertEqual(digest, again)
            self.assertTrue(first.is_dir())
            first_files = {item.relative_to(first).as_posix(): item.read_bytes() for item in first.rglob('*') if item.is_file()}
            second_files = {item.relative_to(second).as_posix(): item.read_bytes() for item in second.rglob('*') if item.is_file()}
            self.assertEqual(first_files, second_files)
            index = json.loads(first_files['index.json'])
            self.assertEqual({entry['path'] for entry in index['files']}, set(first_files) - {'index.json'})
            self.assertEqual(index['digest'], digest)
            canonical = b''
            for entry in index['files']:
                content = first_files[entry['path']]
                self.assertEqual(entry['size'], len(content))
                self.assertEqual(entry['sha256'], hashlib.sha256(content).hexdigest())
                canonical += (entry['path'] + '\0' + entry['sha256'] + '\n').encode('utf-8')
            self.assertEqual(hashlib.sha256(canonical).hexdigest(), digest)

    def test_non_runtime_files_are_not_distributed(self):
        (self.plugin / 'README.md').write_text('not device content')
        output, _ = packages.package_plugin(self.plugin, self.root / 'out')
        self.assertFalse((output / 'README.md').exists())

    def test_case_collisions_are_rejected(self):
        assets = self.plugin / 'assets'
        assets.mkdir()
        (assets / 'Clock.png').write_bytes(b'a')
        other = self.plugin / 'assets2'
        other.mkdir()
        (other / 'other.png').write_bytes(b'b')
        from unittest.mock import patch
        original = Path.relative_to
        def relative(path, *args, **kwargs):
            if path.name == 'other.png' and path.parent.name == 'assets2':
                return Path('assets/clock.png')
            return original(path, *args, **kwargs)
        with patch.object(Path, 'relative_to', relative):
            with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_legacy_archive_is_removed(self):
        output = self.root / 'out'
        output.mkdir()
        (output / 'clock.tpk').write_bytes(b'legacy')
        packages.package_plugin(self.plugin, output)
        self.assertFalse((output / 'clock.tpk').exists())

    def test_directory_index_schema_rejects_invalid_metadata(self):
        manifest, files = packages.validate_plugin(self.plugin)
        valid = packages.file_index(manifest, files)
        tools.validate(valid, document='index.json')
        changes = [lambda item: item.update(size=-1), lambda item: item.update(size=.5),
                   lambda item: item.update(size='3'), lambda item: item.update(size=1048577),
                   lambda item: item.update(sha256='F' * 64), lambda item: item.update(path='../escape.lua'),
                   lambda item: item.update(path='a//b.lua'), lambda item: item.update(path='a/./b.lua'),
                   lambda item: item.update(path='a/'), lambda item: item.update(extra=True)]
        for change in changes:
            invalid = copy.deepcopy(valid)
            change(invalid['files'][0])
            with self.assertRaises(packages.ValidationError): tools.validate(invalid, document='index.json')
        tools.validate({'plugins': [valid]}, document='catalog.json')
        with self.assertRaises(packages.ValidationError): tools.validate({'plugins': [valid] * 33}, document='catalog.json')
        with self.assertRaises(packages.ValidationError): tools.validate({'plugins': [], 'execute': True}, document='catalog.json')

    def test_directory_publisher_treats_paths_and_content_as_data(self):
        import subprocess
        from unittest.mock import patch
        sys.path.insert(0, str(ROOT / 'tools'))
        import plugin_push
        hostile = 'data;touch LEAK.lua'
        payload = b'__THINKOS_FILE__\n$(touch LEAK)\n'
        (self.plugin / hostile).write_bytes(payload)
        directory, _ = packages.package_plugin(self.plugin, self.root / 'out')
        device = self.root / 'device'
        device.mkdir()
        execute = subprocess.run
        calls = []
        def local_shell(args, *, input, check, timeout):
            calls.append(args)
            return execute(['sh'], input=input, check=check, timeout=timeout, cwd=device)
        with patch.object(plugin_push.subprocess, 'run', local_shell):
            plugin_push.publish_directory(directory)
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0][-3:], ['run-as', 'moe.kiwi.thinkos', 'sh'])
        self.assertEqual((device / 'files/dev-plugins/clock' / hostile).read_bytes(), payload)
        self.assertFalse((device / 'LEAK').exists())
        self.assertEqual(list((device / 'files/dev-plugins').iterdir()), [device / 'files/dev-plugins/clock'])

    def test_new_api_is_rejected(self):
        self.manifest(lambda data: data.update(api=6))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_traversal_reference_is_rejected(self):
        self.manifest(lambda data: data.update(main='../outside.lua'))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_symlink_cannot_export_external_data(self):
        (self.root / 'outside').write_text('must not package')
        (self.plugin / 'escape').symlink_to(self.root / 'outside')
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_unsupported_component_is_rejected(self):
        path = self.plugin / 'views/face.json'
        view = json.loads(path.read_text())
        view['slots']['hero']['type'] = 'webview'
        path.write_text(json.dumps(view))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_missing_view_is_rejected(self):
        (self.plugin / 'views/face.json').unlink()
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_duplicate_page_ids_are_rejected(self):
        self.manifest(lambda data: data['pages'].append(copy.deepcopy(data['pages'][0])))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_duplicate_json_keys_are_rejected(self):
        path = self.plugin / 'manifest.json'
        path.write_text(path.read_text().replace('"api": 4,', '"api": 4, "api": 2,'))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_setting_default_must_satisfy_its_declaration(self):
        self.manifest(lambda data: data.update(settings={'rate': {'type': 'number', 'label': 'Rate', 'default': 'fast', 'min': 1, 'max': 9}}))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_old_config_field_is_gone(self):
        self.manifest(lambda data: data.update(config={'rate': {'type': 'number', 'default': 5}}))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_plugin_id_system_is_reserved(self):
        self.manifest(lambda data: data.update(id='system'))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_valid_settings_are_accepted(self):
        self.manifest(lambda data: data.update(settings={
            'hour24': {'type': 'bool', 'label': '24h', 'default': True},
            'zone': {'type': 'choice', 'label': 'Zone', 'default': 'Asia/Hong_Kong', 'source': 'timezone'}}))
        packages.validate_plugin(self.plugin)

    def test_shared_setting_vectors_agree_with_the_runtime(self):
        vectors = json.loads((ROOT / 'shared/schema/settings/vectors.json').read_text())
        for item in vectors['valid']:
            with self.subTest(valid=item['key']):
                tools.validate_setting(item['key'], item['field'], item.get('system', False))
        for item in vectors['invalid']:
            with self.subTest(invalid=item['why']):
                with self.assertRaises(tools.ValidationError):
                    tools.validate_setting(item['key'], item['field'], item.get('system', False))

    def test_widget_open_must_point_to_declared_page(self):
        self.manifest(lambda data: data['widgets'][0].update(opens='absent'))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_size_budget(self):
        (self.plugin / 'huge.lua').write_bytes(b'x' * (packages.MAX_FILE_BYTES + 1))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_lua_file_cannot_exceed_runtime_load_limit(self):
        (self.plugin / 'main.lua').write_text('--' + 'x' * packages.MAX_JSON_SCRIPT_BYTES)
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_file_count_matches_runtime_budget(self):
        for index in range(packages.MAX_FILES):
            (self.plugin / f'data{index}.lua').write_text('a')
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_unknown_fields_fail_closed(self):
        self.manifest(lambda data: data.update(nativeBridge=True))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

if __name__ == '__main__': unittest.main()
