package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Server-pinned native missense PM1 engineering slice; separate from the historical HNF1A runtime. */
public final class Pm1CriterionEvaluator {
    public static final String CRITERION = "ACMG_AMP_2015:PM1";
    public static final String ID = "reviewed-protein-region-pm1";
    public static final String VERSION = "1.0.0";
    public static final String SCOPE = "PM1_REGION_DEVELOPMENT";
    public static final String DECLARATION = "ordinary-pm1-region-v1";
    private static final Set<String> NEGATIVE = Set.of("PM1_EXPLICIT_EXCLUSION", "PM1_BENIGN_DISTRIBUTION_NOT_MET",
            "PM1_PATHOGENIC_DISTRIBUTION_NOT_MET", "PM1_COVERED_POSITION_NOT_SUPPORTED", "PM1_REGION_NOT_QUALIFIED");
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final Pm1RegionPolicy regionPolicy;

    public Pm1CriterionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper);
        adapter = new RuntimeEvidenceV2Adapter(mapper);
        regionPolicy = new Pm1RegionPolicy(adapter);
    }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode policy,
            ObjectNode selection, ObjectNode model, ObjectNode regions, ObjectNode resourceProfile) {
        ObjectNode result = mapper.createObjectNode().put("criterionId", CRITERION)
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("positionCalculations", 0).put("storedVepProjections", 0)
                .put("classificationEnabled", false).put("adoptionSupported", false)
                .put("adoptionStatus", "NOT_SUPPORTED").put("adoptionReasonCode", "NEW_POLICY_ADOPTION_NOT_INTEGRATED");
        result.set("context", context.deepCopy()); result.set("policySelection", selection.deepCopy());
        result.set("policy", policy.deepCopy()); result.set("registryBinding", resourceProfile.path("registryBinding").deepCopy());
        result.set("transcriptModelBinding", policy.path("transcriptModelBinding").deepCopy());
        result.set("regionMaterialBinding", policy.path("regionMaterialBinding").deepCopy());
        result.set("regionMaterial", regions.deepCopy());
        ObjectNode observation = mapper.createObjectNode();
        result.set("resolvedInput", observation);
        String issue;
        try { adapter.validateCompleteContext(context); issue = policyIssue(context, policy, selection, model, regions); }
        catch (IllegalArgumentException invalid) { issue = "PM1_CONTEXT_OR_MATERIAL_IDENTITY_INVALID"; }
        if (issue != null) return finish(result, context, policy, selection, observation, issue);
        if (!validRegistry(resourceProfile)) return finish(result, context, policy, selection, observation, "PM1_REGISTRY_IDENTITY_INVALID");
        observation.put("resourceProfileSha256", adapter.canonicalSha256(resourceProfile))
                .put("transcriptModelSha256", adapter.canonicalSha256(model)).put("regionMaterialSha256", adapter.canonicalSha256(regions));
        try {
            var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
            var binding = wiring.bind(resourceProfile, resourceProfile.path("vepResourceId").asText());
            if (!"VEP".equals(binding.getProvider()) || !"116.0".equals(binding.getProviderVersion()))
                return finish(result, context, policy, selection, observation, "PM1_ANNOTATION_SOURCE_INVALID");
            TranscriptConsequence tx = wiring.selectedVepConsequence(context, candidate);
            if (tx == null) return finish(result, context, policy, selection, observation, "PM1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            if (!"missense_variant".equals(tx.getConsequence()))
                return finish(result, context, policy, selection, observation, "CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED");
            ObjectNode atomic = wiring.projectStoredTranscript(context, candidate, tx, binding);
            result.set("evidence", atomic); result.put("storedVepProjections", 1);
            ObjectNode annotation = mapper.valueToTree(tx);
            annotation.put("timestamp", Objects.toString(tx.getTimestamp(), null));
            result.set("storedAnnotation", annotation);
            observation.put("storedAnnotationSha256", adapter.canonicalSha256(annotation));
            result.put("positionCalculations", 1);
            issue = CodingMissenseResolver.resolve(context, tx, model, observation, adapter);
            if (issue == null) issue = regionPolicy.resolve(regions, observation);
        } catch (IllegalArgumentException | ArithmeticException invalid) { issue = "PM1_VARIANT_OR_ANNOTATION_IDENTITY_INVALID"; }
        return finish(result, context, policy, selection, observation, issue);
    }

    String policyIssue(ObjectNode context, ObjectNode policy, ObjectNode selection, ObjectNode model, ObjectNode regions) {
        return regionPolicy.validate(context, policy, selection, model, regions);
    }

    private ObjectNode finish(ObjectNode result, ObjectNode context, ObjectNode policy, ObjectNode selection, ObjectNode observation, String issue) {
        if (!completeEvaluationIdentity(result, context, policy, selection, observation)
                || ((issue == null || NEGATIVE.contains(issue)) && !result.has("evidence")))
            return refuseIncompleteIdentity(result, issue == null ? "PM1_RESULT_IDENTITY_INVALID" : issue);
        boolean negative = NEGATIVE.contains(Objects.toString(issue, ""));
        boolean complete = issue == null || negative;
        boolean unsupported = issue != null && (issue.contains("UNSUPPORTED") || issue.contains("NOT_SUPPORTED")
                || issue.equals("CODING_MISSENSE_NATIVE_SNV_REQUIRED"));
        String reason = issue == null ? "PM1_REVIEWED_REGION_MATCH" : issue;
        String disposition = complete ? "COMPLETE" : unsupported ? "UNSUPPORTED_BRANCH_OR_OPERATOR"
                : reason.contains("MISSING") || reason.contains("INCOMPLETE") || reason.contains("UNKNOWN") ? "MISSING_INPUT" : "INVALID_INPUT";
        result.put("inputDisposition", disposition).put("evaluatorCalls", complete ? 1 : 0);
        ObjectNode evaluation = result.putObject("evaluation").put("schemaVersion", "criterion-evaluation-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("criterionId", CRITERION).put("baseStrength", "MODERATE")
                .put("appliedStrength", issue == null ? observation.path("strength").asText() : "NONE");
        evaluation.putObject("evaluationResult").put("status", issue == null ? "MET" : negative ? "NOT_MET" : "NOT_EVALUATED")
                .put("reasonCode", reason);
        ObjectNode applicability = evaluation.putObject("criterionApplicability").put("status", complete ? "APPLICABLE" : unsupported ? "NOT_APPLICABLE" : "UNKNOWN");
        if (!complete) applicability.put("reasonCode", reason);
        evaluation.putObject("ruleBinding").put("currentGeneralDesignation", "PM1")
                .put("ruleRegistryId", result.at("/registryBinding/registryId").asText())
                .put("ruleRegistrySha256", result.at("/registryBinding/registrySha256").asText())
                .put("cspecSelectionId", selection.path("selectionId").asText())
                .set("authoritySourceIds", policy.path("authoritySourceIds").deepCopy());
        String evidenceId = result.at("/evidence/evidenceId").asText();
        var evidenceIds = evaluation.putArray("evidenceIds"); if (!evidenceId.isBlank()) evidenceIds.add(evidenceId);
        ObjectNode trace = evaluation.putObject("deterministicTrace").put("executionStatus", complete ? "COMPLETED" : "NOT_RUN")
                .put("evaluatorId", ID).put("evaluatorVersion", VERSION).put("algorithmId", policy.path("policyId").asText())
                .put("algorithmVersion", policy.path("policyVersion").asText());
        if (!complete) trace.put("reasonCode", reason);
        trace.set("inputEvidenceIds", evidenceIds.deepCopy());
        var parameters = trace.putArray("parameters");
        parameters.addObject().put("name", "policySha256").put("value", adapter.canonicalSha256(policy));
        parameters.addObject().put("name", "resolvedInputSha256").put("value", adapter.canonicalSha256(observation));
        parameters.addObject().put("name", "selectionSha256").put("value", adapter.canonicalSha256(selection));
        parameters.addObject().put("name", "regionMaterialSha256").put("value", result.at("/regionMaterialBinding/canonicalSha256").asText());
        parameters.addObject().put("name", "transcriptModelSha256").put("value", result.at("/transcriptModelBinding/canonicalSha256").asText());
        trace.putArray("steps").addObject().put("stepCode", issue == null ? "PINNED_CODON_AND_REVIEWED_REGION_AGREE" : issue)
                .put("outcome", issue == null ? "TRUE" : negative ? "FALSE" : "UNKNOWN").set("inputEvidenceIds", evidenceIds.deepCopy());
        trace.put("traceSha256", adapter.canonicalSha256(trace));
        evaluation.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(evaluation));
        boolean conflict = Set.of("PM1_REGION_CONFLICT", "PM1_REGION_PRECEDENCE_CONFLICT").contains(reason);
        var conflictIds = evaluation.putObject("conflict").put("status", conflict ? "PRESENT" : "NONE").putArray("conflictIds");
        if (conflict) {
            ObjectNode identity = mapper.createObjectNode().put("contextId", context.path("contextId").asText())
                    .put("reasonCode", reason).put("policySha256", adapter.canonicalSha256(policy))
                    .put("regionMaterialSha256", result.at("/regionMaterialBinding/canonicalSha256").asText());
            identity.set("resolvedInput", observation.deepCopy());
            conflictIds.add("conflict-sha256-" + adapter.canonicalSha256(identity));
        }
        evaluation.putObject("review").put("status", conflict ? "REQUIRED" : "NOT_REQUIRED")
                .put("decision", "NONE").putArray("reviewRecordIds");
        result.putArray("criterionEvaluations").add(evaluation.deepCopy());
        return result;
    }

    private boolean completeEvaluationIdentity(ObjectNode result, ObjectNode context, ObjectNode policy,
            ObjectNode selection, ObjectNode observation) {
        try {
            adapter.validateCompleteContext(context);
            if (!regionPolicy.evaluationIdentityValid(context, policy, selection) || !validRegistry(result)) return false;
            adapter.canonicalSha256(observation);
            return !result.has("evidence") || result.at("/evidence/evidenceId").asText().matches("evidence-sha256-[0-9a-f]{64}");
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private ObjectNode refuseIncompleteIdentity(ObjectNode result, String reason) {
        boolean unsupported = reason.contains("UNSUPPORTED") || reason.contains("NOT_SUPPORTED");
        result.put("inputDisposition", unsupported ? "UNSUPPORTED_BRANCH_OR_OPERATOR"
                : reason.contains("MISSING") || reason.contains("INCOMPLETE") ? "MISSING_INPUT" : "INVALID_INPUT")
                .put("evaluatorCalls", 0);
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        result.putObject("criterionApplicability").put("status", unsupported ? "NOT_APPLICABLE" : "UNKNOWN").put("reasonCode", reason);
        ObjectNode minimal = result.putArray("criterionEvaluations").addObject().put("criterionId", CRITERION);
        minimal.set("evaluationResult", result.path("evaluationResult").deepCopy());
        minimal.set("criterionApplicability", result.path("criterionApplicability").deepCopy());
        return result;
    }

    private boolean validRegistry(JsonNode profile) {
        JsonNode binding = profile.path("registryBinding");
        if (!binding.path("registryId").asText().matches("acmg-rule-registry-[a-z0-9][a-z0-9._-]{2,95}")
                || !binding.path("registrySha256").asText().matches("[0-9a-f]{64}")) return false;
        try (var input = Pm1CriterionEvaluator.class.getResourceAsStream(binding.path("registryClasspathResource").asText())) {
            if (input == null) return false;
            byte[] bytes = input.readAllBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            JsonNode registry = mapper.readTree(bytes);
            if (registry == null || !hash.equals(binding.path("registrySha256").asText())
                    || !registry.path("registryId").equals(binding.path("registryId"))) return false;
            int count = 0;
            for (JsonNode criterion : registry.path("criteria")) if (CRITERION.equals(criterion.path("criterionId").asText())) count++;
            return count == 1;
        } catch (IOException | NoSuchAlgorithmException invalid) { return false; }
    }
}
