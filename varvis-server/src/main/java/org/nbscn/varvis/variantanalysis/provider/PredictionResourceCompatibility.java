package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Compatibility is separate from raw acquisition, sequence association and disease application. */
public final class PredictionResourceCompatibility {
    private PredictionResourceCompatibility() { }

    /** Explicit, digest-bound offline evidence, never a client-supplied trusted boolean. */
    public static final class StandardBinding {
        static final String EXACT_SCHEMA = "prediction-standard-resource-compatibility-v2";
        static final String EXACT_ID = "am-exact-single-row-local-adoption-20260918";
        static final String SUPPLEMENT_SHA = "14143482fe4611f45837b7917cd9e5e07a8ba18229d28377d11ba9d20d4afde6";
        private final ObjectNode evidence;
        private final String digest;
        private StandardBinding(ObjectNode evidence, String digest) { this.evidence = evidence.deepCopy(); this.digest = digest; }
        public static StandardBinding load(ObjectMapper mapper, Path path, String expectedSha) throws IOException {
            if (Files.size(path) > 65536) throw new IllegalArgumentException("COMPATIBILITY_ARTIFACT_TOO_LARGE");
            byte[] bytes = Files.readAllBytes(path);
            if (!PredictionCalibration.sha256(bytes).equals(expectedSha)) throw new IllegalArgumentException("COMPATIBILITY_ARTIFACT_DIGEST_MISMATCH");
            JsonNode node = mapper.readTree(bytes);
            if (!(node instanceof ObjectNode object) || !"prediction-standard-resource-compatibility-v1".equals(node.path("schemaVersion").asText()))
                throw new IllegalArgumentException("COMPATIBILITY_ARTIFACT_INVALID");
            return new StandardBinding(object, expectedSha);
        }
        public ObjectNode evidence() { return evidence.deepCopy(); }
        public String sha256() { return digest; }

        public static StandardBinding loadExact(ObjectMapper mapper, Path path, String expectedSha, Path supplement) throws IOException {
            if (Files.size(path) > 65536 || Files.size(supplement) != 37027) throw new IllegalArgumentException("EXACT_ADOPTION_SOURCE_SIZE_INVALID");
            byte[] bytes = Files.readAllBytes(path);
            if (!PredictionCalibration.sha256(bytes).equals(expectedSha)) throw new IllegalArgumentException("COMPATIBILITY_ARTIFACT_DIGEST_MISMATCH");
            JsonNode node = mapper.readTree(bytes);
            if (!(node instanceof ObjectNode object) || !EXACT_SCHEMA.equals(node.path("schemaVersion").asText()))
                throw new IllegalArgumentException("EXACT_ADOPTION_SUCCESSOR_REQUIRED");
            if (!SUPPLEMENT_SHA.equals(PredictionCalibration.sha256(Files.readAllBytes(supplement)))
                    || !SUPPLEMENT_SHA.equals(node.at("/mappingSource/sha256").asText())
                    || node.at("/mappingSource/page").asInt() != 2
                    || !"https://www.ccs.neu.edu/home/radivojac/papers/bergquist_genetmed_2025_supp.pdf".equals(node.at("/mappingSource/url").asText()))
                throw new IllegalArgumentException("EXACT_ADOPTION_SOURCE_DIGEST_MISMATCH");
            return new StandardBinding(object, expectedSha);
        }

