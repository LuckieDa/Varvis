package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Source explanations only. Cannot select, approve or replace a PVS1 policy. */
public final class Pvs1ApplicabilitySources {
    public static final String VERSION = "1.0.0";
    private final ObjectMapper mapper;
    private final ObjectNode identity;
    private final Map<String, ObjectNode> specifications = new HashMap<>();
    private final Map<String, List<ObjectNode>> entries = new HashMap<>();

    public static Pvs1ApplicabilitySources load(ObjectMapper mapper, Path file, String sha, String mechanismSha) throws IOException {
        require(sha.matches("[0-9a-f]{64}") && Files.isRegularFile(file) && Files.size(file) <= 64_000_000,
                "PVS1_APPLICABILITY_FILE_INVALID");
        byte[] raw;
        try (var in = Files.newInputStream(file)) { raw = in.readNBytes(64_000_001); }
        require(raw.length <= 64_000_000 && sha.equals(CodingTranscriptMaterialSupply.sha(raw)), "PVS1_APPLICABILITY_RAW_SHA_MISMATCH");
        var strict = mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return new Pvs1ApplicabilitySources(mapper, strict.readTree(raw), sha, mechanismSha);
    }

    Pvs1ApplicabilitySources(ObjectMapper mapper, JsonNode doc, String sha, String mechanismSha) {
        this.mapper = mapper;
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        require("pvs1-applicability-sources-v1".equals(doc.path("schemaVersion").asText())
                && VERSION.equals(doc.path("methodVersion").asText()) && "SOURCE_FACTS_ONLY".equals(doc.path("status").asText())
                && isFalse(doc.path("runtimeEligible")) && isFalse(doc.path("clinicalAcceptance"))
                && mechanismSha.equals(doc.path("mechanismCandidatesRawSha256").asText())
                && doc.path("specifications").isArray() && doc.path("specifications").size() <= 2_000
                && doc.path("entries").isArray() && doc.path("entries").size() <= 20_000,
                "PVS1_APPLICABILITY_SOURCE_INVALID");
        for (var s : doc.path("specifications")) {
            var original = s.path("original");
            String key = original.path("entId").asText() + "@" + original.at("/entContent/version").asText();
            var states = new ArrayList<String>();
            for (var state : original.at("/entContent/states")) if (state.path("current").asBoolean()) states.add(state.path("name").asText());
            require(s instanceof ObjectNode && key.equals(s.path("specificationKey").asText())
                    && original.path("entId").asText().matches("GN[0-9]+") && states.equals(List.of("Released"))
                    && adapter.canonicalSha256(original).equals(s.path("entitySha256").asText())
                    && s.at("/source/rawSha256").asText().matches("[0-9a-f]{64}")
                    && s.at("/source/jsonPointer").asText().matches("/data(?:/[0-9]+)?")
                    && s.at("/source/sourceUrl").asText().startsWith("https://")
                    && isFalse(s.path("runtimeEligible")) && !specifications.containsKey(key),
                    "PVS1_APPLICABILITY_SPECIFICATION_INVALID");
            specifications.put(key, ((ObjectNode) s).deepCopy());
        }
        Set<String> ids = new HashSet<>();
        for (var e : doc.path("entries")) {
            var spec = specifications.get(e.path("specificationKey").asText());
            if (!(e instanceof ObjectNode && spec != null && e.path("entryId").asText().startsWith("pvs1-source-")
                    && ids.add(e.path("entryId").asText()))) {
                throw new IllegalArgumentException("PVS1_APPLICABILITY_ENTRY_INVALID");
            }
            ObjectNode body = ((ObjectNode) e).deepCopy(); body.remove("entryId");
            require(e.path("entryId").asText().equals("pvs1-source-" + adapter.canonicalSha256(body)), "PVS1_APPLICABILITY_ENTRY_HASH_MISMATCH");
            var original = spec.path("original");
            String cp = e.path("clausePointer").asText(), sp = e.path("scopePointer").asText();
            require(cp.matches("/ld/CriteriaCode/[0-9]+")
                    && sp.matches("/ld/RuleSet/[0-9]+/entContent/genes/[0-9]+/diseases/[0-9]+"), "PVS1_APPLICABILITY_POINTER_INVALID");
            var clause = original.at(cp); var disease = original.at(sp);
            var gene = original.at(sp.substring(0, sp.lastIndexOf("/diseases/")));
            String symbol = gene.path("gene").asText(), hgnc = "";
            for (var g : original.at("/ld/Gene")) if (symbol.equals(g.at("/entContent/HGNC/symbol").asText())) {
                String found = g.at("/entContent/HGNC/hgnc_id").asText();
                require(hgnc.isEmpty() || hgnc.equals(found), "PVS1_APPLICABILITY_GENE_AMBIGUOUS"); hgnc = found;
            }
            String rawMoi = disease.path("preferredModeOfInheritance").asText().strip(), moi = moi(rawMoi);
            String status = moi.isEmpty() ? rawMoi.isEmpty() ? "INHERITANCE_NOT_PROVIDED" : "INHERITANCE_NOT_MAPPED" : "EXACT";
            if (!hgnc.matches("HGNC:[1-9][0-9]*") || !disease.path("preferredMondoId").asText().matches("MONDO:[0-9]{7}")) status = "GENE_OR_DISEASE_UNRESOLVED";
            var cg = clause.at("/entContent/gene"); var cd = clause.at("/entContent/disease");
            boolean clauseScope = contains(cg, symbol) && (cd.isMissingNode() || cd.isArray() && (cd.isEmpty() || contains(cd, disease.path("preferredMondoId").asText())));
            if (!clauseScope) status = "CLAUSE_SCOPE_UNRESOLVED";
            String relation = !clauseScope ? "UNRESOLVED" : cd.isMissingNode() ? "RULESET_DISEASE_CONTEXT_ONLY"
                    : cd.isEmpty() ? "EXPLICIT_EMPTY_DISEASE_SCOPE" : "EXPLICIT_GENE_AND_DISEASE";
            require("PVS1".equals(clause.at("/entContent/label").asText())
                    && adapter.canonicalSha256(clause).equals(e.path("clauseSha256").asText())
                    && gene.equals(e.path("rawGeneAssociation")) && symbol.equals(e.path("geneSymbol").asText())
                    && hgnc.equals(e.at("/scope/hgncId").asText()) && disease.path("preferredMondoId").asText().equals(e.at("/scope/mondoId").asText())
                    && moi.equals(e.at("/scope/moi").asText()) && status.equals(e.path("scopeStatus").asText())
                    && relation.equals(e.path("clauseScopeRelation").asText()),
                    "PVS1_APPLICABILITY_SCOPE_MISMATCH");
            entries.computeIfAbsent(hgnc + "|" + disease.path("preferredMondoId").asText(), k -> new ArrayList<>()).add(((ObjectNode) e).deepCopy());
        }
        identity = mapper.createObjectNode().put("schemaVersion", "pvs1-applicability-source-binding-v1").put("version", VERSION)
                .put("rawSha256", sha).put("mechanismCandidatesRawSha256", mechanismSha).put("sourceScope", "PINNED_HISTORICAL_SNAPSHOT")
                .put("specifications", specifications.size()).put("associations", ids.size()).put("runtimeEligible", false);
    }

