package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

/** Versioned invocation successor, separate from the unchanged scientific calibration profile. */
public final class AcmgBusinessActivation {
    private AcmgBusinessActivation() { }
    public static void verify(ObjectMapper mapper) {
        try (var input = AcmgBusinessActivation.class.getResourceAsStream("/acmg/pp3-bp4-business-activation-v1.json")) {
            byte[] bytes = input.readAllBytes();
            if (!"35346d1674e081a03a07a11cb0f0eff3201f2f58d3e7889abc89f4152dafcdf8".equals(
                    org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))))
                throw new IllegalArgumentException("ACMG_BUSINESS_ACTIVATION_MISMATCH");
            JsonNode registration = mapper.readTree(bytes);
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            var profile = AlphaMissenseCriterionEvaluator.loadProfile(mapper);
            var rules = AlphaMissenseCriterionEvaluator.loadSuccessorRegistry(mapper);
            if (!"pp3-bp4-explicit-business-e4-b1-v1".equals(registration.path("registrationId").asText())
                    || !registration.path("supersedesInvocationActivation").equals(profile.path("activation"))
                    || !registration.path("calibrationProfileCanonicalSha256").asText().equals(adapter.canonicalSha256(profile))
                    || !registration.path("ruleRegistryId").equals(rules.path("registryId"))
                    || !registration.path("ruleRegistrySha256").equals(profile.at("/registryBinding/registrySha256"))
                    || !registration.path("effectiveInvocationActivation").path("patientFlowEnabled").asBoolean()
                    || registration.path("automaticTrigger").asBoolean() || registration.at("/effectiveInvocationActivation/classificationEnabled").asBoolean())
                throw new IllegalArgumentException("ACMG_BUSINESS_ACTIVATION_MISMATCH");
            try (var cspecInput = AcmgBusinessActivation.class.getResourceAsStream("/acmg/cspec-overlay-registry-v1/" + registration.path("cspecRegistryId").asText() + ".json")) {
                String cspec = new String(cspecInput.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (!registration.path("cspecRegistrySha256").asText().equals(org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(cspec)))
                    throw new IllegalArgumentException("ACMG_BUSINESS_ACTIVATION_MISMATCH");
            }
            // The dispatcher always independently verifies the pinned CSpec/authority/rule/profile pairing.
        } catch (Exception invalid) { throw new IllegalArgumentException("ACMG_BUSINESS_ACTIVATION_MISMATCH", invalid); }
    }
}
