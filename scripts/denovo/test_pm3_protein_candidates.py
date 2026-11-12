from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
from test_core_context_review import LocalFixture
from pm3_protein_candidates import candidates
from pm3_identity import resolve_case
from test_pm3_automatic_facts import source, table
from pm3_source_aliases import crosswalks


class ProteinCandidates(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.f=LocalFixture(Path(self.temp.name))

    def test_all_codon_alternatives_not_just_convenient_snv(self):
        r=candidates(self.f.resolver(),'EXAMPLE','p.Thr1Ser',['NM_000001.1'])
        self.assertTrue(r['scopeComplete']);self.assertFalse(r['paperAlleleEstablished'])
        self.assertEqual(6,len(r['candidates']))
        self.assertEqual({'c.1A>T','c.2C>G'}, {c['codingChanges'][0] for c in r['candidates'] if c['substitutionType']=='SNV'})
        self.assertEqual(4,sum(c['substitutionType']=='MULTINUCLEOTIDE' for c in r['candidates']))
        self.assertIn('PM3_PROTEIN_MULTINUCLEOTIDE_ALTERNATIVES_EXIST',r['issues'])

    def test_missing_transcript_is_search_scope_not_assumed_paper_transcript(self):
        r=candidates(self.f.resolver(),'EXAMPLE','p.T1S',[])
        self.assertEqual('LOCAL_MANE_GENE_TRANSCRIPTS_ONLY',r['searchScope'])
        self.assertIn('PM3_PROTEIN_PAPER_TRANSCRIPT_NOT_ESTABLISHED',r['issues'])
        self.assertFalse(r['businessPublishable'])

    def test_single_snv_is_not_uniqueness_when_other_codons_exist(self):
        r=candidates(self.f.resolver(),'EXAMPLE','p.T1I',['NM_000001.1'])
        self.assertEqual(1,sum(c['substitutionType']=='SNV' for c in r['candidates']))
        self.assertEqual(3,len(r['candidates']))
        self.assertFalse(r['paperAlleleEstablished'])
        self.assertNotIn('local/',str(r['resourcePins']))

    def test_partial_search_never_declared_complete(self):
        r=candidates(self.f.resolver(),'EXAMPLE','p.T1S',['NM_000001.1','NM_000001.2'])
        self.assertEqual(6,len(r['candidates']))
        self.assertFalse(r['scopeComplete']);self.assertEqual('INCOMPLETE',r['status'])

    def test_negative_strand_and_exon_split_reuse_forward_validation(self):
        for kw,expected in [({'strand':'-'},101),({'split':True},200)]:
            with self.subTest(kw=kw):
                f=LocalFixture(Path(self.temp.name),**kw)
                r=candidates(f.resolver(),'EXAMPLE','p.T1S',['NM_000001.1'])
                c=next(c for c in r['candidates'] if c['codingChanges']==['c.2C>G'])
                self.assertEqual(expected,c['allele']['position'])
                self.assertEqual('G' if kw.get('strand')=='-' else 'C',c['allele']['reference'])

    def test_wrong_residue_version_gene_and_unsupported_never_guess(self):
        for gene,aa,tx,code in [('EXAMPLE','p.A1S',[],'PM3_PROTEIN_REFERENCE_AMINO_ACID_MISMATCH'),
                ('EXAMPLE','p.T1S',['NM_000001.2'],'PM3_PROTEIN_TRANSCRIPT_MAPPING_MISSING_OR_AMBIGUOUS'),
                ('OTHER','p.T1S',[],'PM3_PROTEIN_TRANSCRIPT_RESOURCE_NOT_FOUND'),
                ('EXAMPLE','p.T1del',[],'PM3_PROTEIN_SUBSTITUTION_REQUIRED')]:
            r=candidates(self.f.resolver(),gene,aa,tx)
            self.assertFalse(r['scopeComplete']);self.assertEqual([],r['candidates']);self.assertIn(code,r['issues'])

    def test_changed_resource_and_duplicate_mapping_remain_failures(self):
        resolver=self.f.resolver();resolver.load();resolver.mane*=2
        self.assertIn('PM3_PROTEIN_TRANSCRIPT_MAPPING_MISSING_OR_AMBIGUOUS',candidates(resolver,'EXAMPLE','p.T1S',[])['issues'])
        (self.f.local/'data.jsonl').write_bytes(b'changed')
        self.assertFalse(candidates(self.f.resolver(),'EXAMPLE','p.T1S',[])['scopeComplete'])

    def test_normal_case_path_retains_candidates_without_resolving_paper_allele(self):
        case={'caseLabel':'A','semantics':{'observations':[{'geneSymbol':'EXAMPLE',
            'targetRepresentation':'p.T1S','partnerRepresentation':None,'reportedTranscripts':[]}]}}
        before=deepcopy(case)
        r=resolve_case(case,self.f.resolver(),{'position':101})[0]
        self.assertEqual({},r['identities']);self.assertNotIn('alleleIds',r)
        self.assertEqual(6,len(r['issues'][0]['candidateSearch']['candidates']))
        self.assertEqual(before,case)

    def test_real_source_gate_precedes_identity_search(self):
        case={'caseLabel':'148','semantics':{'observations':[]}}
        from unittest.mock import Mock
        resolver=Mock()
        self.assertEqual([],resolve_case(case,resolver));resolver.load.assert_not_called()

    def test_paper_crosswalk_can_use_explicit_case_transcript(self):
        s=source(table().replace('<th>Transcript</th>','').replace('<td>NM_000001.1</td>',''))
        c={'caseLabel':'1','sourceAlleleCrosswalks':crosswalks(s),'semantics':{'observations':[{
            'geneSymbol':'EXAMPLE','targetRepresentation':'p.T1S','partnerRepresentation':None,
            'reportedTranscripts':['NM_000001.1']}]}}
        self.assertEqual('SOURCE_TRANSCRIPT_LOOKUP_REQUIRED',c['sourceAlleleCrosswalks'][0]['qualification'])
        self.assertEqual('LOCAL_IDENTITIES_VERIFIED',resolve_case(c,self.f.resolver())[0]['status'])

    def test_paper_dna_pair_can_automatically_get_transcript_from_pinned_pmid_record(self):
        from test_literature_source_assertions import RECORD, VCV, PMID
        raw=RECORD.replace(b'NM_999999997.1',b'NM_000001.1').replace(b'HGNC:999999997',b'HGNC:1')
        raw=raw.replace(b'c.123A&gt;G',b'c.2C&gt;G').replace(b'p.Thr41Ala',b'p.Thr1Ser')
        raw=raw.replace(b'positionVCF="123" referenceAlleleVCF="A"',b'positionVCF="101" referenceAlleleVCF="C"')
        self.f.config['clinvar']=[{**self.f.put('record.xml',raw),'accession':VCV}]
        s=source(table().replace('<th>Transcript</th>','').replace('<td>NM_000001.1</td>',''))
        c={'caseLabel':'1','paperPmid':PMID,'sourceAlleleCrosswalks':crosswalks(s),'semantics':{'observations':[{
            'geneSymbol':'EXAMPLE','targetRepresentation':'p.T1S','partnerRepresentation':None,'reportedTranscripts':[]}]}}
        r=resolve_case(c,self.f.resolver())[0]
        self.assertEqual('LOCAL_IDENTITIES_VERIFIED',r['status'])
        self.assertEqual('NM_000001.1',r['identities']['target']['transcript'])
        self.assertEqual('c.2C>G',r['identities']['target']['coding'])
        self.assertTrue(r['identities']['target']['sourceTranscriptLink']['clinvarMatches'])
        conflict=raw.replace(b'alternateAlleleVCF="G"',b'alternateAlleleVCF="T"')
        self.f.config['clinvar']=[{**self.f.put('conflict.xml',conflict),'accession':VCV}]
        rejected=resolve_case(c,self.f.resolver())[0]
        self.assertEqual({},rejected['identities'])
        self.assertEqual('PM3_PROTEIN_RESOURCE_IDENTITY_CONFLICT',rejected['issues'][0]['reasonCode'])
        self.f.config['clinvar']=[{**self.f.put('record.xml',raw),'accession':VCV}]
        c['paperPmid']='1'
        self.assertEqual({},resolve_case(c,self.f.resolver())[0]['identities'])
        c['paperPmid']=PMID;c['sourceAlleleCrosswalks']=[]
        self.assertEqual({},resolve_case(c,self.f.resolver())[0]['identities'])


if __name__=='__main__':unittest.main()
