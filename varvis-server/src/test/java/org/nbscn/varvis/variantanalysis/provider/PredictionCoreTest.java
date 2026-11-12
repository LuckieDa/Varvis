package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic contracts only: no external score, inference or calibration validation. */
public class PredictionCoreTest {
    final ObjectMapper mapper = new ObjectMapper();
    ObjectNode calibration() { return PredictionDevelopmentRuntime.load(mapper, "prediction-am-calibration-v1.json"); }
    public static ObjectNode resource(ObjectMapper mapper) {
        var r = mapper.createObjectNode().put("model", "ALPHAMISSENSE").put("dataVersion", "SYNTHETIC")
                .put("sourceLocator", "synthetic-fixture").put("sha256", "a".repeat(64)).put("calibrationCompatibility", "VERIFIED");
        r.putObject("license").put("status", "PERMITTED").put("purpose", "DECLARED_RESEARCH").put("sourceLocator", "synthetic-only");
        r.putObject("compatibilityEvidence").put("sha256", "b".repeat(64)).put("sourceLocator", "synthetic-only")
                .put("calibrationId", "BERGQUIST_2025_AM_TABLE1"); return r;
    }
    public static ObjectNode restrictions(ObjectMapper mapper, JsonNode plan) {
        var r = mapper.createObjectNode().put("status", "VERIFIED_SAVED_REFERENCES").put("sourceSha256", "c".repeat(64));
        r.set("contextId", plan.at("/context/contextId")); r.putArray("references"); return r;
    }
    @Test void everyPrintedBoundaryAndGridIsExactAndThreePointsNeverStrong() {
        var c = calibration(); assertNull(PredictionCalibration.issue(c));
        for (var band : c.path("bands")) for (String bound : new String[]{"lower", "upper"}) {
            var result = PredictionCalibration.evaluate(mapper, c, band.path(bound).asText(), true);
            assertEquals(band.path("sourcePointValue"), result.path("sourcePointValue"), result.toString());
            assertEquals(band.path("localAppliedStrength"), result.path("appliedStrength"));
        }
        for (int i = 0; i <= 1000; i++) assertEquals("CALIBRATED", PredictionCalibration.evaluate(mapper, c,
                java.math.BigDecimal.valueOf(i, 3).toPlainString(), true).path("status").asText());
        assertEquals("THREE_POINT", PredictionCalibration.evaluate(mapper, c, "0.972", true).path("sourceStrength").asText());
        assertEquals("MODERATE", PredictionCalibration.evaluate(mapper, c, "0", true).path("appliedStrength").asText());
    }
    @Test void realPrecisionAndIntermediateValuesNeverRoundedAndMalformedBandsRefused() {
        for (String value : new String[]{"0.1695", "0.7915", "0.9055", "0.0705", "0.9899"})
            assertEquals("CALIBRATION_PRECISION_UNRESOLVED", PredictionCalibration.evaluate(mapper, calibration(), value, true).path("reasonCode").asText());
        assertEquals("CALIBRATION_PRECISION_UNRESOLVED", PredictionCalibration.evaluate(mapper, calibration(), "0.990", false).path("reasonCode").asText());
        assertEquals("UNINFORMATIVE", PredictionCalibration.evaluate(mapper, calibration(), "0.170", true).path("direction").asText());
        for (String invalid : new String[]{"NaN", "Infinity", "-0.1", "1.1", "1e-3"}) assertEquals("CALIBRATION_SCORE_INVALID",
                PredictionCalibration.evaluate(mapper, calibration(), invalid, true).path("reasonCode").asText());
        assertEquals("MISSING_VALUE", PredictionCalibration.evaluate(mapper, calibration(), null, true).path("reasonCode").asText());
        var c = calibration(); ((ObjectNode)c.at("/bands/1")).put("lower", "0.069"); assertEquals("CALIBRATION_BAND_OVERLAP", PredictionCalibration.issue(c));
    }
    ObjectNode plan() {
        var p = PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json");
        p.putObject("context").put("contextId", "synthetic-context"); p.set("effectRestrictions", restrictions(mapper, p)); return p;
    }
    ObjectNode mechanisms(String protein, String splice) {
        var m = mapper.createObjectNode();
        m.set("PROTEIN_MISSENSE", PredictionCalibration.evaluate(mapper, calibration(), protein, true));
        m.set("SPLICING", PredictionCalibration.evaluate(mapper, calibration(), splice, true)); return m;
    }
    JsonNode apply(ObjectNode p, String protein, String splice) { return PredictionMechanismApplication.apply(mapper, p, mechanisms(protein, splice), null); }
    @Test void mechanismsDoNotCancelOrAddAndUnknownIsNotGray() {
        var p = plan(); var both = apply(p, "0.792", "0.999");
        assertEquals("MET", both.at("/PP3/status").asText()); assertEquals("SUPPORTING", both.at("/PP3/appliedStrength").asText());
        var opposed = apply(p, "0.1", "0.999"); assertEquals("MET", opposed.at("/PP3/status").asText());
        assertEquals("SPLICING", opposed.at("/PP3/selectedMechanism").asText()); assertEquals("NOT_MET", opposed.at("/BP4/status").asText());
        assertEquals("MET", apply(p, "0.990", null).at("/PP3/status").asText());
        assertEquals("NOT_EVALUATED", apply(p, "0.1", null).at("/BP4/status").asText());
        assertEquals("NOT_MET", apply(p, "0.5", "0.5").at("/PP3/status").asText());
        assertEquals("NOT_EVALUATED", apply(p, "0.5", null).at("/PP3/status").asText());
        assertEquals("SUPPORTING", apply(p, "0.1", "0").at("/BP4/appliedStrength").asText());
        p.put("secondaryPp3Route", "DISABLED"); assertNotEquals("MET", apply(p, null, "0.999").at("/PP3/status").asText());
    }
    @Test void savedSameEffectRestrictionBlocksOnlyDependentApplication() {
        var p = plan(); ((ArrayNode)p.at("/effectRestrictions/references")).addObject().put("criterionId", "ACMG_AMP_2015:PP3")
                .put("mechanism", "PROTEIN_MISSENSE").put("savedEvidenceId", "synthetic-evidence").put("effectId", "synthetic-effect").put("action", "BLOCK_DUPLICATE");
        var result = apply(p, "0.990", "0.990"); assertEquals("SAME_EFFECT_ALREADY_ADOPTED", result.at("/PP3/reasonCode").asText());
        assertEquals("NOT_MET", result.at("/BP4/status").asText());
        p.remove("effectRestrictions"); assertEquals("SAVED_EFFECT_RESTRICTIONS_UNAVAILABLE", apply(p, "0.990", null).at("/PP3/reasonCode").asText());
    }
    @Test void offlineStandardBandingNeverBecomesMechanismApplication() {
        var mechanism = mapper.createObjectNode().put("status", "CALIBRATED").put("direction", "HARMFUL")
                .put("calibratedStrength", "STRONG").put("appliedStrength", "NONE")
                .put("operation", "OFFLINE_CALIBRATION_VALIDATION").put("applicationStatus", "NOT_EVALUATED");
        var mechanisms = mapper.createObjectNode(); mechanisms.set("PROTEIN_MISSENSE", mechanism);
        assertEquals("NOT_EVALUATED", PredictionMechanismApplication.apply(mapper, plan(), mechanisms, null).at("/PP3/status").asText());
        mechanism.put("direction", "BENIGN"); mechanisms.set("SPLICING", mechanism.deepCopy());
        assertEquals("NOT_EVALUATED", PredictionMechanismApplication.apply(mapper, plan(), mechanisms, null).at("/BP4/status").asText());
    }
    TranscriptConsequence tx() throws Exception { return mapper.treeToValue(mapper.readTree("{\"transcriptAccession\":\"ENST00000000001.2\",\"geneId\":\"ENSG00000000001\",\"geneSymbol\":\"SYNTHETIC\",\"consequence\":\"missense_variant\",\"proteinPosition\":\"7\",\"aminoAcids\":\"E/V\"}"), TranscriptConsequence.class); }
    @Test void multipleRowsExactIdentityAndVerifiedCdsBridgeDoNotStripVersions() throws Exception {
        var tx = tx(); var raw = mapper.createObjectNode(); var rows = raw.putArray("rows");
        rows.addObject().put("transcriptId", "ENST00000000001.1").put("proteinVariant", tx.getAminoAcidChange()).put("rawScore", "0.99");
        assertEquals("IDENTITY_UNRESOLVED", PredictionObservationAssociation.alpha(mapper, raw, tx, null).path("status").asText());
        var bridge = mapper.createObjectNode().put("status", "VERIFIED_SEQUENCE_EQUIVALENCE").put("sourceTranscriptId", "ENST00000000001.1")
                .put("targetTranscriptId", "ENST00000000001.2").put("sourceProvenanceSha256", "d".repeat(64)).put("sourceCds", "ATGGAA").put("targetCds", "ATGGAA")
                .put("cdsCanonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(mapper.getNodeFactory().textNode("ATGGAA")));
        assertEquals("ASSOCIATED", PredictionObservationAssociation.alpha(mapper, raw, tx, bridge).path("status").asText());
        rows.addObject().put("transcriptId", tx.getVersionedTranscriptId()).put("proteinVariant", tx.getAminoAcidChange()).put("rawScore", "0.99");
        rows.add(rows.get(1).deepCopy()); assertEquals(2, PredictionObservationAssociation.alpha(mapper, raw, tx, null).path("matchedRowIndices").size());
        ((ObjectNode)rows.get(2)).put("rawScore", "0.1"); assertEquals("AMBIGUOUS_RECORD", PredictionObservationAssociation.alpha(mapper, raw, tx, null).path("status").asText());
        assertEquals(3, raw.path("rows").size());
    }
    @Test void spliceRawParametersAnnotationManifestAndNegativeOffsetsAreIndependentGates() throws Exception {
        var resource = resource(mapper).put("model", "SPLICEAI").put("scoreMode", "RAW").put("distance", 4999).put("mask", 0);
        ((ObjectNode)resource.path("compatibilityEvidence")).put("calibrationId", "CLINGEN_SVI_SPLICE_2024_03_22_SECTION_5");
        var annotation = resource.putObject("annotation").put("status", "VERIFIED").put("source", "SYNTHETIC_MANE_EQUIVALENCE")
                .put("version", "synthetic").put("assembly", "GRCh38");
        for (String key : new String[]{"transcriptSetSha256", "referenceSha256", "annotationSha256", "calibrationEvidenceSha256"}) annotation.put(key, "e".repeat(64));
        var expected = resource.deepCopy(); assertNull(PredictionResourceCompatibility.issue(expected, resource));
        for (String field : new String[]{"scoreMode", "distance", "mask", "annotation"}) {
            var bad = resource.deepCopy(); bad.remove(field); assertNotNull(PredictionResourceCompatibility.issue(expected, bad));
        }
        var allele = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
        var row = mapper.createObjectNode().put("transcriptId", tx().getVersionedTranscriptId()).put("geneId", tx().getGeneId()).put("strand", "-");
        row.set("queryAllele", mapper.valueToTree(allele));
        for (String kind : new String[]{"AG", "AL", "DG", "DL"}) row.put("DS_" + kind, "0.2").put("DP_" + kind, -3);
        var result = SpliceAiBusinessRuntime.calibrateDevelopment(mapper, row, expected, resource, tx(), allele);
        assertEquals("HARMFUL", result.path("direction").asText(), result.toString()); assertEquals(97, result.at("/observation/events/0/genomicPosition").asInt());
        resource.put("appliedStrength", "STRONG").put("sourcePointValue", 4);
        var capped = SpliceAiBusinessRuntime.calibrateDevelopment(mapper, row, expected, resource, tx(), allele);
        assertEquals("SUPPORTING", capped.path("appliedStrength").asText(), "AM synthetic strength is never a SpliceAI calibration input");
        for (String kind : new String[]{"AG", "AL", "DG", "DL"}) row.put("DS_" + kind, "0.1");
        assertEquals("BENIGN", SpliceAiBusinessRuntime.calibrateDevelopment(mapper, row, expected, resource, tx(), allele).path("direction").asText());
        var canonical = mapper.treeToValue(mapper.createObjectNode().put("transcriptAccession", tx().getVersionedTranscriptId())
                .put("geneId", tx().getGeneId()).put("consequence", "intron_variant&splice_donor_variant"), TranscriptConsequence.class);
        assertEquals("SPLICE_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE", SpliceAiBusinessRuntime.calibrateDevelopment(mapper, row, expected, resource, canonical, allele).path("reasonCode").asText());
        resource.putObject("license").put("status", "UNVERIFIED"); assertEquals("LICENSE_SOURCE_UNBOUND", PredictionResourceCompatibility.issue(expected, resource));
    }
}
