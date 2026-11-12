package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

/** Validation of the implemented FAF95 policy vocabulary, independently of policy selection. */
final class PopulationFrequencyPolicy {
    private PopulationFrequencyPolicy() { }

    static String issue(JsonNode policy, String criterionId) {
        if (policy == null || !policy.isObject()
                || !"population-frequency-policy-v1".equals(policy.path("schemaVersion").asText()))
            return "POPULATION_FREQUENCY_POLICY_INVALID";
        if (!"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText()))
            return "POPULATION_FREQUENCY_POLICY_NOT_REVIEWED";
        if (policy.path("policyId").asText().isBlank() || policy.path("policyVersion").asText().isBlank()
                || policy.at("/review/reviewId").asText().isBlank()) return "POPULATION_FREQUENCY_POLICY_IDENTITY_MISSING";
        JsonNode criteria = policy.path("criterionIds");
        Set<String> criterionIds = new HashSet<>();
        if (!criteria.isArray() || criteria.isEmpty()) return "POPULATION_FREQUENCY_POLICY_CRITERION_REQUIRED";
        for (JsonNode id : criteria)
            if (!id.isTextual() || !Set.of(PopulationFrequencyRuntime.BA1, PopulationFrequencyRuntime.BS1).contains(id.asText())
                    || !criterionIds.add(id.asText())) return "POPULATION_FREQUENCY_POLICY_CRITERION_INVALID";
        if (!criterionIds.contains(criterionId)) return "POPULATION_FREQUENCY_POLICY_CRITERION_NOT_APPLICABLE";
        JsonNode scope = policy.path("scope");
        if (!scope.path("hgncId").asText().matches("HGNC:[0-9]+")
                || !scope.path("mondoId").asText().matches("MONDO:[0-9]{7}")
                || !Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE", "X_LINKED", "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE")
                        .contains(scope.path("moi").asText())
                || !"GRCh38".equals(scope.path("assembly").asText())
                || !"NATIVE_NONPROJECTED_SNV".equals(scope.path("variantType").asText()))
            return "POPULATION_FREQUENCY_POLICY_SCOPE_UNSUPPORTED";
        JsonNode sources = policy.path("sources");
        if (!sources.isArray() || sources.isEmpty()) return "POPULATION_FREQUENCY_POLICY_SOURCE_MISSING";
        Set<String> authorities = new HashSet<>();
        for (JsonNode source : sources) {
            if (source.path("authoritySourceId").asText().isBlank()
                    || !authorities.add(source.path("authoritySourceId").asText())
                    || !source.path("url").asText().startsWith("https://")
                    || source.path("version").asText().isBlank()
                    || !source.path("snapshotSha256").asText().matches("[0-9a-f]{64}"))
                return "POPULATION_FREQUENCY_POLICY_SOURCE_INVALID";
        }
        JsonNode official = policy.path("officialConditions");
        if (!"gnomad.joint.faf95max".equals(official.path("metric").asText())
                || !"fraction".equals(official.path("unit").asText())
                || official.has("infoId") && !"fafmax_faf95_max_joint".equals(official.path("infoId").asText())
                || official.has("ancestryInfoId") && !"fafmax_faf95_max_gen_anc_joint".equals(official.path("ancestryInfoId").asText())
                || official.has("confidence") && !"95_PERCENT_LOWER_BOUND".equals(official.path("confidence").asText())
                || official.has("groupSelection") && !"HIGHEST_FAF_NOT_HIGHEST_AF".equals(official.path("groupSelection").asText()))
            return "POPULATION_FREQUENCY_POLICY_METRIC_UNSUPPORTED";
        String designation = criterionId.substring(criterionId.indexOf(':') + 1);
        JsonNode rule = official.path(designation);
        if (!rule.isObject() || rule.path("operator").asText().isBlank())
            return "POPULATION_FREQUENCY_THRESHOLD_INVALID";
        Set<String> commonFields = Set.of("metric", "unit", "infoId", "ancestryInfoId", "confidence", "groupSelection",
                "BA1", "BS1", "PM2", "bs1IndependentMinimumAn");
        for (var names = official.fieldNames(); names.hasNext();)
            if (!commonFields.contains(names.next())) return "POPULATION_FREQUENCY_POLICY_OPERATOR_UNSUPPORTED";
        Set<String> ruleFields = Set.of("operator", "decimal", "strength", "ratioText");
        for (var names = rule.fieldNames(); names.hasNext();)
            if (!ruleFields.contains(names.next())) return "POPULATION_FREQUENCY_POLICY_OPERATOR_UNSUPPORTED";
        if (!Set.of("GREATER_THAN", "GREATER_THAN_OR_EQUAL").contains(rule.path("operator").asText())
                || !("BA1".equals(designation) ? "STANDALONE" : "STRONG").equals(rule.path("strength").asText())
                || "BS1".equals(designation) && official.hasNonNull("bs1IndependentMinimumAn"))
            return "POPULATION_FREQUENCY_POLICY_OPERATOR_UNSUPPORTED";
        try {
            if (!rule.path("decimal").isTextual()) return "POPULATION_FREQUENCY_THRESHOLD_INVALID";
            BigDecimal value = new BigDecimal(rule.path("decimal").asText());
            if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0)
                return "POPULATION_FREQUENCY_THRESHOLD_INVALID";
            JsonNode minimum = policy.at("/engineeringConditions/minimumSelectedAncestryAn/" + designation);
            if (!minimum.isTextual() || new BigInteger(minimum.asText()).signum() <= 0)
                return "POPULATION_FREQUENCY_MINIMUM_AN_INVALID";
        } catch (NumberFormatException invalid) {
            return "POPULATION_FREQUENCY_THRESHOLD_INVALID";
        }
        JsonNode quality = policy.path("engineeringConditions");
        if (!"VCF_FLOAT32_WITH_DECIMAL_ROUNDING".equals(quality.path("afCountPrecision").asText()))
            return "POPULATION_FREQUENCY_AF_PRECISION_POLICY_REQUIRED";
        JsonNode precisionSources = quality.path("afCountPrecisionSourceIds");
        Set<String> precisionIds = new HashSet<>();
        if (!precisionSources.isArray() || precisionSources.isEmpty()) return "POPULATION_FREQUENCY_AF_PRECISION_SOURCE_REQUIRED";
        for (JsonNode source : precisionSources)
            if (!source.isTextual() || !authorities.contains(source.asText()) || !precisionIds.add(source.asText()))
                return "POPULATION_FREQUENCY_AF_PRECISION_SOURCE_UNBOUND";
        if (quality.path("minimumAnBasis").asText().isBlank()
                || !"POSITIVE_INTEGER".equals(quality.path("selectedAn").asText())
                || !"INTEGER_BETWEEN_ZERO_AND_AN".equals(quality.path("selectedAc").asText())
                || !quality.path("fafMatchesSelectedAncestry").isBoolean()
                || !quality.path("fafMatchesSelectedAncestry").asBoolean()
                || !"ABSTAIN_IF_P_LT_0_0001_OR_UNKNOWN".equals(quality.path("discordancePolicy").asText())
                || !"PRESERVE_AND_REQUIRE_PASS_OR_DOT".equals(quality.path("subtypeFlags").asText()))
            return "POPULATION_FREQUENCY_QUALITY_POLICY_UNSUPPORTED";
        JsonNode ancestries = quality.path("applicableAncestries");
        Set<String> unique = new HashSet<>();
        if (!ancestries.isArray() || ancestries.isEmpty()) return "POPULATION_FREQUENCY_ANCESTRY_POLICY_REQUIRED";
        for (JsonNode ancestry : ancestries)
            if (!ancestry.isTextual() || !unique.add(ancestry.asText()))
                return "POPULATION_FREQUENCY_ANCESTRY_POLICY_INVALID";
        if (!policy.path("exceptionReview").isObject()
                || policy.at("/exceptionReview/reviewId").asText().isBlank()
                || policy.at("/exceptionReview/scope").asText().isBlank()
                || !"REVIEWED_NO_RELEVANT_EXCEPTION_IDENTIFIED_IN_PINNED_SOURCES"
                        .equals(policy.at("/exceptionReview/status").asText())
                || !policy.at("/exceptionReview/unresolvedAlleles").isArray())
            return "POPULATION_FREQUENCY_EXCEPTION_REVIEW_REQUIRED";
        return null;
    }

