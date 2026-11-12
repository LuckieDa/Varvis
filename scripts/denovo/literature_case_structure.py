"""Source-native case ownership, header scope and explicitly linked footnotes.

No paper/gene allowlist, model calls, genomic mapping or scientific qualification.
These structures supplement the legacy parser without changing old request text.
"""
from copy import deepcopy
import re
import xml.etree.ElementTree as ET

from extract_literature_cases import (extract, table_grid, cell_text, normalized,
                                     citation, finding_state, FIELD_NAMES, CASE_HEADERS)
from qualify_literature_cases import (cite, matches, PARENTAGE_SENTENCES, TRIO_SENTENCES,
                                     ASSUMED_SENTENCES, FAMILY_SENTENCES, CRITICAL_CONTEXT, sentences)
from prepare_literature import prepare

VERSION = 'denovo-case-structure-1.1.0'
LABEL = r'[A-Za-z0-9][A-Za-z0-9_.-]{0,63}'
START = re.compile(r'^(?:Case|Patient|Individual|Proband)\s+(' + LABEL + r')(?=\s|:)', re.I)
# Explicit numbered/alphanumeric identifiers, not ordinary "the proband and".
MENTION = re.compile(r'\b(?i:Case|Patient|Individual|Proband)\s+'
                     r'([A-Z]|[0-9]+|[A-Za-z0-9_.-]*[0-9][A-Za-z0-9_.-]*)(?![\w.-])')
PRIOR = re.compile(r'\b(?:previously (?:reported|described|published)|reported (?:elsewhere|previously)|described by)\b', re.I)


def prior_case(text):
    """A variant's publication history is not the patient's event history."""
    for _, _, sentence in sentences(text):
        if not PRIOR.search(sentence):
            continue
        if re.fullmatch(r'The (?:variation|variant|mutation) was (?:not )?(?:previously reported|reported previously)\.', sentence, re.I):
            continue
        if re.fullmatch(r'(?:This|The) (?:patient|case|proband) (?:was|has) not (?:been )?(?:previously reported|reported previously)\.', sentence, re.I):
            continue
        return True
    return False
SPECIAL_HEADERS = {'cds change': 'variantText',
                   'de novo variants (parental testing)': 'reportedInheritance'}


def paragraph_label(unit):
    """Only a complete, uniquely numbered paragraph, never an inferred neighbour."""
    if unit['kind'] != 'BODY_PARAGRAPH' or any(
            re.search(r'\b(?:discussion|references|methods|background)\b', s, re.I)
            for s in unit['sections']):
        return None
    match = START.match(unit['text'])
    if not match or {m[1] for m in MENTION.finditer(unit['text'])} != {match[1]}:
        return None
    return match[1]


def paragraph_owned(document, unit, label):
    from single_case_scope import owns
    if owns(document, unit, label):
        return True
    matches = [u for u in document['units'] if paragraph_label(u) == label]
    return len(matches) == 1 and matches[0]['unitId'] == unit['unitId']


def case_header(value):
    return normalized(value) in CASE_HEADERS or bool(re.fullmatch(
        r'(?:patient|case|individual|proband)\s*\(method\)', normalized(value)))


def row_label(value, header):
    method_header = normalized(header).endswith('(method)')
    pattern = r'(?:(?:Case|Patient|Individual|Proband)\s+)?(' + LABEL + r')'
    if method_header:
        pattern += r'(?:\s+\([^()]{1,80}\))?'
    match = re.fullmatch(pattern, value, re.I)
    return match[1] if match else None


