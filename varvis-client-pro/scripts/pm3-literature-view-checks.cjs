const assert = require('node:assert/strict');
const { buildSync } = require('esbuild');
const path = require('node:path');
const project = path.resolve(__dirname, '..');
const output = buildSync({ stdin: { contents: `
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import Panel,{Pm3ReadingProgress,Pm3PaperFacts,Pm3Assessment,Pm3PartnerEvidence,Pm3SavedFacts,validatePm3Task} from './src/pages/varvis/ChromosomeVisualization/components/Pm3LiteraturePanel.jsx';
export const validate=validatePm3Task;
export const render=(paper)=>renderToStaticMarkup(<Pm3PaperFacts paper={paper}/>);
export const assess=(value)=>renderToStaticMarkup(<Pm3Assessment value={value}/>);
export const empty=()=>renderToStaticMarkup(<Panel analysisId="synthetic" topN={5}/>);
export const partner=(p)=>renderToStaticMarkup(<Pm3PartnerEvidence {...p}/>);
export const saved=(value)=>renderToStaticMarkup(<Pm3SavedFacts value={value}/>);
export const progress=(value)=>renderToStaticMarkup(<Pm3ReadingProgress value={value}/>);
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', write: false,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' }).outputFiles[0].text;
const compiled = { exports: {} };
new Function('require', 'module', 'exports', output)(require, compiled, compiled.exports);
const api = compiled.exports;
assert.ok(api.render({pmcid:'PMC123',cacheReuse:{extractionChunks:[0,43],reviewChunks:[43]}})
    .includes('本轮复用提取缓存 2 块、复核缓存 1 块；来源重新核验'));
const dependentGap=api.saved({schemaVersion:'pm3-selected-facts-v1',caseFacts:[{pmcid:'PMC123',caseLabel:'1',
    qualificationBlocker:{reasonCode:'PM3_CASE_DISEASE_CONTEXT_UNRESOLVED',causeReasonCode:'PM3_CASE_DIAGNOSIS_NOT_MATCHED',
        stage:'DISEASE_CONTEXT',dependentChecks:['POLICY','RARITY_ADOPTION']},
    diseaseContextCheck:{reportedDiagnoses:['Explicit diagnosis<script>']}}]});
assert.ok(dependentGap.includes('已取得病例诊断'));
assert.ok(dependentGap.includes('Explicit diagnosis&lt;script&gt;'));
assert.ok(!dependentGap.includes('<script>'));
const reading={schemaVersion:'pm3-reading-loop-v1',state:'INCOMPLETE',roundCount:2,
    feedback:{stopReason:'PM3_READING_TIME_LIMIT',papers:[{pmcid:'PMC123',completedReviewChunks:[1],pendingRelevantChunks:[2,3],blockedReviewChunks:[],needs:[{reasonCode:'PM3_TARGET_IDENTITY_UNRESOLVED'}]}]}};
for(const markup of [api.progress(reading),api.saved({schemaVersion:'pm3-selected-facts-v1',paperCoverage:[{pmcid:'PMC123',readingLoop:reading}]})]) {
    for(const text of ['资料检查尚未完成','已完成 2 轮','相关待读 2 块','本次处理时间已用尽'])assert.ok(markup.includes(text),text);
}
assert.ok(api.progress({...reading,feedback:{papers:[{pmcid:'PMC123',factAvailability:'EXTRACTION_INCOMPLETE'}]}}).includes('事实提取未完成'));
const sourceFact = { field: 'targetVariant', sourceValue: 'c.2C>G', status: 'CASE_OWNED_SEMANTICS_PENDING',
    citation: { quote: 'c.2C>G', unitId: 'exact-source-unit', originalName: 'official.zip',
        archiveMemberName: 'Cases.xlsx<script>', sheet: 'Cases', cell: 'B3' },
    supportingCitations: [{ quote: 'Explicit source context', unitId: 'context-unit' }] };
const anonymous = { caseLabel: 'SRC-abcdef123456-P2-L4', identityKind: 'ANONYMOUS_SOURCE_ROW_NOT_PATIENT_ID',
    pmcid: 'PMC123', facts: [sourceFact], reportedFacts: [sourceFact],
    cohortContext: { schemaVersion: 'pm3-cohort-context-v1', status: 'UNRESOLVED' },
    supplementContext: { reasonCode: 'PM3_SUPPLEMENT_SOURCE_CONTEXT_BOUND', bindings: [{ citations: [sourceFact.citation] }], facts: [] } };
for (const markup of [api.render({ pmcid: 'PMC123', observations: { cases: [anonymous] } }),
    api.saved({ schemaVersion: 'pm3-selected-facts-v1', caseFacts: [anonymous] })]) {
    for (const value of ['未公开患者编号', '不能据此认定独立病例', 'official.zip', 'Cases.xlsx&lt;script&gt;',
        '工作表 Cases', '单元格 B3', 'exact-source-unit', 'Explicit source context', 'context-unit']) assert.ok(markup.includes(value), value);
    assert.ok(markup.includes('已核实正文、完整基因型附件'));
    assert.ok(!markup.includes('尚未建立队列背景到该病例的完整关联'));
    assert.ok(!markup.includes('<script>'));
}
const identityIssue={role:'target',reasonCode:'PM3_PROTEIN_ONLY_SOURCE_CODING_LINK_MISSING',candidateSearch:{
    schemaVersion:'pm3-protein-candidates-v1',reportedProtein:'p.T1I<script>',scopeComplete:false,
    searchScope:'LOCAL_MANE_GENE_TRANSCRIPTS_ONLY',issues:['PM3_PROTEIN_MULTINUCLEOTIDE_ALTERNATIVES_EXIST'],
    candidates:[{transcript:'NM_000001.1',codingChanges:['c.2C>T'],codon:'ACC',alternateCodon:'ATC',substitutionType:'SNV'},
        {transcript:'NM_000001.1',codingChanges:['c.2C>T','c.3C>A'],codon:'ACC',alternateCodon:'ATA',substitutionType:'MULTINUCLEOTIDE'}]}};
for(const markup of [api.render({pmcid:'PMC123',observations:{cases:[{caseLabel:'1',facts:[],resolvedObservations:[{issues:[identityIssue]}]}]}}),
    api.saved({schemaVersion:'pm3-selected-facts-v1',caseFacts:[{pmcid:'PMC123',caseLabel:'1',identityIssues:[identityIssue]}]})]) {
    assert.ok(markup.includes('尚未确认是论文中的变异'));
    assert.ok(markup.includes('不能把部分结果当作全部候选'));
    assert.ok(markup.includes('c.2C&gt;T + c.3C&gt;A'));
    assert.ok(markup.includes('尚未建立完整基因组身份'));
    assert.ok(markup.includes('p.T1I&lt;script&gt;'));assert.ok(!markup.includes('<script>'));
}
const headerFinding={field:'targetVariant',reasonCode:'PM3_TABLE_HEADER_MEANING_AMBIGUOUS',
    columnHeading:'Mutation 2 AV',headingEvidence:{status:'AMBIGUOUS',sourceHeadings:[{
        unitId:'/table/thead/th[3]',quote:'Mutation 2AV<script>',lines:['Mutation 2','AV<script>']}]}};
const headerCase={caseLabel:'27',pmcid:'PMC123',facts:[],unverifiedFindings:[headerFinding],
    semantics:{unverifiedFindings:[headerFinding]},tableLayoutIssues:[{tableId:'table-b',reasonCode:'PM3_TABLE_HEADER_LAYOUT_UNRESOLVED'}]};
for(const markup of [api.render({pmcid:'PMC123',observations:{cases:[headerCase]}}),
    api.saved({schemaVersion:'pm3-selected-facts-v1',caseFacts:[headerCase]})]) {
    assert.ok(markup.includes('这不表示论文没有记载变异'));
    assert.ok(markup.includes('Mutation 2 / AV&lt;script&gt;'));
    assert.ok(markup.includes('/table/thead/th[3]'));
    assert.ok(markup.includes('table-b'));
    assert.ok(!markup.includes('<script>'));
}
const pathFailure=api.render({pmcid:'PMC123',chunkCount:1,completedReviewChunks:[],issues:['DENOVO_LOCAL_PATH_TOO_LONG']});
assert.ok(pathFailure.includes('本地工作目录路径过长'));
assert.ok(pathFailure.includes('不代表论文缺少资料'));
const partnerHtml=api.partner({sources:{assertions:[{vcv:'VCV000000123.1',scv:'SCV123.1',sourceClassification:'Pathogenic',
    comments:[{text:'<script>source quote</script>',xpath:'/Classification/Comment'}]}]},
    qualification:{classification:'UNKNOWN',issues:['PM3_PARTNER_COMPARISON_DEPENDENCY_UNRESOLVED']}});
assert.ok(partnerHtml.includes('尚未排除依赖当前目标变异'));
assert.ok(partnerHtml.includes('SCV123.1'));assert.ok(partnerHtml.includes('/Classification/Comment'));
assert.ok(partnerHtml.includes('&lt;script&gt;'));assert.ok(!partnerHtml.includes('<script>'));
const mixedClassHtml=api.partner({sources:{assertions:[]},qualification:{classification:'LIKELY_PATHOGENIC',
    independentClassification:'VERIFIED',classificationSelection:'P_LP_CONSERVATIVE_LOWER_WEIGHT'}});
assert.ok(mixedClassHtml.includes('原分类分别保留'));
assert.ok(mixedClassHtml.includes('较低权重'));
const dependencyHtml=api.partner({sources:{assertions:[{scv:'SCV123.1'}]},qualification:{assertionAssessments:[{
    scv:'SCV123.1',criterionDependencies:[{sourceDeclaredCriterion:'PM3_Strong',status:'COMPARISON_DEPENDENCY_UNRESOLVED',
        sourceText:'<script>comparison</script>',sourceLocator:'/Classification[1]/Comment[1]',
        comparisonNames:[{reportedName:'R10W',sourceLabel:'P'}],issues:['PM3_PARTNER_COMPARATOR_IDENTITIES_AND_DEPENDENCIES_REQUIRED']}]}]}});
assert.ok(dependencyHtml.includes('R10W'));assert.ok(dependencyHtml.includes('尚未核实为精确变异'));
assert.ok(dependencyHtml.includes('名称清单本身不能证明独立'));assert.ok(dependencyHtml.includes('/Classification[1]/Comment[1]'));
assert.ok(dependencyHtml.includes('&lt;script&gt;'));assert.ok(!dependencyHtml.includes('<script>'));
const resolvedDependencyHtml=api.partner({sources:{assertions:[{scv:'SCV123.1'}]},qualification:{assertionAssessments:[{
    scv:'SCV123.1',criterionDependencies:[{sourceDeclaredCriterion:'PM3_Strong',status:'EXACT_COMPARISON_DEPENDENCIES_TARGET_INDEPENDENT',
        comparisonNames:[{reportedName:'NM_000001.1:c.20C>T',sourceLabel:'P',identityStatus:'VERIFIED',classificationIndependence:'VERIFIED',
            resolution:{classification:'PATHOGENIC',qualifiedScvs:['SCV456.1']}}],issues:[]}]}],
    dependencyGraph:{nodes:[{identity:{geneSymbol:'TEST',transcript:'NM_000001.1'},allele:{assembly:'GRCh38',contig:'1',position:20,reference:'C',alternate:'T'},
        sources:{assertions:[{scv:'SCV456.1',comments:[{text:'actual source <script>',xpath:'/node/Comment'}]}]},qualification:{classification:'PATHOGENIC',independentClassification:'VERIFIED'}}]}}});
assert.ok(resolvedDependencyHtml.includes('精确身份与独立分类已核实'));
assert.ok(resolvedDependencyHtml.includes('SCV456.1'));assert.ok(resolvedDependencyHtml.includes('/node/Comment'));
assert.ok(!resolvedDependencyHtml.includes('这些名称尚未核实'));
assert.ok(resolvedDependencyHtml.includes('&lt;script&gt;'));assert.ok(!resolvedDependencyHtml.includes('<script>'));
const task = { schemaVersion: 'pm3-public-literature-task-v1', taskId: 'a'.repeat(64), status: 'READY',
    scientificExecution: false, modelCalls: null, result: { schemaVersion: 'pm3-paper-process-v1',
        scientificExecution: false, businessPublishable: false, modelCalls: 0 } };
assert.equal(api.validate(task), task);
for (const change of [{ schemaVersion: 'denovo-public-literature-task-v1' }, { scientificExecution: true },
    { result: { ...task.result, businessPublishable: true } }, { result: { ...task.result, modelCalls: -1 } }])
    assert.throws(() => api.validate({ ...task, ...change }));
const html = api.render({ pmcid: 'PMC123', chunkCount: 2, completedReviewChunks: [0],
    observations: { cases: [{ caseLabel: '1', facts: [{ field: 'partnerVariant', sourceValue: 'c.2C>G',
        citation: { quote: '<script>not executed</script>', unitId: 'table:1:R2C3' }, status: 'CASE_ATTRIBUTION_UNRESOLVED' }],
        resolvedObservations: [{ status: 'IDENTITY_INCOMPLETE', partnerQualification: { classification: 'PATHOGENIC',
            independentClassification: 'UNKNOWN', issues: ['PM3_PARTNER_FINAL_CRITERIA_MISSING'] } }] }] } });
assert.ok(html.includes('table:1:R2C3'));
assert.ok(html.includes('PM3_PARTNER_FINAL_CRITERIA_MISSING'));
assert.ok(html.includes('PATHOGENIC'));
assert.ok(html.includes('&lt;script&gt;')); assert.ok(!html.includes('<script>'));
assert.match(api.empty(), /准备 PM3 文献任务/);
const pending = api.render({ pmcid: 'PMC123', chunkCount: 1, completedReviewChunks: [],
    observations: { cases: [], unreviewedExtraction: { cases: [{ caseLabel: '2', facts: [{ field: 'targetVariant',
        sourceValue: 'c.3A>G', citation: { quote: 'c.3A>G', unitId: 'pending-row' }, status: 'MODEL_SOURCE_REVIEW_PENDING' }] }] } } });
assert.ok(pending.includes('待模型复核，未进入判定'));
assert.ok(pending.includes('pending-row'));
assert.ok(!pending.includes('没有取得可展示'));
const rarity = api.render({ pmcid: 'PMC123', observations: { cases: [{ caseLabel: '1', facts: [], resolvedObservations: [{
    rarityQualification: { target: { qualification: 'UNKNOWN', reasonCode: 'PM3_POPULATION_AFFECTED_AR_CONTEXT_REQUIRED' },
        partner: { qualification: 'NOT_APPLICABLE', reasonCode: 'PM3_HOMOZYGOUS_NO_SECOND_ALLELE_REQUIRED' } },
}] }] } });
assert.ok(rarity.includes('尚未核实该病例患病及隐性疾病背景'));
assert.ok(rarity.includes('本观察不需要第二个变异'));
assert.ok(rarity.includes('查询不到不能直接认定罕见'));
const populationTask = { ...task, scientificExecution: true, pm3ScientificExecution: false,
    result: { ...task.result, scientificExecution: true, pm3ScientificExecution: false,
        populationQualification: { schemaVersion: 'pm3-literature-rarity-supply-v1', pm2EvaluatorCalls: 2,
            pm3EvaluatorCalls: 0, providerCalls: 2, allSitesCalls: 0 } } };
assert.equal(api.validate(populationTask), populationTask);
assert.throws(() => api.validate({ ...populationTask, pm3ScientificExecution: true }));
assert.throws(() => api.validate({ ...populationTask, scientificExecution: false }));
const origin = api.render({ pmcid: 'PMC123', observations: { cases: [{ caseLabel: '1', facts: [],
    primarySourceQualification: { qualification: 'VERIFIED' },
    originAssertions: [{ citations: [{ quote: 'Patients were recruited.', unitId: 'methods-p1' }] }],
}] } });
assert.ok(origin.includes('病例之间是否独立、是否重复报道仍需另行核验'));
assert.ok(origin.includes('methods-p1'));
const assessment = { schemaVersion: 'pm3-literature-assessment-v1', businessPublishable: false,
    pm3FinalBusinessExecution: false, use: 'GENERAL_POLICY_CANDIDATE_NOT_ADOPTED',
    policyApplicability: 'SPECIFICATION_SELECTION_PENDING', pm3CalculatorCalls: 3,
    diseaseAssessments: [{ diseaseId: 'MONDO:0009861', calculation: { status: 'MET', strength: 'SUPPORTING',
        quarterPoints: 2, issues: [], contributions: [{ observationId: 'one', qualificationStatus: 'COUNTED',
            awardedQuarterPoints: 2, reason: 'PM3_OBSERVATION_COUNTED', requirements: [], sourceReferences: ['PMC123#table-cell'] }] },
        withheldOccurrences: [{ pmcid: 'PMC456', caseLabel: '2', reasonCode: 'PM3_CROSS_PAPER_OVERLAP_NOT_RECONCILED' }] }] };
const calculatedTask = { ...populationTask, pm3ScientificExecution: true, result: { ...populationTask.result,
    pm3ScientificExecution: true, pm3Assessment: assessment } };
assert.equal(api.validate(calculatedTask), calculatedTask);
const currentTask = { ...calculatedTask, result: { ...calculatedTask.result,
    pm3Assessment: { ...assessment, schemaVersion: 'pm3-literature-assessment-v2' } } };
assert.equal(api.validate(currentTask), currentTask);
assert.throws(() => api.validate({ ...currentTask, result: { ...currentTask.result,
    pm3Assessment: { ...assessment, schemaVersion: 'pm3-literature-assessment-v999' } } }));
for (const producer of ['SOURCE_NATIVE_LITERAL_TABLE', 'SOURCE_NATIVE_CLINICAL_APPENDIX']) {
    const fact = { ...sourceFact, producer };
    for (const markup of [api.render({ pmcid: 'PMC123', observations: { cases: [{ caseLabel: '1', facts: [fact] }] } }),
        api.saved({ schemaVersion: 'pm3-selected-facts-v1', caseFacts: [{ caseLabel: '1', pmcid: 'PMC123', reportedFacts: [fact] }] })]) {
        assert.ok(markup.includes('程序直接核对原文'));
        assert.ok(markup.includes('不代表完成模型复核'));
    }
}
const clinicalFact = { ...sourceFact, producer: 'SOURCE_NATIVE_CLINICAL_APPENDIX',
    ownership: { method: 'SOURCE_CLINICAL_APPENDIX_COHORT_ROW_JOIN' } };
const clinicalCase = { caseLabel: '1', pmcid: 'PMC123', facts: [clinicalFact], reportedFacts: [clinicalFact],
    cohortContext: { schemaVersion: 'pm3-cohort-context-v1', status: 'UNRESOLVED' } };
for (const markup of [api.render({ pmcid: 'PMC123', observations: { cases: [clinicalCase] } }),
    api.saved({ schemaVersion: 'pm3-selected-facts-v1', caseFacts: [clinicalCase] })]) {
    assert.ok(markup.includes('核对临床附件与该病例的关联'));
    assert.ok(!markup.includes('尚未建立队列背景到该病例的完整关联'));
}
assert.throws(() => api.validate({ ...calculatedTask, result: { ...calculatedTask.result,
    pm3Assessment: { ...assessment, businessPublishable: true } } }));
const calculationView = api.assess(assessment);
assert.ok(calculationView.includes('未写入正式 ACMG 结果'));
assert.ok(calculationView.includes('计入分值：0.5'));
assert.ok(calculationView.includes('跨论文是否重复尚未核实'));
assert.ok(calculationView.includes('PMC123#table-cell'));
const unresolvedColumn=api.render({pmcid:'PMC123',observations:{cases:[{caseLabel:'148',facts:[],
    semantics:{unverifiedFindings:[{field:'targetVariant',reasonCode:'PM3_VARIANT_COLUMN_MEANING_UNRESOLVED',columnHeading:'Mutation 2AV'}]},
    resolvedObservations:[{issues:[{role:'partner',reasonCode:'PM3_PROTEIN_ONLY_GENOMIC_IDENTITY_NOT_ESTABLISHED'}]}]}]}});
assert.ok(unresolvedColumn.includes('Mutation 2AV'));
assert.ok(unresolvedColumn.includes('蛋白'));
const aggregate=api.render({pmcid:'PMC123',chunkCount:9,completedReviewChunks:[2],observations:{cases:[],
    materialAssessments:[{materialKind:'AGGREGATE_OR_METHODS',citation:{quote:'<b>Mutation frequencies</b>',unitId:'aggregate-table'}}]}});
assert.ok(aggregate.includes('汇总或方法资料'));assert.ok(aggregate.includes('aggregate-table'));
assert.ok(aggregate.includes('&lt;b&gt;Mutation frequencies'));assert.ok(!aggregate.includes('<b>Mutation frequencies'));
const native=api.render({pmcid:'PMC123',observations:{cases:[{caseLabel:'148',facts:[{field:'geneSymbol',sourceValue:'PAH',producer:'SOURCE_NATIVE_TABLE_CONTEXT',citation:{quote:'PAH genotypes',unitId:'caption'}}],semantics:{issues:['PM3_VARIANT_REPRESENTATIONS_UNRESOLVED']}}]}});
assert.ok(native.includes('直接读取该病例所在表格'));assert.ok(native.includes('同一变异'));
console.log('PM3 view: task boundary rejection, source locator, qualification missingness, HTML escaping and initial render PASS');
const cohort=api.render({pmcid:'PMC123',observations:{cases:[{caseLabel:'148',facts:[],
    cohortContext:{schemaVersion:'pm3-cohort-context-v1',status:'SOURCE_COHORT_BOUND',bindings:[{diagnosis:'disease X',
        citations:[{quote:'Patients diagnosed with disease X',unitId:'enrollment'},{quote:'Table 1 lists all patients',unitId:'table-link'},{quote:'148',unitId:'case-row'}]}],
        relatedStatements:[{citation:{quote:'<script>method</script>',unitId:'method-location'}}]}}]}});
assert.ok(cohort.includes('疾病目录匹配'));assert.ok(cohort.includes('table-link'));assert.ok(cohort.includes('case-row'));
assert.ok(cohort.includes('不能直接当作本病例事实'));assert.ok(cohort.includes('&lt;script&gt;'));assert.ok(!cohort.includes('<script>'));

const singletonFact = { ...sourceFact, producer: 'SOURCE_NATIVE_SINGLE_PROBAND',
    ownership: { method: 'EXPLICIT_SINGLE_PROBAND_RESULTS' } };
const singletonCase = { ...clinicalCase, caseLabel: 'proband', facts: [singletonFact], reportedFacts: [singletonFact] };
for (const markup of [api.render({ pmcid: 'PMC123', observations: { cases: [singletonCase] } }),
    api.saved({ schemaVersion: 'pm3-selected-facts-v1', caseFacts: [singletonCase] })]) {
    assert.ok(markup.includes('已核对本文单例临床报告与检测段落'));
    assert.ok(markup.includes('未公开患者编号'));
    assert.ok(markup.includes('不据此认定跨论文病例独立'));
    assert.ok(!markup.includes('尚未建立队列背景到该病例的完整关联'));
}
console.log('PM3 single proband source explanation PASS');

const savedSingletonFact = { field: 'targetVariant', value: 'c.2C>G', citation: sourceFact.citation,
    supportingCitations: sourceFact.supportingCitations, method: 'EXPLICIT_SINGLE_PROBAND_RESULTS',
    sourceBinding: { method: 'EXPLICIT_SINGLE_PROBAND_RESULTS', identityKind: 'SOURCE_SCOPED_UNNAMED_PROBAND_NOT_PATIENT_ID' } };
const savedSingletonMarkup = api.saved({ schemaVersion: 'pm3-selected-facts-v1',
    caseFacts: [{ ...singletonCase, reportedFacts: [savedSingletonFact] }] });
assert.ok(savedSingletonMarkup.includes('已核对本文单例临床报告与检测段落'));
assert.ok(!savedSingletonMarkup.includes('尚未建立队列背景到该病例的完整关联'));
