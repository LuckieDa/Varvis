package org.nbscn.varvis.variantanalysis.dto;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class TrioAnalysisInputRequest {

    public static final String MODE_DISABLED = "DISABLED";
    public static final String MODE_JOINT_VCF = "JOINT_VCF";
    public static final String MODE_THREE_SINGLE_SAMPLE_VCFS = "THREE_SINGLE_SAMPLE_VCFS";

    private final boolean enabled;
    private final String inputMode;
    private final boolean evidenceOnly;
    private final String probandSampleId;
    private final String fatherSampleId;
    private final String motherSampleId;
    private final String probandSex;
    private final String fatherSex;
    private final String motherSex;
    private final Boolean probandAffected;
    private final Boolean fatherAffected;
    private final Boolean motherAffected;
    private final Integer minDepthForConfirmedRef;
    private final Integer minGqForConfirmedRef;
    private final boolean primaryVcfProvided;
    private final boolean fatherVcfProvided;
    private final boolean motherVcfProvided;

    public TrioAnalysisInputRequest(boolean enabled,
                                    String inputMode,
                                    Boolean evidenceOnly,
                                    String probandSampleId,
                                    String fatherSampleId,
                                    String motherSampleId,
                                    Integer minDepthForConfirmedRef,
                                    Integer minGqForConfirmedRef,
                                    boolean primaryVcfProvided,
                                    boolean fatherVcfProvided,
                                    boolean motherVcfProvided) {
        this(enabled, inputMode, evidenceOnly, probandSampleId, fatherSampleId, motherSampleId,
                null, null, null, null, null, null,
                minDepthForConfirmedRef, minGqForConfirmedRef,
                primaryVcfProvided, fatherVcfProvided, motherVcfProvided);
    }

    public TrioAnalysisInputRequest(boolean enabled,
                                    String inputMode,
                                    Boolean evidenceOnly,
                                    String probandSampleId,
                                    String fatherSampleId,
                                    String motherSampleId,
                                    String probandSex,
                                    String fatherSex,
                                    String motherSex,
                                    Boolean probandAffected,
                                    Boolean fatherAffected,
                                    Boolean motherAffected,
                                    Integer minDepthForConfirmedRef,
                                    Integer minGqForConfirmedRef,
                                    boolean primaryVcfProvided,
                                    boolean fatherVcfProvided,
                                    boolean motherVcfProvided) {
        this.enabled = enabled;
        this.inputMode = normalizeInputMode(inputMode);
        this.evidenceOnly = evidenceOnly == null || evidenceOnly;
        this.probandSampleId = trimToNull(probandSampleId);
        this.fatherSampleId = trimToNull(fatherSampleId);
        this.motherSampleId = trimToNull(motherSampleId);
        this.probandSex = normalizeSex(probandSex);
        this.fatherSex = normalizeSex(fatherSex);
        this.motherSex = normalizeSex(motherSex);
        this.probandAffected = probandAffected;
        this.fatherAffected = fatherAffected;
        this.motherAffected = motherAffected;
        this.minDepthForConfirmedRef = positiveOrNull(minDepthForConfirmedRef);
        this.minGqForConfirmedRef = positiveOrNull(minGqForConfirmedRef);
        this.primaryVcfProvided = primaryVcfProvided;
        this.fatherVcfProvided = fatherVcfProvided;
        this.motherVcfProvided = motherVcfProvided;
    }

    public static TrioAnalysisInputRequest disabled() {
        return new TrioAnalysisInputRequest(
                false,
                MODE_DISABLED,
                Boolean.TRUE,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getInputMode() {
        return inputMode;
    }

    public boolean isEvidenceOnly() {
        return evidenceOnly;
    }

    public String getProbandSampleId() {
        return probandSampleId;
    }

    public String getFatherSampleId() {
        return fatherSampleId;
    }

    public String getMotherSampleId() {
        return motherSampleId;
    }

    public String getProbandSex() { return probandSex; }

    public String getFatherSex() { return fatherSex; }

    public String getMotherSex() { return motherSex; }

    public Boolean getProbandAffected() { return probandAffected; }

    public Boolean getFatherAffected() { return fatherAffected; }

    public Boolean getMotherAffected() { return motherAffected; }

    public Integer getMinDepthForConfirmedRef() {
        return minDepthForConfirmedRef;
    }

    public Integer getMinGqForConfirmedRef() {
        return minGqForConfirmedRef;
    }

    public boolean isPrimaryVcfProvided() {
        return primaryVcfProvided;
    }

    public boolean isFatherVcfProvided() {
        return fatherVcfProvided;
    }

    public boolean isMotherVcfProvided() {
        return motherVcfProvided;
    }

    public boolean isDisabled() {
        return !enabled;
    }

    public List<String> validationErrors() {
        if (isDisabled()) {
            return Collections.emptyList();
        }
        List<String> errors = new ArrayList<String>();
        if (!evidenceOnly) {
            errors.add("TRIO_EVIDENCE_ONLY_REQUIRED: trio evidence must remain evidence-only.");
        }
        if (inputMode == null || MODE_DISABLED.equals(inputMode)) {
            errors.add("TRIO_INPUT_MODE_MISSING: choose JOINT_VCF or THREE_SINGLE_SAMPLE_VCFS.");
            return errors;
        }
        if (!MODE_JOINT_VCF.equals(inputMode) && !MODE_THREE_SINGLE_SAMPLE_VCFS.equals(inputMode)) {
            errors.add("TRIO_INPUT_MODE_MISSING: unsupported trio input mode " + inputMode + ".");
            return errors;
        }
        if (!primaryVcfProvided) {
            errors.add("PROBAND_SAMPLE_REQUIRED: primary/proband VCF file is required.");
        }
        if (!hasSampleMapping()) {
            errors.add("TRIO_SAMPLE_MAPPING_INCOMPLETE: proband, father and mother sample IDs are required.");
        } else if (hasDuplicateSampleMapping()) {
            errors.add("TRIO_SAMPLE_MAPPING_DUPLICATE: proband, father and mother sample IDs must be distinct.");
        }
        if (MODE_JOINT_VCF.equals(inputMode) && !primaryVcfProvided) {
            errors.add("JOINT_VCF_REQUIRED: joint VCF must be supplied as the primary VCF file.");
        }
        if (MODE_THREE_SINGLE_SAMPLE_VCFS.equals(inputMode)) {
            if (!fatherVcfProvided) {
                errors.add("FATHER_VCF_REQUIRED: father VCF file is required for three-VCF trio mode.");
            }
            if (!motherVcfProvided) {
                errors.add("MOTHER_VCF_REQUIRED: mother VCF file is required for three-VCF trio mode.");
            }
        }
        return errors;
    }

    public boolean hasValidationErrors() {
        return !validationErrors().isEmpty();
    }

    private boolean hasSampleMapping() {
        return probandSampleId != null && fatherSampleId != null && motherSampleId != null;
    }

    private boolean hasDuplicateSampleMapping() {
        return probandSampleId.equals(fatherSampleId)
                || probandSampleId.equals(motherSampleId)
                || fatherSampleId.equals(motherSampleId);
    }

    public boolean hasExplicitPedigreeAttributes() {
        return isExplicitSex(probandSex) && isExplicitSex(fatherSex) && isExplicitSex(motherSex)
                && probandAffected != null && fatherAffected != null && motherAffected != null;
    }

    public boolean hasValidParentSexes() {
        return "MALE".equals(fatherSex) && "FEMALE".equals(motherSex);
    }

    private static boolean isExplicitSex(String value) {
        return "MALE".equals(value) || "FEMALE".equals(value);
    }

    private static String normalizeSex(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static String normalizeInputMode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        if ("THREE_VCFS".equals(normalized) || "THREE_SINGLE_SAMPLE".equals(normalized)) {
            return MODE_THREE_SINGLE_SAMPLE_VCFS;
        }
        if ("JOINT".equals(normalized) || "JOINT_MULTI_SAMPLE_VCF".equals(normalized)) {
            return MODE_JOINT_VCF;
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static Integer positiveOrNull(Integer value) {
        return value == null || value <= 0 ? null : value;
    }
}
