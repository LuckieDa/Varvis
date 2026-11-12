package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic source and observations; no new disease policy or score-resource acceptance. */
class PredictionConsequenceScopeTest {
    private final PredictionClauseRuntimeTest base = new PredictionClauseRuntimeTest();

    @ParameterizedTest @ValueSource(strings = {"intron_variant", "intron_variant&splice_region_variant",
            "splice_region_variant&intron_variant", "synonymous_variant", "synonymous_variant&splice_region_variant"})
    void exactReviewedClassesAndOptionalModifierRemainEligible(String consequence) throws Exception {
        var f = base.splice("0.01", 3);
        mutate(f, consequence, consequence.contains("synonymous") ? "c.100A>G" : "c.100+3A>G");
        ObjectNode result = base.run(f, policy(f, true), null);
        base.assertStatus("MET", result);
        assertEquals("ELIGIBLE", result.at("/consequenceScopeValidation/status").asText());
        assertEquals("1.0.4", result.at("/evaluation/deterministicTrace/evaluatorVersion").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"splice_region_variant", "synonymous_variant&intron_variant",
            "intron_variant&stop_gained", "synonymous_variant&stop_lost", "synonymous_variant&missense_variant",
            "intron_variant&frameshift_variant", "synonymous_variant&start_lost", "intron_variant&splice_donor_variant",
            "intron_variant&splice_acceptor_variant", "intron_variant&unknown_variant", "synonymous_variant&&splice_region_variant",
            "synonymous_variant&synonymous_variant", "splice_region_variant|intron_variant",
            "synonymous_variant,splice_region_variant", ""})
    void lowScoreCannotOverrideAnyOtherOrMalformedConsequence(String consequence) throws Exception {
        var f = base.splice("0.00", 7); mutate(f, consequence, "c.100+7A>G");
        ObjectNode result = base.run(f, policy(f, true), null);
        base.assertRefused(result); assertFalse(result.has("evidence"));
        assertFalse(result.has("validatedScore"));
    }

    @ParameterizedTest @CsvSource({"-3,MET", "3,MET", "-21,MET", "7,MET", "-2,NOT_EVALUATED",
            "-1,NOT_EVALUATED", "1,NOT_EVALUATED", "2,NOT_EVALUATED", "0,NOT_EVALUATED"})
    void canonicalDinucleotideBoundaryUsesExactVariantOffset(int offset, String expected) throws Exception {
        var f = base.splice("0.01", offset);
        ObjectNode result = base.run(f, policy(f, true), null);
        if ("MET".equals(expected)) {
            base.assertStatus(expected, result);
            assertEquals(offset, result.at("/consequenceScopeValidation/intronicSignedOffset").asInt());
            assertEquals("EXACT_SELECTED_VEP_HGVSC", result.at("/consequenceScopeValidation/positionSource").asText());
        } else base.assertRefused(result);
        // All original SpliceAI DP values are zero in this fixture; they never replace the variant offset.
    }

    @ParameterizedTest @ValueSource(strings = {"c.100_101del", "c.100+2147483648A>G", "c.100+3AA>GG",
            "c.100+3A>A", "c.100+0A>G", "c.100+3N>G", "c.100A>G"})
    void missingComplexOverflowOrContradictoryHgvsCannotProveIntronicEligibility(String hgvs) throws Exception {
        var f = base.splice("0.01", 7); mutate(f, "intron_variant", hgvs);
        ObjectNode result = base.run(f, policy(f, true), null);
        base.assertRefused(result);
        assertEquals("PREDICTION_INTRONIC_POSITION_MISSING_OR_INVALID", result.at("/evaluationResult/reasonCode").asText());
    }

    @Test void synonymousCannotBorrowIntronicHgvsAndOldPolicyKeepsItsDomain() throws Exception {
        var f = base.splice("0.01", 7); mutate(f, "synonymous_variant", "c.100+7A>G");
        ObjectNode result = base.run(f, policy(f, false), null);
        base.assertRefused(result);
        assertEquals("PREDICTION_CONSEQUENCE_POSITION_CLASS_MISMATCH", result.at("/evaluationResult/reasonCode").asText());
        mutate(f, "splice_region_variant&stop_gained", "c.100A>G");
        ObjectNode old = base.policy(f, "BP4", "SPLICEAI", "LE", "0.1");
        base.assertStatus("MET", base.run(f, old, null));
        assertFalse(base.run(f, old, null).has("consequenceScopeValidation"));
        base.assertRefused(base.run(f, policy(f, false), null));
    }

