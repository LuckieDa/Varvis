"""Public API/identity regressions. All inline records are synthetic."""
import json
import unittest

import litvar_literature as litvar
from test_discover_public_literature import target


class UncertainInventoryTests(unittest.TestCase):
    def test_uncertain_hgvs_inventory_is_readable_and_url_encoded(self):
        for name in ('p.M1?', 'c.4003-?_4284+?del', 'c.?del+', 'c.1164_1188del]'):
            identifier = 'litvar@#123#' + name
            body = repr({'_id': identifier, 'pmids_count': 1}).encode()
            self.assertEqual(identifier, litvar.decode(body, 'gene')[0]['_id'])
            self.assertIn('%3F' if '?' in name else '%5D', litvar.url_for('summary', identifier))

    def test_missing_autocomplete_gene_is_unresolved_not_a_match(self):
        rows = litvar.decode(b'[{"_id":"litvar@rs123##","name":"c.1A>G"}]', 'autocomplete')
        self.assertEqual('UNRESOLVED_GENE_MAPPING', litvar.relation(rows[0], 'EXAMPLE', ['c.1A>G']))
        self.assertEqual([], litvar.decode(b'{"_id":"litvar@rs123##","name":"c.1A>G"}', 'summary')['gene'])

    def test_inventory_does_not_admit_whitespace_or_code(self):
        for name in ('p.M1? bad', 'p.M1?\n', "p.M1?';exec('x')"):
            with self.assertRaises(ValueError):
                litvar.url_for('summary', 'litvar@#123#' + name)


def raw(value):
    return json.dumps(value).encode()


def entry(identifier='litvar@rs123##', name='p.T41A', gene='EXAMPLE', **extra):
    return {'_id': identifier, 'gene': [gene], 'name': name, 'hgvs': name, **extra}


class Fixture:
    def __init__(self, responses):
        self.responses, self.calls = responses, []

    def get_litvar(self, operation, value):
        self.calls.append((operation, value))
        result = self.responses[(operation, value)]
        if isinstance(result, Exception):
            raise result
        return result if isinstance(result, bytes) else raw(result)


def query(aliases):
    q = litvar.plans([target(aliases=aliases)])[0]
    q['issues'] = []
    return q


def pubs(pmid):
    return {'pmids': [pmid], 'pmids_count': 1}


