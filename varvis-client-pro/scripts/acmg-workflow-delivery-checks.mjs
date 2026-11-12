import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const load = async name => import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(fs.readFileSync(new URL(`../src/pages/varvis/ChromosomeVisualization/utils/${name}.js`, import.meta.url), 'utf8')).toString('base64')}`));
const { evidenceOutcome, criterionLabel, materialPreparationDetails } = await load('acmgInput');
const { liveProgressText, watchSubmissionProgress } = await load('acmgRunFeedback');
const { materialTaskSummary, prepareAutomaticLiterature } = await load('automaticAcmg');
const codes = ['BA1','BS1','PM2','PP3','BP4','PS1','PM5','BP7','PVS1','PM4','BP3','PM1','PP2','BP1','PS2','PM6','PM3','PP1'];
const categories = { RESOURCE_DISABLED:'DISABLED', TOOL_TIMEOUT:'EXECUTION_FAILED', SOURCE_IDENTITY_MISMATCH:'EXECUTION_FAILED',
    RESOURCE_NOT_CONFIGURED:'CONFIGURATION', POPULATION_NO_RECORD:'NO_RECORD', TRANSCRIPT_REQUIRED:'MISSING',
    ASSEMBLY_UNSUPPORTED:'UNSUPPORTED', SOURCE_UNAVAILABLE:'UNAVAILABLE', SOURCE_FILE_NOT_FOUND:'UNAVAILABLE',
    SOURCE_CONFLICT:'CONFLICT', READING_INCOMPLETE:'INCOMPLETE', OLD_UNEXPLAINED_CODE:'UNRESOLVED' };
for (const code of codes) for (const [reasonCode, kind] of Object.entries(categories)) {
    const evaluation = {criterionId:`ACMG_AMP_2015:${code}`, appliedStrength:'NONE', evaluationResult:{status:'NOT_EVALUATED',reasonCode}};
    const before=JSON.stringify(evaluation);
    assert.equal(evidenceOutcome(evaluation).kind,kind);
    assert.notEqual(criterionLabel(evaluation),'未评价');
    assert.equal(JSON.stringify(evaluation),before,'presentation must not rewrite saved results');
    for(const status of ['MET','NOT_MET']) assert.equal(evidenceOutcome({...evaluation,evaluationResult:{status,reasonCode}}).kind,status);
}
assert.equal(evidenceOutcome({evaluationResult:{status:'NOT_EVALUATED',reasonCode:'BP7_CONSEQUENCE_NOT_APPLICABLE'}}).kind,'NOT_APPLICABLE');
assert.equal(evidenceOutcome({criterionId:'ACMG_AMP_2015:BP7',reviewRequired:true,evaluationResult:{status:'MET'}}).kind,'REVIEW');
const criterion={status:'NOT_EVALUATED',reasonCode:'PM3_CONTEXT_MISSING',details:{modulePointer:'/modules/pm3'}};
criterion.details.materialPreparation={schemaVersion:'material-preparation-summary-v1',status:'UNRESOLVED',reasonCode:criterion.reasonCode,module:'pm3',moduleResultPointer:'/modules/pm3',capturedDocuments:0,excludedSourceReasons:['PM3_TASK_VERSION_STALE']};
assert.ok(materialPreparationDetails(criterion).some(row=>row[0]==='未采用的缓存资料'));
criterion.details.materialPreparation.status='EVALUATED';
assert.match(materialPreparationDetails(criterion)[0][1],/不相容/);
const text=liveProgressText({phase:'VERIFY_EVALUATE',candidateId:'v',hypothesisId:'h',criterion:'PP1',elapsedSeconds:41,status:'RUNNING'},[{candidateId:'v',geneSymbol:'TEST'}],[{hypothesis:{candidateId:'v',hypothesisId:'h',mondoId:'MONDO:1',moi:'AD'}}]);
assert.match(text,/TEST.*MONDO:1.*PP1.*41/); assert.doesNotMatch(text,/%|预计/);
let queued, reads=0, observations=[];
const stop=watchSubmissionProgress({read:async()=>{reads++;throw new Error('transport');},onProgress:v=>observations.push(v),schedule:fn=>{queued=fn;return 1;},cancel:()=>{queued=null;}});
await queued();assert.equal(reads,1);assert.equal(observations[0].status,'UNAVAILABLE');stop();assert.equal(queued,null);
assert.equal(materialTaskSummary({status:'READY',result:{readingLoop:{state:'INCOMPLETE',feedback:{stopReason:'PM3_MODEL_DISABLED_PENDING_READING'}}}}),'待核查 · 资料提取未启用');
assert.equal(materialTaskSummary({status:'READY',result:{papers:[{status:'PARTIAL',stopReason:'PARENT_PHENOTYPE_MISSING'}]}}),'待核查 · 缺少亲本表型');
assert.equal(materialTaskSummary({status:'READY',result:{status:'PARTIAL',stopReason:'PP1_SOURCE_BATCH_FINISHED'}}),'待核查');
const id='a'.repeat(64);let pp1=0;
const base={analysisId:'a',topN:1,declaration:{hypotheses:[{hypothesis:{candidateId:'v',hypothesisId:'h'}},{hypothesis:{candidateId:'v',hypothesisId:'h'}}]},
    progress:()=>{},get:async()=>({enabled:false}),post:async url=>{
        if(url.endsWith('/pp1/local-review'))return {schemaVersion:'pp1-local-review-plan-v1',planId:id,attempts:[{candidateId:'v',hypothesisId:'h'}]};
        if(url.endsWith('/local-review'))return {schemaVersion:'denovo-local-discovery-plan-v1',candidateIds:[]};
        pp1++;return {taskId:id,status:'READY'};
    }};
await prepareAutomaticLiterature(base);assert.equal(pp1,1,'identical context gets one material task; distinct contexts remain independent in automatic-acmg-smoke');
let intake=0;
const paper={pmcid:'PMC123',candidateIntake:{status:'AVAILABLE'}};
const paperPlan={schemaVersion:'denovo-local-discovery-plan-v1',candidateIds:['v']};
const paperTest={...base,declaration:{selectedCriteria:['ACMG_AMP_2015:PS2'],hypotheses:[]},
    get:async()=>({enabled:true,selectionSha256:id,candidateAcceptanceEnabled:true}),
    post:async url=>{if(url.endsWith('/local-review'))return paperPlan;if(url.endsWith('/accept')){intake++;return {status:'ACCEPTED_FOR_QUALIFICATION_NOT_SCORED',scientificExecution:false};}return {taskId:id,status:'READY',result:{papers:[paper,paper]}};}};
await prepareAutomaticLiterature(paperTest);assert.equal(intake,1,'same paper intake is not duplicated');
await assert.rejects(prepareAutomaticLiterature({...paperTest,post:async url=>url.endsWith('/local-review')?paperPlan:
    url.endsWith('/accept')?{status:'ACCEPTED_FOR_QUALIFICATION_NOT_SCORED',scientificExecution:false}:{taskId:id,status:'READY',result:{papers:[paper,{...paper,candidateIntake:{status:'CHANGED'}}]}}}),/不同资料版本/);
if (process.argv[2]) {
    const batch = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
    for (const row of batch.results) for (const item of row.localBatch.criteria) {
        assert.ok(item.details.materialPreparation, item.criterionId);
        const details = materialPreparationDetails(item);
        assert.ok(details.length > 0);
        assert.doesNotMatch(details[0][1], /摘要与当前结果不相容/, item.criterionId);
    }
}
console.log('PASS: 18 criteria × 12 reason categories; status precedence/immutability; BP7 review; material audit binding; live progress/read failure; incomplete READY; scoped task and paper deduplication/conflict.');
