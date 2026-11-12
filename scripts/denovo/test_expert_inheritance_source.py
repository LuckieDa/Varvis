from copy import deepcopy
import tempfile
from pathlib import Path
import unittest
from model_literature_contract import canonical, digest
from test_pp1_specification_context import specification
from expert_inheritance_source import declarations, supply
from literature_context_options import resolve_inheritance


class ExpertInheritanceSourceTest(unittest.TestCase):
    def setUp(self):
        self.spec = specification()
        self.spec['original']['entContent']['shortTitle'] = 'TEST Variant Curation Expert Panel'
        self.spec['original']['ld']['Gene'][0]['entContent']['HGNC'].update(entrez_id='123', status='Approved')
        self.row = {'gene': 'GENE1', 'geneNcbiId': '123', 'mondoId': 'MONDO:0000001', 'ep': 'TEST VCEP',
            'retracted': False, 'moi': 'Autosomal dominant inheritance', 'uuid': 'a'*8+'-'+'a'*4+'-'+'a'*4+'-'+'a'*4+'-'+'a'*12,
            'docVersion': '1.0.0', 'approvedDate': '2025-01-01', 'publishedDate': '2025-02-01'}
        self.desc = {'sourceUrl': 'https://erepo.genome.network/evrepo/api/summary/classifications?columns=gene&matchTypes=exact&values=GENE1'}

    def extract(self, row=None, spec=None):
        return declarations({'data': [row or self.row]}, self.desc, [spec or self.spec])

    def test_exact_identity_and_expert_scope_yield_background_only(self):
        row = {**self.row, 'classification': 'Benign', 'metCodes': ['BA1'], 'summaryDesc': 'not used'}
        r = self.extract(row)[0]
        self.assertEqual('AD', r['mode']); self.assertEqual('HGNC:1', r['hgncId'])
        self.assertEqual('/data/0', r['source']['jsonPointer'])
        self.assertEqual(self.extract()[0], r)
        self.assertNotIn('classification', r); self.assertNotIn('BA1', str(r))

    def test_other_gene_disease_panel_and_retracted_or_unpublished_not_adopted(self):
        for field, value in [('gene', 'OTHER'), ('geneNcbiId', '124'), ('mondoId', 'MONDO:0000002'),
                ('ep', 'OTHER VCEP'), ('retracted', True), ('publishedDate', None), ('moi', 'unknown'), ('uuid', '')]:
            with self.subTest(field=field): self.assertEqual([], self.extract({**self.row, field: value}))
        s = deepcopy(self.spec); s['original']['entContent']['states'][0]['current'] = False
        self.assertEqual([], self.extract(spec=s))

    def test_distinct_modes_retained_and_duplicate_witnesses_do_not_bloat_context(self):
        r = declarations({'data': [self.row, self.row, {**self.row, 'moi': 'Autosomal recessive inheritance'}]}, self.desc, [self.spec])
        self.assertEqual(['AD', 'AR'], [v['mode'] for v in r])

    def test_untrusted_origin_rejected(self):
        self.desc['sourceUrl'] = self.desc['sourceUrl'].replace('erepo.genome.network', 'example.invalid')
        with self.assertRaisesRegex(ValueError, 'SOURCE_URL'): self.extract()

    def test_capture_failure_is_isolated_to_matching_gene_and_conflict_is_not_overridden(self):
        first = {'geneSymbol': 'GENE1', 'hgncId': 'HGNC:1', 'mondoId': 'MONDO:0000001',
                 'publishedInheritance': [], 'sources': []}
        second = {**first, 'geneSymbol': 'OTHER', 'sources': []}
        with tempfile.TemporaryDirectory() as tmp:
            supply(Path(tmp), [{'geneSymbol': 'GENE1', 'captureStatus': 'UNAVAILABLE'}], [self.spec], [first, second])
        resolve_inheritance(first); resolve_inheritance(second)
        self.assertEqual('RESOURCE_UNAVAILABLE', first['inheritanceResolution']['status'])
        self.assertEqual('MISSING', second['inheritanceResolution']['status'])
        first.update(publishedInheritance=['AD'], sources=[{'inheritanceDeclaration': {'status': 'CONFLICT'}}])
        resolve_inheritance(first)
        self.assertEqual('CONFLICT', first['inheritanceResolution']['status'])
        self.assertFalse(first['publishedInheritance'])

    def test_digest_failure_is_visible_and_never_erases_independent_declaration(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); (root/'local').mkdir()
            raw = canonical({'data': [self.row]}); (root/'local/source.json').write_bytes(raw)
            desc = {**self.desc, 'path': 'local/source.json', 'sha256': digest(raw)}
            c = {'geneSymbol': 'GENE1', 'hgncId': 'HGNC:1', 'mondoId': 'MONDO:0000001',
                 'publishedInheritance': [], 'sources': []}
            supply(root, [desc], [self.spec], [c]); resolve_inheritance(c)
            self.assertEqual('VERIFIED', c['inheritanceResolution']['status'])
            self.assertFalse(c['expertInheritanceSupply']['scientificEvidenceAwarded'])
            (root/'local/source.json').write_bytes(b'changed')
            supply(root, [desc], [self.spec], [c])
            self.assertEqual('UNAVAILABLE', c['expertInheritanceSupply']['status'])
            self.assertEqual(['AD'], c['publishedInheritance'])
            other = {**c, 'publishedInheritance': [], 'sources': []}
            supply(root, [desc], [self.spec], [other]); self.assertEqual([], other['publishedInheritance'])


if __name__ == '__main__': unittest.main()
