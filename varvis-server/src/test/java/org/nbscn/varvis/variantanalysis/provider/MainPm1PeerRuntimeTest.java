package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual sequence/source/PM1 and batch orchestration; peer transports/outcomes and source
 * clauses are explicit engineering fixtures. This is not publisher or clinical qualification. */
class MainPm1PeerRuntimeTest {
    @TempDir Path root;
    private static final String SOURCE = "a".repeat(64), PM1 = "ACMG_AMP_2015:PM1", PM2 = "ACMG_AMP_2015:PM2";
    private GeneralPm1RuntimeTest f;
    private SelectedEvidencePreparation selected;
    private String candidate;
    private ObjectNode context, projection;
    private final AnalysisContext analysis = new AnalysisContext("main-pm1-peer", GenomeAssembly.GRCh38, AnalysisMode.WES);
    private final AtomicInteger pm2Factories = new AtomicInteger(), predictionFactories = new AtomicInteger();
    private Pm2LocalExomesRuntime population;
    private SelectedPredictionRuntime predicted;

    private void setup(String criterion) throws Exception {
        f = new GeneralPm1RuntimeTest(); f.root = root; f.setup();
        context = f.sequence.input.fixture().context(); candidate = f.sequence.input.fixture().candidate().getCandidateId();
        var descriptor = (ObjectNode) f.doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent/strengthDescriptor/0");
        if ("PM2".equals(criterion)) descriptor.put("text", "Located in a mutational hot spot and/or critical and well-established functional domain (_e.g._ active site of an enzyme) without benign variation "
                + "* The variant must be rare (meeting PM2_Supporting) in order to be considered for PM1. "
                + "* Met by a rare variant within the pore helix (amino acids 2 to 2). This region is known to be a critical region of SYNTH1 and has been confirmed to show an absence of likely benign or benign variants listed in gnomAD.");
        else if ("PP3".equals(criterion)) descriptor.put("text", "* Active site residues in SYNTH1 include: Lys2 * Substrate binding residues in SYNTH1 are: Lys2 "
                + "* Cofactor binding residues in SYNTH1 are: Lys2 * Do not apply if PP3_Strong applies");
        String pointer = "/ld/CriteriaCode/0/entContent/strengthDescriptor/0", pin = f.adapter.canonicalSha256(descriptor);
        ((ObjectNode) f.doc.at("/records/0/interpretation/rules/0")).put("sourceSha256", pin);
        if ("PM5".equals(criterion)) {
            ObjectNode pm5Descriptor = org.springframework.test.util.ReflectionTestUtils.invokeMethod(new PublishedPm1Pm5ExclusionTest(), "descriptor");
            assertNotNull(pm5Descriptor);
            var original = (ObjectNode) f.doc.at("/specifications/0/original");
            int pm5Index = original.withArray("/ld/CriteriaCode").size();
            var pm5 = original.withArray("/ld/CriteriaCode").addObject().putObject("entContent").put("label", "PM5")
                    .put("applicability", "Applicable").put("instructionsToUse", "").put("additionalComments", "");
            pm5.set("gene", original.at("/ld/CriteriaCode/0/entContent/gene").deepCopy()); pm5.putArray("strengthDescriptor").add(pm5Descriptor);
            pointer = "/ld/CriteriaCode/" + pm5Index + "/entContent/strengthDescriptor/0"; pin = f.adapter.canonicalSha256(pm5Descriptor);
        }
        ((ObjectNode) f.doc.at("/records/0/interpretation")).putArray("crossCriterionRelations").addObject()
                .put("criterionId", "ACMG_AMP_2015:" + criterion).put("relation", "PM2".equals(criterion) ? "REQUIRE_MET_AT_LEAST" : "EXCLUDE_IF_MET_AT_LEAST")
                .put("minimumStrength", "PM2".equals(criterion) ? "SUPPORTING" : "STRONG").put("sourcePointer", pointer).put("sourceSha256", pin);
        new PublishedCriterionKnowledgeTest().refresh(f.doc);
        selected = mock(SelectedEvidencePreparation.class);
        when(selected.assembly()).thenReturn(new AssemblyResult(analysis.getAnalysisId(), 1, 1, List.of(candidate),
                List.of(new CandidateAssembly(candidate, List.of(new Attempt(candidate, "background", null, null, null, context, List.of())))), List.of(), false));
        when(selected.sources()).thenReturn(List.of()); when(selected.provenance()).thenReturn(f.mapper.createObjectNode());
        when(selected.formalCandidate(candidate)).thenReturn(f.mapper.valueToTree(f.sequence.input.fixture().candidate()));
        when(selected.context(candidate, "background")).thenAnswer(c -> context.deepCopy());
        projection = f.mapper.createObjectNode().put("schemaVersion", "selected-legacy-context-projection-v1")
                .put("candidateId", candidate).put("hypothesisId", "background");
        projection.set("originalContext", context.deepCopy()); projection.set("evaluationContext", context.deepCopy());
        projection.put("bindingSha256", f.adapter.canonicalSha256(projection));
        var projected = mock(LegacyContextProjection.class);
        when(projected.snapshot()).thenAnswer(c -> projection.deepCopy()); when(projected.context()).thenAnswer(c -> context.deepCopy());
        when(projected.queryCandidate()).thenReturn(f.sequence.input.fixture().candidate());
        when(selected.legacyProjection(candidate, "background")).thenReturn(projected);
        population = mock(Pm2LocalExomesRuntime.class); when(population.profile()).thenReturn(f.mapper.createObjectNode());
    }
    private ObjectNode population(String status, String strength, String fault) {
        var out = f.mapper.createObjectNode().put("candidateId", candidate).put("hypothesisId", "background").put("executionSourceSha256", SOURCE);
        out.set("context", context.deepCopy());
        out.putObject("evaluation").put("criterionId", PM2).put("contextId", context.path("contextId").asText()).put("appliedStrength", strength)
                .putObject("evaluationResult").put("status", status).put("reasonCode", "SYNTHETIC_PEER_FINAL");
        if ("SOURCE".equals(fault)) out.put("executionSourceSha256", "b".repeat(64));
        if ("HYPOTHESIS".equals(fault)) out.put("hypothesisId", "other-background");
        if ("CONTEXT".equals(fault)) out.withObject("/context/disease").put("mondoId", "MONDO:9999999");
        return out;
    }
    private LocalEvidenceBatchRuntime runtime(GeneralPm1Runtime engine, List<String> scope) {
        var bp7 = mock(Bp7SelectedBatch.class); when(bp7.identity()).thenReturn(f.mapper.createObjectNode());
        var runtime = new LocalEvidenceBatchRuntime(f.mapper, f.mapper.createObjectNode(), () -> { pm2Factories.incrementAndGet(); return population; },
                () -> new SpliceAiProvider(new VariantAnalysisProperties()).openSharedSession(f.mapper),
                ignored -> { predictionFactories.incrementAndGet(); return predicted; }, null, bp7,
                () -> { throw new IllegalArgumentException("UNAVAILABLE_TEST_PM4"); },
                () -> { throw new IllegalArgumentException("UNAVAILABLE_TEST_PVS1"); },
                () -> { throw new IllegalArgumentException("UNAVAILABLE_TEST_BP3"); }, () -> engine,
                () -> { throw new IllegalArgumentException("UNAVAILABLE_TEST_PP2"); },
                () -> { throw new IllegalArgumentException("UNAVAILABLE_TEST_BP1"); }, false);
        runtime.bindExecutionCriteria(scope); return runtime;
    }
    @ParameterizedTest @CsvSource({"MET,SUPPORTING,MET", "NOT_MET,NONE,NOT_MET", "NOT_EVALUATED,NONE,NOT_EVALUATED"})
    void pm1AloneUsesAnActualBoundFinalPm2WithoutSelectingOrRepeatingIt(String peer, String strength, String expected) throws Exception {
        setup("PM2"); var engine = f.engine();
        when(population.evaluate(selected, candidate, "background", analysis, SOURCE, null)).thenReturn(population(peer, strength, "NONE"));
        var runtime = runtime(engine, List.of(PM1)); var out = runtime.execute(selected, analysis, SOURCE);
        assertEquals(expected, out.at("/results/0/localBatch/criteria/0/status").asText(), out::toPrettyString);
        assertEquals(1, out.at("/results/0/criterionEvaluations").size()); assertEquals(1, out.at("/results/0/localBatch/criteria").size());
        assertEquals(PM1, out.at("/results/0/localBatch/criteria/0/criterionId").asText());
        assertTrue(out.at("/modules/pm2/dependencyOnly").asBoolean()); assertEquals(1, pm2Factories.get()); assertEquals(0, predictionFactories.get());
        verify(population).evaluate(selected, candidate, "background", analysis, SOURCE, null); assertEquals(1, engine.evaluations());
        var facts = runtime.publicView(out).at("/results/0/localBatch/criteria/0/details/pm1Facts");
        assertFalse(facts.has("publicProjectionIssue"), facts::toPrettyString);
        assertEquals(peer, facts.at("/crossCriterionConditionCheck/checks/0/available/status").asText());
        assertEquals("SYNTHETIC_PEER_FINAL", facts.at("/crossCriterionConditionCheck/checks/0/available/reasonCode").asText());
        assertEquals(SOURCE, facts.at("/currentEvaluationBinding/executionSourceSha256").asText());
        artifact("pm2-" + peer, out, runtime.publicView(out));
        assertEquals(out.path("results"), runtime.execute(selected, analysis, SOURCE).path("results"));
        verify(population, times(1)).evaluate(any(), anyString(), anyString(), any(), anyString(), any());
    }
    @Test void selectedPm2IsReusedAsTheDependencyAndBothFinalResultsStayVisible() throws Exception {
        setup("PM2"); when(population.evaluate(any(), anyString(), anyString(), any(), anyString(), any())).thenReturn(population("MET", "SUPPORTING", "NONE"));
        var engine = f.engine(); var runtime = runtime(engine, List.of(PM1, PM2)); var out = runtime.execute(selected, analysis, SOURCE);
        assertEquals(2, out.at("/results/0/criterionEvaluations").size()); assertFalse(out.at("/modules/pm2/dependencyOnly").asBoolean());
        assertEquals(1, out.at("/modules/pm2/results").size()); assertEquals("MET", out.at("/modules/pm1/results/0/status").asText());
        verify(population, times(1)).evaluate(any(), anyString(), anyString(), any(), anyString(), any());
    }
    @ParameterizedTest @ValueSource(strings = {"SOURCE", "HYPOTHESIS", "CONTEXT"})
    void aMetLabelFromTheWrongExecutionOrBackgroundDoesNotSatisfyPm1(String fault) throws Exception {
        setup("PM2"); when(population.evaluate(any(), anyString(), anyString(), any(), anyString(), any())).thenReturn(population("MET", "SUPPORTING", fault));
        var runtime = runtime(f.engine(), List.of(PM1)); var out = runtime.execute(selected, analysis, SOURCE);
        assertEquals("NOT_EVALUATED", out.at("/modules/pm1/results/0/status").asText());
        assertEquals("NONE", out.at("/modules/pm1/results/0/evaluation/appliedStrength").asText());
        assertEquals(1, out.at("/modules/pm1/results/0/peerPreparationIssues").size());
    }
    @ParameterizedTest @ValueSource(strings = {"SOURCE", "DUPLICATE", "CONTEXT", "EVALUATION", "POINTER"})
    void publicProjectionRechecksTheActualPeerRatherThanTrustingItsCachedSummary(String fault) throws Exception {
        setup("PM2"); when(population.evaluate(any(), anyString(), anyString(), any(), anyString(), any())).thenReturn(population("MET", "SUPPORTING", "NONE"));
        var runtime = runtime(f.engine(), List.of(PM1)); var out = runtime.execute(selected, analysis, SOURCE);
        switch (fault) {
            case "SOURCE" -> out.withObject("/modules/pm2").put("sourceSnapshotSha256", "b".repeat(64));
            case "DUPLICATE" -> out.withArray("/modules/pm2/results").add(out.at("/modules/pm2/results/0").deepCopy());
            case "CONTEXT" -> out.withObject("/modules/pm2/results/0/context/disease").put("mondoId", "MONDO:9999999");
            case "EVALUATION" -> out.withObject("/modules/pm2/results/0/evaluation/evaluationResult").put("status", "NOT_MET");
            case "POINTER" -> out.withObject("/modules/pm1/results/0/regionAssessment/crossCriterionConditionCheck/checks/0/available").put("actualModulePointer", "/modules/pm2/results/9");
        }
        var facts = runtime.publicView(out).at("/results/0/localBatch/criteria/0/details/pm1Facts");
        assertEquals("CENTRAL_PM1_MODULE_BINDING_INVALID", facts.path("publicProjectionIssue").asText());
        assertFalse(facts.has("selectedRule")); assertFalse(facts.has("currentEvaluationBinding"));
    }
    @ParameterizedTest @ValueSource(strings = {"SEQUENCE", "SOURCE", "EXCLUSION", "NO_MATCH"})
    void invalidOrNonSupportingLocalRuleDoesNotAcquireAnyDependency(String fault) throws Exception {
        setup("PM2");
        if ("SEQUENCE".equals(fault)) f.sequence.reference = "ATGAGATAA";
        if ("SOURCE".equals(fault)) ((ObjectNode) f.doc.at("/records/0")).put("status", "UNSUPPORTED_CONDITIONS").put("reasonCode", "SOURCE_TEST_UNSUPPORTED");
        if (Set.of("EXCLUSION", "NO_MATCH").contains(fault)) {
            ((ObjectNode) f.doc.at("/records/0/interpretation")).putArray("crossCriterionRelations");
            var rule = (ObjectNode) f.doc.at("/records/0/interpretation/rules/0");
            var source = (ObjectNode) f.doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent/strengthDescriptor/0"); source.put("text", "SYNTHETIC_LOCAL_SUPPORT_OR_EXCLUSION");
            rule.put("sourceSha256", f.adapter.canonicalSha256(source));
            if ("EXCLUSION".equals(fault)) {
                ((ObjectNode) f.doc.at("/specifications/0/original/entContent")).put("notes", "Synthetic explicit exclusion at residue two.");
                rule.put("effect", "EXCLUDE").put("strength", "NONE").put("regionType", "EXCLUSION")
                        .put("sourcePointer", "/entContent/notes").put("sourceSha256", f.adapter.canonicalSha256(f.doc.at("/specifications/0/original/entContent/notes")));
            }
            else { rule.put("start", 1).put("end", 1); rule.putArray("referenceResidues").addObject().put("position", 1).put("aminoAcid", "M"); }
        }
        new PublishedCriterionKnowledgeTest().refresh(f.doc);
        var runtime = runtime(f.engine(), List.of(PM1)); var out = runtime.execute(selected, analysis, SOURCE);
        assertEquals(0, pm2Factories.get()); assertEquals(0, predictionFactories.get()); verifyNoInteractions(population);
        assertFalse("MET".equals(out.at("/modules/pm1/results/0/status").asText()));
        if ("EXCLUSION".equals(fault)) assertEquals("NOT_MET", out.at("/modules/pm1/results/0/status").asText());
    }
    @ParameterizedTest @CsvSource({"MET,STRONG,NOT_MET", "MET,MODERATE,MET", "NOT_MET,NONE,MET", "NOT_EVALUATED,NONE,NOT_EVALUATED"})
    void pp3DependencyUsesItsActualShortKeyFinalResultAndTypedSubset(String status, String strength, String expected) throws Exception {
        setup("PP3"); predicted = mock(SelectedPredictionRuntime.class); var plan = mock(SelectedPredictionRuntime.Batch.class);
        when(plan.snapshot()).thenReturn(f.mapper.createObjectNode());
        var key = new SelectedPredictionRuntime.AttemptKey(candidate, "background");
        when(predicted.prepare(selected, analysis, Set.of(key))).thenReturn(plan);
        var output = f.mapper.createObjectNode(); var row = output.putArray("results").addObject();
        row.putObject("plan").put("candidateId", candidate).put("hypothesisId", "background"); row.set("sourceProjection", projection.deepCopy());
        row.putObject("criteria").putObject("PP3").put("criterionId", "ACMG_AMP_2015:PP3").put("status", status)
                .put("appliedStrength", strength).put("reasonCode", "SYNTHETIC_FINAL_PP3");
        when(predicted.execute(plan, List.of("ACMG_AMP_2015:PP3"))).thenReturn(output);
        var runtime = runtime(f.engine(), List.of(PM1)); var out = runtime.execute(selected, analysis, SOURCE);
        assertEquals(expected, out.at("/modules/pm1/results/0/status").asText(), out::toPrettyString);
        assertEquals(0, pm2Factories.get()); assertEquals(1, predictionFactories.get());
        verify(predicted).prepare(selected, analysis, Set.of(key)); verify(predicted).execute(plan, List.of("ACMG_AMP_2015:PP3"));
        assertEquals(1, out.at("/results/0/localBatch/criteria").size());
        var facts = runtime.publicView(out).at("/results/0/localBatch/criteria/0/details/pm1Facts");
        assertFalse(facts.has("publicProjectionIssue"), facts::toPrettyString);
        artifact("pp3-" + status + "-" + strength, out, runtime.publicView(out));
    }
    @Test void preparedCapabilityCannotBeRestoredFromTheCapturedProjection() throws Exception {
        setup("PM2"); var prepared = f.engine().prepare(selected, candidate, "background");
        assertEquals(List.of(PM2), prepared.requiredPeers()); assertThrows(UnsupportedOperationException.class, () -> prepared.requiredPeers().clear());
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class, () -> f.mapper.treeToValue(projection, GeneralPm1Runtime.Prepared.class));
    }
    @Test void preparedMaterialCannotBeBorrowedByAnotherRuntimeInstance() throws Exception {
        setup("PM2"); var first = f.engine(); var prepared = first.prepare(selected, candidate, "background");
        var second = f.engine(); var out = second.evaluate(prepared, null, SOURCE);
        assertEquals("NOT_EVALUATED", out.path("status").asText()); assertEquals("PM1_PREPARATION_OWNER_MISMATCH", out.path("reasonCode").asText());
    }
    @Test void directInternalCallMustStillMatchTheCurrentSourceAndProjection() throws Exception {
        setup("PM2"); var engine = f.engine(); var prepared = engine.prepare(selected, candidate, "background");
        var actual = population("MET", "SUPPORTING", "NONE"); actual.put("executionSourceSha256", "b".repeat(64));
        var facts = PublishedPm1CrossCriterionConditions.fromBoundCurrentModule(f.mapper, projection, actual, "/evaluation", "/context",
                "b".repeat(64), "/modules/pm2/results/0");
        var out = engine.evaluate(prepared, facts, SOURCE);
        assertEquals("NOT_EVALUATED", out.path("status").asText()); assertEquals("PM1_PEER_SOURCE_SNAPSHOT_MISMATCH", out.path("reasonCode").asText());
        assertEquals("NONE", out.at("/evaluation/appliedStrength").asText());
    }
    @Test void transportAuditBindsNativeScoresWithoutTurningThemIntoScientificIdentityOrBreakingHistory() throws Exception {
        setup("PM2"); var actual = population("MET", "SUPPORTING", "NONE");
        actual.put("unusedAnnotationScore", 0.99).put("unusedScaleValue", 1.0);
        assertThrows(IllegalArgumentException.class, () -> f.adapter.canonicalSha256(actual), "Scientific identity remains strict");
        when(population.evaluate(any(), anyString(), anyString(), any(), anyString(), any())).thenReturn(actual);
        var runtime = runtime(f.engine(), List.of(PM1)); var out = runtime.execute(selected, analysis, SOURCE);
        assertEquals("MET", out.at("/modules/pm1/results/0/status").asText(), out::toPrettyString);
        var before = runtime.publicView(out);
        var decimalMapper = new com.fasterxml.jackson.databind.ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var restored = decimalMapper.readTree(f.mapper.writeValueAsBytes(out));
        var after = LocalEvidenceBatchRuntime.publicView(decimalMapper, restored);
        assertArrayEquals(f.mapper.writeValueAsBytes(before), f.mapper.writeValueAsBytes(after));
        assertEquals(before.at("/results/0/localBatch/criteria/0/details/pm1Facts"), after.at("/results/0/localBatch/criteria/0/details/pm1Facts"));
        var changed = restored.deepCopy(); ((ObjectNode) changed.at("/modules/pm2/results/0")).put("unusedAnnotationScore", "number:0.99");
        assertEquals("CENTRAL_PM1_MODULE_BINDING_INVALID", LocalEvidenceBatchRuntime.publicView(decimalMapper, changed)
                .at("/results/0/localBatch/criteria/0/details/pm1Facts/publicProjectionIssue").asText());
        assertThrows(IllegalArgumentException.class, () -> PublishedPm1CrossCriterionConditions.currentAuditSha256(f.mapper,
                f.mapper.createObjectNode().put("invalid", Double.NaN)));
    }
    @ParameterizedTest @CsvSource({"MET,STRONG,NOT_MET", "MET,MODERATE,MET", "NOT_MET,NONE,MET", "NOT_EVALUATED,NONE,NOT_EVALUATED"})
    void pm5UsesOnlyTheUniqueFinalCriterionEvaluationAndNeverItsIntermediateDecision(String status, String strength, String expected) throws Exception {
        setup("PM5"); var engine = f.engine(); var prepared = engine.prepare(selected, candidate, "background");
        assertEquals(List.of("ACMG_AMP_2015:PM5"), prepared.requiredPeers());
        var actual = f.mapper.createObjectNode().put("candidateId", candidate).put("hypothesisId", "background").put("executionSourceSha256", SOURCE);
        actual.set("sourceProjection", projection.deepCopy());
        var module = actual.putObject("evaluations").putObject("PM5");
        module.putObject("scientificDecision").put("status", "MET").put("appliedStrength", "STRONG").put("adopted", true);
        var evaluation = module.putArray("criterionEvaluations").addObject().put("criterionId", "ACMG_AMP_2015:PM5")
                .put("contextId", context.path("contextId").asText()).put("appliedStrength", strength);
        evaluation.putObject("evaluationResult").put("status", status).put("reasonCode", "SYNTHETIC_FINAL_ADOPTION");
        if ("NOT_EVALUATED".equals(status)) evaluation.remove(List.of("appliedStrength", "contextId")); // Actual bundle refusal shape, never scoring permission.
        String pointer = org.springframework.test.util.ReflectionTestUtils.invokeMethod(LocalEvidenceBatchRuntime.class, "pm5EvaluationPointer", actual);
        assertEquals("/evaluations/PM5/criterionEvaluations/0", pointer);
        var facts = PublishedPm1CrossCriterionConditions.fromBoundCurrentModule(f.mapper, projection, actual, pointer,
                "/sourceProjection/evaluationContext", SOURCE, "/modules/reference/results/0");
        var out = engine.evaluate(prepared, facts, SOURCE);
        assertEquals(expected, out.path("status").asText(), out::toPrettyString);
        module.withArray("criterionEvaluations").add(evaluation.deepCopy());
        assertThrows(IllegalArgumentException.class, () -> org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                LocalEvidenceBatchRuntime.class, "pm5EvaluationPointer", actual));
    }
    @Test void missingPm5MaterialCannotBeReplacedByAnUnselectedFlagOrAnotherPeer() throws Exception {
        setup("PM5"); var runtime = runtime(f.engine(), List.of(PM1)); var out = runtime.execute(selected, analysis, SOURCE);
        assertEquals("NOT_EVALUATED", out.at("/modules/pm1/results/0/status").asText());
        assertEquals("PM1_PUBLISHED_PEER_RESULT_UNAVAILABLE", out.at("/modules/pm1/results/0/reasonCode").asText());
        assertEquals(0, pm2Factories.get()); assertEquals(0, predictionFactories.get());
        assertEquals(1, out.at("/results/0/localBatch/criteria").size());
    }
    private void artifact(String caseId, ObjectNode full, ObjectNode publicView) throws Exception {
        String destination = System.getProperty("varvis.main.pm1.peer.artifacts"); if (destination == null) return;
        var path = java.nio.file.Files.createDirectories(Path.of(destination).resolve(caseId));
        java.nio.file.Files.write(path.resolve("actual-full.json"), f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(full));
        java.nio.file.Files.write(path.resolve("actual-public.json"), f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(publicView));
        java.nio.file.Files.write(path.resolve("actual-source-tier.json"), f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                f.mapper.createObjectNode().put("sourceAndPeerLayer", "SYNTHETIC_ENGINEERING_CONTROL")
                        .put("sequenceAndPm1", "ACTUAL_BOUNDED_RUNTIME").put("newClinicalAdoption", false)));
    }
}
