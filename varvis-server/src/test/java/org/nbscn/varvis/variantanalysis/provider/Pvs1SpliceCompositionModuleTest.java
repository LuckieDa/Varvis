package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Source-bound synthetic module controls, separate from source qualification/business tests. */
class Pvs1SpliceCompositionModuleTest {
    final Pvs1SpliceConsequenceTest base = new Pvs1SpliceConsequenceTest();

    void composition(Pvs1SpliceConsequenceTest.Data data) {
        var d = data.input();var fact = data.material();
        fact.putObject("codingEditComposition").put("schemaVersion", Pvs1SpliceComposition.SCHEMA)
                .put("status", "REVIEWED_FOR_SAME_ALLELE_COMPOSITION").put("reviewId", "SYNTHETIC_COMBINATION_REVIEW")
                .put("basis", "Synthetic same physical allele and observed RNA outcome")
                .put("alleleGroupSha256", base.adapter.canonicalSha256(fact.path("allele")))
                .put("referenceCdsSha256", CodingTranscriptMaterialSupply.sha(d.model.path("cdsSequence").asText()))
                .put("spliceEventSha256", base.adapter.canonicalSha256(fact.path("event")))
                .put("sequenceScope", "REFERENCE_CDS_DELETION").putArray("sourceIds").add("synthetic-source");
        d.policy.withObject("scope").put("variantType", "NATIVE_NONPROJECTED_SMALL_SEQUENCE_VARIANT");
        d.policy.withObject("nmdScope").put("minimumCdsPosition", 1);
    }

    String module(Pvs1SpliceConsequenceTest.Data data, ObjectNode input) {
        var d = data.input();
        d.context.put("contextId", base.adapter.canonicalContextId(d.context));
        data.material().put("transcriptModelSha256", base.adapter.canonicalSha256(d.model));
        d.policy.putObject("transcriptModelBinding").put("modelId", d.model.path("modelId").asText())
                .put("modelVersion", d.model.path("modelVersion").asText()).put("canonicalSha256", base.adapter.canonicalSha256(d.model));
        d.selection.put("contextId", d.context.path("contextId").asText()).put("policyId", d.policy.path("policyId").asText())
                .put("policyVersion", d.policy.path("policyVersion").asText()).put("policySha256", base.adapter.canonicalSha256(d.policy));
        d.selection.remove("selectionId");d.selection.put("selectionId", "cspec-selection-sha256-" + base.adapter.canonicalSha256(d.selection));
        return Pvs1SpliceConsequence.resolveForIntegration(d.context, d.candidate.getTranscriptAnnotations().get(0), d.policy,
                d.model, data.material(), input, d.selection);
    }

    @Test void stopEntirelyInsideRetainedInsertionHasNoReferencePositionsAndCannotPassReferencePtcCondition() throws Exception {
        var data = base.data(1, false, false);
        base.moveAllele(data, 2002, "G", "GTGA", "splice_region_variant", ":c.3_4insTGA", 2002, "G");
        data.input().context.withObject("variant").put("variantType", "SMALL_INSERTION");
        composition(data);
        var input = base.mapper.createObjectNode();
        assertNull(module(data, input), input::toPrettyString);
        assertEquals("MET", input.at("/spliceAssessment/status").asText());
        assertEquals(4, input.path("ptcStart").asInt());assertEquals(6, input.path("ptcEnd").asInt());
        assertEquals("NO_REFERENCE_BASES", input.path("ptcReferenceMapping").asText());
        assertEquals(0, input.path("ptcReferenceStart").asInt());assertEquals(0, input.path("ptcReferenceEnd").asInt());
        assertEquals("INSERTED_OR_REPLACEMENT_BASES_HAVE_NO_REFERENCE_IDENTITY", input.path("ptcReferenceMappingReason").asText());
        assertEquals(440, input.path("lastJunctionCdsPosition").asInt());
        assertTrue(input.at("/spliceAssessment/requiresRuntimeAdapter").asBoolean());
        data.input().policy.withObject("nmdScope").putArray("positionConditions").addObject().put("conditionId", "reference-only")
                .put("coordinate", "REFERENCE_PTC_SPAN").put("minimumInclusive", 1)
                .put("basis", "Synthetic condition requires real reference coordinates").putArray("sourceIds").add("synthetic-source");
        assertEquals("PVS1_POLICY_POSITION_UNRESOLVED", module(data, input));
        assertNotEquals("MET", input.at("/spliceAssessment/status").asText());
    }

