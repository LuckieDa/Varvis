import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';

const raw = fs.readFileSync(new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgSequencePopulation.js', import.meta.url), 'utf8');
const { sequenceEditSummaryValid, sequenceMaterialValid, sequenceEditDetails, sourceExecutionDetails,
    populationStrataClaim, populationStrataResultValid, populationStrataDetails } = await import(`data:text/javascript;base64,${Buffer.from(raw).toString('base64')}`);
const hash = value => createHash('sha256').update(value).digest('hex');
const canonical = value => typeof value === 'string' ? value.normalize('NFC') : Array.isArray(value) ? value.map(canonical)
    : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key.normalize('NFC'), canonical(value[key])])) : value;
const identity = value => hash(JSON.stringify(canonical(value)));
const clone = value => structuredClone(value);
let checks = 0;
const test = (label, run) => { run(); checks += 1; console.log(`PASS ${label}`); };

// Independent small control; every scalar below is synthetic. Real retained
// backend result bytes are replayed separately when VARVIS_NATIVE_ROOT is set.
function sequence() {
    return { schemaVersion: 'coding-transcript-edit-facts-v1', methodVersion: '1.0.0', scope: 'CDS_SEQUENCE_FACTS_ONLY',
        topologyScope: 'CODING_EXONS_ONLY', scientificQualificationGranted: false, alteredCdsSha256: hash('ATGCCATAA'),
        referenceCdsLength: 9, alteredCdsLength: 9, netCdsLengthChange: 0,
        binding: { assembly: 'GRCh38', contig: 'X', transcript: 'ENST00000000001.1', modelSha256: 'a'.repeat(64),
            referenceCdsSha256: hash('ATGAAATAA'), contextSha256: 'b'.repeat(64) },
        alteredToReference: [{ alteredStart0: 0, alteredEnd0: 3, referenceStart0: 0 },
            { alteredStart0: 3, alteredEnd0: 5, referenceStart0: null }, { alteredStart0: 5, alteredEnd0: 9, referenceStart0: 5 }],
        codingExons: [{ ordinal: 1, referenceOrdinal: 1, referenceCdsStart0: 0, referenceCdsEnd0: 9, alteredCdsStart0: 0, alteredCdsEnd0: 9 }],
        codingJunctions0: [], edits: [{ id: 'SYNTHETIC', origin: 'CODING_ALLELE', status: 'APPLIED', referenceEditStart0: 3,
            referenceEditEnd0: 5, insertedSequence: 'CC', expectedReference: 'AA', evidenceBindingSha256: 'c'.repeat(64),
            alleleGroupSha256: 'd'.repeat(64), insertionExonOrdinal: 1 }] };
}

