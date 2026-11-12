// Verify the real SavedResult component using a synthetic H2/HTTP result, never a patient result.
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { build } from 'esbuild';
const [input, destination] = process.argv.slice(2);
if (!input || !destination) throw new Error('Supply saved synthetic result and unique output directory');
const project = path.resolve(import.meta.dirname, '..'), output = path.resolve(destination);
fs.mkdirSync(output, { recursive: false });
const run = JSON.parse(fs.readFileSync(input, 'utf8')), original = JSON.stringify(run);
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const utils = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const helper = utils;
const expose = { name: 'saved-result', setup(b) {
    b.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, () => ({ contents: fs.readFileSync(component, 'utf8') + '\nexport {SavedResult};', loader: 'jsx', resolveDir: path.dirname(component) }));
} };
await build({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from ${JSON.stringify(component)};
export {evidenceCards} from ${JSON.stringify(utils)};export {bs1ParameterSupplyDetails} from ${JSON.stringify(helper)};
export const saved=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'checks.cjs'), plugins: [expose], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const api = createRequire(import.meta.url)(path.join(output, 'checks.cjs'));
const entries = run.candidates.flatMap((c) => api.evidenceCards(c, ['ACMG_AMP_2015:BS1'])[0].entries);
const entry = entries.find((e) => e.details.some(([label]) => label === '患病率资料'));
assert.ok(entry); assert.equal(entry.evaluation.evaluationResult.status, 'NOT_EVALUATED');
assert.match(JSON.stringify(entry.details), /已有数值候选，尚未核定采用/);
assert.match(JSON.stringify(entry.details), /本次固定来源中未取得合适参数/);
assert.match(JSON.stringify(entry.details), /未确认/);
const gap = run.candidates.flatMap((c) => c.attempts).flatMap((a) => a.bs1ContextPreparation?.policyOptions || [])
    .map((o) => o.knowledgeGap).find((g) => g?.parameterSupply);
assert.ok(gap);
const states = ['SOURCE_GAP', 'IDENTITY_UNRESOLVED', 'CONFLICT', 'SOFTWARE_UNSUPPORTED', 'INVALID_SOURCE_DATA', 'REVIEW_REQUIRED', 'CANDIDATE_AVAILABLE'];
for (const state of states) {
    const g = structuredClone(gap); g.parameterSupply.fields.prevalence.status = state;
    assert.ok(api.bs1ParameterSupplyDetails(g, g.background).some(([label]) => label === '患病率资料'));
}
for (const fault of ['SCOPE', 'VERSION', 'APPROVAL', 'COUNT', 'FIELD', 'SOURCE', 'IDENTITY']) {
    const g = structuredClone(gap), s = g.parameterSupply;
    if (fault === 'SCOPE') s.scope.mondoId = 'MONDO:0999002';
    if (fault === 'VERSION') s.schemaVersion = 'unknown';
    if (fault === 'APPROVAL') s.scientificallyAdopted = true;
    if (fault === 'COUNT') s.fields.prevalence.evidenceCount = 99;
    if (fault === 'FIELD') delete s.fields.penetrance;
    if (fault === 'SOURCE') s.fields.prevalence.evidence[0].source.sha256 = 'bad';
    if (fault === 'IDENTITY') delete g.knowledgeIdentity.parameterSupplyVersion;
    assert.match(JSON.stringify(api.bs1ParameterSupplyDetails(g, g.background)), /未使用该摘要/);
}
const legacy = structuredClone(gap); delete legacy.parameterSupply;
assert.match(JSON.stringify(api.bs1ParameterSupplyDetails(legacy, legacy.background)), /没有逐字段摘要.*不代表资料不存在/);
const html = api.saved(run);
assert.match(html, /已保存的证据结果/); assert.match(html, /患病率资料/); assert.match(html, /尚未核定采用/);
assert.equal(JSON.stringify(run), original);
assert.deepEqual(JSON.parse(JSON.stringify(run, null, 2)), run);
fs.writeFileSync(path.join(output, 'saved.html'), html);
await build({ stdin: { contents: `import React from 'react';import {createRoot} from 'react-dom/client';import {SavedResult} from ${JSON.stringify(component)};
const run=${original};createRoot(document.getElementById('root')).render(<SavedResult run={run} criteria={run.enabledCriteria}/>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'browser', format: 'iife', outfile: path.join(output, 'page.js'), plugins: [expose], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
fs.writeFileSync(path.join(output, 'index.html'), '<!doctype html><meta charset="utf-8"><title>BS1 参数资料解释核验</title><body style="padding:24px;background:#fafafa"><p>合成保存结果只读回放：不是患者结果，无业务提交及科学执行。</p><div id="root"></div><script src="page.js"></script>');
fs.writeFileSync(path.join(output, 'component-checks.json'), JSON.stringify({ status: 'PASS', stateCases: 7, rejectionCases: 7,
    savedComponentRendered: true, legacyFallback: true, completeSerializationEqual: true, originalUnmodified: true, browserRun: false }, null, 2));
console.log('BS1 parameter supply: saved component, seven states, seven damaged summaries, legacy and complete JSON checks PASS.');
