import React from 'react';
import { Alert, Box, Paper, Stack, Typography } from '@mui/material';
import { evaluationNeedDiagnostic, literatureDiagnosticReason as reasonText, literatureProcessingText, literatureStageText } from '../utils/acmgLiteratureDiagnostics';

export default function LiteratureStageDiagnostics({ value, explain, historical = false, compact = false }) {
    if (!value) return historical ? <Typography variant="body2" color="text.secondary">
        此保存结果没有记录分阶段处理资料；读取历史不会重新获取或分析。
    </Typography> : null;
    if (value.withheld) return <Alert severity="warning">
        本次处理记录受显示大小限制或尚未满足关联、字段核对条件，未展示阶段明细{value.reasonCode ? `（${value.reasonCode}）` : ''}；已有证据结论不变。
        {value.withheldStageTotal !== null && value.withheldStageTotal !== undefined && <Typography variant="body2">
            原记录共 {value.withheldStageTotal} 项阶段{value.withheldStageOmitted !== null ? `，未展示 ${value.withheldStageOmitted} 项` : ''}；
            {value.withheldStagePending !== null ? `待处理 ${value.withheldStagePending} 项` : '待处理数量未记录'}
            {value.withheldSourceRejected !== null ? `，来源检查未通过 ${value.withheldSourceRejected} 项` : ''}。完整原件仍保留。
        </Typography>}
    </Alert>;
    if (value.invalidBinding) return <Alert severity="warning">处理记录与这份资料的来源关联不完整或不一致，暂不展示阶段明细；现有证据结论不变。</Alert>;
    const attention = (value.stages || []).filter((stage) => stage.status !== 'SOURCE_CHECKED');
    const failed = new Set(['TEMPORARY_FAILURE', 'EXECUTION_FAILED', 'SOURCE_REJECTED', 'EXTRACTION_INCOMPLETE']);
    const shownStages = compact ? [...attention.filter((stage) => failed.has(stage.status)),
        ...attention.filter((stage) => !failed.has(stage.status))].slice(0, 3) : (value.stages || []).slice(0, 12);
    return <Paper variant="outlined" sx={{ p: 1.5, borderColor: 'info.light', bgcolor: 'rgba(33, 150, 243, 0.035)' }}>
        <Stack spacing={1}>
            <Typography variant="subtitle2">资料处理记录{value.stage ? ` · ${literatureStageText(value.stage)}` : ''}</Typography>
            {value.rows?.map((row) => <Box key={row.label}>
                <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.5 }}>{row.label}</Typography>
                <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', sm: 'repeat(3, minmax(0, 1fr))' }, gap: 1 }}>
                    {[['需要', row.required], ['已有', row.available], ['差异', row.difference]].map(([label, text]) => <Box key={label} sx={{ minWidth: 0 }}>
                        <Typography variant="caption" color="text.secondary">{label}</Typography>
                        <Typography variant="body2" sx={{ overflowWrap: 'anywhere' }}>{text}</Typography>
                    </Box>)}
                </Box>
            </Box>)}
            {value.stageGaps?.map((gap, index) => <Alert key={index} severity="info">
                系统环节尚待完成：{literatureStageText(gap.stage)}。{gap.required !== null && gap.available !== null
                    ? `需要至少 ${gap.required} 份复核记录，当前 ${gap.available} 份。` : ''}{reasonText(gap.reasonCode, explain)}
                <Typography variant="body2">这是系统处理进度，不能解释为患者或原文缺少相应事实。</Typography>
            </Alert>)}
            {value.omittedStageGaps > 0 && <Typography variant="body2">共 {value.stageGapTotal} 条差异记录，另有 {value.omittedStageGaps} 条因显示上限或类型待核而未展示；完整原记录保留，不据此判断环节已完成。</Typography>}
            {value.missing?.length > 0 && <Alert severity="warning">
                <Typography variant="subtitle2">尚待补齐的判定资料</Typography>
                {value.missingDetails?.length ? <EvaluationNeedDifferences normalized={value.missingDetails} explain={explain} heading={false} />
                    : value.missing.map((need, index) => <Typography key={index} variant="body2">
                    {reasonText(need.reasonCode, explain)}{need.knownFactCount !== null ? `；已有 ${need.knownFactCount} 项事实记录` : ''}
                    {need.sourceLocatorCount !== null ? `、${need.sourceLocatorCount} 处来源定位` : ''}。
                </Typography>)}
                {value.missingTotal > value.missing.length && <Typography variant="body2">原记录共 {value.missingTotal} 项，另有 {value.missingTotal - value.missing.length} 项受显示上限或记录待核影响未展示；完整原记录保留。</Typography>}
            </Alert>}
            {value.missingCountsUnknown && <Typography variant="body2">判定缺项的总数或省略数量尚未完整记录，不能将未展示内容当作零项。</Typography>}
            {value.missingInvalid > 0 && <Typography variant="body2">有 {value.missingInvalid} 条缺项元数据尚待核对，未据其推断原文是否提供资料。</Typography>}
            {Array.isArray(value.stages) && <>
                <Typography variant="body2">{value.stageTotal === null || value.stageTotal === undefined ? '阶段总数尚未完整记录'
                    : `资料块阶段记录：共 ${value.stageTotal} 项`}；提取和复核分别记录，不代表整篇阅读已完成。</Typography>
                {compact && (attention.length > 0 || value.pendingStages > 0 || value.sourceRejectedStages > 0) && <Alert severity="warning">
                    {value.pendingStages !== null && value.pendingStages !== undefined ? `原记录中的待处理阶段 ${value.pendingStages} 项。` : `当前可见的未完成或未通过记录 ${attention.length} 条。`}
                    {value.sourceRejectedStages > 0 ? `另记录来源检查未通过 ${value.sourceRejectedStages} 项。` : ''}
                </Alert>}
                {shownStages.map((stage, index) => <Typography variant="body2" key={index}>
                    资料块 {stage.chunkIndex + 1} · {literatureStageText(stage.stage)}：{literatureProcessingText(stage.status)}
                    {stage.reasonCode ? `；${reasonText(stage.reasonCode, explain)}` : ''}
                </Typography>)}
                {!compact && value.stages.length > 12 && <details><summary>查看其余 {value.stages.length - 12} 项阶段记录</summary>
                    {value.stages.slice(12).map((stage, index) => <Typography variant="body2" key={index}>
                        资料块 {stage.chunkIndex + 1} · {literatureStageText(stage.stage)}：{literatureProcessingText(stage.status)}
                        {stage.reasonCode ? `；${reasonText(stage.reasonCode, explain)}` : ''}
                    </Typography>)}
                </details>}
                {compact && value.stages.length > shownStages.length && <Typography variant="body2">完整逐块记录见下方资料进度与原件区。</Typography>}
            </>}
            {value.stageCountsUnknown && <Typography variant="body2">阶段总数或省略数量尚未完整记录；只展示已取得条目，不判断其余环节已经完成。</Typography>}
            {value.omittedStages > 0 && <Typography variant="body2">另有 {value.omittedStages} 项阶段记录未在面板展示，原始资料仍保留。</Typography>}
            {value.stoppedAfterFailure && <Alert severity="warning">失败后已停止新的模型工作；需先核对任务及调用记录，不能依据已有缓存自动重试。</Alert>}
            <Typography variant="caption" color="text.secondary">以上只说明资料处理情况；证据是否成立及采用强度以上方判定结果为准。</Typography>
        </Stack>
    </Paper>;
}

