package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.ExplicitCaseInputCoordinator.*;
import static org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;

/** Synthetic VCF/reference/tool transport; actual source gates, equivalence and context assembler. */
class SelectedEvidencePreparationTest {
    @TempDir Path temporary;
    record Prepared(SelectedEvidencePreparation result, VariantInterpretation original,
                    com.fasterxml.jackson.databind.JsonNode before, AtomicInteger calls,
                    com.fasterxml.jackson.databind.ObjectMapper mapper) { }

    Prepared prepare(boolean fail, boolean sourceBlocked, boolean changed, int requestedN) throws Exception {
        return prepare(fail, sourceBlocked, changed, requestedN, false);
    }

    Prepared prepare(boolean fail, boolean sourceBlocked, boolean changed, int requestedN, boolean selectTranscript) throws Exception {
        return prepare(fail, sourceBlocked, changed, requestedN, selectTranscript, false);
    }

    Prepared prepare(boolean fail, boolean sourceBlocked, boolean changed, int requestedN, boolean selectTranscript, boolean pp1Only) throws Exception {
        var fixture = new TopNSelectedNormalizationTest(); fixture.temporary = temporary; fixture.mapper.findAndRegisterModules();
        var f = fixture.fixture("ATCG");
        int position = changed ? 2 : 3; String ref = changed ? "T" : "C", alt = changed ? "TT" : "A";
        String vcf = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf(
                "chr11\t" + position + "\t.\t" + ref + "\t" + alt + "\t.\tPASS\t.\tGT:DP:GQ:AD:PS\t0|1:20:99:9,11:100")
                .replace("#CHROM", "##FORMAT=<ID=DP,Number=1,Type=Integer,Description=\"Synthetic depth\">\n"
                        + "##FORMAT=<ID=GQ,Number=1,Type=Integer,Description=\"Synthetic quality\">\n"
                        + "##FORMAT=<ID=AD,Number=R,Type=Integer,Description=\"Synthetic allele depth\">\n"
                        + "##FORMAT=<ID=PS,Number=1,Type=Integer,Description=\"Synthetic phase set\">\n#CHROM");
        var run = org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.run(temporary.resolve("run"), "common-adapter-test",
                GenomeAssembly.GRCh38, vcf, org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv(
                        "1\t11\t" + position + "\t" + ref + "\t" + alt + "\tHBB\t3043\tAR\t0.8"));
        var candidate = run.execution().getInterpretations().get(0);
        var mapping = new ExplicitCaseInputCoordinatorTest(); mapping.temporary = temporary; mapping.setup();
        candidate.applyTranscriptAnnotations(mapping.annotations.get("public-hbb-compatible-e7v"));
        var before = fixture.mapper.valueToTree(candidate); var calls = new AtomicInteger();
        VcfNormalizer tool = (input, directory, assembly) -> {
            calls.incrementAndGet();
            if (fail) throw new IllegalStateException("Could not start bcftools");
            var output = Files.writeString(directory.resolve("synthetic-normalized.vcf"),
                    "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\nchr11\t1\tVARVIS_SELECTED_ALT\tA\tAT\t.\tPASS\t.\n");
            var manifest = new NormalizationRunManifest("PASSED", "GRCh38", "SYNTHETIC", f.properties().getNormalization().referenceSha256For(GenomeAssembly.GRCh38),
                    "bcftools", "SYNTHETIC", "SYNTHETIC", false, 1, 1, 0, 1, 0, 1, "2026-10-10T00:00:00Z", "2026-10-10T00:00:01Z", null, null, "SYNTHETIC");
            return new NormalizationResult(output, true, "SYNTHETIC", List.of(), manifest);
        };
        ReflectionTestUtils.setField(requireNonNull(f.producer()), "selectedNormalizer", new SelectedAlleleNormalizer(fixture.mapper, tool));
        var provenance = new HypothesisProvenance("SYNTHETIC_CALLER", "1.0.0", "synthetic/shared-preparation");
        var choice = mapping.choices.path("fixedChoices").get(0);
        String transcript = choice.path("transcript").asText(); int dot = transcript.lastIndexOf('.');
        var tx = selectTranscript ? new TranscriptSelection(transcript.substring(0, dot), Integer.valueOf(transcript.substring(dot + 1)),
                choice.path("selectionPolicy").asText(), "VEP", "116.0", provenance, choice.path("selectionBasis").asText()) : null;
        var hypotheses = new ArrayList<HypothesisInput>();
        for (String id : List.of("context-one", "context-two")) hypotheses.add(new HypothesisInput(
                new PatientDiseaseHypothesis(candidate.getCandidateId(), id, "HGNC:4827",
                        pp1Only ? "MONDO:9999998" : "context-two".equals(id) ? "MONDO:0099999" : choice.path("syntheticHypothesisMondoId").asText(), "AR", provenance), "UNKNOWN", tx));
        var declaration = new CaseDeclaration(run.job().getId(), run.execution().getInputSource(), "synthetic-proband", "SYNTHETIC",
                new PatientDeclaration("patient-context-shared-preparation", "PROBAND", "UNKNOWN", null, provenance), hypotheses);
        var coordinator = new ExplicitCaseInputCoordinator(fixture.mapper, mapping.resolver, f.producer(), mapping.sources);
        var blockers = sourceBlocked ? Map.of(candidate.getCandidateId(), new ExplicitEvidenceContextAssembler.Blocker("CONTEXT_NOT_READY", "SYNTHETIC_SOURCE_CHANGED", "source"))
                : Map.<String, ExplicitEvidenceContextAssembler.Blocker>of();
        var result = pp1Only ? coordinator.preparePp1Selection(run.job(), run.conversion(), run.execution(),
                new AnalysisContext(run.job().getId(), GenomeAssembly.GRCh38, AnalysisMode.WES), run.execution().getInterpretations(),
                requestedN, List.of(declaration), blockers) : coordinator.prepareSelected(run.job(), run.conversion(), run.execution(),
                new AnalysisContext(run.job().getId(), GenomeAssembly.GRCh38, AnalysisMode.WES), run.execution().getInterpretations(),
                requestedN, List.of(declaration), blockers);
        return new Prepared(result, candidate, before, calls, fixture.mapper);
    }

