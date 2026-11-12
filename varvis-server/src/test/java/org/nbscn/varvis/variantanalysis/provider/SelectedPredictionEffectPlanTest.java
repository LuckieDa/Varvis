package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.nbscn.varvis.variantanalysis.service.PredictionEffectReferences;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic scores/contexts exercise the real saved-effect resolver; no resource or database access. */
class SelectedPredictionEffectPlanTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final AnalysisContext analysis = new AnalysisContext("prediction-effect-plan-fixture", GenomeAssembly.GRCh38, AnalysisMode.WES);
    @TempDir Path temporary;

    @Test void realEffectResolverKeepsExactQueryAndCanonicalResultsForTwoBackgrounds() throws Exception {
        var candidate = missense();
        var revel = revel();
        var runtime = runtime(revel, List.of(candidate));
        var batch = runtime.prepare(selected(List.of(candidate), 2), analysis);
        assertEquals(0, revel.providerCalls());
        // Before the repair the production resolver attaches MissingNode, and execute throws at resultId.
        var result = assertDoesNotThrow(() -> runtime.execute(batch));
        assertEquals(2, result.path("results").size());
        for (var row : result.path("results")) {
            var query = row.at("/plan/context/variant/evidenceQueryAllele");
            assertEquals(query, row.at("/plan/queryAllele"));
            assertEquals(query, row.at("/plan/effectRestrictions/scope/queryAllele"));
            assertEquals("VERIFIED_SAVED_REFERENCES", row.at("/plan/effectRestrictions/status").asText());
            assertEquals("MET", row.at("/criteria/PP3/status").asText());
            assertEquals("SUPPORTING", row.at("/criteria/PP3/appliedStrength").asText());
            var identity = ((ObjectNode) row).deepCopy(); identity.remove("resultId");
            assertEquals("selected-prediction-sha256-" + adapter.canonicalSha256(identity), row.path("resultId").asText());
        }
        assertNotEquals(result.at("/results/0/plan/hypothesisId"), result.at("/results/1/plan/hypothesisId"));
        int calls = revel.providerCalls();
        assertEquals(result, runtime.execute(batch));
        assertEquals(calls, revel.providerCalls());
    }

    @Test void fixedTopTwoShapePreservesStopBlockerAndLaterMissenseResult() throws Exception {
        var candidates = List.of(stop(), missense());
        var revel = revel(); var runtime = runtime(revel, candidates);
        var result = assertDoesNotThrow(() -> runtime.execute(runtime.prepare(selected(candidates, 1), analysis)));
        assertEquals(2, result.path("results").size());
        assertEquals("exomiser-v1-00000001", result.at("/results/0/plan/candidateId").asText());
        assertEquals("PREDICTION_CONSEQUENCE_OUTSIDE_LOCAL_SCOPE", result.at("/results/0/criteria/PP3/reasonCode").asText());
        assertEquals("NOT_EVALUATED", result.at("/results/0/criteria/PP3/status").asText());
        assertEquals("exomiser-v1-00000003", result.at("/results/1/plan/candidateId").asText());
        assertEquals("MET", result.at("/results/1/criteria/PP3/status").asText());
        assertEquals("NOT_MET", result.at("/results/1/criteria/BP4/status").asText());
        assertEquals(1, revel.providerCalls());
        assertEquals(0, result.path("sharedSpliceProviderCalls").asInt());
    }

    private SelectedPredictionRuntime runtime(Hnf1aRevelRuntime revel, List<VariantInterpretation> candidates) throws Exception {
        // Build the immutable snapshot without capture(): resolve() itself is the production implementation.
        var effects = new PredictionEffectReferences(mapper, null, temporary,
                (analysisId, runId) -> { throw new AssertionError("No database or saved run read is allowed"); });
        var body = mapper.createObjectNode().put("analysisId", analysis.getAnalysisId()).put("coverage", "COMPLETE_FOR_DECLARED_SCOPE")
                .put("sourceStore", "ACMG_BUSINESS_RUNS_AND_FUNCTIONAL_EXPERIMENT_REVIEWS").put("externalDeclaration", "UNSPECIFIED")
                .put("snapshotSha256", "a".repeat(64)).put("readAt", "2026-10-10T00:00:00Z");
        var ids = body.putArray("candidateIds"); candidates.forEach(c -> ids.add(c.getCandidateId())); body.putArray("entries");
        var constructor = PredictionEffectReferences.Snapshot.class.getDeclaredConstructor(PredictionEffectReferences.class, ObjectNode.class);
        constructor.setAccessible(true);
        var snapshot = constructor.newInstance(effects, body);
        return new SelectedPredictionRuntime(mapper, mock(PredictionDevelopmentRuntime.class), revel, null, null,
                new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(mapper), snapshot::resolve);
    }

    private SelectedEvidencePreparation selected(List<VariantInterpretation> candidates, int backgrounds) throws Exception {
        var selected = mock(SelectedEvidencePreparation.class);
        var assemblies = new ArrayList<CandidateAssembly>();
        for (var candidate : candidates) {
            var attempts = new ArrayList<Attempt>();
            ObjectNode context = ReflectionTestUtils.invokeMethod(requireNonNull(fixture()), "context", candidate); assertNotNull(context);
            for (int i = 0; i < backgrounds; i++) {
                String hypothesis = "synthetic-background-" + i;
                var projection = mock(LegacyContextProjection.class);
                var snapshot = mapper.createObjectNode().put("candidateId", candidate.getCandidateId()).put("hypothesisId", hypothesis)
                        .put("fixture", "SYNTHETIC_PROJECTION_ONLY");
                snapshot.set("evaluationContext", context); snapshot.put("bindingSha256", adapter.canonicalSha256(snapshot));
                when(projection.context()).thenAnswer(ignored -> context.deepCopy()); when(projection.queryCandidate()).thenReturn(candidate);
                when(projection.snapshot()).thenAnswer(ignored -> snapshot.deepCopy());
                when(projection.bindingSha256()).thenReturn(snapshot.path("bindingSha256").asText());
                when(selected.legacyProjection(candidate.getCandidateId(), hypothesis)).thenReturn(projection);
                attempts.add(new Attempt(candidate.getCandidateId(), hypothesis, null, null, null, context, List.of()));
            }
            assemblies.add(new CandidateAssembly(candidate.getCandidateId(), attempts));
        }
        when(selected.assembly()).thenReturn(new AssemblyResult(analysis.getAnalysisId(), candidates.size(), candidates.size(),
                candidates.stream().map(mappedItem -> mappedItem.getCandidateId()).toList(), assemblies, List.of(), false));
        return selected;
    }
    private Hnf1aRevelRuntimeTest fixture() { var fixture = new Hnf1aRevelRuntimeTest(); fixture.directory = temporary; return fixture; }
    private Hnf1aRevelRuntime revel() {
        // Deliberately synthetic score; the coordinate shape does not make this a real scientific replay.
        Hnf1aRevelRuntime runtime = ReflectionTestUtils.invokeMethod(requireNonNull(fixture()), "fromRows", List.of("12,121416581,120978778,A,G,K,E,0.9,ENST00000257555"));
        assertNotNull(runtime); return runtime;
    }
    private VariantInterpretation missense() { return candidate(3, 120978778, "A", "G", "10A>G", "Lys4Glu", "missense_variant", "1/10", "4", "K/E", "Aaa/Gaa"); }
    private VariantInterpretation stop() { return candidate(1, 120989017, "C", "T", "511C>T", "Arg171Ter", "stop_gained", "2/10", "171", "R/*", "Cga/Tga"); }
    private VariantInterpretation candidate(int ordinal, int position, String ref, String alt, String coding, String protein,
            String consequence, String exon, String aminoPosition, String aminoAcids, String codons) {
        var key = new NormalizedVariantKey("GRCh38", "12", position, ref, alt);
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key)
                .withCandidateIdentity(CandidateIdentity.exomiser(key.canonicalId(), ordinal, "HNF1A", "AUTOSOMAL_DOMINANT", ordinal));
        candidate.applyTranscriptAnnotations(List.of(new TranscriptConsequence("HNF1A", "ENSG00000135100", "ENST00000257555",
                "ENST00000257555.11:c." + coding, "ENSP00000257555.5:p." + protein, consequence, "ENSG00000135100", "ENST00000257555",
                "protein_coding", "MODERATE", exon, null, true, true, false, aminoPosition, aminoAcids, codons,
                null, null, null, "VEP", "COMPLETED", "116.0", Instant.parse("2026-10-09T06:09:29Z"))));
        return candidate;
    }
}
