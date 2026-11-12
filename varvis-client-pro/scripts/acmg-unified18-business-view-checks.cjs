// Current frontend replay of immutable, centrally produced TEST_ONLY unified-18 products.
// No scientific execution, Java, HTTP, browser, source retrieval or model calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { createRequire, Module } = require('node:module');
const [contractPath, outputPath, sharedPath] = process.argv.slice(2);
if (!contractPath || !outputPath) throw Error('Supply public-contract.json and assigned private output');
const project = path.resolve(__dirname, '..'), shared = path.resolve(sharedPath || project), output = path.resolve(outputPath);
if (!output.replaceAll('\\', '/').endsWith('/dispatch/a9/round4/unified18-regression')) throw Error('Use assigned private unified18-regression output');
const dependencies = createRequire(path.join(shared, 'package.json'));
const bundle = dependencies('esbuild').buildSync({
    stdin: { contents: `export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';export {decisionDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgDecisionDetails';export {default as Matrix} from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';export {CandidateOutcomeSummary} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', packages: 'external', write: false, loader: { '.js': 'jsx', '.less': 'empty' },
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'silent',
});
const loaded = new Module(path.join(project, 'scripts', 'acmg-unified18-business-view-bundle.cjs'));
loaded.filename = loaded.id; loaded.paths = [path.join(shared, 'node_modules')];
loaded._compile(bundle.outputFiles[0].text, loaded.filename);
const api = loaded.exports, React = dependencies('react'), { renderToStaticMarkup } = dependencies('react-dom/server');
const axios = dependencies('axios'); let httpCalls = 0;
axios.defaults.adapter = async () => { httpCalls++; throw Error('No HTTP permitted in saved-product frontend replay'); };
const digest = value => createHash('sha256').update(value).digest('hex');
const contractBytes = fs.readFileSync(contractPath), contract = JSON.parse(contractBytes.toString('utf8').replace(/^\uFEFF/, ''));
const root = path.dirname(path.resolve(contractPath)), reports = [], checks = [], failures = [], routingSamples = {}, findings = [];
const totals = { products: 0, all18Products: 0, candidates: 0, backgrounds: 0, rows: 0, routingRows: 0, targetedNewScopeRows: 0, displayIssues: 0, main: {} };
const add = (counts, key) => { counts[key] = (counts[key] || 0) + 1; };
const freeze = value => { if (value && typeof value === 'object' && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); } };
function check(name, action) {
    try { action(); checks.push({ name, status: 'PASS' }); }
    catch (error) { const item = { name, status: 'FAIL', error: error.message }; checks.push(item); failures.push(item); }
}
check('contract source and product list identity', () => {
    assert.equal(contract.schemaVersion, 'pvs1-unified18-product-contract-v1');
    assert.equal(contract.sourceHead, '819fdf8e765446acc7c7c6f3bb5a6f6847326a8a');
    assert.equal(contract.actualProductFiles.length, 19);
});
for (const record of contract.actualProductFiles) {
    const filename = path.resolve(root, record.file);
    if (!filename.startsWith(root + path.sep)) throw Error('Product path escaped contract directory');
    const bytes = fs.readFileSync(filename), run = JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
    const before = digest(JSON.stringify(run)); freeze(run);
    const product = { file: record.file, inputSha256: digest(bytes), bytes: bytes.length, runId: run.runId,
        enabledCriteria: run.enabledCriteria, backgrounds: [], main: {}, originalStatus: {}, displayIssues: [], routing: [], pvs1: [] };
    totals.products++; totals.candidates += run.candidates.length;
    if (run.enabledCriteria.length === 18) totals.all18Products++;
    check(`${record.file}: raw input hash and contract identities`, () => {
        assert.equal(product.inputSha256, record.rawSha256); assert.equal(bytes.length, record.bytes);
        assert.equal(run.analysisId, record.analysisId); assert.equal(run.runId, record.runId);
        assert.deepEqual(run.enabledCriteria, record.enabledCriteria);
    });
    for (const candidate of run.candidates) for (const background of api.savedBackgrounds(candidate)) {
        const key = `${record.file}/${candidate.candidateId}/${background.index}`;
        const cards = api.backgroundEvidenceCards(candidate, run.enabledCriteria, run, background.index);
        const rows = cards.flatMap(card => card.entries.map(entry => ({ card, entry }))), counts = api.presentationCounts(rows);
        totals.backgrounds++; totals.rows += rows.length;
        check(`${key}: scope, count and original-reference invariance`, () => {
            assert.equal(cards.length, run.enabledCriteria.length); assert.equal(rows.length, run.enabledCriteria.length);
            assert.equal(counts.reduce((sum, item) => sum + item.count, 0), rows.length);
            assert.equal(counts.length, 4);
            for (const { card, entry } of rows) {
                if (entry.hypothesisId) assert.equal(entry.hypothesisId, background.hypothesisId);
                if (entry.savedCriterion) assert.strictEqual(entry.savedCriterion,
                    background.attempt.localBatch.criteria.find(value => value.criterionId === card.id));
            }
        });
        check(`${key}: actual summary/matrix SSR four statistics agree`, () => {
            const props = { run, candidate, backgroundIndex: background.index };
            const summary = renderToStaticMarkup(React.createElement(api.CandidateOutcomeSummary, { ...props, criteria: run.enabledCriteria }));
            const matrix = renderToStaticMarkup(React.createElement(api.Matrix, { ...props, enabledCriteria: run.enabledCriteria, showBackgroundSelector: false }));
            assert.ok(summary.includes(`四类合计 ${rows.length} 项证据结果`)); assert.ok(matrix.includes(`四类合计 ${rows.length} 项证据结果`));
            for (const item of counts) { const text = `${item.count} 项${item.label}`; assert.ok(summary.includes(text)); assert.ok(matrix.includes(text)); }
        });
        product.backgrounds.push({ candidateId: candidate.candidateId, index: background.index, hypothesisId: background.hypothesisId, rows: rows.length,
            counts: counts.map(({ kind, count }) => ({ kind, count })) });
        for (const { card, entry } of rows) {
            const presented = api.resultPresentation(entry), details = api.decisionDetails(entry), saved = entry.savedCriterion;
            add(totals.main, presented.mainKind); add(product.main, presented.mainKind);
            add(product.originalStatus, saved?.status || entry.evaluation?.evaluationResult?.status || 'NO_EVALUATION');
            const identity = { candidateId: candidate.candidateId, backgroundIndex: background.index, criterion: card.code };
            if (entry.displayIssue) { totals.displayIssues++; const item = { ...identity, originalStatus: saved?.status, reasonCode: saved?.reasonCode,
                summary: entry.summary, presentationIssues: entry.presentationIssues || [], main: presented.mainKind, outcome: presented.outcome.kind };
                product.displayIssues.push(item); findings.push({ file: record.file, ...item }); }
            if (saved && !entry.displayIssue) check(`${key}/${card.code}: projected saved values unchanged`, () => {
                assert.equal(entry.evaluation.evaluationResult.status, saved.status); assert.equal(entry.evaluation.evaluationResult.reasonCode, saved.reasonCode);
                assert.equal(entry.evaluation.appliedStrength, saved.appliedStrength);
                assert.equal(details.original.status, saved.status); assert.equal(details.original.reasonCode, saved.reasonCode); assert.equal(details.original.strength, saved.appliedStrength);
            });
            const factsKey = card.code === 'PM4' ? 'proteinLengthFacts' : card.code === 'BP3' ? 'bp3Facts' : null;
            const scopeFacts = factsKey && saved?.details?.[factsKey];
            if (scopeFacts?.scope === 'PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE' && saved.status === 'NOT_EVALUATED' && saved.appliedStrength === 'NONE') {
                totals.targetedNewScopeRows++;
                check(`${key}/${card.code}: supported verified-small-allele pending facts are readable without changing original result`, () => {
                    assert.equal(entry.displayIssue, false, 'valid pending new-scope facts must not be rejected as a broken saved result');
                    assert.equal(presented.mainKind, 'NO_CONCLUSION', 'pending source must not become scored/adopted');
                    assert.strictEqual(entry.savedCriterion, saved);
                    assert.equal(entry.evaluation.evaluationResult.status, saved.status);
                    assert.equal(entry.evaluation.evaluationResult.reasonCode, saved.reasonCode);
                    assert.equal(entry.evaluation.appliedStrength, 'NONE');
                });
                if (record.file === 'products/ESCAPE/all-product.json' && background.index === 0 && candidate === run.candidates[0]) {
                    const mutations = [
                        ['unknown scope', value => { value.details[factsKey].scope = 'PROJECT_LOCAL_UNRECOGNIZED_SCOPE'; }],
                        ['clinical approval flag', value => { value.details[factsKey].clinicalValidationCompleted = true; }],
                        ['unknown policy version', value => { value.details[factsKey].policyVersion = '999.0.0'; }],
                        ['unqualified MET promotion', value => { value.status = 'MET'; value.appliedStrength = 'MODERATE'; }],
                    ];
                    for (const [label, mutate] of mutations) check(`${key}/${card.code}: synthetic ${label} remains rejected`, () => {
                        const altered = structuredClone(saved); mutate(altered);
                        const attempt = { ...background.attempt, localBatch: { ...background.attempt.localBatch,
                            criteria: background.attempt.localBatch.criteria.map(value => value === saved ? altered : value) } };
                        const changedCandidate = { ...candidate, attempts: candidate.attempts.map((value, index) => index === background.index ? attempt : value) };
                        const changed = api.backgroundEvidenceCards(changedCandidate, run.enabledCriteria, run, background.index).find(value => value.id === card.id).entries[0];
                        assert.equal(changed.displayIssue, true, 'synthetic invalid source facts must not use the pending-scope adapter');
                        assert.notEqual(api.resultPresentation(changed).mainKind, 'ADOPTED_MET');
                    });
                }
            }
            const routing = saved?.details?.applicabilityRouting;
            if (routing?.skipSpecializedAcquisition === true) {
                totals.routingRows++;
                const item = { ...identity, originalStatus: saved.status, originalReason: saved.reasonCode,
                    routing, projectedApplicability: entry.evaluation?.criterionApplicability, main: presented.mainKind, outcome: presented.outcome.kind,
                    detailIssues: details.issues, detailComparisons: details.comparisons };
                product.routing.push(item); routingSamples[saved.reasonCode] ||= item;
                check(`${key}/${card.code}: actual skip routing is not patient-data insufficiency`, () => {
                    assert.equal(saved.status, 'NOT_EVALUATED'); assert.equal(saved.appliedStrength, 'NONE');
                    assert.equal(entry.displayIssue, false);
                    if (routing.status === 'NOT_APPLICABLE') { assert.equal(presented.mainKind, 'NOT_APPLICABLE'); assert.equal(presented.outcome.kind, 'NOT_APPLICABLE'); }
                    if (routing.status === 'SOFTWARE_UNSUPPORTED') { assert.equal(presented.mainKind, 'NO_CONCLUSION'); assert.equal(presented.outcome.kind, 'UNSUPPORTED'); }
                    assert.ok(!details.issues.some(issue => issue.kind === 'MISSING'));
                });
            }
            if (card.code === 'PVS1') product.pvs1.push({ ...identity, originalStatus: saved?.status, strength: saved?.appliedStrength,
                originalReason: saved?.reasonCode, main: presented.mainKind, outcome: presented.outcome.kind, displayIssue: !!entry.displayIssue });
        }
    }
    check(`${record.file}: raw JSON and byte hashes unchanged after all adapters/SSR`, () => {
        assert.equal(digest(JSON.stringify(run)), before); assert.equal(digest(fs.readFileSync(filename)), record.rawSha256);
    });
    reports.push(product);
}
check('exactly three actual all-18 products replayed without HTTP', () => { assert.equal(totals.all18Products, 3); assert.equal(totals.targetedNewScopeRows, 24); assert.equal(httpCalls, 0); });
const report = { schemaVersion: 'acmg-unified18-business-view-checks-v1', contractPath: path.resolve(contractPath), contractSha256: digest(contractBytes),
    sourceHead: contract.sourceHead, dataClass: contract.dataClass, displayCompatibility: findings.length ? 'PARTIAL_DISPLAY_REJECTIONS_REMAIN' : 'NO_DISPLAY_REJECTIONS_IN_THIS_INPUT_SET', reviewStatus: 'Commander-reported central acceptance: 13 independent checks; this script does not rerun Java or replace that review',
    centralReview: { source: 'COMMANDER_REPORTED', acceptedChecks: 13, rerunByThisFrontendReplay: false },
    layer: 'Actual product JSON generated by central Java entry with TEST_ONLY synthetic scientific inputs; current frontend adapters and actual component SSR',
    totals, checks: { total: checks.length, passed: checks.length - failures.length, failures }, httpCalls, routingSamples, findings, products: reports,
    limitations: ['No independent scientific threshold recomputation', 'No real-user analysis, live HTTP, browser interactions, saved history/export endpoint or actual download verification'] };
fs.mkdirSync(output, { recursive: true }); fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ totals, checks: report.checks, routingSamples: Object.fromEntries(Object.entries(routingSamples).map(([code, item]) => [code, { main: item.main, outcome: item.outcome, originalStatus: item.originalStatus, detailIssues: item.detailIssues }])), findings,
    summary: path.join(output, 'summary.json') }, null, 2));
if (failures.length) process.exitCode = 1;
