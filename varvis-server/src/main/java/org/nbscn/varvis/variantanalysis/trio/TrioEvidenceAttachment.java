package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrioEvidenceAttachment {

    private final String interpretationVariantId;
    private final String trioVariantId;
    private final String geneSymbol;
    private final InheritanceModelCandidate model;
    private final SegregationConfidence confidence;
    private final ParentalOrigin parentalOrigin;
    private final List<String> partnerVariantIds;
    private final List<String> warnings;

    public TrioEvidenceAttachment(String interpretationVariantId,
                                  TrioEvidenceReportEntry entry) {
        this.interpretationVariantId = interpretationVariantId;
        this.trioVariantId = entry.getVariantId();
        this.geneSymbol = entry.getGeneSymbol();
        this.model = entry.getModel();
        this.confidence = entry.getConfidence();
        this.parentalOrigin = entry.getParentalOrigin();
        this.partnerVariantIds = immutableList(entry.getPartnerVariantIds());
        this.warnings = immutableList(entry.getWarnings());
    }

    public String getInterpretationVariantId() {
        return interpretationVariantId;
    }

    public String getTrioVariantId() {
        return trioVariantId;
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

    public List<String> getWarnings() {
        return warnings;
    }

    private List<String> immutableList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<String>(values));
    }
}
