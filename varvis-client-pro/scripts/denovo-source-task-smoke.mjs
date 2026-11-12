import assert from 'node:assert/strict';
import fs from 'node:fs';
const raw = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/deNovoSourceTask.js', import.meta.url));
const { validSourceTask, sourceTaskMessage, watchSourceTask } = await import(`data:text/javascript;base64,${raw.toString('base64')}`);
const initial = { schemaVersion: 'denovo-source-task-v1', taskId: 'a'.repeat(64), status: 'QUEUED', scientificExecution: false, modelCalls: 0 };
const expected = { taskId: initial.taskId, analysisId: 'SYNTHETIC-analysis', runId: 'SYNTHETIC-run' };
const response = status => ({ ...initial, ...expected, savedResultChanged: false, status, ...(status === 'READY' ? { resultSha256: 'b'.repeat(64) } : {}) });
let passed = 0;
function test(name, action) { action(); passed++; console.log(`PASS ${name}`); }
test('initial pending identity', () => assert.ok(validSourceTask(initial, { taskId: initial.taskId })));
for (const [name, mutate] of [
    ['wrong run', x => x.runId = 'other'], ['wrong analysis', x => x.analysisId = 'other'],
    ['wrong source task', x => x.taskId = 'c'.repeat(64)], ['changed history', x => x.savedResultChanged = true],
    ['unknown phase', x => x.status = 'COMPLETED'], ['missing ready pin', x => delete x.resultSha256],
    ['model claim', x => x.modelCalls = 1], ['scientific call', x => x.scientificExecution = true],
]) test(name, () => { const x = response('READY'); mutate(x); assert.equal(validSourceTask(x, expected), false); });
test('ready is source preparation, not automatic scientific adoption', () => {
    assert.match(sourceTaskMessage(response('READY')), /核实新资料/); assert.match(sourceTaskMessage(response('READY')), /历史保持不变/);
});
test('bounded retry metadata is valid only for a failed unfinished budget', () => {
    const value={...response('FAILED'),attemptNumber:1,maximumAttempts:3,canRetry:true};
    assert.ok(validSourceTask(value,expected));
    for(const change of [{attemptNumber:0},{attemptNumber:4},{maximumAttempts:100},{status:'READY',resultSha256:'b'.repeat(64)},{attemptNumber:3}])
        assert.equal(validSourceTask({...value,...change},expected),false);
    assert.ok(validSourceTask({...value,attemptNumber:3,canRetry:false,retryBlockedReason:'DENOVO_TASK_RETRY_LIMIT'},expected));
    const incomplete={...value};delete incomplete.attemptNumber;assert.equal(validSourceTask(incomplete,expected),false);
});
const flush = () => new Promise(resolve => setImmediate(resolve));
{
    const queue = []; const seen = []; let reads = 0;
    const stop = watchSourceTask({ initial, ...expected, read: async () => response(++reads === 1 ? 'RUNNING' : 'READY'),
        onStatus: x => seen.push(x.status), onError: x => assert.fail(x), schedule: callback => { queue.push(callback); return 1; }, unschedule: () => undefined });
    await flush(); assert.deepEqual(seen, ['RUNNING']); assert.equal(queue.length, 1); queue.shift()(); await flush();
    assert.deepEqual(seen, ['RUNNING', 'READY']); assert.equal(queue.length, 0); assert.equal(reads, 2); stop();
    passed++; console.log('PASS read-only polling stops at READY');
}
{
    let finish; let seen = 0; let aborted = false;
    const stop = watchSourceTask({ initial, ...expected, read: (_a, _r, signal) => { signal.addEventListener('abort', () => aborted = true); return new Promise(resolve => finish = resolve); },
        onStatus: () => seen++, onError: () => seen++ });
    stop(); finish(response('READY')); await flush(); assert.equal(seen, 0); assert.equal(aborted, true);
    passed++; console.log('PASS changing record cancels observation and ignores late response');
}
{
    let reads = 0; let error = '';
    watchSourceTask({ initial, ...expected, read: async () => { reads++; throw Error('failure'); }, onStatus: () => assert.fail(), onError: e => error = e });
    await flush(); assert.equal(reads, 1); assert.match(error, /未自动重试/);
    passed++; console.log('PASS read failure does not restart source or poll forever');
}
{
    const queue = []; let now = 0; let error = ''; let reads = 0;
    watchSourceTask({ initial, ...expected, clock: () => now, budget: 10, read: async () => { reads++; return response('RUNNING'); },
        onStatus: () => undefined, onError: e => error = e, schedule: callback => queue.push(callback) });
    await flush(); now = 11; queue.shift()(); await flush(); assert.equal(reads, 1); assert.match(error, /观察上限/);
    passed++; console.log('PASS observation budget does not cancel source work');
}
console.log(JSON.stringify({ passed, actualNetwork: 0, scientificExecutions: 0, modelCalls: 0 }));