def tables(document):
    """Add explicit numbered rows and multi-tier headers; retain original cells."""
    legacy = extract(document)
    results = deepcopy(legacy['cases'])
    existing = {c['tableId'] for c in results}
    for table in document['tables']:
        if table['tableId'] in existing:
            continue
        try:
            grid = table_grid(table)
        except ValueError:
            continue
        headers = []
        for r, row in enumerate(grid):
            ids = [c for c, cell in enumerate(row) if cell and cell['header'] and case_header(cell['text'])]
            mapped = [(c, SPECIAL_HEADERS.get(normalized(cell_text(cell)),
                                             FIELD_NAMES.get(normalized(cell_text(cell)))), cell)
                      for c, cell in enumerate(row) if cell and cell['header']]
            mapped = [item for item in mapped if item[1]]
            if len(ids) == 1 and mapped:
                headers.append((r, ids[0], mapped))
        if len(headers) != 1:
            continue
        r, case_column, mapped = headers[0]
        entries = []
        for row in grid[r + 1:]:
            label = row_label(cell_text(row[case_column]), cell_text(grid[r][case_column]))
            if label is None or row[case_column]['header']:
                entries = []
                break
            entries.append((label, row))
        if not entries or len({v[0] for v in entries}) != len(entries):
            continue
        for label, row in entries:
            fields, issues = [], []
            for column, name, header in mapped:
                if row[column] is None:
                    continue
                cell = row[column]
                fields.append({'field': name, 'sourceValue': cell['text'],
                    'findingState': finding_state(cell['text']), 'citation': citation(document, cell),
                    'fieldHeaderCitation': citation(document, header)})
                if name == 'variantText':
                    # A transcript header must physically span the coding column.
                    tx = {(cell_text(grid[h][column]), (grid[h][column] or {}).get('unitId'))
                          for h in range(r) if re.fullmatch(
                              r'Transcript NM_[0-9]+\.[1-9][0-9]*(?: \([^()]+\))?', cell_text(grid[h][column]))}
                    if len(tx) > 1:
                        issues.append('LITERATURE_FIELD_CONFLICT:transcript')
                    elif tx:
                        text, unit_id = next(iter(tx))
                        unit = next(u for u in document['units'] if u['unitId'] == unit_id)
                        value = re.search(r'NM_[0-9]+\.[1-9][0-9]*', text)[0]
                        fields.append({'field': 'transcript', 'sourceValue': value, 'findingState': 'REPORTED',
                                       'citation': cite(document, unit), 'scopeHeaderCitation': citation(document, header)})
            captions = [u for u in document['units'] if u['kind'] == 'CAPTION'
                        and u['unitId'] == table['tableId'] + '/caption[1]']
            if len(captions) == 1:
                gene = re.fullmatch(r'([A-Z][A-Z0-9-]{1,39}) variants identified in (?:the )?cohort', captions[0]['text'])
                if gene:
                    fields.append({'field': 'geneSymbol', 'sourceValue': gene[1], 'findingState': 'REPORTED',
                                   'citation': cite(document, captions[0])})
            case_cell = row[case_column]
            if normalized(cell_text(grid[r][case_column])).endswith('(method)'):
                method = re.search(r'\(([^()]+)\)$', case_cell['text'])
                if method:
                    fields.append({'field': 'reportedVariantTestingMethod', 'sourceValue': method[1],
                                   'findingState': 'REPORTED', 'citation': citation(document, case_cell)})
            results.append({'observationId': document['pmcid'] + table['tableId'] + '#case=' + label,
                'caseLabel': label, 'tableId': table['tableId'], 'caseCitation': citation(document, case_cell),
                'rowUnitIds': sorted({c['unitId'] for c in row if c and c.get('unitId')}),
                'fields': fields, 'issues': issues, 'extractionMethod': VERSION})
    return results


def footnotes(raw, document, case):
    """Resolve only row-local xref IDs to fn nodes in the SAME table."""
    # Revalidate bounded XML and pin before any secondary parse.
    prepare(raw, document['pmcid'], document['sourceSha256'])
    root = ET.fromstring(raw)
    nodes, ids = {}, {}

    def visit(node, path):
        nodes[path] = node
        if node.get('id'):
            ids.setdefault(node.get('id'), []).append(path)
        counts = {}
        for child in node:
            counts[child.tag] = counts.get(child.tag, 0) + 1
            visit(child, path + '/' + child.tag + '[' + str(counts[child.tag]) + ']')
    visit(root, '/article[1]')
    row_units = case.get('rowUnitIds', [f['citation']['unitId'] for f in case['fields'] if f.get('citation')])
    linked, issues = [], []
    for link in document['links']:
        if link['kind'] != 'xref' or not any(link['sourceUnitId'].startswith(p + '/') for p in row_units):
            continue
        if link.get('refType') not in {'table-fn', 'fn'}:
            continue
        for rid in (link.get('rid') or '').split():
            paths = ids.get(rid, [])
            if len(paths) != 1 or nodes[paths[0]].tag != 'fn' or not paths[0].startswith(case['tableId'] + '/'):
                issues.append('LITERATURE_TABLE_FOOTNOTE_TARGET_UNRESOLVED')
                continue
            notes = [u for u in document['units'] if u['unitId'].startswith(paths[0] + '/')
                     and u['kind'] in {'OTHER_TEXT', 'BODY_PARAGRAPH'}]
            if not notes:
                issues.append('LITERATURE_TABLE_FOOTNOTE_TEXT_UNRESOLVED')
            for unit in notes:
                linked.append({'xref': deepcopy(link), 'targetId': rid, 'citation': cite(document, unit)})
    return {'citations': linked, 'issues': sorted(set(issues))}