export function LiteratureAcquisitionDiagnostics({ value, explain }) {
    if (!value) return null;
    if (value.withheld) return <Alert severity="warning">本次获取进度记录的来源或字段尚待核对，未据其判断原件是否存在{value.reasonCode ? `（${value.reasonCode}）` : ''}。</Alert>;
    return <Alert severity="warning">
        <Typography variant="subtitle2">资料获取尚未完成（{value.total === null || value.total === undefined ? '总数未记录' : `${value.total} 项`}）</Typography>
        {value.items.map((item, index) => <Typography key={index} variant="body2">
            {item.pmcid || '尚未登记论文的任务范围'}：{reasonText(item.reasonCode, explain)}。
        </Typography>)}
        {value.omitted > 0 && <Typography variant="body2">另有 {value.omitted} 项未在此处展开；任务原记录仍保留。</Typography>}
        {value.items.length === 0 && <Typography variant="body2">获取记录尚待核对或受显示上限影响未展示，当前不能据此说明原文是否存在。</Typography>}
        {value.countsUnknown && <Typography variant="body2">获取记录的总数或省略数量尚未完整记录，不能将未展示内容当作零项。</Typography>}
        <Typography variant="body2">这表示传输未完成，不表示没有论文或原文没有所需资料；继续权限以当前任务状态为准。</Typography>
    </Alert>;
}

