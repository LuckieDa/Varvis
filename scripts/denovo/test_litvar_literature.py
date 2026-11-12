"""Synthetic offline contract, identity, failure isolation and discovery tests."""
import json
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
from unittest.mock import patch

from discover_public_literature import discover
from litvar_literature import decode, url_for, plans, resolve, LIMIT
from literature_search_transport import SearchTransport, sha, worker
from test_discover_public_literature import Fake, target, row, response

ID = 'litvar@rs123##'


def raw(data): return json.dumps(data).encode()


def variant(id_=ID, gene='EXAMPLE', name='p.T41A'):
    return {'_id': id_, 'gene': [gene], 'name': name, 'hgvs': name, 'rsid': id_.partition('@')[2][:-2]}


def publications(pmids=('123',), pmcids=()):
    return raw({'pmids': list(pmids), 'pmcids': list(pmcids), 'pmids_count': len(set(pmids))})


class LitFake(Fake):
    def __init__(self, variants=None, pubs=None, text=None):
        super().__init__(text or (lambda q, c: response([row('123', pmcid='PMC456')]) if 'EXT_ID' in q else response()))
        self.variants = raw([variant()]) if variants is None else variants
        self.pubs = publications() if pubs is None else pubs
        self.lit_calls = []

    def get_litvar(self, op, value):
        self.requests += 1
        self.lit_calls.append((op, value))
        result = self.variants if op == 'autocomplete' else raw(next(
            v for v in json.loads(self.variants) if v['_id'] == value)) if op == 'summary' else self.pubs
        if isinstance(result, Exception): raise result
        return result


