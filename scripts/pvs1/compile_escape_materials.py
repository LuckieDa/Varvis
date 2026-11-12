"""Compile pinned, interpreted escape evidence into reviewable PVS1 materials.

This is a data compiler, not a text classifier or scientific approval service.
It preserves the old NMD policy and fills unknown population facts explicitly.
The existing material reader/assembler consumes its candidate bundle format;
neither a published source nor a reviewed base policy approves the new domain.
"""
import argparse
import copy
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from assemble_materials import content_hash, historical_materials, parse, pinned, require, write_new
from compile_nmd_conditions import positive, COORDINATES


def text(value):
    return isinstance(value, str) and bool(value.strip())


def sourced(value, sources):
    ids = value.get('sourceIds')
    return (text(value.get('basis')) and isinstance(ids, list) and bool(ids)
            and all(isinstance(x, str) for x in ids)
            and len(set(ids)) == len(ids) and set(ids) <= sources)


def candidate_bundle(original, replacements):
    """One original catalog stays whole; replace all compiled policies together.

    Per-policy bundles from a multi-transcript catalog must not be unioned: doing
    that would reintroduce the old implementations alongside their replacements.
    """
    suffix = '_ESCAPE_' + content_hash(replacements)[:16]
    catalog = copy.deepcopy(original['catalog'])
    catalog['catalogId'] += suffix
    catalog['review'] = {'status': 'UNREVIEWED', 'reviewId': ''}
    for e in catalog['entries']:
        p = replacements.get(e.get('policyBinding', {}).get('policyId'))
        if p is not None:
            e['entryId'] += suffix
            e['policyBinding'] = {k: p[k] for k in ['policyId', 'policyVersion']}
            e['policyBinding']['policySha256'] = content_hash(p)
            e['review'] = {'status': 'UNREVIEWED', 'reviewId': ''}
            e['baseRuleRelationship'] += ' New escape domain is a separate unreviewed translation; no generic fallback.'
    return {'schemaVersion': 'pvs1-truncating-nmd-bundle-candidate-v1',
        'bundleId': original.get('bundleId', catalog['catalogId']) + suffix, 'bundleVersion': '3.0.0',
        'activation': {'status': 'UNREVIEWED', 'defaultEnabled': False, 'classificationEnabled': False, 'adoptionSupported': False},
        'runtimeResourceBinding': copy.deepcopy(original['runtimeResourceBinding']),
        'transcriptModelResources': copy.deepcopy(original['transcriptModelResources']),
        'catalog': catalog, 'catalogSha256': content_hash(catalog),
        'policies': [copy.deepcopy(replacements.get(p['policyId'], p)) for p in original['policies']],
        'sourceReviewBindings': [], 'acceptance': {'status': 'UNREVIEWED', 'originalNmdReviewNotTransferred': True}}


