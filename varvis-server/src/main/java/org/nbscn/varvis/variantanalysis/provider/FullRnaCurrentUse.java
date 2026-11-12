package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;

/** Request-local application ledger. Scientific module decisions are never overwritten. */
final class FullRnaCurrentUse {
    static final String VERSION = "1.1.0";
    static final String PUBLIC_PROJECTION_VERSION = "full-rna-qualified-initiation-sources-v2";
    private FullRnaCurrentUse() { }

    static ObjectNode apply(ObjectMapper mapper, ReviewedRuleBundle.FullRnaPrepared prepared, String sourceSha,
            String candidateId, String hypothesisId, Map<String, ObjectNode> actual, Map<String, String> pointers) {
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        var ledger = mapper.createObjectNode().put("schemaVersion", "full-rna-application-ledger-v1").put("methodVersion", VERSION)
                .put("candidateId", candidateId).put("hypothesisId", hypothesisId).put("executionSourceSha256", sourceSha);
        ledger.set("preparation", prepared.publicSummary(mapper));
        var claims = ledger.putObject("criteria"); var token = prepared.source();
        for (var entry : actual.entrySet()) {
            String code = entry.getKey(); var raw = entry.getValue();
            // Reviewed PVS1 refusals use the atomic evaluation envelope. Retain it
            // byte-for-JSON-field rather than hashing MissingNode or relabeling a failed rule.
            if (!raw.path("evaluation").isObject()) raw.set("evaluation", raw.deepCopy());
            var evaluation = raw.path("evaluation");
            var claim = claims.putObject(code).put("criterionId", "ACMG_AMP_2015:" + code)
                    .put("modulePointer", pointers.getOrDefault(code.toLowerCase(java.util.Locale.ROOT), ""))
                    .put("evaluationSha256", hashes.canonicalSha256(evaluation))
                    .put("originalStatus", evaluation.at("/evaluationResult/status").asText("NOT_EVALUATED"))
                    .put("originalStrength", evaluation.path("appliedStrength").asText("NONE"))
                    .put("application", "NOT_ADOPTED");
            var originalReason = evaluation.at("/evaluationResult/reasonCode");
            if (originalReason.isTextual()) claim.set("originalReasonCode", originalReason.deepCopy());
            String rawStatus = claim.path("originalStatus").asText();
            claim.put("normalizedReasonCode", originalReason.asText(List.of("MET", "NOT_MET").contains(rawStatus)
                    ? "LOCAL_BATCH_CRITERION_" + rawStatus : code + "_RESULT_UNAVAILABLE"));
            boolean accepted = token != null && "MET".equals(claim.path("originalStatus").asText())
                    && strength(claim.path("originalStrength").asText()) > 0;
            var assessment = code.equals("PVS1") ? raw.at("/resolvedInput/fullRnaAssessment") : raw.path("fullRnaAssessment");
            accepted = accepted && token != null && token.originalSha256().equals(assessment.path("sourceOriginalSha256").asText())
                    && token.facts().mechanicalEffectSha256().equals(assessment.path("mechanicalEffectSha256").asText());
            if (code.equals("PVS1") && token != null && completedSourceBoundExclusion(raw,
                    token.originalSha256(), token.facts().mechanicalEffectSha256())) {
                claim.put("completedExclusion", true)
                        .put("originalApplicability", evaluation.at("/criterionApplicability/status").asText())
                        .put("originalExecutionStatus", evaluation.at("/deterministicTrace/executionStatus").asText());
            }
            if (accepted) claim.put("application", "ADOPTED");
            else if ("MET".equals(rawStatus)) claim.put("reasonCode", "FULL_RNA_CURRENT_EFFECT_BINDING_INVALID");
        }
        if (token != null) {
            ledger.put("sourceOriginalSha256", token.originalSha256()).put("mechanicalEffectSha256", token.facts().mechanicalEffectSha256())
                    .put("factsSha256", token.facts().canonicalSha256());
            var pvs1 = claims.path("PVS1"); var pm4 = claims.path("PM4"); var bp3 = claims.path("BP3");
            if (adopted(bp3) && (adopted(pvs1) || adopted(pm4))) {
                for (var claim : claims) if (adopted(claim)) ((ObjectNode) claim).put("application", "CONFLICT")
                        .put("reasonCode", "FULL_RNA_OPPOSITE_EFFECT_EVIDENCE_CONFLICT");
            } else if (adopted(pvs1) && adopted(pm4)) {
                String winner = strength(pvs1.path("originalStrength").asText()) >= strength(pm4.path("originalStrength").asText()) ? "PVS1" : "PM4";
                var loser = (ObjectNode) claims.path(winner.equals("PVS1") ? "PM4" : "PVS1");
                loser.put("application", "NOT_COUNTED").put("assignedTo", "ACMG_AMP_2015:" + winner)
                        .put("reasonCode", "FULL_RNA_SHARED_EFFECT_ASSIGNED_TO_" + winner);
            }
        }
        ledger.put("ledgerSha256", hashes.canonicalSha256(ledger));
        for (var entry : actual.entrySet()) {
            var raw = entry.getValue(); var proof = raw.putObject("fullRnaCurrentUse");
            proof.put("schemaVersion", "full-rna-current-use-v1").put("methodVersion", VERSION).put("ledgerSha256", ledger.path("ledgerSha256").asText())
                    .put("candidateId", candidateId).put("hypothesisId", hypothesisId).put("executionSourceSha256", sourceSha);
            proof.set("claim", claims.path(entry.getKey()).deepCopy()); proof.set("preparation", prepared.publicSummary(mapper));
            var decisionDetails = decisionDetails(mapper, raw, entry.getKey());
            raw.set("fullRnaDecisionDetails", decisionDetails); proof.put("decisionDetailsSha256", hashes.canonicalSha256(decisionDetails));
            if (raw.has("fullRnaSourceOriginal") && token != null) {
                raw.remove("fullRnaSourceOriginal"); raw.put("fullRnaOriginalReference", token.originalSha256());
            }
            for (String key : List.of("sourceOriginalSha256", "mechanicalEffectSha256", "factsSha256"))
                if (ledger.has(key)) proof.set(key, ledger.path(key).deepCopy());
            proof.put("proofSha256", hashes.canonicalSha256(proof));
            var summary = mapper.createObjectNode().put("schemaVersion", entry.getKey().equals("PM4") ? "pm4-general-facts-v1"
                    : entry.getKey().equals("BP3") ? "bp3-general-facts-v1" : "pvs1-selected-facts-v1")
                    .put("methodVersion", "1.0.0")
                    .put("method", "SOURCE_BOUND_FULL_RNA_CONSEQUENCE").put("clinicalValidationCompleted", false);
            if (entry.getKey().equals("PVS1")) summary.setAll(pvs1MethodMetadata(mapper, raw, true, null));
            summary.set("fullRnaUse", publicProof(mapper, raw));
            summary.set("fullRnaDetails", decisionDetails.deepCopy());
            JsonNode assessment = entry.getKey().equals("PVS1") ? raw.at("/resolvedInput/fullRnaAssessment") : raw.path("fullRnaAssessment");
            if (assessment.isObject()) summary.set("fullRnaAssessment", assessment.deepCopy());
            raw.set("publicSummary", summary);
        }
        return ledger;
    }

