"""Generate OTA contracts from shared/schema/update, independently of plugin wire formats."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / 'shared/schema/update'
SUPPORTED = {'$schema', '$id', '$defs', '$ref', 'title', 'description', 'type', 'format', 'properties', 'required',
             'additionalProperties', 'minProperties', 'maxProperties', 'items', 'minItems', 'maxItems', 'uniqueItems',
             'minLength', 'maxLength', 'pattern', 'minimum', 'maximum', 'const', 'enum'}


def documents(directory=None):
    result = {p.name: json.loads(p.read_text()) for p in sorted((directory or SCHEMAS).glob('*.json'))}
    def check(node):
        if set(node) - SUPPORTED:
            raise ValueError(f'Unknown update schema keyword: {set(node) - SUPPORTED}')
        if 'format' in node and node['format'] != 'int64':
            raise ValueError('Unknown update numeric format')
        if node.get('type') == 'object' and (node.get('additionalProperties') is not False or 'properties' not in node):
            raise ValueError('Update objects require explicit closed properties')
        if node.get('uniqueItems') and node.get('items', {}).get('type') != 'string':
            raise ValueError('Update uniqueItems supports strings only')
        for group in ('properties', '$defs'):
            for item in node.get(group, {}).values(): check(item)
        for name in ('items', 'additionalProperties'):
            if isinstance(node.get(name), dict): check(node[name])
    for schema in result.values(): check(schema)
    return result


def resolve(node, document, docs):
    if '$ref' not in node: return node, document
    file, _, pointer = node['$ref'].partition('#')
    document = file or document
    result = docs[document]
    for part in pointer.lstrip('/').split('/') if pointer else []:
        result = result[part]
    return result, document


def objects(docs):
    seen = set()
    def walk(node, doc):
        if node.get('type') == 'object' and 'title' in node:
            title = node['title']
            if title in seen: raise ValueError('Duplicate update model name')
            if not re.fullmatch(r'[A-Z][A-Za-z0-9]*', title): raise ValueError('Invalid update model name')
            seen.add(title)
            yield node, doc
        for item in node.get('$defs', {}).values(): yield from walk(item, doc)
        for key, item in node.get('properties', {}).items():
            if 'title' in node and not re.fullmatch(r'[a-z][a-z0-9_]*', key): raise ValueError('Invalid update property name')
            yield from walk(item, doc)
        if isinstance(node.get('items'), dict): yield from walk(node['items'], doc)
    for doc, schema in docs.items(): yield from walk(schema, doc)


def model_type(node, doc, docs, go=False):
    node, doc = resolve(node, doc, docs)
    if 'title' in node: return node['title']
    kind = node['type']
    if kind == 'array':
        item = model_type(node['items'], doc, docs, go)
        return '[]' + item if go else f'List<{item}>'
    if kind == 'object':
        if any(value.get('type') != 'string' for value in node.get('properties', {}).values()): raise ValueError('Only string maps supported')
        return 'map[string]string' if go else 'Map<String, String>'
    if kind == 'integer': return ('int64' if go else 'Long') if node.get('format') == 'int64' else ('int' if go else 'Int')
    return {'string': 'string' if go else 'String', 'boolean': 'bool' if go else 'Boolean'}[kind]


def decode(node, doc, docs, value):
    node, doc = resolve(node, doc, docs)
    if 'title' in node: return f'parse{node["title"]}({value} as JSONObject)'
    kind = node['type']
    if kind == 'integer': return f'({value} as Long)' + ('' if node.get('format') == 'int64' else '.toInt()')
    if kind == 'string': return f'({value} as String)'
    if kind == 'boolean': return f'({value} as Boolean)'
    if kind == 'object': return f'stringMap({value} as JSONObject)'
    if kind == 'array': return f'list({value} as JSONArray) {{ item -> {decode(node["items"], doc, docs, "item")} }}'
    raise ValueError('Unsupported update field')


def encode(node, doc, docs, value):
    node, doc = resolve(node, doc, docs)
    if 'title' in node: return f'build{node["title"]}({value})'
    kind = node['type']
    if kind == 'integer': return value if node.get('format') == 'int64' else f'{value}.toLong()'
    if kind in ('string', 'boolean'): return value
    if kind == 'object': return f'JSONObject({value})'
    if kind == 'array': return f'JSONArray().apply {{ {value}.forEach {{ item -> put({encode(node["items"], doc, docs, "item")}) }} }}'
    raise ValueError('Unsupported update field')


def generate_kotlin(directory=None, object_name='UpdateModels', filename='UpdateModels.kt'):
    docs = documents(directory)
    models = list(objects(docs))
    parts = ['// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_kotlin.py.\npackage moe.kiwi.thinkos.update\n\nimport org.json.JSONArray\nimport org.json.JSONObject\n']
    for node, doc in models:
        required = set(node.get('required', []))
        fields = []
        for name, prop in node['properties'].items():
            fields.append(f'    val `{name}`: {model_type(prop, doc, docs)}' + ('? = null' if name not in required else ''))
        parts.append(f'data class {node["title"]}(\n' + ',\n'.join(fields) + '\n)\n')
    parts.append('internal object ' + object_name + ' {\n    private val documents by lazy { mapOf(')
    entries = []
    for doc, schema in docs.items():
        literal = json.dumps(json.dumps(schema, separators=(',', ':'), ensure_ascii=False), ensure_ascii=False).replace('$', '\\$')
        entries.append(f'        "{doc}" to JSONObject({literal})')
    parts.append(',\n'.join(entries) + '\n    ) }\n')
    for doc, schema in docs.items():
        name = schema['title']
        parts.append(f'    fun read{name}(value: JSONObject): {name} {{\n        UpdateSchemaValidator.validate(value, documents.getValue("{doc}"), "{doc}", documents)\n        return parse{name}(value)\n    }}\n')
        parts.append(f'    fun write{name}(value: {name}): JSONObject = build{name}(value).also {{\n        UpdateSchemaValidator.validate(it, documents.getValue("{doc}"), "{doc}", documents)\n    }}\n')
    for node, doc in models:
        name = node['title']; args = []
        for key, prop in node['properties'].items():
            value = decode(prop, doc, docs, f'value.get("{key}")')
            if key not in node.get('required', []): value = f'if (value.has("{key}")) {value} else null'
            args.append(f'        `{key}` = {value}')
        parts.append(f'    private fun parse{name}(value: JSONObject): {name} = {name}(\n' + ',\n'.join(args) + '\n    )\n')
        parts.append(f'    private fun build{name}(value: {name}): JSONObject = JSONObject().apply {{')
        for key, prop in node['properties'].items():
            statement = f'put("{key}", {encode(prop, doc, docs, f"value.`{key}`")})'
            if key not in node.get('required', []): statement = f'if (value.`{key}` != null) {statement}'
            parts.append('        ' + statement)
        parts.append('    }\n')
    parts.append('    private fun stringMap(value: JSONObject): Map<String, String> = java.util.Collections.unmodifiableMap(value.keys().asSequence().associateWith { value.getString(it) })\n    private fun <T> list(value: JSONArray, read: (Any) -> T): List<T> = java.util.Collections.unmodifiableList((0 until value.length()).map { read(value.get(it)) })\n}\n')
    out = ROOT / 'android/app/src/main/java/moe/kiwi/thinkos/update' / filename
    out.parent.mkdir(parents=True, exist_ok=True); out.write_text('\n'.join(parts)); print(out.relative_to(ROOT))


def generate_go(directory=None, filename='update_generated.go'):
    docs = documents(directory)
    parts = ['// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_go.py.\npackage protocol\n']
    for node, doc in objects(docs):
        fields = []
        for key, prop in node['properties'].items():
            typ = model_type(prop, doc, docs, True)
            optional = key not in node.get('required', [])
            if optional and not typ.startswith(('[]', 'map[')): typ = '*' + typ
            name = ''.join(piece[:1].upper() + piece[1:] for piece in key.split('_'))
            fields.append(f'\t{name} {typ} `json:"{key}{",omitempty" if optional else ""}"`')
        parts.append(f'type {node["title"]} struct {{\n' + '\n'.join(fields) + '\n}\n')
    out = ROOT / 'server/internal/protocol' / filename
    out.parent.mkdir(parents=True, exist_ok=True); out.write_text('\n'.join(parts)); print(out.relative_to(ROOT))
