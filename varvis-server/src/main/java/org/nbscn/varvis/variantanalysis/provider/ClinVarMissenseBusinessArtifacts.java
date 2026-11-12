package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;

/** Versioned CSpec selection and existing evidence-v2 bundle envelope for the missense slice. */
public final class ClinVarMissenseBusinessArtifacts {
    public static final String AUTHORITY_RESOURCE = "/acmg/authority-catalog-ps1-pm5-2026-09-09.json";
    public static final String REGISTRY_RESOURCE = "/acmg/rule-registry-v2/acmg-rule-registry-ps1-pm5-2026-09-09.json";
    public static final String OVERLAY_RESOURCE = "/acmg/cspec-overlay-registry-v1/cspec-overlay-registry-ps1-pm5-2026-09-09.json";
    private ClinVarMissenseBusinessArtifacts() { }

    public static ObjectNode selection(ObjectMapper mapper, ObjectNode context) {
        return selection(mapper, context, OVERLAY_RESOURCE);
    }

    static ObjectNode selection(ObjectMapper mapper, ObjectNode context, String overlayResource) {
        ObjectNode overlay = ClinVarMissenseBusinessRuntime.read(mapper, overlayResource);
        JsonNode selected = overlay.path("overlays").get(0);
        if (selected == null) throw new IllegalStateException("Missense CSpec overlay missing");
        JsonNode selector = selected.path("selector");
        boolean match = selector.path("hgncId").equals(context.at("/gene/hgncId"))
                && selector.path("mondoId").equals(context.at("/disease/mondoId"))
                && selector.path("moi").equals(context.path("moi"))
                && selector.at("/transcript/accession").equals(context.at("/transcript/accession"))
                && selector.at("/transcript/version").equals(context.at("/transcript/version"));
        ObjectNode result = mapper.createObjectNode().put("schemaVersion", "cspec-selection-v1")
                .put("canonicalizationProfile", "varvis-json-c14n-v1").put("contextId", context.path("contextId").asText())
                .put("overlayRegistryId", overlay.path("registryId").asText()).put("overlayRegistrySha256", resourceSha(overlayResource))
                .put("policyId", "CSPEC_EXACT_CONTEXT_SELECTION_V1").put("outcome", match ? "SINGLE_SELECTABLE_MATCH" : "ZERO_MATCH");
        ArrayNode candidates = result.putArray("candidateOverlayIds");
        if (match) {
            candidates.add(selected.path("overlayId").asText()); result.put("selectedOverlayId", selected.path("overlayId").asText())
                    .put("selectedOverlaySha256", selected.path("snapshotSha256").asText());
        }
        result.put("selectionId", "cspec-selection-sha256-" + new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(result));
        result.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        result.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        return result;
    }

    public static ObjectNode bundle(ObjectMapper mapper, ObjectNode result) {
        return bundle(mapper, result, REGISTRY_RESOURCE, OVERLAY_RESOURCE, "varvis-ps1-pm5-business-entry");
    }

    static ObjectNode bundle(ObjectMapper mapper, ObjectNode result, String registryResource,
                             String overlayResource, String producerId) {
        return bundle(mapper, result, ClinVarMissenseBusinessRuntime.read(mapper, registryResource),
                registryResource, overlayResource, producerId, Instant.now().toString());
    }

    static ObjectNode bundle(ObjectMapper mapper, ObjectNode result, ObjectNode registry, String registryResource,
                             String overlayResource, String producerId, String createdAt) {
        RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
        ObjectNode context = (ObjectNode) result.path("context");
        ObjectNode authority = mapper.createObjectNode();
        for (String key : List.of("authorityCatalogId", "authorityCatalogSha256", "authorityCutoff", "asOf")) authority.set(key, registry.path(key));
        authority.put("ruleRegistryId", registry.path("registryId").asText()).put("ruleRegistrySha256", resourceSha(registryResource));
        authority.put("cspecOverlayRegistryId", result.at("/selection/overlayRegistryId").asText())
                .put("cspecOverlayRegistrySha256", resourceSha(overlayResource));
        ObjectNode combination = mapper.createObjectNode().put("schemaVersion", "combination-result-v2")
                .put("contextId", context.path("contextId").asText()).put("executionStatus", "NOT_RUN")
                .put("result", "NO_RESULT").put("reasonCode", "E1_COMBINATION_NOT_IMPLEMENTED")
                .put("provisional", true).put("clinicalSignoffStatus", "NOT_SIGNED");
        combination.set("criterionEvaluationIds", ids(mapper, result.path("criterionEvaluations"), "evaluationId"));
        combination.put("combinationId", "combination-sha256-" + adapter.canonicalSha256(combination));
        combination.putObject("conflict").put("status", "NONE").putArray("conflictIds");
        combination.putObject("review").put("status", "NOT_REQUIRED").put("decision", "NONE").putArray("reviewRecordIds");
        ObjectNode bundle = mapper.createObjectNode().put("schemaVersion", "evidence-bundle-v2")
                .put("sourceOfTruth", "ACMG_EVIDENCE_V2").put("dataClass", context.path("dataClass").asText())
                .put("createdAt", createdAt).put("candidateOrderingChanged", false).put("clinicalConclusionClaimed", false);
        bundle.putObject("producer").put("producerId", producerId).put("producerVersion", "1.0.0")
                .put("canonicalizationProfile", "varvis-json-c14n-v1");
        bundle.set("authorityBinding", authority); bundle.set("context", context.deepCopy()); bundle.set("cspecSelection", result.path("selection").deepCopy());
        bundle.set("atomicEvidence", result.path("atomicEvidence").deepCopy()); bundle.set("criterionEvaluations", result.path("criterionEvaluations").deepCopy());
        bundle.set("reviewRecords", result.has("reviewRecords") ? result.path("reviewRecords").deepCopy() : mapper.createArrayNode());
        bundle.putArray("conflicts"); bundle.putArray("legacyAdapterRecords"); bundle.set("combination", combination);
        ObjectNode identity = mapper.createObjectNode();
        for (String key : List.of("sourceOfTruth", "dataClass", "producer", "authorityBinding", "candidateOrderingChanged", "clinicalConclusionClaimed")) identity.set(key, bundle.path(key));
        identity.set("contextId", context.path("contextId")); identity.set("cspecSelectionId", result.at("/selection/selectionId"));
        identity.set("evidenceIds", ids(mapper, bundle.path("atomicEvidence"), "evidenceId"));
        identity.set("evaluationIds", ids(mapper, bundle.path("criterionEvaluations"), "evaluationId"));
        identity.set("reviewRecordIds", ids(mapper, bundle.path("reviewRecords"), "reviewRecordId"));
        identity.putArray("conflictIds"); identity.putArray("adapterRecordIds"); identity.set("combinationId", combination.path("combinationId"));
        bundle.put("bundleId", "bundle-sha256-" + adapter.canonicalSha256(identity));
        return bundle;
    }
    private static ArrayNode ids(ObjectMapper mapper, JsonNode values, String field) {
        TreeSet<String> sorted = new TreeSet<>(); values.forEach(value -> sorted.add(value.path(field).asText()));
        ArrayNode ids = mapper.createArrayNode(); sorted.forEach(ids::add); return ids;
    }
    public static String resourceSha(String path) {
        try (InputStream input = ClinVarMissenseBusinessArtifacts.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing missense artifact: " + path);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (IOException | NoSuchAlgorithmException failure) { throw new IllegalStateException("Cannot hash missense artifact: " + path, failure); }
    }
}
