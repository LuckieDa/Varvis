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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Disease identity only. Does not establish inheritance, classification or independent evidence. */
public final class ClinVarConditionIdentity {
    public static final String VERSION = "clinvar-condition-identity-v1";
    private static final String MONDO_ID = "MONDO:\\d{7}";
    private static final Pattern ID = Pattern.compile(
            "(?<![\\w:])((?:MONDO:)?MONDO:\\d{7}|OMIM:\\d{6}|MEDGEN:(?:C\\d{7}|CN\\d+|\\d+)|UMLS:C\\d{7}|ORPHANET:\\d+|DOID:\\d+)(?![\\w])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REPORTED_CUI = Pattern.compile("(?:^|[|;])\\s*(C\\d{7})(?=[:|;]|$)");
    private static final Pattern EQUIVALENT = Pattern.compile("^xref: (\\S+) \\{[^}]*source=\"MONDO:equivalentTo\"[^}]*}.*$");
    private final Map<String, List<Mapping>> equivalents;
    private final Set<String> activeTerms;
    private final String sha;
    private final String release;
    private final String unavailableReason;

    private ClinVarConditionIdentity(Map<String, List<Mapping>> equivalents, Set<String> activeTerms,
            String sha, String release, String unavailableReason) {
        this.equivalents = equivalents;
        this.activeTerms = activeTerms;
        this.sha = sha;
        this.release = release;
        this.unavailableReason = unavailableReason;
    }

    public static ClinVarConditionIdentity unavailable(String reason) {
        return new ClinVarConditionIdentity(Map.of(), Set.of(), "", "", reason);
    }

    public static ClinVarConditionIdentity load(Path file, String expectedSha) throws IOException {
        require(expectedSha.matches("[0-9a-f]{64}"), "CONDITION_MAPPING_PIN_REQUIRED");
        require(Files.size(file) <= 96L * 1024 * 1024, "CONDITION_MAPPING_SIZE_LIMIT");
        var digest = digest();
        var equivalents = new HashMap<String, List<Mapping>>();
        var active = new HashSet<String>();
        var seen = new HashSet<String>();
        String release = "";
        Term term = new Term();
        int number = 0;
        try (var stream = new DigestInputStream(Files.newInputStream(file), digest);
                var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                number++;
                if (line.startsWith("data-version: releases/")) release = line.substring("data-version: releases/".length());
                if (line.startsWith("[")) {
                    term.finish(equivalents, active, seen);
                    term = new Term();
                    term.isTerm = "[Term]".equals(line);
                } else if (term.isTerm) term.read(line, number);
            }
        }
        term.finish(equivalents, active, seen);
        require(HexFormat.of().formatHex(digest.digest()).equals(expectedSha), "CONDITION_MAPPING_IDENTITY_MISMATCH");
        require(release.matches("\\d{4}-\\d{2}-\\d{2}") && !active.isEmpty(), "CONDITION_MAPPING_FORMAT_INVALID");
        equivalents.replaceAll((key, value) -> List.copyOf(value));
        return new ClinVarConditionIdentity(Map.copyOf(equivalents), Set.copyOf(active), expectedSha, release, "");
    }

    public ObjectNode identity(ObjectMapper mapper) {
        var out = mapper.createObjectNode().put("schemaVersion", VERSION)
                .put("status", sha.isEmpty() ? "UNAVAILABLE" : "READY");
        if (sha.isEmpty()) return out.put("reasonCode", unavailableReason);
        return out.put("sha256", sha).put("release", release).put("method", "MONDO_EXPLICIT_EQUIVALENT_XREF")
                .put("sourceUrl", "https://github.com/monarch-initiative/mondo/releases/download/v" + release + "/mondo.obo");
    }

