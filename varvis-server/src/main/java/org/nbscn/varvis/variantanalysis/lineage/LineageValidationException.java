package org.nbscn.varvis.variantanalysis.lineage;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Fail-closed validation error for the private allele-lineage contract.
 *
 * <p>The message intentionally contains only a stable code and, optionally, a controlled
 * schema field name. It must never contain an input value, path, patient identifier, variant,
 * tool output, or an underlying parser message.</p>
 */
public final class LineageValidationException extends IllegalArgumentException {

    private static final Pattern CONTROLLED_FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");

    private final AnalysisAlleleLineageWire.ReasonCode reasonCode;
    private final String field;

    public LineageValidationException(AnalysisAlleleLineageWire.ReasonCode reasonCode) {
        this(reasonCode, null);
    }

    public LineageValidationException(AnalysisAlleleLineageWire.ReasonCode reasonCode,
                                      String field) {
        super(message(reasonCode, controlled(field)));
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
        this.field = controlled(field);
    }

    public AnalysisAlleleLineageWire.ReasonCode getReasonCode() {
        return reasonCode;
    }

    public String getField() {
        return field;
    }

    private static String message(AnalysisAlleleLineageWire.ReasonCode reasonCode,
                                  String field) {
        Objects.requireNonNull(reasonCode, "reasonCode");
        return field == null || field.isEmpty()
                ? reasonCode.name()
                : reasonCode.name() + ":" + field;
    }

    private static String controlled(String field) {
        return field != null && CONTROLLED_FIELD.matcher(field).matches() ? field : null;
    }
}
