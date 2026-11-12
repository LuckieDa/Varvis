// Offline source-bound result transport and rendering; no browser or model calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { buildSync } = require('esbuild');
const project = path.resolve(__dirname, '..');
const output = buildSync({ stdin: { contents: `
import React from 'react';import {renderToStaticMarkup} from 'react-dom/server';
import {Pm3SavedFacts} from './src/pages/varvis/ChromosomeVisualization/components/Pm3LiteraturePanel.jsx';
export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';
export const render=f=>renderToStaticMarkup(<Pm3SavedFacts value={f}/>);
`, resolveDir: project, loader: 'jsx' }, bundle: true, platform: 'node', format: 'cjs', write: false,
    define: { 'process.env.NODE_ENV': '"production"' }, logLevel: 'warning' }).outputFiles[0].text;
const compiled = { exports: {} }; new Function('require', 'module', 'exports', output)(require, compiled, compiled.exports);
const api = compiled.exports;
const folder = process.argv[2]; if (!folder) throw new Error('Provide immutable business fixture folder');
const saved = JSON.parse(fs.readFileSync(path.join(folder, 'saved.json'), 'utf8'));
const options = JSON.parse(fs.readFileSync(path.join(folder, 'options.json'), 'utf8'));
const before = JSON.stringify(saved), clone = x => JSON.parse(JSON.stringify(x));
assert.equal(api.localBatchScopeIssue(options), null);
assert.ok(api.localBatchSavedRun(saved)); api.validateSavedRun(saved, saved.analysisId);
assert.ok(api.currentBusinessCriteria(options).includes('ACMG_AMP_2015:PM3'));
for (const c of saved.candidates) for (const a of c.attempts) {
    assert.equal(api.localBatchAttemptIssue(c, a, saved.enabledCriteria, saved.executionProfile), null);
}
const broken = clone(saved); broken.executionProfile.moduleBindings.pm3.version = 'unknown';
assert.ok(api.localBatchScopeIssue(broken));
for (const version of ['v1', 'v2']) {
    const compatible = clone(saved), modules = compatible.executionProfile.moduleBindings;
    for (const binding of [modules.pm3, ...['pm2', 'prediction', 'reference'].map(k => modules[k].runtimeBinding.pm3)]) {
        binding.version = `pm3-selected-literature-${version}`;
        binding.assessmentVersion = `pm3-literature-assessment-${version}`;
    }
    assert.equal(api.localBatchScopeIssue(compatible), null);
    assert.ok(api.localBatchSavedRun(compatible));
    for (const binding of [modules.pm3, ...['pm2', 'prediction', 'reference'].map(k => modules[k].runtimeBinding.pm3)]) {
        binding.assessmentVersion = `pm3-literature-assessment-${version === 'v1' ? 'v2' : 'v1'}`;
    }
    assert.ok(api.localBatchScopeIssue(compatible), 'mixed assessment versions must fail');
}
const facts = { schemaVersion: 'pm3-selected-facts-v1', clinicalApproval: false, scope: 'SOURCE_CHECKED_LOCAL_RESEARCH',
    excludedObservations: [], excludedTasks: [], policySelections: [{ schemaVersion: 'pm3-specification-context-v1', status: 'SELECTED', policy: 'CLINGEN_SVI_PM3_2019_V1' }],
    assessment: { diseaseId: 'MONDO:0000001', calculation: { status: 'MET', strength: 'SUPPORTING', quarterPoints: 2,
        contributions: [{ observationId: 'one', proposedQuarterPoints: 2, awardedQuarterPoints: 2, reason: 'PM3_OBSERVATION_COUNTED', sourceReferences: ['PMC123#table:1:R2'] }] } },
    caseFacts: [{ pmcid: 'PMC123', caseLabel: '1', reportedFacts: [{ field: 'zygosity', citation: { quote: '<script>homozygous</script>', unitId: 'table:1:R2' } }] }] };
const row = { criterionId: 'ACMG_AMP_2015:PM3', status: 'MET', appliedStrength: 'SUPPORTING', reasonCode: 'PM3_QUALIFIED_LITERATURE_THRESHOLD_MET', reason: '文献积分满足', details: { pm3Facts: facts } };
assert.ok(api.pm3LiteratureCriterionValid(row));
for (const mutate of [r => r.appliedStrength = 'VERY_STRONG', r => r.details.pm3Facts.assessment.calculation.quarterPoints = 16,
    r => r.details.pm3Facts.assessment.calculation.contributions[0].awardedQuarterPoints = -1,
    r => r.details.pm3Facts.policySelections[0].status = 'UNSUPPORTED']) {
    const bad = clone(row); mutate(bad); assert.equal(api.pm3LiteratureCriterionValid(bad), false);
}
const html = api.render(facts); assert.ok(html.includes('0.5')); assert.ok(html.includes('table:1:R2'));
assert.ok(html.includes('&lt;script&gt;')); assert.ok(!html.includes('<script>')); assert.equal(before, JSON.stringify(saved));
const unresolved=clone(facts);delete unresolved.assessment;
unresolved.excludedObservations=[{pmcid:'PMC123',caseLabel:'1',reasonCode:'PM3_CASE_SEMANTICS_UNRESOLVED'}];
unresolved.caseFacts[0].interpretationStatus='CASE_SEMANTICS_UNRESOLVED';
unresolved.caseFacts[0].unverifiedFindings=[{field:'targetVariant',reasonCode:'PM3_VARIANT_COLUMN_MEANING_UNRESOLVED',columnHeading:'Mutation 2AV'}];
unresolved.paperCoverage=[{pmcid:'PMC123',chunkCount:9,completedReviewChunks:[2],materialAssessments:[
    {materialKind:'AGGREGATE_OR_METHODS',citation:{quote:'Mutation frequencies',unitId:'aggregate-table'}}]}];
const pendingHtml=api.render(unresolved);
assert.ok(pendingHtml.includes('软件尚未核实同病例'));assert.ok(pendingHtml.includes('table:1:R2'));
assert.ok(pendingHtml.includes('未用于计分'));assert.ok(!pendingHtml.includes('<script>'));
assert.ok(pendingHtml.includes('Mutation 2AV'));
assert.ok(pendingHtml.includes('未取得病例不等于整篇没有病例'));assert.ok(pendingHtml.includes('aggregate-table'));
unresolved.paperCoverage[0].issues=['DENOVO_MODEL_HTTP_500'];
unresolved.paperCoverage[0].unreviewedExtraction={cases:[{caseLabel:'not-reviewed',facts:[{field:'targetVariant',sourceValue:'c.1A>G',citation:{quote:'c.1A>G',unitId:'pending-source'}}]}]};
const failedReview=api.render(unresolved);
assert.ok(failedReview.includes('HTTP 500'));assert.ok(failedReview.includes('不是原文资料缺失'));
assert.ok(failedReview.includes('待模型复核，未进入判定'));assert.ok(failedReview.includes('pending-source'));
unresolved.paperCoverage[0].unreviewedExtraction={cases:[]};
assert.ok(!api.render(unresolved).includes('本轮没有取得可展示的病例事实'));
console.log('PASS PM3 saved business identity, scope, per-attempt contract, scoring integrity, citations and SSR escaping');
