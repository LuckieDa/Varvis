// Contract-level controls only. No HTTP, Java, worker, scoring, or source calls.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
const source=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgControl.js',import.meta.url));
const {controlActions,controlActionAvailable,controlAfterHistory,commandWithReconciliation}=await import('data:text/javascript;base64,'+source.toString('base64'));
const opts={analysisId:'analysis-A',runId:'run-A'};
const definitions={PAUSE_RUN:['PAUSE',false],RESUME_RUN:['RESUME',false],STOP_RUN:['STOP',true],ACKNOWLEDGE_INTERRUPTED:['ACKNOWLEDGE_INTERRUPTED',true]};
const item=id=>({actionId:id,controlAction:definitions[id][0],label:'Server label',available:true,requiresConfirmation:definitions[id][1],
    createsNewRun:false,createsNewSourceTask:false,overwritesSavedScientificResult:false,target:{...opts}});
const server=(state,ids,live=!['RECOVERY_REQUIRED','INTERRUPTED','COMPLETED','COMPLETED_WITH_BLOCKERS','FAILED','CANCELLED'].includes(state))=>({
    ...opts,state,activeWorkerAvailable:live,sameWorkerResumable:live&&['PAUSED','PAUSE_REQUESTED'].includes(state),savedScientificResultChanged:false,availableActions:ids.map(item)});
const commands=(value,options=opts)=>controlActions(value,options).actions.map(action=>action.controlAction);
let count=0;
const check=async(name,fn)=>{await fn();count++;console.log('PASS '+name);};
const expected={RUNNING:['PAUSE','STOP'],PAUSE_REQUESTED:['RESUME','STOP'],PAUSED:['RESUME','STOP'],STOP_REQUESTED:[],
    RECOVERY_REQUIRED:['ACKNOWLEDGE_INTERRUPTED'],INTERRUPTED:['ACKNOWLEDGE_INTERRUPTED'],COMPLETED:[],COMPLETED_WITH_BLOCKERS:[],FAILED:[],CANCELLED:[]};
