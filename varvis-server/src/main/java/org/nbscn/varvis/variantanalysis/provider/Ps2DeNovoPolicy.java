package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

/** PS2-specific relationship gate and scoring; no PM6 fallback. */
public final class Ps2DeNovoPolicy {
    public static final String MODE = "SINGLE_CONFIRMED_TRIO_AD_SNV";
    private final FamilyDeNovoPrerequisites prerequisites;
    public Ps2DeNovoPolicy(ObjectMapper mapper) { prerequisites = new FamilyDeNovoPrerequisites(mapper, "PS2_", MODE); }
    @Nullable String validate(JsonNode context, ObjectNode facts, ObjectNode assessment, ObjectNode policy) {
        return prerequisites.validate(context, facts, assessment, policy, Ps2DeNovoPolicy::parentage);
    }
    @Nullable private static String parentage(JsonNode facts, Map<String, JsonNode> members, JsonNode proband, Set<String> sourceIds) {
        JsonNode b = facts.path("binding");
        if (facts.path("parentage").size() != 2) return "PS2_BOTH_PARENTAGE_REQUIRED";
        Set<String> parents = new HashSet<>();
        for (JsonNode p : facts.path("parentage")) {
            JsonNode member = members.get(p.path("parentRole").asText());
            if (member == null || !parents.add(p.path("parentRole").asText()) || !b.equals(p.path("binding"))
                    || !p.path("parentMemberId").equals(member.path("memberId")) || !p.path("probandMemberId").equals(proband.path("memberId"))) return "PS2_PARENTAGE_MEMBER_MISMATCH";
            if (!"CONFIRMED".equals(p.path("status").asText()) || !FamilyDeNovoPrerequisites.sources(p.path("sourceIds"), sourceIds)) return "PS2_PARENTAGE_NOT_CONFIRMED_NO_PM6";
        }
        return null;
    }
    static BigDecimal points(JsonNode facts) {
        for (JsonNode record : facts.path("records")) {
            String role = record.path("role").asText(), call = record.at("/observation/call").asText();
            if (("PROBAND".equals(role) && "HOM_REF".equals(call)) || (!"PROBAND".equals(role) && !"HOM_REF".equals(call))) return BigDecimal.ZERO;
        }
        return switch (facts.at("/clinical/phenotypeCategory").asText()) {
            case "HIGHLY_SPECIFIC" -> new BigDecimal("2");
            case "CONSISTENT" -> new BigDecimal("HIGH".equals(facts.at("/clinical/heterogeneity").asText()) ? "0.5" : "1");
            case "NOT_CONSISTENT" -> BigDecimal.ZERO;
            default -> throw new IllegalStateException("Unvalidated phenotype");
        };
    }
}
