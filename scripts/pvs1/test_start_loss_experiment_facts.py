import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from start_loss_functional_reading import read
from start_loss_functional_library import review, canonical, digest
import test_start_loss_functional_reading as fixture


class ExperimentFactsTest(unittest.TestCase):
    def result(self, text):
        return read(fixture.FunctionalReadingTest().source([text]), 'GENE1', [])['experimentalAssessment']

    def test_reports_partial_endpoint_and_exact_decimal_without_whole_function(self):
        text = 'GENE1 isoform E2 was studied. 31.77% of dysregulated genes were partially rescued in mice.'
        result = self.result(text)
        observation = result['observations'][0]
        self.assertEqual('31.77', observation['reportedValues']['percent'])
        self.assertEqual('dysregulated genes', observation['reportedValues']['endpoint'])
        self.assertEqual(text[observation['sourceStart']:observation['sourceEnd']], observation['sourceQuote'])
        self.assertFalse(result['runtimeEligible'])
        self.assertFalse(result['functionalRescueEstablished'])
        self.assertFalse(result['negativeFunctionalCoverageGranted'])

    def test_intervention_and_fragment_retain_limits(self):
        for text, kind, limit in [
            ('GENE1 protein fragments retained activity.', 'FRAGMENT_CONSTRUCT_REPORTED', 'FRAGMENT_ASSAY_NOT_WHOLE_ISOFORM'),
            ('For GENE1 we first deleted exon 2 in cells.', 'EXON_DELETION_INTERVENTION_REPORTED', 'INTERVENTION_EFFECT_NOT_BASELINE_COMPENSATION'),
            ('We overexpressed GENE1 in neurons.', 'OVEREXPRESSION_INTERVENTION_REPORTED', 'INTERVENTION_EFFECT_NOT_BASELINE_COMPENSATION'),
            ('GENE1 protein was ~50-60% higher in mice.', 'PROTEIN_ABUNDANCE_INCREASE_REPORTED', 'ABUNDANCE_NOT_FUNCTIONAL_RESCUE')]:
            with self.subTest(text=text):
                row = self.result(text)['observations'][0]
                self.assertEqual(kind, row['kind'])
                self.assertEqual(limit, row['useLimit'])
                self.assertFalse(row['establishesFunctionalAlternative'])

    def test_negation_conditional_and_invalid_numbers_do_not_create_facts(self):
        for text in ['GENE1 protein fragments were not tested.',
                     'We might use GENE1 protein fragments to assess activity.',
                     'GENE1 protein was 60-50% higher in mice.',
                     *['GENE1 isoform activity: ' + value + '% of genes were partially rescued.'
                       for value in ['101', '-5', '1e2', '−5']]]:
            with self.subTest(text=text):
                self.assertEqual([], self.result(text)['observations'])

    def test_unknown_meaning_is_not_a_negative_and_background_stays_background(self):
        self.assertEqual('EXPERIMENT_SEMANTICS_UNRESOLVED', self.result('GENE1 activity was discussed.')['status'])
        source = fixture.FunctionalReadingTest().source(['GENE1 protein fragments retained activity.'])
        source['units'][0]['sections'] = ['Introduction']
        self.assertEqual('CITED_OR_BACKGROUND', read(source, 'GENE1', [])['experimentalAssessment']['observations'][0]['sourceRole'])


class FunctionalLibraryTest(unittest.TestCase):
    def test_source_is_pinned_failures_isolated_and_absence_never_granted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            index = root / 'evidence/PVS1/index.json'; index.parent.mkdir(parents=True)
            document = {'sourceClass': 'PUBLIC_PAPER', 'pmcid': 'PMC123', 'acquisitionBundles': [{'sha256': 'b' * 64}]}
            raw = canonical({'schemaVersion': 'public-paper-evidence-index-v1', 'criterion': 'PVS1', 'documents': [document]})
            index.write_bytes(raw)
            with patch('start_loss_functional_library.load', side_effect=ValueError('SOURCE_CHANGED')):
                result = review(root, 'GENE1', digest(raw))
            self.assertEqual('SOURCE_CHANGED', result['pendingDocuments'][0]['reasonCode'])
            self.assertFalse(result['negativeFunctionalCoverageGranted'])
            with self.assertRaisesRegex(ValueError, 'INDEX_CHANGED'):
                review(root, 'GENE1', 'c' * 64)

    def test_reading_report_keeps_original_and_does_not_mutate_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); index = root / 'evidence/PVS1/index.json'; index.parent.mkdir(parents=True)
            raw = canonical({'schemaVersion': 'public-paper-evidence-index-v1', 'criterion': 'PVS1', 'documents': [
                {'sourceClass': 'PUBLIC_PAPER', 'pmcid': 'PMC123', 'acquisitionBundles': [{'sha256': 'b' * 64}]}]})
            index.write_bytes(raw)
            source = fixture.FunctionalReadingTest().source(['GENE1 protein fragments retained activity.']); source['raw'] = b'<article/>'
            original = copy.deepcopy(source)
            with patch('start_loss_functional_library.load', return_value=source):
                result = review(root, 'GENE1', digest(raw))
            self.assertEqual(original, source)
            self.assertEqual(1, result['documentCount'])
            self.assertEqual('<article/>', result['originals']['a' * 64]['rawXml'])
            self.assertFalse(result['runtimeEligible'])


if __name__ == '__main__':
    unittest.main()
