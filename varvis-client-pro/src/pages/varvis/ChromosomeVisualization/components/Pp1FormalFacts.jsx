import React from 'react';
import { Alert, Box, Chip, Paper, Stack, Typography } from '@mui/material';
import { validatePp1FormalCriterion } from '../utils/acmgPp1Formal';
import { displayStrength } from '../utils/evidencePresentation';

const items = value => Array.isArray(value) ? value : [];
const show = value => value === undefined ? '未提供' : value === null ? '未核定' : typeof value === 'string' ? value : JSON.stringify(value, null, 2);
const fraction = value => value ? `${value.numerator}/${value.denominator}` : '尚未计算';
const adverse = value => /CONFLICT|FAILED|ERROR|MISMATCH|INVALID|REJECTED/.test(show(value));
const statusLabel = value => ({ AFFECTED: '患病', UNAFFECTED: '未患病', UNKNOWN: '未核定', CONFLICT: '冲突' })[value] || show(value);
const selectionLabel = family => family.alreadyAllocated ? '此前已分配' : family.selectedForContribution ? '本次选用' : '本次未选用';

function Value({ label, value }) {
    const text = show(value), limit = 6000;
    return <Box sx={{ minWidth: 0 }}><Typography variant="body2" component="div" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>
        <strong>{label}：</strong>{text.slice(0, limit)}
    </Typography>{text.length > limit && <Typography variant="caption" color="warning.main">此字段仅展示前 {limit} 字符；完整字段保留在原始结果。</Typography>}</Box>;
}
function Limited({ values, maximum = 24, children }) {
    const rows = items(values);
    return <>{rows.slice(0, maximum).map((row, index) => <React.Fragment key={index}>{children(row, index)}</React.Fragment>)}
        {rows.length > maximum && <Alert severity="info">此处展示 {maximum} / {rows.length} 项；其余完整字段保留在原始结果。</Alert>}</>;
}
function Sources({ values }) {
    const refs = [...new Map(items(values).filter(Boolean).map(ref => [JSON.stringify(ref), ref])).values()];
    if (!refs.length) return <Typography variant="caption">本项未附可展示的原文定位。</Typography>;
    return <details><summary>原句、位置及来源摘要（{refs.length} 处）</summary><Stack spacing={1} sx={{ mt: 1 }}>
        <Limited values={refs} maximum={12}>{ref => <Paper variant="outlined" sx={{ p: 1 }}>
            <Value label="原句" value={ref.quote} />
            <Value label="来源 / 单元" value={[ref.pmcid || ref.sourceId, ref.unitId || ref.locator].filter(Boolean).join(' · ')} />
            <Value label="字符范围" value={ref.start === undefined ? undefined : `${ref.start}–${ref.end ?? '未提供'}`} />
            <Value label="SHA-256" value={ref.sourceSha256 || ref.sha256} />
        </Paper>}</Limited>
    </Stack></details>;
}
function Need({ value, origin }) {
    const refs = [...items(value.sourceLocators), ...items(value.sourceRefs), ...items(value.knownFacts).flatMap(fact => items(fact.sourceRefs))];
    const severity = adverse(value.reasonCode) ? 'error' : value.blockingEffect === 'BLOCKS_SCORING' ? 'warning' : 'info';
    return <Alert severity={severity} sx={{ '& .MuiAlert-message': { width: '100%', minWidth: 0 } }}>
        <Stack spacing={0.75}>
            <Typography variant="subtitle2">{origin} · {value.blockingEffect === 'BLOCKS_SCORING' ? '阻止评分' : '仍需补充核查'}</Typography>
            <Value label="需要什么" value={value.requirement} />
            <Value label="已经有什么" value={value.knownFacts ?? value.known ?? '此缺项未单独列出已有事实；请结合下方原始核查。'} />
            <Value label="需要达到的条件" value={value.expected} />
            <Value label="目前情况与差异" value={value.actual ?? value.reasonCode} />
            <Value label="成员或范围" value={value.scope} />
            <Value label="后续核查路径" value={value.route} />
            <Value label="原因代码" value={value.reasonCode} />
            <Sources values={refs} />
        </Stack>
    </Alert>;
}
function Clinical({ value }) {
    return <Paper variant="outlined" sx={{ p: 1.5, borderColor: adverse(value.issues) ? 'error.main' : 'divider' }}>
        <Stack spacing={0.75}>
            <Typography variant="subtitle2">{value.memberLabel || value.memberKey} · {statusLabel(value.status)}</Typography>
            <Value label="同次观察 ID" value={value.sourceObservationId} />
            <Value label="状态对应的检查 / 随访年龄" value={value.attainedAge === null ? '未核定；是否要求年龄由本次模型决定' : value.attainedAge === undefined ? undefined : `${value.attainedAge} 年`} />
            <Value label="观察身份与原文位置" value={value.observationIdentity} />
            <Value label="状态事实 / 年龄事实" value={{ statusFactIds: value.statusFactIds, ageFactIds: value.ageFactIds }} />
            <Value label="核查与对应方式" value={{ assessmentIds: value.assessmentIds || (value.id ? [value.id] : []), bindingBasis: value.bindingBasis, issues: value.issues }} />
            <Sources values={value.sourceRefs} />
        </Stack>
    </Paper>;
}
function Section({ title, children }) {
    return <Box component="details" sx={{ border: '1px solid', borderColor: 'divider', borderRadius: 1, p: 1.5, minWidth: 0, '& > summary': { cursor: 'pointer', fontWeight: 600 } }}>
        <summary>{title}</summary><Stack spacing={1.25} sx={{ mt: 1.5 }}>{children}</Stack>
    </Box>;
}

