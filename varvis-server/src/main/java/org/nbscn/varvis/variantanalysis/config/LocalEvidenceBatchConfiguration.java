package org.nbscn.varvis.variantanalysis.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.core.env.Environment;
import org.springframework.lang.Nullable;

/** Separate opt-in wiring. Construction captures configuration/metadata, never scientific observations. */
final class LocalEvidenceBatchConfiguration {
    private static final String PREFIX = "variant-analysis.acmg-business.";
    private static final List<String> KEYS = List.of("binding-directory", "resource-root", "working-root",
            "reference-receipt-file", "reference-receipt-sha256", "reference-receipt-locator",
            "prediction-local-binding-file", "prediction-local-binding-sha256", "revel-manifest-file", "revel-enabled",
            "reviewed-prediction-policy-enabled", "reviewed-prediction-policy-file", "reviewed-prediction-policy-file-sha256",
            "reviewed-prediction-policy-sha256", "revel-publisher-index-directory", "revel-publisher-tabix-executable", "revel-publisher-transport",
            "revel-publisher-review-file", "revel-publisher-review-file-sha256", "revel-publisher-review-sha256",
            "alphamissense-enabled", "spliceai-enabled", "spliceai-raw-command-json", "spliceai-reference-fasta",
            "spliceai-timeout-seconds", "clinvar-sidecar-file", "clinvar-sidecar-manifest",
            "local-batch-pm2-adoption-file", "local-batch-pm2-adoption-sha256", "local-batch-pm2-all-sites-file",
            "local-batch-pm2-all-sites-sha256", "local-batch-pm2-general-absence-enabled", "local-batch-reference-source-file",
            "local-batch-reference-source-sha256", "local-batch-reference-splice-mode", "local-batch-ps1-bundle-file", "local-batch-ps1-bundle-sha256",
            "local-batch-pm5-bundle-file", "local-batch-pm5-bundle-sha256", "effect-review-root",
            "ps1-splice-enabled", "ps1-splice-knowledge-file", "ps1-splice-knowledge-sha256",
            "transcript-material-directory", "transcript-material-manifest-sha256", "gencode-transcript-index-directory",
            "gencode-transcript-index-manifest-sha256", "local-batch-reference-qualified-application-enabled",
            "local-batch-general-protein-model", "general-alphamissense-binding-file", "general-alphamissense-binding-sha256",
            "alphamissense-source-transcript-index-directory", "alphamissense-source-transcript-index-manifest-sha256",
            "local-batch-general-revel-enabled", "prediction-specialization-catalog-file", "prediction-specialization-catalog-sha256", "prediction-specialization-source-directory",
            "prediction-release-index-file", "prediction-release-index-sha256", "prediction-mondo-file", "prediction-mondo-sha256",
            "local-batch-native-splice-enabled", "native-splice-python-file", "native-splice-adapter-file",
            "native-splice-binding-file", "native-splice-binding-sha256", "native-splice-reference-fasta", "native-splice-timeout-seconds",
            "local-batch-pp1-current-input-file");
    private static final List<String> PM4_KEYS = List.of("local-batch-pm4-enabled", "pm4-non-repeat-policy", "pm4-protein-region-directory",
            "pm4-protein-region-manifest-sha256", "pm4-combined-region-file", "pm4-combined-region-sha256",
            "pm4-reviewed-repeat-assertions-file", "pm4-reviewed-repeat-assertions-sha256");
    private static final List<String> DEMAND_KEYS = List.of("pm4-region-demand-enabled", "pm4-region-demand-network-enabled",
            "pm4-region-demand-cache-directory", "pm4-region-demand-uniprot-release", "pm4-region-demand-interpro-release",
            "pm4-region-demand-interpro-minor", "pm4-region-demand-max-proteins", "pm4-region-demand-max-requests",
            "pm4-region-demand-total-seconds", "pm4-region-demand-request-seconds", "pm4-region-demand-max-bytes");
    private static final List<String> EXTENDED_KEYS = List.of("local-batch-pvs1-enabled", "local-batch-bp3-enabled",
            "pvs1-selected-knowledge-enabled", "pvs1-local-material-supply-enabled", "pvs1-bundle-file", "pvs1-bundle-file-sha256", "pvs1-bundle-sha256",
            "pvs1-knowledge-source-file", "pvs1-knowledge-source-sha256", "pvs1-knowledge-adoption-file", "pvs1-knowledge-adoption-sha256",
            "pvs1-applicability-source-file", "pvs1-applicability-source-sha256", "bp3-reviewed-region-assertions-file", "bp3-reviewed-region-assertions-sha256",
            "bp3-published-knowledge-file", "bp3-published-knowledge-sha256");
    private static final List<String> PUBLISHED_KEYS = List.of("local-batch-pm1-enabled", "local-batch-pp2-enabled", "local-batch-bp1-enabled",
            "local-batch-pp2-germline-declared", "pm1-published-knowledge-file", "pm1-published-knowledge-sha256",
            "pp2-published-knowledge-file", "pp2-published-knowledge-sha256", "bp1-published-knowledge-file", "bp1-published-knowledge-sha256",
            "bp1-wide-splice-enabled", "bp1-wide-splice-adapter-file", "bp1-wide-splice-binding-file", "bp1-wide-splice-binding-sha256");
    private static final String PM1_REVIEWED_FILE = "pm1-reviewed-regions-file";
    private static final String PM1_REVIEWED_SHA = "pm1-reviewed-regions-sha256";
    private final ObjectMapper mapper;
    private final VariantAnalysisProperties properties;
    private final Environment env;
    @Nullable private final AlphaMissensePhaseBudget amBudget;
    @Nullable private PredictionSpecializationGuard predictionScopeGuard;
    @Nullable private PublishedPredictionCatalog predictionReleaseCatalog;

    LocalEvidenceBatchConfiguration(ObjectMapper mapper, VariantAnalysisProperties properties, Environment env,
            @Nullable AlphaMissensePhaseBudget amBudget) {
        this.mapper = mapper; this.properties = properties; this.env = env; this.amBudget = amBudget;
    }

    LocalEvidenceBatchRuntime create(Function<JsonNode, JsonNode> effects) { return create(effects, null); }

