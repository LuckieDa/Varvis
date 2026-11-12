import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { runInNewContext } from 'node:vm';
import test from 'node:test';

const require = createRequire(import.meta.url);
const { parse } = require('@babel/parser');
const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');
const drawer = read(
    '../src/pages/varvis/ChromosomeVisualization/components/VariantAnalysisDrawer.jsx',
);
const service = read('../src/pages/varvis/ChromosomeVisualization/services/variantAnalysis.js');

// Execute the actual callbacks rather than a second implementation of the upload flow.
function functionSource(source, name) {
    let found;
    const visit = (node) => {
        if (!node || typeof node !== 'object') return;
        if (node.type === 'VariableDeclarator' && node.id.name === name) {
            const fn = node.init.type === 'CallExpression' ? node.init.arguments[0] : node.init;
            found = source.slice(fn.start, fn.end);
        }
        for (const value of Object.values(node)) {
            if (Array.isArray(value)) value.forEach(visit);
            else if (value && typeof value === 'object') visit(value);
        }
    };
    visit(parse(source, { sourceType: 'module', plugins: ['jsx'] }));
    assert.ok(found, `Missing production callback ${name}`);
    return found;
}

const submitSource = functionSource(drawer, 'handleSubmit');
const restoreSource = functionSource(drawer, 'restoreAnalysis');
const selectFileSource = functionSource(drawer, 'handleSelectFile');
const historyServiceSource = functionSource(service, 'findVariantAnalysisHistory');
const matchingHistory = {
    matchBasis: 'EXACT_FILE_SHA256',
    historyOnly: true,
    matches: [{ id: 'saved-analysis', status: 'READY', genomeAssembly: 'GRCh37' }],
};

