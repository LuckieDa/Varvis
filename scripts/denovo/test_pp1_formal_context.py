"""Pinned synthetic catalog exercises normal select formal-method admission."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from model_literature_contract import canonical, digest
from pp1_context import FORMAL_METHOD, VERSION, select
from test_pp1_formal_policy import model
from test_pp1_segregation import context, selection


class FormalContext(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        content = b'SYNTHETIC independently adopted formal policy and explicit parameters.'
        (self.root / 'source.txt').write_bytes(content)
        self.row = deepcopy(context()['policy'])
        self.row.update(method=FORMAL_METHOD, penetrance='EXPLICIT_FORMAL_MODEL',
                        phenocopy='EXPLICIT_FORMAL_MODEL', formalModel=model(),
                        validFrom='2026-01-01', validThrough='2027-01-01',
                        sources={'S': {'path': 'source.txt', 'sha256': digest(content),
                                       'quote': 'SYNTHETIC independently adopted formal policy', 'locator': 'line1'}})
        decisions = ('method', 'geneDiseaseValidity', 'expertPolicyStatus', 'affectedTerms',
                     'penetrance', 'phenocopy', 'locusModel', 'candidateAssessmentMethods',
                     'formalModel', 'formalModel.ascertainment', 'formalModel.pointBase')
        self.row['decisionSources'] = {field: ['S'] for field in decisions}
        self.write()

    def write(self):
        catalog = canonical({'schemaVersion': VERSION, 'entries': [self.row]})
        (self.root / 'catalog.json').write_bytes(catalog)
        adoption = canonical({'catalogSha256': digest(catalog), 'status': 'ACCEPTED', 'scope': 'SYNTHETIC_TEST'})
        (self.root / 'adoption.json').write_bytes(adoption)
        self.configuration = {'catalog': {'path': 'catalog.json', 'sha256': digest(catalog)},
                              'adoption': {'path': 'adoption.json', 'sha256': digest(adoption)}}

    def selected(self, inheritance='AD'):
        self.write()
        return select(self.root, self.configuration, selection(inheritance), '2026-11-03')

    def test_normal_select_qualifies_formal_method_with_pinned_sources(self):
        result = self.selected()
        self.assertEqual('QUALIFIED', result['status'])
        self.assertEqual(FORMAL_METHOD, result['policy']['method'])
        self.assertEqual([], result['needs'])
        self.assertEqual('SYNTHETIC_TEST', result['dataClass'])
        self.assertEqual('S', result['sourceRefs'][0]['sourceId'])
        self.assertEqual(self.row['formalModel'], result['known']['formalModel'])

    def test_missing_dosage_and_calibration_sources_remain_explained(self):
        del self.row['formalModel']['phenotypeModel']['probabilities']['2']
        del self.row['decisionSources']['formalModel.pointBase']
        result = self.selected()
        self.assertNotEqual('QUALIFIED', result['status'])
        self.assertEqual(self.row, result['policy'])
        self.assertEqual(self.row['scope'], result['needs'][0]['scope'])
        dosage = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_DOSAGE_PROBABILITIES_REQUIRED')
        self.assertEqual('S', dosage['sourceRefs'][0]['sourceId'])
        self.assertEqual('BLOCKS_SCORING', dosage['blockingEffect'])
        self.assertTrue(dosage['knownFacts'])
        self.assertTrue(dosage['expected'])
        self.assertEqual(result['details'], result['needs'])

    def test_each_formal_decision_requires_an_independent_source(self):
        for field in ('formalModel', 'formalModel.ascertainment', 'formalModel.pointBase', 'penetrance', 'phenocopy'):
            previous = self.row['decisionSources'].pop(field)
            result = self.selected()
            with self.subTest(field=field):
                self.assertNotEqual('QUALIFIED', result['status'])
                self.assertTrue(any(n['field'] == field for n in result['needs']))
            self.row['decisionSources'][field] = previous

    def test_dangling_and_untyped_decision_sources_are_structured(self):
        for references in (['UNKNOWN'], 'S', [], None, [None]):
            self.row['decisionSources']['formalModel'] = references
            self.assertNotEqual('QUALIFIED', self.selected()['status'])

    def test_legacy_heuristic_parameters_do_not_enter_formal_model(self):
        for field, value in (('penetrance', 'COMPLETE'), ('phenocopy', 'HEURISTIC_APPLICABLE'),
                             ('expertPolicyStatus', 'APPLICABLE_UNRESOLVED'), ('formalModel', None)):
            old = self.row[field]
            self.row[field] = value
            result = self.selected()
            with self.subTest(field=field):
                self.assertNotEqual('QUALIFIED', result['status'])
                self.assertEqual(FORMAL_METHOD, result['policy']['method'])
                self.assertTrue(result['needs'])
            self.row[field] = old

    def test_ar_admitted_but_formal_xlr_is_not_legacy_xlr(self):
        self.row['scope']['inheritance'] = 'AR'
        self.assertEqual('QUALIFIED', self.selected('AR')['status'])
        self.row['scope']['inheritance'] = 'XLR'
        result = self.selected('XLR')
        self.assertEqual('METHOD_UNSUPPORTED', result['status'])
        self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_INHERITANCE_UNSUPPORTED' for n in result['needs']))

    def test_missing_sources_keep_parameter_and_scope_for_review(self):
        del self.row['sources']
        result = self.selected()
        self.assertNotEqual('QUALIFIED', result['status'])
        self.assertEqual(self.row['formalModel'], result['known']['formalModel'])
        self.assertEqual([], result['sourceRefs'])
        self.assertEqual(self.row['scope'], result['policy']['scope'])

    def test_incomplete_source_records_are_needs_but_changed_bytes_still_rejected(self):
        del self.row['sources']['S']['quote']
        result = self.selected()
        self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_SOURCE_REQUIRED' for n in result['needs']))
        self.row['sources']['S']['quote'] = 'SYNTHETIC independently adopted formal policy'
        self.write()
        (self.root / 'source.txt').write_bytes(b'changed bytes')
        with self.assertRaisesRegex(ValueError, 'CONTEXT_PIN_MISMATCH'):
            select(self.root, self.configuration, selection(), '2026-11-03')


if __name__ == '__main__':
    unittest.main()
