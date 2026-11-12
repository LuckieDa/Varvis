package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrioPipelineResult {

    private final String status;
    private final boolean enabled;
    private final boolean evidenceOnly;
    private final boolean failOpenUsed;
    private final TrioInputMode inputMode;
    private final TrioPreflightReport preflightReport;
    private final TrioEvidenceReport evidenceReport;
    private final List<TrioEvidenceAttachment> attachments;
    private final List<String> issues;

    public TrioPipelineResult(String status,
                              boolean enabled,
                              boolean evidenceOnly,
                              boolean failOpenUsed,
                              TrioInputMode inputMode,
                              TrioPreflightReport preflightReport,
                              TrioEvidenceReport evidenceReport,
                              List<TrioEvidenceAttachment> attachments,
                              List<String> issues) {
        this.status = status;
        this.enabled = enabled;
        this.evidenceOnly = evidenceOnly;
        this.failOpenUsed = failOpenUsed;
        this.inputMode = inputMode;
        this.preflightReport = preflightReport;
        this.evidenceReport = evidenceReport;
        this.attachments = immutableList(attachments);
        this.issues = immutableList(issues);
    }

    public static TrioPipelineResult skipped(String status, String issue) {
        return new TrioPipelineResult(
                status,
                false,
                true,
                false,
                null,
                null,
                null,
                Collections.<TrioEvidenceAttachment>emptyList(),
                issue == null ? Collections.<String>emptyList() : Collections.singletonList(issue));
    }

    public String eventMessage() {
        return "Trio segregation evidence " + status
                + ": evidenceOnly=" + evidenceOnly
                + ", inputMode=" + (inputMode == null ? "NONE" : inputMode.name())
                + ", attached=" + attachments.size()
                + ", issues=" + issues.size()
                + ", rankingEffect=" + TrioEvidenceReport.RANKING_EFFECT
                + ", hardGatePolicy=" + TrioEvidenceReport.HARD_GATE_POLICY;
    }

    public String getStatus() {
        return status;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isEvidenceOnly() {
        return evidenceOnly;
    }

    public boolean isFailOpenUsed() {
        return failOpenUsed;
    }

    public TrioInputMode getInputMode() {
        return inputMode;
    }

    public TrioPreflightReport getPreflightReport() {
        return preflightReport;
    }

    public TrioEvidenceReport getEvidenceReport() {
        return evidenceReport;
    }

    public List<TrioEvidenceAttachment> getAttachments() {
        return attachments;
    }

    public List<String> getIssues() {
        return issues;
    }

    private static <T> List<T> immutableList(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(values));
    }
}