    @Test void pp1DeclaredDiseaseRetainsNormalizationButCannotBeConsumedByOtherCriteria() throws Exception {
        var p = prepare(false, false, true, 1, false, true); String id = p.original().getCandidateId();
        var scoped = p.result().pp1SelectionContext(id, "context-one");
        assertEquals("MONDO:9999998", scoped.at("/disease/mondoId").asText());
        assertEquals("AT", scoped.at("/variant/evidenceQueryAllele/alternate").asText());
        assertEquals("DECLARED_SCOPE_ONLY", p.result().assembly().candidates().get(0).attempts().get(0).binding().status());
        assertEquals("PP1_SELECTION_NOT_GENERAL_EVIDENCE_CONTEXT", assertThrows(IllegalArgumentException.class,
                () -> p.result().context(id,"context-one")).getMessage());
        assertThrows(IllegalArgumentException.class, () -> p.result().queryCandidate(id,"context-one",false));
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
        assertEquals(1, p.calls().get());
    }

    @Test void pp1SelectionStillRejectsChangedExecutionSources() throws Exception {
        var p = prepare(false, true, false, 1, false, true);
        assertEquals("SYNTHETIC_SOURCE_CHANGED", assertThrows(IllegalArgumentException.class,
                () -> p.result().pp1SelectionContext(p.original().getCandidateId(),"context-one")).getMessage());
        assertEquals(0,p.calls().get());
    }

