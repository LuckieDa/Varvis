package org.nbscn.varvis.variantanalysis.dto;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.service.PhenotypeInputNormalizer;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantAnalysisJobResponseTest {

    @Test
    void includesNoOpPhenotypeSummaryWhenPresentOnJob() {
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("hp:0001250")
        ).withGenePriorSummary(genePriorSummary());
        VariantAnalysisJob job = new VariantAnalysisJob(
                "analysis",
                "patient.vcf",
                "abc123",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "proband",
                Paths.get("workspace"),
                Paths.get("workspace", "patient.vcf"),
                summary
        );

        VariantAnalysisJobResponse response = VariantAnalysisJobResponse.from(job);

        assertEquals("VARVIS", response.getEngine());
        assertEquals("NOT_REQUESTED", response.getEngineStatus());
        assertEquals("VARVIS_DEFAULT", response.getRankingMode());
        assertEquals(1, response.getPhenotypeSummary().getValidHpoCount());
        assertEquals(Collections.singletonList("HP:0001250"), response.getPhenotypeSummary().getValidHpoTerms());
        assertFalse(response.getPhenotypeSummary().isRankingApplied());
        assertEquals(
                GenePhenotypePriorStatus.AVAILABLE,
                response.getPhenotypeSummary().getGenePriorSummary().getStatus()
        );
        assertEquals(
                "MECP2",
                response.getPhenotypeSummary().getGenePriorSummary().getTopGenePriors().get(0).getGeneSymbol()
        );
    }

    @Test
    void mapsReadyLargeFilePartialCountsToReadyFastProgressiveStatus() {
        VariantAnalysisJob job = new VariantAnalysisJob(
                "analysis",
                "public-large-fixture.vcf",
                "abc123",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "HG001",
                Paths.get("workspace"),
                Paths.get("workspace", "public-large-fixture.vcf")
        );
        job.complete(
                "HG001",
                true,
                "bcftools norm",
                null,
                Collections.emptyList(),
                Collections.emptyList()
        );
        Map<String, Integer> bucketCounts = new LinkedHashMap<String, Integer>();
        bucketCounts.put("CANDIDATE", 1);
        bucketCounts.put("FILTERED_NOT_CARRIED", 3);
        VariantInterpretationCounts counts = new VariantInterpretationCounts(
                1,
                0,
                4,
                bucketCounts,
                2,
                2,
                2,
                4,
                true,
                false
        );

        VariantAnalysisJobResponse response = VariantAnalysisJobResponse.from(job, counts);

        assertEquals("READY_FAST", response.getAnalysisStage());
        assertEquals("PARTIAL", response.getEnrichmentStatus());
        assertEquals(job.getUpdatedAt(), response.getReadyFastAt());
        assertNull(response.getReadyFullAt());
        assertTrue(response.getPartialEvidenceWarning().contains("not benign or low risk"));
        assertEquals(2, response.getProviderProgress().get("summaryOnlyCount"));
        assertEquals(4, response.getProviderProgress().get("largeFileModeCount"));
        assertEquals("SUCCEEDED", response.getTaskStatus());
    }

    @Test
    void mapsExomiserTerminalFailuresToStructuredProductStates() {
        VariantAnalysisJob job = new VariantAnalysisJob(
                "analysis", "input.vcf", "abc", GenomeAssembly.GRCh38, AnalysisMode.WES,
                "sample", Paths.get("workspace"), Paths.get("workspace", "input.vcf")
        );
        job.applyEngineMetadata(EngineMetadata.exomiserUnavailable(
                "EXOMISER_JAVA21_WINDOWS_UNAVAILABLE", "15.1.0", "fingerprint", "EXOMISER_G0"));
        job.fail("EXOMISER_JAVA21_WINDOWS_UNAVAILABLE");

        VariantAnalysisJobResponse response = VariantAnalysisJobResponse.from(job);

        assertEquals("EXOMISER", response.getEngine());
        assertEquals("ENGINE_UNAVAILABLE", response.getTaskStatus());
        assertEquals("ENGINE_UNAVAILABLE", response.getErrorCode());
        assertFalse(response.isEngineFallbackUsed());
    }

    @Test
    void preservesGrch37InputWhileDeclaringExomiserAnalysisBuild() {
        VariantAnalysisJob job = new VariantAnalysisJob(
                "analysis", "input.vcf", "abc", GenomeAssembly.GRCh37, AnalysisMode.WES,
                "sample", Paths.get("workspace"), Paths.get("workspace", "input.vcf")
        );
        job.complete("sample", true, "Picard LiftoverVcf (LIFTOVER_AUDIT;SOURCE=1;LIFTED=1;REJECTED=0) + EXOMISER", null,
                Collections.emptyList(), Collections.emptyList());
        job.applyEngineMetadata(EngineMetadata.exomiserRanked("15.1.0", "fingerprint", "output", "EXOMISER_G0"));

        VariantAnalysisJobResponse response = VariantAnalysisJobResponse.from(job);

        assertEquals("GRCh37", response.getGenomeAssembly());
        assertEquals("GRCh38", response.getAnalysisAssembly());
        assertTrue(response.isLiftoverApplied());
        assertEquals("COMPLETE", response.getLiftoverStatus());
        assertEquals(1L, response.getLiftoverSourceRecordCount());
        assertEquals(1L, response.getLiftoverLiftedRecordCount());
    }

    @Test
    void exposesPartialLiftoverAsAnAuditableResultWarning() {
        VariantAnalysisJob job = new VariantAnalysisJob(
                "analysis", "input.vcf", "abc", GenomeAssembly.GRCh37, AnalysisMode.WES,
                "sample", Paths.get("workspace"), Paths.get("workspace", "input.vcf")
        );
        job.complete("sample", true, "Picard LiftoverVcf (PARTIAL_LIFTOVER:12;SOURCE=100;LIFTED=88) + EXOMISER", null,
                Collections.emptyList(), Collections.singletonList("12 records were rejected"));
        job.applyEngineMetadata(EngineMetadata.exomiserRanked("15.1.0", "fingerprint", "output", "EXOMISER_G0"));

        VariantAnalysisJobResponse response = VariantAnalysisJobResponse.from(job);

        assertEquals("PARTIAL_LIFTOVER", response.getLiftoverStatus());
        assertEquals(100L, response.getLiftoverSourceRecordCount());
        assertEquals(88L, response.getLiftoverLiftedRecordCount());
        assertEquals(12, response.getLiftoverRejectedRecordCount());
    }

    private GenePhenotypePriorSummary genePriorSummary() {
        return new GenePhenotypePriorSummary(
                true,
                "LOCAL_GENE_PRIOR",
                "mock-v1",
                GenePhenotypePriorStatus.AVAILABLE,
                1,
                1,
                1,
                Collections.singletonList(new GenePhenotypePrior(
                        "MECP2",
                        "ENSG00000169057",
                        0.95d,
                        1,
                        "LOCAL_GENE_PRIOR",
                        "mock-v1",
                        Collections.singletonList("HP:0001250"),
                        Arrays.asList("HP:0001250: Mock seizure phenotype association"),
                        GenePhenotypePriorStatus.AVAILABLE,
                        Instant.parse("2026-01-01T00:00:00Z"),
                        Collections.<String>emptyList()
                )),
                Collections.singletonList("Gene phenotype prior is gene-level candidate support only."),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }
}