function fixture(overrides = {}) {
    const calls = { query: 0, submit: 0, preflight: 0, fetch: [], events: [], variants: [] };
    const state = {};
    const file = { name: 'same.vcf' };
    const context = {
        selectedFile: file,
        selectedFileRef: { current: file },
        busy: false,
        job: { id: 'currently-running', status: 'RANKING' },
        activeAnalysisIdRef: { current: 'currently-running' },
        restoreAnalysisId: 'saved-analysis',
        inputGenomeAssemblySupported: true,
        inputGenomeAssembly: 'GRCh37',
        analysisMode: 'WES',
        analysisScope: 'STANDARD',
        sampleId: 'synthetic-sample',
        analysisInputError: '',
        trioValidationError: '',
        parsedHpoTerms: [],
        parsedExcludedHpoTerms: [],
        hpoProvenance: [],
        trioEnabled: false,
        findVariantAnalysisHistory: async () => {
            calls.query += 1;
            return matchingHistory;
        },
        loadPreflightStatus: async () => {
            calls.preflight += 1;
            return { applicationReady: true };
        },
        submitVariantAnalysis: async (input) => {
            calls.submit += 1;
            calls.submittedInput = input;
            return { id: 'new-analysis', status: 'UPLOADED' };
        },
        fetchVariantAnalysis: async (id) => {
            calls.fetch.push(id);
            return { id, status: 'READY' };
        },
        activateAnalysisJob: (job) => {
            context.job = job;
            context.activeAnalysisIdRef.current = job?.id;
        },
        loadEvents: async (id) => {
            calls.events.push(id);
        },
        loadVariants: async (id) => {
            calls.variants.push(id);
        },
        clearVariantSelection: () => {
            state.selectionCleared = true;
        },
        analysisRequestErrorMessage: (error) => error.message,
        ...overrides,
    };
    const callbacks = `${submitSource}\n${restoreSource}\n${selectFileSource}`;
    for (const [name] of callbacks.matchAll(/\b[A-Z][A-Z0-9_]+\b/g)) context[name] = name;
    context.ACTIVE_ANALYSIS_STATUSES = new Set(['UPLOADED', 'VALIDATING', 'RANKING']);
    for (const [, name] of callbacks.matchAll(/\b(set\w+)\(/g)) {
        context[name] = (value) => {
            state[name] = value;
            if (name === 'setBusy') context.busy = value;
            if (name === 'setSelectedFile') context.selectedFile = value;
        };
    }
    return {
        context,
        calls,
        state,
        submit: runInNewContext(`(${submitSource})`, context),
        restore: runInNewContext(`(${restoreSource})`, context),
        select: runInNewContext(`(${selectFileSource})`, context),
    };
}

test('history hit preserves current job and does not preflight or submit a new analysis', async () => {
    const f = fixture({ analysisInputError: 'Current inputs need review' });
    await f.submit();
    assert.equal(f.calls.query, 1);
    assert.equal(f.calls.preflight, 0);
    assert.equal(f.calls.submit, 0);
    assert.equal(f.context.job.id, 'currently-running');
    assert.equal(f.state.setHistoryMatches, matchingHistory.matches);
    assert.equal(f.state.setVariantsPage, undefined);
});

test('explicit new-analysis action bypasses lookup but retains normal execution preflight', async () => {
    const f = fixture();
    await f.submit({ createNewAnalysis: true });
    assert.equal(f.calls.query, 0);
    assert.equal(f.calls.preflight, 1);
    assert.equal(f.calls.submit, 1);
    assert.equal(f.calls.submittedInput.createNewAnalysis, true);
    assert.equal(f.calls.submittedInput.file, f.context.selectedFile);
    assert.equal(f.context.job.id, 'new-analysis');
    assert.equal(f.state.setHistoryMatches, null);
});

test('a click event or non-boolean field cannot authorize bypassing history lookup', async () => {
    for (const event of [{ type: 'click' }, { createNewAnalysis: 'true' }]) {
        const f = fixture();
        await f.submit(event);
        assert.equal(f.calls.query, 1);
        assert.equal(f.calls.submit, 0);
    }
});

test('history failure or invalid response never falls through to a new analysis', async () => {
    for (const find of [
        async () => {
            throw new Error('Connection unavailable');
        },
        async () => null,
    ]) {
        const f = fixture({ findVariantAnalysisHistory: find });
        await f.submit();
        assert.equal(f.calls.submit, 0);
        assert.equal(f.calls.preflight, 0);
        assert.match(f.state.setError, /未提交新分析/);
        assert.equal(f.context.job.id, 'currently-running');
    }
});

test('no history match follows the existing submit workflow', async () => {
    const f = fixture({
        job: null,
        findVariantAnalysisHistory: async () => ({ ...matchingHistory, matches: [] }),
    });
    await f.submit();
    assert.equal(f.calls.preflight, 1);
    assert.equal(f.calls.submit, 1);
    assert.equal(f.calls.submittedInput.createNewAnalysis, false);
});

test('while analysis is running, a history miss does not implicitly start another analysis', async () => {
    const f = fixture({
        findVariantAnalysisHistory: async () => ({ ...matchingHistory, matches: [] }),
    });
    await f.submit();
    assert.equal(f.calls.preflight, 0);
    assert.equal(f.calls.submit, 0);
    assert.equal(f.context.job.id, 'currently-running');
    assert.match(f.state.setError, /本次未新建任务/);
});

test('explicit new analysis cannot bypass the existing scientific input or readiness checks', async () => {
    const invalid = fixture({ analysisInputError: 'Missing sample selection' });
    await invalid.submit({ createNewAnalysis: true });
    assert.equal(invalid.calls.submit, 0);
    const unavailable = fixture({ loadPreflightStatus: async () => ({ applicationReady: false }) });
    await unavailable.submit({ createNewAnalysis: true });
    assert.equal(unavailable.calls.submit, 0);
});

test('existing running job can switch to saved history using reads only', async () => {
    const f = fixture();
    await f.restore();
    assert.deepEqual(f.calls.fetch, ['saved-analysis']);
    assert.deepEqual(f.calls.events, ['saved-analysis']);
    assert.equal(f.calls.submit, 0);
    assert.equal(f.calls.query, 0);
    assert.equal(f.context.job.id, 'saved-analysis');
    assert.equal(f.state.setVariantPageNumber, 1);
    assert.equal(f.state.selectionCleared, true);
    assert.equal(f.state.setRestoredHistoryId, 'saved-analysis');
});

test('file selection clears previous matches and discards a stale in-flight history response', async () => {
    let resolveLookup;
    const f = fixture({
        findVariantAnalysisHistory: () =>
            new Promise((resolve) => {
                resolveLookup = resolve;
            }),
    });
    f.state.setHistoryMatches = matchingHistory.matches;
    const pending = f.submit();
    const nextFile = { name: 'different.vcf' };
    f.select(nextFile);
    assert.equal(f.state.setHistoryMatches, null);
    assert.equal(f.context.selectedFileRef.current, nextFile);
    resolveLookup(matchingHistory);
    await pending;
    assert.equal(f.state.setHistoryMatches, null);
    assert.equal(f.calls.submit, 0);
    assert.equal(f.calls.preflight, 0);
});

test('history service sends only the uploaded file to the read-only lookup route', async () => {
    const fields = [];
    let route;
    const find = runInNewContext(`(${historyServiceSource})`, {
        FormData: class {
            append(...args) {
                fields.push(args);
            }
        },
        ANALYSIS_BASE: '/variant-analyses',
        UPLOAD_TIMEOUT_MS: 600000,
        axios: {
            post: async (url) => {
                route = url;
                return { data: matchingHistory };
            },
        },
    });
    const file = { name: 'synthetic.vcf' };
    assert.equal(await find({ file }), matchingHistory);
    assert.equal(route, '/variant-analyses/history');
    assert.deepEqual(fields, [['vcfFile', file]]);
});
