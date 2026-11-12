package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Batch-owned public acquisition. Only public gene/transcript/record identifiers leave the host.
 * A successful gene summary is not a complete interval search or a scientific review. */
public final class Pvs1StartLossPublicSources {
    static final String BASE = "https://erepo.genome.network/evrepo/api/";
    public static final String VERSION = "1.3.0";
    record Snapshot(ObjectNode receipt, JsonNode document) {
        Snapshot { receipt = receipt.deepCopy(); document = document.deepCopy(); }
        @Override public ObjectNode receipt() { return receipt.deepCopy(); }
        @Override public JsonNode document() { return document.deepCopy(); }
    }
    private final ObjectMapper mapper;
    private final boolean networkAllowed;
    private final ProteinRegionHttpTransport.Fetcher fetcher;
    private final Map<String, Snapshot> cache = new LinkedHashMap<>();
    private final ObjectNode originals;
    private int requests, bytes;
    private long elapsedNanos;
    public Pvs1StartLossPublicSources(ObjectMapper mapper, boolean networkAllowed) {
        this(mapper, networkAllowed, (uri, timeout, maximum) -> Set.of("eutils.ncbi.nlm.nih.gov", "search.clinicalgenome.org", "ftp.clinicalgenome.org").contains(uri.getHost())
                ? ProteinRegionHttpTransport.getText(uri, timeout, maximum) : ProteinRegionHttpTransport.get(uri, timeout, maximum));
    }
    Pvs1StartLossPublicSources(ObjectMapper mapper, boolean networkAllowed, ProteinRegionHttpTransport.Fetcher fetcher) {
        this.mapper = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.networkAllowed = networkAllowed; this.fetcher = fetcher; originals = mapper.createObjectNode();
    }
    public ObjectNode identity() {
        return mapper.createObjectNode().put("version", VERSION).put("networkAllowed", networkAllowed)
                .put("maximumRequests", 32).put("maximumBytes", 32_000_000).put("maximumNetworkSeconds", 60)
                .put("negativeCoverageGranted", false).put("patientDataSent", false);
    }
    public ObjectNode originals() { return originals.deepCopy(); }
    public ObjectNode counts() { return mapper.createObjectNode().put("httpRequests", requests).put("bytes", bytes); }
    Snapshot gene(String symbol) {
        if (!symbol.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) return failure("", "GENE_IDENTITY_INVALID");
        return get("summary/classifications?columns=gene&matchTypes=exact&values=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8));
    }
    /** Two identical count-bounded traversals establish only this source's gene
     * enumeration. Page gaps, duplicates, changed data and budget exhaustion do not. */
    Snapshot genePages(String symbol) {
        if (!symbol.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) return failure("", "GENE_IDENTITY_INVALID");
        String base = "summary/classifications?columns=gene&matchTypes=exact&values=" + URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        String cacheKey = "paginated:" + symbol; var previous = cache.get(cacheKey); if (previous != null) return previous;
        var aggregate = mapper.createObjectNode(); aggregate.putObject("status").put("code", 200);
        var rows = aggregate.putArray("data"); var captures = aggregate.putArray("pages");
        var first = new LinkedHashMap<String, JsonNode>(); boolean complete = true;
        String reason = "EREPO_GENE_PAGES_STABLE";
        for (int pass = 0; pass < 2 && complete; pass++) {
            var observed = new LinkedHashMap<String, JsonNode>(); boolean ended = false;
            for (int page = 1; page <= 16; page++) {
                var snapshot = get(base + "&pgSize=100&pg=" + page, pass == 1);
                var receipt = snapshot.receipt(); captures.add(receipt.put("pass", pass + 1).put("page", page));
                var doc = snapshot.document();
                // ERepo represents a completed empty query (and the end after a
                // full page) as a structured 404. Keep its raw HTTP response;
                // only this verified API meaning is an empty enumeration.
                if ("AVAILABLE".equals(receipt.path("status").asText())
                        && receipt.path("semanticEmptyGeneQuery").asBoolean()
                        && emptyGeneQuery(receipt.path("sourceUrl").asText(), 404, doc)) { ended = true; break; }
                if (!"AVAILABLE".equals(receipt.path("status").asText()) || doc.at("/status/code").asInt() != 200
                        || !doc.path("data").isArray() || doc.path("data").size() > 100) {
                    complete = false; reason = "EREPO_GENE_PAGE_UNAVAILABLE_OR_INVALID"; break;
                }
                for (var row : doc.path("data")) {
                    String uuid = row.path("uuid").asText();
                    if (uuid.isBlank() || !symbol.equals(row.path("gene").asText()) || observed.putIfAbsent(uuid, row) != null) {
                        complete = false; reason = "EREPO_GENE_PAGE_DUPLICATE_OR_SCOPE_MISMATCH"; break;
                    }
                }
                if (!complete) break;
                if (doc.path("data").size() < 100) { ended = true; break; }
            }
            if (!ended && complete) { complete = false; reason = "EREPO_GENE_PAGE_LIMIT"; }
            if (pass == 0) { first.putAll(observed); observed.values().forEach(rows::add); }
            else if (!first.equals(observed)) { complete = false; reason = "EREPO_GENE_SNAPSHOT_CHANGED"; }
        }
        aggregate.put("sourceEnumerationComplete", complete).put("coverageReason", reason);
        try {
            String raw = mapper.writeValueAsString(aggregate); byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
            String sha = CodingTranscriptMaterialSupply.sha(bytes);
            var receipt = mapper.createObjectNode().put("status", "AVAILABLE").put("sourceUrl", BASE + base)
                    .put("sha256", sha).put("sourceEnumerationComplete", complete).put("coverageReason", reason)
                    .put("kind", "DERIVED_PAGINATED_EREPO_GENE_SNAPSHOT").put("recordCount", rows.size());
            originals.putObject(sha).put("rawJson", raw).set("receipt", receipt.deepCopy());
            var result = new Snapshot(receipt, aggregate); cache.put(cacheKey, result); return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { return failure(BASE + base, "PAGE_ASSEMBLY_FAILED"); }
    }
    Snapshot interpretation(String uuid) {
        if (!uuid.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")) return failure("", "RECORD_ID_INVALID");
        return get("interpretation/" + uuid);
    }
    Snapshot clinvarRecord(String variationId) {
        if (!variationId.matches("[1-9][0-9]{0,8}")) return failure("", "CLINVAR_VARIATION_ID_INVALID");
        String accession = "VCV" + "0".repeat(9 - variationId.length()) + variationId;
        return fetchSource("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=clinvar&rettype=vcv&id="
                + accession, false, "XML");
    }
    Snapshot refseqReport(String symbol, boolean products) {
        if (!symbol.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) return failure("", "GENE_IDENTITY_INVALID");
        return fetchJson("https://api.ncbi.nlm.nih.gov/datasets/v2/gene/symbol/"
                + URLEncoder.encode(symbol, StandardCharsets.UTF_8) + "/taxon/9606/"
                + (products ? "product_report" : "dataset_report") + "?page_size=1000", false);
    }
    Snapshot refseqSequences(java.util.List<String> accessions) {
        if (accessions.isEmpty() || accessions.size() > 50 || new java.util.HashSet<>(accessions).size() != accessions.size()
                || accessions.stream().anyMatch(id -> !id.matches("[NX][MR]_[0-9]+\\.[1-9][0-9]*")))
            return failure("", "REFSEQ_SEQUENCE_IDENTIFIERS_INVALID");
        String ids = String.join(",", accessions.stream().sorted().toList());
        return fetchSource("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=nuccore&id="
                + URLEncoder.encode(ids, StandardCharsets.UTF_8) + "&rettype=fasta&retmode=text", false, true);
    }
    Snapshot refseqGenBank(String transcript) {
        if (!transcript.matches("NM_[0-9]+\\.[1-9][0-9]*")) return failure("", "REFSEQ_SEQUENCE_IDENTIFIERS_INVALID");
        return fetchSource("https://eutils.ncbi.nlm.nih.gov/entrez/eutils/efetch.fcgi?db=nuccore&id="
                + URLEncoder.encode(transcript, StandardCharsets.UTF_8) + "&rettype=gb&retmode=text", false, "GENBANK");
    }
    Snapshot clinGenMechanism(String source) {
        if (!Set.of(Pvs1StartLossClinGenMechanism.DOSAGE_URL, Pvs1StartLossClinGenMechanism.VALIDITY_URL,
                Pvs1StartLossClinGenMechanism.DISEASE_DOSAGE_URL).contains(source))
            return failure("", "CLINGEN_MECHANISM_SOURCE_INVALID");
        return fetchSource(source, false, "CSV");
    }
    Snapshot ensemblTranscript(String transcript) {
        if (!transcript.matches("ENST[0-9]+\\.[1-9][0-9]*")) return failure("", "ENSEMBL_TRANSCRIPT_IDENTITY_INVALID");
        String id = transcript.substring(0, transcript.indexOf('.'));
        return fetchJson("https://rest.ensembl.org/lookup/id/" + id + "?expand=1;content-type=application/json", false);
    }
    Snapshot cspecVersion(String namespace, String version) {
        if (!namespace.matches("GN[0-9]{3,5}") || !version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}"))
            return failure("", "REFERENCE_GUIDELINE_IDENTITY_INVALID");
        return fetchJson("https://cspec.genome.network/cspec/SequenceVariantInterpretation/id/" + namespace + "/version/" + version
                + "?detail=high&fields=entId%2CentType%2CldhId%2Crev%2Cmodified%2CentContent%2Cld.Gene%2Cld.RuleSet%2Cld.CriteriaCode%2Cld.File", false);
    }
    private Snapshot get(String relative) {
        return get(relative, false);
    }
    private Snapshot get(String relative, boolean fresh) {
        return fetchJson(BASE + relative, fresh);
    }
    private Snapshot fetchJson(String url, boolean fresh) {
        return fetchSource(url, fresh, false);
    }
    private Snapshot fetchSource(String url, boolean fresh, boolean fasta) {
        return fetchSource(url, fresh, fasta ? "FASTA" : "JSON");
    }
    private Snapshot fetchSource(String url, boolean fresh, String format) {
        var cached = cache.get(url);
        if (cached != null && !fresh) return cached;
        if (!networkAllowed) return failure(url, "PUBLIC_NETWORK_DISABLED");
        if (Thread.currentThread().isInterrupted()) return failure(url, "INTERRUPTED");
        long remaining = Duration.ofSeconds(60).toNanos() - elapsedNanos;
        if (requests >= 32 || bytes >= 32_000_000 || remaining <= 0) return failure(url, "ACQUISITION_BUDGET_EXHAUSTED");
        long started = System.nanoTime(); requests++;
        Snapshot result;
        int limit = Math.min(4_000_000, 32_000_000 - bytes);
        boolean charged = false;
        try {
            var response = fetcher.get(URI.create(url), Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(12).toNanos())), limit);
            if (response == null || response.body() == null) throw new IllegalArgumentException("SOURCE_RESPONSE_INVALID");
            bytes += Math.min(response.body().length, limit);
            charged = true;
            if (response.status() != 200 && !(response.status() == 404 && "JSON".equals(format)
                    && url.startsWith(BASE + "summary/classifications?"))) result = failure(url, "SOURCE_HTTP_" + response.status());
            else {
                byte[] raw = response.body();
                if (raw.length == 0 || raw.length > limit) throw new IllegalArgumentException("SOURCE_SIZE_INVALID");
                // Decode strictly: retaining a replacement-character string would change original bytes.
                String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(raw)).toString();
                JsonNode doc = "FASTA".equals(format) ? fasta(text) : "XML".equals(format)
                        ? mapper.createObjectNode().put("xml", text) : "GENBANK".equals(format)
                        ? mapper.createObjectNode().put("genbank", text) : "CSV".equals(format)
                        ? mapper.createObjectNode().put("rawCsv", text) : mapper.readTree(raw);
                if (doc == null || !doc.isObject()) throw new IllegalArgumentException("SOURCE_SCHEMA_INVALID");
                boolean emptyGeneQuery = emptyGeneQuery(url, response.status(), doc);
                if (response.status() != 200 && !emptyGeneQuery) throw new IllegalArgumentException("SOURCE_HTTP_" + response.status());
                String sha = CodingTranscriptMaterialSupply.sha(raw);
                var receipt = mapper.createObjectNode().put("status", "AVAILABLE").put("sourceUrl", url)
                        .put("sha256", sha).put("capturedAt", Instant.now().toString()).put("bytes", raw.length);
                if (emptyGeneQuery) receipt.put("httpStatus", 404).put("semanticEmptyGeneQuery", true)
                        .put("semanticMeaning", "EREPO_STRUCTURED_NO_RECORDS_FOR_EXACT_GENE_QUERY");
                var stored = originals.path(sha) instanceof ObjectNode prior ? prior : originals.putObject(sha);
                String rawField = Set.of("FASTA", "GENBANK", "CSV").contains(format) ? "rawText" : "XML".equals(format) ? "rawXml" : "rawJson";
                receipt.put("format", format);
                if (!stored.has(rawField)) { stored.put(rawField, text); stored.set("receipt", receipt.deepCopy()); stored.putArray("captures"); }
                stored.withArray("captures").add(receipt.deepCopy());
                result = new Snapshot(receipt, doc);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); result = failure(url, "INTERRUPTED");
        } catch (Exception unavailable) { result = failure(url, "SOURCE_UNAVAILABLE_OR_INVALID"); }
        finally {
            // Transport exceptions may happen after a partial body: charge the reservation
            // rather than making repeated oversized/failed reads free against the batch cap.
            if (!charged) bytes += limit;
            elapsedNanos += System.nanoTime() - started;
        }
        // Includes failures: at most one attempt per source in this batch. New runs may retry.
        cache.put(url, result); return result;
    }
    private static boolean emptyGeneQuery(String url, int status, JsonNode doc) {
        if (status != 404 || !url.matches(java.util.regex.Pattern.quote(BASE)
                + "summary/classifications\\?columns=gene&matchTypes=exact&values=[A-Za-z0-9][A-Za-z0-9_.-]{0,63}&pgSize=100&pg=[1-9][0-9]?")) return false;
        if (!doc.isObject() || doc.size() != 2 || doc.path("metadata").size() != 1 || doc.at("/metadata/rendered").size() != 2
                || doc.path("status").size() != 3 || !doc.at("/status/code").isIntegralNumber() || doc.at("/status/code").asInt() != 404
                || !"Not Found".equals(doc.at("/status/name").asText())
                || !"No records were found for given query".equals(doc.at("/status/msg").asText())
                || !(BASE + "summary/srvc").equals(doc.at("/metadata/rendered/by").asText())) return false;
        try { Instant.parse(doc.at("/metadata/rendered/when").asText()); return true; }
        catch (java.time.DateTimeException invalid) { return false; }
    }
    private ObjectNode fasta(String text) {
        var doc = mapper.createObjectNode(); var sequences = doc.putObject("sequences");
        String accession = null; var bases = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            if (line.startsWith(">")) {
                if (accession != null) {
                    if (bases.isEmpty()) throw new IllegalArgumentException("EMPTY_FASTA_RECORD");
                    sequences.put(accession, bases.toString());
                }
                accession = line.substring(1).split("\\s+", 2)[0]; bases.setLength(0);
                if (!accession.matches("[NX][MR]_[0-9]+\\.[1-9][0-9]*") || sequences.has(accession) || sequences.size() >= 50)
                    throw new IllegalArgumentException("FASTA_IDENTITY_INVALID");
            } else {
                if (accession == null || !line.matches("[ACGTNacgtn]+") || bases.length() + line.length() > 1_000_000)
                    throw new IllegalArgumentException("FASTA_SEQUENCE_INVALID");
                bases.append(line.toUpperCase(java.util.Locale.ROOT));
            }
        }
        if (accession == null || bases.isEmpty()) throw new IllegalArgumentException("FASTA_RECORD_MISSING");
        sequences.put(accession, bases.toString()); return doc;
    }
    private Snapshot failure(String url, String reason) {
        return new Snapshot(mapper.createObjectNode().put("status", "UNAVAILABLE").put("sourceUrl", url)
                .put("reasonCode", "PVS1_START_LOSS_AUTO_" + reason), mapper.missingNode());
    }
}
