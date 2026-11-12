package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClinVarCompactIndexProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void exactAlleleHitReturnsCompactEvidenceFields() throws Exception {
        Path index = compactIndex(record("chr1", 100, "A", "G", "Pathogenic"));

        EvidenceItem evidence = evidence(provider(index).annotate(
                Collections.singletonList(variant(key("1", 100, "A", "G"))),
                context()
        ), key("1", 100, "A", "G"));

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals("Pathogenic", evidence.getLabel());
        assertEquals("LOCAL_CLINVAR_COMPACT", evidence.getDetails().get("source"));
        assertEquals("EXACT_ALLELE_COMPACT_INDEX", evidence.getDetails().get("matchType"));
        assertEquals("Pathogenic", evidence.getDetails().get("clinicalSignificance"));
        assertEquals("criteria_provided", evidence.getDetails().get("reviewStatus"));
        assertEquals("GENE1", evidence.getDetails().get("geneSymbol"));
        assertEquals("VCV000001", evidence.getDetails().get("variationId"));
        assertEquals("Disease A", evidence.getDetails().get("conditionName"));
        assertEquals("2026-01-01", evidence.getDetails().get("lastEvaluated"));
        assertEquals("ClinVar compact tiny", evidence.getSourceVersion());
    }

    @Test
    void chrPrefixIsNormalizedForExactAlleleLookup() throws Exception {
        Path index = compactIndex(record("chr1", 100, "A", "G", "Likely_pathogenic"));

        EvidenceItem evidence = evidence(provider(index).annotate(
                Collections.singletonList(variant(key("1", 100, "A", "G"))),
                context()
        ), key("1", 100, "A", "G"));

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals("Likely_pathogenic", evidence.getDetails().get("significance"));
    }

    @Test
    void refMismatchDoesNotHit() throws Exception {
        Path index = compactIndex(record("1", 100, "C", "G", "Pathogenic"));

        EvidenceItem evidence = evidence(provider(index).annotate(
                Collections.singletonList(variant(key("1", 100, "A", "G"))),
                context()
        ), key("1", 100, "A", "G"));

        assertNoRecordNotBenign(evidence);
    }

    @Test
    void altMismatchDoesNotHit() throws Exception {
        Path index = compactIndex(record("1", 100, "A", "T", "Pathogenic"));

        EvidenceItem evidence = evidence(provider(index).annotate(
                Collections.singletonList(variant(key("1", 100, "A", "G"))),
                context()
        ), key("1", 100, "A", "G"));

        assertNoRecordNotBenign(evidence);
    }

    @Test
    void multiallelicSiteRequiresExactAlternateRow() throws Exception {
        Path index = compactIndex(
                record("1", 100, "A", "T", "Benign"),
                record("1", 100, "A", "G", "Uncertain_significance")
        );

        EvidenceItem evidence = evidence(provider(index).annotate(
                Collections.singletonList(variant(key("1", 100, "A", "G"))),
                context()
        ), key("1", 100, "A", "G"));

        assertEquals("APPLICABLE", evidence.getApplicability());
        assertEquals("Uncertain_significance", evidence.getDetails().get("clinicalSignificance"));
        assertFalse(evidence.getLabel().toLowerCase().contains("pathogenic"));
    }

    @Test
    void batchLookupReturnsResultForEveryInputVariant() throws Exception {
        Path index = compactIndex(record("1", 100, "A", "G", "Pathogenic"));
        NormalizedVariantKey hit = key("1", 100, "A", "G");
        NormalizedVariantKey miss = key("1", 101, "C", "T");

        Map<NormalizedVariantKey, List<EvidenceItem>> result = provider(index).annotate(
                Arrays.asList(variant(hit), variant(miss)),
                context()
        );

        assertEquals(2, result.size());
        assertEquals("APPLICABLE", evidence(result, hit).getApplicability());
        assertNoRecordNotBenign(evidence(result, miss));
    }

    @Test
    void missingCompactFileReturnsUnavailableNotLowRisk() throws Exception {
        VariantAnalysisProperties properties = baseProperties(tempDir.resolve("missing.tsv"));

        EvidenceItem evidence = evidence(new ClinVarCompactIndexProvider(properties).annotate(
                Collections.singletonList(variant(key("1", 100, "A", "G"))),
                context()
        ), key("1", 100, "A", "G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertNull(evidence.getRawScore());
        assertEquals("UNAVAILABLE", evidence.getDetails().get("status"));
        assertFalse(evidence.getLabel().toLowerCase().contains("benign"));
    }

    @Test
    void disabledCompactIndexPreservesDisabledExecutionAxis() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);

        EvidenceItem evidence = evidence(new ClinVarCompactIndexProvider(properties).annotate(
                Collections.singletonList(variant(key("1", 100, "A", "G"))),
                context()
        ), key("1", 100, "A", "G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("DISABLED", evidence.getStatus());
        assertEquals("DISABLED", evidence.getDetails().get("resourceExecutionStatus"));
        assertEquals("NOT_QUERIED", evidence.getDetails().get("queryOutcomeStatus"));
    }

    @Test
    void preservesMultipleRecordsAndConditionStructure() throws Exception {
        Path index = compactIndex(
                record("1", 100, "A", "G", "Pathogenic"),
                "1\t100\tA\tG\tLikely_pathogenic\tcriteria_provided\tGENE1\tVCV000002\t"
                        + "Disease B|Disease C\t2026-01-02\tClinVar compact tiny"
        );
        NormalizedVariantKey key = key("1", 100, "A", "G");

        List<EvidenceItem> evidence = provider(index).annotate(
                Collections.singletonList(variant(key)), context()).get(key);

        assertEquals(2, evidence.size());
        assertEquals("VCV000001", evidence.get(0).getDetails().get("variationId"));
        assertEquals("VCV000002", evidence.get(1).getDetails().get("variationId"));
        assertEquals("Disease B|Disease C", evidence.get(1).getDetails().get("conditionNames"));
        assertEquals(2, ((List<?>) evidence.get(1).getDetails().get("conditions")).size());
        assertTrue(evidence.get(0).getStableIdentityKey().contains("VCV000001"));
    }

    @Test
    void nonGrch38InputReturnsAssemblyUnsupported() throws Exception {
        Path index = compactIndex(record("1", 100, "A", "G", "Pathogenic"));
        NormalizedVariantKey grch37 = new NormalizedVariantKey("GRCh37", "1", 100, "A", "G");

        EvidenceItem evidence = evidence(provider(index).annotate(
                Collections.singletonList(variant(grch37)),
                new AnalysisContext("analysis", GenomeAssembly.GRCh37, AnalysisMode.WES)
        ), grch37);

        assertEquals("ASSEMBLY_UNSUPPORTED", evidence.getApplicability());
        assertEquals("ASSEMBLY_UNSUPPORTED", evidence.getDetails().get("status"));
    }

    private void assertNoRecordNotBenign(EvidenceItem evidence) {
        assertEquals("NO_RECORD", evidence.getApplicability());
        assertEquals("no_record", evidence.getDetails().get("matchType"));
        assertNull(evidence.getRawScore());
        assertFalse(evidence.getLabel().toLowerCase().contains("benign"));
    }

    private ClinVarCompactIndexProvider provider(Path index) {
        return new ClinVarCompactIndexProvider(baseProperties(index));
    }

    private VariantAnalysisProperties baseProperties(Path index) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setVersion("ClinVar local");
        properties.getClinVar().getCompactIndex().setEnabled(true);
        properties.getClinVar().getCompactIndex().setFile(index.toString());
        properties.getClinVar().getCompactIndex().setSourceVersion("ClinVar compact tiny");
        return properties;
    }

    private Path compactIndex(String... records) throws IOException {
        Path index = tempDir.resolve("clinvar.compact.tsv");
        String header = "chrom\tpos\tref\talt\tsignificance\treviewStatus\tgene\tvariationId\t"
                + "conditionName\tlastEvaluated\tsourceVersion";
        List<String> lines = new java.util.ArrayList<String>();
        lines.add(header);
        lines.addAll(Arrays.asList(records));
        Files.write(index, lines, StandardCharsets.UTF_8);
        return index;
    }

    private String record(String chrom, int pos, String ref, String alt, String significance) {
        return chrom + "\t" + pos + "\t" + ref + "\t" + alt + "\t" + significance
                + "\tcriteria_provided\tGENE1\tVCV000001\tDisease A\t2026-01-01\tClinVar compact tiny";
    }

    private EvidenceItem evidence(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                  NormalizedVariantKey key) {
        return result.get(key).get(0);
    }

    private NormalizedVariantKey key(String chromosome, int position, String reference, String alternate) {
        return new NormalizedVariantKey("GRCh38", chromosome, position, reference, alternate);
    }

    private VariantInterpretation variant(NormalizedVariantKey key) {
        return new VariantInterpretation(
                "GRCh38:" + key.getChromosome() + ":" + key.getPosition()
                        + ":" + key.getReference() + ":" + key.getAlternate(),
                new VariantQcObservation(
                        key, "proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", "SNV", "chr" + key.getChromosome() + "|" + key.getPosition()
                        + "|" + key.getReference() + "|" + key.getAlternate() + "|1"
                ),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private AnalysisContext context() {
        return new AnalysisContext("analysis", GenomeAssembly.GRCh38, AnalysisMode.WES);
    }
}
