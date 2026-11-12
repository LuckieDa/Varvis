package org.nbscn.varvis.variantanalysis.exomiser;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ArtifactKind;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.Code;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.FileIdentitySource;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.IdentityAssurance;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.Limits;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.RawScanResult;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ScanException;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ScanResult;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExomiserTsvScannerTest {

    private static final String VARIANT_HEADER =
            "#RANK\tCONTIG\tSTART\tREF\tALT\tOPTIONAL";
    private static final String PRIVACY_CANARY =
            "patient-Alice-C:\\private\\exomiser-secret";

    @TempDir
    Path directory;

    @Test
    void streamsTwelveThousandRowsWhileHashingAndCountingRawArtifact() throws Exception {
        Path artifact = directory.resolve("large.variants.tsv");
        int rows = 12_000;
        try (BufferedWriter writer = Files.newBufferedWriter(artifact, StandardCharsets.UTF_8)) {
            writer.write(VARIANT_HEADER);
            writer.write('\n');
            for (int index = 1; index <= rows; index++) {
                writer.write(Integer.toString(index));
                writer.write("\t1\t");
                writer.write(Integer.toString(index + 99));
                writer.write("\tA\tT\tvalue-");
                writer.write(Integer.toString(index));
                writer.write('\n');
            }
        }
        AtomicInteger delivered = new AtomicInteger();
        AtomicInteger rowMapsCreated = new AtomicInteger();
        AtomicReference<Map<String, String>> first = new AtomicReference<>();
        AtomicReference<Map<String, String>> last = new AtomicReference<>();
        ExomiserTsvScanner scanner = new ExomiserTsvScanner(
                TEST_IDENTITY, rowMapsCreated::incrementAndGet);

        ScanResult inspected = scanner.inspect(artifact, ArtifactKind.VARIANTS);

        assertEquals(rows, inspected.dataRowCount());
        assertEquals(0, rowMapsCreated.get());

        ScanResult result = scanner.scan(artifact, ArtifactKind.VARIANTS, (row, ordinal) -> {
            assertEquals(delivered.incrementAndGet(), ordinal);
            if (ordinal == 1) {
                first.set(row);
            }
            if (ordinal == rows) {
                last.set(row);
            }
        });

        assertEquals(rows, delivered.get());
        assertEquals(rows, rowMapsCreated.get());
        assertEquals(rows, result.dataRowCount());
        assertEquals(rows + 1L, result.physicalLineCount());
        assertEquals(Files.size(artifact), result.byteCount());
        assertEquals(rawSha256(artifact), result.rawSha256());
        assertEquals(6, result.columnCount());
        assertEquals(List.of("#RANK", "CONTIG", "START", "REF", "ALT", "OPTIONAL"),
                result.header());
        assertEquals("1", first.get().get("#RANK"));
        assertEquals("12000", last.get().get("#RANK"));
        assertNotSame(first.get(), last.get());
        assertThrows(UnsupportedOperationException.class,
                () -> first.get().put("ALT", "patient-secret"));
        assertEquals("ScanResult[redacted]", result.toString());
    }

    @Test
    void acceptsMixedLfCrlfBlankRowsAndNoFinalLfWithShortOptionalTail() throws Exception {
        Path artifact = writeUtf8("mixed.variants.tsv",
                VARIANT_HEADER + "\r\n"
                        + "1\t1\t100\tA\tT\t基因✓\n"
                        + "\r\n"
                        + "2\t2\t200\tG\tC");
        List<Map<String, String>> rows = new ArrayList<>();

        ScanResult result = scanner().scan(
                artifact, ArtifactKind.VARIANTS, (row, ordinal) -> rows.add(row));

        assertEquals(4L, result.physicalLineCount());
        assertEquals(2L, result.dataRowCount());
        assertEquals("基因✓", rows.get(0).get("OPTIONAL"));
        assertEquals("", rows.get(1).get("OPTIONAL"));
        assertEquals("2", rows.get(1).get("#RANK"));
    }

    @Test
    void dataBlanknessUsesLegacyTrimSemanticsRatherThanUnicodeIsBlank() throws Exception {
        Path asciiBlank = writeUtf8("ascii-blank.tsv", "#RANK\n \t \n1\n");

        assertEquals(1L, scanner().inspect(asciiBlank, ArtifactKind.GENES).dataRowCount());

        Path unicodeWhitespace = writeUtf8("unicode-whitespace.tsv", "#RANK\n\u2003\n");
        assertCode(Code.REQUIRED_CELL_MISSING,
                () -> scanner().inspect(unicodeWhitespace, ArtifactKind.GENES));
    }

    @Test
    void genesRequireOnlyRankAndAcceptUnknownHeaders() throws Exception {
        Path artifact = writeUtf8("genes.tsv",
                "#RANK\tENTREZ_GENE_ID\tFUTURE_COLUMN\n"
                        + "1\t7157\tunknown-is-retained\n");
        AtomicReference<Map<String, String>> delivered = new AtomicReference<>();

        ScanResult result = scanner().scan(
                artifact, ArtifactKind.GENES, (row, ordinal) -> delivered.set(row));

        assertEquals(1L, result.dataRowCount());
        assertEquals("7157", delivered.get().get("ENTREZ_GENE_ID"));
        assertEquals("unknown-is-retained", delivered.get().get("FUTURE_COLUMN"));
    }

    @Test
    void inspectReturnsMetadataWithoutRequiringRowRetention() throws Exception {
        Path artifact = writeUtf8("inspect.genes.tsv", "#RANK\n1\n2\n");

        ScanResult result = scanner().inspect(artifact, ArtifactKind.GENES);

        assertEquals(3L, result.physicalLineCount());
        assertEquals(2L, result.dataRowCount());
        assertEquals(List.of("#RANK"), result.header());
    }

    @Test
    void requiredArtifactPreflightPreservesMissingInvalidAndEmptyDistinctions()
            throws Exception {
        Path missing = directory.resolve("missing-required.tsv");
        Path empty = directory.resolve("empty-required.tsv");
        Files.write(empty, new byte[0]);
        Path present = writeUtf8("present-required.tsv", "#RANK\n1\n");

        assertCode(Code.FILE_MISSING,
                () -> scanner().verifyRequiredNonEmpty(missing));
        assertCode(Code.HEADER_MISSING,
                () -> scanner().verifyRequiredNonEmpty(empty));
        assertCode(Code.FILE_INVALID,
                () -> scanner().verifyRequiredNonEmpty(directory));
        scanner().verifyRequiredNonEmpty(present);
    }

    @Test
    void productionIdentityPolicySupportsTheCurrentLocalFileProvider() throws Exception {
        Path artifact = writeUtf8("production-identity.genes.tsv", "#RANK\n1\n");

        ScanResult result = new ExomiserTsvScanner()
                .inspect(artifact, ArtifactKind.GENES);

        assertEquals(1L, result.dataRowCount());
        assertEquals(rawSha256(artifact), result.rawSha256());
        BasicFileAttributes attributes = Files.readAttributes(
                artifact, BasicFileAttributes.class);
        assertEquals(
                attributes.fileKey() == null
                        ? IdentityAssurance.WINDOWS_PRIVATE_PATH_DRIFT_TOKEN
                        : IdentityAssurance.PROVIDER_FILE_KEY,
                result.identityAssurance());
    }

    @Test
    void verifiesPriorScanSnapshotWithoutReopeningContent() throws Exception {
        Path artifact = writeUtf8("stable-token.genes.tsv", "#RANK\n1\n");
        AtomicInteger snapshots = new AtomicInteger();
        ExomiserTsvScanner scanner = new ExomiserTsvScanner(
                (path, attributes) -> "identity-" + Math.min(1, snapshots.incrementAndGet()));

        ScanResult result = scanner.inspect(artifact, ArtifactKind.GENES);
        scanner.verifyUnchanged(artifact, result);

        assertEquals(4, snapshots.get());
    }

    @Test
    void terminalVerificationRejectsAChangedIdentity() throws Exception {
        Path artifact = writeUtf8("changed-token.genes.tsv", "#RANK\n1\n");
        AtomicInteger snapshots = new AtomicInteger();
        ExomiserTsvScanner scanner = new ExomiserTsvScanner(
                (path, attributes) -> snapshots.incrementAndGet() <= 3
                        ? "original-identity"
                        : "replacement-identity");

        ScanResult result = scanner.inspect(artifact, ArtifactKind.GENES);

        assertCode(Code.FILE_CHANGED,
                () -> scanner.verifyUnchanged(artifact, result));
        assertEquals(4, snapshots.get());
    }

    @Test
    void absentVerificationAcceptsContinuedAbsenceAndRejectsAnyNewEntry() throws Exception {
        Path artifact = directory.resolve("optional.genes.tsv");

        scanner().verifyAbsent(artifact);
        Files.createDirectory(artifact);

        assertCode(Code.FILE_CHANGED, () -> scanner().verifyAbsent(artifact));
    }

    @Test
    void hashesOpaqueCompanionAtExactBoundaryAndRejectsBoundaryPlusOne() throws Exception {
        Path artifact = writeUtf8("result.jsonl", "{\"rank\":1}\n");
        long size = Files.size(artifact);
        ExomiserTsvScanner scanner = scanner();

        RawScanResult result = scanner.inspectRaw(artifact, size);
        scanner.verifyRawUnchanged(artifact, result);

        assertEquals(size, result.byteCount());
        assertEquals(rawSha256(artifact), result.rawSha256());
        assertEquals("RawScanResult[redacted]", result.toString());
        assertCode(Code.TOTAL_BYTES_LIMIT_EXCEEDED,
                () -> scanner.inspectRaw(artifact, size - 1L));
    }

    @Test
    void opaqueCompanionTerminalVerificationRejectsAChangedIdentity() throws Exception {
        Path artifact = writeUtf8("changed-result.jsonl", "{\"rank\":1}\n");
        AtomicInteger snapshots = new AtomicInteger();
        ExomiserTsvScanner scanner = new ExomiserTsvScanner(
                (path, attributes) -> snapshots.incrementAndGet() <= 3
                        ? "original-identity"
                        : "replacement-identity");

        RawScanResult result = scanner.inspectRaw(artifact, 1_024L);

        assertCode(Code.FILE_CHANGED,
                () -> scanner.verifyRawUnchanged(artifact, result));
        assertEquals(4, snapshots.get());
    }

    @Test
    void rejectsMalformedUtf8() throws Exception {
        byte[] header = (VARIANT_HEADER + "\n1\t1\t100\tA\tT\t")
                .getBytes(StandardCharsets.UTF_8);
        byte[] malformed = new byte[header.length + 3];
        System.arraycopy(header, 0, malformed, 0, header.length);
        malformed[header.length] = (byte) 0xc3;
        malformed[header.length + 1] = 0x28;
        malformed[header.length + 2] = '\n';
        Path artifact = directory.resolve("malformed-utf8.tsv");
        Files.write(artifact, malformed);

        assertCode(Code.INVALID_UTF8,
                () -> scanner().inspect(artifact, ArtifactKind.VARIANTS));
    }

    @Test
    void rejectsUtf8BomAndNulBytes() throws Exception {
        byte[] plain = (VARIANT_HEADER + "\n1\t1\t100\tA\tT\tvalue\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] bom = new byte[plain.length + 3];
        bom[0] = (byte) 0xef;
        bom[1] = (byte) 0xbb;
        bom[2] = (byte) 0xbf;
        System.arraycopy(plain, 0, bom, 3, plain.length);
        Path bomArtifact = directory.resolve("bom.tsv");
        Files.write(bomArtifact, bom);

        byte[] nul = plain.clone();
        nul[nul.length - 2] = 0;
        Path nulArtifact = directory.resolve("nul.tsv");
        Files.write(nulArtifact, nul);

        assertCode(Code.BOM_FORBIDDEN,
                () -> scanner().inspect(bomArtifact, ArtifactKind.VARIANTS));
        assertCode(Code.NUL_FORBIDDEN,
                () -> scanner().inspect(nulArtifact, ArtifactKind.VARIANTS));
    }

    @Test
    void rejectsEmbeddedAndTrailingBareCarriageReturns() throws Exception {
        Path embedded = writeUtf8("embedded-cr.tsv",
                VARIANT_HEADER + "\n1\t1\r100\tA\tT\tvalue\n");
        Path trailing = writeUtf8("trailing-cr.tsv", VARIANT_HEADER + "\r");

        assertCode(Code.BARE_CR_FORBIDDEN,
                () -> scanner().inspect(embedded, ArtifactKind.VARIANTS));
        assertCode(Code.BARE_CR_FORBIDDEN,
                () -> scanner().inspect(trailing, ArtifactKind.VARIANTS));
    }

    @Test
    void rejectsMissingBlankAndDuplicateHeaders() throws Exception {
        Path missing = directory.resolve("empty.tsv");
        Files.write(missing, new byte[0]);
        Path blankLine = writeUtf8("blank-header.tsv", "\n");
        Path blankCell = writeUtf8("blank-cell.tsv", "#RANK\t\n1\tx\n");
        Path duplicate = writeUtf8("duplicate.tsv", "#RANK\t#RANK\n1\t1\n");

        assertCode(Code.HEADER_MISSING,
                () -> scanner().inspect(missing, ArtifactKind.GENES));
        assertCode(Code.HEADER_BLANK,
                () -> scanner().inspect(blankLine, ArtifactKind.GENES));
        assertCode(Code.HEADER_BLANK,
                () -> scanner().inspect(blankCell, ArtifactKind.GENES));
        assertCode(Code.HEADER_DUPLICATE,
                () -> scanner().inspect(duplicate, ArtifactKind.GENES));
    }

    @Test
    void rejectsMissingRequiredHeadersForEachArtifactKind() throws Exception {
        Path variants = writeUtf8("missing-alt.tsv", "#RANK\tCONTIG\tSTART\tREF\n");
        Path genes = writeUtf8("missing-rank.tsv", "GENE_SYMBOL\nGENE1\n");

        assertCode(Code.REQUIRED_HEADER_MISSING,
                () -> scanner().inspect(variants, ArtifactKind.VARIANTS));
        assertCode(Code.REQUIRED_HEADER_MISSING,
                () -> scanner().inspect(genes, ArtifactKind.GENES));
    }

    @Test
    void padsShortOptionalTailButRejectsMissingOrBlankRequiredCell() throws Exception {
        Path accepted = writeUtf8("short-tail.tsv",
                VARIANT_HEADER + "\n1\t1\t100\tA\tT\n");
        AtomicReference<Map<String, String>> row = new AtomicReference<>();

        scanner().scan(accepted, ArtifactKind.VARIANTS,
                (value, ordinal) -> row.set(value));

        assertEquals("", row.get().get("OPTIONAL"));

        Path missing = writeUtf8("short-required.tsv",
                VARIANT_HEADER + "\n1\t1\t100\tA\n");
        Path blank = writeUtf8("blank-required.tsv",
                VARIANT_HEADER + "\n1\t1\t100\tA\t\toptional\n");
        assertCode(Code.REQUIRED_CELL_MISSING,
                () -> scanner().inspect(missing, ArtifactKind.VARIANTS));
        assertCode(Code.REQUIRED_CELL_MISSING,
                () -> scanner().inspect(blank, ArtifactKind.VARIANTS));
    }

    @Test
    void rejectsDataRowsWithExtraCells() throws Exception {
        Path artifact = writeUtf8("extra.tsv",
                VARIANT_HEADER + "\n1\t1\t100\tA\tT\toptional\textra\n");

        assertCode(Code.TSV_COLUMN_COUNT_MISMATCH,
                () -> scanner().inspect(artifact, ArtifactKind.VARIANTS));
    }

    @Test
    void totalByteLimitAcceptsExactBoundaryAndRejectsBoundaryPlusOne() throws Exception {
        Path artifact = writeUtf8("byte-boundary.tsv", "#RANK\n1\n");
        long size = Files.size(artifact);

        scanner(new Limits(size, 10, 10, 10, 32)).inspect(artifact, ArtifactKind.GENES);

        assertCode(Code.TOTAL_BYTES_LIMIT_EXCEEDED,
                () -> scanner(new Limits(size - 1L, 10, 10, 10, 32))
                        .inspect(artifact, ArtifactKind.GENES));
    }

    @Test
    void physicalLineLimitAcceptsBoundaryAndRejectsBoundaryPlusOne() throws Exception {
        Limits limits = new Limits(1_024, 2, 10, 10, 32);
        Path exact = writeUtf8("physical-exact.tsv", "#RANK\n1\n");
        Path plusOne = writeUtf8("physical-plus-one.tsv", "#RANK\n1\n\n");

        assertEquals(2L, scanner(limits).inspect(exact, ArtifactKind.GENES)
                .physicalLineCount());
        assertCode(Code.PHYSICAL_LINE_LIMIT_EXCEEDED,
                () -> scanner(limits).inspect(plusOne, ArtifactKind.GENES));
    }

    @Test
    void dataRowLimitAcceptsBoundaryAndRejectsBoundaryPlusOne() throws Exception {
        Limits limits = new Limits(1_024, 10, 2, 10, 32);
        Path exact = writeUtf8("rows-exact.tsv", "#RANK\n1\n2\n");
        Path plusOne = writeUtf8("rows-plus-one.tsv", "#RANK\n1\n2\n3\n");

        assertEquals(2L, scanner(limits).inspect(exact, ArtifactKind.GENES).dataRowCount());
        assertCode(Code.DATA_ROW_LIMIT_EXCEEDED,
                () -> scanner(limits).inspect(plusOne, ArtifactKind.GENES));
    }

    @Test
    void columnLimitAcceptsBoundaryAndRejectsBoundaryPlusOne() throws Exception {
        Limits limits = new Limits(1_024, 10, 10, 3, 64);
        Path exact = writeUtf8("columns-exact.tsv", "#RANK\tA\tB\n1\tx\ty\n");
        Path plusOne = writeUtf8(
                "columns-plus-one.tsv", "#RANK\tA\tB\tC\n1\tx\ty\tz\n");

        assertEquals(3, scanner(limits).inspect(exact, ArtifactKind.GENES).columnCount());
        assertCode(Code.COLUMN_LIMIT_EXCEEDED,
                () -> scanner(limits).inspect(plusOne, ArtifactKind.GENES));
    }

    @Test
    void linePayloadLimitExcludesTerminatorsAndHandlesNoFinalLf() throws Exception {
        Limits limits = new Limits(1_024, 10, 10, 10, 8);
        Path exactCrlf = writeUtf8("line-exact-crlf.tsv", "#RANK\r\n12345678\r\n");
        Path exactNoFinalLf = writeUtf8("line-exact-no-lf.tsv", "#RANK\n12345678");
        Path plusOne = writeUtf8("line-plus-one.tsv", "#RANK\n123456789\n");

        assertEquals(2L, scanner(limits).inspect(exactCrlf, ArtifactKind.GENES)
                .physicalLineCount());
        assertEquals(2L, scanner(limits).inspect(exactNoFinalLf, ArtifactKind.GENES)
                .physicalLineCount());
        assertCode(Code.LINE_BYTES_LIMIT_EXCEEDED,
                () -> scanner(limits).inspect(plusOne, ArtifactKind.GENES));
    }

    @Test
    void defaultVariantDataCeilingRejectsFiftyThousandAndOne() throws Exception {
        Path artifact = directory.resolve("default-row-ceiling.variants.tsv");
        try (BufferedWriter writer = Files.newBufferedWriter(artifact, StandardCharsets.UTF_8)) {
            writer.write(VARIANT_HEADER);
            writer.write('\n');
            for (int index = 1; index <= 50_001; index++) {
                writer.write(Integer.toString(index));
                writer.write("\t1\t100\tA\tT\tvalue\n");
            }
        }

        assertCode(Code.DATA_ROW_LIMIT_EXCEEDED,
                () -> scanner().inspect(artifact, ArtifactKind.VARIANTS));
    }

    @Test
    void identityChangeAcrossSingleOpenFailsClosed() throws Exception {
        Path artifact = writeUtf8("identity-change.tsv", "#RANK\n1\n");
        AtomicInteger snapshots = new AtomicInteger();
        FileIdentitySource changing = (path, attributes) ->
                snapshots.incrementAndGet() < 3 ? "first-identity" : "changed-identity";
        ExomiserTsvScanner scanner = new ExomiserTsvScanner(changing);

        assertCode(Code.FILE_CHANGED,
                () -> scanner.inspect(artifact, ArtifactKind.GENES));
        assertEquals(3, snapshots.get());
    }

    @Test
    void identityCapabilityLossAfterTheInitialSnapshotIsFileChanged() throws Exception {
        for (int failingSnapshot : List.of(2, 3)) {
            Path artifact = writeUtf8(
                    "identity-loss-at-snapshot-" + failingSnapshot + ".tsv", "#RANK\n1\n");
            AtomicInteger snapshots = new AtomicInteger();
            ExomiserTsvScanner scanner = new ExomiserTsvScanner((path, attributes) ->
                    snapshots.incrementAndGet() == failingSnapshot
                            ? null
                            : "stable-identity");

            assertCode(Code.FILE_CHANGED,
                    () -> scanner.inspect(artifact, ArtifactKind.GENES));
            assertEquals(failingSnapshot, snapshots.get());
        }
    }

    @Test
    void identityCapabilityLossDuringTerminalVerificationIsFileChanged() throws Exception {
        Path artifact = writeUtf8("terminal-identity-loss.tsv", "#RANK\n1\n");
        AtomicInteger snapshots = new AtomicInteger();
        ExomiserTsvScanner scanner = new ExomiserTsvScanner((path, attributes) ->
                snapshots.incrementAndGet() == 4 ? null : "stable-identity");
        ScanResult result = scanner.inspect(artifact, ArtifactKind.GENES);

        assertCode(Code.FILE_CHANGED,
                () -> scanner.verifyUnchanged(artifact, result));
        assertEquals(4, snapshots.get());
    }

    @Test
    void presentEntryWithUnavailableIdentityCannotPassAbsentVerification() throws Exception {
        Path artifact = writeUtf8("appeared-without-identity.tsv", "#RANK\n1\n");
        ExomiserTsvScanner scanner = new ExomiserTsvScanner(
                (path, attributes) -> null);

        assertCode(Code.FILE_CHANGED, () -> scanner.verifyAbsent(artifact));
    }

    @Test
    void disappearanceAfterInitialSnapshotIsAlwaysFileChanged() throws Exception {
        for (int failingSnapshot : List.of(2, 3)) {
            Path artifact = writeUtf8(
                    "disappears-at-snapshot-" + failingSnapshot + ".tsv", "#RANK\n1\n");
            AtomicInteger snapshots = new AtomicInteger();
            FileIdentitySource disappearing = (path, attributes) -> {
                int snapshot = snapshots.incrementAndGet();
                if (snapshot == failingSnapshot) {
                    throw new NoSuchFileException(PRIVACY_CANARY);
                }
                return "stable-identity";
            };
            ExomiserTsvScanner scanner = new ExomiserTsvScanner(disappearing);

            ScanException failure = assertCode(Code.FILE_CHANGED,
                    () -> scanner.inspect(artifact, ArtifactKind.GENES));

            assertEquals(failingSnapshot, snapshots.get());
            assertFalse(failure.getMessage().contains(PRIVACY_CANARY));
            assertFalse(failure.toString().contains(PRIVACY_CANARY));
        }
    }

    @Test
    void indirectReplacementAfterInitialSnapshotIsAlwaysFileChanged() throws Exception {
        for (int failingSnapshot : List.of(2, 3)) {
            Path artifact = writeUtf8(
                    "reparse-at-snapshot-" + failingSnapshot + ".tsv", "#RANK\n1\n");
            AtomicInteger snapshots = new AtomicInteger();
            ExomiserTsvScanner scanner = new ExomiserTsvScanner(
                    TEST_IDENTITY,
                    path -> snapshots.incrementAndGet() == failingSnapshot);

            assertCode(Code.FILE_CHANGED,
                    () -> scanner.inspect(artifact, ArtifactKind.GENES));

            assertEquals(failingSnapshot, snapshots.get());
        }
    }

    @Test
    void nullProviderIdentityIsUnsupported() throws Exception {
        Path artifact = writeUtf8("null-identity.tsv", "#RANK\n1\n");
        ExomiserTsvScanner scanner = new ExomiserTsvScanner(
                (path, attributes) -> null);

        assertCode(Code.FILESYSTEM_UNSUPPORTED,
                () -> scanner.inspect(artifact, ArtifactKind.GENES));
    }

    @Test
    void rejectsDirectoryAndSymbolicFileWithoutFollowing() throws Exception {
        assertCode(Code.FILE_INVALID,
                () -> scanner().inspect(directory, ArtifactKind.GENES));

        Path actual = writeUtf8("actual.tsv", "#RANK\n1\n");
        Path symbolic = directory.resolve("symbolic.tsv");
        try {
            Files.createSymbolicLink(symbolic, actual);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("symbolic links unavailable on this host");
        }
        assertCode(Code.FILE_INVALID,
                () -> scanner().inspect(symbolic, ArtifactKind.GENES));
    }

    @Test
    void rejectsRegularFileWhenDosReparseSeamReportsReparsePoint() throws Exception {
        Path artifact = writeUtf8("reported-reparse.tsv", "#RANK\n1\n");
        ExomiserTsvScanner scanner = new ExomiserTsvScanner(
                TEST_IDENTITY, path -> true);

        assertCode(Code.FILE_INVALID,
                () -> scanner.inspect(artifact, ArtifactKind.GENES));
    }

    @Test
    void filesystemAndConsumerFailuresNeverLeakPathCanaryOrRawCause() throws Exception {
        String pathCanary = "patient-Alice-secret-token";
        Path missing = directory.resolve(pathCanary + ".tsv");
        ScanException missingFailure = assertCode(Code.FILE_MISSING,
                () -> scanner().inspect(missing, ArtifactKind.GENES));
        assertFalse(missingFailure.getMessage().contains(pathCanary));
        assertFalse(missingFailure.toString().contains(pathCanary));
        assertFalse(missingFailure.getMessage().contains(missing.toAbsolutePath().toString()));
        assertFalse(missingFailure.toString().contains(missing.toAbsolutePath().toString()));
        assertNull(missingFailure.getCause());
        assertEquals(0, missingFailure.getSuppressed().length);

        Path artifact = writeUtf8("consumer.tsv", "#RANK\n1\n");
        ScanException consumerFailure = assertCode(Code.CONSUMER_FAILED,
                () -> scanner().scan(artifact, ArtifactKind.GENES,
                        (row, ordinal) -> {
                            throw new IllegalStateException(PRIVACY_CANARY);
                        }));
        assertFalse(consumerFailure.getMessage().contains(PRIVACY_CANARY));
        assertFalse(consumerFailure.toString().contains(PRIVACY_CANARY));
    }

    @Test
    void rejectsNullArgumentsWithStableCodes() {
        assertCode(Code.PATH_REQUIRED,
                () -> scanner().inspect(null, ArtifactKind.GENES));
        assertCode(Code.KIND_REQUIRED,
                () -> scanner().inspect(directory.resolve("unused"), null));
        assertCode(Code.CONSUMER_REQUIRED,
                () -> scanner().scan(
                        directory.resolve("unused"), ArtifactKind.GENES, null));
        assertCode(Code.PATH_REQUIRED,
                () -> scanner().inspectRaw(null, 1L));
        assertCode(Code.INVALID_LIMITS,
                () -> scanner().inspectRaw(directory.resolve("unused"), 0L));
        assertCode(Code.SCAN_RESULT_REQUIRED,
                () -> scanner().verifyUnchanged(directory.resolve("unused"), null));
        assertCode(Code.SCAN_RESULT_REQUIRED,
                () -> scanner().verifyRawUnchanged(directory.resolve("unused"), null));
        assertCode(Code.PATH_REQUIRED,
                () -> scanner().verifyAbsent(null));
    }

    private ExomiserTsvScanner scanner() {
        return new ExomiserTsvScanner(TEST_IDENTITY);
    }

    private ExomiserTsvScanner scanner(Limits limits) {
        return new ExomiserTsvScanner(limits, TEST_IDENTITY);
    }

    private Path writeUtf8(String name, String value) throws IOException {
        Path path = directory.resolve(name);
        Files.writeString(path, value, StandardCharsets.UTF_8);
        return path;
    }

    private static ScanException assertCode(Code code, Executable executable) {
        ScanException exception = assertThrows(ScanException.class, executable);
        assertEquals(code, exception.code());
        assertEquals(code.name(), exception.getMessage());
        assertNull(exception.getCause());
        assertEquals(0, exception.getSuppressed().length);
        return exception;
    }

    private static String rawSha256(Path artifact) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8 * 1024];
        try (InputStream input = Files.newInputStream(artifact, StandardOpenOption.READ)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final FileIdentitySource TEST_IDENTITY = (path, attributes) -> {
        Object providerKey = attributes.fileKey();
        return providerKey == null
                ? new SyntheticIdentity(
                attributes.isDirectory(), attributes.creationTime(), attributes.size())
                : providerKey;
    };

    private static final class SyntheticIdentity {
        private final boolean directory;
        private final FileTime created;
        private final long initialSize;

        private SyntheticIdentity(boolean directory, FileTime created, long initialSize) {
            this.directory = directory;
            this.created = created;
            this.initialSize = initialSize;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof SyntheticIdentity)) {
                return false;
            }
            SyntheticIdentity that = (SyntheticIdentity) other;
            return directory == that.directory
                    && initialSize == that.initialSize
                    && Objects.equals(created, that.created);
        }

        @Override
        public int hashCode() {
            return Objects.hash(directory, created, initialSize);
        }
    }
}
