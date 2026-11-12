package org.nbscn.varvis.variantanalysis.provider;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AlphaGenomeProviderTest {

    @Test
    void retriesTransientFailureAndCachesSuccessfulSummary() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getAlphaGenome().setEnabled(true);
        AtomicInteger calls = new AtomicInteger();
        AlphaGenomeProvider provider = new AlphaGenomeProvider(properties, key -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("temporary failure");
            }
            return Collections.<String, Object>singletonMap("strongestEffect", "RNA_SEQ decrease");
        });

        EvidenceItem first = provider.enhance(variant("intron_variant"));
        EvidenceItem second = provider.enhance(variant("intron_variant"));

        assertEquals("APPLICABLE", first.getApplicability());
        assertEquals(2, first.getDetails().get("attemptCount"));
        assertEquals("APPLICABLE", second.getApplicability());
        assertEquals(2, calls.get());
    }

    @Test
    void keepsDisabledAndOutOfScopeCasesStructured() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        AtomicInteger calls = new AtomicInteger();
        AlphaGenomeProvider provider = new AlphaGenomeProvider(properties, key -> {
            calls.incrementAndGet();
            return Collections.emptyMap();
        });

        assertEquals("UNAVAILABLE", provider.enhance(variant("splice_donor_variant")).getApplicability());
        assertEquals("NOT_APPLICABLE", provider.enhance(variant("missense_variant")).getApplicability());
        assertEquals(0, calls.get());
    }

    private VariantInterpretation variant(String consequence) {
        NormalizedVariantKey key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
        return new VariantInterpretation(
                "GRCh38:1:100:A:G",
                new VariantQcObservation(
                        key, "proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", "SNV", "chr1|100|A|G|1"
                ),
                Collections.singletonList(new TranscriptConsequence(null, null, null, null, null, consequence)),
                Collections.emptyList()
        );
    }
}
