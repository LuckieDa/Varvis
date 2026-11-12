package org.nbscn.varvis.variantanalysis.casevalidation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseValidationCase {

    private String caseId;
    private String description;
    private String assembly;
    private String vcfPath;
    private String probandSampleId;
    private List<String> hpoTerms = new ArrayList<String>();
    private List<TruthVariant> truthVariants = new ArrayList<TruthVariant>();
    private List<TruthGene> truthGenes = new ArrayList<TruthGene>();
    private Expected expected = new Expected();
    private Metadata metadata = new Metadata();

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAssembly() {
        return assembly;
    }

    public void setAssembly(String assembly) {
        this.assembly = assembly;
    }

    public String getVcfPath() {
        return vcfPath;
    }

    public void setVcfPath(String vcfPath) {
        this.vcfPath = vcfPath;
    }

    public String getProbandSampleId() {
        return probandSampleId;
    }

    public void setProbandSampleId(String probandSampleId) {
        this.probandSampleId = probandSampleId;
    }

    public List<String> getHpoTerms() {
        return hpoTerms == null ? Collections.<String>emptyList() : hpoTerms;
    }

    public void setHpoTerms(List<String> hpoTerms) {
        this.hpoTerms = hpoTerms == null ? new ArrayList<String>() : new ArrayList<String>(hpoTerms);
    }

    public List<TruthVariant> getTruthVariants() {
        return truthVariants == null ? Collections.<TruthVariant>emptyList() : truthVariants;
    }

    public void setTruthVariants(List<TruthVariant> truthVariants) {
        this.truthVariants = truthVariants == null
                ? new ArrayList<TruthVariant>()
                : new ArrayList<TruthVariant>(truthVariants);
    }

    public List<TruthGene> getTruthGenes() {
        return truthGenes == null ? Collections.<TruthGene>emptyList() : truthGenes;
    }

    public void setTruthGenes(List<TruthGene> truthGenes) {
        this.truthGenes = truthGenes == null ? new ArrayList<TruthGene>() : new ArrayList<TruthGene>(truthGenes);
    }

    public Expected getExpected() {
        return expected == null ? new Expected() : expected;
    }

    public void setExpected(Expected expected) {
        this.expected = expected == null ? new Expected() : expected;
    }

    public Metadata getMetadata() {
        return metadata == null ? new Metadata() : metadata;
    }

    public void setMetadata(Metadata metadata) {
        this.metadata = metadata == null ? new Metadata() : metadata;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TruthVariant {
        private String chrom;
        private int pos;
        private String ref;
        private String alt;
        private String geneSymbol;
        private String transcript;
        private String zygosity;
        private String inheritance;
        private String label;
        private String note;

        public String getChrom() {
            return chrom;
        }

        public void setChrom(String chrom) {
            this.chrom = chrom;
        }

        public int getPos() {
            return pos;
        }

        public void setPos(int pos) {
            this.pos = pos;
        }

        public String getRef() {
            return ref;
        }

        public void setRef(String ref) {
            this.ref = ref;
        }

        public String getAlt() {
            return alt;
        }

        public void setAlt(String alt) {
            this.alt = alt;
        }

        public String getGeneSymbol() {
            return geneSymbol;
        }

        public void setGeneSymbol(String geneSymbol) {
            this.geneSymbol = geneSymbol;
        }

        public String getTranscript() {
            return transcript;
        }

        public void setTranscript(String transcript) {
            this.transcript = transcript;
        }

        public String getZygosity() {
            return zygosity;
        }

        public void setZygosity(String zygosity) {
            this.zygosity = zygosity;
        }

        public String getInheritance() {
            return inheritance;
        }

        public void setInheritance(String inheritance) {
            this.inheritance = inheritance;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TruthGene {
        private String geneSymbol;
        private String label;
        private String note;

        public String getGeneSymbol() {
            return geneSymbol;
        }

        public void setGeneSymbol(String geneSymbol) {
            this.geneSymbol = geneSymbol;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Expected {
        private boolean requireGeneAnnotation = true;
        private boolean requirePhenotypeSupport;
        private boolean compareNoHpoWithHpo = true;
        private List<Integer> hitK = new ArrayList<Integer>();

        public Expected() {
            hitK.add(1);
            hitK.add(5);
            hitK.add(10);
            hitK.add(50);
        }

        public boolean isRequireGeneAnnotation() {
            return requireGeneAnnotation;
        }

        public void setRequireGeneAnnotation(boolean requireGeneAnnotation) {
            this.requireGeneAnnotation = requireGeneAnnotation;
        }

        public boolean isRequirePhenotypeSupport() {
            return requirePhenotypeSupport;
        }

        public void setRequirePhenotypeSupport(boolean requirePhenotypeSupport) {
            this.requirePhenotypeSupport = requirePhenotypeSupport;
        }

        public boolean isCompareNoHpoWithHpo() {
            return compareNoHpoWithHpo;
        }

        public void setCompareNoHpoWithHpo(boolean compareNoHpoWithHpo) {
            this.compareNoHpoWithHpo = compareNoHpoWithHpo;
        }

        public List<Integer> getHitK() {
            return hitK == null || hitK.isEmpty() ? defaultHitK() : hitK;
        }

        public void setHitK(List<Integer> hitK) {
            this.hitK = hitK == null ? defaultHitK() : new ArrayList<Integer>(hitK);
        }

        private List<Integer> defaultHitK() {
            List<Integer> defaults = new ArrayList<Integer>();
            defaults.add(1);
            defaults.add(5);
            defaults.add(10);
            defaults.add(50);
            return defaults;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Metadata {
        private String source;
        private boolean deidentified;
        private String createdAt;
        private List<String> notes = new ArrayList<String>();

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public boolean isDeidentified() {
            return deidentified;
        }

        public void setDeidentified(boolean deidentified) {
            this.deidentified = deidentified;
        }

        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        public List<String> getNotes() {
            return notes == null ? Collections.<String>emptyList() : notes;
        }

        public void setNotes(List<String> notes) {
            this.notes = notes == null ? new ArrayList<String>() : new ArrayList<String>(notes);
        }
    }
}
