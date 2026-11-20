#!/usr/bin/env python3
"""Generate local-development types from the single shared schema. No Android writes."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "shared/protocol/schema/simulator/development.json"
OUT = ROOT / "simulator/generated"


def ts(schema):
    if "$ref" in schema:
        return schema["$ref"].rsplit("/", 1)[-1]
    if "const" in schema:
        return json.dumps(schema["const"])
    if "enum" in schema:
        return " | ".join(json.dumps(x) for x in schema["enum"])
    kind = schema.get("type")
    if isinstance(kind, list):
        return " | ".join(ts({**schema, "type": item}) for item in kind)
    if kind == "object":
        props = schema.get("properties", {})
        if not props:
            child = schema.get("additionalProperties", {})
            return "Record<string, " + (ts(child) if isinstance(child, dict) else "unknown") + ">"
        required = schema.get("required", [])
        return "{ " + "; ".join(json.dumps(k) + ("" if k in required else "?") + ": " + ts(v) for k, v in props.items()) + " }"
    if kind == "array":
        return "Array<" + ts(schema.get("items", {})) + ">"
    return {"string": "string", "integer": "number", "number": "number", "boolean": "boolean", "null": "null"}.get(kind, "unknown")


def expanded(value, filename, depth=0):
    if depth > 64:
        raise ValueError("recursive development schema")
    if isinstance(value, list):
        return [expanded(item, filename, depth + 1) for item in value]
    if not isinstance(value, dict):
        return value
    if "$ref" in value:
        relative, _, pointer = value["$ref"].partition("#")
        target = (filename.parent / relative).resolve() if relative else filename
        target.relative_to(ROOT / "shared")
        node = json.loads(target.read_text())
        for part in pointer.split("/")[1:]:
            node = node[part.replace("~1", "/").replace("~0", "~")]
        return expanded(node, target, depth + 1)
    return {key: expanded(item, filename, depth + 1) for key, item in value.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    source = expanded(json.loads(SOURCE.read_text()), SOURCE)
    banner = "GENERATED - DO NOT EDIT; shared/protocol/schema/simulator/development.json"
    outputs = {"protocol.py": "# " + banner + "\nimport json\nSCHEMA = json.loads(" + repr(json.dumps(source, ensure_ascii=False, separators=(",", ":"))) + ")\n",
               "protocol.ts": "// " + banner + "\n" + "\n".join("export type " + name + " = " + ts(value) + ";" for name, value in source["$defs"].items()) + "\n"}
    for name, content in outputs.items():
        path = OUT / name
        if args.check:
            if not path.is_file() or path.read_text() != content:
                raise SystemExit("Stale simulator protocol: " + str(path))
        else:
            OUT.mkdir(parents=True, exist_ok=True)
            path.write_text(content)


if __name__ == "__main__":
    main()
