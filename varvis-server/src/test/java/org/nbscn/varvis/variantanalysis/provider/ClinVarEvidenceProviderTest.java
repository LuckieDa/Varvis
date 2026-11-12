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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClinVarEvidenceProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void compactEnabledAndAvailableUsesCompactBeforeTabix() throws Exception {
        Path compact = compactIndex("1\t100\tA\tG\tPathogenic\tcriteria_provided\tGENE1\t"
                + "VCV000001\tDisease A\t2026-01-01\tClinVar compact tiny");
        ClinVarEvidenceProvider provider = provider(
                properties(compact, null, true, true),
                Collections.singletonList(tabixLine("Benign"))
        );

        EvidenceItem evidence = evidence(provider.annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("LOCAL_CLINVAR_COMPACT", evidence.getDetails().get("source"));
        assertEquals("EXACT_ALLELE_COMPACT_INDEX", evidence.getDetails().get("matchType"));
        assertEquals("Pathogenic", evidence.getLabel());
    }

    @Test
    void compactDisabledKeepsLegacyTabixProviderBehavior() throws Exception {
        Path tabix = indexedFile("clinvar.vcf.gz");
        ClinVarEvidenceProvider provider = provider(
                properties(null, tabix, false, true),
                Collections.singletonList(tabixLine("Likely_pathogenic"))
        );

        EvidenceItem evidence = evidence(provider.annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("LOCAL_CLINVAR", evidence.getDetails().get("source"));
        assertEquals("exact_allele", evidence.getDetails().get("matchType"));
        assertEquals("Likely_pathogenic", evidence.getLabel());
    }

    @Test
    void compactMissingFallsBackToTabixWhenAllowed() throws Exception {
        Path tabix = indexedFile("clinvar.vcf.gz");
        ClinVarEvidenceProvider provider = provider(
                properties(tempDir.resolve("missing.tsv"), tabix, true, true),
                Collections.singletonList(tabixLine("Pathogenic"))
        );

        EvidenceItem evidence = evidence(provider.annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("LOCAL_CLINVAR", evidence.getDetails().get("source"));
        assertEquals("Pathogenic", evidence.getLabel());
    }

    @Test
    void compactMissingReturnsUnavailableWhenFallbackDisabled() throws Exception {
        Path tabix = indexedFile("clinvar.vcf.gz");
        ClinVarEvidenceProvider provider = provider(
                properties(tempDir.resolve("missing.tsv"), tabix, true, false),
                Collections.singletonList(tabixLine("Pathogenic"))
        );

        EvidenceItem evidence = evidence(provider.annotate(
                Collections.singletonList(variant(key("G"))),
                context()
        ), key("G"));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
        assertEquals("LOCAL_CLINVAR_COMPACT", evidence.getDetails().get("source"));
    }

    @Test
    void grch37UsesNativeTabixEvenWhenGrch38CompactIndexIsEnabled() throws Exception {
        Path compact = compactIndex("1\t100\tA\tG\tBenign\tcriteria_provided\tGENE1\t"
                + "VCV000001\tDisease A\t2026-01-01\tClinVar compact tiny");
        Path grch37 = indexedFile("clinvar.grch37.vcf.gz");
        VariantAnalysisProperties properties = properties(compact, null, true, true);
        properties.getClinVar().setGrch37File(grch37.toString());
        ClinVarEvidenceProvider provider = provider(properties, Collections.singletonList(tabixLine("Pathogenic")));
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh37", "1", 100, "A", "G");

        EvidenceItem evidence = evidence(provider.annotate(
                Collections.singletonList(variant(key)),
                new AnalysisContext("analysis", GenomeAssembly.GRCh37, AnalysisMode.WES)
        ), key);

        assertEquals("LOCAL_CLINVAR", evidence.getDetails().get("source"));
        assertEquals("Pathogenic", evidence.getLabel());
        assertEquals("GRCh37", evidence.getDetails().get("configuredResourceAssembly"));
    }

    private ClinVarEvidenceProvider provider(VariantAnalysisProperties properties,
                                             List<String> tabixLines) {
        return new ClinVarEvidenceProvider(
                properties,
                new ClinVarCompactIndexProvider(properties),
                new ClinVarTabixProvider(properties, (file, key) -> tabixLines)
        );
    }

    private VariantAnalysisProperties properties(Path compact,
                                                 Path tabix,
                                                 boolean compactEnabled,
                                                 boolean allowTabixFallback) {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setVersion("ClinVar local");
        if (tabix != null) {
            properties.getClinVar().setGrch38File(tabix.toString());
        }
        properties.getClinVar().getCompactIndex().setEnabled(compactEnabled);
        if (compact != null) {
            properties.getClinVar().getCompactIndex().setFile(compact.toString());
        }
        properties.getClinVar().getCompactIndex().setSourceVersion("ClinVar compact tiny");
        properties.getClinVar().getCompactIndex().setAllowTabixFallback(allowTabixFallback);
        return properties;
    }

    private Path compactIndex(String record) throws IOException {
        Path index = tempDir.resolve("clinvar.compact.tsv");
        Files.write(index, java.util.Arrays.asList(
                "chrom\tpos\tref\talt\tsignificance\treviewStatus\tgene\tvariationId\t"
                        + "conditionName\tlastEvaluated\tsourceVersion",
                record
        ), StandardCharsets.UTF_8);
        return index;
    }

    private Path indexedFile(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[] {1});
        Files.write(tempDir.resolve(name + ".tbi"), new byte[] {1});
        return file;
    }

    private String tabixLine(String significance) {
        return "chr1\t100\tVCV000001\tA\tG\t.\tPASS\t"
                + "ALLELEID=1;CLNSIG=" + significance + ";CLNREVSTAT=criteria_provided";
    }

    private EvidenceItem evidence(Map<NormalizedVariantKey, List<EvidenceItem>> result,
                                  NormalizedVariantKey key) {
        return result.get(key).get(0);
    }

    private NormalizedVariantKey key(String alternate) {
        return new NormalizedVariantKey("GRCh38", "1", 100, "A", alternate);
    }

    private VariantInterpretation variant(NormalizedVariantKey key) {
        return new VariantInterpretation(
                "GRCh38:1:100:A:" + key.getAlternate(),
                new VariantQcObservation(
                        key, "proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", "SNV", "chr1|100|A|" + key.getAlternate() + "|1"
                ),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private AnalysisContext context() {
        return new AnalysisContext("analysis", GenomeAssembly.GRCh38, AnalysisMode.WES);
    }
}
