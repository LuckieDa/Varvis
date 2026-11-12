package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.nbscn.varvis.variantanalysis.service.AcmgRecommendations;

/** Executable vocabulary for one reviewed prediction clause; never infers a disease policy. */
final class PredictionClausePolicy {
    static final String PP3 = "ACMG_AMP_2015:PP3";
    static final String BP4 = "ACMG_AMP_2015:BP4";
    static final String BP7 = "ACMG_AMP_2015:BP7";
    static final Set<String> CRITERIA = Set.of(PP3, BP4, BP7);
    private PredictionClausePolicy() { }

    /** Separate successor vocabulary; the frozen v1 single-threshold contract is unchanged. */
    static String coreIssue(JsonNode policy, JsonNode calibration) {
        if (!"prediction-core-policy-v1".equals(policy.path("schemaVersion").asText())
                || !"ISOLATED_DEVELOPMENT_ONLY".equals(policy.path("activation").asText())
                || !"BEFORE_SCORE_LOOKUP_NO_FALLBACK".equals(policy.path("modelSelection").asText())
                || !"MECHANISM_APPLICATION_V1".equals(policy.path("applicationContract").asText())
                || !policy.path("allowedPp3Mechanisms").isArray() || policy.path("allowedPp3Mechanisms").isEmpty()
                || !policy.path("requiredBp4Mechanisms").isArray() || policy.path("requiredBp4Mechanisms").isEmpty()) return "PREDICTION_CORE_POLICY_INVALID";
        for (String field : java.util.List.of("allowedPp3Mechanisms", "requiredBp4Mechanisms")) {
            var names = new java.util.HashSet<String>();
            for (var name : policy.path(field)) if (!java.util.Set.of("PROTEIN_MISSENSE", "SPLICING").contains(name.asText()) || !names.add(name.asText())) return "PREDICTION_CORE_POLICY_INVALID";
            if (!names.contains(policy.path("primaryMechanism").asText())) return "PREDICTION_CORE_POLICY_INVALID";
        }
        if (!java.util.Set.of("WHEN_PRIMARY_NOT_HARMFUL", "DISABLED").contains(policy.path("secondaryPp3Route").asText())) return "PREDICTION_CORE_POLICY_INVALID";
        return PredictionCalibration.issue(calibration);
    }

