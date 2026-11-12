// Saved-transport validation only. The backend replays raw source bytes and
// scientific policy adoption; this reader does not replace either operation.
export const PP1_FORMAL_METHOD = 'VARVIS_CONDITIONAL_PEDIGREE_LIKELIHOOD_V1';
const SCHEMA = 'pp1-segregation-evaluation-v2';
const RULE = 'VARVIS_CSLR_TYPED_FOUNDERS_V1/PMID19563646/PMID32720330/PMID38103548';
const DEFINITION = 'P(G_other_observed|Y,typed_founders,G_proband)/P(G_other_observed|typed_founders,G_proband)';
const obj = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const str = v => typeof v === 'string' && v.length > 0 && v.length <= 32768;
const hash = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const list = v => Array.isArray(v) && v.length <= 4096;
const texts = v => list(v) && v.every(str);
const unique = v => texts(v) && new Set(v).size === v.length;
const keys = (v, names) => obj(v) && Object.keys(v).length === names.length && names.every(k => Object.hasOwn(v, k));
const canonical = v => Array.isArray(v) ? `[${v.map(canonical).join(',')}]` : obj(v)
    ? `{${Object.keys(v).sort().map(k => `${JSON.stringify(k)}:${canonical(v[k])}`).join(',')}}` : JSON.stringify(v);
