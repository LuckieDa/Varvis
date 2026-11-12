import React, { useState } from 'react';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';

const shown = value => value === undefined ? '未提供' : typeof value === 'object' ? JSON.stringify(value) : String(value);
function Fields({ value, keys }) {
    return <Box component="dl" sx={{ m: 0, display: 'grid', gridTemplateColumns: 'minmax(120px, 1fr) 3fr', gap: 0.5, overflowWrap: 'anywhere' }}>
        {keys.map(([key, label]) => <React.Fragment key={key}><Box component="dt">{label}</Box><Box component="dd" sx={{ m: 0 }}>{shown(value?.[key])}</Box></React.Fragment>)}
    </Box>;
}
// Large source bodies are mounted only when explicitly opened. Every row remains accessible.
function Original({ title, value }) {
    const [open, setOpen] = useState(false);
    return <details onToggle={e => setOpen(e.currentTarget.open)}><summary>{title}</summary>{open && <Box component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 12 }}>{JSON.stringify(value, null, 2) ?? '未提供'}</Box>}</details>;
}
function Cohort({ cases = [] }) {
    const [page, setPage] = useState(0), size = 20;
    const current = Math.min(page, Math.max(0, Math.ceil(cases.length / size) - 1));
    return <details><summary>完整队列名单（{cases.length} 例，含未在该基因诊断者）</summary>
        <Button disabled={!current} onClick={() => setPage(current - 1)}>队列上一页</Button>
        <span>第 {current + 1} / {Math.max(1, Math.ceil(cases.length / size))} 页</span>
        <Button disabled={(current + 1) * size >= cases.length} onClick={() => setPage(current + 1)}>队列下一页</Button>
        {cases.slice(current * size, (current + 1) * size).map((value, index) => <Original key={current * size + index} title={`病例 ${current * size + index + 1} · ${shown(value.caseId)} · ${value.diagnosedInGene === true ? '在该基因诊断' : value.diagnosedInGene === false ? '未在该基因诊断' : '未提供'}`} value={value} />)}
    </details>;
}

