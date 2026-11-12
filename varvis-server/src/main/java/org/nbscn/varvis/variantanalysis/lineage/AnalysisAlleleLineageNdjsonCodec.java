package org.nbscn.varvis.variantanalysis.lineage;

import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.AlleleRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.CandidateLinkRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Document;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.FooterRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.HeaderRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.InputRecord;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.Limits;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.ReasonCode;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.RecordType;
import org.nbscn.varvis.variantanalysis.lineage.AnalysisAlleleLineageWire.WireRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Bounded canonical NDJSON codec for small, synthetic Phase B1 lineage documents.
 *
 * <p>Reading is byte-framed and never allocates more than one hard-ceiling line before parsing.
 * The accepted document is deliberately capped at
 * {@link AnalysisAlleleLineageWireValidator#MAX_IN_MEMORY_BODY_RECORDS}; after parsing, the
 * existing collection validator performs the full semantic, count, link and fingerprint checks.
 * This codec is not connected to product runtime, persistence, hydration or reuse.</p>
 *
 * <p>The caller owns both streams. This codec neither closes nor flushes them. A write-side I/O
 * failure may leave caller-owned output partial, so only a private temporary artifact may be used.
 * Raw {@link IOException}s are restricted to this package-level foundation; the Phase B2 file
 * boundary must translate them to a stable, non-disclosing storage error.</p>
 */
final class AnalysisAlleleLineageNdjsonCodec {

    private static final byte LF = (byte) '\n';
    /**
     * B1 materializes a validated synthetic {@link Document}; this lower ceiling bounds that
     * object graph independently of the 512 MiB product wire ceiling reserved for Phase B2.
     */
    static final long MAX_IN_MEMORY_SIDECAR_BYTES = 16_777_216L;

    private final AnalysisAlleleLineageCanonicalProjector projector;
    private final AnalysisAlleleLineageRecordParser parser;
    private final AnalysisAlleleLineageWireValidator validator;

    AnalysisAlleleLineageNdjsonCodec() {
        this.projector = new AnalysisAlleleLineageCanonicalProjector();
        this.parser = new AnalysisAlleleLineageRecordParser(projector);
        this.validator = new AnalysisAlleleLineageWireValidator(projector);
    }

    /** Writes one already sealed and fully valid document as canonical UTF-8 NDJSON. */
    void write(Document document, OutputStream output) throws IOException {
        if (output == null) {
            fail(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "output");
        }
        validator.validate(document);
        validateInMemoryByteCeiling(document);
        writeRecord(output, document.header());
        for (InputRecord record : document.inputRecords()) {
            writeRecord(output, record);
        }
        for (AlleleRecord record : document.alleleRecords()) {
            writeRecord(output, record);
        }
        for (CandidateLinkRecord record : document.candidateLinks()) {
            writeRecord(output, record);
        }
        writeRecord(output, document.footer());
    }

    /**
     * Reads, canonical-byte checks and fully validates a document with at most 10,000 body rows.
     */
    Document read(InputStream input) throws IOException {
        if (input == null) {
            fail(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "input");
        }
        BoundedNdjsonInput source = new BoundedNdjsonInput(input);
        byte[] firstLine = source.readLine(
                AnalysisAlleleLineageWire.MAX_LINE_BYTES,
                MAX_IN_MEMORY_SIDECAR_BYTES);
        if (firstLine == null) {
            throw new LineageValidationException(ReasonCode.LINEAGE_FOOTER_MISSING);
        }
        WireRecord first = parser.parse(firstLine);
        if (!(first instanceof HeaderRecord)) {
            fail(ReasonCode.LINEAGE_ORDER_INVALID, "recordType");
        }
        HeaderRecord header = (HeaderRecord) first;
        validateSafetyLimits(header.limits());
        if (firstLine.length > header.limits().maxLineBytes()) {
            fail(ReasonCode.LINEAGE_LINE_LIMIT_EXCEEDED, "maxLineBytes");
        }
        if (source.totalBytes() > header.limits().maxSidecarBytes()) {
            fail(ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED, "maxSidecarBytes");
        }
        AnalysisAlleleLineageWireValidator.validateHeaderRecord(header);

        int maxLineBytes = Math.min(
                header.limits().maxLineBytes(), AnalysisAlleleLineageWire.MAX_LINE_BYTES);
        long maxSidecarBytes = Math.min(
                Math.min(header.limits().maxSidecarBytes(),
                        AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES),
                MAX_IN_MEMORY_SIDECAR_BYTES);
        long maxBodyRecords = Math.min(
                header.limits().maxRecordCount(),
                AnalysisAlleleLineageWireValidator.MAX_IN_MEMORY_BODY_RECORDS);
        long maxInputAlleles = Math.min(
                header.limits().maxInputAlleleCount(),
                AnalysisAlleleLineageWire.MAX_INPUT_ALLELE_COUNT);
        long maxCandidateLinks = Math.min(
                header.limits().maxCandidateLinkCount(),
                AnalysisAlleleLineageWire.MAX_CANDIDATE_LINK_COUNT);

        List<InputRecord> inputRecords = new ArrayList<>();
        List<AlleleRecord> alleleRecords = new ArrayList<>();
        List<CandidateLinkRecord> candidateLinks = new ArrayList<>();
        FooterRecord footer = null;
        Section section = Section.INPUT;
        long expectedOrdinal = 1L;
        long bodyRecords = 0L;

        while (footer == null) {
            byte[] line = source.readLine(maxLineBytes, maxSidecarBytes);
            if (line == null) {
                fail(ReasonCode.LINEAGE_FOOTER_MISSING, null);
            }
            WireRecord record = parser.parse(line);
            if (record.recordOrdinal() != expectedOrdinal++) {
                fail(ReasonCode.LINEAGE_ORDER_INVALID, "recordOrdinal");
            }

            if (record instanceof FooterRecord value) {
                footer = value;
                section = Section.FOOTER;
                continue;
            }
            if (record.recordType() == RecordType.HEADER) {
                fail(ReasonCode.LINEAGE_ORDER_INVALID, "recordType");
            }
            bodyRecords++;
            if (bodyRecords > maxBodyRecords) {
                fail(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "maxRecordCount");
            }

            if (record instanceof InputRecord value) {
                requireSection(section, Section.INPUT);
                inputRecords.add(value);
            } else if (record instanceof AlleleRecord value) {
                requireSection(section, Section.INPUT, Section.ALLELE);
                section = Section.ALLELE;
                if ((long) alleleRecords.size() + 1L > maxInputAlleles) {
                    fail(ReasonCode.LINEAGE_ALLELE_LIMIT_EXCEEDED, "maxInputAlleleCount");
                }
                alleleRecords.add(value);
            } else if (record instanceof CandidateLinkRecord value) {
                requireSection(section, Section.INPUT, Section.ALLELE, Section.CANDIDATE);
                section = Section.CANDIDATE;
                if ((long) candidateLinks.size() + 1L > maxCandidateLinks) {
                    fail(ReasonCode.LINEAGE_RECORD_LIMIT_EXCEEDED, "maxCandidateLinkCount");
                }
                candidateLinks.add(value);
            } else {
                fail(ReasonCode.LINEAGE_FIELD_INVALID, "recordType");
            }
        }

        byte[] trailing = source.readLine(maxLineBytes, maxSidecarBytes);
        if (trailing != null) {
            fail(ReasonCode.LINEAGE_ORDER_INVALID, "document");
        }
        Document document = new Document(
                header, inputRecords, alleleRecords, candidateLinks, footer);
        validator.validate(document);
        return document;
    }

    private void writeRecord(OutputStream output, WireRecord record) throws IOException {
        byte[] line = projector.canonicalRecordBytes(record);
        output.write(line);
        output.write(LF);
    }

    private void validateInMemoryByteCeiling(Document document) {
        long totalBytes = 0L;
        for (WireRecord record : document.recordsInCanonicalSections()) {
            long lineBytes = projector.canonicalRecordBytes(record).length + 1L;
            if (exceedsByteCeiling(
                    totalBytes, lineBytes, MAX_IN_MEMORY_SIDECAR_BYTES)) {
                fail(ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED, "maxSidecarBytes");
            }
            totalBytes += lineBytes;
        }
    }

    /** Overflow-safe inclusive ceiling check shared by the read and write byte counters. */
    static boolean exceedsByteCeiling(long consumedBytes, long nextBytes, long ceilingBytes) {
        return consumedBytes < 0L
                || nextBytes < 0L
                || ceilingBytes < 0L
                || consumedBytes > ceilingBytes
                || nextBytes > ceilingBytes - consumedBytes;
    }

    private static void validateSafetyLimits(Limits limits) {
        if (limits == null) {
            throw new LineageValidationException(ReasonCode.LINEAGE_REQUIRED_FIELD_MISSING, "limits");
        }
        positiveWithin(limits.maxSidecarBytes(),
                AnalysisAlleleLineageWire.MAX_SIDECAR_BYTES, "maxSidecarBytes");
        positiveWithin(limits.maxLineBytes(),
                AnalysisAlleleLineageWire.MAX_LINE_BYTES, "maxLineBytes");
        positiveWithin(limits.maxRecordCount(),
                AnalysisAlleleLineageWire.MAX_BODY_RECORD_COUNT, "maxRecordCount");
        positiveWithin(limits.maxInputAlleleCount(),
                AnalysisAlleleLineageWire.MAX_INPUT_ALLELE_COUNT, "maxInputAlleleCount");
        positiveWithin(limits.maxCandidateLinkCount(),
                AnalysisAlleleLineageWire.MAX_CANDIDATE_LINK_COUNT,
                "maxCandidateLinkCount");
        positiveWithin(limits.maxAlleleBytes(),
                AnalysisAlleleLineageWire.MAX_ALLELE_BYTES, "maxAlleleBytes");
        positiveWithin(limits.maxRelationFanIn(),
                AnalysisAlleleLineageWire.MAX_RELATION_FAN_IN, "maxRelationFanIn");
        positiveWithin(limits.maxRelationFanOut(),
                AnalysisAlleleLineageWire.MAX_RELATION_FAN_OUT, "maxRelationFanOut");
    }

    private static void positiveWithin(long value, long maximum, String field) {
        if (value <= 0 || value > maximum) {
            fail(ReasonCode.LINEAGE_FIELD_INVALID, field);
        }
    }

    private static void requireSection(Section actual, Section... allowed) {
        if (Arrays.stream(allowed).noneMatch(value -> value == actual)) {
            fail(ReasonCode.LINEAGE_ORDER_INVALID, "recordType");
        }
    }

    private enum Section {
        INPUT,
        ALLELE,
        CANDIDATE,
        FOOTER
    }

    /** A byte-level LF framer with hard bounded memory and byte counters. */
    static final class BoundedNdjsonInput {

        private static final int CHUNK_BYTES = 8_192;

        private final InputStream input;
        private final byte[] chunk = new byte[CHUNK_BYTES];
        private final byte[] line = new byte[AnalysisAlleleLineageWire.MAX_LINE_BYTES + 1];
        private int chunkIndex;
        private int chunkLength;
        private long totalBytes;
        private boolean eof;

        BoundedNdjsonInput(InputStream input) {
            this.input = Objects.requireNonNull(input, "input");
        }

        byte[] readLine(int maxLineBytes, long maxTotalBytes) throws IOException {
            int lineLength = 0;
            while (true) {
                int next = nextByte();
                if (next < 0) {
                    if (lineLength == 0) {
                        return null;
                    }
                    fail(ReasonCode.LINEAGE_FIELD_INVALID, "lineEnding");
                }
                if (exceedsByteCeiling(totalBytes, 1L, maxTotalBytes)) {
                    fail(ReasonCode.LINEAGE_SIZE_LIMIT_EXCEEDED, "maxSidecarBytes");
                }
                totalBytes++;
                if (next == '\n') {
                    if (lineLength == 0 || line[lineLength - 1] == '\r') {
                        fail(ReasonCode.LINEAGE_FIELD_INVALID, "lineEnding");
                    }
                    return Arrays.copyOf(line, lineLength);
                }
                if (lineLength >= maxLineBytes) {
                    fail(ReasonCode.LINEAGE_LINE_LIMIT_EXCEEDED, "maxLineBytes");
                }
                line[lineLength++] = (byte) next;
            }
        }

        private int nextByte() throws IOException {
            if (chunkIndex < chunkLength) {
                return chunk[chunkIndex++] & 0xff;
            }
            if (eof) {
                return -1;
            }
            chunkLength = input.read(chunk);
            chunkIndex = 0;
            if (chunkLength < 0) {
                eof = true;
                return -1;
            }
            if (chunkLength == 0) {
                int single = input.read();
                if (single < 0) {
                    eof = true;
                    return -1;
                }
                return single;
            }
            return chunk[chunkIndex++] & 0xff;
        }

        long totalBytes() {
            return totalBytes;
        }
    }

    private static void fail(ReasonCode reasonCode, String field) {
        throw field == null
                ? new LineageValidationException(reasonCode)
                : new LineageValidationException(reasonCode, field);
    }
}
