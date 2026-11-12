// A bounded public display reader. Hash consistency is not source authentication:
// the server must re-project the actual immutable module/material before serving it.
const own = (o, k) => Object.hasOwn(o || {}, k);
const object = o => o !== null && typeof o === 'object' && !Array.isArray(o);
const keys = (o, allowed) => object(o) && Object.keys(o).every(k => allowed.includes(k));
const hash = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const id = v => typeof v === 'string' && /^[A-Za-z0-9_.:@+-]{1,160}$/.test(v);
const reason = v => typeof v === 'string' && /^[A-Z0-9_]{1,120}$/.test(v);
const integer = v => Number.isSafeInteger(v);
const positive = v => integer(v) && v > 0;
const range = v => keys(v, ['start', 'end', 'coordinateSystem']) && positive(v.start) && integer(v.end) &&
    v.end >= v.start && v.coordinateSystem === 'REFERENCE_PROTEIN_ONE_BASED_INCLUSIVE';
const overlap = (a, b) => a.start <= b.end && a.end >= b.start;
const contextId = v => typeof v === 'string' && /^context-sha256-[a-f0-9]{64}$/.test(v);
const SELECTED = 'SELECTED_V3_AND_EVALUATION_V2';
const DIRECT = 'DIRECT_EVALUATION_CONTEXT';
const COMPLETE = 'COMPLETE_SEQUENCE_COMPARISON_PASSED';
// Exact failures emitted by the producer's rule projection after material was
// already validated; an arbitrary caller reason is not an availability receipt.
const unavailableRuleReasons = new Set(['RULE_RESULT_STRENGTH_MISMATCH', 'STOP_LOSS_RULE_WITNESS_REQUIRED',
    'STOP_LOSS_RULE_SOURCE_REQUIRED', 'PUBLISHED_RULE_SOURCE_BINDING_REQUIRED', 'PUBLISHED_ORIGINAL_SOURCE_PIN_REQUIRED',
    'PUBLISHED_RULE_RESULT_MISMATCH', 'PROTEIN_REGION_MATERIAL_BINDING_REQUIRED', 'PROTEIN_REGION_SOURCE_PIN_REQUIRED',
    'REGION_ANNOTATION_BINDING_MISMATCH', 'REPEAT_EXCLUSION_RESULT_MISMATCH', 'REPEAT_ORIGINAL_FEATURE_BINDING_REQUIRED',
    'REPEAT_PROOF_LIMIT_EXCEEDED', 'REPEAT_ORIGINAL_FEATURE_REQUIRED', 'COMBINED_SOURCE_REQUIRED',
    'COMBINED_REGION_BINDING_MISMATCH', 'OPERATIONAL_NON_REPEAT_WITNESS_REQUIRED', 'REVIEWED_REGION_SOURCE_BINDING_REQUIRED',
    'REVIEWED_REGION_CONDITION_REQUIRED', 'QUALIFIED_COVERAGE_PROOF_LIMIT_OR_MISSING', 'PROTEIN_RANGE_REQUIRED',
    'PUBLIC_IDENTIFIER_REQUIRED', 'PUBLIC_PROOF_UNAVAILABLE']);
const fields = ['candidateId', 'hypothesisId', 'evaluationContextId', 'selectedContextId', 'projectionBindingSha256', 'bindingMode',
    'queryAllele', 'queryAlleleSha256', 'transcript', 'protein', 'modelCanonicalSha256', 'manifestSha256', 'manifestOrigin',
    'modelSourceRecordsSha256', 'referenceSha256', 'referenceIndexSha256', 'storedAnnotationSha256', 'policySha256', 'materialSha256', 'resultSha256'];
const pinFields = fields.filter(k => k.endsWith('Sha256') && k !== 'projectionBindingSha256' && k !== 'referenceIndexSha256');
const changeIntegers = ['netNucleotideChange', 'netAminoAcidChange', 'referenceCdsLength', 'alteredCdsLength',
    'referenceProteinLength', 'alteredProteinLength', 'referenceEditStart0', 'referenceEditEnd0', 'referenceStopCdsPosition',
    'alteredStopCdsPosition', 'availableThreePrimeBases', 'inspectedThreePrimeBases'];
