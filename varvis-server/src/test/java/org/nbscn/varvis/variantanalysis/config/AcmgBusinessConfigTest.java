package org.nbscn.varvis.variantanalysis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.lang.NonNull;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class AcmgBusinessConfigTest {
    @Test void knowledgePinsArePairedAndBecomePartOfExecutionIdentity(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var factory = new org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalogTest();
        byte[] raw = mapper.writeValueAsBytes(factory.bundle());
        var file = temporary.resolve("knowledge.json"); java.nio.file.Files.write(file, raw);
        String sha = org.nbscn.varvis.variantanalysis.provider.Bs1KnowledgeCatalog.sha(raw);
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.acmg-business.ba1-local-exomes-enabled", "true")
                .withProperty("variant-analysis.acmg-business.bs1-local-exomes-enabled", "true")
                .withProperty("variant-analysis.acmg-business.bs1-knowledge-file", java.util.Objects.requireNonNull(file.toString()));
        assertThrows(IllegalArgumentException.class, () -> new AcmgBusinessConfig().acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env));
        env.withProperty("variant-analysis.acmg-business.bs1-knowledge-sha256", java.util.Objects.requireNonNull(sha));
        var service = new AcmgBusinessConfig().acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env);
        var profile = (org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile)
                org.springframework.test.util.ReflectionTestUtils.getField(java.util.Objects.requireNonNull(service), "executionProfile");
        assertNotNull(profile);
        assertEquals(sha, profile.document().at("/bs1RuntimeBinding/knowledgeIdentity/rawSha256").asText());
        env.withProperty("variant-analysis.acmg-business.bs1-knowledge-sha256", java.util.Objects.requireNonNull("f".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new AcmgBusinessConfig().acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env));
    }
    @Test void retiredApiFlagCannotSilentlySelectRemoteJoint() {
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.acmg-business.ba1-local-api-enabled", "true");
        var error = assertThrows(IllegalArgumentException.class, () -> new AcmgBusinessConfig().acmgBusinessService(
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null, null, new VariantAnalysisProperties(), env));
        assertEquals("BA1_API_PATH_RETIRED_USE_LOCAL_EXOMES", error.getMessage());
    }
    @Test void localBa1AssemblyDoesNotReadRemoteConfiguration() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.acmg-business.ba1-local-exomes-enabled", "true")
                .withProperty("variant-analysis.acmg-business.ba1-local-api-enabled", "INVALID_IGNORED")
                .withProperty("variant-analysis.acmg-business.gnomad-transport", "jdk-range-tabix-v1")
                .withProperty("variant-analysis.acmg-business.gnomad-range-bindings-file", "MUST_NOT_READ");
        var service = new AcmgBusinessConfig().acmgBusinessService(mapper,null,null,null,new VariantAnalysisProperties(),env);
        var profile = (org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile)
                org.springframework.test.util.ReflectionTestUtils.getField(java.util.Objects.requireNonNull(service),"executionProfile");
        assertNotNull(profile);
        assertEquals("EXOMES",profile.document().at("/ba1RuntimeBinding/dataScope/dataset").asText());
        assertFalse(profile.document().at("/bs1RuntimeBinding/formalEnabled").asBoolean());
    }
    @Test void currentBusinessAssemblyDoesNotReadDisabledRuleConfiguration() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var env = new org.springframework.mock.env.MockEnvironment();
        for (String key : java.util.List.of("revel-enabled", "reviewed-prediction-policy-enabled", "population-policy-enabled", "reviewed-rule-policy-enabled"))
            env.withProperty("variant-analysis.acmg-business." + key, "INVALID_DISABLED_CONFIGURATION");
        var service = assertDoesNotThrow(() -> new AcmgBusinessConfig().acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env));
        var profile = (org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile)
                org.springframework.test.util.ReflectionTestUtils.getField(Objects.requireNonNull(service), "executionProfile");
        assertNotNull(profile);
        assertEquals(org.nbscn.varvis.variantanalysis.provider.AcmgBusinessRuleScope.CURRENT, profile.criteria());
    }
    @Test void explicitPopulationQueriesInheritTheLocalIndexCacheSetting() {
        var settings = new VariantAnalysisProperties();
        var cache = java.nio.file.Path.of("cache", "gnomad-joint-v4.1");
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.gnomad.remote-index-cache-dir", "cache/gnomad-joint-v4.1");
        AcmgBusinessConfig.configurePopulationQuery(settings, env);
        assertEquals(cache, settings.getGnomad().getRemoteIndexCacheDir());
    }

    @Test void ba1CatalogDefaultsToBundledSnapshotAndExplicitDisablePreservesLegacy() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var env = new org.springframework.mock.env.MockEnvironment();
        var bundled = Objects.requireNonNull(AcmgBusinessConfig.ba1SpecificationCatalog(mapper, env));
        assertEquals(org.nbscn.varvis.variantanalysis.provider.Ba1SpecificationCatalog.bundled(mapper).identity(), bundled.identity());
        env.withProperty("variant-analysis.acmg-business.ba1-specification-catalog-file", "")
                .withProperty("variant-analysis.acmg-business.ba1-specification-catalog-sha256", "");
        assertEquals(bundled.identity(), Objects.requireNonNull(AcmgBusinessConfig.ba1SpecificationCatalog(mapper, env)).identity());
        env.withProperty("variant-analysis.acmg-business.ba1-generalization-enabled", "false")
                .withProperty("variant-analysis.acmg-business.ba1-specification-catalog-file", "invalid" + (char) 0 + "path");
        assertNull(AcmgBusinessConfig.ba1SpecificationCatalog(mapper, env));
    }
    @Test void invalidBa1CatalogConfigurationRemainsUnavailableInsteadOfLegacyFallback() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var env : java.util.List.of(
                new org.springframework.mock.env.MockEnvironment().withProperty("variant-analysis.acmg-business.ba1-specification-catalog-file", "missing.json"),
                new org.springframework.mock.env.MockEnvironment().withProperty("variant-analysis.acmg-business.ba1-specification-catalog-sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                new org.springframework.mock.env.MockEnvironment().withProperty("variant-analysis.acmg-business.ba1-specification-catalog-file", "invalid" + (char) 0 + "path")
                        .withProperty("variant-analysis.acmg-business.ba1-specification-catalog-sha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                new org.springframework.mock.env.MockEnvironment().withProperty("variant-analysis.acmg-business.ba1-generalization-enabled", "not-a-boolean"))) {
            var configured = Objects.requireNonNull(assertDoesNotThrow(() -> AcmgBusinessConfig.ba1SpecificationCatalog(mapper, env)));
            assertEquals(org.nbscn.varvis.variantanalysis.provider.Ba1SpecificationCatalog
                    .unavailable(mapper, "BA1_CATALOG_CONFIGURATION_INVALID").identity(), configured.identity());
        }
    }
    @org.junit.jupiter.api.Test void predictionDefaultOffAndMissingPinsNeverCollapseToLegacyNull() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var properties = new VariantAnalysisProperties();
        var env = new org.springframework.mock.env.MockEnvironment();
        org.junit.jupiter.api.Assertions.assertNull(AcmgBusinessConfig.reviewedPrediction(mapper, properties, env).runtime());
        env.withProperty("variant-analysis.acmg-business.reviewed-prediction-policy-enabled", "true");
        var setup = AcmgBusinessConfig.reviewedPrediction(mapper, properties, env);
        var runtime = setup.runtime(); org.junit.jupiter.api.Assertions.assertNotNull(runtime);
        org.junit.jupiter.api.Assertions.assertEquals("UNAVAILABLE", runtime.identity().path("status").asText());
        org.junit.jupiter.api.Assertions.assertNull(setup.router());
        org.junit.jupiter.api.Assertions.assertTrue(runtime.contexts().accepts(runtime.contexts().selectionPolicy()));
        org.junit.jupiter.api.Assertions.assertFalse(runtime.identity().path("adoptionSupported").asBoolean(true));
    }
    @Test void optionalPopulationPolicyIsDisabledByDefaultAndInvalidConfigurationCannotStopBackend() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var env = new org.springframework.mock.env.MockEnvironment();
        assertNull(AcmgBusinessConfig.populationFrequencyBundle(mapper, env));
        env.withProperty("variant-analysis.acmg-business.population-policy-enabled", "true")
                .withProperty("variant-analysis.acmg-business.population-policy-bundle-file", "invalid" + (char) 0 + "path");
        var configured = AcmgBusinessConfig.populationFrequencyBundle(mapper, env);
        if (configured == null) throw new AssertionError("An explicitly enabled unavailable policy must retain its identity");
        assertEquals("UNAVAILABLE", configured.identity().path("status").asText());
        assertEquals("POPULATION_POLICY_CONFIGURATION_UNAVAILABLE", configured.identity().path("reasonCode").asText());
    }
    @Test void invalidRevelConfigurationCannotBreakMainServiceInitialization() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.acmg-business.revel-manifest-file", "invalid" + (char) 0 + "manifest");
        var runtime = assertDoesNotThrow(() -> AcmgBusinessConfig.revelRuntime(mapper, env));
        assertEquals("REVEL_CONFIGURATION_INVALID", runtime.providerInfo().path("reasonCode").asText());
        var unavailable = AcmgBusinessConfig.missenseRuntimeBinding(mapper, new VariantAnalysisProperties(), env);
        assertEquals(runtime.resourceIdentitySha256(), unavailable.path("revelResourceIdentitySha256").asText());
        env.withProperty("variant-analysis.acmg-business.revel-enabled", "false");
        var disabled = AcmgBusinessConfig.revelRuntime(mapper, env);
        assertEquals("DISABLED", disabled.providerInfo().path("resourceStatus").asText());
        assertNotEquals(runtime.resourceIdentitySha256(), disabled.resourceIdentitySha256());
    }

    @ParameterizedTest @ValueSource(strings = {"", " "})
    void unsetOptionalResourceFileDoesNotObserveWorkingDirectory(String value) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.acmg-business.pm2-all-sites-command-file", Objects.requireNonNull(value));
        var binding = AcmgBusinessConfig.missenseRuntimeBinding(mapper, new VariantAnalysisProperties(), env);
        assertEquals(value, binding.path("pm2-all-sites-command-file").asText());
        assertTrue(binding.path("fileObservations").isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "121", "1.5", "invalid"})
    void invalidNativeBudgetRejectedBeforeRuntime(@org.springframework.lang.NonNull String raw) {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.gnomad.ba1-native-timeout-seconds", raw);
        assertThrows(RuntimeException.class, () -> AcmgBusinessConfig.configurePopulationQuery(new VariantAnalysisProperties(), environment));
        assertThrows(RuntimeException.class, () -> org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("variant-analysis", org.springframework.boot.context.properties.bind.Bindable.of(VariantAnalysisProperties.class)));
    }

    @Test void nativeBudgetBindingIsSharedAndOptionalIdentityIsBackwardCompatible() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var environment = new org.springframework.mock.env.MockEnvironment();
        var settings = new VariantAnalysisProperties();
        var before = AcmgBusinessConfig.missenseRuntimeBinding(mapper, settings, environment);
        assertFalse(before.path("gnomad").has("ba1NativeTimeoutSeconds"));
        environment.withProperty("variant-analysis.gnomad.ba1-native-timeout-seconds", "120");
        var bound = org.springframework.boot.context.properties.bind.Binder.get(environment)
                .bind("variant-analysis", org.springframework.boot.context.properties.bind.Bindable.of(VariantAnalysisProperties.class)).get();
        var explicit = new VariantAnalysisProperties();
        AcmgBusinessConfig.configurePopulationQuery(explicit, environment);
        assertEquals(120, bound.getGnomad().getBa1NativeTimeoutSeconds());
        assertEquals(bound.getGnomad().getBa1NativeTimeoutSeconds(), explicit.getGnomad().getBa1NativeTimeoutSeconds());
        assertEquals(java.time.Duration.ofSeconds(60), explicit.getGnomad().getTimeout());
        var changed = AcmgBusinessConfig.missenseRuntimeBinding(mapper, bound, environment);
        assertNotEquals(before, changed);
        bound.getGnomad().setBa1NativeTimeoutSeconds(null);
        assertEquals(before, AcmgBusinessConfig.missenseRuntimeBinding(mapper, bound, environment));
    }

    @Test void populationBusinessBudgetIsExplicitAndDoesNotChangeRankingSettings() {
        var ranking = new VariantAnalysisProperties();
        ranking.getGnomad().setTimeout(java.time.Duration.ofMinutes(5));
        var ba1 = new VariantAnalysisProperties(); var pm2 = new VariantAnalysisProperties();
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.acmg-business.gnomad-timeout-seconds", "45")
                .withProperty("variant-analysis.acmg-business.gnomad-total-timeout-seconds", "80")
                .withProperty("variant-analysis.acmg-business.gnomad-max-attempts", "2");
        for (var settings : java.util.List.of(ba1, pm2)) {
            AcmgBusinessConfig.configurePopulationQuery(settings, env);
            assertEquals(java.time.Duration.ofSeconds(45), settings.getGnomad().getTimeout());
            assertEquals(java.time.Duration.ofSeconds(80), settings.getGnomad().getRemoteTotalTimeout());
            assertEquals(2, settings.getGnomad().getRemoteMaxAttempts());
            assertEquals(1, settings.getGnomad().getParallelism());
        }
        assertEquals(java.time.Duration.ofMinutes(5), ranking.getGnomad().getTimeout());
        assertEquals(1, ranking.getGnomad().getRemoteMaxAttempts());
        AcmgBusinessConfig.configurePopulationQuery(ba1, new org.springframework.mock.env.MockEnvironment());
        assertEquals(java.time.Duration.ofSeconds(60), ba1.getGnomad().getTimeout());
        assertEquals(java.time.Duration.ofSeconds(120), ba1.getGnomad().getRemoteTotalTimeout());
        env.withProperty("variant-analysis.acmg-business.gnomad-total-timeout-seconds", "10");
        assertThrows(IllegalArgumentException.class, () -> AcmgBusinessConfig.configurePopulationQuery(pm2, env));
    }
    @Test
    void creatingRuntimeDirectoryCannotChangeResourceBinding(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var properties = new VariantAnalysisProperties();
        var environment = new org.springframework.mock.env.MockEnvironment();
        var output = temporary.resolve("new-runtime");
        properties.getVep().setTempDir(output.toString());
        var before = AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, environment);
        var originalSerializerBefore = mapper.valueToTree(properties.getVep()).path("tempDir");
        java.nio.file.Files.createDirectories(output);
        assertNotEquals(originalSerializerBefore, mapper.valueToTree(properties.getVep()).path("tempDir"),
                "The original Jackson Path URI representation reproduces the directory-lifecycle drift");
        assertEquals(before, AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, environment));
        properties.getVep().setFasta(temporary.resolve("different-reference.fa").toString());
        assertNotEquals(before, AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, environment),
                "Actual configured reference changes still invalidate reuse");
    }

    @Test
    void runtimeIdentityRetainsExactDurationWithoutMutatingApplicationSerialization() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var properties = new VariantAnalysisProperties();
        var environment = new org.springframework.mock.env.MockEnvironment();
        properties.getClinVar().setTimeout(java.time.Duration.ofMillis(1500));
        var binding = AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, environment);
        assertEquals("PT1.5S", binding.at("/clinvar/timeout").asText());
        assertTrue(mapper.isEnabled(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS));
        var first = new org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile(mapper, binding).document();
        assertEquals(first, new org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile(mapper,
                AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, environment)).document());
        properties.getClinVar().setTimeout(java.time.Duration.ofMillis(1501));
        var changed = new org.nbscn.varvis.variantanalysis.provider.AcmgExecutionProfile(mapper,
                AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, environment)).document();
        assertNotEquals(first.path("manifestSha256"), changed.path("manifestSha256"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "test_"})
    void bootstrapsMissingTablesAndRestartsWithoutDdl(String prefix) throws Exception {
        var dataSource = database();
        var jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.execute("CREATE TABLE " + prefix + "variant_analysis_job (id CHAR(36) PRIMARY KEY)");
            initialize(dataSource, prefix);
            jdbc.update("INSERT INTO " + prefix + "variant_analysis_job (id) VALUES ('job')");
            jdbc.update("INSERT INTO " + prefix + "variant_analysis_acmg_source VALUES ('job', 'sha', '{}')");

            // A real MySQL CREATE IF NOT EXISTS can wait on a transaction's metadata lock.
            // Reject statements entirely on restart: only JDBC metadata lookup is necessary.
            initialize(metadataOnly(dataSource), prefix);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + prefix + "variant_analysis_acmg_source", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + prefix + "variant_analysis_acmg_run", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + prefix + "variant_analysis_acmg_chunk", Integer.class));
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }

    @Test
    void completesPartialBootstrapWithoutReplacingExistingData() throws Exception {
        var dataSource = database();
        var jdbc = new JdbcTemplate(dataSource);
        try {
            jdbc.execute("CREATE TABLE variant_analysis_job (id CHAR(36) PRIMARY KEY)");
            initialize(dataSource, "");
            jdbc.update("INSERT INTO variant_analysis_job (id) VALUES ('job')");
            jdbc.update("INSERT INTO variant_analysis_acmg_source VALUES ('job', 'sha', '{}')");
            jdbc.execute("DROP TABLE variant_analysis_acmg_chunk");
            jdbc.execute("DROP TABLE variant_analysis_acmg_run");
            initialize(dataSource, "");
            assertEquals("{}", jdbc.queryForObject("SELECT snapshot_json FROM variant_analysis_acmg_source", String.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM variant_analysis_acmg_run", Integer.class));
        } finally {
            jdbc.execute("SHUTDOWN");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"", "test_"})
    void addsOnlyChunkTableToExistingHistory(String prefix) throws Exception {
        var ds = database(); var jdbc = new JdbcTemplate(ds);
        try {
            jdbc.execute("CREATE TABLE " + prefix + "variant_analysis_job (id CHAR(36) PRIMARY KEY)");
            initialize(ds, prefix);
            jdbc.update("INSERT INTO " + prefix + "variant_analysis_job VALUES ('job')");
            jdbc.update("INSERT INTO " + prefix + "variant_analysis_acmg_run (run_id,analysis_id,request_sha256,request_json,status,created_at,response_json) VALUES ('run','job','hash','{}','COMPLETED','now','old complete result')");
            jdbc.execute("DROP TABLE " + prefix + "variant_analysis_acmg_chunk");
            initialize(ds, prefix);
            assertEquals("old complete result", jdbc.queryForObject("SELECT response_json FROM " + prefix + "variant_analysis_acmg_run", String.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + prefix + "variant_analysis_acmg_chunk", Integer.class));
            initialize(metadataOnly(ds), prefix);
        } finally { jdbc.execute("SHUTDOWN"); }
    }

    @Test
    void reportsConnectionFailureInsteadOfPretendingSchemaIsReady() {
        var failure = new SQLException("Database unavailable", "08S01");
        var unavailable = new DelegatingDataSource() {
            @Override @NonNull public Connection getConnection() throws SQLException { throw failure; }
        };
        assertSame(failure, assertThrows(SQLException.class, () -> initialize(unavailable, "")));
    }

    private static @NonNull DriverManagerDataSource database() {
        return new DriverManagerDataSource("jdbc:h2:mem:acmg-schema-" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
    }

    private static void initialize(DataSource dataSource, String prefix) throws Exception {
        var properties = new VariantAnalysisProperties();
        properties.getPersistence().setTablePrefix(prefix);
        var legacy = new DefaultListableBeanFactory().getBeanProvider(DataSourceInitializer.class);
        new AcmgBusinessConfig().acmgBusinessSchema(dataSource, properties, legacy).afterPropertiesSet();
    }

    private static DataSource metadataOnly(@NonNull DataSource delegate) {
        return new DelegatingDataSource(delegate) {
            @Override @NonNull public Connection getConnection() throws SQLException {
                var connection = super.getConnection();
                return Objects.requireNonNull((Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("createStatement") || method.getName().equals("prepareStatement")) {
                                throw new SQLException("Existing schema must not execute startup SQL");
                            }
                            try { return method.invoke(connection, args); }
                            catch (InvocationTargetException failure) { throw failure.getCause(); }
                        }));
            }
        };
    }
    @Test void businessOutputsCanBeIsolatedWithoutRelocatingPublishedAnalysisSources() {
        var properties = new VariantAnalysisProperties();
        properties.setStorageRoot(java.nio.file.Path.of("published-jobs"));
        var environment = new org.springframework.mock.env.MockEnvironment();
        assertEquals(properties.getStorageRoot(), AcmgBusinessConfig.businessWorkingRoot(properties, environment));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var before = AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, environment);
        environment.withProperty("variant-analysis.acmg-business.working-root", "private-business-work");
        assertEquals(java.nio.file.Path.of("private-business-work"),
                AcmgBusinessConfig.businessWorkingRoot(properties, environment));
        assertEquals(java.nio.file.Path.of("published-jobs"), properties.getStorageRoot());
        assertNotEquals(before, AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, environment));
    }

    @Test void missingOptionalReviewedRulesRemainARecordedFailureWithoutBlockingStartup() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var env = new org.springframework.mock.env.MockEnvironment();
        assertNull(AcmgBusinessConfig.reviewedRuleBundle(mapper, env));
        env.withProperty("variant-analysis.acmg-business.reviewed-rule-policy-enabled", "true")
                .withProperty("variant-analysis.acmg-business.reviewed-rule-bundle-sha256", java.util.Objects.requireNonNull("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        var bundle = AcmgBusinessConfig.reviewedRuleBundle(mapper, env);
        if (bundle == null) throw new AssertionError("Configured failure must remain bound");
        assertEquals("UNAVAILABLE", bundle.identity().path("status").asText());
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", bundle.identity().path("expectedBundleSha256").asText());
        assertNull(AcmgBusinessConfig.reviewedRuleContexts(mapper, bundle));
        assertNull(AcmgBusinessConfig.reviewedRuleContexts(mapper, null));
    }

    @Test void configuredRuleContextUsesTheSameLoadedBundleSnapshot(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var document = org.nbscn.varvis.variantanalysis.provider.ReviewedRuleContextPreparationTest.emptyBundleDocument(mapper, "1");
        var digest = new org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts(mapper).requestHash(document);
        var file = temporary.resolve("synthetic-context-bundle.json");
        java.nio.file.Files.writeString(file, mapper.writeValueAsString(document));
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.acmg-business.reviewed-rule-policy-enabled", "true")
                .withProperty("variant-analysis.acmg-business.reviewed-rule-bundle-file", Objects.requireNonNull(file.toString()))
                .withProperty("variant-analysis.acmg-business.reviewed-rule-bundle-sha256", Objects.requireNonNull(digest));
        var bundle = AcmgBusinessConfig.reviewedRuleBundle(mapper, env);
        if (bundle == null) throw new AssertionError("Configured synthetic bundle missing");
        var capability = AcmgBusinessConfig.reviewedRuleContexts(mapper, bundle);
        if (capability == null) throw new AssertionError("Configured synthetic context capability missing");
        assertEquals(bundle.identity(), capability.identity().path("reviewedRuleBinding"));
        java.nio.file.Files.writeString(file, "invalid updated source");
        assertEquals(bundle.identity(), capability.identity().path("reviewedRuleBinding"));
        var next = AcmgBusinessConfig.reviewedRuleBundle(mapper, env);
        if (next == null) throw new AssertionError("Unavailable replacement must remain identified");
        assertEquals("UNAVAILABLE", next.identity().path("status").asText());
        assertNull(AcmgBusinessConfig.reviewedRuleContexts(mapper, next));
    }

    @Test void jointConfigurationIdentityIncludesPopulationAndRuleActivationInputs() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var properties = new VariantAnalysisProperties();
        var env = new org.springframework.mock.env.MockEnvironment();
        var first = AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, env);
        env.withProperty("variant-analysis.acmg-business.pm2-all-sites-command-file", "changed-command.json");
        var changed = AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, env);
        assertNotEquals(first, changed);
        env.withProperty("variant-analysis.acmg-business.pvs1-enabled", "false");
        assertNotEquals(changed, AcmgBusinessConfig.missenseRuntimeBinding(mapper, properties, env));
    }

}
