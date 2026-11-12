package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class AnnTranscriptAnnotationProvider implements TranscriptAnnotationProvider {

    private static final String VERSION = "VCF_ANN";

    @Override
    public String source() {
        return "ANN_ONLY";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public List<TranscriptConsequence> annotate(VariantInterpretation variant, AnalysisContext context) {
        if (variant == null || variant.getConsequences().isEmpty()) {
            return unavailable("No ANN transcript annotation was present in the normalized VCF");
        }
        List<TranscriptConsequence> annotations = new ArrayList<TranscriptConsequence>();
        Instant timestamp = Instant.now();
        for (TranscriptConsequence consequence : variant.getConsequences()) {
            if (!hasTranscriptSignal(consequence)) {
                continue;
            }
            annotations.add(consequence.withAnnotationMetadata(
                    source(),
                    "COMPLETED",
                    version(),
                    timestamp
            ));
        }
        return annotations.isEmpty()
                ? unavailable("No usable ANN transcript annotation was present in the normalized VCF")
                : annotations;
    }

    private boolean hasTranscriptSignal(TranscriptConsequence consequence) {
        if (consequence == null) {
            return false;
        }
        return StringUtils.hasText(consequence.getGeneSymbol())
                || StringUtils.hasText(consequence.getTranscriptAccession())
                || StringUtils.hasText(consequence.getHgvsCoding())
                || StringUtils.hasText(consequence.getHgvsProtein());
    }

    private List<TranscriptConsequence> unavailable(String summary) {
        return Collections.singletonList(TranscriptConsequence.statusPlaceholder(
                summary,
                source(),
                "NO_RECORD",
                version(),
                Instant.now()
        ));
    }
}
