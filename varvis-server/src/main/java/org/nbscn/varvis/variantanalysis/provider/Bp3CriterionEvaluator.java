package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Pinned, server-owned simple in-frame BP3 engineering runtime. No acquisition or adoption. */
public final class Bp3CriterionEvaluator {
    public static final String CRITERION = "ACMG_AMP_2015:BP3";
    public static final String ID = "simple-inframe-repeat-bp3";
    public static final String VERSION = "1.0.0";
    public static final String SCOPE = "BP3_INFRAME_DEVELOPMENT";
    public static final String DECLARATION = "ordinary-bp3-inframe-v1";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;

    public Bp3CriterionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper); adapter = new RuntimeEvidenceV2Adapter(mapper);
    }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode policy,
            ObjectNode selection, ObjectNode model, ObjectNode regions, ObjectNode resourceProfile) {
        ObjectNode result = mapper.createObjectNode().put("criterionId", CRITERION)
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("lengthCalculations", 0).put("classificationEnabled", false).put("adoptionSupported", false)
                .put("adoptionStatus", "NOT_SUPPORTED").put("adoptionReasonCode", "NEW_POLICY_ADOPTION_NOT_INTEGRATED");
        result.set("context", context.deepCopy()); result.set("policySelection", selection.deepCopy());
        result.set("policy", policy.deepCopy());
        result.set("regionMaterial", regions.deepCopy());
        result.set("registryBinding", resourceProfile.path("registryBinding").deepCopy());
        ObjectNode observation = mapper.createObjectNode();
        result.set("resolvedInput", observation);
        String issue;
        try { adapter.validateCompleteContext(context); issue = new Bp3RegionPolicy(adapter).validate(context, policy, selection, model, regions); }
        catch (IllegalArgumentException invalid) { issue = "BP3_CONTEXT_IDENTITY_INVALID"; }
        if (issue != null) return finish(result, context, policy, selection, observation, issue);
        if (!validRegistry(resourceProfile, policy)) return finish(result, context, policy, selection, observation, "BP3_REGISTRY_IDENTITY_INVALID");
        observation.put("resourceProfileSha256", adapter.canonicalSha256(resourceProfile));
        try {
            var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
            var binding = wiring.bind(resourceProfile, resourceProfile.path("vepResourceId").asText());
            if (!"VEP".equals(binding.getProvider()) || !"116.0".equals(binding.getProviderVersion()))
                return finish(result, context, policy, selection, observation, "BP3_ANNOTATION_SOURCE_INVALID");
            TranscriptConsequence tx = wiring.selectedVepConsequence(context, candidate);
            if (tx == null) return finish(result, context, policy, selection, observation, "BP3_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            ObjectNode atomic = wiring.projectStoredTranscript(context, candidate, tx, binding);
            result.set("evidence", atomic); result.put("storedVepProjections", 1);
            ObjectNode annotation = mapper.valueToTree(tx);
            annotation.put("timestamp", Objects.toString(tx.getTimestamp(), null));
            observation.put("storedAnnotationSha256", adapter.canonicalSha256(annotation))
                    .put("transcriptModelSha256", adapter.canonicalSha256(model))
                    .put("regionMaterialSha256", adapter.canonicalSha256(regions));
            result.set("storedAnnotation", annotation);
            result.put("lengthCalculations", 1);
            issue = ProteinLengthChangeObservation.resolve(context, model, tx, observation);
            if (issue != null) issue = issue.replace("PM4_", "BP3_");
            observation.put("lengthLimitBasis", "EXISTING_COMPLETE_CONTEXT_ENGINEERING_LIMIT_NOT_BP3_SCIENTIFIC_THRESHOLD")
                    .put("strand", model.path("strand").asInt());
            if (issue == null) issue = Bp3EquivalentEditEnvelope.resolve(model, observation);
            if (issue == null) issue = new Bp3RegionPolicy(adapter).resolve(regions, observation);
            if (issue == null && observation.path("netAminoAcidChange").asInt() == 0) issue = "BP3_NO_NET_LENGTH_CHANGE";
        } catch (IllegalArgumentException | ArithmeticException invalid) { issue = "BP3_VARIANT_OR_ANNOTATION_IDENTITY_INVALID"; }
        // Keep full sequences in direct synthetic observation tests, not in the business response.
        finally { observation.remove(List.of("referenceCds", "alteredCds", "referenceProtein", "alteredProtein")); }
        return finish(result, context, policy, selection, observation, issue);
    }

    private ObjectNode finish(ObjectNode result, ObjectNode context, ObjectNode policy, ObjectNode selection, ObjectNode observation, String issue) {
        // A conflicting material set has no scientific review workflow/record in this bounded backend.
        // Preserve its facts and explicit refusal; do not invent a full review envelope.
        if ("BP3_REGION_CONFLICT".equals(issue)) return refuse(result, issue);
        try { adapter.validateCompleteContext(context); }
        catch (IllegalArgumentException invalid) { return refuse(result, Objects.toString(issue, "BP3_CONTEXT_IDENTITY_INVALID")); }
        if (!new Bp3RegionPolicy(adapter).evaluationIdentityValid(context, policy, selection) || !validRegistry(result, policy))
            return refuse(result, Objects.toString(issue, "BP3_EVALUATION_IDENTITY_INVALID"));
        boolean negative = Set.of("BP3_NON_REPEAT_REGION", "BP3_KNOWN_FUNCTION").contains(Objects.toString(issue, ""));
        boolean complete = issue == null || negative;
        boolean unsupported = issue != null && issue.contains("UNSUPPORTED");
        String reason = issue == null ? "BP3_REVIEWED_REPEAT_NO_KNOWN_FUNCTION" : issue;
        String disposition = complete ? "COMPLETE" : unsupported ? "UNSUPPORTED_BRANCH_OR_OPERATOR"
                : reason.contains("MISSING") || reason.contains("INCOMPLETE") ? "MISSING_INPUT" : "INVALID_INPUT";
        result.put("inputDisposition", disposition).put("evaluatorCalls", complete ? 1 : 0);
        ObjectNode evaluation = result.putObject("evaluation").put("schemaVersion", "criterion-evaluation-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("criterionId", CRITERION).put("baseStrength", "SUPPORTING").put("appliedStrength", issue == null ? policy.path("appliedStrength").asText() : "NONE");
        evaluation.putObject("evaluationResult").put("status", issue == null ? "MET" : negative ? "NOT_MET" : "NOT_EVALUATED")
                .put("reasonCode", reason);
        ObjectNode applicability = evaluation.putObject("criterionApplicability")
                .put("status", complete ? "APPLICABLE" : unsupported ? "NOT_APPLICABLE" : "UNKNOWN");
        if (!complete) applicability.put("reasonCode", reason);
        evaluation.putObject("ruleBinding").put("currentGeneralDesignation", "BP3")
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
        trace.putArray("steps").addObject().put("stepCode", issue == null ? "PINNED_LENGTH_AND_REGION_FACTS_AGREE" : issue)
                .put("outcome", issue == null ? "TRUE" : negative ? "FALSE" : "UNKNOWN").set("inputEvidenceIds", evidenceIds.deepCopy());
        trace.put("traceSha256", adapter.canonicalSha256(trace));
        evaluation.put("evaluationId", "evaluation-sha256-" + adapter.canonicalSha256(evaluation));
        evaluation.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        evaluation.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        result.putArray("criterionEvaluations").add(evaluation.deepCopy());
        return result;
    }

    private ObjectNode refuse(ObjectNode result, String reason) {
        result.put("evaluatorCalls", 0).put("inputDisposition", "INVALID_INPUT");
        result.putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", reason);
        result.putObject("criterionApplicability").put("status", "UNKNOWN").put("reasonCode", reason);
        ObjectNode minimal = result.putArray("criterionEvaluations").addObject().put("criterionId", CRITERION);
        minimal.set("evaluationResult", result.path("evaluationResult").deepCopy());
        minimal.set("criterionApplicability", result.path("criterionApplicability").deepCopy());
        return result;
    }

    private boolean validRegistry(JsonNode profile, JsonNode policy) {
        JsonNode binding = profile.path("registryBinding");
        try (var input = Bp3CriterionEvaluator.class.getResourceAsStream(binding.path("registryClasspathResource").asText())) {
            if (input == null) return false;
            byte[] bytes = input.readAllBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            JsonNode registry = mapper.readTree(bytes);
            if (registry == null || !hash.equals(binding.path("registrySha256").asText())
                    || !registry.path("registryId").equals(binding.path("registryId")) || !authorityMatches(registry, policy)) return false;
            int count = 0;
            for (JsonNode criterion : registry.path("criteria"))
                if (CRITERION.equals(criterion.path("criterionId").asText())) {
                    if (!"V2_IMPLEMENTED".equals(criterion.path("implementationStatus").asText())
                            || !"BENIGN".equals(criterion.path("direction").asText())
                            || !"SUPPORTING".equals(criterion.at("/currentGeneral/strength").asText())
                            || !criterion.at("/currentGeneral/authoritySourceIds").equals(policy.path("authoritySourceIds"))) return false;
                    count++;
                }
            return count == 1;
        } catch (IOException | NoSuchAlgorithmException invalid) { return false; }
    }

    private boolean authorityMatches(JsonNode registry, JsonNode policy) throws IOException, NoSuchAlgorithmException {
        String id = registry.path("authorityCatalogId").asText();
        if (!id.matches("authority-catalog-[a-z0-9._-]+")) return false;
        try (var input = getClass().getResourceAsStream("/acmg/" + id + ".json")) {
            if (input == null) return false;
            byte[] bytes = input.readAllBytes(); JsonNode catalog = mapper.readTree(bytes);
            if (catalog == null || !id.equals(catalog.path("catalogId").asText())
                    || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(registry.path("authorityCatalogSha256").asText())) return false;
            for (JsonNode sourceId : policy.path("authoritySourceIds")) {
                boolean matched = false;
                for (JsonNode source : policy.path("scientificSources")) if (sourceId.equals(source.path("sourceId")))
                    for (JsonNode authority : catalog.path("sources")) if (sourceId.equals(authority.path("authoritySourceId")))
                        matched |= "ELIGIBLE".equals(authority.path("normativeEligibility").asText())
                                && "RELEASED".equals(authority.path("publicationStatus").asText())
                                && source.path("version").equals(authority.path("documentVersion"))
                                && source.path("url").equals(authority.path("sourceUrl"))
                                && source.path("sha256").equals(authority.path("snapshotSha256"));
                if (!matched) return false;
            }
            return true;
        }
    }
}
