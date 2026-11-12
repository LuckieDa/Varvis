package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.nbscn.varvis.variantanalysis.provider.CSpecExecutionPreflight;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Allele;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.AssemblyLineage;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.AssemblyResult;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Attempt;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.CandidateAssembly;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.ExplicitInput;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.NormalizationInput;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.PatientContext;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.TranscriptChoice;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.DiseaseContextBinding;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.BindingProvenance;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.HypothesisProvenance;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.PatientDiseaseHypothesis;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic inputs only; tiny reference bytes are hashed and read to establish each tested REF. */
class ExplicitEvidenceContextAssemblerTest {

    private static final String ANNOTATION_VERSION = "synthetic-annotation-v1";
    private static final String ANNOTATION_SOURCE = "SYNTHETIC_TRANSCRIPT_ANNOTATION";
    private static final String REFERENCE_SEQUENCE = "AAAAAACGTACGTACGT";

    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnalysisContext analysis = new AnalysisContext(
            "analysis-synthetic-e4a1b", GenomeAssembly.GRCh38, AnalysisMode.WES);
    private PatientDiseaseContextBindingResolver resolver;
    private Path referenceFile;
    private String referenceSha256;
    private ExplicitContextSourceLedger referenceLedger;
    private final List<ExplicitContextSourceLedger> sourceLedgers = new ArrayList<>();
    private int sourceOrdinal;

    @BeforeEach
    void setUp() throws Exception {
        referenceFile = temporary.resolve("synthetic-reference.fa");
        Files.writeString(referenceFile, ">1\n" + REFERENCE_SEQUENCE + "\n", StandardCharsets.US_ASCII);
        referenceSha256 = sha256(Files.readAllBytes(referenceFile));
        resolver = writeBindingResources();
        replaceReference(record -> { });
    }

