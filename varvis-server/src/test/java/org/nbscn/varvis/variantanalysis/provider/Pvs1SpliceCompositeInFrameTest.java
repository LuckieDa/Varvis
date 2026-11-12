package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independent toy oracles, not actual RNA observations or scientific adoption.
 * R=ATG AAA CCC GGG TTT GAA TAA=MKPGFE*. c7_9del+c13T>C=MKGLE*:
 * one net residue lost, only P3 wholly deleted, F5 replaced. c8del+c12_13del
 * gives MKPVE*: one net residue lost but zero whole reference codons deleted.
 */
class Pvs1SpliceCompositeInFrameTest {
    private static final String CDS = "ATGAAACCCGGGTTTGAATAA";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter hashes = new RuntimeEvidenceV2Adapter(mapper);
    private final Pvs1SpliceInFrameTest legacy = new Pvs1SpliceInFrameTest();
    record Op(String id, CodingTranscriptEdits.Origin origin, int start, int end, String insert) { }
    record Data(ObjectNode context, ObjectNode policy, ObjectNode model, ObjectNode fact, ObjectNode input,
                ObjectNode selection, List<Op> operations) { }
    record Bound(CodingConsequenceFacts.Facts facts, String altered) { }
    private Op rna(int start, int end) { return new Op("rna", CodingTranscriptEdits.Origin.SPLICE_PROJECTION, start, end, ""); }
    private Op coding(int start, int end, String insert) { return new Op("coding", CodingTranscriptEdits.Origin.CODING_ALLELE, start, end, insert); }

