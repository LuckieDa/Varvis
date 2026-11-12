// Controlled transport, real React component; no paid service, backend or science.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
const output = path.resolve(process.argv[2]);
if (fs.existsSync(output)) throw Error('New output required');
fs.mkdirSync(output);
const project = fileURLToPath(new URL('../', import.meta.url));
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/PublicLiteraturePanel.jsx');
const coreMaterials = process.argv[3] ? JSON.parse(fs.readFileSync(process.argv[3])) : [];
await build({ stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';import axios from 'axios';import Panel,{validLiteratureTask} from ${JSON.stringify(component)};(${checks.toString()})(React,createRoot,axios,Panel,validLiteratureTask,${JSON.stringify(coreMaterials)});`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'browser', outfile: path.join(output, 'check.js'), define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const server = http.createServer((req, res) => {
    if (req.method !== 'GET') { res.writeHead(405).end(); return; }
    res.setHeader('Cache-Control', 'no-store');
    if (req.url === '/check.js') { res.setHeader('Content-Type', 'application/javascript;charset=utf-8'); res.end(fs.readFileSync(path.join(output, 'check.js'))); return; }
    res.setHeader('Content-Type', 'text/html;charset=utf-8');
    res.end('<!doctype html><meta charset="utf-8"><title>公开文献组件核验</title><h1>公开文献组件核验（合成接口）</h1><div id="root"></div><script src="/check.js"></script>');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const info = { pid: process.pid, url: `http://127.0.0.1:${server.address().port}/` };
fs.writeFileSync(path.join(output, 'server.json'), JSON.stringify(info));console.log(info.url);
function checks(React, createRoot, axios, Panel, validate, coreMaterials) {
    const start = document.createElement('button'), report = document.createElement('pre'), host = document.createElement('div');
    start.textContent = '运行公开文献组件核查';report.id = 'result';document.getElementById('root').append(start, report, host);
    start.onclick = async () => {
        start.disabled = true;const calls = [], passed = [];let error, state = null;
        const root = createRoot(host), tick = () => new Promise(r => setTimeout(r, 100));
        const button = (text) => [...host.querySelectorAll('button')].find(b => b.textContent === text);
        const check = (value, label) => { if (!value) throw Error(label); passed.push(label); };
        const task = { schemaVersion: 'denovo-public-literature-task-v1', taskId: 'a'.repeat(64), status: 'RUNNING', scientificExecution: false, modelCalls: null };
        try {
            axios.defaults.adapter = async config => {
                calls.push({ method: config.method, url: config.url, body: config.data ? JSON.parse(config.data) : null });
                let data;
                if (config.url.endsWith('/options')) data = { schemaVersion: 'denovo-public-literature-workflow-v1', selectionSha256: 'b'.repeat(64), enabled: true, candidateAcceptanceEnabled: true, modelEnabled: true, publicNetworkEnabled: false, maximumPapers: 2, maximumModelChunks: 2, budgetSecondsIncludingQueue: 540, searchPolicy: 'PRECISE_THEN_GENE_ON_NO_HITS' };
                else if (config.url.endsWith('/cancel')) { state = { ...task, status: 'CANCELLED', reasonCode: 'DENOVO_TASK_CANCELLED' }; data = state; }
                else if (config.url.endsWith('/accept')) data = { schemaVersion: 'denovo-candidate-sources-v1', status: 'ACCEPTED_FOR_QUALIFICATION_NOT_SCORED', modelCalls: 0, scientificExecution: false };
                else if (config.method === 'post') { state = { ...task, taskId: config.url.endsWith('/continue') ? 'c'.repeat(64) : task.taskId }; data = state; }
                else data = state ? [state] : [];
                return { data, config, status: 200, statusText: 'OK', headers: {} };
            };
            root.render(React.createElement(Panel, { analysisId: 'a', topN: '2' }));await tick();await tick();
            check(calls.length === 1 && calls[0].method === 'get', 'mount_only_reads');
            button('准备本次公开文献任务').click();await tick();
            check(host.textContent.includes('基因与变异检索词及公开论文编号'), 'variant_search_disclosure');
            check(button('启动公开文献任务').disabled, 'explicit_confirmation_required');
            host.querySelector('input[type=checkbox]').click();await tick();button('启动公开文献任务').click();await tick();
            check(calls.filter(c => c.method === 'post').length === 1, 'one_explicit_post');
            check(calls.find(c => c.method === 'post').body.topN === 2, 'selected_topn_bound');
            check(calls.find(c => c.method === 'post').body.searchMode === 'LITVAR_ONLY', 'litvar_is_default_discovery_mode');
            check(host.textContent.includes('RUNNING'), 'pending_visible');
            state = { ...task, status: 'READY', result: { schemaVersion: 'denovo-public-literature-workflow-v1', status: 'PARTIAL', modelCalls: 1, scientificExecution: false, businessPublishable: false, issues: ['MODEL_BATCH_LIMIT_UNPROCESSED_CHUNKS'], papers: [] } };
            state.result.discovery = { searchPolicy: 'PRECISE_THEN_GENE_ON_NO_HITS', targets: [
                { targetId: 'one', geneSymbol: 'EXAMPLE', candidateCount: 1, fallbackReason: 'PRECISE_CANDIDATES_FOUND' },
                { targetId: 'two', geneSymbol: 'OTHER', candidateCount: 0, fallbackReason: 'PRECISE_SEARCH_INCOMPLETE' }],
                queries: [{ tier: 'DIRECT_CITATION', status: 'COMPLETE', pagesRead: 1, hitCount: 1 },
                    { tier: 'VARIANT_TERMS', status: 'UNAVAILABLE', pagesRead: 0, hitCount: null },
                    { tier: 'LITVAR_VARIANT', status: 'COMPLETE', linkedReferenceCount: 2, issues: [] },
                    { tier: 'LITVAR_PUBLICATIONS', status: 'PARTIAL', pagesRead: 1,
                        issues: ['LITERATURE_SEARCH_LITVAR_METADATA_MISSING'] },
                    { tier: 'LITVAR_VARIANT', status: 'UNAVAILABLE', issues: ['LITERATURE_SEARCH_LITVAR_AMBIGUOUS_OR_TRUNCATED'] }] };
            state.result.readingPriority = { schemaVersion: 'literature-reading-advice-v1', status: 'PARTIAL', cacheHits: 1,
                limits: { maximumPrefetch: 20 }, orderedPaperKeys: ['PMC123'], items: [{ id: 'rank-1', paperKey: 'PMC123',
                    targetId: 'target-0', priority: 'HIGH', identityState: 'ALIAS_MATCH_ONLY', title: '合成论文',
                    reason: '摘要报告了父母检测，仍需正文核对。', cacheHit: true,
                    citations: [{ field: 'abstract', quote: 'Both parents were tested.' }] }] };
            state.result.acquisition = [{ pmcid: 'PMC123', status: 'REGISTERED' }];
            button('只读刷新文献任务').click();await tick();
            check(host.textContent.includes('模型仅依据标题和摘要') && host.textContent.includes('原文已缓存，尚未深入读取'), 'priority_scope_and_prefetch_separate');
            check(host.textContent.includes('具体等位变异待核实') && host.textContent.includes('Both parents were tested.'), 'priority_identity_and_exact_quote_visible');
            check(host.textContent.includes('复用阅读建议 1 项') && host.textContent.includes('部分论文尚无有效阅读建议'), 'priority_cache_and_unread_visible');
            const priorityDetails = host.querySelector('[data-testid="literature-priority"] details');
            priorityDetails.querySelector('summary').click(); await tick();
            check(priorityDetails.open, 'priority_details_expand');
            check(host.textContent.includes('PARTIAL') && host.textContent.includes('MODEL_BATCH_LIMIT'), 'partial_and_cost_visible');
            check(host.textContent.includes('精确检索已有候选，未扩大') && host.textContent.includes('精确检索尚未完成'), 'hit_and_failure_distinct');
            check(host.textContent.includes('ClinVar 关联论文') && host.textContent.includes('查询不可用'), 'actual_search_tiers_visible');
            check(host.textContent.includes('LitVar 变异与论文关联') && host.textContent.includes('关联 2 个论文标识'), 'litvar_links_not_unique_papers');
            check(host.textContent.includes('部分论文标识未取得元数据') && host.textContent.includes('变异对应不唯一'), 'litvar_missing_and_ambiguous_visible');
            button('只读刷新文献任务').click();await tick();
            check(calls.filter(c => c.method === 'post').length === 1, 'refresh_never_posts');
            let rejected = false;try { validate({ ...state, result: { ...state.result, scientificExecution: true } }); } catch { rejected = true; }
            check(rejected, 'forged_scientific_result_rejected');
            state.result.papers = [{ pmcid: 'PMC123', plannedChunks: 5, chunks: [{ chunkIndex: 0 }],
                issues: [], continuation: { eligible: true, newTargets: 1, reusedTargets: 0, pendingTargets: 4 },
                candidateIntake: { sha256: 'd'.repeat(64) }, cumulativeCases: { uniqueCases: 1, proposals: 2 }, coreMaterials }];
            button('只读刷新文献任务').click();await tick();
            const summary = [...host.querySelectorAll('summary')].find((s) => s.textContent.startsWith('PMC123'));
            summary.click();await tick();
            check(host.textContent.includes('累计 1 个病例候选') && host.textContent.includes('2 次提议'), 'merged_case_count_visible');
            state.result.papers[0].sourceReview = { status: 'PENDING', reviewedTargets: 0, pendingTargets: [{ chunkIndex: 0 }] };
            delete state.result.papers[0].candidateIntake;
            button('只读刷新文献任务').click();await tick();
            check(host.textContent.includes('复核尚未齐全') && !button('准备接收 PMC123 资料'), 'missing_review_cannot_accept');
            state.result.papers[0].sourceReview = { status: 'REPLAYED_NOT_SCORED', reviewedTargets: 1, pendingTargets: [] };
            state.result.papers[0].candidateIntake = { sha256: 'd'.repeat(64) };
            button('只读刷新文献任务').click();await tick();
            check(host.textContent.includes('模型同意不等于事实合格或可以计分'), 'review_not_scientific_approval');
            state.result.papers[0].needReading = { status: 'PARTIAL', cases: [{ caseKey: 'one',
                stopReason: 'DENOVO_BUNDLE_READING_CALL_LIMIT', requirementsSatisfied: false,
                feedback: { needs: [{ label: '病例相关家系信息' }] },
                plan: { chunks: [{ state: 'REVIEWED' }, { state: 'PENDING' }, { state: 'HELD' }, { state: 'DEFERRED' }] },
                rounds: [{ chunkIndex: 0, status: 'REVIEWED', extractionCacheHit: true, reviewCacheHit: true, beforeNeedIds: ['a', 'b'], afterNeedIds: ['b'] }] }] };
            button('只读刷新文献任务').click();await tick();
            check(host.textContent.includes('还缺：病例相关家系信息') && host.textContent.includes('达到本轮模型调用上限'), 'need_and_stop_are_specific');
            check(host.textContent.includes('已完成来源复核 1/4 块') && host.textContent.includes('相关待读 1 块'), 'unread_is_not_absent');
            check(host.textContent.includes('失败或待恢复 1 块') && host.textContent.includes('复用原提取'), 'failure_and_cache_are_visible');
            check([...host.querySelectorAll('summary')].some(s => s.textContent === '查看完整论文任务记录' && !s.parentElement.open), 'raw_record_collapsed');
            if (coreMaterials.length) {
                check(host.textContent.includes('统一病例资料') && host.textContent.includes('缺少已配置且适用的疾病或规则知识'), 'saved_core_facts_and_missing_reasons_visible');
                check(calls.filter(c => c.method === 'post').length === 1, 'reading_core_facts_no_new_post');
            }
            button('准备继续 PMC123').click();await tick();
            check(button('确认继续这篇论文').disabled, 'continuation_requires_fresh_confirmation');
            check(calls.filter(c => c.method === 'post').length === 1, 'prepare_continuation_is_read_only');
            host.querySelector('input[type=checkbox]').click();await tick();button('确认继续这篇论文').click();await tick();
            const resume = calls.filter(c => c.method === 'post')[1];
            check(resume.url.endsWith(`/tasks/${task.taskId}/papers/PMC123/continue`) && resume.body.confirmPublicModel === true, 'continuation_binds_parent_and_paper');
            check(calls.filter(c => c.method === 'post').length === 2, 'one_explicit_continuation_post');
            state = { ...state, taskId: task.taskId, status: 'READY', result: { schemaVersion: 'denovo-public-literature-workflow-v1', status: 'PARTIAL', modelCalls: 0, scientificExecution: false, businessPublishable: false,
                papers: [{ pmcid: 'PMC123', chunks: [], candidateIntake: { sha256: 'd'.repeat(64) } }] } };
            button('只读刷新文献任务').click();await tick();
            [...host.querySelectorAll('summary')].find((s) => s.textContent.startsWith('PMC123')).click();await tick();
            button('准备接收 PMC123 资料').click();await tick();
            check(button('确认加入后续核验').disabled, 'acceptance_requires_fresh_confirmation');
            host.querySelector('input[type=checkbox]').click();await tick();button('确认加入后续核验').click();await tick();
            check(calls.filter(c => c.method === 'post').length === 3, 'one_explicit_source_acceptance');
            check(calls.filter(c => c.method === 'post')[2].body.confirmSourceIntake === true, 'acceptance_distinct_from_model_authorization');
            check(host.textContent.includes('资料已加入后续核验') && host.textContent.includes('已有结果保持不变'), 'acceptance_not_scientific_approval');
            state.result.discovery = { searchMode: 'LITVAR_ONLY', targets: [{targetId:'one',geneSymbol:'EXAMPLE',candidateCount:2}], queries: [{ tier: 'LITVAR_VARIANT',
                status: 'PARTIAL', linkedReferenceCount: 150, unqueriedAliases: ['c.123A>G'],
                variantCandidates: [{ litvarVariantId: 'litvar@rs123##', name: 'p.T42A',
                    discoveryRelation: 'UNRESOLVED_NAME_MAPPING' }] }], articles: [{ pmid: '123', title: 'Synthetic public article', publicationYear: '2026' }] };
            state.result.discoveryState = { searchMode: 'LITVAR_ONLY', resumeAvailable: true, pendingPapers: 2, pendingQueries: 3, attemptedPapers: 2 };
            button('只读刷新文献任务').click();await tick();
            check(host.textContent.includes('待获取 2 篇') && host.textContent.includes('待查询 3 项'), 'discovery_remaining_counts_visible');
            check(host.textContent.includes('名称映射待核实'), 'unresolved_identity_not_adopted');
            check([...host.querySelectorAll('a')].some(a => a.href.includes('litvar2/docsum')), 'official_entry_link_available');
            button('准备继续未处理文献').click();await tick();
            check(button('确认继续未处理文献').disabled, 'discovery_continuation_requires_confirmation');
            host.querySelector('input[type=checkbox]').click();await tick();button('确认继续未处理文献').click();await tick();
            const discoveryResume=calls.filter(c=>c.method==='post')[3];
            check(discoveryResume.url.endsWith(`/tasks/${task.taskId}/discovery/continue`), 'discovery_continuation_uses_bound_parent');
            check(!('searchMode' in discoveryResume.body), 'continuation_cannot_override_frozen_search_mode');
            button('停止此文献任务').click();await tick();
            check(calls.filter(c=>c.method==='post').at(-1).url.endsWith('/cancel'), 'explicit_cancel_owned_task');
            check(host.textContent.includes('CANCELLED') && host.textContent.includes('不会自动重试'), 'cancel_keeps_cost_and_no_retry');
            state = null;root.render(React.createElement(Panel, { analysisId: 'b', topN: '1' }));await tick();await tick();
            check(!host.textContent.includes('aaaaaaaaaaaa'), 'switch_revokes_old_task');
            check(!host.textContent.includes('确认继续这篇论文'), 'switch_revokes_continuation');
            root.unmount();const count = calls.length;await tick();check(calls.length === count, 'unmount_stops_activity');
        } catch (e) { error = e.message;root.unmount(); }
        report.textContent = JSON.stringify({ status: error ? 'FAIL' : 'PASS', error, passed, calls, paidModelCalls: 0, scientificCalls: 0, businessPosts: 0 }, null, 2);
    };
}
