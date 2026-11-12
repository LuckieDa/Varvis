// Offline projection parity and read-cache checks; never invokes providers or writes a business database.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {buildSync} from 'esbuild';
const [archive, projections, output] = process.argv.slice(2);
if (!archive || !projections || !output) throw Error('Supply frozen result, Java projection directory, isolated output');
const root=fileURLToPath(new URL('../',import.meta.url)), out=path.resolve(output);
if(out.startsWith(root)) throw Error('Use isolated output outside frontend');
fs.mkdirSync(out,{recursive:true});
const bundle=path.join(out,'read-checks.cjs');
buildSync({stdin:{contents:`export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';
export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultView';
export {evidenceSummary, validateSavedRun} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel';
export const render=run=>renderToStaticMarkup(<SavedResult run={run} criteria={run.enabledCriteria}/>);`,resolveDir:root,loader:'jsx'},
bundle:true,platform:'node',format:'cjs',outfile:bundle,define:{'process.env.NODE_ENV':'"production"'},loader:{'.less':'empty'},logLevel:'warning'});
const api=createRequire(import.meta.url)(bundle), results=[];
const test=async(name,fn)=>{try{await fn();results.push({name,passed:true});}catch(e){results.push({name,passed:false,error:e.stack});}};
const full=JSON.parse(fs.readFileSync(archive)), index=JSON.parse(fs.readFileSync(path.join(projections,'index.json')));
const before=JSON.stringify(full), files=fs.readdirSync(projections).filter(f=>/^background-\d+-\d+\.json$/.test(f));
let compared=0;
await test('all archived backgrounds preserve every displayed status strength reason and full detail entry',()=>{
    for(const file of files) {
        const view=JSON.parse(fs.readFileSync(path.join(projections,file)));
        api.verifyBackgroundView(view,index,view.candidateId,view.backgroundIndex);
        api.validateSavedRun(view,full.analysisId);
        const old=full.candidates.find(c=>c.candidateId===view.candidateId), selected=view.candidates[0];
        assert.deepEqual(selected.attempts[view.backgroundIndex],old.attempts[view.backgroundIndex]);
        const expected=api.backgroundEvidenceCards(old,full.enabledCriteria,full,view.backgroundIndex);
        const actual=api.backgroundEvidenceCards(selected,view.enabledCriteria,view,view.backgroundIndex);
        assert.equal(actual.length,18);
        assert.deepEqual(actual,expected,`changed detail in ${file}`);
        compared+=actual.length;
    }
    assert.equal(compared,486); assert.equal(JSON.stringify(full),before);
});
const first=JSON.parse(fs.readFileSync(path.join(projections,files[0])));
await test('repeated same-background requests share one fetch, cache hits never load again',async()=>{
    let calls=0;const reader=api.createBackgroundReader(async()=>{calls++;return first;});
    const values=await Promise.all([reader(index,first.candidateId,first.backgroundIndex),reader(index,first.candidateId,first.backgroundIndex)]);
    assert.equal(calls,1);assert.equal(values[0],values[1]);
    await reader(index,first.candidateId,first.backgroundIndex);assert.equal(calls,1);
});
await test('failure is retryable, mismatched identities are never cached or shown',async()=>{
    let calls=0;const reader=api.createBackgroundReader(async()=>{if(++calls===1)throw Error('temporary failure');return first;});
    await assert.rejects(reader(index,first.candidateId,first.backgroundIndex));
    await reader(index,first.candidateId,first.backgroundIndex);assert.equal(calls,2);
    for(const patch of [{runId:'wrong'},{analysisId:'wrong'},{sourceViewSha256:'wrong'},{backgroundIndex:999},{candidateId:'wrong'}])
        assert.throws(()=>api.verifyBackgroundView({...first,...patch},index,first.candidateId,first.backgroundIndex));
});
await test('oversized cache entries remain readable without unbounded retention',async()=>{
    let calls=0;const reader=api.createBackgroundReader(async()=>{calls++;return first;},1);
    await reader(index,first.candidateId,first.backgroundIndex);await reader(index,first.candidateId,first.backgroundIndex);
    assert.equal(calls,2);
});
await test('complete export accepts full original only and never exports navigation as a result',()=>{
    assert.equal(api.verifyCompleteExport(full,index),full);
    assert.throws(()=>api.verifyCompleteExport(index,index));
    assert.throws(()=>api.verifyCompleteExport({...full,runId:'other'},index));
});
await test('index renders navigation with explicit loading, no fabricated zero evidence counts',()=>{
    const html=api.render(index);fs.writeFileSync(path.join(out,'index-render.html'),html);
    assert.ok(html.includes('正在读取并核验当前背景'));
    assert.ok(html.includes('下载本次完整结果 JSON'));
    assert.ok(!html.includes('data-testid="evidence-result-row"'));
    assert.ok(!html.includes('data-testid="status-filter-MET"'));
});
await test('old complete saved results still render original evidence and export control',()=>{
    const html=api.render(full);
    assert.equal((html.match(/data-testid="evidence-result-row"/g)||[]).length,18);
    assert.ok(html.includes('下载本次原始结果 JSON'));
});
fs.writeFileSync(path.join(out,'results.json'),JSON.stringify({compared,results},null,2));
console.log(JSON.stringify({compared,passed:results.filter(r=>r.passed).length,failed:results.filter(r=>!r.passed)},null,2));
if(results.some(r=>!r.passed))process.exitCode=1;
