"""Pinned source replay, current context/ledger and idempotent contributions."""
from copy import deepcopy
from pathlib import Path
import unittest

from evaluate_pp1_snapshot import calculate
from model_literature_contract import digest, parse
from test_process_pp1_paper import PaperTask
from test_pp1_reading import Resolver
from test_pp1_segregation import context


class Snapshot(unittest.TestCase):
    def setUp(self):
        self.p = PaperTask(); self.p.setUp(); self.addCleanup(self.p.doCleanups)
        f = self.p.fixture
        result = self.p.run_paper(allow_model=True)
        paths = parse((f.root/'paper/artifacts.json').read_bytes())['acceptedArtifacts']
        self.doc = {'taskId':'a'*64,
            'request':{'pmcid':'PMC123','bundleSha256':f.pin,'analysisBinding':deepcopy(self.p.binding)},
            'result':result, 'artifacts':[{'path':str(Path(p).relative_to(f.root)), 'sha256':digest(Path(p).read_bytes())} for p in paths]}

    def evaluate(self, docs=None, binding=None):
        f = self.p.fixture
        return calculate(f.root, f.library, f.root, docs or [self.doc], binding or self.p.binding, context(), Resolver())

    def test_saved_score_is_not_trusted_and_duplicate_tasks_not_added(self):
        self.doc['result']['evaluation'].update(status='MET',awardedPP1Points=99)
        other = deepcopy(self.doc); other['taskId']='b'*64
        result = self.evaluate([self.doc,other])
        self.assertEqual(1,result['evaluation']['awardedPP1Points'])
        self.assertEqual(1,len(result['evaluation']['events']))
        self.assertEqual((0,0),(result['modelCalls'],result['networkRequests']))

    def test_current_pp4_contribution_recomputes_cap(self):
        binding=deepcopy(self.p.binding);binding['ledger']['pp4Points']=5
        result=self.evaluate(binding=binding)
        self.assertEqual('1',result['evaluation']['rawSegregationPoints'])
        self.assertEqual(0,result['evaluation']['awardedPP1Points'])

    def test_source_coverage_rebuilt_not_trusted_from_saved_claim(self):
        self.doc['result']['materialCoverage']={'unparsedAttachments':999,'completePublicationProven':True}
        result=self.evaluate()
        coverage=result['sourceCoverage'][0]['materialCoverage']
        self.assertNotEqual(999,coverage['unparsedAttachments'])
        self.assertFalse(coverage['completePublicationProven'])
        self.assertFalse(coverage['wholePaperAbsenceClaim'])

    def test_other_disease_is_excluded_without_borrowing(self):
        self.doc['request']['analysisBinding']['selection']['diseaseId']='TEST:2'
        result=self.evaluate()
        self.assertEqual('NOT_EVALUATED',result['evaluation']['status'])
        self.assertEqual('PP1_TASK_DIFFERENT_SELECTED_CONTEXT',result['excludedTasks'][0]['reasonCode'])

    def test_changed_artifact_rejected(self):
        f=self.p.fixture;path=f.root/self.doc['artifacts'][0]['path'];path.write_bytes(path.read_bytes()+b' ')
        with self.assertRaisesRegex(ValueError,'PP1_CAPTURE_ARTIFACT_CHANGED'):self.evaluate()

    def test_artifact_outside_work_rejected(self):
        f=self.p.fixture;smaller=f.root/'separate-work';smaller.mkdir()
        with self.assertRaisesRegex(ValueError,'PP1_ARTIFACT_OUTSIDE_WORK'):
            calculate(f.root,f.library,smaller,[self.doc],self.p.binding,context(),Resolver())


if __name__=='__main__':unittest.main()
