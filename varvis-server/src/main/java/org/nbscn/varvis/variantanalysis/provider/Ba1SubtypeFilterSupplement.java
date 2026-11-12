package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Native same-release FILTER evidence only. Never changes joint observations or frequency values. */
public final class Ba1SubtypeFilterSupplement {
    public static final String VERSION = "1.0.0";
    private static final String SCHEMA = "ba1-native-subtype-filter-supplement-v1";
    private static final int MAX_OUTPUT_BYTES = 262144;
    private static final int MAX_ROWS = 256;
    private static final List<String> SUBTYPES = List.of("exomes", "genomes");
    private final ObjectMapper mapper;
    private final ObjectNode sourcePolicy;
    private final ObjectNode identity;
    private final String executable;
    private final long timeoutMillis;
    private final Path tempRoot;
    private final Path remoteIndexCacheDir;
    private final Query query;
    private final Map<String, BoundedRangeTabix.Binding> rangeBindings;

    @FunctionalInterface
    interface Query {
        void run(List<String> command, Path output, Path error, VariantAnalysisProperties.Gnomad settings,
                 String resource, String querySha, List<Map<String, Object>> diagnostics) throws IOException;
    }

    public Ba1SubtypeFilterSupplement(ObjectMapper mapper, VariantAnalysisProperties settings, JsonNode reviewedSourcePolicy) {
        this(mapper, settings, reviewedSourcePolicy, Path.of(System.getProperty("java.io.tmpdir")),
                (command, output, error, budget, resource, querySha, diagnostics) -> GnomadQueryRunner.run(
                        command, output, error, true, budget, resource, querySha, 1, diagnostics));
    }

    Ba1SubtypeFilterSupplement(ObjectMapper mapper, VariantAnalysisProperties settings, JsonNode reviewedSourcePolicy,
                              Path tempRoot, Query query) {
        this.mapper = Objects.requireNonNull(mapper);
        try { rangeBindings = BoundedRangeTabix.VERSION.equals(settings.getGnomad().getRemoteTransport())
                ? BoundedRangeTabix.bindings(settings.getGnomad().getRangeBindingsFile()) : Map.of(); }
        catch (IOException e) { throw new IllegalArgumentException("BA1_RANGE_CONFIGURATION_INVALID", e); }
        if (!validPolicy(reviewedSourcePolicy)) throw new IllegalArgumentException("BA1_SUBTYPE_SOURCE_POLICY_INVALID");
        sourcePolicy = ((ObjectNode) reviewedSourcePolicy).deepCopy();
        identity = mapper.createObjectNode().put("schemaVersion", "ba1-subtype-source-identity-v1")
                .put("algorithmVersion", VERSION).put("status", "AVAILABLE").put("policySha256", canonical(mapper, sourcePolicy));
        identity.set("sourcePolicy", sourcePolicy.deepCopy());
        executable = Objects.requireNonNull(settings.getGnomad().getTabixExecutable());
        remoteIndexCacheDir = settings.getGnomad().getRemoteIndexCacheDir();
        if (executable.isBlank()) throw new IllegalArgumentException("BA1_SUBTYPE_TABIX_UNCONFIGURED");
        Integer nativeSeconds = settings.getGnomad().getBa1NativeTimeoutSeconds();
        // Execution budget only; source, allele, one-attempt and literal PASS gates are unchanged.
        timeoutMillis = (nativeSeconds == null ? 60L : nativeSeconds.longValue()) * 1000;
        this.tempRoot = Objects.requireNonNull(tempRoot).toAbsolutePath().normalize();
        this.query = Objects.requireNonNull(query);
    }

