#!/usr/bin/env python3
"""Draw the music player's arrangement from the JSON that NowPlayingPreviewTest writes.

    THINKOS_LAYOUT_DUMP=/tmp/layouts.json ./gradlew --offline testDebugUnitTest --tests '*NowPlayingPreviewTest'
    python3 -I tools/layout_preview.py /tmp/layouts.json /tmp/layouts.html

Only the arrangement: the parts are drawn as labelled boxes at 1 dp = 1 px, with the dark page colour behind them. It says
nothing about fonts, the cover's picture or how anything looks in motion. Open the HTML in a browser, or render it with a
headless browser to a PNG.
"""
import html
import json
import sys

DARK = {"bg": "#0e0f12", "surface": "#1b1d22", "text": "#f2f3f5", "muted": "#8d929b", "accent": "#7c95ff"}
GLYPHS = {"FULL": ["⇄", "⏮", "⏸", "⏭", "⟲"], "TRANSPORT": ["⏮", "⏸", "⏭"], "PLAY": ["⏸"]}
LABELS = {"title": "title", "artist": "artist", "format": "format", "notice": "notice", "idle": "idle text", "seek": "position bar",
          "line": "progress line", "volume": "volume", "queue": "queue list"}


def draw(preset):
    w, h = preset["w"], preset["h"]
    parts, out = preset["parts"], []
    out.append(f'<rect width="{w}" height="{h}" fill="{DARK["bg"]}" stroke="#333" stroke-dasharray="4 4"/>')
    for name, (l, t, r, b) in parts.items():
        width, height = r - l, b - t
        if name == "cover":
            out.append(f'<rect x="{l}" y="{t}" width="{width}" height="{height}" rx="{max(8, min(width, height) * .053):.0f}" fill="{preset["coverFill"]}"/>')
            out.append(f'<text x="{l + width / 2}" y="{t + height / 2}" fill="#fff" fill-opacity=".5" font-size="{min(width, height) * .5:.0f}" text-anchor="middle" dominant-baseline="central">S</text>')
        elif name == "controls":
            continue
        elif name == "queue":
            out.append(f'<rect x="{l}" y="{t}" width="{width}" height="{height}" fill="none" stroke="{DARK["muted"]}" stroke-dasharray="3 3"/>')
            row, y, i = 64, t, 0
            while y + row <= b:
                mark = i == 1
                if mark:
                    out.append(f'<rect x="{l}" y="{y}" width="{width}" height="{row}" rx="14" fill="{DARK["surface"]}"/>')
                out.append(f'<rect x="{l + 56}" y="{y + 16}" width="{min(width - 120, 190 - 20 * (i % 3))}" height="12" rx="3" fill="{DARK["accent"] if mark else DARK["text"]}" fill-opacity="{1 if mark else .8}"/>')
                out.append(f'<rect x="{l + 56}" y="{y + 38}" width="{min(width - 150, 110)}" height="9" rx="3" fill="{DARK["muted"]}"/>')
                out.append(f'<text x="{l + 28}" y="{y + 32}" fill="{DARK["muted"]}" font-size="14" text-anchor="middle" dominant-baseline="central">{i + 1}</text>')
                y += row
                i += 1
        elif name == "seek":
            cy = t + height / 2
            out.append(f'<line x1="{l + 4}" y1="{cy}" x2="{r - 4}" y2="{cy}" stroke="#444" stroke-width="4" stroke-linecap="round"/>')
            out.append(f'<line x1="{l + 4}" y1="{cy}" x2="{l + 4 + (width - 8) * .3}" y2="{cy}" stroke="{DARK["accent"]}" stroke-width="4" stroke-linecap="round"/>')
            out.append(f'<text x="{l + 4}" y="{cy + 18}" fill="{DARK["muted"]}" font-size="12">0:30</text>')
            out.append(f'<text x="{r - 4}" y="{cy + 18}" fill="{DARK["muted"]}" font-size="12" text-anchor="end">-1:34</text>')
        elif name == "line":
            cy = t + height / 2
            th = min(height, 4)
            out.append(f'<line x1="{l}" y1="{cy}" x2="{r}" y2="{cy}" stroke="#444" stroke-width="{th}"/>')
            out.append(f'<line x1="{l}" y1="{cy}" x2="{l + width * .4}" y2="{cy}" stroke="{DARK["accent"]}" stroke-width="{th}"/>')
        elif name == "volume":
            cy = t + height / 2
            out.append(f'<text x="{l + 10}" y="{cy}" fill="{DARK["muted"]}" font-size="16" dominant-baseline="central">🔈</text>')
            out.append(f'<line x1="{l + 40}" y1="{cy}" x2="{r - 8}" y2="{cy}" stroke="#444" stroke-width="4" stroke-linecap="round"/>')
            out.append(f'<line x1="{l + 40}" y1="{cy}" x2="{l + 40 + (width - 48) * .32}" y2="{cy}" stroke="{DARK["accent"]}" stroke-width="4" stroke-linecap="round"/>')
        else:
            size = {"title": 28, "artist": 18, "format": 13, "notice": 14, "idle": 24}.get(name, 14)
            sample = {"title": "Song title that may run long", "artist": "Artist", "format": "FLAC · 24-bit · 48 kHz", "notice": "Cannot start playback",
                      "idle": "Nothing playing"}.get(name, name)
            colour = {"title": DARK["text"], "artist": DARK["muted"], "format": DARK["muted"], "notice": "#ff7a6b", "idle": DARK["text"]}.get(name, DARK["text"])
            out.append(f'<rect x="{l}" y="{t}" width="{width}" height="{height}" fill="none" stroke="#2f3a66" stroke-width="1"/>')
            anchor, x = ("end", r) if preset["rtl"] else ("start", l)
            out.append(f'<text x="{x}" y="{t + height / 2}" fill="{colour}" font-size="{min(size, height * .8):.0f}" text-anchor="{anchor}" dominant-baseline="central">{html.escape(sample)}</text>')
    for i, (l, t, r, b) in enumerate(preset["buttons"]):
        cx, cy, size = (l + r) / 2, (t + b) / 2, r - l
        big = size >= 56
        out.append(f'<circle cx="{cx}" cy="{cy}" r="{size / 2}" fill="{DARK["accent"] if big else "none"}" stroke="{"none" if big else "#555"}" stroke-dasharray="2 3"/>')
        glyph = GLYPHS.get(preset["buttonSet"], [])
        out.append(f'<text x="{cx}" y="{cy}" fill="{"#0e0f12" if big else DARK["text"]}" font-size="{size * .38:.0f}" text-anchor="middle" dominant-baseline="central">{glyph[i] if i < len(glyph) else "•"}</text>')
    return f'<svg width="{w}" height="{h}" viewBox="0 0 {w} {h}" xmlns="http://www.w3.org/2000/svg" font-family="Roboto, Helvetica, Arial, sans-serif">{"".join(out)}</svg>'


def main(source, target):
    with open(source, encoding="utf-8") as f:
        presets = json.load(f)
    cells = "".join(f'<figure><figcaption>{html.escape(p["name"])} · {p["w"]}×{p["h"]} dp · buttons {p["buttonSet"]}</figcaption>{draw(p)}</figure>' for p in presets)
    page = ("<!doctype html><meta charset=utf-8><title>Music player arrangement</title>"
            "<style>body{background:#25272c;color:#d8dae0;font:13px sans-serif;margin:24px}figure{display:inline-block;margin:0 28px 28px 0;vertical-align:top}"
            "figcaption{margin-bottom:6px;color:#aeb3bc}</style>" + cells)
    with open(target, "w", encoding="utf-8") as f:
        f.write(page)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
