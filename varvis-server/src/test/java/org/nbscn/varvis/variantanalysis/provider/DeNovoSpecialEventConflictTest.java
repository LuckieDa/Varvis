package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Independent conflict oracle: two statements about one target/person/trio cannot
 * be resolved by choosing the favourable source. All source-reader output and
 * families here are SYNTHETIC. These adapter tests are not clinical validation.
 * Maternal origin deliberately uses a different ancestral trio; different tissue
 * measurements deliberately remain distinct observations.
 */
class DeNovoSpecialEventConflictTest {
    private static final String FAMILY = "SYNTHETIC-family-1";
    private static final byte[] POLICY_SOURCE = "SYNTHETIC scoped de novo method and phenotype source.".getBytes(StandardCharsets.UTF_8);
    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode allele(boolean x) {
        return mapper.createObjectNode().put("assembly", "GRCh38").put("contig", x ? "X" : "1")
                .put("position", x ? 5_000_000 : 100).put("reference", "A").put("alternate", "G");
    }

    private ObjectNode event() {
        var event = mapper.createObjectNode().put("schemaVersion", "denovo-special-event-v1")
                .put("kind", "CONSTITUTIONAL").put("familyId", FAMILY).put("eventId", "SYNTHETIC-origin-1")
                .put("personId", "child").put("probandId", "child").put("sex", "FEMALE")
                .put("ploidy", "DIPLOID").put("genotype", "HETEROZYGOUS").put("affectedStatus", "AFFECTED")
                .put("parentalAvailability", "AVAILABLE").put("otherAffectedMales", "NONE_IN_CHECKED_SCOPE")
                .put("eventOrigin", "UNKNOWN");
        event.putObject("trio").put("personId", "child").put("motherId", "mother").put("fatherId", "father")
                .put("subjectTarget", "PRESENT").put("motherTarget", "ABSENT").put("fatherTarget", "ABSENT")
                .put("maternity", "CONFIRMED").put("paternity", "CONFIRMED")
                .put("maternityMethod", "SNP_PANEL").put("paternityMethod", "SNP_PANEL")
                .put("method", "SANGER").put("targetCoverage", "EXPLICIT_TARGET")
                .put("detectionCapability", "ADEQUATE").put("quality", "VALID");
        var partner = event.putObject("partner").put("status", "NONE_IN_CHECKED_SCOPE").put("phase", "UNKNOWN");
        partner.putNull("allele"); partner.putArray("sourceRefs");
        event.putArray("siblings"); event.putArray("tissues");
        return event;
    }

    private ObjectNode tissueEvent(boolean unavailable) {
        var event = event().put("kind", "POSTZYGOTIC").put("parentalAvailability", unavailable ? "UNAVAILABLE" : "AVAILABLE");
        if (unavailable) ((ObjectNode) event.path("trio")).put("motherTarget", "UNKNOWN").put("fatherTarget", "UNKNOWN")
                .put("maternity", "INCONCLUSIVE").put("paternity", "INCONCLUSIVE")
                .put("maternityMethod", "UNKNOWN").put("paternityMethod", "UNKNOWN");
        var tissues = event.withArray("tissues");
        tissues.addObject().put("personId", "child").put("tissueId", "SYNTHETIC-brain").put("affected", true)
                .put("altReads", 50).put("totalReads", 1000).put("limit", 0.001)
                .put("method", "TARGETED_SEQUENCING").put("quality", "HIGH_CONFIDENCE");
        tissues.addObject().put("personId", "child").put("tissueId", "SYNTHETIC-blood").put("affected", false)
                .put("altReads", 0).put("totalReads", 1000).put("limit", 0.001)
                .put("method", "TARGETED_SEQUENCING").put("quality", "HIGH_CONFIDENCE");
        return event;
    }

