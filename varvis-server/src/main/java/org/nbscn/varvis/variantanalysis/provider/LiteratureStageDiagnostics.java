package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded operational diagnostics from a trusted captured task, never clinical facts
 * or a continuation authorization. Originals remain in the private source snapshot. */
final class LiteratureStageDiagnostics {
    static final String VERSION="literature-stage-public-v1";
    static final int MAX_BYTES=256*1024, MAX_STAGES=8192, DISPLAY_STAGES=512;
    private static final Set<String> STAGE_STATUS=Set.of("SOURCE_CHECKED","NOT_EXTRACTED","NOT_REVIEWED",
            "TEMPORARY_FAILURE","EXECUTION_FAILED","SOURCE_REJECTED","EXTRACTION_INCOMPLETE");
    private static final Set<String> TRANSFER_REASONS=Set.of("PAPER_DOWNLOAD_TIMEOUT","PAPER_DOWNLOAD_TRANSPORT_FAILURE",
            "PAPER_DOWNLOAD_HTTP_FAILURE","PAPER_DOWNLOAD_WORKER_FAILURE","PAPER_DOWNLOAD_BODY_TRUNCATED","PAPER_DOWNLOAD_BODY_SIZE_MISMATCH");
    private static final Pattern ALIAS=Pattern.compile("(?:rs[1-9][0-9]*|(?:N[MRP]_[0-9]+\\.[1-9][0-9]*:)?[cgp]\\.[A-Za-z0-9_*+?>=().;\\[\\]-]+|[A-Z*][1-9][0-9]*[A-Z*])");
    private LiteratureStageDiagnostics() { }

    static ObjectNode task(ObjectMapper json,JsonNode doc,String snapshotPin) {
        var out=base(json);var request=doc.path("request");var worker=doc.path("result");
        if(!binding(out,doc,snapshotPin) || !safeAllele(request.path("selectedAllele"))
                || !safeTarget(request.path("target")) || !compatibleTarget(request.path("target"),worker.path("target")))
            return status(out,"WITHHELD","PM3_STAGE_BINDING_INVALID");
        out.set("target",target(json,request.path("target")));out.set("selectedAllele",allele(json,request.path("selectedAllele")));
        copyCode(worker,out,"status","workerStatus");copyCode(worker,out,"stopReason","stopReason");
        boolean reconcile=Pm3LiteratureJobs.reconciliationRequired(worker);
        out.put("reconciliationRequired",reconcile);
        if(reconcile)out.put("processingContinuationBlockedReason","PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED");
        var recovery=out.putArray("acquisitionRecovery");var projected=out.putObject("acquisitionProjection");
        var raw=worker.path("acquisitionRecovery");
        if(raw.isMissingNode())projected.put("status","NOT_RECORDED");
        else if(!raw.isArray() || raw.size()>200) {
            projected.put("status","WITHHELD").put("reasonCode","PM3_ACQUISITION_STATE_INVALID");
            if(raw.isArray())projected.put("total",raw.size()).put("omitted",raw.size());
        }
        else {
            boolean valid=true;
            for(var row:raw) {
                if(!row.isObject() || !(row.path("pmcid").isNull() || pmcid(row.path("pmcid")))
                        || !"ACQUISITION".equals(row.path("stage").asText())
                        || !"SYSTEM_TRANSFER_INCOMPLETE".equals(row.path("status").asText())
                        || !TRANSFER_REASONS.contains(row.path("reasonCode").asText())
                        || !"BOUND_CONTINUATION_WITH_EXISTING_TRANSPORT_BUDGETS".equals(row.path("retryPolicy").asText())
                        || !row.path("sourceAbsenceProven").isBoolean() || row.path("sourceAbsenceProven").asBoolean()) {valid=false;break;}
                var item=recovery.addObject();item.set("pmcid",row.path("pmcid"));
                for(String key:List.of("stage","status","reasonCode","retryPolicy"))item.set(key,row.path(key));
                item.put("sourceAbsenceProven",false);
            }
            if(!valid){recovery.removeAll();projected.put("status","WITHHELD").put("reasonCode","PM3_ACQUISITION_STATE_INVALID")
                    .put("total",raw.size()).put("omitted",raw.size());}
            else projected.put("status","AVAILABLE").put("total",raw.size()).put("omitted",0);
        }
        if(worker.path("readingLoop").isObject())out.set("readingLoop",readingLoop(json,worker.path("readingLoop")));
        status(out,"AVAILABLE",null);
        return bounded(json,out);
    }

