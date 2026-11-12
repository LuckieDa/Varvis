package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.SourceRef;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.*;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver;
import org.nbscn.varvis.variantanalysis.service.PatientDiseaseContextBindingResolver.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two fixed PUBLIC alleles and real VEP/reference/catalog resources, with explicitly SYNTHETIC
 * patient, germline disease hypotheses and ranking inputs. This is not clinical validation.
 * No VEP command, reference-wide read, protein mapping or score-based input choice happens here.
 */
class ExplicitAlphaMissenseDispatcherLocalSmokeTest {
    private static final String PREFIX = "varvis.e4a2b.";
    private static final String VERSION = "116.0";
    private static final String PATIENT = "patient-context-synthetic-e4-a2b-proband";
    private static final String HISTORICAL_POLICY = "EXPLICIT_PRE_SCORE_MANE_1_5_VERSIONED_TRANSCRIPT";
    private static final String COMPATIBILITY_POLICY = "EXPLICIT_PUBLIC_VEP_AM_IDENTITY_COMPATIBILITY_V1";
    private static final String NORMALIZATION = "SNV_IDENTITY_WITH_INDEXED_REFERENCE";
    private static final String NORMALIZATION_VERSION = "e4-a2b-public-snv-preparation-v1";
    private static final String REFERENCE_SHA = "8d5e868ddc6d9600b82cd7b7e625c240a43f369f6f1a9f16b0c516eb781e7988";
    private static final List<FixedInput> BRAF_KRAS = List.of(
            new FixedInput("public-braf-v600e", "7", 140753336, "A", "T", "BRAF", "673", "HGNC:1097",
                    "ENST00000646891.2", "MONDO:0015280", "AD"),
            new FixedInput("public-kras-g12d", "12", 25245350, "C", "T", "KRAS", "3845", "HGNC:6407",
                    "ENST00000311936.8", "MONDO:0018997", "AD"));
    private static final List<FixedInput> HBB_TP53 = List.of(
            new FixedInput("public-hbb-sickle", "11", 5227002, "T", "A", "HBB", "3043", "HGNC:4827",
                    "ENST00000335295.4", "MONDO:0013517", "AR"),
            new FixedInput("public-tp53-r175h", "17", 7674220, "C", "T", "TP53", "7157", "HGNC:11998",
                    "ENST00000269305.9", "MONDO:0018875", "AD"));

    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AnalysisContext analysis = new AnalysisContext("analysis-synthetic-e4-a2b-public-resource-smoke",
            GenomeAssembly.GRCh38, AnalysisMode.WES);
    private final List<ExplicitContextSourceLedger> sources = new ArrayList<>();
    private final ArrayNode sourceReceipts = mapper.createArrayNode();

