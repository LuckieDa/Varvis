package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** Synthetic captured-source contract vectors; no provider or clinical validation. */
class T02Pm3StageProjectionTest {
    private final Pm3SelectedBatchTest fixture=new Pm3SelectedBatchTest();
    private final ObjectMapper json=fixture.json;
    private ObjectNode target() {
        var target=json.createObjectNode().put("geneSymbol","PAH");target.putArray("variantAliases").add("NM_000277.3:c.2C>G");return target;
    }
    private ObjectNode paper() {
        var paper=fixture.paper().put("status","PARTIAL").put("bundleSha256","b".repeat(64)).put("chunkCount",300);
        paper.set("target",target());
        var checkpoint=paper.putObject("processingCheckpoint").put("schemaVersion","pm3-paper-processing-checkpoint-v1")
                .put("pmcid","PMC123").put("bundleSha256","b".repeat(64)).put("newModelWorkStoppedAfterFailure",false)
                .put("retryPolicy","EXPLICIT_CONTINUATION_WITH_EXISTING_SHARED_LEDGER_GATES").put("savedScoreAdopted",false);
        checkpoint.set("target",target());
        checkpoint.putArray("stages").addObject().put("chunkIndex",0).put("stage","EXTRACTION")
                .put("status","SOURCE_CHECKED").put("modelCalls",0).put("artifactSha256","d".repeat(64));
        return paper;
    }
    private void boundDocument(ObjectNode doc,boolean discovery) {
        doc.put("resultSha256","c".repeat(64));var request=doc.withObject("/request");request.put("selectionSha256","e".repeat(64));
        request.set("target",target());
        if(discovery){request.put("schemaVersion","pm3-public-discovery-task-v1");request.remove(List.of("pmcid","bundleSha256"));}
        else request.put("schemaVersion","pm3-public-paper-task-v1").put("pmcid","PMC123").put("bundleSha256","b".repeat(64));
    }
    private ObjectNode evaluate(ObjectNode worker,Consumer<ObjectNode> modify) {
        return fixture.batch(worker,doc->{boundDocument(doc,"pm3-discovery-process-v1".equals(worker.path("schemaVersion").asText()));modify.accept(doc);})
                .evaluateContext(fixture.context(fixture.paper()));
    }
    private ObjectNode evaluate(ObjectNode worker) {return evaluate(worker,doc->{});}
    private ObjectNode discovery() {
        var worker=json.createObjectNode().put("schemaVersion","pm3-discovery-process-v1").put("status","PARTIAL");
        worker.set("target",target());worker.putArray("papers");return worker;
    }
    private ObjectNode transfer(ObjectNode worker) {
        return worker.putArray("acquisitionRecovery").addObject().put("pmcid","PMC123").put("stage","ACQUISITION")
                .put("status","SYSTEM_TRANSFER_INCOMPLETE").put("reasonCode","PAPER_DOWNLOAD_TIMEOUT")
                .put("retryPolicy","BOUND_CONTINUATION_WITH_EXISTING_TRANSPORT_BUDGETS").put("sourceAbsenceProven",false);
    }

