package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed toy CDS MEPGFK*; TAA -> CAA and GCTGGTTGA UTR yield MEPGFKQAG*. */
class GeneralPm4StopLossTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AtomicInteger bases = new AtomicInteger();
    Pm4TestFixtures data;
    String spliced, referenceVersion = "TEST_ONLY_REFERENCE_1";
    ObjectNode annotation;

    void setup(int strand, String tail, String hgvsp) throws Exception {
        data = new Pm4TestFixtures(mapper, strand);
        spliced = Pm4TestFixtures.CDS + tail;
        var exons = data.model.putArray("exons");
        int[] lengths = {20, 3, spliced.length() - 23};
        if (spliced.length() < 24) lengths = new int[]{spliced.length()};
        int cds = 1;
        for (int i = 0; i < lengths.length; i++) {
            int start = strand == 1 ? 100 + i * 100 : 500 - i * 150;
            exons.addObject().put("ordinal", i + 1).put("genomicStart", start).put("genomicEnd", start + lengths[i] - 1)
                    .put("cdsStart", cds).put("cdsEnd", cds + lengths[i] - 1);
            cds += lengths[i];
        }
        data.model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(spliced))
                .put("cdsSha256", CodingTranscriptMaterialSupply.sha(Pm4TestFixtures.CDS));
        int genomic = strand == 1 ? 118 : data.model.at("/exons/0/genomicEnd").asInt() - 18;
        data.variant(genomic, strand == 1 ? "T" : "A", strand == 1 ? "C" : "G", "19T>C", hgvsp);
        annotation = data.annotation("19T>C", hgvsp, "stop_lost").put("exon", "1/" + lengths.length);
        applyAnnotation();
    }
    void applyAnnotation() throws Exception {
        data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
    }
    CodingTranscriptMaterialSupply.ReferenceSpan read(String contig, int start, int length) {
        assertEquals("11", contig); bases.addAndGet(length);
        for (var exon : data.model.path("exons")) {
            int lo = exon.path("genomicStart").asInt(), hi = exon.path("genomicEnd").asInt();
            if (start >= lo && (long) start + length - 1 <= hi) {
                String part = spliced.substring(exon.path("cdsStart").asInt() - 1, exon.path("cdsEnd").asInt());
                if (data.model.path("strand").asInt() == -1) part = TruncatingSequence.reverseComplement(part);
                return new CodingTranscriptMaterialSupply.ReferenceSpan(part.substring(start - lo, start - lo + length),
                        mapper.createObjectNode().put("version", referenceVersion)
                                .put("expectedSha256", data.context.at("/variant/normalization/referenceSequenceSha256").asText()));
            }
        }
        throw new AssertionError("Read outside the fixed exons: " + start + "/" + length);
    }
    GeneralPm4Runtime runtime(boolean spec) throws Exception {
        return GeneralPm4TestSupport.create(mapper, root.resolve("runtime"), data.model, this::read, false, false, spec);
    }
    CodingTranscriptMaterialSupply supply(boolean derived) throws Exception {
        return supply(derived, false);
    }
    CodingTranscriptMaterialSupply supply(boolean derived, boolean complexAlleles) throws Exception {
        String tx = data.model.path("transcript").asText();
        if (!derived) {
            byte[] bytes = mapper.writeValueAsBytes(data.model); Files.write(root.resolve(tx + ".model.json"), bytes);
            var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
            manifest.putArray("entries").addObject().put("transcript", tx).put("file", tx + ".model.json")
                    .put("fileBytes", bytes.length).put("fileSha256", CodingTranscriptMaterialSupply.sha(bytes))
                    .put("canonicalSha256", data.canonicalSha256(data.model))
                    .put("modelId", data.model.path("modelId").asText()).put("modelVersion", data.model.path("modelVersion").asText());
            bytes = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), bytes);
            return new CodingTranscriptMaterialSupply(mapper, root, CodingTranscriptMaterialSupply.sha(bytes), this::read, null, complexAlleles);
        }
        var row = data.model.deepCopy();
        row.remove(List.of("sourceRecords", "cdsSequence", "schemaVersion", "modelId", "modelVersion", "sequenceVerification"));
        row.put("transcriptLength", spliced.length()).put("rawGtfSha256", "b".repeat(64));
        byte[] rows = (row + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] index = (tx + "\t0\t" + rows.length + "\t" + CodingTranscriptMaterialSupply.sha(rows) + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(root.resolve("annotations.jsonl"), rows); Files.write(root.resolve("index.tsv"), index);
        var manifest = mapper.createObjectNode().put("schemaVersion", "gencode-transcript-index-v1").put("methodVersion", "1.0.0")
                .put("assembly", "GRCh38").put("release", 38).put("activation", "TECHNICAL_MATERIAL_ONLY").put("independentRnaVerification", false);
        manifest.putObject("source").put("sha256", "a".repeat(64))
                .put("url", "https://ftp.ebi.ac.uk/pub/databases/gencode/Gencode_human/release_38/gencode.v38.annotation.gtf.gz");
        manifest.putObject("counts").put("indexed", 1);
        manifest.putObject("data").put("file", "annotations.jsonl").put("bytes", rows.length).put("sha256", CodingTranscriptMaterialSupply.sha(rows));
        manifest.putObject("index").put("file", "index.tsv").put("bytes", index.length).put("sha256", CodingTranscriptMaterialSupply.sha(index));
        byte[] bytes = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), bytes);
        return new CodingTranscriptMaterialSupply(mapper, null, "", this::read, new GencodeTranscriptIndex(mapper, root, CodingTranscriptMaterialSupply.sha(bytes)), complexAlleles);
    }
    ObjectNode evaluate(GeneralPm4Runtime runtime) { return runtime.evaluate(data.context, data.candidate, mapper.createObjectNode()); }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void sameBoundedStopLossIsEvaluableOnBothStrandsWithoutProteinRegionApproval(int strand) throws Exception {
        setup(strand, "GCTGGTTGACCC", "Ter7GlnextTer3");
        var context = data.context.deepCopy(); var candidate = mapper.valueToTree(data.candidate);
        var runtime = runtime(false);
        // This branch does not consume a region annotation or turn its absence into a negative fact.
        Files.writeString(root.resolve("runtime/combined.json"), "unavailable");
        var result = evaluate(runtime);
        String artifacts = System.getProperty("varvis.pm4.stoploss.artifacts");
        if (artifacts != null) {
            Path output = Path.of(artifacts); Files.createDirectories(output);
            Files.write(output.resolve("strand-" + strand + "-result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
            Files.write(output.resolve("strand-" + strand + "-model.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data.model));
        }
        assertEquals("MET", result.path("status").asText(), result.toPrettyString());
        assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText());
        var facts = result.at("/sequenceMaterial/alleleAssociation");
        assertEquals("STOP_LOSS_SNV", facts.path("branch").asText());
        assertEquals(3, facts.path("netAminoAcidChange").asInt());
        assertEquals(28, facts.path("alteredStopCdsPosition").asInt());
        assertEquals(CodingTranscriptMaterialSupply.sha("MEPGFKQAG*"), facts.path("alteredProteinSha256").asText());
        assertFalse(result.path("publicSummary").toString().contains("GCTGGTTGA"));
        assertEquals("SEQUENCE_DERIVED_STOP_LOSS", result.at("/publicSummary/qualificationBasis").asText());
        assertEquals("NOT_QUERIED_STOP_LOSS_BRANCH", result.at("/publicSummary/repeatObservation").asText());
        assertEquals(context, data.context); assertEquals(candidate, mapper.valueToTree(data.candidate));
        int before = bases.get(); var repeat = evaluate(runtime);
        assertEquals("MET", repeat.path("status").asText());
        assertEquals(2, bases.get() - before, "Only fresh receipt and allele REF checks on a sequence-cache hit");
        assertTrue(repeat.at("/sequenceMaterial/modelCacheHit").asBoolean());
    }
    @Test void specializationStillBlocksBeforeReferenceAcquisition() throws Exception {
        setup(1, "GCTGGTTGACCC", "Ter7GlnextTer3");
        assertEquals("PM4_KNOWN_CSPEC_REQUIRES_CLAUSE_SELECTION", evaluate(runtime(true)).path("reasonCode").asText());
        assertEquals(0, bases.get());
    }
}
