import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

// A loopback-only replay of archived SYNTHETIC responses, never a scientific runner.
// --self-check checks the replay transport and bundle only, NOT browser interaction.
const project = fileURLToPath(new URL('../', import.meta.url));
const checkout = path.resolve(project, '..');
const originals = path.join(checkout, 'local/development/handoffs/PP3-approval-lifecycle-20260922/green');
const scratch = path.join(checkout, 'local/temp/PP3-revel-browser-20260923');
const selfCheck = process.argv.includes('--self-check');
assert.ok(process.argv.slice(2).every((arg) => arg === '--self-check'), 'Only --self-check is supported');
fs.mkdirSync(scratch, { recursive: true });
const output = fs.mkdtempSync(path.join(scratch, selfCheck ? 'self-check-' : 'manual-'));
fs.mkdirSync(path.join(output, 'downloads'));
const writeJson = (name, value) => fs.writeFileSync(path.join(output, name), JSON.stringify(value, null, 2));
const manifest = {};
function archive(name) {
    const bytes = fs.readFileSync(path.join(originals, name));
    manifest[name] = crypto.createHash('sha256').update(bytes).digest('hex');
    return JSON.parse(bytes);
}
const c0 = archive('C0-input-options.json');
const c1 = archive('synthetic-input-options.json');
const source = archive('synthetic-source.json');
const application = archive('synthetic-application.json');
const adoptionRequest = archive('adoption-request.json');
const receipt = archive('receipt.json');
assert.equal(source.dataClass, 'SYNTHETIC');
assert.equal(application.dataClass, 'SYNTHETIC');
assert.equal(receipt.testOnly, true);
assert.equal(source.requestedN, 2);
assert.equal(c0.analysisId, source.analysisId);
assert.equal(c1.analysisId, source.analysisId);
assert.deepEqual(c0.executionProfile, c1.executionProfile);
writeJson('original-sha256.json', manifest);

