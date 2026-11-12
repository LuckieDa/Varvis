package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

/** Fixed toy RNA edits; neither patient data nor scientific approval. */
public class Pvs1SpliceConsequenceTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final TruncatingNmdPvs1RuntimeTest old = new TruncatingNmdPvs1RuntimeTest();

    public static ObjectNode configure(Pvs1NmdBusinessFixture f, int i, boolean escape, boolean predicted) {
        var m = f.models.get(i); var p = f.policies.get(i); var a = f.annotations.get(i);
        String cds = "ATG" + "CCA".repeat(19) + "CTAACC" + "CCA".repeat(177) + "TAA";
        m.put("cdsSequence", cds).put("cdsLength", 600).put("proteinLength", 199)
                .put("lastJunctionCdsPosition", escape ? 76 : 450);
        var exons = m.putArray("exons"); int start = 1; int[] ends = {12, 25, escape ? 76 : 450, 600};
        for (int j = 0; j < 4; j++) {
            int g = i == 0 ? 2000 + j * 1000 : 20000 - j * 1000;
            exons.addObject().put("ordinal", j + 1).put("cdsStart", start).put("cdsEnd", ends[j])
                    .put("genomicStart", g).put("genomicEnd", g + ends[j] - start); start = ends[j] + 1;
        }
        var s = Pvs1NmdEscapeTest.scope(p, 4); Pvs1NmdEscapeTest.critical(s, 5, 190, 1, "STRONG");
        p.put("schemaVersion", "pvs1-truncating-nmd-policy-v4");
        p.withObject("nmdScope").put("longExonScope", "REVIEWED_WITHOUT_LENGTH_LIMIT");
        var scope = p.putObject("spliceScope").put("status", "REVIEWED_FOR_PVS1_SPLICE")
                .put("reviewId", "TEST_ONLY_SPLICE_POLICY").put("basis", "Toy data only, not scientific approval")
                .put("maximumStrength", "VERY_STRONG");
        scope.putArray("sourceIds").add("synthetic-source");
        scope.putArray("evidenceKinds").add("OBSERVED_RNA").add("PREDICTED_CANONICAL");
        scope.putArray("eventTypes").add("EXON_SKIPPING").add("EXON_SHORTENING");
        var key = f.alleles.get(i);
        int g = i == 0 ? 3013 : 18999;
        key = new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey("GRCh38", key.getChromosome(), g, i == 0 ? "G" : "C", i == 0 ? "A" : "T");
        f.alleles.set(i, key);
        a.put("consequence", "splice_donor_variant").put("hgvsCoding", m.path("transcript").asText() + ":c.25+1G>A").put("intron", "2/3");
        a.remove(List.of("hgvsProtein", "exon", "proteinPosition", "aminoAcids", "codons"));
        var event = p.objectNode().put("schemaVersion", "pvs1-splice-outcome-v1").put("outcomeId", "TEST_ONLY_SPLICE_" + i)
                .put("evidenceKind", predicted ? "PREDICTED_CANONICAL" : "OBSERVED_RNA")
                .put("transcript", m.path("transcript").asText()).put("transcriptModelSha256", f.canonical(m))
                .put("hgncId", m.path("hgncId").asText()).put("mondoId", p.at("/scope/mondoId").asText()).put("moi", p.at("/scope/moi").asText());
        event.putObject("allele").put("assembly", "GRCh38").put("contig", m.path("contig").asText()).put("position", g)
                .put("reference", key.getReference()).put("alternate", key.getAlternate());
        event.putObject("review").put("status", "REVIEWED_FOR_PVS1_SPLICE").put("reviewId", "TEST_ONLY_OUTCOME_REVIEW")
                .put("basis", "Fictional single outcome and complete assay interpretation")
                .put("maximumStrength", "VERY_STRONG").put("functionalRescue", "EXCLUDED_IN_REVIEWED_SCOPE")
                .put("outcomeMultiplicity", "SINGLE_SUPPORTED_OUTCOME").put("residualNormalTranscript", predicted ? "NOT_MEASURED" : "NOT_DETECTED_IN_ASSESSED_SCOPE");
        event.set("sourceRecords", p.path("scientificSources").deepCopy());
        event.withObject("review").putArray("sourceIds").add("synthetic-source");
        event.putObject("event").put("type", "EXON_SKIPPING").put("exonOrdinal", 2).put("deleteStartCds", 13).put("deleteEndCds", 25);
        event.putObject("referenceWindow").put("start", i == 0 ? 3013 : 18998).put("sequence", i == 0 ? "GT" : "AC");
        event.putObject("assessment").put("status", predicted ? "QUALIFIED_CANONICAL_PREDICTION" : "QUALIFIED_RNA_ASSAY")
                .put("basis", "TEST_ONLY assay or prediction assessment, full source retained")
                .put("tissueRelevance", "ASSESSED").put("alleleAttribution", "ASSESSED").put("assayQuality", "ASSESSED")
                .put("nearbyRescueAssessment", "ASSESSED").put("alternativeOutcomesAssessment", "ASSESSED");
        event.withObject("assessment").putArray("sourceIds").add("synthetic-source");
        var expected = f.expected.get(i);
        expected.put("kind", "SPLICE").put("ptcStart", 49).put("ptcEnd", 51).put("lastJunction", escape ? 63 : 437)
                .put("distanceToLastJunction", escape ? 12 : 386).put("strength", escape ? "STRONG" : "VERY_STRONG");
        return event;
    }

    record Data(TruncatingNmdPvs1RuntimeTest.Inputs input, ObjectNode material) { }
    Data data(int strand, boolean escape, boolean predicted) throws Exception {
        var f = new Pvs1NmdBusinessFixture(mapper, false); int i = strand == 1 ? 0 : 1;
        var fact = configure(f, i, escape, predicted);
        var d = old.nonsense(i + 1, strand, 31);
        d.model = f.models.get(i); d.policy = f.policies.get(i);
        d.candidate = RuntimeEvidenceV2ProviderWiringTest.variant(f.alleles.get(i));
        d.candidate.applyTranscriptAnnotations(List.of(f.annotation(i)));
        String tx = d.model.path("transcript").asText();
        d.context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, f.alleles.get(i), tx.substring(0, tx.lastIndexOf('.')));
        d.context.withObject("gene").put("hgncId", d.model.path("hgncId").asText()).put("symbol", d.model.path("geneSymbol").asText());
        d.context.withObject("disease").put("mondoId", d.policy.at("/scope/mondoId").asText());
        d.context.withObject("transcript").put("version", 1).put("annotationSourceVersion", "116.0");
        d.context.put("moi", "AUTOSOMAL_DOMINANT");
        d.context.put("contextId", adapter.canonicalContextId(d.context));
        return new Data(d, fact);
    }
    ObjectNode run(Data data) {
        var d = data.input();
        d.policy.putObject("transcriptModelBinding").put("modelId", d.model.path("modelId").asText()).put("modelVersion", d.model.path("modelVersion").asText())
                .put("canonicalSha256", adapter.canonicalSha256(d.model));
        d.selection.put("contextId", d.context.path("contextId").asText()).put("policyId", d.policy.path("policyId").asText()).put("policyVersion", d.policy.path("policyVersion").asText())
                .put("policySha256", adapter.canonicalSha256(d.policy));
        d.selection.remove("selectionId");d.selection.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(d.selection));
        return new TruncatingNmdPvs1Runtime(mapper, old.resourceProfile(), d.model)
                .evaluate(d.context, d.candidate, d.policy, d.selection, mapper.createArrayNode().add(data.material()));
    }
    JsonNode status(ObjectNode r) { return r.has("evaluation") ? r.at("/evaluation/evaluationResult") : r.path("evaluationResult"); }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void rnaExonSkippingDerivesNmdOnBothStrands(int strand) throws Exception {
        var d = data(strand, false, false);var r = run(d);
        assertEquals("MET", status(r).path("status").asText(), r.toPrettyString());
        assertEquals("VERY_STRONG", r.at("/evaluation/appliedStrength").asText());
        assertEquals(49, r.at("/resolvedInput/ptcStart").asInt());assertEquals(51, r.at("/resolvedInput/ptcEnd").asInt());
        assertEquals(437, r.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
        assertEquals(386, r.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals(d.material(), r.path("spliceConsequenceMaterial"));
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void canonicalPredictionUsesActualBoundaryAndDoesNotClaimRna(int strand) throws Exception {
        var r = run(data(strand, false, true));assertEquals("MET", status(r).path("status").asText(), r.toPrettyString());
        assertEquals("PREDICTED_CANONICAL", r.at("/resolvedInput/spliceAssessment/evidenceKind").asText());
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void unresolvedPredictionRetainsExonEffectAndStrandAwareCandidateMotifs(int strand) throws Exception {
        var d = data(strand, false, true);
        var i = d.input();
        int position = i.context.at("/variant/evidenceQueryAllele/position").asInt();
        String oriented = "C".repeat(14) + "GT" + "CCCC" + "GT" + "C".repeat(7) + "GC" + "C".repeat(11);
        String window = CodingTranscriptMapping.orientAllele(i.model, oriented);
        int start = strand == 1 ? position - 20 : position - (window.length() - 21);
        String ref = strand == 1 ? "G" : "C", alt = strand == 1 ? "A" : "T";
        moveAllele(d, position, ref, alt, "splice_donor_variant", ":c.25+1G>A", start, window);
        d.material().withObject("review").put("outcomeMultiplicity", "UNRESOLVED").put("functionalRescue", "UNKNOWN");
        String reason = "PVS1_SPLICE_OUTCOME_UNRESOLVED";
        var detail = Pvs1UnresolvedAssessment.splice(mapper, adapter, i.context, i.candidate, i.model, d.material(), reason);
        assertNotNull(detail);
        assertEquals("ALTERED", detail.at("/candidateProduct/readingFrame").asText());
        assertEquals(13, detail.at("/candidateProduct/deletedCodingBases").asInt());
        assertEquals(mapper.createArrayNode().add("SPLICE_PRODUCT_DISTINCTION").add("SPLICE_FUNCTIONAL_RESCUE")
                .add("RESIDUAL_NORMAL_TRANSCRIPT"), detail.path("missingFacts"));
        assertEquals(2, detail.path("nearbyInFrameSites").size());
        assertEquals(-6, detail.at("/nearbyInFrameSites/0/offsetNt").asInt());
        assertEquals(9, detail.at("/nearbyInFrameSites/1/offsetNt").asInt());
        assertEquals("SEQUENCE_CANDIDATE", detail.at("/nearbyInFrameSites/1/status").asText());
        var result = mapper.createObjectNode();
        result.set("unresolvedAssessment", detail);
        result.set("spliceConsequenceMaterial", d.material());
        var summary = Pvs1SelectedBatch.publicSummary(mapper, result);
        assertEquals(detail, summary.path("unresolvedAssessment"));
        assertFalse(summary.toString().contains(window));
        moveAllele(d, position, ref, alt, "splice_region_variant", ":c.25+1G>A", start, window);
        assertNull(Pvs1UnresolvedAssessment.splice(mapper, adapter, i.context, i.candidate, i.model, d.material(), reason));
        moveAllele(d, position, ref, alt, null, ":c.25+1G>A", start, window);
        assertNull(Pvs1UnresolvedAssessment.splice(mapper, adapter, i.context, i.candidate, i.model, d.material(), reason));
    }

    void moveAllele(Data d, int position, String ref, String alt, String term, String hgvsc, int windowStart, String window) throws Exception {
        var i = d.input();var allele = (ObjectNode) i.context.at("/variant/evidenceQueryAllele");
        allele.put("position", position).put("reference", ref).put("alternate", alt);
        d.material().set("allele", allele.deepCopy());
        d.material().withObject("referenceWindow").put("start", windowStart).put("sequence", window);
        ObjectNode annotation = mapper.valueToTree(i.candidate.getTranscriptAnnotations().get(0));
        annotation.retain("geneSymbol", "transcriptAccession", "hgvsCoding", "consequence", "intron", "source", "status", "version", "timestamp", "maneSelect");
        annotation.put("consequence", term).put("hgvsCoding", i.model.path("transcript").asText() + hgvsc);
        i.candidate = RuntimeEvidenceV2ProviderWiringTest.variant(new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey(
                "GRCh38", allele.path("contig").asText(), position, ref, alt));
        i.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        var identity = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter,
                new org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey("GRCh38", allele.path("contig").asText(), position, ref, alt));
        i.context.set("variant", identity.path("variant"));
        i.context.put("contextId", adapter.canonicalContextId(i.context));
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void acceptorPredictionUsesStrandAwareAgAndMinusCoordinate(int strand) throws Exception {
        var d = data(strand, false, true);
        moveAllele(d, strand == 1 ? 2999 : 19013, strand == 1 ? "G" : "C", strand == 1 ? "A" : "T",
                "splice_acceptor_variant", ":c.13-1G>A", strand == 1 ? 2998 : 19013, strand == 1 ? "AG" : "CT");
        var r = run(d);assertEquals("MET", status(r).path("status").asText(), r.toPrettyString());
        assertEquals(437, r.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
    }
    @Test void skippingPenultimateExonRemovesOriginalLastJunction() throws Exception {
        var d = data(1, false, true);var m = d.input().model;
        ((ObjectNode) m.path("exons").get(2)).put("cdsEnd", 38).put("genomicEnd", 4012);
        ((ObjectNode) m.path("exons").get(3)).put("cdsStart", 39).put("genomicEnd", 5561);
        m.put("lastJunctionCdsPosition", 38);
        d.material().put("transcriptModelSha256", adapter.canonicalSha256(m));
        d.material().withObject("event").put("exonOrdinal", 3).put("deleteStartCds", 26).put("deleteEndCds", 38);
        moveAllele(d, 4013, "G", "A", "splice_donor_variant", ":c.38+1G>A", 4013, "GT");
        var r = run(d);assertEquals("MET", status(r).path("status").asText(), r.toPrettyString());
        assertEquals(25, r.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
        assertEquals(-26, r.at("/resolvedInput/distanceToLastJunction").asInt());
        assertEquals("STRONG", r.at("/evaluation/appliedStrength").asText());
    }
    @Test void retainedCodingChangeCannotBeSilentlyDiscarded() throws Exception {
        var d = data(1, false, false);
        moveAllele(d, 5000, "C", "T", "splice_region_variant", ":c.451C>T", 5000, "C");
        var r = run(d);assertEquals("NOT_EVALUATED", status(r).path("status").asText());
        assertEquals("PVS1_SPLICE_RETAINED_CODING_ALLELE_NOT_SUPPORTED", status(r).path("reasonCode").asText());
    }
    @Test void rnaTruncationReusesEscapePolicyAndCapsStrength() throws Exception {
        var d = data(1, true, false);d.material().withObject("review").put("maximumStrength", "MODERATE");
        var r = run(d);assertEquals("MET", status(r).path("status").asText(), r.toPrettyString());
        assertEquals("MODERATE", r.at("/evaluation/appliedStrength").asText());
        assertEquals(12, r.at("/resolvedInput/distanceToLastJunction").asInt());
    }
    @ParameterizedTest @ValueSource(strings = {"review", "rescue", "multiple", "residual", "quality", "source", "model", "transcript",
            "disease", "kind", "operator", "site", "frame", "terminal", "ref", "motif", "scope", "cap", "condition", "event-span", "legacy"})
    void rejectsMissingConflictingOrUnsupportedFacts(String mutation) throws Exception {
        var d = data(1, false, false);var f = d.material();var p = d.input().policy;String expected;
        switch (mutation) {
            case "review" -> { f.withObject("review").put("status", "UNREVIEWED");expected = "PVS1_SPLICE_OUTCOME_NOT_REVIEWED"; }
            case "rescue" -> { f.withObject("review").put("functionalRescue", "UNKNOWN");expected = "PVS1_SPLICE_FUNCTIONAL_RESCUE_UNRESOLVED"; }
            case "multiple" -> { f.withObject("review").put("outcomeMultiplicity", "MULTIPLE");expected = "PVS1_SPLICE_OUTCOME_UNRESOLVED"; }
            case "residual" -> { f.withObject("review").put("residualNormalTranscript", "PRESENT");expected = "PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED"; }
            case "quality" -> { f.withObject("assessment").put("assayQuality", "UNKNOWN");expected = "PVS1_SPLICE_EVIDENCE_QUALIFICATION_REQUIRED"; }
            case "source" -> { ((ObjectNode) f.path("sourceRecords").get(0)).put("sha256", "b".repeat(64));expected = "PVS1_SPLICE_SOURCE_IDENTITY_CONFLICT"; }
            case "model" -> { f.put("transcriptModelSha256", "0".repeat(64));expected = "PVS1_SPLICE_MODEL_IDENTITY_MISMATCH"; }
            case "transcript" -> { f.put("transcript", "NM_999001.2");expected = "PVS1_SPLICE_OUTCOME_MATERIAL_REQUIRED"; }
            case "disease" -> { f.put("mondoId", "MONDO:0000002");expected = "PVS1_SPLICE_OUTCOME_MATERIAL_REQUIRED"; }
            case "kind" -> { f.put("evidenceKind", "SPLICEAI_SCORE_ONLY");expected = "PVS1_SPLICE_EVIDENCE_KIND_NOT_SUPPORTED"; }
            case "operator" -> { f.withObject("event").put("ignoreUnknown", true);expected = "PVS1_SPLICE_EVENT_NOT_SUPPORTED"; }
            case "site" -> { f.withObject("event").put("deleteStartCds", 14);expected = "PVS1_SPLICE_EVENT_MODEL_MISMATCH"; }
            case "frame" -> { f.withObject("event").put("type", "EXON_SHORTENING").put("deleteEndCds", 24);expected = "PVS1_SPLICE_INFRAME_EFFECT_NOT_IMPLEMENTED"; }
            case "terminal" -> { f.withObject("event").put("exonOrdinal", 4);expected = "PVS1_SPLICE_EVENT_INVALID"; }
            case "ref" -> { f.withObject("referenceWindow").put("sequence", "CT");expected = "PVS1_SPLICE_REFERENCE_MISMATCH"; }
            case "motif" -> { f.put("evidenceKind", "PREDICTED_CANONICAL");f.withObject("assessment").put("status", "QUALIFIED_CANONICAL_PREDICTION");f.withObject("referenceWindow").put("sequence", "GC");expected = "PVS1_SPLICE_NONCANONICAL_MOTIF_NOT_SUPPORTED"; }
            case "scope" -> { p.withObject("spliceScope").put("status", "UNREVIEWED");expected = "PVS1_SPLICE_SCOPE_NOT_REVIEWED"; }
            case "cap" -> { f.withObject("review").put("maximumStrength", "ARBITRARY");expected = "PVS1_SPLICE_OUTCOME_NOT_REVIEWED"; }
            case "condition" -> { p.withObject("nmdScope").putArray("positionConditions").addObject().put("conditionId", "c")
                    .put("coordinate", "REFERENCE_PTC_SPAN").put("maximumExclusive", 62).put("basis", "TEST_ONLY exact source PTC").putArray("sourceIds").add("synthetic-source");expected = "PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE"; }
            case "event-span" -> { p.withObject("nmdScope").putArray("positionConditions").addObject().put("conditionId", "c")
                    .put("coordinate", "REFERENCE_EVENT_SPAN").put("maximumExclusive", 25).put("basis", "TEST_ONLY entire deleted span").putArray("sourceIds").add("synthetic-source");expected = "PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE"; }
            case "legacy" -> { p.put("schemaVersion", "pvs1-truncating-nmd-policy-v3").remove("spliceScope");expected = "PVS1_SPLICE_BRANCH_NOT_IMPLEMENTED"; }
            default -> throw new AssertionError();
        }
        var result = run(d);assertEquals("NOT_EVALUATED", status(result).path("status").asText(), result.toPrettyString());
        assertEquals(expected, status(result).path("reasonCode").asText(), result.toPrettyString());
    }
    @Test void shortenedDonorExonUsesSameSequenceMechanics() throws Exception {
        var d = data(1, false, true);d.material().withObject("event").put("type", "EXON_SHORTENING").put("deleteStartCds", 25);
        var r = run(d);assertEquals("MET", status(r).path("status").asText(), r.toPrettyString());
        assertEquals(61, r.at("/resolvedInput/ptcStart").asInt());assertEquals(449, r.at("/resolvedInput/mutantLastJunctionCdsPosition").asInt());
    }
    @Test void ambiguousIdenticalOutcomesAreNotSilentlyPicked() throws Exception {
        var d = data(1, false, false);run(d);var i = d.input();
        var r = new TruncatingNmdPvs1Runtime(mapper, old.resourceProfile(), i.model).evaluate(i.context, i.candidate, i.policy, i.selection,
                mapper.createArrayNode().add(d.material()).add(d.material().deepCopy()));
        assertEquals("PVS1_SPLICE_OUTCOMES_AMBIGUOUS", status(r).path("reasonCode").asText());
    }
    @Test void missingMaterialNeverUsesPredictedAnnotationAsObservedOutcome() throws Exception {
        var d = data(1, false, false);run(d);var i = d.input();
        var r = new TruncatingNmdPvs1Runtime(mapper, old.resourceProfile(), i.model).evaluate(i.context, i.candidate, i.policy, i.selection);
        assertEquals("PVS1_SPLICE_OUTCOME_MATERIAL_REQUIRED", status(r).path("reasonCode").asText());
    }
    @Test void absentAnnotationLabelDoesNotDiscardQualifiedRna() throws Exception {
        var d = data(1, false, false);var i = d.input();
        ObjectNode a = mapper.valueToTree(i.candidate.getTranscriptAnnotations().get(0));
        a.retain("geneSymbol", "transcriptAccession", "hgvsCoding", "consequence", "intron", "source", "status", "version", "timestamp", "maneSelect");
        a.put("consequence", "intron_variant");
        i.candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(a, TranscriptConsequence.class)));
        assertEquals("MET", status(run(d)).path("status").asText());
    }
    @Test void strictNmdThresholdCannotInventEscape() throws Exception {
        var d = data(1, false, false);d.input().policy.withObject("nmdScope").put("minimumDistanceToLastJunction", 400);
        assertEquals("PVS1_ESCAPE_NMD_STATUS_UNRESOLVED", status(run(d)).path("reasonCode").asText());
    }
    @Test void legacyNonsenseOutputUnaffectedByUnrelatedSpliceMaterial() throws Exception {
        var i = old.nonsense(1, 1, 199);var runtime = new TruncatingNmdPvs1Runtime(mapper, old.resourceProfile(), i.model);
        var before = runtime.evaluate(i.context, i.candidate, i.policy, i.selection);
        var after = runtime.evaluate(i.context, i.candidate, i.policy, i.selection, mapper.createArrayNode().add(data(1, false, false).material()));
        assertEquals(before, after);
    }
}