    private Ba1SubtypeFilterSupplement(ObjectMapper mapper, String reasonCode) {
        this.mapper = Objects.requireNonNull(mapper);
        rangeBindings = Map.of();
        sourcePolicy = mapper.createObjectNode();
        identity = mapper.createObjectNode().put("schemaVersion", "ba1-subtype-source-identity-v1")
                .put("algorithmVersion", VERSION).put("status", "UNAVAILABLE").put("reasonCode", reasonCode);
        executable = ""; timeoutMillis = 0; tempRoot = Path.of(System.getProperty("java.io.tmpdir"));
        remoteIndexCacheDir = null;
        query = (command, output, error, budget, resource, querySha, diagnostics) -> { throw new IOException("BA1_SUBTYPE_SOURCE_UNAVAILABLE"); };
    }

    public static Ba1SubtypeFilterSupplement bundled(ObjectMapper mapper, VariantAnalysisProperties settings) {
        try { return new Ba1SubtypeFilterSupplement(mapper, settings, bundledPolicy(mapper)); }
        catch (IOException | RuntimeException unavailable) { return new Ba1SubtypeFilterSupplement(mapper, "BA1_SUBTYPE_SOURCE_OR_CONFIGURATION_UNAVAILABLE"); }
    }

    private static JsonNode bundledPolicy(ObjectMapper mapper) throws IOException {
        try (var input = Ba1SubtypeFilterSupplement.class.getResourceAsStream("/acmg/ba1-native-subtype-source-policy-v1.json")) {
            if (input == null) throw new IOException("BA1_SUBTYPE_SOURCE_POLICY_MISSING");
            byte[] raw = input.readNBytes(65537);
            if (raw.length > 65536) throw new IOException("BA1_SUBTYPE_SOURCE_POLICY_OVERSIZED");
            JsonNode policy = mapper.readTree(raw);
            if (!validPolicy(policy)) throw new IOException("BA1_SUBTYPE_SOURCE_POLICY_INVALID");
            return policy;
        }
    }

    public ObjectNode identity() { return identity.deepCopy(); }

