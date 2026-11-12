package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Scheduling feedback over the existing assessor, never an alternative scorer.
 * Requirements keep their original reason/status. Unknown phase is not made a
 * mandatory requirement when the existing rule legitimately awards less weight.
 */
public final class Pm3ReadingFeedback {
    public static final String VERSION="pm3-reading-feedback-v2";
    private final ObjectMapper json;
    public Pm3ReadingFeedback(ObjectMapper json){this.json=json;}

    public ObjectNode build(JsonNode worker,JsonNode assessment,String requestPin) {
        return build(worker,assessment,requestPin,false);
    }
    public ObjectNode build(JsonNode worker,JsonNode assessment,String requestPin,boolean stopWhenEvaluable) {
        var out=json.createObjectNode().put("schemaVersion",VERSION).put("requestSha256",requestPin)
                .put("scientificDecision",false).put("fullPaperRead",false);
        var needs=new LinkedHashMap<String,ObjectNode>();
        boolean conflict=false,qualified=false;
        for(var disease:assessment.path("diseaseAssessments")) {
            var calculation=disease.path("calculation");
            qualified |= Set.of("MET","NOT_MET","NOT_APPLICABLE").contains(calculation.path("status").asText());
            for(var contribution:calculation.path("contributions")) {
                for(var requirement:contribution.path("requirements")) {
                    String status=requirement.path("status").asText(),reason=requirement.path("reason").asText();
                    if(Set.of("MISSING_INFORMATION","CONFLICT").contains(status)) {
                        addContribution(needs,reason,status,contribution);
                        conflict |= "CONFLICT".equals(status);
                    }
                }
                // A zero-weight VUS/unknown-phase observation may be clarified,
                // but phase is not imposed on already eligible P/LP branches.
                if("PM3_UNKNOWN_PHASE_VUS_NO_POINTS".equals(contribution.path("reason").asText()))
                    addContribution(needs,"PM3_UNKNOWN_PHASE_VUS_NO_POINTS","OPTIONAL_CLARIFICATION",contribution);
            }
        }
        for(var issue:assessment.path("issues")) {
            if(!needs.isEmpty() && "PM3_NO_QUALIFIED_OBSERVATIONS".equals(issue.asText()))continue;
            add(needs,issue.asText(),"MISSING_INFORMATION","");
        }
        if(assessment.path("diseaseAssessments").isEmpty() && needs.isEmpty())
            add(needs,"PM3_NO_SOURCE_VERIFIED_GENOTYPE_OBSERVATIONS","MISSING_INFORMATION","");
        var papers=out.putArray("papers");boolean pending=false,interrupted=false,planMissing=false,blocked=false,hasNeed=false,readableNeed=false;
        Iterable<JsonNode> source="pm3-discovery-process-v1".equals(worker.path("schemaVersion").asText())?worker.path("papers"):List.of(worker);
        var paperCounts = new HashMap<String,Integer>();
        for (var paper : source) paperCounts.merge(paper.path("pmcid").asText(), 1, (existingCount, addedCount) -> existingCount + addedCount);
        var unassigned = out.putArray("unassignedNeeds");
        for (var need : needs.values()) if (!need.path("observationId").asText().isBlank()
                && paperCounts.getOrDefault(owner(need), 0) != 1) unassigned.add(need.deepCopy());
        if (!unassigned.isEmpty()) {
            interrupted = true;
            out.put("stopReason", "PM3_READING_NEED_SOURCE_UNRESOLVED");
        }
        for(var paper:source) {
            var row=papers.addObject().put("pmcid",paper.path("pmcid").asText()).put("bundleSha256",paper.path("bundleSha256").asText());
            var paperNeeds=new LinkedHashMap<String,ObjectNode>();
            for (var need : needs.entrySet()) {
                var value = need.getValue();
                String owner = owner(value);
                if (value.path("observationId").asText().isBlank()
                        || (paperCounts.getOrDefault(owner, 0) == 1 && owner.equals(paper.path("pmcid").asText())))
                    paperNeeds.put(need.getKey(), value.deepCopy());
            }
            // Cases without an assembled observation never reach the scorer. Keep their
            // precise source-bound gaps in the reading loop instead of a generic no-cases need.
            for (var caseRow : paper.at("/observations/cases")) for (var gap : caseRow.at("/semantics/assemblyGaps")) {
                String reason = gap.path("reasonCode").asText(), label = caseRow.path("caseLabel").asText();
                String status = reason.contains("CONFLICT") ? "CONFLICT" : "MISSING_INFORMATION";
                conflict |= "CONFLICT".equals(status);
                add(paperNeeds, reason, status, label);
                var need = paperNeeds.get(reason + "\n" + status + "\n" + label);
                if (need == null) continue;
                // These are scheduling references to already recorded facts, not new facts.
                var ids = new TreeSet<String>();
                for (var fact : caseRow.path("facts")) {
                    for (String path : List.of("/citation/unitId", "/ownership/anchorUnitId")) {
                        String id = fact.at(path).asText();
                        if (!id.isBlank() && id.length() <= 1024) ids.add(id);
                    }
                }
                need.set("sourceUnitIds", json.valueToTree(ids.stream().limit(64).toList()));
                for (String field : List.of("requiredFields", "requiredAnyOf"))
                    if (gap.path(field).isArray()) need.set(field, gap.path(field).deepCopy());
            }
            for(var caseRow:paper.path("observations").path("cases"))for(var observation:caseRow.path("resolvedObservations")) {
                var policy=observation.path("policySelection");
                if(!"SELECTED".equals(policy.path("status").asText()))
                    add(paperNeeds,"PM3_POLICY_CONTEXT_UNRESOLVED","MISSING_INFORMATION",caseRow.path("caseLabel").asText());
            }
            row.set("needs",json.valueToTree(paperNeeds.values()));
            if (paperNeeds.size() > 64) {
                row.set("needs", json.valueToTree(paperNeeds.values().stream().limit(64).toList()));
                row.put("omittedNeedCount", paperNeeds.size() - 64);
                interrupted = true;
                out.put("stopReason", "PM3_READING_NEEDS_LIMIT");
            }
            hasNeed |= !paperNeeds.isEmpty();
            readableNeed |= paperNeeds.values().stream().anyMatch(n->!Set.of("EXTERNAL_RESOURCE","UNSUPPORTED").contains(n.path("group").asText()));
            var plan=paper.path("readingPlan");
            if(!plan.isObject() || !plan.path("pendingRelevantChunks").isArray())planMissing=true;
            else {
                pending |= !plan.path("pendingRelevantChunks").isEmpty();
                row.set("pendingRelevantChunks",plan.path("pendingRelevantChunks").deepCopy());
                row.set("completedReviewChunks",paper.path("completedReviewChunks").deepCopy());
                blocked |= !plan.path("blockedReviewChunks").isEmpty();
                row.set("blockedReviewChunks",plan.path("blockedReviewChunks").deepCopy());
                row.put("scope",plan.path("scope").asText());
            }
            for(var issue:paper.path("issues"))if(interrupts(issue.asText(),worker))interrupted=true;
            boolean extractionIncomplete = false;
            for (var issue : paper.path("issues")) extractionIncomplete |= issue.asText().contains("EXTRACTION") || issue.asText().contains("TIMEOUT");
            row.put("factAvailability", extractionIncomplete ? "EXTRACTION_INCOMPLETE"
                    : !plan.isObject() || !plan.path("pendingRelevantChunks").isArray() ? "READING_INCOMPLETE"
                    : !plan.path("blockedReviewChunks").isEmpty() ? "VERIFICATION_INCOMPLETE"
                    : !plan.path("pendingRelevantChunks").isEmpty() ? "READING_INCOMPLETE"
                    : !paperNeeds.isEmpty() ? "CHECKED_SCOPE_INSUFFICIENT" : "CURRENT_REQUIREMENTS_CHECKED")
                    .put("absenceEstablished", false);
            for(var n:paperNeeds.values())if(!"OPTIONAL_CLARIFICATION".equals(n.path("status").asText()))qualified=false;
        }
        if(papers.isEmpty())planMissing=true;
        if("pm3-discovery-process-v1".equals(worker.path("schemaVersion").asText())
                && (worker.path("discoveryState").path("resumeAvailable").asBoolean()
                    || !worker.path("pendingRegisteredPapers").isEmpty()
                    || !worker.path("pendingDiscoveryCandidates").isEmpty()))pending=true;
        for(var issue:worker.path("issues"))if(interrupts(issue.asText(),worker))interrupted=true;
        boolean currentComplete=stopWhenEvaluable && qualified && !conflict && needs.isEmpty();
        // Sufficient current observations do not imply maximum strength or that
        // unread blocks contain no further/contrary evidence. Keep coverage.
        String state=interrupted || planMissing?"INCOMPLETE":hasNeed && !readableNeed?"DEPENDENCY_UNRESOLVED":currentComplete?"SUFFICIENT_WITHIN_CHECKED_SCOPE":pending?"CONTINUE_READING":blocked?"INCOMPLETE":conflict?"CONFLICT_REQUIRES_REVIEW":
                qualified?"SUFFICIENT_WITHIN_CHECKED_SCOPE":"SEARCHED_SCOPE_INSUFFICIENT";
        if(currentComplete && !interrupted && !planMissing)out.put("stopReason","PM3_CURRENT_REQUIREMENTS_SATISFIED");
        if(state.equals("DEPENDENCY_UNRESOLVED"))out.put("stopReason","PM3_EXTERNAL_OR_UNSUPPORTED_REQUIREMENT");
        out.put("state",state).put("canContinueWithinTask",state.equals("CONTINUE_READING"))
                .put("hasPendingRelevantWork",pending).put("sourceConflict",conflict)
                .put("scope","CURRENT_REGISTERED_SOURCES_HEURISTIC_SELECTION");
        return out;
    }

