package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.lang.Nullable;

/** Current source replay for the HCM PP2 exclusion condition, never a patient diagnosis or a new classification. */
public final class Pp2OtherPhenotypeSource {
    public static final String VERSION = "pp2-other-phenotype-source-v3";
    public static final String QUALIFICATION_VERSION = "pp2-bounded-literature-qualification-v1";
    public static final String SOURCE_PROJECTION_VERSION = "pp2-phenotype-source-projection-v2";
    public static final String SOURCE_DISPOSITION_VERSION = "pp2-source-disposition-v2";
    private static final int MAX_FILE = 4 * 1024 * 1024;
    private static final long MAX_TOTAL = 32L * 1024 * 1024;
    private static final List<String> READER_FILES = List.of("scripts/ps1_pm5/read_pp2_other_phenotype_sources.py",
            "scripts/ps1_pm5/prepare_expert_reference_evidence.py", "scripts/ps1_pm5/prepare_reference_evidence.py",
            "scripts/denovo/prepare_literature.py", "scripts/denovo/extract_literature_cases.py");

    /** Configuration-owned paths/pins to existing sources and existing adoption. No reviewed/clear flag is accepted. */
    public record ExpertInventory(String scopeId, Path controlledRoot, Path manifest, String manifestRawSha256,
            @Nullable Path adoptedBundle, String adoptedBundleRawSha256, String adoptedBundleCanonicalSha256) {
        public ExpertInventory(String scopeId, Path controlledRoot, Path manifest, String manifestRawSha256) {
            this(scopeId, controlledRoot, manifest, manifestRawSha256, null, "NOT_CONFIGURED", "NOT_CONFIGURED");
        }
    }
    public record LiteratureInventory(String scopeId, Path controlledRoot, Path index, String indexRawSha256) { }
    /** Ranked public allele plus verified selected background: discovery declaration, never an evidence context or patient diagnosis. */
    public record DiscoveryTarget(String candidateId, String hypothesisId, ObjectNode genomicAllele, String hgncId,
            String geneSymbol, String mondoId, String moi, ObjectNode formalCandidate) {
        public DiscoveryTarget { genomicAllele = genomicAllele.deepCopy(); formalCandidate = formalCandidate.deepCopy(); }
        @Override public ObjectNode genomicAllele() { return genomicAllele.deepCopy(); }
        @Override public ObjectNode formalCandidate() { return formalCandidate.deepCopy(); }
    }
    private record ReaderSetup(Path python, Path project, Path work) { }
    public enum Relation { SAME_HCM_FAMILY, DISTINCT, UNRESOLVED, CONFLICT }
    /** The resolver must own its actual ontology/source semantics; unequal identifiers alone never mean DISTINCT. */
    public interface DiseaseRelationResolver {
        ObjectNode identity();
        Relation resolve(JsonNode actualNativeExpertFacts, ObjectNode currentContext, ObjectNode verifiedPolicyBinding);
    }
    public enum Outcome { SUPPORTS_OTHER_PHENOTYPE, NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE, UNKNOWN, CONFLICT, INVALID }

    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    @Nullable private final ReaderSetup readerSetup;
    @Nullable private final Pp2OtherPhenotypeWorkflow workflow;
    private final List<ExpertInventory> experts;
    private final List<LiteratureInventory> literature;
    private final DiseaseRelationResolver relations;
    private final ExternalProcessRunner runner;
    private final java.util.Map<Observed, Pp2LiteratureAssessment> derived = new java.util.IdentityHashMap<>();

