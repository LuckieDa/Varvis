// React SSR and component command routing only. No HTTP or worker execution.
const assert=require('node:assert/strict'),path=require('node:path');
const {createRequire}=require('node:module');
const dependencies=process.argv[2]?createRequire(path.join(path.resolve(process.argv[2]),'package.json')):require;
const project=path.resolve(__dirname,'..');
const built=dependencies('esbuild').buildSync({stdin:{contents:`import React from 'react';import{renderToStaticMarkup}from'react-dom/server';
import Actions from './src/pages/varvis/ChromosomeVisualization/components/AcmgRunControlActions.jsx';
import {controlAfterHistory} from './src/pages/varvis/ChromosomeVisualization/utils/acmgControl.js';
export const render=props=>renderToStaticMarkup(<Actions {...props}/>);
export const renderHistory=(props,run)=>renderToStaticMarkup(<Actions {...props} control={controlAfterHistory(props.control,run)}/>);export const element=props=>Actions(props);`,resolveDir:project,loader:'jsx'},bundle:true,platform:'node',format:'cjs',write:false,
nodePaths:process.argv[2]?[path.join(path.resolve(process.argv[2]),'node_modules')]:[],define:{'process.env.NODE_ENV':'"production"'},logLevel:'warning'}).outputFiles[0].text;
const compiled={exports:{}};new Function('require','module','exports',built)(dependencies,compiled,compiled.exports);
const api=compiled.exports;let calls=[];let checks=0;
const check=(name,fn)=>{fn();checks++;console.log('PASS '+name);};
const target={analysisId:'SYNTHETIC_ANALYSIS',runId:'SYNTHETIC_RUN'};
const definitions={PAUSE:['PAUSE_RUN','暂停当前分析',false],RESUME:['RESUME_RUN','继续当前执行进程',false],STOP:['STOP_RUN','停止当前分析',true],ACKNOWLEDGE_INTERRUPTED:['ACKNOWLEDGE_INTERRUPTED','确认旧运行已中断',true]};
const item=command=>({actionId:definitions[command][0],controlAction:command,label:definitions[command][1],available:true,requiresConfirmation:definitions[command][2],createsNewRun:false,createsNewSourceTask:false,overwritesSavedScientificResult:false,target:{...target}});
const control=(state,commands,live=true)=>({...target,state,activeWorkerAvailable:live,sameWorkerResumable:live&&['PAUSED','PAUSE_REQUESTED'].includes(state),savedScientificResultChanged:false,availableActions:commands.map(item)});
const props=value=>({...target,control:value,onAction:action=>calls.push(action)});
const commands=html=>[...html.matchAll(/data-control-action="([A-Z_]+)"/g)].map(match=>match[1]);
for(const [state,expected]of [['RUNNING',['PAUSE','STOP']],['PAUSED',['RESUME','STOP']],['PAUSE_REQUESTED',['RESUME','STOP']],['STOP_REQUESTED',[]]])check('live '+state+' exposes only fixed currently available commands',()=>{
    assert.deepEqual(commands(api.render(props(control(state,expected)))),expected);
});
for(const state of ['RECOVERY_REQUIRED','INTERRUPTED'])check('dead or unknown owner '+state+' offers ACK only, no resume',()=>{
    const html=api.render(props(control(state,['ACKNOWLEDGE_INTERRUPTED'],false)));
    assert.deepEqual(commands(html),['ACKNOWLEDGE_INTERRUPTED']);
});
for(const state of ['COMPLETED','COMPLETED_WITH_BLOCKERS','FAILED','CANCELLED','INTERRUPTED'])check('persisted terminal '+state+' empty capability list stays empty',()=>{
    const html=api.render(props(control(state,[],false)));assert.deepEqual(commands(html),[]);
});
check('present empty list does not restore legacy RUNNING pause or stop',()=>assert.deepEqual(commands(api.render(props(control('RUNNING',[])))),[]));
check('legacy server missing optional list keeps prior state-based buttons',()=>assert.deepEqual(commands(api.render(props({...target,state:'PAUSED'}))),['RESUME','STOP']));
check('wrong target rejects commands with visible capability warning',()=>{
    const value=control('RUNNING',['PAUSE','STOP']);value.availableActions[0].target.runId='ANOTHER_RUN';
    const html=api.render(props(value));assert.deepEqual(commands(html),[]);assert.ok(html.includes('运行控制尚未核实'));
});
check('unknown source-retry or server URL cannot become a button',()=>{
    const value=control('RUNNING',['PAUSE']);value.availableActions.push({...item('STOP'),actionId:'RETRY_SOURCE',controlAction:'RETRY_SOURCE',url:'https://example.invalid/execute'});
    assert.deepEqual(commands(api.render(props(value))),[]);
});
check('busy controls stay disabled',()=>{
    const html=api.render({...props(control('RUNNING',['PAUSE','STOP'])),busy:true});assert.equal((html.match(/disabled=""/g)||[]).length,2);
});
check('owned local preparation is distinct from live backend resume and creates no ACK',()=>{
    const html=api.render({analysisId:target.analysisId,localPreparation:true,control:{state:'RUNNING'},onAction:action=>calls.push(action)});
    assert.deepEqual(commands(html),['PAUSE','STOP']);
});
check('local ended preparation without run target has no false capability warning or action',()=>{
    const html=api.render({analysisId:target.analysisId,control:{state:'CANCELLED'},onAction:()=>{throw Error('must not execute');}});
    assert.deepEqual(commands(html),[]);assert.doesNotMatch(html,/role="alert"/);
});
for(const previous of ['RUNNING','PAUSED'])for(const status of ['COMPLETED','FAILED','CANCELLED'])check(`history ${status} hides stale ${previous} commands in real controls`,()=>{
    const live=control(previous,previous==='RUNNING'?['PAUSE','STOP']:['RESUME','STOP']);
    const html=api.renderHistory(props(live),{...target,status});
    assert.deepEqual(commands(html),[]);
});
for(const status of ['COMPLETED','COMPLETED_WITH_BLOCKERS','FAILED','CANCELLED'])check(`saved ${status} cannot retain an interrupted ACK button`,()=>{
    const html=api.renderHistory(props(control('INTERRUPTED',['ACKNOWLEDGE_INTERRUPTED'],false)),{...target,status});
    assert.deepEqual(commands(html),[]);
});
check('late same-run snapshot cannot restore visible commands after saved completion',()=>{
    const saved={...target,status:'COMPLETED'};
    for(const live of [control('RUNNING',['PAUSE','STOP']),control('PAUSED',['RESUME','STOP'])])
        assert.deepEqual(commands(api.renderHistory(props(live),saved)),[]);
});
check('another run or analysis cannot lend visible controls to selected ended history',()=>{
    for(const patch of [{runId:'OLD_RUN'},{analysisId:'OLD_ANALYSIS'}])
        assert.deepEqual(commands(api.renderHistory(props({...control('RUNNING',['PAUSE','STOP']),...patch}),{...target,status:'FAILED'})),[]);
});
check('render and history display never invoke command handlers; explicit clicks pass fixed commands only',()=>{
    assert.deepEqual(calls,[]);
    const value=props(control('PAUSED',['RESUME','STOP'])),tree=api.element(value);
    const walk=node=>!node?[]:Array.isArray(node)?node.flatMap(walk):typeof node==='object'?[node,...walk(node.props?.children)]:[];
    const buttons=walk(tree).filter(node=>node.props?.['data-control-action']);
    buttons[0].props.onClick();assert.deepEqual(calls,['RESUME']);buttons[1].props.onClick();assert.deepEqual(calls,['RESUME','STOP']);
});
console.log(`${checks} React live-control component checks passed; synthetic contract fixtures, no HTTP/worker/browser execution.`);
