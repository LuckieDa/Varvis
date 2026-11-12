package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in public local controls. No upload, VEP, population provider or inference. */
class PredictionRealAlphaMissenseTest {
    @Test
    void associationFailureRetainsCapturedObservationAndStopsNextControl(@TempDir Path root) throws Exception {
        var mapper = new ObjectMapper();
        var archive = mapper.readTree(Path.of("../docs/execution/PP3_AM_PUBLIC_VALIDATION_2026-09-17.json").toFile());
        var observation = archive.at("/controls/1/observation").deepCopy();
        var identity = ((com.fasterxml.jackson.databind.node.ObjectNode) observation).deepCopy();
        identity.remove("observationSha256");
        assertEquals(observation.path("observationSha256").asText(), new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(identity));
        var output = mapper.createObjectNode(); output.set("observation", observation);
        Path receipt = root.resolve("receipt.json");
        byte[] expected = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(output);
        var nextControl = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(IllegalArgumentException.class, () -> {
            captureThenAssociate(mapper, receipt, output, () -> { throw new IllegalArgumentException("INJECTED_ASSOCIATION_FAILURE"); });
            nextControl.set(true);
        });
        assertFalse(nextControl.get());
        assertArrayEquals(expected, Files.readAllBytes(receipt));
        assertEquals(observation, mapper.readTree(receipt.toFile()).path("observation"));
        assertEquals("HIT", observation.path("status").asText());
        var diagnostic = mapper.readTree(root.resolve("receipt.json.association-failure.json").toFile());
        assertEquals("ASSOCIATION_FAILED_AFTER_RAW_CAPTURE", diagnostic.path("reasonCode").asText());
        assertEquals("java.lang.IllegalArgumentException", diagnostic.path("exceptionClass").asText());
    }

    @Test
    void captureFailurePreventsAssociationAndNextControl(@TempDir Path root) {
        var mapper = new ObjectMapper(); var associated = new java.util.concurrent.atomic.AtomicBoolean();
        var nextControl = new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(java.io.IOException.class, () -> {
            captureThenAssociate(mapper, root, mapper.createObjectNode(), () -> {
                associated.set(true); return mapper.createObjectNode();
            });
            nextControl.set(true);
        });
        assertFalse(associated.get()); assertFalse(nextControl.get());
    }

    private static com.fasterxml.jackson.databind.JsonNode captureThenAssociate(ObjectMapper mapper, Path receipt,
            com.fasterxml.jackson.databind.node.ObjectNode output,
            java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> associate) throws java.io.IOException {
        Files.write(receipt, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(output));
        try {
            return associate.get();
        } catch (RuntimeException failure) {
            var diagnostic = mapper.createObjectNode().put("reasonCode", "ASSOCIATION_FAILED_AFTER_RAW_CAPTURE")
                    .put("exceptionClass", failure.getClass().getName());
            try {
                Files.write(receipt.resolveSibling(receipt.getFileName() + ".association-failure.json"),
                        mapper.writeValueAsBytes(diagnostic), StandardOpenOption.CREATE_NEW);
            } catch (java.io.IOException diagnosticFailure) {
                failure.addSuppressed(diagnosticFailure);
            }
            throw failure;
        }
    }

