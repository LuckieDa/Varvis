package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class TrioEvidenceReportMapper {

    public TrioEvidenceReport toReport(TrioPreflightReport preflightReport, List<SegregationEvidence> evidenceList) {
        List<TrioEvidenceReportEntry> entries = new ArrayList<TrioEvidenceReportEntry>();
        Map<InheritanceModelCandidate, Integer> counts =
                new EnumMap<InheritanceModelCandidate, Integer>(InheritanceModelCandidate.class);
        if (evidenceList != null) {
            for (SegregationEvidence evidence : evidenceList) {
                if (evidence != null) {
                    entries.add(new TrioEvidenceReportEntry(evidence));
                    Integer count = counts.get(evidence.getModel());
                    counts.put(evidence.getModel(), count == null ? 1 : count + 1);
                }
            }
        }
        return new TrioEvidenceReport(preflightReport, entries, counts);
    }
}