    static void paper(ObjectMapper json,ObjectNode progress,JsonNode doc,JsonNode paper,String snapshotPin) {
        var request=doc.path("request");var worker=doc.path("result");
        progress.put("projectionVersion",VERSION).put("historyActionsAllowed",false).put("requiresCurrentTaskControls",true);
        boolean bound=binding(progress,doc,snapshotPin) && pmcid(paper.path("pmcid")) && sha(paper.path("bundleSha256"))
                && safeTarget(request.path("target")) && compatibleTarget(request.path("target"),worker.path("target"))
                && compatibleTarget(request.path("target"),paper.path("target"));
        if(bound && !"pm3-discovery-process-v1".equals(worker.path("schemaVersion").asText()))
            bound=request.path("pmcid").equals(paper.path("pmcid")) && request.path("bundleSha256").equals(paper.path("bundleSha256"));
        boolean reconcile=Pm3LiteratureJobs.reconciliationRequired(worker);
        progress.put("reconciliationRequired",reconcile);
        if(reconcile)progress.put("processingContinuationBlockedReason","PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED");
        if(bound) {
            progress.set("bundleSha256",paper.path("bundleSha256"));progress.set("target",target(json,request.path("target")));
        }
        status(progress,bound?"AVAILABLE":"WITHHELD",bound?null:"PM3_STAGE_BINDING_INVALID");
        progress.set("processingCheckpoint",checkpoint(json,paper,bound));
    }

    static ObjectNode checkpoint(ObjectMapper json,JsonNode paper,boolean bound) {
        var out=base(json);var state=paper.path("processingCheckpoint");
        if(state.isMissingNode())return status(out,"NOT_RECORDED",null);
        if(!bound || !paper.path("pmcid").equals(state.path("pmcid"))
                || !paper.path("bundleSha256").equals(state.path("bundleSha256"))
                || !paper.path("target").equals(state.path("target")))return status(out,"WITHHELD","PM3_STAGE_BINDING_INVALID");
        if(!"pm3-paper-processing-checkpoint-v1".equals(state.path("schemaVersion").asText())
                || !state.path("stages").isArray() || state.path("stages").size()>MAX_STAGES
                || !state.path("newModelWorkStoppedAfterFailure").isBoolean()
                || !"EXPLICIT_CONTINUATION_WITH_EXISTING_SHARED_LEDGER_GATES".equals(state.path("retryPolicy").asText())
                || !state.path("savedScoreAdopted").isBoolean() || state.path("savedScoreAdopted").asBoolean()
                || paper.has("chunkCount") && !integer(paper.path("chunkCount"),1,4096))
            return status(out,"WITHHELD","PM3_STAGE_STATE_INVALID");
        var stages=out.putArray("stages");var seen=new HashSet<String>();int pending=0,rejected=0;
        int chunkCount=integer(paper.path("chunkCount"),1,4096)?paper.path("chunkCount").asInt():4096;
        for(var row:state.path("stages")) {
            String stage=row.path("stage").asText(),stateCode=row.path("status").asText();
            if(!row.isObject() || !integer(row.path("chunkIndex"),0,chunkCount-1)
                    || !Set.of("EXTRACTION","REVIEW").contains(stage) || !STAGE_STATUS.contains(stateCode)
                    || "NOT_EXTRACTED".equals(stateCode) && !"EXTRACTION".equals(stage)
                    || Set.of("NOT_REVIEWED","EXTRACTION_INCOMPLETE").contains(stateCode) && !"REVIEW".equals(stage)
                    || !seen.add(row.path("chunkIndex").asText()+":"+stage)
                    || row.has("modelCalls") && !integer(row.path("modelCalls"),0,Integer.MAX_VALUE)
                    || ("SOURCE_CHECKED".equals(stateCode) || "SOURCE_REJECTED".equals(stateCode)) && !sha(row.path("artifactSha256"))
                    || row.has("artifactSha256") && !sha(row.path("artifactSha256"))
                    || row.has("reasonCode") && !code(row.path("reasonCode")))
                return status(base(json),"WITHHELD","PM3_STAGE_STATE_INVALID");
            if(!"SOURCE_CHECKED".equals(stateCode) && !"SOURCE_REJECTED".equals(stateCode))pending++;
            if("SOURCE_REJECTED".equals(stateCode))rejected++;
            if(stages.size()<DISPLAY_STAGES) {
                var item=stages.addObject();
                for(String key:List.of("chunkIndex","stage","status","modelCalls","artifactSha256","reasonCode"))if(row.has(key))item.set(key,row.path(key));
            }
        }
        for(String key:List.of("schemaVersion","pmcid","bundleSha256","newModelWorkStoppedAfterFailure","retryPolicy"))out.set(key,state.path(key));
        out.set("target",target(json,paper.path("target")));out.put("savedScoreAdopted",false);
        var projection=out.putObject("projection").put("status","AVAILABLE").put("stageTotal",state.path("stages").size())
                .put("stageOmitted",state.path("stages").size()-stages.size()).put("stagePending",pending).put("sourceRejectedStages",rejected);
        projection.put("truncated",state.path("stages").size()>stages.size());
        return bounded(json,out);
    }

