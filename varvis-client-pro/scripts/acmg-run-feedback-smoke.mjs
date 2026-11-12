import assert from 'node:assert/strict';
import fs from 'node:fs';

const raw = fs.readFileSync(
    new URL(
        '../src/pages/varvis/ChromosomeVisualization/utils/acmgRunFeedback.js',
        import.meta.url,
    ),
    'utf8',
);
const { acmgRequestFeedback, acmgRunLabel, backgroundOptionsIssue, watchAcmgRun } = await import(
    `data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`
);
let checks = 0;
const test = async (label, run) => {
    await run();
    checks++;
    console.log(`PASS ${label}`);
};

const scheduler = () => {
    let nextId = 0;
    const pending = new Map();
    return {
        pending,
        schedule(callback, delay) {
            const id = ++nextId;
            pending.set(id, { callback, delay });
            return id;
        },
        cancel(id) {
            pending.delete(id);
        },
        async tick() {
            assert.equal(pending.size, 1, 'there must be exactly one pending read');
            const [id, task] = pending.entries().next().value;
            pending.delete(id);
            await task.callback();
        },
    };
};
const deferred = () => {
    let resolve;
    let reject;
    const promise = new Promise((done, fail) => {
        resolve = done;
        reject = fail;
    });
    return { promise, resolve, reject };
};
const savedRun = (status, extra = {}) => ({
    analysisId: 'analysis-under-review',
    runId: 'selected-run',
    status,
    ...extra,
});
const watcher = (read, extra = {}) => {
    const clock = scheduler();
    const calls = [];
    const runs = [];
    const errors = [];
    const stop = watchAcmgRun({
        analysisId: 'analysis-under-review',
        runId: 'selected-run',
        read: (...args) => {
            calls.push(args);
            return read(...args);
        },
        onRun: (value) => runs.push(value),
        onError: (error, context) => errors.push({ error, ...context }),
        schedule: clock.schedule,
        cancel: clock.cancel,
        ...extra,
    });
    return { clock, calls, runs, errors, stop };
};

await test('transport and database waits are warnings, with submission uncertainty only on submit', () => {
    const errors = [
        { code: 'ECONNABORTED' },
        { code: 'ETIMEDOUT' },
        { code: 'ERR_NETWORK' },
        { response: { data: { message: 'ACMG_DATABASE_BUSY' } } },
    ];
    errors.forEach((error) => {
        const submit = acmgRequestFeedback(error, 'submit');
        const read = acmgRequestFeedback(error, 'read');
        assert.equal(submit.severity, 'warning');
        assert.equal(submit.submissionUncertain, true);
        assert.equal(read.severity, 'warning');
        assert.equal(read.submissionUncertain, false);
        assert.notEqual(submit.title, read.title);
        assert.ok(submit.message && read.message);
        assert.deepEqual(acmgRequestFeedback(error), read);
    });
});

await test('a rejected declaration remains an actionable error, not an unknown running task', () => {
    const error = { response: { status: 400, data: { message: '请确认疾病关系。' } } };
    for (const operation of ['submit', 'read']) {
        const feedback = acmgRequestFeedback(error, operation);
        assert.equal(feedback.severity, 'error');
        assert.equal(feedback.submissionUncertain, false);
        assert.equal(feedback.message, '请确认疾病关系。');
    }
});

await test('the actual background blocker enables a fresh preparation when the HTTP body omits its reason', () => {
    const error = { response: { status: 409, data: { error: 'Conflict' } } };
    const changed = acmgRequestFeedback(error, 'submit', {
        status: 'FAILED', phase: 'BACKGROUND_VERIFY', reasonCode: 'ACMG_BACKGROUND_CHANGED_RECONFIRM',
    });
    assert.equal(changed.reconfirmBackground, true);
    assert.equal(changed.submissionUncertain, false);
    assert.match(changed.title + changed.message, /复核已确认的背景失败.*版本不一致.*重新准备/);
    const options = {
        executionSourceRecovered: false, runtimeConfigured: true,
        blockers: [{ stage: 'SOURCE_RECOVERY', reasonCode: 'EXECUTION_SNAPSHOT_TASK_MISMATCH' }],
    };
    const issue = backgroundOptionsIssue(options);
    assert.deepEqual(issue, {
        status: 'FAILED', phase: 'SOURCE_RECOVERY', reasonCode: 'EXECUTION_SNAPSHOT_TASK_MISMATCH',
    });
    assert.match(acmgRequestFeedback(null, 'prepare', issue).message, /当前分析任务或输入文件身份不一致/);
    assert.equal(backgroundOptionsIssue({ ...options, executionSourceRecovered: true }), null);
});