    public ObjectNode supplement(JsonNode normalizedAllele, Set<String> missingSubtypes,
                                 String jointResourceVersion, String jointRawSha256) {
        normalizedAllele = Objects.requireNonNull(normalizedAllele == null ? mapper.nullNode() : normalizedAllele.deepCopy());
        missingSubtypes = missingSubtypes == null ? null : new HashSet<>(missingSubtypes);
        var result = mapper.createObjectNode().put("schemaVersion", SCHEMA).put("algorithmVersion", VERSION)
                .put("jointResourceVersion", jointResourceVersion).put("jointRawSha256", jointRawSha256).put("queryCalls", 0);
        result.set("queryAllele", normalizedAllele.deepCopy());
        result.set("sourcePolicyIdentity", identity());
        var requested = result.putArray("requiredSubtypes");
        var missing = result.putArray("originalMissingSubtypes");
        var results = result.putArray("results");
        if (!"AVAILABLE".equals(identity.path("status").asText()))
            return seal(result.put("status", "UNAVAILABLE").put("reasonCode", identity.path("reasonCode").asText()));
        if (!validAllele(normalizedAllele) || !"4.1".equals(jointResourceVersion) || !sha(jointRawSha256)
                || missingSubtypes == null || !new HashSet<>(SUBTYPES).containsAll(missingSubtypes)) {
            return seal(result.put("status", "UNAVAILABLE").put("reasonCode", "BA1_SUBTYPE_INPUT_INVALID"));
        }
        for (String subtype : SUBTYPES) if (missingSubtypes.contains(subtype)) missing.add(subtype);
        if (!missingSubtypes.isEmpty()) for (String subtype : SUBTYPES) requested.add(subtype);
        boolean allPass = true;
        for (String subtype : SUBTYPES) {
            if (missingSubtypes.isEmpty()) continue;
            var item = target(subtype, normalizedAllele);
            results.add(item);
            if (Thread.currentThread().isInterrupted()) {
                item.put("status", "FAILED").put("reasonCode", "BA1_SUBTYPE_CANCELLED");
                allPass = false;
                continue;
            }
            var diagnostics = new ArrayList<Map<String, Object>>();
            Path work = null;
            try {
                work = Files.createTempDirectory(tempRoot, "varvis-ba1-subtype-");
                Path output = Files.createFile(work.resolve("output.vcf"));
                Path error = Files.createFile(work.resolve("stderr.log"));
                Path regions = Files.createFile(work.resolve("query.bed"));
                String bed = item.path("queryContig").asText() + "\t" + (normalizedAllele.path("position").asInt() - 1)
                        + "\t" + normalizedAllele.path("position").asInt() + "\n";
                Files.writeString(regions, bed, StandardCharsets.UTF_8);
                var budget = new VariantAnalysisProperties.Gnomad();
                budget.setTimeout(Duration.ofMillis(timeoutMillis));
                budget.setRemoteTotalTimeout(Duration.ofMillis(timeoutMillis));
                budget.setRemoteMaxAttempts(1);
                if (remoteIndexCacheDir != null) budget.setRemoteIndexCacheDir(remoteIndexCacheDir.toString());
                if (Thread.currentThread().isInterrupted()) throw new IOException("BA1_SUBTYPE_CANCELLED");
                result.put("queryCalls", result.path("queryCalls").asInt() + 1);
                if (!rangeBindings.isEmpty()) {
                    String url=item.path("url").asText();var binding=rangeBindings.get(url);
                    if(binding==null)throw new IOException("BA1_RANGE_OBJECT_NOT_BOUND");
                    int pos=normalizedAllele.path("position").asInt();
                    BoundedRangeTabix.query(url,url+".tbi",binding,BoundedRangeTabix.Limits.nativeFilter(),
                            Objects.requireNonNull(remoteIndexCacheDir),List.of(item.path("queryContig").asText()+":"+pos+"-"+pos),output,diagnostics);
                } else query.run(List.of(executable, "--cache", "1", "-R", regions.toString(), item.path("url").asText()),
                        output, error, budget, "gnomad." + subtype + ".v4.1.sites." + item.path("queryContig").asText() + ".vcf.bgz",
                        GnomadQueryRunner.queryIdentity(item.path("url").asText() + "|" + bed), diagnostics);
                if (Thread.currentThread().isInterrupted()) throw new IOException("BA1_SUBTYPE_CANCELLED");
                if (diagnostics.size() != 1 || !"SUCCEEDED".equals(diagnostics.get(0).get("status")))
                    throw new IOException("BA1_SUBTYPE_TRANSPORT_COMPLETION_UNPROVEN");
                byte[] bytes;
                try (var input = Files.newInputStream(output)) { bytes = input.readNBytes(MAX_OUTPUT_BYTES + 1); }
                if (bytes.length > MAX_OUTPUT_BYTES) throw new IOException("BA1_SUBTYPE_OUTPUT_LIMIT_EXCEEDED");
                String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                item.put("rawResponse", raw).put("rawResponseSha256", GnomadQueryRunner.queryIdentity(raw))
                        .put("rawResponseByteLength", bytes.length);
                item.setAll(parse(mapper, normalizedAllele, raw));
                if (Thread.currentThread().isInterrupted()) throw new IOException("BA1_SUBTYPE_CANCELLED");
            } catch (IOException | RuntimeException failure) {
                item.remove(List.of("filter", "exactRecords"));
                String transport = diagnostics.isEmpty() ? "" : String.valueOf(diagnostics.get(diagnostics.size() - 1).get("status"));
                String reason = Thread.currentThread().isInterrupted() ? "BA1_SUBTYPE_CANCELLED"
                        : "TIMEOUT".equals(transport) ? "BA1_SUBTYPE_TIMEOUT"
                        : failure.getMessage() != null && failure.getMessage().startsWith("BA1_SUBTYPE_")
                        ? failure.getMessage() : "BA1_SUBTYPE_QUERY_FAILED";
                item.put("status", "BA1_SUBTYPE_TIMEOUT".equals(reason) ? "TIMEOUT" : "FAILED").put("reasonCode", reason);
            } finally {
                // Paths are constructed only below this invocation's newly created directory; no recursive cleanup.
                boolean clean = true;
                if (work != null) for (String name : List.of("query.bed", "output.vcf", "stderr.log", "")) try {
                    Files.deleteIfExists(name.isEmpty() ? work : work.resolve(name));
                } catch (IOException | RuntimeException failure) { clean = false; }
                item.put("temporaryFilesCleaned", clean);
                if (!clean) item.put("status", "FAILED").put("reasonCode", "BA1_SUBTYPE_TEMPORARY_CLEANUP_FAILED");
                var safeDiagnostics = item.putArray("transportDiagnostics");
                for (var diagnostic : diagnostics) {
                    var safe = safeDiagnostics.addObject();
                    for (String key : List.of("status", "attempt", "maximumAttempts", "effectiveAttemptMillis", "stdoutBytes", "stderrBytes",
                            "transport", "requests", "requestsStarted", "bodyBytes", "dataLength", "dataEtag", "indexEtag", "clientShutdown", "reason"))
                        if (diagnostic.containsKey(key)) safe.set(key, mapper.valueToTree(diagnostic.get(key)));
                }
            }
            allPass &= "VERIFIED_PASS".equals(item.path("status").asText());
        }
        if (Thread.currentThread().isInterrupted() && !missingSubtypes.isEmpty())
            return seal(result.put("status", "INCOMPLETE").put("reasonCode", "BA1_SUBTYPE_CANCELLED"));
        return seal(result.put("status", missingSubtypes.isEmpty() ? "NOT_REQUESTED" : allPass ? "VERIFIED_PASS" : "INCOMPLETE")
                .put("reasonCode", missingSubtypes.isEmpty() ? "BA1_SUBTYPE_NOT_MISSING" : allPass ? "BA1_SUBTYPE_ALL_REQUIRED_PASS" : "BA1_SUBTYPE_FILTER_UNRESOLVED"));
    }

