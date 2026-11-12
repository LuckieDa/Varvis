package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Portable synthetic fixture by default; an explicitly generated tiny builder fixture is also supported. */
class RevelPublisherIndexObservationProviderTest {
    @TempDir Path temporary;
    final RevelPredictionObservationTest inputs = new RevelPredictionObservationTest();
    final ReviewedPredictionBundleTest bundles = new ReviewedPredictionBundleTest();
    ObjectNode manifest, review;
    Path tabix, reviewFile;
    String manifestOriginal;

    @BeforeEach void preparedSyntheticOnly() throws Exception {
        String fixture = System.getenv("VARVIS_REVEL_ADAPTER_FIXTURE"), executable = System.getenv("VARVIS_REVEL_TABIX");
        if (fixture == null && executable == null) {
            Path generated = Files.createDirectory(temporary.resolve("portable-synthetic-fixture"));
            executable = RevelLocalIndexFixture.create(generated).toString(); fixture = generated.toString();
            Files.writeString(generated.resolve("SYNTHETIC_ONLY.txt"), "SYNTHETIC_REVEL_ADAPTER_FIXTURE_ONLY");
        }
        assumeTrue(fixture != null && executable != null, "Explicit completed tiny synthetic builder fixture and existing tabix required");
        Path directory = Path.of(java.util.Objects.requireNonNull(fixture));
        assertEquals("SYNTHETIC_REVEL_ADAPTER_FIXTURE_ONLY", Files.readString(directory.resolve("SYNTHETIC_ONLY.txt")).strip());
        for (String name : List.of("manifest.json", RevelPublisherIndexManifest.DATA, RevelPublisherIndexManifest.INDEX, RevelPublisherIndexManifest.EXCLUDED)) {
            assertTrue(Files.size(directory.resolve(name)) < 1_000_000, "Real index forbidden in synthetic tests");
            Files.copy(directory.resolve(name), temporary.resolve(name));
        }
        manifestOriginal = Files.readString(temporary.resolve("manifest.json"));
        manifest = (ObjectNode) inputs.base.mapper.readTree(manifestOriginal);
        tabix = Path.of(java.util.Objects.requireNonNull(executable));
        reviewFile = temporary.resolve("review.json");
        review = inputs.base.mapper.createObjectNode().put("schemaVersion", "reviewed-revel-publisher-index-v1")
                .put("adapterId", RevelPublisherIndexObservationProvider.ID).put("adapterVersion", RevelPublisherIndexObservationProvider.VERSION)
                .put("resourceId", "REVEL_SYNTHETIC_PUBLISHER").put("resourceVersion", "1.3-synthetic")
                .put("clinicalAcceptance", false).put("defaultEnabled", false);
        review.putObject("review").put("status", "REVIEWED_INDEX_INTEGRITY").put("reviewId", "SYNTHETIC_ONLY");
        review.putObject("producer").put("version", manifest.path("toolVersion").asText()).put("sha256", manifest.path("producerSha256").asText());
        var artifacts = review.putArray("artifacts");
        for (JsonNode artifact : manifest.path("artifacts")) {
            ObjectNode entry = artifact.deepCopy();
            entry.put("mtimeNs", modified(temporary.resolve(entry.path("file").asText()))); artifacts.add(entry);
        }
        review.putObject("tool").put("bytes", Files.size(tabix)).put("mtimeNs", modified(tabix))
                .put("sha256", manifest.at("/tools/tabix/sha256").asText()).put("version", manifest.at("/tools/tabix/version").asText());
        resign();
    }

