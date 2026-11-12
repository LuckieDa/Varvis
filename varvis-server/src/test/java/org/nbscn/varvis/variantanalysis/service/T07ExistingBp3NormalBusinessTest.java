package org.nbscn.varvis.variantanalysis.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in replay of already saved, hash-pinned synthetic review bytes. No source is minted here. */
@EnabledIfSystemProperty(named="varvis.t07.bp3.existing-source",matches=".+")
class T07ExistingBp3NormalBusinessTest {
    @Test void unchangedExistingSourcesPassNormalBp3AndConflictAndReopenAfterWriterExit()throws Exception {
        Path output=Files.createDirectories(Path.of(required("varvis.t07.bp3.output"))),state=Files.createDirectories(Path.of(required("varvis.t07.bp3.state")));
        Path source=Path.of(required("varvis.t07.bp3.existing-source")).toRealPath();String pin=required("varvis.t07.bp3.existing-sha256");
        Path originals=Path.of(required("varvis.t07.bp3.existing-rna-originals")).toRealPath();String python=required("varvis.pp1.python");
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        run(classpath,T07ExistingBp3BusinessWriter.class.getName(),List.of(state.toString(),output.toString(),python,source.toString(),pin,originals.toString()),output.resolve("writer.log"));
        run(classpath,T07FullRnaBusinessReader.class.getName(),List.of(state.toString(),output.toString()),output.resolve("reader.log"));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var receipt=mapper.readTree(Files.readAllBytes(output.resolve("reader-receipt.json")));
        assertTrue(receipt.path("passed").asBoolean());assertTrue(receipt.path("writerExitedBeforeReader").asBoolean());assertEquals(3,receipt.path("runs").size());
        var originalReceipt=mapper.readTree(Files.readAllBytes(output.resolve("existing-source-receipt.json")));assertTrue(originalReceipt.path("bp3OriginalBytesUnchanged").asBoolean());
        assertTrue(originalReceipt.path("pvs1OriginalBytesUnchanged").asBoolean());assertFalse(originalReceipt.path("newFunctionalQualificationsCreated").asBoolean(true));
    }
    private static String required(String key){return Objects.requireNonNull(System.getProperty(key),"Explicit existing input required: "+key);}
    private static void run(String classpath,String entry,List<String> args,Path log)throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xmx768m","-Xshare:off","-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-ea","-cp",classpath,entry));command.addAll(args);
        var process=new ProcessBuilder(command).directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {assertTrue(process.waitFor(240,TimeUnit.SECONDS),"Child timeout: "+log);assertEquals(0,process.exitValue(),()->"Child failed: "+entry+"\n"+read(log));assertFalse(process.isAlive());}
        finally {if(process.isAlive()){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}}
    }
    private static String read(Path p){try{return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException error){return error.toString();}}
}
