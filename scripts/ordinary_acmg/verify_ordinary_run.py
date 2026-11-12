"""Audit ordinary-entry HTTP/remote-DB artifacts without repairing Java instances.

The bounded public panel is an acceptance input, never an application allowlist.
"""
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
from scripts.e4_a3c import verify_explicit_case_input as intake

sources, e1, require = intake.a3b, intake.e1, intake.require
a2b = intake.a2b


def verify(archive, panel, resource_root):
    before = copy.deepcopy(archive)
    api, job, ranking = archive['apiResult'], archive['job'], archive['ranking']
    require(job['status'] == 'READY' and job['engineStatus'] == 'RANKED'
            and job['engine'] == 'EXOMISER' and job['engineFallbackUsed'] is False
            and job['liftoverApplied'] is False, 'REAL_NATIVE_ENGINE_REQUIRED')
    require(job['fileSha256'] == panel['vcfSha256'] and job['selectedSampleId'] == panel['sampleId'], 'UPLOAD_SOURCE_MISMATCH')
    require(api['analysisId'] == job['id'] and api['selectedCount'] == len(api['candidates'])
            and api['selectedCount'] == min(api['requestedN'], ranking['candidateTotal'])
            and api['candidateIds'] == [r['candidateId'] for r in ranking['variants']], 'FORMAL_ORDER_CHANGED')
    require(api['automaticTrigger'] is False and api['clinicalValidationCompleted'] is False
            and api['candidateOrderingChanged'] is False, 'EXECUTION_SCOPE_CHANGED')
    require(json.loads(archive['persistedResponseRaw']) == api
            and e1.sha256_bytes(archive['persistedResponseRaw'].encode()) == archive['persistedResponseSha256'], 'REMOTE_DB_READBACK_MISMATCH')
    private = json.loads(archive['persistedPrivateRaw'])
    require(e1.sha256_bytes(archive['persistedPrivateRaw'].encode()) == api['privateArtifactSha256'], 'PRIVATE_HASH_MISMATCH')
    require(private['declaration'] == json.loads(archive['persistedRequestRaw'])
            and e1.canonical_sha256(private['declaration']) == api['requestSha256'], 'REQUEST_HASH_MISMATCH')
    require(private['declaration']['dataClass'] == panel['dataClass'] == 'SYNTHETIC', 'PUBLIC_ACCEPTANCE_CASE_CLASS_MISMATCH')
    snapshot = json.loads(archive['sourceSnapshotRaw'])
    require(e1.sha256_bytes(archive['sourceSnapshotRaw'].encode()) == archive['sourceSnapshotSha256'] == api['executionSourceSha256'], 'EXECUTION_HASH_MISMATCH')
    require(snapshot['analysisId'] == job['id'] and snapshot['sampleId'] == panel['sampleId']
            and snapshot['inputAssembly'] == snapshot['analysisAssembly'] == 'GRCh38'
            and snapshot['fullVcfScans'] == 1 and snapshot['unchangedDuringExecution'] is True
            and snapshot['conversionApplied'] is False and snapshot['exitCode'] == 0, 'EXECUTION_SOURCE_MISMATCH')
    require([r['candidateId'] for r in snapshot['candidates']][:api['selectedCount']] == api['candidateIds'], 'SNAPSHOT_ORDER_CHANGED')
    for raw, digest in [('analysisVcfRaw', snapshot['observedVcfSha256']), ('variantTsvRaw', snapshot['outputSha256'])]:
        require(len(archive[raw]) < 1024 * 1024 and e1.sha256_bytes(archive[raw].encode()) == digest, 'BOUNDED_EXECUTION_ARTIFACT_MISMATCH')
    require(snapshot['observedVcfSha256'] == panel['vcfSha256'] and snapshot['outputSha256'] == job['engineOutputSha256'], 'TASK_ARTIFACT_MISMATCH')
    vcf = [l.split('\t') for l in archive['analysisVcfRaw'].splitlines() if l and not l.startswith('#')]
    tsv = list(csv.DictReader(io.StringIO(archive['variantTsvRaw'].lstrip('#')), delimiter='\t'))
    batch = copy.deepcopy(api['result']['adapterResult']['dispatch'])
    require(batch['candidates'] == api['candidates'] and batch['preflight']['candidateIds'] == api['candidateIds'], 'DISPATCH_WRAPPER_CHANGED')
    dispatch_summary = a2b.verify_dispatch(batch)  # Existing schema/scope/selection/atomic/trace IDs.
    for field in ('sourceReceipts', 'sourceProduction', 'declarationIntake', 'candidateInputProvenance'):
        batch[field] = private[field]
    batch['candidateInputFixture'] = {'rawAnalysisVcf': archive['analysisVcfRaw']}
    declaration_summary = intake.verify_intake(batch)  # Does not assert a synthetic engine runner.
    resolve = sources.ledgers(batch)
    schemas = e1.ContractSchemas()
    summary, counts, expected_bundles = [], Counter(), []
    source = private['candidateInputProvenance']
    require(source['analysisId'] == job['id'] and source['uploadArtifactSha256'] == panel['vcfSha256']
            and source['outputSha256'] == snapshot['outputSha256'] and source['vcfRescanned'] is False, 'INPUT_ASSOCIATION_MISMATCH')
    production = private['sourceProduction']
    require(production['additionalFullVcfScans'] == production['fullReferenceHashesComputed'] == 0, 'PRODUCER_SCOPE_CHANGED')
    for row, persisted in zip(api['candidates'], snapshot['candidates'], strict=False):
        cid = row['candidateId']
        require(cid == persisted['candidateId'], 'CANDIDATE_ASSOCIATION_MISMATCH')
        parsed = tsv[int(cid.rsplit('-', 1)[1]) - 1]
        for attempt in row['attempts']:
            statuses = {e['criterionId'].split(':')[-1]: e['evaluationResult']['status'] for e in attempt['criterionEvaluations']}
            counts.update(f'{k}_{v}' for k, v in statuses.items())
            context = attempt['context']
            summary.append({'candidateId': cid, 'gene': parsed['GENE_SYMBOL'], 'hypothesisId': attempt['preflight']['original']['hypothesisId'],
                            'results': statuses, 'blockers': attempt['blockers'], 'contextId': context['contextId'] if context else None,
                            'axes': [{k: e[k] for k in ('resourceExecution', 'resourceAvailability', 'queryOutcome', 'resourceApplicability')} for e in attempt['atomicEvidence']]})
            if context is None:
                require(attempt['providerCalls'] == attempt['evaluatorCalls'] == 0 and attempt['blockers'], 'UNREADY_DISPATCHED')
                continue
            original = attempt['preflight']['original']; provenance = original['provenance']
            proof = resolve(provenance['normalization'], 'NORMALIZATION')
            reference = resolve(provenance['reference'], 'REFERENCE_IDENTITY')
            sources.verify_reference(reference, proof['referenceObservation'], resolve, resource_root)
            ordinal, alt = proof['analysisVcfRecordOrdinal'], proof['selectedAltIndex']
            require(type(ordinal) is int and 1 <= ordinal <= min(len(vcf), 2147483647), 'NORMALIZATION_ANALYSIS_SOURCE_MISMATCH')
            fields = vcf[ordinal - 1]; alts = fields[4].split(',')
            require(type(alt) is int and 1 <= alt <= len(alts), 'SELECTED_ALT_INVALID')
            record = dict(contig=fields[0], position=int(fields[1]), reference=fields[3], alternates=alts, selectedAltIndex=alt, recordOrdinal=ordinal)
            require(proof['matchedRecord'] == persisted['matchedRecord'] == record
                    and proof['originalRecord'] == '|'.join([fields[0], fields[1], fields[3], fields[4], str(alt)]), 'MATCHED_RECORD_MISMATCH')
            require(proof['method'] == sources.METHOD and proof['methodVersion'] == sources.VERSION
                    and proof['refValidation'] == 'MATCH' and proof['status'] == 'UNCHANGED'
                    and proof['analysisId'] == job['id'] and proof['candidateId'] == cid
                    and proof['hypothesisId'] == attempt['preflight']['original']['hypothesisId']
                    and proof['analysisVcfArtifactSha256'] == panel['vcfSha256'], 'NORMALIZATION_SOURCE_MISMATCH')
            allele = context['variant']['normalizedAllele']
            require(allele == proof['originalAllele'] == proof['normalizedAllele']
                    and [allele['contig'], str(allele['position']), allele['reference'], allele['alternate']] == [parsed[k] for k in ('CONTIG', 'START', 'REF', 'ALT')]
                    and context['gene']['symbol'] == parsed['GENE_SYMBOL'], 'CONTEXT_ENGINE_IDENTITY_MISMATCH')
            tx = resolve(provenance['transcript'], 'TRANSCRIPT_CHOICE')
            require(tx['annotation'] == persisted['published']['transcriptAnnotations'][tx['candidateAnnotationIndex']], 'PUBLISHED_ANNOTATION_MISMATCH')
            fixed = next(v for v in panel['variants'] if v['gene'] == context['gene']['symbol'])
            require(context['transcript']['accession'] + '.' + str(context['transcript']['version']) == fixed['transcript'], 'FIXED_TRANSCRIPT_CHANGED')
            if attempt['selection'] is not None and not attempt['blockers'] and len(attempt['criterionEvaluations']) == 2:
                expected_bundles.append(attempt)
    require(len(expected_bundles) == len(api['bundles']), 'PERSISTED_BUNDLE_COUNT_MISMATCH')
    for attempt, row in zip(expected_bundles, api['bundles'], strict=True):
        b = row['bundle']
        for key, field in [('context', 'context'), ('cspecSelection', 'selection'), ('atomicEvidence', 'atomicEvidence'), ('criterionEvaluations', 'criterionEvaluations')]:
            require(b[key] == attempt[field], 'ORIGINAL_JAVA_NODE_CHANGED')
        require(e1.rehash_bundle(b) == b, 'ORIGINAL_JAVA_ID_MISMATCH')
        e1.validate_bundle(b, e1.load_json(e1.AUTHORITY_PATH), e1.load_json(a2b.a2a.RULE_PATH), e1.load_json(a2b.a2a.OVERLAY_PATH), schemas,
                           rule_registry_path=a2b.a2a.RULE_PATH, cspec_registry_path=a2b.a2a.OVERLAY_PATH)
        require(b['combination']['executionStatus'] == 'NOT_RUN' and b['combination']['result'] == 'NO_RESULT', 'COMBINATION_EXECUTED')
    require(archive == before, 'ORIGINAL_ARCHIVE_MODIFIED')
    return {'status': 'PASS', 'analysisId': job['id'], 'runId': api['runId'], 'database': 'Varvis',
            'providerCalls': api['providerCalls'], 'evaluatorCalls': api['evaluatorCalls'],
            'realExomiser': True, 'syntheticPatient': True, 'clinicalValidation': False,
            'javaNodesRewritten': False, 'browserAcceptance': 'BLOCKED_BY_COMPUTER_USE_URL_POLICY',
            'candidateCount': len(api['candidates']), 'bundlesVerified': len(api['bundles']),
            'declarations': declaration_summary, 'dispatchChecks': dispatch_summary['counts'], 'ruleCounts': dict(counts), 'rows': summary}


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--archive', type=Path, required=True); p.add_argument('--panel', type=Path, required=True)
    p.add_argument('--resource-root', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    result = verify(e1.load_json(args.archive), e1.load_json(args.panel), args.resource_root)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k != 'rows'}, ensure_ascii=False))
