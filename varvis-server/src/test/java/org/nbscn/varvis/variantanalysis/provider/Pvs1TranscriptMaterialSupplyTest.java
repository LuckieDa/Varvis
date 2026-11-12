package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic sequence proofs. Material readiness is deliberately separate from clinical and allele-effect judgment. */
class Pvs1TranscriptMaterialSupplyTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final TruncatingNmdPvs1RuntimeTest fixture = new TruncatingNmdPvs1RuntimeTest();
    final AtomicInteger reads = new AtomicInteger();
    TruncatingNmdPvs1RuntimeTest.Inputs data;
    String referenceVersion = "synthetic-1";
    boolean invalidReference;
    ObjectNode referenceModel;

    CodingTranscriptMaterialSupply setup(String kind, int strand) throws Exception {
        data = "STOP".equals(kind) ? fixture.nonsense(1, strand, 31) : fixture.frameshift("INSERTION".equals(kind), strand, 120);
        referenceModel = data.model.deepCopy();
        String cds = data.model.path("cdsSequence").asText();
        data.model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(cds))
                .put("cdsSha256", CodingTranscriptMaterialSupply.sha(cds));
        return write();
    }
    CodingTranscriptMaterialSupply write() throws Exception {
        String tx = data.model.path("transcript").asText();
        byte[] raw = mapper.writeValueAsBytes(data.model); Files.write(root.resolve(tx + ".model.json"), raw);
        ObjectNode manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        manifest.putArray("entries").addObject().put("transcript", tx).put("file", tx + ".model.json")
                .put("fileBytes", raw.length).put("fileSha256", CodingTranscriptMaterialSupply.sha(raw))
                .put("canonicalSha256", adapter.canonicalSha256(data.model)).put("modelId", data.model.path("modelId").asText())
                .put("modelVersion", data.model.path("modelVersion").asText());
        byte[] bytes = mapper.writeValueAsBytes(manifest); Files.write(root.resolve("manifest.json"), bytes);
        return new CodingTranscriptMaterialSupply(mapper, root, CodingTranscriptMaterialSupply.sha(bytes), this::readReference);
    }
    CodingTranscriptMaterialSupply.ReferenceSpan readReference(String contig, int start, int length) {
            reads.incrementAndGet(); var out = new StringBuilder();
            for (int g = start; g < start + length; g++) {
                int c = CodingTranscriptMapping.cdsPosition(referenceModel, g);
                if (c < 1) throw new AssertionError("Synthetic read outside fixture CDS");
                String base = referenceModel.path("cdsSequence").asText().substring(c - 1, c);
                out.append(CodingTranscriptMapping.orientAllele(referenceModel, base));
            }
            if (invalidReference) out.setCharAt(0, out.charAt(0) == 'A' ? 'C' : 'A');
            return new CodingTranscriptMaterialSupply.ReferenceSpan(out.toString(), mapper.createObjectNode()
                    .put("version", referenceVersion).put("expectedSha256", data.context.at("/variant/normalization/referenceSequenceSha256").asText()));
    }

    @Test void boundSupplyAcceptsANativeStopSpanningTheLastJunction() throws Exception {
        setup("STOP", 1);
        var exons = data.model.withArray("exons");
        var penultimate = (ObjectNode) exons.get(2);
        var terminal = (ObjectNode) exons.get(3);
        penultimate.put("cdsEnd", 598).put("genomicEnd", penultimate.path("genomicStart").asInt() + 297);
        terminal.put("cdsStart", 599).put("genomicEnd", terminal.path("genomicStart").asInt() + 1);
        data.model.put("lastJunctionCdsPosition", 598);
        referenceModel = data.model.deepCopy();
        var supply = new Pvs1TranscriptMaterialSupply(mapper, write());
        var material = supply.resolveBound(data.context, data.candidate, data.model);
        assertTrue(material.ready(), () -> material.audit().toPrettyString());
        var result = evaluate(requireNonNull(material.model()));
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result::toPrettyString);
        assertEquals(565, result.at("/resolvedInput/distanceToLastJunction").asInt());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void gencodeIndexSuppliesNmdStructureWithoutPrebuiltPatientModel(int strand) throws Exception {
        setup("STOP", strand);
        String tx = "ENST099999001.1";
        data.context.withObject("transcript").put("accession", "ENST099999001").put("version", 1);
        ObjectNode annotation = mapper.valueToTree(data.candidate.getTranscriptAnnotations().get(0));
        annotation.put("transcriptAccession", tx);
        data.candidate.applyTranscriptAnnotations(List.of(mapper.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .treeToValue(annotation, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        var indexFixture = new GencodeTranscriptIndexTest(); indexFixture.root = Files.createDirectory(root.resolve("gencode"));
        indexFixture.setup();
        indexFixture.row = data.model.deepCopy();
        indexFixture.row.remove(List.of("modelId", "modelVersion", "schemaVersion", "lastJunctionCdsPosition", "cdsSequence", "sequenceVerification", "sourceRecords"));
        indexFixture.row.put("transcript", tx).put("transcriptLength", data.model.path("cdsLength").asInt()).put("rawGtfSha256", "b".repeat(64));
        byte[] row = (indexFixture.row.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(indexFixture.root.resolve("annotations.jsonl"), row);
        byte[] index = (tx + "\t0\t" + row.length + "\t" + CodingTranscriptMaterialSupply.sha(row) + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        Files.write(indexFixture.root.resolve("index.tsv"), index);
        indexFixture.manifest.putObject("data").put("file", "annotations.jsonl").put("bytes", row.length).put("sha256", CodingTranscriptMaterialSupply.sha(row));
        indexFixture.manifest.putObject("index").put("file", "index.tsv").put("bytes", index.length).put("sha256", CodingTranscriptMaterialSupply.sha(index));
        var supply = new Pvs1TranscriptMaterialSupply(mapper, new CodingTranscriptMaterialSupply(mapper, null, "", this::readReference, indexFixture.load()));
        var result = supply.resolve(data.context, data.candidate);
        assertTrue(result.ready(), result.audit().toString());
        assertEquals("GENCODE_GTF_PLUS_REFERENCE", result.audit().at("/structure/modelOrigin").asText());
        assertEquals(data.model.path("cdsSequence"), requireNonNull(result.model()).path("cdsSequence"));
        assertEquals(450, requireNonNull(result.model()).path("lastJunctionCdsPosition").asInt());
        assertFalse(result.audit().path("clinicalAcceptance").asBoolean(true));
        assertTrue(supply.resolve(data.context, data.candidate).audit().at("/structure/modelCacheHit").asBoolean());
    }
    ObjectNode evaluate(ObjectNode model) {
        data.policy.putObject("transcriptModelBinding").put("modelId", model.path("modelId").asText())
                .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", adapter.canonicalSha256(model));
        data.selection.put("policySha256", adapter.canonicalSha256(data.policy)); data.selection.remove("selectionId");
        data.selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(data.selection));
        return new TruncatingNmdPvs1Runtime(mapper, fixture.resourceProfile(), model).evaluate(data.context, data.candidate, data.policy, data.selection);
    }

    @ParameterizedTest @CsvSource({"STOP,1", "STOP,-1", "INSERTION,1", "INSERTION,-1", "DELETION,1", "DELETION,-1"})
    void suppliesBothStrandsAndReusesUnchangedNmdCore(String kind, int strand) throws Exception {
        var common = setup(kind, strand);
        assertFalse(common.resolve(data.context, data.candidate).ready(), "The old missense consumer must not expand silently");
        int before = reads.get();
        var supply = new Pvs1TranscriptMaterialSupply(mapper, common);
        var material = supply.resolve(data.context, data.candidate);
        assertTrue(material.ready(), material.audit().toString()); assertTrue(reads.get() > before);
        assertFalse(material.audit().path("clinicalAcceptance").asBoolean(true));
        assertFalse(material.audit().path("alleleEffectVerified").asBoolean(true));
        var result = evaluate(material.model());
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toString());
        assertEquals("VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
        assertEquals("STOP".equals(kind) ? 31 : "INSERTION".equals(kind) ? 64 : 61, result.at("/resolvedInput/ptcStart").asInt());
        assertEquals("STOP".equals(kind) ? 417 : "INSERTION".equals(kind) ? 55 : 56, result.at("/resolvedInput/distanceToLastJunction").asInt());
        requireNonNull(material.model()).removeAll(); material.audit().removeAll();
        var again = supply.resolve(data.context, data.candidate);
        assertEquals(data.model, again.model()); assertTrue(again.audit().at("/structure/modelCacheHit").asBoolean());
        referenceVersion = "synthetic-2";
        assertFalse(supply.resolve(data.context, data.candidate).audit().at("/structure/modelCacheHit").asBoolean());
    }

    @Test void projectsGenericStructureWithNewIdentityWithoutGrantingMechanism() throws Exception {
        setup("STOP", 1); String oldId = data.model.path("modelId").asText();
        data.model.remove("lastJunctionCdsPosition"); data.model.put("schemaVersion", "native-coding-transcript-model-v1")
                .put("usageScope", "PREDICTION_SEQUENCE_CHECK_ONLY");
        String oldSha = adapter.canonicalSha256(data.model);
        var material = new Pvs1TranscriptMaterialSupply(mapper, write()).resolve(data.context, data.candidate);
        assertTrue(material.ready(), material.audit().toString());
        assertEquals(450, requireNonNull(material.model()).path("lastJunctionCdsPosition").asInt());
        assertEquals(oldSha, requireNonNull(material.model()).at("/technicalModelBinding/canonicalSha256").asText());
        assertEquals(oldId + "_PVS1_NMD_STRUCTURE", requireNonNull(material.model()).path("modelId").asText());
        assertNotEquals(oldSha, adapter.canonicalSha256(material.model()));
        data.policy.withObject("mechanism").put("status", "UNKNOWN");
        assertEquals("PVS1_LOF_MECHANISM_NOT_ESTABLISHED", evaluate(material.model()).at("/evaluationResult/reasonCode").asText());
    }

    @Test void checkedStructureDoesNotHideWrongProteinFacts() throws Exception {
        var supply = new Pvs1TranscriptMaterialSupply(mapper, setup("STOP", 1));
        ObjectNode annotation = mapper.valueToTree(data.candidate.getTranscriptAnnotations().get(0));
        annotation.retain("geneSymbol", "transcriptAccession", "hgvsCoding", "hgvsProtein", "consequence", "exon", "proteinPosition",
                "aminoAcids", "codons", "source", "status", "version", "timestamp", "maneSelect");
        annotation.put("codons", "CGA/TGG");
        data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        var material = supply.resolve(data.context, data.candidate); assertTrue(material.ready());
        assertEquals("PVS1_PROTEIN_CODON_MISMATCH", evaluate(material.model()).at("/evaluation/evaluationResult/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings={"MODEL", "REFERENCE", "CANDIDATE", "ANNOTATION", "JUNCTION"})
    void inconsistentMaterialCannotReachCore(String failure) throws Exception {
        var common = setup("STOP", 1);
        switch (failure) {
            case "MODEL" -> Files.writeString(root.resolve(data.model.path("transcript").asText() + ".model.json"), "{}");
            case "REFERENCE" -> invalidReference = true;
            case "CANDIDATE" -> data.context.withObject("/variant/evidenceQueryAllele").put("alternate", "A");
            case "ANNOTATION" -> data.candidate.applyTranscriptAnnotations(List.of());
            case "JUNCTION" -> { data.model.put("lastJunctionCdsPosition", 449); common = write(); }
        }
        var result = new Pvs1TranscriptMaterialSupply(mapper, common).resolve(data.context, data.candidate);
        assertFalse(result.ready(), result.audit().toString());
        assertFalse(result.audit().path("reasonCode").asText().isBlank());
    }
}
