import React from 'react';
import { Alert, Stack, Typography } from '@mui/material';

const reasons = {
    READY_FOR_ORIGINAL_EVALUATION: '当前病例的必要资料已具备，可以进入原证据评价；不代表已计分或整篇读完。',
    EXTERNAL_OR_UNSUPPORTED_REQUIREMENT: '剩余缺项依赖本地身份、疾病知识或程序支持，继续读论文不能直接补齐。',
    NO_PENDING_RELEVANT_TARGET: '本轮筛选的相关资料块已处理，仍未补齐所需信息；不能据此断言全文没有。',
    NO_READABLE_NEED: '当前没有可通过继续阅读解决的缺项。',
    RECOVERY_REQUIRED: '部分提取或复核失败、未完成，需核对原记录后恢复；没有自动重复付费。',
    WORK_LIMIT_UNRESOLVED: '部分资料超出当前处理范围，尚未检查。',
    READING_CHUNK_LIMIT: '达到本轮资料块上限；已核资料保留，剩余部分尚未读完。',
    DENOVO_BUNDLE_READING_CALL_LIMIT: '达到本轮模型调用上限；已核资料保留，可受控继续。',
    DENOVO_BUNDLE_READING_TIME_LIMIT: '本轮剩余时间不足；已核资料保留，可受控继续。',
    READING_TIME_LIMIT: '本轮时间已用完；已有原件保留，未读部分不能当作无资料。',
    DENOVO_BUNDLE_READING_MODEL_DISABLED: '模型当前关闭且缓存不足，尚未处理剩余资料。',
    READING_SELECTED_TARGET_UNRESOLVED_OR_AMBIGUOUS: '论文病例与本次候选的变异或疾病背景尚未唯一对应，不能借用其他病例的信息。',
    READING_REVIEWED_SEED_UNAVAILABLE: '尚未取得可供续读的初始提取与来源复核资料。',
    READING_SOURCE_AUDIT_REQUIRED: '初始提取仍需来源复核，暂不采用其中的信息。',
};
const explain = (code) => reasons[code] || `处理暂停，需核对执行记录（${code || '原因记录未取得'}）；不代表原文未报告。`;

export default function DeNovoReadingView({ reading }) {
    if (!reading) return null;
    const cases = Array.isArray(reading.cases) ? reading.cases : [];
    const ready = reading.status === 'MATERIAL_READY' && cases.length > 0 && cases.every((c) => c.requirementsSatisfied === true);
    return <Stack spacing={1} sx={{ my: 1 }}>
        <Alert severity={ready ? 'success' : 'warning'}>
            {ready ? '资料核验已就绪，待原规则评价。' : '资料尚未全部就绪。'}
            {' 系统按缺项自动续读，同一病例的已核资料累计使用。'}
        </Alert>
        {cases.map((item, index) => {
            const plan = item.plan;
            const chunks = Array.isArray(plan?.chunks) ? plan.chunks : [];
            const reviewed = chunks.filter((c) => c.state === 'REVIEWED').length;
            const failed = chunks.filter((c) => ['FAILED', 'HELD', 'INCOMPLETE'].includes(c.state)).length;
            const pending = chunks.filter((c) => c.state === 'PENDING').length;
            const needs = [...new Set((item.feedback?.needs || []).map((n) => n.label).filter(Boolean))];
            return <Stack key={item.caseKey || index} spacing={0.5}>
                <Typography variant="subtitle2">病例 {index + 1}：{item.requirementsSatisfied ? '必要资料已具备' : '仍需核对资料'}</Typography>
                <Typography variant="body2">{explain(item.stopReason)}</Typography>
                {needs.length > 0 && <Typography variant="body2">还缺：{needs.join('；')}。已取得的相关原文、所需条件与差异见下方病例资料。</Typography>}
                {chunks.length > 0 && <Typography variant="body2">
                    已完成来源复核 {reviewed}/{chunks.length} 块；相关待读 {pending} 块；失败或待恢复 {failed} 块；其余 {chunks.length - reviewed - pending - failed} 块未纳入本轮阅读。
                    {' 未读、失败和筛选未命中都不等于原文没有信息。'}
                </Typography>}
                {item.rounds?.length > 0 && <details><summary>本轮自动续读 {item.rounds.length} 步</summary>
                    {item.rounds.map((round, n) => <Typography variant="body2" key={n}>
                        第 {n + 1} 步 · 资料块 {round.chunkIndex + 1}：{round.status === 'FAILED' ? explain(round.reasonCode) :
                            `${round.extractionCacheHit ? '复用原提取' : '取得新提取'}、${round.reviewCacheHit ? '复用来源复核' : '完成来源复核'}；必要缺项 ${round.beforeNeedIds?.length ?? '?'} → ${round.afterNeedIds?.length ?? '?'} 项。`}
                    </Typography>)}
                </details>}
            </Stack>;
        })}
        {reading.issues?.length > 0 && <Typography variant="body2">{[...new Set(reading.issues)].map(explain).join(' ')}</Typography>}
    </Stack>;
}
