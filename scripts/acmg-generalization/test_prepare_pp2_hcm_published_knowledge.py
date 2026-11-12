import copy
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from prepare_pp2_hcm_published_knowledge import derive, prepare, TYPE
from prepare_published_criterion_knowledge import criterion_scopes, content_hash


class HcmPreparationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raw = os.environ.get('T05_HCM_CSPEC_RAW')
        catalog = os.environ.get('T05_HCM_CSPEC_CATALOG')
        if not raw or not catalog:
            raise unittest.SkipTest('Explicit existing pinned CSpec original/catalog paths required')
        cls.raw = Path(raw); cls.catalog = Path(catalog)
        cls.assert_raw = hashlib.sha256(cls.raw.read_bytes()).hexdigest()
        if cls.assert_raw != '4dc9861b487b1ff09e348aa80979b67bd410184ded2471597c0ae30fbee3937d':
            raise AssertionError('Wrong GN100 original')
        cls.original = json.loads(cls.raw.read_bytes())['data'][4]
        cls.cp = '/ld/CriteriaCode/6'

    def association(self, original=None):
        return next(s for s in criterion_scopes(original or self.original, 'ACMG_AMP_2015:PP2')
                    if s['scope']['hgncId'] == 'HGNC:12010')

    def config(self):
        return {'catalog': {'file': str(self.catalog), 'sha256': hashlib.sha256(self.catalog.read_bytes()).hexdigest()},
                'captureDirectory': str(self.raw.parent), 'policyId': 'PROJECT_PUBLISHED_HCM_PP2', 'policyVersion': '1.0.0',
                'specificationKeys': ['GN100@1.0']}

    def test_actual_source_scope_and_no_variant_or_frequency_clearance(self):
        before = copy.deepcopy(self.original)
        method = derive(self.original, self.cp, self.association())
        self.assertEqual(TYPE, method['type']); self.assertEqual('SUPPORTING', method['appliedStrength'])
        self.assertEqual({'geneSymbol': 'TPM1', 'hgncId': 'HGNC:12010', 'mondoId': 'MONDO:0005045',
            'moi': 'AUTOSOMAL_DOMINANT', 'preferredTranscript': 'NM_001018005.2',
            'unversionedSourceEnsembl': 'ENST00000403994'}, method['sourceScope'])
        self.assertTrue(method['requiresQualifiedVariantOtherPhenotypeReview'])
        for name in ('AF', 'threshold', 'requiresPm2', 'otherPhenotypeClear', 'review', 'clinicalApproval'):
            self.assertNotIn(name, method)
        self.assertEqual(before, self.original)

    def test_existing_catalog_compiles_once_without_approval_inheritance_or_source_changes(self):
        before = self.raw.read_bytes(); knowledge, audit, plan = prepare(self.config())
        rows = [r for r in knowledge['records'] if r['specificationKey'] == 'GN100@1.0']
        self.assertEqual(1, len(rows)); self.assertEqual('EXECUTABLE', rows[0]['status'])
        self.assertEqual('NM_001018005.2', rows[0]['scope']['transcript'])
        self.assertEqual(self.original, next(s['original'] for s in knowledge['specifications'] if s['specificationKey'] == 'GN100@1.0'))
        self.assertFalse(knowledge['preparation']['priorBa1ApprovalInherited'])
        self.assertFalse(audit['variantPhenotypeClearanceCreated']); self.assertFalse(audit['populationThresholdCreated'])
        self.assertFalse(knowledge['clinicalValidationCompleted']); self.assertNotIn('review', rows[0])
        self.assertEqual((knowledge, audit, plan), prepare(self.config())); self.assertEqual(before, self.raw.read_bytes())

    def test_unknown_source_conditions_or_scope_never_derive_an_executable_method(self):
        changes = [lambda o: o['entContent'].update(notes='Requires another unimplemented condition.'),
            lambda o: o['ld'].update(File=[{'fileName': 'condition.pdf'}]),
            lambda o: o['ld']['CriteriaCode'][6]['entContent']['strengthDescriptor'][4].update(text='Only apply if PM2 is met.'),
            lambda o: o['ld']['CriteriaCode'][6]['entContent']['strengthDescriptor'][4].update(strength='Moderate'),
            lambda o: o['ld']['CriteriaCode'][6]['entContent'].update(gene=['OTHER']),
            lambda o: o['ld']['RuleSet'][0]['entContent']['genes'][0].update(preferredTranscript='NM_001018005.3'),
            lambda o: o['ld']['CriteriaCode'].append(copy.deepcopy(o['ld']['CriteriaCode'][6])),
            lambda o: o['ld']['CriteriaCode'][7]['entContent'].update(additionalComments='pp2 requires an extra condition.')]
        for change in changes:
            original = copy.deepcopy(self.original); change(original)
            with self.subTest(change=change):
                with self.assertRaises(ValueError): derive(original, self.cp, self.association(original))

    def test_unsupported_conditions_retain_full_source_and_wrong_raw_pin_fails(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get('VARVIS_TEST_ROOT')) as temp:
            root = Path(temp); original = copy.deepcopy(self.original)
            original['entContent']['notes'] = 'TEST ONLY: unknown additional rule, never executable.'
            raw = json.dumps({'data': [original]}).encode(); (root/'raw.json').write_bytes(raw)
            source = {'rawRef': 'raw.json', 'rawSha256': hashlib.sha256(raw).hexdigest(), 'jsonPointer': '/data/0',
                      'sourceUrl': 'https://cspec.genome.network/cspec/ui/svi/doc/GN100?version=1.0'}
            catalog = {'schemaVersion': 'a01-ba1-cspec-snapshot-v1', 'metadata': {'status': 'COMPLETE', 'selectedReleasedCount': 1},
                       'entries': [{'specificationId': 'GN100', 'source': source, 'rawRef': 'raw.json', 'rawSha256': source['rawSha256']}]}
            catalog['sha256'] = content_hash(catalog); catalog_bytes = json.dumps(catalog).encode(); (root/'catalog.json').write_bytes(catalog_bytes)
            config = {**self.config(), 'catalog': {'file': str(root/'catalog.json'), 'sha256': hashlib.sha256(catalog_bytes).hexdigest()}, 'captureDirectory': str(root)}
            knowledge, _, _ = prepare(config)
            self.assertEqual('UNSUPPORTED_CONDITIONS', knowledge['records'][0]['status'])
            self.assertEqual('PP2_HCM_GENERAL_NOTES_UNSUPPORTED', knowledge['records'][0]['reasonCode'])
            self.assertEqual(original, knowledge['specifications'][0]['original'])
            config['catalog']['sha256'] = '0'*64
            with self.assertRaises(ValueError): prepare(config)


if __name__ == '__main__':
    unittest.main()
