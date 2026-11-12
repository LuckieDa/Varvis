import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

// Dedicated TEST_ONLY PM1 replay. No backend, database, scientific execution or forwarding.
const project = fileURLToPath(new URL('../', import.meta.url));
const checkout = path.resolve(project, '..');
const selfCheck = process.argv.includes('--self-check');
assert.ok(process.argv.slice(2).every((arg) => arg === '--self-check'));
const task = path.join(checkout, 'local/temp/PM1-ui-business-20260926');
const handoff = path.join(checkout, 'local/development/handoffs/PM1-ui-business-20260926');
fs.mkdirSync(task, { recursive: true });
const output = fs.mkdtempSync(path.join(task, selfCheck ? 'self-check-' : 'browser-'));
const write = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const manifest = JSON.parse(fs.readFileSync(path.join(handoff, 'original-sha256.json')));
assert.equal(manifest.count, 17); assert.equal(manifest.files.length, 17);
for (const record of manifest.files) {
    const original = fs.readFileSync(path.toNamespacedPath(record.path));
    const copy = fs.readFileSync(path.join(handoff, record.copiedPath));
    assert.equal(original.length, record.bytes); assert.equal(sha(original), record.sha256);
    assert.equal(sha(copy), record.sha256); assert.equal(record.copiedSha256, record.sha256);
}
write('original-sha256.json', manifest);
const read = relative => JSON.parse(fs.readFileSync(path.join(handoff, 'originals', relative)));
const normal = Object.fromEntries(['input-options', 'request', 'submit', 'get-after-store-service-rebuild', 'reuse', 'observed-counts'].map(n => [n, read('normal/' + n + '.json')]));
normal.saved = normal['get-after-store-service-rebuild']; normal.savedSource = 'ARCHIVED_GET_AFTER_STORE_SERVICE_REBUILD';
assert.equal(normal['input-options'].requestedN, 2); assert.equal(normal.request.topN, 2);
assert.deepEqual(normal.submit, normal.saved); assert.deepEqual(normal.reuse, normal.saved);
const material = { saved: read('material/submit.json'), savedSource: 'ARCHIVED_SUBMIT_SERVED_AS_TEST_GET_READ_ONLY' };
const fixtures = { NORMAL: normal, MATERIAL: material };
const providers = Object.fromEntries(fs.readdirSync(path.join(handoff, 'originals/provider')).map(n => [n.replace('.json',''), read('provider/' + n)]));
assert.equal(Object.keys(providers).length, 10);
for (const f of Object.values(fixtures)) assert.equal(f.saved.dataClass, 'SYNTHETIC');
write('source-before.json', ['src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx',
    'src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx',
    'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', 'src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js',
    'scripts/acmg-input-contract-smoke.mjs', 'scripts/pm1-business-browser-smoke.mjs'].map(p => ({ path: p, sha256: sha(fs.readFileSync(path.join(project, p))) })));
