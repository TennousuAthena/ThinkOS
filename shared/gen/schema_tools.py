"""Small, fail-closed validator for the JSON Schema vocabulary used by ThinkOS.

No third-party dependencies. Unknown assertion keywords are rejected at schema load.
This is intentionally not a general JSON Schema implementation.
"""
from pathlib import Path
import json
import math
import re

SCHEMA_DIR = Path(__file__).resolve().parents[1] / 'schema' / 'plugin'
SCHEMAS = {p.name: json.loads(p.read_text()) for p in sorted(SCHEMA_DIR.glob('*.json'))}
SYSTEM_SETTINGS = json.loads((SCHEMA_DIR.parent / 'settings/system.json').read_text())
FORM_ICON_NAMES = frozenset(SCHEMAS['layouts.json']['$defs']['formIcon']['enum'])

class ValidationError(ValueError):
    pass

SUPPORTED_KEYS = {'$schema', '$id', '$defs', '$ref', 'title', 'description', 'x-thinkos-slot-roles', 'x-thinkos-api', 'type',
                  'properties', 'required', 'additionalProperties', 'maxProperties',
                  'items', 'minItems', 'maxItems', 'uniqueItems', 'minLength', 'maxLength',
                  'pattern', 'minimum', 'maximum', 'const', 'enum', 'oneOf', 'anyOf'}

def check_schema_vocabulary(schema):
    unknown = set(schema) - SUPPORTED_KEYS
    if unknown:
        raise ValidationError(f'Unsupported schema keywords: {sorted(unknown)}')
    for name in ('properties', '$defs'):
        for child in schema.get(name, {}).values():
            check_schema_vocabulary(child)
    for name in ('items', 'additionalProperties'):
        if isinstance(schema.get(name), dict):
            check_schema_vocabulary(schema[name])
    for name in ('oneOf', 'anyOf'):
        for child in schema.get(name, []):
            check_schema_vocabulary(child)

for _schema in SCHEMAS.values():
    check_schema_vocabulary(_schema)

def resolve(ref, document):
    filename, _, pointer = ref.partition('#')
    document = filename or document
    schema = SCHEMAS[document]
    for part in pointer.split('/')[1:]:
        schema = schema[part.replace('~1', '/').replace('~0', '~')]
    return schema, document

def _type_matches(value, kind):
    return {'object': lambda: isinstance(value, dict), 'array': lambda: isinstance(value, list),
            'string': lambda: isinstance(value, str), 'boolean': lambda: isinstance(value, bool),
            'number': lambda: isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value),
            'integer': lambda: isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value == int(value),
            'null': lambda: value is None}[kind]()

def validate(value, schema=None, document='manifest.json', path='$', depth=0):
    if depth > 32:
        raise ValidationError(f'{path}: maximum nesting exceeded')
    if schema is None:
        schema = SCHEMAS[document]
    def check(condition, message):
        if not condition:
            raise ValidationError(f'{path}: {message}')
    if '$ref' in schema:
        target, target_document = resolve(schema['$ref'], document)
        return validate(value, target, target_document, path, depth + 1)
    for keyword in ('oneOf', 'anyOf'):
        if keyword in schema:
            successes = 0
            errors = []
            for candidate in schema[keyword]:
                try:
                    validate(value, candidate, document, path, depth + 1)
                    successes += 1
                except ValidationError as exc:
                    errors.append(str(exc))
            check(successes == 1 if keyword == 'oneOf' else successes >= 1,
                  f'{keyword}: no unique supported shape ({"; ".join(errors)})')
    if 'type' in schema:
        kinds = schema['type'] if isinstance(schema['type'], list) else [schema['type']]
        check(any(_type_matches(value, kind) for kind in kinds), f'expected {kinds}')
    if 'const' in schema:
        check(type(value) is type(schema['const']) and value == schema['const'], f'expected {schema["const"]!r}')
    if 'enum' in schema:
        check(any(type(value) is type(item) and value == item for item in schema['enum']), 'value is not supported')
    if isinstance(value, dict):
        check(len(value) <= schema.get('maxProperties', 100000), 'too many properties')
        for key in schema.get('required', []):
            check(key in value, f'missing {key}')
        for key, item in value.items():
            child = schema.get('properties', {}).get(key)
            if child is None:
                extra = schema.get('additionalProperties', True)
                check(extra is not False, f'unknown property {key}')
                child = extra if isinstance(extra, dict) else None
            if child is not None:
                validate(item, child, document, f'{path}.{key}', depth + 1)
    elif isinstance(value, list):
        check(len(value) >= schema.get('minItems', 0), 'too few items')
        check(len(value) <= schema.get('maxItems', 100000), 'too many items')
        if schema.get('uniqueItems'):
            check(len({json.dumps(v, sort_keys=True) for v in value}) == len(value), 'duplicate items')
        if 'items' in schema:
            for index, item in enumerate(value):
                validate(item, schema['items'], document, f'{path}[{index}]', depth + 1)
    elif isinstance(value, str):
        check(len(value) >= schema.get('minLength', 0), 'string too short')
        check(len(value) <= schema.get('maxLength', 1000000), 'string too long')
        if 'pattern' in schema:
            check(re.search(schema['pattern'], value) is not None, 'invalid string format')
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        check(value >= schema.get('minimum', -math.inf), 'number below minimum')
        check(value <= schema.get('maximum', math.inf), 'number above maximum')

