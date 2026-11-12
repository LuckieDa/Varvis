// Replay archived central HTTP/H2 public products through current frontend adapters and React SSR.
// Private v*.json files are read as raw bytes for receipt SHA comparison ONLY; never parsed or used to fill public data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createHash } = require('node:crypto');
const { createRequire, Module } = require('node:module');
const { execFileSync } = require('node:child_process');
const project = path.resolve(__dirname, '..'), checkout = path.resolve(project, '..');
const [inputDirectory, outputDirectory, sharedClient = project, expectedHead, filterArgument] = process.argv.slice(2);
if (!inputDirectory || !outputDirectory) throw Error('Supply existing central public fixture directory, private output directory, shared client dependencies and optional expected HEAD');
const inputs = path.resolve(inputDirectory), output = path.resolve(outputDirectory);
if (output === inputs || output.startsWith(`${inputs}${path.sep}`)) throw Error('Reports must not overwrite central originals');
const dependencies = createRequire(path.join(path.resolve(sharedClient), 'package.json'));
const digest = value => createHash('sha256').update(value).digest('hex');
const pinned = new Map(), checks = [], failures = [], products = [], receiptRows = [];
const readRaw = name => { const filename = path.join(inputs, name); const raw = fs.readFileSync(filename);
    pinned.set(name, { bytes: raw.length, sha256: digest(raw) }); return raw; };
const json = raw => JSON.parse(raw.toString('utf8').replace(/^\uFEFF/, ''));
const contract = json(readRaw('public-contract.json')), specs = json(readRaw('public-samples.json'));
if (filterArgument !== undefined && !/^--files=p(?:0|[1-9][0-9]*)\.json(?:,p(?:0|[1-9][0-9]*)\.json)*$/.test(filterArgument)) throw Error('Use an explicit --files=p2.json,p8.json whitelist');
const requestedFiles = filterArgument?.slice('--files='.length).split(',');
if (requestedFiles && (new Set(requestedFiles).size !== requestedFiles.length || requestedFiles.some(file => !specs.some(spec => spec.file === file)))) throw Error('Selected product files must be distinct existing contract entries');
const selectedSpecs = requestedFiles ? specs.filter(spec => requestedFiles.includes(spec.file)) : specs;
const readerSpecs = contract.independentReaders.filter(reader => selectedSpecs.some(spec => spec.kind === reader.kind && spec.scenario === reader.scenario));
const check = (name, action) => { try { action(); checks.push({ name, status: 'PASS' }); }
    catch (error) { const item = { name, status: 'FAIL', error: error.message }; checks.push(item); failures.push(item); } };
