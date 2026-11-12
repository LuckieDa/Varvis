package org.nbscn.varvis.variantanalysis.lineage;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineAlleleRef;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Explicit canonical JSON projection for the Phase A2 in-memory wire contract.
 *
 * <p>This class does not parse or access files. Phase B owns strict NDJSON input, output, atomic
 * publication, and streaming recovery. Field order here is normative and deliberately independent
 * of the application's configured Jackson {@code ObjectMapper}.</p>
 */
public final class AnalysisAlleleLineageCanonicalProjector {

    private static final byte LF = (byte) '\n';
    private final JsonFactory jsonFactory = new JsonFactory();

    /** Returns one canonical JSON object without a trailing LF. */
    public byte[] canonicalRecordBytes(WireRecord record) {
        return canonicalRecordBytes(record, false);
    }

    /**
     * Returns the exact canonical bytes that contribute to the v1 content fingerprint.
     * The footer projection omits {@code contentFingerprint}; all other records use their normal
     * canonical representation. This bounded primitive lets the streaming validator reuse the
     * same projector without materializing a complete {@link Document}.
     */
    byte[] canonicalFingerprintRecordBytes(WireRecord record) {
        return canonicalRecordBytes(record, record instanceof FooterRecord);
    }

    /**
     * Computes the v1 content fingerprint. Every canonical record, including the footer
     * projection with {@code contentFingerprint} completely omitted, contributes one trailing LF.
     */
    public String contentFingerprint(Document document) {
        if (document == null || document.header() == null || document.footer() == null) {
            throw new LineageValidationException(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "document");
        }
        MessageDigest digest = sha256();
        updateLine(digest, canonicalRecordBytes(document.header(), false));
        for (InputRecord inputRecord : safe(document.inputRecords(), "inputRecords")) {
            updateLine(digest, canonicalRecordBytes(inputRecord, false));
        }
        for (AlleleRecord alleleRecord : safe(document.alleleRecords(), "alleleRecords")) {
            updateLine(digest, canonicalRecordBytes(alleleRecord, false));
        }
        for (CandidateLinkRecord candidate : safe(document.candidateLinks(), "candidateLinks")) {
            updateLine(digest, canonicalRecordBytes(candidate, false));
        }
        updateLine(digest, canonicalRecordBytes(document.footer(), true));
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Returns a copy whose footer contains the fingerprint of the supplied draft document. */
    public Document seal(Document draft) {
        String fingerprint = contentFingerprint(draft);
        return draft.withFooter(draft.footer().withContentFingerprint(fingerprint));
    }

    private byte[] canonicalRecordBytes(WireRecord record, boolean omitFooterFingerprint) {
        if (record == null) {
            throw new LineageValidationException(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "record");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(512);
             JsonGenerator generator = jsonFactory.createGenerator(output)) {
            generator.writeStartObject();
            writeCommon(generator, record);
            if (record instanceof HeaderRecord header) {
                writeHeader(generator, header);
            } else if (record instanceof InputRecord inputRecord) {
                writeInputRecord(generator, inputRecord);
            } else if (record instanceof AlleleRecord alleleRecord) {
                writeAlleleRecord(generator, alleleRecord);
            } else if (record instanceof CandidateLinkRecord candidate) {
                writeCandidateLink(generator, candidate);
            } else if (record instanceof FooterRecord footer) {
                writeFooter(generator, footer, omitFooterFingerprint);
            } else {
                throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, "recordType");
            }
            generator.writeEndObject();
            generator.flush();
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("LINEAGE_CANONICAL_PROJECTION_FAILED", exception);
        }
    }

    private static void writeCommon(JsonGenerator generator, WireRecord record) throws IOException {
        writeNullableString(generator, "schemaVersion", record.schemaVersion());
        writeNullableEnum(generator, "recordType", record.recordType());
        generator.writeNumberField("recordOrdinal", record.recordOrdinal());
    }

