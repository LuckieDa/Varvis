package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Explicit one-shot offline receipt; default tests cannot invoke the real saved materials. */
class SavedPredictionCalibrationOfflineTest {
    static final String OUTPUT = "offline-sidecar.json";
    static final String MARKER = "real-attempt-started.txt";
    private static final List<String> CANDIDATES = List.of("exomiser-v1-00000002", "exomiser-v1-00000001");

    @Test @EnabledIfSystemProperty(named = "varvis.pp3.revalidationRoot", matches = ".+")
    void comparePinnedSavedObservationsOnceWithoutAnyAcquisition() throws Exception {
        Path root = Path.of(System.getProperty("varvis.pp3.revalidationRoot"));
        executeOnce(root, CANDIDATES, new SavedWork(root), SavedPredictionCalibrationOfflineTest::writeReceipt);
    }

    /** Local test seam only; neither the production gate nor its API is changed. */
    interface Work<T> {
        void load(ObjectNode receipt) throws Exception;
        T prepare(String candidate) throws Exception;
        ObjectNode compare(T input) throws Exception;
        void verifyPreservation(ObjectNode receipt) throws Exception;
    }
    interface ReceiptWriter { void write(Path path, ObjectNode receipt) throws IOException; }

    static void writeReceipt(Path path, ObjectNode receipt) throws IOException {
        Files.write(path, SavedPredictionCalibrationTest.M.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt), StandardOpenOption.CREATE_NEW);
    }

    static <T> ObjectNode executeOnce(Path root, List<String> candidates, Work<T> work, ReceiptWriter writer) throws Exception {
        var receipt = SavedPredictionCalibrationTest.M.createObjectNode().put("operation", "OFFLINE_CALIBRATION_VALIDATION")
                .put("receiptMode", "NATIVE_EXECUTOR_RECEIPT").put("startedAt", Instant.now().toString()).put("batchStatus", "RUNNING").put("registeredObservations", candidates.size())
                .put("sourceVerificationAttempts", 0).put("comparatorCalls", 0).put("realObservationsCalibrated", 0).put("acceptedCalibrations", 0)
                .put("newAcquisition", 0).put("newTransport", 0).put("newBusinessRuns", 0).put("criteriaModified", false)
                .put("fullSourcePointBandingVerified", false).put("authorFullMappingAndAggregationReproduced", false);
        var results = receipt.putArray("results");
        for (String id : candidates) results.addObject().put("candidateId", id).put("status", "NOT_ATTEMPTED")
                .put("sourceVerificationAttempts", 0).put("comparatorCalls", 0).put("calibrationValidated", false).put("accepted", false);
        // Reserve before input configuration or reader load. Never touch an earlier attempt/output.
        try {
            if (Files.exists(root.resolve(MARKER)) || Files.exists(root.resolve(OUTPUT))) throw new FileAlreadyExistsException("OFFLINE_BATCH_ALREADY_STARTED");
            writeReceipt(root.resolve(MARKER), receipt);
        } catch (Exception | AssertionError initialization) {
            System.err.println(diagnostic("INITIALIZATION", initialization));
            throw initialization;
        }
        String stage = "INPUT_LOAD";
        ObjectNode active = null;
        Throwable failure = null;
        boolean loaded = false;
        try {
            work.load(receipt); loaded = true;
            for (JsonNode node : results) {
                active = (ObjectNode) node;
                stage = "SOURCE_PREPARE"; active.put("stage", stage).put("status", "RUNNING");
                increment(receipt, "sourceVerificationAttempts"); increment(active, "sourceVerificationAttempts");
                T input = work.prepare(active.path("candidateId").asText());
                stage = "COMPARISON"; active.put("stage", stage);
                increment(receipt, "comparatorCalls"); increment(active, "comparatorCalls");
                ObjectNode result = work.compare(input);
                active.set("calibration", result); // retain returned content even when the following assertions fail
                stage = "RESULT_VALIDATION"; active.put("stage", stage);
                verifyResult(result);
                increment(receipt, "realObservationsCalibrated"); active.put("calibrationValidated", true).put("status", "CALIBRATED");
                active = null;
            }
        } catch (Exception | AssertionError rejected) {
            failure = rejected;
            receipt.set("failure", diagnostic(stage, rejected));
            if (active != null) {
                active.put("status", "FAILED"); active.set("failure", diagnostic(stage, rejected));
                ((ObjectNode) receipt.path("failure")).set("candidateId", active.path("candidateId"));
            }
        }
        // Preservation is attempted after a candidate failure too; retain the original cause.
        if (loaded) {
            try {
                work.verifyPreservation(receipt); receipt.put("inputPreservationStatus", "VERIFIED");
            } catch (Exception | AssertionError preservation) {
                receipt.put("inputPreservationStatus", "FAILED"); receipt.set("preservationFailure", diagnostic("INPUT_PRESERVATION", preservation));
                if (failure == null) { failure = preservation; receipt.set("failure", diagnostic("INPUT_PRESERVATION", preservation)); }
                else failure.addSuppressed(preservation);
            }
        } else receipt.put("inputPreservationStatus", "NOT_RUN_INPUT_LOAD_FAILED");
        for (JsonNode node : results) {
            var item = (ObjectNode) node;
            if ("NOT_ATTEMPTED".equals(item.path("status").asText())) item.put("reasonCode", "BATCH_STOPPED_AFTER_FAILURE");
            item.put("accepted", failure == null && item.path("calibrationValidated").asBoolean());
        }
        receipt.put("batchStatus", failure == null ? "SUCCEEDED" : "FAILED").put("endedAt", Instant.now().toString())
                .put("acceptedCalibrations", failure == null ? receipt.path("realObservationsCalibrated").asInt() : 0);
        try {
            writer.write(root.resolve(OUTPUT), receipt);
        } catch (Exception | AssertionError writeFailure) {
            receipt.put("batchStatus", "FAILED").put("acceptedCalibrations", 0);
            for (JsonNode node : results) ((ObjectNode) node).put("accepted", false);
            receipt.set("receiptWriteFailure", diagnostic("RECEIPT_WRITE", writeFailure));
            System.err.println(receipt.path("receiptWriteFailure"));
            if (failure == null) failure = writeFailure; else failure.addSuppressed(writeFailure);
        }
        if (failure instanceof Exception exception) throw exception;
        if (failure instanceof AssertionError assertion) throw assertion;
        return receipt;
    }

    private static void increment(ObjectNode n, String name) { n.put(name, n.path(name).asInt() + 1); }
    private static ObjectNode diagnostic(String stage, Throwable error) {
        var out = SavedPredictionCalibrationTest.M.createObjectNode().put("stage", stage)
                .put("reasonCode", "OFFLINE_" + stage + "_FAILED").put("exceptionType", error.getClass().getSimpleName());
        String message = error.getMessage();
        if (message != null && message.matches("[A-Z][A-Z0-9_]{1,120}")) out.put("sourceReasonCode", message);
        return out; // no raw exception message, input contents or machine paths in diagnostics
    }
    static void verifyResult(JsonNode result) {
        assertNotNull(result); assertEquals("CALIBRATED", result.path("status").asText());
        assertEquals("NOT_EVALUATED", result.path("applicationStatus").asText()); assertEquals("NONE", result.path("appliedStrength").asText());
        assertTrue(result.path("sourcePointValue").isNull()); assertEquals(2, result.path("criteria").size());
        for (String code : List.of("PP3", "BP4")) {
            assertEquals("NOT_EVALUATED", result.path("criteria").path(code).path("status").asText());
            assertEquals("NONE", result.path("criteria").path(code).path("appliedStrength").asText());
        }
    }

    private static final class SavedWork implements Work<SavedPredictionCalibration.VerifiedInput> {
        private final Path root;
        private SavedPredictionCalibration reader;
        private JsonNode preservation;
        SavedWork(Path root) { this.root = root; }
        @Override public void load(ObjectNode receipt) throws Exception {
            var mapper = SavedPredictionCalibrationTest.M;
            receipt.put("inputLoadStage", "CONFIGURATION");
            JsonNode locations = mapper.readTree(Files.readAllBytes(root.resolve("inputs.json")));
            if (locations == null) throw new IllegalArgumentException("OFFLINE_INPUT_CONFIG_MISSING");
            receipt.set("inputReferences", locations.deepCopy());
            preservation = locations.path("preservation");
            receipt.put("inputLoadStage", "PRESERVATION_BASELINE");
            assertFalse(preservation.isEmpty(), "OFFLINE_PRESERVATION_LIST_MISSING");
            var before = receipt.putObject("preservationBefore");
            for (var entry : preservation.properties()) {
                String sha = PredictionCalibration.sha256(Files.readAllBytes(Path.of(entry.getValue().path("path").asText())));
                before.put(entry.getKey(), sha); assertEquals(entry.getValue().path("sha256").asText(), sha, entry.getKey());
            }
            receipt.set("executionMetadata", locations.path("executionMetadata").deepCopy());
            receipt.put("inputLoadStage", "CODE_IDENTITY");
            assertTrue(locations.at("/executionMetadata/baseRevision").asText().matches("[a-f0-9]{40}"));
            assertFalse(locations.at("/executionMetadata/sourceSha256").isEmpty());
            for (var entry : locations.at("/executionMetadata/sourceSha256").properties()) assertEquals(entry.getValue().asText(),
                    PredictionCalibration.sha256(Files.readAllBytes(SavedPredictionCalibrationTest.repo().resolve(entry.getKey()))), entry.getKey());
            Map<String, Path> paths = new LinkedHashMap<>();
            locations.path("artifacts").properties().forEach(e -> paths.put(e.getKey(), Path.of(e.getValue().asText())));
            receipt.put("inputLoadStage", "ADOPTION_LOAD");
            assertEquals("8c042adaae73930b6bf547eb994731635a3567721e0b4818c1ca4bc54139c781", locations.path("adoptionSha256").asText());
            var binding = PredictionResourceCompatibility.StandardBinding.loadExact(mapper, Path.of(locations.path("adoptionPath").asText()),
                    locations.path("adoptionSha256").asText(), SavedPredictionCalibrationTest.supplement());
            var allowed = binding.evidence().path("allowedObservations"); assertEquals(CANDIDATES.size(), allowed.size());
            for (int i = 0; i < CANDIDATES.size(); i++) assertEquals(CANDIDATES.get(i), allowed.get(i).path("candidateId").asText());
            receipt.put("inputLoadStage", "LOCAL_BINDING_LOAD");
            var local = PredictionLocalResourceBinding.load(mapper, Path.of(locations.path("bindingPath").asText()), locations.path("bindingSha256").asText());
            receipt.put("inputLoadStage", "COMPARISON_CONTRACT_LOAD");
            var contract = SavedPredictionCalibrationTest.contract();
            receipt.set("compatibilityEvidence", binding.evidence()); receipt.set("comparisonContract", contract);
            receipt.put("inputLoadStage", "SAVED_READER_LOAD");
            reader = SavedPredictionCalibration.load(mapper, binding, local, contract, paths);
            receipt.put("inputLoadStage", "COMPLETED");
        }
        @Override public SavedPredictionCalibration.VerifiedInput prepare(String candidate) throws Exception {
            if (reader == null) throw new IllegalStateException("OFFLINE_READER_NOT_LOADED");
            return reader.prepare(candidate);
        }
        @Override public ObjectNode compare(SavedPredictionCalibration.VerifiedInput input) {
            return PredictionClauseRuntime.calibrateStandard(SavedPredictionCalibrationTest.M, input);
        }
        @Override public void verifyPreservation(ObjectNode receipt) throws IOException {
            if (preservation == null) throw new IllegalStateException("OFFLINE_PRESERVATION_NOT_LOADED");
            var after = receipt.putObject("preservationAfter");
            boolean unchanged = true;
            for (var entry : preservation.properties()) {
                String sha = PredictionCalibration.sha256(Files.readAllBytes(Path.of(entry.getValue().path("path").asText())));
                after.put(entry.getKey(), sha); if (!entry.getValue().path("sha256").asText().equals(sha)) unchanged = false;
            }
            assertTrue(unchanged, "OFFLINE_INPUT_PRESERVATION_MISMATCH");
        }
    }
}
