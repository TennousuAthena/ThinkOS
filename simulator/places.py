"""OS place picker search, matching the MIT native location/PlaceSearch.kt."""
import json
import math
from urllib.parse import quote_plus

from .contracts import loads
from .network import checked_url, NetworkError


def limit_utf16(text, count=160):
    return text.encode("utf-16-le", errors="replace")[:count * 2].decode("utf-16-le", errors="ignore")


def clean(value):
    if value is None:
        return ""
    if not isinstance(value, str):
        value = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return limit_utf16("".join(c for c in value if ord(c) >= 32).strip())


def query_variants(text):
    if not text or not all("\u4e00" <= c <= "\u9fff" for c in text) or text[-1] in "市县区旗州盟乡镇省":
        return [text]
    return [text] + [text + ending for ending in "市县区"]


def parse(body):
    root = loads(body)
    if not isinstance(root, dict):
        raise ValueError("place search requires an object")
    if root.get("error") is True or str(root.get("error", "")).lower() == "true":
        raise ValueError("place search rejected the request")
    rows = root.get("results")
    if not isinstance(rows, list):
        return []
    result = []
    for row in rows[:20]:
        if not isinstance(row, dict):
            continue
        def number(key, default=None):
            value = row.get(key, default)
            try:
                return float(value) if value is not None and not isinstance(value, bool) else (default if default is not None else math.nan)
            except (ValueError, TypeError, OverflowError):
                return default if default is not None else math.nan
        latitude, longitude, elevation = number("latitude"), number("longitude"), number("elevation", 0)
        name, zone = clean(row.get("name")), clean(row.get("timezone"))
        if not name or not zone or not -90 <= latitude <= 90 or not -180 <= longitude <= 180 or not -500 <= elevation <= 10000:
            continue
        detail = []
        for key in ("admin1", "admin2", "admin3", "country"):
            part = clean(row.get(key))
            if part and part != name and part not in detail:
                detail.append(part)
        result.append({"location": {"name": name, "latitude": latitude, "longitude": longitude, "elevation": elevation, "timezone": zone, "source": "manual"}, "detail": ", ".join(detail)})
    return result


def search(network, endpoint, query, locale):
    text = limit_utf16(query.strip())
    if len(text.encode("utf-16-le")) < 4:
        return []
    if "#" in endpoint:
        raise ValueError("search address cannot contain a fragment")
    checked_url(endpoint)
    language = locale.split("-", 1)[0].lower() or "en"
    separator = "&" if "?" in endpoint else "?"
    found = {}
    for index, variant in enumerate(query_variants(text)):
        try:
            url = endpoint + separator + "name=" + quote_plus(variant) + "&count=20&language=" + quote_plus(language) + "&format=json"
            response = network.http({"pluginId": "@os-place-search", "method": "GET", "url": url})
            if response["status"] != 200:
                raise NetworkError("place_http", "place search returned HTTP " + str(response["status"]))
            rows = parse(response["body"])
        except Exception:
            if index == 0:
                raise
            continue
        for row in rows:
            location = row["location"]
            found.setdefault((location["name"], location["latitude"], location["longitude"]), row)
        if len(found) >= 20:
            break
    return list(found.values())[:20]
