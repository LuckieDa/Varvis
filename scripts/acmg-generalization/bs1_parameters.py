"""Offline, non-adopting BS1 parameter inventory over pinned knowledge inputs.

Recognizing a source fact is not approving a disease model. No value is selected
across populations, no text mention becomes a number, and no policy is emitted.
"""
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation, localcontext
import hashlib
import json
import re

VERSION = 'bs1-parameter-assessment-v1'
FIELDS = ('prevalence', 'penetrance', 'maximumGeneContribution', 'maximumAlleleContribution')
LEGACY = dict(zip(FIELDS, ('qualifiedPrevalence', *FIELDS[1:])))
MODE_NAMES = {'Autosomal dominant inheritance': 'AUTOSOMAL_DOMINANT',
              'Autosomal recessive inheritance': 'AUTOSOMAL_RECESSIVE'}
STATISTICS = {'Point prevalence': 'POINT_PREVALENCE', 'Prevalence at birth': 'BIRTH_PREVALENCE',
              'Lifetime Prevalence': 'LIFETIME_PREVALENCE', 'Annual incidence': 'ANNUAL_INCIDENCE',
              'Cases/families': 'CASE_OR_FAMILY_COUNT'}
MENTIONS = {
    'prevalence': r'\bprevalence\b', 'penetrance': r'\bpenetran(?:ce|t)\b',
    'maximumGeneContribution': r'\b(?:genetic heterogeneity|(?:max(?:imum)?\s+)?genetic contribution|gene contribution)\b',
    'maximumAlleleContribution': r'\b(?:allelic heterogeneity|(?:max(?:imum)?\s+)?allelic contribution|allele contribution)\b'}
ORPHA_BASIS = {'url': 'https://www.orphadata.com/docs/OrphadataScienceDataDescription.pdf',
               'version': 'V3 April 2026', 'pages': '23-25', 'rule': 'ValMoy per 100000; class-only 0.0 is a placeholder'}
RANGES = {'<1/1000000': (None, '0.000001', False, False),
          '1-9/1000000': ('0.000001', '0.000009', True, True),
          '1-9/100000': ('0.00001', '0.00009', True, True),
          '1-5/10000': ('0.0001', '0.0005', True, True),
          '6-9/10000': ('0.0006', '0.0009', True, True),
          '>1/1000': ('0.001', None, False, False)}


def identity(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(',', ':'), allow_nan=False).encode('utf8')).hexdigest()


def decimal(value):
    if not isinstance(value, str) or not re.fullmatch(r'[0-9]+(?:\.[0-9]+)?', value):
        raise ValueError('DECIMAL_TEXT_REQUIRED')
    number = Decimal(value)
    if not number.is_finite():
        raise ValueError('FINITE_DECIMAL_REQUIRED')
    return number


def formatted(number):
    return format(number, 'f').rstrip('0').rstrip('.') if '.' in format(number, 'f') else format(number, 'f')


