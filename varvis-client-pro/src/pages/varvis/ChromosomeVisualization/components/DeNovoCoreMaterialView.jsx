import { deNovoSourceDiscoveryDetails } from '../utils/acmgDeNovoMethods';
import React from 'react';
import { Alert, Paper, Stack, Typography } from '@mui/material';
import { coreGroups, coreFieldLabel, coreValue, coreFactState, coreBlockerText, publishedConditionValue, validCoreMaterial } from '../utils/deNovoCoreMaterial';
import LiteratureExplanation, { LiteratureSources } from './LiteratureExplanation';
import { hasDisplayValue } from '../utils/evidencePresentation';

function SourceDiscovery({value}) {
    if (!value) return null;
    const rows = deNovoSourceDiscoveryDetails(value);
    const failed = [...(value.issues || []).map(issue=>issue.code), ...(value.records || []).flatMap(record=>record.issues || [])]
        .some(code=>/FAIL|INVALID|CONFLICT|ERROR/.test(code));
    return <Paper variant="outlined" sx={{p:1.5,my:1,borderColor:'warning.light'}}>
        <Typography fontWeight={600}>原件自动定位与尚未采用的资料</Typography>
        <Alert severity={failed?'error':'info'}>这里只说明在哪些已取得原件找到哪些字段及仍缺什么；定位成功不代表已成立新发证据。</Alert>
        {rows.slice(0,48).map(([label,text],index)=><Typography key={`${label}:${index}`} variant="body2" sx={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',mt:0.5}}>
            {label}：{String(text).slice(0,6000)}{String(text).length>6000?'（本字段仅展示前6000字符，完整值保留）':''}
        </Typography>)}
        {rows.length>48 && <Typography variant="caption">此处展示48 / {rows.length}项，完整定位、原句和来源仍保留在原始结果。</Typography>}
    </Paper>;
}

/** Read-only saved public-paper facts. No fetching, extraction or scoring here. */
export default function DeNovoCoreMaterialView({ materials }) {
    if (materials === undefined) return <Typography variant="body2">本记录未附新版统一资料视图；保留旧结果，不在读取时重新提取。</Typography>;
    if (!Array.isArray(materials) || materials.length > 256 || materials.some(m => !validCoreMaterial(m)))
        return <Alert severity="warning">统一资料视图格式或身份不完整，不能作为可信资料展示；完整原始结果仍保留。</Alert>;
    if (!materials.length) return <Typography>本记录没有可展示的统一文献病例资料。</Typography>;
    return <Stack spacing={2}>{materials.map((m, i) => <Paper variant="outlined" sx={{ p: 2 }} key={`${m.caseKey}-${i}`}>
        <Typography fontWeight={600}>统一病例资料 · {m.caseKey}</Typography>
        <Typography>{m.dataClass === 'SYNTHETIC' ? '合成验证资料' : '公开论文科研资料'}；资料预检：{m.status === 'READY_FOR_EVALUATION' ? '具备进入原规则核验的条件' : '仍有待补或待核项'}。</Typography>
        <Typography variant="body2" color="text.secondary">资料预检不等于获得 PS2 / PM6。最终积分以已保存的证据结果为准，不代表临床批准。</Typography>
        {m.selectionMatch && <Typography>与本次选择：{({ EXACT: '精确变异与基因相符，疾病及事件资格仍由原规则核验', DIFFERENT_TARGET: '非本次目标，不据此贡献分数', UNRESOLVED: '身份尚未核实' })[m.selectionMatch]}</Typography>}
        <SourceDiscovery value={m.sourceDiscovery} />
        <LiteratureExplanation explanation={m.explanation} fields={m.fields} fieldLabel={coreFieldLabel} valueLabel={coreValue} />
        {!m.explanation && m.blockers.length > 0 && <details><summary>当前阻断原因（{m.blockers.length}项）</summary>{m.blockers.map((b, j) => <Typography key={j} variant="body2" sx={{ my: 1, overflowWrap: 'anywhere' }}>
            {coreFieldLabel(b.path)}：{coreBlockerText(b)} 原因代码：{[b.code, ...(b.causeCodes || [])].join('；')}
        </Typography>)}</details>}
        <details><summary>已记录的病例资料与逐项引用</summary>
        {[...Object.entries(coreGroups).map(([g, label]) => [`/groups/${g}/`, label]), ['/context/', '判定上下文'], ['/conditions/', '规则触发条件'], ['/extensions/', '按条件需要的补充资料']].map(([prefix, label]) => {
            const fields = m.fields.filter(f => f.path.startsWith(prefix) && (hasDisplayValue(f.value) || f.citations.length || f.sourceEvidence?.length));
            if (!fields.length) return null;
            return <details key={prefix}><summary>{label}（已核实 {fields.filter(f => f.verification === 'VERIFIED' && f.availability === 'REPORTED').length} / {fields.length} 字段）</summary>
                {fields.map(f => <div key={f.path} style={{ margin: '12px 0', overflowWrap: 'anywhere' }}>
                    <Typography fontWeight={500}>{coreFieldLabel(f.path)}：{coreFactState(f)}</Typography>
                    {hasDisplayValue(f.value) && <Typography variant="body2">记录值：{publishedConditionValue(f) || coreValue(f.value)}{f.verification !== 'VERIFIED' ? '（未核实候选，不直接采用）' : ''}</Typography>}
                    {(hasDisplayValue(f.subject) || f.issues.length > 0) && <Typography variant="caption">{hasDisplayValue(f.subject) ? `归属：${coreValue(f.subject)}` : ''}{f.issues.length > 0 ? `；${f.issues.join('；')}` : ''}</Typography>}
                    <LiteratureSources sources={f.sourceEvidence} />
                    {!!f.citations.length && <details><summary>原文定位与依据（{f.citations.length}）</summary>{f.citations.map((c, n) => <div key={n}>
                        <Typography variant="caption" component="div">{c.role} · {c.locator} · SHA-256 {c.sourceSha256}</Typography>
                        <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{c.quote || '内部核验资料只展示摘要标识，不公开本地路径或内部原文。'}</Typography>
                    </div>)}</details>}
                </div>)}
            </details>;
        })}</details>
        <details><summary>范围及保存身份</summary><Typography variant="body2">不是整篇论文及所有附件均已完成复核。以下未处理范围保留为缺项，不能推断为正常或未报告。</Typography>
            <Typography component="pre" sx={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{m.coverageLimitations.join('\n') || '没有额外登记的范围说明；不等于全库检索完成。'}</Typography>
            <Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>资料 {m.documentSha256}；复核台账 {m.ledgerSha256}</Typography>
        </details>
    </Paper>)}</Stack>;
}