def compile_policy(material, recipe):
    p = material['policy']; model = (material.get('modelMaterial') or {}).get('model')
    require(isinstance(recipe, dict) and set(recipe) == {
        'basePolicySha256', 'modelSha256', 'basis', 'sourceIds', 'sourceLocators',
        'rules', 'populationFacts', 'positionConditions'}, 'ESCAPE_RECIPE_FIELDS_INVALID')
    require(p.get('schemaVersion') in {'pvs1-truncating-nmd-policy-v1', 'pvs1-truncating-nmd-policy-v2'}
            and material['policySha256'] == content_hash(p) == recipe['basePolicySha256']
            and model is not None and content_hash(model) == recipe['modelSha256']
            == p['transcriptModelBinding']['canonicalSha256'], 'ESCAPE_BASE_BINDING_INVALID')
    require(p.get('review', {}).get('status') == 'REVIEWED_IMPLEMENTED'
            and p.get('mechanism', {}).get('status') == 'ESTABLISHED_LOSS_OF_FUNCTION'
            and text(p.get('nmdScope', {}).get('exonRelevance'))
            and not p['nmdScope']['exonRelevance'].startswith('PENDING'), 'ESCAPE_BASE_APPLICABILITY_UNRESOLVED')
    checks = material.get('sourceChecks', [])
    sources = {s['sourceId'] for s in p['scientificSources']}
    require(sources and len(sources) == len(p['scientificSources'])
            and checks and all(c['status'] == 'RAW_SHA_MATCH' for c in checks)
            and {c['source']['sourceId'] for c in checks} == sources, 'ESCAPE_SOURCE_BYTES_REQUIRED')
    require(sourced(recipe, sources), 'ESCAPE_SOURCE_BINDING_INVALID')
    locators = recipe['sourceLocators']
    require(isinstance(locators, list) and locators and all(isinstance(x, dict) and
            set(x) == {'sourceId', 'locator', 'interpretation'} and x['sourceId'] in sources
            and text(x['locator']) and text(x['interpretation']) for x in locators), 'ESCAPE_SOURCE_LOCATOR_REQUIRED')
    located = {x['sourceId'] for x in locators}
    require(set(recipe['sourceIds']) <= located, 'ESCAPE_SOURCE_LOCATOR_MISSING')
    exons = model['exons']; relevant = p['nmdScope']['relevantExonOrdinals']
    require(exons and [e['ordinal'] for e in exons] == list(range(1, len(exons)+1))
            and isinstance(relevant, list) and relevant and len(set(relevant)) == len(relevant)
            and all(positive(i) and i <= len(exons) for i in relevant), 'ESCAPE_RELEVANT_EXONS_INVALID')
    length = model['proteinLength']; require(positive(length), 'ESCAPE_PROTEIN_LENGTH_INVALID')
    rules = recipe['rules']; ids = set()
    require(isinstance(rules, list) and 0 < len(rules) <= 32, 'ESCAPE_RULE_COUNT_INVALID')
    for r in rules:
        require(isinstance(r, dict) and sourced(r, sources) and set(r['sourceIds']) <= located
                and text(r.get('ruleId')) and r['ruleId'] not in ids
                and r.get('strength') in {'VERY_STRONG', 'STRONG', 'MODERATE', 'SUPPORTING'}, 'ESCAPE_RULE_IDENTITY_INVALID')
        ids.add(r['ruleId'])
        common = {'ruleId', 'predicate', 'strength', 'sourceIds', 'basis'}
        if r.get('predicate') == 'CRITICAL_REGION':
            require(set(r) == common | {'start', 'end', 'minimumAffectedResidues'}
                    and all(positive(r[k]) for k in ['start', 'end', 'minimumAffectedResidues'])
                    and r['start'] <= r['end'] <= length
                    and r['minimumAffectedResidues'] <= r['end']-r['start']+1, 'ESCAPE_REGION_INVALID')
        elif r.get('predicate') == 'PROTEIN_LOSS_FRACTION':
            require(set(r) == common | {'metric', 'comparison', 'numerator', 'denominator'}
                    and r['metric'] in {'REFERENCE_TAIL_ALTERED_OR_REMOVED', 'NET_PROTEIN_SHORTENING'}
                    and r['comparison'] in {'GT', 'LT'}
                    and positive(r['numerator']) and positive(r['denominator'])
                    and r['numerator'] < r['denominator'] and r['strength'] != 'VERY_STRONG', 'ESCAPE_FRACTION_INVALID')
        else:
            raise ValueError('ESCAPE_PREDICATE_UNSUPPORTED')
    # Runtime rules are alternatives, not an ordered decision tree. Do not
    # silently flatten an expert tree's conditional fallback into parallel rules.
    require(len({r['predicate'] for r in rules}) == 1, 'ESCAPE_MIXED_BRANCHES_REQUIRE_EXPLICIT_SCOPE')
    population = recipe['populationFacts']; facts = {}
    require(isinstance(population, list) and len(population) <= len(exons), 'ESCAPE_POPULATION_FACTS_INVALID')
    for f in population:
        require(isinstance(f, dict) and set(f) == {'ordinal', 'populationLof', 'basis', 'sourceIds'}
                and positive(f['ordinal']) and f['ordinal'] <= len(exons) and f['ordinal'] not in facts
                and f['populationLof'] in {'NOT_FREQUENT', 'FREQUENT', 'UNKNOWN'}
                and sourced(f, sources) and set(f['sourceIds']) <= located, 'ESCAPE_POPULATION_FACT_INVALID')
        facts[f['ordinal']] = f
    conditions = recipe['positionConditions']; ids = set()
    require(isinstance(conditions, list) and len(conditions) <= 16, 'ESCAPE_POSITION_CONDITIONS_INVALID')
    for c in conditions:
        require(isinstance(c, dict) and {'conditionId', 'coordinate', 'sourceIds', 'basis'} <= set(c)
                and set(c) <= {'conditionId', 'coordinate', 'sourceIds', 'basis', 'minimumInclusive', 'maximumExclusive'}
                and text(c['conditionId']) and c['conditionId'] not in ids and c['coordinate'] in COORDINATES
                and sourced(c, sources) and set(c['sourceIds']) <= located
                and ('minimumInclusive' in c or 'maximumExclusive' in c)
                and all(k not in c or positive(c[k]) for k in ['minimumInclusive', 'maximumExclusive'])
                and c.get('minimumInclusive', 0) < c.get('maximumExclusive', 2147483648), 'ESCAPE_POSITION_CONDITION_INVALID')
        ids.add(c['conditionId'])
    candidate = copy.deepcopy(p)
    candidate.update(schemaVersion='pvs1-truncating-nmd-policy-v3',
                     policyId=p['policyId']+'_ESCAPE_'+content_hash(recipe)[:16], policyVersion='3.0.0')
    candidate['review'] = {'status': 'UNREVIEWED', 'reviewId': '',
                           'kind': 'NEW_ESCAPE_DOMAIN_REQUIRES_REVIEW'}
    scope = {'status': 'UNREVIEWED', 'reviewId': '', 'basis': recipe['basis'],
             'sourceIds': copy.deepcopy(recipe['sourceIds']), 'rules': copy.deepcopy(rules), 'exonAssessments': []}
    for e in exons:
        ordinal = e['ordinal']; f = facts.get(ordinal)
        scope['exonAssessments'].append({'ordinal': ordinal,
            'biologicalRelevance': 'PRESENT' if ordinal in relevant else 'UNKNOWN',
            'populationLof': f['populationLof'] if f else 'UNKNOWN',
            'basis': 'Transcript/exon relevance retained from pinned base NMD policy. ' +
                (f['basis'] if f else 'No population tolerance evidence supplied; no absence inference.'),
            'sourceIds': sorted(set(p['nmdScope']['sourceIds']) | set(f['sourceIds'] if f else []))})
    if conditions: scope['positionConditions'] = copy.deepcopy(conditions)
    candidate['escapeScope'] = scope
    candidate['scientificBoundaryNotes'] = copy.deepcopy(p.get('scientificBoundaryNotes', [])) + [
        'New escape domain is UNREVIEWED; the original NMD review is not inherited.',
        'Pinned source bytes establish provenance, not correctness of their interpretation or clinical approval.', recipe['basis']]
    unresolved = [e['ordinal'] for e in scope['exonAssessments'] if e['populationLof'] == 'UNKNOWN']
    result = {'schemaVersion': 'pvs1-escape-material-candidate-v1', 'status': 'UNREVIEWED',
            'runtimeEligible': False, 'clinicalAcceptance': False,
            'basePolicy': copy.deepcopy(p), 'basePolicySha256': content_hash(p),
            'model': copy.deepcopy(model), 'modelSha256': content_hash(model),
            'recipe': copy.deepcopy(recipe), 'sourceChecks': copy.deepcopy(checks),
            'policy': candidate, 'policySha256': content_hash(candidate),
            'gaps': {'newDomainReviewRequired': True, 'populationUnknownExons': unresolved,
                     'populationRequiredByRules': any(r['predicate'] == 'PROTEIN_LOSS_FRACTION' for r in rules)}}
    return result


