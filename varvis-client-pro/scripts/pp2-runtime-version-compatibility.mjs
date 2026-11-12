import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';

// Read-only backend response fixture; never starts an analysis.
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
const original = JSON.parse(fs.readFileSync(process.argv[2], 'utf8').replace(/^\uFEFF/, ''));
assert.equal(api.localBatchScopeIssue(original), null, 'current backend scope');
assert.equal(api.currentBusinessCriteria(original).length, 18);
function versioned(version) {
    const copy = structuredClone(original);
    function walk(value) {
        if (!value || typeof value !== 'object') return;
        if (Object.hasOwn(value, 'pp2GeneralVersion')) value.pp2GeneralVersion = version;
        Object.values(value).forEach(walk);
    }
    walk(copy);
    return copy;
}
assert.equal(api.localBatchScopeIssue(versioned('1.0.0')), null, 'old scope remains readable');
assert.equal(api.localBatchScopeIssue(versioned('1.1.0')), null, 'new scope supported');
assert.ok(api.localBatchScopeIssue(versioned('9.9.9')), 'unknown runtime still rejected');
const inconsistent = versioned('1.1.0');
inconsistent.executionProfile.moduleBindings.pp2.runtimeBinding.pp2GeneralVersion = '1.0.0';
assert.ok(api.localBatchScopeIssue(inconsistent), 'inconsistent module bindings still rejected');
console.log('PASS: live scope, 18 criteria, old/new runtime, unknown and inconsistent bindings');
