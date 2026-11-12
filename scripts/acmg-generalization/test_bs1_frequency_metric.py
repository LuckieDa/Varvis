import copy
import unittest
from bs1_expert_batch import compile_reviews, content_hash
import test_bs1_expert_batch as expert_tests


class FrequencyMetricTest(unittest.TestCase):
    def fixture(self):
        f = expert_tests.ExpertBatchTest(); a, c = f.reviewed_bound('≥0.0035', '0.0035', 'GTE')
        text = 'Synthetic: highest population minor allele frequency (MAF) ≥0.0035 in populations with >2000 alleles.'
        c['strengthDescriptor'][0]['text'] = text
        a.update(translation='REVIEWED_SINGLE_TARGET_AF_BOUND_V1', clauseText=text, eligibleAnExpression='>2000',
                 statisticReview='Synthetic target allele interpretation; not clinical adoption',
                 populationStatistic=dict(metricId='gnomad.exomes.eligible_ancestry_max_af', unit='fraction', confidence='POINT_ESTIMATE',
                    ancestries=['nfe', 'afr'], sourceTerm='MAF', interpretation='TARGET_ALT_AF_NOT_ABOVE_HALF',
                    interpretationBasis='Synthetic declaration; never complement target frequency', eligibleAn={'operator':'GT','decimal':'2000'}))
        a['entitySha256'] = content_hash(f.entity)
        return f, a

    def test_explicit_af_preserves_complete_source_scope_and_strict_an(self):
        f, a = self.fixture(); before = copy.deepcopy(f.review)
        records = compile_reviews(f.review, f.specs, f.sources)
        self.assertEqual(2, len(records))
        for r in records:
            p = r['expertPolicy']
            self.assertEqual(a['populationStatistic'], p['statistic'])
            self.assertEqual('AF', p['tiers'][0]['conditions'][0]['fact'])
            self.assertEqual('GTE', p['tiers'][0]['conditions'][0]['operator'])
            self.assertEqual(a['clauseText'], p['tiers'][0]['conditions'][0]['originalText'])
            self.assertNotIn('qualification', r)  # runtime still requires the explicit adoption checks
        self.assertEqual(before, f.review)

    def test_mismatched_an_expression_or_comparison_is_rejected(self):
        for operator, decimal, expression in [('GTE','2000','>2000'),('GT','200','>200'),('GT','2000','>20000')]:
            f,a = self.fixture();a['populationStatistic']['eligibleAn']={'operator':operator,'decimal':decimal};a['eligibleAnExpression']=expression
            with self.assertRaisesRegex(ValueError, 'AN_EXPRESSION_UNBOUND'):compile_reviews(f.review,f.specs,f.sources)

    def test_source_term_and_interpretation_are_not_inferred(self):
        for term, meaning in [('AF','TARGET_ALT_AF'),('MAF','TARGET_ALT_AF'),('MAF','ONE_MINUS_AF')]:
            f,a=self.fixture();a['populationStatistic'].update(sourceTerm=term,interpretation=meaning)
            with self.assertRaisesRegex(ValueError, 'INTERPRETATION_REQUIRED'):compile_reviews(f.review,f.specs,f.sources)

    def test_unknown_population_or_condition_fails_instead_of_being_dropped(self):
        f,a=self.fixture();a['populationStatistic']['ancestries'].append('asj')
        with self.assertRaisesRegex(ValueError,'ANCESTRY_SCOPE'):compile_reviews(f.review,f.specs,f.sources)
        f,a=self.fixture();a['populationStatistic']['minimumAge']=40
        with self.assertRaisesRegex(ValueError,'STATISTIC_FIELDS'):compile_reviews(f.review,f.specs,f.sources)

    def test_faf_semantics_and_changed_original_are_not_accepted(self):
        f,a=self.fixture();a['populationStatistic']['confidence']='95_PERCENT_LOWER_BOUND'
        with self.assertRaisesRegex(ValueError,'STATISTIC_INVALID'):compile_reviews(f.review,f.specs,f.sources)
        f,a=self.fixture();f.entity['entContent']['extraCaveat']='Cannot use in population X'
        with self.assertRaisesRegex(ValueError,'SOURCE_CHANGED'):compile_reviews(f.review,f.specs,f.sources)
