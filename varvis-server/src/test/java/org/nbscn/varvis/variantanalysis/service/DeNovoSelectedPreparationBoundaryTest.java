package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.DeNovoApplicabilityPolicy;
import org.nbscn.varvis.variantanalysis.provider.DeNovoLocalEvidenceEvaluator;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses real preparation machinery with synthetic VCF/reference/transport; no scientific run. */
class DeNovoSelectedPreparationBoundaryTest {
    @TempDir Path temporary;
    private SelectedEvidencePreparationTest.Prepared prepared(boolean blocked) throws Exception {
        var fixture = new SelectedEvidencePreparationTest(); fixture.temporary = temporary;
        return fixture.prepare(false, blocked, true, 1);
    }
    private DeNovoApplicabilityPolicy policy(SelectedEvidencePreparationTest.Prepared p) throws Exception {
        byte[] source = "SYNTHETIC source boundary fixture, not knowledge".getBytes(StandardCharsets.UTF_8);
        var n = p.mapper().createObjectNode().put("schemaVersion", DeNovoApplicabilityPolicy.SCHEMA).put("policyId", "SYNTHETIC-boundary")
                .put("version", "1").put("materialClass", "SYNTHETIC").put("allowAssumedParentage", true).put("expertPolicyStatus", "GENERAL_ALLOWED");
        n.putObject("scope").put("hgncId", "HGNC:4827").put("diseaseId", "MONDO:0009715").put("inheritance", "AD");
        n.putArray("sources").addObject().put("sourceId", "s").put("sha256", sha(source)).put("locator", "fixture line 1").put("quote", "SYNTHETIC source boundary fixture");
        var rule = n.putArray("conditions").addObject().put("conditionId", "c").put("category", "CONSISTENT");
        rule.putArray("presentAll").add("HP:0001250"); rule.putArray("absentAll"); rule.putArray("sourceIds").add("s");
        byte[] bytes = p.mapper().writeValueAsBytes(n);
        return DeNovoApplicabilityPolicy.load(bytes, sha(bytes), Map.of("s", source), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
    }

    @Test void publicEntryReusesPreparedIdentityWithoutAnotherNormalizationOrMutatingRanking() throws Exception {
        var p = prepared(false); var id = p.original().getCandidateId();
        var before = p.mapper().valueToTree(p.original()); int beforeCalls = p.calls().get();
        var evaluator = new DeNovoLocalEvidenceEvaluator(p.mapper()); var policy = policy(p);
        var first = evaluator.evaluate(p.result(), id, "context-one", policy, List.of());
        assertEquals("NOT_EVALUATED", first.result().status());
        assertTrue(first.blockers().contains("DENOVO_INHERITANCE_NOT_SUPPORTED"), "The shared fixture is AR, not a new AD truth set");
        assertEquals(first, evaluator.evaluate(p.result(), id, "context-one", policy, List.of()));
        assertEquals(beforeCalls, p.calls().get()); assertEquals(before, p.mapper().valueToTree(p.original()));
        assertNotEquals(p.result().context(id, "context-one").at("/variant/inputAllele"),
                p.result().context(id, "context-one").at("/variant/evidenceQueryAllele"));
    }

    @Test void publishedSourceGateRunsBeforeNewPolicyAndReports() throws Exception {
        var p = prepared(true); var evaluator = new DeNovoLocalEvidenceEvaluator(p.mapper());
        var error = assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(p.result(), p.original().getCandidateId(), "context-one", null, List.of()));
        assertEquals("SYNTHETIC_SOURCE_CHANGED", error.getMessage());
    }

    @Test void unselectedCandidateCannotEnterNewEvaluator() throws Exception {
        var p = prepared(false); var evaluator = new DeNovoLocalEvidenceEvaluator(p.mapper());
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluate(p.result(), "not-in-selected-top-n", "context-one", null, List.of()));
    }

    @Test void rawPolicyIdentitySurvivesCallerMutation() throws Exception {
        var p = prepared(false); var policy = policy(p); byte[] raw = policy.originalBytes(); String pin = policy.sha256();
        raw[0] = 0; ObjectNode snapshot = policy.snapshot(); snapshot.put("policyId", "modified");
        assertEquals(pin, sha(policy.originalBytes())); assertEquals("SYNTHETIC-boundary@1", policy.id());
    }
    private static String sha(byte[] raw) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
}
