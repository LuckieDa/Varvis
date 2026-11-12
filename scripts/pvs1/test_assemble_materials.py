import copy
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import assemble_materials as mod


class AssemblyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        original = {'g2p id':'G2P1', 'hgnc id':'123', 'disease MONDO':'MONDO:0000001',
                    'allelic requirement':'monoallelic_autosomal', 'molecular mechanism':'loss of function',
                    'confidence':'definitive', 'comments':'Full original restriction\nTEST_ONLY', 'molecular mechanism support':'inferred'}
        self.facts = {'schemaVersion':'pvs1-mechanism-source-candidates-v1', 'methodVersion':'1.0.0',
                      'status':'UNREVIEWED','runtimeEligible':False,'clinicalAcceptance':False,
                      'counts':{'sourceRecords':1},'source':{'version':'TEST_ONLY'},
                      'records':[{'recordId':'G2P1:1','sourceRow':1,'sourceRecord':original,
                                  'sourceRecordSha256':mod.sha(mod.canonical(original)), 'status':'UNREVIEWED','runtimeEligible':False}]}
        row = ['GeneID:123','ENSG000001','HGNC:123','TEST','test','NM_000001.1','NP_000001.1',
               'ENST000001.4','ENSP000001.1','MANE Select','NC_000001.11','100','300','+']
        self.mane = '\t'.join(mod.MANE_HEADER) + '\n' + '\t'.join(row) + '\n'
        self.config = {'schemaVersion':'pvs1-material-plan-input-v1','mechanisms':self.write('source.json', self.facts),
                       'mane':self.write('mane.gz', gzip.compress(self.mane.encode())), 'candidates':[]}
        self.config['mane']['version'] = 'TEST_ONLY'

    def write(self, name, data):
        raw = data if isinstance(data, bytes) else json.dumps(data).encode()
        file = self.root / name
        file.write_bytes(raw)
        return {'file':str(file), 'sha256':mod.sha(raw)}

    def update_facts(self):
        self.facts['counts']['sourceRecords'] = len(self.facts['records'])
        for record in self.facts['records']:
            record['sourceRecordSha256'] = mod.sha(mod.canonical(record['sourceRecord']))
        self.config['mechanisms'] = self.write('source.json', self.facts)

    def batch(self, plan):
        results = []
        for req in plan['structureRequests']:
            c = req['context']
            row = {'assembly':'GRCh38','contig':'1','hgncId':c['gene']['hgncId'],'geneSymbol':c['gene']['symbol'],
                   'transcript':'ENST000001.4', 'cdsLength':99, 'exons':[{'ordinal':1}]}
            results.append({'requestId':req['requestId'], 'context':c, 'status':'AVAILABLE',
                            'annotation':row,'annotationCanonicalSha256':mod.content_hash(row)})
        return {'schemaVersion':'pvs1-structure-batch-v1', 'methodVersion':'1.0.0', 'coreExecutions':0, 'planRawSha256':'a'*64,
                'runtimeEligible':False,'clinicalAcceptance':False,'results':results}

    def test_joins_original_sources_and_never_approves_mane_or_lof(self):
        plan = mod.plan(self.config)
        self.assertEqual(1, len(plan['structureRequests']))
        self.assertEqual(self.facts['records'], plan['sourceRecords'])
        self.assertEqual('4', str(plan['structureRequests'][0]['context']['transcript']['version']))
        result = mod.finish(plan, 'a'*64, self.batch(plan))
        self.assertEqual(1, result['counts']['structuralBackgrounds'])
        self.assertFalse(result['backgrounds'][0]['runtimeEligible'])
        self.assertEqual(0, result['counts']['adoptedBackgrounds'])
        self.assertEqual('NOT_ESTABLISHED_BY_MANE_ALONE', result['backgrounds'][0]['transcripts'][0]['clinicalRelevance'])

    def test_new_background_is_data_only_and_transcript_reads_are_deduplicated(self):
        record = copy.deepcopy(self.facts['records'][0])
        record.update(recordId='G2P1:2', sourceRow=2)
        record['sourceRecord']['disease MONDO'] = 'MONDO:0000002'
        self.facts['records'].append(record); self.update_facts()
        plan = mod.plan(self.config)
        self.assertEqual(2, len(plan['backgrounds']))
        self.assertEqual(1, len(plan['structureRequests']))
        self.assertEqual(2, len(plan['structureRequests'][0]['origins']))

    def test_mechanism_conflicts_and_non_autosomal_labels_survive(self):
        record = copy.deepcopy(self.facts['records'][0]); record.update(recordId='G2P1:2', sourceRow=2)
        record['sourceRecord']['molecular mechanism'] = 'gain of function'
        self.facts['records'].append(record); self.update_facts()
        result = mod.plan(self.config)
        self.assertIn('MECHANISM_SOURCE_CONFLICT', result['backgrounds'][0]['reasonCodes'])
        for r in self.facts['records']:
            r['sourceRecord']['allelic requirement'] = 'monoallelic_X_hemizygous'
        self.update_facts(); result = mod.plan(self.config)
        self.assertIsNone(result['backgrounds'][0]['moi'])
        self.assertIn('ALLELIC_REQUIREMENT_MAPPING_NOT_IMPLEMENTED', result['backgrounds'][0]['reasonCodes'])

    def test_missing_disease_is_retained_without_joining_other_missing_diseases(self):
        self.facts['records'][0]['sourceRecord']['disease MONDO'] = ''
        self.update_facts(); result = mod.plan(self.config)
        self.assertEqual([], result['backgrounds']); self.assertEqual(['G2P1:1'], result['unresolvedSourceRecordIds'])

    def test_broken_source_digest_or_original_row_is_rejected(self):
        self.config['mechanisms']['sha256'] = '0'*64
        with self.assertRaisesRegex(ValueError, 'INPUT_SHA_MISMATCH'): mod.plan(self.config)
        self.facts['records'][0]['sourceRecord']['comments'] = 'changed'
        self.config['mechanisms'] = self.write('source.json', self.facts)
        with self.assertRaisesRegex(ValueError, 'MECHANISM_RECORD_INVALID'): mod.plan(self.config)

    def test_mane_complete_source_validation_reused(self):
        self.config['mane'] = self.write('mane.gz', gzip.compress(self.mane.replace('MANE Select','something else').encode()))
        self.config['mane']['version'] = 'TEST_ONLY'
        with self.assertRaisesRegex(ValueError, 'MANE_STATUS_DRIFT'): mod.plan(self.config)

    def test_historical_policy_remains_unchanged_and_not_adopted(self):
        policy = {'schemaVersion':'pvs1-truncating-nmd-policy-v1','policyId':'TEST_ONLY',
                  'scope':{'hgncId':'HGNC:123','mondoId':'MONDO:0000001','moi':'AUTOSOMAL_DOMINANT'},
                  'review':{'status':'SOURCE_REVIEW_INCOMPLETE'},'scientificSources':[]}
        doc = {'schemaVersion':'pvs1-truncating-nmd-bundle-candidate-v1','policies':[policy],
               'catalog':{'note':'TEST_ONLY'},'catalogSha256':mod.content_hash({'note':'TEST_ONLY'}),
               'activation':{'status':'PENDING'},'runtimeResourceBinding':{'status':'PENDING'},
               'acceptance':{'realVepValidationCompleted':False}, 'sourceReviewBindings':[]}
        self.config['candidates'] = [{**self.write('old.json',doc), 'resourceRoot':str(self.root)}]
        result = mod.plan(self.config); actual = result['historicalMaterials'][0]
        self.assertEqual(policy, actual['policy']); self.assertFalse(actual['runtimeEligible'])
        self.assertEqual(doc, actual['originalCandidate'])
        self.assertIn('POLICY_MODEL_BYTES_NOT_VERIFIED', actual['reasonCodes'])
        self.assertEqual([actual['materialId']], result['backgrounds'][0]['historicalMaterialIds'])

    def test_incomplete_or_misbound_structure_batches_are_rejected(self):
        plan = mod.plan(self.config)
        for kind in ['missing', 'duplicate', 'pin', 'context', 'annotation', 'method', 'core']:
            with self.subTest(kind=kind):
                batch = self.batch(plan)
                if kind == 'missing': batch['results'] = []
                elif kind == 'duplicate': batch['results'] *= 2
                elif kind == 'pin': batch['planRawSha256'] = 'b'*64
                elif kind == 'method': batch['methodVersion'] = 'unsupported'
                elif kind == 'core': batch['coreExecutions'] = 1
                elif kind == 'context':
                    batch['results'][0]['context'] = copy.deepcopy(batch['results'][0]['context'])
                    batch['results'][0]['context']['gene']['hgncId'] = 'HGNC:999'
                else: batch['results'][0]['annotation']['transcript'] = 'ENST000001.5'
                with self.assertRaises(ValueError): mod.finish(plan,'a'*64,batch)

    def test_exact_version_missing_retained_as_gap_not_changed_to_neighbor(self):
        plan = mod.plan(self.config); batch = self.batch(plan)
        row = batch['results'][0]
        row.update(status='UNAVAILABLE', reasonCode='GENCODE_EXACT_TRANSCRIPT_VERSION_UNAVAILABLE')
        row.pop('annotation'); row.pop('annotationCanonicalSha256')
        result = mod.finish(plan, 'a'*64, batch)
        self.assertEqual(0, result['counts']['structuralBackgrounds'])
        self.assertIn('EXACT_VERSION_STRUCTURAL_ANNOTATION_UNAVAILABLE', result['backgrounds'][0]['reasonCodes'])

    def test_output_exclusive_and_resource_paths_confined(self):
        file = self.root/'out.json'; mod.write_new(file, {'a':1})
        with self.assertRaises(FileExistsError): mod.write_new(file, {'a':2})
        self.assertEqual({'a':1}, json.loads(file.read_text()))
        for relative in ['../bad','..\\bad','C:/bad','/bad']:
            with self.assertRaises(ValueError): mod.child(self.root,relative)

    def test_duplicate_json_is_not_silently_overwritten(self):
        with self.assertRaisesRegex(ValueError, 'DUPLICATE_JSON_FIELD'): mod.parse('{"a":1,"a":2}')

    def test_historical_review_missing_or_changed_bytes_remain_explicit(self):
        self.test_historical_policy_remains_unchanged_and_not_adopted()
        doc = mod.parse(Path(self.config['candidates'][0]['file']).read_bytes())
        review = self.write('review.json', {'scope':'TEST_ONLY_LIMITED_EXONS'})
        doc['sourceReviewBindings'] = [{'file':'review.json', 'sha256':review['sha256']}]
        self.config['candidates'][0].update(self.write('old.json',doc))
        material = mod.plan(self.config)['historicalMaterials'][0]
        self.assertEqual('RAW_SHA_MATCH', material['sourceReviewChecks'][0]['status'])
        Path(review['file']).write_text('changed')
        material = mod.plan(self.config)['historicalMaterials'][0]
        self.assertEqual('INVALID', material['sourceReviewChecks'][0]['status'])
        self.assertIn('POLICY_SOURCE_REVIEW_BYTES_NOT_ALL_VERIFIED', material['reasonCodes'])
        Path(review['file']).unlink()
        material = mod.plan(self.config)['historicalMaterials'][0]
        self.assertEqual('NOT_LOCATED', material['sourceReviewChecks'][0]['status'])


if __name__ == '__main__':
    unittest.main()