def epidemiology_fact(row, source):
    """Normalize units only. Incidence/counts remain distinct from prevalence."""
    statistic = STATISTICS.get(row.get('type'), 'UNKNOWN')
    fact = {'kind': 'ORPHADATA_OBSERVATION', 'field': 'prevalence', 'orphaId': row['orphaId'],
            'source': source, 'locator': 'ORPHA:' + row['orphaId'].split(':')[-1] + '/Prevalence/' + str(row.get('sourceRecordId') or identity(row)),
            'citation': row.get('sourceCitation'), 'population': row.get('geography'),
            'statistic': statistic, 'validation': row.get('validation'),
            'raw': {k: row.get(k) for k in ('type', 'mean', 'class', 'qualification', 'disorderGroup', 'disorderType')},
            'normalizationBasis': ORPHA_BASIS, 'issues': [], 'technicalCandidate': False,
            'scientificallyAdopted': False}
    issues = fact['issues']
    if row.get('validation') != 'Validated': issues.append('SOURCE_NOT_VALIDATED')
    if not row.get('sourceCitation'): issues.append('SOURCE_CITATION_MISSING')
    if not row.get('geography'): issues.append('POPULATION_UNSPECIFIED')
    if statistic == 'UNKNOWN': issues.append('STATISTIC_NOT_RECOGNIZED')
    if statistic == 'CASE_OR_FAMILY_COUNT':
        fact['valueKind'] = 'COUNT'
        issues.append('CASE_COUNTS_ARE_NOT_PREVALENCE')
        return fact
    if statistic == 'ANNUAL_INCIDENCE': issues.append('INCIDENCE_IS_NOT_PREVALENCE')
    if statistic in {'BIRTH_PREVALENCE', 'LIFETIME_PREVALENCE'}:
        issues.append('CURRENT_MODEL_STATISTIC_UNSUPPORTED')
    qualification = row.get('qualification')
    class_key = re.sub(r'\s+', '', row.get('class') or '')
    if class_key in RANGES:
        low, high, li, hi = RANGES[class_key]
        fact['reportedRange'] = {'lower': low, 'upper': high, 'lowerInclusive': li, 'upperInclusive': hi,
                                 'unit': 'fraction', 'basis': 'REPORTED_CLASS_NOT_CONFIDENCE_INTERVAL'}
    if qualification == 'Class only':
        fact['valueKind'] = 'RANGE' if 'reportedRange' in fact else 'UNREPORTED'
        if row.get('mean') not in (None, '', '0', '0.0'):
            issues.append('CLASS_ONLY_HAS_NONPLACEHOLDER_MEAN')
        issues.append('CURRENT_MODEL_RANGE_UNSUPPORTED' if 'reportedRange' in fact else 'NO_NUMERIC_ESTIMATE_REPORTED')
    elif qualification == 'Value and class':
        try:
            number = decimal(row.get('mean'))
            with localcontext() as context:
                context.prec = max(40, len(number.as_tuple().digits) + 5)
                number = number / Decimal(100000)
            if not Decimal(0) < number <= Decimal(1): raise ValueError('RANGE')
            fact.update(valueKind='POINT', value=formatted(number), unit='fraction', rawUnit='per_100000')
            if 'reportedRange' in fact:
                interval = fact['reportedRange']
                low = Decimal(interval['lower']) if interval['lower'] is not None else None
                high = Decimal(interval['upper']) if interval['upper'] is not None else None
                if ((low is not None and (number < low or number == low and not interval['lowerInclusive']))
                        or (high is not None and (number > high or number == high and not interval['upperInclusive']))):
                    issues.append('VALUE_CLASS_DISAGREEMENT_REQUIRES_REVIEW')
        except (ValueError, InvalidOperation):
            fact['valueKind'] = 'INVALID'; issues.append('SOURCE_NUMERIC_VALUE_INVALID')
    else:
        fact['valueKind'] = 'UNSUPPORTED'; issues.append('SOURCE_QUALIFICATION_UNSUPPORTED')
    fact['technicalCandidate'] = statistic == 'POINT_PREVALENCE' and fact['valueKind'] == 'POINT' and not issues
    return fact


