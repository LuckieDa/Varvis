"""Bounded DOCX case links and literal facts, not clinical qualification.

Require the original main case plus matching gene/coding identity in the same
paragraph or explicit table row. No row-number inference, inherited neighbour,
model vote, ACMG-label scoring or inferred parental result.
"""
from collections import defaultdict
import re

from literature_case_structure import tables, MENTION
from bundle_model_contract import cite
from literature_allele_identity import coding as coding_alias, protein

CDNA = re.compile(r'(?<!\w)c\.[0-9]+(?:[+-][0-9]+)?(?:_[0-9]+)?(?:[ACGT]>[ACGT]|delins[ACGT]+|del[ACGT]*|dup[ACGT]*|ins[ACGT]+)(?!\w)')
PROTEIN = re.compile(r'(?<!\w)p\.[A-Za-z*]+[0-9]+[A-Za-z*]+(?!\w)')
START = re.compile(r'^(?:Patient|Case|Individual|Proband)\s+([A-Za-z0-9_.-]+):\s*', re.I)
UNSAFE = re.compile(r'\b(?:no|not|without|absent|possible|possibly|may|might|mother|father|parents?|siblings?|another|previously)\b', re.I)


def scope(source, label):
    result = {'ownedUnitIds': [], 'anchorCitations': [], 'issues': [], 'authorClassificationUnitIds': [],
              'scope': 'EXPLICIT_DOCX_CASE_AND_SAME_MAIN_GENE_CODING_IDENTITY'}
    native = [r for r in tables(source['main']) if r['caseLabel'] == label]
    if len(native) != 1:
        result['issues'].append('SUPPLEMENT_MAIN_CASE_ANCHOR_UNRESOLVED'); return result
    fields = native[0]['fields']
    values = lambda name: {f['sourceValue'] for f in fields if f['field'] == name and f['findingState'] == 'REPORTED'}
    genes, variants, transcripts = values('geneSymbol'), values('variantText'), values('transcript')
    if len(genes) != 1 or len(variants) != 1:
        result['issues'].append('SUPPLEMENT_MAIN_ALLELE_ANCHOR_UNRESOLVED'); return result
    gene = next(iter(genes))
    try:
        unused_tx, coding = coding_alias(next(iter(variants)))
    except ValueError:
        result['issues'].append('SUPPLEMENT_MAIN_ALLELE_ANCHOR_UNRESOLVED'); return result
    proteins = {protein(v) for v in values('proteinChange')}
    result.update(gene=gene, coding=coding, mainCaseCitation=native[0]['caseCitation'])
    units = [u for u in source['units'] if u.get('sourcePart') == 'word/document.xml']
    if len(units) > 4096 or sum(len(u['text'].encode()) for u in units) > 1048576:
        result['issues'].append('SUPPLEMENT_CASE_SCOPE_LIMIT'); return result

    def own(group, anchors):
        result['ownedUnitIds'].extend(u['unitId'] for u in group)
        result['anchorCitations'].extend(cite(source, {'unitId': u['unitId'], 'quote': u['text']}) for u in anchors if u['text'])

    paragraphs = [u for u in units if u['kind'] == 'SUPPLEMENT_PARAGRAPH'
                  and (m := START.match(u['text'])) and m[1] == label]
    by_file = defaultdict(list)
    for u in paragraphs: by_file[u['sourceSha256']].append(u)
    for group in by_file.values():
        if len(group) != 1:
            result['issues'].append('SUPPLEMENT_CASE_PARAGRAPH_AMBIGUOUS'); continue
        u = group[0]
        coding_match = CDNA.search(u['text'])
        lead = re.split(r'[.!?]\s+', u['text'][:coding_match.start()])[-1] if coding_match else ''
        if ({m[1] for m in MENTION.finditer(u['text'])} != {label}
                or set(CDNA.findall(u['text'])) != {coding}
                or (proteins and any(protein(v) not in proteins for v in PROTEIN.findall(u['text'])))
                or UNSAFE.search(lead)
                or not re.search(r'(?<![\w-])' + re.escape(gene) + r' (?:variant|mutation)\b', u['text'])):
            result['issues'].append('SUPPLEMENT_CASE_OR_ALLELE_NOT_MATCHED'); continue
        own([u], [u])
    grouped = defaultdict(list)
    for u in units:
        if u['kind'] == 'SUPPLEMENT_CELL': grouped[(u['sourceSha256'], u['sheet'])].append(u)
    for cells in grouped.values():
        rows = defaultdict(dict)
        for u in cells:
            m = re.fullmatch(r'R([1-9][0-9]*)C([1-9][0-9]*)', u['cell'])
            if m: rows[int(m[1])][int(m[2])] = u
        if not rows: continue
        header = rows[min(rows)]
        case_cols = [col for col, u in header.items() if u['text'].strip().casefold() in
                     {'patient id', 'case id', 'individual id', 'patient', 'case', 'individual'}]
        if len(case_cols) != 1: continue
        case_col = case_cols[0]
        matches = [row for row in rows.values() if row.get(case_col, {}).get('text', '').strip() in
                   {label, 'Case '+label, 'Patient '+label, 'Individual '+label}]
        if len(matches) != 1:
            if matches: result['issues'].append('SUPPLEMENT_CASE_ROW_AMBIGUOUS')
            continue
        row = matches[0]
        if any(u.get('gridSpan', 1) != 1 or u.get('verticalMerge') for u in [*header.values(), *row.values()]):
            result['issues'].append('SUPPLEMENT_CASE_MERGE_UNRESOLVED'); continue
        cols = [col for col, u in header.items() if re.fullmatch(re.escape(gene) + r'(?:\s+\((NM_[0-9]+\.[0-9]+)\))?', u['text'].strip())]
        if len(cols) != 1 or cols[0] not in row: continue
        col = cols[0]; allele = row[col]; tx = re.findall(r'NM_[0-9]+\.[0-9]+', header[col]['text'])
        if (set(CDNA.findall(allele['text'])) != {coding} or UNSAFE.search(allele['text'])
                or (tx and transcripts and set(tx) != transcripts)
                or (proteins and any(protein(v) not in proteins for v in PROTEIN.findall(allele['text'])))):
            result['issues'].append('SUPPLEMENT_CASE_OR_ALLELE_NOT_MATCHED'); continue
        own([row[case_col], allele], [header[case_col], row[case_col], header[col], allele])
        # Statements under an ACMG/classification heading are author rationale,
        # not independently established clinical observations.
        result['authorClassificationUnitIds'].extend(u['unitId'] for c, u in row.items()
            if c in header and re.search(r'ACMG|classification|pathogenicity', header[c]['text'], re.I))
    result['ownedUnitIds'] = sorted(set(result['ownedUnitIds']))
    return result


