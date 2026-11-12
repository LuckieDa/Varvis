import fs from 'node:fs';
import path from 'node:path';

// Actual React DOM lifecycle tests with controlled service promises, separate from
// the archive HTTP replay and the three user-facing @Browser submissions.
export async function buildBp2ComponentChecks({ project, output, fixtures, build }) {
    const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
    const statePath = path.join(output, 'component-service.js');
    fs.writeFileSync(statePath, `export const api={};
export const fetchAcmgInputOptions=(...a)=>api.options(...a);
export const fetchAcmgBusinessRuns=(...a)=>api.list(...a);
export const fetchAcmgBusinessRun=(...a)=>api.read(...a);
export const submitAcmgBusinessRun=(...a)=>api.submit(...a);
export const functionalReviewRequest=()=>{throw new Error('FORBIDDEN_FUNCTIONAL_REVIEW');};`);
    const entry = `import React from 'react';import {createRoot} from 'react-dom/client';import Panel,{SavedResult} from ${JSON.stringify(component)};import {api} from ${JSON.stringify(statePath)};
const fixtures=${JSON.stringify(fixtures)};
(${runChecks.toString()})(React,createRoot,Panel,SavedResult,api,fixtures);`;
    fs.writeFileSync(path.join(output, 'component-checks-entry.jsx'), entry);
    await build({ stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'browser', format: 'iife', outfile: path.join(output, 'component-checks.js'),
        loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning', plugins: [{ name: 'controlled-component-service', setup(b) {
            b.onResolve({ filter: /services\/variantAnalysis$/ }, () => ({ path: statePath }));
            b.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, () => ({ contents: fs.readFileSync(component, 'utf8') + '\nexport {SavedResult};', loader: 'jsx', resolveDir: path.dirname(component) }));
        } }] });
}