        String exactIssue(ObjectMapper mapper, JsonNode calibration, PredictionLocalResourceBinding local) {
            if (!EXACT_SCHEMA.equals(evidence.path("schemaVersion").asText())
                    || !EXACT_ID.equals(evidence.at("/localAdoption/id").asText())
                    || !"1.0.0".equals(evidence.at("/localAdoption/version").asText())
                    || !"VERIFIED".equals(evidence.at("/localAdoption/status").asText())
                    || !"EXACT_SINGLE_ROW".equals(evidence.at("/localAdoption/scope").asText())
                    || !"PARTIAL".equals(evidence.path("overallStatus").asText())
                    || !evidence.path("authorFullMappingAndAggregationReproduced").isBoolean()
                    || evidence.path("authorFullMappingAndAggregationReproduced").asBoolean()) return "EXACT_ADOPTION_SCOPE_OR_VERSION_INVALID";
            String issue = resourceIssue(mapper, calibration, local, local.identity());
            if (issue != null) return issue;
            if (!"NONCOMMERCIAL_LOCAL_RESEARCH_PUBLIC_CONTROLS_ONLY".equals(local.savedPurpose())) return "EXACT_ADOPTION_USE_CONFLICT";
            for (String fact : List.of("modelAndScore", "productAndDerivation", "purpose")) {
                JsonNode check = evidence.path("checks").path(fact);
                String expected = "purpose".equals(fact) ? local.identity().at("/license/textSha256").asText()
                        : local.identity().path("localBindingSha256").asText();
                if (!"PASS".equals(check.path("status").asText()) || !expected.matches("[0-9a-f]{64}")
                        || !expected.equals(check.path("sha256").asText()))
                    return "EXACT_ADOPTION_RESOURCE_PROOF_MISSING";
            }
            return null;
        }

        String issue(ObjectMapper mapper, JsonNode calibration, PredictionLocalResourceBinding local, JsonNode actual, JsonNode input) {
            // A scoped successor cannot authorize the old independent resource/input/rawScore API.
            if (EXACT_SCHEMA.equals(evidence.path("schemaVersion").asText())) return "TRUSTED_SAVED_CALIBRATION_ENTRY_REQUIRED";
            String resourceIssue = resourceIssue(mapper, calibration, local, actual);
            if (resourceIssue != null) return resourceIssue;
            return legacyInputIssue(input);
        }

