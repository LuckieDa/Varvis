package org.nbscn.varvis.variantanalysis.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.nbscn.varvis.variantanalysis.provider.AlphaMissenseProvider;
import org.nbscn.varvis.variantanalysis.provider.AlphaMissensePhaseBudget;
import static org.junit.jupiter.api.Assertions.*;

class SavedCalibrationModeTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withBean(VariantAnalysisProperties.class)
                .withUserConfiguration(AlphaMissenseProvider.class, AlphaMissensePhaseBudget.class);
    }
    @Test void calibrationHasNoAmProviderAndDoesNotInitializeOldBudgetEvenWhenEnabled() {
        runner().withPropertyValues("variant-analysis.acmg-business.saved-observation-calibration-only=true",
                "variant-analysis.acmg-business.am-phase-budget-enabled=true").run(context -> {
            assertNull(context.getStartupFailure()); assertTrue(context.getBeansOfType(AlphaMissenseProvider.class).isEmpty());
            assertTrue(context.getBeansOfType(AlphaMissensePhaseBudget.class).isEmpty());
        });
    }
    @Test void defaultAndAssociationRetainLegacyProviderAssembly() {
        runner().run(context -> { assertNull(context.getStartupFailure()); assertEquals(1, context.getBeansOfType(AlphaMissenseProvider.class).size()); });
        runner().withPropertyValues("variant-analysis.acmg-business.saved-observation-association-only=true",
                "variant-analysis.acmg-business.am-phase-budget-enabled=true").run(context -> {
            assertNull(context.getStartupFailure()); assertEquals(1, context.getBeansOfType(AlphaMissenseProvider.class).size());
            assertTrue(context.getBeansOfType(AlphaMissensePhaseBudget.class).isEmpty());
        });
    }
    @Test void enabledScienceSettingsCannotBeUsedForCalibrationIsolation() {
        var properties = new VariantAnalysisProperties();
        properties.getAlphaMissense().setEnabled(false); properties.getAlphaGenome().setEnabled(false);
        properties.getCadd().setEnabled(false); properties.getClinVar().setEnabled(false);
        properties.getGnomad().setEnabled(false); properties.getSpliceAi().setEnabled(false);
        properties.getVep().setEnabled(false); properties.getVep().setExecutionEnabled(false);
        properties.getExomiser().setEnabled(false); properties.getLlmShadow().setEnabled(false);
        properties.getPhenotypePrior().setEnabled(false);
        assertDoesNotThrow(() -> AcmgBusinessConfig.requireCalibrationIsolation(properties));
        properties.getAlphaMissense().setEnabled(true);
        assertThrows(IllegalArgumentException.class, () -> AcmgBusinessConfig.requireCalibrationIsolation(properties));
        properties.getAlphaMissense().setEnabled(false);
        properties.getPhenotypePrior().setEnabled(true);
        assertThrows(IllegalArgumentException.class, () -> AcmgBusinessConfig.requireCalibrationIsolation(properties));
    }

    @Test void calibrationBlocksPhenotypeQueryBeforeControllerWhileSavedReadsRemainAvailable() throws Exception {
        String prefix = "/variant-analyses/b6353342-1e10-4c75-9ef9-8f42c229d084";
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("variant-analysis.acmg-business.acceptance-scope", "PP3_BP4_DEVELOPMENT")
                .withProperty("variant-analysis.acmg-business.saved-observation-calibration-only", "true");
        var filter = new SavedObservationOnlyConfig().savedObservationOnlyFilter(environment).getFilter();
        assertNotNull(filter);
        for (String method : java.util.List.of("GET", "HEAD", "OPTIONS")) {
            var request = new org.springframework.mock.web.MockHttpServletRequest(method, prefix + "/phenotype-prior");
            request.setServletPath(prefix + "/phenotype-prior");
            var response = new org.springframework.mock.web.MockHttpServletResponse();
            var reached = new java.util.concurrent.atomic.AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> reached.set(true));
            assertEquals(403, response.getStatus()); assertFalse(reached.get());
            assertTrue(SavedObservationOnlyConfig.allowed(method, prefix + "/phenotype-prior"));
            for (String path : java.util.List.of(prefix, prefix + "/variants", prefix + "/acmg/input-options",
                    prefix + "/acmg/runs/41bbec6c-26e8-43a1-b637-94b3798f93e7",
                    prefix + "/exports/json", "/index.html", "/umi.js")) {
                if (path == null) throw new AssertionError("Saved route fixture must be non-null");
                var savedRequest = new org.springframework.mock.web.MockHttpServletRequest(method, path);
                savedRequest.setServletPath(path);
                var savedResponse = new org.springframework.mock.web.MockHttpServletResponse();
                filter.doFilter(savedRequest, savedResponse, (req, res) -> reached.set(true));
                assertTrue(reached.get(), method + " " + path); assertEquals(200, savedResponse.getStatus());
                reached.set(false);
            }
        }
    }
}