    private DeNovoApplicabilityPolicy policy(String inheritance, boolean tissue) throws Exception {
        var p = mapper.createObjectNode().put("schemaVersion", DeNovoApplicabilityPolicy.SCHEMA)
                .put("policyId", "SYNTHETIC-conflict-policy").put("version", "1").put("materialClass", "SYNTHETIC")
                .put("allowAssumedParentage", true).put("expertPolicyStatus", tissue ? "APPLICABLE_IMPLEMENTED" : "GENERAL_ALLOWED");
        p.putObject("scope").put("hgncId", "HGNC:SYNTHETIC").put("diseaseId", "MONDO:SYNTHETIC").put("inheritance", inheritance);
        p.putArray("sources").addObject().put("sourceId", "source").put("sha256", FamilyDeNovoJson.sha256(POLICY_SOURCE))
                .put("locator", "SYNTHETIC").put("quote", new String(POLICY_SOURCE, StandardCharsets.UTF_8));
        var rule = p.putArray("conditions").addObject().put("conditionId", "consistent").put("category", "CONSISTENT");
        rule.putArray("presentAll").add("HP:0001250"); rule.putArray("absentAll"); rule.putArray("sourceIds").add("source");
        var term = p.putArray("literatureTerms").addObject().put("hpoId", "HP:0001250");
        term.putArray("aliases").add("HP:0001250"); term.putArray("sourceIds").add("source");
        p.putObject("specialEvents").put("mode", tissue ? "TISSUE_DIFFERENTIAL_PS2" : "SVI_1_1").putArray("sourceIds").add("source");
        byte[] raw = mapper.writeValueAsBytes(p);
        return DeNovoApplicabilityPolicy.load(raw, FamilyDeNovoJson.sha256(raw), Map.of("source", POLICY_SOURCE), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
    }

    private ObjectNode report(String inheritance, ObjectNode event) throws Exception {
        ObjectNode report;
        try (var input = getClass().getResourceAsStream("/denovo-automatic/synthetic-local-report.json")) {
            assertNotNull(input); report = (ObjectNode) mapper.readTree(input);
        }
        report.put("schemaVersion", DeNovoStructuredReport.SCHEMA_V2);
        ((ObjectNode) report.path("clinical")).put("inheritance", inheritance);
        if ("X_LINKED".equals(inheritance)) {
            ((ObjectNode) report.path("target")).put("contig", "X").put("position", 5_000_000);
        }
        report.set("specialEvent", event.deepCopy());
        return report;
    }

    private DeNovoStructuredReport read(ObjectNode report) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(report);
        return DeNovoStructuredReport.read(bytes, FamilyDeNovoJson.sha256(bytes));
    }

    private ObjectNode context(String inheritance) {
        var context = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("moi", inheritance);
        context.putObject("scope").put("analysisDomain", "GERMLINE");
        context.putObject("gene").put("hgncId", "HGNC:SYNTHETIC"); context.putObject("disease").put("mondoId", "MONDO:SYNTHETIC");
        context.putObject("patientContext").put("patientContextId", "child").put("familyContextId", FAMILY)
                .put("role", "PROBAND").put("affectedStatus", "AFFECTED");
        var variant = context.putObject("variant");
        for (String name : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) variant.set(name, allele("X_LINKED".equals(inheritance)));
        return context;
    }

    private DeNovoLocalEvidenceEvaluator evaluator() {
        var regions = DeNovoA10RegionSupply.fromAssessor(mapper, SexChromosomeRegionResolver.CATALOG_SHA256,
                a -> SexChromosomeRegionResolver.assess(mapper, a));
        return new DeNovoLocalEvidenceEvaluator(mapper, regions);
    }

    private DeNovoLocalEvidenceEvaluator.Evaluation current(String inheritance, boolean tissue, ObjectNode... rawReports) throws Exception {
        var reports = new java.util.ArrayList<DeNovoStructuredReport>();
        for (var raw : rawReports) {
            var parsed = read(raw); assertEquals(raw, parsed.snapshot(), "Source conflicts must remain in the retained report"); reports.add(parsed);
        }
        var evaluation = evaluator().evaluateBound(context(inheritance), mapper.createObjectNode(), policy(inheritance, tissue), reports);
        for (var report : reports) assertTrue(evaluation.result().events().stream().anyMatch(e -> e.sourceRefs().contains(report.sha256() + "#")), evaluation.toString());
        return evaluation;
    }

