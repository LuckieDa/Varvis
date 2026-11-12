"""PM3 table header paths from original grid coordinates, never from cell values.

Source units/quotes stay byte-bound and unchanged. Only explicit header spans and
line breaks are interpreted; ambiguous qualifiers are retained, not stripped.
Body merges still cannot establish a case. No disease or paper allowlist.
"""
import re
import xml.etree.ElementTree as ET
from extract_literature_cases import table_grid
from prepare_literature import text


def source_tree(source):
    return ET.fromstring(source['raw']) if source.get('raw') and not source.get('publisherReader') else None


def native_lines(cell, tree):
    if tree is None:
        return [cell['text'].strip()]
    uid = cell['unitId']
    if not uid.startswith('/article[1]/'):
        raise ValueError('PM3_TABLE_HEADER_SOURCE_MISMATCH')
    node = tree.find('./' + uid.removeprefix('/article[1]/'))
    if node is None or text(node) != cell['text']:
        raise ValueError('PM3_TABLE_HEADER_SOURCE_MISMATCH')
    if node.get('scope') not in (None, 'col', 'colgroup'):
        raise ValueError('PM3_TABLE_HEADER_ASSOCIATION_UNSUPPORTED')
    parts = []

    def visit(n):
        if n.tag in ('break', 'p'):
            parts.append('\n')
        parts.append(n.text or '')
        for child in n:
            visit(child)
            parts.append(child.tail or '')
        if n.tag == 'p':
            parts.append('\n')
    visit(node)
    return [' '.join(line.split()) for line in ''.join(parts).split('\n') if line.strip()]


def compose(segments):
    # Interpret only full paths. A parent qualifier (e.g. Mother / Variant)
    # cannot be silently dropped even if the leaf alone looks familiar.
    from pm3_case_semantics import (VARIANT_HEADING, GENE_HEADING, ZYGOSITY_HEADING,
        TRANSCRIPT_HEADING, DIAGNOSIS_HEADING, AFFECTED_HEADING, PARENT_TEST_HEADING)
    patterns = (VARIANT_HEADING, GENE_HEADING, ZYGOSITY_HEADING, TRANSCRIPT_HEADING,
                DIAGNOSIS_HEADING, AFFECTED_HEADING, PARENT_TEST_HEADING)
    joined = ' '.join(segments)
    if any(p.fullmatch(joined) for p in patterns) or re.fullmatch(
            r'(?:case|patient|proband|subject|individual)(?: (?:id|no\.?|number))?|pt\s*id|family(?: id)?', joined, re.I):
        return joined, 'RESOLVED'
    if len(segments) == 2:
        parent, leaf = segments
        # A typed naming column is an allele only within an explicit detected
        # mutation/variant group. FI/MI, activity and inferred/predicted groups
        # retain their own meaning; full source headings remain in the proof.
        if re.fullmatch(r'detected (?:mutation|variant)', parent, re.I) and re.fullmatch(
                r'systematic name|HGVS(?: name)?', leaf, re.I):
            return 'Variant', 'RESOLVED'
        singular = {'variants': 'Variant', 'mutations': 'Mutation', 'alleles': 'Allele'}
        if parent.lower() in singular and re.fullmatch('[12ab]', leaf, re.I):
            return singular[parent.lower()] + ' ' + leaf, 'RESOLVED'
        if parent.lower() in {'genotype', 'genotypes', 'variants', 'mutations', 'alleles'} and VARIANT_HEADING.fullmatch(leaf):
            return leaf, 'RESOLVED'
    return joined, 'AMBIGUOUS' if len(segments) > 1 else 'UNRECOGNIZED'


def layout(table, tree=None):
    grid = table_grid(table)
    if not grid:
        return [], []
    rows = table['rows']
    # Thead boundaries are explicit even when publishers use td in thead.
    in_head = [all('/thead[' in c.get('unitId', '') for c in r['cells']) and bool(r['cells']) for r in rows]
    if any(in_head):
        count = sum(in_head)
        if in_head != [True] * count + [False] * (len(rows) - count):
            raise ValueError('PM3_TABLE_HEADER_LAYOUT_UNRESOLVED')
    else:
        count = 0
        for row in rows:
            if not row['cells'] or not all(c.get('header') for c in row['cells']):
                break
            count += 1
        count = max(1, count)  # Historical flat tables with td headings.
    if count >= len(grid):
        return [], []
    # Explicit alternative associations cannot be replaced by visual adjacency.
    if tree is not None:
        for row in rows:
            for cell in row['cells']:
                node = tree.find('./' + cell['unitId'].removeprefix('/article[1]/'))
                if node is not None and node.get('headers') is not None:
                    raise ValueError('PM3_TABLE_HEADER_ASSOCIATION_UNSUPPORTED')
    for r, row in enumerate(rows[:count]):
        if any(r + c['rowspan'] > count for c in row['cells']):
            raise ValueError('PM3_TABLE_HEADER_SPAN_CROSSES_BODY')
    if any(c is None for row in grid[:count] for c in row):
        raise ValueError('PM3_TABLE_HEADER_LAYOUT_UNRESOLVED')
    headers = []
    for col in range(len(grid[0])):
        path, seen = [], set()
        for r in range(count):
            cell = grid[r][col]
            if cell['unitId'] in seen:
                continue
            seen.add(cell['unitId'])
            path.append({'unitId': cell['unitId'], 'quote': cell['text'],
                         'lines': native_lines(cell, tree),
                         'rowspan': cell['rowspan'], 'colspan': cell['colspan']})
        segments = [line for c in path for line in c['lines']]
        heading, status = compose(segments)
        proof = {'method': 'SOURCE_GRID_HEADER_PATH', 'status': status,
                 'effectiveHeading': heading, 'sourceHeadings': path}
        if status == 'AMBIGUOUS':
            proof['reasonCode'] = 'PM3_TABLE_HEADER_MEANING_AMBIGUOUS'
        headers.append({'unitId': path[-1]['unitId'], 'text': heading, 'headingEvidence': proof})
    body = [row for row in grid[count:] if not any(c and '/tfoot[' in c['unitId'] for c in row)]
    return headers, body
