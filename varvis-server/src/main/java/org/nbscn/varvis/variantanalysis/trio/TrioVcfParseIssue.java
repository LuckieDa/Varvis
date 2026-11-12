package org.nbscn.varvis.variantanalysis.trio;

public class TrioVcfParseIssue {

    private final TrioVcfParseSeverity severity;
    private final String code;
    private final String message;
    private final TrioVcfVariantKey variantKey;
    private final String sampleId;

    public TrioVcfParseIssue(TrioVcfParseSeverity severity,
                             String code,
                             String message,
                             TrioVcfVariantKey variantKey,
                             String sampleId) {
        this.severity = severity == null ? TrioVcfParseSeverity.ERROR : severity;
        this.code = code;
        this.message = message;
        this.variantKey = variantKey;
        this.sampleId = sampleId;
    }

    public TrioVcfParseSeverity getSeverity() {
        return severity;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public TrioVcfVariantKey getVariantKey() {
        return variantKey;
    }

    public String getSampleId() {
        return sampleId;
    }
}
