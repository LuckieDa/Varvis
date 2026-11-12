// Synthetic contracts + real component SSR + asynchronous read helpers.
// No browser, backend, public source or model is called by this check.
const assert = require('node:assert/strict');
const path = require('node:path');
const { createRequire, Module } = require('node:module');
const project = path.resolve(__dirname, '..');
const shared = path.resolve(process.argv[2] || project);
const dependencies = createRequire(path.join(shared, 'package.json'));
const esbuild = dependencies('esbuild');
const bundle = esbuild.buildSync({
    stdin: { contents: `export {default as PublicPanel,validLiteratureTask,readPublicLiteratureEntry,publicLiteratureActionAvailable,readPublicLiteratureContinuation} from './src/pages/varvis/ChromosomeVisualization/components/PublicLiteraturePanel.jsx';export {default as SourceNotice,readDeNovoSourceEntry} from './src/pages/varvis/ChromosomeVisualization/components/DeNovoSourceTaskNotice.jsx';`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', packages: 'external', write: false,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'silent',
});
const loaded = new Module(path.join(project, 'scripts', 'source-public-entry-bundle.cjs'));
loaded.filename = loaded.id;
loaded.paths = [path.join(shared, 'node_modules')];
loaded._compile(bundle.outputFiles[0].text, loaded.filename);
const { PublicPanel, SourceNotice, validLiteratureTask, readPublicLiteratureEntry,
    publicLiteratureActionAvailable, readPublicLiteratureContinuation, readDeNovoSourceEntry } = loaded.exports;
const React = dependencies('react');
const { renderToStaticMarkup } = dependencies('react-dom/server');
const axios = dependencies('axios');
const id = 'a'.repeat(64), other = 'b'.repeat(64), sha = 'c'.repeat(64);
const paper = { pmcid: 'PMC123', chunks: [], continuation: { eligible: true, reasonCode: 'UNPROCESSED_TARGETS_REMAIN', pendingTargets: 1 } };
const task = (changes = {}) => ({ schemaVersion: 'denovo-public-literature-task-v1', taskId: id,
    status: 'READY', scientificExecution: false, modelCalls: null,
    result: { schemaVersion: 'denovo-public-literature-workflow-v1', scientificExecution: false,
        businessPublishable: false, modelCalls: 0, selectionSha256: sha, papers: [paper], issues: [] }, ...changes });
const options = { schemaVersion: 'denovo-public-literature-workflow-v1', enabled: true, selectionSha256: sha };
const resume = { taskId: id, pmcid: 'PMC123' };
let passed = 0;
async function check(name, test) { await test(); passed++; console.log(`PASS ${name}`); }
function transport(value = task(), list = [value], prepared = options) {
    const calls = [];
    return { calls, read: async (url, config) => {
        calls.push({ method: 'GET', url, config });
        return { data: url.endsWith('/options') ? prepared : url.endsWith('/tasks') ? list : value };
    } };
}
const tick = () => new Promise(resolve => setImmediate(resolve));
(async () => {
    await check('unowned entry reads only current-analysis list and never a repository task URL', async () => {
        const io = transport();
        await readPublicLiteratureEntry({ analysisId: 'current', read: io.read });
        assert.deepEqual(io.calls.map(c => c.url), ['/variant-analyses/current/acmg/literature/tasks']);
    });
    await check('known source analysis reads fixed exact task first, then list', async () => {
        const io = transport();
        const rows = await readPublicLiteratureEntry({ analysisId: 'source', initialTaskId: id, read: io.read });
        assert.equal(io.calls[0].url, `/variant-analyses/source/acmg/literature/tasks/${id}`);
        assert.equal(rows.length, 1);
        assert.ok(io.calls.every(c => c.method === 'GET'));
    });
    await check('unowned list and detail reject mismatched analysis ownership when returned', async () => {
        const io = transport(task({ analysisId: 'foreign' }));
        await assert.rejects(readPublicLiteratureEntry({ analysisId: 'current', read: io.read }), /身份/);
        await assert.rejects(readPublicLiteratureEntry({ analysisId: 'current', initialTaskId: id, read: io.read }), /身份/);
    });
    await check('exact-task mismatch stops before reading list or options', async () => {
        const io = transport(task({ taskId: other }));
        await assert.rejects(readPublicLiteratureEntry({ analysisId: 'source', initialTaskId: id, read: io.read }), /身份/);
        assert.equal(io.calls.length, 1);
    });
    await check('invalid task path never reaches transport', async () => {
        const io = transport();
        await assert.rejects(readPublicLiteratureEntry({ analysisId: 'source', initialTaskId: '../tasks', read: io.read }), /编号/);
        assert.equal(io.calls.length, 0);
    });
    await check('continuation first reads live detail/list/options and binds selection', async () => {
        const io = transport(task({ analysisId: 'source', selectionSha256: sha }));
        const result = await readPublicLiteratureContinuation({ analysisId: 'source', topN: 2, resume, read: io.read });
        assert.equal(result.options.selectionSha256, sha);
        assert.equal(io.calls.length, 3);
        assert.equal(io.calls[2].config.params.topN, 2);
        assert.ok(io.calls.every(c => c.method === 'GET'));
    });
    await check('selection mismatch in live task or fresh options forbids continuation', async () => {
        const io = transport(task({ selectionSha256: other }));
        await assert.rejects(readPublicLiteratureContinuation({ analysisId: 'source', topN: 2, resume, read: io.read }), /身份/);
        const changed = transport(task());
        await assert.rejects(readPublicLiteratureContinuation({ analysisId: 'source', topN: 2, resume, expectedSelection: other, read: changed.read }), /候选/);
    });
    await check('real response result-level selection mismatches cannot pass absent top-level pin', async () => {
        const row = task(); row.result.selectionSha256 = other;
        const io = transport(row);
        await assert.rejects(readPublicLiteratureContinuation({ analysisId: 'source', topN: 2, resume, read: io.read }), /身份/);
        const old = task(); delete old.result.selectionSha256;
        await assert.rejects(readPublicLiteratureContinuation({ analysisId: 'source', topN: 2, resume, read: transport(old).read }), /身份/);
    });
    await check('missing original Top-N never falls back to default for public continuation', async () => {
        const io = transport();
        await assert.rejects(readPublicLiteratureContinuation({ analysisId: 'source', resume, read: io.read }), /Top-N/);
        assert.equal(io.calls.length, 0);
    });
    await check('live terminal failure never opens options or continuation', async () => {
        const io = transport(task({ status: 'FAILED' }));
        await assert.rejects(readPublicLiteratureContinuation({ analysisId: 'source', topN: 2, resume, read: io.read }), /未开放/);
        assert.equal(io.calls.length, 2);
    });
    await check('no-progress/conflict/no-remaining/knowledge blockers deny even contradictory eligible flag', () => {
        for (const code of ['NO_PROGRESS_CONFIGURATION_OR_CACHE_REQUIRED', 'FACT_CONFLICT', 'NO_PENDING_RELEVANT_TARGET', 'NO_SUPPORTED_PENDING_TARGETS', 'EXTERNAL_OR_UNSUPPORTED_REQUIREMENT', 'NO_READABLE_NEED']) {
            const row = task(); row.result.papers = [{ ...paper, continuation: { eligible: true, reasonCode: code } }];
            assert.equal(publicLiteratureActionAvailable(row, resume, [row]), false, code);
        }
    });
    await check('single stalled paper does not conceal other unread paper or independent discovery', () => {
        const row = task(); row.result.papers = [{ ...paper, continuation: { eligible: false, reasonCode: 'NO_PROGRESS_CONFIGURATION_OR_CACHE_REQUIRED' } }, { ...paper, pmcid: 'PMC456' }];
        row.result.discoveryState = { resumeAvailable: true, searchMode: 'LITVAR_ONLY', sha256: sha, pendingPapers: 0, pendingQueries: 1 };
        assert.equal(publicLiteratureActionAvailable(row, resume, [row]), false);
        assert.equal(publicLiteratureActionAvailable(row, { taskId: id, pmcid: 'PMC456' }, [row]), true);
        assert.equal(publicLiteratureActionAvailable(row, { taskId: id, discovery: true }, [row]), true);
    });
    const discoveryResume = { taskId: id, discovery: true };
    const discoveryTask = () => { const row = task(); row.result.discoveryState = { resumeAvailable: true,
        searchMode: 'LITVAR_ONLY', sha256: sha, pendingPapers: 1, pendingQueries: 0 }; return row; };
    await check('discovery cannot repeat only the processed blocked paper even with positive pending count', () => {
        for (const code of ['NO_PROGRESS_CONFIGURATION_OR_CACHE_REQUIRED', 'FACT_CONFLICT', 'EXTERNAL_OR_UNSUPPORTED_REQUIREMENT', 'NO_SUPPORTED_PENDING_TARGETS', 'SOURCE_REVIEW_CACHE_MISSING']) {
            const row = discoveryTask(); row.result.papers = [{ ...paper, continuation: { eligible: false, reasonCode: code } }];
            row.result.acquisition = [{ pmcid: paper.pmcid, status: 'REGISTERED', reasonCode: null }];
            assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), false, code);
        }
    });
    await check('discovery pending counts or article metadata alone never invent unread scope', () => {
        const row = discoveryTask(); row.result.discovery = { articles: [{ pmcid: 'PMC456' }] };
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), false);
        row.result.acquisition = [{ pmcid: 'PMC456', status: 'UNAVAILABLE', reasonCode: 'PAPER_NOT_OPEN_ACCESS' }];
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), false);
    });
    await check('real acquisition array keeps other registered unread paper available', () => {
        const row = discoveryTask(); row.result.papers = [{ ...paper, continuation: { eligible: false, reasonCode: 'NO_PROGRESS_CONFIGURATION_OR_CACHE_REQUIRED' } }];
        row.result.acquisition = [{ pmcid: 'PMC123', status: 'REGISTERED' }, { pmcid: 'PMC456', status: 'REGISTERED' }];
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), true);
    });
    await check('deferred acquisition budget exposes actual unprocessed source rather than processed paper', () => {
        const row = discoveryTask(); row.result.acquisition = [{ pmcid: 'PMC456', status: 'UNAVAILABLE', reasonCode: 'PAPER_ACQUISITION_PAPER_BUDGET' }];
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), true);
        row.result.acquisition[0].pmcid = 'PMC123';
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), false);
    });
    await check('network-disabled acquisition alone cannot cause repeated identical discovery', () => {
        const row = discoveryTask(); row.result.acquisition = [{ pmcid: 'PMC456', status: 'UNAVAILABLE', reasonCode: 'PAPER_DOWNLOAD_NETWORK_DISABLED' }];
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), false);
    });
    await check('remaining queries resume only after durable pending paper queue is empty', () => {
        const row = discoveryTask(); row.result.discoveryState.pendingQueries = 1;
        row.result.acquisition = [{ pmcid: 'PMC123', status: 'REGISTERED' }];
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), false);
        row.result.discoveryState.pendingPapers = 0;
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), true);
    });
    await check('discovery needs authentic checkpoint and known positive remaining scope', () => {
        const row = discoveryTask(); row.result.acquisition = [{ pmcid: 'PMC456', status: 'REGISTERED' }];
        delete row.result.discoveryState.sha256; assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), false);
        row.result.discoveryState.sha256 = sha; row.result.discoveryState.pendingPapers = 0;
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), false);
        delete row.result.discoveryState.pendingQueries; assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row]), false);
    });
    await check('existing discovery child prevents repeating its parent queue', () => {
        const row = discoveryTask(), child = task({ taskId: other }); row.result.acquisition = [{ pmcid: 'PMC456', status: 'REGISTERED' }];
        child.result.discoveryContinuationOf = { parentTaskId: id };
        assert.equal(publicLiteratureActionAvailable(row, discoveryResume, [row, child]), false);
    });
    await check('need-driven reading accepts only original server-supported continuation reasons', () => {
        const row = task(); row.result.papers = [{ ...paper, needReading: {}, continuation: { eligible: true, reasonCode: 'DENOVO_BUNDLE_READING_CALL_LIMIT' } }];
        assert.equal(publicLiteratureActionAvailable(row, resume, [row]), true);
        row.result.papers[0].continuation.reasonCode = 'READY_FOR_ORIGINAL_EVALUATION';
        assert.equal(publicLiteratureActionAvailable(row, resume, [row]), false);
    });
    await check('existing child prevents repeated parent paper continuation', () => {
        const row = task(), child = task({ taskId: other }); child.result.continuationOf = { parentTaskId: id, pmcid: 'PMC123' };
        assert.equal(publicLiteratureActionAvailable(row, resume, [row, child]), false);
    });
    await check('candidate intake requires exact live paper and material digest', () => {
        const row = task();
        assert.equal(publicLiteratureActionAvailable(row, { ...resume, accept: true }, [row]), false);
        row.result.papers[0] = { ...paper, candidateIntake: { sha256: sha } };
        assert.equal(publicLiteratureActionAvailable(row, { ...resume, accept: true }, [row]), true);
    });
    await check('legacy status without optional ownership fields stays readable', () => {
        assert.equal(validLiteratureTask(task()).taskId, id);
        assert.throws(() => validLiteratureTask(task({ scientificExecution: true })), /不相容/);
    });
    await check('source entry SSR exposes return/read controls and no create-task control', () => {
        const html = renderToStaticMarkup(React.createElement(PublicPanel, { analysisId: 'source', topN: 2, readOnlyEntry: true, onReturn() { return undefined; } }));
        assert.match(html, /返回证据详情/); assert.match(html, /只读刷新文献任务/);
        assert.doesNotMatch(html, /准备本次公开文献任务/); assert.doesNotMatch(html, /启动公开文献任务/);
        const normal = renderToStaticMarkup(React.createElement(PublicPanel, { analysisId: 'source', topN: 2 }));
        assert.match(normal, /准备本次公开文献任务/);
    });
    const sourceTask = { schemaVersion: 'denovo-source-task-v1', taskId: id, status: 'READY', resultSha256: sha,
        scientificExecution: false, modelCalls: 0 };
    const run = { analysisId: 'source', runId: 'run-1', localBatch: { modules: { deNovo: { sourceTask } } } };
    await check('terminal de novo detail uses actual fixed run endpoint GET, never public-task endpoint', async () => {
        const calls = [], observed = [], errors = [];
        const adapter = axios.defaults.adapter;
        axios.defaults.adapter = async config => {
            calls.push({ method: config.method, url: config.url, params: config.params });
            return { data: { ...sourceTask, analysisId: run.analysisId, runId: run.runId, savedResultChanged: false }, config, status: 200, statusText: 'OK', headers: {} };
        };
        const stop = readDeNovoSourceEntry({ run, onStatus: value => observed.push(value), onError: error => errors.push(error) });
        await tick(); stop(); axios.defaults.adapter = adapter;
        assert.deepEqual(calls, [{ method: 'get', url: '/variant-analyses/source/acmg/runs/run-1/denovo-source', params: { workflow: 'CURRENT' } }]);
        assert.equal(observed.length, 1); assert.deepEqual(errors, []);
    });
    await check('lazy run without source metadata explicitly looks up actual fixed binding once', async () => {
        const observed = [], errors = []; let reads = 0;
        const stop = readDeNovoSourceEntry({ run: {analysisId: 'source', runId: 'run-1'}, onStatus: v => observed.push(v), onError: e => errors.push(e),
            read: async (analysisId, runId, workflow) => { reads++; assert.equal(workflow, 'CURRENT'); return {...sourceTask,analysisId,runId,savedResultChanged:false}; } });
        await tick(); stop(); assert.equal(reads,1); assert.equal(observed.length,1); assert.deepEqual(errors,[]);
        const html=renderToStaticMarkup(React.createElement(SourceNotice,{run:{analysisId:'source',runId:'run-1'},readOnlyEntry:true}));
        assert.match(html,/只读核对实际资料任务/); assert.doesNotMatch(html,/患者资料不足/);
    });
    await check('lazy fixed lookup rejects wrong returned run and treats binding unavailable as technical', async () => {
        for (const mode of ['wrong-run','unavailable']) {
            const observed=[], errors=[];let reads=0;
            const stop=readDeNovoSourceEntry({run:{analysisId:'source',runId:'run-1'},onStatus:v=>observed.push(v),onError:e=>errors.push(e),read:async()=>{
                reads++;if(mode==='unavailable')throw Error('DENOVO_TASK_SAVED_BINDING_UNAVAILABLE');
                return {...sourceTask,analysisId:'source',runId:'other',savedResultChanged:false};}});
            await tick();stop();assert.equal(reads,1);assert.equal(observed.length,0);assert.equal(errors.length,1);
        }
    });
    await check('closing lazy source view before lookup microtask prevents stale reads', async () => {
        let reads=0;
        const stop=readDeNovoSourceEntry({run:{analysisId:'source',runId:'run-1'},onStatus(){throw Error('stale status');},onError(){throw Error('stale error');},read:async()=>{reads++;return null;}});
        stop();await tick();assert.equal(reads,0);
    });
    await check('de novo binding unavailable is technical error and makes zero reads', async () => {
        const errors = [], calls = [];
        const stop = readDeNovoSourceEntry({ run: { ...run, runId: null }, onStatus() { return undefined; }, onError: error => errors.push(error), read: async (...args) => calls.push(args) });
        await tick(); stop(); assert.equal(calls.length, 0); assert.match(errors[0], /任务绑定/);
        const html = renderToStaticMarkup(React.createElement(SourceNotice, { run: {}, readOnlyEntry: true, onReturn() { return undefined; } }));
        assert.match(html, /技术状态问题/); assert.match(html, /不表示患者资料不足/); assert.match(html, /返回证据详情/);
    });
    await check('de novo mismatched task identity is rejected without retry', async () => {
        const errors = [], observed = []; let reads = 0;
        const stop = readDeNovoSourceEntry({ run, onStatus: value => observed.push(value), onError: error => errors.push(error), read: async () => {
            reads++; return { ...sourceTask, taskId: other, analysisId: run.analysisId, runId: run.runId, savedResultChanged: false };
        } });
        await tick(); stop(); assert.equal(reads, 1); assert.equal(observed.length, 0); assert.match(errors[0], /身份或状态不相容/);
    });
    await check('de novo pending polling is bounded GET-only and stops at terminal state', async () => {
        const scheduled = [], observed = []; let reads = 0;
        const stop = readDeNovoSourceEntry({ run: { ...run, localBatch: { modules: { deNovo: { sourceTask: { ...sourceTask, status: 'RUNNING' } } } } },
            onStatus: value => observed.push(value.status), onError: error => { throw Error(error); },
            read: async () => ({ ...sourceTask, status: ++reads === 1 ? 'RUNNING' : 'READY', analysisId: run.analysisId, runId: run.runId, savedResultChanged: false }),
            schedule: fn => { scheduled.push(fn); return scheduled.length; }, unschedule() { return undefined; } });
        await tick(); assert.equal(scheduled.length, 1); scheduled[0](); await tick(); stop();
        assert.deepEqual(observed, ['RUNNING', 'READY']); assert.equal(reads, 2); assert.equal(scheduled.length, 1);
    });
    console.log(`SOURCE_PUBLIC_ENTRY_CHECKS ${passed} passed; synthetic async/SSR only, browser/HTTP acceptance NOT_RUN`);
})().catch(error => { console.error(error); process.exitCode = 1; });
