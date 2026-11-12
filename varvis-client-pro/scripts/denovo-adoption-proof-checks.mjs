// Read-only committed Java captures plus independent synthetic mutations.
// Captured policies/originals are SYNTHETIC; no Java/resource/clinical execution.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {registerHooks} from 'node:module';
const root=process.env.VARVIS_NATIVE_ROOT;if(!root)throw Error('Set VARVIS_NATIVE_ROOT.');
const base=path.join(root,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a6-a8/round4');
const contractBytes=fs.readFileSync(path.join(base,'public-contract.json')),contract=JSON.parse(contractBytes);
const indexBytes=fs.readFileSync(path.join(base,'actual-sample-index.json')),index=JSON.parse(indexBytes);
registerHooks({resolve(specifier, context, next) {
    return next(/^\.\/[a-zA-Z][^.\/]+$/.test(specifier) ? specifier + '.js' : specifier, context);
}});
const utils=new URL('../src/pages/varvis/ChromosomeVisualization/utils/',import.meta.url);
const api=await import(new URL('acmgDeNovoMethods.js',utils));
const input=await import(new URL('acmgInput.js',utils));
const ids=['ACMG_AMP_2015:PS2','ACMG_AMP_2015:PM6'];
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
let count=0,rawSummaries=0,businessRows=0,refusals=0;const captured=[],business=[];
const check=(name,fn)=>{fn();count++;console.log('PASS '+name);};
check('published contract and generator/review commits exist without merging',()=>{
    for(const sha of [contract.sourceHead,contract.actualSampleGeneratorHead,contract.verifiedDeliveryHead,index.verifiedHead])execFileSync('git',['cat-file','-e',sha+'^{commit}']);
    assert.equal(contract.proofSchemaVersion,'denovo-adoption-proof-v1');
});
for(const item of index.samples){
    const file=path.join(base,item.file),bytes=fs.readFileSync(file),document=JSON.parse(bytes),before=JSON.stringify(document);
    check('captured '+item.file+' pinned original bytes and public method witness',()=>{
        assert.equal(bytes.length,item.bytes);assert.equal(hash(bytes),item.sha256);
        if(document.publicResult){
            const run=document.publicResult;
            for(const candidate of run.candidates)for(const attempt of candidate.attempts){
                const rows=attempt.localBatch.criteria.filter(row=>ids.includes(row.criterionId));
                if(!rows.length)continue;
                const expected=run.enabledCriteria.filter(id=>ids.includes(id));
                const result=api.inspectDeNovoMethodsPair(rows,run.executionProfile,expected,{validCoreMaterial:input.validCoreMaterial,context:attempt.context});
                assert.equal(result.valid,true,JSON.stringify(result));businessRows++;
                assert.equal(rows.find(row=>row.criterionId.endsWith(':PS2')).status,'MET');
                const p=rows[0].details.deNovoFacts.adoptionProof;
                assert.equal(p.appliedStrength,item.file.includes('TISSUE_')?'STRONG':'SUPPORTING');
                captured.push({facts:structuredClone(rows[0].details.deNovoFacts),profile:run.executionProfile,expected});business.push({rows:structuredClone(rows),profile:run.executionProfile,expected,context:attempt.context});
            }
        } else if(document.all?.publicSummary){
            for(const mode of ['all','PS2Only','PM6Only'])if(document[mode]){
                const selected=document[mode],facts=selected.publicSummary,expected=Object.values(selected.criteria).map(row=>row.criterionId);
                const profile={moduleBindings:{deNovo:{calculatorVersion:selected.calculationBindings[0]?.runtimeCalculatorVersion}}};
                // This checks the actual proof/receipt projection. The minimal
                // runtime view is NOT a complete saved business execution profile.
                const result=api.inspectDeNovoAdoptionProof(facts,profile,expected);
                assert.equal(result.valid,true,JSON.stringify(result));rawSummaries++;
                captured.push({facts:structuredClone(facts),profile,expected});
                const positive={TISSUE_BOTH:'STRONG',TISSUE_C1:'MODERATE',TISSUE_C2:'MODERATE',COMBINED_ASSUMED:'SUPPORTING',GENERAL_ASSUMED:'SUPPORTING'}[document.scenario];
                if(positive){assert.equal(facts.status,'MET');assert.equal(facts.adoptionProof.appliedStrength,positive);}
                else assert.notEqual(facts.status,'MET');
                if(mode==='PM6Only'&&positive&&document.scenario!=='GENERAL_ASSUMED'){assert.equal(selected.criteria.PS2,undefined);assert.equal(selected.criteria.PM6.evaluationResult.status,'NOT_EVALUATED');}
            }
        } else if(item.evidenceLevel==='ACTUAL_LEGACY_BUSINESS_REJECTION_SYNTHETIC_REQUEST'){
            assert.equal(document.httpStatus,400);assert.equal(document.actualErrorReason,'ACMG_SELECTION_REQUIRES_CURRENT_WORKBENCH');assert.equal(document.deNovoEvaluations,0);refusals++;
        }
        assert.equal(JSON.stringify(document),before);assert.deepEqual(fs.readFileSync(file),bytes);
    });
}
assert.ok(rawSummaries>0);assert.equal(businessRows,2);assert.equal(refusals,4);
const tissue=captured.find(row=>row.facts.adoptionProof?.events?.[0]?.conditionBranch==='C1_AND_C2');
const combined=captured.find(row=>row.facts.adoptionProof?.policy?.eventMode==='SVI_1_1_COMBINED_PS2'&&row.facts.status==='MET');
const c2only=captured.find(row=>row.facts.adoptionProof?.events?.[0]?.conditionBranch==='PARENTS_UNAVAILABLE_AND_C2');
const inspect=v=>api.inspectDeNovoAdoptionProof(v.facts,v.profile,v.expected);
for(const [name,mutate] of [
    ['unknown schema',v=>v.facts.adoptionProof.schemaVersion='FUTURE'],
    ['incomplete projection',v=>v.facts.adoptionProof.projectionStatus='INCOMPLETE'],
    ['arbitrary strength',v=>v.facts.adoptionProof.appliedStrength='VERY_STRONG'],
    ['wrong event binding',v=>v.facts.adoptionProof.events[0].ordinal=2],
    ['missing source clauses',v=>v.facts.adoptionProof.policy.sources=[]],
    ['wrong adopted mode',v=>v.facts.specializedPolicyAdoptions[0].eventMode='SVI_1_1_COMBINED_PS2'],
    ['wrong global selection',v=>v.facts.specializedPolicyAdoptions[0].globalSelectionId='cspec-selection-sha256-'+'f'.repeat(64)],
    ['execution missing',v=>delete v.facts.calculationBindings[0].executedCalculatorVersion],
    ['runtime substituted',v=>v.facts.calculationBindings[0].runtimeCalculatorVersion='denovo-event-calculator-1.3.0'],
    ['conflicting declared calculator',v=>v.facts.calculationBindings[0].declaredCalculatorVersion='denovo-event-calculator-1.1.0'],
    ['retained facts mislabeled primary paper',v=>v.facts.adoptionProof.events[0].c1.sources[0].role='PAPER_PRIMARY'],
    ['retained facts foreign event',v=>v.facts.adoptionProof.events[0].c1.sources[0].sourceSha256='f'.repeat(64)],
    ['C1 mother present',v=>v.facts.adoptionProof.events[0].c1.motherTarget='PRESENT'],
    ['C1 unconfirmed',v=>v.facts.adoptionProof.events[0].c1.maternity='ASSUMED_NOT_TESTED'],
    ['C1 inadequate assay',v=>v.facts.adoptionProof.events[0].c1.detectionCapability='INADEQUATE'],
    ['C2 equal fractions',v=>v.facts.adoptionProof.events[0].c2.observations[1].altReads=20],
    ['C2 different people',v=>v.facts.adoptionProof.events[0].c2.samePerson=false],
    ['C2 same tissue',v=>v.facts.adoptionProof.events[0].c2.distinctTissues=false],
    ['C2 inadequate zero-read sensitivity',v=>v.facts.adoptionProof.events[0].c2.observations[1].limit=0.8],
    ['C2 missing read counts',v=>delete v.facts.adoptionProof.events[0].c2.observations[0].altReads],
    ['C2 unsafe counts',v=>v.facts.adoptionProof.events[0].c2.observations[0].totalReads=Number.MAX_SAFE_INTEGER+1],
    ['C2 quality unqualified',v=>v.facts.adoptionProof.events[0].c2.observations[0].quality='UNKNOWN'],
    ['COUNTED phenotype inconsistent',v=>{v.facts.events[0].phenotypeCategories=['NOT_CONSISTENT'];v.facts.adoptionProof.events[0].phenotypeCategory='NOT_CONSISTENT';}],
])check('synthetic mutation rejects '+name,()=>{const value=structuredClone(tissue);mutate(value);assert.equal(inspect(value).valid,false,name);});
check('C2-only must have actually unavailable parents and cannot invent confirmed parentage',()=>{
    const value=structuredClone(c2only);value.facts.adoptionProof.events[0].parentalAvailability.value='AVAILABLE';assert.equal(inspect(value).valid,false);
    value.facts.adoptionProof.events[0].parentalAvailability.value='UNAVAILABLE';value.facts.adoptionProof.events[0].c1.motherTarget='ABSENT_QUALIFIED';assert.equal(inspect(value).valid,false);
});
check('combined PS2 retains assumed parentage and cannot fabricate CONFIRMED or remove source-bound assumed permission',()=>{
    for(const mutate of [p=>p.events[0].combined.allowAssumedParentage=false,p=>p.events[0].combined.confirmedParentage=true,p=>p.events[0].combined.rawQuarterPoints=8]){
        const value=structuredClone(combined);mutate(value.facts.adoptionProof);assert.equal(inspect(value).valid,false);
    }
    assert.equal(combined.facts.adoptionProof.events[0].combined.confirmedParentage,false);
});
check('fixed tissue maximum remains a maximum instead of a sum of independent Strong events',()=>{
    const value=structuredClone(tissue),f=value.facts,p=f.adoptionProof;
    f.events.push({...structuredClone(f.events[0]),ordinal:2});f.eventCount=2;p.events.push({...structuredClone(p.events[0]),ordinal:2});p.totalEventCount=2;
    assert.equal(inspect(value).valid,true);assert.equal(p.appliedStrength,'STRONG');p.appliedStrength='VERY_STRONG';assert.equal(inspect(value).valid,false);
});
check('large integer ratios compare by exact cross-products without overflow or low-VAF cutoff',()=>{
    const value=structuredClone(tissue),obs=value.facts.adoptionProof.events[0].c2.observations;
    obs[0].altReads=9007199254740;obs[0].totalReads=900719925474000;obs[0].limit=0.00001;
    obs[1].altReads=0;obs[1].totalReads=900719925474000;obs[1].limit=0.00001;
    assert.equal(inspect(value).valid,true);
});
check('opaque original policy pins still have to match the actual event source references',()=>{
    const value=structuredClone(tissue),p=value.facts.adoptionProof;p.policy.phenotypePolicySha256='f'.repeat(64);
    value.facts.specializedPolicyAdoptions.forEach(row=>row.phenotypePolicySha256=p.policy.phenotypePolicySha256);
    value.facts.calculationBindings.forEach(row=>row.phenotypePolicySha256=p.policy.phenotypePolicySha256);
    assert.deepEqual(inspect(value),{valid:false,issues:['DENOVO_ADOPTION_POLICY_EVENT_BINDING_INVALID']});
});
check('tissue mode cannot be reclassified as SVI or award from zero points',()=>{
    const value=structuredClone(tissue);value.facts.calculationMethod='SVI_POINTS';value.facts.adoptionProof.calculationMethod='SVI_POINTS';
    assert.equal(inspect(value).valid,false);
});
check('phenotype-only proof preserves a known zero and an excluded missing phenotype in saved rows',()=>{
    const mode='SVI_1_1_PHENOTYPE_ONLY';
    for(const [status,disposition,category,eventReason] of [
        ['NOT_MET','ZERO_SUPPORT','NOT_CONSISTENT','DENOVO_PHENOTYPE_NOT_CONSISTENT'],
        ['NOT_EVALUATED','EXCLUDED','UNKNOWN','DENOVO_PHENOTYPE_BASIS_REQUIRED'],
    ]) {
        const value=structuredClone(business.find(sample=>sample.rows[0].details.deNovoFacts.adoptionProof.policy.eventMode==='SVI_1_1_COMBINED_PS2'));
        const f=value.rows[0].details.deNovoFacts,p=f.adoptionProof,excluded=disposition==='EXCLUDED';
        const reasonCode=excluded?'DENOVO_NO_QUALIFIED_OCCURRENCES':eventReason;
        const issues=excluded?[eventReason]:[];
        const event={ordinal:1,sourceKind:'LITERATURE_CASE',disposition,reasonCode:eventReason};
        f.specializedPolicyAdoptions.forEach(a=>{a.eventMode=mode;});
        Object.assign(f,{status,reasonCode,selectedCriterion:'NONE',rawQuarterPoints:0,effectiveQuarterPoints:0,
            hasExcludedObservations:excluded,eventCount:1,qualificationIssues:issues,
            events:[{...event,issues,phenotypeCategories:[category],confirmedParentage:false,
                sourceRefs:f.events[0].sourceRefs,rawQuarterPoints:0}]});
        p.policy.eventMode=mode;
        Object.assign(p,{status:'NOT_ADOPTED',selectedCriterion:'NONE',appliedStrength:'NONE',reasonCode,totalEventCount:1,
            events:[{...event,status:'NOT_ADOPTED',...(excluded?{}:{phenotypeCategory:category})}]});
        f.criterionExplanations.forEach(explanation=>Object.assign(explanation,{status,reasonCode,appliedStrength:'NONE'}));
        value.rows.forEach(row=>Object.assign(row,{status,reasonCode,appliedStrength:'NONE',details:{deNovoFacts:f}}));
        const result=api.inspectDeNovoMethodsPair(value.rows,value.profile,value.expected,{validCoreMaterial:input.validCoreMaterial,context:value.context});
        assert.equal(result.valid,true,JSON.stringify(result));
        f.rawQuarterPoints=4;
        assert.equal(api.inspectDeNovoAdoptionProof(f,value.profile,value.expected).valid,false);
    }
});
check('unexecuted proof and executed-version declarations remain different states',()=>{
    const value=structuredClone(business[0]),facts=value.rows[0].details.deNovoFacts;
    for(const key of ['selectedCriterion','calculationMethod','strengthAdjustment','rawQuarterPoints','effectiveQuarterPoints','highHeterogeneityRawQuarterPoints','highHeterogeneityEffectiveQuarterPoints','eventCount','hasExcludedObservations'])delete facts[key];
    Object.assign(facts,{status:'NOT_EVALUATED',reasonCode:'DENOVO_SYNTHETIC_UNEXECUTED',events:[],adoptionProof:{schemaVersion:'denovo-adoption-proof-v1',status:'NOT_RUN',projectionStatus:'NOT_AVAILABLE'}});
    facts.calculationBindings.forEach(binding=>delete binding.executedCalculatorVersion);
    for(const row of value.rows){row.status='NOT_EVALUATED';row.appliedStrength='NONE';row.reasonCode=facts.reasonCode;row.details.deNovoFacts=structuredClone(facts);}
    for(const row of value.rows)for(const explanation of row.details.deNovoFacts.criterionExplanations){explanation.status='NOT_EVALUATED';explanation.appliedStrength='NONE';explanation.reasonCode=facts.reasonCode;}
    const evaluate=()=>api.inspectDeNovoMethodsPair(value.rows,value.profile,value.expected,{validCoreMaterial:input.validCoreMaterial,context:value.context});
    assert.equal(evaluate().valid,true);
    for(const row of value.rows)row.details.deNovoFacts.calculationBindings[0].executedCalculatorVersion=value.profile.moduleBindings.deNovo.calculatorVersion;
    assert.deepEqual(evaluate(),{valid:false,issues:['DENOVO_UNEXECUTED_PROOF_CONTRADICTION']});
});
check('original contract/index bytes remain immutable',()=>{assert.deepEqual(fs.readFileSync(path.join(base,'public-contract.json')),contractBytes);assert.deepEqual(fs.readFileSync(path.join(base,'actual-sample-index.json')),indexBytes);});
console.log(`${count} DeNovo adoption-proof checks passed: ${rawSummaries} captured SelectedBatch proof summaries, ${businessRows} captured legacy-business rows, ${refusals} actual HTTP400 legacy refusals; all original sources/policies synthetic, no new execution.`);