const same = (a, b) => canonical(a) === canonical(b);
// Reuse the repository's small-document synchronous SHA-256 algorithm. PP1's
// Python canonical JSON preserves raw Unicode (unlike the NFC PS2 projection),
// so do not feed these identities to the PS2/PM6 identity helper.
function identityDigest(value) {
    const bytes = new TextEncoder().encode(canonical(value));
    const data = new Uint8Array(Math.ceil((bytes.length + 9) / 64) * 64);
    data.set(bytes); data[bytes.length] = 0x80;
    const view = new DataView(data.buffer);
    view.setUint32(data.length - 4, bytes.length * 8);
    const h = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    const k = [0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
        0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
        0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
        0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
        0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
        0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
        0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
        0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
    const rotate = (v, n) => (v >>> n) | (v << (32 - n));
    for (let offset = 0; offset < data.length; offset += 64) {
        const w = new Uint32Array(64);
        for (let i = 0; i < 16; i += 1) w[i] = view.getUint32(offset + i * 4);
        for (let i = 16; i < 64; i += 1) {
            const x = w[i - 15], y = w[i - 2];
            w[i] = w[i - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3)) + w[i - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10));
        }
        let [a,b,c,d,e,f,g,t] = h;
        for (let i = 0; i < 64; i += 1) {
            const one = (t + (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) + ((e & f) ^ (~e & g)) + k[i] + w[i]) | 0;
            const two = ((rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
            t = g; g = f; f = e; e = (d + one) | 0; d = c; c = b; b = a; a = (one + two) | 0;
        }
        [a,b,c,d,e,f,g,t].forEach((v, i) => { h[i] = (h[i] + v) | 0; });
    }
    return h.map(v => (v >>> 0).toString(16).padStart(8, '0')).join('');
}
const fail = code => { throw Error(code); };
const requireValue = (condition, code) => { if (!condition) fail(code); };
const gcd = (a, b) => { while (b) { const t = a % b; a = b; b = t; } return a; };
function rational(n, d = 1n) {
    requireValue(d > 0n, 'PP1_FORMAL_RATIONAL_DENOMINATOR');
    const g = gcd(n < 0n ? -n : n, d);
    return { n: n / g, d: d / g };
}
const cmp = (a, b) => { const n = a.n * b.d - b.n * a.d; return n < 0n ? -1 : n > 0n ? 1 : 0; };
const mul = (a, b) => rational(a.n * b.n, a.d * b.d);
const div = (a, b) => rational(a.n * b.d, a.d * b.n);
const sub = (a, b) => rational(a.n * b.d - b.n * a.d, a.d * b.d);
const ZERO = rational(0n), ONE = rational(1n), FIVE = rational(5n);
function readFraction(v) {
    requireValue(keys(v, ['numerator', 'denominator']) && [v.numerator, v.denominator].every(s =>
        typeof s === 'string' && s.length <= 32768 && /^(0|[1-9][0-9]*)$/.test(s)), 'PP1_FORMAL_RATIONAL_INVALID');
    return rational(BigInt(v.numerator), BigInt(v.denominator));
}
function decimal(v, signed = false) {
    requireValue(typeof v === 'string' && v.length <= 1024 &&
        (signed ? /^-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$/ : /^(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?$/).test(v), 'PP1_FORMAL_DECIMAL_INVALID');
    const [mantissa, exp = '0'] = v.toLowerCase().split('e');
    const exponent = Number(exp), parts = mantissa.split('.');
    requireValue(Number.isSafeInteger(exponent) && Math.abs(exponent) <= 1024, 'PP1_FORMAL_DECIMAL_LIMIT');
    const shift = exponent - (parts[1]?.length || 0), numerator = BigInt(parts.join(''));
    return shift >= 0 ? rational(numerator * 10n ** BigInt(shift)) : rational(numerator, 10n ** BigInt(-shift));
}
const direction = bf => cmp(bf, ONE) > 0 ? 'SUPPORTS' : cmp(bf, ONE) < 0 ? 'OPPOSES' : 'UNINFORMATIVE';
const scopeOf = target => ({ geneSymbol: target.geneSymbol, diseaseId: target.diseaseId, inheritance: target.inheritance });
function validTarget(t) {
    return obj(t) && ['selectionKey', 'geneSymbol', 'diseaseId', 'inheritance'].every(k => str(t[k])) &&
        obj(t.allele) && ['assembly', 'contig', 'reference', 'alternate'].every(k => str(t.allele[k])) &&
        Number.isSafeInteger(t.allele.position) && t.allele.position > 0;
}
const sourceIdentity = s => obj(s) && /^PMC[0-9]+$/.test(s.pmcid || '') && hash(s.bundleSha256);
const refValid = (r, identity) => obj(r) && str(r.unitId) && str(r.quote) && hash(r.sourceSha256) &&
    sourceIdentity(r) && (!identity || r.pmcid === identity.pmcid && r.bundleSha256 === identity.bundleSha256);
const refsValid = (v, identity, nonempty = true) => list(v) && (!nonempty || v.length > 0) && v.every(r => refValid(r, identity));
const needsValid = needs => list(needs) && needs.every(n => obj(n) && str(n.reasonCode) && str(n.requirement) &&
    ['BLOCKS_SCORING', 'MAY_ADD_INFORMATION', 'MAY_ADD_EVENT'].includes(n.blockingEffect));
const blocks = needs => needs.some(n => n.blockingEffect === 'BLOCKS_SCORING');

function validateModel(model) {
    requireValue(keys(model, ['schemaVersion', 'version', 'genotypeModel', 'founderConditioning', 'ascertainment', 'pointBase', 'phenotypeModel']) &&
        model.schemaVersion === 'pp1-formal-parameter-model-v1' && model.version === '1.0.0' &&
        model.genotypeModel === 'AUTOSOMAL_SINGLE_ALLELE_DOSAGE' && model.founderConditioning === 'ALL_FOUNDERS_TYPED' &&
        ['PHENOTYPE_BEFORE_GENOTYPING', 'CONDITIONAL_SELECTION_AND_REPORTING_INDEPENDENCE'].includes(model.ascertainment) &&
        model.pointBase === '2.08', 'PP1_FORMAL_MODEL_UNSUPPORTED');
    const probabilities = v => {
        requireValue(keys(v, ['0', '1', '2']), 'PP1_FORMAL_DOSAGE_PROBABILITIES_REQUIRED');
        return ['0', '1', '2'].map(k => {
            const text = v[k], p = decimal(text);
            const parts = text.toLowerCase().split('e'), mantissa = parts[0].replace('.', '').replace(/^0+/, '') || '0';
            const exponent = Number(parts[1] || 0), scale = exponent - (parts[0].split('.')[1]?.length || 0);
            requireValue(text.length <= 64 && mantissa.length <= 24 && Math.abs(exponent) <= 500 && Math.abs(scale) <= 500 &&
                cmp(p, ZERO) >= 0 && cmp(p, ONE) <= 0, 'PP1_FORMAL_PROBABILITY_INVALID');
            return p;
        });
    };
    const phenotype = model.phenotypeModel;
    if (phenotype?.kind === 'AGE_INDEPENDENT_BINARY_STATUS') {
        requireValue(keys(phenotype, ['kind', 'probabilities']), 'PP1_FORMAL_PHENOTYPE_FIELDS_UNSUPPORTED');
        probabilities(phenotype.probabilities);
    } else {
        requireValue(keys(phenotype, ['kind', 'bands']) && phenotype.kind === 'ATTAINED_AGE_BANDS' &&
            Array.isArray(phenotype.bands) && phenotype.bands.length > 0 && phenotype.bands.length <= 64, 'PP1_FORMAL_PHENOTYPE_MODEL_UNSUPPORTED');
        let previous = 0, before;
        phenotype.bands.forEach((band, index) => {
            requireValue(keys(band, ['minimumAge', 'maximumAgeExclusive', 'probabilities']) && Number.isSafeInteger(band.minimumAge) &&
                band.minimumAge >= 0 && band.minimumAge === previous && (index === phenotype.bands.length - 1 ? band.maximumAgeExclusive === null :
                    Number.isSafeInteger(band.maximumAgeExclusive) && band.maximumAgeExclusive > band.minimumAge), 'PP1_FORMAL_AGE_BAND_INVALID');
            const current = probabilities(band.probabilities);
            requireValue(!before || current.every((p, i) => cmp(p, before[i]) >= 0), 'PP1_FORMAL_CUMULATIVE_PROBABILITY_CONFLICT');
            before = current; previous = band.maximumAgeExclusive;
        });
    }
}

function validateContext(e, scored) {
    const c = e.context, p = c?.policy;
    requireValue(obj(c) && str(c.status), 'PP1_FORMAL_CONTEXT_REQUIRED');
    if (p) requireValue(obj(p) && p.method === PP1_FORMAL_METHOD && same(p.scope, scopeOf(e.target)), 'PP1_FORMAL_CONTEXT_IDENTITY');
    if (!scored) return;
    requireValue(c.status === 'QUALIFIED' && ['PUBLIC_RESEARCH', 'SYNTHETIC_TEST'].includes(c.dataClass) &&
        ['catalogSha256', 'adoptionSha256', 'contextKey'].every(k => hash(c[k])) && obj(p) &&
        ['AD', 'AR'].includes(e.target.inheritance) && /^(?:[1-9]|1[0-9]|2[0-2])$/.test(e.target.allele.contig) &&
        ['STRONG', 'DEFINITIVE'].includes(p.geneDiseaseValidity) && p.expertPolicyStatus === 'GENERAL_ALLOWED' &&
        p.penetrance === 'EXPLICIT_FORMAL_MODEL' && p.phenocopy === 'EXPLICIT_FORMAL_MODEL' &&
        ['HETEROGENEOUS', 'HIGH_YIELD_HOMOGENEOUS'].includes(p.locusModel) && texts(p.affectedTerms) && p.affectedTerms.length > 0 &&
        texts(p.candidateAssessmentMethods) && p.candidateAssessmentMethods.length > 0 && obj(c.sourceEvidence) &&
        obj(p.decisionSources) && list(c.sourceRefs) && (!c.needs || needsValid(c.needs) && !blocks(c.needs)), 'PP1_FORMAL_POLICY_PROVENANCE');
    requireValue(c.contextKey === identityDigest([p.scope, c.catalogSha256]), 'PP1_FORMAL_CONTEXT_KEY_MISMATCH');
    const decisions = ['method', 'geneDiseaseValidity', 'expertPolicyStatus', 'affectedTerms', 'penetrance', 'phenocopy', 'locusModel',
        'candidateAssessmentMethods', 'formalModel', 'formalModel.ascertainment', 'formalModel.pointBase'];
    if (Object.hasOwn(p, 'affectedNativeTableTerms')) {
        requireValue(texts(p.affectedNativeTableTerms), 'PP1_FORMAL_AFFECTED_DEFINITION');
        decisions.push('affectedNativeTableTerms');
    }
    for (const name of decisions) {
        const ids = p.decisionSources[name];
        requireValue(unique(ids) && ids.length > 0 && ids.every(id => {
            const source = c.sourceEvidence[id], original = p.sources?.[id];
            return obj(source) && hash(source.sha256) && str(source.quote) && str(source.locator) && obj(original) &&
                original.sha256 === source.sha256 && original.quote === source.quote && original.locator === source.locator &&
                c.sourceRefs.some(r => r.sourceId === id && r.sha256 === source.sha256 && r.quote === source.quote && r.locator === source.locator);
        }), 'PP1_FORMAL_PARAMETER_SOURCE_IDENTITY');
    }
    if (c.known) for (const name of decisions.filter(k => !k.includes('.'))) {
        requireValue(same(c.known[name], p[name]), 'PP1_FORMAL_KNOWN_PARAMETER_CONFLICT');
    }
    validateModel(p.formalModel);
}

function validateLedger(e, required) {
    const l = e.ledger;
    if (!l && !required) return null;
    requireValue(obj(l), 'PP1_FORMAL_LEDGER_REQUIRED');
    // A preserved unknown ledger is a gap, never an empty allocation.
    if (!['KNOWN', 'KNOWN_EMPTY'].includes(l.status) && !required) return null;
    requireValue(['KNOWN', 'KNOWN_EMPTY'].includes(l.status) && l.selectionKey === e.target.selectionKey &&
        same(l.scope, scopeOf(e.target)) && same(l.allele, e.target.allele) && unique(l.eventIds) && unique(l.exclusiveFactIds), 'PP1_FORMAL_LEDGER_IDENTITY');
    const used = typeof l.pp4Points === 'number' ? (Number.isFinite(l.pp4Points) ? decimal(String(l.pp4Points)) : fail('PP1_FORMAL_PP4_POINTS')) : decimal(l.pp4Points);
    requireValue(cmp(used, ZERO) >= 0 && cmp(used, FIVE) <= 0 && (l.status !== 'KNOWN_EMPTY' ||
        cmp(used, ZERO) === 0 && !l.eventIds.length && !l.exclusiveFactIds.length), 'PP1_FORMAL_PP4_POINTS');
    const remaining = sub(FIVE, used);
    if (required || Object.hasOwn(e, 'remainingLocusCapacity')) requireValue(cmp(decimal(e.remainingLocusCapacity), remaining) === 0, 'PP1_FORMAL_CAPACITY_MISMATCH');
    return remaining;
}

function validateComputation(c) {
    requireValue(obj(c) && c.schemaVersion === 'pp1-conditional-likelihood-v1' && c.status === 'COMPUTED' &&
        c.reasonCode === 'PP1_FORMAL_LIKELIHOOD_COMPUTED' && c.definition === DEFINITION &&
        c.comparison === 'CONDITIONAL_GENOTYPE_LIKELIHOOD_NOT_THETA_LINKAGE' && c.founderEvidenceConditionedAway === true &&
        Number.isSafeInteger(c.operationCount) && c.operationCount > 0 && c.operationCount <= 1000000 &&
        ['conditionedMemberKeys', 'observedMemberKeys', 'phenotypeMemberKeys'].every(k => unique(c[k]) && c[k].length <= 32) &&
        c.conditionedMemberKeys.length > 0 && c.conditionedMemberKeys.every(m => c.observedMemberKeys.includes(m)), 'PP1_FORMAL_COMPUTATION_METADATA');
    requireValue(keys(c.fourSums, ['genotypeAnchor', 'genotypeAll', 'phenotypeAnchor', 'phenotypeAll']), 'PP1_FORMAL_FOUR_SUMS_REQUIRED');
    const ga = readFraction(c.fourSums.genotypeAnchor), go = readFraction(c.fourSums.genotypeAll),
        ya = readFraction(c.fourSums.phenotypeAnchor), yo = readFraction(c.fourSums.phenotypeAll), bf = readFraction(c.bayesFactor);
    requireValue(ga.n > 0n && go.n > 0n && ya.n > 0n && cmp(go, ga) <= 0 && cmp(ya, ga) <= 0 &&
        cmp(yo, ya) <= 0 && cmp(yo, go) <= 0 && cmp(div(mul(yo, ga), mul(ya, go)), bf) === 0 &&
        c.direction === direction(bf), 'PP1_FORMAL_BAYES_FACTOR_MISMATCH');
    return bf;
}

function validateFamilies(e, f) {
    const assessments = e.familyAssessments || [];
    requireValue(list(assessments) && new Set(assessments.map(r => r.familyContributionId)).size === assessments.length &&
        new Set(assessments.map(r => r.familyKey)).size === assessments.length, 'PP1_FORMAL_DUPLICATE_FAMILY');
    const values = new Map();
    for (const family of assessments) {
        requireValue(obj(family) && hash(family.familyContributionId) && str(family.familyKey) && unique(family.factIds) && family.factIds.length > 0 &&
            refsValid(family.sourceRefs) && list(family.sourceReports) && family.sourceReports.length > 0 && family.sourceReports.every(sourceIdentity) &&
            family.sourceRefs.every(r => family.sourceReports.some(s => r.pmcid === s.pmcid && r.bundleSha256 === s.bundleSha256)) &&
            typeof family.alreadyAllocated === 'boolean' && typeof family.typedConsanguinityStatementPresent === 'boolean' &&
            family.founderIndependenceAssumptionUsed === false && unique(family.clinicalObservationIds) && unique(family.clinicalAssessmentIds) &&
            (!Object.hasOwn(family, 'selectedForContribution') || family.selectedForContribution === true), 'PP1_FORMAL_FAMILY_IDENTITY');
        requireValue(family.familyContributionId === identityDigest([e.target.allele, scopeOf(e.target), family.familyKey, 'PP1_FAMILY_CONTRIBUTION']), 'PP1_FORMAL_CONTRIBUTION_ID_MISMATCH');
        const bf = validateComputation(family.computation);
        values.set(family.familyContributionId, bf);
        const direct = e.ledger?.eventIds?.includes(family.familyContributionId) || family.factIds.some(id => e.ledger?.exclusiveFactIds?.includes(id));
        requireValue(!direct || family.alreadyAllocated, 'PP1_FORMAL_SHARED_FACT_REUSED');
        // Legacy allocation IDs cannot be regenerated from the public summary's
        // marginalized graph. Their conservative exclusion must remain excluded.
        requireValue(!family.alreadyAllocated || family.selectedForContribution !== true, 'PP1_FORMAL_ALLOCATED_FAMILY_SELECTED');
        requireValue(list(family.selectionAndReportingReviews) && family.selectionAndReportingReviews.length > 0 &&
            family.selectionAndReportingReviews.every(m => obj(m) && m.familyKey === family.familyKey &&
                m.status === 'QUALIFIED_SUFFICIENT_CONDITION' && texts(m.qualifiedSubjectLabels) && m.qualifiedSubjectLabels.includes('FAMILY') &&
                list(m.conflicts) && !m.conflicts.length && list(m.unresolved) && !m.unresolved.length &&
                list(m.sufficientConditions) && m.sufficientConditions.length > 0), 'PP1_FORMAL_ASCERTAINMENT_REVIEW');
        // Every source that contributes this family must carry its own usable
        // mechanism review. A positive first record cannot mask another unknown
        // or conflicting review of the same source, or an omitted family report.
        const qualifiedReports = f.qualifications.filter(q => obj(q) && (Object.hasOwn(q.families || {}, family.familyKey) ||
            family.sourceReports.some(report => same(report, q.sourceIdentity))));
        requireValue(qualifiedReports.length > 0 && new Set(family.sourceReports.map(canonical)).size === family.sourceReports.length &&
            family.sourceReports.every(report => qualifiedReports.some(q => same(q.sourceIdentity, report))) &&
            qualifiedReports.every(q => family.sourceReports.some(report => same(report, q.sourceIdentity))) &&
            family.selectionAndReportingReviews.every(m => qualifiedReports.some(q => same(q.ascertainmentReview?.families?.[family.familyKey], m))),
        'PP1_FORMAL_SOURCE_REPORT_COVERAGE');
        for (const q of qualifiedReports) {
            const report = q.sourceIdentity, packet = q.ascertainmentReview, mechanism = packet?.families?.[family.familyKey], roster = q.families?.[family.familyKey];
            requireValue(q.schemaVersion === 'pp1-source-qualification-v1' && same(q.target, e.target) &&
                q.identity && same(q.identity.allele, e.target.allele) && same(packet?.sourceIdentity, report) &&
                same(packet?.target, e.target) && packet?.schemaVersion === 'pp1-formal-ascertainment-review-v1' &&
                obj(roster) && str(roster.sourceFamilyLabel) && obj(roster.members) && list(q.observations) &&
                mechanism?.familyKey === family.familyKey && mechanism.sourceFamilyLabel === roster.sourceFamilyLabel &&
                family.selectionAndReportingReviews.some(m => same(mechanism, m)), 'PP1_FORMAL_SOURCE_REPORT_IDENTITY');
            const conditions = mechanism.sufficientConditions;
            const allowed = ['PHENOTYPE_BEFORE_GENOTYPING'];
            if (e.context?.policy?.formalModel?.ascertainment === 'CONDITIONAL_SELECTION_AND_REPORTING_INDEPENDENCE') {
                allowed.push('SELECTION_AND_REPORTING_INDEPENDENT_OF_TARGET_GIVEN_PHENOTYPE');
            }
            const knownBases = ['PHENOTYPE_BEFORE_GENOTYPING', 'SELECTION_AND_REPORTING_INDEPENDENT_OF_TARGET_GIVEN_PHENOTYPE'];
            requireValue(conditions.every(condition => obj(condition) && str(condition.subjectLabel) && knownBases.includes(condition.sourceBasis) &&
                unique(condition.claimIds) && condition.claimIds.length > 0 && condition.claimIds.every(hash) && refsValid(condition.sourceRefs, report) &&
                condition.sourceRefs.every(ref => family.sourceRefs.some(original => same(ref, original))) &&
                q.observations.some(r => r.familyKey === family.familyKey && r.memberLabel === condition.subjectLabel &&
                    r.memberKey === roster.members[condition.subjectLabel] && r.kind === 'formalAscertainment' && r.value === condition.sourceBasis &&
                    r.method === 'COMPLETE_SOURCE_MECHANISM_REVIEW' && hash(r.id) && r.id === identityDigest([r.familyKey, r.memberKey, r.kind, r.value]) &&
                    family.factIds.includes(r.id) && same(r.claimIds, condition.claimIds) && same(r.sourceRefs, condition.sourceRefs))) &&
                conditions.some(condition => condition.subjectLabel === 'FAMILY' && allowed.includes(condition.sourceBasis)),
            'PP1_FORMAL_ASCERTAINMENT_CONDITION_BINDING');
        }
        const sourceRows = f.qualifications.filter(q => family.sourceReports.some(report => same(report, q.sourceIdentity)))
            .flatMap(q => [...(q.observations || []), ...(q.clinicalAssessments?.observations || [])]);
        requireValue(family.factIds.every(id => sourceRows.some(r => r.familyKey === family.familyKey && r.id === id)) &&
            family.sourceRefs.every(ref => sourceRows.some(r => (r.sourceRefs || []).some(original => same(ref, original)))), 'PP1_FORMAL_FAMILY_ORIGINAL_BINDING');
    }
    return values;
}

function validateClinical(e, f, selected) {
    const review = e.clinicalObservationReview;
    requireValue(obj(review) && review.schemaVersion === 'pp1-formal-clinical-observations-v1' && list(review.observations) &&
        list(review.memberStates) && needsValid(review.needs) && !blocks(review.needs), 'PP1_FORMAL_CLINICAL_REVIEW');
    const states = review.memberStates.filter(r => r.familyKey === selected.familyKey);
    requireValue(states.length > 0 && same([...selected.clinicalObservationIds].sort(), [...new Set(states.map(r => r.sourceObservationId))].sort()) &&
        same([...selected.clinicalAssessmentIds].sort(), [...new Set(states.flatMap(r => r.assessmentIds))].sort()) &&
        selected.computation.phenotypeMemberKeys.every(key => states.some(r => r.memberKey === key)) &&
        states.every(r => selected.computation.phenotypeMemberKeys.includes(r.memberKey)), 'PP1_FORMAL_CLINICAL_MEMBER_IDENTITY');
    for (const state of states) {
        const identity = state.observationIdentity;
        requireValue(obj(identity) && sourceIdentity(identity) && identity.familyKey === state.familyKey && identity.memberKey === state.memberKey &&
            hash(state.sourceObservationId) && state.sourceObservationId === identityDigest(identity) && str(state.memberKey) && str(state.memberLabel) && ['AFFECTED', 'UNAFFECTED'].includes(state.status) &&
            unique(state.assessmentIds) && state.assessmentIds.length > 0 && unique(state.claimIds) && state.claimIds.length > 0 &&
            unique(state.statusFactIds) && unique(state.ageFactIds) && texts(state.diseaseTerms) && list(state.issues) && !state.issues.length &&
            refsValid(state.sourceRefs, identity) && (state.attainedAge === null || Number.isSafeInteger(state.attainedAge) && state.attainedAge >= 0 && state.attainedAge <= 999) &&
            (e.context.policy.formalModel.phenotypeModel.kind === 'AGE_INDEPENDENT_BINARY_STATUS' || state.attainedAge !== null) &&
            state.diseaseTerms.every(term => e.context.policy.affectedTerms.includes(term)) && (state.status !== 'AFFECTED' || state.diseaseTerms.length > 0), 'PP1_FORMAL_CLINICAL_OBSERVATION_BINDING');
        const q = f.qualifications.find(item => item.sourceIdentity?.pmcid === identity.pmcid && item.sourceIdentity?.bundleSha256 === identity.bundleSha256);
        requireValue(q?.clinicalAssessments?.schemaVersion === review.schemaVersion && same(q.clinicalAssessments.sourceIdentity, q.sourceIdentity) &&
            same(q.clinicalAssessments.target, e.target) && list(q.clinicalAssessments.observations) && state.assessmentIds.every(id =>
                q.clinicalAssessments.observations.some(r => r.id === id && r.sourceObservationId === state.sourceObservationId &&
                    same(r.observationIdentity, identity) && r.familyKey === state.familyKey && r.memberKey === state.memberKey && refsValid(r.sourceRefs, identity))), 'PP1_FORMAL_CLINICAL_SOURCE_IDENTITY');
        const originals = q.clinicalAssessments.observations.filter(r => state.assessmentIds.includes(r.id));
        requireValue(originals.filter(r => r.status).every(r => r.status === state.status) &&
            originals.some(r => r.status === state.status) && originals.filter(r => r.attainedAge !== null && r.attainedAge !== undefined).every(r => r.attainedAge === state.attainedAge) &&
            (state.attainedAge === null || originals.some(r => r.attainedAge === state.attainedAge)), 'PP1_FORMAL_CLINICAL_STATE_CONFLICT');
    }
}

function validateDisplayLogs(e, bf, remaining) {
    requireValue(e.strengthDetermination === 'EXACT_FRACTION_THRESHOLDS' && e.displayLogPrecision === 64 &&
        e.log10BayesFactorStatus === (bf.n === 0n ? 'NEGATIVE_INFINITY' : 'FINITE'), 'PP1_FORMAL_LOG_METADATA');
    const positive = decimal(e.positiveCalibratedPoints), allocated = decimal(e.allocatedPP1Points);
    requireValue(cmp(positive, ZERO) >= 0 && cmp(allocated, cmp(positive, remaining) < 0 ? positive : remaining) === 0, 'PP1_FORMAL_DISPLAY_ALLOCATION');
    if (bf.n === 0n) requireValue(e.log10BayesFactor === null && e.rawCalibratedPoints === null && cmp(positive, ZERO) === 0, 'PP1_FORMAL_ZERO_BF_LOG');
    else {
        const raw = decimal(e.rawCalibratedPoints, true), log = decimal(e.log10BayesFactor, true), sign = cmp(bf, ONE);
        requireValue(Math.sign(cmp(raw, ZERO)) === sign && Math.sign(cmp(log, ZERO)) === sign &&
            cmp(positive, sign > 0 ? raw : ZERO) === 0, 'PP1_FORMAL_LOG_DIRECTION');
    }
    // Display logs are intentionally not inputs to strength. Exact identity and
    // threshold tests below remain rational even for enormous integer strings.
}

export const isPp1FormalEvaluation = e => obj(e) && (e.schemaVersion === SCHEMA || Object.hasOwn(e, 'method') || Object.hasOwn(e, 'modelVersion'));

export function inspectPp1FormalCriterion(row, _profile) {
    try {
        const f = row?.details?.pp1Facts, e = f?.evaluation;
        requireValue(row?.criterionId === 'ACMG_AMP_2015:PP1' && str(row.reasonCode) && typeof row.reason === 'string' &&
            f?.schemaVersion === 'pp1-selected-facts-v1' && f.clinicalApproval === false && list(f.sourceCoverage) && list(f.qualifications) &&
            obj(e) && e.schemaVersion === SCHEMA && e.method === PP1_FORMAL_METHOD && e.modelVersion === '1.0.0' &&
            ['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(e.status) && e.status === row.status && e.businessPublishable === false &&
            validTarget(e.target) && list(e.events) && e.events.length === 0 && needsValid(e.needs), 'PP1_FORMAL_TRANSPORT_INVALID');
        if (Object.hasOwn(f, 'sourceSnapshotSha256')) requireValue(hash(f.sourceSnapshotSha256), 'PP1_FORMAL_SNAPSHOT_IDENTITY');
        if (Object.hasOwn(e, 'ruleClause')) requireValue(e.ruleClause === RULE, 'PP1_FORMAL_RULE_UNSUPPORTED');
        if (Object.hasOwn(e, 'calibration')) requireValue(e.calibration?.base === '2.08' && e.calibration.locusCapacity === '5' &&
            e.calibration.maximumPP1Strength === 'STRONG' && e.calibration.negativeEvidenceAutomaticallyAssignedToBS4 === false, 'PP1_FORMAL_CALIBRATION_UNSUPPORTED');
        validateContext(e, e.status !== 'NOT_EVALUATED');
        const remaining = validateLedger(e, e.status !== 'NOT_EVALUATED');
        const values = validateFamilies(e, f);
        if (e.status === 'NOT_EVALUATED') {
            requireValue(row.appliedStrength === 'NONE' && e.strength === null && e.awardedPP1Points === 0 && blocks(e.needs) &&
                !Object.hasOwn(e, 'selectedFamilyContributionId') && !Object.hasOwn(e, 'bayesFactor') &&
                !(e.familyAssessments || []).some(r => r.selectedForContribution), 'PP1_FORMAL_UNEVALUATED_CONTRADICTION');
            return { valid: true, issues: [] };
        }
        requireValue(e.ruleClause === RULE && obj(e.calibration) && !blocks(e.needs), 'PP1_FORMAL_SCORING_REQUIREMENTS');
        requireValue(!([...values.values()].some(v => cmp(v, ONE) < 0) && [...values.values()].some(v => cmp(v, ONE) > 0)), 'PP1_FORMAL_FAMILIES_DISAGREE');
        const selected = (e.familyAssessments || []).filter(r => r.selectedForContribution === true);
        if (!selected.length) {
            // Early, explicit zero contribution: homogeneous high-yield PP4 or
            // all source families already allocated. No BF is invented.
            requireValue(e.status === 'NOT_MET' && e.strength === null && e.awardedPP1Points === 0 && row.appliedStrength === 'NONE' &&
                e.rawCalibratedPoints === '0' && e.allocatedPP1Points === '0' && !Object.hasOwn(e, 'bayesFactor') &&
                !Object.hasOwn(e, 'selectedFamilyContributionId') && (e.context.policy.locusModel === 'HIGH_YIELD_HOMOGENEOUS' ||
                    e.familyAssessments?.length > 0 && e.familyAssessments.every(r => r.alreadyAllocated)), 'PP1_FORMAL_ZERO_CONTRIBUTION_INVALID');
            return { valid: true, issues: [] };
        }
        requireValue(selected.length === 1 && !selected[0].alreadyAllocated && e.context.policy.locusModel === 'HETEROGENEOUS' &&
            e.requiredFrontendMethodDispatch === true && selected[0].familyContributionId === e.selectedFamilyContributionId &&
            !e.ledger.eventIds.length && !e.ledger.exclusiveFactIds.length, 'PP1_FORMAL_SELECTION_INVALID');
        const bf = readFraction(e.bayesFactor), chosen = values.get(e.selectedFamilyContributionId);
        requireValue(cmp(bf, chosen) === 0 && e.direction === direction(bf) &&
            [...values.values()].every(v => !(cmp(v, ONE) < 0 && cmp(bf, ONE) > 0 || cmp(v, ONE) > 0 && cmp(bf, ONE) < 0)) &&
            (e.familyAssessments || []).filter(r => !r.alreadyAllocated).every(r => cmp(values.get(r.familyContributionId), bf) <= 0), 'PP1_FORMAL_SELECTED_BF_INVALID');
        validateClinical(e, f, selected[0]);
        validateDisplayLogs(e, bf, remaining);
        let points = 0;
        for (const p of [4, 2, 1]) if (cmp(remaining, rational(BigInt(p))) >= 0 &&
            cmp(bf, rational(52n ** BigInt(p), 25n ** BigInt(p))) >= 0) { points = p; break; }
        const strength = ({ 4: 'STRONG', 2: 'MODERATE', 1: 'SUPPORTING', 0: null })[points];
        requireValue(e.awardedPP1Points === points && e.strength === strength && row.appliedStrength === (strength || 'NONE') &&
            (e.status === 'MET') === (points > 0), 'PP1_FORMAL_EXACT_STRENGTH_MISMATCH');
        return { valid: true, issues: [] };
    } catch (error) {
        return { valid: false, issues: [typeof error?.message === 'string' && error.message.startsWith('PP1_') ? error.message : 'PP1_FORMAL_MALFORMED_VALUE'] };
    }
}

export const validatePp1FormalCriterion = (row, profile) => inspectPp1FormalCriterion(row, profile).valid;

const shown = v => v === undefined ? '未提供' : v === null ? '未核定' : typeof v === 'string' ? v : JSON.stringify(v);
const shownFraction = v => obj(v) ? `${shown(v.numerator)}/${shown(v.denominator)}` : '未计算';
const shownRefs = refs => (Array.isArray(refs) ? refs : []).map(r => `${r.pmcid || r.sourceId || ''} ${r.locator || r.unitId || ''}；${r.quote || ''}；SHA256 ${r.sourceSha256 || r.sha256 || '未提供'}`).join('\n');
export function pp1FormalResultDetails(criterion) {
    const f = criterion?.details?.pp1Facts, e = f?.evaluation;
    if (criterion?.criterionId !== 'ACMG_AMP_2015:PP1' || !isPp1FormalEvaluation(e)) return [];
    const rows = [
        ['共分离正式方法', `${e.method}；模型 ${shown(e.modelVersion)}；${shown(e.schemaVersion)}`],
        ['方法用途与边界', '给定表型、已分型创始者及先证者后的基因型条件似然；科研结果，未授予临床批准。负向结果不自动采用 BS4。'],
        ['当前正式结果', e.status === 'NOT_EVALUATED' ? '尚未评价；缺项和冲突未解决，不能解释成零分或未满足。' :
            `${e.status === 'MET' ? '满足' : '未满足'}；采用强度 ${e.strength || '无'}；最终贡献 ${shown(e.awardedPP1Points)} 分`],
        ['精确贝叶斯因子 BF', `${shownFraction(e.bayesFactor)}；${({ SUPPORTS: '支持', OPPOSES: '反向', UNINFORMATIVE: '中性' })[e.direction] || '尚未计算'}`],
        ['校准与位点容量', `基数 ${e.calibration?.base || '未提供'}；PP4 已用 ${shown(e.ledger?.pp4Points)}；剩余 ${shown(e.remainingLocusCapacity)}；强度依精确有理数阈值，最高 Strong`],
        ['对数（仅展示）', `log10 BF ${e.log10BayesFactorStatus === 'NEGATIVE_INFINITY' ? '−∞（BF=0）' : shown(e.log10BayesFactor)}；原始校准 ${shown(e.rawCalibratedPoints)}；正向部分 ${shown(e.positiveCalibratedPoints)}；容量内分配 ${shown(e.allocatedPP1Points)}`],
        ['知识与参数状态', `${shown(e.context?.status)}；${shown(e.context?.dataClass)}；catalog SHA256 ${shown(e.context?.catalogSha256)}；采用记录 SHA256 ${shown(e.context?.adoptionSha256)}`],
        ['采用的正式参数', shown(e.context?.policy?.formalModel)],
        ['原件与选择身份', `来源快照 ${shown(f.sourceSnapshotSha256)}；选择 ${shown(e.target?.selectionKey)}；${shown(e.target)}`],
        ['PP4 排他事实与事件', `账本 ${shown(e.ledger?.status)}；既有贡献 ${shown(e.ledger?.eventIds)}；排他事实 ${shown(e.ledger?.exclusiveFactIds)}`],
    ];
    for (const [id, source] of Object.entries(e.context?.sourceEvidence || {})) rows.push([`参数来源 ${id}`, shownRefs([{ sourceId: id, ...source }])]);
    for (const [index, family] of (Array.isArray(e.familyAssessments) ? e.familyAssessments : []).filter(obj).entries()) {
        const label = `家系 ${index + 1}`;
        rows.push([`${label}贡献身份`, `${family.familyKey}；familyContributionId ${family.familyContributionId}；${family.alreadyAllocated ? '此前已分配，不再计入' : family.selectedForContribution ? '本次选用' : '本次未选用'}；排他事实 ${shown(family.factIds)}`],
            [`${label}完整似然`, `BF ${shownFraction(family.computation?.bayesFactor)}；${shown(family.computation?.direction)}；fourSums ${shown(family.computation?.fourSums)}`],
            [`${label}条件化成员`, `${shown(family.computation?.conditionedMemberKeys)}；已观察基因型 ${shown(family.computation?.observedMemberKeys)}；表型成员 ${shown(family.computation?.phenotypeMemberKeys)}`],
            [`${label}取样与报告机制`, shown(family.selectionAndReportingReviews)],
            [`${label}同次临床观察`, `观察ID ${shown(family.clinicalObservationIds)}；核查ID ${shown(family.clinicalAssessmentIds)}`],
            [`${label}原文与位置`, shownRefs(family.sourceRefs)]);
    }
    for (const q of (Array.isArray(f.qualifications) ? f.qualifications : []).filter(obj)) {
        rows.push([`${q.sourceIdentity?.pmcid || '来源'}取样机制核查`, shown(q.ascertainmentReview)],
            [`${q.sourceIdentity?.pmcid || '来源'}临床原始核查`, shown(q.clinicalAssessments)]);
    }
    if (e.clinicalObservationReview) rows.push(['同次临床观察复核', shown(e.clinicalObservationReview)]);
    for (const [i, need] of (Array.isArray(e.needs) ? e.needs : []).filter(obj).entries()) rows.push([`缺项或冲突 ${i + 1}`, `${need.reasonCode}；${need.requirement}；${need.blockingEffect === 'BLOCKS_SCORING' ? '阻止评分' : '可补充信息'}；范围 ${shown(need.scope)}；已有 ${shown(need.knownFacts || need.known)}；需要 ${shown(need.expected)}；实际 ${shown(need.actual)}；获取路径 ${shown(need.route)}\n${shownRefs(need.sourceLocators || need.sourceRefs)}`]);
    if (e.ignored?.length) rows.push(['未计入的贡献及限制', shown(e.ignored)]);
    rows.push(['核验边界', '本页核对保存结构、身份对应、精确BF和强度一致性；不代替后台原件字节重放、参数科学采用或临床审核。']);
    return rows;
}
