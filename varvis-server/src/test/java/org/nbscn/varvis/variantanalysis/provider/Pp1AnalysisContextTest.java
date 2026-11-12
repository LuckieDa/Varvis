package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class Pp1AnalysisContextTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Pp1AnalysisContext binding = new Pp1AnalysisContext(json);
    private final List<String> profile = List.of("ACMG_AMP_2015:PS2", "ACMG_AMP_2015:PP1");
    private ObjectNode context() {
        var c = json.createObjectNode().put("contextId", "synthetic-context").put("moi", "AUTOSOMAL_DOMINANT");
        c.putObject("scope").put("analysisDomain", "GERMLINE");
        c.putObject("gene").put("symbol", "GENE1");
        c.putObject("disease").put("mondoId", "MONDO:0000001");
        c.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1")
                .put("position", 100).put("reference", "A").put("alternate", "G");
        return c;
    }
    private ObjectNode prepare(ObjectNode c) { return binding.bind(c, "candidate", "hypothesis", "a".repeat(64), profile); }
    @Test void absentPp4DoesNotEstablishAnEmptyCurrentContributionInventory() {
        var value = prepare(context());
        assertEquals("UNKNOWN", value.at("/ledger/status").asText());
        assertTrue(value.at("/ledger/pp4Points").isNull());
        assertEquals("PP1_CURRENT_CONTRIBUTION_SOURCE_UNAVAILABLE", value.at("/ledger/reasonCode").asText());
        assertEquals(value.at("/selection/selectionKey"), value.at("/ledger/selectionKey"));
        assertEquals("MONDO:0000001", value.at("/ledger/scope/diseaseId").asText());
        assertFalse(value.at("/ledger/scope").has("selectionKey"));
    }
    @Test void enabledButUnavailablePp4IsUnknownNeverZero() {
        var value = binding.bind(context(), "candidate", "hypothesis", "a".repeat(64),
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP4"));
        assertEquals("UNKNOWN", value.at("/ledger/status").asText());
        assertTrue(value.at("/ledger/pp4Points").isNull());
    }
    @Test void identityChangesForDifferentDiseaseAlleleHypothesisOrSources() {
        var c = context(); String key = prepare(c).at("/selection/selectionKey").asText();
        ((ObjectNode)c.path("disease")).put("mondoId", "MONDO:0000002");
        assertNotEquals(key, prepare(c).at("/selection/selectionKey").asText());
        c = context(); ((ObjectNode)c.at("/variant/evidenceQueryAllele")).put("position", 101);
        assertNotEquals(key, prepare(c).at("/selection/selectionKey").asText());
        assertNotEquals(key, binding.bind(context(), "candidate", "other", "a".repeat(64), profile).at("/selection/selectionKey").asText());
        assertNotEquals(key, binding.bind(context(), "candidate", "hypothesis", "b".repeat(64), profile).at("/selection/selectionKey").asText());
    }
    @Test void profileOrderDoesNotCreateANewEvaluation() {
        assertEquals(prepare(context()), binding.bind(context(), "candidate", "hypothesis", "a".repeat(64),
                List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PS2")));
    }
    @Test void resultDoesNotMutateOriginalPreparedContext() {
        var c = context(); var original = c.deepCopy(); var result = prepare(c);
        ((ObjectNode)result.at("/selection/allele")).put("position", 999);
        assertEquals(original, c); assertEquals(100, result.at("/ledger/allele/position").asInt());
    }
    @Test void indelAndXLinkedAreNotRejectedByInheritedPm3SnvGate() {
        var c = context(); c.put("moi", "X_LINKED_RECESSIVE");
        ((ObjectNode)c.at("/variant/evidenceQueryAllele")).put("contig", "X").put("reference", "AT");
        assertEquals("XLR", prepare(c).at("/selection/inheritance").asText());
    }
    @Test void unknownInheritanceAndMissingDiseaseDoNotBorrowContext() {
        var c = context(); c.put("moi", "UNKNOWN");
        assertEquals("PP1_INHERITANCE_METHOD_UNSUPPORTED", assertThrows(IllegalArgumentException.class, () -> prepare(c)).getMessage());
        var missing = context(); missing.remove("disease");
        assertThrows(IllegalArgumentException.class, () -> prepare(missing));
    }
    @Test void somaticOrUnnormalizedAllelesRemainExplicitlyRejected() {
        var c = context(); ((ObjectNode)c.path("scope")).put("analysisDomain", "SOMATIC");
        assertThrows(IllegalArgumentException.class, () -> prepare(c));
        var unsupported = context(); ((ObjectNode)unsupported.at("/variant/evidenceQueryAllele")).put("alternate", "<DEL>");
        assertEquals("PP1_NORMALIZED_ALLELE_REQUIRED", assertThrows(IllegalArgumentException.class, () -> prepare(unsupported)).getMessage());
    }
    @Test void missingOrDuplicatedProfileAndMalformedPinRejected() {
        assertThrows(IllegalArgumentException.class, () -> binding.bind(context(), "c", "h", "a".repeat(64), List.of()));
        assertThrows(IllegalArgumentException.class, () -> binding.bind(context(), "c", "h", "a".repeat(64), List.of("ACMG_AMP_2015:PP1", "ACMG_AMP_2015:PP1")));
        assertThrows(IllegalArgumentException.class, () -> binding.bind(context(), "c", "h", "unbound", profile));
    }
}
