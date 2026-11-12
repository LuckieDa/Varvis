package org.nbscn.varvis.variantanalysis.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named="varvis.t04.guidance-xml",matches=".+")
@EnabledIfSystemProperty(named="varvis.pp1.python",matches=".+")
class T04ServiceHistoryTest {
    @TempDir(cleanup=org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;
    @Test void exitedWriterThenFreshReadOnlyServiceAndControllerPreserveSingleRelatedAndEighteen() throws Exception { verify(false); }
    @Test void nativeStandardOriginalSurvivesFreshReadOnlyServiceAndController() throws Exception { verify(true); }
    @Test @EnabledIfSystemProperty(named="varvis.t04.old-native-history-directory",matches=".+")
    void retainedOlderNativeV1ArchiveIsReadableWithoutCurrentProjectionOrQualificationReissue() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String property:List.of("varvis.t04.old-native-history-directory","varvis.t04.old-legacy-history-directory",
                "varvis.t04.old-native-integer-history-directory","varvis.t04.old-native-decimal-history-directory")) {
            String value=System.getProperty(property,"");if(value.isBlank()) continue;Path directory=Path.of(value);
            assertTrue(Files.isRegularFile(directory.resolve("metadata.json")));Path log=temporary.resolve(property+".log");
            run(List.of(java,"-cp",cp,T04ServiceHistoryProcess.class.getName(),"read",directory.toString()),log);
            assertTrue(Files.readString(log).contains("runtimeCalls=0"));
        }
    }
    @Test void differentNativeSourceIdsSurviveActualWriterExitAndFreshReadOnlyService() throws Exception { verify("native-alias"); }
    @Test void preciseDecimalNativeRecordAndUnrecordedRequiredDoseSurviveActualWriterExit() throws Exception { verify("native-decimal"); }
    @Test void partialSupplementalNativeFieldKeepsQualityAndIndependentQualificationAfterWriterExit() throws Exception { verify("native-partial"); }
    private void verify(boolean nativeStandard) throws Exception { verify(nativeStandard?"native":"legacy"); }
    private void verify(String nativeMode) throws Exception {
        String retained=System.getProperty("varvis.t04.service-history-output","");
        Path directory=retained.isBlank()?temporary:Files.createTempDirectory(Files.createDirectories(Path.of(retained)),"case-");
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        String cp=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var writer=new ArrayList<String>(List.of(java,"-Djava.io.tmpdir="+System.getProperty("java.io.tmpdir"),
                "-Dvarvis.t04.guidance-xml="+System.getProperty("varvis.t04.guidance-xml"),"-cp",cp,T04ServiceHistoryProcess.class.getName(),
                "write",directory.toString(),System.getProperty("varvis.pp1.python")));
        if(!nativeMode.equals("legacy")) writer.add(nativeMode);
        run(writer,directory.resolve("writer.log"));
        assertTrue(Files.isRegularFile(directory.resolve("metadata.json")));
        run(List.of(java,"-cp",cp,T04ServiceHistoryProcess.class.getName(),"read",directory.toString()),directory.resolve("reader.log"));
        assertTrue(Files.readString(directory.resolve("reader.log")).contains("runtimeCalls=0"));
        System.out.println("T04_ACTUAL_SERVICE_HISTORY_EVIDENCE="+directory);
    }
    private static void run(List<String> command,Path log) throws Exception {
        var process=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
            while(!process.waitFor(10,TimeUnit.SECONDS)) assertTrue(System.nanoTime()<deadline,"Owned subprocess timeout: "+log);
            assertEquals(0,process.exitValue(),()->{ try { return Files.readString(log); } catch(Exception e) { return e.toString(); } });
            assertFalse(process.isAlive());
        } finally {
            if(process.isAlive()) { process.descendants().forEach(child->child.destroyForcibly());process.destroyForcibly();assertTrue(process.waitFor(10,TimeUnit.SECONDS)); }
        }
    }
}
