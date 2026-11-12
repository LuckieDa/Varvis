"""Fresh declaration source corruption is rejected after rehashing the changed raw receipt and references."""
import copy
import json
import os
import unittest
from pathlib import Path

from scripts.e4_a3c import verify_explicit_case_input as verifier


class ExplicitCaseVerificationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.original = verifier.e1.load_json(Path(os.environ.get('E4_A3C_DISPATCH',
                verifier.ROOT / 'docs/execution/E4_A3C_EXACT_DISPATCH_2026-09-06.json')))

    def setUp(self):
        self.data = copy.deepcopy(self.original)

    def change(self, record, mutate):
        receipt = next(r for r in self.data['sourceReceipts'] if r['source'] == 'EXPLICIT_CASE_DECLARATION_1')
        envelope = json.loads(receipt['rawJson'])
        mutate(envelope['records'][record])
        old = receipt['sha256']
        receipt['rawJson'] = json.dumps(envelope, separators=(',', ':'))
        receipt['sha256'] = verifier.e1.sha256_bytes(receipt['rawJson'].encode())

        def refs(value):
            if isinstance(value, dict):
                if value.get('artifactSha256') == old:
                    value['artifactSha256'] = receipt['sha256']
                for child in value.values():
                    refs(child)
            elif isinstance(value, list):
                for child in value:
                    refs(child)
        refs(self.data)
        verifier.a3b.ledgers(self.data)

    def reject(self, reason):
        with self.assertRaisesRegex(verifier.e1.ContractError, reason):
            verifier.verify_intake(self.data)

    def test_actual_java_output_source_schema_all_ids_and_bundle(self):
        before = copy.deepcopy(self.data)
        result = verifier.verify(self.data, real=True)
        self.assertEqual(2, result['counts']['bundles'])
        self.assertEqual(2, result['explicitDeclarationIntake']['declarationsVerified'])
        self.assertEqual(2, result['realExactAcceptance']['observedDistinctGenes'])
        self.assertEqual(before, self.data)

    def test_receipt_is_caller_assertion_not_independent_clinical_validation(self):
        self.change('declaration', lambda r: r.update(claim='CLINICALLY_VALIDATED'))
        self.reject('DECLARATION_CLAIM_CHANGED')

    def test_received_time_is_utc_without_invented_confirmation(self):
        for change, reason in ((dict(receivedAt='2026-09-06T12:00:00+08:00'), 'REFERENCE_UTC_REQUIRED'),
                               (dict(confirmedAt='2026-09-06T00:00:00Z'), 'DECLARATION_CLAIM_CHANGED')):
            with self.subTest(change=change):
                self.data = copy.deepcopy(self.original)
                self.change('declaration', lambda r: r.update(change))
                self.reject(reason)

    def test_execution_cannot_be_relabelled(self):
        self.change('declaration', lambda r: r['execution'].update(outputSha256='0' * 64))
        self.reject('DECLARATION_EXECUTION_MISMATCH')

    def test_analysis_or_sample_cannot_be_relabelled(self):
        for field in ('analysisId', 'sampleId'):
            with self.subTest(field=field):
                self.data = copy.deepcopy(self.original)
                self.change('declaration', lambda r: r.update({field: 'other'}))
                self.reject('DECLARATION_ANALYSIS_SAMPLE_MISMATCH')

    def test_affected_cannot_be_promoted_from_missing_or_boolean(self):
        for value in (None, False, ''):
            with self.subTest(value=value):
                self.data = copy.deepcopy(self.original)
                self.change('declaration', lambda r: r['input'].update(affectedStatus=value))
                self.reject('DECLARATION_PATIENT_FIELDS_REQUIRED')

    def test_declared_affected_must_equal_generated_receipt(self):
        self.change('declaration', lambda r: r['input'].update(affectedStatus='UNAFFECTED'))
        self.reject('DECLARATION_PATIENT_RECEIPT_MISMATCH')

    def test_mondo_label_cannot_change_binding(self):
        self.change('declaration', lambda r: r['input']['hypothesis'].update(mondoId='MONDO:0099999'))
        self.reject('DECLARATION_BINDING_MISMATCH')

    def test_missing_or_local_declaration_sources_are_not_validated(self):
        for source, reason in ((None, 'DECLARATION_SOURCE_REQUIRED'),
                               (dict(source='CALLER', version='1', recordLocator='/private/case'), 'DECLARATION_SOURCE_INVALID')):
            with self.subTest(source=source):
                self.data = copy.deepcopy(self.original)
                self.change('declaration', lambda r: r['patient'].update(source=source))
                self.reject(reason)

    def test_transcript_version_is_exact_positive_integer(self):
        for version in (None, 4294967297, 1.0, '1', True):
            with self.subTest(version=version):
                self.data = copy.deepcopy(self.original)
                self.change('declaration', lambda r: r['input']['transcript'].update(version=version))
                self.reject('DECLARATION_TRANSCRIPT_FIELDS_REQUIRED')

    def test_transcript_choice_cannot_be_replaced_by_first_or_mane(self):
        self.change('transcript', lambda r: r.update(selectionPolicy='AUTO_MANE'))
        self.reject('DECLARATION_TRANSCRIPT_RECEIPT_MISMATCH')

    def test_annotation_source_is_exact(self):
        self.change('transcript', lambda r: r['annotation'].update(version='115.0'))
        self.reject('DECLARATION_ANNOTATION_MISMATCH')

    def test_versioned_annotation_cannot_be_stripped(self):
        self.change('transcript', lambda r: r['annotation'].update(transcriptAccession='ENST00000647020.2'))
        self.reject('DECLARATION_ANNOTATION_MISMATCH')

    def test_selection_reason_not_replaced_by_unverified_clinical_claim(self):
        self.change('transcript', lambda r: r.update(selectionReason='Clinically confirmed'))
        self.reject('DECLARATION_TRANSCRIPT_RECEIPT_MISMATCH')

    def test_blocked_declaration_may_not_retain_executed_context(self):
        self.data['declarationIntake'][0]['blocker'] = dict(status='CONTEXT_NOT_READY', reasonCode='DECLARATION_SAMPLE_CONFLICT', field='declaration.sampleId')
        self.reject('DECLARATION_BLOCKER_NOT_PRESERVED')

    def test_independent_real_vep_snapshot_not_just_allele_hit(self):
        self.change('transcript', lambda r: r['annotation'].update(hgvsp='ENSP00000494175.1:p.Glu7Ala'))
        with self.assertRaisesRegex(verifier.e1.ContractError, 'DECLARATION_RETAINED_VEP_SNAPSHOT_MISMATCH'):
            verifier.verify_real_selection(self.data)


if __name__ == '__main__':
    unittest.main()