const changeHashes = ['referenceCdsSha256', 'alteredCdsSha256', 'referenceProteinSha256', 'alteredProteinSha256', 'threePrimeWindowSha256'];
const copy = v => JSON.parse(JSON.stringify(v));
const exact = (a, b) => {
    if (a === b) return true;
    if (Array.isArray(a) || Array.isArray(b)) return Array.isArray(a) && Array.isArray(b) &&
        a.length === b.length && a.every((v, i) => exact(v, b[i]));
    return object(a) && object(b) && Object.keys(a).length === Object.keys(b).length &&
        Object.keys(a).every(k => own(b, k) && exact(a[k], b[k]));
};

function canonicalContext(c, version, identityHash) {
    const common = { scope: c.scope, alleleId: c.variant.alleleId, hgncId: c.gene.hgncId, mondoId: c.disease.mondoId,
        moi: c.moi, transcript: { accession: c.transcript.accession, version: c.transcript.version } };
    const value = version === 3 ? { domain: 'varvis-evidence-context-v3', schemaVersion: 'evidence-context-v3',
        dataClass: c.dataClass, ...common, patientContext: c.patientContext }
        : { ...common, patientContextId: c.patientContext.patientContextId, familyContextId: c.patientContext.familyContextId ?? null };
    return `context-sha256-${identityHash(value)}`;
}

function bindingValid(b, f, expected, identityHash, same) {
    const c = expected?.context, mode = expected?.mode;
    if (![SELECTED, DIRECT].includes(mode) || !keys(b, fields) || b.bindingMode !== mode || !id(expected.candidateId) ||
        !id(expected.hypothesisId) || b.candidateId !== expected.candidateId || b.hypothesisId !== expected.hypothesisId ||
        !contextId(b.evaluationContextId) || !c?.variant || !c.gene || !c.disease || !c.patientContext ||
        !id(c.transcript?.accession) || !positive(c.transcript.version) || !id(c.patientContext.patientContextId) ||
        c.schemaVersion !== (mode === SELECTED ? 'evidence-context-v3' : 'evidence-context-v2') ||
        c.contextId !== canonicalContext(c, mode === SELECTED ? 3 : 2, identityHash)) return false;
    const normalizer = c.variant.normalization, allele = c.variant.evidenceQueryAllele;
    if (!normalizer || normalizer.refValidation !== 'MATCH' || !hash(normalizer.referenceSequenceSha256) ||
        c.variant.alleleId !== `allele-sha256-${identityHash({ normalizedAllele: c.variant.normalizedAllele,
            normalizer: { method: normalizer.method, methodVersion: normalizer.methodVersion,
                referenceSequenceSha256: normalizer.referenceSequenceSha256, refValidation: normalizer.refValidation } })}` ||
        !keys(b.queryAllele, ['assembly', 'contig', 'position', 'reference', 'alternate']) ||
        b.queryAllele.assembly !== 'GRCh38' || !id(b.queryAllele.contig) || !positive(b.queryAllele.position) ||
        !/^[ACGT]{1,50}$/.test(b.queryAllele.reference || '') || !/^[ACGT]{1,50}$/.test(b.queryAllele.alternate || '') ||
        b.queryAllele.reference === b.queryAllele.alternate || !same(b.queryAllele, allele) ||
        identityHash(b.queryAllele) !== b.queryAlleleSha256 || b.referenceSha256 !== normalizer.referenceSequenceSha256 ||
        b.transcript !== `${c.transcript.accession}.${c.transcript.version}` || b.transcript !== f.transcript ||
        !id(b.protein) || b.protein !== f.protein || !pinFields.every(k => hash(b[k])) ||
        own(b, 'referenceIndexSha256') && !hash(b.referenceIndexSha256) ||
        !['PREBUILT_MODEL_MANIFEST', 'GENCODE_INDEX_MANIFEST'].includes(b.manifestOrigin)) return false;
    if (mode === SELECTED) return b.selectedContextId === c.contextId && hash(b.projectionBindingSha256) &&
        b.evaluationContextId === canonicalContext(c, 2, identityHash);
    return !own(b, 'selectedContextId') && !own(b, 'projectionBindingSha256') && b.evaluationContextId === c.contextId;
}

