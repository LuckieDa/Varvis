package org.nbscn.varvis.variantanalysis.engine;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ScanResult;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

class ExomiserOutputParserTest {

    @TempDir
    Path temporary;

    @Test
    void parsesLargeVariantOutputInSourceOrderWithoutDroppingRows() throws Exception {
        Path output = temporary.resolve("output");
        Files.createDirectories(output);
        Files.write(output.resolve("result.genes.tsv"), (
                "#RANK\tGENE_SYMBOL\tGENE_SCORE\n"
                        + "1\tGENE1\t0.92\n"
        ).getBytes(StandardCharsets.UTF_8));
        Path variants = output.resolve("result.variants.tsv");
        int rowCount = 12_000;
        try (BufferedWriter writer = Files.newBufferedWriter(variants, StandardCharsets.UTF_8)) {
            writer.write("#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tEXOMISER_VARIANT_SCORE\n");
            for (int index = 0; index < rowCount; index++) {
                writer.write(Integer.toString(index + 1));
                writer.write("\t1\t");
                writer.write(Integer.toString(index + 100));
                writer.write("\tA\tT\tGENE1\t0.91\n");
            }
        }

        List<VariantInterpretation> parsed = new ExomiserOutputParser().parse(output, "S1", "15.1.0");

        assertEquals(rowCount, parsed.size());
        assertEquals("GRCh38|1|100|A|T", parsed.get(0).getVariantId());
        assertEquals("GRCh38|1|100|A|T", parsed.get(0).getCanonicalVariantId());
        assertEquals("exomiser-v1-00000001", parsed.get(0).getCandidateId());
        assertEquals("GRCh38|1|12099|A|T", parsed.get(rowCount - 1).getVariantId());
        assertEquals("exomiser-v1-00012000", parsed.get(rowCount - 1).getCandidateId());
        assertEquals("12000", parsed.get(rowCount - 1).getEvidence().get(0).getDetails().get("rank"));
    }

