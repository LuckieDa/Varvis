"""Offline adversarial source/subject/transport tests, not patient validation."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from pp1_literature_contract import *
from run_pp1_literature_model import run, replay
from test_bundle_model import configuration, response
from test_literature_acquisition import jats
from prepare_literature import prepare
from acquire_public_literature import source_bundle
from paper_library_registration import register
from bundle_model_contract import load


class FamilyFacts(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.library = self.root / 'library'; self.library.mkdir()
        sentence = ('Family F1: Proband III-4 and father II-5 have GENE1 c.1A&gt;G. '
                    'Mother II-6 is healthy. II-6 tested by WGS: +. '
                    'No GENE1 c.1A&gt;G was detected in II-6. '
                    'II-5 is the father of III-4. III-4 is affected. '
                    'II-5 has a grandson IV-1.')
        raw = jats(False).replace(b'A public source.', sentence.encode() + b'</p><p>Additional source context.')
        files, bundle = source_bundle({'pmcid': 'PMC123'}, raw, prepare(raw, 'PMC123', digest(raw)), None, False)
        result = register(self.library, 'PMC123', files, bundle, {'sha256': digest(raw),
            'acquisitionUrl': 'https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML'}, criteria=('PP1',))
        self.pin = result['bundleSha256']; self.source = load(self.library, 'PMC123', self.pin, criterion='PP1')
        self.unit = next(u for u in self.source['units'] if 'Family F1:' in u['text'])
        self.ids = [self.unit['unitId']]
        self.target = {'geneSymbol': 'GENE1', 'variantAliases': ['c.1A>G']}
        self.c = configuration(); self.cfg = self.root / 'config.json'
        self.cfg.write_bytes(canonical(self.c)); (self.root / 'key.txt').write_bytes(b'synthetic-not-secret')

    def q(self, text): return {'unitId': self.unit['unitId'], 'quote': text}
    def fact(self, field='F06.3', member='II-6', value='No GENE1 c.1A>G was detected in II-6.'):
        return {'fieldId': field, 'familyLabel': 'F1', 'memberLabel': member, 'relatedMembers': [],
                'relationKind': 'NONE', 'sourceValue': value, 'citations': [self.q(value)]}
    def candidate(self):
        return {'schemaVersion': VERSION,
            'families': [{'familyLabel': 'F1', 'anchor': self.q('Family F1:'),
                'members': [{'memberLabel': m, 'anchor': self.q(m)} for m in ('III-4', 'II-5', 'II-6')]}],
            'facts': [self.fact()], 'coverage': 'REVIEWED_SUPPLIED_SCOPE',
            'groupChecks': [{'group': 'F%02d' % i, 'outcome': 'UNRESOLVED'} for i in range(1, 11)]}
    def valid(self):
        c = self.candidate()
        # Short member labels recur; exact nonambiguous longer anchors are needed.
        for m, q in zip(c['families'][0]['members'],
            ('Proband III-4', 'father II-5 have', 'Mother II-6 is healthy.')):
            m['anchor'] = self.q(q)
        return c
    def audit(self, candidate):
        return {'schemaVersion': AUDIT_VERSION,
            'audits': [{'claimId': claim_id(f), 'verdict': 'SUPPORTED', 'reason': 'The source names the member and target.',
                       'evidence': f['citations']} for f in candidate['facts']],
            'additionalFamilies': [], 'additions': [], 'groupChecks': candidate['groupChecks'],
            'coverage': 'REVIEWED_SUPPLIED_SCOPE'}
    def checked(self, c=None, a=None):
        c = c or self.valid()
        return audit_check(self.source, c, a or self.audit(c), self.ids, self.target)

    def test_repeated_review_excerpt_uses_unique_original_claim_context(self):
        c=self.valid();a=self.audit(c);a['audits'][0]['evidence']=[self.q('II-6')]
        row=self.checked(c,a)['facts'][0]
        self.assertEqual('SOURCE_CHECKED_MODEL_SUPPORTED',row['sourceSupport'])
        ref=row['auditSourceRefs'][0]
        self.assertEqual('UNIQUE_ORIGINAL_CLAIM_CONTEXT',ref['positionBasis'])
        self.assertEqual('II-6',self.unit['text'][ref['start']:ref['end']])
        self.assertEqual(c['facts'][0]['citations'][0]['quote'],ref['originalClaimQuote'])

    def test_review_excerpt_with_two_original_positions_is_still_rejected(self):
        c=self.valid();c['facts'][0]['citations'].append(self.q('Mother II-6 is healthy.'))
        a=self.audit(c);a['audits'][0]['evidence']=[self.q('II-6')]
        self.assertEqual('REJECTED',self.checked(c,a)['facts'][0]['verification'])

    def test_review_excerpt_absent_or_outside_claim_context_not_repaired(self):
        for quote in ('nonexistent original words','II-5'):
            c=self.valid();a=self.audit(c);a['audits'][0]['evidence']=[self.q(quote)]
            self.assertEqual('REJECTED',self.checked(c,a)['facts'][0]['verification'])

    def test_resolved_quote_does_not_override_challenged_verdict(self):
        c=self.valid();a=self.audit(c);a['audits'][0].update(evidence=[self.q('II-6')],verdict='CONTRADICTED')
        self.assertEqual('UNRESOLVED_OR_CHALLENGED',self.checked(c,a)['facts'][0]['sourceSupport'])

    def test_fact_dictionary_excludes_policy_scores_and_requires_ten_groups(self):
        c = self.valid(); c['facts'][0]['fieldId'] = 'D04'
        with self.assertRaises(ValueError): check_candidate(c)
        c = self.valid(); c['groupChecks'].pop()
        with self.assertRaisesRegex(ValueError, 'GROUP_COVERAGE'): check_candidate(c)
    def test_score_and_unknown_keys_rejected(self):
        c = self.valid(); c['score'] = 1
        with self.assertRaises(ValueError): check_candidate(c)
    def test_exact_missing_negative_not_inferred_from_test(self):
        c = self.valid(); c['facts'] = [self.fact('F06.2', value='II-6 tested by WGS: +.')]
        r = self.checked(c)
        self.assertTrue(any(n['fieldId'] == 'F06.3' and n['knownRelatedFacts'] for n in r['readingNeeds']))
        self.assertTrue(all(n['resolutionStatus'] == 'NOT_YET_REVIEWED' for n in r['readingNeeds']))
    def test_plus_is_not_positive_even_when_reviewer_agrees(self):
        c = self.valid(); c['facts'] = [self.fact(value='+')]
        r = self.checked(c)
        self.assertIn('TEST_MARK_IS_NOT_TARGET_RESULT', r['facts'][0]['issues'])
        self.assertNotEqual('SOURCE_CHECKED_MODEL_SUPPORTED', r['facts'][0]['sourceSupport'])
    def test_source_verified_does_not_mean_scoring_eligible(self):
        r = self.checked()
        self.assertEqual('SOURCE_CHECKED_MODEL_SUPPORTED', r['facts'][0]['sourceSupport'])
        self.assertEqual('UNVERIFIED', r['facts'][0]['verification'])
        self.assertFalse(r['scoringEligible']); self.assertFalse(r['businessPublishable'])
    def test_repeated_label_preserves_all_positions_not_multiple_members(self):
        r = source_check(self.source, self.candidate(), self.ids)
        self.assertEqual(3, len(r['families']['F1']['members']))
        self.assertGreater(len(r['families']['F1']['members']['III-4']['occurrences']), 1)
        c = self.valid(); c['facts'][0]['sourceValue'] = 'III-4'; c['facts'][0]['citations'] = [self.q('III-4')]
        with self.assertRaises(ValueError): source_check(self.source, c, self.ids)
    def test_invented_quotes_unseen_units_nonliteral_value_refused(self):
        for kind in ('quote', 'unit', 'value'):
            c = self.valid()
            if kind == 'quote': c['facts'][0]['citations'][0]['quote'] = 'No such source.'
            if kind == 'unit': c['facts'][0]['citations'][0]['unitId'] = 'other'
            if kind == 'value': c['facts'][0]['sourceValue'] = 'NEGATIVE'
            with self.subTest(kind=kind), self.assertRaises(ValueError): source_check(self.source, c, self.ids)
    def test_member_reference_and_family_anchor_refused(self):
        c = self.valid(); c['facts'][0]['memberLabel'] = 'II-99'
        with self.assertRaisesRegex(ValueError, 'MEMBER_REFERENCE'): source_check(self.source, c, self.ids)
        c = self.valid(); c['families'][0]['familyLabel'] = 'invented'
        c['facts'][0]['familyLabel'] = 'invented'
        self.assertIn('FAMILY_IDENTITY_UNRESOLVED', source_check(self.source, c, self.ids)['facts'][0]['issues'])
    def test_display_family_label_binds_only_one_explicit_member(self):
        c = self.valid(); c['families'][0]['familyLabel'] = 'local display'
        c['families'][0]['anchor'] = self.q('Proband III-4')
        c['facts'][0]['familyLabel'] = 'local display'
        r = self.checked(c)
        self.assertEqual('III-4', r['families']['local display']['canonicalLabel'])
        self.assertEqual('UNIQUE_CITED_MEMBER_LOCAL_KEY', r['families']['local display']['identityBasis'])
        self.assertEqual('local display', r['facts'][0]['raw']['familyLabel'])
    def test_duplicate_member_and_claim_refused(self):
        c = self.valid(); c['families'][0]['members'].append(c['families'][0]['members'][0])
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_MEMBER'): source_check(self.source, c, self.ids)
        c = self.valid(); c['facts'].append(c['facts'][0])
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_CLAIM'): source_check(self.source, c, self.ids)
    def test_audit_cannot_omit_or_duplicate_claims(self):
        c = self.valid(); a = self.audit(c); a['audits'] = []
        with self.assertRaisesRegex(ValueError, 'EVERY_CLAIM'): self.checked(c, a)
        a = self.audit(c); a['audits'] *= 2
        with self.assertRaisesRegex(ValueError, 'EVERY_CLAIM'): self.checked(c, a)
    def test_challenged_fact_retained(self):
        c = self.valid(); a = self.audit(c); a['audits'][0]['verdict'] = 'WRONG_SUBJECT_OR_FIELD'
        r = self.checked(c, a)
        self.assertEqual(c['facts'][0], r['facts'][0]['raw'])
        self.assertEqual('UNRESOLVED_OR_CHALLENGED', r['facts'][0]['sourceSupport'])
    def test_invalid_one_quote_does_not_erase_other_facts(self):
        c = self.valid(); c['facts'].append(self.fact('F06.2', value='not in source'))
        a = self.audit(c); a['audits'][1]['evidence'] = [self.q('II-6 tested by WGS: +.')]
        a['audits'][1]['verdict'] = 'CONTRADICTED'
        r = self.checked(c, a)
        self.assertEqual(2, len(r['facts']))
        self.assertEqual('SOURCE_CHECKED_MODEL_SUPPORTED', r['facts'][0]['sourceSupport'])
        self.assertEqual('REJECTED', r['facts'][1]['verification'])
    def test_audit_echoing_invalid_quote_never_overrides_source(self):
        c = self.valid(); c['facts'].append(self.fact('F06.2', value='not in source'))
        r = self.checked(c)
        self.assertEqual(2, len(r['facts']))
        self.assertEqual('REJECTED', r['facts'][1]['verification'])
        self.assertEqual('UNRESOLVED_OR_CHALLENGED', r['facts'][1]['sourceSupport'])
    def test_addition_not_self_approved(self):
        c = self.valid(); a = self.audit(c)
        a['additions'] = [self.fact('F05.2', value='Mother II-6 is healthy.')]
        r = self.checked(c, a)
        self.assertEqual('ADDITION_REQUIRES_AUDIT', r['facts'][-1]['sourceSupport'])
    def merge_checked(self, checks):
        from unittest.mock import patch
        from run_pp1_literature_model import collect
        values = [{'checked': c, 'originalArtifactSha256': digest(canonical(c))} for c in checks]
        with patch('run_pp1_literature_model.reassess', side_effect=values):
            return collect(self.library, [self.root / str(n) for n in range(len(values))])

    def test_later_review_of_added_fact_is_effective_in_either_order(self):
        added = self.fact('F05.2', value='Mother II-6 is healthy.')
        c = self.valid(); a = self.audit(c); a['additions'] = [added]
        before = self.checked(c, a)
        c2 = self.valid(); c2['facts'] = [added]
        after = self.checked(c2)
        for sequence in ((before, after), (after, before)):
            with self.subTest(firstHasAddition=sequence[0] is before):
                result = self.merge_checked(sequence)
                fact = next(f for f in result['facts'] if f['claimId'] == claim_id(added))
                self.assertEqual('SUPPORTED', fact.get('sourceAudit', {}).get('verdict'))
                self.assertEqual('SOURCE_CHECKED_MODEL_SUPPORTED', fact['sourceSupport'])
                self.assertEqual(2, len(fact['artifactOrigins']))
                self.assertFalse(result['scoringEligible'])

    def test_added_fact_review_disagreements_survive_all_merge_orders(self):
        from itertools import permutations
        added = self.fact('F05.2', value='Mother II-6 is healthy.')
        c = self.valid(); a = self.audit(c); a['additions'] = [added]
        before = self.checked(c, a)
        c2 = self.valid(); c2['facts'] = [added]
        supported = self.checked(c2)
        disagree = self.audit(c2); disagree['audits'][0]['verdict'] = 'AMBIGUOUS'
        ambiguous = self.checked(c2, disagree)
        for sequence in permutations((before, supported, ambiguous)):
            result = self.merge_checked(sequence)
            fact = next(f for f in result['facts'] if f['claimId'] == claim_id(added))
            self.assertIn('AUDIT_DISAGREEMENT', fact['issues'])
            self.assertEqual('UNRESOLVED_OR_CHALLENGED', fact['sourceSupport'])
            self.assertEqual({'SUPPORTED', 'AMBIGUOUS'}, {v['verdict'] for v in fact['allSourceAudits']})

    def test_real_artifact_replay_merges_added_fact_later_audit(self):
        from run_pp1_literature_model import collect
        added = self.fact('F05.2', value='Mother II-6 is healthy.')
        first = self.valid(); first_audit = self.audit(first); first_audit['additions'] = [added]
        second = self.valid(); second['facts'] = [added]
        answers = iter((first, first_audit, second, self.audit(second)))
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        # Second extraction has a different bounded unit set, as in continued
        # reading. Otherwise an identical extraction must reuse the first cache.
        extra = next(u['unitId'] for u in self.source['units'] if u['unitId'] not in self.ids)
        paths = []
        for n, ids in enumerate((self.ids, self.ids + [extra])):
            call_args = args[:4] + (ids,) + args[5:]
            parent = self.root / f'extract-{n}'
            run(*call_args, parent, execute=True, transport=lambda *unused: response(next(answers)))
            reviewed = self.root / f'audit-{n}'
            run(*call_args, reviewed, previous=parent / 'artifact.json', execute=True,
                transport=lambda *unused: response(next(answers)))
            paths.append(reviewed / 'artifact.json')
        result = collect(self.library, paths)
        fact = next(f for f in result['facts'] if f['claimId'] == claim_id(added))
        self.assertEqual('SUPPORTED', fact.get('sourceAudit', {}).get('verdict'))
        self.assertEqual('SOURCE_CHECKED_MODEL_SUPPORTED', fact['sourceSupport'])

    def test_review_additions_without_reextracting_and_reuse_complete_parent_chain(self):
        from run_pp1_literature_model import collect
        c = self.valid(); a = self.audit(c)
        a['additions'] = [self.fact('F05.2', value='Mother II-6 is healthy.')]
        calls = []
        def transport(config, key, body):
            content = parse(parse(body)['messages'][1]['content'])
            calls.append(content)
            return response(c if len(calls) == 1 else a if len(calls) == 2 else self.audit(content['original']))
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        run(*args, self.root / 'extract', execute=True, transport=transport)
        parent = self.root / 'audit/artifact.json'
        run(*args, parent.parent, previous=self.root / 'extract/artifact.json', execute=True, transport=transport)
        child = self.root / 'addition-audit/artifact.json'
        run(*args, child.parent, previous=parent, execute=True, transport=transport)
        self.assertEqual(a['additions'], calls[-1]['original']['facts'])
        self.assertEqual(3, len(calls))
        merged = collect(self.library, [child])
        self.assertEqual(2, len(merged['facts']))
        self.assertTrue(all(f['sourceSupport'] == 'SOURCE_CHECKED_MODEL_SUPPORTED' for f in merged['facts']))
        replay(self.library, child)
        again = run(*args, self.root / 'addition-cache', previous=parent, transport=transport)
        self.assertEqual(0, again['modelCalls']); self.assertEqual(3, len(calls))
        changed = parse(child.read_bytes()); changed['parent']['candidate']['additions'][0]['sourceValue'] = 'forged'
        child.write_bytes(canonical(changed))
        with self.assertRaises(ValueError): collect(self.library, [child])

    def test_no_additions_means_no_extra_review_or_transmission(self):
        c = self.valid(); answers = iter((c, self.audit(c)))
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        run(*args, self.root / 'extract', execute=True, transport=lambda *unused: response(next(answers)))
        run(*args, self.root / 'audit', previous=self.root / 'extract/artifact.json', execute=True,
            transport=lambda *unused: response(next(answers)))
        with self.assertRaisesRegex(ValueError, 'NO_PENDING_ADDITIONS'):
            run(*args, self.root / 'extra', previous=self.root / 'audit/artifact.json', execute=True,
                transport=lambda *unused: self.fail('No additional model request allowed'))
    def test_other_allele_cannot_fill_target_result(self):
        c = self.valid(); r = audit_check(self.source, c, self.audit(c), self.ids,
            {'geneSymbol': 'GENE1', 'variantAliases': ['c.2A>C']})
        self.assertIn('TARGET_ALLELE_LINK_NOT_EXPLICIT_IN_CITATIONS', r['facts'][0]['issues'])
    def test_relation_cycle_blocks_graph(self):
        c = self.valid(); c['facts'] = []
        for subject, related in (('II-5', 'III-4'), ('III-4', 'II-5')):
            f = self.fact('F07.1', subject, 'II-5 is the father of III-4.')
            f.update(relatedMembers=[related], relationKind='PARENT_OF'); c['facts'].append(f)
        r = self.checked(c)
        self.assertEqual(['PARENT_GRAPH_CYCLE'], r['graphIssues'])
        self.assertTrue(all(e['qualification'] == 'GRAPH_CONFLICT_REQUIRES_REVIEW' for e in r['relationCandidates']))
    def test_grandchild_does_not_prove_direct_parent(self):
        c = self.valid()
        c['families'][0]['members'].append({'memberLabel': 'IV-1', 'anchor': self.q('grandson IV-1')})
        f = self.fact('F07.1', 'III-4', 'grandson IV-1')
        f.update(relatedMembers=['IV-1'], relationKind='PARENT_OF'); c['facts'] = [f]
        r = self.checked(c)
        self.assertIn('INTERMEDIATE_GENERATION_NOT_PROVEN', r['facts'][0]['issues'])
        self.assertEqual([], r['relationCandidates'])
    def test_clinical_differences_preserved_no_overwrite(self):
        c = self.valid(); c['facts'].append(self.fact(value='GENE1 c.1A>G'))
        c['facts'][-1]['citations'] = [self.q('Proband III-4 and father II-5 have GENE1 c.1A>G.')]
        r = self.checked(c)
        self.assertEqual(2, len(r['facts']))
        self.assertTrue(all('MULTIPLE_VALUES_REQUIRE_RECONCILIATION' in f['issues'] for f in r['facts']))
    def test_different_paper_same_labels_never_auto_merge(self):
        a = self.checked(); s = deepcopy(self.source); s['pmcid'] = 'PMC999'
        b = audit_check(s, self.valid(), self.audit(self.valid()), self.ids, self.target)
        self.assertNotEqual(a['facts'][0]['memberKey'], b['facts'][0]['memberKey'])
    def test_private_target_or_oversize_input_refused(self):
        t = {**self.target, 'sampleId': 'private'}
        with self.assertRaisesRegex(ValueError, 'PUBLIC_TARGET'): request(self.source, t, self.ids, self.c)
        with self.assertRaisesRegex(ValueError, 'NOT_TRUNCATED'):
            request(self.source, self.target, self.ids, {**self.c, 'maxRequestBytes': 1})
    def test_model_request_contains_only_actual_scope(self):
        req = request(self.source, self.target, self.ids, self.c)
        self.assertEqual(self.ids, [u['unitId'] for u in parse(req['messages'][1]['content'])['units']])
        self.assertNotIn('C02.1', canonical(req).decode())

    def test_compact_review_preserves_every_claim_source_and_warning(self):
        from pp1_literature_contract import COMPACT_AUDIT_PROMPT_VERSION
        candidate = self.valid()
        full = request(self.source, self.target, self.ids, self.c, candidate)
        compact = request(self.source, self.target, self.ids, self.c, candidate, COMPACT_AUDIT_PROMPT_VERSION)
        old = parse(full['messages'][1]['content']); new = parse(compact['messages'][1]['content'])
        self.assertEqual(candidate['facts'], [c['fact'] for c in new['claims']])
        self.assertEqual(candidate, {**new['original'], 'facts': [c['fact'] for c in new['claims']]})
        self.assertEqual({k:v for k,v in old.items() if k not in {'original','outputSchema'}},
                         {k:v for k,v in new.items() if k != 'original'})
        self.assertEqual(full['response_format'], compact['response_format'])
        self.assertLess(len(canonical(compact)), len(canonical(full)))
        with self.assertRaisesRegex(ValueError, 'INPUT_LIMIT_NOT_TRUNCATED'):
            request(self.source, self.target, self.ids, {**self.c,'maxRequestBytes':1}, candidate, COMPACT_AUDIT_PROMPT_VERSION)

    def test_oversize_audit_uses_compact_prompt_and_replays_exactly(self):
        from pp1_literature_contract import EXPLICIT_AUDIT_PROMPT_VERSION
        candidate=self.valid()
        compact=request(self.source,self.target,self.ids,self.c,candidate,EXPLICIT_AUDIT_PROMPT_VERSION)
        self.c['maxRequestBytes']=len(canonical(compact))
        self.cfg.write_bytes(canonical(self.c))
        args=(self.library,'PMC123',self.pin,self.target,self.ids,self.c,self.cfg,self.root/'budget')
        run(*args,self.root/'extract',execute=True,transport=lambda *unused: response(candidate))
        with self.assertRaisesRegex(ValueError,'INPUT_LIMIT_NOT_TRUNCATED'):
            request(self.source,self.target,self.ids,self.c,candidate)
        out=run(*args,self.root/'audit',previous=self.root/'extract/artifact.json',execute=True,
                transport=lambda *unused: response(self.audit(candidate)))
        self.assertEqual(1,out['modelCalls'])
        artifact=replay(self.library,self.root/'audit/artifact.json')
        self.assertEqual(EXPLICIT_AUDIT_PROMPT_VERSION,artifact['binding']['promptVersion'])
        self.assertEqual(candidate,artifact['parent']['candidate'])

    def test_explicit_compact_review_preserves_evidence_and_names_closed_output(self):
        c=self.valid()
        legacy=request(self.source,self.target,self.ids,self.c,c,COMPACT_AUDIT_PROMPT_VERSION)
        current=request(self.source,self.target,self.ids,self.c,c,EXPLICIT_AUDIT_PROMPT_VERSION)
        old=parse(legacy['messages'][1]['content']);new=parse(current['messages'][1]['content'])
        guide=new.pop('outputContract')
        self.assertEqual(old,new)
        self.assertEqual(set(AUDIT_SCHEMA['required']),set(guide))
        self.assertEqual(set(AUDIT_SCHEMA['properties']['audits']['items']['required']),set(guide['audits'][0]))
        self.assertEqual(AUDIT_VERSION,guide['schemaVersion'])
        self.assertEqual(legacy['response_format'],current['response_format'])
        malformed={'auditedClaims':[{'claimId':claim_id(c['facts'][0]),'supportStatus':'SUPPORTED','auditNotes':'Yes'}],
                   'additions':[],'additionalFamilies':[],'groupChecks':c['groupChecks']}
        from bundle_model_transport import decode_response
        with self.assertRaisesRegex(ValueError,'SCHEMA_INVALID_RESPONSE_PROFILE_MISMATCH'):
            decode_response(response(malformed),self.c,AUDIT_VERSION)
        missing=self.audit(c);del missing['audits'][0]['evidence']
        with self.assertRaises(ValueError):check_candidate(missing)
    def test_nonpublic_alias_is_not_sent(self):
        with self.assertRaisesRegex(ValueError, 'TARGET_NOT_IN_PUBLIC_SOURCE'):
            request(self.source, {'geneSymbol': 'GENE1', 'variantAliases': ['c.999A>T']}, self.ids, self.c)
    def test_partial_review_never_declares_source_missing(self):
        c = self.valid(); a = self.audit(c)
        a['groupChecks'] = [{'group': 'F%02d' % i, 'outcome': 'NO_SUPPORT_IN_SUPPLIED_SCOPE'} for i in range(1, 11)]
        a['coverage'] = 'MORE_FACTS_OR_FAMILIES'
        self.assertTrue(all(n['resolutionStatus'] != 'NOT_REPORTED_IN_REVIEWED_SCOPE' for n in self.checked(c, a)['readingNeeds']))
    def test_dedup_observation_preserves_multiple_citations(self):
        c = self.valid(); f = deepcopy(c['facts'][0]); f['citations'].append(self.q('Mother II-6 is healthy.'))
        c['facts'].append(f); r = self.checked(c)
        self.assertEqual(1, r['distinctObservations']); self.assertEqual(2, len(r['facts']))
    def test_two_pass_cache_replay_and_tamper(self):
        count = []
        c = self.valid()
        def transport(config, key, body):
            count.append(1)
            req = parse(body)
            content = parse(req['messages'][1]['content'])
            return response(self.audit(c) if 'original' in content else c)
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        first = run(*args, self.root / 'extract', execute=True, transport=transport)
        second = run(*args, self.root / 'audit', previous=self.root / 'extract/artifact.json', execute=True, transport=transport)
        self.assertEqual((1, 1), (first['modelCalls'], second['modelCalls']))
        replay(self.library, self.root / 'audit/artifact.json')
        again = run(*args, self.root / 'reuse', previous=self.root / 'extract/artifact.json')
        self.assertEqual(0, again['modelCalls']); self.assertEqual(2, len(count))
        from run_pp1_literature_model import collect
        merged = collect(self.library, [self.root / 'audit/artifact.json', self.root / 'reuse/artifact.json'])
        self.assertEqual(1, len(merged['facts'])); self.assertEqual(1, merged['distinctObservations'])
        self.assertFalse(merged['scoringEligible'])
        path = self.root / 'audit/artifact.json'; a = parse(path.read_bytes()); a['checked']['facts'][0]['raw']['sourceValue'] = 'forged'
        path.write_bytes(canonical(a))
        with self.assertRaisesRegex(ValueError, 'FACTS_CHANGED'): replay(self.library, path)
        from run_pp1_literature_model import reassess
        fresh = reassess(self.library, path)
        self.assertFalse(fresh['storedDerivedResultUsed'])
        self.assertEqual(c['facts'][0]['sourceValue'], fresh['checked']['facts'][0]['raw']['sourceValue'])
        a['receipt']['responseSha256'] = '0' * 64; path.write_bytes(canonical(a))
        with self.assertRaisesRegex(ValueError, 'RESPONSE_CHANGED'): reassess(self.library, path)
    def test_preflight_and_wrong_parent_no_network(self):
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        r = run(*args, self.root / 'preflight')
        self.assertEqual(0, r['modelCalls']); self.assertFalse((self.root / 'budget').exists())
    def test_reviewed_implementation_reuses_exact_cache_without_credential(self):
        from unittest.mock import patch
        from run_pp1_literature_model import implementation_pins
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        run(*args, self.root / 'original', execute=True, transport=lambda *unused: response(self.valid()))
        old = implementation_pins()
        (self.root / 'key.txt').unlink()
        with patch('run_pp1_literature_model.implementation_pins', return_value={**old, 'reviewed-parser-change': 'new'}), \
                patch('bundle_replay_compatibility.reviewed_profiles', return_value=[old]):
            result = run(*args, self.root / 'new-reader')
            self.assertTrue(result['cacheHit']); self.assertEqual(0, result['modelCalls'])
            replay(self.library, self.root / 'new-reader/artifact.json')
    def test_parent_target_change_blocked_before_new_request(self):
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        run(*args, self.root / 'extract', execute=True, transport=lambda *unused: response(self.valid()))
        bad = (self.library, 'PMC123', self.pin, {'geneSymbol': 'OTHER', 'variantAliases': ['c.1A>G']},
               self.ids, self.c, self.cfg, self.root / 'budget')
        with self.assertRaisesRegex(ValueError, 'PARENT_MISMATCH'):
            run(*bad, self.root / 'audit', previous=self.root / 'extract/artifact.json')
    def test_tampered_source_refused_even_with_cache(self):
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        run(*args, self.root / 'extract', execute=True, transport=lambda *unused: response(self.valid()))
        (self.library / 'papers/PMC123/PMC123.xml').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'PIN_MISMATCH'):
            replay(self.library, self.root / 'extract/artifact.json')
    def test_failure_keeps_reserved_ledger_and_refuses_retry(self):
        calls = []
        def fail(*args): calls.append(1); raise ValueError('DENOVO_MODEL_HTTP_429')
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        for n in ('first', 'second'):
            with self.assertRaises(ValueError): run(*args, self.root / n, execute=True, transport=fail)
        self.assertEqual(1, len(calls))
        ledger = parse((self.root / 'budget/budget.json').read_bytes())
        self.assertEqual('FAILED_RESERVED', ledger['calls'][0]['status'])
    def test_resume_task_starting_at_complete_schema_failure_keeps_reserve(self):
        from resume_model_pilot import resume
        from model_transport import public_config
        args = (self.library, 'PMC123', self.pin, self.target, self.ids, self.c, self.cfg, self.root / 'budget')
        bad = self.valid(); bad['score'] = 1
        with self.assertRaises(ValueError):
            run(*args, self.root / 'failed', execute=True, transport=lambda *unused: response(bad))
        budget = self.root / 'budget'; original = (budget / 'budget.json').read_bytes(); state = parse(original)
        pin = digest(original); archive = budget / 'continuation-reviews'; archive.mkdir()
        (archive / (pin + '-budget.json')).write_bytes(original)
        # Different, frozen offline request; the resume helper does not perform it.
        next_pin = digest(b'new-public-request')
        preflight = self.root / 'resume-preflight.json'
        preflight.write_bytes(canonical({'schemaVersion': 'denovo-model-preflight-v1', 'executeRequested': False,
                                       'requests': [{'requestSha256': next_pin}]}))
        review = self.root / 'resume-review.json'
        raw = (budget / 'responses' / state['calls'][-1]['key'] / 'response.json').read_bytes()
        review.write_bytes(canonical({'schemaVersion': 'denovo-pilot-continuation-review-v1',
            'authorization': 'USER_TASK_AUTHORIZED_DEVELOPMENT', 'diagnosis': 'Received complete JSON with an unexpected score field; now use a separate frozen public source request.',
            'offlineChecksPassed': True, 'responseSha256': digest(raw), 'nextRequestSha256': next_pin,
            'taskStartBudgetSha256': pin, 'previousConfiguration': public_config(self.c)}))
        result = resume(self.c, budget, pin, preflight, review)
        after = parse((budget / 'budget.json').read_bytes())
        self.assertEqual(state['calls'], after['calls'])
        self.assertEqual([next_pin], after['resumePermit']['requestSha256s'])
        self.assertEqual('ONE_CHANGED_REQUEST_PERMITTED', result['status'])


if __name__ == '__main__': unittest.main()