    @Test void normalizesOnceAndReturnsDetachedTransportViewsForMultipleContexts() throws Exception {
        var p = prepare(false, false, true, 1); String id = p.original().getCandidateId();
        var one = p.result().context(id, "context-one"); var two = p.result().context(id, "context-two");
        assertNotEquals(one.path("contextId"), two.path("contextId"), "Different declared disease contexts retain separate identities");
        assertEquals(List.of("context-one", "context-two"), p.result().assembly().candidates().get(0).attempts().stream()
                .map(mappedItem -> mappedItem.hypothesisId()).toList());
        assertEquals(one.at("/variant/evidenceQueryAllele"), two.at("/variant/evidenceQueryAllele"));
        assertEquals(1, p.calls().get());
        for (String hypothesis : List.of("context-one", "context-two")) {
            var view = p.result().queryCandidate(id, hypothesis, false);
            assertEquals(1, view.getObservation().getKey().getPosition());
            assertEquals("AT", view.getObservation().getKey().getAlternate());
            assertEquals(p.original().getObservation().getGenotype(), view.getObservation().getGenotype());
            assertEquals("REF|TARGET_ALT", view.getAnalysisVcfCall().getCandidateRelativeGenotype());
            assertEquals(9, view.getAnalysisVcfCall().getReferenceDepth());
            assertEquals(11, view.getAnalysisVcfCall().getTargetAlleleDepth());
            assertEquals(1, view.getAnalysisVcfCall().getTargetAlleleIndex());
            assertNotNull(view.getAnalysisVcfCall().getPhaseSetToken());
            assertEquals(p.original().getObservation().getAlleleDepths(), view.getObservation().getAlleleDepths());
            assertEquals(p.mapper().valueToTree(p.original().getAnalysisVcfCall()), p.mapper().valueToTree(view.getAnalysisVcfCall()));
            assertTrue(view.getTranscriptAnnotations().isEmpty()); assertTrue(view.getConsequences().isEmpty());
            view.applyTranscriptAnnotations(p.original().getTranscriptAnnotations());
            assertTrue(p.result().queryCandidate(id, hypothesis, false).getTranscriptAnnotations().isEmpty());
            assertEquals("QUERY_TRANSCRIPT_REANNOTATION_REQUIRED", assertThrows(IllegalArgumentException.class,
                    () -> p.result().queryCandidate(id, hypothesis, true)).getMessage());
        }
        assertEquals(1, p.calls().get());
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
        one.removeAll(); assertFalse(p.result().context(id, "context-one").isEmpty());
    }

    @Test void missingToolKeepsTopNAndNeverReturnsAnOldQueryKey() throws Exception {
        var p = prepare(true, false, true, 2); var id = p.original().getCandidateId();
        assertEquals(1, p.result().assembly().selectedCount());
        assertEquals(List.of(id), p.result().assembly().candidateIds());
        assertEquals(1, p.calls().get());
        assertEquals("NORMALIZATION_TOOL_UNAVAILABLE", assertThrows(IllegalArgumentException.class,
                () -> p.result().queryCandidate(id, "context-one", false)).getMessage());
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
    }

    @Test void restoredSourceBlockerStopsBeforeNormalization() throws Exception {
        var p = prepare(false, true, true, 1);
        assertEquals(0, p.calls().get());
        assertEquals("SYNTHETIC_SOURCE_CHANGED", assertThrows(IllegalArgumentException.class,
                () -> p.result().queryCandidate(p.original().getCandidateId(), "context-one", false)).getMessage());
    }

    @Test void unchangedQueryRetainsAnnotationCopiesAndRejectsUnknownAttempts() throws Exception {
        var p = prepare(false, false, false, 1); var id = p.original().getCandidateId();
        var view = p.result().queryCandidate(id, "context-one", false);
        assertEquals(p.before(), p.mapper().valueToTree(view));
        view.applyTranscriptAnnotations(List.of());
        assertFalse(p.result().queryCandidate(id, "context-one", false).getTranscriptAnnotations().isEmpty());
        assertEquals(0, p.calls().get());
        assertThrows(IllegalArgumentException.class, () -> p.result().queryCandidate("not-selected", "context-one", false));
        assertThrows(IllegalArgumentException.class, () -> p.result().queryCandidate(id, "unknown-hypothesis", false));
    }

