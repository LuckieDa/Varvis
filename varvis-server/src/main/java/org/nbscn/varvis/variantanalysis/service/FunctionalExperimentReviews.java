package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.Hnf1aFunctionalRuntime;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/** Append-only adoption adjunct. Original saved atomic/evaluation nodes are never rewritten. */
public final class FunctionalExperimentReviews {
    public record Input(String evaluationId, String factPackageSha256, String profileSha256, String previousReviewRecordId,
                        String decision, String actorId, String role, String rationale) { }
    private final ObjectMapper mapper;
    private final AcmgBusinessArtifacts identities;
    private final Path root;
    private final BiFunction<String, String, ObjectNode> savedRun;
    private final BiFunction<String, Supplier<ObjectNode>, ObjectNode> locked;
    public FunctionalExperimentReviews(ObjectMapper mapper, Path root, BiFunction<String, String, ObjectNode> savedRun,
                                      BiFunction<String, Supplier<ObjectNode>, ObjectNode> locked) {
        this.mapper = mapper; identities = new AcmgBusinessArtifacts(mapper);
        this.root = root.toAbsolutePath().normalize(); this.savedRun = savedRun; this.locked = locked;
    }
    public ObjectNode submit(String analysisId, String runId, Input input) {
        if (input == null || input.evaluationId()==null || input.actorId()==null || !input.actorId().matches("reviewer-[a-z0-9][a-z0-9._-]{7,95}")
                || !Set.of("GENETICIST", "LAB_SCIENTIST", "CURATOR", "SYSTEM_ADMINISTRATOR").contains(Objects.toString(input.role(), ""))
                || !Set.of("ACCEPTED", "REJECTED", "NONE").contains(Objects.toString(input.decision(), ""))
                || input.rationale()==null || input.rationale().isBlank() || input.rationale().length()>1000)
            throw error("FUNCTIONAL_REVIEW_INPUT_INVALID");
        return locked.apply(analysisId, () -> persist(analysisId, runId, input));
    }
    private ObjectNode persist(String analysisId, String runId, Input input) {
        var calculation = savedRun.apply(analysisId, runId);
        ObjectNode functional = subject(calculation, input.evaluationId());
        if (functional==null) throw error("FUNCTIONAL_REVIEW_SUBJECT_NOT_FOUND");
        String factSha = identities.requestHash(functional.path("factPackage"));
        String profileSha = functional.path("profileSha256").asText();
        if (!profileSha.equals(input.profileSha256()) || !Hnf1aFunctionalRuntime.PROFILE_SHA256.equals(profileSha)
                || !factSha.equals(input.factPackageSha256())
                || !factSha.equals(Hnf1aFunctionalRuntime.loadProfile(mapper).at("/factPackage/canonicalSha256").asText()))
            throw error("FUNCTIONAL_REVIEW_MATERIAL_OR_POLICY_STALE");
        if ("ACCEPTED".equals(input.decision()) && (!"MET".equals(functional.at("/evaluation/evaluationResult/status").asText())
                || !"APPLICABLE".equals(functional.at("/evaluation/criterionApplicability/status").asText())
                || !"NONE".equals(functional.at("/evaluation/conflict/status").asText())))
            throw error("FUNCTIONAL_REVIEW_CANNOT_ADOPT_UNEVALUATED_RESULT");
        var existing = records(analysisId, runId, calculation);
        ObjectNode latest = null;
        var predecessors = new HashSet<String>();
        for (var snapshot : existing) {
            var r = snapshot.path("reviewRecord");
            if (input.evaluationId().equals(r.path("subjectId").asText()) && r.has("previousReviewRecordId")) predecessors.add(r.path("previousReviewRecordId").asText());
        }
        for (var snapshot : existing) {
            var r = snapshot.path("reviewRecord");
            if (input.evaluationId().equals(r.path("subjectId").asText()) && !predecessors.contains(r.path("reviewRecordId").asText())) {
                if (latest!=null) throw error("FUNCTIONAL_REVIEW_CHAIN_INVALID");
                latest = snapshot;
            }
        }
        String previous = latest==null ? null : latest.at("/reviewRecord/reviewRecordId").asText();
        // Identical retry does not append or invent a new decision/time.
        if (latest!=null && latest.path("request").equals(mapper.valueToTree(input))) return latest;
        if (!Objects.equals(previous, input.previousReviewRecordId())) throw error("FUNCTIONAL_REVIEW_PREDECESSOR_STALE");
        var record = mapper.createObjectNode().put("schemaVersion", "review-record-v2")
                .put("contextId", functional.at("/context/contextId").asText()).put("subjectType", "CRITERION_EVALUATION")
                .put("subjectId", input.evaluationId()).put("eventType", "NONE".equals(input.decision()) ? "STARTED" : "DECIDED")
                .put("status", "NONE".equals(input.decision()) ? "IN_PROGRESS" : "COMPLETED").put("decision", input.decision())
                .put("reasonCode", "NONE".equals(input.decision()) ? "FUNCTIONAL_MATERIAL_PENDING" : "FUNCTIONAL_EXPLICIT_ADOPTION_DECISION")
                .put("rationaleSummary", input.rationale()).put("createdAt", Instant.now().toString());
        record.putObject("actor").put("actorId", input.actorId()).put("role", input.role());
        if (previous!=null) record.put("previousReviewRecordId", previous);
        else if (functional.at("/evaluation/review/reviewRecordIds/0").isTextual())
            record.set("previousReviewRecordId", functional.at("/evaluation/review/reviewRecordIds/0"));
        record.putArray("sourceLocators").addObject().put("locatorType", "REPOSITORY_ARTIFACT")
                .put("locatorValue", "acmg/hnf1a-functional-facts-v1.json").put("artifactSha256", factSha);
        record.put("reviewRecordId", "review-sha256-"+identities.requestHash(record));
        var snapshot = mapper.createObjectNode().put("schemaVersion", "functional-review-snapshot-v1")
                .put("analysisId", analysisId).put("runId", runId).put("calculationSha256", calculationHash(calculation))
                .put("calculationHashProfile", "JACKSON_COMPACT_JSON_UTF8_SHA256")
                .put("factPackageSha256", factSha).put("profileSha256", profileSha)
                .put("actorIdentityAssurance", "CALLER_DECLARED_NOT_INDEPENDENTLY_AUTHENTICATED")
                .put("clinicalConclusionClaimed", false);
        snapshot.set("request", mapper.valueToTree(input)); snapshot.set("reviewRecord", record);
        snapshot.put("snapshotSha256", identities.requestHash(snapshot));
        var folder = folder(analysisId, runId);
        Path temporary = null;
        try {
            Files.createDirectories(folder);
            temporary = Files.createTempFile(folder, "review-", ".tmp");
            Files.writeString(temporary, mapper.writeValueAsString(snapshot));
            Files.createLink(folder.resolve(record.path("reviewRecordId").asText()+".json"), temporary);
        } catch (IOException unavailable) { throw error("FUNCTIONAL_REVIEW_STORAGE_UNAVAILABLE"); }
        finally { if (temporary!=null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* unindexed temporary is never read */ } }
        return snapshot;
    }
    public ObjectNode read(String analysisId, String runId) {
        var calculation = savedRun.apply(analysisId, runId);
        var result = mapper.createObjectNode().put("analysisId", analysisId).put("runId", runId)
                .put("currentProfileSha256", Hnf1aFunctionalRuntime.PROFILE_SHA256).put("clinicalConclusionClaimed", false);
        result.set("records", mapper.valueToTree(records(analysisId, runId, calculation)));
        return result;
    }
    public ObjectNode export(String analysisId, String runId) {
        var result = read(analysisId, runId);
        result.set("calculation", savedRun.apply(analysisId, runId));
        return result;
    }
    private List<ObjectNode> records(String analysisId, String runId, ObjectNode calculation) {
        var folder = folder(analysisId, runId);
        if (Files.notExists(folder)) return List.of();
        var results = new ArrayList<ObjectNode>();
        try (var stream = Files.list(folder)) {
            var paths = stream.filter(p -> p.getFileName().toString().matches("review-sha256-[a-f0-9]{64}\\.json")).limit(1001).sorted().toList();
            if (paths.size() > 1000) throw error("FUNCTIONAL_REVIEW_SCOPE_LIMIT_EXCEEDED");
            for (var path : paths) {
                if (Files.size(path)>65536) throw error("FUNCTIONAL_REVIEW_INTEGRITY_FAILURE");
                var parsed = mapper.readTree(Files.readAllBytes(path));
                if (!(parsed instanceof ObjectNode snapshot)) throw error("FUNCTIONAL_REVIEW_INTEGRITY_FAILURE");
                var projection = snapshot.deepCopy(); projection.remove("snapshotSha256");
                var r = snapshot.path("reviewRecord");
                if (!(r instanceof ObjectNode review)) throw error("FUNCTIONAL_REVIEW_INTEGRITY_FAILURE");
                var rp = review.deepCopy(); rp.remove("reviewRecordId");
                ObjectNode functional = subject(calculation, r.path("subjectId").asText());
                if (functional == null || !snapshot.path("snapshotSha256").asText().equals(identities.requestHash(projection))
                        || !r.path("reviewRecordId").asText().equals("review-sha256-"+identities.requestHash(rp))
                        || !path.getFileName().toString().equals(r.path("reviewRecordId").asText()+".json")
                        || !analysisId.equals(snapshot.path("analysisId").asText()) || !runId.equals(snapshot.path("runId").asText())
                        || !"JACKSON_COMPACT_JSON_UTF8_SHA256".equals(snapshot.path("calculationHashProfile").asText())
                        || !calculationHash(calculation).equals(snapshot.path("calculationSha256").asText())
                        || !functional.path("profileSha256").equals(snapshot.path("profileSha256"))
                        || !identities.requestHash(functional.path("factPackage")).equals(snapshot.path("factPackageSha256").asText())
                        || !functional.at("/context/contextId").equals(r.path("contextId")))
                    throw error("FUNCTIONAL_REVIEW_INTEGRITY_FAILURE");
                results.add(snapshot);
            }
        } catch (IOException unavailable) { throw error("FUNCTIONAL_REVIEW_STORAGE_UNAVAILABLE"); }
        validateChains(results, calculation);
        return results;
    }
    private static void validateChains(List<ObjectNode> snapshots, ObjectNode calculation) {
        var bySubject = new HashMap<String, List<JsonNode>>();
        for (var snapshot : snapshots) {
            var record = snapshot.path("reviewRecord");
            bySubject.computeIfAbsent(record.path("subjectId").asText(), ignored -> new ArrayList<>()).add(record);
        }
        for (var entry : bySubject.entrySet()) {
            var functional = subject(calculation, entry.getKey());
            if (functional == null) throw error("FUNCTIONAL_REVIEW_CHAIN_INVALID");
            String initial = functional.at("/evaluation/review/reviewRecordIds/0").asText();
            var successors = new HashMap<String, String>();
            for (var record : entry.getValue()) {
                String previous = record.path("previousReviewRecordId").asText();
                if (previous.isBlank() || successors.put(previous, record.path("reviewRecordId").asText()) != null)
                    throw error("FUNCTIONAL_REVIEW_CHAIN_INVALID");
            }
            var visited = new HashSet<String>();
            String cursor = initial;
            while (successors.containsKey(cursor)) {
                cursor = successors.get(cursor);
                if (!visited.add(cursor)) throw error("FUNCTIONAL_REVIEW_CHAIN_INVALID");
            }
            if (initial.isBlank() || visited.size() != entry.getValue().size()) throw error("FUNCTIONAL_REVIEW_CHAIN_INVALID");
        }
    }
    private static ObjectNode subject(JsonNode calculation, String evaluationId) {
        ObjectNode found = null;
        for (var candidate : calculation.path("candidates")) for (var attempt : candidate.path("attempts")) {
            var f = attempt.path("functional");
            if (evaluationId.equals(f.at("/evaluation/evaluationId").asText()) && f instanceof ObjectNode object) {
                if (found!=null && !found.equals(object)) throw error("FUNCTIONAL_REVIEW_SUBJECT_AMBIGUOUS");
                found=object;
            }
        }
        return found;
    }
    private Path folder(String analysisId, String runId) { return root.resolve(AcmgBusinessStore.sha(analysisId+"\n"+runId)); }
    private String calculationHash(ObjectNode calculation) {
        // The business envelope contains ranking doubles. Its serialization digest is separate from
        // the frozen evidence-v2 canonical identities, which continue to forbid binary floating point.
        try { return AcmgBusinessStore.sha(mapper.writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT).writeValueAsString(calculation)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw error("FUNCTIONAL_REVIEW_CALCULATION_UNREADABLE"); }
    }
    private static ResponseStatusException error(String code) { return new ResponseStatusException(HttpStatus.CONFLICT, code); }
}
