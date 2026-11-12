"""Compile explicit project research interpretations against existing pinned CSpec originals.

No network, clinical approval, runtime activation or gene-specific inference. The
caller supplies interpreted conditions; each criterion's runtime enforces its
supported grammar. This tool preserves the entire specification and never turns
an Applicable flag into scientific facts. A source inventory's BA1 approval is
not inherited by PM1, PP2 or BP1.
"""
from __future__ import annotations
import argparse
import copy
import json
from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'pvs1'))
from prepare_applicability_sources import load_specifications, scopes, pointer, MOI
from assemble_materials import pinned, parse, require, content_hash, write_new, child

VERSION = '1.0.0'
CRITERIA = {'ACMG_AMP_2015:PM1', 'ACMG_AMP_2015:PP2', 'ACMG_AMP_2015:BP1', 'ACMG_AMP_2015:BP3'}
SCOPE_FIELDS = {'hgncId', 'mondoId', 'moi', 'assembly', 'transcript'}
TRANSCRIPT = re.compile(r'(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*')


def source_moi(value, criterion):
    if not isinstance(value, str):
        return None
    value = value.strip()
    if value == 'X-linked inheritance' and criterion == 'ACMG_AMP_2015:BP3':
        return 'X_LINKED'
    return MOI.get(value)


def validate_source_refs(catalog, capture):
    """Reject source references which the Java knowledge loader cannot represent."""
    require(isinstance(catalog, dict) and isinstance(catalog.get('entries'), list), 'PROJECT_SOURCE_REF_INVALID')
    for item in catalog['entries']:
        require(isinstance(item, dict) and isinstance(item.get('source'), dict), 'PROJECT_SOURCE_REF_INVALID')
        source = item['source']
        require(isinstance(item.get('specificationId'), str) and re.fullmatch(r'GN[0-9]+', item['specificationId'])
                and isinstance(source.get('rawSha256'), str) and re.fullmatch('[0-9a-f]{64}', source['rawSha256'])
                and isinstance(source.get('jsonPointer'), str) and re.fullmatch(r'/data(?:/(?:0|[1-9][0-9]*))?', source['jsonPointer'])
                and isinstance(source.get('sourceUrl'), str) and re.fullmatch(r'https://[^\s]+', source['sourceUrl'])
                and item.get('rawRef') == source.get('rawRef') and item.get('rawSha256') == source['rawSha256'],
                'PROJECT_SOURCE_REF_INVALID')
        child(capture, source.get('rawRef'))


def criterion_scopes(original, criterion):
    """BP3's published X-linked scope covers either explicitly selected X-linked model.

    This is source taxonomy, not an inferred patient inheritance model. Other
    criteria retain their existing exact AD/AR selection.
    """
    result = copy.deepcopy(scopes(original))
    if criterion == 'ACMG_AMP_2015:BP3':
        for item in result:
            if (item['scopeStatus'] == 'INHERITANCE_NOT_MAPPED'
                    and pointer(original, item['scopePointer']).get('preferredModeOfInheritance', '').strip()
                    == 'X-linked inheritance'):
                item['scope']['moi'] = 'X_LINKED'
                item['scopeStatus'] = 'EXACT'
    return result


