"""Bounded source completion and primary-event provenance; no evidence scoring."""
import re
from extract_literature_cases import table_grid, cell_text, normalized, citation
from literature_case_structure import tables, primary_origin, paragraph_owned, prior_case
from qualify_literature_cases import CASE_TITLE, cite
from model_literature_contract import digest


def phenotype_matrix(document, label):
    """Only explicit patient columns, not aggregate/literature columns or minus-as-normal."""
    native = tables(document)
    labels = {r['caseLabel'] for r in native}
    if label not in labels:
        return []
    found = []
    for table in document['tables']:
        grid = table_grid(table)
        if not grid or normalized(cell_text(grid[0][0])) not in {
                'patients characteristics', 'patient characteristics', 'clinical characteristics', 'clinical features'}:
            continue
        columns = [(i, cell_text(c)) for i, c in enumerate(grid[0]) if i and cell_text(c) in labels]
        if len({v for _, v in columns}) != len(columns) or {v for _, v in columns} != labels:
            continue
        column = next(i for i, v in columns if v == label)
        for row in grid[1:]:
            name, value = cell_text(row[0]), cell_text(row[column])
            if value not in {'+', 'Present', 'present', 'Yes', 'yes'} or not name or not row[0] or not row[column]:
                continue
            # Headings with colspans are not findings. Negated/ambiguous labels need review.
            if row[0]['unitId'] == row[column]['unitId'] or re.search(
                    r'\b(?:not|without|absent|normal|unknown|possible|mother|father|parent|sibling)\b', name, re.I):
                continue
            refs = [citation(document, c) for c in (grid[0][0], grid[0][column], row[0], row[column])]
            found.append((name, refs))
    return found


def event_source(document, label, candidates):
    """Single primary case identity within checked source scope, not worldwide independence."""
    own = []
    for u in document['units']:
        title = CASE_TITLE.fullmatch(u['sections'][-1]) if u['sections'] else None
        if u['kind'] == 'BODY_PARAGRAPH' and ((title and title[1] == label) or paragraph_owned(document, u, label)):
            own.append(u)
    proposals = [p for c in candidates if c['caseLabel'] == label for p in c.get('candidateProvenance', [])]
    contradictory = any(p['caseOriginProposal'] != 'AUTHOR_COHORT' for p in proposals)
    prior = [cite(document, u) for u in own if prior_case(u['text'])]
    # All citations are reconstructed from the pinned source, not accepted from model values.
    origins = []
    for u in document['units']:
        if primary_origin(document, {'caseLabel': label, 'caseOriginProposal': 'AUTHOR_COHORT',
                                     'originCitations': [cite(document, u)]}):
            origins.append(cite(document, u))
    owned = bool(own or any(c['caseLabel'] == label for c in tables(document)))
    issues = []
    if contradictory or not owned or not origins:
        issues.append('DENOVO_CORE_PRIMARY_CASE_NOT_VERIFIED')
    if prior:
        issues.append('DENOVO_CORE_PRIOR_EVENT_RELATION_UNRESOLVED')
    return {'primary': bool(owned and origins and not contradictory), 'originCitations': origins,
            'priorReportCitations': prior, 'checkedCaseUnits': [u['unitId'] for u in own],
            'issues': issues, 'scope': 'CURRENT_PINNED_SOURCE_CASE; CROSS_SOURCE_AND_CURRENT_CASE_RECHECK_REQUIRED'}


def complete_event(doc, source, native, proof, put):
    label = doc['caseKey'].split('#', 1)[1]
    event = event_source(source['main'], label, native['cases'])
    allele = doc['groups']['variant']['resolvedAllele']
    if event['primary']:
        event['origin'] = 'AUTHOR_COHORT'
    if event['primary'] and not event['issues'] and allele['verification'] == 'VERIFIED':
        a = allele['value']
        # Same stable identity as the original Java reader; input/format changes do not invent a new occurrence.
        allele_key = ':'.join(str(a[k]) for k in ('assembly', 'contig', 'position', 'reference', 'alternate'))
        event['eventId'] = 'literature:' + digest((source['pmcid'] + ':' + label + ':' + allele_key).encode())
        event['independence'] = 'UNIQUE_WITHIN_CHECKED_SCOPE'
    proof('event-source', event)
    if event['primary']:
        put('/groups/case/origin', event['origin'], 'event-source', '/origin', 'SOURCE_PRIMARY_CASE_RECHECK')
    if 'eventId' in event:
        put('/groups/overlap/eventId', event['eventId'], 'event-source', '/eventId', 'STABLE_SOURCE_CASE_ALLELE_KEY')
        put('/groups/overlap/independence', event['independence'], 'event-source', '/independence', 'BOUNDED_SOURCE_SCOPE_ONLY')
    return event