    @Test void sharedProducerRetainsTheFiftyBaseNormalizedScope() throws Exception {
        var fixture = new TopNSelectedNormalizationTest(); fixture.temporary = temporary;
        var f = fixture.fixture("ATCG"); var alternate = "A" + "T".repeat(50);
        var run = fixture.run(1, "A", alternate, alternate, "0|1");
        var candidate = run.execution().getInterpretations().get(0);
        var input = new ExplicitEvidenceContextAssembler.ExplicitInput(candidate.getCandidateId(), "scope-only", null, "SYNTHETIC", null, null, null);
        var result = f.producer().produceSelectedContexts(run.job(), run.conversion(), run.execution(),
                new AnalysisContext(run.job().getId(), GenomeAssembly.GRCh38, AnalysisMode.WES), List.of(candidate), 1, List.of(input), ignored -> null);
        assertEquals("NORMALIZED_ALLELE_OUTSIDE_FROZEN_SCOPE", result.blockers(result.inputs().get(0)).get(0).reasonCode());
    }

    @Test void changedAlleleReannotatesOnceBeforeReceiptBindingAndReusesAcrossContexts() throws Exception {
        var p = prepare(false, false, true, 1, true); var id = p.original().getCandidateId();
        var calls = new AtomicInteger();
        var updated = p.mapper().valueToTree(p.original().getTranscriptAnnotations());
        var selected = p.result().context(id, "context-one").path("transcript");
        String coding = selected.path("accession").asText() + "." + selected.path("version").asText() + ":c.2dup";
        for (var a : updated) ((com.fasterxml.jackson.databind.node.ObjectNode)a).put("hgvsCoding", coding)
                .put("source", "VEP_CACHED").put("timestamp", "2026-10-28T12:00:00.123456789Z");
        var annotations = p.mapper().readerForListOf(TranscriptConsequence.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .<List<TranscriptConsequence>>readValue(updated);
        p.result().prepareQueryAnnotations((variants, context) -> {
            calls.incrementAndGet(); assertEquals(1, variants.size());
            var v = variants.get(0); assertEquals(1, v.getObservation().getKey().getPosition());
            assertTrue(v.getTranscriptAnnotations().isEmpty());
            return Map.of(v.getObservation().getKey(), annotations);
        }, new AnalysisContext("query-test", GenomeAssembly.GRCh38, AnalysisMode.WES));
        p.result().prepareQueryAnnotations((variants, context) -> { throw new AssertionError("Prepared query must be reused"); }, null);
        for (String hypothesis : List.of("context-one", "context-two")) {
            var view = p.result().queryCandidate(id, hypothesis, true);
            assertEquals(coding, view.getTranscriptAnnotations().get(0).getHgvsCoding());
            assertEquals("2026-10-28T12:00:00.123456789Z", view.getTranscriptAnnotations().get(0).getTimestamp().toString());
            assertEquals(p.mapper().valueToTree(p.original().getAnalysisVcfCall()), p.mapper().valueToTree(view.getAnalysisVcfCall()));
            var ref = p.result().queryAnnotationSource(id, hypothesis);
            assertEquals(1, p.result().resolve(ref).at("/queryAllele/position").asInt());
            var projection = p.result().legacyProjection(id, hypothesis);
            assertEquals(p.mapper().valueToTree(ref), projection.snapshot().path("queryAnnotationSource"));
            var receipt = p.result().sources().stream().filter(s -> s.receipt(p.mapper()).path("source").asText().equals(ref.source())).findFirst().orElseThrow().receipt(p.mapper());
            var restored = ExplicitContextSourceLedger.verifiedBytes(p.mapper(), receipt.path("rawJson").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8), receipt.path("sha256").asText());
            assertEquals(p.result().resolve(ref), restored.resolve(ref));
        }
        assertEquals(1, calls.get()); assertEquals(p.before(), p.mapper().valueToTree(p.original()));
    }

