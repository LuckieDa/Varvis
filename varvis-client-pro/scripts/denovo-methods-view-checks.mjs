// Synthetic independent public-contract controls. Optional read-only JSON args
// must contain {rows, profile, expected?}; --discovery-receipt <path> checks an
// existing locator receipt separately. No Java execution is claimed here.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
const source = new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgDeNovoMethods.js', import.meta.url);
const api = await import(`data:text/javascript;base64,${fs.readFileSync(source).toString('base64')}`);
const {inspectDeNovoMethodsPair: inspect, validateDeNovoMethodsPair: valid, deNovoMethodsResultDetails: details, isDeNovoMethodsSummary: modern} = api;
const require=createRequire(import.meta.url), loader=require('./frontend-module-url.cjs');
const oldInput=await import(loader(`data:text/javascript;base64,${fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js',import.meta.url)).toString('base64')}`));
const IDS = ['ACMG_AMP_2015:PS2','ACMG_AMP_2015:PM6'], clone = structuredClone;
const H = 'a'.repeat(64), REF = `${H}#/report/testing`;
const profile = {moduleBindings:{deNovo:{version:'denovo-selected-batch-1.5.0',manifestSha256:H,use:'SYNTHETIC_VALIDATION',
    calculatorVersion:'denovo-event-calculator-1.2.0',qualifierVersion:'denovo-local-evidence-1.4.0',phenotypeVersion:'denovo-phenotype-matcher-1.1.0',
    readerVersion:'denovo-local-report-reader-1.3.0',publicExplanationVersion:'1.2.0',modelCalls:0,modelValidation:'DEFERRED_NOT_CONFIGURED',literatureQualification:'NOT_YET_CONNECTED',
    sexChromosomeRegion:{schemaVersion:'sex-chromosome-region-v1',methodVersion:'1.0.0',sourceCatalogSha256:H,consumer:'DeNovoA10RegionSupply',clinicalQualificationGranted:false,patientPloidyInferred:false}}}};
const event = (confirmed = true, phenotype = 'CONSISTENT', disposition = 'COUNTED') => ({ordinal:1,sourceKind:'CURRENT_CASE',disposition,
    reasonCode:disposition === 'COUNTED' ? 'DENOVO_EVENT_INCLUDED' : disposition === 'DUPLICATE' ? 'DENOVO_SAME_OCCURRENCE' : 'DENOVO_TARGET_TEST_CONFLICT',
    rawQuarterPoints:disposition === 'COUNTED' ? ({HIGHLY_SPECIFIC:8,CONSISTENT:4,CONSISTENT_HIGH_HETEROGENEITY:2}[phenotype] / (confirmed ? 1 : 2)) : 0,
    confirmedParentage:confirmed,issues:[],sourceRefs:[REF],phenotypeCategories:[phenotype]});
