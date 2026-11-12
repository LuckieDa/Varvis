package org.nbscn.varvis.variantanalysis.dto;

import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;

import java.util.List;
import java.util.Map;

public class VariantAnalysisVariantPageResponse {

    private final String analysisId;
    private final VariantInterpretationView view;
    private final int page;
    private final int pageSize;
    private final int total;
    private final int candidateTotal;
    private final int frequencyFilteredTotal;
    private final int allTotal;
    private final VariantInterpretationCounts counts;
    private final Map<String, Object> largeFileSummary;
    private final double populationFrequencyThreshold;
    private final List<VariantInterpretation> variants;
    private Map<String, List<VariantInterpretation>> candidateContexts = Map.of();
    private Integer rawRowTotal;
    private String selectionPolicy;

    public Map<String, List<VariantInterpretation>> getCandidateContexts() { return candidateContexts; }
    public Integer getRawRowTotal() { return rawRowTotal; }
    public String getSelectionPolicy() { return selectionPolicy; }
    public String getCountBasis() { return selectionPolicy == null ? "SOURCE_ROWS" : "UNIQUE_ALLELES_TOTALS_SOURCE_ROWS_TELEMETRY"; }
    public VariantAnalysisVariantPageResponse withDistinctContexts(Map<String, List<VariantInterpretation>> contexts, int rawRows) {
        candidateContexts = contexts;
        rawRowTotal = rawRows;
        selectionPolicy = org.nbscn.varvis.variantanalysis.service.DistinctVariantSelection.POLICY;
        return this;
    }

    public VariantAnalysisVariantPageResponse(String analysisId,
                                              VariantInterpretationView view,
                                              int page,
                                              int pageSize,
                                              int total,
                                              int candidateTotal,
                                              int frequencyFilteredTotal,
                                              int allTotal,
                                              double populationFrequencyThreshold,
                                              List<VariantInterpretation> variants) {
        this(
                analysisId,
                view,
                page,
                pageSize,
                total,
                candidateTotal,
                frequencyFilteredTotal,
                allTotal,
                new VariantInterpretationCounts(candidateTotal, frequencyFilteredTotal, allTotal),
                populationFrequencyThreshold,
                variants
        );
    }

    public VariantAnalysisVariantPageResponse(String analysisId,
                                              VariantInterpretationView view,
                                              int page,
                                              int pageSize,
                                              int total,
                                              int candidateTotal,
                                              int frequencyFilteredTotal,
                                              int allTotal,
                                              VariantInterpretationCounts counts,
                                              double populationFrequencyThreshold,
                                              List<VariantInterpretation> variants) {
        this.analysisId = analysisId;
        this.view = view;
        this.page = page;
        this.pageSize = pageSize;
        this.total = total;
        this.candidateTotal = candidateTotal;
        this.frequencyFilteredTotal = frequencyFilteredTotal;
        this.allTotal = allTotal;
        this.counts = counts == null
                ? new VariantInterpretationCounts(candidateTotal, frequencyFilteredTotal, allTotal)
                : counts;
        this.largeFileSummary = this.counts.toLargeFileSummary();
        this.populationFrequencyThreshold = populationFrequencyThreshold;
        this.variants = variants;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public VariantInterpretationView getView() {
        return view;
    }

    public int getPage() {
        return page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotal() {
        return total;
    }

    public int getCandidateTotal() {
        return candidateTotal;
    }

    public int getFrequencyFilteredTotal() {
        return frequencyFilteredTotal;
    }

    public int getAllTotal() {
        return allTotal;
    }

    public VariantInterpretationCounts getCounts() {
        return counts;
    }

    public Map<String, Object> getLargeFileSummary() {
        return largeFileSummary;
    }

    public double getPopulationFrequencyThreshold() {
        return populationFrequencyThreshold;
    }

    public List<VariantInterpretation> getVariants() {
        return variants;
    }
}
