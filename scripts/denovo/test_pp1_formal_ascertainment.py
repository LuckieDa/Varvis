"""Independent synthetic counterexamples for source selection/report review.

These tests assert source qualification only. They neither evaluate a family
likelihood nor adopt a real disease parameter or execute a model/network call.
"""
from copy import deepcopy
import unittest
from unittest.mock import patch

from model_literature_contract import canonical, digest
from pp1_formal_ascertainment import (CONDITIONAL_INDEPENDENCE, PHENOTYPE_FIRST,
                                      review_ascertainment)


FIRST = 'Family F1 was ascertained on the basis of clinical phenotype before genotyping.'
LIMIT = 'Only variant-positive relatives were included in the reported segregation analysis.'


def fixture(texts, *, quote=FIRST, audit='SUPPORTED', member='FAMILY', families=None):
    units = [{'unitId': 'U' + str(i), 'text': text, 'sourceSha256': 'c' * 64,
              'kind': 'BODY_PARAGRAPH', 'sections': ['Results'], 'sourcePath': f'/article/body/p[{i + 1}]'}
             for i, text in enumerate(texts)]
    source = {'pmcid': 'PMC123', 'bundleSha256': 'a' * 64,
              'main': {'sourceSha256': 'b' * 64}, 'units': units}
    key = digest(canonical(['PMC123', 'b' * 64, 'F1']))
    rosters = families or {'F1': {'canonicalLabel': 'F1', 'anchor': {'unitId': 'U0', 'quote': 'Family F1'},
                                'members': {'I:1': {}, 'II:2': {}}}}
    fact = {'claimId': 'd' * 64, 'familyKey': key,
            'raw': {'familyLabel': 'F1', 'memberLabel': member, 'fieldId': 'F04.1', 'sourceValue': quote},
            'issues': [], 'sourceAudit': {'verdict': audit},
            'sourceRefs': [{'unitId': 'U0', 'quote': quote, 'sourceSha256': 'c' * 64}]}
    inventory = {'sourceIdentity': {'pmcid': 'PMC123', 'bundleSha256': 'a' * 64},
                 'target': {'geneSymbol': 'GENE1', 'variantAliases': ['c.1A>G']},
                 'inputScope': {'unitIds': ['U0']}, 'families': deepcopy(rosters), 'facts': [fact]}
    target = {'selectionKey': 'SYNTHETIC', 'geneSymbol': 'GENE1', 'diseaseId': 'TEST:1', 'inheritance': 'AD'}
    return source, inventory, target, key


