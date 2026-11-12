import AcmgRunControlActions from './AcmgRunControlActions';
import { formatVariantCoordinate } from '../utils/variantCoordinate';
import { backgroundEvidenceCards, savedBackgrounds, resultPresentation, presentationCounts } from '../utils/acmgResultsWorkbench';
import AcmgEvidenceSelectionDialog from './AcmgEvidenceSelectionDialog';
import AcmgResultsWorkbench from './AcmgResultsWorkbench';
import SampleCallSummary from './SampleCallSummary';
import { isResultIndex, verifyCompleteExport } from '../utils/acmgResultView';
import { displayStrength } from '../utils/evidencePresentation';
import axios from 'axios';
import { activeControl, controlLabels, controlActions, controlActionAvailable, controlAfterHistory, createPreparationControl, commandWithReconciliation, terminalControl } from '../utils/acmgControl';
import { prepareAutomaticLiterature, automaticCheckpoint } from '../utils/automaticAcmg';
import Ba1DataScopeNotice from './Ba1DataScopeNotice';
import DeNovoSourceTaskNotice from './DeNovoSourceTaskNotice';
import PublicLiteraturePanel from './PublicLiteraturePanel';
import Pm3LiteraturePanel from './Pm3LiteraturePanel';
import Pp1LiteraturePanel from './Pp1LiteraturePanel';
import LiteratureContextSelection from './LiteratureContextSelection';
import Pp4PhenotypeSummary from './Pp4PhenotypeSummary';
import Bs4FamilySummary from './Bs4FamilySummary';
import Pp1FamilySummary from './Pp1FamilySummary';
import React, { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
    Accordion,
    AccordionDetails,
    AccordionSummary,
    Alert,
    Button,
    Checkbox,
    DialogContent,
    FormControlLabel,
    LinearProgress,
    MenuItem,
    Paper,
    Stack,
    TextField,
    Typography,
} from '@mui/material';
import {
    fetchAcmgBusinessRun,
    fetchAcmgResultView,
    fetchAcmgBusinessRuns,
    fetchAcmgInputOptions,
    submitAcmgBusinessRun,
    fetchAcmgBackground,
    submitConfirmedAcmgRun,
    fetchAcmgControl,
    commandAcmgControl,
} from '../services/variantAnalysis';
import {
    annotationSelectable,
    buildSimplifiedCaseDeclaration,
    buildSavedObservationAssociationDeclaration,
    buildSavedCalibrationDeclaration,
    savedCalibrationParent,
    buildRevelApplicationDeclaration,
    revelSourceEligible,
    revelSavedDetails,
    savedObservationParent,
    enabledCriteria,
    currentBusinessCriteria,
    localBatchCriteriaText, localBatchScopeClaim, localBatchScopeIssue, localBatchSavedRun, localBatchAttemptIssue, localBatchRetryEligible, localInterpretationContextText, unifiedLocalScope, deNovoLocalScope,
    pp4ScopeClaim, pp4ScopeIssue, pp4SavedRun, pp4RetryEligible, buildPp4Declaration,
    bs4ScopeClaim, bs4ScopeIssue, bs4SavedRun, bs4RetryEligible, buildBs4Declaration,
    pp1ScopeClaim, pp1ScopeIssue, pp1SavedRun, pp1RetryEligible, buildPp1Declaration,
    bp2ScopeClaim, bp2ScopeIssue, bp2SavedRun, bp2RetryEligible, buildBp2Declaration,
    pm3ScopeClaim, pm3ScopeIssue, pm3SavedRun, pm3RetryEligible, buildPm3Declaration,
    pm6ScopeClaim,
    pm6ScopeIssue,
    pm6SavedRun,
    pm6RetryEligible,
    buildPm6Declaration,
    ps2ScopeClaim,
    ps2ScopeIssue,
    ps2SavedRun,
    ps2RetryEligible,
    buildPs2Declaration,
    bp1ScopeClaim,
    bp1ScopeIssue,
    bp1SavedRun,
    pp2ScopeClaim,
    pp2ScopeIssue,
    pp2SavedRun,
    bp3ScopeClaim,
    bp3ScopeIssue,
    bp3SavedRun,
    pvs1NmdScopeClaim,
    pvs1NmdScopeIssue,
    pvs1NmdSavedRun, pvs1NmdSavedIssue,
    pm1ScopeClaim,
    pm1ScopeIssue,
    pm1SavedRun,
    pm4ScopeClaim,
    pm4ScopeIssue,
    ps1ScopeClaim,
    ps1ScopeIssue,
    pm5ScopeClaim,
    pm5ScopeIssue,
    declarationDraftIdentity,
    populationPreparation,
    candidateInputStatus,
    contextTranscriptRecommendation,
    prepareSimplifiedInputs,
    prepareAutomaticInputs,
    reasonHelp,
    pm2ContextPreparationDetails,
    relationshipClassificationLabel,
    relationshipOptions,
    validateInputOptions,
    validateSavedRun,
} from '../utils/acmgInput';
import { acmgRequestFeedback, acmgRunLabel, backgroundOptionsIssue, watchAcmgRun, liveProgressText } from '../utils/acmgRunFeedback';

import AcmgEvidenceMatrix from './AcmgEvidenceMatrix';
import AcmgGuidedPreparation from './AcmgGuidedPreparation';
import { prepareGuidedInputs, buildGuidedDeclaration, rebindGuidedInputsAfterIntake } from '../utils/acmgBackground';

function confirmRunControl(action) {
    const messages={STOP:'停止本次分析？当前调用退出后不再启动下一步；不会删除已有资料，也不会自动重跑。',
        ACKNOWLEDGE_INTERRUPTED:'请确认旧分析已不再执行。此操作只结束旧运行记录，不删除已有资料，也不会自动重跑。',
        PAUSE:'确认暂停当前分析？将在安全检查点生效。',RESUME:'确认继续当前执行进程？不会创建新的分析。'};
    return Object.hasOwn(messages,action) && window.confirm(messages[action]);
}

const alleleLabel = (a) => formatVariantCoordinate(a,'坐标不可恢复');
const runTime = (value) => {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? value || '时间未知' : date.toLocaleString();
};

function SavedRunLabel({ run }) {
    if (!run) return null;
    return (
        <span className="cv-acmg-history-run-label">
            <span>{runTime(run.createdAt)} · {controlLabels[run.status] || acmgRunLabel(run.status)}</span>
            <Typography component="span" variant="caption" color="text.secondary">
                运行 ID：{run.runId}
            </Typography>
        </span>
    );
}

function Reasons({ blockers = [] }) {
    return blockers.map((b, i) => {
        const code = typeof b === 'string' ? b : b.reasonCode;
        return (
            <Alert severity="warning" key={`${code}-${i}`} sx={{ my: 1 }}>
                {reasonHelp(code)}
                <Typography variant="caption" component="div">
                    {code}
                    {b.field ? ` · ${b.field}` : ''}
                </Typography>
            </Alert>
        );
    });
}

export function CandidateOutcomeSummary({ candidate, criteria, run, backgroundIndex = 0 }) {
    const cards = backgroundEvidenceCards(candidate, criteria, run, backgroundIndex);
    const rows = cards.flatMap(card=>card.entries.map(entry=>({entry,card})));
    const counts = presentationCounts(rows);
    const metLabels=rows.filter(row=>resultPresentation(row.entry).mainKind==='ADOPTED_MET')
        .map(row=>`${row.card.code} ${displayStrength(row.entry.evaluation.appliedStrength)}`);
    return <Stack spacing={0.5}>
        <Typography variant="body2" data-testid="candidate-outcome-counts">当前背景：{counts.map(item=>`${item.count} 项${item.label}`).join(' · ')}。四类合计 {rows.length} 项证据结果。</Typography>
        <Typography variant="body2" fontWeight={600}>{metLabels.join('；') || '当前背景暂无已采用满足证据；各项具体原因见详情。'}</Typography>
    </Stack>;
}

function CandidateEvidenceResults({candidate,criteria,run,sourceTaskContext}) {
    const [index,setIndex]=useState(0);
    const backgrounds=savedBackgrounds(candidate);
    return <Stack spacing={1}>
        <TextField select size="small" label="当前保存背景" value={index} onChange={event=>setIndex(Number(event.target.value))}>
            {backgrounds.map(background=><MenuItem key={background.index} value={background.index}>{background.label} · {background.identity}</MenuItem>)}
        </TextField>
        <CandidateOutcomeSummary candidate={candidate} criteria={criteria} run={run} backgroundIndex={index} />
        <details><summary style={{cursor:'pointer',padding:'8px 0'}}>查看当前背景的 {criteria.length} 条证据、原因与出处</summary>
            <AcmgEvidenceMatrix candidate={candidate} enabledCriteria={criteria} run={run} backgroundIndex={index} onBackgroundChange={setIndex} showBackgroundSelector={false} sourceTaskContext={sourceTaskContext} />
        </details>
    </Stack>;
}

function hasSavedEvidenceResult(run) {
    return ['COMPLETED', 'COMPLETED_WITH_BLOCKERS'].includes(run?.status) &&
        run.resultsAvailable !== false && Array.isArray(run.candidates);
}

function IndexedSavedResult({run, draftChanged, sourceTaskContext, showHeading}) {
    const [exporting,setExporting] = useState(false), [exportError,setExportError] = useState('');
    const download = async () => {
        setExporting(true); setExportError('');
        try {
            const full = verifyCompleteExport(validateSavedRun(await fetchAcmgBusinessRun(run.analysisId,run.runId,run.sourceViewSha256),run.analysisId),run);
            const url = URL.createObjectURL(new Blob([JSON.stringify(full,null,2)],{type:'application/json'}));
            const link = document.createElement('a'); link.href=url; link.download=`acmg-${run.runId}.json`; link.click();
            setTimeout(()=>URL.revokeObjectURL(url),1000);
        } catch(error) { setExportError(error.message || '完整结果读取失败，请重试。'); }
        finally { setExporting(false); }
    };
    return <Stack spacing={2}>
        {showHeading && <Typography variant="h6">已保存的证据结果</Typography>}
        <Typography variant="body2">{acmgRunLabel(run.status)} · {run.selectedCount} 个变异 · {run.enabledCriteria?.length} 条证据</Typography>
        {draftChanged && <Alert severity="info">背景有未提交的修改。</Alert>}
        <Button variant="outlined" disabled={exporting} onClick={download}>{exporting?'正在读取并核验完整导出，请稍候…':'下载本次完整结果 JSON'}</Button>
        {exportError && <Alert severity="error">{exportError}</Alert>}
        {localBatchSavedRun(run) ? <AcmgResultsWorkbench run={run} criteria={enabledCriteria(run)} sourceTaskContext={sourceTaskContext} /> :
            <Alert severity="warning">保存的证据范围身份不完整，暂不展示科学结论。可下载完整记录核查。</Alert>}
    </Stack>;
}

