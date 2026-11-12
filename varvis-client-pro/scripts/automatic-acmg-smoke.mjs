import assert from 'node:assert/strict';
import fs from 'node:fs';
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/automaticAcmg.js', import.meta.url), 'utf8');
const { prepareAutomaticLiterature } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const prepare = config => prepareAutomaticLiterature({ ...config, post: async (url, ...args) => {
    if (url.endsWith('/pp1/local-review')) throw { response: { status: 404 } };
    return config.post(url, ...args);
} });
const { literaturePollDelay } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
assert.deepEqual([0,1,2,3,4,50].map(literaturePollDelay), [250,500,1000,2000,5000,5000]);
const id = 'a'.repeat(64);
const calls = [];
const base = { analysisId: 'job', topN: 2, declaration: { selectedCriteria:['ACMG_AMP_2015:PP1'], hypotheses: [{ hypothesis: { candidateId: 'a', hypothesisId: 'h', moi: 'AR' } }] },
    progress: () => {}, wait: async () => {},
    get: async () => ({ enabled: false }),
    post: async (url, body) => { calls.push({ url, body }); return { taskId: id, status: 'READY' }; } };
const batchCalls = [];
await prepareAutomaticLiterature({ ...base, declaration: { ...base.declaration, hypotheses: [
    ...base.declaration.hypotheses, { hypothesis: { candidateId: 'b', hypothesisId: 'i' } },
] }, post: async (url, body) => {
    batchCalls.push({ url, body });
    return url.endsWith('/local-review')
        ? { schemaVersion:'pp1-local-review-plan-v1', planId:id, attempts:[{ candidateId:'a', hypothesisId:'h' }] }
        : { taskId:id, status:'READY' };
} });
assert.equal(batchCalls.length, 2);
assert.ok(batchCalls[0].url.endsWith('/pp1/local-review'));
assert.ok(batchCalls[1].url.endsWith('/pp1/tasks'));
assert.equal(batchCalls[1].body.batchPlanId, id);
assert.equal(batchCalls[1].body.candidateId, 'a');
await prepare(base);
assert.equal(calls.length, 1); assert.ok(calls[0].url.endsWith('/pp1/tasks'));
assert.ok(calls.every(c => !Object.hasOwn(c.body, 'allowModel') && !Object.hasOwn(c.body, 'allowPublicNetwork')));
const backgroundCalls = [];
const backgroundNotes = await prepare({...base, candidateLabels: { a: 'GENE' }, declaration:{selectedCriteria:['ACMG_AMP_2015:PP1'],hypotheses:[
    {hypothesis:{candidateId:'a',hypothesisId:'disease-one'}},
    {hypothesis:{candidateId:'a',hypothesisId:'disease-two'}},
]}, get:async()=>({enabled:false}),
    post: async (url, body) => {
        backgroundCalls.push({ url, body });
        return { taskId: id, status: 'READY', result: {
            stopReason: 'PP1_SOURCE_BATCH_FINISHED',
            papers: [{ status: 'PARTIAL', stopReason: 'DENOVO_MODEL_TIMEOUT' }],
            searchAliasCoverage: { omittedAliases: 5 },
        } };
    } });
assert.deepEqual(backgroundCalls.map(c=>[c.body.candidateId,c.body.hypothesisId]),[['a','disease-one'],['a','disease-two']]);
assert.ok(backgroundCalls.every(c=>!Object.hasOwn(c.body,'allowModel')&&!Object.hasOwn(c.body,'allowPublicNetwork')));
assert.deepEqual(backgroundNotes, [{
    label: 'PP1 · GENE', status: '待核查', detail: '资料提取超时 · 5 个检索名称未使用', attention: true, count: 2,
}]);
console.log('Automatic ACMG: two disease backgrounds of the same variant prepare independently without changing permissions.');
let posts = 0;
await assert.rejects(prepare({ ...base, post: async () => { posts++; throw new Error('network unknown'); } }), /network unknown/);
assert.equal(posts, 1, 'ambiguous transport must never retry or proceed');
const notes = await prepare({ ...base, post: async () => ({ taskId: id, status: 'FAILED', reasonCode: 'PP1_MODEL_DISABLED_CACHE_MISSING' }) });
assert.ok(notes.some(note => note.status === '准备失败' && note.detail === '暂无可用资料缓存'));
let reads = 0;
const pollingWaits=[];
await prepare({ ...base, get: async url => { if (url.includes('/tasks/')) { reads++; return { taskId: id, status: 'READY' }; } return { enabled: false }; },
    wait:async ms=>pollingWaits.push(ms),
    post: async () => ({ taskId: id, status: 'RUNNING' }) });
