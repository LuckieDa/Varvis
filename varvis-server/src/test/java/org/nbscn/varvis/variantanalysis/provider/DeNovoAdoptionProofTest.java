package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independent expected rule branches through actual retained local-source/selected execution.
 * All families, clinical policy and source clauses are explicitly synthetic; no clinical adoption. */
class DeNovoAdoptionProofTest {
    @TempDir Path temporary;
    private static final String TISSUE = "TISSUE_DIFFERENTIAL_PS2", COMBINED = "SVI_1_1_COMBINED_PS2";

    private DeNovoBatchFixture fixture(String name, String mode) throws Exception {
        var f = new DeNovoBatchFixture(temporary.resolve(name));
        boolean expert = !"SVI_1_1".equals(mode);
        f.phenotype.put("expertPolicyStatus", expert ? "APPLICABLE_IMPLEMENTED" : "GENERAL_ALLOWED");
        f.phenotype.putObject("specialEvents").put("mode", mode).putArray("sourceIds").add("s");
        String clause = "SYNTHETIC INVENTED software-only rules. HP:0001250 is consistent in this test only. "
                + "TISSUE_DIFFERENTIAL_PS2: C1 requires detected subject, qualified absent parents and confirmed relationships. "
                + "C2 requires qualified same-person affected tissue versus different comparison tissue with adequate detection and higher affected fraction. "
                + "C1+C2 gives fixed Strong; C1 alone or unavailable parents+C2 gives fixed Moderate; no SVI sum. "
                + "SVI_1_1_COMBINED_PS2: qualifying assumed-parentage observations retain assumed SVI points and use combined PS2. "
                + "SVI_1_1 uses ordinary PS2/PM6 parentage selection. This source is invented, not a published expert specification.";
        byte[] source = clause.getBytes(StandardCharsets.UTF_8); Files.write(f.root.resolve("source.txt"), source);
        String sha = DeNovoBatchFixture.sha(source);
        ((ObjectNode) f.phenotype.at("/sources/0")).put("sha256", sha).put("locator", "synthetic rule paragraph").put("quote", clause);
        ((ObjectNode) f.catalog.at("/sources/0")).put("sha256", sha);
        ((ObjectNode) f.manifest.at("/sources/0")).put("sha256", sha);
        for (var entry : f.catalog.path("entries")) if (expert) ((ObjectNode) entry).put("disposition", "SPECIALIZED_IMPLEMENTED");
        f.report.put("schemaVersion", DeNovoStructuredReport.SCHEMA_V2);
        var event = f.mapper.createObjectNode().put("schemaVersion", "denovo-special-event-v1")
                .put("kind", TISSUE.equals(mode) ? "POSTZYGOTIC" : "CONSTITUTIONAL")
                .put("familyId", DeNovoBatchFixture.FAMILY).put("eventId", "SYNTHETIC-private-origin")
                .put("personId", DeNovoBatchFixture.PATIENT).put("probandId", DeNovoBatchFixture.PATIENT)
                .put("sex", "FEMALE").put("ploidy", "DIPLOID").put("genotype", "HETEROZYGOUS")
                .put("affectedStatus", "AFFECTED").put("parentalAvailability", "AVAILABLE")
                .put("otherAffectedMales", "NONE_IN_CHECKED_SCOPE").put("eventOrigin", "UNKNOWN");
        event.putObject("trio").put("personId", DeNovoBatchFixture.PATIENT).put("motherId", "mother").put("fatherId", "father")
                .put("subjectTarget", "PRESENT").put("motherTarget", "ABSENT").put("fatherTarget", "ABSENT")
                .put("maternity", "CONFIRMED").put("paternity", "CONFIRMED")
                .put("maternityMethod", "SNP_PANEL").put("paternityMethod", "SNP_PANEL")
                .put("method", "SANGER").put("targetCoverage", "EXPLICIT_TARGET").put("detectionCapability", "ADEQUATE").put("quality", "VALID");
        var partner = event.putObject("partner").put("status", "NONE_IN_CHECKED_SCOPE").put("phase", "UNKNOWN");
        partner.putNull("allele"); partner.putArray("sourceRefs"); event.putArray("siblings");
        var tissues = event.putArray("tissues");
        if (TISSUE.equals(mode)) {
            ((ObjectNode) f.report.path("clinical")).put("mosaicism", "KNOWN");
            tissues.addObject().put("personId", DeNovoBatchFixture.PATIENT).put("tissueId", "SYNTHETIC-private-lesion")
                    .put("affected", true).put("altReads", 5).put("totalReads", 100).put("limit", 0.001)
                    .put("method", "TARGETED_SEQUENCING").put("quality", "HIGH_CONFIDENCE");
            tissues.addObject().put("personId", DeNovoBatchFixture.PATIENT).put("tissueId", "SYNTHETIC-private-comparison")
                    .put("affected", false).put("altReads", 0).put("totalReads", 100).put("limit", 0.001)
                    .put("method", "TARGETED_SEQUENCING").put("quality", "HIGH_CONFIDENCE");
        }
        f.report.set("specialEvent", event); f.save(); return f;
    }

