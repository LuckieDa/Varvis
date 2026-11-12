import fs from 'node:fs';
import assert from 'node:assert/strict';
const load = async name => import(`data:text/javascript;base64,${fs.readFileSync(new URL(`../src/pages/varvis/ChromosomeVisualization/utils/${name}.js`,import.meta.url)).toString('base64')}`);
const {vepJobExecution,vepStatusExplanation,LOCAL_POPULATION_SCOPE}=await load('analysisReadiness');
let count=0;
const test=(name,run)=>{run();count++;console.log(`PASS ${name}`);};
const old='VEP transcript annotation COMPLETED: selected=2, processed=2, success=2, failed=0, cacheHit=1, elapsedMs=21';
const current='VEP transcript annotation PARTIAL: selected=5, processed=4, success=3, failed=1, cacheHit=2, cacheMiss=2, batches=1, timeouts=1, providerErrors=0, unavailable=2, annotatedAvailable=3, elapsedMs=132, warnings=1';
test('historical terminal job without events is not configuration PENDING',()=>{
    assert.deepEqual(vepJobExecution([{message:'Variant analysis is ready'}],true),{status:'NOT_RECORDED',summary:null});
    assert.match(vepStatusExplanation('NOT_RECORDED'),/不代表未做注释/);
});
test('no job means no execution',()=>assert.equal(vepJobExecution([],false).status,'NOT_REQUESTED'));
test('old summary retains exact counts',()=>assert.deepEqual(vepJobExecution([{message:old}],true).summary,
    {status:'COMPLETED',selectedCount:2,processedCount:2,successCount:2,failedCount:0,cacheHitCount:1,elapsedMillis:21,warningCount:0,createdAt:undefined}));
test('extended production summary no longer loses elapsed/counts',()=>{
    const v=vepJobExecution([{message:current}],true);assert.equal(v.status,'PARTIAL');assert.equal(v.summary.successCount,3);assert.equal(v.summary.elapsedMillis,132);assert.equal(v.summary.warningCount,1);
});
test('later running event supersedes completed summary',()=>assert.deepEqual(vepJobExecution([{message:old},{message:'VEP transcript annotation RUNNING: selected=5'}],true),{status:'RUNNING',summary:null}));
test('failure after old success does not show old success counts',()=>assert.deepEqual(vepJobExecution([{message:old},{message:'VEP transcript annotation FAILED: scope=ACMG_SOURCE_PUBLICATION'}],true),{status:'FAILED',summary:null}));
test('missing fields do not fabricate counters',()=>assert.equal(vepJobExecution([{message:old.replace(', processed=2','')}],true).summary,null));
test('local gnomAD criteria are distinguished from the upstream provider',()=>{assert.match(LOCAL_POPULATION_SCOPE,/BA1、BS1、PM2 使用独立的 ACMG 本地人口数据/);});
console.log(`${count} checks passed`);
