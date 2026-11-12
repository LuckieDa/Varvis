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

/** Independent normal typed-completion control, separate from functional-rescue evidence. */
@EnabledIfSystemProperty(named="varvis.t07.generic-na.existing-parents",matches=".+")
class T07ProteinUnchangedNormalBusinessTest {
    @Test void neutralUnchangedProteinCompletesPvs1WithoutFunctionalReviewAndReopensReadOnly()throws Exception {
        Path output=Files.createDirectories(Path.of(required("varvis.t07.generic-na.output"))),state=Files.createDirectories(Path.of(required("varvis.t07.generic-na.state")));
        String parents=Path.of(required("varvis.t07.generic-na.existing-parents")).toRealPath().toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        run(classpath,T07ProteinUnchangedBusinessWriter.class.getName(),List.of(state.toString(),output.toString(),required("varvis.pp1.python"),parents,required("varvis.t07.generic-na.parent-source-pins")),output.resolve("writer.log"));
        run(classpath,T07FullRnaBusinessReader.class.getName(),List.of(state.toString(),output.toString()),output.resolve("reader.log"));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();var receipt=mapper.readTree(Files.readAllBytes(output.resolve("reader-receipt.json")));
        assertTrue(receipt.path("passed").asBoolean());assertTrue(receipt.path("writerExitedBeforeReader").asBoolean());assertEquals(4,receipt.path("runs").size());
        assertEquals(0,receipt.path("forbiddenScientificFactoryCalls").asInt(-1));
        var generic=mapper.readTree(Files.readAllBytes(output.resolve("generic-parent-receipt.json")));assertTrue(generic.path("existingParentFieldsUnchanged").asBoolean());
        assertTrue(generic.path("existingOriginalBytesUnchanged").asBoolean());assertFalse(generic.path("newFunctionalReviewDocumentsCreated").asBoolean(true));
        assertFalse(generic.path("functionalPreservationClaimed").asBoolean(true));
    }
    private static String required(String key){return Objects.requireNonNull(System.getProperty(key),"Explicit generic NA input required: "+key);}
    private static void run(String classpath,String entry,List<String> args,Path log)throws Exception {
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin/java.exe").toString(),"-Xmx768m","-Xshare:off","-Dfile.encoding=UTF-8","-Dstdout.encoding=UTF-8","-Dstderr.encoding=UTF-8","-ea","-cp",classpath,entry));command.addAll(args);
        var process=new ProcessBuilder(command).directory(Path.of("").toAbsolutePath().toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(240,TimeUnit.SECONDS),"Child timeout: "+log);assertEquals(0,process.exitValue(),()->"Child failed: "+entry+"\n"+read(log));assertFalse(process.isAlive());}
        finally{if(process.isAlive()){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly();}}
    }
    private static String read(Path p){try{return new String(Files.readAllBytes(p),java.nio.charset.StandardCharsets.UTF_8);}catch(java.io.IOException failure){return failure.toString();}}
}
