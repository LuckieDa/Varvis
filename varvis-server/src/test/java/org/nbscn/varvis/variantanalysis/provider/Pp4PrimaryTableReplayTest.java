package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Closed synthetic original-table controls. A replayed table is never clinical qualification. */
class Pp4PrimaryTableReplayTest {
    private static final String TABLE = "/article[1]/body[1]/table-wrap[1]/table[1]";
    private static final String WRAP = """
            <table-wrap id="T1"><label>Table 1.</label><caption><p>Native results &amp; limitations.</p></caption>
            <table id="native-grid" frame="hsides"><thead>
            <tr data-row="h1"><th rowspan="2">Participant</th><th colspan="2">Assessment</th><th rowspan="2">Enrollment</th></tr>
            <tr data-row="h2"><th>Exam</th><th>Finding</th></tr>
            </thead><tbody>
            <tr data-row="r1"><td rowspan="2">Case A</td><td> Exam 1 </td><td>Present <xref ref-type="table-fn" rid="fn1">a</xref> / <sup>c</sup></td><td>Included</td></tr>
            <tr data-row="r2"><td>Exam 2</td><td>Absent</td><td>Included</td></tr>
            <tr data-row="r3"><td>Case B</td><td>Not performed</td><td>Not reported</td><td>Excluded negative row — retain verbatim</td></tr>
            </tbody><tfoot><tr data-row="footer"><td colspan="4">All source rows retained</td></tr></tfoot></table>
            <table-wrap-foot><fn id="fn1"><p>Footnote a: not a diagnostic conclusion.</p></fn></table-wrap-foot></table-wrap>
            """;

