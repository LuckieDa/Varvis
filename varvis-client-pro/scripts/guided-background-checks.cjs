const assert = require('node:assert/strict');
const path = require('node:path');
const { buildSync } = require('esbuild');
const project = path.resolve(__dirname, '..');
const bundled = buildSync({ stdin: { contents: `export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgBackground.js'; export {default as Preparation} from './src/pages/varvis/ChromosomeVisualization/components/AcmgGuidedPreparation.jsx';`, resolveDir: project }, bundle: true, platform: 'node', format: 'cjs', write: false, packages: 'external', loader: { '.less': 'empty' } }).outputFiles[0].text;
const compiled = { exports: {} }; new Function('require', 'module', 'exports', bundled)(require, compiled, compiled.exports);
const api = compiled.exports;
const React = require('react'), { renderToStaticMarkup } = require('react-dom/server');
let checks = 0;
function check(name, fn) { fn(); checks++; console.log(`PASS ${name}`); }
const relation = { stableRelationshipKey:'r1', selectable:true, diseaseLabel:'Disease A', hgncId:'HGNC:1', mondoId:'MONDO:0000001', moi:'AD' };
const candidate = { candidateId:'v1', rank:1, geneSymbol:'GENE', catalog:{hgncId:'HGNC:1'}, allele:{chromosome:'1',position:20,reference:'A',alternate:'G'}, recommendation:{ recommendationId:'rec1', executionSourceSha256:'a'.repeat(64), relationships:[relation], annotations:[] }, transcriptChoices:[] };
const options = { candidates:[candidate], requestedN:1, selectedCount:1, enabledCriteria:[] };
const context = { contextId:'c1', geneSymbol:'GENE', hgncId:'HGNC:1', mondoId:'MONDO:0000002', diseaseLabels:['Disease B'], publishedInheritance:['AD'], sources:[] };

