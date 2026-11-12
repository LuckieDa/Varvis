package org.nbscn.varvis.variantanalysis.benchmark.review;

import java.util.ArrayList;
import java.util.List;

public class ReviewedHpoInput {
    private List<String> observedTerms = new ArrayList<String>();
    private List<String> excludedTerms = new ArrayList<String>();
    private String provenance;

    public List<String> getObservedTerms() { return observedTerms; }
    public void setObservedTerms(List<String> value) { observedTerms = value == null ? new ArrayList<String>() : value; }
    public List<String> getExcludedTerms() { return excludedTerms; }
    public void setExcludedTerms(List<String> value) { excludedTerms = value == null ? new ArrayList<String>() : value; }
    public String getProvenance() { return provenance; }
    public void setProvenance(String value) { provenance = value; }
}
