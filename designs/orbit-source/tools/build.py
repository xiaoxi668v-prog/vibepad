"""Build standalone, offline previews using Python 3 standard library only."""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
fragment=(ROOT/'src/selected-fragment.html').read_text()
css=re.search(r'<style>(.*?)</style>',fragment,re.S).group(1)
js=re.search(r'<script>(.*?)</script>',fragment,re.S).group(1)
markup=fragment.split('<style>')[0].strip()
icons=(ROOT/'src/icons.js').read_text()
(ROOT/'src/orbit-ui.css').write_text(css)
(ROOT/'src/orbit-ui.js').write_text(js)
(ROOT/'src/markup.html').write_text(markup)

def build(name,title,variant=None):
    body=markup
    if variant:
        body=body.replace('data-initial-design="graphite"',f'data-initial-design="{variant}" data-view="screen"')
    bg='#151619' if variant=='graphite' else '#eae7e1' if variant=='titanium' else '#fff'
    wrapper='max-width:1280px;margin:0 auto;' if variant else 'max-width:1328px;margin:0 auto;padding:24px;'
    document=f'''<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="color-scheme" content="light">
<title>{title}</title>
<style>html,body{{margin:0;padding:0;background:{bg}}}body{{{wrapper}}}button,input,textarea{{font:inherit}}svg{{display:block}}{css}</style>
</head>
<body>
{body}
<script>{icons}</script>
<script>{js}</script>
</body>
</html>'''
    (ROOT/name).write_text(document)
    print(name,len(document.encode()),'bytes')

build('index.html','Orbit · 02 / 05 · UI 交付预览')
build('02-graphite-pro.html','02 · 深空专业 · UI 定稿','graphite')
build('05-titanium-duo.html','05 · 双手操控 · UI 定稿','titanium')