function changeValid(v, f, stopLoss, same) {
    if (!keys(v, [...changeIntegers, ...changeHashes, 'newTerminationResolved', 'queryRange', 'editCoordinateSystem', 'netNucleotideChangeMeaning']) ||
        changeIntegers.some(k => own(v, k) && !integer(v[k])) || changeHashes.some(k => own(v, k) && !hash(v[k])) ||
        !changeHashes.slice(0, 4).every(k => hash(v[k])) || !integer(v.netNucleotideChange) || !integer(v.netAminoAcidChange) ||
        v.netAminoAcidChange === 0 || !['referenceCdsLength', 'alteredCdsLength', 'referenceProteinLength', 'alteredProteinLength',
            'referenceStopCdsPosition', 'alteredStopCdsPosition'].every(k => positive(v[k])) ||
        !integer(v.referenceEditStart0) || v.referenceEditStart0 < 0 || !integer(v.referenceEditEnd0) ||
        v.referenceEditEnd0 < v.referenceEditStart0 || v.referenceEditEnd0 > v.referenceCdsLength ||
        v.editCoordinateSystem !== 'REFERENCE_CDS_ZERO_BASED_HALF_OPEN' ||
        v.alteredProteinLength - v.referenceProteinLength !== v.netAminoAcidChange ||
        v.referenceStopCdsPosition !== v.referenceProteinLength * 3 + 1 || v.alteredStopCdsPosition !== v.alteredProteinLength * 3 + 1 ||
        v.referenceCdsLength !== v.referenceStopCdsPosition + 2 || v.alteredCdsLength !== v.alteredStopCdsPosition + 2 ||
        f.netAminoAcidChange !== v.netAminoAcidChange ||
        ['availableThreePrimeBases', 'inspectedThreePrimeBases'].some(k => own(v, k) && v[k] < 0) ||
        own(v, 'newTerminationResolved') && typeof v.newTerminationResolved !== 'boolean' ||
        own(v, 'queryRange') && (!range(v.queryRange) || !same(v.queryRange, f.queryRange))) return false;
    if (!stopLoss) return range(v.queryRange) && v.netNucleotideChange % 3 === 0 &&
        v.alteredCdsLength - v.referenceCdsLength === v.netNucleotideChange && !own(v, 'netNucleotideChangeMeaning');
    return v.netNucleotideChangeMeaning === 'EDIT_DELTA_EXCLUDING_PREDICTED_THREE_PRIME_EXTENSION' &&
        v.newTerminationResolved === true && v.netAminoAcidChange > 0 && positive(v.availableThreePrimeBases) &&
        positive(v.inspectedThreePrimeBases) && v.inspectedThreePrimeBases <= v.availableThreePrimeBases && v.inspectedThreePrimeBases <= 100000 &&
        hash(v.threePrimeWindowSha256) && ['referenceProteinLength', 'alteredProteinLength', 'alteredStopCdsPosition',
            'availableThreePrimeBases', 'inspectedThreePrimeBases', 'newTerminationResolved'].every(k => v[k] === f[k]);
}

