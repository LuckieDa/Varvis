package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;

/** Catalog suggestions are not patient diagnoses. Only score-free resource identities enter this policy. */
public final class AcmgRecommendations {
    public static final String POLICY_ID = "ORDINARY_CATALOG_AND_AM_IDENTITY";
    public static final String VERSION = "1.13.0-all-branches.1";
    public static final String PM2_CONTEXT_SELECTION = "PM2_ONLY_VERIFIED_CLINICAL_ANNOTATION@1.0.0";
    public static final String FREQUENCY_CONTEXT_SELECTION = "HNF1A_FREQUENCY_VERIFIED_CLINICAL_ANNOTATION@1.0.0";
    public static final String BA1_CONTEXT_SELECTION = "BA1_DISEASE_CONTEXT_CONFIRMED@1.0.0";
    public static boolean populationOnlySelection(String value) {
        return PM2_CONTEXT_SELECTION.equals(value) || FREQUENCY_CONTEXT_SELECTION.equals(value) || BA1_CONTEXT_SELECTION.equals(value);
    }
    private final ObjectMapper mapper;
    private final AcmgBusinessArtifacts identities;
    private final Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup;
    private final Clock clock;
    private final ObjectNode policy;
    private final ReviewedRuleContextPreparation ruleContexts;
    private final ReviewedPredictionContextPreparation predictionContexts;
    private final Ba1SpecificationCatalog ba1Catalog;
    private final boolean populationOnly;
    private List<JsonNode> bs1Policies = List.of();
    @org.springframework.lang.Nullable private Bs1KnowledgeCatalog bs1Knowledge;
    private boolean predictionOnly;
    private boolean localInterpretationContexts;
    AcmgRecommendations withBp1(ReviewedRuleBundle bundle) {
        var copy = new AcmgRecommendations(mapper, lookup, clock,
                new ReviewedRuleContextPreparation(mapper, bundle.identity()), null, null, true);
        copy.policy.remove(List.of("pm2ProfileSha256", "pm2ContextSelection"));
        copy.policy.put("policyVersion", "1.0.0-bp1-missense-development").put("requiredDeclaration", AcmgBusinessRuleScope.BP1_DECLARATION);
        return copy;
    }
    AcmgRecommendations withPp2(ReviewedRuleBundle bundle) {
        var copy = new AcmgRecommendations(mapper, lookup, clock,
                new ReviewedRuleContextPreparation(mapper, bundle.identity()), null, null, true);
        copy.policy.remove(List.of("pm2ProfileSha256", "pm2ContextSelection"));
        copy.policy.put("policyVersion", "1.0.0-pp2-missense-development").put("requiredDeclaration", AcmgBusinessRuleScope.PP2_DECLARATION);
        return copy;
    }
    AcmgRecommendations withBp3(ReviewedRuleBundle bundle) {
        var copy = new AcmgRecommendations(mapper, lookup, clock,
                new ReviewedRuleContextPreparation(mapper, bundle.identity()), null, null, true);
        copy.policy.remove(List.of("pm2ProfileSha256", "pm2ContextSelection"));
        copy.policy.put("policyVersion", "1.0.0-bp3-inframe-development").put("requiredDeclaration", AcmgBusinessRuleScope.BP3_DECLARATION);
        return copy;
    }
    AcmgRecommendations withPvs1(ReviewedRuleBundle bundle) {
        var copy = new AcmgRecommendations(mapper, lookup, clock,
                new ReviewedRuleContextPreparation(mapper, bundle.identity()), null, null, true);
        copy.policy.remove(List.of("pm2ProfileSha256", "pm2ContextSelection"));
        copy.policy.put("policyVersion", "1.0.0-pvs1-nmd-development").put("requiredDeclaration", AcmgBusinessRuleScope.PVS1_DECLARATION);
        return copy;
    }
    AcmgRecommendations withPm1(ReviewedRuleBundle bundle) {
        var copy = new AcmgRecommendations(mapper, lookup, clock,
                new ReviewedRuleContextPreparation(mapper, bundle.identity()), null, null, true);
        copy.policy.remove(List.of("pm2ProfileSha256", "pm2ContextSelection"));
        copy.policy.put("policyVersion", "1.0.0-pm1-region-development").put("requiredDeclaration", AcmgBusinessRuleScope.PM1_DECLARATION);
        return copy;
    }
    AcmgRecommendations withMissense(ReviewedRuleBundle bundle) {
        var copy = new AcmgRecommendations(mapper, lookup, clock,
                new ReviewedRuleContextPreparation(mapper, bundle.identity()), null, null, true);
        copy.policy.remove(List.of("pm2ProfileSha256", "pm2ContextSelection"));
        copy.policy.put("policyVersion", bundle.supportsPm5() ? "1.0.0-pm5-missense-development" : "1.0.0-ps1-missense-development")
                .put("requiredDeclaration", bundle.supportsPm5() ? AcmgBusinessRuleScope.PM5_DECLARATION : AcmgBusinessRuleScope.PS1_DECLARATION);
        return copy;
    }
    AcmgRecommendations withPm4(ReviewedRuleBundle bundle) {
        var copy = new AcmgRecommendations(mapper, lookup, clock,
                new ReviewedRuleContextPreparation(mapper, bundle.identity()), null, null, true);
        copy.policy.remove(List.of("pm2ProfileSha256", "pm2ContextSelection"));
        copy.policy.put("policyVersion", "1.0.0-pm4-inframe-development").put("requiredDeclaration", Pm4CriterionEvaluator.DECLARATION);
        return copy;
    }
    AcmgRecommendations withLocalBatch(JsonNode binding) {
        var copy = new AcmgRecommendations(mapper, lookup, clock, null, null, null, true);
        copy.predictionOnly = true;
        copy.localInterpretationContexts = true;
        copy.policy.put("interpretationContextPolicyVersion", LocalInterpretationContextPolicy.VERSION);
        copy.policy.remove(List.of("pm2ProfileSha256", "pm2ContextSelection"));
        copy.policy.put("policyVersion", "1.1.0-local-batch1")
                .put("requiredDeclaration", AcmgBusinessRuleScope.LOCAL_BATCH1_DECLARATION);
        copy.policy.set("localBatchBinding", binding.deepCopy());
        return copy;
    }
    AcmgRecommendations withUnifiedLocal(JsonNode binding) {
        var copy = new AcmgRecommendations(mapper, lookup, clock, null, null, ba1Catalog, true);
        copy.localInterpretationContexts = true;
        copy.policy.put("interpretationContextPolicyVersion", LocalInterpretationContextPolicy.VERSION);
        copy.bs1Policies = bs1Policies.stream().<JsonNode>map(mappedItem -> mappedItem.deepCopy()).toList();
        copy.bs1Knowledge = bs1Knowledge;
        copy.policy.put("policyVersion", AcmgExecutionProfile.publishedLocal(binding) ? "1.0.0-unified-local-fourteen" : AcmgExecutionProfile.extendedLocal(binding) ? "1.0.0-unified-local-eleven" : "1.0.0-unified-local-eight")
                .put("requiredDeclaration", AcmgExecutionProfile.unifiedDeclaration(binding))
                .put("bs1PoliciesSha256", identities.requestHash(mapper.valueToTree(copy.bs1Policies)));
        copy.policy.set("localBatchBinding", binding.deepCopy());
        if (AcmgExecutionProfile.publishedLocal(binding)) copy.policy.put("pp2GermlineDeclared", binding.path("pp2GermlineDeclared").asBoolean());
        if (bs1Knowledge != null) copy.policy.set("bs1Knowledge", bs1Knowledge.identity());
        return copy;
    }
    AcmgRecommendations withPrediction(JsonNode binding) {
        return withPrediction(binding, PredictionDevelopmentRuntime.DECLARATION);
    }
    AcmgRecommendations withPrediction(JsonNode binding, String declarationVersion) {
        var copy = new AcmgRecommendations(mapper, lookup, clock, null,
                new ReviewedPredictionContextPreparation(mapper, binding), null, true);
        copy.predictionOnly = true;
        copy.policy.remove(List.of("pm2ProfileSha256", "pm2ContextSelection"));
        copy.policy.put("policyVersion", SavedPredictionAssociation.DECLARATION.equals(declarationVersion)
                ? "3.1.0-saved-prediction-association" : "3.0.0-prediction-core-development").put("requiredDeclaration", declarationVersion);
        return copy;
    }

