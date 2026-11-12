package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.lang.Nullable;
import static org.nbscn.varvis.variantanalysis.provider.UniProtProteinRegionSupply.*;

/** Request-scoped local-first acquisition. Cache contains public protein records, never patient variants or approvals. */
public final class ProteinRegionDemandSupply {
    public static final String VERSION = "1.0.0";
    private static final String ROOT_URL = "https://www.ebi.ac.uk/interpro/api/";
    private static final Semaphore NETWORK = new Semaphore(1, true);
    private static final AtomicLong COOLDOWN_UNTIL = new AtomicLong();
    public record Settings(Path cache, String uniprotRelease, String interproRelease, String interproMinor,
            boolean networkAllowed, int maximumProteins, int maximumRequests, int totalSeconds, int requestSeconds, int maximumBytes) {
        public Settings {
            require(uniprotRelease.matches("[0-9]{4}_[0-9]{2}") && interproRelease.matches("[0-9]{1,4}\\.[0-9]{1,3}")
                    && interproMinor.matches("[0-9]{1,3}"), "PROTEIN_REGION_DEMAND_RELEASE_REQUIRED");
            require(maximumProteins > 0 && maximumProteins <= 50 && maximumRequests > 0 && maximumRequests <= 100
                    && totalSeconds > 0 && totalSeconds <= 240 && requestSeconds > 0 && requestSeconds <= 35
                    && maximumBytes >= 1024 && maximumBytes <= 64_000_000, "PROTEIN_REGION_DEMAND_LIMIT_INVALID");
        }
    }
    public record Resolution(ObjectNode annotations, ObjectNode combined, ObjectNode acquisition) { }
    private final ObjectMapper mapper;
    private final Settings settings;
    private final ProteinRegionHttpTransport.Fetcher fetcher;
    private final Path cache;
    private final long deadline;
    private final Map<String,String> failures = new LinkedHashMap<>();
    private final java.util.Set<String> attempted = new java.util.HashSet<>();
    private final Map<URI,Observation> responses = new LinkedHashMap<>();
    private record Observation(ProteinRegionHttpTransport.Response response, Instant started, Instant finished) { }
    private int requests, bytes, cacheHits, published;
    @Nullable private String stopped;

