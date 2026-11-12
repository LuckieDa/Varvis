package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class GeneralAlphaMissenseRuntimeTest {
    final RevelPredictionObservationTest fixture = new RevelPredictionObservationTest();
    @ParameterizedTest @CsvSource({"0,MODERATE,BENIGN", "0.099,MODERATE,BENIGN", "0.0990001,SUPPORTING,BENIGN",
            "0.169,SUPPORTING,BENIGN", "0.1690001,NONE,UNINFORMATIVE", "0.7919999,NONE,UNINFORMATIVE",
            "0.792,SUPPORTING,HARMFUL", "0.9059999,SUPPORTING,HARMFUL", "0.906,MODERATE,HARMFUL",
            "0.972,MODERATE,HARMFUL", "0.973,MODERATE,HARMFUL", "0.9899999,MODERATE,HARMFUL", "0.990,STRONG,HARMFUL", "1,STRONG,HARMFUL"})
    void inheritedStandardProjectionHasExactBoundaries(String score, String strength, String direction) {
        var m = fixture.base.mapper;
        var result = GeneralAlphaMissensePolicy.calibrate(m, GeneralAlphaMissensePolicy.load(m), score);
        assertEquals("CALIBRATED", result.path("status").asText()); assertEquals(strength, result.path("appliedStrength").asText());
        assertEquals(direction, result.path("direction").asText()); assertFalse(result.path("clinicalAcceptance").asBoolean());
        assertEquals("OFFLINE_ONLY", PredictionDevelopmentRuntime.load(m, "prediction-standard-offline/am-standard-1.0.0.json").path("activation").asText());
    }
    @ParameterizedTest @CsvSource({"-0.1", "1.01", "NaN", "1e-3", "abc"})
    void invalidScoreIsNotRoundedOrDefaulted(String score) {
        var m = fixture.base.mapper;
        assertEquals("NOT_EVALUATED", GeneralAlphaMissensePolicy.calibrate(m, GeneralAlphaMissensePolicy.load(m), score).path("status").asText());
    }
    @ParameterizedTest @CsvSource({"0.999,HARMFUL,MET,NOT_MET", "0.1,BENIGN,NOT_EVALUATED,NOT_EVALUATED", "0.5,UNINFORMATIVE,NOT_EVALUATED,NOT_MET"})
    void exactSingleRowFeedsMechanismsWithoutInventingSplice(String score, String direction, String pp3, String bp4) throws Exception {
        var i = fixture.input("0.958", "GE", "0.7"); var f = i.fixture(); var m = fixture.base.mapper;
        var calls = new AtomicInteger(); var runtime = runtime(calls, score, "");
        var plan = runtime.prepareSynthetic(f.context(), f.candidate(), (ObjectNode) f.observation().path("transcriptModel"));
        assertEquals(0, calls.get()); var result = runtime.execute(plan);
        assertEquals(direction, result.at("/protein/direction").asText(), result.toPrettyString());
        var app = PredictionDevelopmentRuntime.load(m, "prediction-core-policy-v1.json"); app.set("context", f.context());
        app.set("effectRestrictions", PredictionCoreTest.restrictions(m, app)); var mechanisms = m.createObjectNode();
        mechanisms.set("PROTEIN_MISSENSE", result.path("protein")); mechanisms.putObject("SPLICING").put("status", "NOT_EVALUATED");
        var criteria = PredictionMechanismApplication.apply(m, app, mechanisms, null);
        assertEquals(pp3, criteria.at("/PP3/status").asText()); assertEquals(bp4, criteria.at("/BP4/status").asText());
        assertEquals(result, runtime.execute(plan)); assertEquals(1, calls.get());
        assertThrows(IllegalArgumentException.class, () -> runtime(calls, score, "").execute(plan));
        assertArrayEquals(m.writeValueAsBytes(result), m.writeValueAsBytes(m.readTree(m.writeValueAsBytes(result))));
    }
    @ParameterizedTest @CsvSource({"NO_RECORD,ALPHAMISSENSE_NO_MATCHING_RECORD", "MULTIPLE,ALPHAMISSENSE_SINGLE_SOURCE_ROW_REQUIRED",
            "VERSION,ALPHAMISSENSE_SOURCE_TRANSCRIPT_MATERIAL_REQUIRED", "OTHER_TX,ALPHAMISSENSE_DIFFERENT_TRANSCRIPT_UNSUPPORTED",
            "AA,ALPHAMISSENSE_PROTEIN_IDENTITY_MISMATCH", "RAW,ALPHAMISSENSE_SOURCE_ROW_IDENTITY_MISMATCH",
            "ALLELE,GENERAL_ALPHAMISSENSE_OBSERVATION_IDENTITY_MISMATCH", "RESOURCE,GENERAL_ALPHAMISSENSE_OBSERVATION_IDENTITY_MISMATCH"})
    void missingOrConflictingFactsStayExplicitWithoutOtherModelFallback(String mutation, String reason) throws Exception {
        var i = fixture.input("0.958", "GE", "0.7"); var f = i.fixture(); var calls = new AtomicInteger(); var r = runtime(calls, "0.99", mutation);
        var result = r.execute(r.prepareSynthetic(f.context(), f.candidate(), (ObjectNode) f.observation().path("transcriptModel")));
        assertEquals("NOT_EVALUATED", result.at("/protein/status").asText()); assertEquals(reason, result.at("/protein/reasonCode").asText(), result.toPrettyString()); assertEquals(1, calls.get());
    }
    @Test void unverifiedMaterialCannotQueryAndSyntheticSeamCannotAcceptPatientContext() throws Exception {
        var f = fixture.input("0.958", "GE", "0.7").fixture(); var calls = new AtomicInteger(); var r = runtime(calls, "0.99", "");
        assertEquals("BLOCKED", r.prepare(f.context(), f.candidate(), null).snapshot().path("status").asText());
        f.context().put("dataClass", "PRIVACY_BOUNDED_RUNTIME"); f.context().put("contextId", fixture.base.adapter.canonicalContextId(f.context()));
        assertEquals("BLOCKED", r.prepareSynthetic(f.context(), f.candidate(), (ObjectNode) f.observation().path("transcriptModel")).snapshot().path("status").asText()); assertEquals(0, calls.get());
    }
    @ParameterizedTest @CsvSource({"SAME,CALIBRATED", "CDS,NOT_EVALUATED", "POSITION,NOT_EVALUATED", "MISSING,NOT_EVALUATED"})
    void crossVersionRequiresWholeCdsAndVariantPositionFromTrustedIndex(String change, String expected) throws Exception {
        var f = fixture.input("0.958", "GE", "0.7").fixture(); var calls = new AtomicInteger(); var r = runtime(calls, "0.99", "VERSION");
        var index = org.mockito.Mockito.mock(GencodeTranscriptIndex.class);
        var source = ((ObjectNode) f.observation().path("transcriptModel")).deepCopy().put("transcript", "ENST000001.99");
        if ("CDS".equals(change)) source.put("cdsSequence", "ATGAGATAA");
        if ("POSITION".equals(change)) ((ObjectNode) source.withArray("exons").get(0)).put("cdsStart", 2);
        var reader = org.mockito.Mockito.mock(CodingTranscriptMaterialSupply.ReferenceReader.class);
        if ("MISSING".equals(change)) org.mockito.Mockito.when(index.model(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenThrow(new java.io.IOException("missing"));
        else org.mockito.Mockito.when(index.model(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(source);
        org.mockito.Mockito.when(index.identity()).thenReturn(fixture.base.mapper.createObjectNode().put("manifestSha256", "a".repeat(64)));
        org.springframework.test.util.ReflectionTestUtils.setField(requireNonNull(r), "sourceIndex", index);
        org.springframework.test.util.ReflectionTestUtils.setField(requireNonNull(r), "reference", reader);
        var out = r.execute(r.prepareSynthetic(f.context(), f.candidate(), (ObjectNode) f.observation().path("transcriptModel")));
        assertEquals(expected, out.at("/protein/status").asText(), out.toPrettyString());
        if ("SAME".equals(change)) assertEquals("VERIFIED_SEQUENCE_EQUIVALENCE", out.at("/transcriptBridge/status").asText());
        else assertEquals("UNAVAILABLE", out.at("/transcriptBridge/status").asText());
    }
    @Test void sharedAlleleFactStillRechecksEachSelectedTranscriptAndOwnsCopies() throws Exception {
        var f = fixture.input("0.958", "GE", "0.7").fixture(); var calls = new AtomicInteger(); var r = runtime(calls, "0.99", "");
        var model = (ObjectNode) f.observation().path("transcriptModel");
        var first = r.execute(r.prepareSynthetic(f.context(), f.candidate(), model));
        assertEquals("CALIBRATED", first.at("/protein/status").asText());
        ((ObjectNode) first.at("/observation/rows/0")).put("rawScore", "0.01");
        var changed = f.context().deepCopy(); ((ObjectNode) changed.path("disease")).put("mondoId", "MONDO:0000002");
        changed.put("contextId", fixture.base.adapter.canonicalContextId(changed));
        var second = r.execute(r.prepareSynthetic(changed, f.candidate(), model));
        assertEquals("CALIBRATED", second.at("/protein/status").asText(), second.toPrettyString());
        assertEquals("0.99", second.at("/observation/rows/0/rawScore").asText());
        assertEquals(changed, second.at("/plan/context"));
        assertEquals(1, calls.get(), "same raw allele must not be read per ranking/context");
    }
    @Test void changedResourceEligibilityBlocksEvenWithACachedFact() throws Exception {
        var f = fixture.input("0.958", "GE", "0.7").fixture(); var calls = new AtomicInteger(); var r = runtime(calls, "0.99", "");
        var model = (ObjectNode) f.observation().path("transcriptModel");
        assertEquals("CALIBRATED", r.execute(r.prepareSynthetic(f.context(), f.candidate(), model)).at("/protein/status").asText());
        java.util.function.Function<ObjectNode, String> changed = request -> "PREDICTION_RESOURCE_FILE_CHANGED";
        org.springframework.test.util.ReflectionTestUtils.setField(r, "resourceCheck", changed);
        var second = r.execute(r.prepareSynthetic(f.context(), f.candidate(), model));
        assertEquals("PREDICTION_RESOURCE_FILE_CHANGED", second.at("/protein/reasonCode").asText());
        assertFalse(second.has("observation")); assertEquals(1, calls.get());
    }
    GeneralAlphaMissenseRuntime runtime(AtomicInteger calls, String score, String mutation) {
        var m = fixture.base.mapper; var resource = m.createObjectNode().put("model", "ALPHAMISSENSE");
        return new GeneralAlphaMissenseRuntime(m, resource, request -> {
            calls.incrementAndGet(); var a = request.at("/context/variant/evidenceQueryAllele"); var model = request.path("transcriptModel");
            var out = m.createObjectNode().put("status", "HIT"); out.set("resource", resource.deepCopy());
            out.putObject("queryAllele").put("genomeAssembly", a.path("assembly").asText()).put("chromosome", a.path("contig").asText())
                    .put("position", a.path("position").asLong()).put("reference", a.path("reference").asText()).put("alternate", a.path("alternate").asText());
            String tx = model.path("transcript").asText(), aa = "K2R";
            if ("VERSION".equals(mutation)) tx = tx.substring(0, tx.indexOf('.')) + ".99";
            if ("OTHER_TX".equals(mutation)) tx = "ENST999999.1";
            if ("AA".equals(mutation)) aa = "K3E";
            String raw = "chr" + a.path("contig").asText() + "\t" + a.path("position").asText() + "\t" + a.path("reference").asText()
                    + "\t" + a.path("alternate").asText() + "\thg38\tPTEST\t" + tx + "\t" + aa + "\t" + score + "\tignored_class";
            var rows = out.putArray("rows"); rows.addObject().put("sourceRow", raw).put("transcriptId", tx).put("proteinVariant", aa)
                    .put("uniprotId", "PTEST").put("rawScore", score).put("rawClass", "ignored_class");
            switch (mutation) {
                case "NO_RECORD" -> { out.put("status", "NO_RECORD"); rows.removeAll(); }
                case "MULTIPLE" -> rows.add(rows.get(0).deepCopy());
                case "RAW" -> ((ObjectNode) rows.get(0)).put("rawScore", "0.001");
                case "ALLELE" -> ((ObjectNode) out.path("queryAllele")).put("position", 999);
                case "RESOURCE" -> out.putObject("resource");
                default -> { }
            }
            return out;
        }, true);
    }
}
