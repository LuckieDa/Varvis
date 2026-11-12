package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Explicit source-verified research use. This is not an external or clinical approval. */
final class MissenseResearchQualification {
    static final String MODE = "SOURCE_VERIFIED_PROJECT_RESEARCH";
    static final String SCHEMA = "missense-material-qualification-v2";
    static final String VERSION = "1.1.0";
    private static final List<String> HEADER = List.of("schemaVersion", "bundleId", "bundleVersion", "review", "activation");
    private MissenseResearchQualification() { }

    static ObjectNode preserve(ObjectMapper mapper, ObjectNode candidate) {
        validateCandidate(mapper, candidate);
        var metadata = candidate.deepCopy();
        metadata.remove(List.of("bundleHeader", "catalog", "policies", "missenseMaterials"));
        var proof = mapper.createObjectNode().put("schemaVersion", SCHEMA).put("qualificationMode", MODE)
                .put("qualificationVersion", VERSION).put("scientificApprovalGranted", false)
                .put("candidateSha256", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(candidate));
        proof.set("candidateMetadata", metadata);
        return proof;
    }

    static void validate(ObjectMapper mapper, ObjectNode bundle, ObjectNode proof) {
        require(SCHEMA.equals(proof.path("schemaVersion").asText())
                && MissenseComparisonPolicy.only(proof, Set.of("schemaVersion", "qualificationMode", "qualificationVersion",
                    "scientificApprovalGranted", "candidateSha256", "candidateMetadata"))
                && MODE.equals(proof.path("qualificationMode").asText())
                && (VERSION.equals(proof.path("qualificationVersion").asText())
                    || "1.0.0".equals(proof.path("qualificationVersion").asText())
                        && MissenseComparisonRuntime.PM5.equals(proof.at("/candidateMetadata/criterion").asText()))
                && proof.path("scientificApprovalGranted").isBoolean() && !proof.path("scientificApprovalGranted").booleanValue()
                && proof.path("candidateMetadata") instanceof ObjectNode, "MISSENSE_RESEARCH_QUALIFICATION_INVALID");
        var candidate = ((ObjectNode) proof.path("candidateMetadata")).deepCopy();
        require(MissenseComparisonPolicy.only(candidate, Set.of("schemaVersion", "draft", "dataClass", "purpose", "criterion",
                "backgrounds", "sources", "comparatorProfile", "contextUse", "releaseMode")), "MISSENSE_RESEARCH_METADATA_INVALID");
        var header = mapper.createObjectNode();
        HEADER.forEach(field -> header.set(field, bundle.path(field).deepCopy()));
        candidate.set("bundleHeader", header); candidate.set("catalog", bundle.path("catalog").deepCopy());
        candidate.set("policies", bundle.path("policies").deepCopy());
        candidate.set("missenseMaterials", bundle.at("/materials/missenseMaterials").deepCopy());
        require(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(candidate).equals(proof.path("candidateSha256").asText()),
                "MISSENSE_RESEARCH_CANDIDATE_BINDING_MISMATCH");
        validateCandidate(mapper, candidate);
    }

    private static void validateCandidate(ObjectMapper mapper, ObjectNode candidate) {
        require("missense-preparation-candidate-v1".equals(candidate.path("schemaVersion").asText())
                && candidate.path("draft").isBoolean() && candidate.path("draft").booleanValue()
                && MODE.equals(candidate.path("releaseMode").asText()), "MISSENSE_RESEARCH_CANDIDATE_INVALID");
        String criterion = candidate.path("criterion").asText();
        require(Set.of(MissenseComparisonRuntime.PS1, MissenseComparisonRuntime.PM5).contains(criterion), "MISSENSE_RESEARCH_CRITERION_UNSUPPORTED");
        String dataClass = candidate.path("dataClass").asText(), purpose = candidate.path("purpose").asText();
        require("PUBLIC_REFERENCE".equals(dataClass) && "PROJECT_RESEARCH".equals(purpose)
                || "SYNTHETIC".equals(dataClass) && "TEST_ONLY".equals(purpose), "MISSENSE_RESEARCH_PURPOSE_REQUIRED");
        MissenseMaterialQualification.validateUse(candidate.path("contextUse"), dataClass, purpose);
        require(candidate.at("/bundleHeader/activation/automaticTrigger").isBoolean()
                && !candidate.at("/bundleHeader/activation/automaticTrigger").booleanValue()
                && candidate.at("/bundleHeader/activation/classificationEnabled").isBoolean()
                && !candidate.at("/bundleHeader/activation/classificationEnabled").booleanValue(), "MISSENSE_RESEARCH_ACTIVATION_INVALID");
        require(candidate.path("comparatorProfile").equals(ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper)), "COMPARATOR_PROFILE_MISMATCH");
        require(candidate.path("policies").isArray() && !candidate.path("policies").isEmpty()
                && candidate.path("policies").size() <= 32
                && candidate.path("backgrounds").size() == candidate.path("policies").size()
                && candidate.path("missenseMaterials").size() == candidate.path("policies").size(), "MISSENSE_RESEARCH_CONTENTS_REQUIRED");
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        for (JsonNode policy : candidate.path("policies")) {
            require(MissenseExpertClassificationAdoption.SOURCE_MODE.equals(policy.at("/expertSourceAdoption/mode").asText())
                    && MissenseRnaApplicability.automated(policy.path("rnaApplicability")), "MISSENSE_RESEARCH_AUTOMATED_SOURCE_ROUTE_REQUIRED");
            issue(MissenseComparisonPolicy.issue(policy, criterion));
            JsonNode material = candidate.path("missenseMaterials").path(policy.path("materialsSha256").asText());
            require("SOURCE_VERIFIED".equals(material.at("/referenceAudit/status").asText())
                    && material.at("/referenceAudit/references").isArray() && !material.at("/referenceAudit/references").isEmpty(),
                    "MISSENSE_RESEARCH_VERIFIED_REFERENCES_REQUIRED");
            issue(MissenseComparisonPolicy.materialsIssue(policy, material, criterion, adapter));
            int matches = 0;
            for (JsonNode row : candidate.path("backgrounds")) {
                if (!adapter.canonicalSha256(policy).equals(row.path("policySha256").asText())) continue;
                matches++;
                require(policy.path("materialsSha256").equals(row.path("materialSha256"))
                        && adapter.canonicalSha256(row.path("background")).equals(row.path("backgroundSha256").asText()),
                        "MISSENSE_RESEARCH_BACKGROUND_BINDING_INVALID");
                for (String field : List.of("assembly", "hgncId", "geneSymbol", "protein", "mondoId", "moi"))
                    require(row.path("background").path(field).equals(policy.path("scope").path(field)), "MISSENSE_RESEARCH_BACKGROUND_BINDING_INVALID");
                require(row.at("/background/transcript").asText().equals(policy.at("/scope/transcripts/0/accession").asText()
                        + "." + policy.at("/scope/transcripts/0/version").asInt()), "MISSENSE_RESEARCH_BACKGROUND_BINDING_INVALID");
            }
            require(matches == 1, "MISSENSE_RESEARCH_BACKGROUND_BINDING_INVALID");
            for (JsonNode source : policy.path("sources")) {
                boolean bound = false;
                for (JsonNode registered : candidate.path("sources")) if (source.equals(registered)) bound = true;
                require(bound, "MISSENSE_RESEARCH_SOURCE_UNBOUND");
            }
        }
    }
    private static void issue(@Nullable String reason) { if (reason != null) throw new IllegalArgumentException(reason); }
    private static void require(boolean condition, String reason) { if (!condition) throw new IllegalArgumentException(reason); }
}
