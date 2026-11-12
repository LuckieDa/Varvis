package org.nbscn.varvis.variantanalysis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.*;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

@Configuration
@ConditionalOnProperty(name = "varvis.datasource.enabled", havingValue = "true", matchIfMissing = true)
public class AcmgBusinessConfig {
    @Nullable private final AlphaMissensePhaseBudget amPhaseBudget;

    public AcmgBusinessConfig() {
        this(null);
    }

    @Autowired
    public AcmgBusinessConfig(@Nullable AlphaMissensePhaseBudget amPhaseBudget) {
        this.amPhaseBudget = amPhaseBudget;
    }

    @Bean
    public Pm3LiteratureRaritySupply.Factory pm3LiteraturePopulation(ObjectMapper mapper, Environment env) {
        boolean enabled=env.getProperty("variant-analysis.acmg-business.local-batch-workflow-enabled",Boolean.class,false);
        if(!enabled) return new Pm3LiteratureRaritySupply.Factory() {
            public com.fasterxml.jackson.databind.node.ObjectNode identity(){return mapper.createObjectNode().put("enabled",false);}
            public Pm2LocalExomesRuntime create(){throw new IllegalArgumentException("PM3_LOCAL_POPULATION_WORKFLOW_DISABLED");}
        };
        var local=localWorkflowEnvironment(env);
        var settings=org.springframework.boot.context.properties.bind.Binder.get(local)
                .bind("variant-analysis",org.springframework.boot.context.properties.bind.Bindable.of(VariantAnalysisProperties.class))
                .orElseThrow(()->new IllegalArgumentException("LOCAL_WORKFLOW_CONFIGURATION_REQUIRED"));
        var configuration=new LocalEvidenceBatchConfiguration(mapper,settings,local,amPhaseBudget);
        return new Pm3LiteratureRaritySupply.Factory() {
            public com.fasterxml.jackson.databind.node.ObjectNode identity(){return configuration.populationIdentity();}
            public Pm2LocalExomesRuntime create(){return configuration.population();}
        };
    }
    /** Explicit isolated wiring; deliberately not a bean and never part of CURRENT. */
    public static Bs1Runtime isolatedBs1Runtime(ObjectMapper mapper, VariantAnalysisProperties settings, Path workingRoot) {
        return Bs1Runtime.configured(mapper, Bs1Runtime.policies(mapper), settings,
                new Bs1NativeFilterSupplement(mapper, settings, workingRoot));
    }
    @Bean
    public FunctionalExperimentReviews functionalExperimentReviews(ObjectMapper mapper, VariantAnalysisProperties properties, Environment env,
            AcmgBusinessService business, AcmgBusinessStore store) {
        return new FunctionalExperimentReviews(mapper, businessWorkingRoot(properties, env).resolve("functional-experiment-reviews"),
                business::read, (analysisId, operation) -> store.withAnalysisLock(analysisId, operation));
    }
    @Bean
    public DataSourceInitializer acmgBusinessSchema(@Qualifier("varvisDataSource") DataSource dataSource, VariantAnalysisProperties properties,
            @Qualifier("variantAnalysisDataSourceInitializer") org.springframework.beans.factory.ObjectProvider<DataSourceInitializer> legacySchema) throws java.io.IOException, java.sql.SQLException {
        legacySchema.getIfAvailable(); // Complete the existing optional bootstrap before adding private foreign keys.
        var initializer = new DataSourceInitializer(); initializer.setDataSource(java.util.Objects.requireNonNull(dataSource));
        String prefix = new VariantAnalysisTableNames(properties.getPersistence().getTablePrefix()).prefix();
        // Run additive DDL only for missing tables; avoid metadata locks on existing history.
        var missing = new java.util.HashSet<String>();
        try (var connection = dataSource.getConnection()) {
            for (String name : java.util.List.of("variant_analysis_acmg_source", "variant_analysis_acmg_run", "variant_analysis_acmg_chunk"))
                if (!tableExists(connection, prefix + name)) missing.add(prefix + name);
        }
        if (missing.isEmpty()) { initializer.setEnabled(false); return initializer; }
        String sql;
        try (var input = new ClassPathResource("db/acmg-business-schema.sql").getInputStream()) {
            sql = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("variant_analysis_acmg_source", prefix + "variant_analysis_acmg_source")
                    .replace("variant_analysis_acmg_run", prefix + "variant_analysis_acmg_run")
                    .replace("variant_analysis_acmg_chunk", prefix + "variant_analysis_acmg_chunk")
                    .replace("variant_analysis_job", prefix + "variant_analysis_job");
        }
        sql = java.util.Arrays.stream(sql.split(";")).map(mappedItem -> mappedItem.trim())
                .filter(statement -> missing.stream().anyMatch(name -> statement.startsWith("CREATE TABLE IF NOT EXISTS " + name + " (")))
                .collect(java.util.stream.Collectors.joining(";\n", "", ";"));
        initializer.setDatabasePopulator(new ResourceDatabasePopulator(new org.springframework.core.io.ByteArrayResource(java.util.Objects.requireNonNull(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        return initializer;
    }
    private static boolean tableExists(java.sql.Connection connection, String name) throws java.sql.SQLException {
        var metadata = connection.getMetaData();
        String identifier = metadata.storesUpperCaseIdentifiers() ? name.toUpperCase(java.util.Locale.ROOT) : name;
        String escape = metadata.getSearchStringEscape();
        String pattern = identifier.replace("_", escape + "_");
        try (var tables = metadata.getTables(connection.getCatalog(), null, pattern, new String[]{"TABLE"})) {
            while (tables.next()) if (identifier.equals(tables.getString("TABLE_NAME"))) return true;
        }
        return false;
    }
    @Bean @DependsOn("acmgBusinessSchema")
    public AcmgBusinessStore acmgBusinessStore(@Qualifier("varvisDataSource") DataSource dataSource, VariantAnalysisProperties properties) { return new AcmgBusinessStore(dataSource, properties.getPersistence().getTablePrefix()); }
    @Bean
    public AcmgExecutionSources acmgExecutionSources(ObjectMapper mapper, AcmgBusinessStore store) { return new AcmgExecutionSources(mapper, store); }
    @Bean
    public AcmgBusinessWorkflows acmgBusinessWorkflows(ObjectMapper mapper, VariantAnalysisJobRepository jobs,
            AcmgBusinessStore store, AcmgExecutionSources sources, VariantAnalysisProperties properties,
            Environment env, AcmgBusinessService current) {
        boolean unified = env.getProperty("variant-analysis.acmg-business.unified-local-workflow-enabled", Boolean.class, false);
        if (!env.getProperty("variant-analysis.acmg-business.local-batch-workflow-enabled", Boolean.class, false)) {
            if (unified) throw new IllegalArgumentException("UNIFIED_LOCAL_BATCH_CONFIGURATION_REQUIRED");
            return new AcmgBusinessWorkflows(current, null);
        }
        // A new service identity shares the repository but not the old workflow's execution scope.
        var localEnvironment = localWorkflowEnvironment(env);
        var localProperties = org.springframework.boot.context.properties.bind.Binder.get(localEnvironment)
                .bind("variant-analysis", org.springframework.boot.context.properties.bind.Bindable.of(VariantAnalysisProperties.class))
                .orElseThrow(() -> new IllegalArgumentException("LOCAL_WORKFLOW_CONFIGURATION_REQUIRED"));
        if (unified) {
            var unifiedService = AcmgBusinessService.unifiedLocal(current,
                    buildBusinessService(mapper, jobs, store, sources, localProperties, localEnvironment));
            return new AcmgBusinessWorkflows(unifiedService, null);
        }
        return new AcmgBusinessWorkflows(current, buildBusinessService(mapper, jobs, store, sources, localProperties, localEnvironment));
    }

    static Environment localWorkflowEnvironment(Environment original) {
        var isolated = new org.springframework.core.env.StandardEnvironment();
        var sources = isolated.getPropertySources();
        sources.remove(org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        sources.remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        var overrides = new java.util.LinkedHashMap<String, Object>();
        String prefix = "variant-analysis.acmg-business.";
        overrides.put(prefix + "acceptance-scope", AcmgBusinessRuleScope.LOCAL_BATCH1);
        overrides.put(prefix + "bs1-local-exomes-enabled", false);
        // Fourteen-rule recommendations and execution intake must see the same pinned
        // source relationships; each criterion still applies its own scientific policy.
        if (!fourteenContextKnowledge(original)) {
            overrides.put(prefix + "bs1-knowledge-file", "");
            overrides.put(prefix + "bs1-knowledge-sha256", "");
        }
        // This Windows workflow does not inherit the retired WSL reference-material runner.
        overrides.put(prefix + "spliceai-raw-command-json", "");
        // These acquisition modes apply only to this local workflow, not upload annotation or CURRENT.
        overrides.put("variant-analysis.gnomad.local-population-in-process-enabled",
                original.getProperty(prefix + "local-batch-workflow-in-process-population-enabled", Boolean.class, false));
        // These flags govern the selected local providers as well as upload annotation.
        // Keep CURRENT unchanged; allow only the explicitly selected local acquisitions.
        overrides.put("variant-analysis.alpha-missense.enabled",
                "ALPHAMISSENSE".equals(original.getProperty(prefix + "local-batch-general-protein-model", "REVEL"))
                        && original.getProperty("variant-analysis.alpha-missense.enabled", Boolean.class, false));
        overrides.put("variant-analysis.clin-var.enabled",
                !original.getProperty(prefix + "local-batch-reference-source-file", "").isBlank()
                        && original.getProperty("variant-analysis.clin-var.enabled", Boolean.class, false));
        overrides.put("variant-analysis.splice-ai.enabled", false);
        String alias = original.getProperty(prefix + "local-batch-workflow-grch38-contig-alias-file", "");
        if (!alias.isBlank()) overrides.put("variant-analysis.normalization.grch38-contig-alias-file", alias);
        for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
            overrides.put(prefix + flag, false);
        sources.addFirst(new org.springframework.core.env.MapPropertySource("local-workflow-scope", overrides));
        sources.addLast(new org.springframework.core.env.PropertySource<Environment>("original-read-only", original) {
            @Override @Nullable public Object getProperty(@NonNull String name) { return source.getProperty(name); }
        });
        return isolated;
    }
    static boolean fourteenContextKnowledge(Environment env) {
        String prefix = "variant-analysis.acmg-business.";
        for (String flag : List.of("unified-local-workflow-enabled", "local-batch-workflow-enabled",
                "local-batch-bp7-enabled", "local-batch-pvs1-enabled", "local-batch-pm4-enabled", "local-batch-bp3-enabled",
                "local-batch-pm1-enabled", "local-batch-pp2-enabled", "local-batch-bp1-enabled"))
            if (!env.getProperty(prefix + flag, Boolean.class, false)) return false;
        return true;
    }
    @Bean
    public AcmgBusinessService acmgBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs,
            AcmgBusinessStore store, AcmgExecutionSources sources, VariantAnalysisProperties properties, Environment env) {
        return buildBusinessService(mapper, jobs, store, sources, properties, env);
    }

    private AcmgBusinessService buildBusinessService(ObjectMapper mapper, VariantAnalysisJobRepository jobs,
            AcmgBusinessStore store, AcmgExecutionSources sources, VariantAnalysisProperties properties, Environment env) {
        if (AcmgBusinessRuleScope.PP4_DEVELOPMENT.equals(env.getProperty("variant-analysis.acmg-business.acceptance-scope", ""))) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("PP4_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("PP4_EXECUTION_MODE_CONFLICT");
            var base = new AcmgBusinessService(mapper, jobs, store, sources,
                    () -> { throw new IllegalStateException("PP4_ORDINARY_COORDINATOR_FORBIDDEN"); });
            return AcmgBusinessService.pp4Development(base, new Pp4PhenotypeContextPreparation(mapper,
                    Path.of(env.getProperty("variant-analysis.acmg-business.pp4-bundle-file", "")),
                    env.getProperty("variant-analysis.acmg-business.pp4-bundle-file-sha256", ""),
                    env.getProperty("variant-analysis.acmg-business.pp4-bundle-sha256", "")));
        }
        if (AcmgBusinessRuleScope.BS4_DEVELOPMENT.equals(env.getProperty("variant-analysis.acmg-business.acceptance-scope", ""))) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("BS4_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("BS4_EXECUTION_MODE_CONFLICT");
            var base = new AcmgBusinessService(mapper, jobs, store, sources,
                    () -> { throw new IllegalStateException("BS4_ORDINARY_COORDINATOR_FORBIDDEN"); });
            return AcmgBusinessService.bs4Development(base, new Bs4NonSegregationContextPreparation(mapper,
                    Path.of(env.getProperty("variant-analysis.acmg-business.bs4-bundle-file", "")),
                    env.getProperty("variant-analysis.acmg-business.bs4-bundle-file-sha256", ""),
                    env.getProperty("variant-analysis.acmg-business.bs4-bundle-sha256", "")));
        }
        if (AcmgBusinessRuleScope.PP1_DEVELOPMENT.equals(env.getProperty("variant-analysis.acmg-business.acceptance-scope", ""))) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("PP1_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("PP1_EXECUTION_MODE_CONFLICT");
            var base = new AcmgBusinessService(mapper, jobs, store, sources,
                    () -> { throw new IllegalStateException("PP1_ORDINARY_COORDINATOR_FORBIDDEN"); });
            return AcmgBusinessService.pp1Development(base, new Pp1SegregationContextPreparation(mapper,
                    Path.of(env.getProperty("variant-analysis.acmg-business.pp1-bundle-file", "")),
                    env.getProperty("variant-analysis.acmg-business.pp1-bundle-file-sha256", ""),
                    env.getProperty("variant-analysis.acmg-business.pp1-bundle-sha256", "")));
        }
        if (AcmgBusinessRuleScope.BP2_DEVELOPMENT.equals(env.getProperty("variant-analysis.acmg-business.acceptance-scope", ""))) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("BP2_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("BP2_EXECUTION_MODE_CONFLICT");
            var base = new AcmgBusinessService(mapper, jobs, store, sources,
                    () -> { throw new IllegalStateException("BP2_ORDINARY_COORDINATOR_FORBIDDEN"); });
            return AcmgBusinessService.bp2Development(base, new Bp2PhaseContextPreparation(mapper,
                    Path.of(env.getProperty("variant-analysis.acmg-business.bp2-bundle-file", "")),
                    env.getProperty("variant-analysis.acmg-business.bp2-bundle-file-sha256", ""),
                    env.getProperty("variant-analysis.acmg-business.bp2-bundle-sha256", "")));
        }
        if (AcmgBusinessRuleScope.PM3_DEVELOPMENT.equals(env.getProperty("variant-analysis.acmg-business.acceptance-scope", ""))) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("PM3_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("PM3_EXECUTION_MODE_CONFLICT");
            var base = new AcmgBusinessService(mapper, jobs, store, sources,
                    () -> { throw new IllegalStateException("PM3_ORDINARY_COORDINATOR_FORBIDDEN"); });
            return AcmgBusinessService.pm3Development(base, new Pm3InTransContextPreparation(mapper,
                    Path.of(env.getProperty("variant-analysis.acmg-business.pm3-bundle-file", "")),
                    env.getProperty("variant-analysis.acmg-business.pm3-bundle-file-sha256", ""),
                    env.getProperty("variant-analysis.acmg-business.pm3-bundle-sha256", "")));
        }
        if (AcmgBusinessRuleScope.PM6_DEVELOPMENT.equals(env.getProperty("variant-analysis.acmg-business.acceptance-scope", ""))) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("PM6_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("PM6_EXECUTION_MODE_CONFLICT");
            var base = new AcmgBusinessService(mapper, jobs, store, sources,
                    () -> { throw new IllegalStateException("PM6_ORDINARY_COORDINATOR_FORBIDDEN"); });
            return AcmgBusinessService.pm6Development(base, new Pm6DeNovoContextPreparation(mapper,
                    Path.of(env.getProperty("variant-analysis.acmg-business.pm6-bundle-file", "")),
                    env.getProperty("variant-analysis.acmg-business.pm6-bundle-file-sha256", ""),
                    env.getProperty("variant-analysis.acmg-business.pm6-bundle-sha256", "")));
        }
        if (AcmgBusinessRuleScope.PS2_DEVELOPMENT.equals(env.getProperty("variant-analysis.acmg-business.acceptance-scope", ""))) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("PS2_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("PS2_EXECUTION_MODE_CONFLICT");
            var base = new AcmgBusinessService(mapper, jobs, store, sources,
                    () -> { throw new IllegalStateException("PS2_ORDINARY_COORDINATOR_FORBIDDEN"); });
            return AcmgBusinessService.ps2Development(base, new Ps2DeNovoContextPreparation(mapper,
                    Path.of(env.getProperty("variant-analysis.acmg-business.ps2-bundle-file", "")),
                    env.getProperty("variant-analysis.acmg-business.ps2-bundle-file-sha256", ""),
                    env.getProperty("variant-analysis.acmg-business.ps2-bundle-sha256", "")));
        }
        // Bind one immutable, validated lookup snapshot to this service execution identity.
        // A resource update becomes effective on service restart and receives a new cache identity.
        boolean localBa1 = env.getProperty("variant-analysis.acmg-business.ba1-local-exomes-enabled", Boolean.class, false);
        if (!localBa1 && env.getProperty("variant-analysis.acmg-business.ba1-local-api-enabled", Boolean.class, false))
            throw new IllegalArgumentException("BA1_API_PATH_RETIRED_USE_LOCAL_EXOMES");
        var configuredCatalog = ba1SpecificationCatalog(mapper, env);
        var ba1Catalog = configuredCatalog == null ? Ba1SpecificationCatalog.unavailable(mapper, "BA1_CATALOG_NOT_CONFIGURED") : configuredCatalog;
        var ba1SourceIdentity = mapper.createObjectNode().put("version", "ba1-source-configuration-v1")
                .put("selectedNormalizationVersion", org.nbscn.varvis.variantanalysis.service.NativeSnvSourceProducer.SELECTED_NORMALIZATION_VERSION)
                .put("normalizationConfigurationSha256", new org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts(mapper)
                        .requestHash(mapper.createObjectNode()
                                .put("mode", String.valueOf(properties.getNormalization().getMode()))
                                .put("tool", properties.getNormalization().getBcftoolsExecutable())
                                .put("timeout", String.valueOf(properties.getNormalization().getTimeout()))
                                .put("reference38Profile", properties.getNormalization().profileIdFor(org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38))
                                .put("reference38Alias", String.valueOf(properties.getNormalization().contigAliasFileFor(org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38)))))
                .put("reference38Sha256", properties.getNormalization().referenceSha256For(org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38))
                .put("reference37Sha256", properties.getNormalization().referenceSha256For(org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh37))
                .put("reference38ReceiptSha256", env.getProperty("variant-analysis.acmg-business.reference-receipt-sha256", ""))
                .put("reference37ReceiptSha256", env.getProperty("variant-analysis.acmg-business.reference37-receipt-sha256", ""))
                .put("enabled", env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false)
                        && env.getProperty("variant-analysis.acmg-business.ba1-enabled", Boolean.class, true));
        if (!localBa1 && "jdk-range-tabix-v1".equals(env.getProperty("variant-analysis.acmg-business.gnomad-transport")))
            ba1SourceIdentity.set("transport", mapper.valueToTree(org.nbscn.varvis.variantanalysis.provider.BoundedRangeTabix.identity(
                    env.getRequiredProperty("variant-analysis.acmg-business.gnomad-range-bindings-file"))));
        var identitySettings = localBa1 ? localBa1Settings(properties) : properties;
        var ba1Identity = new Ba1AlleleRuntime(mapper, identitySettings, ba1Catalog, ba1SourceIdentity).identity();
        boolean localBs1 = env.getProperty("variant-analysis.acmg-business.bs1-local-exomes-enabled", Boolean.class, false);
        if (localBs1 && (!localBa1 || !env.getProperty("variant-analysis.acmg-business.acceptance-scope", "").isBlank())) throw new IllegalArgumentException("BS1_LOCAL_BA1_MODE_REQUIRED");
        String knowledgeFile = env.getProperty("variant-analysis.acmg-business.bs1-knowledge-file", "");
        String knowledgeSha = env.getProperty("variant-analysis.acmg-business.bs1-knowledge-sha256", "");
        boolean contextOnlyKnowledge = AcmgBusinessRuleScope.LOCAL_BATCH1.equals(env.getProperty("variant-analysis.acmg-business.acceptance-scope"))
                && fourteenContextKnowledge(env);
        if (knowledgeFile.isBlank() != knowledgeSha.isBlank())
            throw new IllegalArgumentException("BS1_KNOWLEDGE_CONFIGURATION_INVALID");
        final Bs1KnowledgeCatalog knowledge;
        try { knowledge = knowledgeFile.isBlank() ? null : Bs1KnowledgeCatalog.load(Path.of(knowledgeFile), knowledgeSha); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("BS1_KNOWLEDGE_UNAVAILABLE", invalid); }
        boolean routedPopulation = hasJointBs1Knowledge(knowledge);
        if (knowledge != null && !localBs1 && !contextOnlyKnowledge && !routedPopulation)
            throw new IllegalArgumentException("BS1_KNOWLEDGE_CONFIGURATION_INVALID");
        var service = new AcmgBusinessService(mapper, jobs, store, sources, () -> {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("ACMG_BUSINESS_EXECUTION_DISABLED");
            try {
                Path bindings = Path.of(env.getRequiredProperty("variant-analysis.acmg-business.binding-directory"));
                var resolver = PatientDiseaseContextBindingResolver.load(mapper, bindings.resolve("manifest.json"), bindings.resolve("mane-crosswalk.json"), bindings.resolve("clingen-catalog.json"));
                if (knowledge != null) resolver = resolver.withBs1Knowledge(mapper, knowledge);
                var checksum = ExplicitContextSourceLedger.load(mapper,
                        Path.of(env.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),
                        env.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
                var ledgers = List.of(checksum);
                ExplicitContextSourceLedger originalChecksum = null;
                Path originalRoot = null;
                ExplicitContextSourceLedger.SourceRef originalRef = null;
                String originalFile = env.getProperty("variant-analysis.acmg-business.reference37-receipt-file", "");
                if (!originalFile.isBlank()) try {
                    originalChecksum = ExplicitContextSourceLedger.load(mapper, Path.of(originalFile),
                            env.getRequiredProperty("variant-analysis.acmg-business.reference37-receipt-sha256"));
                    originalRoot = Path.of(env.getRequiredProperty("variant-analysis.acmg-business.reference37-resource-root"));
                    originalRef = originalChecksum.reference(env.getRequiredProperty("variant-analysis.acmg-business.reference37-receipt-locator"));
                } catch (Exception unavailable) {
                    originalChecksum = null; originalRef = null;
                }
                var producer = new NativeSnvSourceProducer(mapper, properties,
                        Path.of(env.getRequiredProperty("variant-analysis.acmg-business.resource-root")), ledgers,
                        checksum.reference(env.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-locator")),
                        originalRoot, originalChecksum == null ? List.of() : List.of(originalChecksum), originalRef);
                return new ExplicitCaseInputCoordinator(mapper, resolver, producer, ledgers)
                        .withQueryAnnotations(new org.nbscn.varvis.variantanalysis.provider.VepTranscriptAnnotationProvider(properties)::annotateBatch);
            } catch (Exception unavailable) { throw new IllegalArgumentException("ACMG_TRUSTED_RUNTIME_CONFIGURATION_UNAVAILABLE", unavailable); }
        }, () -> {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false)
                    || !env.getProperty("variant-analysis.acmg-business.ba1-enabled", Boolean.class, true))
                throw new IllegalArgumentException("BA1_BUSINESS_EXECUTION_DISABLED");
            try {
                var checksum = ExplicitContextSourceLedger.load(mapper,
                        Path.of(env.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-file")),
                        env.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-sha256"));
                ExplicitContextSourceLedger originalChecksum = null;
                Path originalRoot = null;
                ExplicitContextSourceLedger.SourceRef originalRef = null;
                String originalFile = env.getProperty("variant-analysis.acmg-business.reference37-receipt-file", "");
                if (!originalFile.isBlank()) try {
                    originalChecksum = ExplicitContextSourceLedger.load(mapper, Path.of(originalFile),
                            env.getRequiredProperty("variant-analysis.acmg-business.reference37-receipt-sha256"));
                    originalRoot = Path.of(env.getRequiredProperty("variant-analysis.acmg-business.reference37-resource-root"));
                    originalRef = originalChecksum.reference(env.getRequiredProperty("variant-analysis.acmg-business.reference37-receipt-locator"));
                } catch (Exception unavailable) {
                    // An unavailable original-assembly receipt does not disable the native GRCh38 path.
                    originalChecksum = null; originalRef = null;
                }
                var producer = new NativeSnvSourceProducer(mapper, properties,
                        Path.of(env.getRequiredProperty("variant-analysis.acmg-business.resource-root")), List.of(checksum),
                        checksum.reference(env.getRequiredProperty("variant-analysis.acmg-business.reference-receipt-locator")),
                        originalRoot, originalChecksum == null ? List.of() : List.of(originalChecksum), originalRef);
                // Explicit population business invocation; ranking provider settings remain unchanged.
                var profile = GnomadPopulationInputProfile.loadExecutableV2();
                var settings = localBa1 ? localBa1Settings(properties) : new VariantAnalysisProperties();
                settings.getGnomad().setEnabled(true); settings.getGnomad().setPopulationInputEnabled(true);
                settings.getGnomad().setVersion(profile.getDocument().path("resources").get(0).path("resourceVersion").asText());
                settings.getGnomad().setTabixExecutable(properties.getGnomad().getTabixExecutable());
                if (!localBa1) configurePopulationQuery(settings, env);
                settings.getGnomad().setAllowFullVcfFallback(false); settings.getGnomad().setAllowVcfInfoFallback(false);
                return new AcmgBusinessService.Ba1Runtime(producer, ba1Catalog == null
                        ? new Ba1AlleleRuntime(mapper, settings) : new Ba1AlleleRuntime(mapper, settings, ba1Catalog, ba1SourceIdentity));
            } catch (Exception unavailable) { throw new IllegalArgumentException("BA1_TRUSTED_RUNTIME_CONFIGURATION_UNAVAILABLE", unavailable); }
        }, AcmgRecommendations.current(mapper, ba1Catalog), () -> {
            if (localBa1) throw new IllegalArgumentException("PM2_DISABLED_LOCAL_EXOMES_BA1_SCOPE");
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false)
                    || !env.getProperty("variant-analysis.acmg-business.pm2-enabled", Boolean.class, true))
                throw new IllegalArgumentException("PM2_BUSINESS_EXECUTION_DISABLED");
            var settings = new VariantAnalysisProperties();
            settings.getGnomad().setEnabled(true); settings.getGnomad().setPopulationInputEnabled(true);
            var profile = GnomadPopulationInputProfile.loadBusinessPm2Supporting();
            settings.getGnomad().setVersion(profile.getDocument().path("resources").get(0).path("resourceVersion").asText());
            settings.getGnomad().setTabixExecutable(properties.getGnomad().getTabixExecutable());
            configurePopulationQuery(settings, env);
            settings.getGnomad().setAllowFullVcfFallback(false); settings.getGnomad().setAllowVcfInfoFallback(false);
            GnomadAllSitesHailBatchRunner runner = null;
            String allSitesConfigurationIssue = null;
            String commandFile = env.getProperty("variant-analysis.acmg-business.pm2-all-sites-command-file", "");
            if (!commandFile.isBlank()) {
                try {
                    Path commandPath = Path.of(commandFile);
                    if (java.nio.file.Files.size(commandPath) > 65536) throw new IllegalArgumentException("PM2_ALL_SITES_COMMAND_INVALID");
                    List<String> command = mapper.readValue(commandPath.toFile(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    Path working = businessWorkingRoot(properties, env).resolve("pm2-all-sites");
                    java.nio.file.Files.createDirectories(working);
                    runner = new GnomadAllSitesHailBatchRunner(mapper, command, working,
                            java.time.Duration.ofSeconds(env.getProperty("variant-analysis.acmg-business.pm2-all-sites-timeout-seconds", Integer.class, 1200)), 500);
                } catch (Exception unavailable) {
                    // Keep observed population evaluation available; no-record results explain the missing evidence.
                    runner = null;
                    allSitesConfigurationIssue = "PM2_ALL_SITES_CONFIGURATION_INVALID";
                }
            }
            return new Pm2SupportingRuntime(mapper, settings, runner, allSitesConfigurationIssue);
        }, () -> { throw new IllegalStateException("DISABLED_PVS1_RUNTIME"); },
                () -> { throw new IllegalStateException("DISABLED_MISSENSE_RUNTIME"); }, null,
                () -> { throw new IllegalStateException("DISABLED_FUNCTIONAL_RUNTIME"); },
                null, null, null, null, ba1Identity,
                () -> { throw new IllegalStateException("DISABLED_PM1_RUNTIME"); }, true);
        String scope = env.getProperty("variant-analysis.acmg-business.acceptance-scope", "");
        if (AcmgBusinessRuleScope.LOCAL_BATCH1.equals(scope)) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("LOCAL_BATCH_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("LOCAL_BATCH_EXECUTION_MODE_CONFLICT");
            var batch = new LocalEvidenceBatchConfiguration(mapper, properties, env, amPhaseBudget);
            return AcmgBusinessService.localBatchWithEffectIdentity(service, batch::create,
                    Path.of(env.getProperty("variant-analysis.acmg-business.effect-review-root",
                            java.util.Objects.requireNonNull(businessWorkingRoot(properties, env).resolve("functional-experiment-reviews").toString()))));
        }
        if (AcmgBusinessRuleScope.BP1_DEVELOPMENT.equals(scope)) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("BP1_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("BP1_EXECUTION_MODE_CONFLICT");
            return AcmgBusinessService.bp1Development(service, bp1Bundle(mapper, env));
        }
        if (AcmgBusinessRuleScope.PP2_DEVELOPMENT.equals(scope)) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("PP2_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("PP2_EXECUTION_MODE_CONFLICT");
            return AcmgBusinessService.pp2Development(service, pp2Bundle(mapper, env));
        }
        if (AcmgBusinessRuleScope.BP3_DEVELOPMENT.equals(scope)) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("BP3_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("BP3_EXECUTION_MODE_CONFLICT");
            return AcmgBusinessService.bp3Development(service, bp3Bundle(mapper, env));
        }
        if (AcmgBusinessRuleScope.PVS1_DEVELOPMENT.equals(scope)) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("PVS1_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("PVS1_EXECUTION_MODE_CONFLICT");
            return AcmgBusinessService.pvs1Development(service, pvs1Bundle(mapper, env, properties));
        }
        if (AcmgBusinessRuleScope.PM1_DEVELOPMENT.equals(scope)) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("PM1_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException("PM1_EXECUTION_MODE_CONFLICT");
            return AcmgBusinessService.pm1Development(service, pm1Bundle(mapper, env));
        }
        if (AcmgBusinessRuleScope.PS1_DEVELOPMENT.equals(scope) || AcmgBusinessRuleScope.PM5_DEVELOPMENT.equals(scope)) {
            boolean pm5 = AcmgBusinessRuleScope.PM5_DEVELOPMENT.equals(scope);
            String code = pm5 ? "PM5" : "PS1";
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException(code + "_BUSINESS_EXECUTION_DISABLED");
            for (String flag : List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only"))
                if (env.getProperty("variant-analysis.acmg-business." + flag, Boolean.class, false))
                    throw new IllegalArgumentException(code + "_EXECUTION_MODE_CONFLICT");
            var bundle = missenseBundle(mapper, properties, env, pm5);
            return pm5 ? AcmgBusinessService.pm5Development(service, bundle) : AcmgBusinessService.ps1Development(service, bundle);
        }
        if (Pm4CriterionEvaluator.SCOPE.equals(scope)) {
            if (!env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false))
                throw new IllegalArgumentException("PM4_BUSINESS_EXECUTION_DISABLED");
            if (env.getProperty("variant-analysis.acmg-business.revel-application-enabled", Boolean.class, false)
                    || env.getProperty("variant-analysis.acmg-business.saved-observation-calibration-only", Boolean.class, false)
                    || env.getProperty("variant-analysis.acmg-business.saved-observation-association-only", Boolean.class, false))
                throw new IllegalArgumentException("PM4_EXECUTION_MODE_CONFLICT");
            var bundle = reviewedRuleBundle(mapper, env);
            if (bundle == null || !bundle.supportsPm4()) throw new IllegalArgumentException("PM4_TRUSTED_BUNDLE_REQUIRED");
            return AcmgBusinessService.pm4Development(service, bundle);
        }
        if (env.getProperty("variant-analysis.acmg-business.revel-application-enabled", Boolean.class, false)) {
            if (!scope.isBlank() || env.getProperty("variant-analysis.acmg-business.saved-observation-calibration-only", Boolean.class, false)
                    || env.getProperty("variant-analysis.acmg-business.saved-observation-association-only", Boolean.class, false))
                throw new IllegalArgumentException("REVEL_APPLICATION_MODE_CONFLICT");
            var prediction = reviewedPrediction(mapper, properties, env);
            var configuredRuntime = prediction.runtime(); var router = prediction.router();
            if (configuredRuntime == null || router == null) throw new IllegalArgumentException("REVEL_APPLICATION_RUNTIME_UNAVAILABLE");
            try {
                return AcmgBusinessService.revelApplication(service, RevelApplicationBusiness.configured(mapper, configuredRuntime, router,
                        Path.of(env.getRequiredProperty("variant-analysis.acmg-business.revel-application-file")),
                        env.getRequiredProperty("variant-analysis.acmg-business.revel-application-file-sha256")),
                        businessWorkingRoot(properties, env).resolve("functional-experiment-reviews"));
            } catch (Exception invalid) { throw new IllegalArgumentException("REVEL_APPLICATION_CONFIGURATION_INVALID", invalid); }
        }
        boolean calibrationOnly = env.getProperty("variant-analysis.acmg-business.saved-observation-calibration-only", Boolean.class, false);
        boolean associationOnly = env.getProperty("variant-analysis.acmg-business.saved-observation-association-only", Boolean.class, false);
        if (calibrationOnly && (associationOnly || !"PP3_BP4_DEVELOPMENT".equals(scope)))
            throw new IllegalArgumentException("SAVED_CALIBRATION_MODE_CONFLICT");
        if (calibrationOnly) {
            requireCalibrationIsolation(properties);
            return AcmgBusinessService.savedPredictionCalibration(service,
                org.nbscn.varvis.variantanalysis.service.SavedCalibrationBusiness.configured(mapper,
                        Path.of(env.getProperty("variant-analysis.acmg-business.saved-calibration-config", "")),
                        env.getProperty("variant-analysis.acmg-business.saved-calibration-config-sha256", "")));
        }
        if ("PP3_BP4_DEVELOPMENT".equals(scope)) {
            String manifest = env.getProperty("variant-analysis.acmg-business.prediction-local-binding-file", "");
            var binding = manifest.isBlank() ? null : PredictionLocalResourceBinding.load(mapper, Path.of(manifest),
                    env.getProperty("variant-analysis.acmg-business.prediction-local-binding-sha256", ""));
            if (env.getProperty("variant-analysis.acmg-business.saved-observation-association-only", Boolean.class, false))
                return AcmgBusinessService.savedPredictionAssociation(service,
                        () -> ReviewedPredictionBusinessRuntime.development(mapper, new AlphaMissenseProvider(properties, binding, null)));
            return AcmgBusinessService.predictionDevelopment(service,
                    () -> ReviewedPredictionBusinessRuntime.development(mapper, new AlphaMissenseProvider(properties, binding, amPhaseBudget)),
                    businessWorkingRoot(properties, env).resolve("functional-experiment-reviews"));
        }
        if (!scope.isBlank() && !"BA1_PM2_BS1_ACCEPTANCE".equals(scope)) throw new IllegalArgumentException("ACMG_ACCEPTANCE_SCOPE_INVALID");
        java.util.function.Supplier<Bs1Runtime> bs1 = () -> {
            if (routedPopulation) {
                var joint = jointPopulationSettings(properties, env);
                Path working = businessWorkingRoot(properties, env).resolve("bs1-native");
                var exomes = localBa1Settings(properties);
                if (!localBs1) {
                    exomes.getGnomad().setEnabled(properties.getGnomad().isEnabled());
                    exomes.getGnomad().setPopulationInputEnabled(properties.getGnomad().isPopulationInputEnabled());
                }
                return Bs1Runtime.routed(mapper, localBs1, exomes, joint,
                        env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false) && (localBs1 || !localBa1),
                        java.util.Objects.requireNonNull(knowledge), new Bs1NativeFilterSupplement(mapper, joint, working));
            }
            if (localBs1) return Bs1Runtime.localExomes(mapper, localBa1Settings(properties),
                    env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false), knowledge);
            var settings = new VariantAnalysisProperties(); var gnomad = settings.getGnomad();
            gnomad.setEnabled(true); gnomad.setPopulationInputEnabled(true); gnomad.setVersion("gnomAD joint v4.1 GRCh38");
            gnomad.setTabixExecutable(properties.getGnomad().getTabixExecutable());
            if (!localBa1) configurePopulationQuery(settings, env);
            gnomad.setAllowFullVcfFallback(false); gnomad.setAllowVcfInfoFallback(false);
            Path working = businessWorkingRoot(properties, env).resolve("bs1-native");
            if (!scope.isBlank() && localBa1) throw new IllegalArgumentException("BA1_LOCAL_SCOPE_CONFLICT");
            if (!scope.isBlank()) return isolatedBs1Runtime(mapper, settings, working);
            return Bs1Runtime.configured(mapper, Bs1Runtime.formalPolicies(mapper), settings,
                    new Bs1NativeFilterSupplement(mapper, settings, working))
                    .withFormalActivation(!localBa1 && env.getProperty("variant-analysis.acmg-business.enabled", Boolean.class, false));
        };
        var configured = scope.isBlank() ? AcmgBusinessService.formalPopulation(service, bs1)
                : AcmgBusinessService.bs1Acceptance(service, bs1);
        return configured;
    }

    private static boolean hasJointBs1Knowledge(@org.springframework.lang.Nullable Bs1KnowledgeCatalog knowledge) {
        if (knowledge == null) return false;
        return knowledge.merge(List.of()).stream().anyMatch(p -> "JOINT".equals(p.at("/scope/sexChromosome/requiredDataset").asText()));
    }

    static VariantAnalysisProperties jointPopulationSettings(VariantAnalysisProperties properties, Environment env) {
        var settings = new VariantAnalysisProperties(); var target = settings.getGnomad(); var original = properties.getGnomad();
        // Dataset selection is server policy; availability still follows the effective source switches.
        target.setEnabled(original.isEnabled()); target.setPopulationInputEnabled(original.isPopulationInputEnabled());
        target.setVersion("gnomAD joint v4.1 GRCh38"); target.setTabixExecutable(original.getTabixExecutable());
        target.setAllowFullVcfFallback(false); target.setAllowVcfInfoFallback(false);
        configurePopulationQuery(settings, env); return settings;
    }

    static VariantAnalysisProperties localBa1Settings(VariantAnalysisProperties properties) {
        var settings = new VariantAnalysisProperties();
        var target = settings.getGnomad(); var original = properties.getGnomad();
        target.setBa1LocalExomesEnabled(true); target.setEnabled(true); target.setPopulationInputEnabled(true);
        target.setVersion("gnomAD exomes v4.1 GRCh38"); target.setTabixExecutable(original.getTabixExecutable());
        target.setTimeout(original.getTimeout());
        target.setLocalPopulationInProcessEnabled(original.isLocalPopulationInProcessEnabled());
        target.setGrch38Files(original.getGrch38Files().stream().map(mappedItem -> mappedItem.toString()).collect(java.util.stream.Collectors.joining(",")));
        target.setAllowFullVcfFallback(false); target.setAllowVcfInfoFallback(false);
        return settings;
    }

    static void requireCalibrationIsolation(VariantAnalysisProperties properties) {
        if (properties.getAlphaMissense().isEnabled() || properties.getAlphaGenome().isEnabled()
                || properties.getCadd().isEnabled() || properties.getClinVar().isEnabled()
                || properties.getGnomad().isEnabled() || properties.getSpliceAi().isEnabled()
                || properties.getVep().isEnabled() || properties.getVep().isExecutionEnabled()
                || properties.getExomiser().isEnabled() || properties.getLlmShadow().isEnabled()
                || properties.getPhenotypePrior().isEnabled())
            throw new IllegalArgumentException("CALIBRATION_ONLY_REQUIRES_ACQUISITION_DISABLED");
    }
    /** One immutable BA1 catalog per service. Invalid configured catalogs never restore legacy selection. */
    @org.springframework.lang.Nullable
    static Ba1SpecificationCatalog ba1SpecificationCatalog(ObjectMapper mapper, Environment env) {
        String prefix = "variant-analysis.acmg-business.";
        try {
            if (!env.getProperty(prefix + "ba1-generalization-enabled", Boolean.class, true)) return null;
            String file = env.getProperty(prefix + "ba1-specification-catalog-file");
            String sha = env.getProperty(prefix + "ba1-specification-catalog-sha256");
            var transcriptPairs = ba1ManeTranscriptPairs(env.getProperty(prefix + "binding-directory", ""));
            if ((file == null || file.isBlank()) && (sha == null || sha.isBlank()))
                return Ba1SpecificationCatalog.bundled(mapper, transcriptPairs);
            if (file == null || file.isBlank() || sha == null || !sha.matches("[0-9a-f]{64}"))
                return Ba1SpecificationCatalog.unavailable(mapper, "BA1_CATALOG_CONFIGURATION_INVALID");
            return Ba1SpecificationCatalog.load(mapper, Path.of(file), sha, transcriptPairs);
        } catch (Exception invalid) {
            return Ba1SpecificationCatalog.unavailable(mapper, "BA1_CATALOG_CONFIGURATION_INVALID");
        }
    }

    @Nullable
    private static Ba1ManeTranscriptPairs ba1ManeTranscriptPairs(String bindingDirectory) {
        if (bindingDirectory.isBlank()) return null;
        try {
            return Ba1ManeTranscriptPairs.load(Path.of(bindingDirectory));
        } catch (java.io.IOException | IllegalArgumentException unavailable) {
            org.slf4j.LoggerFactory.getLogger(AcmgBusinessConfig.class)
                    .warn("BA1 MANE 1.5 transcript pairs unavailable. Scope checks require a literal transcript match.");
            return null;
        }
    }

    record PredictionSetup(@org.springframework.lang.Nullable ReviewedPredictionBusinessRuntime runtime,
                           @org.springframework.lang.Nullable ReviewedPredictionObservationRouter router) { }

    /** Opt-in, restart-bound snapshot. Loading failures remain an explicit unavailable configuration. */
    static PredictionSetup reviewedPrediction(ObjectMapper mapper, VariantAnalysisProperties properties, Environment env) {
        String prefix = "variant-analysis.acmg-business.";
        if (!env.getProperty(prefix + "reviewed-prediction-policy-enabled", Boolean.class, false)) return new PredictionSetup(null, null);
        String rawSha = env.getProperty(prefix + "reviewed-prediction-policy-file-sha256", "");
        String canonicalSha = env.getProperty(prefix + "reviewed-prediction-policy-sha256", "");
        try {
            var bundle = ReviewedPredictionBundle.load(mapper, Path.of(env.getRequiredProperty(prefix + "reviewed-prediction-policy-file")), rawSha, canonicalSha);
            boolean enabled = env.getProperty(prefix + "enabled", Boolean.class, false);
            var settings = new VariantAnalysisProperties();
            settings.getAlphaMissense().setEnabled(enabled && properties.getAlphaMissense().isEnabled()
                    && env.getProperty(prefix + "alphamissense-enabled", Boolean.class, true));
            settings.getAlphaMissense().setVersion(properties.getAlphaMissense().getVersion());
            var amFile = properties.getAlphaMissense().getGrch38File();
            settings.getAlphaMissense().setGrch38File(amFile == null ? null : amFile.toString());
            settings.getAlphaMissense().setTimeout(properties.getAlphaMissense().getTimeout());
            boolean spliceEnabled = enabled && env.getProperty(prefix + "spliceai-enabled", Boolean.class, true)
                    && (!env.containsProperty("variant-analysis.splice-ai.enabled") || properties.getSpliceAi().isEnabled());
            boolean revelEnabled = enabled && env.getProperty(prefix + "revel-enabled", Boolean.class, true);
            RevelPublisherIndexObservationProvider publisher = null;
            if (revelEnabled) {
                try {
                    String transport = env.getProperty(prefix + "revel-publisher-transport", "EXTERNAL_TABIX");
                    if ("LOCAL_HTSJDK".equals(transport)) {
                        publisher = RevelPublisherIndexObservationProvider.loadLocal(mapper,
                                Path.of(env.getRequiredProperty(prefix + "revel-publisher-index-directory")),
                                Path.of(env.getRequiredProperty(prefix + "revel-publisher-review-file")),
                                env.getRequiredProperty(prefix + "revel-publisher-review-file-sha256"),
                                env.getRequiredProperty(prefix + "revel-publisher-review-sha256"));
                    } else if ("EXTERNAL_TABIX".equals(transport)) publisher = RevelPublisherIndexObservationProvider.load(mapper,
                            Path.of(env.getRequiredProperty(prefix + "revel-publisher-index-directory")),
                            Path.of(env.getRequiredProperty(prefix + "revel-publisher-tabix-executable")),
                            Path.of(env.getRequiredProperty(prefix + "revel-publisher-review-file")),
                            env.getRequiredProperty(prefix + "revel-publisher-review-file-sha256"),
                            env.getRequiredProperty(prefix + "revel-publisher-review-sha256"));
                } catch (Exception unavailable) { /* REVEL alone stays unavailable; a selected AM policy is independent. */ }
            }
            String commandJson = env.getProperty(prefix + "spliceai-raw-command-json", "");
            String fasta = env.getProperty(prefix + "spliceai-reference-fasta", "");
            int timeout = env.getProperty(prefix + "spliceai-timeout-seconds", Integer.class, 600);
            Path work = businessWorkingRoot(properties, env).resolve("acmg-reviewed-prediction-spliceai-raw");
            java.util.function.Supplier<SpliceAiBusinessRuntime> raw = () -> {
                if (!spliceEnabled) throw new IllegalArgumentException("PREDICTION_SPLICEAI_DISABLED");
                try {
                    List<String> command = mapper.readValue(commandJson, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                    ClinVarMissenseResourceReadiness.verifyRawCommand(mapper, command, Path.of(fasta), properties);
                    java.nio.file.Files.createDirectories(work);
                    return new SpliceAiBusinessRuntime(mapper, new SpliceAiRawBatchRunner(mapper, command, Path.of(fasta), work,
                            java.time.Duration.ofSeconds(timeout), 100));
                } catch (Exception unavailable) { throw new IllegalArgumentException("PREDICTION_SPLICEAI_CONFIGURATION_UNAVAILABLE", unavailable); }
            };
            var router = new ReviewedPredictionObservationRouter(mapper, new AlphaMissenseProvider(settings), settings.getAlphaMissense().isEnabled(), raw, spliceEnabled, publisher, revelEnabled);
            var binding = router.identity();
            // Hash local configuration for cache separation without exporting command arguments or paths.
            var configuration = mapper.createObjectNode().put("command", commandJson).put("fasta", fasta).put("timeoutSeconds", timeout)
                    .put("alphaFile", amFile == null ? null : amFile.toString());
            binding.put("configurationSha256", new AcmgBusinessArtifacts(mapper).requestHash(configuration));
            return new PredictionSetup(new ReviewedPredictionBusinessRuntime(mapper, bundle, binding), router);
        } catch (Exception unavailable) {
            return new PredictionSetup(ReviewedPredictionBusinessRuntime.unavailable(mapper, rawSha, canonicalSha), null);
        }
    }

    @org.springframework.lang.Nullable
    static ReviewedRuleContextPreparation reviewedRuleContexts(ObjectMapper mapper,
            @org.springframework.lang.Nullable ReviewedRuleBundle bundle) {
        if (bundle == null || !"CONFIGURED".equals(bundle.identity().path("status").asText())) return null;
        return new ReviewedRuleContextPreparation(mapper, bundle.identity());
    }

    /** Loading the trusted multi-context catalog never queries a scientific resource. */
    static ReviewedRuleBundle bp1Bundle(ObjectMapper mapper, Environment env) {
        String prefix = "variant-analysis.acmg-business.";
        try {
            return ReviewedRuleBundle.loadBp1(mapper, Path.of(env.getRequiredProperty(prefix + "bp1-bundle-file")),
                    env.getRequiredProperty(prefix + "bp1-bundle-file-sha256"), env.getRequiredProperty(prefix + "bp1-bundle-sha256"));
        } catch (Exception unavailable) { throw new IllegalArgumentException("BP1_TRUSTED_BUNDLE_REQUIRED", unavailable); }
    }

    static ReviewedRuleBundle pp2Bundle(ObjectMapper mapper, Environment env) {
        String prefix = "variant-analysis.acmg-business.";
        try {
            return ReviewedRuleBundle.loadPp2(mapper, Path.of(env.getRequiredProperty(prefix + "pp2-bundle-file")),
                    env.getRequiredProperty(prefix + "pp2-bundle-file-sha256"), env.getRequiredProperty(prefix + "pp2-bundle-sha256"));
        } catch (Exception unavailable) { throw new IllegalArgumentException("PP2_TRUSTED_BUNDLE_REQUIRED", unavailable); }
    }

    static ReviewedRuleBundle bp3Bundle(ObjectMapper mapper, Environment env) {
        String prefix = "variant-analysis.acmg-business.";
        try {
            return ReviewedRuleBundle.loadBp3(mapper, Path.of(env.getRequiredProperty(prefix + "bp3-bundle-file")),
                    env.getRequiredProperty(prefix + "bp3-bundle-file-sha256"), env.getRequiredProperty(prefix + "bp3-bundle-sha256"));
        } catch (Exception unavailable) { throw new IllegalArgumentException("BP3_TRUSTED_BUNDLE_REQUIRED", unavailable); }
    }

    static ReviewedRuleBundle pvs1Bundle(ObjectMapper mapper, Environment env, VariantAnalysisProperties properties) {
        String prefix = "variant-analysis.acmg-business.";
        try {
            if (env.getProperty(prefix + "pvs1-selected-knowledge-enabled", Boolean.class, false)) {
                if (!env.getProperty(prefix + "pvs1-local-material-supply-enabled", Boolean.class, false))
                    throw new IllegalArgumentException("PVS1_SELECTED_KNOWLEDGE_MATERIAL_SUPPLY_REQUIRED");
                var supply = new LocalEvidenceBatchConfiguration(mapper, properties, env, null).transcriptMaterials();
                if (supply == null) throw new IllegalArgumentException("PVS1_LOCAL_TRANSCRIPT_MATERIAL_REQUIRED");
                var knowledge = pvs1Knowledge(mapper, env);
                return ReviewedRuleBundle.loadPvs1Selected(mapper, Path.of(env.getRequiredProperty(prefix + "pvs1-bundle-file")),
                        env.getRequiredProperty(prefix + "pvs1-bundle-file-sha256"), env.getRequiredProperty(prefix + "pvs1-bundle-sha256"),
                        new org.nbscn.varvis.variantanalysis.provider.Pvs1TranscriptMaterialSupply(mapper, supply), knowledge);
            }
            if (env.getProperty(prefix + "pvs1-local-material-supply-enabled", Boolean.class, false)) {
                var supply = new LocalEvidenceBatchConfiguration(mapper, properties, env, null).transcriptMaterials();
                if (supply == null) throw new IllegalArgumentException("PVS1_LOCAL_TRANSCRIPT_MATERIAL_REQUIRED");
                return ReviewedRuleBundle.loadPvs1(mapper, Path.of(env.getRequiredProperty(prefix + "pvs1-bundle-file")),
                        env.getRequiredProperty(prefix + "pvs1-bundle-file-sha256"), env.getRequiredProperty(prefix + "pvs1-bundle-sha256"),
                        new org.nbscn.varvis.variantanalysis.provider.Pvs1TranscriptMaterialSupply(mapper, supply));
            }
            return ReviewedRuleBundle.loadPvs1(mapper, Path.of(env.getRequiredProperty(prefix + "pvs1-bundle-file")),
                    env.getRequiredProperty(prefix + "pvs1-bundle-file-sha256"), env.getRequiredProperty(prefix + "pvs1-bundle-sha256"));
        } catch (Exception unavailable) { throw new IllegalArgumentException("PVS1_TRUSTED_BUNDLE_REQUIRED", unavailable); }
    }

    static org.nbscn.varvis.variantanalysis.provider.Pvs1KnowledgeCatalog pvs1Knowledge(ObjectMapper mapper, Environment env) throws java.io.IOException {
        String prefix = "variant-analysis.acmg-business.";
        var facts = Path.of(env.getRequiredProperty(prefix + "pvs1-knowledge-source-file"));
        var factsSha = env.getRequiredProperty(prefix + "pvs1-knowledge-source-sha256");
        var adoption = Path.of(env.getRequiredProperty(prefix + "pvs1-knowledge-adoption-file"));
        var adoptionSha = env.getRequiredProperty(prefix + "pvs1-knowledge-adoption-sha256");
        String sources = env.getProperty(prefix + "pvs1-applicability-source-file");
        String sourcesSha = env.getProperty(prefix + "pvs1-applicability-source-sha256");
        if (sources == null && sourcesSha == null)
            return org.nbscn.varvis.variantanalysis.provider.Pvs1KnowledgeCatalog.load(mapper, facts, factsSha, adoption, adoptionSha);
        if (sources == null || sources.isBlank() || sourcesSha == null || sourcesSha.isBlank())
            throw new IllegalArgumentException("PVS1_APPLICABILITY_SOURCE_BINDING_REQUIRED");
        return org.nbscn.varvis.variantanalysis.provider.Pvs1KnowledgeCatalog.load(mapper, facts, factsSha, adoption, adoptionSha, Path.of(sources), sourcesSha);
    }

    static ReviewedRuleBundle pm1Bundle(ObjectMapper mapper, Environment env) {
        String prefix = "variant-analysis.acmg-business.";
        try {
            var bundle = ReviewedRuleBundle.load(mapper, Path.of(env.getRequiredProperty(prefix + "pm1-bundle-file")),
                    env.getRequiredProperty(prefix + "pm1-bundle-sha256"));
            if (!bundle.supportsPm1()) throw new IllegalArgumentException("PM1_TRUSTED_BUNDLE_REQUIRED");
            return bundle;
        } catch (Exception unavailable) { throw new IllegalArgumentException("PM1_TRUSTED_BUNDLE_REQUIRED", unavailable); }
    }

    /** Loading the trusted multi-context catalog never queries a scientific resource. */
    static ReviewedRuleBundle missenseBundle(ObjectMapper mapper, VariantAnalysisProperties properties, Environment env, boolean pm5) {
        String code = pm5 ? "PM5" : "PS1";
        String prefix = "variant-analysis.acmg-business.";
        if (!env.getProperty(prefix + "reviewed-rule-policy-enabled", Boolean.class, false))
            throw new IllegalArgumentException(code + "_TRUSTED_BUNDLE_REQUIRED");
        try {
            var binding = mapper.createObjectNode().put("schemaVersion", pm5 ? "pm5-missense-runtime-binding-v1" : "ps1-missense-runtime-binding-v1");
            binding.set("resourceVerificationPolicy", ClinVarMissenseResourceReadiness.identityPolicy(mapper));
            var pathModule = new com.fasterxml.jackson.databind.module.SimpleModule("Ps1PathIdentity")
                    .addSerializer(Path.class, new com.fasterxml.jackson.databind.ser.std.ToStringSerializer());
            var identityMapper = mapper.copy().registerModule(pathModule)
                    .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
            binding.set("clinvar", identityMapper.valueToTree(properties.getClinVar()));
            binding.set("vep", identityMapper.valueToTree(properties.getVep()));
            for (String key : List.of("clinvar-sidecar-file", "clinvar-sidecar-manifest", "spliceai-raw-command-json",
                    "spliceai-reference-fasta", "spliceai-timeout-seconds", "working-root")) binding.put(key, env.getProperty(prefix + key));
            var bundle = ReviewedRuleBundle.load(mapper, Path.of(env.getRequiredProperty(prefix + "reviewed-rule-bundle-file")),
                    env.getRequiredProperty(prefix + "reviewed-rule-bundle-sha256"), () -> {
                        try {
                            Path sidecar = Path.of(env.getRequiredProperty(prefix + "clinvar-sidecar-file"));
                            Path manifest = Path.of(env.getRequiredProperty(prefix + "clinvar-sidecar-manifest"));
                            ClinVarMissenseResourceReadiness.verify(mapper, properties, sidecar, manifest);
                            var profile = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper);
                            var source = profile.path("resources").get(2);
                            var input = new ClinVarConditionAwareComparatorInput(new ClinVarTabixProvider(properties),
                                    new VepTranscriptAnnotationProvider(properties), new ClinVarComparatorSidecar(mapper, sidecar,
                                    source.path("resourceId").asText(), source.path("resourceVersion").asText(),
                                    source.path("identityFingerprintSha256").asText()), new VepProteinRelationClassifier());
                            List<String> command = mapper.readValue(env.getRequiredProperty(prefix + "spliceai-raw-command-json"),
                                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                            Path fasta = Path.of(env.getRequiredProperty(prefix + "spliceai-reference-fasta"));
                            ClinVarMissenseResourceReadiness.verifyRawCommand(mapper, command, fasta, properties);
                            Path work = businessWorkingRoot(properties, env).resolve(pm5 ? "pm5-missense-raw" : "ps1-missense-raw");
                            java.nio.file.Files.createDirectories(work);
                            var runner = new SpliceAiRawBatchRunner(mapper, command, fasta, work,
                                    java.time.Duration.ofSeconds(env.getProperty(prefix + "spliceai-timeout-seconds", Integer.class, 600)), 100);
                            String spliceSha = SpliceAiCriterionEvaluator.loadProfile(mapper).at("/resources/0/identityFingerprintSha256").asText();
                            return new MissenseComparisonRuntime(mapper, input, runner::run, spliceSha);
                        } catch (Exception unavailable) { throw new IllegalArgumentException(code + "_COMPARISON_RUNTIME_UNAVAILABLE", unavailable); }
                    }, binding);
            if (pm5 ? !bundle.supportsPm5() : !bundle.supportsPs1()) throw new IllegalArgumentException(code + "_TRUSTED_BUNDLE_REQUIRED");
            return bundle;
        } catch (Exception unavailable) { throw new IllegalArgumentException(code + "_TRUSTED_BUNDLE_REQUIRED", unavailable); }
    }

    @org.springframework.lang.Nullable
    static ReviewedRuleBundle reviewedRuleBundle(ObjectMapper mapper, Environment env) {
        if (!env.getProperty("variant-analysis.acmg-business.reviewed-rule-policy-enabled", Boolean.class, false)) return null;
        String expectedSha256 = env.getProperty("variant-analysis.acmg-business.reviewed-rule-bundle-sha256", "");
        try {
            return ReviewedRuleBundle.load(mapper,
                    Path.of(env.getRequiredProperty("variant-analysis.acmg-business.reviewed-rule-bundle-file")), expectedSha256);
        } catch (Exception invalid) {
            return ReviewedRuleBundle.unavailable(mapper, expectedSha256);
        }
    }

    @org.springframework.lang.Nullable
    static PopulationFrequencyBundle populationFrequencyBundle(ObjectMapper mapper, Environment env) {
        if (!env.getProperty("variant-analysis.acmg-business.population-policy-enabled", Boolean.class, false)) return null;
        String expectedSha256 = env.getProperty("variant-analysis.acmg-business.population-policy-bundle-sha256", "");
        try {
            return PopulationFrequencyBundle.load(mapper,
                    Path.of(env.getRequiredProperty("variant-analysis.acmg-business.population-policy-bundle-file")), expectedSha256);
        } catch (Exception invalid) {
            // A missing optional policy resource must not prevent the user's backend from starting.
            return PopulationFrequencyBundle.unavailable(mapper, expectedSha256);
        }
    }

    static Hnf1aRevelRuntime revelRuntime(ObjectMapper mapper, Environment env) {
        if (!env.getProperty("variant-analysis.acmg-business.revel-enabled", Boolean.class, true))
            return Hnf1aRevelRuntime.disabled(mapper);
        try {
            String manifest = env.getProperty("variant-analysis.acmg-business.revel-manifest-file");
            String root = env.getProperty("variant-analysis.acmg-business.resource-root");
            if (manifest == null && root != null)
                manifest = Path.of(root).resolve("Tool/revel/hnf1a/revel-hnf1a.manifest.json").toString();
            return manifest == null ? Hnf1aRevelRuntime.unavailable(mapper) : new Hnf1aRevelRuntime(mapper, Path.of(manifest));
        } catch (java.nio.file.InvalidPathException invalid) {
            return Hnf1aRevelRuntime.configurationUnavailable(mapper);
        }
    }

    static com.fasterxml.jackson.databind.node.ObjectNode missenseRuntimeBinding(ObjectMapper mapper, VariantAnalysisProperties properties, Environment env) {
        return missenseRuntimeBinding(mapper, properties, env, revelRuntime(mapper, env));
    }
    private static com.fasterxml.jackson.databind.node.ObjectNode missenseRuntimeBinding(ObjectMapper mapper, VariantAnalysisProperties properties,
            Environment env, Hnf1aRevelRuntime revel) {
        var binding = mapper.createObjectNode();
        binding.set("resourceVerificationPolicy", ClinVarMissenseResourceReadiness.identityPolicy(mapper));
        for (String suffix : List.of("enabled", "ps1-pm5-enabled", "pm2-enabled", "pvs1-enabled", "ps3-enabled", "ba1-enabled", "revel-enabled", "revel-manifest-file", "working-root",
                "gnomad-timeout-seconds", "gnomad-total-timeout-seconds", "gnomad-max-attempts",
                "pm2-all-sites-command-file", "pm2-all-sites-timeout-seconds", "spliceai-timeout-seconds", "resource-root", "binding-directory", "reference-receipt-file",
                "reference-receipt-sha256", "clinvar-sidecar-file", "clinvar-sidecar-manifest", "spliceai-raw-command-json", "spliceai-reference-fasta"))
            binding.put(suffix, env.getProperty("variant-analysis.acmg-business." + suffix));
        // Duration identity uses its exact ISO representation, not Jackson's decimal seconds.
        // Do not change the application's mapper or any effective timeout.
        var pathIdentity = new com.fasterxml.jackson.databind.module.SimpleModule("AcmgConfiguredPathIdentity")
                .addSerializer(Path.class, new com.fasterxml.jackson.databind.ser.std.ToStringSerializer());
        // Path.toUri() appends a slash once a directory exists; configured path identity must not depend on that lifecycle.
        var identityMapper = mapper.copy().registerModule(pathIdentity)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS);
        binding.set("clinvar", identityMapper.valueToTree(properties.getClinVar()));
        binding.set("vep", identityMapper.valueToTree(properties.getVep()));
        binding.set("gnomad", identityMapper.valueToTree(properties.getGnomad()));
        binding.put("revelResourceIdentitySha256", revel.resourceIdentitySha256());
        // Only the digest is published. Paths and configured transport stay private.
        var observations = binding.putArray("fileObservations");
        for (String suffix : List.of("reference-receipt-file", "clinvar-sidecar-file", "clinvar-sidecar-manifest", "spliceai-reference-fasta", "pm2-all-sites-command-file")) {
            String value = env.getProperty("variant-analysis.acmg-business." + suffix);
            // An unset optional file must not observe Path.of("") and bind the working directory's mtime.
            if (value == null || value.isBlank()) continue;
            var item = observations.addObject().put("role", suffix);
            try { var file = Path.of(value); item.put("bytes", java.nio.file.Files.size(file))
                    .put("modified", java.nio.file.Files.getLastModifiedTime(file).toString()); }
            catch (Exception unavailable) { item.put("status", "UNAVAILABLE"); }
        }
        return binding;
    }
    static Path businessWorkingRoot(VariantAnalysisProperties properties, Environment env) {
        String configured = env.getProperty("variant-analysis.acmg-business.working-root");
        return configured == null || configured.isBlank() ? properties.getStorageRoot() : Path.of(configured);
    }

    static void configurePopulationQuery(VariantAnalysisProperties settings, Environment env) {
        // Separate explicit business budget. Ranking and legacy provider configuration are unchanged.
        int seconds = env.getProperty("variant-analysis.acmg-business.gnomad-timeout-seconds", Integer.class, 60);
        int total = env.getProperty("variant-analysis.acmg-business.gnomad-total-timeout-seconds", Integer.class, 120);
        int attempts = env.getProperty("variant-analysis.acmg-business.gnomad-max-attempts", Integer.class, 2);
        if (seconds < 1 || seconds > 120 || total < seconds || total > 240)
            throw new IllegalArgumentException("ACMG_GNOMAD_QUERY_BUDGET_INVALID");
        settings.getGnomad().setTimeout(java.time.Duration.ofSeconds(seconds));
        settings.getGnomad().setRemoteTotalTimeout(java.time.Duration.ofSeconds(total));
        settings.getGnomad().setRemoteMaxAttempts(attempts);
        settings.getGnomad().setRemoteTransport(env.getProperty("variant-analysis.acmg-business.gnomad-transport", "TABIX"));
        settings.getGnomad().setRangeBindingsFile(env.getProperty("variant-analysis.acmg-business.gnomad-range-bindings-file"));
        settings.getGnomad().setRemoteIndexCacheDir(env.getProperty("variant-analysis.gnomad.remote-index-cache-dir"));
        settings.getGnomad().setBa1NativeTimeoutSeconds(
                env.getProperty("variant-analysis.gnomad.ba1-native-timeout-seconds", Integer.class));
        settings.getGnomad().setParallelism(1);
    }
}
