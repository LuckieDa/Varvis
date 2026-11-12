package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import org.nbscn.varvis.variantanalysis.persistence.JdbcVariantAnalysisJobRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Separate JVMs: actual CURRENT submit/store Writer, then read-only normal Service/Controller.
 * Complete scientific inputs are explicit SYNTHETIC engineering originals, never clinical validation.
 */
public final class T04ServiceHistoryProcess {
    private T04ServiceHistoryProcess() { }
    public static void main(String[] args) throws Exception {
        Path directory=Path.of(args[1]).toAbsolutePath().normalize();
        if("write".equals(args[0])) {
            System.setProperty("varvis.pp1.python",args[2]); write(directory,args.length>3?args[3]:"legacy");
        } else if("read".equals(args[0])) read(directory);
        else throw new IllegalArgumentException("T04_SERVICE_HISTORY_MODE_REQUIRED");
    }
    static ObjectMapper mapper() {
        return org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build()
                .enable(SerializationFeature.INDENT_OUTPUT).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }
    private static void write(Path directory,String nativeMode) throws Exception {
        Files.createDirectories(directory); var metadata=mapper().createObjectNode().put("writerPid",ProcessHandle.current().pid());
        var records=metadata.putArray("records");
        try(var f=new T04CurrentBusinessTest.BusinessFixture(directory.resolve("w"),!nativeMode.equals("legacy"),nativeMode.equals("native-alias"),nativeMode.equals("native-decimal"),doc->{
            if(nativeMode.equals("native-partial")) {
                org.nbscn.varvis.variantanalysis.provider.T04PhenopacketOriginalFixture.addClinicalNumberAndUnrecordedRequiredDose(doc,false);
                doc.withObject("/proband/measurements/0/value/quantity").remove("unit");
            }
        })) {
            for(String mode:List.of("single","related","all")) {
                var request=f.base.request.deepCopy(); request.remove("selectedCriteria");
                if(!"all".equals(mode)) request.set("selectedCriteria",f.base.mapper.valueToTree("single".equals(mode)
                        ?List.of("ACMG_AMP_2015:PP1"):List.of("ACMG_AMP_2015:PP1","ACMG_AMP_2015:PS2","ACMG_AMP_2015:PM6")));
                var response=f.submit(request); var evaluation=f.pp1(response);
                assertEquals("MET",evaluation.path("status").asText()); assertEquals("SUPPORTING",evaluation.path("strength").asText());
                assertEquals("9",evaluation.at("/bayesFactor/numerator").asText()); assertEquals("4",evaluation.at("/bayesFactor/denominator").asText());
                assertEquals(1,evaluation.path("awardedPP1Points").asInt(-1));
                assertEquals("KNOWN_EMPTY",evaluation.at("/ledger/status").asText());
                assertEquals(mode.equals("single")?1:mode.equals("related")?3:18,response.path("enabledCriteria").size());
                String id=response.path("runId").asText(), analysis=f.base.analysisId, endpoint="/variant-analyses/"+analysis+"/acmg/runs/"+id;
                byte[] full=f.base.http.perform(get(endpoint)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                byte[] view=f.base.http.perform(get(endpoint+"/view")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                var index=f.base.mapper.readTree(view); String source=index.path("sourceViewSha256").asText();
                byte[] background=f.base.http.perform(get(endpoint+"/view/background").param("candidateId",response.at("/candidateIds/0").asText())
                        .param("backgroundIndex","0").param("sourceSha256",source)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                var stored=f.base.store.run(analysis,id).orElseThrow(); Path output=directory.resolve(mode); Files.createDirectories(output);
                Files.write(output.resolve("full.json"),full,StandardOpenOption.CREATE_NEW);
                Files.write(output.resolve("view.json"),view,StandardOpenOption.CREATE_NEW);
                Files.write(output.resolve("background.json"),background,StandardOpenOption.CREATE_NEW);
                Files.writeString(output.resolve("private.json"),stored.privateJson(),StandardOpenOption.CREATE_NEW);
                Files.writeString(output.resolve("public.json"),stored.responseJson(),StandardOpenOption.CREATE_NEW);
                var entry=records.addObject().put("mode",mode).put("runId",id).put("analysisId",analysis)
                        .put("candidateId",response.at("/candidateIds/0").asText()).put("sourceViewSha256",source);
                entry.put("fullRawSha256",sha(full)).put("responseRawSha256",stored.responseSha256());
            }
            assertEquals(3,f.pp1Source.evaluations());
            metadata.put("actualPythonEvaluations",f.pp1Source.evaluations()).put("actualDeNovoEvaluations",f.deNovoEvaluations());
            metadata.put("database",f.base.temporary.resolve("business-db").toAbsolutePath().toString());
            metadata.put("pythonEvaluationDirectory",f.pp1Project.resolve("local/work/pp1-evaluations").toAbsolutePath().toString());
        }
        Files.write(directory.resolve("metadata.json"),mapper().writeValueAsBytes(metadata),StandardOpenOption.CREATE_NEW);
        System.out.println("T04_NORMAL_SERVICE_WRITER_EXIT_READY records=3 actualPython=3");
    }
    private static void read(Path directory) throws Exception {
        var json=mapper(); var metadata=json.readTree(Files.readAllBytes(directory.resolve("metadata.json")));
        long writer=metadata.path("writerPid").asLong(); assertNotEquals(writer,ProcessHandle.current().pid());
        assertFalse(ProcessHandle.of(writer).map(value -> java.util.Objects.requireNonNull(value).isAlive()).orElse(false),"Writer must have genuinely exited before Reader");
        Path database=Path.of(metadata.path("database").asText()), mv=Path.of(database+".mv.db");
        assertTrue(database.startsWith(directory)); byte[] databaseBefore=Files.readAllBytes(mv);
        Path python=Path.of(metadata.path("pythonEvaluationDirectory").asText()); long pythonBefore=directoryCount(python);
        var ds=new DriverManagerDataSource("jdbc:h2:file:"+database.toString().replace('\\','/')+";MODE=MySQL;IFEXISTS=TRUE;ACCESS_MODE_DATA=r;FILE_LOCK=NO","sa","");
        var properties=new VariantAnalysisProperties(); properties.setStorageRoot(directory.resolve("w/jobs"));
        var jobs=new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(ds),new DataSourceTransactionManager(ds),properties,json);
        var store=new AcmgBusinessStore(ds); var sources=new AcmgExecutionSources(json,store); var runtimeCalls=new AtomicInteger();
        var service=new AcmgBusinessService(json,jobs,store,sources,()->{
            runtimeCalls.incrementAndGet(); throw new AssertionError("History must never initialize scientific producer/runtime");
        });
        var controller=MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
        for(var entry:metadata.path("records")) {
            String analysis=entry.path("analysisId").asText(),id=entry.path("runId").asText(); Path expected=directory.resolve(entry.path("mode").asText());
            var before=store.run(analysis,id).orElseThrow(); String endpoint="/variant-analyses/"+analysis+"/acmg/runs/"+id;
            assertEquals(Files.readString(expected.resolve("public.json")),before.responseJson());
            assertEquals(Files.readString(expected.resolve("private.json")),before.privateJson());
            byte[] unpinned=controller.perform(get(endpoint)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertEquals(json.readTree(Files.readAllBytes(expected.resolve("full.json"))),json.readTree(unpinned),"Scientific/original JSON values must be identical before testing wire bytes");
            byte[] full=controller.perform(get(endpoint).param("sourceViewSha256",entry.path("sourceViewSha256").asText()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            assertArrayEquals(Files.readAllBytes(expected.resolve("full.json")),full,"Exact normal HTTP full export");
            assertEquals(entry.path("fullRawSha256").asText(),sha(full));
            AcmgSavedViewAssertions.view(Files.readAllBytes(expected.resolve("view.json")),controller.perform(get(endpoint+"/view"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),json.readTree(full));
            AcmgSavedViewAssertions.view(Files.readAllBytes(expected.resolve("background.json")),controller.perform(get(endpoint+"/view/background")
                    .param("candidateId",entry.path("candidateId").asText()).param("backgroundIndex","0")
                    .param("sourceSha256",entry.path("sourceViewSha256").asText())).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(),json.readTree(full));
            assertEquals(json.readTree(full),service.read(analysis,id));
            var evidence=json.readTree(full).path("currentClinicalEvidence"); assertTrue(evidence.path("historyOnly").asBoolean());
            if(evidence.has("nativeClinicalReviewFacts")&&!evidence.at("/nativeClinicalReviewFacts/members/0/measurements").isEmpty()) {
                assertEquals("1.234567890123456789",evidence.at("/nativeClinicalReviewFacts/members/0/measurements/0/quantity/recordedValue").asText());
                assertEquals("EXACT_DECIMAL_STRING",evidence.at("/nativeClinicalReviewFacts/members/0/measurements/0/quantity/numericEncoding").asText());
                assertEquals("NOT_RECORDED_NO_DOSE_IMPUTATION",evidence.at("/nativeClinicalReviewFacts/members/0/medicalActions/0/doseIntervals/0/quantityStatus").asText());
            }
            if(evidence.has("nativeClinicalProjectionFailure")) {
                assertFalse(evidence.has("nativeClinicalReviewFacts"));
                assertEquals("PARTIAL_SOURCE_KNOWN_REQUIRED_FIELD_QUALITY_FAILURES",evidence.at("/nativeClinicalSourceQuality/status").asText());
                assertFalse(evidence.at("/nativeClinicalSourceQuality/completeSchemaConformanceAsserted").asBoolean(true));
                assertEquals("PP4_NATIVE_CLINICAL_ONTOLOGY_ID_REQUIRED",evidence.at("/nativeClinicalProjectionFailure/reasonCode").asText());
            }
            assertFalse(evidence.path("authorityRestorationSupported").asBoolean(true));
            for(var raw:evidence.path("rawBindings")) {
                byte[] bytes=Base64.getDecoder().decode(raw.at("/artifact/base64").asText());
                assertEquals(raw.path("rawSha256").asText(),sha(bytes)); assertEquals(raw.at("/artifact/byteLength").asInt(),bytes.length);
            }
            assertEquals(before,store.run(analysis,id).orElseThrow());
            Path previous=expected.resolve("reader-full.json");
            if(Files.exists(previous)) assertArrayEquals(Files.readAllBytes(previous),full,"Older retained Reader response also remains byte-identical");
            else Files.write(previous,full,StandardOpenOption.CREATE_NEW);
        }
        assertEquals(0,runtimeCalls.get()); assertEquals(pythonBefore,directoryCount(python));
        assertArrayEquals(databaseBefore,Files.readAllBytes(mv),"Read-only normal Service must not rewrite database bytes");
        System.out.println("T04_NORMAL_SERVICE_CONTROLLER_READ_ONLY_COMPLETE records=3 runtimeCalls=0");
    }
    private static long directoryCount(Path path) throws Exception { try(var files=Files.list(path)) { return files.filter(Files::isDirectory).count(); } }
    private static String sha(byte[] raw) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
}
