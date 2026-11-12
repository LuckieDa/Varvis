package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent curated LoF prerequisite for an exact AD disease. Gene-level HI
 * alone never supplies disease scope or a variant's PVS1 result. */
final class Pvs1StartLossClinGenMechanism {
    static final String VERSION = "1.1.0";
    static final String DOSAGE_URL = "https://search.clinicalgenome.org/kb/gene-dosage/download";
    static final String VALIDITY_URL = "https://search.clinicalgenome.org/kb/gene-validity/download";
    static final String DISEASE_DOSAGE_URL = "https://ftp.clinicalgenome.org/ClinGen_gene_curation_list_GRCh38.tsv";
    private static final List<String> DOSAGE_HEADER = List.of("GENE SYMBOL", "HGNC ID", "HAPLOINSUFFICIENCY", "TRIPLOSENSITIVITY", "ONLINE REPORT", "DATE");
    private static final List<String> VALIDITY_HEADER = List.of("GENE SYMBOL", "GENE ID (HGNC)", "DISEASE LABEL", "DISEASE ID (MONDO)", "MOI", "SOP", "CLASSIFICATION", "ONLINE REPORT", "CLASSIFICATION DATE", "GCEP");
    private static final List<String> TSV_HEADER = List.of("Gene Symbol", "Gene ID", "cytoBand", "Genomic Location",
            "Haploinsufficiency Score", "Haploinsufficiency Description", "Haploinsufficiency PMID1", "Haploinsufficiency PMID2",
            "Haploinsufficiency PMID3", "Haploinsufficiency PMID4", "Haploinsufficiency PMID5", "Haploinsufficiency PMID6",
            "Triplosensitivity Score", "Triplosensitivity Description", "Triplosensitivity PMID1", "Triplosensitivity PMID2",
            "Triplosensitivity PMID3", "Triplosensitivity PMID4", "Triplosensitivity PMID5", "Triplosensitivity PMID6",
            "Date Last Evaluated", "Haploinsufficiency Disease ID", "Triplosensitivity Disease ID");

    private Pvs1StartLossClinGenMechanism() { }

    static final class Assessment {
        private final ObjectNode facts;
        private Assessment(ObjectNode facts) { this.facts = facts.deepCopy(); }
        ObjectNode snapshot() { return facts.deepCopy(); }
        boolean qualified() { return "CURATED_MECHANISM_SUPPORTED".equals(facts.path("status").asText()); }
    }
    private record Table(String sha, LocalDate captured, LocalDate created, List<Row> rows) { }
    private record Row(int ordinal, Map<String, String> fields) {
        String value(String key) { return fields.getOrDefault(key, "").strip(); }
    }

    static Assessment assess(ObjectMapper mapper, JsonNode context,
            Pvs1StartLossPublicSources.Snapshot dosage, Pvs1StartLossPublicSources.Snapshot validity,
            Pvs1StartLossPublicSources.Snapshot diseaseDosage) {
        return assess(mapper, context, dosage, validity, diseaseDosage, null);
    }

