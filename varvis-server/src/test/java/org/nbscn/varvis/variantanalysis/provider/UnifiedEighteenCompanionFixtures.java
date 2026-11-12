package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks;
import org.springframework.core.io.ClassPathResource;

/** TEST_ONLY companion inputs for normal CURRENT wiring, never a profile or scored result.
 * Empty local inventories establish no biological absence, source approval or negative evidence.
 * Each instance is request-owned; PM4 and BP3 alone share the A5 sequence/fact supply.
 */
public final class UnifiedEighteenCompanionFixtures {
    public final Path root, uniProtDirectory, uniProtManifest, publishedKnowledgeFile, specializationCatalogFile;
    public final String uniProtManifestSha256, publishedKnowledgeSha256, specializationCatalogSha256;
    public final CodingTranscriptMaterialSupply proteinLengthMaterials, missenseMaterials;
    public final UniProtProteinRegionSupply uniProt;
    public final PublishedCriterionKnowledge publishedKnowledge;
    public final PredictionSpecializationGuard specializationGuard;
    public final GeneralPm4Runtime pm4;
    public final GeneralBp3Runtime bp3;
    public final GeneralPm1Runtime pm1;
    public final MissenseMaterialIndex missenseMaterialIndex;
    public final SplicingExperimentChecks.CatalogSnapshot experimentCatalog;
    private final ObjectMapper mapper;

    public UnifiedEighteenCompanionFixtures(ObjectMapper mapper, Path root,
                                            Pvs1UnifiedEighteenFixture pvs1Fixture) throws Exception {
        this(mapper, root, pvs1Fixture.modelDirectory, pvs1Fixture.modelManifestSha256, pvs1Fixture::readReference);
    }

    /** Reuse the complete companion factories with another caller-owned, pinned coding fixture. */
    public UnifiedEighteenCompanionFixtures(ObjectMapper mapper, Path root, Path modelDirectory,
                                            String manifestSha256, CodingTranscriptMaterialSupply.ReferenceReader reference) throws Exception {
        this.mapper = mapper; this.root = root.toAbsolutePath().normalize(); Files.createDirectories(this.root);
        // Preserve the caller's pinned model files and actual reference reader.
        // The ordinary missense supply intentionally keeps its established constructor/scope.
        proteinLengthMaterials = ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper, modelDirectory, manifestSha256, reference, null);
        missenseMaterials = new CodingTranscriptMaterialSupply(mapper, modelDirectory, manifestSha256, reference);
        uniProtDirectory = this.root.resolve("empty-uniprot"); Files.createDirectories(uniProtDirectory);
        var proteinIndex = mapper.createObjectNode().put("schemaVersion", "uniprot-protein-region-index-v1")
                .put("methodVersion", UniProtProteinRegionSupply.VERSION).put("usageScope", "UNREVIEWED_ANNOTATION_FACTS")
                .put("dataClass", "SYNTHETIC_TEST_ONLY");
        proteinIndex.putArray("entries");
        uniProtManifest = pinned(uniProtDirectory.resolve("manifest.json"), proteinIndex);
        uniProtManifestSha256 = rawSha(uniProtManifest);
        uniProt = new UniProtProteinRegionSupply(mapper, uniProtDirectory, uniProtManifestSha256);

        var knowledge = mapper.createObjectNode().put("schemaVersion", PublishedCriterionKnowledge.SCHEMA)
                .put("methodVersion", PublishedCriterionKnowledge.VERSION)
                .put("adoptionMode", "PROJECT_RESEARCH_PUBLISHED_SPECIFICATIONS")
                .put("clinicalValidationCompleted", false).put("policyId", "TEST_ONLY_EMPTY_PUBLISHED_INVENTORY")
                .put("policyVersion", "1").put("dataClass", "SYNTHETIC_TEST_ONLY");
        knowledge.putArray("specifications"); knowledge.putArray("records");
        publishedKnowledgeFile = pinned(this.root.resolve("empty-published-knowledge.json"), knowledge);
        publishedKnowledgeSha256 = rawSha(publishedKnowledgeFile);
        publishedKnowledge = PublishedCriterionKnowledge.load(mapper, publishedKnowledgeFile, publishedKnowledgeSha256);

        var specifications = mapper.createObjectNode().put("schemaVersion", Ba1SpecificationCatalog.SCHEMA)
                .put("dataClass", "SYNTHETIC_TEST_ONLY");
        specifications.putObject("metadata").put("status", "COMPLETE").put("entryCount", 0)
                .put("snapshotId", "TEST_ONLY_EMPTY_LOCAL_INVENTORY_NO_WORLDWIDE_ABSENCE_CLAIM");
        specifications.putArray("entries");
        specializationCatalogFile = pinned(this.root.resolve("empty-specialization-catalog.json"), specifications);
        specializationCatalogSha256 = rawSha(specializationCatalogFile);
        specializationGuard = PredictionSpecializationGuard.load(mapper, specializationCatalogFile, specializationCatalogSha256);

