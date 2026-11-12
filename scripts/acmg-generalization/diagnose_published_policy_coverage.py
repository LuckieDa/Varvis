"""Diagnose pinned source/identity/record supply; never select or score a criterion.

This is an offline preparation audit, not another runtime or an applicability
review. A snapshot miss never establishes absence of an expert specification.
Existing compiled records are replayed through the original producer before use.
"""
from __future__ import annotations

import argparse
from collections import Counter
import copy
import json
from pathlib import Path
import re

from prepare_published_criterion_knowledge import (
    CRITERIA, compile_plan, content_hash, criterion_scopes, load_specifications,
    parse, pinned, pointer, require, source_moi, validate_source_refs, write_new,
)

VERSION = '1.0.0'
SCOPE_FIELDS = {'hgncId', 'mondoId', 'moi', 'transcript', 'assembly'}
SUPPORTED = CRITERIA | {'ACMG_AMP_2015:' + c for c in
                       ('PS1', 'PM5', 'PS2', 'PM6', 'BA1', 'BS1', 'PM2')}
ROW_FIELDS = ('recordId', 'criterionId', 'specificationKey', 'clausePointer',
              'scopePointer', 'status', 'reasonCode', 'interpretation')


def _compiled_records(config):
    """Reject a compiled artifact whose preserved source or row no longer replays."""
    refs = config.get('knowledge', [])
    require(isinstance(refs, list) and len(refs) <= 32, 'DIAGNOSTIC_KNOWLEDGE_REFS_INVALID')
    result = []
    seen = set()
    for ref in refs:
        require(isinstance(ref, dict), 'DIAGNOSTIC_KNOWLEDGE_REF_INVALID')
        doc = parse(pinned(ref['file'], ref['sha256'], 32_000_000))
        require(ref['sha256'] not in seen, 'DIAGNOSTIC_DUPLICATE_KNOWLEDGE_INPUT')
        seen.add(ref['sha256'])
        require(doc.get('schemaVersion') == 'published-criterion-knowledge-v1'
                and doc.get('methodVersion') == '1.0.0'
                and doc.get('preparation', {}).get('catalogRawSha256') == config['catalog']['sha256'],
                'DIAGNOSTIC_KNOWLEDGE_SOURCE_INVENTORY_MISMATCH')
        require(isinstance(doc.get('records'), list), 'DIAGNOSTIC_KNOWLEDGE_RECORDS_INVALID')
        plans = []
        for row in doc['records']:
            require(isinstance(row, dict) and SCOPE_FIELDS == set(row.get('scope', {})),
                    'DIAGNOSTIC_KNOWLEDGE_SCOPE_INVALID')
            plans.append({**{k: copy.deepcopy(row[k]) for k in ROW_FIELDS},
                          'transcript': row['scope']['transcript'], 'expectedScope': row['scope']})
        replay = compile_plan({
            'schemaVersion': 'published-criterion-interpretation-plan-v1',
            'adoptionMode': doc.get('adoptionMode'),
            'clinicalValidationCompleted': doc.get('clinicalValidationCompleted'),
            'policyId': doc.get('policyId'), 'policyVersion': doc.get('policyVersion'),
            'catalog': config['catalog'], 'captureDirectory': config['captureDirectory'],
            'records': plans,
        })
        require(replay['records'] == doc['records'] and replay['specifications'] == doc.get('specifications'),
                'DIAGNOSTIC_KNOWLEDGE_REPLAY_MISMATCH')
        for row in replay['records']:
            result.append({**copy.deepcopy(row), 'knowledgeRawSha256': ref['sha256']})
    return result


