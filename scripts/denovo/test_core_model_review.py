"""Risk tests for model proposals, immutable provenance and zero-call reuse."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_bundle_qualification import article, registered, candidate
from test_bundle_model import configuration, response
from model_literature_contract import canonical, digest, parse
from bundle_model_contract import review, requests
from run_bundle_model import run as extract
from core_extraction_prompt import CANDIDATE_VERSION
from core_model_review_contract import request, check_review, claim_id, VERSION, GROUPS, check_candidate
from run_core_model_review import run, replay
from core_material_contract import facts


class SourceAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        raw = article().replace(b'Case A was studied.',
            b'Case A was studied. The proband had nystagmus. The mother had migraine. '
            b'The variant was previously unreported.')
        self.library, self.source = registered(self.root, raw)
        self.c = configuration(); self.config = self.root / 'config.json'
        self.config.write_bytes(canonical(self.c)); (self.root / 'key.txt').write_text('synthetic-not-secret')
        self.candidate = candidate(self.source); self.candidate['schemaVersion'] = CANDIDATE_VERSION
        self.initial = review(self.source, self.candidate)
        plans = requests(self.source, extraction_profile='core-material-v1')
        target = self.candidate['cases'][0]['caseCitation']['unitId']
        self.chunk = next(i for i, p in enumerate(plans) if target in parse(p['messages'][1]['content'])['workUnit']['targetUnitIds'])
        self.original_request = plans[self.chunk]
        self.payload = request(self.source, self.candidate, self.original_request, self.initial, self.c)
        self.row = self.candidate['cases'][0]

    def proposal(self):
        return {'schemaVersion': VERSION, 'cases': [{
            'caseLabel': self.row['caseLabel'], 'audits': [
                {'claimId': claim_id(self.row['caseLabel'], f), 'verdict': 'SUPPORTED',
                 'reason': 'Literal field in provided source.', 'evidence': [deepcopy(f['citation'])]}
                for f in self.row['fields']], 'additions': [],
            'groupChecks': [{'group': g, 'outcome': 'UNRESOLVED', 'evidence': []} for g in GROUPS],
            'coverage': 'MORE_REVIEW_NEEDED'}]}

    def check(self, proposal=None):
        return check_review(self.source, self.candidate, self.payload, proposal or self.proposal())

    def test_supported_is_only_proposal_and_all_originals_preserved(self):
        r = self.check()
        self.assertFalse(r['businessPublishable']); self.assertEqual('NOT_RUN', r['criterionDecision'])
        self.assertEqual(len(self.row['fields']), sum(len(c['fields']) for v in r['reviews'] for c in v['cases']))
        self.assertNotIn('"verification":"VERIFIED"', canonical(r).decode())

    def test_historical_scope_reconstructs_but_prompt_claim_or_source_tampering_rejects(self):
        from core_model_review_contract import historical_request
        old = deepcopy(self.payload); body = parse(old['messages'][1]['content'])
        body['units'] = body['units'][:1]
        body['deterministicIssues'][0]['issues'] = ['HISTORICAL_READER_GAP']
        old['messages'][1]['content'] = canonical(body).decode()
        self.assertEqual(old, historical_request(self.source, self.payload, old))
        for kind in ('system','source','claim'):
            changed = deepcopy(old); b = parse(changed['messages'][1]['content'])
            if kind == 'system': changed['messages'][0]['content'] += ' Invent facts.'
            elif kind == 'source': b['units'][0]['text'] += ' invented'
            else: b['cases'][0]['caseLabel'] = 'other-case'
            changed['messages'][1]['content'] = canonical(b).decode()
            with self.subTest(kind=kind), self.assertRaises(ValueError): historical_request(self.source, self.payload, changed)

    def test_historical_diagnostic_uses_existing_canonical_case_label(self):
        from core_model_review_contract import historical_request
        current = deepcopy(self.payload)
        body = parse(current['messages'][1]['content'])
        body['cases'][0]['caseLabel'] = 'Case 3'
        body['deterministicIssues'][0]['caseLabel'] = '3'
        current['messages'][1]['content'] = canonical(body).decode()
        old = deepcopy(current)
        body['deterministicIssues'][0]['issues'] = ['HISTORICAL_READER_GAP']
        old['messages'][1]['content'] = canonical(body).decode()
        self.assertEqual(old, historical_request(self.source, current, old))
        body['deterministicIssues'][0]['caseLabel'] = '4'
        old['messages'][1]['content'] = canonical(body).decode()
        with self.assertRaisesRegex(ValueError, 'DIAGNOSTIC_SHAPE'):
            historical_request(self.source, current, old)

    def test_claim_or_group_coverage_must_be_exact(self):
        for kind in ('missing', 'duplicate', 'unknown', 'group'):
            p = self.proposal(); r = p['cases'][0]
            if kind == 'missing': r['audits'].pop()
            if kind == 'duplicate': r['audits'].append(deepcopy(r['audits'][0]))
            if kind == 'unknown': r['audits'][0]['claimId'] = '0' * 64
            if kind == 'group': r['groupChecks'][-1] = deepcopy(r['groupChecks'][0])
            with self.subTest(kind=kind), self.assertRaisesRegex(ValueError, 'COVERAGE'): self.check(p)

    def test_fake_quote_and_unsent_source_rejected(self):
        for mode in ('fake', 'unsent', 'empty'):
            p = self.proposal(); ev = p['cases'][0]['audits'][0]['evidence']
            if mode == 'fake': ev[0]['quote'] = 'both biological parents confirmed through a nonexistent assay'
            if mode == 'unsent': ev[0]['unitId'] = 'unsent-source'
            if mode == 'empty': ev.clear()
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.check(p)

    def test_unknown_authority_and_false_absence_fields_rejected(self):
        for name in ('verification', 'score', 'absence', 'scientificApproved'):
            p = self.proposal(); p['cases'][0][name] = 'VERIFIED'
            with self.subTest(name=name), self.assertRaises(ValueError): check_candidate(p)

    def test_supported_claim_cannot_be_replaced(self):
        p = self.proposal()
        p['cases'][0]['additions'] = [{'replacesClaimId': p['cases'][0]['audits'][0]['claimId'],
            'field': deepcopy(self.row['fields'][0]), 'reason': 'Change.'}]
        with self.assertRaisesRegex(ValueError, 'REPLACEMENT'): self.check(p)

    def test_wrong_subject_model_vote_never_becomes_confirmed(self):
        p = self.proposal(); field = deepcopy(self.row['fields'][0])
        field.update(field='reportedParentageTestingResult', subject='BOTH_PARENTS')
        p['cases'][0]['additions'] = [{'replacesClaimId': 'NEW', 'field': field, 'reason': 'Speculative.'}]
        r = self.check(p)
        from qualify_bundle_candidates import qualify
        from core_material_extraction import write_package
        from core_material_review import review_package
        view = qualify(self.source, r['reviews'])
        write_package(self.source, view, r['reviews'], self.root / 'raw', data_class='SYNTHETIC')
        idx = review_package(self.source, self.root / 'raw', digest((self.root / 'raw/index.json').read_bytes()), self.root / 'final')
        doc = parse((self.root / 'final' / idx['cases'][0]['path']).read_bytes())
        report = parse((self.root / 'final' / idx['cases'][0]['reportPath']).read_bytes())
        matches = [a for a in report['claims'] if a['rawField']['field'] == 'reportedParentageTestingResult']
        self.assertTrue(matches)
        self.assertTrue(all(a['status'] == 'UNRESOLVED_CLAIM_NOT_ADOPTED' for a in matches))

    def test_duplicate_addition_not_counted_twice_and_original_kept(self):
        p = self.proposal(); f = deepcopy(self.row['fields'][0]); f['field'] = 'reportedRelatedCases'
        a = {'replacesClaimId': 'NEW', 'field': f, 'reason': 'Context only.'}
        p['cases'][0]['additions'] = [a, deepcopy(a)]
        r = self.check(p); self.assertEqual(1, r['cases'][0]['uniqueAdditions'])
        p['cases'][0]['audits'][0]['verdict'] = 'AMBIGUOUS'
        r = self.check(p)
        self.assertEqual(len(self.row['fields']) + 1, sum(len(c['fields']) for v in r['reviews'] for c in v['cases']))

    def test_review_original_scope_is_pinned_and_prompt_separates_jobs(self):
        content = parse(self.payload['messages'][1]['content'])
        self.assertEqual(self.source['bundleSha256'], content['bundleSha256'])
        self.assertEqual(12, len(content['groups']))
        self.assertIn('Job 1', self.payload['messages'][0]['content'])
        self.assertIn('Job 2', self.payload['messages'][0]['content'])
        with self.assertRaisesRegex(ValueError, 'INPUT_LIMIT'):
            request(self.source, self.candidate, self.original_request, self.initial, {**self.c, 'maxRequestBytes': 1})

    def test_novel_variant_and_maternal_phenotype_never_promoted_by_agreement(self):
        p = self.proposal(); uid = self.row['caseCitation']['unitId']
        wrong = [('reportedPreviousPublication', 'PROBAND', 'previously unreported'),
                 ('reportedPhenotype', 'PROBAND', 'The mother had migraine')]
        p['cases'][0]['additions'] = [{'replacesClaimId': 'NEW', 'reason': 'Reviewer agrees, but unsupported semantics.',
            'field': {'field': n, 'subject': s, 'sourceValue': text, 'citation': {'unitId': uid, 'quote': text}}}
            for n, s, text in wrong]
        r = self.check(p)
        from run_core_model_review import materialize
        self.root.joinpath('material').mkdir()
        result = materialize(self.source, r, canonical({'test': 'SYNTHETIC_PROVENANCE'}), self.root / 'material')
        doc = parse((self.root / 'material/core-reviewed' / result['cases'][0]['path']).read_bytes())
        self.assertNotEqual('VERIFIED', facts(doc)['/groups/overlap/previousReports']['verification'])
        positives = facts(doc)['/groups/phenotype/positive']
        self.assertNotIn('The mother had migraine', positives.get('value') or [])

    def test_other_case_unknown_case_and_fabricated_addition_rejected(self):
        for mode in ('case', 'invented'):
            p = self.proposal()
            if mode == 'case': p['cases'][0]['caseLabel'] = 'B'
            else:
                f = deepcopy(self.row['fields'][0]); f['sourceValue'] = 'NOT IN ANY QUOTE'
                p['cases'][0]['additions'] = [{'replacesClaimId': 'NEW', 'field': f, 'reason': 'Incorrect.'}]
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.check(p)

    def test_source_vote_does_not_remove_correct_original_even_if_challenged(self):
        before = self.check(); p = self.proposal()
        p['cases'][0]['audits'][0].update(verdict='CONTRADICTED', reason='Erroneous model disagreement.')
        after = self.check(p)
        self.assertEqual(before['reviews'], after['reviews'])

    def test_transport_replay_cache_and_materialization(self):
        state = self.root / 'budget'
        extract(self.library, self.source['pmcid'], self.source['bundleSha256'], self.c, self.config, state,
                self.root / 'extract', transport=lambda *args: response(self.candidate), extraction_profile='core-material-v1',
                chunk_index=self.chunk)
        original = self.root / 'extract/artifact.json'; original_bytes = original.read_bytes()
        calls = []
        def transport(*args):
            calls.append(1); return response(self.proposal())
        a = run(self.library, original, self.c, self.config, state, self.root / 'audit', transport=transport)
        before = (state / 'budget.json').read_bytes()
        b = run(self.library, original, self.c, self.config, state, self.root / 'cached', cache_only=True,
                transport=lambda *args: self.fail('Network forbidden'))
        self.assertEqual(1, len(calls)); self.assertEqual(0, b['modelCalls'])
        self.assertEqual(before, (state / 'budget.json').read_bytes())
        self.assertEqual(original_bytes, original.read_bytes())
        self.assertEqual(replay(self.library, self.root / 'audit'), replay(self.library, self.root / 'cached'))
        self.assertEqual(a['material'], b['material'])
        body = parse((self.root / 'audit/artifact.json').read_bytes())
        body['review']['cases'][0]['audits'][0]['verdict'] = 'CONTRADICTED'
        (self.root / 'audit/artifact.json').write_bytes(canonical(body))
        with self.assertRaisesRegex(ValueError, 'REVIEW_RESULT_CHANGED'): replay(self.library, self.root / 'audit')


if __name__ == '__main__':
    unittest.main()