const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const helperPath = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const helpers = await import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync(helperPath).toString('base64')}`));
// Expected scientific/context identities come from Java's archived request, never this builder.
function validateRequest(body, f) {
    const archived = f.request;
    assert.deepEqual(Object.keys(body).sort(), ['dataClass', 'declarationVersion', 'hypotheses', 'patient', 'recommendations', 'sampleId', 'topN'].sort());
    for (const key of ['topN', 'declarationVersion', 'sampleId', 'dataClass']) assert.deepEqual(body[key], archived[key], key);
    assert.deepEqual(body.recommendations, archived.recommendations, 'server recommendation references');
    assert.equal(body.hypotheses.length, archived.hypotheses.length);
    assert.deepEqual(Object.keys(body.patient).sort(), ['familyContextId', 'patientContextId', 'role', 'sex', 'source']);
    const source = (s, locator) => assert.deepEqual(s, { source: 'EXPLICIT_USER_DECLARATION', version: '1.0.0', recordLocator: locator });
    // Allowed differences: UI privacy identifier and source labels, UNKNOWN affected status, PM1 confirmation prose.
    assert.equal(body.patient.role, 'PROBAND'); assert.equal(body.patient.sex, 'UNKNOWN'); assert.equal(body.patient.familyContextId, null);
    assert.equal(body.patient.patientContextId, `patient-context-${f['input-options'].analysisId}`);
    source(body.patient.source, `analysis/${f['input-options'].analysisId}/acmg/ordinary-pm1-region-v1`);
    body.hypotheses.forEach((actual, i) => {
        const expected = archived.hypotheses[i], rec = archived.recommendations[i];
        for (const key of ['candidateId', 'hypothesisId', 'hgncId', 'mondoId', 'moi']) assert.equal(actual.hypothesis[key], expected.hypothesis[key], key);
        for (const key of ['accession', 'version', 'selectionPolicy', 'annotationSource', 'annotationSourceVersion'])
            assert.equal(actual.transcript[key], expected.transcript[key], key);
        assert.equal(actual.affectedStatus, 'UNKNOWN');
        const locator = `recommendations/${rec.recommendationId}/selection`;
        source(actual.hypothesis.provenance, locator); source(actual.transcript.source, locator);
        assert.equal(actual.transcript.selectionReason,
            `User confirmed disease relationship, MOI and exact versioned transcript ${expected.transcript.accession}.${expected.transcript.version} for limited PM1 reviewed protein region evaluation. Model, region, qualification and policy remain verified by the server; no scientific adoption or final classification is authorized.`);
        assert.deepEqual(Object.keys(actual).sort(), ['affectedStatus', 'hypothesis', 'transcript']);
        assert.deepEqual(Object.keys(actual.hypothesis).sort(), Object.keys(expected.hypothesis).sort());
        assert.deepEqual(Object.keys(actual.transcript).sort(), Object.keys(expected.transcript).sort());
    });
}

const entry = `import React,{useState} from 'react'; import {createRoot} from 'react-dom/client';
import Panel from ${JSON.stringify(component)};
function Replay(){const [scenario,setScenario]=useState('NORMAL'); const [history,setHistory]=useState(false);
 const [status,setStatus]=useState('TEST_ONLY: 本回放只有 Top-2，请先把组件 Top-20 改为 2，再读取正式候选。');
 const ids=${JSON.stringify(Object.fromEntries(Object.entries(fixtures).map(([key, f]) => [key, f.saved.analysisId])))};
 async function control(route,body){const r=await fetch('/__test/'+route,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});setStatus(JSON.stringify(await r.json(),null,2));}
 return <><aside style={{padding:16,background:'#fff3cd'}}><h1>TEST_ONLY PM1 · 真实组件合成 HTTP</h1>
 <p>无 Java、数据库或科学执行，仅原 runId 的保存回执回放。仅支持 Top-2，不扩造候选。</p>
 <p>仅 NORMAL 有匹配 options/request；其余场景只有归档 submit，作为只读测试 GET，不允许提交。</p>
 <label>归档场景 <select value={scenario} onChange={e=>setScenario(e.target.value)}><option>NORMAL</option><option>MATERIAL</option></select></label>{' '}
 <button onClick={()=>control('control',{delayNext:true})}>延迟下一次 POST（最多20秒）</button>{' '}
 <button onClick={()=>control('release',{})}>释放延迟响应</button>{' '}
 <button onClick={()=>control('state')}>读取请求计数</button>{' '}
 <button onClick={async()=>{await control('control',{history:!history});setHistory(!history);}}>切换当前 options 可用性（之后读取正式候选）</button>
 <p>下载后可通过此控件核验真实下载文件（保存文件路径请另行记录）：</p><input aria-label="核验实际下载JSON" type="file" accept=".json" onChange={async e=>{const f=e.target.files?.[0];if(f){const bytes=await f.arrayBuffer();const rawSha256=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(b=>b.toString(16).padStart(2,'0')).join('');await control('compare-download',{fileName:f.name,rawSha256,value:JSON.parse(new TextDecoder().decode(bytes))});}}}/>
 <pre style={{whiteSpace:'pre-wrap'}}>{status}</pre></aside><Panel analysisId={ids[scenario]}/></>;}
