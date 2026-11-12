package org.nbscn.varvis.variantanalysis.engine;

import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;

import java.nio.file.Path;

/** Truth-free Exomiser integration boundary. Implementations must not inspect truth. */
public interface ExomiserEngineAdapter {

    EngineExecutionResult prepare(VariantAnalysisJob job);

    /** Runs against a validated analysis-build VCF without changing job input provenance. */
    default EngineExecutionResult prepare(VariantAnalysisJob job,
                                          Path analysisVcf,
                                          GenomeAssembly analysisAssembly) {
        return prepare(job);
    }

    /**
     * Runs against a validated analysis-build VCF and an explicit, truth-free
     * family mapping. Implementations that do not support family input retain
     * the existing single-sample behavior.
     */
    default EngineExecutionResult prepare(VariantAnalysisJob job,
                                          Path analysisVcf,
                                          GenomeAssembly analysisAssembly,
                                          TrioPipelineInput trioInput) {
        return prepare(job, analysisVcf, analysisAssembly);
    }
}
