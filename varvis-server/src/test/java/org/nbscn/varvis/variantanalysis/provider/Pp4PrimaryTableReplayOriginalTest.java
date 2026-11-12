package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Opt-in replay of a pinned public primary article, not a PP4 adoption positive.
 * All expected native cells/coordinates were frozen from the original before
 * the Java helper was implemented. No inferred clinical age or cohort yield.
 */
@EnabledIfSystemProperty(named = "varvis.t04.primary-source-xml", matches = ".+")
class Pp4PrimaryTableReplayOriginalTest {
    private static final String RAW = "0a542ec70647ba134445511e7c80ae55da005827dfb014aff5d38d7ecf8d13ed";

    private byte[] original() throws Exception {
        return Files.readAllBytes(Path.of(System.getProperty("varvis.t04.primary-source-xml")));
    }
    private Pp4PrimaryTableReplay.DocumentTable replay() throws Exception {
        return Pp4PrimaryTableReplay.replay(original(), RAW, "PMC12048594", "Tab1");
    }

    @Test void completeNativeScopeIncludesBothHeadersMergedCellsAndBlankValues() throws Exception {
        var table = replay();
        assertEquals("PMC12048594", table.pmcid());
        assertEquals("Tab1", table.tableId());
        assertEquals(RAW, table.rawArtifactSha256());
        assertEquals(2, table.headerRows().size());
        assertEquals(20, table.bodyRows().size());
        assertTrue(table.footerRows().isEmpty());
        assertEquals(19, table.headerRows().get(0).cells().size());
        assertEquals(3, table.headerRows().get(1).cells().size());
        assertEquals("Age at time of testing (years)", table.headerRows().get(0).cells().get(2).text());
        assertEquals(2, table.headerRows().get(0).cells().get(2).rowSpan());
        var first = table.bodyRows().get(0);
        assertEquals(21, first.cells().size());
        assertEquals("Exon", first.cells().get(0).text());
        assertEquals(16, first.cells().get(0).rowSpan());
        assertEquals("", first.cells().get(4).text(), "unreported literal cell stays blank, not zero or absent finding");
        assertTrue(table.canonicalGridSha256().matches("[a-f0-9]{64}"));
        assertNotEquals(RAW, table.canonicalGridSha256(), "grid hash is not the artifact raw-byte hash");
        assertEquals(table, replay());
    }

    @Test void caseNativeCoordinatesPreserveTestingAgeFootnoteWithoutClinicalInterpretation() throws Exception {
        var table = replay(); var row = table.bodyRows().get(3);
        assertEquals(6, row.sourceRowNumber());
        assertEquals(4, row.sectionRowNumber());
        assertEquals("TBODY", row.section());
        assertEquals(20, row.cells().size(), "rowspan inheritance never invents a literal patient column");
        assertEquals("c.2504A>Tp.(Glu835Val)", row.cells().get(0).text());
        var age = row.cells().get(1);
        assertEquals("23c", age.text());
        assertThrows(NumberFormatException.class, () -> Integer.parseInt(age.text()), "do not strip the source superscript to invent a numeric clinical age");
        assertTrue(age.references().stream().anyMatch(ref -> ref.refType().equals("SUPERSCRIPT")
                && ref.text().equals("c") && ref.rid().isEmpty() && ref.locator().contains("sup[1]")));
        assertTrue(table.footnotes().contains("Samsung Medical Center"));
        assertEquals("F", row.cells().get(2).text());
        assertEquals("2.95", row.cells().get(3).text());
        assertEquals("\u2212", row.cells().get(4).text());
        assertEquals("+", row.cells().get(5).text());
        assertEquals("12", row.cells().get(6).text());
        assertEquals("+", row.cells().get(7).text());
        for (int i = 0; i < row.cells().size(); i++) {
            assertEquals(i + 1, row.cells().get(i).cellNumber());
            assertEquals(1, row.cells().get(i).rowSpan());
            assertEquals(1, row.cells().get(i).columnSpan());
        }
    }

    @Test void independentAlternativeCaseAndOtherTestedRowsAreNotDroppedOrBorrowed() throws Exception {
        var table = replay(); var row = table.bodyRows().get(12);
        assertEquals(15, row.sourceRowNumber());
        assertEquals(13, row.sectionRowNumber());
        assertEquals("c.6403G>Ap.(Asp2135Asn)", row.cells().get(0).text());
        assertEquals("61", row.cells().get(1).text());
        assertEquals("7.93", row.cells().get(3).text());
        assertNotEquals(row.cells().get(0).locator(), table.bodyRows().get(3).cells().get(0).locator());
        assertNotEquals(row.cells().get(1).text(), table.bodyRows().get(3).cells().get(1).text());
        // The article's mixed-method/VUS-selected cohort is not turned into a
        // matched diagnostic denominator merely because this complete table replays.
        assertEquals(20, table.bodyRows().size());
    }

    @Test void replayedNativeRecordsAndOriginalBytesStayImmutable() throws Exception {
        var raw = original(); var before = raw.clone();
        var table = Pp4PrimaryTableReplay.replay(raw, RAW, "PMC12048594", "Tab1");
        assertArrayEquals(before, raw);
        assertThrows(UnsupportedOperationException.class, () -> table.bodyRows().clear());
        assertThrows(UnsupportedOperationException.class, () -> table.bodyRows().get(3).cells().clear());
        assertThrows(UnsupportedOperationException.class, () -> table.bodyRows().get(3).cells().get(1).references().clear());
        assertEquals(RAW, table.rawArtifactSha256());
        assertEquals(table, replay());
    }
}
