package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic applicability controls; no clinical or real population adoption. */
public class Bs1KnowledgeQualificationTest {
    final ObjectMapper mapper = new ObjectMapper();
    final Bs1KnowledgeCatalogTest factory = new Bs1KnowledgeCatalogTest();
    @TempDir Path temporary;

    public static void qualify(ObjectNode record) {
        record.remove("qualification");
        if ("MAX_CREDIBLE_AF".equals(record.path("kind").asText()) && !record.has("modelRestrictions")) {
            var restrictions = record.putObject("modelRestrictions");
            restrictions.putArray("variantTypes").add("SNV").add("SMALL_INSERTION").add("SMALL_DELETION");
            restrictions.putArray("transcripts"); restrictions.putArray("commonConditions");
        }
        new Bs1KnowledgeCatalogTest().review(record);
        var prior = record.path("review").deepCopy();
        var q = record.putObject("qualification").put("schemaVersion", Bs1KnowledgeQualification.VERSION)
                .put("payloadSha256", Bs1KnowledgeCatalog.hash(Bs1KnowledgeQualification.payload(record)))
                .put("reviewer", "SYNTHETIC_TEST_NOT_CLINICAL_APPROVAL").put("reviewedAt", "2026-10-09T00:00:00Z")
                .put("basis", "Synthetic arithmetic control only");
        q.set("priorReview", prior);
        q.set("scope", record.path("scope").deepCopy());
        var bindings = q.putArray("sourceBindings");
        for (var source : record.path("sources")) bindings.addObject().put("sourceId", source.path("sourceId").asText())
                .put("locator", "/synthetic").put("contentSha256", "a".repeat(64));
        var checks = q.putObject("checks");
        for (String category : Bs1KnowledgeQualification.CATEGORIES) {
            var check = checks.putObject(category).put("status", "PARAMETER_SCOPE".equals(category)
                    ? ("EXPERT_POLICY".equals(record.path("kind").asText()) ? "PUBLISHED_THRESHOLD_NOT_RECALCULATED" : "MODEL_PARAMETERS_MATCHED")
                    : "SATISFIED").put("basis", "Synthetic source definition and complete explicit scope");
            var ids = check.putArray("sourceIds"); for (var source : record.path("sources")) ids.add(source.path("sourceId").asText());
        }
        new Bs1KnowledgeCatalogTest().review(record);
    }
    ObjectNode record() { var r = factory.record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT"); qualify(r); return r; }
    String issue(ObjectNode r) { return Bs1Policy.issue(Bs1KnowledgeCatalog.policy(r)); }
    Bs1Runtime runtime(ObjectNode r) throws Exception {
        factory.temporary = temporary; return factory.runtime(factory.load(factory.bundle(r)));
    }
    ObjectNode select(Bs1Runtime runtime, JsonNode context) { return Bs1Policy.select(mapper, runtime.policyRecords(), context); }
    ObjectNode condition(String fact, String operator, String value) {
        return mapper.createObjectNode().put("fact", fact).put("operator", operator).put("decimal", value)
                .put("unit", "FAF95".equals(fact) ? "fraction" : "alleles").put("basis", "OFFICIAL_CONDITION")
                .put("originalText", "SYNTHETIC: " + fact + operator + value).put("sourceLocator", "/synthetic/conditions");
    }
    @Test void qualifiedModelEvaluatesConditionsAndPreservesCompleteRecord() throws Exception {
        var r = record();
        r.withObject("/modelRestrictions").withArray("commonConditions").add(condition("AN", "GTE", "5000")).add(condition("AC", "GTE", "5"));
        qualify(r); assertNull(issue(r));
        var rt = runtime(r); var c = factory.context(r);
        for (String an : List.of("10000", "3000")) {
            var result = rt.evaluateLocal(c, factory.local.shared(c, factory.local.facts.info("0.001", an), "PASS"), Bs1LocalExomesTest.SOURCE);
            assertEquals("10000".equals(an) ? "MET" : "NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText());
            if ("3000".equals(an)) assertEquals("BS1_COMMON_CONDITION_NOT_SATISFIED", result.at("/evaluation/evaluationResult/reasonCode").asText());
            assertEquals(r, result.at("/preparation/policy/knowledgeRecord"));
            assertEquals(result, mapper.readTree(mapper.writeValueAsBytes(result)));
        }
        assertEquals(0, rt.counts().path("provider").asInt());
        var few = factory.local.facts.info("0.00003", "10000");
        few.put("AC_nfe", "4"); few.put("AF_nfe", "0.0004");
        var rejected = rt.evaluateLocal(c, factory.local.shared(c, few, "PASS"), Bs1LocalExomesTest.SOURCE);
        assertEquals("READY", rejected.at("/qualityDecision/status").asText());
        assertEquals("NOT_EVALUATED", rejected.at("/evaluation/evaluationResult/status").asText());
        assertEquals("BS1_COMMON_CONDITION_NOT_SATISFIED", rejected.at("/evaluation/evaluationResult/reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"SOURCE_COMPLETENESS", "PARAMETER_SCOPE", "POPULATION_METRIC", "ADDITIONAL_CONDITIONS", "EXCEPTIONS"})
    void unresolvedCategoryIsSpecificAndBlocksBeforeCore(String category) throws Exception {
        var r = record(); r.withObject("/qualification/checks/" + category).put("status", "UNRESOLVED"); factory.review(r);
        String reason = "BS1_QUALIFICATION_" + category + "_UNRESOLVED";
        assertEquals(reason, issue(r));
        var rt = runtime(r); var c = factory.context(r);
        assertEquals(reason, select(rt, c).path("reasonCode").asText());
        assertEquals(0, rt.counts().path("evaluator").asInt());
    }
    @ParameterizedTest @ValueSource(strings = {"prevalence", "penetrance", "maximumGeneContribution", "maximumAlleleContribution"})
    void changedParameterCannotReuseQualificationEvenWithNewOuterReview(String field) {
        var r = record(); r.withObject("/model/" + field).put("value", "0.7"); factory.review(r);
        assertEquals("BS1_QUALIFICATION_BINDING_INVALID", issue(r));
    }
    @Test void changedScopeAndMissingCheckCannotBeIgnored() {
        var r = record(); r.withObject("/qualification/scope").put("mondoId", "MONDO:0999999"); factory.review(r);
        assertEquals("BS1_QUALIFICATION_BINDING_INVALID", issue(r));
        r = record(); r.withObject("/qualification/checks").remove("EXCEPTIONS"); factory.review(r);
        assertEquals("BS1_QUALIFICATION_CHECKS_INCOMPLETE", issue(r));
    }
    @Test void unboundOrDuplicateSourceCannotBeQualified() {
        var r = record(); r.withObject("/qualification/sourceBindings/0").put("sourceId", "unknown"); factory.review(r);
        assertEquals("BS1_QUALIFICATION_SOURCE_INVALID", issue(r));
        r = record(); r.withArray("/qualification/sourceBindings").add(r.at("/qualification/sourceBindings/0").deepCopy()); factory.review(r);
        assertEquals("BS1_QUALIFICATION_SOURCE_INVALID", issue(r));
        r = record(); r.withObject("/qualification/checks/EXCEPTIONS").putArray("sourceIds").add("unknown"); factory.review(r);
        assertEquals("BS1_QUALIFICATION_CHECK_SOURCE_UNBOUND", issue(r));
    }
    @Test void unqualifiedRestrictionsAndUnknownRequirementsAreNotExecutable() {
        var r = record(); r.remove("qualification"); factory.review(r);
        assertEquals("BS1_QUALIFICATION_REQUIRED", issue(r));
        r = record(); r.withObject("/modelRestrictions").put("minimumPatientAge", 40); qualify(r);
        assertEquals("BS1_MODEL_RESTRICTIONS_INVALID", issue(r));
        r = record(); r.withObject("/modelRestrictions").withArray("commonConditions").add(condition("READ_DEPTH", "GTE", "20")); qualify(r);
        assertEquals("BS1_POLICY_UNKNOWN_CONDITION", issue(r));
    }
    @Test void variantAndTranscriptScopeAreAppliedBeforeEvaluation() throws Exception {
        var r = record(); r.withObject("/modelRestrictions").putArray("variantTypes").add("SMALL_DELETION"); qualify(r);
        var c = factory.context(r); assertEquals("BS1_VARIANT_SCOPE_UNSUPPORTED", select(runtime(r), c).path("reasonCode").asText());
        r = record(); r.withObject("/modelRestrictions").withArray("transcripts").addObject().put("accession", "NM_999001").put("version", 1); qualify(r);
        c = factory.context(r); c.remove("transcript"); factory.local.contexts.refresh(c);
        assertEquals("BS1_TRANSCRIPT_REQUIRED_BY_SCOPE", select(runtime(r), c).path("reasonCode").asText());
        c.putObject("transcript").put("accession", "NM_999001").put("version", 2); factory.local.contexts.refresh(c);
        assertEquals("BS1_TRANSCRIPT_VERSION_NOT_BRIDGED", select(runtime(r), c).path("reasonCode").asText());
        c.withObject("/transcript").put("version", 1); factory.local.contexts.refresh(c);
        assertEquals("READY", select(runtime(r), c).path("status").asText());
    }
    @Test void alleleExceptionsRequireExactScopeAndBoundMappingAndBlockHit() throws Exception {
        var r = record(); var c = factory.context(r);
        var e = r.withObject("/exceptionSet").withArray("entries").addObject().put("entryId", "SYNTHETIC-X")
                .put("hgncId", r.at("/scope/hgncId").asText()).put("mondoId", r.at("/scope/mondoId").asText())
                .put("sourceLocator", "/synthetic/exception").put("identityStatus", "VERIFIED_ALLELE").put("disposition", "NOT_APPLICABLE");
        e.set("queryAllele", c.at("/variant/evidenceQueryAllele").deepCopy()); e.putArray("mappingSource").add(factory.source()); qualify(r);
        assertEquals("BS1_POLICY_EXCEPTION_HIT", select(runtime(r), c).path("reasonCode").asText());
        e.put("mondoId", "MONDO:0999999"); qualify(r);
        assertEquals("BS1_QUALIFICATION_EXCEPTION_MAPPING_UNRESOLVED", issue(r));
        e.put("mondoId", r.at("/scope/mondoId").asText()).put("identityStatus", "UNRESOLVED"); qualify(r);
        assertEquals("BS1_QUALIFICATION_EXCEPTION_MAPPING_UNRESOLVED", issue(r));
        e.put("identityStatus", "VERIFIED_ALLELE"); e.withObject("/mappingSource/0").put("sha256", "b".repeat(64)); qualify(r);
        assertEquals("BS1_QUALIFICATION_EXCEPTION_SOURCE_UNBOUND", issue(r));
    }
    @Test void expertExceptionSetsCannotDisagreeAndModelCannotBypassExpert() throws Exception {
        var r = factory.expertRecord("HGNC:999001", "MONDO:0999001", "SYNTHETIC");
        r.set("exceptionSet", r.at("/expertPolicy/exceptionSet").deepCopy()); qualify(r); assertNull(issue(r));
        r.withObject("/exceptionSet").put("version", "different"); qualify(r);
        assertEquals("BS1_QUALIFICATION_EXCEPTION_CONFLICT", issue(r));
        r = record(); var bundle = factory.bundle(r);
        bundle.putArray("expertScopes").addObject().put("hgncId", "HGNC:999001").put("mondoId", "MONDO:0999001")
                .put("specificationId", "GN-SYNTHETIC").put("version", "1").put("sourceId", "synthetic-parameters").put("status", "STRUCTURING_REQUIRED");
        factory.temporary = temporary;
        assertThrows(IllegalArgumentException.class, () -> factory.load(bundle));
    }
    @Test void qualifiedIdentityAndOldRecordsAreCompatible() throws Exception {
        factory.temporary = temporary; var r = factory.record("HGNC:999001", "MONDO:0999001", "AUTOSOMAL_DOMINANT");
        var prior = factory.load(factory.bundle(r)); assertFalse(prior.identity().has("qualificationVersion"));
        var oldPolicy = prior.merge(List.of()).get(0); qualify(r);
        var next = factory.load(factory.bundle(r)); assertEquals(Bs1KnowledgeQualification.VERSION, next.identity().path("qualificationVersion").asText());
        var p = next.merge(List.of()).get(0);
        for (String key : List.of("tiers", "scope", "statistic", "commonConditions", "exceptionSet")) assertEquals(oldPolicy.path(key), p.path(key));
        assertNotEquals(prior.identity(), next.identity());
    }
    @Test void priorAdoptionAndCurrentAuthorCannotBeSilentlyRewritten() {
        var r = record(); r.withObject("/qualification/priorReview").put("contentSha256", "b".repeat(64)); factory.review(r);
        assertEquals("BS1_QUALIFICATION_PRIOR_ADOPTION_INVALID", issue(r));
        r = record(); r.withObject("/qualification").put("reviewer", "Another author"); factory.review(r);
        assertEquals("BS1_QUALIFICATION_ATTRIBUTION_MISMATCH", issue(r));
        r = record(); r.withObject("/review").put("strength", "SUPPORTING");
        assertEquals("BS1_QUALIFICATION_PRIOR_ADOPTION_CHANGED", issue(r));
    }
    @Test void realQualifiedBatchKeepsAllPriorPoliciesAndBoundaryResults() throws Exception {
        String file = System.getProperty("bs1.qualification.bundle", "");
        org.junit.jupiter.api.Assumptions.assumeFalse(file.isBlank(), "No fixed batch snapshot supplied");
        Path priorPath = Path.of(System.getProperty("bs1.qualification.prior"));
        var priorCatalog = Bs1KnowledgeCatalog.load(priorPath, Bs1KnowledgeCatalog.sha(Files.readAllBytes(priorPath)));
        var nextCatalog = Bs1KnowledgeCatalog.load(Path.of(file), System.getProperty("bs1.qualification.sha"));
        var prior = factory.runtime(priorCatalog); var next = factory.runtime(nextCatalog);
        var oldById = new HashMap<String, JsonNode>();
        for (var p : prior.policyRecords()) if (p.has("knowledgeRecord")) oldById.put(p.at("/knowledgeRecord/recordId").asText(), p);
        int count = 0; var rows = mapper.createArrayNode();
        for (var p : next.policyRecords()) {
            if (!p.has("knowledgeRecord")) continue;
            String id = p.at("/knowledgeRecord/recordId").asText(); var before = oldById.get(id); assertNotNull(before);
            assertNull(Bs1Policy.issue(p), id); count++;
            for (String key : List.of("scope", "tiers", "statistic", "commonConditions", "exceptionSet", "requiredContext"))
                assertEquals(before.path(key), p.path(key), id + ":" + key);
            var context = factory.context((ObjectNode)p.path("knowledgeRecord"));
            var boundary = new java.math.BigDecimal(before.at("/tiers/0/conditions/0/decimal").asText());
            for (int direction : List.of(-1, 0, 1)) {
                String faf = boundary.add(new java.math.BigDecimal("0.0000000001").multiply(java.math.BigDecimal.valueOf(direction))).toPlainString();
                var observation = factory.local.shared(context, factory.local.facts.info(faf, "10000"), "PASS");
                var oldResult = prior.evaluateLocal(context, observation, Bs1LocalExomesTest.SOURCE);
                var newResult = next.evaluateLocal(context, observation, Bs1LocalExomesTest.SOURCE);
                assertEquals(oldResult.at("/evaluation/evaluationResult"), newResult.at("/evaluation/evaluationResult"));
                assertEquals(oldResult.at("/evaluation/appliedStrength"), newResult.at("/evaluation/appliedStrength"));
                assertEquals(p.path("knowledgeRecord"), newResult.at("/preparation/policy/knowledgeRecord"));
                rows.addObject().put("recordId", id).put("FAF95", faf).put("priorStatus", oldResult.at("/evaluation/evaluationResult/status").asText())
                        .put("qualifiedStatus", newResult.at("/evaluation/evaluationResult/status").asText());
            }
        }
        assertEquals(22, count); assertEquals(66, rows.size());
        long ready = next.policyRecords().stream().filter(p -> {
            try { return "READY".equals(select(next, factory.context(mapper.createObjectNode().set("scope", p.path("scope")))).path("status").asText()); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }).count();
        assertEquals(28, ready);
        var report = mapper.createObjectNode().put("realKnowledgeRecords",count).put("syntheticPopulationComparisons", rows.size())
                .put("selectionReady", ready).put("newExecutableBackgrounds", 0).put("newScientificAdoptions", 0);
        report.set("checks", rows);
        Files.writeString(Path.of(System.getProperty("bs1.qualification.report")), report.toPrettyString(), StandardOpenOption.CREATE_NEW);
    }
}
