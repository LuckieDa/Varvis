import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { test } from 'node:test';

const root = new URL('../src/pages/varvis/ChromosomeVisualization/', import.meta.url);
const serviceSource = await fs.readFile(new URL('services/cytobands.js', root), 'utf8');
let moduleId = 0;

// Exercise the real cache/retry/normalization code with a controlled HTTP boundary.
async function service(get) {
    const key = `__cytobandSmoke${moduleId++}`;
    globalThis[key] = { get };
    try {
        const source = serviceSource.replace("import axios from 'axios';", `const axios = globalThis.${key};`);
        return await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
    } finally {
        delete globalThis[key];
    }
}

const chromosomes = { data: [{ chromosome: '1' }] };
const bands = { data: [{ band_id: 1, band_name: 'p36', start_bp: 0, end_bp: 100, gie_stain: 'gneg' }] };
const isChromosomes = (url) => url.endsWith('/chromosomes');

test('unavailable backend fails after one request and an explicit retry recovers', async () => {
    const calls = [];
    let available = false;
    const api = await service(async (url, options) => {
        calls.push(url);
        assert.ok(options.timeout <= 20000);
        if (!available) throw new Error('ECONNREFUSED');
        return isChromosomes(url) ? chromosomes : bands;
    });
    await assert.rejects(api.fetchCytobands(), /ECONNREFUSED/);
    assert.equal(calls.length, 1);
    assert.equal(api.getCachedCytobands(), null);
    available = true;
    const result = await api.fetchCytobands();
    assert.deepEqual(result['1'][0], {
        bandId: 1, name: 'p36', start: 0, stop: 100, gieStain: 'gneg', chromosome: '1',
    });
    assert.equal(calls.length, 3);
    assert.strictEqual(api.getCachedCytobands(), result);
});

test('band HTTP failure is not hidden or cached as an empty successful diagram', async () => {
    let failed = true;
    let bandCalls = 0;
    const api = await service(async (url) => {
        if (isChromosomes(url)) return chromosomes;
        bandCalls += 1;
        if (failed) throw Object.assign(new Error('unavailable'), { response: { status: 503 } });
        return bands;
    });
    await assert.rejects(api.fetchCytobands(), /unavailable/);
    assert.equal(api.getCachedCytobands(), null);
    assert.equal(bandCalls, 1);
    failed = false;
    assert.equal(Object.keys(await api.fetchCytobands()).length, 1);
    assert.equal(bandCalls, 2);
});

test('band network failures use only one retry layer', async () => {
    let bandCalls = 0;
    const api = await service(async (url) => {
        if (isChromosomes(url)) return chromosomes;
        bandCalls += 1;
        throw new Error('network timeout');
    });
    await assert.rejects(api.fetchCytobands(), /network timeout/);
    assert.equal(bandCalls, 3);
    assert.equal(api.getCachedCytobands(), null);
});

for (const emptyTarget of ['chromosomes', 'bands']) {
    test(`empty ${emptyTarget} can be fetched again immediately after repair`, async () => {
        let empty = true;
        const api = await service(async (url) => {
            if (empty && (isChromosomes(url) === (emptyTarget === 'chromosomes'))) return { data: [] };
            return isChromosomes(url) ? chromosomes : bands;
        });
        await assert.rejects(api.fetchCytobands(), /empty/);
        assert.equal(api.getCachedCytobands(), null);
        empty = false;
        assert.equal((await api.fetchCytobands())['1'].length, 1);
    });
}

test('concurrent callers share requests and subsequent callers reuse complete data', async () => {
    let calls = 0;
    const api = await service(async (url) => {
        calls += 1;
        return isChromosomes(url) ? chromosomes : bands;
    });
    const [first, second] = await Promise.all([api.fetchCytobands(), api.fetchCytobands()]);
    assert.strictEqual(first, second);
    assert.strictEqual(await api.fetchCytobands(), first);
    assert.equal(calls, 2);
});

// Source contracts supplement service tests; these do not claim browser/React rendering coverage.
test('loading contract: optional overviews do not gate chromosome geometry', async () => {
    const hook = await fs.readFile(new URL('hooks/useCytobandData.js', root), 'utf8');
    assert.match(hook, /setCytobands\(bands\);[\s\S]*?setLoading\(false\);[\s\S]*?await Promise\.allSettled/);
    assert.match(hook, /await Promise\.allSettled\([\s\S]*?\);\s*if \(!alive\) return;/);
});

test('retry contract: error view exposes retry, clears the error and reruns loading', async () => {
    const hook = await fs.readFile(new URL('hooks/useCytobandData.js', root), 'utf8');
    const page = await fs.readFile(new URL('index.jsx', root), 'utf8');
    assert.match(hook, /const retry = useCallback\(\(\) => setLoadVersion/);
    assert.match(hook, /setError\(null\);/);
    assert.match(hook, /\[initialCytobands, loadVersion\]/);
    assert.match(page, /onClick=\{retry\}[^>]*>[\s\S]*?Retry loading chromosomes/);
});
