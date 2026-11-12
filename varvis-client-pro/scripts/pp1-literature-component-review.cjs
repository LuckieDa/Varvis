// Interactive browser harness. All API responses are explicitly synthetic.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {buildSync}=require('esbuild');
const output=path.resolve(process.argv[2]||'');
if(!process.argv[2]||fs.existsSync(output))throw Error('Supply a new private output directory');
fs.mkdirSync(output,{recursive:true});
const project=path.resolve(__dirname,'..');
const app=`import React from 'react';import {createRoot} from 'react-dom/client';import axios from 'axios';
import Panel from './src/pages/varvis/ChromosomeVisualization/components/Pp1LiteraturePanel.jsx';
const declaration={topN:1,hypotheses:[{hypothesis:{candidateId:'candidate-1',hypothesisId:'hypothesis-1',mondoId:'MONDO:0000001'}}]};
createRoot(document.getElementById('root')).render(<Panel analysisId="synthetic" draftIdentity="fixed" candidates={[{candidateId:'candidate-1',geneSymbol:'TEST_GENE'}]} prepareDeclaration={()=>declaration}/>);
setInterval(async()=>{document.getElementById('counts').textContent=JSON.stringify(await fetch('/counts').then(r=>r.json()),null,2);},1000);`;
buildSync({stdin:{contents:app,resolveDir:project,loader:'jsx'},bundle:true,platform:'browser',outfile:path.join(output,'app.js'),
    define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'});
let tasks=[],posts=0,gets=0,cancels=0,polls=0;const requests=[];
const prefix='/variant-analyses/synthetic/acmg/literature/pp1/tasks';
const reply=(res,value)=>res.writeHead(200,{'Content-Type':'application/json;charset=utf-8','Cache-Control':'no-store'}).end(JSON.stringify(value));
const task=id=>({schemaVersion:'pp1-public-literature-task-v1',taskId:id.repeat(64),status:'RUNNING'});
const ready=t=>({...t,status:'READY',result:{schemaVersion:'pp1-discovery-process-v1',status:'EVALUATED',modelAttempts:0,networkRequests:0,
businessPublishable:false,searchPolicy:'LITVAR_ONLY',discoveryState:{resumeAvailable:true},issues:[],papers:[{pmcid:'PMC123',status:'EVALUATED',
stopReason:'EXTERNAL_REQUIREMENT',reading:{readingPlan:{chunks:[{status:'REVIEWED'},{status:'AVAILABLE'}],remainingChunks:1}},
evaluation:{status:'NOT_EVALUATED',strength:null,awardedPP1Points:0,events:[],needs:[{needId:'synthetic-need',requirement:'合成家系记录仍缺疾病适用知识；继续读这篇论文不能代替知识核定。',
blockingEffect:'BLOCKS_SCORING',sourceLocators:[{unitId:'synthetic-table/row2',quote:'Family F1: the daughter carries the variant.'}]}]}}]}});
const server=http.createServer(async(req,res)=>{
    if(req.url==='/counts')return reply(res,{scope:'SYNTHETIC_BROWSER_ONLY',posts,gets,cancels,modelCalls:0,scientificCalls:0,requests});
    if(req.url==='/app.js')return res.writeHead(200,{'Content-Type':'text/javascript'}).end(fs.readFileSync(path.join(output,'app.js')));
    if(req.url==='/')return res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'}).end('<!doctype html><meta charset="utf-8"><title>PP1 浏览器组件核验</title><h1>PP1 浏览器组件核验（合成接口，非科研结果）</h1><div id="root"></div><pre id="counts"></pre><script src="/app.js"></script>');
    if(!req.url.startsWith(prefix))return res.writeHead(404).end();
    let raw='';for await(const chunk of req){raw+=chunk;if(raw.length>65536)return res.writeHead(413).end();}
    const body=raw?JSON.parse(raw):null;requests.push({method:req.method,url:req.url,body});
    fs.appendFileSync(path.join(output,'api.jsonl'),JSON.stringify(requests.at(-1))+'\n');
    if(req.method==='GET'&&req.url===prefix){gets++;if(tasks.length===1&&++polls>=2)tasks=[ready(tasks[0])];return reply(res,tasks);}
    if(req.method==='POST'&&req.url.endsWith('/cancel')){cancels++;const id=req.url.split('/').at(-2);tasks=tasks.map(t=>t.taskId===id?{...t,status:'CANCELLED'}:t);return reply(res,tasks.find(t=>t.taskId===id));}
    if(req.method==='POST'&&req.url===prefix){
        if(body?.candidateId!=='candidate-1'||body?.hypothesisId!=='hypothesis-1'||body?.confirmPublicModel!==true)return res.writeHead(400).end();
        posts++;const t=task(posts===1?'a':'b');tasks=[t,...tasks];polls=0;return reply(res,t);
    }
    return res.writeHead(405).end();
});
server.listen(0,'127.0.0.1',()=>{const info={pid:process.pid,url:'http://127.0.0.1:'+server.address().port+'/',scope:'SYNTHETIC_BROWSER_ONLY'};
    fs.writeFileSync(path.join(output,'server.json'),JSON.stringify(info));console.log(info.url);});