export function SavedResult({ run, trackingPaused, criteria, draftChanged, sourceTaskContext, showHeading = true }) {
    const saved = hasSavedEvidenceResult(run);
    if (saved && isResultIndex(run)) return <IndexedSavedResult key={`${run.runId}:${run.sourceViewSha256}`} run={run} draftChanged={draftChanged} sourceTaskContext={sourceTaskContext} showHeading={showHeading} />;
    const savedPm4 = enabledCriteria(run).includes('ACMG_AMP_2015:PM4');
    const savedPs1 = run.executionProfile?.profileId === 'acmg-ps1-missense-development-v1' &&
        run.declarationVersion === 'ordinary-ps1-missense-v1' &&
        JSON.stringify(enabledCriteria(run)) === '["ACMG_AMP_2015:PS1"]';
    const savedPm5 = run.executionProfile?.profileId === 'acmg-pm5-missense-development-v1' &&
        run.declarationVersion === 'ordinary-pm5-missense-v1' &&
        JSON.stringify(enabledCriteria(run)) === '["ACMG_AMP_2015:PM5"]';
    const displayedCriteria = localBatchScopeClaim(run) ? localBatchSavedRun(run) ? enabledCriteria(run) : [] : pp4ScopeClaim(run) ? pp4SavedRun(run) ? enabledCriteria(run) : [] : bs4ScopeClaim(run) ? bs4SavedRun(run) ? enabledCriteria(run) : [] : pp1ScopeClaim(run) ? pp1SavedRun(run) ? enabledCriteria(run) : [] : bp2ScopeClaim(run) ? bp2SavedRun(run) ? enabledCriteria(run) : [] : pm3ScopeClaim(run) ? pm3SavedRun(run) ? enabledCriteria(run) : [] : pm6ScopeClaim(run) ? pm6SavedRun(run) ? enabledCriteria(run) : []
        : ps2ScopeClaim(run) ? ps2SavedRun(run) ? enabledCriteria(run) : []
        : bp1ScopeClaim(run) ? bp1SavedRun(run) ? enabledCriteria(run) : []
        : pp2ScopeClaim(run) ? pp2SavedRun(run) ? enabledCriteria(run) : []
        : bp3ScopeClaim(run) ? bp3SavedRun(run) ? enabledCriteria(run) : []
        : pvs1NmdScopeClaim(run) ? pvs1NmdSavedRun(run) ? enabledCriteria(run) : []
        : pm1ScopeClaim(run) ? pm1SavedRun(run) ? enabledCriteria(run) : []
        : enabledCriteria(run);
    const historical = JSON.stringify(enabledCriteria(run)) !== JSON.stringify(criteria);
    const download = () => {
        const url = URL.createObjectURL(
            new Blob([JSON.stringify(run, null, 2)], { type: 'application/json' }),
        );
        const a = document.createElement('a');
        a.href = url;
        a.download = `acmg-${run.runId}.json`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
    };
    return (
        <Stack spacing={2}>
            {showHeading && <Typography variant="h6">{saved ? '已保存的证据结果' : '评价运行状态'}</Typography>}
            <Typography variant="body2">
                保存状态：{controlLabels[run.executionControl?.state] || acmgRunLabel(run.status)}
                {run.requestedN != null ? ` · Top-${run.requestedN}` : ''}
                {run.selectedCount != null ? ` · ${run.selectedCount} 条候选` : ''}
                {run.dataClass ? ` · 数据类别：${run.dataClass}` : ''}
            </Typography>
            <Typography variant="body2" color="text.secondary">以下为逐条证据结果，不是变异的最终致病分类。未评价不等于阴性。</Typography>
            <details><summary>判定范围、输入声明与保存身份</summary>
            {localBatchScopeClaim(run) && saved && (!localBatchSavedRun(run) || (run.candidates || []).some((candidate) =>
                !(candidate.attempts?.length) || candidate.attempts.some((attempt) => localBatchAttemptIssue(candidate, attempt, displayedCriteria, run.executionProfile, undefined, run)))) &&
                <Alert severity="warning">保存的本地证据身份或部分结果不完整；受影响背景不展示科学结论。保留完整公开结果 JSON 供核查与导出，其他完整背景独立展示。</Alert>}
            {draftChanged && (
                <Alert severity="info">背景有未提交的修改。</Alert>
            )}
            {historical && (
                <Alert severity="info">
                    {pp4SavedRun(run) ? '此 PP4 记录按自身保存身份只读展示；不授权当前提交，导出保留完整原始记录。' : bs4SavedRun(run) ? '此 BS4 记录按自身保存身份只读展示；不授权当前提交，导出保留完整原始记录。' : pp1SavedRun(run) ? '此 PP1 记录按自身保存身份只读展示；不授权当前提交，导出保留完整原始记录。' : bp2SavedRun(run) ? '此 BP2 记录按自身保存身份只读展示；不授权当前提交，下载保留完整原始记录。' : pm3SavedRun(run) ? '此 PM3 记录按自身保存身份只读展示；不授权当前提交，下载保留完整原始记录。' : pm6SavedRun(run) ? '此 PM6 记录按自身保存身份独立展示；当前材料状态或规则范围不授权本次提交，下载保留完整原始记录。' : bp1SavedRun(run) ? '此 BP1 记录按保存身份只读展示；不授权当前实例提交。下载保留完整原始记录。' : pp2SavedRun(run) ? '此 PP2 记录按保存身份只读展示；不授权当前实例提交。下载保留完整原始记录。' : bp3SavedRun(run) ? '此 BP3 记录按保存身份只读展示；不授权当前实例提交。下载保留完整原始记录。' : pvs1NmdSavedRun(run) ? '此 PVS1 记录按保存身份只读展示；不授权当前实例提交。下载保留原始记录。' : pm1SavedRun(run) ? '此 PM1 记录按保存身份只读展示；不授权当前实例提交。下载保留原始记录。'
                        : savedPm5 ? '此 PM5 记录按保存身份只读展示；不授权当前实例提交。下载保留原始记录。'
                        : savedPs1 ? '此 PS1 记录按保存身份只读展示；不授权当前实例提交。下载保留原始记录。'
                        : savedPm4 ? '此 PM4 记录按保存身份只读展示；不授权当前实例提交。下载保留原始记录。'
                        : localBatchSavedRun(run) ? '此记录按保存时的本地证据范围完整展示；与当前入口不同，也不会授权当前提交。下载保留完整原始记录。'
                        : '此记录按历史规则范围执行。当前页面仅展示当前实例启用的规则；上方保存状态仍是历史整体状态，下载保留完整原始记录。'}
                </Alert>
            )}
            <details><summary style={{ cursor: 'pointer' }}>数据范围、来源与运行说明</summary>
                <Stack spacing={1} sx={{ mt: 1 }}>
                    <Ba1DataScopeNotice scope={run.executionProfile?.ba1RuntimeBinding?.dataScope || run.executionProfile?.baseExecutionProfile?.ba1RuntimeBinding?.dataScope}
                        bs1Scope={run.executionProfile?.bs1RuntimeBinding?.dataScope || run.executionProfile?.baseExecutionProfile?.bs1RuntimeBinding?.dataScope || run.executionProfile?.populationDataScope || run.executionProfile?.baseExecutionProfile?.populationDataScope} pm2Enabled={unifiedLocalScope(run)} />
                    {localBatchSavedRun(run) && <DeNovoSourceTaskNotice run={run} />}
                    {localBatchSavedRun(run) && <Alert severity="info">已保存 {localBatchCriteriaText(run)} {unifiedLocalScope(run) ? `统一入口结果，同一次运行包含${run.enabledCriteria.length}条证据。` : '历史本地模式结果，保留保存时的证据范围。'}各条证据分别保留实际状态与缺项，未评价不是阴性，不生成最终分类。历史只读不授权当前提交；可导出本次公开结果、来源摘要及模块引用，私有原始材料由服务端归档。</Alert>}
                    <Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>
                        运行：{run.runId} · {runTime(run.createdAt)} · {run.executionProfile?.profileId}
                    </Typography>
                    {pp4SavedRun(run) && <Alert severity="info">{run.status === 'COMPLETED_WITH_BLOCKERS'
                        ? 'PP4 最小未评价结果已保存；UNKNOWN 不补造零分、强度或完成评价。材料不可用时历史仍只读。'
                        : 'PP4 致病方向证据已保存，不是目标最终分类。后端点数、半点、上限和强度原样展示；不累计、不自动采用。CORE_RESULT_ONLY 来源保留；页面不冒称复验核心 RAW 字节。'}</Alert>}
                    {pp4ScopeClaim(run) && !pp4SavedRun(run) && <Alert severity="warning">保存的 PP4 身份不完整或相互冲突；不展示可信科学结论，保留完整原始 JSON 供核查和导出。</Alert>}
                    {bs4SavedRun(run) && <Alert severity="info">{run.status === 'COMPLETED_WITH_BLOCKERS'
                        ? 'BS4 未评价结果已保存；保留原原因，不补零分、强度或成功事件。当前材料许可独立核验，历史记录不授权提交。'
                        : 'BS4 良性方向证据已保存，不是目标最终良性分类。1或2事件均只一次 Strong / −4，不执行 PP1/PP4、不累计或自动采用；页面不冒称复验核心 RAW 字节。'}</Alert>}
                    {bs4ScopeClaim(run) && !bs4SavedRun(run) && <Alert severity="warning">保存的 BS4 身份不完整或相互冲突；不展示可信科学结论，保留完整原始 JSON 供核查和导出。</Alert>}
                    {pp1SavedRun(run) && <Alert severity="info">PP1 致病方向证据已保存，不是目标变异最终分类。单核家系后端保存点数与强度原样展示；不执行 PP4/BS4、不累计或自动采用。CORE_RESULT_ONLY 来源及完整原文保留；页面不冒称核验核心 RAW 字节。</Alert>}
                    {pp1ScopeClaim(run) && !pp1SavedRun(run) && <Alert severity="warning">保存的 PP1 身份不完整或相互冲突；不展示可信科学结论，保留完整原始 JSON 供核查和导出。</Alert>}
                    {bp2SavedRun(run) && <Alert severity="info">BP2 良性支持证据已保存；仅支持证据，不是目标变异良性分类。单例最大 Supporting，不累计、不自动采用。内层 CORE_RESULT_ONLY 保留核心来源，页面不重算核心 RAW 字节摘要。</Alert>}
                    {bp2ScopeClaim(run) && !bp2SavedRun(run) && <Alert severity="warning">保存的 BP2 身份不完整或相互冲突；不展示可信科学结论，保留完整原始 JSON 供核查和导出。</Alert>}
                    {pm3SavedRun(run) && <Alert severity="info">PM3 单家系双变异证据已保存，内层 CORE_RESULT_ONLY 保留核心来源标签；单例最大 Moderate，不自动PS2/PM6、不累计、不分类。伙伴P/LP不是目标最终分类。业务确认不是科学批准。页面不以对象重新序列化冒充核心 RAW 字节核验。</Alert>}
                    {pm3ScopeClaim(run) && !pm3SavedRun(run) && <Alert severity="warning">保存的 PM3 身份不完整或相互冲突；不展示可信科学结论，保留完整原始 JSON 供核查和导出。</Alert>}
                    {pm6SavedRun(run) && <Alert severity="info">PM6 单家系证据已保存，内层 CORE_RESULT_ONLY 保留核心来源标签；最大 Moderate，不自动 PS2、累积分或分类。背景确认不是科学批准，历史只读不授权当前提交。核心 RAW 摘要由服务端及归档原字节核验，页面不以对象重新序列化冒充字节核验。</Alert>}
                    {pm6ScopeClaim(run) && !pm6SavedRun(run) && <Alert severity="warning">保存的 PM6 身份不完整或相互冲突；不展示科学结论，保留完整原始 JSON 供核查和导出。</Alert>}
                    {ps2SavedRun(run) && <Alert severity="info">PS2 单家系证据已保存，内层 CORE_RESULT_ONLY 保留核心来源标签。积分和强度不是最终致病分类；NOT_MET 不等于良性，不自动 PM6。历史只读不授权当前提交，完整导出保留原记录。</Alert>}
                    {ps2ScopeClaim(run) && !ps2SavedRun(run) && <Alert severity="warning">保存的 PS2 身份不完整或相互冲突；不展示科学结论，保留完整原始记录供核查，不恢复提交资格。</Alert>}
                    {run.executionProfile?.profileId === 'acmg-pp3-bp4-development-v1' && (
                        <Alert severity="info">
                            {run.operation === 'CALIBRATE_SAVED_OBSERVATIONS'
                                ? `已保存的限定校准运行；直接来源：${run.sourceRunId}；原始观测祖先：${run.rawAncestorRunId}。已校准 ${run.calibratedMechanisms ?? 0}/2；规则应用尚未完成。`
                                : run.operation === 'ASSOCIATE_SAVED_OBSERVATIONS'
                                ? `已保存的派生关联结果；父观测运行：${run.sourceRunId}。新增取分与传输均为 0，校准和规则应用尚未执行。`
                                : '原始观测记录。未选择转录本不代表 VEP 没有兼容选项；读取此记录不会自动关联。'}
                        </Alert>
                    )}
                    {bp1SavedRun(run) && <Alert severity="info">BP1 MET/SUPPORTING 是良性方向支持证据，不是最终良性分类。截短主导不等于显性遗传；三项保存材料声明与本次评价分别展示。背景确认不是科学审核，TEST_ONLY 不是现实专业批准。NOT_MET 不反推 PP2/PVS1 或致病，未知未评价不是阴性；采用与最终分类未开放。</Alert>}
                    {pp2SavedRun(run) && <Alert severity="info">PP2 MET/SUPPORTING 是致病方向支持证据，不是最终致病分类。两个条件的保存材料声明与本次评价结果分别展示；背景确认不是科学审核，TEST_ONLY 不是现实专业批准。NOT_MET 不反推 BP1 或良性，未知未评价不是阴性；采用与最终分类未开放。</Alert>}
                    {bp1ScopeClaim(run) && !bp1SavedRun(run) && <Alert severity="warning">保存的 BP1 执行身份不受支持或相互冲突；只允许下载完整原始记录，不恢复当前提交资格。</Alert>}
                    {pp2ScopeClaim(run) && !pp2SavedRun(run) && <Alert severity="warning">保存的 PP2 执行身份不受支持或相互冲突；只允许下载完整原始记录，不恢复当前提交资格。</Alert>}
                    {bp3SavedRun(run) && <Alert severity="info">BP3 是良性方向支持证据，MET/SUPPORTING 不是最终良性分类。“无已知功能”是保存的受审适用声明，不等于实验已证明无功能。TEST_ONLY 合成材料不是专业批准；采用和最终分类未开放。NOT_MET 不反推 PM4 或致病，未评价不是阴性。</Alert>}
                    {pvs1NmdScopeClaim(run) && !pvs1NmdSavedRun(run) && <Alert severity="warning">{pvs1NmdSavedIssue(run)}</Alert>}
                    {pvs1NmdSavedRun(run) && <Alert severity="info">PVS1 按当前政策核验 NMD 或已核定的逃逸范围；预测不是实验结果，背景确认不是科学批准。TEST_ONLY 合成材料不代表科学采用，采用及最终分类未开放。未评价不等于不满足或良性。</Alert>}
                    {pm1SavedRun(run) && <Alert severity="info">PM1 受审区域限定工程；状态及实际强度按保存结果展示，不代表科学采用。TEST_ONLY 声明不是真实科学审核，采用及最终分类未开放。整体存在阻断不等于所有候选失败。</Alert>}
                    {savedPm4 && <Alert severity="info">PM4 限定工程评价；MET 与计算强度不代表科学采用，采用及最终分类未开放。整体存在阻断不等于所有候选失败。</Alert>}
                    {savedPs1 && <Alert severity="info">PS1 限定蛋白替换比较；强度按保存政策展示，不代表科学采用，采用及最终分类未开放。整体存在阻断不等于所有候选失败。</Alert>}
                    {savedPm5 && <Alert severity="info">PM5 同残基不同蛋白替换比较；实际强度及矩阵失败后的处理按保存政策展示，不重新计算矩阵。合成测试分数不是临床矩阵；不代表科学采用，采用及最终分类未开放。整体存在阻断不等于所有候选失败。</Alert>}
                </Stack>
            </details>
            </details>
            {!displayedCriteria.length && (
                <Alert severity="warning">
                    当前规则范围尚未加载，暂不展开证据；可下载原始保存结果。
                </Alert>
            )}
            {run.status === 'RUNNING' && (
                <Alert severity="info">
                    {trackingPaused
                        ? '自动更新已暂停，请读取保存结果确认。'
                        : controlLabels[run.executionControl?.state] || '保存记录标为运行中；须核对执行状态，不能仅凭旧记录认定后台仍在运行。'}
                </Alert>
            )}
            {run.status === 'FAILED' && (
                <Alert severity="error">
                    {run.operation === 'CALIBRATE_SAVED_OBSERVATIONS'
                        ? `校准已停止：${run.executionFailure?.stage || '阶段未知'} / ${run.executionFailure?.reasonCode || '原因未知'}。已保存部分结果和计数；同一来源不得再次计算。`
                        : '执行或保存失败，本运行没有成功结果。修正具体问题后显式重试。'}
                </Alert>
            )}
            <Button variant="outlined" onClick={download}>
                下载本次原始结果 JSON
            </Button>
            {revelSavedDetails(run).map((row) => (
                <Paper variant="outlined" sx={{ p: 2 }} key={`${row.candidateId}/${row.hypothesisId}`}>
                    <Typography>REVEL 蛋白分支 · {row.candidateId} · {row.hypothesisId}</Typography>
                    <Typography>{row.background}</Typography>
                    <Typography>规范：{row.policy || '未选中'} · 模型：{row.model ? JSON.stringify(row.model) : '缺失'}</Typography>
                    <Typography>原分数：{row.score} · 来源条款：{row.sourceOutcome}</Typography>
                    <Typography>采用：{row.adoption} · 实际强度：{row.strength}</Typography>
                    <Typography sx={{ overflowWrap: 'anywhere' }}>原因：{row.reason || '无'} · 来源：{row.sourceRunId} / {row.clauseResultId || '未形成'}</Typography>
                    <Alert severity="info">仅限所示政策的 PP3 蛋白分支。BP4 缺少 MaxEntScan 附加核验；未排除其他剪接机制，不生成最终分类。</Alert>
                </Paper>
            ))}
            {displayedCriteria.length > 0 && run.executionProfile?.profileId !== 'acmg-revel-pp3-application-v1' &&
                (unifiedLocalScope(run) ? <AcmgResultsWorkbench run={run} criteria={displayedCriteria} sourceTaskContext={sourceTaskContext} /> : (run.candidates || []).map((candidate, i) => (
                    <Paper variant="outlined" sx={{ p: 2 }} key={candidate.candidateId}>
                        <Typography fontWeight={600}>
                            变异 #{candidate.rank ?? i + 1}
                        </Typography>
                        {[...new Set((candidate.attempts || []).map(a => {
                            const allele = a.context?.variant?.normalizedAllele || a.context?.variant?.inputAllele;
                            return allele?.assembly && allele?.contig && allele?.position && allele?.reference && allele?.alternate
                                ? `${a.context?.gene?.symbol ? `${a.context.gene.symbol} · ` : ''}${allele.assembly} ${formatVariantCoordinate(allele)}` : null;
                        }).filter(Boolean))].map(label => <Typography key={label} fontWeight={600} sx={{ overflowWrap: 'anywhere' }}>保存的变异：{label}</Typography>)}
                        <SampleCallSummary candidate={candidate} />
                        {localBatchSavedRun(run) && (candidate.attempts || []).some(a => a.interpretationContext) &&
                            <details><summary>本次解释背景</summary>{(candidate.attempts || []).filter(a => a.interpretationContext).map((a, index) => (
                                <Typography variant="body2" key={`interpretation-${index}`}>
                                    {localInterpretationContextText(a.interpretationContext)}
                                </Typography>
                            ))}</details>}
                        <CandidateEvidenceResults key={`${run.runId}:${candidate.candidateId}`} candidate={candidate} criteria={displayedCriteria} run={run} sourceTaskContext={sourceTaskContext} />
                    </Paper>
                )))}
        </Stack>
    );
}

function Ps2Background({ options }) {
    const p = options.ps2Preparation, b = p.materialBinding, c = b.context, patient = c.patientContext;
    const allele = c.variant.inputAllele;
    return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1}>
        <Typography variant="h6">PS2 固定单家系材料 · Top1</Typography>
        <Typography>变异：{allele.assembly} {formatVariantCoordinate(allele)}</Typography>
        <Typography>基因 / 疾病 / MOI：{c.gene.symbol}（{c.gene.hgncId}） / {c.disease.label}（{c.disease.mondoId}） / {c.moi}</Typography>
        <Typography>患者：{patient.patientContextId} · 角色：{patient.role} · 性别：{patient.sex} · 受累原值：{patient.affectedStatus}</Typography>
        <Typography>家系：{patient.familyContextId} · 样本：{options.sampleId} · 数据类别：{c.dataClass}</Typography>
        <Typography>病例：{b.caseId} · 观察：{b.observationId} · occurrence：{b.occurrenceId}</Typography>
        <Alert severity="info">材料已绑定，科学适用性尚未评价。审核动作不等于亲子已确认；未返回的双亲检测或 GT 不作推断。</Alert>
        <Accordion><AccordionSummary>临床事实与已有审核材料（只读）</AccordionSummary><AccordionDetails>
            <Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify({ clinicalFacts: p.clinicalFacts, review: p.review }, null, 2)}</Typography>
        </AccordionDetails></Accordion>
        <Accordion><AccordionSummary>保存来源身份详情</AccordionSummary><AccordionDetails>
            <Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify({ profile: options.executionProfile, preparationToken: p.preparationToken, publishedInputIdentity: p.publishedInputIdentity }, null, 2)}</Typography>
            <Typography>页面核对小型身份投影；原 VCF、完整候选及材料原始字节摘要由服务端核验。</Typography>
        </AccordionDetails></Accordion>
    </Stack></Paper>;
}

