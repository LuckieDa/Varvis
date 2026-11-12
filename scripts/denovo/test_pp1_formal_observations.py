"""Native, audited observation counterexamples; no clinical/model adoption.

These fixtures run the actual raw extraction/source-review contract. They test
the observation reader separately from likelihood arithmetic and disease policy.
"""
from copy import deepcopy
import unittest

from model_literature_contract import canonical, digest
from pp1_formal_observations import (qualify_assessments, bind_formal_observations,
                                     CONTEXT_MAX_UNITS, CONTEXT_MAX_CHARACTERS)
from pp1_literature_contract import audit_check, claim_id, VERSION as RAW_VERSION, AUDIT_VERSION
from test_pp1_segregation import selection


def reviewed(texts, claims, members=('I:1',), verdict='SUPPORTED'):
    units = [{'unitId': f'U{i}', 'text': text, 'sourceSha256': 'c' * 64, 'kind': 'PARAGRAPH'}
             for i, text in enumerate(['Family F1: ' + ', '.join(members) + '.', *texts])]
    source = {'pmcid': 'PMC123', 'bundleSha256': 'a' * 64, 'attachments': [],
              'main': {'sourceSha256': 'b' * 64, 'tables': []}, 'units': units}
    facts = []
    # claims are field, unit index within texts, member, optional short value,
    # optional quote. Every source review remains exact and independently tied
    # to the raw claim ID through the production audit_check entry point.
    for item in claims:
        field, index, member = item[:3]
        quote = item[4] if len(item) > 4 else texts[index]
        value = item[3] if len(item) > 3 else quote
        facts.append({'fieldId': field, 'familyLabel': 'F1', 'memberLabel': member,
                      'sourceValue': value, 'relatedMembers': [], 'relationKind': 'NONE',
                      'citations': [{'unitId': f'U{index + 1}', 'quote': quote}]})
    groups = [{'group': f'F{i:02}', 'outcome': 'UNRESOLVED'} for i in range(1, 11)]
    candidate = {'schemaVersion': RAW_VERSION, 'families': [{'familyLabel': 'F1',
                 'anchor': {'unitId': 'U0', 'quote': 'Family F1:'},
                 'members': [{'memberLabel': member, 'anchor': {'unitId': 'U0', 'quote': member}}
                             for member in members]}], 'facts': facts,
                 'coverage': 'REVIEWED_SUPPLIED_SCOPE', 'groupChecks': groups}
    audit = {'schemaVersion': AUDIT_VERSION,
             'audits': [{'claimId': claim_id(fact), 'verdict': verdict,
                         'reason': 'Synthetic independent source support proposal.',
                         'evidence': deepcopy(fact['citations'])} for fact in facts],
             'additions': [], 'additionalFamilies': [], 'coverage': candidate['coverage'], 'groupChecks': groups}
    inventory = audit_check(source, candidate, audit, [u['unitId'] for u in units],
                            {'geneSymbol': 'GENE1', 'variantAliases': ['c.1A>G']})
    return source, inventory


def qualify(texts, claims, **kwargs):
    source, inventory = reviewed(texts, claims, **kwargs)
    return source, inventory, qualify_assessments(source, inventory, selection())


def bind(package, source_identity=None, kind=None):
    qualified = {'sourceIdentity': source_identity or package['sourceIdentity'],
                 'target': selection(), 'clinicalAssessments': package, 'observations': []}
    policy = {'affectedTerms': ['TEST disease']}
    if kind:
        policy['formalModel'] = {'phenotypeModel': {'kind': kind}}
    return bind_formal_observations([qualified], policy)