def primary_origin(document, row):
    """Original clinical observation is distinct from recruitment/independence."""
    if row['caseOriginProposal'] != 'AUTHOR_COHORT':
        return False
    units = {u['unitId']: u for u in document['units']}
    for citation_value in row['originCitations']:
        unit = units.get(citation_value['unitId'])
        if not unit or unit['kind'] != 'BODY_PARAGRAPH' or prior_case(unit['text']):
            continue
        text = citation_value['quote']
        from single_case_scope import primary as single_case_primary
        if text == unit['text'] and single_case_primary(document, unit, row['caseLabel']):
            return True
        if re.search(r'\b(?:not|never|elsewhere)\b', text, re.I):
            continue
        methods = any(re.search(r'\b(?:methods|patients|subjects)\b', s, re.I) for s in unit['sections'])
        retrospective = (re.search(r'\bwe performed a retrospective review of .{1,100}\bpatients\b'
                                    r'.{0,200}\b(?:referred|seen|evaluated|treated|examined)\b', text, re.I)
                         and not re.search(r'\b(?:literature|published|pooled|database)\b', text, re.I))
        if methods and (re.search(r'\b(?:Patients|Subjects|Participants) were recruited\b', text) or retrospective):
            return True
        if paragraph_owned(document, unit, row['caseLabel']):
            # Full case history/examination in the numbered paragraph. "We report"
            # alone or an author/model origin tag alone is insufficient.
            if (re.search(r'\b(?:boy|girl|man|woman|male|female|patient)\b', unit['text'], re.I)
                    and re.search(r'\b(?:examination|examined|presented with)\b', unit['text'], re.I)
                    and re.search(r'\b(?:revealed|identified|sequencing|WES|WGS)\b', unit['text'])):
                return True
    return False


