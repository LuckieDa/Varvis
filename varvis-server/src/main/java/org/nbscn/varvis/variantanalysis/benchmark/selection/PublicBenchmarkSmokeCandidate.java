package org.nbscn.varvis.variantanalysis.benchmark.selection;

import org.nbscn.varvis.variantanalysis.benchmark.preparation.PhenopacketImportResult;

public class PublicBenchmarkSmokeCandidate {
    private PhenopacketImportResult imported;
    private String sourceRelativePath;
    private String sourceSha256;
    private boolean allTruthInsideBenchmarkRegion;

    public PhenopacketImportResult getImported() { return imported; }
    public void setImported(PhenopacketImportResult value) { imported = value; }
    public String getSourceRelativePath() { return sourceRelativePath; }
    public void setSourceRelativePath(String value) { sourceRelativePath = value; }
    public String getSourceSha256() { return sourceSha256; }
    public void setSourceSha256(String value) { sourceSha256 = value; }
    public boolean isAllTruthInsideBenchmarkRegion() { return allTruthInsideBenchmarkRegion; }
    public void setAllTruthInsideBenchmarkRegion(boolean value) { allTruthInsideBenchmarkRegion = value; }
}
