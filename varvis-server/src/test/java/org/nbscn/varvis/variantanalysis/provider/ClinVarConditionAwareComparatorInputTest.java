package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClinVarConditionAwareComparatorInputTest {

    private static final String TRANSCRIPT = "ENST000001.2";
    private static final String VEP_VERSION = "VEP 116.0";

    @TempDir
    Path tempDirectory;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void buildsOneVersionedConditionAwareComparatorWithOneClinVarAndVepCall()
            throws Exception {
        CountingClinVar clinVar = clinVar();
        CountingVep vep = new CountingVep(false);
        ClinVarConditionAwareComparatorInput input = new ClinVarConditionAwareComparatorInput(
                clinVar.provider, vep, sidecar(), new VepProteinRelationClassifier());
        NormalizedVariantKey targetKey = key(100, "A", "G");

        ClinVarConditionAwareComparatorInput.Result result = input.build(
                context(targetKey), variant(targetKey), consequence("K/R", "cAa/cGa"));

        assertEquals("READY", result.getStatus());
        assertEquals(ClinVarConditionAwareComparatorInput.RESULT_SCHEMA_VERSION,
                result.getSchemaVersion());
        assertEquals(1, clinVar.calls);
        assertEquals("1:99-101", clinVar.region);
        assertEquals(1, vep.calls);
        assertEquals(4, vep.batchAlleles.size());
        assertEquals(99, result.getCodonWindow().getStart());
        assertEquals(101, result.getCodonWindow().getEnd());
        assertEquals("FORWARD", result.getCodonWindow().getOrientation());
        assertEquals(1, result.getComparators().size());

        ClinVarConditionAwareComparatorInput.ComparatorRecord record =
                result.getComparators().get(0);
        assertEquals(ClinVarConditionAwareComparatorInput.RECORD_SCHEMA_VERSION,
                record.getSchemaVersion());
        assertEquals("context-sha256-test", record.getTargetContextId());
        assertEquals(key(100, "A", "T"), record.getExactAllele());
        assertEquals("2", record.getVariationId());
        assertEquals(List.of("SCV_VCF_1", "SCV_VCF_2"),
                record.getContributingSubmissionIdentifiers());
        assertEquals("SAME_RESIDUE_DIFFERENT_AMINO_ACID", record.getProteinRelation());
        assertEquals("600", record.getTargetProteinChange().getProteinPosition());
        assertEquals("N", record.getComparatorProteinChange().getAlternateAminoAcid());
        assertEquals("CONFLICTING", record.getConflictState());
        assertEquals("Condition one", record.getVariantSummaryRecords().get(0)
                .path("condition").path("name").asText());
        assertEquals("SCV000000001.1", record.getSubmissions().get(0).path("scv").asText());
        assertEquals("Pathogenic", record.getSubmissions().get(0)
                .path("classification").asText());
        assertEquals("2025-12-01", record.getSubmissions().get(0)
                .path("dateLastEvaluated").asText());
        assertEquals("CLINVAR_2026_03_05_COMPARATOR", record.getSnapshotProvenance().getResourceId());
        assertEquals("ClinVar 2026-03-05", record.getSnapshotProvenance().getResourceVersion());
        assertEquals(1, result.getCandidateDecisions().size());
        assertEquals("READY", result.getCandidateDecisions().get(0).getStatus());

        String serialized = mapper.writeValueAsString(result);
        assertFalse(serialized.contains(tempDirectory.toAbsolutePath().toString()));
        assertFalse(serialized.contains("sampleId"));
        assertFalse(serialized.contains("criterion-evaluation-v2"));
        assertFalse(serialized.contains("implementationStatus"));
        assertFalse(serialized.contains("strength"));
    }

    @Test
    void ambiguousCodonsStopBeforeEitherProviderIsCalled() throws Exception {
        CountingClinVar clinVar = clinVar();
        CountingVep vep = new CountingVep(false);
        ClinVarConditionAwareComparatorInput input = new ClinVarConditionAwareComparatorInput(
                clinVar.provider, vep, sidecar(), new VepProteinRelationClassifier());
        NormalizedVariantKey targetKey = key(100, "A", "G");

        ClinVarConditionAwareComparatorInput.Result result = input.build(
                context(targetKey), variant(targetKey), consequence("K/R", "AAA/CGG"));

        assertEquals("NOT_EVALUATED", result.getStatus());
        assertEquals("CODON_WINDOW_NOT_UNIQUELY_RESOLVABLE", result.getReasonCode());
        assertEquals(0, clinVar.calls);
        assertEquals(0, vep.calls);
    }

    @Test
    void crossExonLikeProbeMismatchStopsAfterTheSingleCombinedVepBatch() throws Exception {
        CountingClinVar clinVar = clinVar();
        CountingVep vep = new CountingVep(true);
        ClinVarConditionAwareComparatorInput input = new ClinVarConditionAwareComparatorInput(
                clinVar.provider, vep, sidecar(), new VepProteinRelationClassifier());
        NormalizedVariantKey targetKey = key(100, "A", "G");

        ClinVarConditionAwareComparatorInput.Result result = input.build(
                context(targetKey), variant(targetKey), consequence("K/R", "cAa/cGa"));

        assertEquals("NOT_EVALUATED", result.getStatus());
        assertEquals("CODON_WINDOW_NOT_CONTIGUOUS", result.getReasonCode());
        assertEquals(1, clinVar.calls);
        assertEquals(1, vep.calls);
        assertTrue(result.getComparators().isEmpty());
    }

    @Test
    void capturePreservesCompleteLegacyResultAndTheSuppliedTargetDto() throws Exception {
        CountingClinVar clinVar = clinVar();
        CountingVep vep = new CountingVep(false);
        vep.referenceExon = "7/23";
        ClinVarConditionAwareComparatorInput input = new ClinVarConditionAwareComparatorInput(
                clinVar.provider, vep, sidecar(), new VepProteinRelationClassifier());
        NormalizedVariantKey targetKey = key(100, "A", "G");
        ObjectNode evidenceContext = context(targetKey);
        TranscriptConsequence selected = withField(consequence("K/R", "cAa/cGa"), "exon", "9/27");

        JsonNode legacy = mapper.valueToTree(input.build(evidenceContext, variant(targetKey), selected));
        assertEquals(1, clinVar.calls);
        assertEquals(1, vep.calls);
        var captured = input.buildWithSnapshots(evidenceContext, variant(targetKey), selected);

        assertEquals(2, clinVar.calls);
        assertEquals(2, vep.calls);
        assertEquals(legacy, captured.getResult());
        assertEquals(evidenceContext, captured.getEvidenceContext());
        assertEquals(mapper.valueToTree(selected), captured.getSelectedTargetAnnotation());
        assertEquals("9/27", captured.getSelectedTargetAnnotation().path("exon").asText());
        assertEquals(1, captured.getReferenceSnapshots().size());
        var reference = captured.getReferenceSnapshots().get(0);
        assertEquals(key(100, "A", "T"), reference.getExactAllele());
        assertEquals("2", reference.getVariationId());
        assertEquals(TRANSCRIPT, reference.getTranscript());
        assertEquals(mapper.valueToTree(vep.lastAnnotations.get(reference.getExactAllele()).get(0)),
                reference.getAnnotation());
        assertEquals("7/23", reference.getAnnotation().path("exon").asText());
        assertFalse(legacy.has("referenceSnapshots"));
        assertFalse(legacy.path("comparators").get(0).has("annotation"));
        assertFalse(legacy.has("selectedTargetAnnotation"));
    }

    @Test
    void captureIsIndependentOfReturnedJsonProviderContainersAndTheNextContext() throws Exception {
        CountingClinVar clinVar = clinVar();
        CountingVep vep = new CountingVep(false);
        ClinVarConditionAwareComparatorInput input = new ClinVarConditionAwareComparatorInput(
                clinVar.provider, vep, sidecar(), new VepProteinRelationClassifier());
        NormalizedVariantKey targetKey = key(100, "A", "G");
        ObjectNode firstContext = context(targetKey);
        var first = input.buildWithSnapshots(firstContext, variant(targetKey), consequence("K/R", "cAa/cGa"));
        JsonNode firstResult = first.getResult();
        JsonNode firstTarget = first.getSelectedTargetAnnotation();
        JsonNode firstReference = first.getReferenceSnapshots().get(0).getAnnotation();
        ((ObjectNode) first.getResult()).put("status", "CALLER_MUTATION");
        ((ObjectNode) first.getEvidenceContext()).put("contextId", "CALLER_MUTATION");
        ((ObjectNode) first.getSelectedTargetAnnotation()).put("exon", "CALLER_MUTATION");
        ((ObjectNode) first.getReferenceSnapshots().get(0).getAnnotation()).put("exon", "CALLER_MUTATION");
        assertThrows(UnsupportedOperationException.class, () -> first.getReferenceSnapshots().clear());
        vep.lastAnnotations.clear();
        firstContext.put("contextId", "second-context");
        vep.referenceExon = "11/31";
        var second = input.buildWithSnapshots(firstContext, variant(targetKey), consequence("K/R", "cAa/cGa"));

        assertEquals(firstResult, first.getResult());
        assertEquals(firstTarget, first.getSelectedTargetAnnotation());
        assertEquals(firstReference, first.getReferenceSnapshots().get(0).getAnnotation());
        assertEquals("context-sha256-test", first.getEvidenceContext().path("contextId").asText());
        assertEquals("second-context", second.getResult().path("contextId").asText());
        assertEquals("second-context", second.getResult().path("comparators").get(0).path("targetContextId").asText());
        assertEquals("11/31", second.getReferenceSnapshots().get(0).getAnnotation().path("exon").asText());
        assertEquals(2, clinVar.calls);
        assertEquals(2, vep.calls);
    }

    @Test
    void captureUsesExactRecordIdentityAcrossCandidateAndTranscriptOrder() throws Exception {
        CountingClinVar clinVar = clinVar();
        clinVar.rows = new ArrayList<>(List.of(
                "1\t99\t3\tC\tT\t.\tPASS\tCLNSIGSCV=SCV_SECOND_RECORD",
                "1\t100\t2\tA\tT\t.\tPASS\tCLNSIGSCV=SCV_VCF_1|SCV_VCF_2"));
        CountingVep vep = new CountingVep(false);
        vep.extraTranscripts = true;
        ClinVarConditionAwareComparatorInput input = new ClinVarConditionAwareComparatorInput(
                clinVar.provider, vep, sidecar(true), new VepProteinRelationClassifier());
        NormalizedVariantKey targetKey = key(100, "A", "G");
        var first = input.buildWithSnapshots(context(targetKey), variant(targetKey), consequence("K/R", "cAa/cGa"));
        Collections.reverse(clinVar.rows);
        vep.reverseAnnotations = true;
        var second = input.buildWithSnapshots(context(targetKey), variant(targetKey), consequence("K/R", "cAa/cGa"));

        Map<String, JsonNode> firstByIdentity = capturedByIdentity(first);
        assertEquals(firstByIdentity, capturedByIdentity(second));
        assertEquals(2, firstByIdentity.size());
        assertTrue(firstByIdentity.containsKey("GRCh38|1|100|A|T:2:" + TRANSCRIPT));
        assertTrue(firstByIdentity.containsKey("GRCh38|1|99|C|T:3:" + TRANSCRIPT));
        assertEquals(2, first.getResult().path("comparators").size());
        assertEquals(2, second.getResult().path("comparators").size());
        for (JsonNode record : first.getResult().path("comparators")) {
            assertEquals("CONFLICTING", record.path("conflictState").asText());
            assertEquals("SCV000000001.1", record.path("submissions").get(0).path("scv").asText());
        }
        assertEquals(2, clinVar.calls);
        assertEquals(2, vep.calls);
        assertEquals(5, vep.batchAlleles.size());
    }

    @Test
    void distinctVariationIdsForOneAlleleKeepTheExistingDiscoveryAmbiguity() throws Exception {
        CountingClinVar clinVar = clinVar();
        clinVar.rows = List.of(
                "1\t100\t2\tA\tT\t.\tPASS\tCLNSIGSCV=SCV_FIRST",
                "1\t100\t3\tA\tT\t.\tPASS\tCLNSIGSCV=SCV_SECOND");
        CountingVep vep = new CountingVep(false);
        var input = new ClinVarConditionAwareComparatorInput(
                clinVar.provider, vep, sidecar(), new VepProteinRelationClassifier());
        NormalizedVariantKey targetKey = key(100, "A", "G");
        var capture = input.buildWithSnapshots(context(targetKey), variant(targetKey), consequence("K/R", "cAa/cGa"));
        assertEquals("NOT_EVALUATED", capture.getResult().path("status").asText());
        assertEquals("CLINVAR_COMPARATOR_ALLELE_AMBIGUOUS", capture.getResult().path("reasonCode").asText());
        assertTrue(capture.getReferenceSnapshots().isEmpty());
        assertEquals(1, clinVar.calls);
        assertEquals(0, vep.calls);
    }

    @Test
    void captureExcludesMissingAmbiguousAndUnrelatedCandidatesButRetainsEveryDecision() throws Exception {
        CountingClinVar clinVar = clinVar();
        clinVar.rows = List.of(
                "1\t100\t2\tA\tT\t.\tPASS\tCLNSIGSCV=SCV_VCF_1",
                "1\t99\t4\tC\tT\t.\tPASS\tCLNSIGSCV=MISSING",
                "1\t101\t5\tA\tG\t.\tPASS\tCLNSIGSCV=AMBIGUOUS",
                "1\t100\t6\tA\tC\t.\tPASS\tCLNSIGSCV=OTHER_RESIDUE");
        CountingVep vep = new CountingVep(false);
        vep.partialCandidates = true;
        var input = new ClinVarConditionAwareComparatorInput(
                clinVar.provider, vep, sidecar(), new VepProteinRelationClassifier());
        NormalizedVariantKey targetKey = key(100, "A", "G");
        var capture = input.buildWithSnapshots(context(targetKey), variant(targetKey), consequence("K/R", "cAa/cGa"));

        assertEquals("READY", capture.getResult().path("status").asText());
        assertEquals(1, capture.getReferenceSnapshots().size());
        assertEquals("2", capture.getReferenceSnapshots().get(0).getVariationId());
        assertEquals(4, capture.getResult().path("candidateDecisions").size());
        Map<String, String> decisions = new LinkedHashMap<>();
        for (JsonNode decision : capture.getResult().path("candidateDecisions")) {
            decisions.put(decision.path("variationId").asText(), decision.path("reasonCode").asText());
        }
        assertEquals("SELECTED_COMPARATOR_TRANSCRIPT_NOT_FOUND", decisions.get("4"));
        assertEquals("SELECTED_COMPARATOR_TRANSCRIPT_AMBIGUOUS", decisions.get("5"));
        assertEquals("NOT_A_SAME_RESIDUE_COMPARATOR", decisions.get("6"));
        assertEquals(1, clinVar.calls);
        assertEquals(1, vep.calls);
    }

    @Test
    void captureKeepsEarlyExitCountsAndHasNoReferencesFromAnEarlierCall() throws Exception {
        CountingClinVar clinVar = clinVar();
        CountingVep vep = new CountingVep(false);
        var input = new ClinVarConditionAwareComparatorInput(
                clinVar.provider, vep, sidecar(), new VepProteinRelationClassifier());
        NormalizedVariantKey targetKey = key(100, "A", "G");
        var ready = input.buildWithSnapshots(context(targetKey), variant(targetKey), consequence("K/R", "cAa/cGa"));
        var invalid = input.buildWithSnapshots(context(targetKey), variant(targetKey), consequence("K/R", "AAA/CGG"));
        assertEquals(1, ready.getReferenceSnapshots().size());
        assertEquals("NOT_EVALUATED", invalid.getResult().path("status").asText());
        assertEquals("CODON_WINDOW_NOT_UNIQUELY_RESOLVABLE", invalid.getResult().path("reasonCode").asText());
        assertTrue(invalid.getReferenceSnapshots().isEmpty());
        assertEquals(1, clinVar.calls);
        assertEquals(1, vep.calls);

        clinVar.rows = List.of();
        var empty = input.buildWithSnapshots(context(targetKey), variant(targetKey), consequence("K/R", "cAa/cGa"));
        assertEquals("NO_RECORD", empty.getResult().path("status").asText());
        assertTrue(empty.getReferenceSnapshots().isEmpty());
        assertEquals(2, clinVar.calls);
        assertEquals(2, vep.calls);
    }

    private Map<String, JsonNode> capturedByIdentity(ClinVarConditionAwareComparatorInput.SnapshotCapture capture) {
        Map<String, JsonNode> snapshots = new LinkedHashMap<>();
        for (var reference : capture.getReferenceSnapshots()) {
            snapshots.put(reference.getExactAllele().canonicalId() + ":" + reference.getVariationId()
                    + ":" + reference.getTranscript(), reference.getAnnotation());
        }
        return snapshots;
    }

    private TranscriptConsequence withField(TranscriptConsequence value, String field, String content) {
        ObjectNode node = mapper.valueToTree(value);
        node.put(field, content);
        node.remove(List.of("hgvsc", "hgvsp"));
        return mapper.convertValue(node, TranscriptConsequence.class);
    }

    private CountingClinVar clinVar() throws IOException {
        Path vcf = tempDirectory.resolve("clinvar.vcf.gz");
        Files.write(vcf, new byte[] {1});
        Files.write(vcf.resolveSibling(vcf.getFileName() + ".tbi"), new byte[] {1});
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().setEnabled(true);
        properties.getClinVar().setGrch38File(vcf.toString());
        CountingClinVar counting = new CountingClinVar();
        counting.provider = new ClinVarTabixProvider(properties, null, (file, region) -> {
            counting.calls++;
            counting.region = region;
            return counting.rows;
        });
        return counting;
    }

    private ClinVarComparatorSidecar sidecar() throws Exception {
        return sidecar(false);
    }

    private ClinVarComparatorSidecar sidecar(boolean secondIdentity) throws Exception {
        Path plain = tempDirectory.resolve("comparator-sidecar.tsv");
        String header = "#index_contig\tindex_start\tindex_end\tvariation_id\tassembly\t"
                + "chromosome\tposition\treference_allele\talternate_allele\t"
                + "gene_symbols_json\tconflict_state\t"
                + "variant_summary_records_json\tsubmissions_json\n";
        String aggregate = "[{\"rcvAccessions\":[\"RCV000000001.1\"],"
                + "\"condition\":{\"name\":\"Condition one\","
                + "\"identifiers\":[\"MONDO:0000001\"]},"
                + "\"classification\":\"Conflicting classifications of pathogenicity\","
                + "\"reviewStatus\":\"criteria provided, conflicting classifications\"}]";
        String submissions = "[{\"scv\":\"SCV000000001.1\","
                + "\"classification\":\"Pathogenic\","
                + "\"condition\":{\"name\":\"Condition one\"},"
                + "\"reviewStatus\":\"criteria provided, single submitter\","
                + "\"dateLastEvaluated\":\"2025-12-01\"}]";
        String rows = "VID\t2\t2\t2\tGRCh38\t1\t100\tA\tT\t[\"GENE1\"]\tCONFLICTING\t"
                + aggregate + "\t" + submissions + "\n";
        if (secondIdentity) rows += "VID\t3\t3\t3\tGRCh38\t1\t99\tC\tT\t[\"GENE1\"]\tCONFLICTING\t"
                + aggregate + "\t" + submissions + "\n";
        Files.writeString(plain, header + rows, StandardCharsets.UTF_8);
        Path compressed = tempDirectory.resolve("comparator-sidecar.tsv.gz");
        run(List.of(executable("bgzip").toString(), "-c", plain.toString()), compressed);
        run(List.of(executable("tabix").toString(), "-f", "-s", "1", "-b", "2",
                "-e", "3", "-S", "1", compressed.toString()), null);
        return new ClinVarComparatorSidecar(mapper, compressed,
                "CLINVAR_2026_03_05_COMPARATOR", "ClinVar 2026-03-05", "abc123");
    }

    private void run(List<String> command, Path output) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (output != null) builder.redirectOutput(output.toFile());
        Path error = tempDirectory.resolve("tool-" + Math.abs(command.hashCode()) + ".stderr");
        builder.redirectError(error.toFile());
        Process process = builder.start();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(Files.readString(error, StandardCharsets.UTF_8));
        }
    }

    private Path executable(String name) {
        String suffix = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? ".exe" : "";
        String override = System.getProperty("varvis.test." + name);
        if (override != null && Files.isRegularFile(Paths.get(override))) return Paths.get(override);
        String resourceRoot = System.getenv("VARVIS_RESOURCE_ROOT");
        if (resourceRoot != null) {
            Path configured = Paths.get(resourceRoot, "Tool", "msys64", "ucrt64", "bin",
                    name + suffix);
            if (Files.isRegularFile(configured)) return configured;
        }
        Path relative = Paths.get("..", "..", "Tool", "msys64", "ucrt64", "bin",
                name + suffix).toAbsolutePath().normalize();
        if (Files.isRegularFile(relative)) return relative;
        throw new IllegalStateException("Set -Dvarvis.test." + name + " to the executable path");
    }

    private ObjectNode context(NormalizedVariantKey key) {
        ObjectNode context = mapper.createObjectNode();
        context.put("schemaVersion", "evidence-context-v2");
        context.put("contextId", "context-sha256-test");
        ObjectNode allele = context.putObject("variant").putObject("evidenceQueryAllele");
        allele.put("assembly", key.getGenomeAssembly());
        allele.put("contig", key.getChromosome());
        allele.put("position", key.getPosition());
        allele.put("reference", key.getReference());
        allele.put("alternate", key.getAlternate());
        context.putObject("gene").put("symbol", "GENE1");
        context.putObject("transcript")
                .put("accession", "ENST000001")
                .put("version", 2)
                .put("annotationSourceVersion", VEP_VERSION);
        return context;
    }

    private TranscriptConsequence consequence(String aminoAcids, String codons) {
        return consequenceAt("600", aminoAcids, codons);
    }

    private TranscriptConsequence consequenceAt(String proteinPosition,
                                                 String aminoAcids,
                                                 String codons) {
        return TranscriptConsequence.ann(
                "GENE1", "1", TRANSCRIPT, "Ensembl", "missense_variant", "MODERATE",
                TRANSCRIPT + ":c.1A>G", "ENSP000001.1:p.Lys600Arg", "1/1", null,
                proteinPosition, aminoAcids, codons, Instant.EPOCH)
                .withAnnotationMetadata("VEP_CACHED", "COMPLETED", VEP_VERSION, Instant.EPOCH);
    }

    private VariantInterpretation variant(NormalizedVariantKey key) {
        return new VariantInterpretation(key.canonicalId(),
                new VariantQcObservation(key, null, null, null, null, null, null,
                        "PASS", "SNV", null),
                Collections.emptyList(), Collections.emptyList());
    }

    private NormalizedVariantKey key(int position, String reference, String alternate) {
        return new NormalizedVariantKey("GRCh38", "1", position, reference, alternate);
    }

    private static final class CountingClinVar {
        private ClinVarTabixProvider provider;
        private int calls;
        private String region;
        private List<String> rows = List.of(
                "1\t100\t1\tA\tG\t.\tPASS\tCLNSIGSCV=TARGET_ONLY",
                "1\t100\t2\tA\tT\t.\tPASS\tCLNSIGSCV=SCV_VCF_1|SCV_VCF_2");
    }

    private final class CountingVep extends VepTranscriptAnnotationProvider {
        private final boolean mismatchProbe;
        private int calls;
        private List<NormalizedVariantKey> batchAlleles = Collections.emptyList();
        private Map<NormalizedVariantKey, List<TranscriptConsequence>> lastAnnotations = new LinkedHashMap<>();
        private String referenceExon = "1/1";
        private boolean extraTranscripts;
        private boolean reverseAnnotations;
        private boolean partialCandidates;

        private CountingVep(boolean mismatchProbe) {
            super(new VariantAnalysisProperties());
            this.mismatchProbe = mismatchProbe;
        }

        @Override
        public Map<NormalizedVariantKey, List<TranscriptConsequence>> annotateBatch(
                List<VariantInterpretation> variants, AnalysisContext context) {
            calls++;
            Map<NormalizedVariantKey, List<TranscriptConsequence>> result =
                    new LinkedHashMap<NormalizedVariantKey, List<TranscriptConsequence>>();
            List<NormalizedVariantKey> observed = new ArrayList<NormalizedVariantKey>();
            for (VariantInterpretation variant : variants) {
                NormalizedVariantKey key = variant.getObservation().getKey();
                observed.add(key);
                if (partialCandidates && key.getPosition() == 99 && "T".equals(key.getAlternate())) continue;
                TranscriptConsequence consequence;
                if (key.getPosition() == 100 && "T".equals(key.getAlternate())) {
                    consequence = withField(consequenceAt("600", "K/N", "cAa/cTa"), "exon", referenceExon);
                } else if (partialCandidates && key.getPosition() == 100 && "C".equals(key.getAlternate())) {
                    consequence = consequenceAt("601", "K/Q", "cAa/cCa");
                } else if (mismatchProbe && key.getPosition() == 99) {
                    consequence = consequenceAt("601", "K/Q", "Caa/Aaa");
                } else if (key.getPosition() == 101) {
                    consequence = consequenceAt("600", "K", "caA/caC");
                } else if (key.getPosition() == 99) {
                    consequence = consequenceAt("600", "K/Q", "Caa/Aaa");
                } else {
                    consequence = consequenceAt("600", "K/R", "cAa/cGa");
                }
                List<TranscriptConsequence> annotated = new ArrayList<>();
                if (extraTranscripts) annotated.add(withField(consequence, "transcriptAccession", "ENST000009.9"));
                annotated.add(consequence);
                if (partialCandidates && key.getPosition() == 101 && "G".equals(key.getAlternate())) annotated.add(consequence);
                if (reverseAnnotations) Collections.reverse(annotated);
                result.put(key, annotated);
            }
            batchAlleles = observed;
            lastAnnotations = result;
            return result;
        }
    }
}
