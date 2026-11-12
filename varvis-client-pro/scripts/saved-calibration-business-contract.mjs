import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const raw = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url), 'utf8');
const { buildSavedCalibrationDeclaration: build, savedCalibrationDetails: details, evidenceCards, validateInputOptions, currentBusinessCriteria } =
    await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`));
const source = { analysisId: 'synthetic', runId: 'source', requestSha256: 'sha', operation: 'ASSOCIATE_SAVED_OBSERVATIONS', status: 'COMPLETED_WITH_BLOCKERS' };
const options = { analysisId: 'synthetic', requestedN: 2, selectedCount: 2, sampleId: 'SYNTHETIC', requiredDeclaration: 'ordinary-pp3-bp4-development-v3',
    candidateIds: ['a', 'b'], candidates: [{ candidateId: 'a', rank: 1 }, { candidateId: 'b', rank: 2 }],
    calibrationReference: { operation: 'CALIBRATE_SAVED_OBSERVATIONS', sourceRunId: 'source', sourceRequestSha256: 'sha', executionSourceSha256: 'exec',
        calibrationCanonicalSha256: 'contract', adoptionSha256: 'adoption',
        results: ['a', 'b'].map(candidateId => ({ candidateId, sourceResultId: `result-${candidateId}`, sourceObservationSha256: `observation-${candidateId}` })) } };
assert.equal(validateInputOptions(options, 'synthetic'), options);
const declaration = build(options, 'synthetic', source);
assert.deepEqual(declaration.hypotheses, []); assert.equal(declaration.retryOf, undefined); assert.equal(declaration.association, undefined);
assert.ok(!JSON.stringify(declaration).includes('rawScore'));
assert.throws(() => build(options, 'foreign', source)); assert.throws(() => build(options, 'synthetic', { ...source, runId: 'other' }));
// Use the same scope parser as OrdinaryAcmgEvaluationPanel, with no top-level operation.
const ids = ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:BP4'];
const profileOptions = (version) => ({
    enabledCriteria: [...ids], requiredDeclaration: `ordinary-pp3-bp4-development-v${version}`,
    executionProfile: { profileId: 'acmg-pp3-bp4-development-v1', profileVersion: `${version}.0.0`,
        contextPreparation: version === 3 ? 'FIXED_SAVED_CALIBRATION_V1' : 'INDEPENDENT_SAVED_VEP_SELECTION_V1',
        operation: version === 3 ? 'CALIBRATE_SAVED_OBSERVATIONS' : 'ASSOCIATE_SAVED_OBSERVATIONS',
        requiredDeclaration: `ordinary-pp3-bp4-development-v${version}`, enabledCriteria: [...ids],
        formalEnabled: false, classificationEnabled: false, adoptionSupported: false },
});
const liveShape = profileOptions(3);
assert.equal(liveShape.operation, undefined);
assert.deepEqual(currentBusinessCriteria(liveShape), ids); // Fails against the pre-fix production parser.
const run = { runId: 'synthetic-calibration', candidates: [
    ['HBB', '0.2229', 'UNINFORMATIVE', 'NONE', '无信息区（校准已完成）'],
    ['GJB2', '0.1558', 'BENIGN', 'SUPPORTING', '蛋白机制良性方向'],
].map(([gene, rawScore, direction, strength, label], index) => ({ candidateId: `synthetic-${gene}`, rank: index + 1, label,
    attempts: [{ prediction: { schemaVersion: 'prediction-rule-result-v3', sourceRunId: 'source', rawAncestorRunId: 'raw',
        calibration: { status: 'CALIBRATED', direction, calibratedStrength: strength, appliedStrength: 'NONE', rawScore,
            sources: { doi: 'source-doi' }, localAdoption: { scope: 'EXACT_SINGLE_ROW', status: 'VERIFIED' },
            remainingApplicationConditions: ['DISEASE_APPLICABILITY_NOT_EVALUATED'] },
        criteria: Object.fromEntries(['PP3', 'BP4'].map(id => [id, { status: 'NOT_EVALUATED', appliedStrength: 'NONE' }])) } }],
})) };
const render = (opts, saved) => saved.candidates.map(candidate => ({
    cards: evidenceCards(candidate, currentBusinessCriteria(opts)),
    details: Object.fromEntries(details(candidate.attempts[0].prediction)),
}));
const before = JSON.stringify({ liveShape, run });
const rendered = render(liveShape, run);
assert.equal(rendered.length, 2);
assert.equal(rendered.flatMap(row => row.cards).length, 4);
rendered.forEach((row, i) => {
    assert.ok(row.details['校准结果'].includes(run.candidates[i].label));
    assert.equal(row.details['校准强度'], i === 0 ? 'NONE' : 'SUPPORTING');
    assert.match(row.details['校准来源'], /source-doi/);
    row.cards.forEach(card => { assert.equal(card.entries.length, 1);
        assert.equal(card.entries[0].evaluation.appliedStrength, 'NONE');
        assert.equal(card.entries[0].evaluation.evaluationResult.status, 'NOT_EVALUATED'); });
});
for (const version of [2, 3]) {
    const valid = profileOptions(version);
    assert.deepEqual(currentBusinessCriteria(valid), ids);
    let rejected = 0;
    const reject = mutate => { const invalid = structuredClone(valid); mutate(invalid);
        assert.deepEqual(currentBusinessCriteria(invalid), []); rejected++; };
    for (const field of ['profileId', 'profileVersion', 'contextPreparation', 'operation', 'requiredDeclaration']) {
        reject(o => { delete o.executionProfile[field]; });
        reject(o => { o.executionProfile[field] = 'UNKNOWN'; });
    }
    for (const oldContext of ['POPULATION_CONTEXT_ONLY_V1', 'PREDICTION_CONTEXT_ONLY_V1'])
        reject(o => { o.executionProfile.contextPreparation = oldContext; });
    reject(o => { o.executionProfile.profileVersion = `${version === 2 ? 3 : 2}.0.0`; });
    reject(o => { delete o.requiredDeclaration; });
    reject(o => { o.requiredDeclaration = 'ordinary-pp3-bp4-development-v1'; });
    reject(o => { delete o.executionProfile; });
    reject(o => { o.operation = o.executionProfile.operation; delete o.executionProfile.operation; });
    for (const target of ['top', 'profile', 'both']) {
        for (const value of [undefined, [], [ids[0]], [...ids].reverse(), [...ids, 'ACMG_AMP_2015:BA1'], [ids[0], ids[0]]])
            reject(o => { if (target !== 'profile') o.enabledCriteria = value;
                if (target !== 'top') o.executionProfile.enabledCriteria = value; });
    }
    console.log(`PASS v${version} strict saved scope: ${rejected} rejection cases`);
}
for (const empty of [null, undefined, {}, { enabledCriteria: ids }]) assert.deepEqual(currentBusinessCriteria(empty), []);
for (const schemaVersion of ['prediction-rule-result-v1', 'prediction-rule-result-v2']) {
    const historical = structuredClone(run);
    historical.candidates.forEach(c => { const prediction = c.attempts[0].prediction;
        prediction.schemaVersion = schemaVersion; prediction.calibration = { comparisonSemantics: 'NOT_CALIBRATED' }; });
    const historicalBefore = JSON.stringify(historical);
    for (const opts of [liveShape, profileOptions(2)]) {
        render(opts, historical).forEach(row => {
            assert.match(row.details['校准候选区间'], /未启用/);
            assert.equal(row.details['校准强度'], undefined);
        });
        assert.deepEqual(render(liveShape, run), rendered);
    }
    assert.equal(JSON.stringify(historical), historicalBefore);
}
assert.deepEqual(JSON.parse(JSON.stringify(run, null, 2)), run);
assert.equal(JSON.stringify({ liveShape, run }), before);
console.log('PASS options -> scope -> two candidates/four cards, calibration/application separation, history switching, download and immutability');
assert.equal(Object.fromEntries(details({ schemaVersion: 'prediction-rule-result-v3', calibration: { status: 'CALIBRATED' } }))['校准强度'], '未知');
assert.match(Object.fromEntries(details({ schemaVersion: 'prediction-rule-result-v2' }))['校准候选区间'], /未启用/);
console.log('PASS v3 declaration whitelist, source identity, calibration/application separation, missing values, historical fallback and immutable cards');
