"""10 kb official-call and score-witness tests, no TensorFlow/model inference."""
import copy,json,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import numpy as np
import run_spliceai_native_wide_business as wide
from test_spliceai_native_business import BusinessTest

class WideTest(unittest.TestCase):
    def request(self):
        r=BusinessTest().request();r['distanceBp']=10000;return r
    def test_new_identity_survives_json_and_has_explicit_execution_distance(self):
        with patch.object(wide.engine,'runtime_identity',return_value={'packages':[('test','1')]}):
            value=wide.identity(Path('test'))
        self.assertEqual(value,json.loads(json.dumps(value)))
        self.assertEqual(value['executionDistanceBp'],10000)
        self.assertNotEqual(value['adapterSha256'],value['selectedAdapterSha256'])
    def test_legacy_request_cannot_be_relabelled_as_wide(self):
        for value in [4999,True,'10000',10001]:
            r=self.request();r['distanceBp']=value
            with self.subTest(value=value),self.assertRaises(wide.engine.raw.ProbeError):wide.validate(r)
        r=self.request();old=copy.deepcopy(r);wide.validate(r);self.assertEqual(r,old)
    def test_official_once_per_allele_and_exact_ref_alt_scores_on_both_strands(self):
        for strand in ['+','-']:
            with self.subTest(strand=strand):
                r=self.request();v=r['variants'][0];v['selectedAnnotation']['strand']=strand
                class Vector(list):
                    def __getitem__(self,i):return Vector([super(Vector,self).__getitem__(n) for n in i]) if isinstance(i,list) else super().__getitem__(i)
                class Sequence:
                    def __getitem__(self,region):return SimpleNamespace(seq='A'*(region.stop-region.start))
                class Model:
                    def __init__(self):self.calls=0
                    def predict(self,which):
                        self.calls+=1;out=np.zeros((1,20001,3));i=10001 if strand=='+' else 9999
                        out[0,i,1]=.4 if which=='REF' else .5
                        out[0,i,2]=.3 if which=='REF' else .2
                        return out
                a=v['selectedAnnotation'];models=[Model() for _ in range(5)]
                ann=SimpleNamespace(genes=Vector([a['geneSymbol']]),chroms=Vector([a['contig']]),strands=Vector([strand]),
                    tx_starts=Vector([a['start']]),tx_ends=Vector([a['end']]),exon_starts=[[x[0] for x in a['exons']]],
                    exon_ends=[[x[1] for x in a['exons']]],models=models,ref_fasta={a['contig']:Sequence()})
                seen=[]
                def official_call(record,view,distance,mask):
                    seen.append((distance,mask));self.assertEqual(view.genes,[a['geneSymbol']])
                    for model in view.models:model.predict('REF')
                    for model in view.models:model.predict('ALT')
                    return [f'{v["allele"]["alternate"]}|{a["geneSymbol"]}|0.10|0.00|0.00|0.10|1|-10000|-10000|1']
                result=wide.evaluate(v,ann,SimpleNamespace(normalise_chrom=lambda c,_:c,get_delta_scores=official_call),0,'a'*64)
                self.assertEqual(seen,[(10000,0)]);self.assertEqual([m.calls for m in models],[2]*5)
                self.assertEqual(result['referenceWindow']['length'],30001)
                self.assertAlmostEqual(float(result['officialRefAltScores']['AG']['referenceScore']),.4)
                self.assertAlmostEqual(float(result['officialRefAltScores']['AG']['alternateScore']),.5)
                self.assertAlmostEqual(float(result['officialRefAltScores']['DL']['referenceScore']),.3)
                self.assertEqual(result['officialRefAltScores']['DL']['position'],1)

if __name__=='__main__':unittest.main()
