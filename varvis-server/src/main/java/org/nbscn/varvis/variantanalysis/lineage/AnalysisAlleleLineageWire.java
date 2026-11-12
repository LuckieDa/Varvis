package org.nbscn.varvis.variantanalysis.lineage;

import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Immutable, product-detached wire-domain types for {@code analysis-allele-lineage-v1}.
 *
 * <p>These types deliberately have no Jackson annotations and are not product DTOs. The only
 * supported JSON representation is the explicit canonical projector. Phase A2 performs no file
 * I/O and does not connect these records to a job, engine, repository, API, or ranking path.</p>
 */
public final class AnalysisAlleleLineageWire {

    public static final String SCHEMA_VERSION = "analysis-allele-lineage-v1";
    public static final String GENERATOR_VERSION = "varvis-lineage-writer-v1";
    public static final String PIPELINE_PROFILE_ID = "convert-before-normalize-v1";
    public static final String SOURCE_TOKEN_ALGORITHM = "hmac-sha256-trunc96-be-v1";
    public static final String NORMALIZATION_PROFILE_ID = "bcftools-analysis-reference-v1";
    public static final String NORMALIZATION_TOOL = "bcftools";
    public static final String ENGINE_NAME = "exomiser";
    public static final String ENGINE_PARSER_SCHEMA_VERSION = "exomiser-output-parser-v1";
    public static final String CANDIDATE_IDENTITY_SCHEMA_VERSION = "candidate-identity-v1";
    public static final String LIMIT_PROFILE_ID = "analysis-allele-lineage-limits-v1";
    public static final String FINGERPRINT_ALGORITHM = "sha256-canonical-ndjson-v1";

    public static final long MAX_SIDECAR_BYTES = 536_870_912L;
    public static final int MAX_LINE_BYTES = 65_536;
    public static final long MAX_BODY_RECORD_COUNT = 2_500_000L;
    public static final long MAX_INPUT_ALLELE_COUNT = 1_000_000L;
    public static final long MAX_CANDIDATE_LINK_COUNT = 100_000L;
    public static final int MAX_ALLELE_BYTES = 16_384;
    public static final int MAX_RELATION_FAN_IN = 1_024;
    public static final int MAX_RELATION_FAN_OUT = 1_024;
    public static final int MAX_REASON_CODES = 16;
    public static final int MAX_ORDINARY_STRING_BYTES = 1_024;

    private AnalysisAlleleLineageWire() {
    }