await test('explicit cancellation, legacy unknown stops and a live source check have different recovery paths', () => {
    const error = { code: 'ERR_NETWORK' };
    const cancelled = acmgRequestFeedback(error, 'submit', { status: 'CANCELLED', phase: 'SOURCE_RECOVERY' });
    assert.equal(cancelled.cancelled, true);
    assert.equal(cancelled.submissionUncertain, false);
    for (const progress of [
        { status: 'STOPPED', phase: 'REQUEST_STOPPED' },
        { status: 'RUNNING', phase: 'SOURCE_RECOVERY' },
    ]) {
        const feedback = acmgRequestFeedback(error, 'submit', progress);
        assert.equal(feedback.submissionUncertain, true);
        assert.equal(feedback.reconfirmBackground, undefined);
        assert.equal(feedback.cancelled, undefined);
    }
});

await test('a saved-result integrity failure remains an error even when HTTP status is 503', async () => {
    const error = {
        response: { status: 503, data: { message: 'ACMG_SAVED_RESULT_INTEGRITY_FAILURE' } },
    };
    for (const operation of ['submit', 'read']) {
        const feedback = acmgRequestFeedback(error, operation);
        assert.equal(feedback.severity, 'error');
        assert.equal(feedback.submissionUncertain, false);
        assert.equal(feedback.message, 'ACMG_SAVED_RESULT_INTEGRITY_FAILURE');
    }
    const state = watcher(() => Promise.reject(error));
    await state.clock.tick();
    assert.equal(state.errors.length, 1);
    assert.equal(state.errors[0].error, error);
    assert.equal(state.errors[0].paused, true);
    assert.equal(state.runs.length, 0);
    assert.equal(state.calls.length, 1);
    assert.equal(state.clock.pending.size, 0);
});

await test('running, completed, evidence gaps and execution failure have distinct Chinese labels', () => {
    const statuses = ['RUNNING', 'COMPLETED', 'COMPLETED_WITH_BLOCKERS', 'FAILED'];
    const labels = statuses.map(acmgRunLabel);
    assert.equal(new Set(labels).size, statuses.length);
    labels.forEach((label) => assert.match(label, /[\u4e00-\u9fff]/));
    assert.match(acmgRunLabel('RUNNING'), /正在|运行中|进行中/);
    assert.match(acmgRunLabel('COMPLETED'), /完成/);
    assert.match(acmgRunLabel('COMPLETED_WITH_BLOCKERS'), /完成/);
    assert.doesNotMatch(acmgRunLabel('COMPLETED_WITH_BLOCKERS'), /失败/);
    assert.match(acmgRunLabel('FAILED'), /失败/);
    assert.notEqual(acmgRunLabel(undefined), acmgRunLabel('COMPLETED'));
});

await test('an unacknowledged submission cannot start watching an arbitrary latest run', () => {
    for (const identity of [{ analysisId: '' }, { runId: '' }, { runId: undefined }]) {
        const clock = scheduler();
        let reads = 0;
        assert.throws(() =>
            watchAcmgRun({
                analysisId: 'analysis-under-review',
                runId: 'selected-run',
                ...identity,
                read: () => reads++,
                onRun: () => assert.fail('a run must not be published without identity'),
                onError: () => {},
                schedule: clock.schedule,
                cancel: clock.cancel,
            }),
        );
        assert.equal(reads, 0);
        assert.equal(clock.pending.size, 0);
    }
});

await test('slow GETs never overlap and completion ends watching the acknowledged run', async () => {
    const slowRead = deferred();
    const running = savedRun('RUNNING');
    const completed = savedRun('COMPLETED');
    let count = 0;
    const state = watcher(() => (++count === 1 ? slowRead.promise : completed));
    assert.equal(state.calls.length, 0, 'initial read must wait for its scheduled interval');
    assert.equal(state.clock.pending.values().next().value.delay, 15000);
    const reading = state.clock.tick();
    assert.deepEqual(state.calls, [['analysis-under-review', 'selected-run']]);
    assert.equal(state.clock.pending.size, 0, 'no timer while a read is in flight');
    assert.equal(state.runs.length, 0);
    slowRead.resolve(running);
    await reading;
    assert.deepEqual(state.runs, [running]);
    assert.equal(state.clock.pending.size, 1);
    await state.clock.tick();
    assert.deepEqual(state.runs, [running, completed]);
    assert.deepEqual(state.calls, [
        ['analysis-under-review', 'selected-run'],
        ['analysis-under-review', 'selected-run'],
    ]);
    assert.equal(state.clock.pending.size, 0);
    assert.equal(state.errors.length, 0);
});

await test('completed with gaps and actual failed runs are both terminal', async () => {
    for (const status of ['COMPLETED_WITH_BLOCKERS', 'FAILED']) {
        const result = savedRun(status);
        const state = watcher(() => result);
        await state.clock.tick();
        assert.deepEqual(state.runs, [result]);
        assert.equal(state.calls.length, 1);
        assert.equal(state.clock.pending.size, 0);
        assert.equal(state.errors.length, 0);
    }
});

