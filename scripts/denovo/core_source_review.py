"""Bounded, deterministic source checks; not clinical qualification or scoring.

Only supported structures/grammars produce facts. A failed match is never proof
that the publication did not report a fact. Model suggestions are search hints.
"""
from copy import deepcopy
import re

from core_material_extraction import destination
from literature_case_structure import tables, paragraph_owned, structural_assertions
from qualify_literature_cases import (source_case, CASE_TITLE, cite, sentences, FAMILY_SENTENCES,
                                      PARENTAGE_SENTENCES, ASSUMED_SENTENCES, TRIO_SENTENCES, CRITICAL_CONTEXT)

VERSION = 'denovo-core-source-review-1.1.0'
MAX_UNITS = 96
MAX_SCOPE_BYTES = 65536


def prose_assertions(document, label, owned):
    """Explicit case-owned statements, with unknown critical clauses retained.

    A named assay is optional; an explicit target-test result is not. Normal
    family history is a reported family assessment, not invented parental exams.
    This reader never establishes disease applicability or event independence.
    """
    result = {'normalHistory': [], 'parentTests': [], 'probandTests': [], 'unaffectedParents': [],
              'understood': [], 'unresolved': [], 'assayLimitations': []}
    subject = r'(?:(?:Case|Patient|Individual|Proband) ' + re.escape(label) + r'|The (?:proband|patient|child))'
    normal = re.compile(subject + r' (?:had|has) a normal family history\.')
    introduction = re.compile(subject + r' (?:is|was) a [0-9]+-year-old (?:boy|girl|man|woman)'
        r'(?: \(Figure [0-9]+[A-Z]?\))? born to nonconsanguineous parents with a normal '
        r'(?:perinatal and )?family history(?: who presented with (?P<presentation>[^.!?]+))?\.')
    parental_test = re.compile(r'(?:Both parents tested negative for (?:the|this) variant|'
        r'(?:Targeted )?[Tt]esting of both parents (?:showed|confirmed) the absence of (?:the|this) variant)\.')
    reported_absence = re.compile(r'(?:In addition, )?[Tt]he variant was de novo, as neither parent '
                                 r'carried the variant(?: \([A-Z0-9]+\))?\.')
    unaffected = re.compile(r'(?:Both parents|The mother and father) (?:were|are) (?:clinically )?unaffected\.')
    proband_test = re.compile(r'(?:WES|Whole-exome sequencing|Whole-genome sequencing|Genetic testing|Sanger sequencing) '
                             r'(?:revealed|identified|detected) (?:a |the )?(?:novel )?heterozygous .+\.')
    trio_sample = re.compile(r'We performed (?:trio-based )?(?:whole-exome sequencing|whole-genome sequencing)'
        r'(?: \((?:WES|WGS)\))? on (?:the )?(?:buccal swab and )?(?:peripheral )?blood of the (?:patient|proband) '
        r'and (?:peripheral )?blood of (?:his|her|the proband\x27s) (?P<healthy>unaffected )?parents\.')
    site_test = re.compile(r'No (?:variation|variant|mutation) was detected at this site in (?:his|her|both) parents, '
        r'which was subsequently (?:validated|confirmed) by Sanger sequencing(?: \(Fig\. [0-9]+\))?\.')
    trio_result = re.compile(r'The trio-based (?:WES|WGS) (?:revealed|identified) a heterozygous (?:missense )?variant .+ in the proband, .+\.')
    unsafe = re.compile(r'\b(?:no|not|may|might|possible|possibly|uncertain|mother|father|sibling|previously|unrelated|control|other)\b', re.I)
    for unit in owned:
        for start, end, sentence in sentences(unit['text']):
            ref = cite(document, unit, start, end)
            intro = introduction.fullmatch(sentence)
            normal_history = bool(normal.fullmatch(sentence) or (intro and
                not CRITICAL_CONTEXT.search(intro['presentation'] or '')))
            parent = bool(parental_test.fullmatch(sentence) or site_test.fullmatch(sentence))
            proband = bool((proband_test.fullmatch(sentence) or trio_result.fullmatch(sentence)) and not unsafe.search(sentence))
            sample = trio_sample.fullmatch(sentence)
            if unaffected.fullmatch(sentence) or (sample and sample['healthy']):
                result['unaffectedParents'].append({'method': 'EXPLICIT_UNAFFECTED_PARENTS_AD', 'citation': ref})
            if normal_history:
                result['normalHistory'].append({'method': 'EXPLICIT_NORMAL_FAMILY_HISTORY', 'citation': ref})
            if parent:
                result['parentTests'].append(ref)
            if proband:
                result['probandTests'].append(ref)
            # A domain-specific negative history is understood text, not a
            # normal family assessment for every possible disease.
            domain_history = re.fullmatch(r'(?:He|She) was the (?:first|second|third) born of a non-consanguineous '
                r'[A-Za-z -]+ couple, and there was no family history of [A-Za-z -]+\.', sentence)
            methodology = re.fullmatch(r'Candidate variants were (?:then )?evaluated in the context of clinical presentation and inheritance mode\.', sentence)
            database_absence = re.fullmatch(r'The de novo variant was not observed in public variant databases\.', sentence)
            author_classification = re.fullmatch(r'This novel de novo variant was classified as .+ according to .+\.', sentence)
            known = (normal_history or parent or reported_absence.fullmatch(sentence) or unaffected.fullmatch(sentence)
                     or sample or domain_history or methodology or database_absence or author_classification)
            known = known or any(p.fullmatch(sentence) for _, p in
                                 FAMILY_SENTENCES + PARENTAGE_SENTENCES + ASSUMED_SENTENCES + TRIO_SENTENCES)
            if known:
                result['understood'].append(ref)
            elif CRITICAL_CONTEXT.search(re.sub(r'\bOnline (?:Mendelian|Menedelian) Inheritance in Man\b', 'DATABASE', sentence)):
                result['unresolved'].append(ref)
            if not known and (re.search(r'\b(?:mosaic\w*|contaminat\w*|coverage|inconclusive|sensitivity|allele dropout|poor quality|low quality)\b', sentence, re.I)
                              or (re.search(r'\b(?:parents?|mother|father|parental)\b', sentence, re.I)
                                  and re.search(r'\b(?:test\w*|sequenc\w*|carr\w*|variant|mutation)\b', sentence, re.I))):
                result['assayLimitations'].append(ref)
    return result