    LocalEvidenceBatchRuntime create(Function<JsonNode, JsonNode> effects, @Nullable String effectSourceSha256) {
        boolean extended = extendedEnabled(), published = publishedEnabled();
        // Bind BP1 to the exact catalog snapshot already exposed by BP7, including unavailable snapshots.
        var sharedCatalog = published ? experimentCatalog() : null;
        // All three new criteria consume the same request-owned sequence/model cache.
        var sharedMissense = new CodingTranscriptMaterialSupply[1]; var missenseFailure = new RuntimeException[1];
        java.util.function.Supplier<CodingTranscriptMaterialSupply> missense = () -> {
            if (missenseFailure[0] != null) throw missenseFailure[0];
            if (sharedMissense[0] == null) try {
                sharedMissense[0] = java.util.Objects.requireNonNull(transcriptMaterials(), "PUBLISHED_TRANSCRIPT_CONFIGURATION_REQUIRED");
            } catch (RuntimeException bad) { missenseFailure[0] = bad; throw bad; }
            return sharedMissense[0];
        };
        // One request-owned source/cache instance for PM4 and BP3, including failed initialization.
        var sharedProtein = new GeneralPm4Runtime[1]; var sharedFailure = new RuntimeException[1];
        java.util.function.Supplier<GeneralPm4Runtime> protein = () -> {
            if (sharedFailure[0] != null) throw sharedFailure[0];
            if (sharedProtein[0] == null) try { sharedProtein[0] = pm4(); }
            catch (RuntimeException bad) { sharedFailure[0] = bad; throw bad; }
            return sharedProtein[0];
        };
        // Configuration includes ordinary numeric settings, not scientific decimal facts.
        // Bind their exact serialized representation before using the scientific canonical hasher.
        ObjectNode identity = mapper.createObjectNode().put("schemaVersion", "local-batch1-settings-pin-v1")
                .put("serialization", "ORDERED_CONFIGURATION_JSON_UTF8_V1")
                .put("configurationJsonSha256", org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(identity().toString()));
        var snapshot = sourceSnapshot();
        String referenceMode = referenceSpliceMode();
        var nativeReference = new MissenseNativeSpliceBridge[1];
        java.util.function.Supplier<MissenseNativeSpliceBridge> nativeRaw = () -> {
            if (nativeReference[0] == null) nativeReference[0] = referenceNativeSplice();
            return nativeReference[0];
        };
        var rawCache = new java.util.LinkedHashMap<String, SpliceAiRawBatchRunner.BatchResult>();
        Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> raw = queries -> {
            String key = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(mapper.valueToTree(queries));
            return rawCache.computeIfAbsent(key, ignored -> runRawSplice(queries));
        };
        var spliceComparison = new Ps1SpliceRuntime[1];
        java.util.function.Supplier<Ps1SpliceRuntime> spliceComparisonFactory = () -> {
            if (spliceComparison[0] == null) {
                try {
                    var supply = transcriptMaterials();
                    if (supply == null || !"NATIVE_SELECTED".equals(referenceMode)) throw new IllegalArgumentException("PS1_SPLICE_NATIVE_CONFIGURATION_REQUIRED");
                    spliceComparison[0] = new Ps1SpliceRuntime(mapper, supply,
                            Ps1SpliceKnowledge.load(mapper, Path.of(value("ps1-splice-knowledge-file")), value("ps1-splice-knowledge-sha256")),
                            new ClinVarTabixProvider(properties), nativeRaw.get(), experimentCatalog(),
                            new GencodeTranscriptIndex(mapper, Path.of(value("gencode-transcript-index-directory")), value("gencode-transcript-index-manifest-sha256")));
                } catch (Exception invalid) { throw new IllegalArgumentException("PS1_SPLICE_CONFIGURATION_UNAVAILABLE", invalid); }
            }
            return spliceComparison[0];
        };
        LocalEvidenceBatchRuntime.ReferenceSetup reference = snapshot == null ? null : new LocalEvidenceBatchRuntime.ReferenceSetup(
                this::comparator, snapshot, AcmgBusinessConfig.businessWorkingRoot(properties, env).resolve("local-batch-reference-drafts"),
                (code, capture) -> approvedReference(code, capture, raw, referenceMode, nativeRaw),
                env.getProperty(PREFIX + "local-batch-reference-qualified-application-enabled", Boolean.class, false) ? this::referenceUseIssue : null,
                env.getProperty(PREFIX + "ps1-splice-enabled", Boolean.class, false) ? spliceComparisonFactory : null);
        var pp2Sources = published ? pp2SourceSetup() : null;
        var runtime = new LocalEvidenceBatchRuntime(mapper, identity, this::population, () -> new SpliceAiProvider(properties).openSharedSession(mapper), splice -> {
            String file = value("prediction-local-binding-file");
            var binding = file.isBlank() ? null : PredictionLocalResourceBinding.load(mapper, Path.of(file), value("prediction-local-binding-sha256"));
            var reviewed = AcmgBusinessConfig.reviewedPrediction(mapper, properties, env);
            if ((reviewed.runtime() == null) != (reviewed.router() == null))
                throw new IllegalArgumentException("PREDICTION_REVIEWED_CONFIGURATION_UNAVAILABLE");
            var development = PredictionDevelopmentRuntime.configured(mapper, new AlphaMissenseProvider(properties, binding, amBudget))
                    .withEffectRestrictions(effects);
            return new SelectedPredictionRuntime(mapper, development, AcmgBusinessConfig.revelRuntime(mapper, env),
                    reviewed.runtime(), reviewed.router(), splice, effects, transcriptMaterials(), generalRevel(), nativeSplice(),
                    generalAlpha(), publishedPrediction());
        }, reference, published ? new Bp7SelectedBatch(mapper, sharedCatalog, literatureAssets()) : bp7(), pm4Enabled() ? protein : null,
                extended ? this::pvs1 : null, extended ? () -> bp3(protein.get().sharedFactSupply()) : null,
                published ? () -> new GeneralPm1Runtime(mapper, missense.get(), publishedKnowledge("pm1"), pm1ReviewedRegions(), pm1ClinVarIntake()) : null,
                published ? () -> new GeneralPp2Runtime(mapper, missense.get(), publishedKnowledge("pp2"), pp2GermlineDeclared()) : null,
                published ? () -> new GeneralBp1Runtime(mapper, missense.get(), publishedKnowledge("bp1"), nativeRaw.get(), bp1WideSplice(),
                        sharedCatalog, effects, effectSourceSha256, bp1StructuredLofSource()).withReviewArchive(literatureAssets()) : null,
                pp2GermlineDeclared(), deNovo(),pm3(),pp1())
                .withFullRnaPreparation(extended && !value("pvs1-bundle-file").isBlank() ? this::fullRnaMaterials : null, fullRnaDeclaredPins())
                .withPp2OtherPhenotypePreparation(pp2Sources == null ? null : pp2Sources.factory(),
                        pp2Sources == null ? mapper.createObjectNode() : pp2Sources.capability(),
                        published ? pp2DiscoveryScope() : null);
        if (published) runtime.withPublishedPm5Ptc(() ->
                new org.nbscn.varvis.variantanalysis.provider.PublishedPm5PtcRuntime(mapper, missense.get()));
        return runtime;
    }

    @Nullable
    private java.util.function.Supplier<ReviewedRuleBundle> bp1StructuredLofSource() {
        if (!env.getProperty(PREFIX + "pvs1-selected-knowledge-enabled", Boolean.class, false)) return null;
        return org.springframework.util.function.SingletonSupplier.of(() -> {
            try {
                return AcmgBusinessConfig.pvs1Bundle(mapper, env, properties);
            } catch (IllegalArgumentException unavailable) {
                return ReviewedRuleBundle.unavailable(mapper, value("pvs1-bundle-sha256"));
            }
        });
    }

    private java.util.function.Predicate<ObjectNode> pp2DiscoveryScope() {
        var loaded = new PublishedCriterionKnowledge[1];
        return scope -> {
            PublishedCriterionKnowledge knowledge;
            synchronized (loaded) {
                knowledge = loaded[0];
                if (knowledge == null) { knowledge = publishedKnowledge("pp2"); loaded[0] = knowledge; }
            }
            return knowledge.hasInterpretationForBackground(GeneralPp2Runtime.CRITERION, scope, GeneralPp2Runtime.HCM_METHOD_TYPE);
        };
    }

    private record Pp2SourceSetup(java.util.function.Supplier<Pp2OtherPhenotypeSource> factory, ObjectNode capability) { }
    private Pp2SourceSetup pp2SourceSetup() {
        Path configuration = Path.of(env.getProperty("varvis.literature-workflow-config",
                "../local/private/config/denovo-literature-workflow/config.json"));
        String measured = "UNAVAILABLE";
        try (var input = Files.newInputStream(configuration)) {
            byte[] raw = input.readNBytes(1_048_577);
            if (raw.length <= 1_048_576) measured = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException unavailable) {
            // The request-owned source records the exact configuration failure; ordinary RAS PP2 never opens it.
        }
        final String expected = measured;
        boolean publicNetwork = env.getProperty(PREFIX + "local-batch-denovo-public-network-enabled", Boolean.class, false);
        Path work = AcmgBusinessConfig.businessWorkingRoot(properties, env).resolve("pp2-other-phenotype");
        java.util.function.Supplier<Pp2OtherPhenotypeSource> factory = () -> Pp2OtherPhenotypeSource.forWorkflow(mapper,
                configuration, expected, work, () -> new Pvs1StartLossPublicSources(mapper, publicNetwork));
        return new Pp2SourceSetup(factory, factory.get().identity());
    }