    AcmgRecommendations withBs1(List<JsonNode> policies, boolean formal) {
        var copy = new AcmgRecommendations(mapper, lookup, clock, ruleContexts, predictionContexts, ba1Catalog, true);
        copy.bs1Policies = policies.stream().<JsonNode>map(value -> Objects.requireNonNull(value).deepCopy()).toList();
        copy.policy.put("policyVersion", formal ? "4.0.0-bs1-formal" : "3.0.0-bs1-acceptance")
                .put("bs1PoliciesSha256", identities.requestHash(mapper.valueToTree(copy.bs1Policies)))
                .put("requiredDeclaration", AcmgBusinessRuleScope.BS1_DECLARATION);
        if (!policies.isEmpty() && policies.stream().allMatch(p -> "LOCAL_EXOMES_BUSINESS_V1".equals(p.path("activation").asText())))
            copy.policy.put("policyVersion", "4.1.0-bs1-local-exomes");
        return copy;
    }
    AcmgRecommendations withBs1(Bs1Runtime runtime, boolean formal) {
        var copy = withBs1(runtime.policyRecords(), formal);
        var knowledge = runtime.knowledgeCatalog();
        copy.bs1Knowledge = knowledge;
        if (knowledge != null) {
            copy.policy.put("policyVersion", "4.2.0-bs1-local-knowledge");
            copy.policy.set("bs1Knowledge", knowledge.identity());
        }
        return copy;
    }
    public static AcmgRecommendations current(ObjectMapper mapper, Ba1SpecificationCatalog catalog) {
        return new AcmgRecommendations(mapper, candidate -> { throw new IllegalStateException("DISABLED_IDENTITY_LOOKUP"); },
                Clock.systemUTC(), null, null, catalog, true);
    }

    public AcmgRecommendations(ObjectMapper mapper, Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup) {
        this(mapper, lookup, Clock.systemUTC());
    }
    AcmgRecommendations(ObjectMapper mapper, Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup, Clock clock) {
        this(mapper, lookup, clock, null);
    }
    public AcmgRecommendations(ObjectMapper mapper, Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup,
                               ReviewedRuleContextPreparation ruleContexts) {
        this(mapper, lookup, Clock.systemUTC(), ruleContexts);
    }
    AcmgRecommendations(ObjectMapper mapper, Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup,
                        Clock clock, ReviewedRuleContextPreparation ruleContexts) {
        this(mapper, lookup, clock, ruleContexts, null);
    }
    public AcmgRecommendations(ObjectMapper mapper, Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup,
            ReviewedRuleContextPreparation ruleContexts, ReviewedPredictionContextPreparation predictionContexts) {
        this(mapper, lookup, Clock.systemUTC(), ruleContexts, predictionContexts);
    }
    AcmgRecommendations(ObjectMapper mapper, Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup,
            Clock clock, ReviewedRuleContextPreparation ruleContexts, ReviewedPredictionContextPreparation predictionContexts) {
        this(mapper, lookup, clock, ruleContexts, predictionContexts, null);
    }
    public AcmgRecommendations(ObjectMapper mapper, Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup,
            ReviewedRuleContextPreparation ruleContexts, ReviewedPredictionContextPreparation predictionContexts,
            Ba1SpecificationCatalog ba1Catalog) {
        this(mapper, lookup, Clock.systemUTC(), ruleContexts, predictionContexts, ba1Catalog);
    }
    AcmgRecommendations(ObjectMapper mapper, Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup,
            Clock clock, ReviewedRuleContextPreparation ruleContexts, ReviewedPredictionContextPreparation predictionContexts,
            Ba1SpecificationCatalog ba1Catalog) {
        this(mapper, lookup, clock, ruleContexts, predictionContexts, ba1Catalog, false);
    }
    private AcmgRecommendations(ObjectMapper mapper, Function<VariantInterpretation, AlphaMissenseProvider.IdentityLookup> lookup,
            Clock clock, ReviewedRuleContextPreparation ruleContexts, ReviewedPredictionContextPreparation predictionContexts,
            Ba1SpecificationCatalog ba1Catalog, boolean populationOnly) {
        this.populationOnly = populationOnly;
        this.ruleContexts = ruleContexts;
        this.predictionContexts = predictionContexts;
        this.ba1Catalog = ba1Catalog;
        this.mapper = mapper.copy().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.identities = new AcmgBusinessArtifacts(mapper);
        this.lookup = lookup; this.clock = clock;
        policy = populationOnly ? this.mapper.createObjectNode().put("policyId", POLICY_ID)
                .put("policyVersion", "2.0.0-population-context-only")
                .put("patientSpecific", false).put("scoreIndependent", true)
                .put("compatibility", "VERIFIED_VEP_CONTEXT_ONLY_NO_PREDICTION_IDENTITY")
                .put("pm2ProfileSha256", GnomadPopulationInputProfile.loadBusinessPm2Supporting().getProfileSha256())
                .put("pm2ContextSelection", PM2_CONTEXT_SELECTION)
                : this.mapper.createObjectNode().put("policyId", POLICY_ID).put("policyVersion", VERSION)
                .put("patientSpecific", false).put("scoreIndependent", true)
                .put("relationshipOrder", "Definitive,Strong,Moderate_OR_EXPLICIT_VCEP_SCOPE,Limited;date-desc;MONDO;MOI;record")
                .put("autoRelationship", "ONE_UNAMBIGUOUS_ACCEPTED_RELATIONSHIP_WITH_CONSISTENT_CANDIDATE_MOI")
                .put("transcriptOrder", "MANE_PLUS_CLINICAL,MANE_SELECT,CANONICAL,ONLY_ELIGIBLE")
                .put("compatibility", "CONSEQUENCE_FIRST;HNF1A_REVEL_TRANSCRIPT_CONTEXT;AM_EXACT_TRANSCRIPT_AA;INTRONIC_VEP_HGVSC;SYNO_VEP116_NEAREST_EXON_JB;PS1_PM5_SCOPED_VCEP_TRANSCRIPT;PVS1_STOP_GAINED_CONTEXT;PVS1_FRAMESHIFT_CONTEXT;POPULATION_CONTEXT_FALLBACK;DUPLICATE_BLOCKED")
                .put("revelProfileSha256", identities.requestHash(Hnf1aRevelRuntime.loadProfile(mapper)))
                .put("pm1ProfileSha256", Hnf1aPm1Runtime.PROFILE_SHA256)
                .put("pm2ProfileSha256", GnomadPopulationInputProfile.loadBusinessPm2Supporting().getProfileSha256())
                .put("pm2ContextSelection", PM2_CONTEXT_SELECTION)
                .put("hnf1aFrequencyProfileSha256", GnomadPopulationInputProfile.HNF1A_PROFILE_SHA256)
                .put("hnf1aFrequencyContextSelection", FREQUENCY_CONTEXT_SELECTION)
                .put("pvs1ProfileSha256", Hnf1aPvs1Runtime.PROFILE_SHA256)
                .put("functionalProfileSha256", Hnf1aFunctionalRuntime.PROFILE_SHA256)
                .put("validity", "CURRENT_SERVER_CONTENT_ONLY").put("generatedAtSemantics", "DISPLAY_ONLY")
                .put("missingHypothesisExplanation", "VERIFIED_RECOMMENDATION_BLOCKERS_V1")
                .put("missenseInputPolicySha256", identities.requestHash(ClinVarMissenseBusinessRuntime.loadProfile(mapper)))
                .put("hnf1aMissenseInputPolicySha256", org.nbscn.varvis.variantanalysis.provider.Hnf1aPs1Pm5Runtime.PROFILE_SHA256);
        if (ruleContexts != null) {
            policy.put("policyVersion", "1.10.0-reviewed-rule-context.1");
            policy.set("ruleContextPreparationBinding", ruleContexts.identity());
        }
        if (predictionContexts != null) {
            policy.put("policyVersion", "1.11.0-reviewed-prediction-context.1")
                    .put("compatibility", "PERSISTED_VEP116_CONTEXT_ONLY;PREDICTOR_AND_POLICY_UNVERIFIED;NO_IDENTITY_LOOKUP");
            policy.set("predictionContextPreparationBinding", predictionContexts.identity());
        }
        if (ba1Catalog != null) {
            if (!populationOnly) policy.put("policyVersion", "1.12.0-ba1-disease-context.1");
            policy.put("ba1DiseaseContextSelection", BA1_CONTEXT_SELECTION);
            policy.set("ba1CatalogBinding", ba1Catalog.identity());
        }
    }
    static AcmgRecommendations unavailable(ObjectMapper mapper) {
        return new AcmgRecommendations(mapper, c -> new AlphaMissenseProvider.IdentityLookup(
                "UNAVAILABLE", "ALPHAMISSENSE_IDENTITY_RESOURCE_UNAVAILABLE", null, null, List.of()));
    }

