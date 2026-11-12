package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;

/** Resolves the released CSpec policy before any prediction score is acquired. */
public final class PublishedPredictionCatalog {
    private static final String RASOPATHY_SPECTRUM = "MONDO:0021060";
    private static final String REVEL_PP3 = "For missense variants: REVEL ≥ 0.7. For splicing impact, predicted outcome must match disease mechanism.";
    private static final String REVEL_BP4 = "For missense variants: REVEL ≤0.3.";
    private static final String REVEL_BP4_INSTRUCTIONS = REVEL_BP4
            + " For splicing variants: predicted outcome is negligible or does not match disease mechanism.";
    private static final String OUTSIDE = "OUTSIDE_EXPLICIT_SCOPE";
    private final ObjectMapper mapper;
    private final Path root;
    private final JsonNode index;
    private final ObjectNode identity;
    private final Map<String, Set<String>> parents;
    private final Ba1ManeTranscriptPairs mane;
    private final Map<String, JsonNode> originals = new java.util.concurrent.ConcurrentHashMap<>();

    public static PublishedPredictionCatalog load(ObjectMapper mapper, Path index, String indexSha, Path mondo,
            String mondoSha, Path maneDirectory) throws IOException {
        var contents = checkedBytes(index, indexSha, 8L * 1024 * 1024);
        var document = strict(mapper).readTree(contents);
        require(document != null && "varvis-clingen-cspec-source-library-v1".equals(document.path("schemaVersion").asText())
                && document.path("specifications").isObject() && !document.path("specifications").isEmpty()
                && document.path("specifications").size() == document.at("/counts/specifications").asInt(-1)
                && document.at("/lookup/byHgncId").isObject(), "PREDICTION_RELEASE_INDEX_INVALID");
        var identity = mapper.createObjectNode().put("catalogSha256", indexSha).put("mondoSha256", mondoSha)
                .put("snapshotDate", document.path("snapshotDate").asText()).put("scopeSelectionVersion", "1.0.0");
        var mane = Ba1ManeTranscriptPairs.load(maneDirectory);
        identity.set("transcriptPairSource", mane.identity(mapper));
        return new PublishedPredictionCatalog(strict(mapper), index.toRealPath().getParent(), document, identity,
                loadParents(mondo, mondoSha), mane);
    }

    private PublishedPredictionCatalog(ObjectMapper mapper, Path root, JsonNode index, ObjectNode identity,
            Map<String, Set<String>> parents, Ba1ManeTranscriptPairs mane) {
        this.mapper = mapper;
        this.root = root;
        this.index = index;
        this.identity = identity;
        this.parents = parents;
        this.mane = mane;
    }

    public ObjectNode identity() { return identity.deepCopy(); }

    public ObjectNode select(JsonNode context) {
        var result = identity();
        String hgnc = context.at("/gene/hgncId").asText();
        var matches = new ArrayList<ObjectNode>();
        var checks = result.putArray("scopeChecks");
        var keys = new HashSet<String>();
        for (var indexed : index.path("lookup").path("byHgncId").path(hgnc)) {
            String key = indexed.path("specificationKey").asText();
            if (!keys.add(key)) continue;
            var spec = index.path("specifications").path(key);
            var check = checks.addObject().put("specificationKey", key);
            check.set("source", spec.path("releaseSource").deepCopy());
            try {
                var original = originals.computeIfAbsent(key, ignored -> original(key, spec));
                scope(context, original, check);
                if (!OUTSIDE.equals(check.path("relation").asText())) matches.add(check);
            } catch (IllegalArgumentException unavailable) {
                check.put("relation", "PREDICTION_PUBLISHED_SOURCE_UNAVAILABLE");
                matches.add(check);
            }
        }
        if (matches.isEmpty()) return result.put("status", "NO_KNOWN_SPECIALIZATION_IN_LOCAL_SNAPSHOT")
                .put("reasonCode", "PREDICTION_PUBLISHED_SCOPE_SEARCH_COMPLETE");
        if (matches.size() != 1) return result.put("status", "BLOCKED").put("reasonCode", "PREDICTION_PUBLISHED_SCOPE_AMBIGUOUS");
        var selected = matches.get(0);
        result.set("selected", selected.deepCopy());
        if (selected.has("revelThresholds")) return result.put("status", "PUBLISHED_REVEL_MISSENSE")
                .put("reasonCode", "PREDICTION_PUBLISHED_REVEL_SELECTED");
        return result.put("status", "BLOCKED").put("reasonCode", selected.path("relation").asText());
    }

