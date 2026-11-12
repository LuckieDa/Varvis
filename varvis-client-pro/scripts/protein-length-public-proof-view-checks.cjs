// Read-only A5 producer/public display regression. No Java, HTTP, model or scientific execution.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { createRequire, Module } = require('node:module');
const [contractPath, outputPath, sharedPath] = process.argv.slice(2);
if (!contractPath || !outputPath) throw Error('Supply A5 public-proof contract and assigned private output');
const project = path.resolve(__dirname, '..'), shared = path.resolve(sharedPath || project), output = path.resolve(outputPath);
if (!output.replaceAll('\\', '/').endsWith('/dispatch/a9/round4/protein-length-public-proof-regression')) throw Error('Use assigned protein-length-public-proof-regression output');
const dependencies = createRequire(path.join(shared, 'package.json'));
const bundle = dependencies('esbuild').buildSync({ stdin: { contents: `import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';import Summary from './src/pages/varvis/ChromosomeVisualization/components/EvidenceDecisionSummary.jsx';export const renderSummary=entry=>renderToStaticMarkup(<Summary entry={entry}/>);export {decisionDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgDecisionDetails.js';export {inspectProteinLengthProof,proteinLengthProofDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgProteinLengthProof.js';export {ps2IdentityHash} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';export {backgroundEvidenceCards,resultPresentation,presentationCounts} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js';`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', packages: 'external', write: false, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'silent' });
const loaded = new Module(path.join(project, 'scripts', 'protein-length-proof-bundle.cjs'));
loaded.filename = loaded.id; loaded.paths = [path.join(shared, 'node_modules')]; loaded._compile(bundle.outputFiles[0].text, loaded.filename);
const api = loaded.exports, hash = value => createHash('sha256').update(value).digest('hex'), clone = value => structuredClone(value);
let httpCalls = 0; dependencies('axios').defaults.adapter = async () => { httpCalls++; throw Error('No HTTP permitted'); };
const inputPins = new Map(), checks = [], failures = [], observations = [], directReports = [], controls = [];
const read = filename => { const bytes = fs.readFileSync(filename); inputPins.set(filename, hash(bytes)); return { bytes, value: JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, '')) }; };
const { bytes: contractBytes, value: contract } = read(path.resolve(contractPath)), root = path.dirname(path.resolve(contractPath));
const SELECTED = 'SELECTED_V3_AND_EVALUATION_V2', DIRECT = 'DIRECT_EVALUATION_CONTEXT', VIEW_BOUNDARY = 'SERVER_ACTUAL_MATERIAL_REPROJECTION_REQUIRED';
const freeze = value => { if (value && typeof value === 'object' && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); } };
function check(name, action) { try { action(); checks.push({ name, status: 'PASS' }); } catch (error) { const item = { name, status: 'FAIL', error: error.message }; checks.push(item); failures.push(item); } }
const inspect = (row, expected) => api.inspectProteinLengthProof(row, expected, { identityHash: api.ps2IdentityHash });
const facts = row => row.details?.proteinLengthFacts || row.details?.bp3Facts;
const selectedExpected = (candidate, attempt) => ({ mode: SELECTED, candidateId: candidate.candidateId, hypothesisId: attempt.localBatch.hypothesisId, context: attempt.context });
let publicRows = 0, publicRuns = 0, firstScreenRenders = 0, availableRows = 0, pendingRows = 0, sourceMet = 0, sourceNotMet = 0, moduleRows = 0;
let stopBase, repeatBase, readyPendingBase;
check('stable committed A5 consumer contract', () => {
    assert.equal(contract.schemaVersion, 'a5-protein-length-public-proof-consumer-contract-v1');
    assert.equal(contract.sourceCommit, '6defb44f59a980860084aba2bc4862de14390bd6'); assert.equal(contract.samples.length, 11);
});
for (const scenario of contract.samples) {
    const publicRecord = scenario.files.find(file => file.path.endsWith('actual-public.json'));
    const filename = path.resolve(root, publicRecord.path); if (!filename.startsWith(root + path.sep)) throw Error('Source path escaped pinned input');
    const { bytes, value: run } = read(filename), before = hash(JSON.stringify(run)); freeze(run); publicRuns++;
    check(`${scenario.scenario}: normal public file exact pin`, () => { assert.equal(bytes.length, publicRecord.bytes); assert.equal(hash(bytes), publicRecord.rawSha256); });
    const lookups = new Map();
    for (const candidate of run.candidates) for (const [backgroundIndex, attempt] of candidate.attempts.entries()) {
        const expected = selectedExpected(candidate, attempt), cards = api.backgroundEvidenceCards(candidate, run.enabledCriteria, run, backgroundIndex);
        const countsBefore = JSON.stringify(api.presentationCounts(cards.flatMap(card => card.entries.map(entry => ({ card, entry })))));
        for (const row of attempt.localBatch.criteria.filter(row => /:(PM4|BP3)$/.test(row.criterionId))) {
            publicRows++; if (row.status === 'MET') sourceMet++; else if (row.status === 'NOT_MET') sourceNotMet++; else pendingRows++;
            const f = facts(row), parsed = inspect(row, expected), key = `${scenario.scenario}/${candidate.candidateId}/${backgroundIndex}/${row.criterionId}`;
            lookups.set(`${candidate.candidateId}/${attempt.hypothesisId}/${row.criterionId}`, { row, expected });
            if (scenario.scenario === 'stop-loss-rule-control' && row.status === 'MET') stopBase = { row, expected, run, candidateId: candidate.candidateId, backgroundIndex };
            if (scenario.scenario === 'repeat-exclusion-control' && row.status === 'NOT_MET') repeatBase = { row, expected, run, candidateId: candidate.candidateId, backgroundIndex };
            if (!readyPendingBase && row.status === 'NOT_EVALUATED' && f?.sequenceMaterial?.status === 'READY') readyPendingBase = { row, expected };
            check(`${key}: source statuses and current material/proof meanings`, () => {
                assert.equal(parsed.valid, true, JSON.stringify(parsed.issues));
                const claimed = !!(f && ['sequenceMaterial', 'annotationCorrespondence', 'adoptionProof'].some(field => Object.hasOwn(f, field)));
                assert.equal(parsed.claimed, claimed);
                if (!claimed) { assert.equal(parsed.view, null); assert.deepEqual(api.proteinLengthProofDetails(parsed), []); return; }
                const view = parsed.view; assert.deepEqual(view.result, { status: row.status, reasonCode: row.reasonCode, appliedStrength: row.appliedStrength });
                assert.equal(view.materialStatus, f.sequenceMaterial.status); assert.equal(view.annotationCorrespondence, f.annotationCorrespondence);
                assert.equal(view.proofStatus, f.adoptionProof.status); assert.equal(view.sourceAuthentication, VIEW_BOUNDARY);
                assert.equal(view.clinicalValidationCompleted, false); assert.equal(view.createsScientificApproval, false);
                if (row.status === 'NOT_EVALUATED') { assert.equal(row.appliedStrength, 'NONE'); assert.equal(view.proofStatus, 'UNAVAILABLE'); }
                else { assert.equal(view.proofStatus, 'AVAILABLE'); availableRows++; }
                assert.ok(api.proteinLengthProofDetails(parsed).some(([label]) => label === '公开证明校验边界'));
            });
            check(`${key}: normal selected card hook preserves exact result and original source references`, () => {
                const entry = cards.find(card => card.id === row.criterionId).entries[0];
                assert.equal(entry.displayIssue, false, entry.summary); assert.strictEqual(entry.savedCriterion, row);
                assert.equal(entry.evaluation.evaluationResult.status, row.status); assert.equal(entry.evaluation.evaluationResult.reasonCode, row.reasonCode);
                assert.equal(entry.evaluation.appliedStrength, row.appliedStrength);
                assert.deepEqual(entry.proteinLengthProofView ?? null, parsed.view);
                const main = api.resultPresentation(entry).mainKind;
                if (row.status === 'MET') assert.equal(main, 'ADOPTED_MET'); else if (row.status === 'NOT_MET') assert.equal(main, 'NOT_MET');
                else assert.notEqual(main, 'ADOPTED_MET');
            });
            check(`${key}: actual first-screen Summary shows current facts before legacy fallback`, () => {
                const entry = cards.find(card => card.id === row.criterionId).entries[0], decision = api.decisionDetails(entry);
                const html = api.renderSummary(entry), current = decision.issues.find(issue => issue.kind === 'PROTEIN_LENGTH_CURRENT');
                assert.deepEqual(decision.original, { criterionId: row.criterionId, status: row.status, strength: row.appliedStrength, reasonCode: row.reasonCode });
                assert.ok(!html.includes('<button'), 'read-only summary does not offer fabricated continuation');
                if (parsed.view) {
                    assert.ok(current); assert.strictEqual(current.proteinLengthProof, entry.proteinLengthProofView);
                    assert.match(html, /data-testid="protein-length-current-summary"/);
                    assert.ok(html.indexOf('当前序列与规则核查') < html.indexOf('原保存状态、强度及原因'));
                    assert.ok(html.includes(`当前材料 ${parsed.view.materialStatus}`));
                    assert.ok(html.includes(`${row.status} / ${row.appliedStrength}`));
                    assert.ok(html.includes(parsed.view.proofStatus === 'AVAILABLE' ? '证明可核对' : '证明暂不可采用'));
                    assert.match(html, /摘要值本身不认证来源/);
                    if (parsed.view.annotationCorrespondence === 'COMPLETE_SEQUENCE_COMPARISON_PASSED') assert.match(html, /完整序列比较通过/);
                    if (row.status === 'NOT_MET') assert.match(html, /已评价未满足/);
                    if (row.status === 'NOT_EVALUATED') assert.match(html, /尚未评价/);
                    if (scenario.scenario === 'stop-loss-rule-control' && row.status === 'MET') {
                        assert.match(html, /原始DNA编辑净变化/); assert.match(html, /0 nt/); assert.match(html, /6 → 9 aa/);
                        assert.match(html, /CDS延长不等于DNA插入/); assert.doesNotMatch(html, /9 nt/);
                    }
                } else assert.ok(!current, 'old absent proof is not replaced by a fabricated current proof card');
                firstScreenRenders++;
            });
            observations.push({ scenario: scenario.scenario, candidateId: candidate.candidateId, backgroundIndex, criterionId: row.criterionId,
                sourceStatus: row.status, strength: row.appliedStrength, reasonCode: row.reasonCode, claimed: parsed.claimed, valid: parsed.valid,
                materialStatus: parsed.view?.materialStatus ?? null, annotationCorrespondence: parsed.view?.annotationCorrespondence ?? null, proofStatus: parsed.view?.proofStatus ?? null });
        }
        check(`${scenario.scenario}/${candidate.candidateId}: four counts conserved without rank/result mutation`, () => {
            assert.equal(JSON.stringify(api.presentationCounts(api.backgroundEvidenceCards(candidate, run.enabledCriteria, run, backgroundIndex)
                .flatMap(card => card.entries.map(entry => ({ card, entry }))))), countsBefore); assert.equal(hash(JSON.stringify(run)), before);
        });
    }
    const moduleRecord = scenario.files.find(file => file.path.endsWith('actual-module-public-summaries.json'));
    const moduleFilename = path.resolve(root, moduleRecord.path), { bytes: moduleBytes, value: modules } = read(moduleFilename); freeze(modules);
    check(`${scenario.scenario}: actual module public summaries exact pin`, () => { assert.equal(moduleBytes.length, moduleRecord.bytes); assert.equal(hash(moduleBytes), moduleRecord.rawSha256); });
    for (const [moduleName, records] of Object.entries(modules)) for (const record of records) {
        const criterionId = moduleName === 'pm4' ? 'ACMG_AMP_2015:PM4' : 'ACMG_AMP_2015:BP3';
        const source = lookups.get(`${record.candidateId}/${record.hypothesisId}/${criterionId}`); moduleRows++;
        check(`${scenario.scenario}/${moduleName}/${record.candidateId}: public producer summary agrees with selected saved row`, () => {
            assert.ok(source); assert.equal(record.status, source.row.status); assert.equal(record.reasonCode, source.row.reasonCode);
            assert.deepEqual(record.publicSummary, facts(source.row));
            const row = { ...source.row, details: { [moduleName === 'pm4' ? 'proteinLengthFacts' : 'bp3Facts']: record.publicSummary } };
            assert.equal(inspect(row, source.expected).valid, true);
        });
    }
}
check('exact public coverage and independent result expectations', () => {
    assert.equal(publicRuns, 11); assert.equal(publicRows, 40); assert.equal(sourceMet, 1); assert.equal(sourceNotMet, 1); assert.equal(pendingRows, 38); assert.equal(availableRows, 2);
    const view = inspect(stopBase.row, stopBase.expected).view;
    assert.equal(stopBase.row.appliedStrength, 'MODERATE'); assert.equal(view.change.netNucleotideChange, 0); assert.equal(view.change.netAminoAcidChange, 3);
    assert.equal(view.change.referenceProteinLength, 6); assert.equal(view.change.alteredProteinLength, 9); assert.equal(view.change.alteredStopCdsPosition, 28);
    assert.equal(view.change.netNucleotideChangeMeaning, 'EDIT_DELTA_EXCLUDING_PREDICTED_THREE_PRIME_EXTENSION');
    assert.equal(inspect(repeatBase.row, repeatBase.expected).view.rule.branch, 'OBSERVED_REPEAT_OVERLAP_EXCLUDES_PM4');
});
// Independent expected context from committed producer TEST constants, not from any proof/private receipt.
// 6defb44f: GeneralBp3RuntimeTest.setup -> ProteinLengthChangeMaterialSupplyTest.setup ->
// Pm4TestFixtures.variant -> RuntimeEvidenceV2ProviderWiringTest.syntheticContext and /e2/runtime-evidence-v2-fixture.json.
function directBp3Expected() {
    const allele = { assembly: 'GRCh38', contig: '11', position: 105, reference: 'A', alternate: 'AGCT' };
    const normalization = { status: 'UNCHANGED', method: 'synthetic-left-normalizer', methodVersion: '1.0.0', referenceSequenceSha256: 'f'.repeat(64), refValidation: 'MATCH' };
    const alleleId = 'allele-sha256-' + api.ps2IdentityHash({ normalizedAllele: allele, normalizer: { method: normalization.method,
        methodVersion: normalization.methodVersion, referenceSequenceSha256: normalization.referenceSequenceSha256, refValidation: normalization.refValidation } });
    const context = { schemaVersion: 'evidence-context-v2', canonicalizationProfile: 'varvis-json-c14n-v1', dataClass: 'SYNTHETIC',
        scope: { analysisDomain: 'GERMLINE', diseaseModel: 'MENDELIAN', variantScope: 'SMALL_SEQUENCE_VARIANT' },
        variant: { alleleId, variantType: 'SMALL_INSERTION', inputAllele: clone(allele), normalizedAllele: clone(allele), evidenceQueryAllele: clone(allele), normalization, assemblyLineage: { mappingStatus: 'NATIVE' } },
        gene: { hgncId: 'HGNC:4827', symbol: 'HBB' }, disease: { mondoId: 'MONDO:0000001', label: 'TEST_ONLY' }, moi: 'AUTOSOMAL_RECESSIVE',
        transcript: { accession: 'ENST000001', version: 1, selectionPolicy: 'SYNTHETIC_PINNED_TRANSCRIPT', annotationSourceVersion: '116.0' },
        patientContext: { patientContextId: 'patient-context-synthetic-e1-proband', familyContextId: 'family-context-synthetic-e1-family', role: 'PROBAND', affectedStatus: 'AFFECTED', sex: 'UNKNOWN' },
        contextCompleteness: { status: 'COMPLETE', missingFields: [] } };
    context.contextId = 'context-sha256-' + api.ps2IdentityHash({ scope: context.scope, alleleId, hgncId: context.gene.hgncId,
        mondoId: context.disease.mondoId, moi: context.moi, transcript: { accession: context.transcript.accession, version: 1 },
        patientContextId: context.patientContext.patientContextId, familyContextId: context.patientContext.familyContextId });
    return { mode: DIRECT, candidateId: 'legacy-v1-' + hash('GRCh38|11|105|A|AGCT').slice(0, 24), hypothesisId: 'test-hypothesis', context };
}
let directPositive, directNegative;
const directDirectory = path.resolve(root, contract.directJavaSampleRoot);
for (const [prefix, status, strength, reasonCode] of [['reviewed-bp3-no_known_function-', 'MET', 'SUPPORTING', 'BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION'],
    ['reviewed-bp3-known_function-', 'NOT_MET', 'NONE', 'BP3_REVIEWED_NON_REPEAT_OR_KNOWN_FUNCTION']]) {
    const filename = path.join(directDirectory, fs.readdirSync(directDirectory).filter(file => file.startsWith(prefix)).sort()[0]);
    const { bytes, value: artifact } = read(filename); // Only the public summary is consumed; actualModuleResult/privateReceipt are never accessed.
    const publicSummary = artifact.actualPublicSummary; freeze(publicSummary);
    const row = { criterionId: 'ACMG_AMP_2015:BP3', status, appliedStrength: strength, reasonCode, reason: 'TEST_ONLY direct producer public summary', details: { bp3Facts: publicSummary } };
    const expected = directBp3Expected(), parsed = inspect(row, expected), before = JSON.stringify(publicSummary);
    check(`${prefix}: DIRECT actual public summary with independently constructed TEST context`, () => {
        assert.equal(parsed.valid, true, JSON.stringify(parsed.issues)); assert.equal(parsed.view.proofStatus, 'AVAILABLE');
        assert.equal(parsed.view.expectedBindingMode, DIRECT); assert.equal(parsed.view.selectedContextId, null);
        assert.equal(parsed.view.result.status, status); assert.equal(parsed.view.result.appliedStrength, strength);
        assert.equal(publicSummary.adoptionProof.binding.candidateId, expected.candidateId);
        assert.equal(publicSummary.adoptionProof.binding.evaluationContextId, expected.context.contextId);
        assert.ok(!Object.hasOwn(parsed.view.binding, 'selectedContextId')); assert.ok(!Object.hasOwn(parsed.view.binding, 'projectionBindingSha256'));
        assert.equal(JSON.stringify(publicSummary), before);
    });
    check(`${prefix}: no full explicit context or fake selected mode never passes`, () => {
        assert.equal(inspect(row, { mode: DIRECT, candidateId: expected.candidateId, hypothesisId: expected.hypothesisId }).valid, false);
        assert.equal(inspect(row, { ...expected, mode: SELECTED }).valid, false);
    });
    if (status === 'MET') directPositive = { row, expected }; else directNegative = { row, expected };
    directReports.push({ file: path.relative(root, filename), rawSha256: hash(bytes), status, strength, parsed: parsed.valid,
        layer: 'ACTUAL_DIRECT_PUBLIC_SUMMARY_PLUS_SYNTHETIC_EXPECTED_CONTEXT_FROM_COMMITTED_TEST_CONSTANTS', contextProvenance: '6defb44f producer test constants; never privateReceipt or proof-derived context; not a normal public BP3 product' });
}
function negative(label, base, mutate) {
    check(`synthetic rejection: ${label}`, () => {
        const row = clone(base.row), expected = clone(base.expected); mutate(row, expected);
        const before = JSON.stringify([row, expected]), parsed = inspect(row, expected);
        assert.equal(parsed.valid, false, label); assert.deepEqual(api.proteinLengthProofDetails(parsed), []);
        assert.equal(JSON.stringify([row, expected]), before); controls.push(label);
    });
}
for (const field of ['candidateId', 'hypothesisId']) negative(`wrong ${field}`, stopBase, (row, expected) => { expected[field] = 'wrong-' + field; });
negative('incomplete selected context', stopBase, (row, expected) => { expected.context = {}; });
negative('SELECTED cannot switch to DIRECT', stopBase, (row, expected) => { expected.mode = DIRECT; });
negative('selected source/context mismatch', stopBase, (row, expected) => { expected.context.patientContext.patientContextId = 'another-patient-context'; });
for (const field of ['queryAlleleSha256', 'modelCanonicalSha256', 'manifestSha256', 'modelSourceRecordsSha256', 'referenceSha256', 'referenceIndexSha256', 'storedAnnotationSha256', 'policySha256', 'materialSha256', 'resultSha256', 'projectionBindingSha256']) {
    negative(`one-sided ${field} substitution`, stopBase, row => { facts(row).sequenceMaterial.binding[field] = hash('WRONG_' + field); });
}
negative('query allele copy from another target', stopBase, row => { facts(row).sequenceMaterial.binding.queryAllele.position++; });
negative('v3 selected identity used as v2 evaluation identity', stopBase, row => { facts(row).sequenceMaterial.binding.evaluationContextId = facts(row).sequenceMaterial.binding.selectedContextId; });
negative('wrong transcript', stopBase, row => { facts(row).sequenceMaterial.binding.transcript = 'ENST000999.1'; });
negative('wrong protein', stopBase, row => { facts(row).sequenceMaterial.binding.protein = 'ENSP000999.1'; });
negative('criterion changed while proof retained', stopBase, row => { row.criterionId = 'ACMG_AMP_2015:BP3'; });
for (const [field, value] of [['status', 'NOT_MET'], ['reasonCode', 'UNRELATED_RESULT'], ['appliedStrength', 'STRONG']]) negative(`outer ${field} changed`, stopBase, row => { row[field] = value; });
negative('READY and COMPLETE cannot promote NE to AVAILABLE', readyPendingBase, row => { facts(row).adoptionProof.status = 'AVAILABLE'; });
negative('NE cannot become MET from material READY', readyPendingBase, row => { row.status = 'MET'; row.appliedStrength = 'MODERATE'; });
negative('approval flag true', stopBase, row => { facts(row).adoptionProof.createsScientificApproval = true; });
negative('data class relabelled as real research', stopBase, row => { facts(row).adoptionProof.dataClass = 'PUBLIC_RESEARCH'; });
negative('rule source policy mismatch', stopBase, row => { facts(row).adoptionProof.rule.policySha256 = hash('OTHER_POLICY'); });
negative('stop loss uses wrong DNA/CDS delta meaning', stopBase, row => { facts(row).sequenceMaterial.change.netNucleotideChangeMeaning = 'CDS_LENGTH_DIFFERENCE'; });
negative('resealed stop-loss DNA delta cannot include predicted CDS extension', stopBase, row => {
    for (const envelope of [facts(row).sequenceMaterial, facts(row).adoptionProof]) envelope.change.netNucleotideChange = 9;
});
negative('unresolved stop loss', stopBase, row => { facts(row).sequenceMaterial.change.newTerminationResolved = false; });
negative('stop loss exceeds inspected window', stopBase, row => { facts(row).sequenceMaterial.change.inspectedThreePrimeBases = 100001; });
negative('stop loss wrong termination position', stopBase, row => { facts(row).sequenceMaterial.change.alteredStopCdsPosition++; });
negative('ordinary in-frame non-triplet edit', repeatBase, row => { facts(row).sequenceMaterial.change.netNucleotideChange = 1; });
negative('repeat observation outside query', repeatBase, row => { facts(row).adoptionProof.rule.repeatObservations[0].start = 1000; facts(row).adoptionProof.rule.repeatObservations[0].end = 1000; });
negative('repeat observation arbitrary path', repeatBase, row => { facts(row).adoptionProof.rule.repeatObservations[0].sourceLocator = 'C:/PRIVATE/path'; });
for (const field of ['sourceId', 'version']) check(`synthetic resealed public identifier cannot contain a machine path: ${field}`, () => {
    const run = clone(stopBase.run), candidate = run.candidates.find(value => value.candidateId === stopBase.candidateId), attempt = candidate.attempts[stopBase.backgroundIndex];
    const row = attempt.localBatch.criteria.find(value => value.criterionId === stopBase.row.criterionId), pathValue = `C:/PRIVATE/${field}`;
    for (const envelope of [facts(row).sequenceMaterial, facts(row).adoptionProof]) envelope.sourcePins[0][field] = pathValue;
    assert.deepEqual(facts(row).sequenceMaterial.sourcePins, facts(row).adoptionProof.sourcePins, 'cross-copy identity remains equal so the public identifier whitelist is tested');
    const before = JSON.stringify(run), parsed = inspect(row, selectedExpected(candidate, attempt));
    assert.equal(parsed.valid, false); assert.equal(parsed.view, null); assert.deepEqual(api.proteinLengthProofDetails(parsed), []);
    const entry = api.backgroundEvidenceCards(candidate, run.enabledCriteria, run, stopBase.backgroundIndex).find(card => card.id === row.criterionId).entries[0];
    assert.equal(entry.displayIssue, true); assert.equal(entry.proteinLengthProofView, undefined);
    assert.ok(!api.renderSummary(entry).includes(pathValue)); assert.equal(JSON.stringify(run), before);
    controls.push(`both material/proof public ${field} machine path`);
});
negative('proof leaks full private model', stopBase, row => { facts(row).adoptionProof.model = { cdsSequence: 'PRIVATE_SEQUENCE' }; });
negative('binding leaks private source path', stopBase, row => { facts(row).sequenceMaterial.binding.path = 'C:/PRIVATE/model.json'; });
negative('too many model source records', stopBase, row => { const m = facts(row).sequenceMaterial; m.sourcePins = [...Array.from({ length: 17 }, (_, i) => ({ kind: 'TRANSCRIPT_MODEL', sha256: hash('MODEL_' + i) })), ...m.sourcePins.filter(pin => pin.kind !== 'TRANSCRIPT_MODEL')]; });
for (const url of ['file:///PRIVATE/model', 'https://user:password@example.invalid/a', 'https://example.invalid/a?secret=1', 'https://example.invalid/a#private']) negative('unsafe source URL ' + url.split(':')[0] + '/' + hash(url).slice(0, 4), stopBase, row => { facts(row).sequenceMaterial.sourcePins[0].url = url; });
negative('DIRECT cannot borrow selected projection keys', directPositive, row => { facts(row).sequenceMaterial.binding.selectedContextId = stopBase.expected.context.contextId; });
negative('BP3 reviewed coverage boundary short', directPositive, row => { facts(row).adoptionProof.rule.coverageIntervals[0].end = 1; });
negative('BP3 reviewed favorable/opposite full coverage conflict', directPositive, row => { const intervals = facts(row).adoptionProof.rule.coverageIntervals; intervals.push({ ...intervals[0], repeatStatus: 'NON_REPEAT', functionalStatus: 'KNOWN_FUNCTION' }); });
negative('BP3 positive functional fact contradicts result', directPositive, row => { facts(row).adoptionProof.rule.coverageIntervals[0].functionalStatus = 'KNOWN_FUNCTION'; });
negative('BP3 exclusion cannot borrow no-function positive facts', directNegative, row => { facts(row).adoptionProof.rule.coverageIntervals[0].functionalStatus = 'NO_KNOWN_FUNCTION'; });
// Independent synthetic PM4 reviewed-rule display fixture; no producer or scientific adoption is claimed.
const reviewedPm4 = clone(directPositive);
reviewedPm4.row.criterionId = 'ACMG_AMP_2015:PM4'; reviewedPm4.row.appliedStrength = 'MODERATE'; reviewedPm4.row.reasonCode = 'PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET';
const pf = clone(reviewedPm4.row.details.bp3Facts); reviewedPm4.row.details = { proteinLengthFacts: pf };
Object.assign(pf, { schemaVersion: 'pm4-general-facts-v1', policyVersion: '1.2.0', factsVersion: '1.3.0', regionState: 'NON_REPEAT_SUPPORTED', repeatObservation: 'NO_REPEAT_OBSERVED',
    shortRepeatCandidate: false, nonRepeatFromNoHit: false, qualificationBasis: 'REVIEWED_COVERAGE', nonRepeatPolicy: 'REVIEWED_ONLY', absoluteNonRepeatProof: false, pvs1CombinedAdoptionSupported: false });