function fixture({events = [event()], adjustment='NONE', fixed=false, fixedStrength='STRONG', expected=IDS, mixed=false, unexecuted=false}={}) {
    events=clone(events); events.forEach((row,i)=>row.ordinal=i+1);
    const raw=events.reduce((n,e)=>n+e.rawQuarterPoints,0), hh=events.filter(e=>e.phenotypeCategories.includes('CONSISTENT_HIGH_HETEROGENEITY')).reduce((n,e)=>n+e.rawQuarterPoints,0);
    const effective=raw-hh+Math.min(hh,4), level=effective>=16?4:effective>=8?3:effective>=4?2:effective>=2?1:0;
    const finalLevel=adjustment==='ONE_LEVEL_AR_NO_SECOND_P_LP'?Math.max(0,level-1):level;
    const strength=fixed?fixedStrength:['NONE','SUPPORTING','MODERATE','STRONG','VERY_STRONG'][finalLevel];
    const met=strength!=='NONE'&&!mixed&&!unexecuted, status=mixed||unexecuted?'NOT_EVALUATED':met?'MET':'NOT_MET';
    const selected=met?(fixed||events.some(e=>e.disposition==='COUNTED'&&e.confirmedParentage)?'PS2':'PM6'):'NONE';
    const reason=mixed?'DENOVO_AR_MIXED_REDUCTION_RULE_NOT_IMPLEMENTED':unexecuted?'DENOVO_SELECTED_POLICY_BINDING_UNAVAILABLE':fixed?(met?'DENOVO_EXPERT_TISSUE_STRENGTH':'DENOVO_EXPERT_TISSUE_CONDITIONS_NOT_MET'):
        adjustment==='ONE_LEVEL_AR_NO_SECOND_P_LP'?'DENOVO_AR_ONE_LEVEL_REDUCTION':met?'DENOVO_QUALIFIED_POINTS':'DENOVO_BELOW_SUPPORTING';
    const f={schemaVersion:'denovo-selected-facts-v1',detailVersion:'1.2.0',sourceScope:'CURRENT_CASE_LOCAL_REPORTS',materialUse:'SYNTHETIC_VALIDATION',
        modelValidation:'DEFERRED_NOT_CONFIGURED',literatureQualification:'NOT_YET_CONNECTED',status,clinicalApproval:'NONE',completeLiteratureSearch:false,
        events:unexecuted?[]:events,qualificationIssues:[],coreMaterials:[]};
    if(unexecuted) f.reasonCode=reason;
    else Object.assign(f,{selectedCriterion:selected,rawQuarterPoints:raw,effectiveQuarterPoints:effective,highHeterogeneityRawQuarterPoints:hh,highHeterogeneityEffectiveQuarterPoints:Math.min(hh,4),
        eventCount:events.length,hasExcludedObservations:events.some(e=>e.disposition==='EXCLUDED'),calculationMethod:fixed?'EXPERT_FIXED_STRENGTH':'SVI_POINTS',...(!mixed?{strengthAdjustment:fixed?'NO_SVI_POINT_SUM':adjustment}:{})});
    const rows=expected.map(id=>({criterionId:id,status:met&&id!==`ACMG_AMP_2015:${selected}`?'NOT_EVALUATED':status,
        appliedStrength:met&&id===`ACMG_AMP_2015:${selected}`?strength:'NONE',reasonCode:met&&id!==`ACMG_AMP_2015:${selected}`?`DENOVO_SHARED_EVENT_ASSIGNED_TO_${selected}`:reason,
        reason:'Independent synthetic public-contract control',details:{}}));
    f.criterionExplanations=IDS.map(id=>{const row=rows.find(r=>r.criterionId===id);return {schemaVersion:'literature-decision-explanation-v1',criterionId:row?.criterionId||'',status:row?.status||'',
        reasonCode:row?.reasonCode||'',appliedStrength:row?.appliedStrength||'',basis:'SAVED_RULE_RESULT_NOT_MODEL_OPINION',summary:'Synthetic saved decision explanation'};});
    rows.forEach(row=>row.details.deNovoFacts=clone(f));
    return {rows,profile:clone(profile),expected:clone(expected)};
}
const facts=value=>value.rows[0].details.deNovoFacts;
function alter(value, change) {const f=facts(value);change(f);value.rows.forEach(row=>row.details.deNovoFacts=clone(f));}
function refreshExplanations(value) {alter(value,f=>{for(const e of f.criterionExplanations){const row=value.rows.find(r=>r.criterionId===e.criterionId);if(row)for(const key of ['status','reasonCode','appliedStrength'])e[key]=row[key];}});}
let count=0;
function check(name,fn){fn();count++;console.log(`PASS ${name}`);}
const accepted=value=>assert.deepEqual(inspect(value.rows,value.profile,value.expected),{valid:true,issues:[]});
const rejected=(value,issue)=>{const found=inspect(value.rows,value.profile,value.expected);assert.equal(found.valid,false,JSON.stringify(found));if(issue)assert.deepEqual(found.issues,[issue]);};
check('ordinary SVI confirmed PS2, public details and input remain immutable',()=>{
    const v=fixture(),before=JSON.stringify(v);accepted(v);assert.equal(valid(v.rows,v.profile,v.expected),true);assert.ok(details(v.rows[0]).some(([,text])=>text.includes('SVI')));assert.equal(JSON.stringify(v),before);
});
check('ordinary assumed parentage PM6 is selected once',()=>accepted(fixture({events:[event(false)]})));
for(const expected of [[IDS[0]],[IDS[1]]])check(`single selection ${expected[0]} retains two strict explanation slots`,()=>accepted(fixture({expected})));
for(const [n,strength] of [[1,'MODERATE'],[2,'STRONG'],[4,'VERY_STRONG']])check(`SVI effective threshold ${n} independent events => ${strength}`,()=>{
    const v=fixture({events:Array.from({length:n},()=>event())});accepted(v);assert.equal(v.rows[0].appliedStrength,strength);
});
for(const [n,strength] of [[1,'SUPPORTING'],[2,'MODERATE'],[4,'STRONG']])check(`AR one-level reduction ${n} events => ${strength} while raw points remain`,()=>{
    const v=fixture({events:Array.from({length:n},()=>({...event(),issues:['DENOVO_EVENT_KIND_CONSTITUTIONAL']})),adjustment:'ONE_LEVEL_AR_NO_SECOND_P_LP'});
    accepted(v);assert.equal(v.rows[0].appliedStrength,strength);assert.equal(facts(v).rawQuarterPoints,n*4);
    v.rows[0].appliedStrength=['NONE','SUPPORTING','MODERATE','STRONG','VERY_STRONG'][n===1?2:n===2?3:4];refreshExplanations(v);rejected(v,'DENOVO_ADJUSTED_ROW_MISMATCH');
});
check('AR Supporting becomes NOT_MET with original two quarter points retained',()=>{
    const v=fixture({events:[event(true,'CONSISTENT_HIGH_HETEROGENEITY')],adjustment:'ONE_LEVEL_AR_NO_SECOND_P_LP'});accepted(v);assert.equal(facts(v).effectiveQuarterPoints,2);assert.equal(facts(v).status,'NOT_MET');
});
check('high heterogeneity cap preserves raw six quarter points and effective four',()=>{
    const v=fixture({events:Array.from({length:3},()=>event(true,'CONSISTENT_HIGH_HETEROGENEITY'))});accepted(v);assert.equal(facts(v).rawQuarterPoints,6);assert.equal(facts(v).effectiveQuarterPoints,4);
});
check('duplicates do not add points and excluded prerequisites remain visible',()=>{
    const v=fixture({events:[event(),event(true,'CONSISTENT','DUPLICATE'),{...event(true,'CONSISTENT','EXCLUDED'),issues:['DENOVO_TARGET_TEST_CONFLICT']}]});accepted(v);
    assert.ok(details(v.rows[0]).some(([,text])=>text.includes('DENOVO_TARGET_TEST_CONFLICT')));
});
check('unexecuted selected-policy failure remains NE without invented method or points',()=>accepted(fixture({unexecuted:true})));
check('an unbound background has no saved de novo facts or adopted strength',()=>{
    const {profile,expected}=fixture();
    const rows=expected.map(criterionId=>({criterionId,status:'NOT_EVALUATED',appliedStrength:'NONE',reasonCode:'BINDING_NOT_READY',reason:'',details:{}}));
    assert.equal(inspect(rows,profile,expected,{context:null}).valid,true);
    assert.equal(inspect(rows,profile,expected,{context:{}}).valid,false);
    rows[0].appliedStrength='SUPPORTING';
    assert.equal(inspect(rows,profile,expected,{context:null}).valid,false);
});
check('mixed AR rule is readable NE with positive raw/effective points and omitted adjustment',()=>{
    const v=fixture({mixed:true});accepted(v);assert.ok(details(v.rows[0]).some(([label])=>label==='软件未实现'));
});
function fixedFixture(strength='STRONG') {
    return fixture({fixed:true,fixedStrength:strength,events:[{...event(),rawQuarterPoints:0,reasonCode:'DENOVO_EXPERT_TISSUE_STRENGTH',issues:['DENOVO_EVENT_KIND_POSTZYGOTIC']}]});
}
for(const strength of ['MODERATE','STRONG'])check(`fixed ${strength} zero points preserves saved value but requires public proof`,()=>{
    const v=fixedFixture(strength),before=JSON.stringify(v);rejected(v,'DENOVO_FIXED_STRENGTH_PUBLIC_PROOF_REQUIRED');
    const projected=details(v.rows[0]);assert.ok(projected.some(([,text])=>text.includes(`保存强度 ${strength}`)));assert.ok(projected.some(([label])=>label==='固定强度展示核验依赖'));assert.equal(JSON.stringify(v),before);
});
check('combined PS2 without confirmed event requires adopted policy proof',()=>{
    const v=fixture({events:[event(false)]});alter(v,f=>f.selectedCriterion='PS2');
    v.rows[0].status='MET';v.rows[0].appliedStrength='SUPPORTING';v.rows[0].reasonCode='DENOVO_QUALIFIED_POINTS';
    v.rows[1].status='NOT_EVALUATED';v.rows[1].appliedStrength='NONE';v.rows[1].reasonCode='DENOVO_SHARED_EVENT_ASSIGNED_TO_PS2';refreshExplanations(v);
    rejected(v,'DENOVO_COMBINED_PS2_PUBLIC_POLICY_PROOF_REQUIRED');assert.ok(details(v.rows[0]).some(([label])=>label==='组合 PS2 展示核验依赖'));
});
for(const [name,value] of [['object',{}],['string','not-events'],['null',null],['null-item',[null]]])check(`invalid saved events ${name} is rejected but remains safe to project`,()=>{
    const v=fixture();alter(v,f=>f.events=clone(value));const before=JSON.stringify(v);rejected(v);
    for(const row of v.rows)assert.doesNotThrow(()=>details(row));assert.equal(JSON.stringify(v),before);
});
check('invalid parentage boolean remains unverified in a rejected saved projection',()=>{
    const v=fixture();alter(v,f=>f.events[0].confirmedParentage='false');rejected(v);
    const eventText=details(v.rows[0]).find(([label])=>label==='事件 1')[1];assert.ok(eventText.includes('亲缘关系字段未核实'));assert.ok(!eventText.includes('亲缘关系已证实'));
});
const mutations=[
    ['unknown calculation method',f=>f.calculationMethod='UNIMPLEMENTED_METHOD'],['unknown strength adjustment',f=>f.strengthAdjustment='OTHER_ADJUSTMENT'],
    ['missing executed adjustment',f=>delete f.strengthAdjustment],['unknown summary schema',f=>f.schemaVersion='denovo-selected-facts-v2'],['unsupported detail major',f=>f.detailVersion='2.0.0'],
    ['legacy method metadata disguised as old detail',f=>f.detailVersion='1.1.0'],['float quarter points',f=>f.rawQuarterPoints=4.5],['negative effective points',f=>f.effectiveQuarterPoints=-1],
    ['changed raw sum',f=>f.rawQuarterPoints=8],['changed effective sum',f=>f.effectiveQuarterPoints=8],['changed heterogeneity raw sum',f=>f.highHeterogeneityRawQuarterPoints=1],
    ['changed capped sum',f=>f.highHeterogeneityEffectiveQuarterPoints=1],['wrong event count',f=>f.eventCount++],['wrong excluded flag',f=>f.hasExcludedObservations=true],
    ['unknown source kind',f=>f.events[0].sourceKind='UNVERIFIED_SOURCE'],['malformed source reference',f=>f.events[0].sourceRefs=[`${H}#javascript:bad`]],
    ['missing counted source',f=>f.events[0].sourceRefs=[]],['counted prerequisite failure',f=>f.events[0].issues.push('DENOVO_PARENTAGE_EXCLUDED')],
    ['unknown event kind marker',f=>f.events[0].issues.push('DENOVO_EVENT_KIND_OTHER')],['contradictory phenotype points',f=>f.events[0].phenotypeCategories=['HIGHLY_SPECIFIC']],
    ['unknown phenotype',f=>f.events[0].phenotypeCategories=['UNKNOWN']],['two conflicting phenotype categories',f=>f.events[0].phenotypeCategories=['HIGHLY_SPECIFIC','CONSISTENT']],
    ['changed selected criterion',f=>f.selectedCriterion='PM6'],['duplicate event ordinal',f=>f.events[0].ordinal=2],['mutated explanation',f=>f.criterionExplanations[0].appliedStrength='STRONG'],
    ['false clinical approval',f=>f.clinicalApproval='APPROVED'],['complete search fabrication',f=>f.completeLiteratureSearch=true],['wrong material use',f=>f.materialUse='RESEARCH'],
    ['wrong model status',f=>f.modelValidation='SYNTHETIC_MODEL_REPLAY_ONLY'],['wrong source qualification',f=>f.literatureQualification='UNQUALIFIED'],
];
for(const [name,change] of mutations)check(`reject ${name}`,()=>{const v=fixture();alter(v,change);rejected(v);});
check('different shared PS2 and PM6 facts rejected',()=>{const v=fixture();v.rows[1].details.deNovoFacts.rawQuarterPoints=8;rejected(v);});
check('duplicate pair rows rejected',()=>{const v=fixture();v.rows[1]=clone(v.rows[0]);rejected(v);});
check('de novo facts cannot appear on unrelated criterion',()=>{const v=fixture();v.rows.push({...clone(v.rows[0]),criterionId:'ACMG_AMP_2015:PP1'});rejected(v);});
check('single selection cannot forge the unselected explanation identity',()=>{const v=fixture({expected:[IDS[0]]});alter(v,f=>f.criterionExplanations[1].criterionId=IDS[1]);rejected(v);});
check('missing original core-material validator cannot bypass existing source contracts',()=>{
    const v=fixture();alter(v,f=>f.coreMaterials=[{schemaVersion:'denovo-core-display-v1'}]);rejected(v,'DENOVO_CORE_MATERIAL_VALIDATION_REQUIRED');
    assert.equal(inspect(v.rows,v.profile,v.expected,{validCoreMaterial:()=>false}).valid,false);
});
check('changed runtime source pin or explanation binding rejected',()=>{
    const v=fixture();v.profile.moduleBindings.deNovo.manifestSha256='wrong';rejected(v);
    const v2=fixture();v2.profile.moduleBindings.deNovo.publicExplanationVersion='1.3.0';rejected(v2);
});
check('additive runtime release retains field-based validation',()=>{
    const v=fixture();v.profile.moduleBindings.deNovo.version='denovo-selected-batch-1.8.7';v.profile.moduleBindings.deNovo.calculatorVersion='denovo-event-calculator-1.3.1';
    alter(v,f=>{f.detailVersion='1.2.1';f.additionalDisplayNotice='additive';});v.profile.moduleBindings.deNovo.publicExplanationVersion='1.2.1';accepted(v);
});
check('fixed method cannot pretend to be a positive SVI sum',()=>{const v=fixedFixture();alter(v,f=>f.rawQuarterPoints=8);rejected(v,'DENOVO_POINT_COMPOSITION_MISMATCH');});
check('SVI method cannot use NO_SVI_POINT_SUM',()=>{const v=fixture();alter(v,f=>f.strengthAdjustment='NO_SVI_POINT_SUM');rejected(v);});
check('mixed AR missing adjustment cannot become a positive score',()=>{const v=fixture({mixed:true});alter(v,f=>{f.status='MET';f.selectedCriterion='PS2';});rejected(v);});
check('old details remain separate and unknown new metadata cannot fall through',()=>{
    assert.equal(modern({detailVersion:'1.1.0'}),false);assert.equal(modern({detailVersion:'1.0.0'}),false);
    assert.equal(modern({detailVersion:'9.0.0'}),true);assert.equal(modern({detailVersion:'1.1.0',calculationMethod:'UNKNOWN'}),true);assert.equal(modern(facts(fixture())),true);
});
const baseBinding=()=>clone(profile.moduleBindings.deNovo);
const supplyBinding=()=>({...baseBinding(),literatureQualification:'SELECTED_SOURCE_SUPPLY_CONFIGURED',sourceSupply:{version:'denovo-selected-literature-supply-1.0.0',
    publicNetworkEnabled:false,scope:'EXACT_PINNED_CLINVAR_CITATIONS_ONLY',modelCalls:0,completeLiteratureSearch:false,implementation:Object.fromEntries(
        ['supply_selected_literature.py','fetch_public_literature.py','register_materials.py','prepare_literature.py','literature_allele_identity.py'].map(name=>[name,H])),
    executionMode:'ASYNCHRONOUS',taskVersion:'denovo-source-task-v1',retryPolicy:'denovo-source-explicit-retry-v1',maximumAttempts:3}});