    public ProteinRegionDemandSupply(ObjectMapper mapper, Settings settings) throws IOException { this(mapper, settings, ProteinRegionHttpTransport::get); }
    ProteinRegionDemandSupply(ObjectMapper mapper, Settings settings, ProteinRegionHttpTransport.Fetcher fetcher) throws IOException {
        this.mapper = strictMapper(mapper); this.settings = settings; this.fetcher = fetcher;
        Files.createDirectories(settings.cache()); cache = settings.cache().toRealPath();
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.totalSeconds());
    }
    public ObjectNode identity() {
        return mapper.createObjectNode().put("version", VERSION).put("adapterVersion", InterProProteinRegionPreparation.VERSION)
                .put("uniprotRelease", settings.uniprotRelease()).put("interproRelease", settings.interproRelease()).put("interproMinor", settings.interproMinor())
                .put("networkAllowed", settings.networkAllowed()).put("maximumProteins", settings.maximumProteins()).put("maximumRequests", settings.maximumRequests())
                .put("totalSeconds", settings.totalSeconds()).put("requestSeconds", settings.requestSeconds()).put("maximumBytes", settings.maximumBytes())
                .put("transport", "JDK_HTTPS_HTTP1_1_NO_REDIRECTS")
                .put("cacheRootIdentity", CodingTranscriptMaterialSupply.sha(cache.toString())).put("negativeCoverageGranted", false);
    }
    public ObjectNode counts() { return mapper.createObjectNode().put("httpRequests", requests).put("chargedBytes", bytes).put("cacheHits", cacheHits).put("published", published); }

    /** Invalid existing sources remain errors. Only legitimate absence/missing exact mappings permit acquisition. */
    Resolution resolve(CodingTranscriptMaterialSupply.Material material, ObjectNode local, ObjectNode extra) {
        return resolve(material, local, extra, false);
    }
    Resolution resolveForBp3(CodingTranscriptMaterialSupply.Material material, ObjectNode local, ObjectNode extra) {
        return resolve(material, local, extra, true);
    }
    private Resolution resolve(CodingTranscriptMaterialSupply.Material material, ObjectNode local, ObjectNode extra, boolean bp3) {
        if ("READY".equals(local.path("status").asText()) && "READY".equals(extra.path("status").asText())) {
            if (!settings.uniprotRelease().equals(local.at("/source/release").asText())
                    || !settings.uniprotRelease().equals(extra.at("/sources/uniprotRelease").asText())
                    || !settings.interproRelease().equals(extra.at("/sources/interproRelease").asText())
                    || !settings.interproMinor().equals(extra.at("/sources/interproMinor").asText()))
                return unavailable("PROTEIN_REGION_RELEASE_MISMATCH", mapper.createObjectNode().put("origin", "LOCAL_SOURCE_REJECTED"));
            return new Resolution(local, extra, mapper.createObjectNode().put("origin", "LOCAL_SNAPSHOT"));
        }
        boolean miss = "READY".equals(local.path("status").asText())
                ? List.of("PM4_COMBINED_RESOURCE_NOT_CONFIGURED", "PM4_COMBINED_ACCESSION_NOT_IN_SNAPSHOT").contains(extra.path("reasonCode").asText())
                : List.of("PROTEIN_REGION_LOCAL_NOT_CONFIGURED", "PROTEIN_REGION_GENE_NOT_IN_LOCAL_SNAPSHOT", "PROTEIN_REGION_EXACT_MAPPING_UNAVAILABLE").contains(local.path("reasonCode").asText());
        if (List.of("PM4_COMBINED_BYTES_CHANGED", "PM4_COMBINED_IO_OR_JSON_INVALID", "PM4_COMBINED_DOCUMENT_INVALID")
                .contains(extra.path("reasonCode").asText())) return new Resolution(
                        mapper.createObjectNode().put("status", "UNAVAILABLE").set("reasonCode", extra.path("reasonCode")), extra,
                        mapper.createObjectNode().put("origin", "LOCAL_SOURCE_REJECTED"));
        if (!miss) return new Resolution(local, extra, mapper.createObjectNode().put("origin", "LOCAL_SOURCE_REJECTED"));
        var acquired = mapper.createObjectNode().put("origin", "UNAVAILABLE");
        try {
            require(material.ready() && material.model() != null, "PROTEIN_REGION_SEQUENCE_MATERIAL_UNAVAILABLE");
            var model = java.util.Objects.requireNonNull(material.model());
            var packet = obtain(model); acquired.setAll(packet.acquisition());
            var uni = new UniProtProteinRegionSupply(mapper, packet.directory(), packet.manifestSha());
            var annotations = uni.resolve(material);
            var reader = new CombinedProteinRegionSupply(mapper, packet.directory().resolve("combined.json"), packet.combinedSha());
            var combined = bp3 ? reader.resolveForBp3(annotations) : reader.resolve(annotations);
            return new Resolution(annotations, combined, acquired);
        } catch (IOException bad) { return unavailable("PROTEIN_REGION_DEMAND_IO_UNAVAILABLE", acquired); }
        catch (IllegalArgumentException bad) { return unavailable(bad.getMessage(), acquired); }
    }
    private Resolution unavailable(String reason, ObjectNode acquired) {
        acquired.put("reasonCode", reason); acquired.set("counts", counts());
        return new Resolution(mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", reason),
                mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", reason), acquired);
    }
    record Packet(Path directory, String manifestSha, String combinedSha, ObjectNode acquisition) { }

    synchronized Packet obtain(ObjectNode model) throws IOException {
        String cds = model.path("cdsSequence").asText();
        require(cds.startsWith("ATG") && cds.length() >= 6 && cds.length() <= 18003 && cds.length() % 3 == 0 && cds.matches("[ACGT]+"), "PROTEIN_REGION_DEMAND_SEQUENCE_INVALID");
        String sequence = ProteinLengthChangeObservation.translate(cds);
        require(sequence.endsWith("*") && sequence.indexOf('*') == sequence.length()-1 && sequence.length() <= 6001 && sequence.length()-1 == model.path("proteinLength").asInt(),
                "PROTEIN_REGION_DEMAND_SEQUENCE_INVALID");
        sequence = sequence.substring(0, sequence.length()-1);
        require(model.path("hgncId").asText().matches("HGNC:[1-9][0-9]*")
                && model.path("transcript").asText().matches("(?:ENST[0-9]+|NM_[0-9]+)\\.[1-9][0-9]*")
                && model.path("protein").asText().matches("(?:ENSP[0-9]+|NP_[0-9]+)\\.[1-9][0-9]*"), "PROTEIN_REGION_DEMAND_MODEL_IDENTITY_INVALID");
        var query = mapper.createObjectNode().put("version", VERSION).put("adapterVersion", InterProProteinRegionPreparation.VERSION)
                .put("hgncId", model.path("hgncId").asText()).put("transcript", model.path("transcript").asText())
                .put("protein", model.path("protein").asText()).put("proteinSequenceSha256", CodingTranscriptMaterialSupply.sha(sequence))
                .put("uniprotRelease", settings.uniprotRelease()).put("interproRelease", settings.interproRelease()).put("interproMinor", settings.interproMinor());
        String key = new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(query); Path target = cache.resolve(key);
        if (Files.exists(target)) { cacheHits++; return load(target, query, "CACHE"); }
        if (failures.containsKey(key)) throw new IllegalArgumentException(failures.get(key));
        if (attempted.size() >= settings.maximumProteins()) throw new IllegalArgumentException("PROTEIN_REGION_DEMAND_PROTEIN_LIMIT");
        attempted.add(key);
        try {
            require(settings.networkAllowed(), "PROTEIN_REGION_DEMAND_NETWORK_DISABLED"); check();
            try (var channel = FileChannel.open(cache.resolve(key + ".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.tryLock()) {
                require(lock != null, "PROTEIN_REGION_CACHE_BUSY");
                if (Files.exists(target)) { cacheHits++; return load(target, query, "CACHE"); }
                Path stage = Files.createDirectory(cache.resolve("attempt-" + UUID.randomUUID()));
                try {
                    build(stage, model, sequence, query);
                    var checked = load(stage, query, "NETWORK");
                    // Never expose partial files to a cache reader. Keep failures as acquisition evidence.
                    Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
                    published++;
                    return new Packet(target, checked.manifestSha(), checked.combinedSha(), checked.acquisition());
                } catch (IOException | RuntimeException bad) {
                    mapper.writeValue(stage.resolve("failure.json").toFile(), mapper.createObjectNode().put("reason", safeReason(bad)).put("completedAt", Instant.now().toString()));
                    throw bad;
                }
            }
        } catch (OverlappingFileLockException bad) { failures.put(key, "PROTEIN_REGION_CACHE_BUSY"); throw new IllegalArgumentException("PROTEIN_REGION_CACHE_BUSY"); }
        catch (IOException | IllegalArgumentException bad) { failures.put(key, safeReason(bad)); throw new IllegalArgumentException(safeReason(bad), bad); }
    }

    private void build(Path directory, ObjectNode model, String sequence, ObjectNode query) throws IOException {
        var files = mapper.createObjectNode();
        String search = "(xref:hgnc-" + model.path("hgncId").asText().substring(5) + ") AND (organism_id:9606) AND (reviewed:true)";
        var searched = get(directory, "search", "https://rest.uniprot.org/uniprotkb/search?query="
                + URLEncoder.encode(search, StandardCharsets.UTF_8) + "&format=list&size=25", 16_384, files, true);
        String[] ids = new String(searched.body(), StandardCharsets.US_ASCII).strip().isEmpty() ? new String[0]
                : new String(searched.body(), StandardCharsets.US_ASCII).strip().split("\\s+");
        String total = searched.header("x-total-results");
        require(total.matches("[0-9]{1,5}") && Integer.parseInt(total) == ids.length && ids.length <= 25
                && searched.header("link").isBlank(), "PROTEIN_REGION_SEARCH_INCOMPLETE");
        require(ids.length > 0, "PROTEIN_REGION_NO_REVIEWED_RECORD");
        var seen = new java.util.HashSet<String>(); var matches = new ArrayList<JsonNode>(); var matchedIds = new ArrayList<String>();
        var allReceipts = mapper.createArrayNode();
        for (String id : ids) {
            require(id.matches("[A-Z0-9]{6,10}") && seen.add(id), "PROTEIN_REGION_SEARCH_ACCESSION_INVALID");
            var record = get(directory, id, "https://rest.uniprot.org/uniprotkb/" + id + ".json", MAX_RECORD_BYTES, files, true);
            JsonNode raw = mapper.readTree(record.body()); require(raw instanceof ObjectNode, "PROTEIN_REGION_JSON_OBJECT_REQUIRED");
            var nativeRecord = (ObjectNode)raw; validateRecord(nativeRecord);
            require(id.equals(raw.path("primaryAccession").asText()) && genes(nativeRecord).toString().contains("\"" + model.path("hgncId").asText() + "\""), "PROTEIN_REGION_SEARCH_GENE_MISMATCH");
            if (UniProtProteinRegionSupply.mappingIssue(nativeRecord, model, sequence) == null) { matches.add(raw); matchedIds.add(id); }
        }
        require(matches.size() == 1, matches.isEmpty() ? "PROTEIN_REGION_EXACT_MAPPING_UNAVAILABLE" : "PROTEIN_REGION_MULTIPLE_SOURCE_RECORDS");
        String id = matchedIds.get(0); var uni = matches.get(0);
        var uniReceipt = mapper.readTree(readBytes(directory.resolve(id + "-receipt.json"), 65536));
        var item = mapper.createObjectNode().put("accession", id).put("success", true).put("status", 200)
                .put("url", uniReceipt.path("url").asText()).put("startedAt", uniReceipt.path("startedAt").asText()).put("finishedAt", uniReceipt.path("finishedAt").asText())
                .put("headers", "X-UniProt-Release: " + settings.uniprotRelease() + "\n")
                .put("bytes", uniReceipt.path("bytes").asInt()).put("sha256", uniReceipt.path("sha256").asText());
        allReceipts.add(item); write(directory, "acquisition.json", allReceipts, files);
        // Other search candidates retain their bytes/receipts but are not indexed as a chosen annotation source.
        var manifest = UniProtProteinRegionPreparation.prepare(mapper, directory); write(directory, "manifest.json", manifest, files);
        var root = object(get(directory, "root", ROOT_URL, MAX_RECORD_BYTES, files, false).body());
        require(root.at("/databases/uniprot/version").asText().equals(settings.uniprotRelease())
                && root.at("/databases/interpro/version").asText().equals(settings.interproRelease()), "PROTEIN_REGION_RELEASE_MISMATCH");
        var protein = object(get(directory, "protein", ROOT_URL + "protein/uniprot/" + id + "/", MAX_RECORD_BYTES, files, false).body());
        var entries = object(get(directory, "entries", ROOT_URL + "entry/interpro/protein/uniprot/" + id + "/?page_size=200", MAX_RECORD_BYTES, files, false).body());
        var sources = mapper.createObjectNode().put("uniprotRelease", settings.uniprotRelease()).put("interproRelease", settings.interproRelease()).put("interproMinor", settings.interproMinor());
        var pins = sources.putObject("pins");
        for (String key : List.of("root", "protein", "entries")) {
            pins.set(key, pin(directory, key + ".json")); pins.set(key + "Receipt", pin(directory, key + "-receipt.json"));
        }
        pins.set("uniprot", pin(directory, id + ".json")); pins.set("uniprotAcquisition", pin(directory, "acquisition.json"));
        var facts = InterProProteinRegionPreparation.combine(mapper, id, sequence, uni, protein, entries, sources);
        var combined = mapper.createObjectNode(); combined.putArray("results").addObject().put("accession", id).put("status", "FACTS_READY").set("facts", facts);
        write(directory, "combined.json", combined, files);
        var ready = mapper.createObjectNode().put("schemaVersion", "protein-region-demand-cache-v1").put("version", VERSION);
        ready.set("query", query); ready.set("files", files); mapper.writeValue(directory.resolve("ready.json").toFile(), ready);
    }
    private ProteinRegionHttpTransport.Response get(Path directory, String name, String url, int maximum, ObjectNode files, boolean uni) throws IOException {
        URI uri = URI.create(url); var memo = responses.get(uri);
        var response = memo == null ? null : memo.response();
        Instant start = memo == null ? Instant.now() : memo.started();
        boolean reused = memo != null;
        if (response == null) {
            check(); require(requests < settings.maximumRequests(), "PROTEIN_REGION_DEMAND_REQUEST_LIMIT");
            int limit = Math.min(maximum, settings.maximumBytes() - bytes); require(limit > 0, "PROTEIN_REGION_DEMAND_BYTE_LIMIT");
            boolean permit = false;
            try {
                permit = NETWORK.tryAcquire(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                require(permit, "PROTEIN_REGION_DEMAND_TOTAL_TIMEOUT"); check();
                require(System.currentTimeMillis() >= COOLDOWN_UNTIL.get(), "PROTEIN_REGION_SERVICE_COOLDOWN");
                requests++; bytes += limit; // Charge the reserved cap on failed or interrupted reads as well.
                response = fetcher.get(uri, Duration.ofNanos(Math.min(TimeUnit.SECONDS.toNanos(settings.requestSeconds()), deadline - System.nanoTime())), limit);
                require(response.body().length <= limit, "PROTEIN_REGION_DEMAND_BYTE_LIMIT"); bytes -= limit - response.body().length;
                if (response.status() == 429 || response.status() == 503) {
                    long seconds = 60; String retry = response.header("retry-after");
                    if (retry.matches("[0-9]{1,6}")) seconds = Math.max(60, Math.min(86400, Long.parseLong(retry)));
                    COOLDOWN_UNTIL.accumulateAndGet(System.currentTimeMillis() + seconds*1000, Math::max); stopped = "PROTEIN_REGION_SERVICE_COOLDOWN";
                }
            } catch (InterruptedException bad) { Thread.currentThread().interrupt(); stopped = "PROTEIN_REGION_DEMAND_INTERRUPTED"; throw new IOException(stopped, bad); }
            catch (IOException bad) {
                stopped = safeReason(bad);
                write(directory, name + "-failure.json", mapper.createObjectNode().put("url", url)
                        .put("startedAt", start.toString()).put("finishedAt", Instant.now().toString())
                        .put("reasonCode", stopped).put("reservedByteLimit", limit).set("causeTypes", causeTypes(bad)), files);
                throw bad;
            }
            finally { if (permit) NETWORK.release(); }
        }
        var actual = java.util.Objects.requireNonNull(response);
        Instant finished = memo == null ? Instant.now() : memo.finished();
        var receipt = mapper.createObjectNode().put("url", url).put("resolvedUrl", url).put("status", actual.status())
                .put("success", actual.status() == 200).put("startedAt", start.toString()).put("finishedAt", finished.toString())
                .put("bytes", actual.body().length).put("sha256", CodingTranscriptMaterialSupply.sha(actual.body())).put("requestMemoReused", reused);
        var headers = receipt.putObject("headers"); actual.headers().forEach((key,values) -> headers.put(key, String.join(",", values)));
        write(directory, name + "-receipt.json", receipt, files); Files.write(directory.resolve(name + ".json"), actual.body(), StandardOpenOption.CREATE_NEW);
        files.set(name + ".json", pin(directory, name + ".json"));
        if (actual.status() != 200 && actual.status() != 404) stopped = "PROTEIN_REGION_HTTP_" + actual.status();
        require(actual.status() == 200, actual.status() == 404 ? "PROTEIN_REGION_RECORD_NOT_FOUND" : "PROTEIN_REGION_HTTP_" + actual.status());
        require(actual.header("content-encoding").isBlank() || "identity".equalsIgnoreCase(actual.header("content-encoding")), "PROTEIN_REGION_CONTENT_ENCODING_UNSUPPORTED");
        boolean release = uni ? settings.uniprotRelease().equals(actual.header("x-uniprot-release"))
                : settings.interproRelease().equals(actual.header("interpro-version")) && settings.interproMinor().equals(actual.header("interpro-version-minor"));
        if (!release) { stopped = "PROTEIN_REGION_RELEASE_MISMATCH"; throw new IllegalArgumentException(stopped); }
        responses.put(uri, new Observation(actual, start, finished)); return actual;
    }
    private Packet load(Path directory, ObjectNode query, String origin) throws IOException {
        require(directory.toRealPath().getParent().equals(cache), "PROTEIN_REGION_CACHE_PATH_INVALID");
        var ready = mapper.readTree(readBytes(directory.resolve("ready.json"), 1_000_000));
        require(ready != null && "protein-region-demand-cache-v1".equals(ready.path("schemaVersion").asText())
                && VERSION.equals(ready.path("version").asText()) && query.equals(ready.path("query"))
                && ready.path("files").isObject() && ready.path("files").size() <= 100, "PROTEIN_REGION_CACHE_IDENTITY_INVALID");
        if (ready == null) throw new IllegalArgumentException("PROTEIN_REGION_CACHE_IDENTITY_INVALID");
        for (String name : List.of("manifest.json", "combined.json", "acquisition.json", "search.json", "search-receipt.json",
                "root.json", "root-receipt.json", "protein.json", "protein-receipt.json", "entries.json", "entries-receipt.json"))
            require(ready.path("files").has(name), "PROTEIN_REGION_CACHE_MANIFEST_MISSING");
        for (var field : ready.path("files").properties()) {
            String name = field.getKey();
            require(name.matches("[A-Za-z0-9-]+\\.json"), "PROTEIN_REGION_CACHE_PATH_INVALID");
            var p = directory.resolve(name).toRealPath(); require(p.getParent().equals(directory.toRealPath()), "PROTEIN_REGION_CACHE_PATH_INVALID");
            byte[] raw = readBytes(p, 8_000_000);
            require(field.getValue().path("bytes").isIntegralNumber() && field.getValue().path("bytes").asLong(-1) == raw.length
                    && CodingTranscriptMaterialSupply.sha(raw).equals(field.getValue().path("sha256").asText()), "PROTEIN_REGION_CACHE_BYTES_CHANGED");
        }
        String uni = ready.at("/files/manifest.json/sha256").asText(), combined = ready.at("/files/combined.json/sha256").asText();
        require(uni.matches("[0-9a-f]{64}") && combined.matches("[0-9a-f]{64}"), "PROTEIN_REGION_CACHE_MANIFEST_MISSING");
        return new Packet(directory, uni, combined, mapper.createObjectNode().put("origin", origin)
                .put("cacheKey", new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(query)).put("packetSha256", CodingTranscriptMaterialSupply.sha(readBytes(directory.resolve("ready.json"),1_000_000)))
                .put("uniprotManifestSha256", uni).put("combinedSha256", combined));
    }
    private com.fasterxml.jackson.databind.node.ArrayNode causeTypes(Throwable bad) {
        var out = mapper.createArrayNode(); Throwable cursor = bad;
        for (int i=0; i<8 && cursor!=null; i++, cursor=cursor.getCause()) out.add(cursor.getClass().getName());
        return out;
    }
    private ObjectNode object(byte[] bytes) throws IOException {
        var node = mapper.readTree(bytes);
        if (!(node instanceof ObjectNode object)) throw new IllegalArgumentException("PROTEIN_REGION_JSON_OBJECT_REQUIRED");
        return object;
    }
    private ObjectNode pin(Path directory, String file) throws IOException {
        byte[] bytes = readBytes(directory.resolve(file), 8_000_000);
        return mapper.createObjectNode().put("path", file).put("bytes", bytes.length).put("sha256", CodingTranscriptMaterialSupply.sha(bytes));
    }
    private void write(Path directory, String file, JsonNode value, ObjectNode files) throws IOException {
        Files.write(directory.resolve(file), mapper.writeValueAsBytes(value), StandardOpenOption.CREATE_NEW); files.set(file, pin(directory, file));
    }
    private void check() {
        require(!Thread.currentThread().isInterrupted(), "PROTEIN_REGION_DEMAND_INTERRUPTED");
        require(stopped == null, stopped == null ? "PROTEIN_REGION_DEMAND_STOPPED" : stopped);
        require(System.nanoTime() < deadline, "PROTEIN_REGION_DEMAND_TOTAL_TIMEOUT");
    }
    private static String safeReason(Throwable bad) {
        String value = bad.getMessage(); return value != null && value.matches("[A-Z][A-Z0-9_]{0,160}") ? value : "PROTEIN_REGION_DEMAND_IO_UNAVAILABLE";
    }
}
