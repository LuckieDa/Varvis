"""Bounded A3A test-artifact replay; reuses E1/A2B validation, never repairs Java nodes."""
from __future__ import annotations

import argparse
import copy
import csv
import io
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from scripts.e4_a2b import verify_alphamissense_dispatch as a2b

e1, require = a2b.e1, a2b.require


def verify_sources(batch: dict) -> dict:
    source, fixture = batch['candidateInputProvenance'], batch['candidateInputFixture']
    require(fixture['runner'] == 'SYNTHETIC_TSV_RUNNER_NOT_REAL_EXOMISER'
            and fixture['patientDataClass'] == fixture['rankingDataClass'] == 'SYNTHETIC', 'FIXTURE_BOUNDARY_CHANGED')
    require(source['source'] == 'EXOMISER_CANDIDATE_INPUT_ASSOCIATION' and source['version'] == '1.0.0'
            and source['acceptanceScope'] == 'NATIVE_GRCH38_EXPLICIT_INPUTS'
            and source['vcfRescanned'] is False and source['referenceValidationProduced'] is False,
            'INPUT_ASSOCIATION_BOUNDARY_CHANGED')
    for field in ('rawAnalysisVcf', 'rawVariantTsv'):
        require(isinstance(fixture[field], str) and len(fixture[field]) < 100_000, 'TINY_FIXTURE_REQUIRED')
    vcf_sha = e1.sha256_bytes(fixture['rawAnalysisVcf'].encode('utf-8'))
    tsv_sha = e1.sha256_bytes(fixture['rawVariantTsv'].encode('utf-8'))
    require(vcf_sha == fixture['analysisVcfSha256'], 'ANALYSIS_VCF_SHA_MISMATCH')
    require(tsv_sha == fixture['variantTsvSha256'] == source['outputSha256'], 'VARIANT_TSV_SHA_MISMATCH')
    require(source['analysisId'] == batch['preflight']['analysisId'], 'TASK_ANALYSIS_ASSOCIATION_MISMATCH')
    require(source['uploadHashSource'] == 'EXISTING_UPLOAD_TASK_IDENTITY_NOT_REHASHED', 'UPLOAD_HASH_SCOPE_CHANGED')
    for key in ('uploadArtifactSha256', 'requestSha256', 'commandSha256', 'outputSha256'):
        require(re.fullmatch('[0-9a-f]{64}', source[key]) is not None, 'EXECUTION_IDENTITY_REQUIRED')
    native = (source['originalInputAssembly'] == source['analysisAssembly'] == 'GRCh38'
              and source['analysisVcfIsUploadedFile'] is True and source['conversionApplied'] is False
              and source['conversionEngine'] == 'NONE' and source['rejectedRecordCount'] == 0)
    if native:
        require(vcf_sha == source['uploadArtifactSha256'], 'NATIVE_UPLOAD_SHA_MISMATCH')
    rows = list(csv.DictReader(io.StringIO(fixture['rawVariantTsv'].lstrip('#')), delimiter='\t'))
    require(fixture['originalRecordNullCount'] == len(rows), 'ORIGINAL_RECORD_FIXTURE_CHANGED')
    vcf = [line.split('\t') for line in fixture['rawAnalysisVcf'].splitlines() if line and not line.startswith('#')]
    header = [line.split('\t') for line in fixture['rawAnalysisVcf'].splitlines() if line.startswith('#CHROM')]
    require(len(header) == 1 and len(header[0]) == 10, 'SINGLE_SYNTHETIC_SAMPLE_REQUIRED')
    sample = header[0][9]
    associations = source['candidateAssociations']
    require([row['candidateId'] for row in associations] == batch['preflight']['candidateIds'], 'TOP_N_SOURCE_ORDER_CHANGED')
    require(len(associations) == len(batch['candidates']), 'TOP_N_SOURCE_ROWS_CHANGED')
    ledgers = {}
    receipts = batch.get('sourceReceipts', batch.get('smokeInputProvenance', {}).get('sourceReceipts', batch.get('syntheticSourceReceipts', [])))
    for receipt in receipts:
        require(e1.sha256_bytes(receipt['rawJson'].encode('utf-8')) == receipt['sha256'], 'SOURCE_RAW_SHA_MISMATCH')
        envelope = json.loads(receipt['rawJson'])
        key = (envelope['source'], envelope['version'], receipt['sha256'])
        require(key not in ledgers and envelope['source'] == receipt['source'], 'SOURCE_LEDGER_IDENTITY_CONFLICT')
        ledgers[key] = envelope['records']

    def resolve(ref):
        records = ledgers.get((ref['source'], ref['version'], ref['artifactSha256']), {})
        require(ref['recordLocator'] in records, 'SOURCE_REFERENCE_UNRESOLVED')
        return records[ref['recordLocator']]

    ready = 0
    for association, candidate in zip(associations, batch['candidates'], strict=True):
        cid = association['candidateId']
        require(re.fullmatch('exomiser-v1-[0-9]{8}', cid) is not None, 'EXOMISER_CANDIDATE_ID_INVALID')
        ordinal = int(cid.rsplit('-', 1)[1])
        require(1 <= ordinal <= len(rows), 'EXOMISER_SOURCE_ORDINAL_INVALID')
        parsed = rows[ordinal - 1]
        key = ['GRCh38', parsed['CONTIG'], parsed['START'], parsed['REF'], parsed['ALT']]
        require(association['canonicalVariantId'] == '|'.join(key), 'CANDIDATE_TSV_ALLELE_MISMATCH')
        matches = [(i + 1, fields, fields[4].split(',').index(key[4]) + 1)
                   for i, fields in enumerate(vcf) if fields[0].removeprefix('chr') == key[1] and fields[1] == key[2]
                   and fields[3] == key[3] and key[4] in fields[4].split(',')]
        status = 'MATCHED' if len(matches) == 1 else 'NOT_FOUND' if not matches else 'AMBIGUOUS'
        call = association['analysisVcfCall']
        require(call['matchStatus'] == status and call['matchingRecordCount'] == len(matches)
                and call['assembly'] == 'GRCh38' and call['artifactRole'] == 'EXOMISER_ANALYSIS_VCF',
                'ANALYSIS_CALL_SOURCE_MISMATCH')
        if status == 'MATCHED':
            require(call['matchedRecordOrdinal'] == matches[0][0] and call['targetAlleleIndex'] == matches[0][2],
                    'ANALYSIS_CALL_RECORD_ALT_MISMATCH')
        for attempt in candidate['attempts']:
            if not native or status != 'MATCHED':
                require(attempt['context'] is None and attempt['providerCalls'] == attempt['evaluatorCalls'] == 0
                        and attempt['blockers'], 'UNPROVEN_ANALYSIS_INPUT_DISPATCHED')
                continue
            context = attempt['context']
            if context is None:
                continue
            ready += 1
            allele = context['variant']['normalizedAllele']
            require([allele['assembly'], allele['contig'], str(allele['position']), allele['reference'], allele['alternate']] == key
                    and context['gene']['symbol'] == parsed['GENE_SYMBOL']
                    and context['moi'] == {'AR': 'AUTOSOMAL_RECESSIVE', 'AD': 'AUTOSOMAL_DOMINANT'}[parsed['MOI']],
                    'CONTEXT_PARSED_CANDIDATE_MISMATCH')
            original = attempt['preflight']['original']
            require(any(record.get('ncbiGeneId') == parsed['ENTREZ_GENE_ID']
                        and record.get('hgncId') == context['gene']['hgncId']
                        for record in original['binding']['provenance']['crosswalkRecords']),
                    'CONTEXT_PARSED_GENE_ID_MISMATCH')
            provenance = original['provenance']
            patient, norm = resolve(provenance['patient']), resolve(provenance['normalization'])
            require(patient['sampleId'] == sample, 'PATIENT_ANALYSIS_SAMPLE_MISMATCH')
            for record in (patient, norm, resolve(provenance['transcript']), resolve(provenance['assemblyLineage']['source'])):
                require(record['analysisId'] == source['analysisId'] and record['candidateId'] == cid
                        and record['hypothesisId'] == original['hypothesisId'], 'EXPLICIT_RECEIPT_ASSOCIATION_MISMATCH')
            require(norm.get('analysisVcfArtifactSha256') == vcf_sha
                    and type(norm.get('analysisVcfRecordOrdinal')) is int
                    and norm.get('analysisVcfRecordOrdinal') == matches[0][0], 'NORMALIZATION_ANALYSIS_SOURCE_MISMATCH')
            fields, alt = matches[0][1], matches[0][2]
            # Bounded fixtures are unchanged native SNVs. No production normalizer is introduced here.
            require(norm['originalRecord'] == '|'.join([fields[0], fields[1], fields[3], fields[4], str(alt)])
                    and norm['selectedAltIndex'] == alt, 'ORIGINAL_RECORD_SOURCE_MISMATCH')
    return {'candidateRows': len(associations), 'contextsWithVerifiedInputAssociation': ready,
            'nativeInputEstablished': native, 'realExomiserExecuted': False}