    private void blocked(DeNovoLocalEvidenceEvaluator.Evaluation evaluation, String independentExpectation) {
        var result = evaluation.result();
        assertEquals("NOT_EVALUATED", result.status(), independentExpectation + "\n" + evaluation);
        assertEquals("NONE", result.code()); assertEquals("NONE", result.strength());
        assertEquals(0, result.rawQuarterPoints()); assertEquals(0, result.effectiveQuarterPoints());
        assertTrue(result.events().stream().noneMatch(e -> "COUNTED".equals(e.disposition())), evaluation.toString());
        assertTrue(!evaluation.blockers().isEmpty() || evaluation.qualifications().stream().anyMatch(q -> !q.reasons().isEmpty())
                || result.events().stream().anyMatch(e -> !e.details().isEmpty()), "A blocked observation needs an inspectable diagnostic");
    }

    @Test void concordantOrdinaryAndTypedTargetEvidenceStillReachesRecessiveScoring() throws Exception {
        var result = current("AR", false, report("AR", event())).result();
        assertEquals("MET", result.status(), result.toString()); assertEquals("PS2", result.code());
        assertEquals("SUPPORTING", result.strength()); assertEquals(4, result.rawQuarterPoints());
        assertEquals("ONE_LEVEL_AR_NO_SECOND_P_LP", result.strengthAdjustment());
    }

    @Test void currentPositiveParentCannotBeOverwrittenBySpecialAbsentForTheSameTrio() throws Exception {
        for (int parent : List.of(1, 2)) {
            var raw = report("AR", event()); ((ObjectNode) raw.path("tests").get(parent)).put("result", "PRESENT");
            assertEquals(DeNovoEvidenceCalculator.TargetTest.PRESENT, read(raw).targetStatus(parent == 1 ? "MOTHER" : "FATHER"));
            blocked(current("AR", false, raw), "Ordinary parental PRESENT versus special ABSENT is a source conflict");
        }
    }

    @Test void currentTargetConflictAndQualityFailureCannotBeOverwrittenByTheSpecialTrio() throws Exception {
        for (int member : List.of(0, 1, 2)) for (String quality : List.of("CONFLICT", "FAILED")) {
            var raw = report("AR", event()); ((ObjectNode) raw.path("tests").get(member)).put("quality", quality);
            blocked(current("AR", false, raw), "Known " + quality + " for the same member must survive typed fact selection");
        }
    }

    @Test void currentExcludedParentageCannotBecomeSpecialConfirmed() throws Exception {
        for (int relation : List.of(0, 1)) {
            var raw = report("AR", event()); ((ObjectNode) raw.path("parentage").get(relation)).put("result", "EXCLUDED");
            blocked(current("AR", false, raw), "The same parent-child relation cannot be EXCLUDED and CONFIRMED");
        }
    }

    @Test void supplementalOrdinaryConflictCannotBeDiscardedAfterMergingReports() throws Exception {
        var special = report("AR", event());
        var ordinary = report("AR", event()).put("reportId", "SYNTHETIC-contradiction"); ordinary.remove("specialEvent");
        ((ObjectNode) ordinary.path("tests").get(1)).put("result", "PRESENT");
        blocked(current("AR", false, special, ordinary), "Additional ordinary source must not be overwritten by typed source");
        blocked(current("AR", false, ordinary, special), "Source ordering cannot choose which known result wins");
    }

    @Test void sameEventBindsReportedMotherAndFatherIds() throws Exception {
        for (String parent : List.of("motherId", "fatherId")) {
            var special = event(); ((ObjectNode) special.path("trio")).put(parent, "SYNTHETIC-someone-else");
            blocked(current("AR", false, report("AR", special)), "An event trio cannot borrow test/parentage evidence for another parent identity");
        }
    }

    @Test void maternalOriginUsesTheGrandparentTrioWithoutTreatingTheInheritedChildAsAnotherMutation() throws Exception {
        var maternal = event().put("kind", "MATERNAL_ORIGIN").put("personId", "mother").put("affectedStatus", "UNAFFECTED").put("probandSex", "MALE");
        ((ObjectNode) maternal.path("trio")).put("personId", "mother").put("motherId", "grandmother").put("fatherId", "grandfather");
        var raw = report("X_LINKED", maternal); ((ObjectNode) raw.path("tests").get(1)).put("result", "PRESENT");
        var result = current("X_LINKED", false, raw).result();
        assertEquals("MET", result.status(), result.toString()); assertEquals("PS2", result.code());
        assertEquals("MODERATE", result.strength()); assertEquals(4, result.effectiveQuarterPoints());
        assertEquals(1, result.events().stream().filter(e -> "COUNTED".equals(e.disposition())).count());
        assertEquals("PRESENT", raw.at("/tests/1/result").asText());
        assertEquals("grandmother", raw.at("/specialEvent/trio/motherId").asText());
    }

