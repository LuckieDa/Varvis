import React, { useEffect, useRef, useState } from 'react';
import DeNovoCoreMaterialView from './DeNovoCoreMaterialView';
import DeNovoReadingView from './DeNovoReadingView';
import LiteraturePriorityView from './LiteraturePriorityView';
import axios from 'axios';
import { Alert, Button, Checkbox, FormControlLabel, Paper, Stack, Typography } from '@mui/material';

const base = (id) => `/variant-analyses/${encodeURIComponent(id)}/acmg/literature`;
const pending = (task) => ['QUEUED', 'RUNNING'].includes(task?.status);
const searchReasons = {
    PRECISE_CANDIDATES_FOUND: '精确检索已有候选，未扩大到基因检索',
    PRECISE_SEARCH_INCOMPLETE: '精确检索尚未完成，未将失败当作无命中',
    NO_PRECISE_HITS: '精确检索未命中，已允许基因与家系关键词兜底',
    PRECISE_CLUES_UNAVAILABLE: '缺少可用精确检索线索，使用基因与家系关键词',
};
const searchTiers = { DIRECT_CITATION: 'ClinVar 关联论文', VARIANT_TERMS: '变异写法', GENE_FAMILY_CONTEXT: '基因与家系关键词',
    LITVAR_VARIANT: 'LitVar 变异与论文关联', LITVAR_PUBLICATIONS: 'LitVar 论文元数据核对' };
const searchIssues = {
    LITERATURE_SEARCH_LITVAR_AMBIGUOUS_OR_TRUNCATED: '变异对应不唯一或候选列表截限，未自动选择',
    LITERATURE_SEARCH_LITVAR_NAME_NOT_CONFIRMED: '未确认相同基因和变异写法',
    LITERATURE_SEARCH_LITVAR_METADATA_MISSING: '部分论文标识未取得元数据，保留待核对',
    LITERATURE_SEARCH_LITVAR_REFERENCE_LIMIT: '关联论文超过本轮上限，余项尚未处理',
    LITERATURE_SEARCH_LITVAR_REQUEST_BUDGET: 'LitVar 本轮请求名额已用完',
    LITERATURE_SEARCH_LITVAR_TIME_BUDGET: 'LitVar 本轮时间预算已用完',
    LITERATURE_SEARCH_LITVAR_NAME_MAPPING_UNRESOLVED: 'LitVar 返回的名称与输入不同，保留论文线索，具体变异身份待核对',
    LITERATURE_SEARCH_LITVAR_GENE_CONFLICT: '返回条目的基因不相容，未跟随该条目的论文',
    LITERATURE_SEARCH_LITVAR_SUBSTITUTION_CONFLICT: '返回条目是不同的替换，未跟随该条目的论文',
    LITERATURE_SEARCH_LITVAR_AUTOCOMPLETE_LIMIT: '名称候选达到接口上限，不能视为已穷尽',
    LITERATURE_SEARCH_REQUEST_BUDGET: '本批请求额度已用完，已取得结果保留，可继续未完成部分',
    LITERATURE_SEARCH_BATCH_LIMIT: '本批处理量已到上限，剩余查询已保留',
    LITERATURE_SEARCH_TOTAL_TIMEOUT: '本批总时间已用完，已取得结果保留',
    LITERATURE_SEARCH_VARIANT_CLUES_UNAVAILABLE: '缺少可用于 LitVar 的变异名称或标识',
    LITERATURE_SEARCH_NETWORK_DISABLED: '公网获取关闭且无有效缓存',
    LITERATURE_SEARCH_TIMEOUT: '查询超时，不代表没有文献',
    LITERATURE_SEARCH_RATE_LIMIT: '来源限流，已停止本轮对该来源的新增请求',
};
const searchStates = { COMPLETE: '已查完本轮范围', PARTIAL: '未查完', UNAVAILABLE: '查询不可用',
    SKIPPED_PRECISE_HIT: '已有精确候选，跳过', BLOCKED_PRECISE_INCOMPLETE: '前序未完成，暂不扩大', PENDING: '尚未执行' };
