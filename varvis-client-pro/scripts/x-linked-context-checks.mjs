import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
let checks = 0;
for (const moi of ['X_LINKED_DOMINANT', 'X_LINKED_RECESSIVE']) {
    const context = { policyId: 'LOCAL_ALLELE_INTERPRETATION_CONTEXT', policyVersion: '1.1.0',
        rankingMoi: moi, interpretationMoi: moi, diagnosisClaimed: false, requiresExplicitSelection: true,
        genotypeCheck: 'X_LINKED_CATALOG_SCOPE_EXPLICIT_CONTEXT_NOT_DIAGNOSIS', reasonCode: null };
    const before = JSON.stringify(context), text = h.localInterpretationContextText(context);
    assert.match(text, /来源资料只注明 X 连锁/); assert.match(text, /需主动选择并确认/);
    assert.match(text, /不代表来源已区分显隐性或患者诊断/); assert.ok(!text.includes('两者不同'));
    assert.equal(JSON.stringify(context), before); checks += 5;
    assert.match(h.localInterpretationContextText({ ...context, diagnosisClaimed: true }), /身份不完整/); checks++;
}
assert.match(h.localInterpretationContextText({ policyId: 'LOCAL_ALLELE_INTERPRETATION_CONTEXT', policyVersion: '1.0.0',
    rankingMoi: 'AUTOSOMAL_RECESSIVE', interpretationMoi: 'AUTOSOMAL_DOMINANT', diagnosisClaimed: false, requiresExplicitSelection: true }), /两者不同/);
checks++;
for (const code of ['INTERPRETATION_X_LINKED_CONTEXT_REQUIRED', 'INTERPRETATION_NATIVE_X_ALLELE_REQUIRED']) {
    assert.ok(!h.reasonHelp(code).includes(code)); checks++;
}
console.log(JSON.stringify({ checks, unchangedInput: true, historicalVersionAccepted: true }));
