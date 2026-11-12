package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SegregationEvidence {

    private final String variantId;
    private final String geneSymbol;
    private final InheritanceModelCandidate model;
    private final SegregationConfidence confidence;
    private final ParentalOrigin parentalOrigin;
    private final List<String> partnerVariantIds;
    private final List<String> reasons;
    private final List<String> warnings;

    public SegregationEvidence(String variantId,
                               String geneSymbol,
                               InheritanceModelCandidate model,
                               SegregationConfidence confidence,
                               ParentalOrigin parentalOrigin,
                               List<String> partnerVariantIds,
                               List<String> reasons,
                               List<String> warnings) {
        this.variantId = variantId;
        this.geneSymbol = geneSymbol;
        this.model = model == null ? InheritanceModelCandidate.UNINFORMATIVE : model;
        this.confidence = confidence == null ? SegregationConfidence.UNINFORMATIVE : confidence;
        this.parentalOrigin = parentalOrigin == null ? ParentalOrigin.UNKNOWN : parentalOrigin;
        this.partnerVariantIds = immutableList(partnerVariantIds);
        this.reasons = immutableList(reasons);
        this.warnings = immutableList(warnings);
    }

    private List<String> immutableList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public String getVariantId() {
        return variantId;
    }

    public String getGeneSymbol() {
        return geneSymbol;
    }

    public InheritanceModelCandidate getModel() {
        return model;
    }

    public SegregationConfidence getConfidence() {
        return confidence;
    }

    public ParentalOrigin getParentalOrigin() {
        return parentalOrigin;
    }

    public List<String> getPartnerVariantIds() {
        return partnerVariantIds;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