export const validLiteratureTask = (task, expected = {}) => {
    if (!task || task.schemaVersion !== 'denovo-public-literature-task-v1' ||
        !/^[a-f0-9]{64}$/.test(task.taskId) || task.scientificExecution !== false ||
        task.modelCalls !== null || !['QUEUED', 'RUNNING', 'READY', 'FAILED', 'CANCELLED'].includes(task.status)) {
        throw new Error('公开文献任务回执不相容，请重新读取。');
    }
    if (task.status === 'READY' && (task.result?.schemaVersion !== 'denovo-public-literature-workflow-v1' ||
        task.result.scientificExecution !== false || task.result.businessPublishable !== false ||
        !Number.isSafeInteger(task.result.modelCalls) || task.result.modelCalls < 0)) {
        throw new Error('公开文献结果缺少调用或采用边界。');
    }
    if ((expected.taskId && task.taskId !== expected.taskId) ||
        (expected.analysisId && task.analysisId !== undefined && task.analysisId !== expected.analysisId) ||
        (expected.selectionSha256 && (task.result?.selectionSha256 !== expected.selectionSha256 ||
            (task.selectionSha256 !== undefined && task.selectionSha256 !== expected.selectionSha256)))) {
        throw new Error('公开文献任务身份或候选选择不相容，未采用这次响应。');
    }
    return task;
};

// A repository ID without its source analysis must never become a detail URL.
// Callers pass initialTaskId only for references with a known source analysis.
export async function readPublicLiteratureEntry({ analysisId, initialTaskId, signal, read = axios.get }) {
    if (!analysisId) throw new Error('缺少资料任务所属分析，未读取任务。');
    const config = { timeout: 10000, signal };
    let focused;
    if (initialTaskId) {
        if (!/^[a-f0-9]{64}$/.test(initialTaskId)) throw new Error('公开文献任务编号无效。');
        const { data } = await read(`${base(analysisId)}/tasks/${initialTaskId}`, config);
        focused = validLiteratureTask(data, { analysisId, taskId: initialTaskId });
    }
    const { data } = await read(`${base(analysisId)}/tasks`, config);
    if (!Array.isArray(data)) throw new Error('公开文献任务列表回执不相容。');
    const list = data.map(task => validLiteratureTask(task, { analysisId }));
    return focused ? [focused, ...list.filter(task => task.taskId !== focused.taskId)] : list;
}

// Matches the durable discovery queue's retriable acquisition stops. A disabled
// network alone is not progress: enable/fill the required source before retrying.
const resumableAcquisitionReasons = new Set(['PAPER_ACQUISITION_PAPER_BUDGET', 'PAPER_DOWNLOAD_REQUEST_BUDGET',
    'PAPER_DOWNLOAD_BYTE_BUDGET', 'PAPER_DOWNLOAD_TOTAL_TIMEOUT', 'PAPER_DOWNLOAD_BATCH_STOPPED',
    'PAPER_DOWNLOAD_RATE_LIMIT', 'PAPER_DOWNLOAD_RATE_LIMIT_COOLDOWN']);
const readableContinuationReasons = new Set(['READING_CHUNK_LIMIT', 'DENOVO_BUNDLE_READING_CALL_LIMIT',
    'DENOVO_BUNDLE_READING_TIME_LIMIT', 'DENOVO_BUNDLE_READING_MODEL_DISABLED']);
