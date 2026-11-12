import copy
import json
import os
import unittest
from pathlib import Path
from scripts.e4_b1 import verify_business_run as verifier


class BusinessReadbackTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        path = Path(os.environ.get('E4_B1_ARCHIVE', 'docs/execution/E4_B1_API_READBACK_2026-09-06.json'))
        cls.archive = verifier.e1.load_json(path)

    def check(self, value):
        return verifier.verify(value)

    def resign_response(self, value):
        raw = json.dumps(value['apiResult'], ensure_ascii=False, separators=(',', ':'))
        value['persistedResponseRaw'] = raw
        value['persistedResponseSha256'] = verifier.e1.sha256_bytes(raw.encode())

    def test_actual_persisted_api_and_all_identity_projections(self):
        original = copy.deepcopy(self.archive)
        self.assertEqual('PASS', self.check(self.archive)['status'])
        self.assertEqual(self.archive, original)

    def test_api_divergence(self):
        value = copy.deepcopy(self.archive)
        value['apiResult']['providerCalls'] = 0
        with self.assertRaisesRegex(verifier.e1.ContractError, 'API_READBACK_CHANGED'):
            self.check(value)

    def test_corrupt_saved_hash(self):
        value = copy.deepcopy(self.archive); value['persistedResponseSha256'] = '0' * 64
        with self.assertRaisesRegex(verifier.e1.ContractError, 'PERSISTED_RESPONSE_HASH_MISMATCH'):
            self.check(value)

    def test_private_ledger_corruption(self):
        value = copy.deepcopy(self.archive); value['persistedPrivateRaw'] += ' '
        with self.assertRaisesRegex(verifier.e1.ContractError, 'PERSISTED_PRIVATE_HASH_MISMATCH'):
            self.check(value)

    def test_bundle_identity_forgery_after_rehashing_storage(self):
        value = copy.deepcopy(self.archive)
        value['apiResult']['bundles'][0]['bundle']['bundleId'] = 'bundle-sha256-' + '0' * 64
        self.resign_response(value)
        with self.assertRaisesRegex(verifier.e1.ContractError, 'PERSISTED_JAVA_IDENTITIES_CHANGED_BY_REHASH'):
            self.check(value)

    def test_bundle_must_contain_actual_java_nodes(self):
        value = copy.deepcopy(self.archive)
        value['apiResult']['bundles'][0]['bundle']['criterionEvaluations'][0]['evaluationResult']['status'] = 'MET'
        self.resign_response(value)
        with self.assertRaisesRegex(verifier.e1.ContractError, 'PERSISTED_JAVA_NODE_CHANGED'):
            self.check(value)

    def test_candidate_order(self):
        value = copy.deepcopy(self.archive); value['apiResult']['candidateIds'].reverse(); self.resign_response(value)
        with self.assertRaisesRegex(verifier.e1.ContractError, 'BUSINESS_CANDIDATE_ASSOCIATION_CHANGED'):
            self.check(value)

    def test_declaration_revision_identity_after_rehashing_storage(self):
        value = copy.deepcopy(self.archive)
        private = json.loads(value['persistedPrivateRaw']); private['declaration']['declarationVersion'] = 'changed'
        value['persistedPrivateRaw'] = json.dumps(private, ensure_ascii=False)
        value['apiResult']['privateArtifactSha256'] = verifier.e1.sha256_bytes(value['persistedPrivateRaw'].encode())
        self.resign_response(value)
        with self.assertRaisesRegex(verifier.e1.ContractError, 'DECLARATION_REQUEST_ID_MISMATCH'):
            self.check(value)

    def test_combination_not_executed(self):
        value = copy.deepcopy(self.archive)
        value['apiResult']['bundles'][0]['bundle']['combination']['result'] = 'PATHOGENIC'
        self.resign_response(value)
        with self.assertRaises(verifier.e1.ContractError): self.check(value)

    def test_changed_declaration_cannot_reuse_old_receipts_even_after_identity_recalculation(self):
        value = copy.deepcopy(self.archive)
        private = json.loads(value['persistedPrivateRaw']); private['declaration']['patient']['sex'] = 'FEMALE'
        value['persistedPrivateRaw'] = json.dumps(private, ensure_ascii=False)
        value['apiResult']['privateArtifactSha256'] = verifier.e1.sha256_bytes(value['persistedPrivateRaw'].encode())
        value['apiResult']['requestSha256'] = verifier.e1.canonical_sha256(private['declaration'])
        self.resign_response(value)
        with self.assertRaisesRegex(verifier.e1.ContractError, 'BUSINESS_DECLARATION_RECEIPT_MISMATCH'):
            self.check(value)

    def test_private_snapshot_ordinal_no_truncation(self):
        if 'sourceSnapshotRaw' not in self.archive:
            # Committed audit projection deliberately excludes machine paths; full private snapshot is opt-in.
            self.skipTest('Set E4_B1_ARCHIVE to the locally generated private business archive for snapshot negative tests')
        for ordinal in (4294967297, -4294967295, 9223372036854775808123, 0, -1, 1.5, True, '1', None):
            with self.subTest(ordinal=ordinal):
                value = copy.deepcopy(self.archive)
                snapshot = json.loads(value['sourceSnapshotRaw']); snapshot['candidates'][0]['matchedRecord']['recordOrdinal'] = ordinal
                value['sourceSnapshotRaw'] = json.dumps(snapshot, ensure_ascii=False)
                digest = verifier.e1.sha256_bytes(value['sourceSnapshotRaw'].encode())
                value['sourceSnapshotSha256'] = digest; value['apiResult']['executionSourceSha256'] = digest; self.resign_response(value)
                with self.assertRaisesRegex(verifier.e1.ContractError, 'ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED'):
                    self.check(value)


if __name__ == '__main__':
    unittest.main()
