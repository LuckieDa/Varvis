"""Bounded A3B receipt audit and independent existing E1 bundle/identity validation; never repair Java nodes."""
from __future__ import annotations

import argparse
import copy
import json
import re
from datetime import datetime, timezone
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from scripts.e4_a3a import verify_candidate_input as a3a

a2b, e1, require = a3a.a2b, a3a.e1, a3a.require
METHOD, VERSION = 'NATIVE_SNV_IDENTITY_REF_CHECK', '1.0.0'
QUERY_METHOD, QUERY_VERSION = 'HTSJDK_INDEXED_FASTA_SINGLE_BASE', '2.24.1'


def utc(value):
    require(isinstance(value, str) and value.endswith('Z'), 'REFERENCE_UTC_REQUIRED')
    try:
        return datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError as error:
        raise e1.ContractError('REFERENCE_UTC_REQUIRED') from error


def ledgers(batch):
    result = {}

    def unique(pairs):
        value = {}
        for key, child in pairs:
            require(key not in value, 'SOURCE_DUPLICATE_FIELD')
            value[key] = child
        return value

    for receipt in batch['sourceReceipts']:
        raw = receipt['rawJson'].encode('utf-8')
        require(len(raw) <= 4 * 1024 * 1024 and e1.sha256_bytes(raw) == receipt['sha256'], 'SOURCE_RAW_SHA_MISMATCH')
        envelope = json.loads(raw, object_pairs_hook=unique)
        require(envelope['source'] == receipt['source'] and envelope['dataClass'] == 'SYNTHETIC', 'SMOKE_SOURCE_ENVELOPE_INVALID')
        key = (envelope['source'], envelope['version'], receipt['sha256'])
        require(key not in result, 'SOURCE_LEDGER_IDENTITY_CONFLICT')
        result[key] = envelope['records']

    def resolve(ref, kind=None):
        value = result.get((ref['source'], ref['version'], ref['artifactSha256']), {}).get(ref['recordLocator'])
        require(isinstance(value, dict) and (kind is None or value.get('kind') == kind), 'SOURCE_REFERENCE_UNRESOLVED')
        return value
    return resolve


