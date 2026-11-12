package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.provider.Ps2TestDirectory;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class Bp2LegacySourceCompatibilityTest {
    static ObjectNode withoutExtension(ObjectNode source) {
        source.remove("sampleFilterCapability");
        source.path("candidates").forEach(row->((ObjectNode)row).remove("sampleFilterWitness")); return source;
    }
    @ParameterizedTest @ValueSource(strings={"PS2","PM6","PM3"})
    void oldRulesReadOldShapeAndExecuteWithoutNewFtRequirement(String rule) throws Exception {
        Ps2BusinessIntegrationTest b=switch(rule) {case "PS2"->new Ps2BusinessIntegrationTest(); case "PM6"->new Pm6BusinessFixture(); default->new Pm3BusinessFixture();};
        b.setup();
        try {
            b.start("alpha"); var source=withoutExtension((ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json()));
            b.artifact("legacy-shaped-source-no-witness",source); b.mutatePublishedSource(source);
            var saved=b.submit(b.request(b.options())); assertEquals("COMPLETED",saved.path("status").asText()); assertEquals(1,b.core.get());
            assertFalse(b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json()).has("sampleFilterCapability"));
            b.rebuildAndCompare(b.request(b.options()),saved); assertEquals(1,b.core.get());
        } finally { b.close(); }
    }
    @Test void ordinaryExecutionAndCandidatePayloadRemainCompatibleWithOldSource() throws Exception {
        var b=new AcmgBusinessIntegrationTest(); b.temporary=Ps2TestDirectory.create("ordinary-ft-compat"); b.setup();
        try {
            b.publish(false,GenomeAssembly.GRCh38,null);
            var candidates=b.mapper.valueToTree(b.jobs.findInterpretations(b.analysisId,0,100));
            var source=withoutExtension((ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json())); b.replaceSnapshot(source);
            Files.writeString(b.temporary.resolve("old-shaped-source.json"),source.toPrettyString());
            var saved=b.postRequest(b.request); assertEquals("COMPLETED_WITH_BLOCKERS",saved.path("status").asText());
            assertEquals(2,saved.path("providerCalls").asInt()); assertEquals(1,b.invocations.get());
            assertEquals(candidates,b.mapper.valueToTree(b.jobs.findInterpretations(b.analysisId,0,100)));
            assertEquals(saved,b.postRequest(b.request)); assertEquals(1,b.invocations.get());
            Files.writeString(b.temporary.resolve("saved-ordinary.json"),saved.toPrettyString());
        } finally { b.closeDatabase(); assertFalse(Files.exists(b.temporary.resolve("business-db.lock.db"))); }
    }
    @Test void absentWitnessBlocksOnlyNewBp2WhileUnchangedSavedHistoryIsPreserved() throws Exception {
        var b=new Bp2BusinessFixture(); b.setup();
        try {
            b.start("beta"); var request=b.request(b.options()); var saved=b.submit(request);
            assertEquals("COMPLETED_WITH_BLOCKERS",saved.path("status").asText());
            var before=b.counts(); var list=b.service.list(b.analysisId); String id=saved.path("runId").asText();
            var candidate=b.service.candidate(b.analysisId,saved.at("/candidateIds/0").asText(),id);
            assertEquals(saved,b.submit(request)); assertEquals(before,b.counts());
            var original=b.store.source(b.analysisId).orElseThrow();
            var old=withoutExtension((ObjectNode)b.mapper.readTree(original.json())); b.artifact("frozen-old-source-shape",old); b.mutatePublishedSource(old);
            assertEquals("BP2_PUBLISHED_SAMPLE_FILTER_WITNESS_REQUIRED",assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());
            b.reject(request,"BP2_PUBLISHED_SAMPLE_FILTER_WITNESS_REQUIRED");
            b.reject(request.deepCopy().put("retryOf",id),"BP2_PUBLISHED_SAMPLE_FILTER_WITNESS_REQUIRED");
            assertEquals(saved,b.service.read(b.analysisId,id)); assertEquals(list,b.service.list(b.analysisId));
            assertEquals(candidate,b.service.candidate(b.analysisId,saved.at("/candidateIds/0").asText(),id));
            // Restore the same task snapshot bytes, not a repaired or migrated old source.
            b.mutatePublishedSource((ObjectNode)b.mapper.readTree(original.json()));
            assertEquals(original.sha256(),b.store.source(b.analysisId).orElseThrow().sha256()); before=b.counts();
            assertEquals(saved,b.submit(request)); assertEquals(before,b.counts()); assertEquals(1,b.core.get());
            b.artifact("history-no-new-calls",b.counts());
        } finally { b.close(); }
    }
}
