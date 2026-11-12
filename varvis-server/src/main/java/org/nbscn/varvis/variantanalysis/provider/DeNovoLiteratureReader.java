package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;

/** Replays the pinned source reader once per input/implementation; no model or network. */
public final class DeNovoLiteratureReader {
    public static final String VERSION = "denovo-literature-batch-reader-1.0.0";
    public static final String MODEL_REPLAY_VERSION = "denovo-literature-model-replay-1.0.0";
    private static final List<String> FILES = List.of("run_literature_batch.py", "prepare_literature.py", "extract_literature_cases.py",
            "qualify_literature_cases.py", "literature_allele_identity.py");
    private static final FamilyDeNovoJson JSON = new FamilyDeNovoJson("DENOVO_LITERATURE_");
    private final ObjectMapper mapper;
    private final Path python, scripts, cache;
    private final ExternalProcessRunner runner;
    @Nullable private final Path modelIndex;
    @Nullable private final String modelIndexSha256;
    private int executions;
    @Nullable private DeNovoCandidateRepository.Snapshot candidates;
    @Nullable private DeNovoCoreContextBinding coreContext;
    @Nullable private LiteratureSourceAssets reviewArchive;

    public void withReviewArchive(LiteratureSourceAssets archive) { this.reviewArchive = archive; }

    void retainReviewed(Read read) {
        var archive = reviewArchive;
        if (archive == null || read.issue() != null || read.document() == null) return;
        for (var doc : read.document().path("documents")) {
            if (!"SOURCE_READ".equals(doc.path("status").asText()) || "SYNTHETIC".equals(doc.at("/facts/dataClass").asText())) continue;
            try {
                archive.retainReviewed(doc.path("pmcid").asText(), "DENOVO", doc.path("sourceSha256").asText(), doc.path("facts"));
            } catch (IOException unavailable) {
                org.slf4j.LoggerFactory.getLogger(DeNovoLiteratureReader.class).warn(
                        "Could not retain public de novo material for {}", doc.path("pmcid").asText());
            }
        }
    }

    public DeNovoLiteratureReader withCoreContext(Path root, Path config, String pin) throws IOException {
        coreContext = new DeNovoCoreContextBinding(mapper, root, config, pin);
        coreContext.snapshot();
        return this;
    }

    public DeNovoLiteratureReader(ObjectMapper mapper, Path python, Path scripts, Path cache) {
        this(mapper, python, scripts, cache, new SystemExternalProcessRunner(), null, null);
    }
    /** Explicit offline artifact intake. This reader never receives API credentials. */
    public DeNovoLiteratureReader(ObjectMapper mapper, Path python, Path scripts, Path cache, Path modelIndex, String modelIndexSha256) {
        this(mapper, python, scripts, cache, new SystemExternalProcessRunner(), modelIndex, modelIndexSha256);
    }
    DeNovoLiteratureReader(ObjectMapper mapper, Path python, Path scripts, Path cache, ExternalProcessRunner runner) {
        this(mapper, python, scripts, cache, runner, null, null);
    }
    private DeNovoLiteratureReader(ObjectMapper mapper, Path python, Path scripts, Path cache, ExternalProcessRunner runner,
                                  @Nullable Path modelIndex, @Nullable String modelIndexSha256) {
        this.mapper = mapper; this.python = python.toAbsolutePath().normalize(); this.scripts = scripts.toAbsolutePath().normalize();
        this.cache = cache.toAbsolutePath().normalize(); this.runner = runner;
        this.modelIndex = modelIndex; this.modelIndexSha256 = modelIndexSha256;
    }
    public int executions() { return executions; }
    public DeNovoLiteratureReader withCandidates(Path repository) throws IOException {
        candidates=new DeNovoCandidateRepository(repository).snapshot();return this;
    }
    public boolean hasCandidates() {return candidates!=null && !candidates.artifacts().isEmpty();}
    public String version() { return modelIndex == null && candidates==null ? VERSION : MODEL_REPLAY_VERSION; }
    @Nullable public ObjectNode modelBinding() {
        if (modelIndex == null && candidates==null) return null;
        var result=mapper.createObjectNode().put("version", MODEL_REPLAY_VERSION).put("artifactIndexSha256", modelIndexSha256)
                .put("automaticModelCalls", false).put("sourceScope", "PUBLIC_PAPER_ONLY");
        if(candidates!=null) result.put("candidateSnapshotSha256",candidates.sha256());return result;
    }
    /** Bind newly accepted public originals to existing allele records; per-target science still follows. */
    void appendCandidateDocuments(ArrayNode documents,Map<String,byte[]> originals,ArrayNode citationRecords) throws IOException {
        var candidates = this.candidates;
        if(candidates==null) return;
        var alleles=mapper.createArrayNode();var seen=new java.util.HashSet<String>();
        for(var record:citationRecords) if(seen.add(record.toString())) alleles.add(record);
        for(var document:documents) for(var record:document.path("alleles")) if(seen.add(record.toString())) alleles.add(record);
        for(var intake:candidates.artifacts()) {
            String pin=intake.path("sourceSha256").asText();boolean found=false;
            for(var document:documents) if(document.path("pmcid").equals(intake.path("pmcid"))) {
                if(!pin.equals(document.path("sha256").asText())) throw new IOException("DENOVO_CANDIDATE_SOURCE_VERSION_CHANGED");
                found=true;
            }
            if(!found) {
                var document=documents.addObject().put("sourceId","public-candidate-"+intake.path("pmcid").asText())
                        .put("pmcid",intake.path("pmcid").asText()).put("sha256",pin);
                document.set("alleles",alleles.deepCopy());
            }
            originals.put(pin,DeNovoCandidateRepository.source(intake));
        }
        if(documents.size()>32) throw new IOException("DENOVO_CANDIDATE_DOCUMENT_LIMIT");
    }
    public record Read(ObjectNode document, String sha256, byte[] original, String issue, boolean cached) {
        public Read { document = document == null ? null : document.deepCopy(); original = original == null ? null : original.clone(); }
        @Override public ObjectNode document() { return document == null ? null : document.deepCopy(); }
        @Override public byte[] original() { return original == null ? null : original.clone(); }
    }

