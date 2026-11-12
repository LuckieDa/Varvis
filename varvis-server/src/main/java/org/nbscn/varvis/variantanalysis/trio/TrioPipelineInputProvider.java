package org.nbscn.varvis.variantanalysis.trio;

import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;

public interface TrioPipelineInputProvider {

    TrioPipelineInput inputFor(VariantAnalysisJob job);

    default void release(VariantAnalysisJob job) {
        // Request-backed implementations may discard ephemeral upload metadata
        // after the terminal engine attempt. Persistent output recovery never
        // reconstructs or reruns input from this provider.
    }
}