    ObjectNode resolve(ObjectMapper mapper, JsonNode condition, String targetMondo) {
        var out = mapper.createObjectNode().put("status", "UNVERIFIED");
        if (!targetMondo.matches(MONDO_ID)) return out.put("reasonCode", "TARGET_DISEASE_ID_MISSING");
        Set<String> ids = new LinkedHashSet<>();
        for (String key : List.of("submittedRaw", "reportedRaw")) {
            String raw = condition.path(key).asText();
            if (raw.length() > 4096) return out.put("reasonCode", "CONDITION_TEXT_LIMIT");
            var found = ID.matcher(raw);
            while (found.find()) ids.add(normalize(found.group(1)));
            if ("reportedRaw".equals(key)) {
                var cuis = REPORTED_CUI.matcher(raw);
                while (cuis.find()) ids.add("UMLS:" + cuis.group(1));
            }
        }
        var matches = out.putArray("mappings");
        var unknown = out.putArray("unresolvedIds");
        Set<String> resolved = new LinkedHashSet<>();
        boolean ambiguous = false;
        for (String id : ids) {
            List<Mapping> mappings = lookup(id);
            Set<String> destinations = new HashSet<>();
            for (var mapping : mappings) {
                destinations.add(mapping.mondo());
                resolved.add(mapping.mondo());
                var match = matches.addObject().put("sourceId", id).put("mondoId", mapping.mondo());
                if (mapping.line() == 0) match.put("relation", "EXPLICIT_MONDO_ID");
                else match.put("relation", "MONDO:equivalentTo").put("sourceLine", mapping.line());
            }
            ambiguous |= destinations.size() > 1;
            if (mappings.isEmpty()) unknown.add(id);
        }
        if (ambiguous) return out.put("reasonCode", "CONDITION_MAPPING_AMBIGUOUS");
        if (resolved.size() > 1) return out.put("reasonCode", "CONDITION_IDENTIFIERS_DISAGREE");
        if (!unknown.isEmpty()) return out.put("reasonCode", sha.isEmpty() ? "CONDITION_MAPPING_UNAVAILABLE" : "CONDITION_IDENTIFIER_UNMAPPED");
        if (resolved.isEmpty()) return out.put("reasonCode", "CONDITION_IDENTIFIER_MISSING");
        String disease = resolved.iterator().next();
        return out.put("status", disease.equals(targetMondo) ? "EXACT" : "DIFFERENT")
                .put("mondoId", disease).put("reasonCode", disease.equals(targetMondo) ? "SAME_DISEASE_IDENTITY" : "DIFFERENT_DISEASE_IDENTITY");
    }

    private List<Mapping> lookup(String id) {
        if (id.matches(MONDO_ID)) return sha.isEmpty() || activeTerms.contains(id) ? List.of(new Mapping(id, 0)) : List.of();
        return equivalents.getOrDefault(id, List.of());
    }

    private static String normalize(String value) {
        String id = value.toUpperCase(Locale.ROOT).replace("MONDO:MONDO:", "MONDO:");
        return id.matches("MEDGEN:C\\d{7}") ? "UMLS:" + id.substring(7) : id;
    }

    private static final class Term {
        private boolean isTerm;
        private boolean obsolete;
        private String id = "";
        private final Map<String, Integer> xrefs = new HashMap<>();

        void read(String line, int number) {
            if (line.matches("id: " + MONDO_ID)) id = line.substring(4);
            if ("is_obsolete: true".equals(line)) obsolete = true;
            var match = EQUIVALENT.matcher(line);
            if (match.matches() && ID.matcher(match.group(1)).matches()) xrefs.putIfAbsent(normalize(match.group(1)), number);
        }

        void finish(Map<String, List<Mapping>> equivalents, Set<String> active, Set<String> seen) {
            if (!isTerm || id.isEmpty()) return;
            require(seen.add(id), "CONDITION_MAPPING_DUPLICATE_TERM");
            if (obsolete) return;
            active.add(id);
            xrefs.forEach((xref, line) -> equivalents.computeIfAbsent(xref, ignored -> new ArrayList<>()).add(new Mapping(id, line)));
        }
    }

    private record Mapping(String mondo, int line) { }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
