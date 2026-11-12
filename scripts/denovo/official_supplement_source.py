"""Replayable evidence for explicit imports of official PMC single files.

Trust boundary is the captured retrieval record, as with the existing local
download cache. A SHA verifies integrity, not publisher authorship or clinical
approval. Browser captures retain their actual method; never invent HTTP headers.
"""
from html.parser import HTMLParser
import re
from urllib.parse import urljoin, urlsplit

from model_literature_contract import parse, digest

VERSION = 'pmc-official-single-file-v1'
MAX_PAGE = 2 * 1024 * 1024


def require(value, reason):
    if not value: raise ValueError('PAPER_SINGLE_FILE_' + reason)


class ArticleLinks(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links, self.canonicals, self.metadata = set(), set(), {}

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'a' and attrs.get('href'): self.links.add(attrs['href'])
        if tag == 'link' and attrs.get('rel') == 'canonical': self.canonicals.add(attrs.get('href'))
        if tag == 'meta' and attrs.get('name') in {'citation_doi', 'citation_pmid'}:
            key, value = attrs['name'], attrs.get('content')
            require(key not in self.metadata or self.metadata[key] == value, 'ARTICLE_IDENTITY_CONFLICT')
            self.metadata[key] = value


def validate(document, item, body, receipt, read):
    """read(ref,max) must resolve inside paper root and verify ref's SHA."""
    require(receipt.get('schemaVersion') == VERSION and receipt.get('pmcid') == document['pmcid']
            and receipt.get('mainSha256') == document['sourceSha256'], 'SOURCE_BINDING_MISMATCH')
    pmcid = document['pmcid']
    name = item['originalName']
    require(isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,200}', name)
            and '..' not in name, 'FILENAME_INVALID')
    url = f'https://pmc.ncbi.nlm.nih.gov/articles/instance/{pmcid[3:]}/bin/{name}'
    require(item.get('url') == url and item.get('sha256') == digest(body) and
            item.get('bytes') == len(body), 'FILE_BINDING_MISMATCH')
    matches = [link['sourceUnitId'] for link in document['links']
               if link['kind'] in {'media', 'supplementary-material', 'ext-link'} and
               '/ref[' not in link['sourceUnitId'] and '/ref-list[' not in link['sourceUnitId'] and
               link.get('href') in {name, url, urlsplit(url).path}]
    require(matches and item.get('matchedSourceUnits') == matches and
            item.get('association') == 'EXACT_JATS_FILENAME', 'JATS_LINK_MISMATCH')
    page_raw = read(receipt['article'], MAX_PAGE)
    page_record = parse(read(receipt['articleRecord'], 65536))
    page_url = f'https://pmc.ncbi.nlm.nih.gov/articles/{pmcid}/'
    require(page_record.get('url') == page_url and page_record.get('finalUrl') == page_url and
            page_record.get('status') == 200 and page_record.get('sha256') == digest(page_raw) and
            page_record.get('bytes') == len(page_raw), 'ARTICLE_RECORD_MISMATCH')
    page = ArticleLinks(); page.feed(page_raw.decode('utf-8-sig')); page.close()
    require(page.canonicals == {page_url}, 'ARTICLE_IDENTITY_MISMATCH')
    for key in ('pmid', 'doi'):
        expected = document['identifiers'].get(key)
        require(not expected or page.metadata.get('citation_' + key) == expected, 'ARTICLE_IDENTITY_MISMATCH')
    require(any(urljoin(page_url, link) == url for link in page.links), 'ARTICLE_LINK_MISSING')
    capture = parse(read(receipt['acquisitionRecord'], 1048576))
    rows = [row for row in capture.get('downloads', []) if row.get('originalName') == name]
    require(len(rows) == 1, 'ACQUISITION_RECORD_AMBIGUOUS')
    row = rows[0]
    require(row.get('method') == 'CODEX_IAB_OBSERVED_LINK_DOWNLOAD_MEDIA' and
            all(row.get(k) == item[k] for k in ('url', 'sha256', 'bytes', 'path')) and
            row.get('articleHtmlSha256') == digest(page_raw), 'ACQUISITION_RECORD_MISMATCH')
    return {'kind': VERSION, 'method': row['method'], 'url': url,
            'trustBoundary': 'CAPTURED_RETRIEVAL_RECORD_NOT_DIGITAL_PUBLISHER_SIGNATURE',
            'scientificAdoption': False}
