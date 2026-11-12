package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class TrioEvidenceReport {

    public static final String RANKING_EFFECT = "EVIDENCE_ONLY_NO_RANKING_WEIGHT_CHANGE";
    public static final String HARD_GATE_POLICY = "TRIO_SUPPORT_DOES_NOT_OVERRIDE_HARD_GATES";

    private final TrioPreflightReport preflightReport;
    private final List<TrioEvidenceReportEntry> entries;
    private final Map<InheritanceModelCandidate, Integer> modelCounts;
    private final String rankingEffect;
    private final String hardGatePolicy;

    public TrioEvidenceReport(TrioPreflightReport preflightReport,
                              List<TrioEvidenceReportEntry> entries,
                              Map<InheritanceModelCandidate, Integer> modelCounts) {
        this.preflightReport = preflightReport;
        this.entries = immutableEntries(entries);
        this.modelCounts = immutableCounts(modelCounts);
        this.rankingEffect = RANKING_EFFECT;
        this.hardGatePolicy = HARD_GATE_POLICY;
    }

    private List<TrioEvidenceReportEntry> immutableEntries(List<TrioEvidenceReportEntry> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<TrioEvidenceReportEntry>(values));
    }

    private Map<InheritanceModelCandidate, Integer> immutableCounts(Map<InheritanceModelCandidate, Integer> values) {
        Map<InheritanceModelCandidate, Integer> result =
                new EnumMap<InheritanceModelCandidate, Integer>(InheritanceModelCandidate.class);
        if (values != null) {
            result.putAll(values);
        }
        return Collections.unmodifiableMap(result);
    }

    public TrioPreflightReport getPreflightReport() {
        return preflightReport;
    }

    public List<TrioEvidenceReportEntry> getEntries() {
        return entries;
    }

    public Map<InheritanceModelCandidate, Integer> getModelCounts() {
        return modelCounts;
    }

    public String getRankingEffect() {
        return rankingEffect;
    }

    public String getHardGatePolicy() {
        return hardGatePolicy;
    }
}
