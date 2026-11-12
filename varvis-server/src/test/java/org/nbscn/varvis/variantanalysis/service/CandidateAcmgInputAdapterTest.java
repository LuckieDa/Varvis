package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.Run;
import org.nbscn.varvis.variantanalysis.provider.AlphaMissenseProvider;
import org.nbscn.varvis.variantanalysis.provider.ExplicitAlphaMissenseDispatcher;
import org.nbscn.varvis.variantanalysis.provider.VepJsonOutputParser;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.sha;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.tsv;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.vcf;

/** Synthetic task/runner with retained public allele/VEP/REF receipts; no real Exomiser execution. */
class CandidateAcmgInputAdapterTest {
    private static final Path DOCS = Path.of("../docs/execution");
    private static final String ANALYSIS_ID = "analysis-synthetic-e4-a3a-candidate-input";
    private static final String HBB_VCF = "11\t5227002\tpublic-hbb-compatible-e7v\tT\tA\t.\tPASS\t.\tGT\t.";
    private static final String GJB2_VCF = "13\t20189473\tpublic-gjb2-20189473-ct\tC\tT\t.\tPASS\t.\tGT\t.";
    private static final String HBB_TSV = "1\t11\t5227002\tT\tA\tHBB\t3043\tAR\t0.8";
    private static final String GJB2_TSV = "2\t13\t20189473\tC\tT\tGJB2\t2706\tAR\t0.7";
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnalysisContext analysis = new AnalysisContext(ANALYSIS_ID, GenomeAssembly.GRCh38, AnalysisMode.WES);
    private final List<ExplicitContextSourceLedger> sources = new ArrayList<>();
    private final ArrayNode exportedSources = mapper.createArrayNode();
    private ObjectNode accepted;
    private PatientDiseaseContextBindingResolver resolver;
    private Map<String, List<TranscriptConsequence>> annotations;
    private SourceRef reference;
    private int runOrdinal;

