// Presentation contracts only; synthetic saved results do not validate scientific evaluators.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { Module } = require('node:module');
const { buildSync } = require('esbuild');
const root = path.resolve(__dirname, '..');
const bundle = buildSync({ stdin:{contents:`
export * from './src/pages/varvis/ChromosomeVisualization/utils/evidenceDetailView.js';
export {ps2IdentityHash} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
export {fullRnaPending,rememberFullRnaProjection} from './src/pages/varvis/ChromosomeVisualization/utils/acmgFullRna.js';
export {backgroundEvidenceCards} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js';
export {default as Details,evidenceSourceRows,cacheableSourceRegions,renderableLiteratureReferences} from './src/pages/varvis/ChromosomeVisualization/components/EvidenceCoreDetails.jsx';
export {EvidenceEntryDetails} from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';
`,resolveDir:root},bundle:true,platform:'node',format:'cjs',packages:'external',write:false,loader:{'.js':'jsx','.less':'empty'},logLevel:'silent'});
const mod = new Module(path.join(root,'scripts/details-check-bundle.cjs'));
mod.filename=mod.id; mod.paths=[path.join(root,'node_modules')];mod._compile(bundle.outputFiles[0].text,mod.filename);
const api=mod.exports,React=require('react'),{renderToStaticMarkup}=require('react-dom/server');
const axios=require('axios');
const freeze=v=>{if(v&&typeof v==='object'){Object.values(v).forEach(freeze);Object.freeze(v);}return v;};
const entry=(code,status='MET',details={})=>({savedCriterion:{criterionId:'ACMG_AMP_2015:'+code,status,details},
    evaluation:{criterionId:'ACMG_AMP_2015:'+code,appliedStrength:status==='MET'?'SUPPORTING':'NONE',
        evaluationResult:{status,reasonCode:status==='MET'?'LOCAL_BATCH_CRITERION_MET':'LOCAL_BATCH_CRITERION_NOT_EVALUATED'}},
    summary:status==='MET'?'本次规则条件满足。':'原文未报告父母的目标变异检测结果。'});
