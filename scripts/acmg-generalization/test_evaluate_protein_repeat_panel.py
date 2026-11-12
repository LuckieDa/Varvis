import json
import unittest
import evaluate_protein_repeat_panel as e
from protein_repeat_method import sha


class PanelEvaluationTest(unittest.TestCase):
    def test_union_deduplicates_and_keeps_inter_unit_gap(self):
        units = [{'start': 1, 'end': 4}, {'start': 3, 'end': 5}, {'start': 10, 'end': 12}]
        self.assertEqual(8, e.covered_residues(1, 12, units))
        self.assertEqual(0, e.covered_residues(6, 9, units))
        self.assertEqual(2, e.covered_residues(4, 6, units))

    def example(self):
        panel = json.dumps({'cases': [{'id': 'X', 'class': 'REAL_NATIVE_ANNOTATION_PANEL',
            'sequence': 'ACD', 'sequenceSha256': sha(b'ACD'), 'knownRepeatUnits': []}]}).encode()
        run = dict(panelSha256=sha(panel), toolIntegrityAfter='UNCHANGED', elapsedSeconds=1,
            results=[dict(id='X', sequenceSha256=sha(b'ACD'), status='NONZERO_EXIT')])
        return panel, run

    def test_failure_never_counted_as_no_hit(self):
        panel, run = self.example()
        result = e.evaluate(panel, run)
        self.assertEqual(0, result['completed'])
        self.assertEqual(1, result['failedOrNotAttempted'])
        self.assertNotIn('hitResidues', result['rows'][0])

    def test_empty_annotation_not_negative_truth(self):
        panel, run = self.example()
        run['results'][0].update(status='COMPLETED', facts=dict(groups=[],
            algorithmState='NO_REPEAT_DETECTED', clinicalAdoption=False,
            nonRepeatQualified=False, noKnownFunctionQualified=False))
        result = e.evaluate(panel, run)
        self.assertEqual('NOT_AVAILABLE', result['rows'][0]['nativeNegativeTruth'])
        self.assertFalse(result['nonRepeatQualified'])

    def test_changed_case_or_method_identity_rejects(self):
        panel, run = self.example()
        with self.assertRaises(ValueError):
            e.evaluate(panel + b' ', run)
        run['toolIntegrityAfter'] = 'CHANGED'
        with self.assertRaises(ValueError):
            e.evaluate(panel, run)

    def test_missing_duplicate_or_foreign_result_rejected(self):
        panel, run = self.example()
        run['results'] *= 2
        with self.assertRaises(ValueError):
            e.evaluate(panel, run)
        run['results'] = []
        with self.assertRaises(ValueError):
            e.evaluate(panel, run)


if __name__ == '__main__':
    unittest.main()