    @BeforeEach
    void setup() throws Exception {
        accepted = (ObjectNode) mapper.readTree(Files.readAllBytes(DOCS.resolve("E4_A2B_ACCEPTANCE_EXACT_DISPATCH_2026-09-06.json")));
        JsonNode archive = mapper.readTree(Files.readAllBytes(DOCS.resolve("E4_A2B_ACCEPTANCE_SOURCE_RECEIPTS_2026-09-06.json")));
        JsonNode vep = null;
        for (var receipt : archive.path("receipts")) {
            if (receipt.path("relativeLocator").asText().equals("e4-a2b-acceptance-final-preparation-attempt-1/real-vep-output.jsonl")) vep = receipt;
        }
        assertNotNull(vep);
        byte[] rawVep = vep.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8);
        assertEquals(vep.path("sha256").asText(), sha(rawVep));
        Path output = temporary.resolve("retained-real-vep.jsonl");
        Files.write(output, rawVep);
        annotations = new VepJsonOutputParser(mapper).parseByVariantId(output, "VEP", "116.0").getAnnotationsByVariantId();
        JsonNode source = accepted.at("/smokeInputProvenance/sourceReceipts/0");
        byte[] bytes = source.path("rawJson").asText().getBytes(StandardCharsets.UTF_8);
        assertEquals(source.path("sha256").asText(), sha(bytes));
        Path path = temporary.resolve("reference-ledger.json");
        Files.write(path, bytes);
        var ledger = ExplicitContextSourceLedger.load(mapper, path, sha(bytes));
        sources.add(ledger);
        exportedSources.add(source.deepCopy());
        reference = ledger.reference("reference");
        resolver = subsetResolver();
    }

    @Test
    void parsedAndHydratedCandidatesWithEmptyOriginalRecordReachExistingChainWithoutGtOrDp() throws Exception {
        Run run = run(vcf(HBB_VCF, GJB2_VCF), tsv(HBB_TSV, GJB2_TSV), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        var inputs = inputs(run, ranked);
        var result = dispatch(run, ranked, 2, inputs);
        assertEquals(2, result.dispatch().providerCalls());
        assertEquals(2, result.dispatch().evaluatorCalls());
        for (var row : result.dispatch().candidates()) {
            assertTrue(row.attempts().get(0).blockers().isEmpty());
            assertNotNull(row.attempts().get(0).context());
            // The ordinary test uses a disabled resource: valid missing semantics, not fake real scores.
            row.attempts().get(0).criterionEvaluations().forEach(e -> assertEquals("NOT_EVALUATED", e.at("/evaluationResult/status").asText()));
        }
        assertFalse(result.inputProvenance().path("vcfRescanned").asBoolean());
        assertFalse(mapper.copy().findAndRegisterModules().valueToTree(run.execution()).has("inputSource"));
        export("engineering", run, result);
    }

    @Test
    void multiallelicTargetIndexUsesExistingCallAndRejectsWrongExplicitIndex() throws Exception {
        Run run = run(vcf(HBB_VCF.replace("\tT\tA\t", "\tT\tG,A\t")), tsv(HBB_TSV), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        assertEquals(2, ranked.get(0).getAnalysisVcfCall().getTargetAlleleIndex());
        ExplicitInput correct = input(run, ranked.get(0), 0, "local", records -> {
            ((ObjectNode) records.path("normalization")).put("originalRecord", "11|5227002|T|G,A|2").put("selectedAltIndex", 2);
        });
        assertEquals(1, dispatch(run, ranked, 1, List.of(correct)).dispatch().providerCalls());
        ExplicitInput wrong = input(run, ranked.get(0), 0, "local", records -> { });
        var failed = dispatch(run, ranked, 1, List.of(wrong));
        blocked(failed, "ANALYSIS_VCF_TARGET_ALT_CONFLICT");
        assertEquals(0, failed.dispatch().providerCalls());
    }

    @Test
    void unmatchedAndAmbiguousAnalysisCallsRetainRowsAndNeverDispatch() throws Exception {
        for (String raw : List.of(vcf(GJB2_VCF), vcf(HBB_VCF, HBB_VCF))) {
            Run run = run(raw, tsv(HBB_TSV), GenomeAssembly.GRCh38);
            var ranked = annotate(run);
            String status = ranked.get(0).getAnalysisVcfCall().getMatchStatus().name();
            var result = dispatch(run, ranked, 1, inputs(run, ranked));
            blocked(result, "ANALYSIS_VCF_" + status);
            assertEquals(1, result.dispatch().candidates().size());
            assertEquals(0, result.dispatch().providerCalls());
            assertEquals(0, result.dispatch().evaluatorCalls());
        }
    }

    @Test
    void taskAnalysisConversionAndExecutionAssociationsAreRequired() throws Exception {
        Run run = run(vcf(HBB_VCF), tsv(HBB_TSV), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        var inputs = inputs(run, ranked);
        var adapter = adapter(false);
        var other = new AnalysisContext("wrong-analysis", GenomeAssembly.GRCh38, AnalysisMode.WES);
        blocked(adapter.dispatch(run.job(), run.conversion(), run.execution(), other, ranked, 1, inputs), "ANALYSIS_TASK_SOURCE_CONFLICT");
        blocked(adapter.dispatch(run.job(), null, run.execution(), analysis, ranked, 1, inputs), "BUILD_CONVERSION_SOURCE_REQUIRED");
        var wrongPath = new BuildConversionResult(temporary.resolve("not-the-input.vcf"), false, "NONE", List.of());
        blocked(adapter.dispatch(run.job(), wrongPath, run.execution(), analysis, ranked, 1, inputs), "ANALYSIS_VCF_SOURCE_CONFLICT");
        var copied = EngineExecutionResult.completed(run.execution().getMetadata(), ranked, temporary,
                run.execution().getRequestSha256(), run.execution().getCommandSha256(), 0);
        blocked(adapter.dispatch(run.job(), run.conversion(), copied, analysis, ranked, 1, inputs), "EXECUTION_INPUT_SOURCE_REQUIRED");
        Run unknown = run(vcf(HBB_VCF), tsv(HBB_TSV), null);
        blocked(dispatch(unknown, annotate(unknown), 1, inputs(unknown, annotate(unknown))), "ORIGINAL_INPUT_ASSEMBLY_REQUIRED");
    }

    @Test
    void convertedGrch38CannotBecomeNativeEvenWhenExplicitReceiptsClaimNative() throws Exception {
        Run run = run(vcf(HBB_VCF), tsv(HBB_TSV), GenomeAssembly.GRCh37);
        var ranked = annotate(run);
        var result = dispatch(run, ranked, 1, inputs(run, ranked));
        blocked(result, "PRE_RANKING_LIFTOVER_UNSUPPORTED");
        assertEquals("GRCh37", result.inputProvenance().path("originalInputAssembly").asText());
        assertTrue(result.inputProvenance().path("conversionApplied").asBoolean());
        assertFalse(result.inputProvenance().path("analysisVcfIsUploadedFile").asBoolean());
        var fakeNative = new BuildConversionResult(run.conversion().getOutputFile(), false, "NONE", List.of());
        blocked(adapter(false).dispatch(run.job(), fakeNative, run.execution(), analysis, ranked, 1, inputs(run, ranked)),
                "PRE_RANKING_LIFTOVER_UNSUPPORTED");
        export("converted-blocked", run, result);
    }

    @Test
    void callTamperingAndMissingReceiptAssociationFailClosed() throws Exception {
        Run run = run(vcf(HBB_VCF), tsv(HBB_TSV), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        var inputs = inputs(run, ranked);
        var missing = ranked.get(0).withAnalysisVcfCall(null);
        blocked(dispatch(run, List.of(missing), 1, inputs), "ANALYSIS_VCF_CALL_REQUIRED");
        var changed = ranked.get(0).withAnalysisVcfCall(AnalysisVcfCall.notFound(GenomeAssembly.GRCh38));
        blocked(dispatch(run, List.of(changed), 1, inputs), "ANALYSIS_VCF_CALL_SOURCE_CONFLICT");
        for (String field : List.of("analysisVcfArtifactSha256", "analysisVcfRecordOrdinal")) {
            var bad = input(run, ranked.get(0), 0, "local", records -> ((ObjectNode) records.path("normalization")).remove(field));
            blocked(dispatch(run, ranked, 1, List.of(bad)), "ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED");
        }
    }

    @ParameterizedTest(name = "receipt ordinal {0} must not dispatch")
    @ValueSource(strings = {"4294967297", "-4294967295", "18446744073709551617", "-18446744073709551615",
            "2147483648", "0", "-1", "1.0", "1.5", "\"1\"", "true", "null", "MISSING", "2", "2147483647"})
    void invalidReceiptOrdinalFailsBeforeDispatchAndCannotPoisonValidSibling(String ordinalText) throws Exception {
        Run run = run(vcf(HBB_VCF, GJB2_VCF), tsv(HBB_TSV, GJB2_TSV), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        assertEquals(1, ranked.get(0).getAnalysisVcfCall().getMatchedRecordOrdinal());
        JsonNode ordinal = ordinalText.equals("MISSING") ? null : mapper.readTree(ordinalText);
        // input() serializes the changed record, rehashes it and returns references to that fresh ledger.
        var invalid = input(run, ranked.get(0), 0, "local", records -> {
            var normalization = (ObjectNode) records.path("normalization");
            if (ordinal == null) normalization.remove("analysisVcfRecordOrdinal");
            else normalization.set("analysisVcfRecordOrdinal", ordinal);
            ((ObjectNode) records.path("patient")).put("sex", "FEMALE");
        });
        var isolated = dispatch(run, ranked, 1, List.of(invalid));
        blocked(isolated, "ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED");
        var failed = isolated.dispatch().candidates().get(0).attempts().get(0);
        assertEquals(1, failed.blockers().size()); // No hash/reference error masks the ordinal gate.
        assertEquals(mapper.valueToTree(invalid.binding()), mapper.valueToTree(failed.preflight().original().binding()));
        assertEquals(invalid.normalization().source(), failed.preflight().original().provenance().normalization());
        assertNull(failed.preflight().original().seed());

        var valid = input(run, ranked.get(1), 1, "local", records -> { });
        var mixed = dispatch(run, ranked, 2, List.of(invalid, valid));
        assertEquals(ranked.stream().map((VariantInterpretation value) -> value.getCandidateId()).toList(),
                mixed.dispatch().candidates().stream().map((ExplicitAlphaMissenseDispatcher.CandidateDispatch value) -> value.candidateId()).toList());
        assertNull(mixed.dispatch().candidates().get(0).attempts().get(0).context());
        assertEquals(0, mixed.dispatch().candidates().get(0).attempts().get(0).providerCalls());
        assertEquals(0, mixed.dispatch().candidates().get(0).attempts().get(0).evaluatorCalls());
        assertTrue(mixed.dispatch().candidates().get(1).attempts().get(0).blockers().isEmpty());
        assertNotNull(mixed.dispatch().candidates().get(1).attempts().get(0).context());
        assertEquals(1, mixed.dispatch().providerCalls());
        assertEquals(1, mixed.dispatch().evaluatorCalls());
        export("ordinal-" + sha(ordinalText.getBytes(StandardCharsets.UTF_8)).substring(0, 12), run, mixed);
    }

    @Test
    void differentExecutionCandidateAndDuplicateRowsCannotReuseAnExistingInput() throws Exception {
        Run run = run(vcf(HBB_VCF, GJB2_VCF), tsv(HBB_TSV, GJB2_TSV), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        var supplied = inputs(run, ranked);
        Run other = run(vcf(GJB2_VCF), tsv(GJB2_TSV.replaceFirst("2", "1")), GenomeAssembly.GRCh38);
        var forged = annotate(other).get(0); // Same local ordinal, a different execution's allele.
        blocked(dispatch(run, List.of(forged), 1, supplied), "ENGINE_CANDIDATE_SOURCE_CONFLICT");
        blocked(dispatch(run, List.of(ranked.get(0), ranked.get(0)), 2, supplied), "CANDIDATE_ID_AMBIGUOUS");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void metadataOnlyCspecStopsParsedCandidateBeforeProviderAndEvaluator(boolean producedSource) throws Exception {
        Run run = run(vcf(producedSource ? "chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t." : HBB_VCF),
                tsv((producedSource ? HBB_TSV.replace("5227002", "2") : HBB_TSV).replace("HBB\t3043\tAR", "CDH1\t999\tAD")), GenomeAssembly.GRCh38);
        var ranked = run.execution().getInterpretations();
        var annotation = new TranscriptConsequence("CDH1", "999", "NM_004360.5", "NM_004360.5:c.20A>T",
                null, "missense_variant", "999", "NM_004360.5", "SYNTHETIC", "MODERATE", null, null,
                false, false, false, null, null, null, null, null, null,
                "SYNTHETIC_CSPEC_ANNOTATION", "COMPLETED", "116.0", java.time.Instant.parse("2026-09-06T00:00:00Z"));
        ranked.get(0).applyTranscriptAnnotations(List.of(annotation));
        var explicit = input(run, ranked.get(0), 0, "synthetic-cspec", records -> {
            ((ObjectNode) records.path("transcript")).put("accession", "NM_004360").put("version", 5)
                    .put("annotationSource", "SYNTHETIC_CSPEC_ANNOTATION")
                    .put("selectionPolicy", "EXPLICIT_SYNTHETIC_CSPEC_TEST")
                    .put("resourceDataClass", "SYNTHETIC");
            ((ObjectNode) records.path("transcript")).put("annotationRecordLocator", "synthetic/cspec-test");
            ((ObjectNode) records.path("transcript")).remove(List.of("annotationArtifactSha256", "preparationArtifactSha256", "explicitSelectionReceipt"));
        }, !producedSource);
        var production = producedSource ? tinyReference(r -> { }).producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 1, List.of(explicit)) : null;
        var result = producedSource ? dispatchProduced(run, ranked, 1, production, false) : dispatch(run, ranked, 1, List.of(explicit));
        assertEquals(0, result.dispatch().providerCalls());
        assertEquals(0, result.dispatch().evaluatorCalls());
        var attempt = result.dispatch().candidates().get(0).attempts().get(0);
        assertEquals("SINGLE_NON_SELECTABLE_MATCH", attempt.preflight().outcome());
        assertNull(attempt.selection());
        assertTrue(attempt.blockers().stream().anyMatch(b -> b.reasonCode().equals("CSPEC_MATCH_NOT_EXECUTABLE")));
        assertEquals("FORCE_NOT_EVALUATED", attempt.preflight().constraints().path("criterionEvaluationConstraint").asText());
        if (producedSource) exportProduced("cspec-blocked", run, production, result, "SYNTHETIC_TINY_FASTA_REAL_INDEXED_QUERY");
        else export("cspec-blocked", run, result);
    }

    @Test
    void sameAlleleCandidatesLocalHypothesesFinalOrderAndFailuresArePreserved() throws Exception {
        Run run = run(vcf(HBB_VCF, GJB2_VCF), tsv(HBB_TSV, HBB_TSV.replaceFirst("1", "2"), GJB2_TSV.replaceFirst("2", "3")), GenomeAssembly.GRCh38);
        var parsed = annotate(run);
        var ranked = List.of(parsed.get(1), parsed.get(0), parsed.get(2));
        var valid = input(run, ranked.get(0), 0, "local", records -> { });
        var invalid = input(run, ranked.get(1), 0, "local", records -> {
            ((ObjectNode) records.path("patient")).put("sex", "FEMALE");
            ((ObjectNode) records.path("normalization")).remove("analysisVcfArtifactSha256");
        });
        var another = input(run, ranked.get(0), 0, "another-local", records -> { });
        var outside = input(run, ranked.get(2), 1, "local", records -> { });
        var result = dispatch(run, ranked, 2, List.of(valid, invalid, another, outside));
        assertEquals(ranked.subList(0, 2).stream().map((VariantInterpretation value) -> value.getCandidateId()).toList(),
                result.dispatch().candidates().stream().map((ExplicitAlphaMissenseDispatcher.CandidateDispatch value) -> value.candidateId()).toList());
        assertEquals(List.of(2, 1), result.dispatch().candidates().stream().map(row -> row.attempts().size()).toList());
        assertEquals(2, result.dispatch().providerCalls());
        assertEquals(1, result.dispatch().preflight().unmatchedInputs().size());
        result.dispatch().candidates().get(0).attempts().forEach(a -> assertTrue(a.blockers().isEmpty()));
        assertNull(result.dispatch().candidates().get(1).attempts().get(0).context());
        export("mixed", run, result);
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.e4a3a.local-smoke", matches = "true")
    void twoPublicGenesFromParsedHydratedCandidatesCompleteRealAlphaMissense() throws Exception {
        Path resources = Path.of(System.getProperty("varvis.e4a3a.binding-resource-dir"));
        resolver = PatientDiseaseContextBindingResolver.load(mapper, resources.resolve("manifest.json"),
                resources.resolve("mane-crosswalk.json"), resources.resolve("clingen-catalog.json"));
        Run run = run(vcf(HBB_VCF, GJB2_VCF), tsv(HBB_TSV, GJB2_TSV), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        var explicit = inputs(run, ranked);
        var result = adapter(true).dispatch(run.job(), run.conversion(), run.execution(), analysis, ranked, 2, explicit);
        export("real-exact", run, result); // Preserve the actual outcome before strict assertions.
        assertEquals(2, result.dispatch().providerCalls());
        assertEquals(2, result.dispatch().evaluatorCalls());
        for (var row : result.dispatch().candidates()) {
            var attempt = row.attempts().get(0);
            assertTrue(attempt.blockers().isEmpty(), attempt.blockers().toString());
            assertEquals("ZERO_MATCH", attempt.selection().path("outcome").asText());
            var metrics = attempt.atomicEvidence().get(0).path("measurements");
            for (String id : List.of("exactAlleleMatch", "transcriptProteinMatch")) {
                var matches = new ArrayList<JsonNode>();
                metrics.forEach(m -> { if (m.path("metricId").asText().equals("alphamissense." + id)) matches.add(m); });
                assertEquals(1, matches.size());
                assertEquals(mapper.getNodeFactory().booleanNode(true), matches.get(0).path("rawValue"));
                assertEquals(mapper.getNodeFactory().booleanNode(true), matches.get(0).path("normalizedValue"));
            }
            assertTrue(metrics.findValuesAsText("metricId").contains("alphamissense.amPathogenicity"));
            assertEquals(2, attempt.criterionEvaluations().size());
            attempt.criterionEvaluations().forEach(e -> assertEquals("COMPLETED", e.at("/deterministicTrace/executionStatus").asText()));
        }
    }

    @Test
    void producerCreatesFreshReceiptsFromOneScanAndRealTinyReferenceQueries() throws Exception {
        Run run = tinyRun("T", "G,A");
        var ranked = annotate(run);
        var explicit = supplements(run, ranked);
        assertTrue(explicit.stream().allMatch(i -> i.normalization() == null));
        var fixture = tinyReference(receipt -> { });
        var production = fixture.producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 2, explicit);
        var result = dispatchProduced(run, ranked, 2, production, false);
        assertEquals(2, result.dispatch().providerCalls());
        assertEquals(2, result.dispatch().evaluatorCalls());
        assertEquals(2, production.inputs().get(0).normalization().selectedAltIndex());
        assertEquals("chr11|2|T|G,A|2", production.inputs().get(0).normalization().originalRecord());
        assertEquals(NativeSnvSourceProducer.METHOD, production.inputs().get(0).normalization().method());
        assertEquals(0, production.provenance().path("additionalFullVcfScans").intValue());
        assertEquals(2, production.provenance().path("referenceQueryAttempts").intValue());
        assertEquals(1, run.execution().getInputSource().fullVcfScans());
        assertEquals(run.job().getFileSha256(), run.execution().getInputSource().observedVcfSha256());
        assertFalse(mapper.copy().findAndRegisterModules().valueToTree(run.execution()).has("inputSource"));
        assertFalse(mapper.valueToTree(ranked.get(0).getAnalysisVcfCall()).has("alternates"));
        for (var candidate : ranked) { assertNull(candidate.getObservation().getOriginalRecord()); assertNull(candidate.getAnalysisVcfCall().getDepth()); }
        exportProduced("engineering", run, production, result, "SYNTHETIC_TINY_FASTA_REAL_INDEXED_QUERY");
    }

    @Test
    void producerIsolatesRefMismatchAndRetainsSameAlleleCandidatesAndHypotheses() throws Exception {
        Run run = run(vcf("chr11\t2\t.\tA\tG\t.\tPASS\t.\tGT\t.", "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t."),
                tsv("1\t11\t2\tA\tG\tHBB\t3043\tAR\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7",
                        "3\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.6"), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        var explicit = supplements(run, ranked);
        explicit.add(input(run, ranked.get(1), 1, "second-local", r -> { }, false));
        var fixture = tinyReference(r -> { });
        var production = fixture.producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 3, explicit);
        var result = dispatchProduced(run, ranked, 3, production, false);
        assertEquals(ranked.stream().map((VariantInterpretation value) -> value.getCandidateId()).toList(),
                result.dispatch().candidates().stream().map((ExplicitAlphaMissenseDispatcher.CandidateDispatch value) -> value.candidateId()).toList());
        assertEquals(3, result.dispatch().candidates().size());
        assertNull(result.dispatch().candidates().get(0).attempts().get(0).context());
        assertTrue(result.dispatch().candidates().get(0).attempts().get(0).blockers().stream().anyMatch(b -> b.reasonCode().equals("REF_VALIDATION_NOT_MATCH")));
        assertEquals("A", production.provenance().at("/inputs/0/referenceObservation/expectedReference").asText());
        assertEquals("T", production.provenance().at("/inputs/0/referenceObservation/observedReference").asText());
        assertEquals(2, result.dispatch().candidates().get(1).attempts().size());
        assertEquals(3, result.dispatch().providerCalls());
        assertEquals(3, result.dispatch().evaluatorCalls());
        assertTrue(production.sources().stream().noneMatch(s -> s.receipt(mapper).path("source").asText().equals("NATIVE_SNV_SOURCE_1")));
        exportProduced("mixed", run, production, result, "SYNTHETIC_TINY_FASTA_REAL_INDEXED_QUERY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NOT_FOUND", "AMBIGUOUS", "NON_MINIMAL_INDEL", "SYMBOLIC", "GRCh37", "MISSING_CALL", "OTHER_EXECUTION"})
    void producerBlocksUnsupportedOrUnassociatedInputsBeforeDispatch(String failure) throws Exception {
        String raw = vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t.");
        String rows = tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8");
        if (failure.equals("NOT_FOUND")) raw = raw.replace("\t2\t", "\t4\t");
        if (failure.equals("AMBIGUOUS")) raw += "chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t.\n";
        if (failure.equals("NON_MINIMAL_INDEL") || failure.equals("SYMBOLIC")) {
            String alt = failure.equals("NON_MINIMAL_INDEL") ? "TT" : "<DEL>";
            raw = raw.replace("\tT\tA\t", "\tT\t" + alt + "\t");
            rows = rows.replace("\tT\tA\t", "\tT\t" + alt + "\t");
        }
        Run run = run(raw, rows, failure.equals("GRCh37") ? GenomeAssembly.GRCh37 : GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        var explicit = supplements(run, ranked);
        if (failure.equals("MISSING_CALL")) ranked = List.of(ranked.get(0).withAnalysisVcfCall(null));
        if (failure.equals("OTHER_EXECUTION")) ranked = annotate(tinyRun("T", "G"));
        var fixture = tinyReference(r -> { });
        var production = fixture.producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 1, explicit);
        var result = dispatchProduced(run, ranked, 1, production, false);
        assertEquals(0, result.dispatch().providerCalls());
        assertEquals(0, result.dispatch().evaluatorCalls());
        assertNull(result.dispatch().candidates().get(0).attempts().get(0).context());
        assertTrue(production.sources().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_RECEIPT", "HASH", "MTIME", "INDEX_HASH", "UTC", "NO_INDEX", "NO_CONTIG", "NO_FASTA", "RESOURCE_PATH"})
    void producerRequiresApplicableChecksumAndAvailableIndexedReference(String failure) throws Exception {
        Run run = tinyRun("T", "A");
        var ranked = annotate(run);
        var fixture = tinyReference(r -> {
            switch (failure) {
                case "HASH" -> r.put("observedSha256", "0".repeat(64));
                case "MTIME" -> r.put("mtimeNs", 1);
                case "INDEX_HASH" -> r.put("indexSha256", "0".repeat(64));
                case "UTC" -> r.put("completedAt", "2026-09-01T00:00:00+08:00");
                case "RESOURCE_PATH" -> r.put("logicalName", "other/tiny.fa");
                default -> { }
            }
        });
        if (failure.equals("NO_INDEX")) Files.move(Path.of(fixture.properties().getNormalization().getGrch38Reference() + ".fai"), temporary.resolve("kept-index.fai"));
        if (failure.equals("NO_FASTA")) Files.move(fixture.properties().getNormalization().getGrch38Reference(), temporary.resolve("kept-fasta.fa"));
        if (failure.equals("NO_CONTIG")) {
            run = run(vcf("chr12\t2\t.\tT\tA\t.\tPASS\t.\tGT\t."), tsv("1\t12\t2\tT\tA\tHBB\t3043\tAR\t0.8"), GenomeAssembly.GRCh38);
            ranked = annotate(run);
        }
        var producer = failure.equals("NO_RECEIPT") ? new NativeSnvSourceProducer(mapper, fixture.properties(), temporary, sources, null) : fixture.producer();
        var production = producer.produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 1, supplements(run, ranked));
        var result = dispatchProduced(run, ranked, 1, production, false);
        assertEquals(0, result.dispatch().providerCalls());
        assertEquals(0, result.dispatch().evaluatorCalls());
        assertNull(production.inputs().get(0).normalization());
        assertFalse(production.provenance().at("/inputs/0/blocker/reasonCode").asText().isBlank());
    }

    @Test
    void producedSourcesCannotBeReplayedAgainstAnotherExecutionAndTopNDoesNotBackfill() throws Exception {
        Run run = tinyRun("A", "G"); // First REF mismatches; the valid second candidate remains outside Top-1.
        var ranked = annotate(run);
        var fixture = tinyReference(r -> { });
        var production = fixture.producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 1, supplements(run, ranked));
        var result = dispatchProduced(run, ranked, 1, production, false);
        assertEquals(1, result.dispatch().candidates().size());
        assertEquals(0, result.dispatch().providerCalls());
        assertEquals(1, result.dispatch().preflight().unmatchedInputs().size());
        Run other = tinyRun("T", "A");
        var second = fixture.producer().produce(other.job(), other.conversion(), other.execution(), analysis, annotate(other), 2, supplements(other, annotate(other)));
        blocked(dispatchProduced(run, ranked, 2, second, false), "PRODUCED_SOURCE_EXECUTION_CONFLICT");
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.e4a3b.local-smoke", matches = "true")
    void producerRealHbbGjb2ExactHitUsesFreshNormalizationRefAndLineage() throws Exception {
        Path bindingRoot = Path.of(System.getProperty("varvis.e4a3a.binding-resource-dir"));
        resolver = PatientDiseaseContextBindingResolver.load(mapper, bindingRoot.resolve("manifest.json"),
                bindingRoot.resolve("mane-crosswalk.json"), bindingRoot.resolve("clingen-catalog.json"));
        // Explicit native chr-prefixed representation of the same fixed public alleles; no automatic alias or new VEP run.
        Run run = run(vcf("chr" + HBB_VCF, "chr" + GJB2_VCF), tsv(HBB_TSV, GJB2_TSV), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        var explicit = supplements(run, ranked);
        Path resourceRoot = Path.of(System.getProperty("varvis.e4a3b.resource-root"));
        var properties = new VariantAnalysisProperties();
        var checksum = sources.get(0).resolve(reference).path("actualChecksumReceipt");
        properties.getNormalization().setGrch38Reference(resourceRoot.resolve(checksum.path("logicalName").asText()).toString());
        properties.getNormalization().setGrch38ReferenceSha256(checksum.path("expectedSha256").asText());
        properties.getNormalization().setGrch38ProfileId("grch38-primary-chr");
        var producer = new NativeSnvSourceProducer(mapper, properties, resourceRoot, sources, reference);
        var production = producer.produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 2, explicit);
        var result = dispatchProduced(run, ranked, 2, production, true);
        exportProduced("real-exact", run, production, result, "PUBLIC_GRCH38_FASTA_ACCEPTED_CHECKSUM_FRESH_INDEXED_QUERY");
        assertEquals(2, result.dispatch().providerCalls(), production.provenance().toPrettyString());
        assertEquals(2, result.dispatch().evaluatorCalls());
        for (var row : result.dispatch().candidates()) {
            var attempt = row.attempts().get(0);
            assertTrue(attempt.blockers().isEmpty(), attempt.blockers().toString());
            assertEquals("ZERO_MATCH", attempt.selection().path("outcome").asText());
            for (String id : List.of("exactAlleleMatch", "transcriptProteinMatch")) {
                var metrics = new ArrayList<JsonNode>();
                attempt.atomicEvidence().get(0).path("measurements").forEach(m -> { if (m.path("metricId").asText().equals("alphamissense." + id)) metrics.add(m); });
                assertEquals(1, metrics.size());
                assertTrue(metrics.get(0).path("rawValue").booleanValue());
                assertTrue(metrics.get(0).path("normalizedValue").booleanValue());
            }
            assertTrue(attempt.atomicEvidence().get(0).path("measurements").findValuesAsText("metricId").contains("alphamissense.amPathogenicity"));
            assertEquals(2, attempt.criterionEvaluations().size());
            attempt.criterionEvaluations().forEach(e -> assertEquals("COMPLETED", e.at("/deterministicTrace/executionStatus").asText()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void producerRejectsUnverifiedUploadHashOrFileChangedDuringExecution(boolean changed) throws Exception {
        Run run = ParsedCandidateFixture.run(temporary.resolve("execution-identity"), ANALYSIS_ID, GenomeAssembly.GRCh38,
                vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t."), tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8"), changed ? null : "0".repeat(64), changed);
        var ranked = annotate(run);
        assertEquals(EngineExecutionStatus.RANKED, run.execution().getMetadata().getStatus(), run.execution().getFailureCode());
        assertEquals(!changed, run.execution().getInputSource().unchangedDuringExecution());
        var production = tinyReference(r -> { }).producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 1, supplements(run, ranked));
        blocked(dispatchProduced(run, ranked, 1, production, false), "ANALYSIS_VCF_CONTENT_IDENTITY_NOT_VERIFIED");
        assertEquals(0, production.provenance().path("referenceQueryAttempts").intValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"4294967297", "-4294967295", "18446744073709551617"})
    void generatedReceiptStillPassesThroughR1StrictOrdinalGate(String number) throws Exception {
        Run run = tinyRun("T", "A");
        var ranked = annotate(run);
        var production = tinyReference(r -> { }).producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 2, supplements(run, ranked));
        sources.addAll(production.sources());
        var original = production.inputs().get(0);
        var normal = original.normalization();
        var owning = production.sources().stream().filter(s -> {
            try { s.resolve(normal.source()); return true; } catch (IllegalArgumentException ex) { return false; }
        }).findFirst().orElseThrow();
        ObjectNode envelope = (ObjectNode) mapper.readTree(owning.receipt(mapper).path("rawJson").asText());
        ((ObjectNode) envelope.at("/records/normalization")).set("analysisVcfRecordOrdinal", mapper.readTree(number));
        var modified = ExplicitContextSourceLedger.create(mapper, envelope); // Fresh bytes/hash/reference, no hash error shortcut.
        sources.add(modified);
        var invalid = new ExplicitInput(original.candidateId(), original.hypothesisId(), original.binding(), original.dataClass(), original.transcript(), original.patient(),
                new NormalizationInput(normal.originalAllele(), normal.normalizedAllele(), normal.selectedAltIndex(), normal.originalRecord(), normal.method(), normal.methodVersion(),
                        modified.reference("normalization"), normal.reference(), normal.lineage()));
        var result = dispatch(run, ranked, 2, List.of(invalid, production.inputs().get(1)));
        var first = result.dispatch().candidates().get(0).attempts().get(0);
        assertEquals(1, first.blockers().size());
        assertEquals("ANALYSIS_VCF_RECEIPT_ASSOCIATION_REQUIRED", first.blockers().get(0).reasonCode());
        assertNull(first.context());
        assertEquals(1, result.dispatch().providerCalls());
        assertEquals(1, result.dispatch().evaluatorCalls());
    }

    @Test
    void generatedReceiptsKeepExecutionBindingThroughOldExplicitEntry() throws Exception {
        Run run = tinyRun("T", "A");
        var ranked = annotate(run);
        var production = tinyReference(r -> { }).producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 2, supplements(run, ranked));
        sources.addAll(production.sources());
        assertEquals(2, dispatch(run, ranked, 2, production.inputs()).dispatch().providerCalls());
        Run other = tinyRun("T", "A");
        assertEquals(run.job().getFileSha256(), other.job().getFileSha256());
        assertNotEquals(run.execution().getCommandSha256(), other.execution().getCommandSha256());
        blocked(dispatch(other, annotate(other), 2, production.inputs()), "PRODUCED_SOURCE_EXECUTION_CONFLICT");
    }

    @Test
    void producerOnlyUsesExplicitConfiguredContigAliases() throws Exception {
        Run run = run(vcf("11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t."), tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8"), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        var fixture = tinyReference(r -> { });
        var explicit = supplements(run, ranked);
        var missing = fixture.producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 1, explicit);
        blocked(dispatchProduced(run, ranked, 1, missing, false), "REFERENCE_CONTIG_UNAVAILABLE");
        Path alias = temporary.resolve("explicit-alias.tsv");
        Files.writeString(alias, "11\tchr11\n");
        fixture.properties().getNormalization().setGrch38ContigAliasFile(alias.toString());
        var production = fixture.producer().produce(run.job(), run.conversion(), run.execution(), analysis, ranked, 1, explicit);
        var result = dispatchProduced(run, ranked, 1, production, false);
        assertEquals(1, result.dispatch().providerCalls());
        assertEquals("chr11", production.provenance().at("/inputs/0/referenceObservation/referenceContig").asText());
        assertEquals(sha(Files.readAllBytes(alias)), production.provenance().at("/inputs/0/referenceObservation/contigAssociation/artifactSha256").asText());
        exportProduced("alias", run, production, result, "SYNTHETIC_TINY_FASTA_REAL_INDEXED_QUERY");
    }

    private Run tinyRun(String ref, String alt) throws Exception {
        return run(vcf("chr11\t2\t.\t" + ref + "\t" + alt + "\t.\tPASS\t.\tGT\t.", "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t."),
                tsv("1\t11\t2\t" + ref + "\t" + alt.substring(alt.lastIndexOf(',') + 1) + "\tHBB\t3043\tAR\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7"), GenomeAssembly.GRCh38);
    }

    private List<ExplicitInput> supplements(Run run, List<VariantInterpretation> ranked) throws Exception {
        List<ExplicitInput> result = new ArrayList<>();
        for (var candidate : ranked) result.add(input(run, candidate, "HBB".equals(candidate.getExomiserCandidateFeatures().getGeneSymbol()) ? 0 : 1, "local", r -> { }, false));
        return result;
    }

    @Test void sourceProducerChecksEntireIndelRefAndRetainsSoftMaskObservation() throws Exception {
        var run = tinyRun("TC", "T");
        var fixture = tinyReference(r -> { }, "AtcG");
        var candidate = run.execution().getInterpretations().get(0);
        var production = fixture.producer().produceAllele(run.job(), run.conversion(), run.execution(), analysis, candidate, "SYNTHETIC");
        assertEquals("PRODUCED", production.provenance().at("/inputs/0/status").asText());
        assertEquals(NativeSnvSourceProducer.SMALL_VARIANT_METHOD, production.inputs().get(0).normalization().method());
        var observation = production.provenance().at("/inputs/0/referenceObservation");
        assertEquals("TC", observation.path("observedReference").asText());
        assertEquals("tc", observation.path("rawObservedReference").asText());
        assertEquals(NativeSnvReference.SPAN_QUERY_METHOD, observation.path("method").asText());
    }

    private TinyReference tinyReference(Consumer<ObjectNode> change) throws Exception {
        return tinyReference(change, "ATCG");
    }
    private TinyReference tinyReference(Consumer<ObjectNode> change, String bases) throws Exception {
        Path fasta = temporary.resolve("tiny.fa");
        Files.writeString(fasta, ">chr11\n" + bases + "\n>chr13\nAACT\n");
        Path index = Path.of(fasta + ".fai");
        Files.writeString(index, "chr11\t4\t7\t4\t5\nchr13\t4\t19\t4\t5\n");
        String sha = sha(Files.readAllBytes(fasta));
        var receipt = mapper.createObjectNode().put("resourceId", "SYNTHETIC_TINY_FASTA").put("resourceVersion", "1")
                .put("assembly", "GRCh38").put("logicalName", "tiny.fa").put("expectedSha256", sha).put("observedSha256", sha)
                .put("hashStatus", "VERIFIED").put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true)
                .put("byteLength", Files.size(fasta)).put("mtimeNs", Files.getLastModifiedTime(fasta).to(java.util.concurrent.TimeUnit.NANOSECONDS))
                .put("startedAt", "2026-09-01T00:00:00Z").put("completedAt", "2026-09-01T00:00:01Z").put("indexSha256", sha(Files.readAllBytes(index)))
                .put("resourceDataClass", "SYNTHETIC");
        change.accept(receipt);
        var envelope = mapper.createObjectNode().put("source", "TINY_REFERENCE_CHECKSUM").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").putObject("checksum").set("actualChecksumReceipt", receipt);
        var ledger = ExplicitContextSourceLedger.create(mapper, envelope);
        sources.add(ledger);
        var properties = new VariantAnalysisProperties();
        properties.getNormalization().setGrch38Reference(fasta.toString());
        properties.getNormalization().setGrch38ReferenceSha256(sha);
        properties.getNormalization().setGrch38ProfileId("SYNTHETIC_TINY_TEST_PROFILE");
        var validator = new org.nbscn.varvis.variantanalysis.provider.NormalizationResourceValidator(properties) {
            @Override public org.nbscn.varvis.variantanalysis.provider.NormalizationReferenceReadiness validate(GenomeAssembly assembly) {
                // Tiny sequence does not claim chromosome-1 assembly length; only this engineering readiness seam is synthetic.
                return new org.nbscn.varvis.variantanalysis.provider.NormalizationReferenceReadiness(assembly, "READY", "SYNTHETIC_TINY_TEST_PROFILE",
                        "tiny.fa", sha, true, true, 2, "CHR_PREFIXED", List.of(), List.of("SYNTHETIC_TEST_READINESS"));
            }
        };
        return new TinyReference(new NativeSnvSourceProducer(mapper, properties, temporary, sources, ledger.reference("checksum"), validator), properties);
    }
    private record TinyReference(NativeSnvSourceProducer producer, VariantAnalysisProperties properties) { }

    private CandidateAcmgInputAdapter.Result dispatchProduced(Run run, List<VariantInterpretation> ranked, int n,
                                                               NativeSnvSourceProducer.Production production, boolean real) {
        sources.addAll(production.sources());
        return adapter(real).dispatch(run.job(), run.conversion(), run.execution(), analysis, ranked, n, production);
    }

    private void exportProduced(String label, Run run, NativeSnvSourceProducer.Production production, CandidateAcmgInputAdapter.Result result,
                                 String referenceDataClass) throws Exception {
        String directory = System.getProperty("varvis.e4a3b.output-dir");
        if (directory == null) return;
        ObjectNode value = mapper.valueToTree(result.dispatch());
        value.set("candidateInputProvenance", result.inputProvenance());
        value.set("sourceProduction", production.provenance());
        var receipts = value.putArray("sourceReceipts");
        sources.forEach(s -> receipts.add(s.receipt(mapper)));
        value.set("explicitSelectionProvenance", accepted.path("smokeInputProvenance").deepCopy());
        ((ObjectNode) value.path("explicitSelectionProvenance")).remove("sourceReceipts");
        var fixture = value.putObject("candidateInputFixture").put("runner", "SYNTHETIC_TSV_RUNNER_NOT_REAL_EXOMISER")
                .put("patientDataClass", "SYNTHETIC").put("rankingDataClass", "SYNTHETIC").put("referenceDataClass", referenceDataClass)
                .put("annotationUse", label.equals("real-exact") ? "RETAINED_REAL_VEP_FIXED_ALLELES_NO_NEW_VEP_RUN" : "SYNTHETIC_ENGINEERING_REUSE_OF_TRANSCRIPT_FIELDS")
                .put("rawAnalysisVcf", Files.readString(run.conversion().getOutputFile()))
                .put("rawVariantTsv", Files.readString(run.execution().getWorkDirectory().resolve("output/result.variants.tsv")));
        fixture.put("variantTsvSha256", sha(fixture.path("rawVariantTsv").asText().getBytes(StandardCharsets.UTF_8)))
                .put("analysisVcfSha256", run.execution().getInputSource().observedVcfSha256())
                .put("originalRecordNullCount", run.execution().getInterpretations().stream().filter(c -> c.getObservation().getOriginalRecord() == null).count());
        Path output = Path.of(directory).resolve("e4-a3b-" + label + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value).replace("\r\n", "\n") + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private Run run(String vcf, String tsv, GenomeAssembly assembly) throws Exception {
        Run result = ParsedCandidateFixture.run(temporary.resolve("run-" + ++runOrdinal), ANALYSIS_ID, assembly, vcf, tsv);
        assertEquals(EngineExecutionStatus.RANKED, result.execution().getMetadata().getStatus(), result.execution().getFailureCode());
        return result;
    }

    private List<VariantInterpretation> annotate(Run run) {
        var candidates = run.execution().getInterpretations();
        for (var candidate : candidates) {
            assertNull(candidate.getObservation().getOriginalRecord());
            assertNotNull(candidate.getAnalysisVcfCall());
            assertNull(candidate.getAnalysisVcfCall().getDepth());
            String caseId = "HBB".equals(candidate.getExomiserCandidateFeatures().getGeneSymbol())
                    ? "public-hbb-compatible-e7v" : "public-gjb2-20189473-ct";
            candidate.applyTranscriptAnnotations(annotations.get(caseId));
        }
        return candidates;
    }

    private List<ExplicitInput> inputs(Run run, List<VariantInterpretation> candidates) throws Exception {
        List<ExplicitInput> result = new ArrayList<>();
        for (var candidate : candidates) result.add(input(run, candidate,
                "HBB".equals(candidate.getExomiserCandidateFeatures().getGeneSymbol()) ? 0 : 1, "local", records -> { }));
        return result;
    }

    private ExplicitInput input(Run run, VariantInterpretation candidate, int sourceIndex, String hypothesisId,
                                 Consumer<ObjectNode> change) throws Exception {
        return input(run, candidate, sourceIndex, hypothesisId, change, true);
    }

    private ExplicitInput input(Run run, VariantInterpretation candidate, int sourceIndex, String hypothesisId,
                                 Consumer<ObjectNode> change, boolean includeHistoricalNormalization) throws Exception {
        JsonNode template = accepted.at("/candidates/" + sourceIndex + "/attempts/0");
        var oldHypothesis = mapper.treeToValue(template.at("/preflight/original/binding/provenance/patientHypothesis"), PatientDiseaseHypothesis.class);
        boolean cspec = "CDH1".equals(candidate.getExomiserCandidateFeatures().getGeneSymbol());
        var hypothesis = new PatientDiseaseHypothesis(candidate.getCandidateId(), hypothesisId, cspec ? "HGNC:1748" : oldHypothesis.hgncId(),
                cspec ? "MONDO:0007648" : oldHypothesis.mondoId(), cspec ? "AUTOSOMAL_DOMINANT" : oldHypothesis.moi(),
                cspec ? new HypothesisProvenance("SYNTHETIC_CSPEC_TEST", "1.0.0", "synthetic/cspec-test") : oldHypothesis.provenance());
        var binding = resolver.resolve(candidate, List.of(hypothesis)).bindings().get(0);
        assertEquals("READY", binding.status(), binding.reasonCode());
        JsonNode oldRecords = mapper.readTree(accepted.at("/smokeInputProvenance/sourceReceipts/" + (sourceIndex + 1) + "/rawJson").asText()).path("records");
        ObjectNode records = mapper.createObjectNode();
        // A3B reuses only explicit patient/transcript choices, never historical normalization, REF observations or lineage.
        for (String field : includeHistoricalNormalization ? List.of("patient", "transcript", "normalization", "lineage") : List.of("patient", "transcript")) {
            records.set(field, oldRecords.path(field).deepCopy());
        }
        records.elements().forEachRemaining(record -> ((ObjectNode) record).put("analysisId", ANALYSIS_ID)
                .put("candidateId", candidate.getCandidateId()).put("hypothesisId", hypothesisId));
        ((ObjectNode) records.path("patient").path("hypothesis")).put("hgncId", binding.hgncId())
                .put("mondoId", binding.mondoId()).put("moi", binding.normalizedMoi()).set("provenance", mapper.valueToTree(hypothesis.provenance()));
        ((ObjectNode) records.path("transcript")).put("hgncId", binding.hgncId()).put("geneSymbol", binding.symbol());
        ((ObjectNode) records.path("patient")).put("sampleId", "synthetic-proband");
        ObjectNode normalization = includeHistoricalNormalization ? (ObjectNode) records.path("normalization") : null;
        if (normalization != null) {
            normalization.put("analysisVcfArtifactSha256", run.job().getFileSha256());
            if (candidate.getAnalysisVcfCall().getMatchedRecordOrdinal() != null) {
                normalization.put("analysisVcfRecordOrdinal", candidate.getAnalysisVcfCall().getMatchedRecordOrdinal());
            }
        }
        change.accept(records);
        String name = "E4_A3A_EXPLICIT_RECEIPTS_" + exportedSources.size();
        ObjectNode envelope = mapper.createObjectNode().put("source", name).put("version", "e4-a3a-test-association-v1")
                .put("dataClass", "SYNTHETIC").set("records", records);
        byte[] bytes = mapper.writeValueAsBytes(envelope);
        Path path = temporary.resolve(name + ".json");
        Files.write(path, bytes);
        var ledger = ExplicitContextSourceLedger.load(mapper, path, sha(bytes));
        sources.add(ledger);
        exportedSources.addObject().put("source", name).put("sha256", sha(bytes)).put("rawJson", new String(bytes, StandardCharsets.UTF_8));
        JsonNode transcript = records.path("transcript"), patient = records.path("patient");
        Allele original = normalization == null ? null : mapper.treeToValue(normalization.path("originalAllele"), Allele.class);
        return new ExplicitInput(candidate.getCandidateId(), hypothesisId, binding, "SYNTHETIC",
                new TranscriptChoice(transcript.path("accession").asText(), transcript.path("version").asInt(),
                        transcript.path("selectionPolicy").asText(), transcript.path("annotationSource").asText(),
                        transcript.path("annotationSourceVersion").asText(), ledger.reference("transcript")),
                new PatientContext(patient.path("patientContextId").asText(), patient.path("role").asText(),
                        patient.path("affectedStatus").asText(), patient.path("sex").asText(), null, ledger.reference("patient")),
                normalization == null ? null : new NormalizationInput(original, mapper.treeToValue(normalization.path("normalizedAllele"), Allele.class),
                        normalization.path("selectedAltIndex").asInt(), normalization.path("originalRecord").asText(),
                        normalization.path("method").asText(), normalization.path("methodVersion").asText(),
                        ledger.reference("normalization"), reference, new AssemblyLineage("GRCh38", "NATIVE", original, null, ledger.reference("lineage"))));
    }

    private CandidateAcmgInputAdapter adapter(boolean real) {
        var properties = new VariantAnalysisProperties();
        if (real) {
            properties.getAlphaMissense().setEnabled(true);
            properties.getAlphaMissense().setVersion("2023-08-03");
            properties.getAlphaMissense().setGrch38File(System.getProperty("varvis.e4a3a.alphamissense-grch38"));
            properties.getAlphaMissense().setTimeout(Duration.ofSeconds(30));
        }
        return new CandidateAcmgInputAdapter(mapper, resolver, sources,
                new ExplicitAlphaMissenseDispatcher(mapper, new AlphaMissenseProvider(properties)));
    }
    private CandidateAcmgInputAdapter.Result dispatch(Run run, List<VariantInterpretation> ranked, int n, List<ExplicitInput> inputs) {
        return adapter(false).dispatch(run.job(), run.conversion(), run.execution(), analysis, ranked, n, inputs);
    }
    private void blocked(CandidateAcmgInputAdapter.Result result, String reason) {
        assertEquals(0, result.dispatch().providerCalls());
        assertEquals(0, result.dispatch().evaluatorCalls());
        var attempt = result.dispatch().candidates().get(0).attempts().get(0);
        assertTrue(attempt.blockers().stream().anyMatch(b -> b.reasonCode().equals(reason)), attempt.blockers().toString());
        assertNull(attempt.context());
        assertTrue(attempt.criterionEvaluations().isEmpty());
    }

    private PatientDiseaseContextBindingResolver subsetResolver() throws Exception {
        ObjectNode manifest = mapper.createObjectNode().put("profileId", "PATIENT_DISEASE_BINDING_INTAKE")
                .put("profileVersion", "1.0.0").put("parserVersion", "1.0.0")
                .put("testDataClass", "SYNTHETIC_SUBSET_ENVELOPE_FROM_ACCEPTED_PUBLIC_ROWS");
        manifest.set("intakePolicy", mapper.readTree("""
                {"policyId":"PATIENT_DISEASE_BINDING_INTAKE","version":"1.0.0","readyClassifications":["Definitive","Strong","Moderate"],
                "reviewRequiredClassifications":["Limited"],"notReadyClassifications":["Disputed","Refuted","No Known Disease Relationship",""],
                "catalogMoiMappings":{"AD":"AUTOSOMAL_DOMINANT","AR":"AUTOSOMAL_RECESSIVE"},"unmappedCatalogMoi":["XL","SD","UD","MT"],
                "claim":"VARVIS_BINDING_INTAKE_ONLY_NOT_PATIENT_DIAGNOSIS"}
                """));
        for (String name : List.of("mane", "clingen")) {
            ArrayNode rows = mapper.createArrayNode();
            for (var candidate : accepted.path("candidates")) {
                var binding = candidate.at("/attempts/0/preflight/original/binding/provenance");
                binding.path(name.equals("mane") ? "crosswalkRecords" : "relationshipRecords").forEach(rows::add);
            }
            // A synthetic contract branch only; never a public disease/allele annotation assertion.
            if (name.equals("mane")) {
                rows.addObject().put("ncbiGeneId", "999").put("hgncId", "HGNC:1748").put("symbol", "CDH1")
                        .put("testDataClass", "SYNTHETIC").putArray("sourceRows").add(99999);
            } else {
                rows.addObject().put("hgncId", "HGNC:1748").put("symbol", "CDH1").put("mondoId", "MONDO:0007648")
                        .put("moi", "AD").put("diseaseLabel", "Synthetic CSpec test disease").put("classification", "Definitive")
                        .put("classificationDate", "2026-09-06").put("gcep", "SYNTHETIC")
                        .put("reportLocator", "synthetic/cspec-test").put("sourceRow", 99999).put("testDataClass", "SYNTHETIC");
            }
            byte[] bytes = mapper.writeValueAsBytes(rows);
            Files.write(temporary.resolve(name + ".json"), bytes);
            ObjectNode identity = (ObjectNode) accepted.at("/candidates/0/attempts/0/preflight/original/binding/provenance/"
                    + (name.equals("mane") ? "maneResourceIdentity" : "clingenResourceIdentity")).deepCopy();
            identity.put("derivedSha256", sha(bytes)).put("derivedRecordCount", rows.size()).put("filterPolicy", "SYNTHETIC_TEST_SUBSET");
            manifest.withObject("resources").set(name, identity);
        }
        Files.write(temporary.resolve("manifest.json"), mapper.writeValueAsBytes(manifest));
        return PatientDiseaseContextBindingResolver.load(mapper, temporary.resolve("manifest.json"),
                temporary.resolve("mane.json"), temporary.resolve("clingen.json"));
    }

    private void export(String label, Run run, CandidateAcmgInputAdapter.Result result) throws Exception {
        String directory = System.getProperty("varvis.e4a3a.output-dir");
        if (directory == null) return;
        ObjectNode value = mapper.valueToTree(result.dispatch());
        value.set("candidateInputProvenance", result.inputProvenance());
        ObjectNode smoke = (ObjectNode) accepted.path("smokeInputProvenance").deepCopy();
        smoke.set("sourceReceipts", exportedSources.deepCopy());
        if (label.equals("cspec-blocked")) value.set("syntheticSourceReceipts", exportedSources.deepCopy());
        else value.set("smokeInputProvenance", smoke);
        var fixture = value.putObject("candidateInputFixture").put("runner", "SYNTHETIC_TSV_RUNNER_NOT_REAL_EXOMISER")
                .put("patientDataClass", "SYNTHETIC").put("rankingDataClass", "SYNTHETIC")
                .put("rawAnalysisVcf", Files.readString(run.conversion().getOutputFile()))
                .put("rawVariantTsv", Files.readString(run.execution().getWorkDirectory().resolve("output/result.variants.tsv")));
        fixture.put("variantTsvSha256", sha(fixture.path("rawVariantTsv").asText().getBytes(StandardCharsets.UTF_8)));
        fixture.put("analysisVcfSha256", sha(fixture.path("rawAnalysisVcf").asText().getBytes(StandardCharsets.UTF_8)));
        fixture.put("originalRecordNullCount", run.execution().getInterpretations().stream().filter(c -> c.getObservation().getOriginalRecord() == null).count());
        Path output = Path.of(directory).resolve("e4-a3a-" + label + ".json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value).replace("\r\n", "\n") + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
}