    @Test void actualPublicSummaryHasBoundCheckpointAndNoNewExtrasOrContinuationAuthority() throws Exception {
        var p=paper();p.withObject("/processingCheckpoint").put("artifactPath","D:/private/do-not-export");
        p.at("/processingCheckpoint/stages/0").withObject("").put("quote","do-not-export original paragraph");
        p.withObject("/target").put("privatePath","D:/private/target");
        p.withObject("/processingCheckpoint/target").put("privatePath","D:/private/target");
        var r=evaluate(p);var progress=r.at("/publicSummary/paperCoverage/0");var checkpoint=progress.path("processingCheckpoint");
        assertEquals("AVAILABLE",checkpoint.at("/projection/status").asText(),r.toPrettyString());
        assertEquals("a".repeat(64),progress.path("taskId").asText());assertEquals("c".repeat(64),progress.path("capturedResultSha256").asText());
        assertEquals("WORKER_RESULT_RAW_SHA256",progress.at("/projection/bindingPinKind").asText());
        assertEquals("b".repeat(64),progress.path("bundleSha256").asText());
        assertEquals(1,checkpoint.at("/projection/stageTotal").asInt());assertEquals(0,checkpoint.at("/projection/stagePending").asInt(-1));
        assertEquals("d".repeat(64),checkpoint.at("/stages/0/artifactSha256").asText());
        assertFalse(checkpoint.toString().contains("do-not-export"));assertFalse(progress.path("target").has("privatePath"));
        assertFalse(progress.path("historyActionsAllowed").asBoolean(true));assertFalse(progress.has("canContinueProcessing"));
        assertEquals(fixture.batch(fixture.paper()).evaluateContext(fixture.context(fixture.paper())).path("evaluationResult"),r.path("evaluationResult"));
        assertEquals(r,json.readTree(json.writeValueAsBytes(r)));
    }
    @Test void sourceAcquisitionWithoutPapersStaysAtTaskLevelAndRetainsSourceReference() {
        var worker=discovery();transfer(worker).put("privatePath","D:/private/transfer");
        var r=evaluate(worker);var task=r.at("/publicSummary/taskCoverage/0");
        assertEquals(0,r.at("/publicSummary/paperCoverage").size());assertEquals(1,r.at("/publicSummary/sourceTaskReferences").size());
        assertEquals("c".repeat(64),task.path("capturedResultSha256").asText());assertEquals("e".repeat(64),task.path("selectionSha256").asText());
        assertEquals("AVAILABLE",task.at("/acquisitionProjection/status").asText());
        assertEquals("PAPER_DOWNLOAD_TIMEOUT",task.at("/acquisitionRecovery/0/reasonCode").asText());
        assertFalse(task.toString().contains("D:/private"));assertFalse(task.has("bundleSha256"));assertFalse(task.has("canContinueProcessing"));
        assertEquals("NOT_EVALUATED",r.at("/evaluationResult/status").asText());
    }
    @Test void unresolvedPmcidIsRetainedOnlyAsTaskAcquisitionNotFabricatedPaper() {
        var worker=discovery();transfer(worker).putNull("pmcid");var r=evaluate(worker);
        assertTrue(r.at("/publicSummary/taskCoverage/0/acquisitionRecovery/0/pmcid").isNull());
        assertTrue(r.at("/publicSummary/paperCoverage").isEmpty());
        assertFalse(r.at("/publicSummary/taskCoverage/0").has("bundleSha256"));
    }
    @Test void invalidTransferRowOrTaskBindingIsWithheldRatherThanLeakingOrJoiningAnotherPaper() {
        for(String variant:List.of("BAD_PMCID","UNSAFE_REASON","BAD_TARGET","BAD_RESULT_PIN")) {
            var worker=discovery();var row=transfer(worker);
            if("BAD_PMCID".equals(variant))row.put("pmcid","D:/private/source");
            if("UNSAFE_REASON".equals(variant))row.put("reasonCode","timeout D:/private/source");
            var r=evaluate(worker,doc->{
                if("BAD_TARGET".equals(variant))doc.withObject("/result/target").put("geneSymbol","OTHER");
                if("BAD_RESULT_PIN".equals(variant))doc.put("resultSha256","D:/private/result");
            });
            var task=r.at("/publicSummary/taskCoverage/0");
            assertFalse(task.toString().contains("D:/private"),variant);
            if(variant.startsWith("BAD_TARGET") || variant.equals("BAD_RESULT_PIN"))assertEquals("WITHHELD",task.at("/projection/status").asText(),variant);
            else {assertEquals("WITHHELD",task.at("/acquisitionProjection/status").asText(),variant);assertTrue(task.path("acquisitionRecovery").isEmpty());}
            assertTrue(r.at("/publicSummary/paperCoverage").isEmpty());
        }
    }
    @Test void wrongCheckpointPmcidBundleTargetOrStageTypeDoesNotChangeScientificResult() {
        for(String variant:List.of("PMCID","BUNDLE","TARGET","COUNT","DUPLICATE","NO_ARTIFACT")) {
            var paper=paper();var state=paper.withObject("/processingCheckpoint");
            switch(variant) {
                case "PMCID" -> state.put("pmcid","PMC456");
                case "BUNDLE" -> state.put("bundleSha256","f".repeat(64));
                case "TARGET" -> state.withObject("/target").putArray("variantAliases").add("c.99C>G");
                case "COUNT" -> state.withArray("stages").get(0).withObject("").put("modelCalls",true);
                case "DUPLICATE" -> state.withArray("stages").add(state.withArray("stages").get(0).deepCopy());
                case "NO_ARTIFACT" -> state.withArray("stages").get(0).withObject("").remove("artifactSha256");
                default -> throw new AssertionError(variant);
            }
            var r=evaluate(paper);assertEquals("WITHHELD",r.at("/publicSummary/paperCoverage/0/processingCheckpoint/projection/status").asText(),variant);
            assertEquals("MET",r.at("/evaluationResult/status").asText(),variant);
        }
    }
    @Test void fullStageCountsArePreservedWhenDisplayIsTruncated() {
        var paper=paper();var state=paper.withObject("/processingCheckpoint");var stages=state.putArray("stages");
        for(int i=0;i<513;i++)stages.addObject().put("chunkIndex",i/2).put("stage",i%2==0?"EXTRACTION":"REVIEW")
                .put("status",i%2==0?"NOT_EXTRACTED":"NOT_REVIEWED").put("reasonCode","MODEL_CACHE_MISSING").put("modelCalls",0);
        var checkpoint=evaluate(paper).at("/publicSummary/paperCoverage/0/processingCheckpoint");
        assertEquals("AVAILABLE",checkpoint.at("/projection/status").asText());assertEquals(512,checkpoint.path("stages").size());
        assertEquals(513,checkpoint.at("/projection/stageTotal").asInt());assertEquals(513,checkpoint.at("/projection/stagePending").asInt());
        assertEquals(1,checkpoint.at("/projection/stageOmitted").asInt());
        assertTrue(checkpoint.toString().length()<LiteratureStageDiagnostics.MAX_BYTES);
    }
    @Test void producerAndDisplayBoundsWithholdMalformedClaimsWithoutRewritingOriginals() {
        for(String variant:List.of("STAGE_LIMIT","BAD_CHUNK_COUNT","UNSAFE_ALIAS","WRONG_STAGE","TRANSFER_LIMIT")) {
            var paper=paper();var state=paper.withObject("/processingCheckpoint");
            if("STAGE_LIMIT".equals(variant))for(int i=0;i<8192;i++)state.withArray("stages").addObject();
            if("BAD_CHUNK_COUNT".equals(variant))paper.put("chunkCount",true);
            if("UNSAFE_ALIAS".equals(variant)) {
                paper.withObject("/target").putArray("variantAliases").add("D:/private/alias");
                state.withObject("/target").putArray("variantAliases").add("D:/private/alias");
            }
            if("WRONG_STAGE".equals(variant))state.withArray("stages").get(0).withObject("").put("status","NOT_REVIEWED");
            if("TRANSFER_LIMIT".equals(variant)) {
                var raw=paper.putArray("acquisitionRecovery");for(int i=0;i<201;i++)raw.addObject();
            }
            var module=fixture.batch(paper,doc->boundDocument(doc,false));
            var result=module.evaluateContext(fixture.context(fixture.paper()));
            var path="TRANSFER_LIMIT".equals(variant)?"/publicSummary/taskCoverage/0/acquisitionProjection/status":"/publicSummary/paperCoverage/0/processingCheckpoint/projection/status";
            assertEquals("WITHHELD",result.at(path).asText(),variant);
            assertEquals(paper,module.originals().at("/documents/0/result"));
            assertFalse(result.at("/publicSummary/paperCoverage/0/processingCheckpoint").toString().contains("D:/private"));
        }
    }