    @Test void tissueUnavailableCannotHideOrdinaryKnownParentalTargetOrRelationshipFailures() throws Exception {
        for (int parent : List.of(1, 2)) {
            var raw = report("AD", tissueEvent(true));
            ((ObjectNode) raw.path("tests").get(parent)).put("result", "PRESENT");
            blocked(current("AD", true, raw), "C2 plus UNAVAILABLE cannot erase a known parental carrier observation");
        }
        var excluded = report("AD", tissueEvent(true));
        ((ObjectNode) excluded.path("parentage").get(0)).put("result", "EXCLUDED");
        blocked(current("AD", true, excluded), "C2 exception cannot erase known excluded biological relation");
        for (String quality : List.of("FAILED", "CONFLICT")) {
            var raw = report("AD", tissueEvent(true)); ((ObjectNode) raw.path("tests").get(1)).put("quality", quality);
            blocked(current("AD", true, raw), "UNAVAILABLE cannot hide an unresolved failed/conflicting existing target assay");
        }
    }

    @Test void tissueUnavailableCannotHideItsOwnKnownParentalPositive() throws Exception {
        for (String role : List.of("motherTarget", "fatherTarget")) {
            var special = tissueEvent(true); ((ObjectNode) special.path("trio")).put(role, "PRESENT");
            var raw = report("AD", special); raw.withArray("tests").removeAll(); raw.withArray("parentage").removeAll();
            blocked(current("AD", true, raw), "UNAVAILABLE contradicts a known target-positive parent in the actual event trio");
        }
    }

    @Test void tissueUnavailableDoesNotConvertUnknownParentFactsIntoCompleteAvailableTrio() throws Exception {
        var special = tissueEvent(false); ((ObjectNode) special.path("trio")).put("motherTarget", "UNKNOWN").put("fatherTarget", "UNKNOWN")
                .put("maternity", "INCONCLUSIVE").put("paternity", "INCONCLUSIVE")
                .put("maternityMethod", "UNKNOWN").put("paternityMethod", "UNKNOWN").put("quality", "UNKNOWN");
        var raw = report("AD", special); raw.withArray("tests").removeAll(); raw.withArray("parentage").removeAll();
        blocked(current("AD", true, raw), "AVAILABLE but unknown or quality-unknown parental facts cannot borrow unavailable-parent C2 exception");
    }

    @Test void qualifiedDifferentTissuesAndTrueUnavailableParentsRetainTheirExpertPaths() throws Exception {
        var available = tissueEvent(false);
        available.withArray("tissues").addObject().put("personId", "child").put("tissueId", "SYNTHETIC-skin").put("affected", false)
                .put("altReads", 1).put("totalReads", 1000).put("limit", 0.0001)
                .put("method", "TARGETED_SEQUENCING").put("quality", "HIGH_CONFIDENCE");
        var strong = current("AD", true, report("AD", available)).result();
        assertEquals("MET", strong.status(), strong.toString()); assertEquals("STRONG", strong.strength());
        assertEquals("EXPERT_FIXED_STRENGTH", strong.calculationMethod()); assertEquals(0, strong.effectiveQuarterPoints());
        var unavailable = report("AD", tissueEvent(true)); unavailable.withArray("tests").removeAll(); unavailable.withArray("parentage").removeAll();
        var moderate = current("AD", true, unavailable).result();
        assertEquals("MET", moderate.status(), moderate.toString()); assertEquals("MODERATE", moderate.strength());
        assertEquals(0, moderate.rawQuarterPoints());
    }