def verify_reference(identity, observation, resolve, resource_root=None):
    receipt = identity['actualChecksumReceipt']
    require(resolve(identity['acceptedChecksumSource'])['actualChecksumReceipt'] == receipt, 'ACCEPTED_CHECKSUM_REFERENCE_MISMATCH')
    digest = receipt['observedSha256']
    require(re.fullmatch('[0-9a-f]{64}', digest) is not None
            and receipt['expectedSha256'] == identity['observedSha256'] == identity['expectedSha256'] == digest,
            'REFERENCE_CHECKSUM_IDENTITY_MISMATCH')
    require(identity['assembly'] == receipt['assembly'] == 'GRCh38'
            and identity['verificationStatus'] == receipt['hashStatus'] == 'VERIFIED'
            and identity['verificationMethod'] == receipt['hashAlgorithm'] == 'SHA-256'
            and identity['verificationVersion'] == 'ACCEPTED_RECEIPT_APPLICABILITY_V1'
            and receipt['hashScope'] == 'RAW_BYTES' and receipt['unchangedDuringHash'] is True
            and identity['fullReferenceHashComputed'] is False, 'REFERENCE_CHECKSUM_CONTRACT_MISMATCH')
    require(identity['verificationRecordLocator'] == identity['acceptedChecksumSource']['recordLocator']
            and all(isinstance(receipt.get(f), str) and receipt[f] for f in ('resourceId', 'resourceVersion'))
            and isinstance(identity.get('profileId'), str) and identity['profileId'], 'REFERENCE_SOURCE_IDENTITY_REQUIRED')
    require(utc(receipt['startedAt']) <= utc(receipt['completedAt']) <= utc(identity['checksumApplicabilityCheckedAt'])
            <= datetime.now(timezone.utc), 'REFERENCE_CHECKSUM_TIME_INVALID')
    require(utc(observation['observedAt']) >= utc(receipt['completedAt']), 'REFERENCE_OBSERVATION_TIME_INVALID')
    for field in ('byteLength', 'mtimeNs'):
        require(type(receipt[field]) is int and 0 < receipt[field] <= 2**63 - 1, 'REFERENCE_METADATA_INTEGER_INVALID')
    require(identity['indexSha256'] == receipt['indexSha256'] and re.fullmatch('[0-9a-f]{64}', receipt['indexSha256']),
            'REFERENCE_INDEX_IDENTITY_MISMATCH')
    locator = receipt['logicalName']
    require(isinstance(locator, str) and locator and not locator.startswith(('/', '\\'))
            and ':' not in locator and '..' not in locator.replace('\\', '/').split('/')
            and locator == identity['referenceArtifactLocator'], 'REFERENCE_RESOURCE_LOCATOR_INVALID')
    require(observation['assembly'] == 'GRCh38' and observation['method'] == QUERY_METHOD
            and observation['version'] == QUERY_VERSION and observation['status'] == 'MATCH'
            and observation['expectedReference'] == observation['observedReference']
            and re.fullmatch('[ACGT]', observation['observedReference']), 'REFERENCE_OBSERVATION_INVALID')
    alias = observation['contigAssociation']
    if alias['method'] == 'EXACT_CONTIG':
        require(observation['contig'] == observation['referenceContig'], 'REFERENCE_CONTIG_ASSOCIATION_INVALID')
    else:
        require(alias['method'] == 'CONFIGURED_EXPLICIT_CONTIG_ALIAS'
                and e1.sha256_bytes(alias['rawUtf8'].encode()) == alias['artifactSha256'], 'REFERENCE_ALIAS_IDENTITY_INVALID')
        pairs = [line.split() for line in alias['rawUtf8'].splitlines() if line.strip() and not line.startswith('#')]
        require(all(len(p) == 2 for p in pairs) and len({p[0] for p in pairs}) == len(pairs)
                and dict(pairs).get(observation['contig']) == observation['referenceContig'], 'REFERENCE_ALIAS_ASSOCIATION_INVALID')
    if resource_root is None:
        return
    root = Path(resource_root).resolve()
    path = (root / locator).resolve()
    require(path.is_relative_to(root) and path.is_file(), 'REFERENCE_RESOURCE_UNAVAILABLE')
    stat = path.stat()
    require(stat.st_size == receipt['byteLength'] and stat.st_mtime_ns == receipt['mtimeNs'], 'REFERENCE_CHECKSUM_NOT_APPLICABLE')
    index_path = Path(str(path) + '.fai')
    require(index_path.is_file() and index_path.stat().st_size <= 4 * 1024 * 1024, 'REFERENCE_INDEX_UNAVAILABLE')
    raw = index_path.read_bytes()
    require(e1.sha256_bytes(raw) == receipt['indexSha256'], 'REFERENCE_INDEX_IDENTITY_MISMATCH')
    rows = [line.split('\t') for line in raw.decode().splitlines() if line.split('\t')[0] == observation['referenceContig']]
    require(len(rows) == 1 and len(rows[0]) >= 5, 'REFERENCE_CONTIG_UNAVAILABLE')
    length, offset, bases, width = map(int, rows[0][1:5])
    pos = observation['position']
    require(type(pos) is int and 1 <= pos <= length and 0 < bases <= width, 'REFERENCE_POSITION_INVALID')
    with path.open('rb') as stream:
        stream.seek(offset + (pos - 1) // bases * width + (pos - 1) % bases)
        actual = stream.read(1).decode('ascii')
    require(actual == observation['observedReference'], 'INDEPENDENT_REFERENCE_OBSERVATION_MISMATCH')
    require(path.stat().st_mtime_ns == stat.st_mtime_ns and path.stat().st_size == stat.st_size,
            'REFERENCE_CHANGED_DURING_QUERY')


def verify_production(batch, resource_root=None):
    a3a.verify_sources(batch)
    resolve = ledgers(batch)
    production = batch['sourceProduction']
    require(production['producer'] == METHOD and production['version'] == VERSION
            and production['acceptanceScope'] == 'NATIVE_GRCH38_EXPLICIT_INPUTS'
            and type(production['additionalFullVcfScans']) is int and type(production['fullReferenceHashesComputed']) is int
            and production['additionalFullVcfScans'] == production['fullReferenceHashesComputed'] == 0,
            'PRODUCER_SCOPE_CHANGED')
    source = batch['candidateInputProvenance']
    vcf = [line.split('\t') for line in batch['candidateInputFixture']['rawAnalysisVcf'].splitlines() if line and not line.startswith('#')]
    attempts = {(c['candidateId'], a['preflight']['original']['hypothesisId']): a for c in batch['candidates'] for a in c['attempts']}
    verified = 0
    for row in production['inputs']:
        key = (row['candidateId'], row['hypothesisId'])
        if row['status'] == 'OUTSIDE_SELECTED_CANDIDATES':
            require(key not in attempts, 'OUTSIDE_TOP_N_DISPATCHED')
            continue
        require(key in attempts, 'PRODUCER_CANDIDATE_HYPOTHESIS_MISMATCH')
        attempt = attempts[key]
        require(attempt['preflight']['original']['binding'] == row['binding'], 'PRODUCER_BINDING_CHANGED')
        if row['status'] == 'BLOCKED':
            require(attempt['context'] is None and attempt['providerCalls'] == attempt['evaluatorCalls'] == 0
                    and row['blocker'] in attempt['blockers'], 'SOURCE_BLOCKER_NOT_PRESERVED')
            continue
        require(row['status'] == 'PRODUCED' and row['suppliedNormalization'] is None, 'PRODUCER_SUCCESS_INVALID')
        normal = row['normalization']
        proof, reference = resolve(normal['source'], 'NORMALIZATION'), resolve(normal['reference'], 'REFERENCE_IDENTITY')
        lineage = resolve(normal['lineage']['source'], 'ASSEMBLY_LINEAGE')
        for field in ('originalRecord', 'originalAllele', 'normalizedAllele', 'selectedAltIndex', 'method', 'methodVersion', 'reference'):
            require(normal[field] == proof[field], 'PRODUCED_NORMALIZATION_INPUT_MISMATCH')
        require(proof['method'] == METHOD and proof['methodVersion'] == VERSION and proof['status'] == 'UNCHANGED'
                and proof['executionStatus'] == 'SUCCEEDED' and proof['refValidation'] == 'MATCH', 'PRODUCED_METHOD_MISMATCH')
        ordinal = proof['analysisVcfRecordOrdinal']
        require(type(ordinal) is int and 0 < ordinal <= min(len(vcf), 2**31 - 1), 'NORMALIZATION_ANALYSIS_SOURCE_MISMATCH')
        fields, selected = vcf[ordinal - 1], proof['selectedAltIndex']
        alts = fields[4].split(',')
        require(type(selected) is int and 0 < selected <= len(alts), 'PRODUCED_ALT_INDEX_INVALID')
        expected_record = dict(contig=fields[0], position=int(fields[1]), reference=fields[3], alternates=alts,
                               selectedAltIndex=selected, recordOrdinal=ordinal)
        require(proof['matchedRecord'] == row['matchedRecord'] == expected_record, 'MATCHED_RECORD_SUMMARY_MISMATCH')
        require(proof['originalRecord'] == '|'.join([fields[0], fields[1], fields[3], fields[4], str(selected)]), 'ORIGINAL_RECORD_SOURCE_MISMATCH')
        allele = dict(assembly='GRCh38', contig=fields[0].removeprefix('chr'), position=int(fields[1]), reference=fields[3], alternate=alts[selected - 1])
        require(proof['originalAllele'] == proof['normalizedAllele'] == allele and re.fullmatch('[ACGT]', allele['reference'])
                and re.fullmatch('[ACGT]', allele['alternate']) and allele['reference'] != allele['alternate'], 'NATIVE_SNV_IDENTITY_INVALID')
        for record in (proof, lineage):
            require(record['analysisId'] == source['analysisId'] and record['candidateId'] == key[0] and record['hypothesisId'] == key[1]
                    and record['analysisVcfArtifactSha256'] == source['uploadArtifactSha256']
                    and record['analysisVcfRecordOrdinal'] == ordinal and record['selectedAltIndex'] == selected,
                    'PRODUCED_EXECUTION_ASSOCIATION_MISMATCH')
            execution = record['execution']
            require(all(execution[f] == source[f] for f in ('requestSha256', 'commandSha256', 'outputSha256'))
                    and type(execution['fullVcfScans']) is int and execution['fullVcfScans'] == 1 and execution['unchangedDuringExecution'] is True
                    and type(execution['analysisVcfByteLength']) is int
                    and execution['analysisVcfByteLength'] == len(batch['candidateInputFixture']['rawAnalysisVcf'].encode()),
                    'PRODUCED_EXECUTION_IDENTITY_MISMATCH')
            require(type(execution['analysisVcfMtimeNs']) is int and 0 < execution['analysisVcfMtimeNs'] <= 2**63 - 1,
                    'PRODUCED_EXECUTION_METADATA_INVALID')
        require(lineage['originalInputAssembly'] == 'GRCh38' and lineage['preRankingMappingStatus'] == 'NATIVE'
                and lineage['originalInputAllele'] == allele and lineage['transformationId'] is None, 'PRODUCED_LINEAGE_MISMATCH')
        observation = proof['referenceObservation']
        require(observation == row['referenceObservation'] and observation['position'] == allele['position']
                and observation['contig'] == fields[0] and observation['observedReference'] == allele['reference']
                and proof['inputObservedReference'] == proof['normalizedObservedReference'] == allele['reference']
                and proof['refValidationMethod'] == QUERY_METHOD and proof['refValidationVersion'] == QUERY_VERSION,
                'PRODUCED_REFERENCE_OBSERVATION_MISMATCH')
        verify_reference(reference, observation, resolve, resource_root)
        if attempt['context'] is not None:
            context = attempt['context']
            provenance = attempt['preflight']['original']['provenance']
            require(provenance['normalization'] == normal['source'] and provenance['reference'] == normal['reference']
                    and provenance['assemblyLineage'] == normal['lineage'], 'PRODUCED_RECEIPT_NOT_CONSUMED')
            require(context['variant']['normalization'] == dict(status='UNCHANGED', method=METHOD, methodVersion=VERSION,
                    referenceSequenceSha256=reference['observedSha256'], refValidation='MATCH'), 'CONTEXT_NORMALIZATION_MISMATCH')
            patient, transcript = resolve(provenance['patient']), resolve(provenance['transcript'])
            require(all(patient.get(f) == context['patientContext'].get(f) for f in ('patientContextId', 'role', 'affectedStatus', 'sex', 'familyContextId'))
                    and all(transcript.get(f) == v for f, v in context['transcript'].items()), 'EXPLICIT_CONTEXT_RECEIPT_MISMATCH')
            hypothesis = patient['hypothesis']
            require(hypothesis['hgncId'] == context['gene']['hgncId'] and hypothesis['mondoId'] == context['disease']['mondoId']
                    and hypothesis['moi'] == context['moi']
                    and hypothesis['provenance'] == attempt['preflight']['original']['binding']['provenance']['patientHypothesis']['provenance']
                    and all(r['patientContextId'] == context['patientContext']['patientContextId'] for r in (proof, lineage, transcript)),
                    'EXPLICIT_PATIENT_HYPOTHESIS_ASSOCIATION_MISMATCH')
        verified += 1
    return dict(producedReceiptsVerified=verified, additionalFullVcfScans=0, fullReferenceHashesComputed=0,
                independentCurrentReferenceQueries=verified if resource_root else 0)


def verify_explicit_real_selection(batch):
    """Replay archived real VEP identity against fixed choices; old REF/normalization is not used."""
    smoke = batch['explicitSelectionProvenance']
    fixture = e1.load_json(ROOT / 'varvis-server/src/test/resources/e4-a2b/hbb-gjb2-explicit-compatible-v1.json')
    archive = e1.load_json(ROOT / 'docs/execution/E4_A2B_ACCEPTANCE_SOURCE_RECEIPTS_2026-09-06.json')
    sources = {r['relativeLocator']: r for r in archive['receipts']}
    raw = sources['e4-a2b-acceptance-final-preparation-attempt-1/real-vep-output.jsonl']
    require(e1.sha256_bytes(raw['rawUtf8'].encode()) == raw['sha256'] == smoke['vepOutputSha256'], 'RETAINED_VEP_HASH_MISMATCH')
    require(all(smoke[f] == fixture[f] for f in ('fixedChoices', 'choicesFixedAt', 'selectionPolicy')), 'EXPLICIT_FIXED_CHOICES_CHANGED')
    records = [json.loads(line) for line in raw['rawUtf8'].splitlines() if line.strip()]
    resolve = ledgers(batch)
    for row, choice in zip(batch['candidates'], fixture['fixedChoices'], strict=True):
        require(len(row['attempts']) == 1, 'REAL_SINGLE_ATTEMPT_PER_CHOICE_REQUIRED')
        attempt = row['attempts'][0]
        context, transcript = attempt['context'], attempt['transcriptInput']
        record = [r for r in records if r.get('id') == choice['caseId']]
        require(len(record) == 1, 'RETAINED_VEP_CASE_MISMATCH')
        record = record[0]
        require(record['assembly_name'] == choice['assembly'] == 'GRCh38' and record['seq_region_name'] == choice['contig']
                and record['start'] == record['end'] == choice['position']
                and record['allele_string'] == choice['reference'] + '/' + choice['alternate'], 'RETAINED_VEP_ALLELE_MISMATCH')
        found = [a for a in record['transcript_consequences'] if a['transcript_id'] == choice['transcript']]
        require(len(found) == 1, 'RETAINED_VEP_TRANSCRIPT_MISMATCH')
        annotation = found[0]
        amino = annotation['amino_acids']
        change = amino[0] + str(annotation['protein_start']) + amino[-1]
        require(re.fullmatch('[ACDEFGHIKLMNPQRSTVWY]/[ACDEFGHIKLMNPQRSTVWY]', amino)
                and annotation['protein_start'] == annotation['protein_end'] and change == choice['expectedAminoAcidChange']
                and annotation['hgvsp'] == choice['expectedHgvsp'] and annotation['gene_symbol'] == choice['symbol']
                and annotation['hgnc_id'] == choice['hgncId'] and annotation['variant_allele'] == choice['alternate'], 'RETAINED_VEP_PROTEIN_MISMATCH')
        require(all(context['variant']['inputAllele'][f] == choice[f] for f in ('assembly', 'contig', 'position', 'reference', 'alternate'))
                and context['gene'] == dict(hgncId=choice['hgncId'], symbol=choice['symbol']), 'REAL_FIXED_ALLELE_CHANGED')
        expected = dict(source='VEP', annotationVersion='116.0', geneSymbol=choice['symbol'], geneId=annotation['gene_id'],
                        versionedTranscriptId=choice['transcript'], hgvsc=annotation['hgvsc'], hgvsp=annotation['hgvsp'],
                        vepProteinIdentity=choice['expectedProteinIdentity'], aminoAcidChange=change, consequence='missense_variant')
        require(all(transcript.get(f) == v for f, v in expected.items()), 'JAVA_RETAINED_VEP_IDENTITY_MISMATCH')
        tx = resolve(attempt['preflight']['original']['provenance']['transcript'])
        require(tx['explicitSelectionReceipt'] == choice and tx['annotationArtifactSha256'] == raw['sha256']
                and tx['preparationArtifactSha256'] == smoke['inputPreparationSha256'], 'EXPLICIT_SELECTION_SOURCE_MISMATCH')
    return dict(vepOutputSha256=raw['sha256'], realVepExecutedThisStage=False, fixedChoicesVerified=len(fixture['fixedChoices']))


def verify(batch, resource_root=None, real=False):
    before = copy.deepcopy(batch)
    production = verify_production(batch, resource_root)
    result = a2b.verify_dispatch(batch)
    result['nativeSnvProduction'] = production
    if real:
        result['explicitSelectionSources'] = verify_explicit_real_selection(batch)
        # The new outer source format has already passed its own receipt audit; old A2B entry defaults stay strict.
        result['realExactAcceptance'] = a2b._require_real_exact_genes(batch, 2,
                metadata_field='explicitSelectionProvenance', receipt_field='sourceReceipts')
    require(batch == before, 'ORIGINAL_JAVA_NODES_MODIFIED')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dispatch', type=Path, required=True)
    parser.add_argument('--resource-root', type=Path)
    parser.add_argument('--real-exact', action='store_true')
    parser.add_argument('--summary-output', type=Path)
    args = parser.parse_args()
    result = verify(e1.load_json(args.dispatch), args.resource_root, args.real_exact)
    output = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.summary_output:
        args.summary_output.write_text(output, encoding='utf-8', newline='\n')
    print(output, end='')


if __name__ == '__main__':
    main()
