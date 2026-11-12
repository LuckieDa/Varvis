import frontendModuleUrl from './frontend-module-url.cjs';
// Local transport/SSR checks with synthetic data; no browser, service or scientific resource execution.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { buildSync } from 'esbuild';

const [destination, savedFixture] = process.argv.slice(2);
if (!destination) throw new Error('Supply an isolated output directory outside the frontend module');
const project = path.resolve(fileURLToPath(new URL('../', import.meta.url)));
const output = path.resolve(destination);
if (output === project || output.startsWith(project + path.sep)) throw new Error('Use an isolated output directory');
fs.mkdirSync(output, { recursive: true });
const raw = fs.readFileSync(path.join(project, 'src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js'), 'utf8');
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`));
const ids = ['PM2', 'PP3', 'BP4', 'PS1', 'PM5'].map((code) => `ACMG_AMP_2015:${code}`);
const declaration = 'ordinary-local-computational-batch1-v1';
const profile = {
    profileId: 'acmg-local-computational-batch1-v1', profileVersion: '1.0.0',
    contextPreparation: 'SELECTED_LOCAL_BATCH1_V1', requiredDeclaration: declaration, enabledCriteria: ids,
    formalEnabled: false, classificationEnabled: false, automaticTrigger: false,
    manifestSha256: 'a'.repeat(64), moduleBindings: { pm2: { status: 'UNAVAILABLE' }, prediction: { status: 'CONFIGURED' }, reference: { status: 'UNREVIEWED' } },
};
const clone = (value) => JSON.parse(JSON.stringify(value));
const candidate = {
    candidateId: 'candidate-1', rank: 1, geneSymbol: 'TEST', catalog: { hgncId: 'HGNC:1' },
    transcriptAnnotations: [{ transcriptAccession: 'ENST00000000001.1', geneSymbol: 'TEST', source: 'VEP', version: '116', status: 'COMPLETED' }],
    recommendation: { recommendationId: 'synthetic-rec-1', hypothesisId: 'synthetic-hypothesis-1', policyId: 'ORDINARY_CATALOG_AND_AM_IDENTITY',
        policyVersion: '1.0.0', policyHash: 'b'.repeat(64), generatedAt: '2026-10-10T08:00:00Z', executionSourceSha256: 'c'.repeat(64),
        stableRelationshipKey: 'synthetic-relationship', recommendedAnnotationId: 'synthetic-annotation',
        relationships: [{ hgncId: 'HGNC:1', mondoId: 'MONDO:0000001', moi: 'AD', stableRelationshipKey: 'synthetic-relationship', selectable: true }],
        annotations: [{ annotationId: 'synthetic-annotation', annotationIndex: 0, identityCompatible: true }] },
};
const options = { analysisId: 'synthetic-local-batch', sampleId: 'TEST_ONLY', requestedN: 2, selectedCount: 2,
    requiredDeclaration: declaration, enabledCriteria: ids, executionProfile: profile,
    candidateIds: ['candidate-1', 'candidate-2'], candidates: [candidate, { ...clone(candidate), rank: 2, candidateId: 'candidate-2' }] };
options.candidates[1].recommendation.hypothesisId = 'synthetic-hypothesis-2';
options.candidates[1].recommendation.annotations = [];
options.candidates[1].recommendation.recommendedAnnotationId = null;
const attempt = { hypothesisId: 'synthetic-hypothesis-1', context: { contextId: 'synthetic-context-1' }, localBatch: {
    schemaVersion: 'local-batch1-attempt-v1', candidateId: 'candidate-1', hypothesisId: 'synthetic-hypothesis-1', contextId: 'synthetic-context-1',
    criteria: ids.map((criterionId, index) => ({ criterionId, status: index === 1 ? 'MET' : index === 2 ? 'NOT_MET' : 'NOT_EVALUATED',
        appliedStrength: index === 1 ? 'SUPPORTING' : 'NONE', reasonCode: `TEST_ONLY_${index}`, reason: `合成独立结果 ${index}`,
        details: { fact: index === 1 ? '0.888' : null, scientificApproval: 'NOT_ESTABLISHED' } })),
} };
const run = { analysisId: options.analysisId, runId: 'synthetic-run-1', status: 'COMPLETED_WITH_BLOCKERS', dataClass: 'SYNTHETIC',
    requestedN: 1, selectedCount: 1, candidateIds: ['candidate-1'], candidates: [{ candidateId: 'candidate-1', rank: 1, attempts: [attempt] }],
    declarationVersion: declaration, executionProfile: profile, enabledCriteria: ids,
    localBatch: { moduleSummaries: { marker: 'PUBLIC_SUMMARIES_MUST_SURVIVE', sha256: 'd'.repeat(64), modulePointer: '/prediction/results/0', nested: { unknown: null } } },
};
let checks = 0;
const test = (name, check) => {
    try { check(); checks++; console.log(`PASS ${name}`); }
    catch (error) { console.error(`FAIL ${name}: ${error.message}`); process.exit(1); }
};
test('configured five-clause scope accepts missing science without granting adoption', () => {
    assert.deepEqual(api.currentBusinessCriteria(options), ids);
    assert.equal(api.validateInputOptions(options, options.analysisId), options);
    assert.equal(api.localBatchScopeIssue(options), null);
});
test('profile version flags declaration criteria and every module binding fail closed', () => {
    for (const mutate of [
        (o) => { o.executionProfile.profileId = 'daily-profile'; },
        (o) => { o.executionProfile.profileVersion = '2.0.0'; },
        (o) => { o.executionProfile.contextPreparation = 'POPULATION_CONTEXT_ONLY_V1'; },
        (o) => { o.requiredDeclaration = 'ordinary-ui-v3'; },
        (o) => { o.executionProfile.requiredDeclaration = 'ordinary-ui-v3'; },
        (o) => { o.enabledCriteria.reverse(); },
        (o) => { o.executionProfile.enabledCriteria.pop(); },
        (o) => { o.executionProfile.manifestSha256 = null; },
        (o) => { o.executionProfile.profileId = 'unknown'; o.executionProfile.contextPreparation = 'PREDICTION_CONTEXT_ONLY_V1';
            o.executionProfile.requiredDeclaration = 'ordinary-ui-v3'; o.requiredDeclaration = 'ordinary-ui-v3'; },
        ...['formalEnabled', 'classificationEnabled', 'automaticTrigger'].map((key) => (o) => { o.executionProfile[key] = true; }),
        ...['pm2', 'prediction', 'reference'].map((key) => (o) => { delete o.executionProfile.moduleBindings[key]; }),
    ]) {
        const broken = clone(options); mutate(broken);
        assert.deepEqual(api.currentBusinessCriteria(broken), []);
        assert.throws(() => api.buildSimplifiedCaseDeclaration(broken, broken.analysisId, api.prepareSimplifiedInputs(broken)), /本地证据/);
    }
});
test('interpretation version requires policy binding and preserves legacy history', () => {
    const updated = clone(options); updated.executionProfile.profileVersion = '1.1.0';
    assert.ok(api.localBatchScopeIssue(updated));
    updated.executionProfile.interpretationContextPolicy = { policyId: 'LOCAL_ALLELE_INTERPRETATION_CONTEXT', policyVersion: '1.0.0' };
    assert.equal(api.localBatchScopeIssue(updated), null);
    updated.executionProfile.interpretationContextPolicy.policyVersion = 'unknown';
    assert.ok(api.localBatchScopeIssue(updated));
    assert.equal(api.localBatchScopeIssue(options), null);
});
test('different ranking context is never implicitly selected and preserves explicit choice', () => {
    const updated = clone(options); const rec = updated.candidates[0].recommendation;
    rec.stableRelationshipKey = null;
    rec.relationships[0].requiresExplicitInterpretationContext = true;
    const input = api.prepareSimplifiedInputs(updated);
    assert.equal(input['candidate-1'].stableRelationshipKey, '');
    assert.equal(api.buildSimplifiedCaseDeclaration(updated, updated.analysisId, input).hypotheses.length, 0);
    input['candidate-1'].stableRelationshipKey = rec.relationships[0].stableRelationshipKey;
    assert.equal(api.buildSimplifiedCaseDeclaration(updated, updated.analysisId, input).hypotheses[0].hypothesis.moi, 'AD');
    const context = { policyId: 'LOCAL_ALLELE_INTERPRETATION_CONTEXT', policyVersion: '1.0.0',
        rankingMoi: 'AUTOSOMAL_RECESSIVE', interpretationMoi: 'AUTOSOMAL_DOMINANT', requiresExplicitSelection: true, diagnosisClaimed: false };
    assert.match(api.localInterpretationContextText(context), /排序背景：常染色体隐性/);
    assert.match(api.localInterpretationContextText(context), /本次解释背景：常染色体显性/);
    assert.match(api.localInterpretationContextText(context), /不证明患者/);
});

test('ordinary builder preserves complete selection and leaves incomplete candidate without invented context', () => {
    const input = api.prepareSimplifiedInputs(options);
    const value = api.buildSimplifiedCaseDeclaration(options, options.analysisId, input, undefined, 'SYNTHETIC');
    assert.equal(value.declarationVersion, declaration);
    assert.equal(value.hypotheses.length, 1);
    assert.equal(value.hypotheses[0].hypothesis.candidateId, 'candidate-1');
    assert.equal(value.hypotheses[0].transcript.selectionPolicy, 'ORDINARY_CATALOG_AND_AM_IDENTITY@1.0.0');
    assert.match(value.hypotheses[0].transcript.selectionReason, /explicitly selected local evidence scope/);
    assert.ok(!Object.hasOwn(value, 'executionProfile'));
    assert.ok(!Object.hasOwn(value, 'localBatch'));
    assert.deepEqual(Object.keys(value).sort(), ['topN', 'declarationVersion', 'sampleId', 'dataClass', 'patient', 'hypotheses', 'recommendations'].sort());
    input['candidate-1'].recommendationId = 'stale';
    assert.throws(() => api.buildSimplifiedCaseDeclaration(options, options.analysisId, input), /推荐身份/);
});
test('all missing contexts may be explicitly saved as blockers without fabricating a hypothesis', () => {
    const input = Object.fromEntries(options.candidates.map((row) => [row.candidateId, { recommendationId: row.recommendation.recommendationId, stableRelationshipKey: '', annotationId: '' }]));
    assert.deepEqual(api.buildSimplifiedCaseDeclaration(options, options.analysisId, input).hypotheses, []);
});
test('new declarations cannot borrow a daily profile or another declaration version', () => {
    const daily = clone(options); daily.executionProfile = { contextPreparation: 'POPULATION_CONTEXT_ONLY_V1', enabledCriteria: ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1'] };
    daily.enabledCriteria = daily.executionProfile.enabledCriteria; delete daily.requiredDeclaration;
    assert.deepEqual(api.currentBusinessCriteria(daily), daily.enabledCriteria);
    assert.equal(api.localBatchScopeClaim(daily), false);
    assert.throws(() => api.buildCaseDeclaration(daily, daily.analysisId, api.automaticCaseForm(daily.analysisId, 'SYNTHETIC', declaration), {}), /独立执行范围/);
    assert.throws(() => api.buildCaseDeclaration(options, options.analysisId, api.automaticCaseForm(options.analysisId), {}), /独立执行范围/);
});
test('five statuses remain independent and display adaptation cannot mutate originals', () => {
    const original = JSON.stringify(run);
    const cards = api.evidenceCards(run.candidates[0], ids, undefined, undefined, run);
    assert.deepEqual(cards.map((card) => card.id), ids);
    assert.deepEqual(cards.map((card) => card.entries[0].evaluation.evaluationResult.status), ['NOT_EVALUATED', 'MET', 'NOT_MET', 'NOT_EVALUATED', 'NOT_EVALUATED']);
    assert.equal(cards[1].entries[0].evaluation.appliedStrength, 'SUPPORTING');
    assert.equal(JSON.stringify(run), original);
});
test('missing background retains null identity and five explicit unevaluated clauses', () => {
    const missing = clone(attempt); missing.hypothesisId = null; missing.context = null;
    missing.localBatch.hypothesisId = null; missing.localBatch.contextId = null;
    missing.localBatch.criteria.forEach((row) => { row.status = 'NOT_EVALUATED'; row.appliedStrength = 'NONE'; });
    const row = { candidateId: 'candidate-1', attempts: [missing] };
    assert.equal(api.localBatchAttemptIssue(row, missing), null);
    assert.equal(api.evidenceCards(row, ids, undefined, undefined, run).length, 5);
    missing.localBatch.hypothesisId = '';
    assert.ok(api.localBatchAttemptIssue(row, missing));
});
test('missing duplicate criteria and shared identity errors hide only the damaged attempt', () => {
    for (const mutate of [
        (a) => { a.localBatch.criteria.pop(); }, (a) => { a.localBatch.criteria[0] = a.localBatch.criteria[1]; },
        (a) => { a.localBatch.criteria[0].criterionId = 'ACMG_AMP_2015:BA1'; },
        (a) => { a.localBatch.candidateId = 'wrong'; }, (a) => { a.localBatch.contextId = 'wrong'; },
        (a) => { a.localBatch.hypothesisId = 'wrong'; },
    ]) {
        const broken = clone(attempt); mutate(broken);
        const row = { candidateId: 'candidate-1', attempts: [attempt, broken] };
        const cards = api.evidenceCards(row, ids, undefined, undefined, run);
        assert.equal(cards[1].entries[0].evaluation.evaluationResult.status, 'MET');
        assert.equal(cards[1].entries[1].evaluation, undefined);
        assert.equal(cards[1].entries[1].displayIssue, true);
        assert.ok(api.localBatchAttemptIssue(row, broken));
    }
});
test('invalid status or strength hides that evidence only, never rewrites sibling results', () => {
    for (const [index, edit] of [
        [0, c => { c.appliedStrength = 'SUPPORTING'; }],
        [1, c => { delete c.appliedStrength; }],
        [1, c => { c.appliedStrength = 'UNKNOWN'; }],
        [1, c => { c.appliedStrength = 'STAND_ALONE'; }],
        [2, c => { c.status = 'NO_RECORD'; }],
    ]) {
        const broken = clone(attempt); edit(broken.localBatch.criteria[index]);
        const before = JSON.stringify(broken);
        const row = { candidateId: 'candidate-1', attempts: [attempt, broken] };
        const cards = api.evidenceCards(row, ids, undefined, undefined, run);
        cards.forEach((card, i) => {
            assert.equal(card.entries[0].displayIssue, false);
            assert.equal(card.entries[1].displayIssue, i === index);
            if (i === index) assert.equal(card.entries[1].evaluation, undefined);
            else assert.deepEqual(card.entries[1].evaluation, card.entries[0].evaluation);
        });
        assert.ok(api.localBatchAttemptIssue(row, broken)); // Full-batch audit still detects the error.
        assert.equal(JSON.stringify(broken), before);
    }
});
test('history remains self scoped and retry is explicit same-analysis same-scope only', () => {
    assert.equal(api.localBatchSavedRun(run), true);
    assert.equal(api.localBatchRetryEligible(run, options.analysisId), true);
    assert.equal(api.localBatchRetryEligible(run, 'another-analysis'), false);
    assert.equal(api.localBatchRetryEligible({ ...run, status: 'COMPLETED' }, options.analysisId), false);
    const broken = clone(run); broken.executionProfile.formalEnabled = true;
    assert.equal(api.localBatchSavedRun(broken), false);
    assert.deepEqual(api.evidenceCards(broken.candidates[0], ids, undefined, undefined, broken), []);
});

if (process.argv.includes('--contracts-only')) {
    console.log(`${checks}/${checks} local batch contract groups passed (SSR not requested).`);
    process.exit(0);
}
const bundle = path.join(output, 'local-batch1-ssr.cjs');
buildSync({ stdin: { contents: `
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import Matrix from './src/pages/varvis/ChromosomeVisualization/components/AcmgEvidenceMatrix.jsx';
import { SavedResult } from './src/pages/varvis/ChromosomeVisualization/components/OrdinaryAcmgEvaluationPanel.jsx';
export const renderSaved = (run, criteria = ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1']) => renderToStaticMarkup(React.createElement(SavedResult, { run, criteria, draftChanged: true }));
export const renderMatrix = run => renderToStaticMarkup(React.createElement(Matrix, { candidate: run.candidates[0], enabledCriteria: run.enabledCriteria, run }));
export const downloadSaved = run => {
 const tree = SavedResult({ run, criteria: [], draftChanged: true }); let click;
 const visit = element => { if (!React.isValidElement(element)) return;
  if (element.props.children === '下载本次原始结果 JSON') click = element.props.onClick;
  React.Children.forEach(element.props.children, visit); };
 visit(tree); if (!click) throw new Error('Saved download control missing'); click();
};
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', outfile: bundle,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' });
const { renderSaved, renderMatrix, downloadSaved } = createRequire(import.meta.url)(bundle);
const downloadedJson = (saved) => {
    const original = { Blob: globalThis.Blob, document: globalThis.document, setTimeout: globalThis.setTimeout,
        create: URL.createObjectURL, revoke: URL.revokeObjectURL };
    let blob, clicked = false;
    const anchor = { click() { clicked = true; } };
    try {
        globalThis.Blob = class { constructor(parts, options) { this.parts = parts; this.type = options.type; } };
        globalThis.document = { createElement(tag) { assert.equal(tag, 'a'); return anchor; } };
        globalThis.setTimeout = (fn) => fn();
        URL.createObjectURL = (value) => { blob = value; return 'blob:synthetic-download'; };
        URL.revokeObjectURL = (url) => assert.equal(url, 'blob:synthetic-download');
        downloadSaved(saved);
        assert.equal(clicked, true); assert.equal(anchor.download, `acmg-${saved.runId}.json`);
        assert.equal(blob.type, 'application/json');
        return JSON.parse(blob.parts.join(''));
    } finally {
        globalThis.Blob = original.Blob; globalThis.setTimeout = original.setTimeout;
        if (original.document === undefined) delete globalThis.document; else globalThis.document = original.document;
        URL.createObjectURL = original.create; URL.revokeObjectURL = original.revoke;
    }
};
test('actual saved component shows all five clauses under daily current settings and retains download control', () => {
    const original = JSON.stringify(run);
    const html = renderSaved(run);
    for (const code of ['PM2', 'PP3', 'BP4', 'PS1', 'PM5']) assert.ok(html.includes(code));
    assert.match(html, /历史本地模式结果，保留保存时的证据范围/);
    assert.match(html, /下载本次原始结果 JSON/);
    assert.match(html, /可导出本次公开结果、来源摘要及模块引用/);
    assert.match(html, /实际强度：SUPPORTING/);
    assert.equal(JSON.stringify(run), original);
    fs.writeFileSync(path.join(output, 'saved-synthetic.html'), html);
    fs.writeFileSync(path.join(output, 'saved-synthetic.json'), JSON.stringify(run, null, 2));
});
test('actual damaged matrix labels corruption without claiming a negative scientific result', () => {
    const broken = clone(run); broken.candidates[0].attempts[0].localBatch.criteria[1].appliedStrength = 'NONE';
    const html = renderMatrix(broken);
    assert.match(html, /保存结果待核查/);
    assert.doesNotMatch(html, /已评价 · 满足/);
    fs.writeFileSync(path.join(output, 'damaged-synthetic.html'), html);
});
test('legacy population history retains its saved rules under the new five-clause instance', () => {
    const legacy = { analysisId: options.analysisId, runId: 'synthetic-old-population', status: 'COMPLETED_WITH_BLOCKERS',
        executionProfile: { profileId: 'acmg-ba1-bs1-local-v1' }, enabledCriteria: ['ACMG_AMP_2015:BA1', 'ACMG_AMP_2015:BS1'],
        candidates: [{ candidateId: 'candidate-1', rank: 1, attempts: [] }] };
    const html = renderSaved(legacy, ids);
    assert.match(html, />BA1<\/p>/);
    assert.match(html, />BS1<\/p>/);
    assert.doesNotMatch(html, />(PM2（支持级）|PP3|BP4|PS1|PM5)<\/p>/);
    fs.writeFileSync(path.join(output, 'saved-legacy-in-new-scope.html'), html);
});
test('existing download callback exports the entire public JSON including damaged blocks and unknown fields', () => {
    const broken = clone(run); broken.executionProfile.profileVersion = 'unknown';
    broken.candidates[0].attempts[0].localBatch.criteria.pop(); broken.providerCalls = null; broken.evaluatorCalls = null;
    const original = JSON.stringify(broken);
    assert.equal(api.localBatchSavedRun(broken), false);
    assert.deepEqual(downloadedJson(broken), broken);
    assert.equal(JSON.stringify(broken), original);
});
test('project-local PM2 keeps its scope in rendering and complete export', () => {
    const local = clone(run); const c = local.candidates[0].attempts[0].localBatch.criteria[0];
    c.status = 'MET'; c.appliedStrength = 'SUPPORTING'; c.reasonCode = 'PM2_PROJECT_LOCAL_HIT_MET';
    c.reason = '本地 EXOMES 满足条件；未建立全库或临床采用结论。';
    c.details.populationScope = { schemaVersion: 'pm2-project-local-scope-v1', dataset: 'EXOMES', release: '4.1',
        interpretation: 'LOCAL_DATASET_CRITERION_ONLY', wholeGnomadConclusion: false, clinicalAdoption: 'NOT_ESTABLISHED',
        externalSpecificationEquivalence: 'NOT_ESTABLISHED', allowedQueryOutcome: 'HIT', allowNoRecord: false,
        allowMissingFafSingleton: false, allowWholeDatabaseHomozygoteRequirement: false, reviewOwnership: 'IMPLEMENTER_TECHNICAL_REVIEW' };
    assert.equal(api.localBatchAttemptIssue(local.candidates[0], local.candidates[0].attempts[0]), null);
    assert.match(renderSaved(local), /仅 gnomAD 4.1 EXOMES/);
    assert.deepEqual(downloadedJson(local), local);
    for (const mutate of [
        (v) => { delete v.details.populationScope; },
        (v) => { v.details.populationScope.wholeGnomadConclusion = true; },
        (v) => { v.details.populationScope.dataset = 'JOINT'; },
        (v) => { v.details.populationScope.clinicalAdoption = 'APPROVED'; },
        (v) => { v.reasonCode = 'PM2_PROJECT_LOCAL_HIT_NOT_MET'; },
    ]) {
        const broken = clone(local); mutate(broken.candidates[0].attempts[0].localBatch.criteria[0]);
        assert.ok(api.localBatchAttemptIssue(broken.candidates[0], broken.candidates[0].attempts[0]));
        assert.deepEqual(downloadedJson(broken), broken);
    }
});
test('prediction explanation renders protein and splicing separately without changing NE or export', () => {
    const saved = clone(run); const c = saved.candidates[0].attempts[0].localBatch.criteria[2];
    c.status = 'NOT_EVALUATED'; c.reasonCode = 'BP4_REQUIRED_MECHANISM_UNRESOLVED'; c.reason = '缺少剪接资格';
    c.details.predictionMechanisms = { schemaVersion: 'prediction-mechanism-summary-v1', purpose: 'EXPLANATION_ONLY',
        route: 'REVEL_EXISTING_SUBSET', primaryMechanism: 'PROTEIN_MISSENSE', fallbackAfterScore: false,
        allowedPp3Mechanisms: ['PROTEIN_MISSENSE', 'SPLICING'], requiredBp4Mechanisms: ['PROTEIN_MISSENSE', 'SPLICING'],
        mechanisms: [{ mechanism: 'PROTEIN_MISSENSE', model: 'REVEL', status: 'CALIBRATED', direction: 'BENIGN',
            appliedStrength: 'SUPPORTING', reasonCode: 'EXISTING_PAIRED_CLAUSE_ASSESSMENT', reason: '蛋白结果不能替代剪接',
            observationStatus: 'UNKNOWN', observationReasonCode: 'OBSERVATION_NOT_RECORDED', scoreMode: 'UNKNOWN',
            observationQualificationReasonCode: 'EXISTING_PAIRED_CLAUSE_ASSESSMENT' },
        { mechanism: 'SPLICING', model: 'SPLICEAI', status: 'NOT_EVALUATED', direction: 'UNRESOLVED', appliedStrength: 'NONE',
            reasonCode: 'SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED', observationStatus: 'HIT',
            observationReasonCode: 'SPLICE_RAW_RECORDS_OBTAINED', scoreMode: 'MASKED',
            observationQualificationReasonCode: 'SPLICE_PARAMETERS_INCOMPATIBLE', observationQualificationReason: 'masked 不能直接代入 RAW 校准' }] };
    const before = JSON.stringify(saved), html = renderSaved(saved);
    assert.match(html, /错义蛋白机制/); assert.match(html, /剪接辅助观测/); assert.match(html, /MASKED/);
    assert.match(html, /不根据分数切换工具/); assert.match(html, /不等于正常 RNA/);
    const card = api.evidenceCards(saved.candidates[0], ids, undefined, undefined, saved)[2];
    assert.equal(card.entries[0].evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.deepEqual(downloadedJson(saved), saved); assert.equal(JSON.stringify(saved), before);
    fs.writeFileSync(path.join(output, 'prediction-mechanisms.html'), html);
    const general = clone(saved), generalEntry = general.candidates[0].attempts[0].localBatch.criteria[2];
    generalEntry.details.predictionMechanisms.route = 'GENERAL_LOCAL_REVEL';
    const generalHtml = renderSaved(general);
    assert.match(generalHtml, /通用本地 REVEL（研究范围）/);
    assert.match(generalHtml, /最高强度 Strong/);
    assert.match(generalHtml, /PM1 合用须核对合计最多 4 点/);
    assert.equal(generalEntry.status, 'NOT_EVALUATED');
    assert.deepEqual(downloadedJson(general), general);
    fs.writeFileSync(path.join(output, 'general-revel-synthetic.html'), generalHtml);
    const alpha = clone(general), alphaEntry = alpha.candidates[0].attempts[0].localBatch.criteria[2];
    alphaEntry.details.predictionMechanisms.route = 'GENERAL_LOCAL_ALPHAMISSENSE';
    alphaEntry.details.predictionMechanisms.mechanisms[0].model = 'ALPHAMISSENSE';
    const alphaHtml = renderSaved(alpha);
    assert.match(alphaHtml, /通用本地 AlphaMissense（研究范围）/);
    assert.match(alphaHtml, /PP3 最高 Strong、BP4 蛋白机制最高 Moderate/);
    assert.equal(alphaEntry.status, 'NOT_EVALUATED');
    assert.deepEqual(downloadedJson(alpha), alpha);
    fs.writeFileSync(path.join(output, 'general-alphamissense-synthetic.html'), alphaHtml);
    const amMismatch = clone(saved), amEntry = amMismatch.candidates[0].attempts[0].localBatch.criteria[2];
    amEntry.details.predictionMechanisms.route = 'EXISTING_DEVELOPMENT_CORE';
    Object.assign(amEntry.details.predictionMechanisms.mechanisms[0], { model: 'ALPHAMISSENSE', status: 'NOT_EVALUATED',
        direction: 'UNRESOLVED', appliedStrength: 'NONE', reasonCode: 'ALPHAMISSENSE_TRANSCRIPT_VERSION_MISMATCH',
        reason: '本地 AlphaMissense 有该等位基因记录，但转录本版本与当前选择不一致；尚无完整序列等价证明。',
        observationStatus: 'HIT', observationQualificationReasonCode: 'ALPHAMISSENSE_TRANSCRIPT_VERSION_MISMATCH' });
    const amHtml = renderSaved(amMismatch);
    assert.match(amHtml, /有该等位基因记录/); assert.match(amHtml, /转录本版本与当前选择不一致/);
    assert.equal(amEntry.status, 'NOT_EVALUATED'); assert.deepEqual(downloadedJson(amMismatch), amMismatch);
    fs.writeFileSync(path.join(output, 'am-version-conflict-synthetic.html'), amHtml);
    const materialSaved = clone(saved), materialEntry = materialSaved.candidates[0].attempts[0].localBatch.criteria[2];
    materialEntry.details.predictionMechanisms.transcriptMaterial = { purpose: 'TECHNICAL_MATERIAL_ONLY', scientificApproval: false,
        status: 'READY', transcript: 'ENST000001.4', modelOrigin: 'GENCODE_GTF_PLUS_REFERENCE', reasonCode: 'EXACT_MODEL_REFERENCE_AND_VEP_AGREE' };
    assert.match(renderSaved(materialSaved), /本地 GENCODE 与参考序列/);
    assert.match(renderSaved(materialSaved), /仍须满足预测规则条件/);
    assert.deepEqual(downloadedJson(materialSaved), materialSaved);
    materialEntry.details.predictionMechanisms.transcriptMaterial.scientificApproval = true;
    assert.match(api.predictionMechanismDetails(materialEntry).find(([label]) => label === '转录本材料')[1], /格式不完整/);
    assert.equal(materialEntry.status, c.status);
    for (const mutate of [
        (s) => { s.purpose = 'SCIENTIFIC_APPROVAL'; },
        (s) => { s.fallbackAfterScore = true; },
        (s) => { s.mechanisms[1].direction = 'BENIGN'; },
        (s) => { s.mechanisms[0].model = 'UNREGISTERED_TOOL'; },
        (s) => { s.mechanisms[0].appliedStrength = 'NONE'; },
    ]) {
        const broken = clone(saved); const entry = broken.candidates[0].attempts[0].localBatch.criteria[2];
        mutate(entry.details.predictionMechanisms);
        assert.match(api.predictionMechanismDetails(entry)[0][1], /格式不完整/);
        assert.deepEqual(downloadedJson(broken), broken);
        assert.equal(api.evidenceCards(broken.candidates[0], ids, undefined, undefined, broken)[2].entries[0].evaluation.evaluationResult.status, 'NOT_EVALUATED');
    }
    delete c.details.predictionMechanisms;
    assert.deepEqual(api.predictionMechanismDetails(c), []);
});
test('PM2 absence scope displays detection facts and rejects contradictory saved claims', () => {
    const prose = '本地 EXOMES 未收录该精确 SNV，且该位置总体检测量与区域条件满足本地缺失规则。';
    assert.equal(api.reasonHelp(prose), prose, 'A success explanation containing SNV must not become a missense-only rejection');
    const saved = clone(run), c = saved.candidates[0].attempts[0].localBatch.criteria[0];
    c.status = 'MET'; c.appliedStrength = 'SUPPORTING'; c.reasonCode = 'PM2_PROJECT_LOCAL_ABSENCE_MET';
    c.reason = '本地外显子组未检出且位置检测量充分';
    c.details.populationScope = { schemaVersion: 'pm2-project-local-scope-v2', dataset: 'EXOMES', release: '4.1',
        interpretation: 'LOCAL_DATASET_CRITERION_ONLY', wholeGnomadConclusion: false, clinicalAdoption: 'NOT_ESTABLISHED',
        externalSpecificationEquivalence: 'NOT_ESTABLISHED', allowedQueryOutcome: 'HIT_OR_NO_RECORD', allowNoRecord: true,
        allowMissingFafSingleton: false, allowWholeDatabaseHomozygoteRequirement: false, reviewOwnership: 'IMPLEMENTER_TECHNICAL_REVIEW' };
    c.details.populationFacts = { schemaVersion: 'pm2-local-facts-v1', dataset: 'EXOMES', release: '4.1', queryStatus: 'NO_RECORD',
        detection: { status: 'HIT', adjAn: '100000', stratum: 'ALL_RELEASE_SAMPLES_ADJ', ancestryStratified: false,
            regionFlags: { outside_broad_capture_region: false, outside_ukb_capture_region: false,
                outside_broad_calling_region: false, outside_ukb_calling_region: false } } };
    assert.equal(api.localBatchAttemptIssue(saved.candidates[0], saved.candidates[0].attempts[0]), null);
    const html = renderSaved(saved); assert.match(html, /位置检测量 AN/); assert.match(html, /100000/); assert.match(html, /不包含祖源分组证明/);
    assert.deepEqual(downloadedJson(saved), saved);
    for (const mutate of [
        (v) => { v.details.populationFacts.queryStatus = 'HIT'; },
        (v) => { v.details.populationFacts.detection.status = 'NO_RECORD'; },
        (v) => { v.details.populationFacts.detection.adjAn = '0'; },
        (v) => { v.details.populationFacts.detection.regionFlags.outside_broad_capture_region = true; },
        (v) => { delete v.details.populationFacts.detection.regionFlags.outside_ukb_calling_region; },
        (v) => { v.details.populationFacts.detection.ancestryStratified = true; },
        (v) => { v.details.populationFacts.release = '4.0'; },
        (v) => { v.details.populationScope.allowNoRecord = false; },
        (v) => { v.status = 'NOT_MET'; v.appliedStrength = 'NONE'; },
    ]) {
        const broken = clone(saved); mutate(broken.candidates[0].attempts[0].localBatch.criteria[0]);
        assert.ok(api.localBatchAttemptIssue(broken.candidates[0], broken.candidates[0].attempts[0]));
        assert.deepEqual(downloadedJson(broken), broken);
    }
    fs.writeFileSync(path.join(output, 'pm2-absence.html'), html);
});
test('missing context shows saved specific blockers without changing criterion or export', () => {
    const saved = clone(run), row = saved.candidates[0], attempt = row.attempts[0];
    attempt.context = null;
    attempt.blockers = [{ status: 'CONTEXT_NOT_READY', reasonCode: 'CANDIDATE_RELATIONSHIP_MOI_CONFLICT', field: 'recommendation.relationships' }];
    const criterion = attempt.localBatch.criteria[1];
    Object.assign(criterion, { status: 'NOT_EVALUATED', appliedStrength: 'NONE', reasonCode: 'EXPLICIT_INPUT_MISSING', reason: '缺少输入' });
    const before = JSON.stringify(saved);
    const entry = api.evidenceCards(row, ids, undefined, undefined, saved)[1].entries[0];
    assert.ok(entry.reasons.includes('CANDIDATE_RELATIONSHIP_MOI_CONFLICT'));
    assert.match(renderSaved(saved), /候选遗传模式与该基因/);
    assert.equal(entry.evaluation.evaluationResult.status, 'NOT_EVALUATED');
    assert.deepEqual(downloadedJson(saved), saved); assert.equal(JSON.stringify(saved), before);
    // Do not let a stray legacy blocker describe a completed criterion with a verified context.
    attempt.context = { contextId: 'synthetic-context-1' };
    assert.ok(!api.evidenceCards(row, ids, undefined, undefined, saved)[1].entries[0].reasons.includes('CANDIDATE_RELATIONSHIP_MOI_CONFLICT'));
});
test('interpretation context blockers distinguish unsupported scope, source and sample calls', () => {
    for (const [code, explanation] of [
        ['INTERPRETATION_MOI_SCOPE_UNSUPPORTED', /不能据此切换 X 连锁显隐性/],
        ['INTERPRETATION_NATIVE_AUTOSOMAL_SNV_REQUIRED', /软件支持范围/],
        ['INTERPRETATION_ANALYSIS_VCF_CALL_REQUIRED', /发布来源/],
        ['INTERPRETATION_ANALYSIS_VCF_CALL_INVALID', /无效字段/],
        ['INTERPRETATION_CALLED_DIPLOID_GT_REQUIRED', /完整二倍体/],
        ['INTERPRETATION_TARGET_ALT_NOT_PRESENT', /未包含目标 ALT/],
        ['INTERPRETATION_PUBLISHED_SOURCE_REQUIRED', /正式发布来源/],
    ]) assert.match(api.reasonHelp(code), explanation);
});
test('reference capture distinguishes lookup facts from approval and preserves complete export', () => {
    const saved = clone(run), criterion = saved.candidates[0].attempts[0].localBatch.criteria.find((c) => c.criterionId === ids[4]);
    criterion.details.referenceFacts = { schemaVersion: 'missense-reference-facts-v1', version: '1.0.0',
        purpose: 'EXPLANATION_ONLY', scientificApprovalGranted: false, criterionId: ids[4], searchStatus: 'INCOMPLETE',
        searchReasonCode: 'REFERENCE_COMPARATOR_FACTS_RECORDED', discoveredCandidates: 3, comparableReferences: 1,
        excludedCandidates: 1, unresolvedCandidates: 1, criterionMatches: 1,
        references: [{ variationId: '1234', proteinRelation: 'SAME_RESIDUE_DIFFERENT_AMINO_ACID', sourceAssertionCount: 2 }] };
    const original = JSON.stringify(saved), html = renderSaved(saved);
    assert.match(html, /参考查找有缺项/); assert.match(html, /ClinVar VariationID 1234/); assert.match(html, /不等于合格致病参考/);
    assert.deepEqual(downloadedJson(saved), saved); assert.equal(JSON.stringify(saved), original);
    fs.writeFileSync(path.join(output, 'reference-facts.html'), html);
    for (const change of [s => { s.scientificApprovalGranted = true; }, s => { s.criterionMatches = 0; },
        s => { s.searchStatus = 'COMPLETE'; }, s => { s.references[0].variationId = 'D:/private'; },
        s => { s.criterionId = ids[3]; }, s => { s.discoveredCandidates = 2; }]) {
        const bad = clone(criterion); change(bad.details.referenceFacts);
        assert.match(api.referenceFactDetails(bad)[0][1], /格式不完整/);
    }
    delete criterion.details.referenceFacts;
    assert.deepEqual(api.referenceFactDetails(criterion), []);
});

test('missing reference approval asks for reviewed materials rather than resource retry', () => {
    const saved = clone(run), criterion = saved.candidates[0].attempts[0].localBatch.criteria.find((c) => c.criterionId === ids[4]);
    criterion.reasonCode = 'REFERENCE_APPROVED_MATERIAL_UNAVAILABLE';
    const original = JSON.stringify(saved), html = renderSaved(saved);
    assert.match(api.reasonHelp(criterion.reasonCode), /审核材料/);
    assert.match(html, /重复运行不能补足审核/);
    assert.doesNotMatch(html, /资源未就绪或执行失败/);
    assert.deepEqual(downloadedJson(saved), saved); assert.equal(JSON.stringify(saved), original);
    assert.match(api.reasonHelp('REFERENCE_COMPARATOR_RESOURCE_UNAVAILABLE'), /资源未就绪或执行失败/);
});

if (savedFixture) test('backend saved fixture uses this exact display transport contract and retains public snapshots', () => {
    const saved = JSON.parse(fs.readFileSync(savedFixture, 'utf8'));
    const original = JSON.stringify(saved);
    api.validateSavedRun(saved, saved.analysisId);
    assert.equal(api.localBatchSavedRun(saved), true);
    for (const row of saved.candidates) for (const a of row.attempts) assert.equal(api.localBatchAttemptIssue(row, a, saved.enabledCriteria, saved.executionProfile), null);
    const html = renderSaved(saved);
    assert.match(html, /PM2 \/ PP3 \/ BP4 \/ PS1 \/ PM5/);
    assert.deepEqual(downloadedJson(saved), saved);
    assert.equal(JSON.stringify(saved), original);
    fs.writeFileSync(path.join(output, 'saved-backend.html'), html);
    fs.writeFileSync(path.join(output, 'saved-backend-export.json'), JSON.stringify(saved, null, 2));
});
test('six-criterion PM4 profile is explicit and old profiles cannot borrow its result', () => {
    const six = clone(run); six.executionProfile.profileVersion = '1.2.0';
    six.executionProfile.interpretationContextPolicy = { policyId: 'LOCAL_ALLELE_INTERPRETATION_CONTEXT', policyVersion: '1.0.0' };
    six.executionProfile.moduleBindings.pm4 = { runtimeBinding: { pm4Configured: true } };
    six.enabledCriteria.push('ACMG_AMP_2015:PM4'); six.executionProfile.enabledCriteria.push('ACMG_AMP_2015:PM4');
    const c = { criterionId: 'ACMG_AMP_2015:PM4', status: 'MET', appliedStrength: 'MODERATE',
        reasonCode: 'PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET', reason: '合成受审非重复示例', details: { proteinLengthFacts: {
            schemaVersion: 'pm4-general-facts-v1', policyVersion: '1.0.0', scope: 'PROJECT_LOCAL_SIMPLE_INFRAME',
            clinicalValidationCompleted: false, nonRepeatFromNoHit: false, regionState: 'NON_REPEAT_SUPPORTED',
            shortRepeatCandidate: false, transcript: 'ENST000001.1', protein: 'ENSP000001.1', netAminoAcidChange: 1,
            queryRange: { start: 2, end: 3, coordinateSystem: 'REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE' },
        } } };
    six.candidates[0].attempts[0].localBatch.criteria.push(c);
    assert.equal(api.localBatchScopeIssue(six), null); assert.equal(api.pm4ScopeClaim(six), false);
    assert.equal(api.currentBusinessCriteria(six).length, 6);
    assert.equal(api.localBatchAttemptIssue(six.candidates[0], six.candidates[0].attempts[0], six.enabledCriteria), null);
    assert.match(renderSaved(six), /PM4/); assert.match(renderSaved(six), /完整非重复范围有受审依据/);
    assert.doesNotMatch(api.reasonHelp(c.reasonCode), /本候选未评价/);
    assert.doesNotMatch(api.reasonHelp('PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET'), /本候选未评价/);
    assert.deepEqual(downloadedJson(six), six);
    // Current facts separate an actually completed no-hit query from missing work.
    const current = clone(six); const item = current.candidates[0].attempts[0].localBatch.criteria[5];
    const facts = item.details.proteinLengthFacts;
    facts.factsVersion = '1.1.0'; facts.repeatObservation = 'NO_REPEAT_OBSERVED';
    assert.equal(api.localBatchAttemptIssue(current.candidates[0], current.candidates[0].attempts[0], current.enabledCriteria), null);
    assert.match(renderSaved(current), /区域查询及短重复扫描已完成/);
    item.status = 'NOT_EVALUATED'; item.appliedStrength = 'NONE';
    item.reasonCode = 'PM4_NON_REPEAT_METHOD_OR_CURATED_COVERAGE_NOT_QUALIFIED'; facts.regionState = 'UNKNOWN';
    assert.match(renderSaved(current), /不自动授予非重复资格/); assert.deepEqual(downloadedJson(current), current);
    for (const value of ['INCOMPLETE', 'SHORT_REPEAT_CANDIDATE', 'KNOWN_REPEAT']) {
        facts.repeatObservation = value; facts.shortRepeatCandidate = value === 'SHORT_REPEAT_CANDIDATE';
        assert.equal(api.localBatchAttemptIssue(current.candidates[0], current.candidates[0].attempts[0], current.enabledCriteria), null);
    }
    for (const mutate of [
        (f) => { f.factsVersion = '2.0.0'; },
        (f) => { f.repeatObservation = 'NON_REPEAT_PROVED'; },
        (f) => { f.shortRepeatCandidate = true; },
        (f) => { f.regionState = 'REPEAT_OVERLAP'; },
        (f) => { f.repeatObservation = 'INCOMPLETE'; },
    ]) {
        const bad = clone(six); const f = bad.candidates[0].attempts[0].localBatch.criteria[5].details.proteinLengthFacts;
        f.factsVersion = '1.1.0'; f.repeatObservation = 'NO_REPEAT_OBSERVED'; mutate(f);
        assert.ok(api.localBatchAttemptIssue(bad.candidates[0], bad.candidates[0].attempts[0], bad.enabledCriteria));
    }
    for (const mutate of [
        (v) => { v.executionProfile.profileVersion = '1.1.0'; },
        (v) => { delete v.executionProfile.moduleBindings.pm4; },
    ]) { const bad = clone(six); mutate(bad); assert.ok(api.localBatchScopeIssue(bad)); }
    for (const mutate of [
        (v) => { v.appliedStrength = 'STRONG'; },
        (v) => { v.details.proteinLengthFacts.shortRepeatCandidate = true; },
        (v) => { v.details.proteinLengthFacts.regionState = 'UNKNOWN'; },
        (v) => { delete v.details.proteinLengthFacts; },
        (v) => { v.details.proteinLengthFacts.queryRange.end = 0; },
        (v) => { v.details.proteinLengthFacts.netAminoAcidChange = 0; },
        (v) => { v.status = 'NOT_MET'; v.appliedStrength = 'NONE'; v.reasonCode = 'PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET'; },
    ]) { const bad = clone(six); mutate(bad.candidates[0].attempts[0].localBatch.criteria[5]);
        assert.ok(api.localBatchAttemptIssue(bad.candidates[0], bad.candidates[0].attempts[0], bad.enabledCriteria)); }
    fs.writeFileSync(path.join(output, 'pm4-six.html'), renderSaved(six));
    const operational = clone(six); const op = operational.candidates[0].attempts[0].localBatch.criteria[5];
    op.reasonCode = 'PM4_PROJECT_LOCAL_OPERATIONAL_NON_REPEAT_MET';
    Object.assign(op.details.proteinLengthFacts, { policyVersion: '1.1.0', factsVersion: '1.2.0',
        nonRepeatPolicy: 'ANNOTATION_SEQUENCE_V1', qualificationBasis: 'OPERATIONAL_INFERENCE',
        nonRepeatFromNoHit: true, absoluteNonRepeatProof: false, pvs1CombinedAdoptionSupported: false,
        regionState: 'OPERATIONAL_NON_REPEAT_SUPPORTED', repeatObservation: 'NO_REPEAT_OBSERVED' });
    assert.equal(api.localBatchAttemptIssue(operational.candidates[0], operational.candidates[0].attempts[0], operational.enabledCriteria), null);
    assert.match(renderSaved(operational), /按通用项目政策推定非重复/);
    assert.match(renderSaved(operational), /不需要逐蛋白审核文件/);
    assert.deepEqual(downloadedJson(operational), operational);
    for (const mutate of [
        (v) => { v.details.proteinLengthFacts.nonRepeatPolicy = 'REVIEWED_ONLY'; },
        (v) => { v.details.proteinLengthFacts.qualificationBasis = 'REVIEWED_COVERAGE'; },
        (v) => { v.details.proteinLengthFacts.absoluteNonRepeatProof = true; },
        (v) => { v.details.proteinLengthFacts.pvs1CombinedAdoptionSupported = true; },
        (v) => { v.details.proteinLengthFacts.nonRepeatFromNoHit = false; },
        (v) => { v.details.proteinLengthFacts.repeatObservation = 'INCOMPLETE'; },
        (v) => { v.details.proteinLengthFacts.policyVersion = '1.0.0'; },
        (v) => { v.details.proteinLengthFacts.factsVersion = '1.1.0'; },
        (v) => { v.status = 'NOT_EVALUATED'; v.appliedStrength = 'NONE'; },
        (v) => { v.reasonCode = 'PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET'; },
    ]) {
        const bad = clone(operational); mutate(bad.candidates[0].attempts[0].localBatch.criteria[5]);
        assert.ok(api.localBatchAttemptIssue(bad.candidates[0], bad.candidates[0].attempts[0], bad.enabledCriteria));
        assert.deepEqual(downloadedJson(bad), bad);
    }
    fs.writeFileSync(path.join(output, 'pm4-operational.html'), renderSaved(operational));
});
test('composed BP7 and PM4 scope preserves both bindings and refuses partial or historical authorization', () => {
    const seven = clone(run);
    seven.executionProfile.profileVersion = '1.3.0-bp7-pm4';
    seven.executionProfile.interpretationContextPolicy = { policyId: 'LOCAL_ALLELE_INTERPRETATION_CONTEXT', policyVersion: '1.0.0' };
    seven.executionProfile.moduleBindings.bp7 = { runtimeBinding: { method: 'BP7_SELECTED_SHARED_PREDICTION',
        version: '1.0.0', coreVersion: '1.0.0', positionVersion: '1.0.0', predictionDependencyVersion: '1.0.0',
        policySha256: 'c'.repeat(64), experimentSnapshotId: 'test-only-catalog', experimentSourceStatus: 'AVAILABLE', clinicalApproval: false } };
    seven.executionProfile.moduleBindings.pm4 = { runtimeBinding: { pm4Configured: true } };
    for (const code of ['BP7', 'PM4']) {
        const id = `ACMG_AMP_2015:${code}`;
        seven.enabledCriteria.push(id); seven.executionProfile.enabledCriteria.push(id);
        for (const candidate of seven.candidates) for (const attempt of candidate.attempts)
            attempt.localBatch.criteria.push({ criterionId: id, status: 'NOT_EVALUATED', appliedStrength: 'NONE',
                reasonCode: 'SYNTHETIC_MATERIAL_UNAVAILABLE', reason: 'Test-only missing material', details: {} });
    }
    assert.equal(api.localBatchScopeIssue(seven), null);
    assert.equal(api.currentBusinessCriteria(seven).length, 7);
    for (const candidate of seven.candidates) for (const attempt of candidate.attempts)
        assert.equal(api.localBatchAttemptIssue(candidate, attempt, seven.enabledCriteria, seven.executionProfile), null);
    assert.match(renderSaved(seven), /PM2 \/ PP3 \/ BP4 \/ PS1 \/ PM5 \/ BP7 \/ PM4/);
    assert.deepEqual(downloadedJson(seven), seven);
    for (const mutate of [v => { delete v.executionProfile.moduleBindings.bp7; },
        v => { delete v.executionProfile.moduleBindings.pm4; }, v => { v.enabledCriteria.pop(); },
        v => { v.executionProfile.profileVersion = '1.2.0-bp7'; }, v => { v.executionProfile.profileVersion = '1.2.0'; }]) {
        const bad = clone(seven); mutate(bad); assert.ok(api.localBatchScopeIssue(bad));
    }
    assert.equal(api.localBatchRetryEligible(seven, seven.analysisId, run), false);
    const unbound = { enabledCriteria: seven.enabledCriteria };
    assert.equal(api.localBatchScopeClaim(unbound), true); assert.ok(api.localBatchScopeIssue(unbound));
    fs.writeFileSync(path.join(output, 'composed-bp7-pm4.html'), renderSaved(seven));
});
console.log(`${checks}/${checks} local batch contract/component groups passed; synthetic transport/SSR checks, no browser acceptance.`);
