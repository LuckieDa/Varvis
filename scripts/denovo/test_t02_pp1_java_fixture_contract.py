"""Real worker/cache contract for Java's dynamic synthetic T02 source fixture."""
from pathlib import Path
import os
import shutil
import sys
import tempfile
import unittest

from model_literature_contract import canonical, digest, parse
from run_pp1_paper_task import run as worker
from test_t02_pp1_java_fixture import prepare
import test_pp1_segregation as segregation_fixture


class JavaFixtureContract(unittest.TestCase):
    def test_dynamic_source_two_phases_and_normal_worker_bound_resume_preserve_unknown(self):
        self.exercise('local/budget')

    def test_existing_workflow_short_budget_is_reused_without_configuration_change(self):
        self.exercise('local/b')

    def test_fixture_rejects_budget_escape_and_phase_change_before_more_transport(self):
        temporary_root = Path(__file__).resolve().parents[2] / 'local/temp/t02-pp1-python'
        temporary_root.mkdir(parents=True, exist_ok=True)
        if os.name == 'nt': temporary_root = Path('\\\\?\\' + str(temporary_root.resolve()))
        with tempfile.TemporaryDirectory(dir=temporary_root) as temporary:
            root = Path(temporary); local = root / 'local'; local.mkdir()
            target_path = root / 'target.json'; target_path.write_bytes(canonical({'geneSymbol': 'GENE1', 'variantAliases': ['c.1A>G']}))
            binding_path = root / 'binding.json'; binding_path.write_bytes(canonical({
                'schemaVersion': 'pp1-analysis-binding-v1', 'selection': segregation_fixture.selection(),
                'ledger': segregation_fixture.ledger()}))
            config_path = local / 'workflow.json'; config_path.write_bytes(canonical({'modelBudget': '../outside'}))
            with self.assertRaisesRegex(ValueError, 'T02_FIXTURE_BUDGET_PATH_INVALID'):
                prepare(root, target_path, binding_path, 'EXTRACT_ONLY', sys.executable)
            self.assertFalse((local / 't02-fixture.json').exists())
            self.assertFalse((root / 'outside').exists())
            config_path.write_bytes(canonical({'modelBudget': 'local/b'}))
            first = prepare(root, target_path, binding_path, 'EXTRACT_ONLY', sys.executable)
            before = (local / 't02-fixture.json').read_bytes()
            config_path.write_bytes(canonical({'modelBudget': 'local/changed-budget'}))
            with self.assertRaisesRegex(ValueError, 'T02_FIXTURE_IDENTITY_CHANGED'):
                prepare(root, target_path, binding_path, 'COMPLETE', sys.executable)
            self.assertEqual(1, first['syntheticTransportCalls'])
            self.assertEqual(before, (local / 't02-fixture.json').read_bytes())
            self.assertFalse((local / 'changed-budget').exists())

    def exercise(self, budget_name):
        temporary_root = Path(__file__).resolve().parents[2] / 'local/temp/t02-pp1-python'
        temporary_root.mkdir(parents=True, exist_ok=True)
        if os.name == 'nt': temporary_root = Path('\\\\?\\' + str(temporary_root.resolve()))
        with tempfile.TemporaryDirectory(dir=temporary_root) as temporary:
            root = Path(temporary); local = root / 'local'; local.mkdir()
            target = {'geneSymbol': 'GENE1', 'variantAliases': ['NM_000001.1:c.1A>G', 'c.1A>G']}
            binding = {'schemaVersion': 'pp1-analysis-binding-v1', 'selection': segregation_fixture.selection(),
                       'ledger': segregation_fixture.ledger()}
            binding['ledger'].update(status='UNKNOWN', pp4Points=None)
            target_path = root / 'target.json'; target_path.write_bytes(canonical(target))
            binding_path = root / 'binding.json'; binding_path.write_bytes(canonical(binding))
            config = {'schemaVersion': 'denovo-public-literature-workflow-v1', 'enabled': True,
                'projectRoot': str(root), 'work': 'local/work', 'library': 'local/library',
                'modelConfig': 'local/model.json', 'modelBudget': budget_name,
                'allowModel': False, 'allowPublicNetwork': False}
            config_path = local / 'workflow.json'; config_path.write_bytes(canonical(config))
            configuration_before = config_path.read_bytes()
            first = prepare(root, target_path, binding_path, 'EXTRACT_ONLY', sys.executable)
            self.assertEqual((1, 0, 0), (first['syntheticTransportCalls'], first['liveModelCalls'], first['publicNetworkCalls']))
            self.assertFalse(first['lastExtractionCacheHit'])
            model_before = (local / 'model.json').read_bytes()
            self.assertEqual(budget_name, first['modelBudget'])
            request = {'schemaVersion': 'pp1-public-paper-task-v1', 'selectionSha256': 'a' * 64,
                'pmcid': first['pmcid'], 'bundleSha256': first['bundleSha256'], 'target': target,
                'analysisBinding': binding}
            request_path = local / 'request.json'; request_path.write_bytes(canonical(request))
            parent = local / 'work/pp1-artifacts' / ('1' * 64) / 'paper'
            partial = worker(request_path, digest(request_path.read_bytes()), config_path, parent)
            self.assertEqual(('PP1_MODEL_DISABLED_CACHE_MISSING', 0), (partial['stopReason'], partial['modelAttempts']))
            self.assertEqual('INITIAL_SOURCE_AUDIT', partial['readingState']['stage'])
            self.assertEqual([], partial['readingState']['missing'], 'Biological requirements have not been evaluated yet')
            stage_gap = partial['readingState']['stageGaps'][0]
            self.assertEqual(('SYSTEM_STAGE', 'SOURCE_REVIEW', 1, 0, 1),
                (stage_gap['kind'], stage_gap['stage'], stage_gap['required']['minimumReviewArtifacts'],
                 stage_gap['available']['replayedReviewArtifacts'], stage_gap['difference']['missingMinimumReviewArtifacts']))
            self.assertEqual({'pmcid': first['pmcid'], 'bundleSha256': first['bundleSha256']}, stage_gap['scope'])
            self.assertEqual('PP1_MODEL_DISABLED_CACHE_MISSING', stage_gap['reasonCode'])
            self.assertTrue(stage_gap['recovery']['resumeAvailable'])
            parent_bytes = (parent / 'result.json').read_bytes()
            second = prepare(root, target_path, binding_path, 'COMPLETE', sys.executable)
            self.assertEqual(2, second['syntheticTransportCalls'])
            self.assertTrue(second['lastExtractionCacheHit'])
            self.assertEqual(model_before, (local / 'model.json').read_bytes())
            self.assertEqual(configuration_before, config_path.read_bytes())
            self.assertEqual(budget_name, second['modelBudget'])
            request['processingContinuation'] = {'parentTaskId': '1' * 64, 'parentResultSha256': digest(parent_bytes)}
            request_path.write_bytes(canonical(request))
            child = local / 'work/pp1-artifacts' / ('2' * 64) / 'paper'
            result = worker(request_path, digest(request_path.read_bytes()), config_path, child)
            self.assertEqual(('EVALUATED', 0), (result['status'], result['modelAttempts']))
            self.assertEqual('NOT_EVALUATED', result['evaluation']['status'])
            self.assertEqual('UNKNOWN', result['evaluation']['ledger']['status'])
            self.assertEqual(1, result['readingState']['available']['acceptedReviewArtifacts'])
            self.assertEqual([], result['readingState']['stageGaps'])
            self.assertTrue(result['readingState']['missing'])
            self.assertEqual(parent_bytes, (parent / 'result.json').read_bytes())
            self.assertFalse(result['readingState']['wholePaperAbsenceClaim'])
            archive = os.environ.get('T02_PP1_JAVA_FIXTURE_OUTPUT')
            if archive:
                destination = Path(archive).resolve()
                if os.name == 'nt': destination = Path('\\\\?\\' + str(destination))
                destination.mkdir(parents=True, exist_ok=False)
                shutil.copytree(local, destination / 'local')
                shutil.copyfile(target_path, destination / 'target.json')
                shutil.copyfile(binding_path, destination / 'binding.json')


if __name__ == '__main__': unittest.main()
