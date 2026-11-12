package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.controller.SplicingExperimentCheckController;
import org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources;
import org.nbscn.varvis.variantanalysis.persistence.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Opt-in loopback acceptance service. Copies closed H2 databases; never runs models or writes originals. */
public final class SplicingExperimentPreview {
    private SplicingExperimentPreview() { }
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("private-config-json port");
        ObjectMapper mapper=org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        JsonNode config=mapper.readTree(Files.readAllBytes(Path.of(args[0])));
        Path output=Path.of(config.path("output").asText()).toAbsolutePath();Files.createDirectories(output);
        Path staticRoot=Path.of(config.path("staticRoot").asText()).toAbsolutePath();
        var connections=new ArrayList<DriverManagerDataSource>();var routes=new HashMap<String,MockMvc>();
        var analyses=new HashMap<String,String>();var publicCases=mapper.createArrayNode();var forbidden=new AtomicInteger();
        for(JsonNode entry:config.path("cases")) {
            String name=entry.path("name").asText();if(!name.matches("[a-z-]+"))throw new IllegalArgumentException("case name");
            Path folder=output.resolve(name);Files.createDirectories(folder);
            Path database=folder.resolve("business-db.mv.db");
            if(!Files.exists(database))Files.copy(Path.of(entry.path("databaseSource").asText()),database);
            var ds=new DriverManagerDataSource("jdbc:h2:file:"+folder.resolve("business-db").toString().replace('\\','/')+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE","sa","");connections.add(ds);
            var props=new VariantAnalysisProperties();props.setStorageRoot(folder.resolve("jobs"));
            var jobs=new JdbcVariantAnalysisJobRepository(new NamedParameterJdbcTemplate(ds),new DataSourceTransactionManager(ds),props,mapper);
            var store=new AcmgBusinessStore(ds);
            var business=new AcmgBusinessService(mapper,jobs,store,new AcmgExecutionSources(mapper,store),
                    ()->{forbidden.incrementAndGet();throw new AssertionError("Preview must not invoke computation");},
                    ()->{forbidden.incrementAndGet();throw new AssertionError("Preview must not invoke BA1");});
            String analysis=entry.path("analysisId").asText(),run=entry.path("runId").asText();
            ObjectNode saved=business.read(analysis,run);
            JsonNode expected=mapper.readTree(Files.readAllBytes(Path.of(entry.path("savedSource").asText())));
            if(!saved.equals(expected))throw new IllegalStateException("Copied database does not match saved computation");
            var catalog=new ClassPathResource("acmg/public-splicing-experiments-v1.json");
            var checks=new SplicingExperimentChecks(mapper,catalog,folder.resolve("checks"),business::read);
            if(entry.path("historyFixture").asBoolean()) {
                ObjectNode first=checks.checkCurrent(analysis,run);
                ObjectNode testCatalog;
                try(var in=catalog.getInputStream()){testCatalog=(ObjectNode)mapper.readTree(in);}
                testCatalog.put("version","ISOLATED-HISTORY-TEST-2");
                testCatalog.put("coverage","ISOLATED UI VERSION TEST ONLY. Original scientific record contents unchanged.");
                testCatalog.remove("contentSha256");testCatalog.put("contentSha256",checks.hash(testCatalog));
                var alternate=new SplicingExperimentChecks(mapper,new ByteArrayResource(Objects.requireNonNull(mapper.writeValueAsBytes(testCatalog))),folder.resolve("checks"),business::read);
                alternate.checkCurrent(analysis,run);
                if(!first.equals(checks.read(analysis,run,first.path("checkId").asText())))throw new IllegalStateException("Old snapshot changed");
                Path corrupt=folder.resolve("checks").resolve(AcmgBusinessStore.sha(analysis+"\n"+run)).resolve("f".repeat(64)+".json");
                if(!Files.exists(corrupt))Files.writeString(corrupt,"{isolated-corrupt-history-test",StandardOpenOption.CREATE_NEW);
            }
            routes.put(name,MockMvcBuilders.standaloneSetup(new AcmgBusinessController(business),new SplicingExperimentCheckController(checks)).build());
            analyses.put(name,analysis);
            publicCases.addObject().put("name",name).put("label",entry.path("label").asText()).put("analysisId",analysis).put("runId",run);
        }
        var stop=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        var server=com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1",Integer.parseInt(args[1])),0);server.setExecutor(executor);
        server.createContext("/",exchange->{
            int code=404;byte[] body=new byte[0];String type="application/json; charset=utf-8";
            try {
                String path=exchange.getRequestURI().getPath(),method=exchange.getRequestMethod();
                if(path.equals("/preview/stop")&&method.equals("POST")){code=200;stop.countDown();}
                else if(path.equals("/preview/cases")&&method.equals("GET")){code=200;body=mapper.writeValueAsBytes(publicCases);}
                else if(path.equals("/preview/health")&&method.equals("GET")){code=200;body=mapper.writeValueAsBytes(Map.of("status","READY","pid",ProcessHandle.current().pid(),"forbiddenRuntimeCalls",forbidden.get()));}
                else if((path.equals("/")||path.equals("/preview.js"))&&method.equals("GET")) {
                    code=200;body=Files.readAllBytes(staticRoot.resolve(path.equals("/")?"index.html":"preview.js"));type=path.equals("/")?"text/html; charset=utf-8":"application/javascript";
                } else {
                    String name=exchange.getRequestHeaders().getFirst("X-BP7-Preview-Case");MockMvc route=routes.get(name);
                    String analysis=analyses.get(name);
                    if(route!=null&&path.startsWith("/variant-analyses/"+analysis+"/acmg/runs")) {
                        if(method.equals("GET")||(method.equals("POST")&&path.endsWith("/splicing-checks"))) {
                            var request=method.equals("GET")?get(Objects.requireNonNull(exchange.getRequestURI())):post(Objects.requireNonNull(exchange.getRequestURI()));
                            var response=route.perform(request).andReturn().getResponse();code=response.getStatus();body=response.getContentAsByteArray();
                        } else code=405;
                    }
                }
            } catch(Exception failure){code=500;body=mapper.writeValueAsBytes(Map.of("status","UNAVAILABLE","reason",failure.getClass().getSimpleName()));}
            Files.writeString(output.resolve("http-events.jsonl"),mapper.writeValueAsString(Map.of("method",exchange.getRequestMethod(),"path",exchange.getRequestURI().getPath(),"status",code,"runtimeCalls",forbidden.get()))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            exchange.getResponseHeaders().set("Content-Type",type);exchange.sendResponseHeaders(code,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        try {
            server.start();Files.writeString(output.resolve("service.json"),mapper.writeValueAsString(Map.of("pid",ProcessHandle.current().pid(),"port",Integer.parseInt(args[1]),"purpose","BP7 branch isolated saved-result and experiment-check manual acceptance","expiresAfterHours",24)));
            System.out.println("BP7_CHECK_PREVIEW_READY http://127.0.0.1:"+args[1]+"/");
            stop.await(24,TimeUnit.HOURS);
        } finally {
            server.stop(0);executor.shutdownNow();for(var ds:connections)new JdbcTemplate(Objects.requireNonNull(ds)).execute("SHUTDOWN");
            Files.writeString(output.resolve("final-calls.json"),mapper.writeValueAsString(Map.of("runtimeCalls",forbidden.get())));
        }
    }
}
