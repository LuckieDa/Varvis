package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ClinVarEvidenceProvider implements VariantEvidenceProvider {

    private final VariantAnalysisProperties properties;
    private final ClinVarCompactIndexProvider compactProvider;
    private final ClinVarTabixProvider tabixProvider;

    @Autowired
    public ClinVarEvidenceProvider(VariantAnalysisProperties properties) {
        this(
                properties,
                new ClinVarCompactIndexProvider(properties),
                new ClinVarTabixProvider(properties)
        );
    }

    ClinVarEvidenceProvider(VariantAnalysisProperties properties,
                            ClinVarCompactIndexProvider compactProvider,
                            ClinVarTabixProvider tabixProvider) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
        this.compactProvider = compactProvider;
        this.tabixProvider = tabixProvider;
    }

    @Override
    public String source() {
        return "CLINVAR";
    }

    @Override
    public String version() {
        if (properties.getClinVar().getCompactIndex().isEnabled()) {
            return compactProvider.version();
        }
        return tabixProvider.version();
    }

    @Override
    public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
            List<VariantInterpretation> variants,
            AnalysisContext context) {
        if (containsGrch37Variant(variants, context)) {
            return tabixProvider.annotate(variants, context);
        }
        if (!properties.getClinVar().getCompactIndex().isEnabled()) {
            return tabixProvider.annotate(variants, context);
        }
        if (compactProvider.isAvailable()) {
            return compactProvider.annotate(variants, context);
        }
        if (properties.getClinVar().getCompactIndex().isAllowTabixFallback()) {
            return tabixProvider.annotate(variants, context);
        }
        return compactProvider.annotate(variants, context);
    }

    private boolean containsGrch37Variant(List<VariantInterpretation> variants, AnalysisContext context) {
        if (context != null && context.getGenomeAssembly() != null
                && "GRCh37".equalsIgnoreCase(context.getGenomeAssembly().name())) {
            return true;
        }
        if (variants == null) {
            return false;
        }
        for (VariantInterpretation variant : variants) {
            if (variant != null && variant.getObservation() != null
                    && variant.getObservation().getKey() != null
                    && "GRCh37".equalsIgnoreCase(variant.getObservation().getKey().getGenomeAssembly())) {
                return true;
            }
        }
        return false;
    }
}