    private static void writeHeader(JsonGenerator generator, HeaderRecord header) throws IOException {
        writeNullableString(generator, "generatorVersion", header.generatorVersion());
        writeNullableString(generator, "pipelineProfileId", header.pipelineProfileId());
        writeNullableString(generator, "sourceAlleleTokenAlgorithm", header.sourceAlleleTokenAlgorithm());
        writeNullableEnum(generator, "inputAssembly", header.inputAssembly());
        writeNullableEnum(generator, "analysisAssembly", header.analysisAssembly());
        writeNullableString(generator, "inputArtifactSha256", header.inputArtifactSha256());
        writeNullableEnum(generator, "liftoverPolicy", header.liftoverPolicy());
        writeNullableString(generator, "liftoverTool", header.liftoverTool());
        writeNullableString(generator, "liftoverToolVersion", header.liftoverToolVersion());
        writeNullableString(generator, "liftoverChainSha256", header.liftoverChainSha256());
        writeNullableString(generator, "liftoverReferenceSha256", header.liftoverReferenceSha256());
        writeNullableString(generator, "normalizationProfileId", header.normalizationProfileId());
        writeNullableString(generator, "normalizationReferenceSha256", header.normalizationReferenceSha256());
        writeNullableString(generator, "normalizationTool", header.normalizationTool());
        writeNullableString(generator, "normalizationToolVersion", header.normalizationToolVersion());
        writeNullableString(generator, "normalizationCommandDigest", header.normalizationCommandDigest());
        writeNullableString(generator, "engineName", header.engineName());
        writeNullableString(generator, "engineVersion", header.engineVersion());
        writeNullableString(generator, "engineResourceFingerprint", header.engineResourceFingerprint());
        writeNullableString(generator, "engineParserSchemaVersion", header.engineParserSchemaVersion());
        writeNullableString(generator, "candidateIdentitySchemaVersion", header.candidateIdentitySchemaVersion());
        writeNullableString(generator, "limitProfileId", header.limitProfileId());
        if (header.limits() == null) {
            generator.writeNullField("maxSidecarBytes");
            generator.writeNullField("maxLineBytes");
            generator.writeNullField("maxRecordCount");
            generator.writeNullField("maxInputAlleleCount");
            generator.writeNullField("maxCandidateLinkCount");
            generator.writeNullField("maxAlleleBytes");
            generator.writeNullField("maxRelationFanIn");
            generator.writeNullField("maxRelationFanOut");
        } else {
            generator.writeNumberField("maxSidecarBytes", header.limits().maxSidecarBytes());
            generator.writeNumberField("maxLineBytes", header.limits().maxLineBytes());
            generator.writeNumberField("maxRecordCount", header.limits().maxRecordCount());
            generator.writeNumberField("maxInputAlleleCount", header.limits().maxInputAlleleCount());
            generator.writeNumberField("maxCandidateLinkCount", header.limits().maxCandidateLinkCount());
            generator.writeNumberField("maxAlleleBytes", header.limits().maxAlleleBytes());
            generator.writeNumberField("maxRelationFanIn", header.limits().maxRelationFanIn());
            generator.writeNumberField("maxRelationFanOut", header.limits().maxRelationFanOut());
        }
    }

    private static void writeInputRecord(JsonGenerator generator, InputRecord record) throws IOException {
        generator.writeNumberField("sourceRecordOrdinal", record.sourceRecordOrdinal());
        generator.writeNumberField("sourceAlternateCount", record.sourceAlternateCount());
        writeNullableEnum(generator, "inputRecordStatus", record.inputRecordStatus());
        writeReasonCodes(generator, "reasonCodes", record.reasonCodes());
    }

    private static void writeAlleleRecord(JsonGenerator generator, AlleleRecord record) throws IOException {
        writeNullableString(generator, "sourceAlleleToken", record.sourceAlleleToken());
        generator.writeNumberField("sourceRecordOrdinal", record.sourceRecordOrdinal());
        generator.writeNumberField("sourceAlternateIndex", record.sourceAlternateIndex());
        generator.writeNumberField("sourceAlternateCount", record.sourceAlternateCount());
        writeNullableEnum(generator, "inputStatus", record.inputStatus());
        writeReasonCodes(generator, "inputReasonCodes", record.inputReasonCodes());
        writeAlleleKeyField(generator, "sourceKey", record.sourceKey());
        writeNullableEnum(generator, "liftoverStatus", record.liftoverStatus());
        writeReasonCodes(generator, "liftoverReasonCodes", record.liftoverReasonCodes());
        writeAlleleKeys(generator, "liftedKeys", record.liftedKeys());
        writeNullableEnum(generator, "liftoverCardinality", record.liftoverCardinality());
        writeEnums(generator, "liftoverTransforms", record.liftoverTransforms());
        if (record.targetReferenceValidated() == null) {
            generator.writeNullField("targetReferenceValidated");
        } else {
            generator.writeBooleanField("targetReferenceValidated", record.targetReferenceValidated());
        }
        writeNullableEnum(generator, "normalizationStatus", record.normalizationStatus());
        writeReasonCodes(generator, "normalizationReasonCodes", record.normalizationReasonCodes());
        writeAlleleKeys(generator, "canonicalKeys", record.canonicalKeys());
        writeNullableEnum(generator, "normalizationCardinality", record.normalizationCardinality());
        writeEnums(generator, "normalizationTransforms", record.normalizationTransforms());
        writeNullableEnum(generator, "engineMatchStatus", record.engineMatchStatus());
        writeReasonCodes(generator, "engineReasonCodes", record.engineReasonCodes());
        generator.writeArrayFieldStart("engineAlleles");
        for (EngineAlleleRef engineAllele : requiredList(
                record.engineAlleles(), "engineAlleles")) {
            if (engineAllele == null) {
                throw new LineageValidationException(
                        ReasonCode.LINEAGE_FIELD_INVALID, "engineAlleles");
            }
            generator.writeStartObject();
            writeNullableString(generator, "engineAlleleId", engineAllele.engineAlleleId());
            writeNullableString(generator, "analysisCanonicalVariantId",
                    engineAllele.analysisCanonicalVariantId());
            generator.writeEndObject();
        }
        generator.writeEndArray();
    }

