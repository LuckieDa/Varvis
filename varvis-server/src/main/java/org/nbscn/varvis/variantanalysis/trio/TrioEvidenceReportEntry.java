package org.nbscn.varvis.variantanalysis.trio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TrioEvidenceReportEntry {

    private final String variantId;
    private final String geneSymbol;
    private final InheritanceModelCandidate model;
    private final SegregationConfidence confidence;
    private final ParentalOrigin parentalOrigin;
    private final List<String> partnerVariantIds;
    private final String explanation;
    private final List<String> warnings;

    public TrioEvidenceReportEntry(SegregationEvidence evidence) {
        this.variantId = evidence.getVariantId();
        this.geneSymbol = evidence.getGeneSymbol();
        this.model = evidence.getModel();
        this.confidence = evidence.getConfidence();
        this.parentalOrigin = evidence.getParentalOrigin();
        this.partnerVariantIds = immutableList(evidence.getPartnerVariantIds());
        this.explanation = buildExplanation(evidence);
        this.warnings = immutableList(evidence.getWarnings());
    }

    private String buildExplanation(SegregationEvidence evidence) {
        if (evidence.getReasons().isEmpty()) {
            return "No trio segregation explanation was generated.";
        }
        StringBuilder builder = new StringBuilder();
        for (String reason : evidence.getReasons()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(reason);
        }
        return builder.toString();
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

    public String getExplanation() {
        return explanation;
    }

    public List<String> getWarnings() {
        return warnings;
    }
}
