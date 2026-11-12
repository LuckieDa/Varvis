"""Bounded, offline BS1 parameter extraction. Outputs are never runtime policies.

Only explicit label/value grammar is parsed. Fractions are exact; full clauses
and code-point spans remain authoritative. A complete set is a review proposal,
not evidence that its epidemiology, denominators or scientific use are sound.
"""
from collections import Counter
from fractions import Fraction
import re

from bs1_parameters import FIELDS, MODE_NAMES, identity, specification_clauses

VERSION = 'bs1-parameter-text-v1'
MAX_TEXT = 100000
PREFIX = r'(?:max(?:imum)?\s+)?'
CONTRIBUTION = r'(?:heterogeneity|contribution)'
LABELS = {
    'prevalence': r'\bprevalence\b',
    'penetrance': r'\bpenetrance\b',
    'maximumGeneContribution': rf'\b{PREFIX}(?:genetic|gene)\s+{CONTRIBUTION}\b',
    'maximumAlleleContribution': rf'\b{PREFIX}(?:allelic|allele)\s+{CONTRIBUTION}\b',
}
PAIR = re.compile(rf'\b{PREFIX}(?P<first>genetic|gene|allelic|allele)\s+and\s+'
                  rf'{PREFIX}(?P<second>genetic|gene|allelic|allele)\s+{CONTRIBUTION}\b', re.I)
CONNECTOR = re.compile(r'\s*(?:estimate\s+)?(?:(?:was|were|is|are)\s+)?'
                       r'(?:(?:estimated|set|assumed)\s+)?(?:(?:at|of|as|to)\s+|[=:\-]\s*)?', re.I)
NUMBER = r'(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)?'
# An atom must not stop in the middle of a malformed number or scientific notation.
ATOM = re.compile(rf'(?P<n>{NUMBER})(?:\s*(?P<ratio>/|:|\bin\b)\s*(?P<d>{NUMBER})'
                  r'|\s*(?P<percent>%))?(?![0-9A-Za-z%/:]|[.,][0-9])', re.I)
QUALIFIER = re.compile(r'\s*(?P<q>approximately\b|approx\.?\b|about\b|~|≈|<=|>=|<|>|≤|≥)?\s*', re.I)
RANGE = re.compile(r'\s*(?:–|—|-|\bto\b)\s*(?=[0-9])', re.I)
NEGATION = re.compile(r'\b(?:not|no|without|unknown|unavailable)\b', re.I)
UNSUPPORTED_TAIL = re.compile(r'\s*(?:\bor\b|\band\s+[0-9]|[+*/=±]|(?:x|times)\s+[0-9]|'
                              r'per\s+[0-9]|(?:[–—-]|\bto\b)\s*[0-9~≈<>≤≥-]|[<>≤≥])', re.I)


def packed(value):
    return {'numerator': str(value.numerator), 'denominator': str(value.denominator)}


def labels(text):
    found = []
    paired = []
    for match in PAIR.finditer(text):
        fields = tuple(dict.fromkeys('maximumGeneContribution' if word.lower() in {'genetic', 'gene'}
                                     else 'maximumAlleleContribution'
                                     for word in (match['first'], match['second'])))
        paired.append((match.start(), match.end(), fields))
    found.extend(paired)
    for field, pattern in LABELS.items():
        for match in re.finditer(pattern, text, re.I):
            if not any(start <= match.start() < end for start, end, _ in paired):
                found.append((match.start(), match.end(), (field,)))
    found.sort()
    combined = []
    for start, end, fields in found:
        # "maximum allelic contribution and maximum genetic contribution at 100%"
        if (combined and re.fullmatch(r'\s+and\s+', text[combined[-1][1]:start], re.I)
                and all(f.startswith('maximum') for f in combined[-1][2] + fields)):
            old = combined.pop()
            combined.append((old[0], end, tuple(dict.fromkeys(old[2] + fields))))
        else:
            combined.append((start, end, fields))
    return combined


