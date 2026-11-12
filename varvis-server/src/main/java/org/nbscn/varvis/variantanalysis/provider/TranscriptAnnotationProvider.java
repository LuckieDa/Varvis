package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.List;

public interface TranscriptAnnotationProvider {

    String source();

    String version();

    List<TranscriptConsequence> annotate(VariantInterpretation variant, AnalysisContext context);
}
