package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;

/** Resolves published snapshots by content identity; never rewrites saved run manifests. */
public final class AcmgHistoricalExecutionProfiles {
    private static final String CATALOG = "/acmg/history/execution-profile-history-v1.json";

    private AcmgHistoricalExecutionProfiles() { }

    /** Each predecessor must match its full registered provenance, including its original bytes. */
    public static void validatePredecessors(ObjectMapper mapper, JsonNode bindings) {
        if (bindings == null || !bindings.isArray() || bindings.isEmpty()) throw invalid();
        var catalog = catalog(mapper);
        var seen = new HashSet<String>();
        for (var binding : bindings) {
            var registered = find(catalog.path("profiles"), binding.path("profileId").asText(),
                    binding.path("manifestCanonicalSha256").asText());
            if (!registered.equals(binding)
                    || !seen.add(binding.path("profileId").asText() + ":" + binding.path("manifestCanonicalSha256").asText()))
                throw invalid();
            verifyProfile(mapper, registered);
        }
    }

    /** Uses the static resource's canonical digest, not a saved runtime-enriched manifestSha256. */
    public static ObjectNode load(ObjectMapper mapper, String profileId, String manifestCanonicalSha256) {
        return verifyProfile(mapper, find(catalog(mapper).path("profiles"), profileId, manifestCanonicalSha256));
    }

    /** Historical facts/profile internal paths stay unchanged; resolve their retained bytes by digest. */
    public static ObjectNode loadScientificSnapshot(ObjectMapper mapper, String canonicalSha256) {
        ObjectNode found = null;
        for (var entry : catalog(mapper).path("scientificSnapshots")) {
            if (entry.path("canonicalSha256").asText().equals(canonicalSha256)) {
                if (found != null) throw invalid();
                found = verifyResource(mapper, entry, "canonicalSha256");
            }
        }
        if (found == null) throw invalid();
        return found;
    }

    private static ObjectNode catalog(ObjectMapper mapper) {
        var result = parse(mapper, bytes(CATALOG));
        if (!"acmg-execution-profile-history-v1".equals(result.path("schemaVersion").asText())
                || !"IDENTITY_DISAMBIGUATION_ONLY_NO_SAVED_RESULT_MIGRATION".equals(result.path("purpose").asText())
                || !result.path("profiles").isArray() || result.path("profiles").isEmpty()
                || !result.path("scientificSnapshots").isArray()) throw invalid();
        return result;
    }

    private static JsonNode find(JsonNode entries, String profileId, String digest) {
        JsonNode found = null;
        for (var entry : entries) {
            if (entry.path("profileId").asText().equals(profileId)
                    && entry.path("manifestCanonicalSha256").asText().equals(digest)) {
                if (found != null) throw invalid();
                found = entry;
            }
        }
        if (found == null) throw invalid();
        return found;
    }

    private static ObjectNode verifyProfile(ObjectMapper mapper, JsonNode binding) {
        var document = verifyResource(mapper, binding, "manifestCanonicalSha256");
        if (!document.path("profileId").equals(binding.path("profileId"))
                || !document.path("manifestVersion").equals(binding.path("manifestVersion"))) throw invalid();
        return document;
    }

    private static ObjectNode verifyResource(ObjectMapper mapper, JsonNode binding, String canonicalField) {
        if (!binding.path("sourceCommit").asText().matches("[a-f0-9]{40}")
                || !binding.path("rawSha256").asText().matches("[a-f0-9]{64}")
                || !binding.path(canonicalField).asText().matches("[a-f0-9]{64}")) throw invalid();
        byte[] raw = bytes(binding.path("resourceClasspath").asText());
        if (!binding.path("rawSha256").asText().equals(sha256(raw))) throw invalid();
        var document = parse(mapper, raw);
        if (!binding.path(canonicalField).asText().equals(new RuntimeEvidenceV2Adapter(mapper).canonicalSha256(document)))
            throw invalid();
        return document;
    }

    private static byte[] bytes(String path) {
        if (!path.matches("/acmg/[a-z0-9/._-]+\\.json") || path.contains("..")) throw invalid();
        try (var input = AcmgHistoricalExecutionProfiles.class.getResourceAsStream(path)) {
            if (input == null) throw invalid();
            return input.readAllBytes();
        } catch (IOException unavailable) {
            throw new IllegalArgumentException("ACMG_HISTORICAL_PROFILE_UNAVAILABLE", unavailable);
        }
    }

    private static ObjectNode parse(ObjectMapper mapper, byte[] raw) {
        try {
            var parsed = Objects.requireNonNull(mapper).readTree(raw);
            if (!(parsed instanceof ObjectNode document)) throw invalid();
            return document;
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("ACMG_HISTORICAL_PROFILE_UNREADABLE", unreadable);
        }
    }

    private static String sha256(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("ACMG_HISTORICAL_PROFILE_IDENTITY_INVALID");
    }
}
