package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PredictionSpecificationScopeTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode original() throws Exception {
        return (ObjectNode) mapper.readTree("""
        {"entId":"GN900","entContent":{"version":"1.0","states":[{"name":"Released","current":true}]},
         "ld":{"Gene":[{"entContent":{"HGNC":{"symbol":"TEST","hgnc_id":"HGNC:123"}}}],
          "RuleSet":[{"entContent":{"genes":[{"gene":"TEST","diseases":[{"preferredMondoId":"MONDO:0000001","preferredModeOfInheritance":"Autosomal dominant inheritance"}]}]}}],
          "CriteriaCode":[{"entContent":{"label":"PP3","gene":["TEST"],"disease":["MONDO:0000001"]}},
                          {"entContent":{"label":"BP4","gene":["TEST"],"disease":["MONDO:0000001"]}}]}}
        """);
    }
    ObjectNode context(String disease, String mode) {
        var c = mapper.createObjectNode().put("moi", mode);
        c.putObject("gene").put("hgncId", "HGNC:123"); c.putObject("disease").put("mondoId", disease); return c;
    }
    PredictionSpecializationGuard guard(ObjectNode original, boolean sources) throws Exception {
        var raw = mapper.createObjectNode(); raw.putArray("data").add(original);
        byte[] bytes = mapper.writeValueAsBytes(raw); Files.write(root.resolve("raw.json"), bytes);
        var catalog = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA);
        catalog.putObject("metadata").put("status", "COMPLETE").put("entryCount", 1);
        var e = catalog.putArray("entries").addObject().put("specificationId", "GN900").put("version", "1.0");
        e.putObject("scope").putArray("hgncIds").add("HGNC:123");
        // This BA1-only scope is deliberately different; predictions must use their own raw clauses.
        e.withObject("scope").putArray("mondoIds").add("MONDO:9999999");
        e.putObject("source").put("rawRef", "raw.json").put("rawSha256", PredictionCalibration.sha256(bytes)).put("jsonPointer", "/data/0");
        bytes = mapper.writeValueAsBytes(catalog); Files.write(root.resolve("catalog.json"), bytes);
        return PredictionSpecializationGuard.load(mapper, root.resolve("catalog.json"), PredictionCalibration.sha256(bytes), sources ? root : null);
    }
    @Test void otherDiseaseCanProceedButExactClauseAndOtherUsersStayProtected() throws Exception {
        var g = guard(original(), true);
        assertEquals("BLOCKED", g.checkPredictions(context("MONDO:0000001", "AUTOSOMAL_DOMINANT")).path("status").asText());
        var other = g.checkPredictions(context("MONDO:0000002", "AUTOSOMAL_DOMINANT"));
        assertEquals("NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT", other.path("status").asText());
        assertEquals(2, other.path("scopeChecks").size());
        assertEquals("BLOCKED", g.check(context("MONDO:0000002", "AUTOSOMAL_DOMINANT")).path("status").asText());
        assertEquals("BLOCKED", guard(original(), false).checkPredictions(context("MONDO:0000002", "AUTOSOMAL_DOMINANT")).path("status").asText());
    }
    @Test void inheritanceOnlyExcludedWithExplicitSourceAndKnownContext() throws Exception {
        var g = guard(original(), true);
        assertEquals("NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT", g.checkPredictions(context("MONDO:0000001", "AUTOSOMAL_RECESSIVE")).path("status").asText());
        assertEquals("BLOCKED", g.checkPredictions(context("MONDO:0000001", "UNKNOWN")).path("status").asText());
    }
    @Test void missingClauseScopeInheritsRulesetWithoutInventingThreshold() throws Exception {
        var o = original(); for (var c : o.at("/ld/CriteriaCode")) ((ObjectNode)c.path("entContent")).remove("disease");
        assertEquals("OUTSIDE_EXPLICIT_SCOPE", PredictionSpecificationScope.relation(o, context("MONDO:0000002", "AUTOSOMAL_DOMINANT"), "PP3"));
        assertEquals("MATCHING_CLAUSE_REQUIRES_SELECTION", PredictionSpecificationScope.relation(o, context("MONDO:0000001", "AUTOSOMAL_DOMINANT"), "PP3"));
    }
    @ParameterizedTest @ValueSource(strings={"GENE","DISEASE","MODE","MODE_CONFLICT","CLAUSE_MISSING","CLAUSE_WIDER","RULESET"})
    void uncertaintyCannotFallBackToGeneralPrediction(String kind) throws Exception {
        var o = original(); var c = (ObjectNode)o.at("/ld/CriteriaCode/0/entContent");
        var d = (ObjectNode)o.at("/ld/RuleSet/0/entContent/genes/0/diseases/0");
        switch (kind) {
            case "GENE" -> c.put("gene", "TEST");
            case "DISEASE" -> c.put("disease", "MONDO:0000001");
            case "MODE" -> d.remove("preferredModeOfInheritance");
            case "MODE_CONFLICT" -> d.putArray("modesOfInheritance").addObject().put("modeOfInheritance", "Autosomal recessive inheritance");
            case "CLAUSE_MISSING" -> c.put("label", "BA1");
            case "CLAUSE_WIDER" -> d.put("preferredMondoId", "MONDO:0000002");
            case "RULESET" -> ((ObjectNode)o.at("/ld/RuleSet/0/entContent")).remove("genes");
        }
        assertNotEquals("OUTSIDE_EXPLICIT_SCOPE", PredictionSpecificationScope.relation(o, context("MONDO:0000001", "AUTOSOMAL_DOMINANT"), "PP3"));
    }
    @Test void eitherCriterionOrAdditionalSpecificationProtectsSharedRoute() throws Exception {
        var o = original(); ((ObjectNode)o.at("/ld/CriteriaCode/1/entContent")).putArray("disease").add("MONDO:0000002");
        assertEquals("BLOCKED", guard(o,true).checkPredictions(context("MONDO:0000002", "AUTOSOMAL_DOMINANT")).path("status").asText());
    }
    @Test void sourceTamperingCannotLoad() throws Exception {
        guard(original(),true); Files.writeString(root.resolve("raw.json"), "{}");
        String sha = PredictionCalibration.sha256(Files.readAllBytes(root.resolve("catalog.json")));
        assertThrows(IllegalArgumentException.class, () -> PredictionSpecializationGuard.load(mapper, root.resolve("catalog.json"), sha, root));
    }
}
