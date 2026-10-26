#!/usr/bin/env python3
"""Convierte los manuales Markdown de docs/ a PDF usando python-markdown y Chrome/Chromium en modo headless.

Uso:  python3 docs/tools/md2pdf.py [ruta/al/chrome]

Los diagramas Mermaid se renderizan cargando mermaid desde un CDN (requiere conexión a internet);
si no hay red, el diagrama aparece como texto. Requiere: pip install markdown.
"""
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile

import markdown

DOCS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
MANUALS = ['manual-usuario', 'manual-tecnico']

CSS = """
@page { size: A4; margin: 18mm 16mm; }
body { font: 10.5pt/1.5 'Segoe UI', 'Noto Sans', 'DejaVu Sans', Arial, sans-serif; color: #1b2433; max-width: 100%; }
h1 { font-size: 22pt; border-bottom: 3px solid #2557d6; padding-bottom: 6px; margin-top: 0; }
h2 { font-size: 15pt; margin-top: 22px; border-bottom: 1px solid #d5dbe6; padding-bottom: 3px; page-break-after: avoid; }
h3 { font-size: 12pt; margin-top: 16px; page-break-after: avoid; }
table { border-collapse: collapse; width: 100%; margin: 10px 0; font-size: 9.2pt; page-break-inside: auto; }
tr { page-break-inside: avoid; }
th, td { border: 1px solid #cfd6e3; padding: 4px 7px; vertical-align: top; }
th { background: #eef2fa; text-align: left; }
code { font: 9pt 'DejaVu Sans Mono', Consolas, monospace; background: #f1f3f8; padding: 1px 4px; border-radius: 3px; }
pre { background: #f4f6fa; border: 1px solid #dde3ee; border-radius: 6px; padding: 8px 10px; white-space: pre-wrap; word-break: break-word; page-break-inside: avoid; }
pre code { background: none; padding: 0; font-size: 8.6pt; }
blockquote { margin: 10px 0; padding: 6px 12px; border-left: 4px solid #2557d6; background: #f4f7ff; }
hr { border: 0; border-top: 1px solid #d5dbe6; margin: 18px 0; }
.mermaid { text-align: center; margin: 12px 0; page-break-inside: avoid; }
a { color: #2557d6; text-decoration: none; }
"""

TEMPLATE = """<!DOCTYPE html>
<html lang="es"><head><meta charset="utf-8"><title>{title}</title><style>{css}</style></head>
<body>{body}
<script type="module">
  import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.esm.min.mjs';
  mermaid.initialize({{ startOnLoad: false, theme: 'neutral' }});
  await mermaid.run({{ querySelector: '.mermaid' }});
  document.body.dataset.ready = '1';
</script></body></html>"""


def find_chrome():
    if len(sys.argv) > 1:
        return sys.argv[1]
    for name in ('chromium', 'google-chrome', 'google-chrome-stable', 'chromium-browser'):
        path = shutil.which(name)
        if path:
            return path
    cache = os.path.expanduser('~/.cache/ms-playwright')
    if os.path.isdir(cache):
        for root, _, files in os.walk(cache):
            if 'chrome' in files and 'chrome-linux' in root:
                return os.path.join(root, 'chrome')
    sys.exit('No se encontró Chrome/Chromium; indique la ruta como argumento.')


def render(md_path, pdf_path, chrome, workdir):
    with open(md_path, encoding='utf-8') as f:
        text = f.read()
    body = markdown.markdown(text, extensions=['tables', 'fenced_code', 'sane_lists'])
    # ```mermaid -> <div class="mermaid">
    body = re.sub(r'<pre><code class="language-mermaid">(.*?)</code></pre>',
                  lambda m: '<div class="mermaid">' + html.unescape(m.group(1)) + '</div>', body, flags=re.S)
    title = os.path.basename(md_path)
    html_path = os.path.join(workdir, os.path.basename(md_path) + '.html')
    with open(html_path, 'w', encoding='utf-8') as f:
        f.write(TEMPLATE.format(title=title, css=CSS, body=body))
    subprocess.run([chrome, '--headless=new', '--no-sandbox', '--disable-gpu', '--no-pdf-header-footer',
                    '--virtual-time-budget=20000', f'--print-to-pdf={pdf_path}', 'file://' + html_path],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)


def main():
    chrome = find_chrome()
    with tempfile.TemporaryDirectory() as workdir:
        for name in MANUALS:
            md = os.path.join(DOCS, name + '.md')
            pdf = os.path.join(DOCS, name + '.pdf')
            render(md, pdf, chrome, workdir)
            print('generado', os.path.normpath(pdf), f'({os.path.getsize(pdf) // 1024} KB)')


if __name__ == '__main__':
    main()
