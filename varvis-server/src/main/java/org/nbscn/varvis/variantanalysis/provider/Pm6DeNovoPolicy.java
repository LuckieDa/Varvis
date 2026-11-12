package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Conservative tested-parentage subset, not all clinical PM6 and never a PS2 fallback. */
public final class Pm6DeNovoPolicy {
    public static final String MODE = "SINGLE_ASSUMED_TRIO_AD_SNV_TESTED_PARENTAGE";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    public Pm6DeNovoPolicy(ObjectMapper mapper) { this.mapper = mapper; adapter = new RuntimeEvidenceV2Adapter(mapper); }
    @Nullable String validate(JsonNode context, ObjectNode facts, ObjectNode basis, ObjectNode assessment, ObjectNode policy) {
        if (!adapter.canonicalSha256(facts).equals(basis.path("factsSha256").asText())) return "PM6_PARENTAGE_FACTS_PIN_MISMATCH";
        String basisSha = adapter.canonicalSha256(basis);
        if (!basisSha.equals(assessment.path("parentageBasisSha256").asText()) || !basisSha.equals(policy.path("parentageBasisSha256").asText())) return "PM6_PARENTAGE_BASIS_PIN_MISMATCH";
        return new FamilyDeNovoPrerequisites(mapper, "PM6_", MODE).validate(context, facts, assessment, policy,
                (f, members, proband, ids) -> parentage(f, basis, members, proband, ids));
    }
    @Nullable private String parentage(JsonNode facts, ObjectNode basis, Map<String, JsonNode> members, JsonNode proband, Set<String> factSourceIds) {
        JsonNode binding = facts.path("binding");
        if (!binding.equals(basis.path("binding"))) return "PM6_PARENTAGE_BINDING_MISMATCH";
        if (basis.path("relations").size() != 2 || facts.path("parentage").size() != 2) return "PM6_BOTH_PARENTAGE_REQUIRED";
        Set<String> sources = new HashSet<>();
        ObjectNode projection = basis.deepCopy(); projection.remove("sources");
        // Only the sources container is cyclic. All facts, results, limits and per-relation reviews are in the report.
        for (JsonNode s : basis.path("sources")) {
            if (!sources.add(s.path("sourceId").asText())) return "PM6_PARENTAGE_SOURCE_ID_CONFLICT";
            byte[] raw = s.path("rawUtf8").asText().getBytes(StandardCharsets.UTF_8);
            if (!FamilyDeNovoJson.sha256(raw).equals(s.path("rawSha256").asText())) return "PM6_PARENTAGE_SOURCE_RAW_PIN_MISMATCH";
            String reportSha256;
            try {
                ObjectNode report = new FamilyDeNovoJson("PM6_").parse(mapper, raw);
                reportSha256 = adapter.canonicalSha256(report);
            }
            catch (IllegalArgumentException invalid) { return "PM6_PARENTAGE_SOURCE_DOCUMENT_INVALID"; }
            if (!reportSha256.equals(s.path("canonicalSha256").asText())) return "PM6_PARENTAGE_SOURCE_CANONICAL_PIN_MISMATCH";
            if (!adapter.canonicalSha256(projection).equals(reportSha256)) return "PM6_PARENTAGE_SOURCE_FACTS_MISMATCH";
            if (!FamilyDeNovoPrerequisites.date(s.path("acquiredAt"))) return "PM6_PARENTAGE_SOURCE_DATE_INVALID";
        }
        if (sources.isEmpty()) return "PM6_PARENTAGE_SOURCE_MISSING";
        Map<String, JsonNode> original = new HashMap<>();
        for (JsonNode p : facts.path("parentage")) {
            JsonNode member = members.get(p.path("parentRole").asText());
            if (original.put(p.path("parentRole").asText(), p) != null || member == null || !binding.equals(p.path("binding"))
                    || !member.path("memberId").equals(p.path("parentMemberId")) || !proband.path("memberId").equals(p.path("probandMemberId"))) return "PM6_PARENTAGE_MEMBER_MISMATCH";
            if (!FamilyDeNovoPrerequisites.sources(p.path("sourceIds"), factSourceIds)) return "PM6_PARENTAGE_FACT_SOURCE_MISSING";
        }
        Set<String> roles = new HashSet<>(); int assumed = 0;
        for (JsonNode r : basis.path("relations")) {
            String role = r.path("parentRole").asText(); JsonNode member = members.get(role), p = original.get(role);
            if (!roles.add(role) || member == null || p == null || !binding.equals(r.path("binding"))
                    || !member.path("memberId").equals(r.path("parentMemberId")) || !member.path("sampleId").equals(r.path("parentSampleId"))
                    || !proband.path("memberId").equals(r.path("probandMemberId")) || !proband.path("sampleId").equals(r.path("probandSampleId"))) return "PM6_PARENTAGE_MEMBER_MISMATCH";
            if (!"COMPLETED".equals(r.path("testStatus").asText())) return "PM6_PARENTAGE_TEST_NOT_COMPLETED";
            JsonNode review = r.path("review");
            if (!"REVIEWED".equals(review.path("status").asText()) || !FamilyDeNovoPrerequisites.date(review.path("date"))
                    || !FamilyDeNovoPrerequisites.sources(review.path("sourceIds"), sources) || !FamilyDeNovoPrerequisites.sources(r.path("sourceIds"), sources)) return "PM6_PARENTAGE_REVIEW_OR_SOURCE_INCOMPLETE";
            String conclusion = r.path("conclusion").asText();
            if (!Set.of("ASSUMED", "CONFIRMED").contains(conclusion)) return "PM6_PARENTAGE_CONCLUSION_UNRESOLVED_OR_EXCLUDED";
            String expected = "ASSUMED".equals(conclusion) ? "UNCONFIRMED" : "CONFIRMED";
            if (!expected.equals(p.path("status").asText())) return "PM6_PARENTAGE_FACTS_SIDECAR_CONFLICT";
            if ("ASSUMED".equals(conclusion)) assumed++;
        }
        return assumed == 0 ? "PM6_BOTH_CONFIRMED_OUTSIDE_PM6_NO_PS2_FALLBACK" : null;
    }
    static BigDecimal points(JsonNode facts) {
        if (negativeReason(facts) != null) return BigDecimal.ZERO;
        return switch (facts.at("/clinical/phenotypeCategory").asText()) {
            case "HIGHLY_SPECIFIC" -> BigDecimal.ONE;
            case "CONSISTENT" -> new BigDecimal("HIGH".equals(facts.at("/clinical/heterogeneity").asText()) ? "0.25" : "0.5");
            default -> throw new IllegalStateException("Unvalidated phenotype");
        };
    }
    @Nullable static String negativeReason(JsonNode facts) {
        for (JsonNode r : facts.path("records")) {
            if ("PROBAND".equals(r.path("role").asText()) && "HOM_REF".equals(r.at("/observation/call").asText())) return "PM6_PROBAND_TARGET_NOT_OBSERVED";
            if (!"PROBAND".equals(r.path("role").asText()) && !"HOM_REF".equals(r.at("/observation/call").asText())) return "PM6_PARENTAL_TARGET_OBSERVED";
        }
        return "NOT_CONSISTENT".equals(facts.at("/clinical/phenotypeCategory").asText()) ? "PM6_REVIEWED_PHENOTYPE_NOT_CONSISTENT" : null;
    }
}
