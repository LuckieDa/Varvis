package org.nbscn.varvis.variantanalysis.config;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.Ba1SpecificationCatalog;
import org.nbscn.varvis.variantanalysis.provider.GeneralPm4Runtime;
import org.nbscn.varvis.variantanalysis.provider.LocalEvidenceBatchRuntime;
import org.nbscn.varvis.variantanalysis.provider.NormalizationResourceValidator;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionPreparation;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import org.nbscn.varvis.variantanalysis.service.NativeSnvSourceProducer;
import org.springframework.lang.Nullable;
import org.springframework.mock.env.MockEnvironment;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

/**
 * Invented sequence and annotation transport for the real central PM4/BP3 builders.
 * No scientific approval, predictor output, or criterion result is supplied here.
 * The long N-only chr1 is real file content required by the existing readiness
 * validator; these bytes are explicitly not a biological GRCh38 reference.
 */
public final class Pm4Bp3ComplexPublicFixture {
    public static final String CDS = "ATGGAACCTGGCTTTAAATAA";
    public static final String TAIL = "GCTGGTTGACCC";
    public static final String FULL_SPLICED = CDS + TAIL;
    public static final String ALTERED_CDS = "ATGGAAGCTGAAGGCTTTAAATAA";
    public static final String PREFIX = "variant-analysis.acmg-business.";
    private static final long CHR1_BASES = 248_956_422L;
    private static final String REFERENCE_NAME = "synthetic-transport.fa";
    private static final String RECEIPT_LOCATOR = "checksum";
    private static final Map<Path, SharedReference> REFERENCES = new LinkedHashMap<>();
    private static final Map<String, Gene> GENES = Map.of(
            "HBB", new Gene("11", "HGNC:4827", "ENST00000647020.1", "ENSP00000494175.1", "P12345"),
            "GJB2", new Gene("13", "HGNC:4284", "ENST00000382848.5", "ENSP00000372299.4", "Q12345"));

    private final ObjectMapper mapper;
    private final Path root;
    private final SharedReference reference;
    private final VariantAnalysisProperties properties = new VariantAnalysisProperties();
    private final MockEnvironment environment = new MockEnvironment();
    private final SyntheticRnaLayout layout;
    private final Map<String, ObjectNode> models = new LinkedHashMap<>();
    private final Map<String, ObjectNode> annotations = new LinkedHashMap<>();
    private final List<Path> originals = new ArrayList<>();
    private final List<LocalEvidenceBatchConfiguration> configurations = new ArrayList<>();
    private final List<LocalEvidenceBatchRuntime> batches = new ArrayList<>();

    public Pm4Bp3ComplexPublicFixture(ObjectMapper mapper, Path scenarioRoot, Path sharedReferenceRoot) throws Exception {
        this(mapper, scenarioRoot, sharedReferenceRoot,
                new SyntheticRnaLayout(FULL_SPLICED, 1, CDS.length(), List.of(FULL_SPLICED.length()), 100, 1000, "MEPGFK"));
    }

