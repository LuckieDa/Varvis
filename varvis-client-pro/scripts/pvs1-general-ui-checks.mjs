import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import http from 'node:http';
import { createRequire } from 'node:module';
import { build } from 'esbuild';
import { buildPvs1GeneralComponents } from './pvs1-general-component-checks.mjs';

// Explicit pinned archives; no Java, database, upstream or scientific execution.
// node scripts/pvs1-general-ui-checks.mjs FIXTURES_JSON NEW_OUTPUT [--serve]
const [configPath, outputPath, mode] = process.argv.slice(2);
if (!configPath || !outputPath) throw new Error('FIXTURES_JSON and NEW_OUTPUT required');
const project = process.cwd(), output = path.resolve(outputPath);
fs.mkdirSync(output); // Never overwrite another validation round.
const write = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const manifest = JSON.parse(fs.readFileSync(configPath, 'utf8')), fixtures = {}, sources = [];
for (const [name, files] of Object.entries(manifest)) {
    fixtures[name] = {};
    for (const [role, pin] of Object.entries(files)) {
        const bytes = fs.readFileSync(pin.path); assert.equal(sha(bytes), pin.sha256, name + role);
        fixtures[name][role] = JSON.parse(bytes); sources.push({ name, role, ...pin, bytes: bytes.length });
    }
}
write('source-pins.json', sources);
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const helper = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const h = await import(frontendModuleUrl('data:text/javascript;base64,' + fs.readFileSync(helper).toString('base64')));
const results = [];
const check = (name, action) => { action(); results.push(name); };
const f = fixtures.CURRENT;
for (const [name, item] of Object.entries(fixtures)) {
    check(name + ' exact options and saved identity accepted', () => {
        assert.equal(h.pvs1NmdScopeIssue(item.options), null);
        assert.equal(h.pvs1NmdSavedIssue(item.saved), null);
        assert.equal(h.pvs1NmdSavedRun(item.saved), true);
        assert.deepEqual(h.currentBusinessCriteria(item.options), ['ACMG_AMP_2015:PVS1']);
    });
    check(name + ' exact server selection tokens and immutable input', () => {
        const before = JSON.stringify(item.options), inputs = h.prepareSimplifiedInputs(item.options);
        const body = h.buildSimplifiedCaseDeclaration(item.options, item.options.analysisId, inputs, undefined, 'SYNTHETIC');
        for (const actual of body.hypotheses) {
            const expected = item.request.hypotheses.find(v => v.hypothesis.candidateId === actual.hypothesis.candidateId);
            for (const k of ['candidateId', 'hypothesisId', 'hgncId', 'mondoId', 'moi']) assert.equal(actual.hypothesis[k], expected.hypothesis[k]);
            for (const k of ['accession', 'version', 'annotationSource', 'annotationSourceVersion', 'selectionPolicy']) assert.equal(actual.transcript[k], expected.transcript[k]);
        }
        assert.equal(JSON.stringify(item.options), before);
        inputs[item.options.candidateIds[0]].recommendationId = 'stale';
        assert.throws(() => h.buildSimplifiedCaseDeclaration(item.options, item.options.analysisId, inputs), /确认/);
    });
}
const optionMutations = [
    ['algorithm', o => { o.executionProfile.reviewedRuleBinding.algorithms['transcript-truncating-nmd-pvs1'] = '9.0.0'; }],
    ['context method', o => { o.executionProfile.reviewedRuleBinding.contextPreparation = 'UNSUPPORTED'; }],
    ['knowledge version', o => { o.executionProfile.reviewedRuleBinding.pvs1Knowledge.version = '9.0.0'; }],
    ['clinical claim', o => { o.executionProfile.reviewedRuleBinding.pvs1Knowledge.clinicalAcceptance = true; }],
    ['source pin', o => { o.executionProfile.reviewedRuleBinding.pvs1Knowledge.sourceCandidatesRawSha256 = ''; }],
    ['mixed criterion', o => { o.enabledCriteria.push('ACMG_AMP_2015:BA1'); }],
    ['different preparation binding', o => { o.executionProfile.ruleContextPreparationBinding.reviewedRuleBinding.bundleSha256 = 'f'.repeat(64); }],
    ['stale recommendation', o => { o.candidates[0].recommendation.ruleContextPreparationBinding.version = '9'; }],
];
for (const [name, mutate] of optionMutations) check('reject option ' + name, () => {
    const o = structuredClone(f.options); mutate(o);
    assert.ok(h.pvs1NmdScopeIssue(o)); assert.deepEqual(h.currentBusinessCriteria(o), []);
    assert.throws(() => h.buildSimplifiedCaseDeclaration(o, o.analysisId, h.prepareSimplifiedInputs(o)), /PVS1/);
    assert.throws(() => h.buildCaseDeclaration(o, o.analysisId, h.automaticCaseForm(o.analysisId, 'SYNTHETIC', o.requiredDeclaration), {}), /PVS1/);
});
const escapeOf = run => run.candidates.flatMap(c => c.attempts).find(a => a.pvs1?.resolvedInput?.escapeAssessment)?.pvs1;
const mutations = [
    ['policy hash', v => { v.policySha256 = 'f'.repeat(64); }],
    ['policy body', v => { v.policy.policyVersion = 'untrusted'; }],
    ['selection', v => { v.policySelection.policyId += 'other'; }],
    ['context', v => { v.evaluation.contextId += 'other'; }],
    ['model', v => { v.transcriptModelBinding.modelId += 'other'; }],
    ['evaluator', v => { v.evaluation.deterministicTrace.evaluatorVersion = '1.0.4'; }],
    ['bundle', v => { v.bundleIdentity.bundleId += 'other'; }],
    ['knowledge model', v => { v.knowledgeAssessment.adoption.modelSha256 = 'f'.repeat(64); }],
    ['escape review', v => { v.resolvedInput.escapeAssessment.reviewId += 'other'; }],
    ['strength', v => { v.evaluation.appliedStrength = 'VERY_STRONG'; }],
    ['rule', v => { v.resolvedInput.escapeAssessment.selectedRule.ruleId += 'other'; }],
    ['missing branch', v => { delete v.resolvedInput.escapeAssessment; }],
    ['wrong NMD', v => { v.resolvedInput.nmdPrediction = 'PREDICTED_NMD'; }],
    ['NE strength', v => { v.evaluation.evaluationResult.status = 'NOT_EVALUATED'; }],
];
const invalid = {};
for (const [name, mutate] of mutations) check('reject saved ' + name + ' without editing raw', () => {
    const run = structuredClone(f.saved); mutate(escapeOf(run)); const before = JSON.stringify(run);
    assert.ok(h.pvs1NmdSavedIssue(run)); assert.equal(h.pvs1NmdSavedRun(run), false);
    assert.equal(JSON.stringify(run), before); invalid[name] = run;
});
check('NMD strength must retain saved policy association', () => {
    const run = structuredClone(f.saved); run.candidates[2].attempts[0].pvs1.evaluation.appliedStrength = 'STRONG';
    assert.ok(h.pvs1NmdSavedIssue(run)); invalid['NMD strength'] = run;
});
for (const version of ['1.0.4', '1.1.0']) check('new policy cannot claim runtime ' + version, () => {
    const run = structuredClone(f.saved), b = run.executionProfile.reviewedRuleBinding;
    b.algorithms['transcript-truncating-nmd-pvs1'] = version;
    run.executionProfile.ruleContextPreparationBinding.reviewedRuleBinding = structuredClone(b);
    for (const c of run.candidates) for (const a of c.attempts) if (a.pvs1) a.pvs1.bundleIdentity = structuredClone(b);
    assert.ok(h.pvs1NmdSavedIssue(run)); invalid['runtime capability ' + version] = run;
});
check('exact object key order is irrelevant, but values remain exact', () => {
    const o = structuredClone(f.options), b = o.executionProfile.reviewedRuleBinding;
    o.executionProfile.ruleContextPreparationBinding.reviewedRuleBinding = Object.fromEntries(Object.entries(b).reverse());
    assert.equal(h.pvs1NmdScopeIssue(o), null);
});
check('saved branch metrics and research role do not infer absent science', () => {
    const entries = f.saved.candidates.map(c => h.evidenceCards(c, ['ACMG_AMP_2015:PVS1'])[0].entries[0]);
    assert.deepEqual(entries.map(e => e.evaluation.evaluationResult.status), ['NOT_EVALUATED', 'MET', 'MET']);
    const details = Object.fromEntries(entries[1].details);
    assert.match(details['政策核定角色'], /实施者.*未取得独立/);
    assert.match(details['参考尾段受影响 / 净蛋白缩短'], /^8 \/ 2 /);
    assert.ok(details['原 NMD 相关外显子 / 工程范围（不替代逃逸范围）']);
    assert.match(details['外显子相关性与人群耐受'], /UNKNOWN/);
    assert.match(h.reasonHelp('PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED', 'PVS1_NMD_V6'), /当前保存政策未启用/);
    assert.doesNotMatch(h.reasonHelp('PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED', 'PVS1_NMD_V6'), /PTEN|HNF1A|缺.*人口/);
});

