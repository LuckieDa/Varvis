package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class GeneralRevelRuntimeTest {
    final RevelPredictionObservationTest fixture = new RevelPredictionObservationTest();

    @ParameterizedTest @CsvSource({"0,STRONG,BENIGN", "0.003,STRONG,BENIGN", "0.0030001,STRONG,BENIGN",
            "0.016,STRONG,BENIGN", "0.0160001,MODERATE,BENIGN", "0.183,MODERATE,BENIGN",
            "0.1830001,SUPPORTING,BENIGN", "0.290,SUPPORTING,BENIGN", "0.2900001,NONE,UNINFORMATIVE",
            "0.5,NONE,UNINFORMATIVE", "0.6439999,NONE,UNINFORMATIVE", "0.644,SUPPORTING,HARMFUL",
            "0.7729999,SUPPORTING,HARMFUL", "0.773,MODERATE,HARMFUL", "0.9319999,MODERATE,HARMFUL",
            "0.932,STRONG,HARMFUL", "1,STRONG,HARMFUL"})
    void exactPublishedIntervalsAreContinuousWithoutRounding(String score, String strength, String direction) {
        var mapper = fixture.base.mapper;
        var actual = GeneralRevelPolicy.calibrate(mapper, GeneralRevelPolicy.load(mapper), score);
        assertEquals("CALIBRATED", actual.path("status").asText());
        assertEquals(strength, actual.path("appliedStrength").asText());
        assertEquals(direction, actual.path("direction").asText());
        assertFalse(actual.path("clinicalAcceptance").asBoolean());
        assertFalse(actual.at("/combinationConstraints/automaticCombinationAllowed").asBoolean());
    }

    @ParameterizedTest @CsvSource({".", "NaN", "-0.1", "1.01", "1e-3", "abc"})
    void invalidScoresAreNeverRoundedOrZeroed(String score) {
        var mapper = fixture.base.mapper;
        assertEquals("NOT_EVALUATED", GeneralRevelPolicy.calibrate(mapper, GeneralRevelPolicy.load(mapper), score).path("status").asText());
    }

    @ParameterizedTest @CsvSource({"0.3,SUPPORTING,BENIGN", "0.3000001,NONE,UNINFORMATIVE",
            "0.6999999,NONE,UNINFORMATIVE", "0.7,SUPPORTING,HARMFUL", "0.98,SUPPORTING,HARMFUL"})
    void expertMissenseThresholdsAreInclusiveAndDoNotBorrowGeneralStrength(String score, String strength, String direction) {
        var mapper = fixture.base.mapper;
        var thresholds = mapper.createObjectNode().put("pp3Minimum", "0.7").put("bp4Maximum", "0.3");
        var actual = GeneralRevelRuntime.publishedCalibration(mapper, thresholds, new java.math.BigDecimal(score));
        assertEquals("CALIBRATED", actual.path("status").asText());
        assertEquals(strength, actual.path("appliedStrength").asText());
        assertEquals(direction, actual.path("direction").asText());
        assertEquals(score, actual.path("rawScore").asText());
    }

    @Test void oneChosenModelProducesStrongProteinButDoesNotSupplySpliceOrChangeInput() throws Exception {
        var i = fixture.input("0.958", "GE", "0.7"); var f = i.fixture();
        var mapper = fixture.base.mapper; var calls = new AtomicInteger(); var before = mapper.valueToTree(f.candidate());
        var runtime = runtime(i, calls, "");
        var prepared = runtime.prepareSynthetic(f.context(), f.candidate(), (ObjectNode) f.observation().path("transcriptModel"));
        assertEquals(0, calls.get());
        // Preparation is immutable even if a caller subsequently mutates its saved annotation object.
        f.candidate().applyTranscriptAnnotations(List.of());
        var output = runtime.execute(prepared);
        assertEquals("STRONG", output.at("/protein/appliedStrength").asText(), output.toPrettyString());
        var mechanisms = mapper.createObjectNode(); mechanisms.set("PROTEIN_MISSENSE", output.path("protein"));
        mechanisms.putObject("SPLICING").put("status", "NOT_EVALUATED");
        var plan = PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json"); plan.set("context", f.context());
        plan.set("effectRestrictions", PredictionCoreTest.restrictions(mapper, plan));
        var applied = PredictionMechanismApplication.apply(mapper, plan, mechanisms, null);
        assertEquals("MET", applied.at("/PP3/status").asText()); assertEquals("STRONG", applied.at("/PP3/appliedStrength").asText());
        assertEquals("NOT_MET", applied.at("/BP4/status").asText());
        assertEquals(output, runtime.execute(prepared)); assertEquals(1, calls.get());
        assertEquals(before.at("/observation"), mapper.valueToTree(f.candidate()).at("/observation"));
        assertThrows(IllegalArgumentException.class, () -> runtime(i, calls, "").execute(prepared));
    }

    @ParameterizedTest @CsvSource({"0.1,BENIGN", "0.5,UNINFORMATIVE"})
    void benignOrGrayProteinDoesNotInventMissingSplicing(String score, String direction) throws Exception {
        var i = fixture.input(score, "GE", "0.7"); var f = i.fixture(); var runtime = runtime(i, new AtomicInteger(), "");
        var result = runtime.execute(runtime.prepareSynthetic(f.context(), f.candidate(), (ObjectNode) f.observation().path("transcriptModel")));
        assertEquals(direction, result.at("/protein/direction").asText());
        var mapper = fixture.base.mapper; var m = mapper.createObjectNode(); m.set("PROTEIN_MISSENSE", result.path("protein"));
        m.putObject("SPLICING").put("status", "NOT_EVALUATED");
        var plan = PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json"); plan.set("context", f.context());
        plan.set("effectRestrictions", PredictionCoreTest.restrictions(mapper, plan));
        assertEquals("BENIGN".equals(direction) ? "NOT_EVALUATED" : "NOT_MET",
                PredictionMechanismApplication.apply(mapper, plan, m, null).at("/BP4/status").asText());
    }

    @ParameterizedTest @CsvSource({"NO_RECORD,REVEL_NO_RECORD", "ISOFORMS,REVEL_ISOFORM_SCORE_CONFLICT",
            "CONTEXT,GENERAL_REVEL_OBSERVATION_IDENTITY_MISMATCH", "AA,REVEL_SOURCE_AMINO_ACID_MISMATCH"})
    void incompleteOrConflictingFactsCannotFallback(String mutation, String reason) throws Exception {
        var i = fixture.input("0.958", "GE", "0.7"); var f = i.fixture(); var calls = new AtomicInteger(); var runtime = runtime(i, calls, mutation);
        var result = runtime.execute(runtime.prepareSynthetic(f.context(), f.candidate(), (ObjectNode) f.observation().path("transcriptModel")));
        assertEquals("NOT_EVALUATED", result.at("/protein/status").asText());
        assertEquals(reason, result.at("/protein/reasonCode").asText(), result.toPrettyString()); assertEquals(1, calls.get());
    }

    @Test void unverifiedMaterialOrChangedCandidateIsBlockedBeforeLookup() throws Exception {
        var i = fixture.input("0.99", "GE", "0.7"); var f = i.fixture(); var calls = new AtomicInteger(); var runtime = runtime(i, calls, "");
        assertEquals("GENERAL_REVEL_VERIFIED_TRANSCRIPT_MATERIAL_REQUIRED", runtime.execute(runtime.prepare(f.context(), f.candidate(), null)).at("/protein/reasonCode").asText());
        var model = (ObjectNode) f.observation().path("transcriptModel"); model.put("transcript", "ENST000001.5");
        assertEquals("BLOCKED", runtime.prepareSynthetic(f.context(), f.candidate(), model).snapshot().path("status").asText());
        assertEquals(0, calls.get());
    }

    @Test void specialContextAndNonSyntheticTestSeamCannotObtainGenericScores() throws Exception {
        var mapper = fixture.base.mapper;
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        context.putObject("gene").put("hgncId", "HGNC:11621"); context.putObject("disease").put("mondoId", "MONDO:0015967");
        assertEquals("GENERAL_REVEL_SPECIALIZED_POLICY_REQUIRED", GeneralRevelPolicy.specialIssue(mapper, context));
        var i = fixture.input("0.99", "GE", "0.7"); var f = i.fixture(); var calls = new AtomicInteger(); var runtime = runtime(i, calls, "");
        f.context().put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
        f.context().put("contextId", fixture.base.adapter.canonicalContextId(f.context()));
        assertEquals("BLOCKED", runtime.prepareSynthetic(f.context(), f.candidate(), (ObjectNode) f.observation().path("transcriptModel")).snapshot().path("status").asText());
        assertEquals(0, calls.get());
    }

    GeneralRevelRuntime runtime(RevelPredictionObservationTest.Input i, AtomicInteger calls, String mutation) {
        return new GeneralRevelRuntime(fixture.base.mapper, (ObjectNode) i.policy().path("resourceBinding"), request -> {
            calls.incrementAndGet(); var observed = i.fixture().observation().deepCopy(); observed.put("status", "HIT");
            observed.set("context", request.path("context").deepCopy()); observed.set("transcriptModel", request.path("transcriptModel").deepCopy());
            observed.set("coordinateRecords", observed.path("rawRecords").deepCopy());
            observed.putObject("unusedAlternative").put("ALPHAMISSENSE", "0.999");
            if ("NO_RECORD".equals(mutation)) observed.put("status", "NO_RECORD");
            if ("CONTEXT".equals(mutation)) observed.putObject("context");
            if ("AA".equals(mutation)) {
                ((ObjectNode) i.fixture().observation().at("/rawRecords/0")).put("rawCsv", "1,90,100,A,G,K,Q,0.958,ENST000001");
                fixture.rebindEvidence(i.fixture()); observed.set("rawRecords", i.fixture().observation().path("rawRecords"));
                observed.set("evidence", i.fixture().observation().path("evidence"));
            }
            if ("ISOFORMS".equals(mutation)) observed.withArray("coordinateRecords").addObject().put("rawCsv", "1,90,100,A,G,K,R,0.1,ENST000002");
            return observed;
        }, true);
    }
}
