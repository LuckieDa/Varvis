import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';

const [distArg,logArg]=process.argv.slice(2);
const dist=fs.realpathSync(distArg);
const types={'.html':'text/html;charset=utf-8','.js':'application/javascript','.css':'text/css',
  '.json':'application/json','.svg':'image/svg+xml','.png':'image/png','.woff2':'font/woff2','.ico':'image/x-icon'};
http.createServer((req,res)=>{
  const url=new URL(req.url,'http://127.0.0.1');
  fs.appendFileSync(logArg,JSON.stringify({time:new Date().toISOString(),method:req.method,path:url.pathname})+'\n');
  if(url.pathname==='/variant-analyses'||url.pathname.startsWith('/variant-analyses/')||url.pathname.startsWith('/variantBasedMap/')){
    const upstream=http.request({hostname:'127.0.0.1',port:8086,path:req.url,method:req.method,
      headers:{...req.headers,host:'127.0.0.1:8086'},timeout:15*60*1000},response=>{
      res.writeHead(response.statusCode,response.headers);response.pipe(res);
    });
    upstream.on('timeout',()=>upstream.destroy(new Error('LOCAL_BACKEND_TIMEOUT')));
    upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502,{'Content-Type':'text/plain'});res.end('Local backend unavailable');});
    req.pipe(upstream);return;
  }
  if(!['GET','HEAD'].includes(req.method)){res.writeHead(405);res.end();return;}
  let relative;
  try{relative=decodeURIComponent(url.pathname);}catch{res.writeHead(400);res.end();return;}
  let file=path.resolve(dist,'.'+relative);
  if(!file.startsWith(dist+path.sep)&&file!==dist){res.writeHead(403);res.end();return;}
  if(fs.existsSync(file)&&fs.statSync(file).isDirectory())file=path.join(file,'index.html');
  if(!fs.existsSync(file)){res.writeHead(404);res.end();return;}
  const real=fs.realpathSync(file);
  if(!real.startsWith(dist+path.sep)){res.writeHead(403);res.end();return;}
  res.writeHead(200,{'Content-Type':types[path.extname(real)]||'application/octet-stream','Cache-Control':'no-cache'});
  if(req.method==='HEAD')res.end();else fs.createReadStream(real).pipe(res);
}).listen(8000,'127.0.0.1',()=>console.log('LOCAL_EVIDENCE_FRONTEND_READY 8000'));
