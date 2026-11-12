import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { buildPs2ComponentChecks } from './ps2-business-component-checks.mjs';

// Isolated archive transport: no backend, database, scientific program or forwarding.
const project = fileURLToPath(new URL('../', import.meta.url));
const checkout = path.resolve(project, '..');
const task = path.join(checkout, 'local/temp/PS2-ui-business-20260928');
const handoff = path.join(checkout, 'local/development/handoffs/PS2-ui-business-20260928');
const selfCheck = process.argv.includes('--self-check');
assert.ok(process.argv.slice(2).every(x => x === '--self-check'));
fs.mkdirSync(task, { recursive: true });
const output = fs.mkdtempSync(path.join(task, selfCheck ? 'self-' : 'browser-'));
const write = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const read = f => JSON.parse(fs.readFileSync(f, 'utf8').replace(/^\uFEFF/, ''));
const sha = b => crypto.createHash('sha256').update(b).digest('hex');
const sourceFiles = ['src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
    'src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx', 'src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js',
    'scripts/ps2-business-browser-smoke.mjs', 'scripts/ps2-business-component-checks.mjs'];
write('source-before.json', sourceFiles.map(file => {
    const raw = fs.readFileSync(path.join(project, file)); fs.writeFileSync(path.join(output, path.basename(file)), raw);
    return { file, bytes: raw.length, sha256: sha(raw) };
}));
const require = createRequire(path.join(project, 'package.json'));
write('tools.json', { node: process.version, executable: process.execPath, realpath: fs.realpathSync(process.execPath), argv: process.argv,
    dependencies: ['esbuild', 'react', 'react-dom', 'eslint'].map(name => ({ name, path: fs.realpathSync(require.resolve(name)) })) });
const manifest = read(path.join(handoff, 'source-index.json'));
assert.equal(manifest.files.length, 70);
for (const f of manifest.files) {
    for (const file of [f.source, f.copy]) { const bytes = fs.readFileSync(path.toNamespacedPath(file)); assert.equal(bytes.length, f.bytes); assert.equal(sha(bytes), f.sha256); }
}
write('source-originals-verified.json', { count: 70, digest: 'RAW_BYTES_SHA256', originalsReadOnly: true, files: manifest.files });
const original = (group, role) => { const f = manifest.files.find(x => x.group === group && x.role === role); assert.ok(f, group + '/' + role); return read(f.copy); };
const fixtures = Object.fromEntries(manifest.scenarios.map(s => [s.group, {
    options: original(s.group, 'HTTP_INPUT_OPTIONS_200'), saved: original(s.group, 'HTTP_REBUILT_GET_200'),
    request: original(s.group, s.group === 'FAILED_RETRY_ALPHA' ? 'EXPLICIT_RETRY_REQUEST' : 'EXPLICIT_CONFIRMATION_REQUEST'),
    failed: s.group === 'FAILED_RETRY_ALPHA' ? original(s.group, 'HTTP_EXACT_ORIGINAL_REUSE_FAILED_200') : null,
}]));
const coreOnly = ['CORE_ONLY_MODERATE', 'CORE_ONLY_NOT_MET'].map(group => ({ group, result: original(group, 'CORE_RESULT_NOT_SAVED_RUN') }));
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const helper = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const h = await import(frontendModuleUrl('data:text/javascript;base64,' + fs.readFileSync(helper).toString('base64')));
const checks = [];
const check = (name, fn) => { fn(); checks.push(name); write('contract-progress.json', checks); };
// Independent backend-rule mirror for mutations only; these are not backend materials.
const canonical = v => typeof v === 'string' ? v.normalize('NFC') : Array.isArray(v) ? v.map(canonical)
    : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map(k => [k.normalize('NFC'), canonical(v[k])])) : v;