class FormalObservations(unittest.TestCase):
    def reasons(self, result):
        return {n['reasonCode'] for n in result['needs']}

    def test_time_qualified_status_is_one_observation_with_complete_source_proof(self):
        statement = 'I:1 was clinically unaffected at age 10 years.'
        source, inventory, package = qualify([statement], [('F05.2', 0, 'I:1')])
        before = deepcopy((source, inventory, package))
        result = bind(package)
        self.assertEqual([], result['needs'])
        self.assertEqual(('UNAFFECTED', 10),
                         (result['memberStates'][0]['status'], result['memberStates'][0]['attainedAge']))
        row = result['memberStates'][0]
        self.assertEqual('SAME_COMPLETE_SOURCE_ASSERTION', row['bindingBasis'])
        self.assertTrue(row['statusFactIds'] and row['ageFactIds'] and row['claimIds'])
        self.assertTrue(row['sourceObservationId'] and row['assessmentIds'])
        self.assertTrue(all(r['pmcid'] == 'PMC123' and r['bundleSha256'] == 'a' * 64 for r in row['sourceRefs']))
        self.assertEqual(before, (source, inventory, package))

    def test_age_independent_status_needs_source_binding_but_no_age(self):
        text = 'I:1 was clinically unaffected.'
        _, _, package = qualify([text], [('F05.2', 0, 'I:1')])
        independent = bind(package, kind='AGE_INDEPENDENT_BINARY_STATUS')
        attained = bind(package, kind='ATTAINED_AGE_BANDS')
        self.assertEqual([], independent['needs'])
        self.assertEqual(('UNAFFECTED', None), (independent['memberStates'][0]['status'],
                                                independent['memberStates'][0]['attainedAge']))
        self.assertTrue(independent['memberStates'][0]['sourceObservationId'])
        self.assertEqual([], attained['memberStates'])
        self.assertIn('PP1_FORMAL_STATUS_AGE_OBSERVATION_REQUIRED', self.reasons(attained))

    def test_age_independent_model_does_not_borrow_another_observation_age(self):
        texts = ['I:1 was clinically unaffected.', 'I:1 was examined at age 40 years.']
        _, _, package = qualify(texts, [('F05.2', 0, 'I:1'), ('F05.4', 1, 'I:1')])
        result = bind(package, kind='AGE_INDEPENDENT_BINARY_STATUS')
        self.assertEqual([], result['needs'])
        self.assertEqual(('UNAFFECTED', None), (result['memberStates'][0]['status'], result['memberStates'][0]['attainedAge']))
        self.assertEqual(2, len(result['observations']))

    def test_age_independent_model_does_not_require_unreviewed_age_in_status_quote(self):
        text = 'I:1 was clinically unaffected at age 40 years.'
        _, _, package = qualify([text], [('F05.2', 0, 'I:1', 'clinically unaffected')])
        result = bind(package, kind='AGE_INDEPENDENT_BINARY_STATUS')
        self.assertEqual('UNAFFECTED', result['memberStates'][0]['status'])
        self.assertIsNone(result['memberStates'][0]['attainedAge'])
        self.assertTrue(all(n['blockingEffect'] == 'MAY_ADD_INFORMATION' for n in result['needs']))

    def test_age_independent_model_cannot_use_other_disease_or_legacy_state(self):
        text = 'I:1 was diagnosed with OTHER disease.'
        _, _, package = qualify([text], [('F05.2', 0, 'I:1')])
        self.assertEqual([], bind(package, kind='AGE_INDEPENDENT_BINARY_STATUS')['memberStates'])
        policy = {'affectedTerms': ['TEST disease'],
                  'formalModel': {'phenotypeModel': {'kind': 'AGE_INDEPENDENT_BINARY_STATUS'}}}
        legacy = {'observations': [{'kind': 'clinical', 'value': 'UNAFFECTED', 'sourceRefs': []}]}
        result = bind_formal_observations([legacy], policy)
        self.assertEqual([], result['memberStates'])
        self.assertIn('PP1_FORMAL_OBSERVATION_BINDING_REQUIRED', self.reasons(result))

    def test_ten_year_status_never_becomes_forty_year_status(self):
        texts = ['I:1 was clinically unaffected at age 10 years.', 'I:1 was examined at age 40 years.']
        _, _, package = qualify(texts, [('F05.2', 0, 'I:1'), ('F05.4', 1, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        self.assertIn('PP1_FORMAL_MULTIPLE_CLINICAL_OBSERVATIONS_REQUIRE_SELECTION', self.reasons(result))
        self.assertIn('PP1_FORMAL_STATUS_AGE_OBSERVATION_REQUIRED', self.reasons(result))
        self.assertFalse(any(o['status'] == 'UNAFFECTED' and o['attainedAge'] == 40 for o in result['observations']))
        self.assertTrue(any(o['status'] == 'UNAFFECTED' and o['attainedAge'] == 10 for o in result['observations']))

    def test_same_unit_does_not_mean_same_observation(self):
        text = 'I:1 was clinically unaffected. I:1 was examined at age 40 years.'
        _, _, package = qualify([text], [('F05.2', 0, 'I:1'), ('F05.4', 0, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        self.assertEqual(2, len(result['observations']))
        self.assertIn('PP1_FORMAL_STATUS_AGE_OBSERVATION_REQUIRED', self.reasons(result))

    def test_one_complete_examination_assertion_binds_status_to_its_own_age(self):
        text = 'I:1 was clinically examined at age 40 years and was clinically unaffected by TEST disease.'
        _, _, package = qualify([text], [('F05.2', 0, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['needs'])
        row = result['memberStates'][0]
        self.assertEqual(('UNAFFECTED', 40, ['TEST disease']), (row['status'], row['attainedAge'], row['diseaseTerms']))

    def test_distributed_explicit_visit_references_are_supported(self):
        texts = ['At visit V1, I:1 was clinically unaffected by TEST disease.',
                 'I:1 was examined at age 40 years during visit V1.']
        _, inventory, package = qualify(texts, [('F05.2', 0, 'I:1'), ('F05.4', 1, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['needs'])
        self.assertEqual(1, len(result['memberStates']))
        row = result['memberStates'][0]
        self.assertEqual(40, row['attainedAge'])
        self.assertEqual('EXPLICIT_NAMED_VISIT', row['bindingBasis'])
        self.assertEqual(set(f['claimId'] for f in inventory['facts']), set(row['claimIds']))
        self.assertEqual({'U1', 'U2'}, {r['unitId'] for r in row['sourceRefs']})

    def test_different_visits_or_members_cannot_share_age(self):
        for age_text, members in [('At visit V2, I:1 was examined at age 40 years.', ('I:1',)),
                                  ('At visit v1, I:1 was examined at age 40 years.', ('I:1',)),
                                  ('At visit V1, II:2 was examined at age 40 years.', ('I:1', 'II:2'))]:
            with self.subTest(age_text=age_text):
                member = 'II:2' if 'II:2' in age_text else 'I:1'
                _, _, package = qualify(['At visit V1, I:1 was clinically unaffected.', age_text],
                                         [('F05.2', 0, 'I:1'), ('F05.4', 1, member)], members=members)
                result = bind(package)
                self.assertEqual([], result['memberStates'])
                self.assertIn('PP1_FORMAL_STATUS_AGE_OBSERVATION_REQUIRED', self.reasons(result))

    def test_onset_and_ordinary_ages_do_not_supply_attained_risk(self):
        for age in ('I:1 was 40 years old.', 'I:1 had disease onset at age 40 years.',
                    'I:1 first developed symptoms at age 40 years.'):
            with self.subTest(age=age):
                _, _, package = qualify(['I:1 was clinically unaffected.', age],
                                         [('F05.2', 0, 'I:1'), ('F05.4', 1, 'I:1')])
                result = bind(package)
                self.assertEqual([], result['memberStates'])
                self.assertFalse(any(o['attainedAge'] is not None for o in result['observations']))
                self.assertIn('PP1_FORMAL_STATUS_AGE_OBSERVATION_REQUIRED', self.reasons(result))

    def test_diagnosis_at_onset_is_not_lifelong_attained_affected_status(self):
        text = 'I:1 was diagnosed with TEST disease at onset at age 10 years.'
        _, _, package = qualify([text], [('F05.2', 0, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        self.assertTrue(result['needs'])

    def test_diagnosis_age_needs_an_explicit_examination_or_visit(self):
        ordinary = 'I:1 was diagnosed with TEST disease at age 40 years.'
        _, _, package = qualify([ordinary], [('F05.2', 0, 'I:1')])
        self.assertEqual([], bind(package)['memberStates'])
        examination = 'At the clinical examination at age 40 years, I:1 was diagnosed with TEST disease.'
        _, _, package = qualify([examination], [('F05.2', 0, 'I:1')])
        self.assertEqual(('AFFECTED', 40), (bind(package)['memberStates'][0]['status'],
                                          bind(package)['memberStates'][0]['attainedAge']))

    def test_different_disease_never_lends_target_disease_status(self):
        text = 'I:1 was clinically unaffected by OTHER disease at age 40 years.'
        _, _, package = qualify([text], [('F05.2', 0, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        self.assertEqual(['OTHER disease'], result['observations'][0]['diseaseTerms'])
        self.assertIn('PP1_FORMAL_ASSESSMENT_DISEASE_SCOPE_UNRESOLVED', self.reasons(result))

    def test_opposite_statuses_or_ages_with_same_visit_are_conflicts(self):
        cases = [(['At visit V1, I:1 was clinically unaffected by TEST disease at age 40 years.',
                   'At visit V1, I:1 was diagnosed with TEST disease at age 40 years.'],
                  [('F05.2', 0, 'I:1'), ('F05.2', 1, 'I:1')]),
                 (['At visit V1, I:1 was clinically unaffected at age 10 years.',
                   'At visit V1, I:1 was examined at age 40 years.'],
                  [('F05.2', 0, 'I:1'), ('F05.4', 1, 'I:1')])]
        for texts, claims in cases:
            with self.subTest(texts=texts):
                _, _, package = qualify(texts, claims)
                result = bind(package)
                self.assertEqual([], result['memberStates'])
                self.assertIn('PP1_FORMAL_SAME_OBSERVATION_CONFLICT', self.reasons(result))

    def test_two_complete_followups_are_preserved_without_latest_age_selection(self):
        texts = ['At visit V1, I:1 was clinically unaffected at age 10 years.',
                 'At visit V2, I:1 was diagnosed with TEST disease at age 40 years.']
        _, _, package = qualify(texts, [('F05.2', 0, 'I:1'), ('F05.2', 1, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        self.assertEqual({10, 40}, {o['attainedAge'] for o in result['observations']})
        self.assertIn('PP1_FORMAL_MULTIPLE_CLINICAL_OBSERVATIONS_REQUIRE_SELECTION', self.reasons(result))

    def test_short_quote_cannot_hide_outer_negation_or_conditional_context(self):
        inner = 'I:1 was clinically unaffected at age 40 years.'
        for prefix in ('It is not true that ', 'It is possible that ', 'Whether '):
            with self.subTest(prefix=prefix):
                _, _, package = qualify([prefix + inner], [('F05.2', 0, 'I:1', inner, inner)])
                result = bind(package)
                self.assertEqual([], result['memberStates'])
                self.assertIn('PP1_FORMAL_ASSESSMENT_EXTRACTOR_UNSUPPORTED', self.reasons(result))

    def test_unaudited_additions_and_challenged_facts_never_become_assessments(self):
        text = 'I:1 was clinically unaffected at age 40 years.'
        for verdict in ('AMBIGUOUS', 'CONTRADICTED', 'WRONG_SUBJECT_OR_FIELD'):
            with self.subTest(verdict=verdict):
                _, _, package = qualify([text], [('F05.2', 0, 'I:1')], verdict=verdict)
                self.assertEqual([], package['observations'])
                self.assertIn('PP1_FORMAL_ASSESSMENT_REQUIRES_SOURCE_REVIEW', self.reasons(package))
        source, inventory = reviewed([text], [('F05.2', 0, 'I:1')])
        inventory['facts'][0]['sourceSupport'] = 'ADDITION_REQUIRES_AUDIT'
        package = qualify_assessments(source, inventory, selection())
        self.assertEqual([], package['observations'])

    def test_short_status_value_cannot_authorize_an_age_hidden_in_broad_quote(self):
        text = 'I:1 was clinically unaffected at age 40 years.'
        _, _, package = qualify([text], [('F05.2', 0, 'I:1', 'clinically unaffected')])
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        self.assertIn('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW', self.reasons(result))
        self.assertTrue(all(o['attainedAge'] is None for o in result['observations']))

    def test_unreviewed_later_observation_in_context_is_retained_as_review_need(self):
        old = 'I:1 was clinically unaffected at age 10 years.'
        new = 'I:1 was diagnosed with TEST disease at age 40 years.'
        text = old + ' ' + new
        _, _, package = qualify([text], [('F05.2', 0, 'I:1', old, old)])
        result = bind(package)
        self.assertIn('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW', self.reasons(result))
        context = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW')
        self.assertTrue(any(new.rstrip('.') == r['quote'] for r in context['sourceLocators']))
        self.assertEqual(1, len(result['observations']))
        self.assertEqual([], result['memberStates'])

    def test_related_unreviewed_observation_in_another_unit_is_not_ignored(self):
        texts = ['I:1 was clinically unaffected at age 10 years.',
                 'I:1 was clinically examined at age 40 years and was affected with TEST disease.']
        _, _, package = qualify(texts, [('F05.2', 0, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        self.assertIn('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW', self.reasons(result))

    def test_supplied_event_outside_audited_input_scope_blocks_without_becoming_a_fact(self):
        first = 'I:1 was clinically unaffected at age 10 years.'
        for later in ('At visit V2, I:1 developed TEST disease at age 40 years.',
                      'At visit V2, I:1 was clinically examined at age 40 years and was affected with TEST disease.',
                      'For I:1, this status refers to OTHER disease.'):
            with self.subTest(later=later):
                source, inventory = reviewed([first, later], [('F05.2', 0, 'I:1')])
                inventory['inputScope']['unitIds'].remove('U2')
                package = qualify_assessments(source, inventory, selection())
                result = bind(package)
                self.assertEqual([], result['memberStates'])
                self.assertEqual(1, len(package['observations']))
                self.assertEqual(10, package['observations'][0]['attainedAge'])
                context = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW')
                self.assertEqual('OUTSIDE_AUDITED_INPUT_SCOPE', context['actual']['sourceReviewScope'])
                self.assertEqual('BLOCKS_SCORING', context['blockingEffect'])
                self.assertEqual('U2', context['sourceLocators'][0]['unitId'])

    def test_positive_age_outside_audited_scope_cannot_complete_a_reviewed_visit(self):
        source, inventory = reviewed(['At visit V1, I:1 was clinically unaffected.',
                                      'At visit V1, I:1 was examined at age 40 years.'],
                                     [('F05.2', 0, 'I:1')])
        inventory['inputScope']['unitIds'].remove('U2')
        package = qualify_assessments(source, inventory, selection())
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        self.assertTrue(all(o['attainedAge'] is None for o in package['observations']))
        context = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW')
        self.assertEqual(['AGE'], context['actual']['unreviewedComponents'])
        self.assertEqual('OUTSIDE_AUDITED_INPUT_SCOPE', context['actual']['sourceReviewScope'])

    def test_supplied_context_budget_overflow_blocks_without_partial_observations(self):
        for budget in ('units', 'characters'):
            with self.subTest(budget=budget):
                source, inventory = reviewed(['I:1 was clinically unaffected at age 40 years.'], [('F05.2', 0, 'I:1')])
                if budget == 'units':
                    source['units'].extend({'unitId': f'EXTRA{i}', 'text': '', 'kind': 'PARAGRAPH', 'sourceSha256': 'c' * 64}
                                           for i in range(CONTEXT_MAX_UNITS - len(source['units']) + 1))
                else:
                    source['units'].append({'unitId': 'EXTRA', 'text': 'x' * (CONTEXT_MAX_CHARACTERS + 1),
                                            'kind': 'PARAGRAPH', 'sourceSha256': 'c' * 64})
                package = qualify_assessments(source, inventory, selection())
                result = bind(package)
                self.assertEqual([], package['observations'])
                self.assertEqual([], result['memberStates'])
                issue = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_CLINICAL_CONTEXT_BUDGET_EXCEEDED')
                self.assertEqual(('METHOD_REVIEW', 'BLOCKS_SCORING'), (issue['route'], issue['blockingEffect']))
                self.assertEqual('BUDGET_EXCEEDED', package['contextScan']['status'])
                self.assertEqual(CONTEXT_MAX_UNITS, issue['expected']['maximumUnits'])
                self.assertEqual(CONTEXT_MAX_CHARACTERS, issue['expected']['maximumCharacters'])

    def test_supplied_context_budget_inclusive_boundary_preserves_reviewed_positive(self):
        source, inventory = reviewed(['I:1 was clinically unaffected at age 40 years.'], [('F05.2', 0, 'I:1')])
        source['units'].extend({'unitId': f'EXTRA{i}', 'text': '', 'kind': 'PARAGRAPH', 'sourceSha256': 'c' * 64}
                               for i in range(CONTEXT_MAX_UNITS - len(source['units'])))
        source['units'][-1]['text'] = 'x' * (CONTEXT_MAX_CHARACTERS - sum(len(u['text']) for u in source['units']))
        package = qualify_assessments(source, inventory, selection())
        result = bind(package)
        self.assertEqual([], result['needs'])
        self.assertEqual(40, result['memberStates'][0]['attainedAge'])
        self.assertEqual('WITHIN_BUDGET', package['contextScan']['status'])
        self.assertEqual(CONTEXT_MAX_UNITS, package['contextScan']['suppliedUnits'])
        self.assertEqual(CONTEXT_MAX_CHARACTERS, package['contextScan']['suppliedCharacters'])

    def test_unsupported_later_clinical_events_require_review_without_new_status(self):
        first = 'I:1 was clinically unaffected at age 10 years.'
        for later in ('At visit V2, I:1 developed TEST disease at age 40 years.',
                      'At follow-up, I:1 became affected with TEST disease.',
                      'I:1 was no longer clinically unaffected at age 40 years.',
                      'I:1 had disease onset at age 40 years.'):
            for kind in ('ATTAINED_AGE_BANDS', 'AGE_INDEPENDENT_BINARY_STATUS'):
                with self.subTest(later=later, kind=kind):
                    _, _, package = qualify([first, later], [('F05.2', 0, 'I:1')])
                    result = bind(package, kind=kind)
                    self.assertEqual([], result['memberStates'])
                    self.assertIn('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW', self.reasons(result))
                    self.assertEqual(1, len(result['observations']))
                    self.assertEqual(('UNAFFECTED', 10), (result['observations'][0]['status'], result['observations'][0]['attainedAge']))
                    self.assertTrue(any(later.rstrip('.') == ref['quote'] for n in result['needs'] for ref in n['sourceLocators']))

    def test_local_pronoun_followup_cannot_silently_change_status(self):
        first = 'I:1 was clinically unaffected at age 10 years.'
        for later in ('At follow-up, she became affected with TEST disease.',
                      'She developed TEST disease at age 40 years.'):
            with self.subTest(later=later):
                _, _, package = qualify([first + ' ' + later], [('F05.2', 0, 'I:1', first, first)])
                result = bind(package)
                self.assertEqual([], result['memberStates'])
                context = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW')
                self.assertEqual('UNIQUE_PRECEDING_NATIVE_SUBJECT_REQUIRES_REVIEW', context['actual']['scopeBasis'])
                self.assertEqual(2, len(context['sourceLocators']))

    def test_observation_referent_disease_restriction_cannot_hide_outside_quote(self):
        first = 'I:1 was clinically unaffected at age 40 years.'
        for restriction in ('This status refers to OTHER disease.', 'This examination was specific to OTHER disease.'):
            with self.subTest(restriction=restriction):
                _, _, package = qualify([first + ' ' + restriction], [('F05.2', 0, 'I:1', first, first)])
                result = bind(package)
                self.assertEqual([], result['memberStates'])
                context = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW')
                self.assertEqual(['DISEASE_SCOPE'], context['actual']['unreviewedComponents'])
                self.assertEqual(2, len(context['sourceLocators']))

    def test_explicit_member_disease_restriction_is_scoped_across_units(self):
        first = 'I:1 was clinically unaffected at age 40 years.'
        restriction = 'For I:1, this status refers to OTHER disease.'
        _, _, package = qualify([first, restriction], [('F05.2', 0, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        context = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW')
        self.assertEqual('EXPLICIT_MEMBER_OBSERVATION_REFERENT', context['actual']['scopeBasis'])
        self.assertEqual('U2', context['sourceLocators'][0]['unitId'])

    def test_context_review_does_not_assign_another_person_or_molecular_method(self):
        first = 'I:1 was clinically unaffected at age 40 years.'
        for unrelated in ('At follow-up, II:2 developed OTHER disease.',
                          'I:1’s mother developed OTHER disease.',
                          'I:1 underwent genetic testing for c.1A>G.',
                          'II:2 was examined. She developed OTHER disease.'):
            with self.subTest(unrelated=unrelated):
                _, _, package = qualify([first + ' ' + unrelated], [('F05.2', 0, 'I:1', first, first)],
                                         members=('I:1', 'II:2'))
                result = bind(package)
                self.assertEqual([], result['needs'])
                self.assertEqual(40, result['memberStates'][0]['attainedAge'])

    def test_packet_target_is_copied_and_bound_to_current_qualified_selection(self):
        text = 'I:1 was clinically unaffected at age 40 years.'
        source, inventory = reviewed([text], [('F05.2', 0, 'I:1')])
        target = selection()
        package = qualify_assessments(source, inventory, target)
        self.assertEqual(target, package['target'])
        target['allele']['position'] = 101
        self.assertEqual(100, package['target']['allele']['position'])
        for field, value in [('diseaseId', 'OTHER:1'), ('inheritance', 'AR'), ('selectionKey', 'OTHER_SELECTION')]:
            with self.subTest(field=field):
                changed = deepcopy(package)
                changed['target'][field] = value
                result = bind(changed)
                self.assertEqual([], result['memberStates'])
                self.assertIn('PP1_FORMAL_ASSESSMENT_TARGET_MISMATCH', self.reasons(result))
        changed = deepcopy(package)
        changed['target']['allele']['position'] = 101
        self.assertEqual([], bind(changed)['memberStates'])

    def test_target_check_is_optional_for_old_direct_fixtures_without_source_target(self):
        _, _, package = qualify(['I:1 was clinically unaffected at age 40 years.'], [('F05.2', 0, 'I:1')])
        direct = {'sourceIdentity': package['sourceIdentity'], 'clinicalAssessments': package}
        result = bind_formal_observations([direct], {'affectedTerms': ['TEST disease']})
        self.assertEqual([], result['needs'])
        self.assertEqual(40, result['memberStates'][0]['attainedAge'])

    def test_public_source_examination_age_context_is_visible_without_self_approving_f05(self):
        # Exact public PMC9602094 p4 wording, supplied and verified separately.
        # The existing F05 raw facts cite a table; F07 context is not an F05
        # audit. This test asserts source availability, never clinical adoption.
        native = ('The proband’s 52-year-old mother (II-6) and 17-year-old brother (III-5) '
                  'underwent a cardiological examination. According to the ECHO, the heart chambers '
                  'were not dilated and there were no signs of non-compact myocardium.')
        texts = ['II-6 was clinically unaffected.', 'III-5 was clinically unaffected.', native]
        _, _, package = qualify(texts, [('F05.2', 0, 'II-6'), ('F05.2', 1, 'III-5'), ('F07.1', 2, 'II-6')],
                                 members=('II-6', 'III-5'))
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        candidates = [n for n in result['needs'] if n.get('actual', {}).get('sourceAvailability')
                      == 'EXPLICIT_NATIVE_EXAMINATION_PRESENT_NOT_F05_REVIEWED']
        self.assertEqual({17, 52}, {n['actual']['nativeExaminationAgeCandidate'] for n in candidates})
        self.assertTrue(all(n['sourceLocators'] for n in candidates))
        self.assertFalse(any(o['attainedAge'] is not None for o in result['observations']))

    def test_age_field_does_not_self_approve_status_in_the_same_quote(self):
        text = 'I:1 was clinically unaffected at age 40 years.'
        _, _, package = qualify([text], [('F05.4', 0, 'I:1')])
        result = bind(package)
        self.assertEqual([], result['memberStates'])
        self.assertIn('PP1_FORMAL_RELATED_CLINICAL_CONTEXT_REQUIRES_REVIEW', self.reasons(result))
        self.assertTrue(all(o['status'] is None for o in result['observations']))

    def test_source_local_visit_name_does_not_join_different_papers(self):
        _, _, status = qualify(['At visit V1, I:1 was clinically unaffected.'], [('F05.2', 0, 'I:1')])
        source, inventory = reviewed(['At visit V1, I:1 was examined at age 40 years.'], [('F05.4', 0, 'I:1')])
        source['pmcid'] = 'PMC456'
        source['bundleSha256'] = 'd' * 64
        inventory['sourceIdentity'].update(pmcid='PMC456', bundleSha256='d' * 64)
        for fact in inventory['facts']:
            for ref in fact['sourceRefs']:
                ref.update(bundleSha256='d' * 64)
        age = qualify_assessments(source, inventory, selection())
        result = bind_formal_observations([{'sourceIdentity': p['sourceIdentity'], 'clinicalAssessments': p}
                                           for p in (status, age)], {'affectedTerms': ['TEST disease']})
        self.assertEqual([], result['memberStates'])
        self.assertEqual(2, len(result['observations']))

    def test_legacy_rows_or_forged_observation_identity_cannot_supply_binding(self):
        legacy = {'sourceIdentity': {}, 'observations': [
            {'kind': 'clinical', 'value': 'UNAFFECTED', 'memberKey': 'I:1', 'sourceRefs': []},
            {'kind': 'examinationAge', 'value': 40, 'memberKey': 'I:1', 'sourceRefs': []}]}
        result = bind_formal_observations([legacy], {'affectedTerms': ['TEST disease']})
        self.assertEqual([], result['memberStates'])
        self.assertIn('PP1_FORMAL_OBSERVATION_BINDING_REQUIRED', self.reasons(result))
        _, _, package = qualify(['I:1 was clinically unaffected at age 40 years.'], [('F05.2', 0, 'I:1')])
        package['observations'][0]['sourceObservationId'] = 'invented'
        self.assertEqual([], bind(package)['memberStates'])

    def test_legacy_unresolved_f05_without_typed_status_still_requires_replay(self):
        legacy = {'observations': [], 'unresolved': [{'fieldId': 'F05.2',
                  'reasonCode': 'PP1_TYPED_SEMANTICS_UNRESOLVED', 'claimId': 'old-raw-claim',
                  'sourceValue': 'I:1 was clinically unaffected at age 40 years.',
                  'sourceRefs': [{'unitId': 'U1', 'quote': 'I:1 was clinically unaffected at age 40 years.'}]}]}
        for kind in ('ATTAINED_AGE_BANDS', 'AGE_INDEPENDENT_BINARY_STATUS'):
            with self.subTest(kind=kind):
                result = bind_formal_observations([legacy], {'affectedTerms': ['TEST disease'],
                    'formalModel': {'phenotypeModel': {'kind': kind}}})
                self.assertEqual([], result['memberStates'])
                issue = next(n for n in result['needs'] if n['reasonCode'] == 'PP1_FORMAL_OBSERVATION_BINDING_REQUIRED')
                self.assertEqual('BLOCKS_SCORING', issue['blockingEffect'])
                self.assertEqual('old-raw-claim', issue['knownFacts'][0]['claimId'])
                self.assertTrue(issue['sourceLocators'])

    def table(self, status_header='TEST disease status at last examination', age_header='Age at last examination (years)',
              *, merged=False, unreviewed_header=False):
        labels = ['Individual ID', status_header, age_header, 'I:1', 'unaffected', '40']
        source, inventory = reviewed(['An index table follows.'], [])
        source['units'].extend({'unitId': f'T{i}', 'text': value, 'kind': 'TABLE_CELL', 'sourceSha256': 'c' * 64}
                               for i, value in enumerate(labels))
        cells = [{'unitId': f'T{i}', 'text': value, 'sourceCellIndex': i % 3, 'header': i < 3,
                  'rowspan': 1, 'colspan': 2 if merged and i == 1 else 1} for i, value in enumerate(labels)]
        source['main']['tables'] = [{'rows': [{'cells': cells[:3]}, {'cells': cells[3:]}]}]
        inventory['inputScope']['unitIds'].extend(f'T{i}' for i in range(6) if not (unreviewed_header and i == 1))
        family = digest(canonical(['PMC123', 'b' * 64, 'F1']))
        member = digest(canonical(['PMC123', 'b' * 64, 'F1', 'I:1']))
        inventory['facts'] = [{'claimId': digest(canonical(value)), 'familyKey': family, 'memberKey': member,
            'raw': {'fieldId': field, 'familyLabel': 'F1', 'memberLabel': 'I:1', 'sourceValue': value},
            'sourceAudit': {'verdict': 'SUPPORTED'}, 'sourceSupport': 'SOURCE_CHECKED_MODEL_SUPPORTED',
            'issues': [], 'sourceRefs': [{'unitId': f'T{index}', 'quote': value}]}
            for index, field, value in [(4, 'F05.2', 'unaffected'), (5, 'F05.4', '40')]]
        return qualify_assessments(source, inventory, selection())

    def test_explicit_same_examination_table_headers_bind_across_cells(self):
        result = bind(self.table())
        self.assertEqual([], result['needs'])
        self.assertEqual(('UNAFFECTED', 40), (result['memberStates'][0]['status'], result['memberStates'][0]['attainedAge']))
        self.assertEqual({'T0', 'T1', 'T2', 'T3', 'T4', 'T5'}, {r['unitId'] for r in result['memberStates'][0]['sourceRefs']})

    def test_common_table_row_with_generic_status_or_age_is_not_observation_proof(self):
        for status_header, age_header in [('Phenotype', 'Age at last examination (years)'),
                                          ('TEST disease status at visit V1', 'Age at visit V2 (years)'),
                                          ('TEST disease status at last examination', 'Age (years)'),
                                          ('TEST disease status at last examination', 'Age at onset (years)')]:
            with self.subTest(headers=(status_header, age_header)):
                self.assertEqual([], bind(self.table(status_header, age_header))['memberStates'])
        self.assertEqual([], bind(self.table(merged=True))['memberStates'])
        self.assertEqual([], bind(self.table(unreviewed_header=True))['memberStates'])


if __name__ == '__main__':
    unittest.main()
