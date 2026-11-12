"""Bind a manual PP1 review to the published pages of a scanned PMC article."""
from html.parser import HTMLParser
import re
from xml.etree import ElementTree

from model_literature_contract import digest
from prepare_literature import prose, text

KIND = 'ORIGINAL_PUBLISHED_PAGE'


def require(condition, reason):
    if not condition:
        raise ValueError('PP1_SCAN_' + reason)


def article_metadata(raw):
    require(not re.search(rb'<!ENTITY|<!DOCTYPE[^>]*\[', raw, re.I), 'XML_ENTITY_FORBIDDEN')
    try:
        root = ElementTree.fromstring(raw)
    except ElementTree.ParseError as error:
        raise ValueError('PP1_SCAN_INVALID_METADATA') from error
    articles = root.findall('article')
    require(root.tag == 'pmc-articleset' and len(articles) == 1 and articles[0].find('body') is None,
            'SCANNED_ARTICLE_METADATA_REQUIRED')
    return ElementTree.tostring(articles[0], encoding='utf-8')


def bibliography(raw, pmcid):
    meta = ElementTree.fromstring(raw).find('./front/article-meta')
    require(meta is not None, 'BIBLIOGRAPHY_REQUIRED')
    identifiers = {}
    for identifier in meta.findall('article-id'):
        kind, value = identifier.get('pub-id-type'), text(identifier)
        require(kind not in identifiers or identifiers[kind] == value, 'IDENTIFIER_CONFLICT')
        identifiers[kind] = value
    title = meta.find('./title-group/article-title')
    require(identifiers.get('pmcid') == pmcid and title is not None and text(title), 'ARTICLE_IDENTITY_MISMATCH')
    units = []
    for i, abstract in enumerate(meta.findall('abstract'), 1):
        for j, paragraph in enumerate(abstract.findall('p'), 1):
            value = prose(paragraph)
            if value:
                units.append({'unitId': f'/article[1]/front[1]/article-meta[1]/abstract[{i}]/p[{j}]',
                              'kind': 'ABSTRACT', 'xmlId': paragraph.get('id'), 'sections': [], 'text': value,
                              'textSha256': digest(value.encode('utf-8'))})
    return {'schema': 'pp1-scanned-publication-metadata-v1', 'sourceSha256': digest(raw), 'sourceBytes': len(raw),
            'pmcid': pmcid, 'identifiers': identifiers, 'title': text(title), 'units': units, 'tables': [], 'links': []}


class PageCatalogue(HTMLParser):
    def __init__(self, pmcid, listing):
        super().__init__()
        self.pages = {}
        self.url = re.compile(r'https://cdn\.ncbi\.nlm\.nih\.gov/pmc/blobs/[a-f0-9]+/'
                              + pmcid.removeprefix('PMC') + r'/[a-f0-9]+/[A-Za-z0-9_-]+\.png')
        self.feed(listing.decode('utf-8'))

    def handle_starttag(self, tag, attrs):
        values = dict(attrs)
        page, url = values.get('alt') or '', values.get('src') or ''
        if tag == 'img' and re.fullmatch(r'[1-9][0-9]{0,5}', page) and self.url.fullmatch(url):
            require(page not in self.pages or self.pages[page] == url, 'PAGE_CATALOGUE_AMBIGUOUS')
            self.pages[page] = url


def pages(raw, pmcid, scan, read):
    require(set(scan) == {'metadata', 'catalogue', 'pages'}, 'BUNDLE_SCHEMA')
    require(article_metadata(read(scan['metadata'], 2097152)) == raw, 'METADATA_DERIVATION_CHANGED')
    catalogue = PageCatalogue(pmcid, read(scan['catalogue'], 2097152))
    result = {}
    rows = scan['pages']
    require(isinstance(rows, list) and 1 <= len(rows) <= 40, 'PAGE_LIMIT')
    for row in rows:
        require(set(row) == {'page', 'url', 'path', 'sha256'}, 'PAGE_SCHEMA')
        page, url = row['page'], row['url']
        require(catalogue.pages.get(page) == url and url and page not in result, 'PAGE_NOT_IN_PUBLIC_CATALOGUE')
        image = read(row, 4 * 1024 * 1024)
        require(image.startswith(b'\x89PNG\r\n\x1a\n') and digest(image) == row['sha256'], 'PAGE_CONTENT_INVALID')
        result[page] = {'url': url, 'sourceSha256': row['sha256']}
    return result


def check_reference(source, ref):
    require(set(ref) == {'bundleSha256', 'sourceSha256', 'sourceKind', 'unitId', 'url', 'page', 'locator'},
            'REFERENCE_SCHEMA')
    page = ref['page']
    require(isinstance(page, str), 'REFERENCE_PAGE_INVALID')
    registered = source.get('reviewedPages', {}).get(page)
    require(registered and ref['unitId'] == 'page:' + page and all(ref[k] == v for k, v in registered.items())
            and isinstance(ref['locator'], str) and 1 <= len(ref['locator'].strip()) <= 1000,
            'REFERENCE_NOT_REGISTERED')
