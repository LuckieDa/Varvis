"""Pinned public-record replay attacks, synthetic sequence, no network."""
from copy import deepcopy
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from urllib.parse import quote
from model_literature_contract import canonical
from test_core_context_review import LocalFixture
from refseq_identity import API


class RefSeqTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.f = LocalFixture(Path(self.tmp.name))
        self.f.doc['groups']['variant']['representations']['value'][0] = 'NM_000001.2'
        self.tx = 'NM_000001.2'; self.change = 'c.2C>G'
        self.item = dict(seq_id=self.tx, position=1, deleted_sequence='C', inserted_sequence='G')
        self.genome = dict(seq_id='NC_000001.11', position=100, deleted_sequence='C', inserted_sequence='G')
        self.context = {'data': {'input_hgvs_validity': 'valid', 'spdis': [self.item]}}
        self.equivalents = {'data': {'spdis': [self.item, self.genome]}}
        self.gb = ('VERSION     NM_000001.2\nFEATURES\n     CDS             1..3\n'
                   '                     /gene="EXAMPLE"\n                     /codon_start=1\n'
                   '                     /db_xref="HGNC:HGNC:1"\n                     /translation="T"\n'
                   'ORIGIN\n        1 acc\n//\n')
        self.seal()

    def seal(self, url=None):
        urls = [API + 'hgvs/' + quote(self.tx + ':' + self.change, safe='') + '/contextuals',
                'https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=nuccore&id=' + self.tx + '&rettype=gb&retmode=text',
                API + 'spdi/' + quote(':'.join(str(self.item[k]) for k in ('seq_id','position','deleted_sequence','inserted_sequence')), safe='') + '/all_equivalent_contextual']
        sources = {}
        for n, raw, expected in zip(('contextual','refseq','equivalents'),
                                  (canonical(self.context),self.gb.encode(),canonical(self.equivalents)),urls):
            sources[n] = dict(self.f.put(n + '.raw', raw), url=url or expected, finalUrl=url or expected, httpStatus=200)
        manifest = dict(schemaVersion='denovo-refseq-snv-cache-v1', transcript=self.tx, coding=self.change, sources=sources)
        self.f.config['refseqMappings'] = [dict(transcript=self.tx, coding=self.change,
            manifest=self.f.put('manifest.json',canonical(manifest)))]

    def resolve(self): return self.f.resolver().resolve(self.f.doc, '1')

    def test_exact_old_version_and_local_reference(self):
        r = self.resolve()
        self.assertEqual(101, r['allele']['position']); self.assertEqual(self.tx, r['transcript'])
        self.assertEqual('T1S', r['proteinChange']); self.assertEqual(3, len(r['mappingSources']))

    def test_refseq_version_cds_translation_and_protein_conflicts(self):
        original = self.gb
        for old, new in [('VERSION     NM_000001.2','VERSION     NM_000001.3'),
                         ('1..3','2..3'), ('/translation="T"','/translation="S"'), ('/codon_start=1','/codon_start=2')]:
            self.gb = original.replace(old,new); self.seal()
            with self.subTest(change=new), self.assertRaises(ValueError): self.resolve()
        self.gb=original; self.seal()
        self.f.doc['groups']['variant']['representations']['value'][2] = 'p.Thr1Arg'
        with self.assertRaisesRegex(ValueError,'PROTEIN_CONSEQUENCE'): self.resolve()

    def test_ambiguous_or_wrong_assembly_mapping_is_not_old_key_fallback(self):
        for rows in ([self.item], [self.item,self.genome,self.genome],
                     [self.item,dict(self.genome,seq_id='NC_000001.10')]):
            self.equivalents['data']['spdis']=rows; self.seal()
            with self.assertRaisesRegex(ValueError,'GRCH38_MAPPING'): self.resolve()

    def test_genomic_reference_and_allele_conflicts(self):
        for g in (dict(self.genome,position=102),dict(self.genome,inserted_sequence='A')):
            self.equivalents['data']['spdis']=[self.item,g]; self.seal()
            with self.assertRaisesRegex(ValueError,'GENOMIC'): self.resolve()

    def test_contextual_repeat_deletion_is_left_normalized_and_reference_checked(self):
        from refseq_identity import deletion_on_reference
        reference = SimpleNamespace(base=lambda contig, position: 'ATCTT'[position - 10])
        spdi = {'seq_id': 'NC_000001.11', 'position': 10,
                'deleted_sequence': 'TCTT', 'inserted_sequence': 'T'}
        self.assertEqual({'assembly': 'GRCh38', 'contig': '1', 'position': 10,
                          'reference': 'ATCT', 'alternate': 'A'}, deletion_on_reference(reference, spdi, 3))
        with self.assertRaisesRegex(ValueError, 'GENOMIC_REFERENCE'):
            deletion_on_reference(reference, {**spdi, 'deleted_sequence': 'TATT'}, 3)
        with self.assertRaisesRegex(ValueError, 'GENOMIC_ALLELES'):
            deletion_on_reference(reference, {**spdi, 'inserted_sequence': 'G'}, 3)

    def test_raw_tamper_and_non_official_sources_rejected(self):
        (self.f.local/'refseq.raw').write_bytes(b'changed')
        with self.assertRaises(ValueError): self.resolve()
        self.seal(url='https://untrusted.invalid/data')
        with self.assertRaisesRegex(ValueError,'OFFICIAL_SOURCE'): self.resolve()

    def test_source_input_hgvs_and_version_must_match(self):
        self.context['data']['input_hgvs_validity']='invalid'; self.seal()
        with self.assertRaisesRegex(ValueError,'HGVS'): self.resolve()
        self.context['data']['input_hgvs_validity']='valid'
        self.context['data']['spdis']=[dict(self.item,seq_id='NM_000001.3')]; self.seal()
        with self.assertRaisesRegex(ValueError,'CONTEXTUAL'): self.resolve()

    def test_bounded_acquisition_prepares_reusable_cache_without_silent_activation(self):
        from prepare_refseq_identity import prepare
        sources = [canonical(self.context), self.gb.encode(), canonical(self.equivalents)]; calls=[]
        def transport(url):
            calls.append(url); return sources[len(calls)-1], 200, url
        out = self.f.root/'local/new-cache'
        descriptor = prepare(self.f.root, out, self.tx, self.change, transport)
        self.assertEqual(3, len(calls)); self.assertEqual(self.tx, descriptor['transcript'])
        self.assertEqual('local/manifest.json', self.f.config['refseqMappings'][0]['manifest']['path'])
        self.f.config['refseqMappings']=[descriptor]
        self.assertEqual(101,self.resolve()['allele']['position'])
        with self.assertRaises(ValueError): prepare(self.f.root,out,self.tx,self.change,transport)
        self.assertEqual(3,len(calls))

    def test_failed_official_response_stops_without_retry_or_completed_descriptor(self):
        from prepare_refseq_identity import prepare
        for mode in ('redirect', 'limit', 'error'):
            calls=[]; out=self.f.root/('local/'+mode)
            def transport(url):
                calls.append(url)
                return (b'x'*(4*1024*1024+1) if mode=='limit' else b'{}',429 if mode=='error' else 200,
                        'https://untrusted.invalid/' if mode=='redirect' else url)
            with self.assertRaises(ValueError): prepare(self.f.root,out,self.tx,self.change,transport)
            self.assertEqual(1,len(calls)); self.assertFalse((out/'descriptor.json').exists())


if __name__ == '__main__': unittest.main()
