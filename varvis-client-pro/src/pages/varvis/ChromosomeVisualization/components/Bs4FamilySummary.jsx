import React from 'react';
import { formatVariantCoordinate } from '../utils/variantCoordinate';
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Paper, Stack, Typography } from '@mui/material';
const show = value => value == null ? '未记录' : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
const role = m => m.role === 'CHILD' ? m.proband ? '子女 · 先证者' : '子女' : m.role === 'FATHER' ? '父亲' : m.role === 'MOTHER' ? '母亲' : m.role;
const detail = (label, value) => <Accordion key={label}><AccordionSummary>{label}（只读）</AccordionSummary><AccordionDetails><Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{show(value)}</Typography></AccordionDetails></Accordion>;
const tableStyle = { overflowX: 'auto', '& th, & td': { borderBottom: '1px solid #ddd', p: 1, textAlign: 'left', verticalAlign: 'top' } };
/** Original family facts and backend reports only. No clinical inference or scoring. */
export default function Bs4FamilySummary({ preparation, core }) {
    const c = core?.sourceBundle?.context || preparation?.materialBinding?.context;
    const o = core?.sourceBundle?.observations?.[0] || preparation?.reviewedObservation;
    if (!c || !o) return null;
    const f = o.facts, a = c.variant.inputAllele, patient = c.patientContext, review = o.nonSegregationReview, disease = o.diseaseApplicability;
    const same = (a, b) => a.normalize('NFC') === b.normalize('NFC');
    const proband = f.members.find(m => m.role === 'CHILD' && m.proband);
    return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1}>
        <Typography variant="h6">BS4 单核家系材料 · Top1 唯一目标</Typography>
        <Typography>{a.assembly} {formatVariantCoordinate(a)} · {c.gene.symbol}（{c.gene.hgncId}） · {c.disease.label}（{c.disease.mondoId}） · {c.moi}</Typography>
        <Typography>患者：{patient.patientContextId} · {patient.role} · 性别原值：{patient.sex} · 受累原值：{patient.affectedStatus} · 先证者样本：{proband?.sampleId}</Typography>
        <Typography>家系：{f.familyId} · 病例：{f.caseId} · observation：{f.observationId} · occurrence：{f.occurrenceId} · {c.dataClass}</Typography>
        <Typography>材料声明的不共分离成员：{review.declaredNonSegregatingMembers.join('、') || '无'}（受审原声明，不是本次后端事件数或分值）</Typography>
        <Typography>疾病机制：{disease.mechanism} · 条件模式：{disease.conditionMode} · 疾病有效性独立于本家系：{show(disease.validityIndependentOfFamily)}</Typography>
        <Typography>检测覆盖：{o.locusAttribution.method} · {o.locusAttribution.coverageScope} · {o.locusAttribution.coverageStatus} · 归属：{o.locusAttribution.allocation}</Typography>
        <Typography fontWeight={600}>全成员记录（{f.members.length} 名，不筛除未计入成员）</Typography>
        <Box sx={tableStyle}><table><thead><tr>{['成员 / 样本', '角色 / 受累', '原 GT / DP / GQ / AD', 'record FILTER / 材料 FT', '临床 / 检测', '独立性 / 阴性检测原值'].map(x => <th key={x}>{x}</th>)}</tr></thead><tbody>
            {f.members.map(m => { const r = f.records.find(v => same(v.memberId, m.memberId)), call = r?.observation, reviewed = review.memberReviews.find(v => same(v.memberId, m.memberId)), negative = reviewed?.negativeTest;
                return <tr key={m.memberId}><td>{m.memberId}<br />{m.sampleId}</td><td>{role(m)}<br />{m.affectedStatus}</td><td>{call?.genotype ?? '未记录'} / {call?.depth ?? '未记录'} / {call?.genotypeQuality ?? '未记录'} / {call?.alleleDepth ?? '未记录'}</td><td>{r?.recordFilter ?? '未记录'} / {call?.sampleFilter ?? '未记录'}</td><td>{m.clinical.diagnosis}<br />年龄：{m.clinical.ageYears} 年<br />{m.detection.status} / {m.detection.coverage}<br />审核动作：{m.clinical.review.status}</td><td>{reviewed?.independence}<br />{negative?.method}<br />{negative?.coverage} / {negative?.capability}<br />结果原值：{negative?.result}</td></tr>; })}
        </tbody></table></Box>
        {detail('原始 FORMAT / rawSample、GT 顺序与来源定位', f.records)}
        {detail('双亲关系、临床检测与逐项审核', { parentage: f.parentage, members: f.members })}
        {detail('可靠阴性检测及来源', review.memberReviews)}
        <Typography fontWeight={600}>逐成员替代解释（材料原结论，页面不自行排除）</Typography>
        <Box sx={tableStyle}><table><thead><tr><th>成员</th><th>替代解释</th><th>审核原结论</th><th>调查方法与依据</th></tr></thead><tbody>
            {disease.alternativeExplanations.map((v, i) => <tr key={i}><td>{v.memberId}</td><td>{v.kind}</td><td>{v.assessment}</td><td>{v.method}<br />{v.basis}</td></tr>)}
        </tbody></table></Box>
        {detail('替代解释调查事实与来源', disease.alternativeExplanations)}
        {core?.nonSegregationEvents && <>
            <Typography variant="h6">后端保存不共分离事件（{core.nonSegregationEvents.length} 条报告，{core.eventCount} 条计入）</Typography>
            <Typography>后端原始点数：{core.rawPoints} · 应用点数：{core.appliedPoints} · 保存强度：{core.evaluation.appliedStrength}；一包只计一次，不按人数或事件累加。</Typography>
            <Box sx={tableStyle}><table><thead><tr><th>子女</th><th>父亲 / 母亲</th><th>计入</th><th>后端原原因</th></tr></thead><tbody>
                {core.nonSegregationEvents.map(e => <tr key={e.eventId}><td>{e.identity.childMemberId}</td><td>{e.identity.fatherMemberId}<br />{e.identity.motherMemberId}</td><td>{e.counted ? '是' : '否'}</td><td>{e.reasonCode}</td></tr>)}
            </tbody></table></Box>
            {core.nonSegregationEvents.map(e => detail(`事件 ${e.identity.childMemberId} 的记录、亲缘、临床与阴性检测来源`, { event: e, reviewedMember: review.memberReviews.find(m => same(m.memberId, e.identity.childMemberId)) }))}
        </>}
        <Alert severity="info">BS4 良性方向证据不是目标最终良性分类。材料确认仅授权本次提交，不代替专业审核；页面不重算遗传事件、点数或强度。材料 FT=PASS 不是发布 FT 见证；公开准备仅有发布来源摘要，先证者发布源不证明全家系。</Alert>
        {Object.entries({ '完整不共分离声明与审核': review, '同病临床、疾病适用、条件及来源': disease, '完整候选归属与 PP1/BS4/PP4 共享证据声明': o.locusAttribution, '家系原始事实及全部来源': f, '已有材料审核': o.assessment, '身份与版本详情': preparation || core?.sourceBundle }).map(([label, value]) => detail(label, value))}
    </Stack></Paper>;
}
