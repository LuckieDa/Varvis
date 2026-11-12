package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.provider.AcmgBusinessArtifacts;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Separate read-only JVM. Never builds current scientific runtime or restores source tokens. */
public final class T07FullRnaBusinessReader {
    private T07FullRnaBusinessReader(){ }
    public static void main(String[] args) throws Exception {
        assertEquals(2,args.length);Path root=Path.of(args[0]),output=Path.of(args[1]);
        var mapper=Jackson2ObjectMapperBuilder.json().build().enable(SerializationFeature.INDENT_OUTPUT)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        var manifest=mapper.readTree(Files.readAllBytes(output.resolve("reader-manifest.json")));
        long writer=manifest.path("writerPid").asLong();assertNotEquals(ProcessHandle.current().pid(),writer);
        assertTrue(ProcessHandle.of(writer).isEmpty()||!ProcessHandle.of(writer).orElseThrow().isAlive(),"Writer must actually exit before Reader starts");
        assertTrue(manifest.path("writerCompleted").asBoolean());assertTrue(manifest.path("writerDatabaseClosed").asBoolean());
        var dataSource=new DriverManagerDataSource("jdbc:h2:file:"+root.resolve("business-db").toAbsolutePath().toString().replace('\\','/')
                +";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE","sa","");
        var props=new VariantAnalysisProperties();props.setStorageRoot(root.resolve("jobs"));props.getPersistence().setTablePrefix("");props.getPersistence().setRetryBackoff(Duration.ZERO);
        var jobs=new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(dataSource),new DataSourceTransactionManager(dataSource),props,mapper);
        var store=new AcmgBusinessStore(dataSource);var forbidden=new AtomicInteger();
        var business=new AcmgBusinessService(mapper,jobs,store,new AcmgExecutionSources(mapper,store),
                ()->forbidden(forbidden),()->forbidden(forbidden),AcmgRecommendations.unavailable(mapper),
                ()->forbidden(forbidden),()->forbidden(forbidden),()->forbidden(forbidden),null);
        var http=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(business)).build();String analysis=manifest.path("analysisId").asText();
        var source=store.source(analysis).orElseThrow();assertEquals(manifest.path("sourceSha256").asText(),source.sha256());
        assertArrayEquals(Files.readAllBytes(output.resolve("expected-source.json")),source.json().getBytes(StandardCharsets.UTF_8));
        var receipt=mapper.createObjectNode().put("schemaVersion","t07-normal-current-reader-receipt-v1").put("writerPid",writer).put("readerPid",ProcessHandle.current().pid())
                .put("writerExitedBeforeReader",true).put("readOnlyH2",true).put("entry","NORMAL_SERVICE_CONTROLLER_GET_VIEW_BACKGROUND_FULL_EXPORT")
                .put("userActualDownload","NOT_RUN_USER_OWNED");var runs=receipt.putArray("runs");
        for(var row:manifest.path("runs")) {
            String name=row.path("name").asText(),runId=row.path("runId").asText();assertTrue(name.matches("[A-Z0-9_]+"));Path folder=output.resolve(name);
            var before=store.run(analysis,runId).orElseThrow();
            assertArrayEquals(Files.readAllBytes(folder.resolve("expected-public.json")),before.responseJson().getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(Files.readAllBytes(folder.resolve("expected-private.json")),before.privateJson().getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(Files.readAllBytes(folder.resolve("expected-request.json")),before.requestJson().getBytes(StandardCharsets.UTF_8));
            assertEquals(AcmgBusinessStore.sha(before.responseJson()),before.responseSha256());
            assertEquals(new AcmgBusinessArtifacts(mapper).requestHash(mapper.readTree(before.requestJson())),before.requestSha256());
            String route="/variant-analyses/"+analysis+"/acmg/runs/"+runId;
            if(row.path("expectedHttpStatus").asInt()==503) {
                assertTrue(row.path("ordinaryHashesRepaired").asBoolean());assertTrue(row.path("savedRequestUnchanged").asBoolean());
                assertEquals(AcmgBusinessStore.sha(before.privateJson()),mapper.readTree(before.responseJson()).path("privateArtifactSha256").asText());
                byte[] failure=http.perform(get(route)).andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsByteArray();
                http.perform(get(route+"/view")).andExpect(status().isServiceUnavailable());Files.write(folder.resolve("reader-expected-refusal.json"),failure);
                assertEquals(before,store.run(analysis,runId).orElseThrow());runs.addObject().put("name",name).put("runId",runId).put("expectedRefusal",true)
                        .put("httpStatus",503).put("ordinaryHashesValid",true).put("savedRequestUnchanged",true);continue;
            }
            byte[] full=http.perform(get(route)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            byte[] expected=Files.readAllBytes(folder.resolve("expected-controller-full.json"));assertArrayEquals(expected,full,"Complete actual HTTP export bytes must match Writer: "+name);
            JsonNode product=mapper.readTree(full);assertEquals(product,business.read(analysis,runId));Files.write(folder.resolve("reader-controller-full-export.json"),full);
            var indexBytes=http.perform(get(route+"/view")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();var index=mapper.readTree(indexBytes);
            assertTrue(index.path("executionSourceSha256").isTextual(),index::toPrettyString);Files.write(folder.resolve("reader-index.json"),indexBytes);
            var originals=product.at("/fullRnaEvidence/originalsByRawSha256");int originalCount=0;
            int backgroundsVerified=0, initiationSourcesVerified=0;
            for (var candidate : product.path("candidates")) {
                var attempts=candidate.path("attempts");
                for (int backgroundIndex=0; backgroundIndex<attempts.size(); backgroundIndex++) {
                    var background=http.perform(get(route+"/view/background").param("candidateId",candidate.path("candidateId").asText())
                            .param("backgroundIndex",Integer.toString(backgroundIndex)).param("sourceSha256",index.path("sourceViewSha256").asText()))
                            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                    var restored=mapper.readTree(background);
                    assertEquals(candidate.path("candidateId"),restored.path("candidateId"));
                    assertEquals(attempts.get(backgroundIndex),restored.at("/candidates/0/attempts/0"));
                    if (backgroundsVerified++==0) Files.write(folder.resolve("reader-background.json"),background);
                    initiationSourcesVerified+=verifyInitiationSources(mapper,originals,attempts.get(backgroundIndex));
                }
            }
            for(var field:originals.properties()) {
                byte[] raw=field.getValue().path("originalUtf8").asText().getBytes(StandardCharsets.UTF_8);assertEquals(field.getKey(),AcmgBusinessStore.sha(new String(raw,StandardCharsets.UTF_8)));originalCount++;
            }
            assertEquals(before,store.run(analysis,runId).orElseThrow());
            runs.addObject().put("name",name).put("runId",runId).put("exactStoredPublicPrivateRequest",true).put("exactControllerFullExport",true)
                    .put("candidateBackgroundsVerified",backgroundsVerified).put("sourceOriginalsVerified",originalCount)
                    .put("initiationSourcesVerified",initiationSourcesVerified).put("exportRawSha256",AcmgBusinessStore.sha(new String(full,StandardCharsets.UTF_8)));
        }
        assertEquals(0,forbidden.get());assertEquals(source,store.source(analysis).orElseThrow());
        receipt.put("forbiddenScientificFactoryCalls",forbidden.get()).put("savedStoreAndSourceUnchanged",true).put("passed",true);
        Files.write(output.resolve("reader-receipt.json"),mapper.writeValueAsBytes(receipt));System.out.println("PASS T07 Writer exited; normal read-only Service/Controller/full export and original bytes unchanged");
    }
    private static int verifyInitiationSources(ObjectMapper mapper,JsonNode originals,JsonNode attempt) throws java.io.IOException {
        int verified=0;
        for (var criterion : attempt.at("/localBatch/criteria")) {
            var facts=criterion.at("/details/pvs1Facts");
            var initiation=facts.at("/fullRnaDetails/initiation");
            if (!initiation.path("sourceDocumentsQualified").asBoolean()) continue;
            var original=originals.path(facts.at("/fullRnaUse/sourceOriginalSha256").asText()).path("originalUtf8");
            assertTrue(original.isTextual(),"Qualified initiation material must refer to an exported original");
            var sources=mapper.readTree(original.asText()).at("/pvs1Assessment/initiationReview/sourceDocuments");
            assertTrue(sources.isObject());
            for (var source : initiation.path("sourceChecks")) {
                var text=sources.path(source.path("sourceId").asText()).path("rawText");
                assertTrue(text.isTextual());assertEquals("RAW_SHA_MATCH",source.path("status").asText());
                assertEquals(source.path("actualRawSha256").asText(),AcmgBusinessStore.sha(text.asText()));
                verified++;
            }
            for (var quote : initiation.path("sourceQuotes")) {
                var text=sources.path(quote.path("sourceId").asText()).path("rawText");
                assertTrue(text.isTextual());assertEquals(quote.path("sourceRawSha256").asText(),AcmgBusinessStore.sha(text.asText()));
                assertEquals("JAVA_UTF16_ZERO_BASED_HALF_OPEN",quote.path("coordinateSystem").asText());
                assertEquals(quote.path("text").asText(),text.asText().substring(quote.path("start").asInt(),quote.path("displayEnd").asInt()));
            }
        }
        return verified;
    }
    private static <T>T forbidden(AtomicInteger counter){counter.incrementAndGet();throw new AssertionError("T07_HISTORY_MUST_NOT_BUILD_SCIENTIFIC_RUNTIME");}
}
