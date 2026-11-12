import React, { useEffect, useState } from 'react';
import axios from 'axios';
import { Alert, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';

export default function LiteratureContextSelection({ analysisId, topN, candidates, inputs, onChange, disabled }) {
    const [contexts, setContexts] = useState([]), [error, setError] = useState('');
    useEffect(() => {
        let active = true; setContexts([]); setError('');
        axios.get(`/variant-analyses/${encodeURIComponent(analysisId)}/acmg/literature/pp1/context-options`,
            { params: { topN }, timeout: 40000 }).then(({ data }) => {
            if (data.schemaVersion !== 'literature-context-options-v1' || !Array.isArray(data.contexts)) throw new Error('背景数据格式不完整');
            if (active) setContexts(data.contexts);
        }).catch(e => { if (active) setError(`本地已发布背景读取未完成：${e.response?.data?.message || e.message}`); });
        return () => { active = false; };
    }, [analysisId, topN]);
    return <Paper variant="outlined" sx={{ p: 2 }}><Stack spacing={1}>
        <Typography fontWeight={600}>文献分析背景（可选）</Typography>
        <Typography variant="body2">普通疾病关系列表不包含所需背景时，可从本地已发布规范选择。它是本次分析假设，不是患者诊断或证据批准；PP1仍会独立核查适用性。选择后本候选采用该明确背景，不沿用其他疾病的转录本绑定。</Typography>
        {error && <Alert severity="warning">{error}</Alert>}
        {candidates.map(c => {
            const available = contexts.filter(x => x.geneSymbol === c.geneSymbol && x.hgncId === c.catalog?.hgncId);
            const selected = inputs[c.candidateId]?.literatureContext;
            return <Stack key={c.candidateId} spacing={1}>
                <TextField select label={`#${c.rank} ${c.geneSymbol || ''} 文献疾病背景`} disabled={disabled}
                    value={selected?.contextId || ''} onChange={e => {
                        const choice = available.find(x => x.contextId === e.target.value);
                        onChange(c.candidateId, { literatureContext: choice ? { ...choice, moi: '' } : null,
                            affectedStatus: 'UNKNOWN', annotationId: '', stableRelationshipKey: '' });
                    }}>
                    <MenuItem value="">沿用下方普通疾病关系选择</MenuItem>
                    {available.map(x => <MenuItem key={x.contextId} value={x.contextId}>{x.diseaseLabels.join(' / ')} · {x.mondoId}</MenuItem>)}
                </TextField>
                {selected && <>
                    <TextField select label={`#${c.rank} 文献背景遗传方式`} disabled={disabled} value={selected.moi || ''}
                        onChange={e => onChange(c.candidateId, { literatureContext: { ...selected, moi: e.target.value } })}>
                        <MenuItem value="">请明确选择；不按基因自动推定</MenuItem>
                        {(selected.publishedInheritance.length ? selected.publishedInheritance : ['AD','AR','XLD','XLR','MT']).map(m => <MenuItem key={m} value={m}>{m}</MenuItem>)}
                    </TextField>
                    <Typography variant="body2">{selected.publishedInheritance.length ? '遗传方式来自已核对的规范或精确基因—疾病关联，来源可追溯；不代表患者诊断。' : '现有来源尚未确定该背景的遗传方式，需按本次分析资料明确选择；选择本身不替代科学核实。'}</Typography>
                    {selected.sources.map((s,i) => <a key={i} href={s.sourceUrl} target="_blank" rel="noreferrer">背景出处：{s.specificationKey} · {s.scopePointer}</a>)}
                </>}
                {!available.length && !error && <Typography variant="body2">本地已发布规范暂无该基因的补充背景；不表示不存在相关疾病。</Typography>}
            </Stack>;
        })}
    </Stack></Paper>;
}
