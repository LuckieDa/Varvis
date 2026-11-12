package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Replays structured, source-reviewed facts pinned by the selected policy.
 * The configured reviewed bundle is the trust anchor. Candidate extractions and
 * self-declared summaries cannot replace its original scientific source facts.
 * Byte validation alone is not scientific review; this reader creates no review.
 */
final class Pvs1StartLossRuntimeMaterial {
    static final String SCHEMA = "pvs1-start-loss-runtime-material-v1";
    static final String SOURCE_SCHEMA = "pvs1-start-loss-reviewed-source-v1";
    record Verified(String transcriptStatus, boolean transcriptComplete,
                    String upstreamStatus, boolean upstreamComplete,
                    boolean qualifyingIndependentPathogenicReference, int referenceCount,
                    ObjectNode evidenceSummary, @org.springframework.lang.Nullable String reasonCode) { }
    private Pvs1StartLossRuntimeMaterial() { }

    static Verified validate(ObjectMapper mapper, RuntimeEvidenceV2Adapter adapter, JsonNode material,
                             JsonNode context, JsonNode policy, JsonNode sequenceFacts, JsonNode model) {
        if (material == null || !material.isObject()) return invalid("MATERIAL_MISSING");
        String pin = materialSha256(adapter, policy, context);
        if (!pin.matches("[0-9a-f]{64}") || !pin.equals(adapter.canonicalSha256(material)))
            return invalid("MATERIAL_BINDING_MISMATCH");
        if (!SCHEMA.equals(material.path("schemaVersion").asText())
                || !isTrue(material.path("runtimeEligible")) || !isFalse(material.path("clinicalAcceptance")))
            return invalid("MATERIAL_NOT_ELIGIBLE");
        if (!contextIdentitySha256(adapter, context).equals(material.at("/contextBinding/contextSha256").asText()))
            return invalid("CONTEXT_MISMATCH");
        if (!policyIdentitySha256(adapter, policy).equals(material.at("/policyBinding/policySha256").asText()))
            return invalid("POLICY_MISMATCH");
        if (!sequenceMatches(material.path("sequenceFacts"), sequenceFacts)) return invalid("SEQUENCE_MISMATCH");
        JsonNode requirements = policy.at("/startLossAssessment/sourceBindings");
        if (!requirements.isArray() || requirements.isEmpty() || requirements.size() > 32)
            return invalid("SOURCE_REQUIREMENTS_MISSING");
        Map<String, JsonNode> documents = new LinkedHashMap<>();
        Map<String, String> sourcePointers = new LinkedHashMap<>();
        Set<String> sourceIds = new HashSet<>();
        Set<String> upstreamIds = new HashSet<>();
        String transcriptId = null;
        for (JsonNode binding : requirements) {
            String id = binding.path("sourceId").asText(), role = binding.path("role").asText();
            if (id.isBlank() || !sourceIds.add(id) || !Set.of("TRANSCRIPT_REVIEW", "UPSTREAM_REVIEW").contains(role)
                    || !binding.path("sha256").asText().matches("[0-9a-f]{64}")
                    || !binding.path("url").asText().startsWith("https://") || binding.path("reviewId").asText().isBlank())
                return invalid("SOURCE_BINDING_INVALID");
            JsonNode supplied = material.path("sourceDocuments").path(id);
            if (!supplied.path("rawJson").isTextual()) {
                if ("UPSTREAM_REVIEW".equals(role)) { upstreamIds.add(id); continue; }
                return invalid("SOURCE_UNAVAILABLE");
            }
            String rawText = supplied.path("rawJson").asText();
            if (rawText.length() > 4_000_000) return invalid("SOURCE_BYTES_INVALID");
            byte[] raw = rawText.getBytes(StandardCharsets.UTF_8);
            JsonNode document;
            try {
                if (raw.length > 4_000_000 || !binding.path("sha256").asText().equals(
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))))
                    return invalid("SOURCE_BYTES_INVALID");
                try (JsonParser parser = mapper.getFactory().createParser(raw)) {
                    parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
                    document = mapper.readTree(parser);
                    if (document == null || parser.nextToken() != null) return invalid("SOURCE_BYTES_INVALID");
                }
            } catch (Exception invalid) { return invalid("SOURCE_BYTES_INVALID"); }
            if (!SOURCE_SCHEMA.equals(document.path("schemaVersion").asText())
                    || !role.equals(document.path("role").asText())
                    || !binding.path("reviewId").equals(document.path("reviewId")))
                return invalid("SOURCE_FACT_BINDING_MISMATCH");
            String pointer = document.has("contexts") ? "/contexts/" + contextIdentitySha256(adapter, context) : "";
            document = document.at(pointer);
            if (!contextIdentitySha256(adapter, context).equals(document.path("contextSha256").asText())
                    || !sequenceMatches(document.path("sequenceFacts"), sequenceFacts))
                return invalid("SOURCE_FACT_BINDING_MISMATCH");
            documents.put(id, document);
            sourcePointers.put(id, pointer);
            if ("TRANSCRIPT_REVIEW".equals(role)) {
                if (transcriptId != null) return invalid("TRANSCRIPT_REVIEW_AMBIGUOUS");
                transcriptId = id;
            } else upstreamIds.add(id);
        }
        JsonNode transcript = transcriptId == null ? null : documents.get(transcriptId);
        if (transcript == null) return invalid("TRANSCRIPT_REVIEW_INCOMPLETE");
        Set<String> expected = textSet(policy.at("/startLossAssessment/assessedTranscripts")), observed = new HashSet<>();
        if (expected.isEmpty() || !expected.contains(sequenceFacts.path("selectedTranscript").asText())
                || !transcript.path("findings").isArray() || transcript.path("findings").size() > 1000)
            return invalid("TRANSCRIPT_REVIEW_INCOMPLETE");
        ObjectNode evidenceSummary = mapper.createObjectNode();
        var transcriptFindings = evidenceSummary.putArray("transcriptFindings");
        evidenceSummary.putArray("referenceReviews");
        boolean alternative = false, unresolvedTranscript = false;
        int transcriptIndex = 0;
        for (JsonNode finding : transcript.path("findings")) {
            String tx = finding.path("transcript").asText();
            if (!expected.contains(tx) || !observed.add(tx) || !tx.matches("(?:NM_[0-9]+|ENST[0-9]+)\\.[1-9][0-9]*"))
                return invalid("TRANSCRIPT_SET_MISMATCH");
            var findingSummary = transcriptFindings.addObject().put("transcript", tx).put("sourceId", transcriptId)
                    .put("sourceLocator", sourcePointers.get(transcriptId) + "/findings/" + transcriptIndex++);
            for (String field : new String[]{"functionallyRelevant", "differentStart", "targetUnaffected"})
                if (finding.path(field).isBoolean()) findingSummary.set(field, finding.path(field));
            if (tx.equals(sequenceFacts.path("selectedTranscript").asText())) continue;
            // A known shared start excludes rescue regardless of unknown function.
            // Conversely, lack of functional information is not evidence of absence.
            if (finding.path("differentStart").isBoolean() && !finding.path("differentStart").asBoolean()
                    || finding.path("functionallyRelevant").isBoolean() && !finding.path("functionallyRelevant").asBoolean()) continue;
            if (!finding.path("functionallyRelevant").isBoolean() || !finding.path("differentStart").isBoolean()
                    || !finding.path("targetUnaffected").isBoolean()) { unresolvedTranscript = true; continue; }
            alternative |= finding.path("differentStart").asBoolean() && finding.path("targetUnaffected").asBoolean();
        }
        boolean transcriptComplete = observed.equals(expected) && complete(transcript) && !unresolvedTranscript;
        if (alternative) return new Verified("FOUND_FUNCTIONAL_ALTERNATIVE", transcriptComplete, "NOT_REQUIRED", false, false, 0, evidenceSummary, null);
        if (!transcriptComplete) return invalid("TRANSCRIPT_REVIEW_INCOMPLETE");
        JsonNode restart = sequenceFacts.path("candidateRestartCdsPosition");
        if (!restart.isIntegralNumber() || !restart.canConvertToInt() || restart.asInt() <= 3 || (restart.asInt() - 1) % 3 != 0)
            return invalid("RESTART_UNRESOLVED");
        if (upstreamIds.isEmpty()) return invalid("UPSTREAM_REVIEW_INCOMPLETE");
        var references = Pvs1StartLossReferenceQualifier.reviewedSources(mapper, context, model, restart.asInt(),
                documents, sourcePointers, upstreamIds);
        evidenceSummary.set("referenceReviews", references.records());
        if (references.reasonCode() != null) return new Verified("INVALID", false, "INVALID", false, false, 0,
                evidenceSummary, "PVS1_START_LOSS_RUNTIME_" + references.reasonCode());
        return new Verified("NOT_FOUND_IN_ASSESSED_SET", true,
                references.qualifyingCount() == 0 ? "NONE_IN_ASSESSED_SOURCES" : "QUALIFYING_FOUND", references.complete(),
                references.qualifyingCount() > 0, references.qualifyingCount(), evidenceSummary, null);
    }

    private static boolean sequenceMatches(JsonNode bound, JsonNode facts) {
        if (!"AVAILABLE".equals(facts.path("status").asText())) return false;
        for (String field : new String[]{"selectedTranscript", "strand", "cdsPosition", "referenceCodon",
                "alternateCodon", "candidateRestartCdsPosition"})
            if (bound.path(field).isMissingNode() || !bound.path(field).equals(facts.path(field))) return false;
        return true;
    }
    private static boolean isTrue(JsonNode n) { return n.isBoolean() && n.asBoolean(); }
    private static boolean isFalse(JsonNode n) { return n.isBoolean() && !n.asBoolean(); }
    private static boolean complete(JsonNode n) {
        return isTrue(n.path("complete")) && isFalse(n.path("truncated"))
                && n.path("unresolvedCount").isIntegralNumber() && n.path("unresolvedCount").canConvertToLong()
                && n.path("unresolvedCount").asLong(-1) == 0;
    }
    private static Set<String> textSet(JsonNode array) {
        Set<String> values = new HashSet<>();
        if (!array.isArray() || array.size() > 1000) return Set.of();
        for (JsonNode value : array)
            if (!value.isTextual() || value.asText().isBlank() || !values.add(value.asText())) return Set.of();
        return values;
    }
    static String policyIdentitySha256(RuntimeEvidenceV2Adapter adapter, JsonNode policy) {
        if (!(policy instanceof ObjectNode object)) return "";
        ObjectNode projection = object.deepCopy(); projection.remove(java.util.List.of("startLossMaterialBinding", "startLossMaterialBindings"));
        return adapter.canonicalSha256(projection);
    }
    static String materialSha256(RuntimeEvidenceV2Adapter adapter, JsonNode policy, JsonNode context) {
        if (policy.has("startLossMaterialBindings"))
            return policy.path("startLossMaterialBindings").path(contextIdentitySha256(adapter, context)).asText();
        return policy.at("/startLossMaterialBinding/canonicalSha256").asText();
    }
    /** Scientific identity is independent of a run ID, selection UI metadata and patient identifiers. */
    static String contextIdentitySha256(RuntimeEvidenceV2Adapter adapter, JsonNode context) {
        ObjectNode identity = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        ObjectNode allele = identity.putObject("allele");
        for (String field : new String[]{"assembly", "contig", "position", "reference", "alternate"})
            allele.set(field, context.at("/variant/evidenceQueryAllele").path(field).deepCopy());
        identity.set("hgncId", context.at("/gene/hgncId").deepCopy());
        identity.set("mondoId", context.at("/disease/mondoId").deepCopy());
        identity.set("moi", context.path("moi").deepCopy());
        ObjectNode transcript = identity.putObject("transcript");
        transcript.set("accession", context.at("/transcript/accession").deepCopy());
        transcript.set("version", context.at("/transcript/version").deepCopy());
        return adapter.canonicalSha256(identity);
    }
    static Verified invalid(String reason) {
        return new Verified("INVALID", false, "INVALID", false, false, 0,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(), "PVS1_START_LOSS_RUNTIME_" + reason);
    }
}