    public Pp2OtherPhenotypeSource(ObjectMapper mapper, Path python, Path project, Path work,
            List<ExpertInventory> experts, List<LiteratureInventory> literature) {
        this(mapper, python, project, work, experts, literature, new Pp2OtherPhenotypeDiseaseSemantics(mapper));
    }
    public Pp2OtherPhenotypeSource(ObjectMapper mapper, Path python, Path project, Path work,
            List<ExpertInventory> experts, List<LiteratureInventory> literature, DiseaseRelationResolver relations) {
        this(mapper, python, project, work, experts, literature, relations, new SystemExternalProcessRunner());
    }
    Pp2OtherPhenotypeSource(ObjectMapper mapper, Path python, Path project, Path work,
            List<ExpertInventory> experts, List<LiteratureInventory> literature, DiseaseRelationResolver relations,
            ExternalProcessRunner runner) {
        this(mapper, new ReaderSetup(python.toAbsolutePath().normalize(), project.toAbsolutePath().normalize(), work.toAbsolutePath().normalize()),
                experts, literature, relations, runner, null);
    }
    private Pp2OtherPhenotypeSource(ObjectMapper mapper, @Nullable ReaderSetup setup,
            List<ExpertInventory> experts, List<LiteratureInventory> literature, DiseaseRelationResolver relations,
            ExternalProcessRunner runner, @Nullable Pp2OtherPhenotypeWorkflow workflow) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.adapter = new RuntimeEvidenceV2Adapter(this.mapper); this.readerSetup = setup; this.workflow = workflow;
        this.experts = List.copyOf(experts); this.literature = List.copyOf(literature);
        this.relations = Objects.requireNonNull(relations); this.runner = Objects.requireNonNull(runner);
        var ids = new java.util.HashSet<String>();
        for (var item : experts) require(item.scopeId().matches("[A-Za-z0-9_-]{1,80}") && ids.add(item.scopeId()), "PP2_SOURCE_SCOPE_ID_INVALID");
        for (var item : literature) require(item.scopeId().matches("[A-Za-z0-9_-]{1,80}") && ids.add(item.scopeId()), "PP2_SOURCE_SCOPE_ID_INVALID");
        require(experts.size() <= 32 && literature.size() <= 16, "PP2_SOURCE_SCOPE_TECHNICAL_LIMIT");
    }
    /** Lazy factory: identity construction does not read configuration, invoke native acquisition or open the model. */
    public static Pp2OtherPhenotypeSource forWorkflow(ObjectMapper mapper, Path workflowConfig, String workflowRawSha256,
            Path sourceWorkRoot, Supplier<Pvs1StartLossPublicSources> nativeFactory) {
        return new Pp2OtherPhenotypeSource(mapper, null, List.of(), List.of(), new Pp2OtherPhenotypeDiseaseSemantics(mapper),
                new SystemExternalProcessRunner(), new Pp2OtherPhenotypeWorkflow(workflowConfig, workflowRawSha256, sourceWorkRoot, nativeFactory));
    }

    /** No source files or Python processes are opened here. Actual observed inputs are supplied by observe(). */
    public ObjectNode identity() {
        var out = mapper.createObjectNode().put("methodVersion", VERSION).put("criterionId", "ACMG_AMP_2015:PP2")
                .put("sourceDispositionVersion", SOURCE_DISPOSITION_VERSION).put("sourceProjectionVersion", SOURCE_PROJECTION_VERSION)
                .put("sourceInterpretation", "NATIVE_PUBLISHED_EXPERT_ASSERTION_ORIGINAL_RETAINED")
                .put("sourceQualificationRoute", "INDEPENDENT_NATIVE_EXPERT_ASSERTION_ROUTE")
                .put("nativeExpertAssertionVersion", Pp2NativeExpertAssertion.VERSION)
                .put("caseRelationshipCapability", workflow == null ? "SOFTWARE_UNSUPPORTED" : "BOUNDED_SOURCE_OWNED_OBSERVATION_REVIEW")
                .put("scientificClassificationCreated", false);
        if (workflow != null) out.put("qualificationVersion", QUALIFICATION_VERSION);
        out.set("diseaseRelation", relations.identity().deepCopy());
        out.putObject("derivedAssessment").put("methodVersion", Pp2LiteratureAssessment.VERSION)
                .put("stage", "AFTER_CLAIM").put("rawFactsAreScientificQualification", false);
        var workflowSource = workflow;
        if (workflowSource != null) out.set("automaticWorkflow", workflowSource.identity(mapper));
        var declared = out.putArray("declaredInventories");
        for (var input : experts) {
            var row = declared.addObject().put("scopeId", input.scopeId()).put("kind", "EXPERT")
                    .put("manifestRawSha256", input.manifestRawSha256()).put("existingComparisonAuditConfigured", input.adoptedBundle() != null);
            if (input.adoptedBundle() != null) row.put("adoptedBundleRawSha256", input.adoptedBundleRawSha256())
                    .put("adoptedBundleCanonicalSha256", input.adoptedBundleCanonicalSha256());
        }
        for (var input : literature) declared.addObject().put("scopeId", input.scopeId()).put("kind", "LITERATURE")
                .put("indexRawSha256", input.indexRawSha256());
        return out;
    }

    private record Read(boolean usable, String rawSha256, byte[] bytes) { }
    private final class ObservationBuilder {
        private final ObjectNode originalBytes = mapper.createObjectNode();
        private final ArrayNode reads = mapper.createArrayNode();
        private long total;
        private Read read(String id, Path root, Path path, String expected) {
            var item = reads.addObject().put("readId", id).put("expectedRawSha256", expected).put("status", "UNAVAILABLE");
            try {
                require(expected.matches("[a-f0-9]{64}"), "PP2_SOURCE_EXPECTED_PIN_INVALID");
                Path controlled = root.toRealPath();
                Path real = (path.isAbsolute() ? path : controlled.resolve(path)).toRealPath();
                require(real.startsWith(controlled), "PP2_SOURCE_PATH_OUTSIDE_CONTROLLED_ROOT");
                byte[] raw;
                try (var stream = Files.newInputStream(real)) { raw = stream.readNBytes(MAX_FILE + 1); }
                require(raw.length <= MAX_FILE, "PP2_SOURCE_TECHNICAL_BYTE_LIMIT");
                String observed = CodingTranscriptMaterialSupply.sha(raw); item.put("observedRawSha256", observed);
                if (!originalBytes.has(observed)) {
                    require(total + raw.length <= MAX_TOTAL, "PP2_SOURCE_TECHNICAL_TOTAL_LIMIT");
                    total += raw.length; originalBytes.putObject(observed).put("rawSha256", observed)
                            .put("bytes", raw.length).put("base64", Base64.getEncoder().encodeToString(raw));
                }
                item.put("archivedRawSha256", observed);
                require(expected.equals(observed), "PP2_SOURCE_RAW_PIN_MISMATCH");
                item.put("status", "READ"); return new Read(true, observed, raw);
            } catch (IOException | RuntimeException invalid) {
                item.put("reasonCode", safe(invalid, "PP2_SOURCE_ORIGINAL_UNAVAILABLE"));
                return new Read(false, "UNAVAILABLE", new byte[0]);
            }
        }
    }

    /** Actual input observation belongs before byRequest; identity never contains the original base64/whole inventory facts. */
    public Observed observe() {
        if (workflow != null) return observe(List.of());
        var setup = Objects.requireNonNull(readerSetup);
        Path python = setup.python(), project = setup.project(), work = setup.work();
        var builder = new ObservationBuilder(); var request = mapper.createObjectNode()
                .put("schemaVersion", "pp2-other-phenotype-reader-input-v1");
        var expertScopes = request.putArray("expertScopes"); var papers = request.putArray("literature");
        var method = mapper.createObjectNode(); boolean readerReady = true;
        for (String file : READER_FILES) {
            try { byte[] bytes = Files.readAllBytes(project.resolve(file)); method.put(file, CodingTranscriptMaterialSupply.sha(bytes)); }
            catch (IOException unreadable) { method.put(file, "UNAVAILABLE"); readerReady = false; }
        }
        for (var input : experts) {
            var manifest = builder.read(input.scopeId() + ":manifest", input.controlledRoot(), input.manifest(), input.manifestRawSha256());
            var adoptedPath = input.adoptedBundle();
            if (adoptedPath != null) {
                var adopted = builder.read(input.scopeId() + ":optionalComparisonAudit", input.controlledRoot(), adoptedPath, input.adoptedBundleRawSha256());
                if (adopted.usable()) try {
                    var bundle = object(mapper.readTree(adopted.bytes()));
                    require(adapter.canonicalSha256(bundle).equals(input.adoptedBundleCanonicalSha256()), "PP2_ADOPTION_CANONICAL_PIN_MISMATCH");
                } catch (IOException | RuntimeException invalid) {
                    builder.reads.addObject().put("readId", input.scopeId() + ":optionalComparisonAuditFacts").put("status", "UNAVAILABLE")
                            .put("reasonCode", safe(invalid, "PP2_EXISTING_ADOPTION_UNAVAILABLE"));
                }
            }
            if (!manifest.usable()) continue;
            try {
                var value = object(mapper.readTree(manifest.bytes()));
                require("missense-expert-evidence-manifest-v1".equals(value.path("schemaVersion").asText()), "PP2_EXPERT_MANIFEST_INVALID");
                require(value.path("sources").isArray() && value.path("sources").size() <= 768, "PP2_EXPERT_MANIFEST_INVALID");
                Path manifestPath = input.manifest().isAbsolute() ? input.manifest() : input.controlledRoot().resolve(input.manifest());
                Path parent = Objects.requireNonNull(manifestPath.toAbsolutePath().normalize().getParent());
                for (var source : value.path("sources")) builder.read(input.scopeId() + ":" + source.path("sourceId").asText(),
                        input.controlledRoot(), parent.resolve(source.path("path").asText()), source.path("rawSha256").asText());
                expertScopes.addObject().put("scopeId", input.scopeId()).put("manifestRawSha256", manifest.rawSha256());
            } catch (IOException | RuntimeException invalid) {
                builder.reads.addObject().put("readId", input.scopeId() + ":manifestFacts").put("status", "UNAVAILABLE")
                        .put("reasonCode", safe(invalid, "PP2_EXPERT_MANIFEST_INVALID"));
            }
        }
        for (var input : literature) {
            var index = builder.read(input.scopeId() + ":index", input.controlledRoot(), input.index(), input.indexRawSha256());
            if (!index.usable()) continue;
            try {
                var value = object(mapper.readTree(index.bytes()));
                require("public-paper-evidence-index-v1".equals(value.path("schemaVersion").asText())
                        && value.path("documents").isArray() && value.path("documents").size() <= 256, "PP2_PAPER_INDEX_INVALID");
                Path indexPath = input.index().isAbsolute() ? input.index() : input.controlledRoot().resolve(input.index());
                Path parent = Objects.requireNonNull(indexPath.toAbsolutePath().normalize().getParent());
                for (var source : value.path("documents")) {
                    String id = input.scopeId() + ":" + source.path("pmcid").asText();
                    var original = builder.read(id, input.controlledRoot(), parent.resolve(source.path("path").asText()), source.path("sha256").asText());
                    if (original.usable()) papers.addObject().put("sourceId", id).put("pmcid", source.path("pmcid").asText()).put("rawSha256", original.rawSha256());
                }
            } catch (IOException | RuntimeException invalid) {
                builder.reads.addObject().put("readId", input.scopeId() + ":indexFacts").put("status", "UNAVAILABLE")
                        .put("reasonCode", safe(invalid, "PP2_PAPER_INDEX_INVALID"));
            }
        }
        request.set("originals", builder.originalBytes.deepCopy());
        var reader = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-native-reader-v1").put("status", "UNAVAILABLE");
        String readerInputSha;
        try {
            byte[] inputBytes = mapper.writeValueAsBytes(request); readerInputSha = CodingTranscriptMaterialSupply.sha(inputBytes);
            if (readerReady) {
                Files.createDirectories(work); var directory = Files.createTempDirectory(work, "read-");
                Path input = directory.resolve("input.json"), output = directory.resolve("facts.json");
                Files.write(input, inputBytes, StandardOpenOption.CREATE_NEW);
                var process = runner.run(List.of(python.toString(), project.resolve(READER_FILES.get(0)).toString(),
                        "--input", input.toString(), "--output", output.toString()), project, Duration.ofSeconds(60));
                require(process.isSuccess(), process.isTimedOut() ? "PP2_NATIVE_READER_TIMEOUT" : "PP2_NATIVE_READER_FAILED");
                byte[] raw;
                try (var stream = Files.newInputStream(output)) { raw = stream.readNBytes((int) MAX_TOTAL + 1); }
                require(raw.length <= MAX_TOTAL, "PP2_NATIVE_READER_TECHNICAL_OUTPUT_LIMIT");
                reader = object(mapper.readTree(raw));
                require("pp2-other-phenotype-native-reader-v1".equals(reader.path("schemaVersion").asText())
                        && reader.path("scientificAdoption").isBoolean() && !reader.path("scientificAdoption").asBoolean(), "PP2_NATIVE_READER_RESULT_INVALID");
                require(readerInputSha.equals(reader.path("inputRawSha256").asText()), "PP2_NATIVE_READER_INPUT_PIN_MISMATCH");
                for (String file : READER_FILES) require(method.path(file).asText().equals(
                        CodingTranscriptMaterialSupply.sha(Files.readAllBytes(project.resolve(file)))), "PP2_NATIVE_READER_CHANGED_DURING_REPLAY");
                reader.put("status", "READ");
            } else reader.put("reasonCode", "PP2_NATIVE_READER_IMPLEMENTATION_UNAVAILABLE");
        } catch (IOException | RuntimeException unavailable) {
            readerInputSha = adapter.canonicalSha256(request);
            reader = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-native-reader-v1")
                    .put("status", "UNAVAILABLE").put("reasonCode", safe(unavailable, "PP2_NATIVE_READER_UNAVAILABLE"));
        }
        var observed = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-source-observation-v1")
                .put("readerInputSha256", readerInputSha).put("readerFactsSha256", adapter.canonicalSha256(reader))
                .put("methodSourcesSha256", adapter.canonicalSha256(method)).put("readerStatus", reader.path("status").asText());
        observed.set("sourceCapability", identity()); observed.set("readDispositions", builder.reads.deepCopy());
        observed.put("observationSha256", adapter.canonicalSha256(observed));
        return new Observed(this, observed, builder.originalBytes, method, reader);
    }

    /** One actual source observation for all declared targets; callers must use the same owner after claim. */
    public Observed observe(List<DiscoveryTarget> targets) {
        var declarations = mapper.createArrayNode(); var ids = new java.util.HashSet<String>();
        for (var target : targets) {
            require(!target.candidateId().isBlank() && !target.hypothesisId().isBlank()
                    && ids.add(target.candidateId() + "\u0000" + target.hypothesisId()), "PP2_DISCOVERY_TARGET_DUPLICATE_OR_INVALID");
            var row = declarations.addObject().put("candidateId", target.candidateId()).put("hypothesisId", target.hypothesisId())
                    .put("hgncId", target.hgncId()).put("geneSymbol", target.geneSymbol()).put("mondoId", target.mondoId()).put("moi", target.moi())
                    .put("formalCandidateSha256", adapter.canonicalSha256(target.formalCandidate()));
            row.set("genomicAllele", target.genomicAllele());
        }
        var workflowSource = workflow;
        Observed original;
        if (workflowSource == null) original = observe();
        else {
            var pending = workflowSource.prepare(mapper, targets);
            boolean actualRead = "READ".equals(pending.reader().path("status").asText());
            String readerInputSha256 = actualRead
                    ? pending.configurationObservation().path("readerInputRawSha256").asText()
                    : adapter.canonicalSha256(declarations);
            require(!actualRead || readerInputSha256.matches("[a-f0-9]{64}")
                    && readerInputSha256.equals(pending.reader().path("inputRawSha256").asText()),
                    "PP2_AUTOMATIC_READER_ACTUAL_INPUT_BINDING_INVALID");
            var observation = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-source-observation-v1")
                    .put("readerInputSha256", readerInputSha256)
                    .put("readerFactsSha256", adapter.canonicalSha256(pending.reader()))
                    .put("methodSourcesSha256", adapter.canonicalSha256(pending.methodSources()))
                    .put("readerStatus", pending.reader().path("status").asText());
            observation.set("sourceCapability", identity()); observation.set("readDispositions", pending.readDispositions());
            observation.set("configurationObservation", pending.configurationObservation());
            original = new Observed(this, observation, pending.originals(), pending.methodSources(), pending.reader());
        }
        var observation = original.observation.deepCopy(); observation.remove("observationSha256");
        observation.put("targetRole", "DISCOVERY_ONLY_NOT_SCIENTIFIC_CONTEXT"); observation.set("discoveryTargets", declarations);
        observation.put("discoveryTargetsSha256", adapter.canonicalSha256(declarations));
        observation.put("observationSha256", adapter.canonicalSha256(observation));
        return new Observed(this, observation, original.originals, original.method, original.reader);
    }

    public static final class Observed {
        private final Pp2OtherPhenotypeSource owner;
        private final ObjectNode observation, originals, method, reader;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private Observed(Pp2OtherPhenotypeSource owner, ObjectNode observation, ObjectNode originals, ObjectNode method,
                ObjectNode reader) {
            this.owner = owner; this.observation = observation.deepCopy(); this.originals = originals.deepCopy();
            this.method = method.deepCopy(); this.reader = reader.deepCopy();
        }
        /** Small actual-observation identity for the pre-request key, never raw material or a scientific grant. */
        public ObjectNode identity() {
            return owner.mapper.createObjectNode().put("methodVersion", VERSION)
                    .put("observationSha256", observation.path("observationSha256").asText())
                    .put("methodSourcesSha256", observation.path("methodSourcesSha256").asText())
                    .put("readerStatus", observation.path("readerStatus").asText());
        }
        public ObjectNode snapshot() { return observation.deepCopy(); }
        /** One shared archive for the batch. Per-target reviews contain only source references. */
        public ObjectNode privateArchive() {
            var out = owner.mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-private-archive-v1");
            out.set("observation", observation.deepCopy()); out.set("originals", originals.deepCopy());
            out.set("methodSources", method.deepCopy()); out.set("readerFacts", reader.deepCopy());
            out.put("archiveSha256", owner.adapter.canonicalSha256(out)); return out;
        }
    }

    public Captured capture(ObjectNode context, String candidateId, ObjectNode verifiedHcmPolicyBinding) {
        return capture(observe(), context, candidateId, verifiedHcmPolicyBinding);
    }
    public Captured capture(Observed observed, ObjectNode context, String candidateId, ObjectNode verifiedHcmPolicyBinding) {
        return captureBound(observed, context, candidateId, null, verifiedHcmPolicyBinding);
    }
    public Captured capture(Observed observed, ObjectNode context, String candidateId, String hypothesisId, ObjectNode verifiedHcmPolicyBinding) {
        return captureBound(observed, context, candidateId, hypothesisId, verifiedHcmPolicyBinding);
    }
    /** Actual opaque transcript material can resolve novel-variant search aliases; it never supplies phenotype causality. */
    public Captured capture(Observed observed, ObjectNode context, String candidateId, String hypothesisId,
            ObjectNode verifiedHcmPolicyBinding, CodingTranscriptMaterialSupply.Material material) {
        return captureBound(observed, context, candidateId, hypothesisId, verifiedHcmPolicyBinding, material);
    }
    private Captured captureBound(Observed observed, ObjectNode context, String candidateId, @Nullable String hypothesisId, ObjectNode verifiedHcmPolicyBinding) {
        return captureBound(observed, context, candidateId, hypothesisId, verifiedHcmPolicyBinding, null);
    }
    private Captured captureBound(Observed observed, ObjectNode context, String candidateId, @Nullable String hypothesisId,
            ObjectNode verifiedHcmPolicyBinding, @Nullable CodingTranscriptMaterialSupply.Material material) {
        require(observed.owner == this, "PP2_OTHER_PHENOTYPE_OBSERVATION_OWNER_MISMATCH");
        var out = mapper.createObjectNode().put("schemaVersion", "pp2-other-phenotype-review-v1").put("methodVersion", VERSION)
                .put("sourceProjectionVersion", SOURCE_PROJECTION_VERSION);
        var binding = out.putObject("binding").put("candidateId", candidateId).put("contextSha256", adapter.canonicalSha256(context))
                .put("hcmPolicyBindingSha256", adapter.canonicalSha256(verifiedHcmPolicyBinding))
                .put("sourceSnapshotSha256", observed.observation.path("observationSha256").asText());
        binding.set("allele", context.at("/variant/evidenceQueryAllele").deepCopy());
        var required = out.putArray("requiredScope"); var accepted = out.putArray("qualifiedEvidence");
        var pending = out.putArray("pending"); var conflicts = out.putArray("conflicts");
        var targetReads = out.putArray("sourceReadDispositionIds");
        if (hypothesisId != null) binding.put("hypothesisId", hypothesisId);
        if (observed.observation.has("discoveryTargets")) {
            int matching = 0; boolean same = false; String selectedHypothesis = "";
            for (var target : observed.observation.path("discoveryTargets")) {
                if (!candidateId.equals(target.path("candidateId").asText())
                        || hypothesisId != null && !hypothesisId.equals(target.path("hypothesisId").asText())) continue;
                matching++; selectedHypothesis = target.path("hypothesisId").asText();
                same = target.path("genomicAllele").equals(context.at("/variant/evidenceQueryAllele"))
                        && target.path("hgncId").equals(context.at("/gene/hgncId")) && target.path("geneSymbol").equals(context.at("/gene/symbol"))
                        && target.path("mondoId").equals(context.at("/disease/mondoId")) && target.path("moi").equals(context.path("moi"));
            }
            if (matching != 1 || !same) {
                out.put("outcome", Outcome.INVALID.name()).put("reasonCode", matching > 1
                        ? "PP2_SOURCE_DISCOVERY_TARGET_BINDING_AMBIGUOUS" : "PP2_SOURCE_DISCOVERY_TARGET_CONTEXT_MISMATCH");
                return new Captured(this, out);
            }
            binding.put("hypothesisId", selectedHypothesis);
        }
        if (candidateId.isBlank() || !context.at("/variant/evidenceQueryAllele").isObject()
                || !context.at("/disease/mondoId").asText().matches("MONDO:[0-9]{7}") || verifiedHcmPolicyBinding.isEmpty()) {
            out.put("outcome", Outcome.INVALID.name()).put("reasonCode", "PP2_OTHER_PHENOTYPE_TARGET_BINDING_INVALID");
            return new Captured(this, out);
        }
        String targetId = "target-" + CodingTranscriptMaterialSupply.sha((candidateId + "\u0000" + binding.path("hypothesisId").asText())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 40);
        JsonNode nativeScope = mapper.createObjectNode(), discoveryScope = mapper.createObjectNode();
        for (var scope : observed.reader.path("nativeCoverage")) if (targetId.equals(scope.path("targetId").asText())) nativeScope = scope;
        for (var scope : observed.reader.path("discoveryScopes")) if (targetId.equals(scope.path("targetId").asText())) discoveryScope = scope;
        boolean nativeComplete = "COMPLETE".equals(nativeScope.path("status").asText());
        if (workflow != null) for (var read : observed.observation.path("readDispositions")) targetReads.add(read.path("readId").asText());
        var expertCoverage = required.addObject().put("scope", "EXACT_TARGET_NATIVE_EXPERT_ASSERTIONS").put("status", "INCOMPLETE")
                .put("scopeDefinition", "DECLARED_NATIVE_EXPERT_RECORDS_NOT_EXHAUSTIVE_OTHER_PHENOTYPE_REVIEW");
        boolean workflowUnavailable = workflow != null && !"READ".equals(observed.reader.path("status").asText());
        if (workflow != null) {
            expertCoverage.put("scopeDefinition", "CURRENT_EREPO_EXPERT_ASSERTIONS_FOR_DECLARED_GENE");
            var coverage = mapper.createObjectNode();
            for (String key : List.of("targetId", "scope", "status")) if (nativeScope.has(key)) coverage.set(key, nativeScope.path(key).deepCopy());
            coverage.put("sourceRows", nativeScope.path("rows").size()).put("pendingRows", nativeScope.path("pending").size())
                    .put("sourceLedgerSha256", adapter.canonicalSha256(nativeScope));
            expertCoverage.set("nativeSourceCoverage", coverage);
            if (workflowUnavailable) expertCoverage.put("status", "UNAVAILABLE")
                    .put("reasonCode", observed.reader.path("reasonCode").asText("PP2_AUTOMATIC_SOURCE_UNAVAILABLE"));
            for (var gap : nativeScope.path("pending")) pending.add(gap.deepCopy());
        }
        if (!"READ".equals(observed.reader.path("status").asText())) pending.addObject()
                .put("reasonCode", observed.reader.path("reasonCode").asText("PP2_NATIVE_READER_UNAVAILABLE"));
        int targets = 0, resolved = 0;
        var assertionFacts = new LinkedHashMap<String, String>(); var countedAssertions = new java.util.HashSet<String>();
        for (var scope : observed.reader.path("expertScopes")) {
            String scopeId = scope.path("scopeId").asText();
            for (var row : scope.path("references")) {
                if (!row.path("declaredAllele").equals(context.at("/variant/evidenceQueryAllele"))) continue;
                targets++; var facts = row.path("facts");
                var item = mapper.createObjectNode().put("scopeId", scopeId).put("referenceId", row.path("referenceId").asText())
                        .put("sourceFactSha256", adapter.canonicalSha256(facts));
                if (workflow == null) for (var sourceId : row.path("requiredSourceIds")) targetReads.add(scopeId + ":" + sourceId.asText());
                if (!"SOURCE_FACTS_READ".equals(row.path("status").asText())) {
                    item.put("reasonCode", row.path("reasonCode").asText()); pending.add(item); continue;
                }
                boolean sameGene = false;
                for (var gene : facts.path("geneSymbols")) if (gene.equals(context.at("/gene/symbol"))) sameGene = true;
                if (!sameGene || facts.path("originalExpertSummary").isObject()
                        && !facts.at("/originalExpertSummary/gene").equals(context.at("/gene/symbol"))
                        || !facts.path("allele").equals(context.at("/variant/evidenceQueryAllele"))) {
                    item.put("reasonCode", "PP2_NATIVE_EXPERT_TARGET_CONTEXT_MISMATCH"); pending.add(item); continue;
                }
                String issue = Pp2NativeExpertAssertion.issue(facts, scope.path("sourcePins"), observed.originals);
                if (issue != null) {
                    item.put("reasonCode", issue); if (issue.endsWith("CONFLICT")) {
                        nativeSummaryDifferences(item, facts); conflicts.add(item);
                    } else pending.add(item); continue;
                }
                var adverse = conflictingNativeExpertAssertion(facts);
                if (adverse != null) {
                    item.put("reasonCode", "PP2_NATIVE_EXPERT_ASSERTION_SOURCE_CONFLICT");
                    difference(item, "classification", facts.at("/originalAssertion/classification"), adverse.path("classification"),
                            facts.at("/originalAssertion/scv").asText(), adverse.path("scv").asText(), facts.path("sourceIds"));
                    conflicts.add(item); continue;
                }
                String assertion = facts.at("/originalAssertion/scv").asText();
                var assertionCore = mapper.createObjectNode();
                for (String key : List.of("allele", "disease", "originalAssertion", "originalExpertSummary"))
                    assertionCore.set(key, facts.path(key).deepCopy());
                String previous = assertionFacts.putIfAbsent(assertion, adapter.canonicalSha256(assertionCore));
                if (previous != null && !previous.equals(adapter.canonicalSha256(assertionCore))) {
                    item.put("reasonCode", "PP2_NATIVE_EXPERT_ASSERTION_SOURCE_CONFLICT"); conflicts.add(item); continue;
                }
                var relation = relations.resolve(facts, context.deepCopy(), verifiedHcmPolicyBinding.deepCopy());
                item.put("relation", relation.name()).put("scv", facts.at("/originalAssertion/scv").asText())
                        .put("originalClassification", facts.at("/originalAssertion/classification").asText())
                        .put("sourceQualification", "NATIVE_PUBLISHED_EXPERT_ASSERTION_REPLAYED")
                        .put("classificationAdoption", "ORIGINAL_RETAINED_NOT_RECLASSIFIED");
                item.set("disease", facts.path("disease").deepCopy()); item.set("sourceIds", facts.path("sourceIds").deepCopy());
                if (relation == Relation.CONFLICT) {
                    item.put("reasonCode", "PP2_OTHER_PHENOTYPE_RELATIONSHIP_CONFLICT");
                    difference(item, "diseaseConcept", facts.at("/disease/id"), facts.at("/disease/label"),
                            facts.at("/originalAssertion/scv").asText(), facts.at("/originalExpertSummary/uuid").asText(), facts.path("sourceIds"));
                    conflicts.add(item);
                }
                else if (relation == Relation.UNRESOLVED) { item.put("reasonCode", "PP2_OTHER_PHENOTYPE_DISEASE_RELATIONSHIP_UNRESOLVED"); pending.add(item); }
                else { resolved++; if (relation == Relation.DISTINCT && countedAssertions.add(assertion)) accepted.add(item); }
            }
        }
        expertCoverage.put("targetRecords", targets).put("qualifiedRecords", resolved);
        if ((targets > 0 || nativeComplete) && targets == resolved && conflicts.isEmpty())
            expertCoverage.put("status", "COMPLETED_DECLARED_TARGET_SCOPE");
        if (targets == 0 && !nativeComplete && !workflowUnavailable) pending.addObject().put("reasonCode", "PP2_TARGET_NATIVE_EXPERT_SCOPE_NOT_ESTABLISHED");
        boolean literatureComplete = false;
        if (workflow != null && discoveryScope.isObject() && !discoveryScope.isEmpty()) {
            var retrieval = discoveryScope.path("retrieval");
            boolean originalsPresent = observed.originals.has(retrieval.path("inputSha256").asText());
            for (var pin : retrieval.path("responsePins")) if (!observed.originals.has(pin.path("sha256").asText())) originalsPresent = false;
            for (var pin : retrieval.path("targetSourcePins")) if (!observed.originals.has(pin.path("sourceSha256").asText())) originalsPresent = false;
            boolean aliasesBound = "NATIVE_VCV_EXACT_ALLELE".equals(discoveryScope.path("aliasIdentity").asText())
                    || materialAliasesMatch(material, context, verifiedHcmPolicyBinding, discoveryScope.path("declaredAliases"));
            boolean retrievalComplete = originalsPresent && aliasesBound && "COMPLETE".equals(retrieval.path("status").asText())
                    && retrieval.path("remaining").isArray() && retrieval.path("remaining").isEmpty()
                    && retrieval.path("errors").isArray() && retrieval.path("errors").isEmpty();
            literatureComplete = nativeComplete && originalsPresent && aliasesBound
                    && "COMPLETE".equals(discoveryScope.path("status").asText())
                    && "COMPLETE".equals(retrieval.path("status").asText()) && retrieval.path("candidates").isArray()
                    && retrieval.path("candidates").isEmpty() && retrieval.path("remaining").isArray() && retrieval.path("remaining").isEmpty()
                    && retrieval.path("errors").isArray() && retrieval.path("errors").isEmpty()
                    && discoveryScope.path("knownCitationReferences").isArray() && discoveryScope.path("knownCitationReferences").isEmpty();
            var coverage = required.addObject().put("scope", "TARGET_LITERATURE_RELATIONSHIP_REVIEW")
                    .put("status", literatureComplete ? "COMPLETED_DECLARED_TARGET_SCOPE" : "INCOMPLETE")
                    .put("scopeDefinition", "EXECUTED_TARGET_ALIAS_QUERIES_AND_ALL_RETURNED_TARGET_PUBLICATIONS")
                    .put("retrievalLedgerSha256", retrieval.path("ledgerSha256").asText())
                    .put("candidatePapers", retrieval.path("candidateCount").asInt()).put("aliasIdentityVerified", aliasesBound)
                    .put("retrievalScopeComplete", retrievalComplete);
            coverage.set("declaredAliases", discoveryScope.path("declaredAliases").deepCopy());
            coverage.set("knownCitationReferences", discoveryScope.path("knownCitationReferences").deepCopy());
            if (!literatureComplete) pending.addObject().put("reasonCode", !originalsPresent
                    ? "PP2_DISCOVERY_ORIGINALS_UNAVAILABLE" : !aliasesBound ? "PP2_DISCOVERY_TARGET_ALIAS_IDENTITY_NOT_VERIFIED"
                    : discoveryScope.path("reasonCode").isTextual() ? discoveryScope.path("reasonCode").asText()
                    : "PP2_OTHER_PHENOTYPE_DECLARED_RETRIEVAL_SCOPE_INCOMPLETE");
        } else if (workflow != null) {
            String reason = workflowUnavailable ? observed.reader.path("reasonCode").asText("PP2_AUTOMATIC_SOURCE_UNAVAILABLE")
                    : "PP2_AUTOMATIC_DISCOVERY_SCOPE_UNAVAILABLE";
            required.addObject().put("scope", "TARGET_LITERATURE_RELATIONSHIP_REVIEW").put("status", "UNAVAILABLE")
                    .put("reasonCode", reason);
            if (!workflowUnavailable || pending.isEmpty()) pending.addObject().put("reasonCode", reason);
        } else {
            required.addObject().put("scope", "TARGET_LITERATURE_RELATIONSHIP_REVIEW").put("status", "SOFTWARE_UNSUPPORTED")
                    .put("originalsRead", observed.reader.path("literature").size())
                    .put("reasonCode", "PP2_OTHER_PHENOTYPE_CASE_RELATIONSHIP_NOT_IMPLEMENTED");
            pending.addObject().put("reasonCode", "PP2_OTHER_PHENOTYPE_CASE_RELATIONSHIP_NOT_IMPLEMENTED");
        }
        // A genuine exclusion is usable even while unrelated reads are pending. Material conflicts are not voted away.
        out.put("outcome", !conflicts.isEmpty() ? Outcome.CONFLICT.name() : !accepted.isEmpty()
                ? Outcome.SUPPORTS_OTHER_PHENOTYPE.name() : nativeComplete && literatureComplete && pending.isEmpty()
                ? Outcome.NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE.name() : Outcome.UNKNOWN.name());
        out.put("reasonCode", !conflicts.isEmpty() ? "PP2_OTHER_PHENOTYPE_RELATIONSHIP_CONFLICT" : !accepted.isEmpty()
                ? "PP2_NATIVE_EXPERT_SUPPORTS_OTHER_PHENOTYPE" : nativeComplete && literatureComplete && pending.isEmpty()
                ? "PP2_NO_OTHER_PHENOTYPE_SUPPORT_IN_COMPLETED_DECLARED_SCOPE" : "PP2_OTHER_PHENOTYPE_REVIEW_INCOMPLETE");
        return new Captured(this, out);
    }
    private boolean materialAliasesMatch(@Nullable CodingTranscriptMaterialSupply.Material material, ObjectNode context,
            ObjectNode policy, JsonNode aliases) {
        if (material == null || !material.ready()) return false;
        var model = material.model(); if (model == null) return false;
        var audit = material.snapshot(); var association = audit.path("alleleAssociation");
        if (!"READY".equals(audit.path("status").asText()) || !association.path("allele").equals(context.at("/variant/evidenceQueryAllele"))
                || !model.path("hgncId").equals(context.at("/gene/hgncId")) || !model.path("geneSymbol").equals(context.at("/gene/symbol"))
                || !adapter.canonicalSha256(context).equals(policy.path("preparedContextSha256").asText())
                || !adapter.canonicalSha256(model).equals(policy.path("executionModelSha256").asText())
                || !adapter.canonicalSha256(association).equals(policy.path("alleleAssociationSha256").asText())
                || !adapter.canonicalSha256(model).equals(association.path("nativeModelSha256").asText())) return false;
        String coding = association.path("hgvsc").asText(), protein = association.path("hgvsp").asText();
        if (coding.isBlank() || protein.isBlank() || !aliases.isArray()) return false;
        String codingTail = coding.substring(coding.indexOf(':') + 1), proteinTail = protein.substring(protein.indexOf(':') + 1);
        boolean codingFound = false, proteinFound = false;
        for (var alias : aliases) {
            String value = alias.asText();
            if (value.equals(coding) || value.equals(codingTail)) codingFound = true;
            if (value.equals(protein) || value.equals(proteinTail) || value.equals("p." + association.path("proteinVariantKey").asText())) proteinFound = true;
        }
        return codingFound && proteinFound;
    }

    @Nullable private static JsonNode conflictingNativeExpertAssertion(JsonNode facts) {
        String disease = facts.at("/disease/id").asText();
        for (var assertion : facts.path("otherAssertions")) {
            if (!"current".equals(assertion.path("recordStatus").asText())
                    || !java.util.Set.of("reviewed by expert panel", "practice guideline").contains(assertion.path("reviewStatus").asText())
                    || !java.util.Set.of("benign", "likely benign").contains(assertion.path("classification").asText().toLowerCase(java.util.Locale.ROOT))) continue;
            var ids = new java.util.HashSet<String>(); mondoIds(assertion.path("condition"), ids);
            if (ids.size() == 1 && ids.contains(disease)) return assertion;
        }
        return null;
    }
    private void nativeSummaryDifferences(ObjectNode item, JsonNode facts) {
        String leftRecord = facts.at("/originalAssertion/scv").asText(), rightRecord = facts.at("/originalExpertSummary/uuid").asText();
        for (var entry : List.of(java.util.Map.entry("variationId", "/variationId"), java.util.Map.entry("curationId", "/erepoUuid"),
                java.util.Map.entry("curationVersion", "/erepoVersion"), java.util.Map.entry("diseaseId", "/disease/id"),
                java.util.Map.entry("classification", "/originalAssertion/classification"))) {
            String rightField = switch (entry.getKey()) { case "variationId" -> "cvId"; case "curationId" -> "uuid";
                case "curationVersion" -> "docVersion"; case "diseaseId" -> "mondoId"; default -> "classification"; };
            var left = facts.at(entry.getValue()); var right = facts.path("originalExpertSummary").path(rightField);
            boolean equal = "variationId".equals(entry.getKey()) ? left.asText().equals(right.asText()) : "classification".equals(entry.getKey())
                    ? left.asText().strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT)
                            .equals(right.asText().strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT)) : left.equals(right);
            if (!equal) difference(item, entry.getKey(), left, right, leftRecord, rightRecord, facts.path("sourceIds"));
        }
        if (item.path("reasonCode").asText().endsWith("GENE_CONFLICT")) {
            var genes = new java.util.ArrayList<String>(); for (var gene : facts.path("geneSymbols")) if (gene.isTextual()) genes.add(gene.asText());
            difference(item, "gene", mapper.getNodeFactory().textNode(String.join(", ", genes)), facts.at("/originalExpertSummary/gene"),
                    leftRecord, rightRecord, facts.path("sourceIds"));
        }
    }
    private void difference(ObjectNode item, String field, JsonNode left, JsonNode right, String leftRecord, String rightRecord, JsonNode sourceIds) {
        var differences = item.path("differences").isArray() ? (ArrayNode)item.path("differences") : item.putArray("differences");
        if (differences.size() >= 8) return;
        var row = differences.addObject().put("field", field); side(row.putObject("left"), left, leftRecord, sourceIds);
        side(row.putObject("right"), right, rightRecord, sourceIds);
    }
    private void side(ObjectNode row, JsonNode value, String record, JsonNode sourceIds) {
        if (value.isValueNode() && (!value.isTextual() || value.asText().length() <= 600)) row.set("value", value.deepCopy());
        else row.putNull("value");
        if (!record.isBlank() && record.length() <= 200) row.put("sourceRecordId", record);
        var sources = row.putArray("sourceIds");
        for (var source : sourceIds) if (sources.size() < 16 && source.isTextual() && source.asText().length() <= 200) sources.add(source.asText());
    }
    private static void mondoIds(JsonNode node, java.util.Set<String> ids) {
        if (node.isArray()) { for (var value : node) mondoIds(value, ids); return; }
        if (!node.isObject()) return;
        if ("XRef".equals(node.path("tag").asText()) && "MONDO".equals(node.at("/attributes/DB").asText()))
            ids.add(Pp2OtherPhenotypeDiseaseSemantics.normalizedMondo(node.at("/attributes/ID").asText()));
        for (var child : node.path("children")) mondoIds(child, ids);
    }

    /** Normal after-claim source path. Every invocation issues a bounded private audit, including zero-work/failure. */
    public synchronized Assessed assess(Observed observed, ObjectNode context, String candidateId, String hypothesisId,
            ObjectNode verifiedHcmPolicyBinding, CodingTranscriptMaterialSupply.Material material) {
        require(observed.owner == this, "PP2_OTHER_PHENOTYPE_OBSERVATION_OWNER_MISMATCH");
        var base = captureBound(observed, context, candidateId, hypothesisId, verifiedHcmPolicyBinding, material).snapshot();
        var state = derived.computeIfAbsent(observed, key -> new Pp2LiteratureAssessment(mapper,
                observed.observation.path("observationSha256").asText(), observed.method));
        var prior = state.prior(base); if (prior != null) return new Assessed(this, prior);
        String target = "target-" + CodingTranscriptMaterialSupply.sha((candidateId + "\u0000" + hypothesisId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 40);
        JsonNode scope = mapper.createObjectNode();
        for (var row : observed.reader.path("discoveryScopes")) if (target.equals(row.path("targetId").asText())) scope = row;
        boolean papers = !scope.at("/retrieval/candidates").isEmpty() || !scope.path("knownCitationReferences").isEmpty();
        boolean aliasesBound = false;
        for (var row : base.path("requiredScope")) if ("TARGET_LITERATURE_RELATIONSHIP_REVIEW".equals(row.path("scope").asText()))
            aliasesBound = row.path("aliasIdentityVerified").asBoolean();
        var automatic = workflow;
        Pp2OtherPhenotypeWorkflow.Reading reading;
        if (automatic != null && papers && aliasesBound && "UNKNOWN".equals(base.path("outcome").asText())
                && "READ".equals(observed.reader.path("status").asText()))
            reading = automatic.readLiterature(mapper, observed.method, scope.at("/retrieval/candidates"), scope.path("knownCitationReferences"),
                    qualificationTarget(context, verifiedHcmPolicyBinding, material));
        else reading = new Pp2OtherPhenotypeWorkflow.Reading(Pp2LiteratureAssessment.emptyLedger(mapper,
                papers && !aliasesBound ? "PP2_DISCOVERY_TARGET_ALIAS_IDENTITY_NOT_VERIFIED"
                : "SUPPORTS_OTHER_PHENOTYPE".equals(base.path("outcome").asText()) ? "PP2_NATIVE_EXPERT_EXCLUSION_ALREADY_ESTABLISHED"
                : !"READ".equals(observed.reader.path("status").asText()) ? observed.reader.path("reasonCode").asText("PP2_NATIVE_READER_UNAVAILABLE")
                : papers ? "PP2_RELATIONSHIP_READING_PRECONDITIONS_NOT_MET" : "PP2_NO_TARGET_PAPER_REQUIRES_READING"),
                automatic == null ? Path.of(".").toAbsolutePath() : automatic.work());
        return new Assessed(this, state.prepare(base, reading, workflow != null));
    }
    @Nullable private ObjectNode qualificationTarget(ObjectNode context, ObjectNode policy, CodingTranscriptMaterialSupply.Material material) {
        if (!material.ready()) return null;
        var model = material.model(); if (model == null) return null;
        var association = material.snapshot().path("alleleAssociation"); var allele = context.at("/variant/evidenceQueryAllele");
        String modelPin = adapter.canonicalSha256(model), associationPin = adapter.canonicalSha256(association), contextPin = adapter.canonicalSha256(context);
        if (!association.path("allele").equals(allele) || !modelPin.equals(association.path("nativeModelSha256").asText())
                || !modelPin.equals(policy.path("executionModelSha256").asText()) || !associationPin.equals(policy.path("alleleAssociationSha256").asText())
                || !contextPin.equals(policy.path("preparedContextSha256").asText()) || !model.path("hgncId").equals(context.at("/gene/hgncId"))
                || !model.path("geneSymbol").equals(context.at("/gene/symbol"))) return null;
        String hgvsc = association.path("hgvsc").asText();
        var match = java.util.regex.Pattern.compile("((?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*):(c\\.[1-9][0-9]*[ACGT]>[ACGT])").matcher(hgvsc);
        if (!match.matches()) return null;
        var result = mapper.createObjectNode().put("nativeModelSha256", modelPin).put("alleleAssociationSha256", associationPin)
                .put("contextSha256", contextPin).put("hcmPolicyBindingSha256", adapter.canonicalSha256(policy))
                .put("geneSymbol", model.path("geneSymbol").asText()).put("transcript", match.group(1)).put("coding", match.group(2));
        result.set("allele", allele.deepCopy()); return result;
    }
    /** Actual issued after-claim material only, one deduplicated archive per observed batch. */
    @Nullable public synchronized ObjectNode derivedArchive(Observed observed) {
        require(observed.owner == this, "PP2_OTHER_PHENOTYPE_OBSERVATION_OWNER_MISMATCH");
        var state = derived.get(observed); return state == null ? null : state.archive();
    }
    public static final class Assessed {
        private final Pp2OtherPhenotypeSource owner;
        private final ObjectNode result;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private Assessed(Pp2OtherPhenotypeSource owner, ObjectNode result) { this.owner = owner; this.result = result.deepCopy(); }
        public Review review() { return new Review(owner, result); }
        public ObjectNode snapshot() { return result.deepCopy(); }
    }
    public static final class Captured {
        private final Pp2OtherPhenotypeSource owner;
        private final ObjectNode result;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private Captured(Pp2OtherPhenotypeSource owner, ObjectNode result) { this.owner = owner; this.result = result.deepCopy(); }
        public Review review() { return new Review(owner, result); }
        public ObjectNode snapshot() { return result.deepCopy(); }
    }
    public static final class Review {
        private final Pp2OtherPhenotypeSource owner;
        private final ObjectNode result;
        @com.fasterxml.jackson.annotation.JsonCreator(mode = com.fasterxml.jackson.annotation.JsonCreator.Mode.DISABLED)
        private Review(Pp2OtherPhenotypeSource owner, ObjectNode result) { this.owner = owner; this.result = result.deepCopy(); }
        public Outcome outcome() { return Outcome.valueOf(result.path("outcome").asText()); }
        public ObjectNode snapshot() { return result.deepCopy(); }
        public boolean matches(Observed expected, ObjectNode context, String candidateId, ObjectNode verifiedHcmPolicyBinding) {
            return expected.owner == owner
                    && expected.observation.path("observationSha256").equals(result.at("/binding/sourceSnapshotSha256"))
                    && matches(context, candidateId, verifiedHcmPolicyBinding);
        }
        public boolean matches(Observed expected, ObjectNode context, String candidateId, String hypothesisId, ObjectNode verifiedHcmPolicyBinding) {
            return hypothesisId.equals(result.at("/binding/hypothesisId").asText())
                    && matches(expected, context, candidateId, verifiedHcmPolicyBinding);
        }
        public boolean matches(ObjectNode context, String candidateId, ObjectNode verifiedHcmPolicyBinding) {
            return candidateId.equals(result.at("/binding/candidateId").asText())
                    && owner.adapter.canonicalSha256(context).equals(result.at("/binding/contextSha256").asText())
                    && owner.adapter.canonicalSha256(verifiedHcmPolicyBinding).equals(result.at("/binding/hcmPolicyBindingSha256").asText());
        }
    }
    private static ObjectNode object(@Nullable JsonNode value) {
        if (!(value instanceof ObjectNode object)) throw new IllegalArgumentException("PP2_SOURCE_JSON_OBJECT_REQUIRED");
        return object;
    }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
    private static String safe(Exception problem, String fallback) {
        var message = problem.getMessage(); return message != null && message.matches("[A-Z][A-Z0-9_]{1,120}") ? message : fallback;
    }
}
