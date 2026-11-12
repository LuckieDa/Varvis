package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Validates implemented assay operators, not the scientific truth of a submitted policy. */
final class FunctionalEvidencePolicy {
    private FunctionalEvidencePolicy() { }

    @Nullable static String issue(JsonNode policy) {
        if (!only(policy, Set.of("schemaVersion", "policyId", "policyVersion", "criterionIds", "review", "scope", "sources",
                "registryBinding", "assay", "nativeModelSha256", "annotationResourceSha256", "methodBinding", "factBundle"))
                || !only(policy.path("scope"), Set.of("hgncId", "geneSymbol", "mondoId", "moi", "assembly", "protein", "variantType",
                        "transcripts", "supportedVariantEffects", "startCodonPolicy")))
            return "FUNCTIONAL_POLICY_OPERATOR_UNSUPPORTED";
        if (!"functional-evidence-policy-v1".equals(policy.path("schemaVersion").asText())
                || !text(policy.path("policyId")) || !text(policy.path("policyVersion"))
                || !"REVIEWED_IMPLEMENTED".equals(policy.at("/review/status").asText())
                || !text(policy.at("/review/reviewId"))
                || policy.path("criterionIds").size() != 1
                || !FunctionalEvidenceRuntime.PS3.equals(policy.at("/criterionIds/0").asText()))
            return "FUNCTIONAL_POLICY_IDENTITY_OR_REVIEW_INVALID";
        JsonNode scope = policy.path("scope");
        for (String key : Set.of("hgncId", "geneSymbol", "mondoId", "moi", "assembly", "protein"))
            if (!text(scope.path(key))) return "FUNCTIONAL_POLICY_SCOPE_INCOMPLETE";
        if (!"NATIVE_NONPROJECTED_SNV".equals(scope.path("variantType").asText())
                || !scope.path("supportedVariantEffects").isArray() || scope.path("supportedVariantEffects").size() != 1
                || !"MISSENSE".equals(scope.at("/supportedVariantEffects/0").asText())
                || !"EXCLUDE".equals(scope.path("startCodonPolicy").asText())
                || !scope.path("transcripts").isArray() || scope.path("transcripts").size() != 1
                || !text(scope.at("/transcripts/0/accession"))
                || !scope.at("/transcripts/0/version").isIntegralNumber()
                || scope.at("/transcripts/0/version").asInt() < 1)
            return "FUNCTIONAL_POLICY_SCOPE_UNSUPPORTED";
        if (!sourcesValid(policy.path("sources"))) return "FUNCTIONAL_POLICY_SOURCES_INVALID";
        if (!hash(policy.path("nativeModelSha256")) || !hash(policy.path("annotationResourceSha256"))
                || !text(policy.at("/registryBinding/registryId")) || !hash(policy.at("/registryBinding/registrySha256"))
                || !policy.at("/registryBinding/registryClasspathResource").asText().startsWith("/acmg/rule-registry-v2/")
                || !text(policy.at("/methodBinding/methodId")) || !hash(policy.at("/methodBinding/canonicalSha256"))
                || !text(policy.at("/factBundle/factBundleId")) || !text(policy.at("/factBundle/version"))
                || !hash(policy.at("/factBundle/canonicalSha256")))
            return "FUNCTIONAL_POLICY_INPUT_BINDING_MISSING";
        JsonNode assay = policy.path("assay");
        if (!text(assay.path("unit")) || !only(assay, Set.of("representation", "qualityGate", "sourceId", "sourceColumns", "threshold", "unit"))
                || !only(assay.path("threshold"), Set.of("operator", "decimal", "strength"))
                || !Set.of("LTE", "LT", "GTE", "GT").contains(assay.at("/threshold/operator").asText())
                || decimal(assay.at("/threshold/decimal")) == null
                || !Set.of("SUPPORTING", "MODERATE", "STRONG").contains(assay.at("/threshold/strength").asText()))
            return "FUNCTIONAL_POLICY_OPERATOR_UNSUPPORTED";
        if (source(policy, assay.path("sourceId").asText()) == null)
            return "FUNCTIONAL_POLICY_ASSAY_SOURCE_UNBOUND";
        switch (assay.path("representation").asText()) {
            case "MEASURED_SCALAR":
                if (!"EXCEL_BOOLEAN_TRUE".equals(assay.path("qualityGate").asText()))
                    return "FUNCTIONAL_POLICY_QUALITY_OPERATOR_UNSUPPORTED";
                JsonNode columns = assay.path("sourceColumns");
                Set<String> required = Set.of("proteinVariantKey", "proteinVariantThreeLetter", "mutationType", "wildTypeCodon", "experimentalMutantCodon",
                        "measuredScore", "highConfidence", "imputedScore");
                if (!only(columns, required) || columns.size() != required.size())
                    return "FUNCTIONAL_POLICY_SOURCE_COLUMNS_INVALID";
                Set<String> distinct = new HashSet<>();
                for (String key : required)
                    if (!columns.path(key).asText().matches("[A-Z]{1,3}") || !distinct.add(columns.path(key).asText()))
                        return "FUNCTIONAL_POLICY_SOURCE_COLUMNS_INVALID";
                break;
            case "REVIEWED_PUBLISHED_RANGE":
                if (!"REVIEWED_BOUND".equals(assay.path("qualityGate").asText()) || assay.has("sourceColumns"))
                    return "FUNCTIONAL_POLICY_QUALITY_OPERATOR_UNSUPPORTED";
                break;
            default: return "FUNCTIONAL_POLICY_REPRESENTATION_UNSUPPORTED";
        }
        return null;
    }