    static String issue(JsonNode context, JsonNode policy, JsonNode receipt, RuntimeEvidenceV2Adapter adapter) {
        if (context == null || !context.isObject()) return "PREDICTION_CONTEXT_REQUIRED";
        try { adapter.validateCompleteContext(context); }
        catch (IllegalArgumentException invalid) { return "PREDICTION_CONTEXT_INVALID"; }
        if (!(receipt instanceof ObjectNode object)) return "PREDICTION_CLAUSE_SELECTION_REQUIRED";
        ObjectNode identity = object.deepCopy(); identity.remove("selectionId");
        String criterion = singleCriterion(receipt.path("criterionIds"));
        if (criterion == null || !"acmg-specification-selection-v1".equals(receipt.path("schemaVersion").asText())
                || !context.path("contextId").equals(receipt.path("contextId"))
                || !("cspec-selection-sha256-" + adapter.canonicalSha256(identity)).equals(receipt.path("selectionId").asText())
                || !text(receipt.path("catalogId")) || !text(receipt.path("catalogVersion"))
                || !sha(receipt.path("catalogSha256")) || !text(receipt.path("searchScope")))
            return "PREDICTION_CLAUSE_SELECTION_IDENTITY_INVALID";
        try { Instant.parse(receipt.path("searchedAt").asText()); }
        catch (RuntimeException invalid) { return "PREDICTION_CLAUSE_SELECTION_IDENTITY_INVALID"; }
        if (!Set.of("SPECIALIZED_POLICY_SELECTED", "GENERAL_POLICY_SELECTED").contains(receipt.path("outcome").asText()))
            return text(receipt.path("reasonCode")) ? receipt.path("reasonCode").asText() : "PREDICTION_CLAUSE_SELECTION_INCOMPLETE";
        if (!text(receipt.path("catalogEntryId")) || policy == null || !policy.isObject()
                || !receipt.path("policyId").equals(policy.path("policyId"))
                || !receipt.path("policyVersion").equals(policy.path("policyVersion"))
                || !adapter.canonicalSha256(policy).equals(receipt.path("policySha256").asText()))
            return "PREDICTION_CLAUSE_POLICY_BINDING_INVALID";
        if (!"prediction-clause-policy-v1".equals(policy.path("schemaVersion").asText())
                || !criterion.equals(singleCriterion(policy.path("criterionIds")))
                || !text(policy.path("policyId")) || !text(policy.path("policyVersion"))
                || !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || !onlyFields(policy.path("review"), Set.of("status", "reviewId"))
                || !text(policy.at("/review/reviewId")) || !"SUPPORTING".equals(policy.path("appliedStrength").asText()))
            return "PREDICTION_REVIEWED_CLAUSE_POLICY_REQUIRED";
        if (!onlyFields(policy, Set.of("schemaVersion", "policyId", "policyVersion", "criterionIds", "review", "scope",
                "appliedStrength", "preselectedModel", "modelSelection", "missingOrGreyPolicy", "evidenceMechanism", "sources",
                "calibrationSourceIds", "scopeRationale", "additionalRequirements", "resourceBinding", "revelTranscriptBinding",
                "transcriptModelBinding", "discordanceReview", "bp7Dependency", "positionProfileSha256", "threshold",
                "consequenceScope", "intronicPositionScope")))
            return "PREDICTION_POLICY_VOCABULARY_NOT_IMPLEMENTED";
        JsonNode scope = policy.path("scope");
        if (!onlyFields(scope, Set.of("hgncId", "mondoId", "moi", "assembly", "variantType", "transcriptMode", "transcripts")))
            return "PREDICTION_SCOPE_VOCABULARY_NOT_IMPLEMENTED";
        if (!context.at("/gene/hgncId").equals(scope.path("hgncId"))
                || !context.at("/disease/mondoId").equals(scope.path("mondoId"))
                || !context.path("moi").equals(scope.path("moi"))
                || !context.at("/variant/evidenceQueryAllele/assembly").equals(scope.path("assembly")))
            return "PREDICTION_POLICY_CONTEXT_NOT_APPLICABLE";
        String transcriptMode = scope.path("transcriptMode").asText();
        if (!Set.of("ANY_VALIDATED_TRANSCRIPT", "EXACT_ACCESSIONS").contains(transcriptMode)
                || "EXACT_ACCESSIONS".equals(transcriptMode) && !matchesTranscript(context, scope.path("transcripts")))
            return "PREDICTION_POLICY_TRANSCRIPT_NOT_APPLICABLE";
        if (scope.has("transcripts") && !matchesTranscript(context, scope.path("transcripts")))
            return "PREDICTION_POLICY_TRANSCRIPT_NOT_APPLICABLE";
        String confirmation = context.at("/transcript/selectionPolicy").asText();
        if (AcmgRecommendations.populationOnlySelection(confirmation) || "PM2_CONTEXT_ONLY".equals(confirmation))
            return "PREDICTION_TRANSCRIPT_CONFIRMATION_REQUIRED";
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        boolean snv = ref.matches("[ACGT]") && alt.matches("[ACGT]") && !ref.equals(alt)
                && "SNV".equals(context.at("/variant/variantType").asText());
        if (!"GRCh38".equals(allele.path("assembly").asText())
                || !"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !"NATIVE_NONPROJECTED_SNV".equals(scope.path("variantType").asText()) || !snv)
            return "PREDICTION_NATIVE_SNV_SCOPE_NOT_IMPLEMENTED";
        String model = policy.path("preselectedModel").asText();
        if (!Set.of("ALPHAMISSENSE", "REVEL", "SPLICEAI").contains(model)
                || !"BEFORE_SCORE_LOOKUP_NO_FALLBACK".equals(policy.path("modelSelection").asText())
                || !"NO_MODEL_SWITCH_NO_VOTING".equals(policy.path("missingOrGreyPolicy").asText()))
            return "PREDICTION_PRESELECTED_MODEL_REQUIRED";
        String mechanism = policy.path("evidenceMechanism").asText();
        if (!("SPLICEAI".equals(model) ? "SPLICING" : "PROTEIN_MISSENSE").equals(mechanism)
                || BP7.equals(criterion) && !"SPLICEAI".equals(model))
            return "PREDICTION_MODEL_MECHANISM_MISMATCH";
        JsonNode sources = policy.path("sources");
        if (!CodingTranscriptMapping.sourcesValid(sources) || !bound(policy.path("calibrationSourceIds"), sources)
                || !bound(receipt.path("sourceIds"), sources) || !text(policy.path("scopeRationale")))
            return "PREDICTION_SCIENTIFIC_SOURCE_REQUIRED";
        JsonNode requirements = policy.path("additionalRequirements");
        if (!requirements.isArray()) return "PREDICTION_ADDITIONAL_REQUIREMENTS_UNDECLARED";
        if (!requirements.isEmpty()) return "PREDICTION_SPECIALIZED_REQUIREMENT_NOT_IMPLEMENTED";
        if (policy.has("consequenceScope")) {
            JsonNode consequence = policy.path("consequenceScope");
            if (!Set.of(BP4, BP7).contains(criterion) || !"SPLICEAI".equals(model)
                    || !onlyFields(consequence, Set.of("mode", "sourceIds"))
                    || !"SYNONYMOUS_OR_INTRONIC_ONLY".equals(consequence.path("mode").asText())
                    || !bound(consequence.path("sourceIds"), sources))
                return "PREDICTION_CONSEQUENCE_SCOPE_UNSUPPORTED";
        }
        if (policy.has("intronicPositionScope")) {
            JsonNode position = policy.path("intronicPositionScope");
            if (!BP4.equals(criterion) || !policy.has("consequenceScope")
                    || !onlyFields(position, Set.of("mode", "sourceIds"))
                    || !"OUTSIDE_CANONICAL_SPLICE_DINUCLEOTIDES".equals(position.path("mode").asText())
                    || !bound(position.path("sourceIds"), sources))
                return "PREDICTION_INTRONIC_POSITION_SCOPE_UNSUPPORTED";
        }
        JsonNode source = policy.path("resourceBinding");
        if (!text(source.path("resourceId")) || !text(source.path("resourceVersion"))
                || !sha(source.path("identityFingerprintSha256"))) return "PREDICTION_RESOURCE_IDENTITY_REQUIRED";
        if ("REVEL".equals(model)) {
            JsonNode transcriptBinding = policy.path("revelTranscriptBinding");
            if (!sha(source.path("sourceArtifactSha256")) || !"EXACT_ACCESSIONS".equals(transcriptMode)
                    || !onlyFields(transcriptBinding, Set.of("accession", "status", "sourceIds"))
                    || !onlyFields(policy.path("transcriptModelBinding"), Set.of("modelId", "modelVersion", "canonicalSha256"))
                    || !transcriptBinding.path("accession").asText().matches("ENST[0-9]+")
                    || !"REVIEWED_ACCESSION_BRIDGE_WITH_ALLELE_SPECIFIC_SEQUENCE_CHECK".equals(transcriptBinding.path("status").asText())
                    || !bound(transcriptBinding.path("sourceIds"), sources)
                    || !sha(policy.at("/transcriptModelBinding/canonicalSha256"))
                    || !text(policy.at("/transcriptModelBinding/modelId")) || !text(policy.at("/transcriptModelBinding/modelVersion")))
                return "PREDICTION_REVEL_TRANSCRIPT_RESOURCE_BRIDGE_REQUIRED";
        } else if (policy.has("revelTranscriptBinding") || policy.has("transcriptModelBinding"))
            return "PREDICTION_POLICY_VOCABULARY_NOT_IMPLEMENTED";
        if (!onlyFields(source, "REVEL".equals(model)
                ? Set.of("resourceId", "resourceVersion", "identityFingerprintSha256", "sourceArtifactSha256")
                : Set.of("resourceId", "resourceVersion", "identityFingerprintSha256")))
            return "PREDICTION_RESOURCE_BINDING_VOCABULARY_NOT_IMPLEMENTED";
        JsonNode discordance = policy.path("discordanceReview");
        if (!onlyFields(discordance, Set.of("status", "reviewId", "scope", "sourceIds", "knownDiscordantAlleles"))
                || !"REVIEWED_PINNED_SOURCES_NO_AUTO_RESOLUTION".equals(discordance.path("status").asText())
                || !text(discordance.path("reviewId")) || !text(discordance.path("scope"))
                || !bound(discordance.path("sourceIds"), sources) || !discordance.path("knownDiscordantAlleles").isArray())
            return "PREDICTION_DISCORDANCE_REVIEW_REQUIRED";
        for (JsonNode entry : discordance.path("knownDiscordantAlleles")) {
            if (!onlyFields(entry, Set.of("alleleId", "reasonCode", "sourceIds"))
                    || !entry.path("alleleId").asText().matches("allele-sha256-[0-9a-f]{64}")
                    || !text(entry.path("reasonCode")) || !bound(entry.path("sourceIds"), sources))
                return "PREDICTION_DISCORDANCE_IDENTITY_INVALID";
            if (entry.path("alleleId").equals(context.at("/variant/alleleId"))) return "PREDICTION_KNOWN_DISCORDANCE_REQUIRES_REVIEW";
        }
        if (BP7.equals(criterion)) {
            if (policy.has("threshold") || !onlyFields(policy.path("bp7Dependency"), Set.of("mode", "policyId", "policyVersion", "policySha256"))
                    || !"SAME_CONTEXT_SAME_SPLICEAI_EVIDENCE_BP4".equals(policy.at("/bp7Dependency/mode").asText())
                    || !sha(policy.at("/bp7Dependency/policySha256"))
                    || !text(policy.at("/bp7Dependency/policyId")) || !text(policy.at("/bp7Dependency/policyVersion"))
                    || !"b128e11f4d0e074422b771d195adbc3acdd893a66a56b600de9ff0e1518803e6".equals(policy.path("positionProfileSha256").asText()))
                return "PREDICTION_BP7_DEPENDENCY_OR_POSITION_POLICY_UNSUPPORTED";
        } else {
            if (policy.has("bp7Dependency") || policy.has("positionProfileSha256")) return "PREDICTION_POLICY_VOCABULARY_NOT_IMPLEMENTED";
            JsonNode threshold = policy.path("threshold");
            String operator = threshold.path("operator").asText();
            if (!(PP3.equals(criterion) ? Set.of("GT", "GE") : Set.of("LT", "LE")).contains(operator)
                    || decimal(threshold.path("decimal")) == null || !"score".equals(threshold.path("unit").asText()))
                return "PREDICTION_THRESHOLD_INVALID";
            for (var keys = threshold.fieldNames(); keys.hasNext();)
                if (!Set.of("operator", "decimal", "unit").contains(keys.next())) return "PREDICTION_THRESHOLD_OPERATOR_UNSUPPORTED";
        }
        return null;
    }

    static String singleCriterion(JsonNode values) {
        return values.isArray() && values.size() == 1 && CRITERIA.contains(values.path(0).asText()) ? values.path(0).asText() : null;
    }
    static BigDecimal decimal(JsonNode node) {
        if (!node.isTextual() || node.asText().length() > 40 || !node.asText().matches("(?:0(?:\\.[0-9]+)?|1(?:\\.0+)?)")) return null;
        try { return new BigDecimal(node.asText()); } catch (NumberFormatException invalid) { return null; }
    }
    static boolean met(BigDecimal score, JsonNode threshold) {
        BigDecimal boundary = decimal(threshold.path("decimal"));
        if (boundary == null) throw new IllegalArgumentException("Invalid reviewed threshold");
        int comparison = score.compareTo(boundary);
        return switch (threshold.path("operator").asText()) {
            case "GE" -> comparison >= 0; case "GT" -> comparison > 0;
            case "LE" -> comparison <= 0; case "LT" -> comparison < 0;
            default -> throw new IllegalArgumentException("Unknown reviewed comparison");
        };
    }
    private static boolean matchesTranscript(JsonNode context, JsonNode values) {
        if (!values.isArray() || values.isEmpty()) return false;
        boolean matched = false;
        for (JsonNode tx : values) {
            if (!onlyFields(tx, Set.of("accession", "version")) || !text(tx.path("accession"))
                    || !tx.path("version").isIntegralNumber() || !tx.path("version").canConvertToInt()
                    || tx.path("version").asInt() < 1) return false;
            matched |= context.at("/transcript/accession").equals(tx.path("accession"))
                    && context.at("/transcript/version").equals(tx.path("version"));
        }
        return matched;
    }
    private static boolean bound(JsonNode ids, JsonNode sources) {
        if (!ids.isArray() || ids.isEmpty()) return false;
        Set<String> unique = new HashSet<>();
        for (JsonNode id : ids) {
            if (!text(id) || !unique.add(id.asText())) return false;
            boolean found = false;
            for (JsonNode source : sources) if (id.equals(source.path("sourceId"))) found = true;
            if (!found) return false;
        }
        return true;
    }
    private static boolean sha(JsonNode value) { return value.isTextual() && value.asText().matches("[0-9a-f]{64}"); }
    private static boolean text(JsonNode value) { return value.isTextual() && !value.asText().isBlank(); }
    private static boolean onlyFields(JsonNode value, Set<String> allowed) {
        if (!value.isObject()) return false;
        for (var names = value.fieldNames(); names.hasNext();) if (!allowed.contains(names.next())) return false;
        return true;
    }
}