class FormalAscertainment(unittest.TestCase):
    def review(self, texts, **kwargs):
        source, inventory, target, key = fixture(texts, **kwargs)
        return review_ascertainment(source, inventory, target)['families'][key]

    def test_supported_condition_is_audited_source_bound_and_not_adoption(self):
        source, inventory, target, key = fixture([FIRST])
        original = deepcopy((source, inventory, target))
        out = review_ascertainment(source, inventory, target)
        result = out['families'][key]
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', result['status'])
        self.assertEqual(['FAMILY'], result['qualifiedSubjectLabels'])
        self.assertEqual(PHENOTYPE_FIRST, result['sufficientConditions'][0]['sourceBasis'])
        ref = result['sufficientConditions'][0]['sourceRefs'][0]
        self.assertEqual(FIRST, source['units'][0]['text'][ref['start']:ref['end']])
        self.assertEqual(('PMC123', 'a' * 64, 'c' * 64, '/article/body/p[1]'),
                         (ref['pmcid'], ref['bundleSha256'], ref['sourceSha256'], ref['sourcePath']))
        self.assertEqual(['d' * 64], result['sufficientConditions'][0]['claimIds'])
        self.assertFalse(out['businessPublishable'])
        self.assertEqual(original, (source, inventory, target))

    def test_later_sentence_restriction_preserves_support_and_conflict(self):
        result = self.review([FIRST + ' ' + LIMIT])
        self.assertEqual('INELIGIBLE', result['status'])
        self.assertEqual([], result['qualifiedSubjectLabels'])
        self.assertTrue(result['sufficientConditions'])
        conflict = result['conflicts'][0]
        self.assertEqual([LIMIT], [r['quote'] for r in conflict['restrictionSourceRefs']])
        self.assertEqual([FIRST], [r['quote'] for r in conflict['supportSourceRefs']])

    def test_uncited_unreviewed_later_paragraph_cannot_hide_restriction(self):
        result = self.review([FIRST, LIMIT])
        self.assertEqual('INELIGIBLE', result['status'])
        self.assertEqual(['U0'], result['reviewedUnitIds'])
        self.assertEqual(['U0', 'U1'], result['scannedUnitIds'])
        self.assertEqual('U1', result['conflicts'][0]['restrictionSourceRefs'][0]['unitId'])

    def test_limitation_in_separate_file_stays_unresolved_instead_of_disappearing(self):
        source, inventory, target, key = fixture([FIRST, LIMIT])
        source['units'][1].update(sourceSha256='e' * 64, sections=['Supplemental methods'],
                                  kind='SUPPLEMENT_TEXT', sourcePart='word/document.xml')
        result = review_ascertainment(source, inventory, target)['families'][key]
        self.assertEqual('UNKNOWN', result['status'])
        self.assertTrue(any(r['reasonCode'].endswith('SCOPE_UNRESOLVED') for r in result['unresolved']))
        self.assertEqual('e' * 64, result['records'][-1]['sourceRefs'][0]['sourceSha256'])

    def test_quantified_sampling_genotyping_reporting_and_exclusion_are_distinct_restrictions(self):
        restrictions = (
            'Only variant-positive relatives were sampled in family F1.',
            'Genotyping in family F1 was restricted to variant-positive relatives.',
            'Noncarrier relatives in family F1 were excluded from the analysis.',
            'Variant-negative relatives were not reported in family F1.',
            'Reporting in family F1 depended on the target genotype.',
        )
        for limitation in restrictions:
            with self.subTest(limitation=limitation):
                result = self.review([FIRST, limitation])
                self.assertEqual('INELIGIBLE', result['status'])
                self.assertTrue(any(r['classification'] == 'GENOTYPE_DEPENDENT_RESTRICTION' for r in result['records']))

    def test_denied_or_uncertain_restriction_cannot_become_either_fact_or_authorization(self):
        for limitation in (
            'It is not true that only variant-positive relatives were included in family F1.',
            'Only variant-positive relatives might have been reported in family F1.',
            'Whether only variant-positive relatives were included in family F1 is unknown.',
            'Only variant-positive relatives were not included in family F1.',
        ):
            with self.subTest(limitation=limitation):
                result = self.review([FIRST, limitation])
                self.assertEqual('UNKNOWN', result['status'])
                self.assertTrue(result['sufficientConditions'])
                self.assertFalse(result['qualifiedSubjectLabels'])

    def test_short_quote_cannot_remove_outer_uncertainty_or_negation(self):
        for outer in ('It is not true that ', 'It is possible that ', 'Whether '):
            result = self.review([outer + FIRST], quote=FIRST)
            self.assertEqual('UNKNOWN', result['status'])
            self.assertFalse(result['sufficientConditions'])
            self.assertEqual(outer + FIRST, result['records'][0]['sourceRefs'][0]['quote'])

    def test_other_named_family_restriction_does_not_pollute_f1(self):
        result = self.review([FIRST, 'Only variant-positive relatives in family F2 were reported.'])
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', result['status'])
        self.assertEqual('OUT_OF_SCOPE', result['records'][-1]['classification'])

    def test_multiple_named_families_restriction_includes_current_family(self):
        result = self.review([FIRST, 'Only variant-positive relatives in families F2 and F1 were reported.'])
        self.assertEqual('INELIGIBLE', result['status'])

    def test_cohort_statement_cannot_be_borrowed_for_specific_family(self):
        cohort = 'Families in cohort C1 were ascertained on the basis of clinical phenotype before genotyping.'
        result = self.review(['Family F1 was described. ' + cohort], quote=cohort)
        self.assertEqual('UNKNOWN', result['status'])
        self.assertFalse(result['sufficientConditions'])

    def test_cohort_wide_limit_not_scoped_away_by_previous_f2_paragraph(self):
        result = self.review([FIRST, 'Family F2 was described.',
                              'Reporting in the study cohort was limited to variant-positive relatives.'])
        self.assertEqual('UNKNOWN', result['status'])
        self.assertEqual('UNRESOLVED_SCOPE', result['records'][-1]['scopeBasis'])

    def test_same_member_other_disease_limitation_is_retained_out_of_scope(self):
        result = self.review([FIRST, 'For another disease, only variant-positive relatives including I:1 were reported.'])
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', result['status'])
        self.assertEqual(('OTHER_DISEASE', 'OUT_OF_SCOPE'),
                         (result['records'][-1]['diseaseScope'], result['records'][-1]['classification']))

    def test_unmapped_disease_purpose_cannot_be_assumed_to_be_the_target(self):
        result = self.review([FIRST, 'Only variant-positive relatives in family F1 were reported for disease Z.'])
        self.assertEqual('UNKNOWN', result['status'])
        self.assertTrue(any(r['reasonCode'].endswith('DISEASE_SCOPE_UNRESOLVED') for r in result['unresolved']))

    def test_known_literal_target_disease_binding_does_not_hide_restriction(self):
        source, inventory, target, key = fixture([FIRST,
            'Only variant-positive relatives in family F1 were reported for TEST disease.'])
        result = review_ascertainment(source, inventory, target, disease_terms=('TEST disease',))['families'][key]
        self.assertEqual('INELIGIBLE', result['status'])

    def test_other_family_named_member_is_not_current_family(self):
        families = {'F1': {'canonicalLabel': 'F1', 'anchor': {'unitId': 'U0', 'quote': 'Family F1'}, 'members': {'I:1': {}}},
                    'F2': {'canonicalLabel': 'F2', 'anchor': {'unitId': 'U1', 'quote': 'Family F2'}, 'members': {'II:2': {}}}}
        result = self.review([FIRST, 'Family F2: II:2 was described.',
                              'Only variant-positive relatives of II:2 were reported.'], families=families)
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', result['status'])
        self.assertEqual('OUT_OF_SCOPE', result['records'][-1]['classification'])

    def test_member_condition_keeps_subject_and_does_not_expand_to_family(self):
        statement = 'I:1 was recruited based on clinical diagnosis prior to genetic testing.'
        result = self.review(['Family F1: ' + statement], quote=statement, member='I:1')
        # A colon joins the family anchor to an unsupported outer assertion;
        # whole-sentence evidence is deliberately required here.
        self.assertEqual('UNKNOWN', result['status'])
        result = self.review(['Family F1 was described. ' + statement], quote=statement, member='I:1')
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', result['status'])
        self.assertEqual(['I:1'], result['qualifiedSubjectLabels'])

    def test_member_support_cannot_authorize_family_scoped_fact(self):
        statement = 'I:1 was recruited based on clinical diagnosis prior to genetic testing.'
        result = self.review(['Family F1 was described. ' + statement], quote=statement, member='FAMILY')
        self.assertEqual('UNKNOWN', result['status'])
        self.assertFalse(result['sufficientConditions'])

    def test_conditional_selection_and_reporting_equivalent_has_own_basis(self):
        statement = ('Selection, sampling, genotyping and reporting in family F1 were independent of '
                     'the target genotype conditional on clinical phenotype and clinical covariates.')
        result = self.review([statement], quote=statement)
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', result['status'])
        self.assertEqual(CONDITIONAL_INDEPENDENCE, result['sufficientConditions'][0]['sourceBasis'])
        self.assertNotEqual(PHENOTYPE_FIRST, result['sufficientConditions'][0]['sourceBasis'])

    def test_conditional_non_dependence_is_supported_without_forcing_time_order(self):
        statement = ('Selection and reporting of relatives in family F1 did not depend on target genotype '
                     'given phenotype and covariates.')
        result = self.review([statement], quote=statement)
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', result['status'])
        self.assertEqual(CONDITIONAL_INDEPENDENCE, result['sufficientConditions'][0]['sourceBasis'])

    def test_conditional_equivalent_does_not_override_a_later_reporting_restriction(self):
        statement = ('Selection and reporting in family F1 were independent of target genotype given phenotype.')
        result = self.review([statement, LIMIT], quote=statement)
        self.assertEqual('INELIGIBLE', result['status'])
        self.assertTrue(result['conflicts'])

    def test_audit_missing_rejected_wrong_field_and_pin_mismatch_do_not_authorize(self):
        for verdict in ('AMBIGUOUS', 'CONTRADICTED', 'WRONG_SUBJECT_OR_FIELD'):
            self.assertEqual('UNKNOWN', self.review([FIRST], audit=verdict)['status'])
        for mutate in (
            lambda inv: inv['facts'][0]['raw'].update(fieldId='F04.2'),
            lambda inv: inv['facts'][0]['sourceRefs'][0].update(sourceSha256='e' * 64),
            lambda inv: inv['inputScope'].update(unitIds=[]),
            lambda inv: inv['facts'][0].update(verification='REJECTED'),
            lambda inv: inv['facts'][0].update(issues=['FAMILY_IDENTITY_UNRESOLVED']),
        ):
            source, inventory, target, key = fixture([FIRST])
            mutate(inventory)
            self.assertEqual('UNKNOWN', review_ascertainment(source, inventory, target)['families'][key]['status'])

    def test_raw_positive_in_other_unit_requires_audit_and_cannot_authorize(self):
        result = self.review(['Family F1 was described.', FIRST], quote='Family F1 was described.')
        self.assertEqual('UNKNOWN', result['status'])
        self.assertFalse(result['sufficientConditions'])
        self.assertTrue(any(r['reasonCode'].endswith('POSITIVE_REQUIRES_SOURCE_AUDIT') for r in result['unresolved']))

    def test_unrecognized_mechanism_remains_unknown_not_a_keyword_allowlist(self):
        for statement in (
            'Selection of family F1 depended on positive test results.',
            'Only relatives with positive test results in family F1 were included.',
            'Inclusion in family F1 was limited to the previously selected relatives.',
            'Family F1 was recruited through a genotype-based registry.',
            'Only relatives carrying the mutation in family F1 were described.',
            'Only members with positive molecular results in family F1 were presented.',
        ):
            with self.subTest(statement=statement):
                result = self.review([FIRST, statement])
                self.assertEqual('UNKNOWN', result['status'])
                self.assertFalse(result['qualifiedSubjectLabels'])

    def test_public_pgt_excerpt_is_embryo_transfer_not_adult_segregation_selection(self):
        # Exact mechanism sentence from PMC8702824, Materials and Methods /
        # Preimplantation Genetic Testing and Prenatal Diagnosis. The wrapper
        # remains synthetic: this tests purpose, not complete source replay.
        pgt = 'Embryos diagnosed as unaffected were selected for transfer.'
        result = self.review([FIRST, pgt])
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', result['status'])
        self.assertEqual('OUT_OF_SCOPE', result['records'][-1]['classification'])
        self.assertEqual('EMBRYO_TRANSFER_NOT_ADULT_SEGREGATION', result['records'][-1]['purposeScope'])
        self.assertEqual(pgt, result['records'][-1]['sourceRefs'][0]['quote'])
        result = self.review(['Family F1 was described.', pgt], quote='Family F1 was described.')
        self.assertEqual('UNKNOWN', result['status'])
        self.assertFalse(result['sufficientConditions'])

    def test_embryo_clause_cannot_hide_adult_genotype_restriction(self):
        mixed = ('Embryos diagnosed as unaffected were selected for transfer, '
                 'and only variant-positive relatives in family F1 were included in the segregation analysis.')
        result = self.review([FIRST, mixed])
        self.assertEqual('INELIGIBLE', result['status'])
        self.assertTrue(result['conflicts'])

    def test_genotype_causal_membership_and_reporting_relations_block_without_template_words(self):
        for statement in (
            'In family F1, genotype determined which relatives entered the segregation data.',
            'Only mutation-bearing cousins were described.',
            'Results were presented for carriers but not non-carriers.',
        ):
            with self.subTest(statement=statement):
                self.assertEqual('INELIGIBLE', self.review([FIRST, statement])['status'])
        for statement in (
            'In family F1, molecular results influenced which participants appeared in the pedigree.',
            'The pedigree displayed carriers alone.',
        ):
            with self.subTest(statement=statement):
                self.assertEqual('UNKNOWN', self.review([FIRST, statement])['status'])

    def test_first_purpose_does_not_scope_away_a_later_adult_mechanism(self):
        statement = ('For another disease, embryos were selected for transfer and only '
                     'variant-positive relatives in family F1 were reported for the target disease.')
        result = self.review([FIRST, statement])
        self.assertEqual('UNKNOWN', result['status'])
        self.assertEqual('UNRESOLVED_DISEASE', result['records'][-1]['diseaseScope'])
        self.assertNotEqual('OUT_OF_SCOPE', result['records'][-1]['classification'])

    def test_other_family_clause_does_not_scope_away_a_different_compound_process(self):
        statement = ('Family F2 was recruited by phenotype, but only variant-positive '
                     'relatives in the study were included in segregation analysis.')
        result = self.review([FIRST, statement])
        self.assertEqual('UNKNOWN', result['status'])
        self.assertEqual('UNRESOLVED_SCOPE', result['records'][-1]['scopeBasis'])

    def test_non_ascii_foreign_language_is_explicitly_unsupported(self):
        statement = 'Seuls les apparent\u00e9s porteurs ont \u00e9t\u00e9 analys\u00e9s.'
        result = self.review([FIRST, statement])
        self.assertEqual('UNKNOWN', result['status'])
        self.assertTrue(any(r['reasonCode'].endswith('LANGUAGE_UNSUPPORTED') for r in result['unresolved']))

    def test_unsupported_language_and_budget_are_explicit_unknown(self):
        result = self.review([FIRST, '家系F1仅纳入变异阳性亲属进行分离报告。'])
        self.assertEqual('UNKNOWN', result['status'])
        self.assertTrue(any(r['reasonCode'].endswith('LANGUAGE_UNSUPPORTED') for r in result['unresolved']))
        with patch('pp1_formal_ascertainment.MAX_UNITS', 0):
            result = self.review([FIRST])
        self.assertEqual('UNKNOWN', result['status'])
        self.assertFalse(result['scannedUnitIds'])
        self.assertEqual('PP1_FORMAL_ASCERTAINMENT_REVIEW_BUDGET_EXCEEDED', result['unresolved'][0]['reasonCode'])

    def test_ordinary_carrier_facts_are_not_selection_restrictions(self):
        result = self.review([FIRST, 'I:1 is a carrier. Only one candidate variant was reported.'])
        # Candidate-allocation prose is not a genotype-defined member filter.
        self.assertEqual('QUALIFIED_SUFFICIENT_CONDITION', result['status'])

    def test_source_and_target_mismatches_are_explicit_errors(self):
        for mutate, code in (
            (lambda inv, target: inv['sourceIdentity'].update(bundleSha256='f' * 64), 'ASCERTAINMENT_SOURCE_MISMATCH'),
            (lambda inv, target: inv['sourceIdentity'].update(pmcid='PMC999'), 'ASCERTAINMENT_PAPER_MISMATCH'),
            (lambda inv, target: target.update(geneSymbol='OTHER'), 'ASCERTAINMENT_TARGET_MISMATCH'),
        ):
            source, inventory, target, _ = fixture([FIRST])
            mutate(inventory, target)
            with self.assertRaisesRegex(ValueError, code):
                review_ascertainment(source, inventory, target)


if __name__ == '__main__':
    unittest.main()