const component = path.join(project, 'src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx');
const inputModule = path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js');
const helpers = await import(frontendModuleUrl(`data:text/javascript;base64,${fs.readFileSync(inputModule).toString('base64')}`));
const sourceDeclaration = (options) => helpers.buildSimplifiedCaseDeclaration(
    options, source.analysisId, helpers.prepareSimplifiedInputs(options), undefined, 'SYNTHETIC',
);
function stableDeclaration(body) {
    const copy = structuredClone(body);
    copy.recommendations?.forEach((row) => delete row.generatedAt);
    return copy;
}
const simulation = (run, fields) => ({ ...structuredClone(run), ...fields, testOnlySimulation: true });
const alternate = simulation(source, { runId: 'TEST_ONLY_ALTERNATE_SOURCE' });
const legacy = simulation(source, {
    runId: 'TEST_ONLY_REVEL_1_0_SOURCE',
    executionProfile: { ...structuredClone(source.executionProfile), profileVersion: '1.0.0',
        revelApplicationBinding: c0.revelApplicationBinding },
});
const am = { analysisId: source.analysisId, runId: 'TEST_ONLY_AM_HISTORY', status: 'COMPLETED',
    dataClass: 'SYNTHETIC', requestedN: 2, selectedCount: 0, candidates: [], candidateIds: [], testOnlySimulation: true,
    executionProfile: { profileId: 'acmg-pp3-bp4-development-v1' },
    enabledCriteria: ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'] };
const otherAnalysis = 'TEST_ONLY_SECOND_ANALYSIS';
const otherSource = simulation(source, { analysisId: otherAnalysis, runId: 'TEST_ONLY_SECOND_SOURCE' });
const runs = new Map([source, application, alternate, legacy, am, otherSource].map((run) => [run.runId, run]));
const journal = [];
const pending = [];
const sourcePosts = [];
const state = { phase: 'C0', holdNext: 'none' };
let origin;
let sequence = 0;
function record(entry) {
    journal.push(entry);
    fs.appendFileSync(path.join(output, 'requests.jsonl'), `${JSON.stringify(entry)}\n`);
}
function snapshot() {
    const counts = {};
    journal.forEach(({ kind }) => { counts[kind] = (counts[kind] || 0) + 1; });
    return { testOnly: true, ...state, pending: pending.map(({ kind }) => kind), counts,
        sourceDeclarationsEqual: sourcePosts.length < 2 ? null : sourcePosts.every((body) =>
            isDeepStrictEqual(stableDeclaration(body), stableDeclaration(sourcePosts[0]))),
        scientificExecution: 'NOT_STARTED', output };
}
async function readJson(request) {
    assert.ok(request.headers['content-type']?.startsWith('application/json'), 'TEST_ONLY_JSON_REQUIRED');
    const chunks = [];
    let size = 0;
    for await (const chunk of request) {
        size += chunk.length;
        assert.ok(size <= 1024 * 1024, 'TEST_ONLY_REQUEST_TOO_LARGE');
        chunks.push(chunk);
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

const entry = `import React, {useState} from 'react';
import {createRoot} from 'react-dom/client';
import Panel from ${JSON.stringify(component)};
function Replay() {
    const [analysisId, setAnalysisId] = useState(${JSON.stringify(source.analysisId)});
    const [phase, setPhase] = useState('C0');
    const [holdNext, setHoldNext] = useState('none');
    const [status, setStatus] = useState('通过下方真实组件操作；所有 B1–B6 均须人工记录。');
    async function control(route, body) {
        try {
            const r = await fetch('/__test/' + route, body === undefined ? {} : {
                method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)});
            const value = await r.json();
            setStatus(JSON.stringify(value, null, 2));
        } catch (error) { setStatus(String(error)); }
    }
    return <><aside style={{padding:16, background:'#fff3cd', border:'2px solid #856404'}}>
        <h1>TEST_ONLY · 真实组件 / 合成 HTTP 回放</h1>
        <p>只在 127.0.0.1 回放归档，无后端、数据库或科学执行。模拟记录均以 TEST_ONLY 命名。</p>
        <p>初始 Top-20 的 HTTP 400 是材料限制：请在实际组件中改为 Top-2，再读取正式候选。</p>
        <label>模拟服务配置 <select value={phase} onChange={e=>setPhase(e.target.value)}>
            <option>C0</option><option>C1</option><option>LEGACY</option></select></label>{' '}
        <label>挂起下一次响应 <select value={holdNext} onChange={e=>setHoldNext(e.target.value)}>
            <option value="none">不延迟</option><option value="options">options</option>
            <option value="run">run</option><option value="adoption">采用</option></select></label>{' '}
        <button onClick={()=>control('control', {phase,holdNext})}>仅更新回放服务（不刷新组件）</button>{' '}
        <button onClick={()=>control('release', {})}>释放挂起响应</button>{' '}
        <button onClick={()=>control('state')}>读取请求计数</button>
        <p><label>组件 analysisId <select value={analysisId} onChange={e=>setAnalysisId(e.target.value)}>
            <option value=${JSON.stringify(source.analysisId)}>归档原分析</option>
            <option value=${JSON.stringify(otherAnalysis)}>模拟第二分析</option></select></label></p>
        <p>浏览器下载请另存到：<code>${path.join(output, 'downloads').replaceAll('\\', '/')}</code></p>
        <label>核对实际下载 JSON <input type="file" accept=".json" onChange={async e=>{
            const file=e.target.files?.[0]; if (!file) return;
            try { await control('compare-download', JSON.parse(await file.text())); }
            catch(error) { setStatus(String(error)); }
        }}/></label>
        <pre style={{whiteSpace:'pre-wrap'}}>{status}</pre>
    </aside><Panel analysisId={analysisId}/></>;
}
createRoot(document.getElementById('root')).render(<Replay/>);`;
fs.writeFileSync(path.join(output, 'entry.jsx'), entry);
const built = await build({
    stdin: { contents: entry, resolveDir: project, loader: 'jsx' }, bundle: true,
    platform: 'browser', format: 'iife', outfile: path.join(output, 'app.js'),
    loader: { '.js': 'jsx' }, define: { 'process.env.NODE_ENV': '"production"' },
    metafile: true, logLevel: 'warning',
});
const imports = Object.keys(built.metafile.inputs).map((name) => name.replaceAll('\\', '/'));
assert.ok(imports.some((name) => name.endsWith('/components/OrdinaryAcmgEvaluationPanel.jsx')));
assert.ok(imports.some((name) => name.endsWith('/services/variantAnalysis.js')));
writeJson('bundle-inputs.json', imports);
const html = '<!doctype html><html lang="zh-CN"><meta charset="utf-8">' +
    '<meta name="viewport" content="width=device-width,initial-scale=1">' +
    '<title>TEST_ONLY REVEL HTTP replay</title><body><div id="root"></div>' +
    '<script src="/app.js"></script></body></html>';

const server = http.createServer(async (request, response) => {
    const entryLog = { id: ++sequence, at: new Date().toISOString(), method: request.method,
        url: request.url, kind: 'UNEXPECTED', phase: state.phase };
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
    response.setHeader('X-Content-Type-Options', 'nosniff');
    function reply(status, value, contentType = 'application/json; charset=utf-8', hold = 'none') {
        entryLog.status = status;
        record({ ...entryLog, event: 'request' });
        const send = () => {
            if (!response.destroyed) {
                response.writeHead(status, { 'Content-Type': contentType });
                response.end(contentType.startsWith('application/json') ? JSON.stringify(value) : value);
            }
        };
        if (hold !== 'none' && state.holdNext === hold) {
            state.holdNext = 'none';
            pending.push({ kind: hold, send });
        } else send();
    }
    try {
        if (request.headers.host !== new URL(origin).host ||
            (request.headers.origin && request.headers.origin !== origin) ||
            request.headers['sec-fetch-site'] === 'cross-site') {
            return reply(403, { message: 'TEST_ONLY_LOOPBACK_ORIGIN_REQUIRED' });
        }
        const url = new URL(request.url, origin);
        if (request.method === 'GET' && ['/', '/app.js', '/favicon.ico'].includes(url.pathname)) {
            entryLog.kind = 'STATIC';
            return reply(200, url.pathname === '/' ? html : url.pathname === '/app.js' ?
                fs.readFileSync(path.join(output, 'app.js')) : '',
            url.pathname === '/app.js' ? 'text/javascript; charset=utf-8' : 'text/html; charset=utf-8');
        }
        if (url.pathname.startsWith('/__test/')) {
            entryLog.kind = 'CONTROL';
            if (request.method === 'GET' && url.pathname === '/__test/state') return reply(200, snapshot());
            if (request.method !== 'POST') return reply(405, { message: 'TEST_ONLY_METHOD_NOT_ALLOWED' });
            const body = await readJson(request);
            if (url.pathname === '/__test/control') {
                assert.ok(['C0', 'C1', 'LEGACY'].includes(body.phase));
                assert.ok(['none', 'options', 'run', 'adoption'].includes(body.holdNext));
                Object.assign(state, { phase: body.phase, holdNext: body.holdNext });
            } else if (url.pathname === '/__test/release') {
                pending.splice(0).forEach(({ send }) => send());
            } else if (url.pathname === '/__test/compare-download') {
                const expected = runs.get(body.runId);
                const equal = Boolean(expected && isDeepStrictEqual(body, expected));
                entryLog.downloadEqualsArchivedOrSimulatedRun = equal;
                if (equal) writeJson(`downloads/${body.runId}.json`, body);
                return reply(equal ? 200 : 400, { equal, simulated: Boolean(expected?.testOnlySimulation), runId: body.runId });
            } else return reply(404, { message: 'TEST_ONLY_UNEXPECTED_REQUEST' });
            return reply(200, snapshot());
        }
        const match = /^\/variant-analyses\/([^/]+)\/acmg\/(input-options|runs)(?:\/([^/]+))?$/.exec(url.pathname);
        if (!match) return reply(404, { message: 'TEST_ONLY_UNEXPECTED_REQUEST' });
        const analysisId = decodeURIComponent(match[1]);
        if (![source.analysisId, otherAnalysis].includes(analysisId)) return reply(404, { message: 'TEST_ONLY_UNKNOWN_ANALYSIS' });
        entryLog.analysisId = analysisId;
        if (request.method === 'GET' && match[2] === 'input-options' && !match[3]) {
            entryLog.kind = 'GET_OPTIONS';
            if (url.searchParams.get('topN') !== '2') return reply(400, { message: 'TEST_ONLY 回放仅提供 Top-2' });
            const options = structuredClone(state.phase === 'C0' ? c0 : c1);
            options.analysisId = analysisId;
            if (state.phase === 'LEGACY') options.executionProfile = legacy.executionProfile;
            entryLog.bindingSha256 = options.revelApplicationBinding.bindingSha256;
            return reply(200, options, undefined, 'options');
        }
        if (request.method === 'GET' && match[2] === 'runs') {
            entryLog.kind = match[3] ? 'GET_RUN' : 'GET_RUNS';
            const runId = match[3] && decodeURIComponent(match[3]);
            entryLog.runId = runId;
            const value = runId ? runs.get(runId) : [...runs.values()].filter((run) => run.analysisId === analysisId)
                .map(({ runId: id, status, createdAt }) => ({ runId: id, status, createdAt }));
            if (!value || (runId && value.analysisId !== analysisId)) return reply(404, { message: 'TEST_ONLY_UNKNOWN_RUN' });
            return reply(200, value, undefined, runId ? 'run' : 'none');
        }
        if (request.method === 'POST' && match[2] === 'runs' && !match[3]) {
            const body = await readJson(request);
            entryLog.kind = body.revelApplication ? 'POST_ADOPTION' : 'POST_SOURCE';
            entryLog.body = body;
            if (analysisId !== source.analysisId) return reply(400, { message: 'TEST_ONLY_SECOND_ANALYSIS_READ_ONLY' });
            if (body.revelApplication) {
                const expected = structuredClone(adoptionRequest);
                const selected = runs.get(body.revelApplication.sourceRunId);
                if (state.phase !== 'C1' || body.revelApplication.bindingSha256 !== expected.revelApplication.bindingSha256)
                    return reply(400, { message: 'REVEL_APPLICATION_REFERENCE_INVALID', testOnlySimulation: true });
                if (![source, legacy, alternate].includes(selected)) return reply(400, { message: 'TEST_ONLY_SOURCE_IDENTITY_MISMATCH' });
                expected.revelApplication.sourceRunId = selected.runId;
                expected.revelApplication.sourceRequestSha256 = selected.requestSha256;
                if (!isDeepStrictEqual(body, expected)) return reply(400, { message: 'TEST_ONLY_ADOPTION_DECLARATION_MISMATCH' });
                entryLog.matchesArchivedRequest = selected === source;
                const value = selected === source ? application : simulation(application, {
                    runId: `TEST_ONLY_ADOPTED_${selected.runId}`, sourceRunId: selected.runId,
                    applications: application.applications.map((row) => ({ ...row, sourceRunId: selected.runId })),
                });
                runs.set(value.runId, value);
                return reply(200, value, undefined, 'adoption');
            }
            if (!isDeepStrictEqual(stableDeclaration(body), stableDeclaration(sourceDeclaration(c0))))
                return reply(400, { message: 'TEST_ONLY_SOURCE_DECLARATION_MISMATCH' });
            sourcePosts.push(body);
            return reply(200, source);
        }
        return reply(405, { message: 'TEST_ONLY_METHOD_NOT_ALLOWED' });
    } catch (error) {
        reply(400, { message: `TEST_ONLY_INVALID_REQUEST: ${error.message}` });
    }
});
await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
origin = `http://127.0.0.1:${server.address().port}`;
writeJson('session.json', { pid: process.pid, origin, output, originals, mode: selfCheck ? 'TRANSPORT_SELF_CHECK' : 'MANUAL_BROWSER', browserStatus: 'NOT_RUN' });
console.log(JSON.stringify({ origin, output, browserStatus: 'NOT_RUN' }));
function finish() {
    pending.splice(0).forEach(({ send }) => send());
    writeJson('summary.json', snapshot());
    for (const [name, hash] of Object.entries(manifest)) {
        assert.equal(crypto.createHash('sha256').update(fs.readFileSync(path.join(originals, name))).digest('hex'), hash);
    }
    server.close();
    server.closeAllConnections();
}
if (!selfCheck) {
    process.once('SIGINT', finish);
    process.once('SIGTERM', finish);
} else {
    try {
        const route = `/variant-analyses/${encodeURIComponent(source.analysisId)}/acmg`;
        const call = async (pathname, body, expectedStatus = 200) => {
            const result = await fetch(origin + pathname, body === undefined ? {} : {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
            });
            assert.equal(result.status, expectedStatus);
            return result.json();
        };
        assert.equal((await fetch(origin)).status, 200);
        assert.equal((await fetch(origin + '/app.js')).status, 200);
        await call(`${route}/input-options?topN=20`, undefined, 400);
        assert.deepEqual(await call(`${route}/input-options?topN=2`), c0);
        await call(`${route}/runs`);
        assert.deepEqual(await call(`${route}/runs/${source.runId}`), source);
        assert.deepEqual(await call(`${route}/runs`, sourceDeclaration(c0)), source);
        await call('/__test/control', { phase: 'C1', holdNext: 'none' });
        assert.deepEqual(await call(`${route}/input-options?topN=2`), c1);
        assert.deepEqual(await call(`${route}/runs`, sourceDeclaration(c1)), source);
        const stale = structuredClone(adoptionRequest);
        stale.revelApplication.bindingSha256 = c0.revelApplicationBinding.bindingSha256;
        assert.equal((await call(`${route}/runs`, stale, 400)).message, 'REVEL_APPLICATION_REFERENCE_INVALID');
        await call('/__test/control', { phase: 'C1', holdNext: 'adoption' });
        const delayed = call(`${route}/runs`, adoptionRequest);
        for (let i = 0; !pending.length && i < 50; i++) await new Promise((resolve) => setTimeout(resolve, 10));
        assert.equal(pending.length, 1);
        await call('/__test/release', {});
        assert.deepEqual(await delayed, application);
        for (const fixture of [alternate, legacy, am]) {
            helpers.validateSavedRun(fixture, source.analysisId);
            assert.deepEqual(await call(`${route}/runs/${fixture.runId}`), fixture);
        }
        const legacyRequest = helpers.buildRevelApplicationDeclaration(c1, source.analysisId, legacy, c1.revelApplicationBinding.bindingSha256);
        assert.equal((await call(`${route}/runs`, legacyRequest)).sourceRunId, legacy.runId);
        assert.deepEqual(await call(`/variant-analyses/${otherAnalysis}/acmg/runs/${otherSource.runId}`), otherSource);
        await call('/__test/control', { phase: 'LEGACY', holdNext: 'none' });
        assert.equal((await call(`${route}/input-options?topN=2`)).executionProfile.profileVersion, '1.0.0');
        await call('/unexpected', undefined, 404);
        assert.equal((await fetch(origin, { headers: { Origin: 'https://invalid.example' } })).status, 403);
        assert.equal(snapshot().sourceDeclarationsEqual, true);
        writeJson('transport-validation.json', { status: 'PASS', browserStatus: 'NOT_RUN',
            downloadedInBrowser: false, message: 'HTTP replay and actual component bundling only; B1–B6 not executed.' });
        console.log('PASS: loopback HTTP replay and component bundle. B1–B6 browser interactions NOT_RUN.');
    } finally { finish(); }
}
