// Reader for the committed DeNovoResultSummary 1.2 public contract. It checks
// saved arithmetic and method identity; it does not qualify original sources.
const IDS = ['ACMG_AMP_2015:PS2', 'ACMG_AMP_2015:PM6'];
const PUBLISHED_POINTS = 'SVI_1_1_PUBLISHED_POINTS';
const STRENGTHS = ['SUPPORTING', 'MODERATE', 'STRONG', 'VERY_STRONG'];
const obj = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const text = value => typeof value === 'string' && value.length > 0 && value.length <= 32768;
const hash = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value);
const array = (value, max = 1000) => Array.isArray(value) && value.length <= max;
const code = value => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,160}$/.test(value);
const codes = value => array(value) && value.every(code);
const integer = value => Number.isSafeInteger(value) && value >= 0 && value <= 8000;
const canonical = value => Array.isArray(value) ? `[${value.map(canonical).join(',')}]` : obj(value)
    ? `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}` : JSON.stringify(value);
const same = (a, b) => canonical(a) === canonical(b);
const requireValue = (condition, issue) => { if (!condition) throw Error(issue); };
const semver = (value, prefix, minimumMinor) => {
    if (typeof value !== 'string' || !value.startsWith(prefix)) return false;
    const match = /^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.exec(value.slice(prefix.length));
    return !!match && match[1] === '1' && Number(match[2]) >= minimumMinor;
};
const fromPoints = n => n >= 16 ? 'VERY_STRONG' : n >= 8 ? 'STRONG' : n >= 4 ? 'MODERATE' : n >= 2 ? 'SUPPORTING' : 'NONE';
const reduced = strength => ({ VERY_STRONG: 'STRONG', STRONG: 'MODERATE', MODERATE: 'SUPPORTING', SUPPORTING: 'NONE', NONE: 'NONE' })[strength];
const eventKind = value => /^DENOVO_EVENT_KIND_(CONSTITUTIONAL|MATERNAL_ORIGIN|SIBLING_RECURRENCE|POSTZYGOTIC)$/.test(value);
const phenotypePoints = { HIGHLY_SPECIFIC: 8, RASOPATHY_COMPATIBLE: 8, CONSISTENT: 4, CONSISTENT_HIGH_HETEROGENEITY: 2, NOT_CONSISTENT: 0 };
const sourceRef = value => typeof value === 'string' && /^[0-9a-f]{64}#(?:\/[A-Za-z0-9_~/-]*)?$/.test(value);
const exactKeys = (value, required, optional = []) => obj(value) && required.every(key => Object.hasOwn(value, key)) &&
    Object.keys(value).every(key => required.includes(key) || optional.includes(key));
const pointer = (value, root = false) => typeof value === 'string' && value.length <= 4096 && (root && value === '' ||
    value.startsWith('/') && !/~(?:[^01]|$)/.test(value));

export function deNovoFactComparisonsValid(comparisons, event) {
    return array(comparisons) && comparisons.every(item => exactKeys(item, ['reasonCode', 'memberRole', 'ordinaryResult', 'specialEventResult']) &&
        item.reasonCode === 'DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT' && ['SUBJECT', 'MOTHER', 'FATHER'].includes(item.memberRole) &&
        ['PRESENT', 'ABSENT'].includes(item.ordinaryResult) && ['PRESENT', 'ABSENT'].includes(item.specialEventResult) &&
        item.ordinaryResult !== item.specialEventResult) && (!event || !comparisons.length || event.disposition === 'EXCLUDED' &&
            codes(event.issues) && event.issues.includes('DENOVO_SPECIAL_TARGET_SOURCE_CONFLICT'));
}

/** Additive source-locator contract; call in addition to validCoreMaterial. */
export function deNovoSourceDiscoveryValid(discovery) {
    if (!exactKeys(discovery, ['version', 'checkedSourceSha256s', 'criterionDecision', 'issues', 'records']) ||
        discovery.version !== 'denovo-source-event-discovery-v1' || discovery.criterionDecision !== 'NOT_RUN' ||
        !array(discovery.checkedSourceSha256s, 33) || !discovery.checkedSourceSha256s.length || !discovery.checkedSourceSha256s.every(hash) ||
        !array(discovery.issues) || !array(discovery.records, 4224) || discovery.issues.some(issue =>
            !exactKeys(issue, ['code'], ['sourceSha256']) || !code(issue.code) || Object.hasOwn(issue, 'sourceSha256') && !hash(issue.sourceSha256))) return false;
    const checked = new Set(discovery.checkedSourceSha256s);
    const reference = (ref, pin) => exactKeys(ref, ['sourceSha256', 'locator', 'kind', 'quote']) && ref.sourceSha256 === pin &&
        checked.has(ref.sourceSha256) && ['JSON_POINTER', 'JATS_UNIT'].includes(ref.kind) && pointer(ref.locator, ref.kind === 'JSON_POINTER') &&
        typeof ref.quote === 'string' && ref.quote.length <= 32768;
    for (const record of discovery.records) {
        if (!exactKeys(record, ['sourceSha256', 'recordLocator', 'isEventCandidate', 'missingPaths', 'issues', 'caseReference', 'fields']) ||
            !hash(record.sourceSha256) || !checked.has(record.sourceSha256) || !pointer(record.recordLocator, true) || typeof record.isEventCandidate !== 'boolean' ||
            !array(record.missingPaths) || !record.missingPaths.every(path => pointer(path)) || !codes(record.issues) || !array(record.fields, 4096) ||
            !obj(record.caseReference) || Object.keys(record.caseReference).length > 0 && !reference(record.caseReference, record.sourceSha256)) return false;
        for (const field of record.fields) {
            if (!exactKeys(field, ['path', 'sourceSha256', 'locator', 'kind', 'quote']) || !pointer(field.path) ||
                !reference(Object.fromEntries(Object.entries(field).filter(([key]) => key !== 'path')), record.sourceSha256) ||
                record.missingPaths.some(missing => field.path === missing || field.path.startsWith(`${missing}/`))) return false;
        }
    }
    return true;
}

export const isDeNovoMethodsSummary = value => obj(value) && (Object.hasOwn(value, 'calculationMethod') ||
    Object.hasOwn(value, 'strengthAdjustment') || Object.hasOwn(value, 'detailVersion') && !['1.0.0', '1.1.0'].includes(value.detailVersion));

export const isDeNovoMethodsBinding = binding => obj(binding) && (
    Object.hasOwn(binding, 'publicExplanationVersion') && (semver(binding.publicExplanationVersion, '', 2) || !semver(binding.publicExplanationVersion, '', 0)) ||
    Object.hasOwn(binding, 'sexChromosomeRegion') || semver(binding.version, 'denovo-selected-batch-', 5) ||
    semver(binding.calculatorVersion, 'denovo-event-calculator-', 2) || semver(binding.qualifierVersion, 'denovo-local-evidence-', 4) ||
    typeof binding.version === 'string' && binding.version.startsWith('denovo-selected-batch-') && !semver(binding.version, 'denovo-selected-batch-', 0) ||
    typeof binding.calculatorVersion === 'string' && binding.calculatorVersion.startsWith('denovo-event-calculator-') && !semver(binding.calculatorVersion, 'denovo-event-calculator-', 0));