def specification_clauses(spec, source):
    """Yield complete BS1 text with its exact scope, before field extraction."""
    entity = spec['original']; ld = entity.get('ld', {})
    genes = {g.get('entContent', {}).get('HGNC', {}).get('symbol'):
             g.get('entContent', {}).get('HGNC', {}).get('hgnc_id') for g in ld.get('Gene', [])}
    scopes = []
    for rules in ld.get('RuleSet', []):
        for gene in rules.get('entContent', {}).get('genes', []):
            for disease in gene.get('diseases', []):
                modes = disease.get('modesOfInheritance')
                if modes is None: modes = [{'modeOfInheritance': disease.get('preferredModeOfInheritance')}]
                for mode in modes:
                    scopes.append((gene.get('gene'), genes.get(gene.get('gene')), disease.get('preferredMondoId'),
                                   MODE_NAMES.get(mode.get('modeOfInheritance'))))
    for ci, c in enumerate(ld.get('CriteriaCode', [])):
        clause = c.get('entContent', {})
        if clause.get('label') != 'BS1': continue
        texts = [(k, clause.get(k)) for k in ('instructionsToUse', 'additionalComments')]
        for si, strength in enumerate(clause.get('strengthDescriptor', [])):
            if not strength.get('applicability', '').lower().startswith('applicable'): continue
            texts.extend((f'strengthDescriptor/{si}/{k}', strength.get(k)) for k in ('text', 'instructionsToUse'))
        for locator, text in texts:
            if not isinstance(text, str) or not text.strip(): continue
            for symbol, hgnc, mondo, mode in sorted(set(scopes), key=str):
                if not hgnc or not mondo or symbol not in clause.get('gene', []): continue
                if clause.get('disease') and mondo not in clause['disease']: continue
                yield {'source': source, 'specificationId': spec['specificationId'],
                       'specificationVersion': spec['version'],
                       'scope': {'hgncId': hgnc, 'mondoId': mondo, 'moi': mode},
                       'locator': f"{spec['specificationId']}/ld/CriteriaCode/{ci}/entContent/{locator}",
                       'text': text}


def specification_facts(spec, source):
    """Inventory stays non-numeric; structured text extraction is a separate output."""
    for clause in specification_clauses(spec, source):
        for field, pattern in MENTIONS.items():
            if re.search(pattern, clause['text'], re.I):
                yield dict(clause, kind='CSPEC_TEXT_MENTION', field=field, valueKind='UNSTRUCTURED',
                           technicalCandidate=False, scientificallyAdopted=False,
                           issues=['TEXT_MENTION_REQUIRES_EXTRACTION_AND_SCOPE_REVIEW'])


def structured_facts(record, sources):
    if record.get('kind') != 'MAX_CREDIBLE_AF': return
    model = record.get('model', {})
    for field in FIELDS:
        if field not in model: continue
        p = model[field]; source = sources.get(p.get('sourceId')) if isinstance(p, dict) else None
        issues = []
        fact = {'kind': 'STRUCTURED_PARAMETER', 'field': field, 'recordId': record.get('recordId'),
                'source': source, 'locator': p.get('locator') if isinstance(p, dict) else None,
                'raw': p, 'population': model.get('population'), 'scope': record.get('scope'),
                'technicalCandidate': False, 'scientificallyAdopted': False, 'issues': issues}
        if not source or not fact['locator']: issues.append('PARAMETER_SOURCE_UNBOUND')
        if not model.get('population'): issues.append('POPULATION_UNSPECIFIED')
        try:
            number = decimal(p.get('value'))
            if p.get('unit') != 'fraction' or not Decimal(0) < number <= Decimal(1): raise ValueError('UNIT_OR_RANGE')
            fact.update(valueKind='POINT', value=formatted(number), unit='fraction', statistic=p.get('statistic'))
        except (ValueError, AttributeError, InvalidOperation):
            fact['valueKind'] = 'INVALID'; issues.append('PARAMETER_NUMERIC_VALUE_INVALID')
        if field == 'prevalence' and fact.get('statistic') != 'POINT_PREVALENCE':
            issues.append('CURRENT_MODEL_STATISTIC_UNSUPPORTED')
        # Merely finding a reviewed-looking record is not validating its adoption.
        fact['technicalCandidate'] = not issues
        fact['adoptionCheck'] = 'NOT_PERFORMED_BY_INVENTORY'
        yield fact