export function publicLiteratureActionAvailable(task, resume, tasks = []) {
    if (task?.status !== 'READY' || !task.result || task.taskId !== resume?.taskId || tasks.some(pending)) return false;
    if (resume.discovery) {
        const result = task.result, state = result.discoveryState;
        if (state?.resumeAvailable !== true || state.searchMode !== 'LITVAR_ONLY' ||
            !/^[a-f0-9]{64}$/.test(state.sha256 || '') ||
            !Number.isSafeInteger(state.pendingPapers) || state.pendingPapers < 0 ||
            !Number.isSafeInteger(state.pendingQueries) || state.pendingQueries < 0 ||
            tasks.some(t => t.result?.discoveryContinuationOf?.parentTaskId === task.taskId)) return false;
        const processed = new Set((result.papers || []).map(paper => paper.pmcid));
        // Counts also include processed papers with missing caches. They cannot
        // bypass the original per-paper continuation/no-progress checks here.
        const unread = Array.isArray(result.acquisition) && result.acquisition.some(paper =>
            !processed.has(paper.pmcid) && ((paper.status === 'REGISTERED' && /^PMC[1-9][0-9]*$/.test(paper.pmcid || '')) ||
                resumableAcquisitionReasons.has(paper.reasonCode)));
        return (state.pendingPapers > 0 && unread) || (state.pendingPapers === 0 && state.pendingQueries > 0);
    }
    if (!/^PMC[1-9][0-9]*$/.test(resume.pmcid || '')) return false;
    const papers = (task.result.papers || []).filter(paper => paper.pmcid === resume.pmcid);
    if (papers.length !== 1) return false;
    const paper = papers[0];
    if (resume.accept) return /^[a-f0-9]{64}$/.test(paper.candidateIntake?.sha256 || '');
    if (tasks.some(t => t.result?.continuationOf?.parentTaskId === task.taskId && t.result?.continuationOf?.pmcid === paper.pmcid) ||
        task.result.issues?.some(issue => issue !== 'CANDIDATE_REVIEW_NOT_SCIENTIFIC_ADOPTION')) return false;
    if (/NO_PROGRESS|CONFLICT|NO_PENDING|NO_SUPPORTED_PENDING|EXTERNAL_OR_UNSUPPORTED|NO_READABLE_NEED/.test(paper.continuation?.reasonCode || '')) return false;
    if (paper.needReading) return paper.continuation?.eligible === true && readableContinuationReasons.has(paper.continuation.reasonCode);
    if (paper.continuation && paper.continuation.eligible !== true) return false;
    return paper.continuation?.eligible === true || paper.unprocessedChunks?.some(c =>
        ['MODEL_DISABLED_NO_PAID_CALL', 'MODEL_BATCH_LIMIT_UNPROCESSED_CHUNKS'].includes(c.reasonCode)) ||
        paper.sourceReview?.pendingTargets?.some(target => target.reasonCode === 'SOURCE_REVIEW_BATCH_LIMIT') || false;
}

export async function readPublicLiteratureContinuation({ analysisId, topN, resume, expectedSelection, signal, read = axios.get }) {
    if (!Number.isSafeInteger(topN) || topN < 1) throw new Error('原候选数量未保存或无效；仅可阅读任务，不使用默认 Top-N 继续。');
    if (!resume?.taskId) throw new Error('缺少待核对的原文献任务。');
    const tasks = await readPublicLiteratureEntry({ analysisId, initialTaskId: resume.taskId, signal, read });
    const task = tasks.find(row => row.taskId === resume.taskId);
    if (!publicLiteratureActionAvailable(task, resume, tasks)) throw new Error('当前任务未开放这项操作；请核对未读范围、冲突或停止原因，不会自动重试。');
    const { data: options } = await read(`${base(analysisId)}/options`, { params: { topN }, timeout: 30000, signal });
    if (options.schemaVersion !== 'denovo-public-literature-workflow-v1' || !/^[a-f0-9]{64}$/.test(options.selectionSha256) ||
        (expectedSelection && expectedSelection !== options.selectionSha256)) throw new Error('候选选择已变化或准备回执无效，请重新核对并确认。');
    validLiteratureTask(task, { analysisId, taskId: resume.taskId, selectionSha256: options.selectionSha256 });
    return { tasks, options };
}

