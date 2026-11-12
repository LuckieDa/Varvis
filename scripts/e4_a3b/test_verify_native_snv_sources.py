"""Generated receipt corruption is rejected after correctly updating byte hashes and references."""
import copy
import json
import tempfile
import unittest
from pathlib import Path

from scripts.e4_a3b import verify_native_snv_sources as verifier


class NativeSnvSourceVerificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = verifier.e1.load_json(verifier.ROOT / 'docs/execution/E4_A3B_EXACT_DISPATCH_2026-09-06.json')

    def setUp(self):
        self.data = copy.deepcopy(self.original)

    def reject(self, reason):
        with self.assertRaisesRegex(verifier.e1.ContractError, reason):
            verifier.verify_production(self.data)

    def change_receipt(self, kind, mutate):
        replacements = {}
        selected = next(r for r in self.data['sourceReceipts']
                        if r['source'].startswith('NATIVE_SNV_SOURCE_1')
                        and any(v.get('kind') == kind for v in json.loads(r['rawJson'])['records'].values()))
        envelope = json.loads(selected['rawJson'])
        mutate(next(v for v in envelope['records'].values() if v.get('kind') == kind))
        old = selected['sha256']
        selected['rawJson'] = json.dumps(envelope, separators=(',', ':'))
        selected['sha256'] = verifier.e1.sha256_bytes(selected['rawJson'].encode())
        replacements[old] = selected['sha256']

        def rewrite(value):
            if isinstance(value, dict):
                while value.get('artifactSha256') in replacements:
                    value['artifactSha256'] = replacements[value['artifactSha256']]
                for child in value.values():
                    rewrite(child)
            elif isinstance(value, list):
                for child in value:
                    rewrite(child)

        for _ in range(10):
            changed = False
            for receipt in self.data['sourceReceipts']:
                envelope = json.loads(receipt['rawJson'])
                before = copy.deepcopy(envelope)
                rewrite(envelope)
                if envelope != before:
                    old = receipt['sha256']
                    receipt['rawJson'] = json.dumps(envelope, separators=(',', ':'))
                    receipt['sha256'] = verifier.e1.sha256_bytes(receipt['rawJson'].encode())
                    replacements[old] = receipt['sha256']
                    changed = True
            if not changed:
                break
        else:
            self.fail('Receipt reference graph did not converge')
        rewrite(self.data)
        # All remaining failures must be semantics, not an accidentally stale byte hash or reference.
        verifier.ledgers(self.data)

    def test_actual_java_output_full_schema_ids_bundle_and_fixed_real_vep_sources(self):
        before = copy.deepcopy(self.data)
        result = verifier.verify(self.data, real=True)
        self.assertEqual(2, result['counts']['bundles'])
        self.assertEqual(2, result['nativeSnvProduction']['producedReceiptsVerified'])
        self.assertEqual(2, result['realExactAcceptance']['observedDistinctGenes'])
        self.assertEqual(before, self.data)

    def test_r1_all_invalid_ordinals_with_fresh_receipt_hashes(self):
        for ordinal in (4294967297, -4294967295, 18446744073709551617, -18446744073709551615,
                        2147483648, 0, -1, 1.0, 1.5, '1', True, None, 'MISSING', 2, 2147483647):
            with self.subTest(ordinal=ordinal):
                self.data = copy.deepcopy(self.original)
                def change(record):
                    if ordinal == 'MISSING':
                        record.pop('analysisVcfRecordOrdinal')
                    else:
                        record['analysisVcfRecordOrdinal'] = ordinal
                self.change_receipt('NORMALIZATION', change)
                self.reject('NORMALIZATION_ANALYSIS_SOURCE_MISMATCH')

    def test_full_alt_summary_cannot_be_changed_with_valid_source_ids(self):
        self.change_receipt('NORMALIZATION', lambda r: r['matchedRecord'].update(alternates=['G', 'A']))
        self.reject('MATCHED_RECORD_SUMMARY_MISMATCH')

    def test_execution_receipt_cannot_be_relabelled(self):
        self.change_receipt('NORMALIZATION', lambda r: r['execution'].update(requestSha256='0' * 64))
        self.reject('PRODUCED_EXECUTION_IDENTITY_MISMATCH')

    def test_execution_metadata_requires_lossless_integer_types(self):
        for field, value in (('fullVcfScans', True), ('analysisVcfByteLength', True), ('analysisVcfMtimeNs', 2**64)):
            with self.subTest(field=field):
                self.data = copy.deepcopy(self.original)
                self.change_receipt('NORMALIZATION', lambda r: r['execution'].update({field: value}))
                self.reject('PRODUCED_EXECUTION_(IDENTITY_MISMATCH|METADATA_INVALID)')

    def test_reference_observation_cannot_be_promoted_from_mismatch(self):
        self.change_receipt('NORMALIZATION', lambda r: r['referenceObservation'].update(observedReference='A'))
        self.reject('PRODUCED_REFERENCE_OBSERVATION_MISMATCH')

    def test_checksum_record_requires_real_accepted_reference(self):
        self.change_receipt('REFERENCE_IDENTITY', lambda r: r['actualChecksumReceipt'].update(mtimeNs=1))
        self.reject('ACCEPTED_CHECKSUM_REFERENCE_MISMATCH')

    def test_reference_checksum_applicability_time_must_be_utc(self):
        self.change_receipt('REFERENCE_IDENTITY', lambda r: r.update(checksumApplicabilityCheckedAt='2026-09-06T12:00:00+08:00'))
        self.reject('REFERENCE_UTC_REQUIRED')

    def test_configured_sha_cannot_claim_fresh_full_reference_verification(self):
        self.change_receipt('REFERENCE_IDENTITY', lambda r: r.update(fullReferenceHashComputed=True))
        self.reject('REFERENCE_CHECKSUM_CONTRACT_MISMATCH')

    def test_producer_may_not_replace_existing_explicit_normalization(self):
        self.data['sourceProduction']['inputs'][0]['suppliedNormalization'] = {'claimed': 'old-success'}
        self.reject('PRODUCER_SUCCESS_INVALID')

    def test_blocked_source_cannot_retain_executed_context(self):
        row = self.data['sourceProduction']['inputs'][0]
        row.update(status='BLOCKED', blocker={'status': 'CONTEXT_NOT_READY', 'reasonCode': 'REF_VALIDATION_NOT_MATCH', 'field': 'normalization.production'})
        self.reject('SOURCE_BLOCKER_NOT_PRESERVED')

    def test_wrong_patient_hypothesis_cannot_reuse_valid_context(self):
        receipt = next(r for r in self.data['sourceReceipts'] if r['source'].startswith('E4_A3A_EXPLICIT_RECEIPTS_1'))
        envelope = json.loads(receipt['rawJson'])
        old = receipt['sha256']
        envelope['records']['patient']['hypothesis']['mondoId'] = 'MONDO:0000001'
        receipt['rawJson'] = json.dumps(envelope, separators=(',', ':'))
        receipt['sha256'] = verifier.e1.sha256_bytes(receipt['rawJson'].encode())
        def walk(value):
            if isinstance(value, dict):
                if value.get('artifactSha256') == old:
                    value['artifactSha256'] = receipt['sha256']
                for child in value.values():
                    walk(child)
            elif isinstance(value, list):
                for child in value:
                    walk(child)
        walk(self.data)
        self.reject('EXPLICIT_PATIENT_HYPOTHESIS_ASSOCIATION_MISMATCH')

    def test_real_gate_never_accepts_allele_hit_only(self):
        atomic = self.data['candidates'][0]['attempts'][0]['atomicEvidence'][0]
        metric = next(m for m in atomic['measurements'] if m['metricId'] == 'alphamissense.transcriptProteinMatch')
        metric.update(rawValue=False, normalizedValue=False)
        with self.assertRaisesRegex(verifier.e1.ContractError, 'REAL_EXACT_TRANSCRIPT_PROTEIN_MATCH_REQUIRED'):
            verifier.a2b._require_real_exact_genes(self.data, 2,
                    metadata_field='explicitSelectionProvenance', receipt_field='sourceReceipts')

    def test_old_strict_entry_does_not_silently_adopt_new_receipt_format(self):
        with self.assertRaisesRegex(verifier.e1.ContractError, 'REAL_EXACT_DATA_ROLES_REQUIRED'):
            verifier.a2b._require_real_exact_genes(self.data, 2)

    def test_independent_reference_query_is_bounded_and_checks_actual_byte(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'tiny.fa'
            path.write_bytes(b'>chr11\nATCG\n')
            index = Path(str(path) + '.fai')
            index.write_bytes(b'chr11\t4\t7\t4\t5\n')
            sha = verifier.e1.sha256_file(path)
            manifest = dict(resourceId='SYNTHETIC', resourceVersion='1', assembly='GRCh38', logicalName='tiny.fa',
                            expectedSha256=sha, observedSha256=sha, hashAlgorithm='SHA-256', hashScope='RAW_BYTES', hashStatus='VERIFIED',
                            unchangedDuringHash=True, byteLength=path.stat().st_size, mtimeNs=path.stat().st_mtime_ns,
                            startedAt='2026-09-01T00:00:00Z', completedAt='2026-09-01T00:00:01Z', indexSha256=verifier.e1.sha256_file(index))
            identity = dict(actualChecksumReceipt=manifest, acceptedChecksumSource=dict(recordLocator='checksum'),
                            expectedSha256=sha, observedSha256=sha, assembly='GRCh38', verificationStatus='VERIFIED',
                            verificationMethod='SHA-256', verificationVersion='ACCEPTED_RECEIPT_APPLICABILITY_V1',
                            fullReferenceHashComputed=False, verificationRecordLocator='checksum', profileId='SYNTHETIC',
                            checksumApplicabilityCheckedAt='2026-09-01T00:00:02Z', indexSha256=manifest['indexSha256'], referenceArtifactLocator='tiny.fa')
            observation = dict(assembly='GRCh38', method=verifier.QUERY_METHOD, version=verifier.QUERY_VERSION, status='MATCH',
                               expectedReference='T', observedReference='T', observedAt='2026-09-01T00:00:02Z', contig='chr11',
                               referenceContig='chr11', position=2, contigAssociation=dict(method='EXACT_CONTIG'))
            resolve = lambda ref: dict(actualChecksumReceipt=manifest)
            verifier.verify_reference(identity, observation, resolve, directory)
            observation.update(expectedReference='A', observedReference='A')
            with self.assertRaisesRegex(verifier.e1.ContractError, 'INDEPENDENT_REFERENCE_OBSERVATION_MISMATCH'):
                verifier.verify_reference(identity, observation, resolve, directory)


if __name__ == '__main__':
    unittest.main()
