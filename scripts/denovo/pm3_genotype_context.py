"""Bind an explicit single homozygous genotype to its unique cited case row.

No homozygosity from repeated strings alone, no phase from column order, and no
independence from the case number. The paragraph, xref, row and both allele cells
remain in the proof. The caller still performs genomic identity and disease checks.
"""
import re
from copy import deepcopy
from bundle_model_contract import cite
from pm3_table_headers import layout, source_tree

ALLELE = r'c\.[1-9][0-9]*[ACGT]>[ACGT]'
CLAIM = re.compile(r'\bone(?: (?:patient|proband|individual))? was homozygous '
                   r'\((' + ALLELE + r');\s*(' + ALLELE + r')\)\.')


def facts(source, scope):
    from pm3_observations import pm3_case_header, pm3_row_label
    from pm3_case_semantics import VARIANT_HEADING
    tree = source_tree(source)
    if tree is None:
        return []
    result = []
    for table in source['main']['tables']:
        tid = table.get('xmlId')
        if not tid or sum(t.get('id') == tid for t in tree.iter('table-wrap')) != 1:
            continue
        container = 'main:' + table['tableId']
        owned = {k:v for k,v in scope.items() if v.get('container') == container
                 and v.get('method') == 'EXPLICIT_UNIQUE_CASE_ROW'}
        anchors = {v['anchorUnitId'] for v in owned.values()}
        if len(anchors) != 1:
            continue
        try:
            headers, rows = layout(table, tree)
        except ValueError:
            continue
        allele_columns = [i for i,h in enumerate(headers) if VARIANT_HEADING.fullmatch(h['text'])
                          and h.get('headingEvidence', {}).get('status') == 'RESOLVED']
        case_columns = [i for i,h in enumerate(headers) if pm3_case_header(h['text'])]
        if len(allele_columns) != 2 or len(case_columns) != 1:
            continue
        claims = []
        for unit in source['main']['units']:
            if unit['kind'] != 'BODY_PARAGRAPH':
                continue
            text = unit['text']
            matches = list(CLAIM.finditer(text))
            if len(matches) != 1 or matches[0][1] != matches[0][2]:
                continue
            # Current-study language and an exact native xref are both needed.
            if not re.search(r'\bin our cohort\b', text) or re.search(
                    r'\b(?:previous|published|control|unaffected|suspected|possible|not)\b',
                    text[:matches[0].end()], re.I):
                continue
            node = tree.find('./' + unit['unitId'].removeprefix('/article[1]/'))
            if node is None or not any(x.get('ref-type') == 'table' and x.get('rid') == tid
                                       for x in node.iter('xref')):
                continue
            claims.append((unit, matches[0]))
        if len(claims) != 1:
            continue
        unit, claim = claims[0]
        matches = []
        for row in rows:
            if len(row) != len(headers) or any(c is None for c in row):
                continue
            if any(c.get('rowspan',1) != 1 or c.get('colspan',1) != 1 for c in row):
                continue
            pair = [row[i] for i in allele_columns]
            if all(c['text'].strip() == claim[1] for c in pair):
                matches.append((row[case_columns[0]], pair))
        if len(matches) != 1 or matches[0][0]['unitId'] not in anchors:
            continue
        anchor, pair = matches[0]
        label = pm3_row_label(anchor['text'], headers[case_columns[0]]['text'])
        if not label:
            continue
        citations = [cite(source, {'unitId':u['unitId'], 'quote':u['text']})
                     for u in [unit, anchor, *pair]]
        result.append({'field':'reportedZygosity', 'sourceValue':'homozygous',
            'subject':'PROBAND', 'issues':[], 'producer':'SOURCE_NATIVE_UNIQUE_HOMOZYGOUS_GENOTYPE',
            'status':'CASE_OWNED_SEMANTICS_PENDING', 'citation':citations[0],
            'supportingCitations':citations[1:],
            'ownership':{'method':'EXPLICIT_SINGLE_HOMOZYGOUS_GENOTYPE_ROW',
                'container':container, 'anchorUnitId':anchor['unitId'], 'caseLabel':label,
                'reportedCoding':claim[1], 'tableXmlId':tid,
                'proof':{'citations':deepcopy(citations), 'uniqueMatchingRow':True,
                         'explicitTableXref':True, 'independenceEstablished':False}}})
    return result


def accepts(fact, label):
    own = fact.get('ownership') or {}
    return (own.get('method') == 'EXPLICIT_SINGLE_HOMOZYGOUS_GENOTYPE_ROW'
        and own.get('caseLabel') == label and fact.get('field') == 'reportedZygosity'
        and fact.get('sourceValue') == 'homozygous' and fact.get('subject') == 'PROBAND'
        and fact.get('producer') == 'SOURCE_NATIVE_UNIQUE_HOMOZYGOUS_GENOTYPE'
        and not fact.get('issues') and len(own.get('proof',{}).get('citations',[])) == 4
        and own.get('proof',{}).get('uniqueMatchingRow') is True
        and own.get('proof',{}).get('explicitTableXref') is True)
