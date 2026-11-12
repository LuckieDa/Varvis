package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Reusable CURRENT18 test inputs and genuine PVS1 loaders, never evaluated output.
 * All reference bases, alleles, source originals, assay facts and policies are TEST_ONLY
 * synthetic fixtures. A parsed source qualification does not make these human data.
 * The caller owns the normal service, population profile and context confirmation.
 */
public final class Pvs1UnifiedEighteenFixture {
    public final Pvs1NmdBusinessFixture data;
    public final ReviewedRuleBundle bundle;
    public final Pvs1KnowledgeCatalog knowledge;
    public final Pvs1TranscriptMaterialSupply materialSupply;
    public final Path root, fastaPath, fastaIndexPath, aliasFile, modelDirectory, modelManifestFile,
            bundleFile, knowledgeFactsFile, knowledgeAdoptionFile;
    public final String mode, referenceSha256, fastaIndexSha256, modelManifestSha256,
            bundleRawSha256, bundleCanonicalSha256, knowledgeFactsSha256, knowledgeAdoptionSha256;
    public final AtomicInteger referenceReads = new AtomicInteger();
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode document, knowledgeFacts, adoption;
    private final Map<String, String> referenceContigs;
    private final Map<String, byte[]> originals = new LinkedHashMap<>();
    private static final List<String> MODES = List.of("INFRAME", "ESCAPE", "NMD", "NEGATIVE_INFRAME", "NEGATIVE_ESCAPE",
            "MISSING_ESCAPE_SCOPE", "MISSING_RESIDUAL_RNA", "MISSING_REVIEW", "WRONG_SOURCE_PIN", "WRONG_MODEL_PIN",
            "WRONG_OUTCOME_PIN", "WRONG_TARGET", "UNRESOLVED_SPLICE_PRODUCTS", "WRONG_SPLICE_REFERENCE_WINDOW");

    public Pvs1UnifiedEighteenFixture(ObjectMapper mapper, Path root, String mode) throws Exception {
        this(mapper, root, mode, ignored -> { });
    }

