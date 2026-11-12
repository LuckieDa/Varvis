"""Engineering tests for independent source/science checks; not business acceptance."""
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts.ps1_pm5.verify_hnf1a_comparison import (
    DATA, RES, read, file_sha, original_matrix, expected_strength, verify_reference,
    verify_resources, metric, splice_scores, e1,
)
from scripts.ps1_pm5.hnf1a_source_fetch import fetch
from scripts.ps1_pm5.hnf1a_network_acceptance import validate_public_vcf, CONTROLS


class IndependentHnf1aComparisonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.model=read(RES/'pvs1-hnf1a-transcript-model-v1.json')
        cls.refs=read(DATA/'reference-audit.json')['references']

    def test_original_public_reference_records_and_schema_readonly(self):
        paths=list(DATA.iterdir()); before={p.name:file_sha(p) for p in paths}
        verify_resources()
        self.assertEqual(before,{p.name:file_sha(p) for p in paths})

    def test_scv_and_original_erepo_criteria_are_verified_independently(self):
        for ref in self.refs:verify_reference(ref,self.model)

    def test_circular_criteria_are_not_reclassified(self):
        ref=copy.deepcopy(self.refs[0]);ref['independentCriteria'].append('PS1')
        with self.assertRaisesRegex(ValueError,'applied criteria'):verify_reference(ref,self.model)

    def test_original_expert_classification_must_match(self):
        ref=copy.deepcopy(self.refs[1]);ref['classification']='Pathogenic'
        with self.assertRaisesRegex(ValueError,'classification'):verify_reference(ref,self.model)

    def test_original_evaluation_date_must_match(self):
        ref=copy.deepcopy(self.refs[0]);ref['evaluationDate']='2026-01-01'
        with self.assertRaisesRegex(ValueError,'evaluation date'):verify_reference(ref,self.model)

    def test_disease_mismatch_cannot_be_hidden_by_source_label(self):
        ref=copy.deepcopy(self.refs[0]);ref['mondoId']='MONDO:0000001'
        with self.assertRaisesRegex(ValueError,'reference context'):verify_reference(ref,self.model)

    def test_transcript_version_is_required(self):
        ref=copy.deepcopy(self.refs[0]);ref['transcriptId']='ENST00000257555.10'
        with self.assertRaisesRegex(ValueError,'transcript version'):verify_reference(ref,self.model)

    def test_reference_codon_must_match_original_vep(self):
        ref=copy.deepcopy(self.refs[0]);ref['selectedVep']['codons']='GGA/AGA'
        with self.assertRaisesRegex(ValueError,'VEP codon'):verify_reference(ref,self.model)

    def test_grantham_direction_symmetry_and_inclusive_boundary(self):
        values=original_matrix(DATA/'grantham-GRAR740104.txt')
        self.assertEqual((values['W','C'],values['W','R'],values['G','R']),(215,101,125))
        self.assertTrue(all(values[a,b]==values[b,a] for a,b in values))
        for target,expected in [(124,'SUPPORTING'),(125,'MODERATE'),(126,'MODERATE')]:
            self.assertEqual(expected_strength('PM5','Pathogenic',target,125),expected)
            self.assertEqual(expected_strength('PM5','Likely pathogenic',target,125),'SUPPORTING')
        self.assertEqual(expected_strength('PS1','Pathogenic',125,125),'STRONG')
        self.assertEqual(expected_strength('PS1','Likely pathogenic',125,125),'MODERATE')

    def test_unqualified_reference_or_rule_is_not_accepted(self):
        for code,classification in [('PS1','VUS'),('PM5','Benign'),('OTHER','Pathogenic')]:
            with self.assertRaises(ValueError):expected_strength(code,classification,125,125)

    def test_duplicate_atomic_measurement_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'duplicate measurement'):
            metric({'measurements':[{'metricId':'x','rawValue':'P'},{'metricId':'x','rawValue':'LP'}]},'x')

    def test_display_cannot_replace_raw_splice_score(self):
        allele={'assembly':'GRCh38','contig':'12','position':1,'reference':'G','alternate':'A'}
        raw={'status':'SUCCEEDED','results':[{'queryId':'TARGET','status':'HIT','exactAlleleMatch':True,'allele':allele,
              'annotations':[{'geneSymbol':'HNF1A','deltaScores':dict.fromkeys(('DS_AG','DS_AL','DS_DG','DS_DL'),'0.2')}]}]}
        v={'rawSpliceBatch':raw,'spliceEvidence':[{'queryId':'TARGET','allele':allele,'maximumScore':0.01}],
           'scientificDecisions':{'rawSpliceBatchSha256':e1.canonical_sha256(raw)}}
        with self.assertRaisesRegex(ValueError,'raw/displayed score'):splice_scores(v)

    def test_fixed_public_vcf_is_small_and_only_synthetic(self):
        raw='##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tsynthetic-proband\n'
        raw+=''.join(f'{c}\t{p}\t.\t{r}\t{a}\t100\tPASS\t.\tGT\t0/1\n' for c,p,r,a in CONTROLS)
        validate_public_vcf(raw.encode())
        with self.assertRaises(ValueError):validate_public_vcf(raw.replace('synthetic-proband','patient').encode())
        with self.assertRaises(ValueError):validate_public_vcf(raw.replace('0/1','1/1').encode())


class BoundedPublicFetchTest(unittest.TestCase):
    def test_no_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'source';p.write_bytes(b'original')
            with patch('urllib.request.urlopen') as network:
                with self.assertRaises(ValueError):fetch('https://example.org',p)
                network.assert_not_called()
            self.assertEqual(p.read_bytes(),b'original')

    def test_oversized_source_has_failure_receipt_and_no_partial_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'source'
            response=io.BytesIO(b'abcd');response.status=200;response.url='https://example.org';response.headers={}
            with patch('urllib.request.urlopen',return_value=response):
                with self.assertRaisesRegex(ValueError,'byte limit'):fetch('https://example.org',p,limit=3)
            self.assertFalse(p.exists());self.assertEqual(read(p.with_name('source.receipt.json'))['errorType'],'ValueError')

    def test_success_has_hash_and_immutable_receipt(self):
        with tempfile.TemporaryDirectory() as tmp:
            p=Path(tmp)/'source';response=io.BytesIO(b'public');response.status=200;response.url='https://example.org';response.headers={}
            with patch('urllib.request.urlopen',return_value=response):receipt=fetch('https://example.org',p)
            self.assertEqual(receipt['sha256'],file_sha(p))
            with self.assertRaises(ValueError):fetch('https://example.org',p)


if __name__=='__main__':unittest.main()
