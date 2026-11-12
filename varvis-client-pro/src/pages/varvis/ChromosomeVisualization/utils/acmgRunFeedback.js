export function acmgRunLabel(status) {
    return (
        {
            RUNNING: '正在评价',
            COMPLETED: '评价完成',
            COMPLETED_WITH_BLOCKERS: '评价完成，部分证据待补充',
            FAILED: '执行失败',
            CANCELLED: '已停止',
            INTERRUPTED: '运行已中断',
            RECOVERY_REQUIRED: '执行状态待核实',
        }[status] || '状态待确认'
    );
}

const phases = {
    BACKGROUND_VERIFY: '复核已确认的背景', SOURCE_SNAPSHOT: '读取已保存文献资料并核对版本',
    SOURCE_RECOVERY: '恢复正式候选与来源', RUNTIME_INITIALIZATION: '初始化证据评价环境',
    RUN_CLAIM: '登记评价运行', IDENTITY_ANNOTATION: '核查归一化身份及转录本注释',
    MATERIAL_SUPPLY: '准备文献资料并核查缺项', LOCAL_RESOURCES: '打开本地专用资源',
    PREDICTION: '读取或计算预测资料', POPULATION_CONTEXT: '核查人口频率背景',
    VERIFY_EVALUATE: '核查专用资料并评价', EVALUATE: '评价证据', SAVE: '保存完整结果',
    SAVED: '结果已保存', EXISTING_RUN: '已有相同运行，读取其状态', REQUEST_STOPPED: '请求已停止，请核对运行记录与具体错误',
};

export function liveProgressText(value, candidates = [], hypotheses = []) {
    if (!value || value.status === 'UNAVAILABLE') return '暂未取得运行阶段；这不表示执行失败，请勿重复提交。';
    const c = candidates.find(x => x.candidateId === value.candidateId);
    const h = hypotheses.find(x => x.hypothesis?.candidateId === value.candidateId && x.hypothesis?.hypothesisId === value.hypothesisId)?.hypothesis;
    const elapsed = Number.isFinite(value.elapsedSeconds) ? `已用时 ${value.elapsedSeconds} 秒` : '';
    const updated = value.updatedAt && !Number.isNaN(Date.parse(value.updatedAt)) ? `阶段更新 ${new Date(value.updatedAt).toLocaleTimeString()}` : '';
    return [phases[value.phase] || '正在处理（详细阶段未提供）', c?.geneSymbol, value.candidateId,
        h ? `${h.mondoId || h.hypothesisId} · ${h.moi || '遗传方式未知'}` : value.hypothesisId,
        value.criterion, elapsed, updated].filter(Boolean).join(' · ');
}

// Read-only observer; one failed refresh cannot cancel or repeat the POST.
export function watchSubmissionProgress({ read, onProgress, intervalMs = 5000, schedule = setTimeout, cancel = clearTimeout }) {
    let stopped = false, timer;
    const stop = () => { stopped = true; if (timer !== undefined) cancel(timer); };
    const tick = async () => {
        try { const value = await read(); if (!stopped) onProgress(value); }
        catch (_) { if (!stopped) onProgress({ status: 'UNAVAILABLE' }); }
        if (!stopped) timer = schedule(tick, intervalMs);
    };
    timer = schedule(tick, intervalMs);
    return stop;
}

const reasonPattern = /^[A-Z][A-Z0-9_]{1,159}$/;

export function backgroundOptionsIssue(options) {
    const phase = options?.executionSourceRecovered === false ? 'SOURCE_RECOVERY'
        : options?.runtimeConfigured === false ? 'RUNTIME_INITIALIZATION' : null;
    if (!phase) return null;
    const blocker = Array.isArray(options.blockers) && options.blockers.find(item =>
        item?.stage === phase && reasonPattern.test(item.reasonCode));
    return {
        status: 'FAILED', phase,
        reasonCode: blocker?.reasonCode || (phase === 'SOURCE_RECOVERY'
            ? 'EXECUTION_SOURCE_UNAVAILABLE' : 'ACMG_BACKGROUND_RUNTIME_UNAVAILABLE'),
    };
}

const sourceReasons = {
    EXECUTION_SNAPSHOT_TASK_MISMATCH: '保存的来源与当前分析任务或输入文件身份不一致。',
    EXECUTION_SOURCE_UNAVAILABLE: '未找到该任务保存的执行来源。',
    EXECUTION_SOURCE_FILE_UNAVAILABLE: '该任务的来源文件无法读取。',
    EXECUTION_SOURCE_STORAGE_UNAVAILABLE: '暂时无法读取保存的来源。',
    EXECUTION_SNAPSHOT_HASH_MISMATCH: '保存的来源快照未通过完整性核验。',
    EXECUTION_VCF_ARTIFACT_CHANGED: '发布的 VCF 文件与正式分析记录不一致。',
};

