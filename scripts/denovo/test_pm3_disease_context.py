import tempfile
from pathlib import Path
import unittest
from model_literature_contract import canonical,digest
from pm3_disease_context import DiseaseContext

class Context(unittest.TestCase):
    def test_exact_affected_recessive_case_and_source_failures(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'local').mkdir()
            rows=[{'hgncId':'HGNC:1','symbol':'TEST','mondoId':'MONDO:0000001','diseaseLabel':'Test disorder',
                   'moi':'AR','classification':'Definitive','sourceRow':7}]
            raw=canonical(rows);(root/'local/catalog.json').write_bytes(raw)
            manifest={'profileId':'PATIENT_DISEASE_BINDING_INTAKE','profileVersion':'1.0.0','resources':{'clingen':{
                'resourceId':'CLINGEN_GENE_DISEASE_VALIDITY','sourceUrl':'https://search.clinicalgenome.org/kb/gene-validity/download',
                'derivedSha256':digest(raw),'derivedRecordCount':1}}}
            m=canonical(manifest);(root/'local/manifest.json').write_bytes(m)
            spec={'manifest':{'path':'local/manifest.json','sha256':digest(m)},'catalog':{'path':'local/catalog.json','sha256':digest(raw)}}
            supply=DiseaseContext(root,spec);identity={'hgncId':'HGNC:1','geneSymbol':'TEST'}
            case={'reportedDiagnoses':['Test disorder'],'reportedAffectedStatus':'AFFECTED'}
            self.assertEqual('VERIFIED',supply.qualify(case,identity)['qualification'])
            self.assertEqual('VERIFIED',supply.qualify({**case,'reportedDiagnoses':['MONDO:0000001']},identity)['qualification'])
            for changed in ({'reportedDiagnoses':['suspected Test disorder']},{'reportedDiagnoses':[]},{'reportedAffectedStatus':'UNKNOWN'}):
                self.assertEqual('UNKNOWN',supply.qualify({**case,**changed},identity)['qualification'])
            self.assertEqual('FAILED',supply.qualify({**case,'reportedAffectedStatus':'UNAFFECTED'},identity)['qualification'])
            self.assertEqual('UNKNOWN',supply.qualify(case,{**identity,'geneSymbol':'OTHER'})['qualification'])
            supply.rows[0]['moi']='AD';self.assertEqual('UNKNOWN',supply.qualify(case,identity)['qualification'])
            (root/'local/catalog.json').write_bytes(raw+b' ')
            with self.assertRaisesRegex(ValueError,'PIN_MISMATCH'):DiseaseContext(root,spec)

if __name__=='__main__':unittest.main()
