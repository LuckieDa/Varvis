package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentType;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Bounded replay of a primary JATS table, not material qualification or an adoption issuer.
 * Returned records (including publicly constructible records) confer no scientific authority.
 * Cell text is native DOM text content, including superscripts; it is not a clinical fact extraction.
 * Only tables in the root article's own direct body are replayed, never nested articles or cited material.
 */
public final class Pp4PrimaryTableReplay {
    private static final int MAX_BYTES = 1024 * 1024, MAX_DEPTH = 64, MAX_ELEMENTS = 100000;
    private static final int MAX_TABLES = 128, MAX_ROWS = 256, MAX_CELLS = 4096, MAX_COLUMNS = 128;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Pp4PrimaryTableReplay() { }

    public record CellReference(String refType, String rid, String text, String locator) { }
    public record NativeCell(int cellNumber, String elementName, int rowSpan, int columnSpan,
            String text, String locator, Map<String, String> attributes, List<CellReference> references) {
        public NativeCell { attributes = immutable(attributes); references = List.copyOf(references); }
    }
    public record NativeRow(int sourceRowNumber, int sectionRowNumber, String section,
            Map<String, String> attributes, List<NativeCell> cells) {
        public NativeRow { attributes = immutable(attributes); cells = List.copyOf(cells); }
    }
    public record DocumentTable(String pmcid, String tableId, String rawArtifactSha256,
            String canonicalGridSha256, String tableLocator, String label, String caption, String footnotes,
            Map<String, String> attributes, List<NativeRow> headerRows, List<NativeRow> bodyRows,
            List<NativeRow> footerRows) {
        public DocumentTable {
            attributes = immutable(attributes); headerRows = List.copyOf(headerRows);
            bodyRows = List.copyOf(bodyRows); footerRows = List.copyOf(footerRows);
        }
    }

    /** All indices are one-based literal source positions; spanning cells are never duplicated. */
    public static DocumentTable replay(byte[] raw, String expectedRawSha256, String pmcid, String tableId) throws IOException {
        if (raw == null || raw.length == 0 || raw.length > MAX_BYTES) throw new IOException("PP4_PRIMARY_XML_SIZE");
        if (expectedRawSha256 == null || !expectedRawSha256.matches("[a-f0-9]{64}") ||
                pmcid == null || !pmcid.matches("PMC[1-9][0-9]{0,15}") || !nativeId(tableId))
            throw new IOException("PP4_PRIMARY_INPUT_IDENTITY");
        byte[] bytes = raw.clone();
        String rawSha = sha256(bytes);
        require(rawSha.equals(expectedRawSha256), "PP4_PRIMARY_RAW_SHA_MISMATCH");
        rejectInternalEntities(bytes);
        Document document = parse(bytes);
        Map<String, Element> ids = checkTree(document);
        Element article = document.getDocumentElement();
        if (article == null || !name(article).equals("article")) throw new IOException("PP4_PRIMARY_JATS_ARTICLE_REQUIRED");
        verifyDoctype(document.getDoctype());
        String encoding = document.getXmlEncoding();
        require(encoding == null || encoding.equalsIgnoreCase("UTF-8") || encoding.equalsIgnoreCase("UTF8"), "PP4_PRIMARY_UTF8_REQUIRED");
        Element meta = onlyChild(onlyChild(article, "front"), "article-meta");
        List<Element> articleIds = children(meta).stream().filter(e -> name(e).equals("article-id") &&
                Set.of("pmc", "pmcid").contains(e.getAttribute("pub-id-type"))).toList();
        require(articleIds.size() == 1, "PP4_PRIMARY_PMCID_AMBIGUOUS");
        String actualPmcid = articleIds.get(0).getTextContent().trim();
        if (!actualPmcid.startsWith("PMC")) actualPmcid = "PMC" + actualPmcid;
        require(pmcid.equals(actualPmcid), "PP4_PRIMARY_PMCID_MISMATCH");
        Element wrap = ids.get(tableId);
        if (wrap == null || !name(wrap).equals("table-wrap")) throw new IOException("PP4_PRIMARY_TABLE_ID_MISMATCH");
        verifyPrimaryOwner(article, wrap);
        var tables = wrap.getElementsByTagNameNS("*", "table");
        require(tables.getLength() == 1, "PP4_PRIMARY_TABLE_AMBIGUOUS");
        Element table = (Element) tables.item(0);
        var header = new ArrayList<NativeRow>(); var body = new ArrayList<NativeRow>(); var footer = new ArrayList<NativeRow>();
        int[] totals = {0, 0};
        List<Element> tableChildren = children(table);
        boolean direct = tableChildren.stream().anyMatch(e -> name(e).equals("tr"));
        if (direct) {
            require(tableChildren.stream().noneMatch(e -> Set.of("thead", "tbody", "tfoot").contains(name(e))), "PP4_PRIMARY_ROW_GROUP_AMBIGUOUS");
            readRows(tableChildren.stream().filter(e -> name(e).equals("tr")).toList(), "TABLE_DIRECT", body, totals);
        }
        int heads = 0, feet = 0;
        for (Element child : tableChildren) {
            String section = name(child);
            if (Set.of("thead", "tbody", "tfoot").contains(section)) {
                if (section.equals("thead")) require(++heads == 1, "PP4_PRIMARY_HEADER_AMBIGUOUS");
                if (section.equals("tfoot")) require(++feet == 1, "PP4_PRIMARY_FOOTER_AMBIGUOUS");
                List<Element> rows = children(child);
                require(rows.stream().allMatch(e -> name(e).equals("tr")), "PP4_PRIMARY_ROW_STRUCTURE");
                readRows(rows, section.toUpperCase(java.util.Locale.ROOT), section.equals("thead") ? header : section.equals("tfoot") ? footer : body, totals);
            } else require(Set.of("tr", "colgroup", "col", "caption").contains(section), "PP4_PRIMARY_TABLE_STRUCTURE");
        }
        require(totals[0] > 0 && totals[1] > 0 && table.getElementsByTagNameNS("*", "tr").getLength() == totals[0] &&
                table.getElementsByTagNameNS("*", "th").getLength() + table.getElementsByTagNameNS("*", "td").getLength() == totals[1], "PP4_PRIMARY_ROW_SCOPE_MISMATCH");
        var facts = new DocumentTable(pmcid, tableId, rawSha, "", locator(table), textChild(wrap, "label"),
                textChild(wrap, "caption"), textChild(wrap, "table-wrap-foot"), attributes(table), header, body, footer);
        // C14N of complete native facts, not bytes of the original table or an authentication claim.
        ObjectNode grid = MAPPER.valueToTree(facts);
        grid.remove(List.of("rawArtifactSha256", "canonicalGridSha256"));
        grid.put("schemaVersion", "pp4-primary-table-native-grid-v1");
        String gridSha = new RuntimeEvidenceV2Adapter(MAPPER).canonicalSha256(grid);
        return new DocumentTable(pmcid, tableId, rawSha, gridSha, facts.tableLocator(), facts.label(), facts.caption(),
                facts.footnotes(), facts.attributes(), header, body, footer);
    }

