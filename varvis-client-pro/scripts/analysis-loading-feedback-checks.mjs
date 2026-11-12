// Isolated browser checks with deferred mock reads. No product/server/database/model calls.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const project = fileURLToPath(new URL('../', import.meta.url));
const output = path.resolve(process.argv[2]);
if (output.startsWith(project)) throw Error('Use an isolated output directory');
fs.mkdirSync(output, {recursive:true});
const service = fs.readFileSync(path.join(project,'src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js'),'utf8');
const names = [...service.matchAll(/export const (\w+)\s*=/g)].map(m=>m[1]);
const mock = names.map(name => name === 'SUPPORTED_GENOME_ASSEMBLIES'
    ? `export const ${name}=['GRCh38','GRCh37'];`
    : `export const ${name}=(...args)=>window.mockRead('${name}',args);`).join('\n');
await build({stdin:{contents:`import React from 'react';import {createRoot} from 'react-dom/client';
import Drawer from './src/pages/varvis/ChromosomeVisualization/components/VariantAnalysisDrawer';
import Acmg from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel';
import Notice from './src/pages/varvis/ChromosomeVisualization/components/AnalysisLoadingNotice';
(${checks.toString()})(React,createRoot,Drawer,Acmg,Notice);`,loader:'jsx',resolveDir:project},
bundle:true,platform:'browser',outfile:path.join(output,'checks.js'),loader:{'.js':'jsx','.less':'empty'},
define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning',plugins:[{name:'mock-read-only-service',setup(b){
    b.onLoad({filter:/[\\/]services[\\/]variantAnalysis\.js$/},()=>({contents:mock,loader:'js'}));
}}]});
const server=http.createServer(async(req,res)=>{
    if(req.method==='POST' && req.url==='/test-results') {
        let body=''; for await(const part of req) body+=part;
        fs.writeFileSync(path.join(output,'results.json'),body);res.end('saved');return;
    }
    if(req.method!=='GET'){res.writeHead(405).end();return;}
    if(req.url==='/checks.js'){res.setHeader('Content-Type','text/javascript;charset=utf-8');res.end(fs.readFileSync(path.join(output,'checks.js')));return;}
    if(req.url!=='/'){res.writeHead(404).end();return;}
    res.setHeader('Content-Type','text/html;charset=utf-8');res.end('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>加载反馈 · 隔离检查</title><style>body{font-family:Arial,"Microsoft YaHei",sans-serif;background:#f5f8fc;color:#20304a;padding:20px}button{padding:10px;margin:6px}#status{white-space:pre-wrap}</style><button id="start">运行隔离检查</button><pre id="status">全部请求为本地模拟，不连接产品服务。</pre><div id="root"></div><script src="/checks.js"></script></html>');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
fs.writeFileSync(path.join(output,'server.json'),JSON.stringify({pid:process.pid,port:server.address().port}));
console.log(`http://127.0.0.1:${server.address().port}/`);

function checks(React,createRoot,Drawer,Acmg,Notice) {
    const root=createRoot(document.getElementById('root')), calls=[],pending={};
    const deferred=name=>new Promise((resolve,reject)=>{(pending[name] ||= []).push({resolve,reject});});
    window.mockRead=(name,args)=>{
        calls.push({name,args});
        if (['fetchVariantAnalysis','fetchVariantAnalysisVariants','fetchAcmgInputOptions','fetchAcmgBusinessRuns'].includes(name)) return deferred(name);
        if (name==='fetchVariantAnalysisEvents') return Promise.resolve([]);
        if (name==='fetchVariantAnalysisPreflight') return Promise.resolve({ready:true});
        if (name==='fetchVariantAnalysisProviderStatus') return Promise.resolve({});
        throw Error('Unexpected request '+name);
    };
    const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
    const text=()=>document.body.textContent;
    const assert=(condition,message)=>{if(!condition)throw Error(message);};
    const button=label=>[...document.querySelectorAll('button')].find(el=>el.textContent.trim()===label);
    const take=name=>{const value=pending[name]?.shift();if(!value)throw Error('No deferred '+name);return value;};
    const job={id:'TEST_ONLY_LOADING',status:'READY',originalFilename:'TEST_ONLY.vcf',genomeAssembly:'GRCh38',mode:'WES',candidateCount:1};
    document.getElementById('start').onclick=async()=>{
        document.getElementById('start').disabled=true;
        const results=[];
        const test=async(name,fn)=>{await fn();results.push({name,status:'PASS'});};
        try {
            await test('ACMG entry reports both pending reads and disables prepare',async()=>{
                root.render(React.createElement(Acmg,{analysisId:job.id,candidateCount:19}));await wait(300);
                assert(text().includes('正在读取分析信息与运行状态'), 'entry notice missing');
                assert(text().includes('入口与来源信息：读取中'), 'options stage missing');
                assert(button('正在读取入口…')?.disabled,'prepare must be disabled');
            });
            await test('one finished read cannot hide the other pending read',async()=>{
                take('fetchAcmgBusinessRuns').resolve([]);await wait(150);
                assert(text().includes('历史与运行状态：已完成'), 'completed history missing');
                assert(text().includes('入口与来源信息：读取中'), 'pending options hidden');
            });
            await test('entry failure leaves a visible explanation, not endless loading',async()=>{
                take('fetchAcmgInputOptions').reject(Error('TEST_ONLY entry failed'));await wait(150);
                assert(!text().includes('正在读取分析信息与运行状态'), 'entry spinner remains');
                assert(text().includes('TEST_ONLY entry failed'), 'failure not visible');
            });
            await test('slow waiting reports elapsed time without invented percentage',async()=>{
                root.render(React.createElement(Notice,{startedAt:Date.now()-65000,title:'TEST_ONLY slow',detail:'模拟慢读取'}));await wait(150);
                assert(text().includes('已等待 1 分 5 秒'), 'elapsed time missing');
                assert(!document.querySelector('[role="progressbar"]')?.hasAttribute('aria-valuenow'), 'fake progress');
            });
            await test('saved task metadata loading is visible',async()=>{
                root.render(React.createElement(Drawer,{open:true,onClose:()=>undefined}));await wait(250);
                const input=[...document.querySelectorAll('input')].find(el=>document.querySelector(`label[for="${el.id}"]`)?.textContent.includes('已有分析任务 ID'));
                assert(input,'history input missing');
                Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,job.id);
                input.dispatchEvent(new Event('input',{bubbles:true}));await wait(80);button('读取已有分析').click();await wait(100);
                assert(text().includes('正在读取已保存的分析任务'), 'metadata notice missing');
                assert(button('正在读取…')?.disabled, 'duplicate history read allowed');
            });
            await test('READY task shows loading instead of empty candidate claim',async()=>{
                take('fetchVariantAnalysis').resolve(job);await wait(200);
                assert(text().includes('正在读取候选排名与已有注释'), 'candidate notice missing');
                assert(!text().includes('No variants were returned') && !text().includes('当前筛选条件下未返回候选'), 'loading falsely empty');
                assert(button('候选 ACMG 证据评价')?.disabled, 'stale candidate entry enabled');
            });
            await test('candidate failure shows retry and never means no candidates',async()=>{
                take('fetchVariantAnalysisVariants').reject(Error('TEST_ONLY candidate timeout'));await wait(150);
                assert(text().includes('候选列表读取失败') && text().includes('TEST_ONLY candidate timeout'), 'failure missing');
                assert(button('重新读取'), 'retry missing');
                assert(!text().includes('No variants were returned'), 'failure falsely empty');
            });
            await test('retry reads saved results only and successful zero result is distinguished',async()=>{
                button('重新读取').click();await wait(150);
                assert(text().includes('正在读取候选排名与已有注释'),'retry spinner missing');
                take('fetchVariantAnalysisVariants').resolve({variants:[],total:0,page:1,pageSize:50});await wait(200);
                assert(!text().includes('候选列表读取失败') && !text().includes('正在读取候选排名与已有注释'), 'loading/error remains');
                assert(text().includes('当前筛选条件下未返回候选'), 'true empty missing');
                assert(!calls.some(c=>/submit|command|remove/i.test(c.name)), 'write/science call occurred');
            });
        } catch(error) {results.push({status:'FAIL',message:error.message,stack:error.stack});}
        document.getElementById('status').textContent=JSON.stringify(results,null,2);
        await fetch('/test-results',{method:'POST',body:JSON.stringify({results,calls})});
    };
}