function sourcesValid(pins, b) {
    if (!Array.isArray(pins) || pins.length < 3 || pins.length > 18) return false;
    const models = pins.filter(p => p?.kind === 'TRANSCRIPT_MODEL'), refs = pins.filter(p => p?.kind === 'REFERENCE'),
        annotations = pins.filter(p => p?.kind === 'SELECTED_TRANSCRIPT_ANNOTATION');
    return models.length > 0 && models.length <= 16 && refs.length === 1 && annotations.length === 1 &&
        pins.length === models.length + 2 && models.every(p => keys(p, ['kind', 'sourceId', 'version', 'sha256', 'url']) &&
            (!own(p, 'sourceId') || id(p.sourceId)) && (!own(p, 'version') || id(p.version)) && hash(p.sha256) &&
            (!own(p, 'url') || safeSourceUrl(p.url))) &&
        keys(refs[0], ['kind', 'sha256', 'receiptSourceSha256']) && refs[0].sha256 === b.referenceSha256 &&
        (!own(refs[0], 'receiptSourceSha256') || hash(refs[0].receiptSourceSha256)) &&
        keys(annotations[0], ['kind', 'sha256']) && annotations[0].sha256 === b.storedAnnotationSha256;
}

function safeSourceUrl(v) {
    if (typeof v !== 'string' || v.length > 512 || /[\s\\]/.test(v) || !/^https?:\/\//.test(v)) return false;
    try {
        const u = new URL(v);
        return ['http:', 'https:'].includes(u.protocol) && !!u.hostname && !u.username && !u.password && !u.search && !u.hash;
    } catch (_) { return false; }
}

function covers(intervals, query, accept) {
    let next = query.start;
    for (const x of intervals.filter(accept).slice().sort((a, b) => a.start - b.start)) {
        if (x.end < next) continue;
        if (x.start > next) return false;
        next = x.end + 1;
        if (next > query.end) return true;
    }
    return false;
}

function ruleValid(r, row, f, b, same, identityHash) {
    const pm4 = row.criterionId === 'ACMG_AMP_2015:PM4', met = row.status === 'MET';
    if (!object(r) || r.policyId !== (pm4 ? 'PROJECT_LOCAL_PM4_LENGTH_CHANGE' : 'PROJECT_LOCAL_BP3_VERIFIED_INFRAME_SMALL_ALLELE') ||
        r.policyVersion !== f.policyVersion || r.reasonCode !== row.reasonCode ||
        r.status !== row.status || r.policySha256 !== b.policySha256 ||
        row.appliedStrength !== (met ? pm4 ? 'MODERATE' : 'SUPPORTING' : 'NONE')) return false;
    const common = ['policyId', 'policyVersion', 'reasonCode', 'status', 'policySha256', 'branch'];
    if (r.branch === 'SEQUENCE_DERIVED_STOP_LOSS') return keys(r, [...common, 'basis', 'source', 'regionAnnotationRequired']) && pm4 && met &&
        r.policyId === 'PROJECT_LOCAL_PM4_LENGTH_CHANGE' && row.reasonCode === 'PM4_PROJECT_LOCAL_SEQUENCE_DERIVED_STOP_LOSS_MET' &&
        f.qualificationBasis === 'SEQUENCE_DERIVED_STOP_LOSS' && r.basis === 'REFERENCE_BASED_PREDICTED_EXTENSION_NOT_OBSERVED_RNA_OR_PROTEIN' &&
        r.source === 'https://doi.org/10.1038/gim.2015.30' && r.regionAnnotationRequired === false;
    if (r.branch === 'PUBLISHED_EXPERT_SPECIFICATION') {
        const conditions = r.conditions, selected = f.selectedRule;
        if (pm4 || !keys(r, [...common, 'sourcePin', 'specificationKey', 'clauseSha256', 'selectedRuleSha256', 'specificationSha256',
            'specificationRawSha256', 'regionState', 'conditions']) || !hash(r.sourcePin) || !hash(r.specificationSha256) ||
            !hash(r.specificationRawSha256) || r.specificationKey !== f.specificationKey || !/^GN[0-9]+@[0-9]+(?:\.[0-9]+){1,2}$/.test(r.specificationKey || '') ||
            !hash(r.clauseSha256) || r.clauseSha256 !== f.clauseSha256 || r.selectedRuleSha256 !== identityHash(selected) ||
            f.qualificationBasis !== 'PUBLISHED_EXPERT_SPECIFICATION' || r.regionState !== f.regionState || !range(f.queryRange) ||
            !keys(conditions, ['start', 'end', 'modelRegionStart', 'modelRegionEnd', 'qualificationBasis']) ||
            !['start', 'end', 'modelRegionStart', 'modelRegionEnd'].every(k => positive(conditions[k]) && conditions[k] === selected?.[k]) ||
            conditions.start > conditions.end || conditions.modelRegionStart > conditions.start || conditions.modelRegionEnd < conditions.end ||
            conditions.qualificationBasis !== 'PUBLISHED_EXPERT_SPECIFICATION') return false;
        const covered = f.queryRange.start >= conditions.start && f.queryRange.end <= conditions.end;
        return met ? covered && row.reasonCode === 'BP3_PUBLISHED_REPEAT_REGION_MATCHED' && f.regionState === 'QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION'
            : !covered && row.reasonCode === 'BP3_OUTSIDE_PUBLISHED_ELIGIBLE_REGION' && f.regionState === 'EXCLUDED';
    }
    const regionKeys = ['queryRange', 'annotationSnapshotSha256', 'uniProtRawSha256'];
    if (!range(r.queryRange) || !same(r.queryRange, f.queryRange) || !hash(r.annotationSnapshotSha256) || !hash(r.uniProtRawSha256)) return false;
    if (r.branch === 'OBSERVED_REPEAT_OVERLAP_EXCLUDES_PM4') return keys(r, [...common, ...regionKeys, 'repeatObservations']) &&
        pm4 && !met && row.reasonCode === 'PM4_PROJECT_LOCAL_REPEAT_OVERLAP_NOT_MET' && f.regionState === 'REPEAT_OVERLAP' &&
        f.repeatObservation === 'KNOWN_REPEAT' && Array.isArray(r.repeatObservations) && r.repeatObservations.length > 0 && r.repeatObservations.length <= 16 &&
        r.repeatObservations.every(x => keys(x, ['start', 'end', 'coordinateSystem', 'sourceLocator', 'featureJsonSha256']) &&
            range({ start: x.start, end: x.end, coordinateSystem: x.coordinateSystem }) && overlap(x, r.queryRange) &&
            /^\/features\/[0-9]+$/.test(x.sourceLocator || '') && hash(x.featureJsonSha256));
    if (!hash(r.combinedRawSha256)) return false;
    if (r.branch === 'OPERATIONAL_NON_REPEAT') return keys(r, [...common, ...regionKeys, 'combinedRawSha256', 'basis']) && pm4 && met &&
        row.reasonCode === 'PM4_PROJECT_LOCAL_OPERATIONAL_NON_REPEAT_MET' && f.regionState === 'OPERATIONAL_NON_REPEAT_SUPPORTED' &&
        f.qualificationBasis === 'OPERATIONAL_INFERENCE' && f.nonRepeatPolicy === 'ANNOTATION_SEQUENCE_V1' && f.shortRepeatCandidate === false &&
        f.repeatObservation === 'NO_REPEAT_OBSERVED' && r.basis === 'OPERATIONAL_INFERENCE_NOT_ABSOLUTE_ABSENCE';
    if (!['REVIEWED_NON_REPEAT', 'REVIEWED_REPEAT_AND_FUNCTION'].includes(r.branch)) return false;
    if (!keys(r, [...common, ...regionKeys, 'combinedRawSha256', 'acceptedRawSha256', 'qualificationSha256', 'regionState', 'recordLocator', 'coverageIntervals']) ||
        !hash(r.acceptedRawSha256) || !hash(r.qualificationSha256) || r.regionState !== f.regionState ||
        own(r, 'recordLocator') && !/^\/records\/[0-9]+$/.test(r.recordLocator || '') ||
        !Array.isArray(r.coverageIntervals) || !r.coverageIntervals.length || r.coverageIntervals.length > 16) return false;
    if (pm4 ? r.branch !== 'REVIEWED_NON_REPEAT' || !met || f.regionState !== 'NON_REPEAT_SUPPORTED' ||
        row.reasonCode !== 'PM4_PROJECT_LOCAL_QUALIFIED_NON_REPEAT_MET'
        : r.branch !== 'REVIEWED_REPEAT_AND_FUNCTION' || (met ? f.regionState !== 'QUALIFIED_REPEAT_WITHOUT_KNOWN_FUNCTION' ||
            row.reasonCode !== 'BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION' : f.regionState !== 'EXCLUDED' || row.reasonCode !== 'BP3_REVIEWED_NON_REPEAT_OR_KNOWN_FUNCTION')) return false;
    const sorted = r.coverageIntervals.slice().sort((a, b) => a.start - b.start);
    // Both source qualification readers reject overlapping assertion intervals.
    // A favorable interval cannot mask a contradictory second assertion.
    if (sorted.some((x, i) => i > 0 && x.start <= sorted[i - 1].end)) return false;
    return r.coverageIntervals.every(x => keys(x, ['start', 'end', 'coordinateSystem', 'repeatStatus', 'functionalStatus', 'sourceLocatorsSha256']) &&
        range({ start: x.start, end: x.end, coordinateSystem: x.coordinateSystem }) && hash(x.sourceLocatorsSha256) &&
        ['REPEAT', 'NON_REPEAT', 'UNKNOWN'].includes(x.repeatStatus) &&
        (pm4 && !own(x, 'functionalStatus') || ['NO_KNOWN_FUNCTION', 'KNOWN_FUNCTION', 'UNKNOWN'].includes(x.functionalStatus))) &&
        (pm4 ? !r.coverageIntervals.some(x => x.repeatStatus === 'REPEAT' && overlap(x, r.queryRange)) &&
            covers(r.coverageIntervals, r.queryRange, x => x.repeatStatus === 'NON_REPEAT') : met
            ? !r.coverageIntervals.some(x => overlap(x, r.queryRange) && (x.repeatStatus === 'NON_REPEAT' || x.functionalStatus === 'KNOWN_FUNCTION')) &&
                r.coverageIntervals.some(x => x.start <= r.queryRange.start && x.end >= r.queryRange.end &&
                    x.repeatStatus === 'REPEAT' && x.functionalStatus === 'NO_KNOWN_FUNCTION')
            : r.coverageIntervals.some(x => overlap(x, r.queryRange) && (x.repeatStatus === 'NON_REPEAT' || x.functionalStatus === 'KNOWN_FUNCTION')));
}

export function inspectProteinLengthProof(criterion, expected, { identityHash } = {}) {
    const f = criterion?.details?.proteinLengthFacts || criterion?.details?.bp3Facts;
    const claimed = object(f) && ['sequenceMaterial', 'annotationCorrespondence', 'adoptionProof'].some(k => own(f, k));
    const issues = [], fail = code => { issues.push(code); return { claimed, valid: false, issues, view: null }; };
    if (!claimed) return { claimed: false, valid: true, issues, view: null };
    if (!['ACMG_AMP_2015:PM4', 'ACMG_AMP_2015:BP3'].includes(criterion?.criterionId) ||
        criterion.criterionId === 'ACMG_AMP_2015:PM4' && f !== criterion.details.proteinLengthFacts ||
        criterion.criterionId === 'ACMG_AMP_2015:BP3' && f !== criterion.details.bp3Facts ||
        typeof identityHash !== 'function' || ![SELECTED, DIRECT].includes(expected?.mode)) return fail('PROTEIN_LENGTH_EXPLICIT_CONSUMER_REQUIRED');
    const m = f.sequenceMaterial, p = f.adoptionProof;
    let availabilityOnly = false;
    // Producer JSON-node equality is exact, independent of canonical hash NFC.
    const same = exact;
    try {
        if (f.schemaVersion !== (criterion.criterionId === 'ACMG_AMP_2015:PM4' ? 'pm4-general-facts-v1' : 'bp3-general-facts-v1') ||
            f.policyVersion !== (criterion.criterionId === 'ACMG_AMP_2015:PM4' ? '1.2.0' : '1.1.0') ||
            f.clinicalValidationCompleted !== false || !['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(criterion.status) ||
            !reason(criterion.reasonCode)) return fail('PROTEIN_LENGTH_PUBLIC_FACTS_SCHEMA_MISMATCH');
        if (!object(m) || !object(p) || m.schemaVersion !== 'protein-length-current-sequence-v1' ||
            p.schemaVersion !== 'protein-length-adoption-proof-v1' || !reason(m.reasonCode) || !reason(p.reasonCode) ||
            !['clinicalValidationCompleted', 'createsScientificApproval', 'rescoringPerformed'].every(k => p[k] === false))
            return fail('PROTEIN_LENGTH_PUBLIC_PROOF_SHAPE_MISMATCH');
        if (m.status === 'UNAVAILABLE') {
            if (!keys(m, ['schemaVersion', 'status', 'reasonCode']) || f.annotationCorrespondence !== 'UNAVAILABLE' ||
                !keys(p, ['schemaVersion', 'status', 'reasonCode', 'clinicalValidationCompleted', 'createsScientificApproval', 'rescoringPerformed']) ||
                p.status !== 'UNAVAILABLE' || p.reasonCode !== m.reasonCode || criterion.status !== 'NOT_EVALUATED' || criterion.appliedStrength !== 'NONE')
                return fail('PROTEIN_LENGTH_UNAVAILABLE_RESULT_MISMATCH');
        } else {
            const stopLoss = m.associationKind === 'VERIFIED_STOP_LOSS_SMALL_ALLELE';
            if (!keys(m, ['schemaVersion', 'status', 'reasonCode', 'associationKind', 'binding', 'change', 'sourcePins']) || m.status !== 'READY' ||
                f.annotationCorrespondence !== COMPLETE || !['VERIFIED_INFRAME_LENGTH_CHANGE', 'VERIFIED_STOP_LOSS_SMALL_ALLELE'].includes(m.associationKind) ||
                m.reasonCode !== (stopLoss ? 'STOP_LOSS_MODEL_REFERENCE_AND_VEP_AGREE' : 'LENGTH_CHANGE_MODEL_REFERENCE_AND_VEP_AGREE') ||
                stopLoss && criterion.criterionId !== 'ACMG_AMP_2015:PM4' || f.scope !== (stopLoss
                    ? 'PROJECT_LOCAL_VERIFIED_STOP_LOSS_SMALL_ALLELE' : 'PROJECT_LOCAL_VERIFIED_INFRAME_SMALL_ALLELE') ||
                !bindingValid(m.binding, f, expected, identityHash, same)) return fail('PROTEIN_LENGTH_CURRENT_BINDING_MISMATCH');
            if (!changeValid(m.change, f, stopLoss, same) || m.change.netNucleotideChange !==
                m.binding.queryAllele.alternate.length - m.binding.queryAllele.reference.length ||
                !sourcesValid(m.sourcePins, m.binding)) return fail('PROTEIN_LENGTH_CURRENT_FACTS_MISMATCH');
            if (!keys(p, ['schemaVersion', 'status', 'reasonCode', 'criterionId', 'clinicalValidationCompleted', 'createsScientificApproval',
                'rescoringPerformed', 'dataClass', 'binding', 'result', 'change', 'sourcePins', 'rule', 'unmetCondition']) ||
                p.criterionId !== criterion.criterionId || p.dataClass !== expected.context.dataClass || !same(p.binding, m.binding) ||
                !same(p.change, m.change) || !same(p.sourcePins, m.sourcePins) ||
                !same(p.result, { status: criterion.status, reasonCode: criterion.reasonCode, appliedStrength: criterion.appliedStrength }))
                return fail('PROTEIN_LENGTH_ADOPTION_BINDING_MISMATCH');
            if (p.status === 'AVAILABLE') {
                if (p.reasonCode !== 'CURRENT_MATERIAL_AND_RULE_RESULT_BOUND' || !['MET', 'NOT_MET'].includes(criterion.status) || own(p, 'unmetCondition') ||
                    !ruleValid(p.rule, criterion, f, m.binding, same, identityHash)) return fail('PROTEIN_LENGTH_RULE_PROOF_MISMATCH');
            } else if (p.status === 'UNAVAILABLE' && ['MET', 'NOT_MET'].includes(criterion.status)) {
                if (!unavailableRuleReasons.has(p.reasonCode) || own(p, 'rule') || own(p, 'unmetCondition') ||
                    criterion.appliedStrength !== (criterion.status === 'NOT_MET' ? 'NONE' : criterion.criterionId === 'ACMG_AMP_2015:PM4' ? 'MODERATE' : 'SUPPORTING'))
                    return fail('PROTEIN_LENGTH_UNAVAILABLE_RESULT_MISMATCH');
                availabilityOnly = true;
                issues.push('PROTEIN_LENGTH_ADOPTION_PUBLIC_PROOF_UNAVAILABLE');
            } else if (p.status !== 'UNAVAILABLE' || p.reasonCode !== 'FINAL_RESULT_NOT_ADOPTED' || criterion.status !== 'NOT_EVALUATED' ||
                criterion.appliedStrength !== 'NONE' || p.unmetCondition !== criterion.reasonCode || own(p, 'rule')) return fail('PROTEIN_LENGTH_UNAVAILABLE_RESULT_MISMATCH');
        }
        return { claimed, valid: !availabilityOnly, issues, view: copy({ criterionId: criterion.criterionId, availabilityOnly,
            result: { status: criterion.status, appliedStrength: criterion.appliedStrength, reasonCode: criterion.reasonCode },
            expectedBindingMode: expected.mode, selectedContextId: expected.mode === SELECTED ? expected.context?.contextId : null,
            evaluationContextId: m.binding?.evaluationContextId ?? null, materialStatus: m.status, materialReasonCode: m.reasonCode,
            annotationCorrespondence: f.annotationCorrespondence, proofStatus: p.status, proofReasonCode: p.reasonCode,
            binding: m.binding ?? null, change: m.change ?? null, sourcePins: m.sourcePins ?? [], rule: p.rule ?? null,
            unmetCondition: p.unmetCondition ?? null, clinicalValidationCompleted: false, createsScientificApproval: false,
            sourceAuthentication: 'SERVER_ACTUAL_MATERIAL_REPROJECTION_REQUIRED',
            initialRouting: criterion.details?.applicabilityRouting ? { status: criterion.details.applicabilityRouting.status,
                reasonCode: criterion.details.applicabilityRouting.reasonCode,
                skipSpecializedAcquisition: criterion.details.applicabilityRouting.skipSpecializedAcquisition } : null }) };
    } catch (_) { return fail('PROTEIN_LENGTH_PUBLIC_PROOF_CANONICAL_MISMATCH'); }
}

export function proteinLengthProofDetails(inspected) {
    const v = (inspected?.valid || inspected?.view?.availabilityOnly) && inspected.view;
    if (!v) return [];
    return [['当前序列材料', `${v.materialStatus}；${v.materialReasonCode}`],
        ['当前完整比较', v.annotationCorrespondence],
        ['原判定与采用回执', `${v.result.status} / ${v.result.appliedStrength}；${v.proofStatus}；${v.proofReasonCode}`],
        ...(v.rule ? [['本次规则分支', v.rule.branch], ['规则条件与来源定位', JSON.stringify(v.rule)]] : []),
        ['当前材料与规则的边界', '序列就绪或比较通过不等于证据成立；采用回执不产生额外科学资格或临床采用。初始取证路由是历史记录，不能覆盖当前完整比较。'],
        ['公开证明校验边界', '本页核对公开字段及背景绑定；摘要哈希不是来源认证，完整材料及原件须由服务端实际保存模块重新投影核查。']];
}
