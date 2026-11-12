"""Replay curator-reviewed public observations against pinned originals, without scoring."""
from html.parser import HTMLParser
from pathlib import Path
import re
from urllib.parse import urlsplit

from core_context_review import load_config
from core_local_identity import LocalIdentity
from literature_allele_identity import protein, read_record, resolve
from model_literature_contract import canonical, digest, parse
from prepare_literature import MAX_BYTES, prepare


VERSION = 'denovo-reviewed-paper-v1'
TARGETS = {'PRESENT', 'ABSENT', 'NOT_TESTED', 'NOT_REPORTED', 'QUALITY_FAILED', 'CONFLICT'}
METHODS = {'SANGER', 'TARGETED_SEQUENCING', 'TARGETED_GENOTYPING', 'EXOME', 'GENOME'}
RELATIONS = {'CONFIRMED', 'ASSUMED_NOT_REPORTED', 'ASSUMED_NOT_TESTED', 'ASSUMED_NOT_CONFIRMED',
             'INCONCLUSIVE', 'EXCLUDED'}
ROLES = ('proband', 'mother', 'father')
QTC_IN_MS = re.compile(r'\bQTc(?: interval)?\s*(?:(?:was|of)\s+|[:=]\s*)?(\d{2,4}(?:\.\d+)?)\s*ms\b', re.I)


def require(value, code):
    if not value:
        raise ValueError('DENOVO_REVIEWED_' + code)


def short(value, limit=256):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= limit


def normal(text):
    return ' '.join(text.split())


class PmcArticle(HTMLParser):
    """Only collect literal text and linked original figures inside the article."""

    def __init__(self, raw, pmcid):
        super().__init__()
        require(0 < len(raw) <= MAX_BYTES, 'PAPER_SIZE_LIMIT')
        self.in_article = False
        self.article_url = self.title = self.pmid = self.doi = None
        self.scopes, self.texts, self.images, self.links = [], {}, {}, {}
        self.paragraphs, self.tables = {}, {}
        self.table_depth = 0
        self.table = self.row = self.cell = None
        self.feed(raw.decode('utf-8'))
        self.close()
        require(self.article_url == f'https://pmc.ncbi.nlm.nih.gov/articles/{pmcid}/' and self.title and self.texts,
                'PAPER_IDENTITY')

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == 'meta':
            if attributes.get('name') == 'citation_fulltext_html_url':
                self.article_url = attributes.get('content')
            if attributes.get('name') == 'citation_title':
                self.title = attributes.get('content')
            if attributes.get('name') == 'citation_pmid':
                self.pmid = attributes.get('content')
            if attributes.get('name') == 'citation_doi':
                self.doi = attributes.get('content')
        if tag == 'article':
            self.in_article = True
        if not self.in_article:
            return
        if tag in ('article', 'section', 'figure', 'li', 'p'):
            key = attributes.get('id') or ('article' if tag == 'article' else None)
            if tag == 'p' and not key and not self.table_depth and self.scopes:
                parent_tag, parent_key = self.scopes[-1]
                if parent_key and parent_tag in ('section', 'article'):
                    self.paragraphs[parent_key] = self.paragraphs.get(parent_key, 0) + 1
                    key = f'{parent_key}/p[{self.paragraphs[parent_key]}]'
            self.scopes.append((tag, key))
            if key:
                require(key not in self.texts, 'DUPLICATE_HTML_LOCATOR')
                self.texts[key] = []
        if tag == 'a' and attributes.get('href'):
            for _, key in self.scopes:
                if key:
                    self.links.setdefault(key, set()).add(attributes['href'])
        if tag == 'img':
            figure = next((key for kind, key in reversed(self.scopes) if kind == 'figure'), None)
            if figure:
                self.images.setdefault(figure, set()).add(attributes.get('src'))
        if tag == 'table':
            self.table_depth += 1
            if self.table_depth == 1:
                key = attributes.get('id') or next((key for kind, key in reversed(self.scopes) if kind == 'section' and key), None)
                self.table = {'key': key, 'rows': [], 'usable': True}
            else:
                self.table['usable'] = False
        if self.table_depth == 1 and tag == 'tr':
            self.row, self.spanned_row = [], False
        if self.table_depth == 1 and self.row is not None and tag in ('th', 'td'):
            self.cell = []
            self.spanned_row |= attributes.get('colspan', '1') != '1'
            if attributes.get('rowspan', '1') != '1':
                self.table['usable'] = False

    def handle_endtag(self, tag):
        if self.table_depth == 1 and self.cell is not None and tag in ('th', 'td'):
            self.row.append(normal(' '.join(self.cell)))
            self.cell = None
        if self.table_depth == 1 and self.row is not None and tag == 'tr':
            if not self.spanned_row:
                self.table['rows'].append(self.row)
            self.row = None
        if self.table_depth and tag == 'table':
            self.table_depth -= 1
            if not self.table_depth:
                key, rows = self.table['key'], self.table['rows']
                if key:
                    self.tables[key] = rows if self.table['usable'] and key not in self.tables else None
                self.table = self.row = self.cell = None
        if self.scopes and self.scopes[-1][0] == tag:
            self.scopes.pop()
        if tag == 'article':
            self.in_article = False

    def handle_data(self, data):
        if self.table_depth == 1 and self.cell is not None:
            self.cell.append(data)
        for _, key in self.scopes:
            if key:
                self.texts[key].append(data)

    def case_column(self, locator, label):
        def label_key(value):
            return re.sub(r'^(?:pt\.?|patient|case)\s+', '', value.casefold())

        rows = self.tables.get(locator)
        require(rows and len(rows[0]) >= 2, 'CASE_TABLE_UNAVAILABLE')
        header = rows[0]
        columns = [i for i in range(1, len(header)) if label_key(header[i]) == label_key(label)]
        require(len(columns) == 1, 'CASE_TABLE_COLUMN_UNBOUND')
        cells = {}
        for row in rows[1:]:
            if len(row) != len(header) or not row[0]:
                continue
            require(row[0] not in cells, 'CASE_TABLE_ROW_AMBIGUOUS')
            cells[row[0]] = row[columns[0]]
        return cells

    def case_row(self, locator, label):
        rows = self.tables.get(locator)
        require(rows and len(rows[0]) >= 2, 'CASE_TABLE_UNAVAILABLE')
        matching = [row for row in rows[1:] if len(row) == len(rows[0]) and row[0] == label]
        require(len(matching) == 1, 'CASE_TABLE_ROW_UNBOUND')
        return ' | '.join(matching[0])


