// Display/identity consistency of backend-produced conditions. These checks do not
// establish publisher, source or clinical qualification, and never run evidence.
const object = v => v !== null && typeof v === 'object' && !Array.isArray(v);
const own = (v, k) => object(v) && Object.hasOwn(v, k);
const text = v => typeof v === 'string' && v.length > 0 && v.length <= 512 && [...v].every(c => c.charCodeAt(0) >= 32);
const sha = v => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v);
const reason = v => typeof v === 'string' && /^[A-Z][A-Z0-9_]{2,180}$/.test(v);
const positive = v => Number.isSafeInteger(v) && v > 0;
const sourcePointer = v => typeof v === 'string' && /^\/(?:ld\/CriteriaCode\/(?:0|[1-9][0-9]*)|entContent)(?:\/[A-Za-z0-9_~.-]+)*$/.test(v) && v.length <= 1024;
const rank = { NONE: 0, SUPPORTING: 1, MODERATE: 2, STRONG: 3, VERY_STRONG: 4 };
const PM1 = 'ACMG_AMP_2015:PM1';
const same = (a, b, hash) => hash(a) === hash(b);
const fail = code => { throw new Error(code); };
const requireValue = (valid, code) => { if (!valid) fail(code); };

function contextId(c, hash, selected) {
    const p = { scope: c.scope, alleleId: c.variant.alleleId, hgncId: c.gene.hgncId,
        mondoId: c.disease.mondoId, moi: c.moi,
        transcript: { accession: c.transcript.accession, version: c.transcript.version } };
    if (selected) Object.assign(p, { domain: 'varvis-evidence-context-v3', schemaVersion: 'evidence-context-v3',
        dataClass: c.dataClass, patientContext: c.patientContext });
    else { p.patientContextId = c.patientContext.patientContextId; p.familyContextId = c.patientContext.familyContextId ?? null; }
    return `context-sha256-${hash(p)}`;
}

function callerContext(expected, hash, criterion) {
    const x = expected?.context, profile = expected?.executionProfile;
    requireValue(typeof hash === 'function' && text(expected?.analysisId) && sha(expected?.executionSourceSha256)
        && text(expected?.candidateId) && text(expected?.hypothesisId) && object(profile)
        && Array.isArray(expected.enabledCriteria) && expected.enabledCriteria.includes(criterion)
        && Array.isArray(profile.enabledCriteria) && same(profile.enabledCriteria, expected.enabledCriteria, hash), 'PUBLISHED_CONDITION_CALLER_REQUIRED');
    requireValue(object(x) && ['evidence-context-v2', 'evidence-context-v3'].includes(x.schemaVersion)
        && object(x.scope) && object(x.variant) && object(x.gene) && object(x.disease) && object(x.patientContext)
        && x.scope.analysisDomain === 'GERMLINE' && x.scope.diseaseModel === 'MENDELIAN' && x.scope.variantScope === 'SMALL_SEQUENCE_VARIANT'
        && text(x.patientContext.patientContextId) && text(x.gene.hgncId) && text(x.disease.mondoId) && text(x.moi)
        && text(x.transcript?.accession) && positive(x.transcript.version), 'PUBLISHED_CONDITION_CONTEXT_REQUIRED');
    requireValue(x.contextId === contextId(x, hash, x.schemaVersion === 'evidence-context-v3'), 'PUBLISHED_CONDITION_CALLER_CONTEXT_ID_INVALID');
    const allele = x.variant.evidenceQueryAllele, norm = x.variant.normalization;
    requireValue(object(allele) && allele.assembly === 'GRCh38' && text(allele.contig) && positive(allele.position)
        && /^[ACGT]{1,50}$/.test(allele.reference || '') && /^[ACGT]{1,50}$/.test(allele.alternate || '')
        && allele.reference !== allele.alternate && norm?.refValidation === 'MATCH' && sha(norm.referenceSequenceSha256)
        && x.variant.alleleId === `allele-sha256-${hash({ normalizedAllele: x.variant.normalizedAllele,
            normalizer: { method: norm.method, methodVersion: norm.methodVersion,
                referenceSequenceSha256: norm.referenceSequenceSha256, refValidation: norm.refValidation } })}`, 'PUBLISHED_CONDITION_CALLER_ALLELE_BINDING_INVALID');
    const evaluationContext = { ...x, schemaVersion: 'evidence-context-v2', contextId: contextId(x, hash, false) };
    return { original: x, evaluation: evaluationContext, allele };
}

export { callerContext as publishedConditionCallerContext };

function expectedBinding(c, facts, expected, hash) {
    const { original: x, evaluation: evaluationContext, allele } = callerContext(expected, hash, PM1);
    const b = facts.currentEvaluationBinding, profile = expected.executionProfile;
    const cap = profile.moduleBindings?.pm1?.runtimeBinding?.pm1ConditionalPeerExecution;
    requireValue(cap?.schemaVersion === 'pm1-current-peer-execution-v1' && cap.methodVersion === '1.0.0'
        && cap.peerContractVersion === '1.1.0' && cap.selectedOutputScopeExpanded === false
        && cap.selectionAbsenceMeansNegative === false && cap.dependencyPlanning === 'VERIFIED_MATCHED_SOURCE_SUPPORT_ONLY'
        && sha(profile.moduleBindings?.pm1?.runtimeBinding?.configurationSha256), 'PM1_CURRENT_PROFILE_BINDING_INVALID');
    requireValue(b?.schemaVersion === 'pm1-current-evaluation-binding-v1' && b.candidateId === expected.candidateId
        && b.hypothesisId === expected.hypothesisId && b.executionSourceSha256 === expected.executionSourceSha256
        && b.originalContextId === x.contextId && b.evaluationContextId === evaluationContext.contextId
        && b.contextId === evaluationContext.contextId && b.originalContextSha256 === hash(x)
        && b.evaluationContextSha256 === hash(evaluationContext) && b.queryAlleleSha256 === hash(allele)
        && sha(b.projectionBindingSha256) && b.status === c.status && b.appliedStrength === c.appliedStrength
        && b.reasonCode === c.reasonCode, 'PM1_CURRENT_RESULT_BINDING_MISMATCH');
    return b;
}