    /** Deterministic v1 engine identity derived only from the canonical analysis allele ID. */
    public static String engineAlleleId(String analysisCanonicalVariantId) {
        if (analysisCanonicalVariantId == null || analysisCanonicalVariantId.isEmpty()) {
            throw new LineageValidationException(
                    ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "analysisCanonicalVariantId");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    analysisCanonicalVariantId.getBytes(StandardCharsets.UTF_8));
            return "engine-allele-v1-" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", exception);
        }
    }

    public enum RecordType {
        HEADER,
        INPUT_RECORD,
        ALLELE_LINEAGE,
        CANDIDATE_LINK,
        FOOTER
    }

    public enum InputRecordStatus {
        TARGETS_CAPTURED,
        NO_TARGET_ALLELES
    }

    public enum InputStatus {
        ACCEPTED,
        REJECTED
    }

    public enum LiftoverPolicy {
        NOT_REQUIRED,
        REQUIRED,
        DISABLED
    }

    public enum LiftoverStatus {
        NOT_REQUIRED,
        LIFTED,
        UNMAPPED,
        REF_MISMATCH,
        AMBIGUOUS,
        REJECTED,
        UNAVAILABLE,
        SKIPPED,
        NOT_SUBMITTED
    }

    public enum NormalizationStatus {
        IDENTITY,
        NORMALIZED,
        UNRESOLVED,
        REJECTED,
        NOT_SUBMITTED
    }

    public enum EngineMatchStatus {
        MATCHED,
        NOT_FOUND,
        AMBIGUOUS,
        CONFLICT,
        NOT_SUBMITTED
    }

    public enum RelationCardinality {
        ONE_TO_ZERO,
        ONE_TO_ONE,
        ONE_TO_MANY
    }

    public enum LiftoverTransform {
        IDENTITY,
        FORWARD,
        REVERSE_COMPLEMENT,
        REF_ALT_SWAP,
        REVERSE_COMPLEMENT_AND_SWAP
    }

    public enum NormalizationTransform {
        IDENTITY,
        CONTIG_ALIAS,
        TRIMMED,
        LEFT_ALIGNED,
        SPLIT
    }

    public enum CandidateSource {
        EXOMISER
    }

    public enum CandidateLinkStatus {
        LINKED,
        UNMATCHED,
        AMBIGUOUS,
        CONFLICT
    }

    public enum DocumentStatus {
        COMPLETE,
        PARTIAL,
        FAILED,
        LIMIT_EXCEEDED
    }

    /**
     * Declaration order is the canonical order used for all reason-code arrays.
     */
    public enum ReasonCode {
        INPUT_RECORD_MALFORMED,
        INPUT_ALT_INDEX_INVALID,
        INPUT_ALT_EMPTY,
        INPUT_DUPLICATE_ALT,
        INPUT_ALLELE_UNSUPPORTED,

        LIFTOVER_NO_TARGET,
        LIFTOVER_MISMATCHED_REFERENCE,
        LIFTOVER_REVERSE_COMPLEMENTED_INDEL,
        LIFTOVER_INDEL_STRADDLES_MULTIPLE_INTERVALS,
        LIFTOVER_CANNOT_LIFT_OVER,
        LIFTOVER_OTHER_REJECT,
        LIFTOVER_NOT_REQUIRED,
        LIFTOVER_AMBIGUOUS,
        LIFTOVER_UNAVAILABLE,
        LIFTOVER_SKIPPED_BY_CAP,
        LIFTOVER_PARTIAL,
        LIFTOVER_TRANSFORM_UNSUPPORTED,

        NORMALIZATION_REFERENCE_MISMATCH,
        NORMALIZATION_UNSUPPORTED_CONTIG,
        NORMALIZATION_INVALID_ALLELE,
        NORMALIZATION_PROCESS_FAILED,
        NORMALIZATION_ORIGINAL_UNRESOLVED,
        NORMALIZATION_TOOL_UNAVAILABLE,
        NORMALIZATION_TOOL_TIMEOUT,
        NORMALIZATION_OUTPUT_INVALID,
        NORMALIZATION_ONE_TO_MANY_UNSUPPORTED,

        LEGACY_LINEAGE_UNAVAILABLE,
        UNKNOWN_MAPPING_FAILURE,

        ENGINE_ALLELE_NOT_FOUND,
        ENGINE_ALLELE_AMBIGUOUS,
        ENGINE_ALLELE_CONFLICT,
        ENGINE_NOT_SUBMITTED,
        ENGINE_OUTPUT_INVALID,

        CANDIDATE_ALLELE_UNMATCHED,
        CANDIDATE_ALLELE_AMBIGUOUS,
        CANDIDATE_IDENTITY_CONFLICT,
        CANDIDATE_SOURCE_ORDINAL_INVALID,

        LINEAGE_SCHEMA_UNSUPPORTED,
        LINEAGE_UNKNOWN_FIELD,
        LINEAGE_REQUIRED_FIELD_MISSING,
        LINEAGE_FIELD_INVALID,
        LINEAGE_ORDER_INVALID,
        LINEAGE_DUPLICATE_ID,
        LINEAGE_DANGLING_REFERENCE,
        LINEAGE_CARDINALITY_CONFLICT,
        LINEAGE_ASSEMBLY_TRANSITION_INVALID,
        LINEAGE_FINGERPRINT_MISMATCH,
        LINEAGE_ARTIFACT_FINGERPRINT_MISMATCH,
        LINEAGE_RESOURCE_FINGERPRINT_MISSING,
        LINEAGE_COUNT_MISMATCH,
        LINEAGE_FOOTER_MISSING,
        LINEAGE_SIZE_LIMIT_EXCEEDED,
        LINEAGE_LINE_LIMIT_EXCEEDED,
        LINEAGE_RECORD_LIMIT_EXCEEDED,
        LINEAGE_ALLELE_LIMIT_EXCEEDED,
        LINEAGE_FAN_IN_LIMIT_EXCEEDED,
        LINEAGE_FAN_OUT_LIMIT_EXCEEDED,
        LINEAGE_LEGACY_ABSENT,
        LINEAGE_LEGACY_UNVERIFIED,
        LINEAGE_LEGACY_MULTI_ALT_UNRESOLVED,
        LINEAGE_REUSE_BINDING_MISMATCH,
        LINEAGE_REUSE_STATUS_INELIGIBLE
    }

    public sealed interface WireRecord permits HeaderRecord, InputRecord, AlleleRecord,
            CandidateLinkRecord, FooterRecord {

        String schemaVersion();

        RecordType recordType();

        long recordOrdinal();
    }

    public record Limits(long maxSidecarBytes,
                         int maxLineBytes,
                         long maxRecordCount,
                         long maxInputAlleleCount,
                         long maxCandidateLinkCount,
                         int maxAlleleBytes,
                         int maxRelationFanIn,
                         int maxRelationFanOut) {

        @Override
        public String toString() {
            return "Limits[redacted]";
        }
    }

    public record HeaderRecord(String schemaVersion,
                               RecordType recordType,
                               long recordOrdinal,
                               String generatorVersion,
                               String pipelineProfileId,
                               String sourceAlleleTokenAlgorithm,
                               GenomeAssembly inputAssembly,
                               GenomeAssembly analysisAssembly,
                               String inputArtifactSha256,
                               LiftoverPolicy liftoverPolicy,
                               String liftoverTool,
                               String liftoverToolVersion,
                               String liftoverChainSha256,
                               String liftoverReferenceSha256,
                               String normalizationProfileId,
                               String normalizationReferenceSha256,
                               String normalizationTool,
                               String normalizationToolVersion,
                               String normalizationCommandDigest,
                               String engineName,
                               String engineVersion,
                               String engineResourceFingerprint,
                               String engineParserSchemaVersion,
                               String candidateIdentitySchemaVersion,
                               String limitProfileId,
                               Limits limits) implements WireRecord {

        @Override
        public String toString() {
            return "HeaderRecord[redacted]";
        }
    }

    /**
     * One record-level summary makes inputRecordCount independently recomputable, including a
     * valid VCF data record whose ALT column is '.'. Malformed physical input does not produce a
     * publishable v1 document.
     */
    public record InputRecord(String schemaVersion,
                              RecordType recordType,
                              long recordOrdinal,
                              long sourceRecordOrdinal,
                              int sourceAlternateCount,
                              InputRecordStatus inputRecordStatus,
                              List<ReasonCode> reasonCodes) implements WireRecord {

        public InputRecord {
            reasonCodes = immutableCopy(reasonCodes);
        }

        @Override
        public String toString() {
            return "InputRecord[redacted]";
        }
    }

    public record AlleleKey(GenomeAssembly assembly,
                            String chromosome,
                            int position,
                            String reference,
                            String alternate,
                            String canonicalVariantId) {

        public static AlleleKey from(NormalizedVariantKey key) {
            if (key == null) {
                throw new LineageValidationException(
                        ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "normalizedVariantKey");
            }
            try {
                GenomeAssembly assembly = GenomeAssembly.valueOf(key.getGenomeAssembly());
                return new AlleleKey(
                        assembly,
                        key.getChromosome(),
                        key.getPosition(),
                        key.getReference(),
                        key.getAlternate(),
                        key.canonicalId()
                );
            } catch (RuntimeException exception) {
                throw new LineageValidationException(
                        ReasonCode.LINEAGE_FIELD_INVALID, "normalizedVariantKey");
            }
        }

        @Override
        public String toString() {
            return "AlleleKey[redacted]";
        }
    }

    public record EngineAlleleRef(String engineAlleleId,
                                  String analysisCanonicalVariantId) {

        public static EngineAlleleRef forCanonical(AlleleKey key) {
            if (key == null) {
                throw new LineageValidationException(
                        ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "alleleKey");
            }
            return new EngineAlleleRef(
                    AnalysisAlleleLineageWire.engineAlleleId(key.canonicalVariantId()),
                    key.canonicalVariantId()
            );
        }

        @Override
        public String toString() {
            return "EngineAlleleRef[redacted]";
        }
    }

    public record AlleleRecord(String schemaVersion,
                               RecordType recordType,
                               long recordOrdinal,
                               String sourceAlleleToken,
                               long sourceRecordOrdinal,
                               int sourceAlternateIndex,
                               int sourceAlternateCount,
                               InputStatus inputStatus,
                               List<ReasonCode> inputReasonCodes,
                               AlleleKey sourceKey,
                               LiftoverStatus liftoverStatus,
                               List<ReasonCode> liftoverReasonCodes,
                               List<AlleleKey> liftedKeys,
                               RelationCardinality liftoverCardinality,
                               List<LiftoverTransform> liftoverTransforms,
                               Boolean targetReferenceValidated,
                               NormalizationStatus normalizationStatus,
                               List<ReasonCode> normalizationReasonCodes,
                               List<AlleleKey> canonicalKeys,
                               RelationCardinality normalizationCardinality,
                               List<NormalizationTransform> normalizationTransforms,
                               EngineMatchStatus engineMatchStatus,
                               List<ReasonCode> engineReasonCodes,
                               List<EngineAlleleRef> engineAlleles) implements WireRecord {

        public AlleleRecord {
            inputReasonCodes = immutableCopy(inputReasonCodes);
            liftoverReasonCodes = immutableCopy(liftoverReasonCodes);
            liftedKeys = immutableCopy(liftedKeys);
            liftoverTransforms = immutableCopy(liftoverTransforms);
            normalizationReasonCodes = immutableCopy(normalizationReasonCodes);
            canonicalKeys = immutableCopy(canonicalKeys);
            normalizationTransforms = immutableCopy(normalizationTransforms);
            engineReasonCodes = immutableCopy(engineReasonCodes);
            engineAlleles = immutableCopy(engineAlleles);
        }

        @Override
        public String toString() {
            return "AlleleRecord[redacted]";
        }
    }

    public record CandidateLinkRecord(String schemaVersion,
                                      RecordType recordType,
                                      long recordOrdinal,
                                      String candidateId,
                                      CandidateSource candidateSource,
                                      long candidateSourceOrdinal,
                                      String engineAlleleId,
                                      String analysisCanonicalVariantId,
                                      CandidateLinkStatus linkStatus,
                                      List<ReasonCode> reasonCodes) implements WireRecord {

        public CandidateLinkRecord {
            reasonCodes = immutableCopy(reasonCodes);
        }

        @Override
        public String toString() {
            return "CandidateLinkRecord[redacted]";
        }
    }

    public record FooterRecord(String schemaVersion,
                               RecordType recordType,
                               long recordOrdinal,
                               DocumentStatus status,
                               List<ReasonCode> reasonCodes,
                               long inputRecordCount,
                               long inputAlleleCount,
                               long liftedAlleleCount,
                               long canonicalAlleleCount,
                               long engineAlleleCount,
                               long candidateLinkCount,
                               long unresolvedAlleleCount,
                               long rejectedAlleleCount,
                               String liftedArtifactSha256,
                               String canonicalArtifactSha256,
                               String engineArtifactSha256,
                               String fingerprintAlgorithm,
                               String contentFingerprint) implements WireRecord {

        public FooterRecord {
            reasonCodes = immutableCopy(reasonCodes);
        }

        public FooterRecord withContentFingerprint(String fingerprint) {
            return new FooterRecord(
                    schemaVersion,
                    recordType,
                    recordOrdinal,
                    status,
                    reasonCodes,
                    inputRecordCount,
                    inputAlleleCount,
                    liftedAlleleCount,
                    canonicalAlleleCount,
                    engineAlleleCount,
                    candidateLinkCount,
                    unresolvedAlleleCount,
                    rejectedAlleleCount,
                    liftedArtifactSha256,
                    canonicalArtifactSha256,
                    engineArtifactSha256,
                    fingerprintAlgorithm,
                    fingerprint
            );
        }

        @Override
        public String toString() {
            return "FooterRecord[redacted]";
        }
    }

    public record Document(HeaderRecord header,
                           List<InputRecord> inputRecords,
                           List<AlleleRecord> alleleRecords,
                           List<CandidateLinkRecord> candidateLinks,
                           FooterRecord footer) {

        public Document {
            inputRecords = immutableCopy(inputRecords);
            alleleRecords = immutableCopy(alleleRecords);
            candidateLinks = immutableCopy(candidateLinks);
        }

        public Document withFooter(FooterRecord replacement) {
            return new Document(header, inputRecords, alleleRecords, candidateLinks, replacement);
        }

        public List<WireRecord> recordsInCanonicalSections() {
            List<WireRecord> records = new ArrayList<>();
            records.add(header);
            records.addAll(inputRecords);
            records.addAll(alleleRecords);
            records.addAll(candidateLinks);
            records.add(footer);
            return Collections.unmodifiableList(records);
        }

        @Override
        public String toString() {
            return "Document[redacted]";
        }
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
