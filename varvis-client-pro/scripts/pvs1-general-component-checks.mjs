import fs from 'node:fs';
import path from 'node:path';

export async function buildPvs1GeneralComponents({ project, output, fixtures, build }) {
    const panel = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
    const service = path.join(output, 'component-service.js');
    fs.writeFileSync(service, `export const api={};export const fetchAcmgInputOptions=(...a)=>api.options(...a);export const fetchAcmgBusinessRuns=(...a)=>api.list(...a);export const fetchAcmgBusinessRun=(...a)=>api.read(...a);export const submitAcmgBusinessRun=(...a)=>api.submit(...a);export const functionalReviewRequest=()=>{throw new Error('FORBIDDEN');};`);
    const entry = `import React from 'react';import {createRoot} from 'react-dom/client';import Panel from ${JSON.stringify(panel)};import {api} from ${JSON.stringify(service)};(${runChecks.toString()})(React,createRoot,Panel,api,${JSON.stringify(fixtures)});`;
    fs.writeFileSync(path.join(output, 'component-entry.jsx'), entry);
    await build({ stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'browser', format: 'iife', outfile: path.join(output, 'component-checks.js'), loader: { '.js': 'jsx' },
        define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning', plugins: [{ name: 'controlled-service', setup(b) {
            b.onResolve({ filter: /services\/variantAnalysis$/ }, () => ({ path: service }));
        } }] });
}