    /** Rechecks saved raw records and all source/allele bindings; status labels alone never establish PASS. */
    public static boolean verifiedPass(ObjectMapper mapper, JsonNode supplement, String subtype, JsonNode allele, String jointRawSha) {
        if (subtype == null || !SUBTYPES.contains(subtype)) return false;
        try {
            JsonNode policy = bundledPolicy(mapper);
            var expected = mapper.createObjectNode().put("schemaVersion", "ba1-subtype-source-identity-v1")
                    .put("algorithmVersion", VERSION).put("status", "AVAILABLE").put("policySha256", canonical(mapper, policy));
            expected.set("sourcePolicy", policy);
            return verifiedOne(mapper, supplement, "exomes", allele, jointRawSha, expected)
                    && verifiedOne(mapper, supplement, "genomes", allele, jointRawSha, expected);
        } catch (IOException | RuntimeException invalid) { return false; }
    }

    private static boolean verifiedOne(ObjectMapper mapper, JsonNode supplement, String subtype, JsonNode allele, String jointRawSha,
                                       JsonNode expectedSourcePolicyIdentity) {
        if (supplement == null || !supplement.isObject() || !SUBTYPES.contains(subtype) || !validAllele(allele)
                || !SCHEMA.equals(supplement.path("schemaVersion").asText()) || !VERSION.equals(supplement.path("algorithmVersion").asText())
                || !"4.1".equals(supplement.path("jointResourceVersion").asText()) || !sha(jointRawSha)
                || !jointRawSha.equals(supplement.path("jointRawSha256").asText()) || !allele.equals(supplement.path("queryAllele"))
                || expectedSourcePolicyIdentity == null || !validPolicy(expectedSourcePolicyIdentity.path("sourcePolicy"))
                || !expectedSourcePolicyIdentity.equals(supplement.path("sourcePolicyIdentity"))) return false;
        if (!"VERIFIED_PASS".equals(supplement.path("status").asText()) || supplement.path("queryCalls").asInt(-1) != 2) return false;
        if (!supplement.path("requiredSubtypes").isArray() || !supplement.path("originalMissingSubtypes").isArray()
                || !supplement.path("results").isArray()) return false;
        if (!keys(supplement, Set.of("schemaVersion", "algorithmVersion", "jointResourceVersion", "jointRawSha256", "queryCalls",
                "queryAllele", "sourcePolicyIdentity", "requiredSubtypes", "originalMissingSubtypes", "results", "status", "reasonCode", "resultId"))) return false;
        var originalMissing = new HashSet<String>();
        for (var value : supplement.path("originalMissingSubtypes"))
            if (!value.isTextual() || !SUBTYPES.contains(value.asText()) || !originalMissing.add(value.asText())) return false;
        if (originalMissing.isEmpty()) return false;
        if (!canonical(mapper, expectedSourcePolicyIdentity.path("sourcePolicy")).equals(expectedSourcePolicyIdentity.path("policySha256").asText())) return false;
        ObjectNode unsigned = ((ObjectNode) supplement).deepCopy();
        String resultId = unsigned.path("resultId").asText(); unsigned.remove("resultId");
        if (!("ba1-subtype-sha256-" + canonical(mapper, unsigned)).equals(resultId)) return false;
        var required = new HashSet<String>();
        for (var value : supplement.path("requiredSubtypes")) if (!value.isTextual() || !SUBTYPES.contains(value.asText()) || !required.add(value.asText())) return false;
        if (!required.equals(new HashSet<>(SUBTYPES)) || supplement.path("results").size() != 2) return false;
        JsonNode found = null; var seen = new HashSet<String>();
        for (var value : supplement.path("results")) {
            String name = value.path("subtype").asText();
            if (!required.contains(name) || !seen.add(name)) return false;
            if (subtype.equals(name)) found = value;
        }
        if (found == null || !"VERIFIED_PASS".equals(found.path("status").asText()) || !"4.1".equals(found.path("version").asText())) return false;
        if (!keys(found, Set.of("subtype", "version", "url", "indexUrl", "queryContig", "rawResponse", "rawResponseSha256", "rawResponseByteLength",
                "exactRecords", "returnedRows", "exactRecordCount", "filter", "status", "reasonCode", "temporaryFilesCleaned", "transportDiagnostics"))) return false;
        JsonNode diagnostic = found.path("transportDiagnostics");
        if (!found.path("temporaryFilesCleaned").asBoolean() || !diagnostic.isArray() || diagnostic.size() != 1
                || !"SUCCEEDED".equals(diagnostic.get(0).path("status").asText())
                || diagnostic.get(0).path("attempt").asInt(-1) != 1 || diagnostic.get(0).path("maximumAttempts").asInt(-1) != 1
                || diagnostic.get(0).path("effectiveAttemptMillis").asLong(-1) <= 0
                || diagnostic.get(0).path("effectiveAttemptMillis").asLong() > 120000
                || diagnostic.get(0).path("stdoutBytes").asLong(-1) != found.path("rawResponseByteLength").asLong()) return false;
        String chromosome = chromosome(allele);
        String url = url(subtype).replace("{chromosome}", chromosome);
        if (!url.equals(found.path("url").asText()) || !(url + ".tbi").equals(found.path("indexUrl").asText())
                || !("chr" + chromosome).equals(found.path("queryContig").asText()) || !found.path("rawResponse").isTextual()) return false;
        String raw = found.path("rawResponse").asText();
        if (raw.length() > MAX_OUTPUT_BYTES) return false;
        if (!GnomadQueryRunner.queryIdentity(raw).equals(found.path("rawResponseSha256").asText())
                || raw.getBytes(StandardCharsets.UTF_8).length != found.path("rawResponseByteLength").asInt(-1)) return false;
        ObjectNode parsed = parse(mapper, allele, raw);
        if (!"VERIFIED_PASS".equals(parsed.path("status").asText())) return false;
        for (var field : parsed.properties()) {
            if (!field.getValue().equals(found.path(field.getKey()))) return false;
        }
        return true;
    }

