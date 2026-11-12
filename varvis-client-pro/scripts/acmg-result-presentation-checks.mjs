// Four-conclusion read-only projection. Scientific rules are not run here.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {registerHooks} from 'node:module';
registerHooks({resolve(specifier, context, next) {
    return next(/^\.\/[a-zA-Z][^.\/]+$/.test(specifier) ? specifier + '.js' : specifier, context);
}});
const utils=new URL('../src/pages/varvis/ChromosomeVisualization/utils/',import.meta.url);
const api=await import(new URL('acmgResultsWorkbench.js',utils));
const input=await import(new URL('acmgInput.js',utils));
const {computedRuleRows}=await import(new URL('acmgComputedRuleDetails.js',utils));
let count=0;
const check=(name,fn)=>{fn();count++;console.log('PASS '+name);};
const entry=(status='NOT_EVALUATED',reasonCode='NEW_UNKNOWN_REASON',extra={})=>({evaluation:{criterionId:'ACMG_AMP_2015:PM2',appliedStrength:status==='MET'?'SUPPORTING':'NONE',evaluationResult:{status,reasonCode},...extra}});
const inspect=api.resultPresentation;
const deepFreeze=value=>{if(value&&typeof value==='object'){Object.freeze(value);Object.values(value).forEach(deepFreeze);}return value;};
check('exact four main labels are mutually exclusive',()=>assert.deepEqual(api.mainResultKinds.map(v=>v.label),['已采用满足','已评价未满足','不适用','尚无可采用结论']));
check('validated saved MET and strength adopt without rewriting old records',()=>{
    const e=deepFreeze(entry('MET','LOCAL_BATCH_CRITERION_MET')),before=JSON.stringify(e),p=inspect(e);
    assert.equal(p.mainKind,'ADOPTED_MET');assert.equal(p.adoption.kind,'ADOPTED');assert.equal(p.progress.kind,'EVALUATED');assert.equal(JSON.stringify(e),before);
});
for(const id of ['PM2','BP7','PVS1','PP1'])check(id+' review overrides an internal MET before main counts',()=>{
    const e=entry('MET','INTERNAL_COMPUTATION_MET',{criterionId:'ACMG_AMP_2015:'+id,reviewRequired:true});
    assert.equal(inspect(e).mainKind,'NO_CONCLUSION');assert.equal(inspect(e).outcome.kind,'REVIEW');assert.equal(inspect(e).adoption.kind,'REVIEW');
    assert.equal(api.resultOutcome(e).kind,'REVIEW');
});
for(const adoptionStatus of ['REVIEW','NOT_APPLIED','NOT_ADOPTED','FUTURE_UNKNOWN'])check('explicit '+adoptionStatus+' never adopts internal MET',()=>{
    const e=entry('MET','INTERNAL_COMPUTATION_MET',{adoptionStatus});assert.equal(inspect(e).mainKind,'NO_CONCLUSION');
});
check('a valid applied negative is distinct from no conclusion',()=>{
    const p=inspect(entry('NOT_MET','LOCAL_BATCH_CRITERION_NOT_MET'));assert.equal(p.mainKind,'NOT_MET');assert.equal(p.adoption.kind,'NOT_ADOPTED');assert.equal(p.progress.kind,'EVALUATED');
});
check('completed local BA1 retains its conclusion only with the saved selection and trace', () => {
    const dataScope = {policyId:'BA1_LOCAL_EXOMES_V1',version:'1.0.0',dataset:'EXOMES',release:'4.1'};
    const scope = 'GENERAL_BASELINE';
    const selection = {status:'SELECTED',scope,dataScope};
    selection.selectionId = 'ba1-local-selection-sha256-' + input.ps2IdentityHash(selection);
    const evidenceIds = ['population-evidence-sha256-' + 'b'.repeat(64)];
    const trace = {executionStatus:'COMPLETED',evaluatorId:'gnomad-population-criterion-evaluator',
        evaluatorVersion:'1.0.0-local-exomes-ba1',algorithmId:'GNOMAD_LOCAL_EXOMES_BA1_V1',algorithmVersion:'1.0.0-local-exomes-ba1',
        inputEvidenceIds:evidenceIds,steps:[
            {stepCode:'FREQUENCY_COMPARATOR_SATISFIED',outcome:'FALSE'},
            {stepCode:'SELECTED_ANCESTRY_AN_SUFFICIENT',outcome:'TRUE'},
            {stepCode:'EXACT_PASS_AND_READINESS_CONFIRMED',outcome:'TRUE'},
        ]};
    const deterministicTrace = {...trace,traceSha256:input.ps2IdentityHash(trace)};
    const identity = {schemaVersion:'ba1-allele-evaluation-v1',canonicalizationProfile:'varvis-json-c14n-v1',
        criterionId:'ACMG_AMP_2015:BA1',alleleId:'allele-sha256-'+'a'.repeat(64),scope,dataScope,
        ruleBinding:{dataPolicyId:dataScope.policyId,dataPolicyVersion:dataScope.version,ba1SelectionId:selection.selectionId},
        evaluationResult:{status:'NOT_MET'},appliedStrength:'NONE',criterionApplicability:{status:'APPLICABLE'},
        evidenceIds,deterministicTrace};
    const evaluation = {...identity,evaluationId:'ba1-allele-evaluation-sha256-'+input.ps2IdentityHash(identity),
        review:{status:'NOT_REQUIRED',decision:'NONE',reviewRecordIds:[]}};
    const scalar = {evaluation,raw:{evaluation,selection}};
    assert.equal(inspect(scalar).mainKind,'NOT_MET');
    const rows = computedRuleRows({code:'BA1',kind:'POPULATION',entry:scalar,presentation:inspect(scalar),
        fields:[{label:'质量',value:'FILTER PASS'}],facts:{},read:value=>value||'',
        ba1:{localExomes:true,frequency:'0.001',operator:'GREATER_THAN',threshold:'0.05',an:'10000'}});
    assert.deepEqual(rows.slice(0,3).map(row=>row.outcome),['未满足','满足','满足']);
    assert.equal(inspect({evaluation,raw:{evaluation,generalization:{selection}}}).mainKind,'NOT_MET');
    assert.equal(inspect({evaluation,raw:{evaluation,selection:{...selection,status:'BLOCKED'}}}).mainKind,'NO_CONCLUSION');
    assert.equal(inspect({evaluation,raw:{evaluation,selection:{...selection,dataScope:{...dataScope,release:'4.0'}}}}).mainKind,'NO_CONCLUSION');
    const altered = {...evaluation,deterministicTrace:{...deterministicTrace,traceSha256:'f'.repeat(64)}};
    assert.equal(inspect({evaluation:altered,raw:{evaluation:altered,selection}}).mainKind,'NO_CONCLUSION');
});
for(const target of ['PS2','PM6'])check('NOT_COUNTED names '+target+' and never becomes missing data or negative evidence',()=>{
    const p=inspect(entry('NOT_EVALUATED','DENOVO_SHARED_EVENT_ASSIGNED_TO_'+target));
    assert.equal(p.mainKind,'NO_CONCLUSION');assert.equal(p.outcome.kind,'NOT_COUNTED');assert.equal(p.adoption.kind,'NOT_COUNTED');assert.equal(p.adoption.targetCriterion,target);
    assert.equal(p.adoption.label, `归属 ${target}`);
    assert.ok(p.adoption.next.includes(target));
});
check('actual NOT_APPLICABLE status and applicability each retain explicit scope',()=>{
    for(const e of [entry('NOT_APPLICABLE','LOCAL_BATCH_CRITERION_NOT_APPLICABLE'),entry('NOT_EVALUATED','ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE',{criterionApplicability:{status:'NOT_APPLICABLE'}})]){
        const p=inspect(e);assert.equal(p.mainKind,'NOT_APPLICABLE');assert.equal(p.progress.kind,'RULE_EXCLUDED');
    }
});
check('exact registered NA and BA1 exception can explain historical exclusion',()=>{
    const definition=Object.entries(input.evidenceReasonDefinitions).find(([,v])=>v.category==='NOT_APPLICABLE');assert.ok(definition);
    assert.equal(inspect(entry('NOT_EVALUATED',definition[0])).mainKind,'NOT_APPLICABLE');
    assert.equal(inspect(entry('NOT_EVALUATED','BA1_EXCEPTION_HIT',{criterionId:'ACMG_AMP_2015:BA1'})).mainKind,'NOT_APPLICABLE');
});
check('unknown reason suffix is not a scientific nonapplicability decision',()=>{
    const e=entry('NOT_EVALUATED','FUTURE_BRANCH_NOT_APPLICABLE');assert.equal(inspect(e).mainKind,'NO_CONCLUSION');assert.equal(inspect(e).outcome.kind,'UNRESOLVED');
});
check('inconsistent MET/applicability or missing strength cannot count as adopted',()=>{
    for(const extra of [{criterionApplicability:{status:'NOT_APPLICABLE'}},{appliedStrength:'NONE'},{appliedStrength:undefined},{appliedStrength:'FUTURE'}])assert.equal(inspect(entry('MET','LOCAL_BATCH_CRITERION_MET',extra)).mainKind,'NO_CONCLUSION');
});
check('display identity failure does not become missing clinical data',()=>{
    const e={...entry('MET','LOCAL_BATCH_CRITERION_MET'),displayIssue:true};
    assert.equal(inspect(e).mainKind,'NO_CONCLUSION');assert.equal(inspect(e).adoption.kind,'DISPLAY_UNVERIFIED');assert.equal(inspect(e).outcome.kind,'UNRESOLVED');
});
check('unsupported saved method stays a display issue and retains original evaluation',()=>{
    const e={...entry('MET','LOCAL_BATCH_CRITERION_MET'),displayIssue:true,displayOutcome:{kind:'UNSUPPORTED',label:'当前页面暂不支持此结果方法'}};
    assert.equal(inspect(e).mainKind,'NO_CONCLUSION');assert.equal(inspect(e).outcome.kind,'UNSUPPORTED');assert.equal(inspect(e).progress.kind,'DISPLAY_REVIEW');
    assert.equal(e.evaluation.evaluationResult.status,'MET');
});
for(const [reasonCode,wanted]of [['SOURCE_CONFLICT','CONFLICT'],['SOURCE_FAILED','EXECUTION_FAILED'],['SOURCE_NOT_CONFIGURED','CONFIGURATION'],['SOURCE_UNSUPPORTED','UNSUPPORTED']])check('row-visible '+wanted+' stays precise under NO_CONCLUSION',()=>{
    const p=inspect(entry('NOT_EVALUATED',reasonCode));assert.equal(p.mainKind,'NO_CONCLUSION');assert.equal(p.diagnostics[0].kind,wanted);assert.equal(p.reasonCode,reasonCode);
});
check('free Chinese explanation cannot manufacture scientific state or missing needs',()=>{
    const e={...entry(),summary:'已采用、资料不足、不适用、运行中'};assert.equal(inspect(e).outcome.kind,'UNRESOLVED');assert.equal(inspect(e).mainKind,'NO_CONCLUSION');assert.equal(inspect(e).progress.kind,'UNRESOLVED');
});
check('missing or malformed optional records stay nonpositive and do not throw',()=>{
    for(const e of [undefined,null,{},'MET',{evaluation:null},{evaluation:[]},{evaluation:{evaluationResult:{}}}])assert.equal(inspect(e).mainKind,'NO_CONCLUSION');
});
check('missing scientific identity/reason and unknown status cannot create any scored main conclusion',()=>{
    for(const e of [entry('MET',''),entry('MET','LOCAL',{criterionId:undefined}),entry('MET','LOCAL',{reviewRequired:'true'}),
        {...entry('MET','LOCAL',{adoptionStatus:'NOT_APPLIED'}),adoptionStatus:'ADOPTED'},entry('NOT_MET',''),
        entry('UNKNOWN','LOCAL',{criterionApplicability:{status:'NOT_APPLICABLE'}})]) {
        assert.equal(inspect(e).mainKind,'NO_CONCLUSION');assert.equal(inspect(e).outcome.kind,'UNRESOLVED');
    }
});
check('four counts conserve all scoped rows; adoption/progress do not add rows',()=>{
    const values=[entry('MET'),entry('NOT_MET'),entry('NOT_APPLICABLE'),entry('MET','INTERNAL',{reviewRequired:true}),entry('NOT_EVALUATED','DENOVO_SHARED_EVENT_ASSIGNED_TO_PS2'),entry(),{displayIssue:true},entry('NOT_EVALUATED','SOURCE_FAILED')];
    const rows=values.map(e=>({entry:e,outcome:{kind:'MET'}})),before=JSON.stringify(rows),counts=api.presentationCounts(rows);
    assert.equal(counts.reduce((n,c)=>n+c.count,0),rows.length);assert.deepEqual(counts.map(c=>c.count),[1,1,1,5]);assert.equal(JSON.stringify(rows),before);
    assert.equal(api.presentationCounts([]).reduce((n,c)=>n+c.count,0),0);
});
check('sorting prioritizes adopted evidence, not REVIEW internal MET, and is stable/read-only',()=>{
    const rows=[{entry:entry('MET','INTERNAL',{reviewRequired:true}),index:0},{entry:entry('NOT_MET'),index:1},{entry:entry('MET'),index:2},{entry:entry('MET'),index:3}];
    const before=JSON.stringify(rows),sorted=api.sortResultRows(rows);
    assert.deepEqual(sorted.map(v=>v.index),[2,3,1,0]);assert.equal(JSON.stringify(rows),before);assert.ok(sorted.every(v=>rows.includes(v)));
});
check('legacy secondary 17 styles/counts and outcome-only sort contract remain available',()=>{
    assert.equal(api.resultStatuses.length,17);for(const item of api.resultStatuses)assert.equal(api.resultStatusCounts([{outcome:{kind:item.kind}}]).find(v=>v.kind===item.kind).count,1);
    const kinds=['NOT_APPLICABLE','MISSING','MET','CONFLICT','NOT_MET','MET','FUTURE_STATUS','REVIEW'];
    assert.deepEqual(api.sortResultRows(kinds.map((kind,index)=>({outcome:{kind},index}))).map(v=>v.index),[2,5,4,3,7,6,1,0]);
});