def field_assessment(facts, scope_issues):
    issues = set(scope_issues)
    issues.update(issue for f in facts for issue in f['issues'])
    usable = [f for f in facts if f['technicalCandidate']]
    relevant = [f for f in facts if f.get('statistic') not in {'ANNUAL_INCIDENCE', 'CASE_OR_FAMILY_COUNT'}]
    # Compare only the same population/statistic. Different estimates are a
    # reconciliation queue, not a claim that either study is scientifically wrong.
    groups = defaultdict(set)
    for f in usable:
        source_disease = f.get('orphaId') or (f.get('scope') or {}).get('mondoId')
        groups[(source_disease, f.get('population'), f.get('statistic'))].add(f['value'])
    conflict = any(len(values) > 1 for values in groups.values())
    if conflict: issues.add('MULTIPLE_ESTIMATES_REQUIRE_RECONCILIATION')
    if len(groups) > 1: issues.add('POPULATION_OR_STATISTIC_SELECTION_REQUIRED')
    if not facts and 'EXACT_EPIDEMIOLOGY_MAPPING_MISSING' in scope_issues:
        status = 'IDENTITY_UNRESOLVED'
    elif conflict:
        status = 'CONFLICT'
    elif usable:
        status = 'REVIEW_REQUIRED' if scope_issues or len(groups) > 1 else 'CANDIDATE_AVAILABLE'
    elif facts and not relevant:
        status = 'SOURCE_GAP'
        issues.add('NO_PREVALENCE_ESTIMATE_IN_LOADED_INPUTS')
    elif any(f['valueKind'] == 'UNSTRUCTURED' for f in facts):
        status = 'REVIEW_REQUIRED'
    elif any(set(f['issues']) & {'CURRENT_MODEL_RANGE_UNSUPPORTED', 'CURRENT_MODEL_STATISTIC_UNSUPPORTED',
                               'SOURCE_QUALIFICATION_UNSUPPORTED', 'STATISTIC_NOT_RECOGNIZED'} for f in facts):
        status = 'SOFTWARE_UNSUPPORTED'
    elif any(f['valueKind'] == 'INVALID' for f in facts):
        status = 'INVALID_SOURCE_DATA'
    elif any(f['valueKind'] == 'POINT' for f in facts):
        status = 'REVIEW_REQUIRED'
    else:
        status = 'SOURCE_GAP'
        issues.add('NO_SUITABLE_ESTIMATE_IN_LOADED_INPUTS')
    return {'status': status, 'observationIds': [f['observationId'] for f in facts],
            'technicalCandidateCount': len(usable), 'reasonCodes': sorted(issues),
            'adoptionStatus': 'NOT_ASSESSED', 'selectedValue': None}