    @Test void changedAlleleRejectsWrongKeyAndNeverFallsBackToOriginalAnnotations() throws Exception {
        var p = prepare(false, false, true, 1, true); var id = p.original().getCandidateId();
        p.result().prepareQueryAnnotations((variants, context) -> Map.of(p.original().getObservation().getKey(), p.original().getTranscriptAnnotations()),
                new AnalysisContext("query-test", GenomeAssembly.GRCh38, AnalysisMode.WES));
        assertEquals("QUERY_ANNOTATION_NO_RECORD", assertThrows(IllegalArgumentException.class,
                () -> p.result().queryCandidate(id, "context-one", true)).getMessage());
        assertEquals(p.before(), p.mapper().valueToTree(p.original()));
    }

    @Test void unchangedCandidatesNeverScheduleVep() throws Exception {
        var p = prepare(false, false, false, 1, true);
        p.result().prepareQueryAnnotations((variants, context) -> { throw new AssertionError("Unchanged identity must reuse annotations"); }, null);
        assertEquals(p.before(), p.mapper().valueToTree(p.result().queryCandidate(p.original().getCandidateId(), "context-one", true)));
    }

    @Test void sourceBlockedCandidatesNeverScheduleVep() throws Exception {
        var p = prepare(false, true, true, 1, true);
        p.result().prepareQueryAnnotations((variants, context) -> { throw new AssertionError("Source must be checked first"); }, null);
        assertEquals(0, p.calls().get());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"gene,QUERY_ANNOTATION_IDENTITY_MISMATCH", "version,QUERY_ANNOTATION_IDENTITY_MISMATCH",
            "duplicate,QUERY_ANNOTATION_TRANSCRIPT_AMBIGUOUS", "transcript,QUERY_ANNOTATION_TRANSCRIPT_MISSING",
            "timeout,QUERY_ANNOTATION_TIMEOUT", "disabled,QUERY_ANNOTATION_DISABLED", "exception,QUERY_ANNOTATION_FAILED",
            "source,QUERY_ANNOTATION_IDENTITY_MISMATCH"})
    void reannotationFailureNeverBorrowsOldOrOtherTranscript(String mode, String reason) throws Exception {
        var p = prepare(false, false, true, 1, true); var id = p.original().getCandidateId();
        var updated = p.mapper().valueToTree(p.original().getTranscriptAnnotations());
        for (var entry : updated) {
            var a = (com.fasterxml.jackson.databind.node.ObjectNode) entry;
            switch (mode) {
                case "gene" -> a.put("geneSymbol", "OTHER");
                case "version" -> a.put("version", "117.0");
                case "source" -> a.put("source", "VEP_UNTRUSTED");
                case "transcript" -> { a.put("transcriptAccession", "ENST999999.1"); a.put("transcriptId", "ENST999999.1"); }
                case "timeout" -> a.put("status", "TIMEOUT");
                case "disabled" -> a.put("status", "DISABLED");
                default -> { }
            }
        }
        if ("duplicate".equals(mode)) ((com.fasterxml.jackson.databind.node.ArrayNode) updated)
                .addAll((com.fasterxml.jackson.databind.node.ArrayNode) updated.deepCopy());
        var annotations = p.mapper().readerForListOf(TranscriptConsequence.class)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .<List<TranscriptConsequence>>readValue(updated);
        var calls = new AtomicInteger();
        p.result().prepareQueryAnnotations((variants, context) -> {
            calls.incrementAndGet(); if ("exception".equals(mode)) throw new IllegalStateException("fixture failure");
            return Map.of(variants.get(0).getObservation().getKey(), annotations);
        }, new AnalysisContext("query-test", GenomeAssembly.GRCh38, AnalysisMode.WES));
        for (String hypothesis : List.of("context-one", "context-two"))
            assertEquals(reason, assertThrows(IllegalArgumentException.class, () -> p.result().queryCandidate(id, hypothesis, true)).getMessage());
        assertEquals(1, calls.get()); assertEquals(p.before(), p.mapper().valueToTree(p.original()));
    }
}
