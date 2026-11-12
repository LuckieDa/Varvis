package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.LegacyContextProjection;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Synthetic composition only: existing algorithms/material fixtures and isolated source files, no real resources. */
class LocalEvidenceBatchRuntimeTest {
    @ParameterizedTest
    @ValueSource(strings={"missense_variant", "missense_variant&NMD_transcript_variant", "missense_variant&splice_region_variant"})
    void ps1SelectionCannotRedirectMissensePm5(String consequence) {
        var context = mapper.createObjectNode().put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
        context.putObject("transcript").put("accession", "ENST00000000001").put("version", 1).put("annotationSourceVersion", "116.0");
        context.putObject("gene").put("symbol", "TEST");
        var tx = mock(org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class);
        when(tx.getSource()).thenReturn("VEP"); when(tx.getStatus()).thenReturn("COMPLETED");
        when(tx.getVersionedTranscriptId()).thenReturn("ENST00000000001.1"); when(tx.getGeneSymbol()).thenReturn("TEST");
        when(tx.getVersion()).thenReturn("116.0"); when(tx.getConsequence()).thenReturn(consequence);
        var variant = mock(VariantInterpretation.class); when(variant.getTranscriptAnnotations()).thenReturn(List.of(tx));
        when(variant.getConsequences()).thenReturn(List.of());
        var prepared = selected("one", context, variant);
        var session = mock(ClinVarConditionAwareComparatorInput.Session.class);
        when(session.buildWithSnapshots(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("MISSENSE_REFERENCE_MISSING"));
        var splice = mock(Ps1SpliceRuntime.class);
        var setup = new LocalEvidenceBatchRuntime.ReferenceSetup(() -> null, mapper.createObjectNode().put("dataClass", "PUBLIC_REFERENCE"), null, null, null, () -> splice);
        for (var codes : List.of(List.of("ACMG_AMP_2015:PM5"), List.of("ACMG_AMP_2015:PS1", "ACMG_AMP_2015:PM5"))) {
            var runtime = new LocalEvidenceBatchRuntime(mapper, config(), null, null, null, setup); runtime.bindExecutionCriteria(codes);
            var requested = codes.stream().map(code -> code.substring(code.indexOf(':') + 1)).collect(java.util.stream.Collectors.toSet());
            ObjectNode result = ReflectionTestUtils.invokeMethod(runtime, "reference", prepared, "one", "hypothesis", session, null, null, requested);
            assertNotNull(result); assertEquals("MISSENSE_REFERENCE_MISSING", result.path("reasonCode").asText());
            assertFalse(result.toString().contains("PM5_SPLICE_COMPARISON_NOT_APPLICABLE"));
        }
        verifyNoInteractions(splice); verify(session, times(2)).buildWithSnapshots(any(), any(), any(), any());
    }
    @Test void selectingBp7RetainsItsBp4DependencyButDoesNotEmitBp4() throws Exception {
        var context = mapper.createObjectNode().put("contextId", "fixture-context");
        var prepared = selected("one", context, null);
        var prediction = mock(SelectedPredictionRuntime.class);
        var plan = mock(SelectedPredictionRuntime.Batch.class);
        when(plan.snapshot()).thenReturn(mapper.createObjectNode());
        when(prediction.prepare(prepared, analysis)).thenReturn(plan);
        var dependency = mapper.createObjectNode(); dependency.putArray("results");
        when(prediction.execute(plan, List.of("ACMG_AMP_2015:BP4"))).thenReturn(dependency);
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), null, this::disabledSplice, ignored -> prediction, null,
                new Bp7SelectedBatch(mapper, new org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks(mapper,
                        new org.springframework.core.io.ClassPathResource("acmg/public-splicing-experiments-v1.json"), temporary.resolve("experiments"),
                        (a,r) -> { throw new AssertionError("No external reads"); }).captureCatalog()));
        runtime.bindExecutionCriteria(List.of("ACMG_AMP_2015:BP7"));
        var out = runtime.execute(prepared, analysis, sourceSha);
        verify(prediction).execute(plan, List.of("ACMG_AMP_2015:BP4"));
        assertEquals(1, out.at("/results/0/localBatch/criteria").size());
        assertEquals("ACMG_AMP_2015:BP7", criterion(out, 0).path("criterionId").asText());
    }
    @Test
    void stopLossMissingSequenceIsNotReportedAsACompletedSearchWithoutTermination() {
        String missing = ReflectionTestUtils.invokeMethod(LocalEvidenceBatchRuntime.class, "explanation",
                "NOT_EVALUATED", "PM4_STOP_LOSS_THREE_PRIME_SEQUENCE_UNAVAILABLE");
        String absent = ReflectionTestUtils.invokeMethod(LocalEvidenceBatchRuntime.class, "explanation",
                "NOT_EVALUATED", "PM4_STOP_LOSS_NEW_STOP_NOT_FOUND_IN_TRANSCRIPT");
        String bounded = ReflectionTestUtils.invokeMethod(LocalEvidenceBatchRuntime.class, "explanation",
                "NOT_EVALUATED", "PM4_STOP_LOSS_NEW_STOP_BEYOND_TECHNICAL_WINDOW");
        if (missing == null || absent == null || bounded == null) {
            throw new AssertionError("PM4 stop-loss explanations must be present");
        }
        assertNotEquals(missing, absent, "Missing sequence is not evidence that a complete search found no stop");
        assertTrue(missing.contains("缺少") && missing.contains("3′"), missing);
        assertTrue(absent.contains("已取得") && absent.contains("未找到"), absent);
        assertTrue(bounded.contains("技术范围限制"), bounded);
    }

    @ParameterizedTest @ValueSource(strings={"NO_KNOWN_FUNCTION", "KNOWN_FUNCTION", "MISSING", "CONFLICT"})
    void bp3UsesItsOwnKnowledgeAlongsidePm4AndBothReuseOneMaterialCache(String function) throws Exception {
        var f = new ProteinLengthChangeMaterialSupplyTest(); f.root=temporary;
        f.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var root=temporary.resolve("shared-protein");
        var pm4=GeneralPm4TestSupport.create(mapper,root,f.data.model,f::read,true,false,false,
                "REVIEWED_ONLY", raw -> {
                    var feature=((ObjectNode)raw.path("features").get(0));
                    feature.withObject("location").withObject("start").put("value",1);
                    feature.withObject("location").withObject("end").put("value",6);
                    if (!"CONFLICT".equals(function)) raw.putArray("features").add(feature);
                });
        var shared=pm4.sharedFactSupply(); var supplied=shared.resolve(f.data.context,f.data.candidate,true);
        var k=new Bp3ProteinRegionAssessmentTest();k.root=temporary;k.context=f.data.context;k.model=supplied.material().model();
        k.annotations=(ObjectNode)supplied.snapshot().path("proteinAnnotations");k.extra=(ObjectNode)supplied.snapshot().path("combinedRegions");
        var knowledge=new Bp3ProteinRegionAssessment(mapper);
        if(!"MISSING".equals(function)) {
            var assertion=k.qualified();((ObjectNode)assertion.at("/records/0/intervals/0")).put("start",1).put("end",6).put("functionalStatus","CONFLICT".equals(function)?"NO_KNOWN_FUNCTION":function);
            knowledge=k.accepted(assertion);
        }
        var bp3=new GeneralBp3Runtime(mapper,shared,knowledge,PredictionSpecializationGuard.load(mapper,root.resolve("catalog.json"),CodingTranscriptMaterialSupply.sha(Files.readAllBytes(root.resolve("catalog.json")))));
        var checks=new org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks(mapper,
                new org.springframework.core.io.ClassPathResource("acmg/public-splicing-experiments-v1.json"),temporary.resolve("experiments"),
                (a,r)->{throw new AssertionError("No business reads");});
        var runtime=new LocalEvidenceBatchRuntime(mapper,config(),null,null,null,null,new Bp7SelectedBatch(mapper,checks.captureCatalog()),
                ()->pm4,()->{throw new IllegalArgumentException("PVS1_TEST_MATERIAL_MISSING");},()->bp3);
        var prepared=selected(f.data.candidate.getCandidateId(),f.data.context,f.data.candidate);
        var before=mapper.valueToTree(f.data.candidate);var out=runtime.execute(prepared,analysis,sourceSha);
        assertEquals(9,out.at("/results/0/localBatch/criteria").size());
        assertEquals("NOT_MET",criterion(out,7).path("status").asText(),out.toPrettyString());
        assertEquals("NO_KNOWN_FUNCTION".equals(function)?"MET":"KNOWN_FUNCTION".equals(function)?"NOT_MET":"NOT_EVALUATED",criterion(out,8).path("status").asText(),out.toPrettyString());
        if ("CONFLICT".equals(function)) assertEquals("BP3_FUNCTIONAL_ANNOTATION_CONFLICT_REVIEW_REQUIRED",criterion(out,8).path("reasonCode").asText());
        assertEquals("PVS1_TEST_MATERIAL_MISSING",criterion(out,6).path("reasonCode").asText());
        assertTrue(out.at("/modules/pm4/results/0/sequenceMaterial/modelCacheHit").asBoolean());
        assertTrue(out.at("/modules/bp3/results/0/sequenceMaterial/modelCacheHit").asBoolean());
        assertEquals(before,mapper.valueToTree(f.data.candidate));
        int reads=f.bases.get(); runtime.execute(prepared,analysis,sourceSha);
        assertEquals(reads,f.bases.get());assertEquals(1,pm4.evaluations());assertEquals(1,bp3.evaluations());
        var view=runtime.publicView(out);assertTrue(criterion(view,8).at("/details/bp3Facts").isObject());
        assertFalse(view.toString().contains("cdsSequence"));assertFalse(view.toString().contains("PRIVATE_CONFIGURATION"));
        String output=System.getProperty("varvis.eleven.artifacts");
        if(output!=null) {Path dir=Files.createDirectories(Path.of(output).resolve("bp3-"+function));mapper.writeValue(dir.resolve("public.json").toFile(),view);mapper.writeValue(dir.resolve("private.json").toFile(),out);}
    }
    @ParameterizedTest @ValueSource(strings = {"MET", "NOT_MET", "NOT_EVALUATED", "INVALID"})
    void absentReasonMustNotDescribeAnEvaluatedResultAsUnavailable(String status) {
        var original = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PS1")
                .put("appliedStrength", "MET".equals(status) ? "STRONG" : "NONE");
        original.putObject("evaluationResult").put("status", status);
        var frozen = original.deepCopy();
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), null, null, null, null);
        ObjectNode normalized = ReflectionTestUtils.invokeMethod(runtime, "normalize",
                "ACMG_AMP_2015:PS1", original, "REFERENCE_RESULT_UNAVAILABLE");
        assertNotNull(normalized);
        boolean evaluated = List.of("MET", "NOT_MET").contains(status);
        assertEquals(evaluated ? "LOCAL_BATCH_CRITERION_" + status : "REFERENCE_RESULT_UNAVAILABLE",
                normalized.at("/evaluationResult/reasonCode").asText());
        assertEquals(evaluated ? status : "NOT_EVALUATED", normalized.at("/evaluationResult/status").asText());
        assertEquals("MET".equals(status) ? "STRONG" : "NONE", normalized.path("appliedStrength").asText());
        assertEquals(frozen, original);
        original.withObject("/evaluationResult").put("reasonCode", "SPECIFIC_SOURCE_REASON");
        ObjectNode explicit = ReflectionTestUtils.invokeMethod(runtime, "normalize",
                "ACMG_AMP_2015:PS1", original, "REFERENCE_RESULT_UNAVAILABLE");
        assertNotNull(explicit);
        assertEquals("SPECIFIC_SOURCE_REASON", explicit.at("/evaluationResult/reasonCode").asText());
    }
    @Test void explicitUseGateMustPassBeforeRuntimeFactoryAndCannotAuthorizeAnUnqualifiedBundle() throws Exception {
        var f = new MissenseReferenceSnapshotExportTest().new Fixture(temporary.resolve("qualification-facts"), 0, "NORMAL", "1/1");
        f.context.put("dataClass", "PRIVACY_BOUNDED_RUNTIME"); f.context.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(f.context));
        f.sources.put("dataClass", "PUBLIC_REFERENCE").put("purpose", "PUBLIC_REFERENCE_REVIEW");
        f.target.applyTranscriptAnnotations(List.of(f.selected));
        var selected = selected(f.target.getCandidateId(), f.context, f.target);
        var factoryCalls = new AtomicInteger();
        var unqualified = mock(ReviewedRuleBundle.class);
        when(unqualified.supportsPs1()).thenReturn(true); when(unqualified.supportsPm5()).thenReturn(true);
        when(unqualified.missenseUseIssue(any())).thenReturn("REFERENCE_PATIENT_MATERIAL_QUALIFICATION_UNAVAILABLE");
        var setup = new LocalEvidenceBatchRuntime.ReferenceSetup(() -> f.builder, f.sources, null,
                (criterion, capture) -> { factoryCalls.incrementAndGet(); return unqualified; },
                (criterion, context) -> criterion.endsWith(":PS1") ? "MISSENSE_CONTEXT_USE_NOT_APPROVED" : null);
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), null, this::disabledSplice, null, setup);
        var result = runtime.execute(selected, analysis, sourceSha);
        assertEquals("EXPLICIT_QUALIFIED_PUBLIC_MATERIAL_REQUIRED", runtime.capabilities().path("patientMode").asText());
        assertEquals("MISSENSE_CONTEXT_USE_NOT_APPROVED", criterion(result, 3).path("reasonCode").asText());
        assertEquals("REFERENCE_PATIENT_MATERIAL_QUALIFICATION_UNAVAILABLE", criterion(result, 4).path("reasonCode").asText());
        assertEquals(1, factoryCalls.get()); verify(unqualified, never()).evaluate(anyString(), any(), any());
    }
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final AnalysisContext analysis = new AnalysisContext("local-batch-test", GenomeAssembly.GRCh38, AnalysisMode.WES);
    final String sourceSha = "a".repeat(64);

    private SelectedEvidencePreparation selected(String id, @org.springframework.lang.Nullable ObjectNode context,
            @org.springframework.lang.Nullable VariantInterpretation candidate) {
        var selected = mock(SelectedEvidencePreparation.class);
        var attempts = context == null ? List.<Attempt>of() : List.of(new Attempt(id, "hypothesis", null, null, null, context, List.of()));
        when(selected.assembly()).thenReturn(new AssemblyResult(analysis.getAnalysisId(), 1, 1, List.of(id),
                List.of(new CandidateAssembly(id, attempts)), List.of(), false));
        when(selected.sources()).thenReturn(List.of());
        when(selected.provenance()).thenReturn(mapper.createObjectNode().put("privateSource", "PRIVATE_ORIGINAL_VCF_LINE"));
        ObjectNode candidateDocument = candidate == null ? mapper.createObjectNode() : mapper.valueToTree(candidate);
        when(selected.formalCandidate(id)).thenReturn(candidateDocument);
        if (context != null) {
            when(selected.context(id, "hypothesis")).thenAnswer(ignored -> context.deepCopy());
            var projection = mock(LegacyContextProjection.class);
            var snapshot = mapper.createObjectNode().put("candidateId", id).put("hypothesisId", "hypothesis")
                    .put("fixture", "SYNTHETIC_PROJECTION_BOUNDARY_ONLY");
            snapshot.set("evaluationContext", context); snapshot.set("originalContext", context.deepCopy());
            snapshot.put("bindingSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(snapshot));
            when(projection.context()).thenAnswer(ignored -> context.deepCopy());
            when(projection.snapshot()).thenAnswer(ignored -> snapshot.deepCopy());
            when(projection.queryCandidate()).thenReturn(candidate);
            when(selected.legacyProjection(id, "hypothesis")).thenReturn(projection);
            when(selected.codingSequenceProjection(id, "hypothesis")).thenReturn(projection);
        }
        return selected;
    }

    private SelectedPredictionRuntime prediction(SelectedEvidencePreparation selected, String id, String status, String strength) {
        var runtime = mock(SelectedPredictionRuntime.class); var plan = mock(SelectedPredictionRuntime.Batch.class);
        when(plan.snapshot()).thenReturn(mapper.createObjectNode().put("scoreLookups", 0));
        when(runtime.prepare(selected, analysis)).thenReturn(plan);
        var output = mapper.createObjectNode().put("developmentProviderCalls", 1); var result = output.putArray("results").addObject();
        result.putObject("plan").put("candidateId", id).put("hypothesisId", "hypothesis");
        var criteria = result.putObject("criteria");
        criteria.putObject("PP3").put("criterionId", "ACMG_AMP_2015:PP3").put("status", status)
                .put("appliedStrength", strength).put("reasonCode", "SYNTHETIC_PREDICTION_RESULT");
        criteria.putObject("BP4").put("criterionId", "ACMG_AMP_2015:BP4").put("status", "NOT_MET")
                .put("appliedStrength", "NONE").put("reasonCode", "SYNTHETIC_NEGATIVE_RESULT");
        when(runtime.execute(plan)).thenReturn(output); return runtime;
    }

    private SpliceAiLocalObservationSession disabledSplice() {
        var settings = new VariantAnalysisProperties(); settings.getSpliceAi().setEnabled(false);
        return new SpliceAiProvider(settings).openSharedSession(mapper);
    }
    private ObjectNode config() { return mapper.createObjectNode().put("privatePath", "D:/PRIVATE_CONFIGURATION_MUST_NOT_LEAK"); }
    private JsonNode criterion(ObjectNode out, int index) { return out.at("/results/0/localBatch/criteria/" + index); }

    @Test void emptyBackgroundRetainsFiveExplicitResultsWithoutHypothesisOrConfigurationIo() {
        var calls = new AtomicInteger();
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), () -> { calls.incrementAndGet(); return null; },
                null, null, null);
        assertEquals(0, calls.get()); assertEquals("NOT_CHECKED", runtime.identity().path("resourceReadiness").asText());
        assertEquals(5, runtime.capabilities().path("criteria").size());
        assertEquals("FACT_CAPTURE_ONLY", runtime.capabilities().path("patientMode").asText());
        var out = runtime.execute(selected("empty", null, null), analysis, sourceSha);
        assertEquals(0, calls.get());
        assertTrue(out.at("/results/0/localBatch/hypothesisId").isNull());
        assertTrue(out.at("/results/0/localBatch/contextId").isNull());
        assertEquals(5, out.at("/results/0/localBatch/criteria").size());
        for (int i = 0; i < 5; i++) {
            assertEquals(LocalEvidenceBatchRuntime.CRITERIA.get(i), criterion(out, i).path("criterionId").asText());
            assertEquals("NOT_EVALUATED", criterion(out, i).path("status").asText());
            assertEquals("SELECTED_CONTEXT_UNAVAILABLE", criterion(out, i).path("reasonCode").asText());
            assertEquals("NONE", criterion(out, i).path("appliedStrength").asText());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sourceOrAnalysisIdentityFailureMakesNoFactoryCalls(boolean wrongAnalysis) {
        var selected = selected("empty", null, null); var factories = new AtomicInteger();
        Supplier<Pm2LocalExomesRuntime> pm2 = () -> { factories.incrementAndGet(); throw new AssertionError("No acquisition"); };
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), pm2, null, null, null);
        var other = new AnalysisContext("wrong-analysis", GenomeAssembly.GRCh38, AnalysisMode.WES);
        var output = runtime.execute(selected, wrongAnalysis ? other : analysis, wrongAnalysis ? sourceSha : "untrusted");
        assertEquals(wrongAnalysis ? "LOCAL_BATCH_ANALYSIS_ID_MISMATCH" : "LOCAL_BATCH_SOURCE_IDENTITY_REQUIRED", criterion(output, 0).path("reasonCode").asText());
        assertEquals(0, factories.get());
    }

    @Test void everyInvalidContextRetainsItsOwnSourceBlockerWithoutAnyFactoryInitialization() {
        var selected = selected("one", mapper.createObjectNode().put("contextId", "invalid"), null);
        when(selected.context("one", "hypothesis")).thenThrow(new IllegalArgumentException("NORMALIZATION_SOURCE_CHANGED"));
        var factories = new AtomicInteger();
        var setup = new LocalEvidenceBatchRuntime.ReferenceSetup(() -> { factories.incrementAndGet(); throw new AssertionError("No reference initialization"); }, mapper.createObjectNode(), null, null);
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(),
                () -> { factories.incrementAndGet(); throw new AssertionError("No population initialization"); },
                () -> { factories.incrementAndGet(); throw new AssertionError("No splice initialization"); },
                ignored -> { factories.incrementAndGet(); throw new AssertionError("No prediction initialization"); }, setup);
        var out = runtime.execute(selected, analysis, sourceSha);
        for (int i = 0; i < 5; i++) assertEquals("NORMALIZATION_SOURCE_CHANGED", criterion(out, i).path("reasonCode").asText());
        assertEquals(0, factories.get()); assertEquals(0, out.at("/providerCounts/sharedSplice").asInt(-1));
    }

    @Test void preservesEachActualStrengthAndNegativeResultWhenReferenceConfigurationFails() {
        var context = mapper.createObjectNode().put("contextId", "fixture-context"); var selected = selected("one", context, null);
        var pm2 = mock(Pm2LocalExomesRuntime.class); when(pm2.profile()).thenReturn(mapper.createObjectNode());
        var population = mapper.createObjectNode(); var evaluated = population.putObject("evaluation");
        evaluated.put("criterionId", "ACMG_AMP_2015:PM2").put("appliedStrength", "SUPPORTING");
        evaluated.putObject("evaluationResult").put("status", "MET").put("reasonCode", "SYNTHETIC_PM2_MET");
        when(pm2.evaluate(selected, "one", "hypothesis", analysis, sourceSha, null)).thenReturn(population);
        var prediction = prediction(selected, "one", "MET", "STRONG"); var shared = disabledSplice();
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), () -> pm2, () -> shared, splice -> {
            assertSame(shared, splice); return prediction;
        }, null);
        var output = runtime.execute(selected, analysis, sourceSha);
        assertEquals("SUPPORTING", criterion(output, 0).path("appliedStrength").asText());
        assertEquals("STRONG", criterion(output, 1).path("appliedStrength").asText());
        assertEquals("NOT_MET", criterion(output, 2).path("status").asText());
        assertEquals("REFERENCE_CONFIGURATION_UNAVAILABLE", criterion(output, 3).path("reasonCode").asText());
        assertEquals(population, output.at("/modules/pm2/results/0"));
        var replay = runtime.execute(selected, analysis, sourceSha);
        assertEquals("REQUEST_RESULT_REUSE", replay.path("executionMode").asText());
        assertEquals(0, replay.path("newProviderInvocations").asInt(-1));
        verify(pm2, times(1)).evaluate(selected, "one", "hypothesis", analysis, sourceSha, null);
        assertThrows(IllegalArgumentException.class, () -> runtime.execute(selected, analysis, "b".repeat(64)));
        output.removeAll(); assertEquals(5, runtime.execute(selected, analysis, sourceSha).at("/results/0/localBatch/criteria").size());
    }

    @Test void patientCaptureKeepsClassAndSourceFactsWithoutDraftAndSharesOneSpliceObjectDespitePm2Failure() throws Exception {
        var f = new MissenseReferenceSnapshotExportTest().new Fixture(temporary.resolve("facts"), 0, "NORMAL", "7/19");
        f.context.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
        f.context.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(f.context));
        // Synthetic test doubles simulate public resource declarations; no real patient or scientific approval is used.
        f.sources.put("dataClass", "PUBLIC_REFERENCE").put("purpose", "PUBLIC_REFERENCE_REVIEW");
        f.target.applyTranscriptAnnotations(List.of(f.selected));
        String candidateId = f.target.getCandidateId();
        var selected = selected(candidateId, f.context, f.target); var shared = spy(disabledSplice());
        var disabled = new VariantAnalysisProperties(); disabled.getAlphaMissense().setEnabled(false);
        var prediction = new SelectedPredictionRuntime(mapper, PredictionDevelopmentRuntime.configured(mapper, new AlphaMissenseProvider(disabled)),
                Hnf1aRevelRuntime.unavailable(mapper), null, null, shared,
                plan -> mapper.createObjectNode().put("status", "UNAVAILABLE"));
        var pm2 = mock(Pm2LocalExomesRuntime.class); when(pm2.profile()).thenReturn(mapper.createObjectNode());
        when(pm2.evaluate(selected, candidateId, "hypothesis", analysis, sourceSha, null)).thenThrow(new IllegalStateException("SYNTHETIC_PM2_FAILED"));
        var approvalCalls = new AtomicInteger();
        var setup = new LocalEvidenceBatchRuntime.ReferenceSetup(() -> f.builder, f.sources, temporary.resolve("forbidden-patient-draft"),
                (criterion, capture) -> { approvalCalls.incrementAndGet(); throw new AssertionError("Patient qualification must be established before any approved bundle factory"); });
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), () -> pm2, () -> shared, same -> {
            assertSame(shared, same); return prediction;
        }, setup);
        var output = runtime.execute(selected, analysis, sourceSha); var reference = output.at("/modules/reference/results/0");
        assertEquals("SYNTHETIC_PM2_FAILED", criterion(output, 0).path("reasonCode").asText());
        assertEquals("NOT_EVALUATED", criterion(output, 1).path("status").asText());
        assertTrue(output.at("/modules/prediction/results/0/sharedSpliceSource/rawJson").isTextual());
        assertEquals("CASE_BOUND_FACT_CAPTURE", reference.path("mode").asText());
        assertEquals("PRIVACY_BOUNDED_RUNTIME", reference.at("/capture/evidenceContext/dataClass").asText());
        assertEquals("REFERENCE_DRAFT_DATA_CLASS_UNSUPPORTED", reference.at("/materialPreparationGate/reasonCode").asText());
        assertEquals("REFERENCE_PATIENT_MATERIAL_QUALIFICATION_UNAVAILABLE", criterion(output, 3).path("reasonCode").asText());
        assertEquals("REFERENCE_PATIENT_MATERIAL_QUALIFICATION_UNAVAILABLE", criterion(output, 4).path("reasonCode").asText());
        assertEquals(0, approvalCalls.get());
        assertEquals(1, f.discoveryCalls); assertEquals(1, f.annotationCalls);
        assertFalse(Files.exists(temporary.resolve("forbidden-patient-draft")));
        assertEquals(2, reference.path("sharedSpliceObservations").size());
        verify(shared, times(2)).observe(f.target.getObservation().getKey(), "PRIVACY_BOUNDED_RUNTIME");
        assertTrue(reference.at("/capture/result/comparators/0/submissions").size() > 0);
        var publicView = runtime.publicView(output);
        assertFalse(publicView.has("preparation")); assertFalse(publicView.has("receipts")); assertFalse(publicView.has("sourceProvenance"));
        assertFalse(publicView.toString().contains("PRIVATE_ORIGINAL_VCF_LINE")); assertFalse(publicView.toString().contains("PRIVATE_CONFIGURATION"));
        assertFalse(publicView.toString().contains("sourceReceipt")); assertFalse(publicView.toString().contains("snapshotExport"));
        assertFalse(publicView.toString().contains("referenceSnapshots"));
        var facts = publicView.at("/results/0/localBatch/criteria/4/details/referenceFacts");
        assertEquals("missense-reference-facts-v1", facts.path("schemaVersion").asText());
        assertEquals(1, facts.path("comparableReferences").asInt());
        assertFalse(facts.path("scientificApprovalGranted").asBoolean(true));
        assertEquals(criterion(output, 4).at("/details/referenceFacts"), facts);
        assertEquals("REFERENCE_DRAFT_DATA_CLASS_UNSUPPORTED", publicView.at("/results/0/localBatch/criteria/3/details/materialPreparationGate/reasonCode").asText());
        assertEquals(output.at("/results/0/localBatch/criteria/1/status"), publicView.at("/results/0/localBatch/criteria/1/status"));
        assertTrue(output.has("sourceProvenance"));
    }

    @Test void syntheticReferenceResourcesCannotBeUsedAsPatientSourceFacts() throws Exception {
        var f = new MissenseReferenceSnapshotExportTest().new Fixture(temporary.resolve("facts"), 0, "NORMAL", "7/19");
        f.context.put("dataClass", "PRIVACY_BOUNDED_RUNTIME");
        f.context.put("contextId", new RuntimeEvidenceV2Adapter(mapper).canonicalContextId(f.context));
        f.target.applyTranscriptAnnotations(List.of(f.selected));
        var selected = selected(f.target.getCandidateId(), f.context, f.target);
        var setup = new LocalEvidenceBatchRuntime.ReferenceSetup(() -> f.builder, f.sources, temporary.resolve("forbidden"), null);
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), null, this::disabledSplice, null, setup);
        var output = runtime.execute(selected, analysis, sourceSha);
        assertEquals("REFERENCE_SYNTHETIC_SOURCE_RUNTIME_FORBIDDEN", criterion(output, 3).path("reasonCode").asText());
        assertEquals(0, f.discoveryCalls); assertEquals(0, f.annotationCalls); assertFalse(Files.exists(temporary.resolve("forbidden")));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void existingSeparatelyApprovedSyntheticBundleConsumesTheCapturedFacts(boolean pm5) throws Exception {
        verifyCapturedReference(pm5, false);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void indexedMaterialsConsumeSameCaptureInCurrentBatchAndKeepPublicFacts(boolean pm5) throws Exception {
        verifyCapturedReference(pm5, true);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void qualifiedRuntimeDoesNotGenerateAnotherDraftOrReportItsIrrelevantFailure(boolean pm5) throws Exception {
        verifyCapturedReference(pm5, false, true);
    }
    private void verifyCapturedReference(boolean pm5, boolean indexed) throws Exception {
        verifyCapturedReference(pm5, indexed, false);
    }
    @SuppressWarnings("unchecked")
    private void verifyCapturedReference(boolean pm5, boolean indexed, boolean qualified) throws Exception {
        var f = new GeneratedMissenseReferenceFixture(temporary.resolve("generated"), mapper, pm5).build();
        ClinVarConditionAwareComparatorInput comparator = ReflectionTestUtils.invokeMethod(f, "comparator", "CAPTURE", f.root.resolve("independent-sources/assertions.tsv.gz"));
        ObjectNode sources = ReflectionTestUtils.invokeMethod(f, "snapshotSources");
        assertNotNull(comparator); assertNotNull(sources);
        var context = f.context(0); var target = f.target(0); var selected = selected("one", context, target);
        var setup = new LocalEvidenceBatchRuntime.ReferenceSetup(() -> comparator, sources, temporary.resolve("drafts"), (criterion, captured) -> {
            if (!f.criterion().equals(criterion)) return null;
            ReviewedRuleBundle original = ReflectionTestUtils.invokeMethod(f, "load", f.root.resolve("independent-sources/assertions.tsv.gz"), captured);
            if (!indexed) return original;
            try {
                var document = mapper.createObjectNode().put("schemaVersion", "missense-material-index-v1");
                document.putArray("entries").addObject().put("entryId", "same-capture").put("criterion", f.criterion())
                        .put("path", f.bundlePath().toString()).put("rawSha256", Pm4MaterialPreparation.sha(java.nio.file.Files.readAllBytes(f.bundlePath())))
                        .put("canonicalSha256", f.bundleSha256());
                var path = temporary.resolve("reference-index.json"); mapper.writeValue(path.toFile(), document);
                var index = MissenseMaterialIndex.load(path,Pm4MaterialPreparation.sha(java.nio.file.Files.readAllBytes(path)));
                assertNotNull(original);
                var supplier = (java.util.function.Supplier<MissenseComparisonRuntime>)ReflectionTestUtils.getField(original,"missense"); assertNotNull(supplier);
                return index.bind(criterion,captured.getEvidenceContext(),captured.getSelectedTargetAnnotation(),supplier,original.identity());
            } catch (Exception failure) { throw new IllegalStateException(failure); }
        }, qualified ? (criterion, input) -> null : null);
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), null, this::disabledSplice, null, setup);
        var output = runtime.execute(selected, analysis, sourceSha); var result = criterion(output, pm5 ? 4 : 3);
        assertEquals("MET", result.path("status").asText(), output.toPrettyString());
        assertEquals(pm5 ? "MODERATE" : "STRONG", result.path("appliedStrength").asText());
        if (qualified) {
            assertFalse(Files.exists(temporary.resolve("drafts")));
            assertTrue(output.at("/modules/reference/results/0/draft").isMissingNode());
            assertEquals("NOT_REQUIRED", output.at("/modules/reference/results/0/materialPreparationGate/status").asText());
            assertEquals("REFERENCE_EXISTING_MATERIAL_ROUTE", output.at("/modules/reference/results/0/materialPreparationGate/reasonCode").asText());
            assertEquals("REFERENCE_EXISTING_MATERIAL_ROUTE", runtime.publicView(output).at("/results/0/localBatch/criteria/"+(pm5?4:3)+"/details/materialPreparationGate/reasonCode").asText());
        } else {
            assertEquals("UNREVIEWED", output.at("/modules/reference/results/0/draft/reviewStatus").asText());
            assertEquals("REFERENCE_SCIENTIFIC_REVIEW_REQUIRED", output.at("/modules/reference/results/0/materialPreparationGate/reasonCode").asText());
        }
        assertEquals(0, f.counts().path("runtimeDiscovery").asInt()); assertEquals(0, f.counts().path("runtimeAnnotation").asInt());
        assertEquals(1, output.at("/providerCounts/reference/discoveryCalls").asInt());
        var original = output.at("/modules/reference/results/0/evaluations/" + (pm5 ? "PM5" : "PS1"));
        assertEquals("SAME_INVOCATION_CAPTURE", original.path("referenceFactReuse").asText());
        assertEquals(f.selectedAssertion(0).path("sourceAssertion"), original.at("/references/0/sourceAssertion"));
    }

    @Test void predictionExceptionDoesNotHidePopulationResultAndWrongCriterionCannotBecomeMet() {
        var selected = selected("one", mapper.createObjectNode().put("contextId", "context"), null);
        var pm2 = mock(Pm2LocalExomesRuntime.class); when(pm2.profile()).thenReturn(mapper.createObjectNode());
        var wrong = mapper.createObjectNode(); var evaluation = wrong.putObject("evaluation");
        evaluation.put("criterionId", "ACMG_AMP_2015:BA1").put("appliedStrength", "STAND_ALONE"); evaluation.putObject("evaluationResult").put("status", "MET");
        when(pm2.evaluate(selected, "one", "hypothesis", analysis, sourceSha, null)).thenReturn(wrong);
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), () -> pm2, this::disabledSplice,
                ignored -> { throw new IllegalStateException("SYNTHETIC_PREDICTION_FAILED"); }, null);
        var result = runtime.execute(selected, analysis, sourceSha);
        verify(pm2).evaluate(selected, "one", "hypothesis", analysis, sourceSha, null);
        assertEquals(wrong, result.at("/modules/pm2/results/0"));
        assertEquals("LOCAL_BATCH_CRITERION_ID_MISMATCH", criterion(result, 0).path("reasonCode").asText());
        assertEquals("SYNTHETIC_PREDICTION_FAILED", criterion(result, 1).path("reasonCode").asText());
        assertEquals("NONE", criterion(result, 0).path("appliedStrength").asText());
    }

    @Test void publicProjectionKeepsEachBackgroundDecisionAndItsOwnPrivateSourcePointer() {
        var selected = selected("one", null, null);
        var first = mapper.createObjectNode().put("contextId", "context-one");
        var second = mapper.createObjectNode().put("contextId", "context-two");
        when(selected.assembly()).thenReturn(new AssemblyResult(analysis.getAnalysisId(), 1, 1, List.of("one"),
                List.of(new CandidateAssembly("one", List.of(
                        new Attempt("one", "disease-one", null, null, null, first, List.of()),
                        new Attempt("one", "disease-two", null, null, null, second, List.of())))), List.of(), false));
        when(selected.context("one", "disease-one")).thenReturn(first); when(selected.context("one", "disease-two")).thenReturn(second);
        var pm2 = mock(Pm2LocalExomesRuntime.class); when(pm2.profile()).thenReturn(mapper.createObjectNode());
        for (String hypothesis : List.of("disease-one", "disease-two")) {
            var result = mapper.createObjectNode().put("rawPopulationRecord", "PRIVATE_VCF_" + hypothesis);
            var evaluation = result.putObject("evaluation").put("criterionId", "ACMG_AMP_2015:PM2")
                    .put("appliedStrength", "disease-one".equals(hypothesis) ? "SUPPORTING" : "NONE");
            evaluation.putObject("evaluationResult").put("status", "disease-one".equals(hypothesis) ? "MET" : "NOT_MET")
                    .put("reasonCode", "disease-one".equals(hypothesis) ? "FIRST_BACKGROUND_MET" : "SECOND_BACKGROUND_NOT_MET");
            when(pm2.evaluate(selected, "one", hypothesis, analysis, sourceSha, null)).thenReturn(result);
        }
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), () -> pm2, null, null, null);
        var full = runtime.execute(selected, analysis, sourceSha); var view = runtime.publicView(full);
        for (int i = 0; i < 2; i++) {
            String pointer = view.at("/results/" + i + "/localBatch/criteria/0/details/modulePointer").asText();
            assertEquals("/modules/pm2/results/" + i, pointer);
            assertEquals(full.at(pointer + "/evaluation/evaluationResult/status"), view.at("/results/" + i + "/localBatch/criteria/0/status"));
            assertEquals(i == 0 ? "context-one" : "context-two", view.at("/results/" + i + "/localBatch/contextId").asText());
        }
        assertNotEquals(view.at("/results/0/localBatch/criteria/0/details/originalResultSha256"), view.at("/results/1/localBatch/criteria/0/details/originalResultSha256"));
        assertFalse(view.toString().contains("PRIVATE_VCF_")); assertTrue(full.toString().contains("PRIVATE_VCF_"));
    }

    @Test void optionalPm4RoutesActualCoreAndExactRepeatWithoutChangingFiveCriteria() throws Exception {
        var f = new ProteinLengthChangeMaterialSupplyTest(); f.root = temporary;
        f.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var pm4 = GeneralPm4TestSupport.create(mapper, temporary.resolve("pm4-runtime"), f.data.model, f::read, true, false, false);
        var factories = new AtomicInteger();
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), null, null, null, null,
                () -> { factories.incrementAndGet(); return pm4; });
        assertEquals(0, factories.get()); assertEquals(6, runtime.capabilities().path("criteria").size());
        var profile = AcmgExecutionProfile.localBatch(mapper, runtime.identity());
        assertEquals(6, profile.criteria().size());
        var selected = selected(f.data.candidate.getCandidateId(), f.data.context, f.data.candidate);
        var first = runtime.execute(selected, analysis, sourceSha);
        assertEquals("NOT_MET", criterion(first, 5).path("status").asText(), first.toPrettyString());
        assertEquals(1, factories.get()); assertEquals(1, pm4.evaluations());
        var visible = runtime.publicView(first);
        assertFalse(visible.toString().contains("cdsSequence")); assertFalse(visible.toString().contains("PRIVATE_ORIGINAL"));
        assertEquals("pm4-general-facts-v1", criterion(visible,5).at("/details/proteinLengthFacts/schemaVersion").asText());
        var second = runtime.execute(selected, analysis, sourceSha);
        assertEquals(first.path("results"), second.path("results"));
        assertEquals(0, second.path("newEvaluatorInvocations").asInt(-1));
        assertEquals(1, factories.get()); assertEquals(1, pm4.evaluations());
        var off = new LocalEvidenceBatchRuntime(mapper, config(), null, null, null, null);
        assertFalse(off.identity().has("pm4Configured")); assertEquals(5, off.capabilities().path("criteria").size());
    }
    @Test void combinedBp7AndPm4RunIndependentlyAndReuseTheSameSavedBatch() throws Exception {
        var f = new ProteinLengthChangeMaterialSupplyTest(); f.root = temporary;
        f.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var pm4 = GeneralPm4TestSupport.create(mapper, temporary.resolve("combined-pm4"), f.data.model, f::read, true, false, false);
        var checks = new org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks(mapper,
                new org.springframework.core.io.ClassPathResource("acmg/public-splicing-experiments-v1.json"),
                temporary.resolve("experiments"), (a, r) -> { throw new AssertionError("No business reads"); });
        var bp7 = new Bp7SelectedBatch(mapper, checks.captureCatalog());
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), null, null, null, null, bp7, () -> pm4);
        var prepared = selected(f.data.candidate.getCandidateId(), f.data.context, f.data.candidate);
        var first = runtime.execute(prepared, analysis, sourceSha);
        assertEquals(7, first.at("/results/0/localBatch/criteria").size());
        assertEquals(Bp7SelectedBatch.CRITERION, criterion(first, 5).path("criterionId").asText());
        assertEquals("NOT_EVALUATED", criterion(first, 5).path("status").asText());
        assertEquals(GeneralPm4Runtime.CRITERION, criterion(first, 6).path("criterionId").asText());
        assertEquals("NOT_MET", criterion(first, 6).path("status").asText());
        var visible = runtime.publicView(first);
        assertEquals("pm4-general-facts-v1", criterion(visible, 6).at("/details/proteinLengthFacts/schemaVersion").asText());
        assertTrue(visible.path("modules").has("bp7")); assertTrue(visible.path("modules").has("pm4"));
        int bp7Calls = bp7.evaluatorCalls();
        var repeat = runtime.execute(prepared, analysis, sourceSha);
        assertEquals(first.path("results"), repeat.path("results"));
        assertEquals(1, pm4.evaluations()); assertEquals(bp7Calls, bp7.evaluatorCalls());
        assertEquals(0, repeat.path("newEvaluatorInvocations").asInt(-1));
    }

    @Test void pm4CannotOpenOnBadSourceAndRetainsSixBlockers() {
        var runtime = new LocalEvidenceBatchRuntime(mapper, config(), null, null, null, null,
                () -> { throw new AssertionError("No PM4 source IO before source gates"); });
        var out = runtime.execute(selected("bad", null, null), analysis, "untrusted");
        assertEquals(6, out.at("/results/0/localBatch/criteria").size());
        assertEquals("LOCAL_BATCH_SOURCE_IDENTITY_REQUIRED", criterion(out,5).path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"AVAILABLE", "UNAVAILABLE", "GERMLINE_MISMATCH"})
    void publishedModulesPreserveIndependentFactsAndDoNotReexecuteOnExactRepeat(String pp2Mode) throws Exception {
        boolean failedPp2 = !"AVAILABLE".equals(pp2Mode);
        var material = new CodingTranscriptMaterialSupplyTest(); material.root = temporary; material.setup();
        var f = material.input.fixture();
        var prepared = selected(f.candidate().getCandidateId(), f.context(), f.candidate());
        var pm1 = mock(GeneralPm1Runtime.class); var pp2 = mock(GeneralPp2Runtime.class); var bp1 = mock(GeneralBp1Runtime.class);
        var pm1Prepared = mock(GeneralPm1Runtime.Prepared.class);
        when(pm1Prepared.requiredPeers()).thenReturn(List.of());
        when(pm1.prepare(prepared, f.candidate().getCandidateId(), "hypothesis")).thenReturn(pm1Prepared);
        when(pm1.identity()).thenReturn(mapper.createObjectNode()); when(pp2.identity()).thenReturn(mapper.createObjectNode().put("germlineDeclared", false)); when(bp1.identity()).thenReturn(mapper.createObjectNode());
        for (var code : List.of("PM1", "PP2", "BP1")) {
            var value = mapper.createObjectNode().put("status", "MET").put("reasonCode", code + "_SYNTHETIC_MODULE_MET");
            value.putObject("evaluation").put("criterionId", "ACMG_AMP_2015:" + code).put("appliedStrength", "SUPPORTING")
                    .putObject("evaluationResult").put("status", "MET").put("reasonCode", code + "_SYNTHETIC_MODULE_MET");
            value.putObject("publicSummary").put("schemaVersion", code.toLowerCase(java.util.Locale.ROOT)+"-general-facts-v1").put("clinicalValidationCompleted",false);
            value.put("privateSource", "PRIVATE_MISSENSE_SEQUENCE_MUST_NOT_LEAK");
            if ("PM1".equals(code)) when(pm1.evaluate(pm1Prepared,null,sourceSha)).thenReturn(value);
            else if ("PP2".equals(code)) when(pp2.evaluate(prepared,f.candidate().getCandidateId(),"hypothesis")).thenReturn(value);
            else when(bp1.evaluate(prepared,f.candidate().getCandidateId(),"hypothesis")).thenReturn(value);
        }
        var checks = new org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks(mapper,
                new org.springframework.core.io.ClassPathResource("acmg/public-splicing-experiments-v1.json"),temporary.resolve("experiments"),
                (a,r)->{throw new AssertionError("No business reads");});
        var runtime = new LocalEvidenceBatchRuntime(mapper,config(),null,null,null,null,new Bp7SelectedBatch(mapper,checks.captureCatalog()),
                ()->{throw new IllegalArgumentException("PM4_TEST_UNAVAILABLE");},()->{throw new IllegalArgumentException("PVS1_TEST_UNAVAILABLE");},
                ()->{throw new IllegalArgumentException("BP3_TEST_UNAVAILABLE");},()->pm1,
                ()->{if("UNAVAILABLE".equals(pp2Mode))throw new IllegalArgumentException("PP2_TEST_UNAVAILABLE");return pp2;},()->bp1,"GERMLINE_MISMATCH".equals(pp2Mode));
        var out=runtime.execute(prepared,analysis,sourceSha); var view=runtime.publicView(out);
        assertEquals(12,out.at("/results/0/localBatch/criteria").size());
        assertEquals("MET",criterion(view,9).path("status").asText());
        assertEquals(failedPp2?"NOT_EVALUATED":"MET",criterion(view,10).path("status").asText());
        if ("GERMLINE_MISMATCH".equals(pp2Mode)) assertEquals("PP2_GERMLINE_DECLARATION_BINDING_MISMATCH", criterion(view,10).path("reasonCode").asText());
        assertEquals("MET",criterion(view,11).path("status").asText());
        assertTrue(criterion(view,9).at("/details/pm1Facts").isObject()); assertTrue(criterion(view,11).at("/details/bp1Facts").isObject());
        assertFalse(view.toString().contains("PRIVATE_MISSENSE_SEQUENCE")); assertTrue(view.at("/modules/bp1/privateOriginalAvailable").asBoolean());
        var repeated=runtime.execute(prepared,analysis,sourceSha); assertEquals("REQUEST_RESULT_REUSE",repeated.path("executionMode").asText());
        verify(pm1,times(1)).prepare(prepared,f.candidate().getCandidateId(),"hypothesis");
        verify(pm1,times(1)).evaluate(pm1Prepared,null,sourceSha); verify(bp1,times(1)).evaluate(prepared,f.candidate().getCandidateId(),"hypothesis");
        verify(pp2,times(failedPp2?0:1)).evaluate(prepared,f.candidate().getCandidateId(),"hypothesis");
    }

}
