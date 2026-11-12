"""Replay a manually archived journal reader without representing it as JATS."""
from html.parser import HTMLParser
import re
from urllib.parse import urljoin, urlsplit

from model_literature_contract import digest, parse


VERSION = 'pm3-official-publisher-reader-v1'
SCRIPT = re.compile(r'<script\b[^>]*>\s*data\s*=\s*(\{.*?\})\s*;?\s*</script>', re.S | re.I)


def require(condition, code):
    if not condition:
        raise ValueError('PM3_PUBLISHER_' + code)


def https(url):
    parts = urlsplit(url)
    require(parts.scheme == 'https' and parts.hostname and not parts.username
            and not parts.password and not parts.fragment and not parts.query
            and not parts.port, 'URL_INVALID')
    return parts.netloc


class ArticleLanding(HTMLParser):
    def __init__(self, raw):
        super().__init__()
        self.dois, self.readers = set(), set()
        self.feed(raw.decode('utf-8-sig'))
        self.close()

    def handle_starttag(self, tag, attributes):
        attrs = dict(attributes)
        if tag == 'meta' and attrs.get('name', '').casefold() == 'dc.identifier':
            value = attrs.get('content', '')
            if value.startswith('doi:'):
                self.dois.add(value[4:])
        if tag == 'a' and 'j-html' in (attrs.get('class') or '').split() and attrs.get('href'):
            self.readers.add(attrs['href'])


def inline(nodes, depth=0):
    require(isinstance(nodes, list) and depth < 24, 'INLINE_FORMAT_UNSUPPORTED')
    parts = []
    for node in nodes:
        name, value = node['name'], node['data']
        if name == 'text' and isinstance(value, str):
            parts.append(value)
        elif name in {'italic', 'bold', 'sup', 'sub', 'underline'} and isinstance(value, list):
            parts.append(inline(value, depth+1))
        elif name in {'xref', 'extlink', 'blockXref', 'vocab'} and isinstance(value, dict):
            label = value.get('text', '')
            if isinstance(label, list):
                label = inline(label, depth+1)
            require(isinstance(label, str), 'INLINE_LABEL_INVALID')
            parts.append(label)
        else:
            require(False, 'INLINE_FORMAT_UNSUPPORTED')
    return ''.join(parts)