createRoot(document.getElementById('root')).render(<Replay/>);`;
fs.writeFileSync(path.join(output, 'entry.jsx'), entry);
const bundle = await build({ stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true,
    platform: 'browser', format: 'iife', outfile: path.join(output, 'app.js'), loader: { '.js': 'jsx' },
    define: { 'process.env.NODE_ENV': '"production"' }, metafile: true, logLevel: 'warning' });
const imports = Object.keys(bundle.metafile.inputs).map((s) => s.replaceAll('\\', '/'));
for (const suffix of ['components/OrdinaryAcmgEvaluationPanel.jsx', 'components/AcmgEvidenceMatrix.jsx', 'services/variantAnalysis.js'])
    assert.ok(imports.some((s) => s.endsWith(suffix)));
write('bundle-inputs.json', imports);

// Actual saved panel and provider cards. Provider outputs are not fabricated saved runs.
await build({ stdin: { contents: `import React from 'react'; import {renderToStaticMarkup} from 'react-dom/server';
 import {SavedResult} from ${JSON.stringify(component)}; import Matrix from ${JSON.stringify(path.join(path.dirname(component), 'AcmgEvidenceMatrix.jsx'))}; export const render=(run,criteria=[])=>renderToStaticMarkup(<SavedResult run={run} criteria={criteria}/>); export const renderProvider=(value)=>renderToStaticMarkup(<section aria-label='TEST_ONLY provider output, not a saved run'><Matrix candidate={{candidateId:'TEST_ONLY_DISPLAY_ONLY',attempts:[{pm1:value}]}} enabledCriteria={['ACMG_AMP_2015:PM1']}/></section>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'render.cjs'), loader: { '.js': 'jsx' },
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning', plugins: [{ name: 'test-only-export', setup(api) {
        api.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, () => ({ contents: `${fs.readFileSync(component, 'utf8')}\nexport {SavedResult};`, loader: 'jsx', resolveDir: path.dirname(component) }));
    } }] });