    void resign() throws IOException {
        byte[] bytes = inputs.base.mapper.writeValueAsBytes(manifest); Files.write(temporary.resolve("manifest.json"), bytes);
        review.putObject("manifest").put("file", "manifest.json").put("bytes", bytes.length).put("sha256", RevelPublisherIndexManifest.digest(bytes))
                .put("mtimeNs", modified(temporary.resolve("manifest.json")));
        Files.write(reviewFile, inputs.base.mapper.writeValueAsBytes(review));
    }
    static long modified(Path path) throws IOException { return Files.getLastModifiedTime(path).to(TimeUnit.NANOSECONDS); }
    RevelPublisherIndexObservationProvider provider(ExternalProcessRunner runner) throws IOException {
        return new RevelPublisherIndexObservationProvider(inputs.base.mapper, temporary, tabix, reviewFile,
                RevelPublisherIndexManifest.digest(Files.readAllBytes(reviewFile)), inputs.base.adapter.canonicalSha256(review), runner);
    }
    ObjectNode request(RevelPublisherIndexObservationProvider provider) throws Exception {
        var input = inputs.input("0.7", "GE", "0.7");
        ObjectNode request = inputs.base.mapper.createObjectNode().put("schemaVersion", "reviewed-prediction-observation-request-v1").put("preselectedModel", "REVEL");
        request.set("context", input.fixture().context().deepCopy()); request.set("resourceBinding", provider.resourceBinding());
        request.set("transcriptModel", input.fixture().observation().path("transcriptModel").deepCopy());
        request.set("transcriptModelBinding", input.policy().path("transcriptModelBinding").deepCopy());
        request.set("revelTranscriptBinding", input.policy().path("revelTranscriptBinding").deepCopy());
        seal(request); return request;
    }
    void seal(ObjectNode request) { request.remove("lookupId"); request.put("lookupId", "prediction-lookup-sha256-" + inputs.base.adapter.canonicalSha256(request)); }
    static String line(int ordinal, String raw) { return "1\t100\t" + ordinal + "\t" + raw + "\n"; }
    static String raw(String score) { return "1,90,100,A,G,K,R," + score + ",ENST000001"; }
    static ExternalProcessRunner returning(String value) { return (command, directory, timeout) -> new ProcessResult(0, value, false); }

