// UI transport controls; synthetic outcome examples do not qualify sources or award PP2.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {Module}=require('node:module');
const {buildSync}=require('esbuild');
const root=path.resolve(__dirname,'..');
const bundle=buildSync({stdin:{contents:`export {pp2HcmDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgPp2HcmDetails.js';
export {buildEvidenceDetailView} from './src/pages/varvis/ChromosomeVisualization/utils/evidenceDetailView.js';`,resolveDir:root},
    bundle:true,platform:'node',format:'cjs',packages:'external',write:false,logLevel:'silent'});
const mod=new Module(path.join(root,'scripts/pp2-hcm-check-bundle.cjs'));
mod.filename=mod.id;mod.paths=[path.join(root,'node_modules')];mod._compile(bundle.outputFiles[0].text,mod.filename);
const {pp2HcmDetails,buildEvidenceDetailView}=mod.exports;
const freeze=value=>{if(value&&typeof value==='object'){Object.values(value).forEach(freeze);Object.freeze(value);}return value;};
const facts=()=>({schemaVersion:'pp2-general-facts-v1',method:'PP2_PUBLISHED_HCM_ENRICHMENT_WITH_PHENOTYPE_EXCLUSION_V1',methodVersion:'1.0.0',
    scope:'PROJECT_RESEARCH_PUBLISHED_HCM_ENRICHMENT',specificationKey:'GN100@1.0',declaredTranscript:'NM_001018005.2',executionTranscript:'ENST00000403994.9',
    hcmSourceMethod:{scope:{preferredTranscript:'NM_001018005.2',unversionedSourceEnsembl:'ENST00000403994'}},
    otherPhenotypeReview:{outcome:'UNKNOWN',requiredScope:[{scope:'EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS',status:'INCOMPLETE',targetRecords:0,qualifiedRecords:0}],
        pending:[{reasonCode:'PP2_TARGET_NATIVE_EXPERT_SCOPE_NOT_ESTABLISHED'}],qualifiedEvidence:[]}});
let checks=0;
const check=(name,fn)=>{fn();checks++;console.log('PASS '+name);};