def _request(value, ids):
    require(isinstance(value, dict) and set(value) <= {'requestId', 'criterionId', 'scope', 'geneSymbol'}
            and isinstance(value.get('requestId'), str) and value['requestId'].strip()
            and value['requestId'] not in ids and isinstance(value.get('criterionId'), str)
            and value['criterionId'] in SUPPORTED,
            'DIAGNOSTIC_REQUEST_IDENTITY_INVALID')
    ids.add(value['requestId'])
    scope = value.get('scope')
    require(isinstance(scope, dict) and set(scope) == SCOPE_FIELDS
            and all(isinstance(v, str) for v in scope.values())
            and re.fullmatch(r'HGNC:[1-9][0-9]*', scope['hgncId'])
            and re.fullmatch(r'MONDO:[0-9]{7}', scope['mondoId'])
            and scope['moi'] in {'AUTOSOMAL_DOMINANT', 'AUTOSOMAL_RECESSIVE', 'X_LINKED',
                                 'X_LINKED_DOMINANT', 'X_LINKED_RECESSIVE', 'Y_LINKED', 'UNKNOWN'}
            and re.fullmatch(r'(?:NM_[0-9]+|ENST[0-9]+)\.[1-9][0-9]*', scope['transcript'])
            and scope['assembly'] in {'GRCh37', 'GRCh38'}, 'DIAGNOSTIC_EXPLICIT_SCOPE_REQUIRED')
    require('geneSymbol' not in value or isinstance(value['geneSymbol'], str) and value['geneSymbol'].strip(),
            'DIAGNOSTIC_GENE_SYMBOL_INVALID')


def _source_candidates(specs, request):
    scope, criterion = request['scope'], request['criterionId']
    rows = []
    for spec in specs:
        original = spec['original']
        for association in criterion_scopes(original, criterion):
            if association['scope']['hgncId'] != scope['hgncId']:
                continue
            for ci, wrapper in enumerate(original.get('ld', {}).get('CriteriaCode', [])):
                clause = wrapper.get('entContent', {})
                if criterion != 'ACMG_AMP_2015:' + str(clause.get('label')):
                    continue
                genes, diseases = clause.get('gene'), clause.get('disease')
                if not isinstance(genes, list) or association['geneSymbol'] not in genes:
                    continue
                disease_ok = ('disease' not in clause or isinstance(diseases, list)
                              and (not diseases or association['scope']['mondoId'] in diseases))
                gene = association['rawGeneAssociation']
                txs = gene.get('transcripts', [])
                txs = txs if isinstance(txs, list) else []
                tx_match = scope['transcript'] == gene.get('preferredTranscript') or scope['transcript'] in txs
                mismatches = []
                if request.get('geneSymbol', association['geneSymbol']) != association['geneSymbol']:
                    mismatches.append('geneSymbol')
                for key in ('mondoId', 'moi'):
                    if association['scope'].get(key) != scope[key]:
                        mismatches.append(key)
                if not disease_ok:
                    mismatches.append('clauseDiseaseScope')
                if not tx_match:
                    mismatches.append('transcript')
                if association['scopeStatus'] != 'EXACT':
                    mismatches.append('unresolvedSourceScope')
                disease = pointer(original, association['scopePointer'])
                modes = disease.get('modesOfInheritance', [])
                if (not isinstance(modes, list) or any(not isinstance(mode, dict)
                        or not isinstance(mode.get('modeOfInheritance'), str)
                        or source_moi(mode['modeOfInheritance'], criterion) != association['scope'].get('moi')
                        for mode in modes)):
                    mismatches.append('sourceInheritanceConflict')
                rows.append({
                    'specificationKey': spec['specificationKey'],
                    'source': copy.deepcopy(spec['source']), 'entitySha256': spec['entitySha256'],
                    'scope': copy.deepcopy(association['scope']), 'scopeStatus': association['scopeStatus'],
                    'geneSymbol': association['geneSymbol'], 'scopePointer': association['scopePointer'],
                    'preferredTranscript': gene.get('preferredTranscript'),
                    'clausePointer': f'/ld/CriteriaCode/{ci}', 'clauseSha256': content_hash(wrapper),
                    'applicability': clause.get('applicability'), 'mismatchedDimensions': mismatches,
                    'clauseScopeRelation': 'RULESET_DISEASE_CONTEXT_ONLY' if 'disease' not in clause
                        else 'EXPLICIT_EMPTY_DISEASE_SCOPE' if diseases == [] else 'EXPLICIT_GENE_AND_DISEASE',
                })
    return rows


