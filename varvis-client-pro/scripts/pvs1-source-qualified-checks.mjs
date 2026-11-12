import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const { pvs1SelectedDetails } = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
const sha = 'a'.repeat(64);

function criterion(alternative, witness = false) {
    const transcript = {
        schemaVersion: 'pvs1-start-loss-source-qualified-transcripts-v1', contextSha256: sha,
        status: alternative ? 'FOUND_FUNCTIONAL_ALTERNATIVE' : 'NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET',
        complete: !alternative, method: 'SOURCE_REPLAY', scope: 'DECLARED_SOURCE_SET',
        sourceBindings: [{ role: 'TRANSCRIPT_FUNCTION', sourceSha256: 'b'.repeat(64), sourceLocator: '/original/1', url: 'https://example.org/source' }],
    };
    const strength = alternative ? 'NONE' : witness ? 'MODERATE' : 'SUPPORTING';
    const reason = alternative ? 'PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT' : witness ?
        'PVS1_START_LOSS_UPSTREAM_PATHOGENIC_REFERENCE' : 'PVS1_START_LOSS_NO_UPSTREAM_PATHOGENIC_REFERENCE';
    const a = {
        schemaVersion: 'pvs1-start-loss-automatic-facts-v1', methodVersion: '1.1.0', contextSha256: sha,
        verifiedModelSha256: 'c'.repeat(64), decisionOutcome: alternative ? 'NOT_APPLICABLE' : 'MET', decisionStrength: strength,
        reasonCode: reason, rule: 'CLINGEN_SVI_START_LOSS_2018_ASSESSED_SOURCE_SET', ruleSource: 'https://pubmed.ncbi.nlm.nih.gov/30192042/',
        doubleCountingConstraint: 'NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE',
        sourceQualifiedTranscriptAssessment: { method: 'INTERNAL_SOURCE_REPLAY', contextSha256: sha,
            functionalAlternativeFound: alternative, completeNegative: !alternative, evidence: structuredClone(transcript) },
        sequence: { status: 'AVAILABLE', selectedTranscript: 'NM_000001.1', strand: -1, cdsPosition: 1,
            referenceCodon: 'ATG', alternateCodon: 'GTG', candidateRestartCdsPosition: alternative ? null : 10 },
        preparation: {
            schemaVersion: 'pvs1-start-loss-automatic-preparation-v1', methodVersion: '1.2.0', contextSha256: sha,
            status: 'GENERIC_FACTS_QUALIFIED', issues: [], runtimeEligible: false, scientificEvaluationPerformed: false,
            expertAssertions: { status: 'NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT', entries: [] },
            mechanismAssessment: alternative ? null : { schemaVersion: 'pvs1-start-loss-mechanism-v1', methodVersion: '1.0.0', status: 'CURATED_MECHANISM_SUPPORTED',
                contextSha256: sha, runtimeEligible: false, findings: [{ status: 'CURATED_MECHANISM_SUPPORTED', sourceSupport: 'evidence', sourceConfidence: 'strong' }] },
            transcriptReview: { status: transcript.status, complete: !alternative,
                qualificationBasis: 'SOURCE_QUALIFIED_FUNCTIONAL_TRANSCRIPT_REVIEW', sourceQualifiedAssessment: structuredClone(transcript) },
            upstreamReview: alternative ? { status: 'NOT_REQUIRED', reasonCode: reason } : witness ? { status: 'WITNESS_VALIDATED', qualifyingReferenceCount: 1, reasonCode: reason }
                : { status: 'NO_QUALIFYING_REFERENCE_WITHIN_SCOPE', complete: true, negativeCoverageGranted: true, coverage: { complete: true }, reasonCode: reason },
        },
    };
    return { criterionId: 'ACMG_AMP_2015:PVS1', status: alternative ? 'NOT_APPLICABLE' : 'MET', appliedStrength: strength, reasonCode: reason,
        details: { pvs1Facts: { schemaVersion: 'pvs1-selected-facts-v1', methodVersion: '1.6.0',
            scope: 'AUTOMATIC_GENERIC_START_LOSS', clinicalValidationCompleted: false, automaticAssessment: a } } };
}