await test('leaving the page before the next read cancels the timer', () => {
    const state = watcher(() => assert.fail('cancelled timer must not read'));
    state.stop();
    state.stop();
    assert.equal(state.clock.pending.size, 0);
    assert.equal(state.calls.length, 0);
});

await test('a late successful response cannot replace another page or selected run', async () => {
    const slowRead = deferred();
    const state = watcher(() => slowRead.promise);
    const reading = state.clock.tick();
    state.stop();
    slowRead.resolve(savedRun('RUNNING'));
    await reading;
    assert.equal(state.runs.length, 0);
    assert.equal(state.errors.length, 0);
    assert.equal(state.clock.pending.size, 0);
});

await test('a late failed response cannot publish a warning after unmount', async () => {
    const slowRead = deferred();
    const state = watcher(() => slowRead.promise);
    const reading = state.clock.tick();
    state.stop();
    slowRead.reject(new Error('late timeout'));
    await reading;
    assert.equal(state.runs.length, 0);
    assert.equal(state.errors.length, 0);
    assert.equal(state.clock.pending.size, 0);
});

await test('stopping from a result callback prevents a follow-up timer', async () => {
    let state;
    state = watcher(() => savedRun('RUNNING'), { onRun: () => state.stop() });
    await state.clock.tick();
    assert.equal(state.calls.length, 1);
    assert.equal(state.clock.pending.size, 0);
});

await test('another analysis, another run and a latest-run list cannot masquerade as the selected result', async () => {
    const responses = [
        savedRun('COMPLETED', { analysisId: 'another-analysis' }),
        savedRun('COMPLETED', { runId: 'a-newer-run' }),
        [savedRun('COMPLETED')],
        undefined,
    ];
    for (const response of responses) {
        const state = watcher(() => response);
        await state.clock.tick();
        assert.equal(state.runs.length, 0);
        assert.equal(state.errors.length, 1);
        assert.ok(state.errors[0].error instanceof Error);
        assert.equal(state.errors[0].paused, true);
        assert.equal(state.calls.length, 1);
        assert.equal(state.clock.pending.size, 0);
    }
});

await test('result validation errors are preserved and pause immediately, including transport-like errors', async () => {
    for (const error of [
        new Error('saved result contract mismatch'),
        Object.assign(new Error('validation rejected a stale transport response'), {
            code: 'ETIMEDOUT',
        }),
    ]) {
        const state = watcher(() => savedRun('RUNNING'), {
            onRun: () => {
                throw error;
            },
        });
        await state.clock.tick();
        assert.equal(state.errors.length, 1);
        assert.equal(state.errors[0].error, error);
        assert.equal(state.errors[0].paused, true);
        assert.equal(state.calls.length, 1);
        assert.equal(state.clock.pending.size, 0);
    }
});

await test('a nontransport read error pauses immediately without converting it into a retry warning', async () => {
    const error = new Error('unsupported saved result');
    const state = watcher(() => Promise.reject(error));
    await state.clock.tick();
    assert.equal(state.errors.length, 1);
    assert.equal(state.errors[0].error, error);
    assert.equal(state.errors[0].paused, true);
    assert.equal(state.runs.length, 0);
    assert.equal(state.calls.length, 1);
    assert.equal(state.clock.pending.size, 0);
});

await test('a successful read resets consecutive failures; three later failures pause without resubmitting', async () => {
    let attempt = 0;
    const state = watcher(() => {
        attempt++;
        if (attempt === 2) return savedRun('RUNNING');
        throw Object.assign(new Error(`read timeout ${attempt}`), { code: 'ETIMEDOUT' });
    });
    await state.clock.tick();
    assert.deepEqual(
        state.errors.map((error) => error.paused),
        [false],
    );
    await state.clock.tick();
    assert.deepEqual(state.runs, [savedRun('RUNNING')]);
    await state.clock.tick();
    await state.clock.tick();
    assert.deepEqual(
        state.errors.map((error) => error.paused),
        [false, false, false],
    );
    assert.equal(state.clock.pending.size, 1);
    await state.clock.tick();
    assert.deepEqual(
        state.errors.map((error) => error.paused),
        [false, false, false, true],
    );
    assert.equal(state.calls.length, 5);
    state.calls.forEach((args) =>
        assert.deepEqual(args, ['analysis-under-review', 'selected-run']),
    );
    assert.equal(state.clock.pending.size, 0);
});

await test('an explicit short observation budget is respected after an unavailable read', async () => {
    const state = watcher(
        () => Promise.reject(Object.assign(new Error('offline'), { code: 'ERR_NETWORK' })),
        { intervalMs: 1234, maxReadFailures: 1 },
    );
    assert.equal(state.clock.pending.values().next().value.delay, 1234);
    await state.clock.tick();
    assert.equal(state.errors.length, 1);
    assert.equal(state.errors[0].paused, true);
    assert.equal(state.clock.pending.size, 0);
});

console.log(`ACMG run feedback smoke: ${checks}/${checks} passed.`);
