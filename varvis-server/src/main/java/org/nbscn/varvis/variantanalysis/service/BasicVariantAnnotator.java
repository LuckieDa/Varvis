package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.nio.file.Path;
import java.util.List;

public interface BasicVariantAnnotator {

    List<VariantInterpretation> annotate(Path normalizedVcf, List<VariantQcObservation> observations);
}
