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
const f={options:JSON.parse(fs.readFileSync(path.join(fixtures,'prepare-options.json'))),saved:JSON.parse(fs.readFileSync(path.join(fixtures,'result.json'))),old:JSON.parse(fs.readFileSync(oldFile))};
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
 res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'}).end('<!doctype html><meta charset="utf-8"><title>PS2/PM6共同资料入口组件核验 TEST_ONLY</title><h1>PS2/PM6共同资料入口组件核验 TEST_ONLY</h1><div id="root"></div><script src="/checks.js"></script>');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
fs.writeFileSync(path.join(output,'server.json'),JSON.stringify({pid:process.pid,port:server.address().port,businessPost:0,scientificExecutions:0}));console.log(`http://127.0.0.1:${server.address().port}/`);

function checks(React,createRoot,Panel,SavedResult,api,f){
 const trigger=document.createElement('button'),result=document.createElement('pre'),host=document.createElement('div');
 trigger.textContent='运行 PS2/PM6 组件核验';document.getElementById('root').append(trigger,result,host);
 const clone=structuredClone,wait=ms=>new Promise(r=>setTimeout(r,ms)),ensure=(v,w)=>{if(!v)throw Error(w);};
 const report={status:'RUNNING',attribution:'ACTUAL_REACT_CONTROLLED_TRANSPORT_PUBLIC_RESEARCH_SAVED_RESULT',cases:[],businessPost:0,scientificExecutions:0,modelCalls:0,actualDownload:'NOT_RUN'};
 let root,calls,settings;
 const button=t=>[...host.querySelectorAll('button')].find(b=>b.textContent===t),confirm=()=>host.querySelector('input[type="checkbox"]'),submit=()=>button('确认后执行并保存');
 const settle=async()=>{await wait(100);for(let i=0;i<100;i++){if(!host.querySelector('[role="progressbar"]')){await wait(40);return;}await wait(20);}throw Error('settle timeout');};
 const select=async(label,text)=>{const el=[...host.querySelectorAll('[role="combobox"]')].find(e=>(e.getAttribute('aria-labelledby')||'').split(' ').some(id=>document.getElementById(id)?.textContent===label));ensure(el,`missing select ${label}`);el.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,button:0}));await wait(30);const option=[...document.querySelectorAll('[role="option"]')].find(e=>e.textContent===text);ensure(option,`missing option ${text}`);option.click();await wait(50);};
 const text=async(label,value)=>{const l=[...host.querySelectorAll('label')].find(e=>e.textContent===label),el=l&&document.getElementById(l.htmlFor);ensure(el,`missing field ${label}`);Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(el,value);el.dispatchEvent(new Event('input',{bubbles:true}));await wait(40);};
 const reset=async(config={})=>{if(root)root.unmount();host.replaceChildren();settings=config;calls=[];
 api.options=async(id,n)=>{calls.push('OPTIONS');const v=clone(f.options);v.requestedN=n;v.candidates[0].recommendation.recommendedAnnotationId='';return settings.options?settings.options(v):v;};
 api.list=async()=>{calls.push('LIST');return settings.rows||[];};api.read=async()=>{calls.push('READ');return settings.saved||clone(f.saved);};
 api.submit=async(id,body)=>{calls.push({method:'MOCK_POST',id,body});return clone(f.saved);};
 root=createRoot(host);root.render(React.createElement(Panel,{analysisId:f.options.analysisId}));await settle();};
 const record=async(name,fn)=>{await fn();report.cases.push({name,status:'PASS',calls:clone(calls)});result.textContent=`${report.cases.length} 项通过`;};
 trigger.onclick=async()=>{trigger.disabled=true;try{
 await record('explicit affected state without transcript uses one shared submission',async()=>{await reset();ensure(submit()?.disabled,'unconfirmed disabled');host.querySelector('.MuiAccordionSummary-root').click();await wait(30);await select('当前病例对所选疾病的患病状态','已知患病');confirm().click();await wait(30);ensure(!submit().disabled,'confirmed enabled');submit().click();await settle();const posts=calls.filter(c=>c?.method==='MOCK_POST');ensure(posts.length===1,'one mock POST');ensure(posts[0].body.hypotheses[0].affectedStatus==='AFFECTED','explicit affected');ensure(posts[0].body.hypotheses[0].transcript===null,'no forced transcript');ensure(host.textContent.includes('已经计入 PS2'),'shared exclusion explained');ensure(!confirm().checked&&submit().disabled,'saved revokes');});
 await record('changing affected state revokes confirmation',async()=>{await reset();host.querySelector('.MuiAccordionSummary-root').click();await wait(30);confirm().click();await wait(30);await select('当前病例对所选疾病的患病状态','已知未患病');ensure(!confirm().checked&&submit().disabled,'changed state revokes');ensure(!calls.some(c=>c?.method==='MOCK_POST'),'no submit');});
 await record('explicit current case binding submits only original identifiers',async()=>{await reset();await select('当前家系材料','绑定已登记的当前家系材料');await text('报告中的病例隐私标识','patient-context-synthetic01');await text('报告中的家系隐私标识','family-context-synthetic01');confirm().click();await wait(30);submit().click();await settle();const posts=calls.filter(c=>c?.method==='MOCK_POST');ensure(posts.length===1,'one mock POST');const p=posts[0].body.patient;ensure(p.patientContextId==='patient-context-synthetic01'&&p.familyContextId==='family-context-synthetic01','exact binding');ensure(p.source.recordLocator==='de-novo/current-case-binding','explicit provenance');ensure(posts[0].body.hypotheses[0].affectedStatus==='UNKNOWN','binding does not invent affected');});
 await record('binding edit revokes and candidate reload clears binding',async()=>{await reset();await select('当前家系材料','绑定已登记的当前家系材料');await text('报告中的病例隐私标识','patient-context-synthetic01');await text('报告中的家系隐私标识','family-context-synthetic01');confirm().click();await wait(30);await text('报告中的家系隐私标识','family-context-synthetic02');ensure(!confirm().checked&&submit().disabled,'binding edit revokes');const reload=[...host.querySelectorAll('button')].find(b=>b.textContent.includes('读取')&&b.textContent.includes('候选'));ensure(reload,'reload exists');reload.click();await settle();ensure(!host.textContent.includes('报告中的病例隐私标识'),'binding reset');ensure(!calls.some(c=>c?.method==='MOCK_POST'),'no submit');});
 await record('partial binding fails before transport',async()=>{await reset();await select('当前家系材料','绑定已登记的当前家系材料');await text('报告中的病例隐私标识','patient-context-synthetic01');confirm().click();await wait(30);submit().click();await settle();ensure(!calls.some(c=>c?.method==='MOCK_POST'),'partial binding blocked');ensure(host.textContent.includes('当前病例材料绑定不完整'),'specific reason');});
 await record('missing material binding cannot authorize',async()=>{await reset({options:v=>{delete v.executionProfile.moduleBindings.deNovo;return v;}});ensure(!submit()||submit().disabled,'invalid binding disabled');ensure(!calls.some(c=>c?.method==='MOCK_POST'),'no submit');});
 await record('old fourteen history remains read only',async()=>{const old=clone(f.old);old.analysisId=f.options.analysisId;await reset({rows:[old],saved:old});ensure(host.textContent.includes('同一次运行包含十四条证据'),'history present');ensure(!confirm()?.checked,'history no authorization');const retry=button('修正后显式重试此运行');ensure(!retry||retry.disabled,'wrong-profile retry disabled');});
 await record('inconsistent dual award removes conclusions but keeps export',async()=>{root.unmount();host.replaceChildren();calls=[];root=createRoot(host);const bad=clone(f.saved);const pm6=bad.candidates[0].attempts[0].localBatch.criteria.find(c=>c.criterionId==='ACMG_AMP_2015:PM6');pm6.status='MET';pm6.appliedStrength='SUPPORTING';root.render(React.createElement(SavedResult,{run:bad,criteria:bad.enabledCriteria}));await settle();ensure(host.textContent.includes('受影响背景不展示科学结论'),'damaged warning');ensure(button('下载本次原始结果 JSON'),'complete export');});
 report.status='PASS';
 }catch(e){report.status='FAIL';report.error=e.stack;report.failedCalls=calls;report.visibleText=host.textContent.slice(0,14000);}result.textContent=JSON.stringify(report,null,2);await fetch('/test-results',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(report)});};
}