    private JsonNode original(String key, JsonNode entry) {
        var release = entry.path("releaseSource");
        String id = entry.path("specificationId").asText();
        String version = entry.path("version").asText();
        String expectedFile = "specifications/" + id + "/" + version + "/published-source.json";
        require(id.matches("GN[0-9]{3,5}") && version.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")
                && key.equals(id + "@" + version) && expectedFile.equals(release.path("file").asText())
                && "/data".equals(release.path("jsonPointer").asText()), "PREDICTION_RELEASE_INDEX_INVALID");
        try {
            var path = root.resolve(expectedFile).toRealPath();
            require(path.startsWith(root), "PREDICTION_RELEASE_SOURCE_PATH_INVALID");
            var document = mapper.readTree(checkedBytes(path, release.path("sha256").asText(), 8L * 1024 * 1024));
            var source = document == null ? null : document.path("data");
            require(source != null && id.equals(source.path("entId").asText())
                    && normalizedVersion(version).equals(normalizedVersion(source.at("/entContent/version").asText()))
                    && released(source) && source.at("/ld/Gene").isArray()
                    && source.at("/ld/RuleSet").isArray() && source.at("/ld/CriteriaCode").isArray(),
                    "PREDICTION_RELEASE_SOURCE_IDENTITY_INVALID");
            return source;
        } catch (IOException unavailable) {
            throw new IllegalArgumentException("PREDICTION_RELEASE_SOURCE_UNAVAILABLE", unavailable);
        }
    }