def source_citations(value):
    if isinstance(value, dict):
        if all(k in value for k in ('sourceSha256', 'unitId', 'quote')):
            yield value
        else:
            for child in value.values():
                yield from source_citations(child)
    elif isinstance(value, list):
        for child in value:
            yield from source_citations(child)


def collect(source, label, observations):
    """Re-read native source, then recheck omissions in the owned case scope."""
    document = source['main']; units = {u['unitId']: u for u in document['units']}
    native = tables(document)
    rows = [r for r in native if r['caseLabel'] == label]
    section_ids = set()
    for unit in units.values():
        title = CASE_TITLE.fullmatch(unit['sections'][-1]) if unit['sections'] else None
        if title and title[1] == label and unit['kind'] == 'BODY_PARAGRAPH' and any(
                s.lower() in {'results', 'case reports', 'case report', 'clinical report'} for s in unit['sections'][:-1]):
            section_ids.add(unit['unitId'].rsplit('/p[', 1)[0])
    owned = []
    for unit in units.values():
        if unit['kind'] != 'BODY_PARAGRAPH':
            continue
        title = CASE_TITLE.fullmatch(unit['sections'][-1]) if unit['sections'] else None
        section = bool(len(section_ids) == 1 and title and title[1] == label
                       and unit['unitId'].rsplit('/p[', 1)[0] in section_ids)
        if section or paragraph_owned(document, unit, label):
            # A case heading does not transfer another explicitly named case's facts.
            mentions = re.findall(r'\b(?:Case|Patient|Individual|Proband) ([A-Za-z0-9][A-Za-z0-9_.-]*)', unit['text'])
            if all(v.rstrip('.') == label for v in mentions):
                owned.append(unit)
    proofs, checked, issues = {}, set(), []

    def add(path, value, refs, method, subject='PROBAND', availability='REPORTED'):
        refs = list(source_citations(refs))
        if not refs:
            return
        for ref in refs:
            if (ref['unitId'] not in units or ref['sourceSha256'] != document['sourceSha256']
                    or not ref['quote'] or ref['quote'] not in units[ref['unitId']]['text']):
                raise ValueError('DENOVO_CORE_REVIEW_READER_CITATION_INVALID')
            checked.add(ref['unitId'])
        proofs.setdefault('/groups/' + path.replace('.', '/'), []).append({
            'value': deepcopy(value), 'citations': deepcopy(refs), 'method': method,
            'subject': subject, 'availability': availability})

    from core_case_completion import phenotype_matrix
    for value, refs in phenotype_matrix(document, label):
        add('phenotype.positive', [value], refs, 'EXPLICIT_PATIENT_PHENOTYPE_MATRIX_POSITIVE')

    if len(rows) > 1:
        issues.append('CASE_TABLE_OWNERSHIP_AMBIGUOUS')
    if len(section_ids) > 1:
        issues.append('CASE_SECTION_OWNERSHIP_AMBIGUOUS')
    selected = rows[0] if len(rows) == 1 else None
    if selected:
        add('case.label', label, selected['caseCitation'], 'EXACT_CASE_TABLE_HEADER')
        for field in selected['fields']:
            refs = list(source_citations(field))
            checked.update(r['unitId'] for r in refs)
            for group, name, is_array in destination(field['field'], 'PROBAND'):
                if is_array is None:
                    continue
                # Literal table claims are not normalized alleles, HPO or diagnoses.
                value = field['sourceValue']
                if group == 'phenotype' and name == 'positive' and re.search(
                        r'\b(?:no|not|without|absent|normal|negative|denied|possible|mother|father|parents?|siblings?|brother|sister)\b', value, re.I):
                    # Mixed affirmative/negative clinical cells need clause review.
                    issues.append('PHENOTYPE_TABLE_POLARITY_REQUIRES_REVIEW')
                    continue
                if value.strip().lower() in {'not reported', 'not available', 'unknown', 'na', 'n/a', '-', '?'}:
                    if value.strip().lower() == 'not reported':
                        add(group + '.' + name, None, refs, 'EXPLICIT_TABLE_NOT_REPORTED',
                            availability='NOT_REPORTED_IN_CHECKED_SCOPE')
                    continue
                add(group + '.' + name, [value] if is_array else value, refs, 'OWNED_TABLE_VALUE_AND_HEADER',
                    'FAMILY' if group in {'testing', 'family'} else 'PROBAND')
    # For narrative-only cases, identifier proposals must independently match the
    # owned source. No other model field is fed into the assertion reader.
    identifier_fields = []
    for observation in observations:
        field = observation['rawField']; ref = field['citation']; value = field['sourceValue']
        if field['field'] not in {'geneSymbol', 'variantText', 'proteinChange', 'transcript'} or observation['subject'] != 'PROBAND':
            continue
        unit = next((u for u in owned if u['unitId'] == ref['unitId']), None)
        if not unit or value not in ref['quote'] or ref['quote'] not in unit['text']:
            continue
        name = field['field']
        valid = (name == 'geneSymbol' and re.search(r'(?<![\w-])' + re.escape(value) + r' (?:gene|variant|mutation)\b', unit['text']))
        valid = valid or (name == 'variantText' and re.fullmatch(r'c\.[0-9]+(?:_[0-9]+)?(?:[ACGT]\s*>\s*[ACGT]|delins[ACGT]+|del[ACGT]*|dup[ACGT]*|ins[ACGT]+)', value))
        valid = valid or (name == 'proteinChange' and re.fullmatch(r'p\.[A-Za-z*]+[0-9]+[A-Za-z*]+', value))
        valid = valid or (name == 'transcript' and re.fullmatch(r'NM_[0-9]+\.[0-9]+', value))
        # A source claim with negation/uncertainty near it needs a richer reader.
        containing = [s for _, _, s in sentences(unit['text']) if value in s]
        if not valid or not containing or any(re.search(r'\b(?:not|no|may|possible|mother|father|sibling|previously)\b', s, re.I) for s in containing):
            continue
        identifier_fields.append({**deepcopy(field), 'findingState': 'REPORTED'})
        path = 'gene.symbol' if name == 'geneSymbol' else 'variant.representations'
        add(path, value if name == 'geneSymbol' else [value], ref, 'OWNED_LITERAL_IDENTIFIER')
    if not selected and owned:
        selected = {'observationId': document['pmcid'] + '#' + label, 'caseLabel': label,
                    'caseCitation': cite(document, owned[0]), 'fields': identifier_fields, 'issues': []}
        add('case.label', label, selected['caseCitation'], 'EXPLICIT_CASE_NARRATIVE')
    for unit in owned:
        checked.add(unit['unitId'])
        subject = r'(?:The (?:proband|patient|child)|(?:Case|Patient|Individual|Proband) ' + re.escape(label) + r')'
        age = re.match(subject + r' (?:was|is) (?:a |an )?(?P<age>[0-9]+(?:\.[0-9]+)?[- ](?:year|month|day)s?[- ]old)\b', unit['text'])
        if age:
            add('phenotype.age', age['age'], cite(document, unit, 0, age.end()), 'EXPLICIT_CASE_AGE')
        for start, end, sentence in sentences(unit['text']):
            ref = cite(document, unit, start, end)
            age_match = re.fullmatch(subject + r' (?:was|is) (?:a |an )?(?P<age>[0-9]+(?:\.[0-9]+)?[- ](?:year|month|day)s?[- ]old)\.', sentence)
            if age_match:
                add('phenotype.age', age_match['age'], ref, 'EXPLICIT_CASE_AGE')
            for polarity, pattern in (
                ('positive', subject + r' (?:had|presented with|exhibited) (?P<value>[^.!?]+)\.'),
                ('negative', subject + r' (?:had no|did not have) (?P<value>[^.!?]+)\.')):
                match = re.fullmatch(pattern, sentence)
                if match and not re.search(r'\b(?:no|not|without|normal|may|possible|mother|father|parents?|sibling|brother|sister|previously)\b', match['value'], re.I):
                    add('phenotype.' + polarity, [match['value']], ref, 'EXPLICIT_PROBAND_' + polarity.upper())
            onset = re.fullmatch(subject + r" (?:had onset|developed symptoms) (?:at|in) (?P<value>[^.!?]+)\.", sentence)
            if onset:
                add('phenotype.onset', onset['value'], ref, 'EXPLICIT_PROBAND_ONSET')
            absent = re.fullmatch(r'(?:The )?(?:proband\x27s |patient\x27s )?(mother|father) (?:was |had )?not tested\.', sentence, re.I)
            if absent:
                add('testing.limitations', [sentence], ref, 'EXPLICIT_PARENT_NOT_TESTED', 'FAMILY')
            if re.fullmatch(r'(?:The )?(?:proband\x27s |patient\x27s )?(?:age|age at onset) was not reported\.', sentence, re.I):
                path = 'phenotype.onset' if 'onset' in sentence.lower() else 'phenotype.age'
                add(path, None, ref, 'EXPLICIT_SOURCE_NOT_REPORTED', availability='NOT_REPORTED_IN_CHECKED_SCOPE')
    derived = None
    if selected and len(rows) <= 1 and len(section_ids) <= 1:
        candidate_rows = native if rows else [selected]
        derived = source_case(document, candidate_rows, selected, [])
        structural_assertions(source, selected, native, derived)
        if not rows and label == 'proband':
            from single_case_scope import target_anchor
            derived['targetAnchors'] = target_anchor(document, selected)
        risky = any('CONFLICT' in i or 'MULTIPLE_TARGETS' in i for i in derived['issues'])
        own_ids = {u['unitId'] for u in owned}
        for ref in source_citations([derived['targetTesting'], derived['parentage'], derived.get('reportedParentalAbsence')]):
            u = units[ref['unitId']]
            mentions = re.findall(r'\b(?:Case|Patient|Individual|Proband) ([A-Za-z0-9][A-Za-z0-9_.-]*)', u['text'])
            if (derived.get('caseSection') and ref['unitId'].startswith(derived['caseSection'] + '/')
                    and (any(v.rstrip('.') != label for v in mentions)
                         or (u['kind'] == 'BODY_PARAGRAPH' and ref['unitId'] not in own_ids))):
                risky = True
                issues.append('ASSERTION_CASE_ATTRIBUTION_UNRESOLVED')
        if not risky:
            target = derived['targetTesting']
            if target['status'] in {'PUBLISHED_TRIO_SANGER_DENOVO', 'PUBLISHED_TARGETED_PARENTAL_TESTING_DENOVO'}:
                for person, result in [('proband', 'PRESENT'), ('mother', 'ABSENT'), ('father', 'ABSENT')]:
                    add(person + '.targetResult', result, target, target['status'], person.upper())
                add('testing.method', target['method'], target, target['status'], 'FAMILY')
                add('testing.participants', ['PROBAND', 'MOTHER', 'FATHER'], target, target['status'], 'FAMILY')
                # qualifiedForTarget includes exact-allele/context qualification,
                # deliberately left for stage 3, not copied from old projection.
            absence = derived.get('reportedParentalAbsence')
            if absence:
                for person in ('mother', 'father'):
                    add(person + '.targetResult', 'ABSENT', absence, 'REPORTED_ABSENCE_ASSAY_UNRESOLVED', person.upper())
            relation = derived['parentage']
            value = {'PUBLISHED_BOTH_PARENTS_CONFIRMED': 'CONFIRMED',
                     'PUBLISHED_PARENTAGE_NOT_CONFIRMED': 'ASSUMED_NOT_CONFIRMED',
                     'PUBLISHED_PARENTAGE_NOT_TESTED': 'ASSUMED_NOT_TESTED',
                     'PUBLISHED_PARENTAGE_NOT_REPORTED': 'ASSUMED_NOT_REPORTED'}.get(relation['status'])
            if value:
                for name, person in [('maternity', 'MOTHER'), ('paternity', 'FATHER')]:
                    add('parentage.' + name, value, relation, relation['status'], person)
            prose = prose_assertions(document, label, owned)
            # Reuse the independently source-matched gene/coding/protein anchor.
            # A generic "de novo" label or different target in the same case is
            # insufficient. Do not manufacture a method name from test results.
            bad_target = any('MULTIPLE_TARGETS' in i or 'TARGET_LINK' in i or 'CONFLICT' in i for i in derived['issues'])
            anchors = derived.get('targetAnchors', [])
            from literature_allele_identity import one_field
            gene = one_field(selected, 'geneSymbol') if anchors else None
            target_tests = [p for p in prose['probandTests'] if any(
                p['unitId'] == a['unitId'] for a in anchors) and re.search(r'\bc\.[0-9]+', p['quote'])
                and re.search(r'\bp\.[A-Za-z*]+[0-9]+', p['quote'])
                and re.search(r'(?<![\w-])' + re.escape(gene) + r'(?![\w-])', p['quote'])]
            shared_unresolved = [r for r in derived.get('unresolvedContext', []) if r['unitId'] not in own_ids]
            if anchors and not bad_target and target_tests and prose['parentTests'] and not prose['assayLimitations'] and not shared_unresolved:
                refs = target_tests + prose['parentTests']
                for person, value in [('proband', 'PRESENT'), ('mother', 'ABSENT'), ('father', 'ABSENT')]:
                    add(person + '.targetResult', value, refs, 'EXPLICIT_CASE_TARGET_TEST_RESULTS', person.upper())
                add('testing.participants', ['PROBAND', 'MOTHER', 'FATHER'], refs,
                    'EXPLICIT_CASE_TARGET_TEST_RESULTS', 'FAMILY')
                for method in ('Sanger sequencing', 'whole-exome sequencing', 'whole-genome sequencing'):
                    method_refs = [r for r in refs if method in r['quote']]
                    if method_refs:
                        add('testing.method', method, method_refs, 'EXPLICIT_CASE_TARGET_TEST_RESULTS', 'FAMILY')
            if prose['assayLimitations']:
                add('testing.limitations', [r['quote'] for r in prose['assayLimitations']],
                    prose['assayLimitations'], 'EXPLICIT_CASE_TESTING_LIMITATION_OR_UNRESOLVED_CLAUSE', 'FAMILY')
        issues += derived['issues']
    from supplement_case_review import proofs as supplement_proofs
    extra, supplement_scope = supplement_proofs(source, label)
    all_units = {u['unitId']: u for u in source['units']}
    for path, entries in extra.items():
        for entry in entries:
            for ref in entry['citations']:
                unit = all_units.get(ref['unitId'])
                if (not unit or unit['sourceSha256'] != ref['sourceSha256']
                        or not ref['quote'] or ref['quote'] not in unit['text']):
                    raise ValueError('DENOVO_CORE_REVIEW_READER_CITATION_INVALID')
                checked.add(ref['unitId'])
        proofs.setdefault(path, []).extend(entries)
    issues += supplement_scope['issues'] if any(u.get('sourcePart') for u in source['units']) else []
    # Scoped silence is a reporting state, NOT evidence of unperformed tests or
    # biological relationships. The later contract may choose PM6 only under an
    # applicable assumed-parentage policy and qualified trio variant tests.
    relation_paths = ['/groups/parentage/' + p for p in ('maternity', 'paternity')]
    if owned and not any(proofs.get(p) for p in relation_paths):
        scope_units = [u for u in source['units'] if u['kind'] in {
            'BODY_PARAGRAPH', 'CAPTION', 'SUPPLEMENT_PARAGRAPH', 'SUPPLEMENT_CELL'} and u['text']
            and not any(re.search(r'references|bibliography', s, re.I) for s in u.get('sections', []))]
        relation_signal = re.compile(r'\b(?:parentage|paternity|maternity|kinship|SNPs?|STRs?|'
            r'microsatellites?|short tandem repeats?|biological relationships?|biological parents?|'
            r'genetic relationships?|DNA (?:identity|fingerprinting)|relatedness)\b', re.I)
        if (scope_units and len(scope_units) <= MAX_UNITS
                and sum(len(u['text'].encode()) for u in scope_units) <= MAX_SCOPE_BYTES
                and not any(relation_signal.search(u['text']) for u in scope_units)):
            refs = [{'sourceSha256': u['sourceSha256'], 'unitId': u['unitId'], 'quote': u['text']}
                    for u in scope_units]
            # Attachments use their own source pins; add() accepts main only.
            for name, subject in [('maternity', 'MOTHER'), ('paternity', 'FATHER')]:
                proofs['/groups/parentage/' + name] = [{'value': None, 'citations': refs,
                    'method': 'BOUNDED_PARENTAGE_REPORT_REVIEW', 'subject': subject,
                    'availability': 'NOT_REPORTED_IN_CHECKED_SCOPE'}]
            checked.update(u['unitId'] for u in scope_units)
    # Bounds apply to the complete selected scope, not a silently truncated prefix.
    size = sum(len(all_units[u]['text'].encode('utf-8')) for u in checked)
    if len(checked) > MAX_UNITS or size > MAX_SCOPE_BYTES:
        return {}, {'unitIds': [], 'scopeBytes': 0, 'status': 'SCOPE_LIMIT_EXCEEDED',
                    'unprocessedUnitCount': len(checked), 'issues': ['SOURCE_REVIEW_SCOPE_LIMIT']}
    return proofs, {'unitIds': sorted(checked), 'scopeBytes': size, 'status': 'BOUNDED_SOURCE_RECHECK_COMPLETED',
                    'issues': sorted(set(issues)), 'sourceInterpretation': 'REPORTED_FACTS_NOT_CLINICAL_QUALIFICATION',
                    **({'supplementReview': supplement_scope} if any(u.get('sourcePart') for u in source['units']) else {})}
