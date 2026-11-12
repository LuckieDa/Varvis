// Independent transport controls; no algorithm/policy adoption is implied.
// Optional arguments: captured Python business-evaluation JSON files, or saved
// criterion/publicSummary JSON. Inputs are read only and never rewritten.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';
const url = new URL('../src/pages/varvis/ChromosomeVisualization/utils/acmgPp1Formal.js', import.meta.url);
const api = await import(`data:text/javascript;base64,${fs.readFileSync(url).toString('base64')}`);
const { inspectPp1FormalCriterion: inspect, validatePp1FormalCriterion: valid, pp1FormalResultDetails: details, isPp1FormalEvaluation: formal } = api;
const H = character => character.repeat(64), copy = structuredClone;
const canonical = v => Array.isArray(v) ? `[${v.map(canonical).join(',')}]` : v && typeof v === 'object'
    ? `{${Object.keys(v).sort().map(k => `${JSON.stringify(k)}:${canonical(v[k])}`).join(',')}}` : JSON.stringify(v);
const digest = v => createHash('sha256').update(canonical(v)).digest('hex');
const fraction = (n, d = 1n) => ({ numerator: String(n), denominator: String(d) });
const source = { pmcid: 'PMC123', bundleSha256: H('a') };
const ref = { ...source, unitId: 'PMC123/main.xml/p2', quote: 'SYNTHETIC exact source <script>literal</script>', sourceSha256: H('b') };
const scope = { geneSymbol: 'GENE1', diseaseId: 'TEST:1', inheritance: 'AD' };
const target = { selectionKey: 'SYNTHETIC', ...scope, allele: { assembly: 'GRCh38', contig: '1', position: 100, reference: 'A', alternate: 'G' } };
const parameterSource = { sha256: H('c'), quote: 'SYNTHETIC adopted formal parameters', locator: 'line 1' };
const model = { schemaVersion: 'pp1-formal-parameter-model-v1', version: '1.0.0', genotypeModel: 'AUTOSOMAL_SINGLE_ALLELE_DOSAGE',
    founderConditioning: 'ALL_FOUNDERS_TYPED', ascertainment: 'PHENOTYPE_BEFORE_GENOTYPING', pointBase: '2.08',
    phenotypeModel: { kind: 'AGE_INDEPENDENT_BINARY_STATUS', probabilities: { '0': '0.25', '1': '0.75', '2': '0.75' } } };
const decisions = ['method', 'geneDiseaseValidity', 'expertPolicyStatus', 'affectedTerms', 'penetrance', 'phenocopy', 'locusModel',
    'candidateAssessmentMethods', 'formalModel', 'formalModel.ascertainment', 'formalModel.pointBase'];
const policy = { scope, method: api.PP1_FORMAL_METHOD, geneDiseaseValidity: 'DEFINITIVE', expertPolicyStatus: 'GENERAL_ALLOWED',
    affectedTerms: ['TEST disease'], penetrance: 'EXPLICIT_FORMAL_MODEL', phenocopy: 'EXPLICIT_FORMAL_MODEL', locusModel: 'HETEROGENEOUS',
    candidateAssessmentMethods: ['SYNTHETIC candidate review'], formalModel: model, sources: { S: { path: 'source.txt', ...parameterSource } },
    decisionSources: Object.fromEntries(decisions.map(k => [k, ['S']])) };

