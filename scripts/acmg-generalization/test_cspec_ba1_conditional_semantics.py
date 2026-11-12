"""T03 finite OR grammar controls, not population/source-count qualification."""
import copy
import hashlib
import json
import os
from pathlib import Path
import unittest

from cspec_ba1_semantics import compile_frequency, OR_COUNT_FAMILY


RAW_TEXT = ('Applicable when Grpmax Filtering Allele Frequency is ≥0.002 (0.2%)\u00a0OR ≥10 homo- or hemizygotes '
            '(or the sum of total hemi- and homozygotes) are present in the most current version of gnomAD '
            'available at the time of curation.')


class Ba1ConditionalSemanticsTest(unittest.TestCase):
    def setUp(self):
        self.source = {'url': 'https://example.test/SYNTHETIC_ONLY/rule', 'sha256': 'a' * 64,
                       'jsonPointer': '/SYNTHETIC/criterion/text', 'version': 'SYNTHETIC-ONLY'}
        self.guidance = {'url': 'https://example.test/SYNTHETIC_ONLY/guidance', 'sha256': 'b' * 64}
        self.review = {'reviewId': 'SYNTHETIC_ONLY_NOT_APPROVAL', 'reviewSha256': 'c' * 64,
                       'scope': 'SOURCE_REVIEWED_LIMITED_DERIVATION_FAMILY; NOT_CLINICAL_OR_PRODUCTION_APPROVAL',
                       'sources': [self.guidance]}

    def compile(self, text=RAW_TEXT):
        return compile_frequency(text, self.source, self.review)

    def test_full_or_sentence_retains_both_arms_and_never_becomes_a_scalar(self):
        actual = self.compile()
        self.assertEqual('CONDITIONAL', actual['disposition'])
        self.assertEqual(OR_COUNT_FAMILY, actual['semanticFamily'])
        self.assertEqual('a01-ba1-faf-or-individual-count-v1', actual['derivedPolicyVersion'])
        self.assertEqual('ANY', actual['expression']['operator'])
        self.assertEqual({
            'metric': 'gnomad.joint.faf95max', 'comparator': 'GREATER_THAN_OR_EQUAL',
            'value': '0.002', 'unit': 'fraction', 'minimumAn': '2000',
            'minimumAnComparator': 'GREATER_THAN_OR_EQUAL',
            'ancestries': ['afr', 'amr', 'eas', 'mid', 'nfe', 'sas']}, actual['expression']['frequency'])
        self.assertEqual({
            'operator': 'GREATER_THAN_OR_EQUAL', 'value': '10', 'unit': 'individuals',
            'populationScope': 'SAME_DEDUPLICATED_SOURCE',
            'counts': ['HOMOZYGOUS', 'HEMIZYGOUS'], 'sumOnlyIfMutuallyExclusive': True},
            actual['expression']['individualCount'])
        for scalar_field in ('value', 'comparator', 'minimumAn', 'minimumAnComparator'):
            self.assertNotIn(scalar_field, actual)
        self.assertEqual(RAW_TEXT, actual['originalText'])
        self.assertEqual([self.source], actual['sourceRefs'])
        self.assertEqual([self.guidance, self.source], actual['migration']['sources'])
        self.assertEqual('EXPLICIT_FAF_DEFINITION_ALIGNMENT', actual['migration']['basis'])
        self.assertEqual('PROJECT_SOURCE_DERIVATION_NOT_NEW_VCEP_OR_CLINICAL_APPROVAL', actual['migration']['reviewRole'])
        self.assertNotIn('approved', actual)
        self.assertNotIn('individualCountQualified', actual)

    def test_thresholds_are_source_data_and_not_gene_or_original_cutoff_dispatch(self):
        text = RAW_TEXT.replace('≥0.002 (0.2%)', '>0.004 (0.4%)').replace('≥10', '>=12')
        actual = self.compile(text)
        self.assertEqual('GREATER_THAN', actual['expression']['frequency']['comparator'])
        self.assertEqual('0.004', actual['expression']['frequency']['value'])
        self.assertEqual('12', actual['expression']['individualCount']['value'])
        self.assertEqual(text, actual['originalText'])

    def test_whitespace_and_html_entities_are_semantically_equivalent_but_original_is_preserved(self):
        text = RAW_TEXT.replace('≥', '&ge;').replace(' OR ', '\n OR\t ')
        actual = self.compile(text)
        self.assertEqual(self.compile()['expression'], actual['expression'])
        self.assertEqual(text, actual['originalText'])

    def test_extra_or_changed_condition_is_rejected_instead_of_dropped(self):
        for text in [RAW_TEXT + ' Except founder variants.', 'In adults only: ' + RAW_TEXT,
                     RAW_TEXT.replace('OR ', 'AND '), RAW_TEXT.replace('(or the sum of total hemi- and homozygotes) ', ''),
                     RAW_TEXT.replace('homo- or hemizygotes', 'alternate alleles'),
                     RAW_TEXT.replace('most current version', 'exome component'),
                     RAW_TEXT.replace('≥10', '>10'), RAW_TEXT.replace('≥10', '≥0'),
                     RAW_TEXT.replace('≥10', '≥010'), RAW_TEXT.replace('≥10', '≥10.0'),
                     RAW_TEXT.replace('Filtering Allele Frequency', 'minor allele frequency'),
                     RAW_TEXT.replace('(0.2%)', '(0.02%)'), RAW_TEXT.replace('≥0.002', '≥0'),
                     RAW_TEXT.replace('≥0.002', '≥2'), RAW_TEXT.rstrip('.')]:
            with self.subTest(text=text):
                self.assertIsNone(self.compile(text))

    def test_high_precision_inconsistent_percent_cannot_round_into_equivalence(self):
        text = RAW_TEXT.replace('0.002 (0.2%)', '0.0020000000000000000000000000000000001 (0.2%)')
        self.assertIsNone(self.compile(text))
        equivalent = text.replace('(0.2%)', '(0.20000000000000000000000000000000001%)')
        self.assertIsNotNone(self.compile(equivalent))

    def test_missing_or_malformed_guidance_review_is_not_accepted(self):
        self.assertIsNone(compile_frequency(RAW_TEXT, self.source))
        cases = [None, {}, 'REVIEWED']
        for field in ('reviewId', 'reviewSha256', 'scope', 'sources'):
            damaged = copy.deepcopy(self.review)
            damaged.pop(field)
            cases.append(damaged)
        for value in ['', [], {}, [{'url': 'http://example.test/source', 'sha256': 'b' * 64}],
                      [{'url': 'https://example.test/source', 'sha256': 'wrong'}]]:
            damaged = copy.deepcopy(self.review)
            damaged['sources'] = value
            cases.append(damaged)
        damaged = copy.deepcopy(self.review)
        damaged['scope'] = 'CLINICALLY_APPROVED'
        cases.append(damaged)
        for review in cases:
            with self.subTest(review=review):
                self.assertIsNone(compile_frequency(RAW_TEXT, self.source, review))

    def test_missing_or_malformed_rule_source_is_not_accepted(self):
        for source in [None, {}, 'CLAIMED', {'url': 'http://example.test/rule', 'sha256': 'a' * 64},
                       {'url': 'https://example.test/rule', 'sha256': 'invalid'},
                       {'url': 'https://example.test/rule', 'sha256': 'A' * 64}]:
            with self.subTest(source=source):
                self.assertIsNone(compile_frequency(RAW_TEXT, source, self.review))

    def test_source_inputs_are_not_mutated_or_retained_as_mutable_contract_aliases(self):
        original_source, original_review = copy.deepcopy(self.source), copy.deepcopy(self.review)
        actual = self.compile()
        self.assertEqual(original_source, self.source)
        self.assertEqual(original_review, self.review)
        self.source['sha256'] = 'd' * 64
        self.review['sources'][0]['sha256'] = 'e' * 64
        self.assertEqual([original_source], actual['sourceRefs'])
        self.assertEqual(original_review['sources'] + [original_source], actual['migration']['sources'])
        actual['expression']['frequency']['ancestries'].clear()
        self.assertEqual(['afr', 'amr', 'eas', 'mid', 'nfe', 'sas'], self.compile()['expression']['frequency']['ancestries'])

    def test_legacy_scalar_and_reciprocal_families_are_unchanged(self):
        scalar = compile_frequency('gnomAD joint GroupMax FAF95 >= 0.007; same population AN > 2000.', self.source)
        self.assertEqual('THRESHOLD', scalar['disposition'])
        self.assertEqual('SIMPLE_FAF95_THRESHOLD_V1', scalar['semanticFamily'])
        self.assertEqual('GREATER_THAN', scalar['minimumAnComparator'])
        reciprocal = self.compile('gnomAD Grpmax FAF ≥ 1:10,000 (≥ 0.01% or 0.0001).')
        self.assertEqual('THRESHOLD', reciprocal['disposition'])
        self.assertEqual('RECIPROCAL_GRPMAX_FAF_THRESHOLD_V1', reciprocal['semanticFamily'])
        self.assertNotIn('expression', reciprocal)

    @unittest.skipUnless(os.environ.get('VARVIS_T03_GN027_ORIGINAL'), 'requires explicit pinned original path')
    def test_pinned_original_sentence_is_read_complete_not_a_scalar_excerpt(self):
        # Real source-byte/grammar replay with an explicitly synthetic migration-review
        # control. This is not a new source-qualification or clinical approval claim.
        raw = Path(os.environ['VARVIS_T03_GN027_ORIGINAL']).read_bytes()
        expected = '01df51cabebe125cf9dc19bad5d02116000be49eaf96ac2307b7095603c0e289'
        self.assertEqual(expected, hashlib.sha256(raw).hexdigest())
        entity = json.loads(raw)['data'][3]
        criterion_index, criterion = next((i, c['entContent']) for i, c in enumerate(entity['ld']['CriteriaCode'])
                                          if c['entContent']['label'] == 'BA1')
        descriptor_index, descriptor = next((i, d) for i, d in enumerate(criterion['strengthDescriptor'])
                                            if d['strength'] == 'Stand Alone')
        text = descriptor['text']
        self.assertEqual(RAW_TEXT, text)
        source = {'url': 'https://cspec.genome.network/cspec/SequenceVariantInterpretation/id',
                  'sha256': expected, 'specificationId': 'GN027', 'version': '2.1',
                  'jsonPointer': f'/data/3/ld/CriteriaCode/{criterion_index}/entContent/strengthDescriptor/{descriptor_index}/text'}
        actual = compile_frequency(text, source, self.review)
        self.assertEqual('CONDITIONAL', actual['disposition'])
        self.assertEqual(text, actual['originalText'])
        self.assertEqual('0.002', actual['expression']['frequency']['value'])
        self.assertEqual('10', actual['expression']['individualCount']['value'])


if __name__ == '__main__':
    unittest.main()
