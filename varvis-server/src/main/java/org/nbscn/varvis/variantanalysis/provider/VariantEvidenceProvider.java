package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.List;
import java.util.Map;

public interface VariantEvidenceProvider {

    String source();

    String version();

    Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
            List<VariantInterpretation> variants,
            AnalysisContext context
    );
}
