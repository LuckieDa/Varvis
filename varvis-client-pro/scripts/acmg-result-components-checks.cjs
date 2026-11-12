// Component checks of saved public JSON only; no browser/service/source execution.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {createRequire}=require('node:module');
const nativeRoot=process.env.VARVIS_NATIVE_ROOT;
if(!nativeRoot)throw Error('Set VARVIS_NATIVE_ROOT to existing main resources.');
const project=path.resolve(__dirname,'..'),dependencies=createRequire(path.join(nativeRoot,'varvis-client-pro/package.json'));
const code=dependencies('esbuild').buildSync({stdin:{contents:`
import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import Workbench from './src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx';
import Matrix from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';
import {CandidateOutcomeSummary} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
import {EvidenceEntryDetails} from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';
import {evidenceCards} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
import Ba1Notice from './src/pages/varvis/ChromosomeVisualization/components/Ba1DataScopeNotice.jsx';
export const workbench=run=>renderToStaticMarkup(<Workbench run={run} criteria={run.enabledCriteria}/>);
export const matrix=(run,index)=>renderToStaticMarkup(<Matrix candidate={run.candidates[0]} enabledCriteria={['ACMG_AMP_2015:BS1']} run={run} backgroundIndex={index}/>);
export const summary=(run,index)=>renderToStaticMarkup(<CandidateOutcomeSummary candidate={run.candidates[0]} criteria={['ACMG_AMP_2015:BS1']} run={run} backgroundIndex={index}/>);
export const criterionDetails=(run,code)=>{const card=evidenceCards(run.candidates[0],run.enabledCriteria,'candidate','0',run).find(card=>card.code===code);return renderToStaticMarkup(<EvidenceEntryDetails card={card} entry={card.entries[0]} candidate={run.candidates[0]} run={run}/>);};
export const scope=binding=>renderToStaticMarkup(<Ba1Notice scope={{policyId:'BA1_LOCAL_EXOMES_V1',version:'1.0.0',dataset:'EXOMES',release:'4.1'}} bs1Scope={binding}/>);
`,resolveDir:project,loader:'jsx'},bundle:true,platform:'node',format:'cjs',write:false,nodePaths:[path.join(nativeRoot,'varvis-client-pro/node_modules')],loader:{'.less':'empty','.js':'jsx'},define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'}).outputFiles[0].text;
const compiled={exports:{}};new Function('require','module','exports',code)(dependencies,compiled,compiled.exports);const api=compiled.exports;
const plain=html=>html.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g,'').replace(/<[^>]*>/g,'');
let checks=0;const check=(name,fn)=>{fn();checks++;console.log('PASS '+name);};
const fixture=path.join(nativeRoot,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a10/round3/public-samples-02/a3aeabe87f/1-public.json');
const bytes=fs.readFileSync(fixture),run=JSON.parse(bytes),before=JSON.stringify(run);
check('captured Java same-source two disease backgrounds keep separate MET and NOT_MET in legacy summary and matrix',()=>{
    assert.equal(run.candidates[0].attempts.length,2);
    assert.equal(run.candidates[0].attempts[0].bs1.evaluation.evaluationResult.status,'MET');
    assert.equal(run.candidates[0].attempts[1].bs1.evaluation.evaluationResult.status,'NOT_MET');
    for(const render of [api.summary,api.matrix]){
        const first=plain(render(run,0)),second=plain(render(run,1));
        assert.ok(first.includes('1 项已采用满足') && first.includes('0 项已评价未满足'),first.slice(0,500));
        assert.ok(second.includes('0 项已采用满足') && second.includes('1 项已评价未满足'),second.slice(0,500));
        assert.ok(first.includes('四类合计 1 项证据结果') && second.includes('四类合计 1 项证据结果'));
    }
});
check('workbench first level has exactly four main-count filters and precise causes only secondary',()=>{
    const html=api.workbench(run),text=plain(html);
    assert.equal((html.match(/data-testid="status-filter-/g)||[]).length,4);
    for(const kind of ['ADOPTED_MET','NOT_MET','NOT_APPLICABLE','NO_CONCLUSION'])assert.ok(html.includes(`status-filter-${kind}`));
    assert.ok(text.includes('按原因筛选'));
    assert.ok(!text.includes('采用与过程标签不额外计数'));
    assert.ok(html.includes('evidence-result-row') && text.includes('已采用'));
    assert.ok(!text.includes('规则评价已完成'));
});
check('synthetic pending review never enters adopted count in either legacy or matrix display',()=>{
    const pending=structuredClone(run),evaluation=pending.candidates[0].attempts[0].bs1.evaluation;
    evaluation.review={status:'REQUIRED',decision:'NONE',reviewRecordIds:['review-sha256-'+'a'.repeat(64)]};
    for(const render of [api.summary,api.matrix]){
        const text=plain(render(pending,0));assert.ok(text.includes('0 项已采用满足')&&text.includes('1 项尚无可采用结论'));
    }
    assert.ok(plain(api.matrix(pending,0)).includes('待复核'));
});
check('synthetic invalid saved scope retains visible technical placeholders instead of a blank or missing-case conclusion',()=>{
    const invalid=structuredClone(run);invalid.executionProfile.enabledCriteria=[];
    const text=plain(api.workbench(invalid));
    assert.ok(text.includes('结果待核查')||text.includes('证据范围'));
    assert.ok(text.includes('尚无可采用结论'));
    assert.equal((api.workbench(invalid).match(/data-testid="evidence-result-row"/g)||[]).length,run.enabledCriteria.length);
});
check('history and display projections do not modify original saved data or source bytes',()=>{
    assert.equal(JSON.stringify(run),before);assert.deepEqual(fs.readFileSync(fixture),bytes);
});
check('POLICY_SELECTED scope notice does not relabel BS1 as disabled or EXOMES-only',()=>{
    const text=plain(api.scope({policyId:'POLICY_ROUTED_POPULATION_V1',dataset:'POLICY_SELECTED'}));
    assert.ok(text.includes('BS1') && text.includes('JOINT') && text.includes('EXOMES'));
    assert.ok(!text.includes('BS1 暂不执行') && !text.includes('PM2／BS1 暂不执行'));
});
check('existing Drawer overlay close and return code is retained without adding execution actions',()=>{
    const source=fs.readFileSync(path.join(project,'src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx'),'utf8');
    for(const token of ['zIndex:theme=>theme.zIndex.modal+1','BackdropProps','onClose={()=>setDetail(null)}','关闭并返回'])assert.ok(source.includes(token));
    assert.ok(!source.includes('submitAcmg')&&!source.includes('commandAcmg'));
});
const proofBase=path.join(nativeRoot,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a2-a4');
const proofContract=JSON.parse(fs.readFileSync(path.join(proofBase,'round4/public-contract.json')));
const proofFile=path.join(proofBase,proofContract.actualJavaExamples.find(example=>example.scenario==='UNCAPPED').files['all-local-batch'].path);
const proofBytes=fs.readFileSync(proofFile),proofRun=JSON.parse(proofBytes),proofBefore=JSON.stringify(proofRun);
check('actual PVS1 escape public proof has its source numeric clause and cap comparison on the first screen',()=>{
    const text=plain(api.criterionDetails(proofRun,'PVS1'));
    assert.ok(text.includes('已保存逃逸采用条款') && text.includes('规则需要') && text.includes('本次已有') && text.includes('目前情况与差异'));
    assert.ok(text.includes('MATCH') && text.includes('STRONG'));
    assert.equal(JSON.stringify(proofRun),proofBefore);assert.deepEqual(fs.readFileSync(proofFile),proofBytes);
});
const deNovoBase=path.join(nativeRoot,'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a6-a8/round4');
const deNovoIndex=JSON.parse(fs.readFileSync(path.join(deNovoBase,'actual-sample-index.json'))),deNovoViews=[];
for(const item of deNovoIndex.samples.filter(item=>item.evidenceLevel==='SYNTHETIC_PUBLICATION_LOCAL_REPORT_REAL_BUSINESS_AND_H2_SKELETON')){
    const file=path.join(deNovoBase,item.file),bytes=fs.readFileSync(file),document=JSON.parse(bytes),run=document.publicResult,before=JSON.stringify(document);
    check(`captured ${item.file} adopted conditions/executed receipt are prominent while PM6 stays uncounted`,()=>{
        const text=plain(api.criterionDetails(run,'PS2')),pm6=plain(api.criterionDetails(run,'PM6'));
        assert.ok(text.includes('新发已保存采用条件')&&text.includes('声明、运行与实际执行记录'));
        assert.ok(text.includes('规则需要')&&text.includes('本次已有')&&text.includes('目前情况与差异'));
        assert.ok(pm6.includes('共同事件按所选证据保留，不重复采用'));
        assert.ok(!pm6.includes('新发已保存采用条件'));
        if(item.file.includes('TISSUE_'))assert.ok(text.includes('C1')&&text.includes('C2')&&text.includes('STRONG'));
        else assert.ok(text.includes('SOURCE_BOUND_COMBINED_PS2')||text.includes('SVI_1_1_COMBINED_PS2'));
        assert.equal(JSON.stringify(document),before);assert.deepEqual(fs.readFileSync(file),bytes);
        deNovoViews.push([item.file.includes('TISSUE_')?'denovo-fixed-details':'denovo-combined-details',api.criterionDetails(run,'PS2')]);
    });
}
if(process.env.VARVIS_FRONTEND_ARTIFACT_DIR){
    const output=path.resolve(process.env.VARVIS_FRONTEND_ARTIFACT_DIR);fs.mkdirSync(output,{recursive:true});
    for(const [name,html]of [['four-main-conclusions',api.workbench(run)],['same-source-background0',api.matrix(run,0)],['same-source-background1',api.matrix(run,1)],['pvs1-escape-details',api.criterionDetails(proofRun,'PVS1')],...deNovoViews])
        fs.writeFileSync(path.join(output,name+'.html'),'<!doctype html><meta charset="utf-8"><title>Read-only React SSR developer projection</title>'+html);
    fs.writeFileSync(path.join(output,'layer.json'),JSON.stringify({layer:'ACTUAL_REACT_SSR_COMPONENT_PROJECTION_NOT_BROWSER_ACCEPTANCE',businessRequests:0,sourceRequests:0,scoringCalls:0,sourceFiles:[fixture,proofFile],inputDataClass:'SYNTHETIC Java source policies/minireferences'},null,2)+'\n');
}
console.log(`${checks} React saved-result component checks passed; captured Java input used synthetic policies/minireferences. No page/actual-download acceptance.`);
