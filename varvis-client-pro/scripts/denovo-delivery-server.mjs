// Loopback-only frozen-result delivery. No business writes, database, model or scientific execution.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';

const [destination, inputManifest] = process.argv.slice(2);
if (!destination || !inputManifest) throw Error('Provide new output directory and pinned delivery manifest');
const project=fileURLToPath(new URL('../',import.meta.url)), output=path.resolve(destination);
if (output.startsWith(project) || fs.existsSync(output)) throw Error('Use a new task output directory');
const manifest=JSON.parse(fs.readFileSync(inputManifest));
if (!Array.isArray(manifest.records) || manifest.records.length<1 || manifest.records.length>8) throw Error('Invalid record count');
const records=new Map(), frozenTasks=new Map(), summary=[];
for (const row of manifest.records) {
    if (!/^[a-z0-9-]{1,40}$/.test(row.key) || records.has(row.key) || typeof row.label!=='string') throw Error('Invalid record identity');
    const raw=fs.readFileSync(row.file), sha=crypto.createHash('sha256').update(raw).digest('hex');
    if (raw.length>10000000 || sha!==row.sha256) throw Error('Source changed');
    const value=JSON.parse(raw);
    if (!value.runId || !Array.isArray(value.enabledCriteria)) throw Error('Not a saved business result');
    records.set(row.key,raw);summary.push({key:row.key,label:row.label,runId:value.runId,sha256:sha,bytes:raw.length});
    const task=value.localBatch?.modules?.deNovo?.sourceTask;
    if(task && value.analysisId) {
        const workflow=value.executionProfile?.profileId==='acmg-local-material-and-computational-v1'?'LOCAL_COMPUTATIONAL_BATCH1':'CURRENT';
        frozenTasks.set(`/variant-analyses/${encodeURIComponent(value.analysisId)}/acmg/runs/${encodeURIComponent(value.runId)}/denovo-source`,
            {workflow,value:{...task,analysisId:value.analysisId,runId:value.runId,savedResultChanged:false,deliverySnapshot:true}});
    }
}
fs.mkdirSync(output);
for (const row of summary) fs.writeFileSync(path.join(output,`expected-export-${row.key}.json`),JSON.stringify(JSON.parse(records.get(row.key)),null,2));
const panel=path.join(project,'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
await build({stdin:{contents:`import React from 'react';import {createRoot} from 'react-dom/client';import {SavedResult} from ${JSON.stringify(panel)};(${page.toString()})(React,createRoot,SavedResult);`,resolveDir:project,loader:'jsx'},
    bundle:true,platform:'browser',format:'iife',outfile:path.join(output,'review.js'),define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'});
const server=http.createServer((req,res)=>{
    const url=new URL(req.url,'http://127.0.0.1');
    fs.appendFileSync(path.join(output,'http.jsonl'),JSON.stringify({method:req.method,path:url.pathname,time:new Date().toISOString()})+'\n');
    res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');
    if(req.method!=='GET'){res.writeHead(405,{'Allow':'GET'}).end('Read-only delivery');return;}
    if(frozenTasks.has(url.pathname)){
        const task=frozenTasks.get(url.pathname);
        if(url.searchParams.get('workflow')!==task.workflow){res.writeHead(404).end();return;}
        res.writeHead(200,{'Content-Type':'application/json;charset=utf-8'}).end(JSON.stringify(task.value));return;
    }
    if(url.pathname==='/records'){res.writeHead(200,{'Content-Type':'application/json;charset=utf-8'}).end(JSON.stringify(summary));return;}
    if(url.pathname.startsWith('/runs/')){
        const raw=records.get(url.pathname.slice(6));
        if(!raw){res.writeHead(404).end();return;}
        res.writeHead(200,{'Content-Type':'application/json;charset=utf-8'}).end(raw);return;
    }
    if(url.pathname==='/review.js'){res.writeHead(200,{'Content-Type':'application/javascript;charset=utf-8'}).end(fs.readFileSync(path.join(output,'review.js')));return;}
    if(url.pathname!=='/'){res.writeHead(404).end();return;}
    res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'}).end('<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>PS2/PM6 资料流程交付</title></head><body style="margin:24px;background:#f6f8fc;font-family:system-ui"><div id="root"></div><script src="/review.js"></script></body></html>');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const info={pid:process.pid,port:server.address().port,url:`http://127.0.0.1:${server.address().port}/`,mode:'READ_ONLY_FROZEN_DELIVERY',businessWrites:0,modelCalls:0,records:summary};
fs.writeFileSync(path.join(output,'server.json'),JSON.stringify(info,null,2));console.log(info.url);

function page(React,createRoot,SavedResult){
    function App(){
        const [list,setList]=React.useState([]),[key,setKey]=React.useState(''),[run,setRun]=React.useState(null),[error,setError]=React.useState('');
        React.useEffect(()=>{fetch('/records').then(r=>r.json()).then(rows=>{setList(rows);setKey(rows[0].key);}).catch(e=>setError(String(e)));},[]);
        React.useEffect(()=>{if(!key)return;let live=true;setRun(null);setError('');fetch('/runs/'+encodeURIComponent(key)).then(r=>{if(!r.ok)throw Error('读取失败');return r.json();}).then(r=>{if(live)setRun(r);}).catch(e=>{if(live)setError(String(e));});return()=>{live=false;};},[key]);
        return React.createElement('main',{style:{maxWidth:1180,margin:'auto'}},
            React.createElement('h1',null,'PS2 / PM6 资料流程交付'),
            React.createElement('p',null,'本页只读展示已经保存的结果。切换记录、展开详情和下载不会重新分析；资料状态刷新也只返回保存时的快照，不连接运行后台。真实大模型尚未配置；公开资料科研验证和合成测试分别标注。'),
            React.createElement('label',{htmlFor:'record'},'选择验证记录：'),
            React.createElement('select',{id:'record',value:key,onChange:e=>setKey(e.target.value),style:{padding:10,margin:'0 0 20px 12px',fontSize:16}},list.map(r=>React.createElement('option',{key:r.key,value:r.key},r.label))),
            error?React.createElement('p',{role:'alert'},error):run?React.createElement(SavedResult,{run,criteria:run.enabledCriteria}):React.createElement('p',{role:'status'},'正在读取保存结果…'));
    }
    createRoot(document.getElementById('root')).render(React.createElement(App));
}
