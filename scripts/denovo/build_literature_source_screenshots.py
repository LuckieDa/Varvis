"""Make reusable screenshots of local JATS source regions, without inference.

The screenshots show a local full-text view, not publisher PDF pagination.
No model, scientific evaluator, source rewrite or network acquisition is used.
"""
from __future__ import annotations

import argparse
from collections import defaultdict
import hashlib
import html
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from literature_attachments import MAX_PACKAGE
from literature_docx import read_docx


STYLE = """* { box-sizing: border-box; }
body { margin: 0; background: white; color: #222; font: 18px/1.65 Georgia, 'Times New Roman', serif; }
main { width: 1080px; margin: 0 auto; padding: 28px; }
.source-header { font: 14px/1.6 Arial, sans-serif; color: #657184; border-bottom: 1px solid #ddd; padding-bottom: 16px; margin-bottom: 24px; }
h1 { font-size: 27px; line-height: 1.35; } h2 { font-size: 23px; line-height: 1.4; } h3 { font-size: 20px; }
p, .source-paragraph { margin: 16px 0; } a { color: #245b98; } [id] { scroll-margin-top: 24px; }
.source-supplement p, .source-supplement td, .source-supplement th { white-space: pre-wrap; }
figure { margin: 22px 0; padding: 12px 0; } figure img, .graphic { display: block; max-width: 100%; height: auto; margin: 14px auto; }
.article-table { margin: 22px 0; padding: 12px 0; font: 15px/1.5 Arial, sans-serif; }
.article-table > .source-label { display: block; font-weight: bold; margin-bottom: 8px; }
.caption { margin: 8px 0 14px; } .caption p { margin: 6px 0; }
table { width: 100%; border-collapse: collapse; table-layout: auto; margin: 14px 0; }
td, th { padding: 7px 9px; border: 1px solid #bfc6cd; vertical-align: top; overflow-wrap: anywhere; }
thead { background: #f2f4f6; font-weight: bold; } .article-table .footnotes { font-size: 13px; }
.source-label { margin-right: 8px; } .references { font-size: 15px; }
.unavailable-image { font: 14px Arial, sans-serif; color: #777; }
::highlight(source-reference) { background: #ffe89a; color: inherit; text-decoration: underline 1px solid #ba9129; text-underline-offset: 2px; }
.source-focus-cell { background: #ffe89a !important; }
"""
DOCX_LOCATOR = re.compile(r'attachment:([a-f0-9]{64}):word/document\.xml:/document/body/(?:p\[[1-9]\d*\]|tbl\[[1-9]\d*\]/tr\[[1-9]\d*\]/tc\[[1-9]\d*\])\Z')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def local_tag(element):
    return element.tag.rsplit('}', 1)[-1] if isinstance(element.tag, str) else ''


def index_elements(root):
    by_path, paths = {}, {}

    def visit(element, path):
        by_path[path] = element
        paths[element] = path
        counts = defaultdict(int)
        for child in element:
            tag = local_tag(child)
            if not tag:
                continue
            counts[tag] += 1
            visit(child, f'{path}/{tag}[{counts[tag]}]')

    visit(root, '/' + local_tag(root) + '[1]')
    return by_path, paths


def anchor(path):
    return 'source-' + digest(path.encode())[:20]


def element_text(element):
    return ' '.join(''.join(element.itertext()).split())


