package org.nbscn.varvis.variantanalysis.trio;

import java.util.Locale;
import java.util.Objects;

public class TrioVcfVariantKey {

    private final String assembly;
    private final String chromosome;
    private final long position;
    private final String reference;
    private final String alternate;

    public TrioVcfVariantKey(String assembly, String chromosome, long position, String reference, String alternate) {
        this.assembly = normalizeAssembly(assembly);
        this.chromosome = normalizeChromosome(chromosome);
        this.position = position;
        this.reference = normalizeAllele(reference);
        this.alternate = normalizeAllele(alternate);
    }

    private String normalizeAssembly(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if ("grch37".equals(normalized) || "hg19".equals(normalized) || "b37".equals(normalized)) {
            return "GRCh37";
        }
        if ("grch38".equals(normalized) || "hg38".equals(normalized)) {
            return "GRCh38";
        }
        return value.trim();
    }

    private String normalizeChromosome(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim();
        if (normalized.toLowerCase(Locale.ROOT).startsWith("chr")) {
            normalized = normalized.substring(3);
        }
        if ("M".equalsIgnoreCase(normalized)) {
            return "MT";
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeAllele(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public String getAssembly() {
        return assembly;
    }

    public String getChromosome() {
        return chromosome;
    }

    public long getPosition() {
        return position;
    }

    public String getReference() {
        return reference;
    }

    public String getAlternate() {
        return alternate;
    }

    public String stableVariantId() {
        return assembly + ":" + chromosome + ":" + position + ":" + reference + ">" + alternate;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TrioVcfVariantKey)) {
            return false;
        }
        TrioVcfVariantKey that = (TrioVcfVariantKey) other;
        return position == that.position
                && Objects.equals(assembly, that.assembly)
                && Objects.equals(chromosome, that.chromosome)
                && Objects.equals(reference, that.reference)
                && Objects.equals(alternate, that.alternate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(assembly, chromosome, position, reference, alternate);
    }

    @Override
    public String toString() {
        return stableVariantId();
    }
}
