import base64
import hashlib
import json
from pathlib import Path
import unittest
from run_literature_batch import process, FILES
from test_literature_source_assertions import ARTICLE, RECORD, PMCID, VCV

sha = lambda raw: hashlib.sha256(raw).hexdigest()


def request():
    return {'schemaVersion': 'denovo-literature-reader-request-v1', 'materialPin': 'synthetic',
            'implementation': {name: sha((Path(__file__).parent / name).read_bytes()) for name in FILES},
            'originals': {sha(raw): base64.b64encode(raw).decode() for raw in (ARTICLE, RECORD)},
            'documents': [{'sourceId': 'paper', 'pmcid': PMCID, 'sha256': sha(ARTICLE),
                           'alleles': [{'vcv': VCV, 'sha256': sha(RECORD)}]}]}


class LiteratureBatchTest(unittest.TestCase):
    def test_large_paper_is_explicitly_withheld_without_erasing_other_valid_paper(self):
        from unittest.mock import patch
        r=request();r['documents'].append({**r['documents'][0],'sourceId':'small-paper'})
        with patch('run_literature_batch.qualify',side_effect=[{'cases':[{'audit':'x'*20000}]},{'cases':[{'caseLabel':'kept'}]}]), patch('run_literature_batch.MAX_OUTPUT',10000):
            raw=process(json.dumps(r).encode());result=json.loads(raw)
        self.assertLessEqual(len(raw),10000)
        first,second=result['documents']
        self.assertEqual('DENOVO_LITERATURE_DOCUMENT_RESULT_SIZE_LIMIT',first['reasonCode'])
        self.assertNotIn('facts',first);self.assertEqual(sha(ARTICLE),first['sourceSha256'])
        self.assertEqual('SOURCE_READ',second['status']);self.assertEqual('kept',second['facts']['cases'][0]['caseLabel'])
        self.assertEqual(0,result['modelCalls']);self.assertEqual(0,result['networkCalls'])

    def test_originals_are_reprocessed_and_output_bound_without_model_or_network(self):
        raw = json.dumps(request()).encode(); result = json.loads(process(raw))
        self.assertEqual(sha(raw), result['requestSha256'])
        self.assertEqual('SOURCE_READ', result['documents'][0]['status'])
        self.assertEqual('SUPPORTED_EXPLICIT_FAMILY_STATEMENTS', result['documents'][0]['facts']['cases'][0]['contextVerification'])
        self.assertEqual(0, result['networkCalls']); self.assertEqual(0, result['modelCalls'])

    def test_source_or_implementation_change_cannot_reuse_old_qualification(self):
        r = request(); r['implementation'][FILES[0]] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'IMPLEMENTATION_CHANGED'): process(json.dumps(r).encode())
        r = request(); r['originals'][sha(ARTICLE)] = base64.b64encode(b'changed').decode()
        with self.assertRaisesRegex(ValueError, 'ORIGINAL_PIN_MISMATCH'): process(json.dumps(r).encode())

    def test_one_bad_document_does_not_relabel_it_as_no_cases_or_erase_another(self):
        r = request(); r['documents'].append({**r['documents'][0], 'sourceId': 'wrong-paper', 'pmcid': 'PMC123'})
        result = json.loads(process(json.dumps(r).encode()))
        self.assertEqual(['SOURCE_READ', 'UNAVAILABLE'], [d['status'] for d in result['documents']])
        self.assertEqual('DENOVO_LITERATURE_SOURCE_INVALID', result['documents'][1]['reasonCode'])


if __name__ == '__main__':
    unittest.main()
