package org.nbscn.varvis.variantanalysis.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class VcfSampleDetectionResult {

    private final List<String> samples;
    private final List<String> contigs;
    private final String assemblyHint;
    private final String assemblyConfidence;
    private final boolean assemblyConfirmationRequired;
    private final String contigStyle;
    private final String indexPreparation;
    private final String fileType;
    private final List<String> warnings;
    private final String errorCode;
    private final String errorMessage;

    public VcfSampleDetectionResult(List<String> samples,
                                    List<String> contigs,
                                    String assemblyHint,
                                    String assemblyConfidence,
                                    boolean assemblyConfirmationRequired,
                                    String contigStyle,
                                    String indexPreparation,
                                    String fileType,
                                    List<String> warnings,
                                    String errorCode,
                                    String errorMessage) {
        this.samples = immutable(samples);
        this.contigs = immutable(contigs);
        this.assemblyHint = assemblyHint;
        this.assemblyConfidence = assemblyConfidence;
        this.assemblyConfirmationRequired = assemblyConfirmationRequired;
        this.contigStyle = contigStyle;
        this.indexPreparation = indexPreparation;
        this.fileType = fileType;
        this.warnings = immutable(warnings);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public static VcfSampleDetectionResult success(List<String> samples,
                                                   List<String> contigs,
                                                   String assemblyHint,
                                                   String fileType,
                                                   List<String> warnings) {
        return success(
                samples,
                contigs,
                assemblyHint,
                assemblyHint == null ? "UNKNOWN" : "DECLARED",
                assemblyHint == null,
                "UNKNOWN",
                defaultIndexPreparation(fileType),
                fileType,
                warnings
        );
    }

    public static VcfSampleDetectionResult success(List<String> samples,
                                                   List<String> contigs,
                                                   String assemblyHint,
                                                   String assemblyConfidence,
                                                   boolean assemblyConfirmationRequired,
                                                   String contigStyle,
                                                   String indexPreparation,
                                                   String fileType,
                                                   List<String> warnings) {
        return new VcfSampleDetectionResult(
                samples,
                contigs,
                assemblyHint,
                assemblyConfidence,
                assemblyConfirmationRequired,
                contigStyle,
                indexPreparation,
                fileType,
                warnings,
                null,
                null
        );
    }

    public static VcfSampleDetectionResult error(String fileType,
                                                 String errorCode,
                                                 String errorMessage) {
        return new VcfSampleDetectionResult(
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                null,
                "UNKNOWN",
                false,
                "UNKNOWN",
                defaultIndexPreparation(fileType),
                fileType,
                Collections.<String>emptyList(),
                errorCode,
                errorMessage
        );
    }

    public List<String> getSamples() {
        return samples;
    }

    public List<String> getContigs() {
        return contigs;
    }

    public String getAssemblyHint() {
        return assemblyHint;
    }

    public String getAssemblyConfidence() {
        return assemblyConfidence;
    }

    public boolean isAssemblyConfirmationRequired() {
        return assemblyConfirmationRequired;
    }

    public String getContigStyle() {
        return contigStyle;
    }

    public String getIndexPreparation() {
        return indexPreparation;
    }

    public String getFileType() {
        return fileType;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isSuccessful() {
        return errorCode == null || errorCode.trim().isEmpty();
    }

    private static List<String> immutable(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    private static String defaultIndexPreparation(String fileType) {
        if ("VCF_GZ".equals(fileType)) {
            return "PRIVATE_BGZF_TABIX";
        }
        if ("VCF".equals(fileType)) {
            return "NOT_REQUIRED";
        }
        return "UNSUPPORTED";
    }
}
