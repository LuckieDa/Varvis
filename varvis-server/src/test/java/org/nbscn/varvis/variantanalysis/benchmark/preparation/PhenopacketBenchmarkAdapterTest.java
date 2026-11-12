package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.benchmark.manifest.BenchmarkRunGroup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhenopacketBenchmarkAdapterTest {

    private final PhenopacketBenchmarkAdapter adapter = new PhenopacketBenchmarkAdapter();
    private final PhenopacketImportContext context = new PhenopacketImportContext(
            "synthetic-dataset", "2026.01", "synthetic-source", "https://example.invalid/phenopacket",
            "synthetic-test-license");

    @Test
    void extractsStableObservedAndExcludedHpoAndCanonicalSnv() throws Exception {
        PhenopacketImportResult result = adapter.importJson(resource("phenopacket-case-alpha.synthetic.json"), context);
        assertTrue(result.isEligible(), result.getStatus() + " " + result.getExclusionReasons() + " " + result.getIssues());
        assertEquals(Arrays.asList("HP:0001250"), ids(result.getCaseData().getObservedHpo()));
        assertEquals(Arrays.asList("HP:0001298"), ids(result.getCaseData().getExcludedHpo()));
        assertEquals("GRCh38", result.getCaseData().getDatasetCase().getAssembly());
        assertEquals("1", result.getCaseData().getDatasetCase().getTruthVariants().get(0)
                .getCanonicalIdentity().getChromosome());
        assertTrue(result.getIssues().stream().anyMatch(issue -> "NON_HPO_TERM".equals(issue.getCode())));
        assertNotNull(result.getCaseData().getDatasetCase().getPreparationProvenance());
    }

    @Test
    void extractsMultipleTruthVariantsAndSmallIndel() throws Exception {
        PhenopacketImportResult result = adapter.importJson(resource("phenopacket-case-beta.synthetic.json"), context);
        assertTrue(result.isEligible(), result.getStatus() + " " + result.getExclusionReasons() + " " + result.getIssues());
        assertEquals(2, result.getCaseData().getDatasetCase().getTruthVariants().size());
        assertEquals("AT", result.getCaseData().getDatasetCase().getTruthVariants().get(0)
                .getCanonicalIdentity().getReference());
    }

    @Test
    void supportsPhenopacketStoreSchemaVersionAndGeneContextShape() {
        String json = "{\"schemaVersion\":\"2.0.2\",\"id\":\"store-like-case\","
                + "\"phenotypicFeatures\":[{\"type\":{\"id\":\"HP:0001250\"}}],"
                + "\"interpretations\":[{\"diagnosis\":{\"genomicInterpretations\":[{"
                + "\"variantInterpretation\":{\"variationDescriptor\":{"
                + "\"geneContext\":{\"valueId\":\"HGNC:1\",\"symbol\":\"SYNGENE\"},"
                + "\"vcfRecord\":{\"genomeAssembly\":\"hg38\",\"chrom\":\"1\","
                + "\"pos\":1,\"ref\":\"A\",\"alt\":\"G\"}}}}]}}]}]}";
        PhenopacketImportResult result = adapter.importJson(json, context);
        assertTrue(result.isEligible(), result.getStatus() + " " + result.getExclusionReasons() + " " + result.getIssues());
        assertEquals("SYNGENE", result.getCaseData().getTruthGenes().get(0));
        assertEquals("HGNC:1", result.getCaseData().getTruthGeneIds().get(0));
        assertEquals("GRCh38", result.getCaseData().getDatasetCase().getAssembly());
    }

    @Test
    void excludesUnsafeOrIncompleteTruthWithoutGuessing() {
        String prefix = "{\"schemaVersion\":\"2\",\"id\":\"synthetic-invalid\","
                + "\"phenotypicFeatures\":[{\"type\":{\"id\":\"HP:0000001\"}}],"
                + "\"truthGene\":\"SYNGENE\",\"variationDescriptor\":{\"expressions\":[{\"value\":\"NM_1:c.1A>G\"}]}}";
        PhenopacketImportResult result = adapter.importJson(prefix, context);
        assertEquals(PhenopacketEligibilityStatus.EXCLUDED, result.getStatus());
        assertEquals(PhenopacketExclusionReason.HGVS_ONLY_TRUTH, result.getExclusionReasons().get(0));
    }

    @Test
    void missingVersionOrObservedHpoIsStructured() {
        PhenopacketImportResult missingVersion = adapter.importJson("{\"id\":\"x\"}", context);
        assertEquals(PhenopacketEligibilityStatus.EXCLUDED, missingVersion.getStatus());
        assertEquals(PhenopacketExclusionReason.UNSUPPORTED_SCHEMA_VERSION,
                missingVersion.getExclusionReasons().get(0));
        String noHpo = "{\"schemaVersion\":\"2\",\"id\":\"x\",\"truthGene\":\"SYN\","
                + "\"interpretations\":[{\"vcfRecord\":{\"genomeAssembly\":\"GRCh38\",\"chrom\":\"1\",\"pos\":1,\"ref\":\"A\",\"alt\":\"G\"}}]}";
        assertEquals(PhenopacketExclusionReason.NO_USABLE_OBSERVED_HPO,
                adapter.importJson(noHpo, context).getExclusionReasons().get(0));
    }

    @Test
    void g2RequiresExplicitHumanReviewedInput() throws Exception {
        PhenopacketImportResult source = adapter.importJson(resource("phenopacket-case-alpha.synthetic.json"), context);
        BenchmarkRunGroup g1 = PhenopacketHpoArmFactory.g1(source, "config-fingerprint");
        assertEquals("SOURCE_PROVIDED", g1.getHpoInputMode());
        assertEquals("HUMAN_REVIEWED", PhenopacketHpoArmFactory.g2("synthetic-case-alpha",
                new HumanReviewedHpoInput(Arrays.asList("HP:0001250"), "doctor-confirmed", "synthetic review"),
                "config-fingerprint").getHpoInputMode());
        assertFalse(g1.getHpoTerms().isEmpty());
    }

    @Test
    void datasetImportKeepsIndividualFailureAndDetectsDuplicateCaseId() throws Exception {
        PhenopacketDatasetImportResult result = new PhenopacketDatasetImporter(adapter)
                .importJsonRecords(Arrays.asList(resource("phenopacket-case-alpha.synthetic.json"),
                        resource("phenopacket-case-alpha.synthetic.json"), "not-json"), context);
        assertEquals(3, result.getResults().size());
        assertEquals(1, result.getEligibleCount());
        assertTrue(result.getResults().get(1).getExclusionReasons().contains(PhenopacketExclusionReason.DUPLICATE_CASE_ID));
        assertEquals(PhenopacketEligibilityStatus.INVALID, result.getResults().get(2).getStatus());
    }

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/benchmark/" + name)) {
            if (input == null) throw new IOException("missing fixture");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private java.util.List<String> ids(java.util.List<PhenopacketHpoTerm> terms) {
        java.util.List<String> result = new java.util.ArrayList<String>();
        for (PhenopacketHpoTerm term : terms) result.add(term.getId());
        return result;
    }
}