    private ObjectNode evaluate(DeNovoBatchFixture f) throws Exception {
        return f.load().evaluateBound(f.context(), f.mapper.createObjectNode());
    }

    private JsonNode proof(JsonNode result) {
        JsonNode p = result.at("/publicSummary/adoptionProof");
        assertEquals("denovo-adoption-proof-v1", p.path("schemaVersion").asText(), result.toPrettyString());
        assertEquals(result.at("/qualifiedResult/adoptionProof"), p, "Public proof must be the actual qualifier/calculator projection");
        return p;
    }

    private void assumed(DeNovoBatchFixture f) {
        for (var row : f.report.path("parentage")) ((ObjectNode) row).put("result", "NOT_TESTED").put("method", "NOT_DONE");
        ((ObjectNode) f.report.at("/specialEvent/trio")).put("maternity", "ASSUMED_NOT_TESTED").put("paternity", "ASSUMED_NOT_TESTED")
                .put("maternityMethod", "NOT_TESTED").put("paternityMethod", "NOT_TESTED");
    }

    private void unavailable(DeNovoBatchFixture f) {
        ((ObjectNode) f.report.path("specialEvent")).put("parentalAvailability", "UNAVAILABLE");
        ((ObjectNode) f.report.at("/specialEvent/trio")).put("motherTarget", "UNKNOWN").put("fatherTarget", "UNKNOWN")
                .put("maternity", "INCONCLUSIVE").put("paternity", "INCONCLUSIVE")
                .put("maternityMethod", "UNKNOWN").put("paternityMethod", "UNKNOWN");
        var tests = (ArrayNode) f.report.path("tests"); tests.remove(2); tests.remove(1); f.report.putArray("parentage");
    }