    @Test
    @EnabledIfSystemProperty(named = PREFIX + "local-smoke", matches = "true")
    void fixedTwoGenesFlowFromPublicSourceReceiptsThroughAssemblerPreflightAndRealAlphaMissense() throws Exception {
        JsonNode fixtures = mapper.readTree(Files.readAllBytes(configured("fixtures")));
        assertEquals(COMPATIBILITY_POLICY, fixtures.path("selectionPolicy").asText());
        assertFalse(fixtures.path("diseaseSpecificClinicalSelection").asBoolean(true));
        assertEquals("PUBLIC", fixtures.path("resourceDataClass").asText());
        for (String field : List.of("patientContextDataClass", "hypothesisDataClass", "candidateRankingDataClass")) {
            assertEquals("SYNTHETIC", fixtures.path(field).asText());
        }
        assertFalse(fixtures.path("clinicalValidation").asBoolean(true));
        List<FixedInput> fixedInputs = new ArrayList<>();
        for (JsonNode choice : fixtures.path("fixedChoices")) {
            assertEquals(COMPATIBILITY_POLICY, choice.path("selectionPolicy").asText());
            assertFalse(choice.path("selectionBasis").asText().isBlank());
            assertFalse(choice.path("selectionSourceLocator").asText().isBlank());
            assertTrue(choice.path("selectionSourceSha256").asText().matches("[0-9a-f]{64}"));
            assertFalse(choice.path("identityReceiptLocator").asText().isBlank());
            String moi = choice.path("syntheticHypothesisMoi").asText();
            assertTrue(List.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE").contains(moi));
            fixedInputs.add(new FixedInput(choice.path("caseId").asText(), choice.path("contig").asText(),
                    choice.path("position").asInt(), choice.path("reference").asText(), choice.path("alternate").asText(),
                    choice.path("symbol").asText(), choice.path("ncbiGeneId").asText(), choice.path("hgncId").asText(),
                    choice.path("transcript").asText(), choice.path("syntheticHypothesisMondoId").asText(),
                    moi.equals("AUTOSOMAL_RECESSIVE") ? "AR" : "AD"));
        }
        assertEquals(2, fixedInputs.size());
        assertEquals(2, fixedInputs.stream().map((FixedInput value) -> value.hgnc()).distinct().count());
        runSmoke(fixedInputs, COMPATIBILITY_POLICY, fixtures, true);
    }

    @Test
    @EnabledIfSystemProperty(named = PREFIX + "historical-mismatch", matches = "true")
    void historicalExplicitManeSelectionsRemainNotEvaluated() throws Exception {
        String choiceSet = System.getProperty(PREFIX + "choice-set", "braf-kras");
        assertTrue(List.of("braf-kras", "hbb-tp53").contains(choiceSet));
        List<FixedInput> fixedInputs = choiceSet.equals("braf-kras") ? BRAF_KRAS : HBB_TP53;
        runSmoke(fixedInputs, HISTORICAL_POLICY, null, false);
    }

    private void runSmoke(List<FixedInput> fixedInputs, String policy, JsonNode fixtures, boolean expectExact) throws Exception {
        Path preparationPath = configured("preparation");
        byte[] preparationBytes = Files.readAllBytes(preparationPath);
        JsonNode preparation = mapper.readTree(preparationBytes);
        assertEquals("PUBLIC", preparation.path("resourceDataClass").asText());
        assertEquals("SYNTHETIC", preparation.path("patientContextDataClass").asText());
        assertFalse(preparation.path("alphaMissenseReadByThisScript").asBoolean(true));
        assertEquals(2, preparation.path("fixedChoices").size());
        if (fixtures != null) {
            assertEquals(fixtures.path("fixedChoices"), preparation.path("fixedChoices"));
            assertEquals(fixtures.path("choicesFixedAt"), preparation.path("choicesFixedAt"));
            assertEquals(policy, preparation.path("selectionPolicy").asText());
        }
        for (int index = 0; index < fixedInputs.size(); index++) verifyFixedChoice(fixedInputs.get(index), preparation.path("fixedChoices").get(index));
        JsonNode prepared = null;
        for (JsonNode attempt : preparation.path("attempts")) {
            if ("PREPARED".equals(attempt.path("status").asText())) prepared = attempt;
        }
        assertNotNull(prepared, "Real VEP/reference preparation must already have succeeded");
        assertTrue(Instant.parse(preparation.path("choicesFixedAt").asText())
                .isBefore(Instant.parse(prepared.path("startedAt").asText())));
        assertEquals(0, prepared.at("/vep/exitCode").asInt(-1));
        assertEquals(2, prepared.at("/vep/outputRecordCount").asInt());
        assertEquals(VERSION, prepared.at("/vep/toolVersion").asText());
        assertEquals("116_GRCh38", prepared.at("/vep/cacheVersion").asText());

        Path vepOutput = preparationPath.getParent().resolve(prepared.at("/vep/outputJson").asText());
        Path vepInput = preparationPath.getParent().resolve(prepared.at("/vep/inputVcf").asText());
        assertEquals(prepared.at("/vep/outputSha256").asText(), sha(Files.readAllBytes(vepOutput)));
        assertEquals(prepared.at("/vep/inputVcfSha256").asText(), sha(Files.readAllBytes(vepInput)));
        var parsed = new VepJsonOutputParser(mapper).parseByVariantId(vepOutput, "VEP", VERSION);
        assertTrue(parsed.getWarnings().isEmpty(), parsed.getWarnings().toString());
        assertEquals(2, parsed.getAnnotationsByVariantId().size());

        JsonNode referenceReceipt = prepared.path("referenceManifest");
        assertEquals("VERIFIED", referenceReceipt.path("hashStatus").asText());
        assertEquals(REFERENCE_SHA, referenceReceipt.path("expectedSha256").asText());
        assertEquals(REFERENCE_SHA, referenceReceipt.path("observedSha256").asText());
        assertTrue(referenceReceipt.path("unchangedDuringHash").asBoolean());
        Path referenceFile = configured("resource-root").resolve(referenceReceipt.path("logicalName").asText());
        assertEquals(referenceReceipt.path("byteLength").asLong(), Files.size(referenceFile));
        Instant modified = Files.getLastModifiedTime(referenceFile).toInstant();
        assertEquals(referenceReceipt.path("mtimeNs").asLong(), modified.getEpochSecond() * 1_000_000_000L + modified.getNano());
        assertEquals(referenceReceipt.path("indexSha256").asText(), sha(Files.readAllBytes(Path.of(referenceFile + ".fai"))));
        SourceRef reference = referenceLedger(referenceReceipt, sha(preparationBytes));

        Path bindingResources = configured("binding-resource-dir");
        var resolver = PatientDiseaseContextBindingResolver.load(mapper, bindingResources.resolve("manifest.json"),
                bindingResources.resolve("mane-crosswalk.json"), bindingResources.resolve("clingen-catalog.json"));
        assertEquals(prepared.path("diseaseCatalogSha256").asText(), sha(Files.readAllBytes(bindingResources.resolve("clingen-catalog.json"))));
        assertEquals(prepared.path("diseaseCatalogManifestSha256").asText(), sha(Files.readAllBytes(bindingResources.resolve("manifest.json"))));
        List<VariantInterpretation> candidates = new ArrayList<>();
        List<ExplicitInput> inputs = new ArrayList<>();
        List<String> inputLines = Files.readAllLines(vepInput, StandardCharsets.UTF_8);
        for (int index = 0; index < fixedInputs.size(); index++) {
            FixedInput fixed = fixedInputs.get(index);
            List<TranscriptConsequence> annotations = parsed.getAnnotationsByVariantId().get(fixed.caseId());
            assertNotNull(annotations);
            var selected = annotations.stream().filter(value -> fixed.transcript().equals(value.getVersionedTranscriptId())).toList();
            assertEquals(1, selected.size());
            assertEquals(fixed.symbol(), selected.get(0).getGeneSymbol());
            assertEquals("missense_variant", selected.get(0).getConsequence());
            assertTrue(selected.get(0).getHgvsp().startsWith("ENSP"));
            if (fixtures != null) {
                JsonNode choice = fixtures.path("fixedChoices").get(index);
                assertEquals(choice.path("expectedHgvsp").asText(), selected.get(0).getHgvsp());
                assertEquals(choice.path("expectedProteinIdentity").asText(), selected.get(0).getProteinIdentity());
                assertEquals(choice.path("expectedAminoAcidChange").asText(), selected.get(0).getAminoAcidChange());
            }
            String vcfLine = inputLines.stream().filter(line -> !line.startsWith("#"))
                    .filter(line -> fixed.caseId().equals(line.split("\t")[2])).findFirst().orElseThrow();
            String[] columns = vcfLine.split("\t");
            assertEquals(fixed.contig(), columns[0]);
            assertEquals(fixed.position(), Integer.parseInt(columns[1]));
            assertEquals(fixed.reference(), columns[3]);
            assertEquals(fixed.alternate(), columns[4]);
            VariantInterpretation candidate = candidate(index + 1, fixed, annotations);
            var hypothesis = new PatientDiseaseHypothesis(candidate.getCandidateId(), "synthetic-hypothesis-" + fixed.caseId(),
                    fixed.hgnc(), fixed.mondo(), fixed.normalizedMoi(), new HypothesisProvenance(
                    "SYNTHETIC_PATIENT_HYPOTHESIS", "e4-a2b-local-smoke-v1", "synthetic-patient/hypotheses/" + fixed.caseId()));
            var resolved = resolver.resolve(candidate, List.of(hypothesis));
            assertTrue(resolved.blockers().isEmpty(), resolved.blockers().toString());
            assertEquals(1, resolved.bindings().size());
            DiseaseContextBinding binding = resolved.bindings().get(0);
            assertEquals("READY", binding.status());
            JsonNode observation = prepared.path("referenceObservations").get(index);
            verifyObservation(fixed, observation);
            verifyObservation(fixed, prepared.path("vepReferenceObservations").get(index));
            candidates.add(candidate);
            inputs.add(input(candidate, binding, fixed, reference, observation, prepared, sha(preparationBytes), vcfLine,
                    policy, fixtures == null ? null : fixtures.path("fixedChoices").get(index)));
        }
        var assembled = new ExplicitEvidenceContextAssembler(mapper, resolver, sources).assemble(analysis, candidates, 2, inputs);
        var properties = new VariantAnalysisProperties();
        properties.getAlphaMissense().setEnabled(true);
        properties.getAlphaMissense().setVersion("2023-08-03");
        Path alphaMissense = configured("alphamissense-grch38");
        assertTrue(Files.isRegularFile(alphaMissense));
        assertTrue(Files.isRegularFile(Path.of(alphaMissense + ".tbi")));
        properties.getAlphaMissense().setGrch38File(alphaMissense.toString());
        properties.getAlphaMissense().setTimeout(Duration.ofSeconds(30));
        var result = new ExplicitAlphaMissenseDispatcher(mapper, new AlphaMissenseProvider(properties))
                .dispatch(assembled, candidates, analysis);

        // Export actual results before assertions, so every attempted outcome survives a failed smoke.
        ObjectNode report = mapper.valueToTree(result);
        ObjectNode provenance = report.putObject("smokeInputProvenance").put("resourceDataClass", "PUBLIC")
                .put("patientContextDataClass", "SYNTHETIC").put("candidateRankingDataClass", "SYNTHETIC")
                .put("hypothesisDataClass", "SYNTHETIC").put("selectionPolicy", policy)
                .put("clinicalValidation", false).put("proteinIdentityMappingEstablished", false)
                .put("inputPreparationSha256", sha(preparationBytes)).put("vepOutputSha256", prepared.at("/vep/outputSha256").asText())
                .put("choicesFixedAt", preparation.path("choicesFixedAt").asText());
        provenance.set("fixedChoices", preparation.path("fixedChoices").deepCopy());
        provenance.set("referenceManifest", referenceReceipt.deepCopy());
        provenance.set("sourceReceipts", sourceReceipts.deepCopy());
        ArrayNode attempts = provenance.putArray("preparationAttempts");
        for (JsonNode attempt : preparation.path("attempts")) {
            attempts.addObject().put("attemptNumber", attempt.path("attemptNumber").asInt())
                    .put("status", attempt.path("status").asText()).put("vepExitCode", attempt.at("/vep/exitCode").asInt())
                    .put("vepOutputRecordCount", attempt.at("/vep/outputRecordCount").asInt());
        }
        String serialized = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report).replace("\r\n", "\n");
        assertFalse(Pattern.compile("(?i)(?:^|[\"\\s])[a-z]:[\\\\/]").matcher(serialized).find(),
                "Export must not contain machine paths");
        assertFalse(serialized.contains("file:///"), "Export must not contain local file URIs");
        Path output = Path.of(System.getProperty(PREFIX + "smoke-output", "target/e4-a2b-local-dispatch.json"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, serialized + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

        assertEquals(2, result.candidates().size());
        assertEquals(2, result.providerCalls());
        assertEquals(2, result.evaluatorCalls());
        for (var row : result.candidates()) {
            assertEquals(1, row.attempts().size());
            var attempt = row.attempts().get(0);
            assertTrue(attempt.blockers().isEmpty(), attempt.blockers().toString());
            assertEquals("ZERO_MATCH", attempt.selection().path("outcome").asText());
            assertEquals(1, attempt.atomicEvidence().size());
            assertEquals("HIT", attempt.atomicEvidence().get(0).at("/queryOutcome/status").asText());
            List<JsonNode> alleleMatches = new ArrayList<>();
            attempt.atomicEvidence().get(0).path("measurements").forEach(metric -> {
                if ("alphamissense.exactAlleleMatch".equals(metric.path("metricId").asText())) alleleMatches.add(metric);
            });
            assertEquals(1, alleleMatches.size());
            assertEquals(mapper.getNodeFactory().booleanNode(true), alleleMatches.get(0).path("rawValue"));
            assertEquals(mapper.getNodeFactory().booleanNode(true), alleleMatches.get(0).path("normalizedValue"));
            List<JsonNode> proteinMatches = new ArrayList<>();
            attempt.atomicEvidence().get(0).path("measurements").forEach(metric -> {
                if ("alphamissense.transcriptProteinMatch".equals(metric.path("metricId").asText())) proteinMatches.add(metric);
            });
            assertEquals(1, proteinMatches.size());
            assertTrue(proteinMatches.get(0).path("rawValue").isBoolean());
            assertTrue(proteinMatches.get(0).path("normalizedValue").isBoolean());
            assertEquals(expectExact, proteinMatches.get(0).path("rawValue").asBoolean(),
                    "An allele-only HIT is not a versioned transcript/protein exact-hit smoke");
            assertEquals(expectExact, proteinMatches.get(0).path("normalizedValue").asBoolean());
            assertEquals(expectExact, attempt.atomicEvidence().get(0).path("measurements").findValuesAsText("metricId")
                    .contains("alphamissense.amPathogenicity"), "A mismatch must not acquire a score");
            assertEquals(2, attempt.criterionEvaluations().size());
            for (JsonNode evaluation : attempt.criterionEvaluations()) {
                if (expectExact) {
                    assertEquals("COMPLETED", evaluation.at("/deterministicTrace/executionStatus").asText());
                    assertTrue(List.of("MET", "NOT_MET").contains(evaluation.at("/evaluationResult/status").asText()));
                    assertEquals("SUPPORTING", evaluation.path("baseStrength").asText());
                } else {
                    assertEquals("NOT_EVALUATED", evaluation.at("/evaluationResult/status").asText());
                }
            }
            assertEquals("VEP", attempt.transcriptInput().path("source").asText());
            assertTrue(attempt.transcriptInput().path("vepProteinIdentity").asText().startsWith("ENSP"));
        }
    }

    private SourceRef referenceLedger(JsonNode receipt, String preparationSha) throws Exception {
        ObjectNode record = mapper.createObjectNode().put("kind", "REFERENCE_IDENTITY").put("assembly", "GRCh38")
                .put("resourceDataClass", "PUBLIC").put("observedSha256", receipt.path("observedSha256").asText())
                .put("expectedSha256", receipt.path("expectedSha256").asText()).put("verificationStatus", "VERIFIED")
                .put("verificationMethod", "SHA-256").put("verificationVersion", "python-hashlib-e4-a2b-v1")
                .put("verificationRecordLocator", "e4-a2b-input-preparation/referenceManifest")
                .put("referenceArtifactLocator", receipt.path("logicalName").asText()).put("preparationArtifactSha256", preparationSha);
        record.set("actualChecksumReceipt", receipt.deepCopy());
        return ledger(mapper.createObjectNode().set("reference", record), "PUBLIC_REFERENCE_RECEIPT_FOR_SYNTHETIC_CONTEXT").reference("reference");
    }

    private ExplicitInput input(VariantInterpretation candidate, DiseaseContextBinding binding, FixedInput fixed,
                                SourceRef reference, JsonNode observed, JsonNode prepared,
                                String preparationSha, String vcfLine, String policy, JsonNode explicitChoice) throws Exception {
        Allele allele = new Allele("GRCh38", fixed.contig(), fixed.position(), fixed.reference(), fixed.alternate());
        int dot = fixed.transcript().lastIndexOf('.');
        String accession = fixed.transcript().substring(0, dot);
        int version = Integer.parseInt(fixed.transcript().substring(dot + 1));
        ObjectNode records = mapper.createObjectNode();
        ObjectNode patient = associated("PATIENT_CONTEXT", candidate, binding)
                .put("sampleId", candidate.getObservation().getSampleId()).put("role", "PROBAND")
                .put("affectedStatus", "AFFECTED").put("sex", "UNKNOWN");
        patient.putObject("hypothesis").put("hgncId", binding.hgncId()).put("mondoId", binding.mondoId())
                .put("moi", binding.normalizedMoi()).set("provenance", mapper.valueToTree(binding.provenance().patientHypothesis().provenance()));
        records.set("patient", patient);
        records.set("transcript", associated("TRANSCRIPT_CHOICE", candidate, binding)
                .put("accession", accession).put("version", version).put("selectionPolicy", policy)
                .put("annotationSource", "VEP").put("annotationSourceVersion", VERSION)
                .put("hgncId", fixed.hgnc()).put("geneSymbol", fixed.symbol()).put("resourceDataClass", "PUBLIC")
                .put("annotationRecordLocator", "real-vep-output/" + fixed.caseId() + "/" + fixed.transcript())
                .put("annotationArtifactSha256", prepared.at("/vep/outputSha256").asText())
                .put("preparationArtifactSha256", preparationSha));
        if (explicitChoice != null) ((ObjectNode) records.path("transcript")).set("explicitSelectionReceipt", explicitChoice.deepCopy());
        records.set("lineage", associated("ASSEMBLY_LINEAGE", candidate, binding)
                .put("originalInputAssembly", "GRCh38").put("preRankingMappingStatus", "NATIVE")
                .set("originalInputAllele", mapper.valueToTree(allele)));
        assertTrue(candidate.getObservation().normalizationMapping().isResolved());
        assertEquals("IDENTITY", candidate.getObservation().normalizationMapping().getStatus());
        ObjectNode normalization = associated("NORMALIZATION", candidate, binding)
                .put("originalRecord", candidate.getObservation().getOriginalRecord()).put("selectedAltIndex", 1)
                .put("method", NORMALIZATION).put("methodVersion", NORMALIZATION_VERSION)
                .put("executionStatus", "SUCCEEDED").put("status", "UNCHANGED").put("refValidation", "MATCH")
                .put("inputObservedReference", observed.path("observedReference").asText())
                .put("normalizedObservedReference", observed.path("observedReference").asText())
                .put("refValidationMethod", observed.path("method").asText()).put("refValidationVersion", observed.path("methodVersion").asText())
                .put("refValidationRecordLocator", "e4-a2b-input-preparation/referenceObservations/" + fixed.caseId())
                .put("resourceDataClass", "PUBLIC").put("preparationArtifactSha256", preparationSha).put("originalVcfRecord", vcfLine);
        normalization.set("originalAllele", mapper.valueToTree(allele));
        normalization.set("normalizedAllele", mapper.valueToTree(allele));
        normalization.set("reference", mapper.valueToTree(reference));
        normalization.set("actualReferenceObservation", observed.deepCopy());
        records.set("normalization", normalization);
        var ledger = ledger(records, "EXPLICIT_PUBLIC_INPUT_RECEIPTS_" + fixed.symbol());
        return new ExplicitInput(candidate.getCandidateId(), binding.hypothesisId(), binding, "SYNTHETIC",
                new TranscriptChoice(accession, version, policy, "VEP", VERSION, ledger.reference("transcript")),
                new PatientContext(PATIENT, "PROBAND", "AFFECTED", "UNKNOWN", null, ledger.reference("patient")),
                new NormalizationInput(allele, allele, 1, candidate.getObservation().getOriginalRecord(),
                        NORMALIZATION, NORMALIZATION_VERSION, ledger.reference("normalization"), reference,
                        new AssemblyLineage("GRCh38", "NATIVE", allele, null, ledger.reference("lineage"))));
    }

    private ExplicitContextSourceLedger ledger(ObjectNode records, String source) throws Exception {
        // Envelope class describes the synthetic context association; individual public receipts retain their resource class.
        ObjectNode envelope = mapper.createObjectNode().put("source", source).put("version", "e4-a2b-receipt-projection-v1")
                .put("dataClass", "SYNTHETIC").set("records", records);
        byte[] bytes = mapper.writeValueAsBytes(envelope);
        sourceReceipts.addObject().put("source", source).put("sha256", sha(bytes))
                .put("rawJson", new String(bytes, StandardCharsets.UTF_8));
        Path path = temporary.resolve(source + ".json");
        Files.write(path, bytes);
        var ledger = ExplicitContextSourceLedger.load(mapper, path, sha(bytes));
        sources.add(ledger);
        return ledger;
    }

    private ObjectNode associated(String kind, VariantInterpretation candidate, DiseaseContextBinding binding) {
        return mapper.createObjectNode().put("kind", kind).put("analysisId", analysis.getAnalysisId())
                .put("candidateId", candidate.getCandidateId()).put("hypothesisId", binding.hypothesisId()).put("patientContextId", PATIENT);
    }

    private VariantInterpretation candidate(int ordinal, FixedInput fixed, List<TranscriptConsequence> annotations) {
        var key = NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, fixed.contig(), fixed.position(), fixed.reference(), fixed.alternate());
        var identity = CandidateIdentity.exomiser(key.canonicalId(), ordinal, fixed.symbol(), fixed.moi(), ordinal);
        var observation = new VariantQcObservation(key, "synthetic-proband", "0/1", 30, 60, "15,15", 0.5d,
                "PASS", "SNV", fixed.contig() + "|" + fixed.position() + "|" + fixed.reference() + "|" + fixed.alternate() + "|1");
        var features = new ExomiserCandidateFeatures(ExomiserCandidateFeatures.CURRENT_SCHEMA_VERSION, "15.1.0",
                fixed.symbol(), fixed.ncbi(), fixed.moi(), fixed.normalizedMoi(), ordinal,
                ExomiserCandidateFeatures.GENE_MOI_GROUP_RANK_SEMANTICS, "synthetic-candidate-" + ordinal,
                0.9, 0.8, 0.7, 0.6, "MISSENSE_VARIANT", null, true, "synthetic-variants.tsv", "synthetic-genes.tsv",
                ordinal, ordinal, "SYNTHETIC_EXACT_GENE_MOI", "EXOMISER_VARIANT_SCORE", List.of(), List.of(), Map.of());
        var candidate = new VariantInterpretation(identity, observation, List.of(), List.of(), features);
        candidate.applyTranscriptAnnotations(annotations);
        return candidate;
    }

    private void verifyFixedChoice(FixedInput fixed, JsonNode value) {
        assertEquals(fixed.caseId(), value.path("caseId").asText());
        assertEquals("GRCh38", value.path("assembly").asText());
        assertEquals(fixed.contig(), value.path("contig").asText());
        assertEquals(fixed.position(), value.path("position").asInt());
        assertEquals(fixed.reference(), value.path("reference").asText());
        assertEquals(fixed.alternate(), value.path("alternate").asText());
        assertEquals(fixed.symbol(), value.path("symbol").asText());
        assertEquals(fixed.hgnc(), value.path("hgncId").asText());
        assertEquals(fixed.transcript(), value.path("transcript").asText());
        assertEquals(fixed.mondo(), value.path("syntheticHypothesisMondoId").asText());
    }

    private void verifyObservation(FixedInput fixed, JsonNode observed) {
        assertNotNull(observed);
        assertEquals("GRCh38", observed.path("assembly").asText());
        assertEquals(fixed.contig(), observed.path("contig").asText());
        assertEquals(fixed.position(), observed.path("position").asInt());
        assertEquals(fixed.reference(), observed.path("expectedReference").asText());
        assertEquals(fixed.reference(), observed.path("observedReference").asText());
        assertEquals("MATCH", observed.path("status").asText());
    }

    private Path configured(String suffix) {
        String value = System.getProperty(PREFIX + suffix);
        assertNotNull(value, "Set " + PREFIX + suffix + " for the explicit local smoke");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record FixedInput(String caseId, String contig, int position, String reference, String alternate,
                              String symbol, String ncbi, String hgnc, String transcript, String mondo, String moi) {
        private String normalizedMoi() { return moi.equals("AR") ? "AUTOSOMAL_RECESSIVE" : "AUTOSOMAL_DOMINANT"; }
    }
}
