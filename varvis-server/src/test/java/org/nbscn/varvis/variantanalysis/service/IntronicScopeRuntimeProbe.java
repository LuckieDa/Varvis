package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.nbscn.varvis.variantanalysis.provider.ExternalProcessRunner;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Each attempt owns its output and diagnostic record; a previous READY file is never a fallback. */
final class IntronicScopeRuntimeProbe {
    static ObjectNode run(ObjectMapper mapper,List<String> configured,String reference,Path work,ExternalProcessRunner runner) throws Exception {
        String id=UUID.randomUUID().toString();
        Path output=work.resolve("splice-readiness-"+id+".json"),record=work.resolve("runtime-probe-"+id+".json");
        var command=new ArrayList<>(configured);
        command.addAll(List.of("--readiness-only","--reference-fasta",reference,"--output",output.toString().replace('\\','/')));
        var report=mapper.createObjectNode().put("status","BLOCKED").put("startedAt",Instant.now().toString())
                .put("workingDirectory",work.toString()).put("outputFile",output.toString()).put("recordFile",record.toString()).put("timeoutSeconds",120);
        report.set("command",mapper.valueToTree(command));
        Files.writeString(record,report.toPrettyString(),StandardOpenOption.CREATE_NEW);
        long start=System.nanoTime();
        try {
            if(Files.exists(output))throw new IllegalStateException("READINESS_OUTPUT_ALREADY_EXISTS");
            var process=runner.run(command,work,Duration.ofSeconds(120));
            report.set("process",mapper.valueToTree(process));
            if(!process.isSuccess())throw new IllegalStateException("READINESS_PROCESS_FAILED");
            if(!Files.isRegularFile(output)||Files.size(output)>4*1024*1024)throw new IllegalStateException("FRESH_READINESS_OUTPUT_MISSING_OR_INVALID");
            var result=mapper.readTree(Files.readString(output));
            if(!result.isObject()||!result.path("installedAssetHashesVerified").asBoolean())throw new IllegalStateException("READINESS_IDENTITY_NOT_VERIFIED");
            report.set("result",result);
            report.put("outputSha256",sha(output)).put("status","READY");
        } catch(Exception failure) {report.put("failure",failure.toString());
        } finally {
            report.put("elapsedSeconds",(System.nanoTime()-start)/1_000_000_000.0);
            Files.writeString(record,report.toPrettyString());
        }
        return report;
    }
    static String sha(Path path) throws Exception {
        if(Files.size(path)>4*1024*1024)throw new IllegalArgumentException("Only bounded evidence artifacts may be hashed here");
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