check('new committed startup binding and exact asynchronous supply accepted',()=>{
    assert.equal(api.isDeNovoMethodsBinding(baseBinding()),true);assert.equal(api.deNovoMethodsBindingValid(baseBinding()),true);assert.equal(api.deNovoMethodsBindingValid(supplyBinding()),true);
});
check('legacy public explanation metadata remains on the legacy dispatcher',()=>{
    for (const version of ['1.0.0','1.1.0','1.1.1']) assert.equal(api.isDeNovoMethodsBinding({version:'denovo-selected-batch-1.4.0',
        calculatorVersion:'denovo-event-calculator-1.0.0',publicExplanationVersion:version}),false);
});
check('new binding marker catches unsupported versions while legacy remains separate',()=>{
    assert.equal(api.isDeNovoMethodsBinding({version:'denovo-selected-batch-1.4.0',calculatorVersion:'denovo-event-calculator-1.0.0',qualifierVersion:'denovo-local-evidence-1.2.0'}),false);
    for(const b of [{publicExplanationVersion:'9.0.0'},{calculatorVersion:'denovo-event-calculator-1.2.0'},{qualifierVersion:'denovo-local-evidence-1.4.0'},{version:'denovo-selected-batch-2.0.0'}]) {
        assert.equal(api.isDeNovoMethodsBinding(b),true);assert.equal(api.deNovoMethodsBindingValid(b),false);
    }
});
for(const [name,change] of [
    ['supply scope',b=>b.sourceSupply.scope='GENE_SEARCH'],['supply network flag',b=>b.sourceSupply.publicNetworkEnabled='false'],
    ['supply model calls',b=>b.sourceSupply.modelCalls=1],['supply full search',b=>b.sourceSupply.completeLiteratureSearch=true],
    ['supply missing implementation pin',b=>delete b.sourceSupply.implementation['prepare_literature.py']],['supply changed implementation pin',b=>b.sourceSupply.implementation['fetch_public_literature.py']='wrong'],
    ['unknown supply version',b=>b.sourceSupply.version='denovo-selected-literature-supply-2.0.0'],['wrong async mode',b=>b.sourceSupply.executionMode='SYNC'],
    ['wrong task schema',b=>b.sourceSupply.taskVersion='denovo-source-task-v99'],['wrong retry policy',b=>b.sourceSupply.retryPolicy='AUTOMATIC'],
    ['wrong retry attempts',b=>b.sourceSupply.maximumAttempts=4],['missing supply',b=>delete b.sourceSupply],
    ['supply qualifier mismatch',b=>b.literatureQualification='NOT_YET_CONNECTED'],['missing region supply',b=>delete b.sexChromosomeRegion],
    ['region infers patient ploidy',b=>b.sexChromosomeRegion.patientPloidyInferred=true],['region grants clinical adoption',b=>b.sexChromosomeRegion.clinicalQualificationGranted=true],
    ['unknown calculator major',b=>b.calculatorVersion='denovo-event-calculator-2.0.0'],['old raw report reader',b=>b.readerVersion='denovo-local-report-reader-1.1.0'],
])check(`startup rejects ${name}`,()=>{const b=supplyBinding();change(b);assert.equal(api.deNovoMethodsBindingValid(b),false);});
check('positive literature requires original result pin and matching reader',()=>{
    const b=baseBinding();Object.assign(b,{literatureQualification:'SOURCE_REPLAYED_FOR_PER_TARGET_QUALIFICATION',literatureVersion:'denovo-literature-evidence-1.5.0',
        literatureReaderVersion:'denovo-literature-batch-reader-1.0.0',literatureSourceResultSha256:H});assert.equal(api.deNovoMethodsBindingValid(b),true);
    delete b.literatureSourceResultSha256;assert.equal(api.deNovoMethodsBindingValid(b),false);
});
check('model replay pins, research class and no automatic calls remain strict',()=>{
    const b=baseBinding();b.modelArtifactReplay={version:'denovo-literature-model-replay-1.0.0',artifactIndexSha256:H,automaticModelCalls:false,sourceScope:'PUBLIC_PAPER_ONLY'};
    b.modelValidation='SYNTHETIC_MODEL_REPLAY_ONLY';assert.equal(api.deNovoMethodsBindingValid(b),true);
    b.use='RESEARCH';assert.equal(api.deNovoMethodsBindingValid(b),false);b.use='SYNTHETIC_VALIDATION';b.modelArtifactReplay.automaticModelCalls=true;assert.equal(api.deNovoMethodsBindingValid(b),false);
});
const comparison=()=>({reasonCode:'DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT',memberRole:'MOTHER',ordinaryResult:'PRESENT',specialEventResult:'ABSENT'});
check('new empty fact comparisons are additive and preserve old saved events',()=>{
    const v=fixture();alter(v,f=>f.events[0].factComparisons=[]);accepted(v);
});
check('explicit mother source disagreement remains excluded beside an independent qualified event',()=>{
    const excluded={...event(true,'CONSISTENT','EXCLUDED'),issues:['DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT'],factComparisons:[comparison()]};
    const v=fixture({events:[event(),excluded]});accepted(v);assert.ok(details(v.rows[0]).some(([,text])=>text.includes('母亲')&&text.includes('普通来源 检测到')&&text.includes('特殊事件来源 未检测到')));
});
for(const [name,change] of [
    ['counted source conflict without an issues code',e=>e.factComparisons=[comparison()]],
    ['counted source conflict with normalized code',e=>{e.factComparisons=[comparison()];e.issues=['DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT'];}],
    ['unknown compared member role',e=>e.factComparisons=[{...comparison(),memberRole:'OTHER_PERSON'}]],
    ['unknown compared result',e=>e.factComparisons=[{...comparison(),ordinaryResult:'UNKNOWN'}]],
    ['same compared result mislabeled conflict',e=>e.factComparisons=[{...comparison(),ordinaryResult:'ABSENT'}]],
    ['unscoped comparison reason',e=>e.factComparisons=[{...comparison(),reasonCode:'OTHER_REASON'}]],
    ['free prose in comparison',e=>e.factComparisons=[{...comparison(),privateDetails:'private report prose'}]],
])check(`fact comparisons reject ${name}`,()=>{const v=fixture();alter(v,f=>change(f.events[0]));rejected(v);});
check('comparison API rejects a zero-support or duplicate event retaining explicit conflict',()=>{
    for(const disposition of ['ZERO_SUPPORT','DUPLICATE'])assert.equal(api.deNovoFactComparisonsValid([comparison()],{disposition,issues:['DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT']}),false);
});
const discovery=()=>({version:'denovo-source-event-discovery-v1',checkedSourceSha256s:[H],criterionDecision:'NOT_RUN',issues:[],records:[{
    sourceSha256:H,recordLocator:'/article[1]/table[1]',isEventCandidate:false,missingPaths:['/trio','/tissues'],issues:['DENOVO_DISCOVERY_GENE_BINDING_MISSING_OR_MISMATCH'],
    caseReference:{sourceSha256:H,locator:'/article[1]/table[1]/th[2]',kind:'JATS_UNIT',quote:'Case 1'},fields:[{path:'/binding/geneSymbol',sourceSha256:H,locator:'/article[1]/table[1]/td[2]',kind:'JATS_UNIT',quote:'SYNTHETIC-GENE'}]}]});
