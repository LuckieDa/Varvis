// Actual workbench over immutable saved examples; optional isolated public-source server.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {buildSync}=require('esbuild');
const [configFile,destination,sourcePort]=process.argv.slice(2);
if(!configFile||!destination)throw Error('Supply private example configuration and preview directory');
const project=path.resolve(__dirname,'..'),output=path.resolve(destination),allowed=path.resolve(project,'../local/development/handoffs')+path.sep;
if(!output.startsWith(allowed)||!path.resolve(configFile).startsWith(allowed))throw Error('Preview must use local/development/handoffs');
const config=JSON.parse(fs.readFileSync(configFile,'utf8'));
for(const example of config.examples)if(!path.resolve(example.path).startsWith(allowed))throw Error('Saved example must use local development fixtures');
fs.mkdirSync(output,{recursive:true});
buildSync({stdin:{contents:`
import React from 'react';import{createRoot}from'react-dom/client';
import{createTheme,ThemeProvider,CssBaseline,Alert}from'@mui/material';
import Workbench from './src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx';
import axios from 'axios';
axios.defaults.adapter=async config=>{
 if(config.method!=='post'||config.url!=='/variant-analyses/acmg/source-view/regions')throw Error('预览仅使用已保存结果');
 const res=await fetch(config.url,{method:'POST',headers:{'Content-Type':'application/json'},body:typeof config.data==='string'?config.data:JSON.stringify(config.data)});
 if(!res.ok)throw Error('原文暂不可用');return{data:await res.json(),status:res.status,statusText:res.statusText,headers:{},config};
};
const theme=createTheme({typography:{fontFamily:'"Microsoft YaHei",Arial,sans-serif'},palette:{primary:{main:'#315eaa'}}});
const key=new URLSearchParams(location.search).get('example')||'default';
fetch('/saved-result/'+encodeURIComponent(key)).then(r=>{if(!r.ok)throw Error('保存样例未找到');return r.json();}).then(run=>{
document.getElementById('loading').remove();createRoot(document.getElementById('root')).render(<ThemeProvider theme={theme}><CssBaseline/><Workbench run={run} criteria={run.enabledCriteria}/></ThemeProvider>);
}).catch(e=>createRoot(document.getElementById('root')).render(<Alert severity="error">{e.message}</Alert>));
`,resolveDir:project,loader:'jsx'},bundle:true,platform:'browser',format:'iife',outfile:path.join(output,'workbench.js'),
loader:{'.less':'empty','.js':'jsx'},define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'});
const escape=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const links=config.examples.map(e=>`<a href="/?example=${encodeURIComponent(e.key)}">${escape(e.label)}</a>`).join('');
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ACMG 证据详情 · 保存记录核查</title><style>body{margin:0;background:#f6f8fc!important}main{max-width:1320px;margin:20px auto;padding:24px;background:white;border:1px solid #dce5f3;border-radius:14px}nav{display:flex;gap:18px;margin:0 0 20px;flex-wrap:wrap}nav a{font:600 15px "Microsoft YaHei",sans-serif;color:#315eaa}h1{font:700 22px "Microsoft YaHei",sans-serif;color:#233f60;margin:0 0 18px}@media(max-width:700px){main{margin:0;padding:14px}}</style><main><h1>ACMG 证据评价</h1><nav>${links}</nav><div id="loading" role="status">正在读取保存结果…</div><div id="root"></div></main><script src="/workbench.js"></script></html>`;
fs.writeFileSync(path.join(output,'index.html'),html);
const sourceRequests=[];
const server=http.createServer((req,res)=>{
    const url=new URL(req.url,'http://localhost');
    if(url.pathname.startsWith('/variant-analyses/acmg/source-view/')&&/^\d+$/.test(sourcePort||'')){
        sourceRequests.push({method:req.method,path:url.pathname,time:new Date().toISOString()});
        fs.writeFileSync(path.join(output,'source-requests.json'),JSON.stringify(sourceRequests,null,2));
        const upstream=http.request({host:'127.0.0.1',port:Number(sourcePort),path:req.url,method:req.method,headers:{'Content-Type':'application/json'}},response=>{res.writeHead(response.statusCode,response.headers);response.pipe(res);});
        upstream.on('error',()=>res.writeHead(503).end());req.pipe(upstream);return;
    }
    if(req.method!=='GET'){res.writeHead(405).end();return;}
    if(url.pathname==='/'){res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'}).end(html);return;}
    if(url.pathname==='/workbench.js'){res.writeHead(200,{'Content-Type':'text/javascript;charset=utf-8'});fs.createReadStream(path.join(output,'workbench.js')).pipe(res);return;}
    const example=config.examples.find(e=>url.pathname==='/saved-result/'+e.key);
    if(example){res.writeHead(200,{'Content-Type':'application/json;charset=utf-8'});fs.createReadStream(example.path).pipe(res);return;}
    res.writeHead(404).end();
});
server.listen(0,'127.0.0.1',()=>{const url='http://127.0.0.1:'+server.address().port+'/';
    fs.writeFileSync(path.join(output,'server.json'),JSON.stringify({pid:process.pid,url,configFile,sourcePort,layer:'actual-workbench-saved-history-public-source-assets-only'}));console.log(url);});
