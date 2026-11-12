import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

// Dedicated TEST_ONLY PM5 replay. No backend, database, scientific execution or forwarding.
const project = fileURLToPath(new URL('../', import.meta.url));
const checkout = path.resolve(project, '..');
const selfCheck = process.argv.includes('--self-check');
assert.ok(process.argv.slice(2).every((arg) => arg === '--self-check'));
const task = path.join(checkout, 'local/temp/PM5-ui-business-20260925');
const handoff = path.join(checkout, 'local/development/handoffs/PM5-ui-business-20260925');
const normalSources = path.join(task, 'java/receipts');
const oldSources = path.join(checkout, 'local/temp/PM5-general-business-review-20260924/receipts');
const reviewedEvidence = JSON.parse(fs.readFileSync(path.join(checkout, 'local/development/handoffs/PM5-general-business-review-20260924/validation.json'))).evidence;
fs.mkdirSync(task, { recursive: true }); fs.mkdirSync(handoff, { recursive: true });
const output = fs.mkdtempSync(path.join(task, selfCheck ? 'self-check-' : 'browser-'));
const write = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const provenanceFile = path.join(handoff, 'original-sha256.json');
const manifest = fs.existsSync(provenanceFile) ? JSON.parse(fs.readFileSync(provenanceFile)) : {};
const fixtures = {};
for (const scenario of ['TWO_BACKGROUNDS', 'SAME', 'MATRIX_FAIL', 'FAILURE_SUPPORTING', 'MATERIAL', 'PARTIAL']) {
    const f = {};
    for (const name of scenario === 'TWO_BACKGROUNDS' ? ['input-options', 'request', 'get-reopened', 'submit'] : ['submit']) {
        const relative = `${scenario}/${name}.json`;
        const original = path.join(scenario === 'TWO_BACKGROUNDS' ? normalSources : oldSources, relative), retained = path.join(handoff, 'originals', relative);
        if (!fs.existsSync(retained)) {
            const bytes = fs.readFileSync(original);
            fs.mkdirSync(path.dirname(retained), { recursive: true }); fs.writeFileSync(retained, bytes, { flag: 'wx' });
            manifest[relative] = { original, retained, sha256: sha(bytes), origin: scenario === 'TWO_BACKGROUNDS' ? 'NEW_SINGLE_JAVA_METHOD_SAME_RUN_OPTIONS_REQUEST_SUBMIT_GET' : 'OLD_REVIEWED_READ_ONLY_SUBMIT' };
        }
        const bytes = fs.readFileSync(retained);
        if (scenario !== 'TWO_BACKGROUNDS') {
            const record = reviewedEvidence.find(e => e.path === 'local/temp/PM5-general-business-review-20260924/receipts/' + relative);
            assert.ok(record, 'Old read-only source must have independent review provenance');
            assert.equal(sha(bytes), record.sha256);
        }
        assert.equal(sha(bytes), manifest[relative]?.sha256);
        if (fs.existsSync(original)) assert.equal(sha(fs.readFileSync(original)), sha(bytes));
        f[name] = JSON.parse(bytes);
    }
    f.saved = f['get-reopened'] || f.submit;
    f.savedSource = f['get-reopened'] ? 'ARCHIVED_GET_REOPENED' : 'ARCHIVED_SUBMIT_SERVED_AS_TEST_GET';
    assert.equal(f.saved.dataClass, 'SYNTHETIC');
    if (f.request) {
        assert.equal(f['input-options'].requestedN, 2); assert.equal(f.request.topN, 2);
        assert.deepEqual(f.submit, f['get-reopened']);
    }
    fixtures[scenario] = f;
}
fs.writeFileSync(provenanceFile, JSON.stringify(manifest, null, 2)); write('original-sha256.json', manifest);
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
    // Allowed differences: UI privacy identifier and source labels, UNKNOWN affected status, PM5 confirmation prose.
    assert.equal(body.patient.role, 'PROBAND'); assert.equal(body.patient.sex, 'UNKNOWN'); assert.equal(body.patient.familyContextId, null);
    assert.equal(body.patient.patientContextId, `patient-context-${f['input-options'].analysisId}`);
    source(body.patient.source, `analysis/${f['input-options'].analysisId}/acmg/ordinary-pm5-missense-v1`);
    body.hypotheses.forEach((actual, i) => {
        const expected = archived.hypotheses[i], rec = archived.recommendations[i];
        for (const key of ['candidateId', 'hypothesisId', 'hgncId', 'mondoId', 'moi']) assert.equal(actual.hypothesis[key], expected.hypothesis[key], key);
        for (const key of ['accession', 'version', 'selectionPolicy', 'annotationSource', 'annotationSourceVersion'])
            assert.equal(actual.transcript[key], expected.transcript[key], key);
        assert.equal(actual.affectedStatus, 'UNKNOWN');
        const locator = `recommendations/${rec.recommendationId}/selection`;
        source(actual.hypothesis.provenance, locator); source(actual.transcript.source, locator);
        assert.equal(actual.transcript.selectionReason,
            `User confirmed disease relationship, MOI and exact versioned transcript ${expected.transcript.accession}.${expected.transcript.version} for limited PM5 same-residue different-substitution comparison. Reference independence, matrix or reviewed relationship and policy remain verified by the server; no scientific adoption or final classification is authorized.`);
        assert.deepEqual(Object.keys(actual).sort(), ['affectedStatus', 'hypothesis', 'transcript']);
        assert.deepEqual(Object.keys(actual.hypothesis).sort(), Object.keys(expected.hypothesis).sort());
        assert.deepEqual(Object.keys(actual.transcript).sort(), Object.keys(expected.transcript).sort());
    });
}

