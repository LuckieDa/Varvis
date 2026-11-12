package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.dto.PhenotypeGenePriorLookupResponse;
import org.nbscn.varvis.variantanalysis.dto.TrioAnalysisInputRequest;
import org.nbscn.varvis.exception.VariantAnalysisInputException;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.service.GenePhenotypePriorProviderFactory;
import org.nbscn.varvis.variantanalysis.service.PhenotypeInputNormalizer;
import org.nbscn.varvis.variantanalysis.service.VariantAnalysisJobProcessor;
import org.nbscn.varvis.variantanalysis.service.VariantEnhancementProcessor;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class VariantAnalysisServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void incompatibleDatabaseRejectsBeforeFileStorageOrEngineDispatch() {
        var properties = exomiserProperties();
        properties.setStorageRoot(tempDir.resolve("not-created"));
        var repository = mock(VariantAnalysisJobRepository.class);
        var processor = mock(VariantAnalysisJobProcessor.class);
        org.mockito.Mockito.doThrow(new org.nbscn.varvis.exception.VariantAnalysisPersistenceException(
                "ANALYSIS_DATABASE_SCHEMA_INCOMPATIBLE", "schema mismatch", null))
                .when(repository).verifyPersistenceSchema();
        var service = new VariantAnalysisServiceImpl(repository, mock(VariantEnhancementRepository.class),
                processor, mock(VariantEnhancementProcessor.class), properties);
        assertThrows(org.nbscn.varvis.exception.VariantAnalysisPersistenceException.class,
                () -> service.submit(sampledVcfFile(), "GRCh38", "WES", "proband", Collections.emptyList()));
        org.mockito.Mockito.verifyNoInteractions(processor);
        verify(repository, never()).save(any());
        assertFalse(Files.exists(properties.getStorageRoot()));
    }

    @Test
    void exomiserAcceptsGrch37AndRetainsItsInputAssemblyBeforeLiftover() throws Exception {
        VariantAnalysisProperties properties = exomiserProperties();
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        when(repository.findReusableReadyJob(
                any(String.class), eq(GenomeAssembly.GRCh37.name()), eq(AnalysisMode.WES.name()), eq("proband")
        )).thenReturn(Optional.empty());
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository, mock(VariantEnhancementRepository.class), processor,
                mock(VariantEnhancementProcessor.class), properties
        );

        VariantAnalysisJob job = service.submit(sampledVcfFile(), "GRCh37", "WES", "proband", Collections.emptyList());

        assertEquals(GenomeAssembly.GRCh37, job.getGenomeAssembly());
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"STANDARD","INCLUDE_INTRONS"})
    void sameInputRestoresAttestedReadyResultUnlessExplicitlyCreatedAgain(String selectedScope) throws Exception {
        VariantAnalysisProperties properties = exomiserProperties();
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        MockMultipartFile input = sampledVcfFile();
        String inputSha256 = sha256(input.getBytes());
        Path workspace = Files.createDirectory(tempDir.resolve("ready-analysis"));
        Path storedVcf = workspace.resolve("upload.vcf");
        Files.write(storedVcf, input.getBytes());
        VariantAnalysisJob ready = new VariantAnalysisJob(
                "ready-analysis",
                input.getOriginalFilename(),
                inputSha256,
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "proband",
                workspace,
                storedVcf,
                PhenotypeAwareSummary.disabled()
        );
        ready.complete("proband", false, "EXOMISER", null,
                Collections.emptyList(), Collections.emptyList());
        ready.applyEngineMetadata(EngineMetadata.exomiserRanked(
                properties.getExomiser().getVersion(),
                properties.getExomiser().getResourceFingerprint(),
                "a".repeat(64),
                "EXOMISER_G0"
        ));
        Path template = tempDir.resolve("effective-template.yml");
        Files.copy(Path.of(getClass().getResource("/exomiser/scope-test.yml").toURI()), template);
        properties.getExomiser().setAnalysisTemplate(template.toString());
        var scopePolicy = org.nbscn.varvis.variantanalysis.engine.AnalysisScopePolicy.resolve(properties.getExomiser(),
                org.nbscn.varvis.variantanalysis.engine.AnalysisScopePolicy.parse(selectedScope), false);
        org.nbscn.varvis.variantanalysis.engine.AnalysisScopePolicy.save(ready, scopePolicy);
        Files.writeString(workspace.resolve("analysis-scope-executed.sha256"), scopePolicy.policySha256());
        when(repository.findReusableReadyJobs(
                inputSha256,
                GenomeAssembly.GRCh38.name(),
                AnalysisMode.WES.name(),
                "proband"
        )).thenReturn(Collections.singletonList(ready));
        when(repository.countInterpretations("ready-analysis")).thenReturn(5);
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob restored = service.submit(
                input,
                "GRCh38",
                "WES",
                "proband",
                Collections.emptyList(), null, null, TrioAnalysisInputRequest.disabled(), null, null, selectedScope
        );

        assertSame(ready, restored);
        verify(repository, never()).save(any());
        verify(processor, never()).process(any(String.class), any(PhenotypeAwareSummary.class));
        var created = service.submit(input, "GRCh38", "WES", "proband", Collections.emptyList(), null, null,
                TrioAnalysisInputRequest.disabled(), null, null, selectedScope, true);
        assertFalse(ready.getId().equals(created.getId()));
        assertEquals(inputSha256, created.getFileSha256());
        assertArrayEquals(input.getBytes(), Files.readAllBytes(created.getUploadedFile()));
        assertEquals(selectedScope, org.nbscn.varvis.variantanalysis.engine.AnalysisScopePolicy.publicDetails(created).get("scope"));
        verify(repository).findReusableReadyJobs(inputSha256, GenomeAssembly.GRCh38.name(), AnalysisMode.WES.name(), "proband");
        verify(repository).save(created);
        verify(processor).process(eq(created.getId()), any(PhenotypeAwareSummary.class));

        var other = service.submit(input, "GRCh38", "WES", "proband", Collections.emptyList(), null, null,
                TrioAnalysisInputRequest.disabled(), null, null, selectedScope.equals("STANDARD") ? "INCLUDE_INTRONS" : "STANDARD");
        assertFalse(ready.getId().equals(other.getId()));
        assertEquals(selectedScope, org.nbscn.varvis.variantanalysis.engine.AnalysisScopePolicy.publicDetails(ready).get("scope"));
        Files.writeString(template, Files.readString(template).replace("maxFrequency: 2.0", "maxFrequency: 1.0"));
        var changed = service.submit(input, "GRCh38", "WES", "proband", Collections.emptyList(), null, null,
                TrioAnalysisInputRequest.disabled(), null, null, selectedScope);
        assertFalse(ready.getId().equals(changed.getId()));
        assertThrows(VariantAnalysisInputException.class, () -> service.submit(input, "GRCh38", "WES", "proband",
                Collections.emptyList(), null, null, TrioAnalysisInputRequest.disabled(), null, null, "true"));
    }

    @Test
    void submitDoesNotReuseReadyResultWhenPhenotypeInputsDiffer() throws Exception {
        VariantAnalysisProperties properties = exomiserProperties();
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        MockMultipartFile input = sampledVcfFile();
        String inputSha256 = sha256(input.getBytes());
        Path workspace = Files.createDirectory(tempDir.resolve("phenotype-ready-analysis"));
        Path storedVcf = workspace.resolve("upload.vcf");
        Files.write(storedVcf, input.getBytes());
        VariantAnalysisJob ready = new VariantAnalysisJob(
                "phenotype-ready-analysis",
                input.getOriginalFilename(),
                inputSha256,
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "proband",
                workspace,
                storedVcf,
                PhenotypeAwareSummary.disabled()
        );
        ready.complete("proband", false, "EXOMISER", null,
                Collections.emptyList(), Collections.emptyList());
        ready.applyEngineMetadata(EngineMetadata.exomiserRanked(
                properties.getExomiser().getVersion(),
                properties.getExomiser().getResourceFingerprint(),
                "b".repeat(64),
                "EXOMISER_G0"
        ));
        when(repository.findReusableReadyJobs(
                inputSha256,
                GenomeAssembly.GRCh38.name(),
                AnalysisMode.WES.name(),
                "proband"
        )).thenReturn(Collections.singletonList(ready));
        when(repository.countInterpretations("phenotype-ready-analysis")).thenReturn(5);
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob created = service.submit(
                input,
                "GRCh38",
                "WES",
                "proband",
                Collections.singletonList("HP:0001250")
        );

        assertFalse(ready.getId().equals(created.getId()));
        verify(repository).save(created);
        verify(processor).process(eq(created.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void exomiserRejectsAssemblySelectionThatConflictsWithVcfHeaderEvidence() {
        VariantAnalysisProperties properties = exomiserProperties();
        VariantAnalysisServiceImpl service = service(properties);
        MockMultipartFile grch37Vcf = new MockMultipartFile(
                "vcfFile", "grch37.vcf", "text/plain",
                ("##fileformat=VCFv4.2\n"
                        + "##reference=GRCh37\n"
                        + "##contig=<ID=1,length=249250621>\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n")
                        .getBytes(StandardCharsets.UTF_8)
        );

        VariantAnalysisInputException error = assertThrows(
                VariantAnalysisInputException.class,
                () -> service.submit(grch37Vcf, "GRCh38", "WES", "proband", Collections.emptyList())
        );

        assertEquals("INVALID_INPUT", error.getErrorCode());
        assertTrue(error.getMessage().contains("ASSEMBLY_CONFLICT"));
    }

    @Test
    void exomiserPreparesAPrivateTabixIndexForCompressedVcfWithoutClientSidecar() throws Exception {
        VariantAnalysisProperties properties = exomiserProperties();
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        when(repository.findReusableReadyJob(
                any(String.class), eq(GenomeAssembly.GRCh38.name()), eq(AnalysisMode.WES.name()), eq("proband")
        )).thenReturn(Optional.empty());
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(
                compressedVcfFile(), "GRCh38", "WES", "proband", Collections.emptyList());

        assertTrue(job.getUploadedFile().getFileName().toString().endsWith(".exomiser.vcf.gz"));
        assertTrue(Files.isRegularFile(job.getUploadedFile().resolveSibling(
                job.getUploadedFile().getFileName() + ".tbi")));
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void exomiserRejectsIncompleteFamilyPedigreeRatherThanIgnoringIt() throws Exception {
        VariantAnalysisProperties properties = exomiserProperties();
        VariantAnalysisServiceImpl service = service(properties);
        TrioAnalysisInputRequest trio = new TrioAnalysisInputRequest(
                true, TrioAnalysisInputRequest.MODE_JOINT_VCF, true,
                "proband", "father", "mother", null, null, true, false, false
        );

        VariantAnalysisInputException error = assertThrows(
                VariantAnalysisInputException.class,
                () -> service.submit(vcfFile(), "GRCh38", "WES", "proband",
                        Collections.emptyList(), Collections.emptyList(), trio, null, null)
        );

        assertEquals("INVALID_INPUT", error.getErrorCode());
        assertTrue(error.getMessage().contains("TRIO_PEDIGREE_ATTRIBUTES_INCOMPLETE"));
    }

    @Test
    void exomiserRejectsSeparateFamilyVcfsWithoutAttemptingAnUnsafeMerge() throws Exception {
        VariantAnalysisServiceImpl service = service(exomiserProperties());
        TrioAnalysisInputRequest trio = new TrioAnalysisInputRequest(
                true, TrioAnalysisInputRequest.MODE_THREE_SINGLE_SAMPLE_VCFS, true,
                "proband", "father", "mother", "FEMALE", "MALE", "FEMALE", true, false, false,
                null, null, true, true, true);

        VariantAnalysisInputException error = assertThrows(VariantAnalysisInputException.class,
                () -> service.submit(vcfFile(), "GRCh38", "WES", "proband", Collections.emptyList(),
                        Collections.emptyList(), trio, null, null));

        assertEquals("INVALID_INPUT", error.getErrorCode());
        assertTrue(error.getMessage().contains("TRIO_JOINT_VCF_REQUIRED"));
    }

    @Test
    void submitWithEmptyHpoTermsKeepsExistingNoPhenotypeFlow() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        when(repository.findReusableReadyJob(
                any(String.class),
                eq(GenomeAssembly.GRCh38.name()),
                eq(AnalysisMode.WES.name()),
                eq(null)
        )).thenReturn(Optional.empty());

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(
                vcfFile(),
                "GRCh38",
                "WES",
                null,
                Arrays.asList(null, "   ")
        );

        assertFalse(job.getPhenotypeSummary().isEnabled());
        assertFalse(job.getPhenotypeSummary().isRankingApplied());
        verify(repository).save(job);
        verify(repository).appendEvent(job.getId(), VariantAnalysisStatus.UPLOADED, "Uploaded VCF stored");
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void submitDoesNotReuseLegacyReadyJobWithoutScientificEquivalenceAttestation() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        Path missingWorkspace = tempDir.resolve("old-ready-job-workspace");
        VariantAnalysisJob reusable = new VariantAnalysisJob(
                "ready-job",
                "patient.vcf",
                "stored-sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                null,
                missingWorkspace,
                missingWorkspace.resolve("upload.vcf")
        );
        reusable.complete("sample", false, "COPY_ONLY", null, Collections.emptyList(), Collections.emptyList());
        java.time.Instant legacyUpdatedAt = reusable.getUpdatedAt();
        when(repository.findReusableReadyJob(
                any(String.class),
                eq(GenomeAssembly.GRCh38.name()),
                eq(AnalysisMode.WES.name()),
                eq(null)
        )).thenReturn(Optional.of(reusable));

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(vcfFile(), "GRCh38", "WES", null, Collections.emptyList());

        assertFalse("ready-job".equals(job.getId()));
        assertEquals(VariantAnalysisStatus.UPLOADED, job.getStatus());
        assertEquals(VariantAnalysisStatus.READY, reusable.getStatus());
        assertEquals(legacyUpdatedAt, reusable.getUpdatedAt());
        verify(repository, never()).findReusableReadyJob(
                any(String.class), any(String.class), any(String.class), any()
        );
        verify(repository).save(job);
        verify(repository).appendEvent(job.getId(), VariantAnalysisStatus.UPLOADED, "Uploaded VCF stored");
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void submitWithHpoTermsCapturesSummaryWhenNoMatchingReadyJobExists() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        when(repository.findReusableReadyJob(
                any(String.class),
                eq(GenomeAssembly.GRCh38.name()),
                eq(AnalysisMode.WES.name()),
                eq("proband")
        )).thenReturn(Optional.empty());

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(
                vcfFile(),
                "GRCh38",
                "WES",
                " proband ",
                Arrays.asList("HP:0001250", "hp:0001250", "abc")
        );

        PhenotypeAwareSummary summary = job.getPhenotypeSummary();
        assertTrue(summary.isEnabled());
        assertFalse(summary.isRankingApplied());
        assertEquals(3, summary.getInputHpoCount());
        assertEquals(1, summary.getValidHpoCount());
        assertEquals(1, summary.getInvalidHpoCount());
        assertEquals(1, summary.getDuplicateHpoCount());
        assertEquals(Collections.singletonList("HP:0001250"), summary.getValidHpoTerms());
        assertEquals(GenePhenotypePriorStatus.DISABLED, summary.getGenePriorSummary().getStatus());
        assertFalse(summary.getGenePriorSummary().isEnabled());
        verify(repository, never()).findReusableReadyJob(
                any(String.class),
                eq(GenomeAssembly.GRCh38.name()),
                eq(AnalysisMode.WES.name()),
                eq("proband")
        );
        verify(repository, never()).deleteById(job.getId());
        verify(repository).appendEvent(
                job.getId(),
                VariantAnalysisStatus.UPLOADED,
                "Phenotype HPO input captured; phenotype-aware ranking will be attempted"
        );
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void submitDoesNotReuseEquivalentLegacyHpoJobWithoutScientificEquivalenceAttestation() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        PhenotypeAwareSummary existingSummary = phenotypeSummary(
                properties,
                Arrays.asList("HP:0001250", "HP:0001298")
        );
        VariantAnalysisJob reusable = new VariantAnalysisJob(
                "ready-hpo-job",
                "patient.vcf",
                "stored-sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "proband",
                tempDir.resolve("old-hpo-workspace"),
                tempDir.resolve("old-hpo-workspace/upload.vcf"),
                existingSummary
        );
        reusable.complete("proband", false, "COPY_ONLY", null, Collections.emptyList(), Collections.emptyList());
        java.time.Instant legacyUpdatedAt = reusable.getUpdatedAt();
        when(repository.findReusableReadyJob(
                any(String.class),
                eq(GenomeAssembly.GRCh38.name()),
                eq(AnalysisMode.WES.name()),
                eq("proband")
        )).thenReturn(Optional.of(reusable));

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(
                vcfFile(),
                "GRCh38",
                "WES",
                "proband",
                Arrays.asList("hp:0001298", "HP:0001250")
        );

        assertFalse("ready-hpo-job".equals(job.getId()));
        assertEquals(Arrays.asList("HP:0001250", "HP:0001298"),
                reusable.getPhenotypeSummary().getValidHpoTerms());
        assertEquals(legacyUpdatedAt, reusable.getUpdatedAt());
        verify(repository, never()).findReusableReadyJob(
                any(String.class), any(String.class), any(String.class), any()
        );
        verify(repository).save(job);
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void submitDoesNotReuseReadyJobForDifferentNormalizedHpoTerms() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        VariantAnalysisJob reusable = new VariantAnalysisJob(
                "different-hpo-job",
                "patient.vcf",
                "stored-sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "proband",
                tempDir.resolve("different-hpo-workspace"),
                tempDir.resolve("different-hpo-workspace/upload.vcf"),
                phenotypeSummary(properties, Collections.singletonList("HP:0001250"))
        );
        reusable.complete("proband", false, "COPY_ONLY", null, Collections.emptyList(), Collections.emptyList());
        when(repository.findReusableReadyJob(
                any(String.class),
                eq(GenomeAssembly.GRCh38.name()),
                eq(AnalysisMode.WES.name()),
                eq("proband")
        )).thenReturn(Optional.of(reusable));

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(
                vcfFile(),
                "GRCh38",
                "WES",
                "proband",
                Collections.singletonList("HP:0001298")
        );

        assertFalse("different-hpo-job".equals(job.getId()));
        verify(repository, never()).findReusableReadyJob(
                any(String.class), any(String.class), any(String.class), any()
        );
        verify(repository).save(job);
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void submitWithEnabledLocalPhenotypePriorAddsGenePriorSummaryWithoutRanking() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        properties.getPhenotypePrior().setEnabled(true);
        properties.getPhenotypePrior().setSource("LOCAL_GENE_PRIOR");
        properties.getPhenotypePrior().setVersion("mock-v1");
        properties.getPhenotypePrior().setLocalFile(fixturePath().toString());

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(
                vcfFile(),
                "GRCh38",
                "WES",
                null,
                Collections.singletonList("HP:0001250")
        );

        PhenotypeAwareSummary summary = job.getPhenotypeSummary();
        assertTrue(summary.isEnabled());
        assertFalse(summary.isRankingApplied());
        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getGenePriorSummary().getStatus());
        assertEquals(2, summary.getGenePriorSummary().getGenesWithPrior());
        assertEquals("MECP2", summary.getGenePriorSummary().getTopGenePriors().get(0).getGeneSymbol());
        verify(repository).save(job);
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void lookupPhenotypeGenePriorChecksFullPriorBeyondConfiguredTopK() throws Exception {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        properties.getPhenotypePrior().setEnabled(true);
        properties.getPhenotypePrior().setSource("LOCAL_GENE_PRIOR");
        properties.getPhenotypePrior().setVersion("mock-v1");
        properties.getPhenotypePrior().setLocalFile(fixturePath().toString());
        properties.getPhenotypePrior().setMaxGenes(1);
        PhenotypeAwareSummary phenotypeSummary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("HP:0001250")
        );
        VariantAnalysisJob job = new VariantAnalysisJob(
                "analysis",
                "patient.vcf",
                "sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                null,
                tempDir.resolve("analysis"),
                tempDir.resolve("analysis").resolve("upload.vcf"),
                phenotypeSummary
        );
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById("analysis")).thenReturn(Optional.of(job));

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                mock(VariantAnalysisJobProcessor.class),
                mock(VariantEnhancementProcessor.class),
                properties
        );

        PhenotypeGenePriorLookupResponse response =
                service.lookupPhenotypeGenePrior("analysis", "SCN1A");

        assertTrue(response.isFound());
        assertEquals("SCN1A", response.getNormalizedGene());
        assertEquals(2, response.getPhenotypeRank());
        assertEquals(1, response.getEffectiveTopK());
        assertFalse(response.isInEffectiveTopK());
        assertTrue(response.getMessage().contains("SCN1A not in top-1 phenotype prior, rank=2"));
    }

    @Test
    void submitWithPhen2GeneImportPriorUsesConfiguredProvider() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        Path importFile = writePhen2GeneImport();
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        properties.getPhenotypePrior().setEnabled(true);
        properties.getPhenotypePrior().setSource("PHEN2GENE_IMPORT");
        properties.getPhenotypePrior().setLocalFile(importFile.toString());

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(
                vcfFile(),
                "GRCh38",
                "WES",
                null,
                Collections.singletonList("HP:0001250")
        );

        PhenotypeAwareSummary summary = job.getPhenotypeSummary();
        assertTrue(summary.isEnabled());
        assertFalse(summary.isRankingApplied());
        assertEquals(GenePhenotypePriorStatus.AVAILABLE, summary.getGenePriorSummary().getStatus());
        assertEquals("PHEN2GENE_IMPORT", summary.getGenePriorSummary().getSource());
        assertEquals("phen2gene-import-v1", summary.getGenePriorSummary().getSourceVersion());
        assertEquals("MECP2", summary.getGenePriorSummary().getTopGenePriors().get(0).getGeneSymbol());
        verify(repository).save(job);
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void submitWithAllInvalidHpoTermsDoesNotBlockAnalysis() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        properties.getPhenotypePrior().setEnabled(true);
        properties.getPhenotypePrior().setLocalFile(fixturePath().toString());

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(
                vcfFile(),
                "GRCh38",
                "WES",
                null,
                Arrays.asList("HP:1250", "abc")
        );

        PhenotypeAwareSummary summary = job.getPhenotypeSummary();
        assertTrue(summary.isEnabled());
        assertFalse(summary.isRankingApplied());
        assertEquals(2, summary.getInvalidHpoCount());
        assertEquals(0, summary.getValidHpoCount());
        assertEquals(Arrays.asList("HP:1250", "abc"), summary.getInvalidHpoTerms());
        assertEquals(GenePhenotypePriorStatus.INVALID_INPUT, summary.getGenePriorSummary().getStatus());
        verify(repository).save(job);
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void submitWithMissingLocalPhenotypePriorFileDoesNotBlockJob() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisJobProcessor processor = mock(VariantAnalysisJobProcessor.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        Path missingFile = tempDir.resolve("missing-gene-prior.tsv");
        properties.getPhenotypePrior().setEnabled(true);
        properties.getPhenotypePrior().setLocalFile(missingFile.toString());

        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                processor,
                mock(VariantEnhancementProcessor.class),
                properties
        );

        VariantAnalysisJob job = service.submit(
                vcfFile(),
                "GRCh38",
                "WES",
                null,
                Collections.singletonList("HP:0001250")
        );

        PhenotypeAwareSummary summary = job.getPhenotypeSummary();
        assertTrue(summary.isEnabled());
        assertFalse(summary.isRankingApplied());
        assertEquals(GenePhenotypePriorStatus.UNAVAILABLE, summary.getGenePriorSummary().getStatus());
        String warnings = String.valueOf(summary.getGenePriorSummary().getWarnings());
        assertTrue(warnings.contains("Provider unavailable does not imply low risk"));
        assertFalse(warnings.contains(missingFile.toString()));
        verify(repository).save(job);
        verify(processor).process(eq(job.getId()), any(PhenotypeAwareSummary.class));
    }

    @Test
    void deletesDatabaseRecordBeforeWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("analysis"));
        Files.write(workspace.resolve("upload.vcf"), new byte[] { '#', '\n' });
        VariantAnalysisJob job = new VariantAnalysisJob(
                "analysis",
                "patient.vcf",
                "sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                null,
                workspace,
                workspace.resolve("upload.vcf")
        );
        job.complete("sample", false, "COPY_ONLY", null, Collections.emptyList(), Collections.emptyList());

        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById("analysis")).thenReturn(Optional.of(job));
        when(repository.findReusableReadyJobs(
                "sha",
                GenomeAssembly.GRCh38.name(),
                AnalysisMode.WES.name(),
                null
        )).thenReturn(Collections.singletonList(job));
        doAnswer(invocation -> {
            assertTrue(Files.exists(workspace));
            return null;
        }).when(repository).deleteById("analysis");

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                mock(VariantAnalysisJobProcessor.class),
                mock(VariantEnhancementProcessor.class),
                properties
        );

        service.delete("analysis");

        verify(repository).deleteById("analysis");
        verify(repository, never()).findReusableReadyJobs(
                any(String.class), any(String.class), any(String.class), any()
        );
        assertFalse(Files.exists(workspace));
    }

    @Test
    void deleteReadyAnalysisPreservesOtherLegacyHistoryWithTheSameUploadKey() throws Exception {
        Path currentWorkspace = Files.createDirectory(tempDir.resolve("current-analysis"));
        Path previousWorkspace = Files.createDirectory(tempDir.resolve("previous-analysis"));
        Files.write(currentWorkspace.resolve("upload.vcf"), new byte[] { '#', '\n' });
        Files.write(previousWorkspace.resolve("upload.vcf"), new byte[] { '#', '\n' });
        VariantAnalysisJob current = reusableReadyJob("current", "sha", null, currentWorkspace);
        VariantAnalysisJob previous = reusableReadyJob("previous", "sha", null, previousWorkspace);

        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById("current")).thenReturn(Optional.of(current));
        when(repository.findReusableReadyJobs(
                "sha",
                GenomeAssembly.GRCh38.name(),
                AnalysisMode.WES.name(),
                null
        )).thenReturn(Arrays.asList(current, previous));

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                mock(VariantAnalysisJobProcessor.class),
                mock(VariantEnhancementProcessor.class),
                properties
        );

        service.delete("current");

        verify(repository).deleteById("current");
        verify(repository, never()).deleteById("previous");
        verify(repository, never()).findReusableReadyJobs(
                any(String.class), any(String.class), any(String.class), any()
        );
        assertFalse(Files.exists(currentWorkspace));
        assertTrue(Files.exists(previousWorkspace));
    }

    @Test
    void deleteFailedAnalysisDoesNotRemoveReusableReadyHistory() throws Exception {
        Path failedWorkspace = Files.createDirectory(tempDir.resolve("failed-analysis"));
        Files.write(failedWorkspace.resolve("upload.vcf"), new byte[] { '#', '\n' });
        VariantAnalysisJob failed = new VariantAnalysisJob(
                "failed",
                "patient.vcf",
                "sha",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                null,
                failedWorkspace,
                failedWorkspace.resolve("upload.vcf")
        );
        failed.fail("failed before ready");

        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById("failed")).thenReturn(Optional.of(failed));

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                mock(VariantAnalysisJobProcessor.class),
                mock(VariantEnhancementProcessor.class),
                properties
        );

        service.delete("failed");

        verify(repository).deleteById("failed");
        verify(repository, never()).findReusableReadyJobs(
                any(String.class),
                any(String.class),
                any(String.class),
                any()
        );
        assertFalse(Files.exists(failedWorkspace));
    }

    @Test
    void deleteMissingAnalysisIsIdempotent() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        when(repository.findById("missing")).thenReturn(Optional.empty());

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        VariantAnalysisServiceImpl service = new VariantAnalysisServiceImpl(
                repository,
                mock(VariantEnhancementRepository.class),
                mock(VariantAnalysisJobProcessor.class),
                mock(VariantEnhancementProcessor.class),
                properties
        );

        service.delete("missing");

        verify(repository, never()).deleteById("missing");
    }

    private MockMultipartFile vcfFile() {
        return new MockMultipartFile(
                "vcfFile",
                "patient.vcf",
                "text/plain",
                "##fileformat=VCFv4.2\n#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\n"
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile sampledVcfFile() {
        return new MockMultipartFile(
                "vcfFile", "patient.vcf", "text/plain",
                ("##fileformat=VCFv4.2\n"
                        + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n")
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private MockMultipartFile compressedVcfFile() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(("##fileformat=VCFv4.2\n"
                    + "##contig=<ID=1,length=248956422>\n"
                    + "#CHROM\tPOS\tID\tREF\tALT\tQUAL\tFILTER\tINFO\tFORMAT\tproband\n")
                    .getBytes(StandardCharsets.UTF_8));
            gzip.write("1\t100\t.\tA\tC\t.\tPASS\t.\tGT\t0/1\n".getBytes(StandardCharsets.UTF_8));
        }
        return new MockMultipartFile("vcfFile", "patient.vcf.gz", "application/gzip", bytes.toByteArray());
    }

    private VariantAnalysisProperties exomiserProperties() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setStorageRoot(tempDir);
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        properties.getExomiser().setEnabled(true);
        properties.getExomiser().setResourceFingerprint("test-exomiser-resource-fingerprint-v1");
        return properties;
    }

    private String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private VariantAnalysisServiceImpl service(VariantAnalysisProperties properties) {
        return new VariantAnalysisServiceImpl(
                mock(VariantAnalysisJobRepository.class),
                mock(VariantEnhancementRepository.class),
                mock(VariantAnalysisJobProcessor.class),
                mock(VariantEnhancementProcessor.class),
                properties
        );
    }

    private VariantAnalysisJob reusableReadyJob(String id,
                                                String fileSha256,
                                                String requestedSampleId,
                                                Path workspace) {
        VariantAnalysisJob job = new VariantAnalysisJob(
                id,
                "patient.vcf",
                fileSha256,
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                requestedSampleId,
                workspace,
                workspace.resolve("upload.vcf")
        );
        job.complete("sample", false, "COPY_ONLY", null, Collections.emptyList(), Collections.emptyList());
        return job;
    }

    private PhenotypeAwareSummary phenotypeSummary(VariantAnalysisProperties properties,
                                                    java.util.List<String> hpoTerms) {
        PhenotypeAwareSummary normalized = PhenotypeInputNormalizer.summarize(hpoTerms);
        return normalized.withGenePriorSummary(
                GenePhenotypePriorProviderFactory.create(properties).summarize(normalized)
        );
    }

    private Path fixturePath() throws Exception {
        URL resource = getClass().getResource("/variantanalysis/phenotype-prior/mock-gene-prior.tsv");
        assertNotNull(resource);
        return Paths.get(resource.toURI());
    }

    private Path writePhen2GeneImport() throws Exception {
        Path importFile = tempDir.resolve("phen2gene-import.tsv");
        String content = "hpo_terms\tgene_symbol\tgene_id\tphenotype_score\tphenotype_rank\t"
                + "source\tsource_version\tmatched_hpo_terms\tevidence\tstatus\n"
                + "HP:0001250\tMECP2\t4204\t0.89883\t4\tPHEN2GENE\tphen2gene-import-v1\t"
                + "HP:0001250\tPhen2Gene candidate gene prior\tSeedGene\n";
        Files.write(importFile, content.getBytes(StandardCharsets.UTF_8));
        return importFile;
    }
}
