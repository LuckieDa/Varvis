import copy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from start_loss_functional_reading import read
from start_loss_functional_library import local_reading


class FunctionalReadingTest(unittest.TestCase):
    def source(self, texts):
        units = [{'unitId': '/body/' + str(i), 'kind': 'BODY_PARAGRAPH', 'sections': ['Results'],
                  'sourceSha256': 'a' * 64, 'text': text} for i, text in enumerate(texts)]
        return {'pmcid': 'PMC123', 'bundleSha256': 'b' * 64, 'main': {'sourceSha256': 'a' * 64},
                'units': units, 'attachments': [], 'coverage': {'status': 'SUPPLEMENT_UNAVAILABLE_OR_UNPARSED'}}

    def test_expression_binding_and_therapeutic_rescue_are_not_endogenous_function(self):
        source = self.source(['GENE1 isoform E2 protein expression was detected.',
                              'GENE1 E2 domain constructs retained binding in vitro.',
                              'Overexpression of GENE1 E2 partially rescued the phenotype in mice.'])
        result = read(source, 'GENE1', ['E2'])
        self.assertFalse(result['runtimeEligible'])
        self.assertFalse(result['functionalRescueEstablished'])
        self.assertFalse(result['negativeFunctionalCoverageGranted'])
        self.assertEqual(3, result['reviewedUnitCount'])
        self.assertTrue(all(not x['functionalRescueEstablished'] for x in result['findings']))
        self.assertEqual(source['coverage'], result['supplementCoverage'])
        field = next(r for r in result['requirementsComparison'] if r['field'] == 'constructExtent')
        self.assertEqual(['/body/1'], field['availablePassages'])
        self.assertEqual('SOURCE_MEANING_AND_APPLICABILITY_NOT_YET_QUALIFIED', field['gap'])

    def test_negation_and_counterevidence_are_preserved_without_boolean_conversion(self):
        text = 'GENE1 isoform E2 did not rescue the phenotype, although binding activity was normal.'
        result = read(self.source([text]), 'GENE1', ['E2'])
        self.assertEqual(text, result['findings'][0]['citation']['quote'])
        self.assertIn('limitationsAndCounterevidence', [f['field'] for f in result['findings'][0]['fields']])
        self.assertFalse(result['functionalRescueEstablished'])

    def test_budget_retains_unread_units_and_does_not_invent_negative_coverage(self):
        source = self.source(['GENE1 E2 isoform activity in neurons.', 'GENE1 E2 isoform rescue in mice.'])
        before = copy.deepcopy(source)
        result = read(source, 'GENE1', ['E2'], maximum_units=1)
        self.assertEqual(1, len(result['pendingUnits']))
        self.assertEqual(2, result['matchingUnitCount'])
        self.assertFalse(result['negativeFunctionalCoverageGranted'])
        self.assertEqual(before, source)

    def test_background_and_partial_subject_names_do_not_become_primary_observations(self):
        source = self.source(['GENE1 isoforms have previously been reported to rescue activity.',
                              'GENE10 E20 isoforms have normal activity.'])
        source['units'][0]['sections'] = ['Introduction']
        result = read(source, 'GENE1', ['E2'])
        self.assertEqual(1, result['matchingUnitCount'])
        self.assertEqual('CITED_OR_BACKGROUND', result['findings'][0]['sourceContext'])

    def test_missing_unread_or_unknown_information_is_not_absence(self):
        result = read(self.source(['GENE1 E2 isoform expression.']), 'GENE1', ['E2'])
        field = next(r for r in result['requirementsComparison'] if r['field'] == 'controlsAndReplicates')
        self.assertEqual('NOT_LOCATED_IN_REVIEWED_UNITS', field['gap'])
        self.assertFalse(field['absenceEstablished'])
        self.assertEqual(0, len(field['availablePassages']))
        large = read(self.source(['GENE1 isoform E2 ' + 'a' * 16000]), 'GENE1', ['E2'])
        self.assertEqual(0, large['reviewedUnitCount'])
        self.assertEqual('SOURCE_UNIT_REQUIRES_PARTITION', large['pendingUnits'][0]['reasonCode'])

    def test_duplicate_units_are_rejected(self):
        source = self.source(['GENE1 E2 isoform activity.'])
        source['units'].append(copy.deepcopy(source['units'][0]))
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_SOURCE_UNIT'):
            read(source, 'GENE1', ['E2'])

    def test_local_reading_replays_pinned_quotes_and_recomputes_on_tampering(self):
        source = self.source(['GENE1 isoform expression was detected in mice.'])
        with TemporaryDirectory() as folder:
            first, reused = local_reading(folder, source, 'GENE1')
            self.assertFalse(reused)
            with patch('start_loss_functional_library.read', side_effect=AssertionError('repeat extraction')):
                second, reused = local_reading(folder, source, 'GENE1')
            self.assertTrue(reused)
            self.assertEqual(first, second)
            record, = Path(folder).rglob('*.json')
            record.write_bytes(record.read_bytes().replace(b'mice', b'cells'))
            third, reused = local_reading(folder, source, 'GENE1')
            self.assertFalse(reused)
            self.assertEqual(first, third)


if __name__ == '__main__':
    unittest.main()