def paper_citation(pmcid, title, pin, locator, quote, kind='PAPER_PRIMARY'):
    return {'sourceSha256': pin, 'role': kind, 'locator': locator, 'quote': quote, 'kind': 'SOURCE_UNIT',
            'paperId': pmcid, 'title': title, 'locationLabel': locator,
            'url': f'https://pmc.ncbi.nlm.nih.gov/articles/{pmcid}/',
            'linkScope': 'ARTICLE_LANDING_PAGE', 'scopeOnly': False}


def table_citation(pmcid, title, pin, table, label, row, value):
    return paper_citation(pmcid, title, pin, f'{table} · {label} · {row}', value)


def allele_column(article, pmcid, pin, table, label, case):
    cells = article.case_column(table, label)
    citations = []
    for target in (case['reportedCoding'], case['reportedProtein']):
        matches = [row for row, value in cells.items() if ''.join(value.split()) == target]
        require(len(matches) == 1, 'CASE_TABLE_ALLELE_UNBOUND')
        row = matches[0]
        citations.append(table_citation(pmcid, article.title, pin, table, label, row, cells[row]))
    return cells, citations


def verify_follow_ups(case, primary, primary_cells, document, originals):
    reports, seen = [], {document['pmcid']}
    follow_ups = case.get('followUps', [])
    require(isinstance(follow_ups, list) and len(follow_ups) <= 4, 'FOLLOW_UP_LIMIT')
    for follow_up in follow_ups:
        require(set(follow_up) == {'pmcid', 'sourceSha256', 'label', 'table', 'primaryReference', 'caseReference', 'matches'},
                'FOLLOW_UP_SCHEMA')
        pmcid, pin, label, table = (follow_up[k] for k in ('pmcid', 'sourceSha256', 'label', 'table'))
        require(isinstance(pmcid, str) and re.fullmatch(r'PMC[1-9][0-9]*', pmcid) and pmcid not in seen
                and pin in document.get('reviewedFollowUpSha256s', []) and short(label) and short(table), 'FOLLOW_UP_SOURCE_UNBOUND')
        seen.add(pmcid)
        require(primary is not None and primary.doi and primary_cells, 'FOLLOW_UP_PRIMARY_CASE_UNBOUND')
        article = PmcArticle(originals[pin], pmcid)
        cells, citations = allele_column(article, pmcid, pin, table, label, case)
        ref = follow_up['caseReference']
        anchor = follow_up['primaryReference']
        require(set(ref) == {'locator', 'quote'} and short(ref['locator']) and short(ref['quote'], 4096) and short(anchor),
                'FOLLOW_UP_REFERENCE_SCHEMA')
        quote, locator = normal(ref['quote']), ref['locator']
        text = normal(' '.join(article.texts.get(locator, [])))
        require(re.match(re.escape(label) + r'(?!\w)', quote) and text.startswith(quote)
                and '#' + anchor in article.links.get(locator, set())
                and 'https://doi.org/' + primary.doi in article.links.get(anchor, set()), 'FOLLOW_UP_CASE_REFERENCE_UNBOUND')
        citations.append(paper_citation(pmcid, article.title, pin, locator, quote))
        matches = follow_up['matches']
        require(isinstance(matches, list) and 2 <= len(matches) <= 8, 'FOLLOW_UP_MATCH_LIMIT')
        primary_rows, repeat_rows = set(), set()
        for match in matches:
            require(set(match) == {'primaryRow', 'followUpRow', 'number'}, 'FOLLOW_UP_MATCH_SCHEMA')
            original_row, repeat_row, number = (match[k] for k in ('primaryRow', 'followUpRow', 'number'))
            require(short(original_row) and short(repeat_row) and isinstance(number, str)
                    and re.fullmatch(r'[0-9]+(?:\.[0-9]+)?', number)
                    and original_row not in primary_rows and repeat_row not in repeat_rows, 'FOLLOW_UP_MATCH_SCHEMA')
            primary_rows.add(original_row); repeat_rows.add(repeat_row)
            for value in (primary_cells.get(original_row, ''), cells.get(repeat_row, '')):
                require(re.fullmatch(re.escape(number) + r'(?:\s*[A-Za-zμµ/]+)?', value.replace(',', '')),
                        'FOLLOW_UP_CASE_DETAILS_MISMATCH')
            citations.extend((table_citation(document['pmcid'], primary.title, document['sha256'], case['variantTable'],
                                             case['label'], original_row, primary_cells[original_row]),
                              table_citation(pmcid, article.title, pin, table, label, repeat_row, cells[repeat_row])))
        reports.append({'pmcid': pmcid, 'caseLabel': label, 'sourceSha256': pin, 'citations': citations})
    return reports