        pm4 = new GeneralPm4Runtime(mapper, proteinLengthMaterials, uniProt, null,
                new Pm4ProteinRegionAssessment(mapper), specializationGuard);
        bp3 = new GeneralBp3Runtime(mapper, pm4.sharedFactSupply(), new Bp3ProteinRegionAssessment(mapper),
                specializationGuard, publishedKnowledge);
        pm1 = new GeneralPm1Runtime(mapper, missenseMaterials, publishedKnowledge);
        var referenceIndex = mapper.createObjectNode().put("schemaVersion", "missense-material-index-v1");
        referenceIndex.putArray("entries");
        Path indexFile = pinned(this.root.resolve("empty-missense-material-index.json"), referenceIndex);
        missenseMaterialIndex = MissenseMaterialIndex.load(indexFile, rawSha(indexFile));
        experimentCatalog = new SplicingExperimentChecks(mapper,
                new ClassPathResource("acmg/public-splicing-experiments-v1.json"), this.root.resolve("experiments"),
                (analysis, run) -> { throw new AssertionError("Catalog capture must not execute saved business"); }).captureCatalog();
    }

    public Bp7SelectedBatch bp7() { return new Bp7SelectedBatch(mapper, experimentCatalog); }
    public GeneralPm4Runtime pm4() { return pm4; }
    public GeneralBp3Runtime bp3() { return bp3; }
    public GeneralPm1Runtime pm1() { return pm1; }
    /** The caller supplies an explicit declaration, never an inferred GT/synthetic flag. */
    public GeneralPp2Runtime pp2(boolean germlineDeclared) {
        return new GeneralPp2Runtime(mapper, missenseMaterials, publishedKnowledge, germlineDeclared);
    }
    /** Absent raw splice data remain absent; no fake score or experimental normality is supplied. */
    public GeneralBp1Runtime bp1(Function<JsonNode, JsonNode> effects, String effectSourceSha256) {
        return new GeneralBp1Runtime(mapper, missenseMaterials, publishedKnowledge,
                (MissenseNativeSpliceBridge) null, experimentCatalog, effects, effectSourceSha256);
    }

    /** Existing CDH1 synthetic scope stays exact and is never lent to a PVS1 toy gene. */
    public DeNovoSelectedBatch deNovo() throws Exception {
        Path directory = root.resolve("denovo"), manifest = directory.resolve("manifest.json");
        if (!Files.exists(manifest)) new DeNovoBatchFixture(directory);
        return DeNovoSelectedBatch.load(mapper, manifest, rawSha(manifest), DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
    }

    public Pm3SelectedBatch pm3(Path workflow, Pm3LiteratureRaritySupply.Factory population) {
        return new Pm3SelectedBatch(mapper, Pm3LiteratureJobs.businessSource(workflow, population));
    }
    public Pp1SelectedBatch pp1(Path workflow) {
        return new Pp1SelectedBatch(mapper, Pp1LiteratureJobs.businessSource(workflow));
    }

    /** Real local exomes transport and pending PM2 profile; caller-owned files/settings stay explicit. */
    public Supplier<Pm2LocalExomesRuntime> pm2Factory(VariantAnalysisProperties localPopulationSettings) {
        return () -> new Pm2LocalExomesRuntime(mapper, localPopulationSettings);
    }
    public Supplier<SpliceAiLocalObservationSession> spliceFactory(VariantAnalysisProperties settings) {
        return () -> new SpliceAiProvider(settings).openSharedSession(mapper);
    }
    public Function<SpliceAiLocalObservationSession, SelectedPredictionRuntime> predictionFactory(
            VariantAnalysisProperties settings, Function<JsonNode, JsonNode> effects) {
        return shared -> new SelectedPredictionRuntime(mapper,
                PredictionDevelopmentRuntime.configured(mapper, new AlphaMissenseProvider(settings)).withEffectRestrictions(effects),
                Hnf1aRevelRuntime.unavailable(mapper), null, null, shared, effects, missenseMaterials);
    }
    /** Real comparator providers with an actual empty approved-material inventory. The caller uses
     * isolated missing ClinVar/VEP paths when testing source absence; no VEP execution is needed.
     * Registered resource identities describe the expected contract, never available/approved data.
     */
    public LocalEvidenceBatchRuntime.ReferenceSetup reference(VariantAnalysisProperties settings) {
        var profile = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("resources");
        var sources = mapper.createObjectNode().put("dataClass", "SYNTHETIC").put("purpose", "TEST_ONLY")
                .put("resourceAvailability", "NOT_CHECKED");
        sources.set("CLINVAR_VCF", profile.get(0).deepCopy()); sources.set("VEP", profile.get(1).deepCopy());
        sources.set("SIDECAR", profile.get(2).deepCopy());
        var sidecar = profile.get(2);
        return new LocalEvidenceBatchRuntime.ReferenceSetup(() -> new ClinVarConditionAwareComparatorInput(
                new ClinVarTabixProvider(settings), new VepTranscriptAnnotationProvider(settings),
                new ClinVarComparatorSidecar(mapper, root.resolve("missing-clinvar-sidecar.tsv.gz"),
                        sidecar.path("resourceId").asText(), sidecar.path("resourceVersion").asText(),
                        sidecar.path("identityFingerprintSha256").asText()), new VepProteinRelationClassifier()),
                sources, null,
                (criterion, captured) -> missenseMaterialIndex.bind(criterion, captured.getEvidenceContext(),
                        captured.getSelectedTargetAnnotation(),
                        () -> new MissenseComparisonRuntime(mapper, null, null,
                                SpliceAiCriterionEvaluator.loadProfile(mapper).at("/resources/0/identityFingerprintSha256").asText())
                                .withCapturedInput(captured), missenseMaterialIndex.identity()),
                missenseMaterialIndex::useIssue);
    }

    /** Copies only existing small program inputs into an isolated, initially empty task archive.
     * The production PP1 source adapter and Python evaluator still execute and verify their pins.
     * No context adoption, pedigree, paper task, score, network or model result is invented.
     */
    public Path prepareEmptyLiteratureWorkflow(Path existingScripts, Path existingPython) throws IOException {
        return prepareEmptyLiteratureWorkflow(mapper, root, existingScripts, existingPython);
    }

    /** Reuse the same empty source archive with the actual central configuration factory. */
    public static Path prepareEmptyLiteratureWorkflow(ObjectMapper mapper, Path root,
            Path existingScripts, Path existingPython) throws IOException {
        Path scripts = existingScripts.toRealPath(), python = existingPython.toRealPath();
        if (!Files.isDirectory(scripts) || !Files.isRegularFile(python))
            throw new IllegalArgumentException("TEST_ONLY_EXISTING_PP1_TOOLS_REQUIRED");
        Path project = root.resolve("empty-literature-project"), local = project.resolve("local");
        Path target = project.resolve("scripts/denovo");
        Files.createDirectories(target); Files.createDirectories(local.resolve("work")); Files.createDirectories(local.resolve("library"));
        try (var paths = Files.list(scripts)) {
            for (Path source : paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".py") && !p.getFileName().toString().startsWith("test_")).toList())
                retained(target.resolve(source.getFileName()), Files.readAllBytes(source));
        }
        retained(target.resolve("bundle_replay_profiles.json"), Files.readAllBytes(scripts.resolve("bundle_replay_profiles.json")));
        Path originalProject = scripts.getParent().getParent();
        Path dictionary = project.resolve("docs/design/pp1/field-dictionary.json");
        retained(dictionary, Files.readAllBytes(originalProject.resolve("docs/design/pp1/field-dictionary.json")));
        for (String name : List.of("normalize_public_panel.py", "build_transcript_model.py"))
            retained(project.resolve("scripts/acmg-generalization").resolve(name),
                    Files.readAllBytes(originalProject.resolve("scripts/acmg-generalization").resolve(name)));
        // Same Windows transport form as the existing formal PP1 fixture, using an explicit executable.
        if (python.toString().contains("\"") || python.toString().contains("\r") || python.toString().contains("\n")
                || python.toString().contains("%")) throw new IllegalArgumentException("TEST_ONLY_PYTHON_PATH_INVALID");
        retained(local.resolve("python.cmd"), ("@echo off\r\n\"" + python + "\" %*\r\n").getBytes(StandardCharsets.UTF_8));
        retained(local.resolve("model.json"), mapper.writeValueAsBytes(mapper.createObjectNode()));
        var workflow = mapper.createObjectNode().put("schemaVersion", "denovo-public-literature-workflow-v1")
                .put("enabled", true).put("projectRoot", project.toString()).put("work", "local/work")
                .put("library", "local/library").put("modelConfig", "local/model.json").put("python", "local/python.cmd")
                .put("allowModel", false).put("allowPublicNetwork", false);
        Path workflowFile = local.resolve("workflow.json");
        retained(workflowFile, mapper.writeValueAsBytes(workflow));
        return workflowFile;
    }

    private Path pinned(Path file, ObjectNode value) throws IOException {
        retained(file, mapper.writeValueAsBytes(value)); return file;
    }
    private static void retained(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        if (Files.exists(file)) {
            if (!Arrays.equals(bytes, Files.readAllBytes(file)))
                throw new IllegalArgumentException("TEST_ONLY_COMPANION_SOURCE_CHANGED");
        } else Files.write(file, bytes, StandardOpenOption.CREATE_NEW);
    }
    private static String rawSha(Path file) throws IOException { return CodingTranscriptMaterialSupply.sha(Files.readAllBytes(file)); }
}
