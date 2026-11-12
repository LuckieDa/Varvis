package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaddTabixProviderTest {

    @Test
    void disabledCaddReturnsUnavailableWithoutFailing() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getCadd().setEnabled(false);

        EvidenceItem evidence = evidence(new CaddTabixProvider(properties));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
    }

    @Test
    void missingCaddResourceReturnsUnavailableWithoutFailing() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getCadd().setEnabled(true);
        properties.getCadd().setGrch38SnvFile("D:/does/not/exist/cadd.tsv.gz");

        EvidenceItem evidence = evidence(new CaddTabixProvider(properties));

        assertEquals("UNAVAILABLE", evidence.getApplicability());
    }

    @Test
    void providerSafetyLimitSkipsExcessVariantsBeforeLocalLookup() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getCadd().setEnabled(true);
        properties.getCadd().setMaxCandidates(1);
        properties.getCadd().setGrch38SnvFile("D:/does/not/exist/cadd.tsv.gz");
        VariantInterpretation first = variant("GRCh38:1:100:A:G", 100, "G");
        VariantInterpretation second = variant("GRCh38:1:200:A:G", 200, "G");

        Map<NormalizedVariantKey, List<EvidenceItem>> result = new CaddTabixProvider(properties).annotate(
                java.util.Arrays.asList(first, second),
                new AnalysisContext("test", GenomeAssembly.GRCh38, AnalysisMode.WES)
        );

        assertEquals("UNAVAILABLE", result.get(first.getObservation().getKey()).get(0).getApplicability());
        assertEquals("NOT_APPLICABLE", result.get(second.getObservation().getKey()).get(0).getApplicability());
        assertEquals("CADD lookup skipped", result.get(second.getObservation().getKey()).get(0).getLabel());
    }

    @Test
    void grch37QueryReturnsAssemblyUnsupportedBeforeResourceLookup() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getCadd().setEnabled(true);
        properties.getCadd().setGrch38SnvFile("D:/does/not/exist/cadd.tsv.gz");
        VariantInterpretation variant = variant("GRCh37:1:100:A:G", "GRCh37", 100, "G");

        Map<NormalizedVariantKey, List<EvidenceItem>> result = new CaddTabixProvider(properties).annotate(
                Collections.singletonList(variant),
                new AnalysisContext("test", GenomeAssembly.GRCh37, AnalysisMode.WES)
        );
        EvidenceItem evidence = result.get(variant.getObservation().getKey()).get(0);

        assertEquals("ASSEMBLY_UNSUPPORTED", evidence.getApplicability());
        assertEquals("GRCh37", evidence.getDetails().get("requestedAssembly"));
        assertEquals("GRCh38", evidence.getDetails().get("configuredResourceAssembly"));
        assertEquals(Boolean.FALSE, evidence.getDetails().get("providerInvoked"));
    }

    private EvidenceItem evidence(CaddTabixProvider provider) {
        VariantInterpretation variant = variant("GRCh38:1:100:A:G", 100, "G");
        Map<NormalizedVariantKey, List<EvidenceItem>> result = provider.annotate(
                Collections.singletonList(variant),
                new AnalysisContext("test", GenomeAssembly.GRCh38, AnalysisMode.WES)
        );
        return result.get(variant.getObservation().getKey()).get(0);
    }

    static VariantInterpretation variant(String id, int position, String alternate) {
        return variant(id, "GRCh38", position, alternate);
    }

    static VariantInterpretation variant(String id, String assembly, int position, String alternate) {
        return new VariantInterpretation(
                id,
                new VariantQcObservation(
                        new NormalizedVariantKey(assembly, "1", position, "A", alternate),
                        "proband",
                        "0/1",
                        30,
                        99,
                        "18,12",
                        0.4d,
                        "PASS",
                        "SNV",
                        "chr1|" + position + "|A|" + alternate + "|1"
                ),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }
}
