package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual selected/native/application code with synthetic projection, reference and model transport. */
class Bp7PredictionDependencyTest {
    @TempDir Path directory;
    final NativeSpliceAiRuntimeTest fixture = new NativeSpliceAiRuntimeTest();
    final ObjectMapper mapper = fixture.f.mapper;
    final RuntimeEvidenceV2Adapter adapter = fixture.f.adapter;
    final Bp7PredictionDependency dependency = new Bp7PredictionDependency(mapper);
    record Saved(ObjectNode plan, VariantInterpretation candidate, ObjectNode row, AtomicInteger calls) { }

    Saved saved(String score, String consequence, boolean duplicateEffect) throws Exception {
        return saved(score, consequence, duplicateEffect, false);
    }
    Saved saved(String score, String consequence, boolean duplicateEffect, boolean bp4Only) throws Exception {
        fixture.temporary = directory;
        var original = fixture.input();
        var tx = (ObjectNode) mapper.valueToTree(original.candidate().getTranscriptAnnotations().get(0));
        tx.put("consequence", consequence).put("hgvsCoding", original.candidate().getTranscriptAnnotations().get(0).getVersionedTranscriptId()
                + ("intron_variant".equals(consequence) ? ":c.100+25A>G" : ":c.100A>G"));
        original.candidate().applyTranscriptAnnotations(List.of(mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .treeToValue(tx, TranscriptConsequence.class)));
        if ("intron_variant".equals(consequence)) {
            var exons = original.model().putArray("exons");
            exons.addObject().put("genomicStart", 19000).put("genomicEnd", 19975);
            exons.addObject().put("genomicStart", 20025).put("genomicEnd", 21000);
        }
        var fact = fixture.fact(original);
        if ("intron_variant".equals(consequence)) {
            var exons = ((ObjectNode) fact.at("/annotationRows/0")).putArray("exons");
            exons.addArray().add(19000).add(19975); exons.addArray().add(20025).add(21000);
        }
        ((ObjectNode) fact.at("/annotations/0/deltaScores")).put("DS_AG", score);
        var calls = new AtomicInteger();
        var nativeRuntime = fixture.runtime(original, fact, calls, "HGNC:99999");
        var projection = mock(LegacyContextProjection.class);
        var source = mapper.createObjectNode().put("schemaVersion", "selected-legacy-context-projection-v1")
                .put("candidateId", original.candidate().getCandidateId()).put("hypothesisId", "bp7-synthetic")
                .put("fixtureAttribution", "SYNTHETIC_PROJECTION_REFERENCE_AND_TRANSPORT");
        source.set("evaluationContext", original.context()); source.put("bindingSha256", adapter.canonicalSha256(source));
        when(projection.context()).thenAnswer(i -> original.context().deepCopy());
        when(projection.queryCandidate()).thenReturn(original.candidate());
        when(projection.snapshot()).thenAnswer(i -> source.deepCopy());
        when(projection.bindingSha256()).thenReturn(source.path("bindingSha256").asText());
        var selected = mock(SelectedEvidencePreparation.class);
        var analysis = new AnalysisContext("bp7-offline", GenomeAssembly.GRCh38, AnalysisMode.WES);
        String id = original.candidate().getCandidateId();
        when(selected.legacyProjection(id, "bp7-synthetic")).thenReturn(projection);
        when(selected.assembly()).thenReturn(new AssemblyResult(analysis.getAnalysisId(), 1, 1, List.of(id),
                List.of(new CandidateAssembly(id, List.of(new Attempt(id, "bp7-synthetic", null, null, null, original.context(), List.of())))), List.of(), false));
        var development = new PredictionDevelopmentRuntime(mapper, PredictionDevelopmentRuntime.load(mapper, "prediction-core-policy-v1.json"),
                PredictionDevelopmentRuntime.load(mapper, "prediction-am-calibration-v1.json"), mapper.createObjectNode(),
                ignored -> { throw new AssertionError("BP7 must not query a missense predictor"); }, false);
        var runtime = new SelectedPredictionRuntime(mapper, development, Hnf1aRevelRuntime.unavailable(mapper), null, null,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper), plan -> {
                    var restrictions = PredictionCoreTest.restrictions(mapper, plan);
                    if (duplicateEffect) restrictions.withArray("references").addObject().put("criterionId", "ACMG_AMP_2015:BP4")
                            .put("mechanism", "SPLICING").put("savedEvidenceId", "saved-bp4").put("effectId", "same-effect").put("action", "BLOCK_DUPLICATE");
                    return restrictions;
                }, null, null, nativeRuntime);
        var batch = runtime.prepare(selected, analysis);
        var prepared = (ObjectNode) batch.snapshot().at("/plans/0");
        assertEquals(0, calls.get());
        var result = bp4Only ? runtime.execute(batch, List.of("ACMG_AMP_2015:BP4")) : runtime.execute(batch);
        assertEquals(result, bp4Only ? runtime.execute(batch, List.of("ACMG_AMP_2015:BP4")) : runtime.execute(batch)); assertEquals(1, calls.get(), result.toPrettyString());
        assertEquals(0, result.path("developmentProviderCalls").asInt());
        return new Saved(prepared, original.candidate(), (ObjectNode) result.at("/results/0"), calls);
    }
    ObjectNode inspect(Saved s) { return dependency.inspect(s.plan(), s.candidate(), s.row()); }
    void seal(ObjectNode row) {
        row.remove("resultId"); row.put("resultId", "selected-prediction-sha256-" + adapter.canonicalSha256(row));
    }
    void refused(ObjectNode result, String reason) {
        assertEquals("NOT_EVALUATED", result.path("status").asText(), result.toPrettyString());
        assertEquals(reason, result.path("reasonCode").asText(), result.toPrettyString());
        assertFalse(result.path("finalEvaluationProduced").asBoolean());
    }
    @ParameterizedTest @ValueSource(strings={"0.0", "0.15", "0.9"})
    void bp4OnlyDependencyReplaysWithSameOutcomeAsFullPrediction(String score) throws Exception {
        var full = saved(score, "synonymous_variant", false);
        var one = saved(score, "synonymous_variant", false, true);
        assertEquals(1, one.row().path("criteria").size());
        assertEquals(full.row().at("/criteria/BP4"), one.row().at("/criteria/BP4"));
        assertEquals(inspect(full).path("status"), inspect(one).path("status"));
        assertEquals(inspect(full).path("reasonCode"), inspect(one).path("reasonCode"));
    }
    @ParameterizedTest @CsvSource({
            "0.00,READY_FOR_POSITION_AND_CONFLICT_CHECKS,BP7_SAME_SOURCE_BP4_VALIDATED",
            "0.1,READY_FOR_POSITION_AND_CONFLICT_CHECKS,BP7_SAME_SOURCE_BP4_VALIDATED",
            "0.10000000000000001,DEPENDENCY_NOT_SATISFIED,BP7_SPLICE_SCORE_UNINFORMATIVE",
            "0.19999999999999999,DEPENDENCY_NOT_SATISFIED,BP7_SPLICE_SCORE_UNINFORMATIVE",
            "0.2,DEPENDENCY_NOT_SATISFIED,BP7_SPLICE_NOT_BENIGN",
            "1.0,DEPENDENCY_NOT_SATISFIED,BP7_SPLICE_NOT_BENIGN"})
    void actualSelectedResultKeepsExactThresholdsWithoutNewExecution(String score, String status, String reason) throws Exception {
        var s = saved(score, "synonymous_variant", false);
        byte[] row = mapper.writeValueAsBytes(s.row()), plan = mapper.writeValueAsBytes(s.plan()), candidate = mapper.writeValueAsBytes(s.candidate());
        var result = inspect(s);
        assertEquals(status, result.path("status").asText(), result.toPrettyString());
        assertEquals(reason, result.path("reasonCode").asText());
        assertEquals("NONE", result.path("appliedStrength").asText());
        assertFalse(result.path("finalEvaluationProduced").asBoolean());
        assertEquals("PENDING", result.path("positionCheck").asText());
        assertEquals("PENDING", result.path("experimentalConflictCheck").asText());
        assertTrue(result.at("/dependency/sharedPrediction").asBoolean());
        assertEquals(result, inspect(s)); assertEquals(1, s.calls().get());
        assertArrayEquals(row, mapper.writeValueAsBytes(s.row())); assertArrayEquals(plan, mapper.writeValueAsBytes(s.plan()));
        assertArrayEquals(candidate, mapper.writeValueAsBytes(s.candidate()));
        var restored = (ObjectNode) mapper.readTree(row);
        assertArrayEquals(mapper.writeValueAsBytes(result), mapper.writeValueAsBytes(dependency.inspect(s.plan(), s.candidate(), restored)));
        String destination = System.getProperty("bp7.evidence.dir");
        if (destination != null) java.nio.file.Files.write(Path.of(destination).resolve("dependency-" + score + ".json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
    }
    @Test void intronicDependencyDoesNotPreemptPositionChecks() throws Exception {
        var s = saved("0.0", "intron_variant", false);
        assertEquals("READY_FOR_POSITION_AND_CONFLICT_CHECKS", inspect(s).path("status").asText(), inspect(s).toPrettyString());
    }
    @Test void alreadyAdoptedEffectCannotBeReusedAsBp7Support() throws Exception {
        var result = inspect(saved("0.0", "synonymous_variant", true));
        refused(result, "BP7_BP4_NOT_EVALUATED");
        assertEquals("SAME_EFFECT_ALREADY_ADOPTED", result.path("dependencyReasonCode").asText());
    }
    @ParameterizedTest @CsvSource({
            "SCORE,BP7_ASSOCIATED_RECORD_MISMATCH", "CALIBRATION,BP7_SPLICE_CALIBRATION_MISMATCH",
            "BP4,BP7_SAVED_BP4_REPLAY_MISMATCH", "PROJECTION,BP7_SOURCE_PROJECTION_MISMATCH",
            "ALLELE,BP7_NATIVE_OBSERVATION_ALLELE_MISMATCH", "QUERY_ID,BP7_NATIVE_OBSERVATION_ALLELE_MISMATCH",
            "WINDOW,BP7_REFERENCE_WINDOW_MISMATCH", "NO_FACT,BP7_NATIVE_OBSERVATION_UNAVAILABLE",
            "TEXT_DP,BP7_NATIVE_SCORE_FIELDS_INVALID", "MISSING_SCORE,BP7_NATIVE_SCORE_FIELDS_INVALID",
            "STRING_EXACT,BP7_NATIVE_OBSERVATION_UNAVAILABLE", "RECORD,BP7_ASSOCIATED_RECORD_MISMATCH"})
    void resealedCorruptionStillFailsTheIndependentReplay(String change, String reason) throws Exception {
        var s = saved("0.0", "synonymous_variant", false); var row = s.row();
        switch (change) {
            case "SCORE" -> ((ObjectNode) row.at("/nativeSpliceResult/observation/annotations/0/deltaScores")).put("DS_AG", "0.9");
            case "CALIBRATION" -> ((ObjectNode) row.at("/mechanisms/SPLICING")).put("rawScore", "0.2");
            case "BP4" -> ((ObjectNode) row.at("/criteria/BP4")).put("appliedStrength", "STRONG");
            case "PROJECTION" -> ((ObjectNode) row.path("sourceProjection")).put("candidateId", "other");
            case "ALLELE" -> ((ObjectNode) row.at("/nativeSpliceResult/observation/allele")).put("position", 20001);
            case "QUERY_ID" -> ((ObjectNode) row.at("/nativeSpliceResult/observation")).put("queryId", "other");
            case "WINDOW" -> ((ObjectNode) row.at("/nativeSpliceResult/observation/referenceWindow")).put("sha256", "b".repeat(64));
            case "NO_FACT" -> ((ObjectNode) row.path("nativeSpliceResult")).remove("observation");
            case "TEXT_DP" -> ((ObjectNode) row.at("/nativeSpliceResult/observation/annotations/0/deltaPositions")).put("DP_AG", "1.1");
            case "MISSING_SCORE" -> ((ObjectNode) row.at("/nativeSpliceResult/observation/annotations/0/deltaScores")).remove("DS_AG");
            case "STRING_EXACT" -> ((ObjectNode) row.at("/nativeSpliceResult/observation")).put("exactAlleleMatch", "true");
            case "RECORD" -> ((ObjectNode) row.at("/nativeSpliceResult/associatedRecord")).put("transcriptId", "ENST00000000001.99");
        }
        seal(row); refused(inspect(s), reason); assertEquals(1, s.calls().get());
    }
    @ParameterizedTest @ValueSource(strings = {"version", "method", "calibrationId", "scoreMode", "distance", "mask"})
    void changedNativeMethodCannotSilentlyReuseOldCalibration(String field) throws Exception {
        var s = saved("0.0", "synonymous_variant", false);
        ((ObjectNode) s.plan().at("/nativeSplicePreparation/identity")).put(field, "other");
        s.row().set("plan", s.plan().deepCopy());
        ((ObjectNode) s.row().path("nativeSpliceResult")).set("plan", s.plan().path("nativeSplicePreparation").deepCopy());
        seal(s.row()); refused(inspect(s), "BP7_NATIVE_METHOD_INCOMPATIBLE");
    }
    @ParameterizedTest @ValueSource(strings = {"candidateId", "hypothesisId", "context"})
    void anotherServerPreparedIdentityCannotBorrowSavedResult(String field) throws Exception {
        var s = saved("0.0", "synonymous_variant", false);
        ((ObjectNode) s.row().path("plan")).put(field, "other"); seal(s.row());
        refused(inspect(s), "BP7_PREPARED_PLAN_MISMATCH");
    }
    @Test void proteinOrUnsupportedConsequencesNeverBecomeBp7() throws Exception {
        var s = saved("0.0", "synonymous_variant", false);
        for (String consequence : List.of("missense_variant", "5_prime_UTR_variant", "synonymous_variant&stop_gained", "splice_donor_variant")) {
            var tx = (ObjectNode) mapper.valueToTree(s.candidate().getTranscriptAnnotations().get(0)); tx.put("consequence", consequence);
            s.candidate().applyTranscriptAnnotations(List.of(mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).treeToValue(tx, TranscriptConsequence.class)));
            assertEquals("NOT_APPLICABLE", inspect(s).path("status").asText());
        }
    }
    @ParameterizedTest @CsvSource({
            "SPECIALIZED,BP7_SPECIALIZED_OR_OTHER_ROUTE_REQUIRES_ADAPTER",
            "PROTEIN_PLAN,BP7_SPLICING_BP4_PLAN_REQUIRED",
            "BLOCKED,BP7_PREDICTION_PREPARATION_BLOCKED",
            "REFERENCE,BP7_REFERENCE_BINDING_INVALID",
            "ANNOTATION,BP7_SELECTED_ANNOTATION_MISMATCH"})
    void unavailableOrIncompatiblePreparedSupplyNeverApprovesReuse(String change, String reason) throws Exception {
        var s = saved("0.0", "synonymous_variant", false);
        switch (change) {
            case "SPECIALIZED" -> s.plan().put("route", "REVIEWED_POLICY");
            case "PROTEIN_PLAN" -> s.plan().putArray("requiredBp4Mechanisms").add("PROTEIN_MISSENSE");
            case "BLOCKED" -> ((ObjectNode) s.plan().path("developmentPreparation")).put("status", "BLOCKED");
            case "REFERENCE" -> ((ObjectNode) s.plan().at("/nativeSplicePreparation/referenceWindow/qualifiedReference")).put("expectedSha256", "b".repeat(64));
            case "ANNOTATION" -> ((ObjectNode) s.plan().at("/nativeSplicePreparation/annotation")).put("transcript", "ENST00000000001.99");
        }
        s.row().set("plan", s.plan().deepCopy());
        ((ObjectNode) s.row().path("nativeSpliceResult")).set("plan", s.plan().path("nativeSplicePreparation").deepCopy());
        seal(s.row()); refused(inspect(s), reason);
    }
    @Test void binaryFloatingPointIdentityIsRejectedBeforeAnyAdoption() throws Exception {
        var s = saved("0.0", "synonymous_variant", false);
        ((ObjectNode) s.row().at("/nativeSpliceResult/observation/annotations/0/deltaPositions")).put("DP_AG", 1.1);
        refused(inspect(s), "BP7_DEPENDENCY_INVALID");
    }
    @Test void unsealedOrMissingDependenciesAndUnselectedTranscriptAreExplicit() throws Exception {
        var s = saved("0.0", "synonymous_variant", false);
        refused(dependency.inspect(null, s.candidate(), s.row()), "BP7_PREPARED_CONTEXT_REQUIRED");
        refused(dependency.inspect(s.plan(), null, s.row()), "BP7_PREPARED_CONTEXT_REQUIRED");
        refused(dependency.inspect(s.plan(), s.candidate(), null), "BP7_SAVED_PREDICTION_REQUIRED");
        s.row().put("resultId", "forged"); refused(inspect(s), "BP7_SAVED_PREDICTION_IDENTITY_INVALID");
        s.candidate().applyTranscriptAnnotations(List.of()); refused(inspect(s), "BP7_SELECTED_TRANSCRIPT_NOT_UNIQUE");
        var before = dependency.policy(); dependency.policy().put("version", "changed"); assertEquals(before, dependency.policy());
    }
}