    @Test void aggregateOperationalCapPreservesFullCountsPinsAndPrivateOriginals() throws Exception {
        var paper=paper().put("chunkCount",4096);var state=paper.withObject("/processingCheckpoint");
        var stages=state.putArray("stages");
        // Maximal, explicit synthetic diagnostic vectors exercise aggregate size;
        // they are not a claim of scientific review or a real source workload.
        for(int i=0;i<512;i++)stages.addObject().put("chunkIndex",i).put("stage","REVIEW")
                .put("status","TEMPORARY_FAILURE").put("reasonCode","PM3_"+"A".repeat(188))
                .put("artifactSha256","d".repeat(64)).put("modelCalls",0);
        var plan=paper.putObject("readingPlan");
        for(String key:List.of("orderedRelevantChunks","unselectedChunks","completedReviewChunks",
                "pendingRelevantChunks","pendingAllChunks","blockedReviewChunks")) {
            var indices=plan.putArray(key);for(int i=0;i<4096;i++)indices.add(i);
        }
        for(String key:List.of("completedReviewChunks","pendingReviewChunks")) {
            var indices=paper.putArray(key);for(int i=0;i<4096;i++)indices.add(i);
        }
        var doc=json.createObjectNode().put("taskId","a".repeat(64));doc.set("request",fixture.f.request());doc.set("result",paper);
        boundDocument(doc,false);
        var uncapped=json.createObjectNode().put("pmcid","PMC123").put("status","PARTIAL");
        LiteratureStageDiagnostics.paper(json,uncapped,doc,paper,"f".repeat(64));
        LiteratureStageDiagnostics.progress(json,uncapped,paper,paper);
        assertEquals("AVAILABLE",uncapped.at("/processingCheckpoint/projection/status").asText());
        assertTrue(json.writeValueAsBytes(uncapped.path("processingCheckpoint")).length<LiteratureStageDiagnostics.MAX_BYTES);
        assertTrue(json.writeValueAsBytes(uncapped).length>LiteratureStageDiagnostics.MAX_BYTES);
        var module=fixture.batch(paper,d->boundDocument(d,false));var result=module.evaluateContext(fixture.context(fixture.paper()));
        var progress=(ObjectNode)result.at("/publicSummary/paperCoverage/0");var operational=progress.deepCopy();
        operational.remove(List.of("materialAssessments","unreviewedExtraction"));
        assertTrue(json.writeValueAsBytes(operational).length<=LiteratureStageDiagnostics.MAX_BYTES);
        assertEquals("WITHHELD",progress.at("/projection/status").asText());
        assertEquals("PM3_STAGE_PUBLIC_SIZE_LIMIT",progress.at("/projection/reasonCode").asText());
        assertEquals("c".repeat(64),progress.path("capturedResultSha256").asText());
        assertEquals("b".repeat(64),progress.path("bundleSha256").asText());
        assertEquals(512,progress.at("/processingCheckpoint/projection/stageTotal").asInt());
        assertEquals(512,progress.at("/processingCheckpoint/projection/stagePending").asInt());
        assertEquals(512,progress.at("/processingCheckpoint/projection/stageOmitted").asInt());
        assertFalse(progress.path("processingCheckpoint").has("stages"));
        assertFalse(progress.has("completedReviewChunks"));assertFalse(progress.has("pendingReviewChunks"));
        assertEquals(8,progress.at("/projection/withheldArrays").size());
        for(var array:progress.at("/projection/withheldArrays")) {
            assertEquals(4096,array.path("sourceItems").asInt());assertEquals(4096,array.path("omitted").asInt());
            assertEquals(0,array.path("displayed").asInt(-1));
        }
        assertEquals(paper,module.originals().at("/documents/0/result"));
        assertEquals("MET",result.at("/evaluationResult/status").asText());
    }