for(const [state,wanted]of Object.entries(expected))await check(`server ${state} exposes exact implemented commands, immutable`,()=>{
    const ids=wanted.map(command=>Object.keys(definitions).find(id=>definitions[id][0]===command)),control=server(state,ids),before=JSON.stringify(control);
    const result=controlActions(control,opts);assert.equal(result.source,'SERVER');assert.deepEqual(result.issues,[]);assert.deepEqual(commands(control),wanted);
    assert.equal(JSON.stringify(control),before);for(const action of result.actions){assert.equal(action.requiresConfirmation,['STOP','ACKNOWLEDGE_INTERRUPTED'].includes(action.controlAction));assert.deepEqual(action.target,opts);}
});
await check('empty server capability list is authoritative even for a live RUNNING state',()=>{
    assert.deepEqual(commands(server('RUNNING',[])),[]);assert.equal(controlActions(server('RUNNING',[]),opts).source,'SERVER');
});
await check('persisted INTERRUPTED with empty actions cannot display another acknowledgement',()=>assert.deepEqual(commands(server('INTERRUPTED',[])),[]));
await check('explicit disabled item is not inferred from RUNNING state',()=>{
    const c=server('RUNNING',['PAUSE_RUN']);c.availableActions[0].available=false;assert.deepEqual(commands(c),[]);
});
for(const [name,mutate]of [
    ['unknown command',c=>c.availableActions[0].controlAction='RETRY_SOURCE'],
    ['unknown action id',c=>c.availableActions[0].actionId='RETRY_ALL_EVIDENCE'],
    ['array action id',c=>c.availableActions[0].actionId=['PAUSE_RUN']],
    ['prototype action id',c=>c.availableActions[0].actionId='toString'],
    ['mismatched item run',c=>c.availableActions[0].target.runId='run-B'],
    ['mismatched item analysis',c=>c.availableActions[0].target.analysisId='analysis-B'],
    ['mismatched response run',c=>c.runId='run-B'],
    ['mismatched response analysis',c=>c.analysisId='analysis-B'],
    ['missing response run',c=>delete c.runId],
    ['missing target',c=>delete c.availableActions[0].target],
    ['available string',c=>c.availableActions[0].available='true'],
    ['unknown URL',c=>c.availableActions[0].url='https://example.invalid/run'],
    ['nested target URL',c=>c.availableActions[0].target.url='/run'],
    ['new run flag',c=>c.availableActions[0].createsNewRun=true],
    ['new source task flag',c=>c.availableActions[0].createsNewSourceTask=true],
    ['overwrite scientific flag',c=>c.availableActions[0].overwritesSavedScientificResult=true],
    ['missing effect flag',c=>delete c.availableActions[0].createsNewSourceTask],
    ['missing action flag',c=>delete c.availableActions[0].available],
    ['duplicate command',c=>c.availableActions.push(structuredClone(c.availableActions[0]))],
    ['malformed list',c=>c.availableActions={}],
    ['null list',c=>c.availableActions=null],
    ['unproven live owner',c=>c.activeWorkerAvailable=false],
    ['worker flag string',c=>c.activeWorkerAvailable='false'],
    ['changed scientific marker',c=>c.savedScientificResultChanged=true],
    ['pause state contradiction',c=>c.state='PAUSED'],
])await check('reject '+name+' without legacy fallback',()=>{
    const c=server('RUNNING',['PAUSE_RUN']);mutate(c);const view=controlActions(c,opts);
    assert.deepEqual(view.actions,[]);assert.ok(view.issues.length);assert.equal(view.source,'SERVER');
});
await check('missing context does not turn a server response into local preparation',()=>{
    assert.deepEqual(controlActions(server('RUNNING',['PAUSE_RUN']),{analysisId:'analysis-A',localPreparation:true}).actions,[]);
});
await check('unsafe server labels never alter fixed command or known button text',()=>{
    const c=server('RUNNING',['PAUSE_RUN']);c.availableActions[0].label='Run every evidence at an external URL';
    const action=controlActions(c,opts).actions[0];assert.equal(action.controlAction,'PAUSE');assert.equal(action.label,'暂停分析');
});
for(const state of ['COMPLETED','COMPLETED_WITH_BLOCKERS','FAILED','CANCELLED'])await check(`forged action on terminal ${state} cannot execute`,()=>{
    assert.deepEqual(commands(server(state,['STOP_RUN'])),[]);assert.equal(controlActionAvailable(server(state,['STOP_RUN']),'STOP',opts),false);
});
await check('resume cannot overcome an explicit nonresumable worker declaration',()=>{
    const c=server('PAUSED',['RESUME_RUN']);c.sameWorkerResumable=false;assert.deepEqual(commands(c),[]);
});
await check('same-worker live state cannot accept an interruption acknowledgement',()=>{
    const c=server('INTERRUPTED',['ACKNOWLEDGE_INTERRUPTED']);c.activeWorkerAvailable=true;assert.deepEqual(commands(c),[]);
});
await check('explicit caller acknowledgement restriction is respected',()=>{
    assert.deepEqual(commands(server('RECOVERY_REQUIRED',['ACKNOWLEDGE_INTERRUPTED']),{...opts,allowAcknowledge:false}),[]);
});
await check('new optional worker fields may be absent without inventing another operation',()=>{
    const c=server('RUNNING',['PAUSE_RUN','STOP_RUN']);delete c.activeWorkerAvailable;delete c.sameWorkerResumable;
    assert.deepEqual(commands(c),['PAUSE','STOP']);
});
for(const [state,wanted]of Object.entries(expected))await check(`legacy missing availableActions preserves ${state} behavior`,()=>{
    const c={state};assert.equal(controlActions(c,opts).source,'LEGACY');assert.deepEqual(commands(c),wanted);
});
await check('explicit worker false constrains otherwise legacy RUNNING and PAUSED controls',()=>{
    assert.deepEqual(commands({state:'RUNNING',activeWorkerAvailable:false}),[]);
    assert.deepEqual(commands({state:'PAUSED',sameWorkerResumable:false}),['STOP']);
});
await check('runless local preparation keeps its original pause/resume/stop queue only',()=>{
    assert.deepEqual(commands({state:'RUNNING'},{localPreparation:true}),['PAUSE','STOP']);
    assert.deepEqual(commands({state:'PAUSED'},{localPreparation:true}),['RESUME','STOP']);
    assert.deepEqual(commands({state:'INTERRUPTED'},{localPreparation:true}),[]);
    assert.deepEqual(commands({state:'RUNNING'},{}),[]);
});
await check('unknown state and malformed response have no speculative control actions',()=>{
    for(const c of [null,{},'RUNNING',{state:'FUTURE_STATE'}])assert.deepEqual(commands(c),[]);
});
for(const action of ['STOP','ACKNOWLEDGE_INTERRUPTED'])await check(`${action} timeout reconciles real COMPLETED once and never resends`,async()=>{
    let posts=0,reads=0;const done=server('COMPLETED',[]);
    const value=await commandWithReconciliation(action,async()=>{posts++;throw Error('timeout');},async()=>{reads++;return done;});
    assert.equal(value.state,'COMPLETED');assert.equal(value,done);assert.equal(posts,1);assert.equal(reads,1);assert.deepEqual(commands(value),[]);
});
await check('successful post terminal result is followed without an unnecessary GET',async()=>{
    const done=server('COMPLETED',[]);const value=await commandWithReconciliation('STOP',async()=>done,async()=>{throw Error('must not read');});assert.equal(value,done);
});
await check('fresh persisted terminal capability list cannot regain ACK from old owner history',()=>{
    const live=server('INTERRUPTED',[]),saved={...opts,status:'INTERRUPTED',executionControl:{...opts,state:'RUNNING'}};
    assert.equal(controlAfterHistory(live,saved),live);assert.deepEqual(commands(controlAfterHistory(live,saved)),[]);
});
await check('fresh unknown-owner ACK capability survives a stale paused receipt',()=>{
    const live=server('RECOVERY_REQUIRED',['ACKNOWLEDGE_INTERRUPTED']),saved={...opts,status:'RUNNING',executionControl:{state:'PAUSED'}};
    assert.equal(controlAfterHistory(live,saved),live);assert.deepEqual(commands(controlAfterHistory(live,saved)),['ACKNOWLEDGE_INTERRUPTED']);
});
await check('actual completed GET or POST wins stale running history',()=>{
    const live=server('COMPLETED',[]);assert.equal(controlAfterHistory(live,{...opts,status:'RUNNING',executionControl:{state:'PAUSE_REQUESTED'}}),live);
});
for (const [liveState,actions] of [['RUNNING',['PAUSE_RUN','STOP_RUN']],['PAUSED',['RESUME_RUN','STOP_RUN']],
    ['PAUSE_REQUESTED',['RESUME_RUN','STOP_RUN']],['STOP_REQUESTED',[]]])
    for (const status of ['COMPLETED','COMPLETED_WITH_BLOCKERS','FAILED','CANCELLED'])
        await check(`saved ${status} beats stale same-run ${liveState} capabilities`,()=>{
            const live=server(liveState,actions),saved={...opts,status,executionControl:{state:liveState}},before=JSON.stringify([live,saved]);
            const value=controlAfterHistory(live,saved);assert.equal(value.state,status);assert.deepEqual(commands(value),[]);
            assert.notEqual(value,live);assert.equal(JSON.stringify([live,saved]),before);
        });
