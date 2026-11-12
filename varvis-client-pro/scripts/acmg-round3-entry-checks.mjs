// Frozen result read/projection checks. No API requests or saved-result writes.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import frontendModuleUrl from './frontend-module-url.cjs';
const [fixtureDirectory, archivedRun] = process.argv.slice(2);
if (!fixtureDirectory || !archivedRun) throw Error('Supply normal Python fixture folder and archived complete v1 run');
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const url = frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`), h = await import(url);
const workbench = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js', import.meta.url), 'utf8')
    .replace("from './acmgInput';", 'from ' + JSON.stringify(url) + ';');
const w = await import(`data:text/javascript;base64,${Buffer.from(workbench).toString('base64')}`);
const hash = raw => createHash('sha256').update(raw).digest('hex');
let checks = 0;
const test = (name, action) => { action(); checks++; console.log('PASS ' + name); };
const wrap = value => {
    const e = value.evaluation;
    return { criterionId: 'ACMG_AMP_2015:PP1', status: e.status, appliedStrength: e.strength || 'NONE',
        reason: 'SYNTHETIC public-summary projection of actual normal Python result; not Java business acceptance',
        reasonCode: e.status === 'MET' ? 'PP1_QUALIFIED_SEGREGATION_THRESHOLD_MET' : e.status === 'NOT_MET' ?
            'PP1_QUALIFIED_SEGREGATION_BELOW_THRESHOLD' : 'PP1_LITERATURE_REQUIREMENTS_UNRESOLVED',
        details: { pp1Facts: { schemaVersion: 'pp1-selected-facts-v1', clinicalApproval: false,
            evaluation: e, sourceCoverage: value.sourceCoverage, qualifications: value.qualifications } } };
};
for (const name of ['positive', 'negative', 'zero']) {
    const filename = path.join(fixtureDirectory, name + '.json'), bytes = fs.readFileSync(filename), value = JSON.parse(bytes);
    const before = JSON.stringify(value), row = wrap(value), t = value.evaluation.target;
    const context = { gene: { symbol: t.geneSymbol }, disease: { mondoId: t.diseaseId }, moi: 'AUTOSOMAL_DOMINANT', variant: { evidenceQueryAllele: structuredClone(t.allele) } };
    test('public dispatcher accepts actual normal Python ' + name + ' independently of legacy events', () => {
        assert.equal(h.pp1LiteratureCriterionValid(row, undefined, context), true);
        assert.equal(row.details.pp1Facts.evaluation.events.length, 0);
        assert.ok(!Object.hasOwn(row.details.pp1Facts.evaluation, 'rawSegregationPoints'));
    });
    for (const [label, mutate] of [['gene', c => { c.gene.symbol = 'OTHER'; }], ['disease', c => { c.disease.mondoId = 'OTHER'; }],
        ['MOI', c => { c.moi = 'AUTOSOMAL_RECESSIVE'; }], ['allele', c => { c.variant.evidenceQueryAllele.alternate = 'T'; }]])
        test(name + ' rejects outer selected ' + label + ' mismatch', () => {
            const bad = structuredClone(context); mutate(bad); assert.equal(h.pp1LiteratureCriterionValid(row, undefined, bad), false);
        });
    test(name + ' unknown schema/method/model cannot become legacy points', () => {
        for (const mutate of [e => { e.schemaVersion = 'pp1-segregation-evaluation-v99'; }, e => { e.method = 'UNKNOWN'; },
            e => { e.modelVersion = '99.0.0'; }]) {
            const bad = structuredClone(row); mutate(bad.details.pp1Facts.evaluation); assert.equal(h.pp1LiteratureCriterionValid(bad), false);
        }
    });
    test(name + ' original JSON and file remain unchanged', () => {
        assert.equal(JSON.stringify(value), before); assert.equal(hash(fs.readFileSync(filename)), hash(bytes));
    });
}
const bytes = fs.readFileSync(archivedRun), saved = JSON.parse(bytes), before = JSON.stringify(saved);
const candidate = saved.candidates.find(c => c.attempts?.some(a => a.localBatch?.criteria?.some(r => r.criterionId === 'ACMG_AMP_2015:PP1')));
const attempt = candidate.attempts.find(a => a.localBatch?.criteria?.some(r => r.criterionId === 'ACMG_AMP_2015:PP1'));
const original = attempt.localBatch.criteria.find(r => r.criterionId === 'ACMG_AMP_2015:PP1');
test('actual archived v1 public run retains Supporting/1 and complete source projection', () => {
    assert.equal(original.details.pp1Facts.evaluation.schemaVersion, 'pp1-segregation-evaluation-v1');
    assert.equal(h.pp1LiteratureCriterionValid(original), true);
    assert.equal(original.appliedStrength, 'SUPPORTING'); assert.equal(original.details.pp1Facts.evaluation.awardedPP1Points, 1);
    const card = h.evidenceCards(candidate, saved.enabledCriteria, 'candidate', '0', saved).find(c => c.code === 'PP1');
    assert.ok(card.entries.some(e => !e.displayIssue && e.evaluation?.appliedStrength === 'SUPPORTING'));
});
test('unsupported future method is a presentation limit and never a missing-case fact', () => {
    const next = structuredClone(saved), c = next.candidates.find(c => c.candidateId === candidate.candidateId);
    const r = c.attempts.find(a => a.localBatch?.criteria?.some(r => r.criterionId === 'ACMG_AMP_2015:PP1'))
        .localBatch.criteria.find(r => r.criterionId === 'ACMG_AMP_2015:PP1');
    r.details.pp1Facts.evaluation.schemaVersion = 'pp1-segregation-evaluation-v99';
    const entry = h.evidenceCards(c, next.enabledCriteria, 'candidate', '0', next).find(card => card.code === 'PP1').entries[0];
    assert.equal(entry.displayIssue, true); assert.equal(w.resultOutcome(entry).kind, 'UNSUPPORTED');
    assert.ok(entry.summary.includes('当前页面')); assert.equal(entry.evaluation, undefined);
});
test('archived input bytes and complete result remain unchanged', () => {
    assert.equal(JSON.stringify(saved), before); assert.equal(hash(fs.readFileSync(archivedRun)), hash(bytes));
});
console.log(checks + ' round3 entry/projection checks passed; captured Python and archived Java layers are distinct.');
