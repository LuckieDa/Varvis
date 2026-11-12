package org.nbscn.varvis.variantanalysis.ranking;

import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.List;

public interface VariantRankingService {

    void rank(List<VariantInterpretation> variants);
}
