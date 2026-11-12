package org.nbscn.varvis.variantanalysis.lineage;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateSource;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.DocumentStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineAlleleRef;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineMatchStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecordStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.LiftoverPolicy;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.LiftoverStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.LiftoverTransform;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationStatus;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.NormalizationTransform;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RelationCardinality;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Strict parser for one canonical {@code analysis-allele-lineage-v1} JSON record.
 *
 * <p>The parser is deliberately independent of the application {@code ObjectMapper}. It rejects
 * JSON extensions, duplicate or unknown keys, scalar coercion, invalid Unicode, non-canonical
 * field ordering/escaping/whitespace, and any line above the v1 hard ceiling. Parser exceptions
 * and source bytes are never copied into the public validation exception.</p>
 */
final class AnalysisAlleleLineageRecordParser {

    private static final Set<String> COMMON_FIELDS = Set.of(
            "schemaVersion", "recordType", "recordOrdinal"
    );
    private static final Set<String> HEADER_FIELDS = Set.of(
            "generatorVersion", "pipelineProfileId", "sourceAlleleTokenAlgorithm",
            "inputAssembly", "analysisAssembly", "inputArtifactSha256", "liftoverPolicy",
            "liftoverTool", "liftoverToolVersion", "liftoverChainSha256",
            "liftoverReferenceSha256", "normalizationProfileId",
            "normalizationReferenceSha256", "normalizationTool", "normalizationToolVersion",
            "normalizationCommandDigest", "engineName", "engineVersion",
            "engineResourceFingerprint", "engineParserSchemaVersion",
            "candidateIdentitySchemaVersion", "limitProfileId", "maxSidecarBytes",
            "maxLineBytes", "maxRecordCount", "maxInputAlleleCount",
            "maxCandidateLinkCount", "maxAlleleBytes", "maxRelationFanIn",
            "maxRelationFanOut"
    );
    private static final Set<String> INPUT_FIELDS = Set.of(
            "sourceRecordOrdinal", "sourceAlternateCount", "inputRecordStatus", "reasonCodes"
    );
    private static final Set<String> ALLELE_FIELDS = Set.of(
            "sourceAlleleToken", "sourceRecordOrdinal", "sourceAlternateIndex",
            "sourceAlternateCount",
            "inputStatus", "inputReasonCodes", "sourceKey", "liftoverStatus",
            "liftoverReasonCodes", "liftedKeys", "liftoverCardinality",
            "liftoverTransforms", "targetReferenceValidated", "normalizationStatus",
            "normalizationReasonCodes", "canonicalKeys", "normalizationCardinality",
            "normalizationTransforms", "engineMatchStatus", "engineReasonCodes",
            "engineAlleles"
    );
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "candidateId", "candidateSource", "candidateSourceOrdinal", "engineAlleleId",
            "analysisCanonicalVariantId", "linkStatus", "reasonCodes"
    );
    private static final Set<String> FOOTER_FIELDS = Set.of(
            "status", "reasonCodes",
            "inputRecordCount", "inputAlleleCount", "liftedAlleleCount",
            "canonicalAlleleCount", "engineAlleleCount", "candidateLinkCount",
            "unresolvedAlleleCount", "rejectedAlleleCount", "liftedArtifactSha256",
            "canonicalArtifactSha256", "engineArtifactSha256", "fingerprintAlgorithm",
            "contentFingerprint"
    );
    private static final Set<String> ALLELE_KEY_FIELDS = Set.of(
            "assembly", "chromosome", "position", "reference", "alternate",
            "canonicalVariantId"
    );
    private static final Set<String> ENGINE_REF_FIELDS = Set.of(
            "engineAlleleId", "analysisCanonicalVariantId"
    );
    private static final ThreadLocal<Set<String>> ACTIVE_ROOT_FIELDS = new ThreadLocal<>();

    private final JsonFactory jsonFactory;
    private final AnalysisAlleleLineageCanonicalProjector projector;

    AnalysisAlleleLineageRecordParser() {
        this(new AnalysisAlleleLineageCanonicalProjector());
    }

    AnalysisAlleleLineageRecordParser(AnalysisAlleleLineageCanonicalProjector projector) {
        this.projector = projector;
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(4)
                .maxDocumentLength(AnalysisAlleleLineageWire.MAX_LINE_BYTES)
                .maxTokenCount(65_536)
                .maxNumberLength(20)
                .maxStringLength(AnalysisAlleleLineageWire.MAX_LINE_BYTES)
                .maxNameLength(64)
                .build();
        this.jsonFactory = JsonFactory.builder()
                .streamReadConstraints(constraints)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .disable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
                .disable(JsonReadFeature.ALLOW_YAML_COMMENTS)
                .disable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
                .disable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
                .disable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
                .disable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
                .disable(JsonReadFeature.ALLOW_RS_CONTROL_CHAR)
                .disable(JsonReadFeature.ALLOW_LEADING_DECIMAL_POINT_FOR_NUMBERS)
                .disable(JsonReadFeature.ALLOW_LEADING_PLUS_SIGN_FOR_NUMBERS)
                .disable(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS)
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .disable(JsonReadFeature.ALLOW_TRAILING_DECIMAL_POINT_FOR_NUMBERS)
                .disable(JsonReadFeature.ALLOW_MISSING_VALUES)
                .disable(JsonReadFeature.ALLOW_TRAILING_COMMA)
                .build();
    }

    /** Parses exactly one canonical JSON object without a trailing LF. */
    WireRecord parse(byte[] line) {
        if (line == null) {
            throw new LineageValidationException(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "line");
        }
        if (line.length == 0) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "json");
        }
        if (line.length > AnalysisAlleleLineageWire.MAX_LINE_BYTES) {
            fail(ReasonCode.LINEAGE_LINE_LIMIT_EXCEEDED, "maxLineBytes");
        }
        if (hasBom(line)) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "encoding");
        }

        WireRecord record;
        try (JsonParser parser = jsonFactory.createParser(line)) {
            expectToken(parser.nextToken(), JsonToken.START_OBJECT, "json");
            expectField(parser, "schemaVersion", COMMON_FIELDS);
            String schemaVersion = readNullableString(parser, "schemaVersion",
                    AnalysisAlleleLineageWire.MAX_ORDINARY_STRING_BYTES);
            expectField(parser, "recordType", COMMON_FIELDS);
            RecordType recordType = readRequiredEnum(parser, RecordType.class, "recordType");
            expectField(parser, "recordOrdinal", COMMON_FIELDS);
            long recordOrdinal = readLong(parser, "recordOrdinal");

            ACTIVE_ROOT_FIELDS.set(fieldsFor(recordType));
            try {
                record = switch (recordType) {
                    case HEADER -> parseHeader(parser, schemaVersion, recordType, recordOrdinal);
                    case INPUT_RECORD -> parseInputRecord(
                            parser, schemaVersion, recordType, recordOrdinal);
                    case ALLELE_LINEAGE -> parseAlleleRecord(
                            parser, schemaVersion, recordType, recordOrdinal);
                    case CANDIDATE_LINK -> parseCandidateLink(
                            parser, schemaVersion, recordType, recordOrdinal);
                    case FOOTER -> parseFooter(parser, schemaVersion, recordType, recordOrdinal);
                };
            } finally {
                ACTIVE_ROOT_FIELDS.remove();
            }
            if (parser.nextToken() != null) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "json");
            }
        } catch (LineageValidationException exception) {
            throw exception;
        } catch (IOException exception) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "json");
            return null;
        }

        byte[] canonical = projector.canonicalRecordBytes(record);
        if (!Arrays.equals(line, canonical)) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "canonicalEncoding");
        }
        return record;
    }

    private static HeaderRecord parseHeader(JsonParser parser,
                                            String schemaVersion,
                                            RecordType recordType,
                                            long recordOrdinal) throws IOException {
        String generatorVersion = stringField(parser, "generatorVersion");
        String pipelineProfileId = stringField(parser, "pipelineProfileId");
        String sourceAlleleTokenAlgorithm = stringField(parser, "sourceAlleleTokenAlgorithm");
        GenomeAssembly inputAssembly = enumField(parser, "inputAssembly", GenomeAssembly.class);
        GenomeAssembly analysisAssembly = enumField(parser, "analysisAssembly", GenomeAssembly.class);
        String inputArtifactSha256 = stringField(parser, "inputArtifactSha256");
        LiftoverPolicy liftoverPolicy = enumField(parser, "liftoverPolicy", LiftoverPolicy.class);
        String liftoverTool = stringField(parser, "liftoverTool");
        String liftoverToolVersion = stringField(parser, "liftoverToolVersion");
        String liftoverChainSha256 = stringField(parser, "liftoverChainSha256");
        String liftoverReferenceSha256 = stringField(parser, "liftoverReferenceSha256");
        String normalizationProfileId = stringField(parser, "normalizationProfileId");
        String normalizationReferenceSha256 = stringField(parser, "normalizationReferenceSha256");
        String normalizationTool = stringField(parser, "normalizationTool");
        String normalizationToolVersion = stringField(parser, "normalizationToolVersion");
        String normalizationCommandDigest = stringField(parser, "normalizationCommandDigest");
        String engineName = stringField(parser, "engineName");
        String engineVersion = stringField(parser, "engineVersion");
        String engineResourceFingerprint = stringField(parser, "engineResourceFingerprint");
        String engineParserSchemaVersion = stringField(parser, "engineParserSchemaVersion");
        String candidateIdentitySchemaVersion = stringField(parser,
                "candidateIdentitySchemaVersion");
        String limitProfileId = stringField(parser, "limitProfileId");
        long maxSidecarBytes = longField(parser, "maxSidecarBytes");
        int maxLineBytes = intField(parser, "maxLineBytes");
        long maxRecordCount = longField(parser, "maxRecordCount");
        long maxInputAlleleCount = longField(parser, "maxInputAlleleCount");
        long maxCandidateLinkCount = longField(parser, "maxCandidateLinkCount");
        int maxAlleleBytes = intField(parser, "maxAlleleBytes");
        int maxRelationFanIn = intField(parser, "maxRelationFanIn");
        int maxRelationFanOut = intField(parser, "maxRelationFanOut");
        expectEndObject(parser, activeRootFields());
        return new HeaderRecord(
                schemaVersion, recordType, recordOrdinal, generatorVersion, pipelineProfileId,
                sourceAlleleTokenAlgorithm, inputAssembly, analysisAssembly, inputArtifactSha256,
                liftoverPolicy, liftoverTool, liftoverToolVersion, liftoverChainSha256,
                liftoverReferenceSha256, normalizationProfileId, normalizationReferenceSha256,
                normalizationTool, normalizationToolVersion, normalizationCommandDigest,
                engineName, engineVersion, engineResourceFingerprint, engineParserSchemaVersion,
                candidateIdentitySchemaVersion, limitProfileId,
                new Limits(maxSidecarBytes, maxLineBytes, maxRecordCount, maxInputAlleleCount,
                        maxCandidateLinkCount, maxAlleleBytes, maxRelationFanIn,
                        maxRelationFanOut));
    }

    private static InputRecord parseInputRecord(JsonParser parser,
                                                String schemaVersion,
                                                RecordType recordType,
                                                long recordOrdinal) throws IOException {
        long sourceRecordOrdinal = longField(parser, "sourceRecordOrdinal");
        int sourceAlternateCount = intField(parser, "sourceAlternateCount");
        InputRecordStatus status = enumField(
                parser, "inputRecordStatus", InputRecordStatus.class);
        List<ReasonCode> reasons = enumArrayField(
                parser, "reasonCodes", ReasonCode.class,
                AnalysisAlleleLineageWire.MAX_REASON_CODES);
        expectEndObject(parser, activeRootFields());
        return new InputRecord(schemaVersion, recordType, recordOrdinal, sourceRecordOrdinal,
                sourceAlternateCount, status, reasons);
    }

    private static AlleleRecord parseAlleleRecord(JsonParser parser,
                                                  String schemaVersion,
                                                  RecordType recordType,
                                                  long recordOrdinal) throws IOException {
        String sourceAlleleToken = stringField(parser, "sourceAlleleToken");
        long sourceRecordOrdinal = longField(parser, "sourceRecordOrdinal");
        int sourceAlternateIndex = intField(parser, "sourceAlternateIndex");
        int sourceAlternateCount = intField(parser, "sourceAlternateCount");
        InputStatus inputStatus = enumField(parser, "inputStatus", InputStatus.class);
        List<ReasonCode> inputReasons = enumArrayField(
                parser, "inputReasonCodes", ReasonCode.class,
                AnalysisAlleleLineageWire.MAX_REASON_CODES);
        AlleleKey sourceKey = alleleKeyField(parser, "sourceKey", true);
        LiftoverStatus liftoverStatus = enumField(parser, "liftoverStatus", LiftoverStatus.class);
        List<ReasonCode> liftoverReasons = enumArrayField(
                parser, "liftoverReasonCodes", ReasonCode.class,
                AnalysisAlleleLineageWire.MAX_REASON_CODES);
        List<AlleleKey> liftedKeys = alleleKeyArrayField(parser, "liftedKeys");
        RelationCardinality liftoverCardinality = enumField(
                parser, "liftoverCardinality", RelationCardinality.class);
        List<LiftoverTransform> liftoverTransforms = enumArrayField(
                parser, "liftoverTransforms", LiftoverTransform.class,
                AnalysisAlleleLineageWire.MAX_RELATION_FAN_OUT);
        Boolean targetReferenceValidated = booleanField(parser, "targetReferenceValidated");
        NormalizationStatus normalizationStatus = enumField(
                parser, "normalizationStatus", NormalizationStatus.class);
        List<ReasonCode> normalizationReasons = enumArrayField(
                parser, "normalizationReasonCodes", ReasonCode.class,
                AnalysisAlleleLineageWire.MAX_REASON_CODES);
        List<AlleleKey> canonicalKeys = alleleKeyArrayField(parser, "canonicalKeys");
        RelationCardinality normalizationCardinality = enumField(
                parser, "normalizationCardinality", RelationCardinality.class);
        List<NormalizationTransform> normalizationTransforms = enumArrayField(
                parser, "normalizationTransforms", NormalizationTransform.class,
                AnalysisAlleleLineageWire.MAX_RELATION_FAN_OUT);
        EngineMatchStatus engineMatchStatus = enumField(
                parser, "engineMatchStatus", EngineMatchStatus.class);
        List<ReasonCode> engineReasons = enumArrayField(
                parser, "engineReasonCodes", ReasonCode.class,
                AnalysisAlleleLineageWire.MAX_REASON_CODES);
        List<EngineAlleleRef> engineAlleles = engineRefArrayField(parser, "engineAlleles");
        expectEndObject(parser, activeRootFields());
        return new AlleleRecord(
                schemaVersion, recordType, recordOrdinal, sourceAlleleToken,
                sourceRecordOrdinal, sourceAlternateIndex, sourceAlternateCount, inputStatus,
                inputReasons, sourceKey, liftoverStatus, liftoverReasons, liftedKeys,
                liftoverCardinality, liftoverTransforms, targetReferenceValidated,
                normalizationStatus, normalizationReasons, canonicalKeys,
                normalizationCardinality, normalizationTransforms, engineMatchStatus,
                engineReasons, engineAlleles);
    }

    private static CandidateLinkRecord parseCandidateLink(JsonParser parser,
                                                           String schemaVersion,
                                                           RecordType recordType,
                                                           long recordOrdinal) throws IOException {
        String candidateId = stringField(parser, "candidateId");
        CandidateSource candidateSource = enumField(
                parser, "candidateSource", CandidateSource.class);
        long candidateSourceOrdinal = longField(parser, "candidateSourceOrdinal");
        String engineAlleleId = stringField(parser, "engineAlleleId");
        String canonicalId = stringField(parser, "analysisCanonicalVariantId",
                AnalysisAlleleLineageWire.MAX_LINE_BYTES);
        CandidateLinkStatus linkStatus = enumField(
                parser, "linkStatus", CandidateLinkStatus.class);
        List<ReasonCode> reasons = enumArrayField(
                parser, "reasonCodes", ReasonCode.class,
                AnalysisAlleleLineageWire.MAX_REASON_CODES);
        expectEndObject(parser, activeRootFields());
        return new CandidateLinkRecord(schemaVersion, recordType, recordOrdinal, candidateId,
                candidateSource, candidateSourceOrdinal, engineAlleleId, canonicalId,
                linkStatus, reasons);
    }

    private static FooterRecord parseFooter(JsonParser parser,
                                            String schemaVersion,
                                            RecordType recordType,
                                            long recordOrdinal) throws IOException {
        DocumentStatus status = enumField(parser, "status", DocumentStatus.class);
        List<ReasonCode> reasons = enumArrayField(
                parser, "reasonCodes", ReasonCode.class,
                AnalysisAlleleLineageWire.MAX_REASON_CODES);
        long inputRecordCount = longField(parser, "inputRecordCount");
        long inputAlleleCount = longField(parser, "inputAlleleCount");
        long liftedAlleleCount = longField(parser, "liftedAlleleCount");
        long canonicalAlleleCount = longField(parser, "canonicalAlleleCount");
        long engineAlleleCount = longField(parser, "engineAlleleCount");
        long candidateLinkCount = longField(parser, "candidateLinkCount");
        long unresolvedAlleleCount = longField(parser, "unresolvedAlleleCount");
        long rejectedAlleleCount = longField(parser, "rejectedAlleleCount");
        String liftedArtifactSha256 = stringField(parser, "liftedArtifactSha256");
        String canonicalArtifactSha256 = stringField(parser, "canonicalArtifactSha256");
        String engineArtifactSha256 = stringField(parser, "engineArtifactSha256");
        String fingerprintAlgorithm = stringField(parser, "fingerprintAlgorithm");
        String contentFingerprint = stringField(parser, "contentFingerprint");
        expectEndObject(parser, activeRootFields());
        return new FooterRecord(
                schemaVersion, recordType, recordOrdinal, status, reasons, inputRecordCount,
                inputAlleleCount, liftedAlleleCount, canonicalAlleleCount, engineAlleleCount,
                candidateLinkCount, unresolvedAlleleCount, rejectedAlleleCount,
                liftedArtifactSha256, canonicalArtifactSha256, engineArtifactSha256,
                fingerprintAlgorithm, contentFingerprint);
    }

    private static String stringField(JsonParser parser, String field) throws IOException {
        return stringField(parser, field, AnalysisAlleleLineageWire.MAX_ORDINARY_STRING_BYTES);
    }

    private static String stringField(JsonParser parser,
                                      String field,
                                      int maxUtf8Bytes) throws IOException {
        expectField(parser, field, activeRootFields());
        return readNullableString(parser, field, maxUtf8Bytes);
    }

    private static long longField(JsonParser parser, String field) throws IOException {
        expectField(parser, field, activeRootFields());
        return readLong(parser, field);
    }

    private static int intField(JsonParser parser, String field) throws IOException {
        expectField(parser, field, activeRootFields());
        return readInt(parser, field);
    }

    private static Boolean booleanField(JsonParser parser, String field) throws IOException {
        expectField(parser, field, activeRootFields());
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return false;
        }
        fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        return null;
    }

    private static <E extends Enum<E>> E enumField(JsonParser parser,
                                                    String field,
                                                    Class<E> type) throws IOException {
        expectField(parser, field, activeRootFields());
        return readNullableEnum(parser, type, field);
    }

    private static AlleleKey alleleKeyField(JsonParser parser,
                                            String field,
                                            boolean nullable) throws IOException {
        expectField(parser, field, activeRootFields());
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_NULL && nullable) {
            return null;
        }
        expectToken(token, JsonToken.START_OBJECT, field);
        return parseAlleleKey(parser);
    }

    private static List<AlleleKey> alleleKeyArrayField(JsonParser parser,
                                                       String field) throws IOException {
        expectField(parser, field, activeRootFields());
        expectToken(parser.nextToken(), JsonToken.START_ARRAY, field);
        List<AlleleKey> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
            }
            expectToken(token, JsonToken.START_OBJECT, field);
            if (values.size() >= AnalysisAlleleLineageWire.MAX_RELATION_FAN_OUT) {
                fail(ReasonCode.LINEAGE_FAN_OUT_LIMIT_EXCEEDED, field);
            }
            values.add(parseAlleleKey(parser));
        }
        return values;
    }

    private static AlleleKey parseAlleleKey(JsonParser parser) throws IOException {
        expectField(parser, "assembly", ALLELE_KEY_FIELDS);
        GenomeAssembly assembly = readNullableEnum(parser, GenomeAssembly.class, "assembly");
        expectField(parser, "chromosome", ALLELE_KEY_FIELDS);
        String chromosome = readNullableString(
                parser, "chromosome", AnalysisAlleleLineageWire.MAX_ORDINARY_STRING_BYTES);
        expectField(parser, "position", ALLELE_KEY_FIELDS);
        int position = readInt(parser, "position");
        expectField(parser, "reference", ALLELE_KEY_FIELDS);
        String reference = readNullableString(
                parser, "reference", AnalysisAlleleLineageWire.MAX_ALLELE_BYTES);
        expectField(parser, "alternate", ALLELE_KEY_FIELDS);
        String alternate = readNullableString(
                parser, "alternate", AnalysisAlleleLineageWire.MAX_ALLELE_BYTES);
        expectField(parser, "canonicalVariantId", ALLELE_KEY_FIELDS);
        String canonicalVariantId = readNullableString(
                parser, "canonicalVariantId", AnalysisAlleleLineageWire.MAX_LINE_BYTES);
        expectEndObject(parser, ALLELE_KEY_FIELDS);
        return new AlleleKey(
                assembly, chromosome, position, reference, alternate, canonicalVariantId);
    }

    private static List<EngineAlleleRef> engineRefArrayField(JsonParser parser,
                                                             String field) throws IOException {
        expectField(parser, field, activeRootFields());
        expectToken(parser.nextToken(), JsonToken.START_ARRAY, field);
        List<EngineAlleleRef> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
            }
            expectToken(token, JsonToken.START_OBJECT, field);
            if (values.size() >= AnalysisAlleleLineageWire.MAX_RELATION_FAN_OUT) {
                fail(ReasonCode.LINEAGE_FAN_OUT_LIMIT_EXCEEDED, field);
            }
            expectField(parser, "engineAlleleId", ENGINE_REF_FIELDS);
            String engineAlleleId = readNullableString(
                    parser, "engineAlleleId", AnalysisAlleleLineageWire.MAX_ORDINARY_STRING_BYTES);
            expectField(parser, "analysisCanonicalVariantId", ENGINE_REF_FIELDS);
            String canonicalId = readNullableString(
                    parser, "analysisCanonicalVariantId", AnalysisAlleleLineageWire.MAX_LINE_BYTES);
            expectEndObject(parser, ENGINE_REF_FIELDS);
            values.add(new EngineAlleleRef(engineAlleleId, canonicalId));
        }
        return values;
    }

    private static <E extends Enum<E>> List<E> enumArrayField(JsonParser parser,
                                                              String field,
                                                              Class<E> type,
                                                              int maxSize) throws IOException {
        expectField(parser, field, activeRootFields());
        expectToken(parser.nextToken(), JsonToken.START_ARRAY, field);
        List<E> values = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null || token != JsonToken.VALUE_STRING) {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
            }
            if (values.size() >= maxSize) {
                fail(ReasonCode.class.equals(type)
                        ? ReasonCode.LINEAGE_FIELD_INVALID
                        : ReasonCode.LINEAGE_FAN_OUT_LIMIT_EXCEEDED, field);
            }
            values.add(enumValue(parser.getText(), type, field));
        }
        return values;
    }

    private static String readNullableString(JsonParser parser,
                                             String field,
                                             int maxUtf8Bytes) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token != JsonToken.VALUE_STRING) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
        String value = parser.getText();
        if (!validUnicode(value) || value.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
        return value;
    }

    private static long readLong(JsonParser parser, String field) throws IOException {
        if (parser.nextToken() != JsonToken.VALUE_NUMBER_INT) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
        return parser.getLongValue();
    }

    private static int readInt(JsonParser parser, String field) throws IOException {
        if (parser.nextToken() != JsonToken.VALUE_NUMBER_INT) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
        return parser.getIntValue();
    }

    private static <E extends Enum<E>> E readNullableEnum(JsonParser parser,
                                                          Class<E> type,
                                                          String field) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        if (token != JsonToken.VALUE_STRING) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
        return enumValue(parser.getText(), type, field);
    }

    private static <E extends Enum<E>> E readRequiredEnum(JsonParser parser,
                                                          Class<E> type,
                                                          String field) throws IOException {
        E value = readNullableEnum(parser, type, field);
        if (value == null) {
            fail(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, field);
        }
        return value;
    }

    private static <E extends Enum<E>> E enumValue(String value,
                                                   Class<E> type,
                                                   String field) {
        if (!validUnicode(value)) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
            return null;
        }
    }

    private static Set<String> fieldsFor(RecordType recordType) {
        return switch (recordType) {
            case HEADER -> HEADER_FIELDS;
            case INPUT_RECORD -> INPUT_FIELDS;
            case ALLELE_LINEAGE -> ALLELE_FIELDS;
            case CANDIDATE_LINK -> CANDIDATE_FIELDS;
            case FOOTER -> FOOTER_FIELDS;
        };
    }

    private static Set<String> activeRootFields() {
        Set<String> fields = ACTIVE_ROOT_FIELDS.get();
        if (fields == null) {
            throw new IllegalStateException("LINEAGE_PARSER_CONTEXT_MISSING");
        }
        return fields;
    }

    private static void expectField(JsonParser parser,
                                    String expected,
                                    Set<String> whitelist) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_OBJECT || token == null) {
            fail(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, expected);
        }
        if (token != JsonToken.FIELD_NAME) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, "json");
        }
        String actual = parser.currentName();
        if (!expected.equals(actual)) {
            if (!whitelist.contains(actual)) {
                fail(ReasonCode.LINEAGE_UNKNOWN_FIELD, "field");
            }
            fail(ReasonCode.LINEAGE_ORDER_INVALID, expected);
        }
    }

    private static void expectEndObject(JsonParser parser,
                                        Set<String> whitelist) throws IOException {
        JsonToken token = parser.nextToken();
        if (token == JsonToken.END_OBJECT) {
            return;
        }
        if (token == JsonToken.FIELD_NAME && !whitelist.contains(parser.currentName())) {
            fail(ReasonCode.LINEAGE_UNKNOWN_FIELD, "field");
        }
        fail(ReasonCode.LINEAGE_ORDER_INVALID, "recordType");
    }

    private static void expectToken(JsonToken actual, JsonToken expected, String field) {
        if (actual != expected) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
    }

    private static boolean validUnicode(String value) {
        if (value == null) {
            return true;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBom(byte[] line) {
        return startsWith(line, 0xEF, 0xBB, 0xBF)
                || startsWith(line, 0xFE, 0xFF)
                || startsWith(line, 0xFF, 0xFE)
                || startsWith(line, 0x00, 0x00, 0xFE, 0xFF)
                || startsWith(line, 0xFF, 0xFE, 0x00, 0x00);
    }

    private static boolean startsWith(byte[] value, int... prefix) {
        if (value.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((value[index] & 0xff) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static void fail(ReasonCode reasonCode, String field) {
        throw new LineageValidationException(reasonCode, field);
    }
}