assert.equal(reads, 1);
assert.deepEqual(pollingWaits,[250], 'cached completion no longer pays a fixed five-second wait');
await assert.rejects(prepare({ ...base, active: () => false }), /页面任务已切换/);
await assert.rejects(prepare({ ...base, post: async () => ({ status: 'READY' }) }), /回执无效/);
const ps2 = { ...base, declaration: { selectedCriteria: ['ACMG_AMP_2015:PS2'], hypotheses: [] } };
const plan = candidateIds => ({ schemaVersion: 'denovo-local-discovery-plan-v1', candidateIds });
const disabled = await prepare({ ...ps2, post: async url => {
    assert.ok(url.endsWith('/local-review')); return plan(['a']);
} });
assert.ok(disabled.some(note => note.label === 'PS2 / PM6' && note.status === '文献补查未启用'));
await prepare({ ...ps2, get: async () => { throw new Error('sufficient local data must skip discovery'); },
    post: async url => { assert.ok(url.endsWith('/local-review')); return plan([]); } });
let scopedTask;
await prepare({ ...ps2, get: async () => ({enabled:true,selectionSha256:id}),
    post: async (url,body) => {
        if (url.endsWith('/local-review')) return plan(['a']);
        scopedTask=body; return {taskId:id,status:'READY'};
    } });
assert.deepEqual(scopedTask.candidateIds,['a']);
const acceptedPins=[];
let optionReads=0, acceptedEvents=0;
await prepare({ ...ps2, onAccepted:()=>{acceptedEvents++;},
    get:async()=>({enabled:true,selectionSha256:(++optionReads===1?'a':'b').repeat(64),candidateAcceptanceEnabled:true}),
    post:async(url,body)=>{
        if(url.endsWith('/local-review')) return plan(['a']);
        if(url.endsWith('/tasks')) return {taskId:id,status:'READY',result:{papers:[
            {pmcid:'PMC111',candidateIntake:{sha256:id}},
            {pmcid:'PMC222',candidateIntake:{sha256:id}},
        ]}};
        acceptedPins.push(body.selectionSha256);
        return {status:'ACCEPTED_FOR_QUALIFICATION_NOT_SCORED',scientificExecution:false};
    } });
assert.deepEqual(acceptedPins,['a'.repeat(64),'b'.repeat(64)]);
assert.equal(acceptedEvents,2);
console.log('Automatic ACMG: 8 scenarios passed (order, permissions, ambiguous POST, known failure, polling, cancellation, invalid receipt, disabled sources).');

const memory = new Map();
const checkpoint = { read: key => memory.get(key), write: (key,value) => memory.set(key,value), clear: () => memory.clear() };
let actualPosts = 0, submittedBody;
const recoveryBase = { ...base, checkpoint, declaration: { selectedCriteria:['ACMG_AMP_2015:PP1'], hypotheses: [{ hypothesis: { candidateId: 'a', hypothesisId: 'h' } }] },
    get: async url => {
        if (url.includes('/submissions/')) {
            const sha = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(JSON.stringify(submittedBody)))))
                .map(x=>x.toString(16).padStart(2,'0')).join('');
            return { status:'ACKNOWLEDGED', lane:'pp1', requestSha256:sha, taskId:id };
        }
        if (url.includes('/tasks/')) return { taskId:id,status:'READY' };
        return { enabled:false };
    },
    post: async (url,body,headers) => { actualPosts++; submittedBody=body; assert.ok(headers['X-Varvis-Submission-Id']); throw new Error('response lost'); } };