    private void addContribution(Map<String,ObjectNode> needs,String reason,String status,JsonNode contribution) {
        var need = add(needs,reason,status,contribution.path("observationId").asText());
        if (contribution.path("sourceReferences").isArray()) {
            need.set("sourceReferences", contribution.path("sourceReferences").deepCopy());
            var units = new TreeSet<String>();
            for (var ref : contribution.path("sourceReferences")) {
                String text = ref.asText(); int separator = text.indexOf('#');
                if (separator > 0 && text.substring(0, separator).matches("PMC[1-9][0-9]*")) {
                    String unit = text.substring(separator + 1);
                    if (!unit.isBlank() && unit.length() <= 1024 && !unit.startsWith("case:")) units.add(unit);
                }
            }
            need.set("sourceUnitIds", json.valueToTree(units.stream().limit(64).toList()));
        }
    }
    // The scorer emits exact PMC#unit references. Never infer a source from a
    // case label, observation hash or list position; ambiguous ownership waits.
    private static String owner(JsonNode need) {
        var owners = new HashSet<String>();
        for (var ref : need.path("sourceReferences")) {
            String text = ref.asText(); int separator = text.indexOf('#');
            if (separator <= 0 || separator == text.length() - 1
                    || !text.substring(0, separator).matches("PMC[1-9][0-9]*")) return "";
            owners.add(text.substring(0, separator));
        }
        return owners.size() == 1 ? owners.iterator().next() : "";
    }
    private ObjectNode add(Map<String,ObjectNode> needs,String reason,String status,String observation) {
        if(!reason.matches("[A-Z][A-Z0-9_]{1,160}"))reason="PM3_UNSUPPORTED_REQUIREMENT";
        String group=reason.contains("CONFLICT") || reason.equals("PM3_TARGET_MISMATCH")?"CONFLICT":switch(reason) {
            case "PM3_NO_SOURCE_VERIFIED_GENOTYPE_OBSERVATIONS","PM3_NO_QUALIFIED_OBSERVATIONS",
                 "PM3_CASE_PARTNER_OR_HOMOZYGOSITY_UNRESOLVED" -> "GENOTYPE";
            case "PM3_CASE_IDENTITY_OR_INDEPENDENCE_UNRESOLVED" -> "CASE";
            case "PM3_AFFECTED_RECESSIVE_CONTEXT_UNRESOLVED" -> "DISEASE";
            case "PM3_TARGET_IDENTITY_UNRESOLVED","PM3_PARTNER_IDENTITY_UNRESOLVED",
                 "PM3_CASE_GENE_UNRESOLVED", "PM3_CASE_TARGET_REPRESENTATION_UNRESOLVED",
                 "PM3_CASE_TARGET_ALIAS_NOT_MATCHED", "PM3_VARIANT_REPRESENTATIONS_UNRESOLVED" -> "IDENTITY";
            case "PM3_TARGET_RARITY_NOT_ESTABLISHED","PM3_PARTNER_RARITY_NOT_ESTABLISHED","PM3_POLICY_CONTEXT_UNRESOLVED" -> "EXTERNAL_RESOURCE";
            case "PM3_PARTNER_CLASSIFICATION_INDEPENDENCE_UNRESOLVED","PM3_PARTNER_CLASSIFICATION_MISSING" -> "PARTNER_EVIDENCE";
            case "PM3_UNKNOWN_PHASE_VUS_NO_POINTS","PM3_PHASE_ORIGINAL_MISSING" -> "PHASE";
            default -> "UNSUPPORTED";
        };
        needs.putIfAbsent(reason+"\n"+status+"\n"+observation,json.createObjectNode().put("reasonCode",reason).put("group",group)
                .put("status",status).put("observationId",observation));
        return Objects.requireNonNull(needs.get(reason+"\n"+status+"\n"+observation));
    }
    private boolean interrupts(String reason,JsonNode worker) {
        return !Set.of("PM3_CHUNK_BUDGET_REACHED","PM3_MODEL_REVIEW_SOURCE_CHECK_REJECTED",
                "PM3_MODEL_TARGET_EXTRACTION_INCOMPLETE").contains(reason) && !("MODEL_CACHE_MISSING".equals(reason)
                && worker.path("readingCachePass").asBoolean());
    }
}