    @Test void replacementStopKeepsMixedMappingAndRequiresExplicitComplexPolicy() throws Exception {
        var data = base.data(1, false, false);
        base.moveAllele(data, 2003, "CC", "TA", "splice_region_variant", ":c.4_5delinsTA", 2003, "CC");
        data.input().context.withObject("variant").put("variantType", "MNV");
        composition(data);
        var input = base.mapper.createObjectNode();
        assertEquals("PVS1_NATIVE_SMALL_VARIANT_REQUIRED", module(data, input));
        data.input().policy.putObject("codingEditScope").put("status", "REVIEWED_FOR_COMPLEX_SMALL_ALLELES")
                .put("method", "CODING_TRANSCRIPT_EDITS").put("methodVersion", CodingTranscriptEdits.VERSION)
                .put("maximumAlleleLengthIncludingAnchor", 50).put("basis", "Synthetic bounded complex capability")
                .putArray("sourceIds").add("synthetic-source");
        assertNull(module(data, input), input::toPrettyString);
        assertEquals("MIXED_REFERENCE_AND_INSERTED_BASES", input.path("ptcReferenceMapping").asText());
        assertEquals(0, input.path("ptcReferenceStart").asInt());assertEquals(0, input.path("ptcReferenceMiddle").asInt());
        assertEquals(6, input.path("ptcReferenceEnd").asInt());
        assertEquals("VERY_STRONG", input.at("/spliceAssessment/adoptedStrength").asText());
    }

    @Test void retainedInFrameCodingEditCannotUseTheSingleRnaDeletionRegionalFormula() throws Exception {
        var data = base.data(1, false, false);
        base.moveAllele(data, 4002, "C", "A", "splice_region_variant", ":c.28C>A", 4002, "C");
        data.material().withObject("event").put("type", "EXON_SHORTENING").put("deleteEndCds", 24);
        composition(data);
        inFramePolicy(data);
        var input = base.mapper.createObjectNode();
        assertEquals("PVS1_SPLICE_COMPOSITE_INFRAME_EFFECT_REVIEW_REQUIRED", module(data, input));
        assertTrue(input.has("codingConsequenceFacts"));
        assertFalse(input.has("nmdPrediction"));
        assertNotEquals("MET", input.at("/spliceAssessment/status").asText());
    }

    private void inFramePolicy(Pvs1SpliceConsequenceTest.Data data) {
        var scope = data.input().policy.withObject("spliceScope").putObject("inFrameScope")
                .put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME").put("reviewId", "TEST_ONLY_COMPOSITE_POLICY")
                .put("basis", "Synthetic deletion region and full composite effect controls");
        scope.putArray("sourceIds").add("synthetic-source");
        var exons = scope.putArray("exonAssessments");
        for (int i = 1; i <= data.input().model.path("exons").size(); i++)
            exons.addObject().put("ordinal", i).put("biologicalRelevance", "PRESENT").put("populationLof", "NOT_FREQUENT")
                    .put("basis", "TEST_ONLY exact relevant exons").putArray("sourceIds").add("synthetic-source");
        scope.putArray("rules").addObject().put("ruleId", "TEST_ONLY_REMOVED_REGION")
                .put("predicate", "CRITICAL_REGION").put("start", 5).put("end", 8)
                .put("minimumAffectedResidues", 1).put("strength", "STRONG")
                .put("basis", "TEST_ONLY complete reference codon deletion").putArray("sourceIds").add("synthetic-source");
        data.material().putObject("inFrameReview").put("status", "REVIEWED_FOR_PVS1_SPLICE_INFRAME")
                .put("reviewId", "TEST_ONLY_FULL_EFFECT").put("basis", "Synthetic whole-effect qualification, not clinical approval")
                .putArray("sourceIds").add("synthetic-source");
    }

