package org.nbscn.varvis.functionalacceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.config.VarvisDataSourceConfig;
import org.nbscn.varvis.variantanalysis.provider.Hnf1aFunctionalRuntime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Loopback-only page acceptance of the completed, isolated public functional smoke database. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages="org.nbscn.varvis.variantanalysis",excludeFilters={
        @ComponentScan.Filter(type=FilterType.REGEX,pattern=".*Test.*"),
        @ComponentScan.Filter(type=FilterType.REGEX,pattern=".*VariantAnalysisCleanupService")})
@Import(VarvisDataSourceConfig.class)
public class FunctionalNetworkApplication {
    public static void main(String[] args) throws Exception {
        if(args.length!=3)throw new IllegalArgumentException("Expected completed smoke root, resource root and free port");
        Path allowed=Path.of("target/hnf1a-functional-20260910").toRealPath(),root=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Objects.equals(root.toRealPath().getParent(),allowed)||!root.getFileName().toString().matches("backend-[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Use this worktree's completed functional smoke directory");
        var mapper=new ObjectMapper();Path proofPath=root.resolve("result.json");
        if(Files.isRegularFile(root.resolve("final-verification.json"))) {
            String folder=mapper.readTree(Files.readAllBytes(root.resolve("final-verification.json"))).path("directory").asText();
            if(!folder.matches("verification-[a-f0-9-]{36}"))throw new IllegalArgumentException("Invalid verification directory");
            proofPath=root.resolve(folder).resolve("result.json");
        }
        var proof=mapper.readTree(Files.readAllBytes(proofPath));
        if(!"REAL_PIPELINE_AND_SAVED_BUSINESS_VERIFIED".equals(proof.path("status").asText())||!Files.isRegularFile(root.resolve("business-db.mv.db")))
            throw new IllegalArgumentException("Completed isolated file-H2 proof required");
        int port=Integer.parseInt(args[2]);if(port<1024||port>65535)throw new IllegalArgumentException("Invalid isolated port");
        try(var probe=new ServerSocket(port,1,InetAddress.getByName("127.0.0.1"))) {if(!probe.isBound())throw new IllegalArgumentException("Port unavailable");}
        var configured=new Properties();try(var input=Files.newInputStream(Path.of("config/variant-analysis-local.properties"))){configured.load(input);}
        var settings=new LinkedHashMap<String,Object>();configured.forEach((key,value)->{if(key.toString().startsWith("variant-analysis."))settings.put(key.toString(),value.toString());});
        settings.put("VARVIS_RESOURCE_ROOT",Path.of(args[1]).toRealPath().toString());
        settings.put("server.address","127.0.0.1");settings.put("server.port",port);
        // Match the saved source's mapper contract in AcmgBusinessIntegrationTest.
        // Changing deserialization here changes restored domain values and correctly blocks source recovery.
        settings.put("spring.jackson.serialization.INDENT_OUTPUT",true);
        settings.put("spring.jackson.serialization.WRITE_DATES_AS_TIMESTAMPS",true);
        settings.put("spring.jackson.deserialization.USE_BIG_DECIMAL_FOR_FLOATS",true);
        String jdbc="jdbc:h2:file:"+root.resolve("business-db").toString().replace('\\','/')+";MODE=MySQL;DB_CLOSE_ON_EXIT=FALSE;IFEXISTS=TRUE";
        settings.put("varvis.datasource.enabled",true);settings.put("varvis.datasource.url",jdbc);settings.put("varvis.datasource.jdbc-url",jdbc);
        settings.put("varvis.datasource.username","sa");settings.put("varvis.datasource.password","");settings.put("varvis.datasource.driver-class-name","org.h2.Driver");
        settings.put("varvis.datasource.maximum-pool-size",4);settings.put("varvis.datasource.minimum-idle",1);
        settings.put("variant-analysis.persistence.table-prefix","");settings.put("variant-analysis.persistence.legacy-schema-migration-enabled",false);
        settings.put("variant-analysis.storage-root",root.resolve("jobs").toString());
        for(String suffix:List.of("ba1-enabled","pm2-enabled","pvs1-enabled","ps1-pm5-enabled"))settings.put("variant-analysis.acmg-business."+suffix,false);
        settings.put("variant-analysis.acmg-business.enabled",true);settings.put("variant-analysis.acmg-business.ps3-enabled",true);
        settings.put("varvis.benchmark.real-case.enabled",false);settings.put("spring.devtools.restart.enabled",false);
        var process=Files.createDirectory(root.resolve("page-process-"+UUID.randomUUID()));
        // The configured VEP path participates in execution identity; preserve it across restarts.
        Path temp=Files.createDirectories(root.resolve("network-runtime-temp"));System.setProperty("java.io.tmpdir",temp.toString());
        settings.put("spring.servlet.multipart.location",temp.toString());settings.put("variant-analysis.vep.temp-dir",temp.toString());
        settings.put("logging.file.name",process.resolve("application.log").toString());
        Path bootstrap=process.resolve("isolated-bootstrap.properties");Files.writeString(bootstrap,"# Own isolated H2 page acceptance; no default application imports.\n");
        Files.writeString(process.resolve("identity.json"),mapper.createObjectNode().put("processId",ProcessHandle.current().pid()).put("port",port)
                .put("transport","REAL_NETWORK_HTTP").put("profileSha256",Hnf1aFunctionalRuntime.PROFILE_SHA256).put("analysisId",proof.path("analysisId").asText())
                .put("dataClass","SYNTHETIC").put("automaticTrigger",false).put("classificationEnabled",false).toPrettyString());
        var app=new SpringApplication(FunctionalNetworkApplication.class);
        app.addInitializers(context->context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-functional-settings",settings)));
        var application=app.run("--spring.config.location="+bootstrap.toUri());
        var actualMapper=application.getBean(ObjectMapper.class);
        var jobs=application.getBean(org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository.class);
        var store=application.getBean(org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore.class);
        String analysisId=proof.path("analysisId").asText();
        Files.writeString(process.resolve("restored-candidates.json"),actualMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jobs.findInterpretations(analysisId,0,20)));
        Files.writeString(process.resolve("execution-source.json"),store.source(analysisId).orElseThrow().json());
    }
}
