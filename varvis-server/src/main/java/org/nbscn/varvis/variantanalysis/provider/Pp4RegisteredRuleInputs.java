package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Objects;

/** Immutable MAIN-injected existing rule/catalog selection inputs.
 * Pin validity is not a fresh expert-catalog search or clinical approval. MAIN must register
 * the acquired, appropriately current scoped original; native case facts never create it.
 */
public final class Pp4RegisteredRuleInputs {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ObjectNode template;
    private final ObjectNode identity;
    private final byte[] original;
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    private Pp4RegisteredRuleInputs(ObjectNode template, ObjectNode identity, byte[] original) {
        this.template = template.deepCopy(); this.identity = identity.deepCopy(); this.original = original.clone();
    }
    public static Pp4RegisteredRuleInputs load(byte[] original, String rawPin, String canonicalPin) throws IOException {
        Objects.requireNonNull(original); original = original.clone();
        try {
            var bundle = Pp4PhenotypeBundle.load(JSON, original, rawPin, canonicalPin);
            ObjectNode template = bundle.snapshot();
            ObjectNode context = (ObjectNode) template.path("context"), policy = (ObjectNode) template.path("policy");
            new RuntimeEvidenceV2Adapter(JSON).validateVersionedContext(context);
            need("evidence-context-v3".equals(context.path("schemaVersion").asText()), "PP4_REGISTERED_RULE_V3_REQUIRED");
            need("SINGLE_CASE_AD".equals(policy.path("mode").asText())
                    && "pp4-diploid-snv-dp20-gq20-explicit-ft-v1".equals(policy.path("qualityProfile").asText())
                    && "AUTOSOMAL_DOMINANT".equals(policy.at("/scope/moi").asText())
                    && "GRCh38".equals(policy.at("/scope/assembly").asText())
                    && "TRANSCRIPT_NOT_REQUIRED".equals(policy.at("/scope/transcriptMode").asText())
                    && "NATIVE_NONPROJECTED_SNV".equals(policy.at("/scope/variantType").asText()), "PP4_REGISTERED_RULE_PROFILE_UNSUPPORTED");
            var selected = AcmgSpecificationSelector.forPp4Versioned(JSON, (ObjectNode) template.path("catalog"),
                    template.path("catalogSha256").asText(), JSON.createArrayNode().add(policy)).select(context, Pp4PhenotypeCriterionEvaluator.CRITERION);
            need(selected.selectedPolicy() != null, "PP4_REGISTERED_RULE_SELECTION_UNAVAILABLE");
            registry(template);
            var identity = JSON.createObjectNode().put("schemaVersion", "pp4-registered-rule-inputs-v1")
                    .put("templateRawSha256", rawPin).put("templateCanonicalSha256", canonicalPin)
                    .put("dataClass", context.path("dataClass").asText())
                    .put("originalPolicySha256", Pp4ProductionMethodRegistration.canonical(policy))
                    .put("originalCatalogSha256", template.path("catalogSha256").asText())
                    .put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("policyVersion").asText());
            identity.set("scope", policy.path("scope").deepCopy()); identity.set("catalogSources", template.at("/catalog/sources").deepCopy());
            identity.set("scientificSources", policy.path("scientificSources").deepCopy()); identity.set("registryBinding", template.path("registryBinding").deepCopy());
            identity.set("originalSelection", selected.receipt().deepCopy());
            return new Pp4RegisteredRuleInputs(template, identity, original);
        } catch (IllegalArgumentException invalid) { throw new IOException("PP4_REGISTERED_RULE_INPUT_INVALID", invalid); }
    }
    public ObjectNode identity() { return identity.deepCopy(); }
    public byte[] originalBytes() { return original.clone(); }
    public String sha256() { return Pp4ProductionMethodRegistration.canonical(identity); }
    public boolean matchesContext(ObjectNode context) {
        if (context == null || !template.at("/context/dataClass").equals(context.path("dataClass"))) return false;
        ObjectNode policy = (ObjectNode) template.path("policy");
        try {
            var selected = AcmgSpecificationSelector.forPp4Versioned(JSON, (ObjectNode) template.path("catalog"),
                    template.path("catalogSha256").asText(), JSON.createArrayNode().add(policy)).select(context, Pp4PhenotypeCriterionEvaluator.CRITERION);
            return selected.selectedPolicy() != null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
    /** Only hash slots are derived. Catalog scope, conclusions, reviews and provenance are retained. */
    ObjectNode derive(Pp4QualifiedMaterial material, ObjectNode observation) throws IOException {
        var data = material.nativeFacts(); var context = (ObjectNode) data.path("context");
        need(matchesContext(context), "PP4_REGISTERED_RULE_SCOPE_OR_DATA_CLASS_MISMATCH");
        var result = JSON.createObjectNode().put("schemaVersion", "pp4-phenotype-core-bundle-v1"); result.set("context", context.deepCopy());
        result.putArray("observations").add(observation.deepCopy());
        ObjectNode policy = ((ObjectNode) template.path("policy")).deepCopy();
        policy.set("partPins", observation.at("/assessment/partPins").deepCopy());
        policy.put("assessmentSha256", Pp4ProductionMethodRegistration.canonical(observation.path("assessment")));
        ObjectNode catalog = ((ObjectNode) template.path("catalog")).deepCopy(); int rebound = 0;
        for (var entry : catalog.path("entries")) {
            JsonNode old = entry.path("policyBinding");
            if (old.path("policyId").equals(policy.path("policyId")) && old.path("policyVersion").equals(policy.path("policyVersion"))
                    && old.path("policySha256").asText().equals(identity.path("originalPolicySha256").asText())) {
                ((ObjectNode) old).put("policySha256", Pp4ProductionMethodRegistration.canonical(policy)); rebound++;
            }
        }
        need(rebound > 0, "PP4_REGISTERED_RULE_POLICY_RELATION_MISSING");
        result.set("policy", policy); result.set("catalog", catalog);
        result.put("catalogSha256", Pp4ProductionMethodRegistration.canonical(catalog));
        result.set("registryBinding", template.path("registryBinding").deepCopy());
        return result;
    }
    private static void registry(ObjectNode template) throws IOException {
        JsonNode binding = template.path("registryBinding"), policy = template.path("policy");
        String resource = Pp4PhenotypeCriterionEvaluator.REGISTRY;
        need(resource.equals(binding.path("registryClasspathResource").asText()), "PP4_REGISTERED_RULE_REGISTRY_INVALID");
        try (var stream = Pp4RegisteredRuleInputs.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("PP4_REGISTERED_RULE_REGISTRY_MISSING");
            byte[] bytes = stream.readAllBytes(); JsonNode registry = JSON.readTree(bytes);
            need(registry != null && Pp4ProductionMethodRegistration.rawSha(bytes).equals(binding.path("registrySha256").asText())
                    && registry.path("registryId").equals(binding.path("registryId")), "PP4_REGISTERED_RULE_REGISTRY_PIN_MISMATCH");
            if (registry == null) throw new IOException("PP4_REGISTERED_RULE_REGISTRY_INVALID");
            int implemented = 0;
            for (var criterion : registry.path("criteria")) if (Pp4PhenotypeCriterionEvaluator.CRITERION.equals(criterion.path("criterionId").asText())) {
                need("V2_IMPLEMENTED".equals(criterion.path("implementationStatus").asText())
                        && "PATHOGENIC".equals(criterion.path("direction").asText())
                        && "SUPPORTING".equals(criterion.at("/currentGeneral/strength").asText())
                        && criterion.at("/currentGeneral/authoritySourceIds").equals(policy.path("authoritySourceIds")), "PP4_REGISTERED_RULE_REGISTRY_SCOPE_MISMATCH");
                implemented++;
            }
            need(implemented == 1, "PP4_REGISTERED_RULE_REGISTRY_SCOPE_MISMATCH");
            String authority = registry.path("authorityCatalogId").asText();
            need(authority.matches("authority-catalog-[a-z0-9._-]+"), "PP4_REGISTERED_RULE_AUTHORITY_INVALID");
            try (var source = Pp4RegisteredRuleInputs.class.getResourceAsStream("/acmg/" + authority + ".json")) {
                if (source == null) throw new IOException("PP4_REGISTERED_RULE_AUTHORITY_MISSING");
                byte[] original = source.readAllBytes(); JsonNode catalog = JSON.readTree(original);
                need(catalog != null && Pp4ProductionMethodRegistration.rawSha(original).equals(registry.path("authorityCatalogSha256").asText())
                        && catalog.path("authorityCutoff").equals(registry.path("authorityCutoff")), "PP4_REGISTERED_RULE_AUTHORITY_PIN_MISMATCH");
                if (catalog == null) throw new IOException("PP4_REGISTERED_RULE_AUTHORITY_INVALID");
                need(policy.path("authoritySourceIds").size() == 1 && policy.path("scientificSources").size() == 1,
                        "PP4_REGISTERED_RULE_AUTHORITY_SCOPE_INVALID");
                JsonNode expected = policy.path("scientificSources").get(0); boolean found = false;
                if (expected == null) throw new IOException("PP4_REGISTERED_RULE_AUTHORITY_INVALID");
                need("authority-clingen-svi-pp1-bs4-pp4-2023".equals(expected.path("sourceId").asText())
                        && "2023 publication".equals(expected.path("version").asText())
                        && "RELEASED".equals(expected.path("publicationStatus").asText())
                        && "ELIGIBLE".equals(expected.path("normativeEligibility").asText())
                        && "2023-12-15".equals(expected.path("publicationDate").asText())
                        && "2026-09-11".equals(expected.path("authorityCutoff").asText()), "PP4_REGISTERED_RULE_AUTHORITY_SCOPE_INVALID");
                for (var entry : catalog.path("sources")) if (entry.path("authoritySourceId").equals(expected.path("sourceId"))) {
                    found = expected.path("sourceId").equals(policy.path("authoritySourceIds").get(0))
                            && expected.path("url").equals(entry.path("sourceUrl")) && expected.path("version").equals(entry.path("documentVersion"))
                            && expected.path("sha256").equals(entry.path("snapshotSha256")) && entry.path("snapshotSha256").equals(entry.at("/sourceLocator/artifactSha256"))
                            && expected.path("publicationDate").equals(entry.path("publicationDate")) && expected.path("publicationStatus").equals(entry.path("publicationStatus"))
                            && expected.path("normativeEligibility").equals(entry.path("normativeEligibility")) && expected.path("authorityCutoff").equals(catalog.path("authorityCutoff"));
                }
                need(found, "PP4_REGISTERED_RULE_AUTHORITY_SOURCE_MISMATCH");
            }
        }
    }
    private static void need(boolean condition, String reason) throws IOException { Pp4ProductionMethodRegistration.need(condition, reason); }
}