function validateBindingIdentity(binding) {
    requireValue(obj(binding) && semver(binding.version, 'denovo-selected-batch-', 5) && hash(binding.manifestSha256) &&
        semver(binding.calculatorVersion, 'denovo-event-calculator-', 2) &&
        semver(binding.qualifierVersion, 'denovo-local-evidence-', 4) &&
        semver(binding.phenotypeVersion, 'denovo-phenotype-matcher-', 1) &&
        semver(binding.readerVersion, 'denovo-local-report-reader-', 3) && semver(binding.publicExplanationVersion, '', 2) &&
        binding.modelCalls === 0 && ['RESEARCH', 'SYNTHETIC_VALIDATION'].includes(binding.use) && code(binding.literatureQualification), 'DENOVO_PUBLIC_METHOD_BINDING_INVALID');
    const replay = binding.modelArtifactReplay;
    if (replay) requireValue(replay.version === 'denovo-literature-model-replay-1.0.0' && hash(replay.artifactIndexSha256) &&
        replay.automaticModelCalls === false && replay.sourceScope === 'PUBLIC_PAPER_ONLY' &&
        ['DEFERRED_NOT_CONFIGURED', 'MODEL_CANDIDATES_SOURCE_REPLAYED_NOT_CLINICALLY_VALIDATED', 'SYNTHETIC_MODEL_REPLAY_ONLY'].includes(binding.modelValidation) &&
        (binding.modelValidation !== 'SYNTHETIC_MODEL_REPLAY_ONLY' || binding.use === 'SYNTHETIC_VALIDATION'), 'DENOVO_MODEL_SOURCE_BINDING_INVALID');
    else requireValue(binding.modelValidation === 'DEFERRED_NOT_CONFIGURED', 'DENOVO_MODEL_SOURCE_BINDING_INVALID');
    const supply = binding.sourceSupply;
    requireValue((binding.literatureQualification === 'SELECTED_SOURCE_SUPPLY_CONFIGURED') === Object.hasOwn(binding, 'sourceSupply'), 'DENOVO_SOURCE_SUPPLY_BINDING_INVALID');
    if (supply) {
        requireValue(obj(supply) && supply.version === 'denovo-selected-literature-supply-1.0.0' && typeof supply.publicNetworkEnabled === 'boolean' &&
            supply.scope === 'EXACT_PINNED_CLINVAR_CITATIONS_ONLY' && supply.modelCalls === 0 && supply.completeLiteratureSearch === false &&
            obj(supply.implementation) && ['supply_selected_literature.py', 'fetch_public_literature.py', 'register_materials.py', 'prepare_literature.py', 'literature_allele_identity.py']
                .every(name => hash(supply.implementation[name])), 'DENOVO_SOURCE_SUPPLY_BINDING_INVALID');
        if (['executionMode', 'taskVersion'].some(key => Object.hasOwn(supply, key))) requireValue(supply.executionMode === 'ASYNCHRONOUS' &&
            supply.taskVersion === 'denovo-source-task-v1', 'DENOVO_SOURCE_TASK_BINDING_INVALID');
        if (['retryPolicy', 'maximumAttempts'].some(key => Object.hasOwn(supply, key))) requireValue(supply.executionMode === 'ASYNCHRONOUS' &&
            supply.taskVersion === 'denovo-source-task-v1' && supply.retryPolicy === 'denovo-source-explicit-retry-v1' && supply.maximumAttempts === 3, 'DENOVO_SOURCE_RETRY_BINDING_INVALID');
    } else requireValue(!Object.hasOwn(binding, 'sourceSupply'), 'DENOVO_SOURCE_SUPPLY_BINDING_INVALID');
    const region = binding.sexChromosomeRegion;
    requireValue(obj(region) && region.schemaVersion === 'sex-chromosome-region-v1' && semver(region.methodVersion, '', 0) &&
        hash(region.sourceCatalogSha256) && region.consumer === 'DeNovoA10RegionSupply' && region.clinicalQualificationGranted === false &&
        region.patientPloidyInferred === false, 'DENOVO_REGION_SUPPLY_BINDING_INVALID');
    const literature = ['literatureVersion', 'literatureReaderVersion', 'literatureSourceResultSha256'].some(key => Object.hasOwn(binding, key));
    if (literature || binding.literatureQualification === 'SOURCE_REPLAYED_FOR_PER_TARGET_QUALIFICATION') requireValue(
        semver(binding.literatureVersion, 'denovo-literature-evidence-', 0) && binding.literatureReaderVersion ===
            (replay ? 'denovo-literature-model-replay-1.0.0' : 'denovo-literature-batch-reader-1.0.0') &&
        (!Object.hasOwn(binding, 'literatureSourceResultSha256') || hash(binding.literatureSourceResultSha256)) &&
        (binding.literatureQualification !== 'SOURCE_REPLAYED_FOR_PER_TARGET_QUALIFICATION' || hash(binding.literatureSourceResultSha256)), 'DENOVO_LITERATURE_SOURCE_BINDING_INVALID');
}

export function deNovoMethodsBindingValid(binding) {
    try { validateBindingIdentity(binding); return true; } catch { return false; }
}

function validateBinding(facts, profile) {
    const binding = profile?.moduleBindings?.deNovo;
    validateBindingIdentity(binding);
    requireValue(binding.publicExplanationVersion === facts.detailVersion && facts.materialUse === binding.use &&
        facts.literatureQualification === binding.literatureQualification && facts.modelValidation === binding.modelValidation, 'DENOVO_PUBLIC_METHOD_BINDING_INVALID');
    if (Object.hasOwn(facts, 'sourceSupplyIssues')) requireValue(obj(binding.sourceSupply) && codes(facts.sourceSupplyIssues) &&
        facts.sourceSupplyIssues.length <= 100, 'DENOVO_SOURCE_SUPPLY_ISSUES_INVALID');
}

function validateExplanations(facts, pair, expected) {
    const modern = Object.hasOwn(facts, 'adoptionProof');
    requireValue(array(facts.criterionExplanations, 2) && (modern ? facts.criterionExplanations.length === expected.length : facts.criterionExplanations.length === 2), 'DENOVO_PUBLIC_EXPLANATIONS_REQUIRED');
    const ids = modern ? expected : IDS;
    for (let i = 0; i < ids.length; i += 1) {
        const explanation = facts.criterionExplanations[i], row = pair.find(r => r.criterionId === ids[i]);
        requireValue(obj(explanation) && explanation.schemaVersion === 'literature-decision-explanation-v1' &&
            explanation.basis === 'SAVED_RULE_RESULT_NOT_MODEL_OPINION' && text(explanation.summary), 'DENOVO_PUBLIC_EXPLANATION_INVALID');
        if (!expected.includes(ids[i])) requireValue(['criterionId', 'status', 'reasonCode', 'appliedStrength'].every(key => explanation[key] === ''), 'DENOVO_UNSELECTED_EXPLANATION_FORGED');
        else requireValue(row && ['criterionId', 'status', 'reasonCode', 'appliedStrength'].every(key => explanation[key] === row[key]), 'DENOVO_EXPLANATION_ROW_MISMATCH');
    }
}