_PLUGIN_KEY = re.compile(r'[a-z][a-z0-9_]{0,63}$')
_SYSTEM_KEY = re.compile(r'[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$')
_CHOICE_VALUE = re.compile(r'[A-Za-z0-9_.-]{1,64}$')
_COMMON = {'type', 'label', 'default', 'help', 'group', 'hidden', 'level', 'depends'}
_ALLOWED = {'bool': _COMMON | {'warn'}, 'choice': _COMMON | {'choices', 'source', 'allowEmpty'},
            'number': _COMMON | {'min', 'max', 'step', 'unit', 'format', 'zero', 'presets'}, 'text': _COMMON | {'maxLength', 'secret', 'placeholder'}, 'time': _COMMON}
SETTING_SOURCES = {'timezone', 'place', 'region'}

def _number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)

def _same(a, b):
    return a == b and ((_number(a) and _number(b)) or type(a) is type(b))

def validate_setting(key, field, system=False):
    """Semantic rules for one setting declaration (ADR-017). Mirrors the Kotlin SettingField.parse;
    shared/schema/settings/vectors.json is run through both so they cannot drift apart."""
    def fail(message):
        raise ValidationError(f'settings.{key}: {message}')
    if not (_SYSTEM_KEY if system else _PLUGIN_KEY).match(key):
        fail('invalid key')
    if not isinstance(field, dict):
        fail('must be an object')
    kind = field.get('type')
    if kind not in _ALLOWED:
        fail('type must be bool, choice, number, text or time')
    for name in field:
        if name not in _ALLOWED[kind] and not (system and name == 'section'):
            fail(f"unknown property '{name}' for type {kind}")
    label = field.get('label')
    if not isinstance(label, str) or not label.strip() or not 1 <= len(label) <= 80:
        fail('label is required (1-80 characters)')
    for name, limit in (('help', 200), ('group', 80), ('warn', 200), ('placeholder', 200)):
        if name in field and (not isinstance(field[name], str) or len(field[name]) > limit):
            fail(f'{name} is limited to {limit} characters')
    if 'hidden' in field and not isinstance(field['hidden'], bool):
        fail('hidden must be true or false')
    for name in ('secret', 'allowEmpty'):
        if name in field and not isinstance(field[name], bool):
            fail(f'{name} must be true or false')
    if 'hidden' in field and 'level' in field:
        fail('hidden and level cannot be combined')
    if 'level' in field and field['level'] not in ('normal', 'advanced', 'hidden'):
        fail('level must be normal, advanced or hidden')
    if 'section' in field and (not isinstance(field['section'], str) or not re.fullmatch(r'[a-z][a-z0-9_-]{0,63}', field['section'])):
        fail('invalid section')
    if 'depends' in field:
        condition = field['depends']
        if not isinstance(condition, dict) or set(condition) not in ({'key', 'value'}, {'key', 'in'}, {'key', 'not'}):
            fail('depends needs exactly key and one of value, in or not')
        target = condition['key']
        if not isinstance(target, str) or not (_SYSTEM_KEY if system else _PLUGIN_KEY).fullmatch(target):
            fail('invalid dependency key')
        values = condition['in'] if 'in' in condition else [condition['not'] if 'not' in condition else condition['value']]
        if not isinstance(values, list) or not 1 <= len(values) <= 64:
            fail('depends.in needs 1-64 values')
        if any(not isinstance(v, (str, bool)) and not _number(v) for v in values):
            fail('dependency values must be strings, numbers or booleans')
        if any(any(_same(value, previous) for previous in values[:i]) for i, value in enumerate(values)):
            fail('dependency values must be unique')
    if 'default' not in field:
        fail('default is required')
    default = field['default']
    number = _number
    if kind == 'bool':
        ok = isinstance(default, bool)
    elif kind == 'number':
        lo, hi, step = field.get('min'), field.get('max'), field.get('step')
        if not (number(lo) and number(hi)):
            fail('number needs min and max')
        if not lo < hi:
            fail('min must be below max')
        if step is not None and not (number(step) and step > 0):
            fail('step must be positive')
        if not isinstance(field.get('unit', ''), str) or len(field.get('unit', '')) > 16:
            fail('unit is limited to 16 characters')
        if 'format' in field and (field['format'] != 'duration' or lo < 0):
            fail('format must be duration with non-negative bounds')
        if 'zero' in field and (field.get('format') != 'duration' or lo > 0 or not isinstance(field['zero'], str) or not field['zero'].strip() or len(field['zero']) > 80):
            fail('zero needs 1-80 characters and duration with zero in range')
        if 'presets' in field:
            values = field['presets']
            if not isinstance(values, list) or not 2 <= len(values) <= 64 or any(not number(v) or not lo <= v <= hi for v in values):
                fail('presets needs 2-64 numbers within range')
            if len(set(values)) != len(values):
                fail('presets must be unique')
        ok = number(default) and lo <= default <= hi
    elif kind == 'time':
        ok = isinstance(default, str) and re.fullmatch(r'(?:[01][0-9]|2[0-3]):[0-5][05]', default) is not None
    elif kind == 'text':
        limit = field.get('maxLength', 1024)
        if not _number(limit) or limit != int(limit) or not 1 <= limit <= 1024:
            fail('maxLength must be 1-1024')
        ok = isinstance(default, str) and len(default) <= limit and not any(ord(c) < 32 and c != '\t' for c in default)
    else:
        source, choices, allow_empty = field.get('source'), field.get('choices'), field.get('allowEmpty', False)
        if (source is None) == (choices is None):
            fail("a choice needs exactly one of 'choices' or 'source'")
        if source is not None and source not in SETTING_SOURCES:
            fail(f"unknown source '{source}'")
        if choices is not None:
            if not isinstance(choices, list) or not 2 <= len(choices) <= 64:
                fail('choices must list 2-64 options')
            values = []
            for index, item in enumerate(choices):
                if not isinstance(item, dict) or set(item) != {'value', 'label'}:
                    fail(f'choices[{index}] must be exactly {{value,label}}')
                if not isinstance(item['value'], str) or not _CHOICE_VALUE.match(item['value']):
                    fail(f'choices[{index}].value is invalid')
                if not isinstance(item['label'], str) or not item['label'].strip() or not 1 <= len(item['label']) <= 80:
                    fail(f'choices[{index}].label is required (1-80 characters)')
                values.append(item['value'])
            if len(set(values)) != len(values):
                fail('choice values must be unique')
            if allow_empty:
                fail('allowEmpty only applies to dynamic sources')
            ok = default in values
        else:
            ok = isinstance(default, str) and (default != '' or allow_empty is True)
            if source == 'region' and isinstance(default, str) and default != '':
                ok = re.fullmatch(r'[A-Za-z]{2}', default) is not None
    if not ok:
        fail('default does not satisfy the declaration')

