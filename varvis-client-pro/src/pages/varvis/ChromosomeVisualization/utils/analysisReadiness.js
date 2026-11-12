// Configuration readiness is not a per-analysis execution record.
export const vepJobExecution = (events = [], hasJob = false) => {
    let status = hasJob ? 'NOT_RECORDED' : 'NOT_REQUESTED';
    let summary = null;
    for (const event of events || []) {
        const match = /^VEP transcript annotation ([A-Z_]+)(?::|$)/.exec(event?.message || '');
        if (!match) continue;
        status = match[1];
        summary = null;
        const fields = {};
        for (const field of event.message.matchAll(/(?:[:,] |^)([A-Za-z]+)=(\d+)(?=,|;|\s|$)/g)) {
            fields[field[1]] = Number(field[2]);
        }
        const required = ['selected', 'processed', 'success', 'failed', 'cacheHit', 'elapsedMs'];
        if (required.every((key) => Number.isSafeInteger(fields[key]))) {
            summary = {
                status, selectedCount: fields.selected, processedCount: fields.processed,
                successCount: fields.success, failedCount: fields.failed,
                cacheHitCount: fields.cacheHit, elapsedMillis: fields.elapsedMs,
                warningCount: fields.warnings || 0, createdAt: event.createdAt,
            };
        }
    }
    return { status, summary };
};

export const vepStatusExplanation = (status) => ({
    DISABLED: '本次注释未启用；不等于基础分析失败。',
    UNAVAILABLE: '本次注释资源不可用，不能据此认定变异低风险。',
    MISSING_RESOURCE: '本次注释缺少所需 VEP 资源。',
    INVALID_RESOURCE: '本次注释的 VEP 资源无效。',
    ASSEMBLY_MISMATCH: 'VEP 资源与本次参考基因组不匹配。',
    PARTIAL: '部分所选候选完成注释，请查看逐候选详情。',
    FAILED: '本次注释准备或保存失败；基础排名仍可查看。',
    COMPLETED: '本次所选候选的注释汇总已完成；不表示整个 VCF 都完成注释。',
    PENDING: '本次作业已记录注释等待执行。',
    RUNNING: '本次作业已记录注释正在运行。',
    NOT_RECORDED: '此任务未记录 VEP 作业汇总；不代表未做注释，也不代表正在等待。请查看候选中已保存的转录本注释。',
    NOT_REQUESTED: '尚未选择分析任务；资源配置就绪不代表已经执行注释。',
}[String(status || '').toUpperCase()] || '');

export const LOCAL_POPULATION_SCOPE = 'BA1、BS1、PM2 使用独立的 ACMG 本地人口数据。';
