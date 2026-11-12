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
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture;
import org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.Run;
import org.nbscn.varvis.variantanalysis.provider.AlphaMissenseProvider;
import org.nbscn.varvis.variantanalysis.provider.ExplicitAlphaMissenseDispatcher;
import org.nbscn.varvis.variantanalysis.provider.VepJsonOutputParser;
import org.nbscn.varvis.variantanalysis.provider.ReviewedRuleContextPreparation;
import org.nbscn.varvis.variantanalysis.provider.ReviewedRuleContextPreparationTest;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitCaseInputCoordinator.*;
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

/** New synthetic declarations; only accepted public binding rows, raw VEP and checksum receipts are reused. */
class ExplicitCaseInputCoordinatorTest {
    private static final Path DOCS = Path.of("../docs/execution");
    private static final String ANALYSIS_ID = "analysis-synthetic-e4-a3c-explicit-case";
    private static final String HBB_VCF = "11\t5227002\tpublic-hbb-compatible-e7v\tT\tA\t.\tPASS\t.\tGT\t.";
    private static final String GJB2_VCF = "13\t20189473\tpublic-gjb2-20189473-ct\tC\tT\t.\tPASS\t.\tGT\t.";
    private static final String HBB_TSV = "1\t11\t5227002\tT\tA\tHBB\t3043\tAR\t0.8";
    private static final String GJB2_TSV = "2\t13\t20189473\tC\tT\tGJB2\t2706\tAR\t0.7";
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnalysisContext analysis = new AnalysisContext(ANALYSIS_ID, GenomeAssembly.GRCh38, AnalysisMode.WES);
    final List<ExplicitContextSourceLedger> sources = new ArrayList<>();
    private final ArrayNode exportedSources = mapper.createArrayNode();
    private ObjectNode accepted;
    PatientDiseaseContextBindingResolver resolver;
    Map<String, List<TranscriptConsequence>> annotations;
    SourceRef reference;
    private int runOrdinal;
    JsonNode choices;

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
        choices = mapper.readTree(Files.readAllBytes(Path.of("src/test/resources/e4-a2b/hbb-gjb2-explicit-compatible-v1.json")));
    }

    @ParameterizedTest @ValueSource(strings = {"valid", "missingTranscript", "populationOnly", "missingCapability", "otherCapability", "moiConflict"})
    void ordinaryRecommendationConfirmationReachesRealContextAssemblyWithoutPredictionCalls(String variant) throws Exception {
        Run run = tinyRun(); var ranked = annotate(run); var candidate = ranked.get(0);
        var base = declaration(run, ranked); var original = base.hypotheses().get(0); var oldTx = original.transcript();
        var capability = new ReviewedRuleContextPreparation(mapper, ReviewedRuleContextPreparationTest.binding(mapper));
        var recommendations = new AcmgRecommendations(mapper, c -> new AlphaMissenseProvider.IdentityLookup(
                "UNAVAILABLE", "SYNTHETIC_IDENTITY_ABSENT", null, null, List.of()), capability);
        var current = recommendations.recommend(ANALYSIS_ID, candidate, resolver.referenceOptions(mapper, candidate), "f".repeat(64));
        JsonNode annotation = null, relation = null;
        for (var a : current.path("annotations")) if ((oldTx.accession() + "." + oldTx.version()).equals(a.path("versionedTranscriptId").asText())) annotation = a;
        for (var r : current.path("relationships")) if (original.hypothesis().mondoId().equals(r.path("mondoId").asText())) relation = r;
        assertNotNull(annotation); assertNotNull(relation);
        assertTrue(annotation.path("ruleContextReady").asBoolean(), current.toPrettyString());
        var selection = new AcmgRecommendations.Selection(candidate.getCandidateId(), current.path("recommendationId").asText(),
                current.path("policyVersion").asText(), current.path("policyHash").asText(), null,
                relation.path("stableRelationshipKey").asText(), annotation.path("annotationId").asText());
        var provenance = source("recommendations/" + selection.recommendationId() + "/selection");
        var hypothesis = new PatientDiseaseHypothesis(candidate.getCandidateId(), current.path("hypothesisId").asText(),
                relation.path("hgncId").asText(), relation.path("mondoId").asText(), relation.path("moi").asText(), provenance);
        var tx = new TranscriptSelection(oldTx.accession(), oldTx.version(), capability.selectionPolicy(), oldTx.annotationSource(),
                oldTx.annotationSourceVersion(), provenance, "User confirms exact rule context; each criterion still requires independent evidence.");
        recommendations.verify(selection, current, List.of(new HypothesisInput(hypothesis, "UNKNOWN", tx)));
        if ("missingTranscript".equals(variant)) tx = null;
        if ("populationOnly".equals(variant)) tx = new TranscriptSelection(oldTx.accession(), oldTx.version(), AcmgRecommendations.PM2_CONTEXT_SELECTION,
                oldTx.annotationSource(), oldTx.annotationSourceVersion(), provenance, "Population-only confirmation remains limited.");
        if ("moiConflict".equals(variant)) hypothesis = new PatientDiseaseHypothesis(candidate.getCandidateId(), hypothesis.hypothesisId(),
                hypothesis.hgncId(), hypothesis.mondoId(), "AD", provenance);
        var provider = new AlphaMissenseProvider(new VariantAnalysisProperties()) {
            @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotateSelectedTranscripts(List<VariantInterpretation> variants,
                    AnalysisContext context, Map<NormalizedVariantKey, TranscriptConsequence> selected) { throw new AssertionError("No prediction call during context preparation"); }
        };
        if ("otherCapability".equals(variant)) capability = new ReviewedRuleContextPreparation(mapper,
                ReviewedRuleContextPreparationTest.binding(mapper).put("bundleSha256", "c".repeat(64)));
        var dispatcher = "missingCapability".equals(variant) ? new ExplicitAlphaMissenseDispatcher(mapper, provider)
                : new ExplicitAlphaMissenseDispatcher(mapper, provider, () -> { throw new AssertionError("No SpliceAI"); },
                        () -> { throw new AssertionError("No REVEL"); }, capability);
        var referenceInput = tinyReference(r -> { });
        var result = new ExplicitCaseInputCoordinator(mapper, resolver, referenceInput.producer(), sources, dispatcher)
                .dispatch(run.job(), run.conversion(), run.execution(), analysis, ranked, 1,
                        List.of(withEntries(base, List.of(new HypothesisInput(hypothesis, "UNKNOWN", tx)))));
        var attempt = result.adapterResult().dispatch().candidates().get(0).attempts().get(0);
        assertEquals(0, result.adapterResult().dispatch().providerCalls());
        assertEquals(0, result.adapterResult().dispatch().evaluatorCalls());
        if ("valid".equals(variant)) {
            assertNotNull(attempt.context(), mapper.valueToTree(result).toPrettyString());
            assertTrue(attempt.transcriptInput().path("ruleContextConfirmed").asBoolean());
            assertEquals("REVIEWED_RULE_CONTEXT", attempt.transcriptInput().path("predictionPath").asText());
            assertFalse(attempt.transcriptInput().path("predictionCompatibilityClaimed").asBoolean(true));
            assertEquals(capability.selectionPolicy(), attempt.context().at("/transcript/selectionPolicy").asText());
        } else assertTrue(attempt.context() == null || "PM2_CONTEXT_ONLY".equals(attempt.transcriptInput().path("predictionPath").asText()));
    }

    @Test
    void newDeclarationsGenerateReceiptsAndPreservePrivacyAndOldEntryCompatibility() throws Exception {
        Run run = tinyRun();
        var ranked = annotate(run);
        var result = invoke(run, ranked, 2, List.of(declaration(run, ranked)), tinyReference(r -> { }).producer(), false);
        assertEquals(2, result.adapterResult().dispatch().providerCalls());
        assertEquals(2, result.adapterResult().dispatch().evaluatorCalls());
        assertEquals(2, result.production().provenance().path("referenceQueryAttempts").asInt());
        for (var outcome : result.declarations()) {
            assertNull(outcome.blocker());
            JsonNode record = resolve(result, outcome.declaration());
            assertEquals(ExplicitCaseInputCoordinator.CLAIM, record.path("claim").asText());
            assertEquals("UNKNOWN", record.at("/patient/sex").asText());
            assertEquals("AFFECTED", record.at("/input/affectedStatus").asText());
            assertFalse(record.has("confirmedAt"));
            assertTrue(record.path("execution").path("liveExecutionMatched").asBoolean());
        }
        String publicJson = mapper.copy().findAndRegisterModules().writeValueAsString(result);
        for (String secret : List.of("synthetic-proband", "rawJson", "selectionReason", "receivedAt", temporary.toString()))
            assertFalse(publicJson.contains(secret), secret);
        assertFalse(mapper.copy().findAndRegisterModules().valueToTree(run.execution()).has("inputSource"));
        var compatible = new CandidateAcmgInputAdapter(mapper, resolver, result.sources(), dispatcher(false))
                .dispatch(run.job(), run.conversion(), run.execution(), analysis, ranked, 2, result.production().inputs());
        for (int index = 0; index < 2; index++) {
            var actual = result.adapterResult().dispatch().candidates().get(index).attempts().get(0);
            var old = compatible.dispatch().candidates().get(index).attempts().get(0);
            assertEquals(actual.context(), old.context());
            assertEquals(actual.selection(), old.selection());
            assertFalse(actual.context().path("patientContext").has("familyContextId"));
        }
        export("engineering", run, result, false);
    }

    @ParameterizedTest(name = "explicit intake rejects {0}")
    @ValueSource(strings = {"ANALYSIS", "EXECUTION", "SAMPLE", "PATIENT", "SEX_MISSING", "ROLE", "PATIENT_SOURCE",
            "AFFECTED_MISSING", "AFFECTED_INVALID", "HYPOTHESIS_SOURCE", "TX_MISSING", "TX_VERSION", "TX_SOURCE",
            "TX_REASON", "ANNOTATION_SOURCE", "ANNOTATION_VERSION", "ANNOTATION_AMBIGUOUS", "NOT_SELECTED", "LOCAL_SOURCE"})
    void invalidDeclarationsDoNotPolluteValidSibling(String failure) throws Exception {
        Run run = tinyRun();
        var ranked = annotate(run);
        var valid = declaration(run, ranked);
        var patient = valid.patient();
        var entry = valid.hypotheses().get(0);
        var tx = entry.transcript();
        var hypothesis = entry.hypothesis();
        String expected;
        String analysisId = valid.analysisId(), sample = valid.sampleId(), affected = entry.affectedStatus();
        var witness = valid.executionSource();
        switch (failure) {
            case "ANALYSIS" -> { analysisId = "wrong-analysis"; expected = "DECLARATION_ANALYSIS_CONFLICT"; }
            case "EXECUTION" -> { witness = tinyRun().execution().getInputSource(); expected = "DECLARATION_EXECUTION_CONFLICT"; }
            case "SAMPLE" -> { sample = "wrong-sample"; expected = "DECLARATION_SAMPLE_CONFLICT"; }
            case "PATIENT" -> { patient = null; expected = "PATIENT_DECLARATION_REQUIRED"; }
            case "SEX_MISSING" -> { patient = patient(null, "PROBAND", patient.source()); expected = "PATIENT_FIELD_MISSING"; }
            case "ROLE" -> { patient = patient("UNKNOWN", "FAMILY_MEMBER", patient.source()); expected = "SINGLE_PROBAND_SCOPE_REQUIRED"; }
            case "PATIENT_SOURCE" -> { patient = patient("UNKNOWN", "PROBAND", null); expected = "PATIENT_DECLARATION_SOURCE_REQUIRED"; }
            case "AFFECTED_MISSING" -> { affected = null; expected = "DISEASE_AFFECTED_STATUS_REQUIRED"; }
            case "AFFECTED_INVALID" -> { affected = "false"; expected = "DISEASE_AFFECTED_STATUS_REQUIRED"; }
            case "HYPOTHESIS_SOURCE" -> {
                hypothesis = new PatientDiseaseHypothesis(hypothesis.candidateId(), hypothesis.hypothesisId(), hypothesis.hgncId(), hypothesis.mondoId(), hypothesis.moi(), null);
                expected = "HYPOTHESIS_PROVENANCE_REQUIRED";
            }
            case "TX_MISSING" -> { tx = null; expected = "TRANSCRIPT_CHOICE_REQUIRED"; }
            case "TX_VERSION" -> { tx = tx(tx, tx.accession(), null, tx.annotationSource(), tx.annotationSourceVersion(), tx.source(), tx.selectionReason()); expected = "TRANSCRIPT_VERSION_REQUIRED"; }
            case "TX_SOURCE" -> { tx = tx(tx, tx.accession(), tx.version(), tx.annotationSource(), tx.annotationSourceVersion(), null, tx.selectionReason()); expected = "TRANSCRIPT_DECLARATION_SOURCE_REQUIRED"; }
            case "TX_REASON" -> { tx = tx(tx, tx.accession(), tx.version(), tx.annotationSource(), tx.annotationSourceVersion(), tx.source(), null); expected = "TRANSCRIPT_DECLARATION_SOURCE_REQUIRED"; }
            case "ANNOTATION_SOURCE" -> { tx = tx(tx, tx.accession(), tx.version(), "OTHER", tx.annotationSourceVersion(), tx.source(), tx.selectionReason()); expected = "TRANSCRIPT_ANNOTATION_NOT_MATCHED"; }
            case "ANNOTATION_VERSION" -> { tx = tx(tx, tx.accession(), tx.version(), tx.annotationSource(), "115.0", tx.source(), tx.selectionReason()); expected = "TRANSCRIPT_ANNOTATION_NOT_MATCHED"; }
            case "ANNOTATION_AMBIGUOUS" -> {
                var doubled = new ArrayList<>(ranked.get(0).getTranscriptAnnotations());
                doubled.addAll(ranked.get(0).getTranscriptAnnotations()); ranked.get(0).applyTranscriptAnnotations(doubled);
                expected = "TRANSCRIPT_ANNOTATION_AMBIGUOUS";
            }
            case "NOT_SELECTED" -> { tx = tx(tx, "ENST99999999999", 1, tx.annotationSource(), tx.annotationSourceVersion(), tx.source(), tx.selectionReason()); expected = "TRANSCRIPT_ANNOTATION_NOT_MATCHED"; }
            case "LOCAL_SOURCE" -> { patient = patient("UNKNOWN", "PROBAND", new HypothesisProvenance("CALLER", "1", "/private/case")); expected = "PATIENT_DECLARATION_SOURCE_REQUIRED"; }
            default -> throw new AssertionError(failure);
        }
        // An invalid contradictory sex assertion must not establish a fact for the good GJB2 sibling.
        if (patient != null && !List.of("SEX_MISSING", "ROLE", "PATIENT_SOURCE", "LOCAL_SOURCE").contains(failure))
            patient = patient("FEMALE", "PROBAND", patient.source());
        var bad = new CaseDeclaration(analysisId, witness, sample, "SYNTHETIC", patient, List.of(new HypothesisInput(hypothesis, affected, tx)));
        var good = withEntries(valid, List.of(valid.hypotheses().get(1)));
        var result = invoke(run, ranked, 2, List.of(bad, good), tinyReference(r -> { }).producer(), false);
        var failed = result.adapterResult().dispatch().candidates().get(0).attempts().get(0);
        assertTrue(failed.blockers().stream().anyMatch(b -> b.reasonCode().equals(expected)), failed.blockers().toString());
        assertNull(failed.context());
        assertEquals(0, failed.providerCalls()); assertEquals(0, failed.evaluatorCalls());
        assertNotNull(result.adapterResult().dispatch().candidates().get(1).attempts().get(0).context());
        assertEquals(1, result.adapterResult().dispatch().providerCalls());
        assertEquals(1, result.production().provenance().path("referenceQueryAttempts").asInt());
    }

    @Test
    void missingPedigreeAffectedIsNotAnUnaffectedDiseaseDeclaration() throws Exception {
        var request = new org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest(true, "JOINT_VCF", true,
                "synthetic-proband", "synthetic-father", "synthetic-mother", null, null, true, false, false);
        assertNull(request.getProbandAffected());
        var trio = new org.nbscn.varvis.variantanalysis.trio.TrioPipelineInputResolver().resolve(request, temporary.resolve("unused.vcf"), null, null);
        assertFalse(trio.getMembers().get(0).isAffected()); // Lossy legacy projection is demonstrated, never consumed by intake.
        Run run = tinyRun(); var ranked = annotate(run); var declaration = declaration(run, ranked);
        var entry = declaration.hypotheses().get(0);
        for (String status : new String[]{null, "UNKNOWN"}) {
            var result = invoke(run, ranked, 1, List.of(withEntries(declaration, List.of(new HypothesisInput(entry.hypothesis(), status, entry.transcript())))),
                    tinyReference(r -> { }).producer(), false);
            var attempt = result.adapterResult().dispatch().candidates().get(0).attempts().get(0);
            if (status == null) { assertNull(attempt.context()); assertEquals(0, attempt.providerCalls()); }
            else assertEquals("UNKNOWN", attempt.context().at("/patientContext/affectedStatus").asText());
        }
    }

    @Test
    void mondoConflictScopeLocalLabelsMultipleHypothesesAndTopNRemainIntact() throws Exception {
        Run run = run(vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t.", "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t."),
                tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8", "2\t11\t2\tT\tA\tHBB\t3043\tAR\t0.7", "3\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.6"), GenomeAssembly.GRCh38);
        var ranked = annotate(run); var base = declaration(run, ranked);
        for (boolean rename : List.of(false, true)) {
            var entries = new ArrayList<>(base.hypotheses());
            entries.set(0, relabel(entries.get(0), rename ? "renamed" : "local", "AFFECTED"));
            entries.set(1, relabel(entries.get(1), "different-local", "UNAFFECTED"));
            entries.set(2, relabel(entries.get(2), "local", "UNAFFECTED"));
            entries.add(relabel(entries.get(0), "another", "AFFECTED"));
            var result = invoke(run, ranked, 3, List.of(withEntries(base, entries)), tinyReference(r -> { }).producer(), false);
            var dispatch = result.adapterResult().dispatch();
            assertEquals(ranked.stream().map((VariantInterpretation value) -> value.getCandidateId()).toList(), dispatch.candidates().stream().map(c -> c.candidateId()).toList());
            assertEquals(2, dispatch.candidates().get(0).attempts().size());
            for (int i = 0; i < 2; i++) for (var a : dispatch.candidates().get(i).attempts()) {
                assertNull(a.context()); assertEquals("PATIENT_DISEASE_AFFECTED_STATUS_CONFLICT", a.blockers().get(0).reasonCode());
                assertNotNull(a.preflight().original().provenance().patient());
            }
            assertNotNull(dispatch.candidates().get(2).attempts().get(0).context());
            assertEquals(1, dispatch.providerCalls());
            if (!rename) export("conflict-isolation", run, result, false);
        }
        var ordered = invoke(run, ranked, 1, List.of(base), tinyReference(r -> { }).producer(), false);
        assertEquals(1, ordered.adapterResult().dispatch().candidates().size());
        assertEquals(2, ordered.adapterResult().dispatch().preflight().unmatchedInputs().size());
        assertEquals("INPUT_OUTSIDE_SELECTED_TOP_N", ordered.adapterResult().dispatch().preflight().unmatchedInputs().get(0).blockers().get(0).reasonCode());
        var duplicate = invoke(run, ranked, 1, List.of(withEntries(base, List.of(base.hypotheses().get(0), base.hypotheses().get(0)))), tinyReference(r -> { }).producer(), false);
        assertEquals(0, duplicate.adapterResult().dispatch().providerCalls());
        assertEquals(2, duplicate.adapterResult().dispatch().candidates().get(0).attempts().size());
        assertEquals("HYPOTHESIS_ID_AMBIGUOUS", duplicate.adapterResult().dispatch().candidates().get(0).attempts().get(0).blockers().get(0).reasonCode());
        var missing = invoke(run, ranked, 2, List.of(), tinyReference(r -> { }).producer(), false);
        assertEquals(2, missing.adapterResult().dispatch().candidates().size());
        assertEquals(0, missing.adapterResult().dispatch().providerCalls());
    }

    @ParameterizedTest @ValueSource(strings = {"GRCh38", "GRCh37"})
    void patientOnlyDeclarationRetainsMissingHypothesisInsteadOfTopNError(String assembly) throws Exception {
        var run = run(vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t."), tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8"), GenomeAssembly.valueOf(assembly));
        var ranked = annotate(run);
        var declaration = withEntries(declaration(run, ranked), List.of());
        var result = invoke(run, ranked, 1, List.of(declaration), tinyReference(r -> {}).producer(), false);
        var unmatched = result.adapterResult().dispatch().preflight().unmatchedInputs();
        assertEquals(1, unmatched.size());
        assertNull(unmatched.get(0).candidateId());
        assertEquals("DISEASE_HYPOTHESIS_REQUIRED", unmatched.get(0).blockers().get(0).reasonCode());
        assertEquals(0, result.adapterResult().dispatch().providerCalls());
    }

    @ParameterizedTest @ValueSource(strings = {"insertion", "deletion", "wrongRef"})
    void bs1NativePureShortIndelUsesVerifiedSourceWithoutTranscript(String kind) throws Exception {
        String ref = kind.equals("deletion") ? "TC" : kind.equals("wrongRef") ? "G" : "T";
        String alt = kind.equals("deletion") ? "T" : ref + "A";
        var run = run(vcf("chr11\t2\t.\t" + ref + "\t" + alt + "\t.\tPASS\t.\tGT\t."),
                tsv("1\t11\t2\t" + ref + "\t" + alt + "\tHBB\t3043\tAR\t0.8"), GenomeAssembly.GRCh38);
        var ranked = annotate(run); var declaration = declaration(run, ranked); var input = declaration.hypotheses().get(0);
        declaration = withEntries(declaration, List.of(new HypothesisInput(input.hypothesis(), input.affectedStatus(), null)));
        var coordinator = new ExplicitCaseInputCoordinator(mapper, resolver, tinyReference(r -> {}).producer(), sources);
        var attempt = coordinator.assembleBs1(run.job(), run.conversion(), run.execution(), analysis, ranked, 1,
                List.of(declaration), Map.of()).assembly().candidates().get(0).attempts().get(0);
        if (kind.equals("wrongRef")) { assertNull(attempt.context()); assertFalse(attempt.blockers().isEmpty()); }
        else {
            var context = attempt.context(); assertNotNull(context, mapper.valueToTree(attempt.blockers()).toString());
            assertEquals(kind.equals("deletion") ? "SMALL_DELETION" : "SMALL_INSERTION", context.at("/variant/variantType").asText());
            assertFalse(context.has("transcript"));
            new org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter(mapper).validateVersionedContext(context);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"valid", "sourceRef", "missingTranscript", "sourceIdentity"})
    void pm2ConvertedContextRechecksBothReferencesAndPreservesNativeOnlyDispatcher(String mode) throws Exception {
        // Synthetic conversion witness and tiny references; retained public annotations are used only as an engineering fixture.
        var run = run(vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t."), tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8"), GenomeAssembly.GRCh37);
        var ranked = annotate(run); var candidate = ranked.get(0);
        var target = tinyReference(r -> {});
        var refFixture = new ConvertedAlleleSourceTest(); refFixture.root = temporary;
        var original = refFixture.reference(GenomeAssembly.GRCh37, "sourceRef".equals(mode) ? "AACG" : "ATCG", true);
        org.springframework.test.util.ReflectionTestUtils.setField(java.util.Objects.requireNonNull(target.producer()), "originalReference", original.query());
        var proof = mapper.createObjectNode().put("version", org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.VERSION)
                .put("inputAssembly", "GRCh37").put("analysisAssembly", "GRCh38")
                .put("sourceReferenceSha256", original.sha())
                .put("targetReferenceSha256", target.properties().getNormalization().referenceSha256For(GenomeAssembly.GRCh38));
        proof.set("originalArtifact", org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.artifact(mapper, run.job().getUploadedFile()));
        proof.set("analysisArtifact", org.nbscn.varvis.variantanalysis.engine.ConversionSourceProof.artifact(mapper, run.conversion().getOutputFile()));
        if ("sourceIdentity".equals(mode)) ((ObjectNode) proof.path("originalArtifact")).put("sha256", "0".repeat(64));
        var row = proof.putObject("candidates").putObject(candidate.getCandidateId()).put("status", "ASSOCIATED_PENDING_REFERENCE_VALIDATION")
                .put("originalRecordOrdinal", 1).put("analysisRecordOrdinal", 1).put("originalAltIndex", 1).put("analysisAltIndex", 1)
                .put("originalRecord", "chr11|2|T|A|1").put("genotypeAssociation", "EXACT_ALLELE_INDEX_AND_PHASE_MATCH");
        row.putObject("genotype").putArray("alleleIndexes").add(-1);
        for (String field : List.of("originalAllele", "mappedAllele", "analysisAllele"))
            row.putObject(field).put("assembly", field.equals("originalAllele") ? "GRCh37" : "GRCh38")
                    .put("contig", "chr11").put("position", 2).put("reference", "T").put("alternate", "A");
        var conversion = new BuildConversionResult(run.conversion().getOutputFile(), true, "SYNTHETIC_PROOF", List.of(), null, 0, 1, 1, proof);
        var declaration = declaration(run, ranked);
        if ("missingTranscript".equals(mode)) {
            var input = declaration.hypotheses().get(0);
            declaration = withEntries(declaration, List.of(new HypothesisInput(input.hypothesis(), input.affectedStatus(), null)));
        }
        var coordinator = new ExplicitCaseInputCoordinator(mapper, resolver, target.producer(), sources, dispatcher(false));
        var bs1Input = declaration.hypotheses().get(0);
        var bs1Declaration = withEntries(declaration, List.of(new HypothesisInput(bs1Input.hypothesis(), bs1Input.affectedStatus(), null)));
        var bs1Attempt = coordinator.assembleBs1(run.job(), conversion, run.execution(), analysis, ranked, 1,
                List.of(bs1Declaration), Map.of()).assembly().candidates().get(0).attempts().get(0);
        if (List.of("valid", "missingTranscript").contains(mode)) {
            var bs1Context = bs1Attempt.context(); assertNotNull(bs1Context, mapper.valueToTree(bs1Attempt.blockers()).toString());
            assertEquals("evidence-context-v3", bs1Context.path("schemaVersion").asText()); assertFalse(bs1Context.has("transcript"));
            assertEquals("GRCh37", bs1Context.at("/variant/inputAllele/assembly").asText());
            assertEquals("GRCh38", bs1Context.at("/variant/evidenceQueryAllele/assembly").asText());
            new org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter(mapper).validateVersionedContext(bs1Context);
        } else { assertNull(bs1Attempt.context()); assertFalse(bs1Attempt.blockers().isEmpty()); }
        var converted = coordinator.assembleConvertedPm2(run.job(), conversion, run.execution(), analysis, ranked, 1, List.of(declaration), Map.of());
        var attempt = converted.assembly().candidates().get(0).attempts().get(0);
        var populationOnly = new ExplicitCaseInputCoordinator(mapper, resolver, target.producer(), sources)
                .assemblePopulation(run.job(), conversion, run.execution(), analysis, ranked, 1, List.of(declaration), Map.of());
        var populationContext = populationOnly.assembly().candidates().get(0).attempts().get(0).context();
        if (attempt.context() == null) assertNull(populationContext);
        else {
            assertNotNull(populationContext);
            assertEquals(attempt.context().path("variant"), populationContext.path("variant"));
            new org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(populationContext);
        }
        assertEquals(attempt.blockers(), populationOnly.assembly().candidates().get(0).attempts().get(0).blockers());
        if ("valid".equals(mode)) {
            assertNotNull(attempt.context(), mapper.valueToTree(attempt.blockers()).toString());
            assertEquals("GRCh37", attempt.context().at("/variant/inputAllele/assembly").asText());
            assertEquals("GRCh38", attempt.context().at("/variant/evidenceQueryAllele/assembly").asText());
            new org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter(mapper).validateCompleteContext(attempt.context());
        } else {
            assertNull(attempt.context());
            assertFalse(attempt.blockers().isEmpty());
        }
        var nativeOnly = coordinator.dispatch(run.job(), conversion, run.execution(), analysis, ranked, 1, List.of(declaration));
        assertNull(nativeOnly.adapterResult().dispatch().candidates().get(0).attempts().get(0).context());
        assertEquals(0, nativeOnly.adapterResult().dispatch().providerCalls());
        var service = new AcmgBusinessService(mapper, null, null, null, () -> coordinator);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "pm2Runtime",
                (java.util.function.Supplier<org.nbscn.varvis.variantanalysis.provider.Pm2SupportingRuntime>) () ->
                        new org.nbscn.varvis.variantanalysis.provider.Pm2SupportingRuntime(mapper, new VariantAnalysisProperties()));
        ObjectNode response = mapper.valueToTree(nativeOnly.adapterResult().dispatch());
        var request = new AcmgSubmission(1, "synthetic-conversion-test", null, declaration.sampleId(), declaration.dataClass(),
                declaration.patient(), declaration.hypotheses());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "appendPm2", run.job(), request, ranked,
                new org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources.Recovered(run.execution(), conversion, Map.of(), "a".repeat(64)),
                response, mapper.createObjectNode(), declaration.hypotheses());
        var savedAttempt = response.at("/candidates/0/attempts/0");
        assertFalse(savedAttempt.path("context").isObject(), "Other criteria must retain their native-only gate");
        if ("valid".equals(mode)) {
            assertEquals("GRCh37", savedAttempt.at("/pm2/context/variant/inputAllele/assembly").asText());
            assertTrue(savedAttempt.at("/pm2/policySnapshot").isObject(), "Converted context must reach PM2 policy selection");
        } else assertFalse(savedAttempt.at("/pm2/context").isObject());
        assertNotEquals("PM2_VERIFIED_CONTEXT_MISSING", savedAttempt.at("/pm2/evaluationResult/reasonCode").asText());
    }

    @Test
    void samePatientDifferentDiseaseAndLocalLabelCanDeclareDifferentStatus() throws Exception {
        Run run = tinyRun(); var ranked = annotate(run); var base = declaration(run, ranked);
        var entries = List.of(base.hypotheses().get(0), relabel(base.hypotheses().get(1), "local", "UNAFFECTED"));
        var result = invoke(run, ranked, 2, List.of(withEntries(base, entries)), tinyReference(r -> { }).producer(), false);
        assertEquals(2, result.adapterResult().dispatch().providerCalls());
    }

    @Test
    void multipleExplicitDiseasesAndAltTwoExecuteWithoutDeduplication() throws Exception {
        Run run = run(vcf("chr11\t2\t.\tT\tG,A\t.\tPASS\t.\tGT\t."), tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8"), GenomeAssembly.GRCh38);
        var ranked = annotate(run); var base = declaration(run, ranked); var first = base.hypotheses().get(0);
        var h = first.hypothesis();
        var second = new HypothesisInput(new PatientDiseaseHypothesis(h.candidateId(), "second-disease", h.hgncId(), "MONDO:0099999", h.moi(), source("hypotheses/synthetic-second-disease")), "UNAFFECTED", first.transcript());
        var result = invoke(run, ranked, 1, List.of(withEntries(base, List.of(first, second))), tinyReference(r -> { }).producer(), false);
        var rows = result.adapterResult().dispatch().candidates();
        assertEquals(1, rows.size()); assertEquals(2, rows.get(0).attempts().size());
        assertEquals(2, result.adapterResult().dispatch().providerCalls());
        assertNotEquals(rows.get(0).attempts().get(0).context().path("contextId"), rows.get(0).attempts().get(1).context().path("contextId"));
        assertTrue(result.production().inputs().stream().allMatch(i -> i.normalization().selectedAltIndex() == 2));
        export("multi-hypothesis-alt-two", run, result, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEX", "FAMILY", "PATIENT_ID"})
    void validGeneralPatientFactConflictsStillBlockAcrossDiseases(String field) throws Exception {
        Run run = tinyRun(); var ranked = annotate(run); var base = declaration(run, ranked); var p = base.patient();
        var changed = new PatientDeclaration(field.equals("PATIENT_ID") ? "patient-context-different-synthetic" : p.patientContextId(), p.role(),
                field.equals("SEX") ? "FEMALE" : p.sex(), field.equals("FAMILY") ? "family-context-e4-a3c-synthetic" : null, p.source());
        var second = new CaseDeclaration(base.analysisId(), base.executionSource(), base.sampleId(), base.dataClass(), changed, List.of(base.hypotheses().get(1)));
        var result = invoke(run, ranked, 2, List.of(withEntries(base, List.of(base.hypotheses().get(0))), second), tinyReference(r -> { }).producer(), false);
        assertEquals(0, result.adapterResult().dispatch().providerCalls());
        for (var row : result.adapterResult().dispatch().candidates()) assertEquals("PATIENT_CONTEXT_CROSS_INPUT_CONFLICT", row.attempts().get(0).blockers().get(0).reasonCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"REF", "CHECKSUM", "CONVERTED", "GN007"})
    void inheritedSourceAndCspecGatesKeepZeroCalls(String failure) throws Exception {
        Run run = failure.equals("GN007") ? run(vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t."), tsv("1\t11\t2\tT\tA\tCDH1\t999\tAD\t0.8"), GenomeAssembly.GRCh38)
                : failure.equals("CONVERTED") ? run(vcf(HBB_VCF), tsv(HBB_TSV), GenomeAssembly.GRCh37) : tinyRun();
        var ranked = annotate(run); var base = declaration(run, ranked);
        if (failure.equals("GN007")) {
            ranked.get(0).applyTranscriptAnnotations(List.of(new TranscriptConsequence("CDH1", "999", "NM_004360.5", "NM_004360.5:c.20A>T", "NP_004351.1:p.Glu7Val", "missense_variant")
                    .withAnnotationMetadata("SYNTHETIC_CSPEC_ANNOTATION", "COMPLETED", "1", java.time.Instant.parse("2026-09-01T00:00:00Z"))));
            base = withEntries(base, List.of(new HypothesisInput(new PatientDiseaseHypothesis(ranked.get(0).getCandidateId(), "local", "HGNC:1748", "MONDO:0007648", "AD", source("hypotheses/cdh1")), "AFFECTED",
                    new TranscriptSelection("NM_004360", 5, "EXPLICIT_SYNTHETIC_CSPEC_TEST", "SYNTHETIC_CSPEC_ANNOTATION", "1", source("selections/cdh1"), "Synthetic metadata-only gate test"))));
        }
        var tiny = tinyReference(r -> { if (failure.equals("CHECKSUM")) r.put("observedSha256", "0".repeat(64)); });
        if (failure.equals("REF")) {
            // Actual query now returns C at position 2 while input remains T; update the tiny receipt through a new setup below.
            run = run(vcf("chr11\t3\t.\tT\tA\t.\tPASS\t.\tGT\t."), tsv("1\t11\t3\tT\tA\tHBB\t3043\tAR\t0.8"), GenomeAssembly.GRCh38);
            ranked = annotate(run); base = declaration(run, ranked);
        }
        var result = invoke(run, ranked, 1, List.of(base), tiny.producer(), false);
        assertEquals(0, result.adapterResult().dispatch().providerCalls()); assertEquals(0, result.adapterResult().dispatch().evaluatorCalls());
        var a = result.adapterResult().dispatch().candidates().get(0).attempts().get(0);
        assertFalse(a.blockers().isEmpty());
        if (failure.equals("GN007")) {
            assertTrue(a.blockers().stream().anyMatch(b -> b.reasonCode().equals("CSPEC_MATCH_NOT_EXECUTABLE")), a.blockers().toString());
            export("cspec-blocked", run, result, false);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "varvis.e4a3c.local-smoke", matches = "true")
    void newDeclarationEntryRealHbbGjb2ExactHit() throws Exception {
        Path root = Path.of(System.getProperty("varvis.e4a3a.binding-resource-dir"));
        resolver = PatientDiseaseContextBindingResolver.load(mapper, root.resolve("manifest.json"), root.resolve("mane-crosswalk.json"), root.resolve("clingen-catalog.json"));
        Run run = run(vcf("chr" + HBB_VCF, "chr" + GJB2_VCF), tsv(HBB_TSV, GJB2_TSV), GenomeAssembly.GRCh38);
        var ranked = annotate(run);
        Path resourceRoot = Path.of(System.getProperty("varvis.e4a3b.resource-root"));
        var properties = new VariantAnalysisProperties();
        var checksum = sources.get(0).resolve(reference).path("actualChecksumReceipt");
        properties.getNormalization().setGrch38Reference(resourceRoot.resolve(checksum.path("logicalName").asText()).toString());
        properties.getNormalization().setGrch38ReferenceSha256(checksum.path("expectedSha256").asText());
        properties.getNormalization().setGrch38ProfileId("grch38-primary-chr");
        var result = invoke(run, ranked, 2, List.of(declaration(run, ranked)), new NativeSnvSourceProducer(mapper, properties, resourceRoot, sources, reference), true);
        export("real-exact", run, result, true); // Raw Java output before strict assertions; never repair it.
        assertEquals(2, result.adapterResult().dispatch().providerCalls(), result.production().provenance().toPrettyString());
        assertEquals(2, result.adapterResult().dispatch().evaluatorCalls());
        for (var c : result.adapterResult().dispatch().candidates()) {
            var a = c.attempts().get(0); assertTrue(a.blockers().isEmpty(), a.blockers().toString());
            assertEquals("ZERO_MATCH", a.selection().path("outcome").asText());
            for (String metric : List.of("exactAlleleMatch", "transcriptProteinMatch")) {
                var found = new ArrayList<JsonNode>();
                a.atomicEvidence().get(0).path("measurements").forEach(m -> { if (m.path("metricId").asText().equals("alphamissense." + metric)) found.add(m); });
                assertEquals(1, found.size()); assertTrue(found.get(0).path("rawValue").booleanValue()); assertTrue(found.get(0).path("normalizedValue").booleanValue());
            }
            assertTrue(a.atomicEvidence().get(0).path("measurements").findValuesAsText("metricId").contains("alphamissense.amPathogenicity"));
            assertEquals(2, a.criterionEvaluations().size());
            a.criterionEvaluations().forEach(e -> assertEquals("COMPLETED", e.at("/deterministicTrace/executionStatus").asText()));
        }
    }

    private static HypothesisProvenance source(String locator) { return new HypothesisProvenance("SYNTHETIC_CALLER_DECLARATION", "1.0.0", locator); }
    @Test
    void rawCallerHypothesisLocatorRemainsPrivate() throws Exception {
        Run run = tinyRun(); var ranked = annotate(run); var base = declaration(run, ranked); var first = base.hypotheses().get(0); var h = first.hypothesis();
        var supplied = new HypothesisInput(new PatientDiseaseHypothesis(h.candidateId(), h.hypothesisId(), h.hgncId(), h.mondoId(), h.moi(),
                source("private/synthetic-proband/clinical-declaration")), first.affectedStatus(), first.transcript());
        var result = invoke(run, ranked, 1, List.of(withEntries(base, List.of(supplied))), tinyReference(r -> { }).producer(), false);
        assertEquals(1, result.adapterResult().dispatch().providerCalls());
        assertFalse(mapper.copy().findAndRegisterModules().writeValueAsString(result).contains("synthetic-proband"));
        assertEquals(supplied.hypothesis().provenance().recordLocator(), resolve(result, result.declarations().get(0).declaration())
                .at("/input/hypothesis/provenance/recordLocator").asText());
    }
    private PatientDeclaration patient(String sex, String role, HypothesisProvenance source) {
        return new PatientDeclaration("patient-context-e4-a3c-synthetic", role, sex, null, source);
    }
    private CaseDeclaration declaration(Run run, List<VariantInterpretation> ranked) {
        var entries = new ArrayList<HypothesisInput>();
        for (var candidate : ranked) {
            var choice = choices.path("fixedChoices").get("HBB".equals(candidate.getExomiserCandidateFeatures().getGeneSymbol()) ? 0 : 1);
            String transcript = choice.path("transcript").asText(); int dot = transcript.lastIndexOf('.');
            entries.add(new HypothesisInput(new PatientDiseaseHypothesis(candidate.getCandidateId(), "local", choice.path("hgncId").asText(),
                    choice.path("syntheticHypothesisMondoId").asText(), choice.path("syntheticHypothesisMoi").asText(), source("hypotheses/" + choice.path("symbol").asText())),
                    "AFFECTED", new TranscriptSelection(transcript.substring(0, dot), Integer.valueOf(transcript.substring(dot + 1)), choice.path("selectionPolicy").asText(), "VEP", "116.0",
                    new HypothesisProvenance("EXPLICIT_COMPATIBILITY_TEST_SELECTION", "1", "src/test/resources/e4-a2b/hbb-gjb2-explicit-compatible-v1.json#" + choice.path("fixtureId").asText()),
                    choice.path("selectionBasis").asText())));
        }
        return new CaseDeclaration(ANALYSIS_ID, run.execution().getInputSource(), "synthetic-proband", "SYNTHETIC", patient("UNKNOWN", "PROBAND", source("case/patient")), entries);
    }
    private static CaseDeclaration withEntries(CaseDeclaration base, List<HypothesisInput> inputs) {
        return new CaseDeclaration(base.analysisId(), base.executionSource(), base.sampleId(), base.dataClass(), base.patient(), inputs);
    }
    private static HypothesisInput relabel(HypothesisInput base, String label, String affected) {
        var h = base.hypothesis(); return new HypothesisInput(new PatientDiseaseHypothesis(h.candidateId(), label, h.hgncId(), h.mondoId(), h.moi(), h.provenance()), affected, base.transcript());
    }
    private static TranscriptSelection tx(TranscriptSelection old, String accession, Integer version, String source, String annotationVersion, HypothesisProvenance provenance, String reason) {
        return new TranscriptSelection(accession, version, old.selectionPolicy(), source, annotationVersion, provenance, reason);
    }
    private Run tinyRun() throws Exception {
        return run(vcf("chr11\t2\t.\tT\tA\t.\tPASS\t.\tGT\t.", "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t."),
                tsv("1\t11\t2\tT\tA\tHBB\t3043\tAR\t0.8", "2\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.7"), GenomeAssembly.GRCh38);
    }
    private ExplicitAlphaMissenseDispatcher dispatcher(boolean real) {
        var properties = new VariantAnalysisProperties();
        if (real) {
            properties.getAlphaMissense().setEnabled(true); properties.getAlphaMissense().setVersion("2023-08-03");
            properties.getAlphaMissense().setGrch38File(System.getProperty("varvis.e4a3a.alphamissense-grch38"));
            properties.getAlphaMissense().setTimeout(Duration.ofSeconds(30));
        }
        return new ExplicitAlphaMissenseDispatcher(mapper, new AlphaMissenseProvider(properties));
    }
    private Result invoke(Run run, List<VariantInterpretation> ranked, int n, List<CaseDeclaration> inputs, NativeSnvSourceProducer producer, boolean real) {
        return new ExplicitCaseInputCoordinator(mapper, resolver, producer, sources, dispatcher(real))
                .dispatch(run.job(), run.conversion(), run.execution(), analysis, ranked, n, inputs);
    }
    private JsonNode resolve(Result result, SourceRef ref) {
        return result.sources().stream().filter(s -> s.receipt(mapper).path("sha256").asText().equals(ref.artifactSha256())).findFirst().orElseThrow().resolve(ref);
    }
    private void export(String label, Run run, Result result, boolean real) throws Exception {
        String directory = System.getProperty("varvis.e4a3c.output-dir"); if (directory == null) return;
        var jsonMapper = mapper.copy().findAndRegisterModules();
        ObjectNode value = jsonMapper.valueToTree(result.adapterResult().dispatch());
        value.set("candidateInputProvenance", result.adapterResult().inputProvenance());
        value.set("sourceProduction", result.production().provenance());
        value.set("declarationIntake", jsonMapper.valueToTree(result.declarations()));
        var receipts = value.putArray("sourceReceipts"); result.sources().forEach(s -> receipts.add(s.receipt(mapper)));
        value.set("explicitSelectionProvenance", choices.deepCopy());
        ((ObjectNode) value.path("explicitSelectionProvenance")).put("vepOutputSha256", "741f0001460957669c1bc5661908f0eabf86fc32e21e050f3e724074c5eb574d")
                .put("annotationUse", real ? "RETAINED_REAL_VEP_FIXED_ALLELES_NO_NEW_VEP_RUN" : "SYNTHETIC_ENGINEERING_REUSE_OF_TRANSCRIPT_FIELDS");
        var fixture = value.putObject("candidateInputFixture").put("runner", "SYNTHETIC_TSV_RUNNER_NOT_REAL_EXOMISER")
                .put("patientDataClass", "SYNTHETIC").put("rankingDataClass", "SYNTHETIC")
                .put("rawAnalysisVcf", Files.readString(run.conversion().getOutputFile()))
                .put("rawVariantTsv", Files.readString(run.execution().getWorkDirectory().resolve("output/result.variants.tsv")));
        fixture.put("variantTsvSha256", sha(fixture.path("rawVariantTsv").asText().getBytes(StandardCharsets.UTF_8)))
                .put("analysisVcfSha256", run.execution().getInputSource().observedVcfSha256())
                .put("originalRecordNullCount", run.execution().getInterpretations().stream().filter(c -> c.getObservation().getOriginalRecord() == null).count());
        Path output = Path.of(directory).resolve("e4-a3c-" + label + ".json"); Files.createDirectories(output.getParent());
        Files.writeString(output, jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value).replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    TinyReference tinyReference(Consumer<ObjectNode> change) throws Exception {
        Path fasta = temporary.resolve("tiny.fa");
        Files.writeString(fasta, ">chr11\nATCG\n>chr13\nAACT\n");
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
    record TinyReference(NativeSnvSourceProducer producer, VariantAnalysisProperties properties) { }

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
                var secondDisease = ((ObjectNode) rows.get(0)).deepCopy();
                secondDisease.put("mondoId", "MONDO:0099999").put("diseaseLabel", "Synthetic second disease input-consistency test")
                        .put("testDataClass", "SYNTHETIC").put("reportLocator", "synthetic/second-disease");
                rows.add(secondDisease);
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

}
