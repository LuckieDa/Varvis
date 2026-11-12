import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
let accepted = 0, rejected = 0;
for (const name of ['MET', 'MET_SECOND', 'OUTSIDE', 'SOURCE_CONFLICT', 'POPULATION', 'UNAPPROVED', 'GENERIC', 'NEW_CONDITION', 'NOT_APPLICABLE']) {
    const raw = JSON.parse(fs.readFileSync(path.join(process.argv[2], `${name}.json`)));
    const criterion = { criterionId: 'ACMG_AMP_2015:BP3', status: raw.status, reasonCode: raw.reasonCode,
        appliedStrength: raw.evaluation.appliedStrength, details: { bp3Facts: raw.publicSummary } };
    const before = JSON.stringify(criterion);
    assert.ok(h.bp3FunctionalDetails(criterion).length > 0, name);
    assert.ok(!h.reasonHelp(raw.reasonCode).includes(raw.reasonCode), name);
    assert.equal(JSON.stringify(criterion), before); accepted++;
    if (raw.status === 'NOT_EVALUATED') continue;
    for (const mutate of [c => { c.details.bp3Facts.functionAbsenceInferredFromNoHit = true; },
        c => { c.appliedStrength = 'STRONG'; }, c => { c.details.bp3Facts.clauseSha256 = 'bad'; },
        c => { c.details.bp3Facts.executionTranscript = 'NM_000000.1'; },
        c => { c.details.bp3Facts.functionalAnnotationSearchPerformed = true; },
        c => { c.details.bp3Facts.selectedRule.start = 2.5; },
        c => { c.details.bp3Facts.queryRange.coordinateSystem = 'GENOMIC'; },
        c => { c.reasonCode = 'BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION'; },
        c => { c.details.bp3Facts.policyVersion = '1.0.0'; }]) {
        const bad = structuredClone(criterion); mutate(bad); assert.deepEqual(h.bp3FunctionalDetails(bad), []); rejected++;
    }
    const bad = structuredClone(criterion);
    if (raw.status === 'MET') bad.details.bp3Facts.queryRange.end = bad.details.bp3Facts.selectedRule.end + 1;
    else { bad.details.bp3Facts.queryRange.start = bad.details.bp3Facts.selectedRule.start;
        bad.details.bp3Facts.queryRange.end = bad.details.bp3Facts.selectedRule.end; }
    assert.deepEqual(h.bp3FunctionalDetails(bad), []); rejected++;
}
console.log(JSON.stringify({ accepted, rejected, fullInputUnchanged: true }));
