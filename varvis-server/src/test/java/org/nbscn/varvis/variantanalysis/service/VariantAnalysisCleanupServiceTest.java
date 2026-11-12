package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("deprecation")
class VariantAnalysisCleanupServiceTest {
    @TempDir Path tempDir;

    @ParameterizedTest
    @EnumSource(VariantAnalysisStatus.class)
    void retainsOldJobsAndSourceFilesInEveryStatus(VariantAnalysisStatus status) throws Exception {
        var properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        properties.setRetention(Duration.ofHours(24)); // Old private configuration must also be safe.
        var repository = spy(new InMemoryVariantAnalysisJobRepository());
        Path workspace = Files.createDirectory(tempDir.resolve("old-job"));
        Path input = Files.writeString(workspace.resolve("input.vcf"), "##fileformat=VCFv4.2\n");
        Path output = Files.writeString(workspace.resolve("exomiser-results.json"), "{\"source\":true}");
        Path history = Files.writeString(workspace.resolve("acmg-result.json"), "{\"saved\":true}");
        Instant old = Instant.now().minus(Duration.ofDays(3650));
        var job = VariantAnalysisJob.restore("old-job", "research.vcf", "0", GenomeAssembly.GRCh38,
                AnalysisMode.WES, "proband", workspace, input, old, old, status, "proband",
                false, "COPY_ONLY", null, null, null);
        repository.save(job);
        clearInvocations(repository);

        var service = new VariantAnalysisCleanupService(repository, properties);
        service.cleanupExpiredJobs();
        service.cleanupExpiredJobs();

        verifyNoInteractions(repository); // No parent DELETE, hence no cascading ACMG DELETE.
        assertTrue(repository.findById(job.getId()).isPresent());
        assertEquals("##fileformat=VCFv4.2\n", Files.readString(input));
        assertEquals("{\"source\":true}", Files.readString(output));
        assertEquals("{\"saved\":true}", Files.readString(history));
    }

    @Test
    void oldRetentionValuesCannotReenableExpiryAndNoTimerRemains() {
        var repository = mock(VariantAnalysisJobRepository.class);
        for (Duration duration : Arrays.asList(null, Duration.ZERO, Duration.ofHours(-1), Duration.ofHours(24))) {
            var properties = new VariantAnalysisProperties();
            properties.setRetention(duration);
            properties.setCleanupDelayMs(1);
            new VariantAnalysisCleanupService(repository, properties).cleanupExpiredJobs();
        }
        verifyNoInteractions(repository);
        for (var method : VariantAnalysisCleanupService.class.getDeclaredMethods()) {
            assertEquals(0, method.getAnnotationsByType(Scheduled.class).length);
        }
        assertEquals(Duration.ZERO, new VariantAnalysisProperties().getRetention());
    }
}