def atom(text, start):
    match = ATOM.match(text, start)
    if not match or len(match.group()) > 120:
        raise ValueError('NUMERIC_GRAMMAR_UNSUPPORTED')
    try:
        value = Fraction(match['n'].replace(',', ''))
        unit = 'fraction'
        if match['ratio']:
            value /= Fraction(match['d'].replace(',', ''))
            unit = 'ratio'
        elif match['percent']:
            value /= 100
            unit = 'percent'
    except (ValueError, ZeroDivisionError):
        raise ValueError('NUMERIC_VALUE_INVALID') from None
    return value, unit, match.end()


def numeric(text, start):
    qualifier = QUALIFIER.match(text, start)
    value, unit, end = atom(text, qualifier.end())
    q = (qualifier['q'] or '').lower()
    result = {'kind': 'POINT', 'unit': 'fraction', 'reportedUnit': unit, 'value': packed(value)}
    issues = []
    range_match = RANGE.match(text, end)
    if range_match:
        second, second_unit, second_end = atom(text, range_match.end())
        # In "45–70%", the one explicit percent unit governs both endpoints.
        if unit == 'fraction' and second_unit == 'percent':
            value /= 100
            unit = 'percent'
        if unit != second_unit or q:
            raise ValueError('RANGE_UNIT_OR_QUALIFIER_AMBIGUOUS')
        result = {'kind': 'RANGE', 'unit': 'fraction', 'reportedUnit': unit,
                  'writtenEndpoints': [packed(value), packed(second)],
                  'lower': packed(min(value, second)), 'upper': packed(max(value, second)),
                  'boundaryInclusion': 'UNSPECIFIED', 'selectedValue': None}
        issues.append('RANGE_REQUIRES_MODEL_AND_SCOPE_REVIEW')
        end = second_end
        if not 0 < second <= 1: issues.append('NUMERIC_VALUE_OUT_OF_RANGE')
    elif q in {'<', '<=', '≤', '>', '>=', '≥'}:
        result = {'kind': 'BOUND', 'unit': 'fraction', 'reportedUnit': unit,
                  'operator': q, 'bound': packed(value), 'selectedValue': None}
        issues.append('BOUND_REQUIRES_MODEL_AND_SCOPE_REVIEW')
    elif q:
        result['kind'] = 'APPROXIMATE'
        result['qualifier'] = q
        issues.append('APPROXIMATE_VALUE_NOT_EXACT')
    if not 0 < value <= 1: issues.append('NUMERIC_VALUE_OUT_OF_RANGE')
    # A new Markdown list item or HTML footnote is not multiplication or a
    # comparison. The full unmodified text (including the note) stays in output.
    new_block = re.match(r'[^\S\r\n]*\r?\n\s*(?:[*+-]\s+(?=[A-Za-z])|<sup>[0-9]+</sup>)', text[end:])
    if UNSUPPORTED_TAIL.match(text, end) and not new_block:
        issues.append('NUMERIC_CONTINUATION_UNSUPPORTED')
    return result, end, issues


def extract_text(text):
    if not isinstance(text, str) or len(text) > MAX_TEXT:
        return {'mentions': [], 'issues': ['TEXT_SIZE_OR_TYPE_UNSUPPORTED']}
    mentions = []
    for start, end, fields in labels(text):
        connector = CONNECTOR.match(text, end)
        issues = []
        # Restrict negation to the current sentence/list segment; do not interpret it.
        prefix = re.split(r'[.;\n]', text[max(0, start-80):start])[-1]
        if NEGATION.search(prefix): issues.append('NEGATED_OR_UNAVAILABLE_PARAMETER')
        try:
            value, numeric_end, numeric_issues = numeric(text, connector.end())
            issues.extend(numeric_issues)
            span = [connector.end(), numeric_end]
        except ValueError as error:
            value, span = None, None
            issues.append(str(error))
        for field in fields:
            mentions.append({'field': field, 'labelSpan': [start, end], 'labelText': text[start:end],
                             'valueSpan': span, 'valueText': text[span[0]:span[1]] if span else None,
                             'numeric': value, 'issues': list(issues)})
    issues = []
    if re.search(r'\bpopulation\s+frequency\b', text, re.I):
        issues.append('POPULATION_FREQUENCY_NOT_ASSUMED_TO_BE_PREVALENCE')
    return {'mentions': mentions, 'issues': issues}