    @Test
    void assignsDistinctCandidateRowsToDuplicateCanonicalVariants() throws Exception {
        Path output = temporary.resolve("duplicate-canonical");
        Files.createDirectories(output);
        Files.write(output.resolve("result.genes.tsv"), (
                "#RANK\tGENE_SYMBOL\tMOI\tGENE_SCORE\n"
                        + "1\tGENE1\tAUTOSOMAL_DOMINANT\t0.92\n"
                        + "2\tGENE1\tAUTOSOMAL_RECESSIVE\t0.81\n"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tMOI\tEXOMISER_VARIANT_SCORE\n"
                        + "1\t1\t100\tA\tT\tGENE1\tAUTOSOMAL_DOMINANT\t0.91\n"
                        + "2\t1\t100\tA\tT\tGENE1\tAUTOSOMAL_RECESSIVE\t0.80\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<VariantInterpretation> parsed = new ExomiserOutputParser().parse(output, "S1", "15.1.0");

        assertEquals(2, parsed.size());
        assertEquals(parsed.get(0).getCanonicalVariantId(), parsed.get(1).getCanonicalVariantId());
        assertEquals("GRCh38|1|100|A|T", parsed.get(0).getCanonicalVariantId());
        assertEquals("exomiser-v1-00000001", parsed.get(0).getCandidateId());
        assertEquals("exomiser-v1-00000002", parsed.get(1).getCandidateId());
        assertEquals("GENE1", parsed.get(0).getCandidateIdentity().getGeneSymbol());
        assertEquals("AUTOSOMAL_DOMINANT", parsed.get(0).getCandidateIdentity().getModeOfInheritance());
        assertEquals(1, parsed.get(0).getCandidateIdentity().getDeclaredRank());
        assertEquals("GENE1", parsed.get(1).getCandidateIdentity().getGeneSymbol());
        assertEquals("AUTOSOMAL_RECESSIVE", parsed.get(1).getCandidateIdentity().getModeOfInheritance());
        assertEquals(2, parsed.get(1).getCandidateIdentity().getSourceOrdinal());
        @SuppressWarnings("unchecked")
        Map<String, String> dominantGene = (Map<String, String>) parsed.get(0)
                .getEvidence().get(0).getDetails().get("gene");
        @SuppressWarnings("unchecked")
        Map<String, String> recessiveGene = (Map<String, String>) parsed.get(1)
                .getEvidence().get(0).getDetails().get("gene");
        assertEquals("0.92", dominantGene.get("GENE_SCORE"));
        assertEquals("0.81", recessiveGene.get("GENE_SCORE"));
    }

    @Test
    void linksEveryCompoundHeterozygousVariantToTheUniqueGeneMoiRow() throws Exception {
        Path output = temporary.resolve("compound-heterozygous");
        Files.createDirectories(output);
        Files.write(output.resolve("result.genes.tsv"), (
                "#RANK\tGENE_SYMBOL\tMOI\tGENE_SCORE\n"
                        + "4\tGENE2\tAUTOSOMAL_RECESSIVE\t0.77\n"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tMOI\tEXOMISER_VARIANT_SCORE\n"
                        + "4\t2\t200\tC\tT\tGENE2\tAUTOSOMAL_RECESSIVE\t0.71\n"
                        + "4\t2\t300\tG\tA\tGENE2\tAUTOSOMAL_RECESSIVE\t0.69\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<VariantInterpretation> parsed = new ExomiserOutputParser().parse(output, "S1", "15.1.0");

        assertEquals(2, parsed.size());
        for (VariantInterpretation interpretation : parsed) {
            @SuppressWarnings("unchecked")
            Map<String, String> gene = (Map<String, String>) interpretation
                    .getEvidence().get(0).getDetails().get("gene");
            assertEquals("0.77", gene.get("GENE_SCORE"));
            assertEquals("AUTOSOMAL_RECESSIVE", gene.get("MOI"));
        }
    }

    @Test
    void rejectsRowsWithMoreCellsThanTheDeclaredHeader() throws Exception {
        Path output = temporary.resolve("mismatch");
        Files.createDirectories(output);
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\n"
                        + "1\t1\t100\tA\tT\textra\n"
        ).getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExomiserOutputParser().parse(output, "S1", "15.1.0"));

        assertEquals("TSV_COLUMN_COUNT_MISMATCH", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    @Test
    void zeroByteVariantArtifactPreservesTheLegacyMissingCode() throws Exception {
        Path output = temporary.resolve("zero-byte-variants");
        Files.createDirectories(output);
        Files.write(output.resolve("result.variants.tsv"), new byte[0]);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExomiserOutputParser().parse(output, "S1", "15.1.0"));

        assertEquals("EXOMISER_OUTPUT_VARIANTS_MISSING", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    @Test
    void requiredVariantFailureKeepsPriorityOverAMalformedOptionalGene() throws Exception {
        Path output = temporary.resolve("missing-variants-malformed-gene");
        Files.createDirectories(output);
        Files.writeString(output.resolve("result.genes.tsv"),
                "#RANK\t#RANK\n1\t1\n",
                StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExomiserOutputParser().parse(output, "S1", "15.1.0"));

        assertEquals("EXOMISER_OUTPUT_VARIANTS_MISSING", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void nonRegularVariantArtifactPreservesTheLegacyMissingCode() throws Exception {
        Path output = temporary.resolve("non-regular-variants");
        Files.createDirectories(output.resolve("result.variants.tsv"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExomiserOutputParser().parse(output, "S1", "15.1.0"));

        assertEquals("EXOMISER_OUTPUT_VARIANTS_MISSING", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void validHeaderWithoutDataRowsUsesTheDistinctEmptyCode() throws Exception {
        Path output = temporary.resolve("header-only-variants");
        Files.createDirectories(output);
        Files.write(output.resolve("result.variants.tsv"),
                "#RANK\tCONTIG\tSTART\tREF\tALT\n".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExomiserOutputParser().parse(output, "S1", "15.1.0"));

        assertEquals("EXOMISER_OUTPUT_VARIANTS_EMPTY", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    @Test
    void parseVerifiedBindsInterpretationsAndRawHashToTheSameBoundedScan() throws Exception {
        Path output = temporary.resolve("verified-scan");
        Files.createDirectories(output);
        byte[] genes = (
                "#RANK\tGENE_SYMBOL\tOPTIONAL_EVIDENCE\r\n"
                        + "1\tGENE1\r\n"
        ).getBytes(StandardCharsets.UTF_8);
        byte[] variants = (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tUNKNOWN_COLUMN\r\n"
                        + "1\t1\t100\tA\tT\tGENE1\topaque\r\n"
        ).getBytes(StandardCharsets.UTF_8);
        Files.write(output.resolve("result.genes.tsv"), genes);
        Files.write(output.resolve("result.variants.tsv"), variants);

        ExomiserOutputParser.ParsedOutput parsed = new ExomiserOutputParser()
                .parseVerified(output, "S1", "15.1.0");

        assertEquals(1, parsed.interpretations().size());
        assertThrows(UnsupportedOperationException.class,
                () -> parsed.interpretations().add(null));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(variants)),
                parsed.variantScan().rawSha256());
        assertEquals(variants.length, parsed.variantScan().byteCount());
        assertEquals(2L, parsed.variantScan().physicalLineCount());
        assertEquals(1L, parsed.variantScan().dataRowCount());
        assertEquals(7, parsed.variantScan().columnCount());
        assertEquals(Arrays.asList(
                "#RANK", "CONTIG", "START", "REF", "ALT", "GENE_SYMBOL", "UNKNOWN_COLUMN"
        ), parsed.variantScan().header());
        assertNotNull(parsed.geneScan());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(genes)),
                parsed.geneScan().rawSha256());
        @SuppressWarnings("unchecked")
        Map<String, String> retainedVariant = (Map<String, String>) parsed.interpretations().get(0)
                .getEvidence().get(0).getDetails().get("variant");
        assertEquals("opaque", retainedVariant.get("UNKNOWN_COLUMN"));
    }

    @Test
    void finalPublicationCheckRejectsVariantDriftAfterHydrationWindow() throws Exception {
        Path output = temporary.resolve("post-parse-variant-drift");
        Files.createDirectories(output);
        Path variants = output.resolve("result.variants.tsv");
        Files.writeString(variants,
                "#RANK\tCONTIG\tSTART\tREF\tALT\n1\t1\t100\tA\tT\n",
                StandardCharsets.UTF_8);
        ExomiserOutputParser parser = new ExomiserOutputParser();
        ExomiserOutputParser.ParsedOutput parsed = parser.parseVerified(
                output, "S1", "15.1.0");
        Files.writeString(variants,
                "#RANK\tCONTIG\tSTART\tREF\tALT\n1\t2\t200000\tC\tT\n",
                StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parser.verifyStillCurrent(output, parsed));

        assertEquals("EXOMISER_OUTPUT_PARSE_FAILED", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    @Test
    void finalPublicationCheckRejectsAnOptionalGeneThatAppearsLate() throws Exception {
        Path output = temporary.resolve("post-parse-gene-appears");
        Files.createDirectories(output);
        Files.writeString(output.resolve("result.variants.tsv"),
                "#RANK\tCONTIG\tSTART\tREF\tALT\n1\t1\t100\tA\tT\n",
                StandardCharsets.UTF_8);
        ExomiserOutputParser parser = new ExomiserOutputParser();
        ExomiserOutputParser.ParsedOutput parsed = parser.parseVerified(
                output, "S1", "15.1.0");
        Files.writeString(output.resolve("result.genes.tsv"),
                "#RANK\n1\n",
                StandardCharsets.UTF_8);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> parser.verifyStillCurrent(output, parsed));

        assertEquals("EXOMISER_OUTPUT_PARSE_FAILED", error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void missingGeneArtifactRemainsOptionalAndIsExplicitInVerifiedResult() throws Exception {
        Path output = temporary.resolve("missing-genes");
        Files.createDirectories(output);
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\n"
                        + "1\t1\t100\tA\tT\tGENE1\n"
        ).getBytes(StandardCharsets.UTF_8));

        ExomiserOutputParser.ParsedOutput parsed = new ExomiserOutputParser()
                .parseVerified(output, "S1", "15.1.0");

        assertNull(parsed.geneScan());
        assertEquals("GENE_ARTIFACT_MISSING", parsed.interpretations().get(0)
                .getExomiserCandidateFeatures().getGeneRowMatchStrategy());
    }

    @Test
    void geneReplacementAfterVariantScanFailsTheOutputSetVerification() throws Exception {
        String canary = "PRIVATE_REPLACEMENT_GENE_9d3a";
        Path output = temporary.resolve("gene-output-set-change");
        Files.createDirectories(output);
        Path genes = output.resolve("result.genes.tsv");
        Files.writeString(genes, "#RANK\tGENE_SYMBOL\n1\tGENE1\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("result.variants.tsv"),
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\n"
                        + "1\t1\t100\tA\tT\tGENE1\n",
                StandardCharsets.UTF_8);
        ExomiserTsvScanner scanner = spy(new ExomiserTsvScanner());
        doAnswer(invocation -> {
            Files.writeString(genes,
                    "#RANK\tGENE_SYMBOL\n1\t" + canary + "-with-a-different-size\n",
                    StandardCharsets.UTF_8);
            return invocation.callRealMethod();
        }).when(scanner).verifyUnchanged(eq(genes), any(ScanResult.class));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExomiserOutputParser(scanner)
                        .parseVerified(output, "S1", "15.1.0"));

        assertEquals("EXOMISER_OUTPUT_PARSE_FAILED", error.getMessage());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(canary));
        assertFalse(error.toString().contains(output.toString()));
    }

    @Test
    void initiallyMissingGeneThatAppearsDuringVariantScanFailsClosed() throws Exception {
        Path output = temporary.resolve("gene-appears");
        Files.createDirectories(output);
        Path genes = output.resolve("result.genes.tsv");
        Files.writeString(output.resolve("result.variants.tsv"),
                "#RANK\tCONTIG\tSTART\tREF\tALT\n1\t1\t100\tA\tT\n",
                StandardCharsets.UTF_8);
        ExomiserTsvScanner scanner = spy(new ExomiserTsvScanner());
        doAnswer(invocation -> {
            Files.writeString(genes, "#RANK\n1\n", StandardCharsets.UTF_8);
            return invocation.callRealMethod();
        }).when(scanner).verifyAbsent(eq(genes));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExomiserOutputParser(scanner)
                        .parseVerified(output, "S1", "15.1.0"));

        assertEquals("EXOMISER_OUTPUT_PARSE_FAILED", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    @Test
    void malformedGeneArtifactFailsClosedWithoutLeakingPathOrRawValues() throws Exception {
        String canary = "PRIVATE_GENE_CANARY_7f30";
        Path output = temporary.resolve(canary);
        Files.createDirectories(output);
        Files.write(output.resolve("result.genes.tsv"), (
                "#RANK\t" + canary + "\t" + canary + "\n"
                        + "1\tsecret\tsecret\n"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\n"
                        + "1\t1\t100\tA\tT\n"
        ).getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExomiserOutputParser().parse(output, "S1", "15.1.0"));

        assertEquals("EXOMISER_OUTPUT_PARSE_FAILED", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.toString().contains(canary));
        assertFalse(error.toString().contains(output.toAbsolutePath().toString()));
    }

    @Test
    void rowConversionFailureDoesNotLeakTheInvalidCell() throws Exception {
        String canary = "PRIVATE_START_CANARY_0d9a";
        Path output = temporary.resolve("private-row");
        Files.createDirectories(output);
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\n"
                        + "1\t1\t" + canary + "\tA\tT\n"
        ).getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ExomiserOutputParser().parse(output, "S1", "15.1.0"));

        assertEquals("EXOMISER_OUTPUT_PARSE_FAILED", error.getMessage());
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.toString().contains(canary));
    }

    @Test
    void blankLinesDoNotConsumeOrdinalsAndLegacyNonIntegerRankRemainsReadable() throws Exception {
        Path output = temporary.resolve("legacy-rank");
        Files.createDirectories(output);
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tEXOMISER_VARIANT_SCORE\n"
                        + "\n"
                        + "legacy-rank\t1\t100\tA\tT\tGENE1\t0.91\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<VariantInterpretation> parsed = new ExomiserOutputParser().parse(output, "S1", "15.1.0");

        assertEquals(1, parsed.size());
        assertEquals("exomiser-v1-00000001", parsed.get(0).getCandidateId());
        assertEquals(1, parsed.get(0).getCandidateIdentity().getSourceOrdinal());
        assertNull(parsed.get(0).getCandidateIdentity().getDeclaredRank());
    }

    @Test
    void exposesCompleteTypedFeaturesWithExactGeneIdentityAndRoundTripsJson() throws Exception {
        Path output = temporary.resolve("typed-features");
        Files.createDirectories(output);
        Files.write(output.resolve("result.genes.tsv"), (
                "#RANK\tID\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\t"
                        + "EXOMISER_GENE_COMBINED_SCORE\tEXOMISER_GENE_PHENO_SCORE\t"
                        + "EXOMISER_GENE_VARIANT_SCORE\n"
                        + "3\tgene-101\tGENE1\t101\tAR\t0.91\t0.82\t0.73\n"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tID\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\t"
                        + "EXOMISER_GENE_COMBINED_SCORE\tEXOMISER_GENE_PHENO_SCORE\t"
                        + "EXOMISER_GENE_VARIANT_SCORE\tEXOMISER_VARIANT_SCORE\t"
                        + "FUNCTIONAL_CLASS\tHGVS\tCONTRIBUTING_VARIANT\tFILTER\n"
                        + "3\tvariant-1\t1\t100\tA\tT\tGENE1\t101\tAR\t"
                        + "0.91\t0.82\t0.73\t0.64\tMISSENSE_VARIANT\tNM_1:c.1A>T\t1\tPASS\n"
        ).getBytes(StandardCharsets.UTF_8));

        VariantInterpretation interpretation = new ExomiserOutputParser()
                .parse(output, "S1", "15.1.0")
                .get(0);
        ExomiserCandidateFeatures features = interpretation.getExomiserCandidateFeatures();

        assertNotNull(features);
        assertEquals(ExomiserCandidateFeatures.CURRENT_SCHEMA_VERSION, features.getSchemaVersion());
        assertEquals("15.1.0", features.getEngineVersion());
        assertEquals("GENE1", features.getGeneSymbol());
        assertEquals("101", features.getEntrezGeneId());
        assertEquals("AR", features.getRawModeOfInheritance());
        assertEquals("AUTOSOMAL_RECESSIVE", features.getNormalizedModeOfInheritance());
        assertEquals(3, features.getGeneMoiGroupRank());
        assertEquals(
                ExomiserCandidateFeatures.GENE_MOI_GROUP_RANK_SEMANTICS,
                features.getGeneMoiGroupRankSemantics()
        );
        assertEquals("variant-1", features.getSourceRecordId());
        assertEquals(0.91d, features.getGeneCombinedScore());
        assertEquals(0.82d, features.getGenePhenotypeScore());
        assertEquals(0.73d, features.getGeneVariantScore());
        assertEquals(0.64d, features.getVariantScore());
        assertEquals("MISSENSE_VARIANT", features.getFunctionalClass());
        assertEquals("NM_1:c.1A>T", features.getHgvs());
        assertEquals(Boolean.TRUE, features.getContributingVariant());
        assertEquals("result.variants.tsv", features.getVariantArtifactName());
        assertEquals("result.genes.tsv", features.getGeneArtifactName());
        assertEquals(1, features.getVariantSourceOrdinal());
        assertEquals(1, features.getGeneSourceOrdinal());
        assertEquals("RANK_ENTREZ_MOI", features.getGeneRowMatchStrategy());
        assertEquals("EXOMISER_VARIANT_SCORE", features.getRankingScoreSourceColumn());
        assertTrue(features.getMissingFields().isEmpty());
        assertTrue(features.getInvalidFields().isEmpty());
        assertEquals("VARIANT_TSV:ENTREZ_GENE_ID", features.getFieldSources().get("entrezGeneId"));
        assertEquals("PASS", interpretation.getObservation().getFilterStatus());
        assertTrue(interpretation.getRationale().get(0).contains("gene/MOI group rank 3"));
        assertTrue(interpretation.getRationale().get(0).contains("not a global variant rank"));

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode serialized = mapper.valueToTree(interpretation);
        ObjectNode serializedFeatures = (ObjectNode) serialized.get("exomiserCandidateFeatures");
        assertFalse(serializedFeatures.has("missingFields"));
        assertFalse(serializedFeatures.has("invalidFields"));
        VariantInterpretation restored = mapper.readValue(
                mapper.writeValueAsString(serialized),
                VariantInterpretation.class
        );
        assertEquals("101", restored.getExomiserCandidateFeatures().getEntrezGeneId());
        assertTrue(restored.getExomiserCandidateFeatures().getMissingFields().isEmpty());
        assertTrue(restored.getExomiserCandidateFeatures().getInvalidFields().isEmpty());
        CandidateIdentity persistedIdentity = restored.getCandidateIdentity().withCandidateId("persisted-row-1");
        VariantInterpretation reidentified = restored.withCandidateIdentity(persistedIdentity);
        assertEquals("persisted-row-1", reidentified.getCandidateId());
        assertEquals(0.64d, reidentified.getExomiserCandidateFeatures().getVariantScore());

        assertFeatureTamperRejected(
                mapper, interpretation, "variantSourceOrdinal", 9,
                "EXOMISER_FEATURE_SOURCE_ORDINAL_MISMATCH"
        );
        assertFeatureTamperRejected(
                mapper, interpretation, "geneSymbol", "OTHER_GENE",
                "EXOMISER_FEATURE_GENE_SYMBOL_MISMATCH"
        );
        assertFeatureTamperRejected(
                mapper, interpretation, "rawModeOfInheritance", "AD",
                "EXOMISER_MOI_NORMALIZATION_MISMATCH"
        );
        assertFeatureTamperRejected(
                mapper, interpretation, "geneMoiGroupRank", 99,
                "EXOMISER_FEATURE_RANK_MISMATCH"
        );
        assertFeatureTamperRejected(
                mapper, interpretation, "normalizedModeOfInheritance", "CUSTOM_MODE",
                "EXOMISER_NORMALIZED_MOI_INVALID"
        );
        assertFeatureTamperRejected(
                mapper, interpretation, "normalizedModeOfInheritance", "AUTOSOMAL_DOMINANT",
                "EXOMISER_MOI_NORMALIZATION_MISMATCH"
        );

        ObjectNode consistentButReidentifiedMoi = mapper.valueToTree(interpretation);
        ObjectNode reidentifiedMoiFeatures =
                (ObjectNode) consistentButReidentifiedMoi.get("exomiserCandidateFeatures");
        reidentifiedMoiFeatures.put("rawModeOfInheritance", "AD");
        reidentifiedMoiFeatures.put("normalizedModeOfInheritance", "AUTOSOMAL_DOMINANT");
        JsonMappingException identityMoiFailure = assertThrows(
                JsonMappingException.class,
                () -> mapper.treeToValue(consistentButReidentifiedMoi, VariantInterpretation.class)
        );
        assertTrue(identityMoiFailure.getMessage().contains("EXOMISER_FEATURE_MOI_MISMATCH"));

        ObjectNode missingIdentityGene = mapper.valueToTree(interpretation);
        ((ObjectNode) missingIdentityGene.get("candidateIdentity")).putNull("geneSymbol");
        JsonMappingException nullnessFailure = assertThrows(
                JsonMappingException.class,
                () -> mapper.treeToValue(missingIdentityGene, VariantInterpretation.class)
        );
        assertTrue(nullnessFailure.getMessage().contains(
                "EXOMISER_FEATURE_GENE_SYMBOL_MISMATCH"
        ));

        ObjectNode equivalentMoi = mapper.valueToTree(interpretation);
        ((ObjectNode) equivalentMoi.get("exomiserCandidateFeatures"))
                .put("rawModeOfInheritance", "AUTOSOMAL_RECESSIVE");
        assertEquals(
                "AUTOSOMAL_RECESSIVE",
                mapper.treeToValue(equivalentMoi, VariantInterpretation.class)
                        .getExomiserCandidateFeatures()
                        .getRawModeOfInheritance()
        );
    }

    @Test
    void recordsMissingAndInvalidFieldsAndUsesOnlyFiniteLegacyRankingFallback() throws Exception {
        Path output = temporary.resolve("invalid-fields");
        Files.createDirectories(output);
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tID\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\t"
                        + "EXOMISER_VARIANT_SCORE\tVARIANT_SCORE\tCOMBINED_SCORE\t"
                        + "FUNCTIONAL_CLASS\tHGVS\tCONTRIBUTING_VARIANT\n"
                        + "2\tNA\t1\t100\tA\tT\t.\tNA\tN/A\tNaN\tInfinity\t0.42\t.\tNA\tmaybe\n"
        ).getBytes(StandardCharsets.UTF_8));

        VariantInterpretation interpretation = new ExomiserOutputParser()
                .parse(output, "S1", "15.1.0")
                .get(0);
        ExomiserCandidateFeatures features = interpretation.getExomiserCandidateFeatures();

        assertNull(features.getVariantScore());
        assertEquals(0.42d, interpretation.getRankingScore());
        assertEquals("COMBINED_SCORE", features.getRankingScoreSourceColumn());
        assertNull(features.getGeneSymbol());
        assertNull(features.getEntrezGeneId());
        assertNull(features.getRawModeOfInheritance());
        assertNull(features.getNormalizedModeOfInheritance());
        assertNull(features.getFunctionalClass());
        assertNull(features.getHgvs());
        assertNull(features.getContributingVariant());
        assertNull(interpretation.getObservation().getFilterStatus());
        assertTrue(features.getMissingFields().contains("ID"));
        assertTrue(features.getMissingFields().contains("GENE_SYMBOL"));
        assertTrue(features.getMissingFields().contains("MOI"));
        assertTrue(features.getInvalidFields().contains("EXOMISER_VARIANT_SCORE"));
        assertTrue(features.getInvalidFields().contains("VARIANT_SCORE"));
        assertTrue(features.getInvalidFields().contains("CONTRIBUTING_VARIANT"));
        assertFalse(Double.isNaN(interpretation.getRankingScore()));
        assertFalse(Double.isInfinite(interpretation.getRankingScore()));
        assertEquals("GENE_ARTIFACT_MISSING", features.getGeneRowMatchStrategy());
    }

    @Test
    void preservesExplicitNonPassFilterAndTreatsDotAsUnknown() throws Exception {
        Path output = temporary.resolve("filter-semantics");
        Files.createDirectories(output);
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tEXOMISER_VARIANT_SCORE\tFILTER\n"
                        + "1\t1\t100\tA\tT\t0.90\tLowQual\n"
                        + "2\t1\t200\tC\tG\t0.80\t.\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<VariantInterpretation> parsed = new ExomiserOutputParser()
                .parse(output, "S1", "15.1.0");

        assertEquals("LowQual", parsed.get(0).getObservation().getFilterStatus());
        assertNull(parsed.get(1).getObservation().getFilterStatus());
    }

    @Test
    void ambiguousExactGeneRowsAreNotSelected() throws Exception {
        Path output = temporary.resolve("ambiguous-gene");
        Files.createDirectories(output);
        Files.write(output.resolve("result.genes.tsv"), (
                "#RANK\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\tEXOMISER_GENE_COMBINED_SCORE\n"
                        + "1\tGENE1\t101\tAD\t0.90\n"
                        + "1\tGENE1\t101\tAD\t0.80\n"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\t"
                        + "EXOMISER_VARIANT_SCORE\n"
                        + "1\t1\t100\tA\tT\tGENE1\t101\tAD\t0.70\n"
        ).getBytes(StandardCharsets.UTF_8));

        ExomiserCandidateFeatures features = new ExomiserOutputParser()
                .parse(output, "S1", "15.1.0")
                .get(0)
                .getExomiserCandidateFeatures();

        assertEquals("AMBIGUOUS_RANK_ENTREZ_MOI", features.getGeneRowMatchStrategy());
        assertNull(features.getGeneSourceOrdinal());
        assertNull(features.getGeneCombinedScore());
        assertTrue(features.getInvalidFields().contains(
                "GENE_ROW_MATCH:AMBIGUOUS_RANK_ENTREZ_MOI"
        ));
    }

    @Test
    void explicitMoiConflictDoesNotFallBackToRankAndGene() throws Exception {
        Path output = temporary.resolve("conflicting-gene");
        Files.createDirectories(output);
        Files.write(output.resolve("result.genes.tsv"), (
                "#RANK\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\tEXOMISER_GENE_COMBINED_SCORE\n"
                        + "1\tGENE1\t101\tAR\t0.90\n"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\t"
                        + "EXOMISER_VARIANT_SCORE\n"
                        + "1\t1\t100\tA\tT\tGENE1\t101\tAD\t0.70\n"
        ).getBytes(StandardCharsets.UTF_8));

        ExomiserCandidateFeatures features = new ExomiserOutputParser()
                .parse(output, "S1", "15.1.0")
                .get(0)
                .getExomiserCandidateFeatures();

        assertEquals("CONFLICT_RANK_ENTREZ_MOI", features.getGeneRowMatchStrategy());
        assertNull(features.getGeneSourceOrdinal());
        assertNull(features.getGeneCombinedScore());
        assertEquals("AD", features.getRawModeOfInheritance());
        assertEquals("AUTOSOMAL_DOMINANT", features.getNormalizedModeOfInheritance());
        assertTrue(features.getInvalidFields().contains(
                "GENE_ROW_MATCH:CONFLICT_RANK_ENTREZ_MOI"
        ));
    }

    @Test
    void parsesNativeNumericContributingVariantFlags() throws Exception {
        Path output = temporary.resolve("numeric-contributing-flags");
        Files.createDirectories(output);
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tEXOMISER_VARIANT_SCORE\t"
                        + "CONTRIBUTING_VARIANT\n"
                        + "1\t1\t100\tA\tT\tGENE1\t0.90\t1\n"
                        + "2\t1\t200\tC\tG\tGENE2\t0.80\t0\n"
        ).getBytes(StandardCharsets.UTF_8));

        List<VariantInterpretation> parsed = new ExomiserOutputParser()
                .parse(output, "S1", "15.1.0");

        assertEquals(Boolean.TRUE, parsed.get(0).getExomiserCandidateFeatures().getContributingVariant());
        assertEquals(Boolean.FALSE, parsed.get(1).getExomiserCandidateFeatures().getContributingVariant());
        ObjectNode falseFeatureJson = new ObjectMapper().valueToTree(
                parsed.get(1).getExomiserCandidateFeatures()
        );
        assertTrue(falseFeatureJson.has("contributingVariant"));
        assertFalse(falseFeatureJson.get("contributingVariant").asBoolean());
        assertFalse(parsed.get(0).getExomiserCandidateFeatures().getInvalidFields()
                .contains("CONTRIBUTING_VARIANT"));
        assertFalse(parsed.get(1).getExomiserCandidateFeatures().getInvalidFields()
                .contains("CONTRIBUTING_VARIANT"));
    }

    @Test
    void rejectsGeneRowWhenOverlappingEntrezAndSymbolIdentityConflict() throws Exception {
        Path output = temporary.resolve("conflicting-gene-identifiers");
        Files.createDirectories(output);
        Files.write(output.resolve("result.genes.tsv"), (
                "#RANK\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\tEXOMISER_GENE_COMBINED_SCORE\n"
                        + "1\tGENE2\t101\tAD\t0.90\n"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tENTREZ_GENE_ID\tMOI\t"
                        + "EXOMISER_VARIANT_SCORE\n"
                        + "1\t1\t100\tA\tT\tGENE1\t101\tAD\t0.70\n"
        ).getBytes(StandardCharsets.UTF_8));

        ExomiserCandidateFeatures features = new ExomiserOutputParser()
                .parse(output, "S1", "15.1.0")
                .get(0)
                .getExomiserCandidateFeatures();

        assertEquals("CONFLICT_RANK_ENTREZ_MOI_GENE_SYMBOL", features.getGeneRowMatchStrategy());
        assertNull(features.getGeneSourceOrdinal());
        assertNull(features.getGeneCombinedScore());
        assertTrue(features.getInvalidFields().contains(
                "GENE_ROW_MATCH:CONFLICT_RANK_ENTREZ_MOI_GENE_SYMBOL"
        ));
    }

    @Test
    void unknownMoiRemainsRawAndMatchesGeneRowWithoutMasqueradingAsNormalized() throws Exception {
        Path output = temporary.resolve("unknown-moi");
        Files.createDirectories(output);
        Files.write(output.resolve("result.genes.tsv"), (
                "#RANK\tGENE_SYMBOL\tMOI\tEXOMISER_GENE_COMBINED_SCORE\n"
                        + "1\tGENE1\tcustom mode\t0.90\n"
        ).getBytes(StandardCharsets.UTF_8));
        Files.write(output.resolve("result.variants.tsv"), (
                "#RANK\tCONTIG\tSTART\tREF\tALT\tGENE_SYMBOL\tMOI\tEXOMISER_VARIANT_SCORE\n"
                        + "1\t1\t100\tA\tT\tGENE1\tCUSTOM-MODE\t0.70\n"
        ).getBytes(StandardCharsets.UTF_8));

        ExomiserCandidateFeatures features = new ExomiserOutputParser()
                .parse(output, "S1", "15.1.0")
                .get(0)
                .getExomiserCandidateFeatures();

        assertEquals("CUSTOM-MODE", features.getRawModeOfInheritance());
        assertNull(features.getNormalizedModeOfInheritance());
        assertEquals("RANK_SYMBOL_MOI", features.getGeneRowMatchStrategy());
        assertEquals(0.90d, features.getGeneCombinedScore());
        assertTrue(features.getInvalidFields().contains("MOI:UNRECOGNIZED"));
    }

    private void assertFeatureTamperRejected(ObjectMapper mapper,
                                             VariantInterpretation interpretation,
                                             String field,
                                             Object replacement,
                                             String expectedCode) {
        ObjectNode tree = mapper.valueToTree(interpretation);
        ObjectNode features = (ObjectNode) tree.get("exomiserCandidateFeatures");
        if (replacement instanceof Integer) {
            features.put(field, ((Integer) replacement).intValue());
        } else {
            features.put(field, String.valueOf(replacement));
        }
        JsonMappingException failure = assertThrows(
                JsonMappingException.class,
                () -> mapper.treeToValue(tree, VariantInterpretation.class)
        );
        assertTrue(failure.getMessage().contains(expectedCode));
    }
}
