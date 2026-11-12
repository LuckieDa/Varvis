"""Synthetic source integration tests; no real network, credentials or adoption."""
from copy import deepcopy
from pathlib import Path
import unittest
from unittest.mock import patch

from process_pp1_paper import process, initial_units, public_spellings
from model_literature_contract import parse
from test_pp1_reading import Pipeline, Resolver
from test_pp1_segregation import selection, ledger, context


class InitialPackets(unittest.TestCase):
    def test_printed_alias_spacing_preserved_not_an_allele_resolution(self):
        text = 'FBN1 c.6615A > G, p(Glu2205 = ) and c.6615A>C.'
        self.assertEqual(['c.6615A > G', 'p(Glu2205 = )'],
                         public_spellings(text, ['c.6615A>G', 'p.Glu2205=']))
        self.assertEqual([], public_spellings('c.16615A>G c.6615A>GG', ['c.6615A>G']))
        self.assertEqual([], public_spellings('c.6615A>C', ['c.6615A>G']))

    def select(self, limit, *, failure=None, alias='c.1A>G'):
        source = {'units': [{'unitId': str(i), 'text': text} for i, text in enumerate(
            ['c.1A>GG unrelated', 'c.1A>G table', 'header', 'c.1A>G narrative', 'c.1A>G footnote', 'note'])]}
        packets = [
            {'targetUnitIds':['1'], 'contextUnitIds':['2']},
            {'targetUnitIds':['3'], 'contextUnitIds':[]},
            {'targetUnitIds':['4'], 'contextUnitIds':['5']},
            {'targetUnitIds':['0'], 'contextUnitIds':[]}]
        def preflight(ids):
            if failure and ids != list('012345'):
                raise ValueError(failure)
            if len(ids) > limit:
                raise ValueError('PP1_INPUT_LIMIT_NOT_TRUNCATED')
        with patch('process_pp1_paper.initial_scope', return_value=list('012345')), \
             patch('process_pp1_paper.plans', return_value=packets):
            return initial_units(source, {'variantAliases':[alias]}, preflight)

    def test_table_narrative_and_notes_remain_together_when_they_fit(self):
        self.assertEqual(list('12345'), self.select(5))

    def test_packet_never_partially_included_to_fill_remaining_space(self):
        self.assertEqual(list('123'), self.select(4))

    def test_oversized_first_packet_does_not_hide_smaller_complete_packet(self):
        self.assertEqual(['3'], self.select(1))

    def test_focused_scope_used_even_if_full_scope_fits(self):
        self.assertEqual(list('12345'), self.select(6))

    def test_no_exact_anchor_uses_existing_scope(self):
        self.assertEqual(list('012345'), self.select(6, alias='c.1A>C'))

    def test_non_size_failure_is_not_silently_skipped(self):
        with self.assertRaisesRegex(ValueError, 'TARGET_NOT_IN_PUBLIC_SOURCE'):
            self.select(5, failure='TARGET_NOT_IN_PUBLIC_SOURCE')

    def test_all_packets_too_large_is_explicit(self):
        with self.assertRaisesRegex(ValueError, 'PP1_INPUT_LIMIT_NOT_TRUNCATED'):
            self.select(0)


