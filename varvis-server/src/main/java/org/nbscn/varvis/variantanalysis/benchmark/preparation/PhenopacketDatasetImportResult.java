package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.List;

@JsonPropertyOrder({"results", "datasetIssues", "eligibleCount", "excludedCount", "invalidCount"})
public class PhenopacketDatasetImportResult {

    private List<PhenopacketImportResult> results = new ArrayList<PhenopacketImportResult>();
    private List<PhenopacketImportIssue> datasetIssues = new ArrayList<PhenopacketImportIssue>();

    public List<PhenopacketImportResult> getResults() { return results; }
    public List<PhenopacketImportIssue> getDatasetIssues() { return datasetIssues; }

    public int getEligibleCount() { return count(PhenopacketEligibilityStatus.ELIGIBLE); }
    public int getExcludedCount() { return count(PhenopacketEligibilityStatus.EXCLUDED); }
    public int getInvalidCount() { return count(PhenopacketEligibilityStatus.INVALID); }

    private int count(PhenopacketEligibilityStatus status) {
        int count = 0;
        for (PhenopacketImportResult result : results) {
            if (result != null && result.getStatus() == status) count++;
        }
        return count;
    }
}
