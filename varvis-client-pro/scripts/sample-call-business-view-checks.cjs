// Exact central saved JSON + synthetic transport counterexamples + actual React SSR.
// No Java, HTTP, source recovery, model call, browser or scientific analysis.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { createRequire, Module } = require('node:module');
const [contractPath, outputPath, sharedPath] = process.argv.slice(2);
if (!contractPath || !outputPath) throw Error('Supply sample-call public contract and assigned private output');
const project = path.resolve(__dirname, '..'), shared = path.resolve(sharedPath || project), output = path.resolve(outputPath);
if (!output.replaceAll('\\', '/').endsWith('/dispatch/a9/round4/sample-call-regression')) throw Error('Use assigned sample-call-regression output');
const dependencies = createRequire(path.join(shared, 'package.json'));
const bundle = dependencies('esbuild').buildSync({ stdin: { contents: `export {default as Component,inspectSampleCallSummary} from './src/pages/varvis/ChromosomeVisualization/components/SampleCallSummary.jsx';export {backgroundEvidenceCards,resultPresentation} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';export {default as Workbench} from './src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx';export {SavedResult} from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', packages: 'external', write: false, loader: {'.js':'jsx','.less':'empty'}, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'silent' });
const loaded = new Module(path.join(project, 'scripts', 'sample-call-business-bundle.cjs'));
loaded.filename = loaded.id; loaded.paths = [path.join(shared, 'node_modules')]; loaded._compile(bundle.outputFiles[0].text, loaded.filename);
const { Component, Workbench, SavedResult, inspectSampleCallSummary, backgroundEvidenceCards, resultPresentation } = loaded.exports;
const React = dependencies('react'), { renderToStaticMarkup } = dependencies('react-dom/server');
const axios = dependencies('axios'); let httpCalls = 0;
axios.defaults.adapter = async () => { httpCalls++; throw Error('No HTTP allowed'); };
const digest = value => createHash('sha256').update(value).digest('hex');
const contractBytes = fs.readFileSync(contractPath), contract = JSON.parse(contractBytes.toString('utf8').replace(/^\uFEFF/, ''));
const root = path.dirname(path.resolve(contractPath)), results = [], failures = [], files = [], controls = [];
const freeze = value => { if (value && typeof value === 'object' && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); } };
const render = candidate => renderToStaticMarkup(React.createElement(Component, { candidate }));
const inspect = inspectSampleCallSummary;
function check(name, fn) { try { fn(); results.push({ name, status: 'PASS' }); } catch (error) { const item = { name, status: 'FAIL', error: error.message }; results.push(item); failures.push(item); } }
let base, candidateCount = 0, independentMet = 0;
check('stable central business contract', () => {
    assert.equal(contract.schemaVersion, 'current-sample-call-public-contract-v1');
    assert.equal(contract.sourceHead, '4512c5a52e81cfe9d0e4dbe7efb963268449f75c'); assert.equal(contract.samples.length, 6);
});
for (const scenario of contract.samples) for (const file of scenario.files) {
    const filename = path.resolve(root, file.file);
    if (!filename.startsWith(root + path.sep)) throw Error('Sample file escaped contract directory');
    const bytes = fs.readFileSync(filename), source = JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, ''));
    const before = digest(JSON.stringify(source)); freeze(source);
    const safeCandidates = [];
    check(`${file.file}: exact product bytes`, () => { assert.equal(bytes.length, file.bytes); assert.equal(digest(bytes), file.sha256); });
    for (const [index, candidate] of source.candidates.entries()) {
        candidateCount++;
        if (!base && scenario.scenario === '0_1') base = candidate;
        check(`${file.file}/${index}: original report, completeness and SSR interpretation`, () => {
            const view = inspect(candidate), html = render(candidate);
            const expectedPresent = index === 0 ? scenario.summaryPresent : Object.hasOwn(candidate, 'sampleCallSummary');
            const expectedReported = index === 0 ? scenario.targetAltReported : candidate.sampleCallSummary?.targetAltReported;
            const expectedComplete = index === 0 ? scenario.genotypeComplete : candidate.sampleCallSummary?.genotypeComplete;
            if (expectedPresent) {
                assert.equal(view.state, 'READY'); assert.equal(view.targetAltReported, expectedReported); assert.equal(view.genotypeComplete, expectedComplete);
                assert.match(html, /样本记录事实/);
                if (expectedReported === true) assert.match(html, /原始分析 VCF 记录报告目标 ALT/);
                else if (expectedReported === false) assert.match(html, /原始记录未报告目标 ALT/);
                else assert.match(html, /是否报告目标 ALT 尚无法确定/);
                assert.match(html, expectedComplete ? /基因型记录完整/ : /基因型记录不完整/);
                assert.match(html, /原始 FILTER 通过/);
                assert.match(html, /原始字段缺失/); assert.doesNotMatch(html, new RegExp(candidate.sampleCallSourceSnapshotSha256));
            } else {
                assert.equal(view.state, 'ABSENT'); assert.doesNotMatch(html, /本记录未保存样本报告摘要/);
                if (!view.issue) assert.equal(html, '');
                assert.doesNotMatch(html, /原始分析 VCF 记录报告目标 ALT/);
                assert.doesNotMatch(html, /原始记录未报告目标 ALT/);
            }
            safeCandidates.push({ state: view.state, reported: view.targetAltReported ?? null, complete: view.genotypeComplete ?? null });
        });
        if (index === 0 && source.enabledCriteria?.includes('ACMG_AMP_2015:BS1') && candidate.attempts && scenario.summaryPresent) check(`${file.file}/${index}: independent BS1 adoption survives ref/no-call/partial report`, () => {
            const cards = backgroundEvidenceCards(candidate, source.enabledCriteria, source, 0);
            const entries = cards.find(card => card.id === 'ACMG_AMP_2015:BS1').entries;
            assert.ok(entries.some(entry => resultPresentation(entry).mainKind === 'ADOPTED_MET'));
            independentMet++;
        });
    }
    if (scenario.summaryPresent && file.file.endsWith('/saved.json')) {
        for (const [label, View] of [['workbench',Workbench],['legacy-saved',SavedResult]]) check(`${file.file}: actual ${label} mounts primary sample facts without changing adoption`,()=>{
            const html=renderToStaticMarkup(React.createElement(View,{run:source,criteria:source.enabledCriteria,trackingPaused:true}));
            const start=html.indexOf('data-testid="sample-call-summary"');assert.ok(start>=0,'integrated sample card must be visible');
            const firstCard=html.slice(start,start+2500);
            const expected=scenario.targetAltReported===true?'原始分析 VCF 记录报告目标 ALT':scenario.targetAltReported===false?'原始记录未报告目标 ALT':'当前原始记录是否报告目标 ALT 尚无法确定';
            assert.ok(firstCard.includes(expected),`${label}: primary saved sample fact must match`);
            assert.ok(!html.includes(source.candidates[0].sampleCallSourceSnapshotSha256));
            assert.ok(html.includes('已采用满足'),'independently applicable saved BS1 remains adopted');
        });
    }
    check(`${file.file}: all source objects, ranking, scientific fields and bytes invariant`, () => {
        assert.equal(digest(JSON.stringify(source)), before); assert.equal(digest(fs.readFileSync(filename)), file.sha256);
    });
    files.push({ file: file.file, bytes: bytes.length, sha256: digest(bytes), candidates: safeCandidates });
}
check('all 18 exact products and 36 candidates processed', () => { assert.equal(files.length, 18); assert.equal(candidateCount, 36); });
const mutations = [
    ['missing association', value => { delete value.sampleCallAssociationStatus; }],
    ['wrong association', value => { value.sampleCallAssociationStatus = 'RANKED_RECORD_ONLY'; }],
    ['missing snapshot hash', value => { delete value.sampleCallSourceSnapshotSha256; }],
    ['malformed snapshot hash', value => { value.sampleCallSourceSnapshotSha256 = 'not-a-hash'; }],
    ['uppercase snapshot hash', value => { value.sampleCallSourceSnapshotSha256 = value.sampleCallSourceSnapshotSha256.toUpperCase(); }],
    ['summary schema', value => { value.sampleCallSummary.schemaVersion = 'future-summary-v2'; }],
    ['summary scope', value => { value.sampleCallSummary.scope = 'RANKED_QUERY_COPY'; }],
    ['report contradicts typed call', value => { value.sampleCallSummary.targetAltReported = false; }],
    ['completeness contradicts typed call', value => { value.sampleCallSummary.genotypeComplete = false; }],
    ['reason contradicts reported flags', value => { value.sampleCallSummary.reasonCode = 'SOURCE_DOES_NOT_REPORT_TARGET_ALT'; }],
    ['wrong source role', value => { value.sampleCallSummary.source.artifactRole = 'NORMALIZED_QUERY_VCF'; }],
    ['wrong source provenance', value => { value.sampleCallSummary.source.provenance = 'RANKED_COPY'; }],
    ['source count ambiguous', value => { value.sampleCallSummary.source.matchingRecordCount = 2; }],
    ['assembly mismatch retains target report', value => { value.sampleCallSummary.source.originalAnalysisAssembly = 'GRCh37'; }],
    ['negative DP', value => { value.sampleCallSummary.quality.depth = -1; }],
    ['invalid AB', value => { value.sampleCallSummary.quality.alleleBalance = 1.5; }],
    ['invalid quality field disclosure', value => { value.sampleCallSummary.quality.invalidFields = ['SECRET_SAMPLE_ID']; }],
    ['GT invalidity contradicts report', value => { value.sampleCallSummary.quality.invalidFields = ['GT']; }],
    ['missing candidate-relative typed field', value => { value.sampleCallSummary.candidateRelativeGenotype = null; }],
];
for (const [label, mutate] of mutations) check(`synthetic counterexample: ${label}`, () => {
    const candidate = structuredClone(base); mutate(candidate);
    const before = JSON.stringify(candidate); assert.equal(inspect(candidate).state, 'UNVERIFIED');
    const html = render(candidate); assert.match(html, /data-testid="sample-call-unverified"/); assert.doesNotMatch(html, /原始分析 VCF 记录报告目标 ALT/);
    assert.equal(JSON.stringify(candidate), before); controls.push(label);
});
check('absent legacy fields never reconstructed from raw GT, QC or ranking', () => {
    const candidate = { sampleId: 'SECRET_SAMPLE_ID', rawGT: 'SECRET_RAW_GT', observation: { genotype: '0/1' }, savedObservation: { analysisVcfCall: structuredClone(base.sampleCallSummary) } };
    assert.equal(inspect(candidate).state, 'ABSENT'); assert.equal(render(candidate), '');
});
check('SSR never exposes sample identifiers, paths, raw GT, relative genotype or snapshots', () => {
    const candidate = structuredClone(base), secret = 'SECRET_SAMPLE_RAW_PATH';
    candidate.sampleId = secret; candidate.rawGT = secret; candidate.path = secret;
    candidate.sampleCallSummary.sampleId = secret; candidate.sampleCallSummary.rawGT = secret;
    candidate.sampleCallSummary.candidateRelativeGenotype = secret; candidate.sampleCallSummary.source.path = secret;
    candidate.sampleCallSummary.source.sampleId = secret;
    const html = render(candidate); assert.equal(inspect(candidate).state, 'READY'); assert.doesNotMatch(html, new RegExp(secret));
    assert.doesNotMatch(html, /REF\/TARGET_ALT|sampleId|rawGT|originalAnalysisAlleleKeySha256/);
});
check('FILTER and available numeric QC stay independent without inferred thresholds', () => {
    const candidate = structuredClone(base), quality = candidate.sampleCallSummary.quality;
    quality.filterEvaluation = 'FILTERED'; quality.depth = 0; quality.genotypeQuality = 0; quality.referenceDepth = 5; quality.targetAlleleDepth = 0; quality.alleleBalance = 0;
    const view = inspect(candidate), html = render(candidate);
    assert.equal(view.state, 'READY'); assert.equal(view.targetAltReported, true); assert.match(html, /原始 FILTER 标记为过滤/);
    assert.match(html, /DP 深度：0/); assert.match(html, /目标 ALT 比例：0/); assert.doesNotMatch(html, /综合质量已通过/);
});
check('partial ALT report never becomes complete genotype', () => {
    const candidate = structuredClone(base), value = candidate.sampleCallSummary;
    value.targetAlleleCall = 'PARTIAL_TARGET'; value.genotypeComplete = false; value.reasonCode = 'SOURCE_REPORTS_TARGET_ALT_WITH_INCOMPLETE_GENOTYPE'; value.quality.missingFields = ['GT'];
    assert.equal(inspect(candidate).state, 'READY'); assert.match(render(candidate), /基因型记录不完整/);
    value.genotypeComplete = true; assert.equal(inspect(candidate).state, 'UNVERIFIED');
});
check('ambiguous records reject a verified-association claim and preserve unknown facts without it', () => {
    const candidate = structuredClone(base), value = candidate.sampleCallSummary;
    value.source.matchStatus = 'AMBIGUOUS'; value.source.matchingRecordCount = 2; value.source.targetAlleleIndex = null; value.source.matchedRecordOrdinal = null;
    value.targetAlleleCall = null; value.candidateRelativeGenotype = null; value.targetAltReported = null; value.genotypeComplete = null; value.reasonCode = 'ANALYSIS_VCF_AMBIGUOUS';
    value.quality = { filterEvaluation: null, depth: null, genotypeQuality: null, referenceDepth: null, targetAlleleDepth: null, alleleBalance: null, missingFields: [], invalidFields: [] };
    assert.equal(inspect(candidate).state, 'UNVERIFIED');
    candidate.sampleCallAssociationStatus = null;
    assert.equal(inspect(candidate).state, 'UNKNOWN'); assert.match(render(candidate), /是否报告目标 ALT 尚无法确定/);
});
check('well-formed snapshot digest is only trace metadata, not self-authenticating proof', () => {
    const candidate = structuredClone(base); candidate.sampleCallSourceSnapshotSha256 = 'd'.repeat(64);
    assert.equal(inspect(candidate).state, 'READY');
    assert.equal(render(candidate), render(base));
});
check('zero HTTP calls throughout actual SSR and synthetic cases', () => assert.equal(httpCalls, 0));
const report = { schemaVersion: 'sample-call-business-view-checks-v1', sourceHead: contract.sourceHead, contractSha256: digest(contractBytes),
    layer: 'Central actual saved/options/export JSON produced under synthetic tests + current component SSR; not a new Java/HTTP/browser run',
    counts: { products: files.length, candidates: candidateCount, independentBs1AdoptionsChecked: independentMet, checks: results.length, failures: failures.length, httpCalls },
    files, syntheticCounterexamples: controls, results, failures,
    harnessCorrection: 'Contract scenario expectations apply to candidate index 0; unaffected control candidates are checked against their own saved typed facts, not assigned the primary candidate genotype or MET.',
    limitations: ['Only saved typed facts are displayed; original source association remains the central caller responsibility.',
        'A correctly formatted snapshot hash cannot independently authenticate the source from these three props.',
        'No browser visual acceptance, source recovery, scientific analysis, history/export HTTP or actual download verification.'] };
fs.mkdirSync(output, { recursive: true }); fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ counts: report.counts, failures, summary: path.join(output, 'summary.json') }, null, 2));
if (failures.length) process.exitCode = 1;
