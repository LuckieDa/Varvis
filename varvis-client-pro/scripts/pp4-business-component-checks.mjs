import fs from 'node:fs';
import path from 'node:path';

// Actual React DOM lifecycle tests with controlled service promises, separate from
// the archive HTTP replay and the three user-facing @Browser submissions.
export async function buildPp4ComponentChecks({ project, output, fixtures, build }) {
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
    const canonical = v => Array.isArray(v) ? v.map(canonical) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map(k => [k, canonical(v[k])])) : v;
    const equal = (a, b, message) => ensure(JSON.stringify(canonical(a)) === JSON.stringify(canonical(b)), message);
    const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
    const until = async predicate => { for (let i = 0; i < 150; i++) { if (predicate()) return; await wait(20); } throw new Error('Component wait timeout: ' + host.textContent.slice(0, 1000)); };
    const settle = async () => { await wait(70); await until(() => !host.querySelector('[role="progressbar"]')); await wait(20); };
    const button = text => [...host.querySelectorAll('button')].find(b => b.textContent === text);
    const confirm = () => host.querySelector('input[type="checkbox"]');
    const submit = () => button('确认后执行并保存');
    const retry = () => button('修正后显式重试此运行');
    const fixture = id => Object.values(fixtures).find(f => f.saved.analysisId === id);
    const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
    const alpha = fixtures.NORMAL_ALPHA, unknown = fixtures.NE_POSITIVE_ONLY, failed = fixtures.FAILED_RETRY;
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
            if (new URLSearchParams(location.search).get('r1') === '1') {
                await record('R1 inconsistent half-point report revokes an already confirmed draft', async () => {
                    const f = fixtures.HALF_2_5;
                    await reset(f.saved.analysisId); await settle(); confirm().click(); await wait(25);
                    ensure(!submit().disabled, 'confirmed before corrupt history arrives');
                    const bad = structuredClone(f.saved), core = bad.candidates[0].attempts[0].pp4;
                    core.rawHalfPoints = 6; bad.bundles[0].core = structuredClone(core);
                    configuration.rows = () => [bad]; configuration.read = () => bad;
                    button('读取保存结果').click(); await settle();
                    ensure(host.textContent.includes('保存的 PP4 身份不完整或相互冲突'), 'report conflict warning');
                    ensure(!confirm() && !submit(), 'current permission removed');
                    ensure(!host.textContent.includes('实际强度：'), 'trusted conclusion hidden');
                    ensure(button('下载本次原始结果 JSON'), 'original export retained without clicking');
                    ensure(calls.every(c => c.method !== 'POST'), 'zero mock POST');
                });
                await record('R1 NORMAL_ALPHA page two labels preserve true and false with complete source', async () => {
                    await reset(alpha.saved.analysisId, { rows: () => [alpha.saved] }); await settle();
                    const cohort = [...host.querySelectorAll('details')].find(d => d.querySelector('summary')?.textContent.startsWith('完整队列名单'));
                    cohort.open = true; button('队列下一页').click(); await wait(25);
                    const rows = [...cohort.querySelectorAll('details')], cases = alpha.saved.candidates[0].attempts[0].pp4.sourceBundle.observations[0].diagnosticYield.cases.slice(20, 40);
                    ensure(rows.length === 20 && cases.some(c => c.diagnosedInGene) && cases.some(c => c.diagnosedInGene === false), 'mixed page 21-40');
                    for (let i = 0; i < rows.length; i++) {
                        equal(rows[i].querySelector('summary').textContent, `病例 ${21 + i} · ${cases[i].caseId} · ${cases[i].diagnosedInGene ? '在该基因诊断' : '未在该基因诊断'}`, 'exact boolean label');
                        rows[i].open = true; await wait(15);
                        equal(JSON.parse(rows[i].querySelector('pre').textContent), cases[i], 'full original case');
                    }
                    ensure(calls.every(c => c.method !== 'POST'), 'zero mock POST');
                });
                report.status = 'PASS'; return;
            }
            {
                await record('R1 confirmed draft loses permission after corrupt saved content arrives', async () => {
                    await reset(alpha.saved.analysisId); await settle(); confirm().click(); await wait(25);
                    ensure(!submit().disabled, 'confirmed before history load');
                    const bad=structuredClone(alpha.saved),a=bad.candidates[0].attempts[0],c=a.pp4;
                    c.evaluation.appliedStrength='STRONG';c.criterionEvaluations=[structuredClone(c.evaluation)];a.criterionEvaluations=structuredClone(c.criterionEvaluations);bad.bundles[0].core=structuredClone(c);
                    configuration.rows=()=>[bad];configuration.read=()=>bad;
                    button('读取保存结果').click();await settle();
                    ensure(host.textContent.includes('保存的 PP4 身份不完整或相互冲突'),'content warning');
                    ensure(!confirm()&&!submit(),'current permission removed');
                    ensure(!host.textContent.includes('实际强度：'),'trusted conclusion hidden');
                    ensure(button('下载本次原始结果 JSON'),'original export retained without clicking');
                    ensure(calls.every(c=>c.method!=='POST'),'no business or mock POST');
                });
            }
            {
            await record('PP4 without candidates, fixed fields, unconfirmed disabled, one confirmed POST and double-click lock', async () => {
                const slow = deferred(); await reset(alpha.saved.analysisId, { submit: () => slow.promise }); await settle();
                equal(calls.filter(c => c.method === 'OPTIONS').map(c => c.n), [1], 'PP4 must only GET1');
                ensure(submit()?.disabled && retry()?.disabled, 'unconfirmed disabled');
                ensure(!host.textContent.includes('疾病关系及转录本') && !host.textContent.includes('输入数据类别'), 'no old choices');
                confirm().click(); await wait(30); ensure(!submit().disabled, 'explicit confirm enables');
                submit().click(); submit().click(); await wait(30); ensure(calls.filter(c => c.method === 'POST').length === 1 && submit().disabled, 'single pending POST');
                slow.resolve(alpha.saved); await settle(); ensure(host.textContent.includes('MODERATE'), 'saved result displayed');
                equal(calls.find(c => c.method === 'POST').body.pp4Selection, alpha.request.pp4Selection, 'exact selection');
            });
            await record('UNKNOWN remains eligible and renders minimal NE without fabricated strength', async () => {
                await reset(unknown.saved.analysisId); await settle(); confirm().click(); await wait(25); submit().click(); await settle();
                ensure(host.textContent.includes('实际强度：未记录') && host.textContent.includes('PP4_COHORT_ASCERTAINMENT_INCOMPATIBLE'), 'minimal NE');
                ensure(!host.textContent.includes('实际强度：NONE'), 'no fabricated strength');
            });
            await record('late POST never overwrites switched analysis or confirmation', async () => {
                const slow = deferred(); await reset(unknown.saved.analysisId, { submit: () => slow.promise }); await settle(); confirm().click(); await wait(25); submit().click(); await wait(25);
                change(alpha.saved.analysisId); await settle(); slow.resolve(unknown.saved); await wait(50);
                ensure(host.textContent.includes(alpha.options.pp4Preparation.materialBinding.context.disease.label), 'current analysis');
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
                const row = status === 'COMPLETED_WITH_BLOCKERS' ? unknown.saved : { ...failed.failed, status }; await reset(row.analysisId, { rows: () => [row], read: () => row }); await settle();
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
            for (const kind of ['GET1 error', 'unknown scope', 'partial PP4', 'mixed PP4']) await record('discovery stops on ' + kind, async () => {
                await reset(alpha.saved.analysisId, { options: () => {
                    if (kind === 'GET1 error') throw new Error('TEST_ONLY_DISCOVERY_FAILED');
                    if (kind === 'unknown scope') return { ...legacy(alpha.saved.analysisId, 1), executionProfile: {} };
                    const o = structuredClone(alpha.options); if (kind === 'partial PP4') delete o.pp4Preparation; else o.candidates = []; return o;
                } }); await settle(); ensure(!submit(), 'no permission'); equal(calls.filter(c => c.method === 'OPTIONS').map(c => c.n), [1], 'no continuation');
            });
            await record('GET20 failure discards GET1 and does not retain permission', async () => {
                await reset('legacy', { options: (id, n) => { if (n === 20) throw new Error('TEST_ONLY_GET20_FAILED'); return legacy(id, n); } }); await settle(); ensure(!submit(), 'no partial authority');
            });
            for (const stage of [1, 20]) await record('late discovery GET' + stage + ' cannot touch new state', async () => {
                const slow = deferred(); await reset('legacy', { options: (id, n) => id === 'legacy' ? n === stage ? slow.promise : legacy(id, n) : fixture(id).options });
                await until(() => calls.some(c => c.method === 'OPTIONS' && c.n === stage)); change(unknown.saved.analysisId); await settle();
                confirm().click(); await wait(25); slow.resolve(legacy('legacy', stage)); await wait(70);
                ensure(confirm().checked && !submit().disabled && host.textContent.includes(unknown.options.pp4Preparation.materialBinding.context.disease.label), 'late response preserves new confirmation/busy/options');
                if (stage === 1) ensure(!calls.some(c => c.analysisId === 'legacy' && c.n === 20), 'old GET1 cannot start GET20');
            });
            await record('new options identity revokes existing confirmation', async () => {
                await reset(alpha.saved.analysisId); await settle(); confirm().click(); await wait(25); button('读取正式候选').click(); await settle(); ensure(!confirm().checked && submit().disabled, 'refresh requires confirmation');
            });
            await record('UNKNOWN current outer preparation stays eligible, opaque raw source is not reviewed by UI', async () => {
                const f=fixtures.NE_POSITIVE_ONLY; await reset(f.saved.analysisId,{rows:()=>[f.saved]});await settle();
                ensure(confirm() && submit().disabled,'valid outer identity');confirm().click();await wait(25);ensure(!submit().disabled,'explicit selection remains possible');
                ensure(host.textContent.includes('PP4_COHORT_ASCERTAINMENT_INCOMPATIBLE') && host.textContent.includes('实际强度：未记录'),'saved minimal NE');ensure(calls.every(c=>c.method!=='POST'),'history only');
            });
            await record('saved PP4 history remains independent of current legacy scope',async()=>{
                const f=fixtures.HALF_2_5;await reset(f.saved.analysisId,{options:(id,n)=>legacy(id,n),rows:()=>[f.saved]});await settle();
                ensure(host.textContent.includes('已读取 Top-20')&&host.textContent.includes('MODERATE')&&host.textContent.includes('历史诊断队列'),'history uses saved identity');ensure(calls.every(c=>c.method!=='POST'),'history only');
            });
            await record('published FT refusal revokes permission but preserves saved history',async()=>{
                await reset(unknown.saved.analysisId,{rows:()=>[unknown.saved]});await settle();confirm().click();await wait(25);
                configuration.options=()=>{throw Object.assign(new Error('PP4_PUBLISHED_SAMPLE_FILTER_FAILED'),{response:{status:409}});};
                button('读取正式候选').click();await settle();ensure(!submit()&&!confirm(),'source failure revokes');ensure(host.textContent.includes('PP4_PUBLISHED_SAMPLE_FILTER_FAILED')&&host.textContent.includes(unknown.saved.runId),'source error and history retained');ensure(calls.every(c=>c.method!=='POST'),'no execution');
            });
            for(const name of ['NORMAL_ALPHA','HALF_2_5','NE_ALTERNATIVE_METHOD'])await record('remaining original readonly scene '+name,async()=>{
                const f=fixtures[name];await reset(f.saved.analysisId,{rows:()=>[f.saved]});await settle();ensure(confirm()&&submit().disabled,'valid unconfirmed material');ensure(host.textContent.includes(f.saved.runId),'own saved run');ensure(host.textContent.includes(name.startsWith('NE_')?'PP4_ALTERNATIVE_METHOD_UNESTABLISHED':'MODERATE'),'own outcome');ensure(calls.every(c=>c.method!=='POST'),'read only');
            });
            await record('CWB explicit retry retains complete archived request',async()=>{
                await reset(unknown.saved.analysisId,{rows:()=>[unknown.saved]});await settle();confirm().click();await wait(25);ensure(!retry().disabled,'valid CWB retry');retry().click();await settle();const post=calls.find(c=>c.method==='POST');ensure(post.body.retryOf===unknown.saved.runId,'exact CWB retryOf');
            });
            await record('409 material conflict distinct from 503 and preserved NE history',async()=>{
                await reset(unknown.saved.analysisId,{rows:()=>[unknown.saved],options:()=>{throw Object.assign(new Error('PP4_MATERIAL_UNAVAILABLE'),{response:{status:409}});}});await settle();ensure(!confirm()&&!submit(),'no permission');ensure(host.textContent.includes('PP4_MATERIAL_UNAVAILABLE')&&host.textContent.includes('PP4_COHORT_ASCERTAINMENT_INCOMPATIBLE'),'both distinct facts');ensure(calls.every(c=>c.method!=='POST'),'readonly');
            });
            await record('single PROBAND, full cohort access, ascertainment and alternatives',async()=>{
                const f=fixtures.HALF_2_5;await reset(f.saved.analysisId,{rows:()=>[f.saved]});await settle();
                ensure(host.textContent.includes('500 / 1000')&&host.textContent.includes('1000 例'),'full denominator');
                ensure(host.textContent.includes('ALTERNATIVE_DIAGNOSIS')&&host.textContent.includes('PHENOCOPY')&&host.textContent.includes('NOT_SELECTED_BY_GENE_RESULT'),'source fields');
                const details=[...host.querySelectorAll('details')].find(x=>x.querySelector('summary')?.textContent.includes('完整队列名单'));details.open=true;
                for(let i=0;i<49;i++){button('队列下一页').click();await wait(15);}ensure(details.textContent.includes('病例 1000'),'last case accessible');
                ensure(calls.every(c=>c.method!=='POST'),'readonly');
            });
            await record('CWB changed material pins requires refresh and new confirmation for exact retry',async()=>{
                const f=fixtures.CWB_CHANGED_PINS;await reset(f.saved.analysisId,{rows:()=>[f.failed],read:()=>f.failed});await settle();
                ensure(submit().disabled&&retry().disabled,'history gives no current permission');confirm().click();await wait(25);retry().click();await settle();
                equal(calls.find(c=>c.method==='POST').body,f.request,'new pins exact request');ensure(host.textContent.includes(f.saved.runId),'new result');
            });
            await record('corrupt saved identity blocks current PP4 confirmation and trusted conclusion',async()=>{
                const bad=structuredClone(alpha.saved);bad.candidates[0].attempts[0].pp4.familyId+='x';bad.bundles[0].core=structuredClone(bad.candidates[0].attempts[0].pp4);await reset(alpha.saved.analysisId,{rows:()=>[bad],read:()=>bad});await settle();ensure(host.textContent.includes('保存的 PP4 身份不完整或相互冲突'),'warning');ensure(!confirm()||confirm().disabled,'confirmation blocked');ensure(!host.textContent.includes('实际强度：MODERATE'),'no trusted conclusion');ensure(calls.every(c=>c.method!=='POST'),'readonly');
            });

            }
            report.status = 'PASS';
        } catch (e) { report.status = 'FAIL'; report.error = e.stack; report.failedCalls = calls; }
        finally {
            if (root) root.unmount(); result.textContent = JSON.stringify(report, null, 2);
            await fetch('/__test/component-results', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(report) });
        }
    };
}