def case_label_pattern(label):
    subject = re.escape(label)
    if not re.search(r'\s', label):
        subject = r'(?:case|patient|proband|pt\.?|individual)\s+' + subject
    return re.compile(r'(?<!\w)' + subject + r'(?!\w)', re.I)


def verify_published_conditions(raw, case, cite, units):
    require(isinstance(raw, dict) and raw and set(raw) <= {'qtcMs', 'findings', 'trioExome'}, 'PUBLISHED_CONDITIONS_SCHEMA')
    label = case['label']
    case_label = case_label_pattern(label)

    def case_citation(ref, allow_case_figure=False):
        source = cite(ref, label)
        # A cohort-level observation cannot supply an individual's ECG, family
        # findings or assay. A verified case column or linked figure panel can
        # provide a case locator for the reviewed findings it identifies.
        require(ref.get('columnLabel') == label or (
                'rowLabel' not in ref and case_label.search(units.get(ref['locator'], '')))
                or allow_case_figure and 'caseLocator' in ref,
                'PUBLISHED_CONDITION_CASE_UNBOUND')
        return source

    conditions, fields = {}, []
    if 'qtcMs' in raw:
        measurement = raw['qtcMs']
        require(isinstance(measurement, dict) and set(measurement) == {'value', 'ref'}, 'QTC_SCHEMA')
        value, ref = measurement['value'], measurement['ref']
        source = case_citation(ref)
        measured = {float(v) for v in QTC_IN_MS.findall(source['quote'])}
        if ref.get('columnLabel') == label and re.fullmatch(r'QTc\s*\(ms\)', ref.get('rowLabel', ''), re.I):
            cell = source['quote'].strip()
            if re.fullmatch(r'\d{2,4}(?:\.\d+)?', cell):
                measured.add(float(cell))
        require(type(value) in (int, float) and 0 < value < 10000 and measured == {value}, 'QTC_VALUE_UNBOUND')
        conditions['qtcMs'] = value
        fields.append(('/groups/phenotype/qtcMs', value, [source], 'PROBAND', None))

    findings = raw.get('findings', [])
    require(isinstance(findings, list) and len(findings) <= 24, 'PUBLISHED_FINDINGS_LIMIT')
    observed = {}
    for row in findings:
        require(isinstance(row, dict) and set(row) == {'role', 'finding', 'status', 'ref'}, 'PUBLISHED_FINDING_SCHEMA')
        role, finding, status = row['role'], row['finding'], row['status']
        require(role in {'PROBAND', 'MOTHER', 'FATHER', 'FAMILY'} and short(finding, 80)
                and re.fullmatch(r'[A-Z][A-Z0-9_]+', finding) and status in {'PRESENT', 'ABSENT'}, 'PUBLISHED_FINDING_INVALID')
        observed.setdefault((role, finding), []).append((status, case_citation(row['ref'], allow_case_figure=True)))
    if observed:
        conditions['findings'] = []
    for (role, finding), rows in observed.items():
        statuses = {status for status, _ in rows}
        status = rows[0][0] if len(statuses) == 1 else 'CONFLICT'
        conditions['findings'].append({'role': role, 'finding': finding, 'status': status})
        group = 'phenotype' if role == 'PROBAND' else 'family'
        issue = f'DENOVO_{group.upper()}_SOURCE_CONFLICT' if status == 'CONFLICT' else None
        fields.append((f'/groups/{group}/{role}/{finding}', status, [source for _, source in rows], role, issue))

    if 'trioExome' in raw:
        source = case_citation(raw['trioExome'])
        require(re.search(r'\btrio\b', source['quote'], re.I) and re.search(r'\bexome\b', source['quote'], re.I)
                and all(case['members'][role]['method'] == 'EXOME' for role in ROLES)
                and case['members']['proband']['target'] == 'PRESENT'
                and all(case['members'][role]['target'] == 'ABSENT' for role in ('mother', 'father')),
                'TRIO_EXOME_UNBOUND')
        conditions['trioExome'] = True
        fields.append(('/groups/testing/trioExome', 'TRIO_EXOME', [source], 'FAMILY', None))
    require(conditions, 'PUBLISHED_CONDITIONS_EMPTY')
    return conditions, fields