def validate_settings(fields, system=False):
    if not isinstance(fields, dict):
        raise ValidationError('settings must be an object')
    for key, field in fields.items():
        validate_setting(key, field, system)
    for key, field in fields.items():
        condition = field.get('depends')
        if condition is None:
            continue
        target = fields.get(condition['key'])
        if target is None:
            raise ValidationError(f'settings.{key}: unknown dependency {condition["key"]}')
        # A dependency value must be one the target can hold. Dynamic sources are checked
        # structurally here, before their OS providers run, as at Kotlin install time.
        for value in condition.get('in', [condition['not'] if 'not' in condition else condition.get('value')]):
            validate_setting(condition['key'], {**target, 'default': value}, system)
    complete = set()
    for key in fields:
        path = set()
        current = key
        while current is not None and current not in complete:
            if current in path:
                raise ValidationError(f'settings.{key}: dependency cycle')
            path.add(current)
            current = fields[current].get('depends', {}).get('key')
        complete.update(path)

def validate_system_settings(document):
    if not isinstance(document, dict) or set(document) - {'description', 'scope', 'fields', 'constraints', 'sections'}:
        raise ValidationError('unknown system settings property')
    if document.get('scope') != 'system':
        raise ValidationError('invalid system settings scope')
    fields = document.get('fields')
    validate_settings(fields, True)
    sections = document.get('sections', [])
    if not isinstance(sections, list) or len(sections) > 32:
        raise ValidationError('sections needs at most 32 entries')
    ids = set()
    for item in sections:
        if not isinstance(item, dict) or not {'id', 'label'} <= set(item) or set(item) - {'id', 'label', 'icon'}:
            raise ValidationError('section needs id, label and optional icon')
        key, label = item['id'], item['label']
        if not isinstance(key, str) or not re.fullmatch(r'[a-z][a-z0-9_-]{0,63}', key) or key in ids:
            raise ValidationError('settings section ids must be valid and unique')
        if not isinstance(label, str) or not label.strip() or not 1 <= len(label) <= 80:
            raise ValidationError('settings section label needs 1-80 characters')
        if 'icon' in item and (not isinstance(item['icon'], str) or item['icon'] not in FORM_ICON_NAMES):
            raise ValidationError('settings section icon must be a registered vector glyph')
        ids.add(key)
    for key, field in fields.items():
        if 'section' in field and field['section'] not in ids:
            raise ValidationError(f'settings.{key}: unknown section')
    for pair in document.get('constraints', []):
        if not isinstance(pair, list) or len(pair) != 2 or any(key not in fields for key in pair):
            raise ValidationError('constraint names an unknown setting')
        first, second = (fields[key] for key in pair)
        if first['type'] != second['type'] or first['type'] not in ('number', 'time'):
            raise ValidationError('constraint needs two numbers or two times')
        if (first['type'] == 'time' and first['default'] == second['default']) or (first['type'] == 'number' and first['default'] >= second['default']):
            raise ValidationError('constraint defaults conflict')