def proofs(source, label):
    checked = scope(source, label); result = {}
    units = {u['unitId']: u for u in source['units']}
    def add(path, value, unit, quote, method):
        refs = [cite(source, {'unitId': unit['unitId'], 'quote': quote}), *checked['anchorCitations']]
        result.setdefault('/groups/' + path, []).append({'value': value, 'citations': refs,
            'method': method, 'subject': 'PROBAND', 'availability': 'REPORTED'})
    for uid in checked['ownedUnitIds']:
        u = units[uid]; text = u['text']
        for value in CDNA.findall(text) + PROTEIN.findall(text):
            if text.count(value) == 1:
                add('variant/representations', [value], u, value, 'SUPPLEMENT_EXACT_CASE_ALLELE_LITERAL')
        if u['kind'] != 'SUPPLEMENT_PARAGRAPH': continue
        opening = START.match(text)
        age = re.match(r'(?:A |An |The patient was a |The proband was a )?([0-9]+(?:\.[0-9]+)?-(?:year|month|day)-old)'
                       r'\s+(?:(?!mother\b|father\b|sibling\b)[A-Za-z-]+\s+)?(?:male|female|child|boy|girl|infant|patient|proband)\b',
                       text[opening.end():], re.I)
        if age:
            add('phenotype/age', age[1], u, text[:opening.end()+age.end()], 'SUPPLEMENT_EXPLICIT_CASE_AGE')
        # Facts about assays, parents, diagnosis and onset remain separately
        # reviewed. Sanger verification is not trio testing or parentage.
    return result, checked
