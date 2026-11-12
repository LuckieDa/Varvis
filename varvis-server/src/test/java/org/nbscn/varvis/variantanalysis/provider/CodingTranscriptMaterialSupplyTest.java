package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exact-version acquisition and independent reference/annotation rejection; all clinical inputs synthetic. */
class CodingTranscriptMaterialSupplyTest {
    @TempDir Path root;
    final PredictionClauseRuntimeTest base = new PredictionClauseRuntimeTest();
    final AtomicInteger bases = new AtomicInteger();
    String reference = "ATGAAATAA";
    int referenceStart = 96;
    String referenceVersion = "accepted-synthetic-reference";
    RevelPredictionObservationTest.Input input;
    ObjectNode model, manifest;

    CodingTranscriptMaterialSupply setup() throws Exception {
        input = new RevelPredictionObservationTest().input("0.99", "GE", "0.7");
        model = (ObjectNode) input.fixture().observation().path("transcriptModel");
        model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(reference))
                .put("cdsSha256", CodingTranscriptMaterialSupply.sha(reference));
        return writeAndLoad();
    }
    CodingTranscriptMaterialSupply writeAndLoad() throws Exception {
        byte[] bytes = base.mapper.writeValueAsBytes(model); Files.write(root.resolve("ENST000001.4.model.json"), bytes);
        manifest = base.mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        manifest.putArray("entries").addObject().put("transcript", "ENST000001.4").put("file", "ENST000001.4.model.json")
                .put("fileBytes", bytes.length).put("fileSha256", CodingTranscriptMaterialSupply.sha(bytes))
                .put("canonicalSha256", base.adapter.canonicalSha256(model)).put("modelId", model.path("modelId").asText())
                .put("modelVersion", model.path("modelVersion").asText());
        return load();
    }
    CodingTranscriptMaterialSupply load() throws Exception {
        byte[] bytes = base.mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), bytes);
        return new CodingTranscriptMaterialSupply(base.mapper, root, CodingTranscriptMaterialSupply.sha(bytes), (c, s, n) -> {
            bases.addAndGet(n);
            return new CodingTranscriptMaterialSupply.ReferenceSpan(reference.substring(s - referenceStart, s - referenceStart + n),
                    base.mapper.createObjectNode().put("version", referenceVersion)
                            .put("expectedSha256", input.fixture().context().at("/variant/normalization/referenceSequenceSha256").asText()));
        });
    }
    @Test void untranslatedExonBasesAreIncludedWithoutBecomingCodingBases() throws Exception {
        setup(); reference = "CCCATGAAATAA"; referenceStart = 93;
        ((ObjectNode) model.at("/exons/0")).put("genomicStart", 93).put("cdsStart", -2);
        ((ObjectNode) model.path("sequenceVerification")).put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(reference));
        var result = resolve(writeAndLoad());
        assertTrue(result.ready(), result.snapshot().toString());
        assertEquals(12, result.snapshot().at("/verification/verifiedExonBases").asInt());
        assertEquals(5, result.snapshot().at("/alleleAssociation/cdsPosition").asInt());
    }
    @Test void negativeStrandUsesTranscriptOrientationAndKeepsGenomicAlleleIdentity() throws Exception {
        setup(); model.put("strand", -1); reference = "TTATTTCAT";
        var f = input.fixture();
        ((ObjectNode) f.context().at("/variant/evidenceQueryAllele")).put("reference", "T").put("alternate", "C");
        var key = new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey("GRCh38", "1", 100, "T", "C");
        var observation = new org.nbscn.varvis.variantanalysis.domain.VariantQcObservation(key, "synthetic", "0/1", null, null, null, null, "PASS", "SNV", null);
        var candidate = new org.nbscn.varvis.variantanalysis.domain.VariantInterpretation("negative-strand-synthetic", observation, java.util.List.of(), java.util.List.of());
        candidate.applyTranscriptAnnotations(f.candidate().getTranscriptAnnotations());
        var result = writeAndLoad().resolve(f.context(), candidate);
        assertTrue(result.ready(), result.snapshot().toString());
        assertEquals("K2R", result.snapshot().at("/alleleAssociation/proteinVariantKey").asText());
    }
    CodingTranscriptMaterialSupply.Material resolve(CodingTranscriptMaterialSupply supply) {
        return supply.resolve(input.fixture().context(), input.fixture().candidate());
    }
    @Test void obtainsExactModelChecksReferenceAndVepThenCachesOnlyTranscriptWork() throws Exception {
        var supply = setup(); var first = resolve(supply);
        assertTrue(first.ready(), first.snapshot().toString()); assertEquals(11, bases.get());
        assertEquals("K2R", first.snapshot().at("/alleleAssociation/proteinVariantKey").asText());
        requireNonNull(first.model()).removeAll(); first.snapshot().removeAll();
        var repeat = resolve(supply); assertEquals(model, repeat.model());
        assertTrue(repeat.snapshot().path("modelCacheHit").asBoolean()); assertEquals(12, bases.get());
        // Cached transcript proof must not cache the previous variant/annotation judgment.
        ((ObjectNode) input.fixture().context().at("/variant/evidenceQueryAllele")).put("alternate", "T");
        assertFalse(resolve(supply).ready()); assertEquals(12, bases.get());
    }
    @Test void changedQualifiedReferenceIdentityInvalidatesSuccessCache() throws Exception {
        var supply = setup(); assertTrue(resolve(supply).ready());
        referenceVersion = "different-reference";
        var next = resolve(supply); assertTrue(next.ready()); assertFalse(next.snapshot().path("modelCacheHit").asBoolean());
        assertEquals(22, bases.get());
    }
    @Test void sequenceMismatchNeverBecomesCachedSuccess() throws Exception {
        var supply = setup(); reference = "ATGAGATAA";
        assertEquals("TRANSCRIPT_MATERIAL_REFERENCE_SEQUENCE_MISMATCH", resolve(supply).snapshot().path("reasonCode").asText());
        reference = "ATGAAATAA"; assertTrue(resolve(supply).ready()); assertEquals(21, bases.get());
    }
    @Test void resourceTamperIsDetectedEvenAfterCacheHit() throws Exception {
        var supply = setup(); assertTrue(resolve(supply).ready());
        Path file = root.resolve("ENST000001.4.model.json");
        Files.writeString(file, Files.readString(file).replace("ATGAAATAA", "ATGAGATAA"));
        assertEquals("TRANSCRIPT_MATERIAL_FILE_IDENTITY_MISMATCH", resolve(supply).snapshot().path("reasonCode").asText());
        assertEquals(11, bases.get());
    }
    @Test void exactVersionHasNoAccessionOrGeneFallback() throws Exception {
        var supply = setup(); ((ObjectNode) input.fixture().context().path("transcript")).put("version", 5);
        assertEquals("TRANSCRIPT_MATERIAL_EXACT_VERSION_NOT_AVAILABLE", resolve(supply).snapshot().path("reasonCode").asText());
        assertEquals(0, bases.get());
    }
    @ParameterizedTest @ValueSource(strings={"gene", "assembly", "lineage", "annotation", "sequenceProof"})
    void rejectsMismatchedOrMissingEvidenceBeforeReferenceRead(String field) throws Exception {
        var supply = setup(); var context = input.fixture().context();
        switch (field) {
            case "gene" -> ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:999");
            case "assembly" -> ((ObjectNode) context.at("/variant/evidenceQueryAllele")).put("assembly", "GRCh37");
            case "lineage" -> ((ObjectNode) context.at("/variant/assemblyLineage")).put("mappingStatus", "CONVERTED");
            case "annotation" -> input.fixture().candidate().applyTranscriptAnnotations(java.util.List.of());
            default -> { model.remove("sequenceVerification"); supply = writeAndLoad(); }
        }
        assertFalse(resolve(supply).ready()); assertEquals(0, bases.get());
    }
    @Test void sourceAndManifestCannotBeSubstitutedOrAmbiguous() throws Exception {
        setup();
        assertThrows(IllegalArgumentException.class, () -> new CodingTranscriptMaterialSupply(base.mapper, root, "a".repeat(64), (c,s,n) -> null));
        manifest.withArray("/entries").add(manifest.path("entries").get(0).deepCopy());
        assertEquals("TRANSCRIPT_MATERIAL_AMBIGUOUS", assertThrows(IllegalArgumentException.class, this::load).getMessage());
    }
    @Test void manifestCannotEscapeDirectory() throws Exception {
        setup(); ((ObjectNode) manifest.at("/entries/0")).put("file", "../ENST000001.4.model.json");
        assertThrows(IllegalArgumentException.class, this::load);
    }
    @Test void cancellationPreventsReadsAndDoesNotClearFlag() throws Exception {
        var supply = setup(); Thread.currentThread().interrupt();
        try { assertEquals("TRANSCRIPT_MATERIAL_INTERRUPTED", resolve(supply).snapshot().path("reasonCode").asText());
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(0, bases.get()); }
        finally { Thread.interrupted(); }
    }
    @Test void invalidReferenceReadCannotBeUsed() throws Exception {
        setup(); byte[] bytes = Files.readAllBytes(root.resolve("manifest.json"));
        var supply = new CodingTranscriptMaterialSupply(base.mapper, root, CodingTranscriptMaterialSupply.sha(bytes), (c,s,n) ->
                new CodingTranscriptMaterialSupply.ReferenceSpan("N", base.mapper.createObjectNode().put("id", "synthetic")));
        assertEquals("TRANSCRIPT_REFERENCE_SPAN_INVALID", resolve(supply).snapshot().path("reasonCode").asText());
    }
    @Test void technicalBaseLimitIsCheckedBeforeAnyRead() throws Exception {
        setup(); ((ObjectNode) model.at("/exons/0")).put("genomicEnd", 1_000_096).put("cdsEnd", 1_000_001);
        var result = resolve(writeAndLoad());
        assertEquals("TRANSCRIPT_MATERIAL_BASE_LIMIT", result.snapshot().path("reasonCode").asText()); assertEquals(0, bases.get());
    }
    @Test void referenceCannotChangeBetweenExonsAndReceiptWitness() throws Exception {
        setup(); byte[] bytes = Files.readAllBytes(root.resolve("manifest.json"));
        var counter = new AtomicInteger();
        var supply = new CodingTranscriptMaterialSupply(base.mapper, root, CodingTranscriptMaterialSupply.sha(bytes), (c,s,n) ->
                new CodingTranscriptMaterialSupply.ReferenceSpan(reference.substring(s - 96, s - 96 + n),
                        base.mapper.createObjectNode().put("stamp", counter.incrementAndGet())
                                .put("expectedSha256", input.fixture().context().at("/variant/normalization/referenceSequenceSha256").asText())));
        assertEquals("TRANSCRIPT_REFERENCE_IDENTITY_CHANGED", resolve(supply).snapshot().path("reasonCode").asText());
    }
}
