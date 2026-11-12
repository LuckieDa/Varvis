package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses the trusted synthetic VCF/source/normalization fixture; transport below is deliberately synthetic. */
class Pm2LocalSelectedAdapterTest {
    @TempDir Path temporary;

    Pm2LocalExomesRuntime runtime(ObjectMapper mapper, AtomicInteger calls, List<NormalizedVariantKey> queried) throws Exception {
        VariantEvidenceProvider provider = new VariantEvidenceProvider() {
            public String source() { return "GNOMAD"; }
            public String version() { return "gnomAD exomes v4.1 GRCh38"; }
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants, AnalysisContext analysis) {
                calls.incrementAndGet(); variants.forEach(v -> queried.add(v.getObservation().getKey()));
                throw new IllegalStateException("SYNTHETIC_FAILED_QUERY");
            }
        };
        var constructor = Pm2LocalExomesRuntime.class.getDeclaredConstructor(ObjectMapper.class, Pm2LocalExomesProfile.class, VariantEvidenceProvider.class);
        constructor.setAccessible(true);
        return constructor.newInstance(mapper, Pm2LocalExomesProfile.load(mapper), provider);
    }

    @Test void changedQueryUsesPreparedCopyOnceAcrossBackgroundsAndKeepsFormalCandidate() throws Exception {
        var fixture = new SelectedEvidencePreparationTest(); fixture.temporary = temporary;
        var p = fixture.prepare(false, false, true, 1);
        var calls = new AtomicInteger(); var queried = new java.util.ArrayList<NormalizedVariantKey>();
        var runtime = runtime(p.mapper(), calls, queried);
        var batch = runtime.execute(p.result(), new AnalysisContext(p.result().assembly().analysisId(), GenomeAssembly.GRCh38, AnalysisMode.WES), "a".repeat(64));
        assertEquals(2, batch.path("results").size(), batch.toPrettyString());
        assertEquals(1, calls.get()); assertEquals(1, runtime.providerCalls()); assertEquals(2, runtime.evaluatorCalls());
        assertEquals(1, queried.get(0).getPosition()); assertEquals("AT", queried.get(0).getAlternate());
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
        var one = batch.path("results").get(0); var two = batch.path("results").get(1);
        assertEquals(one.path("observation"), two.path("observation"));
        assertNotEquals(one.at("/evaluation/evaluationId"), two.at("/evaluation/evaluationId"));
        assertEquals("FAILED", one.at("/evidence/resourceExecution/status").asText());
        assertEquals("NOT_QUERIED", one.at("/evidence/queryOutcome/status").asText());
        assertEquals(0, batch.path("allSitesCalls").asInt());
    }

    @Test void sourceOrNormalizationBlockersNeverQueryOriginalCoordinatesOrReplaceSelectedCandidate() throws Exception {
        for (boolean sourceFailure : List.of(false, true)) {
            var fixture = new SelectedEvidencePreparationTest();
            fixture.temporary = java.nio.file.Files.createDirectory(temporary.resolve(sourceFailure ? "source" : "normalization"));
            var p = fixture.prepare(!sourceFailure, sourceFailure, true, 2);
            var calls = new AtomicInteger(); var runtime = runtime(p.mapper(), calls, new java.util.ArrayList<>());
            var batch = runtime.execute(p.result(), new AnalysisContext(p.result().assembly().analysisId(), GenomeAssembly.GRCh38, AnalysisMode.WES), "a".repeat(64));
            assertEquals(0, calls.get()); assertEquals(0, runtime.evaluatorCalls());
            assertEquals(1, batch.at("/preparation/selectedCount").asInt());
            assertEquals(2, batch.path("results").size());
            assertEquals(sourceFailure ? "SYNTHETIC_SOURCE_CHANGED" : "NORMALIZATION_TOOL_UNAVAILABLE",
                    batch.at("/results/0/evaluationResult/reasonCode").asText());
        }
    }

    @Test void sealedSharedObservationIsVerifiedBeforeAnyProviderAndNeverCarriesAnotherContextDecision() throws Exception {
        var fixture = new SelectedEvidencePreparationTest(); fixture.temporary = temporary;
        var p = fixture.prepare(false, false, false, 1); var id = p.original().getCandidateId();
        var calls = new AtomicInteger(); var first = runtime(p.mapper(), calls, new java.util.ArrayList<>());
        var analysis = new AnalysisContext(p.result().assembly().analysisId(), GenomeAssembly.GRCh38, AnalysisMode.WES);
        var a = first.evaluate(p.result(), id, "context-one", analysis, "a".repeat(64), null);
        var consumerCalls = new AtomicInteger(); var consumer = runtime(p.mapper(), consumerCalls, new java.util.ArrayList<>());
        var b = consumer.evaluate(p.result(), id, "context-two", analysis, "a".repeat(64), a.path("observation"));
        assertEquals(0, consumerCalls.get()); assertEquals(1, consumer.evaluatorCalls());
        assertEquals(a.path("observation"), b.path("observation"));
        assertNotEquals(a.at("/evaluation/evaluationId"), b.at("/evaluation/evaluationId"));
        var corrupt = ((com.fasterxml.jackson.databind.node.ObjectNode) a.path("observation")).deepCopy().put("sourceSnapshot", "b".repeat(64));
        var blocked = consumer.evaluate(p.result(), id, "context-one", analysis, "a".repeat(64), corrupt);
        assertEquals("POPULATION_OBSERVATION_BINDING_INVALID", blocked.at("/evaluationResult/reasonCode").asText());
        assertEquals(0, consumerCalls.get()); assertEquals(1, consumer.evaluatorCalls());
    }

    @Test void wrongAnalysisAndMissingContextKeepExplicitRowsWithoutQuerying() throws Exception {
        var fixture = new SelectedEvidencePreparationTest(); fixture.temporary = temporary;
        var p = fixture.prepare(false, false, false, 1);
        var calls = new AtomicInteger(); var runtime = runtime(p.mapper(), calls, new java.util.ArrayList<>());
        var batch = runtime.execute(p.result(), new AnalysisContext("wrong-analysis", GenomeAssembly.GRCh38, AnalysisMode.WES), "a".repeat(64));
        assertEquals(2, batch.path("results").size());
        for (var row : batch.path("results"))
            assertEquals("PM2_ANALYSIS_ID_MISMATCH", row.at("/evaluationResult/reasonCode").asText());
        assertEquals(0, calls.get()); assertEquals(0, runtime.evaluatorCalls());

        var original = p.result().assembly();
        var noAttempts = new ExplicitEvidenceContextAssembler.AssemblyResult(original.analysisId(), original.requestedN(),
                original.selectedCount(), original.candidateIds(),
                List.of(new ExplicitEvidenceContextAssembler.CandidateAssembly(p.original().getCandidateId(), List.of())),
                original.unmatchedInputs(), original.candidateOrderingChanged());
        org.springframework.test.util.ReflectionTestUtils.setField(requireNonNull(p.result()), "assembly", noAttempts);
        var unavailable = runtime.execute(p.result(), new AnalysisContext(original.analysisId(), GenomeAssembly.GRCh38, AnalysisMode.WES), "a".repeat(64));
        assertEquals(1, unavailable.path("results").size());
        assertEquals(p.original().getCandidateId(), unavailable.at("/results/0/candidateId").asText());
        assertTrue(unavailable.at("/results/0/hypothesisId").isNull());
        assertEquals("SELECTED_CONTEXT_UNAVAILABLE", unavailable.at("/results/0/evaluationResult/reasonCode").asText());
        assertEquals(0, calls.get()); assertEquals(0, runtime.evaluatorCalls());
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
    }
}
