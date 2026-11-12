package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Presentation over synthetic, source-bound RNA controls. */
class T07FullRnaCompletedExclusionTest {
    @TempDir Path root;
    private record Data(FullRnaInitiationFixture f,ReviewedRuleBundle.FullRnaPrepared prepared,ObjectNode raw){ }
    private Data actual() throws Exception {
        var f=new FullRnaInitiationFixture(root,1);f.functionalAlternative();
        ((ObjectNode)f.documents.get("init-transcripts").at("/findings/1")).put("differentStart",false);f.republish("init-transcripts");
        var d=evaluate(f);var raw=d.raw;
        assertEquals("NOT_APPLICABLE",raw.at("/resolvedInput/fullRnaAssessment/status").asText(),raw::toPrettyString);
        assertEquals("NOT_EVALUATED",raw.at("/evaluation/evaluationResult/status").asText());assertEquals("NOT_APPLICABLE",raw.at("/evaluation/criterionApplicability/status").asText());
        assertEquals("COMPLETED",raw.at("/evaluation/deterministicTrace/executionStatus").asText());return d;
    }
    private Data evaluate(FullRnaInitiationFixture f) throws Exception {
        var document=new Pvs1NmdBusinessFixture(f.rna.f.mapper,false).document();var entry=document.withObject("materials").putArray("pvs1FullRnaOriginals").addObject().put("entryId","SYNTHETIC_REAL_RESCUE");
        entry.set("allele",f.rna.f.context.at("/variant/evidenceQueryAllele"));entry.put("transcript",f.rna.f.model.path("transcript").asText());f.rna.source();entry.set("binding",f.rna.data.binding());
        byte[] bytes=f.rna.f.mapper.writeValueAsBytes(document);Path file=f.rna.data.originals().resolve("bundle.json");Files.write(file,bytes);
        var bundle=ReviewedRuleBundle.loadPvs1(f.rna.f.mapper,file,CodingTranscriptMaterialSupply.sha(bytes),f.rna.f.adapter.canonicalSha256(document),new Pvs1TranscriptMaterialSupply(f.rna.f.mapper,f.rna.f.supply));
        var prepared=bundle.prepareFullRna(f.rna.f.context,f.rna.f.candidate);assertTrue(prepared.ready(),prepared.snapshot()::toPrettyString);
        var raw=new TruncatingNmdPvs1Runtime(f.rna.f.mapper,new TruncatingNmdPvs1RuntimeTest().resourceProfile(),f.rna.f.model,true)
                .evaluateFullRna(f.rna.f.context,f.rna.f.candidate,f.data.policy(),f.data.selection(),Objects.requireNonNull(prepared.source()));
        return new Data(f,prepared,raw);
    }
    private ObjectNode apply(Data d,ObjectNode raw) {
        var view=d.f.rna.f.mapper.createObjectNode().put("criterionId",Pvs1SelectedBatch.CRITERION).put("status",raw.at("/evaluation/evaluationResult/status").asText())
                .put("appliedStrength",raw.at("/evaluation/appliedStrength").asText()).put("reasonCode",raw.at("/evaluation/evaluationResult/reasonCode").asText());
        var ledger=FullRnaCurrentUse.apply(d.f.rna.f.mapper,d.prepared,"a".repeat(64),"one","hypothesis",Map.of("PVS1",raw),Map.of("pvs1","/modules/pvs1/results/0"));
        FullRnaCurrentUse.applyView(view,FullRnaCurrentUse.claim(ledger,"PVS1"));return view;
    }
    @Test void actualQualifiedSameStartUnaffectedFunctionIsCompletedNotApplicableAndAtomicDataIsUnchanged() throws Exception {
        var d=actual();var before=d.raw.path("evaluation").deepCopy();var view=apply(d,d.raw);
        assertEquals("NOT_APPLICABLE",view.path("status").asText(),view::toPrettyString);assertEquals("NONE",view.path("appliedStrength").asText());assertEquals(before,d.raw.path("evaluation"));
        assertTrue(d.raw.at("/fullRnaCurrentUse/claim/completedExclusion").asBoolean());
        String retained=System.getProperty("varvis.t07.completed.output");if(retained!=null) {
            Path target=Files.createDirectories(Path.of(retained));Files.write(target.resolve("source-original.json"),Objects.requireNonNull(d.prepared.source()).originalBytes());
            Files.write(target.resolve("provider-result.json"),d.f.rna.f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(d.raw));
            Files.write(target.resolve("public-view.json"),d.f.rna.f.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(view));
        }
        assertEquals("1.1.0",d.raw.at("/fullRnaCurrentUse/methodVersion").asText());
        var initiation=d.raw.at("/fullRnaDecisionDetails/initiation");assertEquals("NOT_APPLICABLE",initiation.path("status").asText());
        assertEquals("REMOVED_OR_REPLACED",initiation.at("/nativeStart/status").asText());assertTrue(initiation.at("/nativeStart/alteredStartRna1").isNull());
        assertEquals("PVS1_RNA_INITIATION_FUNCTIONAL_ALTERNATIVE_CONFIRMED",initiation.path("reasonCode").asText());
        assertTrue(initiation.path("sourceDocumentsQualified").asBoolean());
        assertEquals(4,initiation.path("sourceChecks").size());assertEquals(1,initiation.path("sourceQuotes").size());
        assertEquals(java.util.Set.of("init-transcripts","init-primary"),sourceIds(initiation));
        assertEquals(1,initiation.path("transcriptFindings").size());
        assertEquals("ENST000002.1",initiation.at("/transcriptFindings/0/transcript").asText());
        assertEquals(primaryText(d),initiation.at("/sourceQuotes/0/text").asText());assertFalse(initiation.path("sourceSpecifiedStart").isObject(),"Early rescue never chose or proved a start");assertDoesNotThrow(()->FullRnaCurrentUse.publicProof(d.f.rna.f.mapper,d.raw));
    }
    @ParameterizedTest @ValueSource(strings={"different-product","unverified-quote","ambiguous-role"})
    void byteMatchingSourceCannotSupplyDisplayFactsBeforeReviewAdmission(String fault) throws Exception {
        var f=new FullRnaInitiationFixture(root,1);
        var review=Objects.requireNonNull(f.documents.get("init-restart")).put("functionFinding","FUNCTION_PRESERVED_IN_ASSESSED_SCOPE");
        String reason;
        if ("different-product".equals(fault)) {
            review.withObject("scope").put("alteredRnaSha256","0".repeat(64));
            reason="PVS1_RNA_INITIATION_SOURCE_PRODUCT_SCOPE_MISMATCH";
        } else if ("unverified-quote".equals(fault)) {
            ((ObjectNode)review.at("/review/sourceQuotes/0")).put("text","Not present at this original location.");
            reason="PVS1_RNA_INITIATION_SOURCE_LOCATION_INVALID";
        } else {
            f.documents.put("second-restart-review",review.deepCopy());
            f.republish("second-restart-review");
            reason="PVS1_RNA_INITIATION_SOURCE_ROLE_AMBIGUOUS";
        }
        f.republish("init-restart");
        var d=evaluate(f);var view=apply(d,d.raw);
        assertEquals("NOT_EVALUATED",view.path("status").asText());
        assertEquals(reason,view.path("reasonCode").asText());
        var details=d.raw.at("/fullRnaDecisionDetails/initiation");
        assertFalse(details.path("sourceDocumentsQualified").asBoolean(true));
        assertTrue(java.util.stream.StreamSupport.stream(details.path("sourceChecks").spliterator(),false)
                .anyMatch(row->"init-restart".equals(row.path("sourceId").asText())&&"RAW_SHA_MATCH".equals(row.path("status").asText())));
        assertFalse(details.has("sourceDeclaredStartReview"));
        assertFalse(details.has("functionalReviewConditions"));
        assertTrue(details.path("sourceQuotes").isEmpty());
        assertDoesNotThrow(()->FullRnaCurrentUse.publicProof(d.f.rna.f.mapper,d.raw));
    }
    @ParameterizedTest @ValueSource(strings={"unaffected","affected","parent-conflict"})
    void incompleteRestartExperimentDoesNotMaskIndependentTranscriptReview(String alternative) throws Exception {
        var f=new FullRnaInitiationFixture(root,1);f.functionalAlternative();
        if ("affected".equals(alternative)) {
            ((ObjectNode)f.documents.get("init-transcripts").at("/findings/1")).put("targetUnaffected",false);
            f.republish("init-transcripts");
        }
        f.documents.get("init-restart").put("functionFinding","FUNCTION_PRESERVED_IN_ASSESSED_SCOPE");
        f.republish("init-restart");
        if ("parent-conflict".equals(alternative)) {
            f.rna.original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue","EXCLUDED_IN_REVIEWED_SCOPE");
        }

        var d=evaluate(f);var view=apply(d,d.raw);
        boolean rescued="unaffected".equals(alternative);
        assertEquals(rescued?"NOT_APPLICABLE":"NOT_EVALUATED",view.path("status").asText(),view::toPrettyString);
        assertEquals("NONE",view.path("appliedStrength").asText());
        assertEquals("PVS1_RNA_INITIATION_"+switch(alternative) {
            case "unaffected" -> "FUNCTIONAL_ALTERNATIVE_CONFIRMED";
            case "affected" -> "FUNCTIONAL_PRODUCT_REVIEW_INCOMPLETE";
            default -> "FUNCTIONAL_RESCUE_FACTS_CONFLICT";
        },view.path("reasonCode").asText());
        var details=d.raw.at("/fullRnaDecisionDetails/initiation");
        assertFalse(details.has("sourceSpecifiedStart"));
        assertFalse(sourceIds(details).contains("init-restart"));
        assertEquals("affected".equals(alternative),details.has("functionalReviewConditions"));
        if (rescued) assertEquals(java.util.Set.of("init-transcripts","init-primary"),sourceIds(details));
        assertDoesNotThrow(()->FullRnaCurrentUse.publicProof(d.f.rna.f.mapper,d.raw));
    }
    @Test void conflictingObservedProductRetainsTheQualifiedCitationWithoutAdoptingEvidence() throws Exception {
        var f=new FullRnaInitiationFixture(root,1);
        f.documents.get("init-restart").put("startStatus","OBSERVED").put("functionFinding","FUNCTION_PRESERVED_IN_ASSESSED_SCOPE")
                .put("physiologicalRelevance","ASSESSED").put("functionalQualification","REVIEWED_FUNCTIONAL_EXPERIMENT_FOR_FULL_RNA_PRODUCT");
        f.republish("init-restart");
        f.rna.original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue","EXCLUDED_IN_REVIEWED_SCOPE");

        var d=evaluate(f);var view=apply(d,d.raw);
        assertEquals("PVS1_RNA_INITIATION_FUNCTIONAL_RESCUE_FACTS_CONFLICT",view.path("reasonCode").asText());
        assertEquals("NOT_EVALUATED",view.path("status").asText());
        assertEquals("NONE",view.path("appliedStrength").asText());
        assertFalse(d.raw.at("/fullRnaCurrentUse/claim/completedExclusion").asBoolean());
        var details=d.raw.at("/fullRnaDecisionDetails/initiation");
        assertEquals(9,details.at("/sourceSpecifiedStart/alteredStartRna1").asInt());
        assertEquals(java.util.Set.of("init-restart","init-primary"),sourceIds(details));
        assertEquals(primaryText(d),details.at("/sourceQuotes/0/text").asText());
        assertDoesNotThrow(()->FullRnaCurrentUse.publicProof(d.f.rna.f.mapper,d.raw));
    }
    @ParameterizedTest @ValueSource(strings={"OBSERVED","SOURCE_REVIEWED_PUTATIVE","UNKNOWN","NOT_ASSESSED","INVALID"})
    void incompleteFunctionalProductReviewProjectsItsOwnMissingConditions(String startStatus) throws Exception {
        var f=new FullRnaInitiationFixture(root,1);
        var review=Objects.requireNonNull(f.documents.get("init-restart")).put("functionFinding","FUNCTION_PRESERVED_IN_ASSESSED_SCOPE")
                .put("startStatus",startStatus);
        boolean observed="OBSERVED".equals(startStatus);
        if (!observed) review.put("physiologicalRelevance","ASSESSED")
                .put("functionalQualification","REVIEWED_FUNCTIONAL_EXPERIMENT_FOR_FULL_RNA_PRODUCT");
        f.republish("init-restart");
        var d=evaluate(f);var view=apply(d,d.raw);
        assertEquals("NOT_EVALUATED",view.path("status").asText());
        assertEquals("NONE",view.path("appliedStrength").asText());
        assertEquals("PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_REVIEW_INCOMPLETE",view.path("reasonCode").asText());
        var details=d.raw.at("/fullRnaDecisionDetails/initiation");
        assertTrue(details.path("sourceDocumentsQualified").asBoolean());
        assertEquals("INVALID".equals(startStatus)?"":startStatus,details.path("availableStartStatus").asText());
        assertFalse(details.has("sourceSpecifiedStart"));
        var checks=details.path("functionalReviewConditions");
        assertEquals(observed,checks.path("observedStartReported").booleanValue());
        assertEquals(!observed,checks.path("physiologicalRelevanceReviewed").booleanValue());
        assertEquals(!observed,checks.path("sameRnaProductExperimentReviewed").booleanValue());
        assertEquals(3,checks.size());
        assertFalse(details.has("sourceDeclaredStartReview"));
        assertTrue(details.path("sourceQuotes").isEmpty());
        assertDoesNotThrow(()->FullRnaCurrentUse.publicProof(d.f.rna.f.mapper,d.raw));
    }
    @ParameterizedTest @ValueSource(strings={"nested-quote","nested-scope","review-required","restart-witness"})
    void unappliedFactsAreNotExposedAsReviewedEvidence(String fault) throws Exception {
        var f=new FullRnaInitiationFixture(root,1);
        var finding=(ObjectNode)Objects.requireNonNull(f.documents.get("init-transcripts")).at("/findings/1");
        if (fault.startsWith("nested-")) finding.put("targetUnaffected",true);
        String expected;
        switch (fault) {
            case "nested-quote" -> {
                ((ObjectNode)finding.at("/sourceQuotes/0")).put("text","This does not match the exact original location.");
                f.republish("init-transcripts");
                expected="FUNCTION_SOURCE_LOCATION_INVALID";
            }
            case "nested-scope" -> {
                finding.put("qualification","NOT_REVIEWED");
                f.republish("init-transcripts");
                expected="FUNCTION_REVIEW_SCOPE_UNRESOLVED";
            }
            case "review-required" -> {
                f.rna.original.withObject("pvs1Assessment").remove("initiationReview");
                expected="SOURCE_REVIEW_REQUIRED";
            }
            default -> {
                Objects.requireNonNull(f.documents.get("init-restart")).put("proteinThroughFirstStop","MFW*");
                f.republish("init-restart");
                expected="SOURCE_TRANSLATION_WITNESS_MISMATCH";
            }
        }
        var d=evaluate(f);
        var view=apply(d,d.raw);
        assertEquals("NOT_EVALUATED",view.path("status").asText(),d.raw::toPrettyString);
        assertEquals("PVS1_RNA_INITIATION_"+expected,view.path("reasonCode").asText());
        var detail=d.raw.at("/fullRnaDecisionDetails/initiation");
        assertFalse(detail.has("sourceDeclaredStartReview"));
        assertFalse(sourceIds(detail).contains("init-restart"));
        assertEquals(4,detail.at("/sourceDocumentSet/requiredSourceIds").size());
        if ("restart-witness".equals(fault)) {
            assertEquals(1,detail.path("transcriptFindings").size());
            assertEquals(java.util.Set.of("init-transcripts","init-primary"),sourceIds(detail));
        } else {
            assertTrue(detail.path("transcriptFindings").isEmpty());
            assertTrue(detail.path("sourceQuotes").isEmpty());
            assertTrue(sourceIds(detail).isEmpty());
        }
        assertDoesNotThrow(()->FullRnaCurrentUse.publicProof(d.f.rna.f.mapper,d.raw));
    }
    @Test void separatelyLocatedAcceptedTranscriptAndReferenceQuotesArePreserved() throws Exception {
        var f=new FullRnaInitiationFixture(root,1);
        f.pathogenicReference();
        var transcript=(ObjectNode)Objects.requireNonNull(f.documents.get("init-transcripts")).at("/findings/1");
        var reference=(ObjectNode)Objects.requireNonNull(f.documents.get("init-upstream")).at("/records/0");
        String transcriptText=locate(transcript,"complete transcript review"),referenceText=locate(reference,"independent pathogenic reference");
        f.republish("init-transcripts");
        f.republish("init-upstream");
        var d=evaluate(f);
        assertEquals("MET",apply(d,d.raw).path("status").asText(),d.raw::toPrettyString);
        var detail=d.raw.at("/fullRnaDecisionDetails/initiation");
        assertEquals("QUALIFYING",detail.at("/referenceReviews/0/disposition").asText());
        var excerpts=new java.util.HashSet<String>();
        detail.path("sourceQuotes").forEach(row->excerpts.add(row.path("text").asText()));
        assertEquals(java.util.Set.of(primaryText(d),transcriptText,referenceText),excerpts);
        assertEquals(java.util.Set.of("init-primary","init-transcripts","init-restart","init-upstream"),sourceIds(detail));
    }
    private static String locate(ObjectNode row,String text) {
        int start=FullRnaInitiationFixture.PRIMARY.indexOf(text);
        assertTrue(start>=0);
        ((ObjectNode)row.at("/sourceQuotes/0")).put("start",start).put("end",start+text.length()).put("text",text);
        return text;
    }
    private static java.util.Set<String> sourceIds(com.fasterxml.jackson.databind.JsonNode detail) {
        var ids=new java.util.HashSet<String>();
        detail.path("reviewedSourceIds").forEach(id->ids.add(id.asText()));
        return ids;
    }
    @Test void aRehashedCompletionMarkerCannotHideChangedAtomicApplicability() throws Exception {
        var d=actual();apply(d,d.raw);var mapper=d.f.rna.f.mapper;
        d.raw.withObject("/evaluation/criterionApplicability").put("status","UNKNOWN");
        var proof=d.raw.withObject("fullRnaCurrentUse");proof.withObject("claim").put("evaluationSha256",d.f.rna.f.adapter.canonicalSha256(d.raw.path("evaluation")));
        proof.remove("proofSha256");proof.put("proofSha256",d.f.rna.f.adapter.canonicalSha256(proof));
        assertThrows(IllegalArgumentException.class,()->FullRnaCurrentUse.publicProof(mapper,d.raw));
    }
    @Test void dispatcherAndServiceCompletionRequireTheSamePrivateRawLedgerAndProjection() throws Exception {
        var d=actual();var mapper=d.f.rna.f.mapper;
        var view=mapper.createObjectNode().put("criterionId",Pvs1SelectedBatch.CRITERION).put("status","NOT_APPLICABLE").put("appliedStrength","NONE")
                .put("reasonCode",d.raw.at("/evaluation/evaluationResult/reasonCode").asText());
        var batch=mapper.createObjectNode().put("sourceSnapshotSha256","a".repeat(64));batch.putObject("identity").putObject("fullRnaExecution").put("pvs1Version","1.10.1");
        batch.putObject("modules").putObject("pvs1").putArray("results").add(d.raw);
        var ledger=FullRnaCurrentUse.apply(mapper,d.prepared,"a".repeat(64),"one","hypothesis",Map.of("PVS1",d.raw),Map.of("pvs1","/modules/pvs1/results/0"));
        var row=batch.putArray("results").addObject().put("candidateId","one").put("hypothesisId","hypothesis");row.set("fullRnaApplication",ledger);
        var details=view.putObject("details").put("modulePointer","/modules/pvs1/results/0");details.set("originalResult",d.raw.path("evaluation"));details.set("fullRnaUse",d.raw.path("fullRnaCurrentUse"));
        row.putObject("localBatch").putArray("criteria").add(view);details.set("pvs1Facts",FullRnaCurrentUse.project(mapper,batch,row,view));
        var candidate=mapper.createObjectNode().put("candidateId","one");assertTrue(LocalEvidenceBatchRuntime.completedFullRnaExclusion(mapper,batch,candidate,row,view));
        var changed=view.deepCopy();changed.withObject("/details/originalResult/criterionApplicability").put("status","UNKNOWN");
        assertFalse(LocalEvidenceBatchRuntime.completedFullRnaExclusion(mapper,batch,candidate,row,changed));
    }
    private static String primaryText(Data d){return d.f.primaryText;}
    @ParameterizedTest @ValueSource(strings={"unknown-applicability","failed-trace","unexecuted-trace","unknown-assessment","missing-assessment","wrong-effect","wrong-original","wrong-method","nonzero-strength"})
    void aLabelOrReasonWithoutAllActualCompletionBindingsIsNotPromoted(String fault) throws Exception {
        var d=actual();var raw=d.raw.deepCopy();
        switch(fault) {
            case "unknown-applicability" -> raw.withObject("/evaluation/criterionApplicability").put("status","UNKNOWN");
            case "failed-trace" -> raw.withObject("/evaluation/deterministicTrace").put("executionStatus","FAILED");
            case "unexecuted-trace" -> raw.withObject("/evaluation/deterministicTrace").remove("executionStatus");
            case "unknown-assessment" -> raw.withObject("/resolvedInput/fullRnaAssessment").put("status","NOT_EVALUATED");
            case "missing-assessment" -> raw.withObject("resolvedInput").remove("fullRnaAssessment");
            case "wrong-effect" -> raw.withObject("/resolvedInput/fullRnaAssessment").put("mechanicalEffectSha256","b".repeat(64));
            case "wrong-original" -> raw.withObject("/resolvedInput/fullRnaAssessment").put("sourceOriginalSha256","b".repeat(64));
            case "wrong-method" -> raw.withObject("/evaluation/deterministicTrace").put("evaluatorId","other-method");
            case "nonzero-strength" -> raw.withObject("evaluation").put("appliedStrength","STRONG");
            default -> throw new AssertionError(fault);
        }
        if(fault.equals("wrong-method")||fault.equals("missing-assessment")) assertThrows(IllegalArgumentException.class,()->apply(d,raw));
        else assertNotEquals("NOT_APPLICABLE",apply(d,raw).path("status").asText());
    }
}