check('one relationship creates one automatic background, with unknown patient state',()=>{
 const i=api.prepareGuidedInputs(options,[]).v1;
 assert.equal(i.backgroundInputs.length,1); assert.equal(i.backgroundInputs[0].stableRelationshipKey,'r1'); assert.equal(i.backgroundInputs[0].affectedStatus,'UNKNOWN');
});
check('de novo intake refresh preserves the same confirmed background and rejects changes',()=>{
 const snapshot={options:{...options,executionProfile:{manifestSha256:'before',moduleBindings:{
     deNovo:{manifestSha256:'fixed',modelArtifactReplay:{candidateSnapshotSha256:'before'}},pvs1:{version:'one'},
 }}},literature:{contexts:[]}};
 const previous=api.prepareGuidedInputs(options,[]);
 previous.v1.backgroundInputs[0].affectedStatus='AFFECTED';
 const next=structuredClone(snapshot);
 next.options.candidates[0].recommendation.recommendationId='rec2';
 next.options.candidates[0].recommendation.policyHash='new-source-binding';
 next.options.executionProfile.manifestSha256='after';
 next.options.executionProfile.moduleBindings.deNovo.modelArtifactReplay.candidateSnapshotSha256='after';
 const rebound=api.rebindGuidedInputsAfterIntake(snapshot,next,previous);
 assert.equal(rebound.v1.recommendationId,'rec2');
 assert.equal(rebound.v1.backgroundInputs[0].affectedStatus,'AFFECTED');
 const changedRules=structuredClone(next);
 changedRules.options.executionProfile.moduleBindings.pvs1.version='two';
 assert.throws(()=>api.rebindGuidedInputsAfterIntake(snapshot,changedRules,previous),/重新准备并确认/);
 next.options.candidates[0].recommendation.relationships[0].moi='AR';
 assert.throws(()=>api.rebindGuidedInputsAfterIntake(snapshot,next,previous),/重新准备并确认/);
});
check('different disease backgrounds are all prepared without choosing one',()=>{
 const i=api.prepareGuidedInputs(options,[context]);
 assert.equal(i.v1.backgroundInputs.length,2); assert.equal(api.backgroundRows(options,[context],i)[0].status,'MULTIPLE');
});
check('same gene disease inheritance is merged across sources',()=>assert.equal(api.backgroundChoices(candidate,[{...context,mondoId:relation.mondoId}]).length,1));
check('missing inheritance source is retained without creating a false duplicate',()=>{
 const choices=api.backgroundChoices(candidate,[{...context,mondoId:relation.mondoId,publishedInheritance:[],sources:[{sourceUrl:'https://example.test/unknown'}]}]);
 assert.equal(choices.length,1); assert.equal(choices[0].moi,'AD'); assert.equal(choices[0].inheritanceUnspecifiedSources.length,1);
});
check('background grouping never mutates cached source arrays',()=>{
 const supplied=[context,{...context,contextId:'other',publishedInheritance:[],sources:[{sourceUrl:'https://example.test/unspecified'}]}];
 const frozen=JSON.stringify(supplied);
 api.backgroundChoices(candidate,supplied);api.backgroundChoices(candidate,supplied);
 assert.equal(JSON.stringify(supplied),frozen);
});
check('AD and AR remain distinct; same label never merges different disease IDs',()=>{
 assert.equal(api.backgroundChoices(candidate,[{...context,mondoId:relation.mondoId,publishedInheritance:['AR']}]).length,2);
 assert.equal(api.backgroundChoices(candidate,[{...context,diseaseLabels:['Disease A']}]).length,2);
});
check('other gene and missing identity are rejected',()=>{
 assert.equal(api.backgroundChoices(candidate,[{...context,hgncId:'HGNC:2'}]).length,1);
 assert.equal(api.backgroundChoices(candidate,[{...context,hgncId:''}]).length,1);
});
check('patient state stays scoped to exact unchanged background',()=>{
 const i=api.prepareGuidedInputs(options,[context]); i.v1.backgroundInputs[0].affectedStatus='AFFECTED';
 const again=api.prepareGuidedInputs(options,[context],i).v1.backgroundInputs;
 assert.equal(again.filter(x=>x.affectedStatus==='AFFECTED').length,1);
 const stale=api.prepareGuidedInputs(options,[context],{v1:{...i.v1,recommendationId:'old'}}).v1;
 assert.ok(stale.backgroundInputs.every(x=>x.affectedStatus==='UNKNOWN'));
});
check('removed annotation is never carried forward',()=>{
 const i=api.prepareGuidedInputs(options,[]);i.v1.backgroundInputs[0].annotationId='removed';
 assert.equal(api.prepareGuidedInputs(options,[],i).v1.backgroundInputs[0].annotationId,'');
});
check('unknown background and missing inheritance remain explicit gaps',()=>{
 const o={...options,candidates:[{...candidate,recommendation:{...candidate.recommendation,relationships:[]}}]};
 assert.equal(api.backgroundRows(o,[],api.prepareGuidedInputs(o,[]))[0].status,'UNKNOWN');
 const ctx=[{...context,publishedInheritance:[]}];
 assert.equal(api.backgroundChoices(o.candidates[0],ctx)[0].moi,'');
 assert.ok(api.backgroundRows(o,ctx,api.prepareGuidedInputs(o,ctx))[0].issues.some(x=>x.includes('遗传方式')));
});
check('compatible allele transcript is reused across independent literature backgrounds',()=>{
 const annotation={annotationId:'a1',annotationIndex:0,identityCompatible:true,versionedTranscriptId:'NM_000001.2'};
 const c={...candidate,transcriptAnnotations:[{transcriptAccession:'NM_000001.2',geneSymbol:'GENE',source:'VEP',version:'test',status:'COMPLETED'}], recommendation:{...candidate.recommendation,annotations:[annotation],resourceCompatibleTranscript:annotation}};
 const o={...options,candidates:[c]}, i=api.prepareGuidedInputs(o,[context]);
 assert.equal(i.v1.backgroundInputs.find(x=>x.stableRelationshipKey==='r1').annotationId,'a1');
 assert.equal(i.v1.backgroundInputs.find(x=>x.literatureContext).annotationId,'a1');
});
const props={topN:'1',maximumN:19,options,inputs:{},disabled:false,busy:false,notes:[],dataClass:'SYNTHETIC',confirmed:false};
check('preparation disclosure counts all records and only lists outstanding issues when present', () => {
 const notes = [
  { label: 'PP1 · GENE', status: '待核查', detail: '资料提取超时', attention: true, count: 2 },
  { label: 'PS2 / PM6', status: '资料已返回', detail: '', attention: false, count: 1 },
 ];
 const html = renderToStaticMarkup(React.createElement(api.Preparation, { ...props, notes }));
 assert.match(html, /<details[^>]*acmg-material-preparation[^>]*><summary>资料准备/);
 assert.match(html, /3 条记录.*2 条待关注/);
 assert.match(html, /PP1 · GENE（2 条）.*待核查 · 资料提取超时/);
 assert.doesNotMatch(html, /资料已返回/);
});
check('initial page only prepares background',()=>{
 const html=renderToStaticMarkup(React.createElement(api.Preparation,props));
 assert.ok(html.includes('准备身份与背景'));assert.ok(!html.includes('选择证据并分析'));
});
check('prepared page shows every background, provenance and separate optional patient states',()=>{
 const html=renderToStaticMarkup(React.createElement(api.Preparation,{...props,inputs:api.prepareGuidedInputs(options,[context]),snapshot:{literature:{availability:'AVAILABLE',contexts:[context]}}}));
 assert.ok(html.includes('Disease A'));assert.ok(html.includes('Disease B'));assert.ok(html.includes('后续将分别分析'));
 assert.ok(html.includes('本地疾病关系目录'));assert.ok(html.includes('当前背景的患病状态'));assert.ok(!html.includes('待选择分析背景'));
 assert.match(html,/<button[^>]*disabled[^>]*>选择证据并分析<\/button>/);
});
check('changing Top N invalidates the prepared page',()=>{
 const html=renderToStaticMarkup(React.createElement(api.Preparation,{...props,topN:'2',snapshot:{literature:{contexts:[]}}}));
 assert.ok(html.includes('准备身份与背景'));assert.ok(!html.includes('查看背景来源'));
});
check('expert inheritance provenance is visible without adopting variant classifications',()=>{
 const expert={...context,sources:[{kind:'EXACT_PUBLISHED_EXPERT_CONTEXT',matches:[{mode:'AD',source:{
  sourceUrl:'https://erepo.genome.network/evrepo/api/summary/classifications',panel:'TEST VCEP'}}]}]};
 const html=renderToStaticMarkup(React.createElement(api.Preparation,{...props,inputs:api.prepareGuidedInputs(options,[expert]),snapshot:{literature:{availability:'AVAILABLE',contexts:[expert]}}}));
 assert.ok(html.includes('遗传方式来源：常染色体显性'));assert.ok(html.includes('TEST VCEP'));
 assert.ok(html.includes('https://erepo.genome.network/evrepo/api/summary/classifications'));
});
if(process.argv[2]) {
 const snapshot=JSON.parse(require('fs').readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,''));
 const actual=snapshot.options, contexts=snapshot.literature.contexts;
 const inputs=api.prepareGuidedInputs(actual,contexts);
 const declaration=api.buildGuidedDeclaration(actual,actual.analysisId,inputs,contexts,'SYNTHETIC');
 check('saved 19-variant snapshot forms a complete multi-context request without API calls',()=>{
  assert.equal(declaration.topN,19);assert.equal(declaration.recommendations.length,19);
  const expected=api.backgroundRows(actual,contexts,inputs).flatMap(r=>r.backgrounds.filter(b=>b.choice.moi));
  assert.equal(declaration.hypotheses.length,expected.length);
  assert.equal(new Set(declaration.hypotheses.map(x=>x.hypothesis.hypothesisId)).size,expected.length);
  assert.ok(declaration.hypotheses.every(x=>x.affectedStatus==='UNKNOWN'));
  assert.equal(declaration.recommendations.flatMap(r=>r.backgrounds).length,expected.length);
 });
 check('input ordering does not change context identity',()=>{
  const reversed=api.buildGuidedDeclaration(actual,actual.analysisId,inputs,[...contexts].reverse(),'SYNTHETIC');
  assert.deepEqual(reversed,declaration);
 });
 if(process.argv[3])require('fs').writeFileSync(process.argv[3],JSON.stringify(declaration,null,2));
 console.log(JSON.stringify({uniqueVariants:19,backgrounds:declaration.hypotheses.length,allPatientStates:'UNKNOWN',scientificCalls:0}));
}
console.log(checks+' guided background checks passed. No API, model, database or scientific evaluation calls.');
