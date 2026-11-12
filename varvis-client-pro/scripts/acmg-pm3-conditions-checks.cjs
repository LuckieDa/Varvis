// Read-only frontend consumption of frozen normal engineering products, not clinical validation.
const fs=require('fs'),path=require('path'),assert=require('node:assert/strict'),{createHash}=require('crypto'),{Module}=require('module'),{buildSync}=require('esbuild');
const project=path.resolve(__dirname,'..'),dir=process.argv[2],output=process.argv[3];
if(!dir||!output)throw Error('Supply T06 actual-business directory and private result path');
const bundle=buildSync({stdin:{resolveDir:project,contents:`export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgPm3Conditions';
export {ps2IdentityHash,localBatchScopeIssue,pm3LiteratureCriterionValid} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
export {backgroundEvidenceCards} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';
export {buildEvidenceDetailView} from './src/pages/varvis/ChromosomeVisualization/utils/evidenceDetailView';
export {default as Details} from './src/pages/varvis/ChromosomeVisualization/components/EvidenceCoreDetails';`},bundle:true,platform:'node',format:'cjs',packages:'external',write:false,loader:{'.js':'jsx','.less':'empty'},logLevel:'silent'});
const mod=new Module(path.join(project,'scripts/pm3-conditions-bundle.cjs'));mod.filename=mod.id;mod.paths=[path.join(project,'node_modules')];mod._compile(bundle.outputFiles[0].text,mod.id);
const api=mod.exports,React=require('react'),{renderToStaticMarkup}=require('react-dom/server'),read=p=>JSON.parse(fs.readFileSync(p,'utf8').replace(/^\uFEFF/,'')),clone=v=>JSON.parse(JSON.stringify(v));
const sha=b=>createHash('sha256').update(b).digest('hex'),checks=[],pins=[],templates=[];
const test=(name,fn)=>{fn();checks.push({name,status:'PASS'});};
for(const scenario of ['GENERAL_HOMO','PHASE_TRANS','APPROVED_TRANS','PHASE_CONFLICT','HOMO_CONFLICT','APPROVAL_MISSING']){
    const file=path.join(dir,scenario,'actual-current18.json'),before=fs.readFileSync(file),products=read(file).publicResults;
    pins.push({path:file,sha256:sha(before),bytes:before.length,layer:'FROZEN_NORMAL_ENGINEERING_PUBLIC_RUN_NO_CLINICAL_ADOPTION'});
    for(const selection of ['single','related','all'])test(`normal saved ${scenario}/${selection}`,()=>{
        const run=products[selection];assert.equal(api.localBatchScopeIssue(run),null);
        const candidate=run.candidates[0],attempt=candidate.attempts[0],cards=api.backgroundEvidenceCards(candidate,run.enabledCriteria,run,0),card=cards.find(c=>c.code==='PM3'),entry=card?.entries[0];
        assert.ok(entry,'Missing PM3 card');assert.equal(Boolean(entry.displayIssue),false,entry.summary);
        const row=attempt.localBatch.criteria.find(c=>c.criterionId==='ACMG_AMP_2015:PM3');
        assert.equal(api.pm3LiteratureCriterionValid(row,run.executionProfile,attempt.context),true);
        assert.equal(entry.savedCriterion,row);const view=api.buildEvidenceDetailView(card,entry,attempt.context);assert.ok(view.sections.length);
        const html=renderToStaticMarkup(React.createElement(api.Details,{card,entry,candidate}));
        assert.ok(!html.includes('"schemaVersion"'));assert.ok(!html.includes('PM3_PARTNER_'));assert.ok(!html.includes('UNKNOWN_PHASE_WEIGHT'));
        assert.equal(row.status,'NOT_EVALUATED');assert.equal(row.appliedStrength,'NONE');
        if(scenario.includes('CONFLICT'))assert.ok(view.conflicts.length,'Saved source contradiction needs an explicit conflict block');
        if(scenario==='GENERAL_HOMO'){assert.ok(view.sections.some(s=>s.rows.some(r=>r.value.includes('不要求另一个伙伴'))));assert.ok(!view.comparisons.some(r=>r.title.includes('伙伴')));}
        if(scenario==='APPROVED_TRANS'){assert.ok(view.sections.some(s=>s.rows.some(r=>r.label==='专家批准核查'&&r.value==='已满足')));assert.ok(!view.comparisons.some(r=>r.title.includes('批准')));}
        templates.push({run,candidate,attempt,row,view});
    });
    assert.deepEqual(fs.readFileSync(file),before);
}
const trans=templates.find(t=>t.view.sections.some(s=>s.rows.some(r=>r.label==='专家批准核查'))),general=templates.find(t=>t.view.sections.some(s=>s.rows.some(r=>r.value.includes('不要求另一个伙伴'))));
const approved=templates.find(t=>t.row.details.pm3Facts.caseFacts.some(c=>c.publishedConditions?.family==='SVI_PHASE_AND_APPROVED_PARTNER'&&c.publishedConditions?.status==='QUALIFIED'));
for(const [name,change]of [
    ['missing phase condition',v=>{v.requirements=v.requirements.filter(r=>r.conditionId!=='QUALIFIED_PHASE_METHOD');}],
    ['missing required partner approval',v=>{v.requirements=v.requirements.filter(r=>r.conditionId!=='PARTNER_APPROVED_BY_SPECIFICATION_ISSUER');}],
    ['duplicate phase condition',v=>{v.requirements.push(clone(v.requirements.find(r=>r.conditionId==='QUALIFIED_PHASE_METHOD')));}]
])test('qualified saved condition rejects '+name,()=>{const t=clone(approved);change(t.row.details.pm3Facts.caseFacts[0].publishedConditions);assert.equal(api.pm3V4FactsValid(t.row,t.run.executionProfile,t.attempt.context,api.ps2IdentityHash),false);});
test('saved homozygous observation requires no phase or partner approval',()=>{
    for(const c of general.row.details.pm3Facts.caseFacts){
        assert.equal(c.phaseEvidenceStatus,'PHASE_NOT_REQUIRED');
        assert.equal(c.publishedConditions.requirements.length,1);
        const [phase]=c.publishedConditions.requirements;
        assert.equal(phase.conditionId,'QUALIFIED_PHASE_METHOD');
        assert.equal(phase.status,'SATISFIED');
    }
    assert.equal(api.pm3V4FactsValid(general.row,general.run.executionProfile,general.attempt.context,api.ps2IdentityHash),true);
});
test('a rule blocker preserves the displayed distinction between explicit unknown phase and missing source',()=>{
    const f=clone(trans.row.details.pm3Facts),c=f.caseFacts[0];
    delete c.publishedConditions;
    c.qualificationBlocker={reasonCode:'PM3_PUBLISHED_CONDITIONS_NOT_IMPLEMENTED'};
    c.reportedFacts=c.reportedFacts.filter(fact=>fact.field!=='phaseText');
    c.reportedFacts.push({field:'phaseText',citation:{unitId:'phase-cell',quote:'phase unknown'}});
    c.phaseEvidenceStatus='REPORTED_UNDETERMINED';
    const section=()=>api.pm3ConditionDetails(f,{current:true}).sections[0].rows;
    assert.ok(section().some(r=>r.label==='核实相位'&&r.value==='原文明确未定相'));
    assert.ok(section().some(r=>r.label==='相位原文'&&r.value==='phase unknown'));
    c.reportedFacts.pop();
    c.phaseEvidenceStatus='PHASE_ORIGINAL_MISSING';
    assert.ok(section().some(r=>r.label==='核实相位'&&r.value==='缺少可核对的相位原件'));
    assert.ok(!section().some(r=>r.label==='相位原文'));
});
test('RFC6901 empty root is a legal pointer; absent and non-string pointers are not',()=>{const t=clone(approved),f=t.row.details.pm3Facts;for(const p of f.policySelections)p.matches[0].source.jsonPointer='';for(const c of f.caseFacts)c.publishedConditions.source.entityPointer='';assert.equal(api.pm3V4FactsValid(t.row,t.run.executionProfile,t.attempt.context,api.ps2IdentityHash),true);
    for(const value of [undefined,null,0]){const x=clone(t);x.row.details.pm3Facts.policySelections[0].matches[0].source.jsonPointer=value;assert.equal(api.pm3V4FactsValid(x.row,x.run.executionProfile,x.attempt.context,api.ps2IdentityHash),false);}});
