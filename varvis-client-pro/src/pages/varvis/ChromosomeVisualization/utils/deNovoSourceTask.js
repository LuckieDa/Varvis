const phases = new Set(['QUEUED', 'RUNNING', 'READY', 'FAILED', 'CANCELLED']);
export function validSourceTask(value, expected) {
    return !!value && value.schemaVersion === 'denovo-source-task-v1' && phases.has(value.status) &&
        /^[a-f0-9]{64}$/.test(value.taskId || '') && value.taskId === expected.taskId &&
        value.scientificExecution === false && value.modelCalls === 0 &&
        (!expected.runId || value.runId === expected.runId && value.analysisId === expected.analysisId && value.savedResultChanged === false) &&
        (value.status !== 'READY' || /^[a-f0-9]{64}$/.test(value.resultSha256 || '')) &&
        (!['attemptNumber', 'maximumAttempts', 'canRetry'].some(k => Object.hasOwn(value, k)) || Number.isInteger(value.attemptNumber) && value.attemptNumber >= 1 &&
            value.attemptNumber <= 3 && value.maximumAttempts === 3 && typeof value.canRetry === 'boolean' &&
            (!value.canRetry || ['FAILED', 'CANCELLED'].includes(value.status) && value.attemptNumber < 3)) &&
        (!Object.hasOwn(value, 'retryBlockedReason') || /^[A-Z][A-Z0-9_]{0,160}$/.test(value.retryBlockedReason)) &&
        (!Object.hasOwn(value, 'reasonCode') || /^[A-Z][A-Z0-9_]{0,160}$/.test(value.reasonCode));
}
export function sourceTaskMessage(task) {
    return ({
        QUEUED: '参考文献获取已排队；其他证据结果已先保存。',
        RUNNING: '正在独立获取参考文献；这不表示已经查无病例。',
        READY: '资料获取任务已结束。可使用原确认范围显式重试，核实新资料并保存新的评价；本条历史保持不变。',
        FAILED: '参考文献获取未完成。现有证据结果保持，不能将获取失败解释为没有相关病例。',
        CANCELLED: '参考文献任务已取消，现有证据结果保持。',
    })[task?.status] || '资料任务状态无法核实。';
}

/** Read-only polling, with a total observation budget. Never submits/retries an ACMG run. */
export function watchSourceTask({ initial, analysisId, runId, read, onStatus, onError, delay = 3000, budget = 150000,
    clock = Date.now, schedule = setTimeout, unschedule = clearTimeout }) {
    const controller = new AbortController();
    const expected = { taskId: initial.taskId, analysisId, runId };
    const deadline = clock() + budget;
    let stopped = false; let timer; let attempts = 0;
    const poll = async () => {
        if (stopped) return;
        if (++attempts > 50 || clock() >= deadline) { onError('自动读取已达到观察上限；资料任务不会因此重试或取消，可稍后手动查看。'); return; }
        try {
            const value = await read(analysisId, runId, controller.signal);
            if (stopped) return;
            if (!validSourceTask(value, expected)) throw new Error('资料任务身份或状态不相容，未采用这次响应。');
            onStatus(value);
            if (['QUEUED', 'RUNNING'].includes(value.status)) timer = schedule(poll, delay);
        } catch (error) {
            if (!stopped) onError(error.message === '资料任务身份或状态不相容，未采用这次响应。' ? error.message : '暂时无法读取资料任务状态，未自动重试或重新取材。');
        }
    };
    poll();
    return () => { stopped = true; controller.abort(); if (timer !== undefined) unschedule(timer); };
}