def render_full_text(root, paths, media, pmcid, supplement=''):
    id_paths = {element.get('id'): path for element, path in paths.items() if element.get('id')}

    def render(element, depth=0):
        tag = local_tag(element)
        ident = ' id="' + anchor(paths[element]) + '"'
        if tag == 'alternatives':
            children = list(element)
            selected = next((child for child in children if child.get('content-type') == 'image'),
                            children[0] if children else None)
            return render(selected, depth) if selected is not None else ''
        if tag in {'graphic', 'inline-graphic'}:
            filename = element.get('{http://www.w3.org/1999/xlink}href', '')
            image = media.get(filename)
            if image:
                return '<img' + ident + ' class="graphic" src="' + html.escape(image.as_uri(), quote=True) + '" alt="' + html.escape(filename, quote=True) + '">'
            return '<p' + ident + ' class="unavailable-image">图片尚未保存在本地：' + html.escape(filename) + '</p>'
        content = html.escape(element.text or '')
        content += ''.join(render(child, depth + (1 if tag == 'sec' else 0)) + html.escape(child.tail or '')
                           for child in element if local_tag(child))
        if tag == 'xref':
            target = id_paths.get(element.get('rid', ''))
            return '<a' + ident + ' href="#' + anchor(target) + '">' + content + '</a>' if target else '<span' + ident + '>' + content + '</span>'
        if tag == 'ext-link':
            target = element.get('{http://www.w3.org/1999/xlink}href', '')
            if target.startswith(('https://', 'http://')):
                return '<a' + ident + ' href="' + html.escape(target, quote=True) + '" rel="noopener noreferrer">' + content + '</a>'
        if tag in {'td', 'th'}:
            for attr in ('rowspan', 'colspan'):
                value = element.get(attr, '')
                if value.isdigit() and 0 < int(value) <= 100:
                    ident += ' ' + attr + '="' + value + '"'
        mapping = {'sec': 'section', 'p': 'p', 'fig': 'figure', 'table-wrap': 'section',
                   'table': 'table', 'thead': 'thead', 'tbody': 'tbody', 'tfoot': 'tfoot',
                   'tr': 'tr', 'td': 'td', 'th': 'th', 'caption': 'div', 'label': 'span',
                   'italic': 'i', 'bold': 'b', 'sup': 'sup', 'sub': 'sub', 'break': 'br',
                   'list': 'ul', 'list-item': 'li', 'ref-list': 'section', 'ref': 'p',
                   'abstract': 'section', 'body': 'section', 'back': 'section', 'floats-group': 'section',
                   'table-wrap-foot': 'div', 'fn': 'p', 'title': 'h2' if depth < 2 else 'h3'}
        output_tag = mapping.get(tag, 'span')
        classes = {'table-wrap': 'article-table', 'caption': 'caption', 'label': 'source-label',
                   'table-wrap-foot': 'footnotes', 'ref-list': 'references'}
        if output_tag == 'p' and any(child is not element and local_tag(child) in {'p','list','fig','table-wrap','sec','graphic'}
                                     for child in element.iter()):
            # JATS permits block content inside a paragraph. An HTML <p> auto-closes before it.
            output_tag = 'div'
            ident += ' class="source-paragraph"'
        elif tag in classes:
            ident += ' class="' + classes[tag] + '"'
        if output_tag == 'br':
            return '<br' + ident + '>'
        return '<' + output_tag + ident + '>' + content + '</' + output_tag + '>'

    title_element = root.find('.//article-title')
    title = element_text(title_element) if title_element is not None else pmcid
    sections = []
    article_meta = root.find('./front/article-meta')
    if article_meta is not None:
        sections.extend(render(part) for part in article_meta.findall('abstract'))
    sections.extend(render(part) for part in root if local_tag(part) in {'body', 'back', 'floats-group'})
    return ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
            '<meta name="viewport" content="width=device-width, initial-scale=1">'
            '<meta http-equiv="Content-Security-Policy" content="default-src \'none\'; img-src file:; style-src \'unsafe-inline\'">'
            '<title>' + html.escape(title) + '</title><style>' + STYLE + '</style></head><body><main>'
            '<header class="source-header">本地全文视图 · ' + html.escape(pmcid) +
            ' · <a href="https://pmc.ncbi.nlm.nih.gov/articles/' + pmcid + '/" target="_blank" rel="noopener noreferrer">查看出版来源</a></header>'
            '<h1>' + html.escape(title) + '</h1>' + ''.join(sections) + supplement + '</main></body></html>')


