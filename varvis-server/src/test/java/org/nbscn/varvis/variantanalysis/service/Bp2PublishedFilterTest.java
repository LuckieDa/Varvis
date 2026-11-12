package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import org.junit.jupiter.api.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Source FT must not be inferred from a reviewed material's PASS or from record FILTER. */
class Bp2PublishedFilterTest {
    Bp2BusinessFixture b;
    @BeforeEach void setup() throws Exception { b=new Bp2BusinessFixture(); b.setup(); }
    @AfterEach void close() throws Exception { b.close(); }
    @Test void publishedLowQualCannotBorrowMaterialPass() throws Exception {
        b.publishedFt="LowQual"; b.start("alpha");
        assertEquals("54b37a28579a65c743bd1fdbad934a516f3a83dfe4f10e614222c9d2743ce97a",
                Bp2BusinessFixture.sha(Files.readAllBytes(b.jobs.findById(b.analysisId).orElseThrow().getUploadedFile())));
        // Original red fixture bytes are unchanged; this is new capture, not old-snapshot absence.
        var source=(ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json());
        assertEquals("FILTERED",source.at("/candidates/0/sampleFilterWitness/state").asText());
        assertEquals("FILTERED",source.at("/candidates/2/sampleFilterWitness/state").asText());
        assertEquals(1,source.path("fullVcfScans").asInt());
        try {
            var options=b.service.inputOptions(b.analysisId,1); var saved=b.submit(b.request(options));
            b.artifact("unexpected-ft-result",saved);
            fail("Published raw FT=LowQual but material FT=PASS was accepted: "+saved.path("status").asText());
        } catch (org.springframework.web.server.ResponseStatusException rejected) {
            assertNotNull(rejected.getReason()); assertTrue(requireNonNull(rejected.getReason()).startsWith("BP2_PUBLISHED_SAMPLE_FILTER_"));
            b.artifact("ft-rejection",b.mapper.createObjectNode().put("reason",rejected.getReason()));
            assertEquals(0,b.core.get());
        }
    }
    static String transformRows(String vcf, java.util.function.BiFunction<Integer,String[],String[]> change) {
        var out=new StringBuilder(); int ordinal=0;
        for(String line:vcf.split("\n")) {
            if(!line.startsWith("#") && !line.isEmpty()) line=String.join("\t",change.apply(ordinal++,line.split("\t",-1)));
            out.append(line).append('\n');
        }
        return out.toString();
    }
    @ParameterizedTest @ValueSource(strings={"PASS","target-fail","partner-fail","no-ft","dot","empty","missing","zero","record-fail"})
    void actualCaptureSurvivesStoreReconstructionAndRejectsMissingOrFailed(String mode) throws Exception {
        b.expectParserFailure=Set.of("empty","zero").contains(mode);
        b.publishedVcf=vcf->transformRows(vcf,(ordinal,row)->{
            if(mode.equals("no-ft")) {row[8]="GT:DP:GQ:AD"; row[9]=row[9].substring(0,row[9].lastIndexOf(':'));}
            else if(mode.equals("dot")) row[9]=row[9].replace("PASS",".");
            else if(mode.equals("empty")) row[9]=row[9].replace("PASS","");
            else if(mode.equals("missing")) row[9]=row[9].substring(0,row[9].lastIndexOf(':'));
            else if(mode.equals("zero")) row[9]=row[9].replace("PASS","0");
            else if(mode.equals("target-fail") && ordinal==0 || mode.equals("partner-fail") && ordinal==2) row[9]=row[9].replace("PASS","LowQual");
            else if(mode.equals("record-fail") && ordinal==0) row[6]="LowQual";
            return row;
        });
        b.start("alpha");
        if(b.expectParserFailure) { assertEquals(0,b.core.get()); assertTrue(b.store.runs(b.analysisId).isEmpty()); return; }
        String original=b.store.source(b.analysisId).orElseThrow().json();
        b.artifact("captured-source-before-shutdown",b.mapper.readTree(original));
        new JdbcTemplate(requireNonNull(b.dataSource)).execute("SHUTDOWN"); assertFalse(Files.exists(b.temporary.resolve("business-db.lock.db")));
        b.artifact("source-shutdown",b.mapper.createObjectNode().put("dbSha256",Bp2BusinessFixture.sha(Files.readAllBytes(b.temporary.resolve("business-db.mv.db")))));
        b.repositories(); b.wire(); assertEquals(original,b.store.source(b.analysisId).orElseThrow().json());
        var job=b.jobs.findById(b.analysisId).orElseThrow(); var ranked=b.jobs.findInterpretations(b.analysisId,0,100);
        var restored=b.sources.recover(job,List.of(ranked.get(0),ranked.get(2)));
        assertTrue(restored.candidateBlockers().isEmpty()); assertEquals(1,restored.execution().getInputSource().fullVcfScans());
        for(int index:List.of(0,2)) {
            var witness=restored.execution().getInputSource().sampleFilter(ranked.get(index).getObservation().getKey());
            assertNotNull(witness); assertEquals(b.mapper.readTree(original).at("/candidates/"+index+"/sampleFilterWitness"),b.mapper.valueToTree(witness));
        }
        if(mode.equals("PASS")) assertEquals("COMPLETED",b.submit(b.request(b.options())).path("status").asText());
        else {
            String expected=mode.equals("record-fail")?"BP2_PROBAND_FILTER_CONFLICT":Set.of("target-fail","partner-fail","zero").contains(mode)?"BP2_PUBLISHED_SAMPLE_FILTER_FAILED":"BP2_PUBLISHED_SAMPLE_FILTER_UNKNOWN";
            assertEquals(expected,assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());
            assertEquals(0,b.core.get()); assertTrue(b.store.runs(b.analysisId).isEmpty());
            b.artifact("rejected-source",b.mapper.createObjectNode().put("mode",mode).put("reason",expected).set("counts",b.counts()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"sample","position","ordinal","alt","assembly","state","raw","extra","type","digest"})
    void witnessAssociationAndStrictShapeCannotBeReassigned(String mode) throws Exception {
        b.start("alpha"); var request=b.request(b.options());
        var source=(ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json());
        var witness=(ObjectNode)source.at("/candidates/2/sampleFilterWitness");
        switch(mode) {
            case "sample" -> witness.put("sampleId","other");
            case "position" -> ((ObjectNode)witness.path("record")).put("position",123);
            case "ordinal" -> ((ObjectNode)witness.path("record")).put("recordOrdinal",1);
            case "alt" -> ((ObjectNode)witness.path("record")).put("selectedAltIndex",2);
            case "assembly" -> witness.put("assembly","GRCh37");
            case "state" -> witness.put("state","INVALID");
            case "raw" -> witness.put("rawValue","LowQual");
            case "extra" -> witness.put("unknown",true);
            case "type" -> ((ObjectNode)witness.path("record")).put("recordOrdinal","3");
            case "digest" -> witness.put("sampleId","tampered-without-digest");
            default -> throw new IllegalArgumentException(mode);
        }
        if(mode.equals("digest")) new JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_source SET snapshot_json=? WHERE analysis_id=?",b.mapper.writeValueAsString(source),b.analysisId);
        else b.mutatePublishedSource(source);
        b.reject(request,mode.equals("digest")?"EXECUTION_SNAPSHOT_HASH_MISMATCH":"BP2_PUBLISHED_SAMPLE_FILTER_BINDING_CONFLICT");
        assertTrue(b.store.runs(b.analysisId).isEmpty()); assertEquals(0,b.core.get());
    }
    @Test void changedFtWitnessInvalidatesConfirmationAndMaterialMismatchFailsBeforeCore() throws Exception {
        b.start("alpha"); var request=b.request(b.options());
        var source=(ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json());
        var witness=(ObjectNode)source.at("/candidates/2/sampleFilterWitness");
        witness.put("state","FILTERED").put("rawValue","LowQual"); b.mutatePublishedSource(source);
        b.reject(request,"BP2_PUBLISHED_SAMPLE_FILTER_FAILED");
        witness.put("state","PASS").put("rawValue","PASS"); source.put("requestSha256","a".repeat(64)); b.mutatePublishedSource(source);
        b.reject(request,"BP2_SELECTION_STALE");
        b.issue("material-filter-conflict",n->Bp2BusinessFixture.at(n,"/observations/0/facts/records/1").put("sampleFilter","LowQual"));
        assertEquals("BP2_PUBLISHED_SAMPLE_FILTER_MATERIAL_CONFLICT",assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());
        assertEquals(0,b.core.get());
    }
}
