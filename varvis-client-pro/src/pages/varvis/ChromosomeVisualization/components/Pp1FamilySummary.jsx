import React from 'react';
import { formatVariantCoordinate } from '../utils/variantCoordinate';
import { Accordion, AccordionDetails, AccordionSummary, Alert, Box, Paper, Stack, Typography } from '@mui/material';
const show = value => value == null ? '未记录' : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
const role = m => m.role === 'CHILD' ? m.proband ? '子女 · 先证者' : '子女' : m.role === 'FATHER' ? '父亲' : m.role === 'MOTHER' ? '母亲' : m.role;
const detail = (label, value) => <Accordion key={label}><AccordionSummary>{label}（只读）</AccordionSummary><AccordionDetails><Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{show(value)}</Typography></AccordionDetails></Accordion>;
/** Presentation of returned facts and reported events. No genotype or clinical scoring. */
export default function Pp1FamilySummary({ preparation, core }) {
    const c = core?.sourceBundle?.context || preparation?.materialBinding?.context;
    const o = core?.sourceBundle?.observations?.[0] || preparation?.reviewedObservation;
    if (!c || !o) return null;
    const f = o.facts, a = c.variant.inputAllele, patient = c.patientContext;
    const member = id => f.members.find(m => m.memberId.normalize('NFC') === id.normalize('NFC'));
    return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1}>
        <Typography variant="h6">PP1 单核家系材料 · Top1 唯一目标</Typography>
        <Typography>{a.assembly} {formatVariantCoordinate(a)} · {c.gene.symbol}（{c.gene.hgncId}） · {c.disease.label}（{c.disease.mondoId}） · {c.moi}</Typography>
        <Typography>患者：{patient.patientContextId} · {patient.role} · 性别原值：{patient.sex} · 受累原值：{patient.affectedStatus}</Typography>
        <Typography>家系：{f.familyId} · 病例：{f.caseId} · observation：{f.observationId} · occurrence：{f.occurrenceId} · {c.dataClass}</Typography>
        <Typography>材料声明传递数：{o.segregationReview.declaredCount}（受审声明，不是本次计算结果）</Typography>
        <Typography>疾病机制：{o.diseaseApplicability.mechanism} · 条件模式原值：{o.diseaseApplicability.conditionMode}</Typography>
        <Typography>检测覆盖方法：{o.locusAttribution.method} · {o.locusAttribution.coverageScope} · {o.locusAttribution.coverageStatus} · 归属：{o.locusAttribution.allocation}</Typography>
        <Typography fontWeight={600}>全成员记录（{f.members.length} 名，不筛除未计入成员）</Typography>
        <Box sx={{ overflowX: 'auto', '& th, & td': { borderBottom: '1px solid #ddd', p: 1, textAlign: 'left', verticalAlign: 'top' } }}>
            <table><thead><tr>{['成员 / 样本', '角色 / 受累', '原 GT / DP / GQ / AD', 'record FILTER / sample FT', '临床事实', '检测事实'].map(x => <th key={x}>{x}</th>)}</tr></thead>
                <tbody>{f.members.map(m => { const r = f.records.find(v => v.memberId.normalize('NFC') === m.memberId.normalize('NFC')), call = r?.observation;
                    return <tr key={m.memberId}><td>{m.memberId}<br />{m.sampleId}</td><td>{role(m)}<br />{m.affectedStatus}</td><td>{call?.genotype ?? '未记录'} / {call?.depth ?? '未记录'} / {call?.genotypeQuality ?? '未记录'} / {call?.alleleDepth ?? '未记录'}</td><td>{r?.recordFilter ?? '未记录'} / {call?.sampleFilter ?? '未记录'}</td><td>年龄：{m.clinical.ageYears} 年<br />{m.clinical.diagnosis}<br />审核动作：{m.clinical.review.status}</td><td>{m.detection.status}<br />{m.detection.method}<br />覆盖：{m.detection.coverage}<br />审核动作：{m.detection.review.status}</td></tr>; })}</tbody>
            </table>
        </Box>
        {detail('成员原始 FORMAT / rawSample 与定位', f.records)}
        {detail('亲缘事实与逐项审核', f.parentage)}
        {core?.transmissionEvents && <>
            <Typography variant="h6">后端保存传递事件（{core.transmissionEvents.length} 条，{core.transmissionEvents.filter(e => e.counted).length} 条计入）</Typography>
            <Typography>后端原始点数：{core.rawPoints} · 应用点数：{core.appliedPoints} · 保存强度：{core.evaluation.appliedStrength}</Typography>
            <Box sx={{ overflowX: 'auto', '& th, & td': { p: 1, borderBottom: '1px solid #ddd', textAlign: 'left' } }}><table><thead><tr><th>亲本 → 子女</th><th>先证者</th><th>计入</th><th>后端原原因</th></tr></thead><tbody>
                {core.transmissionEvents.map(e => <tr key={e.eventId}><td>{role(member(e.identity.parentMemberId))} {e.identity.parentMemberId} → {e.identity.childMemberId}</td><td>{member(e.identity.childMemberId)?.proband ? '是' : '否'}</td><td>{e.counted ? '是' : '否'}</td><td>{e.reasonCode}</td></tr>)}
            </tbody></table></Box>
            {core.transmissionEvents.map(e => detail(`传递 ${e.identity.childMemberId} 的原三记录、双亲关系与临床依据`, e))}
        </>}
        <Alert severity="info">材料确认只授权本次提交，不代替专业审核。页面不从 GT、相位或表型推断传递，不重算点数或强度。材料 FT=PASS 不是发布 FT 见证；公开准备仅返回发布来源摘要。选定先证者的发布源不证明全家系。</Alert>
        {Object.entries({ '传递审核与完整成员声明': o.segregationReview, '疾病适用、条件及来源': o.diseaseApplicability, '完整候选归属与 PP1/PP4 共享证据声明': o.locusAttribution, '原始家系事实与全部来源': o.facts, '已有材料审核': o.assessment, '身份与版本详情': preparation || core?.sourceBundle }).map(([label, value]) => detail(label, value))}
    </Stack></Paper>;
}
