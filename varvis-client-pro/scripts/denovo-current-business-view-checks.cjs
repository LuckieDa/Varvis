// Current saved Controller outputs only: no Java, HTTP, discovery or model call.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');
const indexPath = process.argv[2];
if (!indexPath) throw Error('Supply current-business-final/public-samples.json.');
const directory = path.dirname(path.resolve(indexPath));
const project = path.resolve(__dirname, '..');
const dependencyRoot = path.resolve(process.argv[3] || path.resolve(project, '../../../../../varvis-client-pro'));
const dependencies = createRequire(path.join(dependencyRoot, 'package.json'));
const bundled = dependencies('esbuild').buildSync({ stdin: { contents: `
import React from 'react';
import {renderToStaticMarkup} from 'react-dom/server';
import Summary from './src/pages/varvis/ChromosomeVisualization/components/EvidenceDecisionSummary.jsx';
export {localBatchScopeIssue} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
export {backgroundEvidenceCards,resultPresentation,presentationCounts} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js';
export {decisionDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgDecisionDetails.js';
export const render=entry=>renderToStaticMarkup(<Summary entry={entry}/>);
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', write: false,
    nodePaths: [path.join(dependencyRoot, 'node_modules')],
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' }).outputFiles[0].text;
const compiled = { exports: {} };
new Function('require', 'module', 'exports', bundled)(dependencies, compiled, compiled.exports);
const api = compiled.exports;
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const indexBytes = fs.readFileSync(indexPath), index = JSON.parse(indexBytes);
assert.equal(index.length, 27, 'this is the current 27-output Controller contract, not older direct module fixtures');
const loaded = new Map(), pins = new Map();
function freeze(value) {
    if (value && typeof value === 'object' && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); }
    return value;
}
for (let number = 0; number < 27; number++) {
    const file = `p${number}.json`, metadata = index.find((sample) => sample.file === file);
    assert.ok(metadata, file);
    const filename = path.resolve(directory, file), bytes = fs.readFileSync(filename);
    assert.ok(bytes.length < 2 * 1024 * 1024, 'bounded saved public output');
    assert.equal(sha(bytes), metadata.sha256, file);
    const run = JSON.parse(bytes);
    assert.equal(run.runId, metadata.runId);
    assert.deepEqual(run.enabledCriteria, metadata.enabledCriteria);
    loaded.set(file, { run: freeze(run), metadata, before: JSON.stringify(run) }); pins.set(filename, metadata.sha256);
}
const selected = [3, 7, 12, 13, 14, 15, 16, 20, 24, 26];
const reportRows = [], results = new Map();
let totalRows = 0, deNovoRows = 0, renderedRows = 0, adoptedRows = 0, sharedRows = 0;
for (const number of selected) {
    const file = `p${number}.json`, { run, metadata } = loaded.get(file), caseRows = [];
    assert.equal(api.localBatchScopeIssue(run), null, `${file}: supported real Controller profile must not disappear`);
    if (metadata.selection === 'all') assert.equal(run.enabledCriteria.length, 10, 'all means ten criteria in this source profile');
    for (const candidate of run.candidates) for (const [background, attempt] of candidate.attempts.entries()) {
        const cards = api.backgroundEvidenceCards(candidate, run.enabledCriteria, run, background);
        assert.equal(cards.length, run.enabledCriteria.length);
        const entries = cards.flatMap((card) => card.entries);
        assert.equal(entries.length, run.enabledCriteria.length);
        for (const entry of entries) assert.notEqual(entry.displayIssue, true, `${file}: unexpected rejected row ${entry.savedCriterion?.criterionId}: ${entry.summary}`);
        totalRows += entries.length;
        for (const card of cards.filter((value) => /:(PS2|PM6)$/.test(value.id))) {
            const entry = card.entries[0], original = attempt.localBatch.criteria.find((row) => row.criterionId === card.id);
            const expected = metadata.evidence[card.id], facts = original.details.deNovoFacts, proof = facts.adoptionProof;
            assert.equal(entry.displayIssue, false, `${file}: de novo adapter must explicitly accept the current row`);
            assert.ok(expected); assert.equal(entry.savedCriterion, original);
            assert.equal(original.status, expected.status); assert.equal(original.appliedStrength, expected.strength);
            assert.equal(proof.status, expected.proofStatus);
            assert.equal(entry.evaluation.evaluationResult.status, original.status);
            assert.equal(entry.evaluation.evaluationResult.reasonCode, original.reasonCode);
            assert.equal(entry.evaluation.appliedStrength, original.appliedStrength);
            assert.equal(facts.clinicalApproval, 'NONE');
            const view = api.decisionDetails(entry), presentation = api.resultPresentation(entry), html = api.render(entry);
            assert.equal(view.original.status, original.status); assert.equal(view.original.strength, original.appliedStrength);
            assert.ok(!view.issues.some((issue) => issue.kind === 'DISPLAY'), `${file}: valid source proof cannot silently lose its comparisons`);
            assert.ok(html.includes('判定核查与条件对照') && html.includes(original.status) && html.includes(original.appliedStrength));
            const receipt = view.issues.find((issue) => issue.kind === 'EXECUTION_RECEIPT');
            assert.ok(receipt);
            assert.equal(receipt.detail.declaredCalculatorVersion, 'denovo-event-calculator-1.2.0');
            assert.equal(receipt.detail.runtimeCalculatorVersion, 'denovo-event-calculator-1.2.0');
            assert.equal(receipt.detail.executedCalculatorVersion, 'denovo-event-calculator-1.2.0');
            const adopted = view.comparisons.filter((row) => row.origin === '新发已保存采用条件');
            if (original.status === 'MET') {
                assert.equal(presentation.mainKind, 'ADOPTED_MET'); adoptedRows++;
                if (proof.status === 'ADOPTED') assert.ok(adopted.length > 0, `${file}: adopted special proof has visible conditions`);
            } else {
                assert.notEqual(presentation.mainKind, 'ADOPTED_MET');
                assert.equal(adopted.length, 0, 'shared or rejected proof must not grant this row adoption');
            }
            if (original.reasonCode.startsWith('DENOVO_SHARED_EVENT_ASSIGNED_TO_')) {
                assert.equal(presentation.adoption.kind, 'NOT_COUNTED');
                assert.equal(presentation.outcome.kind, 'NOT_COUNTED'); sharedRows++;
                assert.ok(view.issues.some((issue) => issue.kind === 'SHARED_EVENT') || proof.status !== 'ADOPTED');
            }
            const row = { file, case: metadata.case, selection: metadata.selection, criterionId: card.id,
                status: original.status, strength: original.appliedStrength, reasonCode: original.reasonCode,
                proofStatus: proof.status, proofSelectedCriterion: proof.selectedCriterion,
                calculationMethod: proof.calculationMethod, conditionBranches: (proof.events || []).map((event) => event.conditionBranch),
                rawQuarterPoints: facts.rawQuarterPoints, effectiveQuarterPoints: facts.effectiveQuarterPoints,
                adoptionConditions: adopted.length, mainKind: presentation.mainKind, displayIssue: false,
                ssrSha256: sha(html), executedCalculatorVersion: receipt.detail.executedCalculatorVersion };
            reportRows.push(row); caseRows.push({ ...row, entry, view, html, facts }); deNovoRows++; renderedRows++;
        }
        const expectedMet = Object.values(metadata.evidence).filter((value) => value.status === 'MET').length;
        const counts = api.presentationCounts(entries.filter((entry) => /:(PS2|PM6)$/.test(entry.savedCriterion?.criterionId)));
        assert.equal(counts.find((count) => count.kind === 'ADOPTED_MET').count, expectedMet, `${file}: no double count from shared proof`);
    }
    results.set(file, caseRows);
}
const find = (file, criterion) => results.get(file).find((row) => row.criterionId === `ACMG_AMP_2015:${criterion}`);
assert.equal(find('p3.json', 'PS2').strength, 'SUPPORTING');
assert.equal(find('p7.json', 'PS2').strength, 'MODERATE');
assert.equal(find('p12.json', 'PM6').strength, 'SUPPORTING');
assert.equal(find('p20.json', 'PS2').strength, 'MODERATE');
for (const file of ['p7.json', 'p13.json', 'p15.json', 'p16.json', 'p20.json']) {
    const row = find(file, 'PS2');
    assert.equal(row.calculationMethod, 'EXPERT_FIXED_STRENGTH');
    assert.equal(row.rawQuarterPoints, 0); assert.equal(row.effectiveQuarterPoints, 0);
    assert.ok(row.adoptionConditions > 0, 'zero SVI points do not negate saved fixed-strength adoption');
}
const pm6Only = find('p14.json', 'PM6');
assert.equal(results.get('p14.json').length, 1);
assert.equal(pm6Only.proofSelectedCriterion, 'PS2'); assert.equal(pm6Only.proofStatus, 'ADOPTED');
assert.equal(pm6Only.status, 'NOT_EVALUATED'); assert.equal(pm6Only.strength, 'NONE');
assert.notEqual(pm6Only.mainKind, 'ADOPTED_MET'); assert.equal(pm6Only.adoptionConditions, 0);
const sharedSource = (row) => ({ materials: row.facts.coreMaterials.map((material) => ({ caseKey: material.caseKey,
    documentSha256: material.documentSha256, ledgerSha256: material.ledgerSha256 })),
    phenotypePolicySha256: row.facts.adoptionProof.policy.phenotypePolicySha256 });
for (const file of ['p14.json', 'p15.json', 'p16.json'])
    assert.deepEqual(sharedSource(results.get(file)[0]), sharedSource(results.get('p13.json')[0]), 'same source across four selected scopes');
for (const file of ['p13.json', 'p15.json', 'p16.json']) {
    const row = find(file, 'PS2'); assert.equal(row.strength, 'STRONG'); assert.deepEqual(row.conditionBranches, ['C1_AND_C2']);
}
for (const row of results.get('p24.json')) {
    assert.equal(row.status, 'NOT_EVALUATED'); assert.equal(row.strength, 'NONE');
    assert.ok(row.facts.events.some((event) => event.reasonCode === 'DENOVO_TARGET_TEST_QUALITY_FAILED'));
    assert.ok(row.html.includes('DENOVO_TARGET_TEST_QUALITY_FAILED'), 'actual quality failure reason remains visible');
}
for (const row of results.get('p26.json')) {
    assert.equal(row.status, 'NOT_EVALUATED'); assert.equal(row.strength, 'NONE');
    assert.ok(row.facts.qualificationIssues.includes('DENOVO_DISCOVERY_ALLELE_BINDING_MISMATCH'));
    assert.ok(row.html.includes('DENOVO_DISCOVERY_ALLELE_BINDING_MISMATCH'), 'wrong target is shown as binding conflict');
}
for (const { run, before } of loaded.values()) assert.equal(JSON.stringify(run), before);
for (const [filename, pin] of pins) assert.equal(sha(fs.readFileSync(filename)), pin);
assert.deepEqual(fs.readFileSync(indexPath), indexBytes);
assert.equal(totalRows, 58); assert.equal(deNovoRows, 18); assert.equal(renderedRows, 18);
const report = { schemaVersion: 'a9-denovo-current-controller-view-regression-v1', status: 'PASS',
    sourceCommitReportedByCommander: '2efc68c7', indexSha256: sha(indexBytes), sourceFilesPinnedUnchanged: pins.size,
    selectedControllerCases: selected.length, actualAdapterRows: totalRows, deNovoRows, renderedRows, adoptedRows, sharedRows,
    unexpectedDisplayIssues: 0, fixtureScope: 'ACTUAL_CONTROLLER_OUTPUTS_WITH_TEST_ONLY_SYNTHETIC_SOURCES_NOT_CLINICAL',
    verificationLayer: 'SAVED_PUBLIC_JSON_CURRENT_FRONTEND_ADAPTER_AND_REACT_SSR',
    newJavaRuns: 0, newHttpCalls: 0, newModelCalls: 0, newAnalyses: 0, scientificQualificationGranted: false, rows: reportRows };
if (process.argv[4]) {
    const reportDirectory = path.resolve(process.argv[4]); fs.mkdirSync(reportDirectory, { recursive: true });
    fs.writeFileSync(path.join(reportDirectory, 'current-controller-view-replay.json'), JSON.stringify(report, null, 2) + '\n');
}
console.log(JSON.stringify({ ...report, rows: undefined }));
