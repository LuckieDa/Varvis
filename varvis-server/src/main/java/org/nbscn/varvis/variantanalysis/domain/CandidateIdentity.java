package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Analysis-local identity for one candidate row.
 *
 * <p>A canonical variant can legitimately occur in multiple engine rows (for example for
 * different genes or modes of inheritance).  The candidate id therefore identifies the source
 * row, while {@code canonicalVariantId} continues to identify the normalized allele.</p>
 */
public final class CandidateIdentity {

    public static final String SCHEMA_VERSION = "candidate-identity-v1";
    private static final String EXOMISER_SOURCE = "EXOMISER";

    private final String schemaVersion;
    private final String candidateId;
    private final String canonicalVariantId;
    private final String source;
    private final Integer sourceOrdinal;
    private final String geneSymbol;
    private final String modeOfInheritance;
    private final Integer declaredRank;

    @JsonCreator
    public CandidateIdentity(@JsonProperty("schemaVersion") String schemaVersion,
                             @JsonProperty("candidateId") String candidateId,
                             @JsonProperty("canonicalVariantId") String canonicalVariantId,
                             @JsonProperty("source") String source,
                             @JsonProperty("sourceOrdinal") Integer sourceOrdinal,
                             @JsonProperty("geneSymbol") String geneSymbol,
                             @JsonProperty("modeOfInheritance") String modeOfInheritance,
                             @JsonProperty("declaredRank") Integer declaredRank) {
        this.schemaVersion = hasText(schemaVersion) ? schemaVersion.trim() : SCHEMA_VERSION;
        this.candidateId = requireText(candidateId, "CANDIDATE_ID_REQUIRED");
        this.canonicalVariantId = requireText(canonicalVariantId, "CANONICAL_VARIANT_ID_REQUIRED");
        this.source = hasText(source) ? source.trim().toUpperCase(Locale.ROOT) : "LEGACY";
        this.sourceOrdinal = sourceOrdinal;
        this.geneSymbol = trimToNull(geneSymbol);
        this.modeOfInheritance = trimToNull(modeOfInheritance);
        this.declaredRank = declaredRank;
    }

    public static CandidateIdentity exomiser(String canonicalVariantId,
                                             int sourceOrdinal,
                                             String geneSymbol,
                                             String modeOfInheritance,
                                             Integer declaredRank) {
        if (sourceOrdinal < 1) {
            throw new IllegalArgumentException("CANDIDATE_SOURCE_ORDINAL_INVALID");
        }
        // The immutable source ordinal is the row identity.  Gene/MOI/rank remain separately
        // auditable fields and deliberately do not make the id drift when parsing improves.
        String candidateId = "exomiser-v1-" + String.format(Locale.ROOT, "%08d", sourceOrdinal);
        return new CandidateIdentity(
                SCHEMA_VERSION,
                candidateId,
                canonicalVariantId,
                EXOMISER_SOURCE,
                sourceOrdinal,
                geneSymbol,
                modeOfInheritance,
                declaredRank
        );
    }

    public static CandidateIdentity legacy(String canonicalVariantId) {
        return legacy(null, canonicalVariantId);
    }

    public static CandidateIdentity legacy(String candidateId, String canonicalVariantId) {
        return new CandidateIdentity(
                SCHEMA_VERSION,
                hasText(candidateId) ? candidateId : legacyCandidateId(canonicalVariantId),
                canonicalVariantId,
                "LEGACY",
                null,
                null,
                null,
                null
        );
    }

    public CandidateIdentity withCandidateId(String persistedCandidateId) {
        if (!hasText(persistedCandidateId) || candidateId.equals(persistedCandidateId.trim())) {
            return this;
        }
        return new CandidateIdentity(
                schemaVersion,
                persistedCandidateId,
                canonicalVariantId,
                source,
                sourceOrdinal,
                geneSymbol,
                modeOfInheritance,
                declaredRank
        );
    }

    public CandidateIdentity withCanonicalVariantId(String canonicalId) {
        if (!hasText(canonicalId) || canonicalVariantId.equals(canonicalId.trim())) {
            return this;
        }
        return new CandidateIdentity(
                schemaVersion,
                candidateId,
                canonicalId,
                source,
                sourceOrdinal,
                geneSymbol,
                modeOfInheritance,
                declaredRank
        );
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public String getCandidateId() {
        return candidateId;
    }

    public String getCanonicalVariantId() {
        return canonicalVariantId;
    }

    public String getSource() {
        return source;
    }

    public Integer getSourceOrdinal() {
        return sourceOrdinal;
    }

    public String getGeneSymbol() {
        return geneSymbol;
    }

    public String getModeOfInheritance() {
        return modeOfInheritance;
    }

    public Integer getDeclaredRank() {
        return declaredRank;
    }

    private static String requireText(String value, String code) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(code);
        }
        return value.trim();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String legacyCandidateId(String canonicalVariantId) {
        String canonical = requireText(canonicalVariantId, "CANONICAL_VARIANT_ID_REQUIRED");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder("legacy-v1-");
            for (int index = 0; index < 12; index++) {
                value.append(String.format(Locale.ROOT, "%02x", digest[index] & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", unavailable);
        }
    }
}
