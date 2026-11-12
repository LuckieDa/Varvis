package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.nbscn.varvis.variantanalysis.config.Pm4Bp3ComplexPublicFixture;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import org.springframework.lang.Nullable;
import static org.junit.jupiter.api.Assertions.*;

/** Reuses frozen test workflow hooks without replacing any normal factory or scorer.
 * Consumes the already saved BP3/PVS1 source bytes unchanged; never writes initiation
 * functional sources, renews a review, or grants real-human scientific qualification. */
public final class T07ExistingBp3BusinessWriter {
    private T07ExistingBp3BusinessWriter(){ }
    public static void main(String[] args) throws Exception {
        assertEquals(6,args.length);Path root=Files.createDirectories(Path.of(args[0])),output=Files.createDirectories(Path.of(args[1]));
        Path existing=Path.of(args[3]).toRealPath(),oldOriginals=Path.of(args[5]).toRealPath();String pin=args[4];
        byte[] before=Files.readAllBytes(existing);assertEquals(pin,sha(before));
        var constructor=T07FullRnaBusinessWriter.class.getDeclaredConstructor(Path.class,Path.class,Path.class,boolean.class);constructor.setAccessible(true);
        var workflow=Objects.requireNonNull(constructor.newInstance(root,output,Path.of(args[2]),false));AcmgBusinessIntegrationTest base=null;
        try {
            call(workflow,"prepare",new Class<?>[0]);base=field(workflow,"base",AcmgBusinessIntegrationTest.class);
            var transport=field(workflow,"transport",Pm4Bp3ComplexPublicFixture.class);String prefix=Pm4Bp3ComplexPublicFixture.PREFIX;
            // Existing fixture setup may emit its old default duplicate. Require exact
            // equality with the frozen source, never a renewed or newly changed review.
            assertArrayEquals(before,Files.readAllBytes(root.resolve("rna/bp3-region-review.json")));
            transport.env().withProperty(prefix+"bp3-reviewed-region-assertions-file",Objects.requireNonNull(existing.toString()))
                    .withProperty(prefix+"bp3-reviewed-region-assertions-sha256",Objects.requireNonNull(pin))
                    .withProperty(prefix+"pvs1-selected-knowledge-enabled","false");
            call(workflow,"install",new Class<?>[0]);
            int pvs1Before=transport.configurationCalls().path("pvs1").asInt();
            var only=submit(workflow,List.of("ACMG_AMP_2015:BP3"),"BP3_ONLY_EXISTING_SOURCE");
            assertEquals(2,only.at("/modules/bp3/results").size());assertEquals(0,only.at("/modules/pvs1/results").size());
            assertEquals(pvs1Before,transport.configurationCalls().path("pvs1").asInt(),"BP3 must not call the PVS1 knowledge factory");
            for(var raw:only.at("/modules/bp3/results")) {
                assertEquals("MET",raw.at("/evaluation/evaluationResult/status").asText(),raw::toPrettyString);assertEquals("SUPPORTING",raw.at("/evaluation/appliedStrength").asText());
                assertEquals("ADOPTED",raw.at("/fullRnaCurrentUse/claim/application").asText());
            }
            int adopted=0;
            for(var attempt:only.path("results"))for(var criterion:attempt.at("/localBatch/criteria"))if("ACMG_AMP_2015:BP3".equals(criterion.path("criterionId").asText())) {
                assertEquals("MET",criterion.path("status").asText());assertEquals("SUPPORTING",criterion.path("appliedStrength").asText());adopted++;
            }
            assertEquals(2,adopted,"Both selected backgrounds must expose the adopted BP3 result");
            // Reuse complete old P3 effect reviews only when the current actual
            // mechanical/context identity agrees. No semantic source field changes.
            Path bundleFile=root.resolve("rna/bundle.json");var bundle=(ObjectNode)base.mapper.readTree(Files.readAllBytes(bundleFile));
            var oldBytes=new ArrayList<byte[]>();
            for(int i=0;i<2;i++) {
                Path old=oldOriginals.resolve("rna-"+i+".json");byte[] original=Files.readAllBytes(old);oldBytes.add(original);
                var doc=base.mapper.readTree(original);String expected=doc.at("/pvs1Assessment/mechanicalEffectSha256").asText();
                String tx=doc.at("/scope/transcript").asText();int found=0;
                for(var material:only.at("/fullRnaArchive/materialsBySha256")) {
                    String source=material.path("originalReference").asText();var candidate=base.mapper.readTree(only.at("/fullRnaArchive/originalsByRawSha256").path(source).path("originalUtf8").asText());
                    if(tx.equals(candidate.at("/scope/transcript").asText())) {assertEquals(expected,material.at("/consequenceFacts/mechanicalEffectSha256").asText(),"Existing source cannot be rewritten to adapt a different context");found++;}
                }
                assertEquals(1,found);Path target=root.resolve("rna/rna-"+i+".json");
                assertTrue(target.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()));assertNotEquals(old.toRealPath(),target.toRealPath());
                Files.copy(old,target,StandardCopyOption.REPLACE_EXISTING);assertArrayEquals(original,Files.readAllBytes(target));
                ((ObjectNode)bundle.at("/materials/pvs1FullRnaOriginals/"+i+"/binding/sourceRecord")).put("sha256",sha(original));
            }
            byte[] bound=base.mapper.writeValueAsBytes(bundle);Files.write(bundleFile,bound);var hashes=new RuntimeEvidenceV2Adapter(base.mapper);
            transport.env().withProperty(prefix+"pvs1-bundle-file-sha256",Objects.requireNonNull(sha(bound)))
                    .withProperty(prefix+"pvs1-bundle-sha256",Objects.requireNonNull(hashes.canonicalSha256(bundle)))
                    .withProperty(prefix+"pvs1-selected-knowledge-enabled","true");
            call(workflow,"install",new Class<?>[0]);
            var opposite=submit(workflow,List.of("ACMG_AMP_2015:PVS1","ACMG_AMP_2015:BP3"),"OPPOSITE_EXISTING_SOURCES");checkConflict(opposite);
            var options=base.service.inputOptions(base.analysisId,2);var enabled=new ArrayList<String>();options.path("enabledCriteria").forEach(x->enabled.add(x.asText()));
            assertEquals(18,enabled.size(),options::toPrettyString);checkConflict(submit(workflow,enabled,"CURRENT18_OPPOSITE_EXISTING_SOURCES"));
            assertArrayEquals(before,Files.readAllBytes(existing));for(int i=0;i<2;i++)assertArrayEquals(oldBytes.get(i),Files.readAllBytes(oldOriginals.resolve("rna-"+i+".json")));
            var sourceReceipt=base.mapper.createObjectNode().put("sourceTier","EXISTING_SYNTHETIC_TEST_ONLY_NOT_HUMAN_FUNCTION_EVIDENCE")
                    .put("bp3OriginalSha256",pin).put("bp3OriginalBytesUnchanged",true).put("pvs1OriginalBytesUnchanged",true)
                    .put("newFunctionalQualificationsCreated",false).put("newInitiationSourceGeneratorCalled",false).put("scientificCoreReplaced",false);
            Files.write(output.resolve("existing-source-receipt.json"),base.mapper.writeValueAsBytes(sourceReceipt));call(workflow,"finishArtifacts",new Class<?>[0]);
        } finally {if(base!=null)base.closeDatabase();}
        var manifest=field(workflow,"manifest",ObjectNode.class);manifest.put("writerDatabaseClosed",true).put("writerCompleted",true);
        var mapper=Objects.requireNonNull(base).mapper;Files.write(output.resolve("reader-manifest.json"),mapper.writeValueAsBytes(manifest));
    }
    private static ObjectNode submit(T07FullRnaBusinessWriter workflow,List<String> selection,String name)throws Exception {
        return (ObjectNode)Objects.requireNonNull(call(workflow,"submit",new Class<?>[]{List.class,String.class},selection,name));
    }
    private static void checkConflict(JsonNode batch) {
        for(String module:List.of("pvs1","bp3")) {
            assertEquals(2,batch.at("/modules/"+module+"/results").size());
            for(var raw:batch.at("/modules/"+module+"/results")) {
                assertEquals("MET",raw.at("/evaluation/evaluationResult/status").asText(),raw::toPrettyString);
                assertEquals(module.equals("pvs1")?"STRONG":"SUPPORTING",raw.at("/evaluation/appliedStrength").asText());
                assertEquals("CONFLICT",raw.at("/fullRnaCurrentUse/claim/application").asText());
            }
        }
        int conflicts=0;
        for(var attempt:batch.path("results"))for(var criterion:attempt.at("/localBatch/criteria"))if(List.of("ACMG_AMP_2015:PVS1","ACMG_AMP_2015:BP3").contains(criterion.path("criterionId").asText())) {
            assertEquals("NOT_EVALUATED",criterion.path("status").asText());assertEquals("NONE",criterion.path("appliedStrength").asText());
            assertEquals("FULL_RNA_OPPOSITE_EFFECT_EVIDENCE_CONFLICT",criterion.path("reasonCode").asText());conflicts++;
        }
        assertEquals(4,conflicts,"Both criteria in both selected backgrounds must expose the conflict");
    }
    private static String sha(byte[] bytes)throws java.security.NoSuchAlgorithmException {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static <T>T field(Object owner,String name,Class<T> type)throws Exception {
        var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return Objects.requireNonNull(type.cast(field.get(owner)));
    }
    @Nullable private static Object call(Object owner,String name,Class<?>[] types,Object...args)throws Exception {
        var method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(owner,args);}catch(InvocationTargetException failed){var cause=failed.getCause();if(cause instanceof Exception e)throw e;if(cause instanceof Error e)throw e;throw failed;}
    }
}
