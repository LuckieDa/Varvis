package org.nbscn.varvis.variantanalysis.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Parent only coordinates isolated processes; the Writer really exits before Reader begins. */
class T07FullRnaCurrentBusinessTest {
    @TempDir Path temporary;
    @Test void realCurrentFactorySavesAndReopensThroughNormalServiceInAnotherReadOnlyJvm() throws Exception {
        String archive=System.getProperty("varvis.t07.business.output");Path output=archive==null?temporary.resolve("output"):Path.of(archive);
        String state=System.getProperty("varvis.t07.business.state");Files.createDirectories(output);Path database=Files.createDirectories(state==null?temporary.resolve("writer"):Path.of(state));
        Path java=Path.of(System.getProperty("java.home"),"bin/java.exe");String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path python=Path.of(Objects.requireNonNull(System.getProperty("varvis.pp1.python"),"Existing Python must be explicit"));
        run(java,classpath,T07FullRnaBusinessWriter.class.getName(),List.of(database.toString(),output.toString(),python.toString()),output.resolve("writer.log"));
        run(java,classpath,T07FullRnaBusinessReader.class.getName(),List.of(database.toString(),output.toString()),output.resolve("reader.log"));
        var receipt=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(output.resolve("reader-receipt.json")));
        assertTrue(receipt.path("passed").asBoolean());assertEquals(12,receipt.path("runs").size());assertTrue(receipt.path("writerExitedBeforeReader").asBoolean());
        Path synonymOutput=Files.createDirectories(output.resolve("synonymous-terminal"));Path synonymState=Files.createDirectories(state==null?temporary.resolve("synonymous-writer"):Path.of(state+"-synonymous"));
        run(java,classpath,T07FullRnaBusinessWriter.class.getName(),List.of(synonymState.toString(),synonymOutput.toString(),python.toString(),"SYNONYMOUS_TERMINAL"),synonymOutput.resolve("writer.log"));
        run(java,classpath,T07FullRnaBusinessReader.class.getName(),List.of(synonymState.toString(),synonymOutput.toString()),synonymOutput.resolve("reader.log"));
        var synonymReceipt=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(synonymOutput.resolve("reader-receipt.json")));
        assertTrue(synonymReceipt.path("passed").asBoolean());assertEquals(2,synonymReceipt.path("runs").size());assertTrue(synonymReceipt.path("writerExitedBeforeReader").asBoolean());
    }
    private static void run(Path java,String classpath,String entry,List<String> args,Path log) throws Exception {
        var command=new java.util.ArrayList<>(List.of(java.toString(),"-Xmx768m","-Xshare:off","-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-ea","-cp",classpath,entry));command.addAll(args);
        var process=new ProcessBuilder(command).directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(240,TimeUnit.SECONDS),"T07 child process did not terminate; see "+log);
            assertEquals(0,process.exitValue(),()->"Child failed: "+entry+"\n"+read(log));assertFalse(process.isAlive());
        } finally { if(process.isAlive()) {process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();} }
    }
    private static String read(Path file){try{return new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException problem){return problem.toString();}}
}