delete pf.functionAbsenceInferredFromNoHit; delete pf.functionalLeadCount;
const pr = pf.adoptionProof; Object.assign(pr, { criterionId: 'ACMG_AMP_2015:PM4', result: { status: 'MET', reasonCode: reviewedPm4.row.reasonCode, appliedStrength: 'MODERATE' } });
Object.assign(pr.rule, { policyId: 'PROJECT_LOCAL_PM4_LENGTH_CHANGE', policyVersion: '1.2.0', reasonCode: reviewedPm4.row.reasonCode, branch: 'REVIEWED_NON_REPEAT', regionState: 'NON_REPEAT_SUPPORTED' });
pr.rule.coverageIntervals[0].repeatStatus = 'NON_REPEAT'; pr.rule.coverageIntervals[0].functionalStatus = 'UNKNOWN';
check('synthetic reviewed PM4 control is structurally valid before adding conflicts', () => assert.equal(inspect(reviewedPm4.row, reviewedPm4.expected).valid, true));
negative('PM4 reviewed non-repeat/repeat full coverage conflict', reviewedPm4, row => { const intervals = facts(row).adoptionProof.rule.coverageIntervals; intervals.push({ ...intervals[0], repeatStatus: 'REPEAT' }); });
for (const base of [stopBase, repeatBase]) check(`synthetic actual producer rule-limit availability retains original ${base.row.status}`, () => {
    const run = clone(base.run), candidate = run.candidates.find(value => value.candidateId === base.candidateId), attempt = candidate.attempts[base.backgroundIndex];
    const row = attempt.localBatch.criteria.find(value => value.criterionId === base.row.criterionId), p = facts(row).adoptionProof;
    p.status = 'UNAVAILABLE'; p.reasonCode = 'REPEAT_PROOF_LIMIT_EXCEEDED'; delete p.rule; delete p.unmetCondition;
    const before = JSON.stringify(run), parsed = inspect(row, selectedExpected(candidate, attempt));
    assert.equal(parsed.valid, false); assert.deepEqual(parsed.issues, ['PROTEIN_LENGTH_ADOPTION_PUBLIC_PROOF_UNAVAILABLE']);
    assert.equal(parsed.view.availabilityOnly, true); assert.equal(parsed.view.materialStatus, 'READY'); assert.equal(parsed.view.rule, null);
    assert.equal(parsed.view.result.status, base.row.status); assert.ok(api.proteinLengthProofDetails(parsed).length > 0);
    const entry = api.backgroundEvidenceCards(candidate, run.enabledCriteria, run, base.backgroundIndex).find(card => card.id === row.criterionId).entries[0];
    assert.equal(entry.displayIssue, true); assert.equal(entry.evaluation, undefined); assert.strictEqual(entry.savedCriterion, row);
    assert.equal(api.resultPresentation(entry).mainKind, 'NO_CONCLUSION'); assert.equal(api.resultPresentation(entry).outcome.kind, 'UNAVAILABLE');
    assert.equal(entry.proteinLengthProofView.availabilityOnly, true);
    const html = api.renderSummary(entry); assert.match(html, /公开采用证明暂不可用/); assert.match(html, /当前材料 READY/);
    assert.match(html, /证明暂不可采用/); assert.match(html, /采用证明核对缺口/); assert.ok(html.includes(`${row.status} / ${row.appliedStrength}`));
    assert.ok(!html.includes('<button')); assert.equal(JSON.stringify(run), before);
});
negative('an arbitrary failure cannot borrow bounded unavailable view', stopBase, row => {
    const p = facts(row).adoptionProof; p.status = 'UNAVAILABLE'; p.reasonCode = 'CLIENT_INVENTED_REASON'; delete p.rule;
});
negative('MET cannot use the NE final-not-adopted receipt', stopBase, row => {
    const p = facts(row).adoptionProof; p.status = 'UNAVAILABLE'; p.reasonCode = 'FINAL_RESULT_NOT_ADOPTED'; delete p.rule;
});
const actualStopCandidate = stopBase.run.candidates.find(candidate => candidate.candidateId === stopBase.candidateId);
const actualStopEntry = api.backgroundEvidenceCards(actualStopCandidate, stopBase.run.enabledCriteria, stopBase.run, stopBase.backgroundIndex)
    .find(card => card.id === 'ACMG_AMP_2015:PM4').entries[0];