    static void progress(ObjectMapper json,ObjectNode out,JsonNode paper,JsonNode worker) {
        if(!pmcid(paper.path("pmcid")))out.remove("pmcid");
        if(!code(paper.path("status")))out.put("status","UNKNOWN");
        if(integer(paper.path("chunkCount"),0,4096))out.set("chunkCount",paper.path("chunkCount"));
        for(String key:List.of("completedReviewChunks","pendingReviewChunks"))indices(json,paper,out,key);
        issues(paper,out);
        if(paper.path("readingPlan").isObject())out.set("readingPlan",readingPlan(json,paper.path("readingPlan")));
        if(worker.path("readingLoop").isObject())out.set("readingLoop",readingLoop(json,worker.path("readingLoop")));
    }

    /** Cap only the operational row. Existing clinical source material is appended
     * by the caller afterwards and keeps its own evidence contract. */
    static void capPaper(ObjectMapper json,ObjectNode progress) {
        if(fits(json,progress))return;
        var projection=progress.withObject("/projection").put("status","WITHHELD")
                .put("reasonCode","PM3_STAGE_PUBLIC_SIZE_LIMIT").put("truncated",true);
        var arrays=projection.putArray("withheldArrays");
        for(String key:List.of("completedReviewChunks","pendingReviewChunks"))
            withholdArray(progress,key,key,arrays);
        if(progress.path("readingPlan") instanceof ObjectNode plan)
            for(String key:List.of("orderedRelevantChunks","unselectedChunks","completedReviewChunks",
                    "pendingRelevantChunks","pendingAllChunks","blockedReviewChunks"))
                withholdArray(plan,key,"readingPlan."+key,arrays);
        if(progress.path("processingCheckpoint") instanceof ObjectNode checkpoint
                && checkpoint.path("stages").isArray()) {
            int total=checkpoint.at("/projection/stageTotal").asInt(checkpoint.path("stages").size());
            checkpoint.remove("stages");
            checkpoint.withObject("/projection").put("status","WITHHELD").put("reasonCode","PM3_STAGE_PUBLIC_SIZE_LIMIT")
                    .put("stageOmitted",total).put("stageDisplayed",0).put("truncated",true);
        }
        if(fits(json,progress))return;
        // Defensive fallback retains exact pins and known full counts, never a
        // fabricated zero/NOT_RECORDED. No original source object is changed.
        var saved=progress.deepCopy();progress.removeAll();
        for(String key:List.of("projectionVersion","taskId","capturedResultSha256","selectionSha256",
                "sourceSnapshotSha256","pmcid","bundleSha256","target","status","chunkCount",
                "reconciliationRequired","processingContinuationBlockedReason","historyActionsAllowed",
                "requiresCurrentTaskControls","absenceOfCasesEstablished","projection"))
            if(saved.has(key))progress.set(key,saved.path(key));
        if(saved.path("processingCheckpoint").isObject()) {
            var checkpoint=progress.putObject("processingCheckpoint");
            for(String key:List.of("projectionVersion","schemaVersion","pmcid","bundleSha256","projection",
                    "newModelWorkStoppedAfterFailure","savedScoreAdopted","retryPolicy"))
                if(saved.path("processingCheckpoint").has(key))checkpoint.set(key,saved.path("processingCheckpoint").path(key));
        }
    }
    private static void withholdArray(ObjectNode owner,String key,String path,
            com.fasterxml.jackson.databind.node.ArrayNode summaries) {
        if(!owner.path(key).isArray())return;
        int count=owner.path(key).size();owner.remove(key);
        summaries.addObject().put("field",path).put("sourceItems",count).put("displayed",0).put("omitted",count);
    }
    private static boolean fits(ObjectMapper json,JsonNode out) {
        try {return json.writeValueAsBytes(out).length<=MAX_BYTES;}
        catch(java.io.IOException ignored) {return false;}
    }

