// Actual saved legacy-business developer captures; source facts/policies are
// SYNTHETIC. Direct SelectedBatch singles and HTTP400 refusals are separate.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import moduleUrl from './frontend-module-url.cjs';
const root=process.env.VARVIS_NATIVE_ROOT;if(!root)throw Error('Set VARVIS_NATIVE_ROOT.');
const base=path.join(root,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a6-a8/round4');
const indexBytes=fs.readFileSync(path.join(base,'actual-sample-index.json')),index=JSON.parse(indexBytes);
const src=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js',import.meta.url));
const url=moduleUrl('data:text/javascript;base64,'+src.toString('base64')),h=await import(url);
const wsrc=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js',import.meta.url),'utf8').replace("'./acmgInput'",JSON.stringify(url));
const w=await import('data:text/javascript;base64,'+Buffer.from(wsrc).toString('base64'));
let count=0,actual=0;const samples=[];const check=(name,fn)=>{fn();count++;console.log('PASS '+name);};
const card=(run,code)=>h.evidenceCards(run.candidates[0],run.enabledCriteria,'candidate','0',run).find(card=>card.code===code)?.entries[0];
for(const item of index.samples.filter(item=>item.evidenceLevel==='SYNTHETIC_PUBLICATION_LOCAL_REPORT_REAL_BUSINESS_AND_H2_SKELETON')){
    const file=path.join(base,item.file),bytes=fs.readFileSync(file),document=JSON.parse(bytes),run=document.publicResult,before=JSON.stringify(document);
    check(`captured ${item.file} pins and actual whole saved profile`,()=>{assert.equal(bytes.length,item.bytes);assert.equal(createHash('sha256').update(bytes).digest('hex'),item.sha256);assert.equal(h.localBatchScopeIssue(run),null);});
    check(`captured ${item.file} expert or combined adopted PS2 and uncounted PM6 stay distinct`,()=>{
        const ps2=card(run,'PS2'),pm6=card(run,'PM6'),fixed=item.file.includes('TISSUE_');
        assert.equal(ps2.displayIssue,false);assert.equal(ps2.evaluation.evaluationResult.status,'MET');assert.equal(ps2.evaluation.appliedStrength,fixed?'STRONG':'SUPPORTING');
        assert.equal(w.resultPresentation(ps2).mainKind,'ADOPTED_MET');
        assert.equal(pm6.displayIssue,false);assert.equal(pm6.evaluation.evaluationResult.status,'NOT_EVALUATED');
        assert.equal(w.resultPresentation(pm6).adoption.targetCriterion,'PS2');
        const facts=ps2.savedCriterion.details.deNovoFacts;assert.equal(facts.adoptionProof.projectionStatus,'COMPLETE');
        if(fixed){assert.equal(facts.rawQuarterPoints,0);assert.equal(facts.effectiveQuarterPoints,0);assert.equal(facts.calculationMethod,'EXPERT_FIXED_STRENGTH');}
        else assert.ok(!facts.events.some(event=>event.disposition==='COUNTED'&&event.confirmedParentage));
        assert.ok(!ps2.details.some(([label])=>label==='固定强度展示核验依赖'||label==='组合 PS2 展示核验依赖'));
        samples.push(structuredClone(run));actual++;
    });
    check(`captured ${item.file} immutable object and raw bytes`,()=>{assert.equal(JSON.stringify(document),before);assert.deepEqual(fs.readFileSync(file),bytes);});
}
assert.equal(actual,2);
const mutateRows=(run,action)=>{for(const row of run.candidates[0].attempts[0].localBatch.criteria)if(row.details.deNovoFacts)action(row.details.deNovoFacts);};
for(const [name,mutate]of [
    ['missing proof',facts=>delete facts.adoptionProof],
    ['wrong adopted strength',facts=>facts.adoptionProof.appliedStrength='VERY_STRONG'],
    ['missing executed-version receipt',facts=>delete facts.calculationBindings[0].executedCalculatorVersion],
    ['wrong original selected-policy adoption',facts=>facts.specializedPolicyAdoptions[0].globalSelectionId='cspec-selection-sha256-'+'f'.repeat(64)],
])check('synthetic public mutation refuses '+name+' and preserves the original verdict',()=>{
    const run=structuredClone(samples[0]);mutateRows(run,mutate);const row=card(run,'PS2');
    assert.equal(row.displayIssue,true);assert.equal(row.evaluation,undefined);assert.equal(w.resultPresentation(row).mainKind,'NO_CONCLUSION');
    assert.equal(row.savedCriterion.status,'MET');assert.equal(row.savedCriterion.appliedStrength,'SUPPORTING');
});
check('synthetic incomplete proof is availability/display problem rather than missing clinical data',()=>{
    const run=structuredClone(samples[0]);mutateRows(run,facts=>facts.adoptionProof.projectionStatus='INCOMPLETE');const row=card(run,'PS2');
    assert.equal(row.displayIssue,true);assert.equal(row.displayOutcome.kind,'UNAVAILABLE');assert.ok(row.summary.includes('投影不完整'));
});
check('synthetic wrong selected background cannot borrow another scope adopted proof',()=>{
    const run=structuredClone(samples[0]);run.candidates[0].attempts[0].context.contextId='context-sha256-'+'f'.repeat(64);
    const row=card(run,'PS2');assert.equal(row.displayIssue,true);assert.equal(row.evaluation,undefined);
});
assert.deepEqual(fs.readFileSync(path.join(base,'actual-sample-index.json')),indexBytes);
console.log(`${count} DeNovo public adapter checks passed: two actual legacy-business BOTH captures plus synthetic mutations; no central18/single HTTP success, Java or page execution claimed.`);