function validRule(f, c) {
    const r = f.selectedRule;
    requireValue(/^(?:ENST[0-9]+|NM_[0-9]+)\.[1-9][0-9]*$/.test(f.transcript || '')
        && /^(?:ENST[0-9]+|NM_[0-9]+)\.[1-9][0-9]*$/.test(f.declaredTranscript || '')
        && f.executionTranscript === f.transcript && /^(?:ENSP[0-9]+|NP_[0-9]+)\.[1-9][0-9]*$/.test(f.protein || '')
        && positive(f.proteinPosition) && /^[ACDEFGHIKLMNPQRSTVWY]$/.test(f.refAminoAcid || '')
        && /^[ACDEFGHIKLMNPQRSTVWY]$/.test(f.altAminoAcid || '') && f.refAminoAcid !== f.altAminoAcid
        && text(f.specificationKey) && object(r) && positive(r.start) && positive(r.end)
        && r.start <= f.proteinPosition && r.end >= f.proteinPosition && text(r.ruleId)
        && sha(r.sourceSha256) && sourcePointer(r.sourcePointer), 'PM1_CURRENT_LOCATION_INVALID');
    if (r.effect === 'EXCLUDE') requireValue(c.status === 'NOT_MET' && r.strength === 'NONE'
        && r.regionType === 'EXCLUSION' && c.reasonCode === 'PM1_PUBLISHED_EXPLICIT_EXCLUSION', 'PM1_CURRENT_EXCLUSION_MISMATCH');
    else requireValue(r.effect === 'SUPPORT' && ['MODERATE', 'SUPPORTING'].includes(r.strength)
        && ['MUTATIONAL_HOTSPOT', 'CRITICAL_FUNCTIONAL_REGION', 'CRITICAL_RESIDUE'].includes(r.regionType)
        && /^\/ld\/CriteriaCode\/(?:0|[1-9][0-9]*)$/.test(f.sourceClausePointer || '')
        && new RegExp(`^${f.sourceClausePointer}/entContent/strengthDescriptor/(?:0|[1-9][0-9]*)$`).test(r.sourcePointer), 'PM1_CURRENT_SUPPORT_RULE_INVALID');
    return r;
}

function relationValid(r) {
    if (!object(r) || !sha(r.sourceSha256) || !sourcePointer(r.sourcePointer)) return false;
    return r.criterionId === 'ACMG_AMP_2015:PM2' && r.relation === 'REQUIRE_MET_AT_LEAST' && r.minimumStrength === 'SUPPORTING'
        || ['ACMG_AMP_2015:PP3', 'ACMG_AMP_2015:PM5'].includes(r.criterionId)
            && r.relation === 'EXCLUDE_IF_MET_AT_LEAST' && r.minimumStrength === 'STRONG';
}

const nonexclusive = r => object(r) && r.relation === 'NOT_MUTUALLY_EXCLUSIVE'
    && /^ACMG_AMP_2015:[A-Z]+[0-9]+$/.test(r.criterionId || '') && sha(r.sourceSha256) && sourcePointer(r.sourcePointer)
    && Object.keys(r).every(k => ['criterionId', 'relation', 'sourceSha256', 'sourcePointer'].includes(k));

