package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Replays actual failed normal-business JSON, without restoring scientific handles or editing its files. */
class FullRnaCurrentUseMissingStrengthTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter hashes = new RuntimeEvidenceV2Adapter(mapper);
    private record Data(ObjectNode batch, ObjectNode attempt, ObjectNode criterion, byte[] original, Path file) { }
    private Data actual() throws Exception {
        String value = System.getProperty("varvis.t07.missingStrength.source");
        org.junit.jupiter.api.Assumptions.assumeTrue(value != null, "Explicit saved normal-business failure artifact required");
        if (value == null) throw new IllegalStateException("ACTUAL_NORMAL_RNA_SOURCE_REQUIRED");
        Path file = Path.of(value); byte[] bytes = Files.readAllBytes(file);
        var doc = java.util.Objects.requireNonNull(mapper.readTree(bytes)); var batch = (ObjectNode) doc.path("localBatch");
        var attempt = (ObjectNode) batch.path("results").path(0);
        var criterion = (ObjectNode) attempt.at("/localBatch/criteria/0");
        assertEquals("ACMG_AMP_2015:PVS1", criterion.path("criterionId").asText());
        var raw = batch.at(criterion.at("/details/modulePointer").asText());
        assertEquals("NOT_EVALUATED", raw.at("/evaluation/evaluationResult/status").asText());
        assertFalse(raw.path("evaluation").has("appliedStrength"));
        return new Data(batch, attempt, criterion, bytes, file);
    }
    @Test void actualAtomicNeWithOmittedStrengthRetainsItsExactCauseWithoutRewritingTheOriginal() throws Exception {
        var data = actual(); String before = data.batch().toString();
        var summary = FullRnaCurrentUse.project(mapper, data.batch(), data.attempt(), data.criterion());
        assertEquals("SOURCE_BOUND_FULL_RNA_CONSEQUENCE", summary.path("method").asText());
        assertEquals("NOT_EXECUTED", summary.path("evaluationState").asText());
        assertEquals("NOT_EVALUATED", data.criterion().path("status").asText());
        assertEquals("NONE", data.criterion().path("appliedStrength").asText());
        assertEquals("PVS1_ESCAPE_POLICY_INVALID", data.criterion().path("reasonCode").asText());
        assertEquals(before, data.batch().toString()); assertArrayEquals(data.original(), Files.readAllBytes(data.file()));
    }
    @Test void explicitlyNullStrengthIsNotTreatedAsTheOmittedAtomicNeField() throws Exception {
        var data = actual(); var raw = (ObjectNode) data.batch().at(data.criterion().at("/details/modulePointer").asText());
        ((ObjectNode) raw.path("evaluation")).putNull("appliedStrength"); refresh(data, raw);
        assertThrows(IllegalArgumentException.class, () -> FullRnaCurrentUse.project(mapper, data.batch(), data.attempt(), data.criterion()));
    }
    @Test void aMetClaimCannotBorrowTheOmittedStrengthException() throws Exception {
        var data = actual(); var raw = (ObjectNode) data.batch().at(data.criterion().at("/details/modulePointer").asText());
        ((ObjectNode) raw.at("/evaluation/evaluationResult")).put("status", "MET");
        ((ObjectNode) raw.at("/fullRnaCurrentUse/claim")).put("originalStatus", "MET");
        data.criterion().put("status", "MET"); refresh(data, raw);
        assertThrows(IllegalArgumentException.class, () -> FullRnaCurrentUse.project(mapper, data.batch(), data.attempt(), data.criterion()));
    }
    private void refresh(Data data, ObjectNode raw) {
        var proof = (ObjectNode) raw.path("fullRnaCurrentUse"); var claim = (ObjectNode) proof.path("claim");
        claim.put("evaluationSha256", hashes.canonicalSha256(raw.path("evaluation")));
        var ledger = (ObjectNode) data.attempt().path("fullRnaApplication");
        ((ObjectNode) ledger.path("criteria")).set("PVS1", claim.deepCopy()); seal(ledger, "ledgerSha256");
        proof.set("ledgerSha256", ledger.path("ledgerSha256")); seal(proof, "proofSha256");
        ((ObjectNode) data.criterion().path("details")).set("originalResult", raw.path("evaluation").deepCopy());
    }
    private void seal(ObjectNode object, String field) { object.remove(field); object.put(field, hashes.canonicalSha256(object)); }
}
