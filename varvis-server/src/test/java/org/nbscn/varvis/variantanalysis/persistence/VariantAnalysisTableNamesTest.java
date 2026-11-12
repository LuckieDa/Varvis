package org.nbscn.varvis.variantanalysis.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantAnalysisTableNamesTest {

    @Test
    void rewritesOnlyKnownAnalysisTablesIntoTheConfiguredAcceptancePrefix() {
        VariantAnalysisTableNames names = new VariantAnalysisTableNames("exomiser_acceptance_");

        String sql = names.rewrite(
                "INSERT INTO variant_analysis_job SELECT * FROM variant_analysis_interpretation "
                        + "JOIN exomiser_analysis_job metadata ON 1 = 1"
        );

        assertTrue(sql.contains("exomiser_acceptance_variant_analysis_job"));
        assertTrue(sql.contains("exomiser_acceptance_variant_analysis_interpretation"));
        assertTrue(sql.contains("exomiser_acceptance_exomiser_analysis_job"));
        assertFalse(sql.contains("INSERT INTO variant_analysis_job"));
        assertEquals("variant_analysis_job", new VariantAnalysisTableNames("").job());
    }

    @Test
    void rejectsUnsafeOrOverlongAcceptancePrefixes() {
        assertThrows(IllegalArgumentException.class, () -> new VariantAnalysisTableNames("bad-prefix"));
        assertThrows(IllegalArgumentException.class,
                () -> new VariantAnalysisTableNames("abcdefghijklmnopqrstuvwxyzabcdef"));
    }
}