class DiscoveryTests(unittest.TestCase):
    def search(self, fake=None, targets=None, **kw):
        return discover(targets or [target(aliases=['p.Thr41Ala'])], fake or LitFake(),
                        precision_first=True, litvar=True, **kw)

    def test_new_paper_flows_into_original_metadata_and_candidate_contract(self):
        r = self.search()
        self.assertEqual(['123'], [c['pmid'] for c in r['candidates']])
        self.assertFalse(r['businessPublishable'])
        self.assertFalse(r['candidates'][0]['matches'][0]['alleleIdentityVerified'])
        self.assertEqual(ID, r['candidates'][0]['matches'][0]['litvarVariantId'])
        self.assertIn('LITVAR_PUBLICATIONS', [o['kind'] for o in r['candidates'][0]['origins']])
        self.assertIsNone(r['targets'][0]['fallbackReason'])
        self.assertTrue(r['targets'][0]['plannedQueriesComplete'])

    def test_official_gene_qualified_ids_without_rsid_are_retrieval_clues(self):
        for gene, identifier, name in [('MECOM', 'litvar@#2122#p.R762K', 'p.R762K'),
                                       ('COL4A3', 'litvar@#1285#p.L980*', 'p.L980*')]:
            payload = raw([{'_id': identifier, 'gene': [gene], 'name': name, 'hgvs': name,
                            'pmids_count': 1, 'flag_gene_variant': False,
                            'flag_clingen_variant': False, 'flag_rsid_variant': False, 'match': None}])
            t = target(aliases=[name]); t['geneSymbol'] = gene
            f = LitFake(variants=payload)
            r = self.search(f, [t])
            self.assertEqual([('autocomplete', gene + ' ' + name), ('summary', identifier), ('publications', identifier)], f.lit_calls)
            self.assertEqual(['123'], [c['pmid'] for c in r['candidates']])
            self.assertFalse(r['candidates'][0]['matches'][0]['alleleIdentityVerified'])
            self.assertTrue(url_for('publications', identifier).startswith(
                'https://www.ncbi.nlm.nih.gov/research/litvar2-api/variant/get/litvar%40%23'))

    def test_pmc_only_link_uses_pmcid_field_and_checks_returned_identity(self):
        f = LitFake(pubs=publications([], ['PMC456']), text=lambda q, c:
                    response([row('123', pmcid='PMC456')]) if 'PMCID:PMC456' in q else response())
        r = self.search(f)
        self.assertEqual(['123'], [c['pmid'] for c in r['candidates']])
        self.assertTrue(r['targets'][0]['plannedQueriesComplete'])
        f = LitFake(pubs=publications([], ['PMC456']), text=lambda q, c:
                    response([row('123', pmcid='PMC999')]) if 'PMCID:PMC456' in q else response())
        r = self.search(f)
        self.assertEqual([], r['candidates'])
        self.assertIn('LITERATURE_SEARCH_LITVAR_METADATA_MISSING', r['targets'][0]['issues'])

    def test_existing_hit_still_uses_litvar_and_deduplicates_provenance(self):
        f = LitFake(text=lambda q, c: response([row('123', pmcid='PMC456')]))
        r = self.search(f)
        self.assertEqual(1, len(r['candidates']))
        self.assertEqual({'LITVAR_PUBLICATIONS'}, {m['tier'] for m in r['candidates'][0]['matches']})
        self.assertEqual(3, len(f.lit_calls))

    def test_shared_variants_share_resolution_and_candidates(self):
        f = LitFake()
        r = self.search(f, [target('a', aliases=['p.T41A']), target('b', aliases=['p.Thr41Ala'])])
        self.assertEqual(3, len(f.lit_calls))
        self.assertEqual({'a', 'b'}, {m['targetId'] for m in r['candidates'][0]['matches']})

    def test_wrong_gene_or_substitution_does_not_follow_first_autocomplete(self):
        for v in [variant(gene='OTHER'), variant(name='p.T41V')]:
            f = LitFake(variants=raw([v]))
            r = self.search(f)
            self.assertEqual([], r['candidates'])
            self.assertFalse(any(op == 'publications' for op, _ in f.lit_calls))
            self.assertEqual('SEARCH_INCOMPLETE', r['targets'][0]['status'])

    def test_ambiguous_or_truncated_retains_all_returned_ids_as_unverified(self):
        for variants in [[variant(), variant('litvar@rs124##')], [variant('litvar@rs' + str(i + 100) + '##') for i in range(LIMIT)]]:
            f = LitFake(variants=raw(variants)); r = self.search(f)
            parent = next(q for q in r['queries'] if q['tier'] == 'LITVAR_VARIANT')
            self.assertEqual(len(variants), len(parent['litvarVariantIds']))
            self.assertTrue(all(not v['alleleIdentityVerified'] for v in parent['variantCandidates']))
            if len(variants) == LIMIT:
                self.assertIn('LITERATURE_SEARCH_LITVAR_AUTOCOMPLETE_LIMIT', r['targets'][0]['issues'])

    def test_transcript_versions_never_stripped_to_claim_match(self):
        f = LitFake(variants=raw([variant(name='NM_123.2:c.123A>G')]))
        r = self.search(f, [target(aliases=['NM_123.1:c.123A>G'])])
        self.assertEqual(1, len(r['candidates']))
        self.assertFalse(r['candidates'][0]['matches'][0]['alleleIdentityVerified'])
        self.assertIn('LITERATURE_SEARCH_LITVAR_NAME_MAPPING_UNRESOLVED', r['targets'][0]['issues'])

    def test_empty_or_failed_litvar_never_falls_back_to_keywords(self):
        for data, expected in [(b'[]', 'NO_HITS_IN_EXECUTED_QUERIES'),
                               (ValueError('LITERATURE_SEARCH_TIMEOUT'), 'SEARCH_INCOMPLETE')]:
            f = LitFake(variants=data, text=response([row()]))
            r = self.search(f)
            self.assertEqual(expected, r['targets'][0]['status'])
            self.assertEqual([], r['candidates'])
            self.assertEqual([], f.queries)

    def test_no_variant_alias_sends_no_litvar_gene_request(self):
        f = LitFake(text=response()); self.search(f, [target(aliases=[])])
        self.assertEqual([], f.lit_calls)

    def test_publication_arrays_not_zipped_and_metadata_must_match(self):
        f = LitFake(pubs=publications(['123'], ['PMC456']), text=lambda q,c: response([row('123',pmcid='PMC999')]) if 'EXT_ID' in q else response())
        r = self.search(f)
        self.assertEqual('PMC999', r['candidates'][0]['pmcid'])
        self.assertIn('LITERATURE_SEARCH_LITVAR_METADATA_MISSING', r['targets'][0]['issues'])
        f = LitFake(text=lambda q,c: response([row('999')]) if 'EXT_ID' in q else response())
        self.assertEqual([], self.search(f)['candidates'])

    def test_count_mismatch_rejected_not_no_hits(self):
        f = LitFake(pubs=raw({'pmids': [], 'pmcids': [], 'pmids_count': 2}))
        self.assertIn('LITERATURE_SEARCH_LITVAR_INCOMPLETE_RESPONSE', self.search(f)['targets'][0]['issues'])

    def test_official_pmid_only_publication_response_preserves_known_links(self):
        # The official service omits pmcids when it supplies only PubMed links.
        f = LitFake(pubs=raw({'pmids': [123], 'pmids_count': 1}))
        r = self.search(f)
        self.assertEqual(['123'], [c['pmid'] for c in r['candidates']])
        parent = next(q for q in r['queries'] if q['tier'] == 'LITVAR_VARIANT')
        self.assertEqual([], parent['pmcids'])
        self.assertTrue(r['targets'][0]['plannedQueriesComplete'])
        for data in [{'pmcids': [], 'pmids_count': 0}, {'pmids': [123], 'pmids_count': 0},
                     {'pmids': [123], 'pmids_count': 1, 'pmcids': None}]:
            with self.assertRaises(ValueError): decode(raw(data), 'publications')

    def test_reference_and_page_limits_explicit(self):
        f = LitFake(pubs=publications([str(i) for i in range(1, 103)]), text=response())
        r = self.search(f, max_pages=2)
        parent = next(q for q in r['queries'] if q['tier'] == 'LITVAR_VARIANT')
        self.assertEqual([], parent['omittedReferences'])
        self.assertEqual(102, len(parent['referenceLinks']))
        self.assertFalse(r['targets'][0]['plannedQueriesComplete'])
        self.assertTrue(any('LITERATURE_SEARCH_BATCH_LIMIT' in q['issues'] for q in r['queries']))

    def test_litvar_names_are_discovery_only_even_when_linked_to_rsid(self):
        r = self.search(LitFake(variants=raw([{**variant(), 'data_clinical_significance': ['pathogenic']}])) )
        self.assertFalse(r['candidates'][0]['matches'][0]['alleleIdentityVerified'])
        self.assertNotIn('pathogenic', json.dumps(r['candidates']))

    def test_secondary_alias_can_recover_exact_name_after_empty_search(self):
        f = LitFake()
        def lookup(op, value):
            if op == 'summary': return raw(variant(name='c.123A>G'))
            return raw([variant(name='c.123A>G')]) if op == 'autocomplete' and ' c.' in value else b'[]' if op == 'autocomplete' else publications()
        f.get_litvar = lookup
        r = self.search(f, [target(aliases=['p.T41A','c.123A>G'])])
        self.assertEqual(1, len(r['candidates']))


class TransportTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def transport(self, online=True):
        import uuid
        return SearchTransport(self.root / 'cache', self.root / uuid.uuid4().hex, online)

    def fake(self, args, stdout, **kw):
        Path(args[-2] if args[-1] == 'litvar' else args[-1]).write_bytes(b'[]' if args[-1] == 'litvar' else response())
        stdout.write(b'{"status":"OK"}'); stdout.flush()
        return subprocess.CompletedProcess(args, 0)

    def test_fixed_host_and_encoded_id_no_fragment_or_arbitrary_url(self):
        self.assertTrue(url_for('publications', ID).endswith('litvar%40rs123%23%23/publications'))
        for op, value in [('publications','https://evil.test/'), ('autocomplete','EXAMPLE x\nsecret'), ('other',ID)]:
            with self.assertRaises(ValueError): url_for(op, value)
        for identifier in ['litvar@#2122#', 'litvar@#abc#p.R762K', 'litvar@#2122#p.R762K?x=1',
                           'litvar@#2122#p.R762K\n', 'litvar@#2122#' + 'A' * 200]:
            with self.assertRaises(ValueError): url_for('publications', identifier)

    def test_cache_roundtrip_offline_no_duplicate_request(self):
        with self.transport() as t, patch('literature_search_transport.subprocess.run', side_effect=self.fake) as run:
            self.assertEqual(b'[]', t.get_litvar('autocomplete','EXAMPLE p.T41A'))
            self.assertEqual(b'[]', t.get_litvar('autocomplete','EXAMPLE p.T41A'))
            self.assertEqual(1, run.call_count)
        with self.transport(False) as t, patch('literature_search_transport.subprocess.run') as run:
            self.assertEqual(b'[]', t.get_litvar('autocomplete','EXAMPLE p.T41A')); run.assert_not_called()

    def test_litvar_timeout_isolated_and_shared_cooldown_is_source_specific(self):
        for failure in ['timeout', '429']:
            with self.subTest(failure=failure), self.transport() as t:
                def fail(args, stdout, **kw):
                    if args[-1] != 'litvar': return self.fake(args, stdout, **kw)
                    if failure == 'timeout': raise subprocess.TimeoutExpired(args,20)
                    stdout.write(b'{"status":"FAILED","reasonCode":"LITERATURE_SEARCH_RATE_LIMIT","cooldownSeconds":600}'); stdout.flush()
                    return subprocess.CompletedProcess(args,0)
                with patch('literature_search_transport.subprocess.run', side_effect=fail):
                    with self.assertRaises(ValueError): t.get_litvar('autocomplete','EXAMPLE p.T41A')
                    self.assertEqual(response(), t.get('EXAMPLE'))
                    with self.assertRaises(ValueError): t.get_litvar('autocomplete','EXAMPLE p.T41V')
            if failure == '429':
                with self.transport() as t, patch('literature_search_transport.subprocess.run') as run:
                    with self.assertRaisesRegex(ValueError,'COOLDOWN'): t.get_litvar('autocomplete','EXAMPLE p.T41V')
                    run.assert_not_called()

    def test_same_global_budget_applies_to_litvar(self):
        for field, value in [('requests',20), ('started',time.monotonic()-121)]:
            with self.subTest(field=field), self.transport() as t, patch('literature_search_transport.subprocess.run') as run:
                setattr(t,field,value)
                with self.assertRaisesRegex(ValueError,'BUDGET|TIMEOUT'): t.get_litvar('autocomplete','EXAMPLE p.T41A')
                run.assert_not_called()

    def test_litvar_worker_accepts_real_shape_and_retains_raw(self):
        payload = raw([variant()])
        class Response:
            status = 200
            headers = {}
            def __enter__(s): return s
            def __exit__(s,*a): pass
            def read(s,n):
                if getattr(s,'sent',False): return b''
                s.sent=True; return payload
        with patch('literature_search_transport.urllib.request.build_opener') as opener:
            opener.return_value.open.return_value = Response()
            result = worker('EXAMPLE p.T41A','autocomplete',self.root / 'raw','litvar')
        self.assertEqual('OK',result['status'])
        self.assertEqual(payload,(self.root / 'raw').read_bytes())

    def test_europe_failure_does_not_disable_litvar(self):
        with self.transport() as t, patch('literature_search_transport.subprocess.run', side_effect=self.fake):
            t.stopped = 'LITERATURE_SEARCH_TIMEOUT'
            self.assertEqual(b'[]',t.get_litvar('autocomplete','EXAMPLE p.T41A'))

    def test_malformed_and_duplicate_keys_rejected(self):
        for value in [b'{}', raw([{'_id':ID}]), raw([variant('https://evil.test')]), raw([{**variant(), 'rsid': []}])]:
            with self.assertRaises(ValueError): decode(value,'autocomplete')
        for value in [b'{}', b'{"pmids":[],"pmids":[],"pmcids":[],"pmids_count":0}', publications([True])]:
            with self.assertRaises(ValueError): decode(value,'publications')


if __name__ == '__main__': unittest.main()
