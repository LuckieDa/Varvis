package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class Bp1ExplanationTest {
    private String explain(String code) throws Exception {
        var method = LocalEvidenceBatchRuntime.class.getDeclaredMethod("explanation", String.class, String.class);
        method.setAccessible(true);
        var result = method.invoke(null, "NOT_EVALUATED", code);
        if (result instanceof String text) return text;
        throw new AssertionError("Explanation must be a String");
    }
    @Test void knownSourceStatesDoNotBecomeMissingDatabaseOrBenignAssertions() throws Exception {
        assertTrue(explain("BP1_SOURCE_NOT_APPLICABLE").contains("明确不适用"));
        assertTrue(explain("BP1_PUBLISHED_SOURCE_CONDITIONS_UNSUPPORTED").contains("软件"));
        assertTrue(explain("BP1_PUBLISHED_STRENGTH_UNSUPPORTED").contains("强度"));
        assertTrue(explain("BP1_PUBLISHED_ATTACHMENTS_UNSUPPORTED").contains("附件"));
        assertTrue(explain("BP1_SOURCE_APPLICABILITY_CONFLICT").contains("冲突"));
        assertTrue(explain("BP1_SOURCE_APPLICABILITY_UNRESOLVED").contains("未明确"));
        assertNotEquals(explain("PUBLISHED_KNOWLEDGE_BACKGROUND_NOT_COVERED"), explain("PUBLISHED_KNOWLEDGE_TRANSCRIPT_NOT_COVERED"));
    }
}
