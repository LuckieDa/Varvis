package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Independent expectations for source-specified RNA starts. All scientific declarations are SYNTHETIC. */
class Pvs1RnaInitiationTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(ints={1,-1})
    void sourceSelectedStartIsNotReplacedByTheFirstAtgAndBoundedNegativeGivesOnlySupporting(int strand) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand);assertEquals(4,FullRnaInitiationFixture.PRODUCT.indexOf("ATG")+1);
        var out=f.run();assertEquals("MET",out.at("/fullRnaAssessment/status").asText(),out::toPrettyString);
        assertEquals("SUPPORTING",out.at("/fullRnaAssessment/adoptedStrength").asText());
        assertEquals(9,out.at("/fullRnaInitiationAssessment/sourceSpecifiedStart/alteredStartRna1").asInt());
        assertEquals("MFE*",out.at("/fullRnaInitiationAssessment/sourceSpecifiedStart/proteinThroughFirstStop").asText());
        assertFalse(out.has("ptcStart"));assertFalse(out.has("nmdPrediction"));
        assertTrue(out.at("/fullRnaInitiationAssessment/putativeRestartDoesNotProveTranslation").asBoolean());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void independentReferenceUsesExistingDecisionAndAllCapsRemain(int strand) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand);f.pathogenicReference();var out=f.run();
        assertEquals("MODERATE",out.at("/fullRnaAssessment/adoptedStrength").asText(),out::toPrettyString);
        f.data.policy().put("appliedStrength","SUPPORTING");f.rebind();out=f.run();assertEquals("SUPPORTING",out.at("/fullRnaAssessment/adoptedStrength").asText());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void sourceQualifiedFunctionalAlternativeStopsThisPvs1RouteWithoutRequiringRestart(int strand) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand);f.functionalAlternative();
        f.documents.get("init-restart").put("startStatus","UNKNOWN");f.republish("init-restart");var out=f.run();
        assertEquals("NOT_APPLICABLE",out.at("/fullRnaAssessment/status").asText(),out::toPrettyString);
        assertEquals("PVS1_RNA_INITIATION_FUNCTIONAL_ALTERNATIVE_CONFIRMED",out.at("/fullRnaAssessment/reasonCode").asText());
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
    }
    @Test void observedFunctionalProductIsAnIndependentSourceRouteNotAnAtgInference() throws Exception {
        var f=new FullRnaInitiationFixture(root,1);var d=f.documents.get("init-restart");
        d.put("startStatus","OBSERVED").put("functionFinding","FUNCTION_PRESERVED_IN_ASSESSED_SCOPE")
                .put("physiologicalRelevance","ASSESSED").put("functionalQualification","REVIEWED_FUNCTIONAL_EXPERIMENT_FOR_FULL_RNA_PRODUCT");
        f.rna.original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue","ASSESSED_IN_INITIATION_REVIEW");f.republish("init-restart");
        var out=f.run();assertEquals("NOT_APPLICABLE",out.at("/fullRnaAssessment/status").asText(),out::toPrettyString);
        assertEquals("PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_CONFIRMED",out.at("/fullRnaAssessment/reasonCode").asText());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({
            "false,missing", "false,unknown", "false,invalid",
            "true,missing", "true,unknown", "true,invalid"
    })
    void unresolvedParentFunctionalReviewCannotCompleteInitiation(boolean alternative, String disposition) throws Exception {
        var f = new FullRnaInitiationFixture(root, 1);
        if (alternative) f.functionalAlternative();
        var parent = f.rna.original.withObject("pvs1Assessment").withObject("parentReview");
        switch (disposition) {
            case "missing" -> parent.remove("functionalRescue");
            case "unknown" -> parent.put("functionalRescue", "UNRESOLVED");
            default -> parent.putObject("functionalRescue").put("unexpected", true);
        }
        var result = f.run();
        var assessment = result.path("fullRnaAssessment");
        assertEquals("NOT_EVALUATED", assessment.path("status").asText(), result::toPrettyString);
        assertEquals("PVS1_RNA_INITIATION_PARENT_FUNCTIONAL_RESCUE_REVIEW_REQUIRED", assessment.path("reasonCode").asText());
        assertFalse(assessment.has("adoptedStrength"));
    }

    @Test void observedProductCannotCompleteRescueOutsideTheReviewedTranscriptScope() throws Exception {
        var f = new FullRnaInitiationFixture(root, 1);
        var restart = f.documents.get("init-restart");
        restart.put("startStatus", "OBSERVED").put("functionFinding", "FUNCTION_PRESERVED_IN_ASSESSED_SCOPE")
                .put("physiologicalRelevance", "ASSESSED")
                .put("functionalQualification", "REVIEWED_FUNCTIONAL_EXPERIMENT_FOR_FULL_RNA_PRODUCT");
        f.rna.original.withObject("pvs1Assessment").withObject("parentReview")
                .put("functionalRescue", "ASSESSED_IN_INITIATION_REVIEW");
        f.data.policy().withObject("spliceScope").withObject("initiationScope")
                .putArray("assessedTranscripts").add("ENST000002.1");
        f.republish("init-restart");

        var result = f.run();
        var assessment = result.path("fullRnaAssessment");
        assertEquals("NOT_EVALUATED", assessment.path("status").asText(), result::toPrettyString);
        assertEquals("PVS1_RNA_INITIATION_SELECTED_TRANSCRIPT_OUTSIDE_SCOPE", assessment.path("reasonCode").asText());
        var initiation = result.path("fullRnaInitiationAssessment");
        assertEquals(f.rna.f.model.path("transcript"), initiation.at("/transcriptReviewScope/selectedTranscript"));
        assertEquals("ENST000002.1", initiation.at("/transcriptReviewScope/requiredTranscripts/0").asText());
        assertFalse(initiation.has("sourceSpecifiedStart"));
        assertFalse(assessment.has("adoptedStrength"));
    }

    @ParameterizedTest @ValueSource(strings={"init-primary","init-transcripts","init-restart"})
    void aPinnedDocumentOutsideTheActualReviewCannotCompleteFunctionalRescue(String omitted) throws Exception {
        var f = new FullRnaInitiationFixture(root, 1);
        f.functionalAlternative();
        var review = f.rna.original.withObject("pvs1Assessment").withObject("initiationReview");
        var ids = review.withArray("sourceIds");
        for (int index = ids.size() - 1; index >= 0; index--) {
            if (omitted.equals(ids.path(index).asText())) ids.remove(index);
        }
        assertTrue(review.path("sourceDocuments").has(omitted));
        var out = f.run();
        var assessment = out.path("fullRnaAssessment");
        assertEquals("NOT_EVALUATED", assessment.path("status").asText(), out::toPrettyString);
        assertEquals("PVS1_RNA_INITIATION_SOURCE_DOCUMENT_NOT_IN_REVIEW_SCOPE", assessment.path("reasonCode").asText());
        assertEquals(omitted, out.at("/fullRnaInitiationAssessment/sourceDocumentSet/outsideReviewSourceIds/0").asText());
        assertFalse(assessment.has("adoptedStrength"));
    }
    @ParameterizedTest @ValueSource(strings={"scope","raw-sha","quote","product","context","review","incomplete","functional-unknown","wrong-start","witness","upstream-incomplete","circular","conflict","rescue-conflict","dna-scope"})
    void missingConflictingOrOutOfScopeFactsCannotBecomeAnInitiationScore(String failure) throws Exception {
        var f=new FullRnaInitiationFixture(root,1);String expected;
        switch(failure) {
            case "scope" -> {f.data.policy().withObject("spliceScope").withObject("initiationScope").put("maximumStrength","STRONG");f.rebind();expected="PVS1_RNA_INITIATION_POLICY_NOT_REVIEWED";}
            case "raw-sha" -> {f.rna.original.withObject("pvs1Assessment").withObject("initiationReview").withObject("sourceDocuments").withObject("init-restart").put("rawText","{}");expected="PVS1_RNA_INITIATION_SOURCE_ORIGINAL_SHA_MISMATCH";}
            case "quote" -> {((ObjectNode)f.documents.get("init-restart").at("/review/sourceQuotes/0")).put("text","not in original");f.republish("init-restart");expected="PVS1_RNA_INITIATION_SOURCE_LOCATION_INVALID";}
            case "product" -> {f.documents.get("init-restart").withObject("scope").put("alteredRnaSha256","0".repeat(64));f.republish("init-restart");expected="PVS1_RNA_INITIATION_SOURCE_PRODUCT_SCOPE_MISMATCH";}
            case "context" -> {f.documents.get("init-restart").put("contextSha256","0".repeat(64));f.republish("init-restart");expected="PVS1_RNA_INITIATION_SOURCE_PRODUCT_SCOPE_MISMATCH";}
            case "review" -> {f.documents.get("init-restart").withObject("review").put("status","UNREVIEWED");f.republish("init-restart");expected="PVS1_RNA_INITIATION_SOURCE_REVIEW_NOT_QUALIFIED";}
            case "incomplete" -> {f.documents.get("init-transcripts").put("complete",false);f.republish("init-transcripts");expected="PVS1_RNA_INITIATION_TRANSCRIPT_REVIEW_INCOMPLETE";}
            case "functional-unknown" -> {var row=(ObjectNode)f.documents.get("init-transcripts").at("/findings/1");row.put("differentStart",true);row.remove("targetUnaffected");row.remove("functionallyRelevant");f.republish("init-transcripts");expected="PVS1_RNA_INITIATION_TRANSCRIPT_REVIEW_INCOMPLETE";}
            case "wrong-start" -> {f.documents.get("init-restart").put("alteredStartRna1",10);f.republish("init-restart");expected="PVS1_RNA_INITIATION_SOURCE_START_NOT_ATG";}
            case "witness" -> {f.documents.get("init-restart").put("proteinThroughFirstStop","MKP*");f.republish("init-restart");expected="PVS1_RNA_INITIATION_SOURCE_TRANSLATION_WITNESS_MISMATCH";}
            case "upstream-incomplete" -> {f.documents.get("init-upstream").put("complete",false);f.republish("init-upstream");expected="PVS1_RNA_INITIATION_UPSTREAM_REVIEW_INCOMPLETE";}
            case "circular" -> {f.pathogenicReference();((ObjectNode)f.documents.get("init-upstream").at("/records/0")).put("classificationBasis","BASED_ON_THIS_START_LOSS");f.republish("init-upstream");expected="PVS1_RNA_INITIATION_REFERENCE_INDEPENDENCE_UNRESOLVED";}
            case "conflict" -> {f.pathogenicReference();((ObjectNode)f.documents.get("init-upstream").at("/records/0")).put("conflicting",true);f.republish("init-upstream");expected="PVS1_RNA_INITIATION_REFERENCE_CONFLICT";}
            case "rescue-conflict" -> {f.functionalAlternative();f.rna.original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue","EXCLUDED_IN_REVIEWED_SCOPE");expected="PVS1_RNA_INITIATION_FUNCTIONAL_RESCUE_FACTS_CONFLICT";}
            case "dna-scope" -> {f.functionalAlternative();((ObjectNode)f.documents.get("init-transcripts").at("/findings/1")).put("qualification","DNA_START_LOSS_ONLY");f.republish("init-transcripts");expected="PVS1_RNA_INITIATION_FUNCTION_REVIEW_SCOPE_UNRESOLVED";}
            default -> throw new AssertionError(failure);
        }
        var out=f.run();assertEquals(expected,out.at("/fullRnaAssessment/reasonCode").asText(),out::toPrettyString);
        assertNotEquals("MET",out.at("/fullRnaAssessment/status").asText());assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void laterInframeMetCannotReplaceTheNextPotentialInframeMetInTheGenericBaseline(int strand) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand,"CCATGATGGCCATGTTTGAATAAGGGTGA");
        var out=f.run();
        assertEquals("PVS1_RNA_INITIATION_EARLIER_INFRAME_MET_REQUIRES_SEPARATE_RULE",out.at("/fullRnaAssessment/reasonCode").asText(),out::toPrettyString);
        assertEquals(4,out.at("/fullRnaInitiationAssessment/earlierReferenceInframeMetCdsPosition").asInt());
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
    }
    @ParameterizedTest @ValueSource(strings={"missing","extra","wrapper"})
    void originalDocumentSetAndWrapperAreNotASelectablePool(String failure) throws Exception {
        var f=new FullRnaInitiationFixture(root,1);
        var docs=f.rna.original.withObject("pvs1Assessment").withObject("initiationReview").withObject("sourceDocuments");
        if("missing".equals(failure))docs.remove("init-restart");
        if("extra".equals(failure))docs.putObject("unbound-favorable-source").put("rawText","{}");
        if("wrapper".equals(failure))docs.withObject("init-restart").put("status","QUALIFIED");
        var out=f.run();
        assertEquals("PVS1_RNA_INITIATION_"+("wrapper".equals(failure)?"SOURCE_DOCUMENT_FORMAT_INVALID":"SOURCE_DOCUMENT_SET_MISMATCH"),out.at("/fullRnaAssessment/reasonCode").asText(),out::toPrettyString);
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
    }
    @Test void sourceHashFailureRetainsExpectedActualAndSourceIdentityForDetails() throws Exception {
        var f=new FullRnaInitiationFixture(root,1);
        f.rna.original.withObject("pvs1Assessment").withObject("initiationReview").withObject("sourceDocuments").withObject("init-restart").put("rawText","{}");
        var out=f.run();var checks=out.at("/fullRnaInitiationAssessment/sourceChecks");
        assertEquals("init-restart",checks.get(checks.size()-1).path("sourceId").asText(),out::toPrettyString);
        var failed=checks.get(checks.size()-1);
        assertEquals(CodingTranscriptMaterialSupply.sha("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),failed.path("actualRawSha256").asText());
        assertNotEquals(failed.path("expectedRawSha256").asText(),failed.path("actualRawSha256").asText());
        assertEquals("SHA_MISMATCH",failed.path("status").asText());
    }

    @Test void overflowingUnresolvedCountCannotTurnAnUpstreamSearchIntoACompleteNegative() throws Exception {
        var f=new FullRnaInitiationFixture(root,1);
        f.documents.get("init-upstream").put("unresolvedCount",new java.math.BigInteger("18446744073709551616"));f.republish("init-upstream");
        var out=f.run();
        assertEquals("PVS1_RNA_INITIATION_UPSTREAM_REVIEW_INCOMPLETE",out.at("/fullRnaAssessment/reasonCode").asText(),out::toPrettyString);
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
        assertTrue(out.at("/fullRnaInitiationAssessment/blockingCondition/required").isTextual());
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"1,negative","-1,negative","1,pathogenic","-1,pathogenic","1,functional","-1,functional","1,bad-source","-1,bad-source"})
    void realRuntimePreservesInitiationMeaningReasonsAndOriginalBytes(int strand,String scenario) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand);
        if("pathogenic".equals(scenario))f.pathogenicReference();
        if("functional".equals(scenario))f.functionalAlternative();
        if("bad-source".equals(scenario))f.rna.original.withObject("pvs1Assessment").withObject("initiationReview").withObject("sourceDocuments").withObject("init-restart").put("rawText","{}");
        var source=f.rna.source();
        var runtime=new TruncatingNmdPvs1Runtime(f.rna.f.mapper,new TruncatingNmdPvs1RuntimeTest().resourceProfile(),f.rna.f.model,true);
        var actual=runtime.evaluateFullRna(f.rna.f.context,f.rna.f.candidate,f.data.policy(),f.data.selection(),source);
        var result=actual.path("evaluation");
        assertEquals(TruncatingNmdPvs1Runtime.FULL_RNA_EVALUATOR_VERSION,result.at("/deterministicTrace/evaluatorVersion").asText(),actual::toPrettyString);
        assertEquals("functional".equals(scenario)||"bad-source".equals(scenario)?"NOT_EVALUATED":"MET",result.at("/evaluationResult/status").asText(),actual::toPrettyString);
        assertEquals("pathogenic".equals(scenario)?"MODERATE":"negative".equals(scenario)?"SUPPORTING":"NONE",result.path("appliedStrength").asText());
        if("functional".equals(scenario))assertEquals("NOT_APPLICABLE",result.at("/criterionApplicability/status").asText());
        if("bad-source".equals(scenario))assertEquals("PVS1_RNA_INITIATION_SOURCE_ORIGINAL_SHA_MISMATCH",result.at("/evaluationResult/reasonCode").asText());
        if("negative".equals(scenario)||"pathogenic".equals(scenario)) {
            assertTrue(result.at("/deterministicTrace/steps").toString().contains("SOURCE_REVIEWED_RNA_INITIATION_RULE_SUPPORTED"));
            assertFalse(result.at("/deterministicTrace/steps").toString().contains("REVIEWED_NMD_SCOPE_SUPPORTED"));
        }
        assertEquals(source.original(),actual.path("fullRnaSourceOriginal"));
        assertEquals(source.facts().mechanicalEffectSha256(),actual.at("/resolvedInput/fullRnaInitiationAssessment/mechanicalEffectSha256").asText());
        String directory=System.getProperty("varvis.t07.initiation.artifacts","");
        if(!directory.isBlank()) {
            var target=Path.of(directory).resolve(scenario+"-"+strand);java.nio.file.Files.createDirectories(target);
            java.nio.file.Files.write(target.resolve("original.json"),source.originalBytes());
            var sample=f.rna.f.mapper.createObjectNode().put("layer","SYNTHETIC_REAL_PROVIDER_NOT_NORMAL_SELECTED_BUSINESS")
                    .put("sourceOriginalSha256",source.originalSha256()).put("humanClinicalSourceValidation",false);
            sample.set("context",f.rna.f.context);sample.set("model",f.rna.f.model);sample.set("policy",f.data.policy());sample.set("selection",f.data.selection());
            sample.set("runtimeResult",actual);f.rna.f.mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve("provider-result.json").toFile(),sample);
        }
    }

    @ParameterizedTest @ValueSource(ints={1,-1})
    void initiationSubdecisionCannotBypassParentPositionConditionsOrInventAPtc(int strand) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand);
        var condition=f.data.policy().withObject("nmdScope").putArray("positionConditions").addObject().put("conditionId","actual-event")
                .put("coordinate","REFERENCE_EVENT_SPAN").put("minimumInclusive",1).put("basis","SYNTHETIC exact reference event scope");
        condition.putArray("sourceIds").add("synthetic-source");f.rebind();var out=f.run();
        assertEquals("MET",out.at("/fullRnaAssessment/status").asText(),out::toPrettyString);
        condition.put("maximumExclusive",2);f.rebind();f.base.run(f.data,out);
        assertEquals("PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE",out.at("/fullRnaAssessment/reasonCode").asText(),out::toPrettyString);
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
        condition.remove("maximumExclusive");
        for(String coordinate:new String[]{"REFERENCE_PTC_SPAN","ALTERED_PTC_SPAN"}) {
            condition.put("coordinate",coordinate);f.rebind();f.base.run(f.data,out);
            assertEquals("PVS1_POLICY_POSITION_UNRESOLVED",out.at("/fullRnaAssessment/reasonCode").asText(),out::toPrettyString);
            assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());assertFalse(out.has("ptcStart"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void completedFunctionalRescueMustPassTheParentPositionConditions(boolean observedProduct) throws Exception {
        var f = new FullRnaInitiationFixture(root, 1);
        String rescueReason;
        if (observedProduct) {
            var restart = f.documents.get("init-restart");
            restart.put("startStatus", "OBSERVED").put("functionFinding", "FUNCTION_PRESERVED_IN_ASSESSED_SCOPE")
                    .put("physiologicalRelevance", "ASSESSED").put("functionalQualification", "REVIEWED_FUNCTIONAL_EXPERIMENT_FOR_FULL_RNA_PRODUCT");
            f.rna.original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue", "ASSESSED_IN_INITIATION_REVIEW");
            f.republish("init-restart");
            rescueReason = "PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_CONFIRMED";
        } else {
            f.functionalAlternative();
            rescueReason = "PVS1_RNA_INITIATION_FUNCTIONAL_ALTERNATIVE_CONFIRMED";
        }
        var condition = f.data.policy().withObject("nmdScope").putArray("positionConditions").addObject()
                .put("conditionId", "rescue-event").put("coordinate", "REFERENCE_EVENT_SPAN").put("minimumInclusive", 1)
                .put("basis", "SYNTHETIC exact reference event scope");
        condition.putArray("sourceIds").add("synthetic-source");
        f.rebind();
        var out = f.run();
        assertEquals("NOT_APPLICABLE", out.at("/fullRnaAssessment/status").asText(), out::toPrettyString);
        assertEquals(rescueReason, out.at("/fullRnaAssessment/reasonCode").asText());

        condition.put("maximumExclusive", 2);
        f.rebind();
        f.base.run(f.data, out);
        assertEquals("NOT_EVALUATED", out.at("/fullRnaAssessment/status").asText(), out::toPrettyString);
        assertEquals("PVS1_OUTSIDE_REVIEWED_POSITIONAL_SCOPE", out.at("/fullRnaAssessment/reasonCode").asText());
        assertEquals("OUTSIDE_SUPPORTED_RANGE", out.at("/positionConditionChecks/0/status").asText());
        assertEquals(rescueReason, out.at("/fullRnaInitiationAssessment/reasonCode").asText());
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());

        condition.remove("maximumExclusive");
        condition.put("coordinate", "REFERENCE_PTC_SPAN");
        f.rebind();
        f.base.run(f.data, out);
        assertEquals("NOT_EVALUATED", out.at("/fullRnaAssessment/status").asText(), out::toPrettyString);
        assertEquals("PVS1_POLICY_POSITION_UNRESOLVED", out.at("/fullRnaAssessment/reasonCode").asText());
        assertEquals("UNRESOLVED", out.at("/positionConditionChecks/0/status").asText());
        assertFalse(out.has("ptcStart"));
    }

    @ParameterizedTest @ValueSource(strings={"putative-is-not-observed-function","observed-without-function-review","different-start-unknown-function","self-signed-review"})
    void missingExperimentalOrIndependentFunctionFactsRemainUnresolved(String scenario) throws Exception {
        var f=new FullRnaInitiationFixture(root,1);String expected;
        var restart=f.documents.get("init-restart");
        switch(scenario) {
            case "putative-is-not-observed-function" -> {
                restart.put("functionFinding","FUNCTION_PRESERVED_IN_ASSESSED_SCOPE").put("physiologicalRelevance","ASSESSED")
                        .put("functionalQualification","REVIEWED_FUNCTIONAL_EXPERIMENT_FOR_FULL_RNA_PRODUCT");
                f.republish("init-restart");expected="PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_REVIEW_INCOMPLETE";
            }
            case "observed-without-function-review" -> {
                restart.put("startStatus","OBSERVED").put("functionFinding","FUNCTION_PRESERVED_IN_ASSESSED_SCOPE");
                f.republish("init-restart");expected="PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_REVIEW_INCOMPLETE";
            }
            case "different-start-unknown-function" -> {
                restart.put("startStatus","OBSERVED");f.republish("init-restart");
                var finding=(ObjectNode)f.documents.get("init-transcripts").at("/findings/1");finding.put("differentStart",true);finding.remove("functionallyRelevant");finding.remove("targetUnaffected");
                f.republish("init-transcripts");expected="PVS1_RNA_INITIATION_TRANSCRIPT_REVIEW_INCOMPLETE";
            }
            case "self-signed-review" -> {
                restart.put("startStatus","OBSERVED").put("functionFinding","FUNCTION_PRESERVED_IN_ASSESSED_SCOPE")
                        .put("physiologicalRelevance","ASSESSED").put("functionalQualification","REVIEWED_FUNCTIONAL_EXPERIMENT_FOR_FULL_RNA_PRODUCT");
                // Changing original fields does not change the server-selected policy or its scientific source pins.
                f.rna.original.withObject("pvs1Assessment").withObject("initiationReview").withObject("sourceDocuments").withObject("init-restart")
                        .put("rawText",f.rna.f.mapper.writeValueAsString(restart));
                expected="PVS1_RNA_INITIATION_SOURCE_ORIGINAL_SHA_MISMATCH";
            }
            default -> throw new AssertionError(scenario);
        }
        var out=f.run();assertEquals(expected,out.at("/fullRnaAssessment/reasonCode").asText(),out::toPrettyString);
        assertEquals("NOT_EVALUATED",out.at("/fullRnaAssessment/status").asText());
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());assertFalse(out.has("nmdPrediction"));
    }

    @ParameterizedTest @ValueSource(ints={1,-1})
    void editCreatedEarlierMutantInframeMetCannotBeOmittedFromThePutativeBaseline(int strand) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand);f.editedEarlierMutantMet();f.pathogenicReference(7,"G","A");
        var source=f.rna.source();assertEquals("CCCATGGCCATGTTTGAATAAGGGTGA",source.result().alteredRna());
        assertFalse(source.facts().nativeStartRetained());
        var out=f.run();
        assertEquals("PVS1_RNA_INITIATION_EARLIER_ALTERED_INFRAME_MET_REQUIRES_SEPARATE_RULE",out.at("/fullRnaAssessment/reasonCode").asText(),out::toPrettyString);
        assertEquals(4,out.at("/fullRnaInitiationAssessment/earlierAlteredInframeMetRnaPosition").asInt());
        assertEquals(10,out.at("/fullRnaInitiationAssessment/sourceSpecifiedStart/alteredStartRna1").asInt());
        assertEquals("MFE*",out.at("/fullRnaInitiationAssessment/sourceSpecifiedStart/proteinThroughFirstStop").asText());
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());assertFalse(out.has("nmdPrediction"));
        f.captureGuardProvider("novel-earlier-putative",out);
    }
    @ParameterizedTest @ValueSource(ints={1,-1})
    void independentlyObservedFunctionalLaterProductDoesNotUseThePutativeEarlierAtgGuard(int strand) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand);f.editedEarlierMutantMet();
        var d=f.documents.get("init-restart");d.put("startStatus","OBSERVED").put("functionFinding","FUNCTION_PRESERVED_IN_ASSESSED_SCOPE")
                .put("physiologicalRelevance","ASSESSED").put("functionalQualification","REVIEWED_FUNCTIONAL_EXPERIMENT_FOR_FULL_RNA_PRODUCT");
        f.rna.original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue","ASSESSED_IN_INITIATION_REVIEW");f.republish("init-restart");
        var out=f.run();assertEquals("NOT_APPLICABLE",out.at("/fullRnaAssessment/status").asText(),out::toPrettyString);
        assertEquals("PVS1_RNA_INITIATION_FUNCTIONAL_PRODUCT_CONFIRMED",out.at("/fullRnaAssessment/reasonCode").asText());
        assertEquals(10,out.at("/fullRnaInitiationAssessment/sourceSpecifiedStart/alteredStartRna1").asInt());
        assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"1,unknown-event","-1,unknown-event","1,geometry-only","-1,geometry-only","1,unaffected","-1,unaffected","1,affected","-1,affected"})
    void sameStartGeometryDoesNotEstablishTheRnaEffectOnAnotherFunctionalIsoform(int strand,String scenario) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand);
        var other=(ObjectNode)f.documents.get("init-transcripts").at("/findings/1");
        other.put("differentStart",false).put("functionallyRelevant",true).put("qualification","REFERENCE_START_GEOMETRY_VERIFIED");
        String expected;
        switch(scenario) {
            case "unknown-event" -> {other.remove("targetUnaffected");expected="PVS1_RNA_INITIATION_TRANSCRIPT_REVIEW_INCOMPLETE";}
            case "geometry-only" -> {other.put("targetUnaffected",false);expected="PVS1_RNA_INITIATION_FUNCTION_REVIEW_SCOPE_UNRESOLVED";}
            case "unaffected" -> {
                other.put("targetUnaffected",true).put("qualification","REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE");
                f.rna.original.withObject("pvs1Assessment").withObject("parentReview").put("functionalRescue","ASSESSED_IN_INITIATION_REVIEW");
                expected="PVS1_RNA_INITIATION_FUNCTIONAL_ALTERNATIVE_CONFIRMED";
            }
            case "affected" -> {other.put("targetUnaffected",false).put("qualification","REVIEWED_RNA_FUNCTION_AND_TARGET_EVENT_SCOPE");expected="";}
            default -> throw new AssertionError(scenario);
        }
        f.republish("init-transcripts");var out=f.run();
        assertEquals(expected,out.at("/fullRnaAssessment/reasonCode").asText(),out::toPrettyString);
        assertEquals("affected".equals(scenario)?"MET":"unaffected".equals(scenario)?"NOT_APPLICABLE":"NOT_EVALUATED",out.at("/fullRnaAssessment/status").asText());
        if("affected".equals(scenario))assertEquals("SUPPORTING",out.at("/fullRnaAssessment/adoptedStrength").asText());
        else assertFalse(out.at("/fullRnaAssessment/adoptedStrength").isTextual());
        f.captureGuardProvider("same-start-"+scenario,out);
    }

    @ParameterizedTest @ValueSource(ints={1,-1})
    void unmodifiedSameFrameUtrAtgDoesNotAcquireAnExtraExperimentalRequirement(int strand) throws Exception {
        var f=new FullRnaInitiationFixture(root,strand);f.unchangedUpstreamUtrAtg();
        var out=f.run();assertEquals("MET",out.at("/fullRnaAssessment/status").asText(),out::toPrettyString);
        assertEquals("SUPPORTING",out.at("/fullRnaAssessment/adoptedStrength").asText());
        assertEquals(13,out.at("/fullRnaInitiationAssessment/sourceSpecifiedStart/alteredStartRna1").asInt());
        assertTrue(out.at("/fullRnaInitiationAssessment/putativeRestartDoesNotProveTranslation").asBoolean());f.captureGuardProvider("unchanged-utr",out);
    }

}
