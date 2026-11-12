"""Small extraction targets with original row/section context, not byte slices.

Every source unit is a target exactly once. Context can repeat; it is not a new
case. These structural groups do not determine a clinical subject or diagnosis.
"""
from collections import defaultdict
import re

VERSION = 'denovo-literature-work-units-1.0.0'


def row_number(cell):
    match = re.fullmatch(r'(?:[A-Z]{1,3}|R)([1-9][0-9]*)(?:C[1-9][0-9]*)?', cell)
    if not match:
        raise ValueError('DENOVO_BUNDLE_CELL_ADDRESS_INVALID')
    return int(match[1])


def plans(source):
    units = source['units']
    by_id = {u['unitId']: u for u in units}
    positions = {u['unitId']: i for i, u in enumerate(units)}
    shared = [u['unitId'] for u in units if u['kind'] in {'ABSTRACT', 'CAPTION'} or
              (u['kind'] == 'BODY_PARAGRAPH' and any(re.search(r'\bmethods?\b', s, re.I)
                                                    for s in u.get('sections', [])))]
    if any(u.get('sourcePart')=='pdf' for u in units):
        # The paragraph introducing a supplement can contain its gene/cohort
        # context even when it is in Results rather than Methods. These are
        # context candidates, not a native proof of case ownership.
        shared += [u['unitId'] for u in units if u['kind']=='BODY_PARAGRAPH' and re.search(
            r'\b(?:supplementary|supplemental|additional)\s+(?:file|table|material|data)\b',u['text'],re.I)]
    groups, assigned = [], set()
    def add(target, context, kind, structure=None):
        # Preserve original source order and exact source units, including blank cells.
        target = sorted(set(target), key=positions.__getitem__)
        context = sorted(set(context) - set(target), key=positions.__getitem__)
        groups.append({'targetUnitIds': target, 'contextUnitIds': context,
                       'kind': kind, 'structure': structure or {},
                       'targetTextCharacters': sum(len(by_id[i]['text']) for i in target)})
        assigned.update(target)
    def cell_ids(rows):
        result=[]
        for row in rows:
            for cell in row['cells']:
                if cell['unitId'] in positions:
                    result.append(cell['unitId'])
                elif cell['text'].strip():
                    raise ValueError('DENOVO_BUNDLE_TABLE_SOURCE_UNIT_MISSING')
                # Original JATS reader has no citation unit for empty cells;
                # retain them in structural rows, never invent a quote target.
        return result
    def note_ids(targets):
        result = []
        for uid in targets:
            unit = by_id[uid]
            if unit.get('sourcePart') == 'pdf':
                pdf_text = [v for v in units if v['sourceSha256'] == unit['sourceSha256']
                            and v.get('archiveMemberName') == unit.get('archiveMemberName')
                            and v.get('pdfTextOnly')]
                # Small native-text supplements fit as context in full. Larger
                # documents retain the complete current page, never a silently
                # truncated line. Context coverage remains explicit in requests.
                if sum(len(v['text']) for v in pdf_text) > 15000 or len(pdf_text) > 96:
                    pages = sorted({v.get('page') for v in pdf_text})
                    local = [v for v in pdf_text if v.get('page') == unit.get('page')]
                    edges = []
                    if len(pages) <= 10:
                        for page in pages:
                            page_units = [v for v in pdf_text if v.get('page') == page]
                            edges.extend(page_units[:3] + page_units[-4:])
                    proposed = {v['unitId']: v for v in local + edges}
                    pdf_text = list(proposed.values()) if len(proposed) <= 96 else local
                result.extend(v['unitId'] for v in pdf_text)
            for ref in unit.get('noteReferences', []):
                kind = ref['kind'].removesuffix('Reference')
                path = f'/{kind}s/{kind}[@id={ref["id"]}]/'
                result.extend(v['unitId'] for v in units if v['sourceSha256'] == unit['sourceSha256']
                              and v.get('archiveMemberName') == unit.get('archiveMemberName')
                              and v.get('sourcePart') == 'word/' + kind + 's.xml'
                              and v.get('sourcePath', '').startswith(path))
        return result
    for table in source['main']['tables']:
        rows = table['rows']
        headers = [c['unitId'] for r in rows for c in r['cells'] if c.get('header') and c['unitId'] in positions]
        if not headers:
            headers = cell_ids(rows[:3])
        for i, row in enumerate(rows):
            target = cell_ids([row])
            if not target:
                continue
            neighbouring = cell_ids(rows[max(0, i-1):i+2])
            add(target, shared+headers+neighbouring, 'MAIN_TABLE_ROW',
                {'tableId': table['tableId'], 'row': row['sourceRowIndex'],
                 'rows': rows[max(0, i-1):i+2], 'headerUnitIds': headers,
                 'headerMeaning': 'SOURCE_MARKUP_OR_LEADING_ROWS_NOT_SUBJECT_PROOF'})
    sheets = defaultdict(list)
    for u in units:
        if u['kind'] == 'SUPPLEMENT_CELL':
            sheets[(u['sourceSha256'], u.get('archiveMemberName'), u.get('archiveMemberSha256'), u['sheet'])].append(u)
    for (pin, member_name, member_pin, sheet), cells in sheets.items():
        rows = defaultdict(list)
        for u in cells:
            rows[row_number(u['cell'])].append(u['unitId'])
        numbers = sorted(rows)
        leading = [uid for number in numbers[:3] for uid in rows[number]]
        trailing = rows[numbers[-1]]
        for i, number in enumerate(numbers):
            neighbours = [uid for n in numbers[max(0,i-1):i+2] for uid in rows[n]]
            add(rows[number], shared+leading+trailing+neighbours+note_ids(rows[number]), 'SUPPLEMENT_ROW',
                {'sourceSha256': pin, 'sheet': sheet, 'row': number,
                 **({'archiveMemberName': member_name, 'archiveMemberSha256': member_pin} if member_pin else {}),
                 'leadingRowUnitIds': leading,
                 'headerMeaning': 'LEADING_ROWS_PRESERVED_NOT_AUTOMATIC_HEADER_OR_CASE_ASSERTION'})
    for i, u in enumerate(units):
        if u['unitId'] in assigned:
            continue
        same_section = [v['unitId'] for v in units if v['kind'] == 'BODY_PARAGRAPH' and
                        u.get('sections') and v.get('sections') == u['sections']]
        neighbours = [v['unitId'] for v in units[max(0, i-1):i+2]]
        notes = []
        if u.get('sourcePart'):
            # Keep same-file prose together; never attribute neighbouring cases.
            neighbours = [v['unitId'] for v in units[max(0, i-1):i+2]
                          if v['sourceSha256'] == u['sourceSha256']
                          and v.get('archiveMemberName') == u.get('archiveMemberName')]
            notes = note_ids([u['unitId']])
        add([u['unitId']], shared+same_section+neighbours+notes, 'TEXT_UNIT')
    groups.sort(key=lambda group: min(positions[i] for i in group['targetUnitIds']))
    assert len(assigned) == len(units)
    return groups
