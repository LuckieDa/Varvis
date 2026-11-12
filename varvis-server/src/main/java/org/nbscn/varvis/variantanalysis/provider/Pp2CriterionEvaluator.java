package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Set;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Pinned, server-owned reviewed missense mechanism/spectrum PP2 engineering runtime. No acquisition or adoption. */
public final class Pp2CriterionEvaluator {
    public static final String CRITERION = "ACMG_AMP_2015:PP2";
    public static final String ID = "reviewed-missense-spectrum-pp2";
    public static final String VERSION = "1.0.0";
    public static final String SCOPE = "PP2_MISSENSE_DEVELOPMENT";
    public static final String DECLARATION = "ordinary-pp2-missense-v1";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;

    public Pp2CriterionEvaluator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper); adapter = new RuntimeEvidenceV2Adapter(mapper);
    }

    public ObjectNode evaluate(ObjectNode context, VariantInterpretation candidate, ObjectNode policy,
            ObjectNode selection, ObjectNode model, ObjectNode spectrum, ObjectNode resourceProfile) {
        ObjectNode result = mapper.createObjectNode().put("criterionId", CRITERION)
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("providerCalls", 0).put("evaluatorCalls", 0)
                .put("sequenceCalculations", 0).put("classificationEnabled", false).put("adoptionSupported", false)
                .put("adoptionStatus", "NOT_SUPPORTED").put("adoptionReasonCode", "NEW_POLICY_ADOPTION_NOT_INTEGRATED");
        result.set("context", context.deepCopy()); result.set("policySelection", selection.deepCopy());
        result.set("policy", policy.deepCopy());
        result.set("spectrumMaterial", spectrum.deepCopy());
        result.set("registryBinding", resourceProfile.path("registryBinding").deepCopy());
        ObjectNode observation = mapper.createObjectNode();
        result.set("resolvedInput", observation);
        String issue;
        try { adapter.validateCompleteContext(context); issue = new Pp2SpectrumPolicy(adapter).validate(context, policy, selection, model, spectrum); }
        catch (IllegalArgumentException invalid) { issue = "PP2_CONTEXT_IDENTITY_INVALID"; }
        if (issue != null) return finish(result, context, policy, selection, observation, issue);
        if (!validRegistry(resourceProfile, policy)) return finish(result, context, policy, selection, observation, "PP2_REGISTRY_IDENTITY_INVALID");
        observation.put("resourceProfileSha256", adapter.canonicalSha256(resourceProfile));
        try {
            var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
            var binding = wiring.bind(resourceProfile, resourceProfile.path("vepResourceId").asText());
            if (!"VEP".equals(binding.getProvider()) || !"116.0".equals(binding.getProviderVersion()))
                return finish(result, context, policy, selection, observation, "PP2_ANNOTATION_SOURCE_INVALID");
            TranscriptConsequence tx = wiring.selectedVepConsequence(context, candidate);
            if (tx == null) return finish(result, context, policy, selection, observation, "PP2_SELECTED_TRANSCRIPT_NOT_UNIQUE");
            ObjectNode atomic = wiring.projectStoredTranscript(context, candidate, tx, binding);
            result.set("evidence", atomic); result.put("storedVepProjections", 1);
            ObjectNode annotation = mapper.valueToTree(tx);
            annotation.put("timestamp", Objects.toString(tx.getTimestamp(), null));
            observation.put("storedAnnotationSha256", adapter.canonicalSha256(annotation))
                    .put("transcriptModelSha256", adapter.canonicalSha256(model))
                    .put("spectrumMaterialSha256", adapter.canonicalSha256(spectrum));
            result.set("storedAnnotation", annotation);
            result.put("sequenceCalculations", 1);
            issue = "missense_variant".equals(tx.getConsequence())
                    ? CodingMissenseResolver.resolve(context, tx, model, observation, adapter)
                    : "PP2_PURE_MISSENSE_SNV_REQUIRED";
            if (issue != null) issue = issue.replace("CODING_MISSENSE_", "PP2_SEQUENCE_");
            observation.put("strand", model.path("strand").asInt());
            if (issue == null) issue = new Pp2SpectrumPolicy(adapter).resolve(spectrum, observation);
        } catch (IllegalArgumentException | ArithmeticException invalid) { issue = "PP2_VARIANT_OR_ANNOTATION_IDENTITY_INVALID"; }
        return finish(result, context, policy, selection, observation, issue);
    }

    private ObjectNode finish(ObjectNode result, ObjectNode context, ObjectNode policy, ObjectNode selection, ObjectNode observation, String issue) {
        // A conflicting material set has no scientific review workflow/record in this bounded backend.
        // Preserve its facts and explicit refusal; do not invent a full review envelope.
        if ("PP2_CONDITION_UNKNOWN_OR_CONFLICT".equals(issue)) return refuse(result, issue);
        try { adapter.validateCompleteContext(context); }
        catch (IllegalArgumentException invalid) { return refuse(result, Objects.toString(issue, "PP2_CONTEXT_IDENTITY_INVALID")); }
        if (!new Pp2SpectrumPolicy(adapter).evaluationIdentityValid(context, policy, selection) || !validRegistry(result, policy))
            return refuse(result, Objects.toString(issue, "PP2_EVALUATION_IDENTITY_INVALID"));
        boolean negative = Set.of("PP2_MISSENSE_MECHANISM_NOT_SUPPORTED", "PP2_LOW_BENIGN_BACKGROUND_NOT_SUPPORTED").contains(Objects.toString(issue, ""));
        boolean complete = issue == null || negative;
        boolean unsupported = issue != null && issue.contains("UNSUPPORTED");
        String reason = issue == null ? "PP2_REVIEWED_MECHANISM_AND_LOW_BENIGN_BACKGROUND" : issue;
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
        evaluation.putObject("ruleBinding").put("currentGeneralDesignation", "PP2")
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
        trace.putArray("steps").addObject().put("stepCode", issue == null ? "PINNED_SEQUENCE_AND_TWO_REVIEWED_CONDITIONS_AGREE" : issue)
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
        try (var input = Pp2CriterionEvaluator.class.getResourceAsStream(binding.path("registryClasspathResource").asText())) {
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
                            || !"PATHOGENIC".equals(criterion.path("direction").asText())
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
