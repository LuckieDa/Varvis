package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.nbscn.varvis.utils.CoordinateUtils;

import java.util.Locale;
import java.util.Objects;

public class NormalizedVariantKey {

    private final String genomeAssembly;
    private final String chromosome;
    private final int position;
    private final String reference;
    private final String alternate;

    @JsonCreator
    public NormalizedVariantKey(@JsonProperty("genomeAssembly") String genomeAssembly,
                                @JsonProperty("chromosome") String chromosome,
                                @JsonProperty("position") int position,
                                @JsonProperty("reference") String reference,
                                @JsonProperty("alternate") String alternate) {
        this.genomeAssembly = genomeAssembly;
        this.chromosome = chromosome;
        this.position = position;
        this.reference = reference;
        this.alternate = alternate;
    }

    public static NormalizedVariantKey canonical(GenomeAssembly genomeAssembly,
                                                 String chromosome,
                                                 int position,
                                                 String reference,
                                                 String alternate) {
        if (genomeAssembly == null) {
            throw new IllegalArgumentException("Genome assembly is required for a canonical variant key");
        }
        String normalizedChromosome = CoordinateUtils.normalizeChromosome(chromosome);
        if (normalizedChromosome == null || normalizedChromosome.isEmpty()) {
            throw new IllegalArgumentException("Chromosome is required for a canonical variant key");
        }
        if (position <= 0) {
            throw new IllegalArgumentException("Position must be positive for a canonical variant key");
        }
        if (reference == null || reference.trim().isEmpty()
                || alternate == null || alternate.trim().isEmpty()) {
            throw new IllegalArgumentException("REF and ALT are required for a canonical variant key");
        }
        return new NormalizedVariantKey(
                genomeAssembly.name(),
                normalizedChromosome,
                position,
                reference.trim().toUpperCase(Locale.ROOT),
                alternate.trim().toUpperCase(Locale.ROOT)
        );
    }

    public String canonicalId() {
        return String.valueOf(genomeAssembly)
                + "|" + String.valueOf(chromosome)
                + "|" + position
                + "|" + String.valueOf(reference)
                + "|" + String.valueOf(alternate);
    }

    public String getGenomeAssembly() {
        return genomeAssembly;
    }

    public String getChromosome() {
        return chromosome;
    }

    public int getPosition() {
        return position;
    }

    public String getReference() {
        return reference;
    }

    public String getAlternate() {
        return alternate;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof NormalizedVariantKey)) {
            return false;
        }
        NormalizedVariantKey that = (NormalizedVariantKey) other;
        return position == that.position
                && Objects.equals(genomeAssembly, that.genomeAssembly)
                && Objects.equals(chromosome, that.chromosome)
                && Objects.equals(reference, that.reference)
                && Objects.equals(alternate, that.alternate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(genomeAssembly, chromosome, position, reference, alternate);
    }
}