test('absent source MOI uses only the exact qualified AR relationship contract, never an arbitrary omission',()=>{const t=clone(approved),f=t.row.details.pm3Facts;for(const p of f.policySelections){p.matches[0].scope.moi='';p.matches[0].inheritanceSupply='EXACT_QUALIFIED_GENE_DISEASE_RELATIONSHIP';}assert.equal(api.pm3V4FactsValid(t.row,t.run.executionProfile,t.attempt.context,api.ps2IdentityHash),true);
    for(const change of [x=>{delete x.row.details.pm3Facts.policySelections[0].matches[0].inheritanceSupply;},x=>{x.row.details.pm3Facts.policySelections[0].matches[0].scope.moi='AUTOSOMAL_DOMINANT';},x=>{x.row.details.pm3Facts.caseFacts[0].diseaseContextCheck.qualification='UNKNOWN';},x=>{x.row.details.pm3Facts.caseFacts[0].diseaseContextCheck.matchedRelationships[0].mondoId='MONDO:0000001';}]){const x=clone(t);change(x);assert.equal(api.pm3V4FactsValid(x.row,x.run.executionProfile,x.attempt.context,api.ps2IdentityHash),false);}});
for(const [name,change]of [
    ['future selected module',t=>{t.run.executionProfile.moduleBindings.pm3.version='pm3-selected-literature-v5';}],
    ['wrong assessment tuple',t=>{t.run.executionProfile.moduleBindings.pm3.assessmentVersion='pm3-literature-assessment-v2';}],
    ['wrong condition capability',t=>{t.run.executionProfile.moduleBindings.pm3.publishedConditions.program='OTHER';}],
    ['different selected disease',t=>{t.attempt.context.disease.mondoId='MONDO:0000001';}],
    ['different selected gene',t=>{t.attempt.context.gene.hgncId='HGNC:1';}],
    ['different clause pin',t=>{t.row.details.pm3Facts.caseFacts[0].publishedConditions.source.clauseSha256='f'.repeat(64);}],
    ['unknown condition schema',t=>{t.row.details.pm3Facts.caseFacts[0].publishedConditions.schemaVersion='pm3-published-conditions-99';}],
    ['false qualified label over missing approval',t=>{const c=t.row.details.pm3Facts.caseFacts[0];c.publishedConditions.status='QUALIFIED';c.publishedConditions.requirements.push({conditionId:'PARTNER_APPROVED_BY_SPECIFICATION_ISSUER',status:'MISSING_INFORMATION',required:'approval'});}],
])test('reject '+name,()=>{const t=clone(trans);change(t);const b=t.run.executionProfile.moduleBindings.pm3;assert.ok(!api.pm3V4BindingValid(b)||!api.pm3V4FactsValid(t.row,t.run.executionProfile,t.attempt.context,api.ps2IdentityHash));});
test('a full requiredMaterial catalog does not become missing information',()=>{const f=clone(general.row.details.pm3Facts),before=api.pm3ConditionDetails(f);f.requiredMaterial=['targetVariant','partnerVariant','phase','caseIndependence'];assert.deepEqual(api.pm3ConditionDetails(f),before);});
test('unknown phase is an allowed saved phase treatment, not automatic missing trans',()=>{const f=clone(general.row.details.pm3Facts),v=api.pm3ConditionDetails(f);assert.ok(!v.comparisons.some(r=>r.title.includes('相位')));});
test('source-approved case-insensitive homozygosity stays valid, while contradictory zygosity is not called homozygous',()=>{const f=clone(general.row.details.pm3Facts);for(const c of f.caseFacts)for(const r of c.reportedFacts)if(r.field==='reportedZygosity')r.value='Homozygous';assert.ok(api.pm3ConditionDetails(f).sections.every(s=>s.rows.some(r=>r.label==='观察类型'&&r.value==='纯合观察')));f.caseFacts[0].reportedFacts.push({field:'reportedZygosity',value:'compound heterozygous'});assert.ok(!api.pm3ConditionDetails(f).sections[0].rows.some(r=>r.label==='观察类型'&&r.value==='纯合观察'));});
test('unknown awarded contribution is not filled with zero',()=>{const f=clone(trans.row.details.pm3Facts);for(const c of f.assessment.calculation.contributions)delete c.awardedQuarterPoints;assert.ok(!api.pm3ConditionDetails(f).sections.some(s=>s.rows.some(r=>r.label==='实际计入的 PM3 积分')));});
test('source condition qualification alone never becomes final PM3 adoption',()=>{assert.equal(trans.row.status,'NOT_EVALUATED');assert.ok(trans.view.needs);assert.equal(trans.view.adopted,false);assert.ok(trans.view.missing.includes('稀有性')||trans.view.comparisons.some(c=>c.title.includes('稀有性')));});
test('a calculated positive label cannot bypass the saved source and rarity prerequisites',()=>{const t=clone(trans),f=t.row.details.pm3Facts;t.row.status='MET';t.row.appliedStrength='SUPPORTING';const c=f.assessment.calculation;c.status='MET';c.strength='SUPPORTING';c.quarterPoints=2;c.contributions=c.contributions.slice(0,1);Object.assign(c.contributions[0],{awardedQuarterPoints:2,proposedQuarterPoints:2,requirements:[]});assert.equal(api.pm3V4FactsValid(t.row,t.run.executionProfile,t.attempt.context,api.ps2IdentityHash),false);});
test('a second patient sharing references cannot acquire the first patient contribution',()=>{const f=clone(trans.row.details.pm3Facts),c=clone(f.caseFacts[0]);c.caseLabel='other patient';f.caseFacts.push(c);const v=api.pm3ConditionDetails(f);assert.ok(v.sections.filter(s=>s.title.includes('other patient')).every(s=>!s.rows.some(r=>r.label==='实际计入的 PM3 积分')));});
test('unsupported source conditions are a software gap, not missing patient information',()=>{const f=clone(trans.row.details.pm3Facts);f.caseFacts=f.caseFacts.slice(0,1);delete f.caseFacts[0].publishedConditions;f.caseFacts[0].qualificationBlocker={reasonCode:'PM3_PUBLISHED_CONDITIONS_NOT_IMPLEMENTED'};f.assessment.calculation.contributions=[];
    const v=api.pm3ConditionDetails(f,{current:true});assert.ok(v.comparisons.some(r=>r.software&&r.gap===''));assert.ok(v.summary.includes('程序支持'));});
