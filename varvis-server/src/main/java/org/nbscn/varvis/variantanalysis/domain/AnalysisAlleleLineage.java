package org.nbscn.varvis.variantanalysis.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, privacy-safe core result for one source VCF allele through analysis-input preparation.
 *
 * <p>This is a non-wire Phase A object, not an {@code analysis-allele-lineage-v1} NDJSON record.
 * Aggregate cardinality and wire projection remain separate fail-closed responsibilities. The
 * opaque source-allele token is the only join identity; different source tokens may safely
 * converge on the same canonical key. This contract deliberately stores no sample identifier,
 * file location, raw genotype, phase set, or free-text tool error.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AnalysisAlleleLineage {

    public static final String CURRENT_SCHEMA_VERSION = "analysis-allele-lineage-core-v1";

    private static final int MAX_CONTIG_LENGTH = 128;
    private static final int MAX_ALLELE_LENGTH = 16_384;
    private static final Pattern OPAQUE_TOKEN = Pattern.compile("[0-9a-f]{24}");
    private static final Pattern SEQUENCE_ALLELE = Pattern.compile("[ACGTN]+");
    private static final Pattern CANONICAL_CONTIG = Pattern.compile(
            "(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)"
    );
    private static final Pattern ACCESSION_CONTIG = Pattern.compile(
            "(?:GL|KI|JH)[0-9]{6}(?:V[0-9]+|\\.[0-9]+)?"
    );
    private static final Pattern UCSC_AUXILIARY_CONTIG = Pattern.compile(
            "(?:(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)_(?:GL|KI|JH)[0-9]{6}"
                    + "(?:V[0-9]+|\\.[0-9]+)?_(?:ALT|RANDOM)"
                    + "|UN_(?:GL|KI|JH)[0-9]{6}(?:V[0-9]+|\\.[0-9]+)?(?:_RANDOM)?)"
    );

    public enum MappingStatus {
        MAPPED,
        LIFTOVER_REJECTED,
        NORMALIZATION_REJECTED,
        UNRESOLVED
    }

    /** Stable, non-free-text reasons for a source allele not reaching a canonical key. */
    public enum LossCode {
        LIFTOVER_NO_TARGET,
        LIFTOVER_MISMATCHED_REFERENCE,
        LIFTOVER_REVERSE_COMPLEMENTED_INDEL,
        LIFTOVER_INDEL_STRADDLES_MULTIPLE_INTERVALS,
        LIFTOVER_CANNOT_LIFT_OVER,
        LIFTOVER_OTHER_REJECT,
        NORMALIZATION_REFERENCE_MISMATCH,
        NORMALIZATION_UNSUPPORTED_CONTIG,
        NORMALIZATION_INVALID_ALLELE,
        NORMALIZATION_PROCESS_FAILED,
        LEGACY_LINEAGE_UNAVAILABLE,
        UNKNOWN_MAPPING_FAILURE
    }

    private final String schemaVersion;
    private final MappingStatus mappingStatus;
    private final String sourceAlleleToken;
    private final Long sourceRecordOrdinal;
    private final Integer sourceAlternateIndex;
    private final Integer sourceAlternateCount;
    private final String sourceAssembly;
    private final String analysisAssembly;
    private final NormalizedVariantKey sourceKey;
    private final NormalizedVariantKey liftedKey;
    private final NormalizedVariantKey canonicalKey;
    private final LossCode lossCode;

    @JsonCreator
    public AnalysisAlleleLineage(
            @JsonProperty("schemaVersion") String schemaVersion,
            @JsonProperty("mappingStatus") MappingStatus mappingStatus,
            @JsonProperty("sourceAlleleToken") String sourceAlleleToken,
            @JsonProperty("sourceRecordOrdinal") Long sourceRecordOrdinal,
            @JsonProperty("sourceAlternateIndex") Integer sourceAlternateIndex,
            @JsonProperty("sourceAlternateCount") Integer sourceAlternateCount,
            @JsonProperty("sourceAssembly") String sourceAssembly,
            @JsonProperty("analysisAssembly") String analysisAssembly,
            @JsonProperty("sourceKey") NormalizedVariantKey sourceKey,
            @JsonProperty("liftedKey") NormalizedVariantKey liftedKey,
            @JsonProperty("canonicalKey") NormalizedVariantKey canonicalKey,
            @JsonProperty("lossCode") LossCode lossCode) {
        this.schemaVersion = fixedSchema(schemaVersion);
        if (mappingStatus == null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_MAPPING_STATUS_REQUIRED");
        }
        this.mappingStatus = mappingStatus;
        this.sourceAlleleToken = opaqueToken(sourceAlleleToken);
        this.sourceRecordOrdinal = positive(
                sourceRecordOrdinal,
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_RECORD_ORDINAL_INVALID"
        );
        this.sourceAlternateIndex = positive(
                sourceAlternateIndex,
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_ALTERNATE_INDEX_INVALID"
        );
        this.sourceAlternateCount = positive(
                sourceAlternateCount,
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_ALTERNATE_COUNT_INVALID"
        );
        if (this.sourceAlternateIndex > this.sourceAlternateCount) {
            throw new IllegalArgumentException(
                    "ANALYSIS_ALLELE_LINEAGE_SOURCE_ALTERNATE_INDEX_OUT_OF_RANGE"
            );
        }
        this.sourceAssembly = validatedAssembly(
                sourceAssembly,
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_ASSEMBLY_INVALID"
        );
        this.analysisAssembly = validatedAssembly(
                analysisAssembly,
                "ANALYSIS_ALLELE_LINEAGE_ANALYSIS_ASSEMBLY_INVALID"
        );
        this.sourceKey = validatedKey(
                sourceKey,
                "ANALYSIS_ALLELE_LINEAGE_SOURCE_KEY_INVALID"
        );
        this.liftedKey = optionalValidatedKey(
                liftedKey,
                "ANALYSIS_ALLELE_LINEAGE_LIFTED_KEY_INVALID"
        );
        this.canonicalKey = optionalValidatedKey(
                canonicalKey,
                "ANALYSIS_ALLELE_LINEAGE_CANONICAL_KEY_INVALID"
        );
        this.lossCode = lossCode;
        validateState();
    }

    public static AnalysisAlleleLineage mapped(String sourceAlleleToken,
                                                long sourceRecordOrdinal,
                                                int sourceAlternateIndex,
                                                int sourceAlternateCount,
                                                GenomeAssembly sourceAssembly,
                                                GenomeAssembly analysisAssembly,
                                                NormalizedVariantKey sourceKey,
                                                NormalizedVariantKey liftedKey,
                                                NormalizedVariantKey canonicalKey) {
        return create(
                MappingStatus.MAPPED,
                sourceAlleleToken,
                sourceRecordOrdinal,
                sourceAlternateIndex,
                sourceAlternateCount,
                sourceAssembly,
                analysisAssembly,
                sourceKey,
                liftedKey,
                canonicalKey,
                null
        );
    }

    public static AnalysisAlleleLineage liftoverRejected(String sourceAlleleToken,
                                                          long sourceRecordOrdinal,
                                                          int sourceAlternateIndex,
                                                          int sourceAlternateCount,
                                                          GenomeAssembly sourceAssembly,
                                                          GenomeAssembly analysisAssembly,
                                                          NormalizedVariantKey sourceKey,
                                                          LossCode lossCode) {
        return create(
                MappingStatus.LIFTOVER_REJECTED,
                sourceAlleleToken,
                sourceRecordOrdinal,
                sourceAlternateIndex,
                sourceAlternateCount,
                sourceAssembly,
                analysisAssembly,
                sourceKey,
                null,
                null,
                lossCode
        );
    }

    public static AnalysisAlleleLineage normalizationRejected(String sourceAlleleToken,
                                                               long sourceRecordOrdinal,
                                                               int sourceAlternateIndex,
                                                               int sourceAlternateCount,
                                                               GenomeAssembly sourceAssembly,
                                                               GenomeAssembly analysisAssembly,
                                                               NormalizedVariantKey sourceKey,
                                                               NormalizedVariantKey liftedKey,
                                                               LossCode lossCode) {
        return create(
                MappingStatus.NORMALIZATION_REJECTED,
                sourceAlleleToken,
                sourceRecordOrdinal,
                sourceAlternateIndex,
                sourceAlternateCount,
                sourceAssembly,
                analysisAssembly,
                sourceKey,
                liftedKey,
                null,
                lossCode
        );
    }

    public static AnalysisAlleleLineage unresolved(String sourceAlleleToken,
                                                    long sourceRecordOrdinal,
                                                    int sourceAlternateIndex,
                                                    int sourceAlternateCount,
                                                    GenomeAssembly sourceAssembly,
                                                    GenomeAssembly analysisAssembly,
                                                    NormalizedVariantKey sourceKey,
                                                    NormalizedVariantKey liftedKey,
                                                    LossCode lossCode) {
        return create(
                MappingStatus.UNRESOLVED,
                sourceAlleleToken,
                sourceRecordOrdinal,
                sourceAlternateIndex,
                sourceAlternateCount,
                sourceAssembly,
                analysisAssembly,
                sourceKey,
                liftedKey,
                null,
                lossCode
        );
    }

    private static AnalysisAlleleLineage create(MappingStatus mappingStatus,
                                                 String sourceAlleleToken,
                                                 long sourceRecordOrdinal,
                                                 int sourceAlternateIndex,
                                                 int sourceAlternateCount,
                                                 GenomeAssembly sourceAssembly,
                                                 GenomeAssembly analysisAssembly,
                                                 NormalizedVariantKey sourceKey,
                                                 NormalizedVariantKey liftedKey,
                                                 NormalizedVariantKey canonicalKey,
                                                 LossCode lossCode) {
        return new AnalysisAlleleLineage(
                CURRENT_SCHEMA_VERSION,
                mappingStatus,
                sourceAlleleToken,
                sourceRecordOrdinal,
                sourceAlternateIndex,
                sourceAlternateCount,
                sourceAssembly == null ? null : sourceAssembly.name(),
                analysisAssembly == null ? null : analysisAssembly.name(),
                sourceKey,
                liftedKey,
                canonicalKey,
                lossCode
        );
    }

    public String getSchemaVersion() {
        return schemaVersion;
    }

    public MappingStatus getMappingStatus() {
        return mappingStatus;
    }

    public String getSourceAlleleToken() {
        return sourceAlleleToken;
    }

    public Long getSourceRecordOrdinal() {
        return sourceRecordOrdinal;
    }

    public Integer getSourceAlternateIndex() {
        return sourceAlternateIndex;
    }

    public Integer getSourceAlternateCount() {
        return sourceAlternateCount;
    }

    public String getSourceAssembly() {
        return sourceAssembly;
    }

    public String getAnalysisAssembly() {
        return analysisAssembly;
    }

    public NormalizedVariantKey getSourceKey() {
        return sourceKey;
    }

    public NormalizedVariantKey getLiftedKey() {
        return liftedKey;
    }

    public NormalizedVariantKey getCanonicalKey() {
        return canonicalKey;
    }

    public LossCode getLossCode() {
        return lossCode;
    }

    private void validateState() {
        if (!sourceAssembly.equals(sourceKey.getGenomeAssembly())) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_SOURCE_ASSEMBLY_MISMATCH");
        }
        validateLiftedAssembly();
        switch (mappingStatus) {
            case MAPPED:
                validateMapped();
                return;
            case LIFTOVER_REJECTED:
                validateLiftoverRejected();
                return;
            case NORMALIZATION_REJECTED:
                validateNormalizationRejected();
                return;
            case UNRESOLVED:
                validateUnresolved();
                return;
            default:
                throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_MAPPING_STATUS_INVALID");
        }
    }

    private void validateLiftedAssembly() {
        if (liftedKey == null) {
            return;
        }
        if (sourceAssembly.equals(analysisAssembly)) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_LIFTED_KEY_UNEXPECTED");
        }
        if (!analysisAssembly.equals(liftedKey.getGenomeAssembly())) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_LIFTED_ASSEMBLY_MISMATCH");
        }
    }

    private void validateMapped() {
        if (canonicalKey == null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_CANONICAL_KEY_REQUIRED");
        }
        if (lossCode != null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_MAPPED_LOSS_CODE_PRESENT");
        }
        if (!analysisAssembly.equals(canonicalKey.getGenomeAssembly())) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_CANONICAL_ASSEMBLY_MISMATCH");
        }
        boolean crossesAssembly = !sourceAssembly.equals(analysisAssembly);
        if (crossesAssembly && liftedKey == null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_LIFTED_KEY_REQUIRED");
        }
        if (!crossesAssembly && liftedKey != null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_LIFTED_KEY_UNEXPECTED");
        }
    }

    private void validateLiftoverRejected() {
        if (liftedKey != null || canonicalKey != null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_REJECTED_KEY_PRESENT");
        }
        if (sourceAssembly.equals(analysisAssembly)) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_LIFTOVER_NOT_REQUIRED");
        }
        if (!isLiftoverLoss(lossCode)) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_LIFTOVER_LOSS_CODE_INVALID");
        }
    }

    private void validateNormalizationRejected() {
        if (canonicalKey != null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_REJECTED_KEY_PRESENT");
        }
        if (!isNormalizationLoss(lossCode)) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_NORMALIZATION_LOSS_CODE_INVALID");
        }
        if (!sourceAssembly.equals(analysisAssembly) && liftedKey == null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_LIFTED_KEY_REQUIRED");
        }
    }

    private void validateUnresolved() {
        if (canonicalKey != null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_UNRESOLVED_CANONICAL_KEY_PRESENT");
        }
        if (!isUnresolvedLoss(lossCode)) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_UNRESOLVED_LOSS_CODE_INVALID");
        }
    }

    private static boolean isLiftoverLoss(LossCode value) {
        return value == LossCode.LIFTOVER_NO_TARGET
                || value == LossCode.LIFTOVER_MISMATCHED_REFERENCE
                || value == LossCode.LIFTOVER_REVERSE_COMPLEMENTED_INDEL
                || value == LossCode.LIFTOVER_INDEL_STRADDLES_MULTIPLE_INTERVALS
                || value == LossCode.LIFTOVER_CANNOT_LIFT_OVER
                || value == LossCode.LIFTOVER_OTHER_REJECT;
    }

    private static boolean isNormalizationLoss(LossCode value) {
        return value == LossCode.NORMALIZATION_REFERENCE_MISMATCH
                || value == LossCode.NORMALIZATION_UNSUPPORTED_CONTIG
                || value == LossCode.NORMALIZATION_INVALID_ALLELE
                || value == LossCode.NORMALIZATION_PROCESS_FAILED;
    }

    private static boolean isUnresolvedLoss(LossCode value) {
        return value == LossCode.LEGACY_LINEAGE_UNAVAILABLE
                || value == LossCode.UNKNOWN_MAPPING_FAILURE;
    }

    private static String fixedSchema(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_SCHEMA_REQUIRED");
        }
        if (!CURRENT_SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_SCHEMA_INVALID");
        }
        return normalized;
    }

    private static String opaqueToken(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || !OPAQUE_TOKEN.matcher(normalized).matches()) {
            throw new IllegalArgumentException("ANALYSIS_ALLELE_LINEAGE_SOURCE_TOKEN_INVALID");
        }
        return normalized;
    }

    private static Integer positive(Integer value, String errorCode) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(errorCode);
        }
        return value;
    }

    private static Long positive(Long value, String errorCode) {
        if (value == null || value <= 0L) {
            throw new IllegalArgumentException(errorCode);
        }
        return value;
    }

    private static String validatedAssembly(String value, String errorCode) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(errorCode);
        }
        try {
            return GenomeAssembly.valueOf(normalized).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(errorCode);
        }
    }

    private static NormalizedVariantKey optionalValidatedKey(NormalizedVariantKey key,
                                                              String errorCode) {
        return key == null ? null : validatedKey(key, errorCode);
    }

    private static NormalizedVariantKey validatedKey(NormalizedVariantKey key, String errorCode) {
        if (key == null) {
            throw new IllegalArgumentException(errorCode);
        }
        try {
            GenomeAssembly assembly = GenomeAssembly.valueOf(key.getGenomeAssembly());
            NormalizedVariantKey normalized = NormalizedVariantKey.canonical(
                    assembly,
                    key.getChromosome(),
                    key.getPosition(),
                    key.getReference(),
                    key.getAlternate()
            );
            if (!normalized.equals(key)
                    || !isSafeContig(normalized.getChromosome())
                    || !isExplicitSequenceAllele(normalized.getReference())
                    || !isExplicitSequenceAllele(normalized.getAlternate())
                    || normalized.getReference().equals(normalized.getAlternate())) {
                throw new IllegalArgumentException(errorCode);
            }
            return key;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(errorCode);
        }
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static boolean isExplicitSequenceAllele(String value) {
        return value != null
                && value.length() <= MAX_ALLELE_LENGTH
                && SEQUENCE_ALLELE.matcher(value).matches();
    }

    private static boolean isSafeContig(String value) {
        if (value == null || value.length() > MAX_CONTIG_LENGTH) {
            return false;
        }
        return CANONICAL_CONTIG.matcher(value).matches()
                || ACCESSION_CONTIG.matcher(value).matches()
                || UCSC_AUXILIARY_CONTIG.matcher(value).matches();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AnalysisAlleleLineage)) {
            return false;
        }
        AnalysisAlleleLineage that = (AnalysisAlleleLineage) other;
        return Objects.equals(schemaVersion, that.schemaVersion)
                && mappingStatus == that.mappingStatus
                && Objects.equals(sourceAlleleToken, that.sourceAlleleToken)
                && Objects.equals(sourceRecordOrdinal, that.sourceRecordOrdinal)
                && Objects.equals(sourceAlternateIndex, that.sourceAlternateIndex)
                && Objects.equals(sourceAlternateCount, that.sourceAlternateCount)
                && Objects.equals(sourceAssembly, that.sourceAssembly)
                && Objects.equals(analysisAssembly, that.analysisAssembly)
                && Objects.equals(sourceKey, that.sourceKey)
                && Objects.equals(liftedKey, that.liftedKey)
                && Objects.equals(canonicalKey, that.canonicalKey)
                && lossCode == that.lossCode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                schemaVersion,
                mappingStatus,
                sourceAlleleToken,
                sourceRecordOrdinal,
                sourceAlternateIndex,
                sourceAlternateCount,
                sourceAssembly,
                analysisAssembly,
                sourceKey,
                liftedKey,
                canonicalKey,
                lossCode
        );
    }
}