    @Test
    void cSpecPreflightKeepsDistinctCompleteContextsAcrossSharedAllelesAndReusedLocalHypothesisIds() throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation second = candidate(1, GenomeAssembly.GRCh38, 1, "SYNTHB", "102", "AR", "NM_001002.3");
        ExplicitInput firstInput = input(first, binding(first, "shared-local-hypothesis", "HGNC:101", "MONDO:0000101", "AD"), null);
        ExplicitInput otherDisease = input(first, binding(first, "local-hypothesis-a2", "HGNC:101", "MONDO:0000102", "AD"), null);
        ExplicitInput secondInput = input(second, binding(second, "shared-local-hypothesis", "HGNC:102", "MONDO:0000103", "AR"), null);
        AssemblyResult assembled = assembler().assemble(analysis, List.of(first, second), 2,
                List.of(firstInput, otherDisease, secondInput));
        ObjectNode before = mapper.valueToTree(assembled);
        CSpecExecutionPreflight preflight = new CSpecExecutionPreflight(mapper);
        var result = preflight.check(assembled);
        assertEquals(2, result.candidates().size());
        assertEquals(List.of(2, 1), result.candidates().stream().map(row -> row.attempts().size()).toList());
        ArrayNode exports = mapper.createArrayNode();
        List<String> selectionIds = new ArrayList<>();
        List<String> contextIds = new ArrayList<>();
        List<String> alleleIds = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < result.candidates().size(); rowIndex++) {
            var attempts = result.candidates().get(rowIndex).attempts();
            for (int attemptIndex = 0; attemptIndex < attempts.size(); attemptIndex++) {
                var selected = attempts.get(attemptIndex);
                Attempt original = assembled.candidates().get(rowIndex).attempts().get(attemptIndex);
                ObjectNode context = ready(original);
                assertEquals(original, selected.original());
                assertTrue(selected.blockers().isEmpty());
                assertEquals("ZERO_MATCH", selected.outcome());
                ObjectNode selection = selected.selection();
                assertNotNull(selection);
                assertEquals("cspec-selection-v1", selection.path("schemaVersion").asText());
                assertEquals("ZERO_MATCH", selection.path("outcome").asText());
                assertEquals(context.path("contextId"), selection.path("contextId"));
                assertTrue(selection.path("candidateOverlayIds").isEmpty());
                assertFalse(selection.has("selectedOverlayId"));
                assertFalse(selection.has("selectedOverlaySha256"));
                assertFalse(selection.has("reasonCode"));
                assertEquals("NONE", selection.at("/conflict/status").asText());
                assertEquals("NOT_REQUIRED", selection.at("/review/status").asText());
                assertNotNull(selected.constraints());
                for (String field : List.of("criterionEvaluationConstraint", "combinationDisposition", "reviewConstraint", "overlaySelectionStatus")) {
                    assertTrue(selected.constraints().has(field), field);
                }
                contextIds.add(context.path("contextId").asText());
                alleleIds.add(context.at("/variant/alleleId").asText());
                selectionIds.add(selection.path("selectionId").asText());
                ObjectNode exported = mapper.createObjectNode();
                exported.set("context", context);
                exported.set("selection", selection);
                exported.set("reviewRecords", mapper.createArrayNode());
                exported.set("conflicts", mapper.createArrayNode());
                exports.add(exported);
            }
        }
        assertEquals(3, contextIds.stream().distinct().count());
        assertEquals(3, selectionIds.stream().distinct().count());
        assertEquals(1, alleleIds.stream().distinct().count());
        assertEquals(before, mapper.valueToTree(assembled));
        assertEquals(mapper.valueToTree(result), mapper.valueToTree(preflight.check(assembled, List.of(), List.of())));
        String output = System.getProperty("varvis.e4a2a.contextOutput");
        if (output != null && !output.isBlank()) mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(output).toFile(), exports);
    }

    @Test
    void cSpecPreflightKeepsFailedAttemptsInOrderWithTheirOriginalBindingAndSourceProvenance() throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation second = candidate(1, GenomeAssembly.GRCh38, 7, "SYNTHB", "102", "AR", "NM_001002.3");
        ExplicitInput firstInput = input(first, firstBinding(first), null);
        ExplicitInput missingTranscript = input(first, binding(first, "local-hypothesis-a2", "HGNC:101", "MONDO:0000102", "AD"), null);
        missingTranscript = copy(missingTranscript, missingTranscript.binding(), null, missingTranscript.patient(), missingTranscript.normalization());
        ExplicitInput secondInput = input(second, binding(second, "local-hypothesis-b", "HGNC:102", "MONDO:0000103", "AR"), null);
        AssemblyResult assembled = assembler().assemble(analysis, List.of(first, second), 2,
                List.of(missingTranscript, firstInput, secondInput));
        var result = new CSpecExecutionPreflight(mapper).check(assembled);
        assertEquals(2, result.candidates().size());
        for (int rowIndex = 0; rowIndex < result.candidates().size(); rowIndex++) {
            var actual = result.candidates().get(rowIndex).attempts();
            List<Attempt> original = assembled.candidates().get(rowIndex).attempts();
            assertEquals(original.size(), actual.size());
            for (int index = 0; index < original.size(); index++) {
                assertEquals(original.get(index), actual.get(index).original());
                assertEquals(original.get(index).binding(), actual.get(index).original().binding());
                assertEquals(original.get(index).provenance(), actual.get(index).original().provenance());
            }
        }
        var failed = result.candidates().get(0).attempts().get(0);
        assertNull(failed.selection());
        assertNull(failed.outcome());
        assertEquals(1, failed.blockers().size());
        assertEquals(failed.original().blockers().get(0).reasonCode(), failed.blockers().get(0).reasonCode());
        assertEquals(failed.original().blockers().get(0).field(), failed.blockers().get(0).field());
        assertEquals("ZERO_MATCH", result.candidates().get(0).attempts().get(1).outcome());
        assertEquals("ZERO_MATCH", result.candidates().get(1).attempts().get(0).outcome());
        assertEquals(first.getCandidateId(), result.candidates().get(0).attempts().get(1).original().candidateId());
        assertEquals(second.getCandidateId(), result.candidates().get(1).attempts().get(0).original().candidateId());
    }

    @Test
    void cSpecPreflightRejectsChangedContextOrAlleleIdentityWithoutDiscardingValidSibling() throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation second = candidate(1, GenomeAssembly.GRCh38, 7, "SYNTHB", "102", "AR", "NM_001002.3");
        ExplicitInput firstInput = input(first, firstBinding(first), null);
        ExplicitInput secondInput = input(second, binding(second, "local-hypothesis-b", "HGNC:102", "MONDO:0000103", "AR"), null);
        AssemblyResult assembled = assembler().assemble(analysis, List.of(first, second), 2, List.of(firstInput, secondInput));
        Attempt original = assembled.candidates().get(0).attempts().get(0);
        for (String identity : List.of("contextId", "alleleId")) {
            ObjectNode changedContext = original.context();
            if ("contextId".equals(identity)) changedContext.put("contextId", "context-sha256-" + "0".repeat(64));
            else changedContext.withObjectProperty("variant").put("alleleId", "allele-sha256-" + "0".repeat(64));
            Attempt changed = new Attempt(original.candidateId(), original.hypothesisId(), original.binding(), original.provenance(),
                    original.seed(), changedContext, List.of());
            AssemblyResult altered = new AssemblyResult(assembled.analysisId(), assembled.requestedN(), assembled.selectedCount(),
                    assembled.candidateIds(), List.of(new CandidateAssembly(first.getCandidateId(), List.of(changed)),
                    assembled.candidates().get(1)), assembled.unmatchedInputs(), assembled.candidateOrderingChanged());
            var result = new CSpecExecutionPreflight(mapper).check(altered);
            var failed = result.candidates().get(0).attempts().get(0);
            assertEquals(changed, failed.original());
            assertNull(failed.selection());
            assertFalse(failed.blockers().isEmpty());
            var successful = result.candidates().get(1).attempts().get(0);
            assertEquals("ZERO_MATCH", successful.outcome());
            assertEquals(assembled.candidates().get(1).attempts().get(0), successful.original());
            assertEquals(successful.original().context().path("contextId"), successful.selection().path("contextId"));
        }
        ready(original);
    }

    @Test
    void assemblesTwoGenesAndExplicitHypothesesWithDeterministicSingletonAndTrioContexts() throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation second = candidate(1, GenomeAssembly.GRCh38, 7, "SYNTHB", "102", "AR", "NM_001002.3");
        DiseaseContextBinding firstBinding = firstBinding(first);
        DiseaseContextBinding secondHypothesis = binding(first, "hypothesis-a2", "HGNC:101", "MONDO:0000102", "AD");
        DiseaseContextBinding secondBinding = binding(second, "hypothesis-b", "HGNC:102", "MONDO:0000103", "AR");
        ExplicitInput singleton = input(first, firstBinding, null);
        ExplicitInput anotherDisease = input(first, secondHypothesis, null);
        ExplicitInput secondInput = input(second, secondBinding, null);
        List<VariantInterpretation> ranked = List.of(first, second);
        List<ExplicitInput> inputs = List.of(singleton, anotherDisease, secondInput);

        AssemblyResult result = assembler().assemble(analysis, ranked, 2, inputs);
        assertEquals(List.of(first.getCandidateId(), second.getCandidateId()), result.candidateIds());
        assertFalse(result.candidateOrderingChanged());
        assertEquals(2, result.candidates().get(0).attempts().size());
        ObjectNode firstContext = ready(result.candidates().get(0).attempts().get(0));
        ObjectNode siblingContext = ready(result.candidates().get(0).attempts().get(1));
        ObjectNode secondContext = ready(result.candidates().get(1).attempts().get(0));
        assertFalse(firstContext.path("patientContext").has("familyContextId"));
        assertEquals("SYNTHB", secondContext.at("/gene/symbol").asText());
        assertNotEquals(firstContext.path("contextId"), siblingContext.path("contextId"));
        assertEquals(firstContext.at("/variant/alleleId"), siblingContext.at("/variant/alleleId"));
        assertEquals(referenceSha256, firstContext.at("/variant/normalization/referenceSequenceSha256").asText());
        assertEquals(mapper.valueToTree(result), mapper.valueToTree(assembler().assemble(analysis, ranked, 2, inputs)));
        AssemblyResult singletonResult = assembler().assemble(analysis, List.of(first), 1, List.of(singleton));
        assertEquals(1, singletonResult.candidates().get(0).attempts().size());
        ExplicitInput trio = input(first, firstBinding, "family-context-synthetic-e4a1b-family");
        AssemblyResult trioResult = assembler().assemble(analysis, List.of(first), 1, List.of(trio));
        ObjectNode trioContext = ready(trioResult.candidates().get(0).attempts().get(0));
        assertEquals("family-context-synthetic-e4a1b-family", trioContext.at("/patientContext/familyContextId").asText());
        assertEquals(firstContext.at("/variant/alleleId"), trioContext.at("/variant/alleleId"));
        assertNotEquals(firstContext.path("contextId"), trioContext.path("contextId"));
        ExplicitInput missingTranscript = copy(anotherDisease, anotherDisease.binding(), null, anotherDisease.patient(), anotherDisease.normalization());
        AssemblyResult mixed = assembler().assemble(analysis, ranked, 1, List.of(singleton, missingTranscript, secondInput));
        ready(mixed.candidates().get(0).attempts().get(0));
        blocked(mixed.candidates().get(0).attempts().get(1), "TRANSCRIPT_CHOICE_REQUIRED");
        blocked(mixed.unmatchedInputs().get(0), "INPUT_OUTSIDE_SELECTED_TOP_N");
        String serialized = mapper.writeValueAsString(result);
        assertFalse(serialized.contains("sampleId"));
        assertFalse(serialized.contains("synthetic-proband"));
        assertFalse(serialized.contains(temporary.toString()));
        assertFalse(serialized.contains("criterion-evaluation"));
        assertFalse(serialized.contains("evidence-bundle"));
        ObjectNode results = mapper.createObjectNode();
        results.set("singleton", mapper.valueToTree(singletonResult));
        results.set("trio", mapper.valueToTree(trioResult));
        results.set("multipleHypotheses", mapper.valueToTree(result));
        results.set("mixed", mapper.valueToTree(mixed));
        AssemblyResult preRankingLiftOver = assembler().assemble(analysis, List.of(first), 1, List.of(liftedInput(singleton)));
        blocked(preRankingLiftOver.candidates().get(0).attempts().get(0), "PRE_RANKING_LIFTOVER_UNSUPPORTED");
        results.set("preRankingLiftOver", mapper.valueToTree(preRankingLiftOver));
        String resourceDirectory = System.getProperty("varvis.e4a1a.resourceDir");
        if (resourceDirectory != null && !resourceDirectory.isBlank()) {
            Path resources = Path.of(resourceDirectory);
            resolver = PatientDiseaseContextBindingResolver.load(mapper, resources.resolve("manifest.json"),
                    resources.resolve("mane-crosswalk.json"), resources.resolve("clingen-catalog.json"));
            VariantInterpretation aars1 = candidate(1, GenomeAssembly.GRCh38, 1, "AARS1", "16", "AD", "NM_001001.2");
            VariantInterpretation aars2 = candidate(2, GenomeAssembly.GRCh38, 7, "AARS2", "57505", "AR", "NM_001002.3");
            ExplicitInput aars1Input = input(aars1, binding(aars1, "synthetic-aars1-ad", "HGNC:20", "MONDO:0013212", "AD"), null);
            ExplicitInput aars2Input = input(aars2, binding(aars2, "synthetic-aars2-ar", "HGNC:21022", "MONDO:0044970", "AR"), null);
            AssemblyResult publicBindings = assembler().assemble(analysis, List.of(aars1, aars2), 2, List.of(aars1Input, aars2Input));
            for (var row : publicBindings.candidates()) {
                ObjectNode context = ready(row.attempts().get(0));
                assertEquals("SYNTHETIC", context.path("dataClass").asText());
                assertTrue(row.attempts().get(0).binding().clingenReportLocator().startsWith("https://search.clinicalgenome.org/"));
            }
            results.set("publicBindings", mapper.valueToTree(publicBindings));
        }
        String output = System.getProperty("varvis.e4a1b.contextOutput");
        if (output != null && !output.isBlank()) mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(output).toFile(), results);
    }

    @Test
    void failuresStayInPlaceWithoutBackfillAndValidSiblingSurvives() throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation later = candidate(3, GenomeAssembly.GRCh38, 7, "SYNTHB", "102", "AR", "NM_001002.3");
        ExplicitInput valid = input(first, firstBinding(first), null);
        ExplicitInput invalid = input(first, binding(first, "hypothesis-a2", "HGNC:101", "MONDO:0000102", "AD"), null);
        invalid = copy(invalid, invalid.binding(), null, invalid.patient(), invalid.normalization());
        ExplicitInput laterInput = input(later, binding(later, "hypothesis-b", "HGNC:102", "MONDO:0000103", "AR"), null);
        AssemblyResult siblings = assembler().assemble(analysis, List.of(first, later), 1, List.of(invalid, valid, laterInput));
        assertEquals(List.of(first.getCandidateId()), siblings.candidateIds());
        blocked(siblings.candidates().get(0).attempts().get(0), "TRANSCRIPT_CHOICE_REQUIRED");
        ready(siblings.candidates().get(0).attempts().get(1));
        blocked(siblings.unmatchedInputs().get(0), "INPUT_OUTSIDE_SELECTED_TOP_N");
        AssemblyResult missing = assembler().assemble(analysis, List.of(first, later), 1, List.of(laterInput));
        blocked(missing.candidates().get(0).attempts().get(0), "EXPLICIT_INPUT_MISSING");
        assertEquals(1, missing.selectedCount());
        assertEquals(2, assembler().assemble(analysis, List.of(first, later), 20, List.of(valid, laterInput)).selectedCount());
    }

    @Test
    void sameAlleleRowsAndLegacySuggestionsNeverChangeIdentityOrOrder() throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation second = candidate(1, GenomeAssembly.GRCh38, 1, "SYNTHB", "102", "AR", "NM_001002.3");
        ExplicitInput one = input(first, firstBinding(first), null);
        ExplicitInput two = input(second, binding(second, "hypothesis-b", "HGNC:102", "MONDO:0000103", "AR"), null);
        AssemblyResult before = assembler().assemble(analysis, List.of(first, second), 2, List.of(one, two));
        first.applyAcmgEvidenceSuggestions(List.of(new AcmgEvidenceSuggestion("BA1", "STANDALONE", "SUGGESTED",
                "SYNTHETIC_LEGACY", "Synthetic legacy payload", true, "acmg-ready-v1", List.of())));
        AssemblyResult after = assembler().assemble(analysis, List.of(first, second), 2, List.of(one, two));
        assertEquals(mapper.valueToTree(before), mapper.valueToTree(after));
        assertEquals(2, after.candidates().size());
        ObjectNode a = ready(after.candidates().get(0).attempts().get(0));
        ObjectNode b = ready(after.candidates().get(1).attempts().get(0));
        assertEquals(a.at("/variant/alleleId"), b.at("/variant/alleleId"));
        assertNotEquals(a.path("contextId"), b.path("contextId"));
        assertEquals(1, first.getAcmgEvidenceSuggestions().size());
    }

    @Test
    void duplicateCandidateAndHypothesisIdsBlockWithoutMergingRows() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        ExplicitInput input = input(candidate, firstBinding(candidate), null);
        AssemblyResult duplicateRows = assembler().assemble(analysis, List.of(candidate, candidate), 2, List.of(input));
        assertEquals(List.of(candidate.getCandidateId(), candidate.getCandidateId()), duplicateRows.candidateIds());
        duplicateRows.candidates().forEach(row -> blocked(row.attempts().get(0), "CANDIDATE_ID_AMBIGUOUS"));
        AssemblyResult duplicateHypotheses = assembler().assemble(analysis, List.of(candidate), 1, List.of(input, input));
        duplicateHypotheses.candidates().get(0).attempts().forEach(attempt -> blocked(attempt, "HYPOTHESIS_ID_AMBIGUOUS"));
    }

    @Test
    void bindingMustBeReadyExactAndReproducibleFromItsOriginalProvenance() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        DiseaseContextBinding binding = firstBinding(candidate);
        ExplicitInput input = input(candidate, binding, null);
        blocked(attempt(candidate, copy(input, null, input.transcript(), input.patient(), input.normalization())), "BINDING_REQUIRED");
        DiseaseContextBinding absent = binding(candidate, "hypothesis-absent", "HGNC:101", "MONDO:9999999", "AD");
        blocked(attempt(candidate, copy(input, absent, input.transcript(), input.patient(), input.normalization())), "BINDING_NOT_READY");
        DiseaseContextBinding other = binding(candidate, "hypothesis-a2", "HGNC:101", "MONDO:0000102", "AD");
        blocked(attempt(candidate, copy(input, other, input.transcript(), input.patient(), input.normalization())), "BINDING_INPUT_IDENTITY_CONFLICT");
        DiseaseContextBinding changed = new DiseaseContextBinding(binding.candidateId(), binding.hypothesisId(), binding.hgncId(),
                binding.symbol(), binding.mondoId(), "Fabricated synthetic label", binding.normalizedMoi(),
                binding.clingenClassification(), binding.clingenClassificationDate(), binding.clingenGcep(),
                binding.clingenReportLocator(), binding.status(), binding.reasonCode(), binding.provenance());
        blocked(attempt(candidate, copy(input, changed, input.transcript(), input.patient(), input.normalization())), "BINDING_PROVENANCE_CONFLICT");
    }

    @Test
    void rejectedForgedBindingNeverEchoesNestedResourceOrRelationshipLocalPaths() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        DiseaseContextBinding binding = firstBinding(candidate);
        ExplicitInput input = input(candidate, binding, null);
        BindingProvenance provenance = binding.provenance();
        ObjectNode mane = (ObjectNode) provenance.maneResourceIdentity();
        mane.putObject("syntheticNested").putArray("localArtifacts").add("D:\\synthetic-private\\mane.json");
        ObjectNode clingen = (ObjectNode) provenance.clingenResourceIdentity();
        clingen.putObject("syntheticNested").put("localArtifact", "/synthetic-private/clingen.json");
        List<com.fasterxml.jackson.databind.JsonNode> relationships = provenance.relationshipRecords();
        ((ObjectNode) relationships.get(0)).put("reportLocator", "file:///synthetic-private/relationship.json");
        BindingProvenance forgedProvenance = new BindingProvenance(provenance.patientHypothesis(), mane, clingen,
                provenance.crosswalkRecords(), relationships, provenance.intakePolicyId(), provenance.intakePolicyVersion());
        DiseaseContextBinding forged = bindingWithProvenance(binding, forgedProvenance, "F:\\synthetic-private\\report.json");
        Attempt rejected = attempt(candidate, copy(input, forged, input.transcript(), input.patient(), input.normalization()));
        blocked(rejected, "BINDING_PROVENANCE_CONFLICT");
        String serialized = mapper.writeValueAsString(rejected);
        assertFalse(serialized.contains("synthetic-private"));
        assertTrue(serialized.contains("[LOCAL_PATH_REJECTED]"));
        assertEquals(binding.hypothesisId(), rejected.binding().hypothesisId());
        assertEquals(binding.candidateId(), rejected.binding().candidateId());

        DiseaseContextBinding noProvenance = bindingWithProvenance(binding, null, "F:/synthetic-private/no-provenance-report.json");
        Attempt missing = attempt(candidate, copy(input, noProvenance, input.transcript(), input.patient(), input.normalization()));
        blocked(missing, "BINDING_PROVENANCE_REQUIRED");
        assertEquals("[LOCAL_PATH_REJECTED]", missing.binding().clingenReportLocator());
        assertFalse(mapper.writeValueAsString(missing).contains("synthetic-private"));
    }

    @Test
    void nullCandidateAndNullAnnotationStayLocalAndUnmatchedInputKeepsSourceIdentity() throws Exception {
        VariantInterpretation good = firstCandidate();
        VariantInterpretation bad = candidate(1, GenomeAssembly.GRCh38, 7, "SYNTHB", "102", "AR", "NM_001002.3");
        ExplicitInput goodInput = input(good, firstBinding(good), null);
        ExplicitInput badInput = input(bad, binding(bad, "hypothesis-b", "HGNC:102", "MONDO:0000103", "AR"), null);
        bad.applyTranscriptAnnotations(Arrays.asList((TranscriptConsequence) null));
        ExplicitInput missingTarget = new ExplicitInput(null, goodInput.hypothesisId(), goodInput.binding(), goodInput.dataClass(),
                goodInput.transcript(), goodInput.patient(), goodInput.normalization());
        AssemblyResult result = assembler().assemble(analysis, Arrays.asList(null, bad, good), 3,
                Arrays.asList(missingTarget, badInput, goodInput));
        assertEquals(Arrays.asList(null, bad.getCandidateId(), good.getCandidateId()), result.candidateIds());
        assertEquals(3, result.selectedCount());
        blocked(result.candidates().get(0).attempts().get(0), "CANDIDATE_REQUIRED");
        blocked(result.candidates().get(1).attempts().get(0), "TRANSCRIPT_ANNOTATION_INVALID");
        ready(result.candidates().get(2).attempts().get(0));
        assertEquals(1, result.unmatchedInputs().size());
        Attempt unmatched = result.unmatchedInputs().get(0);
        blocked(unmatched, "INPUT_OUTSIDE_SELECTED_TOP_N");
        assertNull(unmatched.candidateId());
        assertEquals(goodInput.hypothesisId(), unmatched.hypothesisId());
        assertEquals(goodInput.patient().source(), unmatched.provenance().patient());
        assertEquals(goodInput.transcript().source(), unmatched.provenance().transcript());
        assertEquals(goodInput.normalization().source(), unmatched.provenance().normalization());
    }

    @Test
    void selectedTranscriptNeedsVersionSourceAndUniqueMatchingAnnotation() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        ExplicitInput input = input(candidate, firstBinding(candidate), null);
        TranscriptChoice choice = input.transcript();
        TranscriptChoice noVersion = new TranscriptChoice(choice.accession(), null, choice.selectionPolicy(), choice.annotationSource(), choice.annotationSourceVersion(), choice.source());
        blocked(attempt(candidate, copy(input, input.binding(), noVersion, input.patient(), input.normalization())), "TRANSCRIPT_VERSION_REQUIRED");
        TranscriptChoice noSource = new TranscriptChoice(choice.accession(), choice.version(), choice.selectionPolicy(), null, choice.annotationSourceVersion(), choice.source());
        blocked(attempt(candidate, copy(input, input.binding(), noSource, input.patient(), input.normalization())), "TRANSCRIPT_SOURCE_REQUIRED");
        TranscriptChoice wrongVersion = new TranscriptChoice(choice.accession(), 99, choice.selectionPolicy(), choice.annotationSource(), choice.annotationSourceVersion(), choice.source());
        blocked(attempt(candidate, copy(input, input.binding(), wrongVersion, input.patient(), input.normalization())), "TRANSCRIPT_SOURCE_CONFLICT");
        TranscriptConsequence exact = candidate.getTranscriptAnnotations().get(0);
        candidate.applyTranscriptAnnotations(List.of(exact, exact));
        blocked(attempt(candidate, input), "TRANSCRIPT_ANNOTATION_AMBIGUOUS");
        candidate.applyTranscriptAnnotations(List.of(annotation("SYNTHB", "NM_001001.2", "NM_001001.2:c.1A>G")));
        blocked(attempt(candidate, input), "TRANSCRIPT_ANNOTATION_NOT_MATCHED");
        candidate.applyTranscriptAnnotations(List.of(exact.withAnnotationMetadata(ANNOTATION_SOURCE, "UNAVAILABLE", ANNOTATION_VERSION, Instant.EPOCH)));
        blocked(attempt(candidate, input), "TRANSCRIPT_ANNOTATION_NOT_MATCHED");
        List<TranscriptConsequence> nullAnnotation = new ArrayList<>();
        nullAnnotation.add(null);
        candidate.applyTranscriptAnnotations(nullAnnotation);
        assertFalse(attempt(candidate, input).blockers().isEmpty());
    }

    @Test
    void independentlyVerifiedPatientClaimsMustAgreeAcrossCandidateInputs() throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation second = candidate(1, GenomeAssembly.GRCh38, 7, "SYNTHB", "102", "AR", "NM_001002.3");
        ExplicitInput firstInput = input(first, firstBinding(first), null);
        DiseaseContextBinding secondBinding = binding(second, "hypothesis-b", "HGNC:102", "MONDO:0000103", "AR");
        for (String field : List.of("role", "sex", "familyContextId")) {
            String value = "role".equals(field) ? "FAMILY_MEMBER" : "sex".equals(field) ? "MALE" : "family-context-synthetic-other";
            ExplicitInput changed = input(second, secondBinding, null,
                    records -> ((ObjectNode) records.path("patient")).put(field, value));
            PatientContext old = changed.patient();
            PatientContext patient = new PatientContext(old.patientContextId(), "role".equals(field) ? value : old.role(),
                    old.affectedStatus(), "sex".equals(field) ? value : old.sex(),
                    "familyContextId".equals(field) ? value : old.familyContextId(), old.source());
            changed = copy(changed, changed.binding(), changed.transcript(), patient, changed.normalization());
            AssemblyResult result = assembler().assemble(analysis, List.of(first, second), 2, List.of(firstInput, changed));
            result.candidates().forEach(row -> blocked(row.attempts().get(0), "PATIENT_CONTEXT_CROSS_INPUT_CONFLICT"));
        }
        ExplicitInput differentPatient = input(second, secondBinding, null, records -> records.forEach(record ->
                ((ObjectNode) record).put("patientContextId", "patient-context-synthetic-other")));
        PatientContext old = differentPatient.patient();
        PatientContext alias = new PatientContext("patient-context-synthetic-other", old.role(), old.affectedStatus(), old.sex(), null, old.source());
        differentPatient = copy(differentPatient, differentPatient.binding(), differentPatient.transcript(), alias, differentPatient.normalization());
        assembler().assemble(analysis, List.of(first, second), 2, List.of(firstInput, differentPatient)).candidates()
                .forEach(row -> blocked(row.attempts().get(0), "PATIENT_CONTEXT_CROSS_INPUT_CONFLICT"));
        VariantQcObservation observation = second.getObservation();
        VariantQcObservation otherSample = new VariantQcObservation(observation.getKey(), "synthetic-other-sample",
                observation.getGenotype(), observation.getDepth(), observation.getGenotypeQuality(), observation.getAlleleDepths(),
                observation.getAlleleBalance(), observation.getFilterStatus(), observation.getVariantType(), observation.getOriginalRecord());
        VariantInterpretation differentSample = new VariantInterpretation(second.getCandidateIdentity(), otherSample,
                second.getConsequences(), List.of(), second.getExomiserCandidateFeatures());
        differentSample.applyTranscriptAnnotations(second.getTranscriptAnnotations());
        ExplicitInput differentSampleInput = input(differentSample, secondBinding, null);
        assembler().assemble(analysis, List.of(first, differentSample), 2, List.of(firstInput, differentSampleInput)).candidates()
                .forEach(row -> blocked(row.attempts().get(0), "PATIENT_CONTEXT_CROSS_INPUT_CONFLICT"));
    }

    @Test
    void sameHypothesisAffectedStatusConflictBlocksButInvalidReceiptDoesNotPoisonItsSibling() throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation second = candidate(5, GenomeAssembly.GRCh38, 7, "SYNTHA", "101", "AD", "NM_001001.2");
        ExplicitInput firstInput = input(first, firstBinding(first), null);
        ExplicitInput invalidReceipt = input(second, firstBinding(second), null,
                records -> ((ObjectNode) records.path("patient")).put("affectedStatus", "UNAFFECTED"));
        AssemblyResult isolated = assembler().assemble(analysis, List.of(first, second), 2, List.of(firstInput, invalidReceipt));
        ready(isolated.candidates().get(0).attempts().get(0));
        blocked(isolated.candidates().get(1).attempts().get(0), "PATIENT_CONTEXT_CONFLICT");
        PatientContext old = invalidReceipt.patient();
        PatientContext unaffected = new PatientContext(old.patientContextId(), old.role(), "UNAFFECTED", old.sex(), null, old.source());
        ExplicitInput validButConflicting = copy(invalidReceipt, invalidReceipt.binding(), invalidReceipt.transcript(), unaffected, invalidReceipt.normalization());
        assembler().assemble(analysis, List.of(first, second), 2, List.of(firstInput, validButConflicting)).candidates()
                .forEach(row -> diseaseAffectedStatusBlocked(row.attempts().get(0)));
    }

    @Test
    void distinctDiseasesCanReuseLocalHypothesisIdWithDifferentAffectedStatusAndKeepIdentityAfterRenaming() throws Exception {
        AssemblyResult sharedId = differentDiseaseAffectedStatuses("shared-local-hypothesis", "shared-local-hypothesis");
        AssemblyResult renamed = differentDiseaseAffectedStatuses("renamed-hypothesis-a", "renamed-hypothesis-b");
        assertEquals(sharedId.candidateIds(), renamed.candidateIds());
        for (int index = 0; index < sharedId.candidates().size(); index++) {
            ObjectNode before = ready(sharedId.candidates().get(index).attempts().get(0));
            ObjectNode after = ready(renamed.candidates().get(index).attempts().get(0));
            assertEquals(before.path("contextId"), after.path("contextId"));
            assertEquals(before.at("/variant/alleleId"), after.at("/variant/alleleId"));
        }
        assertEquals("AFFECTED", sharedId.candidates().get(0).attempts().get(0).context().at("/patientContext/affectedStatus").asText());
        assertEquals("UNAFFECTED", sharedId.candidates().get(1).attempts().get(0).context().at("/patientContext/affectedStatus").asText());
        assertNotEquals(sharedId.candidates().get(0).attempts().get(0).context().at("/disease/mondoId"),
                sharedId.candidates().get(1).attempts().get(0).context().at("/disease/mondoId"));
    }

    @Test
    void sameDiseaseConflictBlocksDifferentLocalHypothesisIdsWhileOtherDiseaseSurvivesRenaming() throws Exception {
        AssemblyResult conflict = sameDiseaseConflictWithIndependentDisease("local-a", "local-b", "local-third");
        AssemblyResult renamed = sameDiseaseConflictWithIndependentDisease("renamed-shared", "renamed-shared", "renamed-third");
        assertEquals(conflict.candidateIds(), renamed.candidateIds());
        assertEquals(3, conflict.selectedCount());
        assertFalse(conflict.candidateOrderingChanged());
        for (int index = 0; index < 2; index++) {
            diseaseAffectedStatusBlocked(conflict.candidates().get(index).attempts().get(0));
            diseaseAffectedStatusBlocked(renamed.candidates().get(index).attempts().get(0));
        }
        ObjectNode unaffectedDisease = ready(conflict.candidates().get(2).attempts().get(0));
        ObjectNode renamedDisease = ready(renamed.candidates().get(2).attempts().get(0));
        assertEquals("MONDO:0000103", unaffectedDisease.at("/disease/mondoId").asText());
        assertEquals(unaffectedDisease.path("contextId"), renamedDisease.path("contextId"));
        assertEquals(unaffectedDisease.at("/variant/alleleId"), renamedDisease.at("/variant/alleleId"));

        String output = System.getProperty("varvis.e4a1br1.contextOutput");
        if (output != null && !output.isBlank()) {
            AssemblyResult independent = differentDiseaseAffectedStatuses("shared-local-hypothesis", "shared-local-hypothesis");
            AssemblyResult independentRenamed = differentDiseaseAffectedStatuses("renamed-hypothesis-a", "renamed-hypothesis-b");
            independent.candidates().forEach(row -> ready(row.attempts().get(0)));
            independentRenamed.candidates().forEach(row -> ready(row.attempts().get(0)));
            ObjectNode results = mapper.createObjectNode();
            results.set("differentDiseasesSharedLocalId", mapper.valueToTree(independent));
            results.set("differentDiseasesRenamedLocalIds", mapper.valueToTree(independentRenamed));
            results.set("sameDiseaseDifferentLocalIds", mapper.valueToTree(conflict));
            results.set("sameDiseaseRenamedLocalIds", mapper.valueToTree(renamed));
            mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(output).toFile(), results);
        }
    }

    private AssemblyResult differentDiseaseAffectedStatuses(String firstHypothesisId, String secondHypothesisId) throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation second = candidate(1, GenomeAssembly.GRCh38, 7, "SYNTHB", "102", "AR", "NM_001002.3");
        ExplicitInput firstInput = input(first, binding(first, firstHypothesisId, "HGNC:101", "MONDO:0000101", "AD"), null);
        ExplicitInput secondInput = inputWithAffectedStatus(second,
                binding(second, secondHypothesisId, "HGNC:102", "MONDO:0000103", "AR"), "UNAFFECTED");
        return assembler().assemble(analysis, List.of(first, second), 2, List.of(firstInput, secondInput));
    }

    private AssemblyResult sameDiseaseConflictWithIndependentDisease(String firstHypothesisId, String secondHypothesisId,
                                                                     String thirdHypothesisId) throws Exception {
        VariantInterpretation first = firstCandidate();
        VariantInterpretation second = candidate(5, GenomeAssembly.GRCh38, 7, "SYNTHA", "101", "AD", "NM_001001.2");
        VariantInterpretation third = candidate(1, GenomeAssembly.GRCh38, 8, "SYNTHB", "102", "AR", "NM_001002.3");
        ExplicitInput firstInput = input(first, binding(first, firstHypothesisId, "HGNC:101", "MONDO:0000101", "AD"), null);
        ExplicitInput secondInput = inputWithAffectedStatus(second,
                binding(second, secondHypothesisId, "HGNC:101", "MONDO:0000101", "AD"), "UNAFFECTED");
        ExplicitInput thirdInput = input(third, binding(third, thirdHypothesisId, "HGNC:102", "MONDO:0000103", "AR"), null);
        List<VariantInterpretation> ranked = List.of(first, second, third);
        List<ExplicitInput> inputs = List.of(firstInput, secondInput, thirdInput);
        AssemblyResult result = assembler().assemble(analysis, ranked, 3, inputs);
        for (int index = 0; index < ranked.size(); index++) {
            Attempt individual = attempt(ranked.get(index), inputs.get(index));
            ready(individual);
            Attempt batched = result.candidates().get(index).attempts().get(0);
            assertEquals(individual.binding(), batched.binding());
            assertEquals(individual.provenance(), batched.provenance());
        }
        AssemblyResult topTwo = assembler().assemble(analysis, ranked, 2, inputs);
        assertEquals(List.of(first.getCandidateId(), second.getCandidateId()), topTwo.candidateIds());
        assertEquals(2, topTwo.selectedCount());
        topTwo.candidates().forEach(row -> diseaseAffectedStatusBlocked(row.attempts().get(0)));
        assertEquals(1, topTwo.unmatchedInputs().size());
        assertEquals(third.getCandidateId(), topTwo.unmatchedInputs().get(0).candidateId());
        blocked(topTwo.unmatchedInputs().get(0), "INPUT_OUTSIDE_SELECTED_TOP_N");
        return result;
    }

    private ExplicitInput inputWithAffectedStatus(VariantInterpretation candidate, DiseaseContextBinding binding,
                                                  String affectedStatus) throws Exception {
        ExplicitInput input = input(candidate, binding, null,
                records -> ((ObjectNode) records.path("patient")).put("affectedStatus", affectedStatus));
        PatientContext old = input.patient();
        PatientContext patient = new PatientContext(old.patientContextId(), old.role(), affectedStatus, old.sex(), old.familyContextId(), old.source());
        return copy(input, binding, input.transcript(), patient, input.normalization());
    }

    private void diseaseAffectedStatusBlocked(Attempt attempt) {
        blocked(attempt, "PATIENT_DISEASE_AFFECTED_STATUS_CONFLICT");
        assertEquals("patient.affectedStatus", attempt.blockers().get(0).field());
    }

    @Test
    void explicitMultiallelicOriginalIndexSelectsTheTargetAlternate() throws Exception {
        VariantInterpretation candidate = candidate(4, GenomeAssembly.GRCh38, 1, "A", "G", "1|1|A|T,G|2",
                "SYNTHA", "101", "AD", "NM_001001.2");
        ExplicitInput input = input(candidate, firstBinding(candidate), null);
        assertEquals(2, input.normalization().selectedAltIndex());
        ObjectNode context = ready(attempt(candidate, input));
        assertEquals("G", context.at("/variant/inputAllele/alternate").asText());
        assertEquals("UNCHANGED", context.at("/variant/normalization/status").asText());
    }

    @Test
    void explicitSelectionIgnoresAnnotationOrderAndRejectsConflictingVersionedIdentities() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        ExplicitInput input = input(candidate, firstBinding(candidate), null);
        TranscriptConsequence selected = candidate.getTranscriptAnnotations().get(0);
        candidate.applyTranscriptAnnotations(List.of(annotation("SYNTHA", "NM_009999.1", "NM_009999.1:c.1A>G"), selected));
        assertEquals("NM_001001", ready(attempt(candidate, input)).at("/transcript/accession").asText());
        candidate.applyTranscriptAnnotations(List.of(annotation("SYNTHA", "NM_001001.2", "NM_009999.1:c.1A>G")));
        blocked(attempt(candidate, input), "TRANSCRIPT_IDENTITY_CONFLICT");
        ExplicitInput wrongGeneReceipt = input(candidate, firstBinding(candidate), null,
                records -> ((ObjectNode) records.path("transcript")).put("hgncId", "HGNC:102"));
        blocked(attempt(candidate, wrongGeneReceipt), "TRANSCRIPT_GENE_CONFLICT");
    }

    @Test
    void patientFieldsSampleAndHypothesisAssociationRequireExplicitMatchingSource() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        DiseaseContextBinding binding = firstBinding(candidate);
        ExplicitInput input = input(candidate, binding, null);
        blocked(attempt(candidate, copy(input, binding, input.transcript(), null, input.normalization())), "PATIENT_CONTEXT_REQUIRED");
        PatientContext missing = new PatientContext(input.patient().patientContextId(), "PROBAND", null, "UNKNOWN", null, input.patient().source());
        blocked(attempt(candidate, copy(input, binding, input.transcript(), missing, input.normalization())), "PATIENT_FIELD_MISSING");
        for (String field : List.of("analysisId", "candidateId", "hypothesisId", "patientContextId")) {
            ExplicitInput wrong = input(candidate, binding, null,
                    records -> ((ObjectNode) records.path("patient")).put(field, "synthetic-wrong-association"));
            blocked(attempt(candidate, wrong), "INPUT_ASSOCIATION_CONFLICT");
        }
        ExplicitInput wrongSample = input(candidate, binding, null,
                records -> ((ObjectNode) records.path("patient")).put("sampleId", "synthetic-other-sample"));
        blocked(attempt(candidate, wrongSample), "PATIENT_SAMPLE_ASSOCIATION_CONFLICT");
        ExplicitInput wrongDisease = input(candidate, binding, null,
                records -> ((ObjectNode) records.path("patient").path("hypothesis")).put("mondoId", "MONDO:0000102"));
        blocked(attempt(candidate, wrongDisease), "PATIENT_HYPOTHESIS_CONFLICT");
        ExplicitInput wrongFamily = input(candidate, binding, null,
                records -> ((ObjectNode) records.path("patient")).put("familyContextId", "family-context-synthetic-other"));
        blocked(attempt(candidate, wrongFamily), "PATIENT_CONTEXT_CONFLICT");
    }

    @Test
    void unchangedAndActuallyLeftAlignedAllelesKeepOriginalAndNormalizedCoordinates() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        ExplicitInput unchanged = input(candidate, firstBinding(candidate), null);
        assertEquals("UNCHANGED", ready(attempt(candidate, unchanged)).at("/variant/normalization/status").asText());
        VariantInterpretation deletion = candidate(4, GenomeAssembly.GRCh38, 1, "AA", "A", "1|3|AA|A|1",
                "SYNTHA", "101", "AD", "NM_001001.2");
        ExplicitInput normalized = input(deletion, firstBinding(deletion), null);
        ObjectNode context = ready(attempt(deletion, normalized));
        assertEquals("NORMALIZED", context.at("/variant/normalization/status").asText());
        assertEquals(3, context.at("/variant/inputAllele/position").asInt());
        assertEquals(1, context.at("/variant/normalizedAllele/position").asInt());
        assertEquals("AA", context.at("/variant/inputAllele/reference").asText());
        assertEquals(context.at("/variant/normalizedAllele"), context.at("/variant/evidenceQueryAllele"));
    }

    @Test
    void normalizationAndRefEvidenceCannotBeReplacedWithDeclarationsOrWrongAltIndex() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        ExplicitInput input = input(candidate, firstBinding(candidate), null);
        blocked(attempt(candidate, copy(input, input.binding(), input.transcript(), input.patient(), null)), "NORMALIZATION_PROOF_REQUIRED");
        NormalizationInput original = input.normalization();
        Allele wrongAllele = new Allele("GRCh38", "1", 1, "A", "T");
        NormalizationInput wrongNormalized = new NormalizationInput(original.originalAllele(), wrongAllele, 1,
                original.originalRecord(), original.method(), original.methodVersion(), original.source(), original.reference(), original.lineage());
        blocked(attempt(candidate, copy(input, input.binding(), input.transcript(), input.patient(), wrongNormalized)), "CANDIDATE_NORMALIZED_ALLELE_CONFLICT");
        Allele missingReference = new Allele("GRCh38", "1", 1, null, "G");
        NormalizationInput incomplete = new NormalizationInput(original.originalAllele(), missingReference, 1,
                original.originalRecord(), original.method(), original.methodVersion(), original.source(), original.reference(), original.lineage());
        blocked(attempt(candidate, copy(input, input.binding(), input.transcript(), input.patient(), incomplete)), "ALLELE_FIELDS_REQUIRED");
        NormalizationInput wrongAlt = new NormalizationInput(original.originalAllele(), original.normalizedAllele(), 2,
                original.originalRecord(), original.method(), original.methodVersion(), original.source(), original.reference(), original.lineage());
        blocked(attempt(candidate, copy(input, input.binding(), input.transcript(), input.patient(), wrongAlt)), "ORIGINAL_ALT_INDEX_CONFLICT");
        NormalizationInput noOriginal = new NormalizationInput(original.originalAllele(), original.normalizedAllele(), 1,
                null, original.method(), original.methodVersion(), original.source(), original.reference(), original.lineage());
        blocked(attempt(candidate, copy(input, input.binding(), input.transcript(), input.patient(), noOriginal)), "ORIGINAL_RECORD_REQUIRED");
        for (String field : List.of("inputObservedReference", "normalizedObservedReference", "refValidation")) {
            ExplicitInput wrong = input(candidate, firstBinding(candidate), null,
                    records -> ((ObjectNode) records.path("normalization")).put(field, "SYNTHETIC_WRONG"));
            blocked(attempt(candidate, wrong), "REF_VALIDATION_NOT_MATCH");
        }
        ExplicitInput noProof = input(candidate, firstBinding(candidate), null,
                records -> ((ObjectNode) records.path("normalization")).remove("refValidationRecordLocator"));
        blocked(attempt(candidate, noProof), "REF_VALIDATION_PROOF_REQUIRED");
        ExplicitInput failedRun = input(candidate, firstBinding(candidate), null,
                records -> ((ObjectNode) records.path("normalization")).put("executionStatus", "FAILED"));
        blocked(attempt(candidate, failedRun), "NORMALIZATION_PROOF_REQUIRED");
    }

    @Test
    void referenceRequiresVerifiedExpectedAndObservedHashAndExactSourceIdentity() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        replaceReference(record -> record.remove("expectedSha256"));
        blocked(attempt(candidate, input(candidate, firstBinding(candidate), null)), "REFERENCE_IDENTITY_INSUFFICIENT");
        replaceReference(record -> record.put("verificationStatus", "CONFIGURED_ONLY"));
        blocked(attempt(candidate, input(candidate, firstBinding(candidate), null)), "REFERENCE_IDENTITY_INSUFFICIENT");
        replaceReference(record -> record.put("expectedSha256", "0".repeat(64)));
        blocked(attempt(candidate, input(candidate, firstBinding(candidate), null)), "REFERENCE_IDENTITY_INSUFFICIENT");
        replaceReference(record -> { });
        ExplicitInput input = input(candidate, firstBinding(candidate), null);
        TranscriptChoice original = input.transcript();
        SourceRef unverified = new SourceRef(original.source().source(), original.source().version(), original.source().recordLocator(), "0".repeat(64));
        TranscriptChoice changed = new TranscriptChoice(original.accession(), original.version(), original.selectionPolicy(),
                original.annotationSource(), original.annotationSourceVersion(), unverified);
        blocked(attempt(candidate, copy(input, input.binding(), changed, input.patient(), input.normalization())), "SOURCE_RECORD_NOT_VERIFIED");
    }

    @Test
    void preRankingGrch37LiftOverNeverMasqueradesAsNativeGrch38AndKeepsProvenance() throws Exception {
        VariantInterpretation candidate = firstCandidate();
        ExplicitInput input = input(candidate, firstBinding(candidate), null);
        NormalizationInput normalized = input.normalization();
        ExplicitInput projected = liftedInput(input);
        AssemblyLineage lineage = projected.normalization().lineage();
        Attempt result = attempt(candidate, projected);
        blocked(result, "PRE_RANKING_LIFTOVER_UNSUPPORTED");
        assertEquals(lineage, result.provenance().assemblyLineage());
        assertEquals("GRCh37", result.provenance().assemblyLineage().originalInputAllele().assembly());
        assertEquals(12, result.provenance().assemblyLineage().originalInputAllele().position());
        assertEquals("synthetic-transform-grch37-to-grch38-v1", result.provenance().assemblyLineage().transformationId());
        NormalizationInput missing = new NormalizationInput(normalized.originalAllele(), normalized.normalizedAllele(),
                normalized.selectedAltIndex(), normalized.originalRecord(), normalized.method(), normalized.methodVersion(),
                normalized.source(), normalized.reference(), null);
        blocked(attempt(candidate, copy(input, input.binding(), input.transcript(), input.patient(), missing)), "ORIGINAL_ASSEMBLY_LINEAGE_REQUIRED");
    }

    private ExplicitInput liftedInput(ExplicitInput input) {
        NormalizationInput normalized = input.normalization();
        Allele original37 = new Allele("GRCh37", "1", 12, "G", "A");
        AssemblyLineage lineage = new AssemblyLineage("GRCh37", "MAPPED", original37,
                "synthetic-transform-grch37-to-grch38-v1", normalized.lineage().source());
        NormalizationInput projected = new NormalizationInput(normalized.originalAllele(), normalized.normalizedAllele(),
                normalized.selectedAltIndex(), normalized.originalRecord(), normalized.method(), normalized.methodVersion(),
                normalized.source(), normalized.reference(), lineage);
        return copy(input, input.binding(), input.transcript(), input.patient(), projected);
    }

    private DiseaseContextBinding bindingWithProvenance(DiseaseContextBinding binding, BindingProvenance provenance,
                                                         String reportLocator) {
        return new DiseaseContextBinding(binding.candidateId(), binding.hypothesisId(), binding.hgncId(), binding.symbol(),
                binding.mondoId(), binding.diseaseLabel(), binding.normalizedMoi(), binding.clingenClassification(),
                binding.clingenClassificationDate(), binding.clingenGcep(), reportLocator, binding.status(), binding.reasonCode(), provenance);
    }

    private ExplicitEvidenceContextAssembler assembler() {
        return new ExplicitEvidenceContextAssembler(mapper, resolver, sourceLedgers);
    }

    private Attempt attempt(VariantInterpretation candidate, ExplicitInput input) {
        return assembler().assemble(analysis, List.of(candidate), 1, List.of(input)).candidates().get(0).attempts().get(0);
    }

    private ObjectNode ready(Attempt attempt) {
        assertTrue(attempt.blockers().isEmpty(), () -> attempt.blockers().toString());
        assertNotNull(attempt.seed());
        assertNotNull(attempt.context());
        new RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(attempt.context());
        return attempt.context();
    }

    private void blocked(Attempt attempt, String reason) {
        assertNull(attempt.seed());
        assertNull(attempt.context());
        assertEquals(1, attempt.blockers().size());
        assertEquals("CONTEXT_NOT_READY", attempt.blockers().get(0).status());
        assertEquals(reason, attempt.blockers().get(0).reasonCode());
    }

    private ExplicitInput copy(ExplicitInput input, DiseaseContextBinding binding, TranscriptChoice transcript,
                                PatientContext patient, NormalizationInput normalization) {
        return new ExplicitInput(input.candidateId(), input.hypothesisId(), binding, input.dataClass(), transcript, patient, normalization);
    }

    private VariantInterpretation firstCandidate() {
        return candidate(2, GenomeAssembly.GRCh38, 1, "SYNTHA", "101", "AD", "NM_001001.2");
    }

    private DiseaseContextBinding firstBinding(VariantInterpretation candidate) {
        return binding(candidate, "hypothesis-a1", "HGNC:101", "MONDO:0000101", "AD");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"valid", "sourceReference", "proof", "lineage"})
    void convertedContextPreservesOriginalAssemblyAndRemainsBa1Only(String fault) throws Exception {
        var candidate = firstCandidate();
        var bound = binding(candidate, "converted-test", "HGNC:101", "MONDO:0000101", "AD");
        var base = input(candidate, bound, null);
        var target = base.normalization().normalizedAllele();
        var original = new Allele("GRCh37", target.contig(), target.position(), target.reference(), target.alternate());
        var records = mapper.createObjectNode();
        var sourceRef = mapper.createObjectNode().put("kind", "REFERENCE_IDENTITY").put("assembly", "GRCh37")
                .put("observedSha256", referenceSha256).put("expectedSha256", referenceSha256).put("verificationStatus", "VERIFIED");
        if (fault.equals("sourceReference")) sourceRef.put("observedSha256", "e".repeat(64));
        records.set("reference37", sourceRef);
        var references = ledger(records, "SYNTHETIC_CONVERSION_REFERENCE"); sourceLedgers.add(references);
        var evidence = mapper.createObjectNode().put("status", fault.equals("proof") ? "NOT_VERIFIED" : "VERIFIED")
                .put("originalReferenceSha256", referenceSha256).put("targetReferenceSha256", referenceSha256);
        var norm = associated("NORMALIZATION", candidate, bound, base.patient().patientContextId())
                .put("originalRecord", base.normalization().originalRecord()).put("selectedAltIndex", 1)
                .put("method", ConvertedAlleleSource.METHOD).put("methodVersion", ConvertedAlleleSource.VERSION)
                .put("executionStatus", "SUCCEEDED").put("refValidation", "MATCH");
        norm.set("originalAllele", mapper.valueToTree(original)); norm.set("normalizedAllele", mapper.valueToTree(target));
        norm.set("reference", mapper.valueToTree(base.normalization().reference()));
        norm.set("originalReference", mapper.valueToTree(references.reference("reference37"))); norm.set("conversionEvidence", evidence);
        var lineageRecord = associated("ASSEMBLY_LINEAGE", candidate, bound, base.patient().patientContextId())
                .put("originalInputAssembly", "GRCh37").put("preRankingMappingStatus", "MAPPED")
                .put("transformationId", fault.equals("lineage") ? "wrong" : "SYNTHETIC_MAPPING");
        lineageRecord.set("originalInputAllele", mapper.valueToTree(original)); lineageRecord.set("conversionEvidence", evidence);
        var linkage = ledger(mapper.createObjectNode().set("normalization", norm), "SYNTHETIC_CONVERSION_NORMALIZATION");
        var lineageLedger = ledger(mapper.createObjectNode().set("lineage", lineageRecord), "SYNTHETIC_CONVERSION_LINEAGE");
        sourceLedgers.add(linkage); sourceLedgers.add(lineageLedger);
        var lineage = new AssemblyLineage("GRCh37", "MAPPED", original, "SYNTHETIC_MAPPING", lineageLedger.reference("lineage"));
        var normalization = new NormalizationInput(original, target, 1, base.normalization().originalRecord(),
                ConvertedAlleleSource.METHOD, ConvertedAlleleSource.VERSION, linkage.reference("normalization"), base.normalization().reference(), lineage);
        var converted = new ExplicitInput(base.candidateId(), base.hypothesisId(), bound, base.dataClass(), base.transcript(), base.patient(), normalization);
        var result = new ExplicitEvidenceContextAssembler(mapper, resolver, sourceLedgers, true)
                .assemble(analysis, List.of(candidate), 1, List.of(converted));
        var attempt = result.candidates().get(0).attempts().get(0);
        if (fault.equals("valid")) {
            assertNotNull(attempt.context(), mapper.valueToTree(attempt).toPrettyString());
            assertEquals("GRCh37", attempt.context().at("/variant/normalizedAllele/assembly").asText());
            assertEquals("GRCh38", attempt.context().at("/variant/evidenceQueryAllele/assembly").asText());
            assertEquals("MAPPED", attempt.context().at("/variant/assemblyLineage/mappingStatus").asText());
            assertNull(assembler().assemble(analysis, List.of(candidate), 1, List.of(converted)).candidates().get(0).attempts().get(0).context());
        } else assertNull(attempt.context());
    }

    private ExplicitInput input(VariantInterpretation candidate, DiseaseContextBinding binding, String family) throws Exception {
        return input(candidate, binding, family, records -> { });
    }

    private ExplicitInput input(VariantInterpretation candidate, DiseaseContextBinding binding, String family,
                                 Consumer<ObjectNode> mutation) throws Exception {
        String patientId = "patient-context-synthetic-e4a1b-proband";
        var mapping = candidate.getObservation().normalizationMapping();
        NormalizedVariantKey key = candidate.getObservation().getKey();
        Allele normalized = new Allele(key.getGenomeAssembly(), key.getChromosome(), key.getPosition(), key.getReference(), key.getAlternate());
        Allele original = new Allele(key.getGenomeAssembly(), mapping.getRawChromosome(), mapping.getRawPosition(),
                mapping.getRawReference(), mapping.getRawAlternates().get(mapping.getRawAlternateIndex() - 1));
        String sequence = String.join("", Files.readAllLines(referenceFile, StandardCharsets.US_ASCII).subList(1, 2));
        String originalHaplotype = sequence.substring(0, original.position() - 1) + original.alternate()
                + sequence.substring(original.position() - 1 + original.reference().length());
        String normalizedHaplotype = sequence.substring(0, normalized.position() - 1) + normalized.alternate()
                + sequence.substring(normalized.position() - 1 + normalized.reference().length());
        assertEquals(originalHaplotype, normalizedHaplotype,
                "Explicit synthetic allele pairs must produce the same haplotype from the actual tiny FASTA");
        String versionedTranscript = candidate.getConsequences().get(0).getVersionedTranscriptId();
        int separator = versionedTranscript.lastIndexOf('.');
        String accession = versionedTranscript.substring(0, separator);
        int version = Integer.parseInt(versionedTranscript.substring(separator + 1));
        SourceRef reference = referenceLedger.reference("reference");
        ObjectNode records = mapper.createObjectNode();
        ObjectNode patient = associated("PATIENT_CONTEXT", candidate, binding, patientId);
        patient.put("sampleId", candidate.getObservation().getSampleId()).put("role", "PROBAND")
                .put("affectedStatus", "AFFECTED").put("sex", "UNKNOWN");
        if (family != null) patient.put("familyContextId", family);
        patient.putObject("hypothesis").put("hgncId", binding.hgncId()).put("mondoId", binding.mondoId())
                .put("moi", binding.normalizedMoi()).set("provenance", mapper.valueToTree(binding.provenance().patientHypothesis().provenance()));
        records.set("patient", patient);
        records.set("transcript", associated("TRANSCRIPT_CHOICE", candidate, binding, patientId)
                .put("accession", accession).put("version", version).put("selectionPolicy", "SYNTHETIC_EXPLICIT_SELECTION")
                .put("annotationSource", ANNOTATION_SOURCE).put("annotationSourceVersion", ANNOTATION_VERSION)
                .put("hgncId", binding.hgncId()).put("geneSymbol", binding.symbol())
                .put("annotationRecordLocator", "synthetic-annotation/" + versionedTranscript));
        records.set("lineage", associated("ASSEMBLY_LINEAGE", candidate, binding, patientId)
                .put("originalInputAssembly", key.getGenomeAssembly()).put("preRankingMappingStatus", "NATIVE")
                .set("originalInputAllele", mapper.valueToTree(original)));
        ObjectNode normalization = associated("NORMALIZATION", candidate, binding, patientId)
                .put("originalRecord", candidate.getObservation().getOriginalRecord()).put("selectedAltIndex", mapping.getRawAlternateIndex())
                .put("method", "SYNTHETIC_NORMALIZATION_FIXTURE").put("methodVersion", "synthetic-normalization-fixture-v1")
                .put("executionStatus", "SUCCEEDED").put("status", "IDENTITY".equals(mapping.getStatus()) ? "UNCHANGED" : "NORMALIZED")
                .put("refValidation", "MATCH").put("inputObservedReference", observedReference(original))
                .put("normalizedObservedReference", observedReference(normalized))
                .put("refValidationMethod", "SYNTHETIC_FASTA_SUBSEQUENCE_COMPARISON")
                .put("refValidationVersion", "synthetic-ref-check-v1").put("refValidationRecordLocator", "synthetic-reference/ref-check");
        normalization.set("originalAllele", mapper.valueToTree(original));
        normalization.set("normalizedAllele", mapper.valueToTree(normalized));
        normalization.set("reference", mapper.valueToTree(reference));
        records.set("normalization", normalization);
        mutation.accept(records);
        ExplicitContextSourceLedger ledger = ledger(records, "SYNTHETIC_EXPLICIT_INPUTS_" + (++sourceOrdinal));
        sourceLedgers.add(ledger);
        TranscriptChoice choice = new TranscriptChoice(accession, version, "SYNTHETIC_EXPLICIT_SELECTION", ANNOTATION_SOURCE,
                ANNOTATION_VERSION, ledger.reference("transcript"));
        PatientContext projection = new PatientContext(patientId, "PROBAND", "AFFECTED", "UNKNOWN", family, ledger.reference("patient"));
        AssemblyLineage lineage = new AssemblyLineage(key.getGenomeAssembly(), "NATIVE", original, null, ledger.reference("lineage"));
        NormalizationInput proof = new NormalizationInput(original, normalized, mapping.getRawAlternateIndex(),
                candidate.getObservation().getOriginalRecord(), "SYNTHETIC_NORMALIZATION_FIXTURE", "synthetic-normalization-fixture-v1",
                ledger.reference("normalization"), reference, lineage);
        return new ExplicitInput(candidate.getCandidateId(), binding.hypothesisId(), binding, "SYNTHETIC", choice, projection, proof);
    }

    private ObjectNode associated(String kind, VariantInterpretation candidate, DiseaseContextBinding binding, String patientId) {
        return mapper.createObjectNode().put("kind", kind).put("analysisId", analysis.getAnalysisId())
                .put("candidateId", candidate.getCandidateId()).put("hypothesisId", binding.hypothesisId()).put("patientContextId", patientId);
    }

    private String observedReference(Allele allele) throws Exception {
        String sequence = String.join("", Files.readAllLines(referenceFile, StandardCharsets.US_ASCII).subList(1, 2));
        String observed = sequence.substring(allele.position() - 1, allele.position() - 1 + allele.reference().length());
        assertEquals(allele.reference(), observed, "Synthetic receipt must use a REF observed from its actual FASTA bytes");
        return observed;
    }

    private void replaceReference(Consumer<ObjectNode> mutation) throws Exception {
        if (referenceLedger != null) sourceLedgers.remove(referenceLedger);
        ObjectNode reference = mapper.createObjectNode().put("kind", "REFERENCE_IDENTITY").put("assembly", "GRCh38")
                .put("observedSha256", sha256(Files.readAllBytes(referenceFile))).put("expectedSha256", referenceSha256)
                .put("verificationStatus", "VERIFIED").put("verificationMethod", "SHA-256")
                .put("verificationVersion", "synthetic-reference-check-v1").put("verificationRecordLocator", "synthetic-reference/check")
                .put("referenceArtifactLocator", "synthetic-reference.fa");
        mutation.accept(reference);
        referenceLedger = ledger(mapper.createObjectNode().set("reference", reference), "SYNTHETIC_REFERENCE_" + (++sourceOrdinal));
        sourceLedgers.add(referenceLedger);
    }

    private ExplicitContextSourceLedger ledger(ObjectNode records, String source) throws Exception {
        ObjectNode envelope = mapper.createObjectNode().put("source", source).put("version", "synthetic-receipts-v1")
                .put("dataClass", "SYNTHETIC").set("records", records);
        byte[] bytes = mapper.writeValueAsBytes(envelope);
        Path path = temporary.resolve(source + ".json");
        Files.write(path, bytes);
        return ExplicitContextSourceLedger.load(mapper, path, sha256(Files.readAllBytes(path)));
    }

    private VariantInterpretation candidate(int ordinal, GenomeAssembly assembly, int position,
                                            String symbol, String ncbi, String moi, String transcript) {
        String reference = REFERENCE_SEQUENCE.substring(position - 1, position);
        String alternate = "A".equals(reference) ? "G" : "A";
        return candidate(ordinal, assembly, position, reference, alternate,
                "1|" + position + "|" + reference + "|" + alternate + "|1", symbol, ncbi, moi, transcript);
    }

    private VariantInterpretation candidate(int ordinal, GenomeAssembly assembly, int position, String reference,
                                            String alternate, String originalRecord, String symbol,
                                            String ncbi, String moi, String transcript) {
        NormalizedVariantKey key = NormalizedVariantKey.canonical(assembly, "1", position, reference, alternate);
        CandidateIdentity identity = CandidateIdentity.exomiser(key.canonicalId(), ordinal, symbol, moi, ordinal);
        VariantQcObservation observation = new VariantQcObservation(key, "synthetic-proband", "0/1",
                30, 60, "15,15", 0.5d, "PASS", reference.length() == alternate.length() ? "SNV" : "INDEL", originalRecord);
        ExomiserCandidateFeatures features = new ExomiserCandidateFeatures(
                ExomiserCandidateFeatures.CURRENT_SCHEMA_VERSION, "15.1.0", symbol, ncbi, moi,
                "AR".equals(moi) ? "AUTOSOMAL_RECESSIVE" : "AUTOSOMAL_DOMINANT",
                ordinal, ExomiserCandidateFeatures.GENE_MOI_GROUP_RANK_SEMANTICS, "synthetic-candidate-" + ordinal,
                0.9, 0.8, 0.7, 0.6, "MISSENSE_VARIANT", null, true,
                "synthetic-variants.tsv", "synthetic-genes.tsv", ordinal, ordinal,
                "SYNTHETIC_EXACT_GENE_MOI", "EXOMISER_VARIANT_SCORE", List.of(), List.of(), Map.of());
        TranscriptConsequence annotation = annotation(symbol, transcript, transcript + ":c.1A>G");
        VariantInterpretation candidate = new VariantInterpretation(identity, observation, List.of(annotation), List.of(), features);
        candidate.applyTranscriptAnnotations(List.of(annotation));
        return candidate;
    }

    private TranscriptConsequence annotation(String symbol, String transcript, String hgvsc) {
        return new TranscriptConsequence(symbol, "ENSG00000000101", transcript, hgvsc,
                "ENSP00000000101.1:p.Lys1Arg", "missense_variant")
                .withAnnotationMetadata(ANNOTATION_SOURCE, "COMPLETED", ANNOTATION_VERSION, Instant.EPOCH);
    }

    private DiseaseContextBinding binding(VariantInterpretation candidate, String hypothesisId,
                                          String hgnc, String mondo, String moi) {
        PatientDiseaseHypothesis hypothesis = new PatientDiseaseHypothesis(candidate.getCandidateId(), hypothesisId,
                hgnc, mondo, moi, new HypothesisProvenance("SYNTHETIC_PATIENT_HYPOTHESIS", "e4-a1b-test-v1",
                "synthetic-patient/hypotheses/" + hypothesisId));
        return resolver.resolve(candidate, List.of(hypothesis)).bindings().get(0);
    }

    private PatientDiseaseContextBindingResolver writeBindingResources() throws Exception {
        ArrayNode crosswalk = mapper.createArrayNode()
                .add(crosswalk("101", "HGNC:101", "SYNTHA", 2))
                .add(crosswalk("102", "HGNC:102", "SYNTHB", 3));
        ArrayNode catalog = mapper.createArrayNode()
                .add(relationship("HGNC:101", "SYNTHA", "MONDO:0000101", "AD", 7))
                .add(relationship("HGNC:101", "SYNTHA", "MONDO:0000102", "AD", 8))
                .add(relationship("HGNC:102", "SYNTHB", "MONDO:0000103", "AR", 9));
        ObjectNode manifest = mapper.createObjectNode().put("profileId", "PATIENT_DISEASE_BINDING_INTAKE")
                .put("profileVersion", "1.0.0").put("parserVersion", "1.0.0")
                .put("testDataClass", "SYNTHETIC_RESOURCE_ENVELOPE_NOT_DOWNLOADED");
        manifest.set("intakePolicy", mapper.readTree("""
                {"policyId":"PATIENT_DISEASE_BINDING_INTAKE","version":"1.0.0",
                 "readyClassifications":["Definitive","Strong","Moderate"],
                 "reviewRequiredClassifications":["Limited"],
                 "notReadyClassifications":["Disputed","Refuted","No Known Disease Relationship",""],
                 "catalogMoiMappings":{"AD":"AUTOSOMAL_DOMINANT","AR":"AUTOSOMAL_RECESSIVE"},
                 "unmappedCatalogMoi":["XL","SD","UD","MT"],
                 "claim":"VARVIS_BINDING_INTAKE_ONLY_NOT_PATIENT_DIAGNOSIS"}
                """));
        ObjectNode resources = manifest.putObject("resources");
        resources.set("mane", resource("MANE_GRCh38_SUMMARY", "1.5", crosswalk, "mane-crosswalk.json"));
        resources.set("clingen", resource("CLINGEN_GENE_DISEASE_VALIDITY", "synthetic-2026-09-05", catalog,
                "clingen-catalog.json"));
        mapper.writeValue(temporary.resolve("manifest.json").toFile(), manifest);
        return PatientDiseaseContextBindingResolver.load(mapper, temporary.resolve("manifest.json"),
                temporary.resolve("mane-crosswalk.json"), temporary.resolve("clingen-catalog.json"));
    }

    private ObjectNode resource(String id, String version, ArrayNode rows, String filename) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(rows);
        Files.write(temporary.resolve(filename), bytes);
        ObjectNode resource = mapper.createObjectNode().put("resourceId", id).put("resourceVersion", version)
                .put("sourceUrl", "MANE_GRCh38_SUMMARY".equals(id)
                        ? "https://ftp.ncbi.nlm.nih.gov/refseq/MANE/MANE_human/release_1.5/MANE.GRCh38.v1.5.summary.txt.gz"
                        : "https://search.clinicalgenome.org/kb/gene-validity/download")
                .put("retrievedAt", "2026-09-05T00:00:00Z").put("fileDate", "2026-09-05")
                .put("bytes", bytes.length).put("rawSha256", sha256(bytes))
                .put("headerSha256", sha256("SYNTHETIC".getBytes(StandardCharsets.US_ASCII)))
                .put("parserVersion", "1.0.0").put("sourceCount", rows.size()).put("acceptedCount", rows.size())
                .put("rejectedCount", 0).put("filterPolicy", "SYNTHETIC_TEST_ONLY")
                .put("derivedSha256", sha256(bytes)).put("derivedRecordCount", rows.size());
        if ("MANE_GRCh38_SUMMARY".equals(id)) { resource.put("assembly", "GRCh38"); }
        return resource;
    }

    private ObjectNode crosswalk(String ncbi, String hgnc, String symbol, int sourceRow) {
        ObjectNode row = mapper.createObjectNode().put("ncbiGeneId", ncbi).put("hgncId", hgnc).put("symbol", symbol);
        row.putArray("sourceRows").add(sourceRow);
        return row;
    }

    private ObjectNode relationship(String hgnc, String symbol, String mondo, String moi, int sourceRow) {
        return mapper.createObjectNode().put("hgncId", hgnc).put("symbol", symbol).put("mondoId", mondo)
                .put("diseaseLabel", "Synthetic disease " + mondo).put("moi", moi).put("classification", "Definitive")
                .put("classificationDate", "2026-09-05").put("gcep", "SYNTHETIC_GCEP")
                .put("reportLocator", "https://example.invalid/synthetic-report/" + sourceRow)
                .put("sourceRow", sourceRow);
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
