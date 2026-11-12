import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
let good = 0, rejected = 0;
for (const name of ['SINGLE_STOP', 'SINGLE_FRAME', 'UTR_NMD', 'SINGLE_GAP', 'UTR_GAP']) {
    const row = JSON.parse(fs.readFileSync(path.join(process.argv[2], `${name}.json`))), original = JSON.stringify(row);
    assert.ok(h.pvs1SelectedDetails(row).length > 0, name);
    for (const change of [f => { f.methodVersion = '1.0.0'; }, f => { f.transcriptTopology.version = 'bad'; },
        f => { f.transcriptTopology.exonCount = 0; }]) {
        const bad = structuredClone(row); change(bad.details.pvs1Facts);
        assert.deepEqual(h.pvs1SelectedDetails(bad), [], name); rejected++;
    }
    if (row.status === 'MET') {
        const bad = structuredClone(row); bad.details.pvs1Facts.policyStrengthCap = 'SUPPORTING';
        assert.deepEqual(h.pvs1SelectedDetails(bad), []); rejected++;
    }
    const bad = structuredClone(row), f = bad.details.pvs1Facts;
    if (name.startsWith('SINGLE')) {
        f.lastJunctionCdsPosition = 0;
        assert.deepEqual(h.pvs1SelectedDetails(bad), []); rejected++;
    } else if (row.status === 'MET') {
        f.transcriptTopology.utrApplicabilityBound = false;
        assert.deepEqual(h.pvs1SelectedDetails(bad), []); rejected++;
    }
    assert.equal(JSON.stringify(row), original); good++;
}
if (process.argv[3]) {
    const installed = JSON.parse(fs.readFileSync(process.argv[3]));
    for (const row of installed.publicCriteria) {
        const before = JSON.stringify(row), details = h.pvs1SelectedDetails(row);
        assert.ok(details.some(([label]) => label === '自动序列与转录本供给'));
        assert.ok(details.some(([label]) => label === '来源资格方式'));
        if (row.status === 'MET') {
            const bad = structuredClone(row); bad.details.pvs1Facts.materialSupply.status = 'UNAVAILABLE';
            assert.deepEqual(h.pvs1SelectedDetails(bad), []); rejected++;
        }
        assert.equal(JSON.stringify(row), before); good++;
    }
}
console.log(`PASS: ${good} complete public projections; ${rejected} malformed/overclaimed records rejected; original JSON preserved.`);
