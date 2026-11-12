import frontendModuleUrl from './frontend-module-url.cjs';
// Actual synthetic backend outputs + real React export handler. No scientific or business requests.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { buildSync } from 'esbuild';
const [destination, fixtures, stopLossFixture] = process.argv.slice(2);
if (!destination || !fixtures || !stopLossFixture) throw Error('Provide isolated output, backend artifact root and stop-loss result');
const project = fileURLToPath(new URL('../', import.meta.url)), output = path.resolve(destination);
if (output.startsWith(project)) throw Error('Use isolated output');
fs.mkdirSync(output, { recursive: true });
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(fs.readFileSync(path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js'))).toString('base64')}`));
const load = (folder, file) => JSON.parse(fs.readFileSync(path.join(fixtures, folder, file)));
const saved = load('eleven-business', 'saved.json'), options = load('eleven-business', 'options.json');
const runs = [saved, ...['STOP', 'FRAMESHIFT', 'SPLICE_NMD', 'SPLICE_ESCAPE'].map(c => load(`pvs1-${c}`, 'saved-public.json')),
    ...['NO_KNOWN_FUNCTION', 'KNOWN_FUNCTION', 'MISSING'].map(c => load(`bp3-business-${c}`, 'saved-public.json'))];
const originals = JSON.stringify(runs), clone = v => JSON.parse(JSON.stringify(v));
const ids = ['BA1', 'BS1', 'PM2', 'PP3', 'BP4', 'PS1', 'PM5', 'BP7', 'PVS1', 'PM4', 'BP3'].map(c => `ACMG_AMP_2015:${c}`);
const bundle = path.join(output, 'component.cjs');
buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const render=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);
export const download=run=>{let click;const walk=e=>{if(!React.isValidElement(e))return;if(e.props.children==='下载本次原始结果 JSON')click=e.props.onClick;React.Children.forEach(e.props.children,walk);};walk(SavedResult({run,criteria:run.enabledCriteria}));if(!click)throw Error('Missing download');click();};`, resolveDir: project, loader: 'jsx' },
bundle: true, platform: 'node', format: 'cjs', outfile: bundle, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const { render, download } = createRequire(import.meta.url)(bundle), results = [];
const test = (name, fn) => { try { fn(); results.push({ name, status: 'PASS' }); } catch (e) { results.push({ name, status: 'FAIL', message: e.message.slice(0, 4000) }); } console.log(`${results.at(-1).status} ${name}`); };
const validAttempts = r => { assert.equal(api.localBatchScopeIssue(r), null); assert.ok(api.localBatchSavedRun(r)); api.validateSavedRun(r, r.analysisId);
    for (const c of r.candidates) for (const a of c.attempts) assert.equal(api.localBatchAttemptIssue(c, a, r.enabledCriteria, r.executionProfile), null); };
const first = (r, code) => r.candidates[0].attempts[0].localBatch.criteria.find(c => c.criterionId === `ACMG_AMP_2015:${code}`);
test('eleven exact scope and all actual saved attempts accepted', () => {
    assert.deepEqual(api.currentBusinessCriteria(options), ids); validAttempts(saved);
    for (const c of saved.candidates) assert.deepEqual(api.evidenceCards(c, ids, undefined, undefined, saved).map(c => c.id), ids);
});
test('one declaration binds eleven scope and preserved recommendations', () => {
    const body = api.buildSimplifiedCaseDeclaration(options, options.analysisId, api.prepareSimplifiedInputs(options), undefined, 'SYNTHETIC');
    assert.equal(body.declarationVersion, 'ordinary-unified-local-eleven-v1'); assert.equal(body.recommendations.length, 2);
    assert.equal(Object.hasOwn(body, 'executionProfile'), false); fs.writeFileSync(path.join(output, 'declaration.json'), JSON.stringify(body, null, 2));
});
test('partial flags, old declaration, altered scope or version refuse current authorization', () => {
    for (const mutate of [v => { v.executionProfile.requiredDeclaration = 'ordinary-unified-local-eight-v1'; },
        v => { delete v.executionProfile.moduleBindings.pvs1; }, v => { delete v.executionProfile.moduleBindings.bp3; },
        v => { v.executionProfile.moduleBindings.bp3.runtimeBinding.bp3Configured = false; },
        v => { v.executionProfile.moduleBindings.pm4.runtimeBinding.pm4PolicyVersion = '99.0.0'; },
        v => { v.executionProfile.moduleBindings.pvs1.runtimeBinding.pvs1SelectedVersion = '0.0.0'; },
        v => { v.enabledCriteria.pop(); }, v => { v.executionProfile.classificationEnabled = true; }]) {
        const bad = clone(options); mutate(bad); assert.ok(api.localBatchScopeIssue(bad)); assert.deepEqual(api.currentBusinessCriteria(bad), []);
        assert.throws(() => api.buildSimplifiedCaseDeclaration(bad, bad.analysisId, api.prepareSimplifiedInputs(bad)));
    }
});
for (let i = 1; i < runs.length; i++) test(`actual module business output ${i} accepted and rendered`, () => {
    const r = runs[i]; validAttempts(r); const html = render(r);
    assert.ok(!html.includes('保存的本地证据身份或部分结果不完整'));
    assert.ok(html.includes(i <= 4 ? 'PVS1机制与材料' : 'BP3功能适用'));
    fs.writeFileSync(path.join(output, `actual-${i}.html`), html);
});
test('PVS1 knowledge, sequence, prediction and duplicate-use limits cannot be corrupted', () => {
    for (const mutate of [f => { f.knowledgeStatus = 'UNREVIEWED'; }, f => { delete f.selectedTranscript; },
        f => { f.exonOrdinal = f.exonTotal + 1; }, f => { f.ptcEnd++; },
        f => { f.clinicalValidationCompleted = true; }, f => { f.doubleCountingConstraint = 'COMBINE'; },
        f => { f.nmdPrediction = 'UNRESOLVED'; }]) {
        const r = clone(runs[1]); mutate(first(r, 'PVS1').details.pvs1Facts);
        assert.ok(api.localBatchAttemptIssue(r.candidates[0], r.candidates[0].attempts[0], r.enabledCriteria, r.executionProfile));
    }
    const r = clone(runs[3]); first(r, 'PVS1').details.pvs1Facts.spliceAssessment.interpretation = 'OBSERVED_RNA';
    assert.ok(api.localBatchAttemptIssue(r.candidates[0], r.candidates[0].attempts[0], r.enabledCriteria, r.executionProfile));
});
test('BP3 positive needs consistent independent functional coverage and supporting strength', () => {
    for (const mutate of [c => { c.details.bp3Facts.functionAbsenceInferredFromNoHit = true; },
        c => { c.details.bp3Facts.regionState = 'UNKNOWN'; }, c => { c.details.bp3Facts.repeatObservation = 'INCOMPLETE'; },
        c => { c.details.bp3Facts.functionalLeadCount = -1; }, c => { c.appliedStrength = 'STRONG'; },
        c => { c.details.bp3Facts.queryRange.end = 0; }, c => { delete c.details.bp3Facts; }]) {
        const r = clone(runs[5]); mutate(first(r, 'BP3'));
        assert.ok(api.localBatchAttemptIssue(r.candidates[0], r.candidates[0].attempts[0], r.enabledCriteria, r.executionProfile));
    }
});
test('cross-criterion facts cannot masquerade as another scientific result', () => {
    const r = clone(runs[1]); first(r, 'PM2').details.pvs1Facts = clone(first(r, 'PVS1').details.pvs1Facts);
    assert.ok(api.localBatchAttemptIssue(r.candidates[0], r.candidates[0].attempts[0], r.enabledCriteria, r.executionProfile));
});
test('stop-loss actual core facts render separately; sequence consistency tampering rejected', () => {
    const raw = JSON.parse(fs.readFileSync(stopLossFixture));
    const c = { criterionId: 'ACMG_AMP_2015:PM4', status: raw.status, appliedStrength: raw.evaluation.appliedStrength,
        reasonCode: raw.reasonCode, reason: raw.reasonCode, details: { proteinLengthFacts: raw.publicSummary } };
    assert.ok(api.pm4ProteinLengthDetails(c).some(row => row.join('').includes('终止丢失')));
    for (const change of [f => { f.alteredProteinLength++; }, f => { f.newTerminationResolved = false; },
        f => { f.observedRnaOrProtein = true; }, f => { f.qualificationBasis = 'REVIEWED_COVERAGE'; },
        f => { f.regionState = 'NON_REPEAT_SUPPORTED'; }, f => { f.inspectedThreePrimeBases = 100001; }]) {
        const bad = clone(c); change(bad.details.proteinLengthFacts); assert.deepEqual(api.pm4ProteinLengthDetails(bad), []);
    }
});
test('eleven actual component count and history wording; no automatic combination claim', () => {
    const html = render(saved); assert.ok(html.includes('同一次运行包含十一条证据')); assert.ok(!html.includes('同一次运行包含八条证据'));
    assert.ok(html.includes('未评价不是阴性')); fs.writeFileSync(path.join(output, 'eleven.html'), html);
    const changed = clone(options); changed.executionProfile.manifestSha256 = 'a'.repeat(64);
    assert.equal(api.localBatchRetryEligible(saved, saved.analysisId, changed), false);
    assert.equal(api.localBatchRetryEligible(saved, saved.analysisId, options), true);
});
test('real export handler preserves every actual run and unknown fields, including corrupt records', () => {
    const prev = { Blob: globalThis.Blob, document: globalThis.document, timer: globalThis.setTimeout, create: URL.createObjectURL, revoke: URL.revokeObjectURL };
    try {
        let payload, clicked; const anchor = { click() { clicked = true; } };
        globalThis.Blob = class { constructor(parts) { payload = parts.join(''); } }; globalThis.document = { createElement: () => anchor };
        globalThis.setTimeout = fn => fn(); URL.createObjectURL = () => 'blob:eleven-test'; URL.revokeObjectURL = () => {};
        for (const run of runs) { const value = clone(run); value.future = { unknown: [false, null, '中文'] }; clicked = false;
            download(value); assert.ok(clicked); assert.deepEqual(JSON.parse(payload), value); assert.equal(anchor.download, `acmg-${value.runId}.json`); }
        const broken = clone(runs[1]); delete first(broken, 'PVS1').details.pvs1Facts; download(broken); assert.deepEqual(JSON.parse(payload), broken);
    } finally { globalThis.Blob = prev.Blob; globalThis.document = prev.document; globalThis.setTimeout = prev.timer; URL.createObjectURL = prev.create; URL.revokeObjectURL = prev.revoke; }
});
assert.equal(JSON.stringify(runs), originals);
fs.writeFileSync(path.join(output, 'validation.json'), JSON.stringify({ groups: results.length, results, provenance: 'Synthetic backend output, actual component and export handler; not user download or scientific adoption' }, null, 2));
if (results.some(r => r.status !== 'PASS')) process.exitCode = 1;
