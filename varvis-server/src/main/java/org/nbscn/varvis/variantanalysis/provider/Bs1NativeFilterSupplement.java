package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Same-release native FILTER facts; one bounded query per needed dataset, no BA1 review identity. */
public final class Bs1NativeFilterSupplement {
    private final ObjectMapper mapper;
    private final VariantAnalysisProperties settings;
    private final Path temporaryRoot;
    public Bs1NativeFilterSupplement(ObjectMapper mapper, VariantAnalysisProperties settings, Path temporaryRoot) {
        this.mapper = mapper; this.settings = settings; this.temporaryRoot = temporaryRoot;
    }
    public static ObjectNode contract(ObjectMapper mapper) {
        try (var in = Bs1NativeFilterSupplement.class.getResourceAsStream("/acmg/bs1-native-source-v1.json")) {
            if (in == null) throw new IOException("missing source contract");
            return (ObjectNode) mapper.readTree(in);
        } catch (IOException invalid) { throw new IllegalStateException("BS1_NATIVE_SOURCE_UNAVAILABLE", invalid); }
    }
    public ObjectNode supplement(JsonNode observation, Set<String> needs) {
        var result = mapper.createObjectNode();
        for (String subtype : List.of("exomes", "genomes")) if (needs.contains(subtype)) {
            Path work = null; var diagnostics = new ArrayList<Map<String, Object>>();
            try {
                var allele = observation.path("queryAllele");
                var source = source(mapper, subtype);
                String url = source.path("dataUrlTemplate").asText().replace("{chromosome}", allele.path("contig").asText());
                work = Files.createTempDirectory(temporaryRoot, "varvis-bs1-native-");
                Path output = Files.createFile(work.resolve("output.vcf")), error = Files.createFile(work.resolve("stderr.log"));
                Path bed = work.resolve("query.bed");
                Files.writeString(bed, "chr" + allele.path("contig").asText() + "\t" + (allele.path("position").asInt() - 1) + "\t" + allele.path("position").asInt() + "\n");
                var budget = new VariantAnalysisProperties.Gnomad();
                budget.setTimeout(settings.getGnomad().getTimeout()); budget.setRemoteTotalTimeout(settings.getGnomad().getTimeout());
                budget.setRemoteMaxAttempts(1);
                var cache = settings.getGnomad().getRemoteIndexCacheDir();
                if (cache != null) budget.setRemoteIndexCacheDir(cache.toString());
                GnomadQueryRunner.run(List.of(settings.getGnomad().getTabixExecutable(), "--cache", "1", "-R", bed.toString(), url),
                        output, error, true, budget, "gnomad." + subtype + ".v4.1", GnomadQueryRunner.queryIdentity(url + "|" + Files.readString(bed)), 1, diagnostics);
                if (diagnostics.size() != 1 || !"SUCCEEDED".equals(diagnostics.get(0).get("status"))) throw new IOException("incomplete transport");
                byte[] bytes;
                try (var in = Files.newInputStream(output)) { bytes = in.readNBytes(1_048_577); }
                if (bytes.length > 1_048_576) throw new IOException("output limit");
                String raw = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                result.set(subtype, proof(mapper, subtype, observation, raw));
            } catch (IOException | RuntimeException failure) {
                result.putObject(subtype).put("status", "UNAVAILABLE").put("reasonCode", "BS1_NATIVE_QUERY_FAILED")
                        .put("exceptionType", failure.getClass().getSimpleName());
            } finally {
                if (work != null) try {
                    for (String name : List.of("query.bed", "output.vcf", "stderr.log")) Files.deleteIfExists(work.resolve(name));
                    Files.deleteIfExists(work);
                } catch (IOException retained) { result.withObject("/" + subtype).put("cleanupStatus", "INCOMPLETE"); }
            }
            result.withObject("/" + subtype).set("diagnostics", mapper.valueToTree(diagnostics));
        }
        return result;
    }
    static ObjectNode proof(ObjectMapper mapper, String subtype, JsonNode observation, String raw) {
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var value = mapper.createObjectNode().put("schemaVersion", "bs1-native-filter-fact-v1").put("subtype", subtype)
                .put("resourceVersion", "4.1").put("sourceContractSha256", adapter.canonicalSha256(contract(mapper)))
                .put("jointObservationId", observation.path("observationId").asText()).put("raw", raw)
                .put("observedAt", Instant.now().toString());
        value.set("queryAllele", observation.path("queryAllele").deepCopy()); value.set("source", source(mapper, subtype));
        value.put("status", parseStatus(observation.path("queryAllele"), raw));
        value.put("factId", "bs1-native-sha256-" + adapter.canonicalSha256(value));
        return value;
    }
    static String verifiedState(ObjectMapper mapper, JsonNode proof, String subtype, JsonNode observation, JsonNode policy) {
        if (!proof.isObject() || !"bs1-native-filter-fact-v1".equals(proof.path("schemaVersion").asText())
                || !subtype.equals(proof.path("subtype").asText()) || !"4.1".equals(policy.at("/qualityContract/releaseSemantics/version").asText())
                || !proof.path("queryAllele").equals(observation.path("queryAllele"))
                || !proof.path("jointObservationId").equals(observation.path("observationId"))) return "UNRESOLVED";
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var copy = ((ObjectNode) proof).deepCopy(); copy.remove(List.of("factId", "diagnostics", "cleanupStatus"));
        if (!proof.path("factId").asText().equals("bs1-native-sha256-" + adapter.canonicalSha256(copy))
                || !proof.path("sourceContractSha256").asText().equals(adapter.canonicalSha256(contract(mapper)))
                || !proof.path("source").equals(source(mapper, subtype))) return "UNRESOLVED";
        return parseStatus(observation.path("queryAllele"), proof.path("raw").asText());
    }
    private static JsonNode source(ObjectMapper mapper, String subtype) {
        for (var s : contract(mapper).path("sources")) if (subtype.equals(s.path("subtype").asText())) return s;
        throw new IllegalArgumentException("BS1_NATIVE_SUBTYPE_INVALID");
    }
    private static String parseStatus(JsonNode allele, String raw) {
        if (raw.length() > 1_048_576 || !raw.isEmpty() && !raw.endsWith("\n")) return "INCOMPLETE";
        int matches = 0, rows = 0; String filter = null;
        for (String row : raw.split("\n")) {
            if (row.isEmpty()) continue;
            String[] c = row.replaceFirst("\r$", "").split("\t", -1);
            if (++rows > 256 || c.length < 8 || !c[1].matches("[1-9][0-9]*")) return "INCOMPLETE";
            if (c[0].replaceFirst("^chr", "").equals(allele.path("contig").asText()) && c[1].equals(allele.path("position").asText())
                    && c[3].equals(allele.path("reference").asText())) {
                for (String alt : c[4].split(",")) if (alt.equals(allele.path("alternate").asText())) { matches++; filter = c[6]; }
            }
        }
        return matches == 0 ? "NO_RECORD" : matches != 1 ? "AMBIGUOUS" : "PASS".equals(filter) ? "PASS" : "FILTERED";
    }
}
