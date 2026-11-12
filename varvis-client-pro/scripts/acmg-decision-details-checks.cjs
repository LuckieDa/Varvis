// Saved-fact frontend projection and actual React SSR, not a backend execution.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {createRequire}=require('node:module');
const dependencyProject=process.argv[2];
if(!dependencyProject)throw Error('Supply the existing dependency project; no installation is performed.');
const project=path.resolve(__dirname,'..'),dependencies=createRequire(path.join(path.resolve(dependencyProject),'package.json'));
const {buildSync}=dependencies('esbuild');
const built=buildSync({stdin:{contents:`import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import Summary from './src/pages/varvis/ChromosomeVisualization/components/EvidenceDecisionSummary.jsx';
export {decisionDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgDecisionDetails.js';
export {evidenceCards,localBatchScopeIssue} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
export {populationStrataResultValid} from './src/pages/varvis/ChromosomeVisualization/utils/acmgSequencePopulation.js';
export const render=entry=>renderToStaticMarkup(<Summary entry={entry}/>);`,resolveDir:project,loader:'jsx'},bundle:true,platform:'node',format:'cjs',write:false,
nodePaths:[path.join(path.resolve(dependencyProject),'node_modules')],define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'}).outputFiles[0].text;
const compiled={exports:{}};new Function('require','module','exports',built)(dependencies,compiled,compiled.exports);
const {decisionDetails:view,render,populationStrataResultValid,evidenceCards,localBatchScopeIssue}=compiled.exports;
const plain=html=>html.replace(/<style\b[^>]*>[\s\S]*?<\/style>/g,'').replace(/<[^>]*>/g,'');
const H='a'.repeat(64),criterion=(details={})=>({criterionId:'ACMG_AMP_2015:PP1',status:'NOT_EVALUATED',appliedStrength:'NONE',reasonCode:'SYNTHETIC_SAVED_REASON',reason:'Saved test result',details});
const citation={sourceSha256:H,role:'PAPER_PRIMARY',kind:'SOURCE_UNIT',paperId:'PMC1',quote:'SYNTHETIC mother carries allele',locator:'/article/table[1]',title:'SYNTHETIC',locationLabel:'Table1',url:'https://pmc.ncbi.nlm.nih.gov/articles/PMC1/',linkScope:'ARTICLE_LANDING_PAGE',scopeOnly:false};
const need={reasonCode:'PP1_SYNTHETIC_REQUIREMENT',requirement:'SYNTHETIC status and examination age from same visit',expected:'SYNTHETIC expected age-bound observation',
    knownFacts:[{status:'AFFECTED',sourceRefs:[{quote:'SYNTHETIC affected at52',sourceSha256:H,unitId:'/body/p1'}]}],actual:'SYNTHETIC age is not yet bound to that visit',blockingEffect:'BLOCKS_SCORING',route:'SOURCE_REVIEW',scope:{member:'II-6'}};
const pp1={schemaVersion:'pp1-selected-facts-v1',evaluation:{schemaVersion:'pp1-segregation-evaluation-v2',needs:[need],clinicalObservationReview:{needs:[]}},qualifications:[]};
const entry=details=>({savedCriterion:criterion(details),reasons:[],details:[],displayIssue:false});
let count=0;const check=(name,action)=>{action();count++;console.log('PASS '+name);};
check('only selected current-entry facts are projected, raw other-background criteria are not searched',()=>{
    const v={raw:{criteria:[criterion({pp1Facts:pp1})]},evaluation:{criterionId:'ACMG_AMP_2015:PM3',evaluationResult:{status:'NOT_EVALUATED',reasonCode:'UNKNOWN'}}};
    assert.equal(view(v).comparisons.length,0);assert.equal(view(v).comparisonState,'NOT_SAVED');assert.ok(!render(v).includes('affected at52'));
});
check('unknown code and Chinese reason never manufacture required or absent information',()=>{
    const v=entry({});v.savedCriterion.reasonCode='UNKNOWN_CONFLICT_MISSING_FAILED';v.savedCriterion.reason='年龄不足、有冲突、程序失败';
    const d=view(v),text=plain(render(v));assert.equal(d.comparisons.length,0);assert.equal(d.conflicts.length,0);assert.equal(d.issues.length,0);
    assert.ok(text.includes('UNKNOWN_CONFLICT_MISSING_FAILED')&&text.includes('旧记录未保存条件对照'));assert.ok(!text.includes('缺少年龄'));
});
check('PP1 saved need exposes rule, expected, actual known facts, difference, impact, route and source',()=>{
    const v=entry({pp1Facts:pp1}),before=JSON.stringify(v),d=view(v),text=plain(render(v));
    assert.equal(d.comparisonState,'SAVED');assert.equal(d.comparisons.length,1);
    for(const wanted of ['SYNTHETIC status and examination age','expected age-bound observation','SYNTHETIC affected at52','SYNTHETIC age is not yet bound','BLOCKS_SCORING','SOURCE_REVIEW','II-6',H])assert.ok(text.includes(wanted),wanted);
    assert.equal(JSON.stringify(v),before);
});
check('clinical and qualification needs remain separate saved scopes',()=>{
    const facts=structuredClone(pp1);facts.evaluation.clinicalObservationReview.needs=[{...need,scope:{member:'III-5'},reasonCode:'OTHER_VISIT'}];
    facts.qualifications=[{sourceIdentity:{pmcid:'PMC1'},clinicalAssessments:{needs:[{...need,reasonCode:'CLINICAL_ISSUE'}]}}];
    const d=view(entry({pp1Facts:facts}));assert.equal(d.comparisons.length,3);assert.equal(d.comparisons[1].field.member,'III-5');
});
check('PP1 source support and restriction show both exact quotations before requirement details',()=>{
    const facts=structuredClone(pp1);facts.qualifications=[{sourceIdentity:{pmcid:'PMC1'},ascertainmentReview:{families:{F1:{status:'CONFLICT',conflicts:[{
        reasonCode:'PP1_FORMAL_ASCERTAINMENT_SUPPORT_RESTRICTION_CONFLICT',supportSourceRefs:[{quote:'SYNTHETIC phenotype-first',unitId:'support',sourceSha256:H}],restrictionSourceRefs:[{quote:'SYNTHETIC genotype-selected',unitId:'restriction',sourceSha256:'b'.repeat(64)}]}]}}}}];
    const d=view(entry({pp1Facts:facts})),html=render(entry({pp1Facts:facts})),text=plain(html);
    assert.equal(d.conflicts.length,1);assert.ok(html.includes('MuiAlert-standardError'));
    for(const wanted of ['F1','SYNTHETIC phenotype-first','SYNTHETIC genotype-selected','support','restriction','b'.repeat(64)])assert.ok(text.includes(wanted),wanted);
    assert.ok(text.indexOf('取样支持与限制冲突')<text.indexOf('规则需要'));
});
check('failed display validation preserves original value and does not project unverified clinical requirements',()=>{
    const v=entry({pp1Facts:pp1});v.displayIssue=true;v.summary='SYNTHETIC wrong selected identity';
    const d=view(v),text=plain(render(v));assert.equal(d.comparisonState,'UNVERIFIED');assert.equal(d.comparisons.length,0);
    assert.equal(d.original.reasonCode,'SYNTHETIC_SAVED_REASON');assert.ok(text.includes('wrong selected identity'));assert.ok(!text.includes('SYNTHETIC affected at52'));
});
check('execution failure remains prominent and escaped without becoming case-data absence',()=>{
    const v=entry({});v.executionDiagnostics=[['last success','JSON output complete'],['failed step','ROOT_EXITED_WITH_ACTIVE_DESCENDANTS <script>bad</script>']];
    const html=render(v),text=plain(html);assert.ok(html.includes('MuiAlert-standardError'));
    assert.ok(text.includes('ROOT_EXITED_WITH_ACTIVE_DESCENDANTS')&&text.includes('JSON output complete'));
    assert.ok(html.includes('&lt;script&gt;bad&lt;/script&gt;')&&!html.includes('<script>bad</script>'));assert.equal(view(v).comparisons.length,0);
});
function material() {
    const fields=['paper','case','gene','variant','proband','mother','father','testing','parentage','phenotype','family','overlap'].map(g=>({path:`/groups/${g}/identity`,value:null,availability:'NOT_EXTRACTED',verification:'UNVERIFIED',issues:[],citations:[],sourceEvidence:[]}));
    Object.assign(fields[5],{value:'PRESENT',availability:'REPORTED',verification:'VERIFIED',sourceEvidence:[citation]});
    Object.assign(fields[6],{value:{sourceA:'PRESENT',sourceB:'ABSENT'},availability:'REPORTED',verification:'CONFLICT',issues:['FATHER_SOURCE_CONFLICT'],citations:[citation]});
    const m={schemaVersion:'denovo-core-display-v1',dataClass:'SYNTHETIC',status:'INSUFFICIENT_DATA',criterionDecision:'NOT_RUN',caseKey:'PMC1#SYNTHETIC_CASE',documentSha256:H,ledgerSha256:H,fields,coverageLimitations:[],blockers:[{path:fields[6].path,category:'CONFLICT',code:'FATHER_SOURCE_CONFLICT',meaning:'QUALIFICATION_REQUIRED'}]};
    m.explanation={schemaVersion:'literature-explanation-v1',caseKey:m.caseKey,paper:{paperId:'PMC1',title:'SYNTHETIC',url:citation.url},summary:'SYNTHETIC saved requirements',scopeStatement:'SYNTHETIC checked scope',warnings:[],items:[{id:'FATHER',category:'CONFLICT',label:'SYNTHETIC father testing',requirement:'SYNTHETIC parent test source agreement',finding:'conflict',explanation:'SYNTHETIC sources disagree',distinction:'SYNTHETIC conflict is not absence',effect:'SYNTHETIC prevents event adoption',nextStep:'SYNTHETIC review sourceA and sourceB',scopeStatement:'SYNTHETIC inspected table',fieldPaths:[fields[6].path],observedFieldPaths:[fields[5].path],reasonCodes:['FATHER_SOURCE_CONFLICT'],checkedScope:[]}]};return m;
}
check('validated literature explanation uses only observed verified fields with exact saved requirement/difference/next step',()=>{
    const v={...entry({}),coreMaterials:[material()]},d=view(v),text=plain(render(v));assert.equal(d.comparisons.length,1);assert.equal(d.conflicts.length,1);
    for(const wanted of ['SYNTHETIC parent test source agreement','PRESENT','SYNTHETIC sources disagree','SYNTHETIC review sourceA and sourceB','/groups/mother/identity','SYNTHETIC mother carries allele'])assert.ok(text.includes(wanted),wanted);
    assert.ok(text.includes('当前记录未将冲突双方分别保存'));assert.ok(text.includes('sourceA')&&text.includes('sourceB'));
});
check('different target core material is never counted as missing data in this background',()=>{
    const m=material();m.selectionMatch='DIFFERENT_TARGET';const d=view({...entry({}),coreMaterials:[m]});
    assert.equal(d.comparisons.length,0);assert.equal(d.conflicts.length,0);assert.equal(d.issues[0].kind,'SCOPE');
});
check('malformed or unbound literature explanation cannot be promoted to observed facts',()=>{
    const m=material();m.explanation.items[0].observedFieldPaths=['/groups/father/identity'];
    const d=view({...entry({}),coreMaterials:[m]});assert.equal(d.comparisons.length,0);assert.equal(d.issues[0].kind,'DISPLAY');
});
check('PM3 saved requirement and observation blocker use actual reported facts and preserve missing independent condition text',()=>{
    const facts={schemaVersion:'pm3-selected-facts-v1',assessment:{calculation:{contributions:[{observationId:'OBS1',requirements:[{reason:'PM3_SAVED_REQUIREMENT',status:'CONFLICT'}]}]}},caseFacts:[{pmcid:'PMC2',caseLabel:'II-1',qualificationBlocker:{reasonCode:'PM3_CASE_GENE_UNRESOLVED',dependentChecks:['DISEASE']},reportedFacts:[{field:'GENE',sourceValue:'SYNTHETIC_GENE',citation:{quote:'SYNTHETIC reported gene',unitId:'p1'}}]}]};
    const d=view(entry({pm3Facts:facts})),text=plain(render(entry({pm3Facts:facts})));assert.equal(d.comparisons.length,2);
    assert.ok(text.includes('SYNTHETIC_GENE')&&text.includes('SYNTHETIC reported gene')&&text.includes('未保存具体规则条件'));
});
check('new de novo comparison preserves ordinary and special event values and source IDs without rescore',()=>{
    const v=entry({deNovoFacts:{events:[{ordinal:1,disposition:'EXCLUDED',sourceRefs:[H+'#/mother'],factComparisons:[{memberRole:'MOTHER',ordinaryResult:'PRESENT',specialEventResult:'ABSENT',reasonCode:'DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT'}]}]}});
    const d=view(v),text=plain(render(v));assert.equal(d.conflicts.length,1);assert.equal(d.conflicts[0].left.value,'PRESENT');assert.equal(d.conflicts[0].right.value,'ABSENT');assert.ok(text.includes(H+'#/mother')&&text.includes('EXCLUDED'));
});
check('bound failed material preparation exposes module/stage but never invents source absence',()=>{
    const v=entry({modulePointer:'/modules/pvs1/0',materialPreparation:{schemaVersion:'material-preparation-summary-v1',reasonCode:'SYNTHETIC_SAVED_REASON',moduleResultPointer:'/modules/pvs1/0',module:'pvs1',moduleStatus:'FAILED',capturedDocuments:2}});
    const d=view(v);assert.equal(d.issues[0].kind,'EXECUTION');assert.equal(d.issues[0].detail.capturedDocuments,2);
    v.savedCriterion.details.materialPreparation.moduleResultPointer='/other';assert.equal(view(v).issues.length,0);
});
check('shared failure category is prominent but absent stage and recovery details are never invented',()=>{
    const v=entry({});v.savedCriterion.reasonCode='PROCESS_FAILED';v.evaluation={criterionId:'ACMG_AMP_2015:PS2',appliedStrength:'NONE',evaluationResult:{status:'NOT_EVALUATED',reasonCode:'PROCESS_FAILED'}};
    const d=view(v),text=plain(render(v));assert.equal(d.issues[0].kind,'EXECUTION_FAILED');
    assert.ok(text.includes('PROCESS_FAILED')&&text.includes('未单独保存失败阶段、最后成功点或可恢复性'));assert.equal(d.comparisons.length,0);
});
check('shared conflict label does not fabricate an opposing value or source when no pair was saved',()=>{
    const v=entry({});v.savedCriterion.reasonCode='GENOTYPE_CONFLICT';v.evaluation={criterionId:'ACMG_AMP_2015:PM3',appliedStrength:'NONE',evaluationResult:{status:'NOT_EVALUATED',reasonCode:'GENOTYPE_CONFLICT'}};
    const d=view(v),text=plain(render(v));assert.equal(d.issues[0].kind,'CONFLICT');assert.equal(d.conflicts.length,0);
    assert.ok(text.includes('原记录未将冲突双方的字段、值和出处分别保存'));
});
check('long text and first screen lists stay bounded with explicit original-retention notes',()=>{
    const v=entry({pp1Facts:structuredClone(pp1)}),facts=v.savedCriterion.details.pp1Facts;
    facts.evaluation.needs=Array.from({length:20},(_,i)=>({...need,reasonCode:'NEED_'+i,requirement:'<script>long</script>'+'x'.repeat(7000)}));
    const html=render(v),text=plain(html);assert.ok(text.includes('前6000字符')&&text.includes('条件对照20'));
    assert.ok(!html.includes('<script>long</script>'));assert.ok(!text.includes('NEED_9'));assert.ok(!html.includes('<button'));
});
check('nullable and malformed saved values remain safe and do not start work',()=>{
    for(const v of [undefined,null,{},[],{savedCriterion:[],coreMaterials:{},executionDiagnostics:'FAILED'},{savedCriterion:criterion({pp1Facts:{evaluation:{schemaVersion:'pp1-segregation-evaluation-v2',needs:'bad'},qualifications:[null,[]]}})}])assert.doesNotThrow(()=>{view(v);render(v);});
});
if(process.argv[3])check('archived real-source Python missing-model replay keeps original needs/quotes and bytes immutable',()=>{
    const filename=path.resolve(process.argv[3]),bytes=fs.readFileSync(filename),original=JSON.parse(bytes),before=JSON.stringify(original);
    const row=criterion({pp1Facts:{schemaVersion:'pp1-selected-facts-v1',evaluation:original.evaluation,qualifications:original.qualifications}});
    const d=view({savedCriterion:row,displayIssue:false}),text=plain(render({savedCriterion:row,displayIssue:false}));
    assert.ok(d.comparisons.length>0);assert.ok(text.includes('PP1_FORMAL_MODEL_REQUIRED'));assert.equal(JSON.stringify(original),before);assert.deepEqual(fs.readFileSync(filename),bytes);
});
if(process.argv[4]) {
    const directory=path.resolve(process.argv[4]),captured=new Map();let cases=0,ready=0,notReady=0;
    for(const dir of fs.readdirSync(directory,{withFileTypes:true}).filter(row=>row.isDirectory())) {
        let filename=path.join(directory,dir.name,'1-public.json');
        if(!fs.existsSync(filename))filename=path.join(directory,dir.name,'public.json');
        if(!fs.existsSync(filename))continue;
        const bytes=fs.readFileSync(filename),document=JSON.parse(bytes),before=JSON.stringify(document);
        const saved=[];
        check(`captured 469147aa BS1 first-screen conditions ${dir.name}`,()=>{
            for(const candidate of document.candidates)for(const attempt of candidate.attempts) {
                const v=attempt.bs1;if(!v)continue;
                // Only outer-validated values are passed to this display property.
                if(v.qualityDecision?.status==='READY'&&v.evaluation.deterministicTrace?.executionStatus==='COMPLETED')
                    assert.equal(populationStrataResultValid(v,v.context),true,'captured completed row must pass its actual source/policy reader');
                const item={evaluation:v.evaluation,savedPopulationResult:v,displayIssue:false};
                const d=view(item),html=render(item),text=plain(html);saved.push({value:v,view:d});
                if(v.qualityDecision?.status==='READY'&&v.evaluation.deterministicTrace?.executionStatus==='COMPLETED') {
                    ready++;assert.ok(d.comparisons.length>0);const row=d.comparisons[0];
                    assert.equal(row.field.contextId,v.context.contextId);assert.equal(row.known.conditionActual,'0.0021');
                    assert.ok(text.includes('AF 大于')&&text.includes('0.0021')&&text.includes('SYNTHETIC: BS1 threshold'));
                    assert.ok(row.sources.some(source=>source.sourceId==='synthetic-sex-expert'));
                } else {notReady++;assert.equal(d.comparisons.length,0,'blocked/no-execution result must not invent condition checks');}
            }
            assert.equal(JSON.stringify(document),before);assert.deepEqual(fs.readFileSync(filename),bytes);
        });
        captured.set(dir.name,saved);cases++;
    }
    assert.equal(cases,11,'read eleven captured public case files, including public.json fallback');
    check('captured same-source two-background result keeps AF0.0021 and distinct0.002/0.003 thresholds and saved outcomes',()=>{
        const rows=captured.get('a3aeabe87f');assert.equal(rows.length,2);
        assert.deepEqual(rows.map(row=>row.view.comparisons[0].expected.decimal),['0.002','0.003']);
        assert.deepEqual(rows.map(row=>row.view.comparisons[0].known.conditionActual),['0.0021','0.0021']);
        assert.deepEqual(rows.map(row=>row.view.comparisons[0].difference.savedOutcome),['TRUE','FALSE']);
        assert.notEqual(rows[0].view.comparisons[0].field.contextId,rows[1].view.comparisons[0].field.contextId);
    });
    const fixture=()=>{const value=structuredClone(captured.get('a3aeabe87f')[0].value);return {evaluation:structuredClone(value.evaluation),savedPopulationResult:value,displayIssue:false};};
    check('synthetic swapped population background is technical mismatch, never borrowed conditions',()=>{
        const v=fixture();v.evaluation.contextId='context-OTHER';const d=view(v);assert.equal(d.comparisons.length,0);assert.equal(d.issues[0].kind,'DISPLAY');
    });
    for(const [name,mutate]of [['missing parameter',v=>v.evaluation.deterministicTrace.parameters=v.evaluation.deterministicTrace.parameters.filter(p=>!p.name.startsWith('tier.'))],
        ['wrong threshold',v=>v.evaluation.deterministicTrace.parameters.find(p=>p.name.startsWith('tier.')).value='0.8'],
        ['wrong unit',v=>v.evaluation.deterministicTrace.parameters.find(p=>p.name.startsWith('tier.')).unit='alleles'],
        ['duplicate step',v=>v.evaluation.deterministicTrace.steps.push({...v.evaluation.deterministicTrace.steps[0]})],
        ['missing condition threshold',v=>delete v.preparation.policy.tiers[0].conditions[0].decimal]])
        check('synthetic population condition projection rejects '+name,()=>{
            const v=fixture();mutate(v.savedPopulationResult);const d=view(v);assert.equal(d.comparisons.length,0);assert.ok(d.issues.some(issue=>issue.kind==='DISPLAY'));
        });
    check('synthetic absent condition fact is explicitly not saved, never zero or AF/FAF substitution',()=>{
        const v=fixture();delete v.savedPopulationResult.qualityDecision.facts.AF;
        const row=view(v).comparisons[0];assert.ok(row.known.conditionActual.includes('未保存 AF'));assert.equal(row.difference.savedOutcome,'TRUE');
        assert.ok(row.difference.statement.includes('只展示保存的'));assert.equal(v.savedPopulationResult.evaluation.appliedStrength,'STRONG');
    });
    check('synthetic unexecuted and foreign-mode conditions do not acquire saved outcomes',()=>{
        const v=fixture(),p=v.savedPopulationResult;
        p.preparation.policy.tiers.push({...structuredClone(p.preparation.policy.tiers[0]),tierId:'FOREIGN',moi:'AUTOSOMAL_RECESSIVE'});
        assert.equal(view(v).comparisons.length,1);p.evaluation.deterministicTrace.executionStatus='NOT_RUN';assert.equal(view(v).comparisons.length,0);
    });
    check('raw population result without explicit selected savedPopulationResult property is not searched',()=>{
        const v=fixture();v.raw=v.savedPopulationResult;delete v.savedPopulationResult;assert.equal(view(v).comparisons.length,0);
    });
    console.log(`Captured population layer: ${cases} actual public JSON files, ${ready} READY completed rows, ${notReady} blocked/unexecuted rows; original Java used synthetic policies/minireferences, not clinical proof.`);
}
if(process.argv[5]) {
    const directory=path.resolve(process.argv[5]),contractBytes=fs.readFileSync(path.join(directory,'public-contract.json')),contract=JSON.parse(contractBytes);
    const hash=bytes=>require('node:crypto').createHash('sha256').update(bytes).digest('hex');
    const expectedCaps={UNCAPPED:'STRONG',PARENT_CAP:'MODERATE',LOCAL_CAP:'SUPPORTING',BOTH_CAPS:'SUPPORTING'};
    let captured=0;const entries=[];
    for(const scenario of ['UNCAPPED','PARENT_CAP','LOCAL_CAP','BOTH_CAPS']) {
        const filename=path.join(directory,scenario+'-all-local-batch.json'),bytes=fs.readFileSync(filename),document=JSON.parse(bytes),before=JSON.stringify(document);
        const pin=contract.actualJavaExamples.find(example=>example.scenario===scenario).files['all-local-batch'].sha256;
        check('actual pinned PVS1 escape first-screen '+scenario,()=>{
            assert.equal(hash(bytes),pin);assert.equal(localBatchScopeIssue(document),null);
            for(const candidate of document.candidates) {
                const card=evidenceCards(candidate,document.enabledCriteria,'candidate','0',document).find(row=>row.code==='PVS1');
                for(const item of card.entries) {
                    assert.equal(item.displayIssue,false,'actual new proof must pass the current public method reader');
                    const projection=view(item),row=projection.comparisons.find(value=>value.origin==='PVS1 已保存逃逸采用条款'),text=plain(render(item));
                    assert.ok(row);assert.equal(row.expected.ruleId,'critical-5');assert.ok(row.requirement.includes('5–190')&&row.requirement.includes('1 个参考蛋白残基'));
                    assert.equal(row.known.referenceProteinLength,199);assert.equal(row.known.referenceResiduesAffected,195);assert.equal(row.difference.savedRuleCheck.status,'MATCH');
                    assert.equal(row.difference.savedRuleCheck.affectedResiduesInRegion,186);assert.equal(row.impact.sourceRuleStrength,'STRONG');
                    assert.equal(row.impact.adoptedStrength,expectedCaps[scenario]);assert.ok(row.sources.some(source=>source.sourceId==='synthetic-source'));
                    assert.ok(text.includes('PVS1 已保存逃逸采用条款')&&text.includes('不重新取证、评分或创建审批'));entries.push(item);captured++;
                }
            }
            assert.equal(JSON.stringify(document),before);assert.deepEqual(fs.readFileSync(filename),bytes);
        });
    }
    assert.equal(captured,8);assert.deepEqual(fs.readFileSync(path.join(directory,'public-contract.json')),contractBytes);
    for(const status of ['UNAVAILABLE','NOT_ADOPTED'])check('synthetic '+status+' escape proof never shows positive adopted-clause comparison',()=>{
        const item=structuredClone(entries[0]);item.savedCriterion.details.pvs1Facts.escapeAdoptionProof.status=status;
        assert.equal(view(item).comparisons.some(row=>row.origin==='PVS1 已保存逃逸采用条款'),false);
    });
    check('synthetic invalid display cannot expose positive escape conditions or alter the saved strength',()=>{
        const item=structuredClone(entries[0]),strength=item.savedCriterion.appliedStrength;item.displayIssue=true;
        assert.equal(view(item).comparisons.length,0);assert.equal(item.savedCriterion.appliedStrength,strength);
    });
    for(const [name,mutate]of [['missing numeric clause',p=>delete p.selectedRule.minimumAffectedResidues],
        ['missing referenced source',p=>p.sources=[]],['missing effect operands',p=>delete p.proteinEffect.firstAlteredResidue],
        ['missing saved rule check',p=>delete p.ruleCheck],['missing source strength cap',p=>delete p.strengthDerivation.parentPolicyCap]])check('synthetic incomplete escape proof keeps no invented first-screen '+name,()=>{
            const item=structuredClone(entries[0]);mutate(item.savedCriterion.details.pvs1Facts.escapeAdoptionProof);
            const d=view(item);assert.equal(d.comparisons.some(row=>row.origin==='PVS1 已保存逃逸采用条款'),false);assert.ok(d.issues.some(issue=>issue.kind==='DISPLAY'));
        });
    console.log('Captured escape layer: four pinned actual Java public files/eight rows using SYNTHETIC policies and minireferences; no new clinical adoption or Java re-execution.');
}
if(process.argv[6]) {
    const directory=path.resolve(process.argv[6]),indexPath=path.join(directory,'actual-sample-index.json'),indexBytes=fs.readFileSync(indexPath),index=JSON.parse(indexBytes);
    const hash=bytes=>require('node:crypto').createHash('sha256').update(bytes).digest('hex');
    const entries=[],layers={businessBoth:0,businessSingleRefused:0,selectedFiles:0,selectedScopes:0};
    // Direct SelectedBatch fields are mapped to the existing frontend criterion
    // shape only for this display test. No profile/context is invented and this
    // wrapper is not a public business or HTTP acceptance claim.
    const directEntry=(module,row)=>({displayIssue:false,evaluation:row,savedCriterion:{criterionId:row.criterionId,
        status:row.evaluationResult.status,reasonCode:row.evaluationResult.reasonCode,appliedStrength:row.appliedStrength,
        details:{deNovoFacts:module.publicSummary}}});
    const publicEntry=row=>({displayIssue:false,savedCriterion:row,evaluation:{criterionId:row.criterionId,appliedStrength:row.appliedStrength,
        evaluationResult:{status:row.status,reasonCode:row.reasonCode}}});
    const checkEntry=(item,scenario)=>{
        const facts=item.savedCriterion.details.deNovoFacts,proof=facts.adoptionProof,d=view(item),text=plain(render(item));
        const adopted=d.comparisons.filter(row=>row.origin==='新发已保存采用条件');
        const selected=proof.selectedCriterion===item.savedCriterion.criterionId.split(':').pop()&&item.savedCriterion.status==='MET';
        if(proof.status==='ADOPTED'&&proof.projectionStatus==='COMPLETE'&&selected) {
            assert.ok(adopted.length>0,scenario+' must expose the executed adopted conditions');entries.push(item);
            assert.ok(adopted.every(row=>row.sources.some(source=>typeof source.locator==='string'&&typeof source.sourceSha256==='string')));
            if(proof.policy.eventMode==='TISSUE_DIFFERENTIAL_PS2') {
                assert.equal(facts.rawQuarterPoints,0);assert.ok(adopted.some(row=>row.impact.savedStrength===item.savedCriterion.appliedStrength));
                assert.ok(text.includes('零 SVI 积分不表示未采用'));
                assert.ok(adopted.some(row=>row.field.condition==='C1 三联体与检测'));
                assert.ok(adopted.some(row=>row.field.condition==='亲本可用性'));
                const c2=adopted.find(row=>row.field.condition==='C2 组织观察与检测');assert.ok(c2);
                if(['TISSUE_BOTH','TISSUE_C2'].includes(scenario)) {
                    assert.equal(c2.known.observations[0].altReads,20);assert.equal(c2.known.observations[0].totalReads,100);
                    assert.equal(c2.known.observations[1].altReads,0);assert.equal(c2.known.observations[1].limit,0.01);
                }
                if(scenario==='TISSUE_BOTH')assert.equal(item.savedCriterion.appliedStrength,'STRONG');
                if(['TISSUE_C1','TISSUE_C2'].includes(scenario))assert.equal(item.savedCriterion.appliedStrength,'MODERATE');
                if(scenario==='TISSUE_C2') {
                    const c1=adopted.find(row=>row.field.condition==='C1 三联体与检测'),parents=adopted.find(row=>row.field.condition==='亲本可用性');
                    assert.equal(c1.difference.savedConditionStatus,'NOT_ESTABLISHED');assert.equal(c1.known.confirmedParentage,false);
                    assert.equal(parents.known.parentalAvailability,'UNAVAILABLE');assert.ok(c2.difference.status==='MET');
                }
            } else {
                assert.ok(adopted.every(row=>row.field.condition==='组合 PS2 采用条款'));
                const row=adopted[0];assert.equal(row.known.confirmedParentage,false);assert.equal(row.known.allowAssumedParentage,true);
                assert.equal(row.known.rawQuarterPoints,2);assert.equal(item.savedCriterion.appliedStrength,'SUPPORTING');
            }
        } else {
            assert.equal(adopted.length,0,'unadopted/incomplete/nonselected criterion cannot acquire adopted conditions');
            if(proof.status==='ADOPTED'&&!selected)assert.ok(d.issues.some(issue=>issue.kind==='SHARED_EVENT'));
        }
        const receipts=facts.calculationBindings?.filter(binding=>binding.criterionId===item.savedCriterion.criterionId)||[];
        if(receipts.length===1) {
            const receipt=d.issues.find(issue=>issue.kind==='EXECUTION_RECEIPT');assert.ok(receipt);
            assert.equal(receipt.detail.declaredCalculatorVersion,receipts[0].declaredCalculatorVersion);
            assert.equal(receipt.detail.runtimeCalculatorVersion,receipts[0].runtimeCalculatorVersion);
            assert.equal(Object.hasOwn(receipt.detail,'executedCalculatorVersion'),Object.hasOwn(receipts[0],'executedCalculatorVersion'));
        }
    };
    for(const sample of index.samples)check('pinned actual new de novo detail '+path.basename(sample.file),()=>{
        const filename=path.resolve(directory,sample.file),bytes=fs.readFileSync(filename),document=JSON.parse(bytes),before=JSON.stringify(document);
        assert.equal(bytes.length,sample.bytes);assert.equal(hash(bytes),sample.sha256);
        if(document.httpStatus!==undefined) {
            layers.businessSingleRefused++;assert.equal(document.httpStatus,400);assert.equal(document.actualErrorReason,'ACMG_SELECTION_REQUIRES_CURRENT_WORKBENCH');
            assert.equal(document.deNovoEvaluations,0);assert.equal(document.sourceSupplyCalls,0);assert.equal(document.publicResult,undefined);
        } else if(document.publicResult) {
            layers.businessBoth++;
            for(const candidate of document.publicResult.candidates)for(const attempt of candidate.attempts)for(const row of attempt.localBatch.criteria)
                if(row.details?.deNovoFacts?.adoptionProof)checkEntry(publicEntry(row),document.privateModuleResult.publicSummary.adoptionProof.policy.eventMode==='TISSUE_DIFFERENTIAL_PS2'?'TISSUE_BOTH':'COMBINED_ASSUMED');
        } else {
            layers.selectedFiles++;
            for(const key of ['all','PS2Only','PM6Only'].filter(key=>Object.hasOwn(document,key))) {
                const module=document[key];assert.ok(module?.publicSummary?.adoptionProof);layers.selectedScopes++;
                for(const row of Object.values(module.criteria))checkEntry(directEntry(module,row),document.scenario);
                if(key==='PM6Only'&&module.publicSummary.adoptionProof.selectedCriterion==='PS2')assert.equal(module.criteria.PS2,undefined,'source SelectedBatch only emits requested PM6 row');
            }
        }
        assert.equal(JSON.stringify(document),before);assert.deepEqual(fs.readFileSync(filename),bytes);
    });
    assert.equal(layers.businessBoth,2);assert.equal(layers.businessSingleRefused,4);assert.equal(layers.selectedFiles,17);assert.equal(layers.selectedScopes,35);
    const fixed=entries.find(item=>item.savedCriterion.details.deNovoFacts.adoptionProof.calculationMethod==='EXPERT_FIXED_STRENGTH');assert.ok(fixed);
    for(const [status,projection]of [['NOT_RUN','NOT_AVAILABLE'],['NOT_ADOPTED','COMPLETE'],['ADOPTED','INCOMPLETE']])
        check(`synthetic ${status}/${projection} proof adds no adopted clinical-condition rows`,()=>{
            const item=structuredClone(fixed),proof=item.savedCriterion.details.deNovoFacts.adoptionProof;proof.status=status;proof.projectionStatus=projection;
            const d=view(item);assert.equal(d.comparisons.some(row=>row.origin==='新发已保存采用条件'),false);assert.ok(d.issues.some(issue=>issue.kind==='METHOD_PROOF'));
        });
    check('synthetic absent executed version is never copied from declared/runtime version',()=>{
        const item=structuredClone(fixed),facts=item.savedCriterion.details.deNovoFacts;
        for(const receipt of facts.calculationBindings)delete receipt.executedCalculatorVersion;
        const receipt=view(item).issues.find(issue=>issue.kind==='EXECUTION_RECEIPT');assert.ok(receipt);
        assert.equal(Object.hasOwn(receipt.detail,'executedCalculatorVersion'),false);assert.ok(receipt.detail.executionNote.includes('不证明已执行'));
    });
    check('synthetic missing c1 source record produces display issue without guessed conditions or strength changes',()=>{
        const item=structuredClone(fixed),strength=item.savedCriterion.appliedStrength;
        item.savedCriterion.details.deNovoFacts.adoptionProof.events[0].c1.sources=[];
        const d=view(item);assert.equal(d.comparisons.some(row=>row.origin==='新发已保存采用条件'),false);assert.ok(d.issues.some(issue=>issue.kind==='DISPLAY'));
        assert.equal(item.savedCriterion.appliedStrength,strength);
    });
    check('synthetic rejected current entry cannot expose otherwise complete adopted proof',()=>{
        const item=structuredClone(fixed);item.displayIssue=true;assert.equal(view(item).comparisons.some(row=>row.origin==='新发已保存采用条件'),false);
    });
    assert.deepEqual(fs.readFileSync(indexPath),indexBytes);
    console.log('Captured new de novo layer: '+JSON.stringify(layers)+'; source SelectedBatch scopes are distinct from four HTTP400 legacy single-business rejections. All sources/policies synthetic; no new Java execution or clinical adoption.');
}
console.log(count+' saved decision projection/React SSR checks passed; synthetic controls and optional real-source replay are distinct; no backend/page/download execution.');
