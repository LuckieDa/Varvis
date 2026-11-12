"""Discovery tests: all API responses and ClinVar fixtures here are synthetic."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from discover_public_literature import (targets_from, make_plan, discover, paper, merge_candidate,
                                       contains, MAX_CANDIDATES)
from prepare_literature import sha
from test_literature_source_assertions import ARTICLE, RECORD, VCV, PMCID


def target(id_='one', gene='EXAMPLE', aliases=None, pmids=None):
    return {'targetId': id_, 'geneSymbol': gene, 'geneAliases': [],
            'variantAliases': ['c.123A>G'] if aliases is None else aliases,
            'citationPmids': pmids or [], 'externalScope': 'PUBLIC_VARIANT'}


def row(id_='123', **kw):
    return {'source': 'MED', 'id': id_, 'title': 'An EXAMPLE family study', **kw}


def response(rows=(), count=None, cursor=None):
    value = {'hitCount': len(rows) if count is None else count, 'resultList': {'result': list(rows)}}
    if cursor: value['nextCursorMark'] = cursor
    return json.dumps(value).encode()


class Fake:
    def __init__(self, responses):
        self.responses, self.requests, self.events, self.queries = responses, 0, [], []

    def get(self, query, cursor='*'):
        self.requests += 1
        self.queries.append((query, cursor))
        value = self.responses(query, cursor) if callable(self.responses) else self.responses
        if isinstance(value, Exception): raise value
        return value

    def get_litvar(self, operation, value):
        self.requests += 1
        return b'[]'


class DiscoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def input(self, kind, raw, **kwargs):
        (self.root / 'source').write_bytes(raw)
        doc = {'schemaVersion': 'denovo-literature-discovery-input-v1', 'targets': [{
            'targetId': 'test', 'sourceKind': kind, 'source': {'path': 'source', 'sha256': sha(raw), **kwargs}}]}
        path = self.root / 'input.json'
        path.write_text(json.dumps(doc), encoding='utf-8')
        return path

    def test_clinvar_adapter_uses_existing_identity_and_citation_parser(self):
        loaded = targets_from(self.input('CLINVAR_RECORD', RECORD, vcv=VCV))
        self.assertEqual('EXAMPLE', loaded[0]['geneSymbol'])
        self.assertIn('NM_999999997.1:c.123A>G', loaded[0]['variantAliases'])
        self.assertEqual(['999999997'], loaded[0]['citationPmids'])

    def test_public_paper_does_not_seed_its_own_pmid(self):
        loaded = targets_from(self.input('PUBLIC_JATS', ARTICLE, pmcid=PMCID,
                                         geneSymbol='EXAMPLE', variantAliases=['c.123A>G', 'p.T41A']))
        queries = make_plan(loaded)
        self.assertEqual(1, len(queries))
        self.assertTrue(all('999999997' not in q['query'] for q in queries))
        self.assertEqual([], loaded[0]['citationPmids'])

    def test_unmentioned_public_alias_or_gene_rejected(self):
        for gene, alias in [('EXAMPLE', 'c.124A>G'), ('OTHER', 'c.123A>G')]:
            with self.subTest(gene=gene), self.assertRaisesRegex(ValueError, 'NOT_IN_SOURCE'):
                targets_from(self.input('PUBLIC_JATS', ARTICLE, pmcid=PMCID, geneSymbol=gene, variantAliases=[alias]))

    def test_local_annotation_emits_gene_only(self):
        raw = json.dumps({'schemaVersion': 'denovo-local-gene-signals-v1', 'geneSymbol': 'EXAMPLE',
                          'geneAliases': ['OLDGENE']}).encode()
        loaded = targets_from(self.input('ANNOTATION_SIGNALS', raw))
        self.assertEqual('GENE_ONLY', loaded[0]['externalScope'])
        self.assertEqual([], loaded[0]['variantAliases'])
        self.assertEqual([], make_plan(loaded))

    def test_patient_or_arbitrary_fields_not_accepted_as_annotation(self):
        raw = json.dumps({'schemaVersion': 'denovo-local-gene-signals-v1', 'geneSymbol': 'EXAMPLE',
                          'geneAliases': [], 'patientName': 'do-not-send'}).encode()
        with self.assertRaisesRegex(ValueError, 'ANNOTATION_SCHEMA'):
            targets_from(self.input('ANNOTATION_SIGNALS', raw))

    def test_changed_source_and_path_escape_rejected(self):
        path = self.input('CLINVAR_RECORD', RECORD, vcv=VCV)
        (self.root / 'source').write_bytes(RECORD + b' ')
        with self.assertRaisesRegex(ValueError, 'SHA_MISMATCH'): targets_from(path)
        doc = json.loads(path.read_bytes())
        doc['targets'][0]['source']['path'] = str((self.root / 'source').resolve())
        path.write_text(json.dumps(doc))
        with self.assertRaisesRegex(ValueError, 'SOURCE_PATH'): targets_from(path)










    def test_page_limit_validated_before_transport(self):
        for maximum in [0, -1, True, 21]:
            fake = Fake(response())
            with self.subTest(maximum=maximum), self.assertRaisesRegex(ValueError, 'PAGE_BUDGET_INVALID'):
                discover([target()], fake, max_pages=maximum)
            self.assertEqual(0, fake.requests)


    def test_id_alias_bridge_dedup_and_conflict_retained(self):
        candidates = []
        merge_candidate(candidates, paper(row('123', doi='10.1234/a')))
        merge_candidate(candidates, paper({'source': 'PMC', 'id': 'PMC123', 'title': 'Same', 'pmid': '123'}))
        self.assertEqual(1, len(candidates))
        self.assertEqual('PMC123', candidates[0]['pmcid'])
        merge_candidate(candidates, paper(row('456', doi='10.1234/a')))
        self.assertEqual(2, len(candidates))
        self.assertTrue(all('BIBLIOGRAPHIC_IDENTITY_CONFLICT' in p['flags'] for p in candidates))


    def test_closed_access_review_and_retraction_still_visible(self):
        candidate = paper(row(isOpenAccess='N', isRetracted='Y', pubTypeList={'pubType': ['Review']}))
        self.assertEqual('N', candidate['openAccess'])
        self.assertEqual(2, len(candidate['flags']))




if __name__ == '__main__': unittest.main()
