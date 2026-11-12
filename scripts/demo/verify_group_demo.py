"""Verify actual local demo API/DB artifacts without repairing evidence or manufacturing results."""
from __future__ import annotations
import argparse
import copy
import csv
import io
import json
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.e4_a3b import verify_native_snv_sources as sources

e1, require = sources.e1, sources.require
registries = sources.a2b.a2a


def verify(archive, manifest, resource_root, runtime_root):
    before = copy.deepcopy(archive)
    api, job, ranking = archive['apiResult'], archive['job'], archive['ranking']
    require(job['status'] == 'READY' and job['engineStatus'] == 'RANKED'
            and job['engine'] == 'EXOMISER' and job['rankingMode'] == 'EXOMISER_G0'
            and job['engineFallbackUsed'] is False and job['liftoverApplied'] is False,
            'REAL_NATIVE_G0_REQUIRED')
    require(job['fileSha256'] == manifest['vcfSha256'] and job['selectedSampleId'] == manifest['sampleId'], 'DEMO_UPLOAD_MISMATCH')
    require(api['analysisId'] == job['id'] and api['status'] == 'COMPLETED'
            and api['selectedCount'] == api['requestedN'] == 20
            and api['providerCalls'] == api['evaluatorCalls'] == 20, 'TOP20_EXECUTION_INCOMPLETE')
    require(api['automaticTrigger'] is False and api['clinicalValidationCompleted'] is False
            and api['candidateOrderingChanged'] is False, 'DEMO_SCOPE_CHANGED')
    require(ranking['candidateTotal'] >= 20 and len(ranking['variants']) == 20
            and [r['candidateId'] for r in ranking['variants']] == api['candidateIds'], 'FORMAL_TOP20_ORDER_CHANGED')
    raw, private_raw = archive['persistedResponseRaw'], archive['persistedPrivateRaw']
    require(e1.sha256_bytes(raw.encode()) == archive['persistedResponseSha256'] and json.loads(raw) == api, 'API_DB_READBACK_MISMATCH')
    require(e1.sha256_bytes(private_raw.encode()) == api['privateArtifactSha256'], 'PRIVATE_ARTIFACT_HASH_MISMATCH')
    private = json.loads(private_raw)
    require(private['declaration'] == json.loads(archive['persistedRequestRaw'])
            and e1.canonical_sha256(private['declaration']) == api['requestSha256'], 'DECLARATION_HASH_MISMATCH')
    snapshot_raw = archive['sourceSnapshotRaw']
    require(e1.sha256_bytes(snapshot_raw.encode()) == archive['sourceSnapshotSha256'] == api['executionSourceSha256'], 'SOURCE_SNAPSHOT_HASH_MISMATCH')
    snapshot = json.loads(snapshot_raw)
    require(snapshot['analysisId'] == job['id'] and snapshot['sampleId'] == manifest['sampleId']
            and snapshot['fullVcfScans'] == 1 and snapshot['unchangedDuringExecution'] is True
            and snapshot['conversionApplied'] is False and snapshot['exitCode'] == 0
            and snapshot['engineStatus'] == 'RANKED' and snapshot['rankingMode'] == 'EXOMISER_G0', 'EXECUTION_SOURCE_MISMATCH')
    require([r['candidateId'] for r in snapshot['candidates']][:20] == api['candidateIds'], 'SNAPSHOT_ORDER_MISMATCH')
    def local(path):
        resolved = Path(path).resolve()
        require(resolved.is_relative_to(runtime_root.resolve()) and resolved.is_file()
                and resolved.stat().st_size < 16 * 1024 * 1024, 'BOUNDED_DEMO_ARTIFACT_REQUIRED')
        return resolved.read_bytes()
    vcf = local(snapshot['analysisVcf'])
    require(e1.sha256_bytes(vcf) == manifest['vcfSha256'] == snapshot['observedVcfSha256'], 'VCF_SOURCE_HASH_MISMATCH')
    vcf_rows = [line.split('\t') for line in vcf.decode().splitlines() if line and not line.startswith('#')]
    tsv = local(Path(snapshot['workDirectory']) / 'output/result.variants.tsv')
    require(e1.sha256_bytes(tsv) == snapshot['outputSha256'] == job['engineOutputSha256'], 'REAL_ENGINE_OUTPUT_HASH_MISMATCH')
    engine_rows = list(csv.DictReader(io.StringIO(tsv.decode().lstrip('#')), delimiter='\t'))
    require(len(engine_rows) == manifest['variantCount'] == len(vcf_rows), 'DEMO_ENGINE_ROW_COUNT_MISMATCH')
    require('hiPhivePrioritiser' not in local(Path(snapshot['workDirectory']) / 'input/analysis.yaml').decode(), 'HPO_PRIORITISER_UNEXPECTED')
    resolve = sources.ledgers(private)
    schemas = e1.ContractSchemas()
    statuses = Counter()
    summary_rows = []
    require(len(api['bundles']) == 20 and len(api['candidates']) == 20, 'BUNDLE_COUNT_MISMATCH')
    for rank, (candidate, bundle_row, persisted) in enumerate(zip(api['candidates'], api['bundles'], snapshot['candidates'][:20], strict=True), 1):
        cid = candidate['candidateId']
        require(cid == bundle_row['candidateId'] == persisted['candidateId'] and len(candidate['attempts']) == 1, 'ROW_ASSOCIATION_MISMATCH')
        attempt, bundle = candidate['attempts'][0], bundle_row['bundle']
        require(not attempt['blockers'] and len(attempt['criterionEvaluations']) == 2, 'ATTEMPT_BLOCKED')
        for field, original in [('context', 'context'), ('cspecSelection', 'selection'), ('atomicEvidence', 'atomicEvidence'), ('criterionEvaluations', 'criterionEvaluations')]:
            require(bundle[field] == attempt[original], 'ORIGINAL_JAVA_NODE_CHANGED')
        require(e1.rehash_bundle(bundle) == bundle, 'JAVA_IDENTITY_REHASH_MISMATCH')
        e1.validate_bundle(bundle, e1.load_json(e1.AUTHORITY_PATH), e1.load_json(registries.RULE_PATH),
                           e1.load_json(registries.OVERLAY_PATH), schemas,
                           rule_registry_path=registries.RULE_PATH, cspec_registry_path=registries.OVERLAY_PATH)
        require(bundle['combination']['executionStatus'] == 'NOT_RUN' and bundle['combination']['result'] == 'NO_RESULT', 'CLASSIFICATION_MUST_NOT_RUN')
        provenance = attempt['preflight']['original']['provenance']
        normalization = resolve(provenance['normalization'], 'NORMALIZATION')
        reference = resolve(provenance['reference'], 'REFERENCE_IDENTITY')
        sources.verify_reference(reference, normalization['referenceObservation'], resolve, resource_root)
        record = persisted['matchedRecord']
        ordinal = record['recordOrdinal']
        require(type(ordinal) is int and 1 <= ordinal <= len(vcf_rows), 'SOURCE_ORDINAL_INVALID')
        fields = vcf_rows[ordinal - 1]
        require(record == normalization['matchedRecord'] and fields[0] == record['contig']
                and int(fields[1]) == record['position'] and fields[3] == record['reference']
                and fields[4].split(',') == record['alternates'], 'VCF_RECORD_ASSOCIATION_MISMATCH')
        transcript = resolve(provenance['transcript'], 'TRANSCRIPT_CHOICE')
        require(transcript['annotation'] == persisted['published']['transcriptAnnotations'][transcript['candidateAnnotationIndex']], 'VEP_ANNOTATION_ASSOCIATION_MISMATCH')
        result = {e['criterionId'].split(':')[-1]: e['evaluationResult']['status'] for e in attempt['criterionEvaluations']}
        require(set(result) == {'PP3', 'BP4'} and set(result.values()) <= {'MET', 'NOT_MET'}, 'RULE_NOT_EVALUATED')
        statuses.update(f'{k}_{v}' for k, v in result.items())
        score = next(m['rawValue'] for e in attempt['atomicEvidence'] for m in e['measurements'] if m['metricId'] == 'alphamissense.amPathogenicity')
        summary_rows.append({'rank': rank, 'candidateId': cid, 'allele': bundle['context']['variant']['normalizedAllele'],
                             'gene': bundle['context']['gene']['symbol'], 'amScore': score, **result, 'bundleId': bundle['bundleId']})
    require(archive == before, 'ORIGINAL_ARCHIVE_MODIFIED')
    return {'status': 'PASS', 'analysisId': job['id'], 'runId': api['runId'], 'rankingMode': job['rankingMode'],
            'candidateTotal': ranking['candidateTotal'], 'evaluatedCandidates': 20, 'providerCalls': 20, 'evaluatorCalls': 20,
            'ruleCounts': dict(statuses), 'independentRefChecks': 20, 'bundlesVerified': 20,
            'clinicalValidation': False, 'javaNodesRewritten': False, 'rows': summary_rows}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime-root', type=Path, required=True)
    parser.add_argument('--resource-root', type=Path, required=True)
    parser.add_argument('--summary-output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(e1.load_json(args.runtime_root / 'acceptance/private-audit.json'),
                    e1.load_json(args.runtime_root / 'demo-manifest.json'), args.resource_root, args.runtime_root)
    args.summary_output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({k: v for k, v in result.items() if k != 'rows'}, ensure_ascii=False))
