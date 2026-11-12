package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** SYNTHETIC / TEST_ONLY representation checks; no provider calls or reference approval. */
class Pm5ContextPreparationTest {
    @Test void newVersionRetainsStrictMissenseEligibilityAndSeparateConfirmation() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules(); var fixture = new Ps1TestCatalog(mapper);
        var ps1Doc = fixture.document();
        var ps1 = new ReviewedRuleContextPreparation(mapper, new ReviewedRuleBundle(mapper, ps1Doc, fixture.canonicalSha256(ps1Doc)).identity());
        fixture.configurePm5(); var doc = fixture.document();
        var bundle = new ReviewedRuleBundle(mapper, doc, fixture.canonicalSha256(doc));
        var preparation = new ReviewedRuleContextPreparation(mapper, bundle.identity());
        assertTrue(preparation.selectionPolicy().startsWith("REVIEWED_RULE_CONTEXT@4.0.0:"));
        assertFalse(preparation.accepts(ps1.selectionPolicy())); assertFalse(ps1.accepts(preparation.selectionPolicy()));
        var snv = RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh38", "11", 105, "A", "C"));
        var raw = fixture.annotation(0, false);
        var annotation = mapper.treeToValue(raw, TranscriptConsequence.class);
        assertTrue(preparation.eligible(snv, annotation)); assertTrue(ps1.eligible(snv, annotation));
        for (String[] allele : new String[][]{{"A", "AC"}, {"AC", "A"}, {"AC", "GT"}}) {
            var invalid = RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh38", "11", 105, allele[0], allele[1]));
            assertFalse(preparation.eligible(invalid, annotation));
        }
        for (String effect : new String[]{"synonymous_variant", "start_lost", "frameshift_variant", "missense_variant&splice_region_variant"})
            assertFalse(preparation.eligible(snv, mapper.treeToValue(raw.deepCopy().put("consequence", effect), TranscriptConsequence.class)));
        assertFalse(preparation.eligible(RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh37", "11", 105, "A", "C")), annotation));
        assertThrows(IllegalArgumentException.class, () -> bundle.evaluate(MissenseComparisonRuntime.PS1, mapper.createObjectNode(), snv));
    }
}
