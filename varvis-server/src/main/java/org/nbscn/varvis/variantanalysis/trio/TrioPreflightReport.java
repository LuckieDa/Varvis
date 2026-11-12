package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrioPreflightReport {

    private final TrioInputMode inputMode;
    private final boolean valid;
    private final int memberCount;
    private final int variantCount;
    private final List<TrioPreflightIssue> issues;

    public TrioPreflightReport(TrioInputMode inputMode,
                               boolean valid,
                               int memberCount,
                               int variantCount,
                               List<TrioPreflightIssue> issues) {
        this.inputMode = inputMode == null ? TrioInputMode.SYNTHETIC_OBSERVATIONS : inputMode;
        this.valid = valid;
        this.memberCount = memberCount;
        this.variantCount = variantCount;
        this.issues = immutableIssues(issues);
    }

    private List<TrioPreflightIssue> immutableIssues(List<TrioPreflightIssue> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<TrioPreflightIssue>(values));
    }

    public TrioInputMode getInputMode() {
        return inputMode;
    }

    public boolean isValid() {
        return valid;
    }

    public int getMemberCount() {
        return memberCount;
    }

    public int getVariantCount() {
        return variantCount;
    }

    public List<TrioPreflightIssue> getIssues() {
        return issues;
    }
}
