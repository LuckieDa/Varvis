"""Synthetic source preservation and bounded compiler failures; no network or scientific assertions."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
import prepare_splice_knowledge as subject
from test_prepare_reference_evidence import XML, ALLELE


class SpliceKnowledgeCompilerTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup);self.root=Path(self.tmp.name)
        self.text='Independent observations. ACMG/AMP criteria applied, as specified by TEST (specification version 1.1):PVS1, PP1_Strong, PS1_Supporting.'
        self.raw=(XML.replace(b'criteria provided, single submitter',b'reviewed by expert panel')
                  .replace(b'SubmitterName="Synthetic lab"',b'SubmitterName="Synthetic lab" OrgID="1"')
                  .replace(b'Fictional evidence mentions PS1; no independence inferred.',self.text.encode())
                  .replace(b'DB="MedGen" ID="C123"',b'DB="MONDO" ID="MONDO:0000001"'))
        (self.root/'source.xml').write_bytes(self.raw);(self.root/'policy.html').write_bytes(b'TEST splice clause')
        self.manifest=dict(schemaVersion='ps1-splice-source-manifest-v1',policies=[dict(sourceFile='policy.html',sourceSha256=subject.base.sha(b'TEST splice clause'),sourceClause='TEST splice clause')],
                          records=[dict(variationId='123',scv='SCV000001.2',geneSymbol='TEST',xmlFile='source.xml',xmlSha256=subject.base.sha(self.raw),allele=ALLELE)])
    def run_compiler(self,manifest=None):
        raw=json.dumps(manifest or self.manifest).encode();p=self.root/'manifest.json';p.write_bytes(raw)
        return subject.prepare(p,subject.base.sha(raw),self.root/'out')
    def test_full_source_and_date_preserved_without_target_pair_or_approval(self):
        result=self.run_compiler();out=json.loads((self.root/'out/knowledge.json').read_bytes());r=out['references'][0]
        self.assertEqual(self.raw,(self.root/'out/source.xml').read_bytes());self.assertEqual(self.text,r['originalText']);self.assertEqual('2020-01-02',r['evaluationDate'])
        self.assertEqual(['PVS1','PP1_Strong','PS1_Supporting'],r['criteria']);self.assertEqual('1.1',r['originalVersion'])
        self.assertFalse(out['scientificApprovalGranted']);self.assertFalse(out['preparation']['pairSpecificApprovalCreated']);self.assertEqual(1,result['records'])
    def test_input_and_output_pins_cannot_be_bypassed(self):
        for name in ['xmlSha256','variationId','scv','geneSymbol']:
            with self.subTest(name=name):
                changed=copy.deepcopy(self.manifest);changed['records'][0][name]='invalid'
                with self.assertRaises(ValueError):self.run_compiler(changed)
                self.assertFalse((self.root/'out').exists())
    def test_missing_original_list_is_a_gap(self):
        raw=self.raw.replace(self.text.encode(),b'Pathogenic without an explicit list.')
        (self.root/'source.xml').write_bytes(raw);self.manifest['records'][0]['xmlSha256']=subject.base.sha(raw)
        with self.assertRaisesRegex(ValueError,'EXPLICIT_FINAL_LIST_REQUIRED'):self.run_compiler()
    def test_traversal_stops_before_opening_external_file(self):
        self.manifest['records'][0]['xmlFile']='../outside.xml'
        with self.assertRaisesRegex(ValueError,'SOURCE_FILENAME_REQUIRED'):self.run_compiler()
    def test_existing_output_is_not_overwritten(self):
        self.run_compiler();before=(self.root/'out/knowledge.json').read_bytes()
        with self.assertRaisesRegex(ValueError,'OUTPUT_EXISTS_REFUSE_OVERWRITE'):self.run_compiler()
        self.assertEqual(before,(self.root/'out/knowledge.json').read_bytes())

    def test_transcript_model_is_preserved_and_pinned_not_scientifically_approved(self):
        raw=b'{"fixture":"technical model, runtime validates full bridge"}'
        (self.root/'model.json').write_bytes(raw)
        self.manifest['transcriptModels']=[dict(file='model.json',sha256=subject.base.sha(raw),sourceTranscript='NM_000001.2')]
        self.run_compiler();out=json.loads((self.root/'out/knowledge.json').read_bytes())
        self.assertEqual(self.manifest['transcriptModels'],out['transcriptModels']);self.assertEqual(raw,(self.root/'out/model.json').read_bytes())
        self.assertFalse(out['scientificApprovalGranted'])

    def test_changed_transcript_model_is_rejected_before_output(self):
        (self.root/'model.json').write_bytes(b'{}')
        self.manifest['transcriptModels']=[dict(file='model.json',sha256='0'*64,sourceTranscript='NM_000001.2')]
        with self.assertRaisesRegex(ValueError,'TRANSCRIPT_MODEL_HASH_MISMATCH'):self.run_compiler()
        self.assertFalse((self.root/'out').exists())


if __name__=='__main__':unittest.main()
