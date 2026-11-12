package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Native-source preparation for any selected start-loss allele/background. This
 * does not fabricate a reviewed policy/material from database labels or model output. */
public final class Pvs1StartLossAutomaticPreparation {
    public static final String VERSION = "1.2.0";
    private static final Map<String, String> MODES = Map.of(
            "Autosomal dominant inheritance", "AUTOSOMAL_DOMINANT", "Autosomal recessive inheritance", "AUTOSOMAL_RECESSIVE",
            "X-linked dominant inheritance", "X_LINKED_DOMINANT", "X-linked recessive inheritance", "X_LINKED_RECESSIVE");
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final java.util.function.Supplier<CodingTranscriptMaterialSupply> supplyFactory;
    private final Pvs1StartLossPublicSources sources;
    private final java.util.function.Supplier<PredictionSpecializationGuard> expertSources;
    @org.springframework.lang.Nullable private final Pvs1StartLossClinVarSearch clinvarSearch;
    @org.springframework.lang.Nullable private CodingTranscriptMaterialSupply supply;
    @org.springframework.lang.Nullable private PredictionSpecializationGuard loadedExpertSources;
    @org.springframework.lang.Nullable private RuntimeException expertSourceFailure;
    @org.springframework.lang.Nullable private final Pvs1StartLossFunctionalLiterature functionalLiterature;
    @org.springframework.lang.Nullable private final TranscriptReviewSupply transcriptReviewSupply;
    @FunctionalInterface
    interface TranscriptReviewSupply {
        @org.springframework.lang.Nullable Pvs1StartLossTranscriptQualifier.Verified review(ObjectNode context,
                JsonNode verifiedModel, ObjectNode structuralReview, JsonNode expertAssertions);
    }
    private static final class QualifiedReviewHolder {
        @org.springframework.lang.Nullable private Pvs1StartLossTranscriptQualifier.Verified review;
    }
    private boolean expertSourcesAttempted;
    private final Map<String, ObjectNode> inventories = new LinkedHashMap<>();
    private final Map<String, ObjectNode> refseqInventories = new LinkedHashMap<>();
    private final ObjectNode localOriginals;

    public Pvs1StartLossAutomaticPreparation(ObjectMapper mapper,
            java.util.function.Supplier<CodingTranscriptMaterialSupply> supplyFactory, Pvs1StartLossPublicSources sources) {
        this(mapper, supplyFactory, sources, () -> null);
    }
    public Pvs1StartLossAutomaticPreparation(ObjectMapper mapper,
            java.util.function.Supplier<CodingTranscriptMaterialSupply> supplyFactory, Pvs1StartLossPublicSources sources,
            java.util.function.Supplier<PredictionSpecializationGuard> expertSources) {
        this(mapper, supplyFactory, sources, expertSources, null);
    }
    public Pvs1StartLossAutomaticPreparation(ObjectMapper mapper,
            java.util.function.Supplier<CodingTranscriptMaterialSupply> supplyFactory, Pvs1StartLossPublicSources sources,
            java.util.function.Supplier<PredictionSpecializationGuard> expertSources,
            @org.springframework.lang.Nullable Pvs1StartLossClinVarSearch clinvarSearch) {
        this(mapper, supplyFactory, sources, expertSources, clinvarSearch, null);
    }
    public Pvs1StartLossAutomaticPreparation(ObjectMapper mapper,
            java.util.function.Supplier<CodingTranscriptMaterialSupply> supplyFactory, Pvs1StartLossPublicSources sources,
            java.util.function.Supplier<PredictionSpecializationGuard> expertSources,
            @org.springframework.lang.Nullable Pvs1StartLossClinVarSearch clinvarSearch,
            @org.springframework.lang.Nullable Pvs1StartLossFunctionalLiterature functionalLiterature) {
        this(mapper, supplyFactory, sources, expertSources, clinvarSearch, functionalLiterature, null);
    }
    Pvs1StartLossAutomaticPreparation(ObjectMapper mapper,
            java.util.function.Supplier<CodingTranscriptMaterialSupply> supplyFactory, Pvs1StartLossPublicSources sources,
            java.util.function.Supplier<PredictionSpecializationGuard> expertSources,
            @org.springframework.lang.Nullable Pvs1StartLossClinVarSearch clinvarSearch,
            @org.springframework.lang.Nullable Pvs1StartLossFunctionalLiterature functionalLiterature,
            @org.springframework.lang.Nullable TranscriptReviewSupply transcriptReviewSupply) {
        this.transcriptReviewSupply = transcriptReviewSupply == null ? (context, model, structural, assertions) -> {
            var expert = expertSources();
            return expert == null ? null : Pvs1StartLossTranscriptQualifier.sourceScopedExclusion(mapper, context, model, expert, sources);
        } : transcriptReviewSupply;
        this.functionalLiterature = functionalLiterature;
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.supplyFactory = supplyFactory; this.sources = sources; localOriginals = mapper.createObjectNode();
        this.expertSources = expertSources;
        this.clinvarSearch = clinvarSearch;
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("method", "PVS1_START_LOSS_AUTOMATIC_PREPARATION").put("version", VERSION)
                .put("referenceQualificationVersion", "1.6.0-equivalent-record-review")
                .put("publishedStartLossRuleVersion", "1.1.0")
                .put("missingDiseaseInheritanceJoinVersion", "1.0.0")
                .put("genericStartLossRuleVersion", "1.1.0").put("mechanismQualificationVersion", Pvs1StartLossMechanism.VERSION)
                .put("sourceQualifiedTranscriptSupplyConfigured", transcriptReviewSupply != null)
                .put("sourceQualifiedTranscriptQualifierVersion", Pvs1StartLossTranscriptQualifier.SOURCE_QUALIFICATION_VERSION)
                .put("codingStartScopeBridgeVersion", Pvs1StartLossCodingScopeBridge.VERSION)
                .put("clinGenMechanismVersion", Pvs1StartLossClinGenMechanism.VERSION)
                .put("annotatedStartSupplyVersion", Pvs1StartLossAnnotatedStartSupply.VERSION)
                .put("createsScientificReview", false).put("policySelectionMode", "PUBLISHED_OR_SOURCE_QUALIFIED_GENERIC_OR_BOUND_BUNDLE");
        if (functionalLiterature != null) out.set("functionalLiterature", functionalLiterature.identity());
        out.set("publicSources", sources.identity()); return out;
    }
    /** Original bytes are archived ONCE per module, not duplicated for every variant/background. */
    public ObjectNode originals() {
        var out = sources.originals(); out.setAll(localOriginals.deepCopy());
        if (functionalLiterature != null) out.setAll(functionalLiterature.originals());
        return out;
    }
    public ObjectNode counts() { return sources.counts(); }

    @org.springframework.lang.Nullable
    private PredictionSpecializationGuard expertSources() {
        if (!expertSourcesAttempted) {
            expertSourcesAttempted = true;
            try { loadedExpertSources = expertSources.get(); }
            catch (RuntimeException unavailable) { expertSourceFailure = unavailable; }
        }
        var failure = expertSourceFailure;
        if (failure != null) throw failure;
        return loadedExpertSources;
    }

    static final class Prepared {
        private final ObjectNode preparation;
        @org.springframework.lang.Nullable private final ObjectNode decision;
        private final boolean sourceQualifiedConflict;
        private Prepared(ObjectNode preparation, @org.springframework.lang.Nullable ObjectNode decision) {
            this(preparation, decision, false);
        }
        private Prepared(ObjectNode preparation, @org.springframework.lang.Nullable ObjectNode decision, boolean sourceQualifiedConflict) {
            this.preparation = preparation.deepCopy(); this.decision = decision == null ? null : decision.deepCopy();
            this.sourceQualifiedConflict = sourceQualifiedConflict;
        }
        ObjectNode preparation() { return preparation.deepCopy(); }
        @org.springframework.lang.Nullable ObjectNode decision() { return decision == null ? null : decision.deepCopy(); }
        boolean sourceQualifiedConflict() { return sourceQualifiedConflict; }
    }

