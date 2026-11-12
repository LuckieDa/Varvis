import frontendModuleUrl from './frontend-module-url.cjs';
// Actual saved transports + explicitly synthetic corruptions; no scientific execution or model call.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {buildSync} from 'esbuild';
const [destination, realDirectory, syntheticDirectory, oldFile] = process.argv.slice(2);
if (!oldFile) throw Error('Supply isolated output, public research chain, new synthetic business artifacts and old fourteen JSON');
const project=fileURLToPath(new URL('../',import.meta.url)), output=path.resolve(destination);
if(output.startsWith(project)||fs.existsSync(output))throw Error('Use a new isolated output directory');fs.mkdirSync(output,{recursive:true});
const api=await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(fs.readFileSync(path.join(project,'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js'))).toString('base64')}`));
const read=p=>JSON.parse(fs.readFileSync(p)), clone=structuredClone;
const options=read(path.join(realDirectory,'prepare-options.json')), research=read(path.join(realDirectory,'result.json')), old=read(oldFile);
const samples=Object.fromEntries(['confirmed','assumed','missing','literature'].map(k=>[k,read(path.join(syntheticDirectory,k,'response.json'))]));
if(fs.existsSync(path.join(syntheticDirectory,'auto-supply/response.json')))samples['auto-supply']=read(path.join(syntheticDirectory,'auto-supply/response.json'));
const original=JSON.stringify({options,research,old,samples}), results=[];
const bundle=path.join(output,'component.cjs');
buildSync({stdin:{contents:`import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const render=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);
export const download=run=>{let click;const walk=e=>{if(!React.isValidElement(e))return;if(e.props.children==='下载本次原始结果 JSON')click=e.props.onClick;React.Children.forEach(e.props.children,walk);};walk(SavedResult({run,criteria:run.enabledCriteria}));if(!click)throw Error('Missing download');click();};`,resolveDir:project,loader:'jsx'},bundle:true,platform:'node',format:'cjs',outfile:bundle,define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'});
const {render,download}=createRequire(import.meta.url)(bundle);
function test(name,fn){try{fn();results.push({name,status:'PASS'});}catch(e){results.push({name,status:'FAIL',message:e.stack});}console.log(results.at(-1).status,name);}
const first=(r,code)=>r.candidates[0].attempts[0].localBatch.criteria.find(c=>c.criterionId===`ACMG_AMP_2015:${code}`);
const issue=r=>api.localBatchAttemptIssue(r.candidates[0],r.candidates[0].attempts[0],r.enabledCriteria,r.executionProfile);
test('actual public research preparation and legacy summary readable',()=>{assert.equal(api.localBatchScopeIssue(options),null);assert.ok(api.localBatchSavedRun(research));assert.equal(issue(research),null);const html=render(research);assert.ok(html.includes('历史说明'));assert.ok(html.includes('已经计入 PS2'));fs.writeFileSync(path.join(output,'public-research.html'),html);});
for(const [name,r] of Object.entries(samples))test(`actual new synthetic ${name} output readable and fully rendered`,()=>{assert.equal(api.localBatchScopeIssue(r),null);for(const c of r.candidates)for(const a of c.attempts)assert.equal(api.localBatchAttemptIssue(c,a,r.enabledCriteria,r.executionProfile),null);const html=render(r);assert.ok(html.includes('大模型未配置'));assert.ok(!html.includes('受影响背景不展示科学结论'));fs.writeFileSync(path.join(output,`${name}.html`),html);});
test('PM6 and missing paternal test explanations differ',()=>{assert.equal(first(samples.assumed,'PM6').status,'MET');assert.ok(render(samples.assumed).includes('亲缘关系未完全证实'));assert.ok(render(samples.missing).includes('缺少父亲对这个具体变异的合格检测结果'));});
if(samples['auto-supply']) {
    test('automatic source supply is bound and readable',()=>{const r=samples['auto-supply'];assert.equal(first(r,'PS2').status,'MET');assert.equal(r.executionProfile.moduleBindings.deNovo.sourceSupply.publicNetworkEnabled,false);assert.equal(issue(r),null);});
    test('unbound source supplier or private issue is refused',()=>{const r=clone(samples['auto-supply']);r.executionProfile.moduleBindings.deNovo.sourceSupply.modelCalls=1;assert.ok(api.localBatchScopeIssue(r));const damaged=clone(samples['auto-supply']);for(const c of ['PS2','PM6'])first(damaged,c).details.deNovoFacts.sourceSupplyIssues=['C:/private/source'];assert.ok(issue(damaged));});
    test('missing citation is not described as no cases',()=>{const r=clone(samples['auto-supply']);for(const c of ['PS2','PM6'])first(r,c).details.deNovoFacts.sourceSupplyIssues=['DENOVO_PUBLIC_NO_EXACT_CITATION_SOURCE'];assert.equal(issue(r),null);assert.ok(render(r).includes('不表示没有相关文献'));});
}
test('new declaration explicitly retains affected status without transcript',()=>{const inputs=api.prepareSimplifiedInputs(options);const id=options.candidates[0].candidateId;assert.equal(inputs[id].affectedStatus,'UNKNOWN');inputs[id].affectedStatus='AFFECTED';inputs[id].annotationId='';const s=api.candidateInputStatus(options.candidates[0],inputs[id],options);assert.equal(s.ready,false);assert.equal(s.deNovoReady,true);const request=api.buildSimplifiedCaseDeclaration(options,options.analysisId,inputs,undefined,'SYNTHETIC');assert.equal(request.hypotheses[0].affectedStatus,'AFFECTED');assert.equal(request.hypotheses[0].transcript,null);assert.equal(request.recommendations[0].annotationId,null);fs.writeFileSync(path.join(output,'no-transcript-declaration.json'),JSON.stringify(request,null,2));});
test('unknown status is not silently replaced, invalid status rejected',()=>{const inputs=api.prepareSimplifiedInputs(options),id=options.candidates[0].candidateId;inputs[id].annotationId='';assert.equal(api.buildSimplifiedCaseDeclaration(options,options.analysisId,inputs).hypotheses[0].affectedStatus,'UNKNOWN');inputs[id].affectedStatus='GUESS';assert.throws(()=>api.buildSimplifiedCaseDeclaration(options,options.analysisId,inputs));});
test('old fourteen saved result and scope preserved',()=>{assert.ok(api.localBatchSavedRun(old));for(const c of old.candidates)for(const a of c.attempts)assert.equal(api.localBatchAttemptIssue(c,a,old.enabledCriteria,old.executionProfile),null);assert.ok(render(old).includes('同一次运行包含十四条证据'));assert.equal(api.localBatchRetryEligible(research,research.analysisId,old),false);});
test('new sixteen transport projection retains all old module guards',()=>{const r=clone(old),b=clone(samples.confirmed.executionProfile.moduleBindings.deNovo);r.executionProfile.profileId='acmg-unified-local-denovo-v1';r.executionProfile.moduleBindings.deNovo=b;for(const m of Object.values(r.executionProfile.moduleBindings))if(m.runtimeBinding)m.runtimeBinding.deNovo=clone(b);r.enabledCriteria.push('ACMG_AMP_2015:PS2','ACMG_AMP_2015:PM6');r.executionProfile.enabledCriteria=clone(r.enabledCriteria);assert.equal(api.localBatchScopeIssue(r),null);assert.equal(api.currentBusinessCriteria(r).length,16);delete r.executionProfile.moduleBindings.bp1;assert.ok(api.localBatchScopeIssue(r));});
for(const [name,mutate] of [
 ['missing binding',r=>delete r.executionProfile.moduleBindings.deNovo],['different material pin',r=>r.executionProfile.moduleBindings.deNovo.manifestSha256='a'.repeat(64)],
 ['unvalidated model claim',r=>r.executionProfile.moduleBindings.deNovo.modelCalls=1],['missing criterion',r=>r.enabledCriteria.pop()],
 ['future profile',r=>r.executionProfile.profileVersion='9.0.0'],['clinical approval',r=>r.executionProfile.clinicalValidationCompleted=true],
 ['bare new identity',r=>{r.executionProfile={profileId:r.executionProfile.profileId};delete r.requiredDeclaration;}],
 ['binding under old identity',r=>r.executionProfile.profileId='acmg-local-computational-batch1-v1']])
test(`scope rejects ${name}`,()=>{const r=clone(options);mutate(r);assert.ok(api.localBatchScopeIssue(r));assert.deepEqual(api.currentBusinessCriteria(r),[]);});
const corruptions=[
 ['double adoption',r=>Object.assign(first(r,'PM6'),{status:'MET',appliedStrength:'SUPPORTING'})],
 ['strength mismatch',r=>first(r,'PS2').appliedStrength='VERY_STRONG'],
 ['pair scores differ',r=>first(r,'PS2').details.deNovoFacts.effectiveQuarterPoints++],
 ['fractional score',r=>{for(const c of ['PS2','PM6'])first(r,c).details.deNovoFacts.effectiveQuarterPoints=2.5;}],
 ['event contribution changed',r=>{for(const c of ['PS2','PM6'])first(r,c).details.deNovoFacts.events[0].rawQuarterPoints=0;}],
 ['unverified search claim',r=>{for(const c of ['PS2','PM6'])first(r,c).details.deNovoFacts.completeLiteratureSearch=true;}],
 ['borrowed de novo facts',r=>first(r,'PM2').details.deNovoFacts=clone(first(r,'PS2').details.deNovoFacts)],
 ['private location exposed',r=>{for(const c of ['PS2','PM6'])first(r,c).details.deNovoFacts.events[0].sourceRefs.push('C:/private/patient.json');}],
 ['missing new details',r=>{for(const c of ['PS2','PM6'])delete first(r,c).details.deNovoFacts.detailVersion;}],
 ['bad cap',r=>{for(const c of ['PS2','PM6'])first(r,c).details.deNovoFacts.highHeterogeneityEffectiveQuarterPoints=10;}],
];
for(const [name,mutate] of corruptions)test(`reject damaged ${name} but retain export`,()=>{const r=clone(samples.confirmed);mutate(r);assert.ok(issue(r));const html=render(r);assert.ok(html.includes('受影响背景不展示科学结论'));assert.ok(html.includes('下载本次原始结果 JSON'));});
test('export actual full JSON including unknown fields and rejected records',()=>{const previous={Blob:globalThis.Blob,document:globalThis.document,timer:globalThis.setTimeout,create:URL.createObjectURL,revoke:URL.revokeObjectURL};let payload='',clicked=false;const anchor={click(){clicked=true;}};try{globalThis.Blob=class{constructor(parts){payload=parts.join('');}};globalThis.document={createElement:()=>anchor};globalThis.setTimeout=fn=>fn();URL.createObjectURL=()=> 'blob:denovo';URL.revokeObjectURL=()=>undefined;const damaged=clone(samples.confirmed);corruptions[0][1](damaged);for(const raw of [research,old,...Object.values(samples),damaged]){const r=clone(raw);r.unknownFuture={values:['中文',null,false]};clicked=false;download(r);assert.ok(clicked);assert.equal(anchor.download,`acmg-${r.runId}.json`);assert.deepEqual(JSON.parse(payload),r);}}finally{globalThis.Blob=previous.Blob;globalThis.document=previous.document;globalThis.setTimeout=previous.timer;URL.createObjectURL=previous.create;URL.revokeObjectURL=previous.revoke;}});
assert.equal(JSON.stringify({options,research,old,samples}),original);
fs.writeFileSync(path.join(output,'validation.json'),JSON.stringify({groups:results.length,results,attribution:'Actual saved public research and synthetic business records; React SSR/export handler; no browser or model/scientific execution'},null,2));
if(results.some(x=>x.status!=='PASS'))process.exitCode=1;
