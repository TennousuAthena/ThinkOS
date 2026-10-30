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
_COMMON = {'type', 'label', 'default', 'help', 'group', 'hidden'}
_ALLOWED = {'bool': _COMMON, 'choice': _COMMON | {'choices', 'source', 'allowEmpty'},
            'number': _COMMON | {'min', 'max', 'step', 'unit'}, 'text': _COMMON | {'maxLength', 'secret'}}
SETTING_SOURCES = {'timezone', 'place'}

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
        fail('type must be bool, choice, number or text')
    for name in field:
        if name not in _ALLOWED[kind]:
            fail(f"unknown property '{name}' for type {kind}")
    label = field.get('label')
    if not isinstance(label, str) or not 1 <= len(label) <= 80:
        fail('label is required (1-80 characters)')
    for name, limit in (('help', 200), ('group', 80)):
        if name in field and (not isinstance(field[name], str) or len(field[name]) > limit):
            fail(f'{name} is limited to {limit} characters')
    if 'hidden' in field and not isinstance(field['hidden'], bool):
        fail('hidden must be true or false')
    if 'default' not in field:
        fail('default is required')
    default = field['default']
    number = lambda v: isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v)
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
        if len(field.get('unit', '')) > 16:
            fail('unit is limited to 16 characters')
        ok = number(default) and lo <= default <= hi
    elif kind == 'text':
        limit = field.get('maxLength', 1024)
        if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 1024:
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
                if not isinstance(item['label'], str) or not 1 <= len(item['label']) <= 80:
                    fail(f'choices[{index}].label is required (1-80 characters)')
                values.append(item['value'])
            if len(set(values)) != len(values):
                fail('choice values must be unique')
            if allow_empty:
                fail('allowEmpty only applies to dynamic sources')
            ok = default in values
        else:
            ok = isinstance(default, str) and (default != '' or allow_empty is True)
    if not ok:
        fail('default does not satisfy the declaration')

def validate_manifest(manifest):
    validate(manifest)
    for collection in ('pages', 'widgets', 'commands'):
        ids = [entry['id'] for entry in manifest.get(collection, [])]
        if len(set(ids)) != len(ids):
            raise ValidationError(f'manifest.{collection}: duplicate id')
    if manifest['id'] == 'system':
        raise ValidationError("manifest.id: 'system' is reserved for the OS's own settings")
    for key, field in manifest.get('settings', {}).items():
        validate_setting(key, field)
    pages = {page['id'] for page in manifest.get('pages', [])}
    for widget in manifest.get('widgets', []):
        if widget.get('opens') is not None and widget['opens'] not in pages:
            raise ValidationError(f'widget {widget["id"]}: opens must refer to a page in this plugin')

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
            kind = value.get('type')
            if layout is not None and vocabulary['layouts'].get(layout, api + 1) > api:
                raise ValidationError(f'layout {layout} requires a newer plugin API')
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
