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

/** Pinned, server-owned simple in-frame PM4 engineering runtime. No acquisition or adoption. */
public final class Pm4CriterionEvaluator {
    public static final String CRITERION = "ACMG_AMP_2015:PM4";
    public static final String ID = "simple-inframe-length-pm4";
    public static final String VERSION = "1.0.0";
    public static final String SCOPE = "PM4_INFRAME_DEVELOPMENT";
    public static final String DECLARATION = "ordinary-pm4-inframe-v1";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;

    public Pm4CriterionEvaluator(ObjectMapper mapper) {
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
        result.set("registryBinding", resourceProfile.path("registryBinding").deepCopy());
        ObjectNode observation = mapper.createObjectNode();
        result.set("resolvedInput", observation);
        String issue;
        try { adapter.validateCompleteContext(context); issue = policyIssue(context, policy, selection, model, regions); }
        catch (IllegalArgumentException invalid) { issue = "PM4_CONTEXT_IDENTITY_INVALID"; }
        if (issue != null) return finish(result, context, policy, selection, observation, issue);
        if (!validRegistry(resourceProfile)) return finish(result, context, policy, selection, observation, "PM4_REGISTRY_IDENTITY_INVALID");
        observation.put("resourceProfileSha256", adapter.canonicalSha256(resourceProfile));
        try {
            var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
            var binding = wiring.bind(resourceProfile, resourceProfile.path("vepResourceId").asText());
            if (!"VEP".equals(binding.getProvider()) || !"116.0".equals(binding.getProviderVersion()))
                return finish(result, context, policy, selection, observation, "PM4_ANNOTATION_SOURCE_INVALID");
            TranscriptConsequence tx = wiring.selectedVepConsequence(context, candidate);
            if (tx == null) return finish(result, context, policy, selection, observation, "PM4_SELECTED_TRANSCRIPT_NOT_UNIQUE");
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
            if (issue == null) issue = regionIssue(regions, observation);
            if (issue == null && observation.path("netAminoAcidChange").asInt() == 0) issue = "PM4_NO_NET_LENGTH_CHANGE";
        } catch (IllegalArgumentException | ArithmeticException invalid) { issue = "PM4_VARIANT_OR_ANNOTATION_IDENTITY_INVALID"; }
        // Keep full sequences in direct synthetic observation tests, not in the business response.
        finally { observation.remove(List.of("referenceCds", "alteredCds", "referenceProtein", "alteredProtein")); }
        return finish(result, context, policy, selection, observation, issue);
    }

