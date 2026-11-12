// Real browser component, controlled GET transport; synthetic stored records, no science/model/backend.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
const [destination, artifacts] = process.argv.slice(2);
if (!destination || !artifacts) throw Error('Supply new output directory and synthetic async artifacts');
const project = fileURLToPath(new URL('../', import.meta.url)), output = path.resolve(destination);
if (output.startsWith(project) || fs.existsSync(output)) throw Error('Use new isolated directory');
fs.mkdirSync(output);
const run = JSON.parse(fs.readFileSync(path.join(artifacts, 'pending-response.json')));
const ready = JSON.parse(fs.readFileSync(path.join(artifacts, 'ready-status.json')));
const panel = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
await build({ stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';import axios from 'axios';import {SavedResult} from ${JSON.stringify(panel)};(${checks.toString()})(React,createRoot,axios,SavedResult,${JSON.stringify(run)});`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'browser', outfile: path.join(output, 'checks.js'), define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
let reads = 0;
const server = http.createServer((req, res) => {
    fs.appendFileSync(path.join(output, 'http.jsonl'), JSON.stringify({ method: req.method, url: req.url }) + '\n');
    res.setHeader('Cache-Control', 'no-store');
    if (req.method !== 'GET') { res.writeHead(405).end(); return; }
    if (req.url === '/status') {
        const status = ++reads === 1 ? { ...ready, status: 'RUNNING' } : ready;
        res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(status)); return;
    }
    if (req.url === '/checks.js') { res.writeHead(200, { 'Content-Type': 'text/javascript;charset=utf-8' }).end(fs.readFileSync(path.join(output, 'checks.js'))); return; }
    if (req.url !== '/') { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'Content-Type': 'text/html;charset=utf-8' }).end('<!doctype html><meta charset="utf-8"><title>异步资料组件合成核验</title><h1>异步资料组件合成核验</h1><div id="root"></div><script src="/checks.js"></script>');
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const info = { pid: process.pid, url: `http://127.0.0.1:${server.address().port}/`, mode: 'SYNTHETIC_COMPONENT_READ_ONLY' };
fs.writeFileSync(path.join(output, 'server.json'), JSON.stringify(info)); console.log(info.url);
function checks(React, createRoot, axios, SavedResult, run) {
    const button = document.createElement('button'), report = document.createElement('pre'), host = document.createElement('div');
    button.textContent = '运行异步资料组件检查'; report.id = 'test-result';
    document.getElementById('root').append(button, report, host);
    button.onclick = async () => {
        button.disabled = true; const frozen = JSON.stringify(run), calls = [], states = [];
        const root = createRoot(host); let failure;
        try {
            axios.defaults.adapter = async config => {
                calls.push({ method: config.method, url: config.url, workflow: config.params?.workflow });
                if (config.method !== 'get' || !config.url.endsWith('/denovo-source')) throw Error('Unexpected operation');
                const data = await fetch('/status', { signal: config.signal }).then(r => r.json());
                return { data, status: 200, statusText: 'OK', headers: {}, config };
            };
            root.render(React.createElement(SavedResult, { run, criteria: run.enabledCriteria }));
            const end = Date.now() + 10000;
            while (Date.now() < end) {
                states.push(host.textContent.includes('正在独立获取参考文献'));
                if (host.textContent.includes('资料获取任务已结束')) break;
                await new Promise(resolve => setTimeout(resolve, 50));
            }
            if (!states.some(Boolean) || !host.textContent.includes('资料获取任务已结束')) throw Error('Missing RUNNING → READY transition');
            if (!host.textContent.includes('下载及历史评价仍保持保存时内容')) throw Error('Missing immutable history explanation');
            if (calls.length !== 2 || calls.some(c => c.method !== 'get' || c.workflow !== 'LOCAL_COMPUTATIONAL_BATCH1')) throw Error('Unexpected calls');
            if (JSON.stringify(run) !== frozen) throw Error('Saved object mutated');
            root.unmount(); await new Promise(resolve => setTimeout(resolve, 3200));
            if (calls.length !== 2) throw Error('Polling continues after unmount');
        } catch (error) { failure = error.message; root.unmount(); }
        report.textContent = JSON.stringify({ status: failure ? 'FAIL' : 'PASS', failure, checks: 5, calls, source: 'SYNTHETIC_SAVED_RECORD_REAL_REACT', businessPost: 0, scientificExecutions: 0, modelCalls: 0, actualDownload: false }, null, 2);
    };
}
