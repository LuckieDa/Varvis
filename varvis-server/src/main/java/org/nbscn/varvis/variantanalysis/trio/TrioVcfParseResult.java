package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrioVcfParseResult {

    private final TrioVcfParseRequest request;
    private final TrioAnalysisInput analysisInput;
    private final List<TrioVcfGenotypeRecord> genotypeRecords;
    private final List<TrioVcfParseIssue> issues;

    public TrioVcfParseResult(TrioVcfParseRequest request,
                              TrioAnalysisInput analysisInput,
                              List<TrioVcfGenotypeRecord> genotypeRecords,
                              List<TrioVcfParseIssue> issues) {
        this.request = request;
        this.analysisInput = analysisInput;
        this.genotypeRecords = immutableRecords(genotypeRecords);
        this.issues = immutableIssues(issues);
    }

    private List<TrioVcfGenotypeRecord> immutableRecords(List<TrioVcfGenotypeRecord> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<TrioVcfGenotypeRecord>(values));
    }

    private List<TrioVcfParseIssue> immutableIssues(List<TrioVcfParseIssue> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<TrioVcfParseIssue>(values));
    }

    public TrioVcfParseRequest getRequest() {
        return request;
    }

    public TrioAnalysisInput getAnalysisInput() {
        return analysisInput;
    }

    public List<TrioVcfGenotypeRecord> getGenotypeRecords() {
        return genotypeRecords;
    }

    public List<TrioVcfParseIssue> getIssues() {
        return issues;
    }

    public boolean hasErrors() {
        for (TrioVcfParseIssue issue : issues) {
            if (issue.getSeverity() == TrioVcfParseSeverity.ERROR) {
                return true;
            }
        }
        return false;
    }
}
