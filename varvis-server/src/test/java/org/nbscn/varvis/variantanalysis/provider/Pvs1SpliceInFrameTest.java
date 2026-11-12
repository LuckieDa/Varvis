package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independently specified toy sequences and reviewed rules; not scientific policy approvals. */
class Pvs1SpliceInFrameTest {
    /* Independent hand calculations fixed before the phase-1/2 implementation:
     * Reference CDS: ATG AAA CCT GGT TTT GAA TAA -> M K P G F E * (6 residues).
     * Delete c.8_13: retain C from residue 3 and TT from residue 5 -> CTT (L).
     *   Mutant ATG AAA CTT GAA TAA -> MKLE*: prefix MK + L + suffix E*.
     * Delete c.9_14: retain CC from residue 3 and T from residue 5 -> CCT (P).
     *   Mutant ATG AAA CCT GAA TAA -> MKPE*: prefix MK + P + suffix E*.
     * Both touch residues 3..5, completely remove only residue 4 (G), and shorten
     * the protein by 2/6. Equality of the second fusion AA to original P does not
     * move the RNA event or turn either partial residue into a complete deletion.
     * Delete c.8_10: C + GT -> CGT (R), MKRFE*, touch 3..4, no complete codon
     * removed, net shortening 1/6; no complete-deletion critical rule may match.
     * Premature-stop control: ATG AAA TCT GGT CAA GAA TAA -> MKSGQE*;
     * c.8_13del makes T + AA -> TAA, giving MK* before the retained native stop.
     * It requires the truncating/NMD route and cannot acquire an A3 strength.
     */
    final ObjectMapper mapper = new ObjectMapper();
    record Data(ObjectNode policy, ObjectNode model, ObjectNode fact, ObjectNode input, ObjectNode selection) { }