await build({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from ${JSON.stringify(component)};export const render=run=>renderToStaticMarkup(<SavedResult run={run} criteria={['ACMG_AMP_2015:BA1']}/>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'render.cjs'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const { render } = createRequire(import.meta.url)(path.join(output, 'render.cjs'));
for (const [name, run] of [...Object.entries(fixtures).map(([k, v]) => [k, v.saved]), ...Object.entries(invalid)]) check('actual SSR / complete serialization ' + name, () => {
    const before = JSON.stringify(run), html = render(run);
    assert.ok(html.includes('下载本次原始结果 JSON')); assert.ok(html.includes(run.runId));
    if (invalid[name]) { assert.ok(html.includes('保存的 PVS1')); assert.ok(!html.includes('实际强度：')); }
    else { assert.ok(html.includes('VERY_STRONG')); assert.ok(!html.includes('>BA1<')); }
    if (name === 'CURRENT') { assert.ok(html.includes('8 / 2')); assert.ok(html.includes('实施者来源解释自验')); assert.ok(html.includes('当前保存政策未启用')); }
    const serialized = JSON.stringify(run, null, 2);
    assert.deepEqual(JSON.parse(serialized), run); assert.equal(JSON.stringify(run), before);
    if (fixtures[name]) { fs.writeFileSync(path.join(output, name + '.html'), html); fs.writeFileSync(path.join(output, name + '-export-expected.json'), serialized); }
});
write('contracts.json', { status: 'PASS', count: results.length, checks: results, actualDownload: 'NOT_RUN_USER_CHROME_REQUIRED', scientificExecutions: 0 });
console.log(JSON.stringify({ status: 'PASS', checks: results.length }));
await buildPvs1GeneralComponents({ project, output, fixtures, build });
const entry = `import React from 'react';import {createRoot} from 'react-dom/client';import Panel from ${JSON.stringify(component)};createRoot(document.getElementById('root')).render(<><h1>TEST_ONLY PVS1 原始历史只读回放</h1><p>本页复用已保存结果；无新后端或科学执行。实际下载交用户 Chrome。</p><Panel analysisId=${JSON.stringify(f.saved.analysisId)}/></>);`;
await build({ stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'browser', format: 'iife', outfile: path.join(output, 'replay.js'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const journal = [], server = http.createServer(async (req, res) => {
    const event = { method: req.method, url: req.url, at: new Date().toISOString() };
    const reply = (status, body, type = 'application/json') => { event.status = status; journal.push(event); fs.appendFileSync(path.join(output, 'http.jsonl'), JSON.stringify(event) + '\n'); res.writeHead(status, { 'Content-Type': type + '; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(type === 'application/json' ? JSON.stringify(body) : body); };
    if (req.method === 'POST' && req.url === '/__test/component-results') {
        const chunks = []; for await (const chunk of req) chunks.push(chunk);
        const report = JSON.parse(Buffer.concat(chunks)); write('components.json', report); return reply(200, { saved: true });
    }
    if (req.method !== 'GET') return reply(405, { error: 'READ_ONLY_NO_BUSINESS_POST' });
    if (req.url === '/checks' || req.url === '/') return reply(200, '<!doctype html><meta charset="utf-8"><div id="root"></div><script src="/' + (req.url === '/checks' ? 'component-checks.js' : 'replay.js') + '"></script>', 'text/html');
    if (['/component-checks.js', '/replay.js'].includes(req.url)) return reply(200, fs.readFileSync(path.join(output, req.url.slice(1))), 'text/javascript');
    if (req.url === '/__test/state') return reply(200, { businessPosts: journal.filter(e => /acmg\/runs/.test(e.url) && e.method === 'POST').length, scientificExecutions: 0 });
    if (req.url.includes('/acmg/input-options')) return reply(503, { message: 'TEST_ONLY_CURRENT_UNAVAILABLE_HISTORY_READ_ONLY' });
    if (req.url.endsWith('/acmg/runs/' + f.saved.runId)) return reply(200, f.saved);
    if (req.url.endsWith('/acmg/runs')) return reply(200, [f.saved]);
    return reply(404, { error: 'NOT_FOUND' });
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
write('server.json', { origin, pid: process.pid, mode: 'ARCHIVE_READ_ONLY_WITH_COMPONENT_MOCKS' });
console.log(origin);
const route = `/api/variant-analyses/${f.saved.analysisId}/acmg/runs`;
assert.deepEqual(await (await fetch(origin + route + '/' + f.saved.runId)).json(), f.saved);
assert.equal((await fetch(origin + route, { method: 'POST' })).status, 405);
write('http-selfcheck.json', { status: 'PASS', originalGetDeepEqual: true, rejectedTestPost: 1, actualBusinessPost: 0 });
if (mode !== '--serve') server.close();
else {
    const close = () => { write('server-close.json', { pid: process.pid, at: new Date().toISOString(), requests: journal.length }); server.closeAllConnections(); server.close(); };
    process.on('SIGINT', close); process.on('SIGTERM', close);
}
