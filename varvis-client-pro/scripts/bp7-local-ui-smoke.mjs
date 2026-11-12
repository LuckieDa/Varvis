import frontendModuleUrl from './frontend-module-url.cjs';
// Saved public fixture + actual React components. No backend or scientific execution.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { buildSync } from 'esbuild';

const [destination, fixture, optionsFixture] = process.argv.slice(2);
if (!destination || !fixture || !optionsFixture) throw Error('Provide isolated output, saved public JSON and matching input options');
const project = fileURLToPath(new URL('../', import.meta.url)), output = path.resolve(destination);
if (output.startsWith(project)) throw Error('Use isolated output');
fs.mkdirSync(output, { recursive: true });
const raw = fs.readFileSync(path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js'), 'utf8');
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`));
const saved = JSON.parse(fs.readFileSync(fixture)), options = JSON.parse(fs.readFileSync(optionsFixture));
const original = JSON.stringify(saved), ids = saved.enabledCriteria, clone = v => JSON.parse(JSON.stringify(v));
const bundle = path.join(output, 'component.cjs');
buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const render=(run,criteria)=>renderToStaticMarkup(<SavedResult run={run} criteria={criteria}/>);
export const download=(run)=>{let click;const walk=e=>{if(!React.isValidElement(e))return;if(e.props.children==='下载本次原始结果 JSON')click=e.props.onClick;React.Children.forEach(e.props.children,walk);};walk(SavedResult({run,criteria:run.enabledCriteria}));if(!click)throw Error('No download');click();};`, resolveDir: project, loader: 'jsx' },
bundle: true, platform: 'node', format: 'cjs', outfile: bundle, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const { render, download } = createRequire(import.meta.url)(bundle);
const results = [];
function test(name, run) { run(); results.push({ name, status: 'PASS' }); console.log(`PASS ${name}`); }
const cards = r => r.candidates.flatMap(c => api.evidenceCards(c, r.enabledCriteria, undefined, undefined, r));
test('exact six-rule current scope and all actual saved attempts accepted', () => {
    assert.equal(api.localBatchScopeIssue(options), null); assert.deepEqual(api.currentBusinessCriteria(options), ids);
    assert.equal(api.localBatchSavedRun(saved), true); api.validateSavedRun(saved, saved.analysisId);
    for (const c of saved.candidates) for (const a of c.attempts) assert.equal(api.localBatchAttemptIssue(c, a, ids, saved.executionProfile), null);
});
test('five-rule version, six-rule identity, binding and authorization mismatch refuse submission', () => {
    for (const change of [v => { v.executionProfile.profileVersion = '1.1.0'; }, v => { v.enabledCriteria.pop(); },
        v => { delete v.executionProfile.moduleBindings.bp7; }, v => { v.executionProfile.moduleBindings.bp7.runtimeBinding.clinicalApproval = true; },
        v => { v.executionProfile.moduleBindings.bp7.runtimeBinding.policySha256 = 'bad'; },
        v => { v.executionProfile.moduleBindings.bp7.runtimeBinding.positionVersion = '2'; },
        v => { v.executionProfile.classificationEnabled = true; }, v => { v.executionProfile.enabledCriteria.reverse(); }]) {
        const bad = clone(options); change(bad); assert.ok(api.localBatchScopeIssue(bad)); assert.deepEqual(api.currentBusinessCriteria(bad), []);
    }
});
test('new preparation builds an explicit declaration with BP7 and no fabricated server bindings', () => {
    const inputs = api.prepareSimplifiedInputs(options);
    for (const c of options.candidates) {
        const r = c.recommendation;
        inputs[c.candidateId].stableRelationshipKey = r.relationships.find(x => x.mondoId === 'MONDO:0007269' && x.selectable).stableRelationshipKey;
        inputs[c.candidateId].annotationId = r.annotations.find(x => x.versionedTranscriptId === 'ENST00000368300.9').annotationId;
    }
    const value = api.buildSimplifiedCaseDeclaration(options, options.analysisId, inputs, undefined, 'SYNTHETIC');
    assert.ok(value.hypotheses.length); assert.match(value.hypotheses[0].transcript.selectionReason, /BP7/);
    assert.equal(Object.hasOwn(value, 'executionProfile'), false);
    assert.equal(JSON.stringify(options), JSON.stringify(JSON.parse(fs.readFileSync(optionsFixture))));
});
test('actual BP7 results ignore stale legacy missing-input disposition', () => {
    const bp7 = cards(saved).filter(c => c.code === 'BP7').flatMap(c => c.entries);
    assert.equal(bp7.length, 8); assert.equal(bp7.filter(e => e.evaluation?.evaluationResult.status === 'MET').length, 4);
    assert.equal(bp7.filter(e => api.criterionLabel(e.evaluation) === '不适用').length, 2);
    assert.equal(bp7.filter(e => api.criterionLabel(e.evaluation) === '需复核 · 未采用').length, 2);
    assert.ok(bp7.every(e => !e.displayIssue)); assert.ok(bp7.every(e => !JSON.stringify(e.details).includes('EXPLICIT_INPUT_MISSING')));
});
test('actual component shows transcript, distances, experiment conflict and scope with history readable', () => {
    const html = render(saved, ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1']);
    for (const s of ['PM2 / PP3 / BP4 / PS1 / PM5 / BP7', '需复核 · 未采用', '不适用', 'ENST00000368300.9',
        '供体侧 8；受体侧 315', '距末端 0', 'eriksson-2003-lmna-g608g', '没有额外运行模型', '未收录不等于实验正常', '下载本次原始结果 JSON']) assert.ok(html.includes(s), s);
    assert.ok(!html.includes('保存的本地证据身份或部分结果不完整'));
    assert.ok(html.includes('按保存时的本地证据范围完整展示'));
    assert.ok(!html.includes('当前页面仅展示当前实例启用的规则'));
    fs.writeFileSync(path.join(output, 'saved-real.html'), html);
});
test('damaged BP7 status, strength, policy, conflicts and clinical claims hide affected attempt only', () => {
    for (const mutate of [c => { c.appliedStrength = 'STRONG'; }, c => { c.details.bp7.coreStatus = 'NOT_APPLICABLE'; },
        c => { c.details.bp7.policySha256 = 'b'.repeat(64); }, c => { c.details.bp7.reviewRequired = true; },
        c => { c.details.bp7.experimentalNormalityEstablished = true; }, c => { c.details.bp7.clinicalAdoption = true; },
        c => { c.details.bp7.positionStatus = 'EXCLUDED'; }, c => { c.details.bp7.matchedRecordIds.push('unexpected-conflict'); },
        c => { delete c.details.bp7; }, c => { c.details.bp7.sharedPredictionResultId = ''; }]) {
        const bad = clone(saved), row = bad.candidates.find(c => c.attempts[0].localBatch.criteria.at(-1).status === 'MET');
        const a = row.attempts[0]; mutate(a.localBatch.criteria.at(-1));
        assert.ok(api.localBatchAttemptIssue(row, a, ids, bad.executionProfile));
        const before = JSON.stringify(bad), shown = api.evidenceCards(row, ids, undefined, undefined, bad);
        assert.ok(shown.every(c => c.entries[0].displayIssue)); assert.ok(shown.every(c => !c.entries[0].evaluation));
        assert.equal(JSON.stringify(bad), before); assert.ok(cards(bad).some(c => c.code === 'BP7' && c.entries.some(e => !e.displayIssue)));
    }
});
test('explicit missing background is unevaluated without invented geometry or conflict clearance', () => {
    const bad = clone(saved), row = bad.candidates[0], a = row.attempts[0];
    a.hypothesisId = null; a.context = null; a.localBatch.hypothesisId = null; a.localBatch.contextId = null;
    a.localBatch.criteria.forEach(c => { c.status = 'NOT_EVALUATED'; c.appliedStrength = 'NONE'; c.reasonCode = 'EXPLICIT_INPUT_MISSING'; c.reason = '本次未确认背景'; c.details = {}; });
    assert.equal(api.localBatchAttemptIssue(row, a, ids, bad.executionProfile), null);
    const b = api.evidenceCards(row, ids, undefined, undefined, bad).at(-1).entries[0];
    assert.equal(api.criterionLabel(b.evaluation), '未评价'); assert.ok(!b.details.some(([k]) => k === '内含子距离'));
});
test('not-met and unavailable summaries keep different meanings without manufacturing scores', () => {
    const c = clone(saved.candidates[4].attempts[0].localBatch.criteria.at(-1));
    c.status = 'NOT_MET'; c.appliedStrength = 'NONE'; c.reasonCode = 'BP7_SPLICE_NOT_BENIGN'; c.details.bp7.coreStatus = 'NOT_MET';
    assert.equal(api.criterionLabel({ criterionId: c.criterionId, evaluationResult: { status: c.status } }), '已评价 · 未满足');
    c.status = 'NOT_EVALUATED'; c.details.bp7.coreStatus = 'NOT_EVALUATED'; c.reasonCode = 'BP7_EXPERIMENT_SOURCE_UNAVAILABLE'; c.details.bp7.experimentCheckStatus = 'UNAVAILABLE';
    assert.match(JSON.stringify(api.bp7ResultDetails(c)), /不可用/);
});
test('actual download handler serializes full unchanged public JSON including unknown fields', () => {
    const value = clone(saved); value.futureField = { unknown: [null, false, '原文'], order: ['b', 'a'] };
    const previous = { Blob: globalThis.Blob, document: globalThis.document, timer: globalThis.setTimeout, create: URL.createObjectURL, revoke: URL.revokeObjectURL };
    let payload, clicked = false; const anchor = { click() { clicked = true; } };
    try {
        globalThis.Blob = class { constructor(parts) { payload = parts.join(''); } };
        globalThis.document = { createElement: () => anchor }; globalThis.setTimeout = fn => fn();
        URL.createObjectURL = () => 'blob:bp7-test'; URL.revokeObjectURL = () => {};
        download(value); assert.ok(clicked); assert.equal(anchor.download, `acmg-${value.runId}.json`); assert.deepEqual(JSON.parse(payload), value);
        fs.writeFileSync(path.join(output, 'expected-public-export.json'), JSON.stringify(saved, null, 2));
    } finally { globalThis.Blob = previous.Blob; globalThis.document = previous.document; globalThis.setTimeout = previous.timer; URL.createObjectURL = previous.create; URL.revokeObjectURL = previous.revoke; }
});
assert.equal(JSON.stringify(saved), original);
fs.writeFileSync(path.join(output, 'validation.json'), JSON.stringify({ status: 'PASS', groups: results.length, results, provenance: 'frontend fixture/component checks, not actual downloaded file' }, null, 2));
console.log(`${results.length} BP7 groups passed`);