    private static void verifyPrimaryOwner(Element article, Element wrap) throws IOException {
        Element primaryBody = onlyChild(article, "body");
        boolean withinPrimaryBody = false;
        Node cursor = wrap.getParentNode();
        while (cursor != null && cursor != article) {
            if (cursor instanceof Element ancestor) {
                String tag = name(ancestor);
                require(!Set.of("article", "sub-article", "ref", "ref-list", "citation", "element-citation", "mixed-citation").contains(tag),
                        "PP4_PRIMARY_TABLE_OWNERSHIP_MISMATCH");
                if (tag.equals("body")) {
                    require(!withinPrimaryBody && ancestor == primaryBody, "PP4_PRIMARY_TABLE_OWNERSHIP_MISMATCH");
                    withinPrimaryBody = true;
                }
            }
            cursor = cursor.getParentNode();
        }
        require(cursor == article && withinPrimaryBody, "PP4_PRIMARY_TABLE_OWNERSHIP_MISMATCH");
    }

    private static Document parse(byte[] raw) throws IOException {
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true); factory.setValidating(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("jdk.xml.maxElementDepth", MAX_DEPTH);
            factory.setAttribute("jdk.xml.elementAttributeLimit", 64);
            factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("PP4_PRIMARY_EXTERNAL_RESOLUTION_FORBIDDEN"); });
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException error) throws SAXException { throw error; }
                @Override public void fatalError(SAXParseException error) throws SAXException { throw error; }
            });
            return builder.parse(new ByteArrayInputStream(raw));
        } catch (ParserConfigurationException | SAXException | IllegalArgumentException error) {
            throw new IOException("PP4_PRIMARY_XML_INVALID_OR_UNSAFE", error);
        }
    }

    /** Reject DTD internals before the DOM parser, while leaving the official external declaration inert. */
    private static void rejectInternalEntities(byte[] raw) throws IOException {
        final String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString(); }
        catch (CharacterCodingException error) { throw new IOException("PP4_PRIMARY_UTF8_REQUIRED", error); }
        require(text.indexOf('\0') < 0, "PP4_PRIMARY_UTF8_REQUIRED");
        for (int i = 0; i < text.length(); i++) {
            String end = text.startsWith("<!--", i) ? "-->" : text.startsWith("<![CDATA[", i) ? "]]>" : text.startsWith("<?", i) ? "?>" : null;
            if (end != null) { int j = text.indexOf(end, i + 2); require(j >= 0, "PP4_PRIMARY_XML_STRUCTURE"); i = j + end.length() - 1; continue; }
            if (text.startsWith("<!DOCTYPE", i)) {
                char quote = 0; boolean closed = false;
                for (int j = i + 9; j < text.length(); j++) {
                    char c = text.charAt(j);
                    if (quote != 0) { if (c == quote) quote = 0; }
                    else if (c == '\'' || c == '"') quote = c;
                    else if (c == '[') throw new IOException("PP4_PRIMARY_INTERNAL_DTD_FORBIDDEN");
                    else if (c == '>') { i = j; closed = true; break; }
                }
                require(closed, "PP4_PRIMARY_XML_STRUCTURE");
            } else if (text.charAt(i) == '&') {
                int j = text.indexOf(';', i + 1);
                require(j > i && j - i <= 32, "PP4_PRIMARY_ENTITY_FORBIDDEN");
                String value = text.substring(i + 1, j);
                require(Set.of("amp", "lt", "gt", "quot", "apos").contains(value) || value.matches("#[0-9]+|#x[0-9a-fA-F]+"), "PP4_PRIMARY_ENTITY_FORBIDDEN");
                i = j;
            }
        }
    }

    private static void verifyDoctype(DocumentType type) throws IOException {
        if (type == null) return;
        String pub = type.getPublicId(), system = type.getSystemId();
        require(type.getInternalSubset() == null || type.getInternalSubset().isBlank(), "PP4_PRIMARY_INTERNAL_DTD_FORBIDDEN");
        require(type.getName().equals("article") && pub != null && pub.startsWith("-//NLM//DTD JATS ") && pub.endsWith("//EN") &&
                system != null && system.matches("(?:https?://jats\\.nlm\\.nih\\.gov/(?:[A-Za-z0-9._-]+/)*)?JATS-archivearticle[0-9]+(?:-[A-Za-z0-9]+)*\\.dtd"), "PP4_PRIMARY_DOCTYPE_UNSUPPORTED");
    }

    private record At(Node node, int depth) { }
    private static Map<String, Element> checkTree(Document document) throws IOException {
        var pending = new ArrayDeque<At>(); pending.push(new At(document, 0));
        var ids = new HashMap<String, Element>(); int elements = 0, tables = 0;
        while (!pending.isEmpty()) {
            At at = pending.pop(); Node node = at.node();
            require(node.getNodeType() != Node.ENTITY_REFERENCE_NODE, "PP4_PRIMARY_ENTITY_FORBIDDEN");
            if (node instanceof Element e) {
                require(at.depth() <= MAX_DEPTH && ++elements <= MAX_ELEMENTS && e.getTagName().length() <= 128, "PP4_PRIMARY_XML_LIMIT");
                require(!"http://www.w3.org/2001/XInclude".equals(e.getNamespaceURI()), "PP4_PRIMARY_XINCLUDE_FORBIDDEN");
                if (name(e).equals("table")) require(++tables <= MAX_TABLES, "PP4_PRIMARY_TABLE_LIMIT");
                boolean ordinary = e.hasAttribute("id"), xml = e.hasAttributeNS(XMLConstants.XML_NS_URI, "id");
                require(!(ordinary && xml), "PP4_PRIMARY_ID_AMBIGUOUS");
                if (ordinary || xml) {
                    String id = ordinary ? e.getAttribute("id") : e.getAttributeNS(XMLConstants.XML_NS_URI, "id");
                    require(nativeId(id) && ids.putIfAbsent(id, e) == null, "PP4_PRIMARY_ID_AMBIGUOUS");
                }
            }
            for (Node child = node.getLastChild(); child != null; child = child.getPreviousSibling()) pending.push(new At(child, at.depth() + 1));
        }
        return ids;
    }

    private static void readRows(List<Element> rows, String section, List<NativeRow> output, int[] totals) throws IOException {
        require(rows.size() <= MAX_ROWS, "PP4_PRIMARY_ROW_LIMIT");
        int[] occupiedUntil = new int[MAX_COLUMNS + 1];
        for (int index = 0; index < rows.size(); index++) {
            Element row = rows.get(index); require(++totals[0] <= MAX_ROWS, "PP4_PRIMARY_ROW_LIMIT");
            var cells = new ArrayList<NativeCell>(); int column = 1;
            for (Element cell : children(row)) {
                require(Set.of("th", "td").contains(name(cell)) && ++totals[1] <= MAX_CELLS, "PP4_PRIMARY_CELL_STRUCTURE_OR_LIMIT");
                int rowSpan = span(cell, "rowspan", MAX_ROWS), colSpan = span(cell, "colspan", MAX_COLUMNS);
                require(index + rowSpan <= rows.size(), "PP4_PRIMARY_ROWSPAN_OUTSIDE_SECTION");
                while (column <= MAX_COLUMNS && occupiedUntil[column] > index) column++;
                require(column + colSpan - 1 <= MAX_COLUMNS, "PP4_PRIMARY_COLUMN_LIMIT");
                for (int c = column; c < column + colSpan; c++) {
                    require(occupiedUntil[c] <= index, "PP4_PRIMARY_SPAN_OVERLAP"); occupiedUntil[c] = index + rowSpan;
                }
                column += colSpan;
                var refs = new ArrayList<CellReference>(); var descendants = cell.getElementsByTagNameNS("*", "*");
                for (int j = 0; j < descendants.getLength(); j++) {
                    Element ref = (Element) descendants.item(j);
                    if (name(ref).equals("xref") || name(ref).equals("sup")) refs.add(new CellReference(
                            name(ref).equals("sup") ? "SUPERSCRIPT" : ref.getAttribute("ref-type"),
                            ref.getAttribute("rid"), ref.getTextContent(), locator(ref)));
                }
                cells.add(new NativeCell(cells.size() + 1, name(cell), rowSpan, colSpan, cell.getTextContent(), locator(cell), attributes(cell), refs));
            }
            output.add(new NativeRow(totals[0], index + 1, section, attributes(row), cells));
        }
    }

    private static int span(Element cell, String field, int maximum) throws IOException {
        if (!cell.hasAttribute(field)) return 1;
        String raw = cell.getAttribute(field).trim();
        require(raw.matches("[0-9]{1,4}"), "PP4_PRIMARY_SPAN_INVALID");
        int value = Integer.parseInt(raw); require(value > 0 && value <= maximum, "PP4_PRIMARY_SPAN_LIMIT"); return value;
    }
    private static List<Element> children(Element parent) throws IOException {
        var out = new ArrayList<Element>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e) out.add(e);
            else require((n.getNodeType() != Node.TEXT_NODE && n.getNodeType() != Node.CDATA_SECTION_NODE) || n.getTextContent().isBlank(), "PP4_PRIMARY_TABLE_OR_METADATA_STRUCTURE");
        }
        return out;
    }
    private static Element onlyChild(Element parent, String tag) throws IOException {
        List<Element> found = children(parent).stream().filter(e -> name(e).equals(tag)).toList();
        require(found.size() == 1, "PP4_PRIMARY_METADATA_AMBIGUOUS"); return found.get(0);
    }
    private static String textChild(Element parent, String tag) throws IOException {
        List<Element> found = children(parent).stream().filter(e -> name(e).equals(tag)).toList();
        require(found.size() <= 1, "PP4_PRIMARY_METADATA_AMBIGUOUS"); return found.isEmpty() ? "" : found.get(0).getTextContent();
    }
    private static Map<String, String> attributes(Element e) {
        var out = new TreeMap<String, String>();
        for (int i = 0; i < e.getAttributes().getLength(); i++) { Node a = e.getAttributes().item(i); out.put(a.getNodeName(), a.getNodeValue()); }
        return immutable(out);
    }
    private static Map<String, String> immutable(Map<String, String> values) { return Collections.unmodifiableMap(new TreeMap<>(values)); }
    private static boolean nativeId(String value) { return value != null && !value.isBlank() && value.length() <= 128 && value.codePoints().noneMatch(Character::isISOControl); }
    private static String name(Element e) { return e.getLocalName() == null ? e.getTagName() : e.getLocalName(); }
    private static String locator(Element e) {
        var parts = new ArrayList<String>();
        for (Node n = e; n instanceof Element current; n = n.getParentNode()) {
            int ordinal = 1;
            for (Node prior = n.getPreviousSibling(); prior != null; prior = prior.getPreviousSibling())
                if (prior instanceof Element && prior.getNodeName().equals(n.getNodeName())) ordinal++;
            parts.add(current.getTagName() + "[" + ordinal + "]");
        }
        Collections.reverse(parts); return "/" + String.join("/", parts);
    }
    private static String sha256(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
    private static void require(boolean valid, String code) throws IOException { if (!valid) throw new IOException(code); }
}
