// Synthetic frontend effect/contract harness and React SSR; no actual HTTP, model or browser.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const project = path.resolve(__dirname, '..');
const mainClient = path.resolve(project, '../../../../../varvis-client-pro');
const deps = createRequire(path.join(mainClient, 'package.json'));
const { build } = deps('esbuild');
const React = deps('react');
const { renderToStaticMarkup } = deps('react-dom/server');
const calls = [];
let getImpl = async () => { throw Error('Unexpected GET'); };
let postImpl = async () => { throw Error('Unexpected POST'); };
const axios = { get: (...args) => { calls.push({ method: 'GET', args }); return getImpl(...args); },
    post: (...args) => { calls.push({ method: 'POST', args }); return postImpl(...args); } };
const sha = c => c.repeat(64);
const task = () => ({ schemaVersion: 'pp1-public-literature-task-v1', taskId: sha('a'), status: 'READY',
    canContinueProcessing: false, processingAttempt: 1, maximumProcessingAttempts: 3,
    canContinueDiscovery: true, discoveryAttempt: 1, maximumDiscoveryAttempts: 3, result: {
    schemaVersion: 'pp1-discovery-process-v1', status: 'PARTIAL', businessPublishable: false, modelAttempts: 0,
    searchPolicy: 'LITVAR_ONLY', papers: [], artifactManifestSha256: sha('b'),
    discoveryState: { resumeAvailable: true, sha256: sha('c'), pendingPapers: 1, pendingQueries: 0 }, pendingRegisteredPapers: ['PMC456'] } });
