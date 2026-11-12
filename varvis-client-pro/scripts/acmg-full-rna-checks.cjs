// Provider dispatch fixtures and normal saved runs are reported as separate layers.
const fs=require('fs'),path=require('path'),assert=require('assert/strict'),{createHash}=require('crypto'),{Module}=require('module'),{buildSync}=require('esbuild');
const project=path.resolve(__dirname,'..'),dir=process.argv[2],output=process.argv[3],normalFiles=process.argv.slice(4);
if(!dir||!output)throw Error('Supply provider samples, result path, then optional normal public runs');
const b=buildSync({stdin:{resolveDir:project,contents:`export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgFullRna';
export {ps2IdentityHash,localBatchScopeIssue,evidenceCards,pvs1SelectedDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export {scoreEvidenceCards} from './src/pages/varvis/ChromosomeVisualization/utils/acmgEvidenceScore';
export {backgroundEvidenceCards,resultPresentation} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';
export {buildEvidenceDetailView,fullRnaDetails} from './src/pages/varvis/ChromosomeVisualization/utils/evidenceDetailView';
export {default as Details} from './src/pages/varvis/ChromosomeVisualization/components/EvidenceCoreDetails';`},bundle:true,platform:'node',format:'cjs',packages:'external',write:false,loader:{'.js':'jsx','.less':'empty'},logLevel:'silent'});
const m=new Module(path.join(project,'scripts/fullrna-bundle.cjs'));m.filename=m.id;m.paths=[path.join(project,'node_modules')];m._compile(b.outputFiles[0].text,m.id);
const api=m.exports,React=require('react'),{renderToStaticMarkup}=require('react-dom/server');
const read=f=>JSON.parse(fs.readFileSync(f,'utf8').replace(/^\uFEFF/,'')),clone=v=>JSON.parse(JSON.stringify(v)),sha=b=>createHash('sha256').update(b).digest('hex');
const checks=[],pins=[],templates=[],test=(name,fn)=>{fn();checks.push({name,status:'PASS'});},inspect=(c,e)=>api.inspectFullRnaCriterion(c,e,{identityHash:api.ps2IdentityHash});
test('a source file outside the product review is explained as missing review',()=>{
    const pending=api.fullRnaPending({ready:true,preparation:{},claim:{normalizedReasonCode:'PVS1_RNA_INITIATION_SOURCE_DOCUMENT_NOT_IN_REVIEW_SCOPE'}});
    assert.match(pending.gap,/尚未纳入本次审核/);
    assert.match(pending.conclusion,/暂不采用功能救援结论/);
});
test('outside and unresolved position scope have distinct PVS1 explanations',()=>{
    const pending=normalizedReasonCode=>api.fullRnaPending({ready:true,preparation:{},claim:{normalizedReasonCode}});
    const outside=pending('PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE');
    const unresolved=pending('PVS1_POLICY_POSITION_UNRESOLVED');
    assert.equal(outside.gap,'');
    assert.match(outside.conclusion,/超出适用规范已核定的位置范围/);
    assert.match(unresolved.gap,/位置尚未核实/);
    assert.match(unresolved.conclusion,/位置条件无法核查/);
});
test('residual normal transcript checks distinguish detection, unknown and parent-review scope',()=>{
    const pending=(normalizedReasonCode,assay,parentReview)=>api.fullRnaPending({ready:true,
        preparation:{assayConditions:{residualNormalTranscript:assay}},
        details:{rule:{parentReview:{residualNormalTranscript:parentReview}}},claim:{normalizedReasonCode}});
    const assayCode='PVS1_FULL_RNA_ASSAY_RESIDUALNORMALTRANSCRIPT_UNRESOLVED';
    const parentCode='PVS1_FULL_RNA_RESIDUAL_TRANSCRIPT_UNRESOLVED';
    for(const result of [pending(assayCode,'DETECTED_IN_ASSESSED_SCOPE','UNKNOWN'),
        pending(parentCode,'NOT_DETECTED_IN_ASSESSED_SCOPE','DETECTED_IN_ASSESSED_SCOPE')]){
        assert.match(result.known,/已记录检出/);
        assert.equal(result.gap,'');
        assert.match(result.conclusion,/检出记录未满足/);
    }
    const unresolved=pending(parentCode,'DETECTED_IN_ASSESSED_SCOPE','UNKNOWN');
    assert.doesNotMatch(unresolved.known,/已记录检出/);
    assert.match(unresolved.gap,/当前变异等位基因/);
    assert.match(unresolved.conclusion,/尚未核实/);
});
function contexts(v,out=[]){if(v&&typeof v==='object'){if(/^evidence-context-v[23]$/.test(v.schemaVersion||''))out.push(v);for(const x of Object.values(v))if(x&&typeof x==='object')contexts(x,out);}return out;}
function projected(c){return {...c,schemaVersion:'evidence-context-v2',contextId:'context-sha256-'+api.ps2IdentityHash({scope:c.scope,alleleId:c.variant.alleleId,hgncId:c.gene.hgncId,mondoId:c.disease.mondoId,moi:c.moi,transcript:{accession:c.transcript.accession,version:c.transcript.version},patientContextId:c.patientContext.patientContextId,familyContextId:c.patientContext.familyContextId??null})};}
function makeEntry(c,e){const v=inspect(c,e);assert.equal(v.valid,true,JSON.stringify(v));const evaluation={criterionId:c.criterionId,appliedStrength:c.appliedStrength,evaluationResult:{status:c.status,reasonCode:c.reasonCode}};if(v.view.inapplicable)evaluation.criterionApplicability={status:'NOT_APPLICABLE'};api.rememberFullRnaProjection(evaluation,c,e,v);return {evaluation,savedCriterion:c,hypothesisId:e.hypothesisId,displayIssue:false,details:[]};}
function verify(c,e,row){const view=api.fullRnaEntryView(row);assert.ok(view);const card={id:c.criterionId,code:c.criterionId.split(':').pop()},v=api.buildEvidenceDetailView(card,row,e.context),p=api.resultPresentation(row),html=renderToStaticMarkup(React.createElement(api.Details,{card,entry:row,candidate:{attempts:[{hypothesisId:e.hypothesisId,context:e.context}]}}));
 for(const raw of ['schemaVersion','mechanicalEffectSha256','context-sha256-','alteredRnaSequence','REFERENCE_CDS_ZERO_BASED_HALF_OPEN'])assert.ok(!html.includes(raw),raw);
 if(view.application==='ADOPTED'){assert.equal(p.mainKind,'ADOPTED_MET');assert.equal(v.needs,false);}
 if(view.application==='NOT_COUNTED'){assert.equal(p.adoption.kind,'NOT_COUNTED');assert.equal(p.adoption.targetCriterion,view.assignedTo);assert.equal(v.needs,false);assert.ok(!html.includes('缺少信息'));}
 if(view.application==='NOT_ADOPTED'&&view.preparation.configurationReasonCode){assert.ok(!html.includes('缺少信息'));assert.ok(html.includes('配置不符合要求')||html.includes('配置未就绪')||html.includes('未通过'));}
 if(view.application==='NOT_ADOPTED'&&view.preparation.assayConditions?.assayQuality==='UNKNOWN'){assert.ok(html.includes('RNA 试验质量'));assert.ok(v.comparisons.some(c=>c.gap.includes('试验质量')));}
 if(view.preparation.reasonCode==='FULL_RNA_SOURCE_MULTIPLE_OUTCOMES_UNRESOLVED'){
     assert.equal(p.mainKind,'NO_CONCLUSION');assert.equal(p.outcome.kind,'UNSUPPORTED');
     assert.match(v.summary,/尚不支持联合判定/);
     assert.ok(html.includes('本次资料记录了同一变异的多种 RNA 后果'));
     const historical=clone(e),row=historical.criteria.find(saved=>saved.criterionId===c.criterionId);
     const observed=historical.fullRnaObservedSourceIdentity.sources.find(saved=>saved.entryId===view.preparation.entryId);
     observed.observedSha256='0'.repeat(64);
     const prior=makeEntry(row,historical),priorView=api.buildEvidenceDetailView(card,prior,e.context);
     assert.equal(api.resultPresentation(prior).outcome.kind,'EXECUTION_FAILED');
     assert.match(priorView.summary,/本次运行中发生变化/);
     assert.ok(!priorView.sources.some(source=>source.url===view.preparation.sourceRecord.url));
     assert.ok(!api.fullRnaEntryView(prior).preparation.assayConditions);
     assert.equal(row.details.fullRnaUse.preparation.reasonCode,'FULL_RNA_SOURCE_MULTIPLE_OUTCOMES_UNRESOLVED');
 }
 if(view.preparation.reasonCode==='FULL_RNA_SOURCE_PHYSICAL_ALLELE_SCOPE_REQUIRED'){
     assert.equal(p.mainKind,'NO_CONCLUSION');assert.equal(p.outcome.kind,'MISSING');
     assert.match(v.summary,/无法确认 RNA 产物来自本次变异/);
 }
 if(view.preparation.reasonCode==='FULL_RNA_SOURCE_ORIGINAL_SHA_MISMATCH'){
     assert.equal(p.mainKind,'NO_CONCLUSION');assert.equal(p.outcome.kind,'EXECUTION_FAILED');
     assert.match(v.summary,/原文与已登记的校验值不一致/);
 }
 if(view.claim.normalizedReasonCode==='PVS1_RNA_INITIATION_SOURCE_DOCUMENT_NOT_IN_REVIEW_SCOPE'){
     assert.ok(html.includes('尚未纳入本次审核'));
     assert.ok(html.includes('暂不采用功能救援结论'));
 }
 const checks=view.details?.rule?.positionChecks;
 if(checks?.length){
     const position=v.sections.find(s=>s.title==='规则位置核查');
     assert.ok(html.includes('规则位置核查'));
     for(const check of checks){
         if(check.minimumInclusive!==undefined)assert.ok(position.rows.some(r=>r.value.includes(`≥ ${check.minimumInclusive}`)));
         if(check.maximumExclusive!==undefined)assert.ok(position.rows.some(r=>r.value.includes(`< ${check.maximumExclusive}`)));
         if(check.firstCheckedPosition!==undefined&&check.firstCheckedPosition!==check.lastCheckedPosition)
             assert.ok(position.rows.some(r=>r.value.includes(`实际 ${check.firstCheckedPosition}–${check.lastCheckedPosition}`)));
     }
     if(view.claim.normalizedReasonCode==='PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE'){
         if(view.details.assessmentKind==='INITIATION_LOSS'){
             const branch=v.sections.find(s=>s.title==='起始与替代转录本核查');
             assert.equal(branch.rows.find(r=>r.label==='本次分支结论').value,'尚未完成判定');
         }
         assert.ok(html.includes('超出范围'));
         assert.equal(p.mainKind,'NO_CONCLUSION');
     }
 }
 if(view.application==='CONFLICT'){assert.equal(p.outcome.kind,'CONFLICT');assert.equal(p.mainKind,'NO_CONCLUSION');assert.ok(v.conflicts.length);assert.ok(!html.includes('缺少信息'));}}
