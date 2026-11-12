package org.nbscn.varvis.variantanalysis.trio;

import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.springframework.stereotype.Service;

@Service
public class NoOpTrioPipelineInputProvider implements TrioPipelineInputProvider {

    @Override
    public TrioPipelineInput inputFor(VariantAnalysisJob job) {
        return TrioPipelineInput.absent();
    }
}