/** Read-only returned reports. This component neither determines applicability nor scores PP4. */
export default function Pp4PhenotypeSummary({ preparation, core }) {
    const context = preparation?.materialBinding?.context || core?.sourceBundle?.context;
    const o = preparation?.reviewedObservation || core?.sourceBundle?.observations?.[0];
    if (!o || !context) return null;
    const f = o.facts, phenotype = o.phenotypeReview, yieldReport = o.diagnosticYield, locus = o.locusAttribution;
    const member = f.members?.[0], record = f.records?.[0];
    return <Stack spacing={1} sx={{ minWidth: 0 }}>
        <Alert severity="info">PP4 固定单先证者材料 · 只读受审声明；本次勾选不代替专业审核。单病例证据不等于最终分类，不累计或科学采用。</Alert>
        <Typography variant="subtitle2">目标、病例与检测</Typography>
        <Fields value={{ ...f, sampleId: member?.sampleId, patient: context.patientContext, allele: context.variant?.inputAllele,
            gene: context.gene, disease: context.disease, moi: context.moi, dataClass: context.dataClass }} keys={[
            ['allele', '正式 Top1 目标'], ['gene', '基因'], ['disease', '疾病'], ['moi', '遗传方式'], ['dataClass', '数据类别'], ['sampleId', '样本'], ['patient', '患者身份'], ['familyId', '家系'], ['caseId', '病例'], ['observationId', '观察'], ['occurrenceId', '发生记录']]} />
        <Fields value={{ ...record?.observation, recordFilter: record?.recordFilter, sampleFilter: record?.sampleFilter,
            sampleFormat: member?.detection?.sampleFormat, rawSampleValue: record?.rawSampleValue }} keys={[
            ['genotype', '原 GT'], ['depth', 'DP'], ['genotypeQuality', 'GQ'], ['alleleDepth', 'AD'], ['phased', 'phased'], ['recordFilter', '记录 FILTER'], ['sampleFilter', '材料 FT'], ['sampleFormat', 'FORMAT'], ['rawSampleValue', '原始样本值']]} />
        <Typography variant="subtitle2">表型定义与观察（缺失不等于 ABSENT）</Typography>
        <Fields value={phenotype.definition} keys={[
            ['definitionId', '定义'], ['version', '版本'], ['mechanism', '机制'], ['subtype', '亚型'], ['ageMinimumYears', '最低年龄（岁）'], ['ageMaximumYears', '最高年龄（岁）'], ['mode', '要求模式'], ['requirements', '完整要求']]} />
        {(phenotype.findings || []).map((finding, i) => <Fields key={i} value={finding} keys={[
            ['findingId', '观察 ID'], ['featureId', '特征'], ['presence', 'PRESENT / ABSENT'], ['examination', '检查'], ['method', '方法'], ['ageApplicable', '年龄适用'], ['observedAt', '时间'], ['sourceIds', '来源']]} />)}
        <Typography variant="subtitle2">替代诊断与表型模拟排查</Typography>
        {(phenotype.alternatives || []).map((alternative, i) => <Fields key={i} value={alternative} keys={[
            ['kind', '排查类别'], ['caseId', '病例'], ['method', '原方法'], ['investigation', '调查'], ['detected', '是否检出'], ['unresolvedFindings', '未解决发现'], ['basis', '依据'], ['sourceIds', '来源']]} />)}
        <Original title="完整表型审核、roster 与独立基因疾病依据" value={phenotype} />
        <Typography variant="subtitle2">历史诊断队列：{shown(yieldReport.geneSpecificDiagnosedCount)} / {shown(yieldReport.eligibleTestedCount)}（原分子 / 分母）</Typography>
        <Fields value={yieldReport} keys={[
            ['cohortId', '队列'], ['cohortVersion', '版本'], ['statistic', '统计量'], ['unit', '单位'], ['denominatorDefinition', '分母定义'], ['populationId', '人群'], ['method', '检测方法'], ['testVersion', '检测版本'], ['comparability', '比较范围'], ['independence', '独立性'], ['rosterStatus', '名单状态'], ['ascertainment', '纳入模式']]} />
        <Fields value={yieldReport.ascertainmentDetails} keys={[
            ['eligibility', '入组资格'], ['testing', '检测覆盖'], ['resultSelection', '结果选择'], ['description', '原始描述'], ['basis', '受审依据'], ['sourceIds', '来源']]} />
        <Cohort key={preparation?.preparationToken || core?.sourceBundle?.context?.contextId} cases={yieldReport.cases} />
        <Original title="诊断率完整原件（含全部 cases）" value={yieldReport} />
        <Typography variant="subtitle2">位点归属与证据使用（包内受审声明）</Typography>
        <Fields value={locus} keys={[
            ['coverage', '覆盖'], ['allocation', '归属'], ['candidates', '全部候选（含已排除）'], ['usedEvidence', 'PP1 / BS4 / PP4 共享组'], ['clinicalUse', 'PS2 / PM6 / PM3 / PS4 临床来源使用']]} />
        <Original title="位点归属完整原文" value={locus} />
        {core && <Fields value={core} keys={[
            ['rawPoints', '后端原始点数'], ['appliedPoints', '后端应用点数'], ['rawHalfPoints', '原始半点整数'], ['appliedHalfPoints', '应用半点整数'], ['pointsEncoding', '点数编码'], ['capReason', '上限原因'], ['algorithmVersion', '查表版本'], ['evidenceGroupId', '共享证据组']]} />}
        <Original title="四部件来源、rawUtf8、审核与完整材料" value={o} />
        <Original title="身份、摘要与准备原文" value={preparation || core?.sourceBundle} />
    </Stack>;
}
