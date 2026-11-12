import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
const reasons = {
    PVS1_FULL_TRANSCRIPT_SEQUENCE_AND_STRUCTURE_VERIFIED: '核对一致',
    PVS1_SUPPLIED_TRANSCRIPT_IDENTITY_MISMATCH: '不一致',
    PVS1_SUPPLIED_TRANSCRIPT_SEQUENCE_MISMATCH: '完整 RNA',
    PVS1_SUPPLIED_TRANSCRIPT_STRUCTURE_MISMATCH: '连接位置',
    PVS1_BOUND_FULL_TRANSCRIPT_PROOF_MISSING: '缺少完整转录本',
    PVS1_TRANSCRIPT_MODEL_CONTEXT_MISMATCH: '本次选择',
    PVS1_POLICY_STRENGTH_CONDITIONS_REQUIRED: '强度上限',
    PVS1_SINGLE_EXON_NMD_SCOPE_NOT_IMPLEMENTED: '一个外显子',
    PVS1_NMD_JUNCTION_OUTSIDE_IMPLEMENTED_SCOPE: '末次接头',
    TRANSCRIPT_MATERIAL_EXACT_VERSION_NOT_AVAILABLE: '精确版本',
    TRANSCRIPT_MATERIAL_ALLELE_REFERENCE_MISMATCH: '参考碱基',
    TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH: '参考基因组',
    TRANSCRIPT_MATERIAL_CANDIDATE_ALLELE_MISMATCH: '正式候选',
    TRANSCRIPT_MATERIAL_CONTEXT_REFERENCE_MISMATCH: '参考版本',
    TRANSCRIPT_MATERIAL_SEQUENCE_PROOF_MISSING: '核验信息',
    TRANSCRIPT_MATERIAL_IO_UNAVAILABLE: '不可读取',
    TRANSCRIPT_REFERENCE_IDENTITY_CHANGED: '身份发生变化',
    REFERENCE_CHECKSUM_NOT_APPLICABLE: '核验记录',
};
for (const [code, text] of Object.entries(reasons)) assert.ok(h.reasonHelp(code, 'PVS1_NMD_V6').includes(text), code);
let checked = 0;
function check(value) {
    const original = JSON.stringify(value);
    const cards = h.evidenceCards({ attempts: [{ pvs1: value }] }, ['ACMG_AMP_2015:PVS1'], null, null, {});
    const entry = cards[0].entries[0];
    assert.equal(JSON.stringify(entry.raw), original);
    assert.equal(JSON.stringify(value), original);
    assert.ok(entry.details.some(([name]) => name === '自动序列与转录本供给'));
    const expected = value.transcriptMaterialSupply.status === 'READY' ? '原政策和转录本版本不变' : '不能以已有模型代替';
    assert.ok(entry.details.some(([name, text]) => name === '转录本结构核对' && text.includes(expected)));
    checked += 1;
}
for (const status of ['READY', 'UNAVAILABLE']) check({
    bundleIdentity: { schemaVersion: 'reviewed-rule-bundle-binding-pvs1-v6' },
    evaluationResult: { status: 'NOT_EVALUATED' },
    transcriptMaterialSupply: { bindingMethod: 'EXACT_FULL_TRANSCRIPT_EQUIVALENCE', status,
        reasonCode: status === 'READY' ? 'PVS1_FULL_TRANSCRIPT_SEQUENCE_AND_STRUCTURE_VERIFIED' : 'PVS1_SUPPLIED_TRANSCRIPT_STRUCTURE_MISMATCH',
        verifiedExonCount: 4 },
});
let cappedChecks = 0;
if (process.argv[2]) {
    const rows = JSON.parse(fs.readFileSync(process.argv[2]));
    for (const row of rows) check(row.result);
    // Contract-only mutations of an archived shape, never new scientific evidence or adopted policy.
    const original = rows.find(row => row.result.evaluation?.evaluationResult?.status === 'MET').result;
    for (const strength of ['STRONG', 'MODERATE', 'SUPPORTING']) {
        const value = structuredClone(original);
        delete value.knowledgeAssessment;
        value.policy.appliedStrength = strength;
        value.policySha256 = h.ps2IdentityHash(value.policy);
        value.policySelection.policySha256 = value.policySha256;
        value.evaluation.appliedStrength = strength;
        value.evaluation.deterministicTrace.evaluatorVersion = '1.4.0';
        value.bundleIdentity.algorithms['transcript-truncating-nmd-pvs1'] = '1.4.0';
        const binding = value.bundleIdentity;
        const profile = { profileId: 'acmg-pvs1-nmd-development-v1', profileVersion: '1.0.0',
            requiredDeclaration: 'ordinary-pvs1-nmd-v1', enabledCriteria: ['ACMG_AMP_2015:PVS1'],
            manifestSha256: 'a'.repeat(64), formalEnabled: false, automaticTrigger: false,
            classificationEnabled: false, adoptionSupported: false, reviewedRuleBinding: binding,
            ruleContextPreparationBinding: { schemaVersion: 'reviewed-rule-context-preparation-pvs1-v6', version: '6.0.0',
                mode: 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION', automaticTrigger: false, classificationEnabled: false,
                criterionPolicySelected: false, predictionCompatibilityClaimed: false, reviewedRuleBinding: binding } };
        const run = { attribution: 'TEST_ONLY_CONTRACT_MUTATION', declarationVersion: profile.requiredDeclaration,
            enabledCriteria: profile.enabledCriteria, executionProfile: profile, candidates: [{ attempts: [{ pvs1: value }] }] };
        assert.equal(h.pvs1NmdSavedIssue(run), null, strength);
        value.evaluation.appliedStrength = 'VERY_STRONG';
        assert.ok(h.pvs1NmdSavedIssue(run), 'cannot exceed policy ceiling');
        value.evaluation.appliedStrength = strength;
        value.evaluation.deterministicTrace.evaluatorVersion = '1.1.0';
        assert.ok(h.pvs1NmdSavedIssue(run), 'capped result needs its actual algorithm identity');
        value.evaluation.deterministicTrace.evaluatorVersion = '1.4.0';
        binding.algorithms['transcript-truncating-nmd-pvs1'] = '1.3.0';
        assert.ok(h.pvs1NmdSavedIssue(run), 'old binding cannot claim capped algorithm');
        binding.algorithms['transcript-truncating-nmd-pvs1'] = '1.0.4';
        value.policy.schemaVersion = 'pvs1-truncating-nmd-policy-v1';
        value.policySha256 = h.ps2IdentityHash(value.policy);
        value.policySelection.policySha256 = value.policySha256;
        assert.ok(h.pvs1NmdSavedIssue(run), 'legacy schema cannot bypass the new strength identity');
        cappedChecks += 5;
    }
}
console.log(`PASS: ${Object.keys(reasons).length} precise reasons; ${checked} saved fact displays; ${cappedChecks} strength contracts; complete raw JSON preserved.`);
