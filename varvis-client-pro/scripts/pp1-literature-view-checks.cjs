// Current transport + actual React SSR; synthetic mutations are not scientific validation.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {buildSync} = require('esbuild');
const project = path.resolve(__dirname,'..');
const [optionsPath, reportPath] = process.argv.slice(2);
if (!optionsPath || !reportPath) throw Error('Supply existing CURRENT17 options and a private report path');
const built = buildSync({stdin:{contents:`
import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import * as input from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
import Panel,{Pp1SavedFacts,Pp1PaperProgress,validatePp1Task} from './src/pages/varvis/ChromosomeVisualization/components/Pp1LiteraturePanel.jsx';
export {input,validatePp1Task};
export const saved=value=>renderToStaticMarkup(<Pp1SavedFacts value={value}/>);
export const paper=value=>renderToStaticMarkup(<Pp1PaperProgress paper={value}/>);
export const empty=()=>renderToStaticMarkup(<Panel analysisId="synthetic" draftIdentity="synthetic" prepareDeclaration={()=>({hypotheses:[]})}/>);
`,resolveDir:project,loader:'jsx'},bundle:true,platform:'node',format:'cjs',write:false,
define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'}).outputFiles[0].text;
const compiled={exports:{}};new Function('require','module','exports',built)(require,compiled,compiled.exports);
const {input,validatePp1Task,saved,paper,empty}=compiled.exports,results=[];
const check=(name,fn)=>{fn();results.push({name,status:'PASS'});};
const options=JSON.parse(fs.readFileSync(optionsPath)),original=JSON.stringify(options);
check('CURRENT17 remains valid and is not the historical PM3 workflow',()=>{
    assert.equal(input.localBatchScopeIssue(options),null);assert.equal(input.currentBusinessCriteria(options).length,17);
    assert.equal(input.pm3ScopeClaim(options),false);
});
const next=structuredClone(options),binding={version:'pp1-selected-literature-v1',source:{version:'pp1-paper-job-v1',workflowConfigSha256:'a'.repeat(64)}};
next.executionProfile.profileId='acmg-unified-local-pp1-v1';next.executionProfile.moduleBindings.pp1=binding;
for(const key of ['pm2','prediction','reference'])next.executionProfile.moduleBindings[key].runtimeBinding.pp1=structuredClone(binding);
next.enabledCriteria.push('ACMG_AMP_2015:PP1');next.executionProfile.enabledCriteria=structuredClone(next.enabledCriteria);
check('CURRENT18 profile accepts new PP1, never enters old single-family UI',()=>{
    assert.equal(input.localBatchScopeIssue(next),null);assert.equal(input.currentBusinessCriteria(next).length,18);
    assert.equal(input.pp1ScopeClaim(next),false);assert.equal(input.pm3ScopeClaim(next),false);
});
check('current PP1 v2 task source remains visible in normal CURRENT18',()=>{
    const p=structuredClone(next);p.executionProfile.moduleBindings.pp1.source.version='pp1-paper-job-v2';
    for(const k of ['pm2','prediction','reference'])p.executionProfile.moduleBindings[k].runtimeBinding.pp1=structuredClone(p.executionProfile.moduleBindings.pp1);
    assert.equal(input.localBatchScopeIssue(p),null);assert.equal(input.currentBusinessCriteria(p).length,18);
    const r={...p,status:'COMPLETED_WITH_BLOCKERS',declarationVersion:p.executionProfile.requiredDeclaration};
    assert.equal(input.localBatchSavedRun(r),true);
});
check('unknown or inconsistent PP1 task source version remains rejected',()=>{
    for(const version of ['pp1-paper-job-v3','wrong']){
        const p=structuredClone(next);p.executionProfile.moduleBindings.pp1.source.version=version;
        for(const k of ['pm2','prediction','reference'])p.executionProfile.moduleBindings[k].runtimeBinding.pp1=structuredClone(p.executionProfile.moduleBindings.pp1);
        assert.ok(input.localBatchScopeIssue(p));
    }
    const p=structuredClone(next);p.executionProfile.moduleBindings.pp1.source.version='pp1-paper-job-v2';
    assert.ok(input.localBatchScopeIssue(p));
});
check('mixed historical PP1 preparation is still detected',()=>assert.equal(input.pp1ScopeClaim({...next,pp1Preparation:{}}),true));
for(const [name,mutate] of [['missing source',p=>delete p.executionProfile.moduleBindings.pp1.source],
    ['different runtime',p=>p.executionProfile.moduleBindings.pm2.runtimeBinding.pp1.version='wrong'],
    ['lost criterion',p=>p.enabledCriteria.pop()]])check(name,()=>{const p=structuredClone(next);mutate(p);assert.ok(input.localBatchScopeIssue(p));});
const ref={unitId:'PMC123/main.xml/p2',quote:'Family F1: <script> is literal source text.',sourceSha256:'b'.repeat(64)};
ref.pmcid='PMC123';
const evaluation={schemaVersion:'pp1-segregation-evaluation-v1',status:'MET',strength:'SUPPORTING',awardedPP1Points:1,
    rawSegregationPoints:'1',remainingLocusCapacity:'5',cappedPP1Points:'1',businessPublishable:false,
    events:[{eventId:'event1',memberKey:'opaque-member-key',memberLabel:'II-1',familyLabel:'F1',pmcid:'PMC123',rawPoints:'1',sourceRefs:[ref]}],needs:[{needId:'need1',
        requirement:'另一成员的疾病定义尚未核实；不影响已经确认的传递。',blockingEffect:'MAY_ADD_EVENT',sourceLocators:[ref]}]};
const facts={schemaVersion:'pp1-selected-facts-v1',clinicalApproval:false,evaluation,sourceCoverage:[{pmcid:'PMC123',
    stopReason:'QUALIFIED_EVIDENCE_REACHED',readingPlan:{chunks:[{status:'REVIEWED'},{status:'AVAILABLE'}],remainingChunks:1}}],qualifications:[],excludedTasks:[]};
const row={criterionId:'ACMG_AMP_2015:PP1',status:'MET',appliedStrength:'SUPPORTING',reasonCode:'PP1_QUALIFIED_SEGREGATION_THRESHOLD_MET',reason:'synthetic',details:{pp1Facts:facts}};
check('consistent score and source accepted',()=>assert.equal(input.pp1LiteratureCriterionValid(row),true));
for(const [name,mutate] of [['altered score',r=>r.details.pp1Facts.evaluation.awardedPP1Points=4],
    ['duplicated event',r=>r.details.pp1Facts.evaluation.events.push(structuredClone(r.details.pp1Facts.evaluation.events[0]))],
    ['missing citation',r=>r.details.pp1Facts.evaluation.events[0].sourceRefs=[]],
    ['unsupported very strong',r=>r.appliedStrength='VERY_STRONG']])check(name,()=>{const r=structuredClone(row);mutate(r);assert.equal(input.pp1LiteratureCriterionValid(r),false);});
check('missing source remains explicit NE',()=>assert.equal(input.pp1LiteratureCriterionValid({criterionId:row.criterionId,status:'NOT_EVALUATED',appliedStrength:'NONE',reasonCode:'PP1_REPLAY_FAILED',reason:'failed',details:{}}),true));
check('React shows result, gap, citations, unread scope and escapes source text',()=>{
    const html=saved(facts);for(const text of ['支持级','仍缺什么','另一成员','原文','尚未读取 1','PMC123'])assert.ok(html.includes(text),text);
    assert.ok(!html.includes('<script>'));assert.ok(html.includes('&lt;script&gt;'));
    assert.ok(html.includes('II-1'));assert.ok(!html.includes('opaque-member-key'));
    assert.ok(html.includes('https://pmc.ncbi.nlm.nih.gov/articles/PMC123/'));
});
check('empty facts do not create unused sections',()=>{const html=saved({schemaVersion:'pp1-selected-facts-v1'});assert.ok(!html.includes('已采用'));assert.ok(!html.includes('仍缺什么'));});
const clinical=()=>({...structuredClone(facts),evaluation:{...structuredClone(evaluation),status:'NOT_EVALUATED',strength:null,
    awardedPP1Points:0,events:[],clinicalAssessments:[{memberLabel:'III:1',status:'AFFECTED',reportedCriteria:'named criteria',
        sourceRefs:[ref,structuredClone(ref)],knowledgeBinding:{specificationKey:'TEST@1',scope:{diseaseId:'MONDO:0000001'},
            sourceText:'Use if the patient fulfils named criteria.',jsonPointer:'/criteria/1',source:{sourceUrl:'https://example.org/spec'}}}]}});
check('clinical mapping is visible without converting NE to a zero-point assessment',()=>{
    const f=clinical(),before=JSON.stringify(f),html=saved(f);
    for(const text of ['当前尚不能判定 PP1','已核实的疾病状态依据','成员 III:1','named criteria','TEST@1','MONDO:0000001','不等于已满足 PP1'])assert.ok(html.includes(text),text);
    assert.ok(!html.includes('共分离原始贡献'));assert.equal(JSON.stringify(f),before);
});
check('clinical conflicts are shown as unresolved, never affected',()=>{
    const f=clinical();f.evaluation.clinicalAssessments[0].status='CONFLICT';const html=saved(f);
    assert.ok(html.includes('暂不采用患病结论'));assert.ok(!html.includes('已与本次疾病背景对应'));
});
check('clinical source citations are deduplicated and unsafe text is escaped',()=>{
    const f=clinical();f.evaluation.needs=[];const html=saved(f);
    assert.ok(html.includes('查看原文与位置（1处）'));assert.ok(!html.includes('<script>'));assert.ok(html.includes('&lt;script&gt;'));
});
check('knowledge links reject non-https schemes',()=>{
    const f=clinical();f.evaluation.clinicalAssessments[0].knowledgeBinding.source.sourceUrl='javascript:alert(1)';
    assert.ok(!saved(f).includes('javascript:'));
});
check('member-specific gaps name the affected member and retain source access',()=>{
    const f=clinical();f.evaluation.needs[0].scope={memberLabel:'II:7'};assert.ok(saved(f).includes('成员 II:7'));
});
check('precise missing inputs do not claim the already verified genotype is absent',()=>{
    const f=clinical();f.evaluation.needs=[{reasonCode:'PP1_MEMBER_EVENT_INPUT_INCOMPLETE',scope:{memberLabel:'II:7',missingInputs:['CLINICAL_STATUS']}}];
    const html=saved(f);assert.ok(html.includes('符合本次疾病定义的患病状态依据'));assert.ok(!html.includes('目标变异的基因型依据'));
});
check('expert relative counts are distinguished from evidence points',()=>{
    const f=structuredClone(facts);Object.assign(f.evaluation.events[0],{kind:'EXPERT_AFFECTED_RELATIVES_COUNT',affectedRelativesCount:3});
    const html=saved(f);for(const text of ['已排除先证者','3 位患病携带亲属','并非每人各计一分'])assert.ok(html.includes(text),text);
});
check('read blocks do not conceal pending additions or budget stop',()=>{
    const html=paper({pmcid:'PMC123',stopReason:'PP1_READING_CALL_LIMIT',readingPlan:{
        chunks:[{status:'REVIEWED'}],remainingChunks:0,pendingSourceReview:{claimIds:['a','b']}}});
    for(const text of ['调用上限','2 条','资料块已读不代表'])assert.ok(html.includes(text),text);
});
check('task panel exposes controlled start and refresh',()=>{const html=empty();assert.ok(html.includes('准备 PP1 文献任务'));assert.ok(html.includes('只读刷新'));assert.ok(!html.includes('checked=""'));});
check('unparsed images remain visible after all readable blocks are reviewed',()=>{
    const html=paper({pmcid:'PMC123',stopReason:'SOURCE_FORMAT_REVIEW_REQUIRED',readingPlan:{
        chunks:[{status:'REVIEWED'}],remainingChunks:0,materialCoverage:{unparsedAttachments:1,
            unparsedFiles:[{originalName:'pedigree<script>.jpg',sha256:'a'.repeat(64)}]}}});
    for(const text of ['尚未解析 1','不计入上方可读资料块','查看尚未解析','需要核查'])assert.ok(html.includes(text),text);
    assert.ok(!html.includes('<script>'));assert.ok(html.includes('&lt;script&gt;'));
});
check('HTTP 503 does not claim source absence',()=>{
    const html=paper({pmcid:'PMC123',stopReason:'DENOVO_MODEL_HTTP_503'});
    assert.ok(html.includes('模型服务暂时不可用'));assert.ok(html.includes('没有取得模型结果'));
});
check('attempted blocks are explicitly distinguished from reviewed and unread blocks',()=>{
    const html=paper({pmcid:'PMC123',readingPlan:{chunks:[{status:'REVIEWED'},{status:'ATTEMPTED'},{status:'AVAILABLE'}],remainingChunks:1}});
    assert.ok(html.includes('已尝试处理但未完成复核'));assert.ok(html.includes('不计为已复核'));
});
const task={schemaVersion:'pp1-public-literature-task-v1',taskId:'c'.repeat(64),status:'READY',result:{schemaVersion:'pp1-discovery-process-v1',status:'PARTIAL',businessPublishable:false,modelAttempts:0,searchPolicy:'LITVAR_ONLY',papers:[]}};
check('partial discovery is not scoring success',()=>assert.equal(validatePp1Task(task),task));
check('unexpected search route rejected',()=>assert.throws(()=>validatePp1Task({...task,result:{...task.result,searchPolicy:'GENE_ONLY'}})));
check('no mutation of existing options',()=>assert.equal(JSON.stringify(options),original));
if(process.argv[4]){
    const r=JSON.parse(fs.readFileSync(process.argv[4])),before=JSON.stringify(r);
    check('real saved CURRENT18 retains all eighteen evidence cards',()=>{
        assert.equal(input.localBatchSavedRun(r),true);
        for(const c of r.candidates){const cards=input.evidenceCards(c,r.enabledCriteria,undefined,undefined,r);
            assert.deepEqual(cards.map(v=>v.id),r.enabledCriteria);
            const pp1=cards.find(v=>v.id==='ACMG_AMP_2015:PP1');assert.ok(pp1.entries.length);
            for(const e of pp1.entries){assert.ok(!e.displayIssue);assert.ok(e.pp1LiteratureFacts.evaluation.clinicalAssessments.length);
                assert.ok(!e.reasons.includes('BINDING_NOT_READY'));}
        }
        assert.equal(JSON.stringify(r),before);
    });
    check('mutated real saved PP1 score fails display checks without losing export',()=>{
        const v=structuredClone(r);for(const c of v.candidates)for(const a of c.attempts){
            const row=a.localBatch?.criteria.find(x=>x.criterionId==='ACMG_AMP_2015:PP1');
            if(row)row.details.pp1Facts.evaluation.awardedPP1Points=4;
        }
        const cards=input.evidenceCards(v.candidates[0],v.enabledCriteria,undefined,undefined,v);
        assert.ok(cards.find(c=>c.id==='ACMG_AMP_2015:PP1').entries.every(e=>e.displayIssue));
    });
}
fs.writeFileSync(reportPath,JSON.stringify({scope:'TRANSPORT_AND_REACT_SSR_NOT_BROWSER_OR_SCIENCE',tests:results},null,2));
console.log(`${results.length} transport/SSR checks passed`);
