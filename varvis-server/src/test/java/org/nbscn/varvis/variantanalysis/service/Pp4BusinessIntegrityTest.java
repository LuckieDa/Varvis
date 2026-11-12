package org.nbscn.varvis.variantanalysis.service;
import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.service.Pp4BusinessFixture.at;

class Pp4BusinessIntegrityTest {
    Pp4BusinessFixture b;
    @BeforeEach void setup() throws Exception {b=new Pp4BusinessFixture();b.setup();}
    @AfterEach void close() throws Exception {b.close();}
    @ParameterizedTest @ValueSource(strings={"raw","canonical","projection","float","part","assessment","catalog","policy-pin","registry-pin"})
    void badInternalPinsNeverReachCore(String mode) throws Exception {
        b.start("alpha"); var n=b.bundle.deepCopy();var source=at(n,"/observations/0/facts/sources/0");String reason;
        switch(mode){
            case "raw" -> {source.put("rawSha256","0".repeat(64));Pp4BusinessMaterials.pinsOnly(n);reason="PP4_SOURCE_RAW_PIN_MISMATCH";}
            case "canonical" -> {source.put("canonicalSha256","0".repeat(64));Pp4BusinessMaterials.pinsOnly(n);reason="PP4_SOURCE_CANONICAL_PIN_MISMATCH";}
            case "projection", "float" -> {var r=(ObjectNode)b.mapper.readTree(source.path("rawUtf8").asText());if(mode.equals("float"))r.put("extra",0.25);else r.put("familyId","OTHER_TEST_ONLY");String raw=b.mapper.writeValueAsString(r);source.put("rawUtf8",raw).put("rawSha256",Pp4BusinessFixture.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));if(!mode.equals("float"))source.put("canonicalSha256",b.canonical(r));Pp4BusinessMaterials.pinsOnly(n);reason=mode.equals("float")?"PP4_SOURCE_DOCUMENT_INVALID":"PP4_SOURCE_FACTS_MISMATCH";}
            case "part" -> {at(n,"/observations/0/assessment/partPins").put("facts","0".repeat(64));reason="PP4_PART_PIN_MISMATCH";}
            case "assessment" -> {at(n,"/observations/0/assessment").put("contentSha256","0".repeat(64));reason="PP4_ASSESSMENT_PIN_MISMATCH";}
            case "policy-pin" -> {at(n,"/catalog/entries/0/policyBinding").put("policySha256","0".repeat(64));n.put("catalogSha256",b.canonical(n.path("catalog")));reason="PP4_POLICY_PIN_MISMATCH";}
            case "registry-pin" -> {at(n,"/registryBinding").put("registrySha256","0".repeat(64));reason="PP4_REGISTRY_PIN_MISMATCH";}
            default -> {n.put("catalogSha256","0".repeat(64));reason="PP4_CATALOG_PIN_MISMATCH";}
        }
        b.saveNew("bad-"+mode,b.mapper.writeValueAsBytes(n));
        assertEquals(reason,assertThrows(ResponseStatusException.class,()->b.service.inputOptions(b.analysisId,1)).getReason());
        assertEquals(0,b.core.get());assertTrue(b.store.runs(b.analysisId).isEmpty());b.artifact("integrity-rejection",b.mapper.createObjectNode().put("reason",reason).set("counts",b.counts()));
    }
    @ParameterizedTest @ValueSource(strings={"request","response","profile-version"})
    void corruptOrWrongProfilePredecessorCannotBeRetried(String mode) throws Exception {
        b.start("frozen/alpha-control");var request=b.request(b.options());var saved=b.submit(request);String id=saved.path("runId").asText();var original=b.store.run(b.analysisId,id).orElseThrow();var jdbc=new JdbcTemplate(requireNonNull(b.dataSource));String reason;
        if(mode.equals("response")){jdbc.update("UPDATE variant_analysis_acmg_run SET response_json=? WHERE run_id=?","{}",id);reason="ACMG_SAVED_RESULT_INTEGRITY_FAILURE";}
        else if(mode.equals("request")){jdbc.update("UPDATE variant_analysis_acmg_run SET request_json=? WHERE run_id=?","{}",id);reason="RETRY_SOURCE_INTEGRITY_FAILURE";}
        else {var identity=(ObjectNode)b.mapper.readTree(original.requestJson());at(identity,"/executionProfile").put("profileVersion","999");jdbc.update("UPDATE variant_analysis_acmg_run SET request_json=?,request_sha256=? WHERE run_id=?",b.mapper.writeValueAsString(identity),b.canonical(identity),id);reason="PP4_RETRY_SCOPE_MISMATCH";}
        var before=b.counts();b.reject(request.deepCopy().put("retryOf",id),reason);assertEquals(before,b.counts());
        b.artifact("corrupt-predecessor",b.mapper.valueToTree(b.store.run(b.analysisId,id).orElseThrow()));
    }
    @Test void explicitlySyntheticLegacyHistoryCanReuseWithoutAnySourcePreparation() throws Exception {
        b.start("alpha");var request=b.request(b.options());var saved=b.submit(request);String id=saved.path("runId").asText();var run=b.store.run(b.analysisId,id).orElseThrow();
        // TEST_ONLY stored-history fixture, not a claim that historical PP4 executed without FT.
        var legacy=Bp2LegacySourceCompatibilityTest.withoutExtension((ObjectNode)b.mapper.readTree(b.store.source(b.analysisId).orElseThrow().json()));b.mutatePublishedSource(legacy);
        var identity=(ObjectNode)b.mapper.readTree(run.requestJson());at(identity,"/pp4PublishedInputIdentity").put("executionSourceSha256",b.store.source(b.analysisId).orElseThrow().sha256());String hash=b.canonical(identity);var historical=saved.deepCopy().put("requestSha256",hash);String json=b.mapper.writeValueAsString(historical);
        new JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET request_sha256=?,request_json=?,response_sha256=?,response_json=? WHERE run_id=?",hash,b.mapper.writeValueAsString(identity),org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.sha(json),json,id);
        b.artifact("TEST_ONLY-history-constructed-identity",identity);b.artifact("TEST_ONLY-history-constructed-result",historical);b.artifact("TEST_ONLY-history-old-source",legacy);
        Files.move(b.material,b.temporary.resolve("retained-material.json"));b.wire();var before=b.counts();
        assertEquals(historical,b.service.read(b.analysisId,id));assertEquals(historical,b.submit(request));assertEquals(before,b.counts());
        assertEquals(1,b.core.get());b.artifact("history-reuse-no-preparation",b.counts());
    }
    @Test void ordinaryScopeAndEarlyAssociationCannotAcceptPp4Markers() throws Exception {
        var old=new AcmgBusinessIntegrationTest();old.temporary=Ps2TestDirectory.create("ordinary-pp4-compat");old.setup();
        try {old.publish(false,org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38,null);
            for(String mode:List.of("selection","declaration","token")){var r=old.request.deepCopy();
                if(mode.equals("selection"))r.putObject("pp4Selection").put("confirmed",true);else if(mode.equals("declaration"))r.put("declarationVersion",AcmgBusinessRuleScope.PP4_DECLARATION);else r.put("sampleId",Pp4PhenotypeContextPreparation.TOKEN+"x");
                assertEquals("PP4_SCOPE_REQUIRED",assertThrows(ResponseStatusException.class,()->old.service.submit(old.analysisId,old.mapper.writeValueAsBytes(r))).getReason());}
            assertEquals(0,old.invocations.get());
        }finally{old.closeDatabase();}
    }

    @Test void retryCannotBorrowAnotherRuleOrCorruptPrivatePredecessor()throws Exception {
        b.start("frozen/alpha-control");var request=b.request(b.options());var foreign=b.mapper.createObjectNode();foreign.putObject("declaration").put("declarationVersion",AcmgBusinessRuleScope.BS4_DECLARATION);
        foreign.set("executionProfile",AcmgExecutionProfile.bs4Development(b.mapper,b.mapper.createObjectNode()).document());String foreignId=UUID.randomUUID().toString();
        assertTrue(b.store.claim(new org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.Run(foreignId,b.analysisId,b.canonical(foreign),b.mapper.writeValueAsString(foreign),"RUNNING","2026-10-03T00:00:00Z",null,null,null)));b.store.failed(foreignId);
        b.reject(request.deepCopy().put("retryOf",foreignId),"PP4_RETRY_SCOPE_MISMATCH");assertEquals(0,b.core.get());
        var saved=b.submit(request);String id=saved.path("runId").asText();new JdbcTemplate(requireNonNull(b.dataSource)).update("UPDATE variant_analysis_acmg_run SET private_json=? WHERE run_id=?","{}",id);
        var before=b.counts();b.reject(request.deepCopy().put("retryOf",id),"ACMG_SAVED_RESULT_INTEGRITY_FAILURE");assertEquals(before,b.counts());
    }
}