function make(n = 9n, d = 4n, pp4 = 0) {
    const bf = fraction(n, d), sign = n < d ? -1 : n > d ? 1 : 0;
    const identity = { ...source, familyKey: 'F1', memberKey: 'child', unitId: ref.unitId, start: 0, end: 10 };
    const observationId = digest(identity), assessmentId = H('d'), eventId = digest([target.allele, scope, 'F1', 'PP1_FAMILY_CONTRIBUTION']);
    const assessment = { id: assessmentId, familyKey: 'F1', memberKey: 'child', sourceObservationId: observationId,
        observationIdentity: identity, sourceRefs: [ref], status: 'AFFECTED', attainedAge: null };
    const state = { ...assessment, memberLabel: 'II-2', assessmentIds: [assessmentId], status: 'AFFECTED', attainedAge: null,
        diseaseTerms: ['TEST disease'], claimIds: [H('f')], statusFactIds: [H('1')], ageFactIds: [], issues: [], bindingBasis: 'SAME_SOURCE_OBSERVATION' };
    const review = { familyKey: 'F1', sourceFamilyLabel: 'F1', status: 'QUALIFIED_SUFFICIENT_CONDITION', qualifiedSubjectLabels: ['FAMILY'],
        conflicts: [], unresolved: [], sufficientConditions: [{ subjectLabel:'FAMILY', sourceBasis: 'PHENOTYPE_BEFORE_GENOTYPING', claimIds:[H('6')], sourceRefs: [ref] }] };
    // These four sums independently encode BF = (n/2M)*1 / ((d/M)*(1/2)), M=max(n,d).
    // No implementation under test is called to construct a likelihood or score.
    const maximum = n > d ? n : d;
    const sums = { genotypeAnchor: fraction(1n), genotypeAll: fraction(1n, 2n), phenotypeAnchor: fraction(d, maximum), phenotypeAll: fraction(n, maximum * 2n) };
    // Four sums must also respect joint probability bounds when BF>2.
    if (n > 2n * d) {
        sums.genotypeAll = fraction(d, n);
        sums.phenotypeAnchor = fraction(d, n);
        sums.phenotypeAll = fraction(d, n);
    }
    const remaining = 5 - pp4;
    let points = 0;
    for (const p of [4n, 2n, 1n]) if (remaining >= Number(p) && n * 25n ** p >= d * 52n ** p) { points = Number(p); break; }
    const strength = ({ 4: 'STRONG', 2: 'MODERATE', 1: 'SUPPORTING', 0: null })[points];
    const computation = { schemaVersion: 'pp1-conditional-likelihood-v1', status: 'COMPUTED', reasonCode: 'PP1_FORMAL_LIKELIHOOD_COMPUTED',
        definition: 'P(G_other_observed|Y,typed_founders,G_proband)/P(G_other_observed|typed_founders,G_proband)',
        comparison: 'CONDITIONAL_GENOTYPE_LIKELIHOOD_NOT_THETA_LINKAGE', founderEvidenceConditionedAway: true, operationCount: 40,
        conditionedMemberKeys: ['founder', 'proband'], observedMemberKeys: ['founder', 'proband', 'child'], phenotypeMemberKeys: ['child'],
        fourSums: sums, bayesFactor: bf, direction: sign > 0 ? 'SUPPORTS' : sign < 0 ? 'OPPOSES' : 'UNINFORMATIVE' };
    const family = { familyKey: 'F1', familyContributionId: eventId, factIds: [H('1'), assessmentId], sourceRefs: [ref], sourceReports: [source],
        clinicalObservationIds: [observationId], clinicalAssessmentIds: [assessmentId], computation, alreadyAllocated: false,
        selectionAndReportingReviews: [review], typedConsanguinityStatementPresent: false, founderIndependenceAssumptionUsed: false, selectedForContribution: true };
    const context = { status: 'QUALIFIED', dataClass: 'SYNTHETIC_TEST', catalogSha256: H('2'), adoptionSha256: H('3'), contextKey: digest([scope, H('2')]),
        policy, known: Object.fromEntries(decisions.filter(k => !k.includes('.')).map(k => [k, policy[k]])), needs: [],
        sourceEvidence: { S: parameterSource }, sourceRefs: [{ sourceId: 'S', ...parameterSource }] };
    const e = { schemaVersion: 'pp1-segregation-evaluation-v2', method: api.PP1_FORMAL_METHOD, modelVersion: '1.0.0',
        ruleClause: 'VARVIS_CSLR_TYPED_FOUNDERS_V1/PMID19563646/PMID32720330/PMID38103548', target, context,
        status: points ? 'MET' : 'NOT_MET', strength, awardedPP1Points: points, events: [], needs: [], ignored: [], businessPublishable: false,
        ledger: { status: 'KNOWN', selectionKey: target.selectionKey, scope, allele: target.allele, pp4Points: pp4, eventIds: [], exclusiveFactIds: [] },
        calibration: { base: '2.08', locusCapacity: '5', maximumPP1Strength: 'STRONG', negativeEvidenceAutomaticallyAssignedToBS4: false },
        familyAssessments: [family], selectedFamilyContributionId: eventId, bayesFactor: bf, direction: computation.direction, remainingLocusCapacity: String(remaining),
        requiredFrontendMethodDispatch: true, rawCalibratedPoints: n === 0n ? null : String(sign), positiveCalibratedPoints: sign > 0 ? '1' : '0',
        allocatedPP1Points: sign > 0 ? String(Math.min(remaining, 1)) : '0', log10BayesFactor: n === 0n ? null : String(sign),
        log10BayesFactorStatus: n === 0n ? 'NEGATIVE_INFINITY' : 'FINITE', strengthDetermination: 'EXACT_FRACTION_THRESHOLDS', displayLogPrecision: 64,
        clinicalObservationReview: { schemaVersion: 'pp1-formal-clinical-observations-v1', observations: [state], memberStates: [state], needs: [] } };
    const typedMechanism = {familyKey:'F1', memberKey:'family', memberLabel:'FAMILY', kind:'formalAscertainment', value:'PHENOTYPE_BEFORE_GENOTYPING',
        method:'COMPLETE_SOURCE_MECHANISM_REVIEW', claimIds:[H('6')], sourceRefs:[ref]};
    typedMechanism.id = digest([typedMechanism.familyKey, typedMechanism.memberKey, typedMechanism.kind, typedMechanism.value]);
    family.factIds.push(typedMechanism.id);
    const q = { schemaVersion: 'pp1-source-qualification-v1', sourceIdentity: source, target, identity: { allele: target.allele },
        families:{F1:{sourceFamilyLabel:'F1',members:{FAMILY:'family', 'II-2':'child'}}}, observations: [{id:H('1'), familyKey:'F1', sourceRefs:[ref]},typedMechanism],
        ascertainmentReview: { schemaVersion: 'pp1-formal-ascertainment-review-v1', sourceIdentity: source, target, families: { F1: review } },
        clinicalAssessments: { schemaVersion: 'pp1-formal-clinical-observations-v1', sourceIdentity: source, target, observations: [assessment], needs: [] } };
    return JSON.parse(JSON.stringify({ criterionId: 'ACMG_AMP_2015:PP1', status: e.status, appliedStrength: strength || 'NONE', reasonCode: points ? 'PP1_QUALIFIED_SEGREGATION_THRESHOLD_MET' : 'PP1_QUALIFIED_SEGREGATION_BELOW_THRESHOLD',
        reason: 'Synthetic independent transport fixture', details: { pp1Facts: { schemaVersion: 'pp1-selected-facts-v1', clinicalApproval: false,
            sourceSnapshotSha256: H('5'), evaluation: e, sourceCoverage: [], qualifications: [q], excludedTasks: [] } } }));
}
const evaluated = r => r.details.pp1Facts.evaluation;
let count = 0;
function check(name, fn) { fn(); count += 1; console.log(`PASS ${name}`); }
function accepted(row) { assert.deepEqual(inspect(row), { valid: true, issues: [] }); }
function rejected(row) { assert.equal(valid(row), false, JSON.stringify(inspect(row))); }
check('independent BF 9/4 supporting and empty events accepted without mutation', () => {
    const r = make(), original = JSON.stringify(r); accepted(r); assert.equal(JSON.stringify(r), original);
    const p = details(r); assert.ok(p.some(([key]) => key.includes('完整似然'))); assert.ok(p.some(([, text]) => text.includes('fourSums')));
    assert.ok(p.some(([, text]) => text.includes('同次') || text.includes('观察ID'))); assert.equal(JSON.stringify(r), original);
});
for (const p of [1n, 2n, 4n]) {
    const scale = 10n ** 300n, denominator = 25n ** p * scale, numerator = 52n ** p * scale;
    for (const delta of [-1n, 0n, 1n]) check(`exact 2.08^${p} boundary delta ${delta} beyond float precision`, () => {
        const row = make(numerator + delta, denominator); accepted(row);
        const e = evaluated(row); assert.equal(e.awardedPP1Points, delta < 0n ? p === 1n ? 0 : p === 2n ? 1 : 2 : Number(p));
        if (delta < 0n) { e.awardedPP1Points = Number(p); e.strength = ({ 1: 'SUPPORTING', 2: 'MODERATE', 4: 'STRONG' })[Number(p)]; row.status = e.status = 'MET'; row.appliedStrength = e.strength; rejected(row); }
    });
}
for (const [n, d, label] of [[0n, 1n, 'zero BF negative infinity'], [1n, 2n, 'negative contribution'], [1n, 1n, 'uninformative BF']]) check(label, () => {
    const row = make(n, d); accepted(row); assert.equal(evaluated(row).awardedPP1Points, 0); assert.equal(row.appliedStrength, 'NONE');
    if (!n) assert.ok(details(row).some(([, text]) => text.includes('−∞')));
});
for (const pp4 of [0, 1, 3, 4, 4.5, 5]) check(`PP4 exact capacity ${pp4}`, () => accepted(make(1000n, 1n, pp4)));
const mutations = [
    ['wrong schema', e => e.schemaVersion = 'pp1-segregation-evaluation-v99'],
    ['wrong method', e => e.method = 'OTHER_FORMAL_METHOD'], ['wrong model version', e => e.modelVersion = '2.0.0'],
    ['wrong calibration base', e => e.calibration.base = '2'], ['automatic BS4', e => e.calibration.negativeEvidenceAutomaticallyAssignedToBS4 = true],
    ['wrong rule', e => e.ruleClause = 'OTHER_RULE'], ['wrong BF', e => e.bayesFactor.numerator = '10'],
    ['zero denominator', e => e.bayesFactor.denominator = '0'], ['negative numerator', e => e.bayesFactor.numerator = '-1'],
    ['floating fraction', e => e.bayesFactor.numerator = 9], ['wrong four sums', e => e.familyAssessments[0].computation.fourSums.phenotypeAll.numerator = '0'],
    ['wrong direction', e => e.direction = 'OPPOSES'], ['wrong family direction', e => e.familyAssessments[0].computation.direction = 'OPPOSES'],
    ['floor legacy points', e => { e.rawSegregationPoints = '9'; e.awardedPP1Points = 4; }],
    ['events not formal', e => e.events.push({ eventId: 'fake', rawPoints: '9' })],
    ['wrong capacity', e => e.remainingLocusCapacity = '4.999999999999999999999999999999999999999'],
    ['unknown ledger', e => e.ledger.status = 'UNKNOWN'], ['empty ledger conflict', e => { e.ledger.status = 'KNOWN_EMPTY'; e.ledger.pp4Points = 1; }],
    ['wrong ledger allele', e => e.ledger.allele.position++], ['wrong context scope', e => e.context.policy.scope.inheritance = 'AR'],
    ['unknown model field', e => e.context.policy.formalModel.unimplementedCondition = true],
    ['missing probability', e => delete e.context.policy.formalModel.phenotypeModel.probabilities['2']],
    ['out of range probability', e => e.context.policy.formalModel.phenotypeModel.probabilities['2'] = '1.00000000000000000000001'],
    ['missing parameter source', e => delete e.context.policy.decisionSources['formalModel.pointBase']],
    ['wrong context identity hash', e => e.context.contextKey = H('9')],
    ['changed parameter source pin', e => e.context.sourceEvidence.S.sha256 = H('6')],
    ['changed known parameter', e => e.context.known.formalModel.pointBase = '3'],
    ['duplicate family', e => e.familyAssessments.push(copy(e.familyAssessments[0]))],
    ['selected contribution mismatch', e => e.selectedFamilyContributionId = H('9')],
    ['fact already allocated', e => e.ledger.exclusiveFactIds.push(e.familyAssessments[0].factIds[0])],
    ['event already allocated', e => e.ledger.eventIds.push(e.familyAssessments[0].familyContributionId)],
    ['unknown prior family', e => e.ledger.eventIds.push(H('8'))],
    ['no founder conditioning', e => e.familyAssessments[0].computation.founderEvidenceConditionedAway = false],
    ['ascertainment conflict', e => e.familyAssessments[0].selectionAndReportingReviews[0].conflicts.push({ reasonCode: 'CONFLICT' })],
    ['wrong observation id', e => e.clinicalObservationReview.memberStates[0].sourceObservationId = H('7')],
    ['injected unbound examination age', e => e.clinicalObservationReview.memberStates[0].attainedAge = 45],
    ['observation conflict', e => e.clinicalObservationReview.memberStates[0].issues.push('CONFLICT')],
    ['clinical disease mismatch', e => e.clinicalObservationReview.memberStates[0].diseaseTerms = ['OTHER disease']],
    ['zero log status with positive BF', e => e.log10BayesFactorStatus = 'NEGATIVE_INFINITY'],
    ['blocking source gap with score', e => e.needs.push({ reasonCode: 'SOURCE_CONFLICT', requirement: 'resolve source conflict', blockingEffect: 'BLOCKS_SCORING' })],
];
for (const [name, mutate] of mutations) check(`reject ${name}`, () => { const r = make(); mutate(evaluated(r)); rejected(r); });
for (const [name, mutate] of [
    ['family source pin', r => r.details.pp1Facts.qualifications[0].sourceIdentity.bundleSha256 = H('8')],
    ['family source quote pin', r => r.details.pp1Facts.evaluation.familyAssessments[0].sourceRefs[0].sourceSha256 = 'wrong'],
    ['changed valid-looking raw source pin', r => r.details.pp1Facts.evaluation.familyAssessments[0].sourceRefs[0].sourceSha256 = H('9')],
    ['changed source observation identity', r => { const q = r.details.pp1Facts.qualifications[0]; q.clinicalAssessments.observations[0].observationIdentity.start = 2; }],
    ['changed source disease state', r => r.details.pp1Facts.qualifications[0].clinicalAssessments.observations[0].status = 'UNAFFECTED'],
    ['clinical source target', r => r.details.pp1Facts.qualifications[0].clinicalAssessments.target.selectionKey = 'other'],
    ['clinical missing source observations', r => r.details.pp1Facts.qualifications[0].clinicalAssessments.observations = []],
]) check(`reject ${name}`, () => { const r = make(); mutate(r); rejected(r); });
function synchronizeMechanism(row, change, alsoTyped = false) {
    const f = row.details.pp1Facts, family = f.evaluation.familyAssessments[0], mechanism = family.selectionAndReportingReviews[0];
    change(mechanism);
    for (const q of f.qualifications) {
        q.ascertainmentReview.families[family.familyKey] = copy(mechanism);
        if (alsoTyped) for (const r of q.observations.filter(r => r.familyKey === family.familyKey && r.kind === 'formalAscertainment')) {
            const c = mechanism.sufficientConditions[0]; r.memberLabel = c.subjectLabel; r.value = c.sourceBasis; r.claimIds = copy(c.claimIds); r.sourceRefs = copy(c.sourceRefs);
            const old = r.id; r.id = digest([r.familyKey, r.memberKey, r.kind, r.value]); family.factIds = family.factIds.map(id => id === old ? r.id : id);
        }
    }
}
const mechanismMutations = [
    ['unknown basis in both family and source', m => m.sufficientConditions[0].sourceBasis = 'UNKNOWN_BASIS'],
    ['missing FAMILY subject in both records', m => m.sufficientConditions[0].subjectLabel = 'II-2'],
    ['basis outside adopted ascertainment in both records', m => m.sufficientConditions[0].sourceBasis = 'SELECTION_AND_REPORTING_INDEPENDENT_OF_TARGET_GIVEN_PHENOTYPE'],
    ['empty claims in both records', m => m.sufficientConditions[0].claimIds = []],
    ['unknown claims in both records', m => m.sufficientConditions[0].claimIds = [H('9')]],
    ['wrong source bundle in both records', m => m.sufficientConditions[0].sourceRefs[0].bundleSha256 = H('9')],
    ['wrong valid-looking source pin in both records', m => m.sufficientConditions[0].sourceRefs[0].sourceSha256 = H('9')],
    ['wrong source quote in both records', m => m.sufficientConditions[0].sourceRefs[0].quote = 'OTHER quote'],
    ['unknown status in both reviews', m => m.status = 'UNKNOWN'],
    ['mismatched family label in both reviews', m => m.sourceFamilyLabel = 'OTHER_FAMILY'],
];
for (const [name, change] of mechanismMutations) check(`reject ${name}`, () => { const row = make(); synchronizeMechanism(row, change); rejected(row); });
check('unsupported basis remains rejected when typed observation also forged consistently', () => {
    const r = make(); synchronizeMechanism(r, m => m.sufficientConditions[0].sourceBasis = 'UNKNOWN_BASIS', true); rejected(r);
});
check('wrong source pin remains rejected when typed observation also forged consistently', () => {
    const r = make(); synchronizeMechanism(r, m => m.sufficientConditions[0].sourceRefs[0].sourceSha256 = H('9'), true); rejected(r);
});
check('duplicate same-source UNKNOWN review cannot be hidden by a positive first record', () => {
    const r = make(), f = r.details.pp1Facts, q = copy(f.qualifications[0]); q.ascertainmentReview.families.F1.status = 'UNKNOWN'; f.qualifications.push(q); rejected(r);
});
check('additional source family with UNKNOWN review cannot be omitted from family reports', () => {
    const r = make(), f = r.details.pp1Facts, q = copy(f.qualifications[0]); q.sourceIdentity.bundleSha256 = H('9');
    q.ascertainmentReview.sourceIdentity = copy(q.sourceIdentity); q.ascertainmentReview.families.F1.status = 'UNKNOWN'; f.qualifications.push(q); rejected(r);
});
check('independence condition accepted only with the adopted conditional model', () => {
    const r = make(), e = evaluated(r); e.context.policy.formalModel.ascertainment = e.context.known.formalModel.ascertainment = 'CONDITIONAL_SELECTION_AND_REPORTING_INDEPENDENCE';
    synchronizeMechanism(r, m => m.sufficientConditions[0].sourceBasis = 'SELECTION_AND_REPORTING_INDEPENDENT_OF_TARGET_GIVEN_PHENOTYPE', true); accepted(r);
});
check('display logs cannot award a below-threshold BF', () => {
    const r = make(2n, 1n), e = evaluated(r); e.rawCalibratedPoints = e.positiveCalibratedPoints = '999'; e.allocatedPP1Points = '5'; e.log10BayesFactor = '999'; accepted(r);
    e.awardedPP1Points = 4; e.strength = r.appliedStrength = 'STRONG'; r.status = e.status = 'MET'; rejected(r);
});
function zeroContribution(r, highYield) {
    const e = evaluated(r); r.status = e.status = 'NOT_MET'; r.appliedStrength = 'NONE'; e.strength = null; e.awardedPP1Points = 0;
    e.rawCalibratedPoints = e.allocatedPP1Points = '0';
    for (const k of ['selectedFamilyContributionId', 'bayesFactor', 'direction', 'positiveCalibratedPoints', 'log10BayesFactor', 'log10BayesFactorStatus', 'requiredFrontendMethodDispatch', 'strengthDetermination', 'displayLogPrecision']) delete e[k];
    if (highYield) {
        e.context.policy.locusModel = e.context.known.locusModel = 'HIGH_YIELD_HOMOGENEOUS'; e.familyAssessments = []; delete e.clinicalObservationReview;
        e.ignored = [{reasonCode:'PP1_NOT_INCREMENTAL_TO_HIGH_YIELD_PP4'}];
    } else {
        const f = e.familyAssessments[0]; f.alreadyAllocated = true; delete f.selectedForContribution;
        e.ledger.eventIds.push(f.familyContributionId);
    }
    return r;
}
check('high-yield homogeneous PP4 legal zero contribution', () => accepted(zeroContribution(make(), true)));
check('family previously allocated legal zero contribution without fabricated BF', () => accepted(zeroContribution(make(), false)));
check('zero contribution cannot hide an unallocated family', () => {
    const r = zeroContribution(make(), false); evaluated(r).familyAssessments[0].alreadyAllocated = false; evaluated(r).ledger.eventIds = []; rejected(r);
});
check('compatible additive saved metadata leaves exact validation intact', () => {
    const r = make(); evaluated(r).additionalAuditNotice = {text:'additive display field'}; accepted(r);
});
function ageModel(r) {
    const e = evaluated(r), m = copy(model);
    m.phenotypeModel = {kind:'ATTAINED_AGE_BANDS', bands:[
        {minimumAge:0, maximumAgeExclusive:40, probabilities:{'0':'0.1','1':'0.5','2':'0.5'}},
        {minimumAge:40, maximumAgeExclusive:null, probabilities:{'0':'0.25','1':'0.75','2':'0.75'}},
    ]};
    e.context.policy.formalModel = copy(m); e.context.known.formalModel = copy(m);
    for (const s of [...e.clinicalObservationReview.observations, ...e.clinicalObservationReview.memberStates]) s.attainedAge = 40;
    r.details.pp1Facts.qualifications[0].clinicalAssessments.observations[0].attainedAge = 40;
    return r;
}
check('explicit contiguous attained-age model accepted at band boundary', () => accepted(ageModel(make())));
for (const [name, change] of [
    ['age interval gap', m => m.phenotypeModel.bands[1].minimumAge = 41],
    ['age probability decrease', m => m.phenotypeModel.bands[1].probabilities['1'] = '0.2'],
    ['unknown age condition', m => m.phenotypeModel.bands[0].onsetInsteadOfExamination = true],
    ['phenotype unknown field', m => m.phenotypeModel.otherCondition = true],
]) check(`reject ${name}`, () => {
    const r = ageModel(make()); change(evaluated(r).context.policy.formalModel); evaluated(r).context.known.formalModel = copy(evaluated(r).context.policy.formalModel); rejected(r);
});
check('attained-age model cannot substitute unknown examination age', () => {
    const r = ageModel(make()); evaluated(r).clinicalObservationReview.memberStates[0].attainedAge = null; rejected(r);
});
check('extremely small exact decimal parameter is retained without underflow', () => {
    const r = make(), e = evaluated(r); e.context.policy.formalModel.phenotypeModel.probabilities['0'] = '1e-500';
    e.context.known.formalModel = copy(e.context.policy.formalModel); accepted(r);
});
check('legal missing-policy formal NE keeps needs without inventing BF', () => {
    const r = make(), e = evaluated(r); r.status = e.status = 'NOT_EVALUATED'; r.appliedStrength = 'NONE'; e.strength = null; e.awardedPP1Points = 0;
    e.context = { status: 'KNOWLEDGE_MISSING', policy: { method: api.PP1_FORMAL_METHOD, scope, formalModel: null } }; e.ledger = null;
    e.familyAssessments = []; e.needs = [{ reasonCode: 'PP1_FORMAL_MODEL_REQUIRED', requirement: 'Explicit adopted model required', blockingEffect: 'BLOCKS_SCORING' }];
    for (const k of ['remainingLocusCapacity', 'selectedFamilyContributionId', 'bayesFactor', 'direction', 'rawCalibratedPoints', 'positiveCalibratedPoints', 'allocatedPP1Points', 'log10BayesFactor', 'log10BayesFactorStatus', 'clinicalObservationReview']) delete e[k];
    accepted(r); assert.ok(details(r).some(([, value]) => value.includes('尚未评价')));
    e.status = r.status = 'NOT_MET'; rejected(r);
});
check('normal no-source formal NE may omit calculation metadata', () => {
    const r = make(), e = evaluated(r); r.status = e.status = 'NOT_EVALUATED'; r.appliedStrength = 'NONE'; e.strength = null; e.awardedPP1Points = 0;
    e.context = { status: 'KNOWLEDGE_MISSING' }; e.ledger = null; e.needs = [{ reasonCode: 'PP1_NO_REPLAYABLE_SELECTED_SOURCE', requirement: 'No replayable selected source', blockingEffect: 'BLOCKS_SCORING' }];
    for (const k of Object.keys(e)) if (!['schemaVersion', 'method', 'modelVersion', 'target', 'status', 'strength', 'awardedPP1Points', 'events', 'needs', 'context', 'ledger', 'businessPublishable'].includes(k)) delete e[k];
    r.details.pp1Facts.qualifications = []; accepted(r); e.needs = []; rejected(r);
});
check('formal marker recognition preserves v1 separation and rejects future metadata', () => {
    assert.equal(formal({ schemaVersion: 'pp1-segregation-evaluation-v1', events: [] }), false);
    assert.equal(formal({ schemaVersion: 'pp1-segregation-evaluation-v99', method: api.PP1_FORMAL_METHOD }), true);
    assert.equal(formal(evaluated(make())), true);
});
function wrap(value) {
    if (value.criterionId) return value;
    const facts = value.publicSummary || (value.schemaVersion === 'pp1-business-evaluation-v1'
        ? { schemaVersion: 'pp1-selected-facts-v1', clinicalApproval: false, evaluation: value.evaluation, qualifications: value.qualifications, sourceCoverage: value.sourceCoverage, excludedTasks: value.excludedTasks }
        : value);
    const e = facts.evaluation;
    return { criterionId: 'ACMG_AMP_2015:PP1', status: e.status, appliedStrength: e.strength || 'NONE', reason: 'Captured backend result',
        reasonCode: e.status === 'MET' ? 'PP1_QUALIFIED_SEGREGATION_THRESHOLD_MET' : e.status === 'NOT_MET' ? 'PP1_QUALIFIED_SEGREGATION_BELOW_THRESHOLD' : 'PP1_LITERATURE_REQUIREMENTS_UNRESOLVED', details: { pp1Facts: facts } };
}
for (const filename of process.argv.slice(2)) check(`captured backend ${filename}`, () => {
    const raw = fs.readFileSync(filename), value = JSON.parse(raw), before = JSON.stringify(value), row = wrap(value); accepted(row); details(row);
    assert.equal(JSON.stringify(value), before); assert.deepEqual(fs.readFileSync(filename), raw);
    const altered = copy(row); evaluated(altered).modelVersion = '999.0.0'; rejected(altered);
    if (evaluated(row).status === 'MET') {
        const r = copy(row); evaluated(r).bayesFactor.numerator = '0'; rejected(r);
        for (const [, change] of mechanismMutations) { const mutated = copy(row); synchronizeMechanism(mutated, change); rejected(mutated); }
        const duplicate = copy(row), facts = duplicate.details.pp1Facts, q = copy(facts.qualifications[0]);
        q.ascertainmentReview.families[evaluated(duplicate).familyAssessments[0].familyKey].status = 'UNKNOWN'; facts.qualifications.push(q); rejected(duplicate);
    }
});
console.log(`${count} PP1 formal transport/projection checks passed; synthetic controls and captured JSON remain distinct.`);
