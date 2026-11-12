// Public frontend replay of committed Java escape proof. Captures use synthetic
// source policies/minireferences, never new clinical or Java execution here.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import moduleUrl from './frontend-module-url.cjs';
const root=process.env.VARVIS_NATIVE_ROOT;if(!root)throw Error('Set VARVIS_NATIVE_ROOT.');
const base=path.join(root,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a2-a4');
const contractBytes=fs.readFileSync(path.join(base,'round4/public-contract.json')),contract=JSON.parse(contractBytes);
const source=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js',import.meta.url));
const h=await import(moduleUrl('data:text/javascript;base64,'+source.toString('base64')));
const wSource=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js',import.meta.url),'utf8').replace("'./acmgInput'",JSON.stringify(moduleUrl('data:text/javascript;base64,'+source.toString('base64'))));
const w=await import('data:text/javascript;base64,'+Buffer.from(wSource).toString('base64'));
const id='ACMG_AMP_2015:PVS1',expected={UNCAPPED:'STRONG',PARENT_CAP:'MODERATE',LOCAL_CAP:'SUPPORTING',BOTH_CAPS:'SUPPORTING'};
let checks=0,actual=0;const controls=[];const check=(name,fn)=>{fn();checks++;console.log('PASS '+name);};
const entry=(run,candidate)=>h.evidenceCards(candidate,run.enabledCriteria,'candidate','0',run).find(card=>card.id===id)?.entries[0];
for(const example of contract.actualJavaExamples){
    const ref=example.files['all-local-batch'],file=path.join(base,ref.path),bytes=fs.readFileSync(file),run=JSON.parse(bytes),before=JSON.stringify(run);
    check(`captured ${example.scenario} identity and profile`,()=>{assert.equal(createHash('sha256').update(bytes).digest('hex'),ref.sha256);assert.equal(h.localBatchScopeIssue(run),null);});
    for(const candidate of run.candidates)check(`captured ${example.scenario}/${candidate.candidateId} proof enters normal cards with adopted min-cap`,()=>{
        const row=entry(run,candidate);assert.equal(row.displayIssue,false);assert.equal(row.evaluation.evaluationResult.status,'MET');
        assert.equal(row.evaluation.appliedStrength,expected[example.scenario]);assert.equal(w.resultPresentation(row).mainKind,'ADOPTED_MET');
        assert.ok(row.details.some(([label])=>label==='逃逸公开采用证明'));
        assert.ok(!row.details.some(([label])=>label==='逃逸强度展示核验依赖'));
        controls.push({run:structuredClone(run),candidateId:candidate.candidateId});actual++;
    });
    check(`captured ${example.scenario} input object and bytes unchanged`,()=>{assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(file),bytes);});
    check(`captured ${example.scenario} direct PVS1-only full-raw reader preserves saved status and cap`,()=>{
        const directRef=example.files['pvs1-only'],file=path.join(base,directRef.path),bytes=fs.readFileSync(file),direct=JSON.parse(bytes),before=JSON.stringify(direct);
        assert.equal(createHash('sha256').update(bytes).digest('hex'),directRef.sha256);
        for(const candidate of direct.candidates){const row=entry(direct,candidate);
            assert.equal(row.displayIssue,undefined);assert.equal(row.evaluation.evaluationResult.status,'MET');assert.equal(row.evaluation.appliedStrength,expected[example.scenario]);
            assert.equal(w.resultPresentation(row).mainKind,'ADOPTED_MET');
        }
        assert.equal(JSON.stringify(direct),before);assert.deepEqual(fs.readFileSync(file),bytes);
    });
}
assert.equal(actual,8);
const control=mutate=>{const {run,candidateId}=structuredClone(controls[0]),candidate=run.candidates.find(c=>c.candidateId===candidateId),raw=candidate.attempts[0].localBatch.criteria.find(c=>c.criterionId===id);mutate(raw);return {raw,row:entry(run,candidate)};};
check('synthetic absent proof keeps historical raw MET without display adoption or invented missing case data',()=>{
    const {raw,row}=control(raw=>delete raw.details.pvs1Facts.escapeAdoptionProof);
    assert.equal(raw.status,'MET');assert.equal(row.displayIssue,true);assert.equal(row.evaluation,undefined);assert.equal(row.displayOutcome.kind,'UNSUPPORTED');
    assert.ok(row.summary.includes('不能独立核验'));assert.equal(w.resultPresentation(row).mainKind,'NO_CONCLUSION');
});
check('synthetic UNAVAILABLE public proof has its own availability label and keeps the original scientific declaration',()=>{
    const {raw,row}=control(raw=>raw.details.pvs1Facts.escapeAdoptionProof={schemaVersion:'pvs1-escape-adoption-proof-v1',methodVersion:'1.0.0',scientificApprovalCreated:false,rescoringPerformed:false,status:'UNAVAILABLE',reasonCode:'PVS1_ESCAPE_PUBLIC_PROOF_SOURCE_BINDING_MISMATCH'});
    assert.equal(raw.status,'MET');assert.equal(row.displayIssue,true);assert.equal(row.evaluation,undefined);assert.equal(row.displayOutcome.kind,'UNAVAILABLE');
    assert.ok(row.summary.includes('投影不可用'));assert.ok(row.details.some(([label,value])=>label==='原保存状态与强度（未采用）'&&value==='MET / STRONG'));
});
check('synthetic positive proof tampering cannot pass through public card adapter',()=>{
    for(const mutate of [p=>p.strengthDerivation.adoptedStrength='VERY_STRONG',p=>p.ruleCheck.affectedResiduesInRegion++,p=>p.sources=[]]){
        const {row}=control(raw=>mutate(raw.details.pvs1Facts.escapeAdoptionProof));assert.equal(row.displayIssue,true);assert.equal(row.evaluation,undefined);
    }
});
assert.deepEqual(fs.readFileSync(path.join(base,'round4/public-contract.json')),contractBytes);
console.log(`${checks} PVS1 escape public adapter checks passed; eight captured Java rows, synthetic mutations and original-byte checks, no page/download/source execution.`);