function core(discoveryValue=discovery()) {
    return {schemaVersion:'denovo-core-display-v1',dataClass:'SYNTHETIC',status:'INSUFFICIENT_DATA',criterionDecision:'NOT_RUN',caseKey:'PMC123#1',documentSha256:H,ledgerSha256:H,
        fields:['paper','case','gene','variant','proband','mother','father','testing','parentage','phenotype','family','overlap'].map(group=>({path:`/groups/${group}/synthetic`,
            value:null,availability:'NOT_EXTRACTED',verification:'UNVERIFIED',issues:[],citations:[]})),blockers:[],coverageLimitations:['Synthetic input, no original clinical event qualification'],sourceDiscovery:discoveryValue};
}
check('source discovery strictly supplements the unchanged original core-material validator',()=>{
    const m=core();assert.equal(oldInput.validCoreMaterial(m),true);assert.equal(api.deNovoSourceDiscoveryValid(m.sourceDiscovery),true);
    const v=fixture();alter(v,f=>f.coreMaterials=[m]);assert.equal(inspect(v.rows,v.profile,v.expected,{validCoreMaterial:oldInput.validCoreMaterial}).valid,true);
    const before=JSON.stringify(v);details(v.rows[0]);assert.equal(JSON.stringify(v),before);
    alter(v,f=>f.coreMaterials[0].documentSha256='wrong');assert.equal(inspect(v.rows,v.profile,v.expected,{validCoreMaterial:oldInput.validCoreMaterial}).valid,false);
});
check('JSON root record and intentionally empty case-reference object are valid public projection',()=>{
    const d=discovery(),r=d.records[0];r.recordLocator='';r.caseReference={};r.fields[0].kind='JSON_POINTER';r.fields[0].locator='/cases/0/geneSymbol';
    assert.equal(api.deNovoSourceDiscoveryValid(d),true);assert.ok(api.deNovoSourceDiscoveryDetails(d).some(([,text])=>text.includes('JSON 根对象')));
});
for(const [name,change] of [
    ['unknown discovery version',d=>d.version='denovo-source-event-discovery-v2'],['invented adopted decision',d=>d.criterionDecision='MET'],
    ['missing checked origin pins',d=>d.checkedSourceSha256s=[]],['changed record pin',d=>d.records[0].sourceSha256='b'.repeat(64)],
    ['changed field pin',d=>d.records[0].fields[0].sourceSha256='b'.repeat(64)],['changed case-reference pin',d=>d.records[0].caseReference.sourceSha256='b'.repeat(64)],
    ['invalid missing path',d=>d.records[0].missingPaths=['not-a-pointer']],['invalid escaped field path',d=>d.records[0].fields[0].path='/binding/~notvalid'],
    ['invented source kind',d=>d.records[0].fields[0].kind='UNVERIFIED_PROSE'],['invalid source locator',d=>d.records[0].fields[0].locator='javascript:bad'],
    ['nonboolean candidate state',d=>d.records[0].isEventCandidate='true'],['invented issue prose',d=>d.records[0].issues=['A private file path']],
    ['claim field both found and missing',d=>d.records[0].missingPaths.push('/binding/geneSymbol')],
    ['claim parent missing despite found child',d=>d.records[0].missingPaths.push('/binding')],['invented report success status',d=>d.status='SUCCESS'],
])check(`source discovery rejects ${name}`,()=>{
    const d=discovery();change(d);assert.equal(api.deNovoSourceDiscoveryValid(d),false);
    const v=fixture();alter(v,f=>f.coreMaterials=[core(d)]);assert.equal(inspect(v.rows,v.profile,v.expected,{validCoreMaterial:oldInput.validCoreMaterial}).valid,false);
});
check('source discovery separates missing, unsupported, failed and unadopted scope',()=>{
    const d=discovery();d.records[0].issues.push('DENOVO_DISCOVERY_TYPED_VALUE_UNSUPPORTED');d.issues.push({code:'DENOVO_DISCOVERY_JSON_PARSE_UNSUPPORTED',sourceSha256:'b'.repeat(64)});
    assert.equal(api.deNovoSourceDiscoveryValid(d),true);const text=api.deNovoSourceDiscoveryDetails(d).map(row=>row[1]).join('\n');
    for(const wanted of ['NOT_RUN','尚未取得','当前方法未支持','读取或核验失败','不代表该原件已完成检查','原句 Case 1','SYNTHETIC-GENE'])assert.ok(text.includes(wanted),wanted);
});
const args=process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url ? process.argv.slice(2) : [];
for(let index=0;index<args.length;index++) {
    if(args[index]==='--discovery-receipt') {
        const filename=args[++index];
        check(`existing source-discovery locator receipt ${filename} (not Java selected result)`,()=>{
            const bytes=fs.readFileSync(filename),value=JSON.parse(bytes),before=JSON.stringify(value),d=value.report||value;
            assert.equal(api.deNovoSourceDiscoveryValid(d),true);assert.ok(api.deNovoSourceDiscoveryDetails(d).length);
            assert.equal(d.criterionDecision,'NOT_RUN');assert.equal(JSON.stringify(value),before);assert.deepEqual(fs.readFileSync(filename),bytes);
        });continue;
    }
    const filename=args[index];
    check(`captured saved public pair ${filename}`,()=>{
    const bytes=fs.readFileSync(filename),saved=JSON.parse(bytes),before=JSON.stringify(saved);assert.ok(Array.isArray(saved.rows)&&saved.profile,'Expected committed saved pair and execution profile');
    const result=inspect(saved.rows,saved.profile,saved.expected,{validCoreMaterial:oldInput.validCoreMaterial});if(saved.expectedIssue)assert.deepEqual(result,{valid:false,issues:[saved.expectedIssue]});else assert.equal(result.valid,true,JSON.stringify(result));
    for(const row of saved.rows)details(row);assert.equal(JSON.stringify(saved),before);assert.deepEqual(fs.readFileSync(filename),bytes);
    });
}
console.log(`${count} de novo method transport/projection checks passed; synthetic controls and existing receipt/pair checks remain distinct, no new Java/source execution claimed.`);

// Reused only by frontend adapter controls; these remain synthetic fixtures.
export { fixture as syntheticDeNovoFixture, event as syntheticDeNovoEvent };
