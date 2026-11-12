"""Compile explicit source-bound NMD ranges into policy candidates, never adoption.

The compiler reuses a pinned complete policy/model. A recipe supplies interpreted
conditions and provenance; it cannot infer them from a gene name or source label.
Existing reviews stay with the base policy and do not approve the new domain.
"""
import argparse
import copy
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from assemble_materials import parse, pinned, content_hash, require, write_new

COORDINATES = {'REFERENCE_EVENT_SPAN', 'REFERENCE_PTC_SPAN', 'ALTERED_PTC_SPAN'}


def positive(value):
    return type(value) is int and 0 < value <= 2147483647


def compile_policy(policy, model, recipe):
    require(set(recipe) == {'basePolicySha256', 'modelSha256', 'minimumCdsPosition',
                           'conditions', 'sourceIds', 'basis'}, 'RECIPE_FIELDS_INVALID')
    require(policy.get('schemaVersion') == 'pvs1-truncating-nmd-policy-v1'
            and content_hash(policy) == recipe['basePolicySha256']
            and content_hash(model) == recipe['modelSha256']
            and recipe['modelSha256'] == policy['transcriptModelBinding']['canonicalSha256'], 'BASE_BINDING_INVALID')
    require(policy.get('review', {}).get('status') == 'REVIEWED_IMPLEMENTED'
            and policy.get('mechanism', {}).get('status') == 'ESTABLISHED_LOSS_OF_FUNCTION'
            and not policy.get('nmdScope', {}).get('exonRelevance', '').startswith('PENDING'), 'BASE_APPLICABILITY_UNRESOLVED')
    nmd = policy['nmdScope']
    require(positive(recipe['minimumCdsPosition']) and recipe['minimumCdsPosition'] < model['cdsLength']-3,
            'MINIMUM_CDS_POSITION_INVALID')
    require(isinstance(recipe['basis'], str) and recipe['basis'].strip(), 'BASIS_REQUIRED')
    sources = {s['sourceId'] for s in policy['scientificSources']}
    require(isinstance(recipe['sourceIds'], list) and recipe['sourceIds'] and
            len(set(recipe['sourceIds'])) == len(recipe['sourceIds']) and set(recipe['sourceIds']) <= sources, 'SOURCE_BINDING_INVALID')
    conditions = recipe['conditions']
    require(isinstance(conditions, list) and 0 < len(conditions) <= 16, 'CONDITION_COUNT_INVALID')
    ids = set()
    for c in conditions:
        require(isinstance(c, dict) and {'conditionId', 'coordinate', 'sourceIds', 'basis'} <= set(c)
                and set(c) <= {'conditionId', 'coordinate', 'sourceIds', 'basis', 'minimumInclusive', 'maximumExclusive'}, 'CONDITION_FIELDS_INVALID')
        require(isinstance(c['conditionId'], str) and c['conditionId'].strip() and c['conditionId'] not in ids, 'CONDITION_ID_INVALID')
        ids.add(c['conditionId'])
        require(c['coordinate'] in COORDINATES and isinstance(c['basis'],str) and c['basis'].strip(), 'CONDITION_COORDINATE_OR_BASIS_INVALID')
        require(isinstance(c['sourceIds'],list) and c['sourceIds'] and len(set(c['sourceIds'])) == len(c['sourceIds'])
                and set(c['sourceIds']) <= sources, 'CONDITION_SOURCE_INVALID')
        require('minimumInclusive' in c or 'maximumExclusive' in c, 'CONDITION_RANGE_REQUIRED')
        for key in ['minimumInclusive', 'maximumExclusive']:
            require(key not in c or positive(c[key]), 'CONDITION_BOUND_INVALID')
        require(c.get('minimumInclusive',0) < c.get('maximumExclusive',2147483648), 'CONDITION_RANGE_INVALID')
    relevant = nmd['relevantExonOrdinals']
    exons = model['exons']
    require(isinstance(relevant,list) and relevant and len(set(relevant)) == len(relevant)
            and all(positive(x) and x <= len(exons) for x in relevant), 'RELEVANT_EXONS_INVALID')
    # Partial first/last included exons are evaluated by per-variant predicates.
    # The real terminal exon/junction are never replaced by this engineering set.
    supported = [e['ordinal'] for e in exons[:-1] if e['ordinal'] in relevant
                 and e['cdsEnd'] >= recipe['minimumCdsPosition'] and e['cdsStart'] <= model['cdsLength']-3]
    require(supported, 'NO_SUPPORTED_CODING_EXONS')
    candidate = copy.deepcopy(policy)
    candidate.update(schemaVersion='pvs1-truncating-nmd-policy-v2',
                     policyId=policy['policyId']+'_POSITIONAL_'+content_hash(recipe)[:16], policyVersion='2.0.0')
    candidate['review'] = {'status':'UNREVIEWED', 'reviewId':'', 'kind':'SOURCE_BOUND_COMPILATION_REQUIRES_NEW_DOMAIN_REVIEW'}
    candidate['nmdScope'].update(minimumCdsPosition=recipe['minimumCdsPosition'],
                                engineeringSupportedExonOrdinals=supported, positionConditions=copy.deepcopy(conditions),
                                engineeringScopeRationale=recipe['basis'])
    candidate['nmdScope']['sourceIds'] = sorted(set(nmd['sourceIds']) | set(recipe['sourceIds']))
    candidate['scientificBoundaryNotes'] = [
        'UNREVIEWED candidate. The original review applies only to the archived base policy, not this changed domain.',
        'All positional predicates are ANDed after full sequence and true altered-junction NMD checks. Unresolved reference mappings fail closed.',
        'Mechanism, clinical relevance, original exon relevance, distance and long-exon limitations are retained. No default deployment or clinical acceptance.',
        recipe['basis']]
    return {'schemaVersion':'pvs1-nmd-condition-candidate-v1','status':'UNREVIEWED','runtimeEligible':False,
            'clinicalAcceptance':False,'basePolicy':copy.deepcopy(policy),'recipe':copy.deepcopy(recipe),
            'model':copy.deepcopy(model),'policy':candidate,'policySha256':content_hash(candidate)}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',required=True);p.add_argument('--input-sha256',required=True);p.add_argument('--output',required=True)
    a=p.parse_args();doc=parse(pinned(a.input,a.input_sha256,16_000_000))
    require(set(doc)=={'schemaVersion','materials'} and doc['schemaVersion']=='pvs1-nmd-condition-input-v1'
            and isinstance(doc['materials'],list) and 0 < len(doc['materials']) <= 1000, 'INPUT_INVALID')
    results=[]
    for item in doc['materials']:
        try:
            require(set(item)=={'policy','model','recipe'},'MATERIAL_FIELDS_INVALID')
            results.append(compile_policy(item['policy'],item['model'],item['recipe']))
        except (ValueError, KeyError, TypeError) as e:
            results.append({'status':'BLOCKED','runtimeEligible':False,'reasonCode':str(e), 'basePolicySha256':content_hash(item.get('policy'))})
    write_new(a.output,{'schemaVersion':'pvs1-nmd-condition-batch-v1','inputRawSha256':a.input_sha256,
                       'runtimeEligible':False,'clinicalAcceptance':False,'results':results})
    print(json.dumps({'candidates':sum(x['status']=='UNREVIEWED' for x in results),'blocked':sum(x['status']=='BLOCKED' for x in results),'adoptions':0}))


if __name__=='__main__':main()