    private void scope(JsonNode context, JsonNode original, ObjectNode check) {
        String hgnc = context.at("/gene/hgncId").asText();
        String symbol = context.at("/gene/symbol").asText();
        String disease = context.at("/disease/mondoId").asText();
        String moi = context.path("moi").asText();
        check.put("relation", "PREDICTION_PUBLISHED_SCOPE_UNRESOLVED");
        long genes = original.at("/ld/Gene").valueStream().filter(gene -> hgnc.equals(gene.at("/entContent/HGNC/hgnc_id").asText())
                && symbol.equals(gene.at("/entContent/HGNC/symbol").asText())).count();
        if (genes != 1 || !disease.matches("MONDO:[0-9]{7}")) return;
        var transcripts = new HashSet<String>();
        boolean knownScope = false;
        for (var rule : original.at("/ld/RuleSet")) {
            for (var gene : rule.at("/entContent/genes")) {
                if (!symbol.equals(gene.path("gene").asText())) continue;
                if (!gene.path("diseases").isArray() || gene.path("diseases").isEmpty()) return;
                for (var item : gene.path("diseases")) {
                    String publishedDisease = item.path("preferredMondoId").asText();
                    if (!publishedDisease.matches("MONDO:[0-9]{7}")) return;
                    knownScope = true;
                    if (!disease.equals(publishedDisease)) {
                        if (!parents.containsKey(disease) || !parents.containsKey(publishedDisease)) return;
                        if (descendant(publishedDisease, disease)) return;
                        if (!descendant(disease, publishedDisease)) continue;
                        // The RASopathy release explicitly covers the spectrum. Other
                        // parent terms need the panel's own scope before extending them.
                        if (!RASOPATHY_SPECTRUM.equals(publishedDisease)) return;
                    }
                    Set<String> modes = modes(item);
                    if (modes.isEmpty() || !Set.of("AUTOSOMAL_DOMINANT", "AUTOSOMAL_RECESSIVE",
                            "X_LINKED", "X_LINKED_DOMINANT", "X_LINKED_RECESSIVE").contains(moi)) return;
                    if (!modes.contains(moi) && !(moi.startsWith("X_LINKED") && modes.stream().anyMatch(mode -> mode.startsWith("X_LINKED")))) continue;
                    if (!modes.contains(moi)) return;
                    transcripts.add(gene.path("preferredTranscript").asText());
                    check.put("publishedDisease", publishedDisease).put("requestedDisease", disease).put("publishedMoi", moi);
                }
            }
        }
        if (!knownScope) return;
        if (transcripts.isEmpty()) {
            check.put("relation", OUTSIDE);
            return;
        }
        if (transcripts.size() != 1 || transcripts.contains("")) return;
        String transcript = transcripts.iterator().next();
        check.put("publishedTranscript", transcript).put("relation", "PREDICTION_PUBLISHED_TRANSCRIPT_MISMATCH");
        if (!matchesTranscript(context, transcript)) return;
        check.put("relation", "PREDICTION_PUBLISHED_CLAUSE_UNSUPPORTED");
        var pp3 = revelClause(original, symbol, check.path("publishedDisease").asText(), true);
        var bp4 = revelClause(original, symbol, check.path("publishedDisease").asText(), false);
        if (pp3 == null || bp4 == null) return;
        check.put("relation", "PREDICTION_PUBLISHED_REVEL_SELECTED");
        var thresholds = check.putObject("revelThresholds");
        thresholds.put("pp3Minimum", "0.7").put("bp4Maximum", "0.3").put("strength", "SUPPORTING");
        thresholds.put("pp3Pointer", pp3).put("bp4Pointer", bp4);
    }

    private boolean matchesTranscript(JsonNode context, String required) {
        String selected = context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asText();
        if (selected.equals(required) && required.matches("(?:NM_|ENST)[0-9]+\\.[1-9][0-9]*")) return true;
        var pair = mane.find(context.at("/gene/hgncId").asText(), selected, context.at("/variant/evidenceQueryAllele/contig").asText());
        return pair != null && pair.refseqTranscript().equals(required);
    }

    @Nullable private static String revelClause(JsonNode original, String symbol, String disease, boolean harmful) {
        var clauses = original.at("/ld/CriteriaCode");
        String label = harmful ? "PP3" : "BP4";
        String pointer = null;
        for (int index = 0; index < clauses.size(); index++) {
            var clause = clauses.get(index).path("entContent");
            if (!label.equals(clause.path("label").asText()) || !appliesTo(clause.path("gene"), symbol)
                    || !appliesTo(clause.path("disease"), disease)) continue;
            if (pointer != null || !"Applicable".equals(clause.path("applicability").asText())
                    || !clause.path("additionalComments").asText().isBlank()
                    || !(harmful ? REVEL_PP3 : REVEL_BP4_INSTRUCTIONS).equals(clause.path("instructionsToUse").asText())) return null;
            var strengths = clause.path("strengthDescriptor");
            for (int i = 0; i < strengths.size(); i++) {
                var strength = strengths.get(i);
                if ("not applicable".equalsIgnoreCase(strength.path("applicability").asText())) continue;
                if (pointer != null || !"Applicable".equals(strength.path("applicability").asText())
                        || !"approved".equals(strength.path("status").asText())
                        || !"Supporting".equals(strength.path("strength").asText())
                        || !strength.path("instructionsToUse").asText().isBlank()
                        || !strength.path("additionalComments").asText().isBlank()
                        || !(harmful ? REVEL_PP3 : REVEL_BP4).equals(strength.path("text").asText())) return null;
                pointer = "/data/ld/CriteriaCode/" + index + "/entContent/strengthDescriptor/" + i;
            }
        }
        return pointer;
    }