for (const [label, alter] of [
    ['unrelated display issue', entry => { entry.displayIssue = true; }],
    ['availability marker lacks exact issue', entry => { entry.displayIssue = true; entry.proteinLengthProofView.availabilityOnly = true; entry.displayOutcome = { kind: 'UNAVAILABLE' }; entry.presentationIssues = []; }],
    ['availability marker has another outcome', entry => { entry.displayIssue = true; entry.proteinLengthProofView.availabilityOnly = true; entry.displayOutcome = { kind: 'UNRESOLVED' }; entry.presentationIssues = ['PROTEIN_LENGTH_ADOPTION_PUBLIC_PROOF_UNAVAILABLE']; }],
    ['inconsistent original tuple', entry => { entry.proteinLengthProofView.result.reasonCode = 'OTHER_RESULT'; }],
    ['DIRECT view borrowed by selected details', entry => { entry.proteinLengthProofView.expectedBindingMode = DIRECT; }],
    ['approval flag borrowed into details', entry => { entry.proteinLengthProofView.clinicalValidationCompleted = true; }],
    ['unrecognized authentication declaration', entry => { entry.proteinLengthProofView.sourceAuthentication = 'CLIENT_SELF_ATTESTED'; }],
]) check(`synthetic first-screen isolation: ${label}`, () => {
    const entry = clone(actualStopEntry); alter(entry);
    assert.ok(!api.decisionDetails(entry).issues.some(issue => issue.kind === 'PROTEIN_LENGTH_CURRENT'));
    assert.ok(!api.renderSummary(entry).includes('data-testid="protein-length-current-summary"'));
});
for (const change of [false, true]) check(`synthetic optional source labels and legal HTTP source remain readable ${change}`, () => {
    const row = clone(stopBase.row), f = facts(row);
    for (const branch of [f.sequenceMaterial, f.adoptionProof]) for (const pin of branch.sourcePins.filter(pin => pin.kind === 'TRANSCRIPT_MODEL')) {
        delete pin.sourceId; delete pin.version; pin.url = change ? 'http://example.invalid/model' : 'https://example.invalid/model';
    }
    assert.equal(inspect(row, stopBase.expected).valid, true);
});
check('legacy no optional public fields remains unclaimed and readable', () => {
    const row = clone(readyPendingBase.row); for (const field of ['sequenceMaterial', 'annotationCorrespondence', 'adoptionProof']) delete facts(row)[field];
    assert.deepEqual(inspect(row, readyPendingBase.expected), { claimed: false, valid: true, issues: [], view: null });
});
check('all sources and file bytes remain immutable; no source authentication is claimed', () => {
    for (const [filename, pin] of inputPins) assert.equal(hash(fs.readFileSync(filename)), pin);
    assert.equal(httpCalls, 0); assert.equal(inspect(stopBase.row, stopBase.expected).view.sourceAuthentication, VIEW_BOUNDARY);
});
const report = { schemaVersion: 'protein-length-public-proof-view-checks-v1', sourceCommit: contract.sourceCommit, contractSha256: hash(contractBytes),
    layer: 'Pinned actual 6def public producer outputs + selected adapter; DIRECT expected context is explicitly independent committed TEST constants. No central re-projection/fresh HTTP acceptance claimed.',
    counts: { checks: checks.length, failures: failures.length, normalPublicJson: publicRuns, normalPublicRows: publicRows, actualModulePublicSummaryRows: moduleRows, actualFirstScreenSummarySsrRows: firstScreenRenders,
        sourceMet, sourceNotMet, sourceNotEvaluated: pendingRows, availableProofRows: availableRows, directPublicSummaryScenarios: directReports.length, httpCalls },
    inputPins: [...inputPins].map(([filename, sha256]) => ({ filename, sha256 })), observations, directReports, syntheticNegativeControls: controls, checks, failures,
    limitations: ['No privateReceipt, actualModuleResult, expected-private or private context is used to construct public facts or expected context.',
        'A valid display hash/shape does not authenticate issuer or source; central sourceSnapshot/modulePointer/fresh capability final integration remains separate.',
        'No Java/backend tests, new scientific analysis, sources/models, browser, live save/history/export HTTP or actual download.'] };
fs.mkdirSync(output, { recursive: true }); fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ counts: report.counts, directReports, failures, summary: path.join(output, 'summary.json') }, null, 2));
if (failures.length) process.exitCode = 1;
