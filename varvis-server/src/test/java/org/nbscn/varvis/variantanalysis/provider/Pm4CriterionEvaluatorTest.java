package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

class Pm4CriterionEvaluatorTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // Explicit hand-calculated full CDS and proteins; no tested sequence utility generates expectations.
    @ParameterizedTest @CsvSource({
        "1,105,A,AGCT,6_7insGCT,Glu2_Pro3insAla,ATGGAAGCTCCTGGCTTTAAATAA,MEAPGFK*,6,6",
        "-1,114,G,GAGC,6_7insGCT,Glu2_Pro3insAla,ATGGAAGCTCCTGGCTTTAAATAA,MEAPGFK*,6,6",
        "1,105,ACCT,A,7_9del,Pro3del,ATGGAAGGCTTTAAATAA,MEGFK*,6,9",
        "-1,111,CAGG,C,7_9del,Pro3del,ATGGAAGGCTTTAAATAA,MEGFK*,6,9",
        "1,103,G,GGGC,4_5insGGC,Glu2delinsGlyGln,ATGGGGCAACCTGGCTTTAAATAA,MGQPGFK*,4,4",
        "1,104,A,ACCT,5_6insCCT,Glu2delinsAspLeu,ATGGACCTACCTGGCTTTAAATAA,MDLPGFK*,5,5",
        "1,103,GAAC,G,5_7del,Glu2_Pro3delinsAla,ATGGCTGGCTTTAAATAA,MAGFK*,4,7",
        "1,104,AACC,A,6_8del,Glu2_Pro3delinsAsp,ATGGATGGCTTTAAATAA,MDGFK*,5,8",
        "1,108,T,TCCT,7_9dup,Pro3dup,ATGGAACCTCCTGGCTTTAAATAA,MEPPGFK*,9,9"
    })
    void reconstructsEntireSequenceAcrossStrandsAndCodonPhases(int strand, int pos, String ref, String alt,
            String coding, String protein, String expectedCds, String expectedProtein, int start, int end) throws Exception {
        var data = new Pm4TestFixtures(mapper, strand); data.variant(pos, ref, alt, coding, protein);
        ObjectNode facts = mapper.createObjectNode();
        assertNull(ProteinLengthChangeObservation.resolve(data.context, data.model, data.candidate.getTranscriptAnnotations().get(0), facts), facts.toPrettyString());
        assertEquals(Pm4TestFixtures.CDS, facts.path("referenceCds").asText());
        assertEquals("MEPGFK*", facts.path("referenceProtein").asText());
        assertEquals(expectedCds, facts.path("alteredCds").asText()); assertEquals(expectedProtein, facts.path("alteredProtein").asText());
        assertEquals(start, facts.path("referenceEditStart0").asInt()); assertEquals(end, facts.path("referenceEditEnd0").asInt());
        assertEquals(expectedCds.length() - 2, facts.path("alteredStopCdsPosition").asInt());
        assertEquals(expectedProtein.length() - 7, facts.path("netAminoAcidChange").asInt());
        var result = data.evaluate(); assertStatus(result, "MET", "PM4_SUPPORTED_NON_REPEAT_LENGTH_CHANGE");
        assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText());
        assertEquals(0, result.path("providerCalls").asInt()); assertEquals(1, result.path("lengthCalculations").asInt());
        assertFalse(result.path("adoptionSupported").asBoolean(true)); assertFalse(result.toString().contains(expectedCds));
        ObjectNode identity = ((ObjectNode) result.path("evaluation")).deepCopy(); identity.remove(List.of("evaluationId", "conflict", "review"));
        assertEquals("evaluation-sha256-" + data.adapter.canonicalSha256(identity), result.at("/evaluation/evaluationId").asText());
    }

    @ParameterizedTest @CsvSource({
        "105,A,AG,6_7insG,PM4_FRAMESHIFT_UNSUPPORTED",
        "105,A,ATAA,6_7insTAA,PM4_PREMATURE_STOP_UNSUPPORTED",
        "100,ATGG,A,2_4del,PM4_START_AFFECTED_UNSUPPORTED",
        "116,AATA,A,18_20del,PM4_STOP_LOSS_UNSUPPORTED",
        "105,AC,AGCTT,6_7delinsGCTT,PM4_COMPLEX_ALLELE_UNSUPPORTED",
        "105,C,CGCT,6_7insGCT,PM4_REFERENCE_MISMATCH",
        "105,A,AGCT,5_6insGCT,PM4_HGVSC_SEQUENCE_MISMATCH",
        "105,A,AGCT,6+1_6+2insGCT,PM4_HGVSC_UNSUPPORTED_OR_INVALID"
    })
    void unsupportedAndInvalidEditsCannotBeMet(int pos, String ref, String alt, String coding, String reason) throws Exception {
        var data = new Pm4TestFixtures(mapper, 1); data.variant(pos, ref, alt, coding, null);
        assertStatus(data.evaluate(), "NOT_EVALUATED", reason);
    }

    @Test void noNetChangeIsOnlyANegativeControlAndBoundarySpliceAndProteinClaimsAreChecked() throws Exception {
        var data = positive(); var facts = mapper.createObjectNode();
        ((ObjectNode) data.context.at("/variant/evidenceQueryAllele")).put("alternate", "A");
        ((ObjectNode) data.context.at("/variant/normalizedAllele")).put("alternate", "A");
        assertEquals("PM4_NO_NET_LENGTH_CHANGE", ProteinLengthChangeObservation.resolve(data.context, data.model, data.candidate.getTranscriptAnnotations().get(0), facts));
        data = positive(); data.model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 100).put("genomicEnd", 105).put("cdsStart", 1).put("cdsEnd", 6);
        data.model.withArray("exons").addObject().put("ordinal", 2).put("genomicStart", 200).put("genomicEnd", 214).put("cdsStart", 7).put("cdsEnd", 21);
        data.bind(); assertStatus(data.evaluate(), "NOT_EVALUATED", "PM4_EXON_BOUNDARY_UNSUPPORTED");
        for (String[] mutation : new String[][]{{"consequence", "splice_region_variant", "PM4_SPLICE_BRANCH_UNSUPPORTED"},
                {"hgvsProtein", "ENSP000001.1:p.Glu2_Pro3insGly", "PM4_HGVSP_SEQUENCE_MISMATCH"},
                {"transcriptAccession", "ENST000001.2", "PM4_SELECTED_TRANSCRIPT_NOT_UNIQUE"},
                {"version", "115.0", "PM4_SELECTED_TRANSCRIPT_NOT_UNIQUE"}}) {
            data = positive(); ObjectNode annotation = data.annotation("6_7insGCT", "Glu2_Pro3insAla", "inframe_insertion");
            annotation.put(mutation[0], mutation[1]); data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
            assertStatus(data.evaluate(), "NOT_EVALUATED", mutation[2]);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"REPEAT", "MISSING", "GAP", "UNKNOWN", "VERSION", "SOURCE", "FUNCTION", "REGISTRY"})
    void pinnedRegionAndSourceFactsCannotBeReplacedWithAbsence(String mode) throws Exception {
        var data = positive(); String reason; String status = "NOT_EVALUATED";
        ObjectNode row = (ObjectNode) data.regions.at("/intervals/0");
        switch (mode) {
            case "REPEAT": row.put("repeatStatus", "REPEAT"); reason = "PM4_REPEAT_REGION"; status = "NOT_MET"; break;
            case "FUNCTION": row.put("functionalContext", "NOT_SUPPORTED"); reason = "PM4_FUNCTIONAL_CONTEXT_NOT_MET"; status = "NOT_MET"; break;
            case "MISSING": data.regions.putArray("intervals"); reason = "PM4_REGION_COVERAGE_MISSING"; break;
            case "GAP": row.put("end", 105); reason = "PM4_REGION_COVERAGE_INCOMPLETE"; break;
            case "UNKNOWN": row.put("repeatStatus", "UNKNOWN"); reason = "PM4_REGION_COVERAGE_INCOMPLETE"; break;
            case "SOURCE": ((ObjectNode) data.regions.at("/sourceRecords/0")).put("sha256", "b".repeat(64)); reason = "PM4_MATERIAL_SOURCE_INVALID"; break;
            case "REGISTRY": reason = "PM4_REGISTRY_IDENTITY_INVALID"; break;
            default: data.model.put("transcript", "ENST000001.2"); reason = "PM4_TRANSCRIPT_MODEL_CONTEXT_MISMATCH";
        }
        data.bind(); ObjectNode doc = data.document();
        if (mode.equals("REGISTRY")) ((ObjectNode) doc.at("/materials/pm4ResourceProfile/registryBinding")).put("registrySha256", "b".repeat(64));
        assertStatus(data.bundle(doc).evaluate(Pm4CriterionEvaluator.CRITERION, data.context, data.candidate), status, reason);
    }

    @ParameterizedTest @ValueSource(strings = {"transcriptModels", "pm4Regions"})
    void missingAndCorruptMaterialAreDistinctFromNegative(String bucket) throws Exception {
        var data = positive(); ObjectNode doc = data.document();
        ((ObjectNode) doc.at("/materials/" + bucket)).removeAll();
        var result = data.bundle(doc).evaluate(Pm4CriterionEvaluator.CRITERION, data.context, data.candidate);
        assertStatus(result, "NOT_EVALUATED", bucket.equals("transcriptModels") ? "PM4_MODEL_MISSING" : "PM4_REGION_MISSING");
        doc = data.document(); ObjectNode materials = (ObjectNode) doc.at("/materials/" + bucket);
        ((ObjectNode) materials.elements().next()).put("tampered", true);
        assertStatus(data.bundle(doc).evaluate(Pm4CriterionEvaluator.CRITERION, data.context, data.candidate), "NOT_EVALUATED",
                bucket.equals("transcriptModels") ? "PM4_MODEL_SHA_MISMATCH" : "PM4_REGION_SHA_MISMATCH");
    }

    @Test void policyGapsAndOldBundlesCannotActivatePm4AndNewIdentityIsIsolated() throws Exception {
        var data = positive(); ObjectNode doc = data.document();
        ((ObjectNode) doc.path("catalog")).putArray("entries"); doc.put("catalogSha256", data.adapter.canonicalSha256(doc.path("catalog")));
        var gap = data.bundle(doc).evaluate(Pm4CriterionEvaluator.CRITERION, data.context, data.candidate);
        assertEquals("NOT_EVALUATED", gap.at("/evaluationResult/status").asText()); assertFalse(ReviewedRuleBundle.usesPreexistingClause(gap));
        var legacy = data.bundle(ReviewedRuleContextPreparationTest.emptyBundleDocument(mapper, "1"));
        assertFalse(legacy.supportsPm4()); assertFalse(legacy.identity().toString().contains("PM4"));
        assertThrows(IllegalArgumentException.class, () -> AcmgExecutionProfile.pm4Development(mapper, legacy));
        var current = data.bundle(data.document()); var profile = AcmgExecutionProfile.pm4Development(mapper, current);
        assertEquals(List.of(Pm4CriterionEvaluator.CRITERION), profile.criteria());
        assertFalse(AcmgBusinessRuleScope.CURRENT.contains(Pm4CriterionEvaluator.CRITERION));
        var capability = new ReviewedRuleContextPreparation(mapper, current.identity());
        assertTrue(capability.accepts(capability.selectionPolicy()));
        assertNotEquals(capability.selectionPolicy(), new ReviewedRuleContextPreparation(mapper, legacy.identity()).selectionPolicy());
    }

    @Test void selectionStrengthAndStoredAlleleCannotBeAssertedByCaller() throws Exception {
        var data = positive(); data.policy.put("appliedStrength", "STRONG");
        assertStatus(data.evaluate(), "NOT_EVALUATED", "PM4_POLICY_INVALID");
        data = positive(); data.candidate = RuntimeEvidenceV2ProviderWiringTest.variant(
                new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey("GRCh38", "11", 106, "A", "AGCT"));
        data.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(data.annotation("6_7insGCT", "Glu2_Pro3insAla", "inframe_insertion"), TranscriptConsequence.class)));
        assertStatus(data.evaluate(), "NOT_EVALUATED", "PM4_VARIANT_OR_ANNOTATION_IDENTITY_INVALID");
        data = positive(); ObjectNode doc = data.document();
        ((ObjectNode) doc.at("/catalog/entries/0/policyBinding")).put("policySha256", "e".repeat(64));
        doc.put("catalogSha256", data.adapter.canonicalSha256(doc.path("catalog")));
        var value = data.bundle(doc).evaluate(Pm4CriterionEvaluator.CRITERION, data.context, data.candidate);
        assertEquals("NOT_EVALUATED", value.at("/evaluationResult/status").asText());
        assertEquals(0, value.path("lengthCalculations").asInt());
    }

    @ParameterizedTest @CsvSource({
        "MET,MET,APPLICABLE,COMPLETED,PM4_SUPPORTED_NON_REPEAT_LENGTH_CHANGE",
        "REPEAT,NOT_MET,APPLICABLE,COMPLETED,PM4_REPEAT_REGION",
        "MISSING,NOT_EVALUATED,UNKNOWN,NOT_RUN,PM4_REGION_COVERAGE_MISSING",
        "FRAMESHIFT,NOT_EVALUATED,NOT_APPLICABLE,NOT_RUN,PM4_FRAMESHIFT_UNSUPPORTED"
    })
    void schemaConditionalReasonsAndFinalIdentities(String mode, String status, String applicability,
            String execution, String reason) throws Exception {
        var data = positive();
        if ("MISSING".equals(mode)) { data.regions.putArray("intervals"); data.bind(); }
        if ("REPEAT".equals(mode)) {
            ((ObjectNode) data.regions.at("/intervals/0")).put("repeatStatus", "REPEAT"); data.bind();
        }
        if ("FRAMESHIFT".equals(mode)) data.variant(105, "A", "AG", "6_7insG", null);
        ObjectNode result = data.evaluate(); assertStatus(result, status, reason);
        ObjectNode evaluation = ((ObjectNode) result.path("evaluation")).deepCopy();
        JsonNode applicabilityNode = evaluation.path("criterionApplicability");
        ObjectNode trace = ((ObjectNode) evaluation.path("deterministicTrace")).deepCopy();
        assertEquals(applicability, applicabilityNode.path("status").asText());
        assertEquals(execution, trace.path("executionStatus").asText());
        String traceSha = trace.path("traceSha256").asText(); trace.remove("traceSha256");
        assertEquals(data.adapter.canonicalSha256(trace), traceSha);
        String evaluationId = evaluation.path("evaluationId").asText();
        evaluation.remove(List.of("evaluationId", "conflict", "review"));
        assertEquals("evaluation-sha256-" + data.adapter.canonicalSha256(evaluation), evaluationId);
        // Two conditions from frozen criterion-evaluation-v2.schema.json (170-186, 254-264).
        // This is a focused condition regression, not a full JSON Schema validator.
        assertAll(
                () -> {
                    if (List.of("UNKNOWN", "NOT_APPLICABLE").contains(applicability)) {
                        assertFalse(applicabilityNode.path("reasonCode").asText().isBlank(), "Non-applicable/unknown requires its own reasonCode");
                        assertEquals(reason, applicabilityNode.path("reasonCode").asText());
                    } else assertFalse(applicabilityNode.has("reasonCode"));
                },
                () -> {
                    if (!"COMPLETED".equals(execution)) {
                        assertFalse(trace.path("reasonCode").asText().isBlank(), "Non-completed trace requires its own reasonCode");
                        assertEquals(reason, trace.path("reasonCode").asText());
                    } else assertFalse(trace.has("reasonCode"));
                });
    }

    Pm4TestFixtures positive() throws Exception {
        var data = new Pm4TestFixtures(mapper, 1); data.variant(105, "A", "AGCT", "6_7insGCT", "Glu2_Pro3insAla"); return data;
    }
    static void assertStatus(ObjectNode value, String expected, String reason) {
        JsonNode result = value.has("evaluation") ? value.at("/evaluation/evaluationResult") : value.path("evaluationResult");
        assertEquals(expected, result.path("status").asText(), value.toPrettyString());
        assertEquals(reason, result.path("reasonCode").asText(), value.toPrettyString());
    }
}
