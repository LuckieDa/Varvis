// Synthetic contract and React SSR checks. No HTTP, scientific qualification or model execution.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const project = path.resolve(__dirname, '..');
const mainClient = process.env.VARVIS_READONLY_CLIENT_ROOT || path.resolve(project, '../../../../../varvis-client-pro');
const deps = createRequire(path.join(mainClient, 'package.json'));
const { buildSync } = deps('esbuild');
const compiled = { exports: {} };
const built = buildSync({ stdin: { contents: `
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import * as diagnostics from './src/pages/varvis/ChromosomeVisualization/utils/acmgLiteratureDiagnostics.js';
import View,{EvaluationNeedDifferences,LiteratureAcquisitionDiagnostics} from './src/pages/varvis/ChromosomeVisualization/components/LiteratureStageDiagnostics.jsx';
import {pp1ContinuationIssue,preparePp1Continuation,Pp1SavedFacts,Pp1PaperProgress,validatePp1Task} from './src/pages/varvis/ChromosomeVisualization/components/Pp1LiteraturePanel.jsx';
import {pm3ContinuationState,preparePm3Continuation,Pm3SavedFacts,Pm3PaperFacts,validatePm3Task} from './src/pages/varvis/ChromosomeVisualization/components/Pm3LiteraturePanel.jsx';
export {diagnostics,pp1ContinuationIssue,preparePp1Continuation,pm3ContinuationState,preparePm3Continuation,validatePp1Task,validatePm3Task};
export const renderView=(value,historical=false,compact=false)=>renderToStaticMarkup(<View value={value} historical={historical} compact={compact}/>);
export const renderAcquisition=value=>renderToStaticMarkup(<LiteratureAcquisitionDiagnostics value={value}/>);
export const renderNeeds=needs=>renderToStaticMarkup(<EvaluationNeedDifferences needs={needs}/>);
export const renderPp1=paper=>renderToStaticMarkup(<Pp1PaperProgress paper={paper}/>);
export const renderPm3=paper=>renderToStaticMarkup(<Pm3PaperFacts paper={paper}/>);
export const renderSavedPp1=value=>renderToStaticMarkup(<Pp1SavedFacts value={value}/>);
export const renderSavedPm3=value=>renderToStaticMarkup(<Pm3SavedFacts value={value}/>);
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', write: false,
nodePaths: [path.join(mainClient, 'node_modules')], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' }).outputFiles[0].text;
new Function('require', 'module', 'exports', built)(deps, compiled, compiled.exports);
const api = compiled.exports, d = api.diagnostics, pin = 'a'.repeat(64), other = 'b'.repeat(64), id = 'c'.repeat(64);
const clone = (value) => structuredClone(value), checks = [];
const check = async (name, fn) => { await fn(); checks.push({ name, status: 'PASS' }); };
const current = () => ({ status: 'READY', canContinueProcessing: true, processingAttempt: 1,
    maximumProcessingAttempts: 3, result: { schemaVersion: 'pp1-paper-process-v1', modelCalls: 0 } });
const reading = () => ({ schemaVersion: 'pp1-paper-reading-state-v1', stage: 'SOURCE_REVIEW',
    required: { registeredSourceBundle: pin, sourceReviewArtifactsAtLeast: 1 },
    available: { verifiedSourceBundle: pin, acceptedReviewArtifacts: 0, textUnits: 5, qualifiedObservations: 0 },
    checkpoint: { pmcid: 'PMC123', bundleSha256: pin, acceptedArtifactSha256s: [] },
    stageGaps: [{ kind: 'SYSTEM_STAGE', stage: 'SOURCE_REVIEW', reasonCode: 'SOURCE_REVIEW_PENDING',
        required: { minimumReviewArtifacts: 1 }, available: { replayedReviewArtifacts: 0 } }],
    missing: [{ reasonCode: 'CLINICAL_STATUS_REQUIRED', knownFactCount: 2, sourceLocatorCount: 1,
        quote: 'PRIVATE_QUOTE_NOT_PROGRESS', scope: { memberLabel: 'PRIVATE_MEMBER_NOT_PROGRESS' },
        knownFacts: [{ value: 'PRIVATE_VALUE_NOT_PROGRESS' }] }] });
const checkpoint = () => ({ schemaVersion: 'pm3-paper-processing-checkpoint-v1', pmcid: 'PMC123', bundleSha256: pin,
    stages: [{ chunkIndex: 0, stage: 'EXTRACTION', status: 'SOURCE_CHECKED', artifactPath: 'PRIVATE_PATH_NOT_PROGRESS' },
        { chunkIndex: 0, stage: 'REVIEW', status: 'NOT_REVIEWED', reasonCode: 'DENOVO_BUNDLE_PM3_TIME_BUDGET_EXHAUSTED' }],
    newModelWorkStoppedAfterFailure: false, savedScoreAdopted: false });
const depthAt = (html, marker) => {
    const index = html.indexOf(marker); assert.ok(index >= 0, `Expected rendered marker: ${marker}`);
    const prefix = html.slice(0, index);
    return (prefix.match(/<details(?:\s|>)/g) || []).length - (prefix.match(/<\/details>/g) || []).length;
};
async function main() {
    await check('current server permission and finite counter allow a ready task', () => assert.equal(d.currentLiteratureContinuationIssue(current(), 'PP1'), null));
    await check('old/missing permission never derives action from progress or zero calls', () => {
        for (const changes of [{ canContinueProcessing: undefined }, { canContinueProcessing: false }, { canContinueProcessing: 'true' }]) {
            const t = { ...current(), ...changes }; t.result.discoveryState = { resumeAvailable: true };
            assert.ok(d.currentLiteratureContinuationIssue(t, 'PP1'));
        }
    });
    await check('nonterminal and missing result tasks cannot continue', () => {
        for (const status of ['RUNNING', 'QUEUED', 'FAILED', 'CANCELLED']) assert.ok(d.currentLiteratureContinuationIssue({ ...current(), status }, 'PM3'));
        assert.ok(d.currentLiteratureContinuationIssue({ ...current(), result: undefined }, 'PM3'));
    });
    await check('missing malformed or exhausted counts cannot enable continuation', () => {
        for (const changes of [{ processingAttempt: undefined }, { maximumProcessingAttempts: undefined }, { processingAttempt: '1' },
            { maximumProcessingAttempts: 0 }, { processingAttempt: -1 }, { processingAttempt: 3 }, { maximumProcessingAttempts: Number.MAX_SAFE_INTEGER + 1 }])
            assert.ok(d.currentLiteratureContinuationIssue({ ...current(), ...changes }, 'PM3'));
    });
    await check('current server permission may explicitly supply another valid limit without frontend policy duplication', () => {
        const task = { ...current(), processingAttempt: 4, maximumProcessingAttempts: 5 };
        assert.equal(d.currentLiteratureContinuationIssue(task, 'PM3'), null);
        assert.ok(d.currentLiteratureContinuationIssue({ ...task, canContinueProcessing: false }, 'PM3'));
        assert.ok(d.currentLiteratureContinuationIssue({ ...task, processingAttempt: 5 }, 'PM3'));
    });
    await check('all explicit blocked reasons deny continuation', () => {
        for (const reason of ['PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED', 'PP1_TASK_DEPENDENCY_CHANGED',
            'PM3_PROCESSING_CONTINUATION_LIMIT', {}, 'NEW_UNKNOWN_SERVER_BLOCK'])
            assert.ok(d.currentLiteratureContinuationIssue({ ...current(), processingContinuationBlockedReason: reason }, 'PM3'));
    });
    await check('own and nested Java/Python accounting markers override asserted permission', () => {
        for (const nested of [false, true]) for (const marker of [
            { callAccountingStatus: 'CHECK_SHARED_LEDGER_FOR_FAILED_ATTEMPT' },
            { readingLoop: { failedAttemptAccounting: 'CHECK_SHARED_LEDGER' } },
            { readingState: { recovery: { sharedBudgetReconciliationRequired: true } } },
            { readingState: { sourceExecutionRecovery: { sharedBudgetReconciliationRequired: true } } },
            { readingState: { recovery: { modelChargeStatus: 'FAILED_OR_UNKNOWN_RESERVATION_RETAINED' } } },
            { reading: { recovery: { sharedBudgetReconciliationRequired: true } } }]) {
            const t = current(); t.result = nested ? { papers: [{ papers: [marker] }] } : marker;
            assert.match(d.currentLiteratureContinuationIssue(t, 'PM3'), /核对/);
        }
    });
    await check('shared reconciliation flag and PP1 model issue block work', () => {
        assert.ok(d.currentLiteratureContinuationIssue({ ...current(), sharedBudgetReconciliationRequired: true }, 'PP1'));
        const t = current(); t.result.issues = ['DENOVO_MODEL_TIMEOUT']; assert.ok(d.currentLiteratureContinuationIssue(t, 'PP1'));
    });
    await check('fixed PP1 paper uses actual controls and needs no fabricated discovery state', () => assert.equal(api.pp1ContinuationIssue(current()), null));
    await check('PP1 discovery resume alone cannot grant permission', () => {
        const t = current(); t.canContinueProcessing = false; t.result = { schemaVersion: 'pp1-discovery-process-v1', discoveryState: { resumeAvailable: true } };
        assert.ok(api.pp1ContinuationIssue(t));
    });
    await check('PP1 discovery uses only its actual separate permission counter and blocked reason', () => {
        const t = current(); t.canContinueProcessing = false; t.canContinueDiscovery = true;
        t.discoveryAttempt = 2; t.maximumDiscoveryAttempts = 3;
        t.result = { schemaVersion: 'pp1-discovery-process-v1', artifactManifestSha256: pin,
            discoveryState: { resumeAvailable: true, sha256: pin, pendingPapers: 1, pendingQueries: 0 } };
        assert.equal(api.pp1ContinuationIssue(t), null);
        for (const changes of [{ canContinueDiscovery: false }, { canContinueDiscovery: undefined },
            { discoveryAttempt: 3 }, { maximumDiscoveryAttempts: undefined }, { discoveryContinuationBlockedReason: 'PP1_DISCOVERY_CONTINUATION_LIMIT' }])
            assert.ok(api.pp1ContinuationIssue({ ...t, ...changes }));
        const old = { ...t }; delete old.canContinueDiscovery; old.canContinueProcessing = true;
        assert.ok(api.pp1ContinuationIssue(old), 'processing permission cannot substitute for discovery permission');
    });
    await check('PP1 blocked current read never builds a declaration or starts work', async () => {
        let builtDeclarations = 0, reads = 0; const t = { ...current(), taskId: id, schemaVersion: 'pp1-public-literature-task-v1' };
        t.result = { ...t.result, status: 'PARTIAL', businessPublishable: false, modelAttempts: 0 };
        t.processingContinuationBlockedReason = 'PP1_TASK_DEPENDENCY_CHANGED';
        await assert.rejects(api.preparePp1Continuation({ analysisId: 'synthetic', taskId: id, draftIdentity: 'current',
            prepareDeclaration: () => { builtDeclarations++; return { hypotheses: [] }; } }, async () => { reads++; return { data: t }; }));
        assert.equal(reads, 1); assert.equal(builtDeclarations, 0);
    });
    await check('PM3 fixed preflight checkpoint may continue only with current server permission', () => {
        const t = current(); t.result = { schemaVersion: 'pm3-paper-process-v1', pmcid: 'PMC123', bundleSha256: pin, processingCheckpoint: checkpoint() };
        assert.equal(api.pm3ContinuationState(t).allowed, true);
        t.sharedBudgetReconciliationRequired = true; assert.equal(api.pm3ContinuationState(t).allowed, false);
        t.sharedBudgetReconciliationRequired = false; t.canContinueProcessing = false; assert.equal(api.pm3ContinuationState(t).allowed, false);
    });
    await check('actual PM3 saved source reference still blocks the wrong paper independently of progress', () => {
        const t = current(); t.resultSha256 = other;
        t.result = { schemaVersion: 'pm3-paper-process-v1', pmcid: 'PMC999', selectionSha256: pin, bundleSha256: pin, processingCheckpoint: checkpoint() };
        assert.equal(api.pm3ContinuationState(t, { selectionSha256: pin, capturedResultSha256: other, pmcid: 'PMC123' }).allowed, false);
    });
    await check('explicit current permission is not vetoed by absent legacy pending fields', () => {
        const pp1 = current(); pp1.result = { schemaVersion: 'pp1-discovery-process-v1', stopReason: 'READING_PLAN_NO_PROGRESS' };
        pp1.canContinueDiscovery = true; pp1.discoveryAttempt = 1; pp1.maximumDiscoveryAttempts = 3;
        assert.equal(api.pp1ContinuationIssue(pp1), null);
        const pm3 = current(); pm3.result = { schemaVersion: 'pm3-discovery-process-v1' };
        assert.equal(api.pm3ContinuationState(pm3).allowed, true);
        pm3.result.readingLoop = { state: 'INCOMPLETE', feedback: { hasPendingRelevantWork: false } };
        assert.equal(api.pm3ContinuationState(pm3).allowed, true);
        pm3.result.schemaVersion = 'unsupported-future-task'; assert.equal(api.pm3ContinuationState(pm3).allowed, false);
    });
    await check('current refusal or uncertain accounting overrides positive legacy resume hints in both panels', () => {
        for (const accounting of [false, true]) {
            const pp1 = current(); pp1.result = { schemaVersion: 'pp1-discovery-process-v1', discoveryState: { resumeAvailable: true, pendingPapers: 5 } };
            pp1.canContinueDiscovery = accounting; pp1.discoveryAttempt = 1; pp1.maximumDiscoveryAttempts = 3;
            pp1.sharedBudgetReconciliationRequired = accounting;
            assert.ok(api.pp1ContinuationIssue(pp1));
            const pm3 = current(); pm3.canContinueProcessing = accounting; pm3.sharedBudgetReconciliationRequired = accounting;
            pm3.result = { schemaVersion: 'pm3-discovery-process-v1', discoveryState: { resumeAvailable: true, pendingQueries: 1 } };
            assert.equal(api.pm3ContinuationState(pm3).allowed, false);
        }
    });
    await check('PP1 required existing and difference are explicit without promoting scientific status', () => {
        const original = reading(), before = JSON.stringify(original), v = d.pp1ReadingDiagnostic(original, { pmcid: 'PMC123', bundleSha256: pin });
        const html = api.renderView(v);
        assert.ok(html.includes('需要') && html.includes('已有') && html.includes('差异') && html.includes('至少还需 1 份复核'));
        assert.ok(html.includes('系统处理进度') && html.includes('尚待补齐的判定资料'));
        assert.ok(!html.includes('PRIVATE_')); assert.ok(!JSON.stringify(v).includes('PRIVATE_'));
        assert.equal(JSON.stringify(original), before); assert.equal(v.status, undefined); assert.equal(v.appliedStrength, undefined);
    });
    await check('mismatched PP1 source or enclosing paper withholds all stage detail', () => {
        for (const expected of [{ pmcid: 'PMC999' }, { bundleSha256: other }]) assert.equal(d.pp1ReadingDiagnostic(reading(), expected).invalidBinding, true);
        const value = reading(); value.available.verifiedSourceBundle = other;
        assert.equal(d.pp1ReadingDiagnostic(value).invalidBinding, true);
        assert.ok(api.renderView(d.pp1ReadingDiagnostic(value)).includes('来源关联不完整或不一致'));
        const wrongGap = reading(); wrongGap.stageGaps[0].scope = { pmcid: 'PMC999', bundleSha256: pin };
        assert.equal(d.pp1ReadingDiagnostic(wrongGap).invalidBinding, true);
    });
    await check('unknown numbers are not zero or a completed gap', () => {
        const value = reading(); value.available.acceptedReviewArtifacts = '0'; value.required.sourceReviewArtifactsAtLeast = null;
        assert.ok(api.renderView(d.pp1ReadingDiagnostic(value)).includes('无法计算数量差异'));
    });
    await check('multiple stage differences remain visible and omitted or unknown records are counted', () => {
        const value = reading(); value.stageGaps = Array.from({ length: 70 }, () => clone(value.stageGaps[0]));
        const view = d.pp1ReadingDiagnostic(value); assert.equal(view.stageGaps.length, 64); assert.equal(view.stageGapTotal, 70);
        assert.equal(view.omittedStageGaps, 6); assert.ok(api.renderView(view).includes('另有 6 条'));
        value.stageGaps = [{ kind: 'UNRECOGNIZED_PRIVATE_KIND', quote: 'PRIVATE_QUOTE_NOT_PROGRESS' }];
        const unknown = d.pp1ReadingDiagnostic(value); assert.equal(unknown.stageGaps.length, 0); assert.equal(unknown.omittedStageGaps, 1);
        assert.ok(!api.renderView(unknown).includes('PRIVATE_QUOTE_NOT_PROGRESS'));
    });
    await check('private strings and fields never leak through bounded diagnostics', () => {
        const value = reading(); value.stage = 'PRIVATE_PATH/secret'; value.missing[0].reasonCode = 'private unsafe reason';
        const html = api.renderView(d.pp1ReadingDiagnostic(value)); assert.ok(!html.includes('PRIVATE_') && !html.includes('private unsafe reason'));
    });
    await check('PM3 stages show separate extraction and review and do not expose artifact paths', () => {
        const value = checkpoint(), before = JSON.stringify(value), v = d.pm3CheckpointDiagnostic(value, value), html = api.renderView(v);
        assert.ok(html.includes('提取资料') && html.includes('尚未复核') && html.includes('不代表整篇阅读已完成'));
        assert.ok(!html.includes('PRIVATE_PATH_NOT_PROGRESS')); assert.equal(JSON.stringify(value), before);
        assert.equal(v.savedScoreAdopted, undefined);
    });
    await check('stage records are bounded with an accurate undisplayed count', () => {
        const value = checkpoint(); value.stages = Array.from({ length: 600 }, (_, index) => ({ chunkIndex: index, stage: 'REVIEW', status: 'NOT_REVIEWED' }));
        const v = d.pm3CheckpointDiagnostic(value); assert.equal(v.stages.length, 512); assert.equal(v.omittedStages, 88); assert.ok(api.renderView(v).includes('88'));
    });
    await check('compact saved summary places a late failure before ordinary pending rows without folding its cause', () => {
        const value = checkpoint(); value.stages = Array.from({ length: 30 }, (_, index) => ({ chunkIndex: index, stage: 'REVIEW', status: 'NOT_REVIEWED' }));
        value.stages.push({ chunkIndex: 31, stage: 'REVIEW', status: 'SOURCE_REJECTED', reasonCode: 'SOURCE_REFERENCE_MISMATCH' });
        const html = api.renderView(d.pm3CheckpointDiagnostic(value), false, true);
        assert.equal(depthAt(html, 'SOURCE_REFERENCE_MISMATCH'), 0); assert.ok(html.includes('来源检查未通过'));
    });
    await check('size-withheld records retain authoritative totals and distinguish omission from completion', () => {
        const value = checkpoint(); delete value.stages;
        value.projection = { status: 'WITHHELD', reasonCode: 'PM3_STAGE_PUBLIC_SIZE_LIMIT', stageTotal: 8192,
            stageOmitted: 8192, stagePending: 8000, sourceRejectedStages: 2 };
        const html = api.renderView(d.pm3CheckpointDiagnostic(value));
        assert.ok(html.includes('原记录共 8192 项阶段') && html.includes('待处理 8000 项') && html.includes('来源检查未通过 2 项'));
        assert.equal(d.pm3CheckpointDiagnostic(value, { pmcid: 'PMC999' }).invalidBinding, true);
    });
    await check('task-scoped acquisition failure renders even without any paper or bundle', () => {
        const raw = [{ pmcid: null, stage: 'ACQUISITION', status: 'SYSTEM_TRANSFER_INCOMPLETE',
            reasonCode: 'PAPER_DOWNLOAD_TIMEOUT', sourceAbsenceProven: false }];
        const html = api.renderAcquisition(d.literatureAcquisitionDiagnostic(raw));
        assert.ok(html.includes('尚未登记论文的任务范围') && html.includes('下载超时') && html.includes('不表示没有论文'));
    });
    await check('acquisition cannot turn an absence assertion into progress permission', () => {
        const v = d.literatureAcquisitionDiagnostic([{ pmcid: 'PMC123', stage: 'ACQUISITION', status: 'SYSTEM_TRANSFER_INCOMPLETE', sourceAbsenceProven: true }]);
        assert.equal(v.items.length, 0); assert.ok(api.renderAcquisition(v).includes('尚待核对')); assert.equal(v.allowed, undefined);
    });
    await check('live source components consume their own raw processing records', () => {
        assert.ok(api.renderPp1({ pmcid: 'PMC123', bundleSha256: pin, readingState: reading() }).includes('至少还需 1 份复核'));
        assert.ok(api.renderPm3({ pmcid: 'PMC123', bundleSha256: pin, processingCheckpoint: checkpoint() }).includes('尚未复核'));
    });
    await check('old saved facts stay readable and never invent a fresh processing state', () => {
        const pp1 = api.renderSavedPp1({ schemaVersion: 'pp1-selected-facts-v1', evaluation: { status: 'NOT_EVALUATED' }, sourceCoverage: [{ pmcid: 'PMC123' }] });
        const pm3 = api.renderSavedPm3({ schemaVersion: 'pm3-selected-facts-v1', paperCoverage: [{ pmcid: 'PMC123' }] });
        for (const html of [pp1, pm3]) { assert.ok(html.includes('没有记录分阶段处理资料')); assert.ok(!html.includes('<button')); }
    });
    await check('published evaluation needs show concrete scope required facts and remaining difference', () => {
        const need = { reasonCode: 'PP1_MEMBER_EVENT_INPUT_INCOMPLETE', requirement: '需要同一成员的本次疾病状态及目标变异实际检测结果。',
            scope: { pmcid: 'PMC123', familyLabel: 'F1', memberLabel: 'II-1', missingInputs: ['CLINICAL_STATUS'] },
            knownFacts: [{ kind: 'genotype', value: 'HETEROZYGOUS' }, { kind: 'examinationAge', value: 12 }], blockingEffect: 'MAY_ADD_EVENT' };
        const original = JSON.stringify(need), html = api.renderNeeds([need]);
        assert.ok(html.includes('家系 F1') && html.includes('成员 II-1') && html.includes('需要的信息') && html.includes('实际已有记录'));
        assert.ok(html.includes('目标变异基因型记录：杂合') && html.includes('检查年龄记录：12'));
        assert.ok(html.includes('尚缺：符合本次疾病定义的临床状态依据'));
        assert.ok(html.includes('不表示已经确认全部含义或可以计分')); assert.equal(JSON.stringify(need), original);
    });
    await check('reported variant paragraphs are not mined for HGVS or copied as a complete source paragraph', () => {
        const html = api.renderNeeds([{ reasonCode: 'PP1_LOCAL_IDENTITY_PROVIDER_UNAVAILABLE', requirement: '需要相容的本地身份资源。',
            scope: { pmcid: 'PMC123' }, knownFacts: [{ kind: 'reportedVariant', value: 'Synthetic source paragraph records NM_123.4:c.123A>G with additional private prose.' }] }]);
        assert.ok(!html.includes('NM_123.4:c.123A&gt;G')); assert.ok(!html.includes('additional private prose'));
        assert.ok(html.includes('已记录相关变异描述'));
        assert.ok(api.renderNeeds([{ knownFacts: [{ kind: 'reportedVariant', value: 'NM_123.4:c.123A>G' }] }]).includes('NM_123.4:c.123A&gt;G'));
    });
    await check('existing typed clinical observations retain status age and disease separately', () => {
        const html = api.renderNeeds([{ reasonCode: 'PP1_FORMAL_CLINICAL_INPUT_REQUIRED',
            expected: 'SAME_SOURCE_CLINICAL_OBSERVATION_STATUS_AND_ATTAINED_AGE',
            knownFacts: [{ status: 'UNAFFECTED', attainedAge: 37, diseaseTerm: 'Marfan syndrome', sourceValue: 'PRIVATE_SOURCE_PARAGRAPH' }] }]);
        assert.ok(html.includes('记录的观察年龄：37 岁') && html.includes('报告为未受累') && html.includes('Marfan syndrome'));
        assert.ok(html.includes('同一次原始观察')); assert.ok(!html.includes('PRIVATE_SOURCE_PARAGRAPH'));
    });
    await check('explicit scalar actual input remains distinct from missing actual information', () => {
        assert.ok(api.renderNeeds([{ requirement: '需要明确采用的数值。', actual: 0 }]).includes('当前输入：0'));
        assert.ok(api.renderNeeds([{ requirement: '需要明确采用的数值。', actual: null }]).includes('未记录可在此简表展示的具体已有事实'));
    });
    await check('need view does not copy arbitrary extras private paths or source paragraphs', () => {
        const html = api.renderNeeds([{ requirement: '需要核对该条件。', scope: { memberLabel: 'C:\\private\\patient.txt', arbitrary: 'PRIVATE_EXTRA' },
            knownFacts: [{ kind: 'UNRECOGNIZED_KIND', value: { token: 'PRIVATE_TOKEN' } },
                { kind: 'diagnosis', value: 'C:\\private\\record.json' }, { sourceValue: 'PRIVATE_WHOLE_PARAGRAPH', quote: 'PRIVATE_QUOTE' }],
            actual: { path: 'PRIVATE_PATH', quote: 'PRIVATE_QUOTE' } }]);
        assert.ok(!html.includes('PRIVATE_') && !html.includes('C:\\private')); assert.ok(html.includes('条已有记录未在简表展示'));
    });
    await check('permitted scalar text is escaped by React and complete scope identifiers are retained', () => {
        const html = api.renderNeeds([{ scope: { memberKey: pin, observationId: other }, knownFacts: [{ kind: 'diagnosis', value: '<script>alert(1)</script>' }] }]);
        assert.ok(html.includes(pin) && html.includes(other)); assert.ok(!html.includes('<script>')); assert.ok(html.includes('&lt;script&gt;'));
    });
    await check('simple saved PP1 card visibly includes the normal evaluation need facts', () => {
        const html = api.renderSavedPp1({ schemaVersion: 'pp1-selected-facts-v1', evaluation: { status: 'NOT_EVALUATED', needs: [{
            reasonCode: 'PP1_MEMBER_EVENT_INPUT_INCOMPLETE', requirement: '需要临床状态。', scope: { memberLabel: 'II-1', missingInputs: ['CLINICAL_STATUS'] },
            knownFacts: [{ kind: 'genotype', value: 'CARRIER' }] }] }, sourceCoverage: [] });
        assert.ok(html.includes('成员 II-1') && html.includes('携带目标变异') && html.includes('当前差异')); assert.ok(!html.includes('<button'));
        assert.equal(depthAt(html, '实际已有记录'), 0, 'critical biological needs must be visible without opening details');
    });
    await check('producer totals and omitted counters are preserved rather than replaced by array length', () => {
        const raw = checkpoint(); raw.projection = { status: 'AVAILABLE', stageTotal: 102, stageOmitted: 100, stagePending: 51, sourceRejectedStages: 1 };
        const view = d.pm3CheckpointDiagnostic(raw); assert.equal(view.stageTotal, 102); assert.equal(view.omittedStages, 100); assert.equal(view.pendingStages, 51);
        const rr = reading(); rr.projection = { status: 'AVAILABLE', missingTotal: 9, missingOmitted: 7, missingInvalid: 1 };
        assert.equal(d.pp1ReadingDiagnostic(rr).missingTotal, 9);
        const acquisition = d.literatureAcquisitionDiagnostic([{ pmcid: null, stage: 'ACQUISITION', status: 'SYSTEM_TRANSFER_INCOMPLETE',
            reasonCode: 'PAPER_DOWNLOAD_TIMEOUT', sourceAbsenceProven: false }], { status: 'AVAILABLE', total: 21, omitted: 20 });
        assert.equal(acquisition.total, 21); assert.equal(acquisition.omitted, 20);
    });
    await check('malformed public aggregate counts remain unknown and never become zero', () => {
        const raw = checkpoint(); raw.projection = { status: 'AVAILABLE', stageTotal: '100', stageOmitted: '98' };
        const view = d.pm3CheckpointDiagnostic(raw); assert.equal(view.stageTotal, null); assert.equal(view.omittedStages, null);
        assert.ok(api.renderView(view).includes('尚未完整记录'));
    });
    await check('bounded published missing rows retain legal object scope typed actual facts inputs and locators', () => {
        const rr = reading(), projection = (total, displayed, withheld = 0, omitted = 0) => ({ status: withheld || omitted ? 'PARTIAL' : 'AVAILABLE', total, displayed, withheld, omitted });
        rr.projection = { status: 'AVAILABLE', missingTotal: 1, missingOmitted: 0, missingInvalid: 0 };
        rr.missing = [{ reasonCode: 'PP1_MEMBER_EVENT_INPUT_INCOMPLETE', blockingEffect: 'MAY_ADD_EVENT',
            scope: { paper: { pmcid: 'PMC123', bundleSha256: pin }, familyKey: pin, memberKey: other, memberLabel: 'PRIVATE_UNAPPROVED_MEMBER' },
            scopeProjection: { ...projection(3, 3), kind: 'OBJECT' }, requiredInputs: ['CLINICAL_STATUS'], requiredInputsProjection: projection(1, 1),
            requirement: '需要同一成员的本次疾病状态及目标变异实际检测结果。', requirementProjection: { status: 'AVAILABLE', source: 'PROGRAM_REQUIREMENTS_CATALOG' },
            knownFacts: [{ kind: 'genotype', value: 'HETEROZYGOUS', valueType: 'ENUM', interpretation: 'CAPTURED_SOURCE_VALUE_NOT_CURRENT_ADOPTION' },
                { kind: 'examinationAge', value: 12, valueType: 'INTEGER', interpretation: 'CAPTURED_SOURCE_VALUE_NOT_CURRENT_ADOPTION' }],
            knownFactCount: 3, knownFactsProjection: projection(3, 2, 1), sourceLocatorCount: 1, sourceLocatorProjection: projection(1, 1),
            sourceLocators: [{ kind: 'JATS_UNIT', unitId: '/article/body[1]/sec[1]', sourceSha256: pin, pmcid: 'PMC123', bundleSha256: pin,
                bindingStatus: 'MATCHED_REGISTERED_SOURCE_UNIT', quote: 'PRIVATE_UNAPPROVED_QUOTE' }] }];
        const view = d.pp1ReadingDiagnostic(rr), html = api.renderView(view);
        assert.ok(html.includes(`家系标识 ${pin}`) && html.includes(`成员标识 ${other}`));
        assert.ok(html.includes('目标变异基因型记录：杂合') && html.includes('检查年龄记录：12'));
        assert.ok(html.includes('尚缺：符合本次疾病定义的临床状态依据') && html.includes('/article/body[1]/sec[1]'));
        assert.ok(!html.includes('PRIVATE_UNAPPROVED')); assert.ok(html.includes('1 项因记录类型或公开字段限制未提供'));
        assert.ok(!html.includes('<button'));
    });
    await check('public opaque scope IDs retain their original kind without assigning family/member roles', () => {
        const rr = reading(); rr.projection = { status: 'AVAILABLE', missingTotal: 1, missingOmitted: 0, missingInvalid: 0 };
        rr.missing = [{ reasonCode: 'PP1_CONTRIBUTING_SOURCE_SEMANTICS_UNRESOLVED', scope: [pin, other],
            scopeProjection: { status: 'AVAILABLE', kind: 'OPAQUE_ID_LIST' }, knownFacts: [], knownFactsProjection: { status: 'NOT_RECORDED', total: 0 } }];
        const view = d.pp1ReadingDiagnostic(rr), html = api.renderView(view);
        assert.deepEqual(view.missingDetails[0].scopes, [`范围标识 ${pin}`, `范围标识 ${other}`]);
        assert.ok(!html.includes('家系标识') && !html.includes('成员标识'));
    });
    await check('raw missing extras never acquire public rich status by adding requirement text alone', () => {
        const rr = reading(); rr.projection = { status: 'AVAILABLE', missingTotal: 1, missingOmitted: 0, missingInvalid: 0 };
        rr.missing = [{ reasonCode: 'NEED_INPUT', requirement: 'PRIVATE_RAW_REQUIREMENT', scope: { memberLabel: 'PRIVATE_RAW_MEMBER' },
            knownFacts: [{ kind: 'diagnosis', value: 'PRIVATE_RAW_FACT' }], sourceLocators: [{ quote: 'PRIVATE_RAW_QUOTE' }] }];
        assert.ok(!api.renderView(d.pp1ReadingDiagnostic(rr)).includes('PRIVATE_RAW'));
    });
    if (process.argv[4]) {
        const samplesPath = path.resolve(process.argv[4]), sampleRoot = path.dirname(samplesPath), samples = JSON.parse(fs.readFileSync(samplesPath));
        const replay = [];
        for (const [index, sample] of (samples.samples || []).entries()) {
            const descriptor = sample.publicFile || sample.actualPublicHttpFile, pointer = sample.jsonPointer || sample.factsJsonPointer;
            const scenario = sample.scenario || sample.criterion;
            if (!descriptor || !pointer) continue;
            await check(`actual normal-public ${scenario} sample ${index} visible diagnostics and source controls`, () => {
                const actualPath = path.resolve(sampleRoot, descriptor.path), relative = path.relative(sampleRoot, actualPath);
                assert.ok(!relative.startsWith('..') && !path.isAbsolute(relative));
                const raw = fs.readFileSync(actualPath), sha256 = require('node:crypto').createHash('sha256').update(raw).digest('hex');
                assert.equal(sha256, descriptor.sha256);
                let facts = JSON.parse(raw);
                for (const part of pointer.split('/').slice(1)) facts = facts[part.replace(/~1/g, '/').replace(/~0/g, '~')];
                for (const [field, exact] of Object.entries(sample.exactPublishedFields || {})) assert.deepEqual(facts[field], exact);
                const before = JSON.stringify(facts), isPp1 = scenario.startsWith('PP1');
                const html = isPp1 ? api.renderSavedPp1(facts) : api.renderSavedPm3(facts);
                if (isPp1) {
                    for (const row of facts.sourceCoverage || []) if (row.readingState?.projection?.status === 'AVAILABLE') {
                        const view = d.savedPp1ReadingDiagnostic(row, facts);
                        assert.ok(view && !view.invalidBinding && !view.withheld, 'normal PP1 capsule must remain readable');
                        assert.equal(d.savedPp1ReadingDiagnostic({ ...row, bindingPinKind: 'WORKER_RESULT_RAW_SHA256' }, facts).invalidBinding, true);
                        assert.equal(d.savedPp1ReadingDiagnostic({ ...row, bundleSha256: other }, facts).invalidBinding, true);
                        assert.equal(d.savedPp1ReadingDiagnostic({ ...row, capturedResultSha256: 'not-a-capture-pin' }, facts).invalidBinding, true);
                        assert.equal(d.savedPp1ReadingDiagnostic(row, { ...facts, evaluation: { ...facts.evaluation,
                            target: { ...facts.evaluation.target, geneSymbol: 'OTHER_GENE' } } }).invalidBinding, true);
                    }
                } else {
                    for (const row of facts.paperCoverage || []) if (row.processingCheckpoint?.projection?.status === 'AVAILABLE') {
                        const view = d.savedPm3CheckpointDiagnostic(row, facts);
                        assert.ok(view && !view.invalidBinding && !view.withheld, 'normal PM3 capsule must remain readable');
                        assert.equal(d.savedPm3CheckpointDiagnostic({ ...row, projection: { ...row.projection,
                            bindingPinKind: 'CAPTURED_PAPER_RESULT_CANONICAL_SHA256' } }, facts).invalidBinding, true);
                        assert.equal(d.savedPm3CheckpointDiagnostic(row, { ...facts, sourceSnapshotSha256: other }).invalidBinding, true);
                        assert.equal(d.savedPm3CheckpointDiagnostic({ ...row, pmcid: 'PMC99999' }, facts).invalidBinding, true);
                        if (facts.sourceTaskReferences?.length) assert.equal(d.savedPm3CheckpointDiagnostic({ ...row,
                            capturedResultSha256: other }, facts).invalidBinding, true);
                    }
                    if (!(facts.paperCoverage || []).length && (facts.taskCoverage || []).some((row) => row.acquisitionRecovery?.length))
                        assert.equal(depthAt(html, '资料获取尚未完成'), 0, 'no-paper acquisition remains visible outside details');
                }
                assert.equal(depthAt(html, '本结果记录的资料处理与阻断'), 0);
                assert.ok(!html.includes('<button')); assert.equal(JSON.stringify(facts), before);
                const output = `normal-public-sample-${index}.html`;
                fs.writeFileSync(path.join(path.dirname(process.argv[2]), output), html);
                replay.push({ scenario, sourcePath: actualPath, sourceSha256: sha256,
                    field: pointer, renderedFile: output, scientificSourceClass: 'SYNTHETIC_TEST_ONLY' });
            });
        }
        assert.ok(replay.length, 'Require actual normal public export samples, not handwritten diagnostic objects');
        const controlReplay = [];
        await check('actual archived Controller PP1 discovery responses use the new real mode-specific controls', () => {
            const file = path.join(sampleRoot, 'final-public/pp1/discovery-http/actual-task-responses.json');
            const raw = fs.readFileSync(file), tasks = JSON.parse(raw);
            assert.equal(tasks.length, 3);
            for (const task of tasks) {
                api.validatePp1Task(task); const issue = api.pp1ContinuationIssue(task);
                assert.equal(issue === null, task.canContinueDiscovery === true);
                assert.equal(task.canContinueProcessing, false, 'paper permission cannot substitute for actual discovery permission');
            }
            controlReplay.push({ sourcePath: file, sourceSha256: require('node:crypto').createHash('sha256').update(raw).digest('hex'),
                responseCount: tasks.length, scope: 'ARCHIVED_ACTUAL_CONTROLLER_RESPONSE_REPLAY_NO_NEW_HTTP' });
        });
        await check('actual archived Controller PM3 failure responses stay blocked despite cached successful work', () => {
            for (const scenario of ['IO', 'TIMEOUT', 'EXIT']) {
                const file = path.join(sampleRoot, 'actual-http', scenario, 'actual-blocked-task.json'), raw = fs.readFileSync(file), task = JSON.parse(raw);
                api.validatePm3Task(task); assert.equal(api.pm3ContinuationState(task).allowed, false);
                assert.ok(task.sharedBudgetReconciliationRequired); assert.match(api.pm3ContinuationState(task).reason, /核对/);
                controlReplay.push({ sourcePath: file, sourceSha256: require('node:crypto').createHash('sha256').update(raw).digest('hex'),
                    responseCount: 1, scope: 'ARCHIVED_ACTUAL_CONTROLLER_RESPONSE_REPLAY_NO_NEW_HTTP' });
            }
        });
        fs.writeFileSync(path.join(path.dirname(process.argv[2]), 'normal-public-replay.json'), JSON.stringify({
            scope: 'ACTUAL_PUBLIC_EXPORT_REACT_SSR_NOT_BROWSER_NEW_SCIENCE_OR_HTTP_EXECUTION', sourceSamples: samplesPath, replay, controlReplay }, null, 2));
    }
    if (process.argv[3]) {
        const raw = fs.readFileSync(process.argv[3]), queue = [JSON.parse(raw)], selected = [], seen = new Set();
        for (let index = 0; index < queue.length && index < 100000 && selected.length < 4; index++) {
            const value = queue[index];
            if (!value || typeof value !== 'object') continue;
            if (value.schemaVersion === 'pp1-selected-facts-v1' && Array.isArray(value.evaluation?.needs)) {
                for (const need of value.evaluation.needs) if (Array.isArray(need.knownFacts) && need.knownFacts.length) {
                    const key = JSON.stringify([need.reasonCode, need.scope, need.knownFacts]);
                    if (!seen.has(key)) { selected.push(need); seen.add(key); }
                    if (selected.length === 4) break;
                }
            }
            queue.push(...(Array.isArray(value) ? value : Object.values(value)).filter((item) => item && typeof item === 'object'));
        }
        assert.ok(selected.length, 'Existing public export must supply normal published PP1 evaluation.needs');
        const artifact = { scope: 'ACTUAL_SAVED_SYNTHETIC_SCIENCE_PUBLIC_PP1_EVALUATION_NEEDS_REACT_SSR_NOT_BROWSER',
            sourcePath: path.resolve(process.argv[3]), sourceSha256: require('node:crypto').createHash('sha256').update(raw).digest('hex'),
            sourceField: 'criterion.details.pp1Facts.evaluation.needs', views: selected.map(d.evaluationNeedDiagnostic) };
        const destination = path.dirname(process.argv[2]);
        fs.writeFileSync(path.join(destination, 'existing-needs-display.json'), JSON.stringify(artifact, null, 2));
        fs.writeFileSync(path.join(destination, 'existing-needs-display.html'), api.renderNeeds(selected));
    }
    if (process.argv[2]) fs.writeFileSync(process.argv[2], JSON.stringify({ scope: 'SYNTHETIC_CONTRACT_AND_REACT_SSR_NOT_HTTP_OR_SCIENCE', tests: checks }, null, 2));
    console.log(`${checks.length} T02 literature diagnostic and current-control checks passed`);
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
