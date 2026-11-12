import json,copy,unittest
import prepare_published_transcript_models as p
class GeneIdentityTest(unittest.TestCase):
    def setup(self):
        model={'transcript':'ENST000001.2','protein':'ENSP000001.3','hgncId':'HGNC:1','geneSymbol':'ANY','assembly':'GRCh38',
            'contig':'1','strand':-1,'cdsLength':9,'exons':[{'ordinal':1,'genomicStart':20,'genomicEnd':28,'cdsStart':1,'cdsEnd':9}],
            'cdsSequence':'ATGAAATAA','sequenceVerification':{'original':'unchanged'}}
        row={k:v for k,v in model.items() if k not in ['cdsSequence','sequenceVerification']};row['geneId']='ENSG000001.4';return model,row
    def run_case(self,m,r):
        raw=json.dumps(r).encode();return p.supplement_gene_identity(json.dumps(m).encode(),raw,p.sha(raw),'a'*64)
    def test_preserves_sequence_and_existing_qualification_without_new_claim(self):
        m,r=self.setup();out=self.run_case(m,r);self.assertEqual(out['geneId'],r['geneId']);self.assertNotIn('geneId',m)
        self.assertEqual(out['sequenceVerification'],m['sequenceVerification']);self.assertEqual(out['cdsSequence'],m['cdsSequence'])
        self.assertFalse(out['geneIdentitySupplement']['newSequenceOrRnaApproval'])
    def test_same_symbol_or_coding_sequence_does_not_override_identity_or_exon_conflict(self):
        for field,value in [('transcript','ENST000001.3'),('protein','ENSP000001.4'),('hgncId','HGNC:2'),('strand',1),('cdsLength',12),('exons',[])]:
            m,r=self.setup();r[field]=value
            with self.subTest(field=field),self.assertRaises(ValueError):self.run_case(m,r)
    def test_existing_different_gene_identifier_refused(self):
        m,r=self.setup();m['geneId']='ENSG000002.1'
        with self.assertRaises(ValueError):self.run_case(m,r)
    def test_pinned_bytes_required(self):
        m,r=self.setup()
        with self.assertRaises(ValueError):p.supplement_gene_identity(json.dumps(m).encode(),json.dumps(r).encode(),'b'*64,'a'*64)
if __name__=='__main__':unittest.main()
