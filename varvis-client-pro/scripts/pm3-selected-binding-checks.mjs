import frontendModuleUrl from './frontend-module-url.cjs';
// Transport compatibility only. No evaluator, network, or business database calls.
import fs from 'node:fs';
import assert from 'node:assert/strict';
const source=fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js',import.meta.url),'utf8');
const {validateSavedRun,localBatchScopeIssue}=await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`));
const original=JSON.parse(fs.readFileSync(process.argv[2]));
validateSavedRun(original,original.analysisId);
assert.equal(localBatchScopeIssue(original),null);
const current=structuredClone(original);
function update(value) {
    if(!value || typeof value!=='object')return;
    if(value.version==='pm3-selected-literature-v2')value.version='pm3-selected-literature-v3';
    for(const child of Object.values(value))update(child);
}
update(current);
validateSavedRun(current,current.analysisId);
assert.equal(localBatchScopeIssue(current),null);
const wrongAssessment=structuredClone(current);
wrongAssessment.executionProfile.moduleBindings.pm3.assessmentVersion='pm3-literature-assessment-v1';
assert.ok(localBatchScopeIssue(wrongAssessment));
const wrongProvider=structuredClone(current);
wrongProvider.executionProfile.moduleBindings.pm2.runtimeBinding.pm3.version='pm3-selected-literature-v2';
assert.ok(localBatchScopeIssue(wrongProvider));
const unknown=structuredClone(current);
unknown.executionProfile.moduleBindings.pm3.version='pm3-selected-literature-v999';
assert.ok(localBatchScopeIssue(unknown));
console.log('PASS five cases: saved v2; v3; mismatched assessment/provider/unknown version rejected.');
