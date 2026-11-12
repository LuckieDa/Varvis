import frontendModuleUrl from './frontend-module-url.cjs';
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { buildSync } from 'esbuild';
import { fileURLToPath } from 'node:url';
const [destinationArg, sources] = process.argv.slice(2);
const destination = destinationArg && path.resolve(destinationArg);
const project = fileURLToPath(new URL('../', import.meta.url));
if (!destination || !sources || fs.existsSync(destination)) throw Error('Use a new output directory and actual async business artifacts');
fs.mkdirSync(destination);
const source = fs.readFileSync(path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js'));
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
const bundle = path.join(destination, 'component.cjs');
buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const render=run=>renderToStaticMarkup(<SavedResult run={run} criteria={run.enabledCriteria}/>);
export const exportRun=run=>{let handler;const walk=e=>{if(!React.isValidElement(e))return;if(e.props.children==='下载本次原始结果 JSON')handler=e.props.onClick;React.Children.forEach(e.props.children,walk);};walk(SavedResult({run,criteria:run.enabledCriteria}));handler();};`, resolveDir: project, loader: 'jsx' },
bundle: true, platform: 'node', format: 'cjs', outfile: bundle, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const { render, exportRun } = createRequire(import.meta.url)(bundle); let checks = 0;
for (const name of ['pending-response', 'response']) {
    const run = JSON.parse(fs.readFileSync(path.join(sources, `${name}.json`))); const frozen = JSON.stringify(run);
    assert.equal(api.localBatchScopeIssue(run), null); assert.ok(api.localBatchSavedRun(run)); checks += 2;
    const html = render(run); assert.match(html, /此处只更新资料任务状态/); assert.ok(!html.includes('受影响背景不展示科学结论')); checks += 2;
    assert.ok(html.includes(name === 'response' ? '资料获取任务已结束' : '公开资料正在独立获取')); checks++;
    fs.writeFileSync(path.join(destination, `${name}.html`), html);
    const prior = { Blob: globalThis.Blob, document: globalThis.document, timer: globalThis.setTimeout, create: URL.createObjectURL, revoke: URL.revokeObjectURL };
    let content; let clicked = false;
    try {
        globalThis.Blob = class { constructor(parts) { content = parts.join(''); } };
        globalThis.document = { createElement: () => ({ click: () => { clicked = true; } }) };
        globalThis.setTimeout = action => action(); URL.createObjectURL = () => 'blob:synthetic'; URL.revokeObjectURL = () => undefined;
        exportRun(run); assert.ok(clicked); assert.deepEqual(JSON.parse(content), run); checks += 2;
    } finally {
        globalThis.Blob = prior.Blob; globalThis.document = prior.document; globalThis.setTimeout = prior.timer; URL.createObjectURL = prior.create; URL.revokeObjectURL = prior.revoke;
    }
    assert.equal(JSON.stringify(run), frozen); checks++;
}
fs.writeFileSync(path.join(destination, 'validation.json'), JSON.stringify({ checks, status: 'PASS', actualBrowser: false, actualDownload: false, scientificExecutions: 0, modelCalls: 0 }, null, 2));
console.log(`${checks} render/scope/full-export checks passed`);
