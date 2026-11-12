package org.nbscn.varvis.variantanalysis.domain;

public class CanonicalVariantIdentity {

    public static final String SCHEMA_VERSION = "varvis-canonical-v1";
    public static final String REFERENCE_PROFILE_BOUND = "REFERENCE_PROFILE_BOUND";
    public static final String REFERENCE_PROFILE_UNRESOLVED = "REFERENCE_PROFILE_UNRESOLVED";

    private final String schemaVersion;
    private final String status;
    private final String assembly;
    private final String referenceProfileId;
    private final String canonicalVariantId;
    private final String varvisComputedIdentifier;
    private final String chromosome;
    private final int position;
    private final String reference;
    private final String alternate;
    private final int interbaseStart;
    private final int interbaseEnd;
    private final String refgetSequenceDigest;
    private final String vrsComputedIdentifier;
    private final String vrsCompatibilityStatus;

    public CanonicalVariantIdentity(String status,
                                    String assembly,
                                    String referenceProfileId,
                                    String canonicalVariantId,
                                    String varvisComputedIdentifier,
                                    String chromosome,
                                    int position,
                                    String reference,
                                    String alternate,
                                    int interbaseStart,
                                    int interbaseEnd,
                                    String refgetSequenceDigest,
                                    String vrsComputedIdentifier,
                                    String vrsCompatibilityStatus) {
        this.schemaVersion = SCHEMA_VERSION;
        this.status = status;
        this.assembly = assembly;
        this.referenceProfileId = referenceProfileId;
        this.canonicalVariantId = canonicalVariantId;
        this.varvisComputedIdentifier = varvisComputedIdentifier;
        this.chromosome = chromosome;
        this.position = position;
        this.reference = reference;
        this.alternate = alternate;
        this.interbaseStart = interbaseStart;
        this.interbaseEnd = interbaseEnd;
        this.refgetSequenceDigest = refgetSequenceDigest;
        this.vrsComputedIdentifier = vrsComputedIdentifier;
        this.vrsCompatibilityStatus = vrsCompatibilityStatus;
    }

    public String getSchemaVersion() { return schemaVersion; }
    public String getStatus() { return status; }
    public String getAssembly() { return assembly; }
    public String getReferenceProfileId() { return referenceProfileId; }
    public String getCanonicalVariantId() { return canonicalVariantId; }
    public String getVarvisComputedIdentifier() { return varvisComputedIdentifier; }
    public String getChromosome() { return chromosome; }
    public int getPosition() { return position; }
    public String getReference() { return reference; }
    public String getAlternate() { return alternate; }
    public int getInterbaseStart() { return interbaseStart; }
    public int getInterbaseEnd() { return interbaseEnd; }
    public String getRefgetSequenceDigest() { return refgetSequenceDigest; }
    public String getVrsComputedIdentifier() { return vrsComputedIdentifier; }
    public String getVrsCompatibilityStatus() { return vrsCompatibilityStatus; }
}
