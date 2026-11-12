import React from 'react';
import { Stack, Typography } from '@mui/material';

const levels = { HIGH: '优先阅读', NORMAL: '常规阅读', LOW: '较后阅读', UNRESOLVED: '相关性待核实' };
const identities = { ALIAS_MATCH_ONLY: '检索别名匹配，具体等位变异待核实', UNRESOLVED: '身份对应待核实', CONFLICT: '检索身份冲突，不能据此采用' };

export default function LiteraturePriorityView({ result }) {
    const priority = result?.readingPriority;
    if (!priority || priority.schemaVersion !== 'literature-reading-advice-v1' || priority.status === 'DISABLED') return null;
    const items = Array.isArray(priority.items) ? priority.items : [];
    const acquisitions = Array.isArray(result.acquisition) ? result.acquisition : result.acquisition?.papers || [];
    const registered = acquisitions.filter(p => p.status === 'REGISTERED').length;
    const papers = result.papers || [];
    const order = priority.orderedPaperKeys || [];
    const ordered = [...items].sort((a, b) => order.indexOf(a.paperKey) - order.indexOf(b.paperKey));
    return <Stack spacing={1} data-testid="literature-priority">
        <Typography variant="subtitle2">文献阅读优先级与缓存</Typography>
        <Typography variant="body2">
            已缓存可读原文 {registered} 篇，本轮进入深入读取 {papers.length} 篇；复用阅读建议 {priority.cacheHits || 0} 项。
            {priority.limits && ` 本批最多缓存 ${priority.limits.maximumPrefetch} 篇，深入读取仍受任务与模型总预算限制。`}
        </Typography>
        <Typography variant="body2">模型仅依据标题和摘要建议阅读顺序。</Typography>
        {priority.status === 'PARTIAL' && <Typography variant="body2">部分论文尚无有效阅读建议。</Typography>}
        <details><summary>查看排序理由、原文依据与处理进度（{items.length} 项）</summary>
            {ordered.map((item, i) => {
                const acquired = acquisitions.find(p => p.pmcid === item.paperKey);
                const read = papers.find(p => p.pmcid === item.paperKey);
                const publicId = /^PMC[1-9][0-9]*$/.test(item.paperKey || '') ? item.paperKey : null;
                return <Stack key={item.id || i} spacing={0.5} sx={{ my: 1, pl: 1, borderLeft: '2px solid #ddd' }}>
                    <Typography variant="body2">{levels[item.priority] || '待核实'} · {item.title || item.paperKey} · {item.targetId}</Typography>
                    <Typography variant="body2">{identities[item.identityState] || '身份尚未确认'}。{item.reason}</Typography>
                    <Typography variant="body2">{read ? '已进入深入读取，具体进度见论文资料' : acquired?.status === 'REGISTERED' ? '原文已缓存，尚未深入读取' : '原文尚未取得，具体原因见获取记录'}；{item.cacheHit ? '阅读建议来自核验缓存' : '阅读建议为本轮结果或待补充'}。</Typography>
                    {(item.citations || []).map((c, n) => <Typography variant="body2" key={n}>
                        {c.field === 'title' ? '标题' : '摘要'}原文：“{c.quote}”
                    </Typography>)}
                    {publicId && <a href={`https://pmc.ncbi.nlm.nih.gov/articles/${publicId}/`} target="_blank" rel="noreferrer">查看论文原文</a>}
                </Stack>;
            })}
        </details>
    </Stack>;
}