const headerPin = '66875ac50f53b8f491c263394fc85e17308c639bae4b7d5daa43fea6f773ca83';
const profilePin = 'bce4c90293c0bd5028d5d240a9bdabea9bf4118e27adf2af245f61d98c8e7a1d';
const regionPin = '41b99cb587f84e0f9823a7994ea29573a3f6aad619ebe02d011e02b964b820aa';
function reseal(value) {
    const o = value.observation, d = value.qualityDecision.sexStratifiedObservation;
    delete o.observationId; o.rawRecordSha256 = hash(o.rawRecord); o.observationId = 'population-observation-sha256-' + identity(o);
    d.rawRecordSha256 = o.rawRecordSha256; d.parentObservationId = o.observationId;
    delete d.observationId; d.observationId = 'gnomad-sex-observation-sha256-' + identity(d);
    value.qualityDecision.frequencySelection.sourceObservationId = d.observationId;
    const trace = value.evaluation.deterministicTrace;
    const qualityParameter = trace.parameters?.find(parameter => parameter.name === 'qualitySha256');
    if (qualityParameter) qualityParameter.value = identity(value.qualityDecision);
    if (trace.parameters) { delete trace.traceSha256; trace.traceSha256 = identity(trace); }
}
function population(af = true) {
    const allele = { assembly: 'GRCh38', contig: 'X', position: 3000000, reference: 'A', alternate: 'G' };
    const context = { contextId: 'context-SYNTHETIC', gene: { hgncId: 'HGNC:999001' }, disease: { mondoId: 'MONDO:0999001' },
        moi: 'X_LINKED_DOMINANT', variant: { evidenceQueryAllele: allele } };
    const measure = (statistic, value) => ({ infoId: `${statistic}_nfe_XY`, state: 'PRESENT', rawFieldValue: value, rawValue: value,
        normalizedValue: value, unit: ['AC', 'AN'].includes(statistic) ? 'alleles' : 'fraction',
        ...(statistic === 'faf95' ? { applicability: 'APPLICABLE', confidence: '95_PERCENT_LOWER_BOUND' } : {}) });
    const row = { ancestry: 'nfe', karyotype: 'XY', denominatorUnit: 'ALLELES', sampleCount: 'NOT_SUPPLIED', individualPloidy: 'NOT_ESTABLISHED',
        status: 'OBSERVED', issues: [], measurements: { AC: measure('AC','21'), AN: measure('AN','10000'), AF: measure('AF','0.0021'), faf95: measure('faf95','0.0019') } };
    const resource = { assembly: 'GRCh38', resourceId: 'SYNTHETIC_EXOMES', dataset: 'EXOMES' };
    const observation = { schemaVersion: 'population-observation-v1', rawRecord: 'X\t3000000\t.\tA\tG\t.\tPASS\tAC_nfe_XY=21;AN_nfe_XY=10000;AF_nfe_XY=0.0021;faf95_nfe_XY=0.0019',
        sourceSnapshot: 'e'.repeat(64), profileSha256: profilePin, outputCompleteness: 'VERIFIED', queryAllele: allele, resourceContract: resource,
        carrier: { queryAllele: allele, resourceExecution: { status: 'SUCCEEDED' }, resourceAvailability: { status: 'AVAILABLE' }, queryOutcome: { status: 'HIT' } } };
    const derived = { schemaVersion: 'gnomad-sex-stratified-observation-v1', methodVersion: '1.0.0', dataset: 'EXOMES', release: '4.1',
        headerSha256: headerPin, headerSource: { sha256: headerPin }, profileSha256: profilePin, sourceSnapshot: observation.sourceSnapshot,
        status: 'OBSERVED', stratumMeaning: 'SOURCE_INFERRED_GENETIC_KARYOTYPE_NOT_PATIENT_SEX', statisticalQualification: 'NOT_EVALUATED', criterionEvaluationEnabled: false,
        selectedAltIndex: 0, recordAltCount: 1, recordFilter: 'PASS', queryAllele: allele, resourceContract: resource, strata: [row],
        region: { schemaVersion: 'sex-chromosome-region-v1', methodVersion: '1.0.0', sourceCatalogSha256: regionPin,
            clinicalQualificationGranted: false, patientPloidyInferred: false, queryAllele: allele, queryAlleleSha256: identity(allele),
            status: 'RESOLVED', region: 'NON_PAR', regionId: 'NON_PAR', resolvedContig: 'X',
            classificationSpan: { start: 3000000, end: 3000000, role: 'AFFECTED_REFERENCE_BASES' },
            event: { trimmedPrefixBases: 0, trimmedSuffixBases: 0, referenceBasesConsumed: 1, alternateBasesInserted: 1 } } };
    const statistic = { metricId: `gnomad.exomes.sex_ancestry_max_${af ? 'af' : 'faf95'}`, unit: 'fraction',
        confidence: af ? 'POINT_ESTIMATE' : '95_PERCENT_LOWER_BOUND', sourceTerm: af ? 'AF' : 'FAF95',
        interpretation: af ? 'TARGET_ALT_AF' : 'TARGET_ALT_FAF95', interpretationBasis: 'SYNTHETIC target-alt source interpretation',
        eligibleAn: { operator: 'GTE', decimal: '2000' }, ancestries: ['nfe'], karyotypes: ['XY'], sourceId: 'SYNTHETIC', locator: 'SYNTHETIC/source' };
    const contract = { contractId: 'bs1-local-exomes-quality-v1', dataset: 'EXOMES' };
    const scope = { policyId: 'BS1_LOCAL_EXOMES_V1', dataset: 'EXOMES', release: '4.1', version: '1.0.0' };
    const condition = { fact: af ? 'AF' : 'FAF95', operator: 'GT', decimal: '0.003', unit: 'fraction', basis: 'OFFICIAL_CONDITION', originalText: 'SYNTHETIC target frequency > 0.003', sourceLocator: 'SYNTHETIC/source' };
    const recordScope = { hgncId: 'HGNC:999001', mondoId: 'MONDO:0999001', modes: ['X_LINKED_DOMINANT'],
        sexChromosome: { requiredDataset: 'EXOMES', sourceId: 'SYNTHETIC', sourceLocator: 'SYNTHETIC/source' } };
    const policyScope = { ...recordScope, modeSelection: 'EXACT_DECLARED_MODE' };
    const sources = [{ sourceId: 'SYNTHETIC', url: 'https://example.org/SYNTHETIC', version: 'SYNTHETIC', sha256: 'a'.repeat(64), locator: 'SYNTHETIC/source' }];
    const exceptionSet = { status: 'COMPLETE_IN_PINNED_SCOPE', entries: [] };
    const tiers = [{ tierId: 'SYNTHETIC', moi: context.moi, priority: 1, strength: 'STRONG', sourceLocator: 'SYNTHETIC/source', conditions: [condition] }];
    const record = { recordId: 'SYNTHETIC', version: '1', kind: 'EXPERT_POLICY', publicationStatus: 'Released', scope: recordScope, sources, exceptionSet,
        expertPolicy: { statistic, scope: policyScope, sources, tiers, commonConditions: [], exceptionSet } };
    const payloadSha256 = identity(record);
    const priorReview = { status: 'REVIEWED_FOR_LOCAL_USE', contentSha256: payloadSha256, dataScopePolicyId: 'BS1_LOCAL_EXOMES_V1', reviewer: 'SYNTHETIC', reviewedAt: '2026-11-03', basis: 'SYNTHETIC engineering control' };
    record.qualification = { schemaVersion: 'bs1-knowledge-qualification-v1', payloadSha256, scope: recordScope, priorReview,
        reviewer: 'SYNTHETIC', reviewedAt: '2026-11-03', basis: 'SYNTHETIC engineering control',
        sourceBindings: [{ sourceId: 'SYNTHETIC', locator: 'SYNTHETIC/source', contentSha256: 'a'.repeat(64) }],
        checks: Object.fromEntries(['SOURCE_COMPLETENESS','PARAMETER_SCOPE','POPULATION_METRIC','ADDITIONAL_CONDITIONS','EXCEPTIONS'].map(category =>
            [category, { status: category === 'PARAMETER_SCOPE' ? 'PUBLISHED_THRESHOLD_NOT_RECALCULATED' : 'SATISFIED', basis: 'SYNTHETIC', sourceIds: ['SYNTHETIC'] }])) };
    record.review = { ...priorReview, contentSha256: identity(record) };
    const policy = { statistic, scope: policyScope, sources, tiers, commonConditions: [], qualityContract: contract, dataScope: scope,
        exceptionSet, knowledgeRecord: record, knowledgeDecision: { basis: 'EXPERT_POLICY' } };
    const evidenceId = 'evidence-SYNTHETIC';
    const value = { context, observation, dataScope: scope, evidence: { evidenceId, contextId: context.contextId },
        preparation: { policy, selectedMode: context.moi },
        evaluation: { contextId: context.contextId, evidenceIds: [evidenceId], appliedStrength: 'NONE',
            deterministicTrace: { evaluatorVersion: 'bs1-sex-stratified-frequency-v1', evaluatorId: 'varvis-bs1-policy-evaluator', executionStatus: 'COMPLETED',
                algorithmId: 'BS1_POLICY_TIERS', algorithmVersion: 'bs1-sex-stratified-frequency-v1', inputEvidenceIds: [evidenceId],
                parameters: [{ name: 'exceptionSetSha256', value: identity(exceptionSet) }, { name: 'policySha256', value: identity(policy) },
                    { name: 'qualitySha256', value: '' }, { name: 'selectedMode', value: context.moi },
                    { name: `tier.SYNTHETIC.${condition.fact}.GT`, value: '0.003', unit: 'fraction' }],
                steps: [{ stepCode: 'BS1_TIER_1_CONDITION_1', outcome: 'FALSE', inputEvidenceIds: [evidenceId] }] }, evaluationResult: { status: 'NOT_MET' } },
        qualityDecision: { adapterVersion: 'bs1-sex-stratified-frequency-v1', status: 'READY', issues: [], contract, contractSha256: identity(contract), dataScope: scope,
            filterCondition: { status: 'SATISFIED', observedFilter: 'PASS', basis: 'LOCAL_EXOMES_NATIVE_FILTER' }, sexStratifiedObservation: derived,
            facts: { ancestry: 'nfe', karyotype: 'XY', AC: '21', AN: '10000', AF: '0.0021', ...(!af ? { FAF95: '0.0019' } : {}) },
            frequencySelection: { version: 'bs1-sex-stratified-frequency-v1', statistic, selectedAncestry: 'nfe', selectedKaryotype: 'XY',
                comparisonBasis: af ? 'EXACT_TARGET_AC_OVER_AN' : 'SOURCE_STRATUM_FAF95', populations: [{ ancestry: 'nfe', karyotype: 'XY', status: 'ELIGIBLE', sourceStratum: row }] } } };
    reseal(value); return value;
}
function bindPolicy(value) {
    const p = value.preparation.policy, record = p.knowledgeRecord;
    record.qualification.payloadSha256 = identity(Object.fromEntries(Object.entries(record).filter(([key]) => !['review', 'qualification'].includes(key))));
    record.qualification.priorReview.contentSha256 = record.qualification.payloadSha256;
    record.review.contentSha256 = identity(Object.fromEntries(Object.entries(record).filter(([key]) => key !== 'review')));
    value.evaluation.deterministicTrace.parameters.find(parameter => parameter.name === 'policySha256').value = identity(p);
    reseal(value);
}

