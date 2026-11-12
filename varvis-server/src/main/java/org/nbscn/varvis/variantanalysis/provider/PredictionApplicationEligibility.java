package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Internal, memory-only diagnostic. There is deliberately no runtime caller or application method. */
final class PredictionApplicationEligibility {
    static final String SCHEMA = "prediction-application-eligibility-v1";
    private final ObjectMapper mapper;
    private final RuntimeEvidenceV2Adapter hashes;

    PredictionApplicationEligibility(ObjectMapper mapper) { this.mapper = mapper.copy(); hashes = new RuntimeEvidenceV2Adapter(this.mapper); }

    static final class Receipt {
        private final ObjectNode value;
        private Receipt(ObjectNode value) { this.value = value.deepCopy(); }
        ObjectNode json() { return value.deepCopy(); }
    }

    List<Receipt> assess(SavedPredictionApplicationInputs.Input input) {
        return assessInternal(input, null);
    }

    List<Receipt> assess(SavedPredictionApplicationInputs.Input input, SavedPredictionContextBridge.PreparedContexts prepared) {
        return assessInternal(input, java.util.Objects.requireNonNull(prepared));
    }

    private List<Receipt> assessInternal(SavedPredictionApplicationInputs.Input input, SavedPredictionContextBridge.PreparedContexts prepared) {
        ObjectNode settings = input.settings();
        List<Receipt> results = new ArrayList<>();
        if (!input.issue().isEmpty()) {
            ObjectNode out = base(input, mapper.createObjectNode(), mapper.createObjectNode(), "BATCH");
            fail(out, 0, classification(input.issue()), input.issue(), SavedPredictionApplicationInputs.issuePath(input.issue()));
            return List.of(finish(out));
        }
        for (JsonNode candidate : settings.path("candidates")) {
            String sourceIssue = SavedPredictionApplicationInputs.candidateIssue(mapper, input, candidate);
            ArrayNode hypotheses = candidate.path("hypotheses").deepCopy();
            List<ObjectNode> entries = prepared == null ? List.of() : prepared.candidateEntries(candidate.path("candidateId").asText());
            String bridgeIssue = prepared == null ? "" : !prepared.matches(input) ? "CONTEXT_PREPARATION_SOURCE_IDENTITY_MISMATCH" : prepared.batchIssue();
            if (prepared != null && bridgeIssue.isEmpty()) {
                ArrayNode supplied = mapper.createArrayNode();
                for (ObjectNode entry : entries) {
                    if (entry.path("hypothesisId").asText().isEmpty()) continue;
                    supplied.add(bridgeHypothesis(entry));
                }
                if (hypotheses.isEmpty()) hypotheses = supplied;
                else if (!sameHypotheses(hypotheses, supplied)) bridgeIssue = "CONTEXT_SAVED_HYPOTHESES_CONFLICT";
            }
            if (hypotheses.isEmpty()) hypotheses.addObject();
            for (JsonNode hypothesis : hypotheses) {
                ObjectNode entry = entries.stream().filter(e -> e.path("hypothesisId").asText().equals(hypothesis.path("hypothesisId").asText()))
                        .findFirst().orElse(mapper.createObjectNode());
                ObjectNode pp3 = one(input, settings, candidate, hypothesis, "PP3", sourceIssue, prepared, entry, bridgeIssue);
                ObjectNode bp4 = one(input, settings, candidate, hypothesis, "BP4", sourceIssue, prepared, entry, bridgeIssue);
                if ("SATISFIED".equals(pp3.path("directionGate").asText()) && "SATISFIED".equals(bp4.path("directionGate").asText())
                        && !(pp3.path("coAdoptionGovernanceSha256").isTextual()
                        && pp3.path("coAdoptionGovernanceSha256").equals(bp4.path("coAdoptionGovernanceSha256")))) {
                    fail(pp3, 6, "REVIEW_REQUIRED", "JOINT_DIRECTION_ADOPTION_UNGOVERNED", "/coAdoption");
                    fail(bp4, 6, "REVIEW_REQUIRED", "JOINT_DIRECTION_ADOPTION_UNGOVERNED", "/coAdoption");
                }
                results.add(finish(pp3)); results.add(finish(bp4));
            }
        }
        return List.copyOf(results);
    }

