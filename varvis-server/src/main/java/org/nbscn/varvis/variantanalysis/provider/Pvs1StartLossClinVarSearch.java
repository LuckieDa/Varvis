package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.tribble.readers.TabixReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;

/** Complete traversal of bounded coding intervals in the configured local VCF.
 * Enumerates indels/symbolic records too; classification/independence is NOT inferred.
 * An exhausted interval is local snapshot coverage, never a literature-negative claim. */
public final class Pvs1StartLossClinVarSearch {
    record Interval(int start, int end) { }
    interface Reader { List<String> read(Path file, List<String> regions) throws Exception; }
    private final ObjectMapper mapper;
    private final VariantAnalysisProperties properties;
    private final Reader reader;
    public Pvs1StartLossClinVarSearch(ObjectMapper mapper, VariantAnalysisProperties properties) {
        this(mapper, properties, Pvs1StartLossClinVarSearch::read);
    }
    Pvs1StartLossClinVarSearch(ObjectMapper mapper, VariantAnalysisProperties properties, Reader reader) {
        this.mapper = mapper; this.properties = properties; this.reader = reader;
    }
    static List<Interval> intervals(JsonNode model, int restart) {
        if (restart <= 3 || (restart - 1) % 3 != 0 || restart > model.path("cdsLength").asInt()
                || !model.path("exons").isArray() || model.path("exons").size() > 1000
                || !model.path("strand").isIntegralNumber() || Math.abs(model.path("strand").asInt()) != 1)
            throw new IllegalArgumentException("CLINVAR_UPSTREAM_INTERVAL_INVALID");
        var out = new ArrayList<Interval>(); int next = 1;
        for (var exon : model.path("exons")) {
            for (String field : List.of("cdsStart", "cdsEnd", "genomicStart", "genomicEnd"))
                if (!exon.path(field).isIntegralNumber() || !exon.path(field).canConvertToInt())
                    throw new IllegalArgumentException("CLINVAR_UPSTREAM_INTERVAL_INVALID");
            int lo = Math.max(1, exon.path("cdsStart").asInt()), hi = Math.min(restart - 1, exon.path("cdsEnd").asInt());
            if (lo > hi) continue;
            long a = model.path("strand").asInt() == 1 ? (long) exon.path("genomicStart").asInt() + lo - exon.path("cdsStart").asInt()
                    : (long) exon.path("genomicEnd").asInt() - hi + exon.path("cdsStart").asInt();
            long b = a + hi - lo;
            if (lo != next || a < 1 || b > Integer.MAX_VALUE || a < exon.path("genomicStart").asInt() || b > exon.path("genomicEnd").asInt())
                throw new IllegalArgumentException("CLINVAR_UPSTREAM_INTERVAL_INVALID");
            out.add(new Interval((int) a, (int) b)); next = hi + 1;
        }
        if (next != restart || restart > 100_000) throw new IllegalArgumentException("CLINVAR_UPSTREAM_INTERVAL_LIMIT_OR_GAP");
        return List.copyOf(out);
    }
    public ObjectNode query(JsonNode context, JsonNode model, int restart) {
        var out = mapper.createObjectNode().put("status", "UNAVAILABLE").put("complete", false)
                .put("negativeCoverageGranted", false).put("scope", "LOCAL_CLINVAR_CODING_INTERVALS_ALL_ALLELE_TYPES");
        try {
            var ranges = intervals(model, restart);
            String contig = context.at("/variant/evidenceQueryAllele/contig").asText();
            if (!"GRCh38".equals(context.at("/variant/evidenceQueryAllele/assembly").asText())
                    || !model.path("contig").asText().equals(contig) || !contig.matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y)"))
                throw new IllegalArgumentException("CLINVAR_UPSTREAM_CONTEXT_INVALID");
            var queries = out.putArray("regions"); var regions = new ArrayList<String>();
            for (var r : ranges) { String value = contig + ":" + r.start() + "-" + r.end(); regions.add(value); queries.add(value); }
            if (!properties.getClinVar().isEnabled()) return out.put("reasonCode", "CLINVAR_UPSTREAM_PROVIDER_DISABLED");
            Path file = properties.getClinVar().getGrch38File();
            if (file == null || !Files.isRegularFile(file) || !Files.isRegularFile(Path.of(file + ".tbi")))
                return out.put("reasonCode", "CLINVAR_UPSTREAM_RESOURCE_UNAVAILABLE");
            var before = Files.readAttributes(file, BasicFileAttributes.class);
            var indexBefore = Files.readAttributes(Path.of(file + ".tbi"), BasicFileAttributes.class);
            if (Thread.currentThread().isInterrupted()) return out.put("reasonCode", "CLINVAR_UPSTREAM_INTERRUPTED");
            var lines = reader.read(file, regions);
            if (lines == null || lines.size() > 10000) throw new IllegalArgumentException("CLINVAR_UPSTREAM_RECORD_LIMIT");
            var records = out.putArray("records"); var unique = new LinkedHashSet<String>(); long bytes = 0;
            for (var line : lines) {
                bytes += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                if (bytes > 8_000_000 || Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("CLINVAR_UPSTREAM_LIMIT_OR_INTERRUPTED");
                if (!unique.add(line)) continue;
                String[] f = line.split("\t", -1);
                if (f.length < 8 || !f[0].replaceFirst("^chr", "").equals(contig) || !f[1].matches("[1-9][0-9]{0,9}"))
                    throw new IllegalArgumentException("CLINVAR_UPSTREAM_RECORD_INVALID");
                long position = Long.parseLong(f[1]), end = position + f[3].length() - 1L;
                if (position > Integer.MAX_VALUE || end > Integer.MAX_VALUE) throw new IllegalArgumentException("CLINVAR_UPSTREAM_RECORD_INVALID");
                for (String item : f[7].split(";")) if (item.startsWith("END=")) end = Long.parseLong(item.substring(4));
                if (end < position || end > Integer.MAX_VALUE) throw new IllegalArgumentException("CLINVAR_UPSTREAM_RECORD_INVALID");
                boolean overlaps = false;
                for (var range : ranges) overlaps |= position <= range.end() && end >= range.start();
                if (!overlaps) continue;
                records.addObject().put("variationId", f[2]).put("position", position).put("reference", f[3]).put("alternate", f[4])
                        .put("rawVcfLine", line).put("disposition", "REQUIRES_REFERENCE_QUALIFICATION")
                        .put("reasonCode", "CLINVAR_UPSTREAM_ASSERTION_FACTS_REQUIRED");
            }
            var after = Files.readAttributes(file, BasicFileAttributes.class);
            var indexAfter = Files.readAttributes(Path.of(file + ".tbi"), BasicFileAttributes.class);
            if (!same(before, after) || !same(indexBefore, indexAfter)) throw new IllegalArgumentException("CLINVAR_UPSTREAM_RESOURCE_CHANGED");
            out.putObject("resource").put("version", properties.getClinVar().versionFor("GRCh38"))
                    .put("vcfBytes", before.size()).put("vcfModified", before.lastModifiedTime().toString())
                    .put("indexBytes", indexBefore.size()).put("indexModified", indexBefore.lastModifiedTime().toString());
            return out.put("status", "ENUMERATED").put("complete", true).put("reasonCode", "CLINVAR_UPSTREAM_ENUMERATED_QUALIFICATION_REQUIRED");
        } catch (Exception error) {
            String message = error.getMessage();
            return out.put("reasonCode", message != null && message.matches("CLINVAR_UPSTREAM_[A-Z_]+") ? message : "CLINVAR_UPSTREAM_QUERY_FAILED");
        }
    }
    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) {
        return a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime()) && java.util.Objects.equals(a.fileKey(), b.fileKey());
    }
    private static List<String> read(Path file, List<String> regions) throws Exception {
        var out = new ArrayList<String>(); long bytes = 0;
        try (var reader = new TabixReader(file.toAbsolutePath().normalize().toString())) {
            for (var region : regions) {
                String chromosome = region.substring(0, region.indexOf(':'));
                String nativeRegion = reader.getChromosomes().contains(chromosome) ? region
                        : reader.getChromosomes().contains("chr" + chromosome) ? "chr" + region : null;
                if (nativeRegion == null) throw new IllegalArgumentException("CLINVAR_UPSTREAM_CONTIG_UNAVAILABLE");
                var iterator = reader.query(nativeRegion); String line;
                while (iterator != null && (line = iterator.next()) != null) {
                    bytes += line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if (Thread.currentThread().isInterrupted() || out.size() >= 10000 || bytes > 8_000_000)
                        throw new IllegalArgumentException("CLINVAR_UPSTREAM_LIMIT_OR_INTERRUPTED");
                    out.add(line);
                }
            }
        }
        return out;
    }
}
