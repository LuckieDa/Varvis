"""Business transport metadata checks, without importing TensorFlow."""
import json
import copy
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import unittest
import tempfile
import run_spliceai_native_business as native


class BusinessTest(unittest.TestCase):
    def request(self):
        return {"requestSchemaVersion":native.REQUEST_SCHEMA,"requestId":"fixed-selected","assembly":"GRCh38","distanceBp":4999,"mask":0,
                "variants":[{"queryId":"one","geneSymbol":"TEST","selectedTranscriptId":"ENST00000000001.2",
                    "allele":{"assembly":"GRCh38","contig":"1","position":20000,"reference":"A","alternate":"G"},
                    "selectedAnnotation":{"transcriptId":"ENST00000000001.2","geneId":"ENSG00000000001.1","geneSymbol":"TEST",
                        "contig":"1","strand":"-","start":19000,"end":23000,"exons":[[19000,20000],[22000,23000]]}}]}

    def test_official_coordinate_file_on_both_strands_and_no_overwrite(self):
        for strand in ('+', '-'):
            req=self.request();req['variants'][0]['selectedAnnotation']['strand']=strand
            with tempfile.TemporaryDirectory() as directory:
                path=Path(directory)/'annotations.tsv'; sha=native.write_selected_annotations(req,path)
                self.assertEqual(path.read_text(), '#NAME\tCHROM\tSTRAND\tTX_START\tTX_END\tEXON_START\tEXON_END\n'
                    f'TEST\t1\t{strand}\t18999\t23000\t18999,21999,\t20000,23000,\n')
                self.assertEqual(sha,native.engine.raw.sha256_file(path))
                with self.assertRaises(FileExistsError):native.write_selected_annotations(req,path)

    def test_same_allele_distinct_transcript_accepted_but_exact_unit_duplicate_rejected(self):
        req=self.request();other=copy.deepcopy(req['variants'][0]);other['queryId']='two'
        other['selectedTranscriptId']='ENST00000000002.1';other['selectedAnnotation']['transcriptId']=other['selectedTranscriptId']
        req['variants'].append(other);self.assertEqual(native.validate_selected_request(req),req)
        other['selectedTranscriptId']=req['variants'][0]['selectedTranscriptId']
        other['selectedAnnotation']['transcriptId']=other['selectedTranscriptId']
        with self.assertRaisesRegex(native.engine.raw.ProbeError,'UNIT_DUPLICATED'):native.validate_selected_request(req)

    def test_invalid_selected_annotation_matrix(self):
        mutations=[lambda a:a.update(transcriptId='ENST00000000001'),lambda a:a.update(geneSymbol='BAD\tROW'),
            lambda a:a.update(contig='2'),lambda a:a.update(strand='?'),lambda a:a.update(start=19001),
            lambda a:a.update(end=True),lambda a:a.update(exons=[[True,20000],[22000,23000]]),
            lambda a:a.update(exons=[[19000,22000],[22000,23000]]),lambda a:a.update(exons=[]),
            lambda a:a.update(exons=[[19000,18999],[22000,23000]]),lambda a:a.update(exons=[[19000,20000],[22000,2**32]])]
        for mutate in mutations:
            req=self.request();mutate(req['variants'][0]['selectedAnnotation'])
            with self.subTest(req=req),self.assertRaises(native.engine.raw.ProbeError):native.validate_selected_request(req)

    def test_invalid_variant_and_limits_matrix(self):
        for key,value in [('position',True),('position',18999),('reference','AA'),('alternate','A'),('assembly','GRCh37')]:
            req=self.request();req['variants'][0]['allele'][key]=value
            with self.subTest(key=key),self.assertRaises(native.engine.raw.ProbeError):native.validate_selected_request(req)
        for key,value in [('mask',False),('distanceBp',50),('variants',[]),('variants',self.request()['variants']*11)]:
            req=self.request();req[key]=value
            with self.subTest(key=key),self.assertRaises(native.engine.raw.ProbeError):native.validate_selected_request(req)

    def test_per_record_annotation_view_does_not_include_overlapping_other_transcript(self):
        class Vector(list):
            def __getitem__(self,index):
                return Vector([super(Vector,self).__getitem__(i) for i in index]) if isinstance(index,list) else super().__getitem__(index)
        ann=SimpleNamespace(genes=Vector(['TEST','TEST']),chroms=Vector(['1','1']),strands=Vector(['-','+']),
            tx_starts=Vector([19000,19000]),tx_ends=Vector([23000,40000]),
            exon_starts=[[19000,22000],[19000]],exon_ends=[[20000,23000],[40000]],models=object(),ref_fasta=object())
        req=self.request(); seen=[]
        def witness(variant,view,official):
            seen.append(view);self.assertEqual(view.genes,['TEST']);self.assertEqual(view.strands,['-'])
            self.assertEqual(view.exon_starts,[[19000,22000]]);self.assertIs(view.models,ann.models)
            return {'status':'HIT'}
        with patch.object(native,'witnessed_record',side_effect=witness):
            result=native.selected_evaluator(req,'a'*64)(req['variants'][0],ann,None)
        self.assertEqual(result['selectedAnnotation'],req['variants'][0]['selectedAnnotation'])
        self.assertEqual(ann.genes,['TEST','TEST']);self.assertIsNot(ann,seen[0])
        ann.tx_starts[0]=19001
        with self.assertRaisesRegex(native.engine.raw.ProbeError,'PARSE_MISMATCH'):
            native.selected_evaluator(req,'a'*64)(req['variants'][0],ann,None)

    def test_binding_survives_json_roundtrip_and_does_not_reuse_legacy_identity(self):
        with patch.object(native.engine, 'runtime_identity', return_value={'packages': [('test', '1')]}), \
                patch.object(native.engine.raw, 'sha256_file', return_value='a'*64):
            value = native.identity(Path('test'))
        self.assertEqual(value, json.loads(json.dumps(value)))
        self.assertFalse(value['legacyRuntimeIdentityReused'])

    def test_unavailable_never_supplies_annotation_or_window(self):
        with patch.object(native.engine, 'evaluate_record', return_value={'status': 'UNAVAILABLE'}):
            self.assertEqual(native.witnessed_record({}, None, None), {'status': 'UNAVAILABLE'})

    def test_coordinates_are_official_one_based_without_second_strand_flip(self):
        sequence = SimpleNamespace(__ignored=None)
        class Sequence:
            def __getitem__(self, region):
                self.region = region
                return SimpleNamespace(seq='a'*19999)
        sequence = Sequence()
        ann = SimpleNamespace(genes=['GENE'], chroms=['1'], strands=['-'], tx_starts=[10000], tx_ends=[50000],
                exon_starts=[[10000,40000]], exon_ends=[[11000,50000]], ref_fasta={'1':sequence},
                get_name_and_strand=lambda c,p: (['GENE'],['-'],[0]))
        with patch.object(native.engine, 'evaluate_record', return_value={'status':'HIT'}):
            result = native.witnessed_record({'allele':{'contig':'1','position':20000}}, ann,
                        SimpleNamespace(normalise_chrom=lambda c,t:c))
        self.assertEqual(result['annotationRows'][0]['exons'], [[10000,11000],[40000,50000]])
        self.assertEqual(result['annotationRows'][0]['strand'], '-')
        self.assertEqual(sequence.region, slice(10000,29999))
        self.assertEqual(result['referenceWindow']['start'],10001)


if __name__ == '__main__': unittest.main()