    @Nullable static String contextIssue(JsonNode policy, JsonNode context) {
        JsonNode scope = policy.path("scope");
        if (!scope.path("hgncId").equals(context.at("/gene/hgncId"))
                || !scope.path("geneSymbol").equals(context.at("/gene/symbol"))
                || !scope.path("mondoId").equals(context.at("/disease/mondoId"))
                || !scope.path("moi").equals(context.path("moi"))
                || !scope.path("assembly").equals(context.at("/variant/evidenceQueryAllele/assembly"))
                || !scope.at("/transcripts/0/accession").equals(context.at("/transcript/accession"))
                || !scope.at("/transcripts/0/version").equals(context.at("/transcript/version")))
            return "FUNCTIONAL_POLICY_CONTEXT_MISMATCH";
        if (!"NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText())
                || !"SNV".equals(context.at("/variant/variantType").asText()))
            return "FUNCTIONAL_NATIVE_SNV_REQUIRED";
        String selection = context.at("/transcript/selectionPolicy").asText();
        if ("PM2_CONTEXT_ONLY".equals(selection)
                || org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.populationOnlySelection(selection))
            return "FUNCTIONAL_TRANSCRIPT_CONFIRMATION_REQUIRED";
        return null;
    }

    @Nullable static String factsIssue(JsonNode policy, JsonNode facts, RuntimeEvidenceV2Adapter adapter) {
        if (!"reviewed-functional-facts-v1".equals(facts.path("schemaVersion").asText())
                || !policy.at("/factBundle/factBundleId").equals(facts.path("factBundleId"))
                || !policy.at("/factBundle/version").equals(facts.path("version"))
                || !policy.at("/factBundle/canonicalSha256").asText().equals(adapter.canonicalSha256(facts))
                || !"REVIEWED".equals(facts.at("/review/status").asText())
                || !text(facts.at("/review/reviewId")) || !instant(facts.at("/review/reviewedAt")))
            return "FUNCTIONAL_FACT_BUNDLE_IDENTITY_INVALID";
        if (!policy.path("scope").equals(facts.path("scope"))) return "FUNCTIONAL_FACT_SCOPE_MISMATCH";
        if (!policy.path("nativeModelSha256").asText().equals(adapter.canonicalSha256(facts.path("nativeModel")))
                || !policy.path("annotationResourceSha256").asText().equals(adapter.canonicalSha256(facts.path("annotationResource"))))
            return "FUNCTIONAL_MODEL_OR_ANNOTATION_IDENTITY_MISMATCH";
        JsonNode method = facts.path("method");
        if (!policy.at("/methodBinding/methodId").equals(method.path("methodId"))
                || !policy.at("/methodBinding/canonicalSha256").asText().equals(adapter.canonicalSha256(method)))
            return "FUNCTIONAL_METHOD_IDENTITY_MISMATCH";
        if (!only(method, Set.of("methodId", "assayId", "studyId", "conditionId", "readout", "unit", "mechanismId",
                "evidenceDomain", "constructTransfer", "constructDescription", "constructTransferRationale", "calibrationId",
                "calibrationStatus", "calibrationRationale", "maximumStrength", "biologicalReplicatesDescription", "combination",
                "spliceConsequenceHandling", "sourceIds", "reviewId", "sourceColumns", "evidenceOrigin"))) return "FUNCTIONAL_METHOD_OPERATOR_UNSUPPORTED";
        for (String key : Set.of("assayId", "studyId", "conditionId", "readout", "unit", "mechanismId", "calibrationId", "reviewId",
                "constructDescription", "constructTransferRationale", "calibrationRationale", "biologicalReplicatesDescription"))
            if (!text(method.path(key))) return "FUNCTIONAL_METHOD_REVIEW_INCOMPLETE";
        if (!"PROTEIN_FUNCTION".equals(method.path("evidenceDomain").asText())
                || !"PUBLISHED_EXPERIMENTAL_MEASUREMENT".equals(method.path("evidenceOrigin").asText())
                || !method.path("unit").equals(policy.at("/assay/unit"))
                || !"REVIEWED_SAME_PROTEIN_CHANGE".equals(method.path("constructTransfer").asText())
                || !"VCEP_NAMED_ASSAY".equals(method.path("calibrationStatus").asText())
                || !"SINGLE_ASSAY_NO_STRENGTH_ADDITION".equals(method.path("combination").asText())
                || !"ABSTAIN".equals(method.path("spliceConsequenceHandling").asText())
                || !method.path("maximumStrength").equals(policy.at("/assay/threshold/strength")))
            return "FUNCTIONAL_METHOD_OPERATOR_UNSUPPORTED";
        if ("MEASURED_SCALAR".equals(policy.at("/assay/representation").asText())
                && !method.path("sourceColumns").equals(policy.at("/assay/sourceColumns")))
            return "FUNCTIONAL_MEASUREMENT_COLUMN_BINDING_MISMATCH";
        if (!boundSources(policy, method.path("sourceIds"))) return "FUNCTIONAL_METHOD_SOURCES_UNBOUND";
        if (!CodingTranscriptMapping.modelSourcesBound(facts.path("nativeModel"), policy.path("sources")))
            return "FUNCTIONAL_MODEL_SOURCES_UNBOUND";
        if (!facts.path("records").isArray() || facts.path("records").isEmpty()) return "FUNCTIONAL_FACT_RECORDS_MISSING";
        Set<String> ids = new HashSet<>();
        Set<String> proteins = new HashSet<>();
        Set<String> origins = new HashSet<>();
        for (JsonNode record : facts.path("records")) {
            if (!text(record.path("recordId")) || !ids.add(record.path("recordId").asText())
                    || !record.path("proteinVariantKey").asText().matches("[ACDEFGHIKLMNPQRSTVWY][1-9][0-9]*[ACDEFGHIKLMNPQRSTVWY]")
                    || !proteins.add(record.path("proteinVariantKey").asText())
                    || !record.path("sourceId").equals(policy.at("/assay/sourceId"))
                    || !text(record.path("sheet")) || !record.path("row").isIntegralNumber() || record.path("row").asInt() < 1
                    || !origins.add(record.path("sourceId").asText() + ":" + record.path("sheet").asText() + ":" + record.path("row").asInt()))
                return "FUNCTIONAL_FACT_RECORD_IDENTITY_INVALID";
        }
        return null;
    }

