"""Counterfeit source associations keep valid frozen Java identities; those alone cannot admit them."""
import copy
import json
import unittest

from scripts.e4_a3a import verify_candidate_input as verifier


class CandidateInputVerificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = verifier.e1.load_json(verifier.ROOT / 'docs/execution/E4_A3A_EXACT_DISPATCH_2026-09-06.json')

    def setUp(self):
        self.data = copy.deepcopy(self.original)

    def reject(self, reason):
        with self.assertRaisesRegex(verifier.e1.ContractError, reason):
            verifier.verify_sources(self.data)

    def test_full_bundle_validation_leaves_actual_java_nodes_unchanged(self):
        before = copy.deepcopy(self.data)
        result = verifier.verify(self.data)
        self.assertEqual(2, result['counts']['bundles'])
        self.assertEqual(self.data, before)

    def test_task_association_cannot_reuse_valid_context_ids(self):
        self.data['candidateInputProvenance']['analysisId'] = 'different-task'
        self.reject('TASK_ANALYSIS_ASSOCIATION_MISMATCH')

    def test_converted_analysis_cannot_reuse_valid_native_contexts(self):
        self.data['candidateInputProvenance']['originalInputAssembly'] = 'GRCh37'
        self.data['candidateInputProvenance']['conversionApplied'] = True
        self.reject('UNPROVEN_ANALYSIS_INPUT_DISPATCHED')

    def test_call_record_ordinal(self):
        self.data['candidateInputProvenance']['candidateAssociations'][0]['analysisVcfCall']['matchedRecordOrdinal'] = 2
        self.reject('ANALYSIS_CALL_RECORD_ALT_MISMATCH')

    def test_call_target_alt_index(self):
        self.data['candidateInputProvenance']['candidateAssociations'][0]['analysisVcfCall']['targetAlleleIndex'] = 2
        self.reject('ANALYSIS_CALL_RECORD_ALT_MISMATCH')

    def test_changed_tsv_gene_even_after_rehashing_source(self):
        fixture = self.data['candidateInputFixture']
        fixture['rawVariantTsv'] = fixture['rawVariantTsv'].replace('\tHBB\t', '\tCOUNTERFEIT\t')
        digest = verifier.e1.sha256_bytes(fixture['rawVariantTsv'].encode())
        fixture['variantTsvSha256'] = self.data['candidateInputProvenance']['outputSha256'] = digest
        self.reject('CONTEXT_PARSED_CANDIDATE_MISMATCH')

    def test_changed_vcf_even_after_rehashing_source(self):
        fixture = self.data['candidateInputFixture']
        fixture['rawAnalysisVcf'] = fixture['rawAnalysisVcf'].replace('\tT\tA\t', '\tT\tG\t')
        digest = verifier.e1.sha256_bytes(fixture['rawAnalysisVcf'].encode())
        fixture['analysisVcfSha256'] = self.data['candidateInputProvenance']['uploadArtifactSha256'] = digest
        self.reject('ANALYSIS_CALL_SOURCE_MISMATCH')

    def test_changed_gene_id_even_after_rehashing_source(self):
        fixture = self.data['candidateInputFixture']
        fixture['rawVariantTsv'] = fixture['rawVariantTsv'].replace('\t3043\t', '\t999\t')
        digest = verifier.e1.sha256_bytes(fixture['rawVariantTsv'].encode())
        fixture['variantTsvSha256'] = self.data['candidateInputProvenance']['outputSha256'] = digest
        self.reject('CONTEXT_PARSED_GENE_ID_MISMATCH')

    def test_receipt_ordinal_with_valid_receipt_hash_and_references(self):
        self.change_receipt_ordinal(99)
        self.reject('NORMALIZATION_ANALYSIS_SOURCE_MISMATCH')

    def test_invalid_receipt_ordinals_with_recomputed_hashes_and_references(self):
        for ordinal in (4294967297, -4294967295, 18446744073709551617, -18446744073709551615,
                        2147483648, 0, -1, 1.0, 1.5, '1', True, None, 'MISSING', 2, 2147483647):
            with self.subTest(ordinal=ordinal):
                self.data = copy.deepcopy(self.original)
                self.change_receipt_ordinal(ordinal)
                self.reject('NORMALIZATION_ANALYSIS_SOURCE_MISMATCH')

    def change_receipt_ordinal(self, ordinal):
        receipt = self.data['smokeInputProvenance']['sourceReceipts'][1]
        old = receipt['sha256']
        record = json.loads(receipt['rawJson'])
        if ordinal == 'MISSING':
            record['records']['normalization'].pop('analysisVcfRecordOrdinal')
        else:
            record['records']['normalization']['analysisVcfRecordOrdinal'] = ordinal
        receipt['rawJson'] = json.dumps(record, separators=(',', ':'))
        new = receipt['sha256'] = verifier.e1.sha256_bytes(receipt['rawJson'].encode())

        def change_ref(value):
            if isinstance(value, dict):
                if value.get('artifactSha256') == old:
                    value['artifactSha256'] = new
                for child in value.values():
                    change_ref(child)
            elif isinstance(value, list):
                for child in value:
                    change_ref(child)
        change_ref(self.data)
        # Context/evidence/selection IDs remain untouched and valid; the source relation itself is invalid.

    def test_top_n_order(self):
        self.data['candidateInputProvenance']['candidateAssociations'].reverse()
        self.reject('TOP_N_SOURCE_ORDER_CHANGED')

    def test_original_record_missing_is_not_used_as_a_failure_flag(self):
        self.assertEqual(2, self.data['candidateInputFixture']['originalRecordNullCount'])
        self.assertEqual(2, verifier.verify_sources(self.data)['contextsWithVerifiedInputAssociation'])


if __name__ == '__main__':
    unittest.main()
