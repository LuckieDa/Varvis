// Read-only archived rendering and TEST_ONLY display fixtures. No product API or scientific calls.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const [destination, input] = process.argv.slice(2);
if (!destination || !input) throw Error('Supply isolated output directory and archived result JSON');
const project = fileURLToPath(new URL('../', import.meta.url)), output = path.resolve(destination);
if (output.startsWith(project)) throw Error('Use isolated output');
fs.mkdirSync(output, {recursive:true});
const run = JSON.parse(fs.readFileSync(input));
const entry = `import React from 'react'; import {createRoot} from 'react-dom/client';
import {Dialog,DialogTitle,DialogContent} from '@mui/material';
import Workbench from './src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx';
import {backgroundEvidenceCards,savedBackgrounds,resultOutcome,resultStatusCounts,resultStyle,sortResultRows,resultPresentation,presentationCounts} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';
import {evidenceCards,evidenceSummary} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';
(${checks.toString()})(React,createRoot,Workbench,Dialog,DialogTitle,DialogContent,sortResultRows,backgroundEvidenceCards,savedBackgrounds,resultOutcome,evidenceCards,evidenceSummary,resultStatusCounts,resultStyle,resultPresentation,presentationCounts,${JSON.stringify(run)});`;
await build({stdin:{contents:entry,resolveDir:project,loader:'jsx'},bundle:true,platform:'browser',format:'iife',outfile:path.join(output,'checks.js'),loader:{'.js':'jsx','.less':'empty'},define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'});
const server=http.createServer(async(req,res)=>{
    fs.appendFileSync(path.join(output,'http.jsonl'),JSON.stringify({method:req.method,url:req.url})+'\n');
    if(req.method==='POST' && req.url==='/test-results') {
        let body='';for await(const chunk of req){body+=chunk;if(body.length>1000000){res.writeHead(413).end();return;}}
        fs.writeFileSync(path.join(output,'component-results.json'),body);res.writeHead(200).end('saved');return;
    }
    if(req.method!=='GET'){res.writeHead(405).end();return;}
    if(req.url==='/checks.js'){res.writeHead(200,{'Content-Type':'text/javascript;charset=utf-8'}).end(fs.readFileSync(path.join(output,'checks.js')));return;}
    if(req.url!=='/'){res.writeHead(404).end();return;}
    res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'}).end('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>ACMG 结果工作台 · 归档只读核查</title><style>body{margin:0;padding:24px;background:#f5f8fc;font-family:Arial,"Microsoft YaHei",sans-serif;color:#20304a}#check-status{padding:12px;margin-bottom:16px;background:white;border:1px solid #dbe5f4;border-radius:8px}</style><div id="check-status">归档只读核查中，不连接业务服务</div><div id="root"></div><script src="/checks.js"></script></html>');
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
fs.writeFileSync(path.join(output,'server.json'),JSON.stringify({pid:process.pid,port:server.address().port}));
console.log(`http://127.0.0.1:${server.address().port}/`);

async function checks(React,createRoot,Workbench,Dialog,DialogTitle,DialogContent,sortRows,backgroundCards,backgrounds,outcome,cards,summary,counts,style,_present,mainCounts,run) {
    const results=[], before=JSON.stringify(run),root=createRoot(document.getElementById('root'));
    const wait=ms=>new Promise(resolve=>setTimeout(resolve,ms));
    const assert=(condition,message)=>{if(!condition)throw Error(message);};
    const test=async(name,fn)=>{try{await fn();results.push({name,status:'PASS'});}catch(e){results.push({name,status:'FAIL',error:e.message});}};
    const render=async value=>{root.render(React.createElement(Dialog,{open:true,maxWidth:"xl",fullWidth:true,"aria-labelledby":"test-parent-title"},React.createElement(DialogTitle,{id:"test-parent-title"},"测试用 ACMG 外层窗口"),React.createElement(DialogContent,null,React.createElement(Workbench,{key:value.runId,run:value,criteria:value.enabledCriteria}))));await wait(350);};
    const button=label=>[...document.querySelectorAll('button')].find(b=>b.textContent===label||b.getAttribute('aria-label')===label);
    await test('every archived context preserves status, strength, reason and raw fields',()=>{
        for(const c of run.candidates) for(const b of backgrounds(c)) {
            const selected=backgroundCards(c,run.enabledCriteria,run,b.index);
            assert(selected.length===run.enabledCriteria.length,'criteria omitted');
            for(const card of selected) {
                const original=cards(c,run.enabledCriteria,card.selectedView,card.selectedBackground,run).find(x=>x.id===card.id);
                for(const e of card.entries) assert(original.entries.some(o=>JSON.stringify(o)===JSON.stringify(e)),'entry altered: '+card.code);
            }
        }
    });
    await test('BP7 review never counted as adopted MET',()=>assert(outcome({evaluation:{criterionId:'ACMG_AMP_2015:BP7',reviewRequired:true,evaluationResult:{status:'MET'}}}).kind==='REVIEW','review lost'));
    await test('distinct reasons remain separate and all rows are counted including unknown future statuses',()=>{
        const reasons=['TEST_NOT_APPLICABLE','TEST_MISSING','TEST_NO_RECORD','TEST_INCOMPLETE','TEST_DISABLED','TEST_CONFIGURATION','TEST_UNAVAILABLE','TEST_CONFLICT','TEST_UNSUPPORTED','TEST_FAILED','TEST_OTHER'];
        const rows=reasons.map((reasonCode,index)=>({outcome:outcome({evaluation:{evaluationResult:{status:index===0?'NOT_APPLICABLE':'NOT_EVALUATED',reasonCode}}})}));
        rows.push({outcome:{kind:'FUTURE_STATUS'}});
        const totals=counts(rows);
        assert(totals.reduce((sum,item)=>sum+item.count,0)===rows.length,'lost or double-counted evidence');
        assert(totals.filter(item=>item.count>0).length===11,'distinct reasons collapsed');
        assert(totals.find(item=>item.kind==='UNRESOLVED').count===2,'unknown silently classified');
        assert(style('FUTURE_STATUS').kind==='UNRESOLVED','unknown styling lost');
    });
    await render(run);
    await test('result priority is stable, includes both evidence directions and never mutates entries',()=>{
        const kinds=['NOT_APPLICABLE','MISSING','MET','CONFLICT','NOT_MET','MET','FUTURE_STATUS','REVIEW'];
        const rows=kinds.map((kind,index)=>({outcome:{kind},index,sourceRef:'source-'+index}));
        const frozen=JSON.stringify(rows), sorted=sortRows(rows);
        assert(sorted.map(row=>row.index).join(',')==='2,5,4,3,7,6,1,0','incorrect reading priority');
        assert(JSON.stringify(rows)===frozen && sorted.every(row=>rows.includes(row)),'changed input or source references');
        const actual=document.querySelector('[data-testid="evidence-result-row"]');
        const expected=sortRows(backgroundCards(run.candidates[0],run.enabledCriteria,run,0).flatMap(card=>card.entries.map(entry=>({card,entry,outcome:outcome(entry)}))));
        assert(actual.textContent.startsWith(expected[0].card.code),'UI not sorted');
    });
    await test('only selected variant mounts all 18 concise rows',()=>assert(document.querySelectorAll('[data-testid="evidence-result-row"]').length===18,'row count'));
    await test('independent detail dialog has full fields and closes to same selection',async()=>{
        const selected=document.querySelector('[aria-current="true"]').textContent;
        button('PM2 详情').click();await wait(350);
        const dialog=document.querySelector('[role="dialog"][aria-labelledby="evidence-detail-title"]');assert(dialog?.textContent.includes('完整证据字段与来源标识'),'missing detail');
        assert(dialog?.textContent.includes('PM2'),'wrong criterion');
        const bounds=dialog.getBoundingClientRect();
        assert(dialog.contains(document.elementFromPoint(bounds.left+Math.min(bounds.width/2,150),bounds.top+100)),'detail covered by parent dialog');
        assert(Number(getComputedStyle(dialog.closest('.MuiModal-root')).zIndex)>Number(getComputedStyle(document.querySelector('[role="dialog"][aria-labelledby="test-parent-title"]').closest('.MuiModal-root')).zIndex),'detail layer below parent');
        assert(!button('展开阅读') && !button('收起宽屏'),'removed reading controls remain');
        assert(dialog.getBoundingClientRect().width<=window.innerWidth,'dialog exceeds viewport');
        button('关闭并返回').click();await wait(350);
        assert(!document.querySelector('[role="dialog"][aria-labelledby="evidence-detail-title"]'),'dialog stayed open');
        assert(document.querySelector('[aria-current="true"]').textContent===selected,'selection lost');
    });
    await test('each archived status filter matches the saved reason category and counters conserve totals',async()=>{
        const expected=backgroundCards(run.candidates[0],run.enabledCriteria,run,0).flatMap(card=>card.entries.map(entry=>({entry,outcome:outcome(entry)})));
        for(const item of mainCounts(expected)) {
            const control=document.querySelector('[data-testid="status-filter-'+item.kind+'"]');
            assert(control?.getAttribute('aria-label')===item.label+' '+item.count+' 项','incorrect count '+item.kind);
            control.click();await wait(70);
            assert(document.querySelectorAll('[data-testid="evidence-result-row"]').length===item.count,'incorrect filter '+item.kind);
            button('显示全部').click();await wait(70);
        }
        assert(!document.querySelector('section').textContent.includes('其他状态'),'generic bucket remains');
    });
    await test('switch variant updates rows and known concise reasons',async()=>{
        const list=document.querySelectorAll('nav [role="button"]');assert(list.length===run.candidates.length,'missing candidates');
        list[1].click();await wait(120);
        const expected=backgroundCards(run.candidates[1],run.enabledCriteria,run,0);
        const text=document.querySelector('section').textContent;
        for(const card of expected) for(const e of card.entries) assert(text.includes(summary(e)),'missing reason '+card.code);
    });
    await test('filter hides only display rows and restores all',async()=>{
        const met=document.querySelector('[data-testid="status-filter-ADOPTED_MET"]');
        met.click();await wait(100);
        const text=[...document.querySelectorAll('[data-testid="evidence-result-row"]')].map(e=>e.textContent);
        assert(text.every(t=>t.includes('已采用满足')),'wrong filter');button('显示全部').click();await wait(100);
        assert(document.querySelectorAll('[data-testid="evidence-result-row"]').length===18,'filter did not reset');
    });
    const synthetic=structuredClone(run);synthetic.runId='TEST_ONLY-two-backgrounds';synthetic.candidates=[synthetic.candidates[0]];
    const c=synthetic.candidates[0],other=structuredClone(c.attempts[0]);
    other.hypothesisId='TEST_ONLY-other-background';other.localBatch.hypothesisId=other.hypothesisId;
    other.context=null;other.ba1Context=null;other.bs1Context=null;
    other.localBatch.criteria=[]; // Deliberately incomplete; must not inherit the first background's score.
    c.attempts.push(other);
    await test('incomplete second background never falls back to first background score',async()=>{
        const selected=backgroundCards(c,synthetic.enabledCriteria,synthetic,1);
        assert(selected.every(card=>card.entries.every(e=>outcome(e).kind!=='MET')),'borrowed another score');
        await render(synthetic);button('背景尚未完整确定').click();await wait(150);
        assert(!document.querySelector('section').textContent.includes('已评价 · 满足'),'wrong active background');
        button('BA1 详情').click();await wait(250);assert(document.querySelector('[role="dialog"][aria-labelledby="evidence-detail-title"]'),'missing blocked detail');button('关闭并返回').click();await wait(250);
    });
    await test('original archived result unchanged',()=>assert(JSON.stringify(run)===before,'source mutation'));
    await render(run);
    const report={tests:results.length,passed:results.filter(r=>r.status==='PASS').length,results,businessRequests:0,modelRequests:0};
    document.getElementById('check-status').textContent=`归档只读核查：${report.passed}/${report.tests} 通过 · 无业务或模型请求 · 可手动查看页面`;
    await fetch('/test-results',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(report,null,2)});
}