    /** source IDs and hashes originate in the material manifest, not an HTTP/model request. */
    public synchronized Read read(ArrayNode documents, Map<String, byte[]> originals, String materialPin) {
        try {
            var request = mapper.createObjectNode().put("schemaVersion", "denovo-literature-reader-request-v1").put("materialPin", materialPin);
            request.set("documents", documents.deepCopy());
            var files = new java.util.ArrayList<>(FILES);
            if (modelIndex != null || candidates!=null) {
                var index=mapper.createObjectNode().put("schemaVersion","denovo-bundle-intake-index-v1");index.putArray("artifacts");
                if(modelIndex!=null) {
                    var modelIndexSha256 = this.modelIndexSha256;
                    if (modelIndexSha256 == null || !modelIndexSha256.matches("[a-f0-9]{64}")) return failed("DENOVO_MODEL_INDEX_PIN_REQUIRED");
                    byte[] indexBytes = bounded(modelIndex);
                    if (!modelIndexSha256.equals(FamilyDeNovoJson.sha256(indexBytes))) return failed("DENOVO_MODEL_INDEX_PIN_MISMATCH");
                    index=JSON.parse(mapper,indexBytes);
                }
                boolean bundleIndex = "denovo-bundle-intake-index-v1".equals(index.path("schemaVersion").asText());
                if ((!"denovo-model-artifact-index-v1".equals(index.path("schemaVersion").asText()) && !bundleIndex)
                        || !index.path("artifacts").isArray() || index.path("artifacts").size() > 20) return failed("DENOVO_MODEL_INDEX_INVALID");
                String effectiveIndex=candidates==null?modelIndexSha256:FamilyDeNovoJson.sha256(mapper.writeValueAsBytes(modelBinding()));
                request.put("schemaVersion", "denovo-literature-reader-request-v2-model-replay").put("modelArtifactIndexSha256", effectiveIndex);
                var artifacts = request.putObject("modelArtifacts");
                Path root = modelIndex==null ? cache : modelIndex.toRealPath().getParent();
                if (root == null) return failed("DENOVO_MODEL_INDEX_INVALID");
                var seen = new java.util.HashSet<String>();
                for (var entry : index.path("artifacts")) {
                    String pin = entry.path("sourceSha256").asText();
                    if (!pin.matches("[a-f0-9]{64}") || !seen.add(pin)) return failed("DENOVO_MODEL_INDEX_INVALID");
                    boolean selected = false;
                    for (var d : documents) if (pin.equals(d.path("sha256").asText()) && entry.path("pmcid").equals(d.path("pmcid"))) selected = true;
                    if (!selected) continue;
                    Path artifact = root.resolve(entry.path("path").asText()).normalize().toRealPath();
                    if (!artifact.startsWith(root)) return failed("DENOVO_MODEL_ARTIFACT_PATH_INVALID");
                    byte[] bytes = bounded(artifact, bundleIndex ? 16777216 : 1048576);
                    if (!entry.path("sha256").asText().equals(FamilyDeNovoJson.sha256(bytes))) return failed("DENOVO_MODEL_ARTIFACT_PIN_MISMATCH");
                    var parsedArtifact = bundleIndex ? parseIntake(bytes) : JSON.parse(mapper, bytes);
                    if (bundleIndex && !List.of("denovo-bundle-candidate-intake-v1", "denovo-model-artifact-v1")
                            .contains(parsedArtifact.path("schemaVersion").asText()))
                        return failed("DENOVO_MODEL_ARTIFACT_SCHEMA_INVALID");
                    artifacts.set(pin, parsedArtifact);
                }
                if(candidates!=null) for(var entry:candidates.artifacts().properties()) {
                    if(artifacts.has(entry.getKey()) && "denovo-bundle-candidate-intake-v1".equals(artifacts.path(entry.getKey()).path("schemaVersion").asText()))
                        DeNovoCandidateRepository.cumulative(artifacts.path(entry.getKey()),entry.getValue());
                    boolean selected=false;
                    for(var d:documents) if(entry.getKey().equals(d.path("sha256").asText()) && entry.getValue().path("pmcid").equals(d.path("pmcid"))) selected=true;
                    if(selected) artifacts.set(entry.getKey(),entry.getValue());
                }
                files.addAll(List.of("model_literature_contract.py", "model_transport.py", "model_replay.py", "run_model_extraction.py"));
                boolean hasBundle = false;
                for (var a : artifacts) hasBundle |= "denovo-bundle-candidate-intake-v1".equals(a.path("schemaVersion").asText());
                if (hasBundle) files.addAll(List.of("bundle_candidate_intake.py", "qualify_bundle_candidates.py",
                        "bundle_model_contract.py", "bundle_model_transport.py", "run_bundle_model.py",
                        "prepare_literature_prompt.py", "literature_attachments.py", "literature_docx.py", "literature_pdf.py", "literature_pdf_worker.py", "official_supplement_source.py",
                        "paper_library_registration.py", "literature_work_units.py",
                        "literature_case_structure.py", "bundle_replay_compatibility.py", "author_manuscript_source.py",
                        "core_model_review_intake.py", "core_model_review_contract.py", "run_core_model_review.py",
                        "core_material_review.py", "core_source_review.py", "core_case_completion.py",
                        "core_material_extraction.py", "core_material_contract.py", "core_material_schema.py",
                        "project_core_material.py", "core_extraction_prompt.py", "bundle_replay_profiles.json",
                        "supplement_case_review.py", "single_case_scope.py", "core_review_replay_profiles.json"));
            }
            if (documents.valueStream().anyMatch(d -> d.has("reviewedPaperSha256"))) files.add("reviewed_denovo_case.py");
            var implementation = request.putObject("implementation");
            for (String name : files) {
                Path file = scripts.resolve(name).toRealPath();
                if (!file.startsWith(scripts.toRealPath()) || Files.size(file) > 131072) return failed("DENOVO_LITERATURE_READER_SOURCE_INVALID");
                implementation.put(name, FamilyDeNovoJson.sha256(Files.readAllBytes(file)));
            }
            // Bundle replay also binds the shared PP2 relationship reader in its own module.
            boolean hasBundleArtifact = request.path("modelArtifacts").valueStream().anyMatch(a ->
                    "denovo-bundle-candidate-intake-v1".equals(a.path("schemaVersion").asText()));
            if (hasBundleArtifact) {
                Path sibling = scripts.toRealPath().resolveSibling("ps1_pm5").toRealPath();
                Path shared = sibling.resolve("pp2_relationship_material.py").toRealPath();
                if (!shared.startsWith(sibling)) return failed("DENOVO_LITERATURE_READER_SOURCE_INVALID");
                implementation.put("pp2_relationship_material.py", FamilyDeNovoJson.sha256(bounded(shared, 131072)));
            }
            ObjectNode coreSnapshot = coreContext == null ? null : coreContext.snapshot();
            if (coreSnapshot != null) {
                request.set("coreContext", coreSnapshot);
                for (String name : DeNovoCoreContextBinding.FILES)
                    implementation.put(name, FamilyDeNovoJson.sha256(bounded(scripts.resolve(name), 131072)));
            }
            var encoded = request.putObject("originals");
            for (var entry : new java.util.TreeMap<>(originals).entrySet())
                encoded.put(entry.getKey(), Base64.getEncoder().encodeToString(entry.getValue()));
            byte[] input = mapper.writeValueAsBytes(request);
            if (input.length > 25165824) return failed("DENOVO_LITERATURE_REQUEST_SIZE_LIMIT");
            String key = FamilyDeNovoJson.sha256(input);
            Files.createDirectories(cache);
            Path task = cache.resolve(key), output = task.resolve("result.json"), receipt = task.resolve("receipt.json");
            boolean cached = Files.isDirectory(task);
            if (cached) {
                if (!Files.isRegularFile(receipt)) return failed("DENOVO_LITERATURE_CACHE_INCOMPLETE");
            } else {
                Files.createDirectory(task);
                Files.write(task.resolve("request.json"), input, StandardOpenOption.CREATE_NEW);
                var process = runner.run(List.of(python.toString(), "-E", "-s", "-B", scripts.resolve(FILES.get(0)).toString(),
                        "--request", task.resolve("request.json").toString(), "--output", output.toString()), scripts, Duration.ofSeconds(60));
                executions++;
                Files.writeString(task.resolve("process.log"), process.getOutput(), StandardOpenOption.CREATE_NEW);
                if (!process.isSuccess()) return failed(process.isTimedOut() ? "DENOVO_LITERATURE_READER_TIMEOUT" : "DENOVO_LITERATURE_READER_FAILED");
                byte[] result = bounded(output, coreSnapshot == null ? 1048576 : 4194304);
                var r = mapper.createObjectNode().put("requestSha256", key).put("resultSha256", FamilyDeNovoJson.sha256(result));
                Files.write(receipt, mapper.writeValueAsBytes(r), StandardOpenOption.CREATE_NEW);
            }
            var r = JSON.parse(mapper, bounded(receipt));
            byte[] result = bounded(output, coreSnapshot == null ? 1048576 : 4194304);
            String pin = FamilyDeNovoJson.sha256(result);
            if (!key.equals(r.path("requestSha256").asText()) || !pin.equals(r.path("resultSha256").asText()))
                return failed("DENOVO_LITERATURE_CACHE_PIN_MISMATCH");
            var parsed = coreSnapshot == null ? JSON.parse(mapper, result) : parseIntake(result);
            String resultSchema = modelIndex == null && candidates==null ? "denovo-literature-reader-result-v1" : "denovo-literature-reader-result-v2-model-replay";
            if (!resultSchema.equals(parsed.path("schemaVersion").asText())
                    || !version().equals(parsed.path("readerVersion").asText()) || !key.equals(parsed.path("requestSha256").asText())
                    || !implementation.equals(parsed.path("implementation")) || parsed.path("modelCalls").asInt(-1) != 0
                    || parsed.path("networkCalls").asInt(-1) != 0 || parsed.path("documents").size() != documents.size())
                return failed("DENOVO_LITERATURE_READER_RESULT_MISMATCH");
            if ((modelIndex != null || candidates!=null) && !request.path("modelArtifactIndexSha256").equals(parsed.path("modelArtifactIndexSha256")))
                return failed("DENOVO_LITERATURE_READER_RESULT_MISMATCH");
            for (int i = 0; i < documents.size(); i++) {
                var expected = documents.get(i); var actual = parsed.path("documents").get(i);
                if (!expected.path("sourceId").equals(actual.path("sourceId"))
                        || !expected.path("pmcid").equals(actual.path("pmcid"))
                        || !expected.path("sha256").equals(actual.path("sourceSha256")))
                    return failed("DENOVO_LITERATURE_READER_RESULT_MISMATCH");
                if ("SOURCE_READ".equals(actual.path("status").asText())
                        && (!expected.path("pmcid").equals(actual.at("/facts/pmcid"))
                        || !expected.path("sha256").equals(actual.at("/facts/sourceSha256"))))
                    return failed("DENOVO_LITERATURE_READER_RESULT_MISMATCH");
            }
            if (coreContext != null && coreSnapshot != null && !coreSnapshot.equals(coreContext.snapshot())) return failed("DENOVO_CORE_DEPENDENCY_CHANGED_DURING_READ");
            return new Read(parsed, pin, result, null, cached);
        } catch (IOException | RuntimeException failure) { return failed("DENOVO_LITERATURE_READER_UNAVAILABLE"); }
    }
    private byte[] bounded(Path path) throws IOException {
        return bounded(path, 1048576);
    }
    private ObjectNode parseIntake(byte[] bytes) throws IOException {
        // Large original attachments and core provenance use this bounded path,
        // never by weakening the shared scientific material JSON limits.
        var factory = new com.fasterxml.jackson.core.JsonFactory();
        factory.setStreamReadConstraints(com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxNestingDepth(32).maxStringLength(16777216).build());
        try (var parser = factory.createParser(bytes)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            var node = mapper.readTree(parser);
            if (!(node instanceof ObjectNode object) || parser.nextToken() != null) throw JSON.invalid("SINGLE_OBJECT_REQUIRED");
            JSON.depth(object, 0); return object;
        }
    }
    private byte[] bounded(Path path, int maximum) throws IOException {
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(maximum + 1);
            if (bytes.length == 0 || bytes.length > maximum) throw JSON.invalid("RESULT_SIZE_LIMIT");
            return bytes;
        }
    }
    private static Read failed(String reason) { return new Read(null, null, null, reason, false); }
}