for(const name of fs.readdirSync(dir).filter(n=>n.endsWith('-public-batch.json')).sort()){
 const file=path.join(dir,name),privateFile=file.replace('-public-batch.json','-private-batch.json'),batch=read(file),privateBatch=read(privateFile),before=fs.readFileSync(file),rows=batch.results[0].localBatch.criteria;
 const first=rows.find(api.fullRnaCriterionClaim);if(!first)continue;const prep=first.details.fullRnaUse.preparation;
 const context=contexts(privateBatch).find(c=>{try{return api.ps2IdentityHash(prep.selectedProjectionSha256?projected(c):c)===prep.contextSha256;}catch{return false;}});assert.ok(context,'Missing actual selected/source context');
 const enabled=rows.map(c=>c.criterionId),e={analysisId:batch.analysisId,executionSourceSha256:batch.sourceSnapshotSha256,candidateId:batch.results[0].candidateId,hypothesisId:batch.results[0].hypothesisId,context,fullRnaBatchIdentity:batch.identity,fullRnaObservedSourceIdentity:batch.fullRnaObservedSourceIdentity,fullRnaBatchSourceSha256:batch.sourceSnapshotSha256,enabledCriteria:enabled,executionProfile:{enabledCriteria:enabled,moduleBindings:Object.fromEntries(rows.filter(api.fullRnaCriterionClaim).map(c=>[c.criterionId.split(':').pop().toLowerCase(),{runtimeBinding:batch.identity}]))},criteria:rows};
 pins.push({layer:'ACTUAL_DISPATCH_FIXTURE_NOT_NORMAL_BUSINESS',file,sha256:sha(before),privateFile,privateSha256:sha(fs.readFileSync(privateFile))});
 for(const c of rows.filter(api.fullRnaCriterionClaim))test('actual dispatcher shape '+name+'/'+c.criterionId,()=>{verify(c,e,makeEntry(c,e));templates.push({c,e});});
 test('actual assignment passes unchanged scoring '+name,()=>{const cards=rows.filter(api.fullRnaCriterionClaim).map(c=>({id:c.criterionId,code:c.criterionId.split(':').pop(),entries:[makeEntry(c,e)]})),score=api.scoreEvidenceCards(cards);
 const views=cards.map(c=>api.fullRnaEntryView(c.entries[0]));assert.equal(score.items.length,views.filter(v=>v.application==='ADOPTED').length);
 if(views.some(v=>v.application==='CONFLICT')){assert.equal(score.items.length,0);assert.equal(score.total,null);}
 if(views.some(v=>v.application==='NOT_COUNTED'))assert.equal(score.items.length,1);});
 assert.deepEqual(fs.readFileSync(file),before);
}
for(const file of normalFiles){const run=read(file),before=fs.readFileSync(file);pins.push({layer:'NORMAL_SAVED_PUBLIC_RUN',file,sha256:sha(before)});assert.equal(api.localBatchScopeIssue(run),null);
 for(const candidate of run.candidates)for(const [index,attempt]of candidate.attempts.entries()){const cards=api.backgroundEvidenceCards(candidate,run.enabledCriteria,run,index);
 for(const c of attempt.localBatch.criteria.filter(api.fullRnaCriterionClaim))test('normal workbench '+file+'/'+candidate.candidateId+'/'+c.criterionId,()=>{const e={analysisId:run.analysisId,executionSourceSha256:run.executionSourceSha256,candidateId:candidate.candidateId,hypothesisId:attempt.hypothesisId,context:attempt.context,fullRnaBatchIdentity:run.localBatch?.identity,fullRnaObservedSourceIdentity:run.localBatch?.fullRnaObservedSourceIdentity,fullRnaBatchSourceSha256:run.localBatch?.sourceSnapshotSha256,enabledCriteria:run.enabledCriteria,executionProfile:run.executionProfile,criteria:attempt.localBatch.criteria},row=cards.find(x=>x.id===c.criterionId)?.entries[0];assert.ok(row);assert.equal(Boolean(row.displayIssue),false,row.summary);assert.equal(row.savedCriterion,c);verify(c,e,row);});}
 assert.deepEqual(fs.readFileSync(file),before);
}
assert.ok(templates.length,'Need genuine dispatcher output');const t=templates[0];
for(const [name,change]of [['root',e=>{e.executionSourceSha256='f'.repeat(64);}],['observed source',e=>{delete e.fullRnaObservedSourceIdentity;}],['candidate',e=>{e.candidateId+='-other';}],['background',e=>{e.hypothesisId+='-other';}],['context',e=>{e.context.transcript.version++;}],['capability',e=>{delete e.executionProfile.moduleBindings[t.c.criterionId.split(':').pop().toLowerCase()].runtimeBinding.fullRnaExecution;}]])test('independent caller '+name,()=>{const e=clone(t.e);change(e);assert.equal(inspect(e.criteria.find(c=>c.criterionId===t.c.criterionId),e).valid,false);});
function rehash(c){const p=c.details.fullRnaUse;p.proofSha256=api.ps2IdentityHash(Object.fromEntries(Object.entries(p).filter(([k])=>k!=='proofSha256')));c.details[{PVS1:'pvs1Facts',PM4:'proteinLengthFacts',BP3:'bp3Facts'}[c.criterionId.split(':').pop()]].fullRnaUse=clone(p);}
for(const [name,change]of [['raw evaluation',c=>{c.details.originalResultSha256='f'.repeat(64);}],['module pointer',c=>{c.details.modulePointer='/modules/reference/results/0';}],['future method',c=>{c.details.fullRnaUse.methodVersion='9.0.0';rehash(c);}],['neutral preparation is no grant',c=>{c.details.fullRnaUse.preparation.criterionAdoptionGranted=true;rehash(c);}],['rehashed other context',c=>{c.details.fullRnaUse.preparation.contextSha256='f'.repeat(64);rehash(c);}]])test('malformed public '+name,()=>{const e=clone(t.e),c=e.criteria.find(c=>c.criterionId===t.c.criterionId);change(c);assert.equal(inspect(c,e).valid,false);});
const assigned=templates.find(x=>x.c.details.fullRnaUse.claim.application==='NOT_COUNTED');
assert.ok(assigned,'Need actual shared-effect assignment fixture');
test('an assignment requires its real same-ledger winning peer',()=>{const e=clone(assigned.e);e.criteria=e.criteria.filter(c=>c.criterionId!==assigned.c.details.fullRnaUse.claim.assignedTo);const c=e.criteria.find(c=>c.criterionId===assigned.c.criterionId);assert.equal(inspect(c,e).valid,false);});
test('rehashed wrong assignment target is rejected',()=>{const e=clone(assigned.e),c=e.criteria.find(c=>c.criterionId===assigned.c.criterionId);c.details.fullRnaUse.claim.assignedTo=c.criterionId;rehash(c);assert.equal(inspect(c,e).valid,false);});
const conflicting=templates.find(x=>x.c.details.fullRnaUse.claim.application==='CONFLICT');
assert.ok(conflicting,'Need actual opposite-effect conflict fixture');
test('coherent rehash cannot turn one side of an opposite-effect conflict into adoption',()=>{const e=clone(conflicting.e),c=e.criteria.find(c=>c.criterionId===conflicting.c.criterionId),claim=c.details.fullRnaUse.claim;claim.application='ADOPTED';delete claim.reasonCode;c.status=claim.originalStatus;c.appliedStrength=claim.originalStrength;c.reasonCode=claim.normalizedReasonCode;rehash(c);assert.equal(inspect(c,e).valid,false);});
test('old eager sourceIndex capability cannot be mixed into the new lazy contract',()=>{const e=clone(t.e),c=e.criteria.find(c=>c.criterionId===t.c.criterionId);e.executionProfile.moduleBindings[c.criterionId.split(':').pop().toLowerCase()].runtimeBinding.fullRnaExecution.sourceIndexConfigured=true;assert.equal(inspect(c,e).valid,false);});
test('JSON clones and later edits cannot manufacture a registered adopted projection',()=>{const row=makeEntry(t.c,t.e);assert.ok(api.fullRnaEntryView(row));assert.equal(api.resultPresentation(clone(row)).mainKind,'NO_CONCLUSION');row.evaluation.appliedStrength='VERY_STRONG';assert.equal(api.fullRnaEntryView(row),null);assert.equal(api.resultPresentation(row).mainKind,'NO_CONCLUSION');});
test('display control: unknown start/stop/topology are not zero; RNA length is not CDS length',()=>{const v=clone(inspect(t.c,t.e).view);v.preparation.sequenceEffect={referenceRnaLength:29,alteredRnaLength:29,nativeStart:{retainedContiguously:false},nativeStop:{retainedContiguously:true,inReadingFrameFromRetainedNativeStart:false}};v.preparation.topology={topologyCompleteness:'UNKNOWN',lastJunctionRnaPosition0:null};const rows=api.fullRnaDetails(v).rows;for(const label of ['参考 CDS 长度（nt）','改变后蛋白长度（aa）','蛋白长度净变化（aa）','最后连接点位置（改变后 RNA，零基边界）'])assert.equal(rows.find(r=>r.label===label).value,'尚未确定');assert.ok(rows.find(r=>r.label==='原始终止密码子').value.includes('不在当前阅读框'));});
const result={status:normalFiles.length?'PASS_NORMAL_AND_PROVIDER_FRONTEND':'PASS_PROVIDER_SHAPE_NORMAL_BUSINESS_PENDING',checks:checks.length,results:checks,pins,normalFiles,providerAdapter:'Actual batch.identity and private source/selected context; no new proof/currentBinding or normal runtime created',authorityIssued:false,providerModelNetworkCalls:0,browser:'NOT_RUN'};fs.writeFileSync(output,JSON.stringify(result,null,2));console.log(result.checks+' full RNA checks: '+result.status);