const { render, renderProvider } = createRequire(import.meta.url)(path.join(output, 'render.cjs'));
const outcome = v => { const e=v.evaluation || v; return [e.evaluationResult.status, e.appliedStrength, e.evaluationResult.reasonCode]; };
const expected = {
    'plus-primary': ['MET','MODERATE','PM1_REVIEWED_REGION_MATCH'], 'minus-primary': ['MET','SUPPORTING','PM1_REVIEWED_REGION_MATCH'],
    'plus-adjacent': ['NOT_MET','NONE','PM1_COVERED_POSITION_NOT_SUPPORTED'], 'plus-excluded': ['NOT_MET','NONE','PM1_EXPLICIT_EXCLUSION'],
    COVERAGE_GAP: ['NOT_EVALUATED','NONE','PM1_REGION_COVERAGE_INCOMPLETE'], ORDINARY_DOMAIN: ['NOT_EVALUATED','NONE','PM1_ORDINARY_DOMAIN_NOT_QUALIFIED'],
    BENIGN_UNKNOWN: ['NOT_EVALUATED','NONE','PM1_DISTRIBUTION_UNKNOWN'], 'overlap-unresolved': ['NOT_EVALUATED','NONE','PM1_REGION_CONFLICT'],
    AUTHORITY_MISSING: ['NOT_EVALUATED',undefined,'PM1_POLICY_AUTHORITY_MISSING'], 'overlap-reviewed-lower-strength': ['MET','SUPPORTING','PM1_REVIEWED_REGION_MATCH'],
};
function checkCard(value, html) {
    const before = JSON.stringify(value), [status,strength,reason] = outcome(value);
    const entry = helpers.evidenceCards({ attempts: [{pm1:value}] }, ['ACMG_AMP_2015:PM1'])[0].entries[0];
    assert.deepEqual(entry.evaluation, value.evaluation || value); assert.deepEqual(entry.reasons, [reason]);
    const text = helpers.reasonHelp(reason);
    assert.notEqual(text, reason); assert.equal(helpers.evidenceSummary(entry), text);
    assert.ok(html.split(text).length - 1 >= 2, reason + ' summary AND reason list');
    assert.ok(html.includes(reason)); assert.ok(html.includes(strength ?? '未记录'));
    assert.ok(!html.includes('HNF1A')); assert.ok(!html.includes('GN017')); assert.ok(!html.includes('需要 CSpec / 人工复核处理'));
    if (status === 'MET') assert.ok(html.includes('PM1 满足'));
    assert.equal(JSON.stringify(value), before);
}
for (const [scenario, f] of Object.entries(fixtures)) {
    const before = JSON.stringify(f.saved), html = render(f.saved);
    assert.equal(f.saved.status, scenario === 'NORMAL' ? 'COMPLETED' : 'COMPLETED_WITH_BLOCKERS');
    assert.deepEqual(f.saved.candidates.map(c => outcome(c.attempts[0].pm1)), scenario === 'NORMAL'
        ? [expected['plus-primary'],expected['minus-primary']] : [['NOT_EVALUATED',undefined,'PM1_MODEL_SHA_MISMATCH'],expected['minus-primary']]);
    for (const c of f.saved.candidates) checkCard(c.attempts[0].pm1, html);
    for (const text of ['PM1','只读','科学采用','SUPPORTING']) assert.ok(html.includes(text), text);
    assert.ok(!html.includes('>PS1<')); assert.ok(!html.includes('>PM5<')); assert.equal(JSON.stringify(f.saved), before);
    const other = render(f.saved, ['ACMG_AMP_2015:BA1']); assert.ok(other.includes('>PM1<')); assert.ok(!other.includes('>BA1<'));
    fs.writeFileSync(path.join(output, scenario + '.html'), '<!doctype html><meta charset="utf-8">' + html);
}
for (const [name,value] of Object.entries(providers)) {
    const before=JSON.stringify(value), html=renderProvider(value);
    assert.deepEqual(outcome(value), expected[name]); checkCard(value, html); assert.equal(JSON.stringify(value),before);
    const details = Object.fromEntries(helpers.evidenceCards({attempts:[{pm1:value}]},['ACMG_AMP_2015:PM1'])[0].entries[0].details);
    if (value.resolvedInput?.selectedRule) assert.ok(details['命中区域或残基'].includes(value.resolvedInput.selectedRule.ruleId));
    if (name === 'overlap-reviewed-lower-strength') assert.ok(details['受审优先关系'].includes('higherRuleId'));
    fs.writeFileSync(path.join(output, name + '.html'), '<!doctype html><meta charset="utf-8">' + html);
}
write('render-validation.json', { status: 'PASS', actualComponents: true, savedScenarios: Object.keys(fixtures),
    providerCards: Object.keys(providers), providerIsSavedRun: false, immutableInput: true, currentCriteria: [], browserInteraction: 'NOT_RUN' });

const state = { history: false, delayNext: false }, journal = [], pending = [];
let origin;
const snapshot = () => ({ testOnly: true, ...state, postCount: journal.filter((e) => e.kind === 'POST_RUN').length,
    acceptedPostCount: journal.filter((e) => e.kind === 'POST_RUN' && e.status === 200).length, pending: pending.length,
    scientificExecution: 'NOT_RUN_ARCHIVE_REPLAY_ONLY', output });