class PaperTask(unittest.TestCase):
    def setUp(self):
        self.fixture = Pipeline()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.binding = {'schemaVersion': 'pp1-analysis-binding-v1', 'selection': selection(), 'ledger': ledger()}

    def run_paper(self, name='paper', **kwargs):
        f = self.fixture
        return process(f.library, 'PMC123', f.pin, kwargs.pop('target', f.target), self.binding,
                       kwargs.pop('context', context()), Resolver(), f.config, f.cfg, f.root/'budget', f.root/name,
                       transport=f.transport, **kwargs)

    def test_initial_extract_audit_score_and_readonly_cache(self):
        first = self.run_paper(allow_model=True)
        self.assertEqual(('EVALUATED', 'MET', 2), (first['status'], first['evaluation']['status'], first['modelAttempts']))
        self.assertEqual(2, self.fixture.transports)
        second = self.run_paper('cached')
        self.assertEqual(first['evaluation'], second['evaluation'])
        self.assertEqual(0, second['modelAttempts'])
        self.assertNotIn('acceptedArtifacts', second['reading'])
        self.assertNotIn(str(self.fixture.root), str(second))

    def test_disabled_model_is_explicit_not_absence(self):
        result = self.run_paper()
        self.assertEqual('PARTIAL', result['status'])
        self.assertEqual('PP1_MODEL_DISABLED_CACHE_MISSING', result['stopReason'])
        self.assertEqual(0, self.fixture.transports)

    def test_manual_review_task_copy_uses_short_name_and_unchanged_bytes(self):
        from literature_manual_review import VERSION
        from model_literature_contract import canonical, digest

        document = {'schemaVersion': VERSION, 'binding': {'stage': 'SOURCE_REVIEW',
                    'target': self.fixture.target, 'pmcid': 'PMC123', 'bundleSha256': self.fixture.pin}}
        library_name = digest(canonical(document)) + '.json'
        with patch('pp1_reviewed_cache.manual_matches', return_value=[(Path(library_name), document)]):
            self.run_paper()
        copied, = (self.fixture.root/'paper/manual-reviews').iterdir()
        self.assertLess(len(copied.name), len(library_name))
        self.assertEqual(canonical(document), copied.read_bytes())

    def test_call_limit_preserves_extraction_and_can_reuse_it(self):
        first = self.run_paper(allow_model=True, maximum_calls=1)
        self.assertEqual('PP1_CALL_LIMIT', first['stopReason'])
        self.assertTrue((self.fixture.root/'paper/initial-extraction/artifact.json').is_file())
        second = self.run_paper('continued', allow_model=True)
        self.assertEqual('MET', second['evaluation']['status'])
        self.assertEqual(1, second['modelAttempts'])
        self.assertEqual(2, self.fixture.transports)

    def test_preexisting_audit_is_replayed_not_rescored_from_saved_json(self):
        self.run_paper(allow_model=True)
        manifest = parse((self.fixture.root/'paper/artifacts.json').read_bytes())
        result = self.run_paper('resumed', accepted_artifacts=manifest['acceptedArtifacts'])
        self.assertEqual('MET', result['evaluation']['status'])
        self.assertEqual(0, result['modelAttempts'])

    def test_unknown_ledger_never_becomes_zero(self):
        self.binding['ledger'].update(status='UNKNOWN', pp4Points=None)
        result = self.run_paper(allow_model=True)
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertEqual('EXTERNAL_REQUIREMENT', result['stopReason'])
        self.assertIn('PP1_PP4_LEDGER_UNKNOWN', [n['reasonCode'] for n in result['evaluation']['needs']])

    def test_missing_knowledge_keeps_facts_and_explicit_blocker(self):
        result = self.run_paper(allow_model=True, context={'status':'KNOWLEDGE_MISSING', 'reasonCode':'PP1_QUALIFIED_CONTEXT_UNAVAILABLE'})
        self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
        self.assertTrue(result['reading']['qualification']['observations'])
        self.assertEqual(2, self.fixture.transports)

    def test_cancellation_before_spend(self):
        result = self.run_paper(allow_model=True, cancelled=lambda: True)
        self.assertEqual('PP1_USER_CANCELLED', result['stopReason'])
        self.assertEqual(0, self.fixture.transports)

    def test_unused_annotation_spelling_is_not_sent_to_model(self):
        target = deepcopy(self.fixture.target)
        target['variantAliases'].append('p.Unknown1Gly')
        result = self.run_paper(allow_model=True, target=target)
        self.assertEqual(self.fixture.target, result['target'])
        self.assertEqual('MET', result['evaluation']['status'])

    def test_ledger_other_disease_rejected_before_transport(self):
        self.binding['ledger']['scope']['diseaseId'] = 'TEST:2'
        with self.assertRaisesRegex(ValueError, 'PP1_ANALYSIS_LEDGER_MISMATCH'):
            self.run_paper(allow_model=True)
        self.assertEqual(0, self.fixture.transports)


if __name__ == '__main__': unittest.main()
