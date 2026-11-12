package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.provider.RuntimeEvidenceV2Adapter;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Acceptance assertions only; the caller must separately witness the actual configured process. */
final class IntronicScopeEvidenceAssertions {
    private IntronicScopeEvidenceAssertions() { }
    static String rawHash(JsonNode raw,ObjectMapper mapper) {
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(new RuntimeEvidenceV2Adapter(mapper),"canonicalSha256",raw);
    }
    static Map<String,JsonNode> keyed(JsonNode rows,String key) {
        assertTrue(rows.isArray());var result=new LinkedHashMap<String,JsonNode>();
        for(var row:rows) {String id=row.path(key).asText();assertFalse(id.isBlank());assertNull(result.put(id,row),"Duplicate "+key);}
        return result;
    }
    static void available(JsonNode evidence) {
        assertEquals("SUCCEEDED",evidence.at("/resourceExecution/status").asText());
        assertEquals("AVAILABLE",evidence.at("/resourceAvailability/status").asText());
        assertEquals("HIT",evidence.at("/queryOutcome/status").asText());
        assertEquals("APPLICABLE",evidence.at("/resourceApplicability/status").asText());
    }
    static boolean verify(JsonNode saved,JsonNode raw,ObjectMapper mapper) {
        assertNotNull(raw,"No real raw batch result captured");assertEquals("SUCCEEDED",raw.path("status").asText());
        assertTrue(saved.path("executionSourceSha256").asText().matches("[a-f0-9]{64}"),"Persisted real execution source was not restored");
        var rawHash=rawHash(raw,mapper);
        var attempts=new HashMap<String,JsonNode>();
        for(var candidate:saved.path("candidates")) for(var attempt:candidate.path("attempts"))
            if("SPLICEAI_INTRONIC".equals(attempt.at("/transcriptInput/predictionPath").asText())) {
                assertTrue(attempt.path("blockers").isEmpty(),attempt.toString());
                var transcript=attempt.path("transcriptInput");assertEquals("VEP",transcript.path("source").asText());
                var lineage=transcript.path("rawBatchLineage");assertEquals("SUCCEEDED",lineage.path("status").asText());
                assertEquals(1,lineage.path("batchInvocations").asInt());assertEquals(4999,lineage.path("distanceArgument").asInt());
                assertTrue(lineage.path("maskArgument").isIntegralNumber());assertEquals(0,lineage.path("maskArgument").asInt());
                assertEquals(rawHash,lineage.path("rawResultCanonicalSha256").asText());
                assertNull(attempts.put(attempt.at("/context/contextId").asText(),attempt));
            }
        int verified=0;boolean met=false;
        for(var item:saved.path("bundles")) {
            var bundle=item.path("bundle");var evaluations=keyed(bundle.path("criterionEvaluations"),"criterionId");
            if(!evaluations.containsKey("ACMG_AMP_2015:BP7"))continue;
            assertEquals(Set.of("ACMG_AMP_2015:PP3","ACMG_AMP_2015:BP4","ACMG_AMP_2015:BP7"),evaluations.keySet());
            var context=bundle.path("context");String contextId=context.path("contextId").asText();
            assertNotNull(attempts.remove(contextId),"Missing matching executed attempt");
            var atomic=keyed(bundle.path("atomicEvidence"),"evidenceId");
            var bySource=new HashMap<String,JsonNode>();for(var e:atomic.values())assertNull(bySource.put(e.at("/source/sourceId").asText(),e));
            var splice=bySource.get("SPLICEAI_V1_3_1_RAW_GRCH38_LOCAL_COMPUTE");var vep=bySource.get("VEP_116_GRCH38");
            assertNotNull(splice);assertNotNull(vep);available(splice);available(vep);
            var sm=keyed(splice.path("measurements"),"metricId");var vm=keyed(vep.path("measurements"),"metricId");
            String tx=context.at("/transcript/accession").asText()+"."+context.at("/transcript/version").asInt();
            assertEquals(tx,sm.get("spliceai.selectedTranscriptId").path("rawValue").asText());
            assertEquals(tx,vm.get("vep.selectedTranscriptId").path("rawValue").asText());
            assertEquals("true",sm.get("spliceai.exactAlleleMatch").path("rawValue").asText());
            assertEquals("true",sm.get("spliceai.geneContextMatch").path("rawValue").asText());
            JsonNode hit=null;for(var row:raw.path("results")) if(row.path("allele").equals(context.at("/variant/evidenceQueryAllele"))) {assertNull(hit);hit=row;}
            assertNotNull(hit,"Raw result allele differs from evaluated allele");assertEquals("HIT",hit.path("status").asText());assertTrue(hit.path("exactAlleleMatch").asBoolean());
            JsonNode annotation=null;for(var a:hit.path("annotations"))if(a.path("geneSymbol").equals(context.at("/gene/symbol"))){assertNull(annotation);annotation=a;}
            assertNotNull(annotation);BigDecimal max=BigDecimal.ZERO;
            for(String name:List.of("AG","AL","DG","DL")) {
                String observed=sm.get("spliceai.DS_"+name).path("rawValue").asText();
                assertEquals(annotation.at("/deltaScores/DS_"+name).asText(),observed,"Raw score changed");
                var value=new BigDecimal(observed);assertTrue(value.compareTo(BigDecimal.ZERO)>=0&&value.compareTo(BigDecimal.ONE)<=0);max=max.max(value);
                assertEquals(annotation.at("/deltaPositions/DP_"+name).asText(),sm.get("spliceai.DP_"+name).path("rawValue").asText());
            }
            assertEquals(0,max.compareTo(new BigDecimal(sm.get("spliceai.maximumRawDeltaScore").path("rawValue").asText())));
            for(var evaluation:evaluations.values()) {
                assertEquals(contextId,evaluation.path("contextId").asText());
                assertTrue(Set.of("MET","NOT_MET").contains(evaluation.at("/evaluationResult/status").asText()),"Explicit evaluable status required");
            }
            var pp3=evaluations.get("ACMG_AMP_2015:PP3");var bp4=evaluations.get("ACMG_AMP_2015:BP4");var bp7=evaluations.get("ACMG_AMP_2015:BP7");
            assertEquals(max.compareTo(new BigDecimal("0.20"))>=0?"MET":"NOT_MET",pp3.at("/evaluationResult/status").asText());
            assertEquals(max.compareTo(new BigDecimal("0.10"))<=0?"MET":"NOT_MET",bp4.at("/evaluationResult/status").asText());
            for(var evaluation:List.of(pp3,bp4))assertEquals(mapper.createArrayNode().add(splice.path("evidenceId").asText()),evaluation.path("evidenceIds"));
            assertEquals(Set.of(splice.path("evidenceId").asText(),vep.path("evidenceId").asText()),
                    new HashSet<>(mapper.convertValue(bp7.path("evidenceIds"),mapper.getTypeFactory().constructCollectionType(List.class,String.class))));
            var params=keyed(bp7.at("/deterministicTrace/parameters"),"name");
            assertEquals(bp4.path("evaluationId"),params.get("dependency.bp4EvaluationId").path("value"));
            assertEquals(bp4.at("/evaluationResult/status"),params.get("dependency.bp4Status").path("value"));
            assertEquals(bp4.at("/evaluationResult/status"),bp7.at("/evaluationResult/status"));
            if("MET".equals(bp7.at("/evaluationResult/status").asText()))
                assertEquals("SUPPORTING",bp7.path("appliedStrength").asText());
            int offset=Integer.parseInt(params.get("position.intronicSignedOffset").path("value").asText());
            assertTrue(offset>=7||offset<=-21);assertEquals(offset,Integer.parseInt(vm.get("vep.intronicSignedOffset").path("rawValue").asText()));
            met|="MET".equals(bp7.at("/evaluationResult/status").asText());verified++;
        }
        assertTrue(verified>0,"No evaluable SpliceAI/BP7 bundle");assertTrue(attempts.isEmpty(),"Executed context bundle missing");return met;
    }
}
