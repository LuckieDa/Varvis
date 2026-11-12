"""Lossless-reference projection of existing source facts into the new offline contract.

All projected facts start UNVERIFIED. A source status is retained, not promoted to
an adopted clinical fact. No model request, production material or cache changes.
"""
from copy import deepcopy

from core_material_schema import GROUPS, CONTEXT, EXTENSIONS, VERSION
from core_material_contract import canonical


def missing(case_key, subject='PROBAND', availability='NOT_EXTRACTED'):
    return {'caseKey': case_key, 'subject': subject, 'rawValue': None, 'value': None,
            'origin': 'SOURCE_OBSERVATION', 'availability': availability, 'verification': 'UNVERIFIED',
            'citations': [], 'checkedLocations': [], 'issues': []}


def blank(case_key, data_class='PUBLIC_PAPER_RESEARCH'):
    groups = {g: {k: missing(case_key, g.upper() if g in ('proband', 'mother', 'father') else 'PROBAND')
                  for k in fields} for g, fields in GROUPS.items()}
    groups['parentage']['maternity']['subject'] = 'MOTHER'
    groups['parentage']['paternity']['subject'] = 'FATHER'
    return {'schemaVersion': VERSION, 'caseKey': case_key, 'dataClass': data_class, 'sources': {}, 'groups': groups,
            'context': {k: missing(case_key, 'CONTEXT') for k in CONTEXT},
            'extensions': {**{g: {k: missing(case_key) for k in fields} for g, fields in EXTENSIONS.items()}, 'expert': {}},
            'conditions': {'phaseRequired': missing(case_key, 'CONTEXT'), 'specialFamilyModel': missing(case_key, 'FAMILY'), 'expertRequirements': []},
            'reviewPlan': {'checkedScope': [], 'unavailableSources': [], 'unprocessedScope': [],
                           'secondPassStatus': 'NOT_RUN', 'secondPassFactPaths': [], 'unresolvedDisagreements': []}}


def observed(case_key, subject, value, raw_value, citations, origin='PROGRAM_DERIVED'):
    f = missing(case_key, subject)
    f.update(value=deepcopy(value), rawValue=raw_value, availability='REPORTED', origin=origin, citations=deepcopy(citations))
    return f


