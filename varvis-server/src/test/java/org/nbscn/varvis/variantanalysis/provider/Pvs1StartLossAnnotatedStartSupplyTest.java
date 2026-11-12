package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic annotated NMD structures exercise geometry; no function is inferred. */
class Pvs1StartLossAnnotatedStartSupplyTest {
    @TempDir Path temporary;
    final Pvs1StartLossRuntimeTest helper = new Pvs1StartLossRuntimeTest();
    ObjectNode inventory(Pvs1StartLossRuntimeTest.Fixture f) {
        var out = helper.mapper.createObjectNode().put("coverage", "ALL_SOURCE_TRANSCRIPTS_BEFORE_MODEL_FILTER").put("sourceCoverageComplete", true);
        out.putArray("transcripts").addObject().put("transcript", "ENST00000000001.1").put("geneId", "ENSG00000000001.1")
                .put("geneSymbol", f.data().model.path("geneSymbol").asText()).put("hgncId", f.data().context.at("/gene/hgncId").asText())
                .put("assembly", "GRCh38").put("contig", f.data().model.path("contig").asText())
                .put("transcriptType", "nonsense_mediated_decay").put("structureStatus", "UNRESOLVED");
        return out;
    }
    ObjectNode expanded(Pvs1StartLossRuntimeTest.Fixture f) {
        int strand = f.data().model.path("strand").asInt();
        var doc = helper.mapper.createObjectNode().put("id", "ENST00000000001").put("version", 1)
                .put("object_type", "Transcript").put("species", "homo_sapiens").put("assembly_name", "GRCh38")
                .put("seq_region_name", f.data().model.path("contig").asText()).put("Parent", "ENSG00000000001")
                .put("biotype", "nonsense_mediated_decay").put("strand", strand);
        int start = helper.helper.genomic(f.data().model, 1), end = helper.helper.genomic(f.data().model, 18);
        doc.putObject("Translation").put("id", "ENSP00000000001").put("version", 1).put("Parent", "ENST00000000001")
                .put("length", 5).put("start", Math.min(start, end)).put("end", Math.max(start, end));
        var rows = doc.putArray("Exon");
        for (var exon : f.data().model.path("exons")) rows.addObject().put("start", exon.path("genomicStart").asInt())
                .put("end", exon.path("genomicEnd").asInt()).put("assembly_name", "GRCh38").put("strand", strand)
                .put("seq_region_name", f.data().model.path("contig").asText());
        return doc;
    }
    Pvs1StartLossPublicSources source(ObjectNode doc) {
        return new Pvs1StartLossPublicSources(helper.mapper, true, (url, timeout, max) -> {
            assertEquals("rest.ensembl.org", url.getHost());
            return new ProteinRegionHttpTransport.Response(200, Map.of(), helper.mapper.writeValueAsBytes(doc));
        });
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void exactNmdAnnotatedStartIsComparedWithoutDeclaringItNonfunctional(int strand) throws Exception {
        var f = helper.fixture(strand); var technical = new Pvs1StartLossAutomaticPreparationTest(); technical.root = temporary;
        var original = inventory(f); var before = original.deepCopy();
        var result = Pvs1StartLossAnnotatedStartSupply.supplement(helper.mapper, f.data().context, original, source(expanded(f)), technical.technicalSupply(f));
        assertTrue(result.at("/transcripts/0/codingStartReferenceVerified").asBoolean());
        var review = Pvs1StartLossTranscriptQualifier.structural(helper.mapper, f.data().context, f.data().model, result, "b".repeat(64));
        assertEquals("SAME_START_CODON", review.at("/findings/0/reasonCode").asText());
        assertTrue(review.at("/findings/0/functionallyRelevant").isNull());
        assertFalse(review.path("complete").asBoolean()); // No selected transcript in this synthetic subset.
        assertEquals(before, original);
    }
    @Test void changedVersionWrongGeneAndNonAtgPositionRemainUnresolved() throws Exception {
        var f = helper.fixture(1); var technical = new Pvs1StartLossAutomaticPreparationTest(); technical.root = temporary;
        var reference = technical.technicalSupply(f);
        for (String mutation : java.util.List.of("version", "gene", "start")) {
            var doc = expanded(f);
            if (mutation.equals("version")) doc.put("version", 2);
            else if (mutation.equals("gene")) doc.put("Parent", "ENSG00000000002");
            else doc.withObject("Translation").put("start", helper.helper.genomic(f.data().model, 4));
            var result = Pvs1StartLossAnnotatedStartSupply.supplement(helper.mapper, f.data().context, inventory(f), source(doc), reference);
            assertFalse(result.at("/transcripts/0/codingStartReferenceVerified").asBoolean(), mutation);
            var review = Pvs1StartLossTranscriptQualifier.structural(helper.mapper, f.data().context, f.data().model, result, "b".repeat(64));
            assertEquals("UNRESOLVED", review.at("/findings/0/disposition").asText());
        }
    }
}
