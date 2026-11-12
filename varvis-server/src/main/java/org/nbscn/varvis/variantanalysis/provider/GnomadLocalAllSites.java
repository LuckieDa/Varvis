package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import htsjdk.samtools.util.BlockCompressedInputStream;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Offline sparse index over the unmodified official BGZF TSV. No Hail or subprocess is used. */
public final class GnomadLocalAllSites {
    public static final String VERSION = "gnomad-local-all-sites-v1";
    static final int STRIDE = 4096, MAGIC = 0x414e3031;
    static final String HEADER = "locus\tAN\toutside_broad_capture_region\toutside_ukb_capture_region\toutside_broad_calling_region\toutside_ukb_calling_region";
    public static final List<String> FLAGS = List.of("outside_broad_capture_region", "outside_ukb_capture_region",
            "outside_broad_calling_region", "outside_ukb_calling_region");
    private final ObjectMapper mapper;
    private final Path data;
    private final ObjectNode manifest;
    private final String manifestSha;
    private final Map<Integer, List<Entry>> entries = new HashMap<>();
    private final Map<String, ObjectNode> cache = new HashMap<>();
    private int queries;
    private record Entry(int position, long offset) {}
    record Row(int contig, int position, long an, List<Boolean> flags) {}

    public GnomadLocalAllSites(ObjectMapper mapper, Path manifestPath, String expectedSha) {
        this.mapper = Objects.requireNonNull(mapper);
        try {
            if (!manifestPath.isAbsolute() || Files.size(manifestPath) > 65536) throw new IOException("manifest path/size");
            byte[] bytes = Files.readAllBytes(manifestPath);
            manifestSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!manifestSha.equals(expectedSha)) throw new IOException("manifest checksum");
            JsonNode parsed = mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes);
            if (!(parsed instanceof ObjectNode m) || !VERSION.equals(m.path("schemaVersion").asText())
                    || !"GRCh38".equals(m.path("assembly").asText()) || !"4.1".equals(m.path("release").asText())
                    || !"EXOMES".equals(m.path("dataset").asText()) || !"ALL_RELEASE_SAMPLES_ADJ".equals(m.path("stratum").asText())
                    || m.path("stride").asInt() != STRIDE) throw new IOException("manifest contract");
            manifest = m;
            data = companion(manifestPath, m.path("dataFile").asText());
            Path index = companion(manifestPath, m.path("indexFile").asText());
            checkData();
            long size = Files.size(index);
            if (size < 24 || size > 16_777_216 || (size - 8) % 16 != 0 || !sha(index).equals(m.path("indexSha256").asText()))
                throw new IOException("index integrity");
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(index)))) {
                if (input.readInt() != MAGIC || input.readInt() != STRIDE) throw new IOException("index format");
                int priorContig = 0, priorPosition = 0; long priorOffset = -1;
                for (long i = 8; i < size; i += 16) {
                    int contig = input.readInt(), position = input.readInt(); long offset = input.readLong();
                    if (contig < 1 || contig > 25 || position < 1 || contig < priorContig
                            || (contig == priorContig && position <= priorPosition) || offset <= priorOffset
                            || (offset >>> 16) >= Files.size(data)) throw new IOException("index ordering/range");
                    entries.computeIfAbsent(contig, ignored -> new ArrayList<>()).add(new Entry(position, offset));
                    priorContig = contig; priorPosition = position; priorOffset = offset;
                }
            }
        } catch (Exception invalid) { throw new IllegalArgumentException("PM2_ALL_SITES_RESOURCE_INVALID", invalid); }
    }

    public int queries() { return queries; }
    public ObjectNode binding() {
        return mapper.createObjectNode().put("schemaVersion", VERSION).put("manifestSha256", manifestSha)
                .put("dataSha256", manifest.path("dataSha256").asText()).put("dataset", "EXOMES")
                .put("release", "4.1").put("assembly", "GRCh38").put("stratum", "ALL_RELEASE_SAMPLES_ADJ")
                .put("ancestryStratified", false);
    }

    public ObjectNode query(JsonNode allele) {
        var result = binding(); result.set("queryAllele", allele.deepCopy());
        String contigText = allele.path("contig").asText(); long position = allele.path("position").asLong(-1);
        if (!"GRCh38".equals(allele.path("assembly").asText()) || !contigText.matches("[1-9]|1[0-9]|2[0-2]")
                || position < 1 || position > Integer.MAX_VALUE)
            return result.put("status", "NOT_APPLICABLE").put("reasonCode", "PM2_LOCAL_VARIANT_SCOPE_UNSUPPORTED");
        try {
            checkData();
            String key = contigText + ":" + position;
            if (cache.containsKey(key)) {
                var cached = cache.get(key).deepCopy(); cached.set("queryAllele", allele.deepCopy());
                return cached.put("observationUse", "REQUEST_LOCUS_CACHE");
            }
            queries++;
            int contig = Integer.parseInt(contigText); var list = entries.get(contig);
            result.put("status", "NO_RECORD").put("observationUse", "NEW_LOCAL_READ");
            if (list != null) {
                int lo = 0, hi = list.size() - 1;
                while (lo < hi) { int mid = (lo + hi + 1) >>> 1; if (list.get(mid).position() <= position) lo = mid; else hi = mid - 1; }
                try (var input = new BlockCompressedInputStream(data.toFile())) {
                    input.setCheckCrcs(true); input.seek(list.get(lo).offset());
                    int scanned = 0; String line;
                    while ((line = input.readLine()) != null) {
                        if (++scanned > STRIDE + 1) throw new IOException("sparse query limit");
                        Row row = parse(line);
                        if (row.contig() != contig || row.position() > position) break;
                        if (row.position() == position) {
                            result.put("status", "HIT").put("adjAn", Long.toString(row.an())).put("rawRecord", line);
                            var flags = result.putObject("regionFlags");
                            for (int i = 0; i < FLAGS.size(); i++) flags.put(FLAGS.get(i), row.flags().get(i));
                            break;
                        }
                    }
                    result.put("rowsScanned", scanned);
                }
            }
            checkData(); cache.put(key, result.deepCopy()); return result;
        } catch (Exception invalid) { return result.put("status", "UNAVAILABLE").put("reasonCode", "PM2_ALL_SITES_RESOURCE_CHANGED_OR_UNREADABLE"); }
    }

    private void checkData() throws IOException {
        if (Files.isSymbolicLink(data) || !Files.isRegularFile(data)
                || Files.size(data) != manifest.path("dataBytes").asLong(-1)
                || !Files.getLastModifiedTime(data).toString().equals(manifest.path("dataMtime").asText()))
            throw new IOException("data identity changed");
    }
    private static Path companion(Path manifest, String file) throws IOException {
        if (file.isBlank() || file.contains("/") || file.contains("\\") || file.contains(":") || file.equals("..")) throw new IOException("companion name");
        return manifest.getParent().resolve(file);
    }
    static Row parse(String line) throws IOException {
        if (line.length() > 1024) throw new IOException("row length");
        String[] fields = line.split("\t", -1), locus = fields[0].split(":", -1);
        if (fields.length != 6 || locus.length != 2 || !locus[0].matches("chr([1-9]|1[0-9]|2[0-2]|X|Y|M)")) throw new IOException("row shape");
        try {
            int contig = "chrX".equals(locus[0]) ? 23 : "chrY".equals(locus[0]) ? 24
                    : "chrM".equals(locus[0]) ? 25 : Integer.parseInt(locus[0].substring(3));
            int position = Integer.parseInt(locus[1]); long an = Long.parseLong(fields[1]);
            if (position < 1 || an < 0 || an > 1_461_894) throw new IOException("numeric range");
            List<Boolean> flags = new ArrayList<>();
            for (int i = 2; i < 6; i++) { if (!Set.of("true", "false").contains(fields[i])) throw new IOException("flag missing"); flags.add(Boolean.valueOf(fields[i])); }
            return new Row(contig, position, an, flags);
        } catch (NumberFormatException invalid) { throw new IOException("invalid number", invalid); }
    }

    /** One-time streaming preparation; verifies all BGZF CRCs and ordering without unpacking a second full table. */
    public static ObjectNode build(ObjectMapper mapper, Path data, Path index, Path manifestPath, String expectedDataSha) throws Exception {
        if (Files.exists(index) || Files.exists(manifestPath)) throw new IOException("do not overwrite prepared resources");
        String beforeTime = Files.getLastModifiedTime(data).toString(); long size = Files.size(data);
        String actualSha = sha(data);
        if (!actualSha.equals(expectedDataSha)) throw new IOException("source SHA mismatch");
        long rows = 0, checkpoints = 0; int priorContig = 0, priorPosition = 0;
        try (var input = new BlockCompressedInputStream(data.toFile());
             var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(index, StandardOpenOption.CREATE_NEW)))) {
            input.setCheckCrcs(true);
            if (!HEADER.equals(input.readLine())) throw new IOException("official TSV header mismatch");
            out.writeInt(MAGIC); out.writeInt(STRIDE);
            for (;;) {
                long offset = input.getFilePointer(); String line = input.readLine(); if (line == null) break;
                Row row = parse(line);
                if (row.contig() < priorContig || (row.contig() == priorContig && row.position() <= priorPosition)) throw new IOException("source not uniquely sorted");
                if (rows % STRIDE == 0 || row.contig() != priorContig) {
                    out.writeInt(row.contig()); out.writeInt(row.position()); out.writeLong(offset); checkpoints++;
                }
                priorContig = row.contig(); priorPosition = row.position(); rows++;
            }
        }
        if (rows == 0 || size != Files.size(data) || !beforeTime.equals(Files.getLastModifiedTime(data).toString())) throw new IOException("source changed/empty");
        var result = mapper.createObjectNode().put("schemaVersion", VERSION).put("assembly", "GRCh38").put("release", "4.1")
                .put("dataset", "EXOMES").put("stratum", "ALL_RELEASE_SAMPLES_ADJ").put("stride", STRIDE)
                .put("dataFile", data.getFileName().toString()).put("dataBytes", size).put("dataMtime", beforeTime).put("dataSha256", actualSha)
                .put("indexFile", index.getFileName().toString()).put("indexSha256", sha(index)).put("rows", rows)
                .put("checkpoints", checkpoints).put("crcAndOrderingVerified", true);
        Files.write(manifestPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result), StandardOpenOption.CREATE_NEW);
        return result;
    }
    public static String sha(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) { byte[] buffer = new byte[1 << 20]; int n; while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n); }
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("data.bgz index.bin manifest.json expectedDataSha256");
        System.out.println(build(new ObjectMapper(), Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), args[3]).toPrettyString());
    }
}