    static ObjectNode publicProof(ObjectMapper mapper, JsonNode raw) {
        var hashes = new RuntimeEvidenceV2Adapter(mapper); var proof = raw.path("fullRnaCurrentUse");
        if (!(proof instanceof ObjectNode value)) return mapper.createObjectNode();
        var body = value.deepCopy(); body.remove("proofSha256");
        if (!"full-rna-current-use-v1".equals(value.path("schemaVersion").asText())
                || !List.of("1.0.0", VERSION).contains(value.path("methodVersion").asText())
                || !hashes.canonicalSha256(body).equals(value.path("proofSha256").asText())
                || !hashes.canonicalSha256(raw.path("evaluation")).equals(value.at("/claim/evaluationSha256").asText())
                || !hashes.canonicalSha256(raw.path("fullRnaDecisionDetails")).equals(value.path("decisionDetailsSha256").asText()))
            throw new IllegalArgumentException("FULL_RNA_CURRENT_USE_BINDING_INVALID");
        if (value.at("/claim/completedExclusion").asBoolean()
                && (!VERSION.equals(value.path("methodVersion").asText()) || !completedExclusionClaim(value.path("claim")) || !completedSourceBoundExclusion(raw,
                        value.path("sourceOriginalSha256").asText(), value.path("mechanicalEffectSha256").asText())))
            throw new IllegalArgumentException("FULL_RNA_COMPLETED_EXCLUSION_BINDING_INVALID");
        return value.deepCopy();
    }
    static ObjectNode project(ObjectMapper mapper, JsonNode batch, JsonNode attempt, JsonNode criterion) {
        var hashes = new RuntimeEvidenceV2Adapter(mapper);
        String pointer = criterion.at("/details/modulePointer").asText();
        if (!pointer.matches("/modules/(pvs1|pm4|bp3)/results/(0|[1-9][0-9]{0,5})")) throw new IllegalArgumentException("FULL_RNA_CURRENT_POINTER_INVALID");
        var raw = batch.at(pointer); var proof = publicProof(mapper, raw); var claim = proof.path("claim");
        var ledger = attempt.path("fullRnaApplication");
        if (!(ledger instanceof ObjectNode ledgerObject)) throw new IllegalArgumentException("FULL_RNA_LEDGER_REQUIRED");
        var ledgerBody = ledgerObject.deepCopy(); ledgerBody.remove("ledgerSha256");
        String code = criterion.path("criterionId").asText().substring("ACMG_AMP_2015:".length());
        boolean valid = hashes.canonicalSha256(ledgerBody).equals(ledger.path("ledgerSha256").asText())
                && ledger.path("ledgerSha256").equals(proof.path("ledgerSha256")) && ledger.path("criteria").path(code).equals(claim)
                && attempt.path("candidateId").equals(proof.path("candidateId")) && attempt.path("hypothesisId").equals(proof.path("hypothesisId"))
                && batch.path("sourceSnapshotSha256").equals(proof.path("executionSourceSha256"))
                && pointer.equals(claim.path("modulePointer").asText()) && criterion.path("criterionId").equals(claim.path("criterionId"))
                && criterion.at("/details/originalResult").equals(raw.path("evaluation"))
                && claim.path("originalStatus").equals(raw.at("/evaluation/evaluationResult/status"))
                && (claim.path("originalStrength").equals(raw.at("/evaluation/appliedStrength"))
                    || raw.at("/evaluation/appliedStrength").isMissingNode()
                        && "NOT_EVALUATED".equals(raw.at("/evaluation/evaluationResult/status").asText())
                        && "NONE".equals(claim.path("originalStrength").asText()));
        var wanted = mapper.createObjectNode().put("status", claim.path("originalStatus").asText())
                .put("appliedStrength", claim.path("originalStrength").asText()).put("reasonCode", claim.path("normalizedReasonCode").asText());
        applyView(wanted, claim);
        valid &= criterion.path("status").equals(wanted.path("status")) && criterion.path("appliedStrength").equals(wanted.path("appliedStrength"))
                && criterion.path("reasonCode").equals(wanted.path("reasonCode"));
        if (!valid) throw new IllegalArgumentException("FULL_RNA_CURRENT_CENTRAL_BINDING_INVALID");
        var out = mapper.createObjectNode().put("schemaVersion", code.equals("PM4") ? "pm4-general-facts-v1"
                : code.equals("BP3") ? "bp3-general-facts-v1" : "pvs1-selected-facts-v1")
                .put("methodVersion", "1.0.0")
                .put("method", "SOURCE_BOUND_FULL_RNA_CONSEQUENCE").put("clinicalValidationCompleted", false);
        if (code.equals("PVS1")) out.setAll(pvs1MethodMetadata(mapper, raw, false, batch.at("/identity/fullRnaExecution/pvs1Version").asText()));
        out.set("fullRnaUse", proof); var assessment = code.equals("PVS1") ? raw.at("/resolvedInput/fullRnaAssessment") : raw.path("fullRnaAssessment");
        out.set("fullRnaDetails", raw.path("fullRnaDecisionDetails").deepCopy());
        if (assessment.isObject()) out.set("fullRnaAssessment", assessment.deepCopy()); return out;
    }
    /** Presentation identity follows the saved scientific evaluation, never the active reader's version. */
    static ObjectNode pvs1MethodMetadata(ObjectMapper mapper, JsonNode raw, boolean fresh, @Nullable String savedTarget) {
        if (raw.path("fullRnaCurrentUse").isObject()) publicProof(mapper, raw);
        String version = raw.at("/evaluation/deterministicTrace/evaluatorVersion").asText();
        var assessment = raw.at("/resolvedInput/fullRnaAssessment");
        var out = mapper.createObjectNode();
        if (!version.isEmpty()) {
            String module = assessment.path("methodVersion").asText(); var initiation = raw.at("/resolvedInput/fullRnaInitiationAssessment");
            boolean known = "1.9.0".equals(version) && "1.0.0".equals(module) && initiation.isMissingNode()
                    || "1.10.0".equals(version) && "1.1.0".equals(module) && (initiation.isMissingNode()
                        || "pvs1-full-rna-initiation-assessment-v1".equals(initiation.path("schemaVersion").asText()) && "1.0.0".equals(initiation.path("methodVersion").asText()))
                    || "1.10.1".equals(version) && "1.1.1".equals(module) && (initiation.isMissingNode()
                        || "pvs1-full-rna-initiation-assessment-v1".equals(initiation.path("schemaVersion").asText()) && "1.0.0".equals(initiation.path("methodVersion").asText()));
            if (!known || !TruncatingNmdPvs1Runtime.EVALUATOR_ID.equals(raw.at("/evaluation/deterministicTrace/evaluatorId").asText()))
                throw new IllegalArgumentException("FULL_RNA_SAVED_METHOD_TUPLE_UNSUPPORTED");
            out.put("evaluationState", "EXECUTED").put("evaluationMethodVersion", version);
        } else {
            if (assessment.isObject() || !"NOT_EVALUATED".equals(raw.at("/evaluation/evaluationResult/status").asText()))
                throw new IllegalArgumentException("FULL_RNA_SAVED_EVALUATOR_IDENTITY_REQUIRED");
            version = fresh ? TruncatingNmdPvs1Runtime.FULL_RNA_EVALUATOR_VERSION : raw.at("/publicSummary/methodVersion").asText();
            if (!List.of("1.9.0", "1.10.0", "1.10.1").contains(version)) throw new IllegalArgumentException("FULL_RNA_SAVED_METHOD_IDENTITY_REQUIRED");
            out.put("evaluationState", "NOT_EXECUTED").putNull("evaluationMethodVersion");
        }
        if (savedTarget != null && !savedTarget.equals(version))
            throw new IllegalArgumentException("FULL_RNA_SAVED_METHOD_PROFILE_MISMATCH");
        return out.put("methodVersion", version);
    }
    private static ObjectNode decisionDetails(ObjectMapper mapper, JsonNode raw, String code) {
        var out = mapper.createObjectNode().put("schemaVersion", "full-rna-decision-details-v1").put("methodVersion", VERSION);
        if (code.equals("PVS1")) {
            var input = raw.path("resolvedInput");
            out.put("assessmentKind", input.path("fullRnaInitiationAssessment").isObject() ? "INITIATION_LOSS" : input.path("inFrameAssessment").isObject() ? "IN_FRAME" : input.path("escapeAssessment").isObject()
                    ? "NMD_ESCAPE" : input.path("nmdPrediction").isTextual() ? "NMD" : "UNRESOLVED");
            for (String branch : List.of("inFrameAssessment", "escapeAssessment")) if (input.path(branch).isObject()) {
                var assessment = input.path(branch); var target = out.putObject(branch.equals("inFrameAssessment") ? "inFrame" : "escape");
                copy(target, assessment, List.of("schemaVersion", "status", "reasonCode", "strength", "reviewId", "policyReviewId", "referenceProteinLength",
                        "alteredProteinLength", "deletedProteinResidues", "deletedProteinResidueMetric", "firstAlteredResidue", "referenceResiduesAffected", "netProteinShortening"));
                copy(target.putObject("selectedRule"), assessment.path("selectedRule"), List.of("ruleId", "predicate", "start", "end", "minimumAffectedResidues",
                        "numerator", "denominator", "comparison", "strength"));
                var checks = target.putArray("ruleChecks");
                for (var row : assessment.path("ruleChecks")) {
                    if (checks.size() >= 256) break;
                    var check = checks.addObject(); copy(check, row, List.of("ruleId", "predicate", "status", "matched", "affectedResidues", "affectedResiduesInRegion",
                            "partiallyDeletedReferenceResiduesInRegion", "actualNumerator", "actualDenominator", "leftProduct", "rightProduct", "comparison", "strength"));
                    if (row.path("rule").isObject()) copy(check.putObject("rule"), row.path("rule"), List.of("ruleId", "predicate", "start", "end", "minimumAffectedResidues", "numerator", "denominator", "comparison", "strength"));
                }
            }
            if (input.path("fullRnaInitiationAssessment").isObject()) initiationDetails(mapper, out.putObject("initiation"), raw);
            var nmd = out.putObject("nmd");
            copy(nmd, input, List.of("nmdPrediction", "ptcStart", "ptcEnd", "distanceToLastJunction", "lastJunctionCdsPosition", "referenceLastJunctionCdsPosition",
                    "mutantLastJunctionCdsPosition", "coordinateSystem", "referenceProteinLength", "alteredProteinLength", "exonOrdinal", "exonTotal"));
            copy(nmd.putObject("rule"), raw.at("/policy/nmdScope"), List.of("minimumDistanceToLastJunction", "minimumCdsPosition", "maximumExonLength", "longExonScope"));
            var rule = out.putObject("rule"); copy(rule, raw.path("policy"), List.of("policyId", "policyVersion", "appliedStrength"));
            copy(rule.putObject("scope"), raw.at("/policy/spliceScope"), List.of("maximumStrength"));
            copy(rule.putObject("parentReview"), raw.at("/fullRnaSourceOriginal/pvs1Assessment/parentReview"), List.of("status", "reviewId", "maximumStrength", "functionalRescue", "outcomeMultiplicity", "residualNormalTranscript"));
            var positionChecks = input.path("positionConditionChecks");
            if (positionChecks.isArray() && !positionChecks.isEmpty()) {
                var checks = rule.putArray("positionChecks");
                for (var positionCheck : positionChecks) {
                    var check = checks.addObject();
                    copy(check, positionCheck.path("condition"), List.of("coordinate", "minimumInclusive", "maximumExclusive"));
                    copy(check, positionCheck, List.of("status"));
                    var positions = new java.util.IntSummaryStatistics();
                    for (var position : positionCheck.path("checkedPositions")) positions.accept(position.asInt());
                    if (positions.getCount() > 0)
                        check.put("firstCheckedPosition", positions.getMin()).put("lastCheckedPosition", positions.getMax());
                }
            }
            var source = out.putObject("source"); copy(source, raw.at("/fullRnaSource/sourceRecord"), List.of("sourceId", "version", "url", "sha256"));
            var policySources = out.putArray("policySources");
            for (var policySource : raw.at("/policy/scientificSources")) if (policySources.size() < 16)
                copy(policySources.addObject(), policySource, List.of("sourceId", "version", "url", "sha256"));
        } else {
            var rows = out.putArray("regionChecks");
            for (var row : raw.path("fullRnaRegionAssessments")) {
                if (rows.size() >= 256) break;
                var target = rows.addObject(); copy(target.putObject("referenceProteinRange"), row.path("referenceProteinRange"), List.of("start", "end"));
                copy(target, row.path("regionAssessment"), List.of("regionState", "reasonCode", "functionalConclusion", "repeatStatus", "functionalStatus"));
                var coverage = row.at("/regionAssessment/qualifiedCoverage");
                copy(target.putObject("coverage"), coverage, List.of("status", "acceptedRawSha256", "recordLocator"));
                copy(target.withObject("coverage").putObject("review"), coverage.path("review").isObject() ? coverage.path("review") : coverage.at("/record/review"), List.of("status", "reviewId"));
                var intervals = target.putArray("reviewedIntervals");
                for (var interval : coverage.path("intervals")) {
                    if (intervals.size() >= 256) break;
                    var item = intervals.addObject(); copy(item, interval, List.of("start", "end", "repeatStatus", "functionalStatus"));
                    var links = item.putArray("sourceLocators");
                    for (var link : interval.path("sourceLocators")) if (links.size() < 16 && link.isTextual() && link.asText().length() <= 2048) links.add(link.asText());
                }
                if (row.has("reasonCode")) copy(target, row, List.of("reasonCode"));
            }
        }
        return out;
    }
    private static void initiationDetails(ObjectMapper mapper, ObjectNode out, JsonNode raw) {
        var input = raw.path("resolvedInput"); var assessment = input.path("fullRnaInitiationAssessment");
        copy(out, assessment, List.of("schemaVersion", "methodVersion", "algorithmId", "status", "reasonCode", "strength", "decisionOutcome", "decisionReasonCode",
                "mechanicalEffectSha256", "sourceOriginalSha256", "startChosenByAtgScan", "nmdInferredFromSelectedStart", "transcriptSetComplete",
                "availableStartStatus", "referenceRestartCdsPosition", "baselineScope", "upstreamScopeComplete", "qualifyingReferenceCount",
                "putativeRestartDoesNotProveTranslation", "earlierAlteredInframeMetRnaPosition"));
        copy(out.putObject("nativeStart"), input.at("/fullRnaFacts/nativeStart"), List.of("status", "referenceStartRna1", "referenceEndRna1", "retainedContiguously", "alteredStartRna1"));
        var positions = input.at("/fullRnaFacts/nativeStart/alteredPositions1");
        if (positions.isArray() && positions.size() == 3) out.withObject("nativeStart").set("alteredPositions1", positions.deepCopy());
        if (assessment.path("sourceSpecifiedStart").isObject()) copy(out.putObject("sourceSpecifiedStart"), assessment.path("sourceSpecifiedStart"),
                List.of("alteredStartRna1", "startStatus", "actualCodon", "proteinThroughFirstStop", "interpretation"));
        for (String section : List.of("transcriptReviewScope", "upstreamReviewScope", "blockingCondition", "earlierAlteredInframeMet")) if (assessment.path(section).isObject()) {
            var target = out.putObject(section); var source = assessment.path(section);
            copy(target, source, List.of("complete", "truncated", "unresolvedCount", "requiredCdsStart", "requiredCdsEnd", "condition", "required", "availableFactsLocator",
                    "observedTranslationEstablished", "startChosenByThisCheck", "alteredStartRna1", "selectedTranscript"));
            for (String field : List.of("requiredTranscripts", "observedTranscripts", "referenceRnaPositions1")) boundedValues(target, field, source.path(field), 20);
            var scopes = source.path("availableSourceScopes");
            if (scopes.isArray()) { var rows = target.putArray("availableSourceScopes"); for (var row : scopes) if (rows.size() < 20)
                copy(rows.addObject(), row, List.of("sourceId", "cdsStart", "cdsEnd", "complete", "truncated", "unresolvedCount")); }
        }
        if (assessment.path("sourceDocumentSet").isObject()) for (String field : List.of("requiredSourceIds", "availableSourceIds", "outsideReviewSourceIds"))
            boundedValues(out.withObject("sourceDocumentSet"), field, assessment.path("sourceDocumentSet").path(field), 16);
        var requiredSourceIds = out.withObject("sourceDocumentSet").withArray("requiredSourceIds");
        if (requiredSourceIds.isEmpty()) for (var binding : raw.at("/policy/spliceScope/initiationScope/sourceBindings")) {
            String id = binding.path("sourceId").asText();
            if (requiredSourceIds.size() < 16 && !id.isBlank() && id.length() <= 2048) requiredSourceIds.add(id);
        }
        for (String field : List.of("transcriptFindings", "referenceReviews")) if (assessment.path(field).isArray()) {
            var target = out.putArray(field); var source = assessment.path(field);
            for (var row : source) if (target.size() < 20) copy(target.addObject(), row, List.of("transcript", "differentStart", "functionallyRelevant", "targetUnaffected",
                    "qualification", "recordId", "status", "disposition", "reasonCode", "classification", "cdsPosition", "referenceCdsPosition", "classificationBasis"));
            out.put(field + "Total", source.size()).put(field + "Truncated", source.size() > target.size());
        }
        var sourceChecks = out.putArray("sourceChecks");
        boolean qualified = assessment.path("sourceDocumentsQualified").asBoolean()
                && !"PVS1_RNA_INITIATION_SOURCE_ROLE_AMBIGUOUS".equals(assessment.path("reasonCode").asText());
        out.put("sourceDocumentsQualified", qualified);
        if (qualified && "PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_REVIEW_INCOMPLETE".equals(assessment.path("reasonCode").asText())
                && assessment.path("functionalReviewConditions").isObject()) {
            copy(out.putObject("functionalReviewConditions"), assessment.path("functionalReviewConditions"),
                    List.of("observedStartReported", "physiologicalRelevanceReviewed", "sameRnaProductExperimentReviewed"));
        }
        var original = raw.path("fullRnaSourceOriginal");
        var originals = original.at("/pvs1Assessment/initiationReview/sourceDocuments");
        var primary = new java.util.LinkedHashMap<String, String>();
        var reviewedDocuments = new java.util.LinkedHashMap<String, JsonNode>();
        var quoteLists = new java.util.ArrayList<JsonNode>();
        if (qualified) for (var row : assessment.path("transcriptFindings")) quoteLists.add(row.path("sourceQuotes"));
        for (var check : assessment.path("sourceChecks")) {
            if (sourceChecks.size() >= 16) break;
            copy(sourceChecks.addObject(), check, List.of("sourceId", "role", "status", "sourceLocator", "expectedRawSha256", "actualRawSha256", "rawSha256", "bytes", "reviewApplied"));
            if (!qualified) continue;
            String id = check.path("sourceId").asText();
            var text = originals.path(id).path("rawText");
            if (!text.isTextual() || !"RAW_SHA_MATCH".equals(check.path("status").asText())
                    || !CodingTranscriptMaterialSupply.sha(text.asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(check.path("actualRawSha256").asText())) continue;
            if ("PRIMARY_EVIDENCE".equals(check.path("role").asText())) primary.put(id, text.asText());
            else if (check.path("reviewApplied").asBoolean()) try {
                var doc = mapper.readTree(text.asText()); if (doc == null) continue;
                reviewedDocuments.put(id, doc);
                quoteLists.add(doc.at("/review/sourceQuotes"));
                if ("RESTART_REVIEW".equals(check.path("role").asText())) copy(out.putObject("sourceDeclaredStartReview"), doc,
                        List.of("startStatus", "alteredStartRna1", "functionFinding", "physiologicalRelevance", "functionalQualification"));
            } catch (java.io.IOException invalid) { out.put("sourceCitationIssue", "PINNED_REVIEW_JSON_UNAVAILABLE"); }
        }
        for (var record : assessment.path("referenceReviews")) {
            var document = reviewedDocuments.get(record.path("sourceId").asText());
            if (document == null || !"QUALIFYING".equals(record.path("disposition").asText())) continue;
            String locator = record.path("sourceLocator").asText();
            int recordOffset = locator.lastIndexOf("/records/");
            if (recordOffset >= 0) quoteLists.add(document.at(locator.substring(recordOffset) + "/sourceQuotes"));
        }
        var reviewedIds = new java.util.LinkedHashSet<>(reviewedDocuments.keySet());
        if (out.has("sourceDeclaredStartReview")) out.withObject("sourceDeclaredStartReview").put("layer", "PINNED_SOURCE_DECLARATION_SEPARATE_FROM_RUNTIME_CONCLUSION");
        var citations = out.putArray("sourceQuotes"); var seen = new java.util.HashSet<String>(); int total = 0;
        for (var quotes : quoteLists) for (var quote : quotes) {
            String id = quote.path("sourceId").asText(); String primaryText = primary.get(id);
            int start = quote.path("start").asInt(-1), end = quote.path("end").asInt(-1);
            if (primaryText == null || start < 0 || end <= start || end > primaryText.length() || !primaryText.substring(start, end).equals(quote.path("text").asText())
                    || !seen.add(id + ":" + start + ":" + end)) continue;
            reviewedIds.add(id);
            total++; if (citations.size() >= 8) continue;
            String text = primaryText.substring(start, end); int length = Math.min(600, text.length());
            if (length > 0 && Character.isHighSurrogate(text.charAt(length - 1))) length--;
            citations.addObject().put("sourceId", id).put("sourceRawSha256", CodingTranscriptMaterialSupply.sha(primaryText.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .put("coordinateSystem", "JAVA_UTF16_ZERO_BASED_HALF_OPEN").put("start", start).put("end", end).put("displayEnd", start + length)
                    .put("text", text.substring(0, length)).put("truncated", length < text.length());
        }
        var reviewedSources = out.putArray("reviewedSourceIds"); reviewedIds.forEach(reviewedSources::add);
        out.put("sourceQuoteTotal", total).put("sourceQuotesTruncated", total > citations.size());
    }
    private static void boundedValues(ObjectNode out, String field, JsonNode values, int limit) {
        if (!values.isArray()) return; var array = out.putArray(field);
        for (var value : values) if (array.size() < limit && value.isValueNode() && (!value.isTextual() || value.asText().length() <= 2048)) array.add(value.deepCopy());
        out.put(field + "Total", values.size()).put(field + "Truncated", values.size() > array.size());
    }
    private static void copy(ObjectNode target, JsonNode source, List<String> keys) {
        for (String key : keys) if (source.path(key).isValueNode() && (!source.path(key).isTextual() || source.path(key).asText().length() <= 2048))
            target.set(key, source.path(key).deepCopy());
    }
    @Nullable static JsonNode claim(@Nullable ObjectNode ledger, String code) {
        return ledger == null || !ledger.path("criteria").has(code) ? null : ledger.path("criteria").path(code);
    }
    static void applyView(ObjectNode view, @Nullable JsonNode claim) {
        if (claim == null) return;
        if (completedExclusionClaim(claim)) {
            view.put("status", "NOT_APPLICABLE").put("appliedStrength", "NONE")
                    .put("reasonCode", claim.path("normalizedReasonCode").asText());
            return;
        }
        switch (claim.path("application").asText()) {
            case "NOT_COUNTED" -> view.put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("reasonCode", claim.path("reasonCode").asText());
            case "CONFLICT" -> view.put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("reasonCode", claim.path("reasonCode").asText());
            case "NOT_ADOPTED" -> { if ("MET".equals(claim.path("originalStatus").asText()))
                view.put("status", "NOT_EVALUATED").put("appliedStrength", "NONE").put("reasonCode", "FULL_RNA_CURRENT_EFFECT_BINDING_INVALID"); }
            default -> { }
        }
    }
    /** Only the current, source-bound, actually completed atomic exclusion is projected.
     * Atomic NOT_EVALUATED is retained: it means no evidence was adopted under the v2 contract. */
    private static boolean completedSourceBoundExclusion(JsonNode raw, String originalSha, String effectSha) {
        var evaluation = raw.path("evaluation"); var assessment = raw.at("/resolvedInput/fullRnaAssessment");
        return originalSha.matches("[a-f0-9]{64}") && effectSha.matches("[a-f0-9]{64}")
                && "NOT_EVALUATED".equals(evaluation.at("/evaluationResult/status").asText())
                && "NOT_APPLICABLE".equals(evaluation.at("/criterionApplicability/status").asText())
                && "COMPLETED".equals(evaluation.at("/deterministicTrace/executionStatus").asText())
                && TruncatingNmdPvs1Runtime.EVALUATOR_ID.equals(evaluation.at("/deterministicTrace/evaluatorId").asText())
                && "1.10.1".equals(evaluation.at("/deterministicTrace/evaluatorVersion").asText())
                && "NONE".equals(evaluation.path("appliedStrength").asText())
                && "pvs1-full-rna-assessment-v1".equals(assessment.path("schemaVersion").asText())
                && "1.1.1".equals(assessment.path("methodVersion").asText())
                && "NOT_APPLICABLE".equals(assessment.path("status").asText())
                && originalSha.equals(assessment.path("sourceOriginalSha256").asText())
                && effectSha.equals(assessment.path("mechanicalEffectSha256").asText());
    }
    static boolean completedExclusionClaim(JsonNode claim) {
        return "ACMG_AMP_2015:PVS1".equals(claim.path("criterionId").asText()) && claim.path("completedExclusion").asBoolean()
                && "NOT_ADOPTED".equals(claim.path("application").asText())
                && "NOT_EVALUATED".equals(claim.path("originalStatus").asText()) && "NONE".equals(claim.path("originalStrength").asText())
                && "NOT_APPLICABLE".equals(claim.path("originalApplicability").asText())
                && "COMPLETED".equals(claim.path("originalExecutionStatus").asText());
    }
    static void applyEvaluation(ObjectNode evaluation, @Nullable JsonNode claim) {
        if (claim == null || !List.of("NOT_COUNTED", "CONFLICT").contains(claim.path("application").asText())
                && !("NOT_ADOPTED".equals(claim.path("application").asText()) && "MET".equals(claim.path("originalStatus").asText()))) return;
        evaluation.put("appliedStrength", "NONE");
        evaluation.withObject("evaluationResult").put("status", "NOT_EVALUATED")
                .put("reasonCode", claim.path("reasonCode").asText());
        evaluation.set("applicationAssignment", claim.deepCopy());
    }
    private static boolean adopted(JsonNode claim) { return "ADOPTED".equals(claim.path("application").asText()); }
    private static int strength(String strength) { return switch (strength) { case "VERY_STRONG" -> 4; case "STRONG" -> 3; case "MODERATE" -> 2; case "SUPPORTING" -> 1; default -> 0; }; }
}