def replay(raw, document, review_pin, originals, core, alleles=()):
    require(core is not None, 'LOCAL_IDENTITY_CONFIG_REQUIRED')
    review = parse(originals[review_pin])
    pmcid, source_pin = document['pmcid'], document['sha256']
    require(set(review) - {'dataClass'} == {'schemaVersion', 'pmcid', 'sourceSha256', 'format', 'reviewedBy', 'recordSha256', 'cases'}
            and review['schemaVersion'] == VERSION and review['pmcid'] == pmcid and review['sourceSha256'] == source_pin
            and review.get('dataClass', 'PUBLIC_PAPER_RESEARCH') in {'SYNTHETIC', 'PUBLIC_PAPER_RESEARCH'}
            and short(review['reviewedBy']) and re.fullmatch('[a-f0-9]{64}', review['recordSha256']), 'REVIEW_BINDING_INVALID')
    article = None
    if review['format'] == 'JATS':
        paper = prepare(raw, pmcid, source_pin)
        title = paper['title']
        units = {u['unitId']: normal(u['text']) for u in paper['units'] if u['kind'] != 'REFERENCE'}
        rows = {}
        for unit in paper['units']:
            if re.search(r'/tr\[\d+\]/t[dh]\[\d+\]$', unit['unitId']):
                rows.setdefault(unit['unitId'].rsplit('/', 1)[0], []).append(normal(unit['text']))
        units.update({locator: ' | '.join(cells) for locator, cells in rows.items()})
        figures = {}
    else:
        require(review['format'] == 'PMC_HTML', 'PAPER_FORMAT_INVALID')
        article = PmcArticle(raw, pmcid)
        title = article.title
        units = {key: normal(' '.join(chunks)) for key, chunks in article.texts.items()}
        figures = article.images
    root = Path(core['root'])
    config, _ = load_config(root, {'path': core['path'], 'sha256': core['sha256']})
    resolver = LocalIdentity(root, config['identity'])

    def citation(ref, case_label):
        base = {'locator', 'quote'}
        figure = base | {'imageSha256', 'imageUrl', 'panel'}
        require(set(ref) in (base, figure, figure | {'caseLocator'},
                            base | {'rowLabel'}, base | {'columnLabel', 'rowLabel'}), 'CITATION_SCHEMA')
        locator, quote = ref['locator'], ref['quote']
        require(short(locator, 2048) and short(quote, 4096), 'CITATION_TEXT_CHANGED')
        if 'rowLabel' in ref:
            row = ref['rowLabel']
            require(article is not None and short(row), 'CASE_TABLE_LOCATOR_INVALID')
            if 'columnLabel' in ref:
                column = ref['columnLabel']
                require(column == case_label and article.case_column(locator, column).get(row) == normal(quote),
                        'CASE_TABLE_CELL_UNBOUND')
                return table_citation(pmcid, title, source_pin, locator, column, row, normal(quote))
            require(row == case_label and article.case_row(locator, row) == normal(quote), 'CASE_TABLE_ROW_CHANGED')
            return paper_citation(pmcid, title, source_pin, f'{locator} · {row}', normal(quote))
        require(normal(quote) in units.get(locator, ''), 'CITATION_TEXT_CHANGED')
        pin, kind = source_pin, 'PAPER_PRIMARY'
        if 'imageSha256' in ref:
            pin, image_url = ref['imageSha256'], ref['imageUrl']
            image = originals[pin]
            url = urlsplit(image_url)
            require(pin in document['reviewedMediaSha256s'] and short(ref['panel'])
                    and image_url in figures.get(locator, set()) and url.scheme == 'https'
                    and url.netloc == 'cdn.ncbi.nlm.nih.gov' and f'/{pmcid.removeprefix("PMC")}/' in url.path
                    and image.startswith((b'\xff\xd8\xff', b'\x89PNG\r\n\x1a\n')), 'FIGURE_SOURCE_CHANGED')
            if 'caseLocator' in ref:
                anchor = ref['caseLocator']
                require(article is not None and short(anchor, 2048)
                        and case_label_pattern(case_label).match(units.get(anchor, ''))
                        and '#' + locator in article.links.get(anchor, ()), 'FIGURE_CASE_UNBOUND')
            locator = f'{locator} · {ref["panel"]}'
            kind = 'PAPER_ATTACHMENT'
        return paper_citation(pmcid, title, pin, locator, quote, kind)

    cases, labels = [], set()
    require(isinstance(review['cases'], list) and 1 <= len(review['cases']) <= 32, 'CASE_LIMIT')
    for case in review['cases']:
        common = {'label', 'geneSymbol', 'diseaseId', 'transcript', 'coding', 'reportedCoding',
                  'members', 'parentage', 'phenotype', 'family', 'refs'}
        alias = 'reportedProtein' in case
        optional = {'followUps', 'independentOf', 'phenotypeNegatives', 'publishedConditions'}
        if alias:
            optional |= {'variantTable', 'reportedTranscript', 'variantReference'}
        require(set(case) - optional == common | ({'reportedProtein'} if alias else {'reportedGenomic'}), 'CASE_SCHEMA')
        label = case['label']
        require(short(label) and label not in labels and re.fullmatch(r'MONDO:[0-9]{7}', case['diseaseId']), 'CASE_IDENTITY')
        labels.add(label)
        refs = case['refs']
        fields = {'variant', 'proband', 'mother', 'father', 'maternity', 'paternity', 'phenotype', 'family'}
        if 'reportedTranscript' in case:
            fields.add('reportedTranscript')
        if 'phenotypeNegatives' in case:
            fields.add('phenotypeNegatives')
        require(set(refs) == fields, 'CASE_CITATIONS')
        require(all(isinstance(v, list) and 1 <= len(v) <= 16 for v in refs.values()), 'CITATION_LIMIT')
        checked = {field: [citation(r, label) for r in rows] for field, rows in refs.items()}
        second = 'reportedProtein' if alias else 'reportedGenomic'
        def exact_variant(quote):
            compact = ''.join(quote.split()) if alias else quote
            return case['reportedCoding'] in compact and case[second] in compact and (
                (alias and 'variantTable' in case) or re.search(r'(?<!\w)' + re.escape(label) + r'(?!\w)', quote))

        require(all(short(case[k]) for k in ('reportedCoding', second))
                and any(exact_variant(ref['quote']) for ref in checked['variant']), 'REPORTED_ALLELE_UNBOUND')
        identity = resolver.resolve_coding(case['geneSymbol'], case['coding'], case['transcript'])
        allele = identity['allele']
        primary_cells = None
        if alias:
            require(article is not None, 'REPORTED_ALIAS_ARTICLE_REQUIRED')
            if 'variantTable' in case:
                require(short(case['variantTable']), 'REPORTED_ALIAS_TABLE_REQUIRED')
                primary_cells, alias_citations = allele_column(article, pmcid, source_pin, case['variantTable'], label, case)
                checked['variant'].extend(alias_citations)
            else:
                require(any(exact_variant(ref['quote']) and case['geneSymbol'] in ref['quote']
                            and ('rowLabel' not in ref or ref['rowLabel'] == label)
                            for ref in refs['variant']), 'REPORTED_ALIAS_CASE_UNBOUND')
            require(not ('reportedTranscript' in case and 'variantReference' in case), 'REPORTED_ALIAS_PROOF_AMBIGUOUS')
            if 'reportedTranscript' in case:
                reported = case['reportedTranscript']
                require(short(reported) and any(re.search(r'(?<!\w)' + re.escape(reported) + r'(?![.\w])', ref['quote'])
                                                for ref in checked['reportedTranscript']), 'REPORTED_TRANSCRIPT_UNBOUND')
                bridge = resolver.resolve_coding(case['geneSymbol'], ''.join(case['reportedCoding'].split()),
                                                 reported, protein(case['reportedProtein']))
                checked['variant'].extend(checked['reportedTranscript'])
            else:
                fields = [{'field': name, 'findingState': 'REPORTED', 'sourceValue': case[key]} for name, key in
                          [('geneSymbol', 'geneSymbol'), ('variantText', 'reportedCoding'), ('proteinChange', 'reportedProtein')]]
                reference = case.get('variantReference')
                pmid = article.pmid
                if reference is not None:
                    require(isinstance(reference, dict) and set(reference) == {'pmid', 'locator', 'inTextLocator'},
                            'VARIANT_REFERENCE_SCHEMA')
                    pmid, locator, in_text = (reference[k] for k in ('pmid', 'locator', 'inTextLocator'))
                    require(short(pmid) and re.fullmatch(r'[1-9][0-9]*', pmid) and short(locator) and short(in_text)
                            and '#' + locator in article.links.get(in_text, ())
                            and f'https://pubmed.ncbi.nlm.nih.gov/{pmid}/' in article.links.get(locator, ())
                            and any(ref['locator'] == in_text and all(case[key] in ''.join(ref['quote'].split())
                                    for key in ('reportedCoding', 'reportedProtein')) for ref in refs['variant']),
                            'VARIANT_REFERENCE_UNBOUND')
                    checked['variant'].append(paper_citation(pmcid, title, source_pin, locator, units[locator]))
                bridge = resolve({'fields': fields}, pmid, [read_record(*entry) for entry in alleles])
                if reference is not None:
                    bridge['citedVariantReference'] = reference
            require(bridge.get('allele') == allele and bridge.get('hgncId') == identity['hgncId'], 'REPORTED_ALIAS_IDENTITY_UNRESOLVED')
            identity['reportedAlias'] = bridge
        else:
            genomic = re.fullmatch(r'chr([0-9]{1,2}):(?:g\.)?([0-9]+)\s*(?:\|\s*)?([ACGT])\s*(?:>|\|)\s*([ACGT])',
                                   case['reportedGenomic'], re.I)
            expected = (allele['contig'], str(allele['position']), allele['reference'], allele['alternate'])
            require(genomic and tuple(value.upper() for value in genomic.groups()) == expected, 'REPORTED_LOCUS_MISMATCH')
        require(set(case['members']) == set(ROLES) and set(case['parentage']) == {'maternity', 'paternity'}, 'TRIO_SCHEMA')
        member_ids = set()
        for role in ROLES:
            member = case['members'][role]
            require(set(member) == {'id', 'target', 'method'} and short(member['id']) and member['id'] not in member_ids
                    and member['target'] in TARGETS and member['method'] in METHODS | {'UNKNOWN'}, 'TARGET_OBSERVATION_INVALID')
            member_ids.add(member['id'])
        require(all(relation in RELATIONS for relation in case['parentage'].values()), 'PARENTAGE_STATE_INVALID')
        phenotype = case['phenotype']
        require(isinstance(phenotype, list) and len(phenotype) <= 64 and all(short(p) for p in phenotype), 'PHENOTYPE_INVALID')
        if 'phenotypeNegatives' in case:
            negatives = case['phenotypeNegatives']
            require(isinstance(negatives, list) and 1 <= len(negatives) <= 64
                    and all(short(p) for p in negatives), 'PHENOTYPE_NEGATIVES_INVALID')
        family = case['family']
        require(family in {'MATERNALLY_INHERITED', 'PATERNALLY_INHERITED', 'COMPATIBLE', 'UNKNOWN', 'CONFLICT', 'SPECIAL_MODEL'}, 'FAMILY_STATE_INVALID')
        if family in {'MATERNALLY_INHERITED', 'PATERNALLY_INHERITED'}:
            carrier, other = ('mother', 'father') if family == 'MATERNALLY_INHERITED' else ('father', 'mother')
            require(case['members']['proband']['target'] == case['members'][carrier]['target'] == 'PRESENT'
                    and case['members'][other]['target'] == 'ABSENT', 'TRANSMISSION_TARGET_CONFLICT')
        follow_ups = verify_follow_ups(case, article, primary_cells, document, originals)
        pairs = case.get('independentOf', [])
        require(isinstance(pairs, list) and len(pairs) <= 16, 'INDEPENDENT_CASE_LIMIT')
        distinct, seen = [], {(pmcid, label)}
        for pair in pairs:
            require(isinstance(pair, dict) and set(pair) == {'pmcid', 'caseLabel', 'sourceSha256', 'refs'},
                    'INDEPENDENT_CASE_SCHEMA')
            peer, peer_label, pin = (pair[k] for k in ('pmcid', 'caseLabel', 'sourceSha256'))
            require(isinstance(peer, str) and re.fullmatch(r'PMC[1-9][0-9]*', peer) and short(peer_label)
                    and isinstance(pin, str) and re.fullmatch('[a-f0-9]{64}', pin)
                    and (peer, peer_label) not in seen and (peer == pmcid) == (pin == source_pin),
                    'INDEPENDENT_CASE_IDENTITY')
            seen.add((peer, peer_label))
            refs = pair['refs']
            require(isinstance(refs, list) and 1 <= len(refs) <= 8, 'INDEPENDENT_CASE_CITATION_LIMIT')
            distinct.append({'pmcid': peer, 'caseLabel': peer_label, 'sourceSha256': pin,
                             'citations': [citation(ref, label) for ref in refs]})
        conditions, condition_fields = ({}, [])
        if 'publishedConditions' in case:
            conditions, condition_fields = verify_published_conditions(case['publishedConditions'], case, citation, units)
        cases.append(project(case, identity, checked, review_pin, review, follow_ups, distinct, conditions, condition_fields))
    result = {'pmcid': pmcid, 'sourceSha256': source_pin, 'methodVersion': VERSION, 'cases': cases, 'issues': []}
    if review.get('dataClass') == 'SYNTHETIC':
        result['dataClass'] = 'SYNTHETIC'
    return result


