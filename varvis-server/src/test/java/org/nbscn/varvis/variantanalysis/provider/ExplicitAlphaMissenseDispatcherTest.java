package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.DiseaseContextBinding;
import org.nbscn.varvis.variantanalysis.service.TopNAcmgContextPlanner;
import org.nbscn.varvis.variantanalysis.service.TopNAcmgContextPlanner.ContextSeed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitAlphaMissenseDispatcherTest {
    @Test void knownHnf1aMissensePolicyNeverFallsBackToGeneralAmWhenRevelIsUnavailable() throws Exception {
        for (String transcript : List.of("ENST00000257555.11", "ENST00000257555.10")) {
            var input = fixture(1, "HNF1A", "HGNC:11621", "MONDO:0015967", transcript, null);
            var am = new FixtureProvider("0.99", false, false);
            var batch = dispatcher(am).dispatch(assembly(List.of(input)), List.of(input.candidate()), analysis);
            var attempt = batch.candidates().get(0).attempts().get(0);
            assertEquals(0, am.calls, "Unavailable data or unsupported transcript cannot select a second predictor");
            assertEquals("HNF1A_REVEL", attempt.transcriptInput().path("predictionPath").asText());
            assertEquals(transcript.endsWith(".11") ? "SINGLE_SELECTABLE_MATCH" : "ZERO_MATCH", attempt.selection().path("outcome").asText());
            assertEquals(attempt.selection(), batch.preflight().candidates().get(0).attempts().get(0).selection());
            assertFalse(attempt.criterionEvaluations().stream().anyMatch(e -> "MET".equals(e.at("/evaluationResult/status").asText())));
            assertFalse(attempt.criterionEvaluations().stream().anyMatch(e -> "ACMG_AMP_2015:BP7".equals(e.path("criterionId").asText())));
        }
    }

    @Test void populationOnlyClinicalContextNeverConfirmsPrediction() throws Exception {
        var fixture = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        var original = fixture.attempt(); var draft = original.seed().draft();
        ((ObjectNode) draft.path("transcript")).put("selectionPolicy", org.nbscn.varvis.variantanalysis.service.AcmgRecommendations.PM2_CONTEXT_SELECTION);
        var seed = new ContextSeed(fixture.candidate().getCandidateId(), draft);
        var context = new TopNAcmgContextPlanner(mapper).plan(analysis, List.of(fixture.candidate()), 1, List.of(seed)).candidates().get(0).readyContexts().get(0);
        var attempt = new Attempt(original.candidateId(), original.hypothesisId(), original.binding(), original.provenance(), seed, context, List.of());
        var provider = new FixtureProvider("0.9", false, false);
        var result = dispatcher(provider).dispatch(assembly(List.of(new Fixture(fixture.candidate(), attempt))), List.of(fixture.candidate()), analysis);
        assertEquals(0, provider.calls); assertEquals(0, result.evaluatorCalls());
        var saved = result.candidates().get(0).attempts().get(0);
        assertEquals(context, saved.context()); assertTrue(saved.criterionEvaluations().isEmpty());
        assertTrue(saved.blockers().stream().anyMatch(b -> "PREDICTION_TRANSCRIPT_CONFIRMATION_REQUIRED".equals(b.reasonCode())));
    }
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    final AnalysisContext analysis = new AnalysisContext("analysis-synthetic-a2b", GenomeAssembly.GRCh38, AnalysisMode.WES);

    @Test
    void scoresAndInclusiveThresholdsUseExistingSupportingEvaluatorAndMutualExclusion() throws Exception {
        ArrayNode exports = mapper.createArrayNode();
        for (String score : List.of("0.01", "0.169", "0.1691", "0.5", "0.7919", "0.792", "0.99")) {
            FixtureProvider provider = new FixtureProvider(score, false, false);
            var input = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
            var batch = dispatcher(provider).dispatch(assembly(List.of(input)), List.of(input.candidate()), analysis);
            var result = batch.candidates().get(0).attempts().get(0);
            assertTrue(result.blockers().isEmpty(), result.blockers().toString());
            assertEquals(1, provider.calls);
            assertEquals(1, batch.providerCalls());
            assertEquals(1, batch.evaluatorCalls());
            assertEquals(2, result.criterionEvaluations().size());
            boolean bp4 = Double.parseDouble(score) <= 0.169;
            boolean pp3 = Double.parseDouble(score) >= 0.792;
            assertEquals(pp3 ? "MET" : "NOT_MET", result.criterionEvaluations().get(0).at("/evaluationResult/status").asText());
            assertEquals(bp4 ? "MET" : "NOT_MET", result.criterionEvaluations().get(1).at("/evaluationResult/status").asText());
            assertEquals(score, metric(result.atomicEvidence().get(0), "alphamissense.amPathogenicity").path("rawValue").asText());
            for (JsonNode evaluation : result.criterionEvaluations()) {
                assertEquals(3, evaluation.at("/ruleBinding/authoritySourceIds").size());
                assertEquals("SUPPORTING", evaluation.path("baseStrength").asText());
            }
            assertEquals("ENSP000001.2", result.transcriptInput().path("vepProteinIdentity").asText());
            assertEquals("P12345", metric(result.atomicEvidence().get(0), "alphamissense.uniprotId").path("rawValue").asText());
            assertEquals("NOT_ESTABLISHED", result.transcriptInput().path("proteinIdentityMappingStatus").asText());
            exports.add(mapper.valueToTree(batch));
        }
        write("varvis.e4a2b.syntheticOutput", exports);
    }

    @Test
    void realCspecMetadataAndMissingReviewNeverCallProviderOrEvaluator() throws Exception {
        FixtureProvider provider = new FixtureProvider("0.99", false, false);
        var input = fixture(1, "CDH1", "HGNC:1748", "MONDO:0007648", "NM_004360.5", null);
        var batch = dispatcher(provider).dispatch(assembly(List.of(input)), List.of(input.candidate()), analysis);
        assertEquals(0, batch.providerCalls()); assertEquals(0, batch.evaluatorCalls()); assertEquals(0, provider.calls);
        var result = batch.candidates().get(0).attempts().get(0);
        assertNull(result.selection());
        assertTrue(result.blockers().stream().anyMatch(value -> "CSPEC_MATCH_NOT_EXECUTABLE".equals(value.reasonCode())));
        assertTrue(result.atomicEvidence().isEmpty()); assertTrue(result.criterionEvaluations().isEmpty());
        write("varvis.e4a2b.blockedOutput", mapper.valueToTree(batch));
    }

    @Test
    void selectableMultipleOrMissingFormalSelectionCannotFallBackToGeneralProfile() throws Exception {
        FixtureProvider provider = new FixtureProvider("0.99", false, false);
        var input = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        var checked = new CSpecExecutionPreflight(mapper).check(assembly(List.of(input)));
        var original = checked.candidates().get(0).attempts().get(0);
        for (String outcome : List.of("SINGLE_SELECTABLE_MATCH", "MULTIPLE_EXACT_MATCHES", "ZERO_MATCH")) {
            var fake = new CSpecExecutionPreflight.SelectionAttempt(original.original(), outcome, List.of(), original.selectionId(),
                    outcome.equals("ZERO_MATCH") ? null : original.selection(), original.constraints(), List.of(), List.of(), List.of());
            var result = dispatcher(provider).execute(fake, input.candidate(), analysis, checked.registryPairing(), null);
            assertFalse(result.blockers().isEmpty());
            assertEquals(0, result.providerCalls()); assertEquals(0, result.evaluatorCalls());
        }
        assertEquals(0, provider.calls);
    }

    @Test
    void sameAlleleContextsRemainSeparateAndFailureDoesNotReorderOrFillRows() throws Exception {
        FixtureProvider provider = new FixtureProvider("0.9", false, false);
        var first = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        var second = fixture(2, "SYNTH2", "HGNC:2", "MONDO:0000002", "ENST000002.4", null);
        var third = fixture(3, "SYNTH3", "HGNC:3", "MONDO:0000003", "ENST000003.4", null);
        Attempt failed = new Attempt(second.attempt().candidateId(), second.attempt().hypothesisId(), second.attempt().binding(),
                second.attempt().provenance(), null, null, List.of(new Blocker("CONTEXT_NOT_READY", "SYNTHETIC_SOURCE_MISSING", "input")));
        var anotherHypothesis = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000004", "ENST000001.4", "hypothesis-second");
        AssemblyResult base = assembly(List.of(first, second, third));
        AssemblyResult assembled = new AssemblyResult(base.analysisId(), 3, 3, base.candidateIds(),
                List.of(new CandidateAssembly(first.candidate().getCandidateId(), List.of(first.attempt(), anotherHypothesis.attempt())),
                        new CandidateAssembly(second.candidate().getCandidateId(), List.of(failed)), base.candidates().get(2)), List.of(failed), false);
        ObjectNode before = mapper.valueToTree(assembled);
        var result = dispatcher(provider).dispatch(assembled, List.of(first.candidate(), second.candidate(), third.candidate()), analysis);
        assertEquals(3, result.candidates().size());
        assertEquals(List.of(2, 1, 1), result.candidates().stream().map(row -> row.attempts().size()).toList());
        assertEquals(3, result.providerCalls()); assertEquals(3, result.evaluatorCalls()); assertEquals(3, provider.calls);
        assertEquals(failed, result.preflight().unmatchedInputs().get(0));
        assertEquals(failed, result.candidates().get(1).attempts().get(0).preflight().original());
        assertEquals(0, result.candidates().get(1).attempts().get(0).providerCalls());
        var ready = result.candidates().stream().flatMap(row -> row.attempts().stream()).filter(value -> !value.atomicEvidence().isEmpty()).toList();
        assertEquals(1, ready.stream().map(value -> value.context().at("/variant/alleleId").asText()).distinct().count());
        assertEquals(3, ready.stream().map(value -> value.atomicEvidence().get(0).path("contextId").asText()).distinct().count());
        assertEquals(before, mapper.valueToTree(assembled));
        write("varvis.e4a2b.mixedOutput", mapper.valueToTree(result));
    }

    @Test
    void missingDuplicateReorderedOrDifferentAnalysisCandidatesBlockBeforeCalls() throws Exception {
        FixtureProvider provider = new FixtureProvider("0.9", false, false);
        var a = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        var b = fixture(2, "SYNTH2", "HGNC:2", "MONDO:0000002", "ENST000002.4", null);
        AssemblyResult assembled = assembly(List.of(a, b));
        for (List<VariantInterpretation> ranked : List.of(List.<VariantInterpretation>of(), List.of(a.candidate(), a.candidate()), List.of(b.candidate(), a.candidate()))) {
            var result = dispatcher(provider).dispatch(assembled, ranked, analysis);
            assertEquals(0, result.providerCalls()); assertEquals(0, result.evaluatorCalls());
        }
        var result = dispatcher(provider).dispatch(assembled, List.of(a.candidate(), b.candidate()),
                new AnalysisContext("analysis-other", GenomeAssembly.GRCh38, AnalysisMode.WES));
        assertEquals(0, result.providerCalls()); assertEquals(0, provider.calls);
    }

    @Test
    void exactAnnotationVersionAndGeneMustMatchAndCannotChooseAnotherTranscript() throws Exception {
        FixtureProvider provider = new FixtureProvider("0.9", false, false);
        for (String field : List.of("geneSymbol", "hgvsCoding", "version")) {
            var input = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
            TranscriptConsequence old = input.candidate().getTranscriptAnnotations().get(0);
            ObjectNode annotation = annotationNode(old);
            annotation.put(field, field.equals("version") ? "115.0" : field.equals("geneSymbol") ? "OTHER" : "ENST000001.5:c.100A>G");
            input.candidate().applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
            var result = dispatcher(provider).dispatch(assembly(List.of(input)), List.of(input.candidate()), analysis);
            assertEquals(0, result.providerCalls(), field); assertEquals(0, result.evaluatorCalls());
        }
        assertEquals(0, provider.calls);
    }

    @Test
    void versionedAccessionCannotHideConflictingTranscriptIdOrCodingIdentity() throws Exception {
        FixtureProvider provider = new FixtureProvider("0.9", false, false);
        for (String field : List.of("transcriptId", "hgvsCoding")) {
            var input = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
            ObjectNode annotation = annotationNode(input.candidate().getTranscriptAnnotations().get(0));
            annotation.put("transcriptAccession", "ENST000001.4");
            annotation.put(field, field.equals("transcriptId") ? "ENST000002.4" : "ENST000002.4:c.100A>G");
            input.candidate().applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
            var result = dispatcher(provider).dispatch(assembly(List.of(input)), List.of(input.candidate()), analysis);
            assertEquals(0, result.providerCalls()); assertEquals(0, result.evaluatorCalls());
            assertTrue(result.candidates().get(0).attempts().get(0).blockers().stream()
                    .anyMatch(value -> "TRANSCRIPT_IDENTITY_CONFLICT".equals(value.reasonCode())));
        }
        assertEquals(0, provider.calls);
    }

    @Test
    void missingProteinAndUnavailableResourcePreserveNotRunAndNotEvaluated() throws Exception {
        ArrayNode exports = mapper.createArrayNode();
        for (String missing : List.of("hgvsProtein", "proteinPosition", "aminoAcids", "resource")) {
            var input = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
            if (!missing.equals("resource")) {
                ObjectNode annotation = annotationNode(input.candidate().getTranscriptAnnotations().get(0));
                annotation.remove(missing);
                input.candidate().applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
            }
            var result = dispatcher(new AlphaMissenseProvider(properties())).dispatch(assembly(List.of(input)), List.of(input.candidate()), analysis);
            var attempt = result.candidates().get(0).attempts().get(0);
            assertTrue(attempt.blockers().isEmpty(), attempt.blockers().toString());
            assertEquals("NOT_RUN", attempt.atomicEvidence().get(0).at("/resourceExecution/status").asText());
            assertEquals(missing.equals("resource") ? "RESOURCE_UNAVAILABLE" : "SELECTED_PROTEIN_CONTEXT_INCOMPLETE",
                    attempt.atomicEvidence().get(0).at("/resourceExecution/reasonCode").asText());
            notEvaluated(attempt);
            exports.add(mapper.valueToTree(result));
        }
        write("varvis.e4a2b.missingOutput", exports);
    }

    @Test
    void noRecordDuplicateHitTimeoutAndFailedResourceDoNotBecomeNotMet() throws Exception {
        ArrayNode exports = mapper.createArrayNode();
        for (String mode : List.of("noRecord", "duplicate", "timeout", "failed")) {
            FixtureProvider provider = new FixtureProvider(mode.equals("noRecord") ? null : "0.9", mode.equals("duplicate"), mode.equals("failed"));
            provider.timeout = mode.equals("timeout");
            var input = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
            var result = dispatcher(provider).dispatch(assembly(List.of(input)), List.of(input.candidate()), analysis);
            var attempt = result.candidates().get(0).attempts().get(0);
            assertTrue(attempt.blockers().isEmpty(), attempt.blockers().toString());
            notEvaluated(attempt);
            assertEquals(1, result.providerCalls()); assertEquals(1, result.evaluatorCalls());
            if (mode.equals("noRecord")) assertEquals("NO_RECORD", attempt.atomicEvidence().get(0).at("/queryOutcome/status").asText());
            if (mode.equals("duplicate")) assertEquals("HIT", attempt.atomicEvidence().get(0).at("/queryOutcome/status").asText());
            if (mode.equals("failed")) assertEquals("FAILED", attempt.atomicEvidence().get(0).at("/resourceExecution/status").asText());
            if (mode.equals("timeout")) assertEquals("TIMEOUT", attempt.atomicEvidence().get(0).at("/resourceExecution/status").asText());
            exports.add(mapper.valueToTree(result));
        }
        write("varvis.e4a2b.failuresOutput", exports);
    }

    @Test
    void invalidContextOrMismatchedProviderVersionCannotDispatch() throws Exception {
        var input = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        ObjectNode forged = input.attempt().context();
        forged.put("contextId", "context-sha256-" + "0".repeat(64));
        var invalid = new Fixture(input.candidate(), new Attempt(input.attempt().candidateId(), input.attempt().hypothesisId(),
                input.attempt().binding(), input.attempt().provenance(), input.attempt().seed(), forged, List.of()));
        FixtureProvider provider = new FixtureProvider("0.9", false, false);
        var blocked = dispatcher(provider).dispatch(assembly(List.of(invalid)), List.of(input.candidate()), analysis);
        assertEquals(0, blocked.providerCalls()); assertEquals(0, blocked.evaluatorCalls()); assertEquals(0, provider.calls);
        assertFalse(blocked.candidates().get(0).attempts().get(0).blockers().isEmpty());

        var wrongVersion = new FixtureProvider("0.9", false, false) {
            @Override public String version() { return "SYNTHETIC_WRONG_VERSION"; }
        };
        blocked = dispatcher(wrongVersion).dispatch(assembly(List.of(input)), List.of(input.candidate()), analysis);
        assertEquals(0, blocked.providerCalls()); assertEquals(0, blocked.evaluatorCalls()); assertEquals(0, wrongVersion.calls);
        assertTrue(blocked.candidates().get(0).attempts().get(0).atomicEvidence().isEmpty());
        assertFalse(blocked.candidates().get(0).attempts().get(0).blockers().isEmpty());
    }

    @Test
    void nonMissenseInputKeepsNotApplicableInsteadOfNotMet() throws Exception {
        var input = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        ObjectNode annotation = annotationNode(input.candidate().getTranscriptAnnotations().get(0));
        annotation.put("consequence", "intron_variant");
        annotation.remove(List.of("proteinPosition", "aminoAcids", "hgvsProtein"));
        input.candidate().applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        var result = dispatcher(new AlphaMissenseProvider(properties())).dispatch(assembly(List.of(input)), List.of(input.candidate()), analysis);
        var attempt = result.candidates().get(0).attempts().get(0);
        assertTrue(attempt.blockers().isEmpty(), attempt.blockers().toString());
        assertEquals("NOT_APPLICABLE", attempt.atomicEvidence().get(0).at("/resourceApplicability/status").asText());
        notEvaluated(attempt);
        write("varvis.e4a2b.notApplicableOutput", mapper.valueToTree(result));
    }

    @Test
    void evaluatorExceptionStaysLocalWithoutFabricatedEvaluation() throws Exception {
        FixtureProvider provider = new FixtureProvider("0.9", false, false);
        var a = fixture(1, "SYNTH1", "HGNC:1", "MONDO:0000001", "ENST000001.4", null);
        var b = fixture(2, "SYNTH2", "HGNC:2", "MONDO:0000002", "ENST000002.4", null);
        int[] calls = {0};
        var dispatcher = new ExplicitAlphaMissenseDispatcher(mapper, provider, (context, atomic, profile, rules, selection) -> {
            if (calls[0]++ == 0) throw new IllegalArgumentException("synthetic evaluation failure");
            return new AlphaMissenseCriterionEvaluator(mapper).evaluate(context, atomic, profile, rules, selection);
        });
        var result = dispatcher.dispatch(assembly(List.of(a, b)), List.of(a.candidate(), b.candidate()), analysis);
        assertEquals(2, result.providerCalls()); assertEquals(2, result.evaluatorCalls());
        assertTrue(result.candidates().get(0).attempts().get(0).criterionEvaluations().isEmpty());
        assertFalse(result.candidates().get(0).attempts().get(0).blockers().isEmpty());
        assertEquals(2, result.candidates().get(1).attempts().get(0).criterionEvaluations().size());
        write("varvis.e4a2b.evaluatorFailureOutput", mapper.valueToTree(result));
    }

    Fixture fixture(int ordinal, String gene, String hgnc, String mondo, String transcript, String hypothesis) throws Exception {
        var key = new NormalizedVariantKey("GRCh38", "1", 100, "A", "G");
        VariantInterpretation candidate = new VariantInterpretation(CandidateIdentity.exomiser(key.canonicalId(), ordinal, gene, "AD", ordinal),
                RuntimeEvidenceV2ProviderWiringTest.variant(key).getObservation(), List.of(), List.of());
        candidate.applyTranscriptAnnotations(List.of(annotation(gene, transcript)));
        ObjectNode context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, key, transcript.substring(0, transcript.lastIndexOf('.')));
        context.withObjectProperty("gene").put("hgncId", hgnc).put("symbol", gene);
        context.withObjectProperty("disease").put("mondoId", mondo).put("label", "Synthetic disease context");
        context.withObjectProperty("transcript").put("version", Integer.parseInt(transcript.substring(transcript.lastIndexOf('.') + 1))).put("annotationSourceVersion", "116.0");
        ObjectNode draft = context.deepCopy();
        draft.remove(List.of("schemaVersion", "contextId", "canonicalizationProfile", "contextCompleteness"));
        ((ObjectNode) draft.path("variant")).remove("alleleId");
        ContextSeed seed = new ContextSeed(candidate.getCandidateId(), draft);
        context = new TopNAcmgContextPlanner(mapper).plan(analysis, List.of(candidate), 1, List.of(seed)).candidates().get(0).readyContexts().get(0);
        var binding = new DiseaseContextBinding(candidate.getCandidateId(), hypothesis == null ? "local-hypothesis" : hypothesis,
                hgnc, gene, mondo, "Synthetic disease context", "AUTOSOMAL_DOMINANT", "Definitive", "2026-09-05", "SYNTHETIC", "synthetic/relationship", "READY", null, null);
        SourceRef ref = new SourceRef("SYNTHETIC", "1", "synthetic/receipt", "f".repeat(64));
        var provenance = new Provenance("SYNTHETIC", ref, ref, ref, ref, new AssemblyLineage("GRCh38", "NATIVE",
                new Allele("GRCh38", "1", 100, "A", "G"), null, ref));
        return new Fixture(candidate, new Attempt(candidate.getCandidateId(), binding.hypothesisId(), binding, provenance, seed, context, List.of()));
    }
    AssemblyResult assembly(List<Fixture> values) {
        return new AssemblyResult(analysis.getAnalysisId(), values.size(), values.size(), values.stream().map(value -> value.candidate().getCandidateId()).toList(),
                values.stream().map(value -> new CandidateAssembly(value.candidate().getCandidateId(), List.of(value.attempt()))).toList(), List.of(), false);
    }
    private TranscriptConsequence annotation(String gene, String transcript) {
        String accession = transcript.substring(0, transcript.lastIndexOf('.'));
        return new TranscriptConsequence(gene, null, accession, transcript + ":c.100A>G", "ENSP000001.2:p.Lys34Arg", "missense_variant",
                "ENSG000001", accession, "protein_coding", "MODERATE", "1/2", null, true, true, false,
                "34", "K/R", "aAa/aGa", null, null, null, "VEP_SYNTHETIC", "COMPLETED", "116.0", null);
    }
    private ObjectNode annotationNode(TranscriptConsequence value) {
        ObjectNode node = mapper.valueToTree(value);
        // Retain constructor inputs, excluding the derived identity/alias getters.
        return node.retain("geneSymbol", "entrezGeneId", "transcriptAccession", "hgvsCoding", "hgvsProtein", "consequence",
                "geneId", "transcriptId", "transcriptSource", "impact", "exon", "intron", "canonical", "maneSelect",
                "manePlusClinical", "proteinPosition", "aminoAcids", "codons", "lof", "lofFilter", "lofFlags",
                "source", "status", "version", "timestamp");
    }
    private ExplicitAlphaMissenseDispatcher dispatcher(AlphaMissenseProvider value) { return new ExplicitAlphaMissenseDispatcher(mapper, value); }
    private VariantAnalysisProperties properties() {
        var value = new VariantAnalysisProperties(); value.getAlphaMissense().setEnabled(true); value.getAlphaMissense().setVersion("2023-08-03"); return value;
    }
    private void notEvaluated(ExplicitAlphaMissenseDispatcher.DispatchAttempt attempt) {
        assertEquals(2, attempt.criterionEvaluations().size());
        attempt.criterionEvaluations().forEach(value -> assertEquals("NOT_EVALUATED", value.at("/evaluationResult/status").asText()));
    }
    private JsonNode metric(JsonNode atomic, String id) {
        for (JsonNode metric : atomic.path("measurements")) if (id.equals(metric.path("metricId").asText())) return metric;
        throw new AssertionError(id);
    }
    private void write(String property, JsonNode value) throws Exception {
        String path = System.getProperty(property);
        if (path != null) Files.writeString(Path.of(path), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value), StandardCharsets.UTF_8);
    }
    record Fixture(VariantInterpretation candidate, Attempt attempt) { }
    class FixtureProvider extends AlphaMissenseProvider {
        final String score; final boolean duplicate; final boolean failed; boolean timeout; int calls;
        FixtureProvider(String score, boolean duplicate, boolean failed) { super(properties()); this.score = score; this.duplicate = duplicate; this.failed = failed; }
        @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotateSelectedTranscripts(List<VariantInterpretation> variants, AnalysisContext context,
                                                                                                Map<NormalizedVariantKey, TranscriptConsequence> selected) {
            calls++;
            if (failed) throw new IllegalStateException("synthetic provider failure");
            if (timeout) throw new IllegalStateException(new TimeoutException("synthetic timeout"));
            var key = variants.get(0).getObservation().getKey();
            var consequence = selected.get(key);
            List<String> records = new ArrayList<>();
            if (score != null) {
                String row = key.getChromosome() + "\t" + key.getPosition() + "\t" + key.getReference() + "\t" + key.getAlternate()
                        + "\thg38\tP12345\t" + consequence.getVersionedTranscriptId() + "\t" + consequence.getAminoAcidChange() + "\t" + score + "\tambiguous";
                records.add(row); if (duplicate) records.add(row);
            }
            return Map.of(key, List.of(resolveExactRecords(records, key, consequence)));
        }
    }
}
