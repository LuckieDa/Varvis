package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.AssemblyResult;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Attempt;
import org.nbscn.varvis.variantanalysis.service.ExplicitEvidenceContextAssembler.Blocker;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Opt-in E4-A2A selection only. No provider, criterion evaluator or combination is invoked. */
public final class CSpecExecutionPreflight {
    private static final String RULE_ID = "acmg-rule-registry-e3-c1-2026-09-02";
    private static final String RULE_SHA = "73fca655b6d76700242fc6b12ba4c6c4b9f34ac5c79b38621fe03fecc8303469";
    private static final String CSPEC_ID = "cspec-overlay-registry-e4-a2a-2026-09-05";
    private static final String CSPEC_SHA = "5ffe4ebb0d2b0509271203427802307ab4443a1ae974a344b8fadb5b46708b48";
    private static final String AUTHORITY_ID = "authority-catalog-acmg-evidence-v2-2026-08-31";
    private static final String AUTHORITY_SHA = "9c4d12df8eba350318624e25d50321f845c023f1d136bff934ecfbb89d45e627";
    private static final String AS_OF = "2026-08-31T11:20:00Z";
    private static final String PROFILE_SHA = "b8f040d58ee6ef19f687886a742cacaa0a96dc3b5f803e8f423578e1aaa4cb83";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter adapter;
    private final ObjectNode registry;
    private final ObjectNode pairing;
    private final Blocker resourceIssue;
    private final String cspecSha;

    public CSpecExecutionPreflight(ObjectMapper mapper) {
        this(mapper, resource("/acmg/rule-registry-v2/" + RULE_ID + ".json"),
                resource("/acmg/cspec-overlay-registry-v1/" + CSPEC_ID + ".json"),
                resource("/acmg/alphamissense-pp3-bp4-supporting-profile-v1.json"));
    }

    // Byte injection is package-local for negative tests. It cannot bypass the production pins.
    CSpecExecutionPreflight(ObjectMapper mapper, byte[] ruleBytes, byte[] cspecBytes, byte[] profileBytes) {
        this(mapper, ruleBytes, cspecBytes, profileBytes, false);
    }