const jointSample=process.env.VARVIS_NATIVE_ROOT && path.join(process.env.VARVIS_NATIVE_ROOT,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a10/round3/public-samples-02/a3aeabe87f/1-public.json');
if(jointSample){
    const bytes=fs.readFileSync(jointSample),run=JSON.parse(bytes),before=JSON.stringify(run),candidate=run.candidates[0];
    assert.equal(candidate.attempts.length,2);
    for(const [index,expected]of [[0,'ADOPTED_MET'],[1,'NOT_MET']])check('captured Java JOINT background '+index+' completed atomic v2 without optional reason retains '+expected,()=>{
        const e=candidate.attempts[index].bs1.evaluation;
        assert.equal(Object.hasOwn(e.evaluationResult,'reasonCode'),false);
        const p=inspect({evaluation:e});assert.equal(p.mainKind,expected);assert.equal(p.progress.kind,'EVALUATED');
        const cards=api.backgroundEvidenceCards(candidate,run.enabledCriteria,run,index),rows=cards.flatMap(card=>card.entries.map(entry=>({entry})));
        assert.equal(api.presentationCounts(rows).find(row=>row.kind===expected).count,1);
    });
    const original=candidate.attempts[0].bs1.evaluation;
    for(const [name,mutate]of [
        ['evaluation identity',e=>e.evaluationId='evaluation-sha256-'+'f'.repeat(64)],
        ['context identity',e=>e.contextId='context-sha256-'+'f'.repeat(64)],
        ['trace digest',e=>e.deterministicTrace.traceSha256='f'.repeat(64)],
        ['trace not completed',e=>e.deterministicTrace.executionStatus='NOT_RUN'],
        ['missing trace',e=>delete e.deterministicTrace],
        ['source evidence substitution',e=>e.evidenceIds[0]='evidence-sha256-'+'f'.repeat(64)],
    ])check('synthetic copy of completed atomic v2 rejects '+name+' without a reason fallback',()=>{
        const e=structuredClone(original);mutate(e);assert.equal(inspect({evaluation:e}).mainKind,'NO_CONCLUSION');
    });
    const id='review-sha256-'+'a'.repeat(64);
    for(const status of ['REQUIRED','IN_PROGRESS'])check('atomic v2 '+status+' review blocks its internal MET',()=>{
        const e=structuredClone(original);e.review={status,decision:'NONE',reviewRecordIds:[id]};
        const p=inspect({evaluation:e});assert.equal(p.mainKind,'NO_CONCLUSION');assert.equal(p.outcome.kind,'REVIEW');assert.equal(p.adoption.kind,'REVIEW');
    });
    for(const decision of ['REJECTED','OVERRIDDEN'])check('atomic v2 completed '+decision+' review never silently adopts old internal MET',()=>{
        const e=structuredClone(original);e.review={status:'COMPLETED',decision,reviewRecordIds:[id]};
        const p=inspect({evaluation:e});assert.equal(p.mainKind,'NO_CONCLUSION');assert.equal(p.adoption.kind,decision);
    });
    for(const [name,review]of [
        ['unknown status',{status:'UNKNOWN_FUTURE',decision:'NONE',reviewRecordIds:[]}],
        ['unknown decision',{status:'COMPLETED',decision:'APPROVED_FUTURE',reviewRecordIds:[id]}],
        ['required missing record',{status:'REQUIRED',decision:'NONE',reviewRecordIds:[]}],
        ['notrequired nonnone',{status:'NOT_REQUIRED',decision:'ACCEPTED',reviewRecordIds:[id]}],
        ['bad record identity',{status:'COMPLETED',decision:'ACCEPTED',reviewRecordIds:['wrong']}],
    ])check('atomic v2 malformed review '+name+' remains unverified',()=>{
        const e=structuredClone(original);e.review=review;const p=inspect({evaluation:e});
        assert.equal(p.mainKind,'NO_CONCLUSION');assert.equal(p.outcome.kind,'UNRESOLVED');
    });
    check('actual JOINT capture object and original bytes remain unchanged',()=>{
        assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(jointSample),bytes);
    });
    console.log('FROZEN actual JOINT Java public JSON SHA256 '+createHash('sha256').update(bytes).digest('hex'));
}else console.log('NOT_RUN actual JOINT Java public sample; set VARVIS_NATIVE_ROOT.');