def cohort_parental_testing(document, row, native):
    """Reconcile an exact numbered cohort statement with the selected row.

    Testing for a variant and testing biological relationships remain separate.
    This establishes reported assay facts, not family-context completeness.
    No pronoun resolution, arbitrary 'all patients', or neighbouring gene reuse.
    """
    from literature_allele_identity import one_field
    numbers = {v: i for i, v in enumerate(
        ('zero one two three four five six seven eight nine ten').split())}
    count = r'(?:[1-9][0-9]?|one|two|three|four|five|six|seven|eight|nine|ten)'
    labels = r'[1-9][0-9]*(?:, ?[1-9][0-9]*)*(?:,? and [1-9][0-9]*)?'
    pattern = re.compile(
        r'The parents of (?P<count>' + count + r') of the (?P<total>' + count + r') patients '
        r'\(patients (?P<labels>' + labels + r')\) underwent '
        r'(?P<method>next-generation sequencing or Sanger sequencing|Sanger sequencing|next-generation sequencing) '
        r'to confirm inheritance of the finding\. '
        r'All (?P<results>' + count + r') cases involving parental testing determined the respective '
        r'(?P<gene>[A-Z][A-Z0-9-]{1,39}) alteration to be de novo; '
        r'inheritance of the remaining cases remain unknown\.')

    def number(text):
        return int(text) if text.isdigit() else numbers[text]

    try:
        gene = one_field(row, 'geneSymbol')
        inheritance = one_field(row, 'reportedInheritance')
    except ValueError:
        return {'status': 'UNKNOWN', 'unresolvedContext': []}
    method = re.fullmatch(r'(?:Assumed|Confirmed) de novo\*? \((Sanger sequencing|trio WES|trio WGS)\)', inheritance)
    known_labels = {r['caseLabel'] for r in native if r['tableId'] == row['tableId']}
    claims, unresolved = [], []
    for unit in document['units']:
        if unit['kind'] != 'BODY_PARAGRAPH' or not any(
                s.lower() in {'results', 'case reports', 'clinical report'} for s in unit['sections']):
            continue
        found = []
        for match in pattern.finditer(unit['text']):
            # The full affirmative claim must start at a sentence boundary.
            if match.start() and unit['text'][match.start() - 2:match.start()] not in {'. ', '! ', '? '}:
                continue
            members = re.split(r', ?| and ', match['labels'].replace(', and ', ' and '))
            if (len(members) != len(set(members)) or len(members) != number(match['count'])
                    or number(match['results']) != len(members) or not 0 < len(members) <= number(match['total']) <= 40
                    or number(match['total']) != len(known_labels)
                    or not set(members) <= known_labels or row['caseLabel'] not in members or match['gene'] != gene):
                continue
            found.append(match)
            claims.append({'citation': cite(document, unit, match.start(), match.end()),
                           'members': members, 'methods': match['method']})
        for start, end, sentence in sentences(unit['text']):
            critical = CRITICAL_CONTEXT.search(sentence) or re.search(
                r'\b(?:mothers|fathers|parental|families|contaminat\w*|miscarriages?)\b', sentence, re.I)
            if critical and not any(m.start() <= start and end <= m.end() for m in found):
                unresolved.append(cite(document, unit, start, end))
    conflict = any(re.search(
        r'\b(?:inherited|mosaic(?:ism)?|contaminat\w*|inconclusive|contradicted)\b'
        r'|\b(?:mother|father|parents?)\b[^.!?]{0,100}\b(?:carried|not tested|not assessed|positive)\b',
        c['quote'], re.I) for c in unresolved)
    output = {'status': 'UNKNOWN', 'unresolvedContext': unresolved, 'conflict': conflict}
    if len(claims) != 1 or not method or conflict:
        return output
    expected = 'Sanger sequencing' if method[1] == 'Sanger sequencing' else 'next-generation sequencing'
    if expected not in claims[0]['methods']:
        return output
    field = next(f for f in row['fields'] if f['field'] == 'reportedInheritance')
    output.update(status='PUBLISHED_TARGETED_PARENTAL_TESTING_DENOVO',
                  method={'Sanger sequencing': 'SANGER', 'trio WES': 'TRIO_EXOME', 'trio WGS': 'TRIO_GENOME'}[method[1]],
                  proband='REPORTED_PRESENT', mother='REPORTED_ABSENT', father='REPORTED_ABSENT',
                  citations=[claims[0]['citation'], field['citation']],
                  basis='EXPLICIT_COHORT_TARGET_RESULTS_PLUS_CASE_SPECIFIC_METHOD')
    return output


def table_genomic_observation(document, case):
    """Preserve an explicit same-row GRCh38 SNV; NOT an independently mapped allele."""
    table = next(t for t in document['tables'] if t['tableId'] == case['tableId'])
    grid = table_grid(table)
    headers = []
    names = [('GRCh38', 'GRCh38/hg38'), ('REF',), ('ALT',)]
    for index, row in enumerate(grid):
        columns = [[i for i, c in enumerate(row) if c and c['header'] and c['text'] in group] for group in names]
        if all(len(c) == 1 for c in columns):
            headers.append((index, [c[0] for c in columns]))
    if len(headers) != 1:
        return {'status': 'EXPLICIT_GRCH38_SNV_NOT_RESOLVED'}
    header_index, columns = headers[0]
    selected = [r for r in grid[header_index + 1:] if case['caseCitation']['unitId'] in
                {c['unitId'] for c in r if c}]
    if len(selected) != 1:
        return {'status': 'EXPLICIT_GRCH38_SNV_NOT_RESOLVED'}
    cells = [selected[0][i] for i in columns]
    if any(c is None or c['unitId'] not in case.get('rowUnitIds', []) for c in cells):
        return {'status': 'EXPLICIT_GRCH38_SNV_NOT_RESOLVED'}
    match = re.fullmatch(r'(?:Chr|chr)?([1-9]|1[0-9]|2[0-2]):([1-9][0-9]*)', cells[0]['text'])
    ref, alt = (c['text'] for c in cells[1:])
    if not match or not re.fullmatch('[ACGT]', ref) or not re.fullmatch('[ACGT]', alt) or ref == alt:
        return {'status': 'EXPLICIT_GRCH38_SNV_NOT_RESOLVED'}
    return {'status': 'REPORTED_GRCH38_SNV_REFERENCE_AND_TRANSCRIPT_CHECK_PENDING',
            'allele': {'assembly': 'GRCh38', 'contig': match[1], 'position': int(match[2]),
                       'reference': ref, 'alternate': alt},
            'citations': [citation(document, c) for c in cells],
            'headerCitations': [citation(document, grid[header_index][i]) for i in columns]}


