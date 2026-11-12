package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Set;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.Strength;

/** Executable condition families selected by the existing source-pinned policy.
 * This is knowledge configuration, never a per-case extraction permission. */
final class DeNovoEventPolicy {
    static final String VERSION = "denovo-special-policy-1.0.0";
    enum Mode { SVI_1_1, SVI_1_1_SECOND_ALLELE_REQUIRED, SVI_1_1_COMBINED_PS2, TISSUE_DIFFERENTIAL_PS2, RASOPATHY_POINTS,
        SVI_1_1_PHENOTYPE_ONLY, SVI_1_1_PUBLISHED_POINTS }
    private final Mode mode;
    private final List<String> sources;
    private final List<String> requiredAbsentFindings;
    private final Strength maximumPm6Strength;
    private final boolean trioExomeConfirmsParentage;

    private DeNovoEventPolicy(Mode mode, List<String> sources, List<String> requiredAbsentFindings,
                              Strength maximumPm6Strength, boolean trioExomeConfirmsParentage) {
        this.mode = mode;
        this.sources = List.copyOf(sources);
        this.requiredAbsentFindings = List.copyOf(requiredAbsentFindings);
        this.maximumPm6Strength = maximumPm6Strength;
        this.trioExomeConfirmsParentage = trioExomeConfirmsParentage;
    }
    Mode mode() { return mode; }
    List<String> sourceIds() { return sources; }
    boolean tissueRule() { return mode == Mode.TISSUE_DIFFERENTIAL_PS2; }
    boolean combinedPs2() { return mode == Mode.SVI_1_1_COMBINED_PS2 || tissueRule(); }
    boolean rasopathyPoints() { return mode == Mode.RASOPATHY_POINTS; }
    boolean phenotypeOnly() { return mode == Mode.SVI_1_1_PHENOTYPE_ONLY; }
    boolean publishedPoints() { return mode == Mode.SVI_1_1_PUBLISHED_POINTS; }
    List<String> requiredAbsentFindings() { return requiredAbsentFindings; }
    Strength maximumPm6Strength() { return maximumPm6Strength; }
    boolean trioExomeConfirmsParentage() { return trioExomeConfirmsParentage; }

    static DeNovoEventPolicy read(JsonNode node, String expertStatus, Set<String> sourceIds) {
        var json = new FamilyDeNovoJson("DENOVO_EVENT_POLICY_");
        boolean published = Mode.SVI_1_1_PUBLISHED_POINTS.name().equals(node.path("mode").asText());
        json.shape(node, "mode:s sourceIds:a"
                + (published ? " requiredAbsentFindings:a maximumPm6Strength:s trioExomeConfirmsParentage:b" : ""));
        Mode mode;
        try { mode = Mode.valueOf(node.path("mode").asText()); }
        catch (IllegalArgumentException invalid) { throw json.invalid("MODE_UNSUPPORTED"); }
        if (mode != Mode.SVI_1_1 && !"APPLICABLE_IMPLEMENTED".equals(expertStatus))
            throw json.invalid("EXACT_EXPERT_DELEGATION_REQUIRED");
        json.strings(node.path("sourceIds"));
        if (node.path("sourceIds").isEmpty() || node.path("sourceIds").size() > 30) throw json.invalid("SOURCE_REQUIRED");
        var refs = new java.util.ArrayList<String>();
        for (var id : node.path("sourceIds")) {
            if (!sourceIds.contains(id.asText()) || refs.contains(id.asText())) throw json.invalid("SOURCE_UNBOUND");
            refs.add(id.asText());
        }
        if (!published) return new DeNovoEventPolicy(mode, refs, List.of(), Strength.VERY_STRONG, false);
        var absent = findingKeys(node.path("requiredAbsentFindings"), "(MOTHER|FATHER|FAMILY)", json);
        json.enumeration(node, "maximumPm6Strength", "SUPPORTING MODERATE STRONG VERY_STRONG");
        return new DeNovoEventPolicy(mode, refs, absent, Strength.valueOf(node.path("maximumPm6Strength").asText()),
                node.path("trioExomeConfirmsParentage").asBoolean());
    }

    static List<String> findingKeys(JsonNode node, String roles, FamilyDeNovoJson json) {
        json.strings(node);
        var keys = new java.util.LinkedHashSet<String>();
        if (node.isEmpty() || node.size() > 24) throw json.invalid("FINDINGS_REQUIRED_OR_LIMIT");
        for (var value : node) if (!value.asText().matches(roles + ":[A-Z][A-Z0-9_]{1,79}") || !keys.add(value.asText()))
            throw json.invalid("FINDING_KEY_INVALID");
        return List.copyOf(keys);
    }
}
