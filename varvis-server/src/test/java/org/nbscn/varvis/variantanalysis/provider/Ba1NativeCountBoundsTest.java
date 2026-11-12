package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic native rows and query envelopes, real parser/opaque producer/consumer.
 * Independent expectations are fixed before implementation in T03's private handoff.
 * No real GN027 publication is adopted and no patient sex/ploidy is manufactured.
 */
class Ba1NativeCountBoundsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final PopulationFrequencyRuntimeTest facts = new PopulationFrequencyRuntimeTest();
    private final GnomadPopulationInputProfile profile = GnomadPopulationInputProfile.loadExecutableV2();
    private record NativeInput(String id, ObjectNode carrier, String raw, Ba1NativeIndividualCountSupply.Material material) { }

    private Map<String, String> values(int hom, int hemi, Integer rawAc) {
        int adjAc = 2 * hom + hemi + 5;
        var info = facts.jointInfo("0.0001", "10000", "0.1");
        for (String suffix : new String[]{"joint", "grpmax_joint", "joint_nfe"}) {
            info.put("AC_" + suffix, Integer.toString(adjAc)); info.put("AN_" + suffix, "10000");
            info.put("AF_" + suffix, BigDecimal.valueOf(adjAc).divide(BigDecimal.valueOf(10000)).toPlainString());
            info.put("nhomalt_" + suffix, Integer.toString(hom));
        }
        info.put("AC_joint_XY", Integer.toString(hemi)); info.put("AN_joint_XY", "5000");
        info.put("AF_joint_XY", BigDecimal.valueOf(hemi).divide(BigDecimal.valueOf(5000)).toPlainString());
        info.put("nhomalt_joint_XY", "0");
        if (rawAc != null) {
            info.put("AC_joint_raw", rawAc.toString()); info.put("AN_joint_raw", "12000");
            info.put("AF_joint_raw", BigDecimal.valueOf(rawAc).divide(BigDecimal.valueOf(12000), MathContext.DECIMAL64).toPlainString());
        }
        return info;
    }
    private NativeInput nativeInput(Map<String, String> info, String contig, int position, String ref, String alt) throws Exception {
        var context = facts.diseaseContext("MYH7"); var observation = facts.observation(context, info, "PASS");
        var wiring = new RuntimeEvidenceV2ProviderWiring(mapper, adapter);
        var carrier = wiring.reparsePopulationObservation(context, observation, "a".repeat(64),
                GnomadPopulationInputProfile.loadBusinessPm2Supporting(), profile).carrier();
        var allele = mapper.createObjectNode().put("assembly", "GRCh38").put("contig", contig).put("position", position)
                .put("reference", ref).put("alternate", alt);
        carrier.set("queryAllele", allele);
        var fingerprint = mapper.createObjectNode().put("resourceId", carrier.at("/source/sourceId").asText());
        fingerprint.set("queryAllele", allele); carrier.withObject("/queryOutcome").put("queryFingerprint", adapter.canonicalSha256(fingerprint));
        String[] columns = observation.path("rawPopulationRecord").asText().split("\t", -1);
        columns[0] = "chr" + contig; columns[1] = Integer.toString(position); columns[3] = ref; columns[4] = alt;
        String raw = String.join("\t", columns); wiring.preserveBa1SubtypeFilters(carrier, raw);
        var norm = mapper.createObjectNode();
        for (String name : new String[]{"method", "methodVersion", "referenceSequenceSha256", "refValidation"})
            norm.set(name, context.at("/variant/normalization").path(name));
        String id = adapter.canonicalAlleleId(allele, norm);
        return new NativeInput(id, carrier, raw, Ba1NativeIndividualCountSupply.read(mapper, profile, id, carrier, raw));
    }
    private NativeInput nativeInput(Map<String, String> info) throws Exception { return nativeInput(info, "X", 153691089, "G", "A"); }
    private ObjectNode evaluate(NativeInput input) throws Exception {
        var catalogs = new Ba1ConditionalCatalogTest();
        var selection = new Ba1SpecificationCatalog(mapper, catalogs.snapshot()).select(catalogs.mapping(), facts.diseaseContext("MYH7"));
        return new GnomadPopulationCriterionEvaluator(mapper).evaluateCatalogBa1(input.id(), input.carrier(), profile,
                selection, null, org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(input.raw()), input.material());
    }

    @ParameterizedTest @CsvSource({"0,10,MET,TRUE", "4,6,MET,TRUE", "4,5,NOT_EVALUATED,UNKNOWN"})
    void onlyQualifiedNativeNonParHaploidCountsCanCombineWithDiploidHomozygotes(int homo, int hemi, String expected, String truth) throws Exception {
        var input = nativeInput(values(homo, hemi, null)); var counts = input.material().counts(); assertNotNull(counts);
        assertEquals(BigInteger.valueOf(hemi), counts.hemizygotes());
        assertEquals(Ba1ConditionalFrequencyRule.SumRelationship.SAME_SOURCE_DISJOINT, counts.relationship());
        var output = evaluate(input);
        assertEquals(expected, output.at("/evaluationResult/status").asText(), output.toPrettyString());
        assertEquals(truth, output.at("/ruleConditionEvaluation/countTruth").asText());
        assertEquals("FALSE", output.at("/ruleConditionEvaluation/frequencyTruth").asText());
        assertEquals(Ba1ConditionalFrequencyRule.CountScopeCompleteness.UNVERIFIED_OR_PARTIAL, input.material().completeness());
    }
    @ParameterizedTest @CsvSource({"X,10001,G,A", "14,23400000,G,A", "X,2781479,GA,GT", "X,2781479,G,GA"})
    void parAutosomeCrossingAndNativeAnchorCannotGrantHemizygoteCounts(String contig, int position, String ref, String alt) throws Exception {
        var input = nativeInput(values(0, 10, null), contig, position, ref, alt);
        var counts = input.material().counts(); assertNotNull(counts); assertNull(counts.hemizygotes());
        assertEquals("NOT_EVALUATED", evaluate(input).at("/evaluationResult/status").asText());
    }
    @Test void yNonParUsesTheSameSourceQualificationWithoutAModelOrPatientSexInference() throws Exception {
        var input = nativeInput(values(0, 10, null), "Y", 10000000, "G", "A");
        var counts = input.material().counts(); assertNotNull(counts); assertEquals(BigInteger.TEN, counts.hemizygotes());
        assertEquals("MET", evaluate(input).at("/evaluationResult/status").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"AC_joint_XY", "AN_joint_XY", "AF_joint_XY", "nhomalt_joint_XY"})
    void absentSexStatisticsRemainUnknownAndDoNotEraseAnIndependentHomozygoteLowerBound(String field) throws Exception {
        var info = values(10, 0, null); info.remove(field); var input = nativeInput(info);
        var counts = input.material().counts(); assertNotNull(counts); assertNull(counts.hemizygotes());
        assertEquals("MET", evaluate(input).at("/evaluationResult/status").asText());
    }
    @ParameterizedTest @CsvSource({"9,NOT_MET,FALSE", "10,NOT_EVALUATED,UNKNOWN", "13,NOT_EVALUATED,UNKNOWN"})
    void allRawAltCopiesAreAnUpperBoundNeverAPositivePersonCount(int rawAc, String expected, String countTruth) throws Exception {
        var input = nativeInput(values(0, 1, rawAc));
        assertEquals(BigInteger.valueOf(rawAc), input.material().upperBound());
        var output = evaluate(input);
        assertEquals(expected, output.at("/evaluationResult/status").asText(), output.toPrettyString());
        assertEquals(countTruth, output.at("/ruleConditionEvaluation/countTruth").asText());
    }
    @Test void falseCountBoundCannotResolveAnUnknownFrequencyArm() throws Exception {
        var info = values(0, 1, 9); info.remove("fafmax_faf95_max_joint");
        var output = evaluate(nativeInput(info));
        assertEquals("FALSE", output.at("/ruleConditionEvaluation/countTruth").asText());
        assertEquals("UNKNOWN", output.at("/ruleConditionEvaluation/frequencyTruth").asText());
        assertEquals("NOT_EVALUATED", output.at("/evaluationResult/status").asText());
    }
    @Test void aCompleteRawUpperBoundDoesNotRequireAnExactHomoOrHemiCount() throws Exception {
        var info = values(0, 1, 9); info.remove("nhomalt_joint"); info.remove("AC_joint_XY");
        var input = nativeInput(info); assertNull(input.material().counts());
        assertEquals(BigInteger.valueOf(9), input.material().upperBound());
        assertEquals("QUALIFIED_UPPER_BOUND", input.material().publicDetails().path("status").asText());
        assertEquals("NOT_MET", evaluate(input).at("/evaluationResult/status").asText());
    }
    @Test void anAncestryRawCountCannotReplaceTheWholeReleaseUpperBound() throws Exception {
        var info = values(0, 1, 9); info.remove("AC_joint_raw"); info.put("AC_joint_nfe_raw", "9");
        var input = nativeInput(info); assertNull(input.material().upperBound());
        assertEquals("NOT_EVALUATED", evaluate(input).at("/evaluationResult/status").asText());
    }
    @Test void aRawSexAlleleCountCannotReplaceQualifiedAdjustedHemizygotes() throws Exception {
        var info = values(0, 10, null); info.remove("AC_joint_XY"); info.put("AC_joint_XY_raw", "10");
        var input = nativeInput(info); var counts = input.material().counts(); assertNotNull(counts);
        assertNull(counts.hemizygotes()); assertEquals("NOT_EVALUATED", evaluate(input).at("/evaluationResult/status").asText());
    }
    @Test void zeroXyAlleleCoverageIsMissingScopeAndDoesNotEraseIndependentHomoEvidence() throws Exception {
        var info = values(10, 0, null); info.put("AN_joint_XY", "0");
        var input = nativeInput(info); var counts = input.material().counts(); assertNotNull(counts);
        assertNull(counts.hemizygotes());
        assertEquals("MET", evaluate(input).at("/evaluationResult/status").asText());
        assertEquals("BA1_NATIVE_COUNT_XY_NO_CALLED_ALLELES", input.material().publicDetails()
                .at("/additionalNativeBounds/hemizygoteLowerBound/reasonCode").asText());
    }
    @ParameterizedTest @ValueSource(strings = {"AC_joint_raw", "AN_joint_raw", "AF_joint_raw"})
    void incompleteRawDenominatorDoesNotBecomeACompleteNegative(String field) throws Exception {
        var info = values(0, 1, 9); info.remove(field); var input = nativeInput(info);
        assertNull(input.material().upperBound());
        assertEquals("NOT_EVALUATED", evaluate(input).at("/evaluationResult/status").asText());
    }
    @ParameterizedTest @CsvSource({"nhomalt_joint_XY,1", "AC_joint_XY,100", "AF_joint_XY,0.9", "AC_joint_raw,5",
            "AN_joint_raw,9999", "AF_joint_raw,0.8", "AC_joint_raw,-1", "AC_joint_raw,1.5", "AC_joint_raw,'9,9'"})
    void inconsistentScopePloidyRoundingAndUnitsCannotBeWaivedByAPositiveFrequency(String field, String value) throws Exception {
        var info = values(0, 1, 9); info.put(field, value);
        info.put("fafmax_faf95_max_joint", "0.002"); // Count source fails before either arm may grant evidence.
        var input = nativeInput(info);
        assertNotNull(input.material().issue(mapper, profile, input.id(), input.carrier(),
                org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(input.raw())));
        assertEquals("NOT_EVALUATED", evaluate(input).at("/evaluationResult/status").asText());
    }
}