function peerChecks(f, binding, expected, hash) {
    const declared = f.crossCriterionRelations.filter(r => !nonexclusive(r));
    requireValue(Array.isArray(declared) && declared.length > 0 && declared.length <= 3
        && declared.every(relationValid) && new Set(declared.map(r => r.criterionId)).size === declared.length, 'PM1_CURRENT_PEER_DECLARATION_INVALID');
    requireValue(/^\/ld\/CriteriaCode\/(?:0|[1-9][0-9]*)$/.test(f.sourceClausePointer || '')
        && declared.every(r => r.criterionId.endsWith(':PM5')
            ? !r.sourcePointer.startsWith(`${f.sourceClausePointer}/`)
            : r.sourcePointer.startsWith(`${f.sourceClausePointer}/entContent/strengthDescriptor/`)), 'PM1_CURRENT_PEER_CLAUSE_MISMATCH');
    const check = f.crossCriterionConditionCheck;
    requireValue(check?.schemaVersion === 'pm1-published-peer-conditions-v1' && check.methodVersion === '1.1.0'
        && check.selectionAbsenceMeansNegative === false && Array.isArray(check.checks)
        && check.checks.length === declared.length, 'PM1_CURRENT_PEER_CHECK_REQUIRED');
    let status = 'READY', code = 'PM1_PUBLISHED_PEER_CONDITIONS_MET', supplied = false;
    const seen = new Set(), view = [];
    for (const item of check.checks) {
        const r = item?.required, available = item?.available;
        requireValue(relationValid(r) && !seen.has(r.criterionId) && declared.some(d => same(d, r, hash)), 'PM1_CURRENT_PEER_REQUIRED_MISMATCH');
        seen.add(r.criterionId);
        if (available !== undefined) {
            supplied = true;
            const module = r.criterionId.endsWith(':PM2') ? 'pm2' : r.criterionId.endsWith(':PP3') ? 'prediction' : 'reference';
            const evaluation = module === 'pm2' ? /^\/evaluation$/ : module === 'prediction' ? /^\/criteria\/PP3$/
                : /^\/evaluations\/PM5\/criterionEvaluations\/(?:0|[1-9][0-9]{0,5})$/;
            const context = module === 'pm2' ? '/context' : '/sourceProjection/evaluationContext';
            requireValue(object(available) && available.criterionId === r.criterionId
                && ['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(available.status)
                && (available.status === 'MET' ? rank[available.appliedStrength] > 0 : available.appliedStrength === 'NONE')
                && reason(available.reasonCode) && available.executionSourceSha256 === expected.executionSourceSha256
                && available.auditHashMethod === 'TRANSPORT_JSON_VALUE_C14N_V1'
                && sha(available.actualModuleSha256) && sha(available.actualEvaluationSha256)
                && new RegExp(`^/modules/${module}/results/(?:0|[1-9][0-9]{0,5})$`).test(available.actualModulePointer || '')
                && evaluation.test(available.evaluationPointer || '') && available.contextPointer === context, 'PM1_CURRENT_PEER_AVAILABLE_INVALID');
        }
        if (!available || available.status === 'NOT_EVALUATED') {
            requireValue(item.status === 'UNAVAILABLE' && item.missingInformation === 'ACTUAL_SAME_CONTEXT_ADOPTED_CRITERION_RESULT', 'PM1_CURRENT_PEER_MISSING_MISMATCH');
            if (status !== 'NOT_MET') { status = 'NOT_EVALUATED'; code = 'PM1_PUBLISHED_PEER_RESULT_UNAVAILABLE'; }
        } else {
            const meets = available.status === 'MET' && rank[available.appliedStrength] >= rank[r.minimumStrength];
            const passes = r.relation === 'REQUIRE_MET_AT_LEAST' ? meets : !meets;
            requireValue(item.status === (passes ? 'MET' : 'NOT_MET'), 'PM1_CURRENT_PEER_DECISION_MISMATCH');
            if (!passes) { status = 'NOT_MET'; code = r.relation === 'REQUIRE_MET_AT_LEAST'
                ? 'PM1_PUBLISHED_REQUIRED_PEER_NOT_MET' : 'PM1_PUBLISHED_PEER_EXCLUSION_APPLIES'; }
        }
        view.push({ criterionId: r.criterionId, relation: r.relation, minimumStrength: r.minimumStrength,
            sourcePointer: r.sourcePointer, sourceSha256: r.sourceSha256, status: item.status,
            available: available ? { status: available.status, strength: available.appliedStrength, reasonCode: available.reasonCode,
                modulePointer: available.actualModulePointer, evaluationPointer: available.evaluationPointer } : null });
    }
    requireValue(check.status === status && check.reasonCode === code, 'PM1_CURRENT_PEER_AGGREGATE_MISMATCH');
    if (supplied) requireValue(object(check.binding) && ['candidateId', 'hypothesisId', 'executionSourceSha256',
        'originalContextSha256', 'evaluationContextSha256', 'projectionBindingSha256'].every(k => check.binding[k] === binding[k]), 'PM1_CURRENT_PEER_BINDING_MISMATCH');
    else requireValue(!own(check, 'binding'), 'PM1_CURRENT_UNAVAILABLE_BINDING_MISMATCH');
    return { status, reasonCode: code, peers: view };
}

function inspectBp1(c, expected, hash, profile) {
    const f = c.details?.bp1Facts, configured = (expected?.executionProfile || profile)?.moduleBindings?.bp1?.runtimeBinding;
    const truncating = f?.scope === 'PUBLISHED_TRUNCATING_GOF_CONDITIONS';
    const claimed = truncating || own(f, 'currentBinding') || own(f, 'publicProjectionIssue')
        || configured?.bp1TruncatingConditionExecution?.schemaVersion === 'bp1-truncating-condition-capability-v1' && c.status !== 'NOT_EVALUATED';
    if (!claimed) return { claimed: false, valid: true, issues: [] };
    try {
        requireValue(f?.schemaVersion === 'bp1-general-facts-v1' && ['1.0.0', '1.1.0', '1.2.0'].includes(f.policyVersion)
            && f.clinicalValidationCompleted === false && f.experimentalNormalityEstablished === false
            && !own(f, 'publicProjectionIssue'), 'BP1_CURRENT_PUBLIC_PROJECTION_INVALID');
        const { original: x, evaluation, allele } = callerContext(expected, hash, 'ACMG_AMP_2015:BP1');
        const runtime = expected.executionProfile.moduleBindings?.bp1?.runtimeBinding, cap = runtime?.bp1TruncatingConditionExecution;
        const safeNative = cap?.methodVersion === '1.2.0';
        const legacyNative = cap?.methodVersion === '1.1.0';
        requireValue(runtime?.bp1GeneralVersion === f.policyVersion && sha(runtime.configurationSha256)
            && cap?.schemaVersion === 'bp1-truncating-condition-capability-v1' && ['1.0.0','1.1.0','1.2.0'].includes(cap.methodVersion)
            && cap.sequenceObservation === 'SOURCE_BOUND_CODING_TRUNCATION_CANDIDATE' && cap.pureMissenseApplicability === 'bp1-truncating-applicability-v1'
            && cap.establishedLofContraindicationSupported === true && cap.noEstablishedLofCorrelationQualificationSupported === legacyNative
            && cap.missingRecordMeansNoCorrelation === false && cap.createsReviewReceipt === false
            && cap.runtimeDispatch === 'EXACT_SOURCE_SUBTYPE_BEFORE_LEGACY_MISSENSE_READY_V1'
            && cap.publicProjection === 'BOUNDED_BP1_TRUNCATING_CONDITION_V1'
            && cap.codingProjectionScope === 'TRUNCATING_SUBTYPE_ONLY'
            && (!legacyNative || cap.canonicalSha256 === '798e14a968877833a426b024523a5642bfa04ad8c8d0caffe19fa6132f67ee78'
                && cap.noLofSourceGrammar === 'EXPLICIT_GENE_WIDE_GERMLINE_REVIEW_WITH_NATIVE_VALIDITY_V1'
                && cap.sourceDefinedTruncationMeansExperimentalLof === false)
            && (!safeNative || cap.noLofSourceGrammar === 'NATIVE_REVIEW_INTERPRETATION_PENDING_SOURCE_REPLAY_ONLY_V1'
                && cap.sourceDefinedTruncationMeansExperimentalLof === false)
            && hash(Object.fromEntries(Object.entries(cap).filter(([k]) => k !== 'canonicalSha256'))) === cap.canonicalSha256,
        'BP1_CURRENT_PROFILE_BINDING_INVALID');
        const b = f.currentBinding;
        requireValue(b?.schemaVersion === 'bp1-current-module-binding-v1' && b.executionSourceSha256 === expected.executionSourceSha256
            && b.candidateId === expected.candidateId && b.hypothesisId === expected.hypothesisId
            && b.originalContextId === x.contextId && b.evaluationContextId === evaluation.contextId
            && b.originalContextSha256 === hash(x) && b.evaluationContextSha256 === hash(evaluation)
            && b.queryAlleleSha256 === hash(allele) && sha(b.projectionBindingSha256)
            && /^\/modules\/bp1\/results\/(?:0|[1-9][0-9]{0,5})$/.test(b.modulePointer || '')
            && b.modulePointer === c.details.modulePointer && b.modulePointer === c.details.materialPreparation?.moduleResultPointer
            && b.auditHashMethod === 'TRANSPORT_JSON_VALUE_C14N_V1'
            && b.moduleHashScope === 'ACTUAL_MODULE_EXCLUDING_CURRENT_BINDING_RECEIPT'
            && sha(b.actualModuleSha256) && sha(b.actualEvaluationSha256)
            && b.status === c.status && b.appliedStrength === c.appliedStrength && b.reasonCode === c.reasonCode,
        'BP1_CURRENT_RESULT_BINDING_MISMATCH');
        if (!truncating) {
            requireValue(!legacyNative || ['PUBLISHED_SCOPE_PLUS_PROJECT_RESEARCH_RESTRICTION', 'PUBLISHED_REGION_AND_SPLICE_CONDITIONS'].includes(f.scope)
                && !own(f, 'truncatingConditionCheck'), 'BP1_CURRENT_LEGACY_BRANCH_UNSUPPORTED');
            return { claimed: true, valid: true, handlesDecision: false, issues: [] };
        }
        requireValue(f.policyVersion === '1.2.0' && ['NOT_MET', 'NOT_EVALUATED'].includes(c.status) && c.appliedStrength === 'NONE' && reason(c.reasonCode)
            && f.transcript === `${x.transcript.accession}.${x.transcript.version}`
            && /^(?:ENSP[0-9]+|NP_[0-9]+)\.[1-9][0-9]*$/.test(f.protein || ''), 'BP1_CURRENT_TRUNCATING_RESULT_INVALID');
        const pins = b.observedSourcePins, q = f.truncatingConditionCheck;
        requireValue(object(pins) && pins.specificationKey === f.sourceSpecification && text(pins.specificationKey)
            && /^\/ld\/CriteriaCode\/(?:0|[1-9][0-9]*)$/.test(pins.clausePointer || '') && pins.clausePointer === f.sourceClausePointer
            && ['sourceRawSha256', 'entitySha256', 'clauseSha256', 'knowledgeRawSha256', 'modelCanonicalSha256'].every(k => sha(pins[k]))
            && (!own(pins, 'structureModelSha256') || sha(pins.structureModelSha256)), 'BP1_CURRENT_SOURCE_PINS_INVALID');
        requireValue(object(q) && q.appliedStrength === 'NONE' && q.scientificQualificationGranted === false
            && q.clinicalValidationCompleted === false && q.reasonCode === c.reasonCode && Array.isArray(q.missing)
            && q.missing.length <= 10 && q.required?.geneLofDiseaseCorrelation === 'NO_ESTABLISHED_CORRELATION', 'BP1_CURRENT_CONDITION_REQUIRED');
        if (q.schemaVersion === 'bp1-truncating-applicability-v1') {
            requireValue(c.status === 'NOT_EVALUATED' && q.status === 'NOT_APPLICABLE'
                && q.reasonCode === 'BP1_TRUNCATING_RULE_NOT_APPLICABLE_TO_PURE_MISSENSE'
                && q.required.variantEffect === 'TRUNCATING_WITH_LOSS_OF_FUNCTION' && q.required.diseaseMechanism === 'GAIN_OF_FUNCTION'
                && q.required.germlineOnly === true && q.required.noRecordMeansNoCorrelation === false
                && q.available?.variantEffect === 'VERIFIED_PURE_CODING_MISSENSE' && q.available.modelCanonicalSha256 === pins.modelCanonicalSha256
                && sha(q.available.materialSha256) && q.sourceClause === pins.clauseSha256 && q.missing.length === 0, 'BP1_CURRENT_PURE_MISSENSE_MISMATCH');
        } else {
            requireValue(!legacyNative, 'BP1_CURRENT_LEGACY_TRUNCATING_ASSESSMENT_UNSUPPORTED');
            requireValue(q.schemaVersion === 'bp1-truncating-condition-facts-v1' && q.methodVersion === cap.methodVersion
                && q.status === c.status && q.required.publishedRule === 'PUBLISHED_TRUNCATING_GOF_SUPPORTING_V1'
                && q.required.setting === 'GERMLINE_RASOPATHY'
                && q.required.variantConsequence === (safeNative?'SOURCE_DEFINED_PREDICTED_CODING_TRUNCATION':'SOURCE_QUALIFIED_TRUNCATING_LOF')
                && q.required.missingRecordMeansNoCorrelation === false
                && q.available?.variantConsequence === 'TRUNCATION_CANDIDATE' && q.available.functionalLossEstablishedBySequence === false
                && q.sequenceConsequence?.status === 'TRUNCATION_CANDIDATE' && q.sequenceConsequence.functionalLossEstablishedBySequence === false
                && q.sequenceConsequence.nativeModelSha256 === pins.modelCanonicalSha256 && sha(q.sequenceConsequence.structureMaterialSha256), 'BP1_CURRENT_SEQUENCE_CONDITION_INVALID');
            const source = q.publishedSource, scope = source?.scope;
            requireValue(source?.specificationKey === pins.specificationKey && source.entitySha256 === pins.entitySha256
                && source.clausePointer === pins.clausePointer && source.clauseSha256 === pins.clauseSha256
                && source.sourceRawSha256 === pins.sourceRawSha256 && source.catalogIdentity?.rawSha256 === pins.knowledgeRawSha256
                && scope?.hgncId === x.gene.hgncId && scope.mondoId === x.disease.mondoId && scope.moi === x.moi
                && scope.assembly === allele.assembly && scope.transcript === f.sourceTranscript, 'BP1_CURRENT_PUBLISHED_SCOPE_MISMATCH');
            const correlation = q.lofDiseaseCorrelation;
            requireValue(correlation?.sourceCheckStatus === 'CHECKED' && correlation.sourceBinding?.catalogRawSha256 === pins.knowledgeRawSha256
                && correlation.sourceBinding.sourceRawSha256 === pins.sourceRawSha256 && correlation.sourceBinding.entitySha256 === pins.entitySha256
                && correlation.sourceBinding.clausePointer === pins.clausePointer && correlation.sourceBinding.clauseSha256 === pins.clauseSha256,
            'BP1_CURRENT_CORRELATION_SOURCE_MISMATCH');
            const structured = q.structuredLof;
            if (own(q, 'structuredLof') || own(pins, 'structuredLofSha256')) requireValue(
                c.status === 'NOT_MET' && safeNative && structured?.methodVersion === '1.0.0'
                && structured.status === 'VERIFIED_ESTABLISHED_LOF'
                && structured.contextId === evaluation.contextId && structured.contextSha256 === hash(evaluation)
                && structured.geneSymbol === evaluation.gene.symbol && structured.diseaseLabel === evaluation.disease.label
                && structured.scope?.hgncId === evaluation.gene.hgncId && structured.scope.mondoId === evaluation.disease.mondoId
                && structured.scope.moi === evaluation.moi && structured.scope.assembly === allele.assembly
                && structured.scope.transcript === f.sourceTranscript && structured.modelSha256 === pins.modelCanonicalSha256
                && text(structured.entryId) && ['policySha256', 'bundleSha256', 'adoptionRawSha256',
                    'sourceCandidatesRawSha256', 'mechanismSourceSetSha256'].every(key => sha(structured[key]))
                && Array.isArray(structured.mechanismSources) && structured.mechanismSources.length > 0
                && structured.mechanismSources.length <= 8 && structured.mechanismSources.every(row => text(row.sourceId) && sha(row.sha256))
                && hash(structured) === pins.structuredLofSha256, 'BP1_CURRENT_STRUCTURED_LOF_MISMATCH');
            if (c.status === 'NOT_MET') requireValue(c.reasonCode === 'BP1_GENE_HAS_ESTABLISHED_LOF_DISEASE_CORRELATION'
                && q.available.geneLofDiseaseCorrelation === 'CONTRAINDICATION_ESTABLISHED'
                && q.available.sourceCondition === 'NO_ESTABLISHED_LOF_DISEASE_CORRELATION_CONTRADICTED'
                && (structured ? correlation.status === 'UNKNOWN' : correlation.status === 'CONTRAINDICATION_ESTABLISHED'
                    && correlation.relation?.hgncId === x.gene.hgncId && correlation.relation.molecularMechanism === 'LOSS_OF_FUNCTION'
                    && correlation.sourceMention?.subjectMatchesSelectedScope === true)
                && q.missing.length === 0, 'BP1_CURRENT_CONTRAINDICATION_MISMATCH');
            else {
                const software = safeNative && ['BP1_NO_LOF_LEGACY_PROJECT_TEMPLATE_NOT_SCIENTIFIC_REVIEW',
                    'BP1_NO_LOF_NATIVE_REVIEW_SCOPE_NOT_INTERPRETED','BP1_NO_LOF_ATTACHMENT_INTERPRETATION_UNSUPPORTED'].includes(c.reasonCode);
                const noLof = q.noEstablishedLofQualification;
                requireValue((software || c.reasonCode === 'BP1_NO_ESTABLISHED_LOF_DISEASE_CORRELATION_NOT_VERIFIED')
                && q.available.geneLofDiseaseCorrelation === 'UNKNOWN' && correlation.status === 'UNKNOWN'
                && q.available.sourceCondition === 'UNRESOLVED_NOT_ASSUMED_TRUE'
                && q.missing.some(n => n.conditionId === 'GENE_LOF_DISEASE_CORRELATION'
                    && n.required === 'SOURCE_QUALIFIED_NO_ESTABLISHED_CORRELATION' && reason(n.availableReasonCode)
                    && n.availableReasonCode === (software?c.reasonCode:correlation.reasonCode)), 'BP1_CURRENT_MISSING_QUALIFICATION_MISMATCH');
                if (software) requireValue(noLof?.schemaVersion === 'bp1-no-established-lof-qualification-v1'
                    && noLof.methodVersion === '1.1.0' && noLof.status === 'UNKNOWN' && noLof.reasonCode === c.reasonCode
                    && noLof.sourceCheckStatus === 'CHECKED' && noLof.absenceQualificationGranted === false
                    && noLof.validity?.status === 'NO_SOURCE_EXPIRY_POLICY_ADOPTED'
                    && noLof.claim?.hgncId === x.gene.hgncId && noLof.claim.geneSymbol === x.gene.symbol
                    && noLof.claim.diseaseScope === 'SOURCE_NATIVE_SCOPE_NOT_YET_INTERPRETED'
                    && noLof.claim.inheritanceScope === 'NOT_ASSUMED' && noLof.claim.molecularMechanism === 'NOT_ASSUMED'
                    && noLof.sourceBinding?.catalogRawSha256 === pins.knowledgeRawSha256
                    && ['sourceRawSha256','entitySha256','statementTextSha256'].every(k=>sha(noLof.sourceBinding[k]))
                    && noLof.useBinding?.contextId === evaluation.contextId && noLof.useBinding.contextSha256 === hash(evaluation)
                    && noLof.useBinding.alleleSha256 === hash(allele) && noLof.useBinding.modelCanonicalSha256 === pins.modelCanonicalSha256
                    && noLof.useBinding.structureMaterialSha256 === q.sequenceConsequence.structureMaterialSha256
                    && sha(noLof.useBinding.sequenceObservationSha256), 'BP1_CURRENT_UNSUPPORTED_SOURCE_MISMATCH');
            }
        }
        const softwareUnsupported=safeNative && ['BP1_NO_LOF_LEGACY_PROJECT_TEMPLATE_NOT_SCIENTIFIC_REVIEW',
            'BP1_NO_LOF_NATIVE_REVIEW_SCOPE_NOT_INTERPRETED','BP1_NO_LOF_ATTACHMENT_INTERPRETATION_UNSUPPORTED'].includes(c.reasonCode);
        return { claimed: true, valid: true, handlesDecision: true, issues: [], view: { kind: 'BP1_CURRENT_CONDITIONS',
            conditionStatus: softwareUnsupported?'SOFTWARE_UNSUPPORTED':q.status, status: c.status, required: '需核对适用的截短后果，以及规范要求的“无已建立 LoF 疾病关联”资料资格。',
            available: q.status === 'NOT_APPLICABLE' ? '本次已核为纯编码错义，不符合该截短条款的变异类型。'
                : c.status === 'NOT_MET' ? '已核序列截短；原规范明确记录了当前基因的 LoF 疾病关联。'
                    : softwareUnsupported?'已读取规范与预测编码截短；原生资料的基因、疾病范围解释尚未支持。'
                        : '已核序列截短；现有材料尚未核定当前基因“无已建立 LoF 疾病关联”的资格。',
            difference: q.status === 'NOT_APPLICABLE' ? '该条款不适用；外层未评价及无强度状态保留，不解释为良性。'
                : c.status === 'NOT_MET' ? '这项已知关联与 BP1 所需条件相反，因此本次不能采用此条 BP1。'
                    : softwareUnsupported?'本条证据保留未评价，待原生资料解释能力接通后核查。'
                        : '仍需适用范围明确、可追溯的相关性资格资料；无记录或其他基因的记录不能填为否定。',
            source: `${pins.specificationKey}；${pins.clausePointer} @ ${pins.clauseSha256}`,
            mention: q.lofDiseaseCorrelation?.sourceMention ? { ...q.lofDiseaseCorrelation.sourceMention } : null } };
    } catch (e) {
        return { claimed: true, valid: false, handlesDecision: true, issues: [reason(e?.message) ? e.message : 'BP1_CURRENT_PUBLIC_BINDING_INVALID'] };
    }
}

function verifyRecalculatedPm1(c, f, r, cited, covers, aa) {
    const invalid = 'PM1_REVIEWED_PUBLIC_CALCULATION_INVALID';
    const {survey, observations, assessments, decisionRegionId} = r;
    const roles = ['INDEPENDENT_PATHOGENIC', 'QUALIFIED_BENIGN', 'CONFLICTING', 'UNRESOLVED',
        'EXCLUDED_TARGET', 'EXCLUDED_DIFFERENT_BACKGROUND', 'EXCLUDED_DEPENDENT', 'EXCLUDED_UNCERTAIN'];
    requireValue(covers(survey, survey?.start) && text(survey.dataset) && cited(survey) &&
        ['COMPLETE', 'INCOMPLETE'].includes(survey.status) &&
        Array.isArray(assessments) && assessments.length > 0 && assessments.length <= 16 &&
        Array.isArray(observations) && observations.length <= 512 && observations.every(o =>
            covers(survey, o?.proteinPosition) && positive(o.proteinPosition) &&
            aa(o.refAminoAcid) && aa(o.altAminoAcid) && o.refAminoAcid !== o.altAminoAcid && roles.includes(o.role) &&
            ((o.proteinPosition === f.proteinPosition && o.altAminoAcid === f.altAminoAcid) === (o.role === 'EXCLUDED_TARGET')) &&
            Array.isArray(o.assertions) && o.assertions.length > 0 && o.assertions.length <= 12 &&
            o.assertions.every(a => text(a.assertionId) && text(a.rationale) && cited(a) &&
                ['PATHOGENIC', 'LIKELY_PATHOGENIC', 'BENIGN', 'LIKELY_BENIGN', 'UNCERTAIN_SIGNIFICANCE'].includes(a.classification) &&
                ['MATCHED', 'DIFFERENT', 'UNRESOLVED'].includes(a.diseaseScope) &&
                ['INDEPENDENT', 'DEPENDENT', 'UNVERIFIED'].includes(a.basis))) &&
        new Set(observations.map(o => `${o.refAminoAcid}${o.proteinPosition}${o.altAminoAcid}`)).size === observations.length,
    invalid);
    const qualified = (o, kinds) => o.assertions.some(a =>
        a.basis === 'INDEPENDENT' && a.diseaseScope === 'MATCHED' && kinds.includes(a.classification));
    requireValue(observations.every(o =>
        (o.role !== 'INDEPENDENT_PATHOGENIC' || qualified(o, ['PATHOGENIC', 'LIKELY_PATHOGENIC'])) &&
        (o.role !== 'QUALIFIED_BENIGN' || qualified(o, ['BENIGN', 'LIKELY_BENIGN']))), invalid);

    for (const region of assessments) {
        requireValue(covers(region, f.proteinPosition) && text(region.regionId) && text(region.label) && text(region.rationale) &&
            cited(region) && ['MUTATIONAL_HOTSPOT', 'CRITICAL_FUNCTIONAL_REGION'].includes(region.kind) &&
            ['DOCUMENTED_REGION', 'MISSENSE_ENRICHMENT'].includes(region.method) &&
            Array.isArray(region.issues) && region.issues.every(reason) &&
            ['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(region.status) && reason(region.reasonCode), invalid);
        const count = (role, inside) => observations.filter(o =>
            o.role === role && covers(region, o.proteinPosition) === inside).length;
        const a = count('INDEPENDENT_PATHOGENIC', true), b = count('QUALIFIED_BENIGN', true);
        const d = region.comparisonCounts;
        const complete = survey.status === 'COMPLETE' && covers(survey, region.start) && covers(survey, region.end);
        requireValue(d?.pathogenicInside === a && d.benignInside === b &&
            d.conflictingInside === count('CONFLICTING', true) && d.unresolvedInside === count('UNRESOLVED', true) &&
            d.pathogenicOutside === count('INDEPENDENT_PATHOGENIC', false) && d.benignOutside === count('QUALIFIED_BENIGN', false) &&
            region.surveyCompleteForRegion === complete, invalid);
        if (region.status === 'MET') {
            requireValue(region.reasonCode === 'PM1_REVIEWED_REGION_MATCH' && complete && a > 0 && b === 0 &&
                !d.conflictingInside && !d.unresolvedInside && region.issues.length === 0, invalid);
            if (region.method === 'DOCUMENTED_REGION') {
                requireValue(region.established === true && region.appliedStrength === 'MODERATE', invalid);
            } else {
                const calibration = region.calibration;
                const level = calibration?.levels?.find(v => v.strength === region.appliedStrength);
                const pValue = Number(region.enrichment?.pValue);
                const odds = region.enrichment?.oddsRatio === 'INFINITE' ? Infinity : Number(region.enrichment?.oddsRatio);
                requireValue(calibration?.method === 'FISHER_EXACT_ONE_SIDED' && text(calibration.version) && cited(calibration) &&
                    region.enrichment?.method === calibration.method && ['SUPPORTING', 'MODERATE'].includes(region.appliedStrength) &&
                    level && positive(level.minimumIndependentPathogenic) && a >= level.minimumIndependentPathogenic &&
                    text(level.minimumOddsRatio) && odds >= Number(level.minimumOddsRatio) &&
                    text(level.maximumPValue) && Number.isFinite(pValue) && pValue > 0 && pValue <= Number(level.maximumPValue), invalid);
            }
        } else {
            requireValue(region.appliedStrength === 'NONE' && (region.reasonCode === region.issues[0] ||
                region.status === 'NOT_MET' && region.reasonCode === 'PM1_REGION_ENRICHMENT_BELOW_THRESHOLD' &&
                region.method === 'MISSENSE_ENRICHMENT' && complete && region.issues.length === 0), invalid);
        }
    }
    const selected = assessments.find(region => region.regionId === decisionRegionId);
    requireValue(new Set(assessments.map(region => region.regionId)).size === assessments.length &&
        selected?.status === c.status && selected.reasonCode === c.reasonCode && selected.appliedStrength === c.appliedStrength &&
        assessments.every(region => region.status === 'MET' ? c.status === 'MET' && rank[region.appliedStrength] <= rank[c.appliedStrength]
            : region.status !== 'NOT_EVALUATED' || c.status !== 'NOT_MET'), invalid);
}

function verifyReviewedPm1Region(c,f,expected,hash) {
    const binding=expectedBinding(c,f,expected,hash);
    const r=f.reviewedRegion,x=expected.context;
    const tx=`${x.transcript.accession}.${x.transcript.version}`;
    requireValue(f.schemaVersion==='pm1-general-facts-v1' && f.policyVersion==='1.0.0' &&
        f.scope==='PROJECT_RESEARCH_REVIEWED_REGIONS' && f.qualificationBasis==='REVIEWED_REGIONAL_EVIDENCE' &&
        f.clinicalValidationCompleted===false && f.benignCountsInferred===false &&
        f.automaticNegativeFromNoHit===false && !own(f,'publicProjectionIssue') &&
        ['pm1-reviewed-regions-v1','pm1-reviewed-regions-v2'].includes(r?.schemaVersion) && sha(r.catalogSha256) && sha(r.releaseCatalogSha256) &&
        binding.reviewedRegionSha256===hash(r), 'PM1_REVIEWED_PUBLIC_BINDING_INVALID');
    const aa=value=>/^[ACDEFGHIKLMNPQRSTVWY]$/.test(value || '');
    requireValue(r.scope?.hgncId===x.gene.hgncId && r.scope.mondoId===x.disease.mondoId && r.scope.moi===x.moi &&
        r.scope.assembly===x.variant.evidenceQueryAllele.assembly && r.scope.transcript===tx &&
        r.scope.protein===f.protein && /^(?:ENSP\d+|NP_\d+)\.[1-9]\d*$/.test(f.protein || '') &&
        f.transcript===tx && f.executionTranscript===tx && positive(f.proteinPosition) &&
        x.variant.evidenceQueryAllele.reference.length===1 && x.variant.evidenceQueryAllele.alternate.length===1 &&
        aa(f.refAminoAcid) && aa(f.altAminoAcid) && f.refAminoAcid!==f.altAminoAcid, 'PM1_REVIEWED_PUBLIC_SCOPE_MISMATCH');
    const sources=r.sources || [];
    requireValue(Array.isArray(sources) && sources.length<=64 && sources.every(s=>text(s?.sourceId) && text(s.title) &&
        text(s.locator) && text(s.url) && /^https:\/\/[^\s]+$/.test(s.url)), 'PM1_REVIEWED_PUBLIC_SOURCES_INVALID');
    const ids=new Set(sources.map(s=>s.sourceId));
    const cited=item=>Array.isArray(item?.sourceIds) && item.sourceIds.length>0 && item.sourceIds.every(id=>ids.has(id));
    const covers=(row,position)=>positive(row?.start) && positive(row?.end) && row.start<=position && row.end>=position;
    if (r.schemaVersion === 'pm1-reviewed-regions-v2') {
        requireValue(!own(r, 'region'), 'PM1_REVIEWED_PUBLIC_CALCULATION_INVALID');
        if (own(r, 'assessments')) {
            verifyRecalculatedPm1(c, f, r, cited, covers, aa);
            return;
        }
    }
    const noStrength=c.appliedStrength==='NONE';
    const region=r.region;
    if (!region) {
        const excluded=c.status==='NOT_MET' && c.reasonCode==='PM1_REVIEWED_REGION_OUTSIDE_QUALIFIED' &&
            covers(r.reviewedCoverage,f.proteinPosition) && cited(r.reviewedCoverage);
        const unresolved=c.status==='NOT_EVALUATED' && ['PM1_REVIEWED_REGION_POSITION_UNCOVERED',
            'PM1_REVIEWED_REGION_OVERLAP'].includes(c.reasonCode);
        requireValue(noStrength && (excluded || unresolved),'PM1_REVIEWED_PUBLIC_OUTCOME_INVALID');
        return;
    }
    requireValue(covers(region,f.proteinPosition) && text(region.regionId) && text(region.label) && text(region.reviewRationale) &&
        ['MUTATIONAL_HOTSPOT','CRITICAL_FUNCTIONAL_REGION'].includes(region.kind) &&
        ['QUALIFIED','NOT_QUALIFIED','PENDING'].includes(region.reviewStatus) &&
        ['COMPLETE','INCOMPLETE'].includes(region.benignSurvey) && cited(region) &&
        Array.isArray(region.observations) && region.observations.length<=200 &&
        region.observations.every(o=>covers(region,o?.proteinPosition) && positive(o.proteinPosition) &&
            aa(o.refAminoAcid) && aa(o.altAminoAcid) && o.refAminoAcid!==o.altAminoAcid && cited(o) &&
            ['INDEPENDENT_PATHOGENIC','QUALIFIED_BENIGN','CONFLICTING','UNRESOLVED'].includes(o.role)),
    'PM1_REVIEWED_PUBLIC_REGION_INVALID');
    const independent=region.observations.filter(o=>o.role==='INDEPENDENT_PATHOGENIC' &&
        (o.proteinPosition!==f.proteinPosition || o.altAminoAcid!==f.altAminoAcid)).length;
    requireValue(new Set(region.observations.map(o=>`${o.refAminoAcid}${o.proteinPosition}${o.altAminoAcid}`)).size===region.observations.length &&
        independent===r.independentPathogenicCount,'PM1_REVIEWED_PUBLIC_OBSERVATIONS_INVALID');
    const benign=region.observations.some(o=>o.role==='QUALIFIED_BENIGN');
    const unresolved=region.observations.some(o=>['CONFLICTING','UNRESOLVED'].includes(o.role));
    if (c.status==='MET') {
        requireValue(c.reasonCode==='PM1_REVIEWED_REGION_MATCH' && !benign && !unresolved && independent>0 &&
            region.reviewStatus==='QUALIFIED' && region.benignSurvey==='COMPLETE' &&
            ['MODERATE','SUPPORTING'].includes(region.strength) && c.appliedStrength===region.strength,
        'PM1_REVIEWED_PUBLIC_OUTCOME_INVALID');
    } else if (c.status==='NOT_MET') {
        requireValue(noStrength && (benign ? c.reasonCode==='PM1_REVIEWED_REGION_BENIGN_OBSERVATION' :
            !unresolved && region.reviewStatus==='NOT_QUALIFIED' && c.reasonCode==='PM1_REVIEWED_REGION_NOT_QUALIFIED'),
        'PM1_REVIEWED_PUBLIC_OUTCOME_INVALID');
    } else requireValue(c.status==='NOT_EVALUATED' && noStrength && !benign &&
        ['PM1_REVIEWED_REGION_OBSERVATION_UNRESOLVED','PM1_REVIEWED_REGION_REVIEW_PENDING',
            'PM1_REVIEWED_REGION_OBSERVATION_INCOMPLETE','PM1_REVIEWED_REGION_BENIGN_SURVEY_INCOMPLETE',
            'PM1_REVIEWED_REGION_INDEPENDENT_OBSERVATION_MISSING'].includes(c.reasonCode),
    'PM1_REVIEWED_PUBLIC_OUTCOME_INVALID');
}

export function inspectPublishedConditionCriterion(c, expected, { identityHash, profile } = {}) {
    if (c?.criterionId === 'ACMG_AMP_2015:BP1') return inspectBp1(c, expected, identityHash, profile);
    if (c?.criterionId !== PM1) return { claimed: false, valid: true, issues: [] };
    const f = c.details?.pm1Facts;
    const cap = (expected?.executionProfile || profile)?.moduleBindings?.pm1?.runtimeBinding?.pm1ConditionalPeerExecution;
    const claimed = own(f, 'currentEvaluationBinding') || own(f, 'publicProjectionIssue')
        || cap?.schemaVersion === 'pm1-current-peer-execution-v1' && c.status !== 'NOT_EVALUATED';
    if (!claimed) return { claimed: false, valid: true, issues: [] };
    try {
        if (own(f,'reviewedRegion')) {
            verifyReviewedPm1Region(c,f,expected,identityHash);
            return { claimed: true, valid: true, issues: [] };
        }
        requireValue(f?.schemaVersion === 'pm1-general-facts-v1' && f.policyVersion === '1.0.0'
            && f.scope === 'PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS' && f.clinicalValidationCompleted === false
            && f.qualificationBasis === 'PUBLISHED_EXPERT_SPECIFICATION' && f.benignCountsInferred === false
            && f.automaticNegativeFromNoHit === false && !own(f, 'publicProjectionIssue'), 'PM1_CURRENT_PUBLIC_PROJECTION_INVALID');
        requireValue(['MET', 'NOT_MET', 'NOT_EVALUATED'].includes(c.status) && reason(c.reasonCode)
            && (c.status === 'MET' ? ['MODERATE', 'SUPPORTING'].includes(c.appliedStrength) : c.appliedStrength === 'NONE'), 'PM1_CURRENT_FINAL_RESULT_INVALID');
        const binding = expectedBinding(c, f, expected, identityHash);
        if (f.transcript) requireValue(f.transcript === `${expected.context.transcript.accession}.${expected.context.transcript.version}`, 'PM1_CURRENT_TRANSCRIPT_MISMATCH');
        const rule = c.status === 'NOT_EVALUATED' && !f.selectedRule ? null : validRule(f, c);
        if (c.status !== 'NOT_EVALUATED') requireValue(f.sourceDisposition === 'EXECUTABLE', 'PM1_CURRENT_SOURCE_DISPOSITION_INVALID');
        if (own(f, 'crossCriterionRelations') && c.status !== 'NOT_EVALUATED') requireValue(Array.isArray(f.crossCriterionRelations)
            && f.crossCriterionRelations.length <= 64 && f.crossCriterionRelations.every(r => relationValid(r) || nonexclusive(r))
            && f.crossCriterionRelations.every(r => r.sourcePointer !== rule?.sourcePointer || r.sourceSha256 === rule.sourceSha256), 'PM1_CURRENT_PEER_DECLARATION_INVALID');
        const hasPeers = Array.isArray(f.crossCriterionRelations) && f.crossCriterionRelations.some(r => !nonexclusive(r));
        const peer = rule?.effect !== 'EXCLUDE' && hasPeers && (own(f, 'crossCriterionConditionCheck') || c.status !== 'NOT_EVALUATED')
            ? peerChecks(f, binding, expected, identityHash) : null;
        if (c.status === 'MET') requireValue(rule?.effect === 'SUPPORT' && c.appliedStrength === rule.strength
            && c.reasonCode === 'PM1_PUBLISHED_CRITICAL_LOCATION_MATCH' && (!peer || peer.status === 'READY'), 'PM1_CURRENT_MET_CONDITIONS_MISMATCH');
        else if (c.status === 'NOT_MET' && rule?.effect === 'SUPPORT') requireValue(peer?.status === 'NOT_MET'
            && c.reasonCode === peer.reasonCode, 'PM1_CURRENT_NOT_MET_CONDITIONS_MISMATCH');
        else if (c.status === 'NOT_EVALUATED' && c.reasonCode === 'PM1_PUBLISHED_PEER_RESULT_UNAVAILABLE')
            requireValue(rule?.effect === 'SUPPORT' && peer?.status === 'NOT_EVALUATED', 'PM1_CURRENT_UNAVAILABLE_CONDITIONS_MISMATCH');
        return { claimed: true, valid: true, issues: [], view: { kind: 'PM1_CURRENT_CONDITIONS', status: c.status,
            reasonCode: c.reasonCode, source: expected.executionSourceSha256, peers: peer?.peers || [] } };
    } catch (e) {
        return { claimed: true, valid: false, issues: [reason(e?.message) ? e.message : 'PM1_CURRENT_PUBLIC_BINDING_INVALID'] };
    }
}

export function publishedConditionDetails(inspection) {
    if (!inspection?.valid || !inspection.view) return [];
    const v = inspection.view;
    if (v.kind === 'BP1_CURRENT_CONDITIONS') return [['BP1条款：需要的信息', v.required], ['BP1条款：实际已有', v.available],
        ['BP1条款：当前差异', v.difference], ['BP1条款出处', v.source],
        ...(v.mention ? [['原件主体与本次背景', `原件提到 ${v.mention.namedGeneSymbol || '未记录'}；当前背景 ${v.mention.selectedGeneSymbol || '未记录'}；${v.mention.subjectMatchesSelectedScope ? '主体相同' : '主体不同，不把其他基因的关联搬到本次背景'}`]] : []),
        ['采用边界', '本项仅核对已保存结果与调用背景的一致性；序列截短不自动证明功能丢失，未补造科学或临床资格。']];
    return [['本次条件与保存身份', '已对照当前保存运行的候选、背景、等位与来源身份；这是显示一致性检查，不是来源或临床资格批准。'],
        ...v.peers.map(p => ['依赖条件：需要 / 已有 / 差异', `${p.criterionId.split(':').pop()} ${p.relation === 'REQUIRE_MET_AT_LEAST' ? '须满足至少' : '满足以下强度时排除 PM1'} ${p.minimumStrength}；${p.available ? `本次最终结果 ${p.available.status} / ${p.available.strength}；${p.available.reasonCode}` : '尚无可用的同背景最终结果'}；${({ MET: '此条件已核对', NOT_MET: '此条件阻止本次采用 PM1', UNAVAILABLE: '条件仍缺最终资料，不能视为否定' })[p.status]}`]),
        ...v.peers.map(p => ['条件出处与最终结果定位', `${p.sourcePointer} @ ${p.sourceSha256}${p.available ? `；${p.available.modulePointer}${p.available.evaluationPointer}` : ''}`])];
}
