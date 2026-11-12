// Public reader integration with explicitly synthetic population controls.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import frontendModuleUrl from './frontend-module-url.cjs';
import { syntheticPopulation, rebindSyntheticPolicy } from './acmg-sequence-population-checks.mjs';
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url), 'utf8')
    + '\nexport { sequenceTransportValid, sequenceFailureDetails };';
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`));
let checks = 0;
const test = (name, action) => { action(); checks++; console.log('PASS ' + name); };
const entry = value => {
    value = structuredClone(value); value.candidateId = 'SYNTHETIC_CANDIDATE'; value.hypothesisId = 'SYNTHETIC_HYPOTHESIS';
    return h.evidenceCards({ candidateId: value.candidateId, attempts: [{ hypothesisId: value.hypothesisId, context: value.context, bs1: value }] },
        ['ACMG_AMP_2015:BS1'], 'candidate', '0')[0].entries[0];
};
for (const af of [true, false]) {
    const value = syntheticPopulation(af), before = JSON.stringify(value), name = af ? 'AF' : 'FAF95';
    test(name + ' completed frozen sex-stratum result is readable through public card adapter', () => {
        const e = entry(value); assert.equal(e.displayIssue, undefined); assert.equal(e.evaluation.evaluationResult.status, 'NOT_MET');
        assert.ok(e.details.some(([label, text]) => label === '所选祖源 × 核型' && text.includes('nfe × XY')));
        assert.ok(e.details.some(([label, text]) => label === '质量语义' && text.includes('两者不混用')));
        assert.equal(e.details.some(([label]) => label === 'FAF 所选祖源'), false);
        assert.equal(JSON.stringify(value), before);
    });
    for (const [name, mutate] of [['JOINT relabel', v => { v.dataScope.dataset = 'JOINT'; }],
        ['changed score', v => { v.evaluation.evaluationResult.status = 'MET'; v.evaluation.appliedStrength = 'STRONG'; }],
        ['source failure', v => { v.observation.carrier.resourceExecution.status = 'FAILED'; }],
        ['raw/selected stratum mismatch', v => { v.qualityDecision.frequencySelection.selectedKaryotype = 'XX'; }],
        ['background mismatch', v => { v.context.variant.evidenceQueryAllele.alternate = 'C'; }],
        ['public JOINT measurement conflict', v => { v.evidence.measurements = [{ metricId: 'gnomad.joint.faf95max', rawValue: '1' }]; }],
        ['public scope policy relabel', v => { v.dataScope.policyId = 'BS1_JOINT_SEX_V1'; }],
        ['public scope version mismatch', v => { v.dataScope.version = '9.0.0'; }]])
        test((af ? 'AF' : 'FAF95') + ' adapter rejects ' + name + ' without inventing missing-case or negative evidence', () => {
            const bad = structuredClone(value); mutate(bad);
            if (name.startsWith('public ')) rebindSyntheticPolicy(bad);
            const e = entry(bad);
            assert.equal(e.displayIssue, true); assert.equal(e.evaluation, undefined); assert.ok(e.summary.includes('展示校验'));
        });
}
for (const status of ['MET', 'NOT_MET']) test('FAILED VEP complete JSON cannot be accepted as ' + status, () => {
    const row = { status, details: { sourceExecutionReceipt: { status: 'FAILED' }, sourceExecutionAccepted: false } };
    assert.equal(h.sequenceTransportValid(row), false);
    assert.ok(h.sequenceFailureDetails(row).some(([, value]) => value.includes('失败')));
});
test('incomplete output remains rejected even when execution status claims success', () => {
    assert.equal(h.sequenceTransportValid({ status: 'MET', details: { sourceExecutionReceipt: { status: 'SUCCEEDED' },
        sourceOwnerReceipt: { success: true, ownedExecutionReceipt: { outputReadComplete: false } } } }), false);
});
test('retained diagnostic NE remains readable as a failure and never source success', () => {
    const row = { status: 'NOT_EVALUATED', details: { sourceExecutionReceipt: { status: 'FAILED' }, sourceExecutionAccepted: false,
        scope: 'RETAINED_ACTUAL_VEP_DIAGNOSTIC_REPLAY' } };
    assert.equal(h.sequenceTransportValid(row), true);
    assert.ok(h.sequenceFailureDetails(row).some(([, value]) => value.includes('诊断回放')));
    assert.ok(h.sequenceFailureDetails({ status: 'MET', details: { sourceExecutionReceipt: { status: 'SUCCEEDED' } } }).length);
    assert.deepEqual(h.sequenceFailureDetails({ status: 'MET', details: { sourceExecutionAccepted: true,
        sourceExecutionReceipt: { status: 'SUCCEEDED' }, sourceOwnerReceipt: { success: true, ownedExecutionReceipt: { outputReadComplete: true } } } }), []);
});
test('all non-success receipts and unproven completeness fail closed while legacy absence remains readable', () => {
    for (const status of ['TIMED_OUT', 'UNAVAILABLE', 'PENDING', 'CANCELLED', 'UNKNOWN']) {
        const row = { status: 'MET', details: { sourceExecutionReceipt: { status } } };
        assert.equal(h.sequenceTransportValid(row), false); assert.ok(h.sequenceFailureDetails(row).length);
    }
    for (const complete of [undefined, null, false]) assert.equal(h.sequenceTransportValid({ status: 'MET', details: {
        sourceExecutionAccepted: true, sourceExecutionReceipt: { status: 'SUCCEEDED' },
        sourceOwnerReceipt: { success: true, ownedExecutionReceipt: { outputReadComplete: complete } } } }), false);
    assert.equal(h.sequenceTransportValid({ status: 'MET', details: {} }), true);
});
console.log(checks + ' sequence/population public adapter checks passed; A10 controls remain SYNTHETIC.');
