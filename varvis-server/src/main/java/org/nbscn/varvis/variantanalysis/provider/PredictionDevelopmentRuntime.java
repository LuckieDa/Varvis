package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.nbscn.varvis.variantanalysis.domain.*;

/** Versioned core behind the existing business runtime. No automatic or formal registration. */
public final class PredictionDevelopmentRuntime {
    public static final String DECLARATION = "ordinary-pp3-bp4-development-v1";
    public static final List<String> CRITERIA = List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4");
    @FunctionalInterface public interface ObservationLookup { ObjectNode lookup(JsonNode request) throws Exception; }
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode policy, calibration, resources;
    private final ObservationLookup lookup;
    private final boolean syntheticGrid;
    private final java.util.function.Function<JsonNode, JsonNode> effectRestrictions;
    private final Map<String, ObjectNode> observations = new LinkedHashMap<>();
    private int calls;

    public PredictionDevelopmentRuntime(ObjectMapper mapper, ObjectNode policy, ObjectNode calibration,
            ObjectNode resources, ObservationLookup lookup, boolean syntheticGrid) {
        this(mapper, policy, calibration, resources, lookup, syntheticGrid,
                plan -> mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE"));
    }
    /** Restriction lookup is server-owned; declarations cannot supply or waive saved effect references. */
    public PredictionDevelopmentRuntime(ObjectMapper mapper, ObjectNode policy, ObjectNode calibration,
            ObjectNode resources, ObservationLookup lookup, boolean syntheticGrid,
            java.util.function.Function<JsonNode, JsonNode> effectRestrictions) {
        this.effectRestrictions = Objects.requireNonNull(effectRestrictions);
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper);
        this.policy = policy.deepCopy(); this.calibration = calibration.deepCopy(); this.resources = resources.deepCopy();
        this.lookup = Objects.requireNonNull(lookup); this.syntheticGrid = syntheticGrid;
        String issue = PredictionClausePolicy.coreIssue(policy, calibration);
        if (issue != null) throw new IllegalArgumentException(issue);
    }
    public static ObjectNode load(ObjectMapper mapper, String name) {
        try (var in = PredictionDevelopmentRuntime.class.getResourceAsStream("/acmg/" + name)) {
            if (in == null || !(mapper.readTree(in) instanceof ObjectNode value)) throw new IllegalArgumentException("PREDICTION_CORE_RESOURCE_MISSING");
            return value;
        } catch (java.io.IOException invalid) { throw new IllegalStateException("PREDICTION_CORE_RESOURCE_INVALID", invalid); }
    }
    public static PredictionDevelopmentRuntime configured(ObjectMapper mapper, AlphaMissenseProvider provider) {
        var resources = mapper.createObjectNode();
        resources.set("ALPHAMISSENSE", provider.rawResourceIdentity(mapper));
        resources.putObject("SPLICEAI").put("model", "SPLICEAI").put("calibrationCompatibility", "UNVERIFIED")
                .putObject("annotation").put("status", "UNVERIFIED").put("source", "BUNDLED_GRCH38_NOT_MANE_PROOF");
        return new PredictionDevelopmentRuntime(mapper, load(mapper, "prediction-core-policy-v1.json"),
                load(mapper, "prediction-am-calibration-v1.json"), resources, request -> {
                    if (!"ALPHAMISSENSE".equals(request.path("model").asText())) return mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "SPLICE_EXECUTION_NOT_AUTHORIZED");
                    var key = mapper.treeToValue(request.path("queryAllele"), NormalizedVariantKey.class);
                    return provider.lookupRecords(mapper, key, request.path("resource"), DECLARATION, request.path("dataClass").asText());
                }, false);
    }
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-business-binding-v1").put("status", "CONFIGURED")
                .put("version", "2.0.0-development.3").put("automaticTrigger", false).put("classificationEnabled", false)
                .put("adoptionSupported", false).put("formalEnabled", false).put("syntheticGridOnly", syntheticGrid)
                .put("policySha256", adapter.canonicalSha256(policy)).put("calibrationSha256", adapter.canonicalSha256(calibration))
                .put("resourcesSha256", adapter.canonicalSha256(resources)).put("applicationContract", "MECHANISM_APPLICATION_V1");
        out.put("mechanismApplicationVersion", PredictionMechanismApplication.VERSION);
        out.set("enabledCriteria", mapper.valueToTree(CRITERIA)); return out;
    }
    /** Identity only: no observation lookup or calibration. */
    public JsonNode associationResource() { return resources.path("ALPHAMISSENSE").deepCopy(); }
    /** Immutable and owned; JSON snapshots cannot be submitted back as executable plans. */
    public final class Plan {
        private final ObjectNode snapshot;
        private final VariantInterpretation candidate;
        private Plan(ObjectNode value, VariantInterpretation candidate) {
            snapshot = value.deepCopy();
            try {
                // Our own DTO has derived getter aliases; preserve its immutable query snapshot.
                JsonNode candidateSnapshot = mapper.valueToTree(candidate);
                this.candidate = mapper.readerFor(VariantInterpretation.class)
                        .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(candidateSnapshot);
            } catch (java.io.IOException invalid) { throw new IllegalArgumentException("PREDICTION_QUERY_VIEW_INVALID", invalid); }
        }
        public ObjectNode snapshot() { return snapshot.deepCopy(); }
        private PredictionDevelopmentRuntime owner() { return PredictionDevelopmentRuntime.this; }
    }
    public Plan prepare(VariantInterpretation candidate, JsonNode context, String sourceSha, String hypothesisId, String dataClass, String sourceIssue) {
        var out = mapper.createObjectNode().put("schemaVersion", "prediction-execution-plan-v1").put("candidateId", candidate.getCandidateId())
                .put("hypothesisId", hypothesisId).put("dataClass", dataClass).put("executionSourceSha256", sourceSha)
                .put("status", "PREPARED").put("model", "ALPHAMISSENSE").put("specApplicability", "UNKNOWN")
                .put("declarationVersion", DECLARATION);
        out.set("runtimeIdentity", identity()); out.set("resources", resources.deepCopy());
        out.set("calibration", calibration.deepCopy()); out.set("criterionIds", mapper.valueToTree(CRITERIA));
        out.set("policy", policy.deepCopy()); out.set("context", context == null ? mapper.nullNode() : context.deepCopy());
        for (String field : List.of("primaryMechanism", "allowedPp3Mechanisms", "requiredBp4Mechanisms", "secondaryPp3Route")) out.set(field, policy.path(field).deepCopy());
        var key = candidate.getObservation() == null ? null : candidate.getObservation().getKey();
        out.set("queryAllele", mapper.valueToTree(key));
        String issue = sourceIssue;
        if (issue == null && (sourceSha == null || !sourceSha.matches("[0-9a-f]{64}"))) issue = "PREDICTION_EXECUTION_SOURCE_UNVERIFIED";
        if (issue == null && (key == null || !"GRCh38".equals(key.getGenomeAssembly()) || key.getChromosome() == null || key.getPosition() <= 0 || key.getReference() == null || key.getAlternate() == null
                || !key.getReference().matches("[ACGT]") || !key.getAlternate().matches("[ACGT]") || key.getReference().equals(key.getAlternate()))) issue = "PREDICTION_NATIVE_SNV_REQUIRED";
        out.put("rawAcquisitionAllowed", issue == null);
        if (issue == null && (context == null || !context.isObject())) issue = "SPEC_APPLICABILITY_UNRESOLVED_CONTEXT_MISSING";
        if (issue == null && context != null) try {
            adapter.validateCompleteContext(context);
            if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())) issue = "PREDICTION_NATIVE_SOURCE_REQUIRED";
            var allele = context.at("/variant/evidenceQueryAllele");
            if (key == null || !key.getGenomeAssembly().equals(allele.path("assembly").asText())
                    || !key.getReference().equals(allele.path("reference").asText()) || !key.getAlternate().equals(allele.path("alternate").asText())
                    || key.getPosition() != allele.path("position").asLong()
                    || !key.getChromosome().replaceFirst("^chr", "").equals(allele.path("contig").asText().replaceFirst("^chr", ""))) issue = "PREDICTION_CONTEXT_ALLELE_MISMATCH";
        } catch (RuntimeException invalid) { issue = "PREDICTION_CONTEXT_INVALID"; }
        if (issue == null && context != null) {
            out.put("specApplicability", "NO_APPLICABLE_SPEC_IN_LOCAL_SUPPORTED_CATALOG");
            for (var spec : policy.path("specializations")) if (spec.path("hgncId").equals(context.at("/gene/hgncId"))
                    && spec.path("mondoId").equals(context.at("/disease/mondoId")) && spec.path("moi").equals(context.path("moi"))) {
                out.put("model", spec.path("preselectedModel").asText()).put("specApplicability", "MATCHED"); out.set("specialization", spec.deepCopy());
                // A matched but unsupported specialization never falls back to generic AM.
                out.put("rawAcquisitionAllowed", false);
                issue = spec.path("reasonCode").asText("SPECIALIZED_PREDICTION_NOT_IMPLEMENTED"); break;
            }
        }
        if (issue == null) {
            var tx = new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate);
            if (tx == null) issue = "PREDICTION_SELECTED_TRANSCRIPT_NOT_UNIQUE";
            else if (!IntronicSplicePolicy.missense(tx)) {
                if (IntronicSplicePolicy.spliceCandidate(tx)) {
                    out.put("model", "SPLICEAI").put("primaryMechanism", "SPLICING").putArray("allowedPp3Mechanisms").add("SPLICING");
                    out.putArray("requiredBp4Mechanisms").add("SPLICING");
                    out.put("spliceOnly", true);
                } else { issue = "PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE"; out.put("rawAcquisitionAllowed", false); }
            }
        }
        out.put("planMode", issue == null ? "RULE_APPLICATION" : out.path("rawAcquisitionAllowed").asBoolean() ? "OBSERVATION_ONLY" : "BLOCKED")
                .put("applicationPolicyStatus", issue == null ? "SELECTED" : "UNRESOLVED");
        if (issue != null) out.put("status", "BLOCKED").put("reasonCode", issue);
        out.put("planId", "prediction-plan-sha256-" + adapter.canonicalSha256(out)); return new Plan(out, candidate);
    }
    public ObjectNode execute(Plan plan) { return execute(plan, java.util.List.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4")); }
    public ObjectNode execute(Plan plan, java.util.List<String> selectedCriteria) {
        if (plan.owner() != this) throw new IllegalArgumentException("PREDICTION_PLAN_OWNER_MISMATCH");
        var p = plan.snapshot(); var result = mapper.createObjectNode().put("schemaVersion", "prediction-rule-result-v1");
        result.set("plan", p); result.set("runtimeIdentity", identity()); result.set("calibration", calibration.deepCopy());
        result.set("resourceReadiness", resources.deepCopy());
        var mechanisms = result.putObject("mechanisms");
        mechanisms.putObject("SPLICING").put("status", "NOT_EVALUATED").put("reasonCode", "SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED");
        String applicationIssue = "BLOCKED".equals(p.path("status").asText()) ? p.path("reasonCode").asText() : null;
        if (applicationIssue == null && syntheticGrid && !"SYNTHETIC".equals(p.path("dataClass").asText()))
            applicationIssue = "SYNTHETIC_GRID_NOT_ALLOWED_FOR_PRIVATE_DATA";
        var protein = mechanisms.putObject("PROTEIN_MISSENSE").put("status", "NOT_EVALUATED");
        if (applicationIssue != null) protein.put("reasonCode", applicationIssue);
        if (p.path("rawAcquisitionAllowed").asBoolean() && !p.path("spliceOnly").asBoolean()) {
            String model = p.path("model").asText(); var resource = resources.path(model);
            String acquisitionIssue = PredictionResourceCompatibility.acquisitionIssue(resource);
            if (acquisitionIssue != null) protein.put("reasonCode", acquisitionIssue);
            else {
                var request = mapper.createObjectNode().put("model", model).put("dataClass", p.path("dataClass").asText()).put("declaration", DECLARATION);
                request.set("queryAllele", p.path("queryAllele")); request.set("resource", resource);
                String key = adapter.canonicalSha256(request); ObjectNode observation = observations.get(key);
                if (observation == null) {
                    calls++;
                    try { observation = Objects.requireNonNull(lookup.lookup(request.deepCopy())).deepCopy(); }
                    catch (Exception failed) { observation = mapper.createObjectNode().put("status", "UNAVAILABLE")
                            .put("reasonCode", failed instanceof AlphaMissensePhaseBudget.Exceeded ? failed.getMessage() : "PREDICTION_LOOKUP_FAILED")
                            .put("exceptionType", failed.getClass().getSimpleName()); }
                    observations.put(key, observation.deepCopy());
                }
                result.set("observation", observation.deepCopy());
                String issue = null;
                if (!"HIT".equals(observation.path("status").asText())) issue = observation.path("reasonCode").asText(observation.path("status").asText("PREDICTION_OBSERVATION_MISSING"));
                else if (!request.path("queryAllele").equals(observation.path("queryAllele")) || !resource.equals(observation.path("resource"))
                        || !p.path("dataClass").equals(observation.path("dataClass"))) issue = "PREDICTION_OBSERVATION_REQUEST_MISMATCH";
                if (issue == null) {
                    var tx = associationTranscript(p.path("context"), plan.candidate);
                    if (tx == null) {
                        issue = "PREDICTION_ASSOCIATION_TRANSCRIPT_INPUT_MISSING_OR_NOT_UNIQUE";
                        result.putObject("association").put("status", "IDENTITY_UNRESOLVED").put("reasonCode", issue);
                    }
                    else {
                        var input = result.putObject("associationInput").put("schemaVersion", "prediction-association-input-v1")
                                .put("transcriptId", tx.getVersionedTranscriptId()).put("proteinPosition", tx.getProteinPosition())
                                .put("aminoAcids", tx.getAminoAcids()).put("route", "SELECTED_VEP_TRANSCRIPT")
                                .put("executionSourceSha256", p.path("executionSourceSha256").asText());
                        input.set("queryAllele", p.path("queryAllele").deepCopy());
                        var association = PredictionObservationAssociation.alpha(mapper, observation, tx, mapper.nullNode()); result.set("association", association);
                        if (!"ASSOCIATED".equals(association.path("status").asText())) issue = association.path("reasonCode").asText();
                        else {
                            issue = PredictionResourceCompatibility.issue(resource, observation.path("resource"));
                            if (issue == null && !calibration.path("calibrationId").equals(resource.at("/compatibilityEvidence/calibrationId"))) issue = "CALIBRATION_RESOURCE_IDENTITY_MISMATCH";
                            if (issue == null) {
                                if (syntheticGrid && !"SYNTHETIC".equals(p.path("dataClass").asText())) issue = "SYNTHETIC_GRID_NOT_ALLOWED_FOR_PRIVATE_DATA";
                                else mechanisms.set("PROTEIN_MISSENSE", PredictionClauseRuntime.calibrate(mapper, calibration, association.path("rawScore").asText(), syntheticGrid));
                            }
                        }
                    }
                }
                if (issue != null) protein.put("reasonCode", issue);
            }
        }
        if (p.path("spliceOnly").asBoolean()) protein.put("status", "NOT_APPLICABLE").put("reasonCode", "PROTEIN_MISSENSE_NOT_REQUIRED");
        if (applicationIssue == null) {
            var spliceResource = resources.path("SPLICEAI");
            String spliceIssue = PredictionResourceCompatibility.issue(spliceResource, spliceResource);
            if (spliceIssue == null) {
                var request = mapper.createObjectNode().put("model", "SPLICEAI").put("declaration", DECLARATION).put("dataClass", p.path("dataClass").asText());
                request.set("queryAllele", p.path("queryAllele")); request.set("resource", spliceResource);
                String cacheKey = adapter.canonicalSha256(request); ObjectNode observation = observations.get(cacheKey);
                if (observation == null) {
                    calls++;
                    try { observation = Objects.requireNonNull(lookup.lookup(request.deepCopy())).deepCopy(); }
                    catch (Exception failed) { observation = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "SPLICE_LOOKUP_FAILED"); }
                    observations.put(cacheKey, observation.deepCopy());
                }
                result.set("spliceObservation", observation.deepCopy());
                var tx = associationTranscript(p.path("context"), plan.candidate);
                if (!"HIT".equals(observation.path("status").asText())) spliceIssue = observation.path("reasonCode").asText("SPLICE_OBSERVATION_UNAVAILABLE");
                else if (!request.path("queryAllele").equals(observation.path("queryAllele")) || !spliceResource.equals(observation.path("resource"))
                        || !request.path("dataClass").equals(observation.path("dataClass"))) spliceIssue = "PREDICTION_OBSERVATION_REQUEST_MISMATCH";
                else if (tx == null) spliceIssue = "PREDICTION_SELECTED_TRANSCRIPT_NOT_UNIQUE";
                else if (observation.path("rows").size() != 1) spliceIssue = "SPLICE_RECORD_IDENTITY_UNRESOLVED";
                else mechanisms.set("SPLICING", SpliceAiBusinessRuntime.calibrateDevelopment(mapper, observation.path("rows").get(0), spliceResource,
                        observation.path("resource"), tx, plan.candidate.getObservation().getKey()));
            }
            if (spliceIssue != null) ((ObjectNode) mechanisms.path("SPLICING")).put("reasonCode", spliceIssue);
        }
        JsonNode restrictions;
        try { restrictions = effectRestrictions.apply(p.deepCopy()); }
        catch (RuntimeException unavailable) { restrictions = null; }
        if (restrictions == null || !restrictions.isObject() || !p.at("/context/contextId").isTextual())
            restrictions = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE");
        result.set("effectRestrictions", restrictions.deepCopy()); var applicationPlan = p.deepCopy(); applicationPlan.set("effectRestrictions", restrictions.deepCopy());
        result.set("criteria", PredictionMechanismApplication.apply(mapper, applicationPlan, mechanisms, applicationIssue, selectedCriteria));
        var stages = result.putObject("stages");
        stages.put("rawLookupReady", Set.of("HIT", "NO_RECORD").contains(result.at("/observation/status").asText()));
        stages.put("associationReady", "ASSOCIATED".equals(result.at("/association/status").asText()));
        stages.put("calibrationReady", "CALIBRATED".equals(mechanisms.at("/PROTEIN_MISSENSE/status").asText()));
        boolean applicationReady = true;
        for (var criterion : result.path("criteria")) applicationReady &= !"NOT_EVALUATED".equals(criterion.path("status").asText());
        stages.put("applicationReady", applicationReady);
        stages.put("firstUnresolvedStage", !stages.path("rawLookupReady").asBoolean() ? "RAW_LOOKUP"
                : !stages.path("associationReady").asBoolean() ? "ASSOCIATION"
                : !stages.path("calibrationReady").asBoolean() ? "CALIBRATION" : !applicationReady ? "APPLICATION" : "NONE");
        result.put("providerCalls", calls).put("classificationEnabled", false).put("formalEnabled", false);
        result.put("resultId", "prediction-result-sha256-" + adapter.canonicalSha256(result)); return result;
    }
    private TranscriptConsequence associationTranscript(JsonNode context, VariantInterpretation candidate) {
        // Only the selected VEP identity is needed here; disease validation belongs to application.
        try { return new RuntimeEvidenceV2ProviderWiring(mapper, adapter).selectedVepConsequence(context, candidate); }
        catch (IllegalArgumentException missingIdentity) { return null; }
    }
    public PredictionDevelopmentRuntime withEffectRestrictions(java.util.function.Function<JsonNode, JsonNode> resolver) {
        return new PredictionDevelopmentRuntime(mapper, policy, calibration, resources, lookup, syntheticGrid, resolver);
    }
    public int providerCalls() { return calls; }
}