    static CSpecExecutionPreflight splice(ObjectMapper mapper) {
        return new CSpecExecutionPreflight(mapper, resource("/acmg/rule-registry-v2/acmg-rule-registry-e3-c3-2026-09-02.json"),
                resource("/acmg/cspec-overlay-registry-v1/cspec-overlay-registry-e4-b3-2026-09-08.json"),
                resource("/acmg/spliceai-bp7-supporting-profile-v1.json"), true);
    }
    private CSpecExecutionPreflight(ObjectMapper mapper, byte[] ruleBytes, byte[] cspecBytes, byte[] profileBytes, boolean splice) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.adapter = new RuntimeEvidenceV2Adapter(mapper);
        String profileSha = splice ? adapter.canonicalSha256(SpliceAiCriterionEvaluator.loadBp7Profile(mapper)) : PROFILE_SHA;
        String ruleId = splice ? "acmg-rule-registry-e3-c3-2026-09-02" : RULE_ID;
        String ruleSha = splice ? "5fb10c64a0d157708529e3617247375170117ee25d64f5a970efd484008b6f64" : RULE_SHA;
        String cspecId = splice ? "cspec-overlay-registry-e4-b3-2026-09-08" : CSPEC_ID;
        this.cspecSha = splice ? "7cf63ccf1c7d4544ed907483a331d21eb853f9037b5d587792da9b8ebb11e9cf" : CSPEC_SHA;
        ObjectNode loaded = null;
        ObjectNode identities = null;
        Blocker issue = null;
        try {
            JsonNode rule = mapper.readTree(ruleBytes);
            JsonNode cspec = mapper.readTree(cspecBytes);
            JsonNode profile = mapper.readTree(profileBytes);
            if (!(rule != null && rule.isObject() && cspec != null && cspec.isObject()
                    && profile != null && profile.isObject())) { throw new Blocked("REGISTRY_RESOURCE_INVALID", "registry"); }
            loaded = (ObjectNode) cspec;
            require(ruleId.equals(text(rule, "registryId")) && cspecId.equals(text(loaded, "registryId"))
                    && ruleId.equals(text(loaded, "baseRuleRegistryId"))
                    && ruleSha.equals(text(loaded, "baseRuleRegistrySha256")), "REGISTRY_PAIRING_INVALID", "registry");
            for (JsonNode document : List.of(rule, loaded)) {
                require(AUTHORITY_ID.equals(text(document, "authorityCatalogId"))
                        && AUTHORITY_SHA.equals(text(document, "authorityCatalogSha256")),
                        "AUTHORITY_PAIRING_INVALID", "authorityCatalog");
                require(AS_OF.equals(text(document, "asOf"))
                        && "2026-08-31".equals(text(document, "authorityCutoff")), "REGISTRY_AS_OF_MISMATCH", "asOf");
            }
            require(ruleSha.equals(sha256(ruleBytes)) && cspecSha.equals(sha256(cspecBytes)),
                    "REGISTRY_ARTIFACT_HASH_MISMATCH", "registry.sha256");
            require(profileSha.equals(adapter.canonicalSha256(profile))
                    && ruleId.equals(profile.at("/registryBinding/registryId").asText())
                    && ruleSha.equals(profile.at("/registryBinding/registrySha256").asText()),
                    "EXECUTION_SLICE_BINDING_INVALID", "profile.registryBinding");
            // The frozen authority artifact is pinned here; the offline verifier checks its actual bytes.
            identities = mapper.createObjectNode();
            identities.put("authorityCatalogId", AUTHORITY_ID).put("authorityCatalogSha256", AUTHORITY_SHA);
            identities.put("ruleRegistryId", ruleId).put("ruleRegistrySha256", ruleSha);
            identities.put("overlayRegistryId", cspecId).put("overlayRegistrySha256", cspecSha);
            identities.put("asOf", AS_OF).put("authorityCutoff", "2026-08-31");
            ObjectNode slice = identities.putObject("executionSlice");
            slice.put("profileId", text(profile, "profileId")).put("profileVersion", text(profile, "profileVersion"));
            slice.put("profileCanonicalSha256", profileSha).put("profileAsOf", text(profile, "asOf"));
            slice.put("evaluatorId", splice ? SpliceAiCriterionEvaluator.EVALUATOR_ID : AlphaMissenseCriterionEvaluator.EVALUATOR_ID)
                    .put("evaluatorVersion", splice ? SpliceAiCriterionEvaluator.EVALUATOR_VERSION : AlphaMissenseCriterionEvaluator.EVALUATOR_VERSION);
            slice.set("criterionIds", profile.at("/registryBinding/criterionIds").deepCopy());
            slice.put("strengthCap", profile.at("/strengthPolicy/strengthCap").asText());
        } catch (Blocked failure) {
            issue = blocker(failure.reason, failure.field);
        } catch (IOException | IllegalArgumentException failure) {
            issue = blocker("REGISTRY_RESOURCE_INVALID", "registry");
        }
        this.registry = loaded;
        this.pairing = identities;
        this.resourceIssue = issue;
    }

    ObjectNode registryPairing() { return copy(pairing); }

    public BatchResult check(AssemblyResult input) { return check(input, List.of(), List.of()); }

    /** Records are explicitly supplied immutable events, never generated review requests or decisions. */
    public BatchResult check(AssemblyResult input, List<JsonNode> reviews, List<JsonNode> conflicts) {
        Objects.requireNonNull(input, "assemblyResult");
        List<JsonNode> suppliedReviews = reviews == null ? List.of() : reviews;
        List<JsonNode> suppliedConflicts = conflicts == null ? List.of() : conflicts;
        boolean shapeValid = input.analysisId() != null && !input.analysisId().isBlank()
                && input.requestedN() > 0 && input.selectedCount() <= input.requestedN()
                && input.selectedCount() == input.candidates().size()
                && input.candidateIds().equals(input.candidates().stream().map(row -> row.candidateId()).toList())
                && !input.candidateOrderingChanged();
        List<CandidateResult> rows = new ArrayList<>();
        for (var row : input.candidates()) {
            List<SelectionAttempt> attempts = new ArrayList<>();
            for (Attempt attempt : row.attempts()) {
                try {
                    if (!attempt.blockers().isEmpty()) {
                        attempts.add(failed(attempt, attempt.blockers()));
                        continue;
                    }
                    require(shapeValid, "ASSEMBLY_ASSOCIATION_INVALID", "assemblyResult");
                    require(Objects.equals(row.candidateId(), attempt.candidateId())
                            && row.attempts().stream().filter(other -> Objects.equals(other.hypothesisId(), attempt.hypothesisId())).count() == 1,
                            "CONTEXT_ASSOCIATION_INVALID", "candidateId/hypothesisId");
                    verifyAttempt(attempt);
                    if (resourceIssue != null) {
                        attempts.add(failed(attempt, List.of(resourceIssue)));
                        continue;
                    }
                    attempts.add(select(attempt, registry, cspecSha, suppliedReviews, suppliedConflicts));
                } catch (Blocked failure) {
                    attempts.add(failed(attempt, List.of(blocker(failure.reason, failure.field))));
                } catch (IllegalArgumentException failure) {
                    attempts.add(failed(attempt, List.of(blocker("CONTEXT_CONTRACT_INVALID", "context"))));
                }
            }
            rows.add(new CandidateResult(row.candidateId(), attempts));
        }
        return new BatchResult(input.analysisId(), input.requestedN(), input.selectedCount(), input.candidateIds(),
                rows, input.unmatchedInputs(), input.candidateOrderingChanged(), pairing);
    }

    private void verifyAttempt(Attempt attempt) {
        require(attempt.context() != null, "COMPLETE_CONTEXT_REQUIRED", "context");
        adapter.validateCompleteContext(attempt.context());
        JsonNode context = attempt.context();
        require("GRCh38".equals(context.at("/variant/inputAllele/assembly").asText())
                        && "NATIVE".equals(context.at("/variant/assemblyLineage/mappingStatus").asText()),
                "NATIVE_GRCH38_REQUIRED", "context.variant.assemblyLineage");
        var binding = attempt.binding();
        require(binding != null && "READY".equals(binding.status()) && attempt.hypothesisId() != null
                        && !attempt.hypothesisId().isBlank() && Objects.equals(attempt.candidateId(), binding.candidateId())
                        && attempt.hypothesisId().equals(binding.hypothesisId())
                        && Objects.equals(binding.hgncId(), context.at("/gene/hgncId").asText())
                        && Objects.equals(binding.symbol(), context.at("/gene/symbol").asText())
                        && Objects.equals(binding.mondoId(), context.at("/disease/mondoId").asText())
                        && Objects.equals(binding.diseaseLabel(), context.at("/disease/label").asText())
                        && Objects.equals(binding.normalizedMoi(), text(context, "moi")),
                "CONTEXT_BINDING_MISMATCH", "binding");
        require(attempt.seed() != null && Objects.equals(attempt.candidateId(), attempt.seed().candidateId())
                        && attempt.provenance() != null && Objects.equals(attempt.provenance().dataClass(), text(context, "dataClass")),
                "CONTEXT_ASSEMBLY_LINEAGE_MISSING", "seed/provenance");
        var lineage = attempt.provenance().assemblyLineage();
        require(lineage != null && "GRCh38".equals(lineage.originalInputAssembly())
                        && "NATIVE".equals(lineage.preRankingMappingStatus()) && lineage.transformationId() == null
                        && mapper.valueToTree(lineage.originalInputAllele()).equals(context.at("/variant/inputAllele")),
                "NATIVE_GRCH38_REQUIRED", "provenance.assemblyLineage");
        ObjectNode draft = attempt.seed().draft();
        ObjectNode projection = ((ObjectNode) context).deepCopy();
        projection.remove(List.of("schemaVersion", "canonicalizationProfile", "contextId", "contextCompleteness"));
        ((ObjectNode) projection.path("variant")).remove(List.of("alleleId", "legacyCanonicalVariantId"));
        require(draft.equals(projection), "CONTEXT_SEED_MISMATCH", "seed");
    }

    // Pure selection core; package-local so synthetic overlays remain test fixtures, never runtime inputs.
    SelectionAttempt select(Attempt original, ObjectNode overlayRegistry, String registrySha,
                            List<JsonNode> reviews, List<JsonNode> conflicts) {
        ObjectNode context = original.context();
        adapter.validateCompleteContext(context);
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode overlay : overlayRegistry.path("overlays")) if (matches(overlay, context)) matches.add(overlay);
        List<String> ids = matches.stream().map(overlay -> text(overlay, "overlayId")).toList();
        String outcome = matches.isEmpty() ? "ZERO_MATCH" : matches.size() > 1 ? "MULTIPLE_EXACT_MATCHES"
                : "SELECTABLE".equals(text(matches.get(0), "selectionStatus"))
                && !"METADATA_ONLY".equals(text(matches.get(0), "implementationStatus"))
                ? "SINGLE_SELECTABLE_MATCH" : "SINGLE_NON_SELECTABLE_MATCH";
        String policyKey = switch (outcome) {
            case "ZERO_MATCH" -> "zeroMatch";
            case "SINGLE_SELECTABLE_MATCH" -> "singleSelectableMatch";
            case "SINGLE_NON_SELECTABLE_MATCH" -> "singleNonSelectableMatch";
            default -> "multipleExactMatches";
        };
        ObjectNode constraints = (ObjectNode) overlayRegistry.at("/selectionPolicy/outcomes/" + policyKey).deepCopy();
        ObjectNode selection = mapper.createObjectNode();
        selection.put("schemaVersion", "cspec-selection-v1").put("contextId", text(context, "contextId"));
        selection.put("canonicalizationProfile", "varvis-json-c14n-v1")
                .put("overlayRegistryId", text(overlayRegistry, "registryId")).put("overlayRegistrySha256", registrySha)
                .put("policyId", overlayRegistry.at("/selectionPolicy/policyId").asText()).put("outcome", outcome);
        selection.set("candidateOverlayIds", mapper.valueToTree(ids));
        if ("SINGLE_SELECTABLE_MATCH".equals(outcome)) {
            selection.put("selectedOverlayId", ids.get(0)).put("selectedOverlaySha256", text(matches.get(0), "snapshotSha256"));
        }
        List<Blocker> blockers = new ArrayList<>();
        boolean requiresReview = "REQUIRED".equals(text(constraints, "reviewConstraint"));
        boolean requiresConflict = "MULTIPLE_EXACT_MATCHES".equals(outcome);
        if (requiresReview) {
            selection.put("reasonCode", text(constraints, "reasonCode"));
            blockers.add(blocker(text(constraints, "reasonCode"), "cspecSelection.outcome"));
        }
        String selectionId = selectionIdentity(selection);
        selection.put("selectionId", selectionId);
        List<JsonNode> associatedReviews = reviews.stream().filter(Objects::nonNull)
                .filter(record -> selectionId.equals(text(record, "subjectId"))).toList();
        List<JsonNode> associatedConflicts = conflicts.stream().filter(Objects::nonNull)
                .filter(record -> contains(record.path("participantIds"), selectionId)).toList();
        if (requiresReview) {
            var references = CSpecPreflightReferences.validate(mapper, adapter, text(context, "contextId"), selectionId,
                    ids, requiresConflict, associatedReviews, associatedConflicts);
            if (!references.valid()) {
                blockers.add(blocker(references.reasonCode(), references.field()));
                return new SelectionAttempt(original, outcome, ids, selectionId, null, constraints,
                        associatedReviews, associatedConflicts, blockers);
            }
        } else if (!associatedReviews.isEmpty() || !associatedConflicts.isEmpty()) {
            blockers.add(blocker("UNEXPECTED_SELECTION_REFERENCES", "review/conflict"));
            constraints.put("criterionEvaluationConstraint", "FORCE_NOT_EVALUATED").put("combinationDisposition", "ABSTAIN");
            return new SelectionAttempt(original, outcome, ids, selectionId, null, constraints,
                    associatedReviews, associatedConflicts, blockers);
        }
        ObjectNode conflict = selection.putObject("conflict").put("status", requiresConflict ? "PRESENT" : "NONE");
        conflict.set("conflictIds", mapper.valueToTree(associatedConflicts.stream().map(record -> text(record, "conflictId")).toList()));
        ObjectNode review = selection.putObject("review").put("status", requiresReview ? "REQUIRED" : "NOT_REQUIRED").put("decision", "NONE");
        review.set("reviewRecordIds", mapper.valueToTree(associatedReviews.stream().map(record -> text(record, "reviewRecordId")).toList()));
        return new SelectionAttempt(original, outcome, ids, selectionId, selection, constraints,
                associatedReviews, associatedConflicts, blockers);
    }

    private static boolean matches(JsonNode overlay, JsonNode context) {
        JsonNode selector = overlay.path("selector");
        return selector.path("hgncId").equals(context.at("/gene/hgncId"))
                && selector.path("mondoId").equals(context.at("/disease/mondoId"))
                && selector.path("moi").equals(context.path("moi"))
                && (!overlay.path("transcriptRequired").asBoolean()
                || (selector.at("/transcript/accession").equals(context.at("/transcript/accession"))
                && selector.at("/transcript/version").equals(context.at("/transcript/version"))));
    }

    String selectionIdentity(JsonNode selection) {
        ObjectNode projection = selection.deepCopy();
        projection.remove(List.of("selectionId", "conflict", "review"));
        List<String> ids = new ArrayList<>();
        selection.path("candidateOverlayIds").forEach(id -> ids.add(id.asText()));
        projection.set("candidateOverlayIds", mapper.valueToTree(ids.stream().sorted().toList()));
        return "cspec-selection-sha256-" + adapter.canonicalSha256(projection);
    }

    private SelectionAttempt failed(Attempt original, List<Blocker> blockers) {
        ObjectNode constraints = mapper.createObjectNode().put("criterionEvaluationConstraint", "FORCE_NOT_EVALUATED")
                .put("combinationDisposition", "ABSTAIN");
        return new SelectionAttempt(original, null, List.of(), null, null, constraints, List.of(), List.of(), blockers);
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }
    private static String text(JsonNode node, String field) { return node.path(field).asText(); }
    private static void require(boolean condition, String reason, String field) { if (!condition) throw new Blocked(reason, field); }
    private static Blocker blocker(String reason, String field) { return new Blocker("CSPEC_PREFLIGHT_BLOCKED", reason, field); }
    private static byte[] resource(String path) {
        try (InputStream input = CSpecExecutionPreflight.class.getResourceAsStream(path)) {
            return input == null ? new byte[0] : input.readAllBytes();
        } catch (IOException failure) { return new byte[0]; }
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ObjectNode copy(ObjectNode node) { return node == null ? null : node.deepCopy(); }
    private static List<JsonNode> copies(List<JsonNode> nodes) { return nodes.stream().map((JsonNode value) -> value.deepCopy()).map(JsonNode.class::cast).toList(); }

    public record SelectionAttempt(Attempt original, String outcome, List<String> candidateOverlayIds, String selectionId,
                                   ObjectNode selection, ObjectNode constraints, List<JsonNode> reviewRecords,
                                   List<JsonNode> conflicts, List<Blocker> blockers) {
        public SelectionAttempt {
            candidateOverlayIds = List.copyOf(candidateOverlayIds); selection = copy(selection); constraints = copy(constraints);
            reviewRecords = copies(reviewRecords); conflicts = copies(conflicts); blockers = List.copyOf(blockers);
        }
        @Override public ObjectNode selection() { return copy(selection); }
        @Override public ObjectNode constraints() { return copy(constraints); }
        @Override public List<JsonNode> reviewRecords() { return copies(reviewRecords); }
        @Override public List<JsonNode> conflicts() { return copies(conflicts); }
    }
    public record CandidateResult(String candidateId, List<SelectionAttempt> attempts) {
        public CandidateResult { attempts = List.copyOf(attempts); }
    }
    public record BatchResult(String analysisId, int requestedN, int selectedCount, List<String> candidateIds,
                              List<CandidateResult> candidates, List<Attempt> unmatchedInputs,
                              boolean candidateOrderingChanged, ObjectNode registryPairing) {
        public BatchResult {
            candidateIds = java.util.Collections.unmodifiableList(new ArrayList<>(candidateIds));
            candidates = List.copyOf(candidates); unmatchedInputs = List.copyOf(unmatchedInputs); registryPairing = copy(registryPairing);
        }
        @Override public ObjectNode registryPairing() { return copy(registryPairing); }
    }
    private static final class Blocked extends RuntimeException {
        private final String reason;
        private final String field;
        private Blocked(String reason, String field) { this.reason = reason; this.field = field; }
    }
}