const card=code=>({code,id:'ACMG_AMP_2015:'+code});
const render=(code,e)=>renderToStaticMarkup(React.createElement(api.Details,{card:card(code),entry:e}));
const computed=code=>api.evidenceDetailDefinitions[code].kind!=='LITERATURE';
let checks=0;function check(name,fn){fn();checks++;console.log('PASS '+name);}
for(const code of Object.keys(api.evidenceDetailDefinitions)){
    check(code+' successful saved outcome has dedicated layout and no missing/advice placeholder',()=>{
        const e=freeze(entry(code)),before=JSON.stringify(e),view=api.buildEvidenceDetailView(card(code),e),html=render(code,e);
        assert.equal(view.code,code);assert.equal(view.adopted,true);assert.ok(view.requirement.length>10);
        assert.ok(html.includes('evidence-core-details-'+code));
        assert.equal(html.includes('evidence-rule-actual-comparison'),computed(code));
        assert.ok(!html.includes('evidence-requirement-comparison'));assert.ok(!html.includes('缺少信息'));assert.ok(!html.includes('建议</td>'));
        assert.equal(JSON.stringify(e),before);
    });
    check(code+' unresolved outcome shows required/current/missing/conclusion fields',()=>{
        const e=entry(code,'NOT_EVALUATED'),view=api.buildEvidenceDetailView(card(code),e),html=render(code,e);
        assert.equal(view.needs,true);
        const labels=computed(code)?['规则要求','当前变异','待核实']:['规则需要','已有信息','缺少信息','结论'];
        for(const text of labels)assert.ok(html.includes(text));
        assert.ok(!html.includes('LOCAL_BATCH_CRITERION'));
    });
}
check('all existing criteria preserve evaluated-negative and explicitly inapplicable states without fake missing-data panels',()=>{
    for(const code of Object.keys(api.evidenceDetailDefinitions))for(const status of ['NOT_MET','NOT_APPLICABLE']){
        const e=entry(code,status);
        e.evaluation.evaluationResult.reasonCode=status==='NOT_MET'?'LOCAL_BATCH_CRITERION_NOT_MET':'EXPLICIT_SCOPE_EXCLUSION';
        if(status==='NOT_APPLICABLE')e.evaluation.criterionApplicability={status:'NOT_APPLICABLE'};
        const view=api.buildEvidenceDetailView(card(code),e),html=render(code,e);
        assert.equal(view.presentation.mainKind,status,code);assert.equal(view.adopted,false);assert.equal(view.needs,false);
        if(computed(code))assert.ok(html.includes(`data-rule-outcome="${status==='NOT_MET'?'未满足':'不适用'}"`),code);
        assert.ok(!html.includes('缺少信息'),code);assert.ok(!html.includes('建议</td>'),code);
    }
});
check('reviewed PM1 preserves the region boundary, independent variants and conflicting source',()=>{
    const region={label:'测试电压感受区',start:412,end:434,reviewStatus:'PENDING',reviewRationale:'区域采用强度待复核',
        benignSurvey:'INCOMPLETE',observations:[{proteinPosition:420,refAminoAcid:'R',altAminoAcid:'H',role:'INDEPENDENT_PATHOGENIC'},
            {proteinPosition:419,refAminoAcid:'V',altAminoAcid:'I',role:'CONFLICTING'}]};
    const e=entry('PM1','NOT_EVALUATED',{pm1Facts:{proteinPosition:428,refAminoAcid:'T',altAminoAcid:'I',
        reviewedRegion:{schemaVersion:'pm1-reviewed-regions-v1',region,sources:[{title:'冲突原始记录',url:'https://example.test/pm1',locator:'Variant 419'}]}}});
    const view=api.buildEvidenceDetailView(card('PM1'),e);
    assert.deepEqual(view.ruleRows.map(row=>row.outcome),['满足','待核实','满足','资料冲突']);
    const html=render('PM1',e);
    for(const value of ['412–434','p.T428I','p.R420H','p.V419I','https://example.test/pm1']) assert.ok(html.includes(value),value);
    assert.ok(!html.includes('pm1-reviewed-regions-v1'));
});
check('numbers, zero and false survive while opaque objects and internal code strings do not',()=>{
    assert.equal(api.readableEvidenceValue(0),'0');assert.equal(api.readableEvidenceValue(false),'否');
    assert.equal(api.readableEvidenceValue({arbitrary:{secret:'x'}}),'');
    assert.equal(api.readableEvidenceValue('PRESELECTED_MECHANISM_SUPPORTS_PP3'),'');
    assert.equal(api.readableEvidenceValue('context-sha256-'+'a'.repeat(64)),'');
    assert.equal(api.readableEvidenceValue('{"rawScore":0}'),'原始分值：0');
});
check('PM1 mapped conditions distinguish unresolved disease from same-disease conflict and retain older saved material',()=>{
    const context={disease:{mondoId:'MONDO:0012345'},moi:'AUTOSOMAL_DOMINANT'};
    const intake={schemaVersion:'pm1-clinvar-intake-v2',usedForScoring:false,status:'ENUMERATED',
        transcript:'NM_012345.1',protein:'NP_012345.1',mondoId:context.disease.mondoId,moi:context.moi,
        candidateRecordCount:1,referenceSearchComplete:true,regions:[],
        diseaseMappingSource:{status:'READY',release:'2026-11-03'},
        records:[{variationId:'123',proteinChange:'p.Ala12Val',aggregateClassification:'CONFLICT',
            reviewStatus:'AGGREGATE_CONFLICT_SCOPE_UNVERIFIED',sameDiseaseSubmissionCount:1,
            otherDiseaseSubmissionCount:0,unresolvedDiseaseSubmissionCount:1}]};
    const facts={transcript:intake.transcript,protein:intake.protein,clinvarIntake:intake,
        currentEvaluationBinding:{clinvarIntakeSha256:api.ps2IdentityHash(intake)}};
    const e=entry('PM1','NOT_EVALUATED',{pm1Facts:facts});
    const rows=()=>api.buildEvidenceDetailView(card('PM1'),e,context).sections.find(s=>s.title==='本地 ClinVar 候选对照').rows;
    let text=JSON.stringify(rows());
    assert.ok(text.includes('背景待核 1 条'));assert.ok(text.includes('部分提交的疾病背景待核'));
    assert.ok(!text.includes('同疾病提交分类冲突'));
    intake.schemaVersion='pm1-clinvar-intake-v1';intake.records[0].reviewStatus='AGGREGATE_CONFLICT';
    delete intake.records[0].unresolvedDiseaseSubmissionCount;
    facts.currentEvaluationBinding.clinvarIntakeSha256=api.ps2IdentityHash(intake);
    assert.ok(JSON.stringify(rows()).includes('ClinVar 汇总分类冲突'));
    intake.records[0].sameDiseaseSubmissionCount=7;
    assert.equal(rows()[0].value,'保存的对照资料无法核实');
});
check('PM6 exposes the adopted expert points clause and both parental findings',()=>{
    const eventMode='RASOPATHY_POINTS',criterionId=card('PM6').id;
    const e=entry('PM6','MET',{deNovoFacts:{
        events:[{ordinal:1,disposition:'COUNTED',confirmedParentage:false,rawQuarterPoints:4}],
        specializedPolicyAdoptions:[{criterionId,status:'SELECTED',eventMode,catalogEntryId:'GN043-2.3-PM6',policyVersion:'2.3'}],
        adoptionProof:{schemaVersion:'denovo-adoption-proof-v1',projectionStatus:'COMPLETE',status:'ADOPTED',
            selectedCriterion:'PM6',appliedStrength:'MODERATE',policy:{eventMode,sources:[{locator:'Policy clause'}]},
            events:[{ordinal:1,disposition:'COUNTED',conditionBranch:'SOURCE_BOUND_RASOPATHY_POINTS',
                combined:{clauseMode:eventMode,status:'MET',probandTarget:'PRESENT',motherTarget:'ABSENT_QUALIFIED',
                    fatherTarget:'ABSENT_QUALIFIED',maternity:'ASSUMED_NOT_REPORTED',paternity:'ASSUMED_NOT_REPORTED',
                    rawQuarterPoints:4,sources:[{locator:'Case observations'}]}}]},
    }});
    e.savedCriterion.appliedStrength='MODERATE';
    const view=api.buildEvidenceDetailView(card('PM6'),e);
    const comparison=view.comparisons.find(row=>row.title.includes('PM6 专家组积分条款'));
    assert.ok(comparison);
    assert.match(comparison.known,/母亲检测：未检出目标变异.*父亲检测：未检出目标变异/);
    assert.ok(view.sources.some(row=>row.url==='https://cspec.genome.network/cspec/ui/svi/doc/GN043?version=2.3'));
    assert.ok(!render('PM6',e).includes('新发采用证明的逐项对照未完整取得'));
    const proof=e.savedCriterion.details.deNovoFacts.adoptionProof;
    const [event]=proof.events;
    proof.policy.eventMode='SVI_1_1_PUBLISHED_POINTS';
    event.conditionBranch='SOURCE_BOUND_PUBLISHED_SVI_POINTS';
    event.combined.clauseMode=proof.policy.eventMode;
    event.combined.familyHistory='COMPATIBLE';
    const published=api.buildEvidenceDetailView(card('PM6'),e).comparisons.find(row=>row.title.includes('PM6 专家组积分条款'));
    assert.match(published?.known||'',/家族史：相容/);
    assert.ok(!render('PM6',e).includes('新发采用证明的逐项对照未完整取得'));
    event.conditionBranch='SOURCE_BOUND_COMBINED_PS2';
    assert.ok(render('PM6',e).includes('新发采用证明的逐项对照未完整取得'));
});
check('de novo phenotype review shows the zero and missing reasons with the selected published clause',()=>{
    const eventMode='SVI_1_1_PHENOTYPE_ONLY';
    for(const [code,status,disposition,category,reason,label] of [
        ['PS2','NOT_MET','ZERO_SUPPORT','NOT_CONSISTENT','DENOVO_PHENOTYPE_NOT_CONSISTENT','病例表型与当前疾病不一致'],
        ['PM6','NOT_EVALUATED','EXCLUDED','UNKNOWN','DENOVO_PHENOTYPE_BASIS_REQUIRED','缺少可用于核对当前疾病的个体表型资料'],
    ]) {
        const e=entry(code,status,{deNovoFacts:{
            events:[{ordinal:1,disposition,reasonCode:reason,phenotypeCategories:[category],rawQuarterPoints:0}],
            specializedPolicyAdoptions:[{criterionId:card(code).id,status:'SELECTED',eventMode,
                catalogEntryId:`GN112-1.0-${code}`,policyVersion:'1.0'}],
            adoptionProof:{status:'NOT_ADOPTED',projectionStatus:'COMPLETE',policy:{eventMode}},
        }});
        const view=api.buildEvidenceDetailView(card(code),e);
        assert.ok(view.sources.some(row=>row.url==='https://cspec.genome.network/cspec/ui/svi/doc/GN112?version=1.0'));
        const html=render(code,e);
        assert.ok(html.includes(label));
        assert.equal(html.includes('病例表型与当前疾病不一致'),status==='NOT_MET');
    }
});
check('PP1 distinguishes counted obligate carriers and keeps older family cards compatible',()=>{
    const event={pmcid:'PMC123',familyLabel:'F1',affectedRelativesCount:2,memberLabel:'I:1、III:3',
        expertStrength:'SUPPORTING',rawPoints:'1',sourceRefs:[]};
    const saved=event=>({...entry('PP1'),pp1LiteratureFacts:{evaluation:{events:[event]}}});
    assert.ok(!render('PP1',saved(event)).includes('其中必然携带者'));
    const current=saved({...event,obligateCarrierCount:1,obligateCarrierLabels:['I:1']});
    const detail=render('PP1',current);
    assert.ok(detail.includes('其中必然携带者'));
    const rows=api.buildEvidenceDetailView(card('PP1'),current).papers[0].cases[0].rows;
    assert.equal(rows.find(row=>row.label==='其中必然携带者')?.value,'I:1');
});
check('PP1 shows counted transmissions, the actual family threshold and rule attachment status',()=>{
    const saved={...entry('PP1'),pp1LiteratureFacts:{evaluation:{
        events:[{pmcid:'PMC123',familyLabel:'F1',parentLabel:'I:1',memberLabel:'II:2',informativeMeioses:1}],
        meiosisAssessment:{informativeMeioses:7,qualifiedFamilyCount:2,thresholds:{
            SUPPORTING:{minimum:3,maximum:4,minimumIndependentFamilies:1},
            STRONG:{minimum:7,maximum:null,minimumIndependentFamilies:2},
        }},
    }}};
    const view=api.buildEvidenceDetailView(card('PP1'),freeze(saved));
    assert.equal(view.sections[0].rows.find(r=>r.label==='计入家系')?.value,'2 个');
    assert.ok(view.sections[0].rows.some(r=>r.value==='≥7 次 · 至少 2 个独立家系'));
    assert.equal(view.papers[0].cases[0].rows.find(r=>r.label==='计入传递')?.value,'I:1 → II:2');
    assert.ok(!render('PP1',entry('PP1')).includes('信息性传递计数'));
    const url='https://cspec.genome.network/cspec/ui/svi/doc/GN135?version=1.1';
    const unresolved={...entry('PP1','NOT_EVALUATED'),pp1LiteratureFacts:{evaluation:{context:{ruleAttachments:[{
        label:'HHT Phenotype',status:'REGISTERED_FILE_UNAVAILABLE',ruleSourceUrl:url,
    }]}}}};
    const pending=api.buildEvidenceDetailView(card('PP1'),freeze(unresolved));
    assert.deepEqual(pending.sections[0].rows,[{label:'HHT Phenotype',value:'已核对发布登记，待取得附件原件'}]);
    assert.ok(pending.sources.some(r=>r.url===url));
    assert.ok(!render('PP1',unresolved).includes('REGISTERED_FILE_UNAVAILABLE'));
    const implemented=structuredClone(unresolved);
    const evaluation=implemented.pp1LiteratureFacts.evaluation;
    evaluation.context.ruleAttachments[0].status='VERIFIED_CONDITIONS_IMPLEMENTED';
    evaluation.clinicalAssessments=[{basis:'HHT_CURACAO_AFFECTED_ONLY',memberLabel:'II:2',status:'AFFECTED',
        verifiedManifestations:['EPISTAXIS','TELANGIECTASES','FAMILY_HISTORY']}];
    const qualified=render('PP1',implemented);
    for(const label of ['已核对并应用附件条件','Curaçao 患病资格','自发反复鼻出血','合格一等亲家族史'])assert.ok(qualified.includes(label));
});
check('only selected criterion contributes core facts; other batch rows cannot leak',()=>{
    const e=entry('PM1');delete e.savedCriterion;e.raw={criteria:[
        {criterionId:'ACMG_AMP_2015:PM4',details:{pm1Facts:{proteinPosition:999}}},
        {criterionId:'ACMG_AMP_2015:PM1',details:{pm1Facts:{proteinPosition:130}}}]};
    const view=api.buildEvidenceDetailView(card('PM1'),freeze(e));
    assert.equal(view.fields.find(r=>r.label==='蛋白位置（aa）').value,'130');assert.ok(!JSON.stringify(view.fields).includes('999'));
});
check('actual model score and complete exact interval render without inferred cutoffs',()=>{
    const e=entry('PP3','MET',{selectedPredictionMechanism:'PROTEIN_MISSENSE',predictionMechanisms:{route:'GENERAL_LOCAL_ALPHAMISSENSE',mechanisms:[{mechanism:'PROTEIN_MISSENSE',model:'ALPHAMISSENSE',
        status:'CALIBRATED',direction:'HARMFUL',appliedStrength:'STRONG',values:{rawScore:'0.74'},
        thresholds:{lower:'0.7',upper:'0.8',lowerInclusive:true,upperInclusive:false}}]}});
    const view=api.buildEvidenceDetailView(card('PP3'),e),rows=view.sections[0].ruleRows;
    assert.deepEqual(rows.find(r=>r.label==='原始分值'),{label:'原始分值',actual:'0.74',requirement:'0.7 ≤ 分值 < 0.8',outcome:'满足'});
    const html=render('PP3',e);
    for(const text of ['已用于 PP3','AlphaMissense 模型原文','PP3／BP4 分值校准原文'])assert.ok(html.includes(text));
    delete e.savedCriterion.details.predictionMechanisms.mechanisms[0].values;
    assert.equal(api.buildEvidenceDetailView(card('PP3'),e).sections[0].ruleRows[0].outcome,'明细不全');
});
check('protein and splice directions remain separate and are not described as two adopted PP3 contributions',()=>{
    const e=entry('PP3','MET',{predictionMechanisms:{mechanisms:[
        {mechanism:'PROTEIN_MISSENSE',model:'ALPHAMISSENSE',status:'CALIBRATED',direction:'HARMFUL',appliedStrength:'STRONG'},
        {mechanism:'SPLICING',model:'SPLICEAI',status:'CALIBRATED',direction:'BENIGN',appliedStrength:'SUPPORTING'}]}});
    const view=api.buildEvidenceDetailView(card('PP3'),e);
    assert.equal(view.sections[0].role,'支持 PP3 方向');assert.equal(view.sections[1].role,'其他机制核查');
    assert.match(view.sections[1].ruleRows.find(r=>r.label==='预测结论').actual,/^支持无明显剪接影响/);
    assert.ok(!render('PP3',e).includes('机制采用强度'));
});
check('PM2 labels every recorded absence condition and keeps a strict threshold and a missing record distinct',()=>{
    const conditions=[{label:'精确变异查询',requirement:'未检出当前精确变异',actual:'NO_RECORD'},
        {label:'位点检测记录',requirement:'该位置有检测记录',actual:'HIT'},
        {label:'检测等位基因数 AN',requirement:'AN > 2000',actual:'2000'},
        {label:'Broad 捕获区域',requirement:'位于区域内',actual:'位于区域内'},
        {label:'UKB 捕获区域',requirement:'位于区域内',actual:'未保存'}];
    const e=entry('PM2','NOT_EVALUATED',{populationRule:{schemaVersion:'pm2-rule-display-v1',route:'GENERAL_ABSENCE_ONLY',conditions}});
    const rows=api.buildEvidenceDetailView(card('PM2'),e).ruleRows;
    assert.deepEqual(rows.slice(0,5).map(row=>row.outcome),['满足','满足','未满足','满足','待核实']);
    assert.equal(rows[1].actual,'该位点有检测记录');
    const html=render('PM2',e);
    assert.equal((html.match(/data-rule-outcome="满足"/g)||[]).length,3);
    assert.ok(html.includes('data-rule-outcome="未满足"'));
});
check('PM4 stop loss presents protein extension and the new stop, while an in-frame repeat can fail',()=>{
    const e=entry('PM4','MET',{proteinLengthFacts:{scope:'PROJECT_LOCAL_STOP_LOSS_SNV',predictedHgvsp:'p.(Ter249GlnextTer64)',
        referenceProteinLength:248,alteredProteinLength:312,netAminoAcidChange:64,newTerminationResolved:true,alteredStopCdsPosition:937}});
    const view=api.buildEvidenceDetailView(card('PM4'),e);
    assert.deepEqual(view.ruleRows.map(r=>r.outcome),['满足','满足','满足']);
    const html=render('PM4',e);
    for(const text of ['248 aa → 312 aa','净变化 +64 aa','CDS 第 937 位'])assert.ok(html.includes(text));
    assert.ok(!html.includes('非重复条件'));
    const inFrame=entry('PM4','NOT_MET',{proteinLengthFacts:{scope:'PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE',netAminoAcidChange:-2,
        regionState:'REPEAT_OVERLAP',queryRange:{start:10,end:11}}});
    const region=api.buildEvidenceDetailView(card('PM4'),inFrame).ruleRows.find(r=>r.label==='蛋白区域');
    assert.equal(region.outcome,'未满足');assert.match(region.actual,/10–11 aa · 与重复区域重叠/);
});
check('PM2 rule and observed values are paired without inventing AF zero or a generic cutoff',()=>{
    const e=entry('PM2','MET',{populationFacts:{dataset:'EXOMES',release:'4.1',queryStatus:'NO_RECORD',detection:{status:'HIT',adjAn:'1461824'}},
        populationRule:{schemaVersion:'pm2-rule-display-v1',route:'GENERAL_ABSENCE_ONLY',conditions:[
            {label:'检测等位基因数 AN',requirement:'AN > 2000',actual:'1461824'},
            {label:'Broad 捕获区域',requirement:'位于区域内',actual:'位于区域内'}]}});
    const html=render('PM2',e);for(const text of ['规则要求','当前变异','AN &gt; 2000','1461824','gnomAD 外显子组未检出判定条件'])assert.ok(html.includes(text));
    assert.ok(!html.includes('AF = 0'));assert.ok(!html.includes('当前规则补写'));
    delete e.savedCriterion.details.populationRule;
    assert.ok(render('PM2',e).includes('本次保存摘要缺少规则阈值'));
});
check('PM2 differing saved rule and execution values are prominently conflicting rather than satisfied',()=>{
    const e=entry('PM2','MET',{populationRule:{schemaVersion:'pm2-rule-display-v1',conditions:[{
        label:'检测量阈值资料冲突',requirement:'规则记录：AN > 2000',actual:'执行轨迹：AN > 10000',recordedOutcome:'TRUE'}]}});
    const view=api.buildEvidenceDetailView(card('PM2'),e),html=render('PM2',e);
    assert.equal(view.ruleRows[0].conflict,true);assert.equal(view.ruleRows[0].outcome,'资料冲突');assert.ok(html.includes('data-rule-conflict="true"'));
});
check('PM2 names each distinct rule source and renders a repeated link only once',()=>{
    const acmg='https://www.nature.com/articles/gim201530';
    const clingen='https://www.clinicalgenome.org/docs/pm2-recommendation-for-absence-rarity/';
    const e=entry('PM2','MET',{populationRule:{schemaVersion:'pm2-rule-display-v1',sourceUrls:[acmg,clingen,clingen]}});
    e.links=[{title:'PM2 规则出处',url:acmg+'/'}];
    const view=api.buildEvidenceDetailView(card('PM2'),freeze(e));
    assert.deepEqual(view.sources.map(({title,url})=>[title,url]),[
        ['ACMG/AMP 变异解读指南',acmg],['ClinGen SVI · PM2 稀有性建议',clingen],
    ]);
    const html=render('PM2',e);
    assert.equal((html.match(/href="https:\/\//g)||[]).length,2);
    assert.match(html,/href="https:\/\/www\.nature\.com\/articles\/gim201530"[^>]*>ACMG\/AMP 变异解读指南<\/a>/);
    assert.match(html,/href="https:\/\/www\.clinicalgenome\.org\/docs\/pm2-recommendation-for-absence-rarity\/"[^>]*>ClinGen SVI · PM2 稀有性建议<\/a>/);
    assert.ok(!html.includes('查看完整原文'));
    assert.ok(!html.includes('PM2 规则出处'));
});
check('legacy PM2 details show the more precise section once and keep other documents',()=>{
    const acmg='https://www.nature.com/articles/gim201530';
    const clingen='https://www.clinicalgenome.org/docs/pm2-recommendation-for-absence-rarity/';
    const e=entry('PM2');
    e.links=[acmg,clingen,clingen+'#recommendation'].map(url=>({title:'PM2 规则出处',url}));
    const view=api.buildEvidenceDetailView(card('PM2'),freeze(e));
    assert.deepEqual(view.sources.map(({title,url})=>[title,url]),[
        ['ACMG/AMP 变异解读指南',acmg],['ClinGen SVI · PM2 稀有性建议',clingen+'#recommendation'],
    ]);
    const html=render('PM2',e);
    assert.ok(!html.includes('PM2 规则出处'));
    assert.equal((html.match(/href="https:\/\//g)||[]).length,2);
    assert.ok(html.includes(`href="${clingen}#recommendation"`));
});
check('PM2 collapses repeated specialist rule URLs and identifies distinct published versions',()=>{
    const document='https://cspec.genome.network/cspec/ui/svi/doc/GN078';
    const first=document+'?version=1.0',second=document+'?version=1.1';
    const e=entry('PM2','MET',{populationFacts:{specificationKey:'GN078@1.1'},populationRule:{schemaVersion:'pm2-rule-display-v1',sourceUrls:[first,second]}});
    e.links=[{title:'PM2 规则出处',url:second.replace('cspec.genome.network','CSPEC.GENOME.NETWORK').replace('?','/?')}];
    const html=render('PM2',freeze(e));
    assert.equal((html.match(/href="https:\/\//g)||[]).length,2);
    assert.ok(html.includes('ClinGen 专家组规范 · GN078 v1.0'));
    assert.ok(html.includes('ClinGen 专家组规范 · GN078 v1.1'));
    assert.ok(!html.includes('PM2 规则出处'));
});
check('PS1 splice comparison keeps actual paired event scores and the recorded similarity rule distinct from RNA experiments',()=>{
    const e=entry('PS1','MET',{spliceComparisonFacts:{schemaVersion:'ps1-splice-facts-v1',transcript:'ENST000001.2',referenceScv:'SCV123456',baseline:'PVS1_STRONG',observedRna:false,
        comparison:{similarityMode:'PUBLISHED_RELATIVE_TEN_PERCENT',targetEvent:{event:'NATIVE_DONOR_LOSS',position:100,score:'0.91',profile:{DL:{position:100,score:'0.91'}}},
            referenceEvent:{event:'NATIVE_DONOR_LOSS',position:100,score:'0.94',profile:{DL:{position:100,score:'0.94'}}}}}});
    const view=api.buildEvidenceDetailView(card('PS1'),e),html=render('PS1',e);
    assert.ok(view.basisAvailable);for(const text of ['0.91','0.94','10%','RNA 实验观察','供体位点丢失比较','ClinVar 参考提交'])assert.ok(html.includes(text));
    assert.equal(view.fields.find(r=>r.label==='RNA 实验观察').value,'否');assert.ok(!html.includes('NATIVE_DONOR_LOSS'));
});
check('PVS1 start-loss and NMD escape supported branches retain sequence facts, saved conditions and sources',()=>{
    const start=entry('PVS1','MET',{pvs1Facts:{variantMechanism:'START_LOSS',startLossAssessment:{selectedTranscript:'NM_001.2',cdsPosition:1,referenceCodon:'ATG',alternateCodon:'GTG',
        candidateRestartCdsPosition:100,transcriptReviewStatus:'NOT_FOUND_IN_ASSESSED_SET',transcriptReviewComplete:true,upstreamSearchComplete:true,qualifyingReferenceCount:0,
        sources:[{url:'https://example.org/pvs1',title:'原始规范'}]}}});
    const startHtml=render('PVS1',start);for(const text of ['起始密码子丢失核查','ATG','GTG','合格独立致病参考数','原始规范'])assert.ok(startHtml.includes(text));
    const escape=entry('PVS1','MET',{pvs1Facts:{nmdPrediction:'NMD_NOT_PREDICTED',ptcStart:201,distanceToLastJunction:30,
        escapeAdoptionProof:{proteinEffect:{firstAlteredResidue:67,referenceProteinLength:100,referenceResiduesAffected:34},
        ruleChecks:[{rule:{predicate:'PROTEIN_LOSS_FRACTION',metric:'REFERENCE_TAIL_ALTERED_OR_REMOVED',comparison:'GT',numerator:1,denominator:10},status:'MATCH',actualNumerator:34,actualDenominator:100}],
        positionConditions:{escape:{conditions:[{coordinate:'ALTERED_PTC_SPAN',minimumInclusive:100,maximumExclusive:300,checkedPositions:[201,203],status:'PASS'}]}},sources:[{url:'https://example.org/escape'}]}}});
    const escapeHtml=render('PVS1',escape);for(const text of ['预计不触发 NMD','34/100','1/10','位置 ≥ 100','位置 &lt; 300','201；203'])assert.ok(escapeHtml.includes(text));
    assert.ok(!escapeHtml.includes('PROTEIN_LOSS_FRACTION'));
});
check('PVS1 reads saved ordinary nonsense and junction facts without inventing an NMD decision',()=>{
    const facts={sequenceDerivationStatus:'GENOMIC_CDS_HGVSC_AND_PROTEIN_AGREE',cdsPosition:1242,ptcStart:1240,ptcEnd:1242,
        lastJunctionCdsPosition:1315,distanceToLastJunction:73,nmdPrediction:'PREDICTED_NMD'};
    const saved=entry('PVS1','MET',{pvs1Facts:facts});
    const view=api.buildEvidenceDetailView(card('PVS1'),saved);
    const effect=view.ruleRows.find(row=>row.label==='变异后果');
    assert.equal(effect.actual,'无义变异');
    assert.equal(effect.outcome,'满足');
    const nmd=api.buildEvidenceDetailView(card('PVS1'),entry('PVS1','NOT_EVALUATED',{pvs1Facts:facts}));
    assert.equal(nmd.ruleRows.find(row=>row.label.includes('NMD')).outcome,'');
    assert.ok(view.fields.some(row=>row.label.includes('末次外显子连接位置')&&row.value==='1315'));
});
check('two cases from one paper remain distinct while repeated original region is deduplicated',()=>{
    const citation={unitId:'/article[1]/body[1]/p[1]',quote:'Both cases were reported.'};
    const caseFact=n=>({pmcid:'PMC123456',caseKey:'c'+n,caseLabel:'病例 '+n,reportedFacts:[{field:'reportedZygosity',value:'HOMOZYGOUS',citation}]});
    const e=entry('PM3','NOT_EVALUATED');e.pm3LiteratureFacts={caseFacts:[caseFact(1),caseFact(2)]};
    const view=api.buildEvidenceDetailView(card('PM3'),freeze(e));
    assert.equal(view.papers.length,1);assert.equal(view.papers[0].cases.length,2);assert.equal(view.papers[0].sources.length,1);
    assert.ok(render('PM3',e).includes('纯合'));assert.ok(!render('PM3',e).includes('reportedZygosity'));
});
check('complementary records from the same case merge fields and sources rather than silently overwrite',()=>{
    const e=entry('PM3','NOT_EVALUATED');e.pm3LiteratureFacts={caseFacts:[
        {pmcid:'PMC123456',caseKey:'same',reportedFacts:[{field:'reportedZygosity',value:'HOMOZYGOUS',citation:{unitId:'/article[1]/body[1]/p[1]',quote:'Homozygous.'}}]},
        {pmcid:'PMC123456',caseKey:'same',reportedFacts:[{field:'parentalTransmission',value:'Both parents carried the allele.',citation:{unitId:'/article[1]/body[1]/p[2]',quote:'Both parents carried the allele.'}}]}]};
    const view=api.buildEvidenceDetailView(card('PM3'),e);assert.equal(view.papers.length,1);assert.equal(view.papers[0].cases.length,1);
    assert.equal(view.papers[0].cases[0].rows.length,2);assert.equal(view.papers[0].sources.length,2);
});
check('original paragraph text is a source rather than a fabricated extracted field value',()=>{
    const e=entry('PM3','NOT_EVALUATED');e.pm3LiteratureFacts={caseFacts:[{pmcid:'PMC123456',caseKey:'one',reportedFacts:[
        {field:'reportedPhase',citation:{unitId:'/article[1]/body[1]/p[1]',quote:'A long paragraph with several variants and no extracted phase.'}}]}]};
    const view=api.buildEvidenceDetailView(card('PM3'),e);
    assert.equal(view.papers[0].cases[0].rows.length,0);assert.equal(view.papers[0].sources.length,1);
});
check('known non-target paper cannot supply the current variant facts',()=>{
    const e=entry('PM3','NOT_EVALUATED');e.pm3LiteratureFacts={caseFacts:[{pmcid:'PMC123456',targetMatch:'DIFFERENT_TARGET',reportedFacts:[{field:'targetVariant',value:'c.999A>G'}]}]};
    assert.equal(api.buildEvidenceDetailView(card('PM3'),e).papers.length,0);
});
check('unresolved target correspondence is shown as unconfirmed paper information rather than adopted material',()=>{
    const e=entry('PM3','NOT_EVALUATED');e.pm3LiteratureFacts={caseFacts:[{pmcid:'PMC123456',caseKey:'one',targetMatch:'UNRESOLVED',reportedFacts:[{field:'reportedZygosity',value:'HOMOZYGOUS'}]}]};
    const html=render('PM3',e);assert.ok(html.includes('目标对应关系待核查'));assert.ok(!html.includes('本次采用资料'));
});
check('paper title and complete-original action occur once per paper rather than once per screenshot',()=>{
    const e=entry('PM3','NOT_EVALUATED');e.pm3LiteratureFacts={caseFacts:[{pmcid:'PMC123456',caseKey:'one',reportedFacts:[
        {field:'reportedZygosity',value:'HOMOZYGOUS',citation:{title:'One saved article title',unitId:'/article[1]/body[1]/p[1]',quote:'A paragraph.'}},
        {field:'parentalTransmission',value:'Both parents were carriers.',citation:{title:'One saved article title',unitId:'/article[1]/body[1]/fig[1]/caption[1]',quote:'A caption.'}}]}]};
    const html=render('PM3',e);assert.equal(html.split('One saved article title').length-1,1);
    assert.equal(html.split('查看完整原文').length-1,1);assert.ok(html.includes('原文图注'));
});
check('the screenshot request keeps exact article and supplement references without invalidating them with other locator formats',()=>{
    const base={pmcid:'PMC123456',quote:'Verified original'};
    const body={...base,locator:'/article[1]/body[1]/p[1]'};
    const attachment={...base,locator:`attachment:${'a'.repeat(64)}:word/document.xml:/document/body/tbl[1]/tr[1]/tc[2]`};
    const outside={...base,locator:`attachment:${'b'.repeat(64)}:../private.txt:/document/body/p[1]`};
    assert.deepEqual(api.renderableLiteratureReferences([outside,body,attachment,body,{...base,locator:'/private/file'}]),[body,attachment]);
});
check('literature used by an experimental criterion joins the same original-source card on a non-adopted result',()=>{
    const e=entry('BP7','NOT_EVALUATED',{bp7:{experimentCheckStatus:'REVIEW_REQUIRED',experimentSources:[
        {pmid:'123456',url:'https://pubmed.ncbi.nlm.nih.gov/123456/',locator:'Figure 2'}]}});
    const html=render('BP7',e);
    assert.ok(html.includes('资料信息提取'));
    assert.ok(html.includes('原文依据'));
    assert.equal(html.split('查看完整原文').length-1,1);
    assert.ok(html.includes('https://pubmed.ncbi.nlm.nih.gov/123456/'));
});
check('failed shared image preserves every distinct original excerpt and validates exact paper identity',()=>{
    const image='/variant-analyses/acmg/source-view/papers/PMC123456/'+'a'.repeat(24)+'/regions/'+'b'.repeat(64)+'.png';
    const sources=[{pmcid:'PMC123456',locator:'/article[1]/body[1]/p[1]',quote:'First excerpt.'},{pmcid:'PMC123456',locator:'/article[1]/body[1]/p[2]',quote:'Second excerpt.'}];
    const regions=sources.map(source=>({...source,status:'AVAILABLE',imageUrl:image}));
    assert.equal(api.evidenceSourceRows(sources,regions).length,1);
    const fallback=api.evidenceSourceRows(sources,regions,new Set([image]));assert.equal(fallback.length,2);assert.ok(fallback.every(row=>!row.showImage));
    assert.equal(api.evidenceSourceRows(sources,regions.map(r=>({...r,imageUrl:image.replace('PMC123456','PMC999')}))).filter(row=>row.showImage).length,0);
    assert.equal(api.evidenceSourceRows(sources,regions.map(r=>({...r,quote:'Wrong original quote.'}))).filter(row=>row.showImage).length,0);
});
check('reviewed figure regions match the exact saved case and digests and fall back to the original link',()=>{
    const figure={sourceSha256:'a'.repeat(64),reviewSha256:'b'.repeat(64),caseLabel:'Case 1'};
    const source={pmcid:'PMC123456',locator:'figure-one · A · Case 1 · II-1',quote:'Genotypes are shown.',figure,
        url:'https://pmc.ncbi.nlm.nih.gov/articles/PMC123456/'};
    const {url,...ref}=source;
    assert.deepEqual(api.renderableLiteratureReferences([source,{...source,figure:undefined}]),[ref]);
    const imageUrl=`/variant-analyses/acmg/source-view/papers/${source.pmcid}/${figure.sourceSha256}/regions/${'c'.repeat(64)}.png`;
    const region={...ref,status:'AVAILABLE',imageUrl,locationLabel:'Figure 1A · Case 1'};
    const [row]=api.evidenceSourceRows([source],[region]);
    assert.equal(row.showImage,true);assert.equal(row.fullTextUrl,url);
    assert.equal(api.cacheableSourceRegions([ref],[region]),true);
    for(const wrong of [{...region,figure:{...figure,caseLabel:'Case 2'}},
        {...region,figure:{...figure,reviewSha256:'d'.repeat(64)}},
        {...region,imageUrl:imageUrl.replace(figure.sourceSha256,'e'.repeat(64))}]){
        assert.equal(api.evidenceSourceRows([source],[wrong])[0].showImage,false);
        assert.equal(api.cacheableSourceRegions([ref],[wrong]),false);
    }
});
check('public-source asset URLs follow the existing axios API prefix and quote-only results are retryable on reopening',()=>{
    const previous=axios.defaults.baseURL;axios.defaults.baseURL='https://example.invalid/api';
    try{
        const source={pmcid:'PMC123456',locator:'/article[1]/body[1]/p[1]',quote:'A statement.'};
        const base='/variant-analyses/acmg/source-view/papers/PMC123456/'+'a'.repeat(24);
        const region={...source,status:'AVAILABLE',imageUrl:base+'/regions/'+'b'.repeat(64)+'.png',fullTextUrl:base+'/fulltext#source-'+'c'.repeat(20)};
        const row=api.evidenceSourceRows([source],[region])[0];assert.equal(row.imageUrl,'https://example.invalid/api'+region.imageUrl);
        assert.equal(row.fullTextUrl,'https://example.invalid/api'+region.fullTextUrl);
        assert.equal(api.cacheableSourceRegions([source],[region]),true);
        assert.equal(api.cacheableSourceRegions([source],[{...source,status:'QUOTE_ONLY'}]),false);
    }finally{axios.defaults.baseURL=previous;}
});
check('unresolved information is displayed once and conflict sides and execution failures stay visible',()=>{
    const e=entry('PM1','NOT_EVALUATED',{pm1Facts:{proteinPosition:130}});const html=render('PM1',e);
    assert.equal(html.split('蛋白位置（aa）').length-1,1);assert.ok(html.includes('已有信息'));
    const conflict=entry('PS2','NOT_EVALUATED',{deNovoFacts:{events:[{ordinal:1,disposition:'EXCLUDED',factComparisons:[
        {memberRole:'MOTHER',ordinaryResult:'PRESENT',specialEventResult:'ABSENT'}]}]}});
    const conflictHtml=render('PS2',conflict);for(const text of ['母亲','检出目标变异','未检出目标变异','来源差异'])assert.ok(conflictHtml.includes(text));
    const failure=entry('PP3','NOT_EVALUATED');failure.executionDiagnostics=[{message:'本地剪接推理超过本次运行时限。',stage:'剪接推理'}];
    assert.ok(render('PP3',failure).includes('本地剪接推理超过本次运行时限。'));
});
check('unverified saved display never promotes stored facts into accepted core materials',()=>{
    const e=entry('PM1','MET',{pm1Facts:{proteinPosition:130}});e.displayIssue=true;
    const view=api.buildEvidenceDetailView(card('PM1'),e);
    assert.equal(view.adopted,false);assert.equal(view.fields.length,0);assert.ok(view.failure.length>0);
});
check('PVS1 RNA history keeps byte checks while hiding an out-of-scope function declaration and excerpt',()=>{
    const sourceFacts={schemaVersion:'pvs1-full-rna-initiation-assessment-v1',methodVersion:'1.0.0',status:'NOT_EVALUATED',
        sourceDeclaredStartReview:{startStatus:'OBSERVED',alteredStartRna1:9,
        functionFinding:'FUNCTION_PRESERVED_IN_ASSESSED_SCOPE'},sourceChecks:[{sourceId:'restart',role:'RESTART_REVIEW',status:'RAW_SHA_MATCH'}],
        sourceQuotes:[{text:'Example start review excerpt'}]};
    const detail=initiation=>JSON.stringify(api.fullRnaDetails({code:'PVS1',ready:true,preparation:{},details:{assessmentKind:'INITIATION_LOSS',initiation,
        policySources:[{sourceId:'restart',url:'https://example.org/restart'},{sourceId:'rule',url:'https://example.org/rule'}]},
        claim:{originalStatus:'NOT_EVALUATED',originalStrength:'NONE'}}));
    for(const fault of [{reasonCode:'PVS1_RNA_INITIATION_SOURCE_PRODUCT_SCOPE_MISMATCH'},
        {reasonCode:'PVS1_RNA_INITIATION_SOURCE_LOCATION_INVALID'},{reasonCode:'PVS1_RNA_INITIATION_SOURCE_ROLE_AMBIGUOUS'},
        {sourceDocumentsQualified:false}]){
        const text=detail({...sourceFacts,...fault});
        assert.ok(text.includes('已核对原文一致性'));
        assert.ok(text.includes('https://example.org/rule'));
        for(const rejected of ['资料记载的功能结果','资料记载的起始位置','Example start review excerpt','https://example.org/restart'])assert.ok(!text.includes(rejected));
    }
    const qualified=detail({...sourceFacts,sourceDocumentsQualified:true,reviewedSourceIds:['restart']});
    assert.ok(qualified.includes('检查范围内功能保留'));
    assert.ok(qualified.includes('Example start review excerpt'));
    assert.ok(!qualified.includes('原文报告的起始，已核对序列'));
    assert.ok(detail({...sourceFacts,sourceDocumentsQualified:true,sourceSpecifiedStart:{startStatus:'OBSERVED',alteredStartRna1:9}})
        .includes('原文报告的起始，已核对序列'));
});
check('an exact source with several RNA products remains unadopted and shows both observed conditions',()=>{
    const reasonCode='FULL_RNA_SOURCE_MULTIPLE_OUTCOMES_UNRESOLVED';
    const e=entry('PVS1','NOT_EVALUATED');
    const view={code:'PVS1',ready:false,application:'NOT_ADOPTED',
        claim:{normalizedReasonCode:reasonCode,originalStatus:'NOT_EVALUATED',originalStrength:'NONE'},
        preparation:{reasonCode,assayConditions:{outcomeMultiplicity:'MULTIPLE_OUTCOMES',
            residualNormalTranscript:'DETECTED_IN_ASSESSED_SCOPE'}}};
    api.rememberFullRnaProjection(e.evaluation,e.savedCriterion,{}, {claimed:true,valid:true,view});
    const html=render('PVS1',e);
    for(const label of ['已观察到多种后果','本次检查范围内也记录了正常转录本','当前软件尚不支持联合判定'])
        assert.ok(html.includes(label),label);
    assert.ok(!html.includes('缺少信息'));
    assert.ok(!html.includes('MULTIPLE_OUTCOMES'));
});
check('RNA source with unverified allele binding explains the source gap without attributing products',()=>{
    const e=entry('PVS1','NOT_EVALUATED');
    const view={code:'PVS1',ready:false,application:'NOT_ADOPTED',
        claim:{normalizedReasonCode:'PVS1_FULL_RNA_SOURCE_REQUIRED',originalStatus:'NOT_EVALUATED',originalStrength:'NONE'},
        preparation:{reasonCode:'FULL_RNA_SOURCE_PHYSICAL_ALLELE_SCOPE_REQUIRED'}};
    api.rememberFullRnaProjection(e.evaluation,e.savedCriterion,{}, {claimed:true,valid:true,view});
    const html=render('PVS1',e);
    assert.ok(html.includes('等位基因来源核查'));
    assert.ok(html.includes('当前无法确认 RNA 产物来自本次变异'));
    assert.ok(!html.includes('已观察到多种后果'));
    assert.ok(!html.includes('FULL_RNA_SOURCE_PHYSICAL_ALLELE_SCOPE_REQUIRED'));
});
check('RNA pending comparison distinguishes saved source assertions from checked material',()=>{
    const view={ready:true,claim:{normalizedReasonCode:'PVS1_RNA_INITIATION_SOURCE_PRODUCT_SCOPE_MISMATCH'},preparation:{},
        details:{initiation:{reasonCode:'PVS1_RNA_INITIATION_SOURCE_PRODUCT_SCOPE_MISMATCH',sourceDeclaredStartReview:{startStatus:'SOURCE_REVIEWED_PUTATIVE'}}}};
    assert.ok(!api.fullRnaPending(view).known.includes('候选起始'));
    view.details.initiation.reasonCode=view.claim.normalizedReasonCode='PVS1_RNA_INITIATION_FUNCTION_SOURCE_LOCATION_INVALID';
    assert.ok(!api.fullRnaPending(view).known.includes('候选起始'));
    view.details.initiation.reasonCode=view.claim.normalizedReasonCode='PVS1_RNA_INITIATION_UPSTREAM_REVIEW_INCOMPLETE';
    view.details.initiation.sourceSpecifiedStart={startStatus:'SOURCE_REVIEWED_PUTATIVE',alteredStartRna1:9};
    assert.ok(api.fullRnaPending(view).known.includes('候选起始'));
    view.details.initiation.sourceDocumentsQualified=false;
    assert.ok(!api.fullRnaPending(view).known.includes('候选起始'));
});
check('PVS1 RNA details distinguish incomplete experiment review from contradictory reviews',()=>{
    const code='PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_REVIEW_INCOMPLETE';
    const initiation={reasonCode:code,sourceDocumentsQualified:true,functionalReviewConditions:{observedStartReported:true,
        physiologicalRelevanceReviewed:true,sameRnaProductExperimentReviewed:false}};
    const saved=normalizedReasonCode=>({code:'PVS1',ready:true,application:'NOT_ADOPTED',preparation:{},
        claim:{normalizedReasonCode,originalStatus:'NOT_EVALUATED',originalStrength:'NONE'},details:{initiation}});
    const pending=api.fullRnaPending(saved(code));
    assert.equal(pending.gap,'针对同一异常 RNA 产物的功能实验审核');
    assert.equal(pending.known,'来源记录报告了实测起始。已记录疾病机制相关性审核。');
    assert.ok(pending.conclusion.includes('实验审核未完成'));
    const legacy={...saved(code),details:{}};
    assert.equal(api.fullRnaPending(legacy).gap,'本次保存记录未列明缺失的具体审核项目。');
    const unqualified={...saved(code),details:{initiation:{...initiation,sourceDocumentsQualified:false}}};
    assert.equal(api.fullRnaPending(unqualified).gap,api.fullRnaPending(legacy).gap);
    assert.equal(api.fullRnaPending(unqualified).known,api.fullRnaPending(legacy).known);
    for(const [availableStartStatus,text] of [['SOURCE_REVIEWED_PUTATIVE','候选起始，实际翻译尚未证实'],
        ['UNKNOWN','翻译起始尚未确定'],['NOT_ASSESSED','尚未评估翻译起始']]){
        const candidate={...initiation,availableStartStatus,functionalReviewConditions:{...initiation.functionalReviewConditions,observedStartReported:false}};
        const comparison=api.fullRnaPending({...saved(code),details:{initiation:candidate}});
        assert.ok(comparison.known.includes(text));
        assert.ok(comparison.gap.includes('实测起始位置'));
    }
    const renderSaved=reason=>{
        const e=entry('PVS1','NOT_EVALUATED');
        e.evaluation.evaluationResult.reasonCode=reason;
        api.rememberFullRnaProjection(e.evaluation,e.savedCriterion,{}, {claimed:true,valid:true,view:saved(reason)});
        return {view:api.buildEvidenceDetailView(card('PVS1'),e),html:render('PVS1',e)};
    };
    const incomplete=renderSaved(code);
    assert.ok(incomplete.html.includes('针对同一异常 RNA 产物的功能实验审核'));
    assert.equal(incomplete.view.conflicts.length,0);
    const parentPending=renderSaved('PVS1_RNA_INITIATION_PARENT_FUNCTIONAL_RESCUE_REVIEW_REQUIRED');
    assert.ok(parentPending.html.includes('上层 PVS1 审核的功能救援结论'));
    assert.ok(parentPending.html.includes('功能救援审核尚未完成'));
    assert.equal(parentPending.view.conflicts.length,0);
    const conflict=renderSaved('PVS1_RNA_INITIATION_FUNCTIONAL_RESCUE_FACTS_CONFLICT');
    assert.equal(conflict.view.presentation.mainKind,'NO_CONCLUSION');
    assert.equal(conflict.view.presentation.outcome.kind,'CONFLICT');
    assert.ok(!conflict.html.includes('缺少信息'));
    for(const label of ['功能救援审核冲突','本次起始资料审核','上层功能缺失审核','本次不采用 PVS1'])
        assert.ok(conflict.html.includes(label),label);
});
check('RNA details retain checked alternatives and references while separating legacy failed findings and unused sources',()=>{
    const base={schemaVersion:'pvs1-full-rna-initiation-assessment-v1',methodVersion:'1.0.0',status:'NOT_EVALUATED',
        sourceDocumentsQualified:true,reasonCode:'PVS1_RNA_INITIATION_FUNCTION_SOURCE_LOCATION_INVALID',
        transcriptReviewScope:{complete:true},transcriptFindings:[{transcript:'ENST000002.1',functionallyRelevant:true,
            targetUnaffected:true,qualification:'REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE'}],
        sourceQuotes:[{text:'Example alternative transcript location'}],
        sourceDocumentSet:{requiredSourceIds:['primary','transcripts','restart','upstream']}};
    const urls=['primary','transcripts','restart','upstream','independent-rule'].map(id=>({sourceId:id,url:'https://example.org/'+id}));
    const read=initiation=>JSON.stringify(api.fullRnaDetails({code:'PVS1',ready:true,preparation:{},selectedTranscript:'ENST000001.1',
        details:{assessmentKind:'INITIATION_LOSS',initiation,policySources:urls,source:{url:'https://example.org/bound-rule'}},
        claim:{originalStatus:'NOT_EVALUATED',originalStrength:'NONE'}}));
    const old=read(base);
    for(const invalid of ['ENST000002.1','已完成对应范围审核','核查完整','Example alternative transcript location','https://example.org/transcripts'])
        assert.ok(!old.includes(invalid),invalid);
    assert.ok(old.includes('https://example.org/independent-rule'));
    const early=read({...base,sourceDocumentsQualified:false,sourceDocumentSet:undefined,reasonCode:'PVS1_RNA_INITIATION_SOURCE_REVIEW_REQUIRED'});
    assert.ok(early.includes('https://example.org/bound-rule'));
    assert.ok(!early.includes('https://example.org/transcripts'));
    const newResult=read({...base,reasonCode:'PVS1_RNA_INITIATION_UPSTREAM_SOURCE_LOCATION_INVALID',
        reviewedSourceIds:['primary','transcripts'],transcriptSetComplete:true});
    for(const accepted of ['ENST000002.1','已完成对应范围审核','核查完整','Example alternative transcript location','https://example.org/transcripts'])
        assert.ok(newResult.includes(accepted),accepted);
    assert.ok(!newResult.includes('https://example.org/upstream'));
    assert.ok(!newResult.includes('https://example.org/restart'));
    const legacyCompleted=read({...base,status:'NOT_APPLICABLE',reasonCode:'PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_CONFIRMED',
        sourceChecks:[{sourceId:'transcripts',role:'TRANSCRIPT_FUNCTION_REVIEW',status:'RAW_SHA_MATCH'},
            {sourceId:'upstream',role:'UPSTREAM_REVIEW',status:'RAW_SHA_MATCH'},{sourceId:'restart',role:'RESTART_REVIEW',status:'RAW_SHA_MATCH'}],
        transcriptFindings:[],sourceSpecifiedStart:{startStatus:'OBSERVED',alteredStartRna1:9}});
    assert.ok(legacyCompleted.includes('https://example.org/restart'));
    assert.ok(legacyCompleted.includes('原文报告的起始，已核对序列'));
    assert.ok(!legacyCompleted.includes('https://example.org/transcripts'));
    assert.ok(!legacyCompleted.includes('Example alternative transcript location'));
    const oldSelected=read({...base,reasonCode:'PVS1_RNA_INITIATION_UPSTREAM_REVIEW_INCOMPLETE',
        transcriptFindings:[{...base.transcriptFindings[0],transcript:'ENST000001.1'},...base.transcriptFindings]});
    assert.ok(!oldSelected.includes('ENST000001.1'));
    assert.ok(oldSelected.includes('ENST000002.1'));
    assert.ok(!oldSelected.includes('Example alternative transcript location'));
});
check('rescue conflict shows the precise verified fact and original excerpt once',()=>{
    const reason='PVS1_RNA_INITIATION_FUNCTIONAL_RESCUE_FACTS_CONFLICT',sha='a'.repeat(64),url='https://example.org/functional-assay';
    const excerpt='The original functional observation.';
    const saved=(finding,quoteSha=sha)=>{
        const e=entry('PVS1','NOT_EVALUATED');e.evaluation.evaluationResult.reasonCode=reason;
        const view={code:'PVS1',selectedTranscript:'ENST000001.2',ready:true,application:'NOT_ADOPTED',preparation:{},
            claim:{normalizedReasonCode:reason,originalStatus:'NOT_EVALUATED',originalStrength:'NONE'},details:{
                policySources:[{sourceId:'primary',sha256:sha,url}],initiation:{schemaVersion:'pvs1-full-rna-initiation-assessment-v1',methodVersion:'1.0.0',
                    sourceDocumentsQualified:true,reasonCode:reason,reviewedSourceIds:['primary'],sourceChecks:[{
                        sourceId:'primary',role:'PRIMARY_EVIDENCE',status:'RAW_SHA_MATCH',actualRawSha256:sha}],
                    sourceQuotes:[{sourceId:'primary',sourceRawSha256:quoteSha,text:excerpt}],...finding}}};
        api.rememberFullRnaProjection(e.evaluation,e.savedCriterion,{}, {claimed:true,valid:true,view});
        return {detail:api.buildEvidenceDetailView(card('PVS1'),e),html:render('PVS1',e)};
    };
    const product=saved({sourceSpecifiedStart:{startStatus:'OBSERVED',alteredStartRna1:9}});
    assert.match(product.detail.conflicts[0].left.value,/第 9 位起始的产物/);
    assert.equal(product.detail.conflicts[0].left.sources[0].url,url);
    assert.equal(product.html.split(excerpt).length,2);
    assert.equal(product.html.split(url).length,2);
    const alternate=saved({transcriptFindings:[{transcript:'ENST000002.1',qualification:'REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE',
        functionallyRelevant:true,targetUnaffected:true}]});
    assert.match(alternate.detail.conflicts[0].left.value,/转录本 ENST000002\.1/);
    const unverified=saved({},'b'.repeat(64));
    assert.deepEqual(unverified.detail.conflicts[0].left.sources,[]);
    assert.ok(!unverified.html.includes(excerpt));
});
check('concise production entry uses new cards and omits legacy JSON accordions',()=>{
    const e=entry('PP3','MET',{predictionMechanisms:{primaryMechanism:'PROTEIN_MISSENSE'}});
    e.details=[['规则详情',JSON.stringify({secret:'HIDDEN_SENTINEL'})]];
    const html=renderToStaticMarkup(React.createElement(api.EvidenceEntryDetails,{card:card('PP3'),entry:e,concise:true}));
    assert.ok(html.includes('evidence-core-details-PP3'));for(const word of ['HIDDEN_SENTINEL','完整证据字段与来源标识','技术记录','判定核查与条件对照'])assert.ok(!html.includes(word));
});
for(const filename of process.argv.slice(2))check('saved result projects all evidence read-only: '+path.basename(filename),()=>{
    const bytes=fs.readFileSync(filename),run=freeze(JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/,''))),before=JSON.stringify(run);
    const coverage=new Set();let sources=0,rows=0;
    for(const c of run.candidates||[])for(let i=0;i<(c.attempts||[]).length;i++)for(const crd of api.backgroundEvidenceCards(c,run.enabledCriteria,run,i))for(const e of crd.entries){
        const view=api.buildEvidenceDetailView(crd,e,c.attempts[i]?.context);if(!view)continue;
        coverage.add(crd.code);sources+=view.papers.flatMap(p=>p.sources).length;rows+=view.fields.length+view.papers.flatMap(p=>p.cases.flatMap(x=>x.rows)).length;
        const html=render(crd.code,e);for(const text of ['context-sha256-','/modules/','&quot;schemaVersion&quot;','完整证据字段与来源标识','技术记录'])assert.ok(!html.includes(text),crd.code+': '+text);
    }
    assert.ok(coverage.size>0);assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(filename),bytes);
    console.log(JSON.stringify({criteria:[...coverage],coreRows:rows,originalRefs:sources}));
});
console.log(`${checks} evidence detail contracts passed.`);