test('synthetic sequence replacement preserves zero net length without granting evidence', () => {
    const value = sequence(), original = JSON.stringify(value);
    assert.equal(sequenceEditSummaryValid(value), true);
    assert.match(JSON.stringify(sequenceEditDetails({ sequenceDerivation: value })), /不授疾病/);
    assert.equal(JSON.stringify(value), original);
});
test('sequence mapping, length, group, version and qualification mutations fail independently', () => {
    for (const mutate of [v => { v.alteredToReference[2].referenceStart0 = 4; }, v => { v.netCdsLengthChange = 1; },
        v => { v.methodVersion = '9.0.0'; }, v => { v.scientificQualificationGranted = true; },
        v => { const other = clone(v.edits[0]); other.id = 'other'; other.status = 'DUPLICATE_SEQUENCE_EDIT'; other.alleleGroupSha256 = ''; v.edits.push(other); }]) {
        const value = sequence(); mutate(value); assert.equal(sequenceEditSummaryValid(value), false);
    }
});
test('malformed saved arrays fail closed without breaking detail projection', () => {
    for (const value of [null, {}, { edits: [null] }, { ...sequence(), edits: [null] }, { ...sequence(), codingExons: [null] }]) {
        assert.equal(sequenceEditSummaryValid(value), false);
    }
    const value = population(); value.qualityDecision.frequencySelection.populations = {};
    assert.equal(populationStrataResultValid(value), false);
    assert.doesNotThrow(() => populationStrataDetails(value));
});
test('AF and FAF95 use separate frozen metrics, confidence and same-stratum facts', () => {
    for (const af of [true, false]) {
        const value = population(af), original = JSON.stringify(value);
        assert.equal(populationStrataClaim(value), true);
        assert.equal(populationStrataResultValid(value), true);
        assert.match(JSON.stringify(populationStrataDetails(value)), /来源遗传核型/);
        assert.equal(JSON.stringify(value), original);
    }
});
test('parent execution failure remains unavailable despite complete parseable JSON', () => {
    const value = population(); value.observation.carrier.resourceExecution.status = 'FAILED'; reseal(value);
    assert.equal(populationStrataResultValid(value), false);
    assert.equal(populationStrataResultValid({ ...population(), qualityDecision: { status: 'BLOCKED' } }), false);
});
test('EXOMES cannot be promoted to JOINT by relabeling result or policy', () => {
    for (const mutate of [v => { v.dataScope.dataset = 'JOINT'; }, v => { v.preparation.policy.scope.sexChromosome.requiredDataset = 'JOINT'; },
        v => { v.qualityDecision.sexStratifiedObservation.dataset = 'JOINT'; reseal(v); },
        v => { v.preparation.policy.statistic.metricId = 'gnomad.joint.sex_ancestry_max_af'; }]) {
        const value = population(); mutate(value); assert.equal(populationStrataResultValid(value), false);
    }
});
test('raw field, parent, header, region and selected stratum bindings are enforced after resealing', () => {
    for (const mutate of [v => { v.observation.rawRecord = v.observation.rawRecord.replace('AF_nfe_XY=0.0021', 'AF_nfe_XY=0.02'); },
        v => { v.qualityDecision.sexStratifiedObservation.headerSha256 = 'f'.repeat(64); },
        v => { v.qualityDecision.sexStratifiedObservation.region.region = 'PAR'; },
        v => { v.qualityDecision.frequencySelection.selectedKaryotype = 'ALL'; },
        v => { v.qualityDecision.facts.AC = '200'; }, v => { v.qualityDecision.sexStratifiedObservation.strata[0].denominatorUnit = 'PERSONS'; }]) {
        const value = population(); mutate(value); reseal(value); assert.equal(populationStrataResultValid(value), false);
    }
});
test('missing FAF95 is permitted only for ordinary AF and is never supplied from another stratum', () => {
    const af = population(); delete af.qualityDecision.sexStratifiedObservation.strata[0].measurements.faf95; reseal(af);
    assert.equal(populationStrataResultValid(af), true);
    const faf = population(false); delete faf.qualityDecision.sexStratifiedObservation.strata[0].measurements.faf95; reseal(faf);
    assert.equal(populationStrataResultValid(faf), false);
});
test('quality and source facts are retained for a negative result; no-record cannot be zero AF', () => {
    const value = population(); assert.equal(populationStrataResultValid(value), true);
    value.observation.carrier.queryOutcome.status = 'NO_RECORD'; value.qualityDecision.sexStratifiedObservation.status = 'NO_RECORD'; reseal(value);
    assert.equal(populationStrataResultValid(value), false);
});
test('count/AF contradiction remains invalid when both sealed raw and selected facts are changed', () => {
    const value = population(); value.observation.rawRecord = value.observation.rawRecord.replace('AF_nfe_XY=0.0021', 'AF_nfe_XY=0.02');
    const field = value.qualityDecision.sexStratifiedObservation.strata[0].measurements.AF;
    field.rawFieldValue = '0.02'; field.rawValue = '0.02'; value.qualityDecision.facts.AF = '0.02'; reseal(value);
    assert.equal(populationStrataResultValid(value), false);
});
test('exact AF and FAF95 threshold edges determine trace, final status and strength independently', () => {
    for (const af of [true, false]) for (const [operator, threshold, expected] of [
        ['GT', af ? '0.0021' : '0.0019', 'NOT_MET'], ['GTE', af ? '0.0021' : '0.0019', 'MET'],
        ['GT', af ? '0.00210000000000000000000001' : '0.00190000000000000000000001', 'NOT_MET'],
        ['GT', af ? '0.00209999999999999999999999' : '0.00189999999999999999999999', 'MET'],
    ]) {
        const value = population(af), condition = value.preparation.policy.tiers[0].conditions[0];
        condition.operator = operator; condition.decimal = threshold;
        const trace = value.evaluation.deterministicTrace, parameter = trace.parameters.find(p => p.name.startsWith('tier.'));
        parameter.name = `tier.SYNTHETIC.${condition.fact}.${operator}`; parameter.value = threshold;
        trace.steps[0].outcome = expected === 'MET' ? 'TRUE' : 'FALSE';
        value.evaluation.evaluationResult.status = expected; value.evaluation.appliedStrength = expected === 'MET' ? 'STRONG' : 'NONE';
        bindPolicy(value);
        assert.equal(populationStrataResultValid(value), true, `${af ? 'AF' : 'FAF95'} ${operator} ${threshold}`);
        const changed = clone(value); changed.evaluation.evaluationResult.status = expected === 'MET' ? 'NOT_MET' : 'MET';
        assert.equal(populationStrataResultValid(changed), false);
    }
});
test('trace, adopted qualification, selected mode and outcome cannot be changed independently', () => {
    for (const mutate of [v => { v.evaluation.appliedStrength = 'STRONG'; },
        v => { v.evaluation.deterministicTrace.steps[0].outcome = 'TRUE'; reseal(v); },
        v => { v.evaluation.deterministicTrace.parameters.find(p => p.name.startsWith('tier.')).value = '0.001'; reseal(v); },
        v => { v.preparation.selectedMode = 'X_LINKED_RECESSIVE'; },
        v => { delete v.preparation.policy.knowledgeRecord.qualification; },
        v => { v.preparation.policy.knowledgeRecord.qualification.checks.POPULATION_METRIC.status = 'UNRESOLVED'; bindPolicy(v); },
        v => { v.preparation.policy.knowledgeRecord.qualification.sourceBindings[0].locator = 'unbound'; bindPolicy(v); }]) {
        const value = population(); mutate(value); assert.equal(populationStrataResultValid(value), false);
    }
});