    @Test void absentNewFieldsAndMissingLegacyPinsAreExplicitWithoutScientificRegression() {
        var p=fixture.paper();var module=fixture.batch(p);var r=module.evaluateContext(fixture.context(p));
        assertEquals("NOT_RECORDED",r.at("/publicSummary/paperCoverage/0/processingCheckpoint/projection/status").asText());
        assertEquals("WITHHELD",r.at("/publicSummary/taskCoverage/0/projection/status").asText());
        assertTrue(r.at("/publicSummary/sourceTaskReferences").isEmpty());assertEquals("MET",r.at("/evaluationResult/status").asText());
        assertEquals(p,module.originals().at("/documents/0/result"));
    }
    @Test void ownOrNestedAccountingNeverBecomesHistoricalAuthorizationAndProgressExtrasAreClosed() {
        for(boolean nested:List.of(false,true)) {
            var p=paper();p.putObject("readingPlan").put("scope","HEURISTIC_RELEVANT_TARGET_UNITS_NOT_FULL_PAPER")
                    .put("privatePath","D:/private/plan").putArray("pendingRelevantChunks").add(1);
            var marker=nested?p.putArray("papers").addObject():p;
            marker.putObject("readingLoop").put("failedAttemptAccounting","CHECK_SHARED_LEDGER").put("artifactPath","D:/private/loop");
            if(nested)p.putObject("readingLoop").put("state","INCOMPLETE").putObject("feedback").put("canContinueWithinTask",true).put("quote","private original");
            var r=evaluate(p);var task=r.at("/publicSummary/taskCoverage/0");var progress=r.at("/publicSummary/paperCoverage/0");
            for(var row:List.of(task,progress)) {
                assertTrue(row.path("reconciliationRequired").asBoolean());
                assertEquals("PM3_MODEL_BUDGET_RECONCILIATION_REQUIRED",row.path("processingContinuationBlockedReason").asText());
                assertFalse(row.toString().contains("D:/private"));assertFalse(row.has("canContinueProcessing"));
            }
            assertFalse(progress.path("readingLoop").toString().contains("private original"));
            assertFalse(progress.at("/readingLoop/feedback").has("canContinueWithinTask"));
        }
    }
}
