package org.nbscn.varvis.variantanalysis.domain;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class NormalizationResult {

    private final Path outputFile;
    private final boolean normalizationApplied;
    private final String engine;
    private final List<String> warnings;
    private final NormalizationRunManifest manifest;

    public NormalizationResult(Path outputFile,
                               boolean normalizationApplied,
                               String engine,
                               List<String> warnings) {
        this(outputFile, normalizationApplied, engine, warnings, null);
    }

    public NormalizationResult(Path outputFile,
                               boolean normalizationApplied,
                               String engine,
                               List<String> warnings,
                               NormalizationRunManifest manifest) {
        this.outputFile = outputFile;
        this.normalizationApplied = normalizationApplied;
        this.engine = engine;
        this.warnings = warnings == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.manifest = manifest;
    }

    public Path getOutputFile() {
        return outputFile;
    }

    public boolean isNormalizationApplied() {
        return normalizationApplied;
    }

    public String getEngine() {
        return engine;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public NormalizationRunManifest getManifest() {
        return manifest;
    }

    public NormalizationResult withManifest(NormalizationRunManifest updatedManifest) {
        return new NormalizationResult(
                outputFile, normalizationApplied, engine, warnings, updatedManifest
        );
    }
}
