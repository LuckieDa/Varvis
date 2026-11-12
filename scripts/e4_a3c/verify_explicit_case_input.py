"""Audit fresh caller-declaration receipts and existing source/schema/identity/bundle output, without repair."""
from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from scripts.e4_a3b import verify_native_snv_sources as a3b

e1, a2b, require = a3b.e1, a3b.a2b, a3b.require
CLAIM = 'CALLER_DECLARATION_NOT_INDEPENDENT_CLINICAL_VERIFICATION'


def declared_source(source):
    require(isinstance(source, dict) and set(source) == {'source', 'version', 'recordLocator'}, 'DECLARATION_SOURCE_REQUIRED')
    for value in source.values():
        require(isinstance(value, str) and value and value.strip() == value
                and not value.startswith(('/', '\\')) and not re.match(r'^[A-Za-z]:[\\/]', value)
                and not value.lower().startswith('file:') and '..' not in value.replace('\\', '/').split('/')
                and not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value), 'DECLARATION_SOURCE_INVALID')


def verify_intake(batch):
    resolve = a3b.ledgers(batch)
    source = batch['candidateInputProvenance']
    sample = next(line.split('\t')[9] for line in batch['candidateInputFixture']['rawAnalysisVcf'].splitlines() if line.startswith('#CHROM'))
    attempts = [a for c in batch['candidates'] for a in c['attempts']]
    verified = 0
    for ordinal, outcome in enumerate(batch['declarationIntake'], 1):
        require(type(outcome['ordinal']) is int and outcome['ordinal'] == ordinal, 'DECLARATION_ORDER_CHANGED')
        ref = outcome['declaration']
        matching = [a for a in attempts if a['preflight']['original']['candidateId'] == outcome['candidateId']
                    and a['preflight']['original']['hypothesisId'] == outcome['hypothesisId']]
        if not matching:
            require(any(a['candidateId'] == outcome['candidateId'] and a['hypothesisId'] == outcome['hypothesisId']
                        for a in batch['preflight']['unmatchedInputs']), 'DECLARATION_ROW_LOST')
            continue
        if ref is None:
            require(outcome['blocker'] is not None, 'DECLARATION_REFERENCE_REQUIRED')
        else:
            require(ref['source'] == f'EXPLICIT_CASE_DECLARATION_{ordinal}' and ref['version'] == '1.0.0'
                    and ref['recordLocator'] == 'declaration', 'DECLARATION_RECEIPT_IDENTITY_MISMATCH')
            received = resolve(ref, 'CASE_DECLARATION')
            require(received['claim'] == CLAIM and 'confirmedAt' not in received, 'DECLARATION_CLAIM_CHANGED')
            require(a3b.utc(received['receivedAt']) <= datetime.now(timezone.utc), 'DECLARATION_RECEIVED_TIME_INVALID')
        if outcome['blocker']:
            require(all(a['context'] is None and a['providerCalls'] == a['evaluatorCalls'] == 0
                        and outcome['blocker'] in a['blockers'] for a in matching), 'DECLARATION_BLOCKER_NOT_PRESERVED')
            continue
        require(len(matching) == 1, 'DECLARATION_HYPOTHESIS_AMBIGUOUS')
        attempt = matching[0]
        original = attempt['preflight']['original']
        provenance, binding = original['provenance'], original['binding']
        patient = resolve(provenance['patient'], 'PATIENT_CONTEXT')
        transcript = resolve(provenance['transcript'], 'TRANSCRIPT_CHOICE')
        require(all(provenance[k] == dict(ref, recordLocator=k) for k in ('patient', 'transcript')),
                'DECLARATION_SOURCE_NOT_CONSUMED')
        require(received['analysisId'] == source['analysisId'] and received['sampleId'] == sample,
                'DECLARATION_ANALYSIS_SAMPLE_MISMATCH')
        execution = received['execution']
        require(execution['liveExecutionMatched'] is True and execution['analysisId'] == source['analysisId']
                and execution['analysisVcfArtifactSha256'] == source['uploadArtifactSha256']
                and all(execution[f] == source[f] for f in ('requestSha256', 'commandSha256', 'outputSha256')),
                'DECLARATION_EXECUTION_MISMATCH')
        declaration, supplied = received['patient'], received['input']
        declared_source(declaration['source'])
        declared_source(supplied['hypothesis']['provenance'])
        declared_source(supplied['transcript']['source'])
        require(declaration['role'] == 'PROBAND' and declaration['sex'] in ('FEMALE', 'MALE', 'UNKNOWN')
                and supplied['affectedStatus'] in ('AFFECTED', 'UNAFFECTED', 'UNKNOWN'), 'DECLARATION_PATIENT_FIELDS_REQUIRED')
        require(all(patient.get(f) == declaration.get(f) for f in ('patientContextId', 'role', 'sex', 'familyContextId'))
                and patient['affectedStatus'] == supplied['affectedStatus'] and patient['declarationSource'] == declaration['source'],
                'DECLARATION_PATIENT_RECEIPT_MISMATCH')
        bound_hypothesis = binding['provenance']['patientHypothesis']
        bound_source = dict(source=ref['source'], version=ref['version'], recordLocator='declaration')
        require(binding['status'] == 'READY' and all(supplied['hypothesis'][f] == bound_hypothesis[f]
                    for f in ('candidateId', 'hypothesisId', 'hgncId', 'mondoId', 'moi'))
                and bound_hypothesis['provenance'] == bound_source
                and supplied['hypothesis']['candidateId'] == outcome['candidateId']
                and supplied['hypothesis']['hypothesisId'] == outcome['hypothesisId'], 'DECLARATION_BINDING_MISMATCH')
        require(patient['hypothesis'] == dict(hgncId=binding['hgncId'], mondoId=binding['mondoId'], moi=binding['normalizedMoi'],
                provenance=bound_source), 'DECLARATION_DISEASE_RECEIPT_MISMATCH')
        choice = supplied['transcript']
        require(type(choice['version']) is int and 0 < choice['version'] <= 2**31 - 1
                and isinstance(choice['selectionReason'], str) and choice['selectionReason'].strip(), 'DECLARATION_TRANSCRIPT_FIELDS_REQUIRED')
        require(all(transcript[f] == choice[f] for f in ('accession', 'version', 'selectionPolicy', 'annotationSource', 'annotationSourceVersion', 'selectionReason'))
                and transcript['declarationSource'] == choice['source'], 'DECLARATION_TRANSCRIPT_RECEIPT_MISMATCH')
        annotation = transcript['annotation']
        require(type(transcript['candidateAnnotationIndex']) is int and transcript['candidateAnnotationIndex'] >= 0
                and transcript['annotationRecordLocator'] == 'transcript/annotation', 'DECLARATION_ANNOTATION_REFERENCE_REQUIRED')
        selected = choice['accession'] + '.' + str(choice['version'])
        coding_identity = annotation['hgvsCoding'].split(':', 1)[0] if annotation.get('hgvsCoding') and ':' in annotation['hgvsCoding'] else None
        identities = (annotation.get('transcriptAccession'), annotation.get('transcriptId'), coding_identity)
        versioned = next((s for s in (identities[0], coding_identity) if s and re.fullmatch(r'.+\.[0-9]+', s)), None)
        require(versioned == selected and all(s in (None, '', choice['accession'], selected) for s in identities)
                and annotation['geneSymbol'] == binding['symbol']
                and annotation['source'] == choice['annotationSource'] and annotation['version'] == choice['annotationSourceVersion']
                and annotation['status'] == 'COMPLETED', 'DECLARATION_ANNOTATION_MISMATCH')
        for record in (patient, transcript):
            require(record['claim'] == CLAIM and record['declarationRecordLocator'] == 'declaration'
                    and record['analysisId'] == received['analysisId'] and record['sampleId'] == sample
                    and record['patientContextId'] == declaration['patientContextId']
                    and record['candidateId'] == outcome['candidateId'] and record['hypothesisId'] == outcome['hypothesisId'],
                    'DECLARATION_RECORD_ASSOCIATION_MISMATCH')
        if attempt['context'] is not None:
            context = attempt['context']
            require(all(context['patientContext'].get(f) == patient.get(f) for f in ('patientContextId', 'role', 'sex', 'affectedStatus', 'familyContextId'))
                    and context['disease']['mondoId'] == supplied['hypothesis']['mondoId'], 'DECLARATION_CONTEXT_MISMATCH')
        verified += 1
    return dict(declarationsVerified=verified, clinicalValidation=False, pedigreeAffectedUsed=False)