const entry = `import React,{useState} from 'react'; import {createRoot} from 'react-dom/client';
import Panel from ${JSON.stringify(component)};
function Replay(){const [scenario,setScenario]=useState('TWO_BACKGROUNDS'); const [history,setHistory]=useState(false);
 const [status,setStatus]=useState('TEST_ONLY: 本回放只有 Top-2，请先把组件 Top-20 改为 2，再读取正式候选。');
 const ids=${JSON.stringify(Object.fromEntries(Object.entries(fixtures).map(([key, f]) => [key, f.saved.analysisId])))};
 async function control(route,body){const r=await fetch('/__test/'+route,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});setStatus(JSON.stringify(await r.json(),null,2));}
 return <><aside style={{padding:16,background:'#fff3cd'}}><h1>TEST_ONLY PM5 · 真实组件合成 HTTP</h1>
 <p>无 Java、数据库或科学执行，仅原 runId 的保存回执回放。仅支持 Top-2，不扩造候选。</p>
 <p>仅 TWO_BACKGROUNDS 有匹配 options/request；其余场景只有归档 submit，作为只读测试 GET，不允许提交。</p>
 <label>归档场景 <select value={scenario} onChange={e=>setScenario(e.target.value)}><option>TWO_BACKGROUNDS</option><option>SAME</option><option>MATRIX_FAIL</option><option>FAILURE_SUPPORTING</option><option>MATERIAL</option><option>PARTIAL</option></select></label>{' '}
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

// Render the actual saved panel with absent CURRENT options: historical PM5 must still be visible.
await build({ stdin: { contents: `import React from 'react'; import {renderToStaticMarkup} from 'react-dom/server';
 import {SavedResult} from ${JSON.stringify(component)}; export const render=(run,criteria=[])=>renderToStaticMarkup(<SavedResult run={run} criteria={criteria}/>);`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', outfile: path.join(output, 'render.cjs'), loader: { '.js': 'jsx' },
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning', plugins: [{ name: 'test-only-export', setup(api) {
        api.onLoad({ filter: /OrdinaryAcmgEvaluationPanel\.jsx$/ }, () => ({ contents: `${fs.readFileSync(component, 'utf8')}\nexport {SavedResult};`, loader: 'jsx', resolveDir: path.dirname(component) }));
    } }] });
const { render } = createRequire(import.meta.url)(path.join(output, 'render.cjs'));
for (const [scenario, f] of Object.entries(fixtures)) {
    const before = JSON.stringify(f.saved), html = render(f.saved);
    const expected = { TWO_BACKGROUNDS: ['MODERATE', 'SUPPORTING', 'Glu2Asp', 'SCV000000001.1'], SAME: ['未满足', 'NONE'],
        MATRIX_FAIL: ['未满足', 'NONE', 'SUPPORTING', 'NO_ELIGIBLE_REVIEWED_PROTEIN_COMPARISON'],
        FAILURE_SUPPORTING: ['满足', 'SUPPORTING'], MATERIAL: ['未评价', 'PM5_REFERENCE_MATERIALS_MISSING', '未记录'],
        PARTIAL: ['未评价', 'MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE'] };
    const evaluations = f.saved.candidates.map(c => c.attempts[0].missenseComparison.criterionEvaluations[0]);
    const outcomes = { TWO_BACKGROUNDS: [['MET','MODERATE'],['MET','SUPPORTING']], SAME: [['NOT_MET','NONE'],['NOT_MET','NONE']],
        MATRIX_FAIL: [['NOT_MET','NONE'],['MET','SUPPORTING']], FAILURE_SUPPORTING: [['MET','SUPPORTING'],['MET','SUPPORTING']],
        MATERIAL: [['NOT_EVALUATED',undefined],['MET','SUPPORTING']], PARTIAL: [['NOT_EVALUATED','NONE'],['MET','SUPPORTING']] };
    assert.deepEqual(evaluations.map(e => [e.evaluationResult.status, e.appliedStrength]), outcomes[scenario]);
    assert.equal(f.saved.status, ['MATERIAL','PARTIAL'].includes(scenario) ? 'COMPLETED_WITH_BLOCKERS' : 'COMPLETED');
    // Check actual summary and reason-list prose against the archived decisions, without rewriting them.
    const reasonText = {
        INDEPENDENT_REFERENCE_AND_BOTH_SPLICE_GATES_MET: '本次比较的参考独立性及目标、参考双方剪接门禁已满足；具体强度以保存结果为准。',
        NO_ELIGIBLE_REVIEWED_PROTEIN_COMPARISON: '本次评价未找到符合当前政策要求的已审核蛋白比较支持，规则未满足。',
        PM5_REFERENCE_MATERIALS_MISSING: 'PM5 可信参考材料缺失，本次未评价；转录本确认不能替代参考独立性及比较依据审核。',
        MISSENSE_REFERENCE_DISCOVERY_INCOMPLETE: '参考搜索尚不完整，本次未评价，不能作为完整阴性结论。',
    };
    for (const candidate of f.saved.candidates) {
        const entry = helpers.evidenceCards(candidate, ['ACMG_AMP_2015:PM5'])[0].entries[0];
        const code = entry.reasons[0]?.reasonCode || entry.reasons[0];
        assert.ok(reasonText[code], `${scenario}: archived reason must be covered`);
        assert.equal(helpers.evidenceSummary(entry), reasonText[code]);
        assert.ok(entry.reasons.includes(code));
        assert.ok(html.split(reasonText[code]).length - 1 >= 2, `${scenario}: summary and reason list`);
        assert.ok(html.includes(code));
    }
    assert.ok(!html.includes('任务缺少或未通过可信来源 / REF 核验'));
    assert.ok(!html.includes('需要 CSpec / 人工复核处理'));
    if (['TWO_BACKGROUNDS','MATRIX_FAIL','FAILURE_SUPPORTING','PARTIAL'].includes(scenario)) {
        assert.ok(html.includes('TARGET_GTE_REFERENCE')); assert.ok(html.includes('不表示通过'));
        assert.ok(html.includes('目标分数 ' + (scenario === 'TWO_BACKGROUNDS' ? 80 : 10) + ' / 参考分数 40'));
        assert.ok(html.includes('AUDITED_AMINO_ACID_TRIPLES_SINGLE_REFERENCE'));
        assert.ok(html.includes('合成测试分数不是临床矩阵'));
    }
    for (const text of ['PM5', '只读', '科学采用', ...expected[scenario]]) assert.ok(html.includes(text), text);
    assert.ok(!html.includes('>PS1<')); assert.ok(!html.includes('采用当前已保存 REVEL')); assert.equal(JSON.stringify(f.saved), before);
    const otherScope = render(f.saved, ['ACMG_AMP_2015:BA1']);
    assert.ok(otherScope.includes('>PM5<')); assert.ok(!otherScope.includes('>BA1<'));
    fs.writeFileSync(path.join(output, `${scenario}.html`), `<!doctype html><meta charset="utf-8">${html}`);
}
write('render-validation.json', { status: 'PASS', actualComponents: true, scenarios: Object.keys(fixtures), currentCriteria: [], browserInteraction: 'NOT_RUN' });

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
        if (req.method === 'GET' && url.pathname === '/') return reply(200, '<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>PM5 TEST_ONLY</title><div id="root"></div><script src="/app.js"></script></html>', 'text/html');
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
                fileName: body.fileName, rawSha256: body.rawSha256, comparedAgainst: f.savedSource, input: 'BROWSER_FILE_PICKER_ACTUAL_DOWNLOAD' });
            return reply(200, { status: 'PASS', runId: f.saved.runId });
        }
        const match = /^\/variant-analyses\/([^/]+)\/acmg\/(input-options|runs)(?:\/([^/]+))?$/.exec(url.pathname);
        const f = match && Object.values(fixtures).find((v) => v.saved.analysisId === decodeURIComponent(match[1]));
        if (!f) return reply(404, { message: 'TEST_ONLY_UNKNOWN_ROUTE' });
        if (req.method === 'GET' && match[2] === 'input-options') {
            event.kind = 'GET_OPTIONS';
            if (state.history || !f.request) return reply(503, { message: 'TEST_ONLY_CURRENT_PM5_UNAVAILABLE_OR_READ_ONLY_ARCHIVE' });
            if (url.searchParams.get('topN') !== '2') return reply(400, { message: 'TEST_ONLY_TOP2_REQUIRED：请把正式 Top-N 改为 2 后读取' });
            return reply(200, f['input-options']);
        }
        if (req.method === 'GET' && match[2] === 'runs') {
            event.kind = match[3] ? 'GET_RUN' : 'GET_RUNS'; event.savedSource = f.savedSource; const run = f.saved;
            if (match[3] && match[3] !== run.runId) return reply(404, { message: 'TEST_ONLY_UNKNOWN_RUN' });
            return reply(200, match[3] ? run : [{ runId: run.runId, status: run.status, createdAt: run.createdAt }]);
        }
        if (req.method === 'POST' && match[2] === 'runs' && !match[3]) {
            event.kind = 'POST_RUN'; assert.equal(state.history, false, 'Current PM5 scope unavailable');
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
                continue;
            }
            const o = f['input-options'], route = `/variant-analyses/${o.analysisId}/acmg`;
            assert.equal((await fetch(origin + route + '/input-options?topN=20')).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/input-options?topN=2')).json(), o);
            const inputs = helpers.prepareSimplifiedInputs(o);
            f.request.recommendations.forEach((r) => Object.assign(inputs[r.candidateId], { annotationId: r.annotationId, stableRelationshipKey: r.stableRelationshipKey }));
            const actual = helpers.buildSimplifiedCaseDeclaration(o, o.analysisId, inputs, undefined, 'SYNTHETIC');
            const post = (b) => fetch(origin + route + '/runs', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });
            for (let i = 0; i < 2; i++) { const r = await post(actual); assert.equal(r.status, 200, await r.clone().text()); assert.deepEqual(await r.json(), f['get-reopened']); }
            const wrong = structuredClone(actual); wrong.hypotheses[0].transcript.selectionPolicy += 'wrong'; assert.equal((await post(wrong)).status, 400);
            assert.deepEqual(await (await fetch(origin + route + '/runs/' + f['get-reopened'].runId)).json(), f['get-reopened']);
        }
        assert.equal((await fetch(origin, { headers: { Origin: 'https://invalid.example' } })).status, 403);
        write('transport-validation.json', { status: 'PASS', ...snapshot(), browserInteraction: 'NOT_RUN', download: 'NOT_RUN' });
    } finally { finish(); }
}