const results = [];
const check = async (name, fn) => { await fn(); results.push({ name, status: 'PASS' }); };
function compile(text) { const m = { exports: {} }; new Function('require', 'module', 'exports', text)(name => name === 'axios' ? axios : deps(name), m, m.exports); return m.exports; }
function harness(Panel, props) {
    const states = [], refs = [], effects = [], queued = [], timers = new Map();
    let cursor, refCursor, effectCursor, tree, dirty = true, timerId = 0, currentProps = props;
    const hooks = {
        useState(initial) { const i = cursor++; if (!(i in states)) states[i] = typeof initial === 'function' ? initial() : initial;
            return [states[i], next => { const value = typeof next === 'function' ? next(states[i]) : next; if (!Object.is(states[i], value)) { states[i] = value; dirty = true; } }]; },
        useRef(initial) { const i = refCursor++; if (!(i in refs)) refs[i] = { current: initial }; return refs[i]; },
        useEffect(fn, dependencies) { const i = effectCursor++, prior = effects[i];
            if (!prior || dependencies.some((v, n) => !Object.is(v, prior.dependencies[n]))) {
                effects[i] = { dependencies, cleanup: prior?.cleanup }; queued.push(() => { effects[i].cleanup?.(); effects[i].cleanup = fn(); });
            } },
    };
    function render() { cursor = 0; refCursor = 0; effectCursor = 0; globalThis.__pp1Hooks = hooks; tree = Panel(currentProps); dirty = false; }
    function withTimers(fn) { const saveSet = global.setTimeout, saveClear = global.clearTimeout;
        global.setTimeout = cb => { timers.set(++timerId, cb); return timerId; }; global.clearTimeout = id => timers.delete(id);
        try { return fn(); } finally { global.setTimeout = saveSet; global.clearTimeout = saveClear; } }
    async function settle() { for (let i = 0; i < 14; i++) { if (dirty) render(); withTimers(() => { while (queued.length) queued.shift()(); }); await Promise.resolve(); } if (dirty) render(); }
    render();
    return { settle, html: () => renderToStaticMarkup(tree), find(predicate) { function walk(e) { if (!React.isValidElement(e)) return null; if (predicate(e)) return e; for (const child of React.Children.toArray(e.props.children)) { const hit = walk(child); if (hit) return hit; } return null; } return walk(tree); }, update(next) { currentProps = next; dirty = true; },
        async poll() { const cb = timers.values().next().value; assert.equal(typeof cb, 'function'); timers.clear();
            const saveSet = global.setTimeout, saveClear = global.clearTimeout; global.setTimeout = next => { timers.set(++timerId, next); return timerId; }; global.clearTimeout = id => timers.delete(id);
            try { await cb(); await settle(); } finally { global.setTimeout = saveSet; global.clearTimeout = saveClear; } },
        click(label) { function find(element) { if (!React.isValidElement(element)) return null; if (element.props.children === label && element.props.onClick) return element;
            for (const child of React.Children.toArray(element.props.children)) { const hit = find(child); if (hit) return hit; } return null; }
            const found = find(tree); assert.ok(found, label); return found.props.onClick(); },
        dispose() { withTimers(() => effects.forEach(e => e.cleanup?.())); delete globalThis.__pp1Hooks; } };
}
(async () => {
    const built = await build({ stdin: { contents: `export {default as Panel, readPp1Tasks, validatePp1Task, pp1ContinuationIssue, pp1CurrentContextIssue, preparePp1Continuation, Pp1ContinuationControl} from './src/pages/varvis/ChromosomeVisualization/components/Pp1LiteraturePanel.jsx';`, resolveDir: project },
        nodePaths: [path.join(mainClient, 'node_modules')], bundle: true, platform: 'node', format: 'cjs', write: false,
        external: ['react', 'react-dom', 'react-dom/server', 'axios'], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning',
        plugins: [{ name: 'panel-hook-observer', setup(b) { b.onLoad({ filter: /Pp1LiteraturePanel\.jsx$/ }, ({ path: filename }) => ({ loader: 'jsx', contents: fs.readFileSync(filename, 'utf8')
            .replace("import React, { useEffect, useRef, useState } from 'react';", "import React from 'react'; const useEffect=(...a)=>globalThis.__pp1Hooks.useEffect(...a), useRef=(...a)=>globalThis.__pp1Hooks.useRef(...a), useState=(...a)=>globalThis.__pp1Hooks.useState(...a);") })); } }] });
    const api = compile(built.outputFiles[0].text), id = sha('a'), analysisId = 'synthetic-analysis';
    const props = { analysisId, initialTaskId: id }, get = async url => { assert.equal(url, `/variant-analyses/${analysisId}/acmg/literature/pp1/tasks/${id}`); return { data: task() }; };
    await check('exact direct linked GET and immutable source', async () => { const value = task(), before = JSON.stringify(value), seen = [];
        const rows = await api.readPp1Tasks(props, async (...args) => { seen.push(args); return { data: value }; });
        assert.equal(rows[0], value); assert.equal(seen.length, 1); assert.equal(seen[0][0], `/variant-analyses/${analysisId}/acmg/literature/pp1/tasks/${id}`); assert.equal(JSON.stringify(value), before); });
    await check('normal list path preserved', async () => { const rows = await api.readPp1Tasks({ analysisId }, async url => { assert.ok(url.endsWith('/pp1/tasks')); return { data: [task()] }; }); assert.equal(rows.length, 1); });
    await check('empty list accepted', async () => assert.deepEqual(await api.readPp1Tasks({ analysisId }, async () => ({ data: [] })), []));
    for (const invalid of [{ analysisId, initialTaskId: '../other' }, { initialTaskId: id }]) await check('invalid identity never requests or falls back', async () => { let count = 0; await assert.rejects(api.readPp1Tasks(invalid, () => { count++; })); assert.equal(count, 0); });
    await check('wrong task rejected', async () => { const value = task(); value.taskId = sha('d'); await assert.rejects(api.readPp1Tasks(props, async () => ({ data: value }))); });
    await check('wrong explicit analysis rejected', async () => { const value = task(); value.analysisId = 'other'; await assert.rejects(api.readPp1Tasks(props, async () => ({ data: value }))); });
    await check('missing task or malformed result rejected', async () => { await assert.rejects(api.readPp1Tasks(props, async () => ({ data: null }))); const value = task(); value.result.businessPublishable = true; assert.throws(() => api.validatePp1Task(value)); });
    await check('read errors propagate', async () => assert.rejects(api.readPp1Tasks(props, async () => { throw Error('HTTP 503'); }), /503/));
    await check('live checkpoint eligible', () => assert.equal(api.pp1ContinuationIssue(task()), null));
    await check('per-paper capture hash is not parent result pin', async () => { const value = task(); value.capturedResultSha256 = sha('e'); assert.equal((await api.readPp1Tasks(props, async () => ({ data: value })))[0], value); });
    await check('nonready tasks stay read-only; current fixed-paper permission is honored', () => { for (const status of ['RUNNING', 'FAILED', 'CANCELLED']) assert.ok(api.pp1ContinuationIssue({ ...task(), status })); const value = task(); value.result.schemaVersion = 'pp1-paper-process-v1'; value.canContinueProcessing = true; delete value.canContinueDiscovery; assert.equal(api.pp1ContinuationIssue(value), null); });
    await check('current refusal wins even when old progress hints remain positive', () => { const value = task(); value.canContinueDiscovery = false; assert.ok(api.pp1ContinuationIssue(value)); });
    await check('legacy resume hints never substitute for missing current permission', () => { const value = task(); delete value.canContinueDiscovery; assert.ok(api.pp1ContinuationIssue(value)); const v = task(); delete v.discoveryAttempt; assert.ok(api.pp1ContinuationIssue(v)); });
    await check('current permission is not vetoed by an old no-progress annotation', () => { const value = task(); value.result.stopReason = 'READING_PLAN_NO_PROGRESS'; assert.equal(api.pp1ContinuationIssue(value), null); });
    await check('single paper knowledge/conflict/no-progress does not block other unread papers', () => { for (const stopReason of ['EXTERNAL_REQUIREMENT', 'SOURCE_REVIEW_REJECTED_PENDING', 'READING_PLAN_NO_PROGRESS']) { const value = task(); value.result.papers = [{ pmcid: 'PMC123', stopReason }]; assert.equal(api.pp1ContinuationIssue(value), null); } });
    await check('missing callback or draft identity is read only', () => { assert.ok(api.pp1CurrentContextIssue(undefined, () => ({}))); assert.ok(api.pp1CurrentContextIssue('identity', undefined)); });
    await check('missing declaration capability never fabricates approval or reads', async () => { let reads = 0; await assert.rejects(api.preparePp1Continuation({ analysisId, taskId: id }, () => { reads++; })); assert.equal(reads, 0); });
    await check('blocked live parent never invokes declaration builder', async () => { let declarations = 0; const value = task(); value.canContinueDiscovery = false;
        await assert.rejects(api.preparePp1Continuation({ analysisId, taskId: id, draftIdentity: 'current', prepareDeclaration() { declarations++; } }, async () => ({ data: value }))); assert.equal(declarations, 0); });
    await check('actual declaration reused unchanged without POST', async () => { const declaration = { hypotheses: [{ hypothesis: { candidateId: 'current-candidate', hypothesisId: 'current-background' } }] }, before = JSON.stringify(declaration);
        const prepared = await api.preparePp1Continuation({ analysisId, taskId: id, draftIdentity: 'current', prepareDeclaration: () => declaration }, get);
        assert.equal(prepared.declaration, declaration); assert.equal(JSON.stringify(declaration), before); });
    await check('empty actual declaration rejected', async () => assert.rejects(api.preparePp1Continuation({ analysisId, taskId: id, draftIdentity: 'current', prepareDeclaration: () => ({ hypotheses: [] }) }, get)));
    await check('SSR missing context hides continue and explains prerequisite', () => { const html = renderToStaticMarkup(React.createElement(api.Pp1ContinuationControl, { task: task(), explain: true })); assert.ok(html.includes('\u5f53\u524d\u4ec5\u67e5\u770b')); assert.ok(!html.includes('<button')); });
    await check('SSR valid context exposes explicit preparation only', () => { const html = renderToStaticMarkup(React.createElement(api.Pp1ContinuationControl, { task: task(), draftIdentity: 'current', prepareDeclaration: () => ({}), explain: true })); assert.ok(html.includes('\u51c6\u5907\u7ee7\u7eed PP1')); assert.ok(!html.includes('checked=""')); });
    await check('linked mount GET only and loading to task without auto POST', async () => { calls.length = 0; let resolve; getImpl = () => new Promise(r => { resolve = r; }); const h = harness(api.Panel, props);
        assert.ok(h.html().includes('\u53ea\u8bfb\u52a0\u8f7d')); await h.settle(); assert.equal(calls.length, 1); assert.ok(calls[0].args[0].endsWith(`/tasks/${id}`)); resolve({ data: task() }); await h.settle(); const html = h.html();
        assert.ok(html.includes(id.slice(0, 12))); assert.ok(!html.includes('\u51c6\u5907 PP1 \u6587\u732e\u4efb\u52a1')); assert.ok(html.includes('\u5c1a\u65e0\u672c\u6b21\u6709\u6548')); assert.ok(!html.includes('\u51c6\u5907\u7ee7\u7eed PP1')); assert.equal(calls.filter(c => c.method === 'POST').length, 0); h.dispose(); });
    await check('failed read shows cause and refresh only GET', async () => { calls.length = 0; getImpl = async () => { throw Error('synthetic HTTP 404'); }; const h = harness(api.Panel, props); await h.settle(); assert.ok(h.html().includes('404'));
        getImpl = get; await h.click('\u53ea\u8bfb\u5237\u65b0 PP1 \u4efb\u52a1'); await h.settle(); assert.ok(h.html().includes(id.slice(0, 12))); assert.equal(calls.length, 2); assert.ok(calls.every(c => c.method === 'GET')); h.dispose(); });
    await check('pending polling never lists or creates task', async () => { calls.length = 0; getImpl = async () => ({ data: { schemaVersion: 'pp1-public-literature-task-v1', taskId: id, status: 'RUNNING' } }); const h = harness(api.Panel, props); await h.settle(); await h.poll();
        assert.equal(calls.length, 2); assert.ok(calls.every(c => c.method === 'GET' && c.args[0].endsWith(`/tasks/${id}`))); assert.ok(!h.html().includes('\u505c\u6b62 PP1')); h.dispose(); });
    await check('normal empty UI retains creation path and explicit empty state', async () => { calls.length = 0; getImpl = async () => ({ data: [] }); const h = harness(api.Panel, { analysisId, draftIdentity: 'current', prepareDeclaration: () => ({ hypotheses: [] }) }); await h.settle(); assert.ok(h.html().includes('\u5c1a\u65e0 PP1')); assert.ok(h.html().includes('\u51c6\u5907 PP1')); assert.ok(calls.every(c => c.method === 'GET')); h.dispose(); });
    await check('wrong task is never rendered', async () => { getImpl = async () => ({ data: { ...task(), taskId: sha('d') } }); const h = harness(api.Panel, props); await h.settle(); assert.ok(h.html().includes('\u4e0d\u4e00\u81f4')); assert.ok(!h.html().includes(sha('d').slice(0, 12))); h.dispose(); });
    await check('identity change discards stale response', async () => { let oldResolve; getImpl = () => new Promise(resolve => { oldResolve = resolve; }); const h = harness(api.Panel, props); await h.settle(); const nextId = sha('e'); getImpl = async () => ({ data: { ...task(), taskId: nextId } }); h.update({ analysisId, initialTaskId: nextId }); await h.settle(); oldResolve({ data: task() }); await h.settle(); assert.ok(h.html().includes(nextId.slice(0, 12))); assert.ok(!h.html().includes(id.slice(0, 12))); h.dispose(); });
    await check('explicit return does not write', async () => { calls.length = 0; getImpl = get; let returns = 0; const h = harness(api.Panel, { ...props, onReturn: () => { returns++; } }); await h.settle(); const before = calls.length; h.click('\u8fd4\u56de\u8bc1\u636e\u8be6\u60c5'); assert.equal(returns, 1); assert.equal(calls.length, before); h.dispose(); });
    await check('explicit prepare and checkbox required before single continuation POST; then read child task', async () => {
        calls.length = 0; getImpl = get; const declaration = { hypotheses: [{ hypothesis: { candidateId: 'candidate-current', hypothesisId: 'background-current' } }] };
        const h = harness(api.Panel, { ...props, draftIdentity: 'current', prepareDeclaration: () => declaration }); await h.settle();
        await h.find(e => typeof e.props.onPrepare === 'function').props.onPrepare(task()); await h.settle();
        assert.equal(calls.filter(c => c.method === 'POST').length, 0);
        await h.click('启动 PP1 资料任务'); await h.settle(); assert.equal(calls.filter(c => c.method === 'POST').length, 0);
        h.find(e => e.props.control?.props?.onChange).props.control.props.onChange({ target: { checked: true } }); await h.settle();
        const child = { schemaVersion: 'pp1-public-literature-task-v1', taskId: sha('f'), status: 'QUEUED' };
        postImpl = async () => ({ data: child }); await h.click('启动 PP1 资料任务'); await h.settle();
        const posts = calls.filter(c => c.method === 'POST'); assert.equal(posts.length, 1); assert.equal(posts[0].args[1].parentTaskId, id);
        assert.equal(posts[0].args[1].declaration, declaration); assert.equal(posts[0].args[1].confirmPublicModel, true); assert.ok(h.html().includes(sha('f').slice(0, 12)));
        getImpl = async url => { assert.ok(url.endsWith(`/tasks/${child.taskId}`)); return { data: child }; };
        await h.click('只读刷新 PP1 任务'); await h.settle(); assert.equal(calls.filter(c => c.method === 'POST').length, 1); h.dispose();
    });
    await check('current parent permission revoked after preparation: no POST or ambiguity flag', async () => {
        calls.length = 0; getImpl = get; const h = harness(api.Panel, { ...props, draftIdentity: 'current', prepareDeclaration: () => ({ hypotheses: [{ hypothesis: { candidateId: 'c', hypothesisId: 'h' } }] }) });
        await h.settle(); await h.find(e => typeof e.props.onPrepare === 'function').props.onPrepare(task()); await h.settle();
        h.find(e => e.props.control?.props?.onChange).props.control.props.onChange({ target: { checked: true } }); await h.settle();
        getImpl = async () => { const value = task(); value.canContinueDiscovery = false; return { data: value }; };
        await h.click('启动 PP1 资料任务'); await h.settle(); assert.equal(calls.filter(c => c.method === 'POST').length, 0); assert.ok(h.html().includes('尚未提交续读')); h.dispose();
    });
    await check('explicit live permission without old pending fields still sends one correctly bound confirmed request', async () => {
        const allowed = task(); delete allowed.result.discoveryState; delete allowed.result.artifactManifestSha256;
        allowed.result.stopReason = 'READING_PLAN_NO_PROGRESS';
        const declaration = { hypotheses: [{ hypothesis: { candidateId: 'current-candidate', hypothesisId: 'current-background' } }] };
        calls.length = 0; getImpl = async () => ({ data: allowed });
        const h = harness(api.Panel, { ...props, draftIdentity: 'current', prepareDeclaration: () => declaration }); await h.settle();
        await h.find(e => typeof e.props.onPrepare === 'function').props.onPrepare(allowed); await h.settle();
        assert.equal(calls.filter(c => c.method === 'POST').length, 0);
        h.find(e => e.props.control?.props?.onChange).props.control.props.onChange({ target: { checked: true } }); await h.settle();
        postImpl = async () => ({ data: { schemaVersion: 'pp1-public-literature-task-v1', taskId: sha('f'), status: 'QUEUED' } });
        await h.click('启动 PP1 资料任务'); await h.settle();
        const posts = calls.filter(c => c.method === 'POST'); assert.equal(posts.length, 1);
        assert.equal(posts[0].args[0], `/variant-analyses/${analysisId}/acmg/literature/pp1/tasks`);
        assert.deepEqual(posts[0].args[1], { declaration, candidateId: 'current-candidate', hypothesisId: 'current-background',
            confirmPublicModel: true, parentTaskId: id }); h.dispose();
    });
    await check('refused or unreconciled current permission never posts despite positive old resume hints', async () => {
        for (const changes of [{ canContinueDiscovery: false }, { sharedBudgetReconciliationRequired: true }]) {
            const blocked = { ...task(), ...changes }; let declarations = 0;
            calls.length = 0; getImpl = async () => ({ data: blocked });
            const h = harness(api.Panel, { ...props, draftIdentity: 'current', prepareDeclaration: () => { declarations++; return { hypotheses: [] }; } });
            await h.settle(); await h.find(e => typeof e.props.onPrepare === 'function').props.onPrepare(blocked); await h.settle();
            assert.equal(declarations, 0); assert.equal(calls.filter(c => c.method === 'POST').length, 0); h.dispose();
        }
    });
    if (process.argv[2]) fs.writeFileSync(process.argv[2], JSON.stringify({ scope: 'SYNTHETIC_CONTRACT_EFFECT_HARNESS_AND_REACT_SSR_NOT_ACTUAL_HTTP_BROWSER_OR_SCIENCE', tests: results }, null, 2));
    console.log(`${results.length} PP1 linked-task synthetic contract/effect/SSR checks passed`);
})().catch(error => { console.error(error); process.exitCode = 1; });