    private ObjectNode target(String subtype, JsonNode allele) {
        String target = url(subtype).replace("{chromosome}", chromosome(allele));
        return mapper.createObjectNode().put("subtype", subtype).put("version", "4.1")
                .put("url", target).put("indexUrl", target + ".tbi").put("queryContig", "chr" + chromosome(allele));
    }

    private ObjectNode seal(ObjectNode result) {
        return result.put("resultId", "ba1-subtype-sha256-" + canonical(mapper, result));
    }

    private static ObjectNode parse(ObjectMapper mapper, JsonNode allele, String raw) {
        var result = mapper.createObjectNode(); var records = result.putArray("exactRecords");
        if (raw.length() > MAX_OUTPUT_BYTES || raw.getBytes(StandardCharsets.UTF_8).length > MAX_OUTPUT_BYTES || !raw.isEmpty() && !raw.endsWith("\n"))
            return result.put("status", "FAILED").put("reasonCode", "BA1_SUBTYPE_INCOMPLETE_OR_OVERSIZED_OUTPUT");
        int rows = 0;
        for (String physical : raw.split("\n", -1)) {
            if (physical.isEmpty()) continue;
            String line = physical.endsWith("\r") ? physical.substring(0, physical.length() - 1) : physical;
            String[] values = line.split("\t", -1);
            if (++rows > MAX_ROWS || values.length < 8 || !values[1].matches("[1-9][0-9]{0,9}") || line.indexOf('\r') >= 0)
                return result.put("status", "FAILED").put("reasonCode", "BA1_SUBTYPE_MALFORMED_OR_EXCESS_ROWS");
            long pos;
            try { pos = Long.parseLong(values[1]); } catch (NumberFormatException invalid) { return result.put("status", "FAILED").put("reasonCode", "BA1_SUBTYPE_POSITION_INVALID"); }
            if (!("chr" + chromosome(allele)).equals(values[0]) || pos != allele.path("position").asLong()
                    || !allele.path("reference").asText().equals(values[3]) || !allele.path("alternate").asText().equals(values[4])) continue;
            records.addObject().put("rawRecord", line).put("rawRecordSha256", GnomadQueryRunner.queryIdentity(line))
                    .put("hashScope", "UTF8_VCF_RECORD_EXCLUDING_CR_LF").put("filter", values[6]);
        }
        result.put("returnedRows", rows).put("exactRecordCount", records.size());
        if (records.isEmpty()) return result.put("status", "NO_RECORD").put("reasonCode", "BA1_SUBTYPE_EXACT_ALLELE_NOT_FOUND");
        if (records.size() != 1) return result.put("status", "AMBIGUOUS").put("reasonCode", "BA1_SUBTYPE_MULTIPLE_EXACT_RECORDS");
        String filter = records.get(0).path("filter").asText(); result.put("filter", filter);
        return result.put("status", "PASS".equals(filter) ? "VERIFIED_PASS" : "NONPASS")
                .put("reasonCode", "PASS".equals(filter) ? "BA1_SUBTYPE_EXACT_RECORD_PASS" : "BA1_SUBTYPE_FILTER_NOT_PASS");
    }