    /** No public JSON-to-qualified-result entry. Acquire and verify the bound
     * original sources inside the same call before constructing this result. */
    Prepared prepareForEvaluation(ObjectNode context, VariantInterpretation candidate) {
        return prepareForEvaluation(context, candidate, null);
    }

    Prepared prepareForEvaluation(ObjectNode context, VariantInterpretation candidate,
            @org.springframework.lang.Nullable ReviewedRuleBundle bundle) {
        var qualified = new QualifiedReviewHolder();
        var prepared = prepare(context, candidate, mapper.createObjectNode(), bundle, qualified);
        var sourceReview = qualified.review;
        if (sourceReview != null && sourceReview.conflicting()) return new Prepared(prepared, null, true);
        if ("PUBLISHED_RULE_FACTS_QUALIFIED".equals(prepared.path("status").asText())
                && "QUALIFIED".equals(prepared.at("/publishedRuleCheck/status").asText()))
            return new Prepared(prepared, publishedDecision(context, prepared));
        if (bundle != null) {
            var mechanism = bundle.startLossMechanism(context);
            if (!"CURATED_MECHANISM_SUPPORTED".equals(mechanism.path("status").asText())
                    && !"CONFLICT".equals(mechanism.path("status").asText()) && "AUTOSOMAL_DOMINANT".equals(context.path("moi").asText())
                    && "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET".equals(prepared.at("/transcriptReview/status").asText())
                    && prepared.at("/transcriptReview/complete").asBoolean()) {
                var independent = Pvs1StartLossClinGenMechanism.assess(mapper, context,
                        sources.clinGenMechanism(Pvs1StartLossClinGenMechanism.DOSAGE_URL),
                        sources.clinGenMechanism(Pvs1StartLossClinGenMechanism.VALIDITY_URL),
                        sources.clinGenMechanism(Pvs1StartLossClinGenMechanism.DISEASE_DOSAGE_URL),
                        bundle.startLossAssociation(context));
                mechanism = Pvs1StartLossMechanism.withClinGen(mapper, context, mechanism, independent);
            }
            prepared.set("mechanismAssessment", mechanism);
            var generic = genericDecision(context, prepared, qualified.review);
            if (generic != null) return new Prepared(prepared, generic);
        }
        if (!"AVAILABLE".equals(prepared.at("/sequence/status").asText())
                || !"FOUND_FUNCTIONAL_ALTERNATIVE".equals(prepared.at("/transcriptReview/status").asText()))
            return new Prepared(prepared, null);
        var entries = prepared.at("/expertAssertions/entries");
        if (!entries.isArray() || entries.isEmpty()) return new Prepared(prepared, null);
        for (var entry : entries) if (!"QUALIFIED".equals(entry.path("status").asText())
                || !"PROHIBITED".equals(entry.path("policyDisposition").asText())) return new Prepared(prepared, null);
        String reason = "PVS1_START_LOSS_PUBLISHED_ALTERNATIVE_START_EXCLUSION";
        var result = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1").put("classificationEnabled", false)
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("providerCalls", 0).put("evaluatorCalls", 1);
        var facts = result.putObject("expertStartLossFacts").put("schemaVersion", "pvs1-start-loss-expert-facts-v1")
                .put("methodVersion", VERSION).put("contextSha256", Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, context))
                .put("decisionOutcome", "NOT_APPLICABLE").put("decisionStrength", "NONE").put("reasonCode", reason);
        facts.set("sequence", prepared.path("sequence").deepCopy());
        facts.set("expertAssertions", prepared.path("expertAssertions").deepCopy());
        var evaluation = result.putObject("evaluation").put("criterionId", "ACMG_AMP_2015:PVS1").put("appliedStrength", "NONE");
        evaluation.putObject("criterionApplicability").put("status", "NOT_APPLICABLE").put("reasonCode", reason);
        evaluation.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        evaluation.putObject("deterministicTrace").put("executionStatus", "COMPLETED").put("evaluatorId", "pvs1-start-loss-published-exclusion")
                .put("evaluatorVersion", VERSION).put("factsSha256", adapter.canonicalSha256(facts));
        return new Prepared(prepared, result);
    }

    /** Only called with this invocation's acquired facts and the trusted bundle's
     * mechanism reader. No public JSON-to-qualified-decision entry. The structural
     * negative route proves that every assessed coding start is affected; it does
     * not infer that an untested different-start isoform lacks function. */
    @org.springframework.lang.Nullable
    private ObjectNode genericDecision(ObjectNode context, ObjectNode prepared,
            @org.springframework.lang.Nullable Pvs1StartLossTranscriptQualifier.Verified qualified) {
        var expert = prepared.path("expertAssertions");
        var mechanism = prepared.path("mechanismAssessment");
        var transcripts = prepared.path("transcriptReview");
        var upstream = prepared.path("upstreamReview");
        var sequence = prepared.path("sequence");
        if (!Set.of("SYNTHETIC", "PRIVACY_BOUNDED_RUNTIME").contains(context.path("dataClass").asText())) return null;
        for (var issue : prepared.path("issues"))
            if (!"PVS1_START_LOSS_POLICY_UNRESOLVED".equals(issue.asText())) return null;
        boolean sourcePositive = qualified != null && qualified.functionalAlternativeFound();
        boolean sourceNegative = qualified != null && qualified.completeNegative();
        boolean noSpecialist = "NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT".equals(expert.path("status").asText())
                && expert.path("entries").isArray() && expert.path("entries").isEmpty();
        boolean explicitSourceExclusion = sourcePositive && qualified != null && resolvedExplicitExclusion(qualified, expert);
        if ((!noSpecialist && !explicitSourceExclusion) || !"AVAILABLE".equals(sequence.path("status").asText())) return null;
        if (sourcePositive) {
            if (!"SOURCE_TRANSCRIPT_FACTS_QUALIFIED".equals(prepared.path("status").asText())) return null;
            return automaticDecision(context, prepared, new Pvs1StartLossDecision.Result(
                    Pvs1StartLossDecision.Outcome.NOT_APPLICABLE, "NONE", "PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT"), qualified);
        }
        if ("PRIVACY_BOUNDED_RUNTIME".equals(context.path("dataClass").asText())
                && !"PUBLIC_REFERENCE".equals(mechanism.at("/binding/sourceDataClass").asText())) return null;
        boolean structuralNegative = "COMPLETE_STRUCTURAL_SOURCE_SET_NO_DIFFERENT_START".equals(transcripts.path("qualificationBasis").asText())
                && transcripts.path("sourceEnumerationComplete").asBoolean() && transcripts.path("selectedTranscriptPresent").asBoolean();
        if (!"INCOMPLETE".equals(prepared.path("status").asText())
                || !"CURATED_MECHANISM_SUPPORTED".equals(mechanism.path("status").asText())
                || !Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, context).equals(mechanism.path("contextSha256").asText())
                || !(sourceNegative || structuralNegative)) return null;
        boolean witness = "WITNESS_VALIDATED".equals(upstream.path("status").asText())
                && upstream.path("qualifyingReferenceCount").asInt() > 0;
        boolean negative = "NO_QUALIFYING_REFERENCE_WITHIN_SCOPE".equals(upstream.path("status").asText())
                && upstream.path("complete").asBoolean() && upstream.path("negativeCoverageGranted").asBoolean()
                && upstream.at("/coverage/complete").asBoolean();
        var decision = Pvs1StartLossDecision.evaluate(new Pvs1StartLossDecision.Facts(
                sequence.path("status").asText(), "PERMITS", "ESTABLISHED_LOF", transcripts.path("status").asText(),
                transcripts.path("complete").asBoolean(), sequence.path("candidateRestartCdsPosition").asInt(),
                witness ? "QUALIFYING_FOUND" : negative ? "COMPLETE" : "INCOMPLETE", negative, witness));
        if (decision.outcome() != Pvs1StartLossDecision.Outcome.MET) return null;
        return automaticDecision(context, prepared, decision, qualified);
    }

    /** A typed replay may repair only an exact-transcript scope mismatch for a
     * standalone source exclusion. It cannot authorize generic fallback from an
     * unimplemented, conditional or missing specialist rule. */
    private static boolean expertScopeBridgeCandidate(JsonNode assertions) {
        var entries = assertions.path("entries");
        if (!entries.isArray() || entries.isEmpty()) return false;
        for (var entry : entries) if (!"UNRESOLVED".equals(entry.path("status").asText())
                || !"EXPERT_EXACT_TRANSCRIPT_SCOPE_UNRESOLVED".equals(entry.path("reasonCode").asText())) return false;
        return true;
    }

    private static boolean resolvedExplicitExclusion(Pvs1StartLossTranscriptQualifier.Verified verified, JsonNode assertions) {
        var evidence = verified.snapshot();
        if (!verified.functionalAlternativeFound() || !"EXPLICIT_SOURCE_START_LOSS_EXCLUSION".equals(evidence.path("qualificationBasis").asText())
                || !"PROHIBITED".equals(evidence.path("policyDisposition").asText()) || !expertScopeBridgeCandidate(assertions)) return false;
        Set<String> keys = new java.util.HashSet<>();
        for (var key : evidence.path("expertSpecificationKeys")) if (!key.isTextual() || key.asText().isBlank() || !keys.add(key.asText())) return false;
        if (keys.isEmpty()) return false;
        Set<String> observed = new java.util.HashSet<>();
        for (var entry : assertions.path("entries")) {
            String key = entry.path("specificationKey").asText(), sha = entry.at("/source/rawSha256").asText();
            if (!keys.contains(key) || !sha.matches("[a-f0-9]{64}")) return false;
            boolean sourceBound = false;
            for (var binding : evidence.path("sourceBindings")) sourceBound |= sha.equals(binding.path("sourceSha256").asText());
            if (!sourceBound) return false;
            observed.add(key);
        }
        return observed.equals(keys);
    }

    @org.springframework.lang.Nullable
    private ObjectNode automaticDecision(ObjectNode context, ObjectNode prepared, Pvs1StartLossDecision.Result decision,
            @org.springframework.lang.Nullable Pvs1StartLossTranscriptQualifier.Verified qualified) {
        if (!prepared.path("verifiedModelSha256").asText().matches("[0-9a-f]{64}")) return null;
        boolean met = decision.outcome() == Pvs1StartLossDecision.Outcome.MET;
        String outcome = met ? "MET" : "NOT_APPLICABLE";
        prepared.put("status", "GENERIC_FACTS_QUALIFIED"); prepared.withArray("issues").removeAll();
        prepared.withObject("policySelection").put("outcome", "AUTOMATIC_GENERIC_START_LOSS")
                .put("reasonCode", decision.reasonCode()).put("automaticFallbackPerformed", false);
        var result = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1").put("classificationEnabled", false)
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("providerCalls", 0).put("evaluatorCalls", 1);
        var facts = result.putObject("automaticStartLossFacts").put("schemaVersion", "pvs1-start-loss-automatic-facts-v1")
                .put("methodVersion", qualified == null ? "1.0.0" : "1.1.0").put("contextSha256", prepared.path("contextSha256").asText())
                .put("decisionOutcome", outcome).put("decisionStrength", decision.strength()).put("reasonCode", decision.reasonCode())
                .put("verifiedModelSha256", prepared.path("verifiedModelSha256").asText())
                .put("ruleSource", "https://pubmed.ncbi.nlm.nih.gov/30192042/")
                .put("rule", "CLINGEN_SVI_START_LOSS_2018_ASSESSED_SOURCE_SET")
                .put("doubleCountingConstraint", "NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE");
        facts.set("sequence", prepared.path("sequence").deepCopy());
        if (qualified != null) {
            var assessment = facts.putObject("sourceQualifiedTranscriptAssessment").put("method", "INTERNAL_SOURCE_REPLAY")
                    .put("contextSha256", qualified.contextSha256()).put("functionalAlternativeFound", qualified.functionalAlternativeFound())
                    .put("completeNegative", qualified.completeNegative());
            assessment.set("evidence", qualified.snapshot());
        }
        facts.set("preparation", publicSummary(mapper, prepared));
        var evaluation = result.putObject("evaluation").put("criterionId", "ACMG_AMP_2015:PVS1").put("appliedStrength", decision.strength());
        evaluation.putObject("criterionApplicability").put("status", met ? "APPLICABLE" : "NOT_APPLICABLE").put("reasonCode", decision.reasonCode());
        evaluation.putObject("evaluationResult").put("status", met ? "MET" : "NOT_EVALUATED").put("reasonCode", decision.reasonCode());
        evaluation.putObject("deterministicTrace").put("executionStatus", "COMPLETED").put("evaluatorId", "pvs1-start-loss-automatic-generic")
                .put("evaluatorVersion", qualified == null ? "1.0.0" : "1.1.0").put("factsSha256", adapter.canonicalSha256(facts));
        return result;
    }

    private ObjectNode publishedDecision(ObjectNode context, ObjectNode prepared) {
        String strength = prepared.at("/publishedRuleCheck/strength").asText();
        String reason = "PVS1_START_LOSS_EXPLICIT_PUBLISHED_RULE";
        var result = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1").put("classificationEnabled", false)
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("providerCalls", 0).put("evaluatorCalls", 1);
        var facts = result.putObject("expertStartLossFacts").put("schemaVersion", "pvs1-start-loss-expert-facts-v2")
                .put("methodVersion", "1.3.0").put("contextSha256", Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, context))
                .put("decisionOutcome", "MET").put("decisionStrength", strength).put("reasonCode", reason)
                .put("doubleCountingConstraint", "NO_AUTOMATIC_COMBINATION_OF_PVS1_WITH_OTHER_EFFECT_EVIDENCE");
        for (String key : List.of("sequence", "expertAssertions", "publishedRuleCheck")) facts.set(key, prepared.path(key).deepCopy());
        var evaluation = result.putObject("evaluation").put("criterionId", "ACMG_AMP_2015:PVS1").put("appliedStrength", strength);
        evaluation.putObject("criterionApplicability").put("status", "APPLICABLE").put("reasonCode", reason);
        evaluation.putObject("evaluationResult").put("status", "MET").put("reasonCode", reason);
        evaluation.putObject("deterministicTrace").put("executionStatus", "COMPLETED").put("evaluatorId", "pvs1-start-loss-explicit-published-rule")
                .put("evaluatorVersion", "1.0.0").put("factsSha256", adapter.canonicalSha256(facts));
        return result;
    }

    public ObjectNode prepare(ObjectNode context, VariantInterpretation candidate, JsonNode existingResult) {
        return prepare(context, candidate, existingResult, null, null);
    }

    private ObjectNode prepare(ObjectNode context, VariantInterpretation candidate, JsonNode existingResult,
            @org.springframework.lang.Nullable ReviewedRuleBundle bundle, @org.springframework.lang.Nullable QualifiedReviewHolder qualified) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-automatic-preparation-v1")
                .put("methodVersion", VERSION).put("runtimeEligible", false).put("scientificEvaluationPerformed", false)
                .put("contextSha256", Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, context));
        var issues = out.putArray("issues");
        var selection = out.putObject("policySelection");
        selection.put("outcome", existingResult.at("/policySelection/outcome").asText("UNAVAILABLE"))
                .put("reasonCode", existingResult.at("/policySelection/reasonCode").asText("PVS1_START_LOSS_POLICY_UNRESOLVED"))
                .put("policyId", existingResult.at("/policy/policyId").asText())
                .put("policySchema", existingResult.at("/policy/schemaVersion").asText())
                .put("automaticFallbackPerformed", false);
        try {
            adapter.validateCompleteContext(context);
            if (!Pvs1StartLossResolver.selectedStartLoss(context, candidate)) return out.put("status", "NOT_REQUIRED");
            if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("PVS1_START_LOSS_AUTO_INTERRUPTED");
            var currentSupply = supply;
            if (currentSupply == null) { currentSupply = java.util.Objects.requireNonNull(supplyFactory.get(), "PVS1_START_LOSS_AUTO_STRUCTURE_UNAVAILABLE"); supply = currentSupply; }
            var material = currentSupply.resolveStructure(context, candidate);
            out.set("sequenceSupply", material.snapshot());
            var model = material.model();
            if (model == null) throw new IllegalArgumentException(material.snapshot().path("reasonCode").asText("PVS1_START_LOSS_AUTO_STRUCTURE_UNAVAILABLE"));
            var annotation = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            if (annotation == null) throw new IllegalArgumentException("PVS1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            ObjectNode sequence = Pvs1StartLossResolver.sequence(mapper, context, annotation, model);
            out.set("sequence", sequence);
            if (!"AVAILABLE".equals(sequence.path("status").asText())) {
                issues.add(sequence.path("reasonCode").asText()); return out.put("status", sequence.path("status").asText());
            }
            String verifiedModel = mapper.writeValueAsString(model), verifiedModelSha = CodingTranscriptMaterialSupply.sha(verifiedModel);
            localOriginals.putObject(verifiedModelSha).put("rawJson", verifiedModel).put("kind", "REFERENCE_VERIFIED_CODING_MODEL");
            out.put("verifiedModelSha256", verifiedModelSha);
            try {
                var expert = expertSources();
                if (expert != null) {
                    var sourceContext = Pvs1StartLossPublishedRule.sourceContext(context, model);
                    var assertions = expert.startLossAssertions(mapper, sourceContext,
                            bundle == null ? null : bundle.startLossDiseaseScope(sourceContext));
                    if (!sourceContext.path("transcript").equals(context.path("transcript"))) {
                        var bridge = assertions.putObject("transcriptBinding").put("method", "EXACT_FULL_TRANSCRIPT_EQUIVALENCE")
                                .put("verifiedModelSha256", verifiedModelSha).put("executionContextUnchanged", true);
                        bridge.set("selectedTranscript", context.path("transcript").deepCopy());
                        bridge.set("sourceTranscript", sourceContext.path("transcript").deepCopy());
                    }
                    for (var item : assertions.path("sourceDocuments").properties())
                        localOriginals.set(item.getKey(), item.getValue().deepCopy());
                    assertions.remove("sourceDocuments"); out.set("expertAssertions", assertions);
                }
            } catch (RuntimeException unavailable) {
                out.putObject("expertAssertions").put("status", "UNAVAILABLE").put("reasonCode", "EXPERT_SOURCE_UNAVAILABLE");
            }
            var published = Pvs1StartLossPublishedRule.resolve(mapper, out.path("expertAssertions"), sequence, model);
            out.set("publishedRuleCheck", published);
            if ("QUALIFIED".equals(published.path("status").asText())) {
                String rawModel = mapper.writeValueAsString(model), modelSha = CodingTranscriptMaterialSupply.sha(rawModel);
                localOriginals.putObject(modelSha).put("rawJson", rawModel).put("kind", "REFERENCE_VERIFIED_CODING_MODEL");
                published.put("verifiedModelSha256", modelSha);
                out.putObject("transcriptReview").put("status", "NOT_REQUIRED").put("complete", false)
                        .put("reasonCode", "PUBLISHED_RULE_SEPARATE_GENERIC_TRANSCRIPT_REVIEW_NOT_REQUIRED");
                out.putObject("upstreamReview").put("status", "NOT_REQUIRED").put("complete", false)
                        .put("reasonCode", "PUBLISHED_RULE_SEPARATE_GENERIC_UPSTREAM_REVIEW_NOT_REQUIRED");
                selection.put("outcome", "EXPLICIT_PUBLISHED_RULE").put("reasonCode", "PVS1_START_LOSS_EXPLICIT_PUBLISHED_RULE");
                return out.put("status", "PUBLISHED_RULE_FACTS_QUALIFIED");
            }
            if (expertScopeBridgeCandidate(out.path("expertAssertions"))) {
                out.putObject("transcriptReview").put("status", "UNRESOLVED").put("complete", false)
                        .put("reasonCode", "EXPERT_EXACT_TRANSCRIPT_SCOPE_UNRESOLVED");
                var early = qualifyTranscriptReview(context, model, out);
                if (early != null && (early.conflicting() || resolvedExplicitExclusion(early, out.path("expertAssertions")))) {
                    if (qualified != null) qualified.review = early;
                    out.putObject("upstreamReview").put("status", "NOT_REQUIRED").put("complete", false)
                            .put("reasonCode", early.conflicting() ? "PVS1_START_LOSS_EVIDENCE_CONFLICT" : "PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT");
                    return out.put("status", early.conflicting() ? "CONFLICT" : "SOURCE_TRANSCRIPT_FACTS_QUALIFIED");
                }
            }
            try {
            String inventoryKey = context.at("/gene/hgncId").asText() + ":" + context.at("/variant/evidenceQueryAllele/assembly").asText();
            ObjectNode inventory = inventories.get(inventoryKey);
            if (inventory == null) {
                inventory = currentSupply.startLossInventory(context);
                inventory = Pvs1StartLossAnnotatedStartSupply.supplement(mapper, context, inventory, sources, currentSupply);
                // Inventory is derived from pinned raw GTF rows, not a functional assertion source.
                inventories.put(inventoryKey, inventory.deepCopy());
            }
            String raw = mapper.writeValueAsString(inventory), sha = CodingTranscriptMaterialSupply.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            localOriginals.putObject(sha).put("rawJson", raw).put("kind", "PINNED_GENCODE_DERIVED_INVENTORY");
            out.set("transcriptReview", transcripts(mapper, context, model, inventory, sha));
            } catch (Exception missingInventory) {
                out.putObject("transcriptReview").put("status", "UNAVAILABLE").put("complete", false)
                        .put("reasonCode", "PVS1_START_LOSS_AUTO_TRANSCRIPT_INVENTORY_UNAVAILABLE");
            }
            out.set("transcriptReview", Pvs1StartLossTranscriptQualifier.withExpertAssertions(
                    (ObjectNode) out.path("transcriptReview"), out.path("expertAssertions")));
            if ("FOUND_FUNCTIONAL_ALTERNATIVE".equals(out.at("/transcriptReview/status").asText())) {
                out.putObject("upstreamReview").put("status", "NOT_REQUIRED").put("complete", false)
                        .put("reasonCode", "EXPERT_ALTERNATIVE_START_EXCLUSION");
                return out.put("status", "EXPERT_TRANSCRIPT_FACTS_QUALIFIED");
            }
            String refseqKey = context.at("/gene/hgncId").asText() + ":" + model.path("contig").asText()
                    + ":" + context.at("/variant/normalization/referenceSequenceSha256").asText();
            ObjectNode refseq = refseqInventories.get(refseqKey);
            if (refseq == null) {
                String symbol = context.at("/gene/symbol").asText();
                refseq = Pvs1StartLossRefSeqInventory.read(mapper, context, sources.refseqReport(symbol, false), sources.refseqReport(symbol, true));
                refseq = Pvs1StartLossRefSeqSequenceVerifier.verify(context, refseq, sources, currentSupply);
                refseqInventories.put(refseqKey, refseq.deepCopy());
            }
            String refseqRaw = mapper.writeValueAsString(refseq);
            String refseqSha = CodingTranscriptMaterialSupply.sha(refseqRaw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            localOriginals.putObject(refseqSha).put("rawJson", refseqRaw).put("kind", "DERIVED_REFSEQ_TRANSCRIPT_INVENTORY");
            var refseqSummary = refseq.deepCopy(); refseqSummary.remove("transcripts");
            refseqSummary.put("transcriptCount", refseq.path("transcripts").size()).put("inventorySha256", refseqSha);
            out.set("refseqInventory", refseqSummary);
            var combined = Pvs1StartLossTranscriptQualifier.combineStructural((ObjectNode) out.path("transcriptReview"),
                    transcripts(mapper, context, model, refseq, refseqSha));
            out.set("transcriptReview", Pvs1StartLossTranscriptQualifier.withExpertAssertions(combined, out.path("expertAssertions")));
            var sourceQualified = qualifyTranscriptReview(context, model, out);
            if (qualified != null) qualified.review = sourceQualified;
            if (sourceQualified != null && sourceQualified.conflicting()) {
                out.putObject("upstreamReview").put("status", "NOT_REQUIRED").put("complete", false)
                        .put("reasonCode", "PVS1_START_LOSS_EVIDENCE_CONFLICT");
                return out.put("status", "CONFLICT");
            }
            if (sourceQualified != null && sourceQualified.functionalAlternativeFound()) {
                out.putObject("upstreamReview").put("status", "NOT_REQUIRED").put("complete", false)
                        .put("reasonCode", "PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT");
                return out.put("status", "SOURCE_TRANSCRIPT_FACTS_QUALIFIED");
            }
            attachFunctionalReading(context, out);
            if (clinvarSearch != null) {
                var scan = clinvarSearch.query(context, model, sequence.path("candidateRestartCdsPosition").asInt());
                String original = mapper.writeValueAsString(scan);
                String digest = CodingTranscriptMaterialSupply.sha(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                localOriginals.putObject(digest).put("rawJson", original).put("kind", "LOCAL_CLINVAR_INTERVAL_QUERY");
                scan.put("sourceSha256", digest); out.set("clinvarSearch", scan);
            }
            out.set("upstreamReview", upstreamWithCoverage(context, model, sequence, out.path("clinvarSearch")));
            if (!"NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET".equals(out.at("/transcriptReview/status").asText()))
                issues.add("PVS1_START_LOSS_AUTO_FUNCTIONAL_TRANSCRIPT_COVERAGE_UNRESOLVED");
            if (!out.at("/upstreamReview/complete").asBoolean() && !"WITNESS_VALIDATED".equals(out.at("/upstreamReview/status").asText()))
                issues.add("PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
            if (!Pvs1StartLossPolicy.SCHEMA.equals(existingResult.at("/policy/schemaVersion").asText()))
                issues.add("PVS1_START_LOSS_POLICY_UNRESOLVED");
            return out.put("status", "INCOMPLETE");
        } catch (Exception unavailable) {
            String reason = unavailable.getMessage();
            issues.add(reason != null && reason.matches("[A-Z][A-Z0-9_]{1,120}") ? reason : "PVS1_START_LOSS_AUTO_PREPARATION_UNAVAILABLE");
            return out.put("status", "UNAVAILABLE");
        }
    }

    @org.springframework.lang.Nullable
    private Pvs1StartLossTranscriptQualifier.Verified qualifyTranscriptReview(ObjectNode context, JsonNode model, ObjectNode prepared) {
        var supply = transcriptReviewSupply;
        if (supply == null || !(prepared.path("transcriptReview") instanceof ObjectNode structural)
                || !"UNRESOLVED".equals(structural.path("status").asText())) return null;
        try {
            var verified = supply.review(context.deepCopy(), model.deepCopy(), structural.deepCopy(), prepared.path("expertAssertions").deepCopy());
            if (verified == null) return null;
            var evidence = verified.snapshot();
            String expectedStatus = verified.conflicting() ? "CONFLICT" : verified.functionalAlternativeFound()
                    ? "FOUND_FUNCTIONAL_ALTERNATIVE" : "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET";
            int outcomes = (verified.functionalAlternativeFound() ? 1 : 0) + (verified.completeNegative() ? 1 : 0) + (verified.conflicting() ? 1 : 0);
            boolean sourceBindingsValid = evidence.path("sourceBindings").isArray() && !evidence.path("sourceBindings").isEmpty()
                    && evidence.path("sourceBindings").size() <= 32;
            for (var binding : evidence.path("sourceBindings")) sourceBindingsValid &= !binding.path("role").asText().isBlank()
                    && binding.path("sourceSha256").asText().matches("[a-f0-9]{64}") && !binding.path("sourceLocator").asText().isBlank()
                    && binding.path("url").asText().startsWith("https://");
            if (!Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, context).equals(verified.contextSha256()) || outcomes != 1
                    || !"pvs1-start-loss-source-qualified-transcripts-v1".equals(evidence.path("schemaVersion").asText())
                    || !verified.contextSha256().equals(evidence.path("contextSha256").asText())
                    || !expectedStatus.equals(evidence.path("status").asText()) || !evidence.path("complete").isBoolean()
                    || evidence.path("complete").asBoolean() != verified.completeNegative() || !sourceBindingsValid) {
                structural.putObject("sourceQualification").put("status", "UNAVAILABLE")
                        .put("reasonCode", "PVS1_START_LOSS_TRANSCRIPT_ASSESSMENT_BINDING_INVALID");
                return null;
            }
            structural.set("sourceQualifiedAssessment", evidence);
            structural.put("status", verified.conflicting() ? "CONFLICT" : verified.functionalAlternativeFound() ? "FOUND_FUNCTIONAL_ALTERNATIVE" : "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET")
                    .put("complete", verified.completeNegative()).put("qualificationBasis", "SOURCE_QUALIFIED_FUNCTIONAL_TRANSCRIPT_REVIEW")
                    .put("reasonCode", verified.conflicting() ? "PVS1_START_LOSS_EVIDENCE_CONFLICT" : verified.functionalAlternativeFound() ? "PVS1_START_LOSS_FUNCTIONAL_ALTERNATIVE_TRANSCRIPT"
                            : "PVS1_START_LOSS_SOURCE_QUALIFIED_NO_FUNCTIONAL_ALTERNATIVE");
            return verified;
        } catch (RuntimeException unavailable) {
            structural.putObject("sourceQualification").put("status", "UNAVAILABLE")
                    .put("reasonCode", "PVS1_START_LOSS_TRANSCRIPT_QUALIFICATION_UNAVAILABLE");
            return null;
        }
    }

    /** Supplement unresolved reviews; never replace structural/expert decisions. */
    void attachFunctionalReading(JsonNode context, ObjectNode prepared) {
        var reader = functionalLiterature;
        var review = prepared.path("transcriptReview");
        if (reader == null || !(review instanceof ObjectNode transcriptReview)
                || java.util.Set.of("FOUND_FUNCTIONAL_ALTERNATIVE", "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET", "NOT_REQUIRED")
                        .contains(review.path("status").asText())) return;
        transcriptReview.set("functionalLiterature", reader.review(context.at("/gene/symbol").asText(), transcriptReview));
    }

    static ObjectNode transcripts(ObjectMapper mapper, JsonNode context, JsonNode model, JsonNode inventory, String sha) {
        return Pvs1StartLossTranscriptQualifier.structural(mapper, context, model, inventory, sha);
    }

    ObjectNode upstreamWithCoverage(ObjectNode context, ObjectNode model, ObjectNode sequence, JsonNode scan) {
        int restart = sequence.path("candidateRestartCdsPosition").asInt();
        var upstream = upstream(context, model, sequence);
        var reconciled = Pvs1StartLossUpstreamCoverage.reconcile(context, model, restart, upstream, scan, sources);
        if (scan instanceof ObjectNode local) {
            if ("WITNESS_VALIDATED".equals(reconciled.path("status").asText())) {
                local.putObject("candidateReview").put("status", "NOT_REQUIRED").put("complete", false)
                        .put("reasonCode", "CLINVAR_WITNESS_VALIDATED_NEGATIVE_ENUMERATION_NOT_REQUIRED");
            } else {
                local.set("candidateReview", Pvs1StartLossClinVarCandidates.review(mapper, context, model, restart, local, sources));
                reconciled = Pvs1StartLossUpstreamCoverage.reconcile(context, model, restart, upstream, local, sources);
            }
        }
        return reconciled;
    }
    static List<Integer> startPositions(JsonNode row) {
        if (!row.path("exons").isArray() || row.path("exons").isEmpty() || row.path("exons").size() > 1000
                || !row.path("strand").isIntegralNumber() || Math.abs(row.path("strand").asInt()) != 1) throw new IllegalArgumentException();
        List<Integer> positions = new ArrayList<>();
        for (int cds = 1; cds <= 3; cds++) {
            Integer position = null;
            for (var exon : row.path("exons")) {
                for (String key : List.of("cdsStart", "cdsEnd", "genomicStart", "genomicEnd"))
                    if (!exon.path(key).isIntegralNumber() || !exon.path(key).canConvertToInt()) throw new IllegalArgumentException();
                if (exon.path("cdsStart").asInt() <= cds && exon.path("cdsEnd").asInt() >= cds) {
                    if (position != null) throw new IllegalArgumentException();
                    long p = row.path("strand").asInt() == 1
                            ? (long) exon.path("genomicStart").asInt() + cds - exon.path("cdsStart").asInt()
                            : (long) exon.path("genomicEnd").asInt() - cds + exon.path("cdsStart").asInt();
                    if (p < 1 || p > Integer.MAX_VALUE || p < exon.path("genomicStart").asInt() || p > exon.path("genomicEnd").asInt())
                        throw new IllegalArgumentException();
                    position = (int) p;
                }
            }
            if (position == null) throw new IllegalArgumentException();
            positions.add(position);
        }
        return positions;
    }

    ObjectNode upstream(ObjectNode context, ObjectNode model, ObjectNode sequence) {
        var out = mapper.createObjectNode().put("status", "INCOMPLETE").put("complete", false)
                .put("searchScope", "EREPO_GENE_SUMMARY_AND_EXACT_VERSIONED_TRANSCRIPT_SMALL_EDIT")
                .put("negativeCoverageGranted", false);
        var records = out.putArray("records");
        int restart = sequence.path("candidateRestartCdsPosition").asInt();
        if (restart <= 3) return out.put("reasonCode", "PVS1_START_LOSS_RESTART_CANDIDATE_UNRESOLVED");
        out.put("cdsStart", 1).put("cdsEnd", restart - 1);
        var snapshot = sources.genePages(model.path("geneSymbol").asText());
        out.set("source", snapshot.receipt());
        out.put("sourceEnumerationComplete", snapshot.receipt().path("sourceEnumerationComplete").asBoolean());
        JsonNode document = snapshot.document();
        if (!"AVAILABLE".equals(snapshot.receipt().path("status").asText()))
            return out.put("status", "UNAVAILABLE").put("reasonCode", snapshot.receipt().path("reasonCode").asText());
        if (document.at("/status/code").asInt() != 200 || !document.path("data").isArray() || document.path("data").size() > 10000)
            return out.put("status", "UNAVAILABLE").put("reasonCode", "PVS1_START_LOSS_AUTO_SOURCE_SCHEMA_INVALID");
        // Two stable traversals establish only this source's enumeration. They do
        // not establish interval mapping or independent pathogenicity of its records.
        out.put("reasonCode", "PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
        PredictionSpecializationGuard referenceSpecifications = null;
        try { referenceSpecifications = expertSources(); } catch (RuntimeException unavailable) { /* Keep each source failure explicit below. */ }
        Map<String, List<ObjectNode>> byAllele = new LinkedHashMap<>();
        int index = 0;
        for (var row : document.path("data")) {
            var record = records.addObject().put("sourceSha256", snapshot.receipt().path("sha256").asText())
                    .put("sourceLocator", "/data/" + index++).put("recordId", row.path("uuid").asText())
                    .put("clinvarVariationId", row.path("cvId").asText())
                    .put("classification", row.path("classification").asText()).put("disposition", "UNRESOLVED");
            if (!row.path("gene").equals(model.path("geneSymbol"))) { record.put("reasonCode", "GENE_MISMATCH"); continue; }
            String sourceMoi = MODES.get(row.path("moi").asText());
            boolean backgroundResolved = row.path("mondoId").asText().matches("MONDO:[0-9]{7}") && sourceMoi != null;
            record.put("sourceBackgroundResolved", backgroundResolved);
            if (backgroundResolved && (!row.path("mondoId").equals(context.at("/disease/mondoId")) || !context.path("moi").asText().equals(sourceMoi))) {
                record.put("disposition", "EXCLUDED").put("reasonCode", "BACKGROUND_MISMATCH"); continue;
            }
            Pvs1StartLossReferenceMapping.Result mapping = null;
            String mappingIssue = "";
            var descriptions = record.putArray("sourceHgvs");
            if (row.path("hgvs").isArray() && row.path("hgvs").size() <= 1000) for (var hgvs : row.path("hgvs")) {
                if (!hgvs.isTextual() || !hgvs.asText().startsWith(model.path("transcript").asText() + ":")) continue;
                descriptions.add(hgvs.asText());
                var next = Pvs1StartLossReferenceMapping.map(model, hgvs.asText());
                if (!next.mapped()) mappingIssue = next.reason();
                else if (mapping != null && !mapping.key().equals(next.key())) mappingIssue = "REFERENCE_REPRESENTATION_CONFLICT";
                else mapping = next;
            }
            if (!mappingIssue.isEmpty() || mapping == null) {
                var intronic = Pvs1StartLossReferenceMapping.intronicLocation(model, row.path("hgvs"));
                if (mapping == null && intronic.resolved()) {
                    record.put("disposition", "EXCLUDED").put("reasonCode", "OUTSIDE_UPSTREAM_INTERVAL");
                    record.putObject("scopeExclusion").put("method", "EXACT_TRANSCRIPT_INTRON_SNV_GEOMETRY_V1")
                            .put("genomicPosition", intronic.position()).put("transcript", model.path("transcript").asText())
                            .put("scope", "CODING_BASES_BEFORE_CANDIDATE_RESTART")
                            .put("referenceAlleleVerified", false).put("spliceEffectAssessed", false);
                    continue;
                }
                var deletion = Pvs1StartLossReferenceMapping.intronicDeletion(model, row.path("hgvs"));
                if (mapping == null && deletion.resolved()) {
                    record.put("disposition", "EXCLUDED").put("reasonCode", "OUTSIDE_UPSTREAM_INTERVAL");
                    record.putObject("scopeExclusion").put("method", "EXACT_TRANSCRIPT_INTRON_DELETION_GEOMETRY_V1")
                            .put("genomicPosition", deletion.first()).put("genomicEnd", deletion.last())
                            .put("transcript", model.path("transcript").asText()).put("genomicDescriptionConfirmed", true)
                            .put("scope", "CODING_BASES_BEFORE_CANDIDATE_RESTART")
                            .put("referenceAlleleVerified", false).put("spliceEffectAssessed", false);
                    continue;
                }
                if ("REFERENCE_REPRESENTATION_CONFLICT".equals(deletion.reason())) {
                    record.put("reasonCode", deletion.reason()); continue;
                }
                record.put("reasonCode", "REFERENCE_REPRESENTATION_CONFLICT".equals(intronic.reason()) ? intronic.reason()
                        : mappingIssue.isEmpty() ? "EXACT_TRANSCRIPT_MAPPING_UNRESOLVED" : mappingIssue); continue;
            }
            record.put("cdsPosition", mapping.first()).put("cdsEnd", mapping.last())
                    .put("alleleKey", mapping.key()).put("mappingScope", "SAME_CODING_EXON_REFERENCE_HAPLOTYPE");
            if (mapping.first() >= restart) { record.put("disposition", "EXCLUDED").put("reasonCode", "OUTSIDE_UPSTREAM_INTERVAL"); continue; }
            if (mapping.last() >= restart) { record.put("reasonCode", "REFERENCE_UPSTREAM_BOUNDARY_UNRESOLVED"); continue; }
            byAllele.computeIfAbsent(mapping.key(), ignored -> new ArrayList<>()).add(record);
            int targetCds = sequence.path("cdsPosition").asInt();
            String targetAlt = CodingTranscriptMapping.orientAllele(model, context.at("/variant/evidenceQueryAllele/alternate").asText());
            String targetRef = CodingTranscriptMapping.orientAllele(model, context.at("/variant/evidenceQueryAllele/reference").asText());
            var target = Pvs1StartLossReferenceMapping.map(model, model.path("transcript").asText() + ":c." + targetCds + targetRef + ">" + targetAlt);
            if (target.mapped() && mapping.key().equals(target.key())) { record.put("disposition", "EXCLUDED").put("reasonCode", "TARGET_EXCLUDED"); continue; }
            if (!row.path("retracted").isBoolean()) { record.put("reasonCode", "RETRACTION_STATUS_UNRESOLVED"); continue; }
            if (row.path("retracted").asBoolean()) { record.put("disposition", "EXCLUDED").put("reasonCode", "WITHDRAWN_EXCLUDED"); continue; }
            try {
                java.time.LocalDate.parse(row.path("approvedDate").asText()); java.time.LocalDate.parse(row.path("publishedDate").asText());
            } catch (RuntimeException missingDate) { record.put("reasonCode", "PUBLICATION_STATUS_UNRESOLVED"); continue; }
            if (!"Pathogenic".equals(row.path("classification").asText())) {
                if (Set.of("Likely Pathogenic", "Uncertain Significance", "Likely Benign", "Benign").contains(row.path("classification").asText()))
                    record.put("disposition", "EXCLUDED").put("reasonCode", "BASELINE_PATHOGENIC_REQUIRED");
                else record.put("reasonCode", "REFERENCE_CLASSIFICATION_UNRESOLVED");
                continue;
            }
            // Coordinate/self/retraction and explicit non-P exclusions do not
            // need a missing inheritance subtype. Adoption still does. Keep
            // mapped non-P rows in byAllele so they can block a conflicting P.
            if (!backgroundResolved) { record.put("reasonCode", "BACKGROUND_UNRESOLVED"); continue; }
            var original = sources.interpretation(row.path("uuid").asText());
            record.set("interpretationSource", original.receipt());
            record.set("nativeAssessment", assessment(mapper, row, original.document()));
            var assessment = (ObjectNode) record.path("nativeAssessment");
            var referenceContext = Pvs1StartLossPublishedRule.sourceContext(context, model);
            var specification = referenceSpecifications == null ? null
                    : referenceSpecifications.referenceSpecification(referenceContext, original.document().path("assertionMethod"), row);
            if (specification == null && referenceSpecifications != null)
                specification = referenceSpecifications.historicalReferenceSpecification(referenceContext, original.document().path("assertionMethod"), row, sources);
            if (specification != null) {
                Pvs1StartLossOriginalRuleReplay.attach(assessment, Pvs1StartLossOriginalRuleReplay.replay(mapper, row, original.document(), specification));
                var grounds = Pvs1StartLossCuratedGrounds.assess(mapper, row, original.document(), specification, model);
                assessment.set("curatedGrounds", grounds);
                if ("INDEPENDENT_CURATED_BASIS_ESTABLISHED".equals(grounds.path("status").asText())) {
                    var assertions = Pvs1StartLossClinVarAssertions.read(mapper, context, row, sources.clinvarRecord(row.path("cvId").asText()));
                    var allele = assertions.path("allele");
                    if ("CONSISTENT_WITH_EREPO".equals(assertions.path("status").asText())) {
                        var mapped = Pvs1StartLossReferenceMapping.genomic(model, allele.path("position").asInt(),
                                allele.path("reference").asText(), allele.path("alternate").asText());
                        if (!mapped.mapped() || !mapping.key().equals(mapped.key()))
                            assertions.put("status", "UNRESOLVED").put("reasonCode", "CLINVAR_EREPO_CODING_ALLELE_MISMATCH");
                        else assertions.put("codingAlleleKey", mapped.key()).put("localEquivalentRecordReviewPending", true);
                    }
                    assessment.set("clinvarAssertions", assertions);
                }
                JsonNode specSource = specification.source();
                String sha = specSource.path("rawSha256").asText();
                if (!localOriginals.has(sha)) localOriginals.putObject(sha).put("rawJson", specification.raw()).set("receipt", specSource);
            } else assessment.putObject("originalRuleReplay").put("status", "UNRESOLVED")
                    .put("reasonCode", "ORIGINAL_GUIDELINE_SOURCE_OR_SCOPE_UNRESOLVED").put("independenceEstablished", false);
            record.put("reasonCode", record.at("/nativeAssessment/reasonCode").asText());
        }
        for (var group : byAllele.values()) {
            var active = group.stream().filter(r -> !Set.of("WITHDRAWN_EXCLUDED", "TARGET_EXCLUDED", "RETRACTION_STATUS_UNRESOLVED",
                    "PUBLICATION_STATUS_UNRESOLVED", "REFERENCE_CLASSIFICATION_UNRESOLVED").contains(r.path("reasonCode").asText())).toList();
            var classes = new LinkedHashSet<String>(); active.forEach(r -> classes.add(r.path("classification").asText()));
            boolean conflict = classes.contains("Pathogenic") && classes.stream().anyMatch(c -> !Set.of("Pathogenic", "Likely Pathogenic").contains(c));
            if (conflict) active.forEach(r -> r.put("disposition", "CONFLICT").put("reasonCode", "REFERENCE_CLASSIFICATION_CONFLICT"));
            else if (group.size() > 1) group.forEach(r -> r.put("sameAlleleRecordCount", group.size()));
        }
        return out;
    }

    /** Native SEPIO assessment replay, not interpretation of free-text scientific claims. */
    static ObjectNode assessment(ObjectMapper mapper, JsonNode summary, JsonNode original) {
        var out = mapper.createObjectNode().put("independenceEstablished", false);
        if (!"VariantPathogenicityInterpretation".equals(original.path("type").asText())
                || summary.path("uuid").asText().isBlank() || !summary.path("uuid").equals(original.path("uuid"))
                || !summary.path("mondoId").equals(original.at("/condition/id"))
                || !summary.path("classification").equals(original.at("/statementOutcome/label"))
                || !original.at("/variant/id").asText().equals("CAR:" + summary.path("caId").asText())
                || !original.path("evidenceLine").isArray())
            return out.put("reasonCode", "ORIGINAL_INTERPRETATION_UNAVAILABLE_OR_MISMATCH");
        var codes = out.putArray("criteria");
        int lineIndex = 0;
        for (var line : original.path("evidenceLine")) {
            if (lineIndex >= 1000 || !line.path("evidenceItem").isArray() || line.path("evidenceItem").size() > 1000)
                return out.put("reasonCode", "ORIGINAL_CRITERIA_UNRESOLVED");
            int itemIndex = 0;
            for (var item : line.path("evidenceItem")) {
                if ("CriterionAssessment".equals(item.path("type").asText())) {
                    var criterion = codes.addObject().put("code", item.at("/criterion/label").asText())
                            .put("outcome", item.at("/statementOutcome/label").asText())
                            .put("sourceDefaultStrength", item.at("/criterion/defaultStrength/label").asText())
                            .put("appliedStrengthStatus", "NOT_REPLAYED")
                            .put("sourceLocator", "/evidenceLine/" + lineIndex + "/evidenceItem/" + itemIndex);
                    // ClinGen's interpretation model places the applied strength
                    // on the evidence line, not on criterion.defaultStrength.
                    String applied = line.at("/evidenceStrength/label").asText();
                    if (line.path("evidenceItem").size() == 1 && "Met".equals(item.at("/statementOutcome/label").asText())
                            && Set.of("Pathogenic Very Strong", "Pathogenic Strong", "Pathogenic Moderate", "Pathogenic Supporting",
                                    "Benign Stand Alone", "Benign Strong", "Benign Supporting").contains(applied)) {
                        criterion.put("appliedStrengthStatus", "EXPLICIT_SOURCE_VALUE").put("sourceAppliedStrength", applied)
                                .put("appliedStrengthLocator", "/evidenceLine/" + lineIndex + "/evidenceStrength");
                    }
                    criterion.put("dependencyStatus", "NOT_ESTABLISHED_FROM_CRITERION_LABEL");
                    var observations = criterion.putArray("observationLocations");
                    boolean enumerated = observationLocations(item, "/evidenceLine/" + lineIndex + "/evidenceItem/" + itemIndex,
                            observations, 0);
                    criterion.put("observationsEnumerated", enumerated).put("observationSemanticsVerified", false);
                }
                itemIndex++;
            }
            lineIndex++;
        }
        boolean dependencyReview = false;
        out.set("sourceEvidenceAudit", Pvs1StartLossEvidenceGraph.audit(mapper, original, codes));
        for (var code : codes) if ("Met".equals(code.path("outcome").asText())
                && (code.path("code").asText().matches("PVS1(?:_.*)?") || code.path("code").asText().matches("PM4(?:_.*)?"))) dependencyReview = true;
        return out.put("reasonCode", dependencyReview ? "START_LOSS_DEPENDENCY_REVIEW_REQUIRED" : "CLASSIFICATION_INDEPENDENCE_REQUIRES_SCIENTIFIC_FACTS");
    }

    private static boolean observationLocations(JsonNode assertion, String pointer, ArrayNode locations, int depth) {
        if (!assertion.has("evidenceLine")) return true;
        var lines = assertion.path("evidenceLine");
        if (depth >= 8 || !lines.isArray() || lines.size() > 1000) return false;
        for (int i = 0; i < lines.size(); i++) {
            var items = lines.get(i).path("evidenceItem");
            if (!items.isArray() || items.size() > 1000) return false;
            for (int j = 0; j < items.size(); j++) {
                var item = items.get(j); String locator = pointer + "/evidenceLine/" + i + "/evidenceItem/" + j;
                if (!item.isObject() || locations.size() >= 1000) return false;
                locations.addObject().put("sourceLocator", locator).put("sourceType", item.path("type").asText())
                        .put("sourceId", item.path("id").asText());
                if (!observationLocations(item, locator, locations, depth + 1)) return false;
            }
        }
        return true;
    }

    public static ObjectNode publicSummary(ObjectMapper mapper, JsonNode prepared) {
        var out = mapper.createObjectNode();
        for (String key : List.of("schemaVersion", "methodVersion", "status", "runtimeEligible", "scientificEvaluationPerformed", "contextSha256", "issues", "policySelection", "transcriptReview", "upstreamReview", "expertAssertions", "clinvarSearch", "refseqInventory", "publishedRuleCheck", "mechanismAssessment"))
            if (prepared.has(key)) out.set(key, prepared.path(key).deepCopy());
        if (out.path("expertAssertions") instanceof ObjectNode expert) summarizeExpertAssertions(mapper, expert);
        if (out.at("/transcriptReview/functionalLiterature/candidateApplicability") instanceof ObjectNode functional)
            Pvs1StartLossFunctionalApplicability.summarize(mapper, functional);
        if (out.path("mechanismAssessment") instanceof ObjectNode mechanism) {
            for (var layer : List.of(mechanism, mechanism.path("g2pAssessment"))) if (layer instanceof ObjectNode assessment) {
                limitSummary(mapper, assessment, "findings", 20);
                for (var row : assessment.path("findings")) if (row instanceof ObjectNode object) {
                    var source = object.path("sourceRecord");
                    if (source.isObject()) object.put("sourceSupport", source.path("molecular mechanism support").asText())
                            .put("sourceConfidence", source.path("confidence").asText());
                    object.remove("sourceRecord"); // full records remain in the private result
                }
            }
        }
        if (out.path("clinvarSearch") instanceof ObjectNode scan && scan.path("records") instanceof ArrayNode all) {
            var rows = mapper.createArrayNode();
            for (int i = 0; i < Math.min(100, all.size()); i++) {
                var item = (ObjectNode) all.get(i).deepCopy(); item.remove("rawVcfLine");
                item.put("sourceLocator", "/records/" + i); rows.add(item);
            }
            int scanTotal = scan.path("totalRecords").isIntegralNumber() ? Math.max(all.size(), scan.path("totalRecords").asInt()) : all.size();
            scan.put("totalRecords", scanTotal).put("displayTruncated", scan.path("displayTruncated").asBoolean() || scanTotal > 100).set("records", rows);
            if (scan.path("candidateReview") instanceof ObjectNode candidates) {
                limitSummary(mapper, candidates, "records", 100);
                for (var row : candidates.path("records")) if (row.path("assertions") instanceof ObjectNode assertions)
                    limitSummary(mapper, assertions, "assertions", 20);
            }
        }
        for (String section : List.of("transcriptReview", "upstreamReview")) {
            String key = section.equals("transcriptReview") ? "findings" : "records";
            if (out.path(section) instanceof ObjectNode review && review.path(key) instanceof ArrayNode rows) {
                int total = rows.size();
                int fullTotal = review.path("totalRecords").isIntegralNumber() ? Math.max(total, review.path("totalRecords").asInt()) : total;
                review.put("totalRecords", fullTotal).put("displayTruncated", review.path("displayTruncated").asBoolean() || fullTotal > 100);
                var ordered = new ArrayList<JsonNode>(); rows.forEach(ordered::add);
                if (section.equals("upstreamReview")) ordered.sort(java.util.Comparator.comparingInt(row ->
                        "CONFLICT".equals(row.path("disposition").asText()) ? 0
                                : "UNRESOLVED".equals(row.path("disposition").asText()) ? 1
                                : "OUTSIDE_UPSTREAM_INTERVAL".equals(row.path("reasonCode").asText()) ? 3 : 2));
                var visible = mapper.createArrayNode(); for (int i = 0; i < Math.min(100, total); i++) visible.add(ordered.get(i));
                review.set(key, visible);
                if (section.equals("upstreamReview")) for (var row : visible)
                    if (row.at("/nativeAssessment/clinvarAssertions") instanceof ObjectNode assertions)
                        limitSummary(mapper, assertions, "assertions", 20);
            }
        }
        return out;
    }

    static void summarizeExpertAssertions(ObjectMapper mapper, ObjectNode expert) {
        for (var entry : expert.path("entries")) if (entry.path("inheritanceScopeJoin") instanceof ObjectNode joined) {
            limitSummary(mapper, joined, "records", 20);
            for (var record : joined.path("records")) if (record instanceof ObjectNode row) {
                row.put("sourceInheritance", row.at("/sourceRecord/allelic requirement").asText())
                        .put("sourceConfidence", row.at("/sourceRecord/confidence").asText());
                row.remove("sourceRecord");
            }
        }
    }

    private static void limitSummary(ObjectMapper mapper, ObjectNode parent, String key, int limit) {
        if (!(parent.path(key) instanceof ArrayNode all)) return;
        int total = parent.path("totalRecords").isIntegralNumber() ? Math.max(all.size(), parent.path("totalRecords").asInt()) : all.size();
        parent.put("totalRecords", total).put("displayTruncated", parent.path("displayTruncated").asBoolean() || total > limit);
        var ordered = new ArrayList<JsonNode>(); all.forEach(ordered::add);
        ordered.sort(java.util.Comparator.comparingInt(row -> switch (row.path("disposition").asText()) {
            case "CONFLICTING_CURRENT_ASSERTION", "POTENTIAL_CONFLICT_REQUIRES_BACKGROUND_REVIEW", "UNRESOLVED" -> 0;
            case "SAME_EREPO_EXPERT_ASSERTION" -> 1;
            default -> 2;
        }));
        var visible = mapper.createArrayNode();
        for (int i = 0; i < Math.min(limit, ordered.size()); i++) visible.add(ordered.get(i));
        parent.set(key, visible);
    }
}