function Pm6Background({ options }) {
    const p = options.pm6Preparation, b = p.materialBinding, c = b.context, patient = c.patientContext;
    const allele = c.variant.inputAllele;
    return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1}>
        <Typography variant="h6">PM6 固定单家系材料 · Top1</Typography>
        <Typography>变异：{allele.assembly} {formatVariantCoordinate(allele)}</Typography>
        <Typography>基因 / 疾病 / MOI：{c.gene.symbol}（{c.gene.hgncId}） / {c.disease.label}（{c.disease.mondoId}） / {c.moi}</Typography>
        <Typography>患者：{patient.patientContextId} · 角色：{patient.role} · 性别：{patient.sex} · 受累原值：{patient.affectedStatus}</Typography>
        <Typography>家系：{patient.familyContextId} · 样本：{options.sampleId} · 数据类别：{c.dataClass}</Typography>
        <Typography>病例：{b.caseId} · 观察：{b.observationId} · occurrence：{b.occurrenceId}</Typography>
        <Alert severity="info">材料已绑定，科学适用性尚未评价。审核动作不等于亲子已确认；未返回的双亲检测或 GT 不作推断。</Alert>
        {(p.parentageBasis.relations || []).map((relation) => <Paper variant="outlined" key={relation.parentRole} sx={{ p: 1 }}>
            <Typography>{relation.parentRole} · 检测状态：{relation.testStatus} · 亲缘结论：{relation.conclusion} · 审核动作：{relation.review?.status}</Typography>
            <Typography>方法：{relation.method}</Typography><Typography>结果：{relation.result}</Typography><Typography>限制：{relation.limitations}</Typography>
            <Typography>审核人 / 日期：{relation.review?.reviewer} / {relation.review?.date}</Typography>
        </Paper>)}
        <Accordion><AccordionSummary>完整亲缘依据及来源原文（只读）</AccordionSummary><AccordionDetails>
            <Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(p.parentageBasis, null, 2)}</Typography>
        </AccordionDetails></Accordion>
        <Accordion><AccordionSummary>临床事实与已有审核材料（只读）</AccordionSummary><AccordionDetails>
            <Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify({ clinicalFacts: p.clinicalFacts, review: p.review }, null, 2)}</Typography>
        </AccordionDetails></Accordion>
        <Accordion><AccordionSummary>保存来源身份详情</AccordionSummary><AccordionDetails>
            <Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify({ profile: options.executionProfile, preparationToken: p.preparationToken, publishedInputIdentity: p.publishedInputIdentity }, null, 2)}</Typography>
            <Typography>页面核对小型身份投影；原 VCF、完整候选及材料原始字节摘要由服务端核验。</Typography>
        </AccordionDetails></Accordion>
    </Stack></Paper>;
}

function Pm3Background({ options }) {
    const p = options.pm3Preparation, b = p.materialBinding, c = b.context, o = p.reviewedObservation, patient = c.patientContext;
    const show = value => value === undefined || value === null ? '未提供' : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
    return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1}>
        <Typography variant="h6">PM3 固定双变异材料 · 仅评价 Top1 目标</Typography>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
            {['target', 'partner'].map(role => { const a = o.facts.alleles[role].inputAllele, rarity = o[role + 'Rarity']; return <Paper variant="outlined" key={role} sx={{ p: 1, flex: 1 }}>
                <Typography variant="subtitle1">{role === 'target' ? '目标变异（唯一评价目标）' : '伙伴变异（支持见证，不是第二评价目标）'}</Typography>
                <Typography>{role === 'target' ? p.candidateId : p.partnerCandidateId}</Typography>
                <Typography>{a.assembly} {formatVariantCoordinate(a)}</Typography>
                <Typography>亲本方向：{show(o.phase[role + 'Parent'])}</Typography>
                <Typography>稀有性：{show(rarity.conclusion)} · 资源：{show(rarity.resourceVersion)}</Typography>
                <Typography>查询 / 覆盖 / 适用：{show(rarity.queryOutcome)} / {show(rarity.coverage)} / {show(rarity.applicability)}</Typography>
                <Typography>AF 原值：{show(rarity.observedAf)} · 策略原值：{show(rarity.frequencyPolicy.comparison)} {show(rarity.frequencyPolicy.maximumAf)}</Typography>
            </Paper>; })}
        </Stack>
        <Typography>基因 / 疾病 / MOI：{c.gene.symbol}（{c.gene.hgncId}） / {c.disease.label}（{c.disease.mondoId}） / {c.moi}</Typography>
        <Typography>患者：{patient.patientContextId} · 角色：{patient.role} · 性别：{patient.sex} · 受累原值：{patient.affectedStatus}</Typography>
        <Typography>家系：{patient.familyContextId} · 样本：{options.sampleId} · 数据类别：{c.dataClass}</Typography>
        <Typography>病例：{o.facts.caseId} · 观察：{o.facts.observationId} · occurrence：{o.facts.occurrenceId}</Typography>
        <Typography>相位原结论：{show(o.phase.conclusion)} · 方法：{show(o.phase.method)} · 审核动作：{show(o.phase.review?.status)}</Typography>
        <Typography>伙伴外部分级：{show(o.partnerClassification.classification)} · 独立性原结论：{show(o.partnerClassification.independence?.conclusion)}</Typography>
        <Alert severity="info">材料已绑定，尚未评价科学适用性。伙伴 P/LP 不是目标变异或 Varvis 最终分类。GT 和单位点 phase 不能证明跨位点反式；检测完成、亲缘确认、审核动作与相位结论分别保留。UNKNOWN 就是未知。</Alert>
        <Typography>父母记录来自外部受审材料，不代表当前单样本分析验证了双亲原始数据。确认仅许可本次提交，不是科学批准。</Typography>
        {Object.entries({ '六条记录、检测、亲缘及来源原文': o.facts, '相位与记录定位': o.phase, '伙伴分类、独立性依据及来源': o.partnerClassification, '目标稀有性、完整频率策略及来源': o.targetRarity, '伙伴稀有性、完整频率策略及来源': o.partnerRarity, '已有材料审核': o.assessment,
            '保存来源身份详情': { profile: options.executionProfile, preparationToken: p.preparationToken, binding: b } }).map(([label, value]) => <Accordion key={label}><AccordionSummary>{label}（只读）</AccordionSummary><AccordionDetails><Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{show(value)}</Typography></AccordionDetails></Accordion>)}
        <Typography>页面核对返回的身份和内容关联；原 VCF、完整候选及材料原始字节由服务器核验。只评价单例，不累计、不自动切换 PS2/PM6、不分类。</Typography>
    </Stack></Paper>;
}

function Bp2Background({ options }) {
    const p = options.bp2Preparation, b = p.materialBinding, c = b.context, o = p.reviewedObservation, patient = c.patientContext;
    const show = value => value === undefined || value === null ? '未提供' : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
    return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1}>
        <Typography variant="h6">BP2 固定双变异材料 · 仅评价 Top1 目标</Typography>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={2}>
            {['target', 'partner'].map(role => { const a = o.facts.alleles[role].inputAllele; return <Paper variant="outlined" key={role} sx={{ p: 1, flex: 1 }}>
                <Typography variant="subtitle1">{role === 'target' ? '目标变异（唯一评价目标）' : '伙伴变异（支持见证，不是第二评价目标）'}</Typography>
                <Typography>{role === 'target' ? p.candidateId : p.partnerCandidateId}</Typography>
                <Typography>{a.assembly} {formatVariantCoordinate(a)}</Typography>
                <Typography>亲本方向：{show(o.phase[role + 'Parent'])}</Typography>
            </Paper>; })}
        </Stack>
        <Typography>基因 / 疾病 / MOI：{c.gene.symbol}（{c.gene.hgncId}） / {c.disease.label}（{c.disease.mondoId}） / {c.moi}</Typography>
        <Typography>患者：{patient.patientContextId} · 角色：{patient.role} · 性别：{patient.sex} · 受累原值：{patient.affectedStatus}</Typography>
        <Typography>家系：{patient.familyContextId} · 样本：{options.sampleId} · 数据类别：{c.dataClass}</Typography>
        <Typography>病例：{o.facts.caseId} · 观察：{o.facts.observationId} · occurrence：{o.facts.occurrenceId}</Typography>
        <Typography>相位原结论：{show(o.phase.conclusion)} · 方法：{show(o.phase.method)} · 审核动作：{show(o.phase.review?.status)}</Typography>
        <Typography>伙伴外部分级：{show(o.partnerClassification.classification)} · 独立性原结论：{show(o.partnerClassification.independence?.conclusion)}</Typography>
        <Typography>疾病适用模式：{show(o.diseaseApplicability.mode)} · 机制：{show(o.diseaseApplicability.mechanism)} · 原结论：{show(o.diseaseApplicability.applicability)}</Typography>
        <Typography>表型一致性：{show(o.diseaseApplicability.phenotypeConsistency)} · 额外效应：{show(o.diseaseApplicability.additionalEffect)}</Typography>
        <Typography>外显率材料：{show(o.diseaseApplicability.penetrance?.conclusion)} · 材料版本：{show(o.diseaseApplicability.materialVersion)}</Typography>
        {(o.diseaseApplicability.penetrance?.conditions || []).map((condition, i) => <Paper key={i} variant="outlined" sx={{ p: 1 }}>
            <Typography>{condition.kind} · 限制类型：{condition.constraint?.mode || '未提供结构化条件'}</Typography>
            <Typography>限制原文：{show(condition.requirement)}</Typography>
            <Typography>结构化限制：{show(condition.constraint)}</Typography>
            <Typography>患者结构化事实：{condition.patient ? show(Object.fromEntries(Object.entries(condition.patient).filter(([key]) => !['binding', 'materialVersion', 'mechanism'].includes(key)))) : '未提供'}</Typography>
            <Typography>患者事实原文：{show(condition.patientFact)}</Typography>
            <Typography>材料结论：{show(condition.conclusion)} · 依据：{show(condition.basis)}</Typography>
        </Paper>)}
        <Alert severity="info">以上并列保留材料条件、患者值及审核叙述，页面不替换冲突原值或计算科学结论。旧材料未提供结构化条件不等于无约束；cis 不要求外显率条件。</Alert>
        <Alert severity="info">发布样本 FT 由后端来源链核验；此处材料 sampleFilter 不代表发布见证。当前准备失败将撤销提交资格，历史记录仍可只读。</Alert>
        <Alert severity="info">材料已绑定，尚未评价科学适用性。伙伴 P 不是目标变异或 Varvis 最终分类。GT 和 PS 不能独立证明跨位点顺式或反式；检测完成、亲缘确认、审核动作与相位结论分别保留。UNKNOWN 就是未知。</Alert>
        <Typography>父母记录来自外部受审材料，不代表当前单样本分析验证了双亲原始数据。确认仅许可本次提交，不是科学批准。</Typography>
        {Object.entries({ '六条记录、检测、亲缘及来源原文': o.facts, '相位与记录定位': o.phase, '伙伴分类、独立性依据及来源': o.partnerClassification, '疾病适用、条件、审核与完整来源': o.diseaseApplicability, '已有材料审核': o.assessment,
            '保存来源身份详情': { profile: options.executionProfile, preparationToken: p.preparationToken, binding: b } }).map(([label, value]) => <Accordion key={label}><AccordionSummary>{label}（只读）</AccordionSummary><AccordionDetails><Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{show(value)}</Typography></AccordionDetails></Accordion>)}
        <Typography>页面核对返回的身份和内容关联；原 VCF、完整候选及材料原始字节由服务器核验。只评价单例，不累计、不自动切换 PS2/PM6、不分类。</Typography>
    </Stack></Paper>;
}