    static String contextIssue(JsonNode policy, JsonNode context) {
        if (context == null || !context.isObject()) return "POPULATION_FREQUENCY_DECLARATION_REQUIRED";
        JsonNode scope = policy.path("scope");
        if (!scope.path("hgncId").equals(context.at("/gene/hgncId"))
                || !scope.path("mondoId").equals(context.at("/disease/mondoId"))
                || !scope.path("moi").equals(context.path("moi")))
            return "POPULATION_FREQUENCY_POLICY_NOT_APPLICABLE";
        String selection = context.at("/transcript/selectionPolicy").asText();
        if (selection.isBlank() || "PM2_CONTEXT_ONLY".equals(selection)
                || org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.PM2_CONTEXT_SELECTION.equals(selection))
            return "POPULATION_FREQUENCY_DECLARATION_REQUIRED";
        if (scope.has("transcripts")) {
            boolean matched = false;
            for (JsonNode transcript : scope.path("transcripts"))
                if (transcript.path("accession").equals(context.at("/transcript/accession"))
                        && transcript.path("version").equals(context.at("/transcript/version"))) matched = true;
            if (!matched) return "POPULATION_FREQUENCY_TRANSCRIPT_NOT_APPLICABLE";
        }
        JsonNode allele = context.at("/variant/evidenceQueryAllele");
        if (!scope.path("assembly").equals(allele.path("assembly"))) return "POPULATION_FREQUENCY_ASSEMBLY_UNSUPPORTED";
        if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !"MATCH".equals(context.at("/variant/normalization/refValidation").asText())
                || !"SNV".equals(context.at("/variant/variantType").asText())
                || !allele.path("reference").asText().matches("[ACGT]")
                || !allele.path("alternate").asText().matches("[ACGT]")
                || allele.path("reference").equals(allele.path("alternate")))
            return "POPULATION_FREQUENCY_NATIVE_SNV_REQUIRED";
        return null;
    }
}
