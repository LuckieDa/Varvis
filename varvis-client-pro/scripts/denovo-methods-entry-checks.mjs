// New committed-summary adapter tests using synthetic rows in a read-only
// archived profile scaffold. This is not a captured new Java business result.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import frontendModuleUrl from './frontend-module-url.cjs';
import { syntheticDeNovoFixture as fixture, syntheticDeNovoEvent as event } from './denovo-methods-view-checks.mjs';
const filename = process.argv[2];
if (!filename) throw Error('Supply archived complete run as a read-only profile scaffold');
const bytes = fs.readFileSync(filename), archived = JSON.parse(bytes), before = JSON.stringify(archived);
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url), 'utf8')
    + '\nexport { deNovoBindingValid, deNovoPairValid };';
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`));
let count = 0;
const check = (name, action) => { action(); count++; console.log('PASS ' + name); };
const runWith = value => {
    const run = structuredClone(archived), profile = run.executionProfile, binding = value.profile.moduleBindings.deNovo;
    profile.moduleBindings.deNovo = structuredClone(binding);
    for (const key of ['pm2', 'prediction', 'reference']) profile.moduleBindings[key].runtimeBinding.deNovo = structuredClone(binding);
    const candidate = run.candidates[0];
    for (const attempt of candidate.attempts) for (const row of value.rows) {
        const index = attempt.localBatch.criteria.findIndex(r => r.criterionId === row.criterionId);
        assert.ok(index >= 0); attempt.localBatch.criteria[index] = structuredClone(row);
    }
    return { run, profile, candidate };
};
const entry = value => {
    const v = runWith(value);
    assert.equal(h.localBatchScopeIssue(v.run), null, 'new binding must pass the actual profile startup adapter');
    const cards = h.evidenceCards(v.candidate, v.run.enabledCriteria, 'candidate', '0', v.run);
    return { ...v, card: cards.find(card => card.code === 'PS2').entries[0] };
};
check('new startup binding and replicated runtime bindings are all required', () => {
    const v = runWith(fixture()); assert.equal(h.deNovoBindingValid(v.profile), true);
    v.profile.moduleBindings.prediction.runtimeBinding.deNovo.manifestSha256 = 'b'.repeat(64);
    assert.equal(h.deNovoBindingValid(v.profile), false);
});
check('ordinary new SVI uses its independent adapter through normal public cards', () => {
    const {card} = entry(fixture()); assert.equal(card.displayIssue, false);
    assert.equal(card.evaluation.appliedStrength, 'MODERATE'); assert.ok(card.details.some(([label]) => label === '新发计算方法'));
});
check('AR adjustment displays Supporting instead of rederiving Moderate from raw points', () => {
    const {card} = entry(fixture({adjustment:'ONE_LEVEL_AR_NO_SECOND_P_LP'}));
    assert.equal(card.displayIssue, false); assert.equal(card.evaluation.appliedStrength, 'SUPPORTING');
});
check('AR Supporting-to-none remains NOT_MET while raw half point is preserved', () => {
    const {card} = entry(fixture({events:[event(true,'CONSISTENT_HIGH_HETEROGENEITY')],adjustment:'ONE_LEVEL_AR_NO_SECOND_P_LP'}));
    assert.equal(card.displayIssue, false); assert.equal(card.evaluation.evaluationResult.status, 'NOT_MET');
    assert.ok(card.details.some(([,value]) => String(value).includes('0.5')));
});
check('fixed strength with zero points stays a precise public-proof display dependency', () => {
    const e = {...event(),reasonCode:'DENOVO_EXPERT_TISSUE_STRENGTH',rawQuarterPoints:0};
    const {card} = entry(fixture({events:[e],fixed:true}));
    assert.equal(card.displayIssue, true); assert.equal(card.evaluation, undefined); assert.equal(card.displayOutcome.kind, 'UNSUPPORTED');
    assert.ok(card.summary.includes('公共摘要')); assert.ok(card.details.some(([label]) => label === '固定强度展示核验依赖'));
});
check('combined PS2 without published adopted mode never becomes ordinary PS2', () => {
    const value = fixture({events:[event(false)]});
    for (const row of value.rows) {
        row.details.deNovoFacts.selectedCriterion = 'PS2';
        row.status = row.criterionId.endsWith(':PS2') ? 'MET' : 'NOT_EVALUATED';
        row.appliedStrength = row.criterionId.endsWith(':PS2') ? 'SUPPORTING' : 'NONE';
        row.reasonCode = row.criterionId.endsWith(':PS2') ? 'DENOVO_QUALIFIED_POINTS' : 'DENOVO_SHARED_EVENT_ASSIGNED_TO_PS2';
    }
    for (const row of value.rows) for (const explanation of row.details.deNovoFacts.criterionExplanations) {
        const original = value.rows.find(r => r.criterionId === explanation.criterionId);
        for (const key of ['status','reasonCode','appliedStrength']) explanation[key] = original[key];
    }
    const {card} = entry(value); assert.equal(card.displayIssue,true); assert.equal(card.evaluation,undefined);
    assert.equal(card.displayOutcome.kind,'UNSUPPORTED'); assert.ok(card.summary.includes('组合 PS2'));
});
check('mixed-AR software gap preserves source points and its actual NOT_EVALUATED status', () => {
    const {card} = entry(fixture({mixed:true})); assert.equal(card.displayIssue,false);
    assert.equal(card.evaluation.evaluationResult.status,'NOT_EVALUATED'); assert.ok(card.summary.includes('尚未实现'));
});
check('new binding cannot downgrade missing method metadata into legacy point summation', () => {
    const value = fixture({adjustment:'ONE_LEVEL_AR_NO_SECOND_P_LP'});
    for (const row of value.rows) {
        for (const key of ['detailVersion','calculationMethod','strengthAdjustment']) delete row.details.deNovoFacts[key];
        if (row.criterionId.endsWith(':PS2')) { row.appliedStrength='MODERATE'; row.reasonCode='DENOVO_QUALIFIED_POINTS'; }
    }
    assert.equal(h.deNovoPairValid(value.rows,value.profile,value.expected),false);
    const {card} = entry(value); assert.equal(card.displayIssue,true); assert.equal(card.evaluation,undefined);
});
check('archived profile source and file bytes remain unchanged', () => {
    assert.equal(JSON.stringify(archived),before); assert.deepEqual(fs.readFileSync(filename),bytes);
});
console.log(count+' DeNovo public adapter checks passed; fixed/combined proof gaps remain explicit, new rows are synthetic.');