def verify_real_selection(batch):
    fixture = e1.load_json(ROOT / 'varvis-server/src/test/resources/e4-a2b/hbb-gjb2-explicit-compatible-v1.json')
    smoke = batch['explicitSelectionProvenance']
    require(all(smoke[f] == fixture[f] for f in fixture), 'EXPLICIT_FIXED_CHOICES_CHANGED')
    archive = e1.load_json(ROOT / 'docs/execution/E4_A2B_ACCEPTANCE_SOURCE_RECEIPTS_2026-09-06.json')
    raw = next(r for r in archive['receipts'] if r['relativeLocator'] == 'e4-a2b-acceptance-final-preparation-attempt-1/real-vep-output.jsonl')
    require(e1.sha256_bytes(raw['rawUtf8'].encode()) == raw['sha256'] == smoke['vepOutputSha256'], 'RETAINED_VEP_HASH_MISMATCH')
    require(smoke['annotationUse'] == 'RETAINED_REAL_VEP_FIXED_ALLELES_NO_NEW_VEP_RUN', 'REAL_ANNOTATION_SCOPE_REQUIRED')
    records = [json.loads(line) for line in raw['rawUtf8'].splitlines() if line.strip()]
    resolve = a3b.ledgers(batch)
    for row, choice in zip(batch['candidates'], fixture['fixedChoices'], strict=True):
        require(len(row['attempts']) == 1, 'REAL_SINGLE_ATTEMPT_PER_CHOICE_REQUIRED')
        attempt = row['attempts'][0]
        context, tx = attempt['context'], attempt['transcriptInput']
        record = [r for r in records if r['id'] == choice['caseId']]
        require(len(record) == 1, 'RETAINED_VEP_CASE_MISMATCH')
        record = record[0]
        require(record['assembly_name'] == 'GRCh38' and record['seq_region_name'] == choice['contig']
                and record['start'] == record['end'] == choice['position']
                and record['allele_string'] == choice['reference'] + '/' + choice['alternate'], 'RETAINED_VEP_ALLELE_MISMATCH')
        found = [a for a in record['transcript_consequences'] if a['transcript_id'] == choice['transcript']]
        require(len(found) == 1, 'RETAINED_VEP_TRANSCRIPT_MISMATCH')
        annotation = found[0]
        require(re.fullmatch('[ACDEFGHIKLMNPQRSTVWY]/[ACDEFGHIKLMNPQRSTVWY]', annotation['amino_acids'])
                and annotation['protein_start'] == annotation['protein_end'], 'RETAINED_VEP_PROTEIN_MISMATCH')
        change = annotation['amino_acids'][0] + str(annotation['protein_start']) + annotation['amino_acids'][-1]
        require(change == choice['expectedAminoAcidChange'] and annotation['hgvsp'] == choice['expectedHgvsp']
                and annotation['gene_symbol'] == choice['symbol'] and annotation['hgnc_id'] == choice['hgncId']
                and annotation['variant_allele'] == choice['alternate'], 'RETAINED_VEP_PROTEIN_MISMATCH')
        require(all(context['variant']['inputAllele'][f] == choice[f] for f in ('assembly', 'contig', 'position', 'reference', 'alternate'))
                and context['gene'] == dict(hgncId=choice['hgncId'], symbol=choice['symbol']), 'REAL_FIXED_ALLELE_CHANGED')
        expected = dict(source='VEP', annotationVersion='116.0', geneSymbol=choice['symbol'], geneId=annotation['gene_id'],
                        versionedTranscriptId=choice['transcript'], hgvsc=annotation['hgvsc'], hgvsp=annotation['hgvsp'],
                        vepProteinIdentity=choice['expectedProteinIdentity'], aminoAcidChange=change, consequence='missense_variant')
        require(all(tx.get(f) == value for f, value in expected.items()), 'JAVA_RETAINED_VEP_IDENTITY_MISMATCH')
        receipt = resolve(attempt['preflight']['original']['provenance']['transcript'])
        snapshot = receipt['annotation']
        require(snapshot['hgvsc'] == annotation['hgvsc'] and snapshot['hgvsp'] == annotation['hgvsp']
                and snapshot['geneId'] == annotation['gene_id'] and snapshot['aminoAcids'] == annotation['amino_acids']
                and snapshot['transcriptAccession'] == choice['transcript'], 'DECLARATION_RETAINED_VEP_SNAPSHOT_MISMATCH')
        require(receipt['selectionReason'] == choice['selectionBasis']
                and receipt['declarationSource'] == dict(source='EXPLICIT_COMPATIBILITY_TEST_SELECTION', version='1',
                    recordLocator='src/test/resources/e4-a2b/hbb-gjb2-explicit-compatible-v1.json#' + choice['fixtureId']),
                'DECLARATION_FIXED_SELECTION_SOURCE_MISMATCH')
    return dict(fixedChoicesVerified=2, vepOutputSha256=raw['sha256'], realVepExecutedThisStage=False)


def verify(batch, resource_root=None, real=False):
    before = copy.deepcopy(batch)
    intake = verify_intake(batch)
    result = a3b.verify(batch, resource_root)  # Keeps A3B, old source gates and independent frozen IDs unchanged.
    result['explicitDeclarationIntake'] = intake
    if real:
        result['explicitSelectionSources'] = verify_real_selection(batch)
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
