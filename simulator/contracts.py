"""Small fail-closed validator for the generated development contract."""
import json
import math
import re
from .generated.protocol import SCHEMA


def validate(value, schema, path="$", depth=0):
    if depth > 32:
        raise ValueError("maximum JSON nesting exceeded")
    if "$ref" in schema:
        schema = SCHEMA["$defs"][schema["$ref"].rsplit("/", 1)[-1]]
    kind = schema.get("type")
    matches = {"object": lambda: isinstance(value, dict), "array": lambda: isinstance(value, list),
               "string": lambda: isinstance(value, str), "boolean": lambda: isinstance(value, bool),
               "integer": lambda: isinstance(value, int) and not isinstance(value, bool),
               "number": lambda: isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value), "null": lambda: value is None}
    if kind and not any(matches[item]() for item in (kind if isinstance(kind, list) else [kind])):
        raise ValueError(path + ": unexpected value type")
    if "const" in schema and (type(value) is not type(schema["const"]) or value != schema["const"]):
        raise ValueError(path + ": invalid constant")
    if "enum" in schema and value not in schema["enum"]:
        raise ValueError(path + ": unsupported value")
    if isinstance(value, dict):
        if len(value) > schema.get("maxProperties", 4096) or len(value) < schema.get("minProperties", 0):
            raise ValueError(path + ": too many properties")
        for key in schema.get("required", []):
            if key not in value:
                raise ValueError(path + ": missing " + key)
        for key, child in value.items():
            item = schema.get("properties", {}).get(key, schema.get("additionalProperties", {}))
            if item is False:
                raise ValueError(path + ": unknown property " + key)
            validate(child, item if isinstance(item, dict) else {}, path + "." + key, depth + 1)
    elif isinstance(value, list):
        if len(value) > schema.get("maxItems", 4096) or len(value) < schema.get("minItems", 0):
            raise ValueError(path + ": too many items")
        for i, child in enumerate(value):
            validate(child, schema.get("items", {}), path + "[" + str(i) + "]", depth + 1)
        if schema.get("uniqueItems"):
            encoded = [json.dumps(item, sort_keys=True, ensure_ascii=True, allow_nan=False, separators=(",", ":")) for item in value]
            if len(set(encoded)) != len(encoded):
                raise ValueError(path + ": duplicate array item")
    elif isinstance(value, str):
        if not schema.get("minLength", 0) <= len(value) <= schema.get("maxLength", 1048576) or ("pattern" in schema and re.fullmatch(schema["pattern"], value) is None):
            raise ValueError(path + ": invalid string")
    elif isinstance(value, (int, float)) and not isinstance(value, bool):
        if not math.isfinite(value) or value < schema.get("minimum", -math.inf) or value > schema.get("maximum", math.inf):
            raise ValueError(path + ": number outside range")


def request(name, value):
    validate(value, SCHEMA["$defs"][name])
    return value


def loads(data):
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise ValueError("duplicate JSON property")
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("non-finite JSON")))
