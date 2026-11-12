package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.provider.LocalEvidenceBatchRuntime;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.nbscn.varvis.variantanalysis.provider.T07FullRnaBusinessMaterials;
import org.springframework.lang.Nullable;
import static org.junit.jupiter.api.Assertions.*;

/** Neutral RNA engineering control. Reuses existing generic parent conditions unchanged;
 * never creates initiation/in-frame functional reviews or changes a scientific factory. */
public final class T07ProteinUnchangedBusinessWriter {
    private static final String C="ACMG_AMP_2015:", REASON="PVS1_FULL_RNA_PROTEIN_UNCHANGED";
    private static final String RNA="ATGGAACCCGGCTTTAAATAAGCTAGGTTGACCC";
    private T07ProteinUnchangedBusinessWriter(){ }
    public static void main(String[] args)throws Exception {
        assertEquals(5,args.length);
        Path root=Files.createDirectories(Path.of(args[0])),output=Files.createDirectories(Path.of(args[1]));
        Path old=Path.of(args[3]).toRealPath();String[] pins=args[4].split(",");assertEquals(2,pins.length);
        var originalBytes=new ArrayList<byte[]>();
        for(int i=0;i<2;i++){byte[] bytes=Files.readAllBytes(old.resolve("rna-"+i+".json"));assertEquals(pins[i],sha(bytes));originalBytes.add(bytes);}
        var constructor=T07FullRnaBusinessWriter.class.getDeclaredConstructor(Path.class,Path.class,Path.class,boolean.class);constructor.setAccessible(true);
        var workflow=Objects.requireNonNull(constructor.newInstance(root,output,Path.of(args[2]),true));AcmgBusinessIntegrationTest base=null;
        try {
            call(workflow,"prepare",new Class<?>[0]);base=field(workflow,"base",AcmgBusinessIntegrationTest.class);
            var materials=field(workflow,"materials",T07FullRnaBusinessMaterials.class);
            var manifest=field(workflow,"manifest",ObjectNode.class);
            manifest.put("expectedPvs1","COMPLETED_NOT_APPLICABLE_PROTEIN_UNCHANGED").put("expectedPm4","NO_LENGTH_SCORE")
                    .put("independentOracle","c9T>C plus RNA [24,24) A: native ATG/TAA preserved, protein MEPGFK* unchanged; only this PVS1 protein-LoF route is excluded.");
            materials.neutralRnaEvent("SYNTHETIC_UTR_INSERT_A_PROTEIN_UNCHANGED",24,24,"A",RNA);call(workflow,"install",new Class<?>[0]);
            var neutral=submit(workflow,List.of(C+"PVS1"),"GENERIC_NA_NEUTRAL_PREPARATION");assertFacts(neutral);
            assertEquals(2,neutral.at("/modules/pvs1/results").size());
            for(var raw:neutral.at("/modules/pvs1/results")) {
                assertEquals("NOT_EVALUATED",raw.at("/evaluation/evaluationResult/status").asText());
                assertEquals("PVS1_FULL_RNA_EFFECT_REVIEW_REQUIRED",raw.at("/evaluation/evaluationResult/reasonCode").asText());
                assertFalse(raw.at("/fullRnaCurrentUse/claim/completedExclusion").asBoolean());
            }
            bindExistingGenericParents(base,materials,neutral,originalBytes);call(workflow,"install",new Class<?>[0]);
            verifyCompleted(base,submit(workflow,List.of(C+"PVS1"),"GENERIC_NA_SINGLE"),output.resolve("GENERIC_NA_SINGLE"),true);
            verifyCompleted(base,submit(workflow,List.of(C+"PVS1",C+"PM4",C+"BP3"),"GENERIC_NA_RELATED"),output.resolve("GENERIC_NA_RELATED"),false);
            var enabled=new ArrayList<String>();base.service.inputOptions(base.analysisId,2).path("enabledCriteria").forEach(x->enabled.add(x.asText()));assertEquals(18,enabled.size());
            verifyCompleted(base,submit(workflow,enabled,"GENERIC_NA_CURRENT18"),output.resolve("GENERIC_NA_CURRENT18"),false);
            for(int i=0;i<2;i++)assertArrayEquals(originalBytes.get(i),Files.readAllBytes(old.resolve("rna-"+i+".json")));
            var receipt=base.mapper.createObjectNode().put("sourceTier","SYNTHETIC_ENGINEERING_NOT_HUMAN_FUNCTION")
                    .put("existingParentFieldsUnchanged",true).put("existingOriginalBytesUnchanged",true)
                    .put("newFunctionalReviewDocumentsCreated",false).put("initiationGeneratorCalled",false)
                    .put("functionalPreservationClaimed",false).put("productionFactoryReplaced",false);
            var parentPins=receipt.putArray("existingParentOriginalSha256");for(String pin:pins)parentPins.add(pin);
            Files.write(output.resolve("generic-parent-receipt.json"),base.mapper.writeValueAsBytes(receipt));call(workflow,"finishArtifacts",new Class<?>[0]);
        }finally{if(base!=null)base.closeDatabase();}
        var manifest=field(workflow,"manifest",ObjectNode.class);manifest.put("writerCompleted",true).put("writerDatabaseClosed",true);
        Files.write(output.resolve("reader-manifest.json"),Objects.requireNonNull(base).mapper.writeValueAsBytes(manifest));
    }
    /** Rebinds only neutral observation identity; generic source condition nodes are copied unchanged.
     * Old complete in-frame effect reviews and all initiation reviews are deliberately absent. */
    private static void bindExistingGenericParents(AcmgBusinessIntegrationTest base,T07FullRnaBusinessMaterials fixture,JsonNode neutral,List<byte[]> old)throws Exception {
        var originals=field(fixture,"originals",List.class);var business=field(fixture,"business",Object.class);var policies=field(business,"policies",List.class);
        assertEquals(2,originals.size());assertEquals(2,policies.size());var hashes=new RuntimeEvidenceV2Adapter(base.mapper);
        for(int i=0;i<2;i++) {
            assertInstanceOf(ObjectNode.class,originals.get(i));var original=(ObjectNode)originals.get(i);assertNotNull(original);assertFalse(original.has("pvs1Assessment"));
            var previous=base.mapper.readTree(old.get(i));var generic=previous.at("/pvs1Assessment/parentReview");assertTrue(generic.isObject());
            assertEquals(previous.at("/scope/transcript"),original.at("/scope/transcript"));
            var policy=base.mapper.valueToTree(policies.get(i));assertEquals(previous.at("/pvs1Assessment/policySha256").asText(),hashes.canonicalSha256(policy));
            assertFalse(policy.at("/spliceScope/initiationScope").isObject());
            JsonNode material=null;
            for(var candidate:neutral.at("/fullRnaArchive/materialsBySha256")) {
                var source=base.mapper.readTree(neutral.at("/fullRnaArchive/originalsByRawSha256").path(candidate.path("originalReference").asText()).path("originalUtf8").asText());
                if(source.at("/scope/transcript").equals(original.at("/scope/transcript"))){assertNull(material);material=candidate;}
            }
            if(material==null)throw new AssertionError("Actual neutral mechanical identity missing");
            var assessment=original.putObject("pvs1Assessment");assessment.set("method",previous.at("/pvs1Assessment/method").deepCopy());
            assessment.set("policySha256",previous.at("/pvs1Assessment/policySha256").deepCopy());
            assessment.put("mechanicalEffectSha256",material.at("/consequenceFacts/mechanicalEffectSha256").asText());assessment.set("parentReview",generic.deepCopy());
            assertEquals(generic,assessment.path("parentReview"));assertEquals(4,assessment.size());
            assertFalse(assessment.has("inFrameReview"));assertFalse(assessment.has("initiationReview"));
        }
        fixture.writeBundle();
    }
    private static void assertFacts(JsonNode batch) {
        assertEquals(2,batch.at("/fullRnaArchive/materialsBySha256").size());
        for(var material:batch.at("/fullRnaArchive/materialsBySha256")) {
            var facts=material.path("consequenceFacts");assertEquals("MEPGFK*",facts.path("alteredProteinThroughFirstTermination").asText());
            assertEquals("MEPGFK*",facts.at("/codingEffects/referenceProteinThroughTermination").asText());
            assertEquals(6,facts.path("alteredProteinLength").asInt());assertEquals(0,facts.path("netProteinLengthChange").asInt());
            assertTrue(facts.at("/nativeStart/retainedContiguously").asBoolean());assertTrue(facts.at("/nativeStop/retainedContiguously").asBoolean());
        }
    }
    private static void verifyCompleted(AcmgBusinessIntegrationTest base,ObjectNode batch,Path folder,boolean only)throws Exception {
        assertFacts(batch);assertEquals(2,batch.at("/modules/pvs1/results").size());
        for(var raw:batch.at("/modules/pvs1/results")) {
            assertEquals("NOT_EVALUATED",raw.at("/evaluation/evaluationResult/status").asText(),raw::toPrettyString);
            assertEquals("NOT_APPLICABLE",raw.at("/evaluation/criterionApplicability/status").asText());
            assertEquals("COMPLETED",raw.at("/evaluation/deterministicTrace/executionStatus").asText());assertEquals("NONE",raw.at("/evaluation/appliedStrength").asText());
            assertEquals(REASON,raw.at("/resolvedInput/fullRnaAssessment/reasonCode").asText());assertEquals("NOT_APPLICABLE",raw.at("/resolvedInput/fullRnaAssessment/status").asText());
            assertTrue(raw.at("/fullRnaCurrentUse/claim/completedExclusion").asBoolean());
            assertFalse(raw.at("/resolvedInput/fullRnaInitiationAssessment").isObject());assertFalse(raw.at("/resolvedInput/inFrameAssessment").isObject());
        }
        var product=base.mapper.readTree(Files.readAllBytes(folder.resolve("expected-controller-full.json")));int completed=0;
        if(only)assertEquals("COMPLETED",product.path("status").asText(),"PVS1-only completed exclusion must finish the normal run");
        for(var candidate:product.path("candidates")) {
            if(only)assertTrue(candidate.path("enabledRuleResultsComplete").asBoolean(),"PVS1-only candidate results must be complete: "+candidate.path("candidateId").asText());
            for(var attempt:candidate.path("attempts"))for(var criterion:attempt.at("/localBatch/criteria")) {
                if((C+"PVS1").equals(criterion.path("criterionId").asText())) {
                    assertEquals("NOT_APPLICABLE",criterion.path("status").asText());assertEquals("NONE",criterion.path("appliedStrength").asText());
                    assertEquals(REASON,criterion.path("reasonCode").asText());
                    assertTrue(LocalEvidenceBatchRuntime.completedFullRnaExclusion(base.mapper,batch,candidate,attempt,criterion));
                    var changed=criterion.deepCopy();((ObjectNode)changed.path("details")).put("originalResultSha256","f".repeat(64));
                    assertFalse(LocalEvidenceBatchRuntime.completedFullRnaExclusion(base.mapper,batch,candidate,attempt,changed));
                    var missing=criterion.deepCopy();((ObjectNode)missing.path("details")).remove("originalResultSha256");
                    assertFalse(LocalEvidenceBatchRuntime.completedFullRnaExclusion(base.mapper,batch,candidate,attempt,missing));completed++;
                }else if(List.of(C+"PM4",C+"BP3").contains(criterion.path("criterionId").asText()))assertEquals("NONE",criterion.path("appliedStrength").asText());
            }
        }
        assertEquals(2,completed,"Each actual selected background must expose its source-bound completed exclusion");
    }
    private static ObjectNode submit(T07FullRnaBusinessWriter workflow,List<String> selection,String name)throws Exception {
        return (ObjectNode)Objects.requireNonNull(call(workflow,"submit",new Class<?>[]{List.class,String.class},selection,name));
    }
    private static String sha(byte[] bytes)throws java.security.NoSuchAlgorithmException{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static <T>T field(Object owner,String name,Class<T> type)throws Exception {
        var member=owner.getClass().getDeclaredField(name);member.setAccessible(true);return Objects.requireNonNull(type.cast(member.get(owner)));
    }
    @Nullable private static Object call(Object owner,String name,Class<?>[] types,Object...args)throws Exception {
        var method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(owner,args);}catch(InvocationTargetException failed){var cause=failed.getCause();if(cause instanceof Exception e)throw e;if(cause instanceof Error e)throw e;throw failed;}
    }
}