def docx_sources(paper, pins):
    """Render source-bound original OOXML text and table cells, preserving their structural positions."""
    html_parts, units, available = [], {}, []
    for pin in pins:
        directory = paper / 'supplementary'
        source = directory / (pin + '.docx')
        try:
            if not directory.resolve().is_relative_to(paper.resolve()) or not source.resolve().is_relative_to(directory.resolve()):
                continue
            if source.stat().st_size > MAX_PACKAGE:
                continue
            with source.open('rb') as stream:
                raw = stream.read(MAX_PACKAGE + 1)
            if len(raw) > MAX_PACKAGE or digest(raw) != pin:
                continue
            parsed = read_docx(raw)
        except (OSError, ValueError):
            continue
        blocks = {}
        for unit in parsed['units']:
            locator = f"attachment:{pin}:{unit['location']}"
            if DOCX_LOCATOR.fullmatch(locator):
                blocks.setdefault(unit.get('sheet', unit['location']), []).append((locator, unit))
        rendered = []
        for block, entries in blocks.items():
            if entries[0][1]['kind'] == 'SUPPLEMENT_PARAGRAPH':
                locator, unit = entries[0]
                rendered.append(f'<p id="{anchor(locator)}">{html.escape(unit["text"])}</p>')
                units[locator] = (locator, 'p', unit['text'])
                continue
            if any(unit['verticalMerge'] is not None for _, unit in entries):
                continue
            region = f'attachment:{pin}:{block}'
            rows = defaultdict(list)
            columns = defaultdict(lambda: 1)
            if not all(re.fullmatch(r'R[1-9]\d*C[1-9]\d*', unit['cell']) for _, unit in entries):
                continue
            table_units = {}
            for locator, unit in entries:
                row, column = unit['cell'].split('C', 1)
                if int(column) != columns[row]:
                    break
                columns[row] += unit['gridSpan']
                tag = 'th' if unit['header'] else 'td'
                rows[row].append(
                    f'<{tag} id="{anchor(locator)}" colspan="{unit["gridSpan"]}">{html.escape(unit["text"])}</{tag}>')
                table_units[locator] = (region, tag, unit['text'])
            else:
                units.update(table_units)
                table = ''.join('<tr>' + ''.join(cells) + '</tr>' for cells in rows.values())
                rendered.append(f'<section class="article-table" id="{anchor(region)}"><table>{table}</table></section>')
        if rendered:
            available.append(pin)
            html_parts.append('<section class="source-supplement"><h2>补充材料 · DOCX 原件内容</h2>' + ''.join(rendered) + '</section>')
    return ''.join(html_parts), units, available


def registered_media(paper, pmcid, xml_sha, root, media_root, names):
    """Reuse only original images bound to this version of the article and an exact graphic filename."""
    image_name = re.compile(r'[A-Za-z0-9_-][A-Za-z0-9_.-]*\.(?:png|jpg|jpeg|gif|webp)\Z')
    needed = {name for name in names if name and '..' not in name and image_name.fullmatch(name)
              and not (media_root / name).is_file()}
    if not needed:
        return
    figures = {fig.get('id'): {graphic.get('{http://www.w3.org/1999/xlink}href') for graphic in fig.iter()
                              if local_tag(graphic) in {'graphic', 'inline-graphic'}}
               for fig in root.iter() if local_tag(fig) == 'fig' and fig.get('id')}

    def read_record(path):
        try:
            if not path.resolve().is_relative_to(paper.resolve()) or path.stat().st_size > 512 * 1024:
                return {}
            record = json.loads(path.read_bytes())
            return record if isinstance(record, dict) else {}
        except (OSError, ValueError):
            return {}

    def retain(name, pin, source):
        if name not in needed or not re.fullmatch(r'[a-f0-9]{64}', str(pin)):
            return
        try:
            if not source.resolve().is_relative_to(paper.resolve()) or source.stat().st_size > 24 * 1024 * 1024:
                return
            raw = source.read_bytes()
            if digest(raw) != pin:
                return
            media_root.mkdir(parents=True, exist_ok=True)
            if not media_root.resolve().is_relative_to(media_root.parent.parent.resolve()):
                return
            with tempfile.NamedTemporaryFile(dir=media_root, suffix='.tmp', delete=False) as stream:
                staged = Path(stream.name)
                stream.write(raw)
            try:
                if not (media_root / name).exists():
                    staged.replace(media_root / name)
            finally:
                staged.unlink(missing_ok=True)
            needed.discard(name)
        except OSError:
            return

    for path in sorted((paper / 'pedigree-figures').glob('*/source.json'))[:32]:
        record = read_record(path)
        name = record.get('graphicHref')
        figure_id = record.get('figureId')
        if (record.get('schemaVersion') == 'pp1-figure-source-v1' and record.get('pmcid') == pmcid
                and record.get('mainSha256') == xml_sha and isinstance(name, str) and isinstance(figure_id, str)
                and name in figures.get(figure_id, ())
                and record.get('imageSha256') == path.parent.name and 'viewBox' not in record):
            retain(name, path.parent.name, path.parent / 'original.bin')
    for path in sorted((paper / 'source-bundles').glob('*.json'))[:32] if needed else []:
        record = read_record(path)
        main, attachments = record.get('main'), record.get('attachments')
        if record.get('pmcid') != pmcid or not isinstance(main, dict) or main.get('sha256') != xml_sha or not isinstance(attachments, list):
            continue
        for asset in attachments:
            if not isinstance(asset, dict):
                continue
            name, pin = asset.get('originalName'), asset.get('sha256')
            if isinstance(name, str) and name in needed and asset.get('path') == f'supplementary/{pin}{Path(name).suffix.lower()}':
                retain(name, pin, paper / asset['path'])


