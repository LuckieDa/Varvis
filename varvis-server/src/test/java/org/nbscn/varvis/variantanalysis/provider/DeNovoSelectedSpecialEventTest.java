package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Normal selected materials, invented families/knowledge, and the actual installed A10 resolver. */
class DeNovoSelectedSpecialEventTest {
    @TempDir Path temporary;

    private DeNovoBatchFixture fixture(String name, String moi, String inheritance, String mode, boolean expert) throws Exception {
        var f = new DeNovoBatchFixture(temporary.resolve(name));
        ((ObjectNode) f.phenotype.path("scope")).put("inheritance", inheritance);
        f.phenotype.put("expertPolicyStatus", expert ? "APPLICABLE_IMPLEMENTED" : "GENERAL_ALLOWED");
        f.phenotype.putObject("specialEvents").put("mode", mode).putArray("sourceIds").add("s");
        f.report.put("schemaVersion", DeNovoStructuredReport.SCHEMA_V2);
        ((ObjectNode) f.report.path("clinical")).put("inheritance", inheritance);
        f.report.set("specialEvent", event(f));
        for (var entry : f.catalog.path("entries")) {
            ((ObjectNode) entry.path("scope")).put("moi", moi);
            if (expert) ((ObjectNode) entry).put("disposition", "SPECIALIZED_IMPLEMENTED");
        }
        for (var policy : f.selectorPolicies.path("policies")) ((ObjectNode) policy.path("scope")).put("moi", moi);
        f.save(); return f;
    }

    private ObjectNode event(DeNovoBatchFixture f) {
        var event = f.mapper.createObjectNode().put("schemaVersion", "denovo-special-event-v1").put("kind", "CONSTITUTIONAL")
                .put("familyId", DeNovoBatchFixture.FAMILY).put("eventId", "SYNTHETIC-origin")
                .put("personId", DeNovoBatchFixture.PATIENT).put("probandId", DeNovoBatchFixture.PATIENT)
                .put("sex", "FEMALE").put("ploidy", "DIPLOID").put("genotype", "HETEROZYGOUS").put("affectedStatus", "AFFECTED")
                .put("parentalAvailability", "AVAILABLE").put("otherAffectedMales", "NONE_IN_CHECKED_SCOPE").put("eventOrigin", "UNKNOWN");
        event.putObject("trio").put("personId", DeNovoBatchFixture.PATIENT).put("motherId", "mother").put("fatherId", "father")
                .put("subjectTarget", "PRESENT").put("motherTarget", "ABSENT").put("fatherTarget", "ABSENT")
                .put("maternity", "CONFIRMED").put("paternity", "CONFIRMED").put("maternityMethod", "SNP_PANEL").put("paternityMethod", "SNP_PANEL")
                .put("method", "SANGER").put("targetCoverage", "EXPLICIT_TARGET").put("detectionCapability", "ADEQUATE").put("quality", "VALID");
        var partner = event.putObject("partner").put("status", "NONE_IN_CHECKED_SCOPE").put("phase", "UNKNOWN");
        partner.putNull("allele"); partner.putArray("sourceRefs"); event.putArray("siblings"); event.putArray("tissues");
        return event;
    }

    private ObjectNode context(DeNovoBatchFixture f, String moi) {
        var context = f.context().put("moi", moi);
        var adapter = new RuntimeEvidenceV2Adapter(f.mapper);
        context.put("contextId", adapter.canonicalVersionedContextId(context)); adapter.validateVersionedContext(context);
        return context;
    }

    private void sameSingleSelections(DeNovoBatchFixture f, ObjectNode context, JsonNode all) throws Exception {
        for (String code : List.of("PS2", "PM6")) {
            var one = f.load().evaluateBound(context, f.mapper.createObjectNode(), List.of("ACMG_AMP_2015:" + code));
            assertEquals(all.path("qualifiedResult"), one.path("qualifiedResult"));
            assertEquals(all.at("/criteria/" + code), one.at("/criteria/" + code));
            assertEquals(1, one.path("criteria").size());
        }
    }