function validateEvents(facts, fixed) {
    let raw = 0, heterogeneous = 0, confirmed = false, counted = 0;
    for (const [index, event] of facts.events.entries()) {
        requireValue(obj(event) && event.ordinal === index + 1 && ['CURRENT_CASE', 'LITERATURE_CASE'].includes(event.sourceKind) &&
            ['COUNTED', 'ZERO_SUPPORT', 'DUPLICATE', 'EXCLUDED'].includes(event.disposition) && code(event.reasonCode) && codes(event.issues) &&
            integer(event.rawQuarterPoints) && typeof event.confirmedParentage === 'boolean' && array(event.sourceRefs) && event.sourceRefs.every(sourceRef) &&
            array(event.phenotypeCategories, 6) && new Set(event.phenotypeCategories).size === event.phenotypeCategories.length &&
            event.phenotypeCategories.every(p => Object.hasOwn(phenotypePoints, p) || p === 'UNKNOWN') &&
            (event.disposition === 'EXCLUDED' || event.sourceRefs.length > 0), 'DENOVO_PUBLIC_EVENT_INVALID');
        if (Object.hasOwn(event, 'factComparisons')) requireValue(deNovoFactComparisonsValid(event.factComparisons, event), 'DENOVO_FACT_COMPARISON_CONTRADICTION');
        if (event.disposition !== 'EXCLUDED') requireValue(event.issues.every(eventKind), 'DENOVO_EVENT_PREREQUISITE_CONFLICT');
        if (fixed) {
            requireValue(event.rawQuarterPoints === 0 && (event.disposition !== 'COUNTED' || event.reasonCode === 'DENOVO_EXPERT_TISSUE_STRENGTH') &&
                (event.disposition !== 'ZERO_SUPPORT' || event.reasonCode === 'DENOVO_EXPERT_TISSUE_CONDITIONS_NOT_MET'), 'DENOVO_FIXED_POINT_SUM_CONFLICT');
        } else if (event.disposition === 'COUNTED') {
            const categories = event.phenotypeCategories.filter(p => p !== 'UNKNOWN');
            requireValue(event.reasonCode === 'DENOVO_EVENT_INCLUDED' && categories.length === 1 && phenotypePoints[categories[0]] > 0 &&
                event.rawQuarterPoints === phenotypePoints[categories[0]] / (event.confirmedParentage ? 1 : 2), 'DENOVO_EVENT_POINT_PHENOTYPE_MISMATCH');
            raw += event.rawQuarterPoints; counted += 1;
            if (categories[0] === 'CONSISTENT_HIGH_HETEROGENEITY') heterogeneous += event.rawQuarterPoints;
            confirmed ||= event.confirmedParentage;
        } else requireValue(event.rawQuarterPoints === 0, 'DENOVO_NONCOUNTED_POINTS');
        if (event.disposition === 'DUPLICATE') requireValue(event.reasonCode === 'DENOVO_SAME_OCCURRENCE', 'DENOVO_DUPLICATE_REASON_MISMATCH');
    }
    requireValue(raw === facts.rawQuarterPoints && heterogeneous === facts.highHeterogeneityRawQuarterPoints &&
        facts.highHeterogeneityEffectiveQuarterPoints === Math.min(heterogeneous, 4) &&
        facts.effectiveQuarterPoints === raw - heterogeneous + Math.min(heterogeneous, 4) &&
        facts.hasExcludedObservations === facts.events.some(event => event.disposition === 'EXCLUDED'), 'DENOVO_POINT_COMPOSITION_MISMATCH');
    return { confirmed, counted };
}