    private ObjectNode one(SavedPredictionApplicationInputs.Input input, ObjectNode settings, JsonNode candidate,
                           JsonNode hypothesis, String criterion, String sourceIssue, SavedPredictionContextBridge.PreparedContexts prepared,
                           ObjectNode bridgeEntry, String bridgeIssue) {
        ObjectNode out = base(input, candidate, hypothesis, criterion);
        if (prepared != null) {
            ObjectNode identity = prepared.identity(); identity.set("preparation", bridgeEntry.deepCopy());
            identity.put("legacyAssessmentId", out.path("assessmentId").asText());
            out.put("candidateApplicationKey", "prediction-candidate-sha256-" + hashes.canonicalSha256(identity));
            out.put("assessmentId", "prediction-eligibility-sha256-" + hashes.canonicalSha256(identity));
            out.set("contextPreparation", bridgeEntry.deepCopy()); out.set("contextBridgeIdentity", prepared.identity());
        }
        if (!sourceIssue.isEmpty()) { fail(out, 0, classification(sourceIssue), sourceIssue, SavedPredictionApplicationInputs.issuePath(sourceIssue)); return out; }
        pass(out, 0);
        if (!bridgeIssue.isEmpty()) { fail(out, 1, classification(bridgeIssue), bridgeIssue, "/contextPreparation"); return out; }
        JsonNode published = SavedPredictionApplicationInputs.publicationCandidate(input, candidate.path("candidateId").asText());
        out.set("publicationProjection", published.path("sourceProjection").deepCopy());
        ObjectNode savedFacts = out.putObject("savedStageFacts");
        for (var entry : candidate.path("evidence").properties()) {
            JsonNode saved = SavedPredictionApplicationInputs.prediction(input, candidate, entry.getValue());
            savedFacts.putObject(entry.getKey()).put("observationStatus", saved.at("/observation/status").asText())
                    .put("associationStatus", saved.at("/association/status").asText())
                    .put("savedCalibrationStatus", saved.at(entry.getValue().path("calibrationPointer").asText()).path("status").asText())
                    .put("policyCompatibilityChecked", false);
        }
        if (prepared != null && !bridgeEntry.path("blockers").isEmpty()) {
            for (JsonNode blocker : bridgeEntry.path("blockers")) fail(out, 1, classification(blocker.path("reasonCode").asText()),
                    blocker.path("reasonCode").asText(), blocker.path("field").asText());
            return out;
        }
        JsonNode context = hypothesis.path("context");
        if (!context.isObject()) { fail(out, 1, "BLOCKED", "EXPLICIT_CONTEXT_MISSING", "/hypotheses/context"); return out; }
        try { hashes.validateCompleteContext(context); }
        catch (IllegalArgumentException invalid) { fail(out, 1, "BLOCKED", "CONTEXT_IDENTITY_INCOMPLETE_OR_INVALID", "/context"); return out; }
        if (!"VERIFIED".equals(hypothesis.path("verification").asText())
                || !SavedPredictionApplicationInputs.nonblank(hypothesis, "reviewId", "reviewerId", "sourceSha256")) {
            fail(out, 1, "BLOCKED", "EXPLICIT_CONTEXT_VERIFICATION_MISSING", "/hypothesis/review"); return out;
        }
        boolean trusted = prepared != null && "VERIFIED_FOR_S1".equals(bridgeEntry.path("preparationStatus").asText());
        if (prepared != null && !trusted) { fail(out, 1, "BLOCKED", "CONTEXT_PREPARATION_NOT_VERIFIED", "/contextPreparation"); return out; }
        JsonNode hgnc = trusted ? bridgeEntry.path("verifiedHgncId") : published.path("hgncId");
        if (!hgnc.isTextual() || hgnc.asText().isBlank()) {
            fail(out, 1, "BLOCKED", "CONTEXT_GENE_IDENTITY_UNVERIFIED", "/publication/hgncId"); return out;
        }
        if (!(trusted ? SavedPredictionContextBridge.contextInputAlleleMatches(published, context.at("/variant/inputAllele"))
                      : context.at("/variant/inputAllele").equals(published.path("inputAllele")))
                || !context.at("/variant/evidenceQueryAllele").equals(allele(published.path("queryAllele")))
                || !context.at("/gene/hgncId").equals(hgnc)) {
            fail(out, 1, "BLOCKED", "CONTEXT_CANDIDATE_IDENTITY_MISMATCH", "/context/variant"); return out;
        }
        if (trusted) out.set("inputContigComparison", SavedPredictionContextBridge.contigReceipt(mapper, published));
        boolean transcriptFound = false;
        for (JsonNode annotation : published.path("annotations")) {
            try {
                if (SavedPredictionApplicationInputs.versionedAnnotationIdentity(annotation).equals(
                        context.at("/transcript/accession").asText() + "." + context.at("/transcript/version").asInt())) transcriptFound = true;
            } catch (IllegalArgumentException invalid) {
                // An unrelated, unversioned or conflicting annotation cannot establish this context.
                // The evidence-bound annotation has already passed the stricter S0 check.
            }
        }
        if (!transcriptFound) { fail(out, 1, "BLOCKED", "CONTEXT_TRANSCRIPT_MISMATCH", "/context/transcript"); return out; }
        pass(out, 1);
        if (!(settings.path("catalog") instanceof ObjectNode catalog) || !(settings.path("policies") instanceof ArrayNode policies)) {
            fail(out, 2, "BLOCKED", "CATALOG_INPUT_MISSING", "/catalog"); return out;
        }
        // Complete-context validation forbids observation/score fields before selection.
        var selected = new AcmgSpecificationSelector(mapper, catalog, hashes.canonicalSha256(catalog), policies)
                .select(context, "ACMG_AMP_2015:" + criterion);
        out.set("selector", selected.receipt().deepCopy());
        ObjectNode policy = selected.selectedPolicy();
        if (policy == null) {
            String reason = selected.receipt().path("reasonCode").asText();
            fail(out, 2, classification(reason), reason, "/catalog/entries"); return out;
        }
        pass(out, 2);
        out.put("selectedPolicySha256", hashes.canonicalSha256(policy));
        boolean clauseSource = input.sourceKind() == SavedPredictionApplicationInputs.SourceKind.REVIEWED_PREDICTION_CLAUSE_RESULT_V1;
        var sidecar = clauseSource ? PredictionApplicationPolicy.clause(mapper, policy, input, criterion) : null;
        String issue = sidecar == null ? PredictionApplicationPolicy.issue(mapper, policy, settings, criterion) : sidecar.issue();
        if (clauseSource && issue.isEmpty()) {
            String clauseIssue = PredictionClausePolicy.issue(context, policy, selected.receipt(), hashes);
            if (clauseIssue != null) issue = clauseIssue;
        }
        if (!issue.isEmpty()) {
            String path = clauseSource ? ("PREDICTION_SPECIALIZED_REQUIREMENT_NOT_IMPLEMENTED".equals(issue)
                    ? "/policy/additionalRequirements" : "/clauseApplicationSidecars") : "/policy/application";
            fail(out, 3, classification(issue), issue, path); return out;
        }
        pass(out, 3);
        JsonNode app = sidecar == null ? policy.path("application") : sidecar.json(), template = app.path("template"), plan = template.path("criteria").path(criterion);
        out.set("policyReferences", app.deepCopy());
        out.set("adoption", clauseSource ? clauseReviewProjection(settings.path("adoptions").path(app.path("adoptionId").asText()))
                : settings.path("adoptions").path(app.path("adoptionId").asText()).deepCopy());
        out.set("modelSelectionProof", clauseSource ? clauseReviewProjection(settings.path("modelProofs").path(app.path("modelProofId").asText()))
                : settings.path("modelProofs").path(app.path("modelProofId").asText()).deepCopy());
        String governanceId = app.path("coAdoptionId").asText();
        JsonNode governance = settings.path("coAdoptions").path(governanceId);
        if (!governanceId.isBlank() && "REVIEWED".equals(governance.path("status").asText())
                && SavedPredictionApplicationInputs.nonblank(governance, "reviewId", "version")
                && contains(governance.path("policySha256s"), hashes.canonicalSha256(policy)))
            out.put("coAdoptionGovernanceSha256", hashes.canonicalSha256(governance));
        var mechanisms = mapper.createObjectNode();
        for (JsonNode name : plan.path("mechanisms")) mechanisms.set(name.asText(), clauseSource
                ? clauseMechanism(input, candidate, hypothesis, settings, template.path("mechanisms").path(name.asText()), app, criterion)
                : mechanism(input, candidate, hypothesis, settings, template.path("mechanisms").path(name.asText()), name.asText()));
        out.set("mechanisms", mechanisms);
        ObjectNode direction = direction(plan, mechanisms, criterion);
        out.put("directionGate", direction.path("gate").asText());
        String strengthField = clauseSource ? "sourceClauseStrength" : "sourceCalibratedStrength";
        out.set(strengthField, mapper.createObjectNode());
        for (var e : mechanisms.properties()) {
            if (e.getValue().path("sourceVerified").asBoolean()) ((ObjectNode) out.path(strengthField))
                    .put(e.getKey(), e.getValue().path("sourceStrength").asText());
        }
        // Only the mechanisms actually required by the fixed plan can block this criterion.
        for (JsonNode used : direction.path("required")) {
            JsonNode mechanism = mechanisms.path(used.asText());
            if (!mechanism.path("issue").asText().isEmpty())
                fail(out, 4, classification(mechanism.path("issue").asText()), mechanism.path("issue").asText(), "/mechanisms/" + used.asText());
        }
        if (stage(out, 4).path("status").asText().equals("NOT_CHECKED")) pass(out, 4);
        if ("UNKNOWN".equals(direction.path("gate").asText())) fail(out, 5, "BLOCKED", "MECHANISM_INPUT_MISSING", "/mechanisms");
        else if ("NOT_APPLICABLE".equals(direction.path("gate").asText())) fail(out, 5, "NOT_APPLICABLE", "REQUIRED_MECHANISM_NOT_APPLICABLE", "/mechanisms");
        else pass(out, 5);
        out.put("directionReason", direction.path("reason").asText());
        out.put("qualifiedStrength", direction.path("strength").asText("NONE"));
        JsonNode coverage = settings.path("effectSnapshots").path(hypothesis.path("effectSnapshotId").asText());
        out.set("effectCoverage", clauseSource ? clauseCoverageProjection(coverage) : coverage.deepCopy());
        String effectIssue = PredictionEffectCoverage.issue(coverage, template.path("requiredCoverage"), candidate, hypothesis, settings.path("task"));
        if (effectIssue.isEmpty()) pass(out, 6);
        else fail(out, 6, classification(effectIssue), effectIssue, "/effectCoverage");
        return out;
    }

