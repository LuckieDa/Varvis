// Actual pinned Java/HTTP/Python/H2 outputs are read only. New controls below
// are synthetic display checks; this script performs no analysis or recovery.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { createRequire } = require('node:module');
const contractPath = process.argv[2];
if (!contractPath) throw Error('Supply pp1-trusted-contributions/public-contract.json.');
const directory = path.dirname(path.resolve(contractPath)), project = path.resolve(__dirname, '..');
const dependencyRoot = path.resolve(process.argv[3] || path.resolve(project, '../../../../../varvis-client-pro'));
const dependencies = createRequire(path.join(dependencyRoot, 'package.json'));
const built = dependencies('esbuild').buildSync({ stdin: { contents: `
import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import Summary from './src/pages/varvis/ChromosomeVisualization/components/EvidenceDecisionSummary.jsx';
export {pp1ContributionPreparationView,pp1LiteratureCriterionValid,evidenceOutcome,criterionLabel} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
export {pp1FormalResultDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgPp1Formal.js';
export {backgroundEvidenceCards,resultPresentation} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js';
export {decisionDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgDecisionDetails.js';
export const render=entry=>renderToStaticMarkup(<Summary entry={entry}/>);
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', write: false,
    nodePaths: [path.join(dependencyRoot, 'node_modules')], define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' }).outputFiles[0].text;
const compiled = { exports: {} };
new Function('require', 'module', 'exports', built)(dependencies, compiled, compiled.exports);
const api = compiled.exports, hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const copy = value => JSON.parse(JSON.stringify(value));
const pins = new Map(), sourceDocuments = new Map();
const read = filename => {
    const raw = fs.readFileSync(filename); pins.set(filename, hash(raw));
    return JSON.parse(raw);
};
const contract = read(path.resolve(contractPath));
const manifest = read(path.join(directory, 'artifact-manifest.json'));
const receipt = read(path.join(directory, 'receipt.json'));
assert.equal(contract.schemaVersion, 'pp1-trusted-current-contribution-public-contract-v1');
assert.equal(contract.commit, '5c0f2de5db8d6c88537862a55215bf1ea63fbbe8');
assert.equal(manifest.commit, contract.commit); assert.equal(receipt.commit, contract.commit);
for (const name of ['public-contract.json', 'receipt.json']) {
    const expected = manifest.artifacts.find(file => file.path === name), filename = path.join(directory, name);
    assert.equal(pins.get(filename), expected.sha256); assert.equal(fs.statSync(filename).size, expected.bytes);
}
assert.equal(contract.productionSource.configured, false);
assert.equal(contract.productionSource.defaultPp4Points, null);
assert.equal(contract.samples.length, 12);
for (const file of contract.samples) {
    const filename = path.resolve(file.path);
    assert.ok(filename.startsWith(directory + path.sep)); assert.ok(file.bytes < 1024 * 1024);
    const document = read(filename);
    assert.equal(fs.statSync(filename).size, file.bytes); assert.equal(pins.get(filename), file.sha256);
    sourceDocuments.set(filename, { document, before: JSON.stringify(document) });
}
function freeze(value) {
    if (value && typeof value === 'object' && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); }
    return value;
}
const rowOf = run => run.candidates[0].attempts[0].localBatch.criteria.find(row => row.criterionId === 'ACMG_AMP_2015:PP1');
const entryOf = run => api.backgroundEvidenceCards(run.candidates[0], run.enabledCriteria, run, 0)
    .find(card => card.id === 'ACMG_AMP_2015:PP1').entries[0];
let checks = 0;
const check = (label, test) => { test(); checks++; console.log(`PASS ${label}`); };
check('saved PP1 accepts an exact published PP4 exclusion and rejects changed proof', () => {
    const scope = { geneSymbol: 'GENE', diseaseId: 'MONDO:1234567', inheritance: 'AD' };
    const allele = { assembly: 'GRCh38', contig: '1', position: 1234, reference: 'A', alternate: 'G' };
    const proof = { schemaVersion: 'pp1-published-pp4-exclusion-v1', criterion: 'ACMG_AMP_2015:PP4',
        status: 'NOT_APPLICABLE', specificationKey: 'GN123@1.0', clausePointer: '/ld/CriteriaCode/0',
        clauseSha256: 'a'.repeat(64), scope };
    const selectionKey = 'b'.repeat(64), reasonCode = 'PP1_CURRENT_CONTRIBUTION_PUBLISHED_PP4_NOT_APPLICABLE';
    const facts = { schemaVersion: 'pp1-selected-facts-v1', clinicalApproval: false, sourceCoverage: [], qualifications: [],
        contributionPreparation: { capability: 'TRUSTED_CURRENT_CONTRIBUTION_SNAPSHOT_V1', status: 'COMPLETE',
            ledgerStatus: 'KNOWN_EMPTY', reasonCode, requirement: 'Rule', finding: 'Excluded', gap: '',
            impact: 'No shared points', nextStep: 'Evaluate PP1', publishedPp4Exclusion: structuredClone(proof) },
        evaluation: { schemaVersion: 'pp1-segregation-evaluation-v1', status: 'NOT_EVALUATED', businessPublishable: false,
            strength: null, awardedPP1Points: 0, events: [], needs: [], target: { ...scope, selectionKey, allele },
            context: { status: 'QUALIFIED', qualificationBasis: 'ORIGINAL_PUBLISHED_EXPERT_SPECIFICATION',
                policy: { ruleClause: 'GN123@1.0/ld/CriteriaCode/1', pp4Exclusion: structuredClone(proof) },
                sourceEvidence: { specification: { rawSha256: 'c'.repeat(64) } } },
            ledger: { status: 'KNOWN_EMPTY', reasonCode, pp4Points: 0, scope, selectionKey, allele,
                eventIds: [], exclusiveFactIds: [], publishedPp4Exclusion: structuredClone(proof) } } };
    const row = { criterionId: 'ACMG_AMP_2015:PP1', status: 'NOT_EVALUATED', appliedStrength: 'NONE',
        reasonCode: 'PP1_LITERATURE_REQUIREMENTS_UNRESOLVED', reason: 'Source review pending', details: { pp1Facts: facts } };
    const context = { gene: { symbol: scope.geneSymbol }, disease: { mondoId: scope.diseaseId },
        moi: 'AUTOSOMAL_DOMINANT', variant: { evidenceQueryAllele: allele } };
    assert.equal(api.pp1LiteratureCriterionValid(row, undefined, context), true);
    assert.equal(api.pp1LiteratureCriterionValid(row, undefined, { ...context, disease: { mondoId: 'MONDO:7654321' } }), false);
    for (const change of [f => { delete f.evaluation.context.policy.pp4Exclusion; },
        f => { f.contributionPreparation.publishedPp4Exclusion.clauseSha256 = 'd'.repeat(64); },
        f => { f.evaluation.ledger.pp4Points = 1; }]) {
        const modified = structuredClone(row);
        change(modified.details.pp1Facts);
        assert.equal(api.pp1LiteratureCriterionValid(modified, undefined, context), false);
    }
});


async function main() {
    if (process.argv[6]) {
        const run = read(path.resolve(process.argv[6]));
        const row = rowOf(run), attempt = run.candidates[0].attempts[0];
        check('saved published-meiosis workbench result displays the verified PP1 strength', () => {
            assert.equal(api.pp1LiteratureCriterionValid(row,run.executionProfile,attempt.context),true);
            assert.equal(entryOf(run).displayIssue,false);
            for (const alter of [e => { e.meiosisAssessment.informativeMeioses += 1; },
                e => { e.events[0].memberKey = e.events[1].memberKey; },
                e => { e.meiosisAssessment.thresholds.SUPPORTING.minimum -= 1; }]) {
                const changed = copy(row);
                alter(changed.details.pp1Facts.evaluation);
                assert.equal(api.pp1LiteratureCriterionValid(changed,run.executionProfile,attempt.context),false);
            }
        });
    }
    const runReports = [], runs = [];
    for (const [filename, source] of sourceDocuments) {
        if (!/(?:saved|all)\.json$/.test(filename)) continue;
        const run = freeze(source.document), row = rowOf(run), facts = row.details.pp1Facts, original = JSON.stringify(facts.evaluation);
        runs.push(run);
        check(`${path.relative(directory, filename)} actual PP1 system contribution gap is visible without adoption`, () => {
            const entry = entryOf(run), view = api.decisionDetails(entry), preparation = facts.contributionPreparation;
            assert.equal(entry.displayIssue, false); assert.equal(entry.savedCriterion, row);
            assert.equal(row.status, 'NOT_EVALUATED'); assert.equal(row.appliedStrength, 'NONE');
            assert.equal(row.reasonCode, 'PP1_LITERATURE_REQUIREMENTS_UNRESOLVED');
            assert.equal(facts.evaluation.method, 'VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1');
            assert.equal(facts.evaluation.ledger.status, 'UNKNOWN'); assert.equal(facts.evaluation.ledger.pp4Points, null);
            const presentation = api.resultPresentation(entry);
            assert.equal(presentation.mainKind, 'NO_CONCLUSION'); assert.equal(presentation.outcome.kind, 'CONFIGURATION');
            assert.equal(presentation.reasonCode, row.reasonCode); assert.equal(presentation.adoption.kind, 'NOT_ADOPTED');
            const issue = view.issues[0];
            assert.equal(issue.kind, 'SYSTEM_INTEGRATION'); assert.equal(issue.contributionPreparation, preparation);
            assert.equal(issue.detail.pp4Points, null); assert.equal(issue.codes[0], preparation.reasonCode);
            assert.deepEqual(view.original, { criterionId: row.criterionId, status: row.status, strength: row.appliedStrength, reasonCode: row.reasonCode });
            const html = api.render(entry);
            for (const name of ['requirement', 'finding', 'gap', 'impact', 'nextStep']) assert.ok(html.includes(preparation[name]), name);
            assert.ok(html.includes(preparation.reasonCode));
            assert.ok(html.indexOf('可信采用清单未就绪') < html.indexOf('原保存状态、强度及原因'));
            assert.ok(html.includes('不作为患者资料缺失') && html.includes('未选择 PP4 也不默认其贡献为零'));
            assert.ok(!html.includes('<button'), 'the source-integration message has no fake continuation action');
            assert.ok(!/PP4 已用 0/.test(JSON.stringify(entry.details)));
            assert.equal(JSON.stringify(facts.evaluation), original);
            const mirrored = run.localBatch.results[0].localBatch.criteria.find(value => value.criterionId === row.criterionId);
            assert.deepEqual(mirrored, row, 'mirrored stored scientific result remains exact');
            runReports.push({ file: path.relative(directory, filename), status: row.status, strength: row.appliedStrength,
                originalReason: row.reasonCode, systemReason: preparation.reasonCode, captureStatus: preparation.status,
                ledgerStatus: facts.evaluation.ledger.status, pp4Points: facts.evaluation.ledger.pp4Points,
                pp4Selected: run.enabledCriteria.includes('ACMG_AMP_2015:PP4'), mainKind: presentation.mainKind,
                processKind: presentation.outcome.kind, sourceFactsSha256: hash(JSON.stringify({ qualifications: facts.qualifications,
                    sourceCoverage: facts.sourceCoverage, evaluation: facts.evaluation })), ssrSha256: hash(html) });
        });
        check(`${path.relative(directory, filename)} old absent contribution field remains readable and unknown`, () => {
            const old = copy(run); delete rowOf(old).details.pp1Facts.contributionPreparation;
            const entry = entryOf(old), view = api.decisionDetails(entry);
            assert.equal(entry.displayIssue, false); assert.equal(api.pp1ContributionPreparationView(rowOf(old)), null);
            assert.equal(api.resultPresentation(entry).mainKind, 'NO_CONCLUSION');
            assert.ok(!view.issues.some(issue => issue.kind === 'SYSTEM_INTEGRATION'));
            assert.equal(rowOf(old).details.pp1Facts.evaluation.ledger.pp4Points, null);
            assert.equal(JSON.stringify(rowOf(old).details.pp1Facts.evaluation), original);
        });
    }
    assert.equal(runs.length, 6);
    const base = runs[0];
    check('raw fabricated PP1 properties cannot borrow validated system classification', () => {
        const evaluation = { criterionId: 'ACMG_AMP_2015:PP1', appliedStrength: 'NONE', evaluationResult: { status: 'NOT_EVALUATED' },
            pp1ContributionPreparation: { capability: 'TRUSTED_CURRENT_CONTRIBUTION_SNAPSHOT_V1', ledgerStatus: 'UNKNOWN' } };
        assert.notEqual(api.evidenceOutcome(evaluation).kind, 'CONFIGURATION');
        assert.notEqual(api.criterionLabel(evaluation), '可信采用清单未就绪');
        assert.notEqual(api.resultPresentation({ evaluation }).outcome.kind, 'CONFIGURATION');
    });
    check('JSON-cloned or manually reconstructed projections do not inherit the private decoder marker', () => {
        const entry = entryOf(base);
        assert.equal(api.resultPresentation(entry).outcome.kind, 'CONFIGURATION');
        assert.equal(Object.hasOwn(entry.evaluation, 'pp1ContributionPreparation'), false);
        for (const evaluation of [copy(entry.evaluation), { ...entry.evaluation }]) {
            assert.notEqual(api.evidenceOutcome(evaluation).kind, 'CONFIGURATION');
            assert.notEqual(api.criterionLabel(evaluation), '可信采用清单未就绪');
            assert.notEqual(api.resultPresentation({ ...entry, evaluation }).outcome.kind, 'CONFIGURATION');
        }
    });
    for (const [label, change] of [
        ['projection reason', (run, entry) => { entry.evaluation.evaluationResult.reasonCode = 'PP1_OTHER_REQUIRED'; }],
        ['projection context', (run, entry) => { entry.evaluation.contextId = 'different-context'; }],
        ['saved source reason', run => { rowOf(run).reasonCode = 'PP1_OTHER_REQUIRED'; }],
        ['selected source context', run => { run.candidates[0].attempts[0].context.disease.mondoId = 'MONDO:9999999'; }],
    ]) check(`private marker cannot survive changed ${label}`, () => {
        const run = copy(base), entry = entryOf(run);
        assert.equal(api.resultPresentation(entry).outcome.kind, 'CONFIGURATION');
        change(run, entry);
        assert.notEqual(api.evidenceOutcome(entry.evaluation).kind, 'CONFIGURATION');
        assert.notEqual(api.criterionLabel(entry.evaluation), '可信采用清单未就绪');
        assert.notEqual(api.resultPresentation(entry).outcome.kind, 'CONFIGURATION');
    });
    const tamper = [
        ['unknown cannot become zero', (run, row, facts) => { facts.evaluation.ledger.pp4Points = 0; }],
        ['ledger scope cannot change', (run, row, facts) => { facts.evaluation.ledger.scope.diseaseId = 'MONDO:9999999'; }],
        ['ledger allele cannot change', (run, row, facts) => { facts.evaluation.ledger.allele.position += 1; }],
        ['current context cannot change', run => { run.candidates[0].attempts[0].context.disease.mondoId = 'MONDO:9999999'; }],
        ['capture status is not readiness', (run, row, facts) => { facts.contributionPreparation.status = 'READY'; }],
        ['capture without pins is not trusted', (run, row, facts) => { facts.contributionPreparation.status = 'CAPTURED'; }],
        ['reason must match saved ledger', (run, row, facts) => { facts.contributionPreparation.reasonCode = 'PP1_CONTRIBUTION_OTHER'; }],
        ['unsupported capability is technical mismatch', (run, row, facts) => { facts.contributionPreparation.capability = 'CLIENT_DECLARED'; }],
        ['incomplete paragraph is not promoted', (run, row, facts) => { delete facts.contributionPreparation.finding; }],
        ['unknown cannot declare known inventory', (run, row, facts) => { facts.contributionPreparation.ledgerStatus = 'KNOWN'; }],
        ['unknown cannot invent captured digest', (run, row, facts) => { facts.contributionPreparation.snapshotSha256 = 'bad'; }],
        ['NOT_EVALUATED cannot become MET', (run, row, facts) => { row.status = facts.evaluation.status = 'MET'; row.appliedStrength = 'SUPPORTING'; }],
        ['UNKNOWN cannot be changed into NOT_MET', (run, row, facts) => { row.status = facts.evaluation.status = 'NOT_MET'; }],
    ];
    for (const [label, mutate] of tamper) check(`synthetic tamper: ${label}`, () => {
        const run = copy(base), row = rowOf(run), facts = row.details.pp1Facts;
        mutate(run, row, facts);
        const before = JSON.stringify(run), entry = entryOf(run), view = api.decisionDetails(entry);
        assert.equal(entry.displayIssue, true); assert.equal(api.resultPresentation(entry).adoption.kind, 'DISPLAY_UNVERIFIED');
        assert.ok(!view.issues.some(issue => issue.kind === 'SYSTEM_INTEGRATION'));
        assert.equal(JSON.stringify(run), before);
    });
    check('synthetic CAPTURED plus UNKNOWN remains a blocking system gap, never adoption', () => {
        const run = copy(base), preparation = rowOf(run).details.pp1Facts.contributionPreparation;
        Object.assign(preparation, { status: 'CAPTURED', snapshotSha256: 'a'.repeat(64), sourceIdentitySha256: 'b'.repeat(64) });
        const entry = entryOf(run), view = api.decisionDetails(entry);
        assert.equal(entry.displayIssue, false); assert.equal(api.resultPresentation(entry).outcome.kind, 'CONFIGURATION');
        assert.equal(api.resultPresentation(entry).mainKind, 'NO_CONCLUSION');
        assert.equal(view.issues[0].contributionPreparation.status, 'CAPTURED');
        assert.equal(view.issues[0].detail.pp4Points, null); assert.ok(api.render(entry).includes('不表示账本已核定或证据已采用'));
    });
    check('PP1-only metadata is never borrowed by a different criterion', () => {
        const row = copy(rowOf(base)); row.criterionId = 'ACMG_AMP_2015:PM4';
        assert.equal(api.pp1ContributionPreparationView(row).valid, false);
        const entry = { savedCriterion: row, evaluation: { criterionId: row.criterionId, appliedStrength: 'NONE',
            evaluationResult: { status: row.status, reasonCode: row.reasonCode } } };
        assert.ok(!api.decisionDetails(entry).issues.some(issue => issue.kind === 'SYSTEM_INTEGRATION'));
        assert.notEqual(api.resultPresentation(entry).outcome.kind, 'CONFIGURATION');
    });
    // Archived normal Python results remain distinct from new Java business
    // outputs. Their historical source ledger is not a new production inventory.
    const historyDirectory = path.resolve(process.argv[5] || path.resolve(directory, '../../dispatch/a9/round3/python-normal-fixtures'));
    const historyReceipt = read(path.join(historyDirectory, 'receipt.json')), historyReports = [];
    for (const record of historyReceipt.records) check(`archived normal Python ${record.name}: absent new field preserves exact core BF and status`, () => {
        const filename = path.join(historyDirectory, `${record.name}.json`), document = read(filename);
        assert.equal(pins.get(filename), record.sha256);
        const original = JSON.stringify(document), e = document.evaluation;
        assert.equal(e.status, record.status); assert.deepEqual(e.bayesFactor, record.bayesFactor);
        assert.equal(e.awardedPP1Points, record.points);
        const row = { criterionId: 'ACMG_AMP_2015:PP1', status: e.status, appliedStrength: e.strength || 'NONE',
            reasonCode: e.status === 'MET' ? 'PP1_QUALIFIED_SEGREGATION_THRESHOLD_MET' : 'PP1_QUALIFIED_SEGREGATION_BELOW_THRESHOLD',
            reason: 'Read-only projection of archived normal Python core result; not a new Java business adoption',
            details: { pp1Facts: { schemaVersion: 'pp1-selected-facts-v1', clinicalApproval: false, evaluation: e,
                sourceCoverage: document.sourceCoverage, qualifications: document.qualifications } } };
        const target = e.target, context = { gene: { symbol: target.geneSymbol }, disease: { mondoId: target.diseaseId },
            moi: { AD: 'AUTOSOMAL_DOMINANT', AR: 'AUTOSOMAL_RECESSIVE', XLR: 'X_LINKED_RECESSIVE' }[target.inheritance],
            variant: { evidenceQueryAllele: copy(target.allele) } };
        assert.equal(api.pp1LiteratureCriterionValid(row, undefined, context), true); assert.equal(api.pp1ContributionPreparationView(row), null);
        const entry = { savedCriterion: row, displayIssue: false, evaluation: { criterionId: row.criterionId,
            appliedStrength: row.appliedStrength, evaluationResult: { status: row.status, reasonCode: row.reasonCode } } };
        assert.equal(api.resultPresentation(entry).mainKind, e.status === 'MET' ? 'ADOPTED_MET' : 'NOT_MET');
        assert.ok(!api.decisionDetails(entry).issues.some(issue => issue.kind === 'SYSTEM_INTEGRATION'));
        assert.ok(api.pp1FormalResultDetails(row).some(([label]) => label.includes('完整似然')));
        assert.ok(api.render(entry).includes(e.status));
        assert.equal(JSON.stringify(document), original);
        historyReports.push({ name: record.name, layer: historyReceipt.layer, status: e.status, strength: row.appliedStrength,
            bayesFactor: e.bayesFactor, originalSha256: record.sha256, newContributionFieldPresent: false });
    });
    assert.equal(historyReports.length, 3);
    for (const [filename, source] of sourceDocuments) assert.equal(JSON.stringify(source.document), source.before, filename);
    for (const [filename, pin] of pins) assert.equal(hash(fs.readFileSync(filename)), pin, filename);
    const report = { schemaVersion: 'pp1-trusted-contribution-frontend-regression-v1', status: 'PASS', checks,
        sourceCommit: contract.commit, actualBusinessRunsReplayed: runs.length, sourceArtifactsPinnedUnchanged: sourceDocuments.size,
        sourceMetadataPinnedUnchanged: 3, artifactBreakdown: { publicSavedAndAllRunJson: 6, sourceFixtures: 3, separateJvmReceipts: 3 },
        actualLayer: 'PINNED_NORMAL_JAVA_HTTP_PYTHON_H2_OUTPUTS_WITH_SYNTHETIC_SOURCE_SSR_REPLAY', archivedCoreResults: historyReports,
        syntheticControls: ['malformed fields and identity/status/reason changes', 'CAPTURED with UNKNOWN ledger',
            'different criterion isolation', 'raw/cloned/mutated projections cannot forge private selected-entry provenance'],
        newJavaRuns: 0, newHttpCalls: 0, newModelCalls: 0, newAnalyses: 0, scientificQualificationGranted: false, runs: runReports };
    if (process.argv[4]) {
        const reportDirectory = path.resolve(process.argv[4]); fs.mkdirSync(reportDirectory, { recursive: true });
        fs.writeFileSync(path.join(reportDirectory, 'frontend-replay.json'), JSON.stringify(report, null, 2) + '\n');
    }
    console.log(JSON.stringify({ ...report, runs: undefined }));
}
main().catch(error => { console.error(error); process.exitCode = 1; });
