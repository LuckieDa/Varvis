package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Synthetic internal-port ownership checks, not a production adoption source. */
class Pp1ContributionSnapshotOwnershipTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ObjectNode authority = json.createObjectNode().put("providerId", "TEST_ONLY_OWNERSHIP_SOURCE")
            .put("version", "1").put("origin", "SERVICE_OWNED").put("scope", "CURRENT_EVALUATION_ONLY");

    private ObjectNode binding() {
        var context = json.createObjectNode().put("contextId", "test-only-context").put("moi", "AUTOSOMAL_DOMINANT");
        context.putObject("scope").put("analysisDomain", "GERMLINE");
        context.putObject("gene").put("symbol", "GENE1");
        context.putObject("disease").put("mondoId", "MONDO:0000001");
        context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1")
                .put("position", 100).put("reference", "A").put("alternate", "G");
        return new Pp1AnalysisContext(json).bind(context, "candidate", "hypothesis", "a".repeat(64),
                List.of("ACMG_AMP_2015:PP1"));
    }

    private ObjectNode emptyInventory(ObjectNode binding) {
        var inventory = json.createObjectNode().put("schemaVersion", Pp1ContributionSnapshot.SCHEMA)
                .put("analysisId", "analysis").put("sourceIdentitySha256", Pp1ContributionSnapshot.digest(json, authority));
        var entry = inventory.putArray("entries").addObject()
                .put("bindingIdentitySha256", Pp1ContributionSnapshot.digest(json, binding.path("identity")));
        entry.set("scope", binding.at("/ledger/scope").deepCopy());
        entry.set("allele", binding.at("/ledger/allele").deepCopy());
        var coverage = entry.putObject("coverage").put("status", "COMPLETE").put("provenanceSha256", "b".repeat(64));
        coverage.putArray("checkedResultSha256s").add("c".repeat(64));
        entry.putArray("contributions");
        inventory.put("snapshotSha256", Pp1ContributionSnapshot.digest(json, inventory));
        return inventory;
    }

    private void addSyntheticAllocationWithoutResealing(ObjectNode inventory) {
        var allocation = ((ObjectNode) inventory.at("/entries/0")).withArray("contributions").addObject()
                .put("contributionId", "d".repeat(64)).put("criterionId", "ACMG_AMP_2015:PP4")
                .put("status", "ADOPTED").put("sourceResultSha256", "c".repeat(64)).put("points", "4");
        allocation.putArray("eventIds"); allocation.putArray("exclusiveFactIds");
    }

    private Pp1ContributionSnapshot.Source stableSource(ObjectNode value) {
        return new Pp1ContributionSnapshot.Source() {
            @Override public ObjectNode identity() { return authority.deepCopy(); }
            @Override public ObjectNode capture(String analysisId, ObjectNode recovered) { return value; }
        };
    }

    @Test void identityCallbackCannotChangeAlreadyValidatedCaptureUnderItsOriginalDigest() throws Exception {
        var binding = binding();
        byte[] bindingBefore = json.writeValueAsBytes(binding);
        var sourceOwned = emptyInventory(binding);
        var expected = sourceOwned.deepCopy();
        byte[] expectedBytes = json.writeValueAsBytes(expected);
        var identityCalls = new AtomicInteger();
        var source = new Pp1ContributionSnapshot.Source() {
            @Override public ObjectNode identity() {
                if (identityCalls.incrementAndGet() == 2) addSyntheticAllocationWithoutResealing(sourceOwned);
                return authority.deepCopy();
            }
            @Override public ObjectNode capture(String analysisId, ObjectNode recovered) { return sourceOwned; }
        };

        var captured = Pp1ContributionSnapshot.capture(json, source, authority.deepCopy(), "analysis", json.createObjectNode());
        assertEquals(2, identityCalls.get(), "the mutation occurs in the post-validation identity check");
        assertEquals(1, sourceOwned.at("/entries/0/contributions").size(), "the source-side mutation must actually execute");
        assertEquals(expected.path("snapshotSha256"), sourceOwned.path("snapshotSha256"), "the source did not reseal its mutation");
        var retained = captured.originals();
        var unsigned = retained.deepCopy(); unsigned.remove("snapshotSha256");
        var applied = captured.apply(binding, "analysis");
        assertAll(
                () -> assertArrayEquals(expectedBytes, json.writeValueAsBytes(retained), "retain exactly the owned empty inventory"),
                () -> assertEquals(expected, retained),
                () -> assertEquals(Pp1ContributionSnapshot.digest(json, unsigned), retained.path("snapshotSha256").asText()),
                () -> assertEquals("KNOWN_EMPTY", applied.at("/ledger/status").asText()),
                () -> assertEquals("0", applied.at("/ledger/pp4Points").asText()),
                () -> assertArrayEquals(bindingBefore, json.writeValueAsBytes(binding), "applying never mutates its caller binding"));
    }

    @Test void recoveredInputAndLaterSourceMutationCannotAlterTheFrozenInventory() throws Exception {
        var binding = binding(); var sourceOwned = emptyInventory(binding); var expected = sourceOwned.deepCopy();
        var recovered = json.createObjectNode().put("callerOwned", "unchanged");
        byte[] recoveredBefore = json.writeValueAsBytes(recovered);
        var source = new Pp1ContributionSnapshot.Source() {
            @Override public ObjectNode identity() { return authority.deepCopy(); }
            @Override public ObjectNode capture(String analysisId, ObjectNode received) {
                received.put("callerOwned", "source changed its own received copy");
                return sourceOwned;
            }
        };
        var captured = Pp1ContributionSnapshot.capture(json, source, authority.deepCopy(), "analysis", recovered);
        addSyntheticAllocationWithoutResealing(sourceOwned);
        assertArrayEquals(recoveredBefore, json.writeValueAsBytes(recovered));
        assertEquals(expected, captured.originals());
        assertEquals("KNOWN_EMPTY", captured.apply(binding, "analysis").at("/ledger/status").asText());
    }

    @Test void returnedOriginalsAndAppliedBindingsHaveIndependentOwnership() throws Exception {
        var binding = binding(); byte[] bindingBefore = json.writeValueAsBytes(binding);
        var expected = emptyInventory(binding);
        var captured = Pp1ContributionSnapshot.capture(json, stableSource(expected), authority.deepCopy(), "analysis", json.createObjectNode());
        var exposed = captured.originals(); exposed.withArray("entries").removeAll();
        var applied = captured.apply(binding, "analysis");
        applied.withObject("/ledger").put("pp4Points", "4");
        applied.withObject("/identity").put("candidateId", "changed by consumer");
        assertEquals(expected, captured.originals());
        assertArrayEquals(bindingBefore, json.writeValueAsBytes(binding));
        var again = captured.apply(binding, "analysis");
        assertEquals("KNOWN_EMPTY", again.at("/ledger/status").asText());
        assertEquals("0", again.at("/ledger/pp4Points").asText());
        assertEquals("candidate", again.at("/identity/candidateId").asText());
    }
}