export default function OrdinaryAcmgEvaluationPanel({ analysisId, candidateCount, onReturnToCandidates }) {
    const [workflow, setWorkflow] = useState('CURRENT');
    const [backgroundSnapshot, setBackgroundSnapshot] = useState(null);
    const [evidenceSelectorCount, setEvidenceSelectorCount] = useState(null);
    const [workflows, setWorkflows] = useState([]);
    const initialN = candidateCount > 0 ? Math.min(20, candidateCount) : 20;
    const [topN, setTopN] = useState(String(initialN));
    const [options, setOptions] = useState(null);
    const [inputs, setInputs] = useState({});
    const [caseBinding, setCaseBinding] = useState(null);
    const [dataClass, setDataClass] = useState('PRIVACY_BOUNDED_RUNTIME');
    const [confirmed, setConfirmed] = useState(false);
    const [busy, setBusy] = useState(false);
    const [entryLoading, setEntryLoading] = useState(null);
    const [foregroundRead, setForegroundRead] = useState(null);
    const [feedback, setFeedback] = useState(null);
    const [submissionOperation, setSubmissionOperation] = useState(null);
    const submitting = submissionOperation !== null;
    const [pendingSubmission, setPendingSubmission] = useState(null);
    const [trackingPaused, setTrackingPaused] = useState(false);
    const [monitorVersion, setMonitorVersion] = useState(0);
    const [runs, setRuns] = useState([]);
    const [runId, setRunId] = useState('');
    const [{ run, showResults }, setRunView] = useState({ run: null, showResults: false });
    const displayRun = useCallback((value) => {
        setRunView({ run: value, showResults: hasSavedEvidenceResult(value) });
    }, []);
    const [executionControl, setExecutionControl] = useState(null);
    const [controlTarget, setControlTarget] = useState('');
    const activeControlTarget=useRef(controlTarget);
    activeControlTarget.current=controlTarget;
    const [controlBusy, setControlBusy] = useState(false);
    const [, setControlledRetry] = useState(false);
    const preparationControl = useRef(null);
    const materialTask = useRef(null);
    const executionPhase = useRef('IDLE');
    const queuedControl = useRef(null);
    const [associationSelections, setAssociationSelections] = useState({});
    useEffect(() => {
        setAssociationSelections({});
        setConfirmed(false);
    }, [analysisId, options, run?.runId]);
    const alive = useRef(true);
    const generation = useRef(0);
    const inFlight = useRef(false);
    const recordSection = useRef(null);
    const workspaceContent = useRef(null);
    const workspaceHeading = useRef(null);
    useLayoutEffect(() => {
        if (workspaceContent.current) workspaceContent.current.scrollTop = 0;
        workspaceHeading.current?.focus({ preventScroll: true });
    }, [showResults]);
    const readVersion = useRef(0);
    const analysisEpoch = useRef(0);
    const activeAnalysis = useRef(analysisId);
    activeAnalysis.current = analysisId;
    const refreshRuns = useCallback(async () => {
        const epoch = analysisEpoch.current;
        const values = await fetchAcmgBusinessRuns(analysisId);
        if (
            alive.current &&
            activeAnalysis.current === analysisId &&
            epoch === analysisEpoch.current
        )
            setRuns(values || []);
        return values || [];
    }, [analysisId]);
    const readRun = useCallback(
        async (id) => {
            if (!id) return;
            const ticket = ++readVersion.current;
            const value = validateSavedRun(await fetchAcmgResultView(analysisId, id), analysisId);
            if (value.runId !== id) throw new Error('保存结果与所选运行不一致，请核查来源。');
            if (
                alive.current &&
                activeAnalysis.current === analysisId &&
                ticket === readVersion.current
            ) {
                displayRun(value);
                setRunId(id);
                setTrackingPaused(false);
                setExecutionControl(previous => controlAfterHistory(previous,value));
                setControlTarget(['RUNNING','RECOVERY_REQUIRED','INTERRUPTED'].includes(value.status) ? id : '');
                return value;
            }
        },
        [analysisId, displayRun],
    );
    const loadOptions = useCallback(
        async (n) => {
            const ticket = ++generation.current;
            const current = () => alive.current && activeAnalysis.current === analysisId && ticket === generation.current;
            setOptions(null);
            setConfirmed(false);
            setCaseBinding(null);
            try {
                let response = await fetchAcmgInputOptions(analysisId, 1, workflow);
                if (!current()) return;
                let value = validateInputOptions(response, analysisId);
                if (workflow !== 'CURRENT' && (value.selectedWorkflow !== workflow || !localBatchScopeClaim(value)))
                    throw new Error('当前服务未返回所选证据入口；请重新选择，未授予提交许可。');
                if (!currentBusinessCriteria(value).length) throw new Error('当前执行范围未知或不完整，请重新读取；未授予提交许可。');
                if (workflow !== 'CURRENT' && !(pp4ScopeClaim(value) || bs4ScopeClaim(value) || pp1ScopeClaim(value) || bp2ScopeClaim(value) || ps2ScopeClaim(value) || pm6ScopeClaim(value) || pm3ScopeClaim(value)) && n !== 1) {
                    response = await fetchAcmgInputOptions(analysisId, n, workflow);
                    if (!current()) return;
                    value = validateInputOptions(response, analysisId);
                    if (workflow !== 'CURRENT' && (value.selectedWorkflow !== workflow || !localBatchScopeClaim(value)))
                        throw new Error('读取期间证据入口发生变化；请重新读取。');
                    if (pp4ScopeClaim(value) || bs4ScopeClaim(value) || pp1ScopeClaim(value) || bp2ScopeClaim(value) || ps2ScopeClaim(value) || pm6ScopeClaim(value) || pm3ScopeClaim(value) || !currentBusinessCriteria(value).length || value.requestedN !== n)
                        throw new Error('两阶段读取的范围或数量已变化，请重新读取；未提交评价。');
                }
                if (!current()) return;
                setOptions(value);
                setWorkflows(Array.isArray(value.availableWorkflows) ? value.availableWorkflows.filter((item) =>
                    ['CURRENT', 'LOCAL_COMPUTATIONAL_BATCH1'].includes(item.id) && typeof item.label === 'string') : []);
                // CURRENT only probes the enabled scope here; prepare the selected N on demand.
                setTopN(String(workflow === 'CURRENT' ? n : value.requestedN));
                if (pp4ScopeClaim(value) || bs4ScopeClaim(value) || pp1ScopeClaim(value) || bp2ScopeClaim(value) || ps2ScopeClaim(value) || pm6ScopeClaim(value) || pm3ScopeClaim(value)) {
                    setInputs({});
                    setDataClass((value.pp4Preparation || value.bs4Preparation || value.pp1Preparation || value.bp2Preparation || value.pm3Preparation || value.pm6Preparation || value.ps2Preparation).materialBinding.context.dataClass);
                } else setInputs((previous) => unifiedLocalScope(value)
                    ? prepareAutomaticInputs(value, previous) : prepareSimplifiedInputs(value, previous));
                setConfirmed(false);
            } catch (error) {
                if (!current()) return;
                setOptions(null);
                setConfirmed(false);
                throw error;
            }
        },
        [analysisId, workflow],
    );
    useEffect(() => {
        alive.current = true;
        let cancelled = false;
        displayRun(null);
        setBackgroundSnapshot(null);
        setEvidenceSelectorCount(null);
        setOptions(null);
        setInputs({});
        setCaseBinding(null);
        setTopN(String(initialN));
        setDataClass('PRIVACY_BOUNDED_RUNTIME');
        setConfirmed(false);
        setRunId('');
        setRuns([]);
        setPendingSubmission(null);
        setFeedback(null);
        setSubmissionOperation(null);
        setTrackingPaused(false);
        setExecutionControl(null);setControlTarget('');setControlBusy(false);setControlledRetry(false);
        inFlight.current = false;
        setBusy(true);
        setForegroundRead(null);
        setEntryLoading({ startedAt: Date.now(), options: '读取中', history: '读取中' });
        const markEntry = (key, status) => {
            if (!cancelled && alive.current) setEntryLoading(old => old && ({ ...old, [key]: status }));
        };
        const observeEntry = (promise, key) => promise.then(value => {
            markEntry(key, '已完成'); return value;
        }, error => { markEntry(key, '读取失败'); throw error; });
        Promise.allSettled([
            observeEntry(loadOptions(initialN), 'options'),
            observeEntry(refreshRuns().then((v) => {
                if (cancelled || !v[0]) return;
                setRunId(v[0].runId);
                // Completed multi-megabyte history is read on demand, not a prerequisite for new input.
                if (activeControl(v[0].status) || ['RECOVERY_REQUIRED','INTERRUPTED'].includes(v[0].status)) {
                    // Restore observation, never restart execution or read multi-megabyte history on entry.
                    return fetchAcmgControl(analysisId,v[0].runId).then(control => {
                        if (cancelled) return;
                        setControlTarget(v[0].runId);setExecutionControl(control);
                        displayRun({analysisId,runId:v[0].runId,status:control.state,resultsAvailable:false});
                    });
                }
            }), 'history'),
        ])
            .then((r) => {
                if (!cancelled && alive.current) {
                    const failure = r.find((v) => v.status === 'rejected');
                    if (failure) setFeedback(acmgRequestFeedback(failure.reason));
                }
            })
            .finally(() => {
                if (!cancelled && alive.current) { setBusy(false); setEntryLoading(null); }
            });
        return () => {
            preparationControl.current?.command('STOP');
            alive.current = false;
            cancelled = true;
            generation.current += 1;
            readVersion.current += 1;
            analysisEpoch.current += 1;
        };
    }, [analysisId, displayRun, loadOptions, refreshRuns, initialN]);
    useEffect(() => {
        if (!controlTarget) return undefined;
        let cancelled=false,timer;
        const poll=async()=>{
            try {
                const value=await fetchAcmgControl(analysisId,controlTarget);
                if (cancelled) return;
                let actual=value;
                if (queuedControl.current) {
                    const pending=queuedControl.current;queuedControl.current=null;
                    const action=typeof pending==='string'?pending:pending.action;
                    const target={analysisId,runId:controlTarget};
                    const offered=controlActions(value,target).actions.find(item=>item.controlAction===action);
                    // A queued explicit click may arrive after completion or a capability change.
                    // Do not post a stale command or replace the actual returned state.
                    if (offered && (!offered.requiresConfirmation || pending.confirmed || confirmRunControl(action)))
                        actual=await commandWithReconciliation(action,
                            () => commandAcmgControl(analysisId,controlTarget,action),
                            () => fetchAcmgControl(analysisId,controlTarget));
                }
                if (cancelled) return;
                setExecutionControl(actual);
                if (terminalControl(actual.state) || actual.state === 'PAUSED')
                    setFeedback(previous => previous?.controlNotice ? null : previous);
                if (activeControl(actual.state)) timer=setTimeout(poll,2500);
                else if (inFlight.current) timer=setTimeout(poll,2500);
                else if (!['RECOVERY_REQUIRED'].includes(actual.state)) await readRun(controlTarget);
            } catch (error) {
                if (!cancelled) {setFeedback({controlNotice:true,severity:'warning',title:'运行控制状态暂不可用',message:'没有重新启动分析。请核对连接后刷新状态，勿重复提交。'});timer=setTimeout(poll,5000);}
            }
        };
        poll();return()=>{cancelled=true;clearTimeout(timer);};
    },[analysisId,controlTarget,monitorVersion,readRun]);
    useEffect(() => {
        if (busy || !runId || run?.status !== 'RUNNING' || trackingPaused || controlTarget) return undefined;
        const ticket = readVersion.current;
        return watchAcmgRun({
            analysisId,
            runId,
            read: fetchAcmgResultView,
            onRun: (value) => {
                if (
                    !alive.current ||
                    activeAnalysis.current !== analysisId ||
                    ticket !== readVersion.current
                )
                    return;
                const validated = validateSavedRun(value, analysisId);
                displayRun(validated);
                setRuns((previous) =>
                    previous.map((item) =>
                        item.runId === runId ? { ...item, status: validated.status } : item,
                    ),
                );
                setFeedback(null);
            },
            onError: (error, { paused }) => {
                if (
                    !alive.current ||
                    activeAnalysis.current !== analysisId ||
                    ticket !== readVersion.current
                )
                    return;
                const notice = acmgRequestFeedback(error);
                setFeedback({
                    ...notice,
                    message:
                        notice.severity === 'error'
                            ? notice.message
                            : paused
                              ? '连续几次未能更新状态，自动更新已暂停。后台运行不会因此被取消，请稍后点击“读取保存结果”。'
                              : '暂未取得最新状态，页面会稍后继续查询。后台运行不会因此被取消。',
                });
                setTrackingPaused(paused);
            },
        });
    }, [analysisId, runId, run?.status, trackingPaused, monitorVersion, busy, controlTarget, displayRun]);
    const act = async (operation, operationName = 'read') => {
        if (inFlight.current) return;
        const epoch = analysisEpoch.current;
        inFlight.current = true;
        setBusy(true);
        setForegroundRead({ startedAt: Date.now(), operation: operationName });
        setFeedback(null);
        try {
            await operation();
        } catch (e) {
            if (
                alive.current &&
                activeAnalysis.current === analysisId &&
                epoch === analysisEpoch.current
            ) {
                if (e.message==='ACMG_USER_STOPPED') {
                    setExecutionControl({state:'CANCELLED'});executionPhase.current='IDLE';
                    clearGuidedConfirmation();
                }
                else if (executionPhase.current==='PREPARATION') {setExecutionControl(null);executionPhase.current='IDLE';}
                setFeedback(e.automaticSubmissionUncertain ? { severity: 'warning', title: '资料任务回执待确认', message: e.message } : acmgRequestFeedback(e, operationName));
                setTrackingPaused(true);
            }
        } finally {
            if (
                alive.current &&
                activeAnalysis.current === analysisId &&
                epoch === analysisEpoch.current
            ) {
                inFlight.current = false;
                setBusy(false);
                setForegroundRead(null);
            }
        }
    };
    const updateInput = (id, changes) => {
        setInputs((old) => ({
            ...old,
            [id]: { ...old[id], ...changes },
        }));
        setConfirmed(false);
    };
    const pp4Claim = pp4ScopeClaim(options);
    const pp4Issue = pp4ScopeIssue(options, analysisId);
    const pp4SavedConflict = pp4ScopeClaim(run) && !pp4SavedRun(run) && !['FAILED', 'RUNNING'].includes(run?.status);
    useEffect(() => { if (pp4Claim) setConfirmed(false); }, [run, pp4Claim]);
    const bs4Claim = bs4ScopeClaim(options);
    const bs4Issue = bs4ScopeIssue(options, analysisId);
    const bs4SavedConflict = bs4ScopeClaim(run) && !bs4SavedRun(run) && !['FAILED', 'RUNNING'].includes(run?.status);
    useEffect(() => { if (bs4Claim) setConfirmed(false); }, [run, bs4Claim]);
    const pp1Claim = pp1ScopeClaim(options);
    const pp1Issue = pp1ScopeIssue(options, analysisId);
    const pp1SavedConflict = pp1ScopeClaim(run) && !pp1SavedRun(run) && !['FAILED', 'RUNNING'].includes(run?.status);
    useEffect(() => { if (pp1Claim) setConfirmed(false); }, [run, pp1Claim]);
    const bp2Claim = bp2ScopeClaim(options);
    const bp2Issue = bp2ScopeIssue(options, analysisId);
    const pm3Claim = pm3ScopeClaim(options);
    const pm3Issue = pm3ScopeIssue(options, analysisId);
    const pm6Claim = pm6ScopeClaim(options);
    const pm6Issue = pm6ScopeIssue(options, analysisId);
    const ps2Claim = ps2ScopeClaim(options);
    const ps2Issue = ps2ScopeIssue(options, analysisId);
    const singleFamilyClaim = pp4Claim || bs4Claim || pp1Claim || bp2Claim || ps2Claim || pm6Claim || pm3Claim;
    const localBatchClaim = localBatchScopeClaim(options);
    const localBatchIssue = localBatchScopeIssue(options);
    const candidateStates = options && !singleFamilyClaim
        ? options.candidates.map((candidate) => ({
              candidate,
              ...candidateInputStatus(candidate, inputs[candidate.candidateId], options),
          }))
        : [];
    const pm4Claim = pm4ScopeClaim(options);
    const bp1Claim = bp1ScopeClaim(options);
    const bp1Issue = bp1ScopeIssue(options);
    const pp2Claim = pp2ScopeClaim(options);
    const pp2Issue = pp2ScopeIssue(options);
    const bp3Claim = bp3ScopeClaim(options);
    const bp3Issue = bp3ScopeIssue(options);
    const pvs1Claim = pvs1NmdScopeClaim(options);
    const pvs1Issue = pvs1NmdScopeIssue(options);
    const pvs1SavedConflict = pvs1NmdScopeClaim(run) && Boolean(pvs1NmdSavedIssue(run));
    useEffect(() => { if (pvs1Claim) setConfirmed(false); }, [run, pvs1Claim]);
    const pm1Claim = pm1ScopeClaim(options);
    const pm1Issue = pm1ScopeIssue(options);
    const pm4Issue = pm4ScopeIssue(options);
    const ps1Claim = ps1ScopeClaim(options);
    const ps1Issue = ps1ScopeIssue(options);
    const pm5Claim = pm5ScopeClaim(options);
    const pm5Issue = pm5ScopeIssue(options);
    const ruleOnlyClaim = bp1Claim || pp2Claim || bp3Claim || pvs1Claim || pm1Claim || pm4Claim || ps1Claim || pm5Claim;
    const readyCount = candidateStates.filter(
        (state) => state.ready || state.deNovoReady || (!ruleOnlyClaim && !localBatchClaim && (state.pm2Ready || state.ba1Ready || state.bs1Ready)),
    ).length;
    const catalogBa1 = Boolean(options?.executionProfile?.ba1RuntimeBinding);
    const ba1OnlyCount = candidateStates.filter((state) => !state.ready && state.ba1Ready).length;
    const criteria = pvs1Claim && pvs1SavedConflict ? [] : currentBusinessCriteria(options);
    const bs1Enabled = criteria.includes('ACMG_AMP_2015:BS1');
    const predictionEnabled = criteria.includes('ACMG_AMP_2015:PP3');
    const associationOnly = options?.executionProfile?.operation === 'ASSOCIATE_SAVED_OBSERVATIONS';
    const calibrationOnly = options?.executionProfile?.operation === 'CALIBRATE_SAVED_OBSERVATIONS';
    const calibrationEligible = savedCalibrationParent(options, run, analysisId);
    const [adoptionBinding, setAdoptionBinding] = useState(null);
    const currentAdoptionBinding = options?.revelApplicationBinding?.bindingSha256;
    const adoptionConfirmed = Boolean(adoptionBinding && adoptionBinding === currentAdoptionBinding);
    useEffect(() => setAdoptionBinding(null), [analysisId, run?.runId, currentAdoptionBinding]);
    const revelEnabled = options?.executionProfile?.profileId === 'acmg-revel-pp3-application-v1';
    const parentEligible = savedObservationParent(run, analysisId);
    const draftIdentity = pp4Claim ? JSON.stringify([analysisId, options.executionProfile, options.pp4Preparation, options.pp4Selection]) : bs4Claim ? JSON.stringify([analysisId, options.executionProfile, options.bs4Preparation, options.bs4Selection]) : pp1Claim ? JSON.stringify([analysisId, options.executionProfile, options.pp1Preparation, options.pp1Selection]) : bp2Claim ? JSON.stringify([analysisId, options.executionProfile, options.bp2Preparation, options.bp2Selection]) : pm3Claim ? JSON.stringify([analysisId, options.executionProfile, options.pm3Preparation, options.pm3Selection]) : pm6Claim ? JSON.stringify([analysisId, options.executionProfile, options.pm6Preparation, options.pm6Selection]) : ps2Claim ? JSON.stringify([analysisId, options.executionProfile, options.ps2Preparation, options.ps2Selection]) : associationOnly || calibrationOnly
        ? JSON.stringify([analysisId, topN, run?.runId, associationSelections])
        : localBatchClaim || pvs1Claim ? JSON.stringify([declarationDraftIdentity(analysisId, topN, dataClass, inputs), options.executionProfile, options.enabledCriteria, caseBinding])
        : declarationDraftIdentity(analysisId, topN, dataClass, inputs);
    const [submittedDraft, setSubmittedDraft] = useState(null);
    const inputLocked =
        busy || submitting || Boolean(pendingSubmission) || activeControl(executionControl?.state)
        || executionControl?.state==='RECOVERY_REQUIRED' || (run?.status === 'RUNNING' && !executionControl);
    const maximumN = candidateCount > 0 ? Math.min(50, candidateCount) : 50;
    const [automaticStage, setAutomaticStage] = useState('');
    const [measuredProgress, setMeasuredProgress] = useState(null);
    const [automaticNotes, setAutomaticNotes] = useState([]);
    const clearGuidedConfirmation = () => {
        setConfirmed(false);
        setBackgroundSnapshot(null);
        setEvidenceSelectorCount(null);
    };
    const prepareBackground = () => act(async () => {
        const epoch = analysisEpoch.current;
        if (!/^[1-9][0-9]*$/.test(topN) || Number(topN) > maximumN)
            throw new Error(`请选择 1 至 ${maximumN} 个变异。`);
        clearGuidedConfirmation();
        setAutomaticStage(''); setMeasuredProgress(null);
        const snapshot = await fetchAcmgBackground(analysisId, Number(topN));
        if (!alive.current || activeAnalysis.current !== analysisId || epoch !== analysisEpoch.current) return;
        if (snapshot.backgroundHandling !== 'AUTOMATIC_DISTINCT_CONTEXTS_V1') throw new Error('后端尚未启用多背景分析，请更新并重启后端后重新准备；未启动评价。');
        if (snapshot.schemaVersion !== 'acmg-common-background-v1' || !/^[a-f0-9]{64}$/.test(snapshot.backgroundSha256 || ''))
            throw new Error('背景准备响应身份不完整，请重新准备。');
        const value = validateInputOptions(snapshot.options, analysisId);
        const issue = backgroundOptionsIssue(value);
        if (issue) {
            setFeedback(acmgRequestFeedback(null, 'prepare', issue));
            return;
        }
        if (!unifiedLocalScope(value) || currentBusinessCriteria(value).length !== 18 || value.requestedN !== Number(topN) ||
            value.executionProfile?.candidateSelectionPolicy !== 'DISTINCT_ALLELE_FIRST_FORMAL_OCCURRENCE_V1' ||
            new Set(value.candidateIds).size !== value.selectedCount || !Array.isArray(snapshot.literature?.contexts))
            throw new Error('当前服务未返回本次选择的18条证据范围，未授予分析许可。');
        setOptions(value);
        setInputs(prepareGuidedInputs(value, snapshot.literature?.contexts || [], backgroundSnapshot ? inputs : {}));
        setBackgroundSnapshot(snapshot);
    }, 'prepare');
    const openEvidenceSelector = () => {
        if (!confirmed || !backgroundSnapshot || inputLocked) return;
        try {
            const declaration = buildGuidedDeclaration(options, analysisId, inputs,
                backgroundSnapshot.literature?.contexts || [], dataClass, caseBinding);
            setFeedback(null);
            setEvidenceSelectorCount(declaration.hypotheses.length);
        } catch (error) {
            setConfirmed(false);
            setFeedback(acmgRequestFeedback(error, 'prepare'));
        }
    };
    const submit = (retry = false, adopt = false, automatic = false, selectedCriteria = null) =>
        act(async () => {
            const preparedOptions = options, preparedInputs = inputs;
            if (automatic && (inputLocked || workflow !== 'CURRENT' || !confirmed || !backgroundSnapshot ||
                String(options?.requestedN) !== topN)) throw new Error('请先准备并确认当前背景。');
            if (retry && Boolean(localBatchScopeClaim(run)) !== Boolean(localBatchClaim) &&
                !localBatchRetryEligible(run, analysisId, options))
                throw new Error('此历史记录属于另一证据入口，不能跨入口重试；请确认后创建当前入口的新运行。');
            if (!automatic && localBatchClaim && (!confirmed || inputLocked || localBatchIssue || !criteria.length ||
                !options?.selectedCount || String(options.requestedN) !== topN ||
                (retry && !localBatchRetryEligible(run, analysisId, options))))
                throw new Error('请读取并明确确认当前五条证据范围与所选背景；历史记录不能授权当前提交或跨模式重试。');
            if (singleFamilyClaim && inputLocked) throw new Error('单家系 当前正在处理请求，请勿重复提交。');
            if ((bp1Claim || pp2Claim || bp3Claim || pvs1Claim) && (!confirmed || inputLocked || (ruleOnlyClaim && !readyCount) || !criteria.length ||
                !options?.selectedCount || String(options.requestedN) !== topN))
                throw new Error(`${bp1Claim ? 'BP1' : pp2Claim ? 'PP2' : pvs1Claim ? 'PVS1' : 'BP3'} 请先读取当前候选并完成精确背景确认。`);
            let submissionBackground = backgroundSnapshot?.backgroundSha256;
            let declaration = pp4Claim ? buildPp4Declaration(options, analysisId, confirmed, retry ? run : undefined) : bs4Claim ? buildBs4Declaration(options, analysisId, confirmed, retry ? run : undefined) : pp1Claim ? buildPp1Declaration(options, analysisId, confirmed, retry ? run : undefined) : bp2Claim ? buildBp2Declaration(options, analysisId, confirmed, retry ? run : undefined) : pm3Claim ? buildPm3Declaration(options, analysisId, confirmed, retry ? run : undefined) : pm6Claim ? buildPm6Declaration(options, analysisId, confirmed, retry ? run : undefined) : ps2Claim ? buildPs2Declaration(options, analysisId, confirmed, retry ? run : undefined) : adopt
                ? buildRevelApplicationDeclaration(options, analysisId, run, adoptionBinding)
                : calibrationOnly
                ? buildSavedCalibrationDeclaration(options, analysisId, run)
                : associationOnly
                ? buildSavedObservationAssociationDeclaration(options, analysisId, run, associationSelections)
                : automatic ? buildGuidedDeclaration(preparedOptions, analysisId, preparedInputs, backgroundSnapshot.literature?.contexts || [], dataClass, caseBinding) : buildSimplifiedCaseDeclaration(
                preparedOptions,
                analysisId,
                preparedInputs,
                retry ? run?.runId : undefined,
                dataClass,
                caseBinding,
            );
            if (automatic && selectedCriteria) {
                if (!selectedCriteria.length || selectedCriteria.some(id => !criteria.includes(id))) throw new Error('请选择有效证据。');
                declaration.selectedCriteria = criteria.filter(id => selectedCriteria.includes(id));
                declaration.operationId = window.crypto.randomUUID();
            }
            if (automatic && retry) declaration.retryOf=run.runId;
            if (automatic) {
                const epoch = analysisEpoch.current;
                setAutomaticNotes([]); setMeasuredProgress(null);
                setControlTarget(''); queuedControl.current = null; executionPhase.current = 'PREPARATION';
                const localControl = createPreparationControl(state => {
                    if (alive.current && activeAnalysis.current === analysisId && analysisEpoch.current === epoch)
                        setExecutionControl({state: state === 'CANCELLED' && materialTask.current ? 'STOP_REQUESTED' : state});
                });
                preparationControl.current = localControl;
                setExecutionControl({state: 'RUNNING'});
                setAutomaticStage('已确认背景，正在按适用性准备专用资料');
                let acceptedSource = false;
                const notes = await prepareAutomaticLiterature({ analysisId, topN: Number(topN), declaration,
                    candidateLabels: Object.fromEntries((preparedOptions.candidates || []).map(c => [c.candidateId, c.geneSymbol])),
                    controlPoint:()=>localControl.checkpoint(),
                    onAccepted:()=>{acceptedSource = true;},
                    onTask:async task=>{
                        if (!alive.current || activeAnalysis.current!==analysisId || analysisEpoch.current!==epoch) return;
                        materialTask.current=task;
                        if (task && ['QUEUED','RUNNING'].includes(task.status) && localControl.state()==='CANCELLED')
                            await axios.post(`${task.base}/tasks/${task.taskId}/cancel`,null,{timeout:30000});
                    },
                    checkpoint: automaticCheckpoint(window.sessionStorage, `${analysisId}:${(declaration.selectedCriteria || criteria).join(",")}`),
                    get: async (url, params) => (await axios.get(url, { params, timeout: 60000 })).data,
                    post: async (url, body, headers) => (await axios.post(url, body, { timeout: 180000, headers })).data,
                    progress: setAutomaticStage,
                    onNotes: notes => {
                        if (alive.current && activeAnalysis.current === analysisId && analysisEpoch.current === epoch) setAutomaticNotes(notes);
                    },
                    active: () => alive.current && activeAnalysis.current === analysisId && analysisEpoch.current === epoch });
                setAutomaticNotes(notes);
                await localControl.checkpoint();
                if (acceptedSource) {
                    const refreshed = await fetchAcmgBackground(analysisId, Number(topN));
                    if (!alive.current || activeAnalysis.current !== analysisId || analysisEpoch.current !== epoch) return;
                    await localControl.checkpoint();
                    if (refreshed.schemaVersion !== 'acmg-common-background-v1' ||
                        refreshed.backgroundHandling !== 'AUTOMATIC_DISTINCT_CONTEXTS_V1' ||
                        !/^[a-f0-9]{64}$/.test(refreshed.backgroundSha256 || ''))
                        throw new Error('新增资料后未能核对本次分析背景，请重新准备。');
                    const freshOptions = validateInputOptions(refreshed.options,analysisId);
                    const issue = backgroundOptionsIssue(freshOptions);
                    if (issue) {
                        clearGuidedConfirmation();
                        setExecutionControl(null); executionPhase.current = 'IDLE';
                        setAutomaticStage('');
                        setFeedback(acmgRequestFeedback(null, 'prepare', issue));
                        return;
                    }
                    let freshInputs;
                    try {
                        freshInputs = rebindGuidedInputsAfterIntake(backgroundSnapshot,{...refreshed,options:freshOptions},preparedInputs);
                    } catch (error) {
                        clearGuidedConfirmation();
                        throw error;
                    }
                    const scope = declaration.selectedCriteria;
                    declaration = buildGuidedDeclaration(freshOptions,analysisId,freshInputs,
                        refreshed.literature?.contexts || [],dataClass,caseBinding);
                    if (scope) declaration.selectedCriteria = scope;
                    submissionBackground = refreshed.backgroundSha256;
                }
                executionPhase.current='EVALUATION';materialTask.current=null;
                setAutomaticStage(`正在评价 ${preparedOptions.selectedCount} 个不同变异、${declaration.hypotheses.length} 个明确背景，各${(declaration.selectedCriteria || criteria).length}条证据并分别保存；请等待`);
            }
            const submittedIdentity = draftIdentity;
            const ticket = ++readVersion.current;
            const epoch = analysisEpoch.current;
            const currentAnalysis = () => alive.current && activeAnalysis.current === analysisId && epoch === analysisEpoch.current;
            const current = () => currentAnalysis() && ticket === readVersion.current;
            let observedProgress;
            let runToRead;
            const startedAt = new Date().toLocaleString();
            setSubmissionOperation(adopt ? 'ADOPT_SAVED_REVEL_PP3' : 'EVALUATE');
            try {
                const value = validateSavedRun(
                    await (automatic ? submitConfirmedAcmgRun(analysisId, declaration, submissionBackground, value => {
                        if (!current()) return;
                        setAutomaticStage(liveProgressText(value, preparedOptions.candidates, declaration.hypotheses));
                        if (value.status === 'UNAVAILABLE') return;
                        observedProgress = value;
                        setMeasuredProgress({analysisId, timing:value.timing});
                        if (value.runId && !terminalControl(value.status)) setControlTarget(value.runId);
                    })
                        : submitAcmgBusinessRun(analysisId, declaration, workflow)),
                    analysisId,
                );
                if (!current()) return;
                setSubmittedDraft({ runId: value.runId, identity: submittedIdentity });
                displayRun(value);
                setRunId(value.runId);
                setPendingSubmission(null);
                setTrackingPaused(false);
                setExecutionControl({state:value.status});executionPhase.current='IDLE';
                if (automatic && terminalControl(value.status)) {
                    setControlTarget(''); queuedControl.current = null;
                }
                if (automatic && value.status === 'CANCELLED') clearGuidedConfirmation();
            } catch (error) {
                if (!current()) return;
                const notice = acmgRequestFeedback(error, 'submit', observedProgress);
                if (notice.reconfirmBackground || (automatic && notice.cancelled)) clearGuidedConfirmation();
                const acknowledged = observedProgress?.runId;
                executionPhase.current = 'IDLE';
                if (acknowledged) {
                    setPendingSubmission(null);
                    setRunId(acknowledged);
                    if (terminalControl(observedProgress.status)) {
                        runToRead = acknowledged;
                        displayRun(null);
                        setControlTarget(''); queuedControl.current = null;
                        setExecutionControl({state: observedProgress.status});
                        setFeedback(observedProgress.phase === 'SAVED' ? {
                            severity: 'info', title: '本次结果已保存',
                            message: '提交响应中断，正在读取同一次运行。',
                        } : notice);
                    } else {
                        displayRun({analysisId,runId:acknowledged,status:'RUNNING',resultsAvailable:false});
                        setControlTarget(acknowledged);
                        setMonitorVersion(v => v + 1);
                        setFeedback({
                            severity: 'info', title: '已取得本次运行编号',
                            message: '提交响应中断，正在读取该次运行的状态。',
                        });
                    }
                } else {
                    setFeedback(notice);
                    queuedControl.current = null;
                    setControlTarget('');
                    setExecutionControl(notice.cancelled ? {state:'CANCELLED'} : null);
                    if (notice.submissionUncertain) {
                        setPendingSubmission({ startedAt });
                        // The history list cannot identify an unacknowledged submission.
                        displayRun(null);
                        setRunId('');
                    }
                }
            } finally {
                if (current()) setSubmissionOperation(null);
            }
            if (runToRead) {
                try {
                    const value = await readRun(runToRead);
                    if (value) {
                        setSubmittedDraft({ runId: value.runId, identity: submittedIdentity });
                        if (observedProgress.phase === 'SAVED') setFeedback({
                            severity: 'success', title: '已读取本次保存的结果',
                            message: '提交响应中断后，已从同一次运行恢复结果。',
                        });
                    }
                } catch (error) {
                    if (currentAnalysis()) setFeedback(previous => ({
                        ...acmgRequestFeedback(error),
                        title: previous?.title || '已取得本次运行编号',
                        message: '暂未读到本次运行详情。请在历史记录中点击“读取保存结果”。',
                    }));
                }
            }
            try {
                await refreshRuns();
            } catch (error) {
                if (currentAnalysis()) setFeedback(previous => previous || acmgRequestFeedback(error));
            }
        });
    // A late control read may arrive after history already reported completion.
    // Reconcile the same saved run again for display/actions; keep new local preparation independent.
    const visibleControl = executionControl?.analysisId === run?.analysisId && executionControl?.runId === run?.runId && run?.runId
        ? controlAfterHistory(executionControl,run) : executionControl;
    const controlAction=async action=>{
        const commandEpoch=analysisEpoch.current;
        const currentAnalysis=()=>alive.current && activeAnalysis.current===analysisId && analysisEpoch.current===commandEpoch;
        const current=()=>currentAnalysis() && activeControlTarget.current===controlTarget;
        setControlBusy(true);
        try {
            const target={analysisId,runId:controlTarget,
                localPreparation:!controlTarget && ['PREPARATION','EVALUATION'].includes(executionPhase.current),
                allowAcknowledge:run?.status!=='CANCELLED'};
            const offered=controlActions(visibleControl,target).actions.find(item=>item.controlAction===action);
            if (!offered || !controlActionAvailable(visibleControl,action,target)) throw new Error('当前运行未提供此控制操作，请先核对最新状态。');
            const confirmed=['STOP','ACKNOWLEDGE_INTERRUPTED'].includes(action) || offered.requiresConfirmation;
            if (confirmed && !confirmRunControl(action)) return;
            if (controlTarget) {
                const value=await commandWithReconciliation(action,
                    () => commandAcmgControl(analysisId,controlTarget,action),
                    () => fetchAcmgControl(analysisId,controlTarget));
                if (!current()) return;
                setExecutionControl(value);
                setFeedback(previous => previous?.controlNotice ? null : previous);
                if (terminalControl(value.state)) {await readRun(controlTarget);await refreshRuns();}
                if (current()) setMonitorVersion(v=>v+1);
            } else if (executionPhase.current==='PREPARATION') {
                preparationControl.current.command(action);
                const task=materialTask.current;
                if(action==='STOP' && task && ['QUEUED','RUNNING'].includes(task.status))
                    await axios.post(`${task.base}/tasks/${task.taskId}/cancel`,null,{timeout:30000});
            } else if(executionPhase.current==='EVALUATION') {
                queuedControl.current={action,confirmed};setExecutionControl({state:action==='STOP'?'STOP_REQUESTED':action==='RESUME'?'RUNNING':'PAUSE_REQUESTED'});
            }
        } catch(error) {if (current()) setFeedback({controlNotice:true,severity:'warning',title:'控制请求未确认',message:error.response?.data?.message||error.message});}
        finally {if (currentAnalysis()) setControlBusy(false);}
    };
    const controlPanel=visibleControl && <><Paper variant="outlined" sx={{p:2,mb:2}}>
        <Typography fontWeight={700}>{controlLabels[visibleControl.state]||acmgRunLabel(visibleControl.state)}</Typography>
        <AcmgRunControlActions control={visibleControl} analysisId={analysisId}
            runId={controlTarget || (visibleControl.runId===run?.runId ? visibleControl.runId : '')} busy={controlBusy}
            localPreparation={!controlTarget && ['PREPARATION','EVALUATION'].includes(executionPhase.current)}
            allowAcknowledge={run?.status!=='CANCELLED'} onAction={controlAction} />
    </Paper>
        {['CANCELLED','INTERRUPTED','FAILED'].includes(run?.status) && <Stack spacing={0.5} sx={{mb:2}}>
            <Button disabled={busy||controlBusy||!options} variant="outlined" onClick={()=>{setControlledRetry(true);setBackgroundSnapshot(null);setConfirmed(false);}}>为新分析重新准备背景</Button>
        </Stack>}
    </>;
    const returnToPreparation = () => {
        setRunView(previous => ({ ...previous, showResults: false }));
        setConfirmed(false);
        setExecutionControl(null);
        setAutomaticStage('');
        setFeedback(null);
    };
    const selectedRun = run && (
        <SavedResult
            run={run}
            criteria={criteria}
            trackingPaused={trackingPaused}
            showHeading={!showResults}
            sourceTaskContext={options?.candidates && run.analysisId === analysisId ? {
                analysisId, draftIdentity, candidates: options.candidates,
                prepareDeclaration: () => buildSimplifiedCaseDeclaration(options, analysisId, inputs, undefined, dataClass, caseBinding),
            } : undefined}
            draftChanged={Boolean(
                submittedDraft && submittedDraft.runId === run.runId &&
                submittedDraft.identity !== draftIdentity
            )}
        />
    );
    const preparationPage = !showResults && (
        <Stack spacing={2} sx={{ p: { xs: 1, md: 2 } }}>
            <Paper variant="outlined" sx={{ p: 2 }}>
                <details ref={recordSection} className="cv-acmg-history">
                    <summary>历史记录与状态刷新</summary>
                    <div className="cv-acmg-history-controls">
                        <TextField
                            select
                            fullWidth
                            label="已保存的运行记录"
                            disabled={busy}
                            value={runs.some((r) => r.runId === runId) ? runId : ''}
                            onChange={(e) =>
                                act(async () => {
                                    await readRun(e.target.value);
                                    if (alive.current && activeAnalysis.current === analysisId)
                                        setPendingSubmission(null);
                                })
                            }
                            SelectProps={{
                                renderValue: (value) => <SavedRunLabel run={runs.find((r) => r.runId === value)} />,
                                MenuProps: { PaperProps: { sx: { maxWidth: 'calc(100vw - 32px)' } } },
                            }}
                        >
                            {runs.map((r) => (
                                <MenuItem key={r.runId} value={r.runId} sx={{ whiteSpace: 'normal', overflowWrap: 'anywhere' }}>
                                    <SavedRunLabel run={r} />
                                </MenuItem>
                            ))}
                        </TextField>
                        <Button
                            variant="outlined"
                            disabled={busy}
                            onClick={() =>
                                act(async () => {
                                    const values = await refreshRuns();
                                    if (!pendingSubmission) await readRun(runId || values[0]?.runId);
                                })
                            }
                        >
                            {pendingSubmission ? '刷新运行记录' : '读取保存结果'}
                        </Button>
                    </div>
                </details>
            </Paper>
            {evidenceSelectorCount !== null && <AcmgEvidenceSelectionDialog open available={criteria}
                variantCount={options?.selectedCount || 0}
                backgroundCount={evidenceSelectorCount}
                onClose={()=>setEvidenceSelectorCount(null)} onStart={selected=>{
                    setEvidenceSelectorCount(null); submit(false,false,true,selected);
                }} /> }
            {workflow === 'CURRENT' && <AcmgGuidedPreparation topN={topN} maximumN={maximumN}
                onCount={value => {setTopN(value);clearGuidedConfirmation();setControlledRetry(false);}}
                snapshot={backgroundSnapshot} options={options} inputs={inputs} onInput={updateInput}
                onPrepare={prepareBackground} onStart={openEvidenceSelector}
                onBack={()=>{clearGuidedConfirmation();setAutomaticStage('');}}
                confirmed={confirmed} onConfirm={setConfirmed} disabled={inputLocked} busy={busy || activeControl(executionControl?.state)}
                entryLoading={entryLoading} foregroundRead={foregroundRead}
                controlPanel={controlPanel} paused={executionControl?.state==='PAUSED'}
                timing={measuredProgress?.analysisId===analysisId ? measuredProgress.timing : run?.progress?.timing}
                feedback={feedback} stage={controlLabels[executionControl?.state] && executionControl?.state!=='RUNNING' ? controlLabels[executionControl.state] : automaticStage || (controlTarget && activeControl(executionControl?.state) ? liveProgressText(run?.progress, options?.candidates) : '')}
                notes={automaticNotes} dataClass={dataClass} onDataClass={value=>{setDataClass(value);setConfirmed(false);}}
                pending={Boolean(pendingSubmission)} />}
            {workflow !== 'CURRENT' && <>
            <details><summary style={{ cursor: 'pointer', padding: '12px' }}>可选背景、资料任务及高级设置</summary>
            {workflows.length > 1 && <TextField select label="证据分析入口" value={workflow} disabled={inputLocked}
                onChange={(event) => {
                    generation.current += 1; readVersion.current += 1;
                    setConfirmed(false); setOptions(null); setInputs({}); setSubmittedDraft(null);
                    setWorkflow(event.target.value);
                }}>
                {workflows.map((item) => <MenuItem key={item.id} value={item.id}>{item.label}</MenuItem>)}
            </TextField>}
            {localBatchClaim && criteria.includes('ACMG_AMP_2015:PM1') && <Alert severity="info">PM1核对精确蛋白位置与发布规范中的热点或关键区域；PP2分别核对错义机制与低良性错义背景；BP1核对截短主导适用和本次变异剪接限制。材料未覆盖会给出具体原因，未命中不自动等于良性或不满足。{options.executionProfile?.moduleBindings?.pp2?.runtimeBinding?.pp2GermlineDeclared === true ? '本实例PP2限明确声明的胚系科研范围，确认前请核对本次用途。' : '本实例未声明PP2胚系范围，PP2保留未评价。'}不是临床批准，不将不同规则自动相加为最终分类。</Alert>}
            {localBatchClaim && criteria.includes('ACMG_AMP_2015:PM4') && <Alert severity="info">PM4核对简单框内插入/缺失、精确蛋白和完整等价影响范围。{options.executionProfile?.moduleBindings?.pm4?.runtimeBinding?.pm4PolicyVersion === '1.2.0' && '另支持参考序列推导的终止丢失SNV延长。'}框内分支来源明确的重复区可判不满足；缺少非重复依据保留未评价。PM4不满足不代表BP3成立。</Alert>}
            {localBatchClaim && criteria.includes('ACMG_AMP_2015:PVS1') && <Alert severity="info">PVS1需要适用的LoF机制、转录本与区域知识，核对实际序列、提前终止、NMD及有依据的逃逸/剪接后果。BP3需要独立受审的重复区与无已知功能依据，注释未命中不能代替。各条证据独立记录，同一效应不自动重复计权或合并为最终分类。</Alert>}
            {localBatchClaim && <Alert severity="info">本次使用本地预测与参考材料：错义变异按事前配置使用 REVEL 或 AlphaMissense，并检查所需剪接机制；剪接相关变异检查 RAW SpliceAI。原生剪接最多处理 50 个去重的变异与转录本组合，每批最多 10 个，共用总预算；超出或材料不足会给出具体原因。PS1/PM5 仍需合格参考材料，未评价不等于不满足。修改本次选择需要重新确认，不会修改历史结果。</Alert>}
            <Ba1DataScopeNotice scope={options?.executionProfile?.ba1RuntimeBinding?.dataScope} bs1Scope={options?.executionProfile?.bs1RuntimeBinding?.dataScope || options?.executionProfile?.populationDataScope} pm2Enabled={unifiedLocalScope(options)} />
            <Typography variant="h5">{!options ? '普通证据评价：等待读取范围' : localBatchClaim ? `${localBatchCriteriaText(options)}：${unifiedLocalScope(options) ? '统一证据评价' : '本地证据评价'}` : pp4Claim ? 'PP4 单先证者：限定表型特异性证据评价' : bs4Claim ? 'BS4 单核家系：限定不共分离证据评价' : pp1Claim ? 'PP1 单核家系：限定共分离证据评价' : bp2Claim ? 'BP2 单家系：双变异良性支持证据评价' : pm3Claim ? 'PM3 单家系：双变异反式支持证据评价' : pm6Claim ? 'PM6 单家系：限定 de novo 证据评价' : ps2Claim ? 'PS2 单家系：限定 de novo 证据评价' : bp1Claim ? 'BP1 错义变异：截短主导机制与变异适用评价' : pp2Claim ? 'PP2 错义变异：受审机制与变异谱评价' : bp3Claim ? 'BP3 简单框内 indel：重复区受审适用评价' : pvs1Claim ? 'PVS1 无义及移码：NMD 与逃逸范围评价' : pm1Claim ? 'PM1 错义变异：受审区域评价' : pm5Claim ? 'PM5 错义变异：同残基不同蛋白替换比较' : ps1Claim ? 'PS1 错义变异：限定蛋白替换比较' : pm4Claim ? 'PM4 简单框内 indel：限定蛋白长度改变评价' : revelEnabled ? '限定 REVEL PP3：来源准备与显式采用' : predictionEnabled ? 'PP3 / BP4 开发验证（未正式启用）' : bs1Enabled ? 'BA1 / PM2 Supporting / BS1 证据评价' : 'BA1 / PM2 Supporting 证据评价'}</Typography>
            {bp1Issue && <Alert severity="warning">{bp1Issue}</Alert>}
            {pp2Issue && <Alert severity="warning">{pp2Issue}</Alert>}
            {bp3Issue && <Alert severity="warning">{bp3Issue}</Alert>}
            {pvs1Issue && <Alert severity="warning">{pvs1Issue}</Alert>}
            {pvs1Claim && pvs1SavedConflict && <Alert severity="warning">{pvs1NmdSavedIssue(run)} 请核查原始记录，读取有效历史后重新确认。</Alert>}
            {pm1Issue && <Alert severity="warning">{pm1Issue}</Alert>}
            {pm4Issue && <Alert severity="warning">{pm4Issue}</Alert>}
            {ps1Issue && <Alert severity="warning">{ps1Issue}</Alert>}
            {pm5Issue && <Alert severity="warning">{pm5Issue}</Alert>}
            {localBatchClaim && criteria.includes('ACMG_AMP_2015:BP7') && <Alert severity="info">BP7 复用同源 RAW 剪接预测，并核对同义或内含子位置、实验冲突及本地功能审核。最高 Supporting；不适用、缺资料和需复核分别说明。未收录实验不代表实验正常，BP4 与 BP7 不自动累加为最终分类。</Alert>}
            {localBatchIssue && <Alert severity="warning">{localBatchIssue}</Alert>}
            {localBatchClaim && workflow === 'CURRENT' && <PublicLiteraturePanel analysisId={analysisId} topN={topN} />}
            {localBatchClaim && workflow === 'CURRENT' && <Pm3LiteraturePanel analysisId={analysisId} topN={topN} />}
            {localBatchClaim && workflow === 'CURRENT' && criteria.includes('ACMG_AMP_2015:PP1') && !localBatchIssue &&
                <LiteratureContextSelection analysisId={analysisId} topN={topN} candidates={options.candidates}
                    inputs={inputs} onChange={updateInput} disabled={inputLocked} />}
            {localBatchClaim && workflow === 'CURRENT' && criteria.includes('ACMG_AMP_2015:PP1') && !localBatchIssue &&
                <Pp1LiteraturePanel analysisId={analysisId} draftIdentity={draftIdentity} candidates={options.candidates}
                    prepareDeclaration={() => buildSimplifiedCaseDeclaration(options, analysisId, inputs, undefined, dataClass, caseBinding)} />}
            {revelEnabled && <>
                <FormControlLabel control={<Checkbox checked={adoptionConfirmed} disabled={inputLocked || !revelSourceEligible(options, run, analysisId)}
                    onChange={(event) => setAdoptionBinding(event.target.checked ? currentAdoptionBinding : null)} />}
                    label="我确认提交当前保存来源的 PP3 采用请求；服务端批准、背景及同效应条件未通过时仍不应用。" />
                <Button variant="outlined" disabled={inputLocked || !adoptionConfirmed || !revelSourceEligible(options, run, analysisId)} onClick={() => submit(false, true)}>
                    采用当前已保存 REVEL 来源
                </Button>
            </>}
            <Typography variant="body2">
                当前启用：{criteria.map((id) => id.split(':').pop()).join(' / ') || '尚未加载'} ·{' '}
                {options?.executionProfile?.profileId || '等待读取配置'}
            </Typography>
            {localBatchClaim && <Alert severity="info">当前为 {localBatchCriteriaText(options)} {unifiedLocalScope(options) ? '统一入口，一次确认、一次提交、一起保存。BA1 可独立核验；BS1 仍需适用疾病知识，缺项逐条说明。' : '本地证据模式。'}确认仅选择本次背景及已有注释；来源、校准、参考审核和实际应用由各模块分别核验，局部缺项不阻断其他证据。</Alert>}
            <Alert severity="info">
                {singleFamilyClaim ? '使用固定单家系病例材料，确认仅选择本次输入，不代替亲子鉴定或科学审核。准备完成尚未进行科学评价；未知结论交核心核验，不自动计分或分类。' : bp1Claim ? '确认疾病、遗传方式和精确版本化转录本后，评价截短主导致病机制、当前变异例外与剪接适用三项材料。序列与精确适用由服务端核验；截短主导不等于显性遗传，PP2 阴性不能代替 BP1。背景确认不是材料的科学审核，不生成最终分类。' : pp2Claim ? '确认疾病、遗传方式和精确版本化转录本后，评价受审错义致病机制与低良性错义背景。序列和两条件的精确适用由服务端核验；错义标签、预测分数、PM1 命中或零条良性记录不能代替两条件，背景确认不是科学审核。' : bp3Claim ? '确认疾病、遗传方式及精确版本化转录本后，评价简单框内插入或缺失。序列、完整等价编辑包络及重复区功能适用声明由服务端核验；背景确认不是科学审核，不生成最终分类。' : pvs1Claim ? '确认疾病、遗传方式和精确版本化转录本后，核验无义或简单移码的 NMD 与已核定逃逸分支。序列、LoF 机制及外显子范围由服务端核验；预测不是实验，背景确认不是科学批准，不生成最终分类。' : pm1Claim ? '确认疾病关系、遗传方式及精确版本化转录本后，执行受审区域评价。模型、区域资格及分布依据由服务端核验；普通 domain 不自动满足 PM1，背景确认不是科学批准，不生成最终分类。'
                    : pm5Claim ? '确认疾病关系、遗传方式和精确版本化转录本后，执行 PM5 同残基不同蛋白替换比较。参考独立性、矩阵或审核关系、双方剪接及政策由后端核验；转录本确认不代表参考已获科学批准，不生成最终分类。'
                    : ps1Claim ? '确认疾病关系、遗传方式和精确版本化转录本后，执行 PS1 蛋白替换比较。参考资格、独立性、双方剪接及政策由后端核验；转录本确认不代表参考已获科学批准，不生成最终分类。'
                    : pm4Claim ? '确认疾病关系和精确版本化转录本后，执行限定蛋白长度改变评价。模型、区域和政策由服务端核验，缺记录不代表非重复区；科学采用及最终分类未开放。'
                    : calibrationOnly ? '本实例仅允许对获准的已保存分数作限定校准，不重新取证或应用 PP3/BP4。'
                    : associationOnly ? '本实例仅允许关联已保存观测，不重新查分。关联选择独立于疾病背景；应用仍缺背景及校准，不生成临床结论。'
                    : '使用正式候选，确认声明后执行并保存。各规则分别核验适用规范、来源及证据；无记录不等于频率为零。本页不生成最终五级分类。'}
            </Alert>
            {busy && <LinearProgress />}
            {submitting && (
                <Alert severity="info">
                    {singleFamilyClaim ? '正在读取单家系核验结果并保存，请勿重复提交。' : bp1Claim ? '正在读取 BP1 核验结果并保存评价，请勿重复提交。' : pp2Claim ? '正在读取 PP2 核验结果并保存评价，请勿重复提交。' : bp3Claim ? '正在读取 BP3 核验结果并保存评价，请勿重复提交。' : pvs1Claim ? '正在核验 PVS1 序列、NMD 与逃逸适用范围并保存评价，请勿重复提交。' : pm1Claim ? '正在核验 PM1 位置与受审区域并保存评价。请勿重复提交；结果不确定时读取保存状态。'
                        : pm5Claim ? '正在核验 PM5 参考材料、比较同残基不同替换并保存评价。请勿重复提交；结果不确定时读取保存状态。'
                        : ps1Claim ? '正在核验 PS1 参考材料、比较蛋白替换并保存评价。请勿重复提交；结果不确定时读取保存状态。'
                        : pm4Claim ? '正在核验限定 PM4 输入、重建蛋白长度改变并保存评价。请勿重复提交；结果不确定时读取保存状态。'
                        : submissionOperation === 'ADOPT_SAVED_REVEL_PP3' ? '正在核验已保存来源和当前批准并保存采用结果，不进行科学查询。请勿重复提交。'
                        : calibrationOnly ? '正在核验保存来源、校准并保存新结果，不进行科学查询。请勿重复提交。'
                        : associationOnly ? '正在核验引用并保存关联结果，不进行科学查询。请勿重复提交。' : '正在准备和评价证据，资源查询可能需要数分钟，请勿重复提交。'}
                </Alert>
            )}
            {feedback && !pendingSubmission && (
                <Alert severity={feedback.severity}>
                    <Typography fontWeight={600}>{feedback.title}</Typography>
                    {feedback.message}
                </Alert>
            )}
            {pendingSubmission && (
                <Alert
                    severity="warning"
                    action={
                        <Button
                            color="inherit"
                            onClick={() => {
                                if (recordSection.current) {
                                    recordSection.current.open = true;
                                    recordSection.current.scrollIntoView({ behavior: 'smooth' });
                                }
                            }}
                        >
                            查看记录
                        </Button>
                    }
                >
                    {pendingSubmission.startedAt}{' '}
                    的提交状态待确认。尚未收到响应不代表后台失败；请核对提交时间选择运行记录，不要重复提交。
                </Alert>
            )}
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
                {!pp4Claim && !bs4Claim && !pp1Claim && !bp2Claim && <TextField
                    label="正式 Top-N（1–100）"
                    size="small"
                    disabled={inputLocked || singleFamilyClaim}
                    value={topN}
                    onChange={(e) => {
                        setTopN(e.target.value);
                        setConfirmed(false);
                        ++generation.current;
                    }}
                />}
                <Button
                    disabled={inputLocked || !/^[1-9][0-9]{0,2}$/.test(topN) || Number(topN) > 100}
                    onClick={() => act(() => loadOptions(Number(topN)))}
                >
                    读取正式候选
                </Button>
                {!singleFamilyClaim && !associationOnly && !calibrationOnly && <TextField
                    select
                    size="small"
                    label="输入数据类别"
                    disabled={inputLocked}
                    value={dataClass}
                    onChange={(e) => {
                        setDataClass(e.target.value);
                        setConfirmed(false);
                    }}
                    helperText="仅明确的公开/合成测试才选择 SYNTHETIC；系统不根据基因或文件名推断。"
                >
                    <MenuItem value="PRIVACY_BOUNDED_RUNTIME">普通上传任务</MenuItem>
                    <MenuItem value="SYNTHETIC">公开合成测试数据（SYNTHETIC）</MenuItem>
                </TextField>}
            </Stack>
            {singleFamilyClaim && !pp4Issue && !pp4SavedConflict && !bs4Issue && !bs4SavedConflict && !pp1Issue && !pp1SavedConflict && !bp2Issue && !ps2Issue && !pm6Issue && !pm3Issue && <>
                {pp4Claim ? <Pp4PhenotypeSummary preparation={options.pp4Preparation} /> : bs4Claim ? <Bs4FamilySummary preparation={options.bs4Preparation} /> : pp1Claim ? <Pp1FamilySummary preparation={options.pp1Preparation} /> : bp2Claim ? <Bp2Background options={options} /> : pm3Claim ? <Pm3Background options={options} /> : pm6Claim ? <Pm6Background options={options} /> : <Ps2Background options={options} />}
                <FormControlLabel control={<Checkbox checked={confirmed} disabled={inputLocked}
                    onChange={(e) => setConfirmed(e.target.checked)} />}
                    label="使用以上病例与家系材料进行本次限定评价（不代替亲子鉴定或科学审核）" />
                <Stack direction="row" spacing={2}>
                    <Button variant="contained" disabled={inputLocked || !confirmed} onClick={() => submit()}>确认后执行并保存</Button>
                    <Button disabled={inputLocked || !confirmed || !(pp4Claim ? pp4RetryEligible(run, analysisId) : bs4Claim ? bs4RetryEligible(run, analysisId) : pp1Claim ? pp1RetryEligible(run, analysisId) : bp2Claim ? bp2RetryEligible(run, analysisId) : pm3Claim ? pm3RetryEligible(run, analysisId) : pm6Claim ? pm6RetryEligible(run, analysisId) : ps2RetryEligible(run, analysisId))} onClick={() => submit(true)}>修正后显式重试此运行</Button>
                </Stack>
            </>}
            {options && !singleFamilyClaim && !(pvs1Claim && pvs1SavedConflict) && (
                <>
                    <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>
                        分析：{analysisId} · 样本：{options.sampleId || '缺失'} · 输入 / 分析
                        assembly：{options.inputAssembly || '未知'} /{' '}
                        {options.analysisAssembly || '未记录'} · 已读取 Top-{options.requestedN} /{' '}
                        {options.selectedCount} 条
                    </Typography>
                    <Typography variant="body2">
                        来源：
                        {calibrationOnly ? '固定保存来源，提交后核验 DB 与归档' : options.executionSourceRecovered ? '已恢复，执行时继续核验' : '不可恢复'} ·
                        配置：{options.runtimeConfigured ? '已加载' : '不可用'}
                    </Typography>
                    <Reasons blockers={options.blockers} />
                    {calibrationOnly ? <>
                        <Typography variant="h6">校准已保存分数</Typography>
                        <Typography variant="body2">仅获准的两条合成公开控制；分数来自真实本地预计算资源。
                            复用保存的关联和固定归档，进行限定蛋白机制校准。PP3/BP4 尚未应用，不是临床分类。</Typography>
                        <Typography variant="body2">直接来源：{options.calibrationReference?.sourceRunId || '本分析不在授权范围'}
                            {' · '}当前选择：{run?.runId || '请从下方运行列表读取已保存关联记录'}</Typography>
                        <FormControlLabel control={<Checkbox checked={confirmed} disabled={inputLocked || !calibrationEligible}
                            onChange={(e) => setConfirmed(e.target.checked)} />}
                            label="确认仅校准这两条已保存关联分数；不重新取证、不改变选择、不应用 PP3/BP4。" />
                        <Button variant="contained" onClick={() => submit()}
                            disabled={inputLocked || !calibrationEligible || !confirmed || String(options.requestedN) !== topN}>
                            校准已保存分数并保存新运行
                        </Button>
                    </> : associationOnly ? <>
                        <Typography variant="h6">关联已保存观测：待确认选择</Typography>
                        <Typography variant="body2">
                            请在下方运行记录中读取原始观测父 run，再从已有 VEP 选项明确选择转录本。
                            不按分数或数组顺序自动选择；改变草稿不会改写下方保存结果。
                        </Typography>
                        <Typography variant="body2">
                            父运行：{parentEligible ? run.runId : '尚未选择可用的原始观测记录'} · 样本性质：{parentEligible ? run.dataClass : '待选择'}
                        </Typography>
                        {parentEligible && options.candidates.filter((c) => run.candidates?.some((p) => p.candidateId === c.candidateId &&
                            p.attempts?.some((a) => a.prediction?.observation?.status === 'HIT'))).map((c) => (
                                <TextField key={c.candidateId} select fullWidth size="small"
                                    label={`${c.candidateId} · 关联转录本`} disabled={inputLocked}
                                    value={associationSelections[c.candidateId] || ''}
                                    onChange={(e) => { setAssociationSelections((previous) => ({ ...previous, [c.candidateId]: e.target.value })); setConfirmed(false); }}
                                    helperText={c.associationOptions?.length ? '来自已保存 VEP；选择只用于身份关联，不声明疾病适用性。' : '当前保存注释中没有通过身份和来源核验的独立关联选项。'}>
                                    <MenuItem value="">不选择</MenuItem>
                                    {(c.associationOptions || []).map((a) => <MenuItem key={a.annotationId} value={a.annotationId}>
                                        {a.versionedTranscriptId} · {a.annotationSource} {a.annotationSourceVersion} · 注释 #{a.annotationIndex}
                                    </MenuItem>)}
                                </TextField>
                            ))}
                        <FormControlLabel control={<Checkbox checked={confirmed} disabled={inputLocked || !parentEligible}
                            onChange={(e) => setConfirmed(e.target.checked)} />}
                            label="我明确选择以上已有 VEP 注释，与本分析父 run 的原始观测关联；不授权新查询、校准或临床应用。" />
                        <Button variant="contained" onClick={() => submit()}
                            disabled={inputLocked || !parentEligible || !confirmed || !Object.values(associationSelections).some(Boolean) ||
                                Object.values(associationSelections).filter(Boolean).length > 2 || String(options.requestedN) !== topN}>
                            关联已保存观测并保存新运行
                        </Button>
                    </> : <>
                    <Typography variant="h6">待确认声明</Typography>
                    <Typography variant="body2">
                        推荐只提供可选背景，尚不是用户确认。目录关系不是患者诊断，
                        {deNovoLocalScope(options) ? '请按当前资料选择患病状态；不知道时保留未知。' : '患病状态保持 UNKNOWN。'}
                        {localBatchClaim ? '缺项候选仍按正式顺序保存，各条证据保留独立原因，不阻断其他规则。' : ps1Claim || pm5Claim ? '缺参考或剪接材料的候选由服务端独立保留未评价原因，不影响同批其他候选。' : bp1Claim || pp2Claim || bp3Claim || pvs1Claim || pm1Claim || pm4Claim ? '缺模型或适用材料的候选由服务端独立保留拒绝原因，不影响同批其他候选。' : '缺项候选仍按正式顺序保存，BA1 可以独立尝试。'}
                    </Typography>
                    {candidateStates.map(({ candidate: c, ready, ba1Ready, bs1Ready, relationship }) => {
                        const input = inputs[c.candidateId] || {};
                        const relationships = relationshipOptions(c);
                        const recommended = contextTranscriptRecommendation(c, relationship);
                        const eligibleTxs =
                            c.recommendation?.annotations?.filter((t) =>
                                annotationSelectable(t, relationship),
                            ) || [];
                        const preparation = populationPreparation(c, input, confirmed, options);
                        return (
                            <Accordion
                                key={`${c.candidateId}-${c.recommendation?.recommendationId}`}
                                variant="outlined"
                            >
                                <AccordionSummary>
                                    <Stack spacing={1} sx={{ width: '100%' }}>
                                        <Typography fontWeight={600}>
                                            #{c.rank} · {c.geneSymbol || '基因缺失'} ·{' '}
                                            {alleleLabel(c.allele)}
                                        </Typography>
                                        <Typography variant="caption">{c.candidateId}</Typography>
                                        {!predictionEnabled && !ruleOnlyClaim && <Typography variant="body2">
                                            BA1：{preparation.ba1}
                                        </Typography>}
                                        {!predictionEnabled && !ruleOnlyClaim && <Typography variant="body2">
                                            PM2：{preparation.pm2}
                                        </Typography>}
                                        {predictionEnabled && <Typography variant="body2">{localBatchClaim ? '确认已有背景和注释；各条证据所需材料分别核验。' : '仅确认已保存的背景与转录本；模型、校准与机制分别核验。'}</Typography>}
                                        {pm4Claim && <Typography variant="body2">PM4：{ready ? confirmed ? '声明已确认，待限定评价' : '背景及转录本待整体确认' : '请选择疾病关系和精确版本化转录本'}；模型和区域材料仍由服务端核验。</Typography>}
                                        {bp1Claim && <Typography variant="body2">BP1：{ready ? confirmed ? '背景已确认，待三项材料适用评价' : '背景及转录本待整体确认' : '请选择疾病关系和精确版本化转录本'}；序列、截短机制、变异例外与剪接适用仍由服务端核验。</Typography>}
                                        {pp2Claim && <Typography variant="body2">PP2：{ready ? confirmed ? '背景已确认，待双条件适用评价' : '背景及转录本待整体确认' : '请选择疾病关系和精确版本化转录本'}；序列、机制与低良性背景材料仍由服务端核验。</Typography>}
                                        {bp3Claim && <Typography variant="body2">BP3：{ready ? confirmed ? '背景已确认，待重复区适用评价' : '背景及转录本待整体确认' : '请选择疾病关系和精确版本化转录本'}；重复与功能适用事实仍由服务端核验。</Typography>}
                                        {pvs1Claim && <Typography variant="body2">PVS1：{ready ? confirmed ? '背景已确认，待 PVS1 评价' : '背景及转录本待整体确认' : '请选择疾病关系和精确版本化转录本'}；LoF 与外显子科学依据仍由服务端核验。</Typography>}
                                        {pm1Claim && <Typography variant="body2">PM1：{ready ? confirmed ? '背景已确认，待区域评价' : '背景及转录本待整体确认' : '请选择疾病关系和精确版本化转录本'}；区域资格及分布仍由服务端核验。</Typography>}
                                        {ps1Claim && <Typography variant="body2">PS1：{ready ? confirmed ? '声明已确认，待蛋白替换比较' : '背景及转录本待整体确认' : '请选择疾病关系和精确版本化转录本'}；参考资格仍由后端核验。</Typography>}
                                        {pm5Claim && <Typography variant="body2">PM5：{ready ? confirmed ? '声明已确认，待同残基不同替换比较' : '背景及转录本待整体确认' : '请选择疾病关系和精确版本化转录本'}；参考独立性与比较依据仍由后端核验。</Typography>}
                                        {bs1Enabled && <Typography variant="body2">BS1：{preparation.bs1}</Typography>}
                                    </Stack>
                                </AccordionSummary>
                                <AccordionDetails>
                                    <Stack spacing={2}>
                                        <Reasons blockers={c.blockers} />
                                        {c.catalog?.reasonCode && (
                                            <Reasons blockers={[c.catalog.reasonCode]} />
                                        )}
                                        {c.recommendation?.candidateMoiReasonCode && (
                                            <Reasons
                                                blockers={[c.recommendation.candidateMoiReasonCode]}
                                            />
                                        )}
                                        {!ruleOnlyClaim && pm2ContextPreparationDetails(
                                            c.recommendation?.pm2ContextPreparation?.diagnostics,
                                        ).map(([label, text]) => (
                                            <Typography key={label} variant="body2">
                                                {label}：{text}
                                            </Typography>
                                        ))}
                                        <TextField
                                            select
                                            fullWidth
                                            label="适用的基因—疾病关系"
                                            disabled={inputLocked}
                                            value={input.stableRelationshipKey || ''}
                                            onChange={(e) => {
                                                const selected = relationships.find(
                                                    (r) =>
                                                        r.stableRelationshipKey ===
                                                        String(e.target.value),
                                                );
                                                const annotation =
                                                    c.recommendation?.annotations?.find(
                                                        (t) =>
                                                            t.annotationId === input.annotationId,
                                                    );
                                                updateInput(c.candidateId, {
                                                    literatureContext: null,
                                                    stableRelationshipKey: String(e.target.value),
                                                    ...(deNovoLocalScope(options) ? { affectedStatus: 'UNKNOWN' } : {}),
                                                    ...(!annotationSelectable(annotation, selected)
                                                        ? { annotationId: '' }
                                                        : {}),
                                                });
                                            }}
                                            helperText="请选择本次评价的真实适用假设；唯一推荐仍需整体确认。"
                                        >
                                            <MenuItem value="">请选择</MenuItem>
                                            {relationships.map((r) => (
                                                <MenuItem
                                                    value={r.stableRelationshipKey}
                                                    key={`${r.stableRelationshipKey}-${r.reportLocator}`}
                                                    disabled={!r.selectable}
                                                >
                                                    {r.diseaseLabel || r.mondoId} · {r.mondoId} ·{' '}
                                                    {r.moi} · {relationshipClassificationLabel(r)}{' '}
                                                    {r.reasonCode || ''}
                                                </MenuItem>
                                            ))}
                                        </TextField>
                                        {localBatchClaim && <Typography variant="body2">
                                            {relationship?.interpretationContext
                                                ? localInterpretationContextText(relationship.interpretationContext)
                                                : 'Exomiser 排序背景保留不变。请选择本次解释所用疾病背景；不同背景不会自动选中。'}
                                        </Typography>}
                                        {relationship && (
                                            <Typography
                                                variant="caption"
                                                sx={{ overflowWrap: 'anywhere' }}
                                            >
                                                关系来源：{relationship.hgncId} ·{' '}
                                                {relationship.reportLocator}
                                            </Typography>
                                        )}
                                        <Typography variant="body2">
                                            临床优先注释：
                                            {c.recommendation?.clinicalPreferredTranscript
                                                ?.versionedTranscriptId || '未能唯一确定'}{' '}
                                            · 上下文推荐：
                                            {recommended?.versionedTranscriptId || '无'}
                                        </Typography>
                                        <TextField
                                            select
                                            fullWidth
                                            label="本次确认的版本化转录本"
                                            disabled={inputLocked}
                                            value={
                                                eligibleTxs.some(
                                                    (t) => t.annotationId === input.annotationId,
                                                )
                                                    ? input.annotationId
                                                    : ''
                                            }
                                            onChange={(e) =>
                                                updateInput(c.candidateId, {
                                                    annotationId: String(e.target.value),
                                                })
                                            }
                                            helperText="确认上下文不代表证据成立；执行时仍核验转录本、来源与政策。"
                                        >
                                            <MenuItem value="">
                                                {!ruleOnlyClaim && (ba1Ready || bs1Ready)
                                                    ? bs1Enabled ? '确认人口规则疾病背景（转录本按各规则要求核验）' : '仅确认 BA1 疾病背景（不确认转录本）'
                                                    : '未选择转录本'}
                                            </MenuItem>
                                            {eligibleTxs.map((t) => (
                                                <MenuItem
                                                    value={t.annotationId}
                                                    key={t.annotationId}
                                                >
                                                    {t.versionedTranscriptId} ·{' '}
                                                    {t.hgvsp || t.hgvsc || t.consequence} ·{' '}
                                                    {t.annotationSource}@{t.annotationSourceVersion}
                                                    {t.maneSelect ? ' · MANE Select' : ''}
                                                </MenuItem>
                                            ))}
                                        </TextField>
                                        {!ruleOnlyClaim && (ba1Ready || bs1Ready) && !ready && (
                                            <Alert severity="info">
                                                {bs1Enabled ? '此选择确认疾病假设；BS1 按自身政策决定转录本要求，PM2 的缺项不会因此被补齐。' : '此选择只确认 BA1 疾病背景，不确认转录本，也不授权 PM2。专规仍按实际所需条件核验。'}
                                            </Alert>
                                        )}
                                        {deNovoLocalScope(options) && <>
                                            <TextField select fullWidth label="当前病例对所选疾病的患病状态" disabled={inputLocked}
                                                value={input.affectedStatus || 'UNKNOWN'} onChange={e => { updateInput(c.candidateId, { affectedStatus: e.target.value }); }}>
                                                <MenuItem value="UNKNOWN">未知／尚未确定</MenuItem>
                                                <MenuItem value="AFFECTED">已知患病</MenuItem>
                                                <MenuItem value="UNAFFECTED">已知未患病</MenuItem>
                                            </TextField>
                                            <Alert severity="info">PS2/PM6 不要求为家系证据强选转录本；其他证据仍按各自要求核验。患病状态不等于亲缘或父母检测确认，相关事实由原始材料独立核对。</Alert>
                                        </>}
                                        <Typography variant="caption">
                                            推荐身份：{c.recommendation?.policyId}@
                                            {c.recommendation?.policyVersion}
                                        </Typography>
                                    </Stack>
                                </AccordionDetails>
                            </Accordion>
                        );
                    })}
                    {deNovoLocalScope(options) && <Stack spacing={1}>
                        <TextField select label="当前家系材料" disabled={inputLocked} value={caseBinding ? 'BOUND' : 'UNBOUND'}
                            onChange={e => { setConfirmed(false); setCaseBinding(e.target.value === 'BOUND' ? {
                                analysisId, materialSha256: options.executionProfile.moduleBindings.deNovo.manifestSha256,
                                patientContextId: '', familyContextId: '',
                            } : null); }}>
                            <MenuItem value="UNBOUND">未绑定当前家系材料（仍可核对文献病例）</MenuItem>
                            <MenuItem value="BOUND">绑定已登记的当前家系材料</MenuItem>
                        </TextField>
                        {caseBinding && <>
                            <TextField label="报告中的病例隐私标识" value={caseBinding.patientContextId} disabled={inputLocked}
                                placeholder="patient-context-…" onChange={e => { setCaseBinding(old => ({...old, patientContextId:e.target.value})); setConfirmed(false); }} />
                            <TextField label="报告中的家系隐私标识" value={caseBinding.familyContextId} disabled={inputLocked}
                                placeholder="family-context-…" onChange={e => { setCaseBinding(old => ({...old, familyContextId:e.target.value})); setConfirmed(false); }} />
                            <Alert severity="info">请确认当前分析样本 {options.sampleId} 就是这份已登记报告的先证者，只填写报告中的隐私标识，不填写姓名。此操作只建立病例对应关系；目标检测、亲缘、疾病与表型仍由原件核验。文献病例不会因此变成本病例。</Alert>
                        </>}
                    </Stack>}
                    <Alert severity="info">
                        {localBatchClaim ? `已准备${criteria.includes('ACMG_AMP_2015:PP1') ? '可声明背景（文献背景不代表已确认转录本）' : deNovoLocalScope(options) ? '疾病背景（PS2/PM6 不强制转录本）' : '完整背景及转录本'} ${readyCount} / ${options.selectedCount} 条；确认后逐条执行并保存，未选背景或缺材料保留真实原因。` : ruleOnlyClaim ? `已准备 ${bp1Claim ? 'BP1' : pp2Claim ? 'PP2' : bp3Claim ? 'BP3' : pvs1Claim ? 'PVS1' : pm1Claim ? 'PM1' : pm5Claim ? 'PM5' : ps1Claim ? 'PS1' : 'PM4'} 疾病关系及转录本 ${readyCount} / ${options.selectedCount} 条。至少一条完整背景可提交，其余候选保留独立未评价原因。` : <>已准备可声明背景 {readyCount} / ${options.selectedCount} 条，其中{' '}
                        {ba1OnlyCount} 条未确认转录本的人口规则背景。
                        {catalogBa1 ? 'BA1 使用当前规范目录核验，缺项不回退通用规范。' : ''} PM2
                        缺背景或政策时保留具体未评价原因。</>}
                    </Alert>
                    <FormControlLabel
                        control={
                            <Checkbox
                                checked={confirmed}
                                disabled={inputLocked || !criteria.length || !options.selectedCount}
                                onChange={(e) => setConfirmed(e.target.checked)}
                            />
                        }
                        label={localBatchClaim ? `我确认本次数据类别、所选疾病关系${deNovoLocalScope(options) ? '、当前病例患病状态及可用注释' : '及已有转录本注释'}用于 ${localBatchCriteriaText(options)} 独立核验；${criteria.includes('ACMG_AMP_2015:PP2') && options.executionProfile?.moduleBindings?.pp2?.runtimeBinding?.pp2GermlineDeclared === true ? '本次符合PP2胚系科研用途；' : ''}缺项保持未评价，背景确认不代表资源、参考审核或科学采用，不生成最终分类。` : bp1Claim ? '我确认所选疾病、遗传方式及精确版本化转录本，用于本实例 BP1 截短机制与变异适用评价；背景确认不是三项材料的科学审核，不授权采用或最终分类。' : pp2Claim ? '我确认所选疾病、遗传方式及精确版本化转录本，用于本实例 PP2 错义机制与变异谱评价；背景确认不是两条件材料的科学审核，不授权采用或最终分类。' : bp3Claim ? '我确认所选疾病、遗传方式及精确版本化转录本，用于本实例 BP3 框内 indel 评价；背景确认不是重复区或功能适用的科学审核，不授权采用或最终分类。' : pvs1Claim ? '我确认所选疾病关系、遗传方式及精确版本化转录本，用于当前政策覆盖的 PVS1 NMD 或逃逸分支；背景确认不是科学批准，预测不是实验，不生成最终分类。' : pm1Claim ? '我确认所选疾病关系、遗传方式及精确版本化转录本，用于本实例 PM1 受审区域评价；模型、区域资格及分布由服务端核验，背景确认不是科学批准，不生成最终分类。' : pm5Claim ? '我确认所选疾病关系、遗传方式及精确版本化转录本，用于本实例 PM5 同残基不同蛋白替换比较；参考独立性与比较依据由后端核验，不代表科学采用，不生成最终分类。' : ps1Claim ? '我确认所选疾病关系、遗传方式及精确版本化转录本，用于本实例 PS1 蛋白替换比较；参考资格由后端核验，不代表科学采用，不生成最终分类。' : pm4Claim ? '我确认所选疾病关系及精确版本化转录本，用于本实例限定 PM4 蛋白长度改变评价；不代表科学采用，不生成最终分类。' : predictionEnabled ? '我确认所选背景和转录本用于本隔离实例的 PP3/BP4 开发验证；不授权其他规则或最终分类，未核验项保持未评价。' : bs1Enabled
                            ? '我确认以上数据类别及所选疾病假设用于本实例 BA1、PM2、BS1 的独立核验；不要求转录本的 BS1 政策可省略该项。各规则仍独立核验来源、政策和质量，不生成最终分类。'
                            : '我确认以上数据类别及所选评价假设。仅 BA1 背景不授权 PM2；完整背景按各规则原条件核验。未准备的候选保留真实原因，不生成最终分类。'}
                    />
                    <Stack direction="row" spacing={2}>
                        <Button
                            variant="contained"
                            disabled={
                                inputLocked ||
                                !confirmed ||
                                (ruleOnlyClaim && !readyCount) ||
                                !criteria.length ||
                                !options.selectedCount ||
                                String(options.requestedN) !== topN
                            }
                            onClick={() => submit()}
                        >
                            确认后执行并保存
                        </Button>
                        <Button
                            disabled={
                                inputLocked ||
                                !confirmed ||
                                (ruleOnlyClaim && !readyCount) ||
                                !criteria.length ||
                                !options.selectedCount ||
                                String(options.requestedN) !== topN ||
                                (Boolean(localBatchScopeClaim(run)) !== Boolean(localBatchClaim) && !localBatchRetryEligible(run, analysisId, options)) ||
                                revelEnabled || (localBatchClaim && !localBatchRetryEligible(run, analysisId, options)) || !['FAILED', 'COMPLETED_WITH_BLOCKERS'].includes(run?.status)
                            }
                            onClick={() => submit(true)}
                        >
                            修正后显式重试此运行
                        </Button>
                    </Stack>
                    <Typography variant="caption">
                        相同声明及执行版本返回已有运行；刷新只读取记录，不自动重新评价。
                        {revelEnabled && ' REVEL 已保存来源请使用上方显式采用入口；本入口不追加取分重试预算。'}
                    </Typography>
                    </>}
                </>
            )}
            </details></>}
            {run && !hasSavedEvidenceResult(run) && (
                <Paper variant="outlined" sx={{ p: 2 }}>{selectedRun}</Paper>
            )}
        </Stack>
    );
    return <>
        <Stack component="header" direction="row" justifyContent="space-between"
            alignItems="center" useFlexGap flexWrap="wrap" gap={1}
            sx={{ px: { xs: 2, md: 3 }, py: 1.5, borderBottom: '1px solid', borderColor: 'divider' }}>
            <Typography component="h2" variant="h6" id="analysis-lab-acmg-title"
                ref={workspaceHeading} tabIndex={-1} sx={{ outline: 'none' }}>
                {showResults ? 'ACMG 证据结果' : '当前任务 · ACMG 证据评价'}
            </Typography>
            <Stack direction="row" gap={1} useFlexGap flexWrap="wrap">
                {showResults && <Button variant="outlined" onClick={returnToPreparation}>返回分析准备</Button>}
                {!showResults && hasSavedEvidenceResult(run) && (
                    <Button variant="outlined" disabled={inputLocked} onClick={() => displayRun(run)}>查看保存结果</Button>
                )}
                {onReturnToCandidates && <Button onClick={onReturnToCandidates}>返回候选排名</Button>}
            </Stack>
        </Stack>
        <DialogContent ref={workspaceContent} sx={{ p: 0 }}>
            {showResults ? (
                <Stack component="section" aria-labelledby="analysis-lab-acmg-title" spacing={2}
                    sx={{ p: { xs: 1, md: 2 } }}>
                    {selectedRun}
                </Stack>
            ) : preparationPage}
        </DialogContent>
    </>;
}