def proposal(clause):
    parsed = extract_text(clause['text'])
    if not parsed['mentions'] and not parsed['issues']:
        return None
    result = dict(clause, extractorVersion=VERSION, status='UNREVIEWED', modelExecutable=False,
                  scientificallyAdopted=False, parsed=parsed)
    result['clauseId'] = identity(clause)
    parameters = {}
    for field in FIELDS:
        mentions = [m for m in parsed['mentions'] if m['field'] == field]
        points = [m for m in mentions if m['numeric'] and m['numeric']['kind'] == 'POINT' and not m['issues']]
        values = {identity(m['numeric']['value']) for m in points}
        # All occurrences must be understood. Never pick a number out of mixed
        # ranges, old/new assumptions, missing cross-references or alternatives.
        status = ('NO_EXPLICIT_LABEL' if not mentions else
                  'MULTIPLE_VALUES_REQUIRE_REVIEW' if len(values) > 1 else
                  'UNRESOLVED_MENTIONS' if len(points) != len(mentions) else
                  'EXPLICIT_POINT_UNREVIEWED')
        parameters[field] = {'status': status, 'mentionIndexes': [i for i, m in enumerate(parsed['mentions']) if m['field'] == field],
                             'candidateValue': points[0]['numeric']['value'] if status == 'EXPLICIT_POINT_UNREVIEWED' else None}
    result['parameters'] = parameters
    result['completeExplicitPointSet'] = all(p['status'] == 'EXPLICIT_POINT_UNREVIEWED' for p in parameters.values())
    result['reviewRequirements'] = ['FULL_CLAUSE_AND_EXPERT_PRIORITY', 'PARAMETER_DEFINITIONS_AND_DENOMINATORS',
                                    'POPULATION_AGE_AND_STATISTIC', 'MODEL_STRENGTH_AND_EXCEPTION_SCOPE']
    if clause['scope']['moi'] not in MODE_NAMES.values():
        result['reviewRequirements'].append('MOI_SCOPE_UNSUPPORTED_OR_UNRESOLVED')
    return result


def extract(specifications, sources, backgrounds):
    source_by_id = {s['sourceId']: s for s in sources}
    proposals = {}
    examined = set()
    for spec in specifications:
        for clause in specification_clauses(spec, source_by_id[spec['sourceId']]):
            examined.add((clause['source']['sourceId'], clause['locator']))
            item = proposal(clause)
            if item: proposals[item['clauseId']] = item
    output = sorted(proposals.values(), key=lambda p: p['clauseId'])
    by_scope = {}
    for item in output:
        by_scope.setdefault(tuple(item['scope'].get(k) for k in ('hgncId', 'mondoId', 'moi')), []).append(item)
    drafts = []
    for background in backgrounds:
        scope = {k: background.get(k) for k in ('hgncId', 'mondoId', 'moi')}
        matches = by_scope.get(tuple(scope.values()), [])
        drafts.append({'scope': scope, 'proposalIds': [p['clauseId'] for p in matches],
                       'completeProposalIds': [p['clauseId'] for p in matches if p['completeExplicitPointSet']],
                       'status': 'UNREVIEWED' if matches else 'NO_MATCH_IN_CURRENT_EXTRACTION',
                       'modelExecutable': False, 'scientificallyAdopted': False})
    unique_clauses = {(p['source']['sourceId'], p['locator']) for p in output}
    report = {'extractorVersion': VERSION, 'releasedSpecifications': len(specifications),
              'examinedUniqueClauses': len(examined), 'parameterUniqueClauses': len(unique_clauses),
              'scopedProposals': len(output), 'backgrounds': len(backgrounds),
              'backgroundsWithProposals': sum(bool(d['proposalIds']) for d in drafts),
              'backgroundsWithCompleteExplicitPointProposal': sum(bool(d['completeProposalIds']) for d in drafts),
              'completeExplicitPointProposals': sum(p['completeExplicitPointSet'] for p in output),
              'parameterStatusCounts': {f: dict(Counter(p['parameters'][f]['status'] for p in output)) for f in FIELDS},
              'newScientificAdoptions': 0, 'newExecutableBackgrounds': 0,
              'claim': 'TEXT_EXTRACTION_NOT_PARAMETER_QUALIFICATION_OR_PATIENT_COVERAGE'}
    return output, drafts, report
