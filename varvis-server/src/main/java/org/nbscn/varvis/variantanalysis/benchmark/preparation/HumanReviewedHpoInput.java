package org.nbscn.varvis.variantanalysis.benchmark.preparation;

import java.util.ArrayList;
import java.util.List;

public class HumanReviewedHpoInput {

    private List<String> terms = new ArrayList<String>();
    private String provenance;
    private String rationale;

    public HumanReviewedHpoInput() {
    }

    public HumanReviewedHpoInput(List<String> terms, String provenance, String rationale) {
        this.terms = terms == null ? new ArrayList<String>() : new ArrayList<String>(terms);
        this.provenance = provenance;
        this.rationale = rationale;
    }

    public List<String> getTerms() { return terms; }
    public void setTerms(List<String> value) { terms = value; }
    public String getProvenance() { return provenance; }
    public void setProvenance(String value) { provenance = value; }
    public String getRationale() { return rationale; }
    public void setRationale(String value) { rationale = value; }
}
