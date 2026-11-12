package org.nbscn.varvis.variantanalysis.lineage;

import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleKey;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.EngineAlleleRef;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Collision-free binary facts used only by the bounded streaming semantic validator.
 *
 * <p>Identity components are length-delimited; candidate-order strings intentionally use
 * NUL-terminated ASCII so unsigned byte ordering is identical to the canonical comparator. Keys
 * are compared by {@link BoundedExternalSortSpool}; a format-version byte followed by a family
 * byte creates disjoint namespaces and equal logical identities produce equal keys. Facts contain
 * variant coordinates and alleles and therefore remain sensitive derived data even though sample
 * identifiers, paths and free text are excluded.</p>
 */
final class AnalysisAlleleLineageFactCodec {

    static final byte COVERAGE = 0x10;
    static final byte TOKEN = 0x20;
    static final byte SOURCE_OUTCOME = 0x30;
    static final byte DUPLICATE_SOURCE = 0x40;
    static final byte LIFTED_RELATION = 0x50;
    static final byte CANONICAL_RELATION = 0x60;
    static final byte ENGINE_JOIN = 0x70;
    static final byte CANDIDATE_ID = 0x71;
    static final byte CANDIDATE_SOURCE = 0x72;
    static final byte CANDIDATE_ORDER = 0x73;

    private static final byte FACT_FORMAT_VERSION = 0x01;
    private static final byte INPUT_COVERAGE = 0x01;
    private static final byte ALLELE_COVERAGE = 0x02;
    private static final byte ENGINE_DEFINITION = 0x01;
    private static final byte CANDIDATE_DEFINITION = 0x02;

    private AnalysisAlleleLineageFactCodec() {
    }

    static byte kind(byte[] key) {
        if (key == null || key.length < 2 || key[0] != FACT_FORMAT_VERSION) {
            throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
        }
        return key[1];
    }

    static byte[] coverageKey(long sourceRecordOrdinal) {
        return encode(output -> {
            output.writeByte(FACT_FORMAT_VERSION);
            output.writeByte(COVERAGE);
            output.writeLong(sourceRecordOrdinal);
        });
    }

    static long decodeCoverageKey(byte[] key) {
        return decodeKey(key, COVERAGE, input -> {
            long sourceRecordOrdinal = input.readLong();
            requireEnd(input);
            if (sourceRecordOrdinal <= 0) {
                fail();
            }
            return sourceRecordOrdinal;
        });
    }

    static byte[] inputCoverageValue(InputRecord record) {
        return encode(output -> {
            output.writeByte(INPUT_COVERAGE);
            output.writeInt(record.sourceAlternateCount());
            output.writeByte(record.inputRecordStatus().ordinal());
        });
    }

    static byte[] alleleCoverageValue(AlleleRecord record) {
        return encode(output -> {
            output.writeByte(ALLELE_COVERAGE);
            output.writeInt(record.sourceAlternateIndex());
            output.writeInt(record.sourceAlternateCount());
        });
    }

    static CoverageFact decodeCoverageValue(byte[] value) {
        return decode(value, input -> {
            byte marker = input.readByte();
            if (marker == INPUT_COVERAGE) {
                int alternateCount = input.readInt();
                int status = input.readUnsignedByte();
                requireEnd(input);
                return new CoverageFact(true, alternateCount, 0, status);
            }
            if (marker == ALLELE_COVERAGE) {
                int alternateIndex = input.readInt();
                int alternateCount = input.readInt();
                requireEnd(input);
                return new CoverageFact(false, alternateCount, alternateIndex, -1);
            }
            fail();
            return null;
        });
    }

    static byte[] tokenKey(String token) {
        return stringKey(TOKEN, token);
    }

    static byte[] sourcePairValue(AlleleRecord allele) {
        return encode(output -> {
            output.writeLong(allele.sourceRecordOrdinal());
            output.writeInt(allele.sourceAlternateIndex());
        });
    }

    static byte[] duplicateSourceKey(AlleleRecord allele) {
        return encode(output -> {
            output.writeByte(FACT_FORMAT_VERSION);
            output.writeByte(DUPLICATE_SOURCE);
            output.writeLong(allele.sourceRecordOrdinal());
            writeString(output, allele.sourceKey().canonicalVariantId());
        });
    }

