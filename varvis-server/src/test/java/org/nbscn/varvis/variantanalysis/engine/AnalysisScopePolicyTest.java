package org.nbscn.varvis.variantanalysis.engine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;
import static org.junit.jupiter.api.Assertions.*;
class AnalysisScopePolicyTest {
    @TempDir Path work;
    VariantAnalysisProperties.Exomiser settings() throws Exception {
        var s = new VariantAnalysisProperties().getExomiser();
        Path p=work.resolve("template.yml"); Files.copy(Path.of(getClass().getResource("/exomiser/scope-test.yml").toURI()),p);
        s.setAnalysisTemplate(p.toString()); return s;
    }
    VariantAnalysisJob job() throws Exception {
        Path vcf=work.resolve("input.vcf");
        Files.writeString(vcf,"##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n1\t100\t.\tA\tG\t.\tPASS\t.\tGT\t1/1\n");
        return new VariantAnalysisJob("scope-job","input.vcf","a".repeat(64),GenomeAssembly.GRCh38,AnalysisMode.WES,"proband",work,vcf,PhenotypeAwareSummary.disabled());
    }
    @Test @SuppressWarnings("unchecked") void onlyTargetFilterChangesAndHpoModesRemain() throws Exception {
        var s=settings();
        for(boolean hpo: List.of(false,true)) {
            var original=AnalysisScopePolicy.resolve(s,AnalysisScopePolicy.Scope.STANDARD,hpo);
            assertEquals(ExomiserRequestWriter.analysisYaml(s.getAnalysisTemplate(),hpo),original.effectiveYaml());
            var introns=AnalysisScopePolicy.resolve(s,AnalysisScopePolicy.Scope.INCLUDE_INTRONS,hpo);
            var yaml=new Yaml(); Map<String,Object> expected=yaml.load(original.effectiveYaml());
            var steps=(List<Map<String,Object>>)expected.get("steps");
            var remove=(List<String>)((Map<String,Object>)steps.get(2).get("variantEffectFilter")).get("remove");
            remove.remove("CODING_TRANSCRIPT_INTRON_VARIANT");
            assertEquals(expected,yaml.load(introns.effectiveYaml()));
            assertEquals(hpo,introns.effectiveYaml().contains("hiPhivePrioritiser"));
            assertTrue(introns.effectiveYaml().contains("keepNonPathogenic: true"));
        }
    }
    @Test void missingOrLowPrecomputedScoreDoesNotGateNonCodingCandidates() throws Exception {
        var s=settings();Path p=Path.of(s.getAnalysisTemplate());Files.writeString(p,Files.readString(p).replace("keepNonPathogenic: true", "keepNonPathogenic: false, target: NON_CODING"));
        var introns=AnalysisScopePolicy.resolve(s,AnalysisScopePolicy.Scope.INCLUDE_INTRONS,false);
        assertTrue(introns.effectiveYaml().contains("keepNonPathogenic: true"));
        assertTrue(introns.effectiveYaml().contains("target: NON_CODING"));
        var review=AnalysisScopePolicy.resolve(s,AnalysisScopePolicy.Scope.SINGLE_VARIANT_REVIEW,false);
        assertTrue(review.effectiveYaml().contains("keepNonPathogenic: true"));
        assertTrue(review.effectiveYaml().contains("target: NON_CODING"));
    }
    @Test @SuppressWarnings("unchecked") void singleVariantReviewCombinesIntronScopeAndFrequencyCapsWithSeparateAttestation() throws Exception {
        var s = settings();
        var standard = AnalysisScopePolicy.resolve(s, AnalysisScopePolicy.Scope.STANDARD, true);
        var introns = AnalysisScopePolicy.resolve(s, AnalysisScopePolicy.Scope.INCLUDE_INTRONS, true);
        var review = AnalysisScopePolicy.resolve(s, AnalysisScopePolicy.Scope.SINGLE_VARIANT_REVIEW, true);
        var yaml = new Yaml();
        Map<String, Object> expected = yaml.load(introns.effectiveYaml());
        ((Map<String, Object>) expected.get("inheritanceModes")).replaceAll((mode, cap) -> 100.0);
        var steps = (List<Map<String, Object>>) expected.get("steps");
        ((Map<String, Object>) steps.get(3).get("frequencyFilter")).put("maxFrequency", 100.0);
        assertEquals(expected, yaml.load(review.effectiveYaml()));
        assertTrue(review.supplementsZeroPassed());
        assertEquals("VARVIS_SINGLE_VARIANT_REVIEW_V2", review.policyId());

        var job = job();
        AnalysisScopePolicy.save(job, review);
        assertFalse(AnalysisScopePolicy.reusable(job, review));
        assertEquals(review.effectiveYaml(), AnalysisScopePolicy.forExecution(job, s, true));
        assertTrue(AnalysisScopePolicy.reusable(job, review));
        assertFalse(AnalysisScopePolicy.reusable(job, standard));
        assertFalse(AnalysisScopePolicy.reusable(job, introns));
    }
    @Test void singleVariantReviewRequiresPassOnlyWithOneFrequencyFilter() throws Exception {
        var s = settings();
        var template = Path.of(s.getAnalysisTemplate());
        var original = Files.readString(template);
        for (var invalid : List.of(original.replace("PASS_ONLY", "FULL"),
                original.replace("frequencyFilter:", "someOtherFilter:"),
                original.replace("frequencyFilter: { maxFrequency: 2.0 },",
                        "frequencyFilter: { maxFrequency: 2.0 }, frequencyFilter: { maxFrequency: 1.0 },"))) {
            Files.writeString(template, invalid);
            var error = assertThrows(IllegalArgumentException.class,
                    () -> AnalysisScopePolicy.resolve(s, AnalysisScopePolicy.Scope.SINGLE_VARIANT_REVIEW, true));
            assertEquals("SINGLE_VARIANT_REVIEW_TEMPLATE_UNSUPPORTED", error.getMessage());
        }
    }
    @Test void actualWriterUsesSavedScopeAndReuseRequiresExecutedUnchangedPolicy() throws Exception {
        var s=settings();var job=job();var selected=AnalysisScopePolicy.resolve(s,AnalysisScopePolicy.Scope.INCLUDE_INTRONS,false);
        AnalysisScopePolicy.save(job,selected); assertFalse(AnalysisScopePolicy.reusable(job,selected));
        var artifacts=new ExomiserRequestWriter().write(job,s,work.resolve("execution"),job.getUploadedFile(),List.of(),TrioPipelineInput.absent());
        assertEquals(selected.effectiveYaml(),Files.readString(artifacts.getAnalysis()));
        assertTrue(AnalysisScopePolicy.reusable(job,selected));
        assertFalse(AnalysisScopePolicy.reusable(job,AnalysisScopePolicy.resolve(s,AnalysisScopePolicy.Scope.STANDARD,false)));
        Path p=Path.of(s.getAnalysisTemplate());Files.writeString(p,Files.readString(p).replace("maxFrequency: 2.0","maxFrequency: 1.0"));
        assertFalse(AnalysisScopePolicy.reusable(job,AnalysisScopePolicy.resolve(s,AnalysisScopePolicy.Scope.INCLUDE_INTRONS,false)));
        assertEquals(selected,AnalysisScopePolicy.read(job));
    }
    @Test void legacyAndCorruptSnapshotsNeverAttestReuse() throws Exception {
        var s=settings();var job=job();var policy=AnalysisScopePolicy.resolve(s,AnalysisScopePolicy.Scope.STANDARD,false);
        assertEquals("STANDARD",AnalysisScopePolicy.publicDetails(job).get("scope"));
        assertFalse(AnalysisScopePolicy.reusable(job,policy));
        AnalysisScopePolicy.save(job,policy);Path snapshot=work.resolve("analysis-scope-v1.json");
        Files.writeString(snapshot,Files.readString(snapshot).replace("scope-job","another-job"));
        assertThrows(IllegalArgumentException.class,()->AnalysisScopePolicy.read(job));
        assertEquals("UNKNOWN",AnalysisScopePolicy.publicDetails(job).get("scope"));
        assertFalse(AnalysisScopePolicy.reusable(job,policy));
    }
    @Test void scopeEnumIsStrict() {
        assertEquals(AnalysisScopePolicy.Scope.STANDARD,AnalysisScopePolicy.parse(null));
        for(String invalid:List.of("", "true", "1", "include_introns", "../preset.yml")) assertThrows(IllegalArgumentException.class,()->AnalysisScopePolicy.parse(invalid));
    }
    @Test void oldIntronsSnapshotRemainsReadableButNeverMatchesNewOutputCoverage() throws Exception {
        var s=settings();var job=job();var current=AnalysisScopePolicy.resolve(s,AnalysisScopePolicy.Scope.INCLUDE_INTRONS,false);
        var old=new AnalysisScopePolicy.Policy(AnalysisScopePolicy.Scope.INCLUDE_INTRONS,"VARVIS_INCLUDE_INTRONS_V1","a".repeat(64),current.effectiveYaml());
        AnalysisScopePolicy.save(job,old);AnalysisScopePolicy.forExecution(job,s,false);
        assertEquals(old,AnalysisScopePolicy.read(job));assertFalse(AnalysisScopePolicy.read(job).supplementsZeroPassed());
        assertFalse(AnalysisScopePolicy.reusable(job,current));assertTrue(AnalysisScopePolicy.reusable(job,old));
        assertEquals(old.effectiveYaml(),current.effectiveYaml());
    }
}
