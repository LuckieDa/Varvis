// Read-only CURRENT PVS1 products plus explicitly synthetic display controls.
// This is neither a new backend execution nor new source/scientific qualification.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createRequire, Module } = require('node:module');
const { spawnSync } = require('node:child_process');
const [contractPath, outputPath, sharedPath] = process.argv.slice(2);
if (!contractPath || !outputPath) throw Error('Supply coding-current public-contract.json and assigned private output.');
const root = path.dirname(path.resolve(contractPath)), project = path.resolve(__dirname, '..'), repository = path.dirname(project);
const output = path.resolve(outputPath), shared = path.resolve(sharedPath || project);
if (output !== path.resolve(root, '../../../a9/round4/pvs1-coding-current-regression')) throw Error('Use assigned PVS1 coding private output.');
const dependencies = createRequire(path.join(shared, 'package.json'));
const built = dependencies('esbuild').buildSync({ stdin: { contents: `
import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import Summary from './src/pages/varvis/ChromosomeVisualization/components/EvidenceDecisionSummary.jsx';
export const render=entry=>renderToStaticMarkup(<Summary entry={entry}/>);
export {decisionDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgDecisionDetails.js';
export {ps2IdentityHash} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
export {backgroundEvidenceCards,resultPresentation} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js';
export {isPvs1CodingEscapeAssessment,inspectPvs1CodingEscapeAssessment,pvs1CodingEscapeDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgPvs1Splice.js';
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', packages: 'external', write: false,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'silent' });
const loaded = new Module(path.join(project, 'scripts', 'pvs1-coding-current-bundle.cjs'));
loaded.filename = loaded.id; loaded.paths = [path.join(shared, 'node_modules')]; loaded._compile(built.outputFiles[0].text, loaded.filename);
const api = loaded.exports, hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex'), clone = value => structuredClone(value);
let httpCalls = 0;
dependencies('axios').defaults.adapter = async () => { httpCalls++; throw Error('HTTP forbidden in display regression'); };
globalThis.fetch = async () => { httpCalls++; throw Error('Fetch forbidden in display regression'); };
const pins = new Map(), checks = [], failures = [], reports = [], controls = [], fixtures = new Map();
function read(filename) {
    const bytes = fs.readFileSync(filename); pins.set(filename, hash(bytes));
    return { bytes, value: JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, '')) };
}
function check(name, run) {
    try { run(); checks.push({ name, status: 'PASS' }); }
    catch (error) { const failure = { name, status: 'FAIL', error: error.message }; checks.push(failure); failures.push(failure); }
}
function freeze(value) {
    if (value && typeof value === 'object' && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); }
    return value;
}
const { value: contract } = read(path.resolve(contractPath)), { value: manifest } = read(path.join(root, 'artifact-manifest.json'));
const { bytes: verificationBytes, value: verification } = read(path.join(root, 'verification.json'));
const PVS1 = 'ACMG_AMP_2015:PVS1';
const facts = row => row.details.pvs1Facts, assessment = row => facts(row).codingEscapeAssessment, proof = row => facts(row).escapeAdoptionProof;
const inspect = ({ row, context, profile }) => api.inspectPvs1CodingEscapeAssessment(row, context, { identityHash: api.ps2IdentityHash, profile });
const rowAt = (run, candidate = 0) => run.candidates[candidate].attempts[0].localBatch.criteria.find(row => row.criterionId === PVS1);
const entryAt = (run, candidate = 0) => api.backgroundEvidenceCards(run.candidates[candidate], run.enabledCriteria, run, 0)
    .find(card => card.id === PVS1).entries[0];
const declaredCondition = (row, coordinate, checkedPositions) => {
    proof(row).positionConditions.escape = { status: 'CHECKED', conditions: [{ conditionId: 'TEST_ONLY_DISPLAY_POSITION', coordinate,
        minimumInclusive: 1, maximumExclusive: 1000, sourceIds: [proof(row).sources[0].sourceId], status: 'PASS', checkedPositions }] };
};
const expectedPositions = (kind, candidate) => {
    if (!['SIMPLE', 'COMPLEX'].includes(kind)) return { cdsPosition: 499, proteinPosition: 167,
        ptcSpan: { start: 499, end: 501, coordinateSystem: 'REFERENCE_CDS_1_BASED_INCLUSIVE' },
        referenceEvent: { coordinateSystem: 'REFERENCE_CDS_0_BASED_HALF_OPEN', start0: 498, end0: 499, deletedBaseCount: 1, insertedBaseCount: 1, kind: 'SUBSTITUTION' },
        hgvscEvent: { coordinateSystem: 'REFERENCE_CDS_0_BASED_HALF_OPEN', start0: 498, end0: 499, deletedBaseCount: 1, insertedBaseCount: 1, kind: 'SUBSTITUTION', sequenceEquivalent: true } };
    const complex = kind === 'COMPLEX', insertion = !complex && candidate === 0;
    const normal = complex ? [30, 32, 2, 1, 'DELINS'] : insertion ? [30, 30, 0, 1, 'INSERTION'] : [31, 32, 1, 0, 'DELETION'];
    const hgvs = complex || insertion ? normal : [30, 31, 1, 0, 'DELETION'];
    const event = a => ({ coordinateSystem: 'REFERENCE_CDS_0_BASED_HALF_OPEN', start0: a[0], end0: a[1], deletedBaseCount: a[2], insertedBaseCount: a[3], kind: a[4] });
    return { cdsPosition: !complex && !insertion ? 32 : 31, proteinPosition: 11, firstChangedProteinPosition: 11,
        ptcSpan: { start: insertion ? 64 : 61, end: insertion ? 66 : 63, coordinateSystem: 'ALTERED_CDS_1_BASED_INCLUSIVE' },
        referenceEvent: event(normal), hgvscEvent: { ...event(hgvs), sequenceEquivalent: true },
        referencePtc: { coordinateSystem: 'REFERENCE_CDS_1_BASED', positions: insertion ? [63, 64, 65] : [62, 63, 64], status: 'REFERENCE_BASES' } };
};

check('accepted producer contract and backend metadata remain separate from this frontend replay', () => {
    assert.equal(contract.schemaVersion, 'pvs1-coding-current-contract-v1');
    assert.equal(contract.sourceHead, '3983da908929fdf2a5731263e28200d29a08bc04');
    assert.equal(manifest.sourceHead, contract.sourceHead); assert.equal(verification.sourceHead, contract.sourceHead);
    assert.equal(hash(verificationBytes), contract.verificationRawSha256);
    assert.equal(contract.actualJavaPositiveResponses.length, 9); assert.equal(contract.negativeActualResponses.length, 3);
    assert.equal(verification.standaloneJvmStoreReaders.length, 3);
    assert.deepEqual(verification.totals, { tests: 99, failures: 0, errors: 0, skipped: 0, newProjection: 20, currentBusiness: 8, priorProofRegression: 71 });
});
for (const record of verification.source) check(`committed producer metadata pin: ${record.file}`, () => {
    const result = spawnSync('git', ['show', `${contract.sourceHead}:${record.file}`], { cwd: repository, maxBuffer: 4 * 1024 * 1024 });
    assert.equal(result.status, 0, String(result.stderr)); assert.equal(hash(result.stdout), record.finalRawSha256);
});
let productCount = 0, actualRows = 0, availableRows = 0, counterRows = 0, ssrRows = 0;
let structurallyAvailableRows = 0, sourceQualifiedRows = 0, adoptedRows = 0, unavailableProofRows = 0;
for (const record of [...contract.actualJavaPositiveResponses, ...contract.negativeActualResponses]) {
    const filename = path.resolve(root, record.file);
    if (!filename.startsWith(root + path.sep)) throw Error('Product escaped source directory.');
    const { bytes, value: run } = read(filename), before = hash(JSON.stringify(run)); freeze(run); productCount++;
    check(`${record.file}: exact original JSON pin`, () => {
        const pin = manifest.files.find(file => file.file === record.file);
        assert.equal(bytes.length, record.bytes); assert.equal(hash(bytes), record.rawSha256);
        assert.equal(pin.bytes, record.bytes); assert.equal(pin.rawSha256, record.rawSha256);
        assert.equal(run.analysisId, record.analysisId); assert.equal(run.runId, record.runId);
        assert.equal(run.status, record.overallStatus); assert.deepEqual(run.enabledCriteria, record.selectedCriteria);
    });
    for (const expected of record.pvs1) {
        const candidate = run.candidates[expected.candidate], attempt = candidate.attempts[0], row = rowAt(run, expected.candidate), f = facts(row);
        const scope = { row, context: attempt.context, profile: run.executionProfile }, parsed = inspect(scope), entry = entryAt(run, expected.candidate);
        const key = `${record.file}/candidate-${expected.candidate}`, a = assessment(row), p = proof(row); actualRows++;
        if (p?.status === 'AVAILABLE') structurallyAvailableRows++;
        if (p?.status === 'UNAVAILABLE') unavailableProofRows++;
        if (f.sourceQualifiedResearch === true && a?.sourceQualifiedResearch === true && a?.sourceQualification?.status === 'VERIFIED') sourceQualifiedRows++;
        if (api.resultPresentation(entry).mainKind === 'ADOPTED_MET') adoptedRows++;
        check(`${key}: exact scientific status/strength and typed source contract`, () => {
            assert.equal(row.status, expected.status); assert.equal(row.appliedStrength, expected.strength); assert.equal(row.reasonCode, expected.reasonCode);
            assert.equal(f.methodVersion, expected.factsMethodVersion); assert.equal(f.sourceQualifiedResearch, expected.sourceQualifiedResearch);
            assert.equal(a?.status ?? null, expected.codingStatus); assert.equal(a?.sourceQualification?.status ?? null, expected.sourceQualification);
            assert.equal(p?.status ?? null, expected.structuralProofStatus); assert.deepEqual(a?.positions ?? null, expected.positions);
            if (a) {
                availableRows++; assert.equal(a.sourceQualifiedResearch, true); assert.equal(a.sourceQualification.status, 'VERIFIED');
                assert.equal(a.sourceQualification.reasonCode, 'PVS1_SOURCE_QUALIFIED_CONDITIONS_BOUND');
                assert.equal(a.proofPointer, '#/escapeAdoptionProof'); assert.equal(a.scientificApprovalCreated, false); assert.equal(a.rescoringPerformed, false);
                assert.deepEqual(p.positionConditions, { escape: { status: 'NOT_DECLARED' }, nmd: { status: 'NOT_DECLARED' } });
                assert.equal(p.bindings.evaluationSha256, row.details.originalResultSha256);
                assert.deepEqual(a.transcriptModelFacts, p.transcriptModelFacts);
                const kind = record.file.split('/')[1];
                assert.deepEqual(a.positions, expectedPositions(kind, expected.candidate));
                assert.equal(a.mechanism, kind === 'STOP' || !['SIMPLE', 'COMPLEX'].includes(kind) ? 'STOP_GAINED' : 'FRAMESHIFT');
                const stopMode = !['SIMPLE', 'COMPLEX'].includes(kind), complexMode = kind === 'COMPLEX';
                const alteredProteinLength = stopMode ? 166 : kind === 'SIMPLE' && expected.candidate === 0 ? 21 : 20;
                assert.equal(a.transcriptModelFacts.cdsLength, 600); assert.equal(a.transcriptModelFacts.proteinLength, 199);
                assert.deepEqual(p.proteinEffect, { firstAlteredResidue: stopMode ? 167 : 11, referenceProteinLength: 199,
                    alteredProteinLength, referenceResiduesAffected: stopMode ? 33 : 189, netProteinShortening: 199 - alteredProteinLength });
                assert.equal(p.selectedRule.predicate, 'CRITICAL_REGION'); assert.equal(p.selectedRule.start, 160); assert.equal(p.selectedRule.end, 190);
                assert.equal(p.selectedRule.minimumAffectedResidues, complexMode ? 10 : 1);
                assert.deepEqual(p.ruleCheck, { status: 'MATCH', affectedResiduesInRegion: stopMode ? 24 : 31 });
                assert.deepEqual(p.strengthDerivation, { sourceRuleStrength: 'STRONG', parentPolicyCap: complexMode ? 'MODERATE' : 'VERY_STRONG',
                    adoptedStrength: complexMode ? 'MODERATE' : 'STRONG' });
            } else { counterRows++; assert.equal(row.status, 'NOT_EVALUATED'); assert.equal(row.appliedStrength, 'NONE'); }
        });
        check(`${key}: strict normal selected reader actually accepts the supplied product`, () => {
            assert.equal(parsed.claimed, !!a); assert.equal(parsed.valid, true, JSON.stringify(parsed.issues));
            assert.equal(entry.displayIssue, false, entry.summary); assert.strictEqual(entry.savedCriterion, row);
            assert.equal(entry.evaluation.evaluationResult.status, row.status); assert.equal(entry.evaluation.appliedStrength, row.appliedStrength);
            assert.equal(entry.evaluation.evaluationResult.reasonCode, row.reasonCode);
            if (a) { assert.ok(parsed.view); assert.deepEqual(entry.pvs1CodingProofView, parsed.view); assert.ok(api.pvs1CodingEscapeDetails(parsed).length); }
            else { assert.equal(parsed.view, null); assert.equal(entry.pvs1CodingProofView, undefined); }
            assert.equal(api.resultPresentation(entry).mainKind, row.status === 'MET' ? 'ADOPTED_MET' : 'NO_CONCLUSION');
        });
        check(`${key}: actual Summary SSR preserves original and exposes current coding proof`, () => {
            const decision = api.decisionDetails(entry), html = api.render(entry); ssrRows++;
            assert.deepEqual(decision.original, { criterionId: row.criterionId, status: row.status, strength: row.appliedStrength, reasonCode: row.reasonCode });
            assert.ok(!html.includes('<button'), 'no fabricated analysis/continuation action');
            if (a) {
                assert.match(html, /data-testid="pvs1-coding-current-summary"/);
                assert.ok(html.includes('VERIFIED')); assert.ok(html.includes(row.appliedStrength));
                assert.ok(html.includes(a.mechanism)); assert.ok(html.includes(String(a.positions.cdsPosition)));
                assert.ok(html.indexOf('pvs1-coding-current-summary') < html.indexOf('原保存状态、强度及原因'));
            }
        });
        if (record.file.endsWith('/single-product.json') && ['COMPLEX', 'SIMPLE', 'STOP'].includes(record.file.split('/')[1]))
            fixtures.set(`${record.file.split('/')[1]}-${expected.candidate}`, { ...scope, run, candidate: expected.candidate });
        reports.push({ file: record.file, candidate: expected.candidate, status: row.status, strength: row.appliedStrength,
            method: f.methodVersion, claim: !!a, assessmentStatus: a?.status ?? null, sourceQualification: a?.sourceQualification?.status ?? null,
            positions: a?.positions ?? null, proofStatus: p?.status ?? null, originalResultSha256: row.details.originalResultSha256,
            displayIssue: entry.displayIssue, mainKind: api.resultPresentation(entry).mainKind, htmlSha256: hash(api.render(entry)) });
    }
    check(`${record.file}: parsing and SSR do not mutate the stored product`, () => assert.equal(hash(JSON.stringify(run)), before));
}

function synthetic(name, base, mutate, expected = 'REJECT', verify) {
    check(`SYNTHETIC ${name}`, () => {
        const run = clone(base.run), row = rowAt(run, base.candidate), attempt = run.candidates[base.candidate].attempts[0];
        mutate(row, attempt.context, run); const raw = JSON.stringify(row);
        const parsed = inspect({ row, context: attempt.context, profile: run.executionProfile }), entry = entryAt(run, base.candidate), presentation = api.resultPresentation(entry);
        if (expected === 'VALID') {
            assert.equal(parsed.valid, true, JSON.stringify(parsed.issues)); assert.equal(entry.displayIssue, false, entry.summary);
            assert.equal(presentation.mainKind, 'ADOPTED_MET'); assert.ok(entry.pvs1CodingProofView);
        } else {
            assert.equal(parsed.valid, false, `${name} was accepted`); assert.ok(parsed.issues.length);
            assert.equal(entry.displayIssue, true); assert.equal(presentation.mainKind, 'NO_CONCLUSION');
            assert.notEqual(presentation.outcome.kind, 'MISSING'); assert.equal(entry.evaluation, undefined);
            if (expected === 'UNAVAILABLE') {
                assert.equal(presentation.outcome.kind, 'UNAVAILABLE'); assert.equal(parsed.view?.availabilityOnly, true);
                assert.ok(entry.pvs1CodingProofView); assert.equal(entry.pvs1CodingProofView.availabilityOnly, true);
                const html = api.render(entry); assert.match(html, /data-testid="pvs1-coding-current-summary"/);
                assert.ok(html.includes('MET') && html.includes(row.appliedStrength));
            }
            if (expected === 'TECHNICAL') {
                assert.equal(parsed.view, null); assert.equal(entry.pvs1CodingProofView, undefined);
                assert.equal(presentation.outcome.kind, 'UNRESOLVED', 'malformed coding data must not borrow an old availability label');
            }
        }
        if (verify) verify({ row, parsed, entry });
        assert.strictEqual(entry.savedCriterion, row); assert.equal(JSON.stringify(row), raw);
        controls.push({ name, layer: 'SYNTHETIC_DISPLAY_ONLY', expected, valid: parsed.valid, issues: parsed.issues,
            displayIssue: entry.displayIssue, mainKind: presentation.mainKind, processKind: presentation.outcome.kind });
    });
}
const stop = fixtures.get('STOP-0'), insertion = fixtures.get('SIMPLE-0'), deletion = fixtures.get('SIMPLE-1'), complex = fixtures.get('COMPLEX-0');
if (!stop || !insertion || !deletion || !complex) throw Error('Required independent fixtures absent.');
function replaceSourceId(row, next) {
    const p = proof(row), old = p.sources[0].sourceId;
    const map = list => list.map(value => value === old ? next : value);
    p.sources[0].sourceId = next; p.review.sourceIds = map(p.review.sourceIds);
    p.selectedRule.sourceIds = map(p.selectedRule.sourceIds);
    for (const check of p.ruleChecks) check.rule.sourceIds = map(check.rule.sourceIds);
    for (const exon of p.exonChecks) exon.sourceIds = map(exon.sourceIds);
    for (const scope of Object.values(p.positionConditions)) for (const condition of scope.conditions || []) condition.sourceIds = map(condition.sourceIds);
}
function replaceRuleId(row, next) {
    const p = proof(row), old = p.selectedRule.ruleId; p.selectedRule.ruleId = next;
    for (const check of p.ruleChecks) if (check.rule.ruleId === old) check.rule.ruleId = next;
}
function replaceOpaqueIds(row, identifier, version) {
    replaceSourceId(row, identifier); replaceRuleId(row, identifier);
    const p = proof(row); p.sources[0].version = version; p.review.reviewId = identifier;
    p.policy.policyId = identifier; p.policy.policyVersion = version; p.policy.reviewId = identifier;
    declaredCondition(row, 'REFERENCE_EVENT_SPAN', [499]); p.positionConditions.escape.conditions[0].conditionId = identifier;
}
for (const [name, identifier, version] of [['slash', 'ClinGen/SVI', 'release/1'], ['space', 'source id', 'release 1'],
    ['Unicode', '中文审核条款', '研究版本一'], ['exact 256-character bound', '标'.repeat(256), 'v'.repeat(256)]]) {
    synthetic(`opaque ${name} identifiers and versions remain exact and associated`, stop,
        row => replaceOpaqueIds(row, identifier, version), 'VALID', ({ row, parsed, entry }) => {
            const p = proof(row), v = parsed.view;
            assert.equal(v.sources[0].sourceId, identifier); assert.equal(v.sources[0].version, version);
            assert.equal(v.selectedRule.ruleId, identifier); assert.equal(v.positionConditions.escape.conditions[0].conditionId, identifier);
            assert.equal(p.review.reviewId, identifier); assert.equal(p.policy.reviewId, identifier);
            assert.equal(p.policy.policyId, identifier); assert.equal(p.policy.policyVersion, version);
            assert.equal(v.sources[0].url, p.sources[0].url, 'opaque identifier does not become a URL');
            assert.ok(!api.render(entry).includes(`href="${identifier}"`));
        });
}
const opaqueFieldChanges = {
    sourceId: (row, value) => replaceSourceId(row, value), sourceVersion: (row, value) => { proof(row).sources[0].version = value; },
    ruleId: (row, value) => replaceRuleId(row, value), reviewId: (row, value) => { proof(row).review.reviewId = value; },
    policyId: (row, value) => { proof(row).policy.policyId = value; }, policyVersion: (row, value) => { proof(row).policy.policyVersion = value; },
    policyReviewId: (row, value) => { proof(row).policy.reviewId = value; },
    conditionId: (row, value) => { proof(row).positionConditions.escape.conditions[0].conditionId = value; },
};
for (const [field, mutate] of Object.entries(opaqueFieldChanges)) for (const [kind, value] of [['empty', ''], ['blank', '   '],
    ['CRLF', 'first\r\nsecond'], ['overlong', 'x'.repeat(257)]]) synthetic(`opaque ${field} ${kind} remains invalid`, stop, row => {
    replaceOpaqueIds(row, '合法来源/规则 标识', 'release 1'); mutate(row, value);
}, 'TECHNICAL');
synthetic('legal opaque identifiers cannot bypass source association', stop, row => {
    replaceOpaqueIds(row, 'ClinGen/SVI 中文来源', 'release 1'); proof(row).selectedRule.sourceIds = ['另一合法但未关联的来源 / missing'];
}, 'TECHNICAL');
for (const [name, mutate] of [
    ['wrong disease scope', row => { proof(row).scope.mondoId = 'MONDO:0000001'; }],
    ['wrong selected context', (_, context) => { context.gene.hgncId = 'HGNC:1'; }],
    ['top source qualification false', row => { facts(row).sourceQualifiedResearch = false; }],
    ['assessment source qualification false', row => { assessment(row).sourceQualifiedResearch = false; }],
    ['source qualification NOT_AVAILABLE', row => { assessment(row).sourceQualification.status = 'NOT_AVAILABLE'; }],
    ['source qualification missing', row => { delete assessment(row).sourceQualification; }],
    ['unrelated proof pointer', row => { assessment(row).proofPointer = '#/otherCriterion/escapeAdoptionProof'; }],
    ['coding assessment malformed null', row => { facts(row).codingEscapeAssessment = null; }],
    ['coding mechanism incompatible', row => { assessment(row).mechanism = 'FRAMESHIFT'; }],
    ['coding position changed', row => { assessment(row).positions.cdsPosition++; }],
    ['coding PTC span changed', row => { assessment(row).positions.ptcSpan.end++; }],
    ['normal event half-open length mismatch', row => { assessment(row).positions.referenceEvent.end0++; }],
    ['HGVS equivalence false', row => { assessment(row).positions.hgvscEvent.sequenceEquivalent = false; }],
    ['model digest disagreement', row => { assessment(row).transcriptModelFacts.modelSha256 = '1'.repeat(64); }],
    ['source reference removed', row => { proof(row).selectedRule.sourceIds = ['UNKNOWN_SOURCE']; }],
    ['parent strength cap violated', row => { proof(row).strengthDerivation.parentPolicyCap = 'SUPPORTING'; }],
    ['original evaluation digest changed', row => { row.details.originalResultSha256 = '2'.repeat(64); }],
    ['evaluation identity malformed', row => { proof(row).bindings.evaluationId = 'evaluation-sha256-invalid'; }],
    ['nonpositive scientific row with positive proof', row => { row.status = 'NOT_MET'; row.appliedStrength = 'NONE'; }],
    ['assessment promotion without positive row', row => { row.status = 'NOT_EVALUATED'; row.appliedStrength = 'NONE'; }],
]) synthetic(name, stop, mutate);
synthetic('complex cap promoted above parent', complex, row => { row.appliedStrength = 'STRONG'; proof(row).strengthDerivation.adoptedStrength = 'STRONG'; });
synthetic('complex method cannot fall back to ordinary path when edit derivation is deleted', complex, row => { delete facts(row).codingEditDerivation; }, 'TECHNICAL');
synthetic('complex method cannot fall back to ordinary path with null edit derivation', complex, row => { facts(row).codingEditDerivation = null; }, 'TECHNICAL');
synthetic('STOP may explicitly declare its unchanged mechanism', stop, row => { facts(row).variantMechanism = 'STOP_GAINED'; }, 'VALID');
synthetic('STOP may explicitly declare its reference coordinate system', stop, row => { facts(row).coordinateSystem = 'REFERENCE_CDS'; }, 'VALID');
for (const value of [null, false]) synthetic(`STOP mechanism ${String(value)} is not absence`, stop, row => { facts(row).variantMechanism = value; }, 'TECHNICAL');
for (const value of [null, 'ALTERED_CDS']) synthetic(`STOP coordinate ${String(value)} is not reference-CDS absence`, stop, row => { facts(row).coordinateSystem = value; }, 'TECHNICAL');
synthetic('STOP reference PTC mapping null must not stand in for an absent mapping', stop, row => { assessment(row).positions.referencePtc = null; }, 'TECHNICAL');
synthetic('source-shaped missing qualification preserves structural proof and raw MET without adopting', stop, row => {
    facts(row).sourceQualifiedResearch = false; assessment(row).sourceQualifiedResearch = false;
    assessment(row).sourceQualification = { status: 'NOT_AVAILABLE', reasonCode: 'PVS1_CODING_ESCAPE_SOURCE_QUALIFICATION_NOT_AVAILABLE' };
}, 'UNAVAILABLE');
synthetic('source-shaped unavailable adjacent proof preserves raw MET as availability issue', stop, row => {
    const f = facts(row), current = assessment(row);
    f.escapeAdoptionProof = { schemaVersion: 'pvs1-escape-adoption-proof-v1', methodVersion: '1.0.0', scientificApprovalCreated: false,
        rescoringPerformed: false, status: 'UNAVAILABLE', reasonCode: 'PVS1_ESCAPE_PUBLIC_PROOF_INPUT_INVALID' };
    f.codingEscapeAssessment = { schemaVersion: current.schemaVersion, methodVersion: current.methodVersion, proofPointer: current.proofPointer,
        scientificApprovalCreated: false, rescoringPerformed: false, sourceQualifiedResearch: true, sourceQualification: clone(current.sourceQualification),
        status: 'UNAVAILABLE', reasonCode: 'PVS1_ESCAPE_PUBLIC_PROOF_INPUT_INVALID' };
}, 'UNAVAILABLE');
synthetic('malformed present complex assessment cannot borrow old unavailable-proof label', complex, row => {
    facts(row).codingEscapeAssessment = null;
    facts(row).escapeAdoptionProof = { schemaVersion: 'pvs1-escape-adoption-proof-v1', methodVersion: '1.0.0', scientificApprovalCreated: false,
        rescoringPerformed: false, status: 'UNAVAILABLE', reasonCode: 'PVS1_ESCAPE_PUBLIC_PROOF_INPUT_INVALID' };
}, 'TECHNICAL');
const codingProjectionUnavailable = row => {
    const current = assessment(row);
    facts(row).codingEscapeAssessment = { schemaVersion: current.schemaVersion, methodVersion: current.methodVersion, proofPointer: current.proofPointer,
        scientificApprovalCreated: false, rescoringPerformed: false, sourceQualifiedResearch: true, sourceQualification: clone(current.sourceQualification),
        status: 'UNAVAILABLE', reasonCode: 'PVS1_ESCAPE_PUBLIC_PROOF_CODING_HGVS_REQUIRED' };
};
synthetic('coding projection unavailable with independently valid structural proof is availability-only', stop, codingProjectionUnavailable, 'UNAVAILABLE');
synthetic('bare internal rejection reason is not an issuer availability code', stop, row => {
    codingProjectionUnavailable(row); assessment(row).reasonCode = 'CODING_HGVS_REQUIRED';
}, 'TECHNICAL');
synthetic('arbitrary issuer-looking prefixed reason is not an availability code', stop, row => {
    codingProjectionUnavailable(row); assessment(row).reasonCode = 'PVS1_ESCAPE_PUBLIC_PROOF_NOT_A_REAL_ISSUER_REASON';
}, 'TECHNICAL');
synthetic('proof failure cannot masquerade as a coding-projection failure beside AVAILABLE proof', stop, row => {
    codingProjectionUnavailable(row); assessment(row).reasonCode = 'PVS1_ESCAPE_PUBLIC_PROOF_SOURCE_QUALIFICATION_MISMATCH';
}, 'TECHNICAL');
synthetic('unavailable coding projection cannot borrow wrong-target available proof', stop, row => {
    codingProjectionUnavailable(row); proof(row).scope.mondoId = 'MONDO:0000001';
}, 'TECHNICAL');
synthetic('frameshift reference mapping incomplete cannot establish reference PTC PASS', complex, row => {
    assessment(row).positions.referencePtc.positions[1] = null; assessment(row).positions.referencePtc.status = 'CONTAINS_INSERTED_BASES';
    declaredCondition(row, 'REFERENCE_PTC_SPAN', [62, 64]);
});
for (const [name, base, positions] of [['STOP', stop, [499]], ['insertion', insertion, [30, 31, 30, 31]],
    ['negative deletion', deletion, [32, 32, 31, 31]], ['complex', complex, [31, 32, 31, 32]]]) {
    synthetic(`${name} declared reference event preserves ordered duplicate endpoints`, base, row => declaredCondition(row, 'REFERENCE_EVENT_SPAN', positions), 'VALID');
    synthetic(`${name} incomplete or deduplicated reference event cannot PASS`, base, row =>
        declaredCondition(row, 'REFERENCE_EVENT_SPAN', name === 'STOP' ? [499, 499] : [...new Set(positions)]));
}
synthetic('STOP reference PTC scope uses original span endpoints', stop, row => declaredCondition(row, 'REFERENCE_PTC_SPAN', [499, 501]), 'VALID');
synthetic('STOP reference PTC scope must not invent a frameshift triple', stop, row => declaredCondition(row, 'REFERENCE_PTC_SPAN', [499, 500, 501]));
synthetic('frameshift reference PTC scope needs all three mapped bases', insertion, row => declaredCondition(row, 'REFERENCE_PTC_SPAN', [63, 64, 65]), 'VALID');
synthetic('frameshift reference PTC middle base cannot be omitted', insertion, row => declaredCondition(row, 'REFERENCE_PTC_SPAN', [63, 65]));
synthetic('NMD position scope stays NOT_USED_BY_ESCAPE_METHOD without a fabricated PASS', stop, row => {
    proof(row).positionConditions.nmd = { status: 'NOT_USED_BY_ESCAPE_METHOD', conditions: [{ conditionId: 'TEST_ONLY_NMD_DECLARATION',
        coordinate: 'REFERENCE_EVENT_SPAN', minimumInclusive: 1, sourceIds: [proof(row).sources[0].sourceId] }] };
}, 'VALID');
synthetic('unused NMD scope must not carry a PASS assertion', stop, row => {
    proof(row).positionConditions.nmd = { status: 'NOT_USED_BY_ESCAPE_METHOD', conditions: [{ conditionId: 'TEST_ONLY_NMD_DECLARATION',
        coordinate: 'REFERENCE_EVENT_SPAN', minimumInclusive: 1, sourceIds: [proof(row).sources[0].sourceId], status: 'PASS', checkedPositions: [499] }] };
});
for (const base of [stop, insertion, deletion, complex]) check(`legacy absent optional coding field ${base.row.details.pvs1Facts.methodVersion}/${base.candidate}`, () => {
    const run = clone(base.run), row = rowAt(run, base.candidate); delete facts(row).codingEscapeAssessment;
    const parsed = inspect({ row, context: run.candidates[base.candidate].attempts[0].context, profile: run.executionProfile });
    assert.equal(parsed.claimed, false); assert.equal(parsed.valid, true); assert.equal(parsed.view, null);
    assert.equal(api.isPvs1CodingEscapeAssessment(facts(row)), false); assert.deepEqual(api.pvs1CodingEscapeDetails(parsed), []);
    assert.equal(entryAt(run, base.candidate).pvs1CodingProofView, undefined); assert.equal(row.status, 'MET');
});
for (const record of verification.standaloneJvmStoreReaders) check(`source-reported standalone JVM receipt only: ${record.file}`, () => {
    const filename = path.resolve(root, record.file), { bytes, value: receipt } = read(filename), pin = manifest.files.find(file => file.file === record.file);
    assert.equal(hash(bytes), record.rawSha256); assert.equal(hash(bytes), pin.rawSha256); assert.equal(bytes.length, pin.bytes);
    assert.equal(receipt.status, 'PASS'); assert.equal(receipt.runId, record.runId);
    assert.equal(receipt.verificationLayer, 'STANDALONE_JVM_ACMG_BUSINESS_STORE_READ');
    assert.equal(receipt.serviceReconstructed, false); assert.equal(receipt.controllerReconstructed, false);
    assert.equal(receipt.runtimeInstantiated, false); assert.equal(receipt.evaluationTriggered, false);
    assert.equal(receipt.publicPrivateRequestRawBytesMatched, true); assert.equal(receipt.export.completeResponseBytesMatched, true);
    assert.equal(receipt.documents.public.storedResponseShaMatched, true);
    assert.equal(receipt.documents.private.publicPrivateDigestStatus, 'MATCHED');
    assert.equal(receipt.documents.request.storedRequestIdentityShaMatched, true);
    const original = contract.actualJavaPositiveResponses.find(item => item.runId === record.runId);
    assert.equal(receipt.documents.public.rawSha256, original.rawSha256); assert.equal(receipt.export.rawSha256, original.rawSha256);
});
check('source-reported compiler rejection audit is a pinned receipt, not a new compilation', () => {
    const filename = path.resolve(root, contract.sourceCompilerNegative), { bytes, value: audit } = read(filename);
    const pin = manifest.files.find(file => file.file === contract.sourceCompilerNegative);
    assert.equal(hash(bytes), pin.rawSha256); assert.equal(bytes.length, pin.bytes);
    assert.equal(audit.blockedPolicies, 1); assert.equal(audit.boundPolicies, 1); assert.equal(audit.clinicalAcceptance, false);
    assert.deepEqual(audit.policies[0].reasonCodes, ['POLICY_SOURCE_BYTES_REQUIRED']);
    assert.equal(audit.policies[0].status, 'BLOCKED'); assert.equal(audit.policies[1].status, 'SOURCE_QUALIFIED_PROJECT_RESEARCH');
});
check('actual product and validation layers counted without inventing runs', () => {
    assert.equal(productCount, 12); assert.equal(actualRows, 24); assert.equal(availableRows, 20); assert.equal(counterRows, 4); assert.equal(ssrRows, 24);
    assert.equal(structurallyAvailableRows, 20); assert.equal(sourceQualifiedRows, 20); assert.equal(adoptedRows, 20); assert.equal(unavailableProofRows, 0);
    assert.equal(httpCalls, 0);
});
for (const [filename, pin] of pins) check(`original input bytes unchanged: ${path.relative(root, filename)}`, () => assert.equal(hash(fs.readFileSync(filename)), pin));
const receipt = { schemaVersion: 'a9-pvs1-coding-current-view-regression-v1', sourceHead: contract.sourceHead,
    state: failures.length ? 'NOT_PASS' : 'PASS', checks: checks.length, passed: checks.length - failures.length, failures,
    actualPositiveProductJson: 9, actualCounterProductJson: 3, actualPvs1Rows: actualRows,
    actualCounts: { claimedAssessment: availableRows, structuralProofAvailable: structurallyAvailableRows, sourceQualified: sourceQualifiedRows,
        adoptedAfterStrictReader: adoptedRows, unavailableProof: unavailableProofRows, absentOptionalField: counterRows },
    originalUnevaluatedRows: counterRows, actualSummarySsrRows: ssrRows, reportedSeparateJvmReceiptsRead: 3,
    sourceReportedBackendTests: verification.totals, backendTestsRunByThisScript: 0, httpCalls, newAnalysisCalls: 0,
    layers: { actualProducts: 'EXISTING_TEST_ONLY_CURRENT_CONTROLLER_SERVICE_H2_PRODUCTS_READ_ONLY',
        controls: 'SYNTHETIC_DISPLAY_CONTRACT_ONLY', jvm: 'EXISTING_SOURCE_RECEIPTS_NOT_REEXECUTED', source: 'COMMITTED_BLOB_PINS_READ_ONLY' },
    frontendSourceSha256: Object.fromEntries(['utils/acmgPvs1Splice.js', 'utils/acmgInput.js', 'utils/acmgDecisionDetails.js', 'components/EvidenceDecisionSummary.jsx']
        .map(file => [file, hash(fs.readFileSync(path.join(project, 'src/pages/varvis/ChromosomeVisualization', file)))])),
    scriptSha256: hash(fs.readFileSync(__filename)),
    notClaimed: ['new human/clinical qualification', 'new Java/Python/VEP/model/network execution', 'user UI/download acceptance', 'whole A2/A4 scientific completion'],
    inputPins: [...pins].map(([file, rawSha256]) => ({ file, rawSha256 })), reports, controls };
fs.mkdirSync(output, { recursive: true }); fs.writeFileSync(path.join(output, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
fs.writeFileSync(path.join(output, 'checks.json'), JSON.stringify(checks, null, 2) + '\n');
console.log(JSON.stringify({ state: receipt.state, checks: receipt.checks, passed: receipt.passed, failures, actualPvs1Rows: actualRows, httpCalls }, null, 2));
if (failures.length) process.exitCode = 1;
