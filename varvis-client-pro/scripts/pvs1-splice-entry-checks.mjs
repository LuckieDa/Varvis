// Read-only projection of captured Java public results. These Java runs used
// SYNTHETIC source policies/minireferences; they are not clinical or VEP proof.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import frontendModuleUrl from './frontend-module-url.cjs';

const nativeRoot=process.env.VARVIS_NATIVE_ROOT;
if(!nativeRoot)throw Error('Set VARVIS_NATIVE_ROOT to the existing main checkout; captured artifacts are read-only.');
const source=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js',import.meta.url),'utf8');
const h=await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`));
const root=path.join(nativeRoot,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a2-a4/round3/business-final-01/business');
const id='ACMG_AMP_2015:PVS1';
const criterion=attempt=>attempt.localBatch.criteria.find(row=>row.criterionId===id);
const entry=(run,candidate)=>h.evidenceCards(candidate,run.enabledCriteria,'candidate','0',run).find(card=>card.code==='PVS1').entries[0];
let count=0,actualRows=0;
const check=(name,action)=>{action();count++;console.log('PASS '+name);};
const documents=new Map();
for(const [number,mode]of [[1,'INFRAME'],[2,'NEGATIVE'],[3,'TOPOLOGY'],[4,'COMPOSED']]){
    const scenario=`newSourceQualifiedSpliceMethodsReachPvs1OnlyBatchAndDurableHistory-_${number}__mode_${mode}`;
    const filename=path.join(root,scenario,'round3-all.json'),bytes=fs.readFileSync(filename),run=JSON.parse(bytes),before=JSON.stringify(run);
    documents.set(mode,run);
    check(`captured cf2e8e61 ${mode}: normal profile accepts its actual public runtime binding`,()=>assert.equal(h.localBatchScopeIssue(run),null));
    for(const candidate of run.candidates){
        const row=criterion(candidate.attempts[0]),facts=row.details.pvs1Facts,expectedStatus=mode==='NEGATIVE'?'NOT_MET':'MET';
        const expectedStrength=mode==='NEGATIVE'?'NONE':mode==='INFRAME'?'MODERATE':'VERY_STRONG';
        check(`captured cf2e8e61 ${mode} ${facts.selectedTranscript}: independent status/cap and complete public detail projection`,()=>{
            const card=entry(run,candidate);
            assert.equal(card.displayIssue,false);assert.equal(card.evaluation.evaluationResult.status,expectedStatus);
            assert.equal(card.evaluation.appliedStrength,expectedStrength);assert.equal(row.status,expectedStatus);assert.equal(row.appliedStrength,expectedStrength);
            assert.ok(card.details.some(([label])=>label==='PVS1序列方法'));
            for(const label of ['原始转录本拓扑','改变后转录本拓扑','模型／上下文／CDS绑定','编码与剪接编辑审计'])assert.ok(card.details.some(([key])=>key===label),label);
            if(mode==='INFRAME'||mode==='NEGATIVE')assert.ok(card.details.some(([key])=>key==='在框模块结论'));
            if(mode==='TOPOLOGY'||mode==='COMPOSED')assert.ok(card.details.some(([label])=>label==='改变后PTC与连接点距离'));
            assert.equal(facts.codingEditComposition.referenceCdsLength,600);
            assert.equal(facts.codingEditComposition.alteredCdsLength,mode==='INFRAME'||mode==='NEGATIVE'?588:587);
            actualRows++;
        });
    }
    check(`captured ${mode}: public projection changes neither document nor archived bytes`,()=>{
        assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(filename),bytes);
    });
    console.log(`FROZEN public ${mode} SHA256 ${createHash('sha256').update(bytes).digest('hex')}`);
}
assert.equal(actualRows,8);

// The controls below deliberately mutate copies of one captured result. They
// exercise public rejection logic, not new backend executions/source adoption.
const mutations=[
    ['missing method',(run,a,f)=>delete f.method],
    ['unknown method',(run,a,f)=>f.method='UNRECOGNIZED_METHOD'],
    ['unsupported method major',(run,a,f)=>f.methodVersion='2.0.0'],
    ['missing context',(run,a)=>delete a.context],
    ['wrong context transcript',(run,a)=>a.context.transcript.version++],
    ['wrong CDS context binding',(run,a,f)=>f.codingEditComposition.binding.contextSha256='f'.repeat(64)],
    ['wrong CDS source digest',(run,a,f)=>f.codingEditComposition.binding.referenceCdsSha256='wrong'],
    ['missing source qualification',(run,a,f)=>delete f.sourceQualifiedResearch],
    ['wrong material model equivalence',(run,a,f)=>f.materialSupply.policyModelUnchanged=false],
    ['strength beyond source cap',(run,a,f)=>f.spliceAssessment.maximumStrength='SUPPORTING'],
    ['adopted strength mismatch',(run,a,f)=>f.spliceAssessment.adoptedStrength='STRONG'],
    ['saved strength mismatch',(run,a,f,row)=>row.appliedStrength='VERY_STRONG'],
    ['bounded topology totals inconsistent',(run,a,f)=>f.spliceAssessment.retainedExonsTotal++],
    ['new facts cannot use the legacy selected runtime',(run)=>{for(const binding of Object.values(run.executionProfile.moduleBindings))if(binding.runtimeBinding?.pvs1SelectedVersion)binding.runtimeBinding.pvs1SelectedVersion='1.1.0';}],
];
for(const [name,mutate]of mutations)check('synthetic public mutation rejects '+name,()=>{
    const run=structuredClone(documents.get('INFRAME')),candidate=run.candidates[0],attempt=candidate.attempts[0],row=criterion(attempt);
    mutate(run,attempt,row.details.pvs1Facts,row);
    const card=entry(run,candidate);
    assert.equal(card.displayIssue,true);assert.equal(card.evaluation,undefined);
    assert.ok(card.details.some(([label,value])=>label==='原保存状态与强度（未采用）'&&value===`${row.status} / ${row.appliedStrength}`));
});
check('synthetic escape strength keeps the saved raw value and explicitly reports public rule-proof dependency',()=>{
    const run=structuredClone(documents.get('COMPOSED')),candidate=run.candidates[0],row=criterion(candidate.attempts[0]),facts=row.details.pvs1Facts;
    facts.nmdPrediction='NMD_NOT_PREDICTED';facts.escapeAssessment={schemaVersion:'pvs1-nmd-escape-assessment-v1',status:'MET'};
    const card=entry(run,candidate);
    assert.equal(card.displayIssue,true);assert.equal(card.evaluation,undefined);assert.equal(card.displayOutcome.kind,'UNSUPPORTED');
    assert.ok(card.summary.includes('公共摘要')&&card.summary.includes('不将其改判为病例资料不足'));
    assert.ok(card.details.some(([label])=>label==='逃逸强度展示核验依赖'));
    assert.ok(card.details.some(([label,value])=>label==='原保存状态与强度（未采用）'&&value==='MET / VERY_STRONG'));
});
console.log(`${count} PVS1 public adapter checks passed: eight captured Java rows plus synthetic mutations; no backend, page, or download execution.`);