def compile_plan(config):
    require(isinstance(config, dict) and config.get('schemaVersion') == 'published-criterion-interpretation-plan-v1'
            and config.get('clinicalValidationCompleted') is False
            and config.get('adoptionMode') == 'PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS', 'PROJECT_RESEARCH_PLAN_REQUIRED')
    require(all(isinstance(config.get(k), str) and config[k].strip() for k in ('policyId', 'policyVersion')),
            'PROJECT_POLICY_IDENTITY_REQUIRED')
    source = config.get('catalog')
    require(isinstance(source, dict) and isinstance(source.get('file'), str)
            and isinstance(source.get('sha256'), str) and isinstance(config.get('captureDirectory'), str),
            'PROJECT_SOURCE_REF_INVALID')
    catalog = parse(pinned(source['file'], source['sha256'], 32_000_000))
    validate_source_refs(catalog, Path(config['captureDirectory']))
    try:
        specs, _ = load_specifications(catalog, Path(config['captureDirectory']))
    except (KeyError, TypeError, AttributeError, IndexError) as error:
        raise ValueError('PROJECT_SOURCE_GRAPH_INVALID') from error
    by_key = {s['specificationKey']: s for s in specs}
    plans = config.get('records')
    require(isinstance(plans, list) and len(plans) <= 20_000, 'PROJECT_RECORDS_INVALID')
    rows, selected, ids, selection_keys = [], {}, set(), set()
    for plan in plans:
        require(isinstance(plan, dict) and isinstance(plan.get('criterionId'), str) and plan['criterionId'] in CRITERIA
                and isinstance(plan.get('recordId'), str) and plan['recordId'].strip() and plan['recordId'] not in ids,
                'PROJECT_RECORD_IDENTITY_INVALID')
        ids.add(plan['recordId'])
        require(isinstance(plan.get('specificationKey'), str) and plan['specificationKey'] in by_key,
                'PROJECT_SPECIFICATION_NOT_FOUND')
        spec = by_key[plan['specificationKey']]
        original = spec['original']
        cp, sp = plan.get('clausePointer'), plan.get('scopePointer')
        require(isinstance(cp, str) and re.fullmatch('/ld/CriteriaCode/(?:0|[1-9][0-9]*)', cp), 'PROJECT_CLAUSE_POINTER_INVALID')
        matches = [s for s in criterion_scopes(original, plan['criterionId']) if s['scopePointer'] == sp and s['scopeStatus'] == 'EXACT']
        require(len(matches) == 1, 'PROJECT_EXACT_SOURCE_SCOPE_REQUIRED')
        association = matches[0]
        clauses = original.get('ld', {}).get('CriteriaCode', [])
        require(int(cp.rsplit('/', 1)[1]) < len(clauses), 'PROJECT_CLAUSE_POINTER_INVALID')
        clause = pointer(original, cp)
        content = clause.get('entContent')
        require(isinstance(content, dict) and isinstance(content.get('gene'), list)
                and all(isinstance(g, str) and g.strip() for g in content['gene'])
                and 'ACMG_AMP_2015:' + str(content.get('label')) == plan['criterionId']
                and association['geneSymbol'] in content['gene'], 'PROJECT_CRITERION_SOURCE_MISMATCH')
        cd = content.get('disease')
        require('disease' not in content or isinstance(cd, list) and all(isinstance(d, str) for d in cd)
                and (not cd or association['scope']['mondoId'] in cd),
                'PROJECT_CLAUSE_DISEASE_MISMATCH')
        disease = pointer(original, sp)
        modes = disease.get('modesOfInheritance', [])
        preferred_moi = source_moi(disease.get('preferredModeOfInheritance'), plan['criterionId'])
        require(isinstance(modes, list) and preferred_moi is not None
                and all(isinstance(m, dict) and source_moi(m.get('modeOfInheritance'), plan['criterionId']) == preferred_moi
                        for m in modes), 'PROJECT_SOURCE_INHERITANCE_CONFLICT')
        gene = association['rawGeneAssociation']
        tx = plan.get('transcript')
        transcripts = gene.get('transcripts')
        require((gene.get('preferredTranscript') is None or isinstance(gene['preferredTranscript'], str))
                and (transcripts is None or isinstance(transcripts, list)
                     and all(isinstance(t, str) and t.strip() for t in transcripts)),
                'PROJECT_EXPLICIT_SOURCE_TRANSCRIPT_REQUIRED')
        require(isinstance(tx, str) and TRANSCRIPT.fullmatch(tx)
                and (tx == gene.get('preferredTranscript') or tx in (transcripts or [])),
                'PROJECT_EXPLICIT_SOURCE_TRANSCRIPT_REQUIRED')
        require(isinstance(plan.get('status'), str) and plan['status'] in {'EXECUTABLE', 'NOT_APPLICABLE', 'UNSUPPORTED_CONDITIONS', 'SOURCE_CONFLICT'}
                and isinstance(plan.get('reasonCode'), str) and re.fullmatch('[A-Z][A-Z0-9_]+', plan['reasonCode']),
                'PROJECT_STATUS_INVALID')
        require(plan['status'] != 'EXECUTABLE' or content.get('applicability') == 'Applicable', 'PROJECT_CLAUSE_NOT_APPLICABLE')
        applicability = content.get('applicability')
        require(plan['status'] != 'NOT_APPLICABLE' or isinstance(applicability, str)
                and ' '.join(applicability.split()).lower() in {'not applicable', 'not applicable for this vcep'},
                'PROJECT_SOURCE_NONAPPLICABILITY_REQUIRED')
        if plan['status'] == 'NOT_APPLICABLE':
            descriptors = content.get('strengthDescriptor', [])
            require(isinstance(descriptors, list)
                    and all(isinstance(d, dict) and isinstance(d.get('applicability'), str)
                            and d['applicability'].strip() for d in descriptors),
                    'PROJECT_SOURCE_STRENGTH_DESCRIPTORS_INVALID')
            require(not any(d['applicability'].strip().lower() == 'applicable' for d in descriptors),
                    'PROJECT_SOURCE_APPLICABILITY_CONFLICT')
        resolved_scope = {**association['scope'], 'assembly': 'GRCh38', 'transcript': tx}
        if 'expectedScope' in plan:
            require(isinstance(plan['expectedScope'], dict) and set(plan['expectedScope']) == SCOPE_FIELDS
                    and plan['expectedScope'] == resolved_scope, 'PROJECT_EXPECTED_SCOPE_MISMATCH')
        selection_key = (plan['criterionId'], *(resolved_scope[k] for k in sorted(SCOPE_FIELDS)))
        require(selection_key not in selection_keys, 'PROJECT_EXACT_SELECTION_AMBIGUOUS')
        selection_keys.add(selection_key)
        interpretation = plan.get('interpretation')
        require(isinstance(interpretation, dict) and all(isinstance(interpretation.get(k), str)
                and interpretation[k].strip() for k in ('type', 'version')), 'PROJECT_INTERPRETATION_REQUIRED')
        require(not any(k in plan for k in ('review', 'reviewStatus', 'scientificApproval')), 'PROJECT_CANNOT_CREATE_REVIEW')
        rows.append({**{k: copy.deepcopy(plan[k]) for k in ('recordId', 'criterionId', 'specificationKey',
                    'status', 'reasonCode', 'interpretation')}, 'clausePointer': cp, 'clauseSha256': content_hash(clause),
                    'scopePointer': sp, 'scope': resolved_scope})
        selected[spec['specificationKey']] = {k: copy.deepcopy(spec[k]) for k in
                                             ('specificationKey', 'source', 'original', 'entitySha256')}
    return {'schemaVersion': 'published-criterion-knowledge-v1', 'methodVersion': VERSION,
            'adoptionMode': config['adoptionMode'], 'clinicalValidationCompleted': False,
            'policyId': config['policyId'], 'policyVersion': config['policyVersion'],
            'preparation': {'methodVersion': VERSION, 'catalogRawSha256': source['sha256'],
                            'planCanonicalSha256': content_hash(config),
                            'originalRawVerified': True, 'priorBa1ApprovalInherited': False,
                            'interpretationAttribution': 'PROJECT_RESEARCH_NOT_NEW_EXPERT_REVIEW'},
            'specifications': [selected[k] for k in sorted(selected)], 'records': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', required=True)
    parser.add_argument('--plan-sha256', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    result = compile_plan(parse(pinned(args.plan, args.plan_sha256, 4_000_000)))
    write_new(args.output, result)
    print(json.dumps({'records': len(result['records']), 'specifications': len(result['specifications']),
                      'clinicalValidationCompleted': False}))


if __name__ == '__main__':
    main()
