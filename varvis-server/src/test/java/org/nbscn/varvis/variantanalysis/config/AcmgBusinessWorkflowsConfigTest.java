package org.nbscn.varvis.variantanalysis.config;

import static java.util.Objects.requireNonNull;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class AcmgBusinessWorkflowsConfigTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;

    @Test void selectedAlphaMissenseAndReferenceAcquisitionReachTheLocalProviderSettings() {
        var original = new MockEnvironment()
                .withProperty("variant-analysis.acmg-business.local-batch-general-protein-model", "ALPHAMISSENSE")
                .withProperty("variant-analysis.alpha-missense.enabled", "true")
                .withProperty("variant-analysis.clin-var.enabled", "true")
                .withProperty("variant-analysis.acmg-business.local-batch-reference-source-file", "public-source.json");
        var local = AcmgBusinessConfig.localWorkflowEnvironment(original);
        var bound = org.springframework.boot.context.properties.bind.Binder.get(local)
                .bind("variant-analysis", org.springframework.boot.context.properties.bind.Bindable.of(VariantAnalysisProperties.class))
                .orElseThrow(() -> new IllegalStateException("NO_LOCAL_SETTINGS"));
        assertAll(() -> assertTrue(bound.getAlphaMissense().isEnabled(), "The selected model must reach the actual provider"),
                () -> assertTrue(bound.getClinVar().isEnabled(), "Configured reference capture must reach the actual provider"));
        assertEquals("true", original.getProperty("variant-analysis.alpha-missense.enabled"));
        assertEquals("true", original.getProperty("variant-analysis.clin-var.enabled"));
        assertNull(local.getProperty("variant-analysis.acmg-business.local-batch-ps1-bundle-file"));
        assertNull(local.getProperty("variant-analysis.acmg-business.local-batch-reference-qualified-application-enabled"));
    }

    @Test void localAcquisitionRespectsProviderDisablingAndPreselectedProteinModel() {
        var original = new MockEnvironment()
                .withProperty("variant-analysis.acmg-business.local-batch-general-protein-model", "ALPHAMISSENSE")
                .withProperty("variant-analysis.alpha-missense.enabled", "false")
                .withProperty("variant-analysis.clin-var.enabled", "false")
                .withProperty("variant-analysis.acmg-business.local-batch-reference-source-file", "public-source.json");
        var disabled = AcmgBusinessConfig.localWorkflowEnvironment(original);
        assertEquals("false", disabled.getProperty("variant-analysis.alpha-missense.enabled"));
        assertEquals("false", disabled.getProperty("variant-analysis.clin-var.enabled"));
        original.withProperty("variant-analysis.acmg-business.local-batch-general-protein-model", "REVEL")
                .withProperty("variant-analysis.alpha-missense.enabled", "true");
        assertEquals("false", AcmgBusinessConfig.localWorkflowEnvironment(original)
                .getProperty("variant-analysis.alpha-missense.enabled"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SEPARATE", "EIGHT", "ELEVEN"})
    void springBeanProxyRespectsSeparateOrUnifiedConfiguration(String mode) {
        boolean unified = !"SEPARATE".equals(mode), eleven = "ELEVEN".equals(mode);
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:workflow_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE variant_analysis_acmg_source (analysis_id VARCHAR(128))");
        jdbc.execute("CREATE TABLE variant_analysis_acmg_run (run_id VARCHAR(128))");
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            var environment = new MockEnvironment()
                    .withProperty("variant-analysis.acmg-business.enabled", "true")
                    .withProperty("variant-analysis.acmg-business.local-batch-workflow-enabled", "true")
                    .withProperty("variant-analysis.storage-root", requireNonNull(temporary.toString()))
                    .withProperty("variant-analysis.acmg-business.working-root", requireNonNull(temporary.toString()));
            if (unified) environment
                    .withProperty("variant-analysis.acmg-business.unified-local-workflow-enabled", "true")
                    .withProperty("variant-analysis.acmg-business.local-batch-bp7-enabled", "true")
                    .withProperty("variant-analysis.acmg-business.ba1-local-exomes-enabled", "true")
                    .withProperty("variant-analysis.acmg-business.bs1-local-exomes-enabled", "true");
            if (eleven) environment
                    .withProperty("variant-analysis.acmg-business.local-batch-pvs1-enabled", "true")
                    .withProperty("variant-analysis.acmg-business.local-batch-pm4-enabled", "true")
                    .withProperty("variant-analysis.acmg-business.local-batch-bp3-enabled", "true");
            context.setEnvironment(environment);
            context.registerBean("varvisDataSource", javax.sql.DataSource.class, () -> ds);
            context.registerBean(com.fasterxml.jackson.databind.ObjectMapper.class,
                    () -> com.fasterxml.jackson.databind.json.JsonMapper.builder().findAndAddModules().build());
            context.registerBean(VariantAnalysisProperties.class, VariantAnalysisProperties::new);
            context.registerBean(org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository.class,
                    () -> org.mockito.Mockito.mock(org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository.class));
            context.register(AcmgBusinessConfig.class);
            context.refresh();
            var workflows = context.getBean(org.nbscn.varvis.variantanalysis.service.AcmgBusinessWorkflows.class);
            if (unified) {
                assertEquals(eleven ? 11 : 8, ((java.util.List<?>) requireNonNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(requireNonNull(workflows.current()), "configuredCriteria"))).size());
                assertSame(workflows.current(), workflows.select("CURRENT"));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,
                        () -> workflows.select("LOCAL_COMPUTATIONAL_BATCH1"));
                assertEquals(2, ((java.util.List<?>) requireNonNull(org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                        context.getBean(org.nbscn.varvis.variantanalysis.service.AcmgBusinessService.class), "configuredCriteria"))).size());
            } else {
                assertSame(context.getBean(org.nbscn.varvis.variantanalysis.service.AcmgBusinessService.class), workflows.current());
                assertNotSame(workflows.current(), workflows.select("LOCAL_COMPUTATIONAL_BATCH1"),
                        "A @Bean self-call must not redirect local construction to the CURRENT singleton");
            }
        } finally { jdbc.execute("SHUTDOWN"); }
    }

    @Test void localOverlayPreservesCurrentScopeKnowledgeAndOriginalEnvironment() {
        var original = new MockEnvironment()
                .withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.bs1-local-exomes-enabled", "true")
                .withProperty("variant-analysis.acmg-business.bs1-knowledge-file", "synthetic-knowledge.json")
                .withProperty("variant-analysis.acmg-business.bs1-knowledge-sha256", "synthetic-sha")
                .withProperty("variant-analysis.alpha-missense.enabled", "true")
                .withProperty("variant-analysis.acmg-business.spliceai-raw-command-json", "${UNCONFIGURED_WSL}")
                .withProperty("variant-analysis.acmg-business.local-batch-workflow-in-process-population-enabled", "true")
                .withProperty("variant-analysis.acmg-business.local-batch-workflow-grch38-contig-alias-file", "synthetic-alias.tsv")
                .withProperty("variant-analysis.acmg-business.native-splice-binding-file", "synthetic-binding.json");
        var local = AcmgBusinessConfig.localWorkflowEnvironment(original);
        assertEquals("LOCAL_COMPUTATIONAL_BATCH1", local.getProperty("variant-analysis.acmg-business.acceptance-scope"));
        assertEquals("false", local.getProperty("variant-analysis.acmg-business.bs1-local-exomes-enabled"));
        assertEquals("", local.getProperty("variant-analysis.acmg-business.bs1-knowledge-file"));
        assertEquals("synthetic-binding.json", local.getProperty("variant-analysis.acmg-business.native-splice-binding-file"));
        assertEquals("true", original.getProperty("variant-analysis.acmg-business.bs1-local-exomes-enabled"));
        assertEquals("synthetic-knowledge.json", original.getProperty("variant-analysis.acmg-business.bs1-knowledge-file"));
        assertNull(original.getProperty("variant-analysis.acmg-business.acceptance-scope"));
        assertEquals("true", local.getProperty("variant-analysis.acmg-business.enabled"));
        assertEquals("true", original.getProperty("variant-analysis.alpha-missense.enabled"));
        assertEquals("false", local.getProperty("variant-analysis.alpha-missense.enabled"));
        assertEquals("true", local.getProperty("variant-analysis.gnomad.local-population-in-process-enabled"));
        assertNull(original.getProperty("variant-analysis.gnomad.local-population-in-process-enabled"));
        assertEquals("synthetic-alias.tsv", local.getProperty("variant-analysis.normalization.grch38-contig-alias-file"));
        assertNull(original.getProperty("variant-analysis.normalization.grch38-contig-alias-file"));
        assertEquals("", local.getProperty("variant-analysis.acmg-business.spliceai-raw-command-json"));
    }
}
