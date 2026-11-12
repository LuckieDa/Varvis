package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GencodeTranscriptIndexTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "pvs1.gencodeInventory", matches = ".+")
    void realInventoryIncludesFilteredRecordsWithoutGrantingFunctionalCoverage() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var directory = Path.of(System.getProperty("pvs1.gencodeInventory"));
        var index = new GencodeTranscriptIndex(mapper, directory,
                CodingTranscriptMaterialSupply.sha(Files.readAllBytes(directory.resolve("manifest.json"))));
        for (String[] gene : new String[][]{{"HGNC:6990", "MECP2", "X"}, {"HGNC:9588", "PTEN", "10"}, {"HGNC:11998", "TP53", "17"}}) {
            var context = mapper.createObjectNode(); context.putObject("gene").put("hgncId", gene[0]).put("symbol", gene[1]);
            context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", gene[2]);
            var inventory = index.inventory(context);
            assertTrue(inventory.path("sourceCoverageComplete").asBoolean(), inventory.toPrettyString());
            assertFalse(inventory.path("functionalCoverageComplete").asBoolean());
            assertTrue(inventory.path("transcripts").size() > 1);
            assertTrue(java.util.stream.StreamSupport.stream(inventory.path("transcripts").spliterator(), false)
                    .anyMatch(row -> "UNRESOLVED".equals(row.path("structureStatus").asText())), inventory.toPrettyString());
        }
    }
    @TempDir Path root;
    final PredictionClauseRuntimeTest base = new PredictionClauseRuntimeTest();
    final AtomicInteger bases = new AtomicInteger();
    RevelPredictionObservationTest.Input input;
    ObjectNode row, manifest;
    String reference = "ATGAAATAA", version = "test-v1";

    @Test void automaticGeneInventoryIsPinnedDetachedAndNeverClaimsFunctionalCompleteness() throws Exception {
        var index = setup();
        var first = index.inventory(input.fixture().context());
        assertEquals(1, first.path("transcripts").size());
        assertFalse(first.path("functionalCoverageComplete").asBoolean());
        assertEquals(0, bases.get(), "inventory must not read full genomic sequence");
        ((ObjectNode) first.path("transcripts").get(0)).removeAll();
        assertEquals("ENST000001.4", index.inventory(input.fixture().context()).at("/transcripts/0/transcript").asText());
        Files.writeString(root.resolve("annotations.jsonl"), "changed");
        assertThrows(IllegalArgumentException.class, () -> index.inventory(input.fixture().context()));
    }

    @Test void fullSourceInventoryKeepsRejectedTranscriptsAndChecksTheirBytes() throws Exception {
        setup();
        var accepted = row.deepCopy().put("transcriptType", "protein_coding").put("structureStatus", "AVAILABLE");
        var rejected = row.deepCopy().put("transcript", "ENST000002.1").put("transcriptType", "protein_coding")
                .put("structureStatus", "UNRESOLVED").put("reason", "GTF_INCOMPLETE_OR_READTHROUGH");
        rejected.remove("exons");
        String a = accepted + "\n", b = rejected + "\n";
        byte[] raw = (a + b).getBytes(StandardCharsets.UTF_8);
        String symbol = row.path("geneSymbol").asText(), hgnc = row.path("hgncId").asText();
        byte[] idx = ("ENST000001.4\t" + hgnc + "\t" + symbol + "\t0\t" + a.getBytes(StandardCharsets.UTF_8).length + "\t" + CodingTranscriptMaterialSupply.sha(a)
                + "\nENST000002.1\t" + hgnc + "\t" + symbol + "\t" + a.getBytes(StandardCharsets.UTF_8).length + "\t" + b.getBytes(StandardCharsets.UTF_8).length
                + "\t" + CodingTranscriptMaterialSupply.sha(b) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("inventory.jsonl"), raw); Files.write(root.resolve("inventory-index.tsv"), idx);
        manifest.put("inventoryCoverage", "ALL_SOURCE_TRANSCRIPTS_BEFORE_MODEL_FILTER").put("transcripts", 2);
        manifest.putObject("inventory").put("file", "inventory.jsonl").put("bytes", raw.length).put("sha256", CodingTranscriptMaterialSupply.sha(raw));
        manifest.putObject("inventoryIndex").put("file", "inventory-index.tsv").put("bytes", idx.length).put("sha256", CodingTranscriptMaterialSupply.sha(idx));
        var index = write(); var inventory = index.inventory(input.fixture().context());
        assertTrue(inventory.path("sourceCoverageComplete").asBoolean());
        assertFalse(inventory.path("functionalCoverageComplete").asBoolean());
        assertEquals(2, inventory.path("transcripts").size());
        assertEquals("GTF_INCOMPLETE_OR_READTHROUGH", inventory.at("/transcripts/1/reason").asText());
        Files.writeString(root.resolve("inventory.jsonl"), "corrupt");
        assertThrows(IllegalArgumentException.class, () -> index.inventory(input.fixture().context()));
    }

    GencodeTranscriptIndex setup() throws Exception {
        input = new RevelPredictionObservationTest().input("0.99", "GE", "0.7");
        row = ((ObjectNode) input.fixture().observation().path("transcriptModel")).deepCopy();
        row.remove(java.util.List.of("sourceRecords", "cdsSequence", "schemaVersion", "modelId", "modelVersion"));
        row.put("transcriptLength", 9).put("rawGtfSha256", "b".repeat(64));
        manifest = base.mapper.createObjectNode().put("schemaVersion", "gencode-transcript-index-v1").put("methodVersion", "1.0.0")
                .put("assembly", "GRCh38").put("activation", "TECHNICAL_MATERIAL_ONLY").put("release", 38).put("independentRnaVerification", false);
        manifest.putObject("source").put("sha256", "a".repeat(64)).put("url", "https://ftp.ebi.ac.uk/pub/databases/gencode/Gencode_human/release_38/gencode.v38.annotation.gtf.gz");
        manifest.putObject("counts").put("indexed", 1);
        return write();
    }
    GencodeTranscriptIndex write() throws Exception {
        byte[] data = (row.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("annotations.jsonl"), data);
        byte[] index = ("ENST000001.4\t0\t" + data.length + "\t" + CodingTranscriptMaterialSupply.sha(data) + "\n").getBytes(StandardCharsets.US_ASCII);
        Files.write(root.resolve("index.tsv"), index);
        manifest.putObject("data").put("file", "annotations.jsonl").put("bytes", data.length).put("sha256", CodingTranscriptMaterialSupply.sha(data));
        manifest.putObject("index").put("file", "index.tsv").put("bytes", index.length).put("sha256", CodingTranscriptMaterialSupply.sha(index));
        return load();
    }
    GencodeTranscriptIndex load() throws Exception {
        byte[] raw = base.mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), raw);
        return new GencodeTranscriptIndex(base.mapper, root, CodingTranscriptMaterialSupply.sha(raw));
    }
    CodingTranscriptMaterialSupply.ReferenceSpan read(String contig, int start, int length) {
        bases.addAndGet(length);
        return new CodingTranscriptMaterialSupply.ReferenceSpan(reference.substring(start - 96, start - 96 + length),
                base.mapper.createObjectNode().put("version", version).put("expectedSha256", input.fixture().context().at("/variant/normalization/referenceSequenceSha256").asText()));
    }
    CodingTranscriptMaterialSupply supply(GencodeTranscriptIndex index) throws Exception {
        return new CodingTranscriptMaterialSupply(base.mapper, null, "", this::read, index);
    }
    @Test void suppliesNewExactModelToExistingAlleleChecksAndReusesOnlySequence() throws Exception {
        var supply = supply(setup()); var first = supply.resolve(input.fixture().context(), input.fixture().candidate());
        assertTrue(first.ready(), first.snapshot().toString());
        assertEquals("GENCODE_GTF_PLUS_REFERENCE", first.snapshot().path("modelOrigin").asText());
        assertEquals("K2R", first.snapshot().at("/alleleAssociation/proteinVariantKey").asText());
        assertFalse(requireNonNull(first.model()).at("/sequenceVerification/independentRnaVerification").asBoolean());
        int before = bases.get(); var repeat = supply.resolve(input.fixture().context(), input.fixture().candidate());
        assertTrue(repeat.ready()); assertEquals(first.model(), repeat.model()); assertEquals(2, bases.get() - before);
        ObjectNode invalid = base.mapper.valueToTree(input.fixture().candidate().getTranscriptAnnotations().get(0));
        invalid.put("codons", "AAA/CCC");
        input.fixture().candidate().applyTranscriptAnnotations(java.util.List.of(base.mapper.copy()
                .disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(invalid,
                org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        assertFalse(supply.resolve(input.fixture().context(), input.fixture().candidate()).ready());
    }
    @Test void exactVersionAndGeneMismatchNeverReadReferenceOrChooseNeighbor() throws Exception {
        var supply = supply(setup()); var context = input.fixture().context().deepCopy();
        context.withObject("/transcript").put("version", 5);
        assertEquals("GENCODE_EXACT_TRANSCRIPT_VERSION_UNAVAILABLE", supply.resolve(context, input.fixture().candidate()).snapshot().path("reasonCode").asText());
        context = input.fixture().context().deepCopy(); context.withObject("/gene").put("symbol", "OTHER");
        assertEquals("GENCODE_CONTEXT_IDENTITY_MISMATCH", supply.resolve(context, input.fixture().candidate()).snapshot().path("reasonCode").asText());
        assertEquals(0, bases.get());
    }
    @Test void existingPinnedModelWinsAndCorruptionNeverFallsBackToGencode() throws Exception {
        var index = setup();
        var old = new CodingTranscriptMaterialSupplyTest();
        old.root = Files.createDirectory(root.resolve("prebuilt")); old.setup();
        var supply = new CodingTranscriptMaterialSupply(base.mapper, old.root,
                CodingTranscriptMaterialSupply.sha(Files.readAllBytes(old.root.resolve("manifest.json"))), this::read, index);
        var result = supply.resolve(input.fixture().context(), input.fixture().candidate());
        assertTrue(result.ready(), result.snapshot().toString());
        assertEquals("PREBUILT_LOCAL", result.snapshot().path("modelOrigin").asText());
        assertEquals(old.model.path("modelId"), requireNonNull(result.model()).path("modelId"));
        Path file = old.root.resolve("ENST000001.4.model.json");
        Files.writeString(file, Files.readString(file).replace("ATGAAATAA", "ATGAGATAA"));
        var corrupted = supply.resolve(input.fixture().context(), input.fixture().candidate());
        assertFalse(corrupted.ready());
        assertEquals("TRANSCRIPT_MATERIAL_FILE_IDENTITY_MISMATCH", corrupted.snapshot().path("reasonCode").asText());
        assertEquals("PREBUILT_LOCAL", corrupted.snapshot().path("modelOrigin").asText());
    }
    @Test void changedIndexOrDataCannotReuseCachedModel() throws Exception {
        var supply = supply(setup()); assertTrue(supply.resolve(input.fixture().context(), input.fixture().candidate()).ready());
        Files.writeString(root.resolve("annotations.jsonl"), "broken");
        assertEquals("GENCODE_RESOURCE_CHANGED", supply.resolve(input.fixture().context(), input.fixture().candidate()).snapshot().path("reasonCode").asText());
    }
    @Test void resourceRowHashProtectsSameSizedMutationEvenWithPreservedTimestamp() throws Exception {
        var supply = supply(setup()); var path = root.resolve("annotations.jsonl"); var time = Files.getLastModifiedTime(path);
        byte[] data = Files.readAllBytes(path); data[10] = (byte) (data[10] == 'x' ? 'y' : 'x'); Files.write(path, data); Files.setLastModifiedTime(path, time);
        assertEquals("GENCODE_ANNOTATION_HASH_MISMATCH", supply.resolve(input.fixture().context(), input.fixture().candidate()).snapshot().path("reasonCode").asText());
    }
    @Test void referenceChangeInvalidatesBothCachesAndBadTranslationIsRejected() throws Exception {
        var supply = supply(setup()); assertTrue(supply.resolve(input.fixture().context(), input.fixture().candidate()).ready());
        version = "test-v2"; reference = "ATGAAACAA";
        assertEquals("CODING_TRANSCRIPT_MODEL_SEQUENCE_INVALID", supply.resolve(input.fixture().context(), input.fixture().candidate()).snapshot().path("reasonCode").asText());
    }
    @Test void malformedIndexBoundsOrManifestCannotBeLoaded() throws Exception {
        setup(); manifest.withObject("/data").put("file", "../annotations.jsonl");
        assertThrows(IllegalArgumentException.class, this::load);
        manifest.withObject("/data").put("file", "annotations.jsonl"); manifest.withObject("/index").put("sha256", "0".repeat(64));
        assertThrows(IllegalArgumentException.class, this::load);
    }
    @Test void cancellationDoesNotReadOrClearInterrupt() throws Exception {
        var supply = supply(setup()); Thread.currentThread().interrupt();
        try { assertEquals("TRANSCRIPT_MATERIAL_INTERRUPTED", supply.resolve(input.fixture().context(), input.fixture().candidate()).snapshot().path("reasonCode").asText());
            assertTrue(Thread.currentThread().isInterrupted()); assertEquals(0, bases.get()); }
        finally { Thread.interrupted(); }
    }
}
