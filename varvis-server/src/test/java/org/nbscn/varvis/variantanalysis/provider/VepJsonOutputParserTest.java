package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VepJsonOutputParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parsesTranscriptManeAndLofteeFieldsFromTinyJsonOutput() throws IOException {
        Path output = tempDir.resolve("vep.json");
        Files.write(output, ("{\"input\":\"1 100 . A G . . .\","
                + "\"transcript_consequences\":[{"
                + "\"gene_symbol\":\"GENE1\","
                + "\"gene_id\":\"ENSG000001\","
                + "\"transcript_id\":\"ENST000001\","
                + "\"biotype\":\"protein_coding\","
                + "\"consequence_terms\":[\"missense_variant\",\"splice_region_variant\"],"
                + "\"impact\":\"MODERATE\","
                + "\"hgvsc\":\"ENST000001.7:c.1A>G\","
                + "\"hgvsp\":\"ENSP000001.3:p.Lys1Arg\","
                + "\"exon\":\"1/10\","
                + "\"intron\":\"2/9\","
                + "\"canonical\":1,"
                + "\"mane_select\":\"NM_000001.1\","
                + "\"mane_plus_clinical\":\"NM_000001.1\","
                + "\"protein_start\":1,"
                + "\"protein_end\":2,"
                + "\"amino_acids\":\"K/R\","
                + "\"codons\":\"aAa/aGa\","
                + "\"LoF\":\"HC\","
                + "\"LoF_filter\":\"END_TRUNC\","
                + "\"LoF_flags\":\"SINGLE_EXON\""
                + "}]}\n").getBytes(StandardCharsets.UTF_8));

        List<TranscriptConsequence> annotations =
                new VepJsonOutputParser(new ObjectMapper()).parse(output, "VEP_CACHED", "vep-test");

        assertEquals(1, annotations.size());
        TranscriptConsequence annotation = annotations.get(0);
        assertEquals("GENE1", annotation.getGeneSymbol());
        assertEquals("ENSG000001", annotation.getGeneId());
        assertEquals("ENST000001", annotation.getTranscriptId());
        assertEquals("ENST000001", annotation.getTranscriptAccession());
        assertEquals("ENST000001.7", annotation.getVersionedTranscriptId());
        assertEquals(7, annotation.getTranscriptVersion());
        assertEquals("ENSP000001.3", annotation.getProteinIdentity());
        assertEquals("missense_variant&splice_region_variant", annotation.getConsequence());
        assertEquals("MODERATE", annotation.getImpact());
        assertTrue(annotation.getManeSelect());
        assertTrue(annotation.getManePlusClinical());
        assertEquals("1/10", annotation.getExon());
        assertEquals("2/9", annotation.getIntron());
        assertEquals("1-2", annotation.getProteinPosition());
        assertEquals("K/R", annotation.getAminoAcids());
        assertEquals("aAa/aGa", annotation.getCodons());
        assertEquals("HC", annotation.getLof());
        assertEquals("END_TRUNC", annotation.getLofFilter());
        assertEquals("SINGLE_EXON", annotation.getLofFlags());
        assertEquals("VEP_CACHED", annotation.getSource());
        assertEquals("COMPLETED", annotation.getStatus());
    }

    @Test
    void parsesBatchOutputByVariantIdAndKeepsPartialWarnings() throws IOException {
        Path output = tempDir.resolve("vep-batch.json");
        Files.write(output, ("{\"id\":\"GRCh38:1:100:A:G\","
                + "\"transcript_consequences\":[{"
                + "\"gene_symbol\":\"GENE1\","
                + "\"transcript_id\":\"ENST000001\","
                + "\"consequence_terms\":[\"missense_variant\"]"
                + "}]}\n"
                + "{broken-json\n").getBytes(StandardCharsets.UTF_8));

        VepParseResult result =
                new VepJsonOutputParser(new ObjectMapper()).parseByVariantId(output, "VEP_COMPUTED", "vep-test");

        assertEquals(1, result.getAnnotationsByVariantId().size());
        assertEquals("GENE1",
                result.getAnnotationsByVariantId().get("GRCh38:1:100:A:G").get(0).getGeneSymbol());
        assertEquals(1, result.getWarnings().size());
    }
}