    @Test void differentTissueResultsNeedBoundMeasurementScopesRatherThanChoosingThePositiveSource() throws Exception {
        var special = tissueEvent(false);
        ((ObjectNode) special.path("trio")).put("method", "TARGETED_SEQUENCING").putObject("observationScopes")
                .putObject("subject").put("specimenId", "SYNTHETIC-brain-1").put("tissue", "SYNTHETIC-brain").put("timepoint", "SYNTHETIC-T1");
        var raw = report("AD", special);
        ((ObjectNode) raw.path("tests").get(0)).put("result", "ABSENT").put("method", "TARGETED_SEQUENCING")
                .putObject("observationScope").put("specimenId", "SYNTHETIC-blood-1").put("tissue", "SYNTHETIC-blood").put("timepoint", "SYNTHETIC-T1");
        var result = current("AD", true, raw).result();
        assertEquals("MET", result.status(), result.toString()); assertEquals("STRONG", result.strength());
        assertEquals("ABSENT", raw.at("/tests/0/result").asText());
        var noScope = raw.deepCopy(); ((ObjectNode) noScope.path("tests").get(0)).remove("observationScope");
        blocked(current("AD", true, noScope), "Unscoped negative must not be silently assigned to a favourable different tissue");
        var inventedTissue = raw.deepCopy(); ((ObjectNode) inventedTissue.at("/tests/0/observationScope")).put("tissue", "SYNTHETIC-undeclared-tissue");
        blocked(current("AD", true, inventedTissue), "A tissue label alone does not replace an actual same-person measurement");
    }

    @Test void oneSpecimenCannotBeDeclaredAsTwoDifferentTissuesToEvadeAConflict() throws Exception {
        var special = tissueEvent(false);
        ((ObjectNode) special.path("trio")).putObject("observationScopes").putObject("subject")
                .put("specimenId", "SYNTHETIC-same-specimen").put("tissue", "SYNTHETIC-brain").put("timepoint", "SYNTHETIC-T1");
        var raw = report("AD", special);
        ((ObjectNode) raw.path("tests").get(0)).put("result", "ABSENT").putObject("observationScope")
                .put("specimenId", "SYNTHETIC-same-specimen").put("tissue", "SYNTHETIC-blood").put("timepoint", "SYNTHETIC-T2");
        var result = current("AD", true, raw);
        blocked(result, "Contradictory specimen identity must not grant a different-observation exception");
        assertTrue(result.qualifications().stream().anyMatch(q -> q.reasons().contains("DENOVO_SPECIAL_SPECIMEN_SCOPE_CONFLICT")));
    }

    private ObjectNode core(ObjectNode special, DeNovoApplicabilityPolicy policy) throws Exception {
        String caseKey = "PMC999999997#SYNTHETIC-case";
        var core = mapper.createObjectNode().put("schemaVersion", "denovo-core-evaluation-intake-1.1.0")
                .put("status", "READY_FOR_EVALUATION").put("dataClass", "SYNTHETIC").put("caseKey", caseKey).put("policySha256", policy.sha256());
        core.putArray("blockers"); core.set("specialEvent", special.deepCopy());
        var values = core.putObject("values"); values.set("/context/allele", allele(false)); values.set("/groups/variant/resolvedAllele", allele(false));
        values.put("/context/hgncId", "HGNC:SYNTHETIC").put("/context/diseaseId", "MONDO:SYNTHETIC")
                .put("/context/inheritance", "AR").put("/context/geneDiseaseApplicability", "APPLICABLE").put("/context/phenotypeCategory", "CONSISTENT");
        for (String key : List.of("policyId", "allowAssumedParentage", "expertPolicyStatus")) values.set("/context/" + key, policy.snapshot().path(key));
        values.set("/context/policyVersion", policy.snapshot().path("version"));
        values.putArray("/groups/phenotype/positive").add("HP:0001250"); values.put("/groups/case/origin", "AUTHOR_COHORT");
        values.put("/groups/family/compatibility", "COMPATIBLE").put("/groups/testing/qualifiedForTarget", true)
                .put("/groups/proband/targetResult", "PRESENT").put("/groups/mother/targetResult", "ABSENT").put("/groups/father/targetResult", "ABSENT")
                .put("/groups/parentage/maternity", "CONFIRMED").put("/groups/parentage/paternity", "CONFIRMED");
        String sourceSha = FamilyDeNovoJson.sha256(caseKey.getBytes(StandardCharsets.UTF_8));
        core.putObject("sourcePins").putObject("special-event-original").put("sha256", sourceSha).put("role", "PAPER_ATTACHMENT");
        core.putObject("specialEventSource").put("sourceClass", "ORIGINAL_JSON_ATTACHMENT").put("sourceSha256", sourceSha)
                .put("replayStatus", "EXACT_REGISTERED_SOURCE_VALUES").putArray("parentSourceSha256s");
        var fact = mapper.createObjectNode().put("caseKey", caseKey).put("verification", "VERIFIED")
                .put("origin", "PROGRAM_DERIVED").put("availability", "REPORTED"); fact.set("value", special.deepCopy());
        var citation = fact.putArray("citations").addObject().put("sourceId", "special-event-original")
                .put("kind", "JSON_POINTER").put("locator", "/specialEvent").put("quote", mapper.writeValueAsString(special));
        byte[] raw = mapper.writeValueAsBytes(fact);
        var proof = core.putObject("fieldProofs").putObject("/extensions/specialEvent"); proof.set("fact", fact);
        proof.put("factCanonicalUtf8", new String(raw, StandardCharsets.UTF_8));
        proof.putObject("check").put("method", "PINNED_SPECIAL_EVENT_SOURCE_REPLAY").put("factSha256", FamilyDeNovoJson.sha256(raw))
                .putArray("basis").add(citation.deepCopy());
        return core;
    }

