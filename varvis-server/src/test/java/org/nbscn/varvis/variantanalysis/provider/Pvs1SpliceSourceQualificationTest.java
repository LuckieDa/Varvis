package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual toy source files qualify synthetic fixtures, never public clinical knowledge. */
public class Pvs1SpliceSourceQualificationTest {
    @org.junit.jupiter.api.io.TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    public static ObjectNode adoption(ObjectMapper mapper, ObjectNode facts, List<ObjectNode> policies) {
        return Pvs1SourceQualificationTest.qualified(mapper, facts, policies);
    }

    public static Map<String, byte[]> configureOriginals(ObjectMapper mapper, ObjectNode policy, ObjectNode model, List<ObjectNode> outcomes) {
        var originals = new LinkedHashMap<String, byte[]>();
        var containers = new java.util.ArrayList<JsonNode>();containers.add(policy.path("scientificSources"));containers.add(model.path("sourceRecords"));
        for (var outcome : outcomes) containers.add(outcome.path("sourceRecords"));
        for (var container : containers) for (var source : container) {
            String id = source.path("sourceId").asText();
            byte[] bytes = originals.computeIfAbsent(id, key -> ("TEST_ONLY pinned synthetic original: " + key).getBytes(StandardCharsets.UTF_8));
            ((ObjectNode) source).put("sha256", CodingTranscriptMaterialSupply.sha(bytes));
        }
        var adapter = new RuntimeEvidenceV2Adapter(mapper);String modelPin = adapter.canonicalSha256(model);
        policy.withObject("transcriptModelBinding").put("canonicalSha256", modelPin);
        for (var outcome : outcomes) outcome.put("transcriptModelSha256", modelPin);
        return originals;
    }

    public static ObjectNode qualification(ObjectMapper mapper, Path directory, ObjectNode policy, ObjectNode model,
                                            List<ObjectNode> outcomes, Map<String, byte[]> originals) throws Exception {
        Files.createDirectories(directory);var adapter = new RuntimeEvidenceV2Adapter(mapper);
        var candidate = mapper.createObjectNode();candidate.putArray("policies").add(policy.deepCopy());
        byte[] candidateBytes = mapper.writeValueAsBytes(candidate);Files.write(directory.resolve("candidate.json"), candidateBytes);
        var q = mapper.createObjectNode().put("method", "UNCHANGED_SOURCE_POLICY_CONDITIONS")
                .put("reviewWitness", "INLINE_REVIEW_IN_PINNED_ORIGINAL")
                .put("originalCandidateRawSha256", CodingTranscriptMaterialSupply.sha(Files.readAllBytes(directory.resolve("candidate.json"))))
                .put("createsExpertReview", false).put("clinicalAcceptance", false);
        q.set("originalPolicyReview", policy.path("review").deepCopy());
        var knowledge = q.putObject("knowledge");
        for (String field : List.of("mechanism", "nmdScope", "escapeScope", "spliceScope", "codingEditScope"))
            if (policy.has(field)) knowledge.set(field, policy.path(field).deepCopy());
        knowledge.set("maximumStrength", policy.path("appliedStrength").deepCopy());
        q.set("sourceVerification", proofs(mapper, directory, policy.path("scientificSources"), originals));
        var v = q.putObject("spliceVerification");v.set("model", model.deepCopy());
        Files.write(directory.resolve("model.json"), mapper.writeValueAsBytes(model));
        v.put("modelRawSha256", CodingTranscriptMaterialSupply.sha(Files.readAllBytes(directory.resolve("model.json"))));
        var records = v.putArray("outcomes");int index = 0;
        for (var outcome : outcomes) {
            Path path = directory.resolve("outcome-" + index++ + ".json");Files.write(path, mapper.writeValueAsBytes(outcome));
            byte[] raw = Files.readAllBytes(path);
            var proof = records.addObject().put("status", "RAW_SHA_MATCH").put("bytes", raw.length)
                    .put("rawSha256", CodingTranscriptMaterialSupply.sha(raw)).put("canonicalSha256", adapter.canonicalSha256(outcome));
            proof.set("outcome", mapper.readTree(raw));proof.set("sourceVerification", proofs(mapper, directory, outcome.path("sourceRecords"), originals));
        }
        return q;
    }

