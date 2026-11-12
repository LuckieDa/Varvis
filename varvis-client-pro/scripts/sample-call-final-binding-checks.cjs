// Final publication-boundary replay: exact central product JSON + current React SSR.
// No scientific execution, Java, HTTP, source recovery, browser or model calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { createRequire, Module } = require('node:module');
const [contractPath, outputPath, sharedPath] = process.argv.slice(2);
if (!contractPath || !outputPath) throw Error('Supply final call-binding contract and assigned private output');
const project = path.resolve(__dirname, '..'), shared = path.resolve(sharedPath || project), output = path.resolve(outputPath);
if (!output.replaceAll('\\', '/').endsWith('/dispatch/a9/round4/sample-call-final-binding')) throw Error('Use assigned sample-call-final-binding output');
const sourceDigest = value => createHash('sha256').update(value).digest('hex');
const frontendSourceSha256 = Object.fromEntries([
    'src/pages/varvis/ChromosomeVisualization/components/SampleCallSummary.jsx',
    'src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench.js',
    'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js',
    'src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx',
    'scripts/sample-call-final-binding-checks.cjs',
].map(file => [file, sourceDigest(fs.readFileSync(path.join(project, file)))]));
const dependencies = createRequire(path.join(shared, 'package.json'));
const bundle = dependencies('esbuild').buildSync({ stdin: { contents: `export {default as Component,inspectSampleCallSummary} from './src/pages/varvis/ChromosomeVisualization/components/SampleCallSummary.jsx';export {default as Workbench} from './src/pages/varvis/ChromosomeVisualization/components/AcmgResultsWorkbench.jsx';export {backgroundEvidenceCards,resultPresentation,presentationCounts} from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', packages: 'external', write: false, loader: { '.js': 'jsx', '.less': 'empty' }, define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'silent' });
const loaded = new Module(path.join(project, 'scripts', 'sample-call-final-binding-bundle.cjs'));
loaded.filename = loaded.id; loaded.paths = [path.join(shared, 'node_modules')]; loaded._compile(bundle.outputFiles[0].text, loaded.filename);
const { Component, inspectSampleCallSummary: inspect, backgroundEvidenceCards, resultPresentation, presentationCounts, Workbench } = loaded.exports;
const React = dependencies('react'), { renderToStaticMarkup } = dependencies('react-dom/server');
let httpCalls = 0;
dependencies('axios').defaults.adapter = async () => { httpCalls++; throw Error('No HTTP permitted'); };
const digest = value => createHash('sha256').update(value).digest('hex');
const readJson = filename => { const bytes = fs.readFileSync(filename); return { bytes, value: JSON.parse(bytes.toString('utf8').replace(/^\uFEFF/, '')) }; };
const { bytes: contractBytes, value: contract } = readJson(contractPath), root = path.dirname(path.resolve(contractPath));
const { bytes: validationBytes, value: validation } = readJson(path.join(root, contract.receipt));
const checks = [], failures = [], files = [], states = {}, scenarioStates = {}, synthetic = [];
let candidateCount = 0, independentMetRows = 0, workbenchReplays = 0, positiveBase, ambiguousBase, noCallBase;
const freeze = value => { if (value && typeof value === 'object' && !Object.isFrozen(value)) { Object.values(value).forEach(freeze); Object.freeze(value); } };
const render = candidate => renderToStaticMarkup(React.createElement(Component, { candidate }));
const add = (counts, key) => { counts[key] = (counts[key] || 0) + 1; };
function check(name, action) { try { action(); checks.push({ name, status: 'PASS' }); } catch (error) { const item = { name, status: 'FAIL', error: error.message }; checks.push(item); failures.push(item); } }
check('final public contract and central receipt agree on 21 exact scenarios', () => {
    assert.equal(contract.schemaVersion, 'sample-call-original-record-public-contract-v1');
    assert.equal(contract.sourceHead, '74d82a4025ded7419cb58c18a8bf0ae184a1c968');
    assert.equal(validation.schemaVersion, 'sample-call-original-record-binding-validation-v1');
    assert.equal(validation.sourceHead, contract.sourceHead); assert.equal(contract.sampleCases, 21);
    assert.equal(contract.sampleManifest.length, 21); assert.deepEqual(validation.samples, contract.sampleManifest);
});
for (const scenario of contract.sampleManifest) for (const record of scenario.files) {
    const filename = path.resolve(root, record.file);
    if (!filename.startsWith(root + path.sep)) throw Error('Product path escaped contract directory');
    const { bytes, value: source } = readJson(filename), before = digest(JSON.stringify(source)); freeze(source);
    check(`${record.file}: exact original raw bytes and hash`, () => { assert.equal(bytes.length, record.bytes); assert.equal(digest(bytes), record.sha256); });
    const observed = [];
    for (const [index, candidate] of source.candidates.entries()) {
        candidateCount++;
        const view = inspect(candidate), summary = candidate.sampleCallSummary;
        add(states, view.state);
        if (index === 0) {
            scenarioStates[scenario.scenario] ||= { primaryState: view.state, products: 0 }; scenarioStates[scenario.scenario].products++;
            if (scenario.scenario === '0_1' && !positiveBase) positiveBase = candidate;
            if (scenario.scenario === 'ambiguous-call' && !ambiguousBase) ambiguousBase = candidate;
            if (scenario.scenario === 'N_N' && !noCallBase) noCallBase = candidate;
        }
        check(`${record.file}/${index}: candidate-own saved fields preserve report semantics`, () => {
            const present = index === 0 ? scenario.summaryPresent : Object.hasOwn(candidate, 'sampleCallSummary');
            if (!present) assert.equal(view.state, 'ABSENT');
            else if (summary.source.matchStatus !== 'MATCHED') {
                assert.equal(view.state, 'UNKNOWN'); assert.equal(view.targetAltReported, null); assert.equal(view.genotypeComplete, null);
                assert.notEqual(candidate.sampleCallAssociationStatus, 'VERIFIED_ORIGINAL_ANALYSIS_SOURCE');
            } else {
                assert.equal(view.state, 'READY'); assert.equal(candidate.sampleCallAssociationStatus, 'VERIFIED_ORIGINAL_ANALYSIS_SOURCE');
                assert.equal(view.targetAltReported, index === 0 ? scenario.targetAltReported : summary.targetAltReported);
                assert.equal(view.genotypeComplete, index === 0 ? scenario.genotypeComplete : summary.genotypeComplete);
            }
            if (index === 0) assert.equal(candidate.sampleCallAssociationStatus ?? null, scenario.associationStatus);
        });
        check(`${record.file}/${index}: actual SSR labels do not invent source binding or patient-data damage`, () => {
            const html = render(candidate);
            if (view.state === 'READY') {
                assert.match(html, /样本记录事实/);
                assert.match(html, /原始 FILTER|未保存原始 FILTER 评价/);
                if (view.targetAltReported === true) assert.match(html, /原始分析 VCF 记录报告目标 ALT/);
                else if (view.targetAltReported === false) assert.match(html, /原始记录未报告目标 ALT/);
                else assert.match(html, /是否报告目标 ALT 尚无法确定/);
                assert.match(html, view.genotypeComplete ? /基因型记录完整/ : /基因型记录不完整/);
            } else if (view.state === 'UNKNOWN') {
                assert.match(html, /data-testid="sample-call-unknown"/); assert.match(html, /尚未建立当前候选的唯一记录关联/);
                assert.match(html, /是否报告目标 ALT 尚无法确定/);
                assert.doesNotMatch(html, /data-testid="sample-call-unverified"|原始分析 VCF 记录报告目标 ALT|原始记录未报告目标 ALT/);
            } else {
                assert.doesNotMatch(html, /本记录未保存样本报告摘要/);
                if (view.issue) assert.match(html, /data-testid="sample-call-absent"/);
                else assert.equal(html, '');
                assert.doesNotMatch(html, /原始分析 VCF 记录报告目标 ALT|原始记录未报告目标 ALT/);
                if (view.issue === 'SOURCE_CONFLICT') assert.match(html, /来源关联不一致/);
                if (view.issue === 'ASSOCIATION_REQUIRED') assert.match(html, /原始记录的关联尚未核实/);
                if (view.issue === 'SOURCE_UNAVAILABLE') assert.match(html, /原始分析来源不可用或尚未通过核查/);
            }
            for (const token of [candidate.sampleCallSourceSnapshotSha256, summary?.source?.originalAnalysisAlleleKeySha256]) if (token) assert.ok(!html.includes(token));
            assert.doesNotMatch(html, /sampleId|rawGT|REF\/TARGET_ALT/);
        });
        if (candidate.attempts && source.enabledCriteria?.includes('ACMG_AMP_2015:BS1')) for (const [backgroundIndex, attempt] of candidate.attempts.entries()) {
            const original = attempt.bs1?.evaluation;
            if (original?.evaluationResult?.status !== 'MET') continue;
            check(`${record.file}/${index}/${backgroundIndex}: independent saved BS1 MET remains adopted`, () => {
                const rows = backgroundEvidenceCards(candidate, source.enabledCriteria, source, backgroundIndex).find(card => card.id === 'ACMG_AMP_2015:BS1').entries;
                assert.ok(rows.some(entry => resultPresentation(entry).mainKind === 'ADOPTED_MET'));
                assert.equal(original.evaluationResult.status, 'MET'); independentMetRows++;
            });
        }
        observed.push({ index, state: view.state, issue: view.issue, sourceState: view.sourceState,
            targetAltReported: view.targetAltReported ?? null, genotypeComplete: view.genotypeComplete ?? null });
    }
    if (path.basename(record.file) === 'saved.json') check(`${record.file}: actual Workbench primary sample state and four evidence counts`, () => {
        const candidate = source.candidates[0], view = inspect(candidate);
        const cards = backgroundEvidenceCards(candidate, source.enabledCriteria, source, 0);
        const rows = cards.flatMap(card => card.entries.map(entry => ({ card, entry })));
        const counts = presentationCounts(rows), countsBefore = JSON.stringify(counts);
        const html = renderToStaticMarkup(React.createElement(Workbench, { run: source, criteria: source.enabledCriteria }));
        assert.match(html, /data-testid="acmg-results-workbench"/);
        const testId = view.state === 'READY' ? 'sample-call-summary' : view.state === 'UNKNOWN' ? 'sample-call-unknown' : 'sample-call-absent';
        assert.equal((html.match(new RegExp(`data-testid="${testId}"`, 'g')) || []).length, view.state === 'ABSENT' && !view.issue ? 0 : 1);
        if (view.state === 'UNKNOWN') {
            assert.match(html, /尚未建立当前候选的唯一记录关联/);
            assert.match(html, /是否报告目标 ALT 尚无法确定/);
        } else if (view.state === 'ABSENT') {
            assert.doesNotMatch(html, /本记录未保存样本报告摘要/);
            if (view.issue === 'SOURCE_CONFLICT') assert.match(html, /当前候选的来源关联不一致/);
            if (view.issue === 'ASSOCIATION_REQUIRED') assert.match(html, /原始记录的关联尚未核实/);
            if (view.issue === 'SOURCE_UNAVAILABLE') assert.match(html, /原始分析来源不可用或尚未通过核查/);
        }
        assert.equal((html.match(/data-testid="evidence-result-row"/g) || []).length, rows.length);
        assert.doesNotMatch(html, /未审核、未采用及读取问题不计作已采用满足/);
        for (const item of counts) assert.ok(html.includes(`aria-label="${item.label} ${item.count} 项"`));
        assert.equal(JSON.stringify(presentationCounts(backgroundEvidenceCards(candidate, source.enabledCriteria, source, 0)
            .flatMap(card => card.entries.map(entry => ({ card, entry }))))), countsBefore);
        assert.equal(digest(JSON.stringify(source)), before); workbenchReplays++;
    });
    check(`${record.file}: source object, ranks, scores, scientific results and bytes unchanged`, () => {
        assert.equal(digest(JSON.stringify(source)), before); assert.equal(digest(fs.readFileSync(filename)), record.sha256);
    });
    files.push({ file: record.file, bytes: bytes.length, sha256: digest(bytes), candidates: observed });
}
check('all 63 actual product surfaces, 126 candidates and 21 saved Workbench integrations covered', () => { assert.equal(files.length, 63); assert.equal(candidateCount, 126); assert.equal(workbenchReplays, 21); });
function reject(label, original, mutate, issue) {
    check(`synthetic semantic counterexample: ${label}`, () => {
        const candidate = structuredClone(original); mutate(candidate); const before = JSON.stringify(candidate);
        const view = inspect(candidate); assert.equal(view.state, 'UNVERIFIED'); if (issue) assert.equal(view.issue, issue);
        const html = render(candidate); assert.match(html, /sample-call-unverified/);
        assert.doesNotMatch(html, /原始分析 VCF 记录报告目标 ALT|原始记录未报告目标 ALT/);
        assert.equal(JSON.stringify(candidate), before); synthetic.push({ label, expected: 'UNVERIFIED' });
    });
}
reject('MATCHED true without exact VERIFIED', positiveBase, value => { delete value.sampleCallAssociationStatus; }, 'ASSOCIATION_INVALID');
reject('MATCHED false without exact VERIFIED', positiveBase, value => {
    delete value.sampleCallAssociationStatus; Object.assign(value.sampleCallSummary, { targetAlleleCall: 'HOM_REF', candidateRelativeGenotype: 'REF/REF', targetAltReported: false, reasonCode: 'SOURCE_DOES_NOT_REPORT_TARGET_ALT' });
}, 'ASSOCIATION_INVALID');
reject('MATCHED no-call without exact VERIFIED', noCallBase, value => { delete value.sampleCallAssociationStatus; }, 'ASSOCIATION_INVALID');
reject('non-MATCHED cannot borrow VERIFIED marker', ambiguousBase, value => { value.sampleCallAssociationStatus = 'VERIFIED_ORIGINAL_ANALYSIS_SOURCE'; }, 'ASSOCIATION_INVALID');
reject('non-MATCHED cannot invent true report', ambiguousBase, value => { value.sampleCallSummary.targetAltReported = true; }, 'FACT_CONFLICT');
reject('malformed snapshot', positiveBase, value => { value.sampleCallSourceSnapshotSha256 = 'bad'; });
reject('missing snapshot', positiveBase, value => { delete value.sampleCallSourceSnapshotSha256; });
reject('uppercase snapshot', positiveBase, value => { value.sampleCallSourceSnapshotSha256 = value.sampleCallSourceSnapshotSha256.toUpperCase(); });
reject('wrong summary scope', positiveBase, value => { value.sampleCallSummary.scope = 'NORMALIZED_QUERY_COPY'; });
reject('wrong source provenance', positiveBase, value => { value.sampleCallSummary.source.provenance = 'RANKED_COPY'; });
reject('source record count mismatch', positiveBase, value => { value.sampleCallSummary.source.matchingRecordCount = 2; });
reject('invalid source ordinal', positiveBase, value => { value.sampleCallSummary.source.matchedRecordOrdinal = null; });
reject('inconsistent reported typed flags', positiveBase, value => { value.sampleCallSummary.targetAltReported = false; }, 'FACT_CONFLICT');
reject('partial type cannot become complete', positiveBase, value => { value.sampleCallSummary.targetAlleleCall = 'PARTIAL_TARGET'; value.sampleCallSummary.reasonCode = 'SOURCE_REPORTS_TARGET_ALT_WITH_INCOMPLETE_GENOTYPE'; }, 'FACT_CONFLICT');
reject('matched source assemblies cannot disagree', positiveBase, value => { value.sampleCallSummary.source.originalAnalysisAssembly = 'GRCh37'; }, 'FACT_CONFLICT');
reject('quality cannot carry private field code', positiveBase, value => { value.sampleCallSummary.quality.missingFields = ['PRIVATE_SAMPLE_NAME']; });
reject('invalid quality scalar', positiveBase, value => { value.sampleCallSummary.quality.depth = -1; });
check('synthetic NOT_FOUND remains unknown without a VERIFIED marker', () => {
    const candidate = structuredClone(ambiguousBase); candidate.sampleCallSummary.source.matchStatus = 'NOT_FOUND';
    candidate.sampleCallSummary.source.matchingRecordCount = 0; candidate.sampleCallSummary.reasonCode = 'ANALYSIS_VCF_NOT_FOUND';
    assert.equal(inspect(candidate).state, 'UNKNOWN'); assert.match(render(candidate), /未匹配到当前候选的记录/);
    assert.doesNotMatch(render(candidate), /sample-call-unverified|存在多个匹配记录/);
});
check('synthetic missing original call remains unknown without a VERIFIED marker', () => {
    const candidate = structuredClone(ambiguousBase), summary = candidate.sampleCallSummary;
    for (const key of ['schemaVersion', 'matchStatus', 'assembly', 'targetAlleleIndex', 'matchingRecordCount', 'matchedRecordOrdinal', 'artifactRole', 'provenance']) summary.source[key] = null;
    summary.reasonCode = 'ANALYSIS_VCF_CALL_MISSING'; assert.equal(inspect(candidate).state, 'UNKNOWN');
    assert.match(render(candidate), /未保存可用的原始样本调用信息/); assert.doesNotMatch(render(candidate), /sample-call-unverified/);
});
check('legacy absent fields do not borrow raw or ranked calls', () => {
    const candidate = { rawGT: '0/1', sampleId: 'PRIVATE_SAMPLE_NAME', savedObservation: { analysisVcfCall: positiveBase.sampleCallSummary } };
    assert.equal(inspect(candidate).state, 'ABSENT'); assert.equal(render(candidate), '');
});
check('saved conflict and unavailable blockers are explained without inferred patient-data deficits', () => {
    for (const [code, text] of [['ANALYSIS_VCF_CALL_SOURCE_CONFLICT', /来源关联不一致/], ['ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED', /原始记录的关联尚未核实/], ['EXECUTION_SOURCE_UNAVAILABLE', /原始分析来源不可用/]]) {
        const candidate = { attempts: [{ blockers: [{ reasonCode: code, path: 'PRIVATE_PATH' }] }] };
        assert.equal(inspect(candidate).state, 'ABSENT'); assert.match(render(candidate), text); assert.doesNotMatch(render(candidate), /PRIVATE_PATH/);
    }
});
check('privacy sentinel never renders from valid, unknown, conflicting or absent cards', () => {
    for (const original of [positiveBase, ambiguousBase, { sampleCallSummary: null }, {}]) {
        const candidate = structuredClone(original), secret = 'PRIVATE_SAMPLE_GT_PATH';
        candidate.sampleId = secret; candidate.rawGT = secret; candidate.path = secret;
        if (candidate.sampleCallSummary) {
            candidate.sampleCallSummary.sampleId = secret; candidate.sampleCallSummary.rawGT = secret;
            candidate.sampleCallSummary.source.path = secret;
            if (candidate.sampleCallSummary.source.matchStatus === 'MATCHED') candidate.sampleCallSummary.candidateRelativeGenotype = secret;
        }
        assert.doesNotMatch(render(candidate), new RegExp(secret));
    }
});
check('QC zero and FILTER remain independent from the original target report', () => {
    const candidate = structuredClone(positiveBase), q = candidate.sampleCallSummary.quality;
    q.filterEvaluation = 'FILTERED'; q.depth = 0; q.genotypeQuality = 0; q.alleleBalance = 0;
    assert.equal(inspect(candidate).targetAltReported, true); assert.match(render(candidate), /原始 FILTER 标记为过滤/);
    assert.match(render(candidate), /DP 深度：0/); assert.match(render(candidate), /目标 ALT 比例：0/);
});
check('snapshot digest is trace only and is not rendered or promoted to authentication', () => {
    const candidate = structuredClone(positiveBase); candidate.sampleCallSourceSnapshotSha256 = 'e'.repeat(64);
    assert.equal(inspect(candidate).state, 'READY');
    assert.equal(render(candidate), render(positiveBase));
});
check('all component/SSR checks made zero HTTP requests', () => assert.equal(httpCalls, 0));
check('owned frontend source and both central contract files stay unchanged during replay', () => {
    for (const [file, hash] of Object.entries(frontendSourceSha256).filter(([file]) => file.endsWith('SampleCallSummary.jsx') || file.endsWith('sample-call-final-binding-checks.cjs')))
        assert.equal(digest(fs.readFileSync(path.join(project, file))), hash);
    assert.equal(digest(fs.readFileSync(contractPath)), digest(contractBytes));
    assert.equal(digest(fs.readFileSync(path.join(root, contract.receipt))), digest(validationBytes));
});
const report = { schemaVersion: 'sample-call-final-binding-frontend-checks-v1', sourceHead: contract.sourceHead,
    frontendSourceSha256, concurrentSourceNote: 'Shared adapter/Workbench hashes are observations at replay start, not a final source freeze or a claim about other agents. Root pins the final combined state.', publicContract: { path: path.resolve(contractPath), sha256: digest(contractBytes) },
    centralReceipt: { sha256: digest(validationBytes), sourceHead: validation.sourceHead, reportedTests: validation.tests, executedByThisScript: false },
    layer: 'Read-only exact CURRENT product options/saved/export JSON from central synthetic HTTP/H2 verification + current standalone-card and actual saved-Workbench SSR + explicit synthetic semantic controls',
    counts: { scenarios: contract.sampleManifest.length, actualProducts: files.length, candidates: candidateCount, checks: checks.length, failures: failures.length,
        independentBs1MetSurfaceRows: independentMetRows, actualSavedWorkbenchReplays: workbenchReplays, httpCalls, states },
    scenarioStates, files, syntheticCounterexamples: synthetic, checks, failures,
    limits: ['This script does not rerun Java, original source recovery or clinical/scientific evidence evaluation.',
        'It checks saved publication markers and transport consistency; a digest alone is not proof of association.',
        'No original GT parsing, browser visual acceptance, live save/history/export endpoint or actual download verification.'] };
fs.mkdirSync(output, { recursive: true }); fs.writeFileSync(path.join(output, 'summary.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ counts: report.counts, scenarioStates, failures, summary: path.join(output, 'summary.json') }, null, 2));
if (failures.length) process.exitCode = 1;