export default function PublicLiteraturePanel({ analysisId, topN, readOnlyEntry = false, initialTaskId = null, onReturn }) {
    const scopeUnavailable = !Number.isSafeInteger(topN) || topN < 1;
    const [options, setOptions] = useState(null);
    const [confirmed, setConfirmed] = useState(false);
    const [tasks, setTasks] = useState([]);
    const [busy, setBusy] = useState(true);
    const [message, setMessage] = useState('');
    const [uncertain, setUncertain] = useState(false);
    const [continuation, setContinuation] = useState(null);
    const active = useRef('');
    const identity = `${analysisId}:${topN}:${readOnlyEntry}:${initialTaskId || ''}`;
    active.current = identity;
    const alive = useRef(true);
    useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
    const current = (key) => alive.current && active.current === key;

    const refresh = async () => {
        const key = identity;
        setMessage(''); setBusy(true);
        try {
            const list = await readPublicLiteratureEntry({ analysisId, initialTaskId });
            if (current(key)) setTasks(list);
        } catch (error) { if (current(key)) setMessage(error.message); }
        finally { if (current(key)) setBusy(false); }
    };
    useEffect(() => {
        setOptions(null); setConfirmed(false); setTasks([]); setMessage(''); setUncertain(false); setBusy(true); setContinuation(null);
        const key = identity;
        const controller = new AbortController();
        readPublicLiteratureEntry({ analysisId, initialTaskId, signal: controller.signal })
            .then(list => { if (current(key)) setTasks(list); })
            .catch(error => { if (current(key) && !controller.signal.aborted) setMessage(error.response?.data?.message || error.message); })
            .finally(() => { if (current(key) && !controller.signal.aborted) setBusy(false); });
        return () => controller.abort();
    }, [identity]); // eslint-disable-line react-hooks/exhaustive-deps

    const prepare = async (resume = null) => {
        const key = identity; setBusy(true); setMessage(''); setConfirmed(false); setOptions(null); setContinuation(null);
        try {
            const prepared = resume ? await readPublicLiteratureContinuation({ analysisId, topN, resume }) : null;
            const data = prepared ? prepared.options : (await axios.get(`${base(analysisId)}/options`, { params: { topN }, timeout: 30000 })).data;
            if (prepared && current(key)) setTasks(prepared.tasks);
            if (data.schemaVersion !== 'denovo-public-literature-workflow-v1' ||
                !/^[a-f0-9]{64}$/.test(data.selectionSha256)) throw new Error('准备回执无效。');
            if (current(key)) { setOptions(data); setUncertain(false); setContinuation(resume); }
        } catch (error) { if (current(key)) { setOptions(null); setMessage(error.response?.data?.message || error.message); } }
        finally { if (current(key)) setBusy(false); }
    };
    const cancel = async (taskId) => {
        const key = identity; setBusy(true); setMessage('');
        try {
            const { data } = await axios.post(`${base(analysisId)}/tasks/${taskId}/cancel`, {}, { timeout: 15000 });
            const task = validLiteratureTask(data, { analysisId, taskId });
            if (current(key)) {
                setTasks((old) => old.map((t) => t.taskId === taskId ? task : t));
                setMessage('已请求停止；请只读刷新确认进程退出。已有原件与费用记录保留，不会自动重试。');
            }
        } catch (error) { if (current(key)) setMessage('停止结果待核对，请只读刷新任务。'); }
        finally { if (current(key)) setBusy(false); }
    };
    const start = async () => {
        if (!confirmed || busy || uncertain || !options?.enabled || (continuation?.accept && !options.candidateAcceptanceEnabled)) return;
        const key = identity; setBusy(true); setMessage(''); setConfirmed(false);
        let submitted = false;
        try {
            if (continuation) {
                const checked = await readPublicLiteratureContinuation({ analysisId, topN, resume: continuation, expectedSelection: options.selectionSha256 });
                if (!current(key)) return;
                setTasks(checked.tasks);
                if (!checked.options.enabled || (continuation.accept && !checked.options.candidateAcceptanceEnabled)) throw new Error('当前任务配置未开放这项操作，请核对配置后重新确认。');
            }
            const suffix = continuation?.discovery ? `/tasks/${continuation.taskId}/discovery/continue` : continuation ? `/tasks/${continuation.taskId}/papers/${continuation.pmcid}/${continuation.accept ? 'accept' : 'continue'}` : '/tasks';
            submitted = true;
            const { data } = await axios.post(`${base(analysisId)}${suffix}`, {
                topN: Number(topN), selectionSha256: options.selectionSha256,
                [continuation?.accept ? 'confirmSourceIntake' : 'confirmPublicModel']: true,
                ...(!continuation ? { searchMode: 'LITVAR_ONLY' } : {}),
            }, { timeout: continuation?.accept ? 75000 : 15000 });
            if (continuation?.accept) {
                if (data.schemaVersion !== 'denovo-candidate-sources-v1' || data.status !== 'ACCEPTED_FOR_QUALIFICATION_NOT_SCORED' || data.scientificExecution !== false || data.modelCalls !== 0) throw new Error('资料接收回执无效。');
                if (current(key)) { setOptions(null); setContinuation(null); setMessage('资料已加入后续核验。请重新准备证据分析；系统仍会核对变异、疾病、亲子关系及重复病例。已有结果保持不变。'); }
            } else {
                const task = validLiteratureTask(data);
                if (current(key)) setTasks((old) => [task, ...old.filter((t) => t.taskId !== task.taskId)]);
            }
        } catch (error) {
            if (current(key)) {
                setUncertain(submitted);
                setOptions(null); setContinuation(null);
                setMessage(submitted ? `提交结果待核对，请只读刷新任务列表。${error.response?.data?.message || ''}` : `${error.response?.data?.message || error.message} 未提交新任务。`);
            }
        } finally { if (current(key)) setBusy(false); }
    };
    return <Paper variant="outlined" sx={{ p: 2, my: 2 }}>
        <Stack spacing={1}>
            <Typography variant="h6">PS2 / PM6 公开文献获取与提取</Typography>
            {scopeUnavailable && <Alert severity="warning">原候选范围缺失或无效，当前仅可查看任务。</Alert>}
            <Stack direction="row" spacing={1}>
                {!readOnlyEntry && <Button disabled={busy || tasks.some(pending)} onClick={() => prepare()}>准备本次公开文献任务</Button>}
                {onReturn && <Button onClick={onReturn}>返回证据详情</Button>}
                <Button disabled={busy} onClick={refresh}>只读刷新文献任务</Button>
            </Stack>
            {options && <Alert severity={options.enabled ? 'info' : 'warning'}>
                {continuation?.accept ? `将 ${continuation.pmcid} 的本轮累计资料加入后续证据核验；不调用模型、不直接计分。${options.candidateAcceptanceEnabled ? '' : '资料接收尚未配置。'}` : <>
                    {continuation?.discovery ? '继续上一轮尚未处理的查询与论文；保留原检索方式，已完成的论文不会重复提取。' : continuation && `继续处理 ${continuation.pmcid}；绑定上一轮原文版本，复用已完成目标，仅处理尚未完成部分。`}
                    {!continuation && '发现方式：LitVar（论文信息补全使用 Europe PMC）。'}
                    {options.enabled ? `本次最多 ${options.maximumPapers} 篇、${options.maximumModelChunks} 次新增模型请求；含排队最多 ${options.budgetSecondsIncludingQueue} 秒。公开获取${options.publicNetworkEnabled ? '已启用' : '仅用缓存'}，模型${options.modelEnabled ? '可产生费用，受共享总预算限制' : '付费调用关闭，可复用核验通过的缓存'}。` : '公开文献任务尚未启用，请检查本地任务配置。'}
                    {options.readingPriorityEnabled && `先由模型核对标题和摘要的相关性（最多 ${options.maximumPriorityCalls} 次建议请求，同享模型预算），最多预缓存 ${options.maximumPrefetchPapers} 篇，深入阅读上限 ${options.maximumPapers} 篇；未读论文保留续作。`}
                    联网时仅发送必要的基因与变异检索词及公开论文编号，不发送样本、患者身份或表型。
                </>}
                大论文、未解析附件和未处理分块会明确列出。提取与引用核验不等于病例可采用。
            </Alert>}
            {options?.enabled && <>
                <FormControlLabel control={<Checkbox checked={confirmed} disabled={busy || uncertain || tasks.some(pending)} onChange={(e) => setConfirmed(e.target.checked)} />}
                    label={continuation?.accept ? '确认加入后续资格核验；这不代表科学批准，也不修改旧结果。' : '确认上述检索信息范围，仅提取公开论文；按限额执行，模型启用时可能产生费用。'} />
                <Button variant="outlined" disabled={!confirmed || busy || uncertain || tasks.some(pending) || (continuation?.accept && !options.candidateAcceptanceEnabled)} onClick={start}>{continuation?.accept ? '确认加入后续核验' : continuation?.discovery ? '确认继续未处理文献' : continuation ? '确认继续这篇论文' : '启动公开文献任务'}</Button>
            </>}
            {busy && <Typography role="status" variant="body2">正在核对文献任务与候选范围…</Typography>}
            {message && <Alert severity="warning">{message}</Alert>}
            {readOnlyEntry && !busy && !message && tasks.length === 0 && <Alert severity="info">当前分析未列出公开文献任务。</Alert>}
            {tasks.map((task) => <Paper key={task.taskId} variant="outlined" sx={{ p: 1 }}>
                <Typography>任务 {task.taskId.slice(0, 12)} · {task.status}</Typography>
                {pending(task) && <Button disabled={busy} onClick={() => cancel(task.taskId)}>停止此文献任务</Button>}
                {task.reasonCode && <Alert severity="warning">{task.reasonCode}；未自动重试，失败费用可能仍有保留。</Alert>}
                {task.result && <>
                    <Typography variant="body2">{task.result.status} · 模型调用/保留尝试 {task.result.modelCalls} · 科学求值 0。{task.result.issues?.join('；')}</Typography>
                    <LiteraturePriorityView result={task.result} />
                    {task.result.discovery?.searchMode && <Typography variant="body2">本轮发现方式：{{ COMBINED: '历史两路合并（已停用）', LITVAR_ONLY: '仅 LitVar', ORIGINAL: '历史关键词检索（已停用）' }[task.result.discovery.searchMode]}</Typography>}
                    {task.result.discoveryState && <Alert severity={task.result.discoveryState.resumeAvailable ? 'info' : 'success'}>
                        累计已尝试获取 {task.result.discoveryState.attemptedPapers} 篇；待获取 {task.result.discoveryState.pendingPapers} 篇，待查询 {task.result.discoveryState.pendingQueries} 项。获取失败的原因见各篇记录；查完本轮计划不等于穷尽全部文献。
                    </Alert>}
                    {publicLiteratureActionAvailable(task, { taskId: task.taskId, discovery: true }, tasks) &&
                        <Button disabled={busy || uncertain || scopeUnavailable || tasks.some(pending)} onClick={() => prepare({ taskId: task.taskId, discovery: true })}>准备继续未处理文献</Button>}
                    {(task.result.discovery?.queries?.length > 0 || task.result.discovery?.targets?.length > 0) && <Stack spacing={0.5}>
                        <Typography variant="subtitle2">本次检索顺序与范围</Typography>
                        {task.result.discovery.targets?.map((target, index) => <Typography variant="body2" key={target.targetId}>
                            候选 {index + 1} · {target.geneSymbol}：{target.candidateCount} 篇候选论文；{searchReasons[target.fallbackReason] || '查看下方各层实际执行状态'}。
                        </Typography>)}
                        {task.result.discovery.queries?.map((query, index) => <Stack key={index} spacing={0.5}><Typography variant="body2">
                            {searchTiers[query.tier] || query.tier}：{searchStates[query.status] || query.status}{query.tier === 'LITVAR_VARIANT' ? '' : `；已读 ${query.pagesRead || 0} 页`}{Number.isSafeInteger(query.hitCount) ? `，接口返回 ${query.hitCount} 条` : ''}。
                            {Number.isSafeInteger(query.linkedReferenceCount) && `关联 ${query.linkedReferenceCount} 个论文标识（PMID／PMCID 未必互不重复）。`}
                            {query.issues?.map((issue) => searchIssues[issue] || issue).join('；')}
                        </Typography>{query.variantCandidates?.length > 0 && <details><summary>查看 {query.variantCandidates.length} 个 LitVar 条目及身份核对</summary>
                            {query.variantCandidates.map((v) => <Typography variant="body2" key={v.litvarVariantId}>
                                {v.gene?.join(' / ')} {v.name} · {v.discoveryRelation === 'UNRESOLVED_NAME_MAPPING' ? '名称映射待核实' : v.discoveryRelation?.endsWith('CONFLICT') ? '身份冲突，已排除' : '检索线索匹配，尚非等位身份核实'} ·
                                <a href={`https://www.ncbi.nlm.nih.gov/research/litvar2/docsum?variant=${encodeURIComponent(v.litvarVariantId)}`} target="_blank" rel="noreferrer">查看 LitVar 原条目</a>
                            </Typography>)}
                        </details>}</Stack>)}
                        {task.result.discovery.additionalQueryRecords > 0 && <Typography variant="body2">另有 {task.result.discovery.additionalQueryRecords} 项查询保存在完整任务记录中。</Typography>}
                    </Stack>}
                    {task.result.discovery?.articles?.length > 0 && <details><summary>本批 {task.result.discovery.articles.length} 篇候选论文</summary>
                        {task.result.discovery.articles.map((article, i) => <Typography variant="body2" key={`${article.pmid || article.pmcid}-${i}`}>
                            {article.publicationYear || ''} {article.title} ·
                            {article.pmid ? <a href={`https://pubmed.ncbi.nlm.nih.gov/${encodeURIComponent(article.pmid)}/`} target="_blank" rel="noreferrer">PMID {article.pmid}</a> : article.pmcid}
                            {article.matches?.some((m) => m.litvarLinks?.some((v) => v.discoveryRelation === 'UNRESOLVED_NAME_MAPPING')) && ' · 目标变异关联待核实'}
                        </Typography>)}
                    </details>}
                    <details><summary>查看检索、获取与费用情况</summary>
                        <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 300, overflow: 'auto' }}>{JSON.stringify({
                            discovery: task.result.discovery, acquisition: task.result.acquisition,
                            networkRequests: task.result.networkRequests,
                            estimatedMicroCreditsThisRun: task.result.estimatedMicroCreditsThisRun,
                            chargeStatus: task.result.modelChargeStatus,
                        }, null, 2)}</pre>
                    </details>
                    {task.result.papers?.map((paper) => <details key={paper.pmcid}>
                        <summary>{paper.pmcid}：{paper.needReading ? (paper.needReading.status === 'MATERIAL_READY' ? '资料已就绪，待规则评价' : '仍有缺项或未完成检查') : `已处理 ${(paper.chunks || []).length}/${paper.plannedChunks ?? '?'} 块`}</summary>
                        <DeNovoReadingView reading={paper.needReading} />
                        {paper.cumulativeCases && <Typography variant="body2">累计 {paper.cumulativeCases.uniqueCases} 个病例候选，来自 {paper.cumulativeCases.proposals} 次提议；同病例合并，冲突保留。尚需变异身份与疾病适用核验，不直接计分。</Typography>}
                        {paper.sourceReview && <Alert severity={paper.sourceReview.status === 'REPLAYED_NOT_SCORED' ? 'info' : 'warning'}>
                            来源复核：已核对 {paper.sourceReview.reviewedTargets} 个提取结果。
                            {paper.sourceReview.status === 'REPLAYED_NOT_SCORED' ? '复核建议已接入程序核验；模型同意不等于事实合格或可以计分。' : '复核尚未齐全，暂不开放资料接收；已有事实和缺项仍保留。'}
                        </Alert>}
                        <DeNovoCoreMaterialView materials={paper.coreMaterials} />
                        {paper.continuation && <Typography variant="body2">本轮新增完成 {paper.continuation.newTargets} 个目标，复用 {paper.continuation.reusedTargets} 个；尚未完成 {paper.continuation.pendingTargets} 个。{paper.continuation.reasonCode === 'NO_PROGRESS_CONFIGURATION_OR_CACHE_REQUIRED' ? '本篇本轮没有新增进展，需要核对配置或缓存；其他论文的未读范围仍单独保留。' : paper.continuation.reasonCode}</Typography>}
                        {publicLiteratureActionAvailable(task, { taskId: task.taskId, pmcid: paper.pmcid, accept: true }, tasks) && <Button disabled={busy || uncertain || scopeUnavailable || tasks.some(pending)} onClick={() => prepare({ taskId: task.taskId, pmcid: paper.pmcid, accept: true })}>准备接收 {paper.pmcid} 资料</Button>}
                        {publicLiteratureActionAvailable(task, { taskId: task.taskId, pmcid: paper.pmcid }, tasks) &&
                            <Button disabled={busy || uncertain || scopeUnavailable || tasks.some(pending)} onClick={() => prepare({ taskId: task.taskId, pmcid: paper.pmcid })}>准备继续 {paper.pmcid}</Button>}
                        <details><summary>查看完整论文任务记录</summary><pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 350, overflow: 'auto' }}>{JSON.stringify(paper, null, 2)}</pre></details>
                    </details>)}
                </>}
            </Paper>)}
        </Stack>
    </Paper>;
}