def structural_assertions(source, candidate, native, result):
    """Recover owned facts, but never upgrade undocumented assays to trio Sanger.

    Legacy verified case-section outputs are left alone. New structural facts
    cannot pass the existing scorer until identity, assay and policy checks pass.
    """
    if 'LITERATURE_PRIMARY_CASE_SECTION_UNRESOLVED' not in result['issues']:
        return
    document = source['main']
    label = candidate['caseLabel']
    paragraphs = [u for u in document['units'] if paragraph_label(u) == label]
    rows = [c for c in native if c['caseLabel'] == label]
    if len(paragraphs) == 1 and not rows:
        unit = paragraphs[0]
        result['issues'].remove('LITERATURE_PRIMARY_CASE_SECTION_UNRESOLVED')
        result['caseUnitIds'] = [unit['unitId']]
        result['caseStructure'] = 'EXPLICIT_NUMBERED_PARAGRAPH'
        # Anchor must contain the complete selected pair in the SAME paragraph.
        from literature_allele_identity import one_field, coding, protein
        try:
            gene = one_field(candidate, 'geneSymbol')
            unused, nt = coding(one_field(candidate, 'variantText'))
            aa = protein(one_field(candidate, 'proteinChange'))
            changes = set(re.findall(r'\bc\.[0-9]+(?:_[0-9]+)?(?:[ACGT]>[ACGT]|delins[ACGT]+|del[ACGT]*|dup[ACGT]*|ins[ACGT]+)', unit['text']))
            proteins = {protein(v) for v in re.findall(r'\bp\.\s*([A-Za-z*]+[0-9]+[A-Za-z*]+)', unit['text'])}
            if changes != {nt} or proteins != {aa} or not re.search(
                    r'(?<![\w-])' + re.escape(gene) + r'(?![\w-])', unit['text']):
                result['issues'].append('LITERATURE_CASE_TARGET_LINK_UNRESOLVED')
                return
        except ValueError as error:
            result['issues'].append(str(error))
            return
        result['targetAnchors'] = [cite(document, unit)]
        # The affirmative "neither parent carried" is not a contradictory carrier.
        text = unit['text']
        absence = re.search(r'(?:In addition, )?[Tt]he variant was de novo, as neither parent carried the variant(?: \([A-Z0-9]+\))?\.', text)
        rest = text.replace(absence[0], '') if absence else text
        conflict = re.search(r'\b(?:mosaic(?:ism)?|inconclusive|inherited|contradicted)\b'
                             r'|\b(?:mother|father|parents?)\b[^.!?]{0,80}\b(?:not tested|carried the variant)\b', rest, re.I)
        if PRIOR.search(text):
            result['issues'].append('LITERATURE_PRIOR_PUBLICATION_OVERLAP_UNRESOLVED')
        if conflict:
            result['issues'].append('LITERATURE_SPECIAL_OR_CONFLICT_TEXT_NOT_AUTOMATICALLY_RESOLVED')
        grammars = PARENTAGE_SENTENCES + TRIO_SENTENCES + ASSUMED_SENTENCES + FAMILY_SENTENCES
        unsupported = [cite(document, unit, start, end) for start, end, sentence in sentences(text)
                       if CRITICAL_CONTEXT.search(sentence) and not any(p.fullmatch(sentence) for unused, p in grammars)]
        result['unresolvedContext'] = unsupported
        result['contextVerification'] = 'UNRESOLVED' if unsupported or conflict else 'SUPPORTED_EXPLICIT_FAMILY_STATEMENTS'
        if unsupported or conflict:
            result['pendingChecks'].append('LITERATURE_UNRECOGNIZED_CLINICAL_NARRATIVE_NOT_VERIFIED')
        if absence and not conflict and not any('CONFLICT' in i for i in result['issues']):
            result['reportedParentalAbsence'] = {
                'status': 'BOTH_PARENTS_REPORTED_ABSENT_METHOD_NOT_ESTABLISHED',
                'citation': cite(document, unit, absence.start(), absence.end()),
                'meaning': 'Reported absence, not proof of assay method, biological parentage or scientific adoption.'}
        trio = matches(document, [unit], TRIO_SENTENCES)
        parentage = matches(document, [unit], PARENTAGE_SENTENCES)
        assumed = matches(document, [unit], ASSUMED_SENTENCES)
        if trio and not conflict and not any('CONFLICT' in i for i in result['issues']):
            result['targetTesting'] = {'status': 'PUBLISHED_TRIO_SANGER_DENOVO', 'method': 'SANGER',
                'proband': 'PRESENT', 'mother': 'REPORTED_ABSENT', 'father': 'REPORTED_ABSENT', 'citations': trio}
        else:
            result['issues'].append('LITERATURE_PARENTAL_TARGET_METHOD_NOT_VERIFIED')
        if parentage and assumed:
            result['parentage'] = {'status': 'CONFLICT', 'citations': parentage + assumed}
            result['issues'].append('LITERATURE_PARENTAGE_CONFLICT')
        elif parentage:
            result['parentage'] = {'status': 'PUBLISHED_BOTH_PARENTS_CONFIRMED', 'citations': parentage}
        elif assumed and len({v['rule'] for v in assumed}) == 1:
            status = ('PUBLISHED_PARENTAGE_NOT_TESTED' if assumed[0]['rule'] == 'EXPLICIT_PARENTAGE_NOT_TESTED'
                      else 'PUBLISHED_PARENTAGE_NOT_REPORTED')
            result['parentage'] = {'status': status, 'citations': assumed}
        else:
            result['issues'].append('LITERATURE_PARENTAGE_NOT_RESOLVED')
    elif len(rows) == 1 and not paragraphs:
        row = rows[0]
        result['issues'].remove('LITERATURE_PRIMARY_CASE_SECTION_UNRESOLVED')
        result['caseStructure'] = 'EXPLICIT_TABLE_ROW'
        result['caseUnitIds'] = row.get('rowUnitIds', [])
        notes = footnotes(source['raw'], document, row)
        result['linkedFootnotes'] = notes['citations']
        result['issues'].extend(notes['issues'])
        negative = [n for n in notes['citations'] if re.fullmatch(
            r'Assumed de novo as (?:paternity and maternity|maternity and paternity) were not confirmed\.',
            n['citation']['quote'])]
        other_parentage = [n for n in notes['citations'] if n not in negative and re.search(
            r'\b(?:parentage|paternity|maternity)\b', n['citation']['quote'], re.I)]
        if negative and not notes['issues'] and not other_parentage:
            result['parentage'] = {'status': 'PUBLISHED_PARENTAGE_NOT_CONFIRMED',
                'citations': negative, 'meaning': 'Not confirmed is not a claim that testing was never performed.'}
            result['pendingChecks'].append('LITERATURE_PARENTAGE_UNCONFIRMED_SOURCE_STATUS_REQUIRES_POLICY')
        elif other_parentage:
            result['issues'].append('LITERATURE_PARENTAGE_CONFLICT_OR_UNSUPPORTED_FOOTNOTE')
        else:
            result['issues'].append('LITERATURE_PARENTAGE_NOT_RESOLVED')
        result['targetTableFacts'] = [
            deepcopy(f) for f in row['fields'] if f['field'] in {
                'reportedInheritance', 'reportedVariantTestingMethod', 'geneSymbol', 'variantText',
                'proteinChange', 'transcript'}]
        assay = cohort_parental_testing(document, row, native)
        result['reportedGenomicObservation'] = table_genomic_observation(document, row)
        result['unresolvedContext'] = assay.pop('unresolvedContext')
        if assay.pop('conflict', False):
            result['issues'].append('LITERATURE_SPECIAL_OR_CONFLICT_TEXT_NOT_AUTOMATICALLY_RESOLVED')
        if assay['status'] != 'UNKNOWN' and not notes['issues'] and not other_parentage:
            result['targetTesting'] = assay
        else:
            result['issues'].append('LITERATURE_PARENTAL_TARGET_RESULTS_NOT_VERIFIED')
        if result['unresolvedContext']:
            result['issues'].append('LITERATURE_FAMILY_CONTEXT_ATTRIBUTION_UNRESOLVED')
        result['contextVerification'] = 'UNRESOLVED'
        result['pendingChecks'].append('LITERATURE_COMPLETE_FAMILY_CONTEXT_NOT_VERIFIED')
    elif len(paragraphs) > 1 or len(rows) > 1:
        result['issues'].append('LITERATURE_CASE_COHORT_AMBIGUOUS')
