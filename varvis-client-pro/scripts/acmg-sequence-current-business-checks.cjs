// Replay pinned Controller/store public outputs. Private results are comparison
// evidence only and are never supplied to the frontend adapter.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');
const contractPath = process.argv[2];
if (!contractPath) throw Error('Supply the pinned A5 round4 public-contract.json; this check never runs an analysis.');
const directory = path.dirname(path.resolve(contractPath));
const project = path.resolve(__dirname, '..');
const dependencyRoot = process.argv[3] || path.resolve(project, '../../../../../varvis-client-pro');
const dependencies = createRequire(path.join(path.resolve(dependencyRoot), 'package.json'));
const compiled = dependencies('esbuild').buildSync({ stdin: { contents: `
export {evidenceCards,localBatchScopeIssue,localBatchAttemptIssue} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
export {backgroundEvidenceCards} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js';
`, resolveDir: project }, bundle: true, platform: 'node', format: 'cjs', write: false,
    nodePaths: [path.join(path.resolve(dependencyRoot), 'node_modules')], logLevel: 'warning' }).outputFiles[0].text;
const moduleOutput = { exports: {} };
new Function('require', 'module', 'exports', compiled)(dependencies, moduleOutput, moduleOutput.exports);
const api = moduleOutput.exports;
const hash = (raw) => crypto.createHash('sha256').update(raw).digest('hex');
const clone = (value) => JSON.parse(JSON.stringify(value));
const pointer = (value, at) => at.split('/').slice(1).reduce((current, key) => current?.[key.replace(/~1/g, '/').replace(/~0/g, '~')], value);
const contractRaw = fs.readFileSync(contractPath), contract = JSON.parse(contractRaw);
assert.equal(contract.schemaVersion, 'a5-pm4-bp3-actual-public-business-contract-v1');
assert.equal(contract.sourceCommit, '805756fb6c03a7f345020d200394ed13f270f411');
assert.equal(contract.sourceTier, 'SYNTHETIC_ENGINEERING_DATA_ACTUAL_PRODUCTION_CONFIGURATION_SERVICE_CONTROLLER_STORE');
assert.equal(contract.scientificQualificationGranted, false);
assert.equal(contract.newVepRuns, 0); assert.equal(contract.newModelCalls, 0); assert.equal(contract.newNetworkCalls, 0);
const pins = new Map();
function readPinned(file) {
    const filename = path.resolve(directory, file.path);
    assert.ok(filename.startsWith(directory + path.sep), 'contract artifact must remain inside its directory');
    assert.ok(file.bytes < 2 * 1024 * 1024, 'this check reads bounded small outputs only');
    const raw = fs.readFileSync(filename);
    assert.equal(raw.length, file.bytes); assert.equal(hash(raw), file.rawSha256);
    pins.set(filename, file.rawSha256);
    return JSON.parse(raw);
}
function freeze(value) {
    if (value && typeof value === 'object' && !Object.isFrozen(value)) {
        Object.values(value).forEach(freeze); Object.freeze(value);
    }
    return value;
}
let publicRows = 0, notEvaluated = 0, proofBlocked = 0, privateCurrent = 0, legacyRows = 0;
const runs = [];
for (const sample of contract.samples) {
    const run = readPinned(sample.files.find((file) => file.path.endsWith('actual-public.json')));
    const savedPrivate = readPinned(sample.files.find((file) => file.path.endsWith('expected-private.json')));
    for (const file of sample.files) readPinned(file);
    runs.push(run);
    const before = JSON.stringify(run), privateBefore = JSON.stringify(savedPrivate);
    freeze(run);
    assert.equal(api.localBatchScopeIssue(run), null, sample.scenario);
    for (const binding of sample.bindings) {
        const criterion = pointer(run, binding.publicPointer);
        const candidate = pointer(run, binding.publicPointer.split('/attempts/')[0]);
        const attempt = pointer(run, binding.publicPointer.split('/localBatch/')[0]);
        const moduleResult = pointer(savedPrivate.localBatch, binding.modulePointerRelativeToPrivateLocalBatch);
        assert.equal(criterion.criterionId, binding.criterionId);
        assert.equal(criterion.status, binding.status); assert.equal(criterion.appliedStrength, binding.appliedStrength);
        assert.equal(criterion.reasonCode, binding.reasonCode);
        if (binding.currentSequenceMaterialStatus !== undefined) {
            assert.equal(moduleResult.sequenceMaterial?.status ?? null, binding.currentSequenceMaterialStatus ?? null);
            assert.equal(moduleResult.annotationCorrespondence ?? null, binding.currentAnnotationCorrespondence ?? null);
            assert.equal(moduleResult.sourceProjection?.annotationCorrespondence ?? null, binding.immutableSourceProjection ?? null);
            assert.equal(criterion.details.applicabilityRouting?.reasonCode ?? null, binding.immutablePrecheckReason ?? null);
            if (binding.currentSequenceMaterialStatus === 'READY' && binding.currentAnnotationCorrespondence === 'COMPLETE_SEQUENCE_COMPARISON_PASSED') privateCurrent++;
        }
        assert.equal(criterion.details.sequenceMaterial, undefined);
        assert.equal(criterion.details.annotationCorrespondence, undefined, 'private READY must not be invented in a public projection');
        const cards = api.evidenceCards(candidate, run.enabledCriteria, undefined, undefined, run);
        const entry = cards.find((card) => card.id === criterion.criterionId)?.entries[candidate.attempts.indexOf(attempt)];
        assert.ok(entry, `${sample.scenario}: source row remains visible`);
        assert.equal(entry.savedCriterion, criterion);
        const fact = criterion.details.proteinLengthFacts || criterion.details.bp3Facts;
        const unsupportedAdoption = /^PROJECT_LOCAL_VERIFIED_/.test(fact?.scope || '') && ['MET', 'NOT_MET'].includes(criterion.status);
        if (unsupportedAdoption) {
            assert.equal(entry.displayIssue, true, 'new scientific adoption cannot be accepted merely by aliasing its scope');
            assert.equal(entry.evaluation, undefined);
            assert.match(entry.summary, /公开摘要.*核验回执/);
            proofBlocked++;
        } else {
            assert.equal(entry.displayIssue, false, `${sample.scenario}: ${criterion.reasonCode}`);
            assert.equal(entry.evaluation.evaluationResult.status, criterion.status);
            assert.equal(entry.evaluation.evaluationResult.reasonCode, criterion.reasonCode);
            assert.equal(entry.evaluation.appliedStrength, criterion.appliedStrength);
            notEvaluated++;
            if (/^PROJECT_LOCAL_VERIFIED_/.test(fact?.scope || '')) {
                const text = JSON.stringify(entry.details);
                assert.match(text, /当前完整序列材料或比较回执/);
                assert.match(text, /初始路由/);
                assert.ok(!text.includes('复杂变异不在此分支范围'));
            }
        }
        assert.equal(api.backgroundEvidenceCards(candidate, run.enabledCriteria, run, candidate.attempts.indexOf(attempt))
            .find((card) => card.id === criterion.criterionId).entries[0].displayIssue, unsupportedAdoption);
        publicRows++;
    }
    assert.equal(JSON.stringify(run), before); assert.equal(JSON.stringify(savedPrivate), privateBefore);
}
const base = runs.find((run) => JSON.stringify(run.enabledCriteria) === '["ACMG_AMP_2015:PM4"]');
assert.ok(base);
const scopeTampering = [
    (run) => { run.executionProfile.criterionSelection.configuredManifestSha256 = 'bad'; },
    (run) => { run.executionProfile.populationExecutionProfile.ba1RuntimeBinding = {}; },
    (run) => { run.executionProfile.ba1RuntimeBinding = {}; },
    (run) => { run.executionProfile.bs1RuntimeBinding = null; },
    (run) => { run.executionProfile.moduleBindings.pvs1.runtimeBinding.configurationSha256 = 'f'.repeat(64); },
    (run) => { run.executionProfile.requiredDeclaration = 'other'; },
    (run) => { run.executionProfile.enabledCriteria = ['ACMG_AMP_2015:BA1']; run.enabledCriteria = ['ACMG_AMP_2015:BA1']; },
];
for (const mutate of scopeTampering) { const run = clone(base); mutate(run); assert.ok(api.localBatchScopeIssue(run)); }
const sourceCandidate = base.candidates[0];
for (const mutate of [
    (candidate, attempt) => { attempt.localBatch.candidateId = 'other'; },
    (candidate, attempt) => { attempt.localBatch.contextId = 'other'; },
    (candidate, attempt) => { attempt.context.contextId = 'other'; },
    (candidate, attempt) => { attempt.localBatch.criteria[0].appliedStrength = 'MODERATE'; },
    (candidate, attempt) => { attempt.localBatch.criteria[0].details.proteinLengthFacts.clinicalValidationCompleted = true; },
    (candidate, attempt) => { attempt.localBatch.criteria[0].details.proteinLengthFacts.factsVersion = '99.0.0'; },
]) {
    const candidate = clone(sourceCandidate), attempt = candidate.attempts[0]; mutate(candidate, attempt);
    assert.ok(api.localBatchAttemptIssue(candidate, attempt, base.enabledCriteria, base.executionProfile, base.enabledCriteria[0]));
}
const bp3Run = runs.find((run) => JSON.stringify(run.enabledCriteria) === '["ACMG_AMP_2015:BP3"]');
for (const mutate of [
    (fact) => { fact.functionAbsenceInferredFromNoHit = true; },
    (fact) => { fact.functionalLeadCount = -1; },
    (fact) => { fact.policyVersion = '99.0.0'; },
]) {
    const candidate = clone(bp3Run.candidates[0]), attempt = candidate.attempts[0];
    mutate(attempt.localBatch.criteria[0].details.bp3Facts);
    assert.ok(api.localBatchAttemptIssue(candidate, attempt, bp3Run.enabledCriteria, bp3Run.executionProfile, bp3Run.enabledCriteria[0]));
}
// Old scope behavior is still evaluated by the original validators. These are
// explicitly synthetic controls derived in memory, not altered source files.
for (const run of runs) for (const candidateSource of run.candidates) for (const attemptSource of candidateSource.attempts) {
    for (const row of attemptSource.localBatch.criteria) {
        const fact = row.details.proteinLengthFacts || row.details.bp3Facts;
        if (!fact || !/^PROJECT_LOCAL_VERIFIED_/.test(fact.scope)) continue;
        if (row.status !== 'NOT_EVALUATED' && fact.scope !== 'PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE') continue;
        const candidate = clone(candidateSource), attempt = candidate.attempts[candidateSource.attempts.indexOf(attemptSource)];
        const changed = attempt.localBatch.criteria.find((criterion) => criterion.criterionId === row.criterionId);
        (changed.details.proteinLengthFacts || changed.details.bp3Facts).scope = fact.scope === 'PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE'
            ? 'PROJECT_LOCAL_STOP_LOSS_SNV' : 'PROJECT_LOCAL_SIMPLE_INFRAME';
        assert.equal(api.localBatchAttemptIssue(candidate, attempt, run.enabledCriteria, run.executionProfile, changed.criterionId), null);
        legacyRows++;
    }
}
for (const [filename, pin] of pins) assert.equal(hash(fs.readFileSync(filename)), pin, 'source file must remain byte-identical');
assert.equal(hash(fs.readFileSync(contractPath)), hash(contractRaw));
console.log(JSON.stringify({ status: 'PASS', sourceCommit: contract.sourceCommit, samples: runs.length,
    actualPublicRows: publicRows, displayedOriginalNotEvaluated: notEvaluated, newAdoptionProofBlocked: proofBlocked,
    privateReadyComparisons: privateCurrent, syntheticLegacyScopeControls: legacyRows, pinnedFilesUnchanged: pins.size,
    layer: 'PINNED_ACTUAL_PUBLIC_JSON_REPLAY_PLUS_SYNTHETIC_TAMPERING_NO_NEW_JAVA_HTTP_OR_MODEL',
    publicCurrentSequenceReceipt: 'ABSENT_NOT_INVENTED', scientificQualificationGranted: false }));