def validate_related(keys, system_fields=None):
    fields = SYSTEM_SETTINGS['fields'] if system_fields is None else system_fields
    if not isinstance(keys, list) or len(keys) > 32 or any(not isinstance(key, str) for key in keys) or len(set(keys)) != len(keys):
        raise ValidationError('related must list at most 32 unique system settings')
    for key in keys:
        field = fields.get(key)
        if field is None or field.get('hidden', False) or field.get('level') == 'hidden' or field.get('secret', False):
            raise ValidationError(f'related.{key}: setting must exist and be visible and non-secret')

def validate_manifest(manifest):
    validate(manifest)
    for collection in ('pages', 'widgets', 'commands', 'apps'):
        ids = [entry['id'] for entry in manifest.get(collection, [])]
        if len(set(ids)) != len(ids):
            raise ValidationError(f'manifest.{collection}: duplicate id')
    if manifest['id'] == 'system':
        raise ValidationError("manifest.id: 'system' is reserved for the OS's own settings")
    validate_settings(manifest.get('settings', {}))
    validate_related(manifest.get('related', []))
    pages = {page['id'] for page in manifest.get('pages', [])}
    for widget in manifest.get('widgets', []):
        if widget.get('opens') is not None and widget['opens'] not in pages:
            raise ValidationError(f'widget {widget["id"]}: opens must refer to a page in this plugin')
    for app in manifest.get('apps', []):
        if app['page'] not in pages:
            raise ValidationError(f'app {app["id"]}: page must refer to a page in this plugin')
        # An app is entered and left by the user; a page that holds a role (home, sleep, settings, ...) is placed by the OS.
        if next(page for page in manifest['pages'] if page['id'] == app['page']).get('roles'):
            raise ValidationError(f'app {app["id"]}: its page cannot hold a role')
    validate_public_contract(manifest)