    private static boolean appliesTo(JsonNode values, String value) {
        return values.isMissingNode() || values.isArray() && (values.isEmpty()
                || values.valueStream().anyMatch(item -> value.equals(item.asText())));
    }

    private static Set<String> modes(JsonNode disease) {
        var modes = new HashSet<String>();
        if (disease.hasNonNull("preferredModeOfInheritance")) modes.add(mode(disease.path("preferredModeOfInheritance").asText()));
        for (var entry : disease.path("modesOfInheritance")) modes.add(mode(entry.path("modeOfInheritance").asText()));
        return modes.contains("") ? Set.of() : modes;
    }

    private static String mode(String raw) {
        return switch (raw.strip().toLowerCase(Locale.ROOT).replace(" inheritance", "")) {
            case "autosomal dominant" -> "AUTOSOMAL_DOMINANT";
            case "autosomal recessive" -> "AUTOSOMAL_RECESSIVE";
            case "x-linked" -> "X_LINKED";
            case "x-linked dominant" -> "X_LINKED_DOMINANT";
            case "x-linked recessive" -> "X_LINKED_RECESSIVE";
            default -> "";
        };
    }

    private boolean descendant(String child, String ancestor) {
        var visited = new HashSet<String>();
        var pending = new ArrayDeque<>(parents.getOrDefault(child, Set.of()));
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (ancestor.equals(current)) return true;
            if (visited.add(current)) pending.addAll(parents.getOrDefault(current, Set.of()));
        }
        return false;
    }

    private static Map<String, Set<String>> loadParents(Path file, String sha) throws IOException {
        require(Files.size(file) <= 96L * 1024 * 1024, "PREDICTION_MONDO_SIZE_LIMIT");
        var parents = new HashMap<String, Set<String>>();
        var digest = digest();
        try (var stream = new DigestInputStream(Files.newInputStream(file), digest);
                var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            boolean term = false;
            String id = "";
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("[")) {
                    term = "[Term]".equals(line);
                    id = "";
                } else if (term && line.matches("id: MONDO:[0-9]{7}")) {
                    id = line.substring(4);
                    parents.put(id, new HashSet<>());
                } else if (!id.isEmpty() && "is_obsolete: true".equals(line)) {
                    parents.remove(id);
                    id = "";
                } else if (!id.isEmpty() && line.startsWith("is_a: MONDO:") && line.length() >= 19) {
                    String parent = line.substring(6, 19);
                    if (parent.matches("MONDO:[0-9]{7}")) parents.get(id).add(parent);
                }
            }
        }
        require(HexFormat.of().formatHex(digest.digest()).equals(sha) && parents.size() > 1000
                && parents.containsKey(RASOPATHY_SPECTRUM), "PREDICTION_MONDO_IDENTITY_MISMATCH");
        return parents;
    }

    private static boolean released(JsonNode entity) {
        var states = entity.at("/entContent/states").valueStream().filter(state -> state.path("current").asBoolean())
                .map(state -> state.path("name").asText()).toList();
        return states.equals(List.of("Released"));
    }

    private static String normalizedVersion(String version) {
        return version.matches("[0-9]+\\.[0-9]+") ? version + ".0" : version;
    }

    private static byte[] checkedBytes(Path file, String sha, long maximum) throws IOException {
        require(Files.size(file) <= maximum, "PREDICTION_RELEASE_SOURCE_SIZE_LIMIT");
        byte[] bytes = Files.readAllBytes(file);
        require(PredictionCalibration.sha256(bytes).equals(sha), "PREDICTION_RELEASE_SOURCE_IDENTITY_MISMATCH");
        return bytes;
    }

    private static ObjectMapper strict(ObjectMapper mapper) {
        return mapper.copy().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalArgumentException(reason);
    }
}
