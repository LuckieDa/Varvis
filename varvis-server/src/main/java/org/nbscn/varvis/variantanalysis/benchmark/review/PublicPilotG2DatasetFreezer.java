package org.nbscn.varvis.variantanalysis.benchmark.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Freezes reviewed G2 inputs for an already approved public pilot dataset. */
public class PublicPilotG2DatasetFreezer {
    public static final String SCHEMA_VERSION = "pilot-reviewed-g2-input-manifest-v1";
    public static final String VALIDATION_SCHEMA_VERSION = "pilot-reviewed-g2-validation-v1";
    public static final String BUILDER_VERSION = "public-pilot-g2-freezer-v1";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final HpoReviewDecisionValidator validator = new HpoReviewDecisionValidator();
    private final HpoReviewG2InputBuilder builder = new HpoReviewG2InputBuilder();

    public Map<String, Object> freeze(Path datasetRoot, String sourceCommit) throws IOException {
        require(sourceCommit != null && sourceCommit.matches("[0-9a-f]{40}"), "G2_SOURCE_COMMIT_INVALID");
        JsonNode approved = read(datasetRoot.resolve("approved-dataset-manifest.json"));
        JsonNode approval = read(datasetRoot.resolve("dataset-approval-validation.json"));
        JsonNode mapping = read(datasetRoot.resolve(
                "truth-masked-hpo-review-v1/internal/DO_NOT_PROVIDE_TO_REVIEWER.mapping.json"));
        Path reviewRoot = datasetRoot.resolve("truth-masked-hpo-review-v1");
        require("APPROVED_FOR_G2_BUILD".equals(approved.path("status").asText()), "G2_DATASET_NOT_APPROVED");
        require("PASSED".equals(approval.path("status").asText()), "G2_DATASET_APPROVAL_INVALID");
        require(approved.path("approval").path("datasetFingerprint").asText()
                .equals(approval.path("datasetFingerprint").asText()), "G2_DATASET_FINGERPRINT_MISMATCH");
        require("DO_NOT_PROVIDE_TO_REVIEWER".equals(mapping.path("handling").asText()),
                "G2_REVIEW_MAPPING_HANDLING_INVALID");
        require(approved.path("cases").size() == 30 && mapping.path("mappings").size() == 30,
                "G2_CASE_COUNT_INVALID");

        Map<String, JsonNode> cases = byText(approved.path("cases"), "caseId", "G2_DUPLICATE_CASE_ID");
        Map<String, Integer> orderByCase = new HashMap<String, Integer>();
        for (int index = 0; index < approved.path("cases").size(); index++)
            orderByCase.put(approved.path("cases").get(index).path("caseId").asText(), index + 1);
        List<JsonNode> mappings = new ArrayList<JsonNode>();
        mapping.path("mappings").forEach(mappings::add);
        Collections.sort(mappings, Comparator.comparing(value -> value.path("reviewId").asText()));
        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
        int observedCount = 0;
        int excludedCount = 0;
        for (JsonNode item : mappings) {
            String reviewId = item.path("reviewId").asText();
            String caseId = item.path("caseId").asText();
            JsonNode datasetCase = cases.get(caseId);
            require(datasetCase != null, "G2_CASE_MAPPING_INVALID");
            HpoReviewPacket packet = mapper.readValue(reviewRoot.resolve(
                    "reviewer-visible/" + reviewId + ".packet.json").toFile(), HpoReviewPacket.class);
            Path decisionPath = reviewRoot.resolve("final-decisions/" + reviewId + ".decision.json");
            HpoReviewDecisionDocument decision = mapper.readValue(decisionPath.toFile(),
                    HpoReviewDecisionDocument.class);
            HpoReviewValidationResult validated = validator.validate(packet, decision);
            require(validated.getStatus() == HpoReviewReadinessStatus.READY_IDENTICAL_TO_G1,
                    "G2_DECISION_NOT_READY_IDENTICAL:" + reviewId);
            require(item.path("packetFingerprint").asText().equals(packet.getPacketFingerprint()),
                    "G2_PACKET_FINGERPRINT_MISMATCH:" + reviewId);
            ReviewedHpoInput input = builder.build(packet, decision);
            List<String> sourceObserved = terms(packet, HpoReviewOriginalStatus.OBSERVED);
            List<String> sourceExcluded = terms(packet, HpoReviewOriginalStatus.EXCLUDED);
            require(sourceObserved.equals(input.getObservedTerms()), "G2_G1_OBSERVED_MISMATCH:" + reviewId);
            require(sourceExcluded.equals(input.getExcludedTerms()), "G2_EXCLUDED_PROVENANCE_MISMATCH:" + reviewId);
            observedCount += input.getObservedTerms().size();
            excludedCount += input.getExcludedTerms().size();

            Map<String, Object> entry = new LinkedHashMap<String, Object>();
            entry.put("selectionOrder", selectionOrder(datasetCase, orderByCase.get(caseId)));
            entry.put("role", role(datasetCase));
            entry.put("caseId", caseId);
            entry.put("groupId", "G2");
            entry.put("datasetId", approved.path("datasetId").asText());
            entry.put("datasetVersion", approved.path("datasetVersion").asText());
            entry.put("truthCanonicalIdentities", truthIdentities(datasetCase));
            entry.put("reviewId", reviewId);
            entry.put("reviewProtocolVersion", packet.getReviewProtocolVersion());
            entry.put("packetFingerprint", packet.getPacketFingerprint());
            entry.put("packetContentChecksum", packet.getPacketContentChecksum());
            entry.put("decisionSha256", sha256(decisionPath));
            entry.put("decisionCount", decision.getTermDecisions().size());
            entry.put("readiness", validated.getStatus().name());
            entry.put("observedHpoTerms", input.getObservedTerms());
            entry.put("excludedHpoTerms", input.getExcludedTerms());
            entry.put("hpoInputChecksum", termChecksum(input.getObservedTerms()));
            entry.put("g1HpoInputChecksum", termChecksum(sourceObserved));
            entry.put("g1G2Equivalence", "OBSERVED_HPO_IDENTICAL_TO_G1");
            entry.put("excludedTermsArePositiveInput", false);
            entry.put("reviewProvenance", input.getProvenance());
            entry.put("aiAssistance", decision.getAiAssistance());
            entry.put("nonHpoConfigurationFingerprint",
                    approved.path("approval").path("datasetFingerprint").asText());
            entries.add(entry);
        }
        Collections.sort(entries, Comparator.comparingInt(value -> ((Number) value.get("selectionOrder")).intValue()));
        for (int index = 0; index < entries.size(); index++)
            require(((Number) entries.get(index).get("selectionOrder")).intValue() == index + 1,
                    "G2_SELECTION_ORDER_INVALID");

        Map<String, Object> manifest = new LinkedHashMap<String, Object>();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("status", "READY_IDENTICAL_TO_G1");
        manifest.put("datasetId", approved.path("datasetId").asText());
        manifest.put("datasetVersion", approved.path("datasetVersion").asText());
        manifest.put("approvedDatasetFingerprint",
                approved.path("approval").path("datasetFingerprint").asText());
        manifest.put("approvedDatasetManifestSha256", sha256(datasetRoot.resolve("approved-dataset-manifest.json")));
        manifest.put("builderVersion", BUILDER_VERSION);
        manifest.put("sourceCommit", sourceCommit);
        manifest.put("hpoOntologyVersion", approved.path("hpoOntologyVersion").asText());
        manifest.put("caseCount", entries.size());
        manifest.put("observedTermCount", observedCount);
        manifest.put("excludedTermCount", excludedCount);
        manifest.put("g1G2Equivalence", "ALL_OBSERVED_HPO_INPUTS_IDENTICAL");
        manifest.put("candidateHardCap", 20000);
        manifest.put("vepCap", 8000);
        manifest.put("pilotExecutionStatus", "NOT_STARTED");
        manifest.put("cases", entries);
        manifest.put("manifestFingerprint", fingerprint(manifest));

        Map<String, Object> validation = new LinkedHashMap<String, Object>();
        validation.put("schemaVersion", VALIDATION_SCHEMA_VERSION);
        validation.put("status", "PASSED");
        validation.put("datasetId", approved.path("datasetId").asText());
        validation.put("approvedDatasetFingerprint", manifest.get("approvedDatasetFingerprint"));
        validation.put("g2ManifestFingerprint", manifest.get("manifestFingerprint"));
        validation.put("caseCount", 30);
        validation.put("decisionCount", observedCount + excludedCount);
        validation.put("observedTermCount", observedCount);
        validation.put("excludedTermCount", excludedCount);
        validation.put("readyIdenticalToG1Count", 30);
        validation.put("additions", 0);
        validation.put("removedTerms", 0);
        validation.put("excludedTermsArePositiveInput", false);
        validation.put("telemetryContract", "benchmark-analysis-telemetry-v1");
        validation.put("telemetryInvariantsValidatedBy", "BenchmarkTelemetryCollectorTest");
        validation.put("staleArtifactAndIdentityRejectionValidatedBy",
                "PublicPilotG2DatasetFreezerTest,Test-BenchmarkCaseResult.Synthetic.ps1");
        validation.put("pilotExecutionStatus", "NOT_STARTED");

        Path output = reviewRoot.resolve("approved-g2");
        write(output.resolve("pilot-g2-input-manifest.json"), manifest);
        write(output.resolve("pilot-g2-validation.json"), validation);
        return validation;
    }