    static byte[] duplicateDeclaredValue(AlleleRecord allele) {
        boolean declared = allele.inputReasonCodes().equals(
                List.of(ReasonCode.INPUT_DUPLICATE_ALT));
        return new byte[] {(byte) (declared ? 1 : 0)};
    }

    static boolean decodeBoolean(byte[] value) {
        if (value == null || value.length != 1 || (value[0] != 0 && value[0] != 1)) {
            throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
        }
        return value[0] == 1;
    }

    static byte[] sourceOutcomeKey(AlleleRecord allele) {
        return stringKey(SOURCE_OUTCOME, allele.sourceKey().canonicalVariantId());
    }

    static byte[] sourceOutcomeValue(AlleleRecord allele) {
        return encode(output -> writeSourceOutcome(output, allele));
    }

    static byte[] liftedRelationKey(AlleleKey lifted) {
        return stringKey(LIFTED_RELATION, lifted.canonicalVariantId());
    }

    static byte[] liftedRelationValue(AlleleRecord allele) {
        return envelope(
                allele.sourceKey().canonicalVariantId().getBytes(StandardCharsets.UTF_8),
                allele.sourceAlleleToken().getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] canonicalRelationKey(AlleleKey canonical) {
        return stringKey(CANONICAL_RELATION, canonical.canonicalVariantId());
    }

    static byte[] canonicalRelationValue(AlleleRecord allele) {
        return envelope(
                encode(output -> writeEngineOutcome(output, allele)),
                allele.sourceAlleleToken().getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] engineJoinKey(String engineAlleleId) {
        return stringKey(ENGINE_JOIN, engineAlleleId);
    }

    static byte[] engineDefinitionValue(AlleleRecord allele, EngineAlleleRef engine) {
        return taggedStrings(
                ENGINE_DEFINITION,
                engine.analysisCanonicalVariantId(),
                allele.sourceAlleleToken());
    }

    static byte[] candidateLinkValue(CandidateLinkRecord candidate) {
        return taggedStrings(
                CANDIDATE_DEFINITION,
                candidate.analysisCanonicalVariantId(),
                candidate.candidateId());
    }

    static LinkFact decodeLinkValue(byte[] value) {
        return decode(value, input -> {
            int marker = input.readUnsignedByte();
            byte[] canonical = readBytes(input);
            byte[] memberIdentity = readBytes(input);
            requireEnd(input);
            if (marker != ENGINE_DEFINITION && marker != CANDIDATE_DEFINITION) {
                fail();
            }
            return new LinkFact(marker == ENGINE_DEFINITION, canonical, memberIdentity);
        });
    }

    static byte[] candidateIdKey(CandidateLinkRecord candidate) {
        return stringKey(CANDIDATE_ID, candidate.candidateId());
    }

    static byte[] candidateSourceValue(CandidateLinkRecord candidate) {
        return encode(output -> {
            output.writeByte(candidate.candidateSource().ordinal());
            output.writeLong(candidate.candidateSourceOrdinal());
        });
    }

    static byte[] candidateSourceKey(CandidateLinkRecord candidate) {
        return encode(output -> {
            output.writeByte(FACT_FORMAT_VERSION);
            output.writeByte(CANDIDATE_SOURCE);
            output.writeByte(candidate.candidateSource().ordinal());
            output.writeLong(candidate.candidateSourceOrdinal());
        });
    }

    static byte[] candidateIdValue(CandidateLinkRecord candidate) {
        return candidate.candidateId().getBytes(StandardCharsets.UTF_8);
    }

    static byte[] candidateOrderKey(CandidateLinkRecord candidate) {
        return encode(output -> {
            output.writeByte(FACT_FORMAT_VERSION);
            output.writeByte(CANDIDATE_ORDER);
            AlleleKey key = tryParseCanonicalId(candidate.analysisCanonicalVariantId());
            if (key == null) {
                // A2 resolves the candidate's engine/canonical reference before ordering. Keep an
                // unknown identity sortable so ENGINE_JOIN can emit that stable diagnostic first.
                output.writeByte(1);
            } else {
                output.writeByte(0);
                output.writeByte(key.assembly().ordinal());
                int contigRank = AnalysisAlleleLineageWireValidator.streamingContigRank(
                        key.chromosome());
                output.writeByte(contigRank);
                if (contigRank >= 100) {
                    writeTerminatedAscii(output, key.chromosome());
                }
                output.writeInt(key.position());
                writeTerminatedAscii(output, key.reference());
                writeTerminatedAscii(output, key.alternate());
            }
            output.writeByte(candidate.candidateSource().ordinal());
            output.writeLong(candidate.candidateSourceOrdinal());
            writeTerminatedAscii(output, candidate.candidateId());
        });
    }

    static byte[] candidateOrderValue(long sequence) {
        return encode(output -> output.writeLong(sequence));
    }

    static long decodeCandidateOrderValue(byte[] value) {
        return decode(value, input -> {
            long sequence = input.readLong();
            requireEnd(input);
            return sequence;
        });
    }

    static Envelope decodeEnvelope(byte[] value) {
        return decode(value, input -> {
            byte[] first = readBytes(input);
            byte[] second = readBytes(input);
            requireEnd(input);
            return new Envelope(first, second);
        });
    }

    private static byte[] taggedStrings(byte tag, String first, String second) {
        return encode(output -> {
            output.writeByte(tag);
            writeString(output, first);
            writeString(output, second);
        });
    }

    private static byte[] stringKey(byte kind, String value) {
        return encode(output -> {
            output.writeByte(FACT_FORMAT_VERSION);
            output.writeByte(kind);
            writeString(output, value);
        });
    }

    private static byte[] envelope(byte[] first, byte[] second) {
        return encode(output -> {
            writeBytes(output, first);
            writeBytes(output, second);
        });
    }

    private static void writeSourceOutcome(DataOutputStream output,
                                           AlleleRecord allele) throws IOException {
        output.writeByte(allele.liftoverStatus().ordinal());
        writeEnums(output, allele.liftoverReasonCodes());
        writeAlleleKeys(output, allele.liftedKeys());
        output.writeByte(allele.liftoverCardinality().ordinal());
        writeEnums(output, allele.liftoverTransforms());
        writeNullableBoolean(output, allele.targetReferenceValidated());
        output.writeByte(allele.normalizationStatus().ordinal());
        writeEnums(output, allele.normalizationReasonCodes());
        writeAlleleKeys(output, allele.canonicalKeys());
        output.writeByte(allele.normalizationCardinality().ordinal());
        writeEnums(output, allele.normalizationTransforms());
        writeEngineOutcome(output, allele);
    }

    private static void writeEngineOutcome(DataOutputStream output,
                                            AlleleRecord allele) throws IOException {
        output.writeByte(allele.engineMatchStatus().ordinal());
        writeEnums(output, allele.engineReasonCodes());
        output.writeInt(allele.engineAlleles().size());
        for (EngineAlleleRef engine : allele.engineAlleles()) {
            writeString(output, engine.engineAlleleId());
            writeString(output, engine.analysisCanonicalVariantId());
        }
    }

    private static void writeAlleleKeys(DataOutputStream output,
                                        List<AlleleKey> keys) throws IOException {
        output.writeInt(keys.size());
        for (AlleleKey key : keys) {
            writeString(output, key.canonicalVariantId());
        }
    }

    private static void writeEnums(DataOutputStream output,
                                   List<? extends Enum<?>> values) throws IOException {
        output.writeInt(values.size());
        for (Enum<?> value : values) {
            output.writeInt(value.ordinal());
        }
    }

    private static void writeNullableBoolean(DataOutputStream output,
                                             Boolean value) throws IOException {
        output.writeByte(value == null ? -1 : value ? 1 : 0);
    }

    private static AlleleKey tryParseCanonicalId(String canonicalId) {
        if (canonicalId == null) {
            return null;
        }
        String[] parts = canonicalId.split("\\|", -1);
        if (parts.length != 5) {
            return null;
        }
        try {
            GenomeAssembly assembly = GenomeAssembly.valueOf(parts[0]);
            int position = Integer.parseInt(parts[2]);
            AlleleKey key = new AlleleKey(
                    assembly, parts[1], position, parts[3], parts[4], canonicalId);
            String reconstructed = assembly.name() + "|" + parts[1] + "|" + position
                    + "|" + parts[3] + "|" + parts[4];
            if (position <= 0 || !canonicalId.equals(reconstructed)
                    || !terminatedAscii(parts[1])
                    || !terminatedAscii(parts[3])
                    || !terminatedAscii(parts[4])) {
                return null;
            }
            return key;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean terminatedAscii(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (!value.equals(new String(bytes, StandardCharsets.US_ASCII))
                || bytes.length > AnalysisAlleleLineageWire.MAX_LINE_BYTES) {
            return false;
        }
        for (byte valueByte : bytes) {
            if (valueByte == 0) {
                return false;
            }
        }
        return true;
    }

    private static void writeTerminatedAscii(DataOutputStream output,
                                             String value) throws IOException {
        if (value == null) {
            throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
        }
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        if (!value.equals(new String(bytes, StandardCharsets.US_ASCII))
                || bytes.length == 0 || bytes.length > AnalysisAlleleLineageWire.MAX_LINE_BYTES) {
            fail();
        }
        for (byte valueByte : bytes) {
            if (valueByte == 0) {
                fail();
            }
        }
        output.write(bytes);
        output.writeByte(0);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (!value.equals(new String(bytes, StandardCharsets.UTF_8))) {
            fail();
        }
        writeBytes(output, bytes);
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        if (value == null || value.length > AnalysisAlleleLineageWire.MAX_LINE_BYTES) {
            throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
        }
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] readBytes(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > AnalysisAlleleLineageWire.MAX_LINE_BYTES
                || length > input.available()) {
            fail();
        }
        return input.readNBytes(length);
    }

    private static void requireEnd(DataInputStream input) throws IOException {
        if (input.read() != -1) {
            fail();
        }
    }

    private static byte[] encode(Encoder encoder) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            encoder.write(output);
            output.flush();
            byte[] result = bytes.toByteArray();
            if (result.length == 0 || result.length > AnalysisAlleleLineageWire.MAX_LINE_BYTES) {
                fail();
            }
            return result;
        } catch (LineageValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            fail();
            return null;
        }
    }

    private static <T> T decode(byte[] value, Decoder<T> decoder) {
        if (value == null || value.length == 0
                || value.length > AnalysisAlleleLineageWire.MAX_LINE_BYTES) {
            throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(value))) {
            return decoder.read(input);
        } catch (LineageValidationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            fail();
            return null;
        }
    }

