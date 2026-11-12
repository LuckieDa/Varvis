package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.RawScanResult;
import org.nbscn.varvis.variantanalysis.exomiser.ExomiserTsvScanner.ScanResult;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExomiserCompletionRecoveryServiceTest {

    @TempDir
    Path temporary;

    @Test
    void closesReadyOnlyWhenPersistedRowsMatchCompletedExomiserOutput() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("complete");
        writeOutput(job, 2);
        when(repository.countInterpretations(job.getId())).thenReturn(2);

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());
        assertTrue(recovery.recover(job));

        assertEquals(VariantAnalysisStatus.READY, job.getStatus());
        assertEquals(AnalysisEngine.EXOMISER, job.getEngineMetadata().getEngine());
        assertEquals("RANKED", job.getEngineMetadata().getStatus().name());
        assertEquals("fbeacded9c78cb90f293e571914c5dd82f847e0778c4bdca0ca7c8a59fd30f5e",
                job.getEngineMetadata().getOutputSha256());
        verify(repository).save(job);
        verify(repository).appendEvent(eq(job.getId()), eq(VariantAnalysisStatus.READY),
                contains("EXOMISER_PERSISTENCE_RECOVERED"));
    }

    @Test
    void requiresThePersistedTerminalEngineAttestationBeforeRecovery() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("missing-terminal-attestation");
        writeOutput(job, 1);
        EngineMetadata unattested = EngineMetadata.varvisRunning();
        job.applyEngineMetadata(unattested);

        ExomiserCompletionRecoveryService recovery =
                new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:"
                        + "EXOMISER_RECOVERY_ENGINE_ATTESTATION_INVALID",
                job.getErrorMessage());
        assertSame(unattested, job.getEngineMetadata());
        verify(repository, never()).countInterpretations(job.getId());
    }

    @Test
    void sameRowCountWithDifferentVariantContentCannotReusePersistedInterpretations()
            throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("variant-hash-drift");
        Path variants = writeOutput(job, 1);
        EngineMetadata checkpoint = job.getEngineMetadata();
        Files.writeString(variants,
                "#RANK\tCONTIG\tSTART\tREF\tALT\n1\t2\t200000\tC\tT\n");

        ExomiserCompletionRecoveryService recovery =
                new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:"
                        + "EXOMISER_RECOVERY_OUTPUT_SHA_MISMATCH",
                job.getErrorMessage());
        assertSame(checkpoint, job.getEngineMetadata());
        verify(repository, never()).countInterpretations(job.getId());
    }

    @Test
    void recoveryPreservesTheOriginalEngineProvenanceWhenCurrentConfigDrifts()
            throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("preserve-engine-provenance");
        writeOutput(job, 1);
        EngineMetadata checkpoint = job.getEngineMetadata();
        when(repository.countInterpretations(job.getId())).thenReturn(1);
        VariantAnalysisProperties changed = properties();
        changed.getExomiser().setVersion("99.0.0");
        changed.getExomiser().setResourceFingerprint("current-config-must-not-rewrite");

        ExomiserCompletionRecoveryService recovery =
                new ExomiserCompletionRecoveryService(repository, changed);

        assertTrue(recovery.recover(job));
        assertSame(checkpoint, job.getEngineMetadata());
        assertEquals("15.1.0", job.getEngineMetadata().getEngineVersion());
        assertEquals("test-fingerprint", job.getEngineMetadata().getResourceFingerprint());
    }

    @Test
    void anIncompleteSiblingAttemptDoesNotHideTheUniqueCompleteCheckpoint()
            throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("complete-with-residual-attempt");
        writeOutput(job, 1);
        Files.createDirectories(job.getWorkspace()
                .resolve("exomiser-incomplete")
                .resolve("output"));
        when(repository.countInterpretations(job.getId())).thenReturn(1);

        ExomiserCompletionRecoveryService recovery =
                new ExomiserCompletionRecoveryService(repository, properties());

        assertTrue(recovery.recover(job));
        assertEquals(VariantAnalysisStatus.READY, job.getStatus());
    }

    @Test
    void closesFailedWhenOutputAndPersistedRowsDoNotMatch() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("incomplete");
        writeOutput(job, 2);
        when(repository.countInterpretations(job.getId())).thenReturn(1);

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());
        assertFalse(recovery.recover(job));

        assertEquals(VariantAnalysisStatus.FAILED, job.getStatus());
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_RECOVERY_ROW_COUNT_MISMATCH",
                job.getErrorMessage());
        verify(repository).save(job);
        verify(repository).appendEvent(eq(job.getId()), eq(VariantAnalysisStatus.FAILED),
                contains("EXOMISER_PERSISTENCE_INCOMPLETE"));
    }

    @Test
    void hashesCompletedOutputAcrossMultipleStreamingBuffers() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("large-complete");
        writeOutput(job, 12_000);
        when(repository.countInterpretations(job.getId())).thenReturn(12_000);

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());

        assertTrue(recovery.recover(job));
        assertEquals("d09b401624e1aa3c52e8e3e32436399b32b42ff53dc6e6412881ad5b78c5ee24",
                job.getEngineMetadata().getOutputSha256());
    }

    @Test
    void ignoresRankingJobsWithoutAnExomiserOutputCheckpoint() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("no-output");

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());
        assertFalse(recovery.recover(job));

        assertEquals(VariantAnalysisStatus.RANKING, job.getStatus());
        verify(repository, never()).save(job);
    }

    @Test
    void ignoresRankingJobWhenWorkspaceNoLongerExists() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("missing-workspace");
        Files.delete(job.getWorkspace().resolve("input.vcf"));
        Files.delete(job.getWorkspace());

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals(VariantAnalysisStatus.RANKING, job.getStatus());
        verify(repository, never()).save(job);
        verify(repository, never()).appendEvent(eq(job.getId()), eq(VariantAnalysisStatus.FAILED),
                contains("EXOMISER_PERSISTENCE_INCOMPLETE"));
    }

    @Test
    void recoversOnlyTheExactGenericRestartFailureWhenCheckpointIsComplete() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("restart-failure");
        writeOutput(job, 1);
        job.fail("Application restarted before analysis completed");
        when(repository.countInterpretations(job.getId())).thenReturn(1);

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());

        assertTrue(recovery.recover(job));
        assertEquals(VariantAnalysisStatus.READY, job.getStatus());
    }

    @Test
    void restoresCompactPartialLiftoverAuditFromPersistedEventWithoutRerunningExomiser() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("partial-liftover");
        writeOutput(job, 1);
        when(repository.countInterpretations(job.getId())).thenReturn(1);
        when(repository.findEvents(job.getId())).thenReturn(Collections.singletonList(new VariantAnalysisEvent(
                1L, job.getId(), VariantAnalysisStatus.NORMALIZING,
                "PARTIAL_LIFTOVER: source=951530, lifted=936271, rejected=15259; retained for audit",
                Instant.parse("2026-07-26T00:00:00Z")
        )));

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());

        assertTrue(recovery.recover(job));
        assertEquals(VariantAnalysisStatus.READY, job.getStatus());
        assertTrue(job.isNormalizationApplied());
        assertEquals("PICARD;PARTIAL_LIFTOVER:15259;SOURCE=951530;LIFTED=936271",
                job.getNormalizationEngine());
    }

    @Test
    void failsClosedWithStableCodeWhenVariantsOutputIsMissing() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("missing-variants");
        Path variants = writeOutput(job, 1);
        Files.delete(variants);

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals(VariantAnalysisStatus.FAILED, job.getStatus());
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_TSV_FILE_MISSING",
                job.getErrorMessage());
        verify(repository, never()).countInterpretations(job.getId());
        verify(repository).appendEvent(job.getId(), VariantAnalysisStatus.FAILED,
                job.getErrorMessage());
    }

    @Test
    void malformedOutputDoesNotPersistPathOrRawCellCanary() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("private-path-canary");
        Path variants = writeOutput(job, 1);
        String rawCanary = "PATIENT_SECRET_C:WAREHOUSE_CASE_42";
        Files.write(variants, ("#RANK\tCONTIG\tSTART\tREF\tALT\n"
                + "1\t1\t100000\tA\tG\t" + rawCanary + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals(VariantAnalysisStatus.FAILED, job.getStatus());
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_TSV_COLUMN_COUNT_MISMATCH",
                job.getErrorMessage());
        assertFalse(job.getErrorMessage().contains(rawCanary));
        assertFalse(job.getErrorMessage().contains(job.getWorkspace().toString()));
        verify(repository, never()).countInterpretations(job.getId());
        verify(repository).appendEvent(job.getId(), VariantAnalysisStatus.FAILED,
                job.getErrorMessage());
    }

    @Test
    void companionGeneSymlinkFailsClosedWithoutLeakingTargetOrPath() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("gene-link");
        Path variants = writeOutput(job, 1);
        Path genes = variants.getParent().resolve("result.genes.tsv");
        Path privateTarget = temporary.resolve("PATIENT_SECRET_GENE_TARGET.tsv");
        Files.write(privateTarget, "#RANK\tGENE_SYMBOL\n1\tSECRET\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assumeTrue(replaceWithSymbolicLink(genes, privateTarget),
                "Symbolic links are unavailable on this host");

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals(VariantAnalysisStatus.FAILED, job.getStatus());
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_RECOVERY_OUTPUT_SET_INCOMPLETE",
                job.getErrorMessage());
        assertFalse(job.getErrorMessage().contains(privateTarget.toString()));
        assertFalse(job.getErrorMessage().contains(job.getWorkspace().toString()));
        verify(repository, never()).countInterpretations(job.getId());
        verify(repository).appendEvent(job.getId(), VariantAnalysisStatus.FAILED,
                job.getErrorMessage());
    }

    @Test
    void missingCompanionOutputUsesTheStableIncompleteSetCode() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("missing-gene-companion");
        Path variants = writeOutput(job, 1);
        Files.delete(variants.getParent().resolve("result.genes.tsv"));

        ExomiserCompletionRecoveryService recovery =
                new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals(VariantAnalysisStatus.FAILED, job.getStatus());
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_RECOVERY_OUTPUT_SET_INCOMPLETE",
                job.getErrorMessage());
        verify(repository, never()).countInterpretations(job.getId());
        verify(repository).appendEvent(job.getId(), VariantAnalysisStatus.FAILED,
                job.getErrorMessage());
    }

    @Test
    void emptyJsonlCompanionUsesTheStableIncompleteSetCode() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("empty-jsonl-companion");
        Path variants = writeOutput(job, 1);
        Files.write(variants.getParent().resolve("result.jsonl"), new byte[0]);

        ExomiserCompletionRecoveryService recovery =
                new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_RECOVERY_OUTPUT_SET_INCOMPLETE",
                job.getErrorMessage());
        verify(repository, never()).countInterpretations(job.getId());
    }

    @Test
    void malformedGeneCompanionUsesTheStableScannerCode() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("malformed-gene-companion");
        Path variants = writeOutput(job, 1);
        Files.writeString(variants.getParent().resolve("result.genes.tsv"),
                "#RANK\t#RANK\n1\t1\n");

        ExomiserCompletionRecoveryService recovery =
                new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_TSV_HEADER_DUPLICATE",
                job.getErrorMessage());
        verify(repository, never()).countInterpretations(job.getId());
    }

    @Test
    void geneReplacementBeforeRecoveryCommitFailsTheOutputSetVerification() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("gene-output-set-change");
        Path variants = writeOutput(job, 1);
        Path genes = variants.getParent().resolve("result.genes.tsv");
        ExomiserTsvScanner scanner = spy(new ExomiserTsvScanner());
        doAnswer(invocation -> {
            Files.writeString(genes,
                    "#RANK\tGENE_SYMBOL\n1\tREPLACED_WITH_A_DIFFERENT_SIZE\n");
            return invocation.callRealMethod();
        }).when(scanner).verifyUnchanged(eq(genes), any(ScanResult.class));

        ExomiserCompletionRecoveryService recovery =
                new ExomiserCompletionRecoveryService(repository, properties(), scanner);

        assertFalse(recovery.recover(job));
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_TSV_FILE_CHANGED",
                job.getErrorMessage());
        verify(repository, never()).countInterpretations(job.getId());
    }

    @Test
    void jsonlReplacementBeforeRecoveryCommitFailsTheOutputSetVerification() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("jsonl-output-set-change");
        Path variants = writeOutput(job, 1);
        Path jsonl = variants.getParent().resolve("result.jsonl");
        ExomiserTsvScanner scanner = spy(new ExomiserTsvScanner());
        doAnswer(invocation -> {
            Files.writeString(jsonl,
                    "{\"sample\":\"replacement-with-a-different-size\"}\n");
            return invocation.callRealMethod();
        }).when(scanner).verifyRawUnchanged(eq(jsonl), any(RawScanResult.class));

        ExomiserCompletionRecoveryService recovery =
                new ExomiserCompletionRecoveryService(repository, properties(), scanner);

        assertFalse(recovery.recover(job));
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_RAW_FILE_CHANGED",
                job.getErrorMessage());
        verify(repository, never()).countInterpretations(job.getId());
    }

    @Test
    void unexpectedRecoveryFailureDoesNotPersistUnderlyingExceptionMessage() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJob job = rankingJob("repository-error");
        writeOutput(job, 1);
        String privateCanary = "PATIENT_SECRET_D:\\CASES\\CASE_99";
        when(repository.countInterpretations(job.getId()))
                .thenThrow(new IllegalStateException(privateCanary));

        ExomiserCompletionRecoveryService recovery = new ExomiserCompletionRecoveryService(repository, properties());

        assertFalse(recovery.recover(job));
        assertEquals(VariantAnalysisStatus.FAILED, job.getStatus());
        assertEquals("EXOMISER_PERSISTENCE_INCOMPLETE:EXOMISER_RECOVERY_FAILED",
                job.getErrorMessage());
        assertFalse(job.getErrorMessage().contains(privateCanary));
        verify(repository).appendEvent(job.getId(), VariantAnalysisStatus.FAILED,
                job.getErrorMessage());
    }

    private VariantAnalysisProperties properties() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getExomiser().setVersion("15.1.0");
        properties.getExomiser().setResourceFingerprint("test-fingerprint");
        return properties;
    }

    private VariantAnalysisJob rankingJob(String id) throws Exception {
        Path workspace = temporary.resolve(id);
        Files.createDirectories(workspace);
        Path uploaded = workspace.resolve("input.vcf");
        Files.write(uploaded, Collections.singletonList("##fileformat=VCFv4.2"));
        VariantAnalysisJob job = new VariantAnalysisJob(
                id, "input.vcf", "sha", GenomeAssembly.GRCh38, AnalysisMode.WES,
                "sample", workspace, uploaded
        );
        job.transitionTo(VariantAnalysisStatus.RANKING);
        return job;
    }

    private Path writeOutput(VariantAnalysisJob job, int rows) throws Exception {
        Path output = job.getWorkspace().resolve("exomiser-test").resolve("output");
        Files.createDirectories(output);
        StringBuilder text = new StringBuilder("#RANK\tCONTIG\tSTART\tREF\tALT\n");
        for (int index = 0; index < rows; index++) {
            text.append(index + 1)
                    .append("\t1\t")
                    .append(100_000 + index)
                    .append("\tA\tG\n");
        }
        Path variants = output.resolve("result.variants.tsv");
        byte[] variantBytes = text.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(variants, variantBytes);
        job.applyEngineMetadata(EngineMetadata.exomiserRanked(
                "15.1.0",
                "test-fingerprint",
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(variantBytes)),
                "EXOMISER_G0"
        ));
        Files.write(output.resolve("result.genes.tsv"), "#RANK\tGENE_SYMBOL\n1\tGENE1\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(output.resolve("result.jsonl"), "{\"sample\":\"synthetic\"}\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return variants;
    }

    private boolean replaceWithSymbolicLink(Path link, Path target) throws Exception {
        Files.delete(link);
        try {
            Files.createSymbolicLink(link, target);
            return Files.isSymbolicLink(link);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException exception) {
            return false;
        }
    }
}
