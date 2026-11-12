const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const { buildSync } = require('esbuild');
const project = path.resolve(__dirname, '..');
const source = buildSync({ stdin: { contents: `export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';`, resolveDir: project }, bundle: true, platform: 'node', format: 'cjs', write: false }).outputFiles[0].text;
const compiled = { exports: {} }; new Function('require','module','exports',source)(require,compiled,compiled.exports);
const api = compiled.exports;
const candidate = { candidateId: 'one', recommendation: { recommendationId:'rec', relationships: [
    { stableRelationshipKey:'a', selectable:true, diseaseLabel:'A', moi:'AR' },
] } };
const options = { candidates:[candidate] };
assert.equal(api.prepareAutomaticInputs(options).one.stableRelationshipKey,'a');
candidate.recommendation.relationships.push({ stableRelationshipKey:'b', selectable:false });
assert.equal(api.prepareAutomaticInputs(options).one.stableRelationshipKey,'a');
candidate.recommendation.relationships.push({ stableRelationshipKey:'c', selectable:true });
assert.equal(api.prepareAutomaticInputs(options).one.stableRelationshipKey,'');
assert.equal(api.automaticBackgroundSummary(options,api.prepareAutomaticInputs(options))[0].status,'SELECTION_REQUIRED');
const prior = { one:{ recommendationId:'rec', stableRelationshipKey:'c', annotationId:'', affectedStatus:'UNKNOWN' } };
assert.equal(api.prepareAutomaticInputs(options,prior).one.stableRelationshipKey,'c');
candidate.recommendation.recommendationId='new';
assert.equal(api.prepareAutomaticInputs(options,prior).one.stableRelationshipKey,'');
candidate.recommendation.relationships=[];
assert.equal(api.automaticBackgroundSummary(options,api.prepareAutomaticInputs(options))[0].status,'BACKGROUND_UNAVAILABLE');
console.log('Automatic background: 7 checks passed; unique hypothesis prepared, ambiguous and missing backgrounds distinguished.');
if (process.argv[2]) {
    const actual=JSON.parse(fs.readFileSync(process.argv[2],'utf8').replace(/^\uFEFF/,''));
    const old=api.prepareSimplifiedInputs(actual), next=api.prepareAutomaticInputs(actual);
    const failed={runId:'r',analysisId:'a',status:'FAILED',resultsAvailable:false,retryExecutionProfile:actual.executionProfile};
    assert.equal(api.localBatchRetryEligible(failed,'a',actual),true);
    assert.equal(api.localBatchRetryEligible(failed,'another',actual),false);
    assert.equal(api.localBatchRetryEligible({...failed,retryExecutionProfile:{}},'a',actual),false);
    console.log('Verified failed-run retry scope: 3 checks passed.');
    console.log(JSON.stringify({before:api.automaticBackgroundSummary(actual,old),after:api.automaticBackgroundSummary(actual,next)},null,2));
}
