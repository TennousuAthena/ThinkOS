#!/usr/bin/env python3
"""Build a local before/after review from UI-00 and UI-17's actual indexes."""
import argparse
import html
import json
import os
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, default=Path('artifacts/ui/UI-00'))
    parser.add_argument('--after', type=Path, default=Path('artifacts/ui/UI-17'))
    args = parser.parse_args()
    before, after = [json.loads((p/'index.json').read_text()) for p in (args.before, args.after)]
    old = {c['file']: c for c in before['captures']}
    new = {c['file']: c for c in after['captures']}
    rows = []
    for filename in sorted(old.keys() | new.keys()):
        columns = []
        for label, entries, directory in (('改前', old, args.before), ('改后', new, args.after)):
            capture = entries.get(filename)
            if capture is None:
                columns.append('<div><h3>'+label+'</h3><p>此组合未采集该滚动位置</p></div>')
                continue
            path = directory/filename
            if not path.is_file(): raise FileNotFoundError(path)
            src = html.escape(os.path.relpath(path, args.after))
            actual = capture.get('actual_page', '')
            note = ('横幅预览；无持久副作用' if capture.get('preview') else '')
            if actual: note += ('  实际页面：'+actual)
            columns.append('<div><h3>'+label+'</h3><p>'+html.escape(note)+'</p><a href="'+src+'"><img loading="lazy" src="'+src+'" alt="'+html.escape(filename)+'"></a></div>')
        rows.append('<section data-key="'+html.escape(filename)+'"><h2>'+html.escape(filename.removesuffix('.png'))+'</h2><div class="pair">'+''.join(columns)+'</div></section>')
    report = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>ThinkOS 界面前后对比</title>
<meta name="viewport" content="width=device-width,initial-scale=1"><style>
body{font:16px system-ui;margin:0;background:#101114;color:#eee}header{position:sticky;top:0;background:#101114;padding:16px 24px;z-index:1}h1{font-size:24px;margin:0 0 12px}input{font:inherit;padding:10px;width:min(80vw,600px)}main{padding:0 24px}section{margin:24px 0 48px}h2{font-size:18px;overflow-wrap:anywhere}h3{font-size:16px}.pair{display:grid;grid-template-columns:1fr 1fr;gap:24px}img{display:block;max-width:100%;max-height:900px;width:auto;height:auto}p{min-height:24px;color:#aaa}a{color:inherit}section[hidden]{display:none}
</style><header><h1>ThinkOS 界面前后对比</h1><input id="filter" aria-label="筛选页面或语言" placeholder="筛选，例如 settings、landscape、ar"><p>原始截图；点击可查看全尺寸。夜间首页与相册按产品策略显示休眠时钟。此文件不代表所有者已确认。</p></header><main>'''+''.join(rows)+'''</main><script>
document.getElementById('filter').addEventListener('input',e=>{const q=e.target.value.toLowerCase();document.querySelectorAll('section').forEach(s=>s.hidden=!s.dataset.key.toLowerCase().includes(q));});
</script></html>'''
    output = args.after/'comparison.html'
    output.write_text(report)
    print(str(output)+': '+str(len(rows))+' indexed comparisons')


if __name__ == '__main__': main()