const archived=process.argv[2] || (process.env.VARVIS_NATIVE_ROOT&&path.join(process.env.VARVIS_NATIVE_ROOT,'local/development/handoffs/PP1-general-context-local-cache-20261028/dev-01/ordinary-save-before.json'));
if(archived){
    const bytes=fs.readFileSync(archived),run=JSON.parse(bytes),before=JSON.stringify(run);
    for(const candidate of run.candidates){
        check('captured archived '+candidate.candidateId+': each saved background has conserving independent counts',()=>{
            for(let index=0;index<api.savedBackgrounds(candidate).length;index++){
                const cards=api.backgroundEvidenceCards(candidate,run.enabledCriteria,run,index),rows=cards.flatMap(card=>card.entries.map(e=>({card,entry:e})));
                assert.equal(rows.length,run.enabledCriteria.length);assert.equal(api.presentationCounts(rows).reduce((n,c)=>n+c.count,0),rows.length);
                assert.ok(rows.every(({entry:e})=>!e.displayIssue));
            }
        });
    }
    check('invalid run profile exposes one technical review row per unique requested criterion, not zero scope',()=>{
        const bad=structuredClone(run);bad.executionProfile.profileId='SYNTHETIC_UNRECOGNIZED_PROFILE';
        const criteria=[...bad.enabledCriteria,bad.enabledCriteria[0]],cards=api.backgroundEvidenceCards(bad.candidates[0],criteria,bad,0);
        assert.deepEqual(cards.map(card=>card.id),bad.enabledCriteria);assert.equal(cards.length,18);
        const rows=cards.flatMap(card=>card.entries.map(entry=>({entry})));
        assert.equal(rows.length,18);assert.ok(rows.every(({entry})=>entry.displayIssue&&entry.evaluation===undefined));
        assert.ok(rows.every(({entry})=>inspect(entry).outcome.kind!=='MISSING'));
        assert.equal(api.presentationCounts(rows).find(c=>c.kind==='NO_CONCLUSION').count,18);
        assert.equal(api.presentationCounts(rows).reduce((n,c)=>n+c.count,0),18);
    });
    check('missing saved result is visible for every requested criterion and retains requested order',()=>{
        const empty={candidateId:'SYNTHETIC_EMPTY',attempts:[{}]},criteria=['ACMG_AMP_2015:PP1','ACMG_AMP_2015:PVS1'];
        const bad={...run,executionProfile:{profileId:'SYNTHETIC_UNRECOGNIZED_PROFILE'}};
        const cards=api.backgroundEvidenceCards(empty,criteria,bad,0),rows=cards.flatMap(card=>card.entries.map(entry=>({entry})));
        assert.deepEqual(cards.map(card=>card.id),criteria);assert.equal(rows.length,2);
        assert.ok(rows.every(({entry})=>entry.displayIssue&&entry.evaluation===undefined));
        assert.equal(api.presentationCounts(rows).find(c=>c.kind==='NO_CONCLUSION').count,2);
    });
    check('missing background has zero scope; another background is never a fallback',()=>{
        const c=run.candidates[0];assert.deepEqual(api.backgroundEvidenceCards(c,run.enabledCriteria,run,api.savedBackgrounds(c).length),[]);
    });
    check('wrong selected hypothesis becomes display review, never borrowed MET or missing clinical data',()=>{
        const c=structuredClone(run.candidates[0]),a=c.attempts[0];a.hypothesisId='SYNTHETIC_WRONG_BACKGROUND';
        const cards=api.backgroundEvidenceCards(c,run.enabledCriteria,run,0),rows=cards.flatMap(card=>card.entries.map(e=>({entry:e})));
        assert.equal(api.presentationCounts(rows).find(v=>v.kind==='ADOPTED_MET').count,0);
        assert.ok(rows.filter(({entry:e})=>e.displayIssue).every(({entry:e})=>inspect(e).outcome.kind!=='MISSING'));
        // Allele-only population records can remain valid independently of a disease hypothesis; retain their saved reasons.
    });
    check('captured archive object and full bytes remain unchanged',()=>{assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(archived),bytes);});
    console.log('FROZEN actual archived Java public JSON SHA256 '+createHash('sha256').update(bytes).digest('hex'));
}else console.log('NOT_RUN actual archived public JSON; supply readonly archive or VARVIS_NATIVE_ROOT.');
console.log(count+' main-result projection checks passed; synthetic control and captured Java display layers remain separate.');