def region_for(path, by_path):
    if path not in by_path:
        raise ValueError('原文定位不存在：' + path)
    for kind in ('table-wrap', 'fig'):
        match = re.search(r'/' + kind + r'\[\d+\](?:/|$)', path)
        if match:
            return path[:match.end()].rstrip('/')
    return path


def plan_document(document, library, output):
    pmcid = document['pmcid']
    if not re.fullmatch(r'PMC\d+', pmcid):
        raise ValueError('只接收明确的PMC原文身份。')
    xml_path = library / 'papers' / pmcid / (pmcid + '.xml')
    raw = xml_path.read_bytes()
    if len(raw) > 20 * 1024 * 1024:
        raise ValueError('原文过大。')
    root = ET.fromstring(raw)
    by_path, paths = index_elements(root)
    refs = [ref for case in document.get('cases', []) for row in case.get('rows', [])
            for ref in row.get('sourceRefs', []) if isinstance(ref, dict) and ref.get('unitId')]
    pins = sorted({match[1] for ref in refs if (match := DOCX_LOCATOR.fullmatch(ref['unitId']))})
    supplement, supplement_units, available = docx_sources(xml_path.parent, pins)
    names = {element.get('{http://www.w3.org/1999/xlink}href') for element in root.iter()
             if local_tag(element) in {'graphic', 'inline-graphic'}}
    media_root = output / 'source-media' / pmcid
    registered_media(xml_path.parent, pmcid, digest(raw), root, media_root, names)
    media = {name: media_root / name for name in names
             if name and Path(name).name == name and (media_root / name).is_file()}
    media_hashes = {name: digest(path.read_bytes()) for name, path in sorted(media.items())}
    worker = Path(__file__).with_name('capture_literature_source_regions.cjs')
    renderer = digest(Path(__file__).read_bytes() + worker.read_bytes())
    identity = {'xml': digest(raw), 'media': media_hashes, 'renderer': renderer}
    if available:
        identity['attachments'] = available
    key = digest(json.dumps(identity, sort_keys=True).encode())
    folder = output / pmcid / key[:24]
    folder.mkdir(parents=True, exist_ok=True)
    full_text = folder / 'fulltext.html'
    html_text = render_full_text(root, paths, media, pmcid, supplement)
    if not full_text.exists() or full_text.read_text(encoding='utf-8') != html_text:
        full_text.write_text(html_text, encoding='utf-8')
    gallery = []
    tables = [element for element in root.iter() if local_tag(element) == 'table-wrap'] if document.get('includeGallery', True) else []
    figures = [element for element in root.iter() if local_tag(element) == 'fig'
               and 'pedigree' in element_text(element).lower()] if document.get('includeGallery', True) else []
    for element in tables + figures:
        path = paths[element]
        label = element.find('label')
        title = element_text(label) if label is not None else ('表格' if local_tag(element) == 'table-wrap' else '家系图')
        ref = {'unitId': path, 'quote': element_text(element.find('caption')) if element.find('caption') is not None else title}
        gallery.append({'label': ('表格 · ' if local_tag(element) == 'table-wrap' else '家系图 · ') + title,
                        'sourceRefs': [ref]})
        refs.append(ref)
    previous = {}
    manifest_path = folder / 'manifest.json'
    if manifest_path.is_file():
        previous = json.loads(manifest_path.read_text(encoding='utf-8'))
    regions, annotations, jobs, mapping, citations_by_region = {}, {}, [], {}, {}
    for ref in refs:
        path = ref['unitId']
        if path in by_path:
            region, kind, text = region_for(path, by_path), local_tag(by_path[path]), element_text(by_path[path])
        elif path in supplement_units:
            region, kind, text = supplement_units[path]
        elif document.get('skipUnavailableReferences'):
            continue
        else:
            raise ValueError('原文定位不存在：' + path)
        quote = ' '.join(str(ref.get('quote', '')).split())
        if quote and quote not in ' '.join(text.split()):
            if document.get('skipUnavailableReferences'):
                continue
            raise ValueError('保存的摘录与当前原文位置不一致：' + path)
        if not quote:
            raise ValueError('没有可用于精确标注的原文摘录：' + path)
        citation = {'unitId': path, 'targetElementId': anchor(path),
                    'quote': quote, 'sourceKind': kind}
        selected = citations_by_region.setdefault(region, [])
        if citation not in selected:
            selected.append(citation)
        if region in regions:
            continue
        regions[region] = {'elementId': anchor(region)}
    for region, citations in citations_by_region.items():
        citation_key = digest(json.dumps({'region': region, 'citations': citations},
            ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode())
        annotated = folder / (anchor(citation_key) + '-annotated.png')
        prior = previous.get('annotations', {}).get(citation_key, {})
        reusable_annotation = prior.get('available') is not False and annotated.is_file() and prior.get('sha256') == digest(annotated.read_bytes()) and annotated.read_bytes().startswith(b'\x89PNG\r\n\x1a\n')
        cached_unavailable = prior.get('available') is False
        annotations[citation_key] = {'path': str(annotated), 'elementId': anchor(region),
            'citations': citations, 'available': reusable_annotation, 'reused': reusable_annotation,
            'fallbackReason': prior.get('fallbackReason', '') if cached_unavailable else ''}
        for citation in citations:
            target_path = citation['unitId']
            mapped = mapping.setdefault(target_path, [])
            if not any(item['quote'] == citation['quote'] for item in mapped):
                mapped.append({'quote': citation['quote'], 'imageUrl': annotated.as_uri(),
                    'fullTextUrl': full_text.as_uri() + '#' + anchor(target_path),
                    'annotationKey': citation_key})
        annotation = annotations[citation_key]
        if annotation['reused'] or annotation['fallbackReason']:
            continue
        jobs.append({'htmlPath': str(full_text), 'elementId': annotation['elementId'],
            'outputPath': annotation['path'], 'annotation': {'citations': citations},
            'annotationKey': citation_key})
    document['sourceScreenshots'] = mapping
    document['sourceGallery'] = gallery
    document.setdefault('sources', {})['localFullTextUrl'] = full_text.as_uri()
    document['sources']['localScreenshotRoot'] = folder.as_uri() + '/'
    manifest = {'sourceFile': str(xml_path), 'sourceSha256': digest(raw), 'mediaSha256': media_hashes, 'registeredMediaChecked': True,
                'attachmentSha256s': available,
                'rendererSha256': renderer, 'viewKind': 'LOCAL_JATS_FULLTEXT',
                'regions': regions, 'annotations': annotations}
    return manifest_path, manifest, jobs


def publish_manifest(path, manifest):
    # Another request can add regions to the same generation while rendering.
    # Read the published index under the lock and replace it atomically for readers.
    with path.with_suffix('.lock').open('a+b') as lock:
        if os.name == 'nt':
            import msvcrt
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl
            fcntl.flock(lock, fcntl.LOCK_EX)
        previous = json.loads(path.read_text(encoding='utf-8')) if path.is_file() else {}
        combined = manifest.copy()
        identity_fields = ('sourceSha256', 'mediaSha256', 'rendererSha256')
        if all(previous.get(field) == manifest[field] for field in identity_fields):
            for field in ('regions', 'annotations'):
                combined[field] = previous[field] | manifest[field]
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(combined, ensure_ascii=False, indent=2), encoding='utf-8')
        temporary.replace(path)


