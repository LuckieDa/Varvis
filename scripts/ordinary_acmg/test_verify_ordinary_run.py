"""Opt-in negative tests against the captured original Java/remote-DB run."""
import copy
import json
import os
import unittest
from pathlib import Path
from scripts.ordinary_acmg.verify_ordinary_run import verify, e1


@unittest.skipUnless(os.environ.get('VARVIS_ORDINARY_ACCEPTANCE_ROOT'), 'requires retained public acceptance artifacts')
class OrdinaryRunAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        root = Path(os.environ['VARVIS_ORDINARY_ACCEPTANCE_ROOT'])
        cls.archive = e1.load_json(root / 'private-audit.json')
        cls.panel = e1.load_json(root / 'fixed-panel.json')

    def check(self, value):
        return verify(value, self.panel, Path(os.environ['VARVIS_RESOURCE_ROOT']))

    def test_original_nodes_remain_unchanged(self):
        value = copy.deepcopy(self.archive)
        self.assertEqual('PASS', self.check(value)['status'])
        self.assertEqual(value, self.archive)

    def test_formal_order_cannot_be_replaced(self):
        value = copy.deepcopy(self.archive); value['ranking']['variants'].reverse()
        with self.assertRaisesRegex(e1.ContractError, 'FORMAL_ORDER_CHANGED'): self.check(value)

    def test_relabelled_engine_is_not_real_execution(self):
        value = copy.deepcopy(self.archive); value['job']['engine'] = 'SYNTHETIC_RUNNER'
        with self.assertRaisesRegex(e1.ContractError, 'REAL_NATIVE_ENGINE_REQUIRED'): self.check(value)

    def test_saved_bytes_cannot_be_silently_repaired(self):
        value = copy.deepcopy(self.archive); value['apiResult']['bundles'][0]['bundle']['bundleId'] = 'bundle-sha256-' + '0' * 64
        with self.assertRaisesRegex(e1.ContractError, 'REMOTE_DB_READBACK_MISMATCH'): self.check(value)

    def test_resigned_database_bytes_still_require_original_bundle_identity(self):
        value = copy.deepcopy(self.archive)
        value['apiResult']['bundles'][0]['bundle']['bundleId'] = 'bundle-sha256-' + '0' * 64
        value['persistedResponseRaw'] = json.dumps(value['apiResult'], ensure_ascii=False)
        value['persistedResponseSha256'] = e1.sha256_bytes(value['persistedResponseRaw'].encode())
        with self.assertRaisesRegex(e1.ContractError, 'ORIGINAL_JAVA_ID_MISMATCH'): self.check(value)

    def test_different_selected_transcript_does_not_satisfy_fixed_input_acceptance(self):
        panel = copy.deepcopy(self.panel); panel['variants'][0]['transcript'] = 'ENST00000646891.999'
        with self.assertRaisesRegex(e1.ContractError, 'FIXED_TRANSCRIPT_CHANGED'):
            verify(copy.deepcopy(self.archive), panel, Path(os.environ['VARVIS_RESOURCE_ROOT']))


if __name__ == '__main__':
    unittest.main()
