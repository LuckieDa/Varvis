package org.nbscn.varvis.variantanalysis.domain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BuildConversionResult {

    private final Path outputFile;
    private final boolean conversionApplied;
    private final String engine;
    private final List<String> warnings;
    private final Path rejectedFile;
    private final int rejectedRecordCount;
    private final long sourceRecordCount;
    private final long analysisRecordCount;
    private final com.fasterxml.jackson.databind.node.ObjectNode sourceProof;

    public BuildConversionResult(Path outputFile,
                                 boolean conversionApplied,
                                 String engine,
                                 List<String> warnings) {
        this(outputFile, conversionApplied, engine, warnings, null, 0, 0L, 0L);
    }

    public BuildConversionResult(Path outputFile,
                                 boolean conversionApplied,
                                 String engine,
                                 List<String> warnings,
                                 Path rejectedFile,
                                 int rejectedRecordCount) {
        this(outputFile, conversionApplied, engine, warnings, rejectedFile, rejectedRecordCount, 0L, 0L);
    }

    public BuildConversionResult(Path outputFile,
                                 boolean conversionApplied,
                                 String engine,
                                 List<String> warnings,
                                 Path rejectedFile,
                                 int rejectedRecordCount,
                                 long sourceRecordCount,
                                 long analysisRecordCount) {
        this(outputFile, conversionApplied, engine, warnings, rejectedFile, rejectedRecordCount,
                sourceRecordCount, analysisRecordCount, null);
    }

    public BuildConversionResult(Path outputFile, boolean conversionApplied, String engine, List<String> warnings,
                                 Path rejectedFile, int rejectedRecordCount, long sourceRecordCount, long analysisRecordCount,
                                 com.fasterxml.jackson.databind.node.ObjectNode sourceProof) {
        this.outputFile = outputFile;
        this.conversionApplied = conversionApplied;
        this.engine = engine;
        this.warnings = warnings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.rejectedFile = rejectedFile;
        this.rejectedRecordCount = Math.max(0, rejectedRecordCount);
        this.sourceRecordCount = Math.max(0L, sourceRecordCount);
        this.analysisRecordCount = Math.max(0L, analysisRecordCount);
        this.sourceProof = sourceProof == null ? null : sourceProof.deepCopy();
    }

    public Path getOutputFile() {
        return outputFile;
    }

    public boolean isConversionApplied() {
        return conversionApplied;
    }

    public String getEngine() {
        return engine;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public Path getRejectedFile() {
        return rejectedFile;
    }

    public int getRejectedRecordCount() {
        return rejectedRecordCount;
    }

    public long getSourceRecordCount() {
        return sourceRecordCount;
    }

    public long getAnalysisRecordCount() {
        return analysisRecordCount;
    }

    /** Private, versioned execution witness. Older conversions remain unproven. */
    public com.fasterxml.jackson.databind.node.ObjectNode getSourceProof() {
        return sourceProof == null ? null : sourceProof.deepCopy();
    }
}
