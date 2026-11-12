// Local-only preview of the actual React workbench over a saved result.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { buildSync } = require('esbuild');
const [input, destination] = process.argv.slice(2);
if (!input || !destination) throw Error('Supply saved result JSON and private preview directory');
const project = path.resolve(__dirname, '..'), output = path.resolve(destination);
const allowed = path.resolve(project, '../local/development/handoffs') + path.sep;
if (!output.startsWith(allowed)) throw Error('Preview must use local/development/handoffs');
fs.mkdirSync(output,{recursive:true});
buildSync({stdin:{contents:`
import React from 'react'; import {createRoot} from 'react-dom/client';
import {createTheme,ThemeProvider,CssBaseline,Alert} from '@mui/material';
import Workbench from './src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx';
import axios from 'axios';
axios.defaults.adapter=async()=>{throw Error('预览仅使用已保存结果');};
const theme=createTheme({typography:{fontFamily:'"Microsoft YaHei",Arial,sans-serif'},palette:{primary:{main:'#315eaa'}}});
fetch('/saved-result.json').then(r=>r.json()).then(run=>{
 document.getElementById('loading').remove();
 createRoot(document.getElementById('root')).render(<ThemeProvider theme={theme}><CssBaseline/><Workbench run={run} criteria={run.enabledCriteria}/></ThemeProvider>);
}).catch(error=>{createRoot(document.getElementById('root')).render(<Alert severity="error">{error.message}</Alert>);});
`,resolveDir:project,loader:'jsx'},bundle:true,platform:'browser',format:'iife',outfile:path.join(output,'workbench.js'),
    loader:{'.less':'empty','.js':'jsx'},define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'});
const html='<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>ACMG 得分与分类 · 预览</title><style>body{margin:0;background:#f6f8fc!important}main{max-width:1320px;margin:24px auto;padding:24px;background:white;border:1px solid #dce5f3;border-radius:14px}h1{font:700 22px "Microsoft YaHei",sans-serif;color:#233f60;margin:0 0 24px}#loading{font:16px "Microsoft YaHei",sans-serif;color:#315eaa}@media(max-width:700px){main{margin:0;padding:14px;border:0;border-radius:0}}</style><main><h1>ACMG 证据评价</h1><div id="loading" role="status">正在读取保存结果…</div><div id="root"></div></main><script src="/workbench.js"></script></html>';
fs.writeFileSync(path.join(output,'index.html'),html);
if (process.argv.includes('--build-only')) process.exit(0);
const server=http.createServer((req,res)=>{
    if(req.method!=='GET'){res.writeHead(405).end();return;}
    if(req.url==='/'){res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'}).end(html);return;}
    if(req.url==='/workbench.js'){res.writeHead(200,{'Content-Type':'text/javascript;charset=utf-8'});fs.createReadStream(path.join(output,'workbench.js')).pipe(res);return;}
    if(req.url==='/saved-result.json'){res.writeHead(200,{'Content-Type':'application/json;charset=utf-8'});fs.createReadStream(input).pipe(res);return;}
    res.writeHead(404).end();
});
server.listen(0,'127.0.0.1',()=>{
    const url=`http://127.0.0.1:${server.address().port}/`;
    fs.writeFileSync(path.join(output,'server.json'),JSON.stringify({pid:process.pid,url,input,layer:'actual-react-workbench-over-saved-history-no-analysis'}));
    console.log(url);
});