    @Test void fixedStrongHasActualPolicyAndSameSourceObservationProofWithoutPrivateIdentity() throws Exception {
        var f = fixture("strong", TISSUE); var result = evaluate(f); JsonNode p = proof(result);
        assertEquals("MET", result.at("/criteria/PS2/evaluationResult/status").asText());
        assertEquals("STRONG", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals(0, result.at("/publicSummary/effectiveQuarterPoints").asInt());
        assertEquals("ADOPTED", p.path("status").asText()); assertEquals(TISSUE, p.at("/policy/eventMode").asText());
        assertEquals("SYNTHETIC-fixture", p.at("/policy/policyId").asText()); assertEquals("1", p.at("/policy/policyVersion").asText());
        assertEquals(f.manifest.at("/phenotypePolicies/0/sha256"), p.at("/policy/phenotypePolicySha256"));
        assertEquals(f.phenotype.at("/sources/0/sha256"), p.at("/policy/sources/0/sourceSha256"));
        assertEquals("synthetic rule paragraph", p.at("/policy/sources/0/locator").asText());
        assertEquals(1, p.path("events").size());
        assertEquals(1, p.at("/events/0/ordinal").asInt()); assertEquals("COUNTED", p.at("/events/0/disposition").asText());
        assertEquals("COMPLETE", p.path("projectionStatus").asText());
        assertEquals("C1_AND_C2", p.at("/events/0/conditionBranch").asText());
        assertEquals("STRONG", p.at("/events/0/adoptedStrength").asText());
        assertEquals("MET", p.at("/events/0/c1/status").asText()); assertEquals("MET", p.at("/events/0/c2/status").asText());
        assertEquals("PRESENT", p.at("/events/0/c1/probandTarget").asText());
        assertEquals("ABSENT_QUALIFIED", p.at("/events/0/c1/motherTarget").asText());
        assertEquals("ABSENT_QUALIFIED", p.at("/events/0/c1/fatherTarget").asText());
        assertEquals("CONFIRMED", p.at("/events/0/c1/maternity").asText());
        assertEquals("CONFIRMED", p.at("/events/0/c1/paternity").asText());
        assertTrue(p.at("/events/0/c2/samePerson").asBoolean()); assertTrue(p.at("/events/0/c2/distinctTissues").asBoolean());
        assertTrue(p.at("/events/0/c2/detectionAdequate").asBoolean());
        assertEquals(5, p.at("/events/0/c2/observations/0/altReads").asInt());
        assertEquals(100, p.at("/events/0/c2/observations/0/totalReads").asInt());
        assertEquals(0, p.at("/events/0/c2/observations/1/altReads").asInt());
        String reportSha = f.manifest.at("/reports/0/sha256").asText();
        assertEquals(reportSha, p.at("/events/0/c1/sources/0/sourceSha256").asText());
        assertEquals("/specialEvent/trio", p.at("/events/0/c1/sources/0/locator").asText());
        assertEquals(reportSha, p.at("/events/0/c2/observations/0/sources/0/sourceSha256").asText());
        assertEquals("/specialEvent/tissues/0", p.at("/events/0/c2/observations/0/sources/0/locator").asText());
        String json = p.toString();
        for (String secret : List.of(DeNovoBatchFixture.PATIENT, DeNovoBatchFixture.FAMILY, "SYNTHETIC-private-origin",
                "SYNTHETIC-private-lesion", "SYNTHETIC-private-comparison", f.root.toString())) assertFalse(json.contains(secret), secret);
    }

    @Test void c1AloneAdoptsModerateWithoutPretendingMissingC2WasDisproved() throws Exception {
        var f = fixture("c1", TISSUE); ((ObjectNode) f.report.path("specialEvent")).putArray("tissues"); f.save();
        var result = evaluate(f); JsonNode p = proof(result);
        assertEquals("MODERATE", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals("ADOPTED", p.path("status").asText());
        assertEquals("C1_ONLY", p.at("/events/0/conditionBranch").asText());
        assertEquals("MET", p.at("/events/0/c1/status").asText());
        assertEquals("NOT_ESTABLISHED", p.at("/events/0/c2/status").asText());
        assertEquals(0, p.at("/events/0/c2/observations").size());
    }

    @Test void c1StillAdoptsModerateWhenComparisonSensitivityCannotEstablishC2() throws Exception {
        var f = fixture("sensitivity", TISSUE); ((ObjectNode) f.report.at("/specialEvent/tissues/1")).put("limit", 0.9); f.save();
        var result = evaluate(f); JsonNode p = proof(result);
        assertEquals("MODERATE", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals("ADOPTED", p.path("status").asText());
        assertEquals("C1_ONLY", p.at("/events/0/conditionBranch").asText());
        assertEquals("NOT_ESTABLISHED", p.at("/events/0/c2/status").asText());
        assertEquals(0.9, p.at("/events/0/c2/observations/1/limit").asDouble());
    }

    @Test void unavailableParentsAndAdequateC2AdoptModerateWithoutInventingConfirmedParentage() throws Exception {
        var f = fixture("unavailable", TISSUE); unavailable(f); f.save(); var result = evaluate(f); JsonNode p = proof(result);
        assertEquals("MET", result.at("/criteria/PS2/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("MODERATE", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals("ADOPTED", p.path("status").asText());
        assertFalse(result.at("/publicSummary/events/0/confirmedParentage").asBoolean());
        assertEquals("NOT_ESTABLISHED", p.at("/events/0/c1/status").asText());
        assertEquals("MET", p.at("/events/0/c2/status").asText());
        assertEquals("PARENTS_UNAVAILABLE_AND_C2", p.at("/events/0/conditionBranch").asText());
        assertEquals("UNAVAILABLE", p.at("/events/0/parentalAvailability/value").asText());
    }

    @Test void ordinaryCounterevidenceAndWrongTrioNeverBecomeAnAdoptedExpertProof() throws Exception {
        for (String fault : List.of("positive", "quality", "excluded", "cross-trio", "unknown", "unknown-phenotype")) {
            var f = fixture("reject-" + fault, TISSUE);
            switch (fault) {
                case "positive" -> ((ObjectNode) f.report.at("/tests/1")).put("result", "PRESENT");
                case "quality" -> ((ObjectNode) f.report.at("/tests/1")).put("quality", "FAILED");
                case "excluded" -> ((ObjectNode) f.report.at("/parentage/0")).put("result", "EXCLUDED");
                case "cross-trio" -> ((ObjectNode) f.report.at("/specialEvent/trio")).put("motherId", "SYNTHETIC-unrelated-parent");
                case "unknown" -> { ((ObjectNode) f.report.at("/tests/1")).put("result", "UNKNOWN");
                    ((ObjectNode) f.report.at("/specialEvent/trio")).put("motherTarget", "UNKNOWN"); }
                case "unknown-phenotype" -> ((ObjectNode) f.report.path("clinical")).putArray("presentHpo");
                default -> fail(fault);
            }
            f.save(); var result = evaluate(f); JsonNode p = proof(result);
            assertEquals("NOT_EVALUATED", result.at("/criteria/PS2/evaluationResult/status").asText(), fault + result.toPrettyString());
            assertNotEquals("ADOPTED", p.path("status").asText(), fault);
            for (var event : p.path("events")) {
                assertNotEquals("QUALIFIED_CONTRIBUTION", event.path("status").asText(), fault);
                assertFalse(event.has("conditionBranch"), fault);
            }
        }
    }

    @Test void wrongModeCannotTurnPostzygoticFactsIntoExpertProof() throws Exception {
        var f = fixture("wrong-mode", TISSUE);
        f.phenotype.put("expertPolicyStatus", "GENERAL_ALLOWED"); ((ObjectNode) f.phenotype.path("specialEvents")).put("mode", "SVI_1_1");
        for (var entry : f.catalog.path("entries")) ((ObjectNode) entry).put("disposition", "GENERAL_CLAUSE_ALLOWED");
        f.save(); var result = evaluate(f);
        assertEquals("NOT_EVALUATED", result.at("/criteria/PS2/evaluationResult/status").asText());
        assertNotEquals("ADOPTED", proof(result).path("status").asText());
    }

    @Test void unboundExpertSourceAndMutatedOriginalAreRejectedBeforeAnyProofCanBeProduced() throws Exception {
        var unbound = fixture("unbound", TISSUE);
        ((ObjectNode) unbound.phenotype.path("specialEvents")).putArray("sourceIds").add("unregistered"); unbound.save();
        assertThrows(IllegalArgumentException.class, unbound::load);
        var altered = fixture("altered", TISSUE); Files.writeString(altered.root.resolve("source.txt"), "changed source bytes");
        assertThrows(IllegalArgumentException.class, altered::load);
    }

    @Test void combinedPs2KeepsAssumedPointsAndOrdinarySameFactsRemainPm6() throws Exception {
        var combined = fixture("combined", COMBINED); assumed(combined); combined.save();
        var result = evaluate(combined); JsonNode p = proof(result);
        assertEquals("MET", result.at("/criteria/PS2/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("SUPPORTING", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals(2, result.at("/publicSummary/effectiveQuarterPoints").asInt());
        assertEquals("ADOPTED", p.path("status").asText()); assertEquals(COMBINED, p.at("/policy/eventMode").asText());
        assertFalse(result.at("/publicSummary/events/0/confirmedParentage").asBoolean());
        assertEquals("SOURCE_BOUND_COMBINED_PS2", p.at("/events/0/conditionBranch").asText());
        assertEquals("MET", p.at("/events/0/combined/status").asText());
        assertEquals(COMBINED, p.at("/events/0/combined/clauseMode").asText());
        assertEquals("ASSUMED_NOT_TESTED", p.at("/events/0/combined/maternity").asText());
        assertEquals("ASSUMED_NOT_TESTED", p.at("/events/0/combined/paternity").asText());
        assertEquals(2, p.at("/events/0/combined/rawQuarterPoints").asInt());
        assertFalse(p.at("/events/0/combined/confirmedParentage").asBoolean());
        var ordinary = fixture("ordinary", "SVI_1_1"); assumed(ordinary); ordinary.save();
        var ordinaryResult = evaluate(ordinary);
        assertEquals("MET", ordinaryResult.at("/criteria/PM6/evaluationResult/status").asText());
        assertEquals("SUPPORTING", ordinaryResult.at("/criteria/PM6/appliedStrength").asText());
        assertEquals(2, ordinaryResult.at("/publicSummary/effectiveQuarterPoints").asInt());
        assertEquals("NOT_APPLICABLE", proof(ordinaryResult).path("status").asText());
    }

    @Test void ordinaryInheritedObservationCannotGainCombinedAdoptionFromModeAlone() throws Exception {
        var f = fixture("combined-parent-positive", COMBINED); assumed(f);
        ((ObjectNode) f.report.at("/tests/1")).put("result", "PRESENT");
        ((ObjectNode) f.report.at("/specialEvent/trio")).put("motherTarget", "PRESENT"); f.save();
        var result = evaluate(f);
        assertEquals("NOT_MET", result.at("/criteria/PS2/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("NOT_ADOPTED", proof(result).path("status").asText());
    }

    @Test void sameRetainedReportTwiceDoesNotDuplicateProofOrExpertStrength() throws Exception {
        var f = fixture("duplicate", TISSUE);
        ((ArrayNode) f.manifest.path("reports")).add(f.manifest.at("/reports/0").deepCopy()); f.writeManifest();
        var result = evaluate(f); JsonNode p = proof(result);
        assertEquals("STRONG", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals(1, result.at("/publicSummary/events").size()); assertEquals(1, p.path("events").size());
        assertEquals(1, result.at("/qualifiedResult/repeatedSourceCount").asInt());
    }

    @Test void oversizedPublicPolicyLocatorDoesNotRewriteTheActualScientificResult() throws Exception {
        var f = fixture("bounded-projection", TISSUE);
        ((ObjectNode) f.phenotype.at("/sources/0")).put("locator", "x".repeat(300)); f.save();
        var result = evaluate(f); JsonNode p = proof(result);
        assertEquals("MET", result.at("/criteria/PS2/evaluationResult/status").asText());
        assertEquals("STRONG", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals("ADOPTED", p.path("status").asText());
        assertEquals("INCOMPLETE", p.path("projectionStatus").asText());
        assertFalse(p.at("/policy/sources/0").has("locator"));
        assertFalse(p.path("limitations").isEmpty());
    }

    @Test void failedSecondSelectionHasNotRunProofAndNoClaimOfExecutedCalculator() throws Exception {
        var f = fixture("not-run", TISSUE);
        ((ObjectNode) f.selectorPolicies.at("/policies/1")).put("denovoCalculatorVersion", "denovo-event-calculator-999.0.0"); f.save();
        var module = f.load(); var result = module.evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals(0, module.evaluations()); assertFalse(result.has("qualifiedResult"));
        assertEquals("NOT_EVALUATED", result.at("/criteria/PS2/evaluationResult/status").asText());
        assertEquals("denovo-adoption-proof-v1", result.at("/publicSummary/adoptionProof/schemaVersion").asText());
        assertEquals("NOT_RUN", result.at("/publicSummary/adoptionProof/status").asText());
        for (var binding : result.path("calculationBindings")) assertFalse(binding.has("executedCalculatorVersion"));
    }

    @Test void unprojectableSelectedPolicyIdentityIsReportedWithoutDiscardingActualAdoption() throws Exception {
        var f = fixture("metadata-bounds", TISSUE);
        for (var policy : f.selectorPolicies.path("policies")) ((ObjectNode) policy).put("policyId", "x".repeat(300) + policy.at("/criterionIds/0").asText()); f.save();
        var result = evaluate(f); JsonNode p = result.at("/publicSummary/adoptionProof");
        assertEquals("MET", result.at("/criteria/PS2/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("STRONG", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals("x".repeat(300) + "ACMG_AMP_2015:PS2", result.at("/specializedPolicyAdoptions/0/policyId").asText());
        assertEquals("ADOPTED", p.path("status").asText()); assertEquals("INCOMPLETE", p.path("projectionStatus").asText());
        assertFalse(result.at("/publicSummary/specializedPolicyAdoptions/0").has("policyId"));
        assertFalse(p.path("limitations").isEmpty());
    }

    @Test void publicAllowlistIgnoresUnknownIdentityAndProseKeysOnAnActualExecutedProof() throws Exception {
        var f = fixture("whitelist", TISSUE); var result = evaluate(f);
        var expected = proof(result).deepCopy(); var qualified = (ObjectNode) result.path("qualifiedResult");
        var actualProof = (ObjectNode) qualified.path("adoptionProof");
        actualProof.put("patientName", "SYNTHETIC-must-not-be-public");
        ((ObjectNode) actualProof.path("policy")).put("arbitraryProse", "SYNTHETIC-must-not-be-public");
        ((ObjectNode) actualProof.at("/policy/sources/0")).put("originalPath", "C:/SYNTHETIC-must-not-be-public");
        ((ObjectNode) actualProof.at("/events/0/c1")).put("memberId", "SYNTHETIC-must-not-be-public");
        ((ObjectNode) actualProof.at("/events/0/c2/observations/0")).put("sampleId", "SYNTHETIC-must-not-be-public");
        var summary = f.mapper.createObjectNode();
        // Projection-only mutation exercise: the scientific result came from the actual evaluator above.
        DeNovoResultSummary.appendAdoption(summary, qualified, result);
        assertEquals(expected, summary.path("adoptionProof"));
        assertFalse(summary.toString().contains("SYNTHETIC-must-not-be-public"));
        assertEquals("STRONG", result.at("/criteria/PS2/appliedStrength").asText());
    }

    @Test void singleCriterionSelectionsKeepActualProofAndNeverPublishAnUnselectedCriterionRow() throws Exception {
        for (String mode : List.of(TISSUE, COMBINED)) {
            var f = fixture("selection-" + mode, mode); if (COMBINED.equals(mode)) assumed(f); f.save();
            var all = evaluate(f); JsonNode expected = proof(all);
            for (String code : List.of("PS2", "PM6")) {
                var single = f.load().evaluateBound(f.context(), f.mapper.createObjectNode(), List.of("ACMG_AMP_2015:" + code));
                assertEquals(expected, proof(single), mode + " " + code);
                assertEquals(all.path("qualifiedResult"), single.path("qualifiedResult"));
                assertEquals(1, single.path("criteria").size()); assertTrue(single.path("criteria").has(code));
                assertEquals(all.at("/criteria/" + code), single.at("/criteria/" + code));
            }
        }
    }
}
