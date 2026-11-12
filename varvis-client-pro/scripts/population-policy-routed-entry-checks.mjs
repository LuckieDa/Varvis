// Read-only public adapter of captured Java policy-routed output. Underlying
// sources/policies/minireferences are SYNTHETIC, no new Java/resource execution.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import moduleUrl from './frontend-module-url.cjs';
const nativeRoot=process.env.VARVIS_NATIVE_ROOT;
if(!nativeRoot)throw Error('Set VARVIS_NATIVE_ROOT to existing main resources.');
const source=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js',import.meta.url));
const h=await import(moduleUrl('data:text/javascript;base64,'+source.toString('base64')));
const base=path.join(nativeRoot,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a10/round3/public-samples-02');
const id='ACMG_AMP_2015:BS1';
const read=(run,candidate,index)=>h.evidenceCards(candidate,['ACMG_AMP_2015:BS1'],'candidate',String(index),run).find(card=>card.id===id)?.entries[0];
let count=0,rows=0,completed=0,ne=0;const all=[];
const check=(name,fn)=>{fn();count++;console.log('PASS '+name);};
for(const name of fs.readdirSync(base).sort()){
    const scenario=path.join(base,name),file=path.join(scenario,fs.existsSync(path.join(scenario,'1-public.json'))?'1-public.json':'public.json');if(!fs.existsSync(file))continue;
    const bytes=fs.readFileSync(file),run=JSON.parse(bytes),before=JSON.stringify(run);all.push(run);
    check(`captured ${name} profile accepts exact POLICY_SELECTED bindings`,()=>assert.equal(h.localBatchScopeIssue(run),null));
    for(const candidate of run.candidates)for(const [index,attempt]of candidate.attempts.entries()){
        if(!attempt.bs1)continue;
        const value=attempt.bs1,entry=read(run,candidate,index),status=value.evaluation.evaluationResult.status;
        check(`captured ${name}/${index} BS1 ${value.dataScope?.dataset||'not selected'} ${status}`,()=>{
            assert.equal(entry.displayIssue,undefined);assert.equal(entry.evaluation.evaluationResult.status,status);
            assert.equal(entry.evaluation.appliedStrength,value.evaluation.appliedStrength);
            assert.equal(entry.hypothesisId,attempt.hypothesisId);rows++;
            if(['MET','NOT_MET'].includes(status)){completed++;assert.ok(entry.details.some(([label])=>label==='政策选择的来源'));}
            else {ne++;assert.equal(status,'NOT_EVALUATED');}
        });
    }
    check(`captured ${name} full public document and bytes immutable`,()=>{assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(file),bytes);});
    console.log('FROZEN '+name+' SHA256 '+createHash('sha256').update(bytes).digest('hex'));
}
assert.equal(all.length,11);assert.equal(rows,17);assert.equal(completed,13);assert.equal(ne,4);
const routed=all.find(run=>run.candidates.some(candidate=>candidate.attempts.some(attempt=>attempt.bs1?.dataScope?.dataset==='JOINT'&&attempt.bs1?.evaluation?.evaluationResult?.status==='MET')));
const mutations=[
    ['outer and nested population runtime identity mismatch',(run,_v)=>{run.executionProfile.bs1RuntimeBinding.sourceRouting.bindingSha256='f'.repeat(64);}],
    ['public policy-selected data scope relabel',(run,_v)=>{run.executionProfile.populationDataScope.dataset='EXOMES';}],
    ['row JOINT relabelled EXOMES',(run,v)=>v.dataScope.dataset='EXOMES'],
    ['unbound source configuration',(run,v)=>v.sourceRouting.effectiveSource.configurationSha256='f'.repeat(64)],
    ['wrong selected background',(run,v,attempt)=>attempt.bs1Context.disease.mondoId='MONDO:0999999'],
];
for(const [name,mutate]of mutations)check('synthetic public mutation rejects '+name+' without false missing-case facts',()=>{
    const run=structuredClone(routed),candidate=run.candidates.find(c=>c.attempts.some(a=>a.bs1?.dataScope?.dataset==='JOINT'&&a.bs1?.evaluation?.evaluationResult?.status==='MET'));
    const index=candidate.attempts.findIndex(a=>a.bs1?.dataScope?.dataset==='JOINT'&&a.bs1?.evaluation?.evaluationResult?.status==='MET'),attempt=candidate.attempts[index];
    mutate(run,attempt.bs1,attempt);
    const issue=h.localBatchScopeIssue(run),entry=read(run,candidate,index);
    assert.ok(issue || entry?.displayIssue);assert.ok(!entry?.evaluation || entry.displayIssue);
});
console.log(`${count} population public-reader checks passed; 11 captured Java public files/17 rows (13 completed,4 NOT_EVALUATED), source data remains synthetic.`);
