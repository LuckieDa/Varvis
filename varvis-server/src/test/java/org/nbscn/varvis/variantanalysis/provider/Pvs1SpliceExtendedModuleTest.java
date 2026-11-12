package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed mechanical/decision expectations, using the genuine existing policy gate.
 * Toy source qualification is explicitly synthetic; no real scientific adoption. */
class Pvs1SpliceExtendedModuleTest {
    final Pvs1SpliceConsequenceTest base = new Pvs1SpliceConsequenceTest();
    final RuntimeEvidenceV2Adapter adapter = base.adapter;

    private void bind(Pvs1SpliceConsequenceTest.Data data) {
        var d = data.input();
        d.context.put("contextId", adapter.canonicalContextId(d.context));
        data.material().put("transcriptModelSha256", adapter.canonicalSha256(d.model));
        d.policy.putObject("transcriptModelBinding").put("modelId", d.model.path("modelId").asText())
                .put("modelVersion", d.model.path("modelVersion").asText()).put("canonicalSha256", adapter.canonicalSha256(d.model));
        d.selection.put("contextId", d.context.path("contextId").asText()).put("policyId", d.policy.path("policyId").asText())
                .put("policyVersion", d.policy.path("policyVersion").asText()).put("policySha256", adapter.canonicalSha256(d.policy));
        d.selection.remove("selectionId");d.selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(d.selection));
    }
    private String module(Pvs1SpliceConsequenceTest.Data data, ObjectNode input) {
        bind(data); var d = data.input();
        return Pvs1SpliceConsequence.resolveForIntegration(d.context, d.candidate.getTranscriptAnnotations().get(0), d.policy,
                d.model, data.material(), input, d.selection);
    }
    private String compatibility(Pvs1SpliceConsequenceTest.Data data, ObjectNode input) {
        bind(data); var d = data.input();
        return Pvs1SpliceConsequence.resolve(d.context, d.candidate.getTranscriptAnnotations().get(0), d.policy,
                d.model, data.material(), input, d.selection);
    }
    private ObjectNode input() { return base.mapper.createObjectNode(); }

    private void inFrame(Pvs1SpliceConsequenceTest.Data d, int ordinal, int lo, int hi) {
        d.material().withObject("event").put("type", "EXON_SHORTENING").put("exonOrdinal", ordinal)
                .put("deleteStartCds", lo).put("deleteEndCds", hi);
        var scope = d.input().policy.withObject("spliceScope").putObject("inFrameScope")
                .put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME").put("reviewId", "TEST_ONLY_REGION")
                .put("basis", "Toy exact local functional rule, not scientific review");
        scope.putArray("sourceIds").add("synthetic-source");
        var exons = scope.putArray("exonAssessments");
        for (var e : d.input().model.path("exons")) exons.addObject().put("ordinal", e.path("ordinal").asInt())
                .put("biologicalRelevance", "PRESENT").put("populationLof", "NOT_FREQUENT")
                .put("basis", "TEST_ONLY").putArray("sourceIds").add("synthetic-source");
        scope.putArray("rules").addObject().put("ruleId", "local").put("predicate", "CRITICAL_REGION")
                .put("start", (lo + 2) / 3).put("end", hi / 3).put("minimumAffectedResidues", 1)
                .put("strength", "STRONG").put("basis", "TEST_ONLY source rule")
                .putArray("sourceIds").add("synthetic-source");
        d.material().putObject("inFrameReview").put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME")
                .put("reviewId", "TEST_ONLY_EVENT_REGION").put("basis", "Toy binding")
                .putArray("sourceIds").add("synthetic-source");
        var relevant = d.input().policy.withObject("nmdScope").putArray("relevantExonOrdinals");
        for (var e : d.input().model.path("exons")) relevant.add(e.path("ordinal").asInt());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void sourceBoundLocalDeletionUsesExplicitStrengthAndWaitsForRuntimeAdapter(int strand) throws Exception {
        var d = base.data(strand, false, false);inFrame(d, 2, 13, 24);
        d.material().withObject("review").put("maximumStrength", "MODERATE");
        var input = input(); assertNull(module(d, input), input::toPrettyString);
        assertEquals("MET", input.at("/spliceAssessment/status").asText());
        assertEquals("MODERATE", input.at("/spliceAssessment/adoptedStrength").asText());
        assertEquals(5, input.at("/inFrameAssessment/firstDeletedReferenceResidue").asInt());
        assertEquals(8, input.at("/inFrameAssessment/lastDeletedReferenceResidue").asInt());
        assertFalse(input.has("nmdPrediction"));
        assertEquals("PVS1_SPLICE_RUNTIME_ADAPTER_REQUIRED", compatibility(d, input()));
        assertDoesNotThrow(() -> adapter.canonicalSha256(input));
    }

    @ParameterizedTest @ValueSource(strings = {"REFERENCE_EVENT_SPAN", "REFERENCE_PTC_SPAN"})
    void inFrameDoesNotIgnoreOriginalSourcePositionConditions(String coordinate) throws Exception {
        var d = base.data(1, false, false);inFrame(d, 2, 13, 24);
        d.input().policy.withObject("nmdScope").putArray("positionConditions").addObject().put("conditionId", "outside")
                .put("coordinate", coordinate).put("maximumExclusive", 13).put("basis", "TEST_ONLY exact policy range")
                .putArray("sourceIds").add("synthetic-source");
        assertEquals(coordinate.equals("REFERENCE_EVENT_SPAN") ? "PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE"
                : "PVS1_POLICY_POSITION_UNRESOLVED", module(d, input()));
    }

    @Test void malformedNewPolicyIsRejectedEvenForOutOfFrameEvent() throws Exception {
        var d = base.data(1, false, false);
        d.input().policy.withObject("spliceScope").putObject("inFrameScope").put("status", "UNREVIEWED");
        assertEquals("PVS1_SPLICE_INFRAME_SCOPE_NOT_REVIEWED", module(d, input()));
    }

    @Test void parentSelectionAndMaterialPinsCannotBeSkippedByModuleEntry() throws Exception {
        var d = base.data(1, false, false);bind(d);var i = d.input();
        i.selection.put("policySha256", "0".repeat(64));
        assertEquals("PVS1_POLICY_SELECTION_IDENTITY_INVALID", Pvs1SpliceConsequence.resolveForIntegration(i.context,
                i.candidate.getTranscriptAnnotations().get(0), i.policy, i.model, d.material(), input(), i.selection));
        bind(d);d.material().put("transcriptModelSha256", "0".repeat(64));
        assertEquals("PVS1_SPLICE_MODEL_IDENTITY_MISMATCH", Pvs1SpliceConsequence.resolveForIntegration(i.context,
                i.candidate.getTranscriptAnnotations().get(0), i.policy, i.model, d.material(), input(), i.selection));
    }

    @Test void reusingInputReplacesMaterialPinsAndRemovesPreviousMechanismAndChecks() throws Exception {
        var d = base.data(1, false, false);var reused = input();assertNull(module(d, reused));
        reused.put("transcriptModelSha256", "0".repeat(64)).put("spliceMaterialSha256", "0".repeat(64));
        reused.putArray("escapePositionConditionChecks").add("STALE");
        inFrame(d, 2, 13, 24);assertNull(module(d, reused), reused::toPrettyString);
        for (String stale : List.of("nmdPrediction", "mechanism", "exonRelevance", "positionConditionChecks", "escapePositionConditionChecks"))
            assertFalse(reused.has(stale), stale);
        assertEquals(adapter.canonicalSha256(d.input().model), reused.path("transcriptModelSha256").asText());
        assertEquals(adapter.canonicalSha256(d.material()), reused.path("spliceMaterialSha256").asText());
    }

    private void modelExons(Pvs1SpliceConsequenceTest.Data d, int... ends) {
        var exons = d.input().model.putArray("exons");int start = 1;
        for (int i = 0; i < ends.length; i++) {
            int g = 2000 + i * 1000;
            exons.addObject().put("ordinal", i + 1).put("cdsStart", start).put("cdsEnd", ends[i])
                    .put("genomicStart", g).put("genomicEnd", g + ends[i] - start);
            start = ends[i] + 1;
        }
        var nmd = d.input().policy.withObject("nmdScope");
        var relevant = nmd.putArray("relevantExonOrdinals");var supported = nmd.putArray("engineeringSupportedExonOrdinals");
        for (int i = 1; i <= ends.length; i++) { relevant.add(i);if (i < ends.length) supported.add(i); }
    }

    @Test void stopCodonAcrossJunctionRetainsAllThreeReferenceCoordinates() throws Exception {
        var d = base.data(1, false, false);modelExons(d, 12, 25, 62, 450, 600);
        var input = input();assertNull(module(d, input), input::toPrettyString);
        assertTrue(input.at("/spliceAssessment/ptcCrossesJunction").asBoolean());
        assertEquals(2, input.at("/spliceAssessment/ptcExons").size());
        assertEquals(62, input.path("ptcReferenceStart").asInt());
        assertEquals(63, input.path("ptcReferenceMiddle").asInt());
        assertEquals(64, input.path("ptcReferenceEnd").asInt());
        assertEquals(437, input.path("lastJunctionCdsPosition").asInt());
        assertEquals(386, input.path("distanceToLastJunction").asInt());
        assertEquals("PVS1_SPLICE_RUNTIME_ADAPTER_REQUIRED", compatibility(d, input()));
    }

    @Test void utrJunctionRequiresOwnSourceApplicabilityAndUsesAlteredPosition() throws Exception {
        var d = base.data(1, false, false);modelExons(d, 12, 25, 450, 620, 650);
        var model = d.input().model;model.put("lastJunctionCdsPosition", 620);
        model.putObject("sequenceVerification").put("cdsSha256", CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText()))
                .put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText() + "A".repeat(50)));
        var input = input();assertEquals("PVS1_UTR_JUNCTION_APPLICABILITY_UNRESOLVED", module(d, input));
        d.input().policy.withObject("nmdScope").putObject("utrJunctionApplicability").put("status", "REVIEWED_FOR_3_PRIME_UTR_JUNCTION")
                .put("basis", "TEST_ONLY source applicability").putArray("sourceIds").add("synthetic-source");
        input = input();assertNull(module(d, input), input::toPrettyString);
        assertEquals(607, input.path("lastJunctionCdsPosition").asInt());
        assertEquals("LAST_JUNCTION_IN_3_PRIME_UTR", input.at("/spliceAssessment/alteredTopology/kind").asText());
        assertEquals("PVS1_SPLICE_RUNTIME_ADAPTER_REQUIRED", compatibility(d, input()));
    }

    @Test void terminalShorteningUsesReferenceSourceIdentityAndAlteredEscapeGeometry() throws Exception {
        var d = base.data(1, false, false);modelExons(d, 12, 25, 450, 600);var model = d.input().model;
        String cds = model.path("cdsSequence").asText();
        model.put("cdsSequence", cds.substring(0, 480) + "CTAACC" + cds.substring(486));
        d.material().withObject("event").put("type", "EXON_SHORTENING").put("exonOrdinal", 4)
                .put("deleteStartCds", 451).put("deleteEndCds", 463);
        var input = input();assertNull(module(d, input), input::toPrettyString);
        assertEquals(469, input.path("ptcStart").asInt());assertEquals(471, input.path("ptcEnd").asInt());
        assertEquals(-21, input.path("distanceToLastJunction").asInt());
        assertEquals("STRONG", input.at("/spliceAssessment/adoptedStrength").asText());
        assertEquals("PVS1_SPLICE_RUNTIME_ADAPTER_REQUIRED", compatibility(d, input()));
    }

    @Test void terminalExonDeletionDoesNotInventAStartOrExtendedStop() throws Exception {
        var first = base.data(1, false, false);first.material().withObject("event").put("exonOrdinal", 1).put("deleteStartCds", 1).put("deleteEndCds", 12);
        assertEquals("PVS1_SPLICE_START_REMOVAL_BRANCH_NOT_IMPLEMENTED", module(first, input()));
        var last = base.data(1, false, false);last.material().withObject("event").put("exonOrdinal", 4).put("deleteStartCds", 451).put("deleteEndCds", 600);
        assertEquals("PVS1_SPLICE_TERMINAL_EXTENSION_BRANCH_NOT_IMPLEMENTED", module(last, input()));
    }

    @Test void rnaSmallIndelDoesNotAutomaticallyRequireAnUnrelatedCodingEdit() throws Exception {
        var d = base.data(1, false, false);
        base.moveAllele(d, 3013, "GT", "G", "splice_donor_variant", ":c.25+1_25+2del", 3013, "GT");
        d.input().context.withObject("variant").put("variantType", "SMALL_DELETION");
        d.input().policy.withObject("scope").put("variantType", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT");
        d.material().set("allele", d.input().context.at("/variant/evidenceQueryAllele").deepCopy());
        var input = input();assertNull(module(d, input), input::toPrettyString);
        assertFalse(input.at("/spliceAssessment/retainedCodingAlleleAssessment/requiresEditCore").asBoolean());
        assertEquals("MET", input.at("/spliceAssessment/status").asText());
        assertEquals("PVS1_SPLICE_RUNTIME_ADAPTER_REQUIRED", compatibility(d, input()));
        d.material().put("evidenceKind", "PREDICTED_CANONICAL");
        d.material().withObject("assessment").put("status", "QUALIFIED_CANONICAL_PREDICTION");
        assertEquals("PVS1_SPLICE_CANONICAL_SNV_REQUIRED", module(d, input()));
    }

    @Test void retainedCodingEditRequiresSourceBoundCompositionReview() throws Exception {
        var d = base.data(1, false, false);
        base.moveAllele(d, 5000, "C", "T", "splice_region_variant", ":c.451C>T", 5000, "C");
        d.material().set("allele", d.input().context.at("/variant/evidenceQueryAllele").deepCopy());
        var input = input();assertEquals("PVS1_SPLICE_RETAINED_CODING_ALLELE_NOT_SUPPORTED", module(d, input));
        assertTrue(input.at("/spliceAssessment/retainedCodingAlleleAssessment/requiresReviewedComposition").asBoolean());
        assertNotEquals("MET", input.at("/spliceAssessment/status").asText());
    }

    @Test void inFrameRnaDeletionCreatingJunctionStopUsesNmdRatherThanRegionalScore() throws Exception {
        var d = base.data(1, false, false);modelExons(d, 6, 13, 450, 600);
        d.input().model.put("cdsSequence", "ATGAAATCTGGTCAAGAA" + "CCA".repeat(193) + "TAA");
        d.input().policy.withObject("nmdScope").put("minimumCdsPosition", 4);
        d.material().withObject("event").put("type", "EXON_SHORTENING").put("exonOrdinal", 2)
                .put("deleteStartCds", 8).put("deleteEndCds", 13);
        // Original MKSGQE...; deletion joins the retained T to AA, creating TAA at c.7.
        var input = input();assertNull(module(d, input), input::toPrettyString);
        assertEquals(7, input.path("ptcStart").asInt());assertEquals(9, input.path("ptcEnd").asInt());
        assertEquals("PREDICTED_NMD", input.path("nmdPrediction").asText());
        assertFalse(input.has("inFrameAssessment"));
        assertEquals("VERY_STRONG", input.at("/spliceAssessment/adoptedStrength").asText());
        assertEquals("PVS1_SPLICE_RUNTIME_ADAPTER_REQUIRED", compatibility(d, input()));
    }

    @Test void deletionOfEntireGenomicIntronCannotInventARemainingSpliceJunction() throws Exception {
        var d = base.data(1, false, false);
        var exon = (ObjectNode) d.input().model.path("exons").get(2);
        exon.put("genomicStart", 3033).put("genomicEnd", 3457);
        String ref = "C" + "G".repeat(20);
        base.moveAllele(d, 3012, ref, "C", "splice_region_variant", ":c.25+1_25+20del", 3012, ref);
        d.input().context.withObject("variant").put("variantType", "SMALL_DELETION");
        d.input().policy.withObject("scope").put("variantType", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT");
        d.material().set("allele", d.input().context.at("/variant/evidenceQueryAllele").deepCopy());
        var input = input();assertEquals("PVS1_SPLICE_COMPOSITION_GENOMIC_JUNCTION_TOPOLOGY_UNSUPPORTED", module(d, input));
        assertFalse(input.has("nmdPrediction"));
    }

    @Test void newTerminalNegativeIsNotConsumedByUnadaptedPublicRuntime() throws Exception {
        var d = base.data(1, false, false);modelExons(d, 12, 25, 450, 600);var model = d.input().model;
        String cds = model.path("cdsSequence").asText();
        model.put("cdsSequence", cds.substring(0, 480) + "CTAACC" + cds.substring(486));
        d.material().withObject("event").put("type", "EXON_SHORTENING").put("exonOrdinal", 4)
                .put("deleteStartCds", 451).put("deleteEndCds", 463);
        for (var row : d.input().policy.at("/escapeScope/exonAssessments"))
            if (row.path("ordinal").asInt() == 4) ((ObjectNode) row).put("populationLof", "FREQUENT");
        var input = input();assertEquals("PVS1_ESCAPE_EXON_LOF_TOLERANT", module(d, input));
        assertEquals("NOT_MET", input.at("/spliceAssessment/status").asText());
        assertEquals("PVS1_SPLICE_RUNTIME_ADAPTER_REQUIRED", compatibility(d, input()));
    }

    @Test void derivedTopologyDistinguishesReferenceFromActualAlteredTranscript() throws Exception {
        var d = base.data(1, false, false);var input = input();assertNull(module(d, input));
        assertEquals(4, input.at("/referenceTranscriptTopology/exonCount").asInt());
        assertEquals(3, input.at("/transcriptTopology/exonCount").asInt());
        assertEquals(450, input.at("/referenceTranscriptTopology/lastJunctionCdsPosition").asInt());
        assertEquals(437, input.at("/transcriptTopology/lastJunctionCdsPosition").asInt());
        assertFalse(input.path("transcriptTopology").has("fullSplicedTranscriptSha256"), "No invented full altered RNA proof");
    }

    @Test void firstExonShorteningCanPreserveStartAndUseItsOwnReviewedRegionalRule() throws Exception {
        var d = base.data(1, false, false);inFrame(d, 1, 4, 12);
        var input = input();assertNull(module(d, input), input::toPrettyString);
        assertTrue(input.path("alteredCdsSequence").asText().startsWith("ATG"));
        assertEquals(2, input.at("/inFrameAssessment/firstDeletedReferenceResidue").asInt());
        assertEquals(4, input.at("/inFrameAssessment/lastDeletedReferenceResidue").asInt());
        assertEquals("STRONG", input.at("/spliceAssessment/adoptedStrength").asText());
        assertEquals("PVS1_SPLICE_RUNTIME_ADAPTER_REQUIRED", compatibility(d, input()));
    }

    @Test void phaseOneSpliceRetainsFusionCodonAndOnlyCountsFullyDeletedCriticalResidues() throws Exception {
        var d = base.data(1, false, false);inFrame(d, 2, 14, 25);
        var input = input();assertNull(module(d, input), input::toPrettyString);
        assertFalse(input.at("/inFrameAssessment/codonAligned").asBoolean());
        assertEquals(4, input.at("/inFrameAssessment/deletedProteinResidues").asInt());
        assertEquals(3, input.at("/inFrameAssessment/fullyRemovedReferenceResidues").asInt());
        assertEquals(6, input.at("/inFrameAssessment/firstFullyRemovedReferenceResidue").asInt());
        assertEquals(8, input.at("/inFrameAssessment/lastFullyRemovedReferenceResidue").asInt());
        assertTrue(input.at("/inFrameAssessment/junctionEffect").isObject());
        assertEquals("STRONG", input.at("/spliceAssessment/adoptedStrength").asText());
    }

    @Test void independentReviewedInFrameRelevanceDoesNotRequireNmdCoverageForThatExon() throws Exception {
        var d = base.data(1, false, false);inFrame(d, 2, 13, 24);
        var nmd = d.input().policy.withObject("nmdScope");
        nmd.putArray("relevantExonOrdinals").add(1).add(3).add(4);
        nmd.putArray("engineeringSupportedExonOrdinals").add(1).add(3);
        var input = input();assertNull(module(d, input), input::toPrettyString);
        assertEquals("MET", input.at("/spliceAssessment/status").asText());
        assertEquals("STRONG", input.at("/spliceAssessment/adoptedStrength").asText());
    }
}
