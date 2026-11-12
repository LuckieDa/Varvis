package org.nbscn.varvis.variantanalysis.trio;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

public class TrioVariantObservation {

    private final String variantId;
    private final String geneSymbol;
    private final String chromosome;
    private final long position;
    private final Map<TrioRole, TrioGenotypeObservation> genotypes;

    public TrioVariantObservation(String variantId,
                                  String geneSymbol,
                                  String chromosome,
                                  long position,
                                  Map<TrioRole, TrioGenotypeObservation> genotypes) {
        this.variantId = variantId;
        this.geneSymbol = geneSymbol;
        this.chromosome = chromosome;
        this.position = position;
        this.genotypes = immutableGenotypes(genotypes);
    }

    private Map<TrioRole, TrioGenotypeObservation> immutableGenotypes(
            Map<TrioRole, TrioGenotypeObservation> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<TrioRole, TrioGenotypeObservation> copy =
                new EnumMap<TrioRole, TrioGenotypeObservation>(TrioRole.class);
        copy.putAll(values);
        return Collections.unmodifiableMap(copy);
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

    public Map<TrioRole, TrioGenotypeObservation> getGenotypes() {
        return genotypes;
    }

    public TrioGenotypeObservation genotype(TrioRole role) {
        return genotypes.get(role);
    }
}