await prepare(recoveryBase);
assert.equal(actualPosts,1,'lost response recovers without repeating POST');assert.equal(memory.size,0);
let blockedPosts=0;
const pending = { ...recoveryBase, get:async url=>url.includes('/submissions/')?{status:'NOT_FOUND'}:{enabled:false},
    post:async()=>{blockedPosts++;throw new Error('lost');} };
await assert.rejects(prepare(pending),/仍待核对/);
await assert.rejects(prepare(pending),/仍待核对/);
assert.equal(blockedPosts,1,'resuming an uncertain operation only reads its receipt');
const conflict = { ...recoveryBase, get:async url=>url.includes('/submissions/')?{status:'ACKNOWLEDGED',lane:'pm3',requestSha256:'bad',taskId:id}:{enabled:false} };
await assert.rejects(prepare(conflict),/输入不一致/);
console.log('Automatic ACMG: recovery, repeated resume and conflicting receipt scenarios passed.');
memory.clear();
await assert.rejects(prepare({...recoveryBase,get:async url=>url.includes('/tasks/')?{taskId:'b'.repeat(64),status:'READY'}:recoveryBase.get(url)}),/身份与提交回执不一致/);
console.log('Automatic ACMG: mismatched recovered task identity rejected.');

for (const modes of [['AD'], ['UNKNOWN'], ['XR'], ['AD', 'AR'], ['AR', 'AR']]) {
    const started = [];
    await prepare({ ...base, declaration: { selectedCriteria:['ACMG_AMP_2015:PP1'], hypotheses: modes.map((moi, i) => ({
        hypothesis: { candidateId: 'a', hypothesisId: `h${i}`, moi },
    })) }, post: async (url, body) => { started.push({url,body}); return {taskId:id,status:'READY'}; } });
    assert.equal(started.filter(c => c.url.endsWith('/pm3/tasks')).length, 0, 'PM3 preparation belongs to the backend evaluation');
    assert.equal(started.filter(c => c.url.endsWith('/pp1/tasks')).length, modes.length,
        'PM3 restriction must not remove independent PP1 disease hypotheses');
}
console.log('Automatic ACMG: PM3 no longer starts frontend source jobs; PP1 retains independent disease hypotheses.');
// Recovered legacy overflow must be retried, while real missing aliases stay local to one context.
let repairedPosts=0;
const oldCheckpoint={read:()=>({operationId:'old',blocked:'资料准备不可用：PP1_PUBLIC_SEARCH_ALIAS_UNAVAILABLE'}),write:()=>{},clear:()=>{}};
await prepare({...base,get:async()=>({enabled:false}),checkpoint:oldCheckpoint,
 post:async()=>{repairedPosts++;return {taskId:id,status:'READY',result:{searchAliasCoverage:{availableAliases:40,selectedAliases:20,omittedAliases:20}}};}});
assert.equal(repairedPosts,1,'obsolete rejection must not keep blocking fixed server preparation');
let contexts=0;
const missingNotes=await prepare({...base,get:async()=>({enabled:false}),declaration:{selectedCriteria:['ACMG_AMP_2015:PP1'],hypotheses:[{hypothesis:{candidateId:'a',hypothesisId:'one'}},{hypothesis:{candidateId:'b',hypothesisId:'two'}}]},
 post:async()=>{if(++contexts===1)throw {response:{status:409,data:{message:'PP1_PUBLIC_SEARCH_ALIAS_MISSING'}}};return {taskId:id,status:'READY'};}});
assert.equal(contexts, 2);
assert.ok(missingNotes.some(note => note.detail === '缺少可用的变异检索名称'));
console.log('PP1 alias overflow recovery and missing-alias isolation passed.');