    static Assessment assess(ObjectMapper mapper, JsonNode context,
            Pvs1StartLossPublicSources.Snapshot dosage, Pvs1StartLossPublicSources.Snapshot validity,
            Pvs1StartLossPublicSources.Snapshot diseaseDosage,
            @org.springframework.lang.Nullable Pvs1KnowledgeCatalog.StartLossAssociation association) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-clingen-mechanism-v1")
                .put("methodVersion", VERSION).put("status", "UNRESOLVED").put("runtimeEligible", false)
                .put("primaryExperimentsReevaluated", false).put("transcriptRelevanceEstablished", false)
                .put("basis", "CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_VALIDITY");
        try {
            String hgnc = context.at("/gene/hgncId").asText(), gene = context.at("/gene/symbol").asText();
            String mondo = context.at("/disease/mondoId").asText();
            require(hgnc.matches("HGNC:[1-9][0-9]*") && gene.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")
                    && mondo.matches("MONDO:[0-9]{7}"), "CONTEXT_INVALID");
            require("AUTOSOMAL_DOMINANT".equals(context.path("moi").asText()), "AD_CONTEXT_REQUIRED");
            require("GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText()), "ASSEMBLY_UNSUPPORTED");
            var adapter = new RuntimeEvidenceV2Adapter(mapper);
            out.put("contextSha256", Pvs1StartLossRuntimeMaterial.contextIdentitySha256(adapter, context));
            Table d = csv(dosage, DOSAGE_URL, "CLINGEN DOSAGE SENSITIVITY CURATIONS", DOSAGE_HEADER);
            Table v = csv(validity, VALIDITY_URL, "CLINGEN GENE DISEASE VALIDITY CURATIONS", VALIDITY_HEADER);
            Table t = tsv(diseaseDosage);
            List<Row> dosageMatches = d.rows().stream().filter(r -> hgnc.equals(r.value("HGNC ID"))).toList();
            require(dosageMatches.size() == 1, "UNIQUE_DOSAGE_GENE_REQUIRED");
            Row dr = dosageMatches.get(0);
            require(gene.equals(dr.value("GENE SYMBOL")), "GENE_IDENTITY_MISMATCH");
            require("Sufficient Evidence for Haploinsufficiency".equals(dr.value("HAPLOINSUFFICIENCY")), "HI3_REQUIRED");
            require(("https://search.clinicalgenome.org/kb/gene-dosage/" + hgnc).equals(dr.value("ONLINE REPORT")), "DOSAGE_REPORT_IDENTITY_INVALID");
            LocalDate dd = OffsetDateTime.parse(dr.value("DATE")).toLocalDate();
            require(!dd.isAfter(d.created()), "DOSAGE_DATE_INVALID");
            List<Row> exactDisease = v.rows().stream().filter(r -> hgnc.equals(r.value("GENE ID (HGNC)"))
                    && mondo.equals(r.value("DISEASE ID (MONDO)"))).toList();
            List<Row> validityMatches = exactDisease.stream().filter(r -> "AD".equals(r.value("MOI"))).toList();
            require(validityMatches.size() <= 1, "UNIQUE_EXACT_DISEASE_VALIDITY_REQUIRED");
            for (var row : validityMatches) require(!List.of("Refuted", "Disputed", "No Known Disease Relationship")
                    .contains(row.value("CLASSIFICATION")), "CONTRARY_VALIDITY_PRESENT");
            Row vr = validityMatches.isEmpty() ? null : validityMatches.get(0);
            if (vr != null) {
                require(gene.equals(vr.value("GENE SYMBOL")) && !vr.value("DISEASE LABEL").isBlank(), "GENE_IDENTITY_MISMATCH");
                require(vr.value("ONLINE REPORT").matches("https://search\\.clinicalgenome\\.org/kb/gene-validity/CGGV:assertion_[A-Za-z0-9_.:-]+")
                        && vr.value("SOP").matches("SOP[1-9][0-9]*") && !vr.value("GCEP").isBlank(), "VALIDITY_REPORT_IDENTITY_INVALID");
                require(!OffsetDateTime.parse(vr.value("CLASSIFICATION DATE")).toLocalDate().isAfter(v.created()), "VALIDITY_DATE_INVALID");
            }
            boolean clinGenAssociation = vr != null && List.of("Strong", "Definitive").contains(vr.value("CLASSIFICATION"));
            if (!clinGenAssociation) {
                if (!(association != null && association.qualified())) {
                    throw new IllegalArgumentException("PVS1_CLINGEN_MECHANISM_" + "STRONG_ASSOCIATION_REQUIRED");
                }
                require(exactDisease.isEmpty() || vr != null && List.of("Limited", "Moderate").contains(vr.value("CLASSIFICATION")), "VALIDITY_SCOPE_CONFLICT");
                var anchor = association.snapshot();
                require(out.path("contextSha256").equals(anchor.path("contextSha256")), "ASSOCIATION_CONTEXT_MISMATCH");
                out.set("associationAnchor", anchor);
                out.put("basis", "CLINGEN_SAME_DISEASE_HAPLOINSUFFICIENCY_AND_G2P_ASSOCIATION");
                var scan = out.putObject("clinGenValidityCheck").put("status", exactDisease.isEmpty() ? "NO_EXACT_DISEASE_ASSOCIATION" : "INSUFFICIENT_ASSOCIATION_STRENGTH")
                        .put("sourceEnumerationComplete", true).put("sourceUrl", VALIDITY_URL).put("sha256", v.sha())
                        .put("capturedDate", v.captured().toString()).put("fileCreatedDate", v.created().toString());
                var checkedRows = scan.putArray("records"); for (var row : exactDisease) source(checkedRows.addObject(), VALIDITY_URL, v, row);
            }
            List<Row> scoped = t.rows().stream().filter(r -> gene.equals(r.value("Gene Symbol"))
                    && mondo.equals(r.value("Haploinsufficiency Disease ID"))).toList();
            require(scoped.size() == 1, "UNIQUE_EXACT_DISEASE_HI_REQUIRED");
            Row tr = scoped.get(0);
            require("3".equals(tr.value("Haploinsufficiency Score"))
                    && "Sufficient evidence for dosage pathogenicity".equals(tr.value("Haploinsufficiency Description")), "HI3_REQUIRED");
            require(tr.value("Gene ID").matches("[1-9][0-9]*") && tr.value("Genomic Location").matches("chr(?:[1-9]|1[0-9]|2[0-2]|X|Y):[1-9][0-9]*-[1-9][0-9]*"), "DOSAGE_GENOMIC_IDENTITY_INVALID");
            LocalDate td = LocalDate.parse(tr.value("Date Last Evaluated"));
            require(td.equals(dd) && !td.isAfter(t.created()), "DOSAGE_SNAPSHOTS_DISAGREE");
            var publications = out.putArray("publications");
            for (int i = 1; i <= 6; i++) {
                String pmid = tr.value("Haploinsufficiency PMID" + i);
                require(pmid.isEmpty() || pmid.matches("[1-9][0-9]*"), "DOSAGE_PUBLICATION_INVALID");
                if (!pmid.isEmpty()) publications.add(pmid);
            }
            require(!publications.isEmpty(), "DOSAGE_PUBLICATIONS_MISSING");
            var binding = out.putObject("binding").put("sourceDataClass", "PUBLIC_REFERENCE")
                    .put("hgncId", hgnc).put("geneSymbol", gene).put("mondoId", mondo).put("moi", "AUTOSOMAL_DOMINANT");
            var sources = binding.putArray("sources");
            source(sources.addObject(), DOSAGE_URL, d, dr);
            if (clinGenAssociation) source(sources.addObject(), VALIDITY_URL, v, java.util.Objects.requireNonNull(vr));
            source(sources.addObject(), DISEASE_DOSAGE_URL, t, tr);
            out.put("status", "CURATED_MECHANISM_SUPPORTED").put("reasonCode", "PVS1_START_LOSS_CLINGEN_DISEASE_HAPLOINSUFFICIENCY");
            if (clinGenAssociation) out.put("diseaseLabel", java.util.Objects.requireNonNull(vr).value("DISEASE LABEL"))
                    .put("validityClassification", vr.value("CLASSIFICATION"));
            else out.put("diseaseLabel", out.at("/associationAnchor/records/0/sourceRecord/disease name").asText())
                    .put("associationSource", "G2P").put("validityClassification", "NOT_USED_G2P_ASSOCIATION_ANCHOR");
            out.put("hiScore", 3).put("evaluationDate", td.toString()).put("geneId", tr.value("Gene ID"));
        } catch (RuntimeException invalid) {
            String message = invalid.getMessage();
            out.put("reasonCode", message != null && message.matches("PVS1_CLINGEN_MECHANISM_[A-Z0-9_]+")
                    ? message : "PVS1_CLINGEN_MECHANISM_SOURCE_INVALID");
        }
        return new Assessment(out);
    }
    private static void source(ObjectNode out, String url, Table table, Row row) {
        out.put("sourceUrl", url).put("sha256", table.sha()).put("sourceRow", row.ordinal())
                .put("capturedDate", table.captured().toString()).put("fileCreatedDate", table.created().toString());
        var fields = out.putObject("sourceRecord"); row.fields().forEach(fields::put);
    }
    private static String original(Pvs1StartLossPublicSources.Snapshot source, String url) {
        var receipt = source.receipt(); var body = source.document().path("rawCsv");
        require("AVAILABLE".equals(receipt.path("status").asText()) && url.equals(receipt.path("sourceUrl").asText())
                && body.isTextual(), "ORIGINAL_UNAVAILABLE");
        String text = body.asText(); byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        require(raw.length > 0 && raw.length <= 2_000_000 && receipt.path("sha256").asText().matches("[a-f0-9]{64}")
                && receipt.path("sha256").asText().equals(CodingTranscriptMaterialSupply.sha(raw)), "ORIGINAL_PIN_MISMATCH");
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }
    private static LocalDate captured(Pvs1StartLossPublicSources.Snapshot source) {
        return Instant.parse(source.receipt().path("capturedAt").asText()).atOffset(java.time.ZoneOffset.UTC).toLocalDate();
    }
    private static Table csv(Pvs1StartLossPublicSources.Snapshot source, String url, String title, List<String> header) {
        String raw = original(source, url); List<List<String>> records = records(raw);
        require(records.size() >= 7 && records.get(0).size() == header.size() && title.equals(records.get(0).get(0))
                && records.get(4).equals(header), "CSV_SCHEMA_INVALID");
        String createdText = records.get(1).get(0);
        require(createdText.matches("FILE CREATED: [0-9]{4}-[0-9]{2}-[0-9]{2}"), "FILE_DATE_INVALID");
        LocalDate captured = captured(source), created = LocalDate.parse(createdText.substring(14));
        require(!created.isAfter(captured), "FILE_DATE_INVALID");
        var rows = new ArrayList<Row>();
        for (int i = 6; i < records.size(); i++) {
            var record = records.get(i); require(record.size() == header.size(), "CSV_ROW_INVALID");
            rows.add(new Row(i + 1, fields(header, record)));
        }
        return new Table(source.receipt().path("sha256").asText(), captured, created, rows);
    }
    private static Table tsv(Pvs1StartLossPublicSources.Snapshot source) {
        String raw = original(source, DISEASE_DOSAGE_URL); String[] lines = raw.split("\\r?\\n", -1);
        require(lines.length > 7 && lines.length <= 10001 && "#ClinGen Gene Curation Results".equals(lines[0])
                && lines[2].startsWith("#Genomic Locations are reported on GRCh38 (hg38):")
                && lines[5].equals("#" + String.join("\t", TSV_HEADER)), "TSV_SCHEMA_INVALID");
        LocalDate captured = captured(source);
        LocalDate created = LocalDate.parse(lines[1].substring(1), java.time.format.DateTimeFormatter.ofPattern("dd MMM,uuuu", java.util.Locale.ENGLISH));
        require(!created.isAfter(captured), "FILE_DATE_INVALID");
        var rows = new ArrayList<Row>();
        for (int i = 6; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) continue;
            var record = Arrays.asList(lines[i].split("\t", -1)); require(record.size() == TSV_HEADER.size(), "TSV_ROW_INVALID");
            rows.add(new Row(i + 1, fields(TSV_HEADER, record)));
        }
        return new Table(source.receipt().path("sha256").asText(), captured, created, rows);
    }
    private static Map<String, String> fields(List<String> header, List<String> row) {
        var fields = new LinkedHashMap<String, String>();
        for (int i = 0; i < header.size(); i++) fields.put(header.get(i), row.get(i)); return fields;
    }
    /** RFC4180 quoting, including multiline/escaped cells; malformed tails fail
     * the source instead of silently changing its rows or field alignment. */
    private static List<List<String>> records(String text) {
        var rows = new ArrayList<List<String>>(); var row = new ArrayList<String>(); var cell = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else cell.append(c);
            } else if (c == '"') { require(cell.isEmpty() && !closed, "CSV_QUOTING_INVALID"); quoted = true; }
            else if (c == ',' || c == '\n' || c == '\r') {
                row.add(cell.toString()); cell.setLength(0); closed = false;
                require(row.size() <= 32, "CSV_COLUMN_LIMIT");
                if (c != ',') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    rows.add(row); row = new ArrayList<>(); require(rows.size() <= 10000, "CSV_ROW_LIMIT");
                }
            } else { require(!closed, "CSV_QUOTING_INVALID"); cell.append(c); }
            require(cell.length() <= 32768, "CSV_CELL_LIMIT");
        }
        require(!quoted, "CSV_QUOTING_INVALID");
        if (!row.isEmpty() || !cell.isEmpty() || closed) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException("PVS1_CLINGEN_MECHANISM_" + code);
    }
}
