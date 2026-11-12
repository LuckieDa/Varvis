"""Independently verify persisted business/API bytes and original Java evidence; never repair nodes."""
from __future__ import annotations
import argparse
import copy
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from scripts.e4_a3c import verify_explicit_case_input as a3c
e1, a2b, require = a3c.e1, a3c.a2b, a3c.require


def verify(value, resource_root=None, real=False, require_snapshot=False):
    original = copy.deepcopy(value)
    raw, api = value['persistedResponseRaw'], value['apiResult']
    require(e1.sha256_bytes(raw.encode()) == value['persistedResponseSha256'], 'PERSISTED_RESPONSE_HASH_MISMATCH')
    require(json.loads(raw) == api, 'API_READBACK_CHANGED')
    private_raw = value['persistedPrivateRaw']
    require(e1.sha256_bytes(private_raw.encode()) == api['privateArtifactSha256'], 'PERSISTED_PRIVATE_HASH_MISMATCH')
    private = json.loads(private_raw)
    require(e1.canonical_sha256(private['declaration']) == api['requestSha256'], 'DECLARATION_REQUEST_ID_MISMATCH')
    require(api['declarationVersion'] == private['declaration']['declarationVersion'], 'DECLARATION_VERSION_MISMATCH')
    require(api['runtimeRegistrationId'] == 'pp3-bp4-explicit-business-e4-b1-v1'
            and api['automaticTrigger'] is False and api['clinicalValidationCompleted'] is False,
            'BUSINESS_ACTIVATION_MISMATCH')
    registration = e1.load_json(ROOT / 'varvis-server/src/main/resources/acmg/pp3-bp4-business-activation-v1.json')
    profile = e1.load_json(a2b.c1.PROFILE_PATH)
    require(registration['supersedesInvocationActivation'] == profile['activation']
            and registration['effectiveInvocationActivation'] == dict(profile['activation'], patientFlowEnabled=True)
            and registration['calibrationProfileCanonicalSha256'] == e1.canonical_sha256(profile)
            and registration['ruleRegistrySha256'] == e1.sha256_file(a2b.a2a.RULE_PATH)
            and registration['cspecRegistrySha256'] == e1.sha256_file(a2b.a2a.OVERLAY_PATH), 'REGISTRATION_PAIRING_MISMATCH')
    batch = copy.deepcopy(api['result']['adapterResult']['dispatch'])
    require(batch['candidates'] == api['candidates'] and batch['preflight']['candidateIds'] == api['candidateIds']
            and batch['preflight']['analysisId'] == api['analysisId'], 'BUSINESS_CANDIDATE_ASSOCIATION_CHANGED')
    for field in ('providerCalls', 'evaluatorCalls'):
        require(batch[field] == api[field], 'BUSINESS_CALL_COUNT_CHANGED')
    for field in ('sourceReceipts', 'sourceProduction', 'declarationIntake', 'candidateInputProvenance'):
        batch[field] = copy.deepcopy(private[field])
    require(api['result']['declarations'] == private['declarationIntake']
            and api['result']['adapterResult']['inputProvenance'] == private['candidateInputProvenance'],
            'PUBLIC_PRIVATE_SOURCE_ASSOCIATION_MISMATCH')
    resolve = a3c.a3b.ledgers(batch)
    for index, outcome in enumerate(private['declarationIntake']):
        if outcome['declaration'] is None:
            continue
        received = resolve(outcome['declaration'], 'CASE_DECLARATION')
        declared = private['declaration']
        require(received['patient'] == declared['patient'] and received['input'] == declared['hypotheses'][index]
                and received['sampleId'] == declared['sampleId'] and received['analysisId'] == api['analysisId'],
                'BUSINESS_DECLARATION_RECEIPT_MISMATCH')
    batch['explicitSelectionProvenance'] = copy.deepcopy(value['explicitSelectionProvenance'])
    batch['candidateInputFixture'] = copy.deepcopy(value['candidateInputFixture'])
    source_summary = a3c.verify(batch, resource_root=resource_root, real=real)
    persisted_bundles = api['bundles']
    attempts = [a for c in api['candidates'] for a in c['attempts']]
    expected = [a for a in attempts if a['selection'] is not None and not a['blockers'] and len(a['criterionEvaluations']) == 2]
    require(len(expected) == len(persisted_bundles), 'PERSISTED_BUNDLE_ROWS_CHANGED')
    schemas = e1.ContractSchemas()
    for attempt, row in zip(expected, persisted_bundles, strict=True):
        bundle = row['bundle']
        require(row['candidateId'] == attempt['preflight']['original']['candidateId']
                and row['hypothesisId'] == attempt['preflight']['original']['hypothesisId'], 'BUNDLE_OUTER_ASSOCIATION_CHANGED')
        for field, source in [('context', 'context'), ('cspecSelection', 'selection'), ('atomicEvidence', 'atomicEvidence'), ('criterionEvaluations', 'criterionEvaluations')]:
            require(bundle[field] == attempt[source], 'PERSISTED_JAVA_NODE_CHANGED')
        require(e1.rehash_bundle(bundle) == bundle, 'PERSISTED_JAVA_IDENTITIES_CHANGED_BY_REHASH')
        e1.validate_bundle(bundle, e1.load_json(e1.AUTHORITY_PATH), e1.load_json(a2b.a2a.RULE_PATH), e1.load_json(a2b.a2a.OVERLAY_PATH), schemas,
                           rule_registry_path=a2b.a2a.RULE_PATH, cspec_registry_path=a2b.a2a.OVERLAY_PATH)
        require(bundle['combination']['executionStatus'] == 'NOT_RUN' and bundle['combination']['result'] == 'NO_RESULT', 'COMBINATION_MUST_NOT_RUN')
    snapshot_verified = False
    if 'sourceSnapshotRaw' in value:
        raw_snapshot = value['sourceSnapshotRaw']
        require(e1.sha256_bytes(raw_snapshot.encode()) == value['sourceSnapshotSha256'] == api['executionSourceSha256'], 'EXECUTION_SNAPSHOT_HASH_MISMATCH')
        snapshot = json.loads(raw_snapshot)
        require(snapshot['version'] == 'acmg-execution-source-v1' and snapshot['analysisId'] == api['analysisId']
                and snapshot['sampleId'] == private['declaration']['sampleId']
                and snapshot['inputAssembly'] == snapshot['analysisAssembly'] == 'GRCh38'
                and snapshot['conversionApplied'] is False and snapshot['fullVcfScans'] == 1
                and snapshot['unchangedDuringExecution'] is True, 'EXECUTION_SNAPSHOT_ASSOCIATION_MISMATCH')
        require([r['candidateId'] for r in snapshot['candidates']][:len(api['candidateIds'])] == api['candidateIds'], 'PERSISTED_ORDER_CHANGED')
        resolve = a3c.a3b.ledgers(batch)
        for row in snapshot['candidates']:
            record = row['matchedRecord']
            ordinal = record['recordOrdinal']
            require(type(ordinal) is int and 1 <= ordinal <= 2147483647
                    and ordinal == row['engineCandidate']['analysisVcfCall']['matchedRecordOrdinal'], 'ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED')
            for attempt in attempts:
                if attempt['context'] is None or attempt['preflight']['original']['candidateId'] != row['candidateId']:
                    continue
                tx = resolve(attempt['preflight']['original']['provenance']['transcript'], 'TRANSCRIPT_CHOICE')
                require(tx['annotation'] == row['published']['transcriptAnnotations'][tx['candidateAnnotationIndex']], 'PUBLISHED_TRANSCRIPT_SNAPSHOT_MISMATCH')
                normalization = resolve(attempt['preflight']['original']['provenance']['normalization'], 'NORMALIZATION')
                require(normalization['matchedRecord'] == record, 'PRODUCER_MATCHED_RECORD_CHANGED')
        snapshot_verified = True
    require(snapshot_verified or not require_snapshot, 'PRIVATE_EXECUTION_SNAPSHOT_REQUIRED')
    require(value == original, 'ORIGINAL_JAVA_INSTANCE_CHANGED')
    return {'status': 'PASS', 'sourceValidation': source_summary, 'persistedBundles': len(persisted_bundles),
            'providerCalls': api['providerCalls'], 'evaluatorCalls': api['evaluatorCalls'],
            'privateExecutionSnapshotVerified': snapshot_verified, 'javaNodesRewritten': False,
            'bundleIds': [r['bundle']['bundleId'] for r in persisted_bundles]}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--resource-root', type=Path)
    parser.add_argument('--real-exact', action='store_true')
    parser.add_argument('--require-snapshot', action='store_true')
    parser.add_argument('--summary-output', type=Path)
    args = parser.parse_args()
    result = verify(e1.load_json(args.archive), args.resource_root, args.real_exact, args.require_snapshot)
    text = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.summary_output:
        args.summary_output.write_text(text, encoding='utf-8', newline='\n')
    print(text, end='')


if __name__ == '__main__':
    main()