    static ObjectNode readingPlan(ObjectMapper json,JsonNode raw) {
        var out=json.createObjectNode();
        if(Set.of("pm3-need-reading-plan-v1","pm3-need-reading-plan-v2").contains(raw.path("schemaVersion").asText()))out.set("schemaVersion",raw.path("schemaVersion"));
        copyCode(raw,out,"scope","scope");
        if(pmcid(raw.path("pmcid")))out.set("pmcid",raw.path("pmcid"));
        if(sha(raw.path("bundleSha256")))out.set("bundleSha256",raw.path("bundleSha256"));
        out.put("absenceProven",false);
        for(String key:List.of("orderedRelevantChunks","unselectedChunks","completedReviewChunks","pendingRelevantChunks","pendingAllChunks","blockedReviewChunks"))
            indices(json,raw,out,key);
        return out;
    }
    static ObjectNode readingLoop(ObjectMapper json,JsonNode raw) {
        var out=json.createObjectNode();
        if("pm3-reading-loop-v1".equals(raw.path("schemaVersion").asText()))out.set("schemaVersion",raw.path("schemaVersion"));
        for(String key:List.of("state","stopReason","failedAttemptAccounting"))copyCode(raw,out,key,key);
        for(String key:List.of("roundCount","maximumRounds"))if(integer(raw.path(key),0,128))out.set(key,raw.path(key));
        if(raw.path("feedback").isObject()) {
            var from=raw.path("feedback");var feedback=out.putObject("feedback");
            for(String key:List.of("state","stopReason"))copyCode(from,feedback,key,key);
            for(String key:List.of("sourceConflict","hasPendingRelevantWork"))if(from.path(key).isBoolean())feedback.set(key,from.path(key));
            // This is a saved observation, not permission for another task.
            feedback.put("requiresCurrentTaskControls",true);
        }
        return out;
    }
    static void indices(ObjectMapper json,JsonNode from,ObjectNode to,String key) {
        var raw=from.path(key);if(!raw.isArray() || raw.size()>4096)return;
        for(var v:raw)if(!integer(v,0,4095))return;
        to.set(key,raw.deepCopy());
    }
    static void issues(JsonNode from,ObjectNode to) {
        var raw=from.path("issues");if(!raw.isArray())return;
        var out=to.putArray("issues");
        for(var v:raw)if(code(v) && out.size()<128)out.add(v);
        if(raw.size()>out.size())to.put("omittedIssueCount",raw.size()-out.size());
    }
    private static ObjectNode base(ObjectMapper json) {
        return json.createObjectNode().put("projectionVersion",VERSION).put("historyActionsAllowed",false).put("requiresCurrentTaskControls",true);
    }
    private static ObjectNode status(ObjectNode out,String status,String reason) {
        var projection=out.withObject("/projection").put("status",status);
        if(reason!=null)projection.put("reasonCode",reason);
        return out;
    }
    private static ObjectNode bounded(ObjectMapper json,ObjectNode out) {
        try {if(json.writeValueAsBytes(out).length<=MAX_BYTES)return out;}catch(java.io.IOException ignored) { /* No raw exception is public. */ }
        return status(base(json),"WITHHELD","PM3_STAGE_PUBLIC_SIZE_LIMIT");
    }
    private static boolean binding(ObjectNode out,JsonNode doc,String snapshotPin) {
        if(!sha(doc.path("taskId")) || !sha(doc.path("resultSha256")) || !sha(doc.at("/request/selectionSha256"))
                || snapshotPin==null || !snapshotPin.matches("[a-f0-9]{64}"))return false;
        out.set("taskId",doc.path("taskId"));out.set("capturedResultSha256",doc.path("resultSha256"));
        out.set("selectionSha256",doc.at("/request/selectionSha256"));out.put("sourceSnapshotSha256",snapshotPin);
        out.withObject("/projection").put("bindingPinKind","WORKER_RESULT_RAW_SHA256");return true;
    }
    private static boolean code(JsonNode value) {return value.isTextual() && value.asText().matches("[A-Z][A-Z0-9_]{0,191}");}
    private static boolean sha(JsonNode value) {return value.isTextual() && value.asText().matches("[a-f0-9]{64}");}
    private static boolean pmcid(JsonNode value) {return value.isTextual() && value.asText().length()<=32 && value.asText().matches("PMC[1-9][0-9]*");}
    private static boolean integer(JsonNode value,int minimum,int maximum) {return value.isIntegralNumber() && value.canConvertToInt() && value.asInt()>=minimum && value.asInt()<=maximum;}
    private static void copyCode(JsonNode from,ObjectNode to,String fromKey,String toKey) {if(code(from.path(fromKey)))to.set(toKey,from.path(fromKey));}
    private static boolean safeTarget(JsonNode value) {
        if(!value.isObject() || !value.path("geneSymbol").isTextual() || !value.path("geneSymbol").asText().matches("[A-Za-z][A-Za-z0-9.-]{0,39}")
                || !value.path("variantAliases").isArray() || value.path("variantAliases").isEmpty() || value.path("variantAliases").size()>32)return false;
        for(var alias:value.path("variantAliases"))if(!alias.isTextual() || alias.asText().length()>300 || !ALIAS.matcher(alias.asText()).matches())return false;
        return true;
    }
    private static boolean compatibleTarget(JsonNode requested,JsonNode returned) {
        if(!safeTarget(requested) || !safeTarget(returned) || !requested.path("geneSymbol").equals(returned.path("geneSymbol")))return false;
        var allowed=new HashSet<String>();
        for(var alias:requested.path("variantAliases")) {String value=alias.asText();allowed.add(value);allowed.add(value.substring(value.lastIndexOf(':')+1));}
        var actual=new HashSet<String>();for(var alias:returned.path("variantAliases"))if(!allowed.contains(alias.asText()))return false;else actual.add(alias.asText());
        for(var alias:requested.path("variantAliases"))if(!actual.contains(alias.asText()) && !actual.contains(alias.asText().substring(alias.asText().lastIndexOf(':')+1)))return false;
        return true;
    }
    private static ObjectNode target(ObjectMapper json,JsonNode value) {
        var out=json.createObjectNode();out.set("geneSymbol",value.path("geneSymbol"));out.set("variantAliases",value.path("variantAliases").deepCopy());return out;
    }
    private static boolean safeAllele(JsonNode value) {
        return value.isObject() && Set.of("GRCh37","GRCh38").contains(value.path("assembly").asText())
                && value.path("contig").isTextual() && value.path("contig").asText().matches("(?:[1-9]|1[0-9]|2[0-2]|X|Y|MT)")
                && value.path("position").isIntegralNumber() && value.path("position").canConvertToLong() && value.path("position").asLong()>0
                && value.path("reference").isTextual() && value.path("reference").asText().matches("[ACGTN]{1,10000}")
                && value.path("alternate").isTextual() && value.path("alternate").asText().matches("[ACGTN]{1,10000}");
    }
    private static ObjectNode allele(ObjectMapper json,JsonNode value) {
        var out=json.createObjectNode();for(String key:List.of("assembly","contig","position","reference","alternate"))out.set(key,value.path(key));return out;
    }
}
