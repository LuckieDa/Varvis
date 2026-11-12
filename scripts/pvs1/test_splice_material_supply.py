"""Pinned toy outcomes exercise preparation-to-runtime transport, not scientific adoption."""
import copy
import unittest
import test_assemble_materials as material_tests
import test_assemble_runtime_bundle as runtime_tests
from assemble_materials import historical_materials, content_hash
from assemble_runtime_bundle import assemble


class SpliceMaterialSupplyTest(unittest.TestCase):
    def setUp(self):
        self.files = material_tests.AssemblyTest()
        self.files.setUp()
        self.addCleanup(self.files.doCleanups)
        self.base = runtime_tests.RuntimeAssemblyTest()
        self.base.setUp()
        self.policy = copy.deepcopy(self.base.policy)
        self.policy['schemaVersion'] = 'pvs1-truncating-nmd-policy-v4'
        source = self.files.write('source.txt', b'TEST_ONLY original splice assay and policy source')
        self.source = {'sourceId': 'TEST_ONLY', 'version': '1', 'url': 'https://example.org/test-only',
                       'sha256': source['sha256'], 'relativePath': 'source.txt'}
        self.policy['scientificSources'] = [self.source]
        self.policy['spliceScope'] = {'status': 'REVIEWED_FOR_PVS1_SPLICE', 'reviewId': 'TEST_ONLY_SPLICE',
                                     'basis': 'Toy review; no real scientific approval', 'sourceIds': ['TEST_ONLY'],
                                     'maximumStrength': 'STRONG', 'evidenceKinds': ['OBSERVED_RNA'],
                                     'eventTypes': ['EXON_SKIPPING']}
        self.model = copy.deepcopy(self.base.model)
        self.outcome = {'schemaVersion': 'pvs1-splice-outcome-v1', 'outcomeId': 'TEST_ONLY_OUTCOME',
                        'evidenceKind': 'OBSERVED_RNA', 'hgncId': 'HGNC:123', 'mondoId': 'MONDO:0000001',
                        'moi': 'AUTOSOMAL_DOMINANT', 'transcript': 'ENST000001.1',
                        'transcriptModelSha256': content_hash(self.model),
                        'allele': {'assembly': 'GRCh38', 'contig': '1', 'position': 100, 'reference': 'G', 'alternate': 'A'},
                        'review': {'status': 'REVIEWED_FOR_PVS1_SPLICE', 'reviewId': 'TEST_ONLY_RNA',
                                   'basis': 'TEST_ONLY full review text\nPreserve this restriction.', 'sourceIds': ['TEST_ONLY']},
                        'assessment': {'status': 'QUALIFIED_RNA_ASSAY', 'basis': 'TEST_ONLY original interpretation'},
                        'event': {'type': 'EXON_SKIPPING', 'exonOrdinal': 2, 'deleteStartCds': 13, 'deleteEndCds': 25},
                        'referenceWindow': {'start': 100, 'sequence': 'GT'}, 'sourceRecords': [copy.deepcopy(self.source)]}
        self.doc = {'schemaVersion': 'pvs1-truncating-nmd-bundle-candidate-v1', 'policies': [self.policy],
                    'catalog': self.base.material['catalog'], 'activation': {'status': 'PENDING'},
                    'runtimeResourceBinding': {'status': 'PENDING'}, 'sourceReviewBindings': []}
        self.doc['catalogSha256'] = content_hash(self.doc['catalog'])
        self.files.write('model.json', self.model)
        self.doc['transcriptModelResources'] = [{'canonicalSha256': content_hash(self.model), 'relativePath': 'model.json'}]
        self.set_outcomes([self.outcome])

    def set_outcomes(self, outcomes):
        self.doc['spliceOutcomeResources'] = []
        for i, outcome in enumerate(outcomes):
            name = f'outcome-{i}.json'
            ref = self.files.write(name, outcome)
            self.doc['spliceOutcomeResources'].append({'relativePath': name, 'sha256': ref['sha256'],
                                                       'canonicalSha256': content_hash(outcome)})

    def materials(self, **bindings):
        ref = self.files.write('candidate.json', self.doc)
        return historical_materials({'candidates': [{**ref, 'resourceRoot': str(self.files.root), **bindings}]})

    def assembled(self):
        materials = self.materials()
        result = assemble(self.base.config, self.base.facts, 'a' * 64, materials, self.base.profile)
        return materials, result

    def test_complete_original_is_delivered_unchanged_without_new_review(self):
        before = copy.deepcopy(self.doc)
        materials, (bundle, use, audit) = self.assembled()
        self.assertEqual(before, self.doc)
        self.assertEqual(self.policy, bundle['policies'][0])
        self.assertEqual([self.outcome], bundle['materials']['pvs1SpliceOutcomes'])
        self.assertEqual(1, audit['boundPolicies'])
        self.assertFalse(audit['scientificPolicyChanges'])
        self.assertEqual('CORE_VALIDATION_REQUIRED', audit['policies'][0]['spliceMaterialStatus'])
        self.assertEqual(['SYNTHETIC'], use['entries'][0]['allowedDataClasses'])
        self.assertFalse(materials[0]['runtimeEligible'])

    def test_missing_raw_bytes_or_changed_digest_never_produces_executable_adoption(self):
        for change in ['missing', 'changed', 'canonical']:
            with self.subTest(change=change):
                self.set_outcomes([self.outcome])
                file = self.files.root / 'outcome-0.json'
                if change == 'missing': file.unlink()
                elif change == 'changed': file.write_bytes(b'{}')
                else: self.doc['spliceOutcomeResources'][0]['canonicalSha256'] = '0' * 64
                _, (bundle, use, audit) = self.assembled()
                self.assertEqual([], use['entries'])
                self.assertIn('SPLICE_RESOURCE_BYTES_NOT_ALL_VERIFIED', audit['policies'][0]['reasonCodes'])

    def test_source_files_for_outcome_only_are_also_verified(self):
        ref = self.files.write('rna.txt', b'TEST_ONLY RNA observation')
        self.outcome['sourceRecords'].append({'sourceId': 'TEST_RNA', 'version': '1', 'url': 'https://example.org/rna',
                                             'relativePath': 'rna.txt', 'sha256': ref['sha256']})
        self.set_outcomes([self.outcome])
        self.assertEqual(1, self.assembled()[1][2]['boundPolicies'])
        (self.files.root / 'rna.txt').write_bytes(b'changed')
        self.assertIn('SPLICE_SOURCE_BYTES_REQUIRED', self.assembled()[1][2]['policies'][0]['reasonCodes'])

    def test_relocated_pinned_files_preserve_original_policy_outcome_and_review(self):
        review = self.files.write('review.json', {'basis': 'TEST_ONLY'})
        self.doc['sourceReviewBindings'] = [{'file': 'review.json', 'sha256': review['sha256']}]
        original, = self.materials()
        root = self.files.root / 'registered'
        root.mkdir()
        missing_path = {'root': str(root), 'pathsBySha256': {review['sha256']: 'missing.json'}}
        missing, = self.materials(fileLocations=missing_path)
        self.assertEqual('NOT_LOCATED', missing['sourceReviewChecks'][0]['status'])
        paths = {}
        for name, digest in [('source.txt', self.source['sha256']), ('review.json', review['sha256']),
                             ('outcome-0.json', self.doc['spliceOutcomeResources'][0]['sha256'])]:
            paths[digest] = 'pinned-' + name
            (self.files.root / name).rename(root / paths[digest])

        locations = {'root': str(root), 'pathsBySha256': paths}
        relocated, = self.materials(fileLocations=locations)
        self.assertEqual(original, relocated)

        (root / paths[review['sha256']]).write_bytes(b'changed')
        invalid, = self.materials(fileLocations=locations)
        self.assertEqual('INPUT_SHA_MISMATCH', invalid['sourceReviewChecks'][0]['reasonCode'])
        paths[review['sha256']] = '../review.json'
        with self.assertRaisesRegex(ValueError, 'RESOURCE_PATH_INVALID'):
            self.materials(fileLocations=locations)

    def test_source_identity_collision_is_not_hidden_by_matching_bytes(self):
        self.outcome['sourceRecords'][0]['version'] = 'different'
        self.set_outcomes([self.outcome])
        self.assertIn('SPLICE_SOURCE_IDENTITY_CONFLICT', self.assembled()[1][2]['policies'][0]['reasonCodes'])

    def test_material_for_other_exact_context_is_not_borrowed(self):
        other = copy.deepcopy(self.outcome)
        other['mondoId'] = 'MONDO:0000002'
        self.set_outcomes([other])
        _, (bundle, _, audit) = self.assembled()
        self.assertEqual([], bundle['materials']['pvs1SpliceOutcomes'])
        self.assertEqual('MATERIAL_REQUIRED_FOR_SPLICE_EVALUATION', audit['policies'][0]['spliceMaterialStatus'])

    def test_wrong_model_for_same_scope_blocks_instead_of_selecting_another_model(self):
        self.outcome['transcriptModelSha256'] = '0' * 64
        self.set_outcomes([self.outcome])
        self.assertIn('SPLICE_MODEL_BINDING_MISMATCH', self.assembled()[1][2]['policies'][0]['reasonCodes'])

    def test_unreviewed_outcome_is_retained_without_promoting_or_dropping_competing_outcome(self):
        other = copy.deepcopy(self.outcome)
        other['outcomeId'] = 'TEST_ONLY_COMPETING'
        other['review']['status'] = 'UNREVIEWED'
        other['event']['deleteEndCds'] = 24
        self.set_outcomes([self.outcome, other])
        _, (bundle, _, audit) = self.assembled()
        self.assertEqual([self.outcome, other], bundle['materials']['pvs1SpliceOutcomes'])
        self.assertEqual('UNREVIEWED', bundle['materials']['pvs1SpliceOutcomes'][1]['review']['status'])
        self.assertEqual('CORE_VALIDATION_REQUIRED', audit['policies'][0]['spliceMaterialStatus'])

    def test_missing_material_does_not_disable_valid_nonsplice_policy_use(self):
        self.doc['spliceOutcomeResources'] = []
        _, (bundle, use, audit) = self.assembled()
        self.assertEqual(1, len(use['entries']))
        self.assertEqual([], bundle['materials']['pvs1SpliceOutcomes'])
        self.assertEqual('MATERIAL_REQUIRED_FOR_SPLICE_EVALUATION', audit['policies'][0]['spliceMaterialStatus'])

    def test_v4_domain_review_and_optional_escape_scope_cannot_inherit_outer_review(self):
        self.policy['spliceScope']['status'] = 'UNREVIEWED'
        self.assertIn('POLICY_SPLICE_DOMAIN_REVIEW_INCOMPLETE', self.assembled()[1][2]['policies'][0]['reasonCodes'])
        self.policy['spliceScope']['status'] = 'REVIEWED_FOR_PVS1_SPLICE'
        self.policy['escapeScope'] = {'status': 'UNREVIEWED', 'reviewId': ''}
        self.assertIn('POLICY_ESCAPE_DOMAIN_REVIEW_INCOMPLETE', self.assembled()[1][2]['policies'][0]['reasonCodes'])

    def test_unknown_fields_paths_duplicates_and_oversized_batch_fail_explicitly(self):
        original = copy.deepcopy(self.doc['spliceOutcomeResources'])
        for mutate in ['extra', 'escape', 'duplicate', 'limit']:
            with self.subTest(mutate=mutate):
                self.doc['spliceOutcomeResources'] = copy.deepcopy(original)
                if mutate == 'extra': self.doc['spliceOutcomeResources'][0]['allowMissing'] = True
                elif mutate == 'escape': self.doc['spliceOutcomeResources'][0]['relativePath'] = '../outcome-0.json'
                elif mutate == 'duplicate': self.doc['spliceOutcomeResources'] *= 2
                else: self.doc['spliceOutcomeResources'] *= 1025
                with self.assertRaises(ValueError): self.materials()


if __name__ == '__main__': unittest.main()
