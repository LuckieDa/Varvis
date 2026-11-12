"""Synthetic risk controls for source semantics; no model, network or scoring."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from test_bundle_qualification import article, registered, candidate
from test_core_evaluation_intake import config_for
from bundle_model_contract import review
from qualify_bundle_candidates import qualify
from core_material_extraction import write_package
from core_material_review import review_package
from core_context_review import context_package
from core_material_contract import validate, digest as object_digest
from model_literature_contract import digest, parse


class CaseSemanticsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.count = 0

    def package(self, extra='', raw=None, wrong_subject=False, configured=False):
        self.count += 1; root = self.root / str(self.count); root.mkdir()
        raw = article() if raw is None else raw
        raw = raw.replace(b'Case A was studied.', ('Case A was studied. ' + extra).encode())
        _, source = registered(root, raw)
        proposal = candidate(source)
        if wrong_subject:
            wrong = deepcopy(proposal['cases'][0]['fields'][0]); wrong['subject'] = 'FATHER'
            proposal['cases'][0]['fields'].append(wrong)
        checked = review(source, proposal)
        checked['workUnit'] = {'targetUnitIds': [checked['cases'][0]['caseCitation']['unitId']]}
        native = qualify(source, [checked])
        write_package(source, native, [checked], root / 'raw', data_class='SYNTHETIC')
        review_package(source, root / 'raw', digest((root / 'raw/index.json').read_bytes()), root / 'review')
        config = parse((root / config_for(root)['path']).read_bytes()) if configured else None
        idx = context_package(source, root / 'review', digest((root / 'review/index.json').read_bytes()),
                              root / 'context', root, config, complete_events=True)
        row = idx['cases'][0]; doc = parse((root / 'context' / row['path']).read_bytes())
        ledger = parse((root / 'context' / row['ledgerPath']).read_bytes())
        report = parse((root / 'context' / row['reportPath']).read_bytes())
        validate(doc, root / 'context', ledger, object_digest(ledger))
        self.assertEqual(0, idx['modelCalls']); self.assertEqual(0, idx['networkCalls'])
        return doc, ledger, report, idx, native

    def test_bad_model_subject_does_not_poison_native_family(self):
        doc, _, _, idx, native = self.package(wrong_subject=True, configured=True)
        self.assertIn('LITERATURE_FIELD_CONFLICT:BUNDLE_SUBJECT_NOT_VERIFIED', native['cases'][0]['issues'])
        self.assertEqual('COMPATIBLE', doc['groups']['family']['compatibility']['value'])
        self.assertEqual('READY_FOR_EVALUATION', idx['cases'][0]['readiness'])

    def test_real_source_family_conflict_still_vetoes(self):
        for sentence in ('The variant was inherited from the mother.', 'An affected sibling was also examined.',
                         'The father was not tested.'):
            with self.subTest(sentence=sentence):
                doc, _, _, idx, _ = self.package(sentence, wrong_subject=True, configured=True)
                self.assertNotEqual('READY_FOR_EVALUATION', idx['cases'][0]['readiness'])
                self.assertNotEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])

    def test_case_owned_passive_clauses_and_mri(self):
        doc, ledger, report, _, _ = self.package('Hypotonia was noted in all four limbs; however, reflexes were normal. '
            'In addition, there was appendicular and gait ataxia (Supplementary Video 3). '
            'MRI of the brain revealed mild isolated cerebellar atrophy (Figure 1E and F).')
        self.assertEqual(['Synthetic trait', 'Hypotonia', 'appendicular and gait ataxia', 'mild isolated cerebellar atrophy'],
                         doc['groups']['phenotype']['positive']['value'])
        self.assertIn('/groups/phenotype/positive', ledger['checks'])
        self.assertTrue(report['caseSemantics']['proofs'])

    def test_active_child_clause_retains_event_age_without_disease_onset(self):
        doc, *_ = self.package('In addition, the child had global developmental delay and was noted to have gait imbalance '
                              'when the child started walking at 4 years of age.')
        self.assertIn('global developmental delay', doc['groups']['phenotype']['positive']['value'])
        self.assertNotEqual('VERIFIED', doc['groups']['phenotype']['onset']['verification'])

    def test_xray_and_followup_explicit_terms_with_polarity_and_actor(self):
        doc, *_ = self.package('The X-ray of both forearms showed superior radioulnar synostosis. '
                              'Follow up at 1 year revealed persisting anemia with severe neutropenia and thrombocytopenia.')
        values = doc['groups']['phenotype']['positive']['value']
        self.assertIn('radioulnar synostosis', values); self.assertIn('thrombocytopenia', values)
        for text in ('The X-ray of both forearms showed no synostosis.',
                     'Follow up at 1 year revealed possible thrombocytopenia.',
                     'The mother was examined. Follow up at 1 year revealed thrombocytopenia.'):
            doc, *_ = self.package(text)
            self.assertEqual(['Synthetic trait'], doc['groups']['phenotype']['positive']['value'])

    def test_event_times_do_not_become_disease_onset(self):
        doc, *_ = self.package('The child had two episodes of seizures at 7 days and 4 years of age.')
        self.assertIn('two episodes of seizures at 7 days and 4 years of age', doc['groups']['phenotype']['positive']['value'])
        self.assertNotEqual('VERIFIED', doc['groups']['phenotype']['onset']['verification'])

    def test_negation_uncertainty_and_other_people_not_promoted(self):
        for sentence in ('The child had no ataxia.', 'The child had possible ataxia.',
                         'MRI of the brain revealed no cerebellar atrophy.', 'The mother had ataxia.',
                         'There was a suggestion of ataxia.', 'There was parental ataxia.',
                         'There was an absence of seizures.', 'There was neither ataxia nor hypotonia.',
                         'The child had normal development.', 'Maternal ataxia was noted in both legs.'):
            with self.subTest(sentence=sentence):
                doc, *_ = self.package(sentence)
                self.assertEqual(['Synthetic trait'], doc['groups']['phenotype']['positive']['value'])

    def test_normal_and_abnormal_mixed_sentence_is_not_flat_positive(self):
        doc, *_ = self.package('On examination, the child had normal pupils and nystagmus.')
        self.assertEqual(['Synthetic trait'], doc['groups']['phenotype']['positive']['value'])

    def test_passive_finding_after_other_person_needs_explicit_subject_reset(self):
        doc, *_ = self.package('The mother was examined. Hypotonia was noted in all four limbs.')
        self.assertNotIn('Hypotonia', doc['groups']['phenotype']['positive']['value'])
        doc, *_ = self.package('The mother was examined. The child was examined. Hypotonia was noted in all four limbs.')
        self.assertIn('Hypotonia', doc['groups']['phenotype']['positive']['value'])

    def test_other_case_and_discussion_never_borrowed(self):
        for raw in (article().replace(b'Case A was studied.', b'Case A was studied. Case B was examined. Hypotonia was noted in all four limbs.'),
                    article().replace(b'<title>Results</title>', b'<title>Discussion</title>')):
            doc, *_ = self.package('Hypotonia was noted in all four limbs.', raw=raw)
            self.assertNotIn('Hypotonia', doc['groups']['phenotype']['positive']['value'] or [])

    def test_prior_negative_keeps_veto_on_new_positive(self):
        doc, ledger, report, *_ = self.package('The proband had no hypotonia. Hypotonia was noted in all four limbs.')
        self.assertEqual('CONFLICT', doc['groups']['phenotype']['positive']['verification'])
        self.assertNotIn('/groups/phenotype/positive', ledger['checks'])
        self.assertIn('POSITIVE_NEGATIVE_SOURCE_CONFLICT', report['caseSemantics']['issues'])

    def test_passive_negative_is_explicit_and_conflicts_are_preserved(self):
        doc, *_ = self.package('No seizures were observed. No hypotonia was noted in all four limbs.')
        self.assertIn('hypotonia', doc['groups']['phenotype']['negative']['value'])
        # Unsupported plural grammar is retained in source, never claimed absent.
        self.assertNotIn('seizures', doc['groups']['phenotype']['negative']['value'])
        doc, ledger, *_ = self.package('Hypotonia was noted in all four limbs. No hypotonia was observed.')
        self.assertEqual('CONFLICT', doc['groups']['phenotype']['positive']['verification'])
        self.assertNotIn('/groups/phenotype/positive', ledger['checks'])

    def figure(self, panel='D', rid='f1', caption='D: Pedigree of Family 2.', opening=None):
        opening = opening or 'Case A is a 5-year-old boy (<xref ref-type="fig" rid="' + rid + '">Figure 1</xref>' + panel + '). Case A was studied.'
        return article().replace(b'Case A was studied.', opening.encode()).replace(b'</body>',
            ('<fig id="f1"><caption><p>A: Pedigree of Family 1. B: The mother has ataxia. ' + caption + '</p></caption></fig></body>').encode())

    def test_jats_case_panel_family_link_and_provenance(self):
        doc, ledger, *_ = self.package(raw=self.figure())
        field = doc['groups']['case']['familyId']
        self.assertEqual('Family 2', field['value']); self.assertEqual('VERIFIED', field['verification'])
        self.assertEqual(2, len(field['citations']))
        self.assertIn('JATS_CASE_XREF_EXACT_PEDIGREE_PANEL', ledger['checks']['/groups/case/familyId']['method'])
        self.assertNotIn('ataxia', doc['groups']['phenotype']['positive']['value'])

    def test_wrong_panel_or_rid_or_plain_text_not_family_proof(self):
        for raw in (self.figure(panel='B'), self.figure(rid='f2'),
                    self.figure().replace(b'<xref ref-type="fig" rid="f1">Figure 1</xref>D', b'Figure 1D')):
            with self.subTest(sha=digest(raw)):
                doc, *_ = self.package(raw=raw)
                self.assertNotEqual('VERIFIED', doc['groups']['case']['familyId']['verification'])

    def test_duplicate_figure_id_or_panel_ambiguous(self):
        for raw in (self.figure().replace(b'</body>', b'<fig id="f1"><caption>D: Pedigree of Family 9.</caption></fig></body>'),
                    self.figure(caption='D: Pedigree of Family 2. D: Pedigree of Family 9.')):
            with self.subTest(sha=digest(raw)):
                doc, *_ = self.package(raw=raw)
                self.assertNotEqual('VERIFIED', doc['groups']['case']['familyId']['verification'])

    def test_later_relative_figure_not_proband_family(self):
        raw = self.figure(opening='Case A was studied. The mother (<xref ref-type="fig" rid="f1">Figure 1</xref>D) was examined.')
        doc, *_ = self.package(raw=raw)
        self.assertNotEqual('VERIFIED', doc['groups']['case']['familyId']['verification'])

    def test_literal_family_history_not_compatibility_or_parentage(self):
        raw = article().replace(b'<td>None</td>', b'<td>Unknown</td>')
        doc, *_ = self.package('There was no family history of similar symptoms.', raw=raw)
        self.assertNotEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])

    def test_explicit_owned_history_does_not_require_duplicate_table_cell(self):
        raw = article().replace(b'<tr><td>Family history</td><td>None</td></tr>', b'')
        doc, ledger, report, *_ = self.package('There was no family history of similar symptoms.', raw=raw, configured=True)
        self.assertEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
        self.assertEqual('COMPATIBLE', doc['groups']['family']['compatibility']['value'])
        self.assertTrue(report['familyReview']['explicitHistoryProofs'])
        self.assertIn('/groups/family/compatibility', ledger['checks'])

    def test_unassigned_nonspecific_history_does_not_override_case_proof(self):
        raw = article().replace(b'</sec></body>', b'<p>Three mothers reported recurrent miscarriages.</p></sec></body>')
        doc, _, report, *_ = self.package(raw=raw, configured=True)
        self.assertEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
        self.assertTrue(report['familyReview']['unassignedHistory'])
        self.assertIn('UNASSIGNED_NONSPECIFIC_HISTORY_NOT_CASE_CONFLICT', report['familyReview']['warnings'])

    def test_unassigned_history_does_not_manufacture_missing_case_proof(self):
        raw = article().replace(b'<tr><td>Family history</td><td>None</td></tr>', b'')
        raw = raw.replace(b'</sec></body>', b'<p>Three mothers reported recurrent miscarriages.</p></sec></body>')
        doc, _, report, *_ = self.package(raw=raw, configured=True)
        self.assertNotEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
        self.assertEqual('DENOVO_FAMILY_HISTORY_NOT_ESTABLISHED', report['familyReview']['reasonCode'])
        self.assertTrue(report['familyReview']['unassignedHistory'])

    def test_explicit_unaffected_parents_can_establish_ad_family_compatibility(self):
        raw = article().replace(b'<tr><td>Family history</td><td>None</td></tr>', b'')
        for sentence in ('Both parents were unaffected.', 'Both parents were clinically unaffected.',
                         'The mother and father were unaffected.'):
            with self.subTest(sentence=sentence):
                doc, ledger, report, idx, _ = self.package(sentence, raw=raw, configured=True)
                self.assertEqual('COMPATIBLE', doc['groups']['family']['compatibility']['value'])
                self.assertEqual('READY_FOR_EVALUATION', idx['cases'][0]['readiness'])
                self.assertEqual('EXPLICIT_UNAFFECTED_PARENTS_AD', report['familyReview']['qualificationBasis'])
                self.assertIsNone(doc['groups']['family']['history']['value'])
                self.assertIn('/groups/family/compatibility', ledger['checks'])

    def test_parent_negative_testing_not_unaffected_clinical_evidence(self):
        raw = article().replace(b'<tr><td>Family history</td><td>None</td></tr>', b'')
        for sentence in ('Both parents tested negative.', 'The mother was unaffected.',
                         'Both parents were possibly unaffected.', 'Both parents were not unaffected.',
                         'Patient B had unaffected parents.'):
            with self.subTest(sentence=sentence):
                doc, _, report, *_ = self.package(sentence, raw=raw, configured=True)
                self.assertNotEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
                self.assertFalse(report['familyReview']['qualified'])

    def test_unaffected_parents_do_not_overrule_unknown_table_or_target_conflict(self):
        for raw, extra in ((article().replace(b'<td>None</td>', b'<td>Unknown</td>'), ''),
                           (article(), 'The patient had an affected sibling.')):
            doc, _, report, *_ = self.package('Both parents were unaffected. ' + extra, raw=raw, configured=True)
            self.assertNotEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
            self.assertFalse(report['familyReview']['qualified'])

    def test_named_target_and_unassigned_special_family_still_need_review(self):
        for sentence in ('Patient A had an affected sibling.', 'Three mothers had mosaicism.',
                         'Three mothers were affected.', 'Patient A had a mother with congenital abnormalities.',
                         'All mothers had congenital abnormalities.'):
            raw = article().replace(b'</sec></body>', ('<p>'+sentence+'</p></sec></body>').encode())
            doc, _, report, *_ = self.package(raw=raw, configured=True)
            self.assertNotEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
            self.assertFalse(report['familyReview']['qualified'])

    def test_unaffected_parent_basis_is_not_transferred_to_other_inheritance(self):
        from core_context_review import family_review
        from literature_case_structure import tables
        from qualify_literature_cases import source_case
        raw = article().replace(b'<tr><td>Family history</td><td>None</td></tr>', b'')
        raw = raw.replace(b'Case A was studied.', b'Case A was studied. Both parents were unaffected.')
        _, source = registered(self.root, raw)
        cases = tables(source['main']); legacy = [source_case(source['main'], cases, cases[0], [])]
        for inheritance in (None, 'AR', 'X_LINKED'):
            self.assertFalse(family_review(source['main'], 'A', legacy, inheritance=inheritance)['qualified'])

    def test_discussion_risk_is_not_an_observed_family_event(self):
        raw = article().replace(b'</body>', b'<sec><title>Discussion</title><p>Mothers may have recurrent miscarriages.</p></sec></body>')
        doc, _, report, *_ = self.package(raw=raw, configured=True)
        self.assertEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
        self.assertEqual([], report['familyReview']['unassignedHistory'])

    def test_other_named_case_history_not_borrowed_or_conflicting(self):
        raw = article().replace(b'</sec></body>', b'<p>Patient B had an affected sibling.</p></sec></body>')
        doc, _, report, *_ = self.package(raw=raw, configured=True)
        self.assertEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
        self.assertEqual([], report['familyReview']['unassignedHistory'])

    def test_other_explicit_case_range_and_assay_caption_do_not_create_family_concern(self):
        extra = (b'<p>Analysis for Cases 2-4 did not identify mosaicism in either parent.</p>'
                 b'<fig><caption><p>Sequences of SNPs in parents and affected of Case A showing consistency with parentage.</p></caption></fig>')
        raw = article().replace(b'</sec></body>', extra + b'</sec></body>')
        doc, _, report, *_ = self.package(raw=raw, configured=True)
        self.assertEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
        self.assertEqual([], report['familyReview']['unassignedHistory'])

    def test_table_unknown_or_missing_history_never_becomes_negative(self):
        for replacement in (b'<td>Unknown</td>', b'<td>Not reported</td>'):
            doc, _, report, *_ = self.package(raw=article().replace(b'<td>None</td>', replacement), configured=True)
            self.assertNotEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
            self.assertEqual('DENOVO_FAMILY_HISTORY_NOT_ESTABLISHED', report['familyReview']['reasonCode'])

    def test_owned_sibling_recurrence_and_unknown_prose_still_block(self):
        for extra in ('The patient had an affected sibling.', 'The mother had unexplained symptoms.'):
            doc, _, report, *_ = self.package(extra, configured=True)
            self.assertNotEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
            self.assertFalse(report['familyReview']['qualified'])

    def test_family_reader_cannot_assert_absence_when_scope_is_too_large(self):
        raw = article().replace(b'</sec></body>', (b'<p>Unassigned cohort text.</p>' * 97) + b'</sec></body>')
        doc, _, report, *_ = self.package(raw=raw, configured=True)
        self.assertNotEqual('VERIFIED', doc['groups']['family']['compatibility']['verification'])
        self.assertEqual('DENOVO_FAMILY_REVIEW_SCOPE_LIMIT', report['familyReview']['reasonCode'])


if __name__ == '__main__':
    unittest.main()