function runChecks(React, createRoot, Panel, api, fixtures) {
    const rootElement = document.getElementById('root');
    const trigger = document.createElement('button'), result = document.createElement('pre'), host = document.createElement('div');
    trigger.textContent = '运行 PVS1 真实组件验证'; rootElement.append(trigger, result, host);
    let root, config, calls;
    const report = { status: 'RUNNING', attribution: 'ACTUAL_REACT_CONTROLLED_PROMISES_TEST_ONLY_NOT_REAL_BUSINESS', cases: [], actualDownload: 'NOT_RUN', scientificExecutions: 0 };
    const wait = ms => new Promise(r => setTimeout(r, ms));
    const ensure = (condition, why) => { if (!condition) throw new Error(why); };
    const settle = async () => { await wait(100); for (let i = 0; i < 150; i++) { if (!host.querySelector('[role="progressbar"]')) { await wait(30); return; } await wait(20); } throw new Error('settle timeout'); };
    const button = text => [...host.querySelectorAll('button')].find(b => b.textContent === text);
    const confirm = () => host.querySelector('input[type="checkbox"]');
    const submit = () => button('确认后执行并保存');
    const top = () => host.querySelector('input:not([type="checkbox"]):not([aria-hidden="true"])');
    const changeInput = (input, value) => { Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, value); input.dispatchEvent(new Event('input', { bubbles: true })); };
    const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
    const fixture = id => Object.values(fixtures).find(f => f.saved.analysisId === id);
    // Discovery uses a synthetic transport projection of archived candidates.
    // Original files and scientific fields are never altered.
    const options = (id, n) => { const o = structuredClone(fixture(id).options); o.requestedN = n; o.candidates = o.candidates.slice(0, n); o.candidateIds = o.candidateIds.slice(0, n); o.selectedCount = o.candidates.length; return o; };
    const change = id => root.render(React.createElement(Panel, { analysisId: id }));
    const reset = async (f = fixtures.CURRENT, settings = {}) => {
        if (root) root.unmount(); host.replaceChildren(); config = settings; calls = [];
        api.options = async (id, n) => { calls.push({ method: 'OPTIONS', id, n }); return config.options ? config.options(id, n) : options(id, n); };
        api.list = async id => { calls.push({ method: 'LIST', id }); return config.rows?.(id) || []; };
        api.read = async (id, runId) => { calls.push({ method: 'READ', id, runId }); return config.read?.(id) || fixture(id).saved; };
        api.submit = async (id, body) => { calls.push({ method: 'MOCK_POST', id, body }); return config.submit ? config.submit(id, body) : structuredClone(fixture(id).saved); };
        root = createRoot(host); change(f.saved.analysisId); await settle();
    };
    const record = async (name, fn) => { await fn(); report.cases.push({ name, status: 'PASS', calls: structuredClone(calls) }); result.textContent = JSON.stringify({ passed: report.cases.length }); };
    trigger.onclick = async () => {
        trigger.disabled = true;
        try {
            await record('explicit confirmation, pending double-click lock, saved branch and new confirmation required', async () => {
                const slow = deferred(); await reset(fixtures.CURRENT, { submit: () => slow.promise });
                ensure(submit()?.disabled, 'unconfirmed disabled'); confirm().click(); await wait(30); ensure(!submit().disabled, 'confirmed enables');
                submit().click(); submit().click(); await wait(30); ensure(calls.filter(c => c.method === 'MOCK_POST').length === 1, 'one mock submission');
                slow.resolve(fixtures.CURRENT.saved); await settle(); ensure(host.textContent.includes('8 / 2') && host.textContent.includes('STRONG'), 'actual escape facts');
                ensure(!confirm().checked && submit().disabled, 'new saved response revokes confirmation');
            });
            await record('Top-N edit revokes and must reload', async () => {
                await reset(); confirm().click(); await wait(25); changeInput(top(), '3'); await wait(40);
                ensure(!confirm().checked && submit().disabled, 'Top-N revoked'); button('读取正式候选').click(); await settle();
                ensure(!confirm().checked && submit().disabled && host.textContent.includes('Top-3'), 'reload not approval');
            });
            await record('refresh and unsupported algorithm revoke permission', async () => {
                await reset(); confirm().click(); await wait(25);
                config.options = (id, n) => { const o = options(id, n); o.executionProfile.reviewedRuleBinding.algorithms['transcript-truncating-nmd-pvs1'] = '9'; return o; };
                button('读取正式候选').click(); await settle(); ensure(!confirm() && !submit(), 'unsupported removes authority');
                ensure(calls.every(c => c.method !== 'MOCK_POST'), 'read only');
            });
            await record('corrupt saved result revokes a previously confirmed draft but keeps original export', async () => {
                await reset(); confirm().click(); await wait(25); ensure(!submit().disabled, 'ready before load');
                const bad = structuredClone(fixtures.CURRENT.saved);
                bad.candidates[1].attempts[0].pvs1.evaluation.appliedStrength = 'VERY_STRONG';
                config.rows = () => [bad]; config.read = () => bad; button('读取保存结果').click(); await settle();
                ensure(!confirm() && !submit(), 'confirmation removed'); ensure(host.textContent.includes('保存的 PVS1'), 'warning');
                ensure(!host.textContent.includes('实际强度：') && button('下载本次原始结果 JSON'), 'no trusted result but export retained');
                ensure(calls.every(c => c.method !== 'MOCK_POST'), 'zero mock POST');
            });
            await record('current unavailable history remains readable without executing', async () => {
                await reset(fixtures.CURRENT, { rows: () => [fixtures.CURRENT.saved], options: () => { throw new Error('TEST_ONLY_503'); } });
                ensure(!confirm() && !submit(), 'no current authority'); ensure(host.textContent.includes('8 / 2') && host.textContent.includes('当前保存政策未启用'), 'archived facts');
                ensure(button('下载本次原始结果 JSON') && calls.every(c => c.method !== 'MOCK_POST'), 'original export and no submit');
            });
            await record('late submit response cannot replace another analysis', async () => {
                const slow = deferred(); await reset(fixtures.CURRENT, { submit: () => slow.promise }); confirm().click(); await wait(25); submit().click(); await wait(25);
                change(fixtures.LEGACY.saved.analysisId); await settle(); slow.resolve(fixtures.CURRENT.saved); await settle();
                ensure(!host.textContent.includes(fixtures.CURRENT.saved.runId) && !confirm().checked, 'old result ignored');
            });
            for (const n of [1, 20]) await record('late options ' + n + ' cannot replace another analysis', async () => {
                const slow = deferred();
                // Reset without awaiting the pending operation; other analysis resolves normally.
                const pending = reset(fixtures.CURRENT, { options: (id, count) => id === fixtures.CURRENT.saved.analysisId && count === n ? slow.promise : options(id, count) });
                await wait(100); change(fixtures.LEGACY.saved.analysisId); await settle(); confirm().click(); await wait(25);
                slow.resolve(options(fixtures.CURRENT.saved.analysisId, n)); await pending; await settle();
                ensure(confirm().checked && !submit().disabled && host.textContent.includes(fixtures.LEGACY.saved.analysisId), 'new confirmation preserved');
            });
            for (const name of ['LEGACY', 'POSITIONAL']) await record('saved history compatible ' + name, async () => {
                const f = fixtures[name]; await reset(f, { rows: () => [f.saved] });
                ensure(host.textContent.includes(f.saved.runId) && host.textContent.includes('VERY_STRONG'), 'history rendered');
                ensure(submit().disabled && !confirm().checked, 'no historic authority'); ensure(calls.every(c => c.method !== 'MOCK_POST'), 'read only');
            });
            await record('explicit retry retains exact retryOf and requires reconfirmation', async () => {
                await reset(fixtures.CURRENT, { rows: () => [fixtures.CURRENT.saved] });
                const retry = button('修正后显式重试此运行'); ensure(retry.disabled, 'not automatically retryable'); confirm().click(); await wait(25); retry.click(); await settle();
                const post = calls.find(c => c.method === 'MOCK_POST'); ensure(post?.body.retryOf === fixtures.CURRENT.saved.runId, 'retryOf exact');
                ensure(!confirm().checked, 'confirmation revoked');
            });
            report.status = 'PASS';
        } catch (e) { report.status = 'FAIL'; report.error = e.stack; report.failedCalls = calls; }
        finally { if (root) root.unmount(); result.textContent = JSON.stringify(report, null, 2); await fetch('/__test/component-results', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(report) }); }
    };
}
