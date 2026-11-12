import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
const load = async name => import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(fs.readFileSync(new URL(`../src/pages/varvis/ChromosomeVisualization/utils/${name}.js`, import.meta.url), 'utf8')).toString('base64')}`));
const { EVIDENCE_GROUPS, criterionId, toggleGroup, orderedSelection } = await load('acmgEvidenceSelection');
const api = await load('acmgInput');
const { verifyCompleteExport } = await load('acmgResultView');
const { prepareAutomaticLiterature } = await load('automaticAcmg');
const all = EVIDENCE_GROUPS.flatMap(g => g.items.map(([code]) => criterionId(code)));
assert.equal(all.length, 18); assert.equal(new Set(all).size, 18);
assert.deepEqual(orderedSelection(all, all), all);
assert.deepEqual(toggleGroup(all, all, all), []);
assert.deepEqual(toggleGroup([], [criterionId('PM3')], all), [criterionId('PM3')]);
assert.deepEqual(toggleGroup([criterionId('PM3')], all.slice(-4), all), all.slice(-4));
for (const selected of ['PM3', 'PP1', 'PS2', 'PM6', 'PM2']) {
    const calls = [];
    await prepareAutomaticLiterature({ analysisId: 'synthetic', topN: 1,
        declaration: { selectedCriteria: [criterionId(selected)], operationId: 'new-run', hypotheses: [{ hypothesis: { candidateId: 'v', hypothesisId: 'h', moi: 'AR' } }] },
        progress() {}, get: async url => { calls.push(url); return { enabled: false }; },
        post: async (url, body) => {
            calls.push(url);
            assert.equal(body.declaration?.operationId, undefined); assert.equal(body.declaration?.selectedCriteria, undefined);
            if (url.endsWith('/pp1/local-review')) return {schemaVersion:'pp1-local-review-plan-v1', planId:'a'.repeat(64),
                attempts:[{candidateId:'v', hypothesisId:'h'}]};
            if (url.endsWith('/local-review')) return {schemaVersion:'denovo-local-discovery-plan-v1', candidateIds:[]};
            return { taskId: 'a'.repeat(64), status: 'READY' };
        } });
    assert.equal(calls.length, ['PM2', 'PM3'].includes(selected) ? 0 : selected === 'PP1' ? 2 : 1, `${selected}: only selected preparation route`);
    if (selected === 'PM3') assert.deepEqual(calls, [], 'PM3 uses server-side source preparation');
    if (selected === 'PP1') {
        assert.match(calls[0], /\/pp1\/local-review$/);
        assert.match(calls[1], /\/pp1\/tasks$/);
    }
}
const dir = process.argv[2];
assert.ok(dir, 'Pass the isolated Java saved-result fixture directory');
let runs = 0, cards = 0;
for (const name of fs.readdirSync(dir).filter(n => n.endsWith('.json'))) {
    const run = JSON.parse(fs.readFileSync(path.join(dir, name), 'utf8'));
    api.validateSavedRun(run, run.analysisId);
    assert.equal(api.localBatchScopeIssue(run), null, name);
    verifyCompleteExport(run, run);
    for (const candidate of run.candidates) {
        for (const attempt of candidate.attempts) {
            assert.equal(api.localBatchAttemptIssue(candidate, attempt, run.enabledCriteria, run.executionProfile), null, `${name}: ${JSON.stringify(attempt.localBatch)}`);
        }
        const shown = api.evidenceCards(candidate, run.enabledCriteria, undefined, undefined, run);
        assert.deepEqual(shown.map(c => c.id), run.enabledCriteria, name);
        assert.ok(shown.every(c => c.status !== 'INVALID'), name);
        cards += shown.length;
    }
    const broken = structuredClone(run); broken.executionProfile.enabledCriteria.push('ACMG_AMP_2015:UNKNOWN');
    assert.ok(api.localBatchScopeIssue(broken));
    assert.throws(() => verifyCompleteExport(broken, run));
    runs++;
}
assert.ok(runs >= 18, 'Require each configured single criterion, mixed and all-selected output');
console.log(`PASS: 18 checkbox choices, 5 preparation-isolation scenarios, ${runs} saved scopes, ${cards} cards, tampered scope rejection.`);
