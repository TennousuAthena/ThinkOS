#!/usr/bin/env python3
"""Generate Android plugin contracts and schema validator; no third-party packages."""
import json
from pathlib import Path
from schema_tools import SCHEMAS, SYSTEM_SETTINGS, resolve, validate_system_settings
ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'android/app/src/main/java/moe/kiwi/thinkos/plugin/PluginSchema.kt'

def kt_type(schema, doc):
    if '$ref' in schema:
        schema, doc = resolve(schema['$ref'], doc)
    if 'title' in schema:
        return schema['title']
    kind = schema.get('type')
    if kind is None and isinstance(schema.get('const'), str): kind = 'string'
    if kind is None and schema.get('enum') and all(isinstance(v, str) for v in schema['enum']): kind = 'string'
    if isinstance(kind, list): return 'Any'
    if kind == 'array': return f'List<{kt_type(schema["items"], doc)}>'
    return {'string':'String','number':'Double','integer':'Int','boolean':'Boolean','object':'JSONObject'}.get(kind, 'Any')

def objects():
    seen = set()
    def walk(schema, doc):
        if not isinstance(schema, dict): return
        if schema.get('type') == 'object' and schema.get('title') not in seen and 'title' in schema:
            seen.add(schema['title'])
            yield schema, doc
        for value in schema.values():
            if isinstance(value, dict): yield from walk(value, doc)
            elif isinstance(value, list):
                for item in value: yield from walk(item, doc)
    for doc, schema in SCHEMAS.items(): yield from walk(schema, doc)

def main():
    parts = ['// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_kotlin.py.\npackage moe.kiwi.thinkos.plugin\n\nimport org.json.JSONArray\nimport org.json.JSONObject\n']
    for schema, doc in objects():
        fields=[]
        for key, value in schema.get('properties', {}).items():
            nullable = key not in schema.get('required', [])
            fields.append(f'    val `{key}`: {kt_type(value, doc)}' + ('? = null' if nullable else ''))
        parts.append(f'data class {schema["title"]}(\n'+',\n'.join(fields)+'\n)\n')
    layouts = SCHEMAS['layouts.json']['$defs']
    slots = []
    roles = []
    for schema in layouts.values():
        properties = schema.get('properties', {})
        if 'layout' in properties and 'slots' in properties:
            name = properties['layout']['const']
            mapping = schema.get('x-thinkos-slot-roles', {})
            if set(mapping) != set(properties['slots']['properties']):
                raise ValueError(f'Layout {name}: slot roles must cover every declared slot')
            names = list(mapping)
            roles.append('        "' + name + '" to mapOf(' + ', '.join('"' + key + '" to "' + value + '"' for key, value in mapping.items()) + ')')
            slots.append('        "' + name + '" to listOf(' + ', '.join('"' + key + '"' for key in names) + ')')
    parts.append('object LayoutVocabulary {\n    val slots: Map<String, List<String>> = mapOf(\n' + ',\n'.join(slots) + '\n    )\n    val roles: Map<String, Map<String, String>> = mapOf(\n' + ',\n'.join(roles) + '\n    )\n}\n')
    entries = []
    for name, schema in SCHEMAS.items():
        encoded = json.dumps(json.dumps(schema, ensure_ascii=False, separators=(',', ':')), ensure_ascii=False).replace('$', '\\$')
        entries.append(f'        "{name}" to JSONObject({encoded})')
    template=(Path(__file__).parent/'kotlin_validator.kt.template').read_text()
    api_vocabulary = SCHEMAS['layouts.json']['x-thinkos-api']
    api_json = json.dumps(json.dumps(api_vocabulary, ensure_ascii=False, separators=(',', ':')), ensure_ascii=False)
    validate_system_settings(SYSTEM_SETTINGS)
    related_keys = ', '.join(json.dumps(key) for key, field in SYSTEM_SETTINGS['fields'].items()
                             if not field.get('hidden', False) and field.get('level') != 'hidden' and not field.get('secret', False))
    parts.append(template.replace('@@SCHEMAS@@', ',\n'.join(entries)).replace('@@API@@', str(api_vocabulary['current'])).replace('@@RENDER_APIS@@', api_json).replace('@@RELATED_KEYS@@', related_keys))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text('\n'.join(parts))
    print(OUT.relative_to(ROOT))
    from update_contracts import generate_kotlin
    generate_kotlin()
    generate_kotlin(ROOT / 'shared/schema/registry', 'RegistryModels', 'RegistryModels.kt')
if __name__ == '__main__': main()
