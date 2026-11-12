"""Server worker boundaries with a fixed synthetic public source and no network."""
from pathlib import Path
import unittest
from unittest.mock import patch

from model_literature_contract import canonical, digest, parse
from run_pp1_paper_task import run
from test_pp1_reading import Pipeline
from test_pp1_segregation import selection, ledger


class Worker(unittest.TestCase):
    def setUp(self):
        self.f = Pipeline(); self.f.setUp(); self.addCleanup(self.f.doCleanups)
        self.root = self.f.root
        local = self.root/'local'; local.mkdir()
        # Relocate the fixture as a coupled bundle, without changing sources.
        self.f.library.rename(local/'library'); self.f.library = local/'library'
        self.f.cfg.rename(local/'model.json'); self.f.cfg = local/'model.json'
        self.config = local/'workflow.json'
        self.c = {'schemaVersion':'denovo-public-literature-workflow-v1', 'enabled':True,
                  'projectRoot':str(self.root), 'work':'local/work', 'library':'local/library',
                  'modelConfig':'local/model.json', 'modelBudget':'local/budget', 'allowModel':False}
        self.config.write_bytes(canonical(self.c))
        self.request = {'schemaVersion':'pp1-public-paper-task-v1', 'selectionSha256':'a'*64,
                        'pmcid':'PMC123', 'bundleSha256':self.f.pin, 'target':self.f.target,
                        'analysisBinding':{'schemaVersion':'pp1-analysis-binding-v1', 'selection':selection(), 'ledger':ledger()}}
        self.input = local/'request.json'

    def execute(self, output=None):
        raw = canonical(self.request); self.input.write_bytes(raw)
        return run(self.input, digest(raw), self.config, output or self.root/'local/work/task/paper')

    def test_cache_missing_returns_bound_partial_without_credentials(self):
        with patch('process_pp1_paper.model_run', wraps=__import__('run_pp1_literature_model').run) as called:
            result = self.execute()
        self.assertEqual('PP1_MODEL_DISABLED_CACHE_MISSING', result['stopReason'])
        self.assertEqual(digest(self.input.read_bytes()), result['requestSha256'])
        self.assertTrue(all(c.kwargs['execute'] is False for c in called.call_args_list))

    def test_alias_coverage_survives_missing_materials_and_preserves_deferred_names(self):
        names = self.request['target']['variantAliases']
        self.request['searchAliasCoverage'] = {
            'policy': 'PP1_MANE_CANONICAL_FIRST_MAX20_V1', 'retrievalOnly': True,
            'availableAliases': len(names)+1, 'selectedAliases': len(names), 'omittedAliases': 1,
            'deferredAliases': ['c.999A>G']}
        result = self.execute()
        self.assertEqual(self.request['searchAliasCoverage'], result['searchAliasCoverage'])
        self.assertEqual('PP1_MODEL_DISABLED_CACHE_MISSING', result['stopReason'])

    def test_alias_coverage_inconsistent_count_rejected_before_execution(self):
        self.request['searchAliasCoverage'] = {
            'policy': 'PP1_MANE_CANONICAL_FIRST_MAX20_V1', 'retrievalOnly': True,
            'availableAliases': 900, 'selectedAliases': 1, 'omittedAliases': 0, 'deferredAliases': []}
        with self.assertRaisesRegex(ValueError, 'PP1_SEARCH_ALIAS_COVERAGE_INVALID'):
            self.execute()

    def test_real_pinned_context_loader_passes_identity_configuration_to_resolver(self):
        config = {'schemaVersion':'denovo-core-context-config-v1','identity':{'fixture':'SYNTHETIC'}}
        path=self.root/'local/context.json'; path.write_bytes(canonical(config))
        self.c['coreContext']={'path':'local/context.json','sha256':digest(path.read_bytes())}
        self.config.write_bytes(canonical(self.c))
        with patch('core_local_identity.LocalIdentity') as resolver:
            result=self.execute()
        resolver.assert_called_once_with(self.root,config['identity'])
        self.assertEqual('PP1_MODEL_DISABLED_CACHE_MISSING',result['stopReason'])
        # The save/replay worker consumes the very same loader contract.
        from evaluate_pp1_snapshot import run as run_snapshot
        spec={'schemaVersion':'pp1-business-evaluation-v1',
              'analysisBinding':self.request['analysisBinding'],'documents':[]}
        specification=self.root/'local/snapshot.json';specification.write_bytes(canonical(spec))
        with patch('core_local_identity.LocalIdentity') as resolver:
            result=run_snapshot(specification,digest(specification.read_bytes()),self.config,
                                self.root/'local/work/snapshot')
        resolver.assert_called_once_with(self.root,config['identity'])
        self.assertEqual('NOT_EVALUATED',result['evaluation']['status'])

    def test_modified_request_bytes_rejected_before_configuration(self):
        self.input.write_bytes(canonical(self.request))
        with self.assertRaisesRegex(ValueError, 'PP1_REQUEST_BYTES_CHANGED'):
            run(self.input, 'b'*64, self.root/'nonexistent', self.root/'local/work/task')

    def test_caller_policy_or_ledger_root_field_rejected(self):
        self.request['policy'] = {'status':'APPROVED'}
        with self.assertRaisesRegex(ValueError, 'PP1_REQUEST_SCHEMA'): self.execute()

    def test_output_cannot_leave_private_work(self):
        with self.assertRaisesRegex(ValueError, 'PP1_OUTPUT_OUTSIDE_WORK_OR_EXISTS'):
            self.execute(self.root/'outside')

    def test_library_cannot_leave_local(self):
        self.c['library'] = '../other'; self.config.write_bytes(canonical(self.c))
        with self.assertRaisesRegex(ValueError, 'PP1_PATH_OUTSIDE_LOCAL'): self.execute()

    def test_disabled_workflow_does_not_start(self):
        self.c['enabled'] = False; self.config.write_bytes(canonical(self.c))
        with self.assertRaisesRegex(ValueError, 'PP1_WORKFLOW_DISABLED'): self.execute()

    def test_configuration_changed_during_execution_rejected(self):
        def changed(*args, **kwargs):
            self.config.write_bytes(canonical({**self.c,'allowModel':True}))
            return {'status':'SYNTHETIC'}
        with patch('run_pp1_paper_task.process', side_effect=changed):
            with self.assertRaisesRegex(ValueError, 'PP1_CONFIGURATION_CHANGED_DURING_TASK'): self.execute()

    def test_malformed_identity_rejected_without_model(self):
        self.request['pmcid'] = '../../source'
        with self.assertRaisesRegex(ValueError, 'PP1_REQUEST_IDENTITY_INVALID'): self.execute()

    def discovery_request(self):
        self.request['schemaVersion']='pp1-public-discovery-task-v1'
        del self.request['pmcid'];del self.request['bundleSha256']
        self.c['searchCache']='local/search-cache';self.config.write_bytes(canonical(self.c))

    def test_discovery_dispatch_preserves_current_binding_and_disabled_switches(self):
        self.discovery_request()
        def fake(*args,**kwargs):
            self.assertEqual(self.root/'local/search-cache',args[2])
            self.assertEqual(self.request['analysisBinding'],args[7])
            self.assertFalse(kwargs['allow_model']);self.assertFalse(kwargs['allow_network'])
            args[10].mkdir(parents=True)
            return {'status':'PARTIAL','scope':'SYNTHETIC_ROUTING_ONLY'}
        with patch('process_pp1_discovery.run',side_effect=fake) as called:result=self.execute()
        self.assertEqual(1,called.call_count);self.assertEqual(self.request['selectionSha256'],result['selectionSha256'])

    def test_discovery_requires_the_shared_search_cache_configuration(self):
        self.discovery_request()
        del self.c['searchCache']; self.c['cache']='local/incorrect-legacy-name'
        self.config.write_bytes(canonical(self.c))
        with patch('process_pp1_discovery.run') as called:
            with self.assertRaisesRegex(ValueError,'PP1_LOCAL_PATH_CONFIGURATION_REQUIRED'):self.execute()
        called.assert_not_called()

    def test_continuation_manifest_cannot_escape_work(self):
        self.discovery_request()
        path=self.root/'local/other/artifacts.json';path.parent.mkdir();path.write_bytes(b'{}')
        self.request['continuation']={'parentTaskId':'a'*64,'parentResultSha256':'b'*64,
            'stateSha256':'c'*64,'statePath':'local/other/discovery-state.json',
            'manifestPath':'local/other/artifacts.json','manifestSha256':digest(b'{}')}
        with self.assertRaisesRegex(ValueError,'PP1_CONTINUATION_PATH'):self.execute()


if __name__ == '__main__': unittest.main()