const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: checkout, encoding: 'utf8' }).trim();
const branch = execFileSync('git', ['branch', '--show-current'], { cwd: checkout, encoding: 'utf8' }).trim();
const expectedBranch = process.env.VARVIS_EXPECTED_FRONTEND_BRANCH || 'feature/acmg-a9-pp1';
check('expected frontend branch and supplied final frontend HEAD', () => {
    assert.equal(branch, expectedBranch);
    if (expectedBranch !== 'feature/acmg-a9-pp1') assert.ok(expectedHead, 'An integration checkout requires an explicit frozen HEAD');
    if (expectedHead) assert.equal(head, expectedHead);
});
check('central public contract carries exactly sixteen original products and seven independent readers', () => {
    assert.equal(contract.schemaVersion, 'central-sequence-proof-and-denovo-completion-v1');
    assert.deepEqual(specs, contract.publicSamples); assert.equal(specs.length, 16); assert.equal(contract.independentReaders.length, 7);
});
const frozen = new WeakSet();
function freeze(value) { if (value && typeof value === 'object' && !frozen.has(value)) { frozen.add(value); Object.values(value).forEach(freeze); Object.freeze(value); } return value; }
const publicRuns = selectedSpecs.map(spec => {
    const raw = readRaw(spec.file), run = freeze(json(raw));
    check(`${spec.file}: exact archived controller HTTP public bytes and declaration`, () => {
        assert.equal(raw.length, spec.bytes); assert.equal(digest(raw), spec.sha256); assert.equal(run.runId, spec.runId);
        assert.equal(run.analysisId, spec.analysisId); assert.equal(run.status, spec.status); assert.deepEqual(run.enabledCriteria, spec.enabledCriteria);
    });
    const privateFile = spec.file.replace(/^p/, 'v');
    const privateRawSha256 = digest(readRaw(privateFile)); // Raw bytes only, intentionally NOT JSON.parse.
    return { spec, run, before: digest(JSON.stringify(run)), privateFile, privateRawSha256 };
});
const byRun = new Map(publicRuns.map(item => [item.run.runId, item]));
for (const spec of readerSpecs) {
    const raw = readRaw(spec.file), receipt = json(raw);
    check(`${spec.file}: exact pre-existing independent reader receipt`, () => { assert.equal(raw.length, spec.bytes); assert.equal(digest(raw), spec.sha256); });
    check(`${spec.file}: stored original/source invariants and zero scientific calls`, () => {
        if (spec.kind === 'PROTEIN_LENGTH') {
            assert.equal(receipt.status, 'PASS'); assert.equal(receipt.runtimeCalls, 0); assert.equal(receipt.originalsUnchanged, true);
            for (const key of ['storedSourceFieldsEqual', 'storeBytesExact', 'controllerJsonEqual', 'historyJsonEqual']) assert.equal(receipt[key], true, key);
        } else {
            assert.equal(receipt.passed, true); assert.equal(receipt.noCurrentScientificRuntimeAssembled, true);
            assert.equal(receipt.currentFactoryReassembly, 'NOT_RUN'); assert.equal(receipt.rawStoreBytesAndSourceUnchanged, true);
            for (const key of ['forbiddenRuntimeCalls', 'evaluationCalls', 'sourceAdapterCalls']) assert.equal(receipt[key], 0, key);
        }
    });
    for (const row of receipt.runs || []) {
        const sample = byRun.get(row.runId);
        if (!sample && requestedFiles) continue; // Unselected archived runs are not frontend replays in explicit subset mode.
        check(`${spec.file}/${row.name || row.scenario}: receipt binds actual public and private raw source SHA`, () => {
            assert.ok(sample, 'reader returned an unknown run');
            assert.equal(row.publicSha256 || row.controllerExportRawSha256, sample.spec.sha256);
            assert.equal(row.privateSha256 || row.storedPrivateSha256, sample.privateRawSha256);
            if (spec.kind === 'PROTEIN_LENGTH') {
                for (const key of ['allStoreRunFieldsEqual', 'storePublicPrivateRequestBytesExact', 'controllerJsonEqual', 'serviceJsonEqual', 'fullExportBytesExact']) assert.equal(row[key], true, key);
            } else {
                for (const key of ['storedBytesUnchanged', 'serviceAndControllerJsonEqualsStored', 'persistedExecutionProfilePreserved']) assert.equal(row[key], true, key);
                assert.equal(row.sourceOriginals.status, 'RAW_BYTES_SHA256_VERIFIED'); assert.ok(row.sourceOriginals.retainedOriginalCount > 0);
            }
        });
        receiptRows.push({ file: spec.file, layer: spec.layer, runId: row.runId, publicRawSha256: row.publicSha256 || row.controllerExportRawSha256,
            privateRawSha256: row.privateSha256 || row.storedPrivateSha256 });
    }
}
const built = dependencies('esbuild').buildSync({
    absWorkingDir: project,
    stdin: { contents: `export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgResultsWorkbench';export {localBatchSavedRun,validateSavedRun} from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput';export {decisionDetails} from './src/pages/varvis/ChromosomeVisualization/utils/acmgDecisionDetails';export {default as DecisionSummary} from './src/pages/varvis/ChromosomeVisualization/components/EvidenceDecisionSummary.jsx';`, resolveDir: project, loader: 'jsx' },
    bundle: true, platform: 'node', format: 'cjs', packages: 'external', write: false, metafile: true,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'silent',
});
const compiled = new Module(path.join(project, 'scripts', 'central-final-memory.cjs')); compiled.filename = compiled.id;
compiled.paths = [path.join(path.resolve(sharedClient), 'node_modules')]; compiled._compile(built.outputFiles[0].text, compiled.filename);
const api = compiled.exports, React = dependencies('react'), { renderToStaticMarkup } = dependencies('react-dom/server');
const axios = dependencies('axios'), priorAdapter = axios.defaults.adapter, priorFetch = global.fetch;
let httpCalls = 0;
axios.defaults.adapter = async () => { httpCalls++; throw Error('Archived public replay cannot call HTTP or create analysis'); };
global.fetch = async () => { httpCalls++; throw Error('Archived public replay cannot call fetch'); };
const sourceFingerprints = Object.keys(built.metafile.inputs).filter(file => file !== '<stdin>').map(file => {
    const absolute = path.isAbsolute(file) ? file : path.resolve(project, file);
    return { file: path.relative(checkout, absolute).replaceAll('\\', '/'), sha256: digest(fs.readFileSync(absolute)) };
});
let backgroundCount = 0, rowCount = 0, savedInapplicabilityRows = 0;
try {
    for (const item of publicRuns) {
        const { spec, run } = item, rowsReport = [], backgrounds = [];
        check(`${spec.file}: current saved-run decoder and enabled criterion scope`, () => { assert.equal(api.localBatchSavedRun(run), true); assert.equal(api.validateSavedRun(run, run.analysisId), run); });
        for (const candidate of run.candidates) for (const background of api.savedBackgrounds(candidate)) {
            const key = `${spec.file}/${candidate.candidateId}/${background.index}`;
            const cards = api.backgroundEvidenceCards(candidate, run.enabledCriteria, run, background.index);
            const rows = cards.flatMap(card => card.entries.map(entry => ({ card, entry })));
            backgroundCount++; rowCount += rows.length;
            check(`${key}: exact saved background rows and four-class conservation`, () => {
                assert.deepEqual(cards.map(card => card.id), run.enabledCriteria); assert.equal(rows.length, run.enabledCriteria.length);
                assert.equal(new Set(cards.map(card => card.id)).size, cards.length);
                assert.equal(api.presentationCounts(rows).length, 4);
                assert.equal(api.presentationCounts(rows).reduce((sum, row) => sum + row.count, 0), rows.length);
                const sorted = api.sortResultRows(rows); assert.equal(sorted.length, rows.length); assert.ok(sorted.every(row => rows.includes(row)));
            });
            backgrounds.push({ candidateId: candidate.candidateId, index: background.index, hypothesisId: background.hypothesisId,
                rowCount: rows.length, counts: api.presentationCounts(rows).map(({ kind, count }) => ({ kind, count })) });
            for (const { card, entry } of rows) {
                const localSource = background.attempt.localBatch?.criteria?.find(row => row.criterionId === card.id);
                const source = localSource || entry.raw?.evaluation; // BA1/BS1 retain their native public evaluation records.
                const presentation = api.resultPresentation(entry), original = source && { status: localSource ? source.status : source.evaluationResult?.status,
                    appliedStrength: source.appliedStrength, reasonCode: localSource ? source.reasonCode : source.evaluationResult?.reasonCode };
                check(`${key}/${card.code}: original object/status/strength/reason and coherent current decoder`, () => {
                    assert.ok(source, 'no original saved row');
                    if (localSource) assert.equal(entry.savedCriterion, source);
                    else { assert.ok(frozen.has(entry.raw), 'native raw record must come from the public saved run'); assert.equal(entry.raw.evaluation, source); assert.equal(entry.evaluation, source); }
                    assert.ok(!entry.displayIssue, `${entry.summary}; ${(entry.presentationIssues || []).join(',')}`);
                    assert.equal(entry.evaluation.evaluationResult.status, original.status); assert.equal(entry.evaluation.evaluationResult.reasonCode, original.reasonCode);
                    assert.equal(entry.evaluation.appliedStrength, source.appliedStrength); assert.equal(presentation.reasonCode, original.reasonCode);
                    if (original.status === 'NOT_EVALUATED') {
                        const routing = localSource?.details?.applicabilityRouting;
                        const savedNotApplicable = routing?.status === 'NOT_APPLICABLE' && routing.reasonCode === original.reasonCode && routing.skipSpecializedAcquisition === true;
                        if (savedNotApplicable) {
                            savedInapplicabilityRows++; assert.equal(routing.status, 'NOT_APPLICABLE'); assert.equal(routing.reasonCode, source.reasonCode);
                            assert.equal(entry.evaluation.criterionApplicability?.status, 'NOT_APPLICABLE'); assert.equal(presentation.mainKind, 'NOT_APPLICABLE');
                        } else assert.equal(presentation.mainKind, 'NO_CONCLUSION');
                    }
                });
                let html = '';
                check(`${key}/${card.code}: actual EvidenceDecisionSummary SSR retains original decision and has no analysis action`, () => {
                    html = renderToStaticMarkup(React.createElement(api.DecisionSummary, { entry }));
                    assert.ok(html.includes('data-testid="evidence-decision-summary"'));
                    assert.ok(html.includes(original.status)); assert.ok(html.includes(original.appliedStrength));
                    if (typeof original.reasonCode === 'string') assert.ok(html.includes(original.reasonCode));
                    assert.ok(!html.includes('<button')); assert.equal(httpCalls, 0);
                });
                rowsReport.push({ candidateId: candidate.candidateId, hypothesisId: background.hypothesisId, criterionId: card.id, original,
                    current: { mainKind: presentation.mainKind, outcome: presentation.outcome.kind, adoption: presentation.adoption.kind, progress: presentation.progress.kind },
                    displayIssue: !!entry.displayIssue, issues: entry.presentationIssues || [], savedApplicabilityRouting: localSource?.details?.applicabilityRouting || null, scientificObjectSha256: digest(JSON.stringify(source || null)), ssrSha256: digest(html) });
            }
        }
        for (const expected of spec.criterionRows) {
            const source = run.candidates.find(c => c.candidateId === expected.candidateId)?.attempts.find(a => a.hypothesisId === expected.hypothesisId)?.localBatch?.criteria?.find(row => row.criterionId === expected.criterionId);
            check(`${spec.file}/${expected.criterionId}/${expected.candidateId}: independent expected row and exact current proof state`, () => {
                assert.ok(source); for (const field of ['status', 'appliedStrength', 'reasonCode']) assert.equal(source[field], expected[field], field);
                const facts = source.details.proteinLengthFacts || source.details.bp3Facts || source.details.deNovoFacts;
                if (spec.kind === 'PROTEIN_LENGTH') {
                    assert.equal(facts.sequenceMaterial.status, expected.sequenceMaterialStatus); assert.equal(facts.annotationCorrespondence, expected.annotationCorrespondence);
                    assert.equal(facts.adoptionProof.status, expected.adoptionProofStatus);
                } else { assert.equal(facts.adoptionProof.status, expected.proofStatus); assert.equal(facts.selectedCriterion, expected.selectedCriterion); }
            });
        }
        if (spec.kind === 'DE_NOVO_EXCLUSIVE_COMPLETION') check(`${spec.file}: event exclusivity and original aggregate completion retained`, () => {
            const selectedRows = rowsReport.filter(row => ['ACMG_AMP_2015:PS2', 'ACMG_AMP_2015:PM6'].includes(row.criterionId));
            const adopted = selectedRows.filter(row => row.current.mainKind === 'ADOPTED_MET');
            assert.ok(adopted.length <= 1, 'one shared event cannot supply both selected criteria'); assert.equal(run.status, spec.status);
            for (const row of selectedRows.filter(row => /^DENOVO_SHARED_EVENT_ASSIGNED_TO_/.test(row.original.reasonCode))) {
                assert.equal(row.original.status, 'NOT_EVALUATED'); assert.equal(row.original.appliedStrength, 'NONE'); assert.equal(row.current.mainKind, 'NO_CONCLUSION');
            }
            if (['opposite-only'].includes(spec.selection) || ['TISSUE_QUALITY', 'TISSUE_WRONG_TARGET'].includes(spec.scenario)) {
                assert.equal(run.status, 'COMPLETED_WITH_BLOCKERS'); assert.equal(adopted.length, 0);
            } else assert.equal(adopted.length, 1);
            if (spec.selection === 'all') assert.equal(run.status, 'COMPLETED_WITH_BLOCKERS');
            if (spec.scenario === 'GENERAL_ASSUMED' && spec.selection !== 'opposite-only') {
                const winner = rowsReport.find(row => row.criterionId === 'ACMG_AMP_2015:PM6'); assert.equal(winner.current.mainKind, 'ADOPTED_MET');
                const facts = run.candidates[0].attempts[0].localBatch.criteria.find(row => row.criterionId === 'ACMG_AMP_2015:PM6').details.deNovoFacts;
                assert.equal(facts.adoptionProof.status, 'NOT_APPLICABLE'); assert.equal(facts.adoptionProof.basis, 'EXECUTED_QUALIFIED_OBSERVATIONS');
                assert.equal(facts.calculationMethod, 'SVI_POINTS'); assert.equal(facts.selectedCriterion, 'PM6');
                assert.ok(facts.calculationBindings.every(binding => binding.executedCalculatorVersion === binding.runtimeCalculatorVersion));
            }
        });
        check(`${spec.file}: all public source pins, objects and saved bytes remain immutable`, () => { assert.equal(digest(JSON.stringify(run)), item.before); });
        products.push({ file: spec.file, scenario: spec.scenario, selection: spec.selection, originalStatus: run.status,
            inputSha256: spec.sha256, backgrounds, rows: rowsReport });
    }
} finally { axios.defaults.adapter = priorAdapter; global.fetch = priorFetch; }
check(requestedFiles ? 'explicit selected HTTP products, raw background scopes and corresponding reader bindings covered' : 'sixteen HTTP products, eighteen backgrounds, seventy-eight rows and all sixteen reader receipts covered', () => {
    const expectedBackgrounds = publicRuns.reduce((sum, {run}) => sum + run.candidates.reduce((count, candidate) => count + candidate.attempts.length, 0), 0);
    const expectedRows = publicRuns.reduce((sum, {run}) => sum + run.candidates.reduce((count, candidate) => count + candidate.attempts.length * run.enabledCriteria.length, 0), 0);
    const expectedInapplicability = publicRuns.reduce((sum, {run}) => sum + run.candidates.flatMap(candidate => candidate.attempts.flatMap(attempt => attempt.localBatch?.criteria || [])).filter(row => row.status === 'NOT_EVALUATED' && row.details?.applicabilityRouting?.status === 'NOT_APPLICABLE' && row.details.applicabilityRouting.reasonCode === row.reasonCode && row.details.applicabilityRouting.skipSpecializedAcquisition === true).length, 0);
    assert.equal(products.length, selectedSpecs.length); assert.equal(backgroundCount, expectedBackgrounds); assert.equal(rowCount, expectedRows);
    assert.equal(savedInapplicabilityRows, expectedInapplicability); assert.equal(receiptRows.length, selectedSpecs.length); assert.equal(httpCalls, 0);
    if (!requestedFiles) { assert.equal(products.length, 16); assert.equal(backgroundCount, 18); assert.equal(rowCount, 78); assert.equal(savedInapplicabilityRows, 12); assert.equal(receiptRows.length, 16); }
});
check('every raw input byte remains exact after current frontend replay', () => {
    for (const [file, expected] of pinned) { const raw = fs.readFileSync(path.join(inputs, file)); assert.equal(raw.length, expected.bytes); assert.equal(digest(raw), expected.sha256, file); }
});
const report = { schemaVersion: 'a9-central-final-view-replay-v1', testScriptSha256: digest(fs.readFileSync(__filename)), frontendHead: head, frontendBranch: branch, expectedHead: expectedHead || null,
    centralSourceCommit: contract.sourceCommit, coverage: { mode: requestedFiles ? 'EXPLICIT_PRODUCT_SUBSET' : 'DEFAULT_FULL_16', selectedFiles: selectedSpecs.map(spec => spec.file), originalFullReceiptPreserved: !!requestedFiles }, acceptance: failures.length ? 'FAILED_CURRENT_FRONTEND_REPLAY' : 'FRONTEND_ARCHIVE_REPLAY_PASSED_USER_ACCEPTANCE_PENDING',
    testLayer: 'CURRENT_FRONTEND_ADAPTERS_AND_ACTUAL_REACT_SSR_OVER_ARCHIVED_ACTUAL_HTTP_H2_PUBLIC_PRODUCTS',
    existingCentralLayer: contract.testLayer, newBackendOrSourceExecutions: 0, httpCalls, clinicalAdoption: 'NONE',
    privateUse: 'RAW_V_FILE_SHA_COMPARISON_ONLY_NO_PRIVATE_JSON_PARSE_OR_PUBLIC_FIELD_FILL',
    notRun: ['New Java/Python/business execution', 'Daily service or production environment', 'Actual browser interactions/visual acceptance', 'User actual downloads'],
    inputPins: [...pinned].map(([file, value]) => ({ file, ...value })), sourceFingerprints,
    totals: { products: products.length, backgrounds: backgroundCount, rows: rowCount, savedInapplicableNeRows: savedInapplicabilityRows, readerReceipts: readerSpecs.length,
        readerRunBindings: receiptRows.length, checks: checks.length, failures: failures.length }, checks, failures, receiptRows, products };
fs.mkdirSync(output, { recursive: true }); fs.writeFileSync(path.join(output, 'report.json'), `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report.totals)); if (failures.length) { console.error(JSON.stringify(failures.slice(0, 8), null, 2)); process.exitCode = 1; }