    private Data data(int strand, String cds, List<Op> operations) {
        var old = legacy.data(strand);var model = old.model();var policy = old.policy();var fact = old.fact();
        model.put("schemaVersion", "native-coding-transcript-model-v1").put("modelId", "TEST_ONLY_COMPOSITE")
                .put("modelVersion", "1").put("assembly", "GRCh38").put("contig", "1").put("hgncId", "HGNC:1")
                .put("geneSymbol", "SYNTHETIC").put("transcript", "NM_000001.1").put("protein", "NP_000001.1")
                .put("cdsSequence", cds).put("cdsLength", cds.length()).put("proteinLength", cds.length() / 3 - 1);
        ((ObjectNode) model.at("/exons/2")).put("cdsEnd", cds.length());
        for (var exon : model.path("exons")) {
            int ordinal = exon.path("ordinal").asInt(), start = strand == 1 ? ordinal * 100 : (4 - ordinal) * 100;
            ((ObjectNode) exon).put("genomicStart", start).put("genomicEnd", start + exon.path("cdsEnd").asInt() - exon.path("cdsStart").asInt());
        }
        model.set("sourceRecords", policy.path("scientificSources").deepCopy());
        var context = mapper.createObjectNode();context.putObject("gene").put("hgncId", "HGNC:1").put("symbol", "SYNTHETIC");
        context.putObject("transcript").put("accession", "NM_000001").put("version", 1);
        var allele = context.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1")
                .put("position", strand == 1 ? 300 : 108).put("reference", strand == 1 ? "T" : "A").put("alternate", strand == 1 ? "C" : "G");
        fact.put("transcript", "NM_000001.1").put("transcriptModelSha256", hashes.canonicalSha256(model));fact.set("allele", allele.deepCopy());
        var splice = operations.stream().filter(op -> op.origin() == CodingTranscriptEdits.Origin.SPLICE_PROJECTION).findFirst().orElseThrow();
        fact.withObject("event").put("type", "EXON_SHORTENING").put("deleteStartCds", splice.start() + 1).put("deleteEndCds", splice.end());
        fact.putObject("review").put("status", "REVIEWED_FOR_PVS1_SPLICE").put("reviewId", "TEST_ONLY_PARENT_REVIEW")
                .put("basis", "Synthetic complete outcome and rescue exclusion; no actual adoption")
                .put("maximumStrength", "STRONG").put("outcomeMultiplicity", "SINGLE_SUPPORTED_OUTCOME")
                .put("functionalRescue", "EXCLUDED_IN_REVIEWED_SCOPE").putArray("sourceIds").add("synthetic-rna");
        policy.put("appliedStrength", "MODERATE");
        var scope = policy.withObject("spliceScope").withObject("inFrameScope");scope.putArray("rules");legacy.critical(scope, "deleted-P3", 3, 3, 1, "STRONG");
        var d = new Data(context, policy, model, fact, mapper.createObjectNode(), old.selection(), operations);
        var beforeReview = derive(d);var identity = beforeReview.facts().mechanicalEffectSha256();
        fact.withObject("inFrameReview").putObject("compositeEffectAssessment")
                .put("schemaVersion", "pvs1-composite-inframe-effect-review-v1").put("status", "REVIEWED_FOR_ACTUAL_COMPOSITE_EFFECT")
                .put("mechanicalEffectSha256", identity).put("reviewId", "TEST_ONLY_WHOLE_EFFECT")
                .put("basis", "Synthetic reviewed actual combined effect, not a single deletion approximation")
                .putArray("sourceIds").add("synthetic-function");
        var bound = derive(d);assertEquals(identity, bound.facts().mechanicalEffectSha256());
        assertNotEquals(beforeReview.facts().canonicalSha256(), bound.facts().canonicalSha256());
        d.input().put("transcriptModelSha256", hashes.canonicalSha256(model)).put("alteredCdsSequence", bound.altered())
                .put("alteredCdsSha256", CodingTranscriptMaterialSupply.sha(bound.altered())).put("netLengthChange", bound.altered().length() - cds.length());
        return d;
    }
    private Data ordinary(int strand) { return data(strand, CDS, List.of(rna(6, 9), coding(12, 13, "C"))); }
    private Bound derive(Data d) {
        var r = CodingTranscriptEdits.bind(mapper, d.context(), d.model(), hashes.canonicalSha256(d.model()), CodingTranscriptMaterialSupply.sha(d.model().path("cdsSequence").asText()));
        var edits = new ArrayList<CodingTranscriptEdits.Edit>();String group = hashes.canonicalSha256(d.context().at("/variant/evidenceQueryAllele"));
        for (var op : d.operations()) {
            int owner = 0;
            if (op.start() == op.end()) for (var exon : d.model().path("exons"))
                if (op.start() >= exon.path("cdsStart").asInt() && op.start() <= exon.path("cdsEnd").asInt()) owner = exon.path("ordinal").asInt();
            edits.add(new CodingTranscriptEdits.Edit(op.id(), op.origin(), op.start(), op.end(), op.insert(),
                    r.cds().substring(op.start(), op.end()), op.origin() == CodingTranscriptEdits.Origin.SPLICE_PROJECTION ? hashes.canonicalSha256(d.fact()) : group, group, owner));
        }
        var result = CodingTranscriptEdits.apply(r, edits);return new Bound(CodingConsequenceFacts.derive(mapper, r, result), result.alteredCds());
    }
    private String run(Data d) { return run(d, derive(d).facts()); }
    private String run(Data d, CodingConsequenceFacts.Facts f) { return Pvs1SpliceInFrame.evaluateComposite(d.context(), d.policy(), d.model(), d.fact(), d.input(), d.selection(), f); }
    private JsonNode audit(Data d) { return d.input().path("inFrameAssessment"); }
    private ObjectNode scope(Data d) { return d.policy().withObject("spliceScope").withObject("inFrameScope"); }
    private void reason(Data d, String reason) {
        assertEquals(reason, run(d), d.input()::toPrettyString);assertEquals("NOT_EVALUATED", audit(d).path("status").asText());
        assertFalse(audit(d).has("selectedRule"));assertTrue(d.input().path("codingConsequenceFacts").isObject());
    }
    private void fraction(Data d, int numerator, int denominator) {
        scope(d).putArray("rules");var rule = scope(d).withArray("rules").addObject().put("ruleId", "net-loss")
                .put("predicate", "DELETED_PROTEIN_FRACTION").put("comparison", "GT").put("numerator", numerator).put("denominator", denominator)
                .put("strength", "MODERATE").put("basis", "TEST_ONLY actual net shortening ratio");rule.putArray("sourceIds").add("synthetic-function");
        region(d, 3, 5);
    }
    private void region(Data d, int start, int end) {
        d.fact().withObject("inFrameReview").putObject("criticalRegionAssessment").put("status", "REVIEWED_IN_DECLARED_SCOPE")
                .put("coverage", "COMPLETE_FOR_AFFECTED_PROTEIN_REGION").put("finding", "NO_KNOWN_CRITICAL_REGION_AFFECTED")
                .put("start", start).put("end", end).put("basis", "TEST_ONLY complete affected-region review")
                .putArray("sourceIds").add("synthetic-function");
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void realCompositionCountsOnlyDeletedP3AndDoesNotRequireRemoteSnvExonLofFacts(int strand) {
        var d = ordinary(strand);scope(d).withArray("exonAssessments").remove(2);
        assertNull(run(d), d.input()::toPrettyString);assertEquals("MKGLE*", d.input().at("/codingConsequenceFacts/alteredProteinThroughTermination").asText());
        assertEquals("MET", audit(d).path("status").asText());assertEquals("STRONG", audit(d).path("strength").asText());
        assertEquals(1, audit(d).path("fullyRemovedReferenceResidues").asInt());assertEquals(1, audit(d).at("/ruleChecks/0/affectedResiduesInRegion").asInt());
        assertEquals(1, audit(d).path("exonChecks").size());assertEquals(2, audit(d).at("/exonChecks/0/ordinal").asInt());
        assertEquals("MODERATE", d.policy().path("appliedStrength").asText(), "Parent caps remain applied by Consequence.completeModule");
        assertEquals("1.1.1", Pvs1SpliceInFrame.VERSION);assertEquals("1.0.0", audit(d).path("operatorVersion").asText());
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void synonymousF5TouchDoesNotContradictACompleteNoCriticalReview(int strand) {
        // Independent oracle: ATG AAA CCC GGG TTT GAA TAA minus c7_9 and c15T>C
        // becomes ATG AAA GGG TTC GAA TAA = MKGFE*. Only P3 is wholly deleted;
        // F5 remains F despite a replaced reference base. Net shortening is 1/6.
        var d = data(strand, CDS, List.of(rna(6, 9), coding(14, 15, "C")));
        var exon = d.model().at("/exons/2");
        int position = strand == 1 ? exon.path("genomicStart").asInt() + 2 : exon.path("genomicEnd").asInt() - 2;
        ((ObjectNode) d.context().at("/variant/evidenceQueryAllele")).put("position", position);
        d.fact().set("allele", d.context().at("/variant/evidenceQueryAllele").deepCopy());
        fraction(d, 1, 10);
        legacy.critical(scope(d), "F5-synonymous", 5, 5, 1, "STRONG");
        d.fact().withObject("inFrameReview").withObject("compositeEffectAssessment")
                .put("mechanicalEffectSha256", derive(d).facts().mechanicalEffectSha256());
        assertNull(run(d), d.input()::toPrettyString);
        assertEquals("MKGFE*", d.input().at("/codingConsequenceFacts/alteredProteinThroughTermination").asText());
        assertEquals("MET", audit(d).path("status").asText());
        assertEquals("MODERATE", audit(d).path("strength").asText());
        assertEquals("net-loss", audit(d).at("/selectedRule/ruleId").asText());
        assertEquals(1, audit(d).at("/ruleChecks/0/actualNumerator").asInt());
        assertEquals(6, audit(d).at("/ruleChecks/0/actualDenominator").asInt());
        assertEquals(0, audit(d).at("/ruleChecks/1/affectedResiduesInRegion").asInt());
        assertEquals(1, audit(d).at("/ruleChecks/1/touchedReferenceResiduesInRegion").asInt());
        assertEquals("NO_MATCH", audit(d).at("/ruleChecks/1/status").asText());
    }

    @Test void replacementCannotBecomeASecondWholeDeletedCriticalResidue() {
        var d = ordinary(1);scope(d).putArray("rules");legacy.critical(scope(d), "F5-replaced", 5, 5, 1, "STRONG");
        reason(d, "PVS1_SPLICE_INFRAME_JUNCTION_FUNCTION_UNRESOLVED");
        assertEquals(0, audit(d).at("/ruleChecks/0/affectedResiduesInRegion").asInt());assertEquals(1, audit(d).at("/ruleChecks/0/touchedReferenceResiduesInRegion").asInt());
    }
    @Test void compensatedFrameshiftHasNoWholeCodonDeletionButCanUseActualNetFraction() {
        var d = data(1, CDS, List.of(coding(7, 8, ""), rna(11, 13)));fraction(d, 1, 10);
        assertNull(run(d), d.input()::toPrettyString);assertEquals("MKPVE*", d.input().at("/codingConsequenceFacts/alteredProteinThroughTermination").asText());
        assertEquals(0, audit(d).path("fullyRemovedReferenceResidues").asInt());assertEquals(1, audit(d).at("/ruleChecks/0/actualNumerator").asInt());
        scope(d).putArray("rules");legacy.critical(scope(d), "not-wholly-deleted", 3, 5, 1, "STRONG");d.fact().withObject("inFrameReview").remove("criticalRegionAssessment");
        reason(d, "PVS1_SPLICE_INFRAME_JUNCTION_FUNCTION_UNRESOLVED");
    }
    @Test void netReducingDelinsAlsoRequiresItsOwnExactExonAssessment() {
        var d = data(1, CDS, List.of(rna(6, 7), coding(12, 15, "C")));fraction(d, 1, 10);
        assertEquals("MKPGE*", derive(d).facts().snapshot().path("alteredProteinThroughTermination").asText());
        scope(d).withArray("exonAssessments").remove(2);reason(d, "PVS1_SPLICE_INFRAME_EXON_ASSESSMENT_MISSING");
    }
    @Test void changedProteinWithZeroNetLossCannotUseTheSmallFractionRule() {
        var d = data(1, CDS, List.of(rna(6, 9), coding(12, 12, "CCC")));fraction(d, 1, 10);
        reason(d, "PVS1_SPLICE_COMPOSITE_INFRAME_NO_NET_PROTEIN_SHORTENING");
        assertEquals("MKGPFE*", d.input().at("/codingConsequenceFacts/alteredProteinThroughTermination").asText());
    }
    @Test void exactSameProteinDoesNotBecomeAFunctionalNegativeOrPositive() {
        var d = data(1, "ATGAAACCCCCCTAA", List.of(rna(6, 9), coding(12, 12, "CCC")));
        reason(d, "PVS1_SPLICE_COMPOSITE_INFRAME_PROTEIN_UNCHANGED");assertEquals("MKPP*", d.input().at("/codingConsequenceFacts/alteredProteinThroughTermination").asText());
    }
    @Test void aJunctionStopCannotEnterTheCompositeRegionalOrFractionBranch() {
        var d = data(1, "ATGAAATCTGGTCAAGAATAA", List.of(rna(7, 13)));fraction(d, 1, 10);
        reason(d, "PVS1_SPLICE_COMPOSITE_INFRAME_TERMINATION_REQUIRES_SEPARATE_BRANCH");
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "status", "source", "duplicateSource", "basis", "unknown"})
    void actualWholeEffectReviewIsMandatoryAndClosed(String fault) {
        var d = ordinary(1);var review = d.fact().withObject("inFrameReview");var effect = review.withObject("compositeEffectAssessment");
        switch (fault) {
            case "missing" -> review.remove("compositeEffectAssessment");case "status" -> effect.put("status", "UNREVIEWED");
            case "source" -> effect.putArray("sourceIds").add("absent");case "duplicateSource" -> effect.withArray("sourceIds").add("synthetic-function");
            case "basis" -> effect.put("basis", "");case "unknown" -> effect.put("clinicalApproval", true);default -> throw new AssertionError();
        }
        reason(d, "PVS1_SPLICE_COMPOSITE_INFRAME_EFFECT_REVIEW_REQUIRED");
    }
    @Test void wrongMechanicalHashCannotReuseAnOtherwiseTypedFullEffectReview() {
        var d = ordinary(1);d.fact().withObject("inFrameReview").withObject("compositeEffectAssessment").put("mechanicalEffectSha256", "f".repeat(64));
        reason(d, "PVS1_SPLICE_COMPOSITE_INFRAME_EFFECT_BINDING_MISMATCH");
    }
    @Test void aSecondRnaEventCannotBeHiddenBehindTheSingleReviewedEvent() {
        var d = data(1, CDS, List.of(rna(6, 9), new Op("rna-2", CodingTranscriptEdits.Origin.SPLICE_PROJECTION, 9, 12, ""), coding(12, 13, "C")));
        reason(d, "PVS1_SPLICE_COMPOSITE_INFRAME_FACTS_BINDING_MISMATCH");
    }
    @ParameterizedTest @ValueSource(strings = {"context", "model", "altered", "factSource"})
    void currentModelContextSequenceAndFullSourceFactAreIndependentlyBound(String fault) {
        var d = ordinary(1);var facts = derive(d).facts();
        switch (fault) {
            case "context" -> d.context().put("changedContext", true);case "model" -> d.model().put("modelVersion", "wrong");
            case "altered" -> d.input().put("alteredCdsSequence", CDS);case "factSource" -> ((ObjectNode) d.fact().at("/sourceRecords/0")).put("sha256", "f".repeat(64));
            default -> throw new AssertionError();
        }
        assertEquals("PVS1_SPLICE_COMPOSITE_INFRAME_FACTS_BINDING_MISMATCH", run(d, facts));assertNotEquals("MET", audit(d).path("status").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"parent", "rescue", "inFrame", "sourceConflict"})
    void originalQualificationCannotBeReplacedByTheNewEffectReview(String fault) {
        var d = ordinary(1);
        if ("parent".equals(fault)) d.fact().withObject("review").put("status", "UNREVIEWED");
        else if ("rescue".equals(fault)) d.fact().withObject("review").put("functionalRescue", "UNKNOWN");
        else if ("inFrame".equals(fault)) d.fact().withObject("inFrameReview").put("status", "UNREVIEWED");
        else ((ObjectNode) d.fact().at("/sourceRecords/0")).put("sha256", "f".repeat(64));
        reason(d, "parent".equals(fault) || "rescue".equals(fault) ? "PVS1_SPLICE_COMPOSITE_INFRAME_PARENT_REVIEW_REQUIRED"
                : "inFrame".equals(fault) ? "PVS1_SPLICE_INFRAME_OUTCOME_NOT_REVIEWED" : "PVS1_SPLICE_SOURCE_IDENTITY_CONFLICT");
    }
    @Test void fractionReviewMustCoverReplacementAndFrameEffectsAsWellAsTheDeletedCodon() {
        var d = ordinary(1);fraction(d, 1, 10);region(d, 3, 3);reason(d, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_ASSESSMENT_REQUIRED");
        region(d, 3, 5);assertNull(run(d), d.input()::toPrettyString);
    }
    @Test void exactTenPercentEqualityRemainsUnspecified() {
        var d = data(1, "ATGAAACCCGGGTTTGAA" + "AAA".repeat(4) + "TAA", List.of(rna(6, 9), coding(12, 13, "C")));fraction(d, 1, 10);
        reason(d, "PVS1_SPLICE_INFRAME_FRACTION_BOUNDARY_UNSPECIFIED");assertEquals(10, audit(d).at("/ruleChecks/0/leftProduct").asInt());assertEquals(10, audit(d).at("/ruleChecks/0/rightProduct").asInt());
    }
    @Test void criticalRoutingAndSourceContradictionRemainIndependentOfRuleOrder() {
        var d = ordinary(1);var critical = scope(d).withArray("rules").get(0).deepCopy();fraction(d, 1, 10);scope(d).withArray("rules").add(critical);
        reason(d, "PVS1_SPLICE_INFRAME_CRITICAL_REGION_FACTS_CONFLICT");d.fact().withObject("inFrameReview").remove("criticalRegionAssessment");
        assertNull(run(d), d.input()::toPrettyString);assertEquals("deleted-P3", audit(d).at("/selectedRule/ruleId").asText());
    }
    @Test void multipleCriticalMatchesAndGeneralVeryStrongNeverSelectAFavorableRule() {
        var d = ordinary(1);legacy.critical(scope(d), "duplicate-region", 3, 3, 1, "MODERATE");reason(d, "PVS1_SPLICE_INFRAME_RULES_AMBIGUOUS");
        scope(d).withArray("rules").remove(1);((ObjectNode) scope(d).at("/rules/0")).put("strength", "VERY_STRONG");d.selection().put("outcome", "GENERAL_POLICY_SELECTED");
        reason(d, "PVS1_SPLICE_INFRAME_SPECIALIZED_STRENGTH_REQUIRED");
    }
}