    @ParameterizedTest @ValueSource(ints = {1, -1})
    void compositeUsesActualFactsAndParentCapsOnBothStrands(int strand) throws Exception {
        var data = base.data(strand, false, false);
        var codingExon = data.input().model.path("exons").get(2);
        int offset = 28 - codingExon.path("cdsStart").asInt();
        int genomic = strand == 1 ? codingExon.path("genomicStart").asInt() + offset
                : codingExon.path("genomicEnd").asInt() - offset;
        base.moveAllele(data, genomic, strand == 1 ? "C" : "G", strand == 1 ? "A" : "T",
                "splice_region_variant", ":c.28C>A", genomic, strand == 1 ? "C" : "G");
        data.material().withObject("event").put("type", "EXON_SHORTENING").put("deleteEndCds", 24);
        composition(data); inFramePolicy(data);
        var before = base.mapper.createObjectNode();
        assertEquals("PVS1_SPLICE_COMPOSITE_INFRAME_EFFECT_REVIEW_REQUIRED", module(data, before));
        String effect = before.at("/spliceAssessment/mechanicalEffectSha256").asText();
        assertTrue(effect.matches("[0-9a-f]{64}"));
        var review = data.material().withObject("inFrameReview").putObject("compositeEffectAssessment")
                .put("schemaVersion", "pvs1-composite-inframe-effect-review-v1")
                .put("status", "REVIEWED_FOR_ACTUAL_COMPOSITE_EFFECT").put("mechanicalEffectSha256", effect)
                .put("reviewId", "TEST_ONLY_EFFECT_BINDING").put("basis", "Synthetic complete mechanical effect reviewed separately from sequence derivation");
        review.putArray("sourceIds").add("synthetic-source");
        data.input().policy.put("appliedStrength", "SUPPORTING");
        data.material().withObject("review").put("maximumStrength", "MODERATE");
        var input = base.mapper.createObjectNode();
        assertNull(module(data, input), input::toPrettyString);
        assertEquals("STRONG", input.at("/inFrameAssessment/strength").asText());
        assertEquals("SUPPORTING", input.at("/spliceAssessment/adoptedStrength").asText());
        assertEquals(effect, input.at("/spliceAssessment/mechanicalEffectSha256").asText(), "Review hash must not create a self-reference cycle");
        assertNotEquals(before.at("/spliceAssessment/codingConsequenceFactsSha256"), input.at("/spliceAssessment/codingConsequenceFactsSha256"),
                "Full fact provenance still binds the actual whole material, including its review");
        assertFalse(input.has("nmdPrediction"));
        var runtime = base.run(data);
        assertEquals("MET", base.status(runtime).path("status").asText(), runtime::toPrettyString);
        assertEquals(TruncatingNmdPvs1Runtime.COMPOSITE_SPLICE_VERSION,
                runtime.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
        assertEquals("SUPPORTING", runtime.at("/evaluation/appliedStrength").asText());
        String output = System.getProperty("varvis.t07.composite.artifacts");
        if (output != null) {
            var directory = java.nio.file.Path.of(output).resolve(strand == 1 ? "plus" : "minus");
            java.nio.file.Files.createDirectories(directory);
            var sample = base.mapper.createObjectNode().put("schemaVersion", "t07-composite-engineering-sample-v1")
                    .put("layer", "ACTUAL_DIRECT_PROVIDER_WITH_SYNTHETIC_SOURCE_DECLARATIONS")
                    .put("normalSelectedBusinessVerified", false).put("scientificAdoptionGranted", false);
            sample.set("context", data.input().context.deepCopy());sample.set("policy", data.input().policy.deepCopy());
            sample.set("model", data.input().model.deepCopy());sample.set("selection", data.input().selection.deepCopy());
            sample.set("sourceOutcome", data.material().deepCopy());sample.set("moduleInput", input.deepCopy());
            sample.set("runtimeResult", runtime.deepCopy());
            java.nio.file.Files.write(directory.resolve("provider-sample.json"), base.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(sample),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        }
        review.put("mechanicalEffectSha256", "0".repeat(64));
        assertEquals("PVS1_SPLICE_COMPOSITE_INFRAME_EFFECT_BINDING_MISMATCH", module(data, base.mapper.createObjectNode()));
    }
}
