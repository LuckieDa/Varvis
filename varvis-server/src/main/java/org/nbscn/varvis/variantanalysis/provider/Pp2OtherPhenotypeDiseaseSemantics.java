package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Finite native-expert disease semantics. Neither identifier inequality nor a clinical symptom establishes a distinct phenotype. */
final class Pp2OtherPhenotypeDiseaseSemantics implements Pp2OtherPhenotypeSource.DiseaseRelationResolver {
    private final ObjectMapper mapper;
    Pp2OtherPhenotypeDiseaseSemantics(ObjectMapper mapper) { this.mapper = mapper; }
    @Override public ObjectNode identity() {
        return mapper.createObjectNode().put("methodVersion", "pp2-native-expert-disease-semantics-v2")
                .put("sameFamily", "EXACT_DISEASE_OR_EXPLICIT_HCM_FAMILY_LABEL")
                .put("distinct", "SINGLE_NATIVE_EXPERT_MONOGENIC_DIABETES_OR_NAMED_PRIMARY_DCM_VS_HCM")
                .put("unknown", "DIFFERENT_ID_GENERIC_CARDIOMYOPATHY_DILATION_ENDSTAGE_HCM_MIXED_OR_UNMAPPED")
                .put("usesGeneOrVariantAllowlist", false).put("fullOntologyDisjointnessClaimed", false);
    }
    @Override public Pp2OtherPhenotypeSource.Relation resolve(JsonNode facts, ObjectNode context, ObjectNode policy) {
        var unknown = Pp2OtherPhenotypeSource.Relation.UNRESOLVED;
        String current = context.at("/disease/mondoId").asText(), reported = facts.at("/disease/id").asText();
        if (!current.matches("MONDO:[0-9]{7}") || !reported.matches("MONDO:[0-9]{7}")) return unknown;
        var ids = new HashSet<String>(); int[] traits = new int[2]; collect(facts.at("/originalAssertion/condition"), ids, traits);
        // A mixed traitset cannot be narrowed to the favorable disease label of its summary.
        if (ids.size() != 1 || !ids.contains(reported) || traits[0] != 1 || traits[1] != 1) return unknown;
        String label = normal(facts.at("/disease/label").asText());
        String summary = normal(facts.at("/originalExpertSummary/condition").asText());
        if (label.isEmpty() || !label.equals(summary)) return unknown;
        boolean hcm = label.matches("(?:familial )?hypertrophic cardiomyopathy(?: [0-9]+)?");
        boolean namedDcm = isNamedPrimaryDcm(label, reported);
        // Known concept/label contradictions must not be cleared by exact ID equality.
        if (("MONDO:0005045".equals(reported) && (namedDcm || label.equals("dilated cardiomyopathy")))
                || ("MONDO:0005021".equals(reported) && hcm)) return Pp2OtherPhenotypeSource.Relation.CONFLICT;
        if (current.equals(reported)) return Pp2OtherPhenotypeSource.Relation.SAME_HCM_FAMILY;
        if (!"MONDO:0005045".equals(current)) return unknown;
        if (hcm) return Pp2OtherPhenotypeSource.Relation.SAME_HCM_FAMILY;
        // The caller has already replayed the current, unique P/LP expert variant-condition
        // assertion against original SCV, SEPIO and summary. This is not a symptom search.
        if (namedDcm) return Pp2OtherPhenotypeSource.Relation.DISTINCT;
        // This is an explicit, unique disease attribution in a qualified expert record, not a patient with an incidental diabetes finding.
        if (label.equals("monogenic diabetes") || label.matches("maturity[- ]onset diabetes of the young(?: (?:type )?[0-9]+)?")
                || label.matches("mody(?: (?:type )?[0-9]+)?")) return Pp2OtherPhenotypeSource.Relation.DISTINCT;
        return unknown;
    }
    private static boolean isNamedPrimaryDcm(String label, String id) {
        return ("MONDO:0005021".equals(id) && label.equals("dilated cardiomyopathy"))
                || label.matches("(?:primary|familial(?: isolated)?|isolated) dilated cardiomyopathy")
                || label.matches("dilated cardiomyopathy(?: type)? [0-9]+[a-z]{0,3}")
                || label.matches("cardiomyopathy, dilated, (?:type )?[0-9]+[a-z]{0,3}");
    }
    private static void collect(JsonNode value, Set<String> ids, int[] traits) {
        if (value.isArray()) { for (var item : value) collect(item, ids, traits); return; }
        if (!value.isObject()) return;
        if ("TraitSet".equals(value.path("tag").asText())) traits[0]++;
        if ("Trait".equals(value.path("tag").asText())) traits[1]++;
        if ("XRef".equals(value.path("tag").asText()) && "MONDO".equals(value.at("/attributes/DB").asText()))
            ids.add(normalizedMondo(value.at("/attributes/ID").asText()));
        for (var child : value.path("children")) collect(child, ids, traits);
    }
    private static String normal(String value) { return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    static String normalizedMondo(String value) {
        return value.matches("[0-9]{7}") ? "MONDO:" + value : value.matches("MONDO:[0-9]{7}") ? value : "UNRESOLVED";
    }
}
