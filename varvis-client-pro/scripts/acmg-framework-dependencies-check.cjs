const assert = require('node:assert/strict');
const path = require('node:path');
const { buildSync } = require('esbuild');
const project = path.resolve(__dirname, '..');
const source = buildSync({ stdin: { contents: `export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgBackground.js'; export * from './src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js';`, resolveDir: project }, bundle: true, platform: 'node', format: 'cjs', write: false }).outputFiles[0].text;
const compiled = { exports: {} };
new Function('require', 'module', 'exports', source)(require, compiled, compiled.exports);
const api = compiled.exports;
const relation = { stableRelationshipKey: 'r1', selectable: true, diseaseLabel: 'Disease A', hgncId: 'HGNC:1', mondoId: 'MONDO:0000001', moi: 'AD' };
const candidate = { candidateId: 'v1', geneSymbol: 'GENE', catalog: { hgncId: 'HGNC:1' }, recommendation: { relationships: [relation] } };
const context = { contextId: 'c1', geneSymbol: 'GENE', hgncId: 'HGNC:1', mondoId: relation.mondoId, publishedInheritance: ['AD'], sources: [] };
for (const status of ['CONFLICT']) {
    const input = [{ ...context, inheritanceResolution: { status, automaticSelectionAllowed: false } }];
    const before = JSON.stringify(input);
    const choices = api.backgroundChoices(candidate, input);
    assert.equal(choices.length, 1);
    assert.equal(choices[0].moi, '');
    assert.equal(choices[0].inheritanceResolution.status, status);
    assert.equal(JSON.stringify(input), before);
    const other = api.backgroundChoices(candidate, [{ ...input[0], mondoId: 'MONDO:0000002' }]);
    assert.equal(other.find(c => c.mondoId === relation.mondoId).moi, 'AD');
}
assert.equal(api.backgroundChoices(candidate, [context]).length, 1, 'Historical source remains readable');
assert.equal(api.backgroundChoices(candidate, [{ ...context, publishedInheritance: [], inheritanceResolution: { status: 'UNMAPPED' } }])[0].moi, 'AD', 'Unmapped supplementary terminology must not erase independently verified inheritance');
assert.equal(api.backgroundChoices(candidate, [{ ...context, publishedInheritance: [] }])[0].moi, 'AD', 'Exact independent known source remains usable');
const expertContext = { ...context, publishedInheritance: ['AD', 'AR'], sources: [
    { kind: 'EXACT_PUBLISHED_EXPERT_CONTEXT', matches: ['AD', 'AR'].map(mode => ({ mode,
        source: { sourceUrl: 'https://erepo.genome.network/evrepo/api/summary/classifications', panel: 'TEST VCEP' } })) },
] };
const expertChoices = api.backgroundChoices(candidate, [expertContext]);
assert.deepEqual(expertChoices.map(c => c.moi).sort(), ['AD', 'AR'], 'Exact alternative inheritance remains separate');
assert.equal(api.backgroundSourceLinks(expertChoices[0]).length, 2);
assert.ok(api.backgroundSourceLinks(expertChoices[0]).every(link => link.label.startsWith('遗传方式来源：')));
assert.deepEqual(api.backgroundSourceLinks({ sources: [{ sourceUrl: 'javascript:alert(1)' }] }), []);
assert.equal(api.backgroundChoices({ ...candidate, geneSymbol: 'OTHER' }, [expertContext]).length, 1, 'Other gene must not adopt expert background');
assert.deepEqual(api.functionalReferenceDetails(undefined), []);
const failure = api.functionalReferenceDetails({ purpose: 'EXPLANATION_ONLY', status: 'UNAVAILABLE', failureStage: 'HISTORY_INDEX', reasonCode: 'EFFECT_SOURCE_READ_TEMPORARY_FAILURE', privatePath: 'SECRET' });
assert.ok(failure.some(row => row[1] === '历史资料读取'));
assert.ok(failure.some(row => row[1] === 'EFFECT_SOURCE_READ_TEMPORARY_FAILURE'));
assert.ok(!JSON.stringify(failure).includes('SECRET'));
assert.ok(api.functionalReferenceDetails({ purpose: 'EXPLANATION_ONLY', status: 'VERIFIED_SAVED_REFERENCES' })[0][1].includes('不代表已穷尽'));
console.log('PASS: background conflict isolation, historical compatibility and dependency explanations');
