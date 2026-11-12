// Actual interactive component, controlled transport. No backend or scientific execution.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';
const [destination, fixtures, oldFile]=process.argv.slice(2);
if(!destination||!fixtures||!oldFile)throw Error('Provide isolated output, fourteen fixtures, old saved JSON');
const project=fileURLToPath(new URL('../',import.meta.url)),output=path.resolve(destination);
if(output.startsWith(project))throw Error('Use isolated output');fs.mkdirSync(output);
const f={options:JSON.parse(fs.readFileSync(path.join(fixtures,'options.json'))),saved:JSON.parse(fs.readFileSync(path.join(fixtures,'saved.json'))),old:JSON.parse(fs.readFileSync(oldFile))};
const service=path.join(output,'service.js');
fs.writeFileSync(service,`export const api={};export const fetchAcmgInputOptions=(...a)=>api.options(...a);export const fetchAcmgBusinessRuns=(...a)=>api.list(...a);export const fetchAcmgBusinessRun=(...a)=>api.read(...a);export const submitAcmgBusinessRun=(...a)=>api.submit(...a);export const functionalReviewRequest=()=>{throw Error('FORBIDDEN');};`);
const panel=path.join(project,'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const entry=`import React from 'react';import {createRoot} from 'react-dom/client';import Panel,{SavedResult} from ${JSON.stringify(panel)};import {api} from ${JSON.stringify(service)};(${checks.toString()})(React,createRoot,Panel,SavedResult,api,${JSON.stringify(f)});`;
await build({stdin:{contents:entry,resolveDir:project,loader:'jsx'},bundle:true,platform:'browser',format:'iife',outfile:path.join(output,'checks.js'),loader:{'.js':'jsx'},define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning',plugins:[{name:'controlled-service',setup(b){b.onResolve({filter:/services\/variantAnalysis$/},()=>({path:service}));}}]});
const server=http.createServer(async(req,res)=>{
 fs.appendFileSync(path.join(output,'http.jsonl'),JSON.stringify({method:req.method,url:req.url,time:new Date().toISOString()})+'\n');
 if(req.method==='POST'&&req.url==='/test-results'){let chunks=[],size=0;for await(const c of req){size+=c.length;if(size>2000000){res.writeHead(413).end();return;}chunks.push(c);}fs.writeFileSync(path.join(output,'component-results.json'),Buffer.concat(chunks));res.writeHead(200).end('saved');return;}
 if(req.method!=='GET'){res.writeHead(405).end();return;}
 if(req.url==='/checks.js'){res.writeHead(200,{'Content-Type':'text/javascript;charset=utf-8'}).end(fs.readFileSync(path.join(output,'checks.js')));return;}
 if(req.url!=='/'){res.writeHead(404).end();return;}
 res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'}).end('<!doctype html><meta charset="utf-8"><title>十四条入口组件核验 TEST_ONLY</title><h1>十四条入口组件核验 TEST_ONLY</h1><div id="root"></div><script src="/checks.js"></script>');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
fs.writeFileSync(path.join(output,'server.json'),JSON.stringify({pid:process.pid,port:server.address().port,businessPost:0,scientificExecutions:0}));console.log(`http://127.0.0.1:${server.address().port}/`);

function checks(React,createRoot,Panel,SavedResult,api,f){
 const trigger=document.createElement('button'),result=document.createElement('pre'),host=document.createElement('div');
 trigger.textContent='运行十四条入口组件核验';document.getElementById('root').append(trigger,result,host);
 const clone=v=>structuredClone(v),wait=ms=>new Promise(r=>setTimeout(r,ms)),ensure=(v,why)=>{if(!v)throw Error(why);};
 const report={status:'RUNNING',attribution:'ACTUAL_REACT_CONTROLLED_TRANSPORT_SYNTHETIC_ONLY',cases:[],businessPost:0,scientificExecutions:0,actualDownload:'NOT_RUN'};
 let root,calls,config;
 const button=t=>[...host.querySelectorAll('button')].find(b=>b.textContent===t),confirm=()=>host.querySelector('input[type="checkbox"]'),submit=()=>button('确认后执行并保存');
 const settle=async()=>{await wait(100);for(let i=0;i<150;i++){if(!host.querySelector('[role="progressbar"]')){await wait(40);return;}await wait(20);}throw Error('settle timeout');};
 const reset=async(settings={})=>{if(root)root.unmount();host.replaceChildren();config=settings;calls=[];
  api.options=async(id,n)=>{calls.push('OPTIONS');const v=config.options?config.options():clone(f.options);v.requestedN=n;v.candidates=v.candidates.slice(0,n);v.candidateIds=v.candidateIds.slice(0,n);v.selectedCount=v.candidates.length;return v;};
  api.list=async()=>{calls.push('LIST');return config.rows||[];};api.read=async()=>{calls.push('READ');return config.saved||clone(f.saved);};
  api.submit=async(id,body)=>{calls.push({method:'MOCK_POST',id,body});return config.submit?config.submit(id,body):clone(f.saved);};
  root=createRoot(host);root.render(React.createElement(Panel,{analysisId:f.options.analysisId}));await settle();};
 const noPost=()=>ensure(!calls.some(c=>c?.method==='MOCK_POST'),'unexpected mock submission');
 const record=async(name,fn)=>{await fn();report.cases.push({name,status:'PASS',calls:clone(calls)});result.textContent=`${report.cases.length}项通过`;};
 trigger.onclick=async()=>{trigger.disabled=true;try{
  await record('fourteen explicit confirmation and duplicate-click lock',async()=>{let resolve;const pending=new Promise(r=>{resolve=r;});await reset({submit:()=>pending});ensure(host.textContent.includes('PM1 / PP2 / BP1'),'new labels');ensure(host.textContent.includes('尚未声明PP2胚系范围')||host.textContent.includes('未声明PP2胚系范围'),'unconfirmed germline scope');ensure(submit()?.disabled,'must confirm');confirm().click();await wait(30);ensure(!submit().disabled,'confirmation enables');submit().click();submit().click();await wait(30);ensure(calls.filter(c=>c?.method==='MOCK_POST').length===1,'one mock POST');ensure(calls.find(c=>c?.method==='MOCK_POST').body.declarationVersion==='ordinary-unified-local-fourteen-v1','new declaration');resolve(clone(f.saved));await settle();ensure(host.textContent.includes('同一次运行包含十四条证据'),'saved count');ensure(!confirm().checked&&submit().disabled,'saved revokes');});
  await record('Top-N modification revokes confirmation',async()=>{await reset();confirm().click();await wait(30);const input=host.querySelector('input:not([type="checkbox"]):not([aria-hidden="true"])');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'3');input.dispatchEvent(new Event('input',{bubbles:true}));await wait(50);ensure(!confirm().checked&&submit().disabled,'reconfirm Top-N');noPost();});
  await record('missing new binding revokes submission',async()=>{await reset();confirm().click();await wait(30);config.options=()=>{const v=clone(f.options);delete v.executionProfile.moduleBindings.pm1;return v;};button('读取正式候选').click();await settle();ensure(!submit()||submit().disabled,'incomplete scope');noPost();});
  await record('PP2 scope mismatch cannot silently authorize',async()=>{await reset();confirm().click();await wait(30);config.options=()=>{const v=clone(f.options);v.executionProfile.moduleBindings.pp2.runtimeBinding.pp2GermlineDeclared=true;return v;};button('读取正式候选').click();await settle();ensure(!submit()||submit().disabled,'scope mismatch');noPost();});
  await record('old eleven history does not authorize new retry',async()=>{const old=clone(f.old);old.analysisId=f.options.analysisId;await reset({rows:[old],saved:old});ensure(host.textContent.includes('同一次运行包含十一条证据'),'old preserved');ensure(!confirm()?.checked,'history not approval');const retry=button('修正后显式重试此运行');ensure(!retry||retry.disabled,'cross profile no retry');noPost();});
  await record('same fourteen retry preserves exact retryOf',async()=>{await reset({rows:[clone(f.saved)]});const retry=button('修正后显式重试此运行');ensure(retry?.disabled,'retry confirmation required');confirm().click();await wait(30);retry.click();await settle();ensure(calls.filter(c=>c?.method==='MOCK_POST').length===1,'one retry');ensure(calls.find(c=>c?.method==='MOCK_POST').body.retryOf===f.saved.runId,'retry identity');});
  await record('incomplete positive result loses conclusion and keeps full export',async()=>{root.unmount();host.replaceChildren();root=createRoot(host);calls=[];const bad=clone(f.saved);const c=bad.candidates[0].attempts[0].localBatch.criteria.find(c=>c.criterionId==='ACMG_AMP_2015:PM1');c.status='MET';c.appliedStrength='MODERATE';c.reasonCode='PM1_PUBLISHED_CRITICAL_LOCATION_MATCH';delete c.details.pm1Facts;root.render(React.createElement(SavedResult,{run:bad,criteria:bad.enabledCriteria}));await settle();ensure(host.textContent.includes('保存的本地证据身份或部分结果不完整'),'corrupt warning');ensure(button('下载本次原始结果 JSON'),'full export');noPost();});
  report.status='PASS';
 }catch(e){report.status='FAIL';report.error=e.stack;report.failedCalls=calls;}result.textContent=JSON.stringify(report,null,2);await fetch('/test-results',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(report)});};
}
