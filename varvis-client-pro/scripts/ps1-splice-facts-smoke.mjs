import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';

registerHooks({ resolve(specifier, context, next) {
    return next(/^\.\/[a-zA-Z][^.\/]+$/.test(specifier) ? specifier + '.js' : specifier, context);
} });
const api = await import(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const event = { event: 'NATIVE_DONOR_LOSS', position: 100, score: '0.98', observedRna: false, inferredExonSkipping: false };
const reason = 'PS1_SAME_PREDICTED_NATIVE_SPLICE_LOSS';
const fact = { schemaVersion: 'ps1-splice-facts-v1', methodVersion: '1.0.0', status: 'MET', reasonCode: reason,
    scope: 'INTRONIC_SNV_SINGLE_NATIVE_GT_AG_SITE_LOSS', observedRna: false, transcript: 'ENST00000000001.1', baseline: 'PP3',
    targetEvent: event, unresolvedReasons: [], referenceScv: 'SCV000000001.1', comparison: { status: 'MET', observedRna: false,
        baseline: 'PP3', appliedStrength: 'MODERATE', similarityMode: 'NO_WEAKER', targetEvent: event, referenceEvent: event } };
const criterion = { criterionId: 'ACMG_AMP_2015:PS1', status: 'MET', appliedStrength: 'MODERATE', reasonCode: reason,
    details: { spliceComparisonFacts: fact } };
let checks=0;
const run = (name, action) => { action(); checks++; console.log(`PASS ${name}`); };
run('valid prediction is displayed without claiming RNA', () => {
    const before=JSON.stringify(criterion), rows=api.spliceComparisonDetails(criterion);
    assert(rows.some(([key])=>key==='相同预测影响')); assert(rows.some(([,text])=>text.includes('不是RNA实验')));
    assert.equal(JSON.stringify(criterion),before);
});
const mutations = {
    strength: c=>{c.appliedStrength='STRONG';},
    position: c=>{c.details.spliceComparisonFacts.comparison.referenceEvent.position++;},
    weak: c=>{c.details.spliceComparisonFacts.comparison.targetEvent.score='0.50';c.details.spliceComparisonFacts.targetEvent.score='0.50';},
    event: c=>{c.details.spliceComparisonFacts.comparison.referenceEvent.event='NATIVE_ACCEPTOR_LOSS';},
    reason: c=>{c.reasonCode='OTHER';},
    status: c=>{c.status='NOT_EVALUATED';},
    rna: c=>{c.details.spliceComparisonFacts.observedRna=true;},
    innerRna: c=>{c.details.spliceComparisonFacts.comparison.observedRna=true;},
    baseline: c=>{c.details.spliceComparisonFacts.comparison.baseline='PVS1';},
    targetSummary: c=>{c.details.spliceComparisonFacts.targetEvent.position++;},
    reference: c=>{c.details.spliceComparisonFacts.referenceScv='';},
    score: c=>{c.details.spliceComparisonFacts.comparison.referenceEvent.score='NaN';},
    threshold: c=>{c.details.spliceComparisonFacts.comparison.referenceEvent.score='0.2';},
};
for(const [name,mutate] of Object.entries(mutations))run(`corrupt ${name} withdraws explanatory claim`,()=>{
    const c=JSON.parse(JSON.stringify(criterion));mutate(c);const before=JSON.stringify(c),rows=api.spliceComparisonDetails(c);
    assert.equal(rows.length,1);assert.match(rows[0][1],/不完整|不一致/);assert.equal(JSON.stringify(c),before);
});
run('real missing PVS1 explained specifically',()=>{
    const c=JSON.parse(JSON.stringify(criterion));c.status='NOT_EVALUATED';c.reasonCode='PS1_SPLICE_PVS1_BASELINE_REQUIRED';c.appliedStrength='NONE';
    Object.assign(c.details.spliceComparisonFacts,{status:c.status,reasonCode:c.reasonCode,baseline:''});delete c.details.spliceComparisonFacts.comparison;
    assert(api.spliceComparisonDetails(c).some(([,s])=>s.includes('PVS1评价')));
});
run('historical results do not acquire a splice claim',()=>assert.deepEqual(api.spliceComparisonDetails({status:'MET'}),[]));
const current = JSON.parse(JSON.stringify(criterion));
const f = current.details.spliceComparisonFacts;
Object.assign(f, {methodVersion:'1.1.0',scope:'INTRONIC_SNV_MATCHED_NATIVE_LOSS_PROFILE',baseline:'PVS1',
    baselineSource:{origin:'SOURCE_ORIGINAL_EXPERT_PVS1',strength:'PVS1',currentPvs1EvaluationCreated:false,
        sourceTranscript:'NM_000001.2',runtimeTranscript:f.transcript,codingHgvs:'c.4+1G>A',
        assertions:[{scv:'SCV000000002.1',originalVersion:'1.1',evaluationDate:'2024-01-02',xmlSha256:'a'.repeat(64)}]}});
current.appliedStrength='SUPPORTING';f.comparison.appliedStrength='SUPPORTING';f.comparison.baseline='PVS1';
const profile = ds => Object.fromEntries(['AG','AL','DG','DL'].map(k=>[k,{score:k==='DL'?'0.98':k==='DG'?ds:'0.00',position:k==='DL'?100:80,offset:k==='DL'?-1:-21}]));
f.targetEvent.profile=profile('0.23');f.comparison.targetEvent.profile=profile('0.23');f.comparison.referenceEvent.profile=profile('0.25');f.comparison.similarityMode='PUBLISHED_RELATIVE_TEN_PERCENT';
run('full profile and retained target baseline are explicit',()=>{
    const rows=api.spliceComparisonDetails(current);assert(rows.some(([k])=>k==='完整预测 DG'));assert(rows.some(([,s])=>s.includes('不是本轮重新计算出的 PVS1')));
});
const profileMutations={
    secondaryPosition:c=>c.details.spliceComparisonFacts.comparison.referenceEvent.profile.DG.position++,
    secondaryWeaker:c=>{const x=c.details.spliceComparisonFacts;x.targetEvent.profile.DG.score='0.224999';x.comparison.targetEvent.profile.DG.score='0.224999';},
    secondaryTooHigh:c=>{const x=c.details.spliceComparisonFacts;x.targetEvent.profile.DG.score='0.275001';x.comparison.targetEvent.profile.DG.score='0.275001';},
    gray:c=>{const x=c.details.spliceComparisonFacts;x.targetEvent.profile.DG.score='0.2';x.comparison.targetEvent.profile.DG.score='0.2';},
    missingEvent:c=>delete c.details.spliceComparisonFacts.comparison.referenceEvent.profile.AG,
    rawSummary:c=>c.details.spliceComparisonFacts.targetEvent.profile.DG.offset++,
    baselineMissing:c=>delete c.details.spliceComparisonFacts.baselineSource,
    baselineInvented:c=>{c.details.spliceComparisonFacts.baselineSource.currentPvs1EvaluationCreated=true;},
    sourcePin:c=>{c.details.spliceComparisonFacts.baselineSource.assertions[0].xmlSha256='';},
    transcript:c=>{c.details.spliceComparisonFacts.baselineSource.runtimeTranscript='ENST00000000001.2';},
};
for(const [name,mutate] of Object.entries(profileMutations))run(`full profile corrupt ${name}`,()=>{
    const c=JSON.parse(JSON.stringify(current));mutate(c);const before=JSON.stringify(c),rows=api.spliceComparisonDetails(c);
    assert.equal(rows.length,1);assert.match(rows[0][1],/不完整|不一致/);assert.equal(JSON.stringify(c),before);
});
console.log(JSON.stringify({checks,status:'PASS',scientificExecution:0}));
