import copy
import tempfile
from pathlib import Path
import unittest
import bs1_knowledge as k
import bs1_parameter_supply as supply
from bs1_parameter_text import extract
from test_bs1_parameters import inventory, spec, epi, SOURCE, background
from test_bs1_knowledge import KnowledgeTest


def summarize(rows=None, mappings=None, specs=None, backgrounds=None):
    drafts, facts, _ = inventory(rows=rows, mappings=mappings, specs=specs, backgrounds=backgrounds)
    proposals, text, _ = extract(specs or [], [SOURCE], backgrounds or [background()])
    original = copy.deepcopy([drafts, facts, proposals, text])
    result = supply.summarize(drafts, facts, proposals, text)
    assert original == [drafts, facts, proposals, text]
    return next(iter(result.values()))


class SupplyTest(unittest.TestCase):
    def test_numeric_candidate_and_missing_fields_are_not_adopted(self):
        s = summarize()
        self.assertFalse(s['modelExecutable']); self.assertFalse(s['scientificallyAdopted'])
        self.assertEqual('CANDIDATE_AVAILABLE', s['fields']['prevalence']['status'])
        self.assertEqual('0.00000037', s['fields']['prevalence']['evidence'][0]['valueText'])
        self.assertEqual(SOURCE, s['fields']['prevalence']['evidence'][0]['source'])
        for name in supply.FIELDS[1:]:
            self.assertEqual('SOURCE_GAP', s['fields'][name]['status'])
            self.assertNotIn('selectedValue', s['fields'][name])

    def test_identity_range_invalid_conflict_and_review_remain_distinct(self):
        cases = [({'rows': [], 'mappings': []}, 'IDENTITY_UNRESOLVED'),
                 ({'rows': [epi(qualification='Class only', mean='0.0')]}, 'SOFTWARE_UNSUPPORTED'),
                 ({'rows': [epi(mean='bad')]}, 'INVALID_SOURCE_DATA'),
                 ({'rows': [epi(), epi(mean='0.04', sourceRecordId='2')]}, 'CONFLICT'),
                 ({'rows': [epi(geography='Europe'), epi(geography='Asia', sourceRecordId='2')]}, 'REVIEW_REQUIRED')]
        for args, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(expected, summarize(**args)['fields']['prevalence']['status'])

    def test_text_points_stay_unreviewed_with_original_locator(self):
        s = summarize(specs=[spec()])
        f = s['fields']['penetrance']
        self.assertEqual('REVIEW_REQUIRED', f['status'])
        self.assertEqual(['EXPLICIT_POINT_UNREVIEWED'], f['textStatuses'])
        self.assertIn('CriteriaCode/0', f['evidence'][0]['locator'])
        self.assertIn('FULL_CLAUSE_AND_EXPERT_PRIORITY', f['reasonCodes'])

    def test_text_range_is_not_hidden_as_absent(self):
        f = summarize(specs=[spec('penetrance = 0.5-0.8')])['fields']['penetrance']
        self.assertNotEqual('SOURCE_GAP', f['status'])
        self.assertIn('RANGE_REQUIRES_MODEL_AND_SCOPE_REVIEW', f['reasonCodes'])
        self.assertEqual('', f['evidence'][0]['valueText'])

    def test_conflicting_text_preserved(self):
        f = summarize(specs=[spec('penetrance = 0.5; penetrance = 0.8')])['fields']['penetrance']
        self.assertEqual('CONFLICT', f['status'])

    def test_wrong_disease_does_not_borrow_text(self):
        s = summarize(specs=[spec()], backgrounds=[background(mondoId='MONDO:0000002')])
        self.assertEqual(0, s['fields']['penetrance']['textProposalCount'])

    def test_large_source_sets_have_explicit_omission_count(self):
        f = summarize(rows=[epi(sourceRecordId=str(i)) for i in range(10)])['fields']['prevalence']
        self.assertEqual(10, f['observationCount'])
        self.assertEqual(3, len(f['evidence'])); self.assertEqual(7, f['omittedEvidenceCount'])

    def test_real_compiler_embeds_all_backgrounds_without_executable_records(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            manifest = KnowledgeTest().setup_manifest(root)
            k.compile_manifest(manifest, root/'out')
            bundle = k.read_json((root/'out/bundle.json').read_bytes())
            self.assertEqual(3, len(bundle['backgrounds'])); self.assertEqual([], bundle['records'])
            for row in bundle['backgrounds']:
                self.assertEqual(supply.key(row), supply.key(row['parameterSupply']['scope']))
                self.assertFalse(row['parameterSupply']['modelExecutable'])


if __name__ == '__main__':
    unittest.main()
