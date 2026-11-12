import assert from 'node:assert/strict';
import fs from 'node:fs';
const load=async name=>import(`data:text/javascript;base64,${Buffer.from(fs.readFileSync(new URL(`../src/pages/varvis/ChromosomeVisualization/utils/${name}.js`,import.meta.url),'utf8')).toString('base64')}`);
const {createPreparationControl,activeControl,commandWithReconciliation}=await load('acmgControl');
const {prepareAutomaticLiterature}=await load('automaticAcmg');
const states=[];const gate=createPreparationControl(s=>states.push(s));
gate.command('PAUSE');let passed=false;const pending=gate.checkpoint().then(()=>{passed=true;});
await Promise.resolve();assert.equal(gate.state(),'PAUSED');assert.equal(passed,false);
gate.command('RESUME');await pending;assert.equal(passed,true);
gate.command('STOP');await assert.rejects(gate.checkpoint(),/ACMG_USER_STOPPED/);
assert.deepEqual(states,['PAUSE_REQUESTED','PAUSED','RUNNING','CANCELLED']);
assert.equal(activeControl('RECOVERY_REQUIRED'),false);assert.equal(activeControl('INTERRUPTED'),false);assert.equal(activeControl('PAUSED'),true);
const before=createPreparationControl();before.command('STOP');let submissions=0;
await assert.rejects(prepareAutomaticLiterature({analysisId:'a',topN:1,declaration:{hypotheses:[]},progress:()=>{},controlPoint:()=>before.checkpoint(),
 get:async()=>{throw new Error('must not fetch');},post:async()=>{submissions++;}}),/ACMG_USER_STOPPED/);assert.equal(submissions,0);
const after=createPreparationControl();let polls=0,cancels=0;
await assert.rejects(prepareAutomaticLiterature({analysisId:'a',topN:1,declaration:{hypotheses:[]},progress:()=>{},wait:async()=>{},controlPoint:()=>after.checkpoint(),
 get:async url=>url.endsWith('/options')?{enabled:true,selectionSha256:'a'.repeat(64)}:(polls++,{taskId:'b'.repeat(64),status:'CANCELLED'}),
 post:async url=>{
     if(url.endsWith('/local-review')) return {schemaVersion:'denovo-local-discovery-plan-v1',candidateIds:['test-candidate']};
     submissions++;
     return {taskId:'b'.repeat(64),status:'RUNNING'};
 },
 onTask:async task=>{if(task?.status==='RUNNING'){after.command('STOP');cancels++;}}
}),/ACMG_USER_STOPPED/);
assert.equal(polls,1);assert.equal(cancels,1);assert.equal(submissions,1,'no next evidence task or final evaluation after stopping');
for (const state of ['PAUSE_REQUESTED','PAUSED','COMPLETED_WITH_BLOCKERS']) {
    let commands=0, reads=0;
    const value=await commandWithReconciliation('PAUSE',async()=>{commands++;throw new Error('timeout');},async()=>{reads++;return {state};});
    assert.equal(value.state,state);assert.equal(commands,1);assert.equal(reads,1);
}
await assert.rejects(commandWithReconciliation('PAUSE',async()=>{throw new Error('timeout');},async()=>({state:'RUNNING'})),/timeout/);
await assert.rejects(commandWithReconciliation('PAUSE',async()=>{throw new Error('timeout');},async()=>{throw new Error('offline');}),/timeout/);
const direct=await commandWithReconciliation('PAUSE',async()=>({state:'PAUSED'}),async()=>{throw new Error('unnecessary read');});
assert.equal(direct.state,'PAUSED');
console.log('PASS late command reconciles completed/paused state without resend; unresolved/offline retained; acknowledged command needs no extra read.');
console.log('PASS preparation pause/resume; stop-before-submit; active-task cancellation acknowledgement; no next task; interrupted/unknown not active.');
