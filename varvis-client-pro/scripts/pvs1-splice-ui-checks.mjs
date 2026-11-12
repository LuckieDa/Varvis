import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import { createRequire } from 'node:module';
import { build } from 'esbuild';

// Pinned, source-bound SYNTHETIC saved runs; no product API or scientific execution.
const [manifestPath, outputPath, mode] = process.argv.slice(2);
if (!manifestPath || !outputPath) throw new Error('FIXTURE_MANIFEST NEW_OUTPUT [--serve] required');
const output = path.resolve(outputPath), project = process.cwd();fs.mkdirSync(output);
const write = (name, data) => fs.writeFileSync(path.join(output, name), JSON.stringify(data, null, 2));
const helper = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const h = await import(frontendModuleUrl('data:text/javascript;base64,' + fs.readFileSync(helper).toString('base64')));
const fixtures = {}, manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
for (const [name, pins] of Object.entries(manifest)) {
    fixtures[name] = {};
    for (const [role, pin] of Object.entries(pins)) {
        const bytes = fs.readFileSync(pin.path);assert.equal(crypto.createHash('sha256').update(bytes).digest('hex'), pin.sha256);
        fixtures[name][role] = JSON.parse(bytes);
    }
}
write('source-pins.json', manifest);
const checks = [], check = (name, fn) => { fn();checks.push(name); };
const rows = run => run.candidates.flatMap(c => c.attempts).map(a => a.pvs1);
for (const [name, f] of Object.entries(fixtures)) {
    check(name + ' options / saved associations', () => {
        assert.equal(h.pvs1NmdScopeIssue(f.options), null);assert.equal(h.pvs1NmdSavedIssue(f.saved), null);
        assert.deepEqual(h.currentBusinessCriteria(f.options), ['ACMG_AMP_2015:PVS1']);
    });
    check(name + ' original context tokens remain usable', () => {
        const form = h.prepareSimplifiedInputs(f.options);
        const body = h.buildSimplifiedCaseDeclaration(f.options, f.options.analysisId, form, undefined, 'SYNTHETIC');
        assert.equal(body.hypotheses.length, 2);
        for (let i = 0; i < 2; i++) assert.equal(body.hypotheses[i].transcript.accession, f.request.hypotheses[i].transcript.accession);
    });
}
const invalid = {}, mutations = {
    'material byte content': v => { v.spliceConsequenceMaterial.assessment.basis += 'tampered'; },
    'material missing': v => { delete v.spliceConsequenceMaterial; },
    'splice assessment missing': v => { delete v.resolvedInput.spliceAssessment; },
    'review association': v => { v.resolvedInput.spliceAssessment.reviewId += 'wrong'; },
    'event association': v => { v.resolvedInput.spliceAssessment.event.deleteStartCds += 1; },
    'evidence label': v => { v.resolvedInput.spliceAssessment.evidenceKind = 'OBSERVED_RNA'; },
    'scope cap': v => { v.resolvedInput.spliceAssessment.maximumStrength = 'SUPPORTING'; },
    'model identity': v => { v.transcriptModelBinding.canonicalSha256 = '0'.repeat(64); },
    'applied strength': v => { v.evaluation.appliedStrength = 'SUPPORTING'; },
    'execution version': v => { v.evaluation.deterministicTrace.evaluatorVersion = '1.2.0'; },
};
for (const [name, mutate] of Object.entries(mutations)) check('reject ' + name + ' preserving raw JSON', () => {
    const run = structuredClone(fixtures.NMD.saved);mutate(rows(run)[0]);const before = JSON.stringify(run);
    assert.ok(h.pvs1NmdSavedIssue(run));assert.equal(JSON.stringify(run), before);invalid[name] = run;
});
await build({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from ${JSON.stringify(component)};export const render=run=>renderToStaticMarkup(<SavedResult run={run} criteria={['ACMG_AMP_2015:PVS1']}/>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'render.cjs'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const { render } = createRequire(import.meta.url)(path.join(output, 'render.cjs'));
for (const [name, run] of [...Object.entries(fixtures).map(([k, f]) => [k, f.saved]), ...Object.entries(invalid)]) check('actual SSR / full serialization ' + name, () => {
    const html = render(run);assert.ok(html.includes('下载本次原始结果 JSON'));
    if (invalid[name]) { assert.ok(html.includes('保存的 PVS1'));assert.ok(!html.includes('实际强度：')); }
    else {
        assert.ok(html.includes('经典位点预测后果'));assert.ok(html.includes('RNA 实验后果'));
        assert.ok(html.includes(name === 'NMD' ? 'VERY_STRONG' : 'STRONG'));
        fs.writeFileSync(path.join(output, name + '.html'), html);
        fs.writeFileSync(path.join(output, name + '-expected-export.json'), JSON.stringify(run, null, 2));
    }
    assert.deepEqual(JSON.parse(JSON.stringify(run, null, 2)), run);
});
write('validation.json', { status: 'PASS', count: checks.length, checks, actualDownload: 'NOT_RUN', scientificExecutions: 0 });
console.log(JSON.stringify({ status: 'PASS', count: checks.length }));
if (mode === '--serve') {
    const run = fixtures.NMD.saved, damaged = invalid['evidence label'];
    await build({ stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';import {SavedResult} from ${JSON.stringify(component)};const good=${JSON.stringify(run)},bad=${JSON.stringify(damaged)};function App(){const [broken,setBroken]=React.useState(false);return <><h1>TEST_ONLY PVS1 剪接结果只读验收</h1><button onClick={()=>setBroken(!broken)}>{broken?'恢复完整记录':'模拟剪接标签损坏'}</button><SavedResult run={broken?bad:good} criteria={['ACMG_AMP_2015:PVS1']}/></>;}createRoot(document.getElementById('root')).render(<App/>);`, resolveDir: project, loader: 'jsx' },
        bundle: true, platform: 'browser', format: 'iife', outfile: path.join(output, 'replay.js'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
    const server = http.createServer((req, res) => {
        const record = { method: req.method, url: req.url, at: new Date().toISOString() };
        let body, type, status;
        if (req.method !== 'GET') { status = 405;body = 'READ_ONLY';type = 'text/plain'; }
        else if (req.url === '/') { status = 200;body = '<!doctype html><meta charset="utf-8"><div id="root"></div><script src="/replay.js"></script>';type = 'text/html'; }
        else if (req.url === '/replay.js') { status = 200;body = fs.readFileSync(path.join(output, 'replay.js'));type = 'text/javascript'; }
        else { status = 404;body = 'NOT_FOUND';type = 'text/plain'; }
        fs.appendFileSync(path.join(output, 'http.jsonl'), JSON.stringify({ ...record, status }) + '\n');
        res.writeHead(status, { 'Content-Type': type + '; charset=utf-8', 'Cache-Control': 'no-store' });res.end(body);
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    write('server.json', { origin: `http://127.0.0.1:${server.address().port}`, pid: process.pid });
    console.log(`http://127.0.0.1:${server.address().port}`);
}
