package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import htsjdk.samtools.util.BlockCompressedOutputStream;
import htsjdk.tribble.index.IndexFactory;
import htsjdk.tribble.index.tabix.TabixFormat;
import htsjdk.variant.vcf.VCFCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GnomadLocalPopulationReaderTest {
    @TempDir Path temp;
    static final String ROW = "chr1\t100\tcontrol\tA\tG,T\t60\tPASS\tAC=1,2;AN=2000;AF=0.0005,0.001;nhomalt=0,1";

    @Test void readsCompletePopulationRecordAndExactAltWithoutExecutable() throws Exception {
        var settings = settings(fixture(ROW));
        var provider = Ba1LocalExomes.provider(settings);
        var key = key(100,"T");
        var item = provider.annotate(List.of(variant(key)), context()).get(key).get(0);
        assertEquals("APPLICABLE", item.getApplicability());
        assertEquals(ROW,item.getDetails().get("rawPopulationRecord"));
        assertEquals(1,item.getDetails().get("matchedAlternateIndex"));
        assertEquals(2,item.getDetails().get("recordAlternateCount"));
        assertEquals("HTSJDK_LOCAL_POPULATION_V1",provider.transportIdentity().get("version"));
        assertEquals("SUCCEEDED",provider.queryDiagnostics().get(0).get("status"));
        assertEquals(0,provider.queryDiagnostics().get(0).get("processLaunches"));
        assertTrue(item.getDetails().containsKey("localPopulationReadReceipt"));
        var measurements=new ObjectMapper().valueToTree(item.getDetails().get("populationMeasurements"));
        assertTrue(measurements.toString().contains("gnomad.exomes.ac"));
        assertFalse(new ObjectMapper().writeValueAsString(provider.queryDiagnostics()).contains(temp.toString().replace("\\","\\\\")));
    }

    @Test void defaultSettingDoesNotChangeSerializedConfigurationAndEnabledSettingDoes() {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var settings=new VariantAnalysisProperties();
        var before=mapper.valueToTree(settings.getGnomad());
        assertFalse(before.has("localPopulationInProcessEnabled"));
        settings.getGnomad().setLocalPopulationInProcessEnabled(true);
        assertTrue(mapper.valueToTree(settings.getGnomad()).path("localPopulationInProcessEnabled").asBoolean());
        assertNotEquals(before,mapper.valueToTree(settings.getGnomad()));
    }

    @Test void exactAlternateMissIsExplicitNoRecordAndFilteredRawIsPreserved() throws Exception {
        var provider=Ba1LocalExomes.provider(settings(fixture(ROW.replace("PASS","q10"))));
        var miss=key(100,"C");var hit=key(100,"G");
        var results=provider.annotate(List.of(variant(miss),variant(hit)),context());
        assertEquals("NO_RECORD",results.get(miss).get(0).getApplicability());
        assertEquals("NO_RECORD",results.get(miss).get(0).getDetails().get("queryOutcomeStatus"));
        assertNull(results.get(miss).get(0).getRawScore());
        assertTrue(results.get(miss).get(0).getDetails().containsKey("localPopulationReadReceipt"));
        assertEquals(ROW.replace("PASS","q10"),results.get(hit).get(0).getDetails().get("rawPopulationRecord"));
        assertNotEquals("PASS",results.get(hit).get(0).getDetails().get("populationInputQualityStatus"));
    }

    @Test void corruptIndexIsFailureWithoutFallbackOrNoRecord() throws Exception {
        var file=fixture(ROW);Files.write(Path.of(file+".tbi"),new byte[]{1,2,3});
        var provider=Ba1LocalExomes.provider(settings(file));
        assertFailed(provider,"FAILED");
        assertEquals(0,provider.queryDiagnostics().get(0).get("processLaunches"));
        Files.delete(Path.of(file+".tbi"));Files.delete(file); // Windows also verifies no leaked handles.
    }

    @Test void incompleteBgzfCannotPublishAnyRecord() throws Exception {
        var file=fixture(ROW);var bytes=Files.readAllBytes(file);
        Files.write(file,Arrays.copyOf(bytes,bytes.length-28));
        var provider=Ba1LocalExomes.provider(settings(file));
        assertFailed(provider,"FAILED");
        assertEquals("GNOMAD_LOCAL_BGZF_INCOMPLETE",provider.queryDiagnostics().get(0).get("reasonCode"));
    }

    @Test void duplicateExactRowsRejectWholeBatch() throws Exception {
        var provider=Ba1LocalExomes.provider(settings(fixture(ROW,ROW)));
        assertFailed(provider,"FAILED");
        assertEquals("GNOMAD_LOCAL_DUPLICATE_EXACT_RECORD",provider.queryDiagnostics().get(0).get("reasonCode"));
    }

    @Test void interruptedReadPreservesInterruptAndDoesNotCacheFailure() throws Exception {
        var provider=Ba1LocalExomes.provider(settings(fixture(ROW)));
        Thread.currentThread().interrupt();
        try {
            assertFailed(provider,"INTERRUPTED");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
        var key=key(100,"G");
        assertEquals("APPLICABLE",provider.annotate(List.of(variant(key)),context()).get(key).get(0).getApplicability());
        assertEquals(2,provider.queryDiagnostics().size());
    }

    @Test void configuredDeadlineIsEnforcedWithoutAProcess() throws Exception {
        var settings=settings(fixture(ROW));settings.getGnomad().setTimeout(Duration.ofNanos(1));
        var provider=Ba1LocalExomes.provider(settings);
        assertFailed(provider,"TIMEOUT");
        assertEquals("GNOMAD_LOCAL_TIMEOUT",provider.queryDiagnostics().get(0).get("reasonCode"));
    }

    @Test void successfulCacheBindsIndexMetadataAndModeBindsSourceIdentity() throws Exception {
        var file=fixture(ROW);var settings=settings(file);var mapper=new ObjectMapper();
        var enabled=Ba1LocalExomes.identity(mapper,settings);
        settings.getGnomad().setLocalPopulationInProcessEnabled(false);
        var legacy=Ba1LocalExomes.identity(mapper,settings);
        assertFalse(legacy.has("localPopulationReader"));assertNotEquals(legacy,enabled);
        settings.getGnomad().setLocalPopulationInProcessEnabled(true);
        var method=org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig.class
                .getDeclaredMethod("localBa1Settings",VariantAnalysisProperties.class);
        method.setAccessible(true);
        assertTrue(((VariantAnalysisProperties)method.invoke(null,settings)).getGnomad().isLocalPopulationInProcessEnabled());
        var provider=Ba1LocalExomes.provider(settings);var key=key(100,"G");
        provider.annotate(List.of(variant(key)),context());provider.annotate(List.of(variant(key)),context());
        assertEquals(1,provider.queryDiagnostics().size());
        var index=Path.of(file+".tbi");Files.setLastModifiedTime(index,
                java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(index).toMillis()+2000));
        provider.annotate(List.of(variant(key)),context());
        assertEquals(2,provider.queryDiagnostics().size());
        assertNotEquals(provider.queryDiagnostics().get(0).get("resourceIdentitySha256"),
                provider.queryDiagnostics().get(1).get("resourceIdentitySha256"));
    }

    @Test void explicitRemoteProfileAndNonPopulationConfigurationAreRejected() throws Exception {
        var settings=settings(fixture(ROW));
        assertThrows(IllegalArgumentException.class,()->new GnomadTabixProvider(settings,GnomadPopulationInputProfile.loadBusinessPm2Supporting()));
        settings.getGnomad().setPopulationInputEnabled(false);
        assertThrows(IllegalArgumentException.class,()->new GnomadTabixProvider(settings));
    }

    @Test void repeatedCandidateKeysAreOneQueryAndDoNotLookLikeDuplicateRecords() throws Exception {
        var provider=Ba1LocalExomes.provider(settings(fixture(ROW)));var key=key(100,"G");
        var result=provider.annotate(List.of(variant(key),variant(key)),context());
        assertEquals("APPLICABLE",result.get(key).get(0).getApplicability());
        assertEquals(1,provider.queryDiagnostics().get(0).get("locusCount"));
        assertEquals(GnomadQueryRunner.queryIdentity("GRCh38:1:100:A:G"),provider.queryDiagnostics().get(0).get("querySha256"));
    }

    @Test void multipleLocalDatasetsForSameChromosomeNeedExplicitSelection() throws Exception {
        var file=fixture(ROW);var settings=settings(file);settings.getGnomad().setGrch38Files(file+","+file);
        assertEquals("GNOMAD_LOCAL_MULTIPLE_RESOURCES_PER_CHROMOSOME",assertThrows(IllegalArgumentException.class,
                ()->Ba1LocalExomes.provider(settings)).getMessage());
        settings.getGnomad().setGrch38Files(file+","+temp.resolve("unassigned.vcf.bgz"));
        assertEquals("GNOMAD_LOCAL_MULTIPLE_RESOURCES_PER_CHROMOSOME",assertThrows(IllegalArgumentException.class,
                ()->Ba1LocalExomes.provider(settings)).getMessage());
    }

    @Test void outputRecordLimitRejectsPartialResults() throws Exception {
        var rows=new String[25001];
        for(int i=0;i<rows.length;i++) rows[i]=ROW.replace("\t100\t","\t"+(100+i)+"\t");
        var settings=settings(fixture(rows));settings.getGnomad().setQueryWindowBp(30000);settings.getGnomad().setQueryMergeGapBp(30000);
        var provider=new GnomadTabixProvider(settings,GnomadPopulationInputProfile.loadLocalBa1(),false,true);
        var first=key(100,"G");var last=key(25100,"G");
        var results=provider.annotate(List.of(variant(first),variant(last)),context());
        assertEquals("UNAVAILABLE",results.get(first).get(0).getApplicability());
        assertEquals("UNAVAILABLE",results.get(last).get(0).getApplicability());
        assertEquals("GNOMAD_LOCAL_OUTPUT_LIMIT",provider.queryDiagnostics().get(0).get("reasonCode"));
    }

    void assertFailed(GnomadTabixProvider provider,String diagnosticStatus) {
        var key=key(100,"G");var item=provider.annotate(List.of(variant(key)),context()).get(key).get(0);
        assertEquals("UNAVAILABLE",item.getApplicability());
        assertEquals("NOT_QUERIED",item.getDetails().get("queryOutcomeStatus"));
        assertFalse(item.getDetails().containsKey("rawPopulationRecord"));
        assertEquals(diagnosticStatus,provider.queryDiagnostics().get(0).get("status"));
        assertEquals(false,provider.queryDiagnostics().get(0).get("complete"));
    }

    Path fixture(String... rows) throws Exception {
        Path file=temp.resolve("gnomad.exomes.v4.1.sites.chr1.vcf.bgz");
        String header="##fileformat=VCFv4.2\n##contig=<ID=chr1,length=1000000>\n"
                +"##FILTER=<ID=q10,Description=\"Synthetic low quality\">\n"
                +"##INFO=<ID=AC,Number=A,Type=Integer,Description=\"Synthetic\">\n"
                +"##INFO=<ID=AN,Number=1,Type=Integer,Description=\"Synthetic\">\n"
                +"##INFO=<ID=AF,Number=A,Type=Float,Description=\"Synthetic\">\n"
                +"##INFO=<ID=nhomalt,Number=A,Type=Integer,Description=\"Synthetic\">\n"
                +"#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n";
        try(var stream=new BlockCompressedOutputStream(file.toFile())) {
            stream.write((header+String.join("\n",rows)+"\n").getBytes(StandardCharsets.UTF_8));
        }
        IndexFactory.createTabixIndex(file.toFile(),new VCFCodec(),TabixFormat.VCF,null).writeBasedOnFeatureFile(file.toFile());
        return file;
    }
    VariantAnalysisProperties settings(Path file) {
        var result=new VariantAnalysisProperties();var g=result.getGnomad();
        g.setEnabled(true);g.setPopulationInputEnabled(true);g.setLocalPopulationInProcessEnabled(true);
        g.setGrch38Files(file.toString());g.setTabixExecutable(temp.resolve("no-executable-present.exe").toString());
        g.setTimeout(Duration.ofSeconds(5));g.setParallelism(1);return result;
    }
    NormalizedVariantKey key(int pos,String alt) {return new NormalizedVariantKey("GRCh38","1",pos,"A",alt);}
    VariantInterpretation variant(NormalizedVariantKey key) {
        return new VariantInterpretation("synthetic-"+key.getPosition()+"-"+key.getAlternate(),
                new VariantQcObservation(key,"synthetic","0/1",30,99,"18,12",0.4,"PASS","SNV","synthetic"),List.of(),List.of());
    }
    AnalysisContext context() {return new AnalysisContext("synthetic-local-reader",GenomeAssembly.GRCh38,AnalysisMode.WES);}
}
