import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const raw = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url), 'utf8');
const { currentBusinessCriteria, revelSourceEligible, buildRevelApplicationDeclaration, revelSavedDetails,
    buildSimplifiedCaseDeclaration, prepareSimplifiedInputs, validateInputOptions } =
    await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`));
const profile = { profileId: 'acmg-revel-pp3-application-v1', profileVersion: '1.1.0', requiredDeclaration: 'ordinary-revel-pp3-v1',
    contextPreparation: 'REVEL_NATIVE_CONTEXT_V1', enabledCriteria: ['ACMG_AMP_2015:PP3'], revelPreparationBinding: { bindingSha256: 'science-pin' } };
const options = { executionProfile: profile, enabledCriteria: profile.enabledCriteria, requestedN: 2, sampleId: 'SYNTHETIC', revelApplicationBinding: { bindingSha256: 'current-approval-pin' } };
const run = { analysisId: 'TEST', runId: 'saved-source', requestSha256: 'saved-digest', executionProfile: profile,
    status: 'COMPLETED_WITH_BLOCKERS', requestedN: 2, dataClass: 'SYNTHETIC', candidates: [{ candidateId: 'c1', attempts: [{
        preflight: { original: { hypothesisId: 'h1' } }, context: { gene: { hgncId: 'HGNC:101' }, disease: { mondoId: 'MONDO:0000001' }, moi: 'AUTOSOMAL_RECESSIVE' },
        transcriptInput: { reviewedPrediction: { clauses: { PP3: { result: { validatedScore: '0', evaluation: { evaluationResult: { status: 'NOT_MET' } } } } } } },
    }] }] };
assert.deepEqual(currentBusinessCriteria(options), ['ACMG_AMP_2015:PP3']);
assert.deepEqual(currentBusinessCriteria({ ...options, executionProfile: { ...profile, profileVersion: 'unknown' } }), []);
assert.equal(revelSourceEligible(options, run, 'TEST'), true);
assert.throws(() => buildRevelApplicationDeclaration(options, 'TEST', run, false));
assert.throws(() => buildRevelApplicationDeclaration(options, 'OTHER', run, 'current-approval-pin'));
const body = buildRevelApplicationDeclaration(options, 'TEST', run, 'current-approval-pin');
assert.equal(body.revelApplication.sourceRunId, run.runId);
assert.equal(body.revelApplication.bindingSha256, 'current-approval-pin');
const refreshed = { ...options, revelApplicationBinding: { bindingSha256: 'new-approval-pin' } };
assert.throws(() => buildRevelApplicationDeclaration(refreshed, 'TEST', run, 'current-approval-pin'));
assert.throws(() => buildRevelApplicationDeclaration(options, 'TEST', run, true));
assert.equal(buildRevelApplicationDeclaration(refreshed, 'TEST', run, 'new-approval-pin').revelApplication.bindingSha256, 'new-approval-pin');
assert.equal(revelSourceEligible({ ...options, revelApplicationBinding: undefined }, run, 'TEST'), false);
const legacyRevel = { ...run, executionProfile: { ...profile, profileVersion: '1.0.0', revelApplicationBinding: { bindingSha256: 'OLD' } } };
assert.deepEqual(revelSavedDetails(legacyRevel), revelSavedDetails(run));
assert.deepEqual(currentBusinessCriteria({ ...options, executionProfile: legacyRevel.executionProfile }), []);
assert.equal(buildRevelApplicationDeclaration(options, 'TEST', legacyRevel, 'current-approval-pin').revelApplication.bindingSha256, 'current-approval-pin');
assert.equal(body.patient, null); assert.deepEqual(body.hypotheses, []);
assert.equal(revelSourceEligible(options, { ...run, operation: 'ADOPT_SAVED_REVEL_PP3' }, 'TEST'), false);
assert.deepEqual(revelSavedDetails({ ...run, executionProfile: { profileId: 'acmg-pp3-bp4-development-v1' } }), []);
assert.equal(revelSavedDetails(run)[0].score, '0'); assert.equal(revelSavedDetails(run)[0].strength, 'NONE');
const adopted = { ...run, applications: [{ candidateId: 'c1', hypothesisId: 'OTHER', adoptionStatus: 'ADOPTED', appliedStrength: 'SUPPORTING' }] };
assert.equal(revelSavedDetails(adopted)[0].adoption, 'NOT_APPLIED');
assert.deepEqual(JSON.parse(JSON.stringify(run)), run);
if (process.argv[2]) {
    const source = JSON.parse(fs.readFileSync(`${process.argv[2]}/synthetic-source.json`, 'utf8'));
    const application = JSON.parse(fs.readFileSync(`${process.argv[2]}/synthetic-application.json`, 'utf8'));
    const actualOptions = JSON.parse(fs.readFileSync(`${process.argv[2]}/synthetic-input-options.json`, 'utf8'));
    validateInputOptions(actualOptions, source.analysisId);
    const sourceBody = buildSimplifiedCaseDeclaration(actualOptions, source.analysisId, prepareSimplifiedInputs(actualOptions), undefined, 'SYNTHETIC');
    const originalOptions = JSON.parse(fs.readFileSync(`${process.argv[2]}/C0-input-options.json`, 'utf8'));
    const originalBody = buildSimplifiedCaseDeclaration(originalOptions, source.analysisId, prepareSimplifiedInputs(originalOptions), undefined, 'SYNTHETIC');
    const scientificDeclaration = (body) => { const copy = structuredClone(body); copy.recommendations.forEach((r) => delete r.generatedAt); return copy; };
    assert.deepEqual(scientificDeclaration(originalBody), scientificDeclaration(sourceBody));
    assert.deepEqual(originalOptions.executionProfile, actualOptions.executionProfile);
    assert.notEqual(originalOptions.revelApplicationBinding.bindingSha256, actualOptions.revelApplicationBinding.bindingSha256);
    assert.throws(() => buildRevelApplicationDeclaration(actualOptions, source.analysisId, source, originalOptions.revelApplicationBinding.bindingSha256));
    assert.equal(sourceBody.declarationVersion, 'ordinary-revel-pp3-v1');
    assert.equal(sourceBody.hypotheses.length, 2); assert.equal(sourceBody.recommendations.length, 2);
    assert.ok(sourceBody.hypotheses.every((h) => ['AR', 'AUTOSOMAL_RECESSIVE'].includes(h.hypothesis.moi)));
    const adoptedBody = buildRevelApplicationDeclaration(actualOptions, source.analysisId, source, actualOptions.revelApplicationBinding.bindingSha256);
    assert.equal(adoptedBody.revelApplication.sourceRunId, source.runId);
    assert.deepEqual(revelSavedDetails(application).map((r) => r.adoption), ['ADOPTED', 'NOT_ADOPTED']);
    assert.deepEqual(revelSavedDetails(application).map((r) => r.strength), ['SUPPORTING', 'NONE']);
    assert.deepEqual(revelSavedDetails(source).map((r) => r.adoption), ['NOT_APPLIED', 'NOT_APPLIED']);
    assert.deepEqual(JSON.parse(JSON.stringify(application)), application);
}
console.log('REVEL application request, saved projection, history separation and download contracts passed; no browser or scientific calls.');
