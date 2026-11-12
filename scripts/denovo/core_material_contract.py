"""Offline data sufficiency only. Never scores, adopts, calls models or writes caches.

VERIFIED is not self-authenticating. An independently supplied, pinned review
ledger must bind every verified fact and the evaluation context. It records a
review, not a clinical certificate. Exact source checks do not prove semantics.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re

from core_material_schema import SCHEMA, VERSION
from prepare_literature import prepare

CHECKER_VERSION = 'denovo-core-readiness-1.0.0'


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def unique(pairs):
    result = {}
    for k, v in pairs:
        require(k not in result, 'DUPLICATE_JSON_KEY:' + k)
        result[k] = v
    return result


def parse(raw):
    return json.loads(raw.decode('utf-8-sig'), object_pairs_hook=unique,
                      parse_constant=lambda x: (_ for _ in ()).throw(ValueError('NONFINITE_JSON')))


def bounded(path, maximum=16 * 1024 * 1024):
    with Path(path).open('rb') as f:
        raw = f.read(maximum + 1)
    require(len(raw) <= maximum, 'FILE_TOO_LARGE')
    return raw


def validate_shape(value, schema=SCHEMA, path=''):
    """The deliberately small JSON Schema subset emitted by core_material_schema.

    No external references or permissive fallback for unknown validation keywords.
    """
    allowed = {'$schema', '$id', 'title', 'type', 'const', 'enum', 'anyOf', 'properties',
               'required', 'additionalProperties', 'minProperties', 'maxProperties',
               'items', 'minItems', 'maxItems', 'minLength', 'maxLength', 'pattern', 'minimum', 'maximum'}
    require(set(schema) <= allowed, 'SCHEMA_KEYWORD_UNSUPPORTED:' + path)
    if 'anyOf' in schema:
        for sub in schema['anyOf']:
            try:
                validate_shape(value, sub, path)
                return
            except ValueError:
                pass
        raise ValueError('SCHEMA_VALUE:' + path)
    if 'const' in schema:
        require(type(value) is type(schema['const']) and value == schema['const'], 'SCHEMA_CONST:' + path)
    if 'enum' in schema:
        require(value in schema['enum'], 'SCHEMA_ENUM:' + path)
    kind = schema.get('type')
    types = {'object': dict, 'array': list, 'string': str, 'integer': int, 'boolean': bool, 'null': type(None)}
    if kind == 'number':
        require(type(value) is int or (type(value) is float and math.isfinite(value)), 'SCHEMA_TYPE:' + path)
    elif kind:
        require(type(value) is types[kind], 'SCHEMA_TYPE:' + path)
    if kind == 'object':
        require(set(schema.get('required', [])) <= value.keys(), 'SCHEMA_REQUIRED:' + path)
        require(schema.get('minProperties', 0) <= len(value) <= schema.get('maxProperties', 10000), 'SCHEMA_PROPERTIES:' + path)
        for k, v in value.items():
            sub = schema.get('properties', {}).get(k, schema.get('additionalProperties', True))
            require(sub is not False, 'SCHEMA_UNKNOWN_FIELD:' + path + '/' + k)
            if isinstance(sub, dict):
                validate_shape(v, sub, path + '/' + k)
    elif kind == 'array':
        require(schema.get('minItems', 0) <= len(value) <= schema.get('maxItems', 10000), 'SCHEMA_ITEMS:' + path)
        for i, v in enumerate(value):
            validate_shape(v, schema['items'], path + '/' + str(i))
    elif kind == 'string':
        require(schema.get('minLength', 0) <= len(value) <= schema.get('maxLength', 1000000), 'SCHEMA_LENGTH:' + path)
        if 'pattern' in schema:
            require(re.search(schema['pattern'], value) is not None, 'SCHEMA_PATTERN:' + path)
    elif kind in ('integer', 'number'):
        require(value >= schema.get('minimum', value), 'SCHEMA_MINIMUM:' + path)
        require(value <= schema.get('maximum', value), 'SCHEMA_MAXIMUM:' + path)


def pointer(value, path):
    if path == '':
        return value
    require(path.startswith('/'), 'POINTER_INVALID')
    for part in path[1:].split('/'):
        require(re.search(r'~(?![01])', part) is None, 'POINTER_ESCAPE_INVALID')
        part = part.replace('~1', '/').replace('~0', '~')
        try:
            if isinstance(value, list):
                require(re.fullmatch(r'0|[1-9][0-9]*', part) is not None, 'POINTER_INDEX_INVALID')
                value = value[int(part)]
            else:
                value = value[part]
        except (ValueError, KeyError, IndexError, TypeError):
            raise ValueError('POINTER_NOT_FOUND:' + path) from None
    return value


def facts(document):
    result = {}
    for group, fields in document['groups'].items():
        for name, fact in fields.items():
            result['/groups/' + group + '/' + name] = fact
    for name, fact in document['context'].items():
        result['/context/' + name] = fact
    for group, fields in document['extensions'].items():
        if group == 'specialEvent':
            result['/extensions/specialEvent'] = fields
            continue
        for name, fact in fields.items():
            result['/extensions/' + group + '/' + name] = fact
    for name in ('phaseRequired', 'specialFamilyModel'):
        result['/conditions/' + name] = document['conditions'][name]
    return result


def context_binding(document):
    return digest({'caseKey': document['caseKey'], 'context': document['context'],
                   'conditions': document['conditions'], 'reviewPlan': document['reviewPlan'],
                   'sourcePins': {s: {'sha256': v['sha256'], 'role': v['role']}
                                  for s, v in document['sources'].items()}})


def load_sources(document, root):
    root = Path(root).resolve(strict=True)
    result, total = {}, 0
    for key, source in document['sources'].items():
        path = (root / source['path']).resolve(strict=True)
        require(path.is_relative_to(root) and not Path(source['path']).is_absolute(), 'SOURCE_PATH_OUTSIDE_PACKAGE')
        raw = bounded(path)
        total += len(raw)
        require(total <= 32 * 1024 * 1024, 'TOTAL_SOURCE_SIZE_LIMIT')
        require(hashlib.sha256(raw).hexdigest() == source['sha256'], 'SOURCE_SHA_MISMATCH:' + key)
        if source['format'] == 'JATS':
            doc = prepare(raw, source['pmcid'], source['sha256'])
            result[key] = ('JATS_UNIT', {u['unitId']: u['text'] for u in doc['units']})
        elif source['format'] == 'JSON':
            result[key] = ('JSON_POINTER', parse(raw))
        elif source['format'] == 'XML':
            result[key] = ('XML', raw)
        else:
            result[key] = ('TEXT', raw.decode('utf-8-sig'))
    return result


def location_text(reference, sources):
    require(reference['sourceId'] in sources, 'CITATION_SOURCE_MISSING')
    kind, value = sources[reference['sourceId']]
    require(kind == reference['kind'], 'CITATION_KIND_MISMATCH')
    if kind == 'JATS_UNIT':
        require(reference['locator'] in value, 'CITATION_UNIT_MISSING')
        return value[reference['locator']]
    if kind == 'JSON_POINTER':
        item = pointer(value, reference['locator'])
        return item if isinstance(item, str) else canonical(item).decode('utf-8')
    require(reference['locator'] == '/', 'TEXT_LOCATOR_INVALID')
    return value


def validate(document, root, ledger, expected_ledger_sha256):
    validate_shape(document)
    require(digest(ledger) == expected_ledger_sha256, 'REVIEW_LEDGER_PIN_MISMATCH')
    require(ledger.get('schemaVersion') == 'denovo-core-review-ledger-v1'
            and ledger.get('scope') == 'OFFLINE_CONTRACT_VALIDATION_ONLY', 'REVIEW_LEDGER_SCOPE')
    require(ledger.get('contextBinding') == context_binding(document), 'CONTEXT_REVIEW_BINDING_MISMATCH')
    checks = ledger.get('checks', {})
    require(isinstance(checks, dict), 'REVIEW_LEDGER_INVALID')
    sources = load_sources(document, root)
    all_facts = facts(document)
    for path, f in all_facts.items():
        require(f['caseKey'] == document['caseKey'], 'FACT_CASE_MISMATCH:' + path)
        expected_subject = next((s.upper() for s in ('proband', 'mother', 'father') if path.startswith('/groups/' + s + '/')), None)
        if expected_subject:
            require(f['subject'] == expected_subject, 'FACT_SUBJECT_MISMATCH:' + path)
        if path == '/groups/parentage/maternity':
            require(f['subject'] == 'MOTHER', 'FACT_SUBJECT_MISMATCH:' + path)
        if path == '/groups/parentage/paternity':
            require(f['subject'] == 'FATHER', 'FACT_SUBJECT_MISMATCH:' + path)
        for citation in f['citations']:
            require(citation['quote'] in location_text(citation, sources), 'CITATION_TEXT_MISMATCH:' + path)
        for scan in f['checkedLocations']:
            location_text(scan, sources)
        if f['availability'] == 'REPORTED':
            require(f['rawValue'] is not None and f['citations'], 'REPORTED_SOURCE_REQUIRED:' + path)
        elif f['availability'] == 'NOT_REPORTED_IN_CHECKED_SCOPE':
            require(f['checkedLocations'] and f['rawValue'] is None and f['value'] is None, 'ABSENCE_SCOPE_REQUIRED:' + path)
        else:
            require(f['rawValue'] is None and f['value'] is None, 'MISSING_FACT_HAS_VALUE:' + path)
        if f['verification'] == 'VERIFIED':
            require(f['origin'] != 'MODEL_PROPOSAL', 'MODEL_SELF_VERIFICATION_FORBIDDEN:' + path)
            check = checks.get(path, {})
            require(check.get('factSha256') == digest(f) and check.get('basis') and check.get('method'), 'FACT_REVIEW_REQUIRED:' + path)
    special = all_facts.get('/extensions/specialEvent')
    if special and special['verification'] == 'VERIFIED':
        from core_special_event import verify_fact
        verify_fact(document, special, checks.get('/extensions/specialEvent', {}), sources, root)
    for requirement in document['conditions']['expertRequirements']:
        require(requirement['path'].startswith('/extensions/expert/') and requirement['path'] in all_facts,
                'EXPERT_REQUIREMENT_PATH_INVALID')
    return all_facts


def evaluation_value(path, fact):
    """Keep source silence null; only the evaluation view selects unconfirmed.

    It must still pass the ledger, target-test, policy and family gates. This
    never turns NOT_EXTRACTED, UNVERIFIED, or CONFLICT into assumed parentage.
    """
    if fact['verification'] != 'VERIFIED':
        return None
    if fact['availability'] == 'REPORTED':
        return fact['value']
    if (path in {'/groups/parentage/maternity', '/groups/parentage/paternity'}
            and fact['availability'] == 'NOT_REPORTED_IN_CHECKED_SCOPE' and fact['checkedLocations']):
        return 'ASSUMED_NOT_REPORTED'
    return None


def assess(document, root, ledger, expected_ledger_sha256):
    all_facts = validate(document, root, ledger, expected_ledger_sha256)
    required, blockers = [], []

    def block(path, code, category='DATA_MISSING'):
        item = {'path': path, 'code': code, 'category': category}
        if path in all_facts:
            item['details'] = all_facts[path]['issues']
        if item not in blockers:
            blockers.append(item)

    def need(path):
        if path not in required:
            required.append(path)
        f = all_facts[path]
        if evaluation_value(path, f) == 'ASSUMED_NOT_REPORTED':
            return 'ASSUMED_NOT_REPORTED'
        if f['verification'] == 'CONFLICT':
            block(path, 'FACT_CONFLICT', 'CONFLICT')
        elif f['verification'] == 'UNSUPPORTED':
            block(path, 'FACT_VERIFICATION_UNSUPPORTED', 'SOFTWARE_UNSUPPORTED')
        elif f['availability'] != 'REPORTED':
            block(path, f['availability'])
        elif f['verification'] != 'VERIFIED' or f['value'] is None:
            block(path, 'FACT_NOT_VERIFIED', 'REVIEW_REQUIRED')
        else:
            return f['value']
        return None

    # A contradictory optional fact must not disappear just because it is not
    # on the ordinary AD minimum list (for example conflicting mosaic reports).
    for path, fact in all_facts.items():
        if fact['verification'] == 'CONFLICT':
            block(path, 'FACT_CONFLICT', 'CONFLICT')
    for subject in ('proband', 'mother', 'father'):
        path = '/groups/' + subject + '/mosaicism'
        if all_facts[path]['value'] == 'REPORTED_PRESENT':
            need(path)

    common = ['/groups/paper/identity', '/groups/paper/availability', '/groups/case/label', '/groups/case/origin',
              '/groups/gene/symbol', '/groups/variant/representations', '/groups/variant/resolvedAllele']
    for path in common:
        need(path)
    for key in ('allele', 'hgncId', 'diseaseId', 'inheritance', 'variantScope', 'geneDiseaseApplicability',
                'policyId', 'policyVersion', 'expertPolicyStatus'):
        need('/context/' + key)
    val = lambda p: evaluation_value(p, all_facts[p])
    if val('/groups/case/origin') in ('CITED_ONLY', 'UNCLEAR'):
        block('/groups/case/origin', 'PRIMARY_CASE_OR_ORIGINAL_REPORT_REQUIRED', 'REVIEW_REQUIRED')
    if val('/groups/paper/availability') == 'UNAVAILABLE':
        block('/groups/paper/availability', 'SOURCE_UNAVAILABLE')
    a, b = val('/groups/variant/resolvedAllele'), val('/context/allele')
    if a and b and a != b:
        block('/context/allele', 'EXACT_ALLELE_MISMATCH', 'NOT_APPLICABLE')
    for allele in (a, b):
        if allele and allele['reference'] == allele['alternate']:
            block('/context/allele', 'REF_EQUALS_ALT', 'CONFLICT')
    if val('/context/geneDiseaseApplicability') == 'NOT_APPLICABLE':
        block('/context/geneDiseaseApplicability', 'BACKGROUND_NOT_APPLICABLE', 'NOT_APPLICABLE')
    elif val('/context/geneDiseaseApplicability') == 'UNRESOLVED':
        block('/context/geneDiseaseApplicability', 'BACKGROUND_UNRESOLVED')
    inheritance = val('/context/inheritance')
    active = []
    special_path = '/extensions/specialEvent'
    typed_event = need(special_path) if special_path in all_facts else None
    if typed_event is not None:
        active.append('specialEvent')
        if val('/groups/case/origin') != 'AUTHOR_COHORT':
            block('/groups/case/origin', 'PRIMARY_CASE_OR_ORIGINAL_REPORT_REQUIRED', 'REVIEW_REQUIRED')
    if inheritance == 'AR' and typed_event is None:
        active.append('recessive')
        for name in ('secondAlleles', 'classification', 'classificationBasis'):
            need('/extensions/recessive/' + name)
        phase_required = need('/conditions/phaseRequired')
        if phase_required:
            if need('/extensions/recessive/phase') == 'UNKNOWN':
                block('/extensions/recessive/phase', 'PHASE_UNRESOLVED')
    elif inheritance == 'X_LINKED' and typed_event is None:
        active.append('xlinked')
        for name in ('eventPerson', 'sex', 'pedigree', 'carrierStatus', 'affectedStatus'):
            need('/extensions/xlinked/' + name)
    if inheritance and inheritance != 'AD' and (typed_event is None or inheritance not in ('AR', 'X_LINKED')):
        block('/context/inheritance', 'CURRENT_ENGINE_INHERITANCE_NOT_SUPPORTED', 'SOFTWARE_UNSUPPORTED')
    if val('/context/variantScope') == 'OTHER':
        block('/context/variantScope', 'CURRENT_ENGINE_VARIANT_SCOPE_NOT_SUPPORTED', 'SOFTWARE_UNSUPPORTED')
    special = need('/conditions/specialFamilyModel')
    # Observed special facts cannot be hidden by a caller choosing NONE.
    if special == 'UNRESOLVED':
        block('/conditions/specialFamilyModel', 'SPECIAL_FAMILY_CONTEXT_UNRESOLVED', 'REVIEW_REQUIRED')
    if typed_event is None and (special in ('MOSAIC', 'SIBLING_RECURRENCE') or any(val('/groups/' + s + '/mosaicism') == 'REPORTED_PRESENT' for s in ('proband', 'mother', 'father'))):
        active.append('mosaic')
        for name in ('individuals', 'tissuesAndAssays', 'alleleFractions', 'relationships', 'eventGrouping'):
            need('/extensions/mosaic/' + name)
        block('/conditions/specialFamilyModel', 'CURRENT_ENGINE_SPECIAL_FAMILY_NOT_SUPPORTED', 'SOFTWARE_UNSUPPORTED')
    expert = val('/context/expertPolicyStatus')
    if expert == 'GENERAL_ALLOWED' and document['conditions']['expertRequirements']:
        block('/context/expertPolicyStatus', 'EXPERT_CONDITIONS_WITH_GENERAL_POLICY', 'CONFLICT')
    if expert == 'UNRESOLVED':
        block('/context/expertPolicyStatus', 'EXPERT_POLICY_UNRESOLVED', 'REVIEW_REQUIRED')
    if expert == 'APPLICABLE_UNSUPPORTED':
        block('/context/expertPolicyStatus', 'EXPERT_POLICY_NOT_SUPPORTED', 'SOFTWARE_UNSUPPORTED')
    if expert in ('APPLICABLE_IMPLEMENTED', 'APPLICABLE_UNSUPPORTED'):
        active.append('expert')
        for r in document['conditions']['expertRequirements']:
            value = need(r['path'])
            if value is not None and r['acceptedValues'] and value not in r['acceptedValues']:
                block(r['path'], 'EXPERT_CONDITION_NOT_MET', 'NOT_APPLICABLE')
    need('/groups/phenotype/positive')
    phenotype = need('/context/phenotypeCategory')
    if typed_event is None:
        for p in ('proband', 'mother', 'father'):
            result = need('/groups/' + p + '/targetResult')
            if result in ('CONFLICT', 'QUALITY_FAILED'):
                block('/groups/' + p + '/targetResult', 'TARGET_' + result, 'CONFLICT' if result == 'CONFLICT' else 'REVIEW_REQUIRED')
        if need('/groups/testing/qualifiedForTarget') is False:
            block('/groups/testing/qualifiedForTarget', 'TARGET_TESTS_NOT_QUALIFIED', 'REVIEW_REQUIRED')
        participants = need('/groups/testing/participants')
        if participants is not None and not set(('PROBAND', 'MOTHER', 'FATHER')) <= set(participants):
            block('/groups/testing/participants', 'TARGET_TEST_PARTICIPANTS_INCOMPLETE')
        mother = need('/groups/parentage/maternity')
        father = need('/groups/parentage/paternity')
        for name, relation in (('maternity', mother), ('paternity', father)):
            if relation in ('EXCLUDED', 'INCONCLUSIVE'):
                block('/groups/parentage/' + name, 'PARENTAGE_' + relation, 'CONFLICT' if relation == 'EXCLUDED' else 'REVIEW_REQUIRED')
        if mother and father and (mother != 'CONFIRMED' or father != 'CONFIRMED'):
            if need('/context/allowAssumedParentage') is False:
                block('/context/allowAssumedParentage', 'ASSUMED_PARENTAGE_POLICY_NOT_APPLICABLE', 'NOT_APPLICABLE')
        family = need('/groups/family/compatibility')
        if family in ('UNRESOLVED', 'CONFLICT', 'SPECIAL_MODEL'):
            block('/groups/family/compatibility', 'FAMILY_' + family, 'CONFLICT' if family == 'CONFLICT' else 'REVIEW_REQUIRED')
    if typed_event is not None and typed_event['kind'] == 'CONSTITUTIONAL':
        family = need('/groups/family/compatibility')
        if family in ('UNRESOLVED', 'CONFLICT', 'SPECIAL_MODEL'):
            block('/groups/family/compatibility', 'FAMILY_' + family, 'CONFLICT' if family == 'CONFLICT' else 'REVIEW_REQUIRED')
    need('/groups/overlap/eventId')
    independence = need('/groups/overlap/independence')
    if independence == 'POSSIBLE_OVERLAP':
        block('/groups/overlap/independence', 'EVENT_OVERLAP_UNRESOLVED', 'REVIEW_REQUIRED')
    if independence == 'KNOWN_DUPLICATE':
        block('/groups/overlap/independence', 'DUPLICATE_NOT_ADDITIONAL_EVENT', 'NOT_APPLICABLE')
    if document['reviewPlan']['unresolvedDisagreements']:
        block('/reviewPlan/unresolvedDisagreements', 'REVIEW_DISAGREEMENT_UNRESOLVED', 'CONFLICT')
    categories = {b['category'] for b in blockers}
    status = ('NOT_APPLICABLE' if 'NOT_APPLICABLE' in categories else
              'SOFTWARE_UNSUPPORTED' if 'SOFTWARE_UNSUPPORTED' in categories else
              'INSUFFICIENT_DATA' if blockers else 'READY_FOR_EVALUATION')
    fact_paths = [p for p in all_facts if p.startswith('/groups/')]
    if typed_event is not None:
        fact_paths.append('/extensions/specialEvent')
    fact_sources = {c['sourceId'] for p in fact_paths for c in all_facts[p]['citations'] + all_facts[p]['checkedLocations']}
    fact_payload = {'schema': VERSION, 'caseKey': document['caseKey'], 'groups': document['groups'],
                       'sources': {s: document['sources'][s]['sha256'] for s in sorted(fact_sources)},
                       'checks': {p: ledger['checks'].get(p) for p in fact_paths}}
    if typed_event is not None:
        fact_payload['specialEvent'] = document['extensions']['specialEvent']
    fact_key = digest(fact_payload)
    extraction_key = digest({s: {'sha256': v['sha256'], 'role': v['role']}
                             for s, v in document['sources'].items()
                             if v['role'] in ('PAPER_PRIMARY', 'PAPER_ATTACHMENT')})
    return {'schemaVersion': 'denovo-core-readiness-result-v1', 'checkerVersion': CHECKER_VERSION,
            'status': status, 'scope': 'OFFLINE_CONTRACT_VALIDATION_ONLY', 'criterionDecision': 'NOT_RUN',
            'requiredFactPaths': required, 'activeExtensions': active, 'blockers': blockers,
            'observedNegativeFacts': {'phenotypeNotConsistent': phenotype == 'NOT_CONSISTENT',
                                      'parentalTargetPresent': any(val('/groups/' + p + '/targetResult') == 'PRESENT' for p in ('mother', 'father'))},
            'extractionSourceKey': extraction_key, 'verifiedFactsKey': fact_key,
            'evaluationKey': digest({'facts': fact_key, 'context': document['context'], 'conditions': document['conditions'],
                                     'extensions': document['extensions'], 'reviews': ledger,
                                     'reviewPlan': document['reviewPlan'],
                                     'sourcePins': {s: v['sha256'] for s, v in document['sources'].items()},
                                     'checker': CHECKER_VERSION}),
            'secondPassStatus': document['reviewPlan']['secondPassStatus'],
            'coverageLimitations': document['reviewPlan']['unavailableSources'] + document['reviewPlan']['unprocessedScope']}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('input', 'sources', 'review-ledger', 'review-ledger-sha256', 'output'):
        p.add_argument('--' + name, required=True)
    a = p.parse_args()
    doc = parse(bounded(a.input, 4 * 1024 * 1024))
    ledger = parse(bounded(a.review_ledger, 4 * 1024 * 1024))
    result = assess(doc, a.sources, ledger, a.review_ledger_sha256)
    with Path(a.output).open('xb') as out:
        out.write(canonical(result) + b'\n')
    print(json.dumps({'status': result['status'], 'blockers': len(result['blockers']), 'modelCalls': 0, 'scientificEvaluations': 0}))


if __name__ == '__main__':
    main()
