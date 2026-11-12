import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';

// Consumes explicitly synthetic core fixtures. Never creates an adopted policy or a patient record.
const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const h = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
const rows = JSON.parse(fs.readFileSync(process.argv[2]));
const reasons = {
    PVS1_SINGLE_EXON_ESCAPE_CONDITIONS_MISSING: '非 NMD',
    PVS1_SINGLE_EXON_JUNCTION_CONFLICT: '相互矛盾',
    PVS1_SPECIAL_TOPOLOGY_SEQUENCE_PROOF_MISSING: '完整 RNA',
    PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED: '适用依据',
    PVS1_UTR_JUNCTION_APPLICABILITY_INVALID: '来源未绑定',
    PVS1_SPECIAL_TOPOLOGY_SPLICE_NOT_IMPLEMENTED: '纯编码',
};
for (const [code, text] of Object.entries(reasons)) assert.ok(h.reasonHelp(code, 'PVS1_NMD_V6').includes(text), code);

function fixture(value) {
    const binding = { schemaVersion: 'reviewed-rule-bundle-binding-pvs1-v6', status: 'CONFIGURED',
        bundleId: 'TEST_ONLY', bundleVersion: '1', bundleSha256: 'a'.repeat(64), bundleRawSha256: 'b'.repeat(64),
        catalogSha256: 'c'.repeat(64), criterionIds: ['ACMG_AMP_2015:PVS1'], automaticTrigger: false,
        classificationEnabled: false, adoptionSupported: false, algorithms: { 'transcript-truncating-nmd-pvs1': '1.5.0' } };
    const profile = { profileId: 'acmg-pvs1-nmd-development-v1', profileVersion: '1.0.0',
        requiredDeclaration: 'ordinary-pvs1-nmd-v1', enabledCriteria: binding.criterionIds,
        manifestSha256: 'd'.repeat(64), formalEnabled: false, automaticTrigger: false, classificationEnabled: false,
        adoptionSupported: false, reviewedRuleBinding: binding, ruleContextPreparationBinding: {
            schemaVersion: 'reviewed-rule-context-preparation-pvs1-v6', version: '6.0.0',
            mode: 'EXPLICIT_VERSIONED_TRANSCRIPT_CONFIRMATION', automaticTrigger: false,
            classificationEnabled: false, criterionPolicySelected: false, predictionCompatibilityClaimed: false,
            reviewedRuleBinding: binding,
        } };
    return { attribution: 'SYNTHETIC_ENGINEERING_FIXTURE_ONLY', declarationVersion: profile.requiredDeclaration,
        enabledCriteria: profile.enabledCriteria, executionProfile: profile,
        candidates: [{ attempts: [{ pvs1: { ...structuredClone(value), bundleIdentity: binding } }] }] };
}
let checks = 0;
for (const row of rows) {
    assert.equal(row.attribution, 'SYNTHETIC_ENGINEERING_FIXTURE_ONLY');
    const run = fixture(row.result);
    assert.equal(h.pvs1NmdSavedIssue(run), null, row.caseId);
    const original = JSON.stringify(row.result);
    const value = run.candidates[0].attempts[0].pvs1;
    const raw = JSON.stringify(value);
    const entry = h.evidenceCards(run.candidates[0], ['ACMG_AMP_2015:PVS1'], null, null, run).at(0).entries.at(0);
    assert.equal(JSON.stringify(entry.raw), raw);
    assert.equal(JSON.stringify(row.result), original);
    assert.ok(entry.details.some(([label, text]) => label === '转录本结构分支' && text.includes(row.caseId.startsWith('SINGLE') ? '单外显子' : '3′UTR')));
    for (const mutation of ['version', 'count', 'sequenceProof', 'trace', 'legacy']) {
        const bad = structuredClone(run); const v = bad.candidates[0].attempts[0].pvs1;
        if (mutation === 'version') v.resolvedInput.transcriptTopology.version = 'other';
        if (mutation === 'count') v.resolvedInput.transcriptTopology.exonCount = 0;
        if (mutation === 'sequenceProof') v.resolvedInput.transcriptTopology.fullSplicedTranscriptSha256 = '';
        if (mutation === 'trace') v.evaluation.deterministicTrace.evaluatorVersion = '1.4.0';
        if (mutation === 'legacy') {
            bad.executionProfile.reviewedRuleBinding.algorithms['transcript-truncating-nmd-pvs1'] = '1.0.4';
            bad.executionProfile.ruleContextPreparationBinding.reviewedRuleBinding = bad.executionProfile.reviewedRuleBinding;
        }
        assert.ok(h.pvs1NmdSavedIssue(bad), `${row.caseId}:${mutation}`); checks += 1;
    }
    if (row.caseId.startsWith('SINGLE')) {
        const bad = structuredClone(run); bad.candidates[0].attempts[0].pvs1.resolvedInput.lastJunctionCdsPosition = 0;
        assert.ok(h.pvs1NmdSavedIssue(bad), 'zero is not a real junction'); checks += 1;
    }
    if (row.caseId === 'UTR_NMD') {
        const bad = structuredClone(run); const v = bad.candidates[0].attempts[0].pvs1;
        delete v.policy.nmdScope.utrJunctionApplicability;
        v.policySha256 = h.ps2IdentityHash(v.policy); v.policySelection.policySha256 = v.policySha256;
        assert.ok(h.pvs1NmdSavedIssue(bad), 'geometry alone cannot establish NMD applicability'); checks += 1;
    }
}
console.log(`PASS: ${Object.keys(reasons).length} reasons; ${rows.length} core-result displays/complete exports; ${checks} corrupted transport rejections.`);