check('current HCM method uses enrichment and keeps both transcript identities',()=>{
    const view=pp2HcmDetails(facts(),{mainKind:'NO_CONCLUSION'});
    assert.ok(view.requirement.includes('病例富集'));assert.ok(!view.requirement.includes('良性错义变异较少'));
    assert.equal(view.fields.find(row=>row.label==='执行转录本').value,'ENST00000403994.9');
    assert.equal(view.fields.find(row=>row.label==='规范首选转录本').value,'NM_001018005.2');
    assert.equal(view.fields.find(row=>row.label==='规范列名的 Ensembl 转录本').value,'ENST00000403994');
});
check('unknown and unrelated method versions do not borrow HCM interpretation',()=>{
    assert.equal(pp2HcmDetails({...facts(),methodVersion:'99.0.0'}),null);
    assert.equal(pp2HcmDetails({...facts(),scope:'PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS'}),null);
});
check('zero target records remain an unresolved scope',()=>{
    const view=pp2HcmDetails(facts(),{mainKind:'NO_CONCLUSION'});
    assert.ok(view.missing.includes('尚未建立'));assert.ok(view.comparisons[0].known.includes('尚未完成'));
    assert.ok(!JSON.stringify(view).includes('0 / 0'));
});
check('no observation reports the actual saved dependency gap',()=>{
    const input=facts();delete input.otherPhenotypeReview;
    const view=pp2HcmDetails(input,{mainKind:'NO_CONCLUSION',reasonCode:'PP2_HCM_OTHER_PHENOTYPE_OBSERVATION_REQUIRED'});
    assert.ok(view.missing.includes('尚未取得'));assert.equal(view.comparisons.length,1);
});
check('engineering adopted example only presents the saved scope and hides pending placeholders',()=>{
    const input=facts();input.otherPhenotypeReview={outcome:'NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE',pending:[],qualifiedEvidence:[],requiredScope:[]};
    const view=pp2HcmDetails(input,{mainKind:'ADOPTED_MET'});
    assert.equal(view.comparisons.length,0);assert.equal(view.missing,'');assert.ok(view.summary.includes('已完成范围内'));
    assert.ok(!view.summary.includes('不存在'));
    assert.equal(pp2HcmDetails(input,{mainKind:'NOT_MET'}).summary,'');
});
check('other phenotype retains original expert facts without issuing a benign classification',()=>{
    const input=facts();input.otherPhenotypeReview.outcome='SUPPORTS_OTHER_PHENOTYPE';
    input.otherPhenotypeReview.qualifiedEvidence=[{scv:'SCV000000001.1',originalClassification:'Pathogenic',disease:{label:'工程界面示例疾病'}}];
    const before=JSON.stringify(input),view=pp2HcmDetails(freeze(input),{mainKind:'NOT_MET'});
    assert.ok(view.summary.includes('未满足'));assert.ok(!view.summary.includes('良性'));
    assert.equal(view.sections[0].rows.find(row=>row.label==='专家原分类').value,'致病');assert.equal(JSON.stringify(input),before);
});
check('internal strings and raw source objects never become detail rows',()=>{
    const input=facts();input.rawJson={unused:'RAW_SECRET_JSON'};input.otherPhenotypeReview.pending.push({reasonCode:'INTERNAL_UNKNOWN_TOKEN',raw:'RAW_SECRET_JSON'});
    const rendered=JSON.stringify(pp2HcmDetails(input,{mainKind:'NO_CONCLUSION'}));
    assert.ok(!rendered.includes('RAW_SECRET_JSON'));assert.ok(!rendered.includes('INTERNAL_UNKNOWN_TOKEN'));
});
check('saved conflict records identify the actual record and disease without raw JSON',()=>{
    const input=facts();input.otherPhenotypeReview.outcome='CONFLICT';
    input.otherPhenotypeReview.conflicts=[{referenceId:'reference1033090',scv:'SCV002032337.1',disease:{label:'工程冲突记录疾病'},reasonCode:'PP2_NATIVE_EXPERT_ASSERTION_SOURCE_CONFLICT'}];
    const view=pp2HcmDetails(input,{mainKind:'NO_CONCLUSION'});
    assert.ok(view.sections.some(section=>section.title==='冲突资料 1'&&section.rows.some(row=>row.value==='SCV002032337.1')));
    assert.ok(JSON.stringify(view).includes('工程冲突记录疾病'));assert.ok(!JSON.stringify(view).includes('PP2_NATIVE_EXPERT_ASSERTION_SOURCE_CONFLICT'));
});
check('saved conflicting values appear on separate sides without inventing a missing side',()=>{
    const input=facts();input.otherPhenotypeReview.outcome='CONFLICT';
    input.otherPhenotypeReview.conflicts=[{field:'classification',reasonCode:'PP2_NATIVE_EXPERT_ASSERTION_SOURCE_CONFLICT',
        left:{label:'原专家断言',value:'Pathogenic'},right:{label:'原专家汇总',value:'Benign'}}];
    const view=pp2HcmDetails(input,{mainKind:'NO_CONCLUSION'});
    assert.equal(view.conflicts.length,1);assert.equal(view.conflicts[0].left.value,'致病');assert.equal(view.conflicts[0].right.value,'良性');
    assert.equal(view.sections.length,0);
});
check('every known saved difference remains a separate comparison with original record identities',()=>{
    const input=facts();input.otherPhenotypeReview.outcome='CONFLICT';
    const sides=(left,right)=>({left:{value:left,sourceRecordId:'SCV002032337.1'},right:{value:right,sourceRecordId:'11111111-1111-4111-8111-111111111111'}});
    input.otherPhenotypeReview.conflicts=[{reasonCode:'PP2_NATIVE_EXPERT_ASSERTION_SOURCE_CONFLICT',differences:[
        {field:'classification',...sides('Pathogenic','Benign')},{field:'diseaseId',...sides('MONDO:0005045','MONDO:0005021')},
        {field:'curationVersion',...sides(0,1)},{field:'curationId',...sides('11111111-1111-4111-8111-111111111111','22222222-2222-4222-8222-222222222222')},
        {field:'variationId',...sides(1234,5678)},{field:'gene',...sides('TPM1','GENE2')},
        {field:'diseaseConcept',...sides('MONDO:0005045','Dilated cardiomyopathy')},
    ]}];
    const before=JSON.stringify(input),view=pp2HcmDetails(freeze(input),{mainKind:'NO_CONCLUSION'});
    assert.equal(view.conflicts.length,7);assert.equal(view.sections.length,0);
    assert.deepEqual(view.conflicts.map(row=>row.field),['分类','疾病编号','审核记录版本','审核记录编号','ClinVar 变异编号','基因','疾病编号与名称']);
    assert.equal(view.conflicts[0].left.value,'致病');assert.equal(view.conflicts[0].right.value,'良性');
    assert.equal(view.conflicts[2].left.value,'0');assert.equal(view.conflicts[6].left.value,'MONDO:0005045');assert.equal(view.conflicts[6].right.value,'Dilated cardiomyopathy');
    assert.ok(view.conflicts.every(row=>row.left.label.includes('SCV002032337.1')&&row.right.label.includes('11111111-1111-4111-8111-111111111111')));
    assert.equal(JSON.stringify(input),before);assert.ok(view.summary.includes('尚不能采用'));
});
check('one-sided differences retain the actual side and never fill the missing value',()=>{
    const input=facts();input.otherPhenotypeReview.outcome='CONFLICT';input.otherPhenotypeReview.conflicts=[{differences:[
        {field:'diseaseId',left:{value:null,sourceRecordId:'SCV002032337.1'},right:{value:'MONDO:0005045'}},
        {field:'curationVersion',left:{value:0},right:{}},
    ]}];
    const view=pp2HcmDetails(input,{mainKind:'NO_CONCLUSION'});
    assert.equal(view.conflicts.length,2);assert.equal(view.conflicts[0].left.value,'');assert.equal(view.conflicts[0].right.value,'MONDO:0005045');
    assert.equal(view.conflicts[1].left.value,'0');assert.equal(view.conflicts[1].right.value,'');
});
check('source links come only from actual safe URLs and no record ID invents a paper link',()=>{
    const input=facts();input.otherPhenotypeReview.outcome='CONFLICT';
    const actual='https://www.ncbi.nlm.nih.gov/clinvar/variation/1234/';
    input.otherPhenotypeReview.conflicts=[{differences:[{field:'classification',left:{value:'Pathogenic',sourceRecordId:'SCV002032337.1',
        sourceIds:[actual,'javascript:alert(1)','file:///private/source.json','https://name:secret@example.org/source','INTERNAL_SOURCE_ID']},
        right:{value:'Benign',sourceRecordId:'11111111-1111-4111-8111-111111111111'}}]}];
    const row=pp2HcmDetails(input,{mainKind:'NO_CONCLUSION'}).conflicts[0];
    assert.deepEqual(row.left.sources,[{url:actual,title:'来源记录 SCV002032337.1'}]);assert.deepEqual(row.right.sources,[]);
    assert.ok(!JSON.stringify(row).includes('pubmed'));assert.ok(!JSON.stringify(row).includes('secret'));
});
check('unknown meanings and non-scalar or internal values are never interpreted as a conflict field',()=>{
    const input=facts();input.otherPhenotypeReview.outcome='CONFLICT';input.otherPhenotypeReview.conflicts=[{referenceId:'reference1033090',differences:[
        {field:'INTERNAL_SEMANTIC_CODE',left:{value:'PRIVATE_UNKNOWN_SECRET'},right:{value:'different'}},
        {field:'classification',left:{value:{private:'PRIVATE_JSON_SECRET'}},right:{value:'INTERNAL_ENUM_SECRET'}},
        {field:'gene',left:{value:'{"private":"PRIVATE_TEXT_SECRET"}'},right:{value:['PRIVATE_ARRAY_SECRET']}},
    ]}];
    const view=pp2HcmDetails(input,{mainKind:'NO_CONCLUSION'}),rendered=JSON.stringify(view);
    assert.equal(view.conflicts.length,0);assert.ok(view.sections.some(section=>section.rows.some(row=>row.value==='reference1033090')));
    for(const secret of ['PRIVATE_UNKNOWN_SECRET','PRIVATE_JSON_SECRET','PRIVATE_TEXT_SECRET','PRIVATE_ARRAY_SECRET','INTERNAL_ENUM_SECRET','INTERNAL_SEMANTIC_CODE'])assert.ok(!rendered.includes(secret));
});
check('reported diagnosis certainty is labelled as certainty without changing its saved meaning',()=>{
    const input=facts();input.otherPhenotypeReview.literatureAssessment={methodVersion:'pp2-literature-assessment-v1',papers:[{pmcid:'PMC123',cases:[{
        caseLabel:'1',facts:[{field:'reportedDiagnosisCertainty',subject:'PROBAND',value:'clinically suspected',citation:{unitId:'/article[1]/body[1]/p[1]',quote:'The diagnosis was clinically suspected.'}}],
    }]}]};
    const view=pp2HcmDetails(input,{mainKind:'NO_CONCLUSION'});
    assert.ok(view.papers[0].cases.some(item=>item.rows.some(row=>row.label==='文中疾病确定程度'&&row.value==='clinically suspected')));
    assert.ok(!JSON.stringify(view).includes('疾病判断依据'));
});
check('wrong-object source failure is distinct from missing literature',()=>{
    const input=facts();input.otherPhenotypeReview.outcome='INVALID';input.otherPhenotypeReview.pending=[];
    const view=pp2HcmDetails(input,{mainKind:'NO_CONCLUSION'});
    assert.ok(view.summary.includes('不一致'));assert.ok(view.missing.includes('对应关系'));assert.ok(view.comparisons[0].suggestion.includes('本次分析对象'));
});
check('workbench integration replaces unrelated RAS fields without altering the saved evidence',()=>{
    const input=facts();const saved={criterionId:'ACMG_AMP_2015:PP2',status:'NOT_EVALUATED',details:{pp2Facts:input}};
    const entry={savedCriterion:saved,evaluation:{criterionId:saved.criterionId,appliedStrength:'NONE',evaluationResult:{status:'NOT_EVALUATED',reasonCode:'PP2_OTHER_PHENOTYPE_REVIEW_INCOMPLETE'}}};
    const before=JSON.stringify(entry),view=buildEvidenceDetailView({id:saved.criterionId,code:'PP2'},freeze(entry));
    assert.ok(view.title.includes('心肌病'));assert.ok(view.requirement.includes('病例富集'));
    assert.ok(!view.fields.some(row=>row.label==='良性错义变异背景'));assert.ok(view.comparisons.some(row=>row.title==='其他疾病表型关联'));
    assert.equal(JSON.stringify(entry),before);assert.equal(view.adopted,false);
});
if(process.argv[2])check('actual saved real-reference module summary is projected read-only',()=>{
    const envelope=JSON.parse(fs.readFileSync(process.argv[2],'utf8')),result=envelope.result;
    assert.equal(envelope.liveVepExecuted,false);assert.equal(envelope.independentRefseqRnaClaimed,false);assert.equal(envelope.phenotypeClearGranted,false);
    assert.equal(result.status,'NOT_EVALUATED');assert.equal(result.evaluation.appliedStrength,'NONE');
    const before=JSON.stringify(result),view=pp2HcmDetails(freeze(result.publicSummary),{mainKind:'NO_CONCLUSION'});
    assert.ok(view.fields.some(row=>row.value==='ENST00000403994.9'));assert.ok(view.missing);assert.equal(JSON.stringify(result),before);
});
console.log(`PASS ${checks} HCM PP2 display groups; no scientific qualification or scoring executed`);
