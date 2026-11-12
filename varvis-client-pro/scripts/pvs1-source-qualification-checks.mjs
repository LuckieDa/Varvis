import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const raw = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${raw.toString('base64')}`));
const reasons = {
    PVS1_SOURCE_QUALIFIED_CONDITIONS_BOUND: '自动核对',
    PVS1_SOURCE_POLICY_CONDITIONS_MISSING: '转录本、外显子、NMD',
    PVS1_SOURCE_QUALIFICATION_ORIGINAL_REVIEW_MISMATCH: '不一致',
    PVS1_SOURCE_QUALIFICATION_CONDITIONS_MISMATCH: '不一致',
    PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED: '完整来源',
    PVS1_SOURCE_QUALIFICATION_BRANCH_UNSUPPORTED: '非剪接',
    PVS1_SOURCE_QUALIFICATION_ORIGINAL_USE_RESTRICTED: '不能自动扩大',
};
for (const [code, text] of Object.entries(reasons)) assert.ok(h.reasonHelp(code, 'PVS1_NMD_V6').includes(text), code);
const value = {
    bundleIdentity: { schemaVersion: 'reviewed-rule-bundle-binding-pvs1-v6' },
    evaluationResult: { status: 'NOT_EVALUATED', reasonCode: 'PVS1_SOURCE_POLICY_CONDITIONS_MISSING' },
    knowledgeAssessment: { status: 'UNAVAILABLE', reasonCode: 'PVS1_SOURCE_POLICY_CONDITIONS_MISSING',
        missingExecutableConditions: ['SOURCE_SUPPORTED_LOF_MECHANISM_AND_STRENGTH'] },
};
const cards = h.evidenceCards({ attempts: [{ pvs1: value }] }, ['ACMG_AMP_2015:PVS1'], null, null, {});
assert.ok(cards[0].entries[0].details.some(([name, text]) => name === '尚缺可执行条件' && text.includes('原数据库记录已保留')));
assert.deepEqual(cards[0].entries[0].raw, value);
value.knowledgeAssessment = { status: 'READY', adoption: { purpose: 'PROJECT_RESEARCH',
    allowedDataClasses: ['PRIVACY_BOUNDED_RUNTIME'], qualification: { createsExpertReview: false } } };
const ready = h.evidenceCards({ attempts: [{ pvs1: value }] }, ['ACMG_AMP_2015:PVS1'], null, null, {});
assert.ok(ready[0].entries[0].details.some(([name, text]) => name === '来源资格方式' && text.includes('没有新增专家审核')));
assert.equal(JSON.stringify(ready[0].entries[0].raw), JSON.stringify(value));
console.log('PASS: 7 precise reasons; missing conditions and source qualification displayed; full raw objects preserved.');
