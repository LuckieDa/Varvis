package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Explicit synthetic service-port controls. No real PP4 adoption source is installed. */
class Pp1ContributionSnapshotTest {
    final ObjectMapper json = new ObjectMapper();
    final ObjectNode authority = json.createObjectNode().put("providerId", "TEST_ONLY_SYNTHETIC_INVENTORY")
            .put("version", "1").put("origin", "SERVICE_OWNED").put("scope", "CURRENT_EVALUATION_ONLY");
    ObjectNode binding() {
        var c = json.createObjectNode().put("contextId", "test-only-context").put("moi", "AUTOSOMAL_DOMINANT");
        c.putObject("scope").put("analysisDomain", "GERMLINE"); c.putObject("gene").put("symbol", "GENE1");
        c.putObject("disease").put("mondoId", "MONDO:0000001");
        c.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1")
                .put("position", 100).put("reference", "A").put("alternate", "G");
        return new Pp1AnalysisContext(json).bind(c, "candidate", "hypothesis", "a".repeat(64), List.of("ACMG_AMP_2015:PP1"));
    }
    ObjectNode inventory(ObjectNode binding) {
        var value = json.createObjectNode().put("schemaVersion", Pp1ContributionSnapshot.SCHEMA).put("analysisId", "analysis")
                .put("sourceIdentitySha256", Pp1ContributionSnapshot.digest(json, authority));
        var entry = value.putArray("entries").addObject().put("bindingIdentitySha256", Pp1ContributionSnapshot.digest(json, binding.path("identity")));
        entry.set("scope", binding.at("/ledger/scope").deepCopy()); entry.set("allele", binding.at("/ledger/allele").deepCopy());
        var coverage = entry.putObject("coverage").put("status", "COMPLETE").put("provenanceSha256", "b".repeat(64));
        coverage.putArray("checkedResultSha256s").add("c".repeat(64)); entry.putArray("contributions");
        return seal(value);
    }
    ObjectNode entry(ObjectNode inventory) { return (ObjectNode) inventory.at("/entries/0"); }
    ObjectNode seal(ObjectNode value) {
        var copy = value.deepCopy(); copy.remove("snapshotSha256");
        value.put("snapshotSha256", Pp1ContributionSnapshot.digest(json, copy)); return value;
    }
    ObjectNode contribution(ObjectNode value, String id, String criterion, int points) {
        var row = entry(value).withArray("contributions").addObject().put("contributionId", id.repeat(64))
                .put("criterionId", "ACMG_AMP_2015:" + criterion).put("status", "ADOPTED")
                .put("sourceResultSha256", "c".repeat(64)).put("points", points);
        row.putArray("eventIds"); row.putArray("exclusiveFactIds"); return row;
    }
    Pp1ContributionSnapshot capture(ObjectNode value) {
        var source = new Pp1ContributionSnapshot.Source() {
            public ObjectNode identity() { return authority.deepCopy(); }
            public ObjectNode capture(String analysis, ObjectNode recovered) { return value.deepCopy(); }
        };
        return Pp1ContributionSnapshot.capture(json, source, authority.deepCopy(), "analysis", json.createObjectNode());
    }
    ObjectNode apply(ObjectNode value, ObjectNode binding) { return capture(seal(value)).apply(binding, "analysis"); }
    void unknown(ObjectNode result) {
        assertEquals("UNKNOWN", result.at("/ledger/status").asText()); assertTrue(result.at("/ledger/pp4Points").isNull());
    }
    @Test void missingAuthorityCannotUseCallerOrHistoryLedger() {
        var declared = binding(); declared.withObject("/ledger").put("status", "KNOWN_EMPTY").put("pp4Points", 0);
        var checked = Pp1ContributionSnapshot.capture(json, null, json.createObjectNode(), "analysis", declared);
        unknown(checked.apply(declared, "analysis"));
    }
    @Test void anEmptyListWithoutExactCompleteInventoryRemainsUnknown() {
        var b = binding(); var value = inventory(b); value.putArray("entries"); unknown(apply(value, b));
    }
    @Test void onlyExplicitSyntheticCompleteInventoryCanEstablishKnownEmpty() {
        var b = binding(); var original = b.deepCopy(); var value = inventory(b); var checked = capture(value);
        value.withArray("entries").removeAll();
        var result = checked.apply(b, "analysis");
        assertEquals("KNOWN_EMPTY", result.at("/ledger/status").asText()); assertEquals("0", result.at("/ledger/pp4Points").asText());
        assertEquals("PP1_VERIFIED_CURRENT_CONTRIBUTION_INVENTORY", result.at("/ledger/reasonCode").asText());
        assertEquals(original, b); assertEquals("UNKNOWN", b.at("/ledger/status").asText());
    }
    @Test void omittedPp4DoesNotEraseAnAlreadyAdoptedCurrentContribution() {
        var b = binding(); var value = inventory(b); contribution(value, "d", "PP4", 4);
        var result = apply(value, b);
        assertEquals("KNOWN", result.at("/ledger/status").asText()); assertEquals(4, result.at("/ledger/pp4Points").asInt());
    }
    @ParameterizedTest @ValueSource(strings = {"0.5", "1.25", "0.123456789012345678901234567890123456789"})
    void exactFractionalAdoptionIsRetainedWithoutIntegerRestrictionOrRounding(String amount) {
        var b = binding(); var value = inventory(b);
        contribution(value, "d", "PP4", 0).put("points", amount);
        var result = apply(value, b);
        assertEquals("KNOWN", result.at("/ledger/status").asText());
        assertEquals(amount, result.at("/ledger/pp4Points").asText());
    }
    @Test void identicalRecordsAndFactIdsAreDeduplicatedWithoutDroppingPriorFamily() {
        var b = binding(); var value = inventory(b); var row = contribution(value, "d", "PP1", 1);
        row.withArray("eventIds").add("family-1").add("family-1"); row.withArray("exclusiveFactIds").add("fact-1");
        entry(value).withArray("contributions").add(row.deepCopy());
        var result = apply(value, b);
        assertEquals("KNOWN", result.at("/ledger/status").asText());
        assertEquals(1, result.at("/ledger/eventIds").size()); assertEquals("family-1", result.at("/ledger/eventIds/0").asText());
        assertEquals(1, result.at("/ledger/exclusiveFactIds").size());
    }
    @Test void conflictingDuplicateOrDoubleAllocationIsNotSummed() {
        for (String fault : List.of("ID_CONFLICT", "FACT_REUSE", "TWO_PP4")) {
            var b = binding(); var value = inventory(b); var first = contribution(value, "d", "PP4", 2);
            first.withArray("exclusiveFactIds").add("fact-1");
            var second = contribution(value, fault.equals("ID_CONFLICT") ? "d" : "e", "PP4", 1);
            if (fault.equals("FACT_REUSE")) second.withArray("exclusiveFactIds").add("fact-1");
            unknown(apply(value, b));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"analysis", "candidate", "hypothesis", "disease", "allele", "context", "source", "criteria"})
    void differentCurrentBindingCannotBorrowTheCompleteInventory(String fault) {
        var b = binding(); var value = inventory(b); var changed = b.deepCopy();
        if (fault.equals("analysis")) { unknown(capture(value).apply(changed, "other-analysis")); return; }
        var identity = changed.withObject("/identity");
        switch (fault) {
            case "candidate" -> identity.put("candidateId", "other");
            case "hypothesis" -> identity.put("hypothesisId", "other");
            case "disease" -> identity.withObject("/preparedContext/disease").put("mondoId", "MONDO:0000002");
            case "allele" -> identity.withObject("/preparedContext/variant/evidenceQueryAllele").put("position", 101);
            case "context" -> identity.withObject("/preparedContext").put("contextId", "other");
            case "source" -> identity.put("sourceSnapshotSha256", "f".repeat(64));
            case "criteria" -> identity.withArray("enabledCriteria").add("ACMG_AMP_2015:PP4");
            default -> fail(fault);
        }
        unknown(apply(value, changed));
    }
    @Test void declaredScopeAndAlleleMustAgreeEvenAfterSnapshotResealing() {
        for (Consumer<ObjectNode> mutate : List.<Consumer<ObjectNode>>of(
                e -> e.withObject("/scope").put("diseaseId", "MONDO:0000002"), e -> e.withObject("/allele").put("position", 101),
                e -> e.withObject("/coverage").put("status", "UNKNOWN"), e -> e.withObject("/coverage").remove("provenanceSha256"),
                e -> e.withObject("/coverage").putArray("checkedResultSha256s"))) {
            var b = binding(); var value = inventory(b); mutate.accept(entry(value)); unknown(apply(value, b));
        }
    }
    @Test void pointRangeUnadoptedOrUnpinnedSourceCannotGrantCapacity() {
        for (String fault : List.of("OVER", "NEGATIVE", "UNADOPTED", "UNPINNED", "PP1_IDENTITY_MISSING")) {
            var b = binding(); var value = inventory(b); var row = contribution(value, "d", fault.equals("PP1_IDENTITY_MISSING") ? "PP1" : "PP4", 1);
            if (fault.equals("OVER")) row.put("points", 6);
            if (fault.equals("NEGATIVE")) row.put("points", -1);
            if (fault.equals("UNADOPTED")) row.put("status", "PENDING");
            if (fault.equals("UNPINNED")) row.put("sourceResultSha256", "f".repeat(64));
            unknown(apply(value, b));
        }
    }
    @Test void otherOriginsAndTamperedCaptureNeverBecomeCurrentAuthority() {
        for (String origin : List.of("HISTORY", "AUTHOR", "REQUEST")) {
            var b = binding(); authority.put("origin", origin); unknown(apply(inventory(b), b));
        }
        authority.put("origin", "SERVICE_OWNED"); var b = binding(); var value = inventory(b); value.put("analysisId", "other");
        unknown(capture(value).apply(b, "analysis"));
    }
    @Test void changingInventoryChangesThePinnedPreparationIdentity() {
        var b = binding(); var left = inventory(b); var right = inventory(b); contribution(right, "d", "PP4", 4); seal(right);
        assertNotEquals(capture(left).summary(), capture(right).summary());
        var stale = left.deepCopy(); stale.withArray("entries").removeAll(); unknown(capture(stale).apply(b, "analysis"));
    }
}
