// Verify saved public transport. Source acquisition and scientific qualification remain on the backend.
import { publishedConditionCallerContext } from './acmgPublishedConditionProof';

const TYPE='PP2_PUBLISHED_HCM_ENRICHMENT_WITH_PHENOTYPE_EXCLUSION_V1';
const SCOPE='PROJECT_RESEARCH_PUBLISHED_HCM_ENRICHMENT';
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
const sha=value=>typeof value==='string'&&/^[a-f0-9]{64}$/.test(value);
const tx=value=>typeof value==='string'&&/^(?:ENST[0-9]+|NM_[0-9]+)\.[1-9][0-9]*$/.test(value);
const count=value=>Number.isSafeInteger(value)&&value>=0;
const sourceVersions=new Set(['pp2-other-phenotype-source-v1','pp2-other-phenotype-source-v2','pp2-other-phenotype-source-v3']);
const boundedQualification='pp2-bounded-literature-qualification-v1';
const requireValue=value=>{if(!value)throw new Error('PP2_HCM_SAVED_TRANSPORT_INCONSISTENT');};

export const pp2HcmClaimed=facts=>facts?.scope===SCOPE||facts?.method===TYPE;

function completeLiteratureScope(scope,review,cap) {
    const candidates=scope.candidatePapers,citations=scope.knownCitationReferences,summary=review.literatureAssessment;
    if(scope.aliasIdentityVerified!==true||!sha(scope.retrievalLedgerSha256)||!count(candidates)||!Array.isArray(citations))return false;
    // The old zero-paper route is only for an actually empty scope, not a downgrade of a saved review.
    if(candidates===0&&citations.length===0)return !summary?.qualificationVersion&&(summary?.papersInScope??0)===0;
    return object(summary)&&cap.sourceCapability?.qualificationVersion===boundedQualification
        &&scope.qualificationVersion===boundedQualification&&summary.qualificationVersion===boundedQualification
        &&scope.retrievalScopeComplete===true&&scope.qualificationScopeStatus==='COMPLETE_DECLARED_SCOPE'
        &&summary.qualificationScopeStatus===scope.qualificationScopeStatus&&summary.scopeStatus==='COMPLETED_DECLARED_REVIEWED_SCOPE'
        &&summary.methodVersion==='pp2-literature-assessment-v1'&&summary.readingMethodVersion==='1.2.0'
        &&summary.readingScopeMeaning==='DECLARED_AVAILABLE_MAIN_AND_PARSED_ATTACHMENT_TEXT_UNITS_ONLY'&&summary.wholePublicationRead===false
        &&summary.outcome===review.outcome&&summary.reasonCode===review.reasonCode
        &&summary.declaredCandidateCount===candidates&&summary.declaredKnownCitationCount===citations.length
        &&count(summary.papersInScope)&&summary.papersInScope>0&&summary.papersInScope<=candidates+citations.length
        &&summary.assessedPapers===summary.papersInScope&&scope.assessedPapers===summary.assessedPapers
        &&summary.unresolvedRecords===0&&summary.unresolvedUnits===0&&summary.unresolvedCitationCount===0
        &&sha(summary.assessmentSha256)&&summary.assessmentSha256===review.binding.derivedAssessmentSha256
        &&sha(summary.readingLedgerRawSha256)&&sha(summary.qualificationLedgerRawSha256);
}