def assess(backgrounds, epidemiology, mappings, specifications, records, sources):
    source_by_id = {s['sourceId']: s for s in sources}
    observations = {}; epi_by_orpha = defaultdict(list)
    def register(fact):
        oid = identity(fact); fact = dict(fact, observationId=oid)
        observations[oid] = fact
        return fact
    for row in epidemiology:
        fact = register(epidemiology_fact(row, source_by_id[row['sourceId']]))
        epi_by_orpha[row['orphaId']].append(fact)
    scoped = defaultdict(list)
    for spec in specifications:
        for fact in specification_facts(spec, source_by_id[spec['sourceId']]):
            s = fact['scope']; scoped[(s['hgncId'], s['mondoId'], s['moi'])].append(register(fact))
    for record in records:
        for fact in structured_facts(record, source_by_id):
            s = record.get('scope', {})
            for mode in s.get('modes', []): scoped[(s.get('hgncId'), s.get('mondoId'), mode)].append(register(fact))
    mapping_by_mondo = defaultdict(list)
    for mapping in mappings: mapping_by_mondo[mapping['mondoId']].append(mapping)
    drafts = []
    for b in backgrounds:
        key = (b['hgncId'], b['mondoId'], b['moi']); linked = mapping_by_mondo[b['mondoId']]
        orphas = {m['orphaId'] for m in linked}; scope_issues = []
        if len(orphas) > 1: scope_issues.append('DISEASE_MAPPING_AMBIGUOUS')
        facts = list(scoped[key]); unresolved = scoped[(b['hgncId'], b['mondoId'], None)]
        facts.extend(unresolved)
        if unresolved: scope_issues.append('SPECIFICATION_MOI_SCOPE_UNRESOLVED')
        for orpha in sorted(orphas): facts.extend(epi_by_orpha[orpha])
        # Submitted GenCC identifiers remain unverified candidates, never exact mapping.
        submitted = set()
        for a in b['assertions']:
            match = re.fullmatch(r'(?:ORPHA|Orphanet):(\d+)', a.get('original', {}).get('disease_original_curie', ''))
            if match: submitted.add('ORPHA:' + match[1])
        if not orphas and not submitted and not any(f['field'] == 'prevalence' for f in facts):
            scope_issues.append('EXACT_EPIDEMIOLOGY_MAPPING_MISSING')
        for orpha in sorted(submitted - orphas): facts.extend(epi_by_orpha[orpha])
        if submitted - orphas: scope_issues.append('SUBMITTED_DISEASE_IDENTIFIER_NOT_VERIFIED')
        facts = list({f['observationId']: f for f in facts}.values())
        fields = {field: field_assessment([f for f in facts if f['field'] == field],
                                          scope_issues if field == 'prevalence' else
                                          ['SPECIFICATION_MOI_SCOPE_UNRESOLVED'] if unresolved else []) for field in FIELDS}
        all_numeric = all(fields[f]['technicalCandidateCount'] > 0 for f in FIELDS)
        reasons = set(scope_issues)
        if b['reasonCode'] != 'BS1_DISEASE_PARAMETERS_MISSING': reasons.add(b['reasonCode'])
        if any(fields[f]['status'] == 'CONFLICT' for f in FIELDS): reasons.add('PARAMETER_CONFLICT_REQUIRES_REVIEW')
        if not all_numeric: reasons.add('COMPLETE_NUMERIC_PARAMETER_SET_NOT_AVAILABLE')
        reasons.add('CROSS_PARAMETER_APPLICABILITY_AND_ADOPTION_NOT_ASSESSED')
        drafts.append({**{k: b[k] for k in ('hgncId', 'mondoId', 'moi')}, 'schemaVersion': VERSION,
                       'status': 'UNREVIEWED', 'parameters': fields, 'exactMappingCandidates': linked,
                       'submittedOrphaCandidates': sorted(submitted),
                       'missingParameters': [LEGACY[f] for f in FIELDS if fields[f]['status'] == 'SOURCE_GAP'],
                       'blockedParameters': [LEGACY[f] for f in FIELDS if fields[f]['status'] not in {'SOURCE_GAP', 'CANDIDATE_AVAILABLE'}],
                       'completeNumericCandidateSet': all_numeric, 'modelExecutable': False,
                       'blockingReasons': sorted(reasons), 'mappingBasis': 'EXACT_OR_SUBMITTED_CANDIDATE_NOT_AUTOMATIC_ADOPTION'})
    report = {'schemaVersion': VERSION, 'backgrounds': len(drafts), 'observations': len(observations),
              'sourceScope': 'ONLY_PINNED_LOADED_INPUTS; absence is not absence from all literature',
              'fieldStatusCounts': {f: dict(sorted(Counter(d['parameters'][f]['status'] for d in drafts).items())) for f in FIELDS},
              'backgroundsWithNumericCandidates': {f: sum(d['parameters'][f]['technicalCandidateCount'] > 0 for d in drafts) for f in FIELDS},
              'completeNumericCandidateSets': sum(d['completeNumericCandidateSet'] for d in drafts),
              'backgroundsWithAnyObservation': {f: sum(bool(d['parameters'][f]['observationIds']) for d in drafts) for f in FIELDS},
              'newModelAdoptions': 0, 'newExecutableBackgrounds': 0,
              'reasonCounts': dict(sorted(Counter(r for d in drafts for r in set(d['blockingReasons'])).items())),
              'fieldReasonBackgroundCounts': {f: dict(sorted(Counter(r for d in drafts for r in d['parameters'][f]['reasonCodes']).items())) for f in FIELDS},
              'limitations': ['CSPEC_BS1_TEXT_MENTIONS_ONLY_NOT_EXHAUSTIVE_LITERATURE_EXTRACTION',
                              'NO_PARAMETER_SELECTION_OR_SCIENTIFIC_ADOPTION', 'NO_RUNTIME_POLICY_CHANGE']}
    return drafts, list(observations.values()), report
