package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.BoundedSequenceDeletion;
import static org.junit.jupiter.api.Assertions.*;

class PublishedPm1DeletionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void completeDomainIncludesBothEndpointsButPartialCoverageDoesNotEarnStrength() {
        assertResult("MACDEFGHIK*", "MACDHIK*", 5, 7, "MET", "MODERATE");
        assertResult("MACDEFGHIK*", "MACDHIK*", 4, 7, "NOT_EVALUATED", "NONE");
        assertResult("MACDEFGHIK*", "MACDHIK*", 8, 10, "NOT_MET", "NONE");
    }

    @Test void equivalentDeletionPositionsDoNotProveAnAmbiguousDomainWasRemoved() {
        assertResult("MAAAAC*", "MAAC*", 3, 4, "NOT_EVALUATED", "NONE");
    }

    @Test void requiresTheDeclaredBackgroundAndAReferenceAnchoredInFrameEdit() {
        var context = mapper.createObjectNode().put("moi", "X_LINKED_RECESSIVE");
        context.putObject("scope").put("analysisDomain", "GERMLINE");
        context.putObject("gene").put("hgncId", "HGNC:6990");
        context.putObject("disease").put("mondoId", "MONDO:0010726");
        assertNotNull(PublishedPm1Deletion.scope(context));
        context.put("moi", "AUTOSOMAL_DOMINANT");
        assertNull(PublishedPm1Deletion.scope(context));
        context.put("moi", "X_LINKED_DOMINANT");
        assertNotNull(PublishedPm1Deletion.scope(context));
        context.putObject("disease").put("mondoId", "MONDO:0000001");
        assertNull(PublishedPm1Deletion.scope(context));
        assertTrue(BoundedSequenceDeletion.extended("C" + "A".repeat(174), "C"));
        assertFalse(BoundedSequenceDeletion.extended("C" + "A".repeat(173), "C"));
        assertFalse(BoundedSequenceDeletion.extended("C" + "A".repeat(174), "A"));
        assertFalse(BoundedSequenceDeletion.extended("C" + "A".repeat(513), "C"));
    }

    private void assertResult(String reference, String altered, int start, int end, String status, String strength) {
        var domains = mapper.createArrayNode();
        domains.addObject().put("name", "TEST_ONLY").put("start", start).put("end", end);
        var actual = PublishedPm1Deletion.assessDomains(mapper, domains, reference, altered, "MODERATE", mapper.createObjectNode());
        assertEquals(status, actual.path("status").asText());
        assertEquals(strength, actual.path("appliedStrength").asText());
    }
}
