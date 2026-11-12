// Read-only display checks; the saved sample is synthetic and grants no scientific qualification.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {Module}=require('node:module');
const {createHash}=require('node:crypto');
const {buildSync}=require('esbuild');
const root=path.resolve(__dirname,'..');
const bundle=buildSync({stdin:{contents:`export {buildEvidenceDetailView} from './src/pages/varvis/ChromosomeVisualization/utils/evidenceDetailView.js';
export {default as Details} from './src/pages/varvis/ChromosomeVisualization/components/EvidenceCoreDetails.jsx';`,resolveDir:root},
    bundle:true,platform:'node',format:'cjs',packages:'external',write:false,logLevel:'silent'});
const mod=new Module(path.join(root,'scripts/pp2-hcm-literature-bundle.cjs'));
mod.filename=mod.id;mod.paths=[path.join(root,'node_modules')];mod._compile(bundle.outputFiles[0].text,mod.filename);
const {buildEvidenceDetailView,Details}=mod.exports,React=require('react'),{renderToStaticMarkup}=require('react-dom/server');
assert.ok(process.argv[2],'Supply the actual saved projection; this check must not silently skip it.');
const review=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
assert.equal(review.literatureAssessment.papers[0].cases.length,2);
assert.ok(process.argv[3]&&process.argv[4],'Supply the actual 1.2 producer projection and its derived raw archive.');
const questionBytes=fs.readFileSync(process.argv[3]),questionReview=JSON.parse(questionBytes);
const questionArchiveBytes=fs.readFileSync(process.argv[4]),questionArchive=JSON.parse(questionArchiveBytes);
assert.equal(questionReview.literatureAssessment.readingMethodVersion,'1.2.0');
const card={id:'ACMG_AMP_2015:PP2',code:'PP2'};
const entry=value=>({savedCriterion:{criterionId:card.id,status:'NOT_EVALUATED',details:{pp2Facts:{
    schemaVersion:'pp2-general-facts-v1',method:'PP2_PUBLISHED_HCM_ENRICHMENT_WITH_PHENOTYPE_EXCLUSION_V1',methodVersion:'1.0.0',
    scope:'PROJECT_RESEARCH_PUBLISHED_HCM_ENRICHMENT',otherPhenotypeReview:value}}},
evaluation:{criterionId:card.id,appliedStrength:'NONE',evaluationResult:{status:'NOT_EVALUATED',reasonCode:'PP2_OTHER_PHENOTYPE_REVIEW_INCOMPLETE'}}});
const freeze=value=>{if(value&&typeof value==='object'){Object.values(value).forEach(freeze);Object.freeze(value);}return value;};
const clone=value=>JSON.parse(JSON.stringify(value));
let count=0;function check(name,fn){fn();count++;console.log('PASS '+name);}
const saved=freeze(entry(review)),before=JSON.stringify(saved);
const view=buildEvidenceDetailView(card,saved),paper=view.papers[0];
check('actual saved two-case material reaches the workbench without mutation or adoption',()=>{
    assert.equal(view.adopted,false);assert.equal(view.needs,true);assert.equal(view.papers.length,1);
    assert.equal(paper.cases.filter(item=>item.label).length,2);assert.equal(JSON.stringify(saved),before);
    assert.equal(view.fields.find(row=>row.label==='本次文献范围').value,'1 篇');
    assert.equal(view.fields.find(row=>row.label==='已完成关联核查').value,'0 篇');
});
check('subjects and individual cases never merge',()=>{
    const [one,two]=paper.cases.filter(item=>item.label);
    assert.ok(one.rows.some(row=>row.label==='母亲变异检测'&&row.value==='did not carry c.100A>G'));
    assert.ok(!one.rows.some(row=>row.value.includes('GENE2')));
    assert.ok(two.rows.some(row=>row.label==='父亲变异检测'&&row.value==='carried c.200C>T'));
    assert.ok(!two.rows.some(row=>row.value.includes('GENE1')));
});
check('each real unit has one containing quote; disjoint units stay separate',()=>{
    assert.equal(paper.sources.length,2);assert.ok(paper.sources.every(source=>source.quote.length>200));
    assert.notEqual(paper.sources[0].locator,paper.sources[1].locator);
});
check('actual unanswered clinical questions appear as a readable comparison',()=>{
    const row=view.comparisons.find(item=>item.title.startsWith('疾病关联核查'));
    for(const key of ['requirement','known','gap','conclusion','suggestion'])assert.ok(row[key]);
    assert.ok(row.gap.includes('疾病过程'));assert.ok(row.gap.includes('其他可能解释'));
    assert.ok(!JSON.stringify(view).includes('QUOTE_CHECKED_LITERAL_FACTS'));
});
check('unknown fields, opaque values and raw identity hashes do not leak into detail rows',()=>{
    const input=clone(review),item=input.literatureAssessment.papers[0].cases[0];
    item.facts.push({field:'INTERNAL_DUMP',value:'RAW_SECRET',citation:item.caseCitation});
    item.facts.push({field:'reportedPhenotype',value:{raw:'RAW_OBJECT_SECRET'},citation:item.caseCitation});
    const rendered=JSON.stringify(buildEvidenceDetailView(card,entry(input)));
    for(const secret of ['RAW_SECRET','RAW_OBJECT_SECRET',input.literatureAssessment.assessmentSha256])assert.ok(!rendered.includes(secret));
});
check('duplicate source labels do not merge different saved records',()=>{
    const input=clone(review);input.literatureAssessment.papers[0].cases[1].caseLabel='1';
    const items=buildEvidenceDetailView(card,entry(input)).papers[0].cases.filter(item=>item.label);
    assert.equal(items.length,2);assert.notEqual(items[0].id,items[1].id);
    assert.equal(items[0].label,'病例 1 · 记录 1');assert.equal(items[1].label,'病例 1 · 记录 2');
});
check('unresolved or omitted clinical subject never defaults to the proband',()=>{
    for(const subject of ['UNCLEAR','OTHER','UNKNOWN_ENUM',undefined]){
        const input=clone(review),item=input.literatureAssessment.papers[0].cases[0];
        item.facts.push({field:'reportedAge',subject,value:'40 years',citation:item.caseCitation});
        const rows=buildEvidenceDetailView(card,entry(input)).papers[0].cases.flatMap(c=>c.rows);
        assert.ok(rows.some(row=>row.label==='对象未明确／年龄'&&row.value==='40 years'));
        assert.ok(!rows.some(row=>row.label==='年龄'&&row.value==='40 years'));
    }
});
check('parent subject on a general clinical fact remains visible',()=>{
    const input=clone(review),item=input.literatureAssessment.papers[0].cases[0];
    item.facts.push({field:'reportedAge',subject:'MOTHER',value:'40 years',citation:item.caseCitation});
    assert.ok(buildEvidenceDetailView(card,entry(input)).papers[0].cases.some(c=>c.rows.some(row=>row.label==='母亲／年龄'&&row.value==='40 years')));
});
check('partial display counts appear only when actually truncated',()=>{
    assert.ok(!paper.cases.some(c=>c.rows.some(row=>row.label.includes('本卡'))));
    const input=clone(review);Object.assign(input.literatureAssessment.papers[0],{casesTruncated:true,casesInSource:3,displayedCases:2});
    assert.ok(buildEvidenceDetailView(card,entry(input)).papers[0].cases.some(c=>c.rows.some(row=>row.value==='2 / 3 条')));
});
check('unsupported assessment versions are not silently interpreted',()=>{
    const input=clone(review);input.literatureAssessment.methodVersion='future-unknown';
    assert.equal(buildEvidenceDetailView(card,entry(input)).papers.length,0);
});
check('rendered original sources have one article title and one full-text entry',()=>{
    const html=renderToStaticMarkup(React.createElement(Details,{card,entry:saved}));
    assert.equal((html.match(/Test source/g)||[]).length,1);
    assert.equal((html.match(/查看完整原文/g)||[]).length,1);
    assert.ok(html.includes('资料信息提取'));assert.ok(html.includes('Case 1 was examined'));
    for(const word of ['RAW_WORK_SCOPE_READ','NOT_ASSESSED','readingLedgerRawSha256','放大图片','查看原图'])assert.ok(!html.includes(word));
});
const questionSaved=freeze(entry(questionReview)),questionBefore=JSON.stringify(questionSaved);
const questionView=buildEvidenceDetailView(card,questionSaved),questionPaper=questionView.papers[0];
check('actual 1.2 producer keeps cohort and function records distinct from cases and qualification',()=>{
    assert.equal(questionReview.literatureAssessment.assessedPapers,0);assert.equal(questionReview.literatureAssessment.wholePublicationRead,false);
    assert.equal(questionReview.literatureAssessment.papers[0].casesInSource,0);
    assert.equal(questionView.adopted,false);assert.equal(questionView.needs,true);
    const records=questionPaper.cases.filter(item=>item.label);
    assert.equal(records.length,2);assert.ok(records.every(item=>item.label.startsWith('资料记录')));
    assert.ok(records[0].rows.some(row=>row.label==='资料类型线索'&&row.value==='队列研究'));
    assert.ok(records[1].rows.some(row=>row.label==='资料类型线索'&&row.value==='功能研究'));
    assert.ok(records.every(item=>item.rows.some(row=>row.label==='变异对应核查'&&row.value==='尚未核实')));
    assert.ok(!records.some(item=>item.label.startsWith('病例')));assert.equal(JSON.stringify(questionSaved),questionBefore);
});
check('actual 1.2 references resolve to retained raw bytes while the display stays immutable',()=>{
    const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
    for(const record of questionReview.literatureAssessment.papers[0].relationshipRecords)for(const citation of [record.anchor,...record.facts.map(fact=>fact.citation)]){
        const original=questionArchive.originals[citation.sourceRawSha256];assert.ok(original);
        const raw=Buffer.from(original.base64,'base64');assert.equal(sha(raw),citation.sourceRawSha256);assert.equal(raw.length,original.bytes);
        assert.ok(raw.toString('utf8').includes(citation.quote),'This actual synthetic source contains its saved literal quote.');
    }
    assert.deepEqual(fs.readFileSync(process.argv[3]),questionBytes);assert.deepEqual(fs.readFileSync(process.argv[4]),questionArchiveBytes);
});
check('question facts separate author statements, study observations and alternative explanations',()=>{
    const input=clone(questionReview),paper=input.literatureAssessment.papers[0],citation=paper.relationshipRecords[0].anchor;
    const definitions=[['reportedClinicalDefinition','DISEASE_CONTEXT','疾病定义'],['reportedDiseaseCourse','DISEASE_CONTEXT','疾病过程'],
        ['reportedClinicalAssessmentBasis','DISEASE_CONTEXT','临床判断依据'],['reportedSubject','SOURCE_SUBJECT','资料所述对象'],
        ['reportedVariantDiseaseStatement','AUTHOR_ASSERTION','作者所述关联'],['reportedRelationshipBasis','REPORTED_BASIS','报告的关联依据'],
        ['reportedStudyDesign','STUDY_OBSERVATION','研究设计'],['reportedObservationOrResult','STUDY_OBSERVATION','观察或实验结果'],
        ['reportedComparatorOrControl','STUDY_OBSERVATION','对照资料'],['reportedAlternativeExplanation','ALTERNATIVES_AND_REFERENCES','其他解释'],
        ['reportedLimitationsOrContradiction','ALTERNATIVES_AND_REFERENCES','局限或相反结果'],['reportedSupportingReference','ALTERNATIVES_AND_REFERENCES','支持性引用']];
    // Engineering display vector only; these values never claim extraction or qualification of this source.
    paper.relationshipRecords=[{materialKindProposal:'AUTHOR_DISCUSSION',targetMatch:'UNRESOLVED',anchor:citation,
        facts:definitions.map(([field,group])=>({field,group,value:'Literal engineering statement',citation}))}];
    const record=buildEvidenceDetailView(card,entry(input)).papers[0].cases.find(item=>item.label);
    for(const [,,label]of definitions)assert.ok(record.rows.some(row=>row.label===label));
    assert.ok(!JSON.stringify(record).includes('事实已证明'));assert.ok(!JSON.stringify(record).includes('AUTHOR_ASSERTION'));
    paper.relationshipRecords[0].facts=[{field:'reportedSubject',group:'SOURCE_SUBJECT',value:'PATIENT_1',
        citation:{...citation,quote:'PATIENT_1 was included in the reported study.'}}];
    assert.ok(buildEvidenceDetailView(card,entry(input)).papers[0].cases.some(item=>
        item.rows.some(row=>row.label==='资料所述对象'&&row.value==='PATIENT_1')));
});
check('target correspondence uses only an actual saved program state and never guesses from source text',()=>{
    for(const [state,label]of [['EXACT','已对应本次精确变异'],['NON_TARGET','已核实为其他变异'],['UNRESOLVED','尚未核实'],['UNKNOWN_FUTURE_STATE',null],['constructor',null],[{},null],[undefined,null]]){
        const input=clone(questionReview),paper=input.literatureAssessment.papers[0];paper.relationshipRecords=paper.relationshipRecords.slice(0,1);
        paper.relationshipRecords[0].targetMatch=state;
        const rows=buildEvidenceDetailView(card,entry(input)).papers[0].cases.find(item=>item.label).rows;
        assert.equal(rows.find(row=>row.label==='变异对应核查')?.value,label||undefined);
    }
});
check('partial question coverage retains extracted material and shows only real progress and truncation',()=>{
    const input=clone(questionReview),paper=input.literatureAssessment.papers[0];
    Object.assign(paper.questionCoverage,{status:'INCOMPLETE',expectedPlans:4,checkedPlans:1});paper.unreviewedVisualCaptionCount=2;
    Object.assign(paper,{relationshipRecordsTruncated:true,relationshipRecordsInSource:5,displayedRelationshipRecords:2,
        relationshipFactsTruncated:true,relationshipFactsInSource:30,displayedRelationshipFacts:16});
    Object.assign(paper.relationshipRecords[0],{factsTruncated:true,factsInSource:8,displayedFacts:1});
    paper.gaps=[{reasonCode:'PP2_RELATIONSHIP_QUESTION_SCOPE_INCOMPLETE'}];
    const view=buildEvidenceDetailView(card,entry(input)),rows=view.papers[0].cases.flatMap(item=>item.rows);
    for(const value of ['1 / 4 项','2 / 5 条','16 / 30 项','1 / 8 项'])assert.ok(rows.some(row=>row.value===value));
    assert.ok(rows.some(row=>row.label==='尚未核查图示'&&row.value==='2 项'));assert.equal(view.papers[0].cases.filter(item=>item.label).length,2);
    assert.ok(view.comparisons.find(item=>item.title.startsWith('疾病关联核查')).gap.includes('尚未提取完整'));
    paper.questionCoverage.expectedPlans=null;paper.questionCoverage.checkedPlans=0;
    assert.ok(!buildEvidenceDetailView(card,entry(input)).papers[0].cases.flatMap(item=>item.rows).some(row=>row.label==='问题提取进度'));
    assert.ok(!questionPaper.cases.some(item=>item.rows.some(row=>row.label.includes('本卡资料')||row.label==='本记录字段范围')));
});
check('actual processing gaps are readable, deduplicated and never presented as missing patient data',()=>{
    const meanings=[['PP2_READING_MODEL_DISABLED_CACHE_MISSING','模型未启用'],['PP2_READING_NEW_WORK_LIMIT','额度已用尽'],
        ['PP2_READING_TIME_BUDGET','时间额度已用尽'],['PP2_READING_ARCHIVE_TECHNICAL_LIMIT','归档容量限制'],
        ['PP2_READING_EXTRACTION_INCOMPLETE','尚未提取完整'],['PP2_READING_RELATIONSHIP_EMPTY_EXTRACTION_IS_NOT_ABSENCE','提取记录为空'],
        ['PP2_READING_RELATIONSHIP_RECORD_WITHOUT_EXTRACTED_FACTS','可核对的字段'],['PP2_READING_ATTACHMENT_NOT_FULLY_READ','附件尚未完整读取'],
        ['PP2_READING_ATTACHMENT_COVERAGE_INCOMPLETE','附件的读取范围'],['PP2_READING_QUESTION_METHOD_PIN_MISMATCH','来源校验未通过'],
        ['PP2_READING_LEDGER_PIN_INVALID','完整性校验未通过'],['PP2_READING_ORIGINAL_PIN_INVALID','原文资料与保存摘要不一致'],
        ['PP2_READING_ORIGINALS_UNAVAILABLE','无法读取'],['PP2_OTHER_PHENOTYPE_RELATIONSHIP_NOT_ESTABLISHED','疾病关联依据尚未完成核查'],
        ['UNRECOGNIZED_PRIVATE_REASON','尚有未完成的资料核查'],['constructor','尚有未完成的资料核查']];
    for(const [reasonCode,meaning]of meanings){
        const input=clone(questionReview);input.literatureAssessment.papers[0].gaps=[{reasonCode},{reasonCode}];
        const row=buildEvidenceDetailView(card,entry(input)).comparisons.find(item=>item.title.startsWith('疾病关联核查'));
        assert.ok(row.gap.includes(meaning));assert.equal(row.gap.indexOf(meaning),row.gap.lastIndexOf(meaning));
        assert.ok(!row.gap.includes(reasonCode));assert.ok(!row.gap.includes('患者'));assert.ok(!row.known.includes('病例'));
    }
});
check('unknown question versions, groups, field names and raw objects never borrow supported semantics',()=>{
    const input=clone(questionReview),record=input.literatureAssessment.papers[0].relationshipRecords[0];
    record.facts.push({field:'PRIVATE_FIELD',group:'SOURCE_SUBJECT',value:'PRIVATE_FIELD_SECRET',citation:record.anchor},
        {field:'reportedSubject',group:'UNKNOWN_GROUP',value:'PRIVATE_GROUP_SECRET',citation:record.anchor},
        {field:'reportedSubject',group:'SOURCE_SUBJECT',value:{raw:'PRIVATE_JSON_SECRET'},citation:record.anchor});
    const rendered=JSON.stringify(buildEvidenceDetailView(card,entry(input)));
    for(const secret of ['PRIVATE_FIELD_SECRET','PRIVATE_GROUP_SECRET','PRIVATE_JSON_SECRET'])assert.ok(!rendered.includes(secret));
    record.materialKindProposal='constructor';
    assert.ok(!buildEvidenceDetailView(card,entry(input)).papers[0].cases.find(item=>item.label).rows.some(row=>row.label==='资料类型线索'));
    input.literatureAssessment.readingMethodVersion='99.0.0';
    assert.equal(buildEvidenceDetailView(card,entry(input)).papers[0].cases.filter(item=>item.label?.startsWith('资料记录')).length,0);
});
check('actual question records reuse original quotations without duplicate article controls or raw enums',()=>{
    assert.equal(questionPaper.sources.length,1,'Containing/same unit quotes collapse, not records.');
    const html=renderToStaticMarkup(React.createElement(Details,{card,entry:questionSaved}));
    assert.equal((html.match(/Test source/g)||[]).length,1);assert.equal((html.match(/查看完整原文/g)||[]).length,1);
    assert.ok(html.includes('资料记录 1'));assert.ok(html.includes('资料记录 2'));assert.ok(html.includes('队列研究'));assert.ok(html.includes('功能研究'));
    for(const word of ['COHORT','FUNCTIONAL_STUDY','RAW_QUESTION_SCOPE_READ','NOT_GRANTED','STUDY_OBSERVATION','readingLedgerRawSha256','本次核查已证明'])assert.ok(!html.includes(word));
});
check('legacy dictionary keys cannot expose inherited objects or functions while gene text is preserved',()=>{
    const input=clone(review),record=input.literatureAssessment.papers[0].cases[0],citation=record.caseCitation;
    record.facts.push({field:'__proto__',value:'PRIVATE_PROTOTYPE_FIELD',citation},{field:'toString',value:'PRIVATE_FUNCTION_FIELD',citation},
        {field:'reportedAge',subject:'toString',value:'40 years',citation},{field:'geneSymbol',subject:'__proto__',value:'TPM1',citation});
    input.outcome='__proto__';input.requiredScope=[{scope:'__proto__',status:'toString'},
        {scope:'EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS',status:'constructor'}];
    input.qualifiedEvidence=[{originalClassification:'toString'}];
    input.conflicts=[{reasonCode:'toString',differences:[{field:'constructor',left:{value:'PRIVATE_UNKNOWN_LEFT'},right:{value:'PRIVATE_UNKNOWN_RIGHT'}}]}];
    const result=buildEvidenceDetailView(card,entry(input)),rows=result.papers[0].cases.flatMap(item=>item.rows),rendered=JSON.stringify(result);
    assert.ok(rows.some(row=>row.label==='对象未明确／年龄'&&row.value==='40 years'));
    assert.ok(rows.some(row=>row.label==='基因'&&row.value==='TPM1'));
    for(const value of ['PRIVATE_PROTOTYPE_FIELD','PRIVATE_FUNCTION_FIELD','PRIVATE_UNKNOWN_LEFT','PRIVATE_UNKNOWN_RIGHT','function toString','[object Object]'])assert.ok(!rendered.includes(value));
    assert.doesNotThrow(()=>renderToStaticMarkup(React.createElement(Details,{card,entry:entry(input)})));
});
console.log(`PASS ${count} saved PP2 literature display groups; no clinical qualification executed`);
