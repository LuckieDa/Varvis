import tempfile
import unittest
from pathlib import Path
import bs1_knowledge as k
from bs1_expert_batch import compile_reviews, content_hash


class ExpertBatchTest(unittest.TestCase):
    def fixture(self):
        self.entity = {'entContent': {'approvedOn': '2026-10-09T00:00:00Z', 'shortTitle': 'Synthetic panel'},
                       'ld': {'CriteriaCode': [{'entContent': {'label': 'BS1', 'gene': ['X'],
                               'instructionsToUse': 'GnomAD filtering allele frequency ≥0.025%.',
                               'strengthDescriptor': [{'status': 'approved', 'applicability': 'Applicable', 'strength': 'Strong',
                                                       'text': 'GnomAD filtering allele frequency ≥0.025%.'},
                                                      {'status': 'approved', 'applicability': 'Not Applicable', 'strength': 'Moderate', 'text': ''}]}}],
                              'Gene': [{'entContent': {'HGNC': {'symbol': 'X', 'hgnc_id': 'HGNC:1'}}}],
                              'RuleSet': [{'entContent': {'genes': [{'gene': 'X', 'diseases': [{'preferredMondoId': 'MONDO:0000001',
                                          'preferredTitle': 'Synthetic disease', 'modesOfInheritance': [
                                              {'modeOfInheritance': 'Autosomal dominant inheritance'},
                                              {'modeOfInheritance': 'Autosomal recessive inheritance'}]}]}]}}]}}
        self.specs = [{'specificationId': 'GN001', 'version': '1', 'sourceId': 'raw', 'original': self.entity}]
        self.sources = [{'sourceId': 'raw', 'url': 'https://example.org/synthetic', 'version': '1', 'sha256': 'a'*64}]
        self.review = {'schemaVersion': 'bs1-expert-clause-review-v1', 'reviewer': 'SYNTHETIC', 'reviewedAt': '2026-10-09',
                       'basis': 'Synthetic controls', 'dataScopePolicyId': 'BS1_LOCAL_EXOMES_V1', 'specifications': [
                           {'specificationId': 'GN001', 'version': '1', 'entitySha256': content_hash(self.entity), 'expectedBackgrounds': 2,
                            'decision': 'ADOPT_LOCAL_EXOMES', 'crossRuleReview': 'Synthetic', 'exceptionReview': 'Synthetic', 'scopeReview': 'Synthetic',
                            'instruction': 'GnomAD filtering allele frequency ≥0.025%.', 'fraction': '0.00025', 'operator': 'GTE'}]}

    def test_dual_mode_scope_and_decimal_percent_are_preserved(self):
        self.fixture(); records = compile_reviews(self.review, self.specs, self.sources)
        self.assertEqual(['AUTOSOMAL_DOMINANT', 'AUTOSOMAL_RECESSIVE'], [r['scope']['modes'][0] for r in records])
        self.assertTrue(all(r['expertPolicy']['tiers'][0]['conditions'][0]['decimal'] == '0.00025' for r in records))
        self.assertTrue(all(r['relationship']['sourceId'] == 'raw' for r in records))
        self.review['specifications'][0]['fraction'] = '0.025'
        with self.assertRaisesRegex(ValueError, 'THRESHOLD_TEXT_MISMATCH'):
            compile_reviews(self.review, self.specs, self.sources)

    def test_full_source_change_or_unreviewed_spec_cannot_be_promoted(self):
        self.fixture(); self.entity['entContent']['notes'] = 'new restriction'
        with self.assertRaisesRegex(ValueError, 'SOURCE_CHANGED'):
            compile_reviews(self.review, self.specs, self.sources)
        self.fixture(); self.review['specifications'][0]['decision'] = 'DRAFT'
        with self.assertRaisesRegex(ValueError, 'SEMANTIC_REVIEW_REQUIRED'):
            compile_reviews(self.review, self.specs, self.sources)

    def test_extra_strength_and_unsupported_modes_are_not_dropped(self):
        self.fixture(); self.entity['ld']['CriteriaCode'][0]['entContent']['strengthDescriptor'][1]['applicability'] = 'Applicable'
        self.review['specifications'][0]['entitySha256'] = content_hash(self.entity)
        with self.assertRaisesRegex(ValueError, 'CLAUSE_REVIEW_MISMATCH'):
            compile_reviews(self.review, self.specs, self.sources)
        self.fixture(); self.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]['modesOfInheritance'][1]['modeOfInheritance'] = 'Semidominant inheritance'
        self.review['specifications'][0]['entitySha256'] = content_hash(self.entity)
        with self.assertRaisesRegex(ValueError, 'MOI_UNSUPPORTED'):
            compile_reviews(self.review, self.specs, self.sources)

    def test_only_exact_sourced_mapping_joins_epidemiology_without_adopting_parameters(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            k.write(d/'relations.json', [{'hgncId': 'HGNC:1', 'mondoId': 'MONDO:0000001', 'moi': 'AD', 'classification': 'Definitive'}])
            (d/'mapping.tsv').write_text('# version\nsubject_id\tpredicate_id\tobject_id\nMONDO:0000001\tskos:exactMatch\tOrphanet:1\nMONDO:0000001\tskos:closeMatch\tOrphanet:2\n')
            (d/'epi.xml').write_text('<JDBOR><Disorder><OrphaCode>1</OrphaCode><Name>X</Name><PrevalenceList><Prevalence><PrevalenceType><Name>Annual incidence</Name></PrevalenceType><ValMoy>2</ValMoy></Prevalence></PrevalenceList></Disorder></JDBOR>')
            sources = [{'sourceId': name, 'path': name, 'kind': kind, 'url': 'https://example.org/synthetic', 'version': '1', 'sha256': k.sha((d/name).read_bytes())}
                       for name, kind in [('relations.json', 'CLINGEN_JSON'), ('mapping.tsv', 'MONDO_SSSOM'), ('epi.xml', 'ORPHA_XML')]]
            k.write(d/'manifest.json', {'schemaVersion': 'bs1-knowledge-input-v1', 'version': '1', 'sources': sources})
            report = k.compile_manifest(d/'manifest.json', d/'out')
            self.assertEqual(1, report['exactDiseaseMappingCandidates']); self.assertEqual(1, report['epidemiologyLinkedCandidateBackgrounds'])
            self.assertEqual(0, report['curatedRecordCandidates'])
            draft = k.read_json((d/'out/parameter-drafts.json').read_bytes())[0]
            self.assertEqual('UNREVIEWED', draft['status']); self.assertEqual(4, len(draft['missingParameters']))
            self.assertEqual('Annual incidence', draft['epidemiologyCandidates'][0]['type'])
            self.assertEqual(3, draft['epidemiologyCandidates'][0]['diseaseMapping']['sourceRow'])

    def reviewed_bound(self, expression='>0.0001', fraction='0.0001', operator='GT'):
        self.fixture()
        self.entity['entContent']['states'] = [{'name': 'Released', 'current': True}]
        c = self.entity['ld']['CriteriaCode'][0]['entContent']
        c.pop('instructionsToUse')
        c['strengthDescriptor'][0]['text'] = 'Use GroupMax FAF95 ' + expression + ' in gnomAD.'
        a = self.review['specifications'][0]
        a.update(translation='REVIEWED_SINGLE_FAF_BOUND_V1', instruction=None, clauseText=c['strengthDescriptor'][0]['text'],
                 thresholdExpression=expression, fraction=fraction, operator=operator,
                 statisticReview='Synthetic FAF95', additionalConditionsReview='Synthetic no other conditions')
        a['entitySha256'] = content_hash(self.entity)
        return a, c

    def test_reviewed_bound_preserves_strictness_percent_and_complete_text(self):
        a, c = self.reviewed_bound()
        record = compile_reviews(self.review, self.specs, self.sources)[0]
        condition = record['expertPolicy']['tiers'][0]['conditions'][0]
        self.assertEqual('GT', condition['operator']); self.assertEqual(c['strengthDescriptor'][0]['text'], condition['originalText'])
        a['operator'] = 'GTE'
        with self.assertRaisesRegex(ValueError, 'THRESHOLD_TEXT_MISMATCH'): compile_reviews(self.review, self.specs, self.sources)
        self.reviewed_bound('≥0.001%', '0.00001', 'GTE')
        self.assertEqual('0.00001', compile_reviews(self.review, self.specs, self.sources)[0]['expertPolicy']['tiers'][0]['conditions'][0]['decimal'])

    def test_unbound_numeric_prefix_or_lost_qualifier_cannot_compile(self):
        a, c = self.reviewed_bound('>0.00012', '0.00012')
        a.update(thresholdExpression='>0.0001', fraction='0.0001')
        with self.assertRaisesRegex(ValueError, 'EXPRESSION_UNBOUND'): compile_reviews(self.review, self.specs, self.sources)
        a, c = self.reviewed_bound(); a['clauseText'] = '>0.0001'
        with self.assertRaisesRegex(ValueError, 'CLAUSE_REVIEW_MISMATCH'): compile_reviews(self.review, self.specs, self.sources)
        a, c = self.reviewed_bound(); a.pop('additionalConditionsReview')
        with self.assertRaisesRegex(ValueError, 'SEMANTIC_REVIEW_REQUIRED'): compile_reviews(self.review, self.specs, self.sources)

    def test_legacy_strength_status_requires_explicit_released_source_basis(self):
        a, c = self.reviewed_bound(); c['strengthDescriptor'][0].pop('status'); a['entitySha256'] = content_hash(self.entity)
        with self.assertRaisesRegex(ValueError, 'STRENGTH_NOT_APPROVED'): compile_reviews(self.review, self.specs, self.sources)
        a['statusBasis'] = 'RELEASED_ENTITY_LEGACY_STRENGTH_STATUS_ABSENT'
        self.assertEqual(2, len(compile_reviews(self.review, self.specs, self.sources)))
        self.entity['entContent']['states'][0]['name'] = 'Under Review'; a['entitySha256'] = content_hash(self.entity)
        with self.assertRaisesRegex(ValueError, 'NOT_RELEASED'): compile_reviews(self.review, self.specs, self.sources)

    def test_legacy_preferred_mode_does_not_guess_or_drop_an_explicit_mode(self):
        a, c = self.reviewed_bound()
        d = self.entity['ld']['RuleSet'][0]['entContent']['genes'][0]['diseases'][0]
        d.pop('modesOfInheritance'); d['preferredModeOfInheritance'] = 'Autosomal recessive inheritance'
        a['expectedBackgrounds'] = 1; a['entitySha256'] = content_hash(self.entity)
        self.assertEqual(['AUTOSOMAL_RECESSIVE'], compile_reviews(self.review, self.specs, self.sources)[0]['scope']['modes'])
        d['modesOfInheritance'] = []; a['entitySha256'] = content_hash(self.entity)
        with self.assertRaisesRegex(ValueError, 'MOI_UNSUPPORTED'): compile_reviews(self.review, self.specs, self.sources)


    def test_reviewed_single_scientific_expression_uses_full_compiler_and_preserves_source(self):
        a, c = self.reviewed_bound('≥8.3x10<sup>-5 </sup>', '0.000083', 'GTE')
        original = c['strengthDescriptor'][0]['text']
        records = compile_reviews(self.review, self.specs, self.sources)
        for record in records:
            condition, = record['expertPolicy']['tiers'][0]['conditions']
            self.assertEqual(('GTE', '0.000083', original), (condition['operator'], condition['decimal'], condition['originalText']))
        a['thresholdExpression'] = '≥8.3x10<sup>-5 </sup> in males'
        with self.assertRaisesRegex(ValueError, 'EXPRESSION_UNBOUND'):
            compile_reviews(self.review, self.specs, self.sources)

    def test_scientific_bound_does_not_lose_operator_or_duplicate_source_identity(self):
        for expression, fraction, operator in [('>8.3 x 10<sup>-5</sup>', '0.000083', 'GT'),
                                                ('≥0.001%', '0.00001', 'GTE')]:
            a, c = self.reviewed_bound(expression, fraction, operator)
            self.assertEqual(operator, compile_reviews(self.review, self.specs, self.sources)[0]['expertPolicy']['tiers'][0]['conditions'][0]['operator'])
            c['strengthDescriptor'][0]['text'] += ' Repeated ' + expression
            a['clauseText'] = c['strengthDescriptor'][0]['text']
            a['entitySha256'] = content_hash(self.entity)
            with self.assertRaisesRegex(ValueError, 'EXPRESSION_UNBOUND'):
                compile_reviews(self.review, self.specs, self.sources)

    def test_nonsex_policy_retains_its_distinct_bigdecimal_length_contract(self):
        # Bs1Policy.conditions uses BigDecimal directly; only the sex consumer
        # adds its 80-character constraint. The generic atom remains unchanged.
        for length in (80, 81):
            with self.subTest(length=length):
                expression = '≥1x10<sup>-' + str(length - 2) + '</sup>'
                fraction = '0.' + '0' * (length - 3) + '1'
                self.reviewed_bound(expression, fraction, 'GTE')
                records = compile_reviews(self.review, self.specs, self.sources)
                self.assertTrue(records)
                self.assertTrue(all(record['expertPolicy']['tiers'][0]['conditions'][0]['decimal'] == fraction
                                    for record in records))


if __name__ == '__main__':
    unittest.main()