test('source criteria-list denial is visible with both original statement and denial; uncertainty stays distinct',()=>{const f=clone(trans.row.details.pm3Facts),c=f.caseFacts[0],a=c.partnerQualification.sourceAssertions[0];a.criteriaListUse={status:'CONFLICT',statement:'This list was not used for the final classification.',originalText:'ACMG/AMP criteria applied: PS3, PM2, PP3. This list was not used for the final classification.',sourceLocator:a.sourceLocator};
    let v=api.pm3ConditionDetails(f);assert.ok(v.conflicts.some(r=>r.left.value.includes('PS3')&&r.right.value.includes('not used')));a.criteriaListUse.status='UNKNOWN';v=api.pm3ConditionDetails(f);assert.ok(v.comparisons.some(r=>r.title.includes('依据采用')&&r.gap.includes('尚未确认')));assert.ok(!v.conflicts.some(r=>r.title.includes('伙伴分类依据采用冲突')));});
test('source method approval conflict keeps applied and denied statements separate',()=>{const f=clone(trans.row.details.pm3Facts),r=f.caseFacts[0].publishedConditions.requirements.find(r=>r.conditionId==='PARTNER_APPROVED_BY_SPECIFICATION_ISSUER');r.status='CONFLICT';r.observed[0].status='CONFLICT';r.observed[0].methodStatements=[{status:'APPLIED',statement:'The classification was performed using the specification.',locator:'/method'},{status:'USE_DENIED_OR_NOT_ESTABLISHED',statement:'The specification was not used.',locator:'/denial'}];const v=api.pm3ConditionDetails(f);assert.ok(v.conflicts.some(c=>c.title.includes('规范采用记录冲突')&&c.left.value.includes('using')&&c.right.value.includes('not used')));});
for(const file of process.argv.slice(4))test('unchanged real legacy PM3 saved result '+path.basename(file),()=>{const before=fs.readFileSync(file),run=read(file);assert.equal(api.localBatchScopeIssue(run),null);let rows=0;
    for(const candidate of run.candidates||[])for(const attempt of candidate.attempts||[])for(const row of attempt.localBatch?.criteria||[])if(row.criterionId==='ACMG_AMP_2015:PM3'){rows++;assert.equal(api.pm3LiteratureCriterionValid(row,run.executionProfile,attempt.context),true);}
    assert.ok(rows);assert.deepEqual(fs.readFileSync(file),before);pins.push({path:file,bytes:before.length,sha256:sha(before),layer:'IMMUTABLE_LEGACY_SAVED_RESULT'});});
const result={status:'PASS_FROZEN_NORMAL_NEGATIVE_FRONTEND_POSITIVE_NORMAL_PENDING',checks:checks.length,results:checks,pins,
    scientificOrModelCalls:0,normalPositivePM3:'PENDING_T06_CFTR_PRODUCT',browser:'NOT_RUN',sourceQualificationChanged:false,rescoring:false};
fs.writeFileSync(output,JSON.stringify(result,null,2)+'\n');console.log(`${checks.length} PM3 conditions checks: ${result.status}`);
