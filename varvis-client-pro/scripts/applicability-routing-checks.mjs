import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url), 'utf8');
const api = await import(frontendModuleUrl(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`));
const codes = { PM3: 'ROUTING_PM3_NON_RECESSIVE_CONTEXT', PM1: 'ROUTING_PM1_IMPLEMENTED_MISSENSE_SCOPE',
    PM4: 'ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE', BP3: 'ROUTING_NO_ANNOTATED_PROTEIN_LENGTH_CHANGE',
    PP2: 'ROUTING_MISSENSE_REQUIRED', BP1: 'ROUTING_MISSENSE_REQUIRED', PM5: 'ROUTING_MISSENSE_REQUIRED',
    BP7: 'BP7_CONSEQUENCE_NOT_APPLICABLE' };
let count = 0;
for (const [code, reasonCode] of Object.entries(codes)) {
    const row = {criterionId: `ACMG_AMP_2015:${code}`, status:'NOT_EVALUATED', appliedStrength:'NONE', reasonCode,
        details: {applicabilityRouting: {status:code === 'PM1' ? 'SOFTWARE_UNSUPPORTED':'NOT_APPLICABLE', reasonCode, skipSpecializedAcquisition:true}}};
    assert.equal(api.validApplicabilityRouting(row), true, code);
    assert.equal(api.criterionLabel({evaluationResult:{status:'NOT_EVALUATED'},criterionApplicability:{status:row.details.applicabilityRouting.status}}),
        code === 'PM1' ? '软件暂未支持' : '不适用');
    assert.equal(api.validApplicabilityRouting({...row,status:'MET',appliedStrength:'SUPPORTING'}), false);
    assert.equal(api.validApplicabilityRouting({...row,reasonCode:'NO_RECORD'}), false);
    assert.equal(api.validApplicabilityRouting({...row,criterionId:'ACMG_AMP_2015:BA1'}), false);
    const unknown = structuredClone(row); unknown.details.applicabilityRouting.status = 'UNRESOLVED';
    assert.equal(api.validApplicabilityRouting(unknown), false, 'unknown cannot authorize skipping');
    unknown.details.applicabilityRouting.skipSpecializedAcquisition = false;
    assert.equal(api.validApplicabilityRouting(unknown), true); count++;
}
assert.equal(api.validApplicabilityRouting({details:{}}),true,'unchanged historical documents remain supported');
if (process.argv[2]) {
    const result = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
    for (const row of result.results) for (const criterion of row.localBatch.criteria)
        assert.equal(api.validApplicabilityRouting(criterion),true,criterion.criterionId);
    count++;
}
console.log(`${count} applicability transport groups passed; excluded routes cannot acquire a score, unknown cannot mean inapplicable.`);
