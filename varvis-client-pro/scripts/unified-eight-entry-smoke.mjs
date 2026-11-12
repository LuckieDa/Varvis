import frontendModuleUrl from './frontend-module-url.cjs';
// Consumes actual synthetic HTTP/H2 output; renders the real component without executing science.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { buildSync } from 'esbuild';
const [destination, fixtureRoot] = process.argv.slice(2);
if (!destination || !fixtureRoot) throw Error('Provide isolated output and backend fixture folder');
const project = fileURLToPath(new URL('../', import.meta.url)), output = path.resolve(destination);
if (output.startsWith(project)) throw Error('Use isolated output');
fs.mkdirSync(output, { recursive: true });
const source = fs.readFileSync(path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js'), 'utf8');
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`));
const options = JSON.parse(fs.readFileSync(path.join(fixtureRoot, 'options.json')));
const saved = JSON.parse(fs.readFileSync(path.join(fixtureRoot, 'saved.json')));
const original = JSON.stringify(saved), clone = v => JSON.parse(JSON.stringify(v));
const ids = ['BA1', 'BS1', 'PM2', 'PP3', 'BP4', 'PS1', 'PM5', 'BP7'].map(c => `ACMG_AMP_2015:${c}`);
const bundle = path.join(output, 'component.cjs');
buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import Panel,{SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const render=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);
export const download=run=>{let click;const walk=e=>{if(!React.isValidElement(e))return;if(e.props.children==='下载本次原始结果 JSON')click=e.props.onClick;React.Children.forEach(e.props.children,walk);};walk(SavedResult({run,criteria:run.enabledCriteria}));if(!click)throw Error('Missing download');click();};`, resolveDir: project, loader: 'jsx' },
bundle: true, platform: 'node', format: 'cjs', outfile: bundle, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const { render, download } = createRequire(import.meta.url)(bundle);
const results = [];
const test = (name, fn) => { fn(); results.push({ name, status: 'PASS' }); console.log(`PASS ${name}`); };
test('actual eight-rule HTTP identity and all six-module projections accepted', () => {
    assert.equal(api.localBatchScopeIssue(options), null); assert.deepEqual(api.currentBusinessCriteria(options), ids);
    assert.ok(api.localBatchSavedRun(saved)); api.validateSavedRun(saved, saved.analysisId);
    for (const c of saved.candidates) {
        const cards = api.evidenceCards(c, ids, undefined, undefined, saved);
        assert.deepEqual(cards.map(c => c.id), ids);
        assert.ok(cards[0].entries.some(e => e.evaluation?.evaluationResult.status === 'MET'));
        for (const a of c.attempts) assert.equal(api.localBatchAttemptIssue(c, a, ids, saved.executionProfile), null);
    }
});
test('eight-rule declaration preserves explicit choices and remains one body', () => {
    const inputs = api.prepareSimplifiedInputs(options);
    const declaration = api.buildSimplifiedCaseDeclaration(options, options.analysisId, inputs, undefined, 'SYNTHETIC');
    assert.equal(declaration.declarationVersion, 'ordinary-unified-local-eight-v1');
    assert.equal(declaration.topN, 2); assert.equal(declaration.recommendations.length, 2);
    assert.equal(Object.hasOwn(declaration, 'executionProfile'), false);
    fs.writeFileSync(path.join(output, 'declaration.json'), JSON.stringify(declaration, null, 2));
});
test('old declaration and altered component bindings do not authorize eight-rule submission', () => {
    for (const mutate of [v => { v.executionProfile.requiredDeclaration = 'ordinary-local-computational-batch1-v1'; },
        v => { delete v.executionProfile.moduleBindings.bp7; }, v => { delete v.executionProfile.bs1RuntimeBinding; },
        v => { v.executionProfile.ba1RuntimeBinding = {}; }, v => { v.executionProfile.populationExecutionProfile.profileVersion = '4.0.0'; },
        v => { v.executionProfile.enabledCriteria.pop(); }, v => { v.executionProfile.formalEnabled = true; }]) {
        const bad = clone(options); mutate(bad); assert.ok(api.localBatchScopeIssue(bad)); assert.deepEqual(api.currentBusinessCriteria(bad), []);
        assert.throws(() => api.buildSimplifiedCaseDeclaration(bad, bad.analysisId, api.prepareSimplifiedInputs(bad)));
    }
});
test('incompatible saved profile cannot authorize retry; exact profile can', () => {
    assert.ok(api.localBatchRetryEligible(saved, saved.analysisId, options));
    const changed = clone(options); changed.executionProfile.manifestSha256 = 'a'.repeat(64);
    assert.equal(api.localBatchRetryEligible(saved, saved.analysisId, changed), false);
});
test('actual saved component renders eight sections, local scope and separate missing-material reasons', () => {
    const html = render(saved);
    for (const phrase of ['BA1 / BS1 / PM2 / PP3 / BP4 / PS1 / PM5 / BP7', '同一次运行包含八条证据',
        '未评价不是阴性', '下载本次原始结果 JSON']) assert.ok(html.includes(phrase), phrase);
    assert.ok(!html.includes('保存的本地证据身份或部分结果不完整'));
    assert.ok(html.includes('PM2 在同次运行中独立核验'));
    assert.ok(!html.includes('PM2 暂不执行'));
    fs.writeFileSync(path.join(output, 'saved-component.html'), html);
});
test('damaged local result refuses local scientific display without losing independent population cards', () => {
    const bad = clone(saved); bad.candidates[0].attempts[0].localBatch.criteria.pop();
    const cards = api.evidenceCards(bad.candidates[0], ids, undefined, undefined, bad);
    assert.ok(cards.slice(2).every(c => c.entries[0].displayIssue));
    assert.ok(cards[0].entries.some(e => e.evaluation?.evaluationResult.status === 'MET'));
});
test('real export handler preserves entire saved JSON including unknown fields', () => {
    const value = clone(saved); value.future = { unknown: [false, null, '中文'] };
    const previous = { Blob: globalThis.Blob, document: globalThis.document, timer: globalThis.setTimeout, create: URL.createObjectURL, revoke: URL.revokeObjectURL };
    let payload, clicked = false; const anchor = { click() { clicked = true; } };
    try {
        globalThis.Blob = class { constructor(parts) { payload = parts.join(''); } };
        globalThis.document = { createElement: () => anchor }; globalThis.setTimeout = fn => fn();
        URL.createObjectURL = () => 'blob:unified-test'; URL.revokeObjectURL = () => {};
        download(value); assert.ok(clicked); assert.deepEqual(JSON.parse(payload), value);
        assert.equal(anchor.download, `acmg-${value.runId}.json`);
    } finally { globalThis.Blob = previous.Blob; globalThis.document = previous.document; globalThis.setTimeout = previous.timer; URL.createObjectURL = previous.create; URL.revokeObjectURL = previous.revoke; }
});
assert.equal(JSON.stringify(saved), original);
fs.writeFileSync(path.join(output, 'validation.json'), JSON.stringify({ groups: results.length, results,
    provenance: 'synthetic backend output and actual frontend component; not a user-downloaded file' }, null, 2));
