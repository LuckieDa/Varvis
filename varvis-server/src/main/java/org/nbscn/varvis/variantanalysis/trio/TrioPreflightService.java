package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class TrioPreflightService {

    public TrioPreflightReport preflight(TrioInputMode mode, TrioAnalysisInput input) {
        List<TrioPreflightIssue> issues = new ArrayList<TrioPreflightIssue>();
        if (input == null) {
            issues.add(error("TRIO_INPUT_MISSING", "Trio input is required."));
            return new TrioPreflightReport(mode, false, 0, 0, issues);
        }

        Map<TrioRole, Integer> roleCounts = roleCounts(input);
        requireRole(issues, roleCounts, TrioRole.PROBAND);
        requireRole(issues, roleCounts, TrioRole.FATHER);
        requireRole(issues, roleCounts, TrioRole.MOTHER);
        warnOnDuplicateRoles(issues, roleCounts);

        if (input.getVariants().isEmpty()) {
            issues.add(error("TRIO_VARIANTS_MISSING", "At least one normalized trio variant observation is required."));
        }

        for (TrioVariantObservation variant : input.getVariants()) {
            validateVariant(issues, variant);
        }

        boolean valid = true;
        for (TrioPreflightIssue issue : issues) {
            if (issue.getSeverity() == TrioPreflightSeverity.ERROR) {
                valid = false;
                break;
            }
        }
        return new TrioPreflightReport(mode, valid, input.getMembers().size(), input.getVariants().size(), issues);
    }

    private Map<TrioRole, Integer> roleCounts(TrioAnalysisInput input) {
        Map<TrioRole, Integer> result = new EnumMap<TrioRole, Integer>(TrioRole.class);
        for (TrioMember member : input.getMembers()) {
            if (member != null && member.getRole() != null) {
                Integer count = result.get(member.getRole());
                result.put(member.getRole(), count == null ? 1 : count + 1);
            }
        }
        return result;
    }

    private void requireRole(List<TrioPreflightIssue> issues, Map<TrioRole, Integer> roleCounts, TrioRole role) {
        Integer count = roleCounts.get(role);
        if (count == null || count.intValue() == 0) {
            issues.add(error("TRIO_" + role.name() + "_MISSING", role.name() + " member is required."));
        }
    }

    private void warnOnDuplicateRoles(List<TrioPreflightIssue> issues, Map<TrioRole, Integer> roleCounts) {
        for (Map.Entry<TrioRole, Integer> entry : roleCounts.entrySet()) {
            if (entry.getValue() != null && entry.getValue().intValue() > 1) {
                issues.add(error("TRIO_DUPLICATE_" + entry.getKey().name(),
                        "Each trio role must appear once; duplicate " + entry.getKey().name() + " members were supplied."));
            }
        }
    }

    private void validateVariant(List<TrioPreflightIssue> issues, TrioVariantObservation variant) {
        if (variant == null) {
            issues.add(error("TRIO_VARIANT_NULL", "Variant observation cannot be null."));
            return;
        }
        if (isBlank(variant.getVariantId())) {
            issues.add(error("TRIO_VARIANT_ID_MISSING", "Variant observation is missing a stable variant ID."));
        }
        if (isBlank(variant.getChromosome())) {
            issues.add(error("TRIO_CHROMOSOME_MISSING", "Variant observation is missing chromosome."));
        }
        if (variant.genotype(TrioRole.PROBAND) == null) {
            issues.add(error("TRIO_PROBAND_GENOTYPE_MISSING",
                    "Variant " + safeId(variant) + " is missing proband genotype."));
        }
        warnIfParentUninformative(issues, variant, TrioRole.FATHER);
        warnIfParentUninformative(issues, variant, TrioRole.MOTHER);
    }

    private void warnIfParentUninformative(List<TrioPreflightIssue> issues,
                                           TrioVariantObservation variant,
                                           TrioRole role) {
        TrioGenotypeObservation genotype = variant.genotype(role);
        if (genotype == null || genotype.isUninformative()) {
            issues.add(warning("TRIO_PARENT_GENOTYPE_UNINFORMATIVE",
                    "Variant " + safeId(variant) + " has " + role.name()
                            + " genotype missing/no-call/unobserved; this is not treated as confirmed 0/0."));
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String safeId(TrioVariantObservation variant) {
        return isBlank(variant.getVariantId()) ? "<missing-id>" : variant.getVariantId();
    }

    private TrioPreflightIssue error(String code, String message) {
        return new TrioPreflightIssue(TrioPreflightSeverity.ERROR, code, message);
    }

    private TrioPreflightIssue warning(String code, String message) {
        return new TrioPreflightIssue(TrioPreflightSeverity.WARNING, code, message);
    }
}
