"""Offline continuation controls: real parser/cache, explicitly synthetic model."""
from copy import deepcopy
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch

from test_bundle_qualification import article, registered
from test_literature_source_assertions import PMCID, RECORD, VCV, sha
from test_bundle_model import configuration, response
from model_literature_contract import canonical, parse
from bundle_model_contract import requests, RESPONSE_VERSION
from run_bundle_model import run as real_extract
from continue_literature_paper import process_paper
from bundle_candidate_intake import replay_intake


def source_bytes(raw=None):
    raw = article() if raw is None else raw
    return raw.replace(b'</p></sec></sec>', b'</p><p>Case A was studied. The EXAMPLE variant c.123A&gt;G, p.T41A, was observed again.</p></sec></sec>')


def candidate_response(body):
    content = parse(parse(body)['messages'][1]['content'])
    target = next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
    citation = {'unitId': target['unitId'], 'quote': target['text']}
    candidate = {'schemaVersion': RESPONSE_VERSION, 'coverage': 'EXTRACTED_WITHIN_TARGET',
                 'sourceAssessments': [{'materialKind': 'AGGREGATE_OR_METHODS', 'citation': citation}], 'cases': []}
    if target['kind'] == 'BODY_PARAGRAPH' and 'Case A was studied.' in target['text']:
        method = next(u for u in content['units'] if u['text'] == 'Patients were recruited for this study.')
        candidate['sourceAssessments'][0]['materialKind'] = 'INDIVIDUAL_CASE_MATERIAL'
        candidate['cases'] = [{'caseLabel': 'A', 'caseOrigin': 'AUTHOR_COHORT',
            'caseCitation': {'unitId': target['unitId'], 'quote': 'Case A was studied.'},
            'originCitations': [{'unitId': method['unitId'], 'quote': method['text']}],
            'fields': [{'field': name, 'sourceValue': value, 'subject': 'PROBAND', 'citation': citation}
                       for name, value in [('geneSymbol', 'EXAMPLE'), ('variantText', 'c.123A>G'), ('proteinChange', 'p.T41A')]]}]
    return response(candidate)


def parent_for(item):
    return {'parentTaskId': 'a' * 64, 'parentResultSha256': 'b' * 64,
            'pmcid': item['pmcid'], 'bundleSha256': item['bundleSha256'], 'plannedChunks': item['plannedChunks'],
            'completedTargets': [{'chunkIndex': c['chunkIndex'], 'artifactSha256': c['artifactSha256']} for c in item['chunks']]}


def write_cumulative_fixture(root, conflict=False):
    """Real continuation/parser, SYNTHETIC sources/transport for Java scoring checks."""
    root = Path(root); raw = (root / 'synthetic-literature.xml').read_bytes()
    if b'SYNTHETIC only' not in raw:raise ValueError('SYNTHETIC_FIXTURE_REQUIRED')
    library, source = registered(root, raw)
    c = configuration(); config = root / 'config.json'; config.write_bytes(canonical(c))
    (root / 'key.txt').write_text('synthetic-not-secret')
    case_calls = 0
    def transport(unused, key, body):
        nonlocal case_calls
        output = candidate_response(body)
        value = parse(output); candidate = parse(value['choices'][0]['message']['content'])
        if candidate['cases']:
            case_calls += 1
            if conflict and case_calls > 1:candidate['cases'][0]['caseOrigin'] = 'UNCLEAR'
            value['choices'][0]['message']['content'] = canonical(candidate).decode()
        return canonical(value)
    def run(*args, **kw):
        value = real_extract(*args, **kw, transport=transport)
        if value is None:return None
        path = Path(args[6]) / 'artifact.json'; a = parse(path.read_bytes())
        a['receipt']['transportKind'] = 'SYNTHETIC_TEST'; path.write_bytes(canonical(a))
        value['artifactSha256'] = sha(path.read_bytes())
        for receipt in (root / 'budget/responses').glob('*/receipt.json'):
            r = parse(receipt.read_bytes()); r['transportKind'] = 'SYNTHETIC_TEST'; receipt.write_bytes(canonical(r))
        return value
    parent = None
    with patch('continue_literature_paper.extract', side_effect=run):
        for turn in range(32):
            work = root / f'continuation-{turn}'; work.mkdir()
            result = {'papers': [], 'issues': [], 'modelCalls': 0, 'modelAttempts': 0,
                      'estimatedMicroCreditsThisRun': 0, 'chargedOrReservedMicroCreditsThisRun': 0}
            item = process_paper(library, PMCID, source['bundleSha256'], c, config, root / 'budget',
                                 work, result, lambda: None, time.monotonic()+60, True, parent)
            if not item['continuation']['eligible']:break
            parent = parent_for(item)
    if not item['completeTargetExtraction']:raise ValueError(str(item))
    body = (work / item['candidateIntake']['path']).read_bytes()
    (root / 'bundle-intake.json').write_bytes(body)
    (root / 'model-artifacts.json').write_bytes(canonical({'schemaVersion': 'denovo-bundle-intake-index-v1',
        'artifacts': [{'pmcid': PMCID, 'sourceSha256': sha(raw), 'path': 'bundle-intake.json', 'sha256': sha(body)}]}))
    (root / 'continuation-summary.json').write_bytes(canonical(item))


class ContinuationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.raw = source_bytes()
        self.library, self.source = registered(self.root, self.raw)
        self.c = configuration(); self.config = self.root / 'config.json'
        self.config.write_bytes(canonical(self.c)); (self.root / 'key.txt').write_text('synthetic-not-secret')
        self.calls, self.turn = [], 0

    def transport(self, unused, key, body):
        self.calls.append(body)
        return candidate_response(body)

    def step(self, parent=None, enabled=True, deadline=None):
        self.turn += 1; work = self.root / f'pass-{self.turn}'; work.mkdir()
        result = {'papers': [], 'issues': [], 'modelCalls': 0, 'modelAttempts': 0,
                  'estimatedMicroCreditsThisRun': 0, 'chargedOrReservedMicroCreditsThisRun': 0}
        def run(*args, **kw):return real_extract(*args, **kw, transport=self.transport)
        with patch('continue_literature_paper.extract', side_effect=run):
            item = process_paper(self.library, PMCID, self.source['bundleSha256'], self.c, self.config,
                self.root / 'budget', work, result, lambda: None, deadline or time.monotonic() + 60, enabled, parent)
        return item, result, work

    def test_incremental_targets_are_once_only_and_same_case_is_one(self):
        parent = None; previous = set()
        for unused in range(20):
            item, result, work = self.step(parent)
            current = {c['chunkIndex'] for c in item['chunks']}
            self.assertLessEqual(result['modelCalls'], 2)
            self.assertTrue(previous.issubset(current))
            self.assertEqual(len(current - previous), result['modelCalls'])
            if not item['continuation']['eligible']:break
            parent, previous = parent_for(item), current
        self.assertTrue(item['completeTargetExtraction'], item['issues'])
        self.assertEqual(len(self.calls), len(set(self.calls)))
        self.assertEqual(item['plannedChunks'], len(self.calls))
        self.assertEqual(1, item['cumulativeCases']['uniqueCases'])
        self.assertEqual(2, item['cumulativeCases']['proposals'])
        intake = parse((work / item['candidateIntake']['path']).read_bytes())
        facts = replay_intake(self.raw, PMCID, sha(self.raw), intake, [(RECORD, sha(RECORD), VCV)], work)
        self.assertEqual(1, len(facts['cases'])); self.assertEqual([], facts['cases'][0]['issues'])
        calls = len(self.calls)
        again, result, unused = self.step(parent_for(item))
        self.assertEqual(calls, len(self.calls)); self.assertEqual(0, result['modelCalls'])
        self.assertEqual(item['candidateIntake']['sha256'], again['candidateIntake']['sha256'])

    def test_missing_old_cache_stops_before_any_new_call(self):
        item, unused, work = self.step(); parent = parent_for(item)
        cache = next((self.root / 'budget/responses').iterdir()); (cache / 'receipt.json').unlink()
        calls = len(self.calls); item, result, unused = self.step(parent)
        self.assertEqual(calls, len(self.calls)); self.assertEqual(0, result['modelCalls'])
        self.assertFalse(item['continuation']['eligible']); self.assertIn('CACHE', item['continuation']['reasonCode'])

    def test_changed_parent_identity_and_result_are_not_requeried(self):
        item, unused, work = self.step(); parent = parent_for(item); calls = len(self.calls)
        for field in ('bundleSha256', 'plannedChunks'):
            bad = deepcopy(parent); bad[field] = '0' * 64 if field == 'bundleSha256' else 999
            with self.assertRaisesRegex(ValueError, 'SOURCE_OR_PLAN_CHANGED'):self.step(bad)
        bad = deepcopy(parent); bad['completedTargets'][0]['artifactSha256'] = '0' * 64
        item, unused, work = self.step(bad)
        self.assertIn('CACHE_CHANGED', item['continuation']['reasonCode']); self.assertEqual(calls, len(self.calls))

    def test_duplicate_parent_indices_rejected(self):
        item, unused, work = self.step(); parent = parent_for(item)
        parent['completedTargets'].append(parent['completedTargets'][0])
        with self.assertRaisesRegex(ValueError, 'CHECKPOINT_INVALID'):self.step(parent)

    def test_disabled_reuses_without_credentials_and_stops_no_progress_loop(self):
        item, unused, work = self.step(); calls = len(self.calls)
        (self.root / 'key.txt').unlink()
        new, result, work = self.step(parent_for(item), enabled=False)
        self.assertEqual(calls, len(self.calls)); self.assertEqual(0, result['modelCalls'])
        self.assertFalse(new['continuation']['eligible'])
        self.assertEqual('NO_PROGRESS_CONFIGURATION_OR_CACHE_REQUIRED', new['continuation']['reasonCode'])

    def test_failure_keeps_reservation_and_does_not_skip_to_another_target(self):
        calls = []
        def failure(*args):calls.append(1);raise ValueError('DENOVO_MODEL_HTTP_429')
        self.transport = failure
        item, result, work = self.step()
        self.assertEqual(1, len(calls)); self.assertEqual(1, result['modelCalls'])
        self.assertGreater(result['chargedOrReservedMicroCreditsThisRun'], 0)
        self.assertFalse(item['continuation']['eligible'])
        again, result, work = self.step()
        self.assertEqual(1, len(calls)); self.assertFalse(again['continuation']['eligible'])

    def test_cited_only_invalid_label_is_isolated_without_repaying_or_scoring(self):
        def rejected_response(unused, key, body):
            self.calls.append(body)
            candidate = parse(parse(candidate_response(body))['choices'][0]['message']['content'])
            content = parse(parse(body)['messages'][1]['content'])
            target = next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
            if target['text'] == 'Patients were recruited for this study.':
                citation = {'unitId': target['unitId'], 'quote': target['text']}
                candidate['cases'] = [{'caseLabel': 'Author et al., 2020', 'caseOrigin': 'CITED_ONLY',
                    'caseCitation': citation, 'originCitations': [citation], 'fields': []}]
            return response(candidate)
        self.transport = rejected_response
        parent = None
        for unused in range(20):
            item, result, work = self.step(parent)
            self.assertEqual([], result['issues'])
            if not item['continuation']['eligible']:break
            parent = parent_for(item)
        self.assertEqual(1, len(item['rejectedTargets']))
        rejected = item['rejectedTargets'][0]
        self.assertEqual('DENOVO_PROMPT_CASE_LABEL_NOT_ANCHORED', rejected['reasonCode'])
        hint = parse((work / rejected['path']).read_bytes())
        self.assertEqual('NOT_ELIGIBLE_FOR_SCORING', hint['status'])
        self.assertEqual('CITED_ONLY', hint['sourceHints'][0]['proposedOrigin'])
        self.assertFalse(hint['sourceHints'][0]['originVerified'])
        self.assertEqual(1, item['cumulativeCases']['uniqueCases'])
        self.assertEqual(2, item['cumulativeCases']['proposals'])
        self.assertEqual(len(self.calls), len(set(self.calls)))
        before = (self.root / 'budget/budget.json').read_bytes()
        again, result, unused = self.step(parent_for(item), enabled=False)
        self.assertEqual(0, result['modelCalls'])
        self.assertEqual(before, (self.root / 'budget/budget.json').read_bytes())
        self.assertEqual(item['candidateIntake']['sha256'], again['candidateIntake']['sha256'])
        total_charged = sum(row['chargedOrReservedMicroCredits']
                            for row in parse(before)['calls'])
        self.assertEqual(len(self.calls) * 130, total_charged)
        # Isolation must not hide source/cache tampering.
        path = self.root / 'budget/responses' / rejected['cacheKey'] / 'response.json'
        path.write_bytes(b'{}')
        bad, result, unused = self.step(parent_for(item), enabled=False)
        self.assertIn('CACHE_PIN_MISMATCH', bad['continuation']['reasonCode'])
        self.assertFalse(bad['continuation']['eligible'])

    def test_invalid_primary_label_and_invalid_citation_still_stop(self):
        for kind in ('primary', 'citation'):
            with self.subTest(kind=kind):
                # New independent synthetic budget; never change a failed ledger.
                with tempfile.TemporaryDirectory() as td:
                    root = Path(td)
                    library, source = registered(root, self.raw)
                    cfg = root / 'config.json'; cfg.write_bytes(canonical(self.c))
                    (root / 'key.txt').write_text('synthetic-not-secret')
                    def bad_response(unused, key, body):
                        content = parse(parse(body)['messages'][1]['content'])
                        target = next(u for u in content['units'] if u['unitId'] in content['workUnit']['targetUnitIds'])
                        quote = {'unitId': target['unitId'], 'quote': target['text']}
                        candidate = {'schemaVersion': RESPONSE_VERSION, 'coverage': 'EXTRACTED_WITHIN_TARGET',
                            'sourceAssessments': [{'materialKind': 'UNCLEAR', 'citation': quote}],
                            'cases': [{'caseLabel': 'Invented label', 'caseOrigin': 'AUTHOR_COHORT' if kind == 'primary' else 'CITED_ONLY',
                                'caseCitation': quote, 'originCitations': [quote], 'fields': []}]}
                        if kind == 'citation':
                            candidate['cases'][0]['fields'] = [{'field': 'geneSymbol', 'subject': 'PROBAND',
                                'sourceValue': 'MISSING', 'citation': {'unitId': target['unitId'], 'quote': 'MISSING'}}]
                        return response(candidate)
                    def extract(*args, **kw): return real_extract(*args, **kw, transport=bad_response)
                    work = root / 'pass'; work.mkdir()
                    result = {'papers': [], 'issues': [], 'modelCalls': 0, 'modelAttempts': 0,
                              'estimatedMicroCreditsThisRun': 0, 'chargedOrReservedMicroCreditsThisRun': 0}
                    with patch('continue_literature_paper.extract', side_effect=extract):
                        item = process_paper(library, PMCID, source['bundleSha256'], self.c, cfg, root / 'budget',
                                             work, result, lambda: None, time.monotonic() + 60, True)
                    self.assertTrue(result['issues'])
                    self.assertFalse(item['continuation']['eligible'])
                    self.assertFalse(item.get('rejectedTargets'))
                    self.assertEqual(1, result['modelCalls'])

    def test_expired_budget_cannot_start_transport(self):
        item, result, work = self.step(deadline=time.monotonic() - 1)
        self.assertEqual([], self.calls); self.assertFalse(item['continuation']['eligible'])
        self.assertIn('TIME_BUDGET', item['continuation']['reasonCode'])


if __name__ == '__main__':unittest.main()
