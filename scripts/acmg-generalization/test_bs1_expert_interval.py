import copy
import unittest
import test_bs1_expert_batch as fixtures
from bs1_expert_batch import compile_reviews, content_hash


class ExpertIntervalTest(unittest.TestCase):
    def fixture(self, expression='greater than or equal to 0.0005 but less than 0.001', term='MAF'):
        f = fixtures.ExpertBatchTest()
        a, c = f.reviewed_bound()
        text = 'gnomAD ' + term + ' ' + expression + '.'
        c['strengthDescriptor'][0]['text'] = text
        a.update(translation='REVIEWED_TARGET_AF_INTERVAL_V1', clauseText=text,
                 intervalExpression=expression, sourceMetricExpression=term,
                 lowerBound={'operator': 'GTE', 'fraction': '0.0005'},
                 upperBound={'operator': 'LT', 'fraction': '0.001'},
                 sampleSizeBasis='LOCAL_ENGINEERING_MINIMUM',
                 sampleSizeReview='Synthetic: AN>=2000 is local protection, not a quoted source requirement.',
                 statisticReview='Synthetic reviewed ordinary target AF; not FAF.',
                 populationStatistic={'metricId': 'gnomad.exomes.eligible_ancestry_max_af', 'unit': 'fraction',
                     'confidence': 'POINT_ESTIMATE', 'ancestries': ['afr','amr','eas','nfe','sas'],
                     'sourceTerm': term, 'interpretation': 'TARGET_ALT_AF_NOT_ABOVE_HALF' if term == 'MAF' else 'TARGET_ALT_AF',
                     'interpretationBasis': 'Synthetic local exomes scope', 'eligibleAn': {'operator':'GTE','decimal':'2000'}})
        for k in ('thresholdExpression','operator','fraction'):
            a.pop(k, None)
        a['entitySha256'] = content_hash(f.entity)
        return f, a, c

    def test_both_bounds_are_preserved_with_source_and_separate_modes(self):
        f, a, c = self.fixture()
        records = compile_reviews(f.review, f.specs, f.sources)
        self.assertEqual(2, len(records))
        for r in records:
            conditions = r['expertPolicy']['tiers'][0]['conditions']
            self.assertEqual([('AF','GTE','0.0005'),('AF','LT','0.001')],
                             [(x['fact'],x['operator'],x['decimal']) for x in conditions])
            self.assertTrue(all(x['originalText'] == a['clauseText'] for x in conditions))
            self.assertIn('local protection', r['review']['basis'])

    def test_symbol_percent_and_bound_strictness_are_exact(self):
        f,a,c=self.fixture('>0.05% (0.05%) and \\<0.1% (0.1%)')
        a['lowerBound']['operator']='GT'
        r=compile_reviews(f.review,f.specs,f.sources)[0]
        self.assertEqual('GT',r['expertPolicy']['tiers'][0]['conditions'][0]['operator'])
        a['lowerBound']['operator']='GTE'
        with self.assertRaisesRegex(ValueError,'INTERVAL_BOUND_MISMATCH'):compile_reviews(f.review,f.specs,f.sources)

    def test_explicit_source_sample_count_and_long_af_label(self):
        f,a,c=self.fixture('≥0.0005 (0.05%) and \\<0.001 (0.1%)', 'AF')
        text=a['clauseText'].replace(' AF ', ' allele frequency ')+' At least 2,000 observed alleles.'
        c['strengthDescriptor'][0]['text']=text
        a.update(clauseText=text,sourceMetricExpression='allele frequency',sampleSizeBasis='SOURCE_STATED',eligibleAnExpression='At least 2,000 observed alleles')
        a['entitySha256']=content_hash(f.entity)
        self.assertEqual('AF',compile_reviews(f.review,f.specs,f.sources)[0]['expertPolicy']['tiers'][0]['conditions'][1]['fact'])
        a['populationStatistic']['eligibleAn']['operator']='GT'
        with self.assertRaisesRegex(ValueError,'AN_EXPRESSION_UNBOUND'):compile_reviews(f.review,f.specs,f.sources)

    def test_invalid_or_unbound_range_is_rejected(self):
        for fault in ['UPPER_REMOVED','LOWER_CHANGED','PREFIX','AMBIGUOUS','REVERSED','PERCENT','EXTRA_NUMBER','MISSING_REVIEW','SOURCE_CHANGE','SAMPLE_BASIS','FAF']:
            with self.subTest(fault=fault):
                f,a,c=self.fixture()
                if fault=='UPPER_REMOVED': a.pop('upperBound')
                elif fault=='LOWER_CHANGED': a['lowerBound']['fraction']='0.0004'
                elif fault=='PREFIX': a['intervalExpression']=a['intervalExpression'].replace('0.001','0.00')
                elif fault=='AMBIGUOUS': a['intervalExpression']='between 0.0005 and 0.001'
                elif fault=='REVERSED': a['upperBound']['fraction']='0.0001'
                elif fault=='PERCENT': a['intervalExpression']='≥0.0005 (0.5%) and <0.001'
                elif fault=='EXTRA_NUMBER': a['intervalExpression'] += ' or above 0.2'
                elif fault=='MISSING_REVIEW': a.pop('sampleSizeReview')
                elif fault=='SOURCE_CHANGE': f.entity['entContent']['newCaveat']='unreviewed'
                elif fault=='SAMPLE_BASIS': a['sampleSizeBasis']='DEFAULT'
                elif fault=='FAF': a['sourceMetricExpression']='FAF'
                with self.assertRaises(ValueError): compile_reviews(f.review,f.specs,f.sources)

    def test_bound_percent_mismatch_is_rejected_even_if_source_contains_it(self):
        f,a,c=self.fixture('≥0.0005 (0.5%) and <0.001')
        with self.assertRaisesRegex(ValueError,'INTERVAL_PERCENT_MISMATCH'):compile_reviews(f.review,f.specs,f.sources)

    def test_unsupported_mode_extra_strength_or_lost_source_requirement_is_rejected(self):
        for fault in ['MOI','STRENGTH','SOURCE_AN','TERM']:
            with self.subTest(fault=fault):
                f,a,c=self.fixture()
                if fault=='MOI': f.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['modesOfInheritance'][0]['modeOfInheritance']='X-linked inheritance'
                elif fault=='STRENGTH': c['strengthDescriptor'][1]['applicability']='Applicable'
                elif fault=='SOURCE_AN': a.update(sampleSizeBasis='SOURCE_STATED',eligibleAnExpression='≥2000')
                elif fault=='TERM': a['populationStatistic']['sourceTerm']='AF'
                a['entitySha256']=content_hash(f.entity)
                with self.assertRaises(ValueError):compile_reviews(f.review,f.specs,f.sources)

    def test_compilation_leaves_review_and_original_unchanged(self):
        f,a,c=self.fixture();before=copy.deepcopy((f.review,f.specs,f.sources))
        compile_reviews(f.review,f.specs,f.sources)
        self.assertEqual(before,(f.review,f.specs,f.sources))

    def test_source_sample_count_cannot_match_a_numeric_prefix(self):
        f,a,c=self.fixture()
        text=a['clauseText']+' Observed alleles >=20000.'
        c['strengthDescriptor'][0]['text']=text
        a.update(clauseText=text,sampleSizeBasis='SOURCE_STATED',eligibleAnExpression='>=2000')
        a['entitySha256']=content_hash(f.entity)
        with self.assertRaisesRegex(ValueError,'AN_EXPRESSION_UNBOUND'):
            compile_reviews(f.review,f.specs,f.sources)
