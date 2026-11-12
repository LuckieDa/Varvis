package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Frozen release scopes and Table 4 weights for direct genomic PTCs. */
final class PublishedPm5PtcTable {
    static final String VERSION = "published-pm5-ptc-table-v1";
    private static final String RESOURCE = "/acmg/published-pm5-ptc-table-v1.json";
    private static final String RESOURCE_SHA = "ea98f3d80cbfcdc0cf605f97d12213a1ae44eec39554be8a80b7bb5652a100b8";
    private static final Set<String> STRENGTHS = Set.of("NONE", "SUPPORTING", "MODERATE", "STRONG");
    private static final Set<String> DIRECT_TERMS = Set.of("stop_gained", "frameshift_variant", "NMD_transcript_variant");

    record Row(String id, int exon, String exonLabel, int cdsStart, int cdsEnd,
               int firstStopPosition, int lastStopPosition, boolean pvs1Applicable, String pm5Strength) {
        boolean contains(int stopPosition) {
            return stopPosition >= firstStopPosition && stopPosition <= lastStopPosition;
        }
    }

    record Scope(String specification, String hgncId, String gene, String disease, String inheritance,
                 String transcript, String maneTranscript, int nativeStopPosition, String refseqCdsSha,
                 String specificationUrl, String releaseSha, String pm5Pointer, String pvs1Pointer,
                 String tableUrl, String tableSha, String tableSheet, List<Row> rows) {
        boolean matchesContext(JsonNode context) {
            return hgncId.equals(context.at("/gene/hgncId").asText())
                    && gene.equals(context.at("/gene/symbol").asText())
                    && disease.equals(context.at("/disease/mondoId").asText())
                    && inheritance.equals(context.path("moi").asText())
                    && "GERMLINE".equals(context.at("/scope/analysisDomain").asText());
        }

        boolean matchesTranscript(@Nullable String selected) {
            return transcript.equals(selected) || maneTranscript.equals(selected);
        }

        @Nullable Row stopRow(int exon, int stopPosition) {
            if (stopPosition < 1 || stopPosition >= nativeStopPosition) return null;
            return rows.stream().filter(r -> r.exon() == exon && r.contains(stopPosition)).findFirst().orElse(null);
        }
    }

    private record Catalog(List<Scope> scopes) { }

    private static final class Holder {
        static final Catalog CATALOG = load();
    }

    private PublishedPm5PtcTable() { }

    @Nullable static Scope select(@Nullable JsonNode context) {
        if (context == null) return null;
        return Holder.CATALOG.scopes().stream().filter(scope -> scope.matchesContext(context)).findFirst().orElse(null);
    }

    static boolean directAnnotation(Set<String> terms) {
        return DIRECT_TERMS.containsAll(terms) && (terms.contains("stop_gained") || terms.contains("frameshift_variant"));
    }

    static String identity() { return RESOURCE_SHA; }

    private static Catalog load() {
        try (var in = PublishedPm5PtcTable.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalArgumentException("PM5_PTC_RULE_TABLE_UNAVAILABLE");
            byte[] raw = in.readNBytes(262_145);
            String canonical = new String(raw, StandardCharsets.UTF_8).replace("\r\n", "\n");
            check(raw.length <= 262_144 && RESOURCE_SHA.equals(CodingTranscriptMaterialSupply.sha(canonical)));
            var mapper = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            var catalog = mapper.readTree(canonical);
            check(VERSION.equals(catalog.path("schemaVersion").asText())
                    && "STOP_CODON_EXON".equals(catalog.path("weightBasis").asText())
                    && "GRCh38".equals(catalog.path("assembly").asText()));
            var scopes = new ArrayList<Scope>();
            var keys = new HashSet<List<String>>();
            for (var value : catalog.path("scopes")) {
                var scope = readScope(catalog, value);
                check(keys.add(List.of(scope.hgncId(), scope.disease(), scope.inheritance())));
                scopes.add(scope);
            }
            check(!scopes.isEmpty());
            return new Catalog(List.copyOf(scopes));
        } catch (IOException failure) {
            throw new IllegalArgumentException("PM5_PTC_RULE_TABLE_UNAVAILABLE", failure);
        }
    }

    private static Scope readScope(JsonNode catalog, JsonNode value) {
        int nativeStop = value.path("referenceStopProteinPosition").asInt();
        check(nativeStop > 1 && "GERMLINE".equals(value.path("origin").asText()));
        var rows = new ArrayList<Row>();
        for (var raw : value.path("rows")) {
            var span = raw.path("referenceExonSenseCdsSpanInclusive");
            int exon = raw.path("stopExonOrdinal").asInt();
            int start = span.path(0).asInt();
            int end = span.path(1).asInt();
            check(exon > 1 && start >= 1 && end >= start && end <= (nativeStop - 1) * 3);
            var condition = raw.path("stopProteinPositionCondition");
            int first = 1;
            int last = nativeStop - 1;
            if (!condition.isNull()) {
                int boundary = condition.path("position").asInt();
                check(boundary > 1 && boundary < nativeStop);
                switch (condition.path("operator").asText()) {
                    case "LT" -> last = boundary - 1;
                    case "GT" -> first = boundary + 1;
                    default -> throw new IllegalArgumentException("PM5_PTC_RULE_TABLE_INVALID");
                }
            }
            String pvs1 = raw.path("sourcePvs1Label").asText();
            String strength = raw.path("pm5Strength").asText("NONE");
            check(Set.of("PVS1", "PVS1_N/A").contains(pvs1) && STRENGTHS.contains(strength)
                    && ("PVS1".equals(pvs1) || "NONE".equals(strength)));
            var row = new Row(raw.path("id").asText(), exon, raw.path("sourceExonLabel").asText(), start, end,
                    first, last, "PVS1".equals(pvs1), strength);
            check(rows.stream().noneMatch(prior -> prior.exon() == exon
                    && prior.firstStopPosition() <= row.lastStopPosition()
                    && row.firstStopPosition() <= prior.lastStopPosition()));
            rows.add(row);
        }
        check(!rows.isEmpty());
        return new Scope(value.path("specification").asText(), value.path("hgnc").asText(), value.path("gene").asText(),
                value.path("disease").asText(), value.path("inheritance").asText(), value.path("transcript").asText(),
                value.path("maneTranscript").asText(), nativeStop, value.path("refseqCdsSha256").asText(),
                value.path("specificationUrl").asText(),
                value.path("releaseSha256").asText(), value.path("pm5Pointer").asText(), value.path("pvs1Pointer").asText(),
                value.path("tableUrl").asText(), catalog.path("tableSha256").asText(), catalog.path("tableSheet").asText(),
                List.copyOf(rows));
    }

    private static void check(boolean ok) {
        if (!ok) throw new IllegalArgumentException("PM5_PTC_RULE_TABLE_INVALID");
    }
}
