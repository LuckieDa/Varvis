package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

/** Invented source-reader output checks adapter contracts; it is not real-paper validation. */
class DeNovoSpecialEventAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temporary;
    private static final String FAMILY = "SYNTHETIC-family-1";
    private static final byte[] POLICY_SOURCE = "SYNTHETIC scope and phenotype specification.".getBytes(StandardCharsets.UTF_8);

    private ObjectNode allele(String contig) {
        return mapper.createObjectNode().put("assembly", "GRCh38").put("contig", contig)
                .put("position", 100).put("reference", "A").put("alternate", "G");
    }

    private ObjectNode event() {
        var event = mapper.createObjectNode().put("schemaVersion", "denovo-special-event-v1")
                .put("kind", "CONSTITUTIONAL").put("familyId", FAMILY).put("eventId", "origin-1")
                .put("personId", "child").put("probandId", "child").put("sex", "FEMALE")
                .put("ploidy", "DIPLOID").put("genotype", "HETEROZYGOUS").put("affectedStatus", "AFFECTED")
                .put("parentalAvailability", "AVAILABLE").put("otherAffectedMales", "NONE_IN_CHECKED_SCOPE")
                .put("eventOrigin", "DISTINCT_ORIGIN_CONFIRMED");
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

    private DeNovoApplicabilityPolicy policy(String inheritance) throws Exception {
        var policy = mapper.createObjectNode().put("schemaVersion", DeNovoApplicabilityPolicy.SCHEMA)
                .put("policyId", "SYNTHETIC-special").put("version", "1").put("materialClass", "SYNTHETIC")
                .put("allowAssumedParentage", true).put("expertPolicyStatus", "GENERAL_ALLOWED");
        policy.putObject("scope").put("hgncId", "HGNC:SYNTHETIC").put("diseaseId", "MONDO:SYNTHETIC").put("inheritance", inheritance);
        policy.putArray("sources").addObject().put("sourceId", "source").put("sha256", FamilyDeNovoJson.sha256(POLICY_SOURCE))
                .put("locator", "SYNTHETIC").put("quote", new String(POLICY_SOURCE, StandardCharsets.UTF_8));
        var rule = policy.putArray("conditions").addObject().put("conditionId", "consistent").put("category", "CONSISTENT");
        rule.putArray("presentAll").add("HP:0001250"); rule.putArray("absentAll"); rule.putArray("sourceIds").add("source");
        var term = policy.putArray("literatureTerms").addObject().put("hpoId", "HP:0001250");
        term.putArray("aliases").add("HP:0001250"); term.putArray("sourceIds").add("source");
        policy.putObject("specialEvents").put("mode", "SVI_1_1").putArray("sourceIds").add("source");
        byte[] raw = mapper.writeValueAsBytes(policy);
        return DeNovoApplicabilityPolicy.load(raw, FamilyDeNovoJson.sha256(raw), Map.of("source", POLICY_SOURCE), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
    }

    private ObjectNode report(String inheritance, JsonNode event) throws Exception {
        ObjectNode report;
        try (var input = getClass().getResourceAsStream("/denovo-automatic/synthetic-local-report.json")) {
            assertNotNull(input); report = (ObjectNode) mapper.readTree(input);
        }
        report.put("schemaVersion", DeNovoStructuredReport.SCHEMA_V2);
        ((ObjectNode) report.path("clinical")).put("inheritance", inheritance);
        report.set("specialEvent", event.deepCopy());
        return report;
    }

    private DeNovoStructuredReport readReport(ObjectNode report) throws Exception {
        byte[] raw = mapper.writeValueAsBytes(report);
        return DeNovoStructuredReport.read(raw, FamilyDeNovoJson.sha256(raw));
    }

    private ObjectNode context(String moi, String contig) {
        var context = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("moi", moi);
        context.putObject("scope").put("analysisDomain", "GERMLINE");
        context.putObject("gene").put("hgncId", "HGNC:SYNTHETIC"); context.putObject("disease").put("mondoId", "MONDO:SYNTHETIC");
        context.putObject("patientContext").put("patientContextId", "child").put("familyContextId", FAMILY)
                .put("role", "PROBAND").put("affectedStatus", "AFFECTED");
        var variant = context.putObject("variant");
        for (String name : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) variant.set(name, allele(contig));
        return context;
    }

    @Test void recessiveCurrentReportUsesTypedFactsAndMissingFactsDoNotReuseLegacyTrio() throws Exception {
        var report = report("AR", event());
        var evaluator = new DeNovoLocalEvidenceEvaluator(mapper);
        var result = evaluator.evaluateBound(context("AUTOSOMAL_RECESSIVE", "1"), mapper.createObjectNode(), policy("AR"), List.of(readReport(report)));
        assertEquals("MET", result.result().status(), result.toString());
        assertEquals("PS2", result.result().code()); assertEquals(4, result.result().effectiveQuarterPoints());
        assertEquals("SUPPORTING", result.result().strength());
        assertEquals("ONE_LEVEL_AR_NO_SECOND_P_LP", result.result().strengthAdjustment());
        report.remove("specialEvent");
        var missing = evaluator.evaluateBound(context("AR", "1"), mapper.createObjectNode(), policy("AR"), List.of(readReport(report)));
        assertEquals("NOT_EVALUATED", missing.result().status());
        assertTrue(missing.qualifications().stream().anyMatch(q -> q.reasons().contains("DENOVO_SPECIAL_EVENT_REQUIRED")));
    }

    @Test void conflictingEventsAndWrongActualPersonCannotBeMergedIntoOneCurrentObservation() throws Exception {
        var first = report("AR", event());
        var second = report("AR", event().put("eventId", "different-origin")).put("reportId", "SYNTHETIC-other");
        var evaluator = new DeNovoLocalEvidenceEvaluator(mapper);
        var conflict = evaluator.evaluateBound(context("AR", "1"), mapper.createObjectNode(), policy("AR"), List.of(readReport(first), readReport(second)));
        assertEquals("NOT_EVALUATED", conflict.result().status());
        assertTrue(conflict.qualifications().stream().anyMatch(q -> q.reasons().contains("DENOVO_SPECIAL_EVENT_SOURCE_CONFLICT")));
        var wrong = report("AR", event().put("personId", "unbound-person"));
        var unbound = evaluator.evaluateBound(context("AR", "1"), mapper.createObjectNode(), policy("AR"), List.of(readReport(wrong)));
        assertTrue(unbound.qualifications().stream().anyMatch(q -> q.reasons().contains("DENOVO_SPECIAL_EVENT_REPORT_PERSON_MISMATCH")));
    }

    private ObjectNode xEvent() {
        var event = event();
        var partner = (ObjectNode) event.path("partner");
        partner.put("status", "UNRESOLVED").put("phase", "UNKNOWN").putNull("allele"); partner.putArray("sourceRefs");
        return event;
    }

    @Test void xLinkedSourceCannotInventARegionWhenDependencyIsAbsent() throws Exception {
        var event = xEvent().put("sex", "MALE").put("ploidy", "HAPLOID").put("genotype", "HEMIZYGOUS_ALT");
        var report = report("X_LINKED", event); ((ObjectNode) report.path("target")).put("contig", "X");
        var result = new DeNovoLocalEvidenceEvaluator(mapper).evaluateBound(context("X_LINKED_RECESSIVE", "X"),
                mapper.createObjectNode(), policy("X_LINKED"), List.of(readReport(report)));
        assertEquals("NOT_EVALUATED", result.result().status());
        assertTrue(result.result().events().stream().anyMatch(e -> "DENOVO_SEX_REGION_DEPENDENCY_UNAVAILABLE".equals(e.reasonCode())), result.toString());
    }

    @Test void maternalOriginUsesMothersParentsAndDoesNotReuseTheChildTrio() throws Exception {
        var event = xEvent().put("kind", "MATERNAL_ORIGIN").put("personId", "mother").put("affectedStatus", "UNAFFECTED").put("probandSex", "MALE");
        ((ObjectNode) event.path("trio")).put("personId", "mother").put("motherId", "grandmother").put("fatherId", "grandfather");
        var report = report("X_LINKED", event); ((ObjectNode) report.path("target")).put("contig", "X");
        ((ObjectNode) report.path("tests").get(1)).put("result", "PRESENT");
        DeNovoSpecialEvent.RegionSupply syntheticRegion = a -> new DeNovoSpecialEvent.RegionFact(a.path("assembly").asText(),
                new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(a), "NON_PAR", "8".repeat(64));
        var result = new DeNovoLocalEvidenceEvaluator(mapper, syntheticRegion).evaluateBound(context("X_LINKED_RECESSIVE", "X"),
                mapper.createObjectNode(), policy("X_LINKED"), List.of(readReport(report)));
        assertEquals("MET", result.result().status(), result.toString());
        assertEquals("PS2", result.result().code()); assertEquals(4, result.result().effectiveQuarterPoints());
        assertEquals(1, result.result().events().stream().filter(e -> "COUNTED".equals(e.disposition())).count());
    }

    @Test void parentalPostzygoticSourceKeepsBoundParentAndReportsUnsupportedRule() throws Exception {
        var event = event().put("kind", "POSTZYGOTIC").put("personId", "mother").put("affectedStatus", "UNAFFECTED");
        ((ObjectNode) event.path("trio")).put("personId", "mother").put("motherId", "grandmother").put("fatherId", "grandfather");
        var raw = report("AD", event);
        // This test isolates the unsupported parental-origin method; both source
        // sections consistently report the mother's observed target allele.
        ((ObjectNode) raw.path("tests").get(1)).put("result", "PRESENT");
        var report = readReport(raw);
        var result = new DeNovoLocalEvidenceEvaluator(mapper).evaluateBound(context("AD", "1"), mapper.createObjectNode(), policy("AD"), List.of(report));
        assertEquals("NOT_EVALUATED", result.result().status());
        assertTrue(result.result().events().stream().anyMatch(e -> "DENOVO_PARENTAL_POSTZYGOTIC_RULE_NOT_IMPLEMENTED".equals(e.reasonCode())), result.toString());
        assertFalse(result.qualifications().stream().anyMatch(q -> q.reasons().contains("DENOVO_SPECIAL_EVENT_REPORT_PERSON_MISMATCH")));
        assertEquals("mother", report.snapshot().at("/specialEvent/personId").asText());
    }

    private ObjectNode core(String caseKey, ObjectNode event, DeNovoApplicabilityPolicy policy) throws Exception {
        var core = mapper.createObjectNode().put("schemaVersion", "denovo-core-evaluation-intake-1.1.0")
                .put("status", "READY_FOR_EVALUATION").put("caseKey", caseKey).put("policySha256", policy.sha256());
        core.putArray("blockers"); core.set("specialEvent", event.deepCopy());
        var values = core.putObject("values");
        values.set("/context/allele", allele("1")); values.set("/groups/variant/resolvedAllele", allele("1"));
        values.put("/context/hgncId", "HGNC:SYNTHETIC").put("/context/diseaseId", "MONDO:SYNTHETIC")
                .put("/context/inheritance", "AR").put("/context/geneDiseaseApplicability", "APPLICABLE")
                .put("/context/phenotypeCategory", "CONSISTENT");
        for (String key : List.of("policyId", "allowAssumedParentage", "expertPolicyStatus")) values.set("/context/" + key, policy.snapshot().path(key));
        values.set("/context/policyVersion", policy.snapshot().path("version"));
        values.putArray("/groups/phenotype/positive").add("HP:0001250");
        values.put("/groups/case/origin", "AUTHOR_COHORT");
        values.put("/groups/family/compatibility", "COMPATIBLE");
        // No ordinary proband-trio or independence flag is fabricated for the typed route.
        String sourceSha = FamilyDeNovoJson.sha256(caseKey.getBytes(StandardCharsets.UTF_8));
        core.putObject("sourcePins").putObject("special-event-original").put("sha256", sourceSha).put("role", "PAPER_ATTACHMENT");
        core.putObject("specialEventSource").put("sourceClass", "ORIGINAL_JSON_ATTACHMENT").put("sourceSha256", sourceSha)
                .put("replayStatus", "EXACT_REGISTERED_SOURCE_VALUES").putArray("parentSourceSha256s");
        var fact = mapper.createObjectNode().put("caseKey", caseKey).put("verification", "VERIFIED")
                .put("origin", "PROGRAM_DERIVED").put("availability", "REPORTED");
        fact.set("value", event.deepCopy());
        var citation = fact.putArray("citations").addObject().put("sourceId", "special-event-original")
                .put("kind", "JSON_POINTER").put("locator", "/specialEvent").put("quote", mapper.writeValueAsString(event));
        byte[] factRaw = mapper.writeValueAsBytes(fact);
        var proof = core.putObject("fieldProofs").putObject("/extensions/specialEvent"); proof.set("fact", fact);
        proof.put("factCanonicalUtf8", new String(factRaw, StandardCharsets.UTF_8));
        proof.putObject("check").put("method", "PINNED_SPECIAL_EVENT_SOURCE_REPLAY")
                .put("factSha256", FamilyDeNovoJson.sha256(factRaw)).putArray("basis").add(citation.deepCopy());
        return core;
    }

    @Test void coreRequiresExactSourceProofAndDoesNotTrustAReadyFlag() throws Exception {
        var policy = policy("AR"); var group = new Group("GRCh38:1:100:A:G", "HGNC:SYNTHETIC", "MONDO:SYNTHETIC", "AR");
        var core = core("PMC1#case-1", event(), policy);
        var qualified = DeNovoCoreFacts.read(core, allele("1"), group, policy);
        assertTrue(qualified.issues().isEmpty(), qualified.toString()); assertNotNull(qualified.facts().special());
        ((ObjectNode) core.path("specialEvent")).put("eventId", "changed-after-source-review");
        assertTrue(DeNovoCoreFacts.read(core, allele("1"), group, policy).issues().contains("DENOVO_CORE_SPECIAL_EVENT_PROOF_INVALID"));
        var noProof = core("PMC1#case-1", event(), policy); noProof.remove("fieldProofs");
        assertTrue(DeNovoCoreFacts.read(noProof, allele("1"), group, policy).issues().contains("DENOVO_CORE_SPECIAL_EVENT_PROOF_INVALID"));
        var wrongVersion = core("PMC1#case-1", event(), policy).put("schemaVersion", "denovo-core-evaluation-intake-1.0.0");
        assertTrue(DeNovoCoreFacts.read(wrongVersion, allele("1"), group, policy).issues().contains("DENOVO_CORE_SCHEMA_INVALID"));
        var cited = core("PMC1#case-1", event(), policy);
        ((ObjectNode) cited.path("values")).put("/groups/case/origin", "CITED_ONLY");
        assertTrue(DeNovoCoreFacts.read(cited, allele("1"), group, policy).issues().contains("DENOVO_CORE_EVENT_NOT_QUALIFIED"));
        var familyConflict = core("PMC1#case-1", event(), policy);
        ((ObjectNode) familyConflict.path("values")).put("/groups/family/compatibility", "CONFLICT");
        assertEquals(FamilyHistory.CONFLICT, DeNovoCoreFacts.read(familyConflict, allele("1"), group, policy).facts().familyHistory());
        var knownMosaic = core("PMC1#case-1", event(), policy);
        ((ObjectNode) knownMosaic.path("values")).put("/conditions/specialFamilyModel", "MOSAIC");
        assertEquals(FamilyHistory.MOSAIC_OR_SIBLING_RECURRENCE,
                DeNovoCoreFacts.read(knownMosaic, allele("1"), group, policy).facts().familyHistory());
    }

    @Test void repeatedFamilyEventAcrossPapersIsOneBiologicalEvent() throws Exception {
        var first = pythonReplay("shared-first", true, "PMC999999995");
        var second = pythonReplay("shared-second", true, "PMC999999996");
        var group = replayGroup(first.core());
        var firstRead = DeNovoCoreFacts.read(first.core(), first.core().path("values").path("/context/allele"), group, first.policy());
        var secondRead = DeNovoCoreFacts.read(second.core(), second.core().path("values").path("/context/allele"), group, second.policy());
        assertNotNull(firstRead.eventAuthority()); assertEquals(firstRead.eventAuthority(), secondRead.eventAuthority());
        assertEquals(firstRead.eventId(), secondRead.eventId());
        var result = evaluateLiterature(List.of(first, second));
        assertEquals("MET", result.result().status(), result.toString()); assertEquals(2, result.result().effectiveQuarterPoints());
        assertEquals(1, result.result().events().stream().filter(e -> "COUNTED".equals(e.disposition())).count());
        assertEquals(1, result.result().events().stream().filter(e -> "DUPLICATE".equals(e.disposition())).count());
        var invalidScope = second.core().deepCopy();
        ((ObjectNode) invalidScope.at("/specialEventSource/eventIdentityProof")).put("eventLocator", "/unverified-event-identity");
        assertNull(DeNovoCoreFacts.read(invalidScope, invalidScope.path("values").path("/context/allele"), group, second.policy()).eventAuthority());
    }

    @Test void identicalLocalLabelsInDifferentOriginalsDoNotProveSharedOrIndependentEvents() throws Exception {
        var first = pythonReplay("local-first", false, "PMC999999995");
        var second = pythonReplay("local-second", false, "PMC999999996");
        var group = replayGroup(first.core());
        var firstRead = DeNovoCoreFacts.read(first.core(), first.core().path("values").path("/context/allele"), group, first.policy());
        var secondRead = DeNovoCoreFacts.read(second.core(), second.core().path("values").path("/context/allele"), group, second.policy());
        assertEquals(first.core().at("/specialEvent/familyId"), second.core().at("/specialEvent/familyId"));
        assertEquals(first.core().at("/specialEvent/eventId"), second.core().at("/specialEvent/eventId"));
        assertNotEquals(firstRead.eventAuthority(), secondRead.eventAuthority()); assertNotEquals(firstRead.eventId(), secondRead.eventId());
        assertEquals("MET", calculate(group, first.policy(), firstRead).status());
        var result = evaluateLiterature(List.of(first, second));
        assertEquals("NOT_EVALUATED", result.result().status(), result.toString());
        assertTrue(result.qualifications().stream().allMatch(q -> q.reasons().contains("DENOVO_LITERATURE_EVENT_IDENTITY_SCOPE_UNRESOLVED")));
        assertTrue(result.result().events().stream().noneMatch(e -> "DUPLICATE".equals(e.disposition()) || "COUNTED".equals(e.disposition())));
    }

    private DeNovoLocalEvidenceEvaluator.Evaluation evaluateLiterature(List<Replayed> replays) throws Exception {
        var document = mapper.createObjectNode();
        var documents = document.putArray("documents");
        for (var replay : replays) {
            var core = replay.core(); String caseKey = core.path("caseKey").asText();
            String pmcid = caseKey.substring(0, caseKey.indexOf('#'));
            var facts = documents.addObject().put("status", "SOURCE_READ").putObject("facts").put("pmcid", pmcid);
            facts.putArray("issues"); var row = facts.putArray("cases").addObject().put("caseLabel", caseKey.substring(caseKey.indexOf('#') + 1));
            row.putArray("issues"); row.putObject("alleleIdentity").put("hgncId", core.path("values").path("/context/hgncId").asText())
                    .set("allele", core.path("values").path("/context/allele"));
            row.set("coreEvaluation", core);
        }
        byte[] raw = mapper.writeValueAsBytes(document);
        var literature = new DeNovoLiteratureEvidence(new DeNovoLiteratureReader.Read(document, FamilyDeNovoJson.sha256(raw), raw, null, false));
        var first = replays.get(0); var context = context("AR", "1");
        ((ObjectNode) context.path("gene")).put("hgncId", first.core().path("values").path("/context/hgncId").asText());
        ((ObjectNode) context.path("disease")).put("mondoId", first.core().path("values").path("/context/diseaseId").asText());
        return new DeNovoLocalEvidenceEvaluator(mapper).evaluateBound(context, mapper.createObjectNode(), first.policy(), List.of(), literature);
    }

    private record Replayed(ObjectNode core, DeNovoApplicabilityPolicy policy) { }

    private Replayed pythonReplay(String name, boolean registry, String pmcid) throws Exception {
        Path python = Path.of(System.getProperty("denovo.test.python", "missing-python"));
        Path scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required");
        Path output = temporary.resolve(name);
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B", "-c",
                "from test_core_special_partner import write_java_fixture; import sys; write_java_fixture(sys.argv[1], registry=sys.argv[2]=='true', pmcid=sys.argv[3])",
                output.toString(), Boolean.toString(registry), pmcid),
                scripts, Duration.ofSeconds(30));
        assertTrue(process.isSuccess(), process.getOutput());
        JsonNode fixture = mapper.readTree(Files.readAllBytes(output.resolve("java-special-partner-fixture.json")));
        byte[] policyBytes = mapper.writeValueAsBytes(fixture.path("policy"));
        Map<String, byte[]> policySources = new HashMap<>();
        fixture.path("policySources").properties().forEach(p -> policySources.put(p.getKey(), p.getValue().asText().getBytes(StandardCharsets.UTF_8)));
        var policy = DeNovoApplicabilityPolicy.load(policyBytes, FamilyDeNovoJson.sha256(policyBytes), policySources,
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
        return new Replayed((ObjectNode) fixture.path("core"), policy);
    }

    private Group replayGroup(JsonNode core) {
        var values = core.path("values");
        return new Group("GRCh38:1:100:A:G", values.path("/context/hgncId").asText(),
                values.path("/context/diseaseId").asText(), values.path("/context/inheritance").asText());
    }

    @Test void partnerIndependenceComesFromExistingPythonSourceAuditAndCannotBeBorrowedOrClaimed() throws Exception {
        var replay = pythonReplay("python-partner-replay", false, "PMC999999995");
        var core = replay.core(); var policy = replay.policy();
        JsonNode target = core.path("values").path("/context/allele");
        var group = replayGroup(core);
        var qualified = DeNovoCoreFacts.read(core, target, group, policy);
        assertTrue(qualified.issues().isEmpty(), qualified.toString());
        var result = calculate(group, policy, qualified);
        assertEquals("MET", result.status(), result.toString()); assertEquals("SUPPORTING", result.strength());
        assertEquals(2, result.effectiveQuarterPoints());
        assertEquals("NONE", result.strengthAdjustment());
        var missingProof = core.deepCopy(); missingProof.remove("partnerQualificationProof");
        var missing = DeNovoCoreFacts.read(missingProof, target, group, policy);
        assertTrue(missing.issues().contains("DENOVO_AR_PARTNER_PROOF_INVALID"));
        assertEquals("NOT_EVALUATED", calculate(group, policy, missing).status());
        var wrongPartner = core.deepCopy();
        ((ObjectNode) wrongPartner.at("/partnerQualification/partnerAllele")).put("position", 102);
        assertTrue(DeNovoCoreFacts.read(wrongPartner, target, group, policy).issues().contains("DENOVO_AR_PARTNER_INDEPENDENCE_NOT_VERIFIED"));
        var report = report("AR", event());
        var claimed = (ObjectNode) report.path("specialEvent").path("partner");
        claimed.put("status", "INDEPENDENT_P_LP").put("phase", "TRANS").set("allele", allele("1").put("position", 200));
        claimed.putArray("sourceRefs").add("7".repeat(64) + "#/claimed-classification");
        var unverified = new DeNovoLocalEvidenceEvaluator(mapper).evaluateBound(context("AR", "1"), mapper.createObjectNode(), policy("AR"), List.of(readReport(report)));
        assertEquals("NOT_EVALUATED", unverified.result().status());
        assertTrue(unverified.result().events().stream().anyMatch(e -> "DENOVO_AR_PARTNER_INDEPENDENCE_NOT_VERIFIED".equals(e.reasonCode())));
    }

    private Result calculate(Group group, DeNovoApplicabilityPolicy policy, DeNovoCoreFacts.Read core) {
        return new DeNovoEvidenceCalculator().evaluate(group, policy.calculation(), List.of(new Observation("synthetic-source-replay",
                core.eventId().isBlank() ? "SYNTHETIC-unqualified-event" : core.eventId(), SourceKind.LITERATURE_CASE, core.facts(), Independence.ESTABLISHED,
                List.of("9".repeat(64) + "#/specialEvent"), core.issues())));
    }
    @Test void syntheticCoreCannotUseAResearchPolicyToPromoteItsSourceClass() throws Exception {
        var originalPolicy = policy("AR"); var p = originalPolicy.snapshot().put("materialClass", "SOURCE_ASSESSED_RESEARCH");
        byte[] bytes = mapper.writeValueAsBytes(p);
        var research = DeNovoApplicabilityPolicy.load(bytes, FamilyDeNovoJson.sha256(bytes), Map.of("source", POLICY_SOURCE), DeNovoApplicabilityPolicy.Use.RESEARCH);
        var core = core("PMC1#case-1", event(), research).put("dataClass", "SYNTHETIC");
        var group = new Group("GRCh38:1:100:A:G", "HGNC:SYNTHETIC", "MONDO:SYNTHETIC", "AR");
        var read = DeNovoCoreFacts.read(core, allele("1"), group, research);
        assertTrue(read.issues().contains("DENOVO_CORE_SPECIAL_DATA_CLASS_NOT_ALLOWED"));
        assertEquals("NOT_EVALUATED", calculate(group, research, read).status());
    }

    @Test void typedHypothesisUsesSourcePhenotypesWithoutInventingAPriorDiagnosis() throws Exception {
        var policy = policy("AR"); var raw = report("AR", event());
        ((ObjectNode) raw.path("clinical")).put("reportedDiseaseId", "UNKNOWN").put("inheritance", "UNKNOWN");
        var result = new DeNovoLocalEvidenceEvaluator(mapper).evaluateBound(context("AR", "1"), mapper.createObjectNode(), policy, List.of(readReport(raw)));
        assertEquals("MET", result.result().status(), result.toString());
        assertEquals("SUPPORTING", result.result().strength());
        assertEquals("UNKNOWN", raw.at("/clinical/reportedDiseaseId").asText());
        ((ObjectNode) raw.path("clinical")).put("reportedDiseaseId", "MONDO:DIFFERENT");
        var different = new DeNovoLocalEvidenceEvaluator(mapper).evaluateBound(context("AR", "1"), mapper.createObjectNode(), policy, List.of(readReport(raw)));
        assertEquals("NOT_EVALUATED", different.result().status());
    }

    @Test void typedReportCannotHideAContradictoryBackgroundInASupplementaryReport() throws Exception {
        var typed = report("AR", event());
        var supplement = report("AR", event()).put("reportId", "SYNTHETIC-supplement");
        supplement.remove("specialEvent");
        var evaluator = new DeNovoLocalEvidenceEvaluator(mapper);
        var compatible = evaluator.evaluateBound(context("AR", "1"), mapper.createObjectNode(), policy("AR"), List.of(readReport(typed), readReport(supplement)));
        assertEquals("MET", compatible.result().status(), compatible.toString());
        for (String field : List.of("reportedDiseaseId", "inheritance")) {
            var contradictory = supplement.deepCopy();
            ((ObjectNode) contradictory.path("clinical")).put(field, field.equals("inheritance") ? "AD" : "MONDO:DIFFERENT");
            var result = evaluator.evaluateBound(context("AR", "1"), mapper.createObjectNode(), policy("AR"), List.of(readReport(typed), readReport(contradictory)));
            assertEquals("NOT_EVALUATED", result.result().status(), result.toString());
            assertTrue(result.qualifications().stream().anyMatch(q -> q.reasons().contains("DENOVO_REPORTED_DISEASE_OR_INHERITANCE_UNRESOLVED")));
        }
        ((ObjectNode) typed.path("clinical")).put("reportedDiseaseId", "UNKNOWN").put("inheritance", "UNKNOWN").put("familyHistory", "UNKNOWN");
        var missingHistory = evaluator.evaluateBound(context("AR", "1"), mapper.createObjectNode(), policy("AR"), List.of(readReport(typed)));
        assertEquals("NOT_EVALUATED", missingHistory.result().status());
        assertTrue(missingHistory.result().events().stream().anyMatch(e -> "DENOVO_FAMILY_HISTORY_UNRESOLVED".equals(e.reasonCode())));
    }

}
