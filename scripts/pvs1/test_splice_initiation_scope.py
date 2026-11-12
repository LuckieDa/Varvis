"""RNA initiation policy contracts only; these declarations do not qualify science."""
import copy
import unittest
from splice_source_qualification import scope_issue


class RnaInitiationScopeTest(unittest.TestCase):
    def setUp(self):
        self.source = dict(sourceId='TEST_ONLY', version='TEST_ONLY', url='https://example.test/rna-initiation', sha256='1'*64)
        self.scope = dict(status='REVIEWED_FOR_FULL_RNA_INITIATION', method='CLINGEN_SVI_2018_START_LOSS', methodVersion='1.0.0',
                          reviewId='TEST_ONLY', basis='Invented policy contract, not scientific evidence', sourceIds=['TEST_ONLY'],
                          maximumStrength='MODERATE', assessedTranscripts=['NM_000001.1', 'ENST000001.1'],
                          sourceBindings=[dict(self.source, role='PRIMARY_EVIDENCE', reviewId='TEST_ONLY', mediaType='text/plain')])
        self.policy = dict(scientificSources=[self.source], spliceScope=dict(status='REVIEWED_FOR_PVS1_SPLICE', reviewId='TEST_ONLY',
            basis='Invented policy contract', sourceIds=['TEST_ONLY'], maximumStrength='STRONG', evidenceKinds=['OBSERVED_RNA'], eventTypes=['EXON_SKIPPING']))
        self.model = dict(exons=[])

    def issue(self):
        self.policy['spliceScope']['initiationScope'] = self.scope
        return scope_issue(self.policy, self.model)

    def test_optional_scope_is_additive_and_not_conditioned_on_an_inframe_rule(self):
        self.assertIsNone(scope_issue(self.policy, self.model))
        self.assertIsNone(self.issue())
        self.scope['maximumStrength'] = 'SUPPORTING'
        self.assertIsNone(self.issue())

    def test_scope_method_and_strength_are_not_declared_by_arbitrary_flags(self):
        for key, value in [('status', 'READY'), ('method', 'DNA_ONLY'), ('methodVersion', '999'), ('maximumStrength', 'STRONG'),
                           ('reviewId', ''), ('basis', ''), ('sourceIds', ['unbound']), ('unknownClaim', True)]:
            with self.subTest(key=key):
                former = copy.deepcopy(self.scope)
                self.scope[key] = value
                self.assertEqual('RNA_INITIATION_POLICY_NOT_REVIEWED', self.issue())
                self.scope = former

    def test_transcript_versions_and_unique_complete_declared_set_are_explicit(self):
        for value in [None, [], ['NM_000001'], ['NM_000001.0'], ['NM_000001.1']*2, [True], ['NM_000001.1']*1001]:
            with self.subTest(value=str(value)[:50]):
                self.scope['assessedTranscripts'] = value
                self.assertEqual('RNA_INITIATION_TRANSCRIPT_SCOPE_INVALID', self.issue())

    def test_binding_role_original_metadata_and_policy_source_must_match(self):
        for key, value in [('role', 'MODEL_EXTRACTION'), ('sha256', '2'*64), ('url', 'https://example.test/other'),
                           ('version', 'other'), ('sourceId', 'other'), ('reviewId', ''), ('mediaType', 'application/pdf'), ('unknown', True)]:
            with self.subTest(key=key):
                former = copy.deepcopy(self.scope['sourceBindings'])
                self.scope['sourceBindings'][0][key] = value
                self.assertEqual('RNA_INITIATION_SOURCE_BINDINGS_INVALID', self.issue())
                self.scope['sourceBindings'] = former
        self.scope['sourceBindings'][0].update(role='RESTART_REVIEW', mediaType='text/plain')
        self.assertEqual('RNA_INITIATION_SOURCE_BINDINGS_INVALID', self.issue())
        self.scope['sourceBindings'][0]['mediaType'] = 'application/json'
        self.assertIsNone(self.issue())

    def test_binding_arrays_and_enum_types_fail_with_reasons_not_type_errors(self):
        original = copy.deepcopy(self.scope['sourceBindings'])
        for value in [None, {}, [], original*2, original*17, [None], [True]]:
            with self.subTest(value=str(value)[:50]):
                self.scope['sourceBindings'] = value
                self.assertEqual('RNA_INITIATION_SOURCE_BINDINGS_INVALID', self.issue())
        for key in ['role', 'mediaType', 'sha256', 'sourceId', 'reviewId']:
            for value in [[], {}, True, 1, None]:
                self.scope['sourceBindings'] = copy.deepcopy(original)
                self.scope['sourceBindings'][0][key] = value
                with self.subTest(key=key, value=value):
                    self.assertEqual('RNA_INITIATION_SOURCE_BINDINGS_INVALID', self.issue())


if __name__ == '__main__':
    unittest.main()