def project(source_facts, case_label, source_manifest, facts_source_id):
    matches = [(i, c) for i, c in enumerate(source_facts['cases']) if c['caseLabel'] == case_label]
    if len(matches) != 1:
        raise ValueError('CORE_PROJECTION_CASE_NOT_UNIQUE')
    i, case = matches[0]
    key = source_facts['pmcid'] + '#' + case_label
    d = blank(key)
    d['sources'] = deepcopy(source_manifest)
    base = '/cases/' + str(i)

    def citation(path, value):
        return {'sourceId': facts_source_id, 'kind': 'JSON_POINTER', 'locator': path,
                'quote': value if isinstance(value, str) else canonical(value).decode('utf-8')}

    def paper_citations(value):
        if isinstance(value, dict):
            if all(k in value for k in ('sourceSha256', 'unitId', 'quote')):
                refs = [sid for sid, s in source_manifest.items() if s['sha256'] == value['sourceSha256'] and s['format'] == 'JATS']
                if len(refs) == 1:
                    yield {'sourceId': refs[0], 'kind': 'JATS_UNIT', 'locator': value['unitId'], 'quote': value['quote']}
            else:
                for v in value.values():
                    yield from paper_citations(v)
        elif isinstance(value, list):
            for v in value:
                yield from paper_citations(v)

    def put(group, name, value, source_path, source_value, subject='PROBAND', extra=()):
        refs = [citation(source_path, source_value), *paper_citations(extra)]
        refs = list({canonical(r): r for r in refs}.values())
        d['groups'][group][name] = observed(key, subject, value,
            source_value if isinstance(source_value, str) else canonical(source_value).decode('utf-8'), refs)

    put('paper', 'identity', source_facts['pmcid'], '/pmcid', source_facts['pmcid'], 'PAPER')
    put('case', 'label', case_label, base + '/caseLabel', case_label, extra=case.get('caseCitation', {}))
    fields = case.get('fields', [])
    for field, group, name, as_list in [('geneSymbol', 'gene', 'symbol', False),
                                       ('reportedPhenotype', 'phenotype', 'positive', True),
                                       ('reportedPhenotypeNegation', 'phenotype', 'negative', True),
                                       ('reportedDiagnosis', 'phenotype', 'diagnosis', False),
                                       ('reportedFamilyHistory', 'family', 'history', False),
                                       ('reportedProbandGenotype', 'proband', 'genotype', False)]:
        selected = [(j, f) for j, f in enumerate(fields) if f['field'] == field]
        if not selected:
            continue
        if not as_list and len({f['sourceValue'] for _, f in selected}) != 1:
            d['groups'][group][name]['verification'] = 'CONFLICT'
            continue
        j, f = selected[0]
        values = [v['sourceValue'] for _, v in selected]
        put(group, name, values if as_list else values[0], base + '/fields', fields,
            'FAMILY' if group == 'family' else 'PROBAND', extra=[v for _, v in selected])
    variant_fields = [f for f in fields if f['field'] in ('variantText', 'proteinChange', 'transcript')]
    if variant_fields:
        put('variant', 'representations', [f['sourceValue'] for f in variant_fields], base + '/fields', fields, extra=variant_fields)
    identity = case.get('alleleIdentity', {})
    if identity.get('status') == 'ALIAS_RESOLVED_REFERENCE_CHECK_PENDING':
        # Mapping is preserved, but remains unverified until reference checks are supplied.
        put('variant', 'resolvedAllele', identity['allele'], base + '/alleleIdentity', identity)
    target = case.get('targetTesting', {})
    if target.get('status') in ('PUBLISHED_TRIO_SANGER_DENOVO', 'PUBLISHED_TARGETED_PARENTAL_TESTING_DENOVO'):
        result_map = {'PRESENT': 'PRESENT', 'REPORTED_PRESENT': 'PRESENT', 'REPORTED_ABSENT': 'ABSENT'}
        for subject in ('proband', 'mother', 'father'):
            if target.get(subject) in result_map:
                put(subject, 'targetResult', result_map[target[subject]], base + '/targetTesting', target, subject.upper(), target)
        put('testing', 'method', target['method'], base + '/targetTesting', target, 'FAMILY', target)
        put('testing', 'participants', ['PROBAND', 'MOTHER', 'FATHER'], base + '/targetTesting', target, 'FAMILY', target)
        put('testing', 'qualifiedForTarget', True, base + '/targetTesting', target, 'FAMILY', target)
    parentage = case.get('parentage', {})
    state = {'PUBLISHED_BOTH_PARENTS_CONFIRMED': 'CONFIRMED', 'PUBLISHED_PARENTAGE_NOT_CONFIRMED': 'ASSUMED_NOT_CONFIRMED'}.get(parentage.get('status'))
    if state:
        for name, subject in (('maternity', 'MOTHER'), ('paternity', 'FATHER')):
            put('parentage', name, state, base + '/parentage', parentage, subject, parentage)
    if case.get('familyHistory') == 'REPORTED_NONE' and case.get('contextVerification') == 'SUPPORTED_EXPLICIT_FAMILY_STATEMENTS':
        put('family', 'compatibility', 'COMPATIBLE', base + '/contextVerification', case['contextVerification'], 'FAMILY', case.get('familyHistoryCitations', []))
    for problem in case.get('issues', []):
        d['reviewPlan']['unprocessedScope'].append('Legacy qualification: ' + problem)
    if case.get('unresolvedContext'):
        d['groups']['family']['compatibility']['issues'] = ['UNASSIGNED_OR_UNSUPPORTED_FAMILY_CONTEXT']
    d['reviewPlan']['unprocessedScope'].append('No claim of complete paper/attachment extraction; projection does not qualify facts.')
    return d
