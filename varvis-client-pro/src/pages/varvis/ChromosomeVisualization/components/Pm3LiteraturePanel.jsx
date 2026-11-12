import React, { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { Alert, Button, Checkbox, FormControlLabel, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import { pm3Reason, pm3Field } from '../utils/pm3Explanation';
import LiteraturePriorityView from './LiteraturePriorityView';
import LiteratureStageDiagnostics, { LiteratureAcquisitionDiagnostics } from './LiteratureStageDiagnostics';
import { currentLiteratureContinuationIssue, literatureAcquisitionDiagnostic, pm3CheckpointDiagnostic,
    savedPm3AcquisitionDiagnostic, savedPm3CheckpointDiagnostic, savedPm3TaskProblem } from '../utils/acmgLiteratureDiagnostics';
import { formatVariantCoordinate } from '../utils/variantCoordinate';

const pending = (task) => ['QUEUED', 'RUNNING'].includes(task.status);
const nativeFactText = (fact) => fact.producer?.startsWith('SOURCE_NATIVE_')
    ? '程序直接核对原文及关联位置；不代表完成模型复核，也不等于已满足计分条件。' : null;
const caseTitle = (row) => row.identityKind === 'ANONYMOUS_SOURCE_ROW_NOT_PATIENT_ID' || /^SRC-[a-f0-9]{12}-/.test(row.caseLabel || '')
    ? `原文观察 ${row.caseLabel}（未公开患者编号；不能据此认定独立病例）` : `病例 ${row.caseLabel}`;
const sourceLocation = (citation = {}) => [citation.originalName, citation.archiveMemberName,
    citation.page ? `第 ${citation.page} 页` : null, citation.sheet ? `工作表 ${citation.sheet}` : null,
    citation.cell ? `单元格 ${citation.cell}` : null].filter(Boolean).join(' · ') || citation.unitId;
function Pm3SourceCitation({ citation }) {
    if (!citation) return null;
    return <><span>{sourceLocation(citation)}</span>{citation.originalName && <details>
        <summary>精确原文定位</summary><Typography variant="caption">{citation.unitId}</Typography>
    </details>}</>;
}
const base = (id) => `/variant-analyses/${encodeURIComponent(id)}/acmg/literature/pm3`;
export const validatePm3Task = (task) => {
    const loop = task?.result?.readingLoop;
    if (loop && (loop.schemaVersion !== 'pm3-reading-loop-v1' || loop.fullPaperRead !== false ||
        !['CONTINUE_READING', 'INCOMPLETE', 'DEPENDENCY_UNRESOLVED', 'CONFLICT_REQUIRES_REVIEW', 'SUFFICIENT_WITHIN_CHECKED_SCOPE', 'SEARCHED_SCOPE_INSUFFICIENT'].includes(loop.state) ||
        !Number.isSafeInteger(loop.roundCount) || loop.roundCount < 1 || !Array.isArray(loop.rounds) || loop.rounds.length !== loop.roundCount ||
        !Array.isArray(loop.feedback?.papers))) throw new Error('PM3 自动续读回执无效。');
    const population = task?.result?.populationQualification;
    const populationEvaluated = Number.isSafeInteger(population?.pm2EvaluatorCalls) && population.pm2EvaluatorCalls > 0;
    const assessment = task?.result?.pm3Assessment;
    const pm3Calculated = Number.isSafeInteger(assessment?.pm3CalculatorCalls) && assessment.pm3CalculatorCalls > 0;
    if (assessment && (!['pm3-literature-assessment-v1', 'pm3-literature-assessment-v2'].includes(assessment.schemaVersion) || assessment.businessPublishable !== false ||
        assessment.pm3FinalBusinessExecution !== false || assessment.use !== 'GENERAL_POLICY_CANDIDATE_NOT_ADOPTED' ||
        assessment.policyApplicability !== 'SPECIFICATION_SELECTION_PENDING' ||
        !Number.isSafeInteger(assessment.pm3CalculatorCalls) || assessment.pm3CalculatorCalls < 0 ||
        task.result.pm3ScientificExecution !== pm3Calculated)) throw new Error('PM3 资料计算回执无效。');
    if (task?.schemaVersion !== 'pm3-public-literature-task-v1' || !/^[a-f0-9]{64}$/.test(task.taskId) ||
        !['QUEUED', 'RUNNING', 'READY', 'FAILED', 'CANCELLED'].includes(task.status) ||
        task.scientificExecution !== (populationEvaluated || pm3Calculated) || task.modelCalls !== null ||
        (task.pm3ScientificExecution !== undefined && task.pm3ScientificExecution !== pm3Calculated)) throw new Error('PM3 任务回执不相容。');
    if (population && (population.schemaVersion !== 'pm3-literature-rarity-supply-v1' ||
        population.pm3EvaluatorCalls !== 0 || task.result.pm3ScientificExecution !== pm3Calculated ||
        ['providerCalls', 'allSitesCalls', 'pm2EvaluatorCalls'].some((key) => !Number.isSafeInteger(population[key]) || population[key] < 0)))
        throw new Error('PM3 人口资格回执无效。');
    if (task.status === 'READY' && (!['pm3-discovery-process-v1', 'pm3-paper-process-v1'].includes(task.result?.schemaVersion) ||
        task.result.businessPublishable !== false || task.result.scientificExecution !== (populationEvaluated || pm3Calculated) ||
        !Number.isSafeInteger(task.result.modelCalls) || task.result.modelCalls < 0)) throw new Error('PM3 资料结果边界无效。');
    return task;
};

const pm3Pin = (value) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
export const validatePm3SourceReference = (reference, analysisId, taskId) => {
    if (!reference) return;
    if (reference.bindingScope !== 'GENE_AND_SELECTED_ALLELE_NOT_HYPOTHESIS_SPECIFIC' ||
        reference.analysisId !== analysisId || reference.taskId !== taskId ||
        !pm3Pin(reference.taskId) || !pm3Pin(reference.selectionSha256) || !pm3Pin(reference.capturedResultSha256) ||
        (reference.kind !== undefined && reference.kind !== 'PM3_LITERATURE') ||
        (reference.pmcid !== undefined && !/^PMC[0-9]+$/.test(reference.pmcid)))
        throw new Error('PM3 保存来源的任务绑定无效；未读取其他任务。');
};
export const readPm3Task = async ({ analysisId, taskId, reference, signal }, client = axios) => {
    if (typeof analysisId !== 'string' || !analysisId.trim() || !pm3Pin(taskId)) throw new Error('PM3 来源任务标识无效。');
    validatePm3SourceReference(reference, analysisId, taskId);
    const { data } = await client.get(`${base(analysisId)}/tasks/${taskId}`, { signal, timeout: 10000 });
    const task = validatePm3Task(data);
    if (task.taskId !== taskId || (task.analysisId !== undefined && task.analysisId !== analysisId))
        throw new Error('PM3 返回了其他分析或任务，已停止后续操作。');
    return task;
};
export const pm3SourceBindingState = (task, reference) => {
    if (!reference) return { matches: true };
    if (!task.resultSha256 || !task.result?.selectionSha256)
        return { matches: false, reason: '当前回执未提供完整的保存来源绑定，仅可阅读。' };
    if (task.result.selectionSha256 !== reference.selectionSha256 || task.resultSha256 !== reference.capturedResultSha256)
        return { matches: false, reason: '当前任务与保存时的选择或资料摘要不同；可阅读当前资料，不能按原保存引用继续。保存的证据结论未改变。' };
    if (reference.pmcid && ![task.result, ...(task.result.papers || [])].some((paper) => paper.pmcid === reference.pmcid))
        return { matches: false, reason: '当前任务的论文与保存来源不一致，仅可阅读。' };
    return { matches: true };
};
export const pm3ContinuationState = (task, reference) => {
    if (pending(task)) return { allowed: false, reason: '任务仍在执行，仅只读刷新状态。' };
    const binding = pm3SourceBindingState(task, reference);
    if (!binding.matches) return { allowed: false, reason: binding.reason };
    const permissionIssue = currentLiteratureContinuationIssue(task, 'PM3');
    if (permissionIssue) return { allowed: false, reason: permissionIssue };
    if (!['pm3-paper-process-v1', 'pm3-discovery-process-v1'].includes(task.result?.schemaVersion))
        return { allowed: false, reason: '尚未取得受支持的资料任务类型；已有资料仍可查看。' };
    return { allowed: true, reason: '服务端已允许此任务继续处理资料；请核对当前范围并明确确认后继续。' };
};
export const preparePm3Continuation = async ({ analysisId, taskId, reference, topN }, client = axios) => {
    if (!Number.isSafeInteger(topN) || topN < 1) throw new Error('未取得原候选范围，仅可阅读来源任务，不能使用默认范围继续。');
    const task = await readPm3Task({ analysisId, taskId, reference }, client);
    const eligibility = pm3ContinuationState(task, reference);
    if (!eligibility.allowed) throw new Error(eligibility.reason);
    if (!pm3Pin(task.resultSha256)) throw new Error('PM3 缺少父任务资料摘要，不能绑定继续确认。');
    const { data: options } = await client.get(`${base(analysisId)}/options`, { params: { topN }, timeout: 30000 });
    if (options.schemaVersion !== 'pm3-literature-options-v1' || !pm3Pin(options.selectionSha256) ||
        !Array.isArray(options.selection?.candidates)) throw new Error('PM3 候选回执无效。');
    if (task.result.selectionSha256 !== options.selectionSha256) throw new Error('当前 Top-N 与原任务不同，请返回对应候选范围后再继续。');
    return { task, options, confirmation: { analysisId, taskId, topN: Number(topN), enabled: options.enabled === true,
        selectionSha256: options.selectionSha256, resultSha256: task.resultSha256 } };
};
export const continuePm3Task = async ({ analysisId, taskId, reference, topN, confirmation, confirmed, isCurrent = () => true }, client = axios) => {
    if (confirmed !== true || !confirmation || confirmation.enabled !== true || confirmation.analysisId !== analysisId || confirmation.taskId !== taskId ||
        !Number.isSafeInteger(topN) || topN < 1 || confirmation.topN !== Number(topN) ||
        !pm3Pin(confirmation.resultSha256) || !pm3Pin(confirmation.selectionSha256))
        throw new Error('请先准备并确认当前 PM3 任务的继续范围。');
    const task = await readPm3Task({ analysisId, taskId, reference }, client);
    const eligibility = pm3ContinuationState(task, reference);
    if (!eligibility.allowed) throw new Error(eligibility.reason);
    if (task.resultSha256 !== confirmation.resultSha256 || task.result.selectionSha256 !== confirmation.selectionSha256)
        throw new Error('PM3 父任务在确认后发生变化，请重新只读核对并准备。');
    if (!isCurrent()) throw new Error('PM3 确认页面已关闭或范围已改变，未提交继续请求。');
    try {
        const { data } = await client.post(`${base(analysisId)}/tasks/${taskId}/continue`, {
            topN: Number(topN), selectionSha256: confirmation.selectionSha256, confirmPublicModel: true,
        }, { timeout: 15000 });
        return validatePm3Task(data);
    } catch (error) { error.pm3SubmissionAttempted = true; throw error; }
};

export function Pm3ReadingProgress({ value }) {
    if (!value) return null;
    const labels = { CONTINUE_READING: '仍有相关资料待继续核查', INCOMPLETE: '资料检查尚未完成',
        DEPENDENCY_UNRESOLVED: '仍缺外部资料或程序支持，继续读本文无法解决',
        CONFLICT_REQUIRES_REVIEW: '已发现相互冲突的资料，需要核实', SUFFICIENT_WITHIN_CHECKED_SCOPE: '已检查范围内具备判定资料',
        SEARCHED_SCOPE_INSUFFICIENT: '已检查范围内仍未补齐判定资料' };
    const availability = { EXTRACTION_INCOMPLETE: '事实提取未完成', READING_INCOMPLETE: '相关资料尚未读完',
        VERIFICATION_INCOMPLETE: '原文事实尚未核验完成', CHECKED_SCOPE_INSUFFICIENT: '已检查范围内资料不足',
        CURRENT_REQUIREMENTS_CHECKED: '当前所需资料已检查' };
    return <Alert severity={value.state === 'SUFFICIENT_WITHIN_CHECKED_SCOPE' ? 'info' : 'warning'}>
        <Typography>{labels[value.state] || '续读状态待核实'}；已完成 {value.roundCount} 轮。</Typography>
        {(value.stopReason || value.feedback?.stopReason) && <Typography variant="body2">停止原因：{pm3Reason(value.stopReason || value.feedback.stopReason)}</Typography>}
        {value.feedback?.papers?.map((paper) => <Stack key={paper.pmcid}>
            <Typography variant="body2">{paper.pmcid}：复核完成 {paper.completedReviewChunks?.length || 0} 块，相关待读 {paper.pendingRelevantChunks?.length || 0} 块，提取或复核未完成 {paper.blockedReviewChunks?.length || 0} 块。</Typography>
            {availability[paper.factAvailability] && <Typography variant="body2">资料状态：{availability[paper.factAvailability]}。</Typography>}
            {paper.needs?.length > 0 && <Typography variant="body2">待核实：{[...new Set(paper.needs.map(n => pm3Reason(n.reasonCode)))].join('；')}</Typography>}
        </Stack>)}
    </Alert>;
}

function Pm3SavedSourceProgress({ value }) {
    const tasks = Array.isArray(value.taskCoverage) ? value.taskCoverage : [];
    const papers = Array.isArray(value.paperCoverage) ? value.paperCoverage : [];
    const acquisitions = tasks.slice(0, 128).map((row) => ({ row, view: savedPm3AcquisitionDiagnostic(row, value) })).filter((item) => item.view);
    const stages = papers.slice(0, 32).map((row) => ({ row, view: savedPm3CheckpointDiagnostic(row, value) })).filter((item) => item.view);
    const problems = tasks.slice(0, 128).map((row) => ({ row, problem: savedPm3TaskProblem(row, value) })).filter((item) => item.problem);
    return <Stack spacing={1}>
        {(acquisitions.length > 0 || stages.length > 0 || problems.length > 0) && <Typography variant="subtitle2">本结果记录的资料处理与阻断</Typography>}
        {problems.map(({ row, problem }, index) => <Alert key={`problem-${index}`} severity="warning">
            <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>来源任务 {row.taskId}</Typography>
            <Typography variant="body2">保存时的阻断：{({ RECONCILIATION: '失败调用的执行与费用记录尚待核对，不能据此再次调用。',
                EXECUTION_FAILURE: '资料处理执行失败，已取得部分保留。', SOURCE_CONFLICT: '本任务记录了来源冲突，尚待核对具体资料。',
                DEPENDENCY: '本任务仍有尚未满足的资料或方法依赖。' })[problem.kind]}</Typography>
            {problem.stop && <Typography variant="body2">{pm3Reason(problem.stop)}</Typography>}
            <Typography variant="caption">此处显示保存时的资料记录；能否继续须另读当前任务许可，历史读取不启动新工作。</Typography>
        </Alert>)}
        {acquisitions.map(({ row, view }, index) => <Stack key={`task-${index}`} spacing={0.5}>
            <Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>来源任务 {row.taskId}</Typography>
            <LiteratureAcquisitionDiagnostics value={view} explain={pm3Reason} />
        </Stack>)}
        {stages.map(({ row, view }, index) => <Stack key={`paper-${index}`} spacing={0.5}>
            <Typography variant="body2">{row.pmcid}</Typography><LiteratureStageDiagnostics value={view} compact explain={pm3Reason} />
        </Stack>)}
        {value.taskCoverageProjection?.omitted > 0 && <Typography variant="body2">来源任务原记录共 {value.taskCoverageProjection.total} 项，另有 {value.taskCoverageProjection.omitted} 项未在此展示，完整原记录保留。</Typography>}
        {papers.length > 32 && <Typography variant="body2">另有 {papers.length - 32} 份论文的阶段摘要未在此展示，完整记录见下方资料区。</Typography>}
        {!acquisitions.length && !stages.length && papers.length > 0 && <LiteratureStageDiagnostics historical />}
    </Stack>;
}
export function Pm3SavedFacts({ value }) {
    if (value?.schemaVersion !== 'pm3-selected-facts-v1') return null;
    const group = value.assessment, calculation = group?.calculation;
    return <Stack spacing={1}>
        {calculation && <Typography>已核验文献计入 {calculation.quarterPoints / 4} 分；疾病背景 {group.diseaseId}。不同论文未证明病例独立时不直接相加。</Typography>}
        <Pm3SavedSourceProgress value={value} />
        {value.excludedObservations?.length > 0 && <details><summary>未采用的观察及原因（{value.excludedObservations.length}）</summary>
            {value.excludedObservations.map((r, i) => <Typography variant="body2" key={i}>{r.pmcid} · {caseTitle(r)}：{pm3Reason(r.reasonCode)}</Typography>)}
        </details>}
        {value.excludedTasks?.length > 0 && <Typography variant="body2">部分资料任务未采用：{[...new Set(value.excludedTasks.map(r => pm3Reason(r.reasonCode)))].join('；')}</Typography>}
        {value.paperCoverage?.length > 0 && <details><summary>论文阅读进度与资料依据（{value.paperCoverage.length} 篇）</summary>
            {value.paperCoverage.map((p, i) => <Stack key={i} spacing={0.5}>
                <Pm3ReadingProgress value={p.readingLoop} />
                <LiteratureStageDiagnostics value={savedPm3CheckpointDiagnostic(p, value)} explain={pm3Reason} />
                <Typography variant="body2">{p.pmcid}：已复核 {p.completedReviewChunks?.length || 0} / {p.chunkCount ?? '未知'} 批。结论仅覆盖已完成内容，未取得病例不等于整篇没有病例。</Typography>
                {p.issues?.length > 0 && <Alert severity="warning">{p.issues.map(pm3Reason).join('；')}</Alert>}
                <Pm3MaterialAssessments items={p.materialAssessments} />
                {p.unreviewedExtraction?.cases?.length > 0 && <Pm3PaperFacts paper={{ pmcid: p.pmcid, chunkCount: p.chunkCount,
                    completedReviewChunks: p.completedReviewChunks, observations: { unreviewedExtraction: p.unreviewedExtraction, cases: [] } }} />}
            </Stack>)}
        </details>}
        {calculation?.issues?.length > 0 && <Typography variant="body2">尚缺或存在冲突：{calculation.issues.map(pm3Reason).join('；')}</Typography>}
        {value.caseFacts?.length > 0 && <details><summary>病例事实与原文位置（{value.caseFacts.length} 条观察）</summary>
            {value.caseFacts.map((r, i) => <Paper variant="outlined" sx={{ p: 1 }} key={i}>
                <Typography><a href={`https://pmc.ncbi.nlm.nih.gov/articles/${/^PMC[1-9][0-9]*$/.test(r.pmcid) ? r.pmcid : ''}/`} target="_blank" rel="noreferrer">{r.pmcid}</a> · {caseTitle(r)}</Typography>
                {r.interpretationStatus === 'CASE_SEMANTICS_UNRESOLVED' && <Typography variant="body2">以下是已提取的原文陈述，同病例含义尚未核实，未用于计分。</Typography>}
                <Pm3TableFindings findings={r.unverifiedFindings} issues={r.tableLayoutIssues} />
                <Pm3IdentityIssues issues={r.identityIssues} />
                {r.semanticIssues?.map((reason, j) => <Typography variant="body2" key={`semantic-${j}`}>{pm3Reason(reason)}</Typography>)}
                {r.qualificationBlocker && <Alert severity="info">
                    当前首先受阻：{pm3Reason(r.qualificationBlocker.causeReasonCode || r.qualificationBlocker.reasonCode)}
                    {r.qualificationBlocker.dependentChecks?.length > 0 && ' 后续适用政策或资料采用依赖这一步完成，不能将连带未完成项解释为原文分别缺少这些信息。'}
                </Alert>}
                {r.diseaseContextCheck?.reportedDiagnoses?.length > 0 && <Typography variant="body2">
                    已提取的病例疾病名称：{r.diseaseContextCheck.reportedDiagnoses.join('；')}。疾病身份仍须与可信术语及基因关系核对。
                </Typography>}
                <Pm3CohortContext value={r.cohortContext} supplement={r.supplementContext} facts={r.reportedFacts} />
                <Pm3PartnerEvidence sources={r.partnerClassificationSources} qualification={r.partnerQualification} />
                {Array.isArray(r.reportedFacts) && r.reportedFacts.map((f, j) => <Stack key={j} spacing={0.25}>
                    <Typography variant="body2">{pm3Field(f.field)}{f.producer === 'SOURCE_NATIVE_TABLE_CONTEXT' ? '（直接读取同表标题）' : ''}：{f.citation?.quote || f.sourceValue}</Typography>
                    {nativeFactText(f) && <Typography variant="caption">{nativeFactText(f)}</Typography>}
                    <Pm3SourceCitation citation={f.citation} />
                    {f.supportingCitations?.length > 0 && <details><summary>关联原文依据（{f.supportingCitations.length} 条）</summary>
                        {f.supportingCitations.map((c, k) => <Stack key={k}><Typography variant="body2">{c.quote}</Typography><Pm3SourceCitation citation={c} /></Stack>)}
                    </details>}
                </Stack>)}
            </Paper>)}
        </details>}
        {calculation?.contributions?.length > 0 && <details><summary>逐条计分依据</summary>
            {calculation.contributions.map(r => <Stack key={r.observationId}>
                <Typography variant="body2">计入 {r.awardedQuarterPoints / 4} 分 · {pm3Reason(r.reason)}；{r.sourceReferences.join('；')}</Typography>
                {r.requirements?.map((q, i) => <Typography variant="body2" key={i}>{pm3Reason(q.reason)}（{q.status === 'CONFLICT' ? '资料冲突' : q.status === 'NOT_APPLICABLE' ? '不适用' : q.status === 'REQUIREMENT_NOT_MET' ? '已核查但不满足' : '尚未取得合格依据'}）</Typography>)}
            </Stack>)}
        </details>}
    </Stack>;
}

function Pm3TableFindings({ findings, issues }) {
    return <>
        {findings?.map((f, i) => <Stack key={i} spacing={0.5}>
            <Typography variant="body2">{pm3Field(f.field)}：{pm3Reason(f.reasonCode)}{f.columnHeading ? ` 列标题解读：${f.columnHeading}` : ''}</Typography>
            {f.headingEvidence?.sourceHeadings?.map((h, j) => <Typography variant="body2" key={j}>
                原表头：{h.quote}；{h.lines?.length > 1 ? `按原文换行：${h.lines.join(' / ')}；` : ''}位置：{h.unitId}
            </Typography>)}
        </Stack>)}
        {issues?.map((item, i) => <Typography variant="body2" key={`layout-${i}`}>{pm3Reason(item.reasonCode)}（表格位置：{item.tableId}）</Typography>)}
    </>;
}

function Pm3IdentityIssues({ issues }) {
    return <>{issues?.map((issue, i) => <Stack key={i} spacing={0.5}>
        <Typography variant="body2">{issue.role === 'target' ? '目标变异' : issue.role === 'partner' ? '伙伴变异' : '变异组合'}：{pm3Reason(issue.reasonCode)}</Typography>
        {issue.candidateSearch?.schemaVersion === 'pm3-protein-candidates-v1' && <details>
            <summary>蛋白变化对应的 DNA 候选（尚未确认是论文中的变异）</summary>
            <Typography variant="body2">{issue.candidateSearch.reportedProtein}；查询范围：{issue.candidateSearch.searchScope === 'REPORTED_EXACT_TRANSCRIPTS' ? '文献明确给出的转录本版本' : '本地 MANE 的该基因转录本，并非论文已确认的转录本'}；{issue.candidateSearch.scopeComplete ? '已完成这个有限范围的检查' : '检查未完成，不能把部分结果当作全部候选'}。</Typography>
            {issue.candidateSearch.issues?.map((code, j) => <Typography variant="body2" key={j}>{pm3Reason(code)}</Typography>)}
            {issue.candidateSearch.candidates?.map((c, j) => <Typography variant="body2" key={`candidate-${j}`}>
                {c.transcript}：{c.codingChanges?.join(' + ')}；密码子 {c.codon} → {c.alternateCodon}；{c.substitutionType === 'SNV' ? '单核苷酸候选，已校验本地序列' : '需要多个碱基改变的可能性，尚未建立完整基因组身份'}。
            </Typography>)}
        </details>}
    </Stack>)}</>;
}

export function Pm3PartnerEvidence({ sources, qualification }) {
    if (!sources && !qualification) return null;
    const issues = [...new Set([...(sources?.issues || []), ...(qualification?.issues || [])])];
    return <details><summary>伙伴变异分类来源与采用条件</summary>
        <Typography variant="body2">取得数据库分类，不等于已证明它独立于目标变异；核验通过后才可供 PM3 使用。</Typography>
        {qualification?.classification && <Typography variant="body2">可采用分类：{qualification.classification}；独立性：{qualification.independentClassification}</Typography>}
        {qualification?.classificationSelection === 'P_LP_CONSERVATIVE_LOWER_WEIGHT' && <Typography variant="body2">来源同时报告致病和可能致病；原分类分别保留，PM3按可能致病对应的较低权重处理，不视为致病与良性冲突。</Typography>}
        {issues.map(code => <Typography variant="body2" key={code}>{pm3Reason(code)}</Typography>)}
        {sources?.assertions?.map((r, i) => <Stack key={i} spacing={0.5} sx={{ my: 1 }}>
            <Typography variant="body2">{r.vcv} · {r.scv}：{r.sourceClassification}；{r.reviewStatus}；评价日期 {r.dateLastEvaluated || '未取得'}</Typography>
            {/^VCV\d{9}\.\d+$/.test(r.vcv || '') && <a href={`https://www.ncbi.nlm.nih.gov/clinvar/variation/${r.vcv}/`} target="_blank" rel="noreferrer">查看 ClinVar 原记录</a>}
            {r.comments?.map((c, j) => <Typography variant="body2" key={j}>原文：{c.text}（XML 位置：{c.xpath}）</Typography>)}
            {qualification?.assertionAssessments?.filter(a => a.scv === r.scv).flatMap(a => a.issues || []).map((code, j) => <Typography variant="body2" key={`reason-${j}`}>{pm3Reason(code)}</Typography>)}
            {qualification?.assertionAssessments?.filter(a => a.scv === r.scv).flatMap(a => a.criterionDependencies || []).map((d, j) => <details key={`dependency-${j}`}>
                <summary>{d.sourceDeclaredCriterion}：{d.status === 'INTRINSIC_CRITERION_DECLARED' ? '来源声明为分子、频率或区域依据' : d.status === 'EXACT_COMPARISON_DEPENDENCIES_TARGET_INDEPENDENT' ? '比较对象身份及独立依据链已核实' : '仍需核验与目标变异的依赖关系'}</summary>
                {d.sourceText && <Typography variant="body2">对应原文：{d.sourceText}（位置：{d.sourceLocator}）</Typography>}
                {d.comparisonNames?.map((c, k) => <Typography variant="body2" key={`comparison-${k}`}>
                    原文比较对象：{c.reportedName}（来源标记 {c.sourceLabel}）。{c.identityStatus === 'VERIFIED' && c.classificationIndependence === 'VERIFIED'
                        ? `精确身份与独立分类已核实；分类 ${c.resolution?.classification}，依据提交 ${(c.resolution?.qualifiedScvs || []).join('、')}。`
                        : '尚未核实为精确变异及独立分类，来源标记不等于本系统已接纳分类。'}
                    {c.resolution?.reasonCode && pm3Reason(c.resolution.reasonCode)}
                </Typography>)}
                {d.issues?.map(code => <Typography variant="body2" key={code}>{pm3Reason(code)}</Typography>)}
            </details>)}
        </Stack>)}
        {qualification?.dependencyGraph?.nodes?.length > 0 && <details><summary>比较对象的逐级核查来源（{qualification.dependencyGraph.nodes.length}项）</summary>
            {qualification.dependencyGraph.nodes.map((node, i) => <Stack key={i} spacing={0.5} sx={{ my: 1 }}>
                <Typography variant="body2">{node.identity?.geneSymbol} · {node.identity?.transcript} · {node.allele?.assembly} {formatVariantCoordinate(node.allele)}</Typography>
                {node.reasonCode && <Typography variant="body2">{pm3Reason(node.reasonCode)}</Typography>}
                <Pm3PartnerEvidence sources={node.sources} qualification={node.qualification} />
            </Stack>)}
        </details>}
    </details>;
}

export function Pm3CohortContext({ value, supplement, facts }) {
    const clinicalFacts = (facts || []).filter(f => f.producer === 'SOURCE_NATIVE_CLINICAL_APPENDIX'
        && f.ownership?.method === 'SOURCE_CLINICAL_APPENDIX_COHORT_ROW_JOIN' && f.supportingCitations?.length);
    const singleFacts = (facts || []).filter(f => f.supportingCitations?.length && (
        (f.producer === 'SOURCE_NATIVE_SINGLE_PROBAND' && f.ownership?.method === 'EXPLICIT_SINGLE_PROBAND_RESULTS')
        || (f.method === 'EXPLICIT_SINGLE_PROBAND_RESULTS' && f.sourceBinding?.method === f.method
            && f.sourceBinding?.identityKind === 'SOURCE_SCOPED_UNNAMED_PROBAND_NOT_PATIENT_ID')));
    const boundSingle = singleFacts.length > 0;
    const boundClinical = clinicalFacts.length > 0;
    const boundSupplement = supplement?.reasonCode === 'PM3_SUPPLEMENT_SOURCE_CONTEXT_BOUND' && supplement.bindings?.length > 0;
    if (value?.schemaVersion !== 'pm3-cohort-context-v1' && !boundSupplement && !boundClinical && !boundSingle) return null;
    const adopted = new Set([...(boundSupplement ? [...supplement.bindings.flatMap(b => b.citations || []),
        ...(supplement.facts || []).flatMap(f => [f.citation, ...(f.supportingCitations || [])])] : []),
        ...[...clinicalFacts, ...singleFacts].flatMap(f => [f.citation, ...f.supportingCitations])].filter(Boolean).map(c => c.unitId));
    const related = (value?.relatedStatements || []).filter(s => !adopted.has(s.citation?.unitId));
    return <Stack spacing={0.5}>
        <Typography variant="body2">{boundSingle ? '已核对本文单例临床报告与检测段落的来源关联；proband 仅为原文定位标签，未公开患者编号，不据此认定跨论文病例独立。下方其他相关陈述仍须分别核实。' : boundClinical ? '已通过正文链接、全队列编号及对应字段核对临床附件与该病例的关联；具体临床分类及依据见下方原文，不代表所有计分条件已满足。'
            : boundSupplement ? '已核实正文、完整基因型附件与该观察的来源关联；仍不证明独立患者、相位或转录本身份。' : value?.status === 'SOURCE_COHORT_BOUND'
            ? '已核实入组背景、全体病例表与病例编号的原文关联。疾病目录匹配、变异身份、相位和伙伴资格仍需分别核实。'
            : '尚未建立队列背景到该病例的完整关联，相关陈述不直接用于病例判定。'}</Typography>
        {value?.bindings?.map((b, i) => <details key={i}><summary>队列背景关联依据：{b.diagnosis}</summary>
            {b.citations?.map((c, j) => <Typography variant="body2" key={j}>{c.quote}（原文位置：{c.unitId}）</Typography>)}
        </details>)}
        {boundSupplement && supplement.bindings.map((b, i) => <details key={`supplement-${i}`}><summary>正文到附件的关联依据</summary>
            {b.citations?.map((c, j) => <Stack key={j}><Typography variant="body2">{c.quote}</Typography><Pm3SourceCitation citation={c} /></Stack>)}
        </details>)}
        {related.length > 0 && <details><summary>相关原文，不能直接当作本病例事实（{related.length}）</summary>
            {related.map((s, i) => <Typography variant="body2" key={i}>{s.citation?.quote}（原文位置：{s.citation?.unitId}）</Typography>)}
        </details>}
    </Stack>;
}

export function Pm3Assessment({ value }) {
    return <Stack spacing={0.5}>
        <Alert severity="info">以下是通用 PM3 规则的资料预评价，尚未核定适用专规、未写入正式 ACMG 结果。</Alert>
        {value.diseaseAssessments?.map((group) => <Paper variant="outlined" sx={{ p: 1 }} key={group.diseaseId}>
            <Typography>疾病背景：{group.diseaseId}；预评价：{group.calculation.status}；候选强度：{group.calculation.strength}；计入分值：{group.calculation.quarterPoints / 4}</Typography>
            <Typography variant="body2">在已核查论文内保守累计；不同论文暂不直接相加。</Typography>
            {group.calculation.issues?.length > 0 && <Typography variant="body2">尚需解决：{group.calculation.issues.join('；')}</Typography>}
            <details><summary>逐条观察的计入情况与出处</summary>
                {group.calculation.contributions?.map((row) => <Paper variant="outlined" sx={{ p: 1 }} key={row.observationId}>
                    <Typography variant="body2">{row.qualificationStatus} · 计入 {row.awardedQuarterPoints / 4} 分；{row.reason}</Typography>
                    <Typography variant="body2">{row.requirements?.map((r) => r.reason).join('；')}</Typography>
                    <Typography variant="caption">出处：{row.sourceReferences.join('；')}</Typography>
                </Paper>)}
                {group.withheldOccurrences?.map((row, i) => <Typography variant="body2" key={i}>{row.pmcid} · 病例 {row.caseLabel}：
                    {row.reasonCode === 'PM3_CROSS_PAPER_OVERLAP_NOT_RECONCILED' ? '跨论文是否重复尚未核实，不额外累计' : '同一家系或病例间关系尚未支持额外累计'}</Typography>)}
            </details>
        </Paper>)}
        {value.issues?.length > 0 && <Typography variant="body2">没有可供本轮计算的基因型观察；{value.issues.join('；')}</Typography>}
    </Stack>;
}

const rarityText = (value) => {
    const statuses = { VERIFIED: '已核实符合稀有性要求', FAILED: '已评价，不符合稀有性要求',
        CONFLICT: '资料身份或条件冲突', UNKNOWN: '尚不能判断', NOT_APPLICABLE: '本观察不需要第二个变异' };
    const reasons = { PM3_POPULATION_AFFECTED_AR_CONTEXT_REQUIRED: '尚未核实该病例患病及隐性疾病背景，未发起人口查询',
        PM3_POPULATION_VERIFIED_IDENTITY_REQUIRED: '变异身份尚未核实，未发起人口查询',
        PM3_POPULATION_SELECTED_ALLELE_MISMATCH: '文献目标与所选变异不一致，未发起人口查询',
        PM3_POPULATION_NOT_CONFIGURED: '本地人口查询尚未配置',
        PM2_LOCAL_INTERVAL_QC_FAILED: '已找到人群记录，但所在区间未通过覆盖质控；记录的 PASS 不能代替该检查，当前不能据此确认罕见性',
        PM2_SPECIALIST_POLICY_REVIEW_REQUIRED: '已取得人口资料，但当前疾病专规的稀有性阈值尚未完成可采用绑定，未使用通用阈值替代',
        PM3_LOCAL_POPULATION_WORKFLOW_DISABLED: '本地人口查询工作流未启用',
        PM3_POPULATION_TASK_BUDGET_EXHAUSTED: '本轮处理时间已用尽，该项尚未查询',
        PM3_POPULATION_TASK_QUERY_LIMIT: '本轮人口查询数量已达上限，该项尚未查询' };
    return `${statuses[value.qualification] || '尚不能判断'}${value.qualification === 'NOT_APPLICABLE' ? '' : `；${reasons[value.reasonCode] || value.reasonCode || '具体原因未取得'}`}`;
};

function Pm3MaterialAssessments({ items }) {
    if (!items?.length) return null;
    return <details><summary>本批资料类型与原文位置</summary>
        <Typography variant="caption">类型是模型归纳，引用已核对；它本身不构成病例或计分依据。</Typography>
        {items.map((item, i) => <Typography variant="body2" key={i}>
            {({ INDIVIDUAL_CASE_MATERIAL: '个体病例资料', AGGREGATE_OR_METHODS: '汇总或方法资料', UNCLEAR: '类型尚不明确' })[item.materialKind] || '类型尚不明确'}：
            “{item.citation?.quote}”（原文位置：{item.citation?.unitId}）
        </Typography>)}
    </details>;
}

export function Pm3PaperFacts({ paper }) {
    return <Paper variant="outlined" sx={{ p: 1 }}>
        <Typography><a href={`https://pmc.ncbi.nlm.nih.gov/articles/${encodeURIComponent(paper.pmcid)}/`} target="_blank" rel="noreferrer">{paper.pmcid}</a> · 已复核 {paper.completedReviewChunks?.length || 0} / {paper.chunkCount || 0} 块</Typography>
        <LiteratureStageDiagnostics value={pm3CheckpointDiagnostic(paper.processingCheckpoint, paper)} explain={pm3Reason} />
        {paper.cacheReuse && <Typography variant="body2">本轮复用提取缓存 {paper.cacheReuse.extractionChunks?.length || 0} 块、复核缓存 {paper.cacheReuse.reviewChunks?.length || 0} 块；来源重新核验，事实按当前规则重新组装，不沿用旧分数。</Typography>}
        {paper.issues?.length > 0 && <Alert severity="warning">{paper.issues.map(pm3Reason).join('；')}</Alert>}
        <Pm3MaterialAssessments items={paper.observations?.materialAssessments} />
        {[...(paper.observations?.cases || []).map((item) => ({ item, reviewed: true })),
            ...(paper.observations?.unreviewedExtraction?.cases || []).map((item) => ({ item, reviewed: false }))].map(({ item, reviewed }) => <details key={`${reviewed}:${item.caseLabel}`}>
            <summary>{caseTitle(item)} · {item.facts.length} 条原文陈述；{reviewed ? '不等于可计分病例' : '待模型复核，未进入判定'}</summary>
            <Pm3CohortContext value={item.cohortContext} supplement={item.supplementContext} facts={item.facts} />
            {reviewed && item.primarySourceQualification && <Typography variant="body2">
                病例原始来源：{item.primarySourceQualification.qualification === 'VERIFIED' ? '已核实本研究的原始病例来源' :
                    item.primarySourceQualification.qualification === 'CONFLICT' ? '来源陈述存在冲突' : '尚未核实'}；病例之间是否独立、是否重复报道仍需另行核验。
            </Typography>}
            <Pm3TableFindings findings={item.semantics?.unverifiedFindings} issues={item.tableLayoutIssues} />
            {item.semantics?.issues?.map((reason, i) => <Typography variant="body2" key={`semantic-${i}`}>{pm3Reason(reason)}</Typography>)}
            {item.originAssertions?.some((origin) => origin.citations?.length) && <details><summary>病例来源的原文依据</summary>
                {item.originAssertions.flatMap((origin) => origin.citations || []).map((citation, index) => <Typography variant="body2" key={index}>
                    “{citation.quote}” · 原文位置：{citation.unitId}
                </Typography>)}
            </details>}
            {item.resolvedObservations?.map((row, index) => <Stack key={index} spacing={0.5}>
                <Typography variant="body2">变异身份：{row.status}；相位：{row.reportedObservation?.phase}</Typography>
                {row.policySelection && <Typography variant="body2">适用政策：{row.policySelection.status} · {row.policySelection.reasonCode}；仅依据当前固定专规快照核对。</Typography>}
                <Typography variant="body2">隐性疾病背景：{row.diseaseContext?.reasonCode || '尚未取得核定结果'}；伙伴分类：{row.partnerQualification?.classification || '尚未取得核定结果'}；独立性：{row.partnerQualification?.independentClassification || '尚未核定'}。{row.partnerQualification?.issues?.join('；')}</Typography>
                <Pm3PartnerEvidence sources={row.partnerClassificationSources} qualification={row.partnerQualification} />
                {row.rarityQualification && <>
                    <Typography variant="body2">人口资料范围：本地 gnomAD 外显子组；查询不到不能直接认定罕见。</Typography>
                    {['target', 'partner'].filter((role) => row.rarityQualification[role]).map((role) => <Typography variant="body2" key={role}>
                        {role === 'target' ? '目标变异' : '伙伴变异'}稀有性：{rarityText(row.rarityQualification[role])}
                    </Typography>)}
                </>}
                <Pm3IdentityIssues issues={row.issues} />
            </Stack>)}
            {item.facts.map((fact, index) => <Paper key={index} variant="outlined" sx={{ p: 1, my: 0.5 }}>
                {fact.producer === 'SOURCE_NATIVE_TABLE_CONTEXT' && <Typography variant="caption">直接读取该病例所在表格的明确标题；不是新增模型陈述。</Typography>}
                {nativeFactText(fact) && <Typography variant="caption">{nativeFactText(fact)}</Typography>}
                <Typography variant="body2">{pm3Field(fact.field)}：{fact.sourceValue}</Typography>
                <Typography variant="body2">原文：“{fact.citation.quote}”</Typography>
                <Pm3SourceCitation citation={fact.citation} />
                <Typography variant="caption">病例归属：{fact.status}</Typography>
                {fact.supportingCitations?.length > 0 && <details><summary>关联原文依据（{fact.supportingCitations.length} 条）</summary>
                    {fact.supportingCitations.map((c, k) => <Stack key={k}><Typography variant="body2">{c.quote}</Typography><Pm3SourceCitation citation={c} /></Stack>)}
                </details>}
            </Paper>)}
        </details>)}
        {!paper.observations?.cases?.length && !paper.observations?.unreviewedExtraction?.cases?.length && <Typography variant="body2">本轮没有取得可展示的病例事实；不代表论文中没有病例。</Typography>}
    </Paper>;
}

export default function Pm3LiteraturePanel({ analysisId, topN, initialTaskId, initialTaskReference, onReturn }) {
    const [options, setOptions] = useState(null), [candidate, setCandidate] = useState('');
    const [tasks, setTasks] = useState([]), [confirmed, setConfirmed] = useState(false);
    const [busy, setBusy] = useState(false), [uncertain, setUncertain] = useState(false);
    const [message, setMessage] = useState(''), [parent, setParent] = useState(null);
    const [continuationConfirmation, setContinuationConfirmation] = useState(null);
    const sourceMode = Boolean(initialTaskId || initialTaskReference);
    const sourceScopeAvailable = !sourceMode || (Number.isSafeInteger(topN) && topN > 0);
    const sourceTaskId = initialTaskId || initialTaskReference?.taskId;
    const sourceIdentity = JSON.stringify(initialTaskReference || null);
    const active = useRef(''), alive = useRef(true), identity = `${analysisId}:${topN}:${initialTaskId || ''}:${sourceIdentity}`;
    active.current = identity;
    useEffect(() => { alive.current = true; return () => { alive.current = false; }; }, []);
    const current = (key) => alive.current && active.current === key;
    const referenceFor = (taskId) => taskId === sourceTaskId ? initialTaskReference : undefined;
    const continuationStateFor = (task) => sourceScopeAvailable ? pm3ContinuationState(task, referenceFor(task.taskId))
        : { allowed: false, reason: '保存记录未提供有效的原候选范围，仅可阅读来源任务，不能使用默认范围继续。' };
    const readTasks = async (signal) => {
        if (sourceMode) {
            const ids = [...new Set([sourceTaskId, ...tasks.map((task) => task.taskId)])];
            return Promise.all(ids.map((taskId) => readPm3Task({ analysisId, taskId, reference: referenceFor(taskId), signal })));
        }
        const { data } = await axios.get(`${base(analysisId)}/tasks`, { signal, timeout: 10000 });
        return data.map(validatePm3Task);
    };
    const refresh = async () => {
        const key = identity; setBusy(true); setMessage('');
        try {
            const rows = await readTasks();
            if (current(key)) { setTasks(rows); setUncertain(false); }
        } catch (error) { if (current(key)) setMessage(error.response?.data?.message || error.message); }
        finally { if (current(key)) setBusy(false); }
    };
    useEffect(() => {
        setOptions(null); setCandidate(''); setTasks([]); setConfirmed(false); setParent(null); setContinuationConfirmation(null);
        setBusy(true); setUncertain(false); setMessage('');
        const controller = new AbortController(), key = identity;
        const read = sourceMode
            ? readPm3Task({ analysisId, taskId: sourceTaskId, reference: initialTaskReference, signal: controller.signal }).then((task) => [task])
            : axios.get(`${base(analysisId)}/tasks`, { signal: controller.signal, timeout: 10000 }).then(({ data }) => data.map(validatePm3Task));
        read.then((rows) => { if (current(key)) setTasks(rows); })
            .catch((error) => { if (current(key) && !controller.signal.aborted) setMessage(`PM3 历史未读取成功：${error.response?.data?.message || error.message}`); })
            .finally(() => { if (current(key) && !controller.signal.aborted) setBusy(false); });
        return () => controller.abort();
    }, [identity]); // eslint-disable-line react-hooks/exhaustive-deps
    useEffect(() => {
        const waiting = tasks.filter(pending);
        if (!waiting.length) return undefined;
        const controller = new AbortController(), key = identity;
        const timer = setTimeout(async () => {
            try {
                const updates = await Promise.all(waiting.map((task) => readPm3Task({ analysisId, taskId: task.taskId,
                    reference: referenceFor(task.taskId), signal: controller.signal })));
                if (current(key) && !controller.signal.aborted) setTasks((old) => old.map((task) => updates.find((update) => update.taskId === task.taskId) || task));
            } catch (error) {
                if (current(key) && !controller.signal.aborted) setMessage(`PM3 状态未刷新：${error.response?.data?.message || error.message}`);
            }
        }, 5000);
        return () => { clearTimeout(timer); controller.abort(); };
    }, [identity, tasks]); // eslint-disable-line react-hooks/exhaustive-deps
    const prepare = async (task = null) => {
        if (sourceMode && !task) return;
        if (!sourceScopeAvailable) { setMessage('未取得原候选范围，仅可阅读来源任务。'); return; }
        const key = identity; setBusy(true); setMessage(''); setConfirmed(false); setOptions(null); setParent(null); setContinuationConfirmation(null);
        try {
            if (task) {
                const prepared = await preparePm3Continuation({ analysisId, taskId: task.taskId, reference: referenceFor(task.taskId), topN });
                if (current(key)) { setOptions(prepared.options); setParent(prepared.task); setContinuationConfirmation(prepared.confirmation); }
                return;
            }
            const { data } = await axios.get(`${base(analysisId)}/options`, { params: { topN }, timeout: 30000 });
            if (data.schemaVersion !== 'pm3-literature-options-v1' || !/^[a-f0-9]{64}$/.test(data.selectionSha256) ||
                !Array.isArray(data.selection?.candidates)) throw new Error('PM3 候选回执无效。');
            if (current(key)) { setOptions(data); setCandidate(data.selection.candidates[0]?.candidateId || ''); setParent(task); }
        } catch (error) { if (current(key)) setMessage(error.response?.data?.message || error.message); }
        finally { if (current(key)) setBusy(false); }
    };
    const start = async () => {
        if (!confirmed || busy || uncertain || !options?.enabled || (!parent && !candidate)) return;
        const key = identity; setBusy(true); setConfirmed(false); setMessage('');
        try {
            const task = parent ? await continuePm3Task({ analysisId, taskId: parent.taskId, reference: referenceFor(parent.taskId),
                topN, confirmation: continuationConfirmation, confirmed: true, isCurrent: () => current(key) }) : validatePm3Task((await axios.post(`${base(analysisId)}/tasks`, {
                topN: Number(topN), selectionSha256: options.selectionSha256, confirmPublicModel: true,
                candidateId: candidate,
            }, { timeout: 15000 })).data);
            if (current(key)) { setTasks((old) => [task, ...old.filter((t) => t.taskId !== task.taskId)]); setOptions(null); setParent(null); setContinuationConfirmation(null); }
        } catch (error) {
            if (current(key)) {
                const submitted = !parent || error.pm3SubmissionAttempted === true;
                setUncertain(submitted);
                setMessage(`${submitted ? '提交状态待核对，请只读刷新。' : '未提交继续请求。'}${error.response?.data?.message || error.message || ''}`);
            }
        } finally { if (current(key)) setBusy(false); }
    };
    return <Paper variant="outlined" sx={{ p: 2, my: 2 }}><Stack spacing={1}>
        <Typography variant="h6">PM3 文献病例资料</Typography>
        {!sourceScopeAvailable && <Alert severity="warning">原候选范围缺失或无效，当前仅可查看任务。</Alert>}
        <Stack direction="row" spacing={1}>
            {onReturn && <Button onClick={onReturn}>返回证据详情</Button>}
            {!sourceMode && <Button disabled={busy || uncertain || tasks.some(pending)} onClick={() => prepare()}>准备 PM3 文献任务</Button>}
            <Button disabled={busy} onClick={refresh}>只读刷新 PM3 任务</Button>
        </Stack>
        {busy && !tasks.length && <Typography role="status">正在读取 PM3 来源任务…</Typography>}
        {options && <>
            <Alert severity="info">公开获取：{options.publicNetworkEnabled ? '启用' : '仅缓存'}；模型：{options.modelEnabled ? '可产生费用，使用共享预算' : '仅缓存'}。仅发送必要变异检索词和公开论文内容。{parent ? '继续未处理部分，复用已完成资料。' : ''}</Alert>
            {parent && <Typography variant="body2">继续父任务 {parent.taskId.slice(0, 12)} · 候选摘要 {continuationConfirmation?.selectionSha256.slice(0, 12)} · 资料摘要 {continuationConfirmation?.resultSha256.slice(0, 12)}</Typography>}
            {!parent && <TextField select label="当前 Top-N 候选" value={candidate} onChange={(e) => { setCandidate(e.target.value); setConfirmed(false); }}>
                {options.selection.candidates.map((row) => <MenuItem key={row.candidateId} value={row.candidateId}>排名 {row.rank} · {row.geneSymbol} · {row.candidateId}</MenuItem>)}
            </TextField>}
            <FormControlLabel control={<Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} disabled={busy || uncertain} />} label="确认本次公开资料获取与提取范围" />
            <Button disabled={!confirmed || busy || uncertain || !options.enabled || tasks.some(pending)} onClick={start}>{parent ? '确认并继续 PM3 资料任务' : '启动 PM3 资料任务'}</Button>
        </>}
        {message && <Alert severity="warning">{message}</Alert>}
        {tasks.map((task) => <Paper variant="outlined" sx={{ p: 1 }} key={task.taskId}>
            <Typography>任务 {task.taskId.slice(0, 12)} · {task.status}</Typography>
            {task.reasonCode && <Alert severity="warning">{task.reasonCode}</Alert>}
            {!pm3SourceBindingState(task, referenceFor(task.taskId)).matches && <Alert severity="warning">保存来源核对：{pm3SourceBindingState(task, referenceFor(task.taskId)).reason}</Alert>}
            {task.result && <>
                <Pm3ReadingProgress value={task.result.readingLoop} />
                <LiteratureAcquisitionDiagnostics value={literatureAcquisitionDiagnostic(task.result.acquisitionRecovery)} explain={pm3Reason} />
                <Typography variant="body2">模型调用 {task.result.modelCalls}；{task.result.pm3Assessment ? '已进入资料预评价，正式 ACMG 结果尚未生成' : 'PM3 计分尚未执行'}。{task.result.issues?.join('；')}</Typography>
                {task.result.pm3Assessment && <Pm3Assessment value={task.result.pm3Assessment} />}
                {task.result.populationQualification && <Typography variant="body2">本地人口查询 {task.result.populationQualification.providerCalls} 次；检测量查询 {task.result.populationQualification.allSitesCalls} 次；稀有性规则评价 {task.result.populationQualification.pm2EvaluatorCalls} 次。</Typography>}
                <LiteraturePriorityView result={task.result} />
                {(task.result.papers || [task.result]).filter((p) => p.pmcid).map((paper) => <Pm3PaperFacts key={paper.pmcid} paper={paper} />)}
                <Typography variant="body2">{continuationStateFor(task).reason}</Typography>
                {continuationStateFor(task).allowed && <Button disabled={busy || uncertain || tasks.some(pending)} onClick={() => prepare(task)}>复用缓存并继续 PM3 未处理资料</Button>}
            </>}
        </Paper>)}
    </Stack></Paper>;
}
