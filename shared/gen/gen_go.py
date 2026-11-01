#!/usr/bin/env python3
"""Generate Go plugin contract models from the same JSON Schemas as Android."""
from pathlib import Path
from gen_kotlin import objects
from schema_tools import resolve, SCHEMAS
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'server/internal/protocol/plugin_generated.go'

def go_type(schema, doc):
    if '$ref' in schema: schema,doc=resolve(schema['$ref'],doc)
    if 'title' in schema: return schema['title']
    kind=schema.get('type')
    if kind is None and isinstance(schema.get('const'),str): kind='string'
    if kind is None and schema.get('enum') and all(isinstance(v,str) for v in schema['enum']): kind='string'
    if isinstance(kind,list): return 'any'
    if kind=='array': return '[]'+go_type(schema['items'],doc)
    return {'string':'string','number':'float64','integer':'int','boolean':'bool','object':'map[string]any'}.get(kind,'any')

def main():
    parts=['// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_go.py.\npackage protocol\n\nimport "fmt"\n']
    for schema,doc in objects():
        fields=[]
        for key,value in schema.get('properties',{}).items():
            optional=key not in schema.get('required',[])
            typ=go_type(value,doc)
            if optional and typ in ('bool','int','float64','string'): typ='*'+typ
            fields.append(f'\t{key[:1].upper()+key[1:]} {typ} `json:"{key}{",omitempty" if optional else ""}"`')
        parts.append('type '+schema['title']+' struct {\n'+'\n'.join(fields)+'\n}\n')
    vocabulary = SCHEMAS['layouts.json']['x-thinkos-api']
    parts.append('const PluginAPI = ' + str(vocabulary['current']) + '\n')
    for key in ('componentTypes', 'layouts', 'componentProperties', 'templates'):
        entries = ', '.join('"' + name + '": ' + str(api) for name, api in vocabulary[key].items())
        parts.append('var render' + key[:1].upper() + key[1:] + ' = map[string]int{' + entries + '}\n')
    parts.append('''// ValidateRenderAPI gates the fixed render vocabulary after schema validation.
// The version table is generated from layouts.json, the same source used by Android and Python.
func ValidateRenderAPI(value any, api int) error {
    if api < 1 || api > PluginAPI { return fmt.Errorf("unsupported plugin API %d", api) }
    var walk func(any) error
    walk = func(value any) error {
        switch node := value.(type) {
        case []any:
            for _, child := range node { if err := walk(child); err != nil { return err } }
        case map[string]any:
            if raw, present := node["layout"]; present {
                name, ok := raw.(string)
                minimum, known := renderLayouts[name]
                if !ok || !known || minimum > api { return fmt.Errorf("layout requires a newer plugin API") }
            }
            if slots, present := node["slots"].(map[string]any); present && api < @@SLOT_WIDGET_API@@ {
                for _, slot := range slots {
                    if reference, isObject := slot.(map[string]any); isObject {
                        if _, widget := reference["widget"]; widget { return fmt.Errorf("widget references in slots require a newer plugin API") }
                    }
                }
            }
            if raw, present := node["template"]; present {
                name, ok := raw.(string)
                minimum, known := renderTemplates[name]
                if !ok || !known || minimum > api { return fmt.Errorf("template requires a newer plugin API") }
                if _, photo := node["image"]; photo && name == "tile" && api < @@TILE_IMAGE_API@@ { return fmt.Errorf("tile image requires a newer plugin API") }
                if faces, present := node["faces"].([]any); present && name == "tile" && api < @@TILE_FACE_CONTENT_API@@ {
                    for _, face := range faces {
                        if object, isObject := face.(map[string]any); isObject {
                            if _, own := object["content"]; own { return fmt.Errorf("tile face content requires a newer plugin API") }
                        }
                    }
                }
            }
            rawKind, component := node["type"]
            if component {
                kind, ok := rawKind.(string)
                minimum, known := renderComponentTypes[kind]
                if !ok || !known || minimum > api { return fmt.Errorf("component requires a newer plugin API") }
                for key, minimum := range renderComponentProperties {
                    if _, present := node[key]; present && minimum > api { return fmt.Errorf("component property requires a newer plugin API") }
                }
            }
            _, children := node["children"]
            _, widget := node["template"]
            if children && !widget && !component && api < @@GROUP_API@@ { return fmt.Errorf("component groups require a newer plugin API") }
            for _, child := range node { if err := walk(child); err != nil { return err } }
        }
        return nil
    }
    return walk(value)
}
'''.replace('@@GROUP_API@@', str(vocabulary['componentGroups'])).replace('@@SLOT_WIDGET_API@@', str(vocabulary['slotWidgets'])).replace('@@TILE_IMAGE_API@@', str(vocabulary['tileFields']['image'])).replace('@@TILE_FACE_CONTENT_API@@', str(vocabulary['tileFaceFields']['content'])))
    OUT.parent.mkdir(parents=True,exist_ok=True)
    OUT.write_text('\n'.join(parts))
    print(OUT.relative_to(ROOT))
if __name__=='__main__': main()
