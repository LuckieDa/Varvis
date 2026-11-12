import React, { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import LiteraturePriorityView from './LiteraturePriorityView';
import Pp1FormalFacts from './Pp1FormalFacts';
import { isPp1FormalEvaluation } from '../utils/acmgPp1Formal';
import LiteratureStageDiagnostics, { EvaluationNeedDifferences } from './LiteratureStageDiagnostics';
import { currentLiteratureContinuationIssue, pp1ReadingDiagnostic, savedPp1ReadingDiagnostic } from '../utils/acmgLiteratureDiagnostics';
import { Alert, Button, Checkbox, FormControlLabel, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';

const pending = task => ['QUEUED', 'RUNNING'].includes(task.status);
const base = id => `/variant-analyses/${encodeURIComponent(id)}/acmg/literature/pp1`;
const statusText = { QUEUED: '排队中', RUNNING: '正在查找和核实资料', READY: '资料任务已结束，请查看结果', FAILED: '执行失败', CANCELLED: '已停止' };
const stopText = {
    QUALIFIED_EVIDENCE_REACHED: '已核实资料足以给出本次证据强度', PP1_EVALUABLE_LOWER_BOUND_REACHED: '已有可评价的贡献下界，保留尚未处理范围',
    READING_PLAN_NO_PROGRESS: '本次阅读没有新增可核实资料，需先检查原因再继续，不能重复调用相同内容',
    PP1_READING_PLAN_NO_PROGRESS: '本次阅读没有新增可核实资料，需先检查原因再继续，不能重复调用相同内容',
    EXTERNAL_REQUIREMENT: '仍需疾病知识、方法或共享计分记录；继续读同一论文不能解决',
    AVAILABLE_SCOPE_REVIEWED: '已复核当前取得的资料范围', NO_UNREAD_RELEVANT_BLOCK: '当前未读部分未发现可继续核实此缺项的相关块',
    SOURCE_FORMAT_REVIEW_REQUIRED: '可读文本暂未补齐条件，还有已取得但尚不能解析的附件需要核查',
    DENOVO_MODEL_HTTP_503: '模型服务暂时不可用，本轮没有取得模型结果；原资料和后续核查范围保留',
    ROUND_LIMIT: '达到本轮续读次数上限，可继续未读部分', TIME_LIMIT: '达到本轮时间上限', PP1_TIME_LIMIT: '达到本轮时间上限',
    PP1_CALL_LIMIT: '达到本轮模型调用上限', PP1_MODEL_DISABLED_CACHE_MISSING: '本实例仅使用缓存，当前还没有所需的模型提取或复核缓存',
    MODEL_CACHE_MISSING: '尚无当前资料的模型缓存', USER_CANCELLED: '用户停止任务', PP1_USER_CANCELLED: '用户停止任务',
    PP1_READING_MODEL_DISABLED_CACHE_MISSING: '补充核查所需缓存尚未取得，本实例没有开启模型调用',
    PP1_READING_CALL_LIMIT: '达到本轮模型调用上限，尚待复核的事实继续保留', PP1_READING_TIME_LIMIT: '剩余时间不足以完成下一次核查',
    PP1_REVIEW_DEPTH_LIMIT: '达到连续补提复核上限，剩余事实尚未核实',
    PP1_MEIOSIS_AFFECTED_INDEX_REQUIRED: '先证者的疾病与目标变异尚待核对',
    PP1_MEIOSIS_ORIGIN_CONFLICT: '目标变异的亲本来源存在冲突',
    PP1_MEIOSIS_ORIGIN_UNRESOLVED: '目标变异的亲本来源尚待核对',
    PP1_MEIOSIS_INDEX_CONNECTION_UNRESOLVED: '与先证者的传递关系尚待核对',
    PP1_MEIOSIS_FAMILY_SNAPSHOTS_DIFFER: '同一家系的资料记录有差异，待统一核对',
    PP1_MEIOSIS_STRENGTH_FAMILY_REQUIREMENT: '独立家系数未达到该档要求',
    PP1_ADDITIONAL_FAMILY_INDEPENDENCE_REQUIRED: '家系独立性尚待核对',
    PP1_RULE_ATTACHMENT_REQUIRED: '尚待取得规则附件',
    PP1_RULE_ATTACHMENT_VERIFICATION_FAILED: '规则附件校验未通过',
    PP1_RULE_ATTACHMENT_CONDITIONS_NOT_IMPLEMENTED: '规则附件原件已核对，条件尚待接入',
    PP1_HHT_CURACAO_PHENOTYPE_INCOMPLETE: 'Curaçao 患病资格资料待核',
    PP1_HHT_CURACAO_PHENOTYPE_CONFLICT: 'Curaçao 临床资料存在冲突',
    PP1_ADDED_SOURCE_FACT_REQUIRES_REVIEW: '复核中新发现了可能影响计分的事实，需再次核对原文后才能采用',
    PP1_SOURCE_BATCH_FINISHED: '本批来源检查结束', PP1_TARGET_NOT_IN_PUBLIC_SOURCE: '已获取资料中未核实目标基因和变异写法，未送入模型',
    PP1_DISCOVERY_SEARCH_INCOMPLETE: '检索尚未完成，不能断言没有文献', PP1_NO_CANDIDATES_IN_COMPLETED_SEARCH: '本次已完成的检索没有返回候选论文',
    PP1_PEDIGREE_MODEL_DISABLED_CACHE_MISSING: '家系图还没有可用的独立双读缓存，当前模型开关关闭，未发起新调用',
    PP1_PEDIGREE_PAIR_CALL_CAPACITY: '本轮剩余调用次数不足以完成两次独立读图，原图保留待续核',
    PP1_PEDIGREE_PAIR_TIME_REQUIRED: '本轮剩余时间不足以完成两次独立读图，原图保留待续核',
};
export const pp1StopText = value => stopText[value] || (value ? `此环节尚未完成（${value}）；不表示原文没有资料` : '尚未取得停止原因');
export function validatePp1Task(task, expected = {}) {
    if (task?.schemaVersion !== 'pp1-public-literature-task-v1' || !/^[a-f0-9]{64}$/.test(task.taskId || '') || !Object.hasOwn(statusText, task.status))
        throw new Error('PP1 任务身份或状态无效。');
    if ((expected.taskId && task.taskId !== expected.taskId) ||
        (expected.analysisId && task.analysisId !== undefined && task.analysisId !== expected.analysisId))
        throw new Error('读取的 PP1 任务与所选资料任务或分析不一致。');
    if (task.status === 'READY') {
        const r = task.result;
        if (!['pp1-paper-process-v1', 'pp1-discovery-process-v1'].includes(r?.schemaVersion) ||
            !['PARTIAL', 'EVALUATED'].includes(r.status) || r.businessPublishable !== false ||
            !Number.isSafeInteger(r.modelAttempts) || r.modelAttempts < 0 ||
            (r.schemaVersion === 'pp1-discovery-process-v1' && (r.searchPolicy !== 'LITVAR_ONLY' || !Array.isArray(r.papers))))
            throw new Error('PP1 资料结果不完整，不能显示为已通过。');
    }
    return task;
}
// A sourceCoverage digest is for one captured paper, never the full task result.
export async function readPp1Tasks({ analysisId, initialTaskId }, get = (...args) => axios.get(...args)) {
    if (typeof analysisId !== 'string' || !analysisId.trim()) throw new Error('尚未明确资料所属分析，不能读取 PP1 任务。');
    const linked = initialTaskId !== undefined && initialTaskId !== null;
    if (linked && !/^[a-f0-9]{64}$/.test(initialTaskId)) throw new Error('所选 PP1 资料任务编号无效。');
    const { data } = await get(`${base(analysisId)}/tasks${linked ? `/${initialTaskId}` : ''}`, { timeout: 10000 });
    if (linked) return [validatePp1Task(data, { analysisId, taskId: initialTaskId })];
    if (!Array.isArray(data)) throw new Error('任务清单无效');
    return data.map(task => validatePp1Task(task, { analysisId }));
}
export function pp1ContinuationIssue(task) {
    const permissionIssue = currentLiteratureContinuationIssue(task, 'PP1');
    if (permissionIssue) return permissionIssue;
    if (!['pp1-paper-process-v1', 'pp1-discovery-process-v1'].includes(task.result?.schemaVersion))
        return '尚未取得受支持的资料任务类型；已有资料仍可查看。';
    // Pending work and stored checkpoints are verified by the current server.
    // Historical resume hints are display data, never another permission policy.
    return null;
}
export function pp1CurrentContextIssue(draftIdentity, prepareDeclaration) {
    return typeof draftIdentity === 'string' && draftIdentity.trim() && typeof prepareDeclaration === 'function' ? null :
        '当前仅查看已保存资料任务；尚无本次有效的疾病背景、准备声明及选项身份。请返回分析准备区读取并确认当前背景后，再按原流程准备继续；不会自动补造背景或确认。';
}
export async function preparePp1Continuation({ analysisId, taskId, draftIdentity, prepareDeclaration }, get) {
    const contextIssue = pp1CurrentContextIssue(draftIdentity, prepareDeclaration);
    if (contextIssue) throw new Error(contextIssue);
    const [task] = await readPp1Tasks({ analysisId, initialTaskId: taskId }, get);
    const issue = pp1ContinuationIssue(task);
    if (issue) throw new Error(issue);
    const declaration = prepareDeclaration();
    if (!declaration?.hypotheses?.length) throw new Error('请先选择普通疾病关系或文献分析背景，并明确遗传方式；PP1 不替用户推断疾病。');
    return { task, declaration };
}
export function Pp1ContinuationControl({ task, draftIdentity, prepareDeclaration, disabled, onPrepare, explain = false }) {
    const issue = pp1ContinuationIssue(task) || pp1CurrentContextIssue(draftIdentity, prepareDeclaration);
    if (issue) return explain && task?.status === 'READY' ? <Alert severity="info">{issue}</Alert> : null;
    return <Stack spacing={0.5}>
        {task.resourceRepairRequired === true && <Alert severity="warning">请先修复资源问题，再明确确认继续；本次将重新核对已保留的原件及处理记录。</Alert>}
        <Button disabled={disabled} onClick={() => onPrepare(task)}>准备继续 PP1 未处理资料</Button>
    </Stack>;
}

function Citation({ value, pmcid }) {
    if (!value || (!value.quote && !value.unitId && !value.locator)) return null;
    const paper = pmcid || value.pmcid;
    const scan = value.sourceKind === 'ORIGINAL_PUBLISHED_PAGE' &&
        /^https:\/\/cdn\.ncbi\.nlm\.nih\.gov\/pmc\/blobs\/[a-f0-9]+\/[0-9]+\/[a-f0-9]+\/[A-Za-z0-9_-]+\.png$/.test(value.url || '');
    return <Stack spacing={0.25} sx={{ my: 0.5 }}>
        {value.quote && <Typography variant="body2">原文：“{value.quote}”</Typography>}
        <Typography variant="caption">{[value.originalName, value.locationLabel, value.locator || value.unitId].filter(Boolean).join(' · ')}</Typography>
        {value.sourceKind === 'PEDIGREE_IMAGE' && <Typography variant="body2">家系图依据：{value.figureId}；人物编号及亲缘实线已核对。图中填色不单独作为疾病诊断依据。</Typography>}
        {scan ? <a href={value.url} target="_blank" rel="noreferrer">打开原文第 {value.page} 页</a> :
            /^PMC[1-9][0-9]*$/.test(paper || '') && <a href={`https://pmc.ncbi.nlm.nih.gov/articles/${paper}/${value.sourceKind === 'PEDIGREE_IMAGE' && /^[A-Za-z0-9_-]+$/.test(value.figureId || '') ? `#${value.figureId}` : ''}`} target="_blank" rel="noreferrer">打开 {paper} 原文{value.sourceKind === 'PEDIGREE_IMAGE' ? '家系图' : ''}</a>}
    </Stack>;
}
function Citations({ values = [], pmcid }) {
    const unique = [...new Map(values.filter(Boolean).map(v => [JSON.stringify([v.pmcid || pmcid, v.unitId, v.locator, v.quote]), v])).values()];
    if (!unique.length) return null;
    return <details><summary>查看原文与位置（{unique.length}处）</summary>
        {unique.map((ref, i) => <Citation key={i} value={ref} pmcid={pmcid} />)}
    </details>;
}
function Assessment({ value, pmcid }) {
    if (!value) return null;
    if (isPp1FormalEvaluation(value)) return <Alert severity="info">本资料任务返回正式条件家系似然预评价；完整保存结论、身份和来源尚需一并核查。请在已保存结果中查看正式详情，此处不展示授分或旧版事件点数。</Alert>;
    const e = value, strength = { SUPPORTING: '支持级', MODERATE: '中等级', STRONG: '强级' }[e.strength];
    return <Stack spacing={1}>
        <Typography>{e.status === 'MET' ? `PP1 ${strength || '强度待核实'}（${e.awardedPP1Points} 分）` :
            e.status === 'NOT_MET' ? '已评价，但有效贡献未达到支持级条件' : '当前尚不能判定 PP1'}</Typography>
        {e.status !== 'NOT_EVALUATED' && e.rawSegregationPoints !== undefined && <Typography variant="body2">共分离原始贡献 {e.rawSegregationPoints} 分；本次位点剩余容量 {e.remainingLocusCapacity} 分；实际采用 {e.awardedPP1Points} 分。</Typography>}
        {!!e.clinicalAssessments?.length && <Stack spacing={1}>
            <Typography variant="subtitle2">已核实的疾病状态依据</Typography>
            {e.clinicalAssessments.map((item, i) => <Paper variant="outlined" key={i} sx={{ p: 1 }}>
                <Typography variant="body2">成员 {item.memberLabel}：{item.status === 'AFFECTED' ? '原文报告符合诊断标准，已与本次疾病背景对应。' : '诊断标准陈述与其他临床资料存在冲突，暂不采用患病结论。'}</Typography>
                <Typography variant="body2">报告的标准：{item.reportedCriteria}。这项事实不等于已满足 PP1 的全部条件。</Typography>
                <Citations values={item.sourceRefs} pmcid={pmcid} />
                <details><summary>查看疾病规范依据</summary>
                    <Typography variant="body2">{item.knowledgeBinding?.specificationKey} · {item.knowledgeBinding?.scope?.diseaseId}</Typography>
                    <Typography variant="body2">{item.knowledgeBinding?.sourceText}</Typography>
                    <Typography variant="caption">{item.knowledgeBinding?.jsonPointer}</Typography>
                    {/^https:\/\//.test(item.knowledgeBinding?.source?.sourceUrl || '') && <a href={item.knowledgeBinding.source.sourceUrl} target="_blank" rel="noreferrer">打开已引用的疾病规范</a>}
                </details>
            </Paper>)}
        </Stack>}
        {!!e.needs?.length && <Stack spacing={1}>
            <Typography variant="subtitle2">仍缺什么、为什么影响判定</Typography>
            {e.needs.map((need, i) => <Paper variant="outlined" key={need.needId || i} sx={{ p: 1 }}>
                <EvaluationNeedDifferences needs={[need]} explain={pp1StopText} heading={false} />
                <Citations values={need.sourceLocators} pmcid={pmcid} />
            </Paper>)}
        </Stack>}
        {!!e.events?.length && <details><summary>已采用的家系事件与出处（{e.events.length}）</summary>
            {e.events.map(event => <Stack key={event.eventId} sx={{ my: 1 }}>
                <Typography variant="body2">{event.informativeMeioses === 1
                    ? [event.familyLabel, [event.parentLabel, event.memberLabel].filter(Boolean).join(' → '),
                        event.meiosisBasis === 'CONDITIONAL_SIBSHIP' ? `同胞条件传递 1 次（参考 ${event.conditionalReferenceLabel}）` :
                            event.meiosisBasis === 'DIRECT_PARENT_CHILD' ? '直接亲子传递 1 次' : '信息性传递 1 次'].filter(Boolean).join(' · ')
                    : `${[event.familyLabel, event.memberLabel || event.memberKey].filter(Boolean).join(' · ')} · 原始贡献 ${event.rawPoints} 分`}</Typography>
                {event.kind === 'EXPERT_AFFECTED_RELATIVES_COUNT' && <Typography variant="body2">
                    已排除先证者，共 {event.affectedRelativesCount} 位患病携带亲属；按专家人数阈值换算证据强度，并非每人各计一分。
                </Typography>}
                {Number.isInteger(event.obligateCarrierCount) && event.obligateCarrierCount > 0 && <Typography variant="body2">
                    其中必然携带者 {event.obligateCarrierCount} 位{Array.isArray(event.obligateCarrierLabels) && event.obligateCarrierLabels.length ? ` · ${event.obligateCarrierLabels.join('、')}` : ''}
                </Typography>}
                {event.sourceRefs?.map((ref, i) => <Citation key={i} value={ref} pmcid={pmcid || event.pmcid} />)}
            </Stack>)}
        </details>}
    </Stack>;
}
export function Pp1PaperProgress({ paper, historical = false, showDiagnostics = true }) {
    const plan = paper?.reading?.readingPlan || paper?.readingPlan;
    const material = paper?.materialCoverage || plan?.materialCoverage;
    return <Stack spacing={0.5}>
        <Typography variant="subtitle2">{paper.pmcid}</Typography>
        {showDiagnostics && <LiteratureStageDiagnostics value={historical ? null : pp1ReadingDiagnostic(paper.readingState, paper)}
            explain={pp1StopText} historical={historical} />}
        {paper.stopReason && <Typography variant="body2">{pp1StopText(paper.stopReason)}</Typography>}
        {plan && <Typography variant="body2">已复核 {plan.chunks?.filter(c => c.status === 'REVIEWED').length || 0} / {plan.chunks?.length || 0} 个资料块；尚未读取 {plan.remainingChunks} 个。未读不等于原文没有信息。</Typography>}
        {!!plan?.pedigreeReview?.registeredFigures && <Typography variant="body2">家系图缓存：已登记 {plan.pedigreeReview.registeredFigures} 份原图或局部视图，已核实 {plan.pedigreeReview.reviewedPairs} 组独立双读结果；同图复核不会重复计分。</Typography>}
        {plan?.pedigreeScheduling?.map((item,i) => <Typography key={i} variant="body2">{item.familyLabel}：{item.status === 'VERIFIED' ? `家系关系已复核，本次模型调用 ${item.modelAttempts} 次` : pp1StopText(item.reasonCode)}</Typography>)}
        {!!plan?.chunks?.some(c => c.status === 'ATTEMPTED') && <Typography variant="body2">另有 {plan.chunks.filter(c => c.status === 'ATTEMPTED').length} 个资料块已尝试处理但未完成复核，不计为已复核。</Typography>}
        {!!material?.unparsedAttachments && <Alert severity="warning">
            已取得但尚未解析 {material.unparsedAttachments} 个附件文件，可能包括家系图及其缩略图；这些文件不计入上方可读资料块，也不能据此认定原文没有所需信息。
            {!!material.unparsedFiles?.length && <details><summary>查看尚未解析的附件</summary>
                {material.unparsedFiles.map((file, i) => <Typography key={file.sha256 || i} variant="body2">{file.originalName} · 当前读取程序尚不能提取其中的信息</Typography>)}
            </details>}
        </Alert>}
        {!!plan?.pendingSourceReview?.claimIds?.length && <Typography variant="body2">已有资料中仍有 {plan.pendingSourceReview.claimIds.length} 条可能影响计分的补提事实待复核；资料块已读不代表其中每条事实都已核实。</Typography>}
        <Assessment value={paper.evaluation} pmcid={paper.pmcid} />
    </Stack>;
}
function Pp1SavedSourceProgress({ value }) {
    const rows = Array.isArray(value.sourceCoverage) ? value.sourceCoverage : [];
    if (!rows.length) return null;
    const available = rows.slice(0, 32).map((row) => ({ row, view: savedPp1ReadingDiagnostic(row, value) })).filter((item) => item.view);
    return <Stack spacing={1}>
        {available.length ? <Typography variant="subtitle2">本结果记录的资料处理与阻断</Typography> : null}
        {available.map(({ row, view }, index) => <Stack key={index} spacing={0.5}>
            <Typography variant="body2">{row.pmcid}</Typography>
            <LiteratureStageDiagnostics value={view} compact explain={pp1StopText} />
        </Stack>)}
        {!available.length && <LiteratureStageDiagnostics historical />}
        {rows.length > 32 && <Typography variant="body2">另有 {rows.length - 32} 份资料未在摘要展示，完整记录见下方资料区。</Typography>}
    </Stack>;
}
export function Pp1SavedFacts({ value, criterion }) {
    if (value?.schemaVersion !== 'pp1-selected-facts-v1') return null;
    if (isPp1FormalEvaluation(value.evaluation)) return <Stack spacing={1}>
        <EvaluationNeedDifferences needs={value.evaluation.needs} explain={pp1StopText} />
        <Pp1SavedSourceProgress value={value} />
        <Pp1FormalFacts value={value} criterion={criterion} />
    </Stack>;
    return <Stack spacing={1}>
        <Assessment value={value.evaluation} />
        <Pp1SavedSourceProgress value={value} />
        {!!value.sourceCoverage?.length && <details><summary>资料范围与停止原因（{value.sourceCoverage.length} 项）</summary>
            {value.sourceCoverage.map((p, i) => <Pp1PaperProgress key={i} paper={p} historical showDiagnostics={false} />)}
        </details>}
        {!!value.qualifications?.length && <details><summary>已核实与尚未核实的原文事实</summary>
            {value.qualifications.map((q, i) => <Stack key={i} spacing={1}>
                <Typography variant="subtitle2">{q.sourceIdentity?.pmcid}</Typography>
                {!!q.memberCorrespondence?.length && <Typography variant="body2">正文称谓与图中成员对应：{[...new Set(q.memberCorrespondence.flatMap(c => Object.entries(c.aliases || {}).map(([label,id]) => `${label} → ${id}`)))].join('；')}。同一成员只计一次。</Typography>}
                {[...(q.observations || []), ...(q.unresolved || [])].map((fact, j) => <Stack key={j}>
                    <Typography variant="body2">{fact.reasonCode ? pp1StopText(fact.reasonCode) : '程序已核实的原文事实；是否计分见上方事件。'}</Typography>
                    {fact.sourceRefs?.map((ref, k) => <Citation key={k} value={ref} pmcid={q.sourceIdentity?.pmcid} />)}
                </Stack>)}
            </Stack>)}
        </details>}
        {!!value.excludedTasks?.length && <details><summary>未采用的资料任务（{value.excludedTasks.length}）</summary>
            {value.excludedTasks.map((t, i) => <Typography key={i} variant="body2">{pp1StopText(t.reasonCode)}</Typography>)}
        </details>}
    </Stack>;
}
export default function Pp1LiteraturePanel({ analysisId, draftIdentity, prepareDeclaration, candidates = [], initialTaskId, onReturn }) {
    const [tasks, setTasks] = useState([]), [draft, setDraft] = useState(null), [choice, setChoice] = useState('');
    const [confirmed, setConfirmed] = useState(false), [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false);
    const [message, setMessage] = useState(''), [parent, setParent] = useState(null);
    const [loading, setLoading] = useState(true), [linkedChild, setLinkedChild] = useState(null);
    const [taskIdentity, setTaskIdentity] = useState(null);
    const linked = initialTaskId !== undefined && initialTaskId !== null;
    const identity = JSON.stringify([analysisId, draftIdentity, initialTaskId]), current = useRef(identity); current.current = identity;
    const activeTaskId = linkedChild?.identity === identity ? linkedChild.taskId : initialTaskId;
    const visibleTasks = taskIdentity === identity ? tasks : [];
    const refresh = async (taskId = activeTaskId) => {
        const key = identity;
        setLoading(true);
        try {
            const rows = await readPp1Tasks({ analysisId, initialTaskId: linked ? taskId : undefined });
            if (current.current === key) { setTasks(rows); setTaskIdentity(key); setUncertain(false); setMessage(''); }
        } catch (error) { if (current.current === key) { setTasks([]); setMessage(`PP1 状态读取未完成：${error.response?.data?.message || error.message}`); } }
        finally { if (current.current === key) setLoading(false); }
    };
    useEffect(() => {
        current.current = identity;
        setTasks([]); setDraft(null); setConfirmed(false); setBusy(false); setParent(null); setUncertain(false); setMessage(''); setLinkedChild(null);
        refresh(initialTaskId);
        return () => { if (current.current === identity) current.current = null; };
    }, [identity]); // eslint-disable-line react-hooks/exhaustive-deps
    useEffect(() => {
        if (!tasks.some(pending)) return undefined;
        let disposed = false, timer;
        const poll = async () => { await refresh(); if (!disposed) timer = setTimeout(poll, 5000); };
        timer = setTimeout(poll, 5000);
        return () => { disposed = true; clearTimeout(timer); };
    }, [tasks, identity]); // eslint-disable-line react-hooks/exhaustive-deps
    const prepare = async task => {
        const key = identity;
        setBusy(true);
        try {
            const prepared = task ? await preparePp1Continuation({ analysisId, taskId: task.taskId, draftIdentity, prepareDeclaration }) :
                { task: null, declaration: prepareDeclaration() };
            if (!prepared.declaration?.hypotheses?.length) throw new Error('请先选择普通疾病关系或文献分析背景，并明确遗传方式；PP1 不替用户推断疾病。');
            if (current.current === key) {
                setDraft(prepared.declaration); setChoice('0'); setParent(prepared.task); setConfirmed(false); setMessage('');
            }
        } catch (error) { if (current.current === key) { setDraft(null); setParent(null); setConfirmed(false); setMessage(error.message); } }
        finally { if (current.current === key) setBusy(false); }
    };
    const start = async () => {
        if (!confirmed || busy || uncertain || !draft) return;
        const key = identity, h = draft.hypotheses[Number(choice)]?.hypothesis; if (!h) return;
        setBusy(true); setConfirmed(false);
        let posted = false;
        try {
            if (parent) {
                const [live] = await readPp1Tasks({ analysisId, initialTaskId: parent.taskId });
                const issue = pp1ContinuationIssue(live) || pp1CurrentContextIssue(draftIdentity, prepareDeclaration);
                if (issue) throw new Error(issue);
                if (current.current !== key) return;
            }
            posted = true;
            const { data } = await axios.post(`${base(analysisId)}/tasks`, { declaration: draft, candidateId: h.candidateId,
                hypothesisId: h.hypothesisId, confirmPublicModel: true, ...(parent ? { parentTaskId: parent.taskId } : {}) }, { timeout: 15000 });
            const task = validatePp1Task(data, { analysisId });
            if (current.current === key) {
                setTasks(old => linked ? [task] : [task, ...old.filter(t => t.taskId !== task.taskId)]); setTaskIdentity(key);
                if (linked) setLinkedChild({ identity, taskId: task.taskId });
                setDraft(null); setParent(null);
            }
        } catch (error) { if (current.current === key) { setUncertain(posted); setMessage(posted ? `提交结果尚未确认，请先只读刷新，避免重复调用。${error.response?.data?.message || ''}` : `尚未提交续读：${error.message}`); } }
        finally { if (current.current === key) setBusy(false); }
    };
    const cancel = async task => {
        const key = identity; setBusy(true);
        try {
            const { data } = await axios.post(`${base(analysisId)}/tasks/${task.taskId}/cancel`, {}, { timeout: 15000 });
            const next = validatePp1Task(data, { analysisId, taskId: task.taskId });
            if (current.current === key) setTasks(old => old.map(t => t.taskId === next.taskId ? next : t));
        } catch (error) { if (current.current === key) setMessage(`停止状态待核对：${error.message}`); }
        finally { if (current.current === key) setBusy(false); }
    };
    return <Paper variant="outlined" sx={{ p: 2, my: 2 }}><Stack spacing={1}>
        <Typography variant="h6">PP1 家系共分离文献</Typography>
        {linked && activeTaskId !== initialTaskId && <Alert severity="info">当前显示续读任务。</Alert>}
        {linked && pp1CurrentContextIssue(draftIdentity, prepareDeclaration) && <Alert severity="info">{pp1CurrentContextIssue(draftIdentity, prepareDeclaration)}</Alert>}
        <Stack direction="row" spacing={1}>
            {!linked && <Button disabled={busy || loading || uncertain || tasks.some(pending) || typeof prepareDeclaration !== 'function'} onClick={() => prepare(null)}>准备 PP1 文献任务</Button>}
            <Button disabled={busy || loading} onClick={() => refresh()}>只读刷新 PP1 任务</Button>
            {typeof onReturn === 'function' && <Button onClick={onReturn}>返回证据详情</Button>}
        </Stack>
        {draft && <>
            <TextField select label="候选变异与疾病背景" value={choice} onChange={event => { setChoice(event.target.value); setConfirmed(false); }}>
                {draft.hypotheses.map(({ hypothesis: h }, i) => <MenuItem key={`${h.candidateId}:${h.hypothesisId}`} value={String(i)}>
                    {candidates.find(c => c.candidateId === h.candidateId)?.geneSymbol || h.candidateId} · {h.mondoId || h.hypothesisId}
                </MenuItem>)}
            </TextField>
            <Typography variant="body2">按实例开关使用公开检索和模型；关闭时仅复用缓存。启用模型可能产生费用。仅传输必要检索词和公开论文内容，病例声明留在本地。</Typography>
            {parent && <Alert severity="info">继续此任务的未处理资料；当前变异、疾病或来源变化时，服务端会拒绝串用。</Alert>}
            <FormControlLabel control={<Checkbox checked={confirmed} disabled={busy} onChange={event => setConfirmed(event.target.checked)} />} label="确认本次公开资料获取与模型提取范围" />
            <Button disabled={!confirmed || busy || uncertain || tasks.some(pending)} onClick={start}>启动 PP1 资料任务</Button>
        </>}
        {loading && <Alert severity="info">正在只读加载 PP1 资料任务与处理进度…</Alert>}
        {!loading && !message && !tasks.length && <Alert severity="info">当前分析尚无 PP1 资料任务；没有启动任何新任务。</Alert>}
        {message && <Alert severity="warning">{message}</Alert>}
        {visibleTasks.map(task => <Paper variant="outlined" key={task.taskId} sx={{ p: 1 }}><Stack spacing={1}>
            <Typography>{statusText[task.status]} · {task.taskId.slice(0, 12)}</Typography>
            {task.reasonCode && <Typography variant="body2">{pp1StopText(task.reasonCode)}</Typography>}
            {pending(task) && !linked && <Button disabled={busy} onClick={() => cancel(task)}>停止 PP1 资料任务</Button>}
            {task.result && <>
                <Typography variant="body2">本次模型请求 {task.result.modelAttempts} 次</Typography>
                {task.result.localCacheDiscovery && <Typography variant="body2">关联本地已审资料 {task.result.localCacheDiscovery.papers?.length || 0} 篇</Typography>}
                {task.result.localCacheDiscovery?.issues?.map((reason,i) => <Alert key={`local-${i}`} severity="warning">本地资料索引未完整核实：{reason}；不等于没有论文。</Alert>)}
                <LiteraturePriorityView result={task.result} />
                {(task.result.papers || [task.result]).map(p => <Pp1PaperProgress key={p.pmcid} paper={p} />)}
                {task.result.issues?.map((reason, i) => <Alert key={i} severity="warning">{pp1StopText(reason)}</Alert>)}
                <Pp1ContinuationControl task={task} draftIdentity={draftIdentity} prepareDeclaration={prepareDeclaration}
                    disabled={busy || loading || uncertain || tasks.some(pending)} onPrepare={prepare} explain={linked} />
            </>}
        </Stack></Paper>)}
    </Stack></Paper>;
}