    static boolean boundSources(JsonNode policy, JsonNode ids) {
        if (!ids.isArray() || ids.isEmpty()) return false;
        for (JsonNode id : ids) if (!id.isTextual() || source(policy, id.asText()) == null) return false;
        return true;
    }

    @Nullable static JsonNode source(JsonNode policy, String id) {
        for (JsonNode source : policy.path("sources")) if (id.equals(source.path("sourceId").asText())) return source;
        return null;
    }

    static boolean sourcesValid(JsonNode sources) {
        if (!sources.isArray() || sources.isEmpty()) return false;
        Set<String> ids = new HashSet<>();
        for (JsonNode source : sources)
            if (!text(source.path("sourceId")) || !ids.add(source.path("sourceId").asText())
                    || !text(source.path("version")) || !source.path("url").asText().startsWith("https://") || !hash(source.path("sha256"))) return false;
        return true;
    }

    static boolean only(JsonNode node, Set<String> fields) {
        if (!node.isObject()) return false;
        var names = node.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) return false;
        return true;
    }

    @Nullable static BigDecimal decimal(JsonNode value) {
        if (!value.isTextual() || !value.asText().matches("[-+]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][-+]?[0-9]+)?")) return null;
        try { return new BigDecimal(value.asText()); }
        catch (NumberFormatException invalid) { return null; }
    }

    static boolean text(JsonNode value) { return value.isTextual() && !value.asText().isBlank(); }
    static boolean hash(JsonNode value) { return value.isTextual() && value.asText().matches("[0-9a-f]{64}"); }
    private static boolean instant(JsonNode value) {
        try { Instant.parse(value.asText()); return true; }
        catch (java.time.format.DateTimeParseException invalid) { return false; }
    }
}