// Public adopted-method witness. Original source replay and scientific policy
// qualification remain backend gates; opaque original hashes retain that scope.
const proofLabel = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 240 && [...value].every(character=>character.charCodeAt(0)>=32);
const assayMethods = new Set(['SANGER','TARGETED_SEQUENCING','EXOME','GENOME']);
const kinshipMethods = new Set(['SNP_PANEL','STR_PANEL','SEQUENCING_KINSHIP']);
function sourcesValid(sources,event,prefixes = []) {
    if (!array(sources,128) || !sources.length) return false;
    const refs = event?.sourceRefs || [];
    const retained = sources.filter(source => source?.mapping === 'RETAINED_FACT');
    const original = sources.filter(source => source?.mapping === 'REPLAYED_ORIGINAL_FIELD');
    if (new Set(sources.map(canonical)).size !== sources.length) return false;
    if (!sources.every(source => obj(source) && hash(source.sourceSha256) && proofLabel(source.locator) &&
        (source.kind === 'JSON_POINTER' ? pointer(source.locator,true) : source.kind === 'JATS_UNIT' && source.locator.startsWith('/')) &&
        (source.mapping === 'RETAINED_FACT' ? ['LOCAL_REPORT','DERIVED_FACTS','PAPER_ATTACHMENT'].includes(source.role) && source.kind === 'JSON_POINTER' &&
            pointer(source.fieldPath) && refs.some(ref => typeof ref === 'string' && ref.startsWith(source.sourceSha256+'#') &&
                source.locator.startsWith(ref.slice(65))) : source.mapping === 'REPLAYED_ORIGINAL_FIELD' ?
            ['PAPER_PRIMARY','PAPER_ATTACHMENT'].includes(source.role) && pointer(source.fieldPath) :
            source.mapping === undefined && source.role === 'QUALIFIED_OBSERVATION' && source.kind === 'JSON_POINTER' &&
                refs.includes(source.sourceSha256+'#'+source.locator)))) return false;
    if (retained.length) {
        if (prefixes.some(prefix => !retained.some(source => source.fieldPath === prefix || source.fieldPath.startsWith(prefix)))) return false;
        if (retained.some(source => source.role !== 'LOCAL_REPORT') && (!original.length || prefixes.some(prefix =>
            !original.some(source => source.fieldPath === prefix || source.fieldPath.startsWith(prefix))))) return false;
    } else if (!sources.every(source => source.role === 'QUALIFIED_OBSERVATION')) return false;
    return true;
}
function decimalRatio(value) {
    requireValue(typeof value === 'number' && Number.isFinite(value) && value > 0 && value <= 1, 'DENOVO_ADOPTION_MEASUREMENT_INVALID');
    const m=/^(\d+)(?:\.(\d+))?(?:e([+-]?\d+))?$/.exec(String(value));
    requireValue(m, 'DENOVO_ADOPTION_MEASUREMENT_INVALID');
    const scale=(m[2]?.length || 0)-Number(m[3] || 0), digits=BigInt(m[1]+(m[2] || ''));
    return scale>=0 ? [digits,10n**BigInt(scale)] : [digits*10n**BigInt(-scale),1n];
}
function measurementValid(row,event,qualified) {
    requireValue(obj(row) && Number.isSafeInteger(row.observationOrdinal) && row.observationOrdinal>0 &&
        ['AFFECTED','COMPARISON'].includes(row.role) && row.personRole==='EVENT_SUBJECT' &&
        Number.isSafeInteger(row.altReads) && row.altReads>=0 && Number.isSafeInteger(row.totalReads) && row.totalReads>0 && row.altReads<=row.totalReads &&
        typeof row.limit==='number' && Number.isFinite(row.limit) && row.limit>=0 && row.limit<=1 && proofLabel(row.method) && proofLabel(row.quality) &&
        sourcesValid(row.sources,event,[`/tissues/${row.observationOrdinal-1}/`]), 'DENOVO_ADOPTION_MEASUREMENT_INVALID');
    if (qualified) {
        const [n,d]=decimalRatio(row.limit);
        requireValue(row.quality==='HIGH_CONFIDENCE' && assayMethods.has(row.method) &&
            (row.altReads===0 || BigInt(row.altReads)*d>=BigInt(row.totalReads)*n), 'DENOVO_ADOPTION_MEASUREMENT_QUALITY_INVALID');
    }
}
function tissueProofEvent(event,summary) {
    const c1=event.c1,c2=event.c2,availability=event.parentalAvailability;
    requireValue(obj(c1) && ['MET','NOT_ESTABLISHED'].includes(c1.status) &&
        ['PRESENT','ABSENT_QUALIFIED','UNKNOWN'].includes(c1.probandTarget) && ['ABSENT_QUALIFIED','UNKNOWN','PRESENT'].includes(c1.motherTarget) &&
        ['ABSENT_QUALIFIED','UNKNOWN','PRESENT'].includes(c1.fatherTarget) && typeof c1.confirmedParentage==='boolean' &&
        ['CONFIRMED','ASSUMED_NOT_TESTED','ASSUMED_NOT_REPORTED','UNKNOWN','EXCLUDED'].includes(c1.maternity) &&
        ['CONFIRMED','ASSUMED_NOT_TESTED','ASSUMED_NOT_REPORTED','UNKNOWN','EXCLUDED'].includes(c1.paternity) &&
        c1.confirmedParentage===(c1.maternity==='CONFIRMED'&&c1.paternity==='CONFIRMED') && c1.confirmedParentage===summary.confirmedParentage &&
        sourcesValid(c1.sources,summary,['/trio/']), 'DENOVO_ADOPTION_C1_INVALID');
    const first=c1.probandTarget==='PRESENT'&&c1.motherTarget==='ABSENT_QUALIFIED'&&c1.fatherTarget==='ABSENT_QUALIFIED'&&c1.confirmedParentage;
    requireValue((c1.status==='MET')===first && (c1.status!=='MET' || assayMethods.has(c1.method)&&kinshipMethods.has(c1.maternityMethod)&&
        kinshipMethods.has(c1.paternityMethod)&&c1.targetCoverage==='EXPLICIT_TARGET'&&c1.detectionCapability==='ADEQUATE'&&c1.quality==='VALID'), 'DENOVO_ADOPTION_C1_INVALID');
    requireValue(obj(availability) && ['AVAILABLE','UNAVAILABLE','UNKNOWN'].includes(availability.value) &&
        sourcesValid(availability.sources,summary,['/parentalAvailability']) && !(availability.value==='UNAVAILABLE' &&
            (c1.motherTarget!=='UNKNOWN'||c1.fatherTarget!=='UNKNOWN'||['CONFIRMED','EXCLUDED'].includes(c1.maternity)||['CONFIRMED','EXCLUDED'].includes(c1.paternity))), 'DENOVO_ADOPTION_AVAILABILITY_CONFLICT');
    requireValue(obj(c2) && ['MET','NOT_ESTABLISHED'].includes(c2.status) && c2.comparisonMethod==='QUALIFIED_AFFECTED_FRACTION_GREATER_THAN_COMPARISON' &&
        array(c2.observations,20), 'DENOVO_ADOPTION_C2_INVALID');
    c2.observations.forEach(row=>measurementValid(row,summary,c2.status==='MET'));
    const second=c2.status==='MET';
    if (second) {
        const [a,b]=c2.observations;
        requireValue(c2.observations.length===2 && a.role==='AFFECTED'&&b.role==='COMPARISON'&&a.observationOrdinal!==b.observationOrdinal &&
            c2.samePerson===true&&c2.distinctTissues===true&&c2.detectionAdequate===true&&a.altReads>0 &&
            BigInt(a.altReads)*BigInt(b.totalReads)>BigInt(b.altReads)*BigInt(a.totalReads), 'DENOVO_ADOPTION_C2_COMPARISON_INVALID');
        const [n,d]=decimalRatio(b.limit);
        requireValue(b.altReads>0||n*BigInt(a.totalReads)<=BigInt(a.altReads)*d, 'DENOVO_ADOPTION_C2_DETECTION_INVALID');
    } else requireValue(c2.limitation==='NO_QUALIFIED_COMPARISON_ESTABLISHED_NOT_PROVEN_ABSENT' &&
        !Object.hasOwn(c2,'samePerson')&&!Object.hasOwn(c2,'distinctTissues')&&!Object.hasOwn(c2,'detectionAdequate'), 'DENOVO_ADOPTION_C2_INVALID');
    const branch=first&&second?'C1_AND_C2':first?'C1_ONLY':second?'PARENTS_UNAVAILABLE_AND_C2':'CONDITIONS_NOT_ESTABLISHED';
    requireValue(event.conditionBranch===branch, 'DENOVO_ADOPTION_TISSUE_BRANCH_INVALID');
    const level=first&&second?2:first||availability.value==='UNAVAILABLE'&&second?1:0;
    const counted=event.disposition==='COUNTED';
    const strength=level===2?'STRONG':level===1?'MODERATE':'NONE';
    requireValue(counted ? level>0&&event.phenotypeCategory!=='NOT_CONSISTENT'&&event.adoptedStrength===strength :
        event.disposition==='ZERO_SUPPORT'&&!Object.hasOwn(event,'adoptedStrength'), 'DENOVO_ADOPTION_FIXED_STRENGTH_INVALID');
    return counted?level:0;
}
function adoptionMetadata(facts,profile,expected,policy) {
    const selections=facts.specificationSelections,adoptions=facts.specializedPolicyAdoptions,bindings=facts.calculationBindings;
    requireValue(array(selections,2)&&array(adoptions,2)&&array(bindings,2)&&selections.length===expected.length&&
        adoptions.length===expected.length&&bindings.length===expected.length, 'DENOVO_ADOPTION_EXECUTION_METADATA_REQUIRED');
    const used=new Set();
    for (const id of expected) {
        const ss=selections.filter(s=>obj(s)&&same(s.criterionIds,[id])),aa=adoptions.filter(a=>a?.criterionId===id),bb=bindings.filter(b=>b?.criterionId===id);
        requireValue(ss.length===1&&aa.length===1&&bb.length===1, 'DENOVO_ADOPTION_SELECTION_IDENTITY_INVALID');
        const [s]=ss,[a]=aa,[b]=bb;
        requireValue(s.schemaVersion==='acmg-specification-selection-v1'&&/^cspec-selection-sha256-[a-f0-9]{64}$/.test(s.selectionId||'')&&
            hash(s.catalogSha256)&&proofLabel(s.catalogId)&&proofLabel(s.catalogVersion)&&proofLabel(s.catalogEntryId)&&
            s.disposition==='SPECIALIZED_IMPLEMENTED'&&s.outcome==='SPECIALIZED_POLICY_GAP'&&s.reasonCode==='DENOVO_SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED'&&
            a.schemaVersion==='denovo-specialized-policy-adoption-v1'&&a.status==='SELECTED'&&a.globalSelectionId===s.selectionId&&a.catalogEntryId===s.catalogEntryId&&
            a.eventMode===policy.eventMode&&a.phenotypePolicySha256===policy.phenotypePolicySha256&&hash(a.policySha256)&&proofLabel(a.policyId)&&proofLabel(a.policyVersion)&&
            b.phenotypePolicySha256===a.phenotypePolicySha256&&b.selectorPolicySha256===a.policySha256&&
            semver(b.declaredCalculatorVersion,'denovo-event-calculator-',2)&&b.runtimeCalculatorVersion===profile?.moduleBindings?.deNovo?.calculatorVersion&&
            b.executedCalculatorVersion===b.runtimeCalculatorVersion&&b.declaredCalculatorVersion===b.runtimeCalculatorVersion&&b.compatibilityMode==='EXACT_VERSION', 'DENOVO_ADOPTION_EXECUTION_BINDING_INVALID');
        requireValue(!used.has(s.selectionId),'DENOVO_ADOPTION_SELECTION_IDENTITY_INVALID');used.add(s.selectionId);
    }
}
function checkPublishedContext(facts,context,policy) {
    if (!context) return; // Outer current-attempt binding remains a caller gate.
    requireValue(obj(context) && obj(context.variant?.evidenceQueryAllele), 'DENOVO_ADOPTION_CONTEXT_INVALID');
    const inheritance={AUTOSOMAL_DOMINANT:'AD',AUTOSOMAL_RECESSIVE:'AR',X_LINKED_RECESSIVE:'XLR'}[context.moi];
    for (const material of facts.coreMaterials || []) if (material.selectionMatch==='EXACT') for (const field of material.fields || []) {
        if (field.verification!=='VERIFIED'||field.availability!=='REPORTED') continue;
        const expected={'/context/allele':context.variant.evidenceQueryAllele,'/context/hgncId':context.gene?.hgncId,
            '/context/diseaseId':context.disease?.mondoId,'/context/inheritance':inheritance,'/context/policyId':policy.policyId,'/context/policyVersion':policy.policyVersion};
        if (Object.hasOwn(expected,field.path)) requireValue(expected[field.path]!==undefined&&same(field.value,expected[field.path]),'DENOVO_ADOPTION_CONTEXT_INVALID');
    }
}
function adoptionProof(facts,profile,expected,options={}) {
    const p=facts.adoptionProof;
    requireValue(obj(p)&&p.schemaVersion==='denovo-adoption-proof-v1', 'DENOVO_ADOPTION_PUBLIC_PROOF_INVALID');
    if (p.projectionStatus!=='COMPLETE') requireValue(false,p.projectionStatus==='INCOMPLETE'?'DENOVO_ADOPTION_PUBLIC_PROOF_INCOMPLETE':'DENOVO_ADOPTION_PUBLIC_PROOF_UNAVAILABLE');
    requireValue(['ADOPTED','NOT_ADOPTED','NOT_APPLICABLE'].includes(p.status)&&p.basis==='EXECUTED_QUALIFIED_OBSERVATIONS'&&
        p.calculationMethod===facts.calculationMethod&&p.selectedCriterion===facts.selectedCriterion&&integer(p.totalEventCount)&&
        p.totalEventCount===facts.eventCount&&array(p.limitations,100)&&p.limitations.length===0, 'DENOVO_ADOPTION_PUBLIC_PROOF_INVALID');
    if (p.status==='NOT_APPLICABLE') {
        requireValue(p.events?.length===0&&!p.policy,'DENOVO_ADOPTION_NONAPPLICABLE_CONTRADICTION');return {special:false};
    }
    const policy=p.policy;
    requireValue(obj(policy)&&['TISSUE_DIFFERENTIAL_PS2','SVI_1_1_COMBINED_PS2','RASOPATHY_POINTS','SVI_1_1_PHENOTYPE_ONLY',PUBLISHED_POINTS].includes(policy.eventMode)&&hash(policy.phenotypePolicySha256)&&
        proofLabel(policy.policyId)&&proofLabel(policy.policyVersion)&&policy.clauseIdentityMethod==='POLICY_SHA256_MODE_SOURCE_LOCATOR'&&proofLabel(policy.materialClass)&&
        (facts.materialUse!=='SYNTHETIC_VALIDATION'?policy.materialClass!=='SYNTHETIC':true)&&array(policy.sources,32)&&policy.sources.length>0&&
        policy.sources.every(source=>obj(source)&&proofLabel(source.sourceId)&&hash(source.sourceSha256)&&proofLabel(source.locator))&&
        new Set(policy.sources.map(source=>source.sourceId)).size===policy.sources.length, 'DENOVO_ADOPTION_POLICY_SOURCE_INVALID');
    adoptionMetadata(facts,profile,expected,policy);checkPublishedContext(facts,options.context,policy);
    if (policy.eventMode==='SVI_1_1_PHENOTYPE_ONLY') requireValue(p.status==='NOT_ADOPTED'&&facts.selectedCriterion==='NONE'&&
        facts.rawQuarterPoints===0&&facts.effectiveQuarterPoints===0&&facts.events.every(event=>event.disposition!=='COUNTED'),
        'DENOVO_PHENOTYPE_ONLY_CONTRADICTION');
    requireValue(facts.events.filter(event=>event.disposition==='COUNTED').every(event=>event.sourceRefs.includes(policy.phenotypePolicySha256+'#')),
        'DENOVO_ADOPTION_POLICY_EVENT_BINDING_INVALID');
    const rasopathy = policy.eventMode === 'RASOPATHY_POINTS';
    const published = policy.eventMode === PUBLISHED_POINTS;
    if (published) requireValue(STRENGTHS.includes(policy.maximumPm6Strength), 'DENOVO_ADOPTION_STRENGTH_SCOPE_INVALID');
    requireValue(policy.eventMode!=='TISSUE_DIFFERENTIAL_PS2'?facts.calculationMethod==='SVI_POINTS':facts.status==='MET'||facts.status==='NOT_MET'?
        facts.calculationMethod==='EXPERT_FIXED_STRENGTH'&&facts.strengthAdjustment==='NO_SVI_POINT_SUM':true, 'DENOVO_ADOPTION_METHOD_MODE_INVALID');
    requireValue(array(p.events,100)&&p.events.length===p.totalEventCount&&(facts.status==='MET'?p.status==='ADOPTED':p.status==='NOT_ADOPTED'), 'DENOVO_ADOPTION_RESULT_STATUS_INVALID');
    let level=0, confirmed=0, assumed=0;
    for (const [index,event] of p.events.entries()) {
        const summary=facts.events[index];
        requireValue(obj(event)&&['ordinal','disposition','sourceKind','reasonCode'].every(key=>event[key]===summary[key])&&
            ['QUALIFIED_CONTRIBUTION','NOT_ADOPTED'].includes(event.status)&&
            event.status===(event.disposition==='COUNTED'?'QUALIFIED_CONTRIBUTION':'NOT_ADOPTED'), 'DENOVO_ADOPTION_EVENT_BINDING_INVALID');
        if (!['COUNTED','ZERO_SUPPORT'].includes(event.disposition)) {
            requireValue(!event.adoptedStrength&&!event.c1&&!event.c2&&!event.combined,'DENOVO_ADOPTION_EXCLUDED_CONTRADICTION');continue;
        }
        requireValue(summary.phenotypeCategories.includes(event.phenotypeCategory),'DENOVO_ADOPTION_PHENOTYPE_INVALID');
        if (rasopathy) requireValue(['RASOPATHY_COMPATIBLE','CONSISTENT','NOT_CONSISTENT'].includes(event.phenotypeCategory),
            'DENOVO_ADOPTION_PHENOTYPE_INVALID');
        if (policy.eventMode==='TISSUE_DIFFERENTIAL_PS2') level=Math.max(level,tissueProofEvent(event,summary));
        else if (event.disposition==='COUNTED') {
            const c=event.combined;
            const branch = published ? 'SOURCE_BOUND_PUBLISHED_SVI_POINTS' :
                rasopathy ? 'SOURCE_BOUND_RASOPATHY_POINTS' : 'SOURCE_BOUND_COMBINED_PS2';
            const allowedParentage = ['CONFIRMED','ASSUMED_NOT_TESTED','ASSUMED_NOT_REPORTED'];
            if (rasopathy || published) allowedParentage.push('ASSUMED_NOT_CONFIRMED');
            requireValue(event.conditionBranch===branch&&obj(c)&&c.status==='MET'&&c.clauseMode===policy.eventMode&&
                c.probandTarget==='PRESENT'&&c.motherTarget==='ABSENT_QUALIFIED'&&c.fatherTarget==='ABSENT_QUALIFIED'&&
                allowedParentage.includes(c.maternity)&&allowedParentage.includes(c.paternity)&&
                typeof c.confirmedParentage==='boolean'&&c.confirmedParentage===(c.maternity==='CONFIRMED'&&c.paternity==='CONFIRMED')&&c.confirmedParentage===summary.confirmedParentage&&
                typeof c.allowAssumedParentage==='boolean'&&(c.confirmedParentage||c.allowAssumedParentage)&&
                c.phenotypeCategory===event.phenotypeCategory&&c.rawQuarterPoints===summary.rawQuarterPoints&&
                (c.sourceMappingStatus==='QUALIFIED_OBSERVATION_LEVEL'?Array.isArray(c.sources)&&c.sources.every(source=>source.role==='QUALIFIED_OBSERVATION'):
                    c.sourceMappingStatus===undefined&&Array.isArray(c.sources)&&c.sources.some(source=>source.mapping==='RETAINED_FACT'))&&
                sourcesValid(c.sources,summary,c.sourceMappingStatus==='QUALIFIED_OBSERVATION_LEVEL'?[]:['/trio/','/kind']), 'DENOVO_ADOPTION_COMBINED_CONDITIONS_INVALID');
            if (published) requireValue(event.sourceKind==='LITERATURE_CASE' && c.familyHistory==='COMPATIBLE' &&
                ['CONSISTENT','HIGHLY_SPECIFIC'].includes(event.phenotypeCategory) && c.sourceMappingStatus==='QUALIFIED_OBSERVATION_LEVEL',
                'DENOVO_ADOPTION_PUBLISHED_CONDITIONS_INVALID');
            if (c.confirmedParentage) confirmed += 1;
            else assumed += 1;
        } else requireValue(!event.adoptedStrength&&!event.combined,'DENOVO_ADOPTION_ZERO_SUPPORT_CONTRADICTION');
    }
    const nominal=policy.eventMode==='TISSUE_DIFFERENTIAL_PS2'?(level===2?'STRONG':level===1?'MODERATE':'NONE'):fromPoints(facts.effectiveQuarterPoints);
    const unresolved=rasopathy&&nominal==='VERY_STRONG'&&confirmed<2&&!(confirmed===1&&assumed>=2);
    const capped=published && confirmed===0 && STRENGTHS.indexOf(nominal)>STRENGTHS.indexOf(policy.maximumPm6Strength);
    if (published) requireValue(facts.strengthAdjustment===(capped?'PM6_PUBLISHED_STRENGTH_CAP':'NONE'),
        'DENOVO_STRENGTH_ADJUSTMENT_INVALID');
    const strength=unresolved?'NONE':capped?policy.maximumPm6Strength:nominal;
    const owner=(rasopathy||published)&&confirmed===0?'PM6':'PS2';
    requireValue(p.appliedStrength===strength&&(facts.status==='MET'?strength!=='NONE'&&p.selectedCriterion===owner:strength==='NONE'), 'DENOVO_ADOPTION_STRENGTH_INVALID');
    const reason=unresolved?'DENOVO_EXPERT_STRENGTH_SCOPE_UNRESOLVED':facts.status==='MET'?
        (policy.eventMode==='TISSUE_DIFFERENTIAL_PS2'?'DENOVO_EXPERT_TISSUE_STRENGTH':'DENOVO_QUALIFIED_POINTS'):p.reasonCode;
    requireValue(p.reasonCode===reason&&code(reason), 'DENOVO_ADOPTION_REASON_INVALID');
    return {special:true,mode:policy.eventMode,strength,reason,unresolved};
}
export function inspectDeNovoAdoptionProof(facts,profile,expected=IDS,options={}) {
    try{return {valid:true,issues:[],...adoptionProof(facts,profile,expected,options)};}
    catch(error){return {valid:false,issues:[error?.message?.startsWith('DENOVO_')?error.message:'DENOVO_ADOPTION_PUBLIC_PROOF_MALFORMED']};}
}

