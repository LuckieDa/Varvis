import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from prepare_bp1_function_screen import compile_screen


class ReadOnlyRows:
    title='Synthetic'
    max_row=4
    max_column=4
    def __init__(self,rows):self.rows=rows
    def iter_rows(self,values_only=False):return iter(self.rows)
    def __getitem__(self,key):return self
    def close(self):pass


class FunctionalScreenTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)
        def source(name,body):
            p=self.root/name;p.write_bytes(body);return {'file':str(p),'sha256':hashlib.sha256(body).hexdigest()}
        text='Synthetic research uses NM_000001.1 and NP_000001.1. TEST_ONLY.'
        cds='ATGGCTGCTGCTTAA'
        model={'hgncId':'HGNC:1','transcript':'NM_000001.1','protein':'NP_000001.1','cdsSequence':cds,
               'sequenceVerification':{'cdsSha256':hashlib.sha256(cds.encode()).hexdigest()}}
        self.config={'schemaVersion':'bp1-function-screen-plan-v1','clinicalAdoption':False,'sourceId':'TEST_ONLY','sourceUrl':'https://example.org/synthetic',
            'screenId':'TEST_ONLY','workbook':source('test.xlsx',b'PK\x03\x04SYNTHETIC_MOCK_ONLY'),
            'article':source('article.xml',f'<article><p>{text}</p></article>'.encode()),
            'model':source('model.json',json.dumps(model).encode()),'paragraphSha256':[hashlib.sha256(text.encode()).hexdigest()],
            'hgncId':'HGNC:1','transcript':'NM_000001.1','protein':'NP_000001.1','ranges':[{'start':2,'end':4}],
            'sheet':'Synthetic','headerRow':1,'columns':{'variantType':'Type','proteinVariant':'Protein','classification':'Effect'},
            'missenseLabel':'Missense','classMap':{'Measured functional':'FUNCTIONAL','Measured damaging':'DAMAGING','Uncertain':'INTERMEDIATE'},
            'expectedCounts':{'FUNCTIONAL':1,'DAMAGING':1,'INTERMEDIATE':1}}
        self.rows=[['Type','Protein','Effect'],['Missense','A2V','Measured functional'],['Missense','A3V','Measured damaging'],['Missense','A4V','Uncertain']]
    def run_compile(self):
        with patch('prepare_bp1_function_screen.load_workbook',return_value=ReadOnlyRows(self.rows)), patch('prepare_bp1_function_screen.zipfile.ZipFile') as archive:
            archive.return_value.__enter__.return_value.infolist.return_value=[]
            return compile_screen(self.config)
    def test_full_rows_source_and_uncertainty_preserved_without_new_classification(self):
        out=self.run_compile();self.assertFalse(out['clinicalAdoption']);self.assertFalse(out['absenceMeansNormal'])
        records=out['screens'][0]['records'];self.assertEqual(3,len(records))
        self.assertEqual('INTERMEDIATE',records[2]['reportedClass']);self.assertEqual(self.rows[3],records[2]['originalRow']['cells'])
        self.assertEqual(4,records[2]['sourceRow']);self.assertEqual(self.config['workbook']['sha256'],out['screens'][0]['source']['rawSha256'])
    def test_source_pin_identity_counts_and_coverage_fail_closed(self):
        for field,value,reason in [('protein','NP_000001.2','SEQUENCE_IDENTITY'),('expectedCounts',{'FUNCTIONAL':3},'COUNTS_MISMATCH'),
                ('ranges',[{'start':2,'end':3}],'COVERAGE_MISMATCH')]:
            with self.subTest(field=field):
                old=copy.deepcopy(self.config);self.config[field]=value
                with self.assertRaisesRegex(ValueError,reason):self.run_compile()
                self.config=old
        self.config['workbook']['sha256']='0'*64
        with self.assertRaisesRegex(ValueError,'PIN_MISMATCH'):self.run_compile()
    def test_wrong_residue_duplicate_unknown_and_formula_are_not_valid_rows(self):
        for row,reason in [(['Missense','V2A','Measured functional'],'RESIDUE'),(['Missense','A3V','Measured functional'],'AMBIGUOUS'),
                          (['Missense','A2V','Unknown'],'CLASS_UNSUPPORTED'),(['Missense','A2V','Measured functional','=1+1'],'FORMULA')]:
            with self.subTest(reason=reason):
                self.rows[1]=row
                with self.assertRaisesRegex(ValueError,reason):self.run_compile()
    def test_ambiguous_header_cannot_silently_select_a_column(self):
        self.rows[0]=['Type','Protein','Protein','Effect']
        with self.assertRaisesRegex(ValueError,'COLUMN_AMBIGUOUS'):self.run_compile()

    def test_measurements_keep_numeric_type_and_decimal_text_without_identity_floats(self):
        self.rows[0].append('Research measurement')
        self.rows[1].append(-0.00690550316501976)
        record=self.run_compile()['screens'][0]['records'][0]
        cell=record['originalRow']['cells'][-1]
        self.assertEqual('number',cell['decodedCellType'])
        self.assertEqual(self.rows[1][-1],float(cell['decimalText']))
        self.assertEqual('FUNCTIONAL',record['reportedClass'])
        self.rows[1][-1]=float('nan')
        with self.assertRaisesRegex(ValueError,'NONFINITE'):self.run_compile()


if __name__=='__main__':unittest.main()
