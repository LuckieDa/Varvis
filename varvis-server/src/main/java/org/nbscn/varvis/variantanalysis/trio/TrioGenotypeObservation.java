package org.nbscn.varvis.variantanalysis.trio;

public class TrioGenotypeObservation {

    private final TrioRole role;
    private final String genotype;
    private final TrioGenotypeCall call;
    private final Integer depth;
    private final Integer genotypeQuality;
    private final String alleleDepth;
    private final boolean phased;

    public TrioGenotypeObservation(TrioRole role,
                                   String genotype,
                                   TrioGenotypeCall call,
                                   Integer depth,
                                   Integer genotypeQuality,
                                   String alleleDepth) {
        this(role, genotype, call, depth, genotypeQuality, alleleDepth, false);
    }

    public TrioGenotypeObservation(TrioRole role,
                                   String genotype,
                                   TrioGenotypeCall call,
                                   Integer depth,
                                   Integer genotypeQuality,
                                   String alleleDepth,
                                   boolean phased) {
        if (role == null) {
            throw new IllegalArgumentException("Trio role is required.");
        }
        this.role = role;
        this.genotype = genotype;
        this.call = call == null ? TrioGenotypeCall.OTHER : call;
        this.depth = depth;
        this.genotypeQuality = genotypeQuality;
        this.alleleDepth = alleleDepth;
        this.phased = phased;
    }

    public static TrioGenotypeObservation het(TrioRole role) {
        return new TrioGenotypeObservation(role, "0/1", TrioGenotypeCall.HET_ALT, null, null, null);
    }

    public static TrioGenotypeObservation homAlt(TrioRole role) {
        return new TrioGenotypeObservation(role, "1/1", TrioGenotypeCall.HOM_ALT, null, null, null);
    }

    public static TrioGenotypeObservation hemiAlt(TrioRole role) {
        return new TrioGenotypeObservation(role, "1", TrioGenotypeCall.HEMI_ALT, null, null, null);
    }

    public static TrioGenotypeObservation homRef(TrioRole role) {
        return new TrioGenotypeObservation(role, "0/0", TrioGenotypeCall.HOM_REF, null, null, null);
    }

    public static TrioGenotypeObservation noCall(TrioRole role) {
        return new TrioGenotypeObservation(role, "./.", TrioGenotypeCall.NO_CALL, null, null, null);
    }

    public static TrioGenotypeObservation unobserved(TrioRole role) {
        return new TrioGenotypeObservation(role, null, TrioGenotypeCall.UNOBSERVED, null, null, null);
    }

    public TrioRole getRole() {
        return role;
    }

    public String getGenotype() {
        return genotype;
    }

    public TrioGenotypeCall getCall() {
        return call;
    }

    public Integer getDepth() {
        return depth;
    }

    public Integer getGenotypeQuality() {
        return genotypeQuality;
    }

    public String getAlleleDepth() {
        return alleleDepth;
    }

    public boolean isPhased() {
        return phased;
    }

    public boolean carriesAlternateAllele() {
        return call == TrioGenotypeCall.HET_ALT
                || call == TrioGenotypeCall.HOM_ALT
                || call == TrioGenotypeCall.HEMI_ALT;
    }

    public boolean isHeterozygousAlternate() {
        return call == TrioGenotypeCall.HET_ALT;
    }

    public boolean isHomozygousAlternate() {
        return call == TrioGenotypeCall.HOM_ALT;
    }

    public boolean isHemizygousAlternate() {
        return call == TrioGenotypeCall.HEMI_ALT;
    }

    public boolean isConfirmedReference() {
        return call == TrioGenotypeCall.HOM_REF;
    }

    public boolean isUnconfirmedReference() {
        return call == TrioGenotypeCall.UNCONFIRMED_HOM_REF;
    }

    public boolean isUninformative() {
        return call == TrioGenotypeCall.NO_CALL
                || call == TrioGenotypeCall.UNOBSERVED
                || call == TrioGenotypeCall.UNCONFIRMED_HOM_REF;
    }
}