    private ObjectNode clauseCoverageProjection(JsonNode value) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : List.of("snapshotId", "version", "status", "reviewId", "externalWorldCovered", "candidateId", "hypothesisId",
                "contextId", "equivalenceScope", "validFrom", "validThrough", "coverage")) if (value.has(field)) out.set(field, value.path(field).deepCopy());
        ArrayNode effects = out.putArray("effects");
        for (JsonNode item : value.path("effects")) {
            ObjectNode effect = effects.addObject();
            for (String field : List.of("kind", "referenceId", "sourceSha256", "equivalence", "adoption"))
                if (item.has(field)) effect.set(field, item.path(field).deepCopy());
        }
        return out;
    }

    private ObjectNode clauseReviewProjection(JsonNode value) {
        ObjectNode out = mapper.createObjectNode();
        for (String field : List.of("status", "reviewId", "reviewerId", "version", "sourceSha256", "sourceRunId", "purpose",
                "policyId", "policyVersion", "policySha256", "templateSha256", "kind", "preparationIds"))
            if (value.has(field)) out.set(field, value.path(field).deepCopy());
        return out;
    }

    private ObjectNode clauseMechanism(SavedPredictionApplicationInputs.Input input, JsonNode candidate, JsonNode hypothesis,
            JsonNode settings, JsonNode requirement, JsonNode app, String criterion) {
        ObjectNode out = mapper.createObjectNode().put("state", "UNAVAILABLE").put("sourceVerified", false).put("issue", "");
        try {
            ObjectNode view = PredictionClauseSourceView.read(mapper, input, candidate, hypothesis.path("hypothesisId").asText(), criterion).json();
            out.put("sourceKind", input.sourceKind().name());
            for (String field : List.of("sourceClauseOutcome", "sourceClauseStrength", "resourceExecution", "queryOutcome", "clauseResultId", "preparationId"))
                if (view.has(field)) out.set(field, view.path(field));
            if (!view.path("issue").asText().isEmpty()) return out.put("issue", view.path("issue").asText());
            SavedPredictionApplicationInputs.require(view.path("contextSha256").asText().equals(hashes.canonicalSha256(hypothesis.path("context"))), "CLAUSE_APPLICATION_CONTEXT_MISMATCH");
            SavedPredictionApplicationInputs.require(view.path("policyBinding").equals(requirement.path("policyBinding")), "CLAUSE_APPLICATION_POLICY_MISMATCH");
            JsonNode proof = settings.path("modelProofs").path(app.path("modelProofId").asText());
            SavedPredictionApplicationInputs.require(contains(proof.path("preparationIds"), view.path("preparationId").asText()), "MODEL_SELECTION_PROOF_MISSING");
            JsonNode compatibility = settings.path("compatibilities").path(requirement.path("compatibilityId").asText());
            PredictionApplicationPolicy.reviewedReference(compatibility, "RESOURCE_COMPATIBILITY_UNVERIFIED");
            SavedPredictionApplicationInputs.require(requirement.path("compatibilityId").equals(compatibility.path("id"))
                    && input.sourceKind().name().equals(compatibility.path("sourceKind").asText())
                    && "REVEL".equals(compatibility.path("model").asText()) && "EXACT_SINGLE_ROW".equals(compatibility.path("scope").asText())
                    && settings.at("/task/purpose").equals(compatibility.path("purpose"))
                    && view.path("policyBinding").equals(compatibility.path("policyBinding"))
                    && view.at("/policy/scope").equals(compatibility.path("policyScope")), "RESOURCE_COMPATIBILITY_UNVERIFIED");
            for (String field : List.of("resourceBinding", "transcriptModelBinding", "revelTranscriptBinding"))
                SavedPredictionApplicationInputs.require(view.path(field).equals(compatibility.path(field)), "RESOURCE_COMPATIBILITY_UNVERIFIED");
            SavedPredictionApplicationInputs.require(contains(compatibility.path("annotationSourceSha256s"), view.path("annotationSourceSha256").asText()), "CLAUSE_ANNOTATION_COMPATIBILITY_UNVERIFIED");
            return out.put("sourceVerified", true).put("sourceStrength", view.path("sourceClauseStrength").asText())
                    .put("mappedStrength", view.path("sourceClauseStrength").asText()).put("direction", view.path("direction").asText())
                    .put("state", view.path("direction").asText());
        } catch (RuntimeException invalid) { return out.put("issue", SavedPredictionApplicationInputs.safeClauseIssue(invalid)); }
    }

    private ObjectNode bridgeHypothesis(ObjectNode entry) {
        ObjectNode hypothesis = mapper.createObjectNode().put("hypothesisId", entry.path("hypothesisId").asText());
        if (!"VERIFIED_FOR_S1".equals(entry.path("preparationStatus").asText())) return hypothesis;
        hypothesis.set("context", entry.path("context").deepCopy()); hypothesis.put("verification", "VERIFIED");
        for (String field : List.of("reviewId", "reviewerId")) hypothesis.set(field, entry.path("review").path(field));
        hypothesis.set("sourceSha256", entry.path("reviewSourceSha256")); return hypothesis;
    }

    private static boolean sameHypotheses(ArrayNode existing, ArrayNode supplied) {
        if (existing.size() != supplied.size()) return false;
        for (JsonNode original : existing) {
            ObjectNode contextFields = ((ObjectNode) original).deepCopy();
            // This pre-existing downstream S6 pointer is not a context claim and must survive unchanged.
            contextFields.remove("effectSnapshotId");
            if (supplied.valueStream().filter(h -> h.path("hypothesisId").equals(original.path("hypothesisId")))
                    .filter(contextFields::equals).count() != 1) return false;
        }
        return true;
    }

    private ObjectNode mechanism(SavedPredictionApplicationInputs.Input input, JsonNode candidate, JsonNode hypothesis,
                                 JsonNode settings, JsonNode requirement, String name) {
        ObjectNode out = mapper.createObjectNode().put("state", "NOT_QUERIED").put("issue", "MECHANISM_NOT_QUERIED").put("sourceVerified", false);
        JsonNode binding = candidate.path("evidence").path(name);
        if (!binding.isObject()) return out;
        JsonNode prediction = SavedPredictionApplicationInputs.prediction(input, candidate, binding);
        JsonNode observation = prediction.path("observation");
        if (!binding.path("model").equals(requirement.path("model"))) return out.put("issue", "PRESELECTED_MODEL_MISMATCH");
        if (!hypothesis.at("/context/transcript/accession").asText().concat(".").concat(hypothesis.at("/context/transcript/version").asText())
                .equals(prediction.at("/selection/versionedTranscriptId").asText())) return out.put("issue", "SELECTED_TRANSCRIPT_ASSOCIATION_MISMATCH");
        if (!"HIT".equals(observation.path("status").asText())) {
            String state = observation.path("status").asText();
            if ("NO_RECORD".equals(state) && "SUCCEEDED".equals(observation.at("/execution/status").asText())
                    && observation.path("rows").isArray() && observation.path("rows").isEmpty())
                return out.put("state", "NO_RECORD").put("issue", "PRIMARY_NO_RECORD_PROVENANCE_UNSUPPORTED");
            if ("NOT_APPLICABLE".equals(state) && "VERIFIED".equals(binding.at("/applicability/status").asText())
                    && SavedPredictionApplicationInputs.nonblank(binding.path("applicability"), "reviewId", "sourceSha256"))
                return out.put("state", "NOT_APPLICABLE").put("issue", "");
            return out.put("state", "UNAVAILABLE").put("issue", "MECHANISM_RESOURCE_UNAVAILABLE");
        }
        JsonNode calibration = prediction.at(binding.path("calibrationPointer").asText());
        var view = PredictionCalibrationView.read(calibration);
        if (!view.issue().isEmpty()) return out.put("issue", view.issue());
        boolean standard = "prediction-standard-calibration-result-v1".equals(view.schema());
        if (!requirement.path("calibrationId").asText().equals(standard ? calibration.path("calibrationId").asText() : binding.path("legacyCalibrationId").asText())
                || !requirement.path("calibrationVersion").asText().equals(standard ? calibration.path("calibrationVersion").asText() : binding.path("legacyCalibrationVersion").asText()))
            return out.put("issue", "SELECTED_CALIBRATION_CONTRACT_MISMATCH");
        JsonNode contract = settings.path("calibrationContracts").path(requirement.path("calibrationId").asText());
        String contractHash = requirement.path("calibrationContractSha256").asText();
        if (!contract.isObject() || !hashes.canonicalSha256(contract).equals(contractHash)
                || !contract.path("calibrationId").equals(requirement.path("calibrationId"))
                || !contract.path("version").equals(requirement.path("calibrationVersion"))
                || (standard && !contractHash.equals(input.document("request").at("/declaration/calibration/calibrationCanonicalSha256").asText()))
                || !(standard ? contractHash.equals(calibration.at("/savedSource/calibrationCanonicalSha256").asText())
                : contractHash.equals(hashes.canonicalSha256(calibration.path("calibration")))))
            return out.put("issue", "CALIBRATION_CONTRACT_DIGEST_MISMATCH");
        if (!standard) return out.put("issue", "LEGACY_CALIBRATION_SOURCE_PROVENANCE_UNSUPPORTED");
        out.put("sourceVerified", true).put("sourceStrength", view.sourceStrength()).put("direction", view.direction());
        JsonNode compatibility = settings.path("compatibilities").path(requirement.path("compatibilityId").asText());
        out.set("compatibility", compatibility.deepCopy());
        if (!"REVIEWED".equals(compatibility.path("status").asText())
                || !SavedPredictionApplicationInputs.nonblank(compatibility, "id", "version", "reviewId", "reviewerId")
                || !compatibility.path("id").equals(requirement.path("compatibilityId"))
                || !compatibility.path("model").equals(binding.path("model"))
                || !compatibility.path("resource").equals(observation.path("resource"))
                || !compatibility.path("calibrationId").equals(requirement.path("calibrationId"))
                || !compatibility.path("calibrationVersion").equals(requirement.path("calibrationVersion"))
                || !compatibility.path("calibrationContractSha256").equals(requirement.path("calibrationContractSha256"))
                || !compatibility.path("purpose").equals(settings.at("/task/purpose"))) return out.put("issue", "RESOURCE_COMPATIBILITY_UNVERIFIED");
        if (!"EXACT_SINGLE_ROW".equals(compatibility.path("scope").asText())) return out.put("issue", "AGGREGATION_CONTRACT_UNSUPPORTED");
        if (observation.path("rows").size() != 1 || !mapper.createArrayNode().add(0).equals(prediction.at("/association/matchedRowIndices")))
            return out.put("issue", "EXACT_SINGLE_ROW_OUTSIDE_SCOPE");
        if (!contains(compatibility.path("observationSha256s"), observation.path("observationSha256").asText()))
            return out.put("issue", "COMPATIBILITY_RECORD_OUTSIDE_ADOPTED_SCOPE");
        if (!"UNINFORMATIVE".equals(view.direction())) {
            String strength = requirement.path("strengthMap").path(view.sourceStrength()).asText();
            if (!Set.of("SUPPORTING", "MODERATE", "STRONG").contains(strength)) return out.put("issue", "STRENGTH_MAPPING_MISSING");
            out.put("mappedStrength", strength);
        }
        return out.put("state", view.direction()).put("issue", "");
    }

    private ObjectNode direction(JsonNode plan, ObjectNode mechanisms, String criterion) {
        String mode = plan.path("mode").asText(), wanted = "PP3".equals(criterion) ? "HARMFUL" : "BENIGN";
        ObjectNode out = mapper.createObjectNode().put("gate", "NOT_SATISFIED").put("strength", "NONE").put("reason", "DIRECTION_NOT_SUPPORTING");
        ArrayNode required = out.putArray("required");
        List<String> names = new ArrayList<>();
        for (JsonNode name : plan.path("mechanisms")) names.add(name.asText());
        if ("MISSING_FALLBACK".equals(mode)) {
            String first = names.get(0);
            JsonNode primary = mechanisms.path(first);
            if ("NO_RECORD".equals(primary.path("state").asText())) {
                for (String name : names) required.add(name);
                return out.put("gate", "UNKNOWN").put("reason", "PRIMARY_NO_RECORD_PROVENANCE_UNSUPPORTED");
            }
            else names = new ArrayList<>(List.of(first));
        }
        boolean unknown = false, notApplicable = false, all = true;
        String chosen = "";
        for (String name : names) {
            JsonNode value = mechanisms.path(name); required.add(name);
            String state = value.path("state").asText();
            boolean unavailable = !value.path("issue").asText().isEmpty();
            unknown |= unavailable;
            notApplicable |= "NOT_APPLICABLE".equals(state);
            boolean supports = !unavailable && wanted.equals(state);
            all &= supports;
            if (supports && chosen.isEmpty()) chosen = name;
            // Fixed priority: an unresolved earlier alternative cannot be bypassed by a later score.
            if (!"ALL".equals(mode) && (supports || unavailable || "NOT_APPLICABLE".equals(state))) break;
        }
        if (unknown) return out.put("gate", "UNKNOWN").put("reason", "MECHANISM_INPUT_MISSING");
        if (notApplicable) return out.put("gate", "NOT_APPLICABLE").put("reason", "REQUIRED_MECHANISM_NOT_APPLICABLE");
        boolean satisfied = "ALL".equals(mode) ? all : !chosen.isEmpty();
        if (satisfied) {
            String use = "ALL".equals(mode) ? plan.path("strengthMechanism").asText() : chosen;
            return out.put("gate", "SATISFIED").put("reason", "DIRECTION_SUPPORTED")
                    .put("strength", mechanisms.path(use).path("mappedStrength").asText("NONE"));
        }
        for (String name : names) if ("UNINFORMATIVE".equals(mechanisms.path(name).path("state").asText())) out.put("reason", "CALIBRATED_UNINFORMATIVE");
        for (String name : names) if ("NOT_MET".equals(mechanisms.path(name).path("state").asText())) out.put("reason", "SOURCE_CLAUSE_NOT_MET");
        return out;
    }

    private ObjectNode base(SavedPredictionApplicationInputs.Input input, JsonNode candidate, JsonNode hypothesis, String criterion) {
        ObjectNode out = mapper.createObjectNode().put("schemaVersion", SCHEMA).put("criterionId", "BATCH".equals(criterion) ? "BATCH" : "ACMG_AMP_2015:" + criterion)
                .put("candidateId", candidate.path("candidateId").asText()).put("rank", candidate.path("rank").asInt())
                .put("hypothesisId", hypothesis.path("hypothesisId").asText()).put("directionGate", "UNKNOWN")
                .put("appliedStrength", "NONE").put("proposedApplicationStrength", "NONE").put("applicationReady", false)
                .put("formalEnabled", false).put("criterionEvaluationCreated", false);
        out.set("sourceIdentity", input.identity());
        ObjectNode identity = input.identity(); identity.put("contractVersion", SCHEMA).put("criterionId", out.path("criterionId").asText());
        identity.set("candidate", candidate.deepCopy()); identity.set("hypothesis", hypothesis.deepCopy());
        out.put("candidateApplicationKey", "prediction-candidate-sha256-" + hashes.canonicalSha256(identity));
        out.put("assessmentId", "prediction-eligibility-sha256-" + hashes.canonicalSha256(identity));
        ArrayNode stages = out.putArray("stages");
        for (int i = 0; i < 8; i++) stages.addObject().put("stage", "S" + i).put("status", "NOT_CHECKED").putArray("reasons");
        return out;
    }

    private Receipt finish(ObjectNode out) {
        String status = "", reason = "";
        for (JsonNode stage : out.path("stages")) if (!stage.path("reasons").isEmpty()) {
            status = stage.path("status").asText(); reason = stage.at("/reasons/0/code").asText(); break;
        }
        if (status.isEmpty()) {
            if ("SATISFIED".equals(out.path("directionGate").asText())) {
                String strength = out.path("qualifiedStrength").asText();
                if (!Set.of("SUPPORTING", "MODERATE", "STRONG").contains(strength)) {
                    fail(out, 7, "BLOCKED", "QUALIFIED_STRENGTH_MISSING", "/strength"); status = "BLOCKED"; reason = "QUALIFIED_STRENGTH_MISSING";
                } else { status = "PRECONDITIONS_PASS"; reason = "DECLARED_ENGINEERING_PRECONDITIONS_SATISFIED"; out.put("proposedApplicationStrength", strength); }
            } else { status = "NO_SUPPORT"; reason = out.path("directionReason").asText(); }
        }
        out.put("status", status).put("reasonCode", reason);
        stage(out, 7).put("status", "COMPLETE"); out.remove("qualifiedStrength");
        return new Receipt(out);
    }

    private static ObjectNode stage(ObjectNode out, int index) { return (ObjectNode) out.path("stages").get(index); }
    private static void pass(ObjectNode out, int index) { stage(out, index).put("status", "PASS"); }
    private static void fail(ObjectNode out, int index, String status, String reason, String path) {
        ObjectNode stage = stage(out, index);
        if (stage.path("reasons").isEmpty()) stage.put("status", status);
        ((ArrayNode) stage.path("reasons")).addObject().put("code", reason).put("missingPath", path);
    }
    private static String classification(String reason) {
        if (reason.contains("NOT_IMPLEMENTED") || reason.contains("UNSUPPORTED")) return "UNSUPPORTED";
        if (reason.contains("CONFLICT") || reason.contains("UNRESOLVED") || reason.contains("OUTSIDE_ADOPTED_SCOPE")) return "REVIEW_REQUIRED";
        return "BLOCKED";
    }
    private static boolean contains(JsonNode array, String value) {
        if (!array.isArray()) return false;
        for (JsonNode item : array) if (value.equals(item.asText())) return true;
        return false;
    }
    private ObjectNode allele(JsonNode key) {
        ObjectNode out = mapper.createObjectNode();
        out.set("assembly", key.path("genomeAssembly")); out.set("contig", key.path("chromosome")); out.set("position", key.path("position"));
        out.set("reference", key.path("reference")); out.set("alternate", key.path("alternate")); return out;
    }
}
