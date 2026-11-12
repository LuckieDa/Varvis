import frontendModuleUrl from './frontend-module-url.cjs';
// Actual synthetic Java business output; no provider or scientific execution.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { buildSync } from 'esbuild';

const [input, destination] = process.argv.slice(2);
if (!input || !destination || fs.existsSync(destination)) throw Error('Provide saved synthetic model result and NEW output directory');
const project = fileURLToPath(new URL('../', import.meta.url));
const output = path.resolve(destination);
if (output.startsWith(project)) throw Error('Output must be outside frontend sources');
fs.mkdirSync(output, { recursive: true });
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync(path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js')).toString('base64')}`));
const original = fs.readFileSync(input), run = JSON.parse(original), results = [];
const issue = r => api.localBatchAttemptIssue(r.candidates[0], r.candidates[0].attempts[0], r.enabledCriteria, r.executionProfile);
function test(name, action) { action(); results.push({ name, status: 'PASS' }); }
const bundle = path.join(output, 'component.cjs');
buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const render=r=>renderToStaticMarkup(<SavedResult run={r} criteria={r.enabledCriteria}/>);`, resolveDir: project, loader: 'jsx' },
bundle: true, platform: 'node', format: 'cjs', outfile: bundle, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const { render } = createRequire(import.meta.url)(bundle);
test('actual saved synthetic model result accepted', () => { assert.equal(api.localBatchScopeIssue(run), null); assert.equal(issue(run), null); });
test('actual component labels synthetic model honestly', () => {
    const html = render(run); assert.ok(html.includes('合成模型响应的工程验证'));
    assert.ok(!html.includes('大模型未配置')); assert.ok(!html.includes('受影响背景不展示科学结论'));
    fs.writeFileSync(path.join(output, 'synthetic-model.html'), html);
});
for (const name of ['confirmed', 'assumed', 'missing', 'literature', 'auto-supply']) {
    const file = path.resolve(path.dirname(input), '..', name, 'response.json');
    if (fs.existsSync(file)) test(`existing ${name} identity and explanation unchanged`, () => {
        const legacy = JSON.parse(fs.readFileSync(file));
        assert.equal(api.localBatchScopeIssue(legacy), null); assert.equal(issue(legacy), null);
        assert.ok(render(legacy).includes('未采用真实模型提取结果'));
    });
}
for (const [name, mutate] of [
    ['missing artifact binding', b => delete b.modelArtifactReplay],
    ['bad artifact pin', b => b.modelArtifactReplay.artifactIndexSha256 = 'bad'],
    ['implicit paid call', b => b.modelArtifactReplay.automaticModelCalls = true],
    ['patient transmission scope', b => b.modelArtifactReplay.sourceScope = 'PATIENT_REPORT'],
    ['fake research use', b => b.use = 'RESEARCH'],
    ['unverified model status', b => b.modelValidation = 'CLINICALLY_APPROVED'],
]) test(`reject ${name}`, () => {
    const r = structuredClone(run); mutate(r.executionProfile.moduleBindings.deNovo);
    assert.ok(api.localBatchScopeIssue(r));
});
test('pair cannot claim another model origin', () => {
    const r = structuredClone(run);
    const c = r.candidates[0].attempts[0].localBatch.criteria.find(x => x.criterionId === 'ACMG_AMP_2015:PS2');
    c.details.deNovoFacts.modelValidation = 'MODEL_CANDIDATES_SOURCE_REPLAYED_NOT_CLINICALLY_VALIDATED';
    assert.ok(issue(r));
});
test('specific missing and conflict explanations', () => {
    assert.match(api.reasonHelp('DENOVO_MODEL_CASE_ATTRIBUTION_UNRESOLVED', 'DENOVO'), /其他病例/);
    assert.match(api.reasonHelp('DENOVO_MODEL_ARTIFACT_NOT_AVAILABLE', 'DENOVO'), /自动付费/);
    assert.match(api.reasonHelp('DENOVO_MODEL_PHENOTYPE_SUBJECT_UNRESOLVED', 'DENOVO'), /先证者/);
});
test('entire unknown/private-safe public JSON retained', () => {
    assert.deepEqual(JSON.parse(JSON.stringify(run, null, 2)), JSON.parse(original));
    assert.deepEqual(fs.readFileSync(input), original);
});
fs.writeFileSync(path.join(output, 'validation.json'), JSON.stringify({ attribution: 'SYNTHETIC model artifacts; actual saved Java response and React SSR. No actual model, browser or download.', results }, null, 2));
console.log(`${results.length} model replay contract/render checks passed`);