def diagnose(config):
    require(isinstance(config, dict) and config.get('schemaVersion') == 'published-policy-coverage-diagnostic-input-v1',
            'DIAGNOSTIC_INPUT_INVALID')
    source = config.get('catalog')
    require(isinstance(source, dict) and isinstance(source.get('file'), str)
            and isinstance(source.get('sha256'), str) and isinstance(config.get('captureDirectory'), str),
            'DIAGNOSTIC_SOURCE_REF_INVALID')
    requests = config.get('requests')
    require(isinstance(requests, list) and len(requests) <= 20_000, 'DIAGNOSTIC_REQUESTS_INVALID')
    ids = set()
    for request in requests:
        _request(request, ids)
    catalog = parse(pinned(config['catalog']['file'], config['catalog']['sha256'], 32_000_000))
    validate_source_refs(catalog, Path(config['captureDirectory']))
    try:
        specs, _ = load_specifications(catalog, Path(config['captureDirectory']))
    except (KeyError, TypeError, AttributeError, IndexError) as error:
        raise ValueError('DIAGNOSTIC_SOURCE_GRAPH_INVALID') from error
    records = _compiled_records(config)
    results = []
    for request in requests:
        candidates = _source_candidates(specs, request)
        exact = [c for c in candidates if not c['mismatchedDimensions']]
        supplied = [r for r in records if r['criterionId'] == request['criterionId'] and r['scope'] == request['scope']]
        if request['scope']['assembly'] != 'GRCh38':
            reason = 'ASSEMBLY_OUTSIDE_EXISTING_COMPILER_CONTRACT'
        elif not candidates:
            reason = 'NO_GENE_CRITERION_MATCH_IN_PINNED_SOURCE_INVENTORY'
        elif not exact:
            reason = 'SOURCE_IDENTITY_OR_SCOPE_NOT_MATCHED'
        elif len(exact) > 1:
            reason = 'MULTIPLE_SOURCE_SCOPES_REQUIRE_REVIEW'
        elif len(supplied) > 1:
            reason = 'MULTIPLE_SUPPLIED_RECORDS_REQUIRE_SELECTION'
        elif supplied:
            reason = ('RECORD_SUPPLIED_RUNTIME_VALIDATION_REQUIRED' if supplied[0]['status'] == 'EXECUTABLE'
                      else 'SUPPLIED_RECORD_' + supplied[0]['status'])
        elif exact[0]['applicability'] in ('Not applicable', 'Not Applicable', 'Not Applicable for this VCEP'):
            reason = 'SOURCE_MARKED_NOT_APPLICABLE_RECORD_NOT_SUPPLIED'
        elif request['criterionId'] not in CRITERIA:
            reason = 'SOURCE_MATCH_POLICY_ROUTE_REQUIRES_RUNTIME_VALIDATION'
        else:
            reason = 'SOURCE_MATCH_RECORD_NOT_SUPPLIED'
        results.append({**copy.deepcopy(request), 'reasonCode': reason, 'sourceCandidates': candidates,
                        'exactSourceCount': len(exact), 'suppliedRecords': supplied,
                        'knowledgeSupply': 'PINNED_ARTIFACTS_PROVIDED_FOR_AUDIT' if config.get('knowledge')
                            else 'NO_ARTIFACTS_PROVIDED_FOR_AUDIT',
                        'runtimeEvaluated': False, 'evidenceAwarded': False,
                        'transcriptBridgeEvaluated': False,
                        'generalRouteEstablishedByThisAudit': False})
    return {'schemaVersion': 'published-policy-coverage-diagnostic-v1', 'methodVersion': VERSION,
            'purpose': 'OFFLINE_SUPPLY_DIAGNOSIS_ONLY', 'runtimeEligible': False,
            'sourceScope': 'PINNED_HISTORICAL_SNAPSHOT_NOT_LATEST_OR_WORLDWIDE_SEARCH',
            'catalogRawSha256': config['catalog']['sha256'], 'inputCanonicalSha256': content_hash(config),
            'clinicalValidationCompleted': False, 'createsReviewReceipt': False,
            'counts': dict(Counter(r['reasonCode'] for r in results)), 'requests': results}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--config-sha256', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    output = diagnose(parse(pinned(args.config, args.config_sha256, 4_000_000)))
    write_new(args.output, output)
    print(json.dumps(output['counts'], sort_keys=True))


if __name__ == '__main__':
    main()