const digest = v => sha(JSON.stringify(canonical(v)));
const a = fixtures.NORMAL_ALPHA;
for (const [name, f] of Object.entries(fixtures)) {
    check(name + ' intact identity and exact request (no normalization)', () => {
        const before = JSON.stringify(f); assert.equal(h.ps2ScopeIssue(f.options, f.options.analysisId), null);
        assert.deepEqual(h.currentBusinessCriteria(f.options), ['ACMG_AMP_2015:PS2']);
        assert.deepEqual(h.buildPs2Declaration(f.options, f.options.analysisId, true, f.failed || undefined), f.request);
        assert.ok(h.ps2SavedRun(f.saved)); assert.equal(JSON.stringify(f), before);
    });
    check(name + ' explicit confirmation and legacy entry guards', () => {
        for (const confirmed of [false, undefined, 'true', 1]) assert.throws(() => h.buildPs2Declaration(f.options, f.options.analysisId, confirmed));
        assert.throws(() => h.buildSimplifiedCaseDeclaration(f.options, f.options.analysisId, {}));
        assert.throws(() => h.buildCaseDeclaration(f.options, f.options.analysisId, {}, {}));
        assert.throws(() => h.prepareSimplifiedInputs(f.options));
    });
}
const mutations = [
    ['profile', o => { o.executionProfile.profileVersion = '2.0.0'; }], ['declaration', o => { o.requiredDeclaration = 'ordinary-bp1-missense-v1'; }],
    ['scope', o => { o.acceptanceScope = 'UNKNOWN'; }], ['missing preparation', o => { delete o.ps2Preparation; }], ['missing profile', o => { delete o.executionProfile; }],
    ['mixed candidates', o => { o.candidates = []; }], ['mixed criteria', o => { o.enabledCriteria.push('ACMG_AMP_2015:BP1'); }],
    ['analysis', o => { o.analysisId = fixtures.UNKNOWN_ALPHA.options.analysisId; }], ['sample', o => { o.sampleId += 'x'; }],
    ['candidate', o => { o.candidateIds[0] += 'x'; }], ['pin', o => { o.executionProfile.ps2MaterialPins.bundleRawSha256 = '0'.repeat(64); }],
    ['binding', o => { o.ps2Preparation.materialBinding.familyId += 'x'; }], ['patient', o => { o.ps2Preparation.materialBinding.context.patientContext.sex = 'FEMALE'; }],
    ['case', o => { o.ps2Selection.caseId += 'x'; }], ['observation', o => { o.ps2Selection.observationId += 'x'; }], ['occurrence', o => { o.ps2Selection.occurrenceId += 'x'; }],
    ['token', o => { o.ps2Selection.preparationToken = fixtures.UNKNOWN_ALPHA.options.ps2Selection.preparationToken; }],
    ['tx injection', o => { o.ps2Selection.transcript = {}; }], ['result injection', o => { o.ps2Selection.result = 'MET'; }], ['preconfirmed', o => { o.ps2Selection.confirmed = true; }],
    ['float', o => { o.ps2Preparation.publishedInputIdentity.extra = 0.5; }], ['unsafe integer', o => { o.ps2Preparation.materialBinding.context.variant.inputAllele.position = 9007199254740992; }],
];
for (const [name, mutate] of mutations) check('reject ' + name, () => { const o = structuredClone(a.options); mutate(o); assert.ok(h.ps2ScopeIssue(o, a.options.analysisId)); assert.deepEqual(h.currentBusinessCriteria(o), []); assert.throws(() => h.buildPs2Declaration(o, a.options.analysisId, true)); });
const vectors = [
    [{ z: ['Cafe\u0301', { 'e\u0301': 'A\u030a' }, null, true, 7], a: 'Cafe\u0301' }, '{"a":"Café","z":["Café",{"é":"Å"},null,true,7]}'],
    [{ 'é': 1, z: 2, 'e\u0301x': 3 }, '{"éx":3,"z":2,"é":1}'], [{}, '{}'], [{ familyContextId: null }, '{"familyContextId":null}'],
];
for (const [input, bytes] of vectors) check('fixed canonical bytes ' + bytes, () => { assert.equal(h.ps2IdentityHash(input), sha(bytes)); });
check('NFD remains intact while matching existing NFC identity', () => {
    const o = structuredClone(a.options); o.ps2Preparation.materialBinding.context.disease.label = o.ps2Preparation.materialBinding.context.disease.label.normalize('NFD');
    const before = JSON.stringify(o); assert.equal(h.ps2ScopeIssue(o), null); assert.ok(h.buildPs2Declaration(o, o.analysisId, true)); assert.equal(JSON.stringify(o), before);
});
check('v3 full patient projection and absent transcript fixed original identity', () => {
    const c = a.options.ps2Preparation.materialBinding.context;
    const projection = { domain: 'varvis-evidence-context-v3', schemaVersion: c.schemaVersion, dataClass: c.dataClass, scope: c.scope,
        alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId, moi: c.moi, patientContext: c.patientContext };
    assert.equal(digest(projection), '51db03e66ade2631474f66dc0b1128efd6cc0177a9ae122ec13aee488c78ac82');
    assert.equal(h.ps2IdentityHash(projection), digest(projection));
    for (const mutate of [v => { delete v.patientContext.affectedStatus; }, v => { v.patientContext.familyContextId = null; }, v => { delete v.patientContext.familyContextId; }, v => { v.transcript = null; }]) {
        const v = structuredClone(projection); mutate(v); assert.notEqual(h.ps2IdentityHash(v), digest(projection));
    }
    write('independent-identity-vectors.json', { fixedCanonicalBytes: vectors, originalV3Projection: projection, expected: c.contextId, provenance: 'FIXED_ORIGINAL_BACKEND_CONTEXT_AND_LITERAL_UTF8_VECTORS' });
});
check('standalone core and criterion name do not grant business capability', () => { assert.equal(h.ps2ScopeClaim(coreOnly[0].result), false); assert.equal(h.ps2ScopeClaim({ enabledCriteria: ['ACMG_AMP_2015:PS2'] }), false); });
for (const status of ['FAILED', 'COMPLETED_WITH_BLOCKERS', 'RUNNING', 'COMPLETED']) check('retry state ' + status, () => {
    const r = { ...fixtures.FAILED_RETRY_ALPHA.failed, status };
    assert.equal(h.ps2RetryEligible(r, r.analysisId), ['FAILED', 'COMPLETED_WITH_BLOCKERS'].includes(status));
    if (!h.ps2RetryEligible(r, r.analysisId)) assert.throws(() => h.buildPs2Declaration(fixtures.FAILED_RETRY_ALPHA.options, r.analysisId, true, r));
    assert.equal(h.ps2RetryEligible(r, a.options.analysisId), false);
});
for (const [name, mutate] of [
    ['artifact run', r => { r.bundles[0].runId += 'x'; }], ['candidate', r => { r.candidates[0].candidateId += 'x'; }],
    ['core', r => { r.candidates[0].attempts[0].ps2.deNovoPoints = 0; }], ['criterion', r => { r.candidates[0].attempts[0].criterionEvaluations = []; }],
    ['profile', r => { delete r.executionProfile; }], ['context', r => { r.bundles[0].preparation.materialBinding.context.patientContext.sex = 'FEMALE'; }],
    ['mixed attempt', r => { r.candidates[0].attempts[0].bp1 = {}; }], ['raw pin', r => { r.bundles[0].coreRawSha256 = 'bad'; }],
]) check('saved rejects ' + name, () => { const r = structuredClone(a.saved); mutate(r); assert.ok(h.ps2ScopeClaim(r)); assert.equal(h.ps2SavedRun(r), false); });
write('contract-validation.json', { status: 'PASS', count: checks.length, checks, requestComparison: 'FULL_JSON_NO_EXCLUSIONS_NO_NORMALIZATION', scientificExecution: 0 });

