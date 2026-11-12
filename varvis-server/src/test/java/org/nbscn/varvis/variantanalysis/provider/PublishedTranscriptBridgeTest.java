package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Synthetic runtime controls plus opt-in actual prepared model-source validation, never clinical adoption. */
class PublishedTranscriptBridgeTest {
    @TempDir Path root;
    final GeneralPm1RuntimeTest fixture = new GeneralPm1RuntimeTest();

    void setup() throws Exception {
        fixture.root = root; fixture.setup();
        var model = fixture.sequence.model;
        model.put("protein", "ENSP000001.3");
        var tx = fixture.mapper.createObjectNode().put("geneSymbol", "SYNTH1").put("transcriptAccession", "ENST000001")
                .put("transcriptId", "ENST000001").put("hgvsCoding", "ENST000001.4:c.5A>G")
                .put("hgvsProtein", "ENSP000001.3:p.Lys2Arg").put("consequence", "missense_variant").put("exon", "1/1")
                .put("proteinPosition", "2").put("aminoAcids", "K/R").put("codons", "AAA/AGA")
                .put("source", "VEP_SYNTHETIC").put("status", "COMPLETED").put("version", "116.0").put("timestamp", "2026-09-01T00:00:00Z");
        fixture.sequence.input.fixture().candidate().applyTranscriptAnnotations(java.util.List.of(
                fixture.mapper.treeToValue(tx, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        model.putObject("transcriptBridge").put("status", "FULL_TRANSCRIPT_EXONS_AND_CDS_AGREE")
                .put("refseqTranscript", "NM_000001.3").put("refseqProtein", "NP_000001.1")
                .put("ensemblTranscript", model.path("transcript").asText()).put("ensemblProtein", model.path("protein").asText());
        var p = (ObjectNode) model.path("sequenceVerification");
        p.put("queriedBases", 9).put("fastaIndexSha256", "a".repeat(64)).put("independentRnaVerification", true)
                .put("independentRnaMethod", "PINNED_GENCODE_REFERENCE_AND_REFSEQ_FULL_RNA_V1");
        var sources = model.putArray("sourceRecords");
        sources.addObject().put("sourceId", "GENCODE:ENST000001.4").put("sourceKind", "GENCODE_GTF_LOCAL_SNAPSHOT")
                .put("version", "test").put("sha256", "b".repeat(64)).put("annotationRowSha256", "c".repeat(64))
                .put("rawGtfSha256", "d".repeat(64)).put("indexManifestSha256", "e".repeat(64)).put("url", "https://example.org/gtf");
        for (String v : new String[]{"2", "3"}) sources.addObject().put("sourceId", "RefSeq:NM_000001." + v)
                .put("version", "NM_000001." + v).put("sourceKind", "NCBI_VERSIONED_REFSEQ_GENBANK")
                .put("sha256", v.repeat(64)).put("url", "https://example.org/refseq");
        String line = "GeneID:1\tENSG000001.1\t" + model.path("hgncId").asText() + "\t" + model.path("geneSymbol").asText()
                + "\tsynthetic\tNM_000001.3\tNP_000001.1\tENST000001.4\tENSP000001.3\tMANE Select\tNC_000001.11\t96\t104\t+\n";
        String rowSha = CodingTranscriptMaterialSupply.sha(line.getBytes(StandardCharsets.UTF_8));
        sources.addObject().put("sourceId", "MANE:test:ENST000001.4").put("version", "test").put("sourceKind", "NCBI_MANE_SUMMARY_ROW")
                .put("sha256", "f".repeat(64)).put("rowRawSha256", rowSha).put("rowRawUtf8", line).put("url", "https://example.org/mane");
        var eq = p.putObject("rnaEquivalence").put("schemaVersion", "gencode-refseq-full-equivalence-v1")
                .put("sourceModelRawSha256", "0".repeat(64)).put("refseqRawSha256", "3".repeat(64))
                .put("refseqTranscript", "NM_000001.3").put("refseqProtein", "NP_000001.1")
                .put("hgncId", model.path("hgncId").asText()).put("cdsSha256", p.path("cdsSha256").asText())
                .put("fullSplicedTranscriptSha256", p.path("fullSplicedTranscriptSha256").asText())
                .put("maneRawSha256", "f".repeat(64)).put("maneRowRawSha256", rowSha).put("scientificPolicyAdoption", false);
        eq.putArray("fullExonLengths").add(9); eq.putArray("codingExonIntervals").addArray().add(1).add(9);
        String[] fields = {"#NCBI_GeneID", "Ensembl_Gene", "HGNC_ID", "symbol", "name", "RefSeq_nuc", "RefSeq_prot", "Ensembl_nuc",
                "Ensembl_prot", "MANE_status", "GRCh38_chr", "chr_start", "chr_end", "chr_strand"};
        var row = eq.putObject("maneRow"); String[] values = line.strip().split("\t", -1);
        for (int i = 0; i < fields.length; i++) row.put(fields[i], values[i]);
        var t = model.putArray("codingTranscriptTransfers").addObject().put("schemaVersion", "refseq-coding-missense-transfer-v1")
                .put("status", "CDS_PROTEIN_AND_CODING_EXONS_IDENTICAL").put("allowedPurpose", "PURE_CODING_MISSENSE_SNV")
                .put("sourceTranscript", "NM_000001.2").put("targetTranscript", "NM_000001.3").put("refseqProtein", "NP_000001.1")
                .put("ensemblTranscript", "ENST000001.4").put("ensemblProtein", model.path("protein").asText())
                .put("hgncId", model.path("hgncId").asText()).put("geneSymbol", model.path("geneSymbol").asText())
                .put("sourceRawSha256", "2".repeat(64)).put("targetRawSha256", "3".repeat(64))
                .put("cdsSha256", p.path("cdsSha256").asText()).put("sourceRnaSha256", "4".repeat(64))
                .put("targetRnaSha256", p.path("fullSplicedTranscriptSha256").asText()).put("fullRnaEquivalent", false)
                .put("fullTranscriptEquivalenceClaimed", false).put("sourceFullExonCoverage", false).put("scientificPolicyAdoption", false);
        t.set("codingExonIntervals", eq.path("codingExonIntervals").deepCopy());
        declared("NM_000001.2");
    }

    void declared(String tx) {
        ((ObjectNode)fixture.doc.at("/specifications/0/original/ld/RuleSet/0/entContent/genes/0")).put("preferredTranscript", tx);
        ((ObjectNode)fixture.doc.at("/records/0/scope")).put("transcript", tx);
        new PublishedCriterionKnowledgeTest().refresh(fixture.doc);
    }
    PublishedCriterionKnowledge knowledge() {
        return new PublishedCriterionKnowledge(fixture.mapper, fixture.doc, "a".repeat(64));
    }
    ObjectNode run() throws Exception {
        var supplied = fixture.sequence.resolve(fixture.sequence.writeAndLoad()); assertTrue(supplied.ready(), supplied.snapshot().toPrettyString());
        return knowledge().selectForVerifiedMissense(GeneralPm1Runtime.CRITERION, fixture.sequence.input.fixture().context(), supplied);
    }

    @Test void onlyOpaqueMissenseMaterialEnablesCodingTransferWhileFullBridgeStaysNewVersion() throws Exception {
        setup(); var context = fixture.sequence.input.fixture().context(); var model = fixture.sequence.model;
        assertEquals("UNAVAILABLE", knowledge().select(GeneralPm1Runtime.CRITERION, context, model).path("status").asText());
        var result = run(); assertEquals("READY", result.path("status").asText(), result.toPrettyString());
        assertEquals("NM_000001.2", result.path("declaredTranscript").asText());
        assertEquals("NM_000001.3", result.at("/transcriptBridge/refseqTranscript").asText());
        assertEquals("NM_000001.2", result.at("/codingTranscriptTransfer/sourceTranscript").asText());
        assertFalse(result.at("/codingTranscriptTransfer/fullTranscriptEquivalenceClaimed").asBoolean(true));
        declared("NM_000001.3"); assertEquals("READY", knowledge().select(GeneralPm1Runtime.CRITERION, context, model).path("status").asText());
    }
    @Test void actualPm1RuntimeConsumesTransferAfterOriginalMissenseSupply() throws Exception {
        setup(); fixture.supply = fixture.sequence.writeAndLoad();
        var result = fixture.run(fixture.engine()); assertEquals("MET", result.path("status").asText(), result.toPrettyString());
        assertTrue(result.at("/knowledgeSelection/codingTranscriptTransfer").isObject());
    }
    @Test void structureMaterialAndChangedContextCannotClaimMissense() throws Exception {
        setup(); var supply = fixture.sequence.writeAndLoad(); var f = fixture.sequence.input.fixture();
        var structure = supply.resolveStructure(f.context(), f.candidate()); assertTrue(structure.ready());
        assertEquals("PUBLISHED_KNOWLEDGE_VERIFIED_PURE_MISSENSE_REQUIRED", knowledge().selectForVerifiedMissense(GeneralPm1Runtime.CRITERION, f.context(), structure).path("reasonCode").asText());
        var missense = supply.resolve(f.context(), f.candidate()); assertTrue(missense.ready());
        ((ObjectNode)f.context().at("/variant/assemblyLineage")).put("mappingStatus", "CONVERTED");
        assertEquals("PUBLISHED_KNOWLEDGE_VERIFIED_PURE_MISSENSE_REQUIRED", knowledge().selectForVerifiedMissense(GeneralPm1Runtime.CRITERION, f.context(), missense).path("reasonCode").asText());
        ((ObjectNode)f.context().at("/variant/assemblyLineage")).put("mappingStatus", "NATIVE");
        ((ObjectNode)f.context().at("/variant/evidenceQueryAllele")).put("alternate", "T");
        assertEquals("PUBLISHED_KNOWLEDGE_VERIFIED_PURE_MISSENSE_REQUIRED", knowledge().selectForVerifiedMissense(GeneralPm1Runtime.CRITERION, f.context(), missense).path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"GENE", "PROTEIN", "CDS", "EXON", "SOURCE_VERSION", "TARGET_VERSION", "SOURCE_PIN", "TARGET_PIN", "SOURCE_RNA", "TARGET_RNA", "RNA_CLAIM", "SCOPE", "POLICY", "DUPLICATE"})
    void invalidTransferNeverBorrowsTheOldPolicy(String which) throws Exception {
        setup(); var m = fixture.sequence.model; var t = (ObjectNode)m.at("/codingTranscriptTransfers/0");
        switch (which) {
            case "GENE" -> t.put("hgncId", "HGNC:999"); case "PROTEIN" -> t.put("refseqProtein", "NP_000001.2");
            case "CDS" -> t.put("cdsSha256", "0".repeat(64)); case "EXON" -> t.putArray("codingExonIntervals").addArray().add(1).add(8);
            case "SOURCE_VERSION" -> t.put("sourceTranscript", "NM_000001.1"); case "TARGET_VERSION" -> t.put("targetTranscript", "NM_000001.4");
            case "SOURCE_PIN" -> t.put("sourceRawSha256", "0".repeat(64)); case "TARGET_PIN" -> t.put("targetRawSha256", "0".repeat(64));
            case "SOURCE_RNA" -> t.put("sourceRnaSha256", ""); case "TARGET_RNA" -> t.put("targetRnaSha256", "0".repeat(64));
            case "RNA_CLAIM" -> t.put("fullTranscriptEquivalenceClaimed", true); case "SCOPE" -> t.put("allowedPurpose", "SPLICING");
            case "POLICY" -> t.put("scientificPolicyAdoption", true); case "DUPLICATE" -> m.withArray("codingTranscriptTransfers").add(t.deepCopy());
        }
        assertEquals("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED", run().path("reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings={"GENCODE_ROW", "RNA_RAW", "RNA_ID", "FULL_RNA", "FULL_EXON", "CODING_EXON", "MANE_RAW", "MANE_ROW", "MANE_IDENTITY", "PROOF"})
    void incompleteIndependentRnaOrManeProofNeverCreatesFullBridge(String which) throws Exception {
        setup(); declared("NM_000001.3"); var m = fixture.sequence.model; var p = (ObjectNode)m.path("sequenceVerification"); var eq = (ObjectNode)p.path("rnaEquivalence");
        switch (which) {
            case "GENCODE_ROW" -> ((ObjectNode)m.at("/sourceRecords/0")).put("annotationRowSha256", "");
            case "RNA_RAW" -> eq.put("refseqRawSha256", "0".repeat(64)); case "RNA_ID" -> eq.put("refseqTranscript", "NM_000001.2");
            case "FULL_RNA" -> eq.put("fullSplicedTranscriptSha256", "0".repeat(64)); case "FULL_EXON" -> eq.putArray("fullExonLengths").add(8);
            case "CODING_EXON" -> eq.putArray("codingExonIntervals").addArray().add(1).add(8);
            case "MANE_RAW" -> eq.put("maneRawSha256", "0".repeat(64)); case "MANE_ROW" -> ((ObjectNode)m.at("/sourceRecords/3")).put("rowRawUtf8", "changed");
            case "MANE_IDENTITY" -> ((ObjectNode)eq.path("maneRow")).put("RefSeq_nuc", "NM_000001.2"); case "PROOF" -> p.put("independentRnaVerification", false);
        }
        assertEquals("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED", knowledge().select(GeneralPm1Runtime.CRITERION, fixture.sequence.input.fixture().context(), m).path("reasonCode").asText());
    }
    @Test void realPreparedFiveModelsPassFullBridgeWithoutClaimingClinicalEvidence() throws Exception {
        String location = System.getProperty("varvis.published.models", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(location.isBlank(), "Only run with explicitly supplied actual prepared models");
        setup(); var manifest = fixture.mapper.readTree(Files.readAllBytes(Path.of(location, "manifest.json")));
        assertEquals(5, manifest.path("entries").size());
        for (JsonNode entry : manifest.path("entries")) {
            byte[] raw = Files.readAllBytes(Path.of(location, entry.path("file").asText()));
            assertEquals(entry.path("fileSha256").asText(), CodingTranscriptMaterialSupply.sha(raw));
            var model = (ObjectNode)fixture.mapper.readTree(raw); var c = fixture.sequence.input.fixture().context().deepCopy();
            assertEquals(entry.path("canonicalSha256").asText(), fixture.adapter.canonicalSha256(model));
            ((ObjectNode)c.path("gene")).put("hgncId",model.path("hgncId").asText()).put("symbol",model.path("geneSymbol").asText());
            String[] tx = model.path("transcript").asText().split("\\."); ((ObjectNode)c.path("transcript")).put("accession",tx[0]).put("version",Integer.parseInt(tx[1]));
            ((ObjectNode)c.at("/variant/evidenceQueryAllele")).put("contig",model.path("contig").asText());
            ((ObjectNode)fixture.doc.at("/specifications/0/original/ld/Gene/0/entContent/HGNC")).put("hgnc_id",model.path("hgncId").asText());
            ((ObjectNode)fixture.doc.at("/records/0/scope")).put("hgncId",model.path("hgncId").asText());
            declared(model.at("/transcriptBridge/refseqTranscript").asText());
            var selected=knowledge().select(GeneralPm1Runtime.CRITERION,c,model);
            assertEquals("READY",selected.path("status").asText(),model.path("transcript")+selected.toString());
            assertFalse(selected.path("clinicalValidationCompleted").asBoolean(true));
        }
    }

    @ParameterizedTest @ValueSource(strings={"CHROMOSOME", "CHROMOSOME_VERSION", "UNMAPPED_CHROMOSOME", "STRAND", "START", "END", "INVALID_MODEL_STRAND"})
    void selfConsistentManeRowMustStillMatchModelGenomicIdentity(String which) throws Exception {
        setup(); declared("NM_000001.3"); var model = fixture.sequence.model;
        var eq = (ObjectNode)model.at("/sequenceVerification/rnaEquivalence"); var row = (ObjectNode)eq.path("maneRow");
        switch (which) {
            case "CHROMOSOME" -> row.put("GRCh38_chr", "NC_000002.12");
            case "CHROMOSOME_VERSION" -> row.put("GRCh38_chr", "NC_000001.10");
            case "UNMAPPED_CHROMOSOME" -> row.put("GRCh38_chr", "NT_113891.3");
            case "STRAND" -> row.put("chr_strand", "-"); case "START" -> row.put("chr_start", "97");
            case "END" -> row.put("chr_end", "105"); case "INVALID_MODEL_STRAND" -> model.put("strand", 0);
        }
        String[] fields = {"#NCBI_GeneID", "Ensembl_Gene", "HGNC_ID", "symbol", "name", "RefSeq_nuc", "RefSeq_prot", "Ensembl_nuc",
                "Ensembl_prot", "MANE_status", "GRCh38_chr", "chr_start", "chr_end", "chr_strand"};
        String line = java.util.Arrays.stream(fields).map(k -> row.path(k).asText()).collect(java.util.stream.Collectors.joining("\t")) + "\n";
        String sha = CodingTranscriptMaterialSupply.sha(line.getBytes(StandardCharsets.UTF_8));
        eq.put("maneRowRawSha256", sha); ((ObjectNode)model.at("/sourceRecords/3")).put("rowRawUtf8", line).put("rowRawSha256", sha);
        assertEquals("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED",
                knowledge().select(GeneralPm1Runtime.CRITERION, fixture.sequence.input.fixture().context(), model).path("reasonCode").asText());
    }
}
