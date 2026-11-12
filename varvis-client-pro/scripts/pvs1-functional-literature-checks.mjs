import frontendModuleUrl from './frontend-module-url.cjs';
import assert from 'node:assert/strict';
import fs from 'node:fs';

const source = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgInput.js', import.meta.url));
const { pvs1FunctionalLiteratureDetails, pvs1StartLossAutomaticDetails, pvs1FunctionalApplicabilityDetails } = await import(frontendModuleUrl(`data:text/javascript;base64,${source.toString('base64')}`));
const f = {
    schemaVersion: 'pvs1-start-loss-functional-summary-v1', status: 'READ_WITH_LIMITS', runtimeEligible: false,
    functionalRescueEstablished: false, negativeFunctionalCoverageGranted: false, pendingDocumentCount: 1,
    documents: [{ pmcid: 'PMC123', reviewedUnitCount: 2, pendingUnitCount: 1, observationCount: 1,
        supplementCoverage: { status: 'SUPPLEMENT_UNAVAILABLE_OR_UNPARSED', reasonCode: 'PAPER_DOWNLOAD_TIMEOUT' },
        observations: [{ kind: 'PARTIAL_READOUT_RESCUE_REPORTED', sourceQuote: '31.77% of genes were partially rescued.',
            sourceRole: 'CITED_OR_BACKGROUND', quoteTruncated: false }],
        remainingRequirements: ['EXACT_ISOFORM_TO_TRANSCRIPT_BINDING', 'ENDOGENOUS_COMPENSATION_APPLICABILITY'] }],
};
const before = structuredClone(f);
const rows = pvs1FunctionalLiteratureDetails(f);
assert.ok(rows.some(([label, value]) => label.includes('实验观察') && value.includes('不能等同完整功能恢复') && value.includes('31.77%') && value.includes('背景或引用')));
assert.ok(rows.some(([label, value]) => label.includes('仍需核查') && value.includes('精确版本转录本') && value.includes('天然表达')));
assert.ok(rows.some(([label, value]) => label.includes('补充材料缺项') && value.includes('超时')));
assert.deepEqual(f, before);
for (const field of ['runtimeEligible', 'functionalRescueEstablished', 'negativeFunctionalCoverageGranted'])
    assert.deepEqual(pvs1FunctionalLiteratureDetails({ ...f, [field]: true }), []);
assert.deepEqual(pvs1FunctionalLiteratureDetails(undefined), []);
assert.ok(pvs1FunctionalLiteratureDetails({ ...f, status: 'UNAVAILABLE', reasonCode: 'FUNCTIONAL_LITERATURE_READING_TIMEOUT' })[0][1].includes('超时'));
const prepared = { schemaVersion: 'pvs1-start-loss-automatic-preparation-v1', methodVersion: '1.2.0',
    status: 'INCOMPLETE', runtimeEligible: false, scientificEvaluationPerformed: false,
    issues: ['PVS1_START_LOSS_AUTO_FUNCTIONAL_TRANSCRIPT_COVERAGE_UNRESOLVED'],
    transcriptReview: { status: 'UNRESOLVED', complete: false, functionalLiterature: f } };
assert.ok(pvs1StartLossAutomaticDetails(prepared).some(([label]) => label === '功能文献与判定的差距'));
const applicability = {
    schemaVersion: 'pvs1-start-loss-functional-applicability-v1', status: 'ASSESSMENT_INCOMPLETE',
    functionalQualificationGranted: false, negativeFunctionalCoverageGranted: false,
    candidateCount: 31, displayTruncated: true, candidates: [{ transcript: 'NM_000001.2',
        status: 'IDENTITY_MENTION_ONLY_FUNCTION_UNRESOLVED', functionalQualificationGranted: false,
        negativeFunctionalCoverageGranted: false, documents: [{ pmcid: 'PMC123',
            identityRelation: 'TRANSCRIPT_VERSION_MISMATCH', invalidObservationCount: 1, observations: [{
                identityRelation: 'TRANSCRIPT_MENTION_ONLY_IN_CONTEXT', useDisposition: 'EXPERIMENT_SUBJECT_NOT_BOUND',
            }] }] }],
};
const candidateRows = pvs1FunctionalApplicabilityDetails(applicability);
assert.ok(candidateRows.some(([, value]) => value.includes('版本与候选不符') && value.includes('只在上下文提及') && value.includes('核验失败')));
assert.ok(candidateRows.some(([, value]) => value.includes('31') && value.includes('最多展示30条')));
assert.deepEqual(pvs1FunctionalApplicabilityDetails({ ...applicability, functionalQualificationGranted: true }), []);
const combined = pvs1FunctionalLiteratureDetails({ ...f, candidateApplicability: applicability });
assert.ok(combined.some(([label]) => label.includes('NM_000001.2')));
console.log('PVS1 functional literature display checks passed.');