    @Test @EnabledIfSystemProperty(named = "varvis.prediction.replay.directory", matches = ".+")
    void replaySavedObservationWithArchivedProteinFieldsWithoutQuery() throws Exception {
        var mapper = new ObjectMapper(); var root = Path.of(System.getProperty("varvis.prediction.replay.directory"));
        var saved = mapper.readTree(root.resolve("real-observations-remaining.json").toFile());
        var entry = saved.at("/controls/0"); var input = entry.path("associationInput");
        String change = input.path("aminoAcidChange").asText(); assertTrue(change.matches("[A-Z][1-9][0-9]*[A-Z]"));
        var tx = mapper.createObjectNode().put("transcriptAccession", input.path("versionedTranscriptId").asText())
                .put("proteinPosition", change.substring(1, change.length() - 1)).put("aminoAcids", change.charAt(0) + "/" + change.charAt(change.length() - 1));
        var association = PredictionObservationAssociation.alpha(mapper, entry.path("observation"), mapper.treeToValue(tx, TranscriptConsequence.class), null);
        assertEquals("ASSOCIATED", association.path("status").asText()); assertEquals("0.1558", association.path("rawScore").asText());
        var proof = mapper.createObjectNode().put("lookupInvocations", 0).put("transformation", "Archived one-letter aminoAcidChange split into proteinPosition and aminoAcids; full transcript version retained");
        proof.set("associationInput", input); proof.set("association", association);
        Files.writeString(root.resolve("association-replay.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(proof), StandardOpenOption.CREATE_NEW);
    }

    @Test @EnabledIfSystemProperty(named = "varvis.prediction.real.directory", matches = ".+")
    void preRegisteredNativeControls() throws Exception {
        var mapper = new ObjectMapper(); var root = Path.of(System.getProperty("varvis.prediction.real.directory"));
        var binding = PredictionLocalResourceBinding.load(mapper, root.resolve("local-binding.json"), Files.readString(root.resolve("local-binding.sha256")).trim());
        var settings = new VariantAnalysisProperties(); settings.getAlphaMissense().setEnabled(true);
        settings.getAlphaMissense().setVersion("2023-08-03");
        settings.getAlphaMissense().setGrch38File(System.getProperty("varvis.prediction.real.data"));
        var provider = new AlphaMissenseProvider(settings, binding);
        var controls = mapper.readTree(root.resolve("controls-preregistered.json").toFile());
        assertEquals(2, controls.path("controls").size());
        var output = mapper.createObjectNode().put("schemaVersion", "pp3-am-public-observations-v1")
                .put("syntheticGridOnly", false).put("ordinaryBusinessRun", false).put("uniquePreRegistered", 2).put("budgetUnique", 8)
                .put("scoreOrigin", "REAL_LOCAL_PRECOMPUTED_RESOURCE").put("clinicalBackground", "NOT_ASSERTED");
        var rows = output.putArray("controls");
        // Exclusive receipt prevents accidental spending on a repeated test invocation.
        int resume = Integer.getInteger("varvis.prediction.real.resume-index", 0);
        Path receipt = root.resolve(resume == 0 ? "real-observations.json" : "real-observations-remaining.json"); Files.writeString(receipt, "{\"status\":\"STARTED\"}", StandardOpenOption.CREATE_NEW);
        var archive = mapper.readTree(Path.of("../docs/execution/E4_A2B_ACCEPTANCE_EXACT_DISPATCH_2026-09-06.json").toFile());
        int calls = 0, attempts = 0, hits = 0, rawRows = 0, associated = 0;
        int index = 0;
        for (var control : controls.path("controls")) {
            if (index++ < resume) continue;
            var key = mapper.treeToValue(control.path("queryAllele"), NormalizedVariantKey.class);
            var entry = rows.addObject(); entry.set("control", control);
            var input = archive.at("/candidates/" + (index - 1) + "/attempts/0/transcriptInput");
            var txJson = mapper.createObjectNode().put("transcriptAccession", input.path("versionedTranscriptId").asText())
                    .put("geneId", input.path("geneId").asText()).put("geneSymbol", input.path("geneSymbol").asText())
                    .put("consequence", input.path("consequence").asText()).put("hgvsProtein", input.path("hgvsp").asText());
            String change = input.path("aminoAcidChange").asText();
            assertTrue(change.matches("[A-Z][1-9][0-9]*[A-Z]"));
            txJson.put("proteinPosition", change.substring(1, change.length() - 1))
                    .put("aminoAcids", change.charAt(0) + "/" + change.charAt(change.length() - 1));
            var tx = mapper.treeToValue(txJson, TranscriptConsequence.class);
            var result = provider.lookupRecords(mapper, key, binding.identity(), PredictionDevelopmentRuntime.DECLARATION, "SYNTHETIC");
            calls++; attempts += result.path("transportAttempts").asInt(); if ("HIT".equals(result.path("status").asText())) hits++;
            rawRows += result.path("rows").size(); entry.set("observation", result);
            entry.set("associationInput", input); entry.put("associationSource", "E4_A2B_ACCEPTANCE_EXACT_DISPATCH_2026-09-06.json");
            var association = captureThenAssociate(mapper, receipt, output,
                    () -> PredictionObservationAssociation.alpha(mapper, result, tx, null));
            entry.set("association", association);
            if ("ASSOCIATED".equals(association.path("status").asText())) associated++;
            entry.put("calibrationStatus", "NOT_EVALUATED").put("calibrationReason", "CALIBRATION_RESOURCE_COMPATIBILITY_UNVERIFIED")
                    .put("applicationStatus", "NOT_EVALUATED");
            output.put("lookupInvocations", calls).put("transportAttempts", attempts).put("hit", hits).put("rawRows", rawRows)
                    .put("associatedBackgrounds", associated).put("calibrated", 0).put("applicablePp3", 0).put("applicableBp4", 0);
            Files.writeString(receipt, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            assertEquals(1, result.path("transportAttempts").asInt(), result.toString());
            assertTrue(java.util.Set.of("HIT", "NO_RECORD").contains(result.path("status").asText()), result.toString());
        }
        output.put("noRecord", calls - hits).put("errors", 0);
        Files.writeString(receipt, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(output));
    }
}