    @Test void newFieldsRequireExactSupportedModeBoundSourcesAndCorrectClause() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode p = policy(f, true);
        ((ObjectNode) p.path("consequenceScope")).put("conservationRequired", true);
        assertPolicyRefused(f, p, "PREDICTION_CONSEQUENCE_SCOPE_UNSUPPORTED");
        p = policy(f, true); ((ObjectNode) p.path("consequenceScope")).put("mode", "ANY_SPLICE_REGION");
        assertPolicyRefused(f, p, "PREDICTION_CONSEQUENCE_SCOPE_UNSUPPORTED");
        p = policy(f, true); ((ObjectNode) p.path("consequenceScope")).putArray("sourceIds").add("unreviewed");
        assertPolicyRefused(f, p, "PREDICTION_CONSEQUENCE_SCOPE_UNSUPPORTED");
        p = policy(f, true); p.remove("consequenceScope");
        assertPolicyRefused(f, p, "PREDICTION_INTRONIC_POSITION_SCOPE_UNSUPPORTED");
        p = policy(f, true); ((ObjectNode) p.path("intronicPositionScope")).put("minimumAbsoluteOffset", 2);
        assertPolicyRefused(f, p, "PREDICTION_INTRONIC_POSITION_SCOPE_UNSUPPORTED");
        p = policy(f, true); ((ObjectNode) p.path("intronicPositionScope")).putArray("sourceIds").add("unreviewed");
        assertPolicyRefused(f, p, "PREDICTION_INTRONIC_POSITION_SCOPE_UNSUPPORTED");
        p = policy(f, false); p.putArray("criterionIds").add("ACMG_AMP_2015:PP3");
        assertPolicyRefused(f, p, "PREDICTION_CONSEQUENCE_SCOPE_UNSUPPORTED");
        var alpha = base.alpha("0.01");
        p = base.policy(alpha, "BP4", "ALPHAMISSENSE", "LE", "0.1"); scope(p);
        assertPolicyRefused(alpha, p, "PREDICTION_CONSEQUENCE_SCOPE_UNSUPPORTED");
    }

    @Test void contradictoryOrDuplicatePositionMeasurementIsNotAValidProof() throws Exception {
        var f = base.splice("0.01", 7); var p = policy(f, true);
        ObjectNode valid = base.run(f, p, null); base.assertStatus("MET", valid);
        ObjectNode position = (ObjectNode) valid.path("vepEvidence").deepCopy();
        var measurements = (com.fasterxml.jackson.databind.node.ArrayNode) position.path("measurements");
        ObjectNode offset = null;
        for (var value : measurements) if ("vep.intronicSignedOffset".equals(value.path("metricId").asText())) offset = (ObjectNode) value;
        assertNotNull(offset);
        offset.put("rawValue", "3");
        assertEquals("PREDICTION_INTRONIC_POSITION_IDENTITY_MISMATCH", PredictionConsequenceScope.issue(p,
                f.candidate().getTranscriptAnnotations().get(0), position, base.mapper.createObjectNode()));
        offset.put("rawValue", "7"); measurements.add(offset.deepCopy());
        assertEquals("PREDICTION_INTRONIC_POSITION_AMBIGUOUS", PredictionConsequenceScope.issue(p,
                f.candidate().getTranscriptAnnotations().get(0), position, base.mapper.createObjectNode()));
    }

    @Test void bp7KeepsItsStricterPositionsAndRevalidatesScopedBp4() throws Exception {
        var f = base.splice("0.01", 3); ObjectNode bp4 = policy(f, true), bp7 = base.bp7Policy(f, bp4); scope(bp7);
        ObjectNode dependency = base.run(f, bp4, null); base.assertStatus("MET", dependency);
        base.assertRefused(base.run(f, bp7, dependency));
        mutate(f, "intron_variant&splice_region_variant", "c.100+7A>G");
        dependency = base.run(f, bp4, null);
        ObjectNode result = base.run(f, bp7, dependency); base.assertStatus("MET", result);
        assertEquals(1, result.path("dependencyRevalidationCalls").asInt());
        assertEquals(0, result.path("dependencyProviderCalls").asInt());
        ObjectNode wrong = dependency.deepCopy();
        ((ObjectNode) wrong.path("policy")).remove("intronicPositionScope"); base.seal(wrong);
        base.assertRefused(base.run(f, bp7, wrong));
        bp7.set("intronicPositionScope", bp4.path("intronicPositionScope").deepCopy());
        assertPolicyRefused(f, bp7, "PREDICTION_INTRONIC_POSITION_SCOPE_UNSUPPORTED");
    }

    @Test void hgvsVersionCannotBeBorrowedFromAnotherTranscript() throws Exception {
        var f = base.splice("0.01", 7);
        ObjectNode json = annotation(f); json.put("hgvsCoding", "ENST000001.99:c.100+7A>G");
        f.candidate().applyTranscriptAnnotations(List.of(base.mapper.treeToValue(json, TranscriptConsequence.class)));
        ObjectNode result = base.run(f, policy(f, true), null); base.assertRefused(result);
        assertFalse(result.has("consequenceScopeValidation"));
    }

    @ParameterizedTest @CsvSource({"0.15,MET", "0.20,MET", "0.200001,NOT_MET"})
    void selectedBp4ThresholdIsNotClampedByTheOldGenericBoundary(String score, String expected) throws Exception {
        for (int offset : List.of(7, -21)) {
            var f = base.splice(score, offset); ObjectNode bp4 = policy(f, true);
            ((ObjectNode) bp4.path("threshold")).put("decimal", "0.20");
            ObjectNode bp7 = base.bp7Policy(f, bp4); scope(bp7);
            ObjectNode dependency = base.run(f, bp4, null); base.assertStatus(expected, dependency);
            ObjectNode result = base.run(f, bp7, dependency); base.assertStatus(expected, result);
            assertEquals(1, result.path("dependencyRevalidationCalls").asInt());
            assertEquals(0, result.path("dependencyProviderCalls").asInt());
            assertFalse(result.path("policy").has("threshold"));
        }
    }

    private ObjectNode policy(PredictionClauseRuntimeTest.Fixture f, boolean position) {
        ObjectNode p = base.policy(f, "BP4", "SPLICEAI", "LE", "0.1"); scope(p);
        if (position) p.putObject("intronicPositionScope").put("mode", "OUTSIDE_CANONICAL_SPLICE_DINUCLEOTIDES")
                .putArray("sourceIds").add("synthetic-source");
        return p;
    }
    private static void scope(ObjectNode p) {
        p.putObject("consequenceScope").put("mode", "SYNONYMOUS_OR_INTRONIC_ONLY").putArray("sourceIds").add("synthetic-source");
    }
    private void assertPolicyRefused(PredictionClauseRuntimeTest.Fixture f, ObjectNode p, String reason) {
        ObjectNode result = base.run(f, p, null); base.assertRefused(result);
        assertEquals(reason, result.at("/evaluationResult/reasonCode").asText()); assertFalse(result.has("observation"));
    }
    private ObjectNode annotation(PredictionClauseRuntimeTest.Fixture f) {
        ObjectNode node = base.mapper.valueToTree(f.candidate().getTranscriptAnnotations().get(0));
        node.retain("geneSymbol", "entrezGeneId", "transcriptAccession", "hgvsCoding", "hgvsProtein", "consequence",
                "geneId", "transcriptId", "transcriptSource", "impact", "exon", "intron", "canonical", "maneSelect", "manePlusClinical",
                "proteinPosition", "aminoAcids", "codons", "lof", "lofFilter", "lofFlags", "source", "status", "version", "timestamp");
        return node;
    }
    private void mutate(PredictionClauseRuntimeTest.Fixture f, String terms, String hgvs) throws Exception {
        ObjectNode node = annotation(f); node.put("consequence", terms);
        node.put("hgvsCoding", f.context().at("/transcript/accession").asText() + "." + f.context().at("/transcript/version").asInt() + ":" + hgvs);
        f.candidate().applyTranscriptAnnotations(List.of(base.mapper.treeToValue(node, TranscriptConsequence.class)));
    }
}