    public ObjectNode identity() { return identity.deepCopy(); }

    public ObjectNode describe(JsonNode context) {
        var out = mapper.createObjectNode().put("schemaVersion", "pvs1-applicability-source-explanation-v1")
                .put("runtimeEligible", false).put("clinicalAcceptance", false)
                .put("interpretation", "SOURCE_DETAILS_ONLY_NOT_EXECUTABLE_POLICY");
        out.set("binding", identity()); var details = out.putArray("details");
        String key = context.at("/gene/hgncId").asText() + "|" + context.at("/disease/mondoId").asText();
        for (var entry : entries.getOrDefault(key, List.of())) {
            String relation = !"EXACT".equals(entry.path("scopeStatus").asText()) ? "SCOPE_REQUIRES_INTERPRETATION"
                    : context.path("moi").asText().equals(entry.at("/scope/moi").asText()) ? "EXACT_GENE_DISEASE_INHERITANCE" : "DIFFERENT_INHERITANCE_NOT_APPLICABLE";
            var item = details.addObject().put("relation", relation);
            item.set("entry", entry.deepCopy());
            // Full source graph retains global/other-clause restrictions and attachment references.
            item.set("specification", specifications.get(entry.path("specificationKey").asText()).deepCopy());
        }
        return out.put("status", details.isEmpty() ? "NO_MATCH_IN_LOADED_SPECIALIST_SNAPSHOT" : "SOURCE_DETAILS_REQUIRE_INTERPRETATION");
    }

    private static String moi(String raw) { return switch (raw) {
        case "Autosomal dominant inheritance", "Autosomal Dominant" -> "AUTOSOMAL_DOMINANT";
        case "Autosomal recessive inheritance", "Autosomal Recessive" -> "AUTOSOMAL_RECESSIVE";
        default -> "";
    }; }
    private static boolean contains(JsonNode array, String text) { if (array.isArray()) for (var n : array) if (n.isTextual() && text.equals(n.asText())) return true; return false; }
    private static boolean isFalse(JsonNode n) { return n.isBoolean() && !n.asBoolean(); }
    private static void require(boolean ok, String reason) { if (!ok) throw new IllegalArgumentException(reason); }
}
