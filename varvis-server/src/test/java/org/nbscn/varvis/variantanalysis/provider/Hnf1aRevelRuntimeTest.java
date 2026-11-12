package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.DiseaseContextBinding;
import org.nbscn.varvis.variantanalysis.service.TopNAcmgContextPlanner;
import org.nbscn.varvis.variantanalysis.service.TopNAcmgContextPlanner.ContextSeed;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** All fixed scores and source rows here are engineering fixtures, never resource acceptance. */
class Hnf1aRevelRuntimeTest {
    private static final String HEADER = "chr,hg19_pos,grch38_pos,ref,alt,aaref,aaalt,REVEL,Ensembl_transcriptid";
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    @TempDir Path directory;

    @Test void dispatcherUsesAvailableRevelWithDisabledAmAndNeverCallsAnotherPredictor() throws Exception {
        var original = candidate(); var key = original.getObservation().getKey();
        var candidate = new VariantInterpretation(CandidateIdentity.exomiser(key.canonicalId(), 1, "HNF1A", "AD", 1),
                original.getObservation(), List.of(), List.of());
        candidate.applyTranscriptAnnotations(original.getTranscriptAnnotations());
        var analysis = new AnalysisContext("analysis-revel-dispatch-synthetic", GenomeAssembly.GRCh38, AnalysisMode.WES);
        var draft = context(candidate); draft.remove(List.of("schemaVersion", "contextId", "canonicalizationProfile", "contextCompleteness"));
        ((ObjectNode) draft.path("variant")).remove("alleleId");
        var seed = new ContextSeed(candidate.getCandidateId(), draft);
        var planned = new TopNAcmgContextPlanner(mapper).plan(analysis, List.of(candidate), 1, List.of(seed));
        var context = planned.candidates().get(0).readyContexts().get(0);
        var binding = new DiseaseContextBinding(candidate.getCandidateId(), "synthetic-revel-hypothesis", "HGNC:11621", "HNF1A", "MONDO:0015967",
                "monogenic diabetes", "AUTOSOMAL_DOMINANT", "Definitive", "2026-09-10", "SYNTHETIC", "synthetic/relationship", "READY", null, null);
        var ref = new SourceRef("SYNTHETIC", "1", "synthetic/revel-receipt", "f".repeat(64));
        var provenance = new Provenance("SYNTHETIC", ref, ref, ref, ref, new AssemblyLineage("GRCh38", "NATIVE",
                new Allele("GRCh38", "12", 120989017, "C", "G"), null, ref));
        var attempt = new Attempt(candidate.getCandidateId(), binding.hypothesisId(), binding, provenance, seed, context, List.of());
        var assembled = new AssemblyResult(analysis.getAnalysisId(), 1, 1, List.of(candidate.getCandidateId()),
                List.of(new CandidateAssembly(candidate.getCandidateId(), List.of(attempt))), List.of(), false);
        var properties = new VariantAnalysisProperties(); properties.getAlphaMissense().setEnabled(false);
        int[] amCalls = {0};
        var am = new AlphaMissenseProvider(properties) {
            @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotateSelectedTranscripts(List<VariantInterpretation> variants,
                    AnalysisContext ignored, Map<NormalizedVariantKey, TranscriptConsequence> selected) {
                amCalls[0]++; throw new AssertionError("HNF1A selected REVEL before any AM availability or score query");
            }
        };
        var revel = runtime("0.8");
        var dispatcher = new ExplicitAlphaMissenseDispatcher(mapper, am, () -> { throw new AssertionError("Protein REVEL cannot invoke splice runtime"); }, () -> revel);
        var result = dispatcher.dispatch(assembled, List.of(candidate), analysis);
        var executed = result.candidates().get(0).attempts().get(0);
        assertTrue(executed.blockers().isEmpty(), executed.blockers().toString());
        assertEquals(0, amCalls[0]); assertEquals(1, result.providerCalls()); assertEquals(2, result.evaluatorCalls());
        assertEquals(2, executed.criterionEvaluations().size());
        assertEquals("MET", executed.criterionEvaluations().get(0).at("/evaluationResult/status").asText());
        assertEquals("NOT_MET", executed.criterionEvaluations().get(1).at("/evaluationResult/status").asText());
        assertTrue(executed.criterionEvaluations().stream().noneMatch(e -> "ACMG_AMP_2015:BP7".equals(e.path("criterionId").asText())));
        assertTrue(executed.atomicEvidence().stream().noneMatch(e -> e.at("/source/sourceId").asText().startsWith("ALPHAMISSENSE")));
        String fixtureOutput = System.getProperty("varvis.revel.fixtureBundleOutput");
        if (fixtureOutput != null) Files.writeString(Path.of(fixtureOutput), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                new AcmgBusinessArtifacts(mapper).bundles(result)), StandardCharsets.UTF_8);
    }

    @ParameterizedTest @CsvSource({"0.149999,NOT_MET,MET", "0.15,NOT_MET,MET", "0.150001,NOT_MET,NOT_MET",
            "0.699999,NOT_MET,NOT_MET", "0.70,MET,NOT_MET", "0.700001,MET,NOT_MET", "0,NOT_MET,MET", "1,MET,NOT_MET"})
    void exactInclusiveDiseaseThresholdsAreSupportingOnly(String score, String pp3, String bp4) throws Exception {
        var runtime = runtime(score); var candidate = candidate(); var result = runtime.evaluate(context(candidate), candidate);
        assertTrue(result.blockers().isEmpty(), result.blockers().toString());
        assertEquals(1, result.providerCalls()); assertEquals(2, result.evaluatorCalls());
        assertEquals(2, result.atomicEvidence().size()); assertEquals(2, result.criterionEvaluations().size());
        assertEquals(pp3, result.criterionEvaluations().get(0).at("/evaluationResult/status").asText());
        assertEquals(bp4, result.criterionEvaluations().get(1).at("/evaluationResult/status").asText());
        for (var evaluation : result.criterionEvaluations()) {
            assertEquals("SUPPORTING", evaluation.path("baseStrength").asText());
            assertEquals("MET".equals(evaluation.at("/evaluationResult/status").asText()) ? "SUPPORTING" : "NONE", evaluation.path("appliedStrength").asText());
            assertFalse(evaluation.at("/evaluationResult").has("reasonCode"));
            var identity = evaluation.deepCopy(); identity.remove(List.of("evaluationId", "conflict", "review"));
            assertEquals("evaluation-sha256-" + adapter.canonicalSha256(identity), evaluation.path("evaluationId").asText());
        }
        var prediction = result.atomicEvidence().get(1);
        assertEquals("REVEL_HNF1A_2021", prediction.at("/source/sourceId").asText());
        assertEquals(score, prediction.at("/measurements/0/rawValue").asText());
        assertEquals("UNVERSIONED_SOURCE_NO_VERSION_EQUIVALENCE_CLAIM", result.transcriptInput().path("sourceTranscriptVersionStatus").asText());
        assertFalse(result.transcriptInput().path("bp7DependencyEligible").asBoolean(true));
        assertEquals("PROTEIN_MISSENSE_PREDICTION", result.transcriptInput().path("evidenceMechanism").asText());
        assertEquals(0, result.transcriptInput().path("modelInferenceCalls").asInt(-1));
    }

    @ParameterizedTest @CsvSource({".,REVEL_SCORE_MISSING", "NaN,REVEL_SCORE_MISSING", "bad,REVEL_SCORE_INVALID", "-0.01,REVEL_SCORE_INVALID", "1.01,REVEL_SCORE_INVALID"})
    void invalidAndAbsentScoresNeverBecomeBenign(String score, String issue) throws Exception {
        var runtime = runtime(score); var candidate = candidate(); assertIssue(runtime.evaluate(context(candidate), candidate), issue);
    }
    @Test void unavailableAndDisabledAreDifferentAndHaveNoProviderQueries() throws Exception {
        var candidate = candidate(); var context = context(candidate);
        for (var runtime : List.of(Hnf1aRevelRuntime.unavailable(mapper), Hnf1aRevelRuntime.disabled(mapper), Hnf1aRevelRuntime.configurationUnavailable(mapper))) {
            var result = runtime.evaluate(context, candidate); String issue = runtime.providerInfo().path("reasonCode").asText();
            assertIssue(result, issue); assertEquals(0, runtime.providerCalls()); assertEquals(0, result.providerCalls());
            assertEquals("NOT_QUERIED", result.atomicEvidence().get(1).at("/queryOutcome/status").asText());
            assertEquals("REVEL_RESOURCE_DISABLED".equals(issue) ? "DISABLED" : "NOT_RUN", result.atomicEvidence().get(1).at("/resourceExecution/status").asText());
            assertEquals("SINGLE_SELECTABLE_MATCH", result.selection().path("outcome").asText());
        }
    }
    @ParameterizedTest @CsvSource({"gene,HGNC:1,REVEL_POLICY_NOT_APPLICABLE", "disease,MONDO:0010894,REVEL_POLICY_NOT_APPLICABLE",
            "moi,AUTOSOMAL_RECESSIVE,REVEL_POLICY_NOT_APPLICABLE", "transcript,10,REVEL_TRANSCRIPT_NOT_SUPPORTED",
            "selection,PM2_CONTEXT_ONLY,REVEL_TRANSCRIPT_CONFIRMATION_REQUIRED"})
    void exactConfirmedDiseaseAndTranscriptScopeIsRequired(String dimension, String value, String issue) throws Exception {
        var candidate = candidate(); var context = context(candidate);
        switch (dimension) {
            case "gene" -> ((ObjectNode) context.path("gene")).put("hgncId", value);
            case "disease" -> ((ObjectNode) context.path("disease")).put("mondoId", value);
            case "moi" -> context.put("moi", value);
            case "transcript" -> ((ObjectNode) context.path("transcript")).put("version", Integer.parseInt(value));
            case "selection" -> ((ObjectNode) context.path("transcript")).put("selectionPolicy", value);
            default -> fail("unexpected fixture dimension");
        }
        refresh(context); var runtime = runtime("0.8"); var result = runtime.evaluate(context, candidate);
        assertEquals(issue, result.blockers().get(0).code()); assertEquals(0, runtime.providerCalls()); assertEquals(0, runtime.evaluatorCalls());
    }
    @ParameterizedTest @CsvSource({"hgvsCoding,ENST00000257555.11:c.512C>G,REVEL_GENOMIC_CDS_MAPPING_MISMATCH",
            "hgvsProtein,ENSP00000257555.4:p.Arg171Gly,REVEL_PROTEIN_CODON_MISMATCH", "codons,CAA/GAA,REVEL_PROTEIN_CODON_MISMATCH",
            "aminoAcids,Q/E,REVEL_PROTEIN_CODON_MISMATCH", "proteinPosition,172,REVEL_PROTEIN_CODON_MISMATCH",
            "exon,3/10,REVEL_EXON_STRUCTURE_MISMATCH", "consequence,missense_variant&splice_region_variant,REVEL_SPLICE_BRANCH_NOT_IMPLEMENTED",
            "consequence,synonymous_variant,REVEL_CONSEQUENCE_NOT_APPLICABLE"})
    void allIndependentSequenceAndAnnotationChecksAreRequired(String field, String value, String issue) throws Exception {
        var candidate = candidate(); var context = context(candidate); mutate(candidate, field, value);
        var runtime = runtime("0.1"); assertIssue(runtime.evaluate(context, candidate), issue); assertEquals(0, runtime.providerCalls());
    }
    @Test void aDifferentCandidateCannotBorrowTheContextAnnotation() throws Exception {
        var candidate = candidate(); var context = context(candidate);
        ((ObjectNode) context.at("/variant/evidenceQueryAllele")).put("alternate", "A");
        // Inconsistent original/query identity must fail before any source query.
        refresh(context); var result = runtime("0.9").evaluate(context, candidate);
        assertFalse(result.blockers().isEmpty()); assertEquals(0, result.providerCalls());
    }
    @Test void duplicateSelectedAnnotationNeverChoosesTheFirst() throws Exception {
        var candidate = candidate(); var context = context(candidate); var tx = candidate.getTranscriptAnnotations().get(0);
        candidate.applyTranscriptAnnotations(List.of(tx, tx));
        assertEquals("REVEL_SELECTED_TRANSCRIPT_NOT_UNIQUE", runtime("0.8").evaluate(context, candidate).blockers().get(0).code());
    }
    @Test void sourceAminoAcidsMustMatchDerivedCurrentVersionedCodon() throws Exception {
        var candidate = candidate(); var runtime = fromRows(List.of(row("0.9").replace(",R,G,", ",Q,E,")));
        assertIssue(runtime.evaluate(context(candidate), candidate), "REVEL_SOURCE_AMINO_ACID_MISMATCH");
    }
    @Test void sourceVersionedOrDifferentAccessionsAreNotSilentlyStripped() throws Exception {
        var candidate = candidate(); var runtime = fromRows(List.of(row("0.9").replace("ENST00000257555", TRANSCRIPT)));
        assertIssue(runtime.evaluate(context(candidate), candidate), "REVEL_SOURCE_TRANSCRIPT_MISMATCH");
    }
    private static final String TRANSCRIPT = "ENST00000257555.11";
    @Test void conflictingDuplicateScoresAreNotResolvedInFavorOfEitherRule() throws Exception {
        var candidate = candidate(); var runtime = fromRows(List.of(row("0.1"), row("0.9")));
        assertIssue(runtime.evaluate(context(candidate), candidate), "REVEL_RECORD_CONFLICT");
    }
    @Test void agreeingDuplicatesRetainBothRecordsWithoutDuplicateCriteria() throws Exception {
        var candidate = candidate(); var runtime = fromRows(List.of(row("0.10"), row("0.1")));
        var result = runtime.evaluate(context(candidate), candidate); assertTrue(result.blockers().isEmpty());
        assertEquals(2, result.criterionEvaluations().size()); assertEquals(2, result.atomicEvidence().get(1).at("/source/sourceRecords").size());
    }
    @Test void conflictingPublishedMappingsCannotBeCollapsedByIdenticalScore() throws Exception {
        var candidate = candidate(); var runtime = fromRows(List.of(row("0.1"), row("0.1").replace("121426820", "121426821")));
        assertIssue(runtime.evaluate(context(candidate), candidate), "REVEL_MAPPING_AMBIGUOUS");
    }
    @Test void missingPublishedCoordinateStaysUnresolvedRatherThanZero() throws Exception {
        var candidate = candidate(); var runtime = fromRows(List.of(row("0.1").replace(",120989017,", ",.,")));
        assertIssue(runtime.evaluate(context(candidate), candidate), "REVEL_PUBLISHED_MAPPING_MISSING");
    }
    @Test void successfulEmptyQueryIsNoRecordAndNeverBp4() throws Exception {
        var candidate = candidate(); var runtime = fromRows(List.of(row("0.1").replace(",C,G,", ",C,A,")));
        var result = runtime.evaluate(context(candidate), candidate); assertIssue(result, "REVEL_NO_RECORD");
        assertEquals("NO_RECORD", result.atomicEvidence().get(1).at("/queryOutcome/status").asText());
    }
    @Test void sourceChecksumAndOriginalLineAreBothVerified() throws Exception {
        var candidate = candidate(); Path manifest = resource(List.of(row("0.8")));
        Files.writeString(directory.resolve("segment.csv"), HEADER + "\n" + row("0.9") + "\n");
        assertIssue(new Hnf1aRevelRuntime(mapper, manifest).evaluate(context(candidate), candidate), "REVEL_RESOURCE_CHECKSUM_MISMATCH");
        var value = (ObjectNode) mapper.readTree(Files.readAllBytes(manifest));
        ((ObjectNode) value.path("source")).put("sha256", sha(Files.readAllBytes(directory.resolve("segment.csv"))));
        Files.writeString(manifest, mapper.writeValueAsString(value));
        assertIssue(new Hnf1aRevelRuntime(mapper, manifest).evaluate(context(candidate), candidate), "REVEL_SOURCE_ROW_MISMATCH");
    }
    @Test void resourceAndPolicySelectionIdentitiesAreStableAndVersionBound() throws Exception {
        var candidate = candidate(); var context = context(candidate); var first = runtime("0.8");
        var second = runtime("0.1"); assertNotEquals(first.resourceIdentitySha256(), second.resourceIdentitySha256());
        // Rewriting all source files cannot change an already-bound immutable JVM snapshot.
        assertEquals("0.8", first.evaluate(context, candidate).transcriptInput().path("score").asText());
        assertEquals("0.1", second.evaluate(context, candidate).transcriptInput().path("score").asText());
        var selection = Hnf1aRevelRuntime.selection(mapper, context); var identity = selection.deepCopy(); identity.remove(List.of("selectionId", "conflict", "review"));
        assertEquals("cspec-selection-sha256-" + adapter.canonicalSha256(identity), selection.path("selectionId").asText());
        assertEquals("3.1", first.evaluate(context, candidate).transcriptInput().at("/policyAuthority/authorityVersion").asText());
        assertEquals(selection, Hnf1aRevelRuntime.selection(mapper, context));
        assertEquals(Hnf1aRevelRuntime.PROFILE_SHA256, adapter.canonicalSha256(Hnf1aRevelRuntime.loadProfile(mapper)));
    }

    @Test void maskedRevelPreInterruptedHasNoLookupOrEvaluation() throws Exception {
        var candidate = candidate(); var context = context(candidate); var runtime = runtime("0.8");
        try {
            Thread.currentThread().interrupt();
            var result = runtime.evaluate(context, candidate, java.util.Set.of("ACMG_AMP_2015:PP3"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, result.providerCalls()); assertEquals(0, result.evaluatorCalls());
            assertIssue(result, "PREDICTION_BUNDLE_INTERRUPTED");
        } finally { Thread.interrupted(); }
    }
    @Test void maskedRevelCancellationAfterActualRowLookupRetainsInputAndStopsRules() throws Exception {
        var candidate = candidate(); var context = context(candidate);
        var runtime = new Hnf1aRevelRuntime(interruptAfterSourceRowsMapper(), resource(List.of(row("0.8"))));
        try {
            var result = runtime.evaluate(context, candidate, java.util.Set.of("ACMG_AMP_2015:PP3", "ACMG_AMP_2015:BP4"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, result.providerCalls()); assertEquals(0, result.evaluatorCalls());
            assertEquals(1, result.transcriptInput().path("sourceRecords").size());
            assertEquals("0.8", result.transcriptInput().path("score").asText());
            assertEquals("CANCELLED", result.transcriptInput().path("executionStatus").asText());
            assertTrue(result.criterionEvaluations().isEmpty());
            assertIssue(result, "PREDICTION_BUNDLE_INTERRUPTED");
        } finally { Thread.interrupted(); }
    }
    @Test void oldPublicRevelEntryPreservesItsHistoricalEvaluationBehavior() throws Exception {
        var candidate = candidate(); var context = context(candidate);
        var runtime = new Hnf1aRevelRuntime(interruptAfterSourceRowsMapper(), resource(List.of(row("0.8"))));
        try {
            var result = runtime.evaluate(context, candidate);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, result.providerCalls()); assertEquals(2, result.evaluatorCalls());
            assertEquals("MET", result.criterionEvaluations().get(0).at("/evaluationResult/status").asText());
        } finally { Thread.interrupted(); }
    }
    private ObjectMapper interruptAfterSourceRowsMapper() {
        var interruptedMapper = new ObjectMapper() {
            @Override public <T extends com.fasterxml.jackson.databind.JsonNode> T valueToTree(Object value) {
                T result = super.valueToTree(value);
                // Inject only after the actual tiny source lookup has returned its source rows.
                if (value instanceof List<?> rows && !rows.isEmpty()) {
                    Object first = rows.get(0);
                    if (first != null && "Row".equals(first.getClass().getSimpleName())) Thread.currentThread().interrupt();
                }
                return result;
            }
        };
        interruptedMapper.findAndRegisterModules();
        return interruptedMapper;
    }

    private void assertIssue(Hnf1aRevelRuntime.Result result, String issue) {
        assertFalse(result.blockers().isEmpty()); assertEquals(issue, result.blockers().get(0).code());
        for (var evaluation : result.criterionEvaluations()) {
            assertEquals("NOT_EVALUATED", evaluation.at("/evaluationResult/status").asText());
            assertEquals("NONE", evaluation.path("appliedStrength").asText());
            assertEquals(issue, evaluation.at("/evaluationResult/reasonCode").asText());
        }
    }
    private Hnf1aRevelRuntime runtime(String score) throws Exception { return fromRows(List.of(row(score))); }
    private Hnf1aRevelRuntime fromRows(List<String> rows) throws Exception { return new Hnf1aRevelRuntime(mapper, resource(rows)); }
    private static String row(String score) { return "12,121426820,120989017,C,G,R,G," + score + ",ENST00000257555"; }
    private Path resource(List<String> rows) throws Exception {
        String csv = HEADER + "\n" + String.join("\n", rows) + "\n";
        StringBuilder tsv = new StringBuilder(HEADER.replace(',', '\t') + "\tsourceLine\n");
        for (int i = 0; i < rows.size(); i++) tsv.append(rows.get(i).replace(',', '\t')).append('\t').append(i + 2).append('\n');
        Files.writeString(directory.resolve("segment.csv"), csv); Files.writeString(directory.resolve("hnf1a-revel.tsv"), tsv);
        var manifest = mapper.createObjectNode().put("schemaVersion", "revel-hnf1a-resource-v1").put("resourceVersion", "1.3")
                .put("derivationVersion", "hnf1a-revel-cds-map-v1").put("dataFile", "hnf1a-revel.tsv").put("dataSha256", sha(tsv.toString().getBytes(StandardCharsets.UTF_8)))
                .put("transcriptModelSha256", Hnf1aRevelRuntime.loadProfile(mapper).at("/transcriptModel/canonicalSha256").asText());
        manifest.putObject("scope").put("hgncId", "HGNC:11621").put("transcript", TRANSCRIPT).put("refseqTranscript", "NM_000545.8")
                .put("assembly", "GRCh38").put("contig", "12").put("minPosition", 120978769).put("maxPosition", 121001189);
        manifest.putObject("source").put("file", "segment.csv").put("sha256", sha(csv.getBytes(StandardCharsets.UTF_8)))
                .put("originalAssembly", "GRCh37").put("publishedAssembly", "GRCh38").put("mappingMethod", "REVEL_publisher_liftOver")
                .put("url", "https://example.test/revel-engineering-fixture.csv").put("retrievedAt", "2026-09-10T00:00:00Z");
        manifest.put("dataClass", "SYNTHETIC_ENGINEERING_FIXTURE");
        Path path = directory.resolve("revel-hnf1a.manifest.json"); Files.writeString(path, mapper.writeValueAsString(manifest)); return path;
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private VariantInterpretation candidate() {
        var key = new NormalizedVariantKey("GRCh38", "12", 120989017, "C", "G");
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(key);
        candidate.applyTranscriptAnnotations(List.of(new TranscriptConsequence("HNF1A", "ENSG00000135100", "ENST00000257555",
                TRANSCRIPT + ":c.511C>G", "ENSP00000257555.5:p.Arg171Gly", "missense_variant", "ENSG00000135100", "ENST00000257555",
                "Ensembl", "MODERATE", "2/10", null, true, true, false, "171", "R/G", "CGA/GGA", null, null, null,
                "VEP_CACHED", "COMPLETED", "116.0", Instant.parse("2026-09-10T00:00:00Z")))); return candidate;
    }
    private ObjectNode context(VariantInterpretation candidate) throws Exception {
        var context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, candidate.getObservation().getKey(), "ENST00000257555");
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:11621").put("symbol", "HNF1A");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0015967").put("label", "monogenic diabetes"); context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript")).put("version", 11).put("annotationSourceVersion", "116.0"); refresh(context); return context;
    }
    private void refresh(ObjectNode context) { context.put("contextId", adapter.canonicalContextId(context)); }
    private void mutate(VariantInterpretation candidate, String field, String value) throws Exception {
        ObjectNode json = mapper.valueToTree(candidate.getTranscriptAnnotations().get(0)); json.put(field, value);
        json.retain(List.of("intron", "consequence", "lofFlags", "hgvsCoding", "hgvsProtein", "entrezGeneId", "geneSymbol", "transcriptAccession", "geneId", "transcriptId", "exon", "canonical", "timestamp", "manePlusClinical", "lofFilter", "codons", "lof", "impact", "proteinPosition", "transcriptSource", "status", "aminoAcids", "source", "maneSelect", "version"));
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(json, TranscriptConsequence.class)));
    }
}
