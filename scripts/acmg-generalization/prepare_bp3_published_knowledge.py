"""Derive BP3 source conditions once per pinned specification, never per variant.

Only a closed, full-text region grammar is executable. Generic BP3 wording does
not establish absence of known function. Contradictions and additional population
conditions stay visible. No network, clinical approval or runtime activation.
"""
import argparse
import json
from pathlib import Path
import re
from prepare_published_criterion_knowledge import compile_plan, load_specifications, criterion_scopes, pointer
from assemble_materials import pinned, parse, write_new

CRITERION = 'ACMG_AMP_2015:BP3'
TYPE = 'PUBLISHED_BP3_EXPLICIT_REPEAT_REGION_V1'
NA = {'not applicable', 'not applicable for this vcep'}
REGION = re.compile(
    r'Use for ([A-Za-z0-9_-]+) repetitive regions only \(amino acids ([1-9][0-9]*)-([1-9][0-9]*)\)\. '
    r'These events can be multiple and large \(e\.g\. 9 plus 15 total 20-30 bp is known, a single 21bp event is common\)\. '
    r'Please note that \1 encompasses residues ([1-9][0-9]*) through ([1-9][0-9]*), '
    r'with UniProt identifying particularly disordered regions between ([0-9]+-[0-9]+(?:, [0-9]+-[0-9]+)*), and ([0-9]+-[0-9]+)\.')


def normalized(value):
    return ' '.join((value or '').split())


def derive(clause):
    app = normalized(clause.get('applicability')).lower()
    texts = [normalized(clause.get(k)) for k in ('additionalComments', 'instructionsToUse')]
    active = [s for s in clause.get('strengthDescriptor', []) if s.get('applicability') == 'Applicable']
    bodies = texts + [normalized(s.get('text')) for s in active]
    negative = any(re.match(r'(?i)^(?:not applicable\b|do not use\b)', t) for t in bodies)
    if app in NA:
        return 'SOURCE_CONFLICT' if active else 'NOT_APPLICABLE', 'BP3_SOURCE_APPLICABILITY_CONFLICT' if active else 'BP3_SOURCE_NOT_APPLICABLE', None
    if app != 'applicable':
        return 'UNSUPPORTED_CONDITIONS', 'BP3_SOURCE_APPLICABILITY_UNRESOLVED', None
    if negative:
        return 'SOURCE_CONFLICT', 'BP3_SOURCE_APPLICABILITY_CONFLICT', None
    if any('BA1' in t or 'frequency commensurate' in t for t in bodies):
        return 'UNSUPPORTED_CONDITIONS', 'BP3_OTHER_INFRAME_POPULATION_EVIDENCE_REQUIRED', None
    if (len(active) != 1 or active[0].get('strength') != 'Supporting'
            or active[0].get('status') != 'approved'):
        return 'UNSUPPORTED_CONDITIONS', 'BP3_SOURCE_STRENGTH_UNRESOLVED', None
    match = REGION.fullmatch(normalized(active[0].get('text')))
    if (not any(texts) and not normalized(active[0].get('instructionsToUse')) and match
            and clause.get('baseStrength') == 'Benign' and clause.get('defaultStrength') == 'Benign Supporting'):
        name, start, end, outer_start, outer_end, ranges, last = match.groups()
        start, end, outer_start, outer_end = map(int, (start, end, outer_start, outer_end))
        spans = [tuple(map(int, p.split('-'))) for p in (ranges + ', ' + last).split(', ')]
        if (outer_start <= start <= end <= outer_end <= 1_000_000
                and all(outer_start <= a <= b <= outer_end for a, b in spans)):
            return 'EXECUTABLE', 'BP3_SUPPORTED_PUBLISHED_REGION_GRAMMAR', {
                'type': TYPE, 'version': '1.0.0', 'regionName': name, 'start': start, 'end': end,
                'modelRegionStart': outer_start, 'modelRegionEnd': outer_end,
                'strength': 'SUPPORTING', 'coordinateSystem': 'REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE',
                'supportedVariantScope': 'SINGLE_SIMPLE_INFRAME_COMPLETE_EQUIVALENT_ENVELOPE'}
    return 'UNSUPPORTED_CONDITIONS', 'BP3_PUBLISHED_REGION_OR_CONDITIONS_REQUIRED', None


def prepare(config):
    source = config['catalog']
    specs, _ = load_specifications(parse(pinned(source['file'], source['sha256'], 32_000_000)), Path(config['captureDirectory']))
    plan = {k: config[k] for k in ('catalog', 'captureDirectory', 'policyId', 'policyVersion')}
    plan.update(schemaVersion='published-criterion-interpretation-plan-v1',
                adoptionMode='PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS', clinicalValidationCompleted=False, records=[])
    audit = {'schemaVersion': 'bp3-published-source-audit-v1', 'clinicalValidationCompleted': False, 'clauses': []}
    for spec in specs:
        original = spec['original']
        for ci, wrapper in enumerate(original.get('ld', {}).get('CriteriaCode', [])):
            clause = wrapper.get('entContent', {})
            if clause.get('label') != 'BP3':
                continue
            status, reason, interpretation = derive(clause)
            entry = {'specificationKey': spec['specificationKey'], 'clausePointer': f'/ld/CriteriaCode/{ci}',
                     'status': status, 'reasonCode': reason, 'retainedScopes': 0, 'unresolvedScopes': [], 'originalClause': wrapper}
            audit['clauses'].append(entry)
            for association in criterion_scopes(original, CRITERION):
                if association['geneSymbol'] not in clause.get('gene', []):
                    continue
                diseases = clause.get('disease', [])
                if diseases and association['scope']['mondoId'] not in diseases:
                    continue
                tx = association['rawGeneAssociation'].get('preferredTranscript', '')
                disease = pointer(original, association['scopePointer'])
                modes = disease.get('modesOfInheritance', [])
                if not isinstance(modes, list) or any(m.get('modeOfInheritance', '').strip()
                        != disease.get('preferredModeOfInheritance', '').strip() for m in modes):
                    entry['unresolvedScopes'].append({**association, 'scopeStatus': 'SOURCE_INHERITANCE_CONFLICT'})
                    continue
                if association['scopeStatus'] != 'EXACT' or not re.fullmatch(r'(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*', tx):
                    entry['unresolvedScopes'].append(association)
                    continue
                plan['records'].append({'recordId': f"BP3:{spec['specificationKey']}:{ci}:{len(plan['records'])}",
                    'criterionId': CRITERION, 'specificationKey': spec['specificationKey'], 'clausePointer': entry['clausePointer'],
                    'scopePointer': association['scopePointer'], 'transcript': tx, 'status': status, 'reasonCode': reason,
                    'interpretation': interpretation or {'type': 'BP3_RETAINED_UNEXECUTABLE_SOURCE_V1', 'version': '1.0.0'}})
                entry['retainedScopes'] += 1
    return compile_plan(plan), audit, plan


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--config-sha256', required=True)
    parser.add_argument('--output-directory', required=True)
    args = parser.parse_args()
    output = Path(args.output_directory)
    output.mkdir(parents=True, exist_ok=False)
    knowledge, audit, plan = prepare(parse(pinned(args.config, args.config_sha256, 1_000_000)))
    for name, doc in [('knowledge', knowledge), ('audit', audit), ('plan', plan)]:
        write_new(output / (name + '.json'), doc)
    print(json.dumps({'clauses': len(audit['clauses']), 'records': len(knowledge['records']),
                      'executable': sum(r['status'] == 'EXECUTABLE' for r in knowledge['records'])}))


if __name__ == '__main__':
    main()
