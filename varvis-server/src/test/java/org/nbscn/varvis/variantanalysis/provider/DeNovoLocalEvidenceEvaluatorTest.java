package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** Source reports and applicability knowledge are SYNTHETIC, never clinical adoption. */
class DeNovoLocalEvidenceEvaluatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final DeNovoLocalEvidenceEvaluator evaluator = new DeNovoLocalEvidenceEvaluator(mapper);
    private byte[] resource(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/denovo-automatic/" + name)) { assertNotNull(in); return in.readAllBytes(); }
    }
    private ObjectNode report() throws Exception { return (ObjectNode) mapper.readTree(resource("synthetic-local-report.json")); }
    private DeNovoStructuredReport report(ObjectNode n) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(n); return DeNovoStructuredReport.read(bytes, FamilyDeNovoJson.sha256(bytes));
    }
    private ObjectNode policyNode() throws Exception {
        ObjectNode p = mapper.createObjectNode().put("schemaVersion", DeNovoApplicabilityPolicy.SCHEMA)
                .put("policyId", "SYNTHETIC-phenotype").put("version", "1").put("materialClass", "SYNTHETIC")
                .put("allowAssumedParentage", true).put("expertPolicyStatus", "GENERAL_ALLOWED");
        p.putObject("scope").put("hgncId", "HGNC:SYNTHETIC").put("diseaseId", "MONDO:SYNTHETIC").put("inheritance", "AD");
        p.putArray("sources").addObject().put("sourceId", "synthetic-source")
                .put("sha256", FamilyDeNovoJson.sha256(resource("synthetic-phenotype-source.txt")))
                .put("locator", "SYNTHETIC lines 1-4").put("quote", "the reported presence of HP:0001250 meets the invented CONSISTENT condition.");
        var rule = p.putArray("conditions").addObject().put("conditionId", "synthetic-consistent").put("category", "CONSISTENT");
        rule.putArray("presentAll").add("HP:0001250"); rule.putArray("absentAll"); rule.putArray("sourceIds").add("synthetic-source");
        return p;
    }
    private DeNovoApplicabilityPolicy policy(ObjectNode n) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(n);
        return DeNovoApplicabilityPolicy.load(bytes, FamilyDeNovoJson.sha256(bytes), Map.of("synthetic-source", resource("synthetic-phenotype-source.txt")), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
    }
    private ObjectNode context() {
        var c = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("moi", "AUTOSOMAL_DOMINANT");
        c.putObject("scope").put("analysisDomain", "GERMLINE");
        c.putObject("gene").put("hgncId", "HGNC:SYNTHETIC"); c.putObject("disease").put("mondoId", "MONDO:SYNTHETIC");
        c.putObject("patientContext").put("patientContextId", "child").put("familyContextId", "SYNTHETIC-family-1").put("role", "PROBAND").put("affectedStatus", "AFFECTED");
        var a = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "1").put("position", 100).put("reference", "A").put("alternate", "G");
        var v = c.putObject("variant"); for (String name : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) v.set(name, a.deepCopy());
        return c;
    }
    private ObjectNode formal() { var f = mapper.createObjectNode(); f.putObject("analysisVcfCall").put("targetAlleleCall", "HET_TARGET"); return f; }
    private DeNovoLocalEvidenceEvaluator.Evaluation evaluate(ObjectNode... docs) throws Exception {
        var reports = new java.util.ArrayList<DeNovoStructuredReport>(); for (var doc : docs) reports.add(report(doc));
        return evaluator.evaluateBound(context(), formal(), policy(policyNode()), reports);
    }
    private void reason(DeNovoLocalEvidenceEvaluator.Evaluation e, String reason) {
        assertEquals("NOT_EVALUATED", e.result().status());
        assertTrue(e.blockers().contains(reason) || e.qualifications().stream().anyMatch(q -> q.reasons().contains(reason))
                || e.result().events().stream().anyMatch(event -> event.reasonCode().equals(reason) || event.details().contains(reason)), e.toString());
    }
    private ObjectNode row(ObjectNode n, String field, int i) { return (ObjectNode) n.path(field).get(i); }

    @Test void reportToPolicyToCalculatorWithoutPerCasePolicyPin() throws Exception {
        var p = policy(policyNode()); var n = report();
        var e = evaluator.evaluateBound(context(), formal(), p, List.of(report(n)));
        assertEquals("MET", e.result().status()); assertEquals("PS2", e.result().code()); assertEquals("MODERATE", e.result().strength());
        assertEquals(4, e.result().effectiveQuarterPoints());
        n.put("reportId", "SYNTHETIC-report-another").put("reportVersion", "2");
        var next = evaluator.evaluateBound(context(), formal(), p, List.of(report(n)));
        assertEquals(e.result().effectiveQuarterPoints(), next.result().effectiveQuarterPoints()); assertEquals(e.policySha256(), next.policySha256());
        assertFalse(p.snapshot().has("factsSha256"));
    }
    @Test void currentCaseRequirementsStayLocalAndSharedScopeCannotBeBypassed() throws Exception {
        var c=context(); ((ObjectNode)c.path("patientContext")).put("affectedStatus","UNKNOWN");
        assertFalse(DeNovoLocalEvidenceEvaluator.contextIssues(c,c.at("/variant/evidenceQueryAllele"),false).contains("DENOVO_AFFECTED_STATUS_REQUIRED"));
        reason(evaluator.evaluateBound(c,formal(),policy(policyNode()),List.of(report(report()))),"DENOVO_AFFECTED_STATUS_REQUIRED");
        ((ObjectNode)c.path("patientContext")).put("affectedStatus","UNAFFECTED");
        reason(evaluator.evaluateBound(c,formal(),policy(policyNode()),List.of(report(report()))),"DENOVO_AFFECTED_STATUS_REQUIRED");
        ((ObjectNode)c.at("/variant/evidenceQueryAllele")).put("contig","X");
        assertTrue(DeNovoLocalEvidenceEvaluator.contextIssues(c,c.at("/variant/evidenceQueryAllele"),false).contains("DENOVO_ASSEMBLY_OR_CHROMOSOME_NOT_SUPPORTED"));
        c.put("moi","Y_LINKED");
        assertTrue(DeNovoLocalEvidenceEvaluator.contextIssues(c,c.at("/variant/evidenceQueryAllele"),false).contains("DENOVO_INHERITANCE_NOT_SUPPORTED"));
    }

    @Test void noParentageTestCanBePm6ButNoParentalTargetTestCannot() throws Exception {
        var n = report(); n.putArray("parentage"); var e = evaluate(n);
        assertEquals("PM6", e.result().code()); assertEquals("SUPPORTING", e.result().strength());
        ((ArrayNode) n.path("tests")).remove(2); reason(evaluate(n), "DENOVO_PATERNAL_TARGET_TEST_REQUIRED");
    }

    @Test void separateLaboratoryAndClinicalReportsCanSupplyOneOccurrence() throws Exception {
        var lab = report(); ((ObjectNode) lab.path("clinical")).put("reportedDiseaseId", "UNKNOWN").putArray("presentHpo");
        lab.putArray("parentage");
        var clinical = report(); clinical.put("reportId", "SYNTHETIC-clinical"); clinical.putArray("tests");
        var e = evaluate(lab, clinical);
        assertEquals("PS2", e.result().code()); assertEquals(4, e.result().effectiveQuarterPoints());
        assertEquals(1, e.result().events().size()); assertEquals(3, e.qualifications().size());
        String labSha = report(lab).sha256();
        assertTrue(e.result().events().get(0).sourceRefs().stream().anyMatch(s -> s.startsWith(labSha)));
    }

    @Test void repeatedBytesOrSeparateReportsNeverCreateExtraEvents() throws Exception {
        var a = report(); var b = report(); b.put("reportId", "SYNTHETIC-other-copy");
        var e = evaluate(a, a, b); assertEquals(1, e.repeatedSourceCount());
        assertEquals(1, e.result().events().size()); assertEquals(4, e.result().effectiveQuarterPoints());
        assertEquals(e.result(), evaluate(b, a).result());
    }

    @Test void conflictsAcrossReportsCannotBeDiscardedAsLessHelpful() throws Exception {
        var a = report(); var b = report(); b.put("reportId", "SYNTHETIC-conflict"); row(b, "tests", 1).put("result", "PRESENT");
        reason(evaluate(a, b), "DENOVO_TARGET_TEST_CONFLICT");
        row(b, "tests", 1).put("quality", "UNKNOWN"); reason(evaluate(a, b), "DENOVO_TARGET_TEST_CONFLICT");
        b = report(); ((ArrayNode) b.at("/clinical/absentHpo")).add("HP:0001250");
        reason(evaluate(a, b), "DENOVO_PHENOTYPE_SOURCE_CONFLICT");
    }

    @Test void reportContradictingPublishedProbandStopsCurrentOccurrence() throws Exception {
        var a = report(); var b = report(); row(b, "tests", 0).put("result", "ABSENT");
        reason(evaluate(a, b), "DENOVO_REPORT_PUBLISHED_PROBAND_CONFLICT");
    }

    @Test void parentIdentityCannotBeChangedAcrossReports() throws Exception {
        var a = report(); var b = report(); row(b, "members", 1).put("memberId", "other-mother");
        row(b, "tests", 1).put("memberId", "other-mother"); row(b, "parentage", 0).put("parentId", "other-mother");
        reason(evaluate(a, b), "DENOVO_CROSS_REPORT_PARENT_IDENTITY_CONFLICT");
    }

    @Test void wrongCaseGeneOrAlleleCannotEnterCurrentObservation() throws Exception {
        var n = report(); n.put("familyId", "OTHER"); reason(evaluate(n), "DENOVO_REPORT_PATIENT_OR_FAMILY_MISMATCH");
        n = report(); ((ObjectNode) n.path("target")).put("hgncId", "HGNC:OTHER"); reason(evaluate(n), "DENOVO_REPORT_TARGET_IDENTITY_MISMATCH");
        n = report(); ((ObjectNode) n.path("target")).put("alternate", "T"); reason(evaluate(n), "DENOVO_REPORT_TARGET_IDENTITY_MISMATCH");
    }

    @Test void knownInheritedVariantIsZeroSupportNotMissingData() throws Exception {
        var n = report(); row(n, "tests", 1).put("result", "PRESENT"); var e = evaluate(n);
        assertEquals("NOT_MET", e.result().status()); assertEquals(0, e.result().effectiveQuarterPoints());
        assertEquals("DENOVO_PARENTAL_TARGET_PRESENT", e.result().events().get(0).reasonCode());
    }

    @Test void unknownPhenotypeIsNotAnExplicitNegative() throws Exception {
        var n = report(); ((ObjectNode) n.path("clinical")).putArray("presentHpo");
        reason(evaluate(n), "DENOVO_PHENOTYPE_CONDITIONS_NOT_ESTABLISHED");
        var p = policyNode(); row(p, "conditions", 0).putArray("absentAll").add("HP:0001250"); row(p, "conditions", 0).putArray("presentAll");
        reason(evaluator.evaluateBound(context(), formal(), policy(p), List.of(report(n))), "DENOVO_PHENOTYPE_CONDITIONS_NOT_ESTABLISHED");
    }

    @Test void unrelatedDiseaseCannotSupplyPhenotypeTier() throws Exception {
        var n = report(); ((ObjectNode) n.path("clinical")).put("reportedDiseaseId", "MONDO:OTHER");
        reason(evaluate(n), "DENOVO_REPORTED_DISEASE_OR_INHERITANCE_UNRESOLVED");
        var p = policyNode(); ((ObjectNode) p.path("scope")).put("diseaseId", "MONDO:OTHER");
        reason(evaluator.evaluateBound(context(), formal(), policy(p), List.of(report(report()))), "DENOVO_POLICY_BACKGROUND_MISMATCH");
    }

    @Test void conflictingPolicyTiersDoNotPickTheHigherScore() throws Exception {
        var p = policyNode(); ((ArrayNode) p.path("conditions")).add(row(p, "conditions", 0).deepCopy().put("conditionId", "other").put("category", "HIGHLY_SPECIFIC"));
        reason(evaluator.evaluateBound(context(), formal(), policy(p), List.of(report(report()))), "DENOVO_PHENOTYPE_POLICY_CONFLICT");
    }

    @Test void expertUnresolvedBlocksGenericScoringAndAssumedPolicyIsExplicit() throws Exception {
        var p = policyNode(); p.put("expertPolicyStatus", "APPLICABLE_UNRESOLVED");
        assertEquals("DENOVO_EXPERT_POLICY_UNRESOLVED", evaluator.evaluateBound(context(), formal(), policy(p), List.of(report(report()))).result().reasonCode());
        p = policyNode(); p.put("allowAssumedParentage", false); var n = report(); n.putArray("parentage");
        reason(evaluator.evaluateBound(context(), formal(), policy(p), List.of(report(n))), "DENOVO_ASSUMED_PARENTAGE_POLICY_REQUIRED");
    }

    @TestFactory Stream<DynamicTest> specialFamilyAndParentageGates() {
        return Stream.of(new String[]{"mosaicism", "KNOWN", "DENOVO_SPECIAL_FAMILY_MODEL_NOT_SUPPORTED"},
                new String[]{"mosaicism", "SUSPECTED", "DENOVO_SPECIAL_FAMILY_MODEL_NOT_SUPPORTED"},
                new String[]{"siblingRecurrence", "PRESENT", "DENOVO_SPECIAL_FAMILY_MODEL_NOT_SUPPORTED"},
                new String[]{"familyHistory", "UNKNOWN", "DENOVO_FAMILY_HISTORY_UNRESOLVED"},
                new String[]{"familyHistory", "SIMILAR_HISTORY", "DENOVO_FAMILY_HISTORY_UNRESOLVED"})
                .map(row -> DynamicTest.dynamicTest(row[0] + row[1], () -> {
                    var n = report(); ((ObjectNode) n.path("clinical")).put(row[0], row[1]); reason(evaluate(n), row[2]);
                }));
    }

    @Test void normalizedInputAliasCanBeUsedWithoutChangingFormalCandidate() throws Exception {
        var c = context(); var original = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", "1").put("position", 99).put("reference", "TA").put("alternate", "T");
        var query = original.deepCopy().put("position", 98).put("reference", "AT").put("alternate", "A");
        ((ObjectNode) c.path("variant")).set("inputAllele", original); ((ObjectNode) c.path("variant")).set("normalizedAllele", query);
        ((ObjectNode) c.path("variant")).set("evidenceQueryAllele", query); var n = report();
        ((ObjectNode) n.path("target")).put("position", 99).put("reference", "TA").put("alternate", "T");
        var f = formal(); var before = f.deepCopy(); var e = evaluator.evaluateBound(c, f, policy(policyNode()), List.of(report(n)));
        assertEquals("MET", e.result().status()); assertEquals(before, f);
        // Synthetic preverified context only; this test does not prove reference equivalence.
    }

    @Test void unsupportedChromosomeMnvAndMissingFamilyHaveSpecificReasons() throws Exception {
        var c = context(); ((ObjectNode) c.at("/variant/evidenceQueryAllele")).put("contig", "X");
        reason(evaluator.evaluateBound(c, formal(), policy(policyNode()), List.of(report(report()))), "DENOVO_ASSEMBLY_OR_CHROMOSOME_NOT_SUPPORTED");
        c = context(); ((ObjectNode) c.at("/variant/evidenceQueryAllele")).put("reference", "AC").put("alternate", "GT");
        reason(evaluator.evaluateBound(c, formal(), policy(policyNode()), List.of(report(report()))), "DENOVO_VARIANT_TYPE_NOT_SUPPORTED");
        c = context(); ((ObjectNode) c.path("patientContext")).remove("familyContextId");
        reason(evaluator.evaluateBound(c, formal(), policy(policyNode()), List.of(report(report()))), "DENOVO_FAMILY_CONTEXT_REQUIRED");
    }

    @Test void chromosomeAliasDoesNotRequireAHandEditedSourceReport() throws Exception {
        var n = report(); ((ObjectNode) n.path("target")).put("contig", "chr1");
        var e = evaluate(n); assertEquals("MET", e.result().status());
        assertEquals("chr1", n.at("/target/contig").asText());
    }

    @Test void policyMustHaveConfiguredPinSourcesAndConditions() throws Exception {
        var p = policyNode(); byte[] bytes = mapper.writeValueAsBytes(p);
        var sources = Map.of("synthetic-source", resource("synthetic-phenotype-source.txt"));
        assertThrows(IllegalArgumentException.class, () -> DeNovoApplicabilityPolicy.load(bytes, "0".repeat(64), sources, DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION));
        assertThrows(IllegalArgumentException.class, () -> DeNovoApplicabilityPolicy.load(bytes, FamilyDeNovoJson.sha256(bytes), Map.of(), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION));
        assertThrows(IllegalArgumentException.class, () -> DeNovoApplicabilityPolicy.load(bytes, FamilyDeNovoJson.sha256(bytes), sources, DeNovoApplicabilityPolicy.Use.RESEARCH));
        row(p, "sources", 0).put("quote", "NOT IN ORIGINAL"); assertThrows(IllegalArgumentException.class, () -> policy(p));
    }

    @Test void payloadCannotSmugglePerCaseApprovalIntoPolicy() throws Exception {
        var p = policyNode(); p.put("factsSha256", "0".repeat(64)); assertThrows(IllegalArgumentException.class, () -> policy(p));
        var empty = policyNode(); row(empty, "conditions", 0).putArray("presentAll"); assertThrows(IllegalArgumentException.class, () -> policy(empty));
        var bad = policyNode(); row(bad, "conditions", 0).putArray("absentAll").add("HP:0001250"); assertThrows(IllegalArgumentException.class, () -> policy(bad));
    }

    @Test void emptyReportListAndReportBudgetAreBounded() throws Exception {
        assertEquals("DENOVO_NO_OBSERVATIONS", evaluate().result().reasonCode());
        var p = policy(policyNode()); var r = report(report());
        assertThrows(IllegalArgumentException.class, () -> evaluator.evaluateBound(context(), formal(), p, java.util.Collections.nCopies(101, r)));
    }

    @TestFactory Stream<DynamicTest> frozenFullSourceChainsAndSerialization() throws Exception {
        String expected = new String(resource("frozen-source-chain-expectations.tsv"), StandardCharsets.UTF_8);
        return expected.lines().skip(1).map(line -> DynamicTest.dynamicTest(line.split("\t")[0], () -> {
            String[] row = line.split("\t"); String scenario = row[0]; var n = report();
            var docs = new java.util.ArrayList<ObjectNode>(); docs.add(n);
            switch (scenario) {
                case "assumed" -> n.putArray("parentage");
                case "father-missing" -> ((ArrayNode) n.path("tests")).remove(2);
                case "inherited" -> row(n, "tests", 1).put("result", "PRESENT");
                case "phenotype-missing" -> ((ObjectNode) n.path("clinical")).putArray("presentHpo");
                case "separate-reports" -> {
                    var other = report(); other.put("reportId", "SYNTHETIC-clinical"); other.putArray("tests"); docs.add(other);
                    n.putArray("parentage"); ((ObjectNode) n.path("clinical")).put("reportedDiseaseId", "UNKNOWN").putArray("presentHpo");
                }
                case "confirmed" -> { }
                default -> fail("Unknown frozen scenario");
            }
            var p = policy(policyNode()); var reports = new java.util.ArrayList<DeNovoStructuredReport>();
            for (var doc : docs) reports.add(report(doc));
            var e = evaluator.evaluateBound(context(), formal(), p, reports);
            assertEquals(row[1], e.result().status()); assertEquals(row[2], e.result().code());
            assertEquals(row[3], e.result().strength()); assertEquals(Integer.parseInt(row[4]), e.result().effectiveQuarterPoints());
            byte[] json = mapper.writeValueAsBytes(e);
            assertEquals(e, mapper.readValue(json, DeNovoLocalEvidenceEvaluator.Evaluation.class));
            var sources = new java.util.LinkedHashMap<String, byte[]>(); sources.put(p.sha256(), p.originalBytes());
            for (var report : reports) sources.put(report.sha256(), report.originalBytes());
            for (var event : e.result().events()) for (String ref : event.sourceRefs()) {
                String pin = ref.substring(0, 64), pointer = ref.substring(65); byte[] raw = sources.get(pin);
                assertNotNull(raw); assertEquals(pin, FamilyDeNovoJson.sha256(raw));
                assertFalse(mapper.readTree(raw).at(pointer).isMissingNode(), ref);
            }
            String output = System.getProperty("varvis.denovo.artifacts");
            if (output != null) {
                Path directory = Path.of(output).resolve(scenario); Files.createDirectories(directory);
                Files.write(directory.resolve("evaluation.json"), json, StandardOpenOption.CREATE_NEW);
                Files.write(directory.resolve("context.json"), mapper.writeValueAsBytes(context()), StandardOpenOption.CREATE_NEW);
                Files.write(directory.resolve("formal.json"), mapper.writeValueAsBytes(formal()), StandardOpenOption.CREATE_NEW);
                Files.write(directory.resolve("synthetic-phenotype-source.txt"), resource("synthetic-phenotype-source.txt"), StandardOpenOption.CREATE_NEW);
                for (var source : sources.entrySet()) Files.write(directory.resolve(source.getKey() + ".json"), source.getValue(), StandardOpenOption.CREATE_NEW);
            }
        }));
    }
}
