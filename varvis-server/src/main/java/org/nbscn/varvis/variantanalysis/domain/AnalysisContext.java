package org.nbscn.varvis.variantanalysis.domain;

import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryCollector;

public class AnalysisContext {

    private final String analysisId;
    private final GenomeAssembly genomeAssembly;
    private final AnalysisMode analysisMode;
    private final VcfPreflightSummary preflightSummary;
    private final PhenotypeAwareSummary phenotypeSummary;
    private final BenchmarkTelemetryCollector benchmarkTelemetry;
    private volatile PhenotypeRescueSummary phenotypeRescueSummary = PhenotypeRescueSummary.disabled();

    public AnalysisContext(String analysisId, GenomeAssembly genomeAssembly, AnalysisMode analysisMode) {
        this(analysisId, genomeAssembly, analysisMode, VcfPreflightSummary.empty(),
                PhenotypeAwareSummary.disabled(), BenchmarkTelemetryCollector.disabled());
    }

    public AnalysisContext(String analysisId,
                           GenomeAssembly genomeAssembly,
                           AnalysisMode analysisMode,
                           VcfPreflightSummary preflightSummary) {
        this(analysisId, genomeAssembly, analysisMode, preflightSummary,
                PhenotypeAwareSummary.disabled(), BenchmarkTelemetryCollector.disabled());
    }

    public AnalysisContext(String analysisId,
                           GenomeAssembly genomeAssembly,
                           AnalysisMode analysisMode,
                           VcfPreflightSummary preflightSummary,
                           PhenotypeAwareSummary phenotypeSummary) {
        this(analysisId, genomeAssembly, analysisMode, preflightSummary, phenotypeSummary,
                BenchmarkTelemetryCollector.disabled());
    }

    public AnalysisContext(String analysisId,
                           GenomeAssembly genomeAssembly,
                           AnalysisMode analysisMode,
                           VcfPreflightSummary preflightSummary,
                           PhenotypeAwareSummary phenotypeSummary,
                           BenchmarkTelemetryCollector benchmarkTelemetry) {
        this.analysisId = analysisId;
        this.genomeAssembly = genomeAssembly;
        this.analysisMode = analysisMode;
        this.preflightSummary = preflightSummary == null ? VcfPreflightSummary.empty() : preflightSummary;
        this.phenotypeSummary = phenotypeSummary == null ? PhenotypeAwareSummary.disabled() : phenotypeSummary;
        this.benchmarkTelemetry = benchmarkTelemetry == null
                ? BenchmarkTelemetryCollector.disabled() : benchmarkTelemetry;
    }

    public String getAnalysisId() {
        return analysisId;
    }

    public GenomeAssembly getGenomeAssembly() {
        return genomeAssembly;
    }

    public AnalysisMode getAnalysisMode() {
        return analysisMode;
    }

    public VcfPreflightSummary getPreflightSummary() {
        return preflightSummary;
    }

    public PhenotypeAwareSummary getPhenotypeSummary() {
        return phenotypeSummary;
    }

    public PhenotypeRescueSummary getPhenotypeRescueSummary() {
        return phenotypeRescueSummary;
    }

    public BenchmarkTelemetryCollector getBenchmarkTelemetry() { return benchmarkTelemetry; }

    public void setPhenotypeRescueSummary(PhenotypeRescueSummary phenotypeRescueSummary) {
        this.phenotypeRescueSummary = phenotypeRescueSummary == null
                ? PhenotypeRescueSummary.disabled()
                : phenotypeRescueSummary;
    }

    public boolean isLargeFileMode() {
        return preflightSummary.isLargeFileMode();
    }
}
