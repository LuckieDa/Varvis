import unittest
from copy import deepcopy
import test_bundle_model
from pm3_literature_contract import VERSION, review
from pm3_observations import ownership, project


class Observations(unittest.TestCase):
    def test_pdf_coordinate_projection_is_lossless_text_without_mutating_original_citation(self):
        from pm3_observations import coordinate_projection
        value={'citation':{'bbox':[56.639977344,636.0647423741281,542.24260761327,644.104739158128],
                           'quote':'unchanged','page':2},'count':1}
        original=deepcopy(value);result=coordinate_projection(value)
        self.assertEqual(original,value)
        self.assertEqual(['56.639977344','636.0647423741281','542.24260761327','644.104739158128'],result['citation']['bbox'])
        self.assertEqual('DECIMAL_STRINGS_IN_PDF_POINTS',result['citation']['bboxEncoding'])
        self.assertEqual(2,result['citation']['page']);self.assertEqual('unchanged',result['citation']['quote'])
        with self.assertRaisesRegex(ValueError,'PM3_PDF_COORDINATES_INVALID'):
            coordinate_projection({'bbox':[0,0,float('nan'),1]})

    def test_long_paper_keeps_extraction_and_review_artifacts_past_128_chunks(self):
        from unittest.mock import patch
        from pm3_observations import collect
        artifacts=[{'binding':{'pmcid':'PMC123','bundleSha256':'a'*64,
                    'target':{'geneSymbol':'EXAMPLE','variantAliases':['c.2C>G']},'chunkIndex':i//2,
                    'chunkCount':150,'stage':'EXTRACTION' if i%2==0 else 'SOURCE_REVIEW'},'review':{}} for i in range(300)]
        with patch('run_pm3_literature_model.replay',side_effect=artifacts), \
             patch('pm3_observations.load',return_value={}), \
             patch('pm3_observations.project',side_effect=lambda *args:{'cases':[]}):
            result=collect('library',list(range(300)))
        self.assertEqual(list(range(150)),result['processedChunks'])
        self.assertTrue(result['allChunksProcessed'])

    def test_only_review_replacement_enters_qualification_and_pending_is_preserved(self):
        from unittest.mock import patch
        from pm3_observations import collect
        binding={'pmcid':'PMC123','bundleSha256':self.f.pin,
                 'target':{'geneSymbol':'GENE1','variantAliases':['c.1A>G']},'chunkIndex':0,'chunkCount':1}
        original=self.checked();replacement=deepcopy(original);replacement['cases']=[]
        a={'binding':{**binding,'stage':'EXTRACTION'},'review':original}
        b={'binding':{**binding,'stage':'SOURCE_REVIEW'},'review':replacement,'originalExtraction':a}
        with patch('run_pm3_literature_model.replay',return_value=a),patch('pm3_observations.load',return_value=self.f.source):
            pending=collect('library',['a'])
        self.assertEqual([],pending['cases']);self.assertFalse(pending['allChunksProcessed'])
        self.assertTrue(pending['unreviewedExtraction']['cases'])
        with patch('run_pm3_literature_model.replay',side_effect=[a,b]),patch('pm3_observations.load',return_value=self.f.source):
            final=collect('library',['a','b'])
        self.assertEqual([],final['cases']);self.assertTrue(final['allChunksProcessed'])
        self.assertEqual([],final['unreviewedExtraction']['cases'])

    def setUp(self):
        self.f = test_bundle_model.BundleModelTests(); self.f.setUp()
        self.addCleanup(self.f.doCleanups)

    def checked(self):
        c = self.f.candidate(); c['schemaVersion'] = VERSION
        c['cases'][0]['fields'][0]['field'] = 'targetVariant'
        return review(self.f.source, c)

    def test_explicit_row_owns_its_cells_not_other_case(self):
        checked = self.checked()
        fact = checked['cases'][0]['fields'][0]
        scope = ownership(self.f.source, checked['cases'][0]['caseLabel'])
        self.assertIn(fact['citation']['unitId'], scope)
        wrong = [u for u in self.f.source['units'] if u.get('cell') == 'R3C2']
        self.assertTrue(wrong)
        self.assertTrue(all(u['unitId'] not in scope for u in wrong))

    def test_missing_is_not_unreported_and_ownership_is_not_scoring(self):
        result = project(self.f.source, [self.checked()])
        case = result['cases'][0]
        self.assertEqual('NOT_EXTRACTED', case['fields']['reportedPhase']['status'])
        self.assertEqual('CASE_OWNED_SEMANTICS_PENDING', case['facts'][0]['status'])
        self.assertEqual('NOT_ESTABLISHED', case['independenceStatus'])
        self.assertFalse(result['businessPublishable'])

    def test_repeated_chunks_do_not_duplicate_and_review_does_not_erase(self):
        first = self.checked(); second = deepcopy(first)
        second['cases'][0]['fields'][0]['sourceValue'] = 'GENE'
        result = project(self.f.source, [first, first, second])
        case = result['cases'][0]
        self.assertEqual(2, len(case['facts']))
        self.assertEqual('MULTIPLE_CLAIMS_REQUIRE_RECONCILIATION', case['fields']['targetVariant']['status'])

    def test_empty_case_result_keeps_source_checked_assessment_without_asserting_absence(self):
        checked=self.checked();checked['cases']=[]
        result=project(self.f.source,[checked,checked])
        self.assertEqual([],result['cases'])
        self.assertEqual(checked['sourceAssessments'],result['materialAssessments'])
        self.assertFalse(result['businessPublishable'])

    def test_hidden_or_merged_rows_not_qualified(self):
        source = deepcopy(self.f.source)
        checked = self.checked(); fact = checked['cases'][0]['fields'][0]
        unit = next(u for u in source['units'] if u['unitId'] == fact['citation']['unitId'])
        unit['sheetState'] = 'hidden'
        self.assertNotIn(unit['unitId'], ownership(source, checked['cases'][0]['caseLabel']))
        unit['sheetState'] = 'visible'; unit['gridSpan'] = 2
        self.assertNotIn(unit['unitId'], ownership(source, checked['cases'][0]['caseLabel']))

    def test_different_source_rejected(self):
        checked = self.checked(); checked['bundleSha256'] = '0'*64
        with self.assertRaisesRegex(ValueError, 'SOURCE_MISMATCH'): project(self.f.source, [checked])

    def test_origin_proof_survives_projection_but_does_not_assert_independence(self):
        checked=self.checked();source=deepcopy(self.f.source)
        unit=next(u for u in source['main']['units'] if u['kind']=='BODY_PARAGRAPH')
        unit['sections']=['Methods'];unit['text']='Patients were recruited at our centre.'
        row=checked['cases'][0];row['caseOriginProposal']='AUTHOR_COHORT'
        row['originCitations']=[{'unitId':unit['unitId'],'quote':unit['text']}]
        case=project(source,[checked])['cases'][0]
        self.assertEqual('VERIFIED',case['primarySourceQualification']['qualification'])
        self.assertEqual(row['originCitations'],case['originAssertions'][0]['citations'])
        self.assertEqual('NOT_ESTABLISHED',case['independenceStatus'])
        self.assertFalse(case['primarySourceQualification']['independenceEstablished'])
        revised=deepcopy(checked);revised['cases'][0]['caseOriginProposal']='CITED_ONLY'
        conflict=project(source,[checked,revised])['cases'][0]
        self.assertEqual('CONFLICT',conflict['primarySourceQualification']['qualification'])
        row['originCitations'][0]['quote']='Patients were not recruited at our centre.'
        unit['text']=row['originCitations'][0]['quote']
        self.assertEqual('UNKNOWN',project(source,[checked])['cases'][0]['primarySourceQualification']['qualification'])


if __name__ == '__main__': unittest.main()
