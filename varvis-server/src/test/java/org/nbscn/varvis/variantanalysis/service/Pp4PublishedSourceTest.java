package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Current production single scan and private source recovery; no synthetic PASS witnesses. */
class Pp4PublishedSourceTest {
    Pp4BusinessFixture b;
    @BeforeEach void setup() throws Exception { b=new Pp4BusinessFixture(); b.setup(); }
    @AfterEach void close() throws Exception { b.close(); }
    @ParameterizedTest @ValueSource(strings={"PASS","LowQual","no-ft","dot","missing","record-fail"})
    void capturedFilterAndRecordBoundarySurviveReconstruction(String mode) throws Exception {
        b.publishedVcf=vcf->Bp2PublishedFilterTest.transformRows(vcf,(i,row)->{
            switch(mode) {
                case "LowQual" -> row[9]=row[9].replace("PASS","LowQual");
                case "dot" -> row[9]=row[9].replace("PASS",".");
                case "no-ft" -> {row[8]=row[8].replace(":FT",""); row[9]=row[9].substring(0,row[9].lastIndexOf(':'));}
                case "missing" -> row[9]=row[9].substring(0,row[9].lastIndexOf(':'));
                case "record-fail" -> row[6]="LowQual";
                default -> { }
            } return row;
        });
        b.start("alpha"); String raw=b.store.source(b.analysisId).orElseThrow().json();
        b.artifact("captured-source",b.mapper.readTree(raw));
        new JdbcTemplate(requireNonNull(b.dataSource)).execute("SHUTDOWN");
        assertFalse(Files.exists(b.temporary.resolve("business-db.lock.db")));
        b.artifact("source-shutdown",b.mapper.createObjectNode().put("dbSha256",Pp4BusinessFixture.sha(Files.readAllBytes(b.temporary.resolve("business-db.mv.db")))));
        b.repositories(); b.wire(); assertEquals(raw,b.store.source(b.analysisId).orElseThrow().json());
        var job=b.jobs.findById(b.analysisId).orElseThrow(); var candidates=b.jobs.findInterpretations(b.analysisId,0,1);
        var recovered=b.sources.recover(job,candidates); assertTrue(recovered.candidateBlockers().isEmpty());
        assertEquals(1,recovered.execution().getInputSource().fullVcfScans());
        assertEquals(b.mapper.readTree(raw).at("/candidates/0/sampleFilterWitness"),b.mapper.valueToTree(recovered.execution().getInputSource().sampleFilter(candidates.get(0).getObservation().getKey())));
        if(mode.equals("PASS")) assertEquals("COMPLETED",b.submit(b.request(b.options())).path("status").asText());
        else {
            String expected=mode.equals("record-fail")?"PP4_PROBAND_FILTER_CONFLICT":mode.equals("LowQual")?"PP4_PUBLISHED_SAMPLE_FILTER_FAILED":"PP4_PUBLISHED_SAMPLE_FILTER_UNKNOWN";
            assertEquals(expected,assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());
            assertEquals(0,b.core.get()); assertTrue(b.store.runs(b.analysisId).isEmpty());
            b.artifact("refusal",b.mapper.createObjectNode().put("reason",expected).set("counts",b.counts()));
        }
    }
    @ParameterizedTest @ValueSource(strings={"sample","assembly","position","ordinal","alt","digest"})
    void corruptedWitnessCannotReachCore(String mode) throws Exception {
        b.start("alpha"); var request=b.request(b.options());
        var source=(ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json());
        var witness=(ObjectNode)source.at("/candidates/0/sampleFilterWitness");
        switch(mode) {
            case "sample", "digest" -> witness.put("sampleId","OTHER_TEST_ONLY");
            case "assembly" -> witness.put("assembly","GRCh37");
            case "position" -> ((ObjectNode)witness.path("record")).put("position",123);
            case "ordinal" -> ((ObjectNode)witness.path("record")).put("recordOrdinal",2);
            case "alt" -> ((ObjectNode)witness.path("record")).put("selectedAltIndex",2);
            default -> throw new IllegalArgumentException(mode);
        }
        if(mode.equals("digest"))new JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_source SET snapshot_json=? WHERE analysis_id=?",b.mapper.writeValueAsString(source),b.analysisId);
        else b.mutatePublishedSource(source);
        b.reject(request,mode.equals("digest")?"EXECUTION_SNAPSHOT_HASH_MISMATCH":"PP4_PUBLISHED_SAMPLE_FILTER_BINDING_CONFLICT");
        assertEquals(0,b.core.get()); assertTrue(b.store.runs(b.analysisId).isEmpty());
    }
    @Test void oldSourceRejectsNewExecutionButHistoryAndExactUnchangedIdentityRemainReadable() throws Exception {
        b.start("frozen/alpha-control"); var request=b.request(b.options()); var saved=b.submit(request);
        String id=saved.path("runId").asText(); var before=b.counts(); var list=b.service.list(b.analysisId);
        var candidate=b.service.candidate(b.analysisId,saved.at("/candidateIds/0").asText(),id);
        var original=b.store.source(b.analysisId).orElseThrow();
        var old=Bp2LegacySourceCompatibilityTest.withoutExtension((ObjectNode)b.mapper.readTree(original.json()));
        b.artifact("TEST_ONLY-legacy-shaped-source",old); b.mutatePublishedSource(old);
        assertEquals("PP4_PUBLISHED_SAMPLE_FILTER_WITNESS_REQUIRED",assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());
        b.reject(request,"PP4_PUBLISHED_SAMPLE_FILTER_WITNESS_REQUIRED");
        b.reject(request.deepCopy().put("retryOf",id),"PP4_PUBLISHED_SAMPLE_FILTER_WITNESS_REQUIRED");
        assertEquals(saved,b.service.read(b.analysisId,id)); assertEquals(list,b.service.list(b.analysisId)); assertEquals(candidate,b.service.candidate(b.analysisId,saved.at("/candidateIds/0").asText(),id));
        b.mutatePublishedSource((ObjectNode)b.mapper.readTree(original.json())); before=b.counts();
        assertEquals(saved,b.submit(request)); assertEquals(before,b.counts()); assertEquals(1,b.core.get());
        b.artifact("historical-read-counts",b.counts());
    }
}