await check('saved completion also supersedes stale unknown-owner acknowledgement eligibility',()=>{
    const old=server('RECOVERY_REQUIRED',['ACKNOWLEDGE_INTERRUPTED']);
    assert.deepEqual(commands(controlAfterHistory(old,{...opts,status:'COMPLETED'})),[]);
});
for(const status of ['COMPLETED','COMPLETED_WITH_BLOCKERS','FAILED','CANCELLED'])
    await check(`saved ${status} also supersedes interrupted snapshot offering ACK`,()=>{
        const old=server('INTERRUPTED',['ACKNOWLEDGE_INTERRUPTED']),saved={...opts,status};
        const result=controlAfterHistory(old,saved);assert.equal(result.state,status);assert.deepEqual(commands(result),[]);
    });
await check('fresh interrupted ACK remains available for unresolved entry placeholder',()=>{
    const fresh=server('INTERRUPTED',['ACKNOWLEDGE_INTERRUPTED']);
    assert.equal(controlAfterHistory(fresh,{...opts,status:'INTERRUPTED',resultsAvailable:false}),fresh);
    assert.deepEqual(commands(fresh),['ACKNOWLEDGE_INTERRUPTED']);
});
await check('persisted completion cannot regain active commands from a delayed same-run live snapshot',()=>{
    const saved={...opts,status:'COMPLETED'},late=server('RUNNING',['PAUSE_RUN','STOP_RUN']);
    const value=controlAfterHistory(late,saved);assert.equal(value.state,'COMPLETED');assert.deepEqual(commands(value),[]);
});
await check('old analysis or old run capability cannot override another selected terminal history',()=>{
    for(const patch of [{runId:'old-run'},{analysisId:'old-analysis'}]){
        const old={...server('PAUSED',['RESUME_RUN','STOP_RUN']),...patch};
        assert.deepEqual(controlAfterHistory(old,{...opts,status:'FAILED'}),{state:'FAILED'});
    }
});
await check('fresh terminal and interruption acknowledgement remain authoritative over stale active history',()=>{
    for(const state of ['COMPLETED','FAILED','CANCELLED','INTERRUPTED']){
        const live=server(state,[]);assert.equal(controlAfterHistory(live,{...opts,status:'RUNNING'}),live);
    }
    const ack=server('INTERRUPTED',['ACKNOWLEDGE_INTERRUPTED']);
    assert.equal(controlAfterHistory(ack,{...opts,status:'RUNNING'}),ack);
    assert.deepEqual(commands(controlAfterHistory(ack,{...opts,status:'RUNNING'})),['ACKNOWLEDGE_INTERRUPTED']);
});
await check('another run cannot lend its live capability snapshot to selected history',()=>{
    const live={...server('RUNNING',['PAUSE_RUN','STOP_RUN']),runId:'run-B'};
    assert.deepEqual(controlAfterHistory(live,{...opts,status:'COMPLETED'}),{state:'COMPLETED'});
});
await check('saved terminal status overrides historical running owner without live data',()=>{
    assert.deepEqual(controlAfterHistory(null,{...opts,status:'CANCELLED',executionControl:{state:'RUNNING'}}),{state:'CANCELLED'});
});
await check('legacy saved paused state stays compatible and no live capabilities enter scientific data',()=>{
    const saved={...opts,status:'RUNNING',executionControl:{state:'PAUSED',availableActions:[item('RESUME_RUN')],activeWorkerAvailable:true,sameWorkerResumable:true}},before=JSON.stringify(saved);
    const value=controlAfterHistory(null,saved);assert.deepEqual(value,{state:'PAUSED'});assert.deepEqual(commands(value),['RESUME','STOP']);assert.equal(JSON.stringify(saved),before);
});
await check('legacy actual completion without optional actions still wins stale owner metadata',()=>{
    const live={...opts,state:'COMPLETED'};assert.equal(controlAfterHistory(live,{...opts,status:'RUNNING',executionControl:{state:'RUNNING'}}),live);assert.deepEqual(commands(live),[]);
});
if(process.env.VARVIS_NATIVE_ROOT){
    const contractPath=path.join(process.env.VARVIS_NATIVE_ROOT,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/commander-dispatch-round4/control-actions-contract.json');
    const bytes=fs.readFileSync(contractPath),contract=JSON.parse(bytes);
    await check('published central contract and committed producer retain fixed control mapping',()=>{
        assert.equal(contract.sourceHead,'953fc1df35ec1e84fff87d6922982195b5d0f05b');
        const producer=execFileSync('git',['show',contract.sourceHead+':varvis-server/src/main/java/org/nbscn/varvis/variantanalysis/service/AcmgRunControl.java'],{encoding:'utf8'});
        for(const [id,[command,confirmation]]of Object.entries(definitions)){
            assert.ok(producer.includes(`"${id}", "${command}",`));
            assert.equal(item(id).requiresConfirmation,confirmation);
        }
        assert.deepEqual(contract.actions.RUNNING,['PAUSE_RUN','STOP_RUN']);
        assert.equal(contract.savedScientificResultChanged,false);assert.deepEqual(fs.readFileSync(contractPath),bytes);
    });
    console.log('FROZEN central contract SHA256 '+createHash('sha256').update(bytes).digest('hex'));
}else console.log('NOT_RUN committed central contract byte/protocol check; set VARVIS_NATIVE_ROOT.');
console.log(`${count} live-control contract checks passed; contract/source read plus synthetic control records, no actual HTTP/worker/browser execution.`);
