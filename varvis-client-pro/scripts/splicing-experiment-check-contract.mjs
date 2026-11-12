import assert from 'node:assert/strict';
import fs from 'node:fs';
const raw = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/splicingExperimentCheck.js', import.meta.url), 'utf8');
const { compatibleCheck, experimentPrompt, safeSourceUrl, savedCheckVersions, initialExperimentCheck } = await import(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`);
let count = 0;
const test = (label, fn) => { fn(); count++; console.log(`PASS ${label}`); };
const evaluation = { evaluationId: 'eval', contextId: 'context', criterionId: 'ACMG_AMP_2015:BP7', criterionApplicability: { status: 'APPLICABLE' }, evaluationResult: { status: 'MET' } };
const candidate = { candidateId: 'candidate', attempts: [{ context: { contextId: 'context' }, transcriptInput: { predictionPath: 'SPLICEAI_SYNONYMOUS' }, criterionEvaluations: [evaluation] }] };
const check = { candidateId: 'candidate', evaluationId: 'eval', contextId: 'context', criterionId: evaluation.criterionId, status: 'OBSERVATION_MISMATCH_REVIEW_REQUIRED', recordIds: ['paper'] };
const snapshot = { schemaVersion: 'splicing-experiment-check-v1', runId: 'run', analysisId: 'analysis', checkId: 'a'.repeat(64), checks: [check], material: { catalog: { records: [{ recordId: 'paper', geneSymbol: 'LMNA' }] } } };
test('conflict visible without changing the original MET', () => {
    const before = JSON.stringify(candidate);
    const prompt = experimentPrompt(candidate, evaluation, snapshot);
    assert.equal(prompt.severity, 'warning'); assert.match(prompt.label, /待复核/);
    assert.equal(prompt.records.length, 1); assert.equal(JSON.stringify(candidate), before);
});
test('no matching record is limited coverage, not experimentally normal', () => {
    const prompt = experimentPrompt(candidate, evaluation, { checks: [] });
    assert.equal(prompt.label, '未核验'); assert.match(prompt.reason, /不代表实验正常.*可继续分析/);
});
test('uncertain transcript remains unverified', () => {
    const prompt = experimentPrompt(candidate, evaluation, { checks: [{ ...check, status: 'UNVERIFIED', reasonCode: 'TRANSCRIPT_OR_GENE_APPLICABILITY_UNCERTAIN' }] });
    assert.equal(prompt.label, '未核验'); assert.match(prompt.reason, /适用条件尚未确认/);
});
test('material unavailable and transport failure both remain visible', () => {
    assert.equal(experimentPrompt(candidate, evaluation, { checks: [{ ...check, status: 'UNAVAILABLE' }] }).severity, 'error');
    assert.equal(experimentPrompt(candidate, evaluation, snapshot, true).label, '核验资料不可用');
});
test('other candidate or context cannot receive a conflict', () => {
    for (const changed of [{ candidateId: 'other' }, { contextId: 'other' }, { evaluationId: 'other' }]) {
        assert.equal(experimentPrompt(candidate, evaluation, { checks: [{ ...check, ...changed }] }).label, '未核验');
    }
});
test('BA1, PM2, AM and BP7 not-applicable are excluded', () => {
    for (const code of ['BA1', 'PM2', 'PP3']) assert.equal(experimentPrompt(candidate, { ...evaluation, criterionId: `ACMG_AMP_2015:${code}` }, snapshot), null);
    assert.equal(experimentPrompt(candidate, { ...evaluation, criterionApplicability: { status: 'NOT_APPLICABLE' } }, snapshot), null);
    const am = structuredClone(candidate); am.attempts[0].transcriptInput.predictionPath = 'ALPHAMISSENSE_MISSENSE';
    assert.equal(experimentPrompt(am, evaluation, snapshot), null);
});
test('saved snapshot cannot cross runs and malformed versions are rejected', () => {
    assert.equal(compatibleCheck(snapshot, { runId: 'run', analysisId: 'analysis' }), true);
    assert.equal(compatibleCheck(snapshot, { runId: 'other', analysisId: 'analysis' }), false);
    assert.equal(compatibleCheck({ ...snapshot, checkId: '../file' }, snapshot), false);
});
test('sources only permit safe HTTPS URLs', () => {
    assert.equal(safeSourceUrl('https://pubmed.ncbi.nlm.nih.gov/12714972/'), 'https://pubmed.ncbi.nlm.nih.gov/12714972/');
    for (const url of ['javascript:alert(1)', 'file:///x', 'https://user:secret@example.com', 'invalid']) assert.equal(safeSourceUrl(url), undefined);
});
const good = { checkId: 'b'.repeat(64), availability: 'AVAILABLE' };
const bad = { checkId: 'c'.repeat(64), availability: 'UNAVAILABLE' };
for (const [label, rows, expected] of [
    ['mixed history reads valid version', [good, bad], [['get'], ['get', `/${good.checkId}`]]],
    ['all corrupt history never creates a check', [bad], [['get']]],
    ['empty history creates once and reloads versions', [], [['get'], ['post'], ['get']]],
]) {
    const calls = [];
    const result = await initialExperimentCheck(async (...args) => {
        calls.push(args); return args.length === 1 && args[0] === 'get' ? rows : snapshot;
    });
    assert.deepEqual(calls, expected, label);
    if (rows.length === 1) assert.equal(result.snapshot, null);
    count++; console.log(`PASS ${label}`);
}
for (const [label, request] of [
    ['storage failure never becomes empty history', async () => { throw Error('503'); }],
    ['invalid list never becomes empty history', async () => ({ status: 'UNAVAILABLE' })],
]) {
    let posts = 0;
    await assert.rejects(initialExperimentCheck(async (...args) => { if (args[0] === 'post') posts++; return request(); }));
    assert.equal(posts, 0); count++; console.log(`PASS ${label}`);
}
test('old version rows remain compatible and invalid identifiers fail closed', () => {
    assert.equal(savedCheckVersions([{ checkId: good.checkId }]).available.length, 1);
    for (const rows of [[{checkId: '../bad'}], [good, good], [{...good, availability: 'BROKEN'}]]) assert.throws(() => savedCheckVersions(rows));
});
console.log(`${count}/${count} contracts passed`);
