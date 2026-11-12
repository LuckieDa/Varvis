"""Offline engineering controls. Synthetic HTTP bytes are not clinical data."""
from collections.abc import Mapping
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from pp2_discovery_scope import FrozenTransport, canonical, replay
from discover_public_literature import make_plan, targets_from
from literature_search_transport import endpoint
from litvar_literature import metadata_plans, resolve, search_text
from prepare_literature import sha


def raw(value):
    return json.dumps(value).encode('utf-8')


def variant(index=123, name='p.T41A', gene='EXAMPLE'):
    return {'_id': 'litvar@rs' + str(index) + '##', 'gene': [gene],
            'name': name, 'hgvs': name, 'rsid': 'rs' + str(index)}


def metadata(ids, count=None, cursor=None):
    value = {'hitCount': len(ids) if count is None else count,
             'resultList': {'result': [{'source': 'MED', 'id': str(i),
                'title': 'An EXAMPLE family study'} for i in ids]}}
    if cursor is not None:
        value['nextCursorMark'] = cursor
    return raw(value)


class ScopeReplayTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)

    def input(self, aliases=None, issues=None, targets=1):
        doc = {'schemaVersion': 'denovo-literature-discovery-input-v1', 'targets': []}
        for index in range(targets):
            signals = raw({'schemaVersion': 'denovo-selected-search-signals-v1',
                'geneSymbol': 'EXAMPLE', 'variantAliases': ['p.T41A'] if aliases is None else aliases,
                'citationPmids': [], 'citationSources': [], 'issues': issues or []})
            name = 'signals-' + str(index) + '.json'
            (self.root / name).write_bytes(signals)
            doc['targets'].append({'targetId': 'target-' + str(index),
                'sourceKind': 'SELECTED_VARIANT_SIGNALS',
                'source': {'path': name, 'sha256': sha(signals)}})
        path = self.root / 'input.json'
        path.write_bytes(raw(doc))
        return path

    def responses(self, path, pmids=('123',), aliases_empty=False):
        q = deepcopy(make_plan(targets_from(path))[0])
        q['issues'] = []
        v = variant()
        responses = {endpoint('litvar', search_text(q['gene'], alias), 'autocomplete')[0]:
                     raw([] if aliases_empty else [v]) for alias in q['aliases']}
        responses[endpoint('litvar', v['_id'], 'summary')[0]] = raw(v)
        responses[endpoint('litvar', v['_id'], 'publications')[0]] = raw({
            'pmids': list(pmids), 'pmcids': [], 'pmids_count': len(pmids)})
        refs = resolve(q, FrozenTransport(responses))
        children = metadata_plans(q, refs)
        for child in children:
            ids = [r['id'] for r in child['requestedReferences']]
            responses[endpoint('europepmc', child['query'], '*')[0]] = metadata(ids)
        return responses, children

    def test_complete_is_only_declared_retrieval_and_never_scientific_adoption(self):
        path = self.input()
        responses, _ = self.responses(path)
        with patch('urllib.request.OpenerDirector.open', side_effect=AssertionError('network')):
            result = replay(path, responses)
        self.assertEqual('COMPLETE', result['status'])
        self.assertFalse(result['completeLiteratureSearch'])
        self.assertFalse(result['scientificReviewComplete'])
        self.assertFalse(result['scientificAdoption'])
        self.assertFalse(result['businessPublishable'])
        self.assertEqual(0, result['networkRequests'])
        self.assertEqual(1, result['unqualifiedCandidateCount'])
        self.assertTrue(all(c['qualification'] == 'DISCOVERY_CANDIDATE_ONLY' for c in result['candidates']))
        self.assertTrue(all(not m['alleleIdentityVerified'] for c in result['candidates'] for m in c['matches']))
        self.assertEqual(4, len(result['usedResponses']))
        self.assertEqual(set(result['plannedQueryIds']), set(result['targets'][0]['plannedQueryIds']))
        self.assertEqual(set(result['targets'][0]['queryIds']), set(result['targets'][0]['finishedQueryIds']))
        self.assertIn('litvar_literature.py', result['methodPins'])
        self.assertEqual(sha(path.read_bytes()), result['inputSha256'])
        self.assertEqual(result['ledgerSha256'], sha(canonical({k: v for k, v in result.items() if k != 'ledgerSha256'})))

    def test_valid_empty_responses_complete_only_the_executed_alias_scope(self):
        path = self.input(aliases=['p.T41A', 'NM_123.1:c.123A>G'])
        responses, _ = self.responses(path, aliases_empty=True)
        result = replay(path, responses)
        self.assertEqual('COMPLETE', result['status'])
        self.assertEqual([], result['candidates'])
        self.assertEqual(2, len(result['usedResponses']))
        self.assertEqual([], result['queryReplay'][0]['unqueriedAliases'])
        self.assertEqual('NO_MATCH_IN_EXECUTED_LITVAR_QUERIES', result['queryReplay'][0]['emptyReason'])
        self.assertFalse(result['completeLiteratureSearch'])

    def test_empty_source_or_no_alias_plan_never_completes(self):
        for path in [self.input(aliases=[]), self.root / 'absent.json']:
            with self.subTest(path=path):
                r = replay(path, {})
                self.assertEqual('INCOMPLETE', r['status'])
                self.assertTrue(r['errors'])
        path = self.input()
        path.write_bytes(raw({'schemaVersion': 'denovo-literature-discovery-input-v1', 'targets': []}))
        self.assertEqual('INCOMPLETE', replay(path, {})['status'])

    def test_missing_alias_response_does_not_skip_to_clear(self):
        path = self.input(aliases=['p.T41A', 'c.123A>G'])
        responses, _ = self.responses(path, aliases_empty=True)
        del responses[endpoint('litvar', 'EXAMPLE p.T41A', 'autocomplete')[0]]
        r = replay(path, responses)
        self.assertEqual('INCOMPLETE', r['status'])
        self.assertEqual(['p.T41A'], r['queryReplay'][0]['unqueriedAliases'])
        self.assertTrue(any('FROZEN_RESPONSE_MISSING' in i for q in r['queryReplay'] for i in q['issues']))

    def test_metadata_absent_or_wrong_identifier_is_incomplete(self):
        path = self.input()
        responses, children = self.responses(path)
        url = endpoint('europepmc', children[0]['query'], '*')[0]
        for body in [metadata([]), metadata(['999']), b'null']:
            with self.subTest(body=body):
                r = replay(path, dict(responses, **{url: body}))
                self.assertEqual('INCOMPLETE', r['status'])
                self.assertTrue(r['remaining'])
                self.assertTrue(any(e['url'] == url and e['sha256'] == sha(body)
                                    for e in r['usedResponses']))

    def test_pagination_replays_exact_cursor_and_checks_all_required_references(self):
        path = self.input()
        responses, children = self.responses(path, ['123', '124'])
        query = children[0]['query']
        first = endpoint('europepmc', query, '*')[0]
        second = endpoint('europepmc', query, 'next-page')[0]
        responses[first] = metadata(['123'], count=2, cursor='next-page')
        r = replay(path, responses)
        self.assertEqual('INCOMPLETE', r['status'])
        responses[second] = metadata(['124'], count=2)
        r = replay(path, responses)
        self.assertEqual('COMPLETE', r['status'])
        q = next(q for q in r['queryReplay'] if q['tier'] == 'LITVAR_PUBLICATIONS')
        self.assertEqual(['*', 'next-page'], q['seenCursors'])
        self.assertEqual(2, len(q['matchedReferences']))
        self.assertEqual({sha(responses[first]), sha(responses[second])}, {
            e['sha256'] for e in r['usedResponses'] if e['source'] == 'europepmc'})

    def test_pagination_repeat_or_changed_hit_count_never_completes(self):
        path = self.input()
        responses, children = self.responses(path, ['123', '124'])
        query = children[0]['query']
        first = endpoint('europepmc', query, '*')[0]
        second = endpoint('europepmc', query, 'next-page')[0]
        for a, b in [(metadata(['123'], 2, '*'), metadata(['124'], 2)),
                     (metadata(['123'], 2, 'next-page'), metadata(['124'], 3))]:
            with self.subTest(first=a):
                r = replay(path, dict(responses, **{first: a, second: b}))
                self.assertEqual('INCOMPLETE', r['status'])

    def test_true_batch_continuation_does_not_call_network_or_take_injected_checkpoint(self):
        path = self.input()
        pmids = [str(i) for i in range(1000, 1440)]
        responses, _ = self.responses(path, pmids)
        short = replay(path, responses, max_batches=1)
        self.assertEqual('INCOMPLETE', short['status'])
        self.assertIn('PP2_DISCOVERY_REPLAY_BATCH_LIMIT', short['errors'])
        complete = replay(path, responses)
        self.assertEqual('COMPLETE', complete['status'])
        self.assertGreater(len(complete['batches']), 1)
        self.assertEqual(440, complete['candidateCount'])
        self.assertEqual(23, len(complete['queryReplay']))
        self.assertTrue(all(b['metadataPagesRead'] <= 20 for b in complete['batches']))

    def test_more_than_network_request_limit_of_frozen_aliases_can_complete(self):
        aliases = ['NM_123.' + str(i) + ':c.123A>G' for i in range(1, 31)]
        path = self.input(aliases=aliases)
        responses, _ = self.responses(path, aliases_empty=True)
        result = replay(path, responses)
        self.assertEqual('COMPLETE', result['status'])
        self.assertEqual(30, result['batches'][0]['replayOperations'])
        self.assertEqual(30, len(result['queryReplay'][0]['resolution']))
        self.assertEqual(0, result['networkRequests'])

    def test_source_issues_remain_even_when_raw_queries_are_empty(self):
        path = self.input(issues=['ORIGINAL_ALLELE_ALIAS_UNRESOLVED'])
        responses, _ = self.responses(path, aliases_empty=True)
        r = replay(path, responses)
        self.assertEqual('INCOMPLETE', r['status'])
        self.assertTrue(any(v['reasonCode'] == 'PP2_DISCOVERY_TARGET_SOURCE_ISSUE' for v in r['remaining']))

    def test_every_planned_target_is_required_even_when_some_share_queries(self):
        path = self.input(targets=2)
        responses, _ = self.responses(path)
        r = replay(path, responses)
        self.assertEqual('COMPLETE', r['status'])
        self.assertEqual(2, len(r['targetSourcePins']))
        self.assertEqual(1, len(r['plannedQueryIds']))
        doc = json.loads(path.read_bytes())
        source = self.root / doc['targets'][1]['source']['path']
        signals = json.loads(source.read_bytes())
        signals['variantAliases'] = []
        source.write_bytes(raw(signals))
        doc['targets'][1]['source']['sha256'] = sha(source.read_bytes())
        path.write_bytes(raw(doc))
        self.assertEqual('INCOMPLETE', replay(path, responses)['status'])

    def test_original_source_and_response_pin_changes_are_observable(self):
        path = self.input()
        responses, _ = self.responses(path)
        original = replay(path, responses)
        changed = dict(responses)
        url = next(u for u in responses if 'autocomplete' in u)
        changed[url] += b'\n'
        rerun = replay(path, changed)
        self.assertEqual('COMPLETE', rerun['status'])
        self.assertNotEqual(original['responseSetSha256'], rerun['responseSetSha256'])
        self.assertNotEqual(original['ledgerSha256'], rerun['ledgerSha256'])
        source = self.root / 'signals-0.json'
        source.write_bytes(source.read_bytes() + b'\n')
        self.assertEqual('INCOMPLETE', replay(path, responses)['status'])

    def test_malformed_or_limited_litvar_response_cannot_sign_complete(self):
        path = self.input()
        responses, _ = self.responses(path)
        auto = next(u for u in responses if 'autocomplete' in u)
        pub = next(u for u in responses if u.endswith('/publications'))
        cases = [dict(responses, **{auto: b'{}'}),
                 dict(responses, **{pub: raw({'pmids': [], 'pmids_count': 1})})]
        limited = dict(responses, **{auto: raw([variant(i) for i in range(100, 200)])})
        for i in range(100, 200):
            v = variant(i)
            limited[endpoint('litvar', v['_id'], 'summary')[0]] = raw(v)
            limited[endpoint('litvar', v['_id'], 'publications')[0]] = raw({'pmids': [], 'pmids_count': 0})
        cases.append(limited)
        for responses in cases:
            with self.subTest(size=len(responses)):
                self.assertEqual('INCOMPLETE', replay(path, responses)['status'])

    def test_exact_url_no_repair_or_alternative_response_choice(self):
        path = self.input()
        responses, _ = self.responses(path, aliases_empty=True)
        url = next(u for u in responses if 'autocomplete' in u)
        responses[url.replace('limit=100', 'limit=99')] = responses.pop(url)
        self.assertEqual('INCOMPLETE', replay(path, responses)['status'])

    def test_invalid_budget_or_raw_type_is_incomplete(self):
        path = self.input()
        for responses, kw in [({}, {'max_batches': 0}), ({'https://example.org': '[]'}, {}),
                               ({}, {'max_batches': True}), ({}, {'max_batches': 257})]:
            with self.subTest(kw=kw):
                self.assertEqual('INCOMPLETE', replay(path, responses, **kw)['status'])

    def test_conflicting_duplicate_url_items_are_rejected_before_lookup(self):
        class ConflictMapping(Mapping):
            def __iter__(self):
                return iter(['https://example.org'])
            def __len__(self):
                return 1
            def __getitem__(self, key):
                return b'[]'
            def items(self):
                return [('https://example.org', b'[]'), ('https://example.org', b'{}')]
        r = replay(self.input(), ConflictMapping())
        self.assertEqual('INCOMPLETE', r['status'])
        self.assertEqual(['PP2_DISCOVERY_RESPONSE_URL_CONFLICT'], r['errors'])


if __name__ == '__main__':
    unittest.main()