    private static <T> T decodeKey(byte[] key, byte expectedKind, Decoder<T> decoder) {
        if (key == null || key.length < 2 || key[0] != FACT_FORMAT_VERSION
                || key[1] != expectedKind) {
            throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
        }
        return decode(Arrays.copyOfRange(key, 2, key.length), decoder);
    }

    private static void fail() {
        throw new LineageValidationException(ReasonCode.LINEAGE_FIELD_INVALID, "semanticFact");
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }

    @FunctionalInterface
    private interface Decoder<T> {
        T read(DataInputStream input) throws IOException;
    }

    record CoverageFact(boolean inputRecord,
                        int sourceAlternateCount,
                        int sourceAlternateIndex,
                        int inputStatusOrdinal) {

        @Override
        public String toString() {
            return "CoverageFact[redacted]";
        }
    }

    record Envelope(byte[] first, byte[] second) {
        Envelope {
            first = first.clone();
            second = second.clone();
        }

        @Override
        public byte[] first() {
            return first.clone();
        }

        @Override
        public byte[] second() {
            return second.clone();
        }

        boolean sameFirst(Envelope other) {
            return other != null && Arrays.equals(first, other.first);
        }

        @Override
        public String toString() {
            return "Envelope[redacted]";
        }
    }

    record LinkFact(boolean engineDefinition, byte[] canonicalId, byte[] memberIdentity) {
        LinkFact {
            canonicalId = canonicalId.clone();
            memberIdentity = memberIdentity.clone();
        }

        @Override
        public byte[] canonicalId() {
            return canonicalId.clone();
        }

        @Override
        public byte[] memberIdentity() {
            return memberIdentity.clone();
        }

        boolean sameCanonical(LinkFact other) {
            return other != null && Arrays.equals(canonicalId, other.canonicalId);
        }

        @Override
        public String toString() {
            return "LinkFact[redacted]";
        }
    }
}