    private static void writeCandidateLink(JsonGenerator generator,
                                           CandidateLinkRecord record) throws IOException {
        writeNullableString(generator, "candidateId", record.candidateId());
        writeNullableEnum(generator, "candidateSource", record.candidateSource());
        generator.writeNumberField("candidateSourceOrdinal", record.candidateSourceOrdinal());
        writeNullableString(generator, "engineAlleleId", record.engineAlleleId());
        writeNullableString(generator, "analysisCanonicalVariantId",
                record.analysisCanonicalVariantId());
        writeNullableEnum(generator, "linkStatus", record.linkStatus());
        writeReasonCodes(generator, "reasonCodes", record.reasonCodes());
    }

    private static void writeFooter(JsonGenerator generator,
                                    FooterRecord footer,
                                    boolean omitContentFingerprint) throws IOException {
        writeNullableEnum(generator, "status", footer.status());
        writeReasonCodes(generator, "reasonCodes", footer.reasonCodes());
        generator.writeNumberField("inputRecordCount", footer.inputRecordCount());
        generator.writeNumberField("inputAlleleCount", footer.inputAlleleCount());
        generator.writeNumberField("liftedAlleleCount", footer.liftedAlleleCount());
        generator.writeNumberField("canonicalAlleleCount", footer.canonicalAlleleCount());
        generator.writeNumberField("engineAlleleCount", footer.engineAlleleCount());
        generator.writeNumberField("candidateLinkCount", footer.candidateLinkCount());
        generator.writeNumberField("unresolvedAlleleCount", footer.unresolvedAlleleCount());
        generator.writeNumberField("rejectedAlleleCount", footer.rejectedAlleleCount());
        writeNullableString(generator, "liftedArtifactSha256", footer.liftedArtifactSha256());
        writeNullableString(generator, "canonicalArtifactSha256", footer.canonicalArtifactSha256());
        writeNullableString(generator, "engineArtifactSha256", footer.engineArtifactSha256());
        writeNullableString(generator, "fingerprintAlgorithm", footer.fingerprintAlgorithm());
        if (!omitContentFingerprint) {
            writeNullableString(generator, "contentFingerprint", footer.contentFingerprint());
        }
    }

    private static void writeAlleleKeyField(JsonGenerator generator,
                                            String name,
                                            AlleleKey key) throws IOException {
        generator.writeFieldName(name);
        if (key == null) {
            generator.writeNull();
        } else {
            writeAlleleKey(generator, key);
        }
    }

    private static void writeAlleleKeys(JsonGenerator generator,
                                        String name,
                                        List<AlleleKey> keys) throws IOException {
        generator.writeArrayFieldStart(name);
        for (AlleleKey key : requiredList(keys, name)) {
            if (key == null) {
                throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, name);
            }
            writeAlleleKey(generator, key);
        }
        generator.writeEndArray();
    }

    private static void writeAlleleKey(JsonGenerator generator, AlleleKey key) throws IOException {
        generator.writeStartObject();
        writeNullableEnum(generator, "assembly", key.assembly());
        writeNullableString(generator, "chromosome", key.chromosome());
        generator.writeNumberField("position", key.position());
        writeNullableString(generator, "reference", key.reference());
        writeNullableString(generator, "alternate", key.alternate());
        writeNullableString(generator, "canonicalVariantId", key.canonicalVariantId());
        generator.writeEndObject();
    }

    private static void writeReasonCodes(JsonGenerator generator,
                                         String name,
                                         List<ReasonCode> values) throws IOException {
        generator.writeArrayFieldStart(name);
        for (ReasonCode value : requiredList(values, name)) {
            if (value == null) {
                throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, name);
            }
            generator.writeString(value.name());
        }
        generator.writeEndArray();
    }

    private static void writeEnums(JsonGenerator generator,
                                   String name,
                                   List<? extends Enum<?>> values) throws IOException {
        generator.writeArrayFieldStart(name);
        for (Enum<?> value : requiredList(values, name)) {
            if (value == null) {
                throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, name);
            }
            generator.writeString(value.name());
        }
        generator.writeEndArray();
    }

    private static void writeNullableString(JsonGenerator generator,
                                            String name,
                                            String value) throws IOException {
        if (value == null) {
            generator.writeNullField(name);
        } else {
            generator.writeStringField(name, value);
        }
    }

    private static void writeNullableEnum(JsonGenerator generator,
                                          String name,
                                          Enum<?> value) throws IOException {
        if (value == null) {
            generator.writeNullField(name);
        } else {
            generator.writeStringField(name, value.name());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", exception);
        }
    }

    private static void updateLine(MessageDigest digest, byte[] line) {
        digest.update(line);
        digest.update(LF);
    }

    private static <T> List<T> safe(List<T> values, String field) {
        return requiredList(values, field);
    }

    private static <T> List<T> requiredList(List<T> values, String field) {
        if (values == null) {
            throw new LineageValidationException(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, field);
        }
        return values;
    }
}