    @Test void normalArBatchKeepsRawPointsAndAppliesOneLevelReductionForEachSelection() throws Exception {
        var f = fixture("ar", "AUTOSOMAL_RECESSIVE", "AR", "SVI_1_1", false);
        var context = context(f, "AUTOSOMAL_RECESSIVE"); var module = f.load();
        var result = module.evaluateBound(context, f.mapper.createObjectNode());
        assertEquals("MET", result.at("/qualifiedResult/result/status").asText(), result.toPrettyString());
        assertEquals("SUPPORTING", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals(4, result.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        assertEquals("ONE_LEVEL_AR_NO_SECOND_P_LP", result.at("/qualifiedResult/result/strengthAdjustment").asText());
        sameSingleSelections(f, context, result);
    }

    @Test void normalXBatchUsesActualA10AndDoesNotInventParInheritance() throws Exception {
        var f = fixture("x", "X_LINKED_RECESSIVE", "X_LINKED", "SVI_1_1", false);
        ((ObjectNode) f.report.path("target")).put("contig", "X").put("position", 5000000);
        ((ObjectNode) f.report.path("specialEvent")).put("sex", "MALE").put("ploidy", "HAPLOID").put("genotype", "HEMIZYGOUS_ALT");
        f.save(); var context = context(f, "X_LINKED_RECESSIVE"); var module = f.load();
        var result = module.evaluateBound(context, f.mapper.createObjectNode());
        assertEquals("MET", result.at("/criteria/PS2/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("MODERATE", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals(SexChromosomeRegionResolver.CATALOG_SHA256, module.identity().at("/sexChromosomeRegion/sourceCatalogSha256").asText());
        assertEquals(SexChromosomeRegionResolver.VERSION, module.identity().at("/sexChromosomeRegion/methodVersion").asText());
        sameSingleSelections(f, context, result);
        ((ObjectNode) f.report.path("target")).put("position", 10001);
        ((ObjectNode) f.report.path("specialEvent")).put("sex", "FEMALE").put("ploidy", "DIPLOID").put("genotype", "HETEROZYGOUS");
        f.save(); var par = f.load().evaluateBound(context(f, "X_LINKED_RECESSIVE"), f.mapper.createObjectNode());
        assertEquals("NOT_EVALUATED", par.at("/criteria/PS2/evaluationResult/status").asText());
        assertTrue(par.toString().contains("DENOVO_PAR_INHERITANCE_RULE_NOT_IMPLEMENTED"));
    }

    @Test void normalSharedSiblingSourcesCountOneEventAndRetainBothOriginals() throws Exception {
        var f = fixture("siblings", "AUTOSOMAL_DOMINANT", "AD", "SVI_1_1", false);
        var event = (ObjectNode) f.report.path("specialEvent"); event.put("kind", "SIBLING_RECURRENCE").put("eventOrigin", "SHARED_EVENT");
        event.putArray("siblings").add("SYNTHETIC-sibling-2"); ((ObjectNode) f.report.path("clinical")).put("siblingRecurrence", "PRESENT");
        f.save(); var second = f.report.deepCopy().put("reportId", "SYNTHETIC-second-laboratory-record");
        byte[] raw = f.mapper.writeValueAsBytes(second); Files.write(f.root.resolve("second-report.json"), raw);
        ((ArrayNode) f.manifest.path("reports")).addObject().put("path", "second-report.json").put("sha256", DeNovoBatchFixture.sha(raw)); f.writeManifest();
        var module = f.load(); var context = context(f, "AUTOSOMAL_DOMINANT"); var result = module.evaluateBound(context, f.mapper.createObjectNode());
        assertEquals("MET", result.at("/criteria/PS2/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(4, result.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        assertEquals(1, result.at("/qualifiedResult/result/events").size());
        assertTrue(module.originals().has(DeNovoBatchFixture.sha(raw))); sameSingleSelections(f, context, result);
    }

    private DeNovoBatchFixture tissueFixture(String name) throws Exception {
        var f = fixture(name, "AUTOSOMAL_DOMINANT", "AD", "TISSUE_DIFFERENTIAL_PS2", true);
        ((ObjectNode) f.report.path("clinical")).put("mosaicism", "KNOWN");
        var event = (ObjectNode) f.report.path("specialEvent"); event.put("kind", "POSTZYGOTIC");
        var tissues = event.putArray("tissues");
        tissues.addObject().put("personId", DeNovoBatchFixture.PATIENT).put("tissueId", "SYNTHETIC-lesional-tissue").put("affected", true)
                .put("altReads", 20).put("totalReads", 100).put("limit", 0.01).put("method", "TARGETED_SEQUENCING").put("quality", "HIGH_CONFIDENCE");
        tissues.addObject().put("personId", DeNovoBatchFixture.PATIENT).put("tissueId", "SYNTHETIC-blood").put("affected", false)
                .put("altReads", 0).put("totalReads", 100).put("limit", 0.01).put("method", "TARGETED_SEQUENCING").put("quality", "HIGH_CONFIDENCE");
        f.save(); return f;
    }

    @Test void exactSpecializedEntryUsesBoundedAdoptionAndPreservesTheGlobalRefusalReceipt() throws Exception {
        var f = tissueFixture("expert"); var context = context(f, "AUTOSOMAL_DOMINANT"); var module = f.load();
        var result = module.evaluateBound(context, f.mapper.createObjectNode());
        assertEquals("MET", result.at("/criteria/PS2/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("STRONG", result.at("/criteria/PS2/appliedStrength").asText());
        assertEquals("EXPERT_FIXED_STRENGTH", result.at("/qualifiedResult/result/calculationMethod").asText());
        assertEquals(0, result.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        assertEquals("DENOVO_SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED", result.at("/specificationSelections/0/reasonCode").asText());
        assertEquals("SELECTED", result.at("/specializedPolicyAdoptions/0/status").asText());
        assertEquals("SPECIALIZED_IMPLEMENTED", f.catalog.at("/entries/0/disposition").asText());
        var declared = f.selectorPolicies.at("/policies/0");
        assertEquals(new RuntimeEvidenceV2Adapter(f.mapper).canonicalSha256(declared), result.at("/specializedPolicyAdoptions/0/policySha256").asText());
        sameSingleSelections(f, context, result);
    }

    @Test void boundedExpertAdoptionRejectsWrongScopeUnreviewedPolicyCriterionAndBindingHash() throws Exception {
        for (String failure : List.of("scope", "review", "criterion", "hash", "mode", "unresolved", "ambiguous")) {
            var f = tissueFixture("expert-" + failure); var selected = (ObjectNode) f.selectorPolicies.at("/policies/0");
            switch (failure) {
                case "scope" -> ((ObjectNode) selected.path("scope")).put("unreviewedExtension", "not-the-entry-scope");
                case "review" -> ((ObjectNode) selected.path("review")).put("status", "UNREVIEWED");
                case "criterion" -> selected.putArray("criterionIds").add("ACMG_AMP_2015:PVS1");
                case "mode" -> ((ObjectNode) f.phenotype.path("specialEvents")).put("mode", "SVI_1_1");
                case "unresolved" -> { f.phenotype.put("expertPolicyStatus", "APPLICABLE_UNRESOLVED"); ((ObjectNode) f.phenotype.path("specialEvents")).put("mode", "SVI_1_1"); }
                case "ambiguous" -> ((ArrayNode) f.selectorPolicies.path("policies")).add(selected.deepCopy());
                default -> { }
            }
            f.save();
            if (failure.equals("hash")) {
                ((ObjectNode) f.catalog.at("/entries/0/policyBinding")).put("policySha256", "0".repeat(64));
                byte[] raw = f.mapper.writeValueAsBytes(f.catalog); Files.write(f.root.resolve("catalog.json"), raw);
                ((ObjectNode) f.manifest.path("catalog")).put("sha256", DeNovoBatchFixture.sha(raw)); f.writeManifest();
            }
            var module = f.load(); var result = module.evaluateBound(context(f, "AUTOSOMAL_DOMINANT"), f.mapper.createObjectNode(), List.of("ACMG_AMP_2015:PS2"));
            assertEquals("NOT_EVALUATED", result.at("/criteria/PS2/evaluationResult/status").asText(), failure + result.toPrettyString());
            assertEquals(0, module.evaluations(), failure);
            assertEquals("DENOVO_SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED", result.at("/specificationSelections/0/reasonCode").asText());
        }
    }

    @Test void legacyAdCalculatorDeclarationsRemainExplicitlyCompatibleWithoutResigningSources() throws Exception {
        for (String version : List.of("denovo-event-calculator-1.0.0", "denovo-event-calculator-1.1.0")) {
            var f = new DeNovoBatchFixture(temporary.resolve(version));
            for (var policy : f.selectorPolicies.path("policies")) ((ObjectNode) policy).put("denovoCalculatorVersion", version);
            f.save(); byte[] original = Files.readAllBytes(f.root.resolve("selector-policies.json")); var module = f.load();
            var result = module.evaluateBound(f.context(), f.mapper.createObjectNode());
            assertEquals("MET", result.at("/criteria/PS2/evaluationResult/status").asText(), result.toPrettyString());
            assertEquals(version, result.at("/calculationBindings/0/declaredCalculatorVersion").asText());
            assertEquals(DeNovoEvidenceCalculator.VERSION, result.at("/calculationBindings/0/executedCalculatorVersion").asText());
            assertEquals(f.catalog.at("/entries/0/policyBinding/policySha256"), result.at("/calculationBindings/0/selectorPolicySha256"));
            assertArrayEquals(original, Base64.getDecoder().decode(module.originals().path(DeNovoBatchFixture.sha(original)).asText()));
        }
        var special = fixture("stale-special", "AUTOSOMAL_RECESSIVE", "AR", "SVI_1_1", false);
        for (var policy : special.selectorPolicies.path("policies")) ((ObjectNode) policy).put("denovoCalculatorVersion", "denovo-event-calculator-1.0.0");
        special.save(); var refused = special.load().evaluateBound(context(special, "AUTOSOMAL_RECESSIVE"), special.mapper.createObjectNode());
        assertEquals("DENOVO_SELECTED_POLICY_BINDING_UNAVAILABLE", refused.at("/criteria/PS2/evaluationResult/reasonCode").asText());
    }

    @Test void failureOfSecondSelectionDoesNotClaimTheCalculatorExecuted() throws Exception {
        var f = new DeNovoBatchFixture(temporary.resolve("selection-before-execution"));
        ((ObjectNode) f.selectorPolicies.at("/policies/1")).put("denovoCalculatorVersion", "denovo-event-calculator-999.0.0");
        f.save(); var module = f.load(); var result = module.evaluateBound(f.context(), f.mapper.createObjectNode());
        assertEquals(0, module.evaluations());
        assertEquals("NOT_EVALUATED", result.at("/criteria/PS2/evaluationResult/status").asText());
        assertEquals(1, result.path("calculationBindings").size());
        assertEquals(DeNovoEvidenceCalculator.VERSION, result.at("/calculationBindings/0/runtimeCalculatorVersion").asText());
        assertFalse(result.at("/calculationBindings/0").has("executedCalculatorVersion"));
        assertFalse(result.has("qualifiedResult"));
    }
}