    private DeNovoLocalEvidenceEvaluator.Evaluation literature(ObjectNode core, DeNovoApplicabilityPolicy policy) throws Exception {
        var document = mapper.createObjectNode();
        var facts = document.putArray("documents").addObject().put("status", "SOURCE_READ").putObject("facts").put("pmcid", "PMC999999997");
        facts.putArray("issues");
        var row = facts.putArray("cases").addObject().put("caseLabel", "SYNTHETIC-case"); row.putArray("issues");
        row.putObject("alleleIdentity").put("hgncId", "HGNC:SYNTHETIC").set("allele", allele(false)); row.set("coreEvaluation", core);
        byte[] raw = mapper.writeValueAsBytes(document);
        var literature = new DeNovoLiteratureEvidence(new DeNovoLiteratureReader.Read(document, FamilyDeNovoJson.sha256(raw), raw, null, false));
        return evaluator().evaluateBound(context("AR"), mapper.createObjectNode(), policy, List.of(), literature);
    }

    @Test void literatureCoreCannotReplaceKnownParentalTargetConflictsOrExcludedParentage() throws Exception {
        var p = policy("AR", false);
        var baseline = literature(core(event(), p), p).result();
        assertEquals("MET", baseline.status(), baseline.toString()); assertEquals("SUPPORTING", baseline.strength());
        assertEquals(4, baseline.effectiveQuarterPoints());
        for (String field : List.of("/groups/mother/targetResult", "/groups/father/targetResult"))
            for (String value : List.of("PRESENT", "CONFLICT", "QUALITY_FAILED")) {
                var c = core(event(), p); ((ObjectNode) c.path("values")).put(field, value);
                blocked(literature(c, p), "Source-replayed ordinary core " + field + "=" + value + " cannot become typed ABSENT");
            }
        for (String field : List.of("/groups/parentage/maternity", "/groups/parentage/paternity")) {
            var c = core(event(), p); ((ObjectNode) c.path("values")).put(field, "EXCLUDED");
            blocked(literature(c, p), "Source-replayed EXCLUDED relationship cannot become typed CONFIRMED");
        }
    }

    @Test void literatureUnqualifiedOrdinaryAssayDoesNotHideItsKnownConflict() throws Exception {
        var p = policy("AR", false);
        var c = core(event(), p); ((ObjectNode) c.path("values")).put("/groups/testing/qualifiedForTarget", false)
                .put("/groups/mother/targetResult", "CONFLICT");
        blocked(literature(c, p), "Assay qualification=false cannot erase the underlying explicit target conflict");
    }

    @Test void nullEvaluationProjectionCannotEraseARetainedOrdinarySourceConflict() throws Exception {
        var p = policy("AR", false);
        for (String path : List.of("/groups/mother/targetResult", "/groups/parentage/paternity")) {
            var c = core(event(), p); ((ObjectNode) c.path("values")).putNull(path);
            ((ObjectNode) c.path("fieldProofs")).putObject(path).putObject("fact")
                    .put("verification", "CONFLICT").put("origin", "SOURCE_OBSERVATION").put("availability", "REPORTED").putNull("value");
            var result = literature(c, p);
            blocked(result, "Conflicting source observations remain relevant after a safe null value projection");
            assertTrue(result.qualifications().stream().anyMatch(q -> q.reasons().contains("DENOVO_ORDINARY_SOURCE_FACT_CONFLICT:" + path)));
        }
    }
}