def prepare(raw, pmcid, main, receipt, read):
    """Verify original bytes and collect literal units at publisher JSON pointers."""
    require(receipt.get('schemaVersion') == VERSION and digest(raw) == main['sha256'], 'SOURCE_MISMATCH')
    main_url = main['url']
    landing = receipt['landing']
    require(https(main_url) == https(landing['url']), 'LANDING_ORIGIN_MISMATCH')
    identifiers = parse(read(receipt['europePmcMetadata'], 262144))['resultList']['result']
    require(len(identifiers) == 1 and identifiers[0]['pmcid'] == pmcid
            and identifiers[0]['source'] == 'MED'
            and re.fullmatch(r'[1-9][0-9]*', identifiers[0]['id']), 'PUBLIC_IDENTIFIER_MISMATCH')
    pmid, doi = identifiers[0]['id'], identifiers[0]['doi']
    require(re.fullmatch(r'10\.[0-9]{4,9}/\S{1,180}', doi), 'DOI_INVALID')
    landing_page = ArticleLanding(read(landing, 2097152))
    require(landing_page.dois == {doi} and
            {urljoin(landing['url'], path) for path in landing_page.readers} == {main_url}, 'READER_NOT_LINKED_FROM_DOI')
    scripts = SCRIPT.findall(raw.decode('utf-8-sig'))
    require(len(scripts) == 1, 'STRUCTURED_ARTICLE_REQUIRED')
    article = parse(scripts[0].encode())
    require(article['articlemeta']['doi'] == doi and article['offline'] is True
            and isinstance(article['body'], list) and article['body'], 'ARTICLE_IDENTITY_OR_BODY_MISMATCH')
    language = article['defaultlang']
    titles = [inline(t['data']) for t in article['titlegroup']['articletitle'] if t['lang'] == language]
    require(len(titles) == 1 and titles[0].strip(), 'TITLE_REQUIRED')

    units, tables, figures, used = [], [], {}, set()
    attachments = receipt['figures']

    def unit(path, kind, text, sections, lang, **extra):
        value = ' '.join(text.split())
        if value:
            units.append({'unitId': path, 'kind': kind, 'sections': list(sections),
                'language': lang, 'text': value, 'textSha256': digest(value.encode()),
                'sourceSha256': main['sha256'], 'sourcePart': 'publisher-reader-json',
                'sourcePath': path, **extra})
        return value

    def caption(item, path, sections, lang, **extra):
        candidates = [(i, c) for i, c in enumerate(item['caption']) if c['lang'] == lang]
        require(len(candidates) == 1, 'CAPTION_AMBIGUOUS')
        number, row = candidates[0]
        label = inline(row['label']).strip()
        unit_id = f'{path}/caption/{number}/title'
        unit(unit_id, 'CAPTION', inline(row['title']), sections, lang,
             sourceLabel=label, sourceObjectId=item['id'], **extra)
        return label, unit_id

    def table(item, path, sections, lang):
        caption(item, path, sections, lang)
        require(len(item['table']) == 1, 'TABLE_GROUP_UNSUPPORTED')
        group = item['table'][0]
        rows = []
        for part in ('head', 'body', 'foot'):
            for row_index, row in enumerate(group[part]):
                cells = []
                for col, cell in enumerate(row):
                    rowspan, colspan = int(cell.get('rowspan', '1')), int(cell.get('colspan', '1'))
                    require(1 <= rowspan <= 100 and 1 <= colspan <= 100, 'TABLE_SPAN_INVALID')
                    location = f'{path}/table/0/{part}/{row_index}/{col}/data'
                    value = unit(location, 'TABLE_CELL', inline(cell['data']), sections, lang)
                    cells.append({'unitId': location if value else None, 'sourceCellIndex': col,
                        'text': value, 'rowspan': rowspan, 'colspan': colspan, 'header': part == 'head'})
                rows.append({'sourceRowIndex': len(rows), 'cells': cells})
        tables.append({'tableId': path, 'xmlId': item['id'], 'rows': rows,
                       'layoutStatus': 'SOURCE_SPANS_PRESERVED_NOT_FLATTENED'})

    def figure(item, path, sections, lang):
        key = item['id']
        require(key not in used and re.fullmatch(r'[A-Za-z][A-Za-z0-9_.-]{0,79}', key), 'FIGURE_ID_INVALID')
        used.add(key)
        proof = attachments.get(key)
        if proof is not None:
            choices = [graphic['big'] for graphic in item['graphics'] if graphic.get('big')]
            require(len(choices) == 1 and urljoin(main_url, choices[0]) == proof['url']
                    and https(proof['url']) == https(main_url), 'FIGURE_LINK_MISMATCH')
            image = read(proof, 5 * 1024 * 1024)
            require(image.startswith(b'\xff\xd8\xff') and image.endswith(b'\xff\xd9'), 'FIGURE_JPEG_REQUIRED')
            proof = {'sha256': proof['sha256'], 'url': proof['url'], 'bytes': len(image)}
        label, pointer = caption(item, path, sections, lang, **({'publishedFigure': proof} if proof else {}))
        if proof:
            figures[key] = {**proof, 'label': label, 'captionUnitId': pointer}

    def visit(nodes, path, sections, lang, kind, depth=0):
        require(depth < 16 and len(nodes) <= 512 and len(units) <= 10000, 'STRUCTURE_LIMIT')
        for i, node in enumerate(nodes):
            name, item = node['name'], node['data']
            pointer = f'{path}/{i}/data'
            if name == 'sec':
                require(item and item[0]['name'] == 'sectitle', 'SECTION_TITLE_REQUIRED')
                title = ' '.join(inline(item[0]['data']['title']).split())
                require(bool(title), 'SECTION_TITLE_REQUIRED')
                visit(item, pointer, (*sections, title), lang, kind, depth+1)
            elif name == 'p':
                unit(pointer, kind, inline(item), sections, lang)
            elif name == 'fig' and kind == 'BODY_PARAGRAPH':
                figure(item, pointer, sections, lang)
            elif name == 'table' and kind == 'BODY_PARAGRAPH':
                table(item, pointer, sections, lang)
            else:
                require(name == 'sectitle' and i == 0 and depth > 0, 'BODY_BLOCK_UNSUPPORTED')

    for i, abstract in enumerate(article['abstracts']):
        visit(abstract['data'], f'/abstracts/{i}/data', (), abstract['lang'], 'ABSTRACT')
    visit(article['body'], '/body', (), language, 'BODY_PARAGRAPH')
    require(used.issuperset(attachments) and any(u['kind'] == 'BODY_PARAGRAPH' for u in units),
            'BODY_OR_FIGURE_MISSING')
    return {'schema': VERSION, 'pmcid': pmcid, 'sourceSha256': main['sha256'], 'sourceBytes': len(raw),
            'sourceUrl': main_url, 'identifiers': {'pmcid': pmcid, 'pmid': pmid, 'doi': doi},
            'title': titles[0], 'units': units, 'tables': tables, 'figures': figures, 'links': [],
            'qualification': 'SOURCE_PREPARED_NOT_CLINICALLY_QUALIFIED', 'modelCalls': 0}
