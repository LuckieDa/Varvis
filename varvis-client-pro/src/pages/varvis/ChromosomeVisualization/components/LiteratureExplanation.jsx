import React from 'react';
import { Alert, Link, Stack, Typography } from '@mui/material';

/** Shared read-only explanation; source quotes are rendered as text, never HTML. */
export function LiteratureSources({ sources }) {
    return sources?.length ? <details><summary>原文依据与位置（{sources.length}）</summary>{sources.map((s, i) => <div key={i} style={{ margin: '8px 0', overflowWrap: 'anywhere' }}>
        <Typography variant="body2">{s.title} · {s.paperId}</Typography>
        <Typography variant="body2">位置：{s.locationLabel}</Typography>
        {s.quote && <Typography component="blockquote" variant="body2" sx={{ whiteSpace: 'pre-wrap', ml: 2 }}>{s.quote}</Typography>}
        <Link href={s.url} target="_blank" rel="noopener noreferrer">打开论文；按上述位置或原句查找</Link>
        <details><summary>精确来源标识</summary><Typography variant="caption" sx={{ overflowWrap: 'anywhere' }}>{s.locator} · SHA-256 {s.sourceSha256}</Typography></details>
    </div>)}</details> : null;
}

export default function LiteratureExplanation({ explanation, fields, fieldLabel, valueLabel }) {
    if (!explanation) return <Typography variant="body2">旧记录未保存新版完整判定说明；已有事实和引用仍在下方，不重新提取或补写历史结论。</Typography>;
    return <Stack spacing={1}>
        <Typography fontWeight={600}>事实、判定要求与缺项说明</Typography>
        <Typography>{explanation.summary}</Typography>
        {explanation.items.map(item => <details key={item.id}>
            <summary>{item.label}：{item.finding}</summary>
            <Typography variant="body2">判定需要：{item.requirement}</Typography>
            {item.observedFieldPaths.length > 0 && <details><summary>已经核实的相关事实与依据</summary>{item.observedFieldPaths.map(p => {
                const f = fields.find(v => v.path === p);
                return <div key={p} style={{ margin: '8px 0' }}><Typography variant="body2">{fieldLabel(p)}：{valueLabel(f.value)}</Typography><LiteratureSources sources={f.sourceEvidence} /></div>;
            })}</details>}
            <Typography variant="body2">当前情况：{item.explanation}</Typography>
            <Typography variant="body2">{item.distinction}</Typography>
            <Typography variant="body2">对判定的影响：{item.effect}</Typography>
            <Typography variant="body2">如何继续：{item.nextStep}</Typography>
            <details><summary>检查范围及具体缺项</summary>
                <Typography variant="body2">{item.scopeStatement}</Typography>
                <Typography variant="body2">待核内容：{item.fieldPaths.map(fieldLabel).join('；')}</Typography>
                <LiteratureSources sources={item.checkedScope} />
                <Typography variant="caption">原因代码：{item.reasonCodes.join('；')}</Typography>
            </details>
        </details>)}
        {explanation.warnings.map((w, i) => <Alert severity="info" key={i}>{w.text}<LiteratureSources sources={[w.source]} /></Alert>)}
        <Typography variant="caption">{explanation.scopeStatement}</Typography>
    </Stack>;
}