    /** finalCustomizer executes before reference generation, identity pinning and source
     * capture, allowing caller-owned synthetic gene/disease/ranking backgrounds. It must
     * keep the two models, policies, annotations and physical alleles mutually consistent.
     * Failure modes affect candidate0 only; candidate1 remains the positive control.
     */
    public Pvs1UnifiedEighteenFixture(ObjectMapper mapper, Path root, String mode,
                                     Consumer<Pvs1NmdBusinessFixture> finalCustomizer) throws Exception {
        if (mapper == null || root == null || finalCustomizer == null || !MODES.contains(mode))
            throw new IllegalArgumentException("TEST_ONLY_PVS1_UNIFIED_FIXTURE_ARGUMENT_INVALID");
        this.mapper = mapper;this.adapter = new RuntimeEvidenceV2Adapter(mapper);this.mode = mode;
        this.root = root.toAbsolutePath().normalize();Files.createDirectories(this.root);
        data = new Pvs1NmdBusinessFixture(mapper, false);
        for (int i = 0; i < 2; i++) configure(i);
        finalCustomizer.accept(data);
        for (int i = 0; i < 2; i++) {
            var model = data.models.get(i);String cds = model.path("cdsSequence").asText();
            // These toy models have full RNA exactly equal to CDS; no UTR proof is inferred.
            for (var exon : model.path("exons")) if (exon.path("cdsStart").asInt() < 1
                    || exon.path("cdsEnd").asInt() > cds.length())
                throw new IllegalArgumentException("TEST_ONLY_FIXTURE_UTR_SEQUENCE_NOT_PROVIDED");
            model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(cds))
                    .put("cdsSha256", CodingTranscriptMaterialSupply.sha(cds));
            var captured = Pvs1SpliceSourceQualificationTest.configureOriginals(mapper, data.policies.get(i), model, outcomes(i));
            for (var entry : captured.entrySet()) {
                byte[] prior = originals.putIfAbsent(entry.getKey(), entry.getValue());
                if (prior != null && !java.util.Arrays.equals(prior, entry.getValue()))
                    throw new IllegalArgumentException("TEST_ONLY_SOURCE_ID_COLLISION");
            }
        }
        // Each output is create-new: a second construction cannot silently replace pinned inputs.
        fastaPath = this.root.resolve("test-only-reference.fa");fastaIndexPath = this.root.resolve("test-only-reference.fa.fai");
        aliasFile = this.root.resolve("test-only-contig-alias.tsv");
        referenceContigs = buildReference();writeFasta();
        referenceSha256 = CodingTranscriptMaterialSupply.sha(Files.readAllBytes(fastaPath));
        fastaIndexSha256 = CodingTranscriptMaterialSupply.sha(Files.readAllBytes(fastaIndexPath));
        var aliases = new StringBuilder();for (String contig : referenceContigs.keySet()) aliases.append(contig).append('\t').append("chr").append(contig).append('\n');
        create(aliasFile, aliases.toString().getBytes(StandardCharsets.US_ASCII));
        modelDirectory = this.root.resolve("models");Files.createDirectories(modelDirectory);
        modelManifestFile = modelDirectory.resolve("manifest.json");
        var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        var rows = manifest.putArray("entries");
        for (var model : data.models) {
            String tx = model.path("transcript").asText();Path file = modelDirectory.resolve(tx + ".model.json");
            byte[] bytes = mapper.writeValueAsBytes(model);create(file, bytes);
            rows.addObject().put("transcript", tx).put("file", file.getFileName().toString()).put("fileBytes", bytes.length)
                    .put("fileSha256", CodingTranscriptMaterialSupply.sha(bytes)).put("canonicalSha256", adapter.canonicalSha256(model))
                    .put("modelId", model.path("modelId").asText()).put("modelVersion", model.path("modelVersion").asText());
        }
        byte[] manifestBytes = mapper.writeValueAsBytes(manifest);create(modelManifestFile, manifestBytes);
        modelManifestSha256 = CodingTranscriptMaterialSupply.sha(manifestBytes);
        document = data.document();
        if (mode.equals("NMD")) document.withObject("materials").putArray("pvs1SpliceOutcomes");
        knowledgeFacts = Pvs1KnowledgeCatalogTest.facts(mapper, data.policies);
        adoption = Pvs1SpliceSourceQualificationTest.adoption(mapper, knowledgeFacts, data.policies);
        for (int i = 0; i < 2; i++) {
            var qualification = Pvs1SpliceSourceQualificationTest.qualification(mapper, this.root.resolve("source-originals-" + i),
                    data.policies.get(i), data.models.get(i), outcomes(i), originals);
            ((ObjectNode) adoption.at("/entries/" + i)).set("qualification", qualification);
        }
        knowledgeFactsFile = this.root.resolve("source-candidates.json");knowledgeAdoptionFile = this.root.resolve("source-adoption.json");
        byte[] factsBytes = mapper.writeValueAsBytes(knowledgeFacts);create(knowledgeFactsFile, factsBytes);
        knowledgeFactsSha256 = CodingTranscriptMaterialSupply.sha(factsBytes);adoption.put("sourceCandidatesSha256", knowledgeFactsSha256);
        if (mode.equals("WRONG_SOURCE_PIN"))
            ((ObjectNode) adoption.at("/entries/0/qualification/sourceVerification/0/source")).put("sha256", "f".repeat(64));
        byte[] adoptionBytes = mapper.writeValueAsBytes(adoption);create(knowledgeAdoptionFile, adoptionBytes);
        knowledgeAdoptionSha256 = CodingTranscriptMaterialSupply.sha(adoptionBytes);
        // Corruption controls are deliberately applied after capturing the correct originals.
        // No qualification witness is rebound to make a substituted model/outcome pass.
        if (mode.equals("WRONG_MODEL_PIN")) {
            String pin = adapter.canonicalSha256(data.models.get(0));
            ((ObjectNode) document.at("/materials/transcriptModels").path(pin)).put("modelVersion", "TEST_ONLY_TAMPERED");
        } else if (mode.equals("WRONG_OUTCOME_PIN"))
            ((ObjectNode) document.at("/materials/pvs1SpliceOutcomes/0")).put("transcriptModelSha256", "f".repeat(64));
        else if (mode.equals("WRONG_TARGET")) {
            var target = (ObjectNode) document.at("/materials/pvs1SpliceOutcomes/0/allele");
            target.put("alternate", "T".equals(target.path("alternate").asText()) ? "A" : "T");
        }
        bundleFile = this.root.resolve("test-only-pvs1-bundle.json");byte[] bundleBytes = mapper.writeValueAsBytes(document);create(bundleFile, bundleBytes);
        bundleRawSha256 = CodingTranscriptMaterialSupply.sha(bundleBytes);bundleCanonicalSha256 = adapter.canonicalSha256(document);
        knowledge = Pvs1KnowledgeCatalog.load(mapper, knowledgeFactsFile, knowledgeFactsSha256, knowledgeAdoptionFile, knowledgeAdoptionSha256);
        var common = new CodingTranscriptMaterialSupply(mapper, modelDirectory, modelManifestSha256, this::readReference);
        materialSupply = new Pvs1TranscriptMaterialSupply(mapper, common);
        bundle = ReviewedRuleBundle.loadPvs1Selected(mapper, bundleFile, bundleRawSha256, bundleCanonicalSha256, materialSupply, knowledge);
    }

    private void configure(int i) {
        if (mode.equals("NMD")) {
            // The v4 source-qualified inventory is explicitly empty for an ordinary
            // stop-gained allele; there is no manufactured RNA event.
            var policy = data.policies.get(i);policy.put("schemaVersion", Pvs1SpliceConsequence.SCHEMA);
            var scope = policy.putObject("spliceScope").put("status", "REVIEWED_FOR_PVS1_SPLICE")
                    .put("reviewId", "TEST_ONLY_EMPTY_RNA_INVENTORY").put("maximumStrength", "VERY_STRONG")
                    .put("basis", "TEST_ONLY ordinary truncating scope; no observed RNA outcome asserted");
            scope.putArray("sourceIds").add("synthetic-source");scope.putArray("evidenceKinds").add("OBSERVED_RNA");
            scope.putArray("eventTypes").add("EXON_SKIPPING").add("EXON_SHORTENING");
            data.expected.get(i).put("status", "MET");return;
        }
        boolean inFrame = mode.equals("INFRAME") || mode.equals("NEGATIVE_INFRAME");
        var outcome = inFrame ? Pvs1SpliceRound3Fixture.configure(data, i, "INFRAME") : escape(i);
        if (i == 0) switch (mode) {
            case "NEGATIVE_INFRAME" -> {
                ((ObjectNode) data.policies.get(i).at("/spliceScope/inFrameScope/exonAssessments/1")).put("biologicalRelevance", "ABSENT");
                data.policies.get(i).withObject("nmdScope").putArray("relevantExonOrdinals").add(1).add(3).add(4);
                data.policies.get(i).withObject("nmdScope").putArray("engineeringSupportedExonOrdinals").add(1).add(3);
                expected(i, "NOT_MET", "NONE", "PVS1_SPLICE_INFRAME_EXON_NOT_RELEVANT");
            }
            case "NEGATIVE_ESCAPE" -> {
                for (var exon : data.policies.get(i).at("/escapeScope/exonAssessments")) ((ObjectNode) exon).put("populationLof", "FREQUENT");
                expected(i, "NOT_MET", "NONE", "PVS1_ESCAPE_EXON_LOF_TOLERANT");
            }
            case "MISSING_ESCAPE_SCOPE" -> {
                data.policies.get(i).remove("escapeScope");expected(i, "NOT_EVALUATED", "NONE", "PVS1_NMD_ESCAPE_BRANCH_NOT_IMPLEMENTED");
            }
            case "MISSING_RESIDUAL_RNA" -> {
                outcome.withObject("review").put("residualNormalTranscript", "UNKNOWN");
                expected(i, "NOT_EVALUATED", "NONE", "PVS1_SPLICE_RESIDUAL_TRANSCRIPT_UNRESOLVED");
            }
            case "UNRESOLVED_SPLICE_PRODUCTS" -> {
                outcome.withObject("review").put("outcomeMultiplicity", "MULTIPLE_OUTCOMES");
                expected(i, "NOT_EVALUATED", "NONE", "PVS1_SPLICE_OUTCOME_UNRESOLVED");
            }
            case "WRONG_SPLICE_REFERENCE_WINDOW" ->
                expected(i, "NOT_EVALUATED", "NONE", "PVS1_SPLICE_CURRENT_REFERENCE_WINDOW_MISMATCH");
            case "MISSING_REVIEW" -> {
                outcome.withObject("review").put("status", "UNREVIEWED");
                expected(i, "NOT_EVALUATED", "NONE", "PVS1_SOURCE_QUALIFICATION_SPLICE_REVIEW_REQUIRED");
            }
            case "WRONG_MODEL_PIN" -> expected(i, "NOT_EVALUATED", "NONE", "REVIEWED_PVS1_MATERIAL_IDENTITY_UNAVAILABLE");
            case "WRONG_SOURCE_PIN" -> expected(i, "NOT_EVALUATED", "NONE", "PVS1_SOURCE_QUALIFICATION_BYTES_REQUIRED");
            case "WRONG_OUTCOME_PIN" -> expected(i, "NOT_EVALUATED", "NONE", "PVS1_SPLICE_MODEL_IDENTITY_MISMATCH");
            case "WRONG_TARGET" -> expected(i, "NOT_EVALUATED", "NONE", "PVS1_SPLICE_OUTCOME_MATERIAL_REQUIRED");
            default -> { }
        }
        data.spliceOutcomes.add(outcome);
    }

    private ObjectNode escape(int i) {
        var outcome = Pvs1SpliceConsequenceTest.configure(data, i, true, false);
        var model = data.models.get(i);var policy = data.policies.get(i);var exons = model.putArray("exons");
        int start = 1;int[] ends = {12, 25, 62, 76, 600};
        for (int j = 0; j < ends.length; j++) {
            int genomic = i == 0 ? 2000 + j * 1000 : 20000 - j * 1000;
            exons.addObject().put("ordinal", j + 1).put("cdsStart", start).put("cdsEnd", ends[j])
                    .put("genomicStart", genomic).put("genomicEnd", genomic + ends[j] - start);start = ends[j] + 1;
        }
        policy.withObject("nmdScope").putArray("relevantExonOrdinals").add(1).add(2).add(3).add(4).add(5);
        policy.withObject("nmdScope").putArray("engineeringSupportedExonOrdinals").add(1).add(2).add(3).add(4);
        policy.withObject("escapeScope").withArray("exonAssessments").addObject().put("ordinal", 5)
                .put("biologicalRelevance", "PRESENT").put("populationLof", "NOT_FREQUENT")
                .put("basis", "TEST_ONLY final exon source fact after the independently fixed topology split")
                .putArray("sourceIds").add("synthetic-source");
        outcome.put("transcriptModelSha256", adapter.canonicalSha256(model));
        data.expected.get(i).put("status", "MET").put("strength", "STRONG");return outcome;
    }

    private void expected(int i, String status, String strength, String reason) {
        data.expected.get(i).put("status", status).put("strength", strength).put("reasonCode", reason);
    }
    private List<ObjectNode> outcomes(int i) { return data.spliceOutcomes.isEmpty() ? List.of() : List.of(data.spliceOutcomes.get(i)); }

    public Pvs1SelectedBatch selectedBatch() { return new Pvs1SelectedBatch(mapper, bundle); }
    public CodingTranscriptMaterialSupply codingMaterialSupply() throws IOException {
        return new CodingTranscriptMaterialSupply(mapper, modelDirectory, modelManifestSha256, this::readReference);
    }
    /** Explicit PM4/BP3 code-bound A5 capability, kept separate from the ordinary supply. */
    public CodingTranscriptMaterialSupply proteinLengthMaterialSupply() throws IOException {
        return ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper, modelDirectory, modelManifestSha256, this::readReference, null);
    }
    public ObjectNode document() { return document.deepCopy(); }
    public ObjectNode knowledgeFacts() { return knowledgeFacts.deepCopy(); }
    public ObjectNode adoption() { return adoption.deepCopy(); }
    public TranscriptConsequence annotation(int i) throws Exception { return data.annotation(i); }
    public VariantInterpretation candidate(int i) throws Exception {
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(data.alleles.get(i));candidate.applyTranscriptAnnotations(List.of(annotation(i)));return candidate;
    }

    /** Actual factory/config file pins; no caller-trust profile is manufactured. */
    public Map<String, String> properties() {
        var values = new LinkedHashMap<String, String>();String prefix = "variant-analysis.acmg-business.";
        values.put(prefix + "pvs1-bundle-file", bundleFile.toString());values.put(prefix + "pvs1-bundle-file-sha256", bundleRawSha256);
        values.put(prefix + "pvs1-bundle-sha256", bundleCanonicalSha256);values.put(prefix + "pvs1-selected-knowledge-enabled", "true");
        values.put(prefix + "pvs1-knowledge-source-file", knowledgeFactsFile.toString());values.put(prefix + "pvs1-knowledge-source-sha256", knowledgeFactsSha256);
        values.put(prefix + "pvs1-knowledge-adoption-file", knowledgeAdoptionFile.toString());values.put(prefix + "pvs1-knowledge-adoption-sha256", knowledgeAdoptionSha256);
        values.put(prefix + "pvs1-local-material-supply-enabled", "true");values.put(prefix + "transcript-material-directory", modelDirectory.toString());
        values.put(prefix + "transcript-material-manifest-sha256", modelManifestSha256);return Map.copyOf(values);
    }

    public ObjectNode pinnedFiles() {
        var out = mapper.createObjectNode().put("dataClass", "TEST_ONLY_SYNTHETIC_REFERENCE_AND_ASSAY")
                .put("mode", mode).put("createsExpertReview", false).put("clinicalAcceptance", false);
        out.putObject("reference").put("path", fastaPath.toString()).put("sha256", referenceSha256)
                .put("indexPath", fastaIndexPath.toString()).put("indexSha256", fastaIndexSha256).put("aliasFile", aliasFile.toString());
        out.putObject("models").put("path", modelManifestFile.toString()).put("sha256", modelManifestSha256);
        out.putObject("bundle").put("path", bundleFile.toString()).put("rawSha256", bundleRawSha256).put("canonicalSha256", bundleCanonicalSha256);
        out.putObject("knowledge").put("factsPath", knowledgeFactsFile.toString()).put("factsSha256", knowledgeFactsSha256)
                .put("adoptionPath", knowledgeAdoptionFile.toString()).put("adoptionSha256", knowledgeAdoptionSha256);
        return out;
    }

    private Map<String, String> buildReference() {
        var assigned = new LinkedHashMap<String, Map<Integer, Character>>();
        for (int i = 0; i < data.models.size(); i++) {
            var model = data.models.get(i);String contig = model.path("contig").asText(), cds = model.path("cdsSequence").asText();
            for (var exon : model.path("exons")) for (int g = exon.path("genomicStart").asInt(); g <= exon.path("genomicEnd").asInt(); g++) {
                int c = CodingTranscriptMapping.cdsPosition(model, g);
                putBase(assigned, contig, g, CodingTranscriptMapping.orientAllele(model, cds.substring(c - 1, c)).charAt(0));
            }
            if (!data.spliceOutcomes.isEmpty()) {
                var window = data.spliceOutcomes.get(i).path("referenceWindow");String sequence = window.path("sequence").asText();
                for (int j = 0; j < sequence.length(); j++) {
                    int position = window.path("start").asInt() + j;
                    char base = sequence.charAt(j);
                    // Keep the allele REF and the verified exons intact; change the other intronic motif base.
                    if (i == 0 && "WRONG_SPLICE_REFERENCE_WINDOW".equals(mode) && position != data.alleles.get(i).getPosition())
                        base = base == 'A' ? 'T' : 'A';
                    putBase(assigned, contig, position, base);
                }
            }
            var allele = data.alleles.get(i);
            for (int j = 0; j < allele.getReference().length(); j++)
                putBase(assigned, contig, allele.getPosition() + j, allele.getReference().charAt(j));
        }
        var result = new LinkedHashMap<String, String>();
        for (var entry : assigned.entrySet()) {
            int maximum = entry.getValue().keySet().stream().mapToInt(mappedItem -> mappedItem.intValue()).max().orElseThrow();
            if (maximum > 100_000) throw new IllegalArgumentException("TEST_ONLY_TINY_REFERENCE_LIMIT");
            var bases = new StringBuilder("A".repeat(maximum + 30));
            entry.getValue().forEach((position, base) -> bases.setCharAt(position - 1, base));result.put(entry.getKey(), bases.toString());
        }
        return Collections.unmodifiableMap(result);
    }
    private static void putBase(Map<String, Map<Integer, Character>> assigned, String contig, int position, char base) {
        if (position < 1 || "ACGT".indexOf(base) < 0) throw new IllegalArgumentException("TEST_ONLY_REFERENCE_BASE_INVALID");
        var bases = assigned.computeIfAbsent(contig, ignored -> new LinkedHashMap<>());Character prior = bases.putIfAbsent(position, base);
        if (prior != null && prior != base) throw new IllegalArgumentException("TEST_ONLY_REFERENCE_SOURCE_CONFLICT");
    }
    private void writeFasta() throws IOException {
        var bytes = new ByteArrayOutputStream();var index = new StringBuilder();
        for (var entry : referenceContigs.entrySet()) {
            bytes.write(('>' + entry.getKey() + '\n').getBytes(StandardCharsets.US_ASCII));int offset = bytes.size();String bases = entry.getValue();
            index.append(entry.getKey()).append('\t').append(bases.length()).append('\t').append(offset).append("\t60\t61\n");
            for (int i = 0; i < bases.length(); i += 60) bytes.write((bases.substring(i, Math.min(i + 60, bases.length())) + '\n').getBytes(StandardCharsets.US_ASCII));
        }
        create(fastaPath, bytes.toByteArray());create(fastaIndexPath, index.toString().getBytes(StandardCharsets.US_ASCII));
    }
    public CodingTranscriptMaterialSupply.ReferenceSpan readReference(String contig, int start, int length) throws IOException {
        referenceReads.incrementAndGet();
        if (!CodingTranscriptMaterialSupply.sha(Files.readAllBytes(fastaPath)).equals(referenceSha256)
                || !CodingTranscriptMaterialSupply.sha(Files.readAllBytes(fastaIndexPath)).equals(fastaIndexSha256))
            throw new IOException("TEST_ONLY_CURRENT_REFERENCE_BYTES_CHANGED");
        String bases = referenceContigs.get(contig.startsWith("chr") ? contig.substring(3) : contig);
        if (bases == null || start < 1 || length < 1 || (long) start + length - 1 > bases.length())
            throw new IOException("TEST_ONLY_REFERENCE_REGION_INVALID");
        return new CodingTranscriptMaterialSupply.ReferenceSpan(bases.substring(start - 1, start - 1 + length), mapper.createObjectNode()
                .put("version", "TEST_ONLY_UNIFIED18_REFERENCE_V1").put("expectedSha256", referenceSha256).put("fastaIndexSha256", fastaIndexSha256));
    }
    private static void create(Path path, byte[] bytes) throws IOException { Files.write(path, bytes, StandardOpenOption.CREATE_NEW); }
}