await build({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import {SavedResult} from ${JSON.stringify(component)};import Matrix from ${JSON.stringify(path.join(path.dirname(component), 'AcmgEvidenceMatrix.jsx'))};export const render=(run,criteria=[])=>renderToStaticMarkup(<SavedResult run={run} criteria={criteria}/>);export const renderCore=ps2=>renderToStaticMarkup(<><p>CORE_RESULT_ONLY</p><Matrix candidate={{candidateId:'CORE_RESULT_ONLY',attempts:[{ps2}]}} enabledCriteria={['ACMG_AMP_2015:PS2']}/></>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'render.cjs'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning',
    plugins: [{ name: 'export-test-component', setup(api) { api.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, () => ({ contents: fs.readFileSync(component, 'utf8') + '\nexport {SavedResult};', loader: 'jsx', resolveDir: path.dirname(component) })); } }] });
const renderer = require(path.join(output, 'render.cjs'));
const renders = [];
for (const [name, core, html] of [...Object.entries(fixtures).map(([name, f]) => [name, f.saved.candidates[0].attempts[0].ps2, renderer.render(f.saved, ['ACMG_AMP_2015:BA1'])]),
    ...coreOnly.map(f => [f.group, f.result, renderer.renderCore(f.result)])]) {
    const card = h.ps2EvidenceEntry(core), e = card.evaluation;
    assert.ok(html.includes('PS2')); assert.ok(html.includes(e.evaluationResult.reasonCode)); assert.ok(html.includes(h.reasonHelp(e.evaluationResult.reasonCode)));
    assert.ok(html.includes(e.appliedStrength || '实际强度：未记录')); assert.equal(card.details.find(([k]) => k === '本次积分')[1], core.deNovoPoints ?? '未给出');
    assert.ok(!html.includes('需要 CSpec / 人工复核处理')); fs.writeFileSync(path.join(output, name + '.html'), html);
    renders.push({ name, status: e.evaluationResult.status, points: core.deNovoPoints ?? null, strength: e.appliedStrength ?? null });
}
const malformed = structuredClone(a.saved); malformed.bundles[0].coreRawSha256 = 'bad';
assert.ok(!renderer.render(malformed, ['ACMG_AMP_2015:PS2']).includes('实际强度：'));
// Presentation-only reason control, never a reissued material or saved backend run.
const ftControl = structuredClone(fixtures.UNKNOWN_ALPHA.saved.candidates[0].attempts[0].ps2);
ftControl.evaluationResult.reasonCode = 'PS2_RAW_FT_CONFLICT';
ftControl.criterionEvaluations[0].evaluationResult.reasonCode = 'PS2_RAW_FT_CONFLICT';
const ftHtml = renderer.renderCore(ftControl);
assert.ok(ftHtml.includes('PS2_RAW_FT_CONFLICT') && ftHtml.includes('原始样本 FT 与结构化过滤事实冲突，未计分。'));
assert.ok(ftHtml.includes('实际强度：未记录'));
assert.equal(h.ps2EvidenceEntry(ftControl).details.find(([key]) => key === '本次积分')[1], '未给出');
fs.writeFileSync(path.join(output, 'FRONTEND_REASON_CONTROL_FT.json'), JSON.stringify(ftControl, null, 2));
fs.writeFileSync(path.join(output, 'FRONTEND_REASON_CONTROL_FT.html'), ftHtml);
write('render-validation.json', { status: 'PASS', actualReactServerComponents: true, renders, malformedDoesNotShowConclusion: true,
    ftReasonControl: { status: 'PASS', attribution: 'FRONTEND_PRESENTATION_ONLY_NOT_BACKEND_ORIGINAL', noPointsOrStrengthInvented: true } });