let checked = 0;
for (const [alternative, witness] of [[true, false], [false, false], [false, true]]) {
    const c = criterion(alternative, witness);
    const rows = pvs1SelectedDetails(c);
    assert.ok(rows.length > 0);
    assert.ok(rows.some(([label]) => label === '功能转录本来源核定'));
    assert.ok(rows.some(([label]) => label === (alternative ? '适用性结论' : '采用强度')));
    const a = c.details.pvs1Facts.automaticAssessment;
    for (const mutate of [
        v => { v.sourceQualifiedTranscriptAssessment.contextSha256 = 'd'.repeat(64); },
        v => { v.sourceQualifiedTranscriptAssessment.evidence.contextSha256 = 'd'.repeat(64); },
        v => { v.sourceQualifiedTranscriptAssessment.completeNegative = alternative; },
        v => { v.sourceQualifiedTranscriptAssessment.evidence.sourceBindings = []; },
        v => { v.preparation.transcriptReview.sourceQualifiedAssessment.status = 'CONFLICT'; },
        v => { v.preparation.transcriptReview.sourceQualifiedAssessment.sourceBindings[0].sourceSha256 = 'f'.repeat(64); },
        v => { v.preparation.expertAssertions.entries.push({ status: 'UNRESOLVED' }); },
        v => { v.preparation.transcriptReview.complete = alternative; },
    ]) {
        const bad = structuredClone(c); mutate(bad.details.pvs1Facts.automaticAssessment);
        assert.deepEqual(pvs1SelectedDetails(bad), []); checked++;
    }
    assert.equal(a.contextSha256, sha);
}
const scoped = criterion(true), a = scoped.details.pvs1Facts.automaticAssessment;
a.preparation.expertAssertions = { status: 'SPECIALIST_SCOPE_REQUIRES_SELECTION', entries: [{ status: 'UNRESOLVED',
    reasonCode: 'EXPERT_EXACT_TRANSCRIPT_SCOPE_UNRESOLVED', specificationKey: 'GN_TEST@1', source: { rawSha256: 'b'.repeat(64) } }] };
for (const evidence of [a.sourceQualifiedTranscriptAssessment.evidence, a.preparation.transcriptReview.sourceQualifiedAssessment]) {
    evidence.qualificationBasis = 'EXPLICIT_SOURCE_START_LOSS_EXCLUSION'; evidence.policyDisposition = 'PROHIBITED';
    evidence.expertSpecificationKeys = ['GN_TEST@1'];
}
assert.ok(pvs1SelectedDetails(scoped).length > 0);
for (const mutate of [
    v => { v.preparation.expertAssertions.entries[0].reasonCode = 'EXPERT_CONDITIONAL_ASSERTION_NOT_IMPLEMENTED'; },
    v => { v.preparation.expertAssertions.entries[0].source.rawSha256 = 'd'.repeat(64); },
    v => { v.sourceQualifiedTranscriptAssessment.evidence.expertSpecificationKeys.push('GN_OTHER@2'); },
    v => { v.preparation.transcriptReview.sourceQualifiedAssessment.policyDisposition = 'PERMITS'; },
    v => { v.preparation.expertAssertions.entries.push({ status: 'UNRESOLVED', reasonCode: 'EXPERT_ORIGINAL_UNAVAILABLE' }); },
]) {
    const bad = structuredClone(scoped); mutate(bad.details.pvs1Facts.automaticAssessment);
    assert.deepEqual(pvs1SelectedDetails(bad), []); checked++;
}
console.log(`PVS1 source-qualified projection: 3 outcomes and ${checked} inconsistent payloads checked.`);
