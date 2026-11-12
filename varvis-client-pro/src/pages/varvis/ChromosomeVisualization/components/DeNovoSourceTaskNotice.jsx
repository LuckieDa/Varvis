import React, { useEffect, useState } from 'react';
import { Alert, Button, Stack, Typography } from '@mui/material';
import { fetchDeNovoSourceTask } from '../services/deNovoSourceTask';
import { sourceTaskMessage, validSourceTask, watchSourceTask } from '../utils/deNovoSourceTask';
import { reasonHelp } from '../utils/acmgInput';

// Uses the run-bound status endpoint; taskId is never a public-literature URL.
export function readDeNovoSourceEntry({ run, onStatus, onError, read = fetchDeNovoSourceTask, ...watchOptions }) {
    const initial = run?.localBatch?.modules?.deNovo?.sourceTask;
    if (!run?.analysisId || !run?.runId || (initial && !validSourceTask(initial, { taskId: initial.taskId }))) {
        onError('资料任务绑定或保存回执不可用，暂不能核对实时状态。');
        return () => undefined;
    }
    const workflow = run.executionProfile?.profileId === 'acmg-local-material-and-computational-v1' ? 'LOCAL_COMPUTATIONAL_BATCH1' : 'CURRENT';
    const readStatus = (analysisId, runId, signal) => read(analysisId, runId, workflow, signal);
    if (initial) return watchSourceTask({ ...watchOptions, initial, analysisId: run.analysisId, runId: run.runId,
        read: readStatus, onStatus, onError });
    // Lazy result headers omit sourceSupply. Ask the fixed run-bound endpoint for
    // its actual binding; never infer a literature task ID or start a worker.
    const controller = new AbortController();
    let stopped = false, stopWatch;
    Promise.resolve().then(() => stopped ? undefined : readStatus(run.analysisId, run.runId, controller.signal)).then(value => {
        if (stopped) return;
        if (!validSourceTask(value, { taskId: value?.taskId, analysisId: run.analysisId, runId: run.runId }))
            throw new Error('资料任务身份或状态不相容，未采用这次响应。');
        let first = true;
        stopWatch = watchSourceTask({ ...watchOptions, initial: value, analysisId: run.analysisId, runId: run.runId,
            read: (...args) => { if (first) { first = false; return Promise.resolve(value); } return readStatus(...args); }, onStatus, onError });
    }).catch(error => { if (!stopped) onError(error.message === '资料任务身份或状态不相容，未采用这次响应。' ? error.message :
        '暂时无法核对本运行的资料任务绑定或实时状态；未启动取证或重新分析。'); });
    return () => { stopped = true; controller.abort(); stopWatch?.(); };
}

export default function DeNovoSourceTaskNotice({ run, readOnlyEntry = false, onReturn }) {
    const initial = run?.localBatch?.modules?.deNovo?.sourceTask;
    const [live, setLive] = useState(null);
    const [error, setError] = useState('');
    const [revision, setRevision] = useState(0);
    const identity = `${run?.analysisId}/${run?.runId}/${initial?.taskId}`;
    const lookup = readOnlyEntry && !initial && Boolean(run?.analysisId && run?.runId);
    useEffect(() => {
        setLive(null); setError('');
        if ((!lookup && !validSourceTask(initial, { taskId: initial?.taskId })) ||
            (!readOnlyEntry && !['QUEUED', 'RUNNING'].includes(initial.status) && revision === 0)) return undefined;
        return readDeNovoSourceEntry({ run, onStatus: value => setLive({ identity, value }), onError: setError });
    }, [identity, initial, revision, readOnlyEntry, lookup, run, run?.analysisId, run?.runId, run?.executionProfile?.profileId]);
    if (!lookup && !validSourceTask(initial, { taskId: initial?.taskId })) return readOnlyEntry ? <Alert severity="warning">资料任务绑定或保存回执不可用。{onReturn && <Button onClick={onReturn}>返回证据详情</Button>}</Alert> : null;
    const task = live?.identity === identity ? live.value : initial;
    if (!task) return <Alert severity={error ? 'warning' : 'info'}>
        {error ? `资料任务状态核对失败：${error}` : '正在读取资料任务状态…'}
        {onReturn && <Button onClick={onReturn}>返回证据详情</Button>}
        <Button onClick={() => setRevision(v => v + 1)}>只读刷新资料状态</Button>
    </Alert>;
    return <Alert severity={error || ['FAILED', 'CANCELLED'].includes(task.status) ? 'warning' : 'info'}>
        <Stack spacing={1}>
            <Typography variant="body2">{task.status === 'READY' ? '资料获取已完成' : sourceTaskMessage(task)}</Typography>
            {task.reasonCode && task.reasonCode !== 'DENOVO_SUPPLY_PENDING' && <Typography variant="body2">{reasonHelp(task.reasonCode, 'DENOVO')}</Typography>}
            {task.attemptNumber && <Typography variant="caption">此资料请求已使用 {task.attemptNumber} / {task.maximumAttempts} 次尝试。</Typography>}
            {!error && live?.identity === identity && task.canRetry && <Typography variant="body2">可返回分析准备区重新提交评价。</Typography>}
            {task.retryBlockedReason && <Typography variant="body2">{reasonHelp(task.retryBlockedReason, 'DENOVO')}</Typography>}
            {error && <Typography variant="body2">资料任务状态核对失败：{error}</Typography>}
            {onReturn && <Button onClick={onReturn}>返回证据详情</Button>}
            <Button size="small" onClick={() => setRevision(v => v + 1)}>只读刷新资料状态</Button>
        </Stack>
    </Alert>;
}
