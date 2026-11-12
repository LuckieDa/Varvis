package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic disease parameters and population facts, not a scientific adoption receipt. */
public class Bs1KnowledgeCatalogTest {
    final ObjectMapper mapper = new ObjectMapper();
    final Bs1LocalExomesTest local = new Bs1LocalExomesTest();
    @TempDir Path temporary;
    ObjectNode source() {
        return mapper.createObjectNode().put("sourceId", "synthetic-parameters").put("url", "https://example.org/synthetic")
                .put("version", "SYNTHETIC-1").put("sha256", "a".repeat(64)).put("locator", "synthetic/reference-controls");
    }
    public ObjectNode record(String gene, String disease, String moi) {
        var r = mapper.createObjectNode().put("recordId", gene + disease).put("version", "1").put("kind", "MAX_CREDIBLE_AF");
        r.putObject("scope").put("hgncId", gene).put("mondoId", disease).putArray("modes").add(moi);
        r.putArray("sources").add(source());
        r.putArray("supportedAncestries").add("nfe");
        var model = r.putObject("model").put("population", "SYNTHETIC_POPULATION").put("applicability", "MENDELIAN_RARE_DISEASE");
        for (var e : Map.of("prevalence", "0.001", "penetrance", "0.5", "maximumGeneContribution", "0.2", "maximumAlleleContribution", "0.1").entrySet())
            model.putObject(e.getKey()).put("value", e.getValue()).put("unit", "fraction").put("sourceId", "synthetic-parameters").put("locator", "SYNTHETIC:" + e.getKey());
        model.withObject("/prevalence").put("statistic", "POINT_PREVALENCE");
        r.putObject("exceptionSet").put("setId", "synthetic-empty").put("version", "1").put("criterionId", Bs1Policy.CRITERION)
                .put("status", "COMPLETE_IN_PINNED_SCOPE").put("sourceLocator", "SYNTHETIC_COMPLETE_SCOPE").putArray("entries");
        review(r); return r;
    }
    void review(ObjectNode r) {
        r.remove("review"); String hash = Bs1KnowledgeCatalog.hash(r);
        r.putObject("review").put("status", "REVIEWED_FOR_LOCAL_USE").put("contentSha256", hash)
                .put("reviewer", "SYNTHETIC_TEST_NOT_CLINICAL_APPROVAL").put("reviewedAt", "2026-10-09T00:00:00Z")
                .put("basis", "Synthetic arithmetic control only").put("dataScopePolicyId", "BS1_LOCAL_EXOMES_V1")
                .put("expertSpecificationCheck", "NO_APPLICABLE_EXPERT_SPECIFICATION").put("strength", "STRONG");
    }
    public ObjectNode bundle(ObjectNode... records) {
        var b = mapper.createObjectNode().put("schemaVersion", "bs1-knowledge-bundle-v1").put("version", "SYNTHETIC-1");
        var s = source(); s.remove("locator"); b.putArray("sources").add(s);
        var rows = b.putArray("records"); for (var r : records) rows.add(r);
        b.putArray("backgrounds"); return b;
    }
    public ObjectNode expertRecord(String gene, String disease, String symbol) {
        var r = record(gene, disease, "AUTOSOMAL_DOMINANT");
        r.put("kind", "EXPERT_POLICY").put("publicationStatus", "Released"); r.remove("model");
        var expert = ((ObjectNode) local.policy("GN017")).deepCopy();
        expert.withObject("/scope").put("hgncId", gene).put("mondoId", disease); r.set("expertPolicy", expert);
        r.putObject("relationship").put("symbol", symbol).put("diseaseLabel", "SYNTHETIC expert scope")
                .put("sourceId", "synthetic-parameters").put("specificationId", "GN900").put("specificationVersion", "1")
                .put("approvedOn", "2026-10-09T00:00:00Z").put("panel", "SYNTHETIC expert panel")
                .put("url", "https://cspec.clinicalgenome.org/cspec/ui/svi/doc/GN900");
        review(r); return r;
    }
    @Test void expertRelationshipRequiresAdoptedPolicyAndBoundSource() throws Exception {
        var r = expertRecord("HGNC:999001", "MONDO:0999001", "SYNTHETIC");
        var rows = load(bundle(r)).expertRelationships();
        assertEquals(1, rows.size()); assertFalse(rows.get(0).path("classificationIsGeneValidity").asBoolean(true));
        r.withObject("/relationship").put("sourceId", "unbound"); review(r);
        assertThrows(IllegalArgumentException.class, () -> load(bundle(r)).expertRelationships());
        r.remove("review"); assertTrue(load(bundle(r)).expertRelationships().isEmpty());
    }
    Bs1KnowledgeCatalog load(ObjectNode bundle) throws Exception {
        byte[] raw = mapper.writeValueAsBytes(bundle); Path file = temporary.resolve(UUID.randomUUID() + ".json"); Files.write(file, raw);
        return Bs1KnowledgeCatalog.load(file, Bs1KnowledgeCatalog.sha(raw));
    }
    ObjectNode context(ObjectNode record) throws Exception {
        var c = local.context("GN017"); c.withObject("/gene").put("hgncId", record.at("/scope/hgncId").asText());
        c.withObject("/disease").put("mondoId", record.at("/scope/mondoId").asText()); c.put("moi", record.at("/scope/modes/0").asText());
        local.contexts.refresh(c); return c;
    }
    Bs1Runtime runtime(Bs1KnowledgeCatalog catalog) {
        return Bs1Runtime.localExomes(mapper, new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties(), true, catalog);
    }
    @Test void publishedEquationsHaveIndependentAdAndArControls() {
        var r = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT");
        // By hand: .001*.2*.1/(2*.5) = .00002; sqrt(.001*.2/.5)*.1 = .002.
        assertEquals("0.00002", Bs1MaximumCredibleFrequency.calculate(mapper, "AUTOSOMAL_DOMINANT", r.path("model")).path("maximumCredibleAf").asText());
        assertEquals("0.002", Bs1MaximumCredibleFrequency.calculate(mapper, "AUTOSOMAL_RECESSIVE", r.path("model")).path("maximumCredibleAf").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"prevalence", "penetrance", "maximumGeneContribution", "maximumAlleleContribution"})
    void missingParameterNeverUsesDefault(String name) {
        var r = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT"); r.withObject("/model").remove(name);
        var calculation = Bs1MaximumCredibleFrequency.calculate(mapper, "AUTOSOMAL_DOMINANT", r.path("model"));
        assertEquals("BS1_DISEASE_PARAMETERS_MISSING", calculation.path("reasonCode").asText()); assertEquals(name, calculation.at("/missingParameters/0").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.1", "NaN", "1e-900000", "50%", "0.0000000000000000000000000000001"})
    void invalidParametersFailClosed(String value) {
        var r = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT"); r.withObject("/model/penetrance").put("value", value);
        assertEquals("BS1_MODEL_PARAMETER_INVALID", Bs1MaximumCredibleFrequency.calculate(mapper, "AUTOSOMAL_DOMINANT", r.path("model")).path("reasonCode").asText());
    }
    @Test void incidenceIsNotPrevalenceAndModelIsNotAdoption() {
        var r = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT"); r.withObject("/model/prevalence").put("statistic", "ANNUAL_INCIDENCE");
        assertEquals("BS1_PREVALENCE_STATISTIC_UNSUPPORTED", Bs1MaximumCredibleFrequency.calculate(mapper, "AUTOSOMAL_DOMINANT", r.path("model")).path("reasonCode").asText());
        r.remove("review"); assertEquals("BS1_KNOWLEDGE_REVIEW_REQUIRED", Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
    }
    @Test void reviewBindsValuesAndStrengthAndCannotBypassExpertScope() throws Exception {
        var r = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT"); r.withObject("/model/penetrance").put("value", "1");
        assertEquals("BS1_KNOWLEDGE_REVIEW_REQUIRED", Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
        review(r); r.withObject("/review").remove("strength");
        assertEquals("BS1_MODEL_STRENGTH_ADOPTION_REQUIRED", Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
        review(r); r.withObject("/review").remove("expertSpecificationCheck");
        assertEquals("BS1_EXPERT_SPECIFICATION_CHECK_REQUIRED", Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
        var existing = local.policy("GJB2"); var blocked = record(existing.at("/scope/hgncId").asText(), existing.at("/scope/mondoId").asText(), existing.at("/scope/modes/0").asText());
        assertEquals(Bs1LocalExomes.policies(mapper), load(bundle(blocked)).merge(Bs1LocalExomes.policies(mapper)));
    }
    @Test void arbitraryNewBackgroundsUseSameRuntimeAndFactsWithExactBoundary() throws Exception {
        var ad = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT");
        var ar = record("HGNC:999002", "MONDO:0999002", "AUTOSOMAL_RECESSIVE");
        var rt = runtime(load(bundle(ad, ar)));
        for (var r : List.of(ad, ar)) {
            var c = context(r); String boundary = r == ad ? "0.00002" : "0.002";
            var equal = rt.evaluateLocal(c, local.shared(c, local.facts.info(boundary, "10000"), "PASS"), Bs1LocalExomesTest.SOURCE);
            assertEquals("NOT_MET", equal.at("/evaluation/evaluationResult/status").asText(), equal.toPrettyString());
            var high = new BigDecimal(boundary).add(new BigDecimal("0.00000001")).toPlainString();
            var met = rt.evaluateLocal(c, local.shared(c, local.facts.info(high, "10000"), "PASS"), Bs1LocalExomesTest.SOURCE);
            assertEquals("MET", met.at("/evaluation/evaluationResult/status").asText(), met.toPrettyString());
            assertEquals(r, met.at("/preparation/policy/knowledgeRecord"));
            assertEquals(met, mapper.readTree(mapper.writeValueAsBytes(met)));
            var bad = rt.evaluateLocal(c, local.shared(c, local.facts.info(high, "10000"), "LowQual"), Bs1LocalExomesTest.SOURCE);
            assertEquals("BS1_LOCAL_FILTER_NOT_PASS", bad.at("/evaluation/evaluationResult/reasonCode").asText());
        }
        assertEquals(0, rt.counts().path("provider").asInt());
    }
    @Test void pinsDuplicatesSourceBindingAndIdentityInvalidateReuse() throws Exception {
        var r = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT"); var b = bundle(r);
        assertThrows(IllegalArgumentException.class, () -> load(bundle(r, r)));
        var modified = b.deepCopy(); modified.withObject("/sources/0").put("sha256", "b".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> load(modified));
        var file = temporary.resolve("pin.json"); Files.write(file, mapper.writeValueAsBytes(b));
        assertThrows(IllegalArgumentException.class, () -> Bs1KnowledgeCatalog.load(file, "f".repeat(64)));
        var before = runtime(load(b)).identity(); b.put("version", "2");
        assertNotEquals(before, runtime(load(b)).identity());
        var p = Bs1KnowledgeCatalog.policy(r); p.withObject("/tiers/0/conditions/0").put("decimal", "0.00000001");
        assertEquals("BS1_KNOWLEDGE_POLICY_DERIVATION_MISMATCH", Bs1Policy.issue(p));
    }
    @Test void expertRecordsUseExistingEvaluatorWithoutGeneCode() throws Exception {
        var r = record("HGNC:999003", "MONDO:0999003", "AUTOSOMAL_DOMINANT");
        r.put("kind", "EXPERT_POLICY").put("publicationStatus", "Released"); r.remove("model");
        var expert = ((ObjectNode) local.policy("GN017")).deepCopy(); expert.withObject("/scope").put("hgncId", "HGNC:999003").put("mondoId", "MONDO:0999003");
        r.set("expertPolicy", expert); review(r); var c = context(r);
        var result = runtime(load(bundle(r))).evaluateLocal(c, local.shared(c, local.facts.info("0.000033", "10000"), "PASS"), Bs1LocalExomesTest.SOURCE);
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        r.put("publicationStatus", "Under Review"); review(r);
        assertEquals("BS1_EXPERT_SPECIFICATION_NOT_RELEASED", Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
    }
    @Test void configuredRealBundleCanBeMeasuredOfflineWhenProvided() throws Exception {
        String file = System.getProperty("bs1.knowledge.test-file", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(file.isBlank(), "No task-local knowledge snapshot requested");
        var catalog = Bs1KnowledgeCatalog.load(Path.of(file), System.getProperty("bs1.knowledge.test-sha"));
        var rt = runtime(catalog); var reasons = new TreeMap<String, Integer>(); int executable = 0;
        for (var p : rt.policyRecords()) {
            String reason = Bs1Policy.issue(p);
            if (reason == null) executable++; else reasons.merge(reason, 1, (existingCount, addedCount) -> existingCount + addedCount);
        }
        int selectionReady = 0;
        for (var p : rt.policyRecords()) {
            if (Bs1Policy.issue(p) != null) continue;
            var c = context(mapper.createObjectNode().set("scope", p.path("scope")));
            if ("READY".equals(Bs1Policy.select(mapper, rt.policyRecords(), c).path("status").asText())) selectionReady++;
        }
        var report = mapper.createObjectNode().put("records", rt.policyRecords().size()).put("structurallyExecutable", executable)
                .put("selectionReady", selectionReady).put("backgrounds", catalog.backgroundCount());
        report.set("blockedReasons", mapper.valueToTree(reasons)); report.set("identity", rt.identity());
        Files.writeString(Path.of(System.getProperty("bs1.knowledge.test-report")), report.toPrettyString());
        assertTrue(catalog.backgroundCount() > 100); assertEquals(12, Bs1LocalExomes.policies(mapper).size());
        // This optional frozen panel must not impose its threshold on future bundles.
        if (!Boolean.getBoolean("bs1.knowledge.rasopathy-controls")) return;
        for (var p : rt.policyRecords()) {
            if (!"EXPERT_POLICY".equals(p.at("/knowledgeRecord/kind").asText())) continue;
            if (!"MONDO:0021060".equals(p.at("/scope/mondoId").asText())) continue;
            var c = context((ObjectNode) p.path("knowledgeRecord"));
            // Independent published RASopathy boundary, not derived from policy output.
            assertEquals("0.00025", p.at("/tiers/0/conditions/0/decimal").asText());
            for (var e : Map.of("0.0002499", "NOT_MET", "0.00025", "MET", "0.0002501", "MET").entrySet()) {
                var result = rt.evaluateLocal(c, local.shared(c, local.facts.info(e.getKey(), "10000"), "PASS"), Bs1LocalExomesTest.SOURCE);
                assertEquals(e.getValue(), result.at("/evaluation/evaluationResult/status").asText(), p.at("/knowledgeRecord/recordId").asText());
            }
            var bad = rt.evaluateLocal(c, local.shared(c, local.facts.info("0.0003", "10000"), "LowQual"), Bs1LocalExomesTest.SOURCE);
            assertEquals("NOT_EVALUATED", bad.at("/evaluation/evaluationResult/status").asText());
            c.withObject("/disease").put("mondoId", "MONDO:0999999"); local.contexts.refresh(c);
            assertNotEquals("READY", Bs1Policy.select(mapper, rt.policyRecords(), c).path("status").asText());
        }
    }

    @Test void configuredCrossDomainBoundsMatchFrozenSourceExpectations() throws Exception {
        String expectedFile = System.getProperty("bs1.knowledge.expected-file", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(expectedFile.isBlank(), "No frozen cross-domain source panel requested");
        var catalog = Bs1KnowledgeCatalog.load(Path.of(System.getProperty("bs1.knowledge.test-file")), System.getProperty("bs1.knowledge.test-sha"));
        var rt = runtime(catalog); var expected = mapper.readTree(Path.of(expectedFile).toFile());
        assertEquals(5, expected.size()); var checks = mapper.createArrayNode();
        for (var e : expected) {
            var matches = rt.policyRecords().stream().filter(p -> e.path("recordId").equals(p.at("/knowledgeRecord/recordId"))).toList();
            assertEquals(1, matches.size()); var p = matches.get(0);
            assertNull(Bs1Policy.issue(p)); assertEquals(e.path("hgncId"), p.at("/scope/hgncId"));
            assertEquals(e.path("mondoId"), p.at("/scope/mondoId"));
            var bound = new BigDecimal(e.path("fraction").asText()); var delta = new BigDecimal("0.0000000001");
            var c = context((ObjectNode)p.path("knowledgeRecord"));
            for (int direction : List.of(-1, 0, 1)) {
                String status = direction > 0 || direction == 0 && "GTE".equals(e.path("operator").asText()) ? "MET" : "NOT_MET";
                var faf = bound.add(delta.multiply(BigDecimal.valueOf(direction))).toPlainString();
                var result = rt.evaluateLocal(c, local.shared(c, local.facts.info(faf, "10000"), "PASS"), Bs1LocalExomesTest.SOURCE);
                assertEquals(status, result.at("/evaluation/evaluationResult/status").asText(), e.path("recordId").asText());
                checks.addObject().put("recordId", e.path("recordId").asText()).put("FAF95", faf).put("expected", status).set("result", result);
            }
            var bad = rt.evaluateLocal(c, local.shared(c, local.facts.info(bound.toPlainString(), "10000"), "LowQual"), Bs1LocalExomesTest.SOURCE);
            assertEquals("NOT_EVALUATED", bad.at("/evaluation/evaluationResult/status").asText());
            c.withObject("/disease").put("mondoId", "MONDO:0999999"); local.contexts.refresh(c);
            assertNotEquals("READY", Bs1Policy.select(mapper, rt.policyRecords(), c).path("status").asText());
        }
        Files.writeString(Path.of(System.getProperty("bs1.knowledge.boundary-report")), checks.toPrettyString());
    }

    @Test void unstructuredExpertScopeAndConflictingRelationshipsBlockGenericModel() throws Exception {
        var r = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT"); var b = bundle(r);
        b.putArray("expertScopes").addObject().put("hgncId", "HGNC:999001").put("mondoId", "*")
                .put("specificationId", "SYNTHETIC-EXPERT").put("version", "1").put("sourceId", "synthetic-parameters").put("status", "STRUCTURING_REQUIRED");
        assertThrows(IllegalArgumentException.class, () -> load(b));
        b.remove("expertScopes");
        var row = b.withArray("backgrounds").addObject().put("hgncId", "HGNC:999001").put("mondoId", "MONDO:0999001")
                .put("moi", "AUTOSOMAL_DOMINANT").put("reasonCode", "BS1_RELATIONSHIP_CONFLICT");
        var assertions = row.putArray("assertions");
        for (String status : List.of("Definitive", "Refuted")) assertions.addObject().put("hgncId", "HGNC:999001")
                .put("mondoId", "MONDO:0999001").put("moi", "AUTOSOMAL_DOMINANT").put("classification", status).put("sourceId", "synthetic-parameters");
        assertThrows(IllegalArgumentException.class, () -> load(b));
        b.withArray("records").removeAll();
        assertEquals("BS1_RELATIONSHIP_CONFLICT", load(b).explain("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT").path("reasonCode").asText());
    }

    @Test void populationScopeCannotBeAssumedOrAppliedToAnotherAncestry() throws Exception {
        var r = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT");
        r.remove("supportedAncestries"); review(r);
        assertEquals("BS1_MODEL_POPULATION_SCOPE_REQUIRED", Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)));
        r.putArray("supportedAncestries").add("eas"); review(r); var c = context(r);
        var result = runtime(load(bundle(r))).evaluateLocal(c, local.shared(c, local.facts.info("0.5", "10000"), "PASS"), Bs1LocalExomesTest.SOURCE);
        assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
    }

    @Test void irrationalThresholdIsRoundedUpAndLegacyConditionsCannotClaimModelBasis() {
        var r = record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_RECESSIVE");
        r.withObject("/model/penetrance").put("value", "0.3");
        var threshold = new BigDecimal(Bs1MaximumCredibleFrequency.calculate(mapper, "AUTOSOMAL_RECESSIVE", r.path("model")).path("maximumCredibleAf").asText());
        // threshold^2 * .3 >= .001 * .2 * .1^2 (no rounded approximation in expected value).
        assertTrue(threshold.multiply(threshold).multiply(new BigDecimal("0.3")).compareTo(new BigDecimal("0.000002")) >= 0);
        var legacy = ((ObjectNode) local.policy("GN017")).deepCopy();
        legacy.withObject("/tiers/0/conditions/0").put("basis", "REVIEWED_MODEL_CONDITION");
        assertNotNull(Bs1Policy.issue(legacy));
    }
}
