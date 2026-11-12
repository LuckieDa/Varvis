package org.nbscn.varvis.variantanalysis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.nbscn.varvis.variantanalysis.service.AcmgBusinessService;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** TEST_ONLY configuration boundaries: no application context or external resource startup. */
class Pm1GeneralBusinessConfigTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build();
    private MockEnvironment scope() {
        return new MockEnvironment().withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.PM1_DEVELOPMENT);
    }
    private AcmgBusinessService service(MockEnvironment env) {
        return new AcmgBusinessConfig().acmgBusinessService(mapper, null, null, null, new VariantAnalysisProperties(), env);
    }
    private AcmgExecutionProfile profile(AcmgBusinessService service) {
        Object value = ReflectionTestUtils.getField(Objects.requireNonNull(service), "executionProfile");
        if (!(value instanceof AcmgExecutionProfile profile)) throw new AssertionError("Execution profile absent");
        return profile;
    }
    @Test void defaultCurrentNeverReadsTheNewScopeOrDamagedPm1Configuration() {
        var env = new MockEnvironment().withProperty("variant-analysis.acmg-business.pm1-bundle-file", "invalid" + (char) 0 + "path")
                .withProperty("variant-analysis.acmg-business.pm1-bundle-sha256", "not-a-sha");
        var value = profile(service(env));
        assertEquals(AcmgBusinessRuleScope.CURRENT, value.criteria());
        assertFalse(value.document().toString().contains("pm1-region"));
        assertEquals(profile(service(new MockEnvironment())).document(), value.document());
    }
    @Test void explicitScopeRequiresEnablementAndFixedServerMaterials() {
        assertEquals("PM1_BUSINESS_EXECUTION_DISABLED", assertThrows(IllegalArgumentException.class, () -> service(scope())).getMessage());
        var enabled = scope().withProperty("variant-analysis.acmg-business.enabled", "true");
        assertEquals("PM1_TRUSTED_BUNDLE_REQUIRED", assertThrows(IllegalArgumentException.class, () -> service(enabled)).getMessage());
        for (String flag : java.util.List.of("revel-application-enabled", "saved-observation-calibration-only", "saved-observation-association-only")) {
            var mixed = scope().withProperty("variant-analysis.acmg-business.enabled", "true")
                    .withProperty("variant-analysis.acmg-business." + flag, "true");
            assertEquals("PM1_EXECUTION_MODE_CONFLICT", assertThrows(IllegalArgumentException.class, () -> service(mixed)).getMessage());
        }
    }
    @Test void validPm1IdentityIsIndependentAndWrongCanonicalPinCannotFallBack() throws Exception {
        var fixture = new Pm1GeneralTestFixture(mapper);
        Path path = temporary.resolve("pm1-test-only-bundle.json");
        var document = fixture.document(); Files.write(path, mapper.writeValueAsBytes(document));
        String sha = fixture.canonicalSha256(document);
        var env = scope().withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.pm1-bundle-file", Objects.requireNonNull(path.toString()))
                .withProperty("variant-analysis.acmg-business.pm1-bundle-sha256", Objects.requireNonNull(sha));
        var value = profile(service(env));
        assertEquals(java.util.List.of("ACMG_AMP_2015:PM1"), value.criteria());
        assertEquals("acmg-pm1-region-development-v1", value.document().path("profileId").asText());
        assertEquals(AcmgBusinessRuleScope.PM1_DECLARATION, value.document().path("requiredDeclaration").asText());
        assertFalse(value.document().path("formalEnabled").asBoolean(true));
        assertFalse(value.document().toString().contains(Hnf1aPm1Runtime.PROFILE_SHA256));
        env.withProperty("variant-analysis.acmg-business.pm1-bundle-sha256", Objects.requireNonNull("0".repeat(64)));
        assertEquals("PM1_TRUSTED_BUNDLE_REQUIRED", assertThrows(IllegalArgumentException.class, () -> service(env)).getMessage());
    }
}
