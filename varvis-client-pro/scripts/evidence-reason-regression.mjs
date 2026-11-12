// Offline projections only. Inputs are read-only archives, never live services.
import fs from 'node:fs';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import frontendModuleUrl from './frontend-module-url.cjs';
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url), 'utf8');
const url = text => frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(text).toString('base64')}`);
const api = await import(url(source));
const workbench = await import(url(fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js', import.meta.url), 'utf8').replace("'./acmgInput'", JSON.stringify(url(source)))));
const checks = [];
const check = (name, fn) => { fn(); checks.push(name); };
const ev = (reason, status = 'NOT_EVALUATED') => ({ criterionId: 'ACMG_AMP_2015:PM2', appliedStrength: 'NONE', evaluationResult: { status, reasonCode: reason } });
for (const [code, d] of Object.entries(api.evidenceReasonDefinitions)) {
    check(`${code}: exact category and explicit explanation`, () => {
        assert.equal(api.evidenceOutcome(ev(code)).kind, d.category);
        assert.equal(api.reasonExplanation(ev(code)).summary, d.summary);
        assert.ok(d.missing && d.impact);
        assert.doesNotMatch(api.reasonHelp(code, undefined, true), /尚未提供可识别/);
    });
}
check('quality rejected is not execution failure; true process failure remains failure', () => {
    assert.equal(api.evidenceOutcome(ev('PM2_LOCAL_INTERVAL_QC_FAILED')).kind, 'QUALITY_REJECTED');
    assert.equal(api.evidenceOutcome(ev('NATIVE_SPLICE_PROCESS_FAILED')).kind, 'EXECUTION_FAILED');
});
check('scientific MET and NOT_MET outrank explanation category', () => {
    for (const s of ['MET', 'NOT_MET']) assert.equal(api.evidenceOutcome(ev('PM2_LOCAL_INTERVAL_QC_FAILED', s)).kind, s);
});
check('unknown code does not pretend to explain missing data', () => {
    assert.equal(api.evidenceOutcome(ev('NEW_UNCLASSIFIED_REASON')).kind, 'UNRESOLVED');
    assert.match(api.reasonHelp('NEW_UNCLASSIFIED_REASON', undefined, true), /维护者/);
});
check('forged metadata cannot convert an unknown result into a positive', () => {
    const e = ev('NEW_UNCLASSIFIED_REASON'); e.explanation = { category: 'MET', status: 'MET' };
    assert.equal(api.evidenceOutcome(e).kind, 'UNRESOLVED');
    const q = ev('PM2_LOCAL_INTERVAL_QC_FAILED');
    assert.equal(api.reasonExplanation(q, { schemaVersion: 'evidence-reason-explanation-v1', status: 'NOT_EVALUATED', reasonCode: 'OTHER' }), undefined);
});
check('historical PVS1 summary explains evaluator exclusion without mutating original', () => {
    const c = { criterionId: 'ACMG_AMP_2015:PVS1', status: 'NOT_EVALUATED', appliedStrength: 'NONE', reasonCode: 'PVS1_CONSEQUENCE_NOT_APPLICABLE', details: {
        modulePointer: '/modules/pvs1/results/0', materialPreparation: { schemaVersion: 'material-preparation-summary-v1', status: 'UNRESOLVED', module: 'pvs1', moduleResultPointer: '/modules/pvs1/results/0', reasonCode: 'PVS1_CONSEQUENCE_NOT_APPLICABLE' } } };
    const before = JSON.stringify(c);
    assert.match(api.materialPreparationDetails(c)[0][1], /原评价器.*不适用/);
    assert.equal(JSON.stringify(c), before);
    c.details.materialPreparation.status = 'NOT_APPLICABLE';
    assert.match(api.materialPreparationDetails(c)[0][1], /不表示取证前已跳过/);
    c.reasonCode = c.details.materialPreparation.reasonCode = 'PVS1_MECHANISM_SOURCE_NOT_FOUND';
    assert.match(api.materialPreparationDetails(c)[0][1], /不相容/);
});
check('new categories have distinct dashboard styles and counts', () => {
    for (const kind of ['QUALITY_REJECTED', 'POLICY_NOT_READY']) {
        assert.equal(workbench.resultStyle(kind).kind, kind);
        assert.equal(workbench.resultStatusCounts([{ outcome: { kind } }]).find(x => x.kind === kind).count, 1);
    }
});
const [input, output, definitions, previousSource] = process.argv.slice(2);
if (definitions) check('backend and frontend exact registry parity', () => assert.deepEqual(api.evidenceReasonDefinitions, JSON.parse(fs.readFileSync(definitions))));
let archived;
if (input) {
    const bytes = fs.readFileSync(input), run = JSON.parse(bytes), before = JSON.stringify(run);
    const rows = [];
    for (const c of run.candidates) for (let i = 0; i < c.attempts.length; i++)
        for (const card of workbench.backgroundEvidenceCards(c, run.enabledCriteria, run, i)) for (const e of card.entries)
            rows.push({ code: card.code, entry: e, outcome: workbench.resultOutcome(e), summary: api.evidenceSummary(e) });
    check('all backgrounds retain 18 individually associated rows', () => {
        assert.equal(rows.length, run.candidates.reduce((n, c) => n + c.attempts.length * run.enabledCriteria.length, 0));
        assert.ok(rows.every(r => !r.entry.displayIssue));
    });
    check('saved batch has no unspecified-summary fallback', () => assert.ok(rows.every(r => !r.summary.includes('尚未提供可识别'))));
    check('original archive object is not modified by display', () => assert.equal(JSON.stringify(run), before));
    check('new optional descriptors survive JSON readback and retain historical result association', () => {
        const copy = JSON.parse(before);
        for (const candidate of copy.candidates) for (const attempt of candidate.attempts) {
            for (const row of attempt.localBatch?.criteria || []) {
                const definition = api.reasonExplanation(row);
                if (definition) row.details.explanation = { schemaVersion: 'evidence-reason-explanation-v1', status: row.status, reasonCode: row.reasonCode, ...definition };
                if (row.reasonCode === 'PVS1_CONSEQUENCE_NOT_APPLICABLE' && row.details?.materialPreparation) row.details.materialPreparation.status = 'NOT_APPLICABLE';
            }
            const definition = api.reasonExplanation(attempt.bs1?.evaluation);
            if (definition) attempt.bs1.explanation = { schemaVersion: 'evidence-reason-explanation-v1', ...attempt.bs1.evaluation.evaluationResult, ...definition };
        }
        const restored = JSON.parse(JSON.stringify(copy));
        const summaries = [];
        for (const c of restored.candidates) for (let i = 0; i < c.attempts.length; i++)
            for (const card of workbench.backgroundEvidenceCards(c, restored.enabledCriteria, restored, i)) for (const e of card.entries) {
                assert.ok(!e.displayIssue);
                summaries.push(api.evidenceSummary(e));
            }
        assert.deepEqual(summaries, rows.map(r => r.summary));
    });
    const counts = {};
    rows.forEach(r => { counts[r.outcome.kind] = (counts[r.outcome.kind] || 0) + 1; });
    const negatives = rows.filter(r => r.outcome.kind === 'NOT_MET');
    check('negative summaries use saved comparisons', () => {
        for (const row of negatives) {
            if (row.code === 'BS1') assert.match(row.summary, /FAF95=.*未满足.*0\.00025/);
            if (row.code === 'BP4') assert.match(row.summary, /蛋白错义.*ALPHAMISSENSE.*未达到/);
        }
    });
    const bs1 = run.candidates.flatMap(c => c.attempts).find(a => a.bs1?.evaluation?.evaluationResult?.status === 'NOT_MET')?.bs1;
    if (bs1) check('BS1 refuses fabricated condition narrative when trace or mode does not bind', () => {
        const changed = structuredClone(bs1);
        changed.preparation.policy.tiers[0].conditions[0].decimal = '0.5';
        assert.equal(api.bs1NegativeSummary(changed), undefined);
        changed.preparation.selectedMode = 'OTHER';
        assert.equal(api.bs1NegativeSummary(changed), undefined);
    });
    if (previousSource) {
        const previous = await import(url(fs.readFileSync(previousSource, 'utf8')));
        const projection = (impl) => run.candidates.flatMap(c => impl.evidenceCards(c, run.enabledCriteria, undefined, undefined, run)
            .flatMap(card => card.entries.map(e => ({ code: card.code, evaluation: JSON.parse(JSON.stringify(e.evaluation)), raw: crypto.createHash('sha256').update(JSON.stringify(e.raw) ?? 'undefined').digest('hex') }))));
        const original = projection(previous), current = projection(api);
        current.forEach(r => { if (r.evaluation) delete r.evaluation.explanation; });
        check('all original evaluation fields, strength, reasons and raw evidence identical', () => assert.deepEqual(current, original));
    }
    archived = { sha256: crypto.createHash('sha256').update(bytes).digest('hex'), rows: rows.length, counts,
        summaries: rows.map(({code, summary, outcome}) => ({code, summary, category: outcome.kind, reasonCode: outcome.reasonCode})) };
}
const report = { checks: checks.length, passed: checks.length, checksPassed: checks, archived };
if (output) fs.writeFileSync(output, JSON.stringify(report, null, 2), { flag: 'wx' });
console.log(JSON.stringify({ checks: checks.length, counts: archived?.counts }));