    // Also used by the offline preparer; the scientific checks are identical to runtime evaluation.
    String policyIssue(ObjectNode context, ObjectNode policy, ObjectNode receipt, ObjectNode model, ObjectNode regions) {
        if (!only(policy, Set.of("schemaVersion", "policyId", "policyVersion", "criterionIds", "review", "scope", "appliedStrength",
                "transcriptModelBinding", "regionMaterialBinding", "scientificSources", "authoritySourceIds", "branch"))
                || !only(policy.path("scope"), Set.of("hgncId", "mondoId", "moi", "assembly", "variantType", "transcriptMode", "transcripts")))
            return "PM4_POLICY_OPERATOR_UNSUPPORTED";
        if (!"pm4-simple-inframe-policy-v1".equals(policy.path("schemaVersion").asText())
                || !"SIMPLE_INFRAME_INDEL_NON_REPEAT".equals(policy.path("branch").asText())
                || !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || policy.at("/review/reviewId").asText().isBlank()
                || !Set.of("MODERATE", "SUPPORTING").contains(policy.path("appliedStrength").asText())
                || policy.path("criterionIds").size() != 1 || !CRITERION.equals(policy.at("/criterionIds/0").asText()))
            return "PM4_POLICY_INVALID";
        ObjectNode receiptIdentity = receipt.deepCopy(); receiptIdentity.remove("selectionId");
        if (!"acmg-specification-selection-v1".equals(receipt.path("schemaVersion").asText())
                || !Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText())
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(receiptIdentity)).equals(receipt.path("selectionId").asText())
                || !context.path("contextId").equals(receipt.path("contextId"))
                || !policy.path("policyId").equals(receipt.path("policyId"))
                || !policy.path("policyVersion").equals(receipt.path("policyVersion"))
                || !policy.path("criterionIds").equals(receipt.path("criterionIds"))
                || !adapter.canonicalSha256(policy).equals(receipt.path("policySha256").asText())) return "PM4_SELECTION_IDENTITY_INVALID";
        JsonNode scope = policy.path("scope");
        if (!context.at("/gene/hgncId").equals(scope.path("hgncId")) || !context.at("/disease/mondoId").equals(scope.path("mondoId"))
                || !context.path("moi").equals(scope.path("moi")) || !"GRCh38".equals(scope.path("assembly").asText())
                || !"NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT".equals(scope.path("variantType").asText())
                || !"EXACT_ACCESSIONS".equals(scope.path("transcriptMode").asText()) || scope.path("transcripts").size() != 1
                || !context.at("/transcript/accession").equals(scope.at("/transcripts/0/accession"))
                || !context.at("/transcript/version").equals(scope.at("/transcripts/0/version"))) return "PM4_POLICY_CONTEXT_MISMATCH";
        if (!bound(policy.path("transcriptModelBinding"), model, "modelId", "modelVersion")) return "PM4_TRANSCRIPT_MODEL_IDENTITY_INVALID";
        if (!bound(policy.path("regionMaterialBinding"), regions, "materialId", "materialVersion")) return "PM4_REGION_MATERIAL_IDENTITY_INVALID";
        if (!CodingTranscriptMapping.sourcesValid(policy.path("scientificSources"))
                || !CodingTranscriptMapping.modelSourcesBound(model, policy.path("scientificSources"))
                || !CodingTranscriptMapping.sourcesValid(regions.path("sourceRecords"))) return "PM4_MATERIAL_SOURCE_INVALID";
        for (JsonNode source : regions.path("sourceRecords")) {
            boolean found = false;
            for (JsonNode trusted : policy.path("scientificSources")) if (source.equals(trusted)) found = true;
            if (!found) return "PM4_MATERIAL_SOURCE_INVALID";
        }
        if (policy.path("authoritySourceIds").isEmpty() || !policy.path("authoritySourceIds").isArray()) return "PM4_POLICY_AUTHORITY_MISSING";
        for (JsonNode id : policy.path("authoritySourceIds")) if (!id.isTextual() || id.asText().isBlank()) return "PM4_POLICY_AUTHORITY_MISSING";
        if (!only(regions, Set.of("schemaVersion", "materialId", "materialVersion", "modelSha256", "assembly", "contig", "hgncId", "transcript", "protein", "sourceRecords", "intervals")))
            return "PM4_REGION_OPERATOR_UNSUPPORTED";
        if (!"pm4-region-material-v1".equals(regions.path("schemaVersion").asText())
                || !adapter.canonicalSha256(model).equals(regions.path("modelSha256").asText())) return "PM4_REGION_MATERIAL_IDENTITY_INVALID";
        for (String field : List.of("assembly", "contig", "hgncId", "transcript", "protein"))
            if (!regions.path(field).equals(model.path(field))) return "PM4_REGION_MATERIAL_IDENTITY_INVALID";
        return null;
    }

    private boolean bound(JsonNode binding, JsonNode material, String id, String version) {
        return !material.path(id).asText().isBlank() && !material.path(version).asText().isBlank()
                && binding.path(id).equals(material.path(id)) && binding.path(version).equals(material.path(version))
                && adapter.canonicalSha256(material).equals(binding.path("canonicalSha256").asText());
    }

    private String regionIssue(JsonNode regions, JsonNode observation) {
        JsonNode intervals = regions.path("intervals");
        if (!intervals.isArray() || intervals.isEmpty()) return "PM4_REGION_COVERAGE_MISSING";
        for (JsonNode row : intervals) {
            if (!only(row, Set.of("start", "end", "repeatStatus", "functionalContext"))
                    || !row.path("start").isIntegralNumber() || !row.path("start").canConvertToInt()
                    || !row.path("end").isIntegralNumber() || !row.path("end").canConvertToInt()
                    || row.path("start").asInt() < 1 || row.path("end").asInt() < row.path("start").asInt()
                    || !Set.of("REPEAT", "NON_REPEAT", "UNKNOWN").contains(row.path("repeatStatus").asText())
                    || !Set.of("SUPPORTED", "NOT_SUPPORTED", "UNKNOWN").contains(row.path("functionalContext").asText()))
                return "PM4_REGION_MATERIAL_INVALID";
        }
        boolean repeat = false, unsupportedFunction = false;
        for (long base = observation.path("genomicCoverageStart").asInt(); base <= observation.path("genomicCoverageEnd").asInt(); base++) {
            JsonNode match = null;
            for (JsonNode row : intervals) if (base >= row.path("start").asInt() && base <= row.path("end").asInt()) {
                if (match != null) return "PM4_REGION_COVERAGE_AMBIGUOUS";
                match = row;
            }
            if (match == null || "UNKNOWN".equals(match.path("repeatStatus").asText()) || "UNKNOWN".equals(match.path("functionalContext").asText()))
                return "PM4_REGION_COVERAGE_INCOMPLETE";
            repeat |= "REPEAT".equals(match.path("repeatStatus").asText());
            unsupportedFunction |= "NOT_SUPPORTED".equals(match.path("functionalContext").asText());
        }
        return repeat ? "PM4_REPEAT_REGION" : unsupportedFunction ? "PM4_FUNCTIONAL_CONTEXT_NOT_MET" : null;
    }

    private ObjectNode finish(ObjectNode result, ObjectNode context, ObjectNode policy, ObjectNode selection, ObjectNode observation, String issue) {
        boolean negative = Set.of("PM4_REPEAT_REGION", "PM4_FUNCTIONAL_CONTEXT_NOT_MET", "PM4_NO_NET_LENGTH_CHANGE").contains(Objects.toString(issue, ""));
        boolean complete = issue == null || negative;
        boolean unsupported = issue != null && issue.contains("UNSUPPORTED");
        String reason = issue == null ? "PM4_SUPPORTED_NON_REPEAT_LENGTH_CHANGE" : issue;
        String disposition = complete ? "COMPLETE" : unsupported ? "UNSUPPORTED_BRANCH_OR_OPERATOR"
                : reason.contains("MISSING") || reason.contains("INCOMPLETE") ? "MISSING_INPUT" : "INVALID_INPUT";
        result.put("inputDisposition", disposition).put("evaluatorCalls", complete ? 1 : 0);
        ObjectNode evaluation = result.putObject("evaluation").put("schemaVersion", "criterion-evaluation-v2")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("criterionId", CRITERION).put("baseStrength", "MODERATE").put("appliedStrength", issue == null ? policy.path("appliedStrength").asText() : "NONE");
        evaluation.putObject("evaluationResult").put("status", issue == null ? "MET" : negative ? "NOT_MET" : "NOT_EVALUATED")
                .put("reasonCode", reason);
        ObjectNode applicability = evaluation.putObject("criterionApplicability")
                .put("status", complete ? "APPLICABLE" : unsupported ? "NOT_APPLICABLE" : "UNKNOWN");
        if (!complete) applicability.put("reasonCode", reason);
        evaluation.putObject("ruleBinding").put("currentGeneralDesignation", "PM4")
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
        return result;
    }

    private static boolean only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false;
        var names = node.fieldNames(); while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }

    boolean validRegistry(JsonNode profile) {
        JsonNode binding = profile.path("registryBinding");
        try (var input = Pm4CriterionEvaluator.class.getResourceAsStream(binding.path("registryClasspathResource").asText())) {
            if (input == null) return false;
            byte[] bytes = input.readAllBytes();
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            JsonNode registry = mapper.readTree(bytes);
            if (registry == null || !hash.equals(binding.path("registrySha256").asText())
                    || !registry.path("registryId").equals(binding.path("registryId"))) return false;
            int count = 0;
            for (JsonNode criterion : registry.path("criteria"))
                if (CRITERION.equals(criterion.path("criterionId").asText())) count++;
            return count == 1;
        } catch (IOException | NoSuchAlgorithmException invalid) { return false; }
    }
}