    private static boolean validAllele(JsonNode allele) {
        if (allele == null || !allele.isObject() || !keys(allele, Set.of("assembly", "contig", "position", "reference", "alternate"))
                || !"GRCh38".equals(allele.path("assembly").asText()) || !allele.path("position").canConvertToInt()
                || !allele.path("position").isIntegralNumber() || allele.path("position").asInt() < 1
                || !allele.path("contig").asText().matches("(?:chr)?(?:[1-9]|1[0-9]|2[0-2]|X|Y)")) return false;
        String ref = allele.path("reference").asText(), alt = allele.path("alternate").asText();
        return ref.matches("[ACGT]{1,50}") && alt.matches("[ACGT]{1,50}") && !ref.equals(alt)
                && (ref.length() == 1 && alt.length() == 1 || Math.min(ref.length(), alt.length()) == 1 && ref.charAt(0) == alt.charAt(0));
    }

    private static boolean validPolicy(JsonNode policy) {
        if (policy == null || !policy.isObject() || !keys(policy, Set.of("schemaVersion", "policyId", "version", "assembly", "jointResourceVersion", "review", "sources"))
                || !"ba1-native-subtype-source-policy-v1".equals(policy.path("schemaVersion").asText())
                || !"GNOMAD_V4_1_NATIVE_SUBTYPE_FILTER".equals(policy.path("policyId").asText())
                || !"1.0.0".equals(policy.path("version").asText()) || !"GRCh38".equals(policy.path("assembly").asText())
                || !policy.path("jointResourceVersion").isTextual() || !"4.1".equals(policy.path("jointResourceVersion").asText())
                || !keys(policy.path("review"), Set.of("status", "reviewId", "receiptSha256"))
                || !"REVIEWED".equals(policy.at("/review/status").asText()) || policy.at("/review/reviewId").asText().isBlank()
                || !"A01-BA1-NATIVE-SUBTYPE-QC-SUPPLEMENT-20260914-01".equals(policy.at("/review/reviewId").asText())
                || !"3003b79b6db02b7715590c8583de39a460876e3ca4aec6ca5d088ad54da72585".equals(policy.at("/review/receiptSha256").asText())
                || !policy.path("sources").isArray() || policy.path("sources").size() != 2) return false;
        var seen = new HashSet<String>();
        for (var source : policy.path("sources")) {
            String subtype = source.path("subtype").asText();
            if (!SUBTYPES.contains(subtype) || !seen.add(subtype) || !keys(source, Set.of("subtype", "version", "dataUrlTemplate", "indexUrlTemplate", "headerSha256", "headerProbeChromosome", "headerHashScope"))
                    || !source.path("version").isTextual() || !"4.1".equals(source.path("version").asText()) || !url(subtype).equals(source.path("dataUrlTemplate").asText())
                    || !(url(subtype) + ".tbi").equals(source.path("indexUrlTemplate").asText())
                    || !"1".equals(source.path("headerProbeChromosome").asText())
                    || !"CHR1_HEADER_ONLY_NOT_WHOLE_DATASET".equals(source.path("headerHashScope").asText())
                    || !("exomes".equals(subtype) ? "66875ac50f53b8f491c263394fc85e17308c639bae4b7d5daa43fea6f773ca83"
                    : "7ce96bf41d60770cb7b97753b5f761a2fd3f66a0fd14d35d63a68dd3cf318097").equals(source.path("headerSha256").asText())) return false;
        }
        return true;
    }

    private static boolean keys(JsonNode node, Set<String> expected) {
        if (!node.isObject() || node.size() != expected.size()) return false;
        var actual = new HashSet<String>(); node.fieldNames().forEachRemaining(actual::add); return actual.equals(expected);
    }
    private static String chromosome(JsonNode allele) { return allele.path("contig").asText().replaceFirst("^chr", ""); }
    private static String url(String subtype) { return "https://storage.googleapis.com/gcp-public-data--gnomad/release/4.1/vcf/" + subtype + "/gnomad." + subtype + ".v4.1.sites.chr{chromosome}.vcf.bgz"; }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String canonical(ObjectMapper mapper, JsonNode value) { return new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(value); }
}
