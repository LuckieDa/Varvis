import frontendModuleUrl from './frontend-module-url.cjs';
// Transport projection test only: never updates a saved run or a scientific ledger.
import fs from 'node:fs';
import assert from 'node:assert/strict';
const moduleUrl = s => frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(s).toString('base64')}`);
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url), 'utf8');
const api = await import(moduleUrl(source));
const workbench = await import(moduleUrl(fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js', import.meta.url), 'utf8').replace("'./acmgInput'", JSON.stringify(moduleUrl(source)))));
const [archivePath, replayPath, outputPath] = process.argv.slice(2);
const archiveBytes = fs.readFileSync(archivePath), archive = JSON.parse(archiveBytes);
const replay = JSON.parse(fs.readFileSync(replayPath));
const projection = structuredClone(archive), checks = [];
const check = (name, fn) => { fn(); checks.push(name); };
function replaceBindings(node) {
    if (!node || typeof node !== 'object') return;
    for (const [key, value] of Object.entries(node)) {
        if (key === 'deNovo' && value?.version?.startsWith('denovo-selected-batch-')) node[key] = replay.identity;
        else replaceBindings(value);
    }
}
replaceBindings(projection);
for (const candidate of projection.candidates) for (const attempt of candidate.attempts) {
    const match = replay.rows.find(r => r.contextId === attempt.context.contextId);
    assert.ok(match, 'every archived context must have a replay result');
    assert.deepEqual(attempt.context.patientContext, match.patientContext);
    for (const row of attempt.localBatch.criteria) {
        const code = row.criterionId.split(':')[1], result = match.result.criteria[code];
        if (!result) continue;
        row.status = result.evaluationResult.status;
        row.reasonCode = result.evaluationResult.reasonCode;
        row.appliedStrength = result.appliedStrength;
        row.details.deNovoFacts = match.result.publicSummary;
    }
}
const cards = run => run.candidates.flatMap(c => c.attempts.flatMap((_, i) =>
    workbench.backgroundEvidenceCards(c, run.enabledCriteria, run, i).flatMap(card => card.entries)));
check('old history remains accepted', () => assert.ok(cards(archive).every(e => !e.displayIssue)));
check('new module versions and all 27 contexts remain accepted', () => {
    const entries = cards(projection);
    assert.equal(entries.length, 486);
    assert.ok(entries.every(e => !e.displayIssue), entries.find(e => e.displayIssue)?.displayIssue);
});
check('two replayed literature positives coexist with UNKNOWN current patient', () => {
    const positives = replay.rows.filter(r => r.result.qualifiedResult?.result?.status === 'MET');
    assert.deepEqual(positives.map(r => [r.gene, r.result.qualifiedResult.result.code, r.result.qualifiedResult.result.strength]),
        [['MECOM', 'PM6', 'SUPPORTING'], ['KCNC3', 'PS2', 'SUPPORTING']]);
    assert.ok(positives.every(r => r.patientContext.affectedStatus === 'UNKNOWN'));
});
check('JSON readback preserves source details and every projected card', () => assert.deepEqual(cards(JSON.parse(JSON.stringify(projection))), cards(projection)));
check('other 16 criteria and patient contexts are unchanged', () => {
    const keep = run => run.candidates.flatMap(c => c.attempts.map(a => ({context: a.context,
        rows: a.localBatch.criteria.filter(r => !['ACMG_AMP_2015:PS2', 'ACMG_AMP_2015:PM6'].includes(r.criterionId))})));
    assert.deepEqual(keep(projection), keep(archive));
});
check('unknown future version is rejected', () => {
    const p = structuredClone(projection.executionProfile);
    p.moduleBindings.deNovo.version = 'denovo-selected-batch-999.0.0';
    assert.ok(api.localBatchAttemptIssue(projection.candidates[0], projection.candidates[0].attempts[0], projection.enabledCriteria, p));
});
check('archive on disk remains byte-identical', () => assert.deepEqual(fs.readFileSync(archivePath), archiveBytes));
const result = {scope: 'OFFLINE_TRANSPORT_PROJECTION_NOT_BUSINESS_RUN', passed: checks.length, checks};
if (outputPath) fs.writeFileSync(outputPath, JSON.stringify(result, null, 2), {flag: 'wx'});
console.log(JSON.stringify(result));