    /** Sequence transport only. The caller's synthetic layout grants no RNA or functional qualification. */
    public Pm4Bp3ComplexPublicFixture(ObjectMapper mapper, Path scenarioRoot, Path sharedReferenceRoot,
            SyntheticRnaLayout layout) throws Exception {
        this.mapper = mapper;
        this.layout = layout;
        this.root = Files.createDirectories(scenarioRoot.toAbsolutePath().normalize());
        this.reference = sharedReference(mapper, sharedReferenceRoot, layout);
        properties.setStorageRoot(root.resolve("jobs"));
        configureNormalization(properties);
        if (!new NormalizationResourceValidator(properties).validate(GenomeAssembly.GRCh38).isReady())
            throw new IllegalStateException("SYNTHETIC_REFERENCE_REAL_VALIDATOR_NOT_READY");
        for (String symbol : List.of("HBB", "GJB2")) {
            models.put(symbol, newModel(symbol));
            annotations.put(symbol, newAnnotation(symbol, "7_9delinsGCTGAA", "Pro3delinsAlaGlu", "protein_altering_variant"));
        }
        writeModels();
        writeProteinRegions();
        var catalog = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA).put("dataClass", "SYNTHETIC");
        catalog.putObject("metadata").put("status", "COMPLETE").put("entryCount", 0).put("snapshotId", "SYNTHETIC_EMPTY_OVERRIDE_INVENTORY");
        catalog.putArray("entries");
        Path catalogFile = write(root.resolve("catalog.json"), catalog);
        pin("prediction-specialization-catalog-file", "prediction-specialization-catalog-sha256", catalogFile);
        environment.withProperty(PREFIX + "resource-root", requireNonNull(reference.root().toString()))
                .withProperty(PREFIX + "reference-receipt-file", requireNonNull(reference.receipt().toString()))
                .withProperty(PREFIX + "reference-receipt-sha256", requireNonNull(reference.receiptSha()))
                .withProperty(PREFIX + "reference-receipt-locator", RECEIPT_LOCATOR)
                .withProperty(PREFIX + "working-root", requireNonNull(root.resolve("working").toString()))
                .withProperty(PREFIX + "local-batch-pm4-enabled", "true")
                .withProperty(PREFIX + "local-batch-bp3-enabled", "true")
                .withProperty(PREFIX + "local-batch-pvs1-enabled", "true")
                .withProperty(PREFIX + "local-batch-bp7-enabled", "true")
                .withProperty(PREFIX + "local-batch-denovo-public-network-enabled", "false")
                .withProperty(PREFIX + "pm4-region-demand-enabled", "false")
                .withProperty(PREFIX + "pm4-region-demand-network-enabled", "false")
                .withProperty(PREFIX + "pm4-non-repeat-policy", "REVIEWED_ONLY");
    }

    public VariantAnalysisProperties props() { return properties; }
    public MockEnvironment env() { return environment; }
    public Path referenceRoot() { return reference.root(); }
    public Path referenceFile() { return reference.fasta(); }
    public Path referenceReceiptFile() { return reference.receipt(); }
    public Path manifestFile() { return root.resolve("models/manifest.json"); }
    public Path modelFile(String gene) { return root.resolve("models").resolve(gene(gene).transcript() + ".model.json"); }
    public Path rawRegionFile(String gene) { return root.resolve("uniprot").resolve(gene(gene).accession() + ".json"); }
    public ObjectNode model(String gene) { return models.get(symbol(gene)).deepCopy(); }
    public ObjectNode annotation(String gene) { return annotations.get(symbol(gene)).deepCopy(); }
    public ExplicitContextSourceLedger referenceLedger() { return reference.ledger(); }

    /** Preserve the service fixture's storage/JDBC settings while sharing the same real reference. */
    public void applyNormalizationTo(VariantAnalysisProperties target) { configureNormalization(target); }

    /** Both toy transcripts already contain the literal tail; this changes only the synthetic annotation. */
    public void stopLossFirst() {
        annotations.put("HBB", newAnnotation("HBB", "19_21delinsCGA", "Ter7ArgextTer3", "stop_lost"));
    }

    /** An observed repeat is a negative PM4 control; it creates no repeat/function review. */
    public void repeatFirst() throws IOException {
        if (!configurations.isEmpty()) throw new IllegalStateException("REPEAT_FIXTURE_MUST_BE_PREPARED_BEFORE_RUNTIME");
        var raw = (ObjectNode) mapper.readTree(Files.readAllBytes(rawRegionFile("HBB")));
        var feature = raw.putArray("features").addObject().put("type", "Repeat").put("description", "SYNTHETIC_A5_CHANGED_RESIDUE_REPEAT");
        var location = feature.putObject("location");
        location.putObject("start").put("value", 3).put("modifier", "EXACT");
        location.putObject("end").put("value", 3).put("modifier", "EXACT");
        feature.putArray("evidences").addObject().put("evidenceCode", "ECO:0000255")
                .put("source", "SYNTHETIC_TEST_ONLY").put("id", "invented-repeat-not-scientific-review");
        rewrite(rawRegionFile("HBB"), raw);
        byte[] rawBytes = Files.readAllBytes(rawRegionFile("HBB"));
        Path directory = root.resolve("uniprot"), acquisitionFile = directory.resolve("acquisition.json");
        var acquisitions = mapper.readTree(Files.readAllBytes(acquisitionFile));
        for (var acquisition : acquisitions) if (gene("HBB").accession().equals(acquisition.path("accession").asText()))
            ((ObjectNode) acquisition).put("bytes", rawBytes.length).put("sha256", sha(rawBytes));
        rewrite(acquisitionFile, acquisitions);
        Path manifest = directory.resolve("manifest.json");
        rewrite(manifest, UniProtProteinRegionPreparation.prepare(mapper, directory));
        environment.withProperty(PREFIX + "pm4-protein-region-manifest-sha256", requireNonNull(sha(Files.readAllBytes(manifest))));
        String acquisitionSha = sha(Files.readAllBytes(acquisitionFile));
        Path combinedFile = root.resolve("combined.json");
        var combined = (ObjectNode) mapper.readTree(Files.readAllBytes(combinedFile));
        for (var row : combined.path("results")) {
            var pins = (ObjectNode) row.at("/facts/sources/pins");
            ((ObjectNode) pins.path("uniprotAcquisition")).put("sha256", acquisitionSha);
            if (gene("HBB").accession().equals(row.path("accession").asText())) {
                ((ObjectNode) row.at("/facts/sourceCompleteness")).put("uniprotFeatureCount", 1);
                for (String pin : List.of("root", "rootReceipt", "protein", "proteinReceipt", "entries", "entriesReceipt", "uniprot"))
                    ((ObjectNode) pins.path(pin)).put("sha256", sha(rawBytes));
            }
        }
        rewrite(combinedFile, combined);
        pin("pm4-combined-region-file", "pm4-combined-region-sha256", combinedFile);
    }

    /** Internally consistent synonymous model; the unchanged literal FASTA must disprove it. */
    public void mismatchReferenceForFirst() throws IOException {
        String changed = "ATGGAACCTGGCTTTAAGTAA";
        var model = models.get("HBB");
        model.put("cdsSequence", changed);
        model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", sha((changed + TAIL).getBytes(StandardCharsets.US_ASCII)))
                .put("cdsSha256", sha(changed.getBytes(StandardCharsets.US_ASCII)));
        Files.write(modelFile("HBB"), mapper.writeValueAsBytes(model));
        Files.write(manifestFile(), mapper.writeValueAsBytes(modelManifest()));
        environment.withProperty(PREFIX + "transcript-material-manifest-sha256", requireNonNull(sha(Files.readAllBytes(manifestFile()))));
    }

    public NativeSnvSourceProducer producer(List<ExplicitContextSourceLedger> contextSources) {
        var all = new ArrayList<>(contextSources);
        if (!all.contains(reference.ledger())) all.add(reference.ledger());
        return new NativeSnvSourceProducer(mapper, properties, reference.root(), all, reference.ledger().reference(RECEIPT_LOCATOR));
    }

    /** Real configuration; the spy observes calls and has no stubs or replacement methods. */
    public LocalEvidenceBatchRuntime centralRuntime(Function<JsonNode, JsonNode> effects, @Nullable String effectSourceSha256) {
        var configuration = spy(new LocalEvidenceBatchConfiguration(mapper, properties, environment, null));
        configurations.add(configuration);
        var batch = configuration.create(effects, effectSourceSha256);
        batches.add(batch);
        return batch;
    }

    public ObjectNode configurationIdentity() {
        return new LocalEvidenceBatchConfiguration(mapper, properties, environment, null).identity();
    }

    public ObjectNode configurationCalls() {
        var out = mapper.createObjectNode().put("configurationInstances", configurations.size())
                .put("centralConfigurationUsed", true).put("coreFactoryOverridden", false);
        out.putArray("coreStubs");
        for (String name : List.of("create", "pm4", "proteinLengthMaterials", "bp3", "transcriptMaterials", "pvs1", "population")) {
            long count = configurations.stream().flatMap(configuration -> mockingDetails(configuration).getInvocations().stream())
                    .filter(invocation -> invocation.getMethod().getName().equals(name)).count();
            out.put(name, count);
        }
        out.put("actualStubCount", configurations.stream().mapToInt(configuration -> mockingDetails(configuration).getStubbings().size()).sum());
        return out;
    }

    /** Read only the existing PM4 cache captured by the real factory, without Supplier.get(). */
    public ObjectNode liveRuntimeCounters() {
        Set<GeneralPm4Runtime> initialized = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            Field factoryField = LocalEvidenceBatchRuntime.class.getDeclaredField("pm4Factory");
            factoryField.setAccessible(true);
            for (var batch : batches) {
                Object factory = factoryField.get(batch);
                if (factory == null) continue;
                Field cacheField = null;
                for (Field capture : factory.getClass().getDeclaredFields()) if (capture.getType() == GeneralPm4Runtime[].class) {
                    if (cacheField != null) throw new IllegalStateException("PM4_FACTORY_CACHE_CAPTURE_AMBIGUOUS");
                    cacheField = capture;
                }
                if (cacheField == null) throw new IllegalStateException("PM4_FACTORY_CACHE_CAPTURE_MISSING");
                cacheField.setAccessible(true);
                var cached = (GeneralPm4Runtime[]) cacheField.get(factory);
                if (cached == null || cached.length != 1) throw new IllegalStateException("PM4_FACTORY_CACHE_CAPTURE_INVALID");
                if (cached[0] != null) initialized.add(cached[0]);
            }
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException("PM4_LIVE_COUNTER_OBSERVATION_UNAVAILABLE", unavailable);
        }
        return mapper.createObjectNode().put("centralBatches", batches.size()).put("initializedPm4Runtimes", initialized.size())
                .put("pm4Evaluations", initialized.stream().mapToInt(mappedItem -> mappedItem.evaluations()).sum())
                .put("supplierInvocationsByObservation", 0).put("evaluationsDuringObservation", 0);
    }

    /** Small source originals only; the 249 MB reference is separately exposed and never copied per case. */
    public List<Path> originalFiles() {
        var paths = new ArrayList<>(originals);
        paths.add(reference.receipt()); paths.add(Path.of(reference.fasta() + ".fai")); paths.add(reference.alias());
        return List.copyOf(paths);
    }

    public ObjectNode scope() {
        var out = mapper.createObjectNode().put("schemaVersion", "a5-pm4-bp3-public-business-fixture-v1")
                .put("dataClass", "SYNTHETIC").put("biologicalGrch38Reference", false)
                .put("referenceTransport", "LITERAL_N_CHR1_AND_INVENTED_CDS_TAIL_CHR11_CHR13")
                .put("chr1ActualBases", CHR1_BASES).put("referenceRawSha256", reference.referenceSha())
                .put("referenceBytes", reference.bytes()).put("fullReferenceHashes", 1)
                .put("realReadinessValidatorUsed", true).put("realCentralConfigurationUsed", true)
                .put("realVepExecuted", false).put("networkExecuted", false).put("scientificQualificationGranted", false)
                .put("reviewAssertionsCreated", false).put("normalizationTransport", "CONFIGURED_BCFTOOLS_EXECUTABLE")
                .put("largeReferenceExcludedFromOriginalFiles", true);
        out.putArray("coreStubs");
        return out;
    }

    private void configureNormalization(VariantAnalysisProperties target) {
        var normalization = target.getNormalization();
        normalization.setMode(VariantAnalysisProperties.Mode.BCFTOOLS);
        normalization.setGrch38Reference(reference.fasta().toString());
        normalization.setGrch38ReferenceSha256(reference.referenceSha());
        normalization.setGrch38ProfileId("SYNTHETIC_A5_PUBLIC_BUSINESS_TRANSPORT");
        normalization.setGrch38ContigAliasFile(reference.alias().toString());
        normalization.setTimeout(Duration.ofSeconds(30));
        normalization.setBcftoolsExecutable(System.getProperty("a5.public.bcftools", System.getProperty("varvis.n01.bcftools", "bcftools")));
    }

    private ObjectNode newModel(String symbol) {
        var gene = gene(symbol);
        String cds = layout.rna().substring(layout.cdsStartRna1() - 1, layout.cdsEndRna1());
        var model = mapper.createObjectNode().put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "SYNTHETIC_A5_PUBLIC_" + symbol).put("modelVersion", "1").put("assembly", "GRCh38")
                .put("contig", gene.contig()).put("strand", 1).put("hgncId", gene.hgnc()).put("geneSymbol", symbol)
                .put("transcript", gene.transcript()).put("protein", gene.protein())
                .put("cdsSequence", cds).put("cdsLength", cds.length()).put("proteinLength", layout.proteinWithoutStop().length());
        model.putArray("sourceRecords").addObject().put("sourceId", "SYNTHETIC_A5_PUBLIC_SEQUENCE").put("version", "1")
                .put("url", "https://example.invalid/a5-public-business-engineering-only").put("sha256", sha(layout.rna().getBytes(StandardCharsets.US_ASCII)));
        var exons = model.putArray("exons"); int first = 1;
        for (int i = 0; i < layout.exonEndsRna1().size(); i++) {
            int end = layout.exonEndsRna1().get(i), genomic = layout.firstExonStart() + i * layout.exonStride();
            exons.addObject().put("ordinal", i + 1).put("genomicStart", genomic).put("genomicEnd", genomic + end - first)
                    .put("cdsStart", first - layout.cdsStartRna1() + 1).put("cdsEnd", end - layout.cdsStartRna1() + 1);
            first = end + 1;
        }
        if (layout.exonEndsRna1().size() > 1)
            model.put("lastJunctionCdsPosition", layout.exonEndsRna1().get(layout.exonEndsRna1().size() - 2) - layout.cdsStartRna1() + 1);
        model.putObject("sequenceVerification").put("fullSplicedTranscriptSha256", sha(layout.rna().getBytes(StandardCharsets.US_ASCII)))
                .put("cdsSha256", sha(cds.getBytes(StandardCharsets.US_ASCII)));
        return model;
    }

    private ObjectNode newAnnotation(String symbol, String coding, String protein, String consequence) {
        var gene = gene(symbol);
        return mapper.createObjectNode().put("geneSymbol", symbol).put("transcriptAccession", gene.transcript())
                .put("hgvsCoding", gene.transcript() + ":c." + coding).put("hgvsProtein", gene.protein() + ":p." + protein)
                .put("consequence", consequence).put("exon", "1/1").put("source", "VEP").put("version", "116.0")
                .put("status", "COMPLETED").put("timestamp", "2026-11-04T00:00:00Z").put("maneSelect", true);
    }

    private void writeModels() throws IOException {
        Files.createDirectories(root.resolve("models"));
        for (var item : models.entrySet()) write(modelFile(item.getKey()), item.getValue());
        Path file = write(manifestFile(), modelManifest());
        environment.withProperty(PREFIX + "transcript-material-directory", requireNonNull(file.getParent().toString()))
                .withProperty(PREFIX + "transcript-material-manifest-sha256", requireNonNull(sha(Files.readAllBytes(file))));
    }

    private ObjectNode modelManifest() throws IOException {
        var manifest = mapper.createObjectNode().put("schemaVersion", "portable-native-transcript-model-derivation-v1");
        var entries = manifest.putArray("entries");
        for (var item : models.entrySet()) {
            Path file = modelFile(item.getKey());
            byte[] bytes = Files.readAllBytes(file);
            entries.addObject().put("transcript", item.getValue().path("transcript").asText()).put("file", file.getFileName().toString())
                    .put("fileBytes", bytes.length).put("fileSha256", sha(bytes))
                    .put("canonicalSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(item.getValue()))
                    .put("modelId", item.getValue().path("modelId").asText()).put("modelVersion", item.getValue().path("modelVersion").asText());
        }
        return manifest;
    }

    private void writeProteinRegions() throws IOException {
        Path directory = Files.createDirectories(root.resolve("uniprot"));
        var acquisitions = mapper.createArrayNode();
        var combined = mapper.createObjectNode().put("dataClass", "SYNTHETIC");
        var results = combined.putArray("results");
        for (String symbol : List.of("HBB", "GJB2")) {
            var gene = gene(symbol);
            var raw = mapper.createObjectNode().put("primaryAccession", gene.accession()).put("entryType", "UniProtKB reviewed (Swiss-Prot)")
                    .put("dataClass", "SYNTHETIC");
            raw.putObject("organism").put("taxonId", 9606);
            raw.putObject("entryAudit").put("entryVersion", 1).put("sequenceVersion", 1);
            raw.putObject("sequence").put("value", layout.proteinWithoutStop()).put("length", layout.proteinWithoutStop().length());
            var crossReferences = raw.putArray("uniProtKBCrossReferences");
            crossReferences.addObject().put("database", "HGNC").put("id", gene.hgnc());
            crossReferences.addObject().put("database", "Ensembl").put("id", gene.transcript())
                    .putArray("properties").addObject().put("key", "ProteinId").put("value", gene.protein());
            raw.putArray("features");
            Path rawFile = write(rawRegionFile(symbol), raw); byte[] rawBytes = Files.readAllBytes(rawFile);
            acquisitions.addObject().put("accession", gene.accession()).put("success", true).put("status", 200)
                    .put("url", "https://rest.uniprot.org/uniprotkb/" + gene.accession() + ".json")
                    .put("startedAt", "2026-11-04T00:00:00Z").put("finishedAt", "2026-11-04T00:00:01Z")
                    .put("headers", "X-UniProt-Release: 2026_03\r\n").put("bytes", rawBytes.length).put("sha256", sha(rawBytes))
                    .put("dataClass", "SYNTHETIC").put("transport", "INVENTED_OFFLINE_ACQUISITION_SHAPE_NO_HTTP");
            var facts = results.addObject().put("accession", gene.accession()).put("status", "FACTS_READY")
                    .putObject("facts").put("schemaVersion", "combined-protein-region-facts-v1").put("accession", gene.accession())
                    .put("coordinateSystem", "ONE_BASED_CLOSED").put("length", layout.proteinWithoutStop().length())
                    .put("sequenceSha256", sha(layout.proteinWithoutStop().getBytes(StandardCharsets.US_ASCII)))
                    .put("adapterVersion", "1.1.0").put("sourceScope", "UNIPROT_FEATURES_AND_INTEGRATED_INTERPRO_ENTRIES_ONLY");
            var sources = facts.putObject("sources").put("interproRelease", "110.0").put("interproMinor", "0").put("uniprotRelease", "2026_03");
            var pins = sources.putObject("pins");
            for (String pin : List.of("root", "rootReceipt", "protein", "proteinReceipt", "entries", "entriesReceipt", "uniprot", "uniprotAcquisition"))
                pins.putObject(pin).put("sha256", sha(rawBytes));
            facts.putObject("sourceCompleteness").put("uniprotFeaturesComplete", true).put("interproEntriesComplete", true)
                    .put("exactFullSequenceMatch", true).put("uniprotFeatureCount", 0).put("interproEntryCount", 0);
            facts.putArray("context"); facts.putArray("regions"); facts.putArray("unplaced");
        }
        write(directory.resolve("acquisition.json"), acquisitions);
        Path manifest = write(directory.resolve("manifest.json"), UniProtProteinRegionPreparation.prepare(mapper, directory));
        environment.withProperty(PREFIX + "pm4-protein-region-directory", requireNonNull(directory.toString()))
                .withProperty(PREFIX + "pm4-protein-region-manifest-sha256", requireNonNull(sha(Files.readAllBytes(manifest))));
        Path combinedFile = write(root.resolve("combined.json"), combined);
        pin("pm4-combined-region-file", "pm4-combined-region-sha256", combinedFile);
    }

    private Path write(Path file, JsonNode value) throws IOException {
        Files.write(file, mapper.writeValueAsBytes(value), StandardOpenOption.CREATE_NEW);
        originals.add(file);
        return file;
    }

    private void rewrite(Path file, JsonNode value) throws IOException {
        if (!originals.contains(file) || !Files.isRegularFile(file))
            throw new IllegalArgumentException("ONLY_EXISTING_SCENARIO_FIXTURE_FILES_CAN_BE_REWRITTEN");
        Files.write(file, mapper.writeValueAsBytes(value), StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private void pin(String pathKey, String shaKey, Path file) throws IOException {
        environment.withProperty(PREFIX + pathKey, requireNonNull(file.toString())).withProperty(PREFIX + shaKey, requireNonNull(sha(Files.readAllBytes(file))));
    }

    private static synchronized SharedReference sharedReference(ObjectMapper mapper, Path requestedRoot, SyntheticRnaLayout layout) throws IOException {
        Path directory = Files.createDirectories(requestedRoot.toAbsolutePath().normalize());
        var cached = REFERENCES.get(directory);
        if (cached != null) {
            if (!cached.layout().equals(layout)) throw new IllegalArgumentException("SYNTHETIC_REFERENCE_ROOT_LAYOUT_CHANGED");
            return cached;
        }
        Path fasta = directory.resolve(REFERENCE_NAME), index = Path.of(fasta + ".fai"), alias = directory.resolve("contig-aliases.tsv");
        Instant started = Instant.now();
        MessageDigest digest = digest();
        String sequence = layout.genomicSequence();
        long chr11Offset = 6 + CHR1_BASES + 1 + 7;
        long chr13Offset = chr11Offset + sequence.length() + 1 + 7;
        try (OutputStream output = new DigestOutputStream(new BufferedOutputStream(
                Files.newOutputStream(fasta, StandardOpenOption.CREATE_NEW), 1_048_576), digest)) {
            output.write(">chr1\n".getBytes(StandardCharsets.US_ASCII));
            byte[] block = new byte[65_536]; Arrays.fill(block, (byte) 'N');
            long remaining = CHR1_BASES;
            while (remaining > 0) { int size = (int) Math.min(block.length, remaining); output.write(block, 0, size); remaining -= size; }
            output.write(("\n>chr11\n" + sequence + "\n>chr13\n" + sequence + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        String referenceSha = HexFormat.of().formatHex(digest.digest());
        String fai = "chr1\t" + CHR1_BASES + "\t6\t" + CHR1_BASES + "\t" + (CHR1_BASES + 1) + "\n"
                + "chr11\t" + sequence.length() + "\t" + chr11Offset + "\t" + sequence.length() + "\t" + (sequence.length() + 1) + "\n"
                + "chr13\t" + sequence.length() + "\t" + chr13Offset + "\t" + sequence.length() + "\t" + (sequence.length() + 1) + "\n";
        Files.writeString(index, fai, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        Files.writeString(alias, "1\tchr1\n11\tchr11\n13\tchr13\n", StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
        var receipt = mapper.createObjectNode().put("resourceId", "SYNTHETIC_A5_PUBLIC_REFERENCE_TRANSPORT").put("resourceVersion", "1")
                .put("assembly", "GRCh38").put("logicalName", REFERENCE_NAME).put("expectedSha256", referenceSha).put("observedSha256", referenceSha)
                .put("hashStatus", "VERIFIED").put("hashAlgorithm", "SHA-256").put("hashScope", "RAW_BYTES").put("unchangedDuringHash", true)
                .put("byteLength", Files.size(fasta)).put("mtimeNs", Files.getLastModifiedTime(fasta).to(TimeUnit.NANOSECONDS))
                .put("startedAt", started.toString()).put("completedAt", Instant.now().toString())
                .put("indexSha256", sha(Files.readAllBytes(index))).put("resourceDataClass", "SYNTHETIC");
        var envelope = mapper.createObjectNode().put("source", "SYNTHETIC_A5_PUBLIC_REFERENCE").put("version", "1").put("dataClass", "SYNTHETIC");
        envelope.putObject("records").putObject(RECEIPT_LOCATOR).set("actualChecksumReceipt", receipt);
        byte[] bytes = mapper.writeValueAsBytes(envelope);
        Path receiptFile = directory.resolve("reference-receipt.json");
        Files.write(receiptFile, bytes, StandardOpenOption.CREATE_NEW);
        var result = new SharedReference(directory, fasta, alias, receiptFile, referenceSha, sha(bytes), Files.size(fasta),
                ExplicitContextSourceLedger.verifiedBytes(mapper, bytes, sha(bytes)), layout);
        REFERENCES.put(directory, result);
        return result;
    }

    private static Gene gene(String value) { return GENES.get(symbol(value)); }
    private static String symbol(String value) {
        if (!GENES.containsKey(value)) throw new IllegalArgumentException("Unknown synthetic gene: " + value);
        return value;
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String sha(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
    private record Gene(String contig, String hgnc, String transcript, String protein, String accession) { }
    private record SharedReference(Path root, Path fasta, Path alias, Path receipt, String referenceSha,
            String receiptSha, long bytes, ExplicitContextSourceLedger ledger, SyntheticRnaLayout layout) { }

    /** Independent fixed geometry/peptide oracle for invented reference-file transport. */
    public record SyntheticRnaLayout(String rna, int cdsStartRna1, int cdsEndRna1, List<Integer> exonEndsRna1,
            int firstExonStart, int exonStride, String proteinWithoutStop) {
        public SyntheticRnaLayout {
            exonEndsRna1 = List.copyOf(exonEndsRna1);
            if (!rna.matches("[ACGT]+") || cdsStartRna1 < 1 || cdsEndRna1 < cdsStartRna1 || cdsEndRna1 > rna.length()
                    || (cdsEndRna1 - cdsStartRna1 + 1) % 3 != 0 || firstExonStart < 1 || exonStride < 1
                    || exonEndsRna1.isEmpty() || exonEndsRna1.get(exonEndsRna1.size() - 1) != rna.length()
                    || !proteinWithoutStop.matches("[A-Z]+") || proteinWithoutStop.length() != (cdsEndRna1 - cdsStartRna1 + 1) / 3 - 1)
                throw new IllegalArgumentException("INVALID_SYNTHETIC_RNA_LAYOUT");
            int previous = 0;
            for (int end : exonEndsRna1) {
                if (end <= previous || end - previous > exonStride) throw new IllegalArgumentException("INVALID_SYNTHETIC_EXON_LAYOUT");
                previous = end;
            }
        }
        private String genomicSequence() {
            int lastStart = firstExonStart + (exonEndsRna1.size() - 1) * exonStride;
            int beforeLast = exonEndsRna1.size() == 1 ? 0 : exonEndsRna1.get(exonEndsRna1.size() - 2);
            char[] bases = new char[lastStart + rna.length() - beforeLast - 1]; Arrays.fill(bases, 'A');
            int first = 0;
            for (int i = 0; i < exonEndsRna1.size(); i++) {
                int end = exonEndsRna1.get(i), genomic0 = firstExonStart + i * exonStride - 1;
                rna.getChars(first, end, bases, genomic0); first = end;
            }
            return new String(bases);
        }
    }
}