def compile_batch(config):
    require(isinstance(config, dict) and set(config) == {'schemaVersion', 'materialsConfig', 'recipes'}
            and config['schemaVersion'] == 'pvs1-escape-supply-input-v1'
            and isinstance(config['recipes'], list) and 0 < len(config['recipes']) <= 1000, 'ESCAPE_INPUT_INVALID')
    ref = config['materialsConfig']
    require(isinstance(ref, dict) and set(ref) == {'file', 'sha256'}, 'ESCAPE_MATERIAL_CONFIG_INVALID')
    source_config = parse(pinned(ref['file'], ref['sha256']))
    materials = {m['policySha256']: m for m in historical_materials(source_config)}
    results = []; grouped = {}; originals = {}; seen = set()
    for recipe in config['recipes']:
        try:
            require(isinstance(recipe, dict) and recipe.get('basePolicySha256') in materials, 'ESCAPE_BASE_NOT_FOUND')
            # Conflicting replacement recipes must not silently pick a winner.
            require(recipe['basePolicySha256'] not in seen, 'ESCAPE_DUPLICATE_BASE_RECIPE')
            seen.add(recipe['basePolicySha256'])
            material = materials[recipe['basePolicySha256']]
            compiled = compile_policy(material, recipe); results.append(compiled)
            key = material['candidateBundleRawSha256']; originals[key] = material['originalCandidate']
            grouped.setdefault(key, {})[material['policy']['policyId']] = compiled['policy']
        except (ValueError, KeyError, TypeError) as error:
            results.append({'status': 'BLOCKED', 'runtimeEligible': False, 'reasonCode': str(error), 'recipe': copy.deepcopy(recipe)})
    # Any repeated base is a whole-batch ambiguity, not a partially usable first
    # recipe plus a dropped conflicting row.
    require(not any(r.get('reasonCode') == 'ESCAPE_DUPLICATE_BASE_RECIPE' for r in results), 'ESCAPE_DUPLICATE_BASE_RECIPE')
    return {'schemaVersion': 'pvs1-escape-supply-v1', 'status': 'REVIEW_CANDIDATES_ONLY',
            'runtimeEligible': False, 'clinicalAcceptance': False, 'materialsConfig': copy.deepcopy(ref),
            'candidateBundles': [{'originalCandidateRawSha256': k, 'bundle': candidate_bundle(originals[k], v)} for k, v in grouped.items()],
            'results': results, 'counts': {'candidates': sum(r['status'] == 'UNREVIEWED' for r in results),
                                          'blocked': sum(r['status'] == 'BLOCKED' for r in results), 'newScientificAdoptions': 0}}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--input', required=True); ap.add_argument('--input-sha256', required=True); ap.add_argument('--output', required=True)
    a = ap.parse_args()
    result = compile_batch(parse(pinned(a.input, a.input_sha256)))
    result['inputRawSha256'] = a.input_sha256
    write_new(Path(a.output), result)
    print(json.dumps(result['counts']))


if __name__ == '__main__': main()