export function pp2HcmCriterionValid(criterion,facts,profile,expected,identityHash) {
    try {
        requireValue(facts.scope===SCOPE&&facts.method===TYPE&&facts.methodVersion==='1.0.0'
            &&facts.constraintScoresRecalculated===false&&facts.createsReviewReceipt===false&&typeof facts.germlineDeclared==='boolean');
        const cap=profile?.moduleBindings?.pp2?.runtimeBinding?.pp2HcmExecution;
        if(cap)requireValue(cap.methodVersion==='1.0.0'&&cap.transcriptScopeVersion==='1.0.0');
        const review=facts.otherPhenotypeReview,method=facts.hcmSourceMethod;
        // A missing dependency is a saved NE, not permission to adopt or a different clinical result.
        if(!review)return criterion.status==='NOT_EVALUATED'&&criterion.appliedStrength==='NONE';
        requireValue(object(method)&&method.status==='SOURCE_METHOD_RECOGNISED'&&object(facts.hcmPolicyBinding)
            &&review.schemaVersion==='pp2-other-phenotype-review-v1'&&object(review.binding)
            &&Array.isArray(review.requiredScope)&&Array.isArray(review.qualifiedEvidence)
            &&Array.isArray(review.pending)&&Array.isArray(review.conflicts));
        const binding=facts.hcmPolicyBinding,source=method.sourceBinding,ruleScope=method.scope;
        requireValue(binding.schemaVersion==='pp2-hcm-policy-binding-v1'&&binding.methodVersion==='1.0.0'
            &&binding.criterionId==='ACMG_AMP_2015:PP2'&&binding.declaredTranscript===facts.declaredTranscript
            &&binding.executionTranscript===facts.executionTranscript&&tx(facts.declaredTranscript)&&tx(facts.executionTranscript)
            &&sha(binding.sourceInterpretationSha256)&&sha(binding.preparedContextSha256)&&sha(binding.executionContextSha256)
            &&sha(binding.executionModelSha256)&&sha(binding.alleleAssociationSha256)&&object(source)&&object(ruleScope)
            &&source.specificationKey===facts.specificationKey&&/^GN\d+@[\d.]+$/.test(source.specificationKey||'')
            &&sha(source.entitySha256)&&sha(source.clauseSha256)&&sha(source.sourceRawSha256)
            &&/^\/ld\/CriteriaCode\/(?:0|[1-9][0-9]*)$/.test(source.clausePointer||'')
            &&/^\/ld\/RuleSet\/\d+\/entContent\/genes\/\d+\/diseases\/\d+$/.test(source.scopePointer||'')
            &&identityHash(source)===identityHash(binding.sourceBinding));
        const {original,evaluation,allele}=publishedConditionCallerContext(expected,identityHash,'ACMG_AMP_2015:PP2');
        const execution=facts.transcriptPreparation?.executionContext||evaluation;
        requireValue(review.binding.candidateId===expected.candidateId&&review.binding.hypothesisId===expected.hypothesisId
            &&review.binding.contextSha256===binding.preparedContextSha256&&binding.preparedContextSha256===identityHash(evaluation)
            &&binding.executionContextSha256===identityHash(execution)
            &&`${execution.transcript?.accession}.${execution.transcript?.version}`===facts.executionTranscript
            &&identityHash(allele)===identityHash(review.binding.allele)
            &&review.binding.hcmPolicyBindingSha256===identityHash(binding)&&sha(review.binding.sourceSnapshotSha256)
            &&ruleScope.geneSymbol===original.gene.symbol&&ruleScope.hgncId===original.gene.hgncId
            &&ruleScope.mondoId===original.disease.mondoId&&ruleScope.moi===original.moi
            &&binding.scope?.hgncId===ruleScope.hgncId&&binding.scope?.mondoId===ruleScope.mondoId
            &&binding.scope?.moi===ruleScope.moi&&binding.scope?.assembly===allele.assembly
            &&binding.scope?.transcript===facts.declaredTranscript&&ruleScope.preferredTranscript===facts.declaredTranscript);
        const observation=expected.pp2OtherPhenotypeSourceIdentity;
        requireValue(object(observation)&&observation.observationSha256===review.binding.sourceSnapshotSha256
            &&sourceVersions.has(review.methodVersion)&&observation.methodVersion===review.methodVersion
            &&(!cap||cap.sourceVersion===review.methodVersion));
        if(facts.sourceTranscriptScope){
            const s=facts.sourceTranscriptScope;
            requireValue(s.schemaVersion==='source-declared-unversioned-transcript-scope-v1'&&s.methodVersion==='1.0.0'
                &&s.mode==='EXPLICIT_UNVERSIONED_SOURCE_SCOPE'&&s.sourceAccession===ruleScope.unversionedSourceEnsembl
                &&s.executionTranscript===facts.executionTranscript&&s.preferredTranscript===facts.declaredTranscript
                &&s.executionModelSha256===binding.executionModelSha256&&s.sourceClauseSha256===source.clauseSha256
                &&s.sourceEntitySha256===source.entitySha256&&s.refseqEquivalenceGranted===false&&s.scientificAdoptionGranted===false
                &&identityHash(s)===identityHash(binding.sourceTranscriptScope));
        }
        if(criterion.status==='NOT_EVALUATED')return criterion.appliedStrength==='NONE'&&
            ({UNKNOWN:'PP2_HCM_OTHER_PHENOTYPE_REVIEW_INCOMPLETE',CONFLICT:'PP2_HCM_OTHER_PHENOTYPE_CONFLICT',INVALID:'PP2_HCM_OTHER_PHENOTYPE_REVIEW_INVALID'})[review.outcome]===criterion.reasonCode;
        requireValue(facts.germlineDeclared===true&&facts.knowledgeStatus==='READY'
            &&(!profile||profile.moduleBindings?.pp2?.runtimeBinding?.pp2GermlineDeclared===true)&&review.conflicts.length===0);
        if(criterion.status==='NOT_MET')return criterion.appliedStrength==='NONE'&&criterion.reasonCode==='PP2_HCM_OTHER_PHENOTYPE_SUPPORTED'
            &&review.outcome==='SUPPORTS_OTHER_PHENOTYPE'&&review.qualifiedEvidence.length>0;
        requireValue(criterion.status==='MET'&&criterion.appliedStrength==='SUPPORTING'
            &&review.methodVersion==='pp2-other-phenotype-source-v3'
            &&object(cap)&&cap.sourceConfigured===true&&cap.lifecycleVersion==='pp2-hcm-request-observation-v1'
            &&cap.sourceCapability?.methodVersion===review.methodVersion
            &&criterion.reasonCode==='PP2_HCM_NO_OTHER_PHENOTYPE_SUPPORT_IN_ASSESSED_SCOPE'
            &&review.outcome==='NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE'&&review.pending.length===0&&review.qualifiedEvidence.length===0
            &&review.requiredScope.length===2
            &&review.requiredScope.every(item=>object(item)&&item.status==='COMPLETED_DECLARED_TARGET_SCOPE'));
        for(const scope of review.requiredScope){
            if(scope.scope==='EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS'){
                const coverage=scope.nativeSourceCoverage;
                requireValue(object(coverage)&&coverage.status==='COMPLETE'&&count(coverage.sourceRows)
                    &&coverage.pendingRows===0&&sha(coverage.sourceLedgerSha256)
                    &&count(scope.targetRecords)&&count(scope.qualifiedRecords)&&scope.targetRecords===scope.qualifiedRecords);
            }
            if(scope.scope==='TARGET_LITERATURE_RELATIONSHIP_REVIEW')requireValue(completeLiteratureScope(scope,review,cap));
        }
        return ['EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS','TARGET_LITERATURE_RELATIONSHIP_REVIEW'].every(scope=>
            review.requiredScope.filter(item=>item.scope===scope).length===1);
    } catch { return false; }
}