    private static String article(String identifiers, String body) {
        return "<article><front><article-meta>" + identifiers + "</article-meta></front><body>" + body
                + "</body><back><ref-list><ref id=\"ref1\"><element-citation><pub-id pub-id-type=\"pmc\">222</pub-id>"
                + "</element-citation></ref></ref-list></back></article>";
    }
    private static String article(String body) { return article("<article-id pub-id-type=\"pmc\">111</article-id>", body); }
    private static byte[] raw(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static Pp4PrimaryTableReplay.DocumentTable replay(byte[] bytes) throws Exception {
        return Pp4PrimaryTableReplay.replay(bytes, sha(bytes), "PMC111", "T1");
    }
    private static Pp4PrimaryTableReplay.NativeCell cell(int number, String name, int rowSpan, int columnSpan,
            String text, String locator, Map<String, String> attributes) {
        return new Pp4PrimaryTableReplay.NativeCell(number, name, rowSpan, columnSpan, text, locator, attributes, List.of());
    }
    private static Pp4PrimaryTableReplay.NativeRow row(int global, int sectionRow, String section, String id,
            Pp4PrimaryTableReplay.NativeCell... cells) {
        return new Pp4PrimaryTableReplay.NativeRow(global, sectionRow, section, Map.of("data-row", id), List.of(cells));
    }
    private static Pp4PrimaryTableReplay.DocumentTable independentlyExpected(byte[] bytes) throws Exception {
        return independentlyExpected(bytes, "T1");
    }
    private static Pp4PrimaryTableReplay.DocumentTable independentlyExpected(byte[] bytes, String tableId) throws Exception {
        String h1 = TABLE + "/thead[1]/tr[1]", h2 = TABLE + "/thead[1]/tr[2]", b1 = TABLE + "/tbody[1]/tr[1]",
                b2 = TABLE + "/tbody[1]/tr[2]", b3 = TABLE + "/tbody[1]/tr[3]", foot = TABLE + "/tfoot[1]/tr[1]";
        var headers = List.of(
                row(1, 1, "THEAD", "h1", cell(1, "th", 2, 1, "Participant", h1 + "/th[1]", Map.of("rowspan", "2")),
                        cell(2, "th", 1, 2, "Assessment", h1 + "/th[2]", Map.of("colspan", "2")),
                        cell(3, "th", 2, 1, "Enrollment", h1 + "/th[3]", Map.of("rowspan", "2"))),
                row(2, 2, "THEAD", "h2", cell(1, "th", 1, 1, "Exam", h2 + "/th[1]", Map.of()),
                        cell(2, "th", 1, 1, "Finding", h2 + "/th[2]", Map.of())));
        var references = List.of(new Pp4PrimaryTableReplay.CellReference("table-fn", "fn1", "a", b1 + "/td[3]/xref[1]"),
                new Pp4PrimaryTableReplay.CellReference("SUPERSCRIPT", "", "c", b1 + "/td[3]/sup[1]"));
        var bodies = List.of(
                row(3, 1, "TBODY", "r1", cell(1, "td", 2, 1, "Case A", b1 + "/td[1]", Map.of("rowspan", "2")),
                        cell(2, "td", 1, 1, " Exam 1 ", b1 + "/td[2]", Map.of()),
                        new Pp4PrimaryTableReplay.NativeCell(3, "td", 1, 1, "Present a / c", b1 + "/td[3]", Map.of(), references),
                        cell(4, "td", 1, 1, "Included", b1 + "/td[4]", Map.of())),
                row(4, 2, "TBODY", "r2", cell(1, "td", 1, 1, "Exam 2", b2 + "/td[1]", Map.of()),
                        cell(2, "td", 1, 1, "Absent", b2 + "/td[2]", Map.of()),
                        cell(3, "td", 1, 1, "Included", b2 + "/td[3]", Map.of())),
                row(5, 3, "TBODY", "r3", cell(1, "td", 1, 1, "Case B", b3 + "/td[1]", Map.of()),
                        cell(2, "td", 1, 1, "Not performed", b3 + "/td[2]", Map.of()),
                        cell(3, "td", 1, 1, "Not reported", b3 + "/td[3]", Map.of()),
                        cell(4, "td", 1, 1, "Excluded negative row — retain verbatim", b3 + "/td[4]", Map.of())));
        var footers = List.of(row(6, 1, "TFOOT", "footer", cell(1, "td", 1, 4, "All source rows retained", foot + "/td[1]", Map.of("colspan", "4"))));
        var expected = new Pp4PrimaryTableReplay.DocumentTable("PMC111", tableId, sha(bytes), "0".repeat(64), TABLE,
                "Table 1.", "Native results & limitations.", "Footnote a: not a diagnostic conclusion.",
                Map.of("id", "native-grid", "frame", "hsides"), headers, bodies, footers);
        var mapper = new ObjectMapper();
        ObjectNode grid = mapper.valueToTree(expected);
        grid.remove(List.of("rawArtifactSha256", "canonicalGridSha256"));
        grid.put("schemaVersion", "pp4-primary-table-native-grid-v1");
        String pin = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(grid);
        return new Pp4PrimaryTableReplay.DocumentTable(expected.pmcid(), expected.tableId(), expected.rawArtifactSha256(), pin,
                expected.tableLocator(), expected.label(), expected.caption(), expected.footnotes(), expected.attributes(), headers, bodies, footers);
    }

    @Test void fullNativeGridMatchesAnIndependentClosedFixtureIncludingAllSourceRows() throws Exception {
        byte[] bytes = raw(article(WRAP));
        var actual = replay(bytes);
        assertEquals(independentlyExpected(bytes), actual);
        assertEquals(2, actual.headerRows().size()); assertEquals(3, actual.bodyRows().size()); assertEquals(1, actual.footerRows().size());
        assertEquals(3, actual.bodyRows().get(1).cells().size(), "rowspan must not invent another literal cell");
        assertEquals(1, actual.bodyRows().get(1).cells().get(0).cellNumber());
        assertEquals("Not performed", actual.bodyRows().get(2).cells().get(1).text());
        assertEquals("Not reported", actual.bodyRows().get(2).cells().get(2).text());
        assertEquals("Excluded negative row — retain verbatim", actual.bodyRows().get(2).cells().get(3).text());
    }

    @Test void rawByteProvenanceAndCanonicalNativeGridHaveDifferentExplicitHashScopes() throws Exception {
        byte[] left = raw(article(WRAP)), right = raw(article(WRAP.replace(" Exam 1 ", " Exam &#x31; ")));
        var a = replay(left); var b = replay(right);
        assertNotEquals(sha(left), sha(right));
        assertEquals(sha(left), a.rawArtifactSha256()); assertEquals(sha(right), b.rawArtifactSha256());
        assertEquals(a.canonicalGridSha256(), b.canonicalGridSha256(), "same original DOM grid, different XML byte encodings");
        assertEquals(a, replay(left), "replaying the same bytes is exact and deterministic");
        var changed = replay(raw(article(WRAP.replace("Not reported", "Present"))));
        assertNotEquals(a.canonicalGridSha256(), changed.canonicalGridSha256(), "native cell content participates in the grid pin");
    }

    @Test void inputAndReturnedCollectionsHaveIndependentImmutableOwnership() throws Exception {
        byte[] bytes = raw(article(WRAP)); var expected = independentlyExpected(bytes); var actual = replay(bytes);
        bytes[0] = 0;
        assertEquals(expected, actual);
        assertThrows(UnsupportedOperationException.class, () -> actual.attributes().put("private", "injected"));
        assertThrows(UnsupportedOperationException.class, () -> actual.headerRows().clear());
        assertThrows(UnsupportedOperationException.class, () -> actual.bodyRows().get(0).attributes().clear());
        assertThrows(UnsupportedOperationException.class, () -> actual.bodyRows().get(0).cells().clear());
        assertThrows(UnsupportedOperationException.class, () -> actual.bodyRows().get(0).cells().get(0).attributes().clear());
        assertThrows(UnsupportedOperationException.class, () -> actual.bodyRows().get(0).cells().get(2).references().clear());
        var attributes = new LinkedHashMap<>(Map.of("data-original", "retained"));
        var references = new ArrayList<>(List.of(new Pp4PrimaryTableReplay.CellReference("table-fn", "fn1", "a", "/xref[1]")));
        var sourceCell = new Pp4PrimaryTableReplay.NativeCell(1, "td", 1, 1, "literal", "/td[1]", attributes, references);
        var cells = new ArrayList<>(List.of(sourceCell));
        var sourceRow = new Pp4PrimaryTableReplay.NativeRow(1, 1, "TBODY", attributes, cells);
        attributes.clear(); references.clear(); cells.clear();
        assertEquals(Map.of("data-original", "retained"), sourceCell.attributes());
        assertEquals(1, sourceCell.references().size()); assertEquals(1, sourceRow.cells().size());
        assertEquals(Map.of("data-original", "retained"), sourceRow.attributes());
    }

    @Test void thePrimaryArticleIdentityCannotBeBorrowedFromACitedReference() throws Exception {
        byte[] bytes = raw(article(WRAP));
        assertThrows(IOException.class, () -> Pp4PrimaryTableReplay.replay(bytes, sha(bytes), "PMC222", "T1"));
        byte[] referenceOnly = raw(article("", WRAP));
        assertThrows(IOException.class, () -> Pp4PrimaryTableReplay.replay(referenceOnly, sha(referenceOnly), "PMC222", "T1"));
        for (String identity : List.of("", "pmc111", "PMC0", "111"))
            assertThrows(IOException.class, () -> Pp4PrimaryTableReplay.replay(bytes, sha(bytes), identity, "T1"));
        var ordinary = replay(bytes);
        var alternate = replay(raw(article("<article-id pub-id-type=\"pmcid\">PMC111</article-id>", WRAP)));
        assertEquals("PMC111", alternate.pmcid());
        assertEquals(ordinary.canonicalGridSha256(), alternate.canonicalGridSha256());
        assertNotEquals(ordinary.rawArtifactSha256(), alternate.rawArtifactSha256(),
                "the alternate primary identifier encoding changes original bytes, not the native table");
    }

    @Test void duplicateArticleOrXmlTableIdentitiesAreRejected() throws Exception {
        for (String xml : List.of(
                article("<article-id pub-id-type=\"pmc\">111</article-id><article-id pub-id-type=\"pmcid\">PMC111</article-id>", WRAP),
                article(WRAP + WRAP),
                article(WRAP.replace("</table-wrap-foot>", "<fn id=\"fn1\"><p>Duplicate locator</p></fn></table-wrap-foot>")))) {
            byte[] bytes = raw(xml); assertThrows(IOException.class, () -> replay(bytes));
        }
    }

    @Test void parentPmcidCannotAuthorizeATableOwnedByAChildArticleOrCitation() throws Exception {
        String child = "<sub-article id=\"foreign-child\"><front-stub><article-id pub-id-type=\"pmc\">PMC222</article-id>"
                + "</front-stub><body>" + WRAP + "</body></sub-article>";
        byte[] childOwned = raw(article("").replace("</body>", "</body>" + child));
        byte[] citationOwned = raw(article("").replace("<element-citation>", "<element-citation>" + WRAP));
        assertAll(
                () -> assertThrows(IOException.class, () -> replay(childOwned),
                        "a table inside the PMC222 sub-article must not be attributed to primary PMC111"),
                () -> assertThrows(IOException.class, () -> replay(citationOwned),
                        "a cited table is not an original body table of primary PMC111"));
    }

    @Test void wrongRawPinAndMissingTableCannotBecomeAReplayedSource() throws Exception {
        byte[] bytes = raw(article(WRAP));
        assertThrows(IOException.class, () -> Pp4PrimaryTableReplay.replay(bytes, "a".repeat(64), "PMC111", "T1"));
        for (String tableId : List.of("", "T2", "native-grid"))
            assertThrows(IOException.class, () -> Pp4PrimaryTableReplay.replay(bytes, sha(bytes), "PMC111", tableId));
        for (String xml : List.of("", "{}", "{\"status\":\"REVIEWED\",\"eligible\":true}", "<article/>")) {
            byte[] raw = raw(xml); assertThrows(IOException.class, () -> replay(raw));
        }
    }

    @Test void nativeTableIdsAreBoundedOpaqueUnicodeValuesNeverXPathExpressions() throws Exception {
        for (String nativeId : List.of("表 格 / 一 [1]", "表".repeat(128))) {
            byte[] bytes = raw(article(WRAP.replace("id=\"T1\"", "id=\"" + nativeId + "\"")));
            var actual = Pp4PrimaryTableReplay.replay(bytes, sha(bytes), "PMC111", nativeId);
            assertEquals(independentlyExpected(bytes, nativeId), actual);
            assertEquals(nativeId, actual.tableId());
            assertThrows(IOException.class, () -> Pp4PrimaryTableReplay.replay(bytes, sha(bytes), "PMC111", "/article[1]/body[1]/table-wrap[1]"));
        }
        byte[] bytes = raw(article(WRAP));
        for (String invalid : List.of("   ", "x".repeat(129), "T1\nother", "T1\rother", "T1\tother", "T1\u007f"))
            assertThrows(IOException.class, () -> Pp4PrimaryTableReplay.replay(bytes, sha(bytes), "PMC111", invalid));
        for (String invalidAttribute : List.of("   ", "x".repeat(129), "bad&#9;id")) {
            byte[] invalidNative = raw(article("<sec id=\"" + invalidAttribute + "\"/>" + WRAP));
            assertThrows(IOException.class, () -> replay(invalidNative));
        }
    }

    @Test void internalDtdSubsetsAndCustomEntityClaimsAreNeverTrustedText() throws Exception {
        for (String declaration : List.of("<!DOCTYPE article []>",
                "<!DOCTYPE article [<!ENTITY clinical 'REVIEWED and eligible'>]>",
                "<!DOCTYPE article [<!ENTITY external SYSTEM 'file:///not-a-permitted-original'>]>")) {
            byte[] bytes = raw(declaration + article(WRAP)); assertThrows(IOException.class, () -> replay(bytes));
        }
        byte[] unresolvedEntity = raw(article(WRAP.replace("Not reported", "&clinical;")));
        assertThrows(IOException.class, () -> replay(unresolvedEntity));
    }

    @Test void officialPublicArchiveDoctypeCanBeDeclaredWithoutReadingAnyExternalDtd() throws Exception {
        String declaration = "<!DOCTYPE article PUBLIC \"-//NLM//DTD JATS (Z39.96) Journal Archiving and Interchange DTD v1.3 20210610//EN\" \"JATS-archivearticle1-3.dtd\">";
        byte[] bytes = raw(declaration + article(WRAP));
        assertEquals(independentlyExpected(bytes), replay(bytes));
    }

    @Test @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void unapprovedHttpSystemIdentifierIsRejectedWithoutARequestToTheTestLocalListener() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/primary.dtd", exchange -> {
            requests.incrementAndGet(); byte[] response = raw("<!-- test-local sentinel, no external source -->");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            String declaration = "<!DOCTYPE article PUBLIC \"-//NLM//DTD JATS (Z39.96) Journal Archiving and Interchange DTD v1.3 20210610//EN\" \"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/primary.dtd\">";
            byte[] bytes = raw(declaration + article(WRAP));
            assertThrows(IOException.class, () -> replay(bytes));
            assertEquals(0, requests.get(), "an unsupported identifier must never be fetched, even from a test-local endpoint");
        } finally { server.stop(0); }
    }

    @Test void spansMustBeBoundedAndCannotBorrowRowsFromAnotherSourceSection() throws Exception {
        for (String changed : List.of(WRAP.replace("rowspan=\"2\"", "rowspan=\"257\""),
                WRAP.replace("rowspan=\"2\"", "rowspan=\"3\""),
                WRAP.replace("rowspan=\"2\"", "rowspan=\"0\""),
                WRAP.replace("colspan=\"2\"", "colspan=\"129\""),
                WRAP.replace("colspan=\"2\"", "colspan=\"not-an-integer\""),
                WRAP.replace("<th colspan=\"2\">Assessment</th>", "<th colspan=\"128\">Assessment</th>"))) {
            byte[] bytes = raw(article(changed)); assertThrows(IOException.class, () -> replay(bytes));
        }
    }

    @Test void depthRowsAndByteLimitsRejectOversizedOriginalsBeforeTheyBecomeTables() throws Exception {
        String deep = "<sec>".repeat(65) + "literal" + "</sec>".repeat(65);
        assertThrows(IOException.class, () -> replay(raw(article(deep + WRAP))));
        String rows = "<table-wrap id=\"T1\"><table><tbody>" + "<tr><td>literal</td></tr>".repeat(257) + "</tbody></table></table-wrap>";
        assertThrows(IOException.class, () -> replay(raw(article(rows))));
        byte[] large = raw(article("<!--" + "x".repeat(1_048_576) + "-->" + WRAP));
        assertThrows(IOException.class, () -> replay(large));
    }
}