def build(report, library, output, browser, node):
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    plans, documents = [], []
    for document in report['documents']:
        try:
            plan = plan_document(document, Path(library).resolve(), output)
        except (OSError, ValueError, ET.ParseError):
            if document.get('skipUnavailableReferences'):
                continue
            raise
        plans.append(plan)
        documents.append(document)
    jobs = [job for _, _, selected in plans for job in selected]
    if jobs:
        job_data = json.dumps({'jobs': jobs}, ensure_ascii=False, indent=2)
        job_path = output / ('capture-jobs-' + digest(job_data.encode('utf-8'))[:24] + '.json')
        job_path.write_text(job_data, encoding='utf-8')
        completed = subprocess.run([str(node), str(Path(__file__).with_name('capture_literature_source_regions.cjs')),
                        str(browser), str(job_path)], check=True, timeout=180,
                        capture_output=True, text=True, encoding='utf-8')
        capture_results = [json.loads(line) for line in completed.stdout.splitlines()]
        skipped = {result['annotationKey']: result for result in capture_results
                   if result.get('annotationSkipped')}
        for document, (_, manifest, _) in zip(documents, plans):
            for result in capture_results:
                annotation = manifest['annotations'].get(result.get('annotationKey'))
                if annotation is not None and not result.get('annotationSkipped'):
                    annotation['available'] = True
            for key, result in skipped.items():
                annotation = manifest['annotations'].get(key)
                if annotation is not None:
                    annotation['available'] = False
                    annotation['fallbackReason'] = result.get('reason', '没有唯一匹配到引用文字')
            for refs_for_path in document.get('sourceScreenshots', {}).values():
                for item in refs_for_path:
                    annotation = manifest['annotations'].get(item.get('annotationKey'))
                    if annotation is not None and not annotation['available']:
                        item['imageUrl'] = None
                    item.pop('annotationKey', None)
    else:
        for document, (_, manifest, _) in zip(documents, plans):
            for refs_for_path in document.get('sourceScreenshots', {}).values():
                for item in refs_for_path:
                    annotation = manifest['annotations'].get(item.get('annotationKey'))
                    if annotation and not annotation['available']:
                        item['imageUrl'] = None
                    item.pop('annotationKey', None)
    for path, manifest, _ in plans:
        for annotation in manifest['annotations'].values():
            if not annotation['available']:
                continue
            raw = Path(annotation['path']).read_bytes()
            if not raw.startswith(b'\x89PNG\r\n\x1a\n'):
                raise ValueError('标注截图不是有效PNG。')
            annotation['sha256'] = digest(raw)
        publish_manifest(path, manifest)
    return {'generated': len(jobs), 'reused': sum(item['reused'] for _, manifest, _ in plans
            for item in manifest['annotations'].values()),
            'folder': str(output)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--library', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--browser', required=True, type=Path)
    parser.add_argument('--node', required=True, type=Path)
    args = parser.parse_args()
    from export_literature_readable import render_html
    report = json.loads(args.report.read_text(encoding='utf-8'))
    receipt = build(report, args.library, args.output, args.browser, args.node)
    args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    output_html = args.report.with_name(args.report.name.removesuffix('.view.json') + '.html')
    output_html.write_text(render_html(report, Path(__file__).with_name('literature_readable_template.html').read_text(encoding='utf-8')), encoding='utf-8')
    print(json.dumps(receipt))


if __name__ == '__main__':
    main()