def verify(data: dict | list) -> dict:
    before = copy.deepcopy(data)
    batches = data if isinstance(data, list) else [data]
    associations = [verify_sources(batch) for batch in batches]
    result = a2b.verify_dispatch(data)
    require(data == before, 'ORIGINAL_JAVA_NODES_MODIFIED')
    result['candidateInputVerification'] = associations
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dispatch', type=Path, action='append', required=True)
    parser.add_argument('--summary-output', type=Path)
    parser.add_argument('--require-real-exact-genes', type=int)
    parser.add_argument('--preparation', type=Path)
    parser.add_argument('--fixtures', type=Path)
    args = parser.parse_args()
    batches = []
    for path in args.dispatch:
        data = e1.load_json(path)
        batches.extend(data if isinstance(data, list) else [data])
    before = copy.deepcopy(batches)
    result = verify(batches)
    if args.require_real_exact_genes:
        result['realExactAcceptance'] = a2b.verify_real_exact_dispatch(batches, args.require_real_exact_genes)['realExactAcceptance']
    if args.preparation:
        require(len(batches) == 1 and args.fixtures is not None, 'ONE_REAL_BATCH_AND_FIXTURES_REQUIRED')
        result['rawPreparationSources'] = a2b.verify_preparation_sources(batches[0], args.preparation, args.fixtures)
    require(before == batches, 'ORIGINAL_JAVA_NODES_MODIFIED')
    output = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.summary_output:
        args.summary_output.write_text(output, encoding='utf-8', newline='\n')
    print(output)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
