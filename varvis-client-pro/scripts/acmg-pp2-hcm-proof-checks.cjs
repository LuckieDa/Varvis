// Public transport verification only. Mutated positive fixtures do not issue a source review or clinical result.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {Module}=require('node:module'),{buildSync}=require('esbuild');
const root=path.resolve(__dirname,'..'),products=process.argv[2];
assert.ok(products,'Pass the existing normal HCM products directory');
const result=buildSync({stdin:{resolveDir:root,contents:`
export {pp2HcmCriterionValid} from './src/pages/varvis/ChromosomeVisualization/utils/acmgPp2HcmProof';
export {ps2IdentityHash,localBatchScopeIssue} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export {backgroundEvidenceCards} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';
export {buildEvidenceDetailView} from './src/pages/varvis/ChromosomeVisualization/utils/evidenceDetailView';
export {pp2HcmDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgPp2HcmDetails';
export {workbenchEvidenceCopy} from './src/pages/varvis/ChromosomeVisualization/utils/acmgWorkbenchCopy';
`},bundle:true,platform:'node',format:'cjs',packages:'external',write:false,logLevel:'silent'});
const mod=new Module(path.join(root,'scripts/hcm-proof-check-bundle.cjs'));mod.filename=mod.id;mod.paths=[path.join(root,'node_modules')];
mod._compile(result.outputFiles[0].text,mod.filename);const api=mod.exports;
const clone=value=>JSON.parse(JSON.stringify(value));
const freeze=value=>{if(value&&typeof value==='object'){Object.values(value).forEach(freeze);Object.freeze(value);}return value;};
const criterion=run=>run.candidates[0].attempts[0].localBatch.criteria.find(row=>row.criterionId==='ACMG_AMP_2015:PP2');
const expected=run=>({analysisId:run.analysisId,executionSourceSha256:run.executionSourceSha256,enabledCriteria:run.enabledCriteria,
    executionProfile:run.executionProfile,candidateId:run.candidates[0].candidateId,hypothesisId:run.candidates[0].attempts[0].hypothesisId,
    context:run.candidates[0].attempts[0].context,pp2OtherPhenotypeSourceIdentity:run.localBatch.pp2OtherPhenotypeSourceIdentity});