function runChecks(React, createRoot, Panel, SavedResult, api, fixtures) {
    const result = document.createElement('pre'), trigger = document.createElement('button'), host = document.createElement('div');
    trigger.textContent = '运行真实 React 组件有限回归'; document.getElementById('root').append(trigger, result, host);
    let root, calls, configuration;
    const report = { status: 'RUNNING', attribution: 'ACTUAL_REACT_DOM_CONTROLLED_SERVICE_TESTS_NOT_BROWSER_U1_U4', cases: [], realDownloads: 0, scientificExecution: 0 };
    const ensure = (v, message) => { if (!v) throw new Error(message); };
    const equal = (a, b, message) => ensure(JSON.stringify(a) === JSON.stringify(b), message);
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const until = async predicate => { for (let i = 0; i < 150; i++) { if (predicate()) return; await wait(20); } throw new Error('Component wait timeout: ' + host.textContent.slice(0, 1000)); };
    const settle = async () => { await wait(70); await until(() => !host.querySelector('[role="progressbar"]')); await wait(20); };
    const button = text => [...host.querySelectorAll('button')].find(b => b.textContent === text);
    const confirm = () => host.querySelector('input[type="checkbox"]');
    const submit = () => button('确认后执行并保存');
    const retry = () => button('修正后显式重试此运行');
    const fixture = id => Object.values(fixtures).find(f => f.saved.analysisId === id);
    const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
    const alpha = fixtures.NORMAL_CIS_AR, unknown = fixtures.NE_AGE, failed = fixtures.FAILED_RETRY_CIS_AR;
    const legacy = (id, n) => ({ analysisId: id, requestedN: n, selectedCount: 0, candidateIds: [], candidates: [], enabledCriteria: ['ACMG_AMP_2015:BA1'],
        executionProfile: { contextPreparation: 'POPULATION_CONTEXT_ONLY_V1', enabledCriteria: ['ACMG_AMP_2015:BA1'] } });
    const change = id => root.render(React.createElement(Panel, { analysisId: id }));
    const reset = async (id = alpha.saved.analysisId, config = {}) => {
        if (root) root.unmount(); host.replaceChildren(); calls = []; configuration = config;
        api.options = async (analysisId, n) => { calls.push({ method: 'OPTIONS', analysisId, n }); return configuration.options ? configuration.options(analysisId, n) : fixture(analysisId).options; };
        api.list = async analysisId => { calls.push({ method: 'LIST', analysisId }); return configuration.rows?.(analysisId) || []; };
        api.read = async (analysisId, runId) => { calls.push({ method: 'READ', analysisId, runId }); return configuration.read?.(analysisId, runId) || fixture(analysisId).saved; };
        api.submit = async (analysisId, body) => { calls.push({ method: 'POST', analysisId, body }); return configuration.submit ? configuration.submit(analysisId, body) : fixture(analysisId).saved; };
        root = createRoot(host); change(id); await wait(40);
    };
    const record = async (name, fn) => { await fn(); report.cases.push({ name, status: 'PASS', calls: structuredClone(calls) }); result.textContent = JSON.stringify({ status: 'RUNNING', passed: report.cases.length }, null, 2); };
    trigger.onclick = async () => {
        trigger.disabled = true;
        try {
            await record('BP2 without candidates, fixed fields, unconfirmed disabled, one confirmed POST and double-click lock', async () => {
                const slow = deferred(); await reset(alpha.saved.analysisId, { submit: () => slow.promise }); await settle();
                equal(calls.filter(c => c.method === 'OPTIONS').map(c => c.n), [1], 'BP2 must only GET1');
                ensure(submit()?.disabled && retry()?.disabled, 'unconfirmed disabled');
                ensure(!host.textContent.includes('疾病关系及转录本') && !host.textContent.includes('输入数据类别'), 'no old choices');
                confirm().click(); await wait(30); ensure(!submit().disabled, 'explicit confirm enables');
                submit().click(); submit().click(); await wait(30); ensure(calls.filter(c => c.method === 'POST').length === 1 && submit().disabled, 'single pending POST');
                slow.resolve(alpha.saved); await settle(); ensure(host.textContent.includes('SUPPORTING'), 'saved result displayed');
                equal(calls.find(c => c.method === 'POST').body.bp2Selection, alpha.request.bp2Selection, 'exact selection');
            });
            await record('UNKNOWN remains eligible and renders minimal NE without fabricated strength', async () => {
                await reset(unknown.saved.analysisId); await settle(); confirm().click(); await wait(25); submit().click(); await settle();
                ensure(host.textContent.includes('实际强度：未记录') && host.textContent.includes('BP2_PENETRANCE_AGE_NOT_SATISFIED'), 'minimal NE');
                ensure(!host.textContent.includes('实际强度：NONE'), 'no fabricated strength');
            });
            await record('late POST never overwrites switched analysis or confirmation', async () => {
                const slow = deferred(); await reset(unknown.saved.analysisId, { submit: () => slow.promise }); await settle(); confirm().click(); await wait(25); submit().click(); await wait(25);
                change(alpha.saved.analysisId); await settle(); slow.resolve(unknown.saved); await wait(50);
                ensure(host.textContent.includes(alpha.options.bp2Preparation.materialBinding.context.disease.label), 'current analysis');
                ensure(!confirm().checked && !host.textContent.includes(unknown.saved.runId), 'old POST ignored');
                ensure(calls.filter(c => c.method === 'POST').length === 1, 'no extra POST');
            });
            await record('options503 revokes confirmation while history remains readable', async () => {
                await reset(unknown.saved.analysisId, { rows: () => [unknown.saved] }); await settle(); confirm().click(); await wait(25);
                configuration.options = () => { throw Object.assign(new Error('TEST_ONLY_503'), { response: { status: 503 } }); };
                button('读取正式候选').click(); await settle(); ensure(!submit() && !confirm(), 'permission removed');
                ensure(host.textContent.includes(unknown.saved.runId) && host.textContent.includes('实际强度：未记录'), 'history survives');
                ensure(calls.every(c => c.method !== 'POST'), 'read only');
            });
            await record('FAILED slim GET supports explicit new-confirmed retry with exact retryOf', async () => {
                await reset(failed.saved.analysisId, { rows: () => [failed.failed], read: () => failed.failed }); await settle();
                ensure(retry().disabled, 'history alone cannot retry'); confirm().click(); await wait(25); ensure(!retry().disabled, 'fresh confirm'); retry().click(); await settle();
                const posts = calls.filter(c => c.method === 'POST'); ensure(posts.length === 1 && posts[0].body.retryOf === failed.failed.runId, 'explicit retry identity');
                ensure(host.textContent.includes(failed.saved.runId) && retry().disabled, 'new completed run not retryable');
            });
            for (const status of ['RUNNING', 'COMPLETED', 'COMPLETED_WITH_BLOCKERS']) await record('actual retry button gate ' + status, async () => {
                const row = { ...failed.failed, status }; await reset(failed.saved.analysisId, { rows: () => [row], read: () => row }); await settle();
                if (!confirm().disabled) { confirm().click(); await wait(25); }
                ensure(retry().disabled === (status !== 'COMPLETED_WITH_BLOCKERS'), 'terminal eligibility'); ensure(calls.every(c => c.method !== 'POST'), 'no automatic retry');
            });
            await record('uncertain submission only refreshes reads and stays locked', async () => {
                await reset(alpha.saved.analysisId, { submit: () => { throw Object.assign(new Error('timeout'), { code: 'ETIMEDOUT' }); } }); await settle();
                confirm().click(); await wait(25); submit().click(); await settle(); ensure(host.textContent.includes('提交状态待确认') && submit().disabled, 'uncertain locked');
                button('刷新运行记录').click(); await settle(); ensure(calls.filter(c => c.method === 'POST').length === 1, 'no automatic resubmit');
            });
            await record('legacy discovery GET1 then GET20 and no provisional submit', async () => {
                const slow = deferred(); await reset('legacy', { options: (id, n) => n === 1 ? legacy(id, n) : slow.promise }); await wait(40);
                equal(calls.filter(c => c.method === 'OPTIONS').map(c => c.n), [1, 20], 'two phases'); ensure(!submit(), 'GET1 is not permission');
                slow.resolve(legacy('legacy', 20)); await settle(); ensure(host.textContent.includes('已读取 Top-20'), 'old default retained');
            });
            for (const kind of ['GET1 error', 'unknown scope', 'partial BP2', 'mixed BP2']) await record('discovery stops on ' + kind, async () => {
                await reset(alpha.saved.analysisId, { options: () => {
                    if (kind === 'GET1 error') throw new Error('TEST_ONLY_DISCOVERY_FAILED');
                    if (kind === 'unknown scope') return { ...legacy(alpha.saved.analysisId, 1), executionProfile: {} };
                    const o = structuredClone(alpha.options); if (kind === 'partial BP2') delete o.bp2Preparation; else o.candidates = []; return o;
                } }); await settle(); ensure(!submit(), 'no permission'); equal(calls.filter(c => c.method === 'OPTIONS').map(c => c.n), [1], 'no continuation');
            });
            await record('GET20 failure discards GET1 and does not retain permission', async () => {
                await reset('legacy', { options: (id, n) => { if (n === 20) throw new Error('TEST_ONLY_GET20_FAILED'); return legacy(id, n); } }); await settle(); ensure(!submit(), 'no partial authority');
            });
            for (const stage of [1, 20]) await record('late discovery GET' + stage + ' cannot touch new state', async () => {
                const slow = deferred(); await reset('legacy', { options: (id, n) => id === 'legacy' ? n === stage ? slow.promise : legacy(id, n) : fixture(id).options });
                await until(() => calls.some(c => c.method === 'OPTIONS' && c.n === stage)); change(unknown.saved.analysisId); await settle();
                confirm().click(); await wait(25); slow.resolve(legacy('legacy', stage)); await wait(70);
                ensure(confirm().checked && !submit().disabled && host.textContent.includes(unknown.options.bp2Preparation.materialBinding.context.disease.label), 'late response preserves new confirmation/busy/options');
                if (stage === 1) ensure(!calls.some(c => c.analysisId === 'legacy' && c.n === 20), 'old GET1 cannot start GET20');
            });
            await record('new options identity revokes existing confirmation', async () => {
                await reset(alpha.saved.analysisId); await settle(); confirm().click(); await wait(25); button('读取正式候选').click(); await settle(); ensure(!confirm().checked && submit().disabled, 'refresh requires confirmation');
            });
            await record('SavedResult full export serialization only, intercepted anchor, no actual download', async () => {
                root.unmount(); host.replaceChildren(); root = createRoot(host);
                root.render(React.createElement(SavedResult, { run: fixtures.NORMAL_TRANS_RESTRICTED.saved, criteria: [] })); await wait(80);
                ensure(host.textContent.includes('PATHOGENIC') && host.textContent.includes('SUPPORTING'), 'partner LP history');
                const create = URL.createObjectURL, revoke = URL.revokeObjectURL, click = HTMLAnchorElement.prototype.click;
                let blob, name, released = false;
                URL.createObjectURL = value => { blob = value; return 'blob:TEST_ONLY_NO_DOWNLOAD'; };
                URL.revokeObjectURL = value => { released = value === 'blob:TEST_ONLY_NO_DOWNLOAD'; };
                HTMLAnchorElement.prototype.click = function () { name = this.download; };
                try { button('下载本次原始结果 JSON').click(); ensure(name === 'acmg-' + fixtures.NORMAL_TRANS_RESTRICTED.saved.runId + '.json', 'original filename'); equal(JSON.parse(await blob.text()), fixtures.NORMAL_TRANS_RESTRICTED.saved, 'entire JSON serialization'); await wait(1100); ensure(released, 'URL released'); }
                finally { URL.createObjectURL = create; URL.revokeObjectURL = revoke; HTMLAnchorElement.prototype.click = click; }
            });
            await record('UNKNOWN current outer preparation stays eligible, opaque raw source is not reviewed by UI', async () => {
                const f=fixtures.NE_AGE; await reset(f.saved.analysisId,{rows:()=>[f.saved]});await settle();
                ensure(confirm() && submit().disabled,'valid outer identity');confirm().click();await wait(25);ensure(!submit().disabled,'explicit selection remains possible');
                ensure(host.textContent.includes('BP2_PENETRANCE_AGE_NOT_SATISFIED') && host.textContent.includes('实际强度：未记录'),'saved minimal NE');ensure(calls.every(c=>c.method!=='POST'),'history only');
            });
            await record('saved BP2 history remains independent of current legacy scope',async()=>{
                const f=fixtures.NORMAL_TRANS_RESTRICTED;await reset(f.saved.analysisId,{options:(id,n)=>legacy(id,n),rows:()=>[f.saved]});await settle();
                ensure(host.textContent.includes('已读取 Top-20')&&host.textContent.includes('SUPPORTING')&&host.textContent.includes('伙伴'),'history uses saved identity');ensure(calls.every(c=>c.method!=='POST'),'history only');
            });
            for(const name of ['NORMAL_CIS_AR','NE_AGE','FAILED_RETRY_CIS_AR'])await record('full original export serialization '+name,async()=>{
                const f=fixtures[name];root.unmount();host.replaceChildren();root=createRoot(host);root.render(React.createElement(SavedResult,{run:f.saved,criteria:[]}));await wait(80);
                const create=URL.createObjectURL,revoke=URL.revokeObjectURL,click=HTMLAnchorElement.prototype.click;let blob,filename;
                URL.createObjectURL=v=>{blob=v;return 'blob:TEST_ONLY_SERIALIZATION';};URL.revokeObjectURL=()=>undefined;HTMLAnchorElement.prototype.click=function(){filename=this.download;};
                try{button('下载本次原始结果 JSON').click();ensure(filename==='acmg-'+f.saved.runId+'.json','filename');equal(JSON.parse(await blob.text()),f.saved,'full JSON');await wait(1100);}finally{URL.createObjectURL=create;URL.revokeObjectURL=revoke;HTMLAnchorElement.prototype.click=click;}
            });

            await record('published FT refusal revokes permission but preserves saved history',async()=>{
                await reset(unknown.saved.analysisId,{rows:()=>[unknown.saved]});await settle();confirm().click();await wait(25);
                configuration.options=()=>{throw Object.assign(new Error('BP2_PUBLISHED_SAMPLE_FILTER_FAILED'),{response:{status:400}});};
                button('读取正式候选').click();await settle();ensure(!submit()&&!confirm(),'source failure revokes');ensure(host.textContent.includes('BP2_PUBLISHED_SAMPLE_FILTER_FAILED')&&host.textContent.includes(unknown.saved.runId),'source error and history retained');ensure(calls.every(c=>c.method!=='POST'),'no execution');
            });
            for(const name of ['NORMAL_CIS_AD','NORMAL_TRANS_UNRESTRICTED','NE_OLD_V1_TRANS'])await record('remaining original readonly scene '+name,async()=>{
                const f=fixtures[name];await reset(f.saved.analysisId,{rows:()=>[f.saved]});await settle();ensure(confirm()&&submit().disabled,'valid unconfirmed material');ensure(host.textContent.includes(f.saved.runId),'own saved run');ensure(host.textContent.includes(name.startsWith('NE_')?'BP2_PENETRANCE_CONDITIONS_NOT_ESTABLISHED':'SUPPORTING'),'own outcome');ensure(calls.every(c=>c.method!=='POST'),'read only');
            });
            report.status = 'PASS';
        } catch (e) { report.status = 'FAIL'; report.error = e.stack; report.failedCalls = calls; }
        finally {
            if (root) root.unmount(); result.textContent = JSON.stringify(report, null, 2);
            await fetch('/__test/component-results', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(report) });
        }
    };
}