def project(case, identity, refs, review_pin, review, follow_ups, distinct, conditions, condition_fields):
    case_key = review['pmcid'] + '#' + case['label']
    fields, blockers = [], []

    def field(path, value, ref, subject, issue=None, category='DATA_MISSING'):
        sources = refs[ref] if isinstance(ref, str) else ref
        fields.append({'path': path, 'value': value, 'subject': subject, 'origin': 'REVIEWED_ASSESSMENT',
                       'availability': 'REPORTED', 'verification': 'CONFLICT' if category == 'CONFLICT' else 'VERIFIED',
                       'issues': [issue] if issue else [], 'citations': sources, 'sourceEvidence': sources})
        if issue:
            blockers.append({'path': path, 'category': category, 'code': issue, 'meaning': 'QUALIFICATION_REQUIRED'})

    field('/groups/paper/identity', review['pmcid'], 'variant', 'PAPER')
    field('/groups/case/label', case['label'], 'variant', 'PROBAND')
    field('/groups/gene/symbol', case['geneSymbol'], 'variant', 'PROBAND')
    reported_coding = case['reportedTranscript'] + ':' + case['reportedCoding'] if 'reportedTranscript' in case else case['reportedCoding']
    field('/groups/variant/representations', [reported_coding, case.get('reportedGenomic', case.get('reportedProtein')),
          case['transcript'] + ':' + case['coding']], 'variant', 'PROBAND')
    field('/groups/variant/resolvedAllele', identity['allele'], 'variant', 'PROBAND')
    for role in ROLES:
        member = case['members'][role]
        subject, target = role.upper(), member['target']
        issue = None if target in {'PRESENT', 'ABSENT'} else f'DENOVO_{subject}_TARGET_{target}'
        if issue is None and member['method'] not in METHODS:
            issue = f'DENOVO_{subject}_TARGET_METHOD_REQUIRED'
        field(f'/groups/{role}/targetResult', target, role, subject, issue, 'CONFLICT' if target == 'CONFLICT' else 'DATA_MISSING')
        field(f'/groups/{role}/memberId', member['id'], role, subject)
        field(f'/groups/testing/{role}Method', member['method'], role, subject)
    for name, subject in [('maternity', 'MOTHER'), ('paternity', 'FATHER')]:
        field('/groups/parentage/' + name, case['parentage'][name], name, subject)
    field('/groups/phenotype/positive', case['phenotype'], 'phenotype', 'PROBAND')
    if not case['phenotype']:
        fields[-1]['availability'] = 'NOT_REPORTED_IN_CHECKED_SCOPE'
    if 'phenotypeNegatives' in case:
        field('/groups/phenotype/negative', case['phenotypeNegatives'], 'phenotypeNegatives', 'PROBAND')
    field('/groups/family/history', case['family'], 'family', 'FAMILY')
    if case['family'] == 'UNKNOWN':
        fields[-1]['availability'] = 'NOT_REPORTED_IN_CHECKED_SCOPE'
    for path, value, citations, role, issue in condition_fields:
        field(path, value, citations, role, issue, 'CONFLICT' if issue else 'DATA_MISSING')
    field('/groups/overlap/independence', 'UNIQUE_WITHIN_CHECKED_SCOPE', 'variant', 'FAMILY')
    if distinct:
        field('/groups/overlap/independentOf', [f'{r["pmcid"]} · {r["caseLabel"]}' for r in distinct],
              [source for peer in distinct for source in peer['citations']], 'FAMILY')
    if follow_ups:
        citations = [citation for report in follow_ups for citation in report['citations']]
        field('/groups/overlap/followUpReports', [f'{r["pmcid"]} · {r["caseLabel"]}' for r in follow_ups], citations, 'PROBAND')
        field('/groups/overlap/eventGrouping', 'VERIFIED_FOLLOW_UP_SAME_EVENT', citations, 'PROBAND')
    field('/context/diseaseId', case['diseaseId'], 'phenotype', 'CONTEXT')
    display = {'schemaVersion': 'denovo-core-display-v1', 'caseKey': case_key,
               'dataClass': review.get('dataClass', 'PUBLIC_PAPER_RESEARCH'),
               'status': 'INSUFFICIENT_DATA' if blockers else 'READY_FOR_EVALUATION', 'criterionDecision': 'NOT_RUN',
               'documentSha256': digest(canonical(case)), 'ledgerSha256': review_pin, 'fields': fields,
               'blockers': blockers, 'coverageLimitations': ['REVIEWED_CASE_AND_CITED_SCOPE_ONLY']}
    reviewed = {'schemaVersion': VERSION, 'caseKey': case_key,
            'documentSha256': display['documentSha256'], 'ledgerSha256': review_pin, 'reviewedBy': review['reviewedBy'],
            'recordSha256': review['recordSha256'], 'identityProof': {key: identity[key] for key in
                ('transcript', 'coding', 'proteinChange', 'gencodeRecordSha256', 'allele', 'reportedAlias') if key in identity},
            'followUpReports': [{'pmcid': r['pmcid'], 'caseLabel': r['caseLabel'], 'sourceSha256': r['sourceSha256']} for r in follow_ups],
            'diseaseId': case['diseaseId'],
            'members': case['members'], 'parentage': case['parentage'], 'phenotype': case['phenotype'],
            'family': case['family'], 'display': display}
    if distinct:
        reviewed['independentOf'] = distinct
    if 'phenotypeNegatives' in case:
        reviewed['phenotypeNegatives'] = case['phenotypeNegatives']
    if conditions:
        reviewed['publishedConditions'] = conditions
        reviewed['identityProof']['ensemblTranscript'] = identity['maneMapping']['Ensembl_nuc']
    return {'caseLabel': case['label'], 'alleleIdentity': {'allele': identity['allele'], 'hgncId': identity['hgncId'],
            'status': 'VERIFIED'}, 'issues': [], 'reviewedCase': reviewed}