    @Test void batchCoordinatesAreReadOnceButDifferentAltIsReparsedAndResourceIsRechecked() throws Exception {
        var calls = new AtomicInteger();
        var provider = provider((command, directory, timeout) -> {
            calls.incrementAndGet(); return new ProcessResult(0, line(1, raw("0.7")) + line(2, "1,90,100,A,T,K,N,0.1,ENST000001"), false);
        }).withBatchFactReuse();
        var firstRequest = request(provider); var first = provider.lookupSnapshot(firstRequest);
        assertEquals("HIT", first.path("status").asText()); assertEquals(1, calls.get());
        var secondRequest = firstRequest.deepCopy();
        ((ObjectNode) secondRequest.at("/context/variant/evidenceQueryAllele")).put("alternate", "T");
        ((ObjectNode) secondRequest.at("/context/variant/inputAllele")).put("alternate", "T");
        ((ObjectNode) secondRequest.at("/context/variant/normalizedAllele")).put("alternate", "T");
        ((ObjectNode) secondRequest.path("context")).put("contextId", inputs.base.adapter.canonicalContextId(secondRequest.path("context")));
        ((ObjectNode) secondRequest.path("context").path("variant")).put("alleleId", inputs.base.adapter.canonicalAlleleId(
                secondRequest.at("/context/variant/normalizedAllele"), secondRequest.at("/context/variant/normalization")));
        ((ObjectNode) secondRequest.path("context")).put("contextId", inputs.base.adapter.canonicalContextId(secondRequest.path("context")));
        seal(secondRequest);
        var second = provider.lookupSnapshot(secondRequest);
        assertEquals("HIT", second.path("status").asText(), second.toPrettyString());
        assertEquals("1,90,100,A,T,K,N,0.1,ENST000001", second.at("/rawRecords/0/rawCsv").asText());
        assertEquals(1, calls.get()); assertTrue(second.at("/rawFactReuse/reused").asBoolean());
        assertEquals(0, second.path("physicalTabixCalls").asInt());
        assertEquals(first.at("/evidence/source/retrievedAt"), second.at("/evidence/source/retrievedAt"));
        Files.setLastModifiedTime(temporary.resolve(RevelPublisherIndexManifest.DATA), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));
        var changed = provider.lookupSnapshot(firstRequest);
        assertEquals("UNAVAILABLE", changed.path("status").asText()); assertEquals(1, calls.get());
    }
    @Test void failedBatchTransportDoesNotRetryForAnotherContext() throws Exception {
        var calls = new AtomicInteger(); var provider = provider((command, directory, timeout) -> {
            calls.incrementAndGet(); return new ProcessResult(1, "", false);
        }).withBatchFactReuse();
        var r = request(provider); var first = provider.lookupSnapshot(r); var second = provider.lookupSnapshot(r.deepCopy());
        assertEquals("UNAVAILABLE", first.path("status").asText()); assertEquals(first.path("reasonCode"), second.path("reasonCode"));
        assertEquals(1, calls.get()); assertEquals(1, first.path("physicalTabixCalls").asInt()); assertEquals(0, second.path("physicalTabixCalls").asInt());
    }

    @Test void rawDuplicatesAndDifferentAlleleTranscriptAreAllPreservedWithoutChoosingScore() throws Exception {
        String output = line(1, raw("0.700")) + line(2, raw("0.7"))
                + line(3, "1,90,100,A,T,K,N,0.1,ENST000001") + line(4, "1,90,100,A,G,K,R,0.99,ENST999999");
        var provider = provider(returning(output));
        ObjectNode observed = provider.lookupSnapshot(request(provider));
        assertEquals("HIT", observed.path("status").asText(), observed.toPrettyString());
        assertEquals(4, observed.path("coordinateRecords").size()); assertEquals(2, observed.path("rawRecords").size());
        assertEquals(raw("0.700"), observed.at("/rawRecords/0/rawCsv").asText());
        assertEquals(raw("0.7"), observed.at("/rawRecords/1/rawCsv").asText());
        assertEquals("0.7", observed.at("/evidence/measurements/0/normalizedValue").asText());
        assertEquals(manifest.at("/source/memberSha256"), provider.resourceBinding().path("sourceArtifactSha256"));
        assertEquals(provider.resourceBinding().path("sourceArtifactSha256"), observed.at("/evidence/source/sourceRecords/0/locator/artifactSha256"));
        assertFalse(observed.toString().contains(temporary.toString())); assertFalse(observed.toString().contains(tabix.toString()));
    }

    @ParameterizedTest @ValueSource(strings = {"0.01", ".", "NaN", "2"})
    void conflictingOrInvalidScoreKeepsBothRowsAndExistingRuntimeRefuses(String score) throws Exception {
        var provider = provider(returning(line(1, raw("0.99")) + line(2, raw(score))));
        ObjectNode request = request(provider), observed = provider.lookupSnapshot(request);
        assertEquals("HIT", observed.path("status").asText()); assertEquals(2, observed.path("rawRecords").size());
        assertTrue(observed.at("/evidence/measurements").isEmpty());
        var input = inputs.input("0.7", "GE", "0.7"); input.policy().set("resourceBinding", provider.resourceBinding());
        var candidate = input.fixture().candidate();
        ObjectNode result = inputs.base.runtime.evaluate(input.fixture().context(), candidate, input.policy(),
                inputs.base.selection(input.fixture().context(), input.policy(), "SPECIALIZED_IMPLEMENTED"), observed, null);
        inputs.base.assertRefused(result);
    }

    @Test void selectedReviewedBundleQueriesOnceAndNativeCdsVepStillControlsRule() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var provider = provider((command, directory, timeout) -> { calls.incrementAndGet(); return new ProcessResult(0, line(1, raw("0.7")), false); });
        var input = inputs.input("0.7", "GE", "0.7"); input.policy().set("resourceBinding", provider.resourceBinding());
        ObjectNode document = bundles.document(input.policy());
        ((ObjectNode) document.at("/materials/transcriptModels")).set(input.policy().at("/transcriptModelBinding/canonicalSha256").asText(), input.fixture().observation().path("transcriptModel"));
        ObjectNode result = bundles.load(document).evaluate(input.fixture().context(), input.fixture().candidate(), provider);
        bundles.assertStatus(result, "PP3", "MET"); assertEquals(1, calls.get());
        assertEquals(1, result.at("/executionCounts/lookupCallbackAttempts").asInt());
        assertEquals(1, result.at("/executionCounts/completedClauseEvaluations").asInt());
        assertEquals(RevelPublisherIndexManifest.VERSION_STATUS, result.at("/clauses/PP3/result/resolvedInput/sourceTranscriptVersionStatus").asText());
        ObjectNode changed = inputs.base.mapper.valueToTree(input.fixture().candidate().getTranscriptAnnotations().get(0));
        changed.retain("geneSymbol", "entrezGeneId", "transcriptAccession", "hgvsCoding", "hgvsProtein", "consequence",
                "geneId", "transcriptId", "transcriptSource", "impact", "exon", "intron", "canonical", "maneSelect",
                "manePlusClinical", "proteinPosition", "aminoAcids", "codons", "lof", "lofFilter", "lofFlags", "source", "status", "version", "timestamp");
        changed.put("codons", "AAA/ATA");
        input.fixture().candidate().applyTranscriptAnnotations(List.of(inputs.base.mapper.treeToValue(changed,
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        result = bundles.load(document).evaluate(input.fixture().context(), input.fixture().candidate(), provider);
        assertEquals("NOT_EVALUATED", result.at("/clauses/PP3/result/evaluationResult/status").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"", "1\t100\t1\t1,90,100,A,T,K,N,0.1,ENST000001\n", "1\t100\t1\t1,90,100,A,G,K,R,0.1,ENST999999\n"})
    void onlyCompleteEmptyMatchingSetMeansNoRecord(String output) throws Exception {
        var p = provider(returning(output)); ObjectNode result = p.lookupSnapshot(request(p));
        assertEquals("NO_RECORD", result.path("status").asText()); assertEquals("NO_RECORD", result.at("/evidence/queryOutcome/status").asText());
        assertEquals(0, result.at("/evidence/queryOutcome/recordCount").asInt()); assertTrue(result.at("/evidence/measurements").isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"truncated", "duplicate", "contradictory", "unknownTranscript", "versionedTranscript", "malformedAllele", "unknownColumn", "wrongCoordinate", "nonAscii", "overflow", "outputLimit"})
    void corruptOrIncompleteCoordinateResultsCannotBecomePartialEvidence(String kind) throws Exception {
        String output = line(1, raw("0.7"));
        output = switch (kind) {
            case "truncated" -> output.stripTrailing();
            case "duplicate" -> output + output;
            case "contradictory" -> output + line(1, raw("0.1"));
            case "unknownTranscript" -> output + line(2, raw("0.2").replace("ENST000001", "."));
            case "versionedTranscript" -> output + line(2, raw("0.2").replace("ENST000001", "ENST000001.4"));
            case "malformedAllele" -> output + line(2, raw("0.2").replace(",A,G,", ",A,.,"));
            case "unknownColumn" -> output.replace("\n", "\textra\n");
            case "wrongCoordinate" -> output.replace("1\t100", "1\t101");
            case "nonAscii" -> output.replace("0.7", "é");
            case "overflow" -> output.replace("\t1\t1,", "\t9223372036854775808\t1,");
            default -> "x".repeat(BoundedTabixProcessRunner.MAX_STDOUT + 1) + "\n";
        };
        var p = provider(returning(output)); assertUnavailable(p.lookupSnapshot(request(p)));
    }

    @ParameterizedTest @ValueSource(strings = {"otherModel", "binding", "projection", "allele", "unconfirmed", "bridge", "model", "interrupted"})
    void invalidPreselectionAndContextNeverLaunchQuery(String kind) throws Exception {
        AtomicInteger calls = new AtomicInteger(); var p = provider((c,d,t) -> { calls.incrementAndGet(); return new ProcessResult(0,"",false); });
        ObjectNode request = request(p);
        switch (kind) {
            case "otherModel" -> request.put("preselectedModel", "ALPHAMISSENSE");
            case "binding" -> ((ObjectNode) request.path("resourceBinding")).put("sourceArtifactSha256", "b".repeat(64));
            case "projection" -> ((ObjectNode) request.at("/context/variant/assemblyLineage")).put("mappingStatus", "PROJECTED");
            case "allele" -> ((ObjectNode) request.at("/context/variant/evidenceQueryAllele")).put("alternate", "AT");
            case "unconfirmed" -> ((ObjectNode) request.at("/context/transcript")).put("selectionPolicy", "PM2_CONTEXT_ONLY");
            case "bridge" -> ((ObjectNode) request.path("revelTranscriptBinding")).put("accession", "ENST999999");
            case "model" -> ((ObjectNode) request.path("transcriptModel")).put("cdsSequence", "ATGAGATAA");
            case "interrupted" -> Thread.currentThread().interrupt();
            default -> fail();
        }
        seal(request);
        try { assertEquals("UNAVAILABLE", p.lookupSnapshot(request).path("status").asText()); }
        finally { if ("interrupted".equals(kind)) assertTrue(Thread.interrupted()); }
        assertEquals(0, calls.get());
    }

    @Test void metadataMutationAndTransportFailureStayUnavailable() throws Exception {
        var p = provider(returning(line(1, raw("0.7")))); ObjectNode request = request(p);
        Files.writeString(temporary.resolve(RevelPublisherIndexManifest.DATA), "changed", StandardCharsets.US_ASCII);
        assertUnavailable(p.lookupSnapshot(request));
    }

    @ParameterizedTest @ValueSource(strings = {"transport", "output"})
    void cancellationAfterQueryStartsCannotReturnSuccessfulEvidence(String stage) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var p = provider((command, directory, timeout) -> {
            calls.incrementAndGet();
            if ("transport".equals(stage)) Thread.currentThread().interrupt();
            return new ProcessResult(0, line(1, raw("0.7")), false) {
                @Override @org.springframework.lang.NonNull public String getOutput() {
                    if ("output".equals(stage)) Thread.currentThread().interrupt();
                    return java.util.Objects.requireNonNull(super.getOutput());
                }
            };
        });
        ObjectNode request = request(p);
        try {
            ObjectNode result = p.lookupSnapshot(request);
            assertUnavailable(result);
            assertEquals("REVEL_QUERY_INTERRUPTED", result.path("reasonCode").asText());
            assertTrue(result.at("/evidence/measurements").isEmpty());
            assertFalse(result.has("coordinateRecords"));
            assertFalse(result.has("matchedRowCount"));
            assertEquals(1, result.path("physicalTabixCalls").asInt());
            assertEquals(1, calls.get());
            assertTrue(Thread.currentThread().isInterrupted(), "Provider must preserve caller cancellation");
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest @ValueSource(strings = {"modelValue", "modelKey", "contextValue", "contextKey"})
    void localPathsRequireANewReviewedPortableModelAndCannotLeakInFailureOutput(String location) throws Exception {
        AtomicInteger calls = new AtomicInteger(); var p = provider((c,d,t) -> { calls.incrementAndGet(); return new ProcessResult(0,"",false); });
        ObjectNode request = request(p);
        ObjectNode container = (ObjectNode) request.path(location.startsWith("model") ? "transcriptModel" : "context");
        if (location.endsWith("Key")) container.putObject("nested").put("D:/private/source.fa", true);
        else container.put("localReferencePath", "D:/private/source.fa");
        ObjectNode result = p.lookupSnapshot(request);
        assertEquals("UNAVAILABLE", result.path("status").asText()); assertEquals(0, calls.get());
        assertEquals("REVEL_LOCAL_PATH_REQUIRES_SANITIZED_REVIEWED_MODEL", result.path("reasonCode").asText());
        assertFalse(result.toString().contains("D:/private")); assertFalse(result.has("transcriptModel"));
        assertFalse(result.has("context"));
    }

    @ParameterizedTest @ValueSource(strings = {"nonzero", "timeout", "exception"})
    void failedTransportHasNoNegativeEvidence(String kind) throws Exception {
        var p = provider((c,d,t) -> { if ("exception".equals(kind)) throw new IOException("private-path-secret"); return new ProcessResult("nonzero".equals(kind) ? 1 : 0, line(1,raw("0.7")), "timeout".equals(kind)); });
        ObjectNode result = p.lookupSnapshot(request(p)); assertUnavailable(result); assertFalse(result.toString().contains("private-path-secret"));
    }

    @ParameterizedTest @ValueSource(strings = {"status", "publisherLookupReady", "sourceDataRows", "producerSha256", "sourceLineConvention", "validation", "artifacts", "unknown"})
    void incompleteOrUnexpectedManifestNeverLoadsEvenWhenReceiptIsRepinned(String field) throws Exception {
        if ("unknown".equals(field)) manifest.put("silentlyEnableClinicalUse", true); else manifest.remove(field);
        resign(); assertThrows(IOException.class, () -> provider(returning("")));
    }

    @ParameterizedTest @ValueSource(strings = {"bgzf", "tbi", "member", "crc", "rowCount", "overflow", "unknownValidation", "tool", "reviewFlag", "artifact"})
    void fullIntegrityAndClosedReceiptAreRequiredEvenWithNewJsonPins(String kind) throws Exception {
        switch (kind) {
            case "bgzf" -> ((ObjectNode) manifest.path("validation")).put("fullBgzfIntegrityTest", false);
            case "tbi" -> ((ObjectNode) manifest.path("validation")).put("completeTbiParsed", false);
            case "member" -> ((ObjectNode) manifest.path("source")).put("memberSha256", "0".repeat(64));
            case "crc" -> ((ObjectNode) manifest.path("source")).put("memberCrc32", "ffffffff");
            case "rowCount" -> ((ObjectNode) manifest.path("validation")).put("fullIndexedRowsReparsed", 1);
            case "overflow" -> manifest.put("maximumDiskBytes", new java.math.BigInteger("9223372036854775808"));
            case "unknownValidation" -> ((ObjectNode) manifest.path("validation")).put("skipFullCheck", true);
            case "tool" -> ((ObjectNode) review.path("tool")).put("sha256", "0".repeat(64));
            case "reviewFlag" -> review.put("automaticClinicalApproval", true);
            case "artifact" -> ((ObjectNode) review.path("artifacts").path(0)).put("sha256", "0".repeat(64));
            default -> fail();
        }
        resign(); assertThrows(IOException.class, () -> provider(returning("")));
    }

    @Test void reviewFileAndCanonicalPinsAreIndependentAndTrailingJsonIsRejected() throws Exception {
        String canonical = inputs.base.adapter.canonicalSha256(review), fileSha = RevelPublisherIndexManifest.digest(Files.readAllBytes(reviewFile));
        assertThrows(IOException.class, () -> new RevelPublisherIndexObservationProvider(inputs.base.mapper,temporary,tabix,reviewFile,"0".repeat(64),canonical,returning("")));
        assertThrows(IOException.class, () -> new RevelPublisherIndexObservationProvider(inputs.base.mapper,temporary,tabix,reviewFile,fileSha,"0".repeat(64),returning("")));
        Files.writeString(reviewFile, Files.readString(reviewFile) + "\n{}");
        assertThrows(IOException.class, () -> provider(returning("")));
    }

    @Test void actualTinyBgzfTabixQueryUsesCompletedBuilderIdentity() throws Exception {
        assumeTrue(System.getenv("VARVIS_REVEL_TABIX") != null, "External executable test remains explicitly opt-in; local transport has a separate real indexed test");
        var p = provider(new BoundedTabixProcessRunner()); ObjectNode result = p.lookupSnapshot(request(p));
        assertEquals("HIT", result.path("status").asText(), result.toPrettyString());
        assertEquals(2, result.path("rawRecords").size());
        assertEquals(1, result.path("physicalTabixCalls").asInt());
        assertEquals(raw("0.7"), result.at("/rawRecords/0/rawCsv").asText());
    }

    void assertUnavailable(ObjectNode result) {
        assertEquals("UNAVAILABLE", result.path("status").asText(), result.toPrettyString());
        assertTrue(result.path("rawRecords").isEmpty());
        assertEquals("UNAVAILABLE", result.at("/evidence/resourceAvailability/status").asText());
        assertEquals("NOT_QUERIED", result.at("/evidence/queryOutcome/status").asText());
        assertEquals(0, result.at("/evidence/queryOutcome/recordCount").asInt());
    }
}