    Data data(int strand) {
        var model = mapper.createObjectNode().put("transcript", "NM_TEST.1").put("strand", strand)
                .put("cdsSequence", "ATGAAACCTGGTTTTGAATAA").put("proteinLength", 6);
        var exons = model.putArray("exons");
        exons.addObject().put("ordinal", 1).put("cdsStart", 1).put("cdsEnd", 6);
        exons.addObject().put("ordinal", 2).put("cdsStart", 7).put("cdsEnd", 12);
        exons.addObject().put("ordinal", 3).put("cdsStart", 13).put("cdsEnd", 21);
        var policy = mapper.createObjectNode().put("schemaVersion", "pvs1-truncating-nmd-policy-v4");
        var source = policy.putArray("scientificSources").addObject().put("sourceId", "synthetic-function")
                .put("version", "TEST_ONLY").put("url", "https://example.test/function").put("sha256", "a".repeat(64));
        policy.putObject("nmdScope").putArray("relevantExonOrdinals").add(1).add(2).add(3);
        var scope = policy.putObject("spliceScope").putObject("inFrameScope")
                .put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME").put("reviewId", "TEST_ONLY_INFRAME_POLICY")
                .put("basis", "Synthetic explicit local effect rules");
        scope.putArray("sourceIds").add("synthetic-function");
        var assessments = scope.putArray("exonAssessments");
        for (int ordinal = 1; ordinal <= 3; ordinal++) assessments.addObject().put("ordinal", ordinal)
                .put("biologicalRelevance", "PRESENT").put("populationLof", "NOT_FREQUENT")
                .put("basis", "Synthetic reviewed exon facts").putArray("sourceIds").add("synthetic-function");
        scope.putArray("rules");critical(scope, "local-region", 3, 4, 2, "STRONG");
        var fact = mapper.createObjectNode().put("transcript", "NM_TEST.1");
        fact.putObject("event").put("type", "EXON_SKIPPING").put("exonOrdinal", 2).put("deleteStartCds", 7).put("deleteEndCds", 12);
        fact.putArray("sourceRecords").add(source.deepCopy()).addObject().put("sourceId", "synthetic-rna")
                .put("version", "TEST_ONLY").put("url", "https://example.test/rna").put("sha256", "b".repeat(64));
        fact.putObject("inFrameReview").put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME").put("reviewId", "TEST_ONLY_EXACT_EVENT")
                .put("basis", "Toy exact RNA event and separate functional sources").putArray("sourceIds")
                .add("synthetic-rna").add("synthetic-function");
        var input = mapper.createObjectNode().put("referenceEditStart0", 6).put("referenceEditEnd0", 12)
                .put("netLengthChange", -6).put("alteredCdsSequence", "ATGAAATTTGAATAA");
        return new Data(policy, model, fact, input, mapper.createObjectNode().put("outcome", "SPECIALIZED_POLICY_SELECTED"));
    }
    ObjectNode scope(Data data) { return (ObjectNode) data.policy().at("/spliceScope/inFrameScope"); }
    ObjectNode audit(Data data) { return (ObjectNode) data.input().path("inFrameAssessment"); }
    ObjectNode critical(ObjectNode scope, String id, int start, int end, int minimum, String strength) {
        var rule = scope.withArray("rules").addObject().put("ruleId", id).put("predicate", "CRITICAL_REGION")
                .put("start", start).put("end", end).put("minimumAffectedResidues", minimum).put("strength", strength)
                .put("basis", "TEST_ONLY exact critical interval and overlap requirement");
        rule.putArray("sourceIds").add("synthetic-function");return rule;
    }
    ObjectNode fraction(Data data, String comparison, int numerator, int denominator) {
        var rule = scope(data).withArray("rules").addObject().put("ruleId", "fraction-" + comparison)
                .put("predicate", "DELETED_PROTEIN_FRACTION").put("comparison", comparison)
                .put("numerator", numerator).put("denominator", denominator).put("strength", "MODERATE")
                .put("basis", "TEST_ONLY explicit ratio of deleted residues to full reference protein");
        rule.putArray("sourceIds").add("synthetic-function");return rule;
    }
    ObjectNode completeRegionAssessment(Data data) {
        var fact = data.fact().withObject("inFrameReview").putObject("criticalRegionAssessment")
                .put("status", "REVIEWED_IN_DECLARED_SCOPE").put("coverage", "COMPLETE_FOR_AFFECTED_PROTEIN_REGION")
                .put("finding", "NO_KNOWN_CRITICAL_REGION_AFFECTED").put("start", 3).put("end", 4)
                .put("basis", "TEST_ONLY explicit complete critical-region assessment for the deleted span");
        fact.putArray("sourceIds").add("synthetic-function");return fact;
    }
    Data phaseData(int strand, int start, int end, String expectedCds) {
        var data = data(strand);
        ((ObjectNode) data.model().at("/exons/0")).put("cdsEnd", start - 1);
        ((ObjectNode) data.model().at("/exons/1")).put("cdsStart", start).put("cdsEnd", end);
        ((ObjectNode) data.model().at("/exons/2")).put("cdsStart", end + 1);
        data.fact().withObject("event").put("deleteStartCds", start).put("deleteEndCds", end);
        data.input().put("alteredCdsSequence", expectedCds).put("referenceEditStart0", start - 1)
                .put("referenceEditEnd0", end).put("netLengthChange", -(end - start + 1));
        return data;
    }
    String run(Data data) { return Pvs1SpliceInFrame.evaluate(data.policy(), data.model(), data.fact(), data.input(), data.selection()); }
    void reason(Data data, String expected) {
        assertEquals(expected, run(data), data.input().toPrettyString());
        assertFalse(audit(data).has("selectedRule"));
        assertNotEquals("MET", audit(data).path("status").asText());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void exactLocalDeletionRemovesOnlyProlineAndGlycineOnEitherStrand(int strand) {
        var data = data(strand);
        assertNull(Pvs1SpliceInFrame.policyIssue(data.policy(), data.model()));assertNull(run(data));
        var audit = audit(data);
        assertEquals("MKPGFE*", audit.path("referenceProteinThroughTermination").asText());
        assertEquals("MKFE*", audit.path("alteredProteinThroughTermination").asText());
        assertEquals(3, audit.path("firstDeletedReferenceResidue").asInt());
        assertEquals(4, audit.path("lastDeletedReferenceResidue").asInt());
        assertEquals(2, audit.path("deletedProteinResidues").asInt());
        assertEquals(4, audit.path("alteredProteinLength").asInt());
        assertEquals("P", audit.at("/referenceResidueEffects/0/referenceAminoAcid").asText());
        assertEquals("G", audit.at("/referenceResidueEffects/1/referenceAminoAcid").asText());
        assertEquals(2, audit.at("/ruleChecks/0/affectedResiduesInRegion").asInt());
        assertEquals("STRONG", audit.path("strength").asText());
        assertEquals("synthetic-function", audit.at("/selectedRule/sourceIds/0").asText());
    }
    @Test void identicalResiduesCannotShiftTheDeletionAwayFromItsSourceCoordinates() {
        var data = data(1);data.model().put("cdsSequence", "ATG" + "CCA".repeat(5) + "TAA");
        data.input().put("alteredCdsSequence", "ATG" + "CCA".repeat(3) + "TAA");
        assertNull(run(data));
        assertEquals(3, audit(data).path("firstDeletedReferenceResidue").asInt());
        assertEquals(4, audit(data).path("lastDeletedReferenceResidue").asInt());
    }
    @Test void distalRegionsArePreservedAndCannotBeCountedAsALostTail() {
        var data = data(1);scope(data).putArray("rules");critical(scope(data), "preserved-tail", 5, 6, 1, "STRONG");
        reason(data, "PVS1_SPLICE_INFRAME_NO_REVIEWED_RULE_MATCH");
        assertEquals(0, audit(data).at("/ruleChecks/0/affectedResiduesInRegion").asInt());
        scope(data).putArray("rules");fraction(data, "GT", 1, 2);completeRegionAssessment(data);
        reason(data, "PVS1_SPLICE_INFRAME_NO_REVIEWED_RULE_MATCH");
        assertEquals(2, audit(data).at("/ruleChecks/0/actualNumerator").asInt());
        assertEquals(6, audit(data).at("/ruleChecks/0/actualDenominator").asInt());
    }
    @Test void deletedFractionNeedsAnExplicitCompleteCriticalRegionReviewAndAnExactRationalRule() {
        var data = data(1);scope(data).putArray("rules");fraction(data, "GT", 1, 4);
        reason(data, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_REQUIRED");
        completeRegionAssessment(data);assertNull(run(data));assertEquals("MODERATE", audit(data).path("strength").asText());
        scope(data).putArray("rules");fraction(data, "GT", 1, 3);fraction(data, "LT", 1, 3);
        reason(data, "PVS1_SPLICE_INFRAME_FRACTION_BOUNDARY_UNSPECIFIED");
        assertEquals(6L, audit(data).at("/ruleChecks/0/leftProduct").asLong());
        assertEquals(6L, audit(data).at("/ruleChecks/0/rightProduct").asLong());
    }
    @ParameterizedTest @ValueSource(strings = {"critical-first", "fraction-first"})
    void aQualifiedCriticalBranchExcludesTheFractionBranchWithoutInventingItsMissingReview(String order) {
        // Independent decision-tree expectation: the qualifying critical rule is Moderate.
        // A parallel Strong fraction rule requires absence of known critical damage;
        // its premise is false here, so it neither blocks Moderate nor raises it to Strong.
        var data = data(1);var criticalRule = ((ObjectNode) scope(data).at("/rules/0")).put("strength", "MODERATE");
        var fractionRule = fraction(data, "GT", 1, 4).put("strength", "STRONG");
        if (order.equals("fraction-first")) scope(data).putArray("rules").add(fractionRule).add(criticalRule);
        assertNull(run(data), data.input().toPrettyString());
        assertEquals("MODERATE", audit(data).path("strength").asText());
        assertEquals("local-region", audit(data).at("/selectedRule/ruleId").asText());
        int fractionRow = order.equals("fraction-first") ? 0 : 1;
        var row = audit(data).path("ruleChecks").path(fractionRow);
        assertEquals("NOT_APPLICABLE_KNOWN_CRITICAL_REGION_AFFECTED", row.path("status").asText());
        assertEquals("SOURCE_BOUND_CRITICAL_REGION_RULE_MATCH", row.path("routingBasis").asText());
        assertEquals("PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_REQUIRED", row.path("criticalRegionAssessmentIssue").asText());
        assertEquals("local-region", row.at("/qualifyingCriticalRules/0/ruleId").asText());
        assertEquals("synthetic-function", row.at("/qualifyingCriticalRules/0/sourceIds/0").asText());
        assertFalse(audit(data).path("inFrameReview").has("criticalRegionAssessment"));
        completeRegionAssessment(data);reason(data, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_FACTS_CONFLICT");
        data.fact().withObject("inFrameReview").remove("criticalRegionAssessment");
        critical(scope(data), "another-critical", 3, 4, 1, "SUPPORTING");
        reason(data, "PVS1_SPLICE_INFRAME_RULES_AMBIGUOUS");
    }
    @Test void criticalRoutingDoesNotExcuseInvalidSourcesOrMandatoryExonFacts() {
        var data = data(1);fraction(data, "GT", 1, 4).put("strength", "STRONG");
        completeRegionAssessment(data).putArray("sourceIds").add("missing");
        reason(data, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_INVALID");
        data.fact().withObject("inFrameReview").remove("criticalRegionAssessment");
        ((ObjectNode) scope(data).at("/exonAssessments/1")).put("populationLof", "UNKNOWN");
        reason(data, "PVS1_SPLICE_INFRAME_POPULATION_TOLERANCE_UNRESOLVED");
        data = data(1);scope(data).putArray("rules");fraction(data, "GT", 1, 4).put("strength", "STRONG");
        reason(data, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_REQUIRED");
    }
    @ParameterizedTest @ValueSource(strings = {"scope", "incomplete", "unknown", "unreviewed", "source", "known"})
    void criticalRegionUncertaintyCannotBeReplacedByNoMatchingRegionRule(String mutation) {
        var data = data(1);scope(data).putArray("rules");fraction(data, "GT", 1, 4);var fact = completeRegionAssessment(data);
        String expected = "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_REQUIRED";
        switch (mutation) {
            case "scope" -> fact.put("end", 3);
            case "incomplete" -> fact.put("coverage", "INCOMPLETE");
            case "unknown" -> fact.put("finding", "UNKNOWN");
            case "unreviewed" -> fact.put("status", "UNREVIEWED");
            case "source" -> { fact.putArray("sourceIds").add("missing");expected = "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_INVALID"; }
            case "known" -> { fact.put("finding", "KNOWN_CRITICAL_REGION_AFFECTED");expected = "PVS1_SPLICE_INFRAME_NO_REVIEWED_RULE_MATCH"; }
            default -> throw new AssertionError();
        }
        reason(data, expected);assertEquals(2, audit(data).path("deletedProteinResidues").asInt());
    }
    @Test void criticalRegionFactsAndOverlappingRulesCannotBeSilentlySelected() {
        var data = data(1);completeRegionAssessment(data);fraction(data, "GT", 1, 4);
        reason(data, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_FACTS_CONFLICT");
        data.fact().withObject("inFrameReview").remove("criticalRegionAssessment");scope(data).withArray("rules").remove(1);
        critical(scope(data), "another-region", 2, 3, 1, "SUPPORTING");
        reason(data, "PVS1_SPLICE_INFRAME_RULES_AMBIGUOUS");
    }
    @ParameterizedTest @ValueSource(ints = {8, 9})
    void phaseChangingJunctionsRetainFactsWithoutInventingTwoDamagedAminoAcids(int start) {
        var data = phaseData(1, start, start + 5, start == 8 ? "ATGAAACTTGAATAA" : "ATGAAACCTGAATAA");
        reason(data, "PVS1_SPLICE_INFRAME_JUNCTION_FUNCTION_UNRESOLVED");
        var audit = audit(data);
        assertEquals(3, audit.path("firstTouchedReferenceResidue").asInt());
        assertEquals(5, audit.path("lastTouchedReferenceResidue").asInt());
        assertEquals(1, audit.path("fullyRemovedReferenceResidues").asInt());
        assertEquals("REMOVED", audit.at("/referenceResidueEffects/1/effect").asText());
        assertEquals(start == 8 ? "CTT" : "CCT", audit.at("/junctionEffect/alteredCodon").asText());
        assertEquals(start == 8 ? "L" : "P", audit.at("/junctionEffect/alteredAminoAcid").asText());
        assertEquals(2, audit.path("deletedProteinResidues").asInt());
        assertEquals(1, audit.at("/ruleChecks/0/affectedResiduesInRegion").asInt());
        assertEquals(1, audit.at("/ruleChecks/0/partiallyDeletedReferenceResiduesInRegion").asInt());
        assertEquals("EXACT_LOCAL_INFRAME_DELETION_WITH_FUSION_CODON", audit.path("sequenceDerivationStatus").asText());
    }
    @ParameterizedTest @ValueSource(ints = {8, 9})
    void phaseChangingJunctionsCanUseAReviewedFullyRemovedCriticalResidue(int start) {
        for (int strand : new int[] {1, -1}) {
            var data = phaseData(strand, start, start + 5, start == 8 ? "ATGAAACTTGAATAA" : "ATGAAACCTGAATAA");
            scope(data).putArray("rules");critical(scope(data), "removed-glycine", 4, 4, 1, "STRONG");
            assertNull(run(data), data.input().toPrettyString());
            var audit = audit(data);
            assertEquals(start == 8 ? "MKLE*" : "MKPE*", audit.path("alteredProteinThroughTermination").asText());
            assertEquals(audit.path("alteredProteinThroughTermination"), audit.path("expectedProteinThroughTermination"));
            assertEquals(4, audit.path("firstFullyRemovedReferenceResidue").asInt());
            assertEquals(4, audit.path("lastFullyRemovedReferenceResidue").asInt());
            assertEquals(1, audit.at("/ruleChecks/0/affectedResiduesInRegion").asInt());
            assertEquals("STRONG", audit.path("strength").asText());
            assertTrue(audit.path("nativeStopRetained").asBoolean());
        }
    }
    @ParameterizedTest @ValueSource(ints = {8, 9})
    void partialCriticalResiduesNeverCountAsFullyRemovedEvenWhenFusionAminoAcidIsConserved(int start) {
        var data = phaseData(1, start, start + 5, start == 8 ? "ATGAAACTTGAATAA" : "ATGAAACCTGAATAA");
        scope(data).putArray("rules");critical(scope(data), "partial-proline", 3, 3, 1, "STRONG");
        reason(data, "PVS1_SPLICE_INFRAME_JUNCTION_FUNCTION_UNRESOLVED");
        assertEquals(0, audit(data).at("/ruleChecks/0/affectedResiduesInRegion").asInt());
        assertEquals(1, audit(data).at("/ruleChecks/0/partiallyDeletedReferenceResiduesInRegion").asInt());
        completeRegionAssessment(data).put("end", 5);fraction(data, "GT", 1, 4);
        reason(data, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_FACTS_CONFLICT");
    }
    @ParameterizedTest @ValueSource(ints = {8, 9})
    void phaseChangingFractionUsesNetShorteningAndRequiresReviewOfTheEntireTouchedSpan(int start) {
        var data = phaseData(1, start, start + 5, start == 8 ? "ATGAAACTTGAATAA" : "ATGAAACCTGAATAA");
        scope(data).putArray("rules");fraction(data, "GT", 1, 4);
        var assessment = completeRegionAssessment(data);
        reason(data, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_REQUIRED");
        assessment.put("start", 4).put("end", 5);
        reason(data, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_REQUIRED");
        assessment.put("start", 3);assertNull(run(data));
        assertEquals(1, audit(data).path("fullyRemovedReferenceResidues").asInt());
        assertEquals(2, audit(data).at("/ruleChecks/0/actualNumerator").asInt());
        assertEquals(6, audit(data).at("/ruleChecks/0/actualDenominator").asInt());
        scope(data).putArray("rules");fraction(data, "GT", 1, 3);fraction(data, "LT", 1, 3);
        reason(data, "PVS1_SPLICE_INFRAME_FRACTION_BOUNDARY_UNSPECIFIED");
    }
    @Test void aSingleCodonLengthDeletionCanFuseTwoResiduesWithoutCompletelyRemovingEither() {
        var data = phaseData(-1, 8, 10, "ATGAAACGTTTTGAATAA");
        scope(data).putArray("rules");critical(scope(data), "two-partial-residues", 3, 4, 1, "STRONG");
        reason(data, "PVS1_SPLICE_INFRAME_JUNCTION_FUNCTION_UNRESOLVED");
        assertEquals("MKRFE*", audit(data).path("alteredProteinThroughTermination").asText());
        assertEquals("CGT", audit(data).at("/junctionEffect/alteredCodon").asText());
        assertEquals(0, audit(data).path("fullyRemovedReferenceResidues").asInt());
        assertTrue(audit(data).path("firstFullyRemovedReferenceResidue").isNull());
        assertTrue(audit(data).path("lastFullyRemovedReferenceResidue").isNull());
        assertEquals(0, audit(data).at("/ruleChecks/0/affectedResiduesInRegion").asInt());
        assertEquals(2, audit(data).at("/ruleChecks/0/partiallyDeletedReferenceResiduesInRegion").asInt());
        scope(data).putArray("rules");fraction(data, "GT", 1, 10);completeRegionAssessment(data);
        assertNull(run(data));assertEquals(1, audit(data).at("/ruleChecks/0/actualNumerator").asInt());
        assertEquals("MODERATE", audit(data).path("strength").asText());
    }
    @Test void aFusionStopCannotAcquireInFrameStrengthBeforeTheTruncatingRoute() {
        var data = phaseData(1, 8, 13, "ATGAAATAAGAATAA");
        data.model().put("cdsSequence", "ATGAAATCTGGTCAAGAATAA");
        scope(data).putArray("rules");critical(scope(data), "removed-glycine", 4, 4, 1, "STRONG");
        reason(data, "PVS1_SPLICE_INFRAME_PREMATURE_TERMINATION_REQUIRES_NMD");
        assertEquals("MK*", audit(data).path("alteredProteinThroughTermination").asText());
        assertEquals("TAA", audit(data).at("/junctionEffect/alteredCodon").asText());
        assertFalse(audit(data).has("ruleChecks"));
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        assertDoesNotThrow(() -> adapter.canonicalSha256(data.input()));
    }
    @ParameterizedTest @ValueSource(strings = {"population", "relevance", "frequent", "absent", "missing"})
    void onlyExplicitlyReviewedRelevantIntolerantExonsCanGiveStrength(String mutation) {
        var data = data(1);var fact = (ObjectNode) scope(data).at("/exonAssessments/1");String expected;
        switch (mutation) {
            case "population" -> { fact.put("populationLof", "UNKNOWN");expected = "PVS1_SPLICE_INFRAME_POPULATION_TOLERANCE_UNRESOLVED"; }
            case "relevance" -> { fact.put("biologicalRelevance", "UNKNOWN");expected = "PVS1_SPLICE_INFRAME_EXON_RELEVANCE_UNRESOLVED"; }
            case "frequent" -> { fact.put("populationLof", "FREQUENT");expected = "PVS1_SPLICE_INFRAME_EXON_LOF_TOLERANT"; }
            case "absent" -> {
                fact.put("biologicalRelevance", "ABSENT");data.policy().withObject("nmdScope").putArray("relevantExonOrdinals").add(1).add(3);
                expected = "PVS1_SPLICE_INFRAME_EXON_NOT_RELEVANT";
            }
            case "missing" -> { scope(data).withArray("exonAssessments").remove(1);expected = "PVS1_SPLICE_INFRAME_EXON_ASSESSMENT_MISSING"; }
            default -> throw new AssertionError();
        }
        reason(data, expected);assertTrue(audit(data).has("exonChecks"));
        assertEquals(mutation.equals("frequent") || mutation.equals("absent"), Pvs1SpliceInFrame.negative(expected));
    }
    @Test void independentlyReviewedInFrameRelevanceDoesNotRequireCoverageByTheNmdPositiveSubset() {
        var data = data(1);data.policy().withObject("nmdScope").putArray("relevantExonOrdinals").add(1).add(3);
        assertNull(Pvs1SpliceInFrame.policyIssue(data.policy(), data.model()));
        assertNull(run(data), data.input().toPrettyString());
        assertEquals("MET", audit(data).path("status").asText());
        assertEquals("PRESENT", audit(data).at("/exonChecks/0/biologicalRelevance").asText());
        assertEquals("synthetic-function", audit(data).at("/exonChecks/0/sourceIds/0").asText());
        assertEquals("STRONG", audit(data).path("strength").asText());
    }
    @Test void anAbsentFactConflictingWithExplicitReviewedRelevanceCannotBecomeANegativeDecision() {
        var data = data(1);((ObjectNode) scope(data).at("/exonAssessments/1")).put("biologicalRelevance", "ABSENT");
        reason(data, "PVS1_SPLICE_INFRAME_EXON_FACTS_CONFLICT");
        assertEquals("NOT_EVALUATED", audit(data).path("status").asText());
        assertEquals("ABSENT", audit(data).at("/exonChecks/0/biologicalRelevance").asText());
        assertFalse(Pvs1SpliceInFrame.negative(audit(data).path("reasonCode").asText()));
    }
    @Test void untouchedExonsDoNotExpandTheLocalDeletionAndMixedAffectedExonsRemainUnresolved() {
        var data = data(1);scope(data).withArray("exonAssessments").remove(2);scope(data).withArray("exonAssessments").remove(0);
        assertNull(run(data));assertEquals(1, audit(data).path("exonChecks").size());
        data = data(1);data.fact().withObject("event").put("deleteStartCds", 4);
        data.input().remove(java.util.List.of("alteredCdsSequence", "referenceEditStart0", "referenceEditEnd0", "netLengthChange"));
        ((ObjectNode) scope(data).at("/exonAssessments/0")).put("biologicalRelevance", "ABSENT");
        reason(data, "PVS1_SPLICE_INFRAME_EXON_FACTS_CONFLICT");assertEquals(2, audit(data).path("exonChecks").size());
    }
    @Test void veryStrongRequiresSpecializedSelectionAndNoDefaultStrengthIsIntroduced() {
        var data = data(1);((ObjectNode) scope(data).at("/rules/0")).put("strength", "VERY_STRONG");
        data.selection().put("outcome", "GENERAL_POLICY_SELECTED");reason(data, "PVS1_SPLICE_INFRAME_SPECIALIZED_STRENGTH_REQUIRED");
        data.selection().put("outcome", "SPECIALIZED_POLICY_SELECTED");assertNull(run(data));assertEquals("VERY_STRONG", audit(data).path("strength").asText());
    }
    @Test void missingReviewAndSourceConflictsRetainTheIndependentlyReconstructedEffect() {
        var data = data(1);data.fact().remove("inFrameReview");reason(data, "PVS1_SPLICE_INFRAME_OUTCOME_NOT_REVIEWED");
        assertEquals("MKFE*", audit(data).path("alteredProteinThroughTermination").asText());
        data = data(1);((ObjectNode) data.fact().at("/sourceRecords/0")).put("sha256", "c".repeat(64));
        reason(data, "PVS1_SPLICE_SOURCE_IDENTITY_CONFLICT");
        data = data(1);data.policy().withObject("spliceScope").remove("inFrameScope");
        assertNull(Pvs1SpliceInFrame.policyIssue(data.policy(), data.model()));reason(data, "PVS1_SPLICE_INFRAME_EFFECT_NOT_IMPLEMENTED");
    }
    @ParameterizedTest @ValueSource(strings = {"review", "event", "legacy", "exonOrdinal"})
    void missingFieldsKeepFailureAuditSerializableAndCanonicallyHashable(String missing) {
        var data = data(1);String expected;
        switch (missing) {
            case "review" -> { data.fact().remove("inFrameReview");expected = "PVS1_SPLICE_INFRAME_OUTCOME_NOT_REVIEWED"; }
            case "event" -> { data.fact().remove("event");expected = "PVS1_SPLICE_INFRAME_PROTEIN_EFFECT_UNRESOLVED"; }
            case "legacy" -> {
                data.fact().remove("inFrameReview");data.policy().withObject("spliceScope").remove("inFrameScope");
                expected = "PVS1_SPLICE_INFRAME_EFFECT_NOT_IMPLEMENTED";
            }
            case "exonOrdinal" -> { ((ObjectNode) data.model().at("/exons/1")).remove("ordinal");expected = "PVS1_SPLICE_INFRAME_EXON_ASSESSMENT_MISSING"; }
            default -> throw new AssertionError();
        }
        reason(data, expected);
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        String hash = assertDoesNotThrow(() -> adapter.canonicalSha256(data.input()));
        String serialized = assertDoesNotThrow(() -> mapper.writeValueAsString(data.input()));
        var restored = assertDoesNotThrow(() -> mapper.readTree(serialized));
        assertEquals(hash, adapter.canonicalSha256(restored));
        if (missing.equals("review") || missing.equals("legacy")) assertTrue(audit(data).path("inFrameReview").isNull());
        if (missing.equals("event")) assertTrue(audit(data).path("event").isNull());
        if (missing.equals("exonOrdinal")) assertTrue(audit(data).at("/exonChecks/0/ordinal").isNull());
    }
    @ParameterizedTest @ValueSource(strings = {"sequence", "start", "end", "length"})
    void upstreamSequenceFactsMustAgreeWithAnIndependentExactDeletion(String mutation) {
        var data = data(1);
        switch (mutation) {
            case "sequence" -> data.input().put("alteredCdsSequence", "ATGAAACCAGAATAA");
            case "start" -> data.input().put("referenceEditStart0", 5);
            case "end" -> data.input().put("referenceEditEnd0", 13);
            case "length" -> data.input().put("netLengthChange", -3);
            default -> throw new AssertionError();
        }
        reason(data, "PVS1_SPLICE_INFRAME_SEQUENCE_MISMATCH");assertEquals("MKFE*", audit(data).path("alteredProteinThroughTermination").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"tail", "source", "duplicateSource", "duplicateRule", "duplicateExon", "float", "overflow", "range", "denominator", "unknown"})
    void malformedOrUnsupportedOperatorsNeverGrantStrength(String mutation) {
        var data = data(1);var rule = (ObjectNode) scope(data).at("/rules/0");
        switch (mutation) {
            case "tail" -> rule.put("predicate", "PROTEIN_LOSS_FRACTION").put("metric", "REFERENCE_TAIL_ALTERED_OR_REMOVED");
            case "source" -> rule.putArray("sourceIds").add("missing");
            case "duplicateSource" -> rule.withArray("sourceIds").add("synthetic-function");
            case "duplicateRule" -> scope(data).withArray("rules").add(rule.deepCopy());
            case "duplicateExon" -> ((ObjectNode) scope(data).at("/exonAssessments/2")).put("ordinal", 2);
            case "float" -> rule.put("minimumAffectedResidues", new BigDecimal("1.5"));
            case "overflow" -> rule.put("end", 2147483648L);
            case "range" -> rule.put("minimumAffectedResidues", 3);
            case "denominator" -> { scope(data).putArray("rules");fraction(data, "GT", 1, 0); }
            case "unknown" -> scope(data).put("ignoreUnknown", true);
            default -> throw new AssertionError();
        }
        assertEquals("PVS1_SPLICE_INFRAME_POLICY_INVALID", Pvs1SpliceInFrame.policyIssue(data.policy(), data.model()));
        reason(data, "PVS1_SPLICE_INFRAME_POLICY_INVALID");
    }
}
