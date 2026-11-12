package org.nbscn.varvis.variantanalysis.trio;

public class SyntheticTrioVariantRecord {

    private final String variantId;
    private final String geneSymbol;
    private final String chromosome;
    private final long position;
    private final String probandGenotype;
    private final String fatherGenotype;
    private final String motherGenotype;

    public SyntheticTrioVariantRecord(String variantId,
                                      String geneSymbol,
                                      String chromosome,
                                      long position,
                                      String probandGenotype,
                                      String fatherGenotype,
                                      String motherGenotype) {
        this.variantId = variantId;
        this.geneSymbol = geneSymbol;
        this.chromosome = chromosome;
        this.position = position;
        this.probandGenotype = probandGenotype;
        this.fatherGenotype = fatherGenotype;
        this.motherGenotype = motherGenotype;
    }

    public String getVariantId() {
        return variantId;
    }

    public String getGeneSymbol() {
        return geneSymbol;
    }

    public String getChromosome() {
        return chromosome;
    }

    public long getPosition() {
        return position;
    }

    public String getProbandGenotype() {
        return probandGenotype;
    }

    public String getFatherGenotype() {
        return fatherGenotype;
    }

    public String getMotherGenotype() {
        return motherGenotype;
    }
}
