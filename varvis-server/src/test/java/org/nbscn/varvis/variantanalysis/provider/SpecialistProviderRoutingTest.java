package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpecialistProviderRoutingTest {

    private final VariantAnalysisProperties properties = new VariantAnalysisProperties();
    private final AnalysisContext context = new AnalysisContext("test", GenomeAssembly.GRCh38, AnalysisMode.WES);

    @Test
    void alphaMissenseQueriesOnlyMissenseVariants() {
        AlphaMissenseProvider provider = new AlphaMissenseProvider(properties);

        assertEquals(
                "UNAVAILABLE",
                provider.annotate(Collections.singletonList(variant("missense_variant")), context)
                        .values().iterator().next().get(0).getApplicability()
        );
        assertEquals(
                "NOT_APPLICABLE",
                provider.annotate(Collections.singletonList(variant("synonymous_variant")), context)
                        .values().iterator().next().get(0).getApplicability()
        );
    }

    @Test
    void spliceAiQueriesOnlySpliceRelevantConsequences() {
        SpliceAiProvider provider = new SpliceAiProvider(properties);

        assertEquals(
                "DISABLED",
                provider.annotate(Collections.singletonList(variant("splice_donor_variant")), context)
                        .values().iterator().next().get(0).getApplicability()
        );
        assertEquals(
                "NOT_APPLICABLE",
                provider.annotate(Collections.singletonList(variant("missense_variant")), context)
                        .values().iterator().next().get(0).getApplicability()
        );
    }

    @Test
    void spliceAiMissingResourceIsUnavailableWhenEnabled() {
        properties.getSpliceAi().setEnabled(true);
        SpliceAiProvider provider = new SpliceAiProvider(properties);

        assertEquals(
                "UNAVAILABLE",
                provider.annotate(Collections.singletonList(variant("splice_donor_variant")), context)
                        .values().iterator().next().get(0).getApplicability()
        );
    }

    private VariantInterpretation variant(String consequence) {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
        return new VariantInterpretation(
                "GRCh38:1:100:A:G",
                new VariantQcObservation(
                        key, "proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", "SNV", "chr1|100|A|G|1"
                ),
                Arrays.asList(new TranscriptConsequence(null, null, null, null, null, consequence)),
                Collections.emptyList()
        );
    }
}