export function EvaluationNeedDifferences({ needs, normalized, explain, heading = true }) {
    if ((!Array.isArray(needs) || !needs.length) && (!Array.isArray(normalized) || !normalized.length)) return null;
    const views = Array.isArray(normalized) ? normalized.slice(0, 128) : needs.slice(0, 128).map(evaluationNeedDiagnostic).filter(Boolean);
    const expectations = { SAME_SOURCE_CLINICAL_OBSERVATION_STATUS_AND_ATTAINED_AGE: '同一次原始观察中相互关联的疾病状态和检查时年龄',
        'explicit adopted policy value': '明确采用并有来源的政策参数' };
    return <Stack spacing={1}>
        {heading && <Typography variant="subtitle2">判定还需要什么、实际已有信息与差异</Typography>}
        {views.map((view, index) => <Paper key={index} variant="outlined" sx={{ p: 1.5, borderColor: 'warning.light', bgcolor: 'rgba(255, 152, 0, 0.035)' }}>
            <Stack spacing={1}>
                {view.scopes.length > 0 && <Typography variant="subtitle2" sx={{ overflowWrap: 'anywhere' }}>{view.scopes.join(' · ')}</Typography>}
                {view.scopeUnrecorded && <Typography variant="caption">本条未提供可展示的具体对象范围；不另行推断其家系、成员或病例角色。</Typography>}
                <Box sx={{ display: 'grid', gridTemplateColumns: { xs: '1fr', md: 'repeat(3, minmax(0, 1fr))' }, gap: 1.5 }}>
                    <Box sx={{ minWidth: 0 }}>
                        <Typography variant="caption" color="text.secondary">需要的信息</Typography>
                        <Typography variant="body2">{view.required || reasonText(view.reasonCode, explain)}</Typography>
                        {view.expected && <Typography variant="body2">所需条件：{expectations[view.expected] || view.expected}</Typography>}
                    </Box>
                    <Box sx={{ minWidth: 0 }}>
                        <Typography variant="caption" color="text.secondary">实际已有记录</Typography>
                        {view.facts.length ? view.facts.map((fact, factIndex) => <Typography key={factIndex} variant="body2" sx={{ overflowWrap: 'anywhere' }}>{fact}</Typography>)
                            : <Typography variant="body2">本结果未记录可在此简表展示的具体已有事实；相关原句及完整记录请看出处。</Typography>}
                        {view.undisplayedFactRecords > 0 && <Typography variant="body2">另有 {view.undisplayedFactRecords} 条已有记录未在简表展示，不代表原文没有信息。</Typography>}
                    </Box>
                    <Box sx={{ minWidth: 0 }}>
                        <Typography variant="caption" color="text.secondary">当前差异</Typography>
                        <Typography variant="body2">{view.difference || ({ MAY_ADD_EVENT: '已有合格贡献保持；本项条件补齐后可能增加可计入的事件。',
                            MAY_ADD_INFORMATION: '本项仍可补充核查；是否阻断评分见本条判定结果。',
                            BLOCKS_FAMILY: '本家系的计分前提尚未完整核定。', BLOCKS_SCORING: '本条评分前提尚未完整核定，不能据此采用。' })[view.blockingEffect]
                            || '上述要求尚未由当前记录完整核定；需要继续核对具体条件及出处。'}</Typography>
                    </Box>
                </Box>
                <Typography variant="caption" color="text.secondary">“已有记录”包括尚待核实的陈述，不表示已经确认全部含义或可以计分。</Typography>
                {view.detailProjections?.map((projection, projectionIndex) => <Typography variant="caption" key={projectionIndex}>
                    {projection.label}：{projection.total === null ? '原记录总数未提供' : `原记录 ${projection.total} 项`}，
                    {projection.displayed === null ? '展示数量未提供' : `已提供 ${projection.displayed} 项`}；
                    {projection.withheld > 0 ? ` ${projection.withheld} 项因记录类型或公开字段限制未提供；` : ''}
                    {projection.omitted > 0 ? ` ${projection.omitted} 项因显示上限省略。` : ''}
                </Typography>)}
                {view.locators?.length > 0 && <details><summary>查看本条具体来源定位（{view.locators.length} 处）</summary>
                    {view.locators.map((locator, locatorIndex) => <Typography key={locatorIndex} variant="body2" sx={{ overflowWrap: 'anywhere' }}>
                        {locator.pmcid} · {locator.unitId || '已登记来源摘要，具体原文位置未记录'} · 来源摘要 {locator.sourceSha256}
                    </Typography>)}
                </details>}
            </Stack>
        </Paper>)}
        {Array.isArray(needs) && needs.length > views.length && <Typography variant="body2">另有 {needs.length - views.length} 项未在此简表展示；完整判定记录仍保留。</Typography>}
    </Stack>;
}
