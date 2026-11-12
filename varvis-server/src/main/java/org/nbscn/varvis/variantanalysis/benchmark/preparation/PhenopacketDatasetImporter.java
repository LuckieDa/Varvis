package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Imports records independently so one malformed public record cannot abort a dataset. */
public class PhenopacketDatasetImporter {

    private final PhenopacketBenchmarkAdapter adapter;

    public PhenopacketDatasetImporter(PhenopacketBenchmarkAdapter adapter) {
        this.adapter = adapter == null ? new PhenopacketBenchmarkAdapter() : adapter;
    }

    public PhenopacketDatasetImportResult importJsonRecords(List<String> jsonRecords,
                                                              PhenopacketImportContext context) {
        PhenopacketDatasetImportResult result = new PhenopacketDatasetImportResult();
        Set<String> caseIds = new HashSet<String>();
        if (jsonRecords == null) return result;
        for (String json : jsonRecords) {
            PhenopacketImportResult imported = adapter.importJson(json, context);
            String caseId = imported.getCaseData() == null ? null : imported.getCaseData().getCaseId();
            if (caseId != null && !caseIds.add(caseId)) {
                imported.setStatus(PhenopacketEligibilityStatus.EXCLUDED);
                imported.getExclusionReasons().add(PhenopacketExclusionReason.DUPLICATE_CASE_ID);
                imported.getIssues().add(new PhenopacketImportIssue("ERROR", "DUPLICATE_CASE_ID",
                        "id", "case ID is duplicated within the dataset"));
            }
            result.getResults().add(imported);
        }
        return result;
    }
}