class InterfaceTests(unittest.TestCase):
    def test_missing_autocomplete_gene_requires_matching_summary(self):
        for gene in ('EXAMPLE', 'OTHER', None):
            a = entry(); del a['gene']
            summary = entry(gene=gene)
            if gene is None: del summary['gene']
            f = Fixture({('autocomplete', 'EXAMPLE p.T41A'): [a],
                         ('summary', a['_id']): summary,
                         ('publications', a['_id']): pubs('111')})
            q = query(['p.T41A'])
            refs = litvar.resolve(q, f)
            self.assertEqual([('MED', '111')] if gene == 'EXAMPLE' else [], refs)
            self.assertEqual(gene == 'EXAMPLE', ('publications', a['_id']) in f.calls)
            if gene is None:
                self.assertIn('LITERATURE_SEARCH_LITVAR_SUMMARY_GENE_MISSING', q['issues'])

    def test_all_five_fixed_host_operations(self):
        for op, value, suffix in [
            ('autocomplete', 'EXAMPLE p.T41A', 'autocomplete/?'),
            ('summary', 'litvar@rs123##', 'get/litvar%40rs123%23%23'),
            ('publications', 'litvar@rs123##', 'get/litvar%40rs123%23%23/publications'),
            ('gene', 'EXAMPLE', 'search/gene/EXAMPLE'),
            ('sensor', 'rs123', 'sensor/rs123'),
        ]:
            with self.subTest(operation=op):
                url = litvar.url_for(op, value)
                self.assertTrue(url.startswith('https://www.ncbi.nlm.nih.gov/research/litvar2-api/'))
                self.assertIn(suffix, url)

    def test_native_identifier_inputs_without_manufactured_gene_prefix(self):
        for value in ['rs123', 'CA123', 'NC_000007.14:140753335:A:C', 'p.V600E']:
            self.assertIn('query=', litvar.url_for('autocomplete', value))

    def test_all_sourced_aliases_planned_and_equivalent_protein_spellings_coalesced(self):
        q = query(['p.Thr41Ala', 'p.T41A', 'c.123A>G', 'NM_123.1:c.123A>G', 'rs123'])
        self.assertEqual(4, len(q['aliases']))
        self.assertIn('rs123', q['aliases'])
        self.assertIn('NM_123.1:c.123A>G', q['aliases'])

    def test_later_alias_and_multiple_entries_retained_with_per_reference_provenance(self):
        a, b = entry(), entry('litvar@rs124##', 'c.123A>G')
        f = Fixture({('autocomplete', 'EXAMPLE p.T41A'): [a],
                     ('autocomplete', 'EXAMPLE c.123A>G'): [b],
                     ('summary', a['_id']): a, ('summary', b['_id']): b,
                     ('publications', a['_id']): pubs('111'), ('publications', b['_id']): pubs('222')})
        q = query(['p.T41A', 'c.123A>G'])
        refs = litvar.resolve(q, f)
        self.assertEqual({('MED', '111'), ('MED', '222')}, set(refs))
        self.assertEqual({a['_id'], b['_id']}, set(q['litvarVariantIds']))
        links = {r['id']: r['variantLinks'] for r in q['referenceLinks']}
        self.assertEqual([a['_id']], [v['litvarVariantId'] for v in links['111']])
        self.assertEqual([b['_id']], [v['litvarVariantId'] for v in links['222']])
        self.assertTrue(all(not r['alleleIdentityVerified'] for r in q['variantCandidates']))

    def test_canonical_name_difference_retained_as_unresolved_not_exact(self):
        a = entry(name='p.R1352Q')
        f = Fixture({('autocomplete', 'EXAMPLE p.R1348Q'): [a],
                     ('summary', a['_id']): a, ('publications', a['_id']): pubs('111')})
        q = query(['p.R1348Q'])
        self.assertEqual([('MED', '111')], litvar.resolve(q, f))
        self.assertEqual('UNRESOLVED_NAME_MAPPING', q['variantCandidates'][0]['discoveryRelation'])
        self.assertFalse(q['variantCandidates'][0]['alleleIdentityVerified'])

    def test_multiple_same_name_ids_not_arbitrarily_selected(self):
        a, b = entry(), entry('litvar@rs124##')
        f = Fixture({('autocomplete', 'EXAMPLE p.T41A'): [a, b],
                     ('summary', a['_id']): a, ('summary', b['_id']): b,
                     ('publications', a['_id']): pubs('111'), ('publications', b['_id']): pubs('222')})
        q = query(['p.T41A'])
        self.assertEqual(2, len(litvar.resolve(q, f)))
        self.assertIsNone(q.get('litvarVariantId'))
        self.assertFalse(any(v['alleleIdentityVerified'] for v in q['variantCandidates']))

    def test_successful_references_survive_later_budget_failure(self):
        a = entry(name='c.123A>G')
        f = Fixture({('autocomplete', 'EXAMPLE c.123A>G'): [a], ('summary', a['_id']): a,
                     ('publications', a['_id']): pubs('111'),
                     ('autocomplete', 'EXAMPLE p.T41A'): ValueError('LITERATURE_SEARCH_REQUEST_BUDGET')})
        q = query(['c.123A>G', 'p.T41A'])
        self.assertEqual([('MED', '111')], litvar.resolve(q, f))
        self.assertEqual(['p.T41A'], q['unqueriedAliases'])
        self.assertIn('LITERATURE_SEARCH_REQUEST_BUDGET', q['issues'])

    def test_duplicate_id_across_aliases_fetches_summary_and_publications_once(self):
        a = entry()
        f = Fixture({('autocomplete', 'EXAMPLE c.123A>G'): [a],
                     ('autocomplete', 'EXAMPLE p.T41A'): [a],
                     ('summary', a['_id']): a, ('publications', a['_id']): pubs('111')})
        q = query(['c.123A>G', 'p.T41A'])
        litvar.resolve(q, f)
        self.assertEqual(1, f.calls.count(('summary', a['_id'])))
        self.assertEqual(1, f.calls.count(('publications', a['_id'])))

    def test_summary_mismatch_cannot_fetch_publications(self):
        a = entry()
        for bad in [entry('litvar@rs999##'), entry(gene='OTHER')]:
            f = Fixture({('autocomplete', 'EXAMPLE p.T41A'): [a], ('summary', a['_id']): bad})
            q = query(['p.T41A'])
            self.assertEqual([], litvar.resolve(q, f))
            self.assertFalse(any(op == 'publications' for op, _ in f.calls))
            self.assertTrue(q['issues'])

    def test_real_gene_stream_shape_is_data_never_executed(self):
        body = b"{'_id': 'litvar@rs123##', 'pmids_count': 1, 'rsid': 'rs123'}\n{'_id': 'litvar@#123#', 'pmids_count': 2}\n"
        rows = litvar.decode(body, 'gene')
        self.assertEqual(2, len(rows))
        self.assertEqual('litvar@#123#p.T41=', litvar.decode(
            b"{'_id': 'litvar@#123#p.T41=', 'pmids_count': 1}", 'gene')[0]['_id'])
        for bad in [b"__import__('os').system('echo unsafe')", b"{'_id':'litvar@rs123##','_id':'litvar@rs124##'}",
                    b"{'_id': 'litvar@rs123##', 'pmids_count': -1}"]:
            with self.assertRaises(ValueError): litvar.decode(bad, 'gene')

    def test_one_conflicting_summary_does_not_hide_later_valid_entry(self):
        a, b = entry(), entry('litvar@rs124##')
        f = Fixture({('autocomplete', 'EXAMPLE p.T41A'): [a, b],
                     ('summary', a['_id']): entry('litvar@rs999##'), ('summary', b['_id']): b,
                     ('publications', b['_id']): pubs('111')})
        q = query(['p.T41A'])
        self.assertEqual([('MED', '111')], litvar.resolve(q, f))
        self.assertTrue(q['resolutionComplete'])

    def test_gene_stream_partial_row_rejected_not_empty(self):
        with self.assertRaises(ValueError): litvar.decode(b"{'_id': 'litvar@rs123##'", 'gene')

    def test_sensor_is_not_a_no_literature_gate(self):
        self.assertEqual({}, litvar.decode(b'{}', 'sensor'))
        self.assertEqual('rs123', litvar.decode(raw({'rsid': 'rs123', 'pmids_count': 2,
            'link': 'https://www.ncbi.nlm.nih.gov/research/litvar2/docsum?query=rs123'}), 'sensor')['rsid'])


if __name__ == '__main__':
    unittest.main()
