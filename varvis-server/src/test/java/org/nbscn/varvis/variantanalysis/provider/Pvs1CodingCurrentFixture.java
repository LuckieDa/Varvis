package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

/** Actual offline source compiler and selected loader on explicitly fictional coding inputs.
 * New source-policy originals and independent TEST_ONLY conditions witnesses are written before
 * compilation. They attest fixture authorship/use, never human assay findings or expert review.
 * Existing STOP/SIMPLE/COMPLEX sequence vectors, v3 rules and caps are reused without rescoring.
 */
public final class Pvs1CodingCurrentFixture {
    public final Pvs1NmdBusinessFixture data;
    public final ReviewedRuleBundle bundle;
    public final Pvs1KnowledgeCatalog knowledge;
    public final Pvs1TranscriptMaterialSupply materialSupply;
    public final Path root, fastaPath, fastaIndexPath, aliasFile, modelDirectory, modelManifestFile,
            bundleFile, knowledgeFactsFile, knowledgeAdoptionFile, assemblyConfig,
            assemblyOutputDirectory, assemblyLog, assemblyScript, python;
    public final String mode, referenceSha256, fastaIndexSha256, modelManifestSha256,
            bundleRawSha256, bundleCanonicalSha256, knowledgeFactsSha256, knowledgeAdoptionSha256,
            assemblyConfigSha256;
    public final ObjectNode assemblyAudit;
    public final AtomicInteger referenceReads = new AtomicInteger();
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final Map<String, String> referenceContigs;
    private final Map<Path, String> originalPins = new LinkedHashMap<>();
    private final ObjectNode document, facts, adoption;

    public Pvs1CodingCurrentFixture(ObjectMapper mapper, Path root, String mode,
            Consumer<Pvs1NmdBusinessFixture> mapping, Path python) throws Exception {
        if (mapper == null || root == null || mapping == null || python == null
                || !List.of("STOP", "SIMPLE", "COMPLEX").contains(mode))
            throw new IllegalArgumentException("TEST_ONLY_CODING_FIXTURE_ARGUMENT_INVALID");
        this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper); this.mode = mode;
        this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        this.python = python.toRealPath(); assemblyScript = assembler();
        if (!Files.isRegularFile(this.python)) throw new IOException("TEST_ONLY_EXISTING_PYTHON_REQUIRED");
        data = new Pvs1NmdBusinessFixture(mapper, !"STOP".equals(mode), true);
        if ("COMPLEX".equals(mode)) configureComplex();
        var retainedConditions = data.policies.stream().map(this::conditionSnapshot).toList();
        mapping.accept(data);
        // Mapping is performed before all source/model/policy bytes and their pins are authored.
        if (data.models.size() != 2 || data.policies.size() != 2 || data.annotations.size() != 2
                || data.alleles.size() != 2 || !data.spliceOutcomes.isEmpty())
            throw new IllegalArgumentException("TEST_ONLY_TWO_CODING_BACKGROUNDS_REQUIRED");
        for (int i = 0; i < 2; i++) if (!retainedConditions.get(i).equals(conditionSnapshot(data.policies.get(i))))
            throw new IllegalArgumentException("TEST_ONLY_BACKGROUND_MAPPING_CANNOT_CHANGE_EXISTING_RULE_CONDITIONS");
        for (int i = 0; i < 2; i++) authorSourcePolicy(i);

        fastaPath = this.root.resolve("test-only-coding-reference.fa");
        fastaIndexPath = this.root.resolve("test-only-coding-reference.fa.fai");
        aliasFile = this.root.resolve("test-only-contig-alias.tsv");
        referenceContigs = buildReference(); writeFasta();
        referenceSha256 = rawSha(fastaPath); fastaIndexSha256 = rawSha(fastaIndexPath);
        var aliases = new StringBuilder();
        referenceContigs.keySet().forEach(contig -> aliases.append(contig).append('\t').append("chr").append(contig).append('\n'));
        create(aliasFile, aliases.toString().getBytes(StandardCharsets.US_ASCII));

