package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.nio.file.Path;
import java.util.List;

public interface VariantInterpretationService {

    List<VariantInterpretation> interpret(
            Path normalizedVcf,
            List<VariantQcObservation> observations,
            AnalysisContext context
    );

    /**
     * Adds configured local evidence and applies a transparent engine-primary composite ranking.
     * The Exomiser variant score and the candidate-row order derived from that score remain in the
     * score breakdown.  That derived order is separate from the native gene/MOI-group {@code #RANK}.
     */
    default List<VariantInterpretation> enrichEvidenceOnly(
            List<VariantInterpretation> interpretations,
            AnalysisContext context
    ) {
        return interpretations;
    }
}
