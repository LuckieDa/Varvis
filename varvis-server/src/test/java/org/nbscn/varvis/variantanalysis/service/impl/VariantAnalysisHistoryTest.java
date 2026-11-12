package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.exception.InvalidVcfUploadException;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.persistence.InMemoryVariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisJobProcessor;
import org.nbscn.varvis.variantanalysis.service.VariantEnhancementProcessor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VariantAnalysisHistoryTest {
    @TempDir Path root;

    @Test
    void exactUploadFindsConvertedReadyAndRunningHistoryWithoutStartingAnalysis() throws Exception {
        var upload = upload("renamed.vcf", "A");
        String sha = sha(upload);
        var repository = spy(new InMemoryVariantAnalysisJobRepository());
        var ready = job("converted-ready", sha);
        ready.complete("proband", true, "PICARD;LIFTOVER;SOURCE=1;LIFTED=1;REJECTED=0", null,
                List.of(), List.of());
        var running = job("already-running", sha);
        running.transitionTo(VariantAnalysisStatus.RANKING);
        var other = job("different-bytes", sha(upload("same-name.vcf", "C")));
        repository.save(ready);
        repository.save(running);
        repository.save(other);
        var readyUpdatedAt = ready.getUpdatedAt();
        var runningUpdatedAt = running.getUpdatedAt();
        clearInvocations(repository);
        var processor = mock(VariantAnalysisJobProcessor.class);
        var enhancement = mock(VariantEnhancementProcessor.class);
        var settings = new VariantAnalysisProperties();
        settings.setStorageRoot(root.resolve("not-created"));
        // History must remain readable even without a configured current Exomiser runtime.
        settings.getExomiser().setEnabled(false);
        var service = new VariantAnalysisServiceImpl(repository, mock(VariantEnhancementRepository.class),
                processor, enhancement, settings);

        var found = service.findHistory(upload);

        assertEquals(Set.of("converted-ready", "already-running"),
                found.stream().map(job -> job.getId()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(GenomeAssembly.GRCh37, found.get(0).getGenomeAssembly());
        assertEquals(VariantAnalysisStatus.READY, ready.getStatus());
        assertEquals(VariantAnalysisStatus.RANKING, running.getStatus());
        assertEquals(readyUpdatedAt, ready.getUpdatedAt());
        assertEquals(runningUpdatedAt, running.getUpdatedAt());
        verify(repository).findHistoryByFileSha256(sha);
        verify(repository, never()).save(any());
        verify(repository, never()).findReusableReadyJobs(any(), any(), any(), any());
        verifyNoInteractions(processor, enhancement);
        assertFalse(Files.exists(settings.getStorageRoot()));
    }

    @Test
    void missingHistoryAndInvalidUploadNeverCreateAJob() throws Exception {
        var repository = mock(VariantAnalysisJobRepository.class);
        var processor = mock(VariantAnalysisJobProcessor.class);
        var settings = new VariantAnalysisProperties();
        settings.setStorageRoot(root.resolve("not-created"));
        var service = new VariantAnalysisServiceImpl(repository, mock(VariantEnhancementRepository.class),
                processor, mock(VariantEnhancementProcessor.class), settings);
        var upload = upload("synthetic.vcf", "A");
        when(repository.findHistoryByFileSha256(sha(upload))).thenReturn(List.of());
        assertTrue(service.findHistory(upload).isEmpty());
        verify(repository).findHistoryByFileSha256(sha(upload));
        clearInvocations(repository);

        assertThrows(InvalidVcfUploadException.class, () -> service.findHistory(
                new MockMultipartFile("vcfFile", "empty.vcf", "text/plain", new byte[0])));
        assertThrows(InvalidVcfUploadException.class, () -> service.findHistory(
                new MockMultipartFile("vcfFile", "invalid.vcf", "text/plain", "not a VCF".getBytes(StandardCharsets.UTF_8))));
        verifyNoInteractions(repository, processor);
        assertFalse(Files.exists(settings.getStorageRoot()));
    }

    @Test
    void memoryHistoryIsBoundedAndSortedNewestFirst() {
        var repository = new InMemoryVariantAnalysisJobRepository();
        for (int index = 0; index < 25; index++) {
            var job = job("history-" + index, "a".repeat(64));
            repository.save(job);
        }
        repository.save(job("other-sha", "b".repeat(64)));
        var history = repository.findHistoryByFileSha256("a".repeat(64));
        assertEquals(20, history.size());
        assertTrue(history.stream().allMatch(job -> "a".repeat(64).equals(job.getFileSha256())));
        for (int index = 1; index < history.size(); index++) {
            assertFalse(history.get(index).getCreatedAt().isAfter(history.get(index - 1).getCreatedAt()));
        }
    }

    private VariantAnalysisJob job(String id, String sha) {
        return new VariantAnalysisJob(id, "original-name.vcf", sha, GenomeAssembly.GRCh37, AnalysisMode.WES,
                "proband", root.resolve(id), root.resolve(id).resolve("upload.vcf"));
    }

    private MockMultipartFile upload(String name, String alternate) {
        return new MockMultipartFile("vcfFile", name, "text/plain", ("##fileformat=VCFv4.2\n"
                + "##reference=GRCh37\n"
                + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n"
                + "1\t100\t.\tG\t" + alternate + "\t.\tPASS\t.\tGT\t0/1\n").getBytes(StandardCharsets.UTF_8));
    }

    private String sha(MockMultipartFile upload) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(upload.getBytes()));
    }
}
