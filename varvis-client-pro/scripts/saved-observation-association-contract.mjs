import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const raw = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url), 'utf8');
const { buildSavedObservationAssociationDeclaration: build, savedObservationParent, scoreOriginLabel, evidenceCards } =
    await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`));
const options = { analysisId: 'synthetic-analysis', requestedN: 2, selectedCount: 2, sampleId: 'SYNTHETIC',
    candidateIds: ['a', 'b'], executionProfile: { operation: 'ASSOCIATE_SAVED_OBSERVATIONS' },
    candidates: ['a', 'b'].map((candidateId, i) => ({ candidateId, rank: i + 1,
        associationOptions: [{ annotationId: `annotation-${i}`, annotationSourceSha256: `source-${i}`, versionedTranscriptId: `ENST00000${i}.1` }] })) };
const parent = { analysisId: options.analysisId, runId: 'parent', declarationVersion: 'ordinary-pp3-bp4-development-v1',
    status: 'COMPLETED_WITH_BLOCKERS', dataClass: 'SYNTHETIC', requestSha256: 'request-hash', executionSourceSha256: 'execution-hash',
    candidates: ['a', 'b'].map((candidateId, i) => ({ candidateId, attempts: [{ prediction: {
        plan: { candidateId, dataClass: 'SYNTHETIC', hypothesisId: 'null' },
        observation: { status: 'HIT', observationSha256: `observation-${i}`, scoreOrigin: 'REAL_LOCAL_PRECOMPUTED_RESOURCE', transportAttempts: 1,
            rows: [{ rawScore: '0.222900', transcriptId: `ENST00000${i}.1` }] },
        criteria: { PP3: { status: 'NOT_EVALUATED', appliedStrength: 'NONE' }, BP4: { status: 'NOT_EVALUATED', appliedStrength: 'NONE' } },
        calibration: { bands: [{ sourceStrength: 'STRONG', localAppliedStrength: 'STRONG' }] },
    } }] })) };
const before = JSON.stringify({ options, parent });
assert.throws(() => build(options, options.analysisId, parent, {}));
const request = build(options, options.analysisId, parent, { a: 'annotation-0', b: 'annotation-1' });
assert.deepEqual(request.hypotheses, []);
assert.equal(request.declarationVersion, 'ordinary-pp3-bp4-development-v2');
assert.equal(request.retryOf, undefined);
assert.equal(request.recommendations, undefined);
assert.equal(request.association.sourceRunId, parent.runId);
assert.equal(request.association.selections[0].selectionOrigin, 'USER_EXPLICIT');
assert.equal(request.association.selections[0].annotationSourceSha256, 'source-0');
assert.ok(!JSON.stringify(request).includes('rawScore'));
assert.throws(() => build(options, options.analysisId, parent, { a: 'annotation-1' }));
assert.throws(() => build(options, options.analysisId, { ...parent, analysisId: 'foreign' }, { a: 'annotation-0' }));
assert.ok(!savedObservationParent({ ...parent, operation: 'ASSOCIATE_SAVED_OBSERVATIONS' }, options.analysisId));
assert.match(scoreOriginLabel('REAL_LOCAL_PRECOMPUTED_RESOURCE'), /真实本地/);
assert.match(scoreOriginLabel(undefined), /历史/);
assert.match(scoreOriginLabel('SYNTHETIC_FIXTURE'), /合成/);
const criteria = ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'];
let cards = 0;
for (const c of parent.candidates) for (const card of evidenceCards(c, criteria)) for (const entry of card.entries) {
    cards++;
    assert.equal(entry.evaluation.appliedStrength, 'NONE');
    assert.ok(!entry.contextLabel.includes('null'));
    const detail = Object.fromEntries(entry.details);
    assert.match(detail['分数来源'], /真实本地/);
    assert.match(detail['样本 \/ 基因型性质'], /合成/);
    assert.match(detail['所选关联转录本'], /本次请求未选择/);
    assert.match(detail['校准候选区间'], /未启用/);
}
assert.equal(cards, 4);
const child = structuredClone(parent.candidates[0]);
child.attempts[0].prediction.associationInput = { transcriptId: 'ENST000000.1', annotationSource: 'VEP', annotationSourceVersion: '116.0', annotationSourceSha256: 'source-0' };
assert.equal(Object.fromEntries(evidenceCards(child, criteria)[0].entries[0].details)['所选关联转录本'], 'ENST000000.1');
assert.equal(before, JSON.stringify({ options, parent }), 'draft/card rendering does not rewrite saved observations');
console.log('PASS saved-observation declaration, explicit selection, independent score origin, historical fallback, four NONE cards and immutable display');