        modelDirectory = this.root.resolve("models"); Files.createDirectories(modelDirectory);
        modelManifestFile = modelDirectory.resolve("manifest.json");
        var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        var resources = mapper.createArrayNode();
        for (var model : data.models) {
            String tx = model.path("transcript").asText(); Path file = modelDirectory.resolve(tx + ".model.json");
            create(file, mapper.writeValueAsBytes(model));
            manifest.withArray("entries").addObject().put("transcript", tx).put("file", file.getFileName().toString())
                    .put("fileBytes", Files.size(file)).put("fileSha256", rawSha(file)).put("canonicalSha256", hash(model))
                    .put("modelId", model.path("modelId").asText()).put("modelVersion", model.path("modelVersion").asText());
            resources.addObject().put("relativePath", "models/" + file.getFileName()).put("canonicalSha256", hash(model));
        }
        create(modelManifestFile, mapper.writeValueAsBytes(manifest)); modelManifestSha256 = rawSha(modelManifestFile);

        // data.document only binds the established model/policy/catalog format; it does not evaluate.
        ObjectNode seed = data.document(); ObjectNode catalog = (ObjectNode) seed.path("catalog");
        catalog.putArray("sources");
        for (int i = 0; i < 2; i++) {
            catalog.withArray("sources").add(data.policies.get(i).at("/scientificSources/0").deepCopy());
            ((ObjectNode) catalog.at("/entries/" + i)).putArray("sourceIds")
                    .add(data.policies.get(i).at("/scientificSources/0/sourceId").asText());
        }
        catalog.put("catalogId", "TEST_ONLY_CODING_" + mode).put("searchScope",
                "Exact fictional input-policy inventory only; no real-gene search or scientific adoption");
        ObjectNode candidate = mapper.createObjectNode().put("schemaVersion", "pvs1-truncating-nmd-bundle-candidate-v1")
                .put("dataClass", "TEST_ONLY_SYNTHETIC_ASSAY_POLICY_SCOPE");
        candidate.set("policies", seed.path("policies").deepCopy()); candidate.set("catalog", catalog);
        candidate.put("catalogSha256", hash(catalog)); candidate.set("activation", seed.path("activation").deepCopy());
        candidate.set("runtimeResourceBinding", seed.at("/materials/pvs1ResourceProfile").deepCopy());
        candidate.set("transcriptModelResources", resources);
        for (int i = 0; i < 2; i++) {
            var policy = data.policies.get(i);
            var review = mapper.createObjectNode().put("schemaVersion", "test-only-pvs1-coding-source-policy-witness-v1")
                    .put("scope", "TEST_ONLY_SYNTHETIC_ASSAY_POLICY_SCOPE").put("purpose", "PROJECT_RESEARCH")
                    .put("reviewId", policy.at("/review/reviewId").asText()).put("witnessRole", "FIXTURE_AUTHOR_CONDITIONS_AND_USE_WITNESS")
                    .put("expertReviewCreated", false).put("clinicalAcceptance", false).put("realPatientData", false)
                    .put("policySha256", hash(policy)).put("basis",
                            "Independent pre-execution witness of these explicit fictional source-policy conditions; not an expert or clinical approval.");
            review.set("originalPolicy", policy.deepCopy()); review.set("sourceOriginal", policy.at("/scientificSources/0").deepCopy());
            review.putObject("conditionsPreserved").setAll(conditionSnapshot(policy));
            Path reviewFile = this.root.resolve("test-only-policy-witness-" + i + ".json");
            create(reviewFile, mapper.writeValueAsBytes(review));
            candidate.withArray("sourceReviewBindings").addObject().put("file", reviewFile.getFileName().toString()).put("sha256", rawSha(reviewFile));
            create(this.root.resolve("test-only-policy-" + i + ".json"), mapper.writeValueAsBytes(policy));
        }
        Path candidateFile = this.root.resolve("test-only-original-candidate.json"); create(candidateFile, mapper.writeValueAsBytes(candidate));
        facts = mechanismFacts(); knowledgeFactsFile = this.root.resolve("test-only-mechanism-facts.json");
        create(knowledgeFactsFile, mapper.writeValueAsBytes(facts)); knowledgeFactsSha256 = rawSha(knowledgeFactsFile);
        var sourceConfig = mapper.createObjectNode().put("schemaVersion", "pvs1-material-plan-input-v1");
        sourceConfig.set("mechanisms", fileBinding(knowledgeFactsFile));
        sourceConfig.putArray("candidates").addObject().put("file", candidateFile.toString()).put("sha256", rawSha(candidateFile))
                .put("resourceRoot", this.root.toString());
        Path sourceConfigFile = this.root.resolve("test-only-materials-config.json"); create(sourceConfigFile, mapper.writeValueAsBytes(sourceConfig));
        Path profileFile = this.root.resolve("resource-profile.json"); create(profileFile, mapper.writeValueAsBytes(seed.at("/materials/pvs1ResourceProfile")));
        var config = mapper.createObjectNode().put("schemaVersion", "pvs1-source-qualified-assembly-input-v1");
        config.set("materialsConfig", fileBinding(sourceConfigFile)); config.set("resourceProfile", fileBinding(profileFile));
        config.putObject("sourceQualification").put("policyId", "TEST_ONLY_CODING_SOURCE_USE_" + mode).put("version", "1.0.0")
                .put("purpose", "PROJECT_RESEARCH").put("clinicalAcceptance", false).put("createsExpertReview", false)
                .putArray("allowedDataClasses").add("SYNTHETIC").add("PRIVACY_BOUNDED_RUNTIME");
        config.putObject("release").put("bindingId", "TEST_ONLY_CODING_CURRENT_" + mode).put("assembledAt", "2026-11-04T00:00:00Z");
        assemblyConfig = this.root.resolve("test-only-assembly-config.json"); create(assemblyConfig, mapper.writeValueAsBytes(config));
        assemblyConfigSha256 = rawSha(assemblyConfig); assemblyOutputDirectory = this.root.resolve("assembled");
        assemblyLog = this.root.resolve("assembled.log"); assemblyAudit = reassembleInto(assemblyOutputDirectory);
        if (assemblyAudit.path("boundPolicies").asInt(-1) != 2 || assemblyAudit.path("blockedPolicies").asInt(-1) != 0)
            throw new IOException("TEST_ONLY_CODING_POLICY_COMPILATION_BLOCKED: " + assemblyAudit.toString());
        bundleFile = assemblyOutputDirectory.resolve("bundle.json"); knowledgeAdoptionFile = assemblyOutputDirectory.resolve("knowledge-adoption.json");
        document = readObject(bundleFile); adoption = readObject(knowledgeAdoptionFile);
        bundleRawSha256 = rawSha(bundleFile); bundleCanonicalSha256 = hash(document); knowledgeAdoptionSha256 = rawSha(knowledgeAdoptionFile);
        knowledge = Pvs1KnowledgeCatalog.load(mapper, knowledgeFactsFile, knowledgeFactsSha256, knowledgeAdoptionFile, knowledgeAdoptionSha256);
        materialSupply = new Pvs1TranscriptMaterialSupply(mapper, codingMaterialSupply());
        bundle = ReviewedRuleBundle.loadPvs1Selected(mapper, bundleFile, bundleRawSha256, bundleCanonicalSha256, materialSupply, knowledge);
    }

    private void configureComplex() throws Exception {
        var fixture = new TruncatingNmdPvs1RuntimeTest();
        for (int i = 0; i < 2; i++) {
            int strand = i == 0 ? 1 : -1; var original = data.models.get(i);
            // Exact independently expected vector already used in Pvs1EscapeAdoptionProofTest.
            var input = fixture.frameshift(false, strand, 100);
            var annotation = fixture.annotation(input, "frameshift_variant", "31_32delinsG", "Pro11AspfsTer11", "3/10", "11", "P/X", "CCA/GA");
            fixture.candidate(input, fixture.genomic(input.model, strand == 1 ? 31 : 32), strand == 1 ? "CC" : "GG", strand == 1 ? "G" : "C", annotation);
            TruncatingNmdPolicyComplexScopeTest.enable(input.policy, 50);
            var scope = Pvs1NmdEscapeTest.scope(input.policy, 10); Pvs1NmdEscapeTest.critical(scope, 160, 190, 10, "STRONG");
            input.policy.put("appliedStrength", "MODERATE");
            String oldTx = input.model.path("transcript").asText(), oldProtein = input.model.path("protein").asText();
            for (String key : List.of("modelId", "modelVersion", "hgncId", "geneSymbol", "contig", "transcript", "refseqTranscript", "protein"))
                input.model.set(key, original.path(key).deepCopy());
            ObjectNode policyScope = (ObjectNode) input.policy.path("scope"), priorScope = (ObjectNode) data.policies.get(i).path("scope");
            for (String key : List.of("hgncId", "mondoId", "moi", "transcripts")) policyScope.set(key, priorScope.path(key).deepCopy());
            input.policy.put("policyId", data.policies.get(i).path("policyId").asText());
            annotation.put("geneSymbol", original.path("geneSymbol").asText()).put("transcriptAccession", original.path("transcript").asText())
                    .put("hgvsCoding", annotation.path("hgvsCoding").asText().replace(oldTx, original.path("transcript").asText()))
                    .put("hgvsProtein", annotation.path("hgvsProtein").asText().replace(oldProtein, original.path("protein").asText()));
            var allele = input.candidate.getObservation().getKey();
            data.models.set(i, input.model); data.policies.set(i, input.policy); data.annotations.set(i, annotation);
            data.alleles.set(i, new NormalizedVariantKey("GRCh38", original.path("contig").asText(), allele.getPosition(), allele.getReference(), allele.getAlternate()));
            data.expected.set(i, mapper.createObjectNode().put("dataClass", "TEST_ONLY").put("kind", "COMPLEX_DELINS")
                    .put("ptcStart", 61).put("ptcEnd", 63).put("lastJunction", 99).put("distanceToLastJunction", 36)
                    .put("ptcReferenceStart", 62).put("ptcReferenceEnd", 64).put("firstAlteredResidue", 11)
                    .put("alteredProteinLength", 20).put("referenceResiduesAffected", 189).put("netProteinShortening", 179)
                    .put("strength", "MODERATE").put("basis", "Existing independent c31_32delinsG vector from Pvs1EscapeAdoptionProofTest; not runtime output"));
        }
    }

    private void authorSourcePolicy(int i) throws IOException {
        var model = data.models.get(i); var policy = data.policies.get(i); String sourceId = "TEST_ONLY_CODING_SOURCE_" + i;
        if (!"pvs1-truncating-nmd-policy-v3".equals(policy.path("schemaVersion").asText()))
            throw new IllegalArgumentException("TEST_ONLY_EXISTING_V3_POLICY_REQUIRED");
        String cds = model.path("cdsSequence").asText();
        model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", CodingTranscriptMaterialSupply.sha(cds))
                .put("cdsSha256", CodingTranscriptMaterialSupply.sha(cds));
        replaceSourceIds(policy, sourceId);
        String reviewId = "TEST_ONLY_PREDECLARED_CODING_POLICY_" + mode + "_" + i;
        policy.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", reviewId)
                .put("kind", "TEST_ONLY_SYNTHETIC_ASSAY_POLICY_SCOPE").put("purpose", "PROJECT_RESEARCH")
                .put("createsExpertReview", false).put("clinicalAcceptance", false);
        ((ObjectNode) policy.path("escapeScope")).put("reviewId", reviewId + "_ESCAPE");
        var source = mapper.createObjectNode().put("schemaVersion", "test-only-pvs1-coding-policy-original-v1")
                .put("dataClass", "SYNTHETIC").put("scope", "TEST_ONLY_SYNTHETIC_ASSAY_POLICY_SCOPE")
                .put("purpose", "PROJECT_RESEARCH").put("expertReviewCreated", false).put("clinicalAcceptance", false)
                .put("realPatientData", false).put("sourceId", sourceId).put("mode", mode)
                .put("origin", "Existing Pvs1NmdBusinessFixture escape inputs; COMPLEX reuses Pvs1EscapeAdoptionProofTest.explicitA5ComplexCodingEscapeUsesActualReferenceMappingWithoutSpliceLocalCaps")
                .put("limitations", "Fictional models, genes, diseases and data-authored source policy. No actual RNA assay, patient, G2P publication or clinical expert policy is asserted.");
        var sourceModel = model.deepCopy(); sourceModel.remove("sourceRecords"); source.set("referenceInput", sourceModel);
        source.set("conditions", conditionSnapshot(policy)); source.set("exactScope", policy.path("scope").deepCopy());
        source.put("policyReviewWitnessId", reviewId).put("parameterChangesFromExistingVector", false);
        Path original = root.resolve("test-only-coding-source-" + i + ".json"); create(original, mapper.writeValueAsBytes(source));
        var sourceRecord = mapper.createObjectNode().put("sourceId", sourceId).put("version", "TEST_ONLY_1")
                .put("url", "https://example.invalid/TEST_ONLY/pvs1-coding/" + mode + "/" + i)
                .put("relativePath", original.getFileName().toString()).put("sha256", rawSha(original));
        model.putArray("sourceRecords").add(sourceRecord.deepCopy()); policy.putArray("scientificSources").add(sourceRecord);
        policy.putObject("transcriptModelBinding").put("modelId", model.path("modelId").asText())
                .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", hash(model));
    }
    private static void replaceSourceIds(JsonNode node, String sourceId) {
        if (node instanceof ObjectNode object) {
            if (object.has("sourceIds")) object.putArray("sourceIds").add(sourceId);
            object.properties().forEach(entry -> { if (!"sourceIds".equals(entry.getKey())) replaceSourceIds(entry.getValue(), sourceId); });
        } else if (node.isArray()) node.forEach(child -> replaceSourceIds(child, sourceId));
    }
    private ObjectNode conditionSnapshot(ObjectNode policy) {
        var result = mapper.createObjectNode();
        for (String key : List.of("mechanism", "nmdScope", "escapeScope", "codingEditScope", "appliedStrength"))
            if (policy.has(key)) result.set(key, policy.path(key).deepCopy());
        return result;
    }
    private ObjectNode mechanismFacts() throws IOException {
        var value = Pvs1KnowledgeCatalogTest.facts(mapper, data.policies); var original = mapper.createObjectNode()
                .put("schemaVersion", "test-only-coding-mechanism-original-v1").put("dataClass", "SYNTHETIC")
                .put("purpose", "PROJECT_RESEARCH").put("clinicalAcceptance", false).put("expertReviewCreated", false)
                .put("meaning", "Fictional G2P-shaped rows for the exact toy backgrounds, not records fetched from G2P");
        var rows = original.putArray("originalRows"); value.path("records").forEach(record -> rows.add(record.path("sourceRecord").deepCopy()));
        Path file = root.resolve("test-only-mechanism-original.json"); create(file, mapper.writeValueAsBytes(original));
        value.putObject("source").put("sha256", rawSha(file)).put("version", "TEST_ONLY_CODING_1")
                .put("url", "https://example.invalid/TEST_ONLY/pvs1-coding-mechanism");
        return value;
    }

    /** Reexecutes the unchanged real CLI. Changed pinned originals fail before any new scoring. */
    public ObjectNode reassembleInto(Path newOutput) throws Exception {
        Path output = newOutput.toAbsolutePath().normalize(), log = output.resolveSibling(output.getFileName() + ".log");
        if (Files.exists(output) || Files.exists(log)) throw new IOException("TEST_ONLY_FRESH_ASSEMBLY_OUTPUT_REQUIRED");
        var command = List.of(python.toString(), "-E", "-s", "-B", assemblyScript.toString(), "--config", assemblyConfig.toString(),
                "--config-sha256", assemblyConfigSha256, "--output-directory", output.toString());
        var process = new ProcessBuilder(command).directory(assemblyScript.getParent().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IOException("TEST_ONLY_SOURCE_COMPILER_TERMINATION_UNCONFIRMED");
            throw new IOException("TEST_ONLY_SOURCE_COMPILER_TIMEOUT");
        }
        var execution = mapper.createObjectNode().put("exitCode", process.exitValue())
                .put("executionMode", "OFFLINE_SOURCE_COMPILER_ONLY").put("capabilityScope", "NO_MODEL_OR_NETWORK_ENTRY_POINT")
                .put("scriptRawSha256", rawSha(assemblyScript)).put("configRawSha256", assemblyConfigSha256).put("logRawSha256", rawSha(log));
        execution.set("argv", mapper.valueToTree(command)); create(output.resolveSibling(output.getFileName() + "-execution.json"), mapper.writeValueAsBytes(execution));
        if (process.exitValue() != 0) throw new IOException("TEST_ONLY_SOURCE_COMPILER_FAILED: " + log);
        return readObject(output.resolve("assembly-audit.json"));
    }
    public ReviewedRuleBundle reloadBundleFrom(Path adoptionFile, String adoptionRawSha256) throws IOException {
        var currentKnowledge = Pvs1KnowledgeCatalog.load(mapper, knowledgeFactsFile, knowledgeFactsSha256, adoptionFile, adoptionRawSha256);
        return ReviewedRuleBundle.loadPvs1Selected(mapper, bundleFile, bundleRawSha256, bundleCanonicalSha256,
                new Pvs1TranscriptMaterialSupply(mapper, codingMaterialSupply()), currentKnowledge);
    }
    public Pvs1SelectedBatch selectedBatch() { return new Pvs1SelectedBatch(mapper, bundle); }
    public CodingTranscriptMaterialSupply codingMaterialSupply() throws IOException {
        return new CodingTranscriptMaterialSupply(mapper, modelDirectory, modelManifestSha256, this::readReference);
    }
    public CodingTranscriptMaterialSupply proteinLengthMaterialSupply() throws IOException {
        return ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper, modelDirectory, modelManifestSha256, this::readReference, null);
    }
    public ObjectNode document() { return document.deepCopy(); }
    public ObjectNode knowledgeFacts() { return facts.deepCopy(); }
    public ObjectNode adoption() { return adoption.deepCopy(); }
    public TranscriptConsequence annotation(int i) throws Exception { return data.annotation(i); }
    public VariantInterpretation candidate(int i) throws Exception {
        var candidate = RuntimeEvidenceV2ProviderWiringTest.variant(data.alleles.get(i)); candidate.applyTranscriptAnnotations(List.of(annotation(i))); return candidate;
    }
    public Map<String, String> properties() {
        var values = new LinkedHashMap<String, String>(); String prefix = "variant-analysis.acmg-business.";
        values.put(prefix + "pvs1-bundle-file", bundleFile.toString()); values.put(prefix + "pvs1-bundle-file-sha256", bundleRawSha256);
        values.put(prefix + "pvs1-bundle-sha256", bundleCanonicalSha256); values.put(prefix + "pvs1-selected-knowledge-enabled", "true");
        values.put(prefix + "pvs1-knowledge-source-file", knowledgeFactsFile.toString()); values.put(prefix + "pvs1-knowledge-source-sha256", knowledgeFactsSha256);
        values.put(prefix + "pvs1-knowledge-adoption-file", knowledgeAdoptionFile.toString()); values.put(prefix + "pvs1-knowledge-adoption-sha256", knowledgeAdoptionSha256);
        values.put(prefix + "pvs1-local-material-supply-enabled", "true"); values.put(prefix + "transcript-material-directory", modelDirectory.toString());
        values.put(prefix + "transcript-material-manifest-sha256", modelManifestSha256); return Map.copyOf(values);
    }
    public ObjectNode pinnedFiles() {
        var pins = mapper.createObjectNode().put("dataClass", "TEST_ONLY_SYNTHETIC_ASSAY_POLICY_SCOPE").put("mode", mode)
                .put("createsExpertReview", false).put("clinicalAcceptance", false).put("sourceCompiler", "ACTUAL_ASSEMBLE_RUNTIME_BUNDLE_CLI");
        var files = pins.putArray("originalFiles"); originalPins.forEach((file, sha) -> files.addObject().put("path", file.toString()).put("rawSha256", sha));
        pins.putObject("reference").put("path", fastaPath.toString()).put("sha256", referenceSha256)
                .put("indexPath", fastaIndexPath.toString()).put("indexSha256", fastaIndexSha256).put("aliasFile", aliasFile.toString());
        pins.putObject("models").put("path", modelManifestFile.toString()).put("sha256", modelManifestSha256);
        pins.putObject("bundle").put("path", bundleFile.toString()).put("rawSha256", bundleRawSha256).put("canonicalSha256", bundleCanonicalSha256);
        pins.putObject("knowledge").put("factsPath", knowledgeFactsFile.toString()).put("factsSha256", knowledgeFactsSha256)
                .put("adoptionPath", knowledgeAdoptionFile.toString()).put("adoptionSha256", knowledgeAdoptionSha256);
        pins.putObject("assembly").put("config", assemblyConfig.toString()).put("configRawSha256", assemblyConfigSha256)
                .put("outputDirectory", assemblyOutputDirectory.toString()).put("log", assemblyLog.toString()).set("audit", assemblyAudit.deepCopy());
        return pins;
    }

    private Map<String, String> buildReference() {
        var assigned = new LinkedHashMap<String, Map<Integer, Character>>();
        for (int i = 0; i < 2; i++) {
            var model = data.models.get(i); String contig = model.path("contig").asText(), cds = model.path("cdsSequence").asText();
            for (var exon : model.path("exons")) for (int genomic = exon.path("genomicStart").asInt(); genomic <= exon.path("genomicEnd").asInt(); genomic++) {
                int coding = CodingTranscriptMapping.cdsPosition(model, genomic);
                putBase(assigned, contig, genomic, CodingTranscriptMapping.orientAllele(model, cds.substring(coding - 1, coding)).charAt(0));
            }
            var allele = data.alleles.get(i);
            for (int j = 0; j < allele.getReference().length(); j++) putBase(assigned, contig, allele.getPosition() + j, allele.getReference().charAt(j));
        }
        var result = new LinkedHashMap<String, String>();
        for (var entry : assigned.entrySet()) {
            int maximum = entry.getValue().keySet().stream().mapToInt(mappedItem -> mappedItem.intValue()).max().orElseThrow();
            if (maximum > 100_000) throw new IllegalArgumentException("TEST_ONLY_TINY_REFERENCE_LIMIT");
            var bases = new StringBuilder("A".repeat(maximum + 30)); entry.getValue().forEach((position, base) -> bases.setCharAt(position - 1, base));
            result.put(entry.getKey(), bases.toString());
        }
        return Collections.unmodifiableMap(result);
    }
    private static void putBase(Map<String, Map<Integer, Character>> assigned, String contig, int position, char base) {
        if (position < 1 || "ACGT".indexOf(base) < 0) throw new IllegalArgumentException("TEST_ONLY_REFERENCE_BASE_INVALID");
        var bases = assigned.computeIfAbsent(contig, ignored -> new LinkedHashMap<>()); Character prior = bases.putIfAbsent(position, base);
        if (prior != null && prior != base) throw new IllegalArgumentException("TEST_ONLY_REFERENCE_SOURCE_CONFLICT");
    }
    private void writeFasta() throws IOException {
        var bytes = new ByteArrayOutputStream(); var index = new StringBuilder();
        for (var entry : referenceContigs.entrySet()) {
            bytes.write(('>' + entry.getKey() + '\n').getBytes(StandardCharsets.US_ASCII)); int offset = bytes.size(); String bases = entry.getValue();
            index.append(entry.getKey()).append('\t').append(bases.length()).append('\t').append(offset).append("\t60\t61\n");
            for (int i = 0; i < bases.length(); i += 60) bytes.write((bases.substring(i, Math.min(i + 60, bases.length())) + '\n').getBytes(StandardCharsets.US_ASCII));
        }
        create(fastaPath, bytes.toByteArray()); create(fastaIndexPath, index.toString().getBytes(StandardCharsets.US_ASCII));
    }
    public CodingTranscriptMaterialSupply.ReferenceSpan readReference(String contig, int start, int length) throws IOException {
        referenceReads.incrementAndGet();
        if (!rawSha(fastaPath).equals(referenceSha256) || !rawSha(fastaIndexPath).equals(fastaIndexSha256))
            throw new IOException("TEST_ONLY_CURRENT_REFERENCE_BYTES_CHANGED");
        String bases = referenceContigs.get(contig.startsWith("chr") ? contig.substring(3) : contig);
        if (bases == null || start < 1 || length < 1 || (long) start + length - 1 > bases.length()) throw new IOException("TEST_ONLY_REFERENCE_REGION_INVALID");
        return new CodingTranscriptMaterialSupply.ReferenceSpan(bases.substring(start - 1, start - 1 + length), mapper.createObjectNode()
                .put("version", "TEST_ONLY_CODING_CURRENT_REFERENCE_V1").put("expectedSha256", referenceSha256).put("fastaIndexSha256", fastaIndexSha256));
    }
    private Path assembler() throws IOException {
        for (Path path : List.of(Path.of("scripts/pvs1/assemble_runtime_bundle.py"), Path.of("../scripts/pvs1/assemble_runtime_bundle.py")))
            if (Files.isRegularFile(path)) return path.toRealPath();
        throw new IOException("TEST_ONLY_SOURCE_COMPILER_NOT_FOUND");
    }
    private ObjectNode fileBinding(Path file) throws IOException { return mapper.createObjectNode().put("file", file.toString()).put("sha256", rawSha(file)); }
    private ObjectNode readObject(Path file) throws IOException {
        if (!(mapper.readTree(Files.readAllBytes(file)) instanceof ObjectNode object)) throw new IOException("TEST_ONLY_JSON_OBJECT_REQUIRED"); return object;
    }
    private String hash(JsonNode value) { return adapter.canonicalSha256(value); }
    private static String rawSha(Path file) throws IOException { return CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file)); }
    private void create(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes, StandardOpenOption.CREATE_NEW); originalPins.put(file, CodingTranscriptMaterialSupply.sha(bytes));
    }
}