    @Nullable Pp1SelectedBatch pp1() {
        if(!env.getProperty(PREFIX+"local-batch-pp1-literature-enabled",Boolean.class,false))return null;
        var literature=Pp1LiteratureJobs.businessSource(
                Path.of(env.getProperty("varvis.literature-workflow-config","../local/private/config/denovo-literature-workflow/config.json")));
        String file=value("local-batch-pp1-current-input-file");
        if(file.isBlank()) return Pp1SelectedBatch.withWorkbenchRegistry(mapper,literature);
        try {
            var registered=Pp4ServerRegisteredInputs.load(Path.of(file));
            return Pp1SelectedBatch.withCurrentFactory(mapper,literature,
                    new Pp1CurrentContributionSource.Factory(new Pp4CurrentAdoptionProducer(registered)));
        } catch(java.io.IOException | IllegalArgumentException unavailable) {
            var readiness=mapper.createObjectNode().put("status","UNAVAILABLE")
                    .put("configuredPathSha256",org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(file));
            String reason=unavailable.getMessage();
            readiness.put("reasonCode",reason!=null && reason.matches("PP4_[A-Z0-9_]+")?reason:"PP4_REGISTERED_INPUTS_UNAVAILABLE");
            try {
                Path path=Path.of(file); long bytes=Files.size(path);
                readiness.put("manifestBytes",bytes).put("manifestModified",Files.getLastModifiedTime(path).toString());
                if(bytes<=262144) readiness.put("manifestRawSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
            } catch(Exception missing) { readiness.put("manifestStatus","UNAVAILABLE"); }
            return Pp1SelectedBatch.unavailableCurrentInputs(mapper,literature,readiness);
        }
    }

    @Nullable Pm3SelectedBatch pm3() {
        if(!env.getProperty(PREFIX+"local-batch-pm3-literature-enabled",Boolean.class,false))return null;
        var population=new Pm3LiteratureRaritySupply.Factory(){
            public ObjectNode identity(){return populationIdentity();}
            public Pm2LocalExomesRuntime create(){return population();}
        };
        return new Pm3SelectedBatch(mapper,Pm3LiteratureJobs.businessSource(
                Path.of(env.getProperty("varvis.literature-workflow-config","../local/private/config/denovo-literature-workflow/config.json")),population));
    }

    Pm2LocalExomesRuntime population() {
        var settings = AcmgBusinessConfig.localBa1Settings(properties);
        String file = value("local-batch-pm2-adoption-file");
        var profile = file.isBlank() ? Pm2LocalExomesProfile.load(mapper)
                : Pm2LocalExomesProfile.loadReviewed(mapper, Path.of(file), value("local-batch-pm2-adoption-sha256"));
        String allSites = value("local-batch-pm2-all-sites-file");
        boolean general = env.getProperty(PREFIX + "local-batch-pm2-general-absence-enabled", Boolean.class, false);
        if (allSites.isBlank()) {
            if (general) throw new IllegalArgumentException("PM2_GENERAL_ABSENCE_RESOURCE_REQUIRED");
            return new Pm2LocalExomesRuntime(mapper, settings, profile);
        }
        return Pm2LocalExomesRuntime.configured(mapper, settings, profile,
                Path.of(allSites), value("local-batch-pm2-all-sites-sha256"), general);
    }

    /** PM3 reuse depends only on population resources, not on unrelated evidence modules. */
    ObjectNode populationIdentity() {
        var paths=new com.fasterxml.jackson.databind.module.SimpleModule("Pm3PopulationPaths")
                .addSerializer(Path.class,new com.fasterxml.jackson.databind.ser.std.ToStringSerializer());
        var out=mapper.createObjectNode().put("schemaVersion","pm3-population-configuration-v1");
        out.set("gnomad",mapper.copy().registerModule(paths).disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .valueToTree(AcmgBusinessConfig.localBa1Settings(properties).getGnomad()));
        var files=out.putArray("fileObservations");
        for(String key:List.of("local-batch-pm2-adoption-file","local-batch-pm2-adoption-sha256",
                "local-batch-pm2-all-sites-file","local-batch-pm2-all-sites-sha256","local-batch-pm2-general-absence-enabled")) {
            out.put(key,value(key));
            if(key.endsWith("-file") && !value(key).isBlank())observe(files,key,Path.of(value(key)),false);
        }
        for(var file:properties.getGnomad().getGrch38Files())observe(files,"gnomad",file,true);
        String allSites=value("local-batch-pm2-all-sites-file");
        if(!allSites.isBlank())try {
            Path manifest=Path.of(allSites);
            if(Files.size(manifest)>65536)throw new IllegalArgumentException("PM2_ALL_SITES_MANIFEST_LIMIT");
            var metadata=mapper.readTree(Files.readAllBytes(manifest));
            for(String field:List.of("dataFile","indexFile")) {
                String name=metadata.path(field).asText();
                if(!name.isBlank() && !name.contains("/") && !name.contains("\\") && !name.contains(":"))
                    observe(files,"pm2-all-sites-"+field,manifest.getParent().resolve(name),false);
            }
        }catch(Exception invalid){out.put("allSitesIdentityStatus","UNAVAILABLE");}
        return out;
    }

    @Nullable DeNovoSelectedBatch deNovo() {
        if (!env.getProperty(PREFIX + "local-batch-denovo-enabled", Boolean.class, false)) return null;
        try {
            var use = DeNovoApplicabilityPolicy.Use.valueOf(env.getProperty(PREFIX + "local-batch-denovo-use", "RESEARCH"));
            DeNovoLiteratureReader reader = null;
            if (env.getProperty(PREFIX + "local-batch-denovo-literature-enabled", Boolean.class, false)) {
                String modelIndex = value("local-batch-denovo-model-artifact-index");
                reader = modelIndex.isBlank() ? new DeNovoLiteratureReader(mapper, Path.of(value("local-batch-denovo-literature-python")),
                        Path.of(value("local-batch-denovo-literature-scripts")), Path.of(value("local-batch-denovo-literature-cache")))
                        : new DeNovoLiteratureReader(mapper, Path.of(value("local-batch-denovo-literature-python")),
                        Path.of(value("local-batch-denovo-literature-scripts")), Path.of(value("local-batch-denovo-literature-cache")),
                        Path.of(modelIndex), value("local-batch-denovo-model-artifact-sha256"));
                String candidates=value("local-batch-denovo-candidate-repository");
                if(!candidates.isBlank()) reader.withCandidates(Path.of(candidates));
                String core=value("local-batch-denovo-core-context-config");
                if (!core.isBlank()) {
                    Path scriptRoot = Path.of(value("local-batch-denovo-literature-scripts")).toAbsolutePath().resolve("../..").normalize();
                    String configuredRoot = env.getProperty("VARVIS_PROJECT_ROOT");
                    Path projectRoot = configuredRoot == null || configuredRoot.isBlank() ? scriptRoot : Path.of(configuredRoot);
                    reader.withCoreContext(projectRoot, Path.of(core), value("local-batch-denovo-core-context-sha256"));
                }
                reader.withReviewArchive(literatureAssets());
            }
            DeNovoLiteratureSupply supply=null;
            if (env.getProperty(PREFIX + "local-batch-denovo-source-supply-enabled", Boolean.class, false))
                supply=new DeNovoLiteratureSupply(mapper,Path.of(value("local-batch-denovo-literature-python")),
                        Path.of(value("local-batch-denovo-literature-scripts")),Path.of(value("local-batch-denovo-source-supply-work")),
                        Path.of(value("local-batch-denovo-public-cache")),env.getProperty(PREFIX+"local-batch-denovo-public-network-enabled",Boolean.class,false)).asynchronous();
            String materialRoot = value("local-batch-denovo-material-root");
            return DeNovoSelectedBatch.load(mapper, Path.of(value("local-batch-denovo-material-file")),
                    value("local-batch-denovo-material-sha256"), use, reader, supply,
                    materialRoot.isBlank() ? null : Path.of(materialRoot));
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("DENOVO_MATERIAL_READ_FAILED", invalid); }
    }

    @Nullable Bp7SelectedBatch bp7() {
        if (!env.getProperty(PREFIX + "local-batch-bp7-enabled", Boolean.class, false)) return null;
        return new Bp7SelectedBatch(mapper, experimentCatalog(), literatureAssets());
    }

    private LiteratureSourceAssets literatureAssets() {
        return new LiteratureSourceAssets(mapper, Path.of(env.getProperty(
                "varvis.literature-workflow-config", "../local/private/config/denovo-literature-workflow/config.json")));
    }

    private org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks.CatalogSnapshot experimentCatalog() {
        String location = env.getProperty(PREFIX + "splicing-experiment-catalog", "classpath:acmg/public-splicing-experiments-v1.json");
        if (location == null || location.isBlank()) throw new IllegalArgumentException("BP7_EXPERIMENT_CATALOG_LOCATION_REQUIRED");
        org.springframework.core.io.Resource resource;
        if (location.startsWith("classpath:")) resource = new org.springframework.core.io.ClassPathResource(java.util.Objects.requireNonNull(location.substring(10)));
        else if (location.startsWith("file:")) resource = new org.springframework.core.io.FileSystemResource(java.util.Objects.requireNonNull(Path.of(java.net.URI.create(location))));
        else {
            if (location.contains("://")) throw new IllegalArgumentException("BP7_EXPERIMENT_CATALOG_LOCAL_ONLY");
            resource = new org.springframework.core.io.FileSystemResource(java.util.Objects.requireNonNull(Path.of(location)));
        }
        var source = new org.nbscn.varvis.variantanalysis.service.SplicingExperimentChecks(mapper, resource,
                properties.getStorageRoot().resolve("splicing-experiment-checks"), (a, r) -> {
                    throw new IllegalStateException("BP7_CATALOG_CAPTURE_MUST_NOT_READ_BUSINESS");
                });
        return source.captureCatalog();
    }

    private boolean pm4Enabled() { return env.getProperty(PREFIX + "local-batch-pm4-enabled", Boolean.class, false); }
    private boolean extendedEnabled() {
        boolean pvs1 = env.getProperty(PREFIX + "local-batch-pvs1-enabled", Boolean.class, false);
        boolean bp3 = env.getProperty(PREFIX + "local-batch-bp3-enabled", Boolean.class, false);
        if ((pvs1 || bp3) && !(pvs1 && bp3 && pm4Enabled() && env.getProperty(PREFIX + "local-batch-bp7-enabled", Boolean.class, false)))
            throw new IllegalArgumentException("EXTENDED_LOCAL_ALL_COMPONENTS_REQUIRED");
        return pvs1 && bp3;
    }
    private boolean publishedEnabled() {
        boolean pm1 = env.getProperty(PREFIX + "local-batch-pm1-enabled", Boolean.class, false);
        boolean pp2 = env.getProperty(PREFIX + "local-batch-pp2-enabled", Boolean.class, false);
        boolean bp1 = env.getProperty(PREFIX + "local-batch-bp1-enabled", Boolean.class, false);
        if ((pm1 || pp2 || bp1) && !(pm1 && pp2 && bp1 && extendedEnabled()))
            throw new IllegalArgumentException("PUBLISHED_LOCAL_ALL_COMPONENTS_REQUIRED");
        return pm1 && pp2 && bp1;
    }
    private boolean pp2GermlineDeclared() { return env.getProperty(PREFIX + "local-batch-pp2-germline-declared", Boolean.class, false); }
    PublishedCriterionKnowledge publishedKnowledge(String code) {
        String file = value(code + "-published-knowledge-file"), pin = value(code + "-published-knowledge-sha256");
        try {
            if (file.isBlank() || !pin.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("PUBLISHED_KNOWLEDGE_PIN_REQUIRED");
            return PublishedCriterionKnowledge.load(mapper, Path.of(file), pin);
        } catch (Exception bad) { throw new IllegalArgumentException(code.toUpperCase(java.util.Locale.ROOT) + "_PUBLISHED_KNOWLEDGE_UNAVAILABLE", bad); }
    }

    @Nullable private java.util.function.Supplier<Pm1ReviewedRegions> pm1ReviewedRegions() {
        String file = value(PM1_REVIEWED_FILE), pin = value(PM1_REVIEWED_SHA);
        if (file.isBlank() && pin.isBlank()) return null;
        return org.springframework.util.function.SingletonSupplier.of(() -> {
            try {
                return Pm1ReviewedRegions.load(mapper, Path.of(file), pin, this::pm1ReleasedScope);
            } catch (java.io.IOException | IllegalArgumentException unavailable) {
                throw new IllegalArgumentException("PM1_REVIEWED_REGION_CATALOG_INVALID", unavailable);
            }
        });
    }

    private ObjectNode pm1ReleasedScope(JsonNode context) {
        try {
            var releases = predictionReleaseCatalog();
            if (releases != null) return releases.select(context);
        } catch (java.io.IOException | IllegalArgumentException unavailable) {
            throw new IllegalArgumentException("PM1_REVIEWED_REGION_RELEASE_CATALOG_UNAVAILABLE", unavailable);
        }
        throw new IllegalArgumentException("PM1_REVIEWED_REGION_RELEASE_CATALOG_UNAVAILABLE");
    }

    private Pm1ClinVarIntake pm1ClinVarIntake() {
        String file = value("clinvar-sidecar-file"), manifest = value("clinvar-sidecar-manifest");
        return new Pm1ClinVarIntake(mapper, properties, file.isBlank() && manifest.isBlank() ? null :
                org.springframework.util.function.SingletonSupplier.of(() -> {
                    try {
                        Path sidecar = Path.of(file);
                        ClinVarMissenseResourceReadiness.verifyClinVar(mapper, properties, sidecar, Path.of(manifest));
                        return clinVarSubmissions(sidecar);
                    } catch (Exception invalid) {
                        throw new IllegalArgumentException("PM1_CLINVAR_SUBMISSIONS_UNAVAILABLE", invalid);
                    }
                }), clinVarConditionIdentity());
    }
    private java.util.function.Supplier<ClinVarConditionIdentity> clinVarConditionIdentity() {
        String file = value("prediction-mondo-file"), pin = value("prediction-mondo-sha256");
        return org.springframework.util.function.SingletonSupplier.of(() -> {
            if (file.isBlank() && pin.isBlank()) return ClinVarConditionIdentity.unavailable("CONDITION_MAPPING_NOT_CONFIGURED");
            try { return ClinVarConditionIdentity.load(Path.of(file), pin); }
            catch (java.io.IOException | IllegalArgumentException invalid) {
                return ClinVarConditionIdentity.unavailable("CONDITION_MAPPING_SOURCE_INVALID");
            }
        });
    }
    Pvs1SelectedBatch pvs1() {
        if (!env.getProperty(PREFIX + "pvs1-selected-knowledge-enabled", Boolean.class, false))
            throw new IllegalArgumentException("PVS1_SELECTED_KNOWLEDGE_REQUIRED");
        return new Pvs1SelectedBatch(mapper, AcmgBusinessConfig.pvs1Bundle(mapper, env, properties),
                new Pvs1StartLossAutomaticPreparation(mapper, () -> java.util.Objects.requireNonNull(transcriptMaterials()),
                        new Pvs1StartLossPublicSources(mapper,
                                env.getProperty(PREFIX + "local-batch-denovo-public-network-enabled", Boolean.class, false)),
                        () -> {
                            try { return predictionSpecializations(); }
                            catch (java.io.IOException unavailable) { throw new IllegalArgumentException("PVS1_START_LOSS_EXPERT_SOURCE_UNAVAILABLE", unavailable); }
                        }, new Pvs1StartLossClinVarSearch(mapper, properties),
                        new Pvs1StartLossFunctionalLiterature(mapper, Path.of(env.getProperty("varvis.literature-workflow-config",
                                "../local/private/config/denovo-literature-workflow/config.json")))));
    }
    /** Neutral RNA source preparation has no PVS1 knowledge, LoF or selected-criterion premise. */
    ReviewedRuleBundle fullRnaMaterials() {
        try {
            var supply = transcriptMaterials();
            if (supply == null) throw new IllegalArgumentException("FULL_RNA_REFERENCE_SUPPLY_UNAVAILABLE");
            return ReviewedRuleBundle.loadPvs1(mapper, Path.of(value("pvs1-bundle-file")),
                    value("pvs1-bundle-file-sha256"), value("pvs1-bundle-sha256"),
                    new Pvs1TranscriptMaterialSupply(mapper, supply));
        } catch (java.io.IOException invalid) { throw new IllegalArgumentException("FULL_RNA_CONTROLLED_BUNDLE_UNAVAILABLE", invalid); }
    }
    private ObjectNode fullRnaDeclaredPins() {
        var out = mapper.createObjectNode();
        for (String key : List.of("pvs1-bundle-file-sha256", "pvs1-bundle-sha256", "transcript-material-manifest-sha256",
                "gencode-transcript-index-manifest-sha256", "reference-receipt-sha256")) out.put(key, value(key));
        return out;
    }
    GeneralBp3Runtime bp3(ProteinLengthRegionFactSupply facts) {
        try {
            String file = value("bp3-reviewed-region-assertions-file");
            var assessment = file.isBlank() ? new Bp3ProteinRegionAssessment(mapper)
                    : new Bp3ProteinRegionAssessment(mapper, Path.of(file), value("bp3-reviewed-region-assertions-sha256"));
            return new GeneralBp3Runtime(mapper, facts, assessment,
                    PredictionSpecializationGuard.load(mapper, Path.of(value("prediction-specialization-catalog-file")), value("prediction-specialization-catalog-sha256")),
                    value("bp3-published-knowledge-file").isBlank() ? null : publishedKnowledge("bp3"));
        } catch (Exception invalid) { throw new IllegalArgumentException("BP3_CONFIGURATION_UNAVAILABLE", invalid); }
    }
    GeneralPm4Runtime pm4() {
        try {
            var supply = proteinLengthMaterials();
            if (supply == null) throw new IllegalArgumentException("PM4_TRANSCRIPT_CONFIGURATION_REQUIRED");
            String assertions = value("pm4-reviewed-repeat-assertions-file"), combined = value("pm4-combined-region-file");
            var demand = demand();
            String local = value("pm4-protein-region-directory");
            return new GeneralPm4Runtime(mapper, supply,
                    demand != null && local.isBlank() ? null : new UniProtProteinRegionSupply(mapper, Path.of(local), value("pm4-protein-region-manifest-sha256")),
                    combined.isBlank() ? null : new CombinedProteinRegionSupply(mapper, Path.of(combined), value("pm4-combined-region-sha256")),
                    assertions.isBlank() ? new Pm4ProteinRegionAssessment(mapper)
                            : new Pm4ProteinRegionAssessment(mapper, Path.of(assertions), value("pm4-reviewed-repeat-assertions-sha256")),
                    PredictionSpecializationGuard.load(mapper, Path.of(value("prediction-specialization-catalog-file")), value("prediction-specialization-catalog-sha256")),
                    Pm4OperationalNonRepeatPolicy.mode(value("pm4-non-repeat-policy")), demand);
        } catch (Exception invalid) { throw new IllegalArgumentException("PM4_CONFIGURATION_UNAVAILABLE", invalid); }
    }

    private boolean demandEnabled() { return env.getProperty(PREFIX + "pm4-region-demand-enabled", Boolean.class, false); }
    @Nullable ProteinRegionDemandSupply demand() throws java.io.IOException {
        if (!demandEnabled()) return null;
        String cache = value("pm4-region-demand-cache-directory");
        if (cache.isBlank()) throw new IllegalArgumentException("PROTEIN_REGION_CACHE_DIRECTORY_REQUIRED");
        return new ProteinRegionDemandSupply(mapper, new ProteinRegionDemandSupply.Settings(Path.of(cache),
                value("pm4-region-demand-uniprot-release"), value("pm4-region-demand-interpro-release"), value("pm4-region-demand-interpro-minor"),
                env.getProperty(PREFIX + "pm4-region-demand-network-enabled", Boolean.class, false),
                limit("max-proteins",10), limit("max-requests",60), limit("total-seconds",90), limit("request-seconds",15), limit("max-bytes",32_000_000)));
    }
    private int limit(String key, int fallback) { return env.getProperty(PREFIX + "pm4-region-demand-" + key, Integer.class, fallback); }

    @Nullable NativeSpliceAiRuntime nativeSplice() {
        if (!env.getProperty(PREFIX + "local-batch-native-splice-enabled", Boolean.class, false)) return null;
        try {
            var ledger = org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.load(mapper,
                    Path.of(value("reference-receipt-file")), value("reference-receipt-sha256"));
            var reference = new org.nbscn.varvis.variantanalysis.service.CodingTranscriptReferenceReader(mapper, properties,
                    Path.of(value("resource-root")), ledger, value("reference-receipt-locator"));
            return new NativeSpliceAiRuntime(mapper,
                    new GencodeTranscriptIndex(mapper, Path.of(value("gencode-transcript-index-directory")), value("gencode-transcript-index-manifest-sha256")),
                    reference, predictionSpecializations(),
                    new NativeSpliceAiBatchRunner(mapper, List.of(value("native-splice-python-file"), value("native-splice-adapter-file")),
                            Path.of(value("native-splice-reference-fasta")), Path.of(value("native-splice-binding-file")), value("native-splice-binding-sha256"),
                            AcmgBusinessConfig.businessWorkingRoot(properties, env).resolve("native-splice"),
                            java.time.Duration.ofSeconds(env.getProperty(PREFIX + "native-splice-timeout-seconds", Integer.class, 540))));
        } catch (Exception invalid) { throw new IllegalArgumentException("NATIVE_SPLICE_CONFIGURATION_UNAVAILABLE", invalid); }
    }

    private String referenceSpliceMode() {
        String mode = env.getProperty(PREFIX + "local-batch-reference-splice-mode", "LEGACY_WSL");
        if (!List.of("LEGACY_WSL", "NATIVE_SELECTED").contains(mode))
            throw new IllegalArgumentException("REFERENCE_SPLICE_MODE_INVALID");
        return mode;
    }

    MissenseNativeSpliceBridge referenceNativeSplice() {
        return referenceNativeSplice(false);
    }

    @Nullable MissenseNativeSpliceBridge bp1WideSplice() {
        if(!env.getProperty(PREFIX+"bp1-wide-splice-enabled",Boolean.class,false)) return null;
        // An optional wide-window failure blocks only the clauses requiring it;
        // the independently bound legacy PALB2 path remains usable.
        try { return referenceNativeSplice(true); }
        catch (IllegalArgumentException unavailable) { return null; }
    }

    private MissenseNativeSpliceBridge referenceNativeSplice(boolean wide) {
        try {
            var ledger = org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.load(mapper,
                    Path.of(value("reference-receipt-file")), value("reference-receipt-sha256"));
            var reference = new org.nbscn.varvis.variantanalysis.service.CodingTranscriptReferenceReader(mapper, properties,
                    Path.of(value("resource-root")), ledger, value("reference-receipt-locator"));
            var bridge = new MissenseNativeSpliceBridge(mapper, reference,
                    new NativeSpliceAiBatchRunner(mapper, List.of(value("native-splice-python-file"), value(wide?"bp1-wide-splice-adapter-file":"native-splice-adapter-file")),
                            Path.of(value("native-splice-reference-fasta")), Path.of(value(wide?"bp1-wide-splice-binding-file":"native-splice-binding-file")), value(wide?"bp1-wide-splice-binding-sha256":"native-splice-binding-sha256"),
                            AcmgBusinessConfig.businessWorkingRoot(properties, env).resolve(wide?"native-bp1-wide-splice":"native-reference-splice"),
                            java.time.Duration.ofSeconds(env.getProperty(PREFIX + "native-splice-timeout-seconds", Integer.class, 540))),wide?10000:4999);
            if (!wide && !value("reference-native-retained-audit-file").isBlank())
                bridge.loadRetainedAudit(Path.of(value("reference-native-retained-audit-file")), value("reference-native-retained-audit-sha256"));
            return bridge;
        } catch (Exception invalid) { throw new IllegalArgumentException("REFERENCE_NATIVE_SPLICE_CONFIGURATION_UNAVAILABLE", invalid); }
    }

    private String proteinModel() {
        String model = env.getProperty(PREFIX + "local-batch-general-protein-model", "REVEL");
        if (!List.of("REVEL", "ALPHAMISSENSE").contains(model)) throw new IllegalArgumentException("PREDICTION_GENERAL_MODEL_INVALID");
        return model;
    }
    @Nullable GeneralAlphaMissenseRuntime generalAlpha() {
        if (!"ALPHAMISSENSE".equals(proteinModel())) return null;
        try {
            var binding = PredictionLocalResourceBinding.load(mapper, Path.of(value("general-alphamissense-binding-file")), value("general-alphamissense-binding-sha256"));
            var provider = new AlphaMissenseProvider(properties, binding, amBudget);
            String directory = value("alphamissense-source-transcript-index-directory");
            GencodeTranscriptIndex index = directory.isBlank() ? null : new GencodeTranscriptIndex(mapper, Path.of(directory), value("alphamissense-source-transcript-index-manifest-sha256"));
            CodingTranscriptMaterialSupply.ReferenceReader reference = null;
            if (index != null) {
                var ledger = org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.load(mapper,
                        Path.of(value("reference-receipt-file")), value("reference-receipt-sha256"));
                reference = new org.nbscn.varvis.variantanalysis.service.CodingTranscriptReferenceReader(mapper, properties,
                        Path.of(value("resource-root")), ledger, value("reference-receipt-locator"));
            }
            return GeneralAlphaMissenseRuntime.configured(mapper, provider,
                    predictionSpecializations(), index, reference);
        } catch (Exception unavailable) { throw new IllegalArgumentException("GENERAL_ALPHAMISSENSE_CONFIGURATION_UNAVAILABLE", unavailable); }
    }

    synchronized PredictionSpecializationGuard predictionSpecializations() throws java.io.IOException {
        if (predictionScopeGuard != null) return predictionScopeGuard;
        String directory = value("prediction-specialization-source-directory");
        var guard = PredictionSpecializationGuard.load(mapper, Path.of(value("prediction-specialization-catalog-file")),
                value("prediction-specialization-catalog-sha256"), directory.isBlank() ? null : Path.of(directory));
        var catalog = predictionReleaseCatalog();
        predictionScopeGuard = catalog == null ? guard : guard.withReleasedCatalog(catalog);
        return predictionScopeGuard;
    }

    @Nullable private synchronized PublishedPredictionCatalog predictionReleaseCatalog() throws java.io.IOException {
        String index = value("prediction-release-index-file");
        if (index.isBlank()) return null;
        if (predictionReleaseCatalog == null) predictionReleaseCatalog = PublishedPredictionCatalog.load(mapper,
                Path.of(index), value("prediction-release-index-sha256"), Path.of(value("prediction-mondo-file")),
                value("prediction-mondo-sha256"), Path.of(value("binding-directory")));
        return predictionReleaseCatalog;
    }

    @Nullable private SelectedPredictionRuntime.PublishedRoute publishedPrediction() {
        PublishedPredictionCatalog catalog;
        try { catalog = predictionReleaseCatalog(); }
        catch (java.io.IOException unavailable) { throw new IllegalArgumentException("PREDICTION_RELEASE_CATALOG_UNAVAILABLE", unavailable); }
        if (catalog == null) return null;
        GeneralRevelRuntime runtime;
        try { runtime = indexedRevel(); }
        catch (java.io.IOException | IllegalArgumentException unavailable) { runtime = null; }
        return new SelectedPredictionRuntime.PublishedRoute(catalog, runtime);
    }

    @Nullable GeneralRevelRuntime generalRevel() {
        if (!"REVEL".equals(proteinModel())) return null;
        if (!env.getProperty(PREFIX + "local-batch-general-revel-enabled", Boolean.class, false)) return null;
        try {
            return indexedRevel();
        } catch (Exception unavailable) { throw new IllegalArgumentException("GENERAL_REVEL_CONFIGURATION_UNAVAILABLE", unavailable); }
    }

    private GeneralRevelRuntime indexedRevel() throws java.io.IOException {
        return GeneralRevelRuntime.configured(mapper, RevelPublisherIndexObservationProvider.loadLocal(mapper,
                Path.of(value("revel-publisher-index-directory")), Path.of(value("revel-publisher-review-file")),
                value("revel-publisher-review-file-sha256"), value("revel-publisher-review-sha256")), predictionSpecializations());
    }

    @Nullable CodingTranscriptMaterialSupply transcriptMaterials() {
        return transcriptMaterials(false);
    }

    /** Request-owned PM4/BP3 sequence facts; other consumers retain their original scope. */
    @Nullable CodingTranscriptMaterialSupply proteinLengthMaterials() {
        return transcriptMaterials(true);
    }

    @Nullable private CodingTranscriptMaterialSupply transcriptMaterials(boolean proteinLength) {
        String root = value("transcript-material-directory");
        String gencode = value("gencode-transcript-index-directory");
        if (root.isBlank() && gencode.isBlank()) return null;
        try {
            var ledger = org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger.load(mapper,
                    Path.of(value("reference-receipt-file")), value("reference-receipt-sha256"));
            var reference = new org.nbscn.varvis.variantanalysis.service.CodingTranscriptReferenceReader(mapper, properties,
                    Path.of(value("resource-root")), ledger, value("reference-receipt-locator"));
            var index = gencode.isBlank() ? null : new GencodeTranscriptIndex(mapper, Path.of(gencode), value("gencode-transcript-index-manifest-sha256"));
            if (proteinLength) return ProteinLengthMaterialFactory.reviewedSmallAlleles(mapper,
                    root.isBlank() ? null : Path.of(root), value("transcript-material-manifest-sha256"), reference, index);
            return new CodingTranscriptMaterialSupply(mapper, root.isBlank() ? null : Path.of(root),
                    value("transcript-material-manifest-sha256"), reference, index);
        } catch (Exception invalid) { throw new IllegalArgumentException("TRANSCRIPT_MATERIAL_CONFIGURATION_UNAVAILABLE", invalid); }
    }

    /** Loads the pinned declaration without constructing any scientific runtime. */
    @Nullable private String referenceUseIssue(String criterion, ObjectNode context) {
        if (materialIndexConfigured()) {
            try { return materialIndex().useIssue(criterion, context); }
            catch (Exception invalid) { return "REFERENCE_MATERIAL_INDEX_INVALID"; }
        }
        String code = criterion.substring(criterion.indexOf(':') + 1).toLowerCase(java.util.Locale.ROOT);
        String key = "local-batch-" + code + "-bundle";
        try {
            if (value(key + "-file").isBlank()) return "REFERENCE_APPROVED_MATERIAL_UNAVAILABLE";
            var bundle = ReviewedRuleBundle.load(mapper, Path.of(value(key + "-file")), value(key + "-sha256"));
            if (!("ps1".equals(code) ? bundle.supportsPs1() : bundle.supportsPm5())) return "REFERENCE_BUNDLE_CRITERION_MISMATCH";
            return bundle.missenseUseIssue(context);
        } catch (Exception invalid) { return "REFERENCE_MATERIAL_QUALIFICATION_INVALID"; }
    }

    private ClinVarConditionAwareComparatorInput comparator() {
        try {
            Path sidecar = Path.of(env.getRequiredProperty(PREFIX + "clinvar-sidecar-file"));
            Path manifest = Path.of(env.getRequiredProperty(PREFIX + "clinvar-sidecar-manifest"));
            ClinVarMissenseResourceReadiness.verify(mapper, properties, sidecar, manifest);
            return new ClinVarConditionAwareComparatorInput(new ClinVarTabixProvider(properties), new VepTranscriptAnnotationProvider(properties),
                    clinVarSubmissions(sidecar), new VepProteinRelationClassifier());
        } catch (Exception invalid) { throw new IllegalArgumentException("REFERENCE_CONFIGURATION_UNAVAILABLE", invalid); }
    }

    private ClinVarComparatorSidecar clinVarSubmissions(Path sidecar) {
        var source = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("resources").path(2);
        return new ClinVarComparatorSidecar(mapper, sidecar, source.path("resourceId").asText(), source.path("resourceVersion").asText(),
                source.path("identityFingerprintSha256").asText());
    }

    private ReviewedRuleBundle approvedReference(String criterion, ClinVarConditionAwareComparatorInput.SnapshotCapture capture,
            Function<List<SpliceAiRawBatchRunner.Query>, SpliceAiRawBatchRunner.BatchResult> raw,
            String mode, java.util.function.Supplier<MissenseNativeSpliceBridge> nativeRaw) {
        String code = criterion.substring(criterion.indexOf(':') + 1);
        String key = "local-batch-" + code.toLowerCase(java.util.Locale.ROOT) + "-bundle";
        if (!materialIndexConfigured() && value(key + "-file").isBlank()) throw new IllegalArgumentException("REFERENCE_APPROVED_MATERIAL_UNAVAILABLE");
        try {
            var index = materialIndexConfigured() ? materialIndex() : null;
            if (index != null) return index.bind(criterion, capture.getEvidenceContext(), capture.getSelectedTargetAnnotation(), () -> {
                var selectedNative = "NATIVE_SELECTED".equals(mode) ? nativeRaw.get() : null;
                return (selectedNative != null ? MissenseComparisonRuntime.withNativeRaw(mapper, null, selectedNative)
                        : new MissenseComparisonRuntime(mapper, null, value("spliceai-raw-command-json").isBlank() ? null : raw,
                                SpliceAiCriterionEvaluator.loadProfile(mapper).at("/resources/0/identityFingerprintSha256").asText()))
                        .withCapturedInput(capture);
            }, mapper.createObjectNode().put("mode", "SAME_CAPTURE_INDEXED_REVIEWED_BUNDLE").put("spliceMode", mode)
                    .put("nativeBindingExpectedRawSha256", value("native-splice-binding-sha256")));
            // The bundle's existing scientific and RNA gates remain mandatory. Shared masked facts are not raw proof.
            var nativeBridge = "NATIVE_SELECTED".equals(mode) ? nativeRaw.get() : null;
            var runtime = (nativeBridge != null ? MissenseComparisonRuntime.withNativeRaw(mapper, null, nativeBridge) : new MissenseComparisonRuntime(mapper, null, value("spliceai-raw-command-json").isBlank() ? null : raw,
                    SpliceAiCriterionEvaluator.loadProfile(mapper).at("/resources/0/identityFingerprintSha256").asText()))
                    .withCapturedInput(capture);
            var bundle = ReviewedRuleBundle.load(mapper, Path.of(value(key + "-file")), value(key + "-sha256"), () -> runtime,
                    mapper.createObjectNode().put("mode", "SAME_CAPTURE_EXISTING_REVIEWED_BUNDLE").put("spliceMode", mode)
                            .put("nativeResourceSha256", nativeBridge == null ? "" : nativeBridge.resourceSha256()));
            if ("PS1".equals(code) ? !bundle.supportsPs1() : !bundle.supportsPm5())
                throw new IllegalArgumentException("REFERENCE_BUNDLE_CRITERION_MISMATCH");
            return bundle;
        } catch (Exception invalid) {
            String message = invalid.getMessage();
            if (materialIndexConfigured() && message != null && message.matches("REFERENCE_MATERIAL_[A-Z0-9_]+"))
                throw new IllegalArgumentException(message, invalid);
            throw new IllegalArgumentException(materialIndexConfigured() ? "REFERENCE_MATERIAL_INDEX_INVALID" : "REFERENCE_APPROVED_MATERIAL_INVALID", invalid);
        }
    }

    private SpliceAiRawBatchRunner.BatchResult runRawSplice(List<SpliceAiRawBatchRunner.Query> queries) {
        try {
            List<String> command = mapper.readValue(value("spliceai-raw-command-json"),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            Path fasta = Path.of(value("spliceai-reference-fasta"));
            ClinVarMissenseResourceReadiness.verifyRawCommand(mapper, command, fasta, properties);
            Path root = AcmgBusinessConfig.businessWorkingRoot(properties, env).resolve("local-batch-reviewed-raw");
            Files.createDirectories(root);
            return new SpliceAiRawBatchRunner(mapper, command, fasta, root,
                    java.time.Duration.ofSeconds(env.getProperty(PREFIX + "spliceai-timeout-seconds", Integer.class, 600)), 100).run(queries);
        } catch (Exception unavailable) { throw new IllegalArgumentException("REFERENCE_RAW_SPLICE_CONFIGURATION_UNAVAILABLE", unavailable); }
    }

    @Nullable private ObjectNode sourceSnapshot() {
        String file = value("local-batch-reference-source-file"), pin = value("local-batch-reference-source-sha256");
        if (file.isBlank()) return null;
        try {
            Path path = Path.of(file);
            if (!pin.matches("[0-9a-f]{64}") || !Files.isRegularFile(path) || Files.size(path) > 65536) return null;
            byte[] bytes;
            try (var in = Files.newInputStream(path)) { bytes = in.readNBytes(65537); }
            if (bytes.length > 65536 || !pin.equals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) return null;
            if (!(mapper.readTree(bytes) instanceof ObjectNode object)) return null;
            var registered = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("resources");
            var roles = List.of("CLINVAR_VCF", "VEP", "SIDECAR");
            for (int i = 0; i < roles.size(); i++) for (String field : List.of("resourceId", "resourceVersion", "identityFingerprintSha256"))
                if (!registered.get(i).path(field).equals(object.path(roles.get(i)).path(field))) return null;
            return object;
        } catch (Exception invalid) { return null; }
    }

    /** Only stat calls on explicitly configured paths; no directory traversal or large-file hashing. */
    ObjectNode identity() {
        var pathModule = new com.fasterxml.jackson.databind.module.SimpleModule("LocalBatchPaths")
                .addSerializer(Path.class, new com.fasterxml.jackson.databind.ser.std.ToStringSerializer());
        var identities = mapper.copy().registerModule(pathModule).disable(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        var out = mapper.createObjectNode().put("schemaVersion", "local-batch1-configuration-v1");
        out.set("finitePublishedConditionExecution", PublishedCriterionKnowledge.executionIdentity(mapper));
        out.set("selectedRoutingExecution", SelectedCriterionRouting.executionIdentity(mapper));
        out.set("pm1CrossCriterionExecution", GeneralPm1Runtime.executionIdentity(mapper));
        out.set("bp1TruncatingConditionExecution", GeneralBp1Runtime.truncatingConditionIdentity(mapper));
        out.put("ba1ConditionalConsumerExecution", GnomadPopulationCriterionEvaluator.BA1_CONDITIONAL_EVALUATOR_VERSION);
        if (env.getProperty(PREFIX + "local-batch-denovo-enabled", Boolean.class, false)) {
            for (String key : List.of("local-batch-denovo-enabled", "local-batch-denovo-use", "local-batch-denovo-material-file", "local-batch-denovo-material-sha256",
                    "local-batch-denovo-literature-enabled", "local-batch-denovo-literature-python", "local-batch-denovo-literature-scripts", "local-batch-denovo-literature-cache",
                    "local-batch-denovo-model-artifact-index", "local-batch-denovo-model-artifact-sha256", "local-batch-denovo-candidate-repository",
                    "local-batch-denovo-core-context-config", "local-batch-denovo-core-context-sha256"))
                out.put(key, value(key));
            String materialRoot = value("local-batch-denovo-material-root");
            if (!materialRoot.isBlank()) out.put("local-batch-denovo-material-root", materialRoot);
            String candidates=value("local-batch-denovo-candidate-repository");
            if(!candidates.isBlank()) try {
                out.put("denovoCandidateSnapshotSha256",new DeNovoCandidateRepository(Path.of(candidates)).snapshot().sha256());
            } catch(java.io.IOException invalid) {throw new IllegalArgumentException("DENOVO_CANDIDATE_CONFIGURATION_UNAVAILABLE",invalid);}
        }
        if (publishedEnabled()) {
            for (String key : PUBLISHED_KEYS) out.put(key, value(key));
            if (!value(PM1_REVIEWED_FILE).isBlank() || !value(PM1_REVIEWED_SHA).isBlank()) {
                out.put(PM1_REVIEWED_FILE, value(PM1_REVIEWED_FILE)).put(PM1_REVIEWED_SHA, value(PM1_REVIEWED_SHA))
                        .put("pm1ReviewedRegionSchema", Pm1ReviewedRegions.SCHEMA);
            }
            out.put("bp1StructuredLofReuseVersion", GeneralBp1Runtime.STRUCTURED_LOF_REUSE_VERSION);
            // A changed intake path must not reuse the earlier missing-relationship result.
            out.put("publishedContextKnowledgeBindingVersion", "1.0.0")
                    .put("publishedContextKnowledgeEnabled", AcmgBusinessConfig.fourteenContextKnowledge(env))
                    .put("bs1-knowledge-file", value("bs1-knowledge-file"))
                    .put("bs1-knowledge-sha256", value("bs1-knowledge-sha256"));
            out.put("pm1GeneralVersion", GeneralPm1Runtime.VERSION).put("pp2GeneralVersion", GeneralPp2Runtime.VERSION)
                    .put("bp1GeneralVersion", GeneralBp1Runtime.VERSION).put("pp2GermlineDeclared", pp2GermlineDeclared());
            out.put("pm1ClinVarIntakeVersion", Pm1ClinVarIntake.VERSION)
                    .put("pm1RegionalQualificationVersion", Pm1ReviewedRegions.QUALIFICATION_METHOD_VERSION)
                    .put("pm1ClinVarEnabled", properties.getClinVar().isEnabled())
                    .put("pm1ClinVarTabixExecutable", properties.getCadd().getTabixExecutable())
                    .put("pm1ClinVarRelease", properties.getClinVar().versionFor("GRCh38"));
            out.put("pp2HcmTranscriptScopeVersion", GeneralPp2Runtime.HCM_TRANSCRIPT_SCOPE_VERSION)
                    .put("pp2HcmSourceMethodAdaptationVersion", PublishedCriterionKnowledge.PP2_HCM_SOURCE_METHOD_ADAPTATION_VERSION)
                    .put("pp2NativePublicNetworkAllowed", env.getProperty(PREFIX + "local-batch-denovo-public-network-enabled", Boolean.class, false));
            out.put("bp1NativeSpliceVersion", MissenseNativeSpliceBridge.VERSION);
        }
        if (extendedEnabled()) {
            out.set("pvs1FunctionalLiterature", new Pvs1StartLossFunctionalLiterature(mapper,
                    Path.of(env.getProperty("varvis.literature-workflow-config",
                            "../local/private/config/denovo-literature-workflow/config.json"))).identity());
            out.put("pvs1StartLossAutomaticVersion", Pvs1StartLossAutomaticPreparation.VERSION)
                    .put("pvs1StartLossPublicSourcesVersion", Pvs1StartLossPublicSources.VERSION)
                    .put("pvs1StartLossPublicNetworkAllowed", env.getProperty(PREFIX + "local-batch-denovo-public-network-enabled", Boolean.class, false));
            for (String key : EXTENDED_KEYS) out.put(key, value(key));
            out.put("pvs1SelectedVersion", Pvs1SelectedBatch.VERSION).put("bp3PolicyVersion", GeneralBp3Runtime.VERSION)
                    .put("bp3AssessmentVersion", Bp3ProteinRegionAssessment.VERSION).put("bp3ProjectionVersion", CombinedProteinRegionSupply.BP3_PROJECTION_VERSION)
                    .put("reviewValidityDate", java.time.LocalDate.now(java.time.Clock.systemUTC()).toString());
        }
        if (env.getProperty(PREFIX + "local-batch-bp7-enabled", Boolean.class, false))
            out.put("local-batch-bp7-enabled", true).put("splicing-experiment-catalog",
                    env.getProperty(PREFIX + "splicing-experiment-catalog", "classpath:acmg/public-splicing-experiments-v1.json"));
        out.put("pm2LocalProfileVersion", Pm2LocalExomesProfile.PROFILE_VERSION);
        out.put("predictionRawFactReuseVersion", PredictionBatchFactCache.VERSION);
        out.put("predictionMechanismSummaryVersion", PredictionMechanismSummary.VERSION);
        out.put("predictionMechanismApplicationVersion", PredictionMechanismApplication.VERSION);
        if (env.getProperty(PREFIX + "local-batch-native-splice-enabled", Boolean.class, false)) out.put("nativeSpliceVersion", NativeSpliceAiRuntime.VERSION);
        if (env.getProperty(PREFIX + "local-batch-general-revel-enabled", Boolean.class, false)) {
            out.put("generalRevelVersion", GeneralRevelPolicy.VERSION);
            out.put("generalRevelPolicySha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(GeneralRevelPolicy.load(mapper)));
        }
        if ("ALPHAMISSENSE".equals(proteinModel())) {
            out.put("generalAlphaMissenseVersion", GeneralAlphaMissensePolicy.VERSION);
            out.put("generalAlphaMissensePolicySha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(GeneralAlphaMissensePolicy.load(mapper)));
        }
        if (materialIndexConfigured()) {
            out.put("referenceMaterialIndexVersion", MissenseMaterialIndex.VERSION)
                    .put("referenceMaterialIndexFile", value("local-batch-reference-material-index-file"))
                    .put("referenceMaterialIndexExpectedRawSha256", value("local-batch-reference-material-index-sha256"));
            try { out.set("referenceMaterialIndex", materialIndex().identity()); }
            catch (Exception invalid) { out.put("referenceMaterialIndexStatus", "INVALID_OR_UNAVAILABLE"); }
        }
        out.put("referenceMaterialQualificationVersion", MissenseMaterialQualification.VERSION);
        if (env.getProperty(PREFIX + "local-batch-reference-qualified-application-enabled", Boolean.class, false))
            out.put("referenceDraftRoutingVersion", "1.0.0");
        out.put("referenceAnnotationVersionBindingVersion", MissenseReferenceDraftPreparation.ANNOTATION_VERSION_BINDING_VERSION);
        out.put("referenceComparatorScopeVersion", ClinVarConditionAwareComparatorInput.COMPARATOR_SCOPE_VERSION);
        out.put("referenceFactSummaryVersion", MissenseReferenceFactSummary.VERSION);
        out.put("referenceSourceFactSemanticsVersion", MissenseReferenceDraftPreparation.SOURCE_FACT_SEMANTICS_VERSION);
        out.put("referenceConditionMappingVersion", MissenseConditionMapping.VERSION);
        out.put("referenceAnnotationSourceBindingVersion", MissenseComparisonRuntime.ANNOTATION_SOURCE_BINDING_VERSION);
        if (env.getProperty(PREFIX + "ps1-splice-enabled", Boolean.class, false)) {
            out.put("ps1SpliceComparisonVersion", Ps1SpliceComparison.VERSION);
            out.put("ps1SpliceAnnotationBindingVersion", "1.0.0");
            try { out.set("ps1SpliceKnowledge", Ps1SpliceKnowledge.load(mapper, Path.of(value("ps1-splice-knowledge-file")),
                    value("ps1-splice-knowledge-sha256")).identity(mapper)); }
            catch (Exception invalid) { out.put("ps1SpliceKnowledgeStatus", "INVALID_OR_UNAVAILABLE"); }
        }
        if ("NATIVE_SELECTED".equals(referenceSpliceMode())) out.put("referenceNativeSpliceVersion", MissenseNativeSpliceBridge.VERSION);
        if ("NATIVE_SELECTED".equals(referenceSpliceMode()) && !value("reference-native-retained-audit-file").isBlank()) {
            out.put("referenceNativeRetainedAuditVersion", MissenseNativeSpliceBridge.RETAINED_AUDIT_VERSION)
                    .put("referenceNativeRetainedAuditFile", value("reference-native-retained-audit-file"))
                    .put("referenceNativeRetainedAuditExpectedSha256", value("reference-native-retained-audit-sha256"));
            try (var stream = java.nio.file.Files.newInputStream(Path.of(value("reference-native-retained-audit-file")))) {
                byte[] bytes = stream.readNBytes(32_000_001);
                if (bytes.length > 32_000_000) throw new IllegalArgumentException("NATIVE_RETAINED_AUDIT_TOO_LARGE");
                out.put("referenceNativeRetainedAuditObservedSha256", java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            }
            catch (Exception invalid) { out.put("referenceNativeRetainedAuditStatus", "INVALID_OR_UNAVAILABLE"); }
        }
        if (!value("transcript-material-directory").isBlank() || !value("gencode-transcript-index-directory").isBlank())
            out.put("transcriptMaterialVersion", CodingTranscriptMaterialSupply.VERSION);
        if (!value("gencode-transcript-index-directory").isBlank()) out.put("gencodeTranscriptIndexVersion", GencodeTranscriptIndex.VERSION);
        if (pm4Enabled()) {
            out.put("pm4PolicyVersion", GeneralPm4Runtime.VERSION).put("pm4ShortRepeatVersion", ProteinShortRepeatScreen.VERSION)
                    .put("pm4CombinedVersion", CombinedProteinRegionSupply.VERSION).put("pm4AssessmentVersion", Pm4ProteinRegionAssessment.VERSION);
            out.put("proteinLengthMaterialFactoryVersion", ProteinLengthMaterialFactory.VERSION)
                    .put("proteinLengthMaterialCapability", ProteinLengthMaterialFactory.CAPABILITY_ID);
            out.set("proteinLengthPublicProjectionCapability", ProteinLengthPublicProof.identity(mapper));
            if (!value("pm4-reviewed-repeat-assertions-file").isBlank()) out.put("pm4ReviewValidityDate", java.time.LocalDate.now(java.time.Clock.systemUTC()).toString());
            for (String key : PM4_KEYS) out.put(key, value(key));
            if (demandEnabled()) {
                out.put("proteinRegionDemandVersion", ProteinRegionDemandSupply.VERSION);
                for (String key : DEMAND_KEYS) out.put(key, value(key));
            }
        }
        for (String key : KEYS) out.put(key, value(key));
        out.set("gnomad", identities.valueToTree(AcmgBusinessConfig.localBa1Settings(properties).getGnomad()));
        out.set("normalization", identities.valueToTree(properties.getNormalization()));
        out.set("alphaMissense", identities.valueToTree(properties.getAlphaMissense()));
        out.set("spliceAi", identities.valueToTree(properties.getSpliceAi()));
        out.set("clinVar", identities.valueToTree(properties.getClinVar()));
        out.set("vep", identities.valueToTree(properties.getVep()));
        var files = out.putArray("fileObservations");
        if (publishedEnabled()) for (String key : PUBLISHED_KEYS)
            if (key.endsWith("-file") && !value(key).isBlank()) observe(files, key, Path.of(value(key)), false);
        if (extendedEnabled()) for (String key : EXTENDED_KEYS)
            if (key.endsWith("-file") && !value(key).isBlank()) observe(files, key, Path.of(value(key)), false);
        if (pm4Enabled()) {
            for (String key : PM4_KEYS) if (key.endsWith("-file") && !value(key).isBlank()) observe(files, key, Path.of(value(key)), false);
            if (!value("pm4-protein-region-directory").isBlank()) observe(files, "pm4-region-manifest",
                    Path.of(value("pm4-protein-region-directory")).resolve("manifest.json"), false);
        }
        if ((publishedEnabled() || env.getProperty(PREFIX + "local-batch-native-splice-enabled", Boolean.class, false) || "NATIVE_SELECTED".equals(referenceSpliceMode()))
                && !value("native-splice-adapter-file").isBlank() && !value("native-splice-python-file").isBlank()) {
            Path script = Path.of(value("native-splice-adapter-file")).toAbsolutePath();
            observe(files, "native-splice-engine", script.resolveSibling("run_spliceai_native_probe.py"), false);
            observe(files, "native-splice-helper", script.getParent().getParent().resolve("e3_c2r/spliceai_raw_resource_probe.py"), false);
            Path venv = Path.of(value("native-splice-python-file")).toAbsolutePath().getParent().getParent();
            observe(files, "native-splice-keras", venv.getParent().resolve("keras/keras.json"), false);
            for (String asset : List.of("models/spliceai1.h5", "models/spliceai2.h5", "models/spliceai3.h5", "models/spliceai4.h5",
                    "models/spliceai5.h5", "annotations/grch38.txt", "utils.py", "__main__.py"))
                observe(files, "native-splice-asset-" + asset, venv.resolve("Lib/site-packages/spliceai").resolve(asset), false);
            observe(files, "native-splice-fai", Path.of(value("native-splice-reference-fasta") + ".fai"), false);
        }
        String allSites = value("local-batch-pm2-all-sites-file");
        if (!allSites.isBlank()) try {
            Path manifest = Path.of(allSites);
            if (Files.size(manifest) <= 65536) {
                var metadata = mapper.readTree(Files.readAllBytes(manifest));
                for (String field : List.of("dataFile", "indexFile")) {
                    String name = metadata.path(field).asText();
                    if (!name.isBlank() && !name.contains("/") && !name.contains("\\") && !name.contains(":"))
                        observe(files, "pm2-all-sites-" + field, manifest.getParent().resolve(name), false);
                }
            }
        } catch (Exception unavailable) { out.put("pm2AllSitesIdentity", "UNAVAILABLE"); }
        for (String key : KEYS) if (key.endsWith("-file") || key.endsWith("-manifest") || key.endsWith("-fasta")) {
            String value = value(key); if (!value.isBlank()) observe(files, key, Path.of(value), false);
        }
        for (var file : properties.getGnomad().getGrch38Files()) observe(files, "gnomad", file, true);
        observe(files, "alphaMissense", properties.getAlphaMissense().getGrch38File(), true);
        observe(files, "spliceSnv", properties.getSpliceAi().getSnvResourcePath(), true);
        observe(files, "spliceIndel", properties.getSpliceAi().getIndelResourcePath(), true);
        observe(files, "spliceSnvIndex", properties.getSpliceAi().getSnvIndexPath(), false);
        observe(files, "spliceIndelIndex", properties.getSpliceAi().getIndelIndexPath(), false);
        observe(files, "clinvar", properties.getClinVar().getGrch38File(), true);
        observe(files, "reference", properties.getNormalization().getGrch38Reference(), false);
        observe(files, "vepReference", properties.getVep().getFasta(), false);
        if (properties.getNormalization().getGrch38Reference() != null)
            observe(files, "reference-fai", Path.of(properties.getNormalization().getGrch38Reference() + ".fai"), false);
        if (properties.getVep().getFasta() != null)
            observe(files, "vepReference-fai", Path.of(properties.getVep().getFasta() + ".fai"), false);
        observe(files, "reference-aliases", properties.getNormalization().getGrch38ContigAliasFile(), false);
        String directory = value("binding-directory");
        String transcriptDirectory = value("transcript-material-directory");
        if (!transcriptDirectory.isBlank()) observe(files, "transcript-material-manifest", Path.of(transcriptDirectory).resolve("manifest.json"), false);
        String gencodeDirectory = value("gencode-transcript-index-directory");
        if (!gencodeDirectory.isBlank()) for (String name : List.of("manifest.json", "index.tsv", "annotations.jsonl"))
            observe(files, "gencode-transcript-" + name, Path.of(gencodeDirectory).resolve(name), false);
        String alphaDirectory = value("alphamissense-source-transcript-index-directory");
        if (!alphaDirectory.isBlank()) for (String name : List.of("manifest.json", "index.tsv", "annotations.jsonl"))
            observe(files, "alphamissense-source-transcript-" + name, Path.of(alphaDirectory).resolve(name), false);
        String revelDirectory = value("revel-publisher-index-directory");
        if (!revelDirectory.isBlank()) for (String name : List.of("manifest.json", "revel-grch38.lookup.tsv.gz", "revel-grch38.lookup.tsv.gz.tbi", "revel-unlocated-source-rows.tsv.gz"))
            observe(files, "revel-publisher-" + name, Path.of(revelDirectory).resolve(name), false);
        if (!directory.isBlank()) for (String child : List.of("manifest.json", "mane-crosswalk.json", "clingen-catalog.json"))
            observe(files, "binding-" + child, Path.of(directory).resolve(child), false);
        return out;
    }
    private void observe(com.fasterxml.jackson.databind.node.ArrayNode rows, String role, @Nullable Path path, boolean indexed) {
        if (path == null) return;
        var row = rows.addObject().put("role", role).put("path", path.toAbsolutePath().normalize().toString());
        try {
            var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            row.put("bytes", attributes.size()).put("modified", attributes.lastModifiedTime().toString())
                    .put("fileKey", String.valueOf(attributes.fileKey())).put("regular", attributes.isRegularFile());
        } catch (Exception unavailable) { row.put("status", "UNAVAILABLE"); }
        if (indexed) { observe(rows, role + "-tbi", Path.of(path + ".tbi"), false); observe(rows, role + "-csi", Path.of(path + ".csi"), false); }
    }
    private boolean materialIndexConfigured() {
        return !value("local-batch-reference-material-index-file").isBlank() || !value("local-batch-reference-material-index-sha256").isBlank();
    }
    private MissenseMaterialIndex materialIndex() throws java.io.IOException {
        if (value("local-batch-reference-material-index-file").isBlank()) throw new IllegalArgumentException("REFERENCE_MATERIAL_INDEX_INVALID");
        return MissenseMaterialIndex.load(Path.of(value("local-batch-reference-material-index-file")), value("local-batch-reference-material-index-sha256"));
    }
    private String value(String key) { return env.getProperty(PREFIX + key, ""); }
}
