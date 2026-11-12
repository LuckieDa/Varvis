"""Opt-in offline replay of existing public originals; no knowledge is adopted.

VARVIS_PP1_NATIVE_REPLAY_ROOT selects the read-only project resource root.
Outputs are confined to this task's private dispatch/a9/native-replay directory.
Absent resources mean SKIPPED, never a claimed real-source pass. The snapshot
envelope is assembled for a research test from original SOURCE_REVIEW bytes;
it is not represented as a production capture or a patient run.
"""
from collections import defaultdict
from copy import deepcopy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from core_context_review import load_config
from core_local_identity import LocalIdentity
from evaluate_pp1_snapshot import calculate
from model_literature_contract import canonical, digest, parse
from pp1_context import formal_context, select
from pp1_formal_policy import FORMAL_METHOD
import pp1_pedigree


@unittest.skipUnless(os.environ.get('VARVIS_PP1_NATIVE_REPLAY_ROOT'), 'explicit existing public-resource root required')
class NativeReplay(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(os.environ['VARVIS_PP1_NATIVE_REPLAY_ROOT']).resolve(strict=True)
        cls.protected = {}
        dispatch = cls.root / ('local/development/handoffs/acmg-six-framework-tasks-20261101/'
                               'parallel-branches-20261103/dispatch/a9/native-replay')
        dispatch.mkdir(parents=True, exist_ok=True)
        cls.output = Path(tempfile.mkdtemp(prefix='run-', dir=dispatch))
        cls.workflow_path = cls.root / 'local/private/config/denovo-literature-workflow/config.json'
        workflow = cls.read(cls.workflow_path)
        core, unused = load_config(cls.root, workflow['coreContext'])
        cls.identity_configuration = core['identity']
        cls.protect(cls.root / workflow['coreContext']['path'])
        for relative in ('local/cache/denovo-gemini-pilot-20261018/budget.json',
                         'local/cache/literature-gpt-sol-20261027/budget.json',
                         'local/tools/literature-pdf/manifest.json'):
            cls.protect(cls.root / relative)
        # The resource-root injection must not substitute a different verifier
        # implementation or bypass original request/response/receipt checks.
        resource_module = cls.root / 'scripts/denovo/pp1_pedigree.py'
        if resource_module.read_bytes() != Path(pp1_pedigree.__file__).read_bytes():
            raise AssertionError('RESOURCE_ROOT_VERIFIER_BYTES_DIFFER')
        cls.protect(resource_module)
        cls.pillow_root = patch.object(pp1_pedigree, '__file__', str(resource_module))
        cls.pillow_root.start()
        cls.addClassCleanup(cls.pillow_root.stop)
        cls.library = cls.root / 'local/knowledge/literature/public-papers'
        cls.positive = cls.root / 'local/development/handoffs/PP1-positive-validation-20261027/test-01'
        cls.original = cls.root / 'local/development/handoffs/PP1-sol-pedigree-integration-20261027/dev-01'
        cls.binding = cls.read(cls.positive / 'binding.json')
        cls.context = select(cls.root, workflow['pp1Knowledge'], cls.binding['selection'])
        cls.assert_qualified_count_context(cls.context)
        cls.protect(cls.root / workflow['pp1Knowledge']['catalog']['path'])
        specification = cls.context['sourceEvidence']['specification']
        cls.protect(cls.root / workflow['pp1Knowledge']['captureDirectory'] / specification['rawRef'])
        cls.baseline = cls.read(cls.original / 'final-evaluation.json')
        accepted = cls.read(cls.positive / 'model-01/artifacts.json')['acceptedArtifacts']
        cls.doc = cls.document(cls.library, [Path(p) for p in accepted], cls.binding, 'published')
        cls.doc['pedigreeReviews'] = []
        for label in ('visual-01', 'visual-02'):
            retained = cls.read(cls.original / label / 'retained-review.json')
            pair = Path(retained['path'])
            figure = pair.parent.parent / 'source.json'
            destination = cls.output / label
            destination.mkdir()
            entry = {}
            for kind, source, name in (('source', figure, 'source.json'),
                                       ('image', figure.parent / 'original.bin', 'original.bin'),
                                       ('pair', pair, 'pair.json')):
                cls.protect(source)
                raw = source.read_bytes()
                copied = destination / name
                copied.write_bytes(raw)
                entry[kind] = {'path': str(copied.relative_to(cls.root)), 'sha256': digest(raw)}
            cls.doc['pedigreeReviews'].append(entry)
        cls.result = cls.replay(cls.doc, cls.binding, cls.context, cls.library)
        cls.save('published-replay.json', cls.result)
        cls.save('resource-injection.json', {
            'mode': 'TEST_ONLY_EXISTING_RESOURCE_ROOT_INJECTION', 'verifierStubbed': False,
            'imagePixelChecksExecuted': True, 'verifierSha256': digest(resource_module.read_bytes()),
            'toolManifestSha256': cls.protected[str(cls.root / 'local/tools/literature-pdf/manifest.json')],
            'sourceSnapshotEnvelope': 'TEST_ASSEMBLED_FROM_ORIGINAL_PUBLIC_ARTIFACTS_NOT_PRODUCTION_CAPTURE',
            'modelCalls': 0, 'networkRequests': 0, 'patientAnalysis': False})

    @staticmethod
    def assert_qualified_count_context(context):
        if context.get('status') != 'QUALIFIED' or context['policy']['method'] != 'PUBLISHED_AFFECTED_RELATIVES_COUNT':
            raise AssertionError('EXISTING_EXACT_PUBLISHED_COUNT_CONTEXT_REQUIRED')

    @classmethod
    def protect(cls, path):
        path = Path(path).resolve(strict=True)
        if not path.is_relative_to(cls.root):
            raise AssertionError('ORIGINAL_OUTSIDE_DECLARED_RESOURCE_ROOT')
        cls.protected[str(path)] = digest(path.read_bytes())

    @classmethod
    def read(cls, path):
        cls.protect(path)
        return parse(Path(path).read_bytes())

    @classmethod
    def save(cls, name, value):
        (cls.output / name).write_bytes(canonical(value))

    @classmethod
    def document(cls, library, paths, binding, label):
        entries = []
        for index, path in enumerate(paths):
            cls.protect(path)
            raw = path.read_bytes()
            artifact = parse(raw)
            if artifact['binding']['stage'] != 'SOURCE_REVIEW':
                raise AssertionError('REAL_SOURCE_REVIEW_REQUIRED')
            copied = cls.output / (label + '-artifact-' + str(index) + '.json')
            copied.write_bytes(raw)
            entries.append({'path': str(copied.relative_to(cls.root)), 'sha256': digest(raw)})
        source = artifact['binding']
        cls.protect(library / 'evidence/PP1/index.json')
        paper_root = library / 'papers' / source['pmcid']
        bundle = cls.read(paper_root / 'source-bundles' / (source['bundleSha256'] + '.json'))
        cls.protect(paper_root / bundle['main']['path'])
        return {'taskId': digest(canonical([label, entries])),
                'request': {'pmcid': source['pmcid'], 'bundleSha256': source['bundleSha256'], 'analysisBinding': binding},
                'result': {'target': source['target'], 'status': 'PUBLIC_ORIGINAL_REPLAY'}, 'artifacts': entries}

    @classmethod
    def replay(cls, doc, binding, context, library):
        documents = doc if isinstance(doc, list) else [doc]
        return calculate(cls.root, library, cls.output, documents, binding, context,
                         LocalIdentity(cls.root, cls.identity_configuration))

    @classmethod
    def tearDownClass(cls):
        changed = [path for path, pin in cls.protected.items() if digest(Path(path).read_bytes()) != pin]
        cls.save('read-only-input-receipt.json', {'inputs': cls.protected, 'changed': changed,
                 'modelCalls': 0, 'networkRequests': 0, 'businessCalls': 0, 'databaseOperations': 0})
        if changed:
            raise AssertionError('READ_ONLY_ORIGINAL_CHANGED: ' + ', '.join(changed))

    def test_01_real_published_count_replayed_from_raw_originals(self):
        evaluation = self.result['evaluation']
        for key in ('status', 'strength', 'awardedPP1Points', 'rawSegregationPoints', 'cappedPP1Points'):
            self.assertEqual(self.baseline[key], evaluation[key], key)
        self.assertEqual(('MET', 'SUPPORTING', 1),
                         (evaluation['status'], evaluation['strength'], evaluation['awardedPP1Points']))
        self.assertEqual(1, len(evaluation['events']))
        event = evaluation['events'][0]
        self.assertEqual('EXPERT_AFFECTED_RELATIVES_COUNT', event['kind'])
        self.assertEqual(2, event['affectedRelativesCount'])
        self.assertEqual(self.baseline['events'][0]['eventId'], event['eventId'])
        self.assertEqual((0, 0), (self.result['modelCalls'], self.result['networkRequests']))
        self.assertGreater(self.result['qualifications'][0]['pedigreeReview']['reviewedPairs'], 0)

    def test_02_duplicate_and_saved_score_do_not_add_real_family(self):
        document = deepcopy(self.doc)
        document['result']['evaluation'] = {'status': 'MET', 'awardedPP1Points': 99}
        result = self.replay([document, deepcopy(document)], self.binding, self.context, self.library)
        self.assertEqual(1, result['evaluation']['awardedPP1Points'])
        self.assertEqual(1, len(result['evaluation']['events']))
        self.save('duplicate-and-saved-score-control.json', result)

    def test_03_current_pp4_capacity_still_limits_real_count(self):
        binding = deepcopy(self.binding)
        binding['ledger'].update(status='KNOWN', pp4Points=5)
        result = self.replay(self.doc, binding, self.context, self.library)
        self.assertEqual(('NOT_MET', 0), (result['evaluation']['status'], result['evaluation']['awardedPP1Points']))
        self.assertEqual('1', result['evaluation']['rawSegregationPoints'])
        self.save('pp4-capacity-control.json', result)

    def test_04_real_general_source_keeps_knowledge_and_ledger_gaps(self):
        old = self.root / 'local/development/handoffs/PP1-literature-extraction-20261026/dev-01'
        baseline_path = self.root / ('local/development/handoffs/PP1-segregation-stage3-20261026/'
                                    'dev-01/final-verification/PMC9602094-qualification.json')
        qualification = self.read(baseline_path)
        binding = {'selection': qualification['target'], 'ledger': None}
        context = {'status': 'KNOWLEDGE_MISSING', 'reasonCode': 'PP1_EXACT_DISEASE_CONTEXT_NOT_ADOPTED'}
        library = old / 'library'
        document = self.document(library, [old / 'PMC9602094-audit-guided/artifact.json'], binding, 'general')
        result = self.replay(document, binding, context, library)
        self.assertEqual(('NOT_EVALUATED', 0), (result['evaluation']['status'], result['evaluation']['awardedPP1Points']))
        self.assertEqual('pp1-segregation-evaluation-v1', result['evaluation']['schemaVersion'])
        self.assertEqual({'PP1_EXACT_DISEASE_CONTEXT_NOT_ADOPTED', 'PP1_PP4_LEDGER_UNKNOWN'},
                         {n['reasonCode'] for n in result['evaluation']['needs']})
        actual = result['qualifications'][0]
        self.assertIsNone(actual['identityIssue'])
        self.assertEqual(4, sum(o['kind'] == 'age' for o in actual['observations']))
        self.assertEqual(2, sum(o['kind'] == 'genotype' and o['value'] == 'NONCARRIER' for o in actual['observations']))
        self.save('general-source-replay.json', result)

    def test_05_formal_request_without_adopted_parameters_returns_actual_gaps(self):
        # The old count policy is not reclassified as adopted formal knowledge.
        # Request the new method with its genuinely absent parameters and sources.
        row = deepcopy(self.context['policy'])
        row.update(method=FORMAL_METHOD, formalModel=None)
        context = formal_context(row, self.binding['selection'], {}, {
            'dataClass': 'PUBLIC_RESEARCH', 'formalKnowledgeAdopted': False,
            'scopeStatement': 'DIAGNOSTIC_METHOD_REQUEST_ONLY_NOT_KNOWLEDGE_ADOPTION'})
        result = self.replay(self.doc, self.binding, context, self.library)
        evaluation = result['evaluation']
        self.assertEqual(('pp1-segregation-evaluation-v2', FORMAL_METHOD, 'NOT_EVALUATED', 0),
                         (evaluation['schemaVersion'], evaluation['method'], evaluation['status'], evaluation['awardedPP1Points']))
        self.assertTrue(any(n['reasonCode'] == 'PP1_FORMAL_MODEL_REQUIRED' for n in evaluation['needs']))
        self.assertEqual([], evaluation['familyAssessments'])
        self.assertNotIn('bayesFactor', evaluation)
        self.assertTrue(result['qualifications'][0]['observations'])
        self.save('formal-missing-adoption-replay.json', result)

    def test_06_real_source_facts_do_not_satisfy_formal_prerequisites(self):
        qualification = self.result['qualifications'][0]
        rows = qualification['observations']
        by_kind = defaultdict(list)
        for row in rows:
            by_kind[row['kind']].append(row)
        genotypes = by_kind['genotype']
        parents = defaultdict(set)
        for row in rows:
            if row['kind'] == 'parentOf':
                parents[row['value']].add(row['memberKey'])
            elif row['kind'] == 'childOf':
                parents[row['memberKey']].add(row['value'])
        self.assertFalse(by_kind['formalAscertainment'])
        self.assertFalse(by_kind['candidateScope'])
        self.assertFalse(by_kind['candidateMethod'])
        self.assertTrue(genotypes)
        self.assertTrue(all(row['value'] == 'CARRIER' for row in genotypes))
        self.assertTrue(any(len(values) == 1 for values in parents.values()))
        self.save('formal-source-gap-audit.json', {
            'purpose': 'PREREQUISITE_INVENTORY_ONLY_NO_MODEL_OR_SCORE',
            'formalKnowledgeAdopted': False, 'independentFormalParameters': None,
            'ascertainmentSourceQualified': False, 'probandRolesAreNotAscertainmentProof': by_kind['proband'],
            'genericCarrierObservationsCannotEstablishDosage': genotypes,
            'partialParentChainsCannotEstablishAllFounders': by_kind['parentOf'] + by_kind['childOf'],
            'candidateAllocationFacts': by_kind['candidateScope'] + by_kind['candidateMethod'],
            'attainedExaminationAgeFacts': by_kind['examinationAge'],
            'ageOnlyRequiredForAgeBandModel': True, 'wholePublicationAbsenceClaim': False,
            'computedFormalStatistic': False, 'modelCalls': 0, 'networkRequests': 0})


if __name__ == '__main__':
    unittest.main()
