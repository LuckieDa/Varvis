package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;
import org.nbscn.varvis.variantanalysis.trio.*;

/** Test-only mutations distinguish reviewed reissuance, stale sources, and outer-only transport pins. */
final class Pm3TestMaterials {
    static final ObjectMapper M = new ObjectMapper();
    static final List<String> PARTS = List.of("facts", "phase", "partnerClassification", "targetRarity", "partnerRarity");
    static byte[] resource(String name) throws Exception {
        try (var stream = Pm3TestMaterials.class.getResourceAsStream("/pm3-in-trans-core/" + name)) {
            if (stream == null) throw new IllegalArgumentException(name); return stream.readAllBytes();
        }
    }
    static ObjectNode base(String tag) throws Exception { return (ObjectNode) M.readTree(resource(tag + "-bundle.json")); }
    static ObjectNode object(JsonNode n, String pointer) { return (ObjectNode) n.at(pointer); }
    static String raw(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    // Independent test mirror. No production canonical/hash function supplies expected pins.
    static JsonNode canonical(JsonNode n) {
        if (n.isTextual()) return M.getNodeFactory().textNode(Normalizer.normalize(n.asText(), Normalizer.Form.NFC));
        if (n.isFloatingPointNumber()) throw new IllegalArgumentException("integer-only test identity");
        if (n.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>(); n.fieldNames().forEachRemaining(key -> sorted.put(key, n.path(key)));
            ObjectNode result = M.createObjectNode(); sorted.forEach((key, value) -> result.set(Normalizer.normalize(key, Normalizer.Form.NFC), canonical(value))); return result;
        }
        if (n.isArray()) { var array = M.createArrayNode(); n.forEach(v -> array.add(canonical(v))); return array; }
        return n.deepCopy();
    }
    static String hash(JsonNode n) throws Exception { return raw(M.writeValueAsBytes(canonical(n))); }
    static ObjectNode observation(ObjectNode root) { return object(root, "/observations/0"); }
    static ObjectNode facts(ObjectNode root) { return object(root, "/observations/0/facts"); }
    static void source(ObjectNode document) throws Exception {
        ObjectNode projection = document.deepCopy(); projection.remove("sources");
        for (JsonNode source : document.path("sources")) {
            String bytes = M.writeValueAsString(projection);
            ((ObjectNode) source).put("rawUtf8", bytes).put("rawSha256", raw(bytes.getBytes(StandardCharsets.UTF_8))).put("canonicalSha256", hash(projection));
        }
    }
    /** Explicit TEST_ONLY re-review, never called in production. Source reports changed only for named parts. */
    static void reissue(ObjectNode root, String... parts) throws Exception {
        for (String part : parts) source(object(observation(root), "/" + part));
        pinsOnly(root);
    }
    /** Bind current documents/assessment/policy without repairing embedded reports. */
    static void pinsOnly(ObjectNode root) throws Exception {
        ObjectNode o = observation(root), a = object(o, "/assessment"), policy = object(root, "/policy");
        for (String part : PARTS) {
            object(a, "/partPins").put(part, hash(o.path(part))); object(policy, "/partPins").put(part, hash(o.path(part)));
        }
        a.remove("contentSha256"); a.put("contentSha256", hash(a)); policy.put("assessmentSha256", hash(a)); catalogPin(root);
    }
    static void catalogPin(ObjectNode root) throws Exception {
        ObjectNode p = object(root, "/policy");
        object(root, "/catalog/entries/0/policyBinding").put("policySha256", hash(p));
        root.put("catalogSha256", hash(root.path("catalog")));
    }
    static Pm3InTransBundle load(ObjectNode root) throws Exception {
        byte[] bytes = M.writeValueAsBytes(root); return Pm3InTransBundle.load(M, bytes, raw(bytes), hash(root));
    }
    static ObjectNode evaluate(ObjectNode root) throws Exception { return new Pm3InTransCriterionEvaluator(M).evaluate(load(root)); }
    static void artifact(String name, ObjectNode input, ObjectNode result) throws Exception {
        String destination = System.getProperty("varvis.ps2.artifacts");
        if (destination == null) return;
        Path directory = Path.of(destination, "pm3", name); Files.createDirectories(directory);
        Files.write(directory.resolve("input.json"), M.writerWithDefaultPrettyPrinter().writeValueAsBytes(input));
        Files.write(directory.resolve("loader-bytes.json"), M.writeValueAsBytes(input));
        Files.write(directory.resolve("output.json"), M.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
        Files.writeString(directory.resolve("pins.json"), M.writeValueAsString(M.createObjectNode().put("rawSha256", raw(M.writeValueAsBytes(input))).put("canonicalSha256", hash(input))));
    }
    static ObjectNode throughAdapter(ObjectNode facts) {
        List<TrioVcfGenotypeRecord> records = new ArrayList<>();
        for (JsonNode r : facts.path("records")) {
            JsonNode key = r.path("variantKey"), g = r.path("observation");
            TrioRole role = TrioRole.valueOf(r.path("role").asText());
            var genotype = new TrioGenotypeObservation(role, g.path("genotype").asText(), TrioGenotypeCall.valueOf(g.path("call").asText()),
                    g.path("depth").isNull() ? null : g.path("depth").intValue(), g.path("genotypeQuality").isNull() ? null : g.path("genotypeQuality").intValue(),
                    g.path("alleleDepth").asText(), g.path("phased").asBoolean());
            records.add(new TrioVcfGenotypeRecord(new TrioVcfVariantKey(key.path("assembly").asText(), key.path("chromosome").asText(), key.path("position").longValue(), key.path("reference").asText(), key.path("alternate").asText()),
                    role, r.path("sampleId").asText(), r.path("geneSymbol").asText(), r.path("recordFilter").asText(), r.path("sampleFilter").asText(), r.path("rawSampleValue").asText(), genotype));
        }
        return new CaseFamilyObservationAdapter(M).adapt(facts, records).snapshot();
    }
    static void call(ObjectNode record, String gt) {
        ObjectNode g = object(record, "/observation");
        boolean het = gt.charAt(0) != gt.charAt(2); int dp = g.path("depth").intValue();
        String ad = het ? (dp / 2) + "," + (dp / 2) : gt.charAt(0) == '0' ? dp + ",0" : "0," + dp;
        g.put("genotype", gt).put("phased", gt.contains("|")).put("call", het ? "HET_ALT" : gt.charAt(0) == '0' ? "HOM_REF" : "HOM_ALT").put("alleleDepth", ad);
        record.put("rawSampleValue", gt + ":" + dp + ":" + g.path("genotypeQuality").asText() + ":" + ad + ":PASS");
    }
    /** New TEST_ONLY family issuance for explicit scope probes, not a repair of stale-pin tests. */
    static void rebindFamily(ObjectNode root) throws Exception {
        ObjectNode f = facts(root), context = object(root, "/context"), binding = object(f, "/binding");
        for (String role : List.of("target", "partner")) {
            ObjectNode variant = object(f, "/alleles/" + role), normalizer = object(variant, "/normalization").deepCopy(); normalizer.remove("status");
            ObjectNode projection = M.createObjectNode(); projection.set("normalizedAllele", variant.path("normalizedAllele")); projection.set("normalizer", normalizer);
            variant.put("alleleId", "allele-sha256-" + hash(projection)); binding.set(role + "AlleleId", variant.path("alleleId"));
        }
        context.set("variant", f.at("/alleles/target").deepCopy());
        ObjectNode cp = M.createObjectNode().put("domain", "varvis-evidence-context-v3").put("schemaVersion", "evidence-context-v3");
        for (String field : List.of("dataClass", "scope", "moi", "patientContext")) cp.set(field, context.path(field));
        cp.set("alleleId", context.at("/variant/alleleId")); cp.set("hgncId", context.at("/gene/hgncId")); cp.set("mondoId", context.at("/disease/mondoId"));
        context.put("contextId", "context-sha256-" + hash(cp)); binding.set("contextId", context.path("contextId")); binding.set("moi", context.path("moi"));
        var ids = new ArrayList<>(List.of(binding.path("targetAlleleId").asText(), binding.path("partnerAlleleId").asText())); java.util.Collections.sort(ids);
        binding.put("pairId", "pair-sha256-" + hash(M.valueToTree(ids)));
        for (String part : PARTS) object(observation(root), "/" + part).set("binding", binding.deepCopy());
        for (String array : List.of("detections", "parentage")) for (JsonNode n : f.path(array)) ((ObjectNode) n).set("binding", binding.deepCopy());
        object(f, "/clinical").set("binding", binding.deepCopy());
        for (int i = 0; i < 6; i++) {
            String role = i % 2 == 0 ? "target" : "partner"; ObjectNode key = object(f, "/alleles/" + role + "/normalizedAllele").deepCopy(); key.set("chromosome", key.remove("contig"));
            object(f, "/records/" + i).set("variantKey", key);
        }
        for (String role : List.of("target", "partner")) object(observation(root), "/" + role + "Rarity").set("alleleId", binding.path(role + "AlleleId"));
        object(observation(root), "/partnerClassification").set("alleleId", binding.path("partnerAlleleId"));
        for (String field : List.of("targetAlleleId", "pairId", "caseId")) object(observation(root), "/partnerClassification/independence").set(field, binding.path(field));
        reissue(root, PARTS.toArray(String[]::new));
    }
}