const nativeRoot = process.env.VARVIS_NATIVE_ROOT;
if (nativeRoot) {
    const directory = path.join(nativeRoot, 'local/development/handoffs/acmg-six-framework-tasks-20261101/parallel-branches-20261103/dispatch/a5/round2');
    const fixture = path.join(directory, 'direct-diagnostic-replay/retained-actual-replay-5558a422-5dd4-443d-9003-e6a90fea49d5.json');
    const bytes = fs.readFileSync(fixture), result = JSON.parse(bytes);
    test('frozen a563dd19 reported allele backend results retain FAILED source and diagnostic qualification', () => {
        assert.equal(result.schemaVersion, 'a5-reported-complex-diagnostic-replay-v1');
        assert.equal(result.actualVepRawSha256, '53793345c1054de8e31aa13361f683fd2df5f93e0045d0abad1ca0f5b6182d5d');
        assert.equal(result.sourceExecutionReceipt.status, 'FAILED'); assert.equal(result.sourceExecutionAccepted, false);
        assert.equal(result.scientificQualificationGranted, false); assert.equal(result.finalMetCalled, false);
        assert.match(JSON.stringify(sourceExecutionDetails(result)), /失败.*诊断回放/);
        for (const row of result.cases) {
            assert.equal(sequenceEditSummaryValid(row.sequenceDerivation, row.engineeringContext), true, row.caseId);
            if (row.lengthMaterial) assert.equal(sequenceMaterialValid(row.lengthMaterial, row.engineeringContext), true, row.caseId);
            if (row.mechanicalFacts) assert.equal(sequenceEditSummaryValid(row.mechanicalFacts.codingEditDerivation, row.engineeringContext), true, row.caseId);
        }
        assert.equal(hash(fs.readFileSync(fixture)), hash(bytes));
        console.log(`FROZEN actual A5 result sha256=${hash(bytes)}`);
    });
    test('actual length/material identity, HGVS and protein length mutations fail', () => {
        const material = result.cases.find(row => row.lengthMaterial).lengthMaterial;
        for (const mutate of [v => { v.associationVersion = '1.0.0'; }, v => { v.alleleAssociation.netAminoAcidChange += 1; },
            v => { v.alleleAssociation.hgvscSequenceEquivalent = false; }, v => { v.verification.cdsSha256 = 'f'.repeat(64); }]) {
            const value = clone(material); mutate(value); assert.equal(sequenceMaterialValid(value), false);
        }
    });
} else console.log('NOT_RUN actual frozen backend A5 replay (set VARVIS_NATIVE_ROOT); synthetic checks are not real-source acceptance.');

if (process.env.VARVIS_POPULATION_FIXTURE) {
    const fixture = process.env.VARVIS_POPULATION_FIXTURE, bytes = fs.readFileSync(fixture), document = JSON.parse(bytes);
    const value = document.result || document, context = document.context || value.context;
    test('explicit frozen backend population JSON passes unchanged', () => {
        const original = JSON.stringify(document);
        assert.equal(populationStrataResultValid(value, context), true);
        assert.ok(populationStrataDetails(value).length > 0);
        assert.equal(JSON.stringify(document), original);
        assert.equal(hash(fs.readFileSync(fixture)), hash(bytes));
        console.log(`FROZEN population result sha256=${hash(bytes)}`);
    });
} else console.log('NOT_RUN frozen A10 Java result replay (set VARVIS_POPULATION_FIXTURE); independent A10 controls above are SYNTHETIC.');

console.log(`${checks} sequence/population frontend contract checks passed.`);

// Shared synthetic controls for testing the public read adapter without copying facts.
export { population as syntheticPopulation, sequence as syntheticSequence, reseal as resealSyntheticPopulation, bindPolicy as rebindSyntheticPolicy };
