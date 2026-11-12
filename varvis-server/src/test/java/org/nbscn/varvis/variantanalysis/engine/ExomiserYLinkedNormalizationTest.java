package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Finite MOI parsing, not a Y disease-mechanism or population-method qualification. */
class ExomiserYLinkedNormalizationTest {
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"Y_LINKED", "y-linked", "Y linked"})
    void parsesExplicitYLinkedWhileRetainingRawIdentityAndNativeRanking(String rawMoi) throws Exception {
        var candidates = parse(List.of(rawMoi, "AR"));
        var y = candidates.get(0);
        var features = y.getExomiserCandidateFeatures();
        assertNotNull(features);
        assertEquals(rawMoi, features.getRawModeOfInheritance());
        assertEquals("Y_LINKED", features.getNormalizedModeOfInheritance());
        assertEquals(rawMoi, y.getCandidateIdentity().getModeOfInheritance());
        assertEquals("exomiser-v1-00000001", y.getCandidateId());
        assertEquals(1, y.getCandidateIdentity().getSourceOrdinal());
        assertEquals(9, y.getCandidateIdentity().getDeclaredRank());
        assertEquals(9, features.getGeneMoiGroupRank());
        assertEquals("RANK_ENTREZ_MOI", features.getGeneRowMatchStrategy());
        assertEquals(0.81d, features.getGeneCombinedScore());
        assertEquals(0.1d, y.getRankingScore());
        assertEquals("DERIVED:MOI", features.getFieldSources().get("normalizedModeOfInheritance"));
        assertFalse(features.getInvalidFields().contains("MOI:UNRECOGNIZED"));
        assertEquals("Y", y.getObservation().getKey().getChromosome());
        assertEquals("GRCh38", y.getObservation().getKey().getGenomeAssembly());
        assertEquals("exomiser-v1-00000002", candidates.get(1).getCandidateId());
        assertEquals(2, candidates.get(1).getCandidateIdentity().getDeclaredRank());
        assertEquals("AUTOSOMAL_RECESSIVE", candidates.get(1).getExomiserCandidateFeatures().getNormalizedModeOfInheritance());
        var mapper = new ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsBytes(y), VariantInterpretation.class);
        assertEquals(rawMoi, restored.getCandidateIdentity().getModeOfInheritance());
        assertEquals("Y_LINKED", restored.getExomiserCandidateFeatures().getNormalizedModeOfInheritance());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Y", "YL", "HOLANDRIC", "HEMIZYGOUS", "MALE", "XL", "UNKNOWN", "Y_LINKED_RECESSIVE"})
    void ambiguousOrUnsupportedModeRemainsUnrecognizedRatherThanInferringY(String rawMoi) throws Exception {
        var candidate = parse(List.of(rawMoi)).get(0);
        var features = candidate.getExomiserCandidateFeatures();
        assertNotNull(features);
        assertEquals(rawMoi, features.getRawModeOfInheritance());
        assertEquals(rawMoi, candidate.getCandidateIdentity().getModeOfInheritance());
        assertNull(features.getNormalizedModeOfInheritance());
        assertTrue(features.getInvalidFields().contains("MOI:UNRECOGNIZED"));
    }

    @Test
    void consistentYFeatureCannotBeReidentifiedAsAnotherRankingMode() throws Exception {
        var mapper = new ObjectMapper();
        var candidate = parse(List.of("Y_LINKED")).get(0);
        ObjectNode json = mapper.valueToTree(candidate);
        ((ObjectNode) json.get("candidateIdentity")).put("modeOfInheritance", "AR");
        var failure = assertThrows(JsonMappingException.class,
                () -> mapper.treeToValue(json, VariantInterpretation.class));
        assertTrue(failure.getMessage().contains("EXOMISER_FEATURE_MOI_MISMATCH"));
    }

    private List<VariantInterpretation> parse(List<String> modes) throws Exception {
        Path output = temporary.resolve("output");
        Files.createDirectories(output);
        StringBuilder genes = new StringBuilder("#RANK\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\tEXOMISER_GENE_COMBINED_SCORE\n");
        StringBuilder variants = new StringBuilder("#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\tEXOMISER_VARIANT_SCORE\n");
        for (int i = 0; i < modes.size(); i++) {
            int rank = i == 0 ? 9 : 2;
            String gene = "SYNTHETIC" + i;
            String geneMoi = "Y_LINKED".equals(modes.get(i).toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace(' ', '_'))
                    ? "Y_LINKED" : modes.get(i);
            genes.append(rank).append('\t').append(gene).append('\t').append(100 + i).append('\t')
                    .append(geneMoi).append("\t0.81\n");
            variants.append(rank).append("\tY\t").append(100 + i).append("\tA\tT\t").append(gene)
                    .append('\t').append(100 + i).append('\t').append(modes.get(i)).append("\t0.1\n");
        }
        Files.writeString(output.resolve("result.genes.tsv"), genes, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("result.variants.tsv"), variants, StandardCharsets.UTF_8);
        return new ExomiserOutputParser().parse(output, "SYNTHETIC", "15.1.0");
    }
}
