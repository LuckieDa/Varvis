// Existing normal exports, actual module products and explicit wire mutations are separate layers.
const fs=require('fs'),path=require('path'),assert=require('node:assert/strict'),crypto=require('crypto'),{Module}=require('module'),{buildSync}=require('esbuild');
const project=path.resolve(__dirname,'..'),normalDir=process.argv[2],moduleDir=process.argv[3],initiationDir=process.argv[4],output=process.argv[5];
if(!normalDir||!moduleDir||!initiationDir||!output)throw Error('Supply normal products, completed module, initiation module products and private output');
const built=buildSync({stdin:{resolveDir:project,contents:`export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgFullRna';
export {ps2IdentityHash,localBatchScopeIssue} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export {backgroundEvidenceCards,resultPresentation} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';
export {scoreEvidenceCards} from './src/pages/varvis/ChromosomeVisualization/utils/acmgEvidenceScore';
export {buildEvidenceDetailView,fullRnaDetails} from './src/pages/varvis/ChromosomeVisualization/utils/evidenceDetailView';
export {default as Details} from './src/pages/varvis/ChromosomeVisualization/components/EvidenceCoreDetails';`},bundle:true,platform:'node',format:'cjs',packages:'external',write:false,loader:{'.js':'jsx','.less':'empty'},logLevel:'silent'});
const mod=new Module(path.join(project,'scripts/rna-application-bundle.cjs'));mod.filename=mod.id;mod.paths=[path.join(project,'node_modules')];mod._compile(built.outputFiles[0].text,mod.id);
const api=mod.exports,React=require('react'),{renderToStaticMarkup}=require('react-dom/server'),read=f=>JSON.parse(fs.readFileSync(f,'utf8').replace(/^\uFEFF/,''));
const clone=v=>JSON.parse(JSON.stringify(v)),sha=b=>crypto.createHash('sha256').update(b).digest('hex'),checks=[],pins=[];
const test=(name,fn)=>{fn();checks.push({name,status:'PASS'});},pin=(file,layer)=>{const bytes=fs.readFileSync(file);pins.push({file,sha256:sha(bytes),bytes:bytes.length,layer});return bytes;};
const expected=(run,candidate,attempt)=>({analysisId:run.analysisId,executionSourceSha256:run.executionSourceSha256,candidateId:candidate.candidateId,hypothesisId:attempt.hypothesisId,context:attempt.context,enabledCriteria:run.enabledCriteria,executionProfile:run.executionProfile,criteria:attempt.localBatch.criteria,fullRnaBatchIdentity:run.localBatch.identity,fullRnaObservedSourceIdentity:run.localBatch.fullRnaObservedSourceIdentity,fullRnaBatchSourceSha256:run.localBatch.sourceSnapshotSha256});
const inspect=(c,e)=>api.inspectFullRnaCriterion(c,e,{identityHash:api.ps2IdentityHash});
const validNames=['NEUTRAL_PREPARATION','PVS1_SINGLE','RELATED','CURRENT18','PM4_ONLY_NO_PVS1_KNOWLEDGE','RNA_INSERTION','RNA_STOP_REMOVAL','RNA_CROSS_CDS_UTR','CHANGED_ORIGINAL','NO_INDEX_LEGACY','synonymous-terminal/SYNONYMOUS_STOP_REMOVAL','synonymous-terminal/SYNONYMOUS_CROSS_CDS_UTR'];
let nativeTemplate,normalRows=0;
for(const name of validNames)test('actual normal same-run full/background '+name,()=>{
    const files=['reader-controller-full-export.json','reader-background.json','reader-index.json'].map(n=>path.join(normalDir,name,n));
    const before=files.map(f=>pin(f,'ACTUAL_NORMAL_WRITER_EXIT_READER_EXPORT')),[full,background,index]=files.map(read);
    for(const run of [full,background])assert.equal(api.localBatchScopeIssue(run),null,name);
    assert.deepEqual(background.localBatch,index.localBatch);assert.equal(background.runId,full.runId);assert.equal(background.executionSourceSha256,full.executionSourceSha256);
    for(const candidate of background.candidates)for(const [attemptIndex,attempt]of candidate.attempts.entries()){
        const saved=full.candidates.find(c=>c.candidateId===candidate.candidateId),savedAttempt=saved.attempts.find(a=>a.hypothesisId===attempt.hypothesisId),cards=api.backgroundEvidenceCards(candidate,background.enabledCriteria,background,attemptIndex),fullCards=api.backgroundEvidenceCards(saved,full.enabledCriteria,full,saved.attempts.indexOf(savedAttempt));
        assert.deepEqual(api.scoreEvidenceCards(cards),api.scoreEvidenceCards(fullCards));
        for(const criterion of attempt.localBatch.criteria.filter(api.fullRnaCriterionClaim)){
            const e=expected(background,candidate,attempt),inspection=inspect(criterion,e),card=cards.find(c=>c.id===criterion.criterionId),entry=card.entries[0];
            assert.equal(inspection.valid,true,JSON.stringify(inspection.issues));assert.equal(Boolean(entry.displayIssue),false);assert.equal(entry.savedCriterion,criterion);
            assert.deepEqual(criterion,savedAttempt.localBatch.criteria.find(c=>c.criterionId===criterion.criterionId));
            const html=renderToStaticMarkup(React.createElement(api.Details,{card,entry,candidate}));
            for(const raw of ['schemaVersion','mechanicalEffectSha256','context-sha256-','proteinThroughFirstStop'])assert.ok(!html.includes(raw),raw);
            if(inspection.view.application==='ADOPTED'||inspection.view.application==='NOT_COUNTED')assert.equal(api.buildEvidenceDetailView(card,entry,attempt.context).needs,false);
            if(!nativeTemplate&&criterion.criterionId==='ACMG_AMP_2015:PVS1'&&criterion.status==='MET')nativeTemplate={run:background,candidate,attempt,criterion};
            normalRows++;
        }
    }
    files.forEach((file,i)=>assert.deepEqual(fs.readFileSync(file),before[i]));
});
assert.ok(nativeTemplate,'Need real normal retained-start MET');
function changedNormal(change){const run=clone(nativeTemplate.run),candidate=run.candidates.find(c=>c.candidateId===nativeTemplate.candidate.candidateId),attempt=candidate.attempts.find(a=>a.hypothesisId===nativeTemplate.attempt.hypothesisId),c=attempt.localBatch.criteria.find(r=>r.criterionId==='ACMG_AMP_2015:PVS1');change(c,run);return {run,candidate,attempt,c};}
function rehash(c){const p=c.details.fullRnaUse;p.proofSha256=api.ps2IdentityHash(Object.fromEntries(Object.entries(p).filter(([k])=>k!=='proofSha256')));c.details.pvs1Facts.fullRnaUse=clone(p);}
test('wire mutations: application contracts cannot mix cap/proof/details or accept a future version',()=>{
    for(const change of [c=>{c.details.fullRnaUse.methodVersion='1.0.0';rehash(c);},c=>{c.details.pvs1Facts.fullRnaDetails.methodVersion='1.0.0';c.details.fullRnaUse.decisionDetailsSha256=api.ps2IdentityHash(c.details.pvs1Facts.fullRnaDetails);rehash(c);},c=>{delete c.details.pvs1Facts.fullRnaDetails;delete c.details.fullRnaUse.decisionDetailsSha256;rehash(c);},c=>{c.details.fullRnaUse.methodVersion='2.0.0';rehash(c);}]){
        const {run,candidate,attempt,c}=changedNormal(change);assert.equal(inspect(c,expected(run,candidate,attempt)).valid,false);
    }
});
test('actual normal mutation: an inapplicability reason alone cannot change MET into public NOT_APPLICABLE',()=>{
    const {run,candidate,attempt,c}=changedNormal(c=>{c.status='NOT_APPLICABLE';c.appliedStrength='NONE';c.reasonCode='PVS1_RNA_INITIATION_FUNCTIONAL_ALTERNATIVE_CONFIRMED';});
    assert.equal(inspect(c,expected(run,candidate,attempt)).valid,false);assert.equal(Boolean(api.backgroundEvidenceCards(candidate,run.enabledCriteria,run,0).find(c=>c.code==='PVS1').entries[0].displayIssue),true);
});
const rawFile=path.join(moduleDir,'provider-result.json'),viewFile=path.join(moduleDir,'public-view.json'),moduleBefore=pin(rawFile,'ACTUAL_COMPLETED_MODULE_NOT_NORMAL_RUN');pin(viewFile,'ACTUAL_MODULE_PUBLIC_PROJECTION_NOT_NORMAL_RUN');
const raw=read(rawFile),f=raw.publicSummary,p=f.fullRnaUse,claim=p.claim,cap={methodVersion:'1.1.0',pvs1Version:'1.10.1'};
test('actual completed module: strict typed exclusion matches preserved NE/NONE and source/effect',()=>{
    assert.equal(api.ps2IdentityHash(raw.evaluation),claim.evaluationSha256);assert.equal(api.ps2IdentityHash(raw.fullRnaDecisionDetails),p.decisionDetailsSha256);
    assert.equal(api.fullRnaCompletedExclusionValid(f,cap,claim,raw.evaluation),true);assert.equal(read(viewFile).status,'NOT_APPLICABLE');
    assert.equal(api.fullRnaCriterionClaim(read(viewFile)),false);assert.equal(api.fullRnaEntryView({evaluation:raw.evaluation,savedCriterion:read(viewFile)}),null);
});
test('module/wire negatives: no reason-only, incomplete execution, foreign source/effect or old application completion',()=>{
    for(const mutate of [(f,c,e)=>{delete c.completedExclusion;},(f,c,e)=>{delete c.originalApplicability;},(f,c,e)=>{c.originalExecutionStatus='NOT_EXECUTED';},(f,c,e)=>{e.deterministicTrace.executionStatus='NOT_EXECUTED';},(f,c,e)=>{f.fullRnaAssessment.mechanicalEffectSha256='f'.repeat(64);},(f,c,e)=>{f.fullRnaAssessment.sourceOriginalSha256='f'.repeat(64);},(f,c,e)=>{f.evaluationState='NOT_EXECUTED';f.evaluationMethodVersion=null;},(f,c,e)=>{f.fullRnaUse.methodVersion='1.0.0';}]){
        const x=clone(f),c=x.fullRnaUse.claim,e=clone(raw.evaluation);mutate(x,c,e);assert.equal(api.fullRnaCompletedExclusionValid(x,cap,c,e),false);
    }
});
const moduleView={code:'PVS1',claim,preparation:p.preparation,assessment:f.fullRnaAssessment,details:f.fullRnaDetails,proof:p,ready:true,positive:false,completedExclusion:true,inapplicable:true,application:claim.application};
test('actual bounded module details distinguish putative start, confirmed unaffected alternative and original excerpts',()=>{
    const core=api.fullRnaDetails(moduleView),text=JSON.stringify(core);
    assert.ok(text.includes('资料审核的候选起始，未证明实际翻译'));assert.ok(text.includes('功能尚未确定'));assert.ok(text.includes('ENST000002.1'));assert.ok(text.includes('不受当前 RNA 事件影响'));
    assert.ok(text.includes('原文摘录 1'));assert.ok(text.includes('SYNTHETIC TEST ONLY'));assert.ok(!text.includes('proteinThroughFirstStop'));assert.ok(!text.includes('mechanicalEffectSha256'));
    assert.equal(core.rows.find(r=>r.label==='改变后蛋白长度（aa）').value,'尚未确定');assert.ok(api.fullRnaSummary(moduleView).includes('功能性替代转录本'));
    assert.ok(!text.includes('翻译起始尚未确定'));assert.ok(!text.includes('缺少信息'));
});
let modulePositive=0;
for(const name of ['pathogenic-1','pathogenic--1','negative-1','negative--1'])test('actual provider-only start-loss facts '+name,()=>{
    const file=path.join(initiationDir,name,'provider-result.json'),before=pin(file,'ACTUAL_PROVIDER_MODULE_NOT_CURRENT_PUBLIC_OR_NORMAL_RUN'),j=read(file).runtimeResult,input=j.resolvedInput,e=j.evaluation,a=input.fullRnaAssessment,i=input.fullRnaInitiationAssessment,s=input.fullRnaFacts,c={originalStatus:e.evaluationResult.status,originalStrength:e.appliedStrength};
    assert.equal(input.variantMechanism,'SOURCE_BOUND_FULL_RNA_INITIATION_LOSS');assert.equal(api.fullRnaInitiationPositiveValid(a,i,s,c,'INITIATION_LOSS'),true);
    assert.equal(e.appliedStrength,name.startsWith('pathogenic')?'MODERATE':'SUPPORTING');assert.equal(s.alteredProteinLength,null);assert.equal(s.netProteinLengthChange,null);assert.equal(i.sourceSpecifiedStart.startStatus,'SOURCE_REVIEWED_PUTATIVE');
    for(const mutate of [(a,i,s,c,d)=>{delete d.i;},(a,i,s,c,d)=>{d.kind='NMD';},(a,i,s)=>{i.mechanicalEffectSha256='f'.repeat(64);},(a,i,s)=>{a.status='NOT_EVALUATED';},(a,i,s)=>{i.status='NOT_EVALUATED';},(a,i,s)=>{delete s.nativeStart;},(a,i,s)=>{s.nativeStart.retainedContiguously=true;}]){
        const d={a:clone(a),i:clone(i),s:clone(s),c:clone(c),kind:'INITIATION_LOSS'};mutate(d.a,d.i,d.s,d.c,d);assert.equal(api.fullRnaInitiationPositiveValid(d.a,d.i,d.s,d.c,d.kind),false);
    }
    assert.deepEqual(fs.readFileSync(file),before);modulePositive++;
});
test('wire cap control only: stored final strength may be lower than branch strength but cannot be higher or unknown',()=>{
    const j=read(path.join(initiationDir,'pathogenic-1','provider-result.json')).runtimeResult,input=j.resolvedInput,a=clone(input.fullRnaAssessment),i=clone(input.fullRnaInitiationAssessment),s=input.fullRnaFacts;
    a.adoptedStrength='SUPPORTING';const c={originalStatus:'MET',originalStrength:'SUPPORTING'};
    assert.equal(i.strength,'MODERATE');assert.equal(api.fullRnaInitiationPositiveValid(a,i,s,c,'INITIATION_LOSS'),true);
    for(const branchStrength of ['SUPPORTING','NONE','UNKNOWN',undefined]){
        const x=clone(i);if(branchStrength===undefined)delete x.strength;else x.strength=branchStrength;
        assert.equal(api.fullRnaInitiationPositiveValid({...a,adoptedStrength:'MODERATE'},x,s,{originalStatus:'MET',originalStrength:'MODERATE'},'INITIATION_LOSS'),false);
    }
    assert.equal(api.fullRnaInitiationPositiveValid(a,i,s,{originalStatus:'MET',originalStrength:'MODERATE'},'INITIATION_LOSS'),false);
});
test('wire display only: ATG clue, local count and transcript completeness do not invent translation or a global absence',()=>{
    const v=clone(moduleView),i=v.details.initiation;i.earlierAlteredInframeMetRnaPosition=6;i.qualifyingReferenceCount=0;i.upstreamScopeComplete=false;
    const core=api.fullRnaDetails(v),rows=core.sections.flatMap(s=>s.rows);assert.equal(rows.find(r=>r.label==='更早的改变后同框 ATG 位置（RNA）').value,'6');
    assert.equal(rows.find(r=>r.label==='本次范围内的合格独立参考数').value,'0');assert.equal(core.rows.find(r=>r.label==='改变后蛋白长度（aa）').value,'尚未确定');
    assert.ok(!JSON.stringify(core).includes('全库'));assert.ok(!JSON.stringify(core).includes('已确认替代起始'));
});
assert.deepEqual(fs.readFileSync(rawFile),moduleBefore);
const result={status:'PASS_NORMAL_BACKGROUND_AND_MODULE_CONTRACTS',checks:checks.length,results:checks,pins,normalValidScenarios:validNames.length,normalRnaRows:normalRows,actualProviderOnlyPositiveModules:modulePositive,
    boundaries:{normalCompletedInitiationExclusion:'NOT_RUN_POLICY_BLOCKED_SOURCE_GENERATOR',normalPositiveInitiation:'NOT_AVAILABLE_EXISTING_PUBLIC_RUN',bareModulePublicViewAuthority:false,newAuthorityOrRequalification:false,scientificModelNetworkCalls:0,browser:'NOT_RUN'},
    note:'Known cap used for the isolated completion predicate is a wire constant only; no normal caller, currentBinding or root identity is created for module products.'};
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(`${checks.length} full RNA application checks: ${result.status}`);
