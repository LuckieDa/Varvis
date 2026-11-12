const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const project = path.resolve(__dirname, '..');
const dependencyRoot = fs.existsSync(path.join(project, 'node_modules/esbuild')) ? project
    : process.env.VARVIS_READONLY_CLIENT_ROOT || path.resolve(project, '../../../../../varvis-client-pro');
const dependencyRequire = createRequire(path.join(dependencyRoot, 'package.json'));
const { buildSync } = dependencyRequire('esbuild');
const output = buildSync({ stdin: { contents: `
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import Panel,{readPm3Task,pm3ContinuationState,preparePm3Continuation,continuePm3Task} from './src/pages/varvis/ChromosomeVisualization/components/Pm3LiteraturePanel.jsx';
export {readPm3Task,pm3ContinuationState,preparePm3Continuation,continuePm3Task};
export const render=(props)=>renderToStaticMarkup(<Panel {...props}/>);
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', write: false,
    nodePaths: [path.join(dependencyRoot, 'node_modules')],
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' }).outputFiles[0].text;
const compiled = { exports: {} };
new Function('require', 'module', 'exports', output)(dependencyRequire, compiled, compiled.exports);
const api = compiled.exports;
const id = 'a'.repeat(64), pin = 'b'.repeat(64), digest = 'c'.repeat(64), analysisId = 'source-analysis';
const reference = { taskId: id, analysisId, selectionSha256: pin, capturedResultSha256: digest,
    bindingScope: 'GENE_AND_SELECTED_ALLELE_NOT_HYPOTHESIS_SPECIFIC' };
const task = { schemaVersion: 'pm3-public-literature-task-v1', taskId: id, status: 'READY',
    scientificExecution: false, modelCalls: null, resultSha256: digest, canContinueProcessing: true,
    processingAttempt: 1, maximumProcessingAttempts: 3,
    result: { schemaVersion: 'pm3-paper-process-v1', businessPublishable: false, scientificExecution: false,
        modelCalls: 0, selectionSha256: pin, pmcid: 'PMC123', readingLoop: {
            schemaVersion: 'pm3-reading-loop-v1', state: 'INCOMPLETE', fullPaperRead: false, roundCount: 1, rounds: [{}],
            feedback: { stopReason: 'PM3_READING_TIME_LIMIT', papers: [{ pmcid: 'PMC123', pendingRelevantChunks: [2] }] },
        } } };
const input = { analysisId, taskId: id, reference, topN: 5 };
const endpoint = `/variant-analyses/${analysisId}/acmg/literature/pm3/tasks/${id}`;
const clone = (value) => JSON.parse(JSON.stringify(value));
const options = { schemaVersion: 'pm3-literature-options-v1', selectionSha256: pin, enabled: true,
    selection: { candidates: [] } };
const mockClient = (value = task) => {
    const requests = [];
    return { requests, get: async (url) => { requests.push(['GET', url]); return { data: clone(url.endsWith('/options') ? options : value) }; },
        post: async (url, body) => { requests.push(['POST', url, body]); return { data: { ...clone(task), taskId: 'd'.repeat(64), status: 'QUEUED', result: undefined } }; } };
};
async function main() {
    let release;
    const deferred = new Promise((resolve) => { release = resolve; });
    const openingCalls = [];
    let resolved = false;
    const opening = api.readPm3Task(input, { get: async (url) => { openingCalls.push(['GET', url]); return deferred; },
        post: () => { throw new Error('Opening must not execute'); } }).then((value) => { resolved = true; return value; });
    assert.equal(resolved, false);
    assert.deepEqual(openingCalls, [['GET', endpoint]]);
    release({ data: clone(task) });
    assert.equal((await opening).taskId, id);
    const client = mockClient();
    await api.readPm3Task(input, client);
    await api.readPm3Task(input, client);
    assert.deepEqual(client.requests, [['GET', endpoint], ['GET', endpoint]], 'opening/refresh uses exact GET only');
    for (const wrong of [{ ...task, taskId: 'e'.repeat(64) }, { ...task, analysisId: 'different' }]) {
        await assert.rejects(api.readPm3Task(input, mockClient(wrong)));
    }
    for (const changed of [{ ...task, resultSha256: 'f'.repeat(64) },
        { ...task, result: { ...task.result, selectionSha256: 'f'.repeat(64) } }]) {
        const readable = await api.readPm3Task(input, mockClient(changed));
        assert.equal(readable.taskId, id, 'a changed snapshot remains readable');
        assert.equal(api.pm3ContinuationState(readable, reference).allowed, false, 'saved binding mismatch cannot continue');
        await assert.rejects(api.preparePm3Continuation(input, mockClient(changed)));
    }
    const wrongReference = mockClient();
    await assert.rejects(api.readPm3Task({ ...input, reference: { ...reference, analysisId: 'other' } }, wrongReference));
    assert.equal(wrongReference.requests.length, 0, 'invalid ownership rejected before network');
    await assert.rejects(api.readPm3Task({ ...input, reference: { ...reference, bindingScope: 'DISEASE_SPECIFIC' } }, mockClient()));
    const paperReference = { ...reference, pmcid: 'PMC999' };
    const differentPaper = await api.readPm3Task({ ...input, reference: paperReference }, mockClient());
    assert.equal(api.pm3ContinuationState(differentPaper, paperReference).allowed, false);
    assert.equal(api.pm3ContinuationState(task, reference).allowed, true);
    for (const stop of ['PM3_READING_NO_PROGRESS', 'PM3_RULE_UNSUPPORTED', 'PM3_NO_PENDING_PROCESSING_WORK']) {
        const stopped = clone(task); stopped.result.readingLoop.feedback.stopReason = stop;
        assert.equal(api.pm3ContinuationState(stopped, reference).allowed, true, 'current permission is not recomputed from historical stop text');
        stopped.canContinueProcessing = false; stopped.processingContinuationBlockedReason = stop;
        stopped.result.discoveryState = { resumeAvailable: true };
        assert.equal(api.pm3ContinuationState(stopped, reference).allowed, false, 'old resume hint cannot override a current refusal');
        stopped.result.pendingRegisteredPapers = ['PMC123'];
        assert.equal(api.pm3ContinuationState(stopped, reference).allowed, false);
        stopped.result.pendingRegisteredPapers = ['PMC999'];
        assert.equal(api.pm3ContinuationState(stopped, reference).allowed, false, 'different pending papers cannot substitute for current permission');
    }
    for (const state of ['DEPENDENCY_UNRESOLVED', 'CONFLICT_REQUIRES_REVIEW', 'SEARCHED_SCOPE_INSUFFICIENT']) {
        const blocked = clone(task); blocked.result.readingLoop.state = state;
        blocked.canContinueProcessing = false;
        assert.equal(api.pm3ContinuationState(blocked, reference).allowed, false);
    }
    const conflict = clone(task); conflict.result.readingLoop.feedback.sourceConflict = true; conflict.canContinueProcessing = false;
    assert.equal(api.pm3ContinuationState(conflict, reference).allowed, false);
    const noRemaining = clone(task); noRemaining.result.readingLoop.feedback.papers = [];
    assert.equal(api.pm3ContinuationState(noRemaining, reference).allowed, true, 'absence of legacy pending metadata is not a current refusal');
    assert.equal(api.pm3ContinuationState({ ...task, resultSha256: undefined }, reference).allowed, false);
    assert.equal(api.pm3ContinuationState({ ...task, status: 'RUNNING' }, reference).allowed, false);
    const searches = clone(noRemaining);
    searches.result.discoveryState = { resumeAvailable: true, pendingQueries: 1, pendingPapers: 0 };
    assert.equal(api.pm3ContinuationState(searches, reference).allowed, true);
    searches.result.discoveryState.pendingPapers = 1;
    assert.equal(api.pm3ContinuationState(searches, reference).allowed, true, 'only the server decides whether this work may continue');
    const preparedClient = mockClient();
    for (const topN of [undefined, null, true, '5', 0, -1, 1.5]) {
        const noScope = mockClient();
        await assert.rejects(api.preparePm3Continuation({ ...input, topN }, noScope));
        assert.deepEqual(noScope.requests, [], 'missing original scope never requests default options');
    }
    const prepared = await api.preparePm3Continuation(input, preparedClient);
    assert.deepEqual(preparedClient.requests.map((row) => row[0]), ['GET', 'GET']);
    await assert.rejects(api.continuePm3Task({ ...input, confirmation: prepared.confirmation, confirmed: false }, preparedClient));
    assert.equal(preparedClient.requests.length, 2);
    for (const patch of [{ analysisId: 'other' }, { taskId: 'e'.repeat(64) }, { topN: 20 }, { resultSha256: 'e'.repeat(64) }, { enabled: false }]) {
        const guarded = mockClient();
        await assert.rejects(api.continuePm3Task({ ...input, confirmation: { ...prepared.confirmation, ...patch }, confirmed: true }, guarded));
        assert.equal(guarded.requests.filter((row) => row[0] === 'POST').length, 0);
    }
    const refused = mockClient({ ...task, canContinueProcessing: false });
    await assert.rejects(api.continuePm3Task({ ...input, confirmation: prepared.confirmation, confirmed: true }, refused));
    assert.deepEqual(refused.requests, [['GET', endpoint]]);
    const closed = mockClient();
    await assert.rejects(api.continuePm3Task({ ...input, confirmation: prepared.confirmation, confirmed: true, isCurrent: () => false }, closed));
    assert.deepEqual(closed.requests, [['GET', endpoint]], 'closing the confirmation prevents a late POST');
    const execution = mockClient();
    await api.continuePm3Task({ ...input, confirmation: prepared.confirmation, confirmed: true }, execution);
    assert.deepEqual(execution.requests, [['GET', endpoint], ['POST', `${endpoint}/continue`, {
        topN: 5, selectionSha256: pin, confirmPublicModel: true }]]);
    const hintless = clone(task); delete hintless.result.readingLoop; delete hintless.result.discoveryState;
    delete hintless.result.processingCheckpoint; delete hintless.result.pendingRegisteredPapers;
    const permitted = mockClient(hintless), newScope = await api.preparePm3Continuation(input, permitted);
    assert.deepEqual(permitted.requests.map(row => row[0]), ['GET', 'GET']);
    const permittedExecution = mockClient(hintless);
    await api.continuePm3Task({ ...input, confirmation: newScope.confirmation, confirmed: true }, permittedExecution);
    assert.deepEqual(permittedExecution.requests, [['GET', endpoint], ['POST', `${endpoint}/continue`, {
        topN: 5, selectionSha256: pin, confirmPublicModel: true }]], 'explicit current permission uses the exact confirmed scope without legacy pending data');
    for (const changes of [{ canContinueProcessing: false }, { sharedBudgetReconciliationRequired: true }]) {
        const blocked = clone(task); Object.assign(blocked, changes); blocked.result.discoveryState = { resumeAvailable: true, pendingQueries: 1 };
        const blockedExecution = mockClient(blocked);
        await assert.rejects(api.continuePm3Task({ ...input, confirmation: newScope.confirmation, confirmed: true }, blockedExecution));
        assert.deepEqual(blockedExecution.requests, [['GET', endpoint]], 'current refusal or unknown accounting stops before POST despite positive old hints');
    }
    const mismatchedOptions = mockClient();
    mismatchedOptions.get = async (url) => ({ data: url.endsWith('/options') ? { ...options, selectionSha256: 'e'.repeat(64) } : clone(task) });
    await assert.rejects(api.preparePm3Continuation(input, mismatchedOptions));
    const legacy = api.render({ analysisId, topN: 5 });
    assert.ok(legacy.includes('准备 PM3 文献任务'), 'legacy panel retains normal creation flow');
    const linked = api.render({ analysisId, topN: 5, initialTaskId: id, initialTaskReference: reference, onReturn() { return undefined; } });
    assert.ok(!linked.includes('准备 PM3 文献任务'));
    assert.ok(linked.includes('返回证据详情'));
    assert.ok(linked.includes('不代表原疾病背景或某个假设专属'));
    const oldLinked = api.render({ analysisId, initialTaskReference: reference });
    assert.ok(oldLinked.includes('不会用默认 Top-N 代替原范围'));
    console.log('PM3 linked task contracts passed (synthetic async transport + SSR; no live task/model execution).');
}
main().catch((error) => { console.error(error); process.exitCode = 1; });
