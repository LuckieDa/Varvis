"""Task-1 feedback: actual existing synthetic source/core pipeline, zero models."""
from copy import deepcopy
from pathlib import Path
import tempfile
import unittest
import shutil
from unittest.mock import patch
import core_evaluation_intake
import test_core_evaluation_intake as fixtures
from test_bundle_qualification import article
from model_literature_contract import parse, canonical, digest
from denovo_reading_feedback import from_package, _feedback, route, group


class ReadingFeedbackTests(unittest.TestCase):
    def setUp(self):
        root=Path(__file__).resolve().parents[2]/'local/temp';root.mkdir(exist_ok=True,parents=True)
        self.temp=tempfile.TemporaryDirectory(prefix='denovo-feedback-',dir=root)
        self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name)

    run_case=fixtures.CoreEvaluationTests.run_case

    def run_feedback(self,raw=None,config_change=None):
        original=core_evaluation_intake.project_package
        self.package=self.root/'retained-context'
        def capture(directory,index):
            shutil.copytree(directory,self.package)
            return original(directory,index)
        with patch.object(core_evaluation_intake,'project_package',side_effect=capture):
            core,*_=self.run_case(raw,config_change)
        self.pin=digest((self.package/'index.json').read_bytes())
        result=from_package(self.package,self.pin)
        self.assertEqual(core['caseKey'],result['cases'][0]['bindings']['caseKey'])
        return result['cases'][0],core

    def test_complete_case_has_no_needs_and_does_not_award(self):
        feedback,core=self.run_feedback()
        self.assertTrue(feedback['requirementsSatisfied']);self.assertEqual([],feedback['needs'])
        self.assertEqual('NOT_RUN',feedback['criterionDecision']);self.assertFalse(feedback['autoContinue'])
        self.assertFalse(feedback['scientificExecution']);self.assertNotIn('score',feedback)
        self.assertEqual(core['evaluationKey'],feedback['bindings']['evaluationKey'])

    def test_multiple_missing_facts_not_just_first_failure(self):
        raw=article().replace(b'<tr><td>Family history</td><td>None</td></tr>',b'')
        raw=raw.replace(b'SNP analysis confirmed both maternity and paternity.',b'Unresolved family information.')
        feedback,core=self.run_feedback(raw)
        expected={(b['path'],b['code']) for b in core['blockers']}
        self.assertGreater(len(expected),1)
        self.assertEqual(expected,{(n['fieldPath'],n['reasonCode']) for n in feedback['needs']})
        self.assertTrue(all(n['caseKey']==core['caseKey'] for n in feedback['needs']))

    def test_parental_negative_is_related_not_family_compatibility(self):
        raw=article().replace(b'<tr><td>Family history</td><td>None</td></tr>',b'')
        self.assertNotEqual(raw,article())
        feedback,_=self.run_feedback(raw)
        need=next(n for n in feedback['needs'] if n['fieldPath']=='/groups/family/compatibility')
        related={x['path']:x for x in need['relatedVerifiedFacts']}
        self.assertEqual('ABSENT',related['/groups/mother/targetResult']['value'])
        self.assertTrue(related['/groups/mother/targetResult']['sourceEvidence'])
        self.assertTrue(need['checkedScope'])
        self.assertIsNone(need['observedField']['value'])
        self.assertIn(need['route'],('SOURCE_REVIEW','LITERATURE_READING'))

    def test_assumed_parentage_is_not_mandatory_confirmation(self):
        raw=article().replace(b'SNP analysis confirmed both maternity and paternity.',b'No parentage testing was performed.')
        feedback,core=self.run_feedback(raw)
        self.assertEqual('ASSUMED_NOT_TESTED',core['values']['/groups/parentage/maternity'])
        self.assertEqual([],feedback['needs']);self.assertTrue(feedback['requirementsSatisfied'])

    def test_external_context_gap_not_paper_omission(self):
        feedback,_=self.run_feedback(config_change=lambda c:c.update(contexts=[]))
        needs=[n for n in feedback['needs'] if n['fieldPath'].startswith('/context/') and n['group']=='CONTEXT']
        self.assertTrue(needs);self.assertTrue(all(n['route']=='EXTERNAL_RESOURCE' for n in needs))

    def test_pin_failure_and_source_tamper_are_rejected(self):
        self.run_feedback()
        with self.assertRaisesRegex(ValueError,'INDEX_PIN'):from_package(self.package,'0'*64)
        index=parse((self.package/'index.json').read_bytes());p=self.package/index['cases'][0]['reportPath']
        p.write_bytes(p.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'INPUT_PIN'):from_package(self.package,self.pin)

    def test_feedback_does_not_mutate_inputs_or_leak_local_paths(self):
        feedback,core=self.run_feedback()
        old=canonical(core)
        report={'caseKey':core['caseKey'],'issues':[]}
        self.assertEqual(feedback,_feedback(core,report))
        self.assertEqual(old,canonical(core));self.assertNotIn(str(self.root),str(feedback))
        with self.assertRaisesRegex(ValueError,'CASE_MISMATCH'):_feedback(core,{'caseKey':'other','issues':[]})

    def test_all_cause_categories_preserve_conflict_over_missing(self):
        _,core=self.run_feedback();changed=deepcopy(core)
        path='/groups/family/compatibility'
        changed['blockers']=[{'path':path,'code':'FACT_NOT_VERIFIED','category':'REVIEW_REQUIRED'}]
        report={'caseKey':core['caseKey'],'issues':[{'paths':[path],'category':'DATA_MISSING','code':'FIRST'},
                                                 {'paths':[path],'category':'DATA_CONFLICT','code':'SECOND'}]}
        n=_feedback(changed,report)['needs'][0]
        self.assertEqual('CONFLICT_REVIEW',n['route']);self.assertIn('DATA_CONFLICT',n['causeCategories'])

    def test_route_matrix_and_subject_field_distinctions(self):
        for category,expected in [('CONFLICT','CONFLICT_REVIEW'),('SOFTWARE_UNSUPPORTED','SOFTWARE_SUPPORT_REQUIRED'),
                                  ('RESOURCE_UNAVAILABLE','EXTERNAL_RESOURCE'),('EXECUTION_FAILURE','EXECUTION_RECOVERY'),
                                  ('NOT_APPLICABLE','NOT_APPLICABLE'),('SOURCE_UNAVAILABLE','SOURCE_ACQUISITION')]:
            self.assertEqual(expected,route({'category':category,'path':'/groups/mother/targetResult','code':'CHECK'},{}))
        self.assertEqual('LITERATURE_READING',route({'category':'DATA_MISSING','path':'/groups/father/targetResult','code':'NOT_EXTRACTED'},{}))
        self.assertEqual('UNMAPPED_REQUIREMENT',route({'category':'DATA_MISSING','path':'/new/field','code':'NEW'},{}))
        self.assertEqual('PARENTAGE',group('/groups/parentage/maternity'))
        self.assertEqual('TESTING',group('/groups/mother/targetResult'))
        self.assertEqual('FAMILY',group('/groups/family/compatibility'))

    def test_not_extracted_and_scoped_absence_are_distinct(self):
        _,core=self.run_feedback()
        for code,state in [('NOT_EXTRACTED','NOT_EXTRACTED'),('NOT_REPORTED_IN_CHECKED_SCOPE','NOT_REPORTED_IN_CHECKED_SCOPE')]:
            changed=deepcopy(core);changed['blockers']=[{'path':'/groups/father/targetResult','code':code,'category':'DATA_MISSING'}]
            n=_feedback(changed,{'caseKey':core['caseKey'],'issues':[]})['needs'][0]
            self.assertEqual(state,n['missingState']);self.assertNotEqual('WHOLE_PAPER_ABSENT',n['absenceMeaning'])


if __name__=='__main__':unittest.main()