export function acmgRequestFeedback(error, operation = 'read', progress) {
    const body = error?.response?.data;
    const reason = [progress?.reasonCode, body?.reasonCode, body?.message, error?.message]
        .find(value => typeof value === 'string' && value) || '';
    if (progress?.status === 'CANCELLED' || reason === 'ACMG_USER_STOPPED') return {
        severity: 'info', title: '本次分析已停止',
        message: '可在历史记录中核对本次状态。再次分析前请重新准备并确认背景。',
        submissionUncertain: false, cancelled: true,
    };
    if (!progress?.runId) {
        let detail, action;
        let phase = progress?.status === 'FAILED' ? progress.phase : undefined;
        if (reason.includes('ACMG_BACKGROUND_CHANGED_RECONFIRM') || reason === 'RECOMMENDATION_STALE') {
            phase = 'BACKGROUND_VERIFY';
            detail = '当前背景与确认时的版本不一致。';
        } else if (reason === 'ACMG_BACKGROUND_CONFIRMATION_REQUIRED') {
            phase = 'BACKGROUND_VERIFY';
            detail = '未收到有效的背景确认。';
        } else if (phase === 'SOURCE_RECOVERY' || /^EXECUTION_(SOURCE|SNAPSHOT)_/.test(reason)) {
            phase = 'SOURCE_RECOVERY';
            detail = sourceReasons[reason] || '保存的执行来源未通过核验。';
            action = '核对该任务的来源后，重新准备并确认背景。';
        } else if (phase === 'RUNTIME_INITIALIZATION' || reason === 'ACMG_BACKGROUND_RUNTIME_UNAVAILABLE') {
            phase = 'RUNTIME_INITIALIZATION';
            detail = '证据评价环境未就绪。';
            action = '恢复服务后，重新准备并确认背景。';
        } else if (progress?.status === 'FAILED' && phase === 'BACKGROUND_VERIFY' && reason === 'ACMG_REQUEST_FAILED') {
            detail = '本次背景核对未完成。';
        }
        if (detail) return {
            severity: 'warning', title: `${phases[phase]}失败`,
            message: detail + (action || '请重新准备、核对并确认背景。'),
            submissionUncertain: false, reconfirmBackground: true,
        };
    }
    const uncertain =
        !reason.includes('ACMG_SAVED_RESULT_INTEGRITY_FAILURE') &&
        (['ECONNABORTED', 'ETIMEDOUT', 'ERR_NETWORK'].includes(error?.code) ||
            reason === 'ACMG_DATABASE_BUSY' ||
            ['RUNNING', 'STOPPED'].includes(progress?.status) ||
            error?.response?.status >= 500);
    if (uncertain) {
        const submissionUncertain = operation === 'submit';
        return {
            severity: 'warning',
            title: submissionUncertain ? '评价状态待确认' : operation === 'prepare' ? '背景准备暂未完成' : '暂未取得最新状态',
            message: submissionUncertain
                ? '尚未收到完成响应，后台可能仍在运行。正在刷新运行记录，请按本次提交时间选择查看；系统不会重复提交评价。'
                : operation === 'prepare' ? '本次未取得完整背景，请稍后重新准备。尚未启动判定，也不能据此认定数据库没有相关资料。'
                : '本次读取暂未成功，不代表评价失败。请稍后再次读取保存结果。',
            submissionUncertain,
        };
    }
    return {
        severity: 'error',
        title: operation === 'submit' ? '提交未完成' : operation === 'prepare' ? '背景准备未完成' : '未能读取结果',
        message: reason || '请求未完成，请检查服务。',
        submissionUncertain: false,
    };
}

// The caller supplies a selected/acknowledged run ID. A list's newest entry is
// not sufficient to associate an unknown submission with a run in another tab.
export function watchAcmgRun({
    analysisId,
    runId,
    read,
    onRun,
    onError,
    intervalMs = 15000,
    maxReadFailures = 3,
    schedule = setTimeout,
    cancel = clearTimeout,
}) {
    if (!analysisId || !runId) throw new Error('ACMG_RUN_ID_REQUIRED');
    let stopped = false;
    let timer;
    let failures = 0;
    const stop = () => {
        stopped = true;
        if (timer !== undefined) cancel(timer);
    };
    const check = async () => {
        timer = undefined;
        let received = false;
        try {
            const value = await read(analysisId, runId);
            if (stopped) return;
            received = true;
            if (value?.analysisId !== analysisId || value?.runId !== runId)
                throw new Error('保存结果与所选任务或运行不一致，请核查来源。');
            onRun(value);
            failures = 0;
            if (value.status !== 'RUNNING') {
                stop();
                return;
            }
        } catch (error) {
            if (stopped) return;
            failures++;
            const paused =
                received ||
                acmgRequestFeedback(error).severity === 'error' ||
                failures >= maxReadFailures;
            onError(error, { paused });
            if (paused) {
                stop();
                return;
            }
        }
        if (!stopped) timer = schedule(check, intervalMs);
    };
    timer = schedule(check, intervalMs);
    return stop;
}