    public ObjectNode recommend(String analysisId, VariantInterpretation candidate, JsonNode catalog, String sourceSha) {
        return recommend(analysisId, candidate, catalog, sourceSha, mapper.createArrayNode());
    }
    ObjectNode recommend(String analysisId, VariantInterpretation candidate, JsonNode catalog, String sourceSha, ArrayNode sourceBlockers) {
        ObjectNode r = policy.deepCopy().put("policyHash", identities.requestHash(policy))
                .put("candidateId", candidate.getCandidateId()).put("analysisId", analysisId)
                .put("executionSourceSha256", sourceSha);
        if (AcmgBusinessRuleScope.BP1_DECLARATION.equals(policy.path("requiredDeclaration").asText())
                || AcmgBusinessRuleScope.PP2_DECLARATION.equals(policy.path("requiredDeclaration").asText())
                || AcmgBusinessRuleScope.BP3_DECLARATION.equals(policy.path("requiredDeclaration").asText())) {
            try { r.put("publishedCandidateRawSha256", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(mapper.writeValueAsString(candidate))); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException(
                    AcmgBusinessRuleScope.BP1_DECLARATION.equals(policy.path("requiredDeclaration").asText()) ? "BP1_PUBLISHED_INPUT_IDENTITY_UNAVAILABLE" : AcmgBusinessRuleScope.PP2_DECLARATION.equals(policy.path("requiredDeclaration").asText())
                            ? "PP2_PUBLISHED_INPUT_IDENTITY_UNAVAILABLE" : "BP3_PUBLISHED_INPUT_IDENTITY_UNAVAILABLE", invalid); }
        }
        // Source failure details must be content-bound too, even when no source hash is available.
        r.set("sourceBlockers", sourceBlockers.deepCopy());
        r.set("geneIdentitySource", catalog.has("geneIdentitySource") ? catalog.path("geneIdentitySource") : mapper.nullNode());
        r.set("relationshipSource", catalog.has("relationshipSource") ? catalog.path("relationshipSource") : mapper.nullNode());
        r.set("allele", mapper.valueToTree(candidate.getObservation() == null ? null : candidate.getObservation().getKey()));
        r.put("hypothesisId", "ordinary-" + identities.requestHash(mapper.createObjectNode()
                .put("analysisId", analysisId).put("candidateId", candidate.getCandidateId())).substring(0, 20) + "-h1");
        var candidateIdentity = candidate.getCandidateIdentity(); var candidateFeatures = candidate.getExomiserCandidateFeatures();
        ObjectNode moiSources = mapper.createObjectNode()
                .put("candidateIdentityMoi", candidateIdentity == null ? null : candidateIdentity.getModeOfInheritance())
                .put("rawFeatureMoi", candidateFeatures == null ? null : candidateFeatures.getRawModeOfInheritance())
                .put("normalizedFeatureMoi", candidateFeatures == null ? null : candidateFeatures.getNormalizedModeOfInheritance());
        r.set("candidateMoiSources", moiSources);
        String candidateMoiReason = candidateMoiReason(moiSources);
        r.put("candidateMoiReasonCode", candidateMoiReason);
        List<ObjectNode> relations = new ArrayList<>();
        for (var row : catalog.path("relationships")) {
            if (classificationRank(row) > 3) continue;
            ObjectNode relation = (ObjectNode) row.deepCopy();
            var key = mapper.createObjectNode().put("hgncId", row.path("hgncId").asText())
                    .put("mondoId", row.path("mondoId").asText()).put("moi", row.path("moi").asText());
            relation.put("stableRelationshipKey", "relationship-" + identities.requestHash(key));
            boolean valid = row.path("hgncId").asText().equals(catalog.path("hgncId").asText())
                    && candidate.getExomiserCandidateFeatures() != null
                    && Objects.equals(candidate.getExomiserCandidateFeatures().getGeneSymbol(), row.path("symbol").asText())
                    && row.path("mondoId").asText().matches("MONDO:[0-9]{7}")
                    && (PatientDiseaseContextBindingResolver.normalizeCatalogMoi(row.path("moi").asText()) != null
                        || localInterpretationContexts && "XL".equals(row.path("moi").asText()))
                    && !row.path("reportLocator").asText().isBlank() && !row.path("classificationDate").asText().isBlank()
                    && !row.path("gcep").asText().isBlank();
            String relationMoi = PatientDiseaseContextBindingResolver.normalizeCatalogMoi(row.path("moi").asText());
            String moiReason = candidateMoiReason != null ? candidateMoiReason : relationMoi == null ? "CATALOG_MOI_UNMAPPABLE"
                    : relationMoi.equals(PatientDiseaseContextBindingResolver.normalizeMoi(moiSources.path("candidateIdentityMoi").asText()))
                    ? null : "CANDIDATE_RELATIONSHIP_MOI_CONFLICT";
            if (localInterpretationContexts) {
                var assessment = LocalInterpretationContextPolicy.assessCatalog(candidate, row.path("moi").asText());
                relation.set("interpretationContext", mapper.valueToTree(assessment));
                relation.put("requiresExplicitInterpretationContext", assessment.requiresExplicitSelection());
                if ("XL".equals(row.path("moi").asText())) {
                    relation.put("catalogMoi", "XL");
                    if (assessment.interpretationMoi() != null) relation.put("moi", assessment.interpretationMoi());
                }
                moiReason = assessment.reasonCode();
                if (moiReason == null && assessment.requiresExplicitSelection()
                        && (sourceSha == null || sourceBlockers == null || !sourceBlockers.isEmpty()))
                    moiReason = "INTERPRETATION_PUBLISHED_SOURCE_REQUIRED";
            }
            if (moiReason != null) relation.put("reasonCode", moiReason);
            relation.put("selectable", valid && moiReason == null && classificationRank(row) < 3);
            relations.add(relation);
        }
        relations.sort(Comparator.comparingInt(AcmgRecommendations::classificationRank)
                .thenComparing(n -> n.path("classificationDate").asText(), Comparator.reverseOrder())
                .thenComparing(n -> n.path("mondoId").asText()).thenComparing(n -> n.path("moi").asText())
                .thenComparing(n -> n.path("reportLocator").asText()));
        // Repeated curated records for one key are ambiguous, not an invitation to choose an array row.
        for (var relation : relations) if (relations.stream().filter(n -> n.path("stableRelationshipKey").equals(relation.path("stableRelationshipKey"))).count() != 1)
            relation.put("selectable", false);
        r.set("relationships", mapper.valueToTree(relations));
        boolean unique = relations.size() == 1 && relations.get(0).path("selectable").asBoolean()
                && !relations.get(0).path("requiresExplicitInterpretationContext").asBoolean();
        r.put("relationshipStatus", candidateMoiReason != null ? "REVIEW_REQUIRED" : unique ? "AUTO_RECOMMENDED"
                : relations.isEmpty() ? "NO_ELIGIBLE_RELATIONSHIP" : "REVIEW_REQUIRED");
        if (unique) r.set("stableRelationshipKey", relations.get(0).path("stableRelationshipKey")); else r.putNull("stableRelationshipKey");

        List<ObjectNode> annotations = new ArrayList<>();
        // Preparing a verified context must not depend on an available prediction or PM2 policy.
        // Each evaluator independently decides whether its evidence and policy are sufficient.
        boolean populationContext = relations.stream().anyMatch(rn -> rn.path("selectable").asBoolean());
        boolean pvs1Context = !populationOnly && relations.stream().filter(rn -> rn.path("selectable").asBoolean())
                .anyMatch(rn -> Hnf1aPvs1Runtime.hasProductionPolicy(rn, PatientDiseaseContextBindingResolver.normalizeCatalogMoi(rn.path("moi").asText())));
        boolean revelContext = !populationOnly && relations.stream().filter(rn -> rn.path("selectable").asBoolean())
                .anyMatch(rn -> Hnf1aRevelRuntime.hasProductionPolicy(rn, PatientDiseaseContextBindingResolver.normalizeCatalogMoi(rn.path("moi").asText())));
        boolean pm1Context = !populationOnly && relations.stream().filter(rn -> rn.path("selectable").asBoolean())
                .anyMatch(rn -> Hnf1aPm1Runtime.hasProductionPolicy(rn, PatientDiseaseContextBindingResolver.normalizeCatalogMoi(rn.path("moi").asText())));
        var list = candidate.getTranscriptAnnotations() == null ? List.<TranscriptConsequence>of() : candidate.getTranscriptAnnotations();
        var features = candidate.getExomiserCandidateFeatures();
        for (int i = 0; i < list.size(); i++) {
            var t = list.get(i);
            if (t == null || features == null || !Objects.equals(features.getGeneSymbol(), t.getGeneSymbol())
                    || t.getSource() == null || !t.getSource().startsWith("VEP") || !"COMPLETED".equals(t.getStatus())
                    || t.getVersion() == null || t.getVersion().isBlank() || t.getTimestamp() == null
                    || t.getVersionedTranscriptId() == null || t.getTranscriptVersion() == null || t.getTranscriptVersion() < 1
                    || !(populationOnly || populationContext || pvs1Context && Hnf1aPvs1Runtime.contextTranscript(t) || IntronicSplicePolicy.spliceCandidate(t) || IntronicSplicePolicy.missense(t)
                    && t.getProteinIdentity() != null && t.getHgvsp() != null && t.getAminoAcidChange() != null)) continue;
            ObjectNode a = mapper.createObjectNode().put("versionedTranscriptId", t.getVersionedTranscriptId())
                    .put("geneSymbol", t.getGeneSymbol()).put("annotationSource", t.getSource()).put("annotationSourceVersion", t.getVersion())
                    .put("hgvsp", t.getHgvsp()).put("vepProteinIdentity", t.getProteinIdentity()).put("aminoAcidChange", t.getAminoAcidChange())
                    .put("predictionPath", populationOnly ? "POPULATION_ONLY" : pvs1Context && Hnf1aPvs1Runtime.contextTranscript(t)
                            ? Arrays.asList(Objects.toString(t.getConsequence(), "").split("[&,|]")).contains("frameshift_variant")
                                    ? "PVS1_FRAMESHIFT_CONTEXT" : "PVS1_STOP_GAINED_CONTEXT"
                            : revelContext && IntronicSplicePolicy.missense(t)
                                    ? Hnf1aRevelRuntime.contextTranscript(t) ? "HNF1A_REVEL" : "HNF1A_REVEL_UNSUPPORTED_TRANSCRIPT"
                            : IntronicSplicePolicy.spliceCandidate(t) ? IntronicSplicePolicy.predictionPath(t)
                            : IntronicSplicePolicy.missense(t) ? "ALPHAMISSENSE_MISSENSE" : "POPULATION_ONLY")
                    .put("hgvsc", t.getHgvsc()).put("consequence", t.getConsequence())
                    .put("pm1Compatible", pm1Context && Hnf1aPm1Runtime.contextTranscript(t)
                            && candidate.getObservation() != null
                            && "GRCh38".equals(candidate.getObservation().getKey().getGenomeAssembly())
                            && candidate.getObservation().getKey().getReference().matches("[ACGT]")
                            && candidate.getObservation().getKey().getAlternate().matches("[ACGT]"))
                    .put("intronicSignedOffset", t.getBp7IntronicSignedOffset())
                    .put("positionReason", populationOnly ? null : new IntronicSplicePolicy(mapper).blocker(t))
                    .put("manePlusClinical", Boolean.TRUE.equals(t.getManePlusClinical())).put("maneSelect", Boolean.TRUE.equals(t.getManeSelect()))
                    .put("canonical", Boolean.TRUE.equals(t.getCanonical()));
            if (!populationOnly && IntronicSplicePolicy.synonymous(t) && t.getSynonymousExonPosition() != null
                    && !t.getSynonymousExonPosition().matches(candidate.getObservation().getKey(), t.getVersionedTranscriptId()))
                a.put("positionReason", "SYNONYMOUS_POSITION_IDENTITY_MISMATCH");
            a.put("annotationSourceSha256", identities.requestHash(mapper.valueToTree(t)));
            if (predictionContexts != null) {
                a.put("predictionPath", ReviewedPredictionContextPreparation.PATH)
                        .put("predictionContextReady", populationContext && sourceSha != null && sourceBlockers.isEmpty()
                                && ReviewedPredictionContextPreparation.eligibleAnnotation(candidate, t))
                        .put("predictionContextSelectionPolicy", predictionContexts.selectionPolicy());
            }
            a.put("annotationId", "annotation-" + identities.requestHash(a)).put("annotationIndex", i);
            long duplicates = list.stream().filter(Objects::nonNull).filter(other -> Objects.equals(t.getVersionedTranscriptId(), other.getVersionedTranscriptId())
                    && Objects.equals(t.getGeneSymbol(), other.getGeneSymbol()) && Objects.equals(t.getSource(), other.getSource())
                    && Objects.equals(t.getVersion(), other.getVersion())).count();
            if (duplicates == 1) annotations.add(a);
        }
        annotations.sort(Comparator.comparing(n -> n.path("annotationId").asText()));
        ObjectNode clinical = preferred(annotations);
        r.set("clinicalPreferredTranscript", clinical == null ? mapper.nullNode() : clinical.deepCopy());
        AlphaMissenseProvider.IdentityLookup identity;
        try { identity = populationOnly ? new AlphaMissenseProvider.IdentityLookup("AVAILABLE", "NOT_QUERIED_POPULATION_CONTEXT_ONLY", null, null, List.of())
                : predictionContexts != null
                ? new AlphaMissenseProvider.IdentityLookup("NOT_QUERIED", "PREDICTION_CONTEXT_CONFIRMATION_DOES_NOT_QUERY_SCORES_OR_IDENTITIES", null, null, List.of())
                : annotations.stream().anyMatch(a -> "ALPHAMISSENSE_MISSENSE".equals(a.path("predictionPath").asText()))
                ? lookup.apply(candidate) : new AlphaMissenseProvider.IdentityLookup("AVAILABLE", "NOT_QUERIED_SPLICE_CONSEQUENCE", null, null, List.of()); }
        catch (RuntimeException failed) { identity = new AlphaMissenseProvider.IdentityLookup("UNAVAILABLE", "ALPHAMISSENSE_IDENTITY_QUERY_FAILED", null, null, List.of()); }
        r.set("resourceIdentity", mapper.valueToTree(identity));
        List<ObjectNode> compatible = new ArrayList<>();
        for (ObjectNode a : annotations) {
            if (predictionContexts != null) {
                a.put("identityCompatible", false).put("identityReason", "PREDICTION_CONTEXT_AVAILABLE_EVIDENCE_NOT_VERIFIED");
                if (a.path("predictionContextReady").asBoolean()) compatible.add(a);
                continue;
            }
            if ("HNF1A_REVEL_UNSUPPORTED_TRANSCRIPT".equals(a.path("predictionPath").asText())) {
                a.put("identityCompatible", populationContext).put("identityReason", "REVEL_TRANSCRIPT_NOT_SUPPORTED")
                        .put("predictionPolicyReason", "REVEL_TRANSCRIPT_NOT_SUPPORTED");
                if (populationContext) { a.put("predictionPath", "POPULATION_ONLY"); compatible.add(a); }
                continue;
            }
            if ("HNF1A_REVEL".equals(a.path("predictionPath").asText())) {
                // This selects the disease-specific context before score lookup; it is not a resource HIT claim.
                a.put("identityCompatible", true).put("identityReason", "HNF1A_REVEL_TRANSCRIPT_CONTEXT");
                compatible.add(a);
                continue;
            }
            if (pvs1ContextPath(a.path("predictionPath").asText())) {
                a.put("identityCompatible", true).put("identityReason", "PVS1_DISEASE_TRANSCRIPT_CONTEXT_ONLY");
                compatible.add(a);
                continue;
            }
            if ("POPULATION_ONLY".equals(a.path("predictionPath").asText())) {
                a.put("identityCompatible", populationContext).put("identityReason", "PM2_CONTEXT_TRANSCRIPT_ONLY");
                if (populationContext) compatible.add(a);
                continue;
            }
            if (IntronicSplicePolicy.splicePath(a.path("predictionPath").asText())) {
                // No SpliceAI score is read for selection, and no model transcript match is claimed.
                boolean valid = a.path("positionReason").isNull() && "116.0".equals(a.path("annotationSourceVersion").asText());
                a.put("identityCompatible", valid).put("identityReason", valid ? "VEP_SPLICE_POSITION_VERIFIED"
                        : a.path("positionReason").asText("VEP_RESOURCE_IDENTITY_MISMATCH"));
                a.put("spliceAiTranscriptIdentityClaimed", false);
                if (valid) compatible.add(a);
                else if (populationContext) {
                    a.put("identityCompatible", true).put("identityReason", "PM2_CONTEXT_TRANSCRIPT_ONLY")
                            .put("predictionPath", "POPULATION_ONLY"); compatible.add(a);
                }
                continue;
            }
            var exact = identity.records().stream().filter(v -> v.transcriptId().equals(a.path("versionedTranscriptId").asText())
                    && v.aminoAcidChange().equals(a.path("aminoAcidChange").asText())).toList();
            a.put("identityCompatible", "AVAILABLE".equals(identity.status()) && exact.size() == 1);
            a.put("identityReason", exact.size() > 1 ? "ALPHAMISSENSE_MATCH_CONFLICT" : exact.isEmpty() ? "TRANSCRIPT_PROTEIN_MISMATCH" : "EXACT_IDENTITY_MATCH");
            if (a.path("identityCompatible").asBoolean()) {
                a.put("alphaMissenseUniprotId", exact.get(0).uniprotId()); compatible.add(a);
            } else if (pvs1Context && Hnf1aFunctionalRuntime.reviewedTranscript(mapper, candidate, list.get(a.path("annotationIndex").asInt()))) {
                a.put("identityCompatible", true).put("identityReason", "HNF1A_FUNCTIONAL_TRANSCRIPT_CONTEXT_ONLY")
                        .put("predictionPath", "HNF1A_FUNCTIONAL_CONTEXT"); compatible.add(a);
            } else if (populationContext) {
                // A PM2 declaration needs a verified VEP transcript, not an AlphaMissense record.
                // Prediction dispatch independently retains its original consequence/resource checks.
                a.put("identityCompatible", true).put("identityReason", "PM2_CONTEXT_TRANSCRIPT_ONLY")
                        .put("predictionPath", "POPULATION_ONLY"); compatible.add(a);
            }
        }
        // This additional path depends on the explicit server capability, never on a score or an inferred LoF mechanism.
        // Keep every existing evidence-compatible route. Only otherwise population-only annotations gain this choice.
        if (ruleContexts != null && populationContext && sourceSha != null && sourceBlockers.isEmpty()) {
            for (ObjectNode annotation : annotations) {
                int index = annotation.path("annotationIndex").asInt(-1);
                if ("POPULATION_ONLY".equals(annotation.path("predictionPath").asText()) && index >= 0 && index < list.size()
                        && ruleContexts.eligible(candidate, list.get(index))) {
                    annotation.put("predictionPath", ReviewedRuleContextPreparation.PATH).put("ruleContextReady", true)
                            .put("ruleContextSelectionPolicy", ruleContexts.selectionPolicy())
                            .put("identityCompatible", false).put("identityReason", "RULE_CONTEXT_AVAILABLE_EVIDENCE_NOT_VERIFIED");
                }
            }
        }
        var observation = candidate.getObservation();
        var allele = observation == null ? null : observation.getKey();
        boolean nativeSnv = allele != null && "GRCh38".equals(allele.getGenomeAssembly())
                && String.valueOf(allele.getReference()).matches("[ACGT]") && String.valueOf(allele.getAlternate()).matches("[ACGT]")
                && !Objects.equals(allele.getReference(), allele.getAlternate());
        boolean scopedRelation = !populationOnly && relations.stream().anyMatch(row -> row.path("selectable").asBoolean()
                && missenseRelationship(row));
        List<ObjectNode> missenseCompatible = new ArrayList<>();
        for (ObjectNode annotation : annotations) {
            boolean ready = nativeSnv && scopedRelation && missensePolicyTranscript(annotation);
            annotation.put("predictionCompatible", annotation.path("identityCompatible").asBoolean()
                    && !annotation.path("ruleContextReady").asBoolean()
                    && !"POPULATION_ONLY".equals(annotation.path("predictionPath").asText())
                    && !"HNF1A_FUNCTIONAL_CONTEXT".equals(annotation.path("predictionPath").asText())
                    && !pvs1ContextPath(annotation.path("predictionPath").asText()));
            annotation.put("ps1Pm5Compatible", ready);
            annotation.put("contextSelectable", annotation.path("identityCompatible").asBoolean() || annotation.path("ruleContextReady").asBoolean()
                    || annotation.path("predictionContextReady").asBoolean() || ready || annotation.path("pm1Compatible").asBoolean());
            if (ready) missenseCompatible.add(annotation);
        }
        r.set("annotations", mapper.valueToTree(annotations));
        // Population-only alternatives must not displace an existing evidence-path context choice.
        List<ObjectNode> evidenceContextCompatible = compatible.stream()
                .filter(a -> !"POPULATION_ONLY".equals(a.path("predictionPath").asText())).toList();
        ObjectNode selected = preferred(evidenceContextCompatible);
        ObjectNode missenseSelected = preferred(missenseCompatible);
        r.set("resourceCompatibleTranscript", selected == null ? mapper.nullNode() : selected.deepCopy());
        r.set("ps1Pm5CompatibleTranscript", missenseSelected == null ? mapper.nullNode() : missenseSelected.deepCopy());
        ObjectNode pm1Selected = preferred(annotations.stream().filter(a -> a.path("pm1Compatible").asBoolean()).toList());
        r.set("pm1CompatibleTranscript", pm1Selected == null ? mapper.nullNode() : pm1Selected.deepCopy());
        String txStatus = compatible.isEmpty() && !"AVAILABLE".equals(identity.status()) ? "IDENTITY_CHECK_UNAVAILABLE"
                : compatible.isEmpty() ? "NO_COMPATIBLE_TRANSCRIPT" : selected == null ? "REVIEW_REQUIRED" : "RESOURCE_COMPATIBLE_RECOMMENDATION";
        if (selected == null && missenseSelected != null) {
            selected = missenseSelected;
            txStatus = "PS1_PM5_TRANSCRIPT_RECOMMENDATION";
        }
        if (selected == null && pm1Selected != null) {
            selected = pm1Selected; txStatus = "PM1_TRANSCRIPT_RECOMMENDATION";
        }
        if (selected == null && evidenceContextCompatible.isEmpty() && missenseCompatible.isEmpty()) {
            selected = preferred(compatible);
            r.set("resourceCompatibleTranscript", selected == null ? mapper.nullNode() : selected.deepCopy());
            if (selected != null) txStatus = "RESOURCE_COMPATIBLE_RECOMMENDATION";
        }
        if (selected != null && selected.path("ruleContextReady").asBoolean()) txStatus = "RULE_CONTEXT_RECOMMENDATION";
        if (selected != null && selected.path("predictionContextReady").asBoolean()) txStatus = "PREDICTION_CONTEXT_RECOMMENDATION";
        r.put("transcriptStatus", txStatus).put("transcriptReasonCode", "PS1_PM5_TRANSCRIPT_RECOMMENDATION".equals(txStatus)
                ? txStatus : "RULE_CONTEXT_RECOMMENDATION".equals(txStatus) || "PREDICTION_CONTEXT_RECOMMENDATION".equals(txStatus) ? txStatus
                : !"AVAILABLE".equals(identity.status()) ? identity.reasonCode() : txStatus);
        if (selected == null) r.putNull("recommendedAnnotationId"); else r.set("recommendedAnnotationId", selected.path("annotationId"));
        boolean sourceReady = sourceSha != null;
        if (predictionOnly) {
            String status = !sourceReady ? "SOURCE_NOT_READY" : unique && selected != null ? "READY_FOR_CONFIRMATION" : "REVIEW_REQUIRED";
            r.put("status", status).put("reasonCode", candidateMoiReason != null ? candidateMoiReason : status);
            r.putObject("predictionPreparation").put("stage", "CONTEXT_CONFIRMATION_ONLY")
                    .put("calibrationStatus", "CALIBRATION_PRECISION_UNRESOLVED").put("scoreLookups", 0);
            r.put("recommendationId", "recommendation-" + identities.requestHash(r)).put("generatedAt", clock.instant().toString());
            return r;
        }
        var population = r.putObject("pm2ContextPreparation");
        population.put("purpose", "PM2_ONLY_NOT_PREDICTION_CONFIRMATION");
        population.set("clinicalAnnotation", clinical == null ? mapper.nullNode() : clinical.deepCopy());
        var eligibleRelationships = population.putArray("eligibleRelationshipKeys");
        var policies = GnomadPopulationInputProfile.loadBusinessPm2Supporting().getDocument()
                .at("/evaluation/criteria/2/populationRule/policySnapshot/records");
        if (sourceReady && clinical != null) for (var relation : relations) {
            var matches = new ArrayList<JsonNode>();
            for (var p : policies) if (p.at("/gene/hgncId").equals(relation.path("hgncId"))
                    && p.at("/disease/mondoId").equals(relation.path("mondoId"))
                    && p.path("moi").asText().equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(relation.path("moi").asText()))) matches.add(p);
            if (relation.path("selectable").asBoolean() && matches.size() == 1 && transcriptIndependentPopulationPolicy(matches.get(0)))
                eligibleRelationships.add(relation.path("stableRelationshipKey"));
        }
        population.put("available", !eligibleRelationships.isEmpty());
        // Explain independent resource checks; these diagnostics neither select nor authorize a context.
        var diagnostics = population.putObject("diagnostics").put("schemaVersion", "pm2-context-preparation-v1")
                .put("candidateId", candidate.getCandidateId()).put("hgncId", catalog.path("hgncId").asText(null))
                .put("geneSymbol", features == null ? null : features.getGeneSymbol());
        var relationshipCheck = diagnostics.putObject("relationshipCheck")
                .put("catalogRecordCount", catalog.path("relationships").size())
                .put("selectableRecordCount", Math.toIntExact(relations.stream().filter(n -> n.path("selectable").asBoolean()).count()))
                .put("lookupFields", "candidate NCBI Gene ID + symbol -> MANE HGNC ID; catalog HGNC ID + symbol");
        var relationshipSource = catalog.path("relationshipSource");
        relationshipCheck.put("resourceId", relationshipSource.path("resourceId").asText(null))
                .put("resourceVersion", relationshipSource.path("resourceVersion").asText(null))
                .put("snapshotSha256", relationshipSource.path("derivedSha256").asText(null));
        int genePolicies = 0, relationshipPolicies = 0;
        for (var p : policies) {
            if (p.at("/gene/hgncId").equals(catalog.path("hgncId"))) genePolicies++;
            for (var relation : relations) if (relation.path("selectable").asBoolean()
                    && p.at("/gene/hgncId").equals(relation.path("hgncId"))
                    && p.at("/disease/mondoId").equals(relation.path("mondoId"))
                    && p.path("moi").asText().equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(relation.path("moi").asText())))
                relationshipPolicies++;
        }
        diagnostics.putObject("policyCheck").put("profileSha256", policy.path("pm2ProfileSha256").asText())
                .put("geneRecordCount", genePolicies).put("relationshipMatchCount", relationshipPolicies)
                .put("lookupFields", "HGNC ID + MONDO ID + normalized MOI")
                .put("relationshipExistenceClaimed", false);
        diagnostics.putObject("transcriptCheck").put("annotationCount", list.size())
                .put("sameGeneCompletedVepCount", Math.toIntExact(list.stream().filter(Objects::nonNull)
                        .filter(t -> features != null && Objects.equals(features.getGeneSymbol(), t.getGeneSymbol())
                                && t.getSource() != null && t.getSource().startsWith("VEP") && "COMPLETED".equals(t.getStatus())).count()))
                .put("contextAnnotationCount", annotations.size()).put("preferredAnnotationAvailable", clinical != null)
                .put("status", !populationContext ? "DISEASE_RELATIONSHIP_REQUIRED_BEFORE_CONTEXT_SELECTION"
                        : clinical == null ? "VERIFIED_CLINICAL_ANNOTATION_REQUIRED" : "ANNOTATION_AVAILABLE_NOT_USER_CONFIRMED");
        String contextReason = !sourceReady || !sourceBlockers.isEmpty() ? "PM2_EXECUTION_SOURCE_NOT_READY"
                : catalog.hasNonNull("reasonCode") ? catalog.path("reasonCode").asText()
                : catalog.path("relationships").isEmpty() ? "PM2_DISEASE_CATALOG_NO_RECORD"
                : !populationContext ? "PM2_DISEASE_RELATIONSHIP_NOT_SELECTABLE"
                : clinical == null ? "PM2_VERIFIED_CLINICAL_ANNOTATION_REQUIRED"
                : relationshipPolicies == 0 ? "PM2_CONTEXT_POLICY_NO_MATCH" : "PM2_CONTEXT_CONFIRMATION_REQUIRED";
        diagnostics.put("reasonCode", contextReason);
        if (!populationOnly) {
        var frequency = r.putObject("hnf1aFrequencyContextPreparation");
        frequency.put("purpose", "HNF1A_DISEASE_BA1_BS1_AND_PM2_NOT_PREDICTION_CONFIRMATION")
                .put("selectionPolicy", FREQUENCY_CONTEXT_SELECTION);
        frequency.set("clinicalAnnotation", clinical == null ? mapper.nullNode() : clinical.deepCopy());
        var frequencyRelations = frequency.putArray("eligibleRelationshipKeys");
        if (sourceReady && clinical != null && nativeSnv) for (var relation : relations)
            if (relation.path("selectable").asBoolean() && "HGNC:11621".equals(relation.path("hgncId").asText())
                    && "MONDO:0015967".equals(relation.path("mondoId").asText())
                    && "AUTOSOMAL_DOMINANT".equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(relation.path("moi").asText())))
                frequencyRelations.add(relation.path("stableRelationshipKey"));
        frequency.put("available", !frequencyRelations.isEmpty());
        }
        if (ba1Catalog != null) {
            var preparation = r.putObject("ba1DiseaseContextPreparation")
                    .put("purpose", "BA1_ONLY_DISEASE_CONFIRMATION_OTHER_RULES_UNCONFIRMED")
                    .put("selectionPolicy", BA1_CONTEXT_SELECTION).put("transcriptConfirmed", false);
            preparation.set("clinicalAnnotation", clinical == null ? mapper.nullNode() : clinical.deepCopy());
            var backgrounds = ba1Catalog.backgroundOptions(candidate);
            preparation.set("catalogBackgrounds", backgrounds);
            var eligible = preparation.putArray("eligibleRelationshipKeys");
            if (sourceReady && sourceBlockers.isEmpty() && clinical != null) for (var relation : relations) {
                boolean matched = false;
                for (var background : backgrounds) matched |= background.path("hgncId").equals(relation.path("hgncId"))
                        && background.path("mondoId").equals(relation.path("mondoId"))
                        && Objects.equals(PatientDiseaseContextBindingResolver.normalizeMoi(background.path("moi").asText()),
                                PatientDiseaseContextBindingResolver.normalizeCatalogMoi(relation.path("moi").asText()));
                if (matched && relation.path("selectable").asBoolean()) eligible.add(relation.path("stableRelationshipKey"));
            }
            preparation.put("available", !eligible.isEmpty()).put("reasonCode", !eligible.isEmpty() ? "BA1_DISEASE_CONTEXT_AVAILABLE"
                    : clinical == null ? "BA1_VERIFIED_CLINICAL_ANNOTATION_REQUIRED"
                    : "BA1_CURRENT_DISEASE_RELATIONSHIP_OR_SOURCE_REQUIRED");
        }
        if (!bs1Policies.isEmpty()) {
            var preparation = r.putObject("bs1ContextPreparation").put("purpose", "BS1_EXPLICIT_DISEASE_CONFIRMATION")
                    .put("schemaVersion", "bs1-context-options-v1");
            var eligible = preparation.putArray("eligibleRelationshipKeys"); var options = preparation.putArray("policyOptions");
            for (var relation : relations) {
                var option = Bs1Runtime.contextOption(mapper, bs1Policies, relation,
                        PatientDiseaseContextBindingResolver.normalizeCatalogMoi(relation.path("moi").asText()));
                var knowledge = bs1Knowledge;
                if (knowledge != null && "BS1_POLICY_NO_RECORD".equals(option.path("reasonCode").asText())) {
                    var gap = knowledge.explain(relation.path("hgncId").asText(), relation.path("mondoId").asText(),
                            PatientDiseaseContextBindingResolver.normalizeCatalogMoi(relation.path("moi").asText()));
                    option.put("reasonCode", gap.path("reasonCode").asText()); option.set("knowledgeGap", gap);
                }
                if (!sourceReady || !sourceBlockers.isEmpty()) option.put("available", false).put("reasonCode", "BS1_EXECUTION_SOURCE_NOT_READY");
                if (!relation.path("selectable").asBoolean()) option.put("available", false).put("reasonCode", "BS1_DISEASE_RELATIONSHIP_NOT_SELECTABLE");
                options.add(option);
                if (option.path("available").asBoolean()) eligible.add(relation.path("stableRelationshipKey"));
            }
            preparation.put("available", !eligible.isEmpty()).put("reasonCode", !eligible.isEmpty() ? "BS1_CONTEXT_CONFIRMATION_REQUIRED"
                    : relations.isEmpty() ? "BS1_DISEASE_CATALOG_NO_RECORD" : options.path(0).path("reasonCode").asText("BS1_POLICY_NO_RECORD"));
        }
        String status = !sourceReady ? "SOURCE_NOT_READY" : candidateMoiReason != null ? "REVIEW_REQUIRED" : unique && selected != null ? "READY_FOR_CONFIRMATION"
                : "REVIEW_REQUIRED".equals(txStatus) || !unique && !relations.isEmpty() ? "REVIEW_REQUIRED" : txStatus;
        r.put("status", status).put("reasonCode", candidateMoiReason != null ? candidateMoiReason
                : relations.size() == 1 && relations.get(0).has("reasonCode") ? relations.get(0).path("reasonCode").asText() : status);
        // Display time is outside identity. Freshness is proven only by regenerating current server content.
        r.put("recommendationId", "recommendation-" + identities.requestHash(r));
        r.put("generatedAt", clock.instant().toString());
        return r;
    }
    private static boolean pvs1ContextPath(String path) {
        return Set.of("PVS1_STOP_GAINED_CONTEXT", "PVS1_FRAMESHIFT_CONTEXT").contains(path);
    }
    private static boolean missensePolicyTranscript(JsonNode annotation) {
        if ("HNF1A".equals(annotation.path("geneSymbol").asText()))
            return "missense_variant".equals(annotation.path("consequence").asText())
                    && "116.0".equals(annotation.path("annotationSourceVersion").asText())
                    && "ENST00000257555.11".equals(annotation.path("versionedTranscriptId").asText())
                    && "ENSP00000257555.5".equals(annotation.path("vepProteinIdentity").asText());
        return "BRAF".equals(annotation.path("geneSymbol").asText())
                && annotation.path("consequence").asText().contains("missense_variant")
                && "116.0".equals(annotation.path("annotationSourceVersion").asText())
                && "ENST00000646891.2".equals(annotation.path("versionedTranscriptId").asText())
                && "ENSP00000493543.1".equals(annotation.path("vepProteinIdentity").asText());
    }
    private static boolean missenseRelationship(JsonNode row) {
        return PatientDiseaseContextBindingResolver.vcepScope(row)
                || ("HGNC:11621".equals(row.path("hgncId").asText()) && "MONDO:0015967".equals(row.path("mondoId").asText())
                && "AUTOSOMAL_DOMINANT".equals(PatientDiseaseContextBindingResolver.normalizeCatalogMoi(row.path("moi").asText())));
    }
    private static String candidateMoiReason(ObjectNode sources) {
        Set<String> normalized = new HashSet<>();
        for (var source : sources) {
            if (source.isNull() || source.asText().isBlank()) return "CANDIDATE_MOI_REQUIRED";
            String moi = PatientDiseaseContextBindingResolver.normalizeMoi(source.asText());
            if (moi == null) return "CANDIDATE_MOI_UNMAPPABLE";
            normalized.add(moi);
        }
        return normalized.size() == 1 ? null : "CANDIDATE_MOI_CONFLICT";
    }
    /** Business explanation of the verified current choices, never of an unselected alternative. */
    ArrayNode missingHypothesisBlockers(JsonNode current, Selection selection) {
        JsonNode selectedRelationship = selectedRecord(current.path("relationships"), "stableRelationshipKey",
                selection == null ? null : selection.stableRelationshipKey(), "selectable");
        JsonNode selectedAnnotation = selectedContextAnnotation(current, selection == null ? null : selection.annotationId(), selectedRelationship);
        ArrayNode blockers = mapper.createArrayNode();
        if ("READY_FOR_CONFIRMATION".equals(current.path("status").asText())) return blockers;
        addReason(blockers, current.path("candidateMoiReasonCode").asText(null), "recommendation.candidateMoiReasonCode");
        String relationshipStatus = current.path("relationshipStatus").asText();
        if (selectedRelationship == null) {
            boolean hasSelectableRelationship = false;
            for (var relation : current.path("relationships")) hasSelectableRelationship |= relation.path("selectable").asBoolean();
            if (hasSelectableRelationship) {
                addReason(blockers, "DISEASE_RELATIONSHIP_REVIEW_REQUIRED", "recommendation.relationshipStatus");
            } else if ("NO_ELIGIBLE_RELATIONSHIP".equals(relationshipStatus)) {
                addReason(blockers, relationshipStatus, "recommendation.relationshipStatus");
            } else if ("REVIEW_REQUIRED".equals(relationshipStatus)) {
                for (var relation : current.path("relationships"))
                    addReason(blockers, relation.path("reasonCode").asText(null), "recommendation.relationships");
                if (blockers.isEmpty()) addReason(blockers, "DISEASE_RELATIONSHIP_REVIEW_REQUIRED", "recommendation.relationshipStatus");
            }
        }
        String transcriptStatus = current.path("transcriptStatus").asText();
        switch (transcriptStatus) {
            case "NO_COMPATIBLE_TRANSCRIPT" -> addReason(blockers, transcriptStatus, "recommendation.transcriptStatus");
            case "REVIEW_REQUIRED" -> {
                if (selectedAnnotation == null) addReason(blockers, "TRANSCRIPT_REVIEW_REQUIRED", "recommendation.transcriptStatus");
            }
            case "IDENTITY_CHECK_UNAVAILABLE" -> {
                String reason = current.path("transcriptReasonCode").asText("");
                addReason(blockers, reason.isBlank() ? transcriptStatus : reason, "recommendation.transcriptReasonCode");
            }
            default -> { /* No trustworthy specific explanation: keep the original missing-input blocker. */ }
        }
        for (var source : current.path("sourceBlockers"))
            addReason(blockers, source.path("reasonCode").asText(null), source.path("field").asText("executionSource"));
        if (current.path("sourceBlockers").isEmpty() && "SOURCE_NOT_READY".equals(current.path("status").asText()))
            addReason(blockers, "SOURCE_NOT_READY", "executionSource");
        return blockers;
    }
    private static void addReason(ArrayNode blockers, String code, String field) {
        if (code == null || code.isBlank()) return;
        for (var blocker : blockers) if (code.equals(blocker.path("reasonCode").asText())) return;
        blockers.addObject().put("status", "CONTEXT_NOT_READY").put("reasonCode", code).put("field", field);
    }
    private static int classificationRank(JsonNode n) {
        if (PatientDiseaseContextBindingResolver.vcepScope(n) || PatientDiseaseContextBindingResolver.bs1ExpertScope(n)) return 2;
        return switch (n.path("classification").asText()) { case "Definitive" -> 0; case "Strong" -> 1; case "Moderate" -> 2; case "Limited" -> 3; default -> 4; };
    }
    private static ObjectNode preferred(List<ObjectNode> annotations) {
        if (annotations.size() == 1) return annotations.get(0);
        for (String flag : List.of("manePlusClinical", "maneSelect", "canonical")) {
            var matches = annotations.stream().filter(a -> a.path(flag).asBoolean()).toList();
            if (matches.size() == 1) return matches.get(0);
            if (matches.size() > 1) return null;
        }
        return null;
    }

    public record BackgroundSelection(String hypothesisId, String stableRelationshipKey, String annotationId) { }
    public record Selection(String candidateId, String recommendationId, String policyVersion, String policyHash,
                            String generatedAt, String stableRelationshipKey, String annotationId,
                            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                            List<BackgroundSelection> backgrounds) {
        public Selection(String candidateId, String recommendationId, String policyVersion, String policyHash,
                         String generatedAt, String stableRelationshipKey, String annotationId) {
            this(candidateId, recommendationId, policyVersion, policyHash, generatedAt, stableRelationshipKey, annotationId, null);
        }
    }

    /** Stable per-background identity; never derived from scores, order, or patient diagnosis. */
    static String backgroundId(PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis h) {
        return h.candidateId() + "-background-" + h.hgncId().replace("HGNC:", "") + "-" + h.mondoId().replace("MONDO:", "") + "-" + h.moi();
    }

    // Only the currently implemented population-only conditions authorize this preparation.
    // A future policy carrying any transcript condition must implement its own confirmed selection.
    static boolean transcriptIndependentPopulationPolicy(JsonNode policy) {
        var fields = policy.fieldNames();
        while (fields.hasNext()) if (fields.next().toLowerCase(Locale.ROOT).contains("transcript")) return false;
        return policy.has("maximumAlleleFrequency") || policy.has("maximumAlleleCount");
    }

    List<ExplicitCaseInputCoordinator.HypothesisInput> withPopulationContexts(AcmgSubmission request, ArrayNode verified) {
        var inputs = new ArrayList<>(request.hypotheses());
        if (predictionOnly) return inputs;
        if (request.recommendations() == null) return inputs;
        for (var selection : request.recommendations()) {
            if (selection.annotationId() != null && !selection.annotationId().isBlank()
                    || inputs.stream().anyMatch(h -> h != null && h.hypothesis() != null
                    && selection.candidateId().equals(h.hypothesis().candidateId()))) continue;
            for (var r : verified) {
                if (!selection.candidateId().equals(r.path("candidateId").asText())) continue;
                var preparation = r.path("pm2ContextPreparation");
                boolean frequencySelected = false;
                for (var key : r.at("/hnf1aFrequencyContextPreparation/eligibleRelationshipKeys"))
                    frequencySelected |= key.asText().equals(selection.stableRelationshipKey());
                if (frequencySelected) preparation = r.path("hnf1aFrequencyContextPreparation");
                boolean ba1Selected = false;
                if (!frequencySelected && ba1Catalog != null) for (var key : r.at("/ba1DiseaseContextPreparation/eligibleRelationshipKeys"))
                    ba1Selected |= key.asText().equals(selection.stableRelationshipKey());
                if (ba1Selected) preparation = r.path("ba1DiseaseContextPreparation");
                boolean allowed = false;
                for (var key : preparation.path("eligibleRelationshipKeys")) allowed |= key.asText().equals(selection.stableRelationshipKey());
                if (!allowed) continue;
                var relation = selectedRecord(r.path("relationships"), "stableRelationshipKey", selection.stableRelationshipKey(), "selectable");
                var tx = preparation.path("clinicalAnnotation");
                if (relation == null || !tx.isObject()) continue;
                String accession = tx.path("versionedTranscriptId").asText(); int dot = accession.lastIndexOf('.');
                if (dot <= 0) continue;
                var source = new PatientDiseaseContextBindingResolver.HypothesisProvenance(
                        frequencySelected ? "CONFIRMED_RECOMMENDATION_HNF1A_FREQUENCY" : ba1Selected ? "CONFIRMED_RECOMMENDATION_BA1_DISEASE" : "CONFIRMED_RECOMMENDATION_PM2_PREPARATION",
                        ba1Selected ? policy.path("policyVersion").asText() : VERSION,
                        "recommendations/" + selection.recommendationId() + (frequencySelected ? "/hnf1a-frequency-context" : ba1Selected ? "/ba1-disease-context" : "/pm2-context"));
                var hypothesis = new PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis(selection.candidateId(),
                        r.path("hypothesisId").asText(), relation.path("hgncId").asText(), relation.path("mondoId").asText(), relation.path("moi").asText(), source);
                var transcript = new ExplicitCaseInputCoordinator.TranscriptSelection(accession.substring(0, dot), Integer.valueOf(accession.substring(dot + 1)),
                        frequencySelected ? FREQUENCY_CONTEXT_SELECTION : ba1Selected ? BA1_CONTEXT_SELECTION : PM2_CONTEXT_SELECTION,
                        tx.path("annotationSource").asText(), tx.path("annotationSourceVersion").asText(), source,
                        frequencySelected ? "Explicit HNF1A disease-frequency confirmation authorizes BA1/BS1 and PM2 using verified clinical annotation; prediction transcript remains unconfirmed."
                                : ba1Selected ? "Explicit BA1-only disease confirmation uses the existing verified clinical annotation. Transcript applicability and all other criteria remain unconfirmed."
                                : "Server selected unique verified clinical annotation solely for transcript-independent PM2 after explicit disease relationship confirmation; prediction transcript remains unconfirmed.");
                inputs.add(new ExplicitCaseInputCoordinator.HypothesisInput(hypothesis, "UNKNOWN", transcript));
            }
        }
        if (!bs1Policies.isEmpty() && (AcmgBusinessRuleScope.BS1_DECLARATION.equals(request.declarationVersion())
                || AcmgBusinessRuleScope.UNIFIED_LOCAL_DECLARATION.equals(request.declarationVersion())
                || AcmgBusinessRuleScope.UNIFIED_ELEVEN_DECLARATION.equals(request.declarationVersion())
                || AcmgBusinessRuleScope.UNIFIED_FOURTEEN_DECLARATION.equals(request.declarationVersion()))) {
            for (var selection : request.recommendations()) {
                if (inputs.stream().anyMatch(h -> h != null && h.hypothesis() != null && selection.candidateId().equals(h.hypothesis().candidateId()))) continue;
                for (var r : verified) {
                    if (!selection.candidateId().equals(r.path("candidateId").asText())) continue;
                    boolean eligible = false;
                    for (var key : r.at("/bs1ContextPreparation/eligibleRelationshipKeys")) eligible |= key.asText().equals(selection.stableRelationshipKey());
                    if (!eligible) continue;
                    var relation = selectedRecord(r.path("relationships"), "stableRelationshipKey", selection.stableRelationshipKey(), "selectable");
                    if (relation == null) continue;
                    var source = new PatientDiseaseContextBindingResolver.HypothesisProvenance("CONFIRMED_RECOMMENDATION_BS1", "1.0.0",
                            "recommendations/" + selection.recommendationId() + "/bs1-context");
                    var hypothesis = new PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis(selection.candidateId(), r.path("hypothesisId").asText(),
                            relation.path("hgncId").asText(), relation.path("mondoId").asText(), relation.path("moi").asText(), source);
                    inputs.add(new ExplicitCaseInputCoordinator.HypothesisInput(hypothesis, "UNKNOWN", null));
                }
            }
        }
        return inputs;
    }
    private static JsonNode selectedRecord(JsonNode records, String key, String selected, String eligible) {
        if (selected == null || selected.isEmpty()) return null;
        JsonNode match = null;
        for (var record : records) if (selected.equals(record.path(key).asText())) {
            if (match != null || !record.path(eligible).asBoolean()) throw new IllegalArgumentException("RECOMMENDATION_STALE");
            match = record;
        }
        if (match == null) throw new IllegalArgumentException("RECOMMENDATION_STALE");
        return match;
    }
    private static JsonNode selectedContextAnnotation(JsonNode current, String selected, JsonNode relation) {
        JsonNode annotation = selectedRecord(current.path("annotations"), "annotationId", selected, "contextSelectable");
        if (annotation != null && !annotation.path("identityCompatible").asBoolean()
                && !annotation.path("ruleContextReady").asBoolean()
                && !annotation.path("predictionContextReady").asBoolean()
                && !(annotation.path("pm1Compatible").asBoolean() && relation != null
                && Hnf1aPm1Runtime.hasProductionPolicy(relation, PatientDiseaseContextBindingResolver.normalizeCatalogMoi(relation.path("moi").asText())))
                && !(annotation.path("ps1Pm5Compatible").asBoolean() && missensePolicyTranscript(annotation)
                && (relation == null || missenseRelationship(relation))))
            throw new IllegalArgumentException("RECOMMENDATION_STALE");
        // An incomplete declaration can retain a known annotation choice, but cannot create a context without a relationship.
        return annotation;
    }
    void verify(Selection supplied, ObjectNode current, List<ExplicitCaseInputCoordinator.HypothesisInput> hypotheses) {
        if (supplied == null) throw new IllegalArgumentException("RECOMMENDATION_STALE");
        if (supplied.backgrounds() != null) {
            // Only the unified literature-enabled workflow supports automatic multi-background declarations.
            // Keep one outer recommendation per candidate and independently validate every inner choice.
            if (!policy.at("/localBatchBinding/pp1").isObject() || supplied.backgrounds().size() > 200
                    || supplied.stableRelationshipKey() != null || supplied.annotationId() != null)
                throw new IllegalArgumentException("RECOMMENDATION_STALE");
            var selected = hypotheses.stream().filter(h -> h != null && h.hypothesis() != null
                    && Objects.equals(supplied.candidateId(), h.hypothesis().candidateId())).toList();
            var seen = new java.util.HashSet<String>();
            for (var background : supplied.backgrounds()) {
                if (background == null || background.hypothesisId() == null || !seen.add(background.hypothesisId()))
                    throw new IllegalArgumentException("RECOMMENDATION_STALE");
                var matching = selected.stream().filter(h -> background.hypothesisId().equals(h.hypothesis().hypothesisId())).toList();
                if (matching.size() != 1 || matching.get(0).hypothesis().hgncId() == null
                        || matching.get(0).hypothesis().mondoId() == null
                        || !background.hypothesisId().equals(backgroundId(matching.get(0).hypothesis())))
                    throw new IllegalArgumentException("RECOMMENDATION_STALE");
                ObjectNode scoped = current.deepCopy(); scoped.put("hypothesisId", background.hypothesisId());
                verify(new Selection(supplied.candidateId(), supplied.recommendationId(), supplied.policyVersion(), supplied.policyHash(),
                        supplied.generatedAt(), background.stableRelationshipKey(), background.annotationId()), scoped, matching);
            }
            if (selected.size() != seen.size()) throw new IllegalArgumentException("RECOMMENDATION_STALE");
            // Even an unresolved candidate must retain its current recommendation identity.
            verify(new Selection(supplied.candidateId(), supplied.recommendationId(), supplied.policyVersion(), supplied.policyHash(),
                    supplied.generatedAt(), null, null), current, List.of());
            return;
        }
        boolean valid = Objects.equals(supplied.recommendationId(), current.path("recommendationId").asText())
                && Objects.equals(supplied.candidateId(), current.path("candidateId").asText())
                && policy.path("policyVersion").asText().equals(supplied.policyVersion()) && Objects.equals(supplied.policyHash(), current.path("policyHash").asText());
        if (!valid) throw new IllegalArgumentException("RECOMMENDATION_STALE");
        // Even omitted hypotheses may carry choices; nonempty choices must resolve uniquely before any rule call.
        JsonNode relation = selectedRecord(current.path("relationships"), "stableRelationshipKey", supplied.stableRelationshipKey(), "selectable");
        JsonNode annotation = selectedContextAnnotation(current, supplied.annotationId(), relation);
        for (var input : hypotheses) {
            if (input.hypothesis() == null || !Objects.equals(supplied.candidateId(), input.hypothesis().candidateId())) continue;
            var t = input.transcript(); var h = input.hypothesis();
            // PP1 literature work can investigate an explicit exact hypothesis
            // without asserting that it is a catalog recommendation. No chosen
            // relationship may be silently replaced by this path. Existing exact
            // transcript identity is independently reusable; it grants no disease validity.
            if (policy.at("/localBatchBinding/pp1").isObject() && supplied.stableRelationshipKey() == null) {
                valid &= h.hypothesisId() != null && !h.hypothesisId().isBlank()
                        && h.hgncId() != null && h.hgncId().matches("HGNC:[1-9][0-9]*")
                        && h.mondoId() != null && h.mondoId().matches("MONDO:[0-9]{7}")
                        && PatientDiseaseContextBindingResolver.normalizeMoi(h.moi()) != null
                        && h.provenance() != null && "EXPLICIT_USER_DECLARATION".equals(h.provenance().source());
                valid &= t == null ? annotation == null : annotation != null
                        && current.path("hypothesisId").asText().equals(h.hypothesisId())
                        && transcriptMatches(supplied, annotation, t);
                continue;
            }
            // New family observations can use a confirmed gene/disease context without a transcript.
            // Other modules still enforce their own transcript requirements; old configurations keep the old contract.
            if (policy.at("/localBatchBinding/deNovo").isObject() && t == null && annotation == null
                    && (supplied.annotationId() == null || supplied.annotationId().isBlank())) {
                valid &= relation != null && current.path("hypothesisId").asText().equals(h.hypothesisId());
                if (relation != null) valid &= relation.path("hgncId").asText().equals(h.hgncId())
                        && relation.path("mondoId").asText().equals(h.mondoId()) && relation.path("moi").asText().equals(h.moi());
                continue;
            }
            valid &= relation != null && annotation != null && t != null && current.path("hypothesisId").asText().equals(h.hypothesisId());
            if (relation != null && annotation != null && t != null) valid &= relation.path("hgncId").asText().equals(h.hgncId())
                    && relation.path("mondoId").asText().equals(h.mondoId()) && relation.path("moi").asText().equals(h.moi())
                    && transcriptMatches(supplied, annotation, t);
        }
        if (!valid) throw new IllegalArgumentException("RECOMMENDATION_STALE");
    }

    private boolean transcriptMatches(Selection supplied, JsonNode annotation, ExplicitCaseInputCoordinator.TranscriptSelection t) {
        return annotation.path("versionedTranscriptId").asText().equals(t.accession() + "." + t.version())
                    && annotation.path("annotationSource").asText().equals(t.annotationSource())
                    && annotation.path("annotationSourceVersion").asText().equals(t.annotationSourceVersion())
                    && (annotation.path("predictionContextReady").asBoolean()
                            ? predictionContexts != null && predictionContexts.accepts(t.selectionPolicy())
                            : annotation.path("ruleContextReady").asBoolean()
                            ? ruleContexts != null && ruleContexts.accepts(t.selectionPolicy())
                            : (POLICY_ID + "@" + policy.path("policyVersion").asText()).equals(t.selectionPolicy()))
                    && t.source() != null && Objects.equals("recommendations/" + supplied.recommendationId() + "/selection", t.source().recordLocator());
    }
}
