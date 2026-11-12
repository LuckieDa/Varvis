package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class Bs1LocalExomesTest {
    final ObjectMapper mapper = new ObjectMapper();
    final Bs1CoreTest contexts = new Bs1CoreTest();
    final Ba1LocalExomesTest facts = new Ba1LocalExomesTest();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    static final String SOURCE = "a".repeat(64);
    ObjectNode context(String gene) throws Exception {
        var c = contexts.context();
        var p = policy(gene);
        c.withObject("/gene").put("hgncId", p.at("/scope/hgncId").asText());
        c.withObject("/disease").put("mondoId", p.at("/scope/mondoId").asText());
        c.put("moi", p.at("/scope/modes/0").asText()); contexts.refresh(c); return c;
    }
    JsonNode policy(String gene) {
        return Bs1LocalExomes.policies(mapper).stream().filter(p -> p.path("policyId").asText().contains(gene)).findFirst().orElseThrow();
    }
    ObjectNode shared(ObjectNode context, Map<String,String> values, String filter) {
        var profile = GnomadPopulationInputProfile.loadLocalBa1();
        var carrier = facts.baseCarrier(context);
        carrier.set("measurements", mapper.valueToTree(profile.parse(filter, values, 0, 1).getMeasurements()));
        var q = context.at("/variant/evidenceQueryAllele");
        String raw = "chr" + q.path("contig").asText() + "\t" + q.path("position").asInt() + "\t.\t" + q.path("reference").asText()
                + "\t" + q.path("alternate").asText() + "\t.\t" + filter + "\t"
                + String.join(";", values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        var r = mapper.createObjectNode().put("executionSourceSha256", SOURCE).put("rawPopulationRecord", raw);
        r.set("evidenceQueryAllele", q.deepCopy()); r.set("normalization", context.at("/variant/normalization"));
        r.put("alleleId", adapter.canonicalAlleleId(q, r.path("normalization")));
        r.putObject("dataScope").put("policyId", "BA1_LOCAL_EXOMES_V1"); r.set("evidence", carrier);
        // BA1 may have no disease mapping; that must not block independent BS1 facts.
        r.putObject("evaluation").putObject("evaluationResult").put("status", "NOT_EVALUATED").put("reasonCode", "BA1_GENE_MAPPING_REQUIRED");
        return r;
    }
    ObjectNode run(ObjectNode c, ObjectNode ba1) {
        var settings = new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties();
        return Bs1Runtime.localExomes(mapper, settings, true).evaluateLocal(c, ba1, SOURCE);
    }
    @ParameterizedTest
    @CsvSource({"GN017,0.00003299999,NOT_MET,NONE", "GN017,0.000033,MET,STRONG",
            "COCH,0.0001999999,NOT_MET,NONE", "COCH,0.0002,MET,STRONG",
            "CDH23_0019501,0.00069999,NOT_MET,NONE", "CDH23_0019501,0.0007,MET,SUPPORTING",
            "CDH23_0019501,0.003,MET,STRONG", "USH2A,0.003,MET,STRONG"})
    void exactPublishedThresholdsInMultipleDominantAndRecessiveBackgrounds(String gene, String faf, String status, String strength) throws Exception {
        var c = context(gene); var ba1 = shared(c, facts.info(faf, "10000"), "PASS"); Bs1LocalExomes.sharedObservation(mapper, c, ba1, SOURCE); var result = run(c, ba1);
        assertEquals(status, result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(strength, result.at("/evaluation/appliedStrength").asText());
        assertEquals("EXOMES", result.at("/dataScope/dataset").asText());
        assertTrue(result.path("observationReusedFromBa1").asBoolean());
        assertFalse(result.at("/evidence/measurements").toString().contains("gnomad.joint."));
    }
    @Test void qualityAndBindingFailuresCannotBecomeNotMet() throws Exception {
        var c = context("GN017");
        for (String kind : List.of("FILTER", "DOT", "AN", "AC", "AF", "FAF", "SOURCE", "QUERY", "NORMALIZER", "RAW")) {
            var values = facts.info("0.00004", "10000"); String filter = "PASS";
            switch (kind) {
                case "FILTER" -> filter = "AC0";
                case "DOT" -> filter = ".";
                case "AN" -> values.put("AN_nfe", "1999");
                case "AC" -> values.put("AC_nfe", "10001");
                case "AF" -> values.put("AF_nfe", "0.9");
                case "FAF" -> values.put("faf95_nfe", "0.001");
            }
            var ba1 = shared(c, values, filter);
            switch (kind) {
                case "SOURCE" -> ba1.put("executionSourceSha256", "b".repeat(64));
                case "QUERY" -> ba1.withObject("/evidenceQueryAllele").put("position", 8);
                case "NORMALIZER" -> ba1.set("normalization", ((ObjectNode) ba1.path("normalization")).deepCopy().put("methodVersion", "invalid"));
                case "RAW" -> ba1.put("rawPopulationRecord", ba1.path("rawPopulationRecord").asText().replace("AC_nfe=200", "AC_nfe=201"));
            }
            var result = run(c, ba1);
            assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText(), kind + result.toPrettyString());
            assertFalse(result.at("/evaluation/evaluationResult/reasonCode").asText().isBlank());
        }
    }
    @Test void preservedJointPoliciesAndUnresolvedKnowledgeAreNotActivated() throws Exception {
        assertEquals(12, Bs1LocalExomes.policies(mapper).size());
        assertTrue(Bs1Runtime.formalPolicies(mapper).get(0).at("/statistic/metricId").asText().contains("joint"));
        var c = context("GN017");
        c.withObject("/gene").put("hgncId", "HGNC:1"); contexts.refresh(c);
        assertEquals("BS1_POLICY_NO_RECORD", run(c, mapper.createObjectNode()).at("/evaluation/evaluationResult/reasonCode").asText());
        c = context("GJB2");
        assertEquals("BS1_EXCEPTION_IDENTITY_UNRESOLVED", run(c, mapper.createObjectNode()).at("/evaluation/evaluationResult/reasonCode").asText());
        var altered = (ObjectNode) policy("GN017").deepCopy();
        altered.withObject("/tiers/0/conditions/0").put("decimal", "0.00001");
        assertEquals("BS1_LOCAL_SCOPE_INVALID", Bs1Policy.issue(altered));
    }
}