/** Read-only saved formal facts. Complete criterion validation precedes scores. */
export default function Pp1FormalFacts({ value, criterion }) {
    const matches = criterion?.details?.pp1Facts === value || JSON.stringify(criterion?.details?.pp1Facts) === JSON.stringify(value);
    if (!matches || !validatePp1FormalCriterion(criterion)) return <Alert severity="warning">PP1 正式结果需要展示核查：完整证据结论、方法、身份、精确分数或来源对应未通过核对。此处不展示授分；完整原始结果仍保留。</Alert>;
    const e = value.evaluation, qualifications = items(value.qualifications);
    const needs = [...items(e.needs).map(need => ({ need, origin: '正式评价' })),
        ...items(e.clinicalObservationReview?.needs).map(need => ({ need, origin: '同次临床观察' })),
        ...qualifications.flatMap(q => [...items(q.clinicalAssessments?.needs).map(need => ({ need, origin: `${q.sourceIdentity?.pmcid || '来源'} 临床核查` })),
            ...items(q.ascertainmentReview?.needs).map(need => ({ need, origin: `${q.sourceIdentity?.pmcid || '来源'} 取样核查` })),
            ...Object.entries(q.ascertainmentReview?.families || {}).flatMap(([familyKey, family]) => [
                ...items(family.unresolved).map(need => ({ origin: '家系取样核查', need: { ...need,
                    requirement: '需核对该家系取样、分型和报告是否满足已采用方法；当前来源声明尚未形成充分依据。',
                    expected: '与当前目标、全家系和疾病范围一致的完整选择及报告机制',
                    actual: { familyKey, status: family.status, reason: need.reasonCode },
                    knownFacts: family.sufficientConditions, sourceLocators: need.sourceRefs, route: 'SOURCE_REVIEW' } })),
                ...items(family.conflicts).map(conflict => ({ origin: '取样支持与限制冲突', need: { ...conflict,
                    requirement: '支持声明与限制声明同时存在，需核对并解决；不能只采用有利句子。',
                    expected: '全家系选择与报告机制不存在尚未解决的互相限制',
                    actual: { support: conflict.supportSourceRefs, restriction: conflict.restrictionSourceRefs },
                    knownFacts: family.sufficientConditions,
                    sourceLocators: [...items(conflict.supportSourceRefs), ...items(conflict.restrictionSourceRefs)], route: 'SOURCE_REVIEW' } })),
            ])])];
    const uniqueNeeds = [...new Map(needs.map(item => [JSON.stringify(item.need), item])).values()]
        .sort((a,b) => Number(adverse(b.need.reasonCode)) - Number(adverse(a.need.reasonCode)));
    const direction = ({ SUPPORTS: '支持方向', OPPOSES: '反向', UNINFORMATIVE: '中性' })[e.direction] || '尚未计算';
    return <Stack spacing={1.5} data-testid="pp1-formal-facts">
        <Typography variant="h6">PP1 条件家系似然</Typography>
        <Stack direction="row" spacing={1} useFlexGap flexWrap="wrap" alignItems="center">
            <Chip label={e.status === 'MET' ? `满足 · ${displayStrength(e.strength)}` : e.status === 'NOT_MET' ? '已评价 · 未达到采用条件' : '尚未评价'} color={e.status === 'MET' ? 'success' : 'default'} />
            {e.status !== 'NOT_EVALUATED' && <Typography fontWeight={600}>最终贡献 {e.awardedPP1Points} 分</Typography>}
        </Stack>
        {e.status === 'NOT_EVALUATED' ? <Alert severity="warning">本次尚未评价。缺项或冲突尚未解决，不能解释为零贡献或未满足。</Alert> :
            <Alert severity={e.direction === 'OPPOSES' ? 'warning' : 'info'}>精确 BF {fraction(e.bayesFactor)} · {direction}。负向或零贡献不会自动采用 BS4。</Alert>}
        <Typography variant="body2">给定表型、已分型创始者及先证者后的条件似然。科研结果，未授予临床批准；强度由精确有理数阈值核对，对数只作展示。</Typography>
        <Value label="方法 / 模型" value={`${e.method} / ${e.modelVersion}`} />
        <Value label="本次目标" value={e.target} />
        <Typography variant="subtitle2">需要什么、已有事实及差距（{uniqueNeeds.length} 项）</Typography>
        {uniqueNeeds.length ? <Limited values={uniqueNeeds}>{item => <Need value={item.need} origin={item.origin} />}</Limited> :
            <Typography variant="body2">本次结果未登记阻止评价的缺项；来源范围和未采用事实仍见下方。</Typography>}
        <Section title="BF、校准、PP4 容量与贡献">
            <Value label="精确 BF（分子 / 分母）" value={fraction(e.bayesFactor)} />
            <Value label="log10 BF（仅展示）" value={e.log10BayesFactorStatus === 'NEGATIVE_INFINITY' ? '−∞（BF=0）' : e.log10BayesFactor} />
            <Value label="原始校准 / 正向部分 / 容量内分配" value={{ raw: e.rawCalibratedPoints, positive: e.positiveCalibratedPoints, allocated: e.allocatedPP1Points }} />
            <Value label="PP4 已用 / 剩余容量" value={{ pp4Points: e.ledger?.pp4Points, remainingLocusCapacity: e.remainingLocusCapacity }} />
            <Value label="校准规则" value={e.calibration} />
            <Value label="既有贡献与排他事实" value={{ status: e.ledger?.status, eventIds: e.ledger?.eventIds, exclusiveFactIds: e.ledger?.exclusiveFactIds }} />
        </Section>
        <Section title={`家系贡献、四和与已条件化成员（${items(e.familyAssessments).length} 家系）`}>
            <Limited values={e.familyAssessments} maximum={12}>{family => <Paper variant="outlined" sx={{ p: 1.5 }}><Stack spacing={1}>
                <Typography variant="subtitle2">{family.familyKey} · {selectionLabel(family)}</Typography>
                <Value label="家系贡献 ID" value={family.familyContributionId} />
                <Value label="排他事实 ID" value={family.factIds} />
                <Value label="精确 BF" value={fraction(family.computation?.bayesFactor)} />
                <Limited values={Object.entries(family.computation?.fourSums || {})}>{([name, amount]) => <Value label={`四和 ${name}`} value={fraction(amount)} />}</Limited>
                <Value label="已条件化成员" value={family.computation?.conditionedMemberKeys} />
                <Value label="已观察基因型 / 表型成员" value={{ observed: family.computation?.observedMemberKeys, phenotype: family.computation?.phenotypeMemberKeys }} />
                <Value label="同次临床观察 / 核查 ID" value={{ observations: family.clinicalObservationIds, assessments: family.clinicalAssessmentIds }} />
                <Value label="取样与报告机制审核" value={family.selectionAndReportingReviews} />
                <Sources values={family.sourceRefs} />
            </Stack></Paper>}</Limited>
            {!items(e.familyAssessments).length && <Typography variant="body2">本次没有形成可计算的家系贡献；未补造 BF 或四和。</Typography>}
        </Section>
        <Section title="同次临床观察：状态、年龄与原文">
            <Limited values={e.clinicalObservationReview?.memberStates}>{state => <Clinical value={state} />}</Limited>
            <Limited values={qualifications} maximum={12}>{q => <Box><Typography variant="subtitle2">{q.sourceIdentity?.pmcid} 原始临床核查</Typography>
                <Limited values={q.clinicalAssessments?.observations} maximum={12}>{observation => <Clinical value={observation} />}</Limited>
                <Value label="已核范围与限制" value={q.clinicalAssessments?.contextScan} />
            </Box>}</Limited>
        </Section>
        <Section title="取样 / 报告机制、冲突与来源范围">
            <Limited values={qualifications} maximum={12}>{q => <Box><Typography variant="subtitle2">{q.sourceIdentity?.pmcid}</Typography>
                <Value label="来源身份" value={q.sourceIdentity} />
                <Limited values={Object.entries(q.ascertainmentReview?.families || {})} maximum={12}>{([family, review]) => <Alert severity={adverse(review) || items(review.conflicts).length ? 'error' : items(review.unresolved).length ? 'warning' : 'info'}>
                    <Value label={`家系 ${family} 取样机制`} value={review} />
                    <Sources values={[...items(review.sufficientConditions), ...items(review.records), ...items(review.conflicts), ...items(review.unresolved)].flatMap(row => items(row.sourceRefs))} />
                </Alert>}</Limited>
                <Value label="尚未核实的事实" value={q.unresolved} />
            </Box>}</Limited>
            <Value label="未计入的贡献与限制" value={e.ignored} />
            <Value label="资料范围与停止原因" value={value.sourceCoverage} />
            <Value label="未采用的任务" value={value.excludedTasks} />
        </Section>
        <Section title="采用的参数、原件与支持边界">
            <Value label="参数核查状态" value={e.context?.status} />
            <Value label="正式模型与参数" value={e.context?.policy?.formalModel} />
            <Value label="疾病适用、来源和决策依据" value={e.context?.policy} />
            <Limited values={Object.entries(e.context?.sourceEvidence || {})}>{([id, source]) => <Box><Value label={`参数来源 ${id}`} value={source} /><Sources values={[source]} /></Box>}</Limited>
            <Value label="目录 / 采用 / 来源快照摘要" value={{ catalog: e.context?.catalogSha256, adoption: e.context?.adoptionSha256, snapshot: value.sourceSnapshotSha256 }} />
            <Value label="方法适用范围" value={e.scopeStatement} />
        </Section>
        <Typography variant="caption" color="text.secondary">详情按区块限量展示；发生截断时会注明。完整字段、原句及来源仍保留在原始结果。本页结构与一致性核对不代替后台原件重放或参数科学采用。</Typography>
    </Stack>;
}