    private JsonNode read(Path path) throws IOException {
        require(Files.isRegularFile(path), "G2_INPUT_MISSING:" + path.getFileName());
        return mapper.readTree(path.toFile());
    }

    private Map<String, JsonNode> byText(JsonNode values, String field, String error) throws IOException {
        Map<String, JsonNode> result = new HashMap<String, JsonNode>();
        for (JsonNode value : values) require(result.put(value.path(field).asText(), value) == null, error);
        return result;
    }

    private int selectionOrder(JsonNode datasetCase, Integer order) throws IOException {
        String status = datasetCase.path("inclusionStatus").asText();
        require("APPROVED_MAIN".equals(status) || "APPROVED_BACKUP".equals(status),
                "G2_CASE_NOT_APPROVED");
        require(order != null && order > 0, "G2_CASE_ORDER_MISSING");
        return Objects.requireNonNull(order);
    }

    private String role(JsonNode datasetCase) {
        return "APPROVED_MAIN".equals(datasetCase.path("inclusionStatus").asText()) ? "MAIN" : "BACKUP";
    }

    private List<String> truthIdentities(JsonNode datasetCase) throws IOException {
        List<String> result = new ArrayList<String>();
        for (JsonNode truth : datasetCase.path("truthVariants")) {
            JsonNode key = truth.path("canonicalIdentity");
            require("GRCh38".equals(key.path("genomeAssembly").asText()), "G2_TRUTH_ASSEMBLY_INVALID");
            result.add(String.join("|", key.path("genomeAssembly").asText(), key.path("chromosome").asText(),
                    key.path("position").asText(), key.path("reference").asText(), key.path("alternate").asText()));
        }
        require(result.size() == 1, "G2_TRUTH_COUNT_INVALID");
        return result;
    }

    private List<String> terms(HpoReviewPacket packet, HpoReviewOriginalStatus status) {
        List<String> result = new ArrayList<String>();
        for (HpoReviewTerm term : packet.getTerms()) if (term.getOriginalStatus() == status) result.add(term.getHpoId());
        Collections.sort(result);
        return result;
    }

    private String termChecksum(List<String> terms) throws IOException {
        return digest(String.join("\n", terms).getBytes(StandardCharsets.UTF_8));
    }

    private String fingerprint(Object value) throws IOException {
        return digest(mapper.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(value));
    }

    private String sha256(Path path) throws IOException { return digest(Files.readAllBytes(path)); }

    private String digest(byte[] bytes) throws IOException {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte item : value) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private void write(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        byte[] expected = (mapper.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
        if (Files.exists(path)) {
            require(java.util.Arrays.equals(expected, Files.readAllBytes(path)),
                    "G2_OUTPUT_CONFLICT:" + path.getFileName());
            return;
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".temporary");
        Files.write(temporary, expected);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }
}