function finish() { pending.splice(0).forEach((p) => p.send()); write('summary.json', snapshot()); server.close(); server.closeAllConnections(); }
const server = http.createServer(async (req, res) => {
    const event = { at: new Date().toISOString(), method: req.method, url: req.url, kind: 'OTHER' };
    res.setHeader('Cache-Control', 'no-store');
    res.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; frame-ancestors 'none'");
    const reply = (status, value, type = 'application/json', delay = false) => {
        event.status = status; journal.push(event); fs.appendFileSync(path.join(output, 'requests.jsonl'), JSON.stringify(event) + '\n');
        const send = () => { if (!res.destroyed) { res.writeHead(status, { 'Content-Type': `${type}; charset=utf-8` }); res.end(type === 'application/json' ? JSON.stringify(value) : value); } };
        if (delay) { state.delayNext = false; const p = { send: () => { clearTimeout(p.timer); const i = pending.indexOf(p); if (i >= 0) pending.splice(i, 1); send(); } }; p.timer = setTimeout(p.send, 20000); pending.push(p); }
        else send();
    };
    try {
        if (req.headers.host !== new URL(origin).host || (req.headers.origin && req.headers.origin !== origin) || req.headers['sec-fetch-site'] === 'cross-site')
            return reply(403, { message: 'TEST_ONLY_LOOPBACK_ORIGIN_REQUIRED' });
        const url = new URL(req.url, origin);
        if (req.method === 'GET' && url.pathname === '/') return reply(200, '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>PM1 TEST_ONLY</title><div id="root"></div><script src="/app.js"></script></html>', 'text/html');
        if (req.method === 'GET' && url.pathname === '/app.js') return reply(200, fs.readFileSync(path.join(output, 'app.js')), 'text/javascript');
        let body;
        if (req.method === 'POST') {
            assert.ok(req.headers['content-type']?.startsWith('application/json')); const chunks = []; let size = 0;
            for await (const chunk of req) { size += chunk.length; assert.ok(size <= 1024 * 1024); chunks.push(chunk); }
            body = JSON.parse(Buffer.concat(chunks).toString()); event.body = body;
        }
        if (url.pathname === '/__test/state' && req.method === 'GET') return reply(200, snapshot());
        if (url.pathname === '/__test/control' && req.method === 'POST') {
            if (typeof body.history === 'boolean') state.history = body.history;
            if (typeof body.delayNext === 'boolean') state.delayNext = body.delayNext;
            return reply(200, snapshot());
        }
        if (url.pathname === '/__test/release' && req.method === 'POST') { pending.splice(0).forEach((p) => p.send()); return reply(200, snapshot()); }
        if (url.pathname === '/__test/stop' && req.method === 'POST') { reply(200, snapshot()); setTimeout(finish, 50); return; }
        if (url.pathname === '/__test/compare-download' && req.method === 'POST') {
            const f = Object.values(fixtures).find((v) => v.saved.runId === body.value?.runId); assert.ok(f);
            assert.match(body.rawSha256, /^[a-f0-9]{64}$/); assert.equal(body.fileName, `acmg-${f.saved.runId}.json`);
            assert.deepEqual(body.value, f.saved); write(`download-comparison-${f.saved.runId}.json`, { status: 'PASS', runId: f.saved.runId,
                fileName: body.fileName, rawSha256: body.rawSha256, comparedAgainst: f.savedSource, input: selfCheck ? 'SELF_CHECK_INTERFACE_ONLY_NOT_DOWNLOAD' : 'BROWSER_FILE_PICKER_ACTUAL_DOWNLOAD' });
            return reply(200, { status: 'PASS', runId: f.saved.runId });
        }
        const match = /^\/variant-analyses\/([^/]+)\/acmg\/(input-options|runs)(?:\/([^/]+))?$/.exec(url.pathname);
        const f = match && Object.values(fixtures).find((v) => v.saved.analysisId === decodeURIComponent(match[1]));
        if (!f) return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
        if (req.method === 'GET' && match[2] === 'input-options') {
            event.kind = 'GET_OPTIONS';
            if (state.history || !f.request) return reply(503, { message: 'TEST_ONLY_CURRENT_PM1_UNAVAILABLE_OR_READ_ONLY_ARCHIVE' });
            if (url.searchParams.get('topN') !== '2') return reply(400, { message: 'TEST_ONLY_TOP2_REQUIRED：请把正式 Top-N 改为 2 后读取' });
            return reply(200, f['input-options']);
        }
        if (req.method === 'GET' && match[2] === 'runs') {
            event.kind = match[3] ? 'GET_RUN' : 'GET_RUNS'; event.savedSource = f.savedSource; const run = f.saved;
            if (match[3] && match[3] !== run.runId) return reply(404, { message: 'TEST_ONLY_UNKNOWN_RUN' });
            return reply(200, match[3] ? run : [{ runId: run.runId, status: run.status, createdAt: run.createdAt }]);
        }
        if (req.method === 'POST' && match[2] === 'runs' && !match[3]) {
            event.kind = 'POST_RUN'; assert.equal(state.history, false, 'Current PM1 scope unavailable');
            assert.ok(f.request, 'READ_ONLY_ARCHIVE_HAS_NO_MATCHING_OPTIONS_OR_REQUEST'); validateRequest(body, f);
            return reply(200, f.saved, undefined, state.delayNext);
        }
        return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
    } catch (error) { return reply(400, { message: `TEST_ONLY_CONTRACT_REJECTED: ${error.message}` }); }
});
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve)); origin = `http://127.0.0.1:${server.address().port}`;
write('server.json', { origin, output, pid: process.pid, topN: 2, testOnly: true, browserStatus: 'NOT_RUN' });
console.log(JSON.stringify({ origin, output, pid: process.pid }));
if (!selfCheck) { process.once('SIGINT', finish); process.once('SIGTERM', finish); }
else {
    try {
        assert.equal((await fetch(origin)).status, 200);
        for (const f of Object.values(fixtures)) {
            if (!f.request) {
                const route = `/variant-analyses/${f.saved.analysisId}/acmg`;
                assert.equal((await fetch(origin + route + '/input-options?topN=2')).status, 503);
                assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved);
                assert.equal((await fetch(origin + route + '/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })).status, 400);
                continue;
            }
            const o = f['input-options'], route = `/variant-analyses/${o.analysisId}/acmg`;
            assert.equal((await fetch(origin + route + '/input-options?topN=20')).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/input-options?topN=2')).json(), o);
            const inputs = helpers.prepareSimplifiedInputs(o);
            f.request.recommendations.forEach((r) => Object.assign(inputs[r.candidateId], { annotationId: r.annotationId, stableRelationshipKey: r.stableRelationshipKey }));
            const actual = helpers.buildSimplifiedCaseDeclaration(o, o.analysisId, inputs, undefined, 'SYNTHETIC');
            const post = (b) => fetch(origin + route + '/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });
            for (let i = 0; i < 1; i++) { const r = await post(actual); assert.equal(r.status, 200, await r.clone().text()); assert.deepEqual(await r.json(), f.saved); }
            const extra = {...actual, extraScienceAuthority:true}; assert.equal((await post(extra)).status,400);
            const wrong = structuredClone(actual); wrong.hypotheses[0].transcript.selectionPolicy += 'wrong'; assert.equal((await post(wrong)).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/runs/' + f.saved.runId)).json(), f.saved);
        }
        assert.equal((await fetch(origin, { headers: { Origin: 'https://invalid.example' } })).status, 403);
        const wrongHostStatus = await new Promise((resolve, reject) => {
            const request = http.get(origin, { headers: { Host: 'invalid.example' } }, response => {
                response.resume(); resolve(response.statusCode);
            });
            request.on('error', reject);
        });
        assert.equal(wrongHostStatus, 403);
        for (const f of Object.values(fixtures)) {
            const body = { fileName: 'acmg-' + f.saved.runId + '.json', rawSha256: sha(JSON.stringify(f.saved)), value: f.saved };
            const compare = b => fetch(origin + '/__test/compare-download', {method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)});
            assert.equal((await compare(body)).status,200);
            assert.equal((await compare({...body,value:{...f.saved,status:'FABRICATED'}})).status,400);
        }
        write('transport-validation.json', { status: 'PASS', ...snapshot(), browserInteraction: 'NOT_RUN', download: 'NOT_RUN' });
    } finally { finish(); }
}
