"""Render the saved unresolved literature view as a standalone review example."""
from pathlib import Path
import json
import sys

ROOT = Path('D:/LSD/Project/Code')
SOURCE = ROOT / 'local/development/handoffs/literature-readable-20261105/delivery/literature-core-fields.view.json'
OUT = ROOT / 'local/development/handoffs/literature-readable-20261105/unresolved-example'
sys.path.insert(0, str(ROOT / 'scripts/denovo'))
from export_literature_readable import render_html


def main():
    report = json.loads(SOURCE.read_text(encoding='utf-8'))
    report['title'] = '资料核查进度'
    report['gapsHeading'] = '尚未完成的判定条件'
    report['documents'] = [dict(document, title=str(document.get('gene', '')) + ' 文献资料',
                                purpose='', limits=[])
                          for document in report.get('documents', [])]
    from build_literature_source_screenshots import build as build_source_screenshots
    screenshot_receipt = build_source_screenshots(report,
        ROOT / 'local/knowledge/literature/public-papers',
        ROOT / 'local/knowledge/literature/public-papers/source-screenshots',
        Path('C:/Program Files/Google/Chrome/Application/chrome.exe'),
        ROOT / 'local/tools/node/node-v22.21.1-win-x64/node.exe')
    OUT.mkdir(parents=True, exist_ok=True)
    template = (ROOT / 'scripts/denovo/literature_readable_template.html').read_text(encoding='utf-8')
    (OUT / 'literature-unresolved-example.html').write_text(render_html(report, template), encoding='utf-8')
    (OUT / 'literature-unresolved-example.view.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'output': str(OUT / 'literature-unresolved-example.html'),
                      'documents': len(report.get('documents', [])),
                      'source': str(SOURCE), 'screenshots': screenshot_receipt}))


if __name__ == '__main__':
    main()