export function inspectDeNovoMethodsPair(rows, profile, expected = IDS, options = {}) {
    try {
        requireValue(array(rows, 100) && array(expected, 2) && expected.length > 0 && new Set(expected).size === expected.length &&
            expected.every(id => IDS.includes(id)) && !rows.some(r => !IDS.includes(r?.criterionId) && Object.hasOwn(r?.details || {}, 'deNovoFacts')), 'DENOVO_PAIR_SCOPE_INVALID');
        const pair = rows.filter(row => IDS.includes(row?.criterionId));
        requireValue(pair.length === expected.length && new Set(pair.map(row => row.criterionId)).size === expected.length && pair.every(row =>
            expected.includes(row.criterionId) && ['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(row.status) && code(row.reasonCode) &&
            typeof row.reason === 'string' && obj(row.details)), 'DENOVO_PAIR_ROWS_INVALID');
        const facts = pair[0].details.deNovoFacts;
        if (options.context === null && pair.every(row => !Object.hasOwn(row.details, 'deNovoFacts') &&
            row.status === 'NOT_EVALUATED' && row.appliedStrength === 'NONE' && row.reasonCode === 'BINDING_NOT_READY')) {
            return { valid: true, issues: [] };
        }
        requireValue(obj(facts) && pair.every(row => same(row.details.deNovoFacts, facts)) && facts.schemaVersion === 'denovo-selected-facts-v1' &&
            semver(facts.detailVersion, '', 2) && ['CURRENT_CASE_LOCAL_REPORTS', 'CURRENT_CASE_AND_LITERATURE'].includes(facts.sourceScope) &&
            ['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(facts.status) && facts.clinicalApproval === 'NONE' && facts.completeLiteratureSearch === false &&
            array(facts.events) && codes(facts.qualificationIssues), 'DENOVO_PUBLIC_METHOD_SUMMARY_INVALID');
        validateBinding(facts, profile);
        if (Object.hasOwn(facts, 'coreMaterials')) requireValue(array(facts.coreMaterials, 256) &&
            (facts.coreMaterials.length === 0 || typeof options.validCoreMaterial === 'function') && facts.coreMaterials.every(material => options.validCoreMaterial(material)), 'DENOVO_CORE_MATERIAL_VALIDATION_REQUIRED');
        if (facts.coreMaterials) requireValue(facts.coreMaterials.every(material => !Object.hasOwn(material, 'sourceDiscovery') ||
            deNovoSourceDiscoveryValid(material.sourceDiscovery)), 'DENOVO_SOURCE_DISCOVERY_INVALID');
        validateExplanations(facts, pair, expected);
        const scored = Object.hasOwn(facts, 'selectedCriterion');
        if (!scored) {
            if (Object.hasOwn(facts,'adoptionProof')) {
                const proof=facts.adoptionProof;
                requireValue(obj(proof)&&proof.schemaVersion==='denovo-adoption-proof-v1'&&proof.status==='NOT_RUN'&&proof.projectionStatus==='NOT_AVAILABLE'&&
                    !proof.policy&&!proof.events&&!proof.selectedCriterion&&!proof.appliedStrength&&
                    (!Object.hasOwn(facts,'calculationBindings')||array(facts.calculationBindings,2)&&facts.calculationBindings.every(binding=>
                        obj(binding)&&expected.includes(binding.criterionId)&&!Object.hasOwn(binding,'executedCalculatorVersion'))), 'DENOVO_UNEXECUTED_PROOF_CONTRADICTION');
            }
            requireValue(facts.status === 'NOT_EVALUATED' && code(facts.reasonCode) && facts.events.length === 0 &&
                !Object.hasOwn(facts, 'calculationMethod') && !Object.hasOwn(facts, 'strengthAdjustment') &&
                !['rawQuarterPoints', 'effectiveQuarterPoints', 'eventCount', 'highHeterogeneityRawQuarterPoints', 'highHeterogeneityEffectiveQuarterPoints'].some(k => Object.hasOwn(facts, k)) &&
                pair.every(row => row.status === 'NOT_EVALUATED' && row.appliedStrength === 'NONE' && row.reasonCode === facts.reasonCode), 'DENOVO_UNEXECUTED_RESULT_CONTRADICTION');
            return { valid: true, issues: [] };
        }
        requireValue(['NONE', 'PS2', 'PM6'].includes(facts.selectedCriterion) &&
            ['rawQuarterPoints', 'effectiveQuarterPoints', 'highHeterogeneityRawQuarterPoints', 'highHeterogeneityEffectiveQuarterPoints'].every(key => integer(facts[key])) &&
            Number.isSafeInteger(facts.eventCount) && facts.eventCount >= 0 && facts.eventCount <= 1000 && facts.eventCount === facts.events.length &&
            typeof facts.hasExcludedObservations === 'boolean' && ['SVI_POINTS', 'EXPERT_FIXED_STRENGTH'].includes(facts.calculationMethod), 'DENOVO_CALCULATION_METHOD_INVALID');
        const fixed = facts.calculationMethod === 'EXPERT_FIXED_STRENGTH', composition = validateEvents(facts, fixed);
        const adopted = Object.hasOwn(facts,'adoptionProof') ? adoptionProof(facts,profile,expected,options) : null;
        requireValue(!facts.events.some(event=>event.phenotypeCategories.includes('RASOPATHY_COMPATIBLE')) || adopted?.mode==='RASOPATHY_POINTS',
            'DENOVO_ADOPTION_POLICY_SOURCE_INVALID');
        if (fixed) {
            requireValue(facts.strengthAdjustment === 'NO_SVI_POINT_SUM' && (!['MET', 'NOT_MET'].includes(facts.status) || facts.selectedCriterion === (facts.status === 'MET' ? 'PS2' : 'NONE')),
                'DENOVO_FIXED_METHOD_CONTRADICTION');
            if (!adopted?.special) return { valid: false, issues: ['DENOVO_FIXED_STRENGTH_PUBLIC_PROOF_REQUIRED'] };
            requireValue(adopted.mode==='TISSUE_DIFFERENTIAL_PS2'&&pair.every(row=>{
                const chosen=row.criterionId===`ACMG_AMP_2015:${facts.selectedCriterion}`;
                return row.status===(facts.status==='MET'&&!chosen?'NOT_EVALUATED':facts.status)&&row.appliedStrength===(facts.status==='MET'&&chosen?adopted.strength:'NONE')&&
                    row.reasonCode===(facts.status==='MET'&&!chosen?`DENOVO_SHARED_EVENT_ASSIGNED_TO_${facts.selectedCriterion}`:adopted.reason);
            }), 'DENOVO_ADOPTION_FIXED_ROW_MISMATCH');
            return {valid:true,issues:[]};
        }
        const mixed = facts.status === 'NOT_EVALUATED' && pair.every(row => row.reasonCode === 'DENOVO_AR_MIXED_REDUCTION_RULE_NOT_IMPLEMENTED');
        requireValue(mixed ? !Object.hasOwn(facts, 'strengthAdjustment') : adopted?.mode===PUBLISHED_POINTS ||
            ['NONE', 'ONE_LEVEL_AR_NO_SECOND_P_LP'].includes(facts.strengthAdjustment), 'DENOVO_STRENGTH_ADJUSTMENT_INVALID');
        let strength = fromPoints(facts.effectiveQuarterPoints);
        if (facts.strengthAdjustment === 'ONE_LEVEL_AR_NO_SECOND_P_LP') strength = reduced(strength);
        if (adopted?.mode===PUBLISHED_POINTS) strength = adopted.strength;
        if (facts.status === 'NOT_EVALUATED') {
            requireValue(facts.selectedCriterion === 'NONE' && pair.every(row => row.status === 'NOT_EVALUATED' && row.appliedStrength === 'NONE') &&
                (mixed || adopted?.unresolved || facts.rawQuarterPoints === 0 && composition.counted === 0) &&
                (!adopted?.special||pair.every(row=>row.reasonCode===adopted.reason)), 'DENOVO_UNEVALUATED_POINTS_CONTRADICTION');
            return { valid: true, issues: [] };
        }
        const met = strength !== 'NONE';
        requireValue((facts.status === 'MET') === met && (met ? facts.selectedCriterion !== 'NONE' : facts.selectedCriterion === 'NONE'), 'DENOVO_ADJUSTED_STATUS_MISMATCH');
        if (met && facts.selectedCriterion === 'PS2' && !composition.confirmed && adopted?.mode!=='SVI_1_1_COMBINED_PS2') {
            return { valid: false, issues: ['DENOVO_COMBINED_PS2_PUBLIC_POLICY_PROOF_REQUIRED'] };
        }
        requireValue(!met || facts.selectedCriterion === (composition.confirmed || adopted?.mode==='SVI_1_1_COMBINED_PS2' ? 'PS2' : 'PM6'), 'DENOVO_EVENT_SELECTION_MISMATCH');
        const phenotypeMismatch = ['SVI_1_1_PHENOTYPE_ONLY',PUBLISHED_POINTS].includes(adopted?.mode) && facts.events.some(event =>
            event.disposition === 'ZERO_SUPPORT' && event.reasonCode === 'DENOVO_PHENOTYPE_NOT_CONSISTENT' &&
            event.phenotypeCategories.includes('NOT_CONSISTENT'));
        const familyHistoryPresent = adopted?.mode===PUBLISHED_POINTS && facts.events.some(event =>
            event.disposition==='ZERO_SUPPORT' && event.reasonCode==='DENOVO_PUBLISHED_FAMILY_HISTORY_CONDITION_NOT_MET');
        const reason = facts.strengthAdjustment === 'ONE_LEVEL_AR_NO_SECOND_P_LP' ? 'DENOVO_AR_ONE_LEVEL_REDUCTION'
            : met ? 'DENOVO_QUALIFIED_POINTS' : phenotypeMismatch ? 'DENOVO_PHENOTYPE_NOT_CONSISTENT'
                : familyHistoryPresent ? 'DENOVO_PUBLISHED_FAMILY_HISTORY_CONDITION_NOT_MET' : 'DENOVO_BELOW_SUPPORTING';
        requireValue(pair.every(row => {
            const chosen = row.criterionId === `ACMG_AMP_2015:${facts.selectedCriterion}`;
            return row.status === (met && !chosen ? 'NOT_EVALUATED' : facts.status) && row.appliedStrength === (met && chosen ? strength : 'NONE') &&
                row.reasonCode === (met && !chosen ? `DENOVO_SHARED_EVENT_ASSIGNED_TO_${facts.selectedCriterion}` : reason);
        }) && (!['SVI_1_1_PHENOTYPE_ONLY',PUBLISHED_POINTS].includes(adopted?.mode) || adopted.reason === reason), 'DENOVO_ADJUSTED_ROW_MISMATCH');
        return { valid: true, issues: [] };
    } catch (error) {
        return { valid: false, issues: [typeof error?.message === 'string' && error.message.startsWith('DENOVO_') ? error.message : 'DENOVO_PUBLIC_METHOD_MALFORMED_VALUE'] };
    }
}

export const validateDeNovoMethodsPair = (rows, profile, expected, options) => inspectDeNovoMethodsPair(rows, profile, expected, options).valid;
const show = value => value === undefined ? '未提供' : typeof value === 'string' ? value : JSON.stringify(value);
const points = value => integer(value) ? `${value / 4} 分（${value} 四分点）` : '未计算';
const issueMeaning = issue => /CONFLICT|MISMATCH|AMBIGUOUS/.test(issue) ? '来源冲突或身份差异，不能选取有利值' :
    /FAILED|FAILURE|PIN_|PARSE|INVALID|UNAVAILABLE|NOT_REGISTERED/.test(issue) ? '取得、读取或核验失败，不能解释为原件没有资料' :
        /UNSUPPORTED|NOT_IMPLEMENTED/.test(issue) ? '当前方法未支持，不能解释为已证实缺失' :
            /MISSING|REQUIRED|INCOMPLETE/.test(issue) ? '当前已查范围仍缺所需字段，未证明全文不存在' : '保留原始原因，当前未据此采用证据';

export function deNovoSourceDiscoveryDetails(discovery, label = '来源自动定位') {
    if (!obj(discovery)) return [];
    const rows = [[`${label}用途`, '只读已登记的原件并定位明确字段；criterionDecision=NOT_RUN。找到字段、候选记录完整或资料已检查均不等于已采用新发证据。'],
        [`${label}已核范围`, Array.isArray(discovery.checkedSourceSha256s) ? discovery.checkedSourceSha256s.join('\n') : '未提供已核原件摘要']];
    for (const issue of (Array.isArray(discovery.issues) ? discovery.issues : []).filter(obj)) rows.push([
        `${label}执行或核验问题`, `${show(issue.code)}：${issueMeaning(issue.code || '')}；原件 SHA256 ${show(issue.sourceSha256)}；${
            Array.isArray(discovery.checkedSourceSha256s) && discovery.checkedSourceSha256s.includes(issue.sourceSha256) ? '此摘要在已查范围' : '此失败记录不代表该原件已完成检查'}`]);
    const records = (Array.isArray(discovery.records) ? discovery.records : []).filter(obj);
    if (!records.length) rows.push([`${label}记录`, '当前已查范围没有定位到可列出的记录；不声称论文、附件或病例全文不存在相关资料。']);
    records.forEach((record, index) => {
        const prefix = `${label}记录 ${index + 1}`;
        rows.push([prefix, `${record.isEventCandidate ? '事件候选记录，尚未授证' : '仅定位病例/变异字段，未构成事件候选'}；原件 SHA256 ${show(record.sourceSha256)}；记录位置 ${record.recordLocator === '' ? 'JSON 根对象' : show(record.recordLocator)}`]);
        if (obj(record.caseReference) && Object.keys(record.caseReference).length) rows.push([`${prefix}病例对应`, `${show(record.caseReference.kind)} ${show(record.caseReference.locator)}；原句 ${show(record.caseReference.quote)}；SHA256 ${show(record.caseReference.sourceSha256)}`]);
        if (Array.isArray(record.missingPaths) && record.missingPaths.length) rows.push([`${prefix}所需字段仍缺`, `${record.missingPaths.join('、')}；仅指本记录尚未取得，不代表原文明确否定。`]);
        if (Array.isArray(record.issues)) for (const issue of record.issues) rows.push([`${prefix}差异或限制`, `${issue}：${issueMeaning(issue)}`]);
        for (const field of (Array.isArray(record.fields) ? record.fields : []).filter(obj)) rows.push([`${prefix}已找到 ${show(field.path)}`, `${show(field.quote)}；${show(field.kind)} ${show(field.locator)}；SHA256 ${show(field.sourceSha256)}`]);
    });
    return rows;
}

export function deNovoMethodsResultDetails(criterion) {
    const f = criterion?.details?.deNovoFacts;
    if (!IDS.includes(criterion?.criterionId) || !isDeNovoMethodsSummary(f)) return [];
    const fixed = f.calculationMethod === 'EXPERT_FIXED_STRENGTH', adjusted = f.strengthAdjustment === 'ONE_LEVEL_AR_NO_SECOND_P_LP';
    const rasopathy = f.adoptionProof?.policy?.eventMode === 'RASOPATHY_POINTS';
    // Invalid saved summaries also enter this read-only diagnostic projection.
    // Array/element checks here do not replace the independent adoption checks.
    const events = (Array.isArray(f.events) ? f.events : []).filter(obj);
    const rows = [
        ['新发计算方法', rasopathy ? 'RASopathy 专家组新生积分' : fixed ? '适用专规的固定强度；不累计 SVI 积分' : f.calculationMethod === 'SVI_POINTS' ? 'SVI 新发事件积分' : '尚未执行计算或方法未知'],
        ['实际保存结论', `${criterion.criterionId}：${criterion.status}；保存强度 ${criterion.appliedStrength}；原因 ${criterion.reasonCode}；同组采用 ${show(f.selectedCriterion)}`],
        ['强度调整', adjusted ? '隐性背景已核范围内没有另一独立 P/LP 等位基因：按有效积分计算原始强度后降一档；保留原始积分，不从最终强度反推或改写积分。' :
            fixed ? 'NO_SVI_POINT_SUM：零积分可与已采用固定强度并存；积分不能用于核定该专规强度。' :
                f.strengthAdjustment === 'PM6_PUBLISHED_STRENGTH_CAP' ? '按已发布专规的 PM6 最高强度采用' :
                    f.strengthAdjustment === 'NONE' ? '未作强度降档' : '调整规则未提供；保留原始状态与原因，不能补默认值。'],
        ['原始与有效积分', `原始 ${points(f.rawQuarterPoints)}；高异质性原始 ${points(f.highHeterogeneityRawQuarterPoints)}，封顶后 ${points(f.highHeterogeneityEffectiveQuarterPoints)}；有效 ${points(f.effectiveQuarterPoints)}`],
        ['同组事件分派', 'PS2 与 PM6 共享同一事件；保存的选择、未采用原因与单选范围分别保留。'],
        ['资料与模型范围', `${show(f.sourceScope)}；${show(f.materialUse)}；${show(f.modelValidation)}；${show(f.literatureQualification)}；公开详情版本 ${show(f.detailVersion)}；不代表完整文献检索或临床批准`],
    ];
    if (fixed && !f.adoptionProof) rows.push(['固定强度展示核验依赖', `保存的 ${criterion.appliedStrength} 原值保留。后台公开摘要未提供来源绑定的专规模式、条件分支与最终采用强度，目前不能独立核对；这不是原始资料不足或证据未满足。`]);
    if (!f.adoptionProof && f.selectedCriterion === 'PS2' && f.calculationMethod === 'SVI_POINTS' && !events.some(event => event.disposition === 'COUNTED' && event.confirmedParentage === true))
        rows.push(['组合 PS2 展示核验依赖', '公开摘要没有提供已采用的组合 PS2 专规模式及来源绑定；保存原值保留，当前不能据此授证。']);
    if (obj(f.adoptionProof)) {
        const proof=f.adoptionProof;
        rows.push(['新发公开采用证明',`${show(proof.status)} / ${show(proof.projectionStatus)}；${show(proof.basis)}`],
            ['已采用专规及条款身份',show(proof.policy)],['已保存条件分支与事实',show(proof.events)],
            ['原规范选择（保留拒绝）',show(f.specificationSelections)],['专项采用回执',show(f.specializedPolicyAdoptions)],
            ['声明／运行／实际执行版本',show(f.calculationBindings)],
            ['采用证明核验边界','公开条件、精确计数比较、模式、采用强度及来源引用按保存结果核对；原件重放和私有目标资格仍由后端核定，不从定位成功、零SVI或任意状态授分。']);
    }
    for (const event of events) {
        rows.push([`事件 ${event.ordinal}`, `${show(event.sourceKind)}；${show(event.disposition)}；${points(event.rawQuarterPoints)}；${event.confirmedParentage === true ? '亲缘关系已证实' : event.confirmedParentage === false ? '亲缘关系未完全证实' : '亲缘关系字段未核实'}；表型 ${show(event.phenotypeCategories)}`],
            [`事件 ${event.ordinal} 条件与限制`, `${show(event.reasonCode)}；${show(event.issues)}`],
            [`事件 ${event.ordinal} 来源`, Array.isArray(event.sourceRefs) ? event.sourceRefs.join('\n') || '未提供公开定位' : '未提供公开定位']);
        for (const comparison of (Array.isArray(event.factComparisons) ? event.factComparisons : []).filter(obj)) rows.push([`事件 ${event.ordinal} 已核来源差异`,
            `${({SUBJECT:'实际事件成员',MOTHER:'实际事件成员的母亲',FATHER:'实际事件成员的父亲'})[comparison.memberRole] || show(comparison.memberRole)}：普通来源 ${comparison.ordinaryResult === 'PRESENT' ? '检测到目标等位基因' : comparison.ordinaryResult === 'ABSENT' ? '未检测到目标等位基因' : show(comparison.ordinaryResult)}；特殊事件来源 ${comparison.specialEventResult === 'PRESENT' ? '检测到目标等位基因' : comparison.specialEventResult === 'ABSENT' ? '未检测到目标等位基因' : show(comparison.specialEventResult)}；${show(comparison.reasonCode)}。保留冲突，不采用本事件。`]);
    }
    for (const material of (Array.isArray(f.coreMaterials) ? f.coreMaterials : []).filter(obj)) {
        if (Object.hasOwn(material, 'sourceDiscovery')) rows.push(...deNovoSourceDiscoveryDetails(material.sourceDiscovery, `病例 ${show(material.caseKey)} 来源定位`));
    }
    if (f.qualificationIssues?.length) rows.push(['资料资格与先决条件', show(f.qualificationIssues)]);
    if (f.sourceSupplyIssues?.length) rows.push(['资料获取状态', show(f.sourceSupplyIssues)]);
    if (criterion.reasonCode === 'DENOVO_AR_MIXED_REDUCTION_RULE_NOT_IMPLEMENTED') rows.push(['软件未实现', '不同伙伴资格对应的隐性降档规则尚不能混合汇总；保留已有积分和来源，本次未采用，不解释为原始资料缺失。']);
    rows.push(['公开字段边界', '当前摘要公开事件原因、先决条件失败代码及来源定位；未公开原始三联体、组织测量、伙伴资格或完整拓扑。可核病例资料见原有资料卡，不补造这些事实。']);
    return rows;
}