let checks=0;
const check=(name,fn)=>{fn();checks++;console.log('PASS '+name);};
let actual;
for(const name of ['MISSING_CONFIGURATION_PP2','UNRESOLVED_SCOPE_PP2','RELATED','CURRENT18']){
    for(const fileName of ['expected-controller-full.json','reader-index.json','reader-background.json'])check(name+' '+fileName+' is read-only and accepted by the formal workbench',()=>{
        const file=path.join(products,name,fileName),bytes=fs.readFileSync(file),run=freeze(JSON.parse(bytes)),before=JSON.stringify(run);
        assert.equal(api.localBatchScopeIssue(run),null);
        if(fileName!=='reader-index.json'){
            const cards=api.backgroundEvidenceCards(run.candidates[0],run.enabledCriteria,run,0),card=cards.find(row=>row.code==='PP2'),entry=card.entries[0];
            assert.equal(Boolean(entry.displayIssue),false);
            const view=api.buildEvidenceDetailView(card,entry,run.candidates[0].attempts[0].context),copy=api.workbenchEvidenceCopy(card,entry);
            assert.ok(view.title.includes('肥厚型心肌病'));assert.ok(view.fields.length>=6);assert.ok(view.comparisons.length>0);
            assert.ok(copy.summary.includes('疾病表型'));assert.ok(!copy.summary.includes('请维护者'));assert.equal(view.adopted,false);
            if(name==='UNRESOLVED_SCOPE_PP2'&&fileName==='expected-controller-full.json')actual=clone(run);
        }
        assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(file),bytes);
    });
}
assert.ok(actual);
function valid(c,run=actual){return api.pp2HcmCriterionValid(c,c.details.pp2Facts,run.executionProfile,expected(run),api.ps2IdentityHash);}
check('actual NE context and source proof are accepted without upgrading status',()=>{
    const c=freeze(clone(criterion(actual))),before=JSON.stringify(c);assert.equal(valid(c),true);
    assert.equal(c.status,'NOT_EVALUATED');assert.equal(c.appliedStrength,'NONE');assert.equal(JSON.stringify(c),before);
});
const mutations={
    'another candidate':f=>{f.otherPhenotypeReview.binding.candidateId='another';},
    'another background':f=>{f.otherPhenotypeReview.binding.hypothesisId='another';},
    'another source observation':f=>{f.otherPhenotypeReview.binding.sourceSnapshotSha256='a'.repeat(64);},
    'changed actual model':f=>{f.hcmPolicyBinding.executionModelSha256='a'.repeat(64);},
    'changed published clause':f=>{f.hcmSourceMethod.sourceBinding.clauseSha256='a'.repeat(64);},
    'claimed RefSeq equivalence':f=>{f.sourceTranscriptScope.refseqEquivalenceGranted=true;},
    'unknown source method':f=>{f.methodVersion='99.0.0';},
};
for(const [name,mutate] of Object.entries(mutations))check('reject '+name+' even when the surrounding saved object is readable',()=>{
    const c=clone(criterion(actual));mutate(c.details.pp2Facts);assert.equal(valid(c),false);
});
function positiveControl(){
    const c=clone(criterion(actual)),review=c.details.pp2Facts.otherPhenotypeReview;
    c.status='MET';c.appliedStrength='SUPPORTING';c.reasonCode='PP2_HCM_NO_OTHER_PHENOTYPE_SUPPORT_IN_ASSESSED_SCOPE';
    review.outcome='NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE';review.pending=[];review.conflicts=[];review.qualifiedEvidence=[];
    review.requiredScope.forEach(row=>{row.status='COMPLETED_DECLARED_TARGET_SCOPE';if(row.nativeSourceCoverage){row.nativeSourceCoverage.status='COMPLETE';row.nativeSourceCoverage.pendingRows=0;}});return c;
}
check('synthetic saved-MET display control requires complete scopes and never computes a grade',()=>{
    const c=positiveControl();assert.equal(valid(c),true);assert.equal(c.appliedStrength,'SUPPORTING');
    assert.equal(Object.hasOwn(c,'classification'),false);
});
for(const [name,mutate] of Object.entries({
    'a pending record':r=>r.pending.push({reasonCode:'UNFINISHED'}),
    'an unfinished native scope':r=>{r.requiredScope[0].status='INCOMPLETE';},
    'contradicting native source coverage':r=>{r.requiredScope[0].nativeSourceCoverage.status='INCOMPLETE';},
    'missing native source coverage':r=>{delete r.requiredScope[0].nativeSourceCoverage;},
    'unequal target and qualified counts':r=>{r.requiredScope[0].targetRecords=2;r.requiredScope[0].qualifiedRecords=1;},
    'noninteger target count':r=>{r.requiredScope[0].targetRecords=0.5;r.requiredScope[0].qualifiedRecords=0.5;},
    'duplicate required scope':r=>r.requiredScope.push(clone(r.requiredScope[0])),
    'an extra unfinished condition':r=>r.requiredScope.push({scope:'UNFINISHED_NEW_CONDITION',status:'INCOMPLETE'}),
    'an unknown completed condition':r=>r.requiredScope.push({scope:'UNSUPPORTED_NEW_CONDITION',status:'COMPLETED_DECLARED_TARGET_SCOPE'}),
    'a paper not yet assessed by the v3 contract':r=>{r.requiredScope[1].candidatePapers=1;},
    'an unreviewed known citation':r=>{r.requiredScope[1].knownCitationReferences=['12345678'];},
    'contradicting other-phenotype evidence':r=>r.qualifiedEvidence.push({scv:'SCV000000001.1'}),
}))check('synthetic saved-MET control rejects '+name,()=>{
    const c=positiveControl();mutate(c.details.pp2Facts.otherPhenotypeReview);assert.equal(valid(c),false);
});
for(const version of ['pp2-other-phenotype-source-v2','pp2-other-phenotype-source-v99'])check('saved-MET control rejects unsupported positive source contract '+version,()=>{
    const c=positiveControl(),run=clone(actual);
    run.executionProfile.moduleBindings.pp2.runtimeBinding.pp2HcmExecution.sourceVersion=version;
    run.localBatch.pp2OtherPhenotypeSourceIdentity.methodVersion=version;
    c.details.pp2Facts.otherPhenotypeReview.methodVersion=version;
    assert.equal(valid(c,run),false);
});
for(const [name,mutate] of Object.entries({
    'missing source capability':binding=>{delete binding.pp2HcmExecution;},
    'unconfigured source':binding=>{binding.pp2HcmExecution.sourceConfigured=false;},
    'unknown observation lifecycle':binding=>{binding.pp2HcmExecution.lifecycleVersion='future';},
    'inconsistent source capability version':binding=>{binding.pp2HcmExecution.sourceCapability.methodVersion='pp2-other-phenotype-source-v99';},
}))check('saved-MET control rejects '+name,()=>{
    const c=positiveControl(),run=clone(actual);mutate(run.executionProfile.moduleBindings.pp2.runtimeBinding);assert.equal(valid(c,run),false);
});
const bounded='pp2-bounded-literature-qualification-v1';
function boundedControl(citationOnly=false){
    const c=positiveControl(),run=clone(actual),review=c.details.pp2Facts.otherPhenotypeReview;
    const cap=run.executionProfile.moduleBindings.pp2.runtimeBinding.pp2HcmExecution.sourceCapability;
    const scope=review.requiredScope.find(row=>row.scope==='TARGET_LITERATURE_RELATIONSHIP_REVIEW');
    cap.qualificationVersion=bounded;
    Object.assign(scope,{qualificationVersion:bounded,qualificationScopeStatus:'COMPLETE_DECLARED_SCOPE',retrievalScopeComplete:true,
        candidatePapers:citationOnly?0:1,knownCitationReferences:citationOnly?[{pmid:'456'}]:[],assessedPapers:1});
    review.binding.derivedAssessmentSha256='b'.repeat(64);
    review.reasonCode='PP2_NO_OTHER_PHENOTYPE_SUPPORT_IN_COMPLETED_DECLARED_SCOPE';
    const summary=review.literatureAssessment={methodVersion:'pp2-literature-assessment-v1',readingMethodVersion:'1.2.0',
        qualificationVersion:bounded,qualificationScopeStatus:'COMPLETE_DECLARED_SCOPE',scopeStatus:'COMPLETED_DECLARED_REVIEWED_SCOPE',
        readingScopeMeaning:'DECLARED_AVAILABLE_MAIN_AND_PARSED_ATTACHMENT_TEXT_UNITS_ONLY',wholePublicationRead:false,
        outcome:review.outcome,reasonCode:review.reasonCode,declaredCandidateCount:scope.candidatePapers,declaredKnownCitationCount:scope.knownCitationReferences.length,
        papersInScope:1,assessedPapers:1,unresolvedRecords:0,unresolvedUnits:0,unresolvedCitationCount:0,assessmentSha256:review.binding.derivedAssessmentSha256,
        readingLedgerRawSha256:'c'.repeat(64),qualificationLedgerRawSha256:'d'.repeat(64)};
    return {c,run,review,cap,scope,summary};
}
for(const citationOnly of [false,true])check(`bounded saved proof preserves ${citationOnly?'citation-only':'retrieved'} nonzero scope`,()=>{
    const {c,run}=boundedControl(citationOnly),before=JSON.stringify(c);
    assert.equal(valid(freeze(c),freeze(run)),true);assert.equal(JSON.stringify(c),before);
    assert.equal(c.appliedStrength,'SUPPORTING');assert.ok(!Object.hasOwn(c,'classification'));
});
for(const [name,mutate] of Object.entries({
    'wrong source qualification capability':({cap})=>{cap.qualificationVersion='future';},
    'old literal-only reading':({summary})=>{summary.readingMethodVersion='1.1.0';},
    'unfinished discovery scope':({scope})=>{scope.retrievalScopeComplete=false;},
    'missing qualification original':({summary})=>{delete summary.qualificationLedgerRawSha256;},
    'unbound derived assessment':({review})=>{review.binding.derivedAssessmentSha256='e'.repeat(64);},
    'incomplete relationship scope':({summary})=>{summary.qualificationScopeStatus='INCOMPLETE';},
    'unresolved observation':({summary})=>{summary.unresolvedRecords=1;},
    'unread citation':({summary})=>{summary.unresolvedCitationCount=1;},
    'incorrectly combined citation count':({summary})=>{summary.declaredKnownCitationCount=0;},
    'untrue assessed count':({scope})=>{scope.assessedPapers=0;},
    'whole-publication claim':({summary})=>{summary.wholePublicationRead=true;},
    'nonzero reading disguised as empty scope':({scope})=>{scope.knownCitationReferences=[];},
}))check('bounded saved proof rejects '+name,()=>{
    const control=boundedControl(true);mutate(control);assert.equal(valid(control.c,control.run),false);
});
if(process.argv[3])check('actual owner-produced finite HCM observation displays its saved clinical context without implying causality',()=>{
    const bytes=fs.readFileSync(process.argv[3]),review=freeze(JSON.parse(bytes)),before=JSON.stringify(review);
    assert.equal(review.outcome,'NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE');
    assert.equal(review.literatureAssessment.declaredCandidateCount,0);assert.equal(review.literatureAssessment.declaredKnownCitationCount,1);
    const facts={...criterion(actual).details.pp2Facts,otherPhenotypeReview:review};
    const view=api.pp2HcmDetails(facts,{mainKind:'ADOPTED_MET'}),rows=view.papers[0].cases.flatMap(item=>item.rows);
    assert.ok(rows.some(row=>row.label==='疾病关联核查'&&row.value==='已完成本次声明范围'));
    assert.ok(rows.some(row=>row.label==='文中病例背景'&&row.value==='已核实为 HCM 病程'));
    assert.ok(!view.summary.includes('变异导致'));assert.equal(JSON.stringify(review),before);assert.deepEqual(fs.readFileSync(process.argv[3]),bytes);
});
if(process.argv[4])for(const fileName of ['expected-controller-full.json','reader-index.json','reader-background.json'])check('actual bounded normal '+fileName+' keeps unresolved clinical attribution',()=>{
    const file=path.join(process.argv[4],'NONZERO_LITERATURE_PP2',fileName),bytes=fs.readFileSync(file),run=freeze(JSON.parse(bytes)),before=JSON.stringify(run);
    assert.equal(api.localBatchScopeIssue(run),null);
    if(fileName!=='reader-index.json'){
        const candidate=run.candidates[0],card=api.backgroundEvidenceCards(candidate,run.enabledCriteria,run,0).find(row=>row.code==='PP2'),entry=card.entries[0];
        assert.equal(Boolean(entry.displayIssue),false);
        const view=api.buildEvidenceDetailView(card,entry,candidate.attempts[0].context),records=view.papers[0].cases.filter(item=>item.label?.startsWith('资料记录'));
        assert.equal(view.adopted,false);assert.equal(records.length,3);
        const individual=records.find(record=>record.rows.some(row=>row.label==='资料类型线索'&&row.value==='个体病例资料'));
        assert.ok(individual.rows.some(row=>row.label==='变异对应核查'&&row.value==='已对应本次精确变异'));
        assert.ok(individual.rows.some(row=>row.label==='缺少信息'&&row.value.includes('明确将 HCM 诊断归于该病例')));
        assert.ok(!individual.rows.some(row=>row.label==='文中病例背景'));
    }
    assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(file),bytes);
});
console.log(`PASS ${checks} HCM formal display groups; immutable normal products retained; no source qualification or scoring executed`);