def validate_public_contract(manifest):
    """API 6 public data and commands: rules JSON Schema cannot express here (ADR-027)."""
    vocabulary = SCHEMAS['layouts.json']['x-thinkos-api']
    api = manifest['api']
    def gate(entry, table, what):
        for key, minimum in table.items():
            if key in entry and minimum > api:
                raise ValidationError(f'{what}{key} requires a newer plugin API')
    gate(manifest, vocabulary['manifestFields'], 'manifest.')
    for permission in manifest.get('permissions', []):
        if vocabulary.get('permissions', {}).get(permission, 1) > api:
            raise ValidationError(f'permission {permission} requires a newer plugin API')
    for page in manifest.get('pages', []):
        for role in page.get('roles', []):
            if vocabulary.get('pageRoles', {}).get(role, 1) > api:
                raise ValidationError(f'page role {role} requires a newer plugin API')
            if role == 'launcher' and 'system.apps' not in manifest.get('permissions', []):
                raise ValidationError('the launcher role needs the system.apps permission')
            if role in ('onboarding', 'settings') and 'system.settings' not in manifest.get('permissions', []):
                raise ValidationError('the settings/onboarding role needs the system.settings permission')
    for field in manifest.get('settings', {}).values():
        gate(field, vocabulary['settingFields'], 'setting.')
        if vocabulary.get('settingTypes', {}).get(field.get('type'), 1) > api:
            raise ValidationError('setting type requires a newer plugin API')
        if vocabulary.get('settingSources', {}).get(field.get('source'), 1) > api:
            raise ValidationError('setting source requires a newer plugin API')
    for widget in manifest.get('widgets', []):
        gate(widget, vocabulary['widgetFields'], 'widget.')
    for command in manifest.get('commands', []):
        gate(command, vocabulary['commandFields'], 'command.')
        if 'cooldown' in command and command.get('public') is not True:
            raise ValidationError(f'command {command["id"]}: cooldown only applies to public commands')
    for name in manifest.get('exports', {}):
        if not re.fullmatch(r'[a-z][a-z0-9-]{0,63}', name):
            raise ValidationError(f'manifest.exports: invalid name {name!r}')
    # A path inside another export would let its readers bypass the inner one's `requires`.
    paths = [export['path'] for export in manifest.get('exports', {}).values()]
    for i, path in enumerate(paths):
        for other in paths[i + 1:]:
            if path == other or path.startswith(other + '.') or other.startswith(path + '.'):
                raise ValidationError(f'manifest.exports: paths overlap: {path}, {other}')
    for alias, use in manifest.get('uses', {}).items():
        if not re.fullmatch(r'[a-z][a-z0-9_]{0,63}', alias):
            raise ValidationError(f'manifest.uses: invalid alias {alias!r}')
        if use['export'].split('.')[0] == manifest['id']:
            raise ValidationError(f'manifest.uses.{alias}: a plugin cannot use its own export')
    for target in manifest.get('calls', []):
        if target.split('.')[0] == manifest['id']:
            raise ValidationError(f'manifest.calls: a plugin cannot call its own command: {target}')

def validate_view(view, api=None):
    validate(view, document='view.json')
    vocabulary = SCHEMAS['layouts.json']['x-thinkos-api']
    api = vocabulary['current'] if api is None else api
    if api not in SCHEMAS['manifest.json']['properties']['api']['enum']:
        raise ValidationError('unsupported plugin API')
    def gate(value):
        if isinstance(value, list):
            for child in value:
                gate(child)
        elif isinstance(value, dict):
            layout = value.get('layout')
            template = value.get('template')
            if template is not None and vocabulary['templates'].get(template, api + 1) > api:
                raise ValidationError(f'template {template} requires a newer plugin API')
            if template == 'tile':
                for key, minimum in vocabulary['tileFields'].items():
                    if key in value and minimum > api:
                        raise ValidationError(f'tile.{key} requires a newer plugin API')
                for face in value.get('faces', []):
                    for key, minimum in vocabulary['tileFaceFields'].items():
                        if isinstance(face, dict) and key in face and minimum > api:
                            raise ValidationError(f'tile face {key} requires a newer plugin API')
            kind = value.get('type')
            if layout is not None and vocabulary['layouts'].get(layout, api + 1) > api:
                raise ValidationError(f'layout {layout} requires a newer plugin API')
            slots = value.get('slots')
            if isinstance(slots, dict):
                for key in slots:
                    if vocabulary.get('layoutSlots', {}).get(str(layout)+'.'+key, 1) > api:
                        raise ValidationError('layout slot requires a newer plugin API')
            if isinstance(slots, dict) and vocabulary['slotWidgets'] > api and any(isinstance(slot, dict) and 'widget' in slot for slot in slots.values()):
                raise ValidationError('widget references in slots require a newer plugin API')
            if kind is not None:
                if vocabulary['componentTypes'].get(kind, api + 1) > api:
                    raise ValidationError(f'component {kind} requires a newer plugin API')
                for key, minimum in vocabulary['componentProperties'].items():
                    if key in value and minimum > api:
                        raise ValidationError(f'component property {key} requires a newer plugin API')
            if 'children' in value and 'template' not in value and kind is None and vocabulary['componentGroups'] > api:
                raise ValidationError('component groups require a newer plugin API')
            for child in value.values():
                gate(child)
    gate(view)
    ids = [variant['id'] for variant in view.get('variants', [])]
    if len(ids) != len(set(ids)):
        raise ValidationError('view.variants: duplicate id')