    private static JsonNode proofs(ObjectMapper mapper, Path directory, JsonNode sources, Map<String, byte[]> originals) throws Exception {
        var proofs = mapper.createArrayNode();
        for (var source : sources) {
            byte[] bytes = originals.get(source.path("sourceId").asText());
            if (bytes == null) throw new IllegalArgumentException("TEST_SOURCE_ORIGINAL_REQUIRED");
            Path path = directory.resolve("source-" + CodingTranscriptMaterialSupply.sha(source.path("sourceId").asText()) + ".txt");
            Files.write(path, bytes);byte[] read = Files.readAllBytes(path);
            if (!CodingTranscriptMaterialSupply.sha(read).equals(source.path("sha256").asText())) throw new IllegalArgumentException("TEST_SOURCE_SHA_MISMATCH");
            proofs.addObject().put("status", "RAW_SHA_MATCH").put("bytes", read.length).set("source", source.deepCopy());
        }
        return proofs;
    }

    ObjectNode entry(ObjectNode policy, ObjectNode q) {
        var e = mapper.createObjectNode().put("policySha256", adapter.canonicalSha256(policy))
                .put("modelSha256", policy.at("/transcriptModelBinding/canonicalSha256").asText())
                .put("exonNmdScopeSha256", adapter.canonicalSha256(policy.path("nmdScope")));e.set("qualification", q);return e;
    }

    @Test void actualToyOriginalsQualifyV4AndTheMaterialActuallyUsedMustMatch() throws Exception {
        var d = new Pvs1SpliceConsequenceTest().data(1, false, false);var p = d.input().policy;var model = d.input().model;var outcome = d.material();
        var originals = configureOriginals(mapper, p, model, List.of(outcome));var q = qualification(mapper, root, p, model, List.of(outcome), originals);
        assertNull(Pvs1SourceQualification.issue(entry(p, q), p, adapter));
        var assessment = mapper.createObjectNode().put("status", "READY");assessment.putObject("adoption").set("qualification", q);
        assertNull(Pvs1SourceQualification.spliceMaterialIssue(assessment, d.input().context, p, model, outcome, adapter));
        var replacement = outcome.deepCopy();replacement.withObject("event").put("deleteEndCds", 24);
        assertEquals("PVS1_SOURCE_QUALIFICATION_SPLICE_MATERIAL_UNBOUND",
                Pvs1SourceQualification.spliceMaterialIssue(assessment, d.input().context, p, model, replacement, adapter));
        assertFalse(q.path("createsExpertReview").asBoolean());
    }

    @ParameterizedTest @ValueSource(strings = {"review", "assay", "source", "material", "model", "context", "scope", "composition"})
    void qualificationRejectsMissingReviewsCorruptBytesAndWrongExactIdentities(String mutation) throws Exception {
        var d = new Pvs1SpliceConsequenceTest().data(1, false, false);var p = d.input().policy;var model = d.input().model;var outcome = d.material();
        var originals = configureOriginals(mapper, p, model, List.of(outcome));
        if (mutation.equals("review")) outcome.withObject("review").put("status", "UNREVIEWED");
        if (mutation.equals("assay")) outcome.withObject("assessment").put("alleleAttribution", "UNKNOWN");
        if (mutation.equals("context")) outcome.withObject("allele").put("assembly", "GRCh37");
        if (mutation.equals("composition")) outcome.putObject("codingEditComposition").put("status", "REVIEWED_FOR_SAME_ALLELE_COMPOSITION");
        if (mutation.equals("scope")) p.withObject("spliceScope").put("status", "UNREVIEWED");
        var q = qualification(mapper, root, p, model, List.of(outcome), originals);
        if (mutation.equals("source")) ((ObjectNode) q.at("/spliceVerification/outcomes/0/sourceVerification/0")).put("bytes", 0);
        if (mutation.equals("material")) ((ObjectNode) q.at("/spliceVerification/outcomes/0")).put("canonicalSha256", "0".repeat(64));
        if (mutation.equals("model")) q.withObject("spliceVerification").withObject("model").put("modelVersion", "wrong");
        assertNotNull(Pvs1SourceQualification.issue(entry(p, q), p, adapter));
    }
}
