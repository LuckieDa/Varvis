package org.nbscn.varvis.variantanalysis.config;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.provider.AlphaMissensePhaseBudget;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class SavedObservationOnlyConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SavedObservationOnlyConfig.class, AlphaMissensePhaseBudget.class);

    @Test void exclusiveModeNeverInitializesOldBudgetAndRejectsScientificEntrances() {
        runner.withPropertyValues("variant-analysis.acmg-business.saved-observation-association-only=true",
                "variant-analysis.acmg-business.am-phase-budget-enabled=true",
                "variant-analysis.acmg-business.acceptance-scope=PP3_BP4_DEVELOPMENT").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(0, context.getBeansOfType(AlphaMissensePhaseBudget.class).size());
            var filter = context.getBean(FilterRegistrationBean.class).getFilter();
            var forwarded = new AtomicInteger();
            for (String path : new String[]{"/variant-analyses", "/variant-analyses/history",
                    "/variant-analyses/id/enhancements", "/variant-analyses/id/acmg/runs/id/splicing-checks"}) {
                var request = new MockHttpServletRequest("POST", path); request.setServletPath(java.util.Objects.requireNonNull(path));
                var response = new MockHttpServletResponse();
                filter.doFilter(request, response, (req, res) -> forwarded.incrementAndGet());
                assertEquals(403, response.getStatus());
                assertTrue(response.getContentAsString().contains("NEW_SCIENTIFIC_ACQUISITION_DISABLED"));
            }
            assertEquals(0, forwarded.get());
            String path = "/variant-analyses/00000000-0000-0000-0000-000000000000/acmg/runs";
            var request = new MockHttpServletRequest("POST", path); request.setServletPath(path);
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> forwarded.incrementAndGet());
            assertEquals(1, forwarded.get()); // The existing service still validates the operation/declaration.
        });
    }
    @Test void formalScopeCannotEnableAssociationMode() {
        runner.withPropertyValues("variant-analysis.acmg-business.saved-observation-association-only=true",
                "variant-analysis.acmg-business.acceptance-scope=CURRENT").run(context -> assertNotNull(context.getStartupFailure()));
    }
    @Test void defaultWiringIsUnchanged() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(FilterRegistrationBean.class).isEmpty());
            assertTrue(context.getBeansOfType(AlphaMissensePhaseBudget.class).isEmpty());
        });
    }
}