await buildPs2ComponentChecks({ project, output, fixtures, build });
const entry = `import React,{useState} from 'react';import {createRoot} from 'react-dom/client';import Panel from ${JSON.stringify(component)};
function Replay(){const [scene,setScene]=useState('NORMAL_ALPHA'),[status,setStatus]=useState('TEST_ONLY archive transport');const ids=${JSON.stringify(Object.fromEntries(Object.entries(fixtures).map(([k, f]) => [k, f.saved.analysisId])))};
async function control(route,body){const r=await fetch('/__test/'+route,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});setStatus(JSON.stringify(await r.json()));}
return <><aside style={{padding:16,background:'#fff3cd'}}><h1>TEST_ONLY PS2 · 真实普通页面</h1><p>单家系归档回放；无科学程序、后端或数据库执行。实际下载待独立接纳后由用户手动完成。</p>
<label>归档场景 <select value={scene} onChange={e=>setScene(e.target.value)}>{Object.keys(ids).map(k=><option key={k}>{k}</option>)}</select></label>{' '}
<button onClick={()=>control('control',{delayNext:true})}>延迟下一次提交响应</button>{' '}<button onClick={()=>control('release',{})}>释放延迟响应</button>{' '}
<button onClick={()=>control('control',{history:true})}>UNKNOWN 当前 options503</button>{' '}<button onClick={()=>control('control',{history:false})}>恢复 UNKNOWN options</button>{' '}
<button onClick={()=>control('state')}>读取请求计数</button><pre>{status}</pre></aside><Panel analysisId={ids[scene]}/></>};createRoot(document.getElementById('root')).render(<Replay/>);`;
fs.writeFileSync(path.join(output, 'entry.jsx'), entry);
const bundle = await build({ stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'browser', format: 'iife', outfile: path.join(output, 'app.js'), loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' }, metafile: true, logLevel: 'warning' });
write('bundle-inputs.json', bundle.metafile.inputs);
for (const suffix of ['OrdinaryAcmgEvaluationPanel.jsx', 'AcmgEvidenceMatrix.jsx', 'services/variantAnalysis.js']) assert.ok(Object.keys(bundle.metafile.inputs).some(x => x.replaceAll('\\', '/').endsWith(suffix)));
write('assets.json', ['entry.jsx', 'app.js', 'component-checks.js'].map(f => ({ file: f, bytes: fs.statSync(path.join(output, f)).size, rawSha256: sha(fs.readFileSync(path.join(output, f))) })));

const state = { history: false, readOnly: false, delayNext: false }, completed = new Set(), journal = [], pending = [];
let origin;
const snapshot = () => ({ ...state, postCount: journal.filter(e => e.kind === 'POST_RUN').length, acceptedPosts: journal.filter(e => e.kind === 'POST_RUN' && e.status === 200).length,
    pending: pending.length, scientificExecution: 0, output });
function finish() { pending.splice(0).forEach(p => p.send()); write('summary.json', snapshot()); server.close(); server.closeAllConnections(); }
const server = http.createServer(async (req, res) => {
    const event = { at: new Date().toISOString(), executionClass: selfCheck ? 'HTTP_SELF_CHECK' : 'BROWSER_REPLAY', method: req.method, url: req.url, kind: 'OTHER' };
    const reply = (status, value, type = 'application/json', delay = false) => {
        event.status = status; journal.push(event); fs.appendFileSync(path.join(output, 'requests.jsonl'), JSON.stringify(event) + '\n');
        const send = () => { if (!res.destroyed) { res.writeHead(status, { 'Content-Type': type + '; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(type === 'application/json' ? JSON.stringify(value) : value); } };
        if (delay) { state.delayNext = false; const p = { send: () => { clearTimeout(p.timer); const i = pending.indexOf(p); if (i >= 0) pending.splice(i, 1); send(); } }; p.timer = setTimeout(p.send, 120000); pending.push(p); } else send();
    };
    try {
        if (req.headers.host !== new URL(origin).host || (req.headers.origin && req.headers.origin !== origin) || req.headers['sec-fetch-site'] === 'cross-site') return reply(403, { message: 'TEST_ONLY_LOOPBACK_ORIGIN_REQUIRED' });
        const url = new URL(req.url, origin);
        res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'");
        if (req.method === 'GET' && ['/', '/component-tests'].includes(url.pathname)) return reply(200, '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>PS2 TEST_ONLY</title><div id="root"></div><script src="/' + (url.pathname === '/' ? 'app.js' : 'component-checks.js') + '"></script></html>', 'text/html');
        if (req.method === 'GET' && ['/app.js', '/component-checks.js'].includes(url.pathname)) return reply(200, fs.readFileSync(path.join(output, url.pathname.slice(1))), 'text/javascript');
        let body;
        if (req.method === 'POST') { assert.ok(req.headers['content-type']?.startsWith('application/json')); const chunks = []; let size = 0; for await (const chunk of req) { size += chunk.length; assert.ok(size < 2e6); chunks.push(chunk); } body = JSON.parse(Buffer.concat(chunks).toString()); event.body = body; }
        if (url.pathname.startsWith('/__test/')) event.kind = 'CONTROL';
        if (url.pathname === '/__test/state' && req.method === 'GET') return reply(200, snapshot());
        if (url.pathname === '/__test/control' && req.method === 'POST') { for (const k of ['readOnly', 'history', 'delayNext']) if (typeof body[k] === 'boolean') state[k] = body[k]; return reply(200, snapshot()); }
        if (url.pathname === '/__test/release' && req.method === 'POST') { pending.splice(0).forEach(p => p.send()); return reply(200, snapshot()); }
        if (url.pathname === '/__test/component-results' && req.method === 'POST') { event.kind = 'COMPONENT_TEST_RECEIPT'; write('component-validation.json', body); return reply(200, { saved: true }); }
        if (url.pathname === '/__test/stop' && req.method === 'POST') { reply(200, snapshot()); setTimeout(finish, 50); return; }
        if (url.pathname.startsWith('/__test/')) return reply(405, { message: 'TEST_ONLY_METHOD_OR_ROUTE' });
        const m = /^\/variant-analyses\/([^/]+)\/acmg\/(input-options|runs)(?:\/([^/]+))?$/.exec(url.pathname);
        const pair = m && Object.entries(fixtures).find(([, f]) => f.saved.analysisId === m[1]);
        if (!pair) return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
        const [name, f] = pair;
        if (req.method === 'GET' && m[2] === 'input-options') { event.kind = 'GET_OPTIONS';
            if ((state.history && name === 'UNKNOWN_ALPHA') || name === 'BETA_SUPPORTING') return reply(503, { message: 'TEST_ONLY_CURRENT_OPTIONS_UNAVAILABLE' });
            if (url.searchParams.get('topN') !== '1') return reply(400, { message: 'TEST_ONLY_TOP1_REQUIRED' }); return reply(200, f.options); }
        if (req.method === 'GET' && m[2] === 'runs') { event.kind = m[3] ? 'GET_RUN' : 'GET_RUNS';
            const rows = f.failed && !completed.has(name) && !state.readOnly ? [f.failed] : [f.saved, ...(f.failed ? [f.failed] : [])];
            if (!m[3]) return reply(200, rows.map(r => ({ runId: r.runId, status: r.status, createdAt: r.createdAt })));
            const r = rows.find(r => r.runId === m[3]); return r ? reply(200, r) : reply(404, { message: 'TEST_ONLY_UNKNOWN_RUN' }); }
        if (req.method === 'POST' && m[2] === 'runs' && !m[3]) { event.kind = 'POST_RUN'; const id = journal.filter(e => e.kind === 'POST_RUN').length + 1;
            write('request-' + id + '-incoming.json', body); write('request-' + id + '-expected.json', f.request);
            assert.ok(!state.readOnly && name !== 'BETA_SUPPORTING' && !(state.history && name === 'UNKNOWN_ALPHA'), 'NO_CURRENT_PERMISSION');
            assert.deepEqual(body, f.request); write('request-' + id + '-comparison.json', { status: 'PASS', fullJson: true, excludedFields: [], normalizedFields: [], expected: 'ARCHIVED_BACKEND_REQUEST', scene: name });
            completed.add(name); return reply(200, f.saved, undefined, state.delayNext); }
        return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
    } catch (e) { return reply(400, { message: 'TEST_ONLY_CONTRACT_REJECTED: ' + e.message }); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); origin = 'http://127.0.0.1:' + server.address().port;
write('server.json', { origin, output, pid: process.pid, testOnly: true, browserStatus: 'NOT_RUN', startedAt: new Date().toISOString() });
console.log(JSON.stringify({ origin, output, pid: process.pid }));
if (!selfCheck) { process.once('SIGINT', finish); process.once('SIGTERM', finish); }
else {
    try {
        const post = (route, body) => fetch(origin + route, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        assert.equal((await fetch(origin)).status, 200); assert.equal((await fetch(origin, { headers: { Origin: 'https://invalid.example' } })).status, 403);
        assert.equal((await fetch(origin + '/__test/control')).status, 405);
        for (const [name, f] of Object.entries(fixtures)) {
            const route = '/variant-analyses/' + f.saved.analysisId + '/acmg';
            assert.equal((await fetch(origin + route + '/input-options?topN=1')).status, name === 'BETA_SUPPORTING' ? 503 : 200);
            if (name === 'BETA_SUPPORTING') continue;
            assert.equal((await fetch(origin + route + '/input-options?topN=20')).status, 400);
            if (f.failed) assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.failed.runId)).json(), f.failed);
            const response = await post(route + '/runs', h.buildPs2Declaration(f.options, f.options.analysisId, true, f.failed || undefined));
            assert.equal(response.status, 200); assert.deepEqual(await response.json(), f.saved);
            assert.equal((await post(route + '/runs', { ...f.request, extra: true })).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved);
        }
        assert.equal((await post('/__test/control', { history: true })).status, 200);
        const unknownRoute = '/variant-analyses/' + fixtures.UNKNOWN_ALPHA.saved.analysisId + '/acmg';
        assert.equal((await fetch(origin + unknownRoute + '/input-options?topN=1')).status, 503);
        assert.equal((await post(unknownRoute + '/runs', fixtures.UNKNOWN_ALPHA.request)).status, 400);
        assert.equal((await post('/__test/control', { readOnly: true })).status, 200);
        for (const f of Object.values(fixtures)) { const route = '/variant-analyses/' + f.saved.analysisId + '/acmg'; assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved); assert.equal((await post(route + '/runs', f.request)).status, 400); }
        assert.equal((await post('/__test/release', {})).status, 200);
        write('http-validation.json', { status: 'PASS', ...snapshot(), allReadOnlyHistoriesMatchFullJson: true, explicitControls: true, realDownloads: 'NOT_RUN_USER_MANUAL_ONLY' });
        assert.equal((await post('/__test/stop', {})).status, 200);
    } catch (e) { write('http-failure.json', { message: e.message, stack: e.stack }); finish(); throw e; }
}
