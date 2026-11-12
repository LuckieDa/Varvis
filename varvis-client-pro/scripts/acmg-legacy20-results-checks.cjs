// Archived product-input regression. No analysis, Java, HTTP, public source or model calls.
// Usage: node acmg-legacy20-results-checks.cjs result.json expected-results.json private-output [shared-client]
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { createRequire, Module } = require('node:module');
const [resultPath, expectedPath, outputPath, sharedPath] = process.argv.slice(2);
if (!resultPath || !expectedPath || !outputPath) throw Error('Supply archived result, conditional expectations and private output');
const project = path.resolve(__dirname, '..');
const shared = path.resolve(sharedPath || project), output = path.resolve(outputPath);
if (!output.replaceAll('\\', '/').endsWith('/dispatch/a9/round4/legacy20-regression')) throw Error('Use the assigned private legacy20-regression directory');
const dependencies = createRequire(path.join(shared, 'package.json'));
const bundle = dependencies('esbuild').buildSync({
    stdin: { contents: `export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';export {default as Matrix} from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';export {CandidateOutcomeSummary} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';export {default as Workbench} from './src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx';`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', packages: 'external', write: false,
    loader: { '.js': 'jsx', '.less': 'empty' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'silent',
});
const loaded = new Module(path.join(project, 'scripts', 'acmg-legacy20-bundle.cjs'));
loaded.filename = loaded.id; loaded.paths = [path.join(shared, 'node_modules')];
loaded._compile(bundle.outputFiles[0].text, loaded.filename);
const api = loaded.exports, React = dependencies('react'), { renderToStaticMarkup } = dependencies('react-dom/server');
const axios = dependencies('axios');
const sha = value => createHash('sha256').update(value).digest('hex');
const rawBytes = fs.readFileSync(resultPath), expectedBytes = fs.readFileSync(expectedPath);
const run = JSON.parse(rawBytes.toString('utf8').replace(/^\uFEFF/, ''));
const expected = JSON.parse(expectedBytes.toString('utf8').replace(/^\uFEFF/, ''));
const before = sha(JSON.stringify(run)), sourceObjects = new WeakSet();
function freeze(value) {
    if (!value || typeof value !== 'object' || sourceObjects.has(value)) return;
    sourceObjects.add(value); Object.values(value).forEach(freeze); Object.freeze(value);
}
freeze(run); freeze(expected);
const failures = [], checks = [], rowsReport = [], backgrounds = [], sourceStatusCounts = {}, displayedMainCounts = {}, byCriterion = {};
let axiosCalls = 0;
axios.defaults.adapter = async () => { axiosCalls++; throw Error('Archived regression must not call HTTP'); };
function check(name, action) {
    try { action(); checks.push({ name, status: 'PASS' }); }
    catch (error) { const item = { name, status: 'FAIL', error: error.message }; checks.push(item); failures.push(item); }
}
const add = (counter, key) => { counter[key] = (counter[key] || 0) + 1; };
const alleleKey = value => value ? `${String(value.contig ?? value.chrom).replace(/^chr/, '')}:${value.position ?? value.pos}:${value.reference ?? value.ref}:${value.alternate ?? value.alt}` : null;
check('archive has the known 19 selected candidates and 18 enabled criteria', () => {
    assert.equal(run.candidates.length, 19); assert.equal(run.enabledCriteria.length, 18); assert.equal(expected.variants.length, 20);
});
for (const candidate of run.candidates) {
    const originalAttempts = candidate.attempts;
    for (const background of api.savedBackgrounds(candidate)) {
        const key = `${candidate.candidateId}/${background.index}`;
        let cards, rows;
        check(`${key}: requested rows, identities and four-count conservation`, () => {
            cards = api.backgroundEvidenceCards(candidate, run.enabledCriteria, run, background.index);
            rows = cards.flatMap(card => card.entries.map(entry => ({ card, entry })));
            assert.equal(cards.length, 18); assert.equal(rows.length, 18);
            assert.equal(new Set(cards.map(card => card.id)).size, 18);
            assert.strictEqual(candidate.attempts, originalAttempts);
            for (const { card, entry } of rows) {
                if (entry.hypothesisId) assert.equal(entry.hypothesisId, background.hypothesisId);
                if (entry.savedCriterion) {
                    const source = background.attempt.localBatch?.criteria?.find(row => row.criterionId === card.id);
                    assert.strictEqual(entry.savedCriterion, source, 'saved criterion source reference changed');
                    if (!entry.displayIssue && entry.evaluation) {
                        assert.equal(entry.evaluation.evaluationResult.status, source.status);
                        assert.equal(entry.evaluation.evaluationResult.reasonCode, source.reasonCode);
                        assert.equal(entry.evaluation.appliedStrength, source.appliedStrength);
                    }
                }
                if (entry.raw && typeof entry.raw === 'object') assert.ok(sourceObjects.has(entry.raw), 'raw source object replaced');
            }
            const counts = api.presentationCounts(rows);
            assert.equal(counts.length, 4); assert.equal(counts.reduce((sum, value) => sum + value.count, 0), 18);
            const sorted = api.sortResultRows(rows);
            assert.equal(sorted.length, rows.length); assert.ok(sorted.every(row => rows.includes(row)), 'sorting replaced row references');
        });
        if (!rows) continue;
        const counts = api.presentationCounts(rows);
        check(`${key}: actual current-background summary and matrix SSR agree`, () => {
            const props = { candidate, run, backgroundIndex: background.index };
            const summary = renderToStaticMarkup(React.createElement(api.CandidateOutcomeSummary, { ...props, criteria: run.enabledCriteria }));
            const matrix = renderToStaticMarkup(React.createElement(api.Matrix, { ...props, enabledCriteria: run.enabledCriteria, showBackgroundSelector: false }));
            assert.match(summary, /data-testid="candidate-outcome-counts"/);
            assert.match(matrix, /data-testid="evidence-counts"/);
            assert.ok(summary.includes('四类合计 18 项证据结果'));
            assert.ok(matrix.includes('四类合计 18 项证据结果'));
            for (const count of counts) {
                const text = `${count.count} 项${count.label}`;
                assert.ok(summary.includes(text), `summary count mismatch: ${text}`);
                assert.ok(matrix.includes(text), `matrix count mismatch: ${text}`);
            }
        });
        backgrounds.push({ candidateId: candidate.candidateId, index: background.index, hypothesisId: background.hypothesisId,
            label: background.label, identity: background.identity, rowCount: rows.length, mainCounts: counts.map(({ kind, count }) => ({ kind, count })) });
        for (const { card, entry } of rows) {
            const projected = api.resultPresentation(entry), saved = entry.savedCriterion;
            const original = saved ? { status: saved.status, reasonCode: saved.reasonCode, strength: saved.appliedStrength } :
                entry.evaluation ? { status: entry.evaluation.evaluationResult?.status, reasonCode: entry.evaluation.evaluationResult?.reasonCode, strength: entry.evaluation.appliedStrength } : {};
            const row = { candidateId: candidate.candidateId, backgroundIndex: background.index, hypothesisId: background.hypothesisId,
                gene: background.attempt.context?.gene?.symbol, allele: alleleKey(background.attempt.context?.variant?.normalizedAllele),
                inputAllele: alleleKey(background.attempt.context?.variant?.inputAllele), criterion: card.code,
                original, displayIssue: !!entry.displayIssue, presentationIssues: entry.presentationIssues || [], summary: entry.summary,
                mainKind: projected.mainKind, adoption: projected.adoption.kind, progress: projected.progress.kind,
                outcome: projected.outcome.kind, displayedReason: projected.reasonCode };
            rowsReport.push(row); add(sourceStatusCounts, original.status || 'NO_EVALUATION'); add(displayedMainCounts, projected.mainKind);
            byCriterion[card.code] ||= { rows: 0, originalStatus: {}, mainKind: {}, displayIssues: 0, presentationIssues: {}, summaries: {}, unresolvedReasonCodes: {} };
            const stats = byCriterion[card.code]; stats.rows++;
            if (projected.mainKind === 'NO_CONCLUSION') add(stats.unresolvedReasonCodes, original.reasonCode || 'NO_REASON'); add(stats.originalStatus, original.status || 'NO_EVALUATION'); add(stats.mainKind, projected.mainKind);
            if (entry.displayIssue) { stats.displayIssues++; for (const issue of row.presentationIssues) add(stats.presentationIssues, issue); add(stats.summaries, entry.summary || 'no summary'); }
        }
    }
}
check('all 27 saved backgrounds and 486 projected rows were covered', () => { assert.equal(backgrounds.length, 27); assert.equal(rowsReport.length, 486); });
check('actual complete-run workbench first background SSR renders 18 result rows', () => {
    const html = renderToStaticMarkup(React.createElement(api.Workbench, { run, criteria: run.enabledCriteria }));
    assert.equal((html.match(/data-testid="evidence-result-row"/g) || []).length, 18);
    assert.ok(html.includes('四类合计 18 项证据结果'));
});
check('raw object/status/strength/reason/source pins and disk bytes remain invariant', () => {
    assert.equal(sha(JSON.stringify(run)), before);
    assert.equal(sha(fs.readFileSync(resultPath)), sha(rawBytes));
    assert.equal(sha(fs.readFileSync(expectedPath)), sha(expectedBytes));
    assert.equal(axiosCalls, 0);
});
const conditional = expected.variants.map(variant => {
    const key = alleleKey(variant), code = String(variant.criterion).match(/^[A-Z]+[0-9]+/)?.[0];
    const matching = rowsReport.filter(row => (row.allele === key || row.inputAllele === key) && row.criterion === code);
    return { testId: variant.testId, gene: variant.gene, allele: key, criterion: variant.criterion,
        historicalOutcome: variant.historicalOutcome, reason: variant.reason,
        interpretation: 'Historical conditional expectation, not unconditional MET and not newly rerun scientific evaluation',
        matchedBackgrounds: matching.length, savedAndDisplayed: matching.map(row => ({ candidateId: row.candidateId, backgroundIndex: row.backgroundIndex,
            inputAllele: row.inputAllele, normalizedAllele: row.allele, original: row.original, mainKind: row.mainKind, displayIssue: row.displayIssue, presentationIssues: row.presentationIssues })) };
});
const report = { schemaVersion: 'acmg-legacy20-frontend-regression-v1', layer: 'Archived actual product JSON + current adapters + actual component SSR; no HTTP/browser/scientific execution',
    source: { resultPath: path.resolve(resultPath), bytes: rawBytes.length, sha256: sha(rawBytes), analysisId: run.analysisId, runId: run.runId,
        expectedPath: path.resolve(expectedPath), expectedSha256: sha(expectedBytes), conditionalExpectationScope: expected.scope },
    counts: { candidates: run.candidates.length, backgrounds: backgrounds.length, projectedRows: rowsReport.length, enabledCriteria: run.enabledCriteria.length,
        checks: checks.length, failures: failures.length, axiosCalls, displayIssues: rowsReport.filter(row => row.displayIssue).length },
    sourceStatusCounts, displayedMainCounts, byCriterion, checks, failures, backgrounds, conditional,
    compatibilityFinding: 'This real archived run has no rejected display rows; all 15 saved MET rows remain adopted. This does not establish compatibility of every possible historical method.',
    limitations: ['Historical unmet scientific conditions are not fixed by presentation or required to be MET.',
        'Known unsupported or inconsistent saved methods remain explicitly reported, without rewriting archived raw records.',
        'SSR does not verify browser clicks, visual layout, live source continuation, save/history/export HTTP or actual user downloads.'] };
fs.mkdirSync(output, { recursive: true });
fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify(report, null, 2) + '\n');
fs.writeFileSync(path.join(output, 'rows.json'), JSON.stringify(rowsReport, null, 2) + '\n');
console.log(JSON.stringify({ counts: report.counts, sourceStatusCounts, displayedMainCounts,
    byCriterion: Object.fromEntries(Object.entries(byCriterion).map(([code, item]) => [code, { displayIssues: item.displayIssues, mainKind: item.mainKind, originalStatus: item.originalStatus, presentationIssues: item.presentationIssues }])),
    unmatchedConditionalTests: conditional.filter(row => !row.matchedBackgrounds).map(row => row.testId), failures, summary: path.join(output, 'summary.json') }, null, 2));
if (failures.length) process.exitCode = 1;
