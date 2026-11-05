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
    def test_time_setting_values_and_api_are_fixed(self):
        field = {'type': 'time', 'label': '时刻', 'default': '23:55'}
        manifest = {'id': 'clock-test', 'name': 'Clock', 'version': '0.1.0', 'api': 10, 'settings': {'at': field}}
        tools.validate_manifest(manifest)
        for api in range(1, 10):
            with self.assertRaises(tools.ValidationError):
                tools.validate_manifest({**manifest, 'api': api})
        for bad in ('7:00', '24:00', '12:60', '12:03', '12:00:00', '１２:００', '12:00\n', True, 720):
            with self.assertRaises(tools.ValidationError):
                tools.validate_setting('at', {**field, 'default': bad})
        for key in ('step', 'style', 'maxLength'):
            with self.assertRaises(tools.ValidationError):
                tools.validate_setting('at', {**field, key: 5})
        api = tools.SCHEMAS['layouts.json']['x-thinkos-api']
        self.assertEqual(10, api['settingTypes']['time'])
        self.assertEqual(10, api['formFields']['time'])
        self.assertEqual(10, api['runtimeMethods']['device.mode'])
        self.assertEqual(10, api['runtimeMethods']['device.setMode'])
        self.assertEqual(10, api['runtimeEvents']['mode'])

    def test_mode_window_contract_allows_midnight_and_forbids_equal_defaults(self):
        doc = copy.deepcopy(tools.SYSTEM_SETTINGS)
        tools.validate_system_settings(doc)
        doc['fields']['sleep.start']['default'] = '08:00'
        doc['fields']['sleep.end']['default'] = '17:00'
        tools.validate_system_settings(doc)
        doc['fields']['sleep.end']['default'] = '08:00'
        with self.assertRaises(tools.ValidationError):
            tools.validate_system_settings(doc)
    def test_form_descriptor_icons_are_fixed_vector_names_without_styles(self):
        definitions = tools.SCHEMAS['layouts.json']['$defs']
        link = {'key': 'section:display', 'type': 'link', 'label': '显示', 'icon': 'monitor'}
        nav = {'items': [{'id': 'display', 'label': '显示', 'icon': 'monitor'}], 'current': 'display'}
        tools.validate(link, definitions['formLink'], 'layouts.json')
        tools.validate(nav, definitions['formNav'], 'layouts.json')
        for value in ('', '../monitor', 'MONITOR', 'missing-vector', 7, None):
            with self.subTest(value=value), self.assertRaises(tools.ValidationError):
                tools.validate(dict(link, icon=value), definitions['formLink'], 'layouts.json')
            with self.subTest(nav=value), self.assertRaises(tools.ValidationError):
                tools.validate({'items': [dict(nav['items'][0], icon=value)]}, definitions['formNav'], 'layouts.json')
        with self.assertRaises(tools.ValidationError):
            tools.validate(dict(link, size=48), definitions['formLink'], 'layouts.json')
        self.assertEqual(10, tools.SCHEMAS['layouts.json']['x-thinkos-api']['formFields']['link.icon'])
        self.assertEqual(10, tools.SCHEMAS['layouts.json']['x-thinkos-api']['formFields']['nav.items.icon'])

    def test_system_update_permission_requires_api_ten(self):
        value={'id':'update-probe','name':'Updates','version':'0.1.0','api':10,'permissions':['system.update']}
        tools.validate_manifest(value)
        for api in range(1,10):
            with self.subTest(api=api), self.assertRaises(tools.ValidationError):
                tools.validate_manifest(dict(value,api=api))
        self.assertEqual(10,tools.SCHEMAS['layouts.json']['x-thinkos-api']['runtimeMethods']['update.status'])
        self.assertEqual(10,tools.SCHEMAS['layouts.json']['x-thinkos-api']['runtimeEvents']['update'])

    def test_launcher_role_requires_api_nine_and_system_apps(self):
        value = {'id': 'launcher-test', 'name': 'Launcher', 'version': '1.0.0', 'api': 9,
                 'permissions': ['system.apps'], 'pages': [{'id': 'main', 'title': 'Apps', 'view': 'views/main.json', 'roles': ['launcher']}]}
        tools.validate_manifest(value)
        with self.assertRaises(tools.ValidationError): tools.validate_manifest(dict(value, api=8))
        with self.assertRaises(tools.ValidationError): tools.validate_manifest(dict(value, permissions=[]))
    def test_setting_prompts_are_api_nine_and_legacy_help_stays_compatible(self):
        field = {'type': 'bool', 'label': 'Cast', 'default': False, 'warn': 'Anyone nearby can cast'}
        manifest = {'id': 'prompt-probe', 'name': 'Prompts', 'version': '0.1.0', 'api': 9, 'settings': {'cast': field}}
        tools.validate_manifest(manifest)
        manifest['api'] = 8
        with self.assertRaises(packages.ValidationError): tools.validate_manifest(manifest)
        manifest.update(api=1, settings={'cast': {'type': 'bool', 'label': 'Cast', 'default': False, 'help': 'Legacy help'}})
        tools.validate_manifest(manifest)

    def test_builtin_settings_groups_are_contiguous(self):
        for path in (ROOT / 'plugins').glob('*/manifest.json'):
            fields = json.loads(path.read_text()).get('settings', {})
            previous, finished = None, set()
            for key, field in fields.items():
                group = field.get('group')
                if group != previous:
                    if previous is not None: finished.add(previous)
                    self.assertNotIn(group, finished, str(path) + ':' + key)
                    previous = group

    def test_api_three_gallery_exposes_bing_and_local_through_shared_interfaces(self):
        manifest, _ = packages.validate_plugin(ROOT / 'plugins/gallery')
        self.assertEqual(9, manifest['api'])
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

    def test_form_navigation_is_optional_and_api_nine_only(self):
        view = {'layout': 'form', 'slots': {'body': {'type': 'form', 'path': 'fields'}}}
        tools.validate_view(view, 1)
        view['slots']['body']['nav'] = 'settingsNav'
        tools.validate_view(view, 9)
        for api in range(1, 9):
            with self.assertRaises(tools.ValidationError): tools.validate_view(view, api)
        for bad in ({}, 'bad path', 3):
            view['slots']['body']['nav'] = bad
            with self.assertRaises(tools.ValidationError): tools.validate_view(view, 9)

    def test_withdrawn_list_fields_are_rejected_and_grid_keeps_api_eight(self):
        for field in ('image', 'group'):
            view = {'layout':'focus', 'slots':{'hero':{'type':'list', 'items':'items', 'item':{'title':'{{.title}}', field:'{{.'+field+'}}'}}}}
            for api in range(2, 10):
                with self.assertRaises(tools.ValidationError): tools.validate_view(view, api)
        grid = {'layout':'focus','slots':{'hero':{'type':'grid','items':'items','item':{'title':'{{.title}}','image':'{{.image}}','icon':'apps'}}}}
        tools.validate_view(grid, 8)
        tools.validate_view(grid, 9)
        with self.assertRaises(tools.ValidationError): tools.validate_view(grid, 7)
        manifest, _ = packages.validate_plugin(ROOT/'plugins/apps')
        self.assertEqual(['system.apps'], manifest['permissions'])
        self.assertFalse(manifest['apps'][0]['listed'])
    def test_tile_packer_uses_first_fit_in_both_orientations(self):
        # 8x6 landscape holds two large tiles side by side; 6x8 portrait stacks them and has no room for a wide one.
        mixed = ["large", "large", "wide", "wide"]
        self.assertEqual(set(), packages.tile_drops(mixed, 8, 6))
        self.assertEqual({2, 3}, packages.tile_drops(mixed, 6, 8))
        self.assertEqual({2}, packages.tile_drops(["large"]*3, 8, 6))
        self.assertEqual({2}, packages.tile_drops(["large"]*3, 6, 8))

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
        # Keep API-gating fixtures at their original API 6 baseline, independent of
        # new metadata added to the factory clock. Factory packages are tested separately.
        path = self.plugin / 'manifest.json'
        manifest = json.loads(path.read_text())
        manifest['api'] = 6
        manifest.pop('related', None)
        for field in manifest.get('settings', {}).values():
            level = field.pop('level', 'normal')
            if level == 'hidden': field['hidden'] = True
        path.write_text(json.dumps(manifest, indent=2))

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

    def test_tile_face_content_is_gated_by_api_six(self):
        tile = {'template': 'tile', 'faces': [{'label': 'x', 'content': {'type': 'clock', 'style': 'analog'}}]}
        tools.validate_view(tile, 6)
        with self.assertRaises(tools.ValidationError): tools.validate_view(tile, 5)
        tile['faces'][0]['content'] = {'type': 'image', 'src': 'a'}
        with self.assertRaises(tools.ValidationError): tools.validate_view(tile, 6)

    def test_public_contract_is_gated_validated_and_reserved(self):
        for field in (dict(exports={'x': {'path': 'public.x', 'shape': 's/1'}}),
                      dict(uses={'x': {'export': 'other.x', 'shape': 's/1'}}),
                      dict(calls=['other.go'])):
            self.manifest(lambda data: data.update(api=5, **field))
            with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)
            self.manifest(lambda data: data.update(api=6))
            packages.validate_plugin(self.plugin)
            self.manifest(lambda data: [data.pop(key) for key in field])
        self.manifest(lambda data: data.update(api=6))
        for bad in (dict(exports={'x': {'path': 'config.token', 'shape': 's/1'}}),
                    dict(exports={'x': {'path': 'uses.daily', 'shape': 's/1'}}),
                    dict(exports={'Bad': {'path': 'a', 'shape': 's/1'}}),
                    dict(exports={'x': {'path': 'a', 'shape': 'unversioned'}}),
                    dict(exports={'a': {'path': 'public', 'shape': 's/1'}, 'b': {'path': 'public.place', 'shape': 's/1', 'requires': ['location']}}),
                    dict(exports={'x': {'path': 'a', 'shape': 's/1', 'requires': ['system.settings']}}),
                    dict(uses={'Bad': {'export': 'other.x', 'shape': 's/1'}}),
                    dict(calls=['clock.go'])):
            self.manifest(lambda data: data.update(**bad))
            with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)
            self.manifest(lambda data: [data.pop(key) for key in bad])

    def test_views_can_only_bind_declared_uses_and_alternates_must_match(self):
        self.manifest(lambda data: data.update(api=6))
        view = self.plugin / 'views/face.json'
        original = view.read_text()
        data = json.loads(original)
        data['slots']['under'] = {'type': 'text', 'value': '{{uses.ghost.x}}'}
        view.write_text(json.dumps(data))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)
        self.manifest(lambda data: data.update(uses={'ghost': {'export': 'other.x', 'shape': 's/1'}}))
        packages.validate_plugin(self.plugin)
        view.write_text(original)
        relative = view.relative_to(self.plugin).as_posix()
        def alternate(data):
            data.setdefault('widgets', [{'id': 'w', 'view': 'views/w.json'}])[0]['alternates'] = {'tile': relative}
        (self.plugin / 'views/w.json').write_text(json.dumps({'template': 'glance', 'children': [{'type': 'text', 'value': 'x'}]}))
        self.manifest(alternate)
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_media_components_need_api_seven_and_the_media_permission(self):
        path = self.plugin / 'views/face.json'
        original = json.loads(path.read_text())
        for component in ({'type': 'video', 'fit': 'cover'}, {'type': 'controls', 'mode': 'seek'}, {'type': 'seekbar'}):
            view = copy.deepcopy(original)
            view['slots']['hero'] = component
            path.write_text(json.dumps(view))
            self.manifest(lambda data: data.update(api=6, permissions=['media']))
            with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)    # API 7 vocabulary
            self.manifest(lambda data: data.update(api=7, permissions=[]))
            with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)    # no media grant
            self.manifest(lambda data: data.update(api=7, permissions=['media']))
            packages.validate_plugin(self.plugin)
        view = copy.deepcopy(original)
        view['slots']['hero'] = {'type': 'controls', 'mode': 'repeat-all'}
        path.write_text(json.dumps(view))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_music_player_view_needs_api_eight_and_the_media_permission(self):
        path = self.plugin / 'views/face.json'
        original = json.loads(path.read_text())
        for component in ({'type': 'nowplaying', 'style': 'full'}, {'type': 'nowplaying', 'style': 'bar', 'idle': '{{footer}}'}):
            view = copy.deepcopy(original)
            view['slots']['hero'] = component
            path.write_text(json.dumps(view))
            self.manifest(lambda data: data.update(api=7, permissions=['media']))
            with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)    # API 8 vocabulary
            self.manifest(lambda data: data.update(api=8, permissions=[]))
            with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)    # no media grant
            self.manifest(lambda data: data.update(api=8, permissions=['media']))
            packages.validate_plugin(self.plugin)
        view = copy.deepcopy(original)
        view['slots']['hero'] = {'type': 'nowplaying', 'style': 'huge'}
        path.write_text(json.dumps(view))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_television_view_needs_api_eight_and_the_media_permission(self):
        path = self.plugin / 'views/face.json'
        original = json.loads(path.read_text())
        view = copy.deepcopy(original)
        view['slots']['hero'] = {'type': 'tv', 'channel': '{{tv}}', 'tap': 'tv', 'fit': 'cover'}
        path.write_text(json.dumps(view))
        self.manifest(lambda data: data.update(api=7, permissions=['media']))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)    # API 8 vocabulary
        self.manifest(lambda data: data.update(api=8, permissions=[]))
        with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)    # no media grant
        self.manifest(lambda data: data.update(api=8, permissions=['media']))
        packages.validate_plugin(self.plugin)
        for bad in ({'type': 'tv', 'fit': 'stretch'}, {'type': 'tv', 'style': 'full'}, {'type': 'tv', 'channel': 3}):
            view['slots']['hero'] = bad
            path.write_text(json.dumps(view))
            with self.assertRaises(packages.ValidationError): packages.validate_plugin(self.plugin)

    def test_iptv_plugin_is_a_valid_api_nine_package(self):
        manifest, _ = packages.validate_plugin(ROOT / 'plugins/iptv')
        self.assertEqual(9, manifest['api'])
        self.assertEqual({'media', 'network'}, set(manifest['permissions']))
        self.assertTrue(any(page.get('live') for page in manifest['pages']))

    def test_player_plugin_is_a_valid_api_nine_package(self):
        manifest, _ = packages.validate_plugin(ROOT / 'plugins/player')
        self.assertEqual(9, manifest['api'])
        self.assertIn('media', manifest['permissions'])

    def test_new_api_is_rejected(self):
        self.manifest(lambda data: data.update(api=tools.SCHEMAS['layouts.json']['x-thinkos-api']['current'] + 1))
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
        path.write_text(path.read_text().replace('{', '{"api": 2,', 1))
        with self.assertRaisesRegex(packages.ValidationError, 'duplicate JSON key: api'):
            packages.validate_plugin(self.plugin)

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

    def test_setting_dependencies_related_keys_and_sections_agree_with_runtime_vectors(self):
        vectors = json.loads((ROOT / 'shared/schema/settings/vectors.json').read_text())
        for item in vectors['validManifests']:
            tools.validate_manifest(item)
        for item in vectors['invalidManifests']:
            with self.subTest(manifest=item['why']), self.assertRaises(tools.ValidationError):
                tools.validate_manifest(item['manifest'])
        for item in vectors['validSystems']:
            tools.validate_system_settings(item)
        for item in vectors['invalidSystems']:
            with self.subTest(system=item['why']), self.assertRaises(tools.ValidationError):
                tools.validate_system_settings(item['document'])

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