        private String resourceIssue(ObjectMapper mapper, JsonNode calibration, PredictionLocalResourceBinding local, JsonNode actual) {
            if (!calibration.path("calibrationId").equals(evidence.path("calibrationId"))
                    || !calibration.path("version").equals(evidence.path("calibrationVersion"))
                    || !new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(calibration).equals(evidence.path("calibrationCanonicalSha256").asText()))
                return "COMPATIBILITY_CALIBRATION_BINDING_MISMATCH";
            String use = acquisitionIssue(actual); if (use != null) return use;
            if (!"ALPHAMISSENSE".equals(actual.path("model").asText())) return "COMPATIBILITY_MODEL_MISMATCH";
            if (!"am_pathogenicity".equals(actual.path("scoreField").asText())) return "COMPATIBILITY_SCORE_FIELD_MISMATCH";
            if (!"ORIGINAL_DECIMAL_STRING".equals(actual.path("scorePrecision").asText())) return "COMPATIBILITY_SCORE_PRECISION_MISMATCH";
            if (!"https://storage.googleapis.com/dm_alphamissense/AlphaMissense_hg38.tsv.gz".equals(actual.path("sourceLocator").asText()))
                return "COMPATIBILITY_MAJOR_TRANSCRIPT_PRODUCT_MISMATCH";
            if (!"2023-08-03".equals(actual.path("dataVersion").asText())) return "COMPATIBILITY_RELEASE_MISMATCH";
            if (!local.identity().equals(actual) || !actual.equals(evidence.path("runtimeResourceIdentity")))
                return "COMPATIBILITY_LOCAL_MANIFEST_MISMATCH";
            return null;
        }
        private String legacyInputIssue(JsonNode input) {
            JsonNode allele = input.path("queryAllele");
            if (!"NATIVE".equals(input.path("assemblyLineage").asText()) || !"GRCh38".equals(allele.path("genomeAssembly").asText())
                    || !"missense_variant".equals(input.path("consequence").asText())
                    || !allele.path("reference").asText().matches("[ACGT]") || !allele.path("alternate").asText().matches("[ACGT]")
                    || allele.path("reference").equals(allele.path("alternate"))
                    || !"EXACT_VERSIONED_TRANSCRIPT".equals(input.path("route").asText())
                    || !input.path("transcriptId").asText().matches("ENST[0-9]+\\.[0-9]+")
                    || !input.path("proteinPosition").asText().matches("[1-9][0-9]*")
                    || !input.path("aminoAcids").asText().matches("[ACDEFGHIKLMNPQRSTVWY]/[ACDEFGHIKLMNPQRSTVWY]"))
                return "COMPATIBILITY_INPUT_OUTSIDE_SCOPE";
            for (String fact : List.of("modelAndScore", "productAndDerivation", "studyMappingAndAggregation", "purpose")) {
                JsonNode check = evidence.path("checks").path(fact);
                if (!"PASS".equals(check.path("status").asText()))
                    return "studyMappingAndAggregation".equals(fact) ? "AM_STUDY_MAPPING_AND_AGGREGATION_UNVERIFIED" : "COMPATIBILITY_FACT_UNVERIFIED_" + fact;
                if (check.path("sourceLocator").asText().isBlank() || !check.path("sha256").asText().matches("[0-9a-f]{64}"))
                    return "COMPATIBILITY_PROOF_MISSING_" + fact;
            }
            return null;
        }
    }
    public static String acquisitionIssue(JsonNode resource) {
        if (resource.at("/license/sourceLocator").asText().isBlank()) return "LICENSE_SOURCE_UNBOUND";
        if (resource.at("/license/purpose").asText().isBlank()) return "USE_PURPOSE_UNDECLARED";
        if (!"DECLARED_RESEARCH".equals(resource.at("/license/purpose").asText())) return "USE_RESTRICTION_CONFLICT";
        if (!"PERMITTED".equals(resource.at("/license/status").asText())) return "PREDICTION_RESOURCE_USE_UNVERIFIED";
        for (String field : List.of("model", "dataVersion", "sourceLocator", "sha256"))
            if (resource.path(field).asText().isBlank()) return "PREDICTION_RESOURCE_IDENTITY_MISSING";
        if (!resource.path("sha256").asText().matches("[0-9a-f]{64}")) return "PREDICTION_RESOURCE_IDENTITY_MISSING";
        return null;
    }
    public static String issue(JsonNode required, JsonNode actual) {
        String acquisition = acquisitionIssue(actual); if (acquisition != null) return acquisition;
        if (!"VERIFIED".equals(actual.path("calibrationCompatibility").asText())) return "CALIBRATION_RESOURCE_COMPATIBILITY_UNVERIFIED";
        if (!actual.at("/compatibilityEvidence/sha256").asText().matches("[0-9a-f]{64}")
                || actual.at("/compatibilityEvidence/sourceLocator").asText().isBlank()
                || actual.at("/compatibilityEvidence/calibrationId").asText().isBlank()) return "CALIBRATION_RESOURCE_COMPATIBILITY_UNVERIFIED";
        for (String field : List.of("model", "dataVersion", "sha256"))
            if (!required.path(field).isTextual() || !required.path(field).equals(actual.path(field))) return "CALIBRATION_RESOURCE_IDENTITY_MISMATCH";
        if ("SPLICEAI".equals(actual.path("model").asText())) {
            if (!"RAW".equals(actual.path("scoreMode").asText()) || actual.path("distance").asInt() != 4999 || actual.path("mask").asInt(-1) != 0)
                return "SPLICE_PARAMETERS_INCOMPATIBLE";
            for (String field : List.of("source", "version", "assembly", "transcriptSetSha256", "referenceSha256", "annotationSha256", "calibrationEvidenceSha256"))
                if (required.path("annotation").path(field).asText().isBlank()
                        || !required.path("annotation").path(field).equals(actual.path("annotation").path(field))) return "SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED";
            if (!"VERIFIED".equals(actual.at("/annotation/status").asText())) return "SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED";
            if (!"GRCh38".equals(actual.at("/annotation/assembly").asText())) return "SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED";
            for (String field : List.of("transcriptSetSha256", "referenceSha256", "annotationSha256", "calibrationEvidenceSha256"))
                if (!actual.path("annotation").path(field).asText().matches("[0-9a-f]{64}")) return "SPLICE_ANNOTATION_COMPATIBILITY_UNVERIFIED";
        }
        return null;
    }
}
