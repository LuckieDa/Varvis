package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisEngine;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.BuildConversionResult;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.engine.EngineExecutionResult;
import org.nbscn.varvis.variantanalysis.engine.ExomiserEngineAdapter;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.trio.RequestBackedTrioPipelineInputProvider;
import org.nbscn.varvis.variantanalysis.trio.TrioMember;
import org.nbscn.varvis.variantanalysis.trio.TrioPipelineInput;
import org.nbscn.varvis.variantanalysis.trio.TrioRole;
import org.nbscn.varvis.variantanalysis.trio.TrioSex;
import org.nbscn.varvis.variantanalysis.trio.TrioVcfSampleMapping;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantAnalysisJobProcessorExomiserTest {

    @TempDir
    Path temporary;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"COMPLETED", "PARTIAL", "SAVE_FAILED", "PROVIDER_FAILED"})
    void publicationAnnotationRecordsSummaryOnlyAfterPersistence(String outcome) throws Exception {
        var repository=mock(VariantAnalysisJobRepository.class);var job=job();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        var properties=new VariantAnalysisProperties();properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        var converter=mock(VcfBuildConverter.class);
        when(converter.convert(any(),any(),any(),any())).thenReturn(new BuildConversionResult(job.getUploadedFile(),false,"NONE",List.of()));
        var adapter=mock(ExomiserEngineAdapter.class);
        when(adapter.prepare(job,job.getUploadedFile(),GenomeAssembly.GRCh38)).thenReturn(EngineExecutionResult.completed(
                EngineMetadata.exomiserRanked("15.1.0","fingerprint","output-sha","EXOMISER_G0"),List.of(),temporary,repeat('c'),repeat('d'),0));
        var interpretation=mock(VariantInterpretationService.class);
        when(interpretation.enrichEvidenceOnly(any(),any())).thenReturn(List.of());
        var candidate=mock(VariantInterpretation.class);when(candidate.getTranscriptAnnotations()).thenReturn(List.of());
        when(repository.findInterpretations(job.getId(),0,org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources.MAX_CANDIDATES)).thenReturn(List.of(candidate));
        var annotations=mock(VepTranscriptAnnotationEnrichmentService.class);
        var summary=new VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary(
                outcome,1,1,outcome.equals("PARTIAL")?0:1,0,0,3L,List.of());
        var result=new VepTranscriptAnnotationEnrichmentService.VepAnnotationResult(java.util.Map.of(),summary);
        if(outcome.equals("PROVIDER_FAILED")) when(annotations.annotateCandidates(any(),any(),any(),any(),any(GenomeAssembly.class)))
                .thenThrow(new IllegalStateException("synthetic failure"));
        else when(annotations.annotateCandidates(any(),any(),any(),any(),any(GenomeAssembly.class))).thenReturn(result);
        var sources=mock(org.nbscn.varvis.variantanalysis.engine.AcmgExecutionSources.class);
        if(outcome.equals("SAVE_FAILED")) org.mockito.Mockito.doThrow(new IllegalStateException("synthetic save failure"))
                .when(sources).savePublicationAnnotations(any(),any(),any());
        var processor=new VariantAnalysisJobProcessor(repository,converter,mock(VcfNormalizer.class),mock(VcfQcService.class),interpretation,properties,annotations);
        org.springframework.test.util.ReflectionTestUtils.setField(processor,"acmgBusinessEnabled",true);
        processor.setExomiserEngineAdapter(adapter);processor.setAcmgExecutionSources(sources);processor.process(job.getId());
        assertEquals(VariantAnalysisStatus.READY,job.getStatus(),"Optional annotation failure must not break base analysis");
        if(outcome.endsWith("FAILED")) {
            verify(annotations,never()).appendSummaryEvent(any(),any());
            verify(repository).appendEvent(org.mockito.ArgumentMatchers.eq(job.getId()),org.mockito.ArgumentMatchers.eq(VariantAnalysisStatus.READY),
                    org.mockito.ArgumentMatchers.startsWith("VEP transcript annotation FAILED:"));
        } else {
            var ordered=org.mockito.Mockito.inOrder(sources,annotations);
            ordered.verify(sources).savePublicationAnnotations(job.getId(),List.of(candidate),java.util.Map.of());
            ordered.verify(annotations).appendSummaryEvent(job.getId(),summary);
        }
    }

    @Test
    void dispatchesDefaultProductJobsToExomiserWithoutCallingLegacyServices() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        VariantAnalysisJob job = job();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        ExomiserEngineAdapter adapter = mock(ExomiserEngineAdapter.class);
        VcfBuildConverter converter = mock(VcfBuildConverter.class);
        when(converter.convert(job.getUploadedFile(), job.getWorkspace(), GenomeAssembly.GRCh38, GenomeAssembly.GRCh38))
                .thenReturn(new BuildConversionResult(job.getUploadedFile(), false, "NONE", Collections.emptyList()));
        when(adapter.prepare(job, job.getUploadedFile(), GenomeAssembly.GRCh38)).thenReturn(EngineExecutionResult.completed(
                EngineMetadata.exomiserRanked("15.1.0", "fingerprint", "output-sha", "EXOMISER_G0"),
                Collections.emptyList(), temporary, repeat('c'), repeat('d'), 0));

        VariantInterpretationService interpretationService = mock(VariantInterpretationService.class);
        when(interpretationService.enrichEvidenceOnly(
                org.mockito.ArgumentMatchers.<List<VariantInterpretation>>any(),
                any(AnalysisContext.class)
        )).thenAnswer(invocation -> invocation.<List<VariantInterpretation>>getArgument(0));
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository,
                converter,
                mock(VcfNormalizer.class),
                mock(VcfQcService.class),
                interpretationService,
                properties
        );
        processor.setExomiserEngineAdapter(adapter);

        processor.process(job.getId());

        assertEquals(VariantAnalysisStatus.READY, job.getStatus());
        assertEquals(AnalysisEngine.EXOMISER, job.getEngineMetadata().getEngine());
        verify(adapter).prepare(job, job.getUploadedFile(), GenomeAssembly.GRCh38);
        verify(repository, org.mockito.Mockito.atLeast(2)).save(job);
        verify(interpretationService).enrichEvidenceOnly(
                org.mockito.ArgumentMatchers.<List<VariantInterpretation>>any(),
                any(AnalysisContext.class)
        );
        verify(repository).replaceInterpretations(job.getId(), Collections.emptyList());
        verify(repository, never()).appendEvent(job.getId(), VariantAnalysisStatus.BASE_ANNOTATING, "Annotating normalized variants");
    }

    private static String repeat(char value) {
        char[] values = new char[64];
        Arrays.fill(values, value);
        return new String(values);
    }

    @Test
    void reportsAdapterUnavailableInsteadOfFallingBackToLegacyPipeline() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        VariantAnalysisJob job = job();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));

        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository,
                mock(VcfBuildConverter.class),
                mock(VcfNormalizer.class),
                mock(VcfQcService.class),
                mock(VariantInterpretationService.class),
                properties
        );

        processor.process(job.getId());

        assertEquals(VariantAnalysisStatus.FAILED, job.getStatus());
        assertEquals(AnalysisEngine.EXOMISER, job.getEngineMetadata().getEngine());
        assertEquals("EXOMISER_ADAPTER_UNAVAILABLE", job.getErrorMessage());
        verify(repository, never()).replaceInterpretations(any(), any());
    }

    @Test
    void retriesTheFirstDatabaseCheckpointAndAuditEventAfterLongExomiserExecution() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        properties.getPersistence().setRetryAttempts(2);
        properties.getPersistence().setRetryBackoff(java.time.Duration.ZERO);
        VariantAnalysisJob job = job();
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        AtomicBoolean injectedFailure = new AtomicBoolean();
        AtomicBoolean injectedEventFailure = new AtomicBoolean();
        doAnswer(invocation -> {
            VariantAnalysisJob saved = invocation.getArgument(0);
            if (saved.getEngineMetadata() != null
                    && saved.getEngineMetadata().getStatus()
                    == org.nbscn.varvis.variantanalysis.domain.EngineExecutionStatus.RANKED
                    && saved.getStatus() == VariantAnalysisStatus.VALIDATING
                    && injectedFailure.compareAndSet(false, true)) {
                throw new org.springframework.dao.TransientDataAccessResourceException(
                        "synthetic stale pool checkpoint");
            }
            return null;
        }).when(repository).save(job);
        doAnswer(invocation -> {
            String message = invocation.getArgument(2);
            if (message.startsWith("EXOMISER_EXECUTION_PROVENANCE:")
                    && injectedEventFailure.compareAndSet(false, true)) {
                throw new org.springframework.dao.TransientDataAccessResourceException(
                        "synthetic stale pool audit event");
            }
            return null;
        }).when(repository).appendEvent(
                org.mockito.ArgumentMatchers.eq(job.getId()),
                org.mockito.ArgumentMatchers.any(VariantAnalysisStatus.class),
                org.mockito.ArgumentMatchers.anyString());
        VcfBuildConverter converter = mock(VcfBuildConverter.class);
        when(converter.convert(job.getUploadedFile(), job.getWorkspace(), GenomeAssembly.GRCh38, GenomeAssembly.GRCh38))
                .thenReturn(new BuildConversionResult(job.getUploadedFile(), false, "NONE", Collections.emptyList()));
        ExomiserEngineAdapter adapter = mock(ExomiserEngineAdapter.class);
        when(adapter.prepare(job, job.getUploadedFile(), GenomeAssembly.GRCh38)).thenReturn(
                EngineExecutionResult.completed(
                        EngineMetadata.exomiserRanked("15.1.0", "fingerprint", "output-sha", "EXOMISER_G0"),
                        Collections.emptyList(), temporary, repeat('c'), repeat('d'), 0));
        VariantInterpretationService interpretationService = mock(VariantInterpretationService.class);
        when(interpretationService.enrichEvidenceOnly(any(), any(AnalysisContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository, converter, mock(VcfNormalizer.class), mock(VcfQcService.class),
                interpretationService, properties);
        processor.setExomiserEngineAdapter(adapter);

        processor.process(job.getId());

        assertTrue(injectedFailure.get());
        assertTrue(injectedEventFailure.get());
        assertEquals(VariantAnalysisStatus.READY, job.getStatus());
        verify(repository, org.mockito.Mockito.atLeast(5)).save(job);
    }

    @Test
    void convertsGrch37BeforeCallingExomiserAndRecordsPartialLiftover() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        VariantAnalysisJob job = grch37Job();
        Path lifted = temporary.resolve("lifted-grch38.vcf");
        Files.write(lifted, Collections.singletonList("##fileformat=VCFv4.2"));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        VcfBuildConverter converter = mock(VcfBuildConverter.class);
        when(converter.convert(job.getUploadedFile(), job.getWorkspace(), GenomeAssembly.GRCh37, GenomeAssembly.GRCh38))
                .thenReturn(new BuildConversionResult(lifted, true, "Picard LiftoverVcf", Collections.singletonList("lifted"),
                        null, 0, 1L, 1L));
        ExomiserEngineAdapter adapter = mock(ExomiserEngineAdapter.class);
        when(adapter.prepare(job, lifted, GenomeAssembly.GRCh38)).thenReturn(EngineExecutionResult.completed(
                EngineMetadata.exomiserRanked("15.1.0", "fingerprint", "output-sha", "EXOMISER_G0"),
                Collections.emptyList(), temporary, "request-sha", "command-sha", 0));

        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository, converter, mock(VcfNormalizer.class), mock(VcfQcService.class),
                mock(VariantInterpretationService.class), properties
        );
        processor.setExomiserEngineAdapter(adapter);
        processor.process(job.getId());

        assertEquals(VariantAnalysisStatus.READY, job.getStatus());
        assertEquals("PICARD;LIFTOVER;SOURCE=1;LIFTED=1;REJECTED=0", job.getNormalizationEngine());
        assertTrue(job.isNormalizationApplied());
        verify(adapter).prepare(job, lifted, GenomeAssembly.GRCh38);

        VariantAnalysisJob rejected = grch37Job("rejected");
        when(repository.findById(rejected.getId())).thenReturn(Optional.of(rejected));
        when(converter.convert(rejected.getUploadedFile(), rejected.getWorkspace(), GenomeAssembly.GRCh37, GenomeAssembly.GRCh38))
                .thenReturn(new BuildConversionResult(lifted, true, "Picard LiftoverVcf", Collections.emptyList(),
                        temporary.resolve("rejected.vcf"), 1, 100L, 99L));
        when(adapter.prepare(rejected, lifted, GenomeAssembly.GRCh38)).thenReturn(EngineExecutionResult.completed(
                EngineMetadata.exomiserRanked("15.1.0", "fingerprint", "output-sha", "EXOMISER_G0"),
                Collections.emptyList(), temporary, "request-sha", "command-sha", 0));
        processor.process(rejected.getId());

        assertEquals(VariantAnalysisStatus.READY, rejected.getStatus());
        assertEquals("PICARD;PARTIAL_LIFTOVER:1;SOURCE=100;LIFTED=99", rejected.getNormalizationEngine());
        verify(adapter).prepare(rejected, lifted, GenomeAssembly.GRCh38);
    }

    @Test
    void stopsBeforeExomiserWhenLiftoverProducesNoRecords() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        VariantAnalysisJob job = grch37Job("no-lifted-records");
        Path lifted = temporary.resolve("empty-lifted-grch38.vcf");
        Files.write(lifted, Collections.singletonList("##fileformat=VCFv4.2"));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        VcfBuildConverter converter = mock(VcfBuildConverter.class);
        when(converter.convert(job.getUploadedFile(), job.getWorkspace(), GenomeAssembly.GRCh37, GenomeAssembly.GRCh38))
                .thenReturn(new BuildConversionResult(lifted, true, "Picard LiftoverVcf", Collections.emptyList(),
                        temporary.resolve("rejected.vcf"), 1, 1L, 0L));
        ExomiserEngineAdapter adapter = mock(ExomiserEngineAdapter.class);
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(
                repository, converter, mock(VcfNormalizer.class), mock(VcfQcService.class),
                mock(VariantInterpretationService.class), properties
        );
        processor.setExomiserEngineAdapter(adapter);

        processor.process(job.getId());

        assertEquals(VariantAnalysisStatus.FAILED, job.getStatus());
        assertEquals("EXOMISER_LIFTOVER_NO_RECORDS:SOURCE=1;REJECTED=1", job.getErrorMessage());
        verify(adapter, never()).prepare(any(), any(), any());
    }

    @Test
    void dispatchesExplicitJointTrioToExomiserWithoutLegacyEvidencePipeline() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        VariantAnalysisJob job = new VariantAnalysisJob("trio", "input.vcf", "sha", GenomeAssembly.GRCh38,
                AnalysisMode.WES, "proband", temporary, temporary.resolve("input.vcf"));
        Files.write(job.getUploadedFile(), Collections.singletonList("##fileformat=VCFv4.2"));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        VcfBuildConverter converter = mock(VcfBuildConverter.class);
        when(converter.convert(job.getUploadedFile(), job.getWorkspace(), GenomeAssembly.GRCh38, GenomeAssembly.GRCh38))
                .thenReturn(new BuildConversionResult(job.getUploadedFile(), false, "NONE", Collections.emptyList()));
        RequestBackedTrioPipelineInputProvider provider = new RequestBackedTrioPipelineInputProvider();
        TrioPipelineInput trio = TrioPipelineInput.jointVcf(job.getUploadedFile(),
                new TrioVcfSampleMapping("proband", "father", "mother"), Arrays.asList(
                        new TrioMember(TrioRole.PROBAND, "proband", TrioSex.FEMALE, true),
                        new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                        new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false)));
        provider.register(job.getId(), trio);
        ExomiserEngineAdapter adapter = mock(ExomiserEngineAdapter.class);
        when(adapter.prepare(job, job.getUploadedFile(), GenomeAssembly.GRCh38, trio)).thenReturn(EngineExecutionResult.completed(
                EngineMetadata.exomiserRanked("15.1.0", "fingerprint", "output-sha", "EXOMISER_G0"),
                Collections.emptyList(), temporary, "request-sha", "command-sha", "ped-sha", 0));
        VariantInterpretationService interpretationService = mock(VariantInterpretationService.class);
        when(interpretationService.enrichEvidenceOnly(any(), any(AnalysisContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(repository, converter,
                mock(VcfNormalizer.class), mock(VcfQcService.class), interpretationService, properties,
                null, null, null, null, null, provider);
        processor.setExomiserEngineAdapter(adapter);

        processor.process(job.getId());

        assertEquals(VariantAnalysisStatus.READY, job.getStatus());
        verify(adapter).prepare(job, job.getUploadedFile(), GenomeAssembly.GRCh38, trio);
        assertFalse(provider.inputFor(job).isPresent());
    }

    @Test
    void convertsGrch37JointTrioBeforePassingTheExactMappingToExomiser() throws Exception {
        VariantAnalysisJobRepository repository = mock(VariantAnalysisJobRepository.class);
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.setDefaultEngine(AnalysisEngine.EXOMISER);
        VariantAnalysisJob job = grch37Job("grch37-trio");
        Path lifted = temporary.resolve("grch37-trio-lifted-grch38.vcf");
        Files.write(lifted, Collections.singletonList("##fileformat=VCFv4.2"));
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
        VcfBuildConverter converter = mock(VcfBuildConverter.class);
        when(converter.convert(job.getUploadedFile(), job.getWorkspace(), GenomeAssembly.GRCh37, GenomeAssembly.GRCh38))
                .thenReturn(new BuildConversionResult(lifted, true, "Picard LiftoverVcf", Collections.emptyList(),
                        null, 0, 1L, 1L));
        TrioPipelineInput trio = TrioPipelineInput.jointVcf(job.getUploadedFile(),
                new TrioVcfSampleMapping("proband", "father", "mother"), Arrays.asList(
                        new TrioMember(TrioRole.PROBAND, "proband", TrioSex.FEMALE, true),
                        new TrioMember(TrioRole.FATHER, "father", TrioSex.MALE, false),
                        new TrioMember(TrioRole.MOTHER, "mother", TrioSex.FEMALE, false)));
        RequestBackedTrioPipelineInputProvider provider = new RequestBackedTrioPipelineInputProvider();
        provider.register(job.getId(), trio);
        ExomiserEngineAdapter adapter = mock(ExomiserEngineAdapter.class);
        when(adapter.prepare(job, lifted, GenomeAssembly.GRCh38, trio)).thenReturn(EngineExecutionResult.completed(
                EngineMetadata.exomiserRanked("15.1.0", "fingerprint", "output-sha", "EXOMISER_G0"),
                Collections.emptyList(), temporary, "request-sha", "command-sha", "ped-sha", 0));
        VariantInterpretationService interpretationService = mock(VariantInterpretationService.class);
        when(interpretationService.enrichEvidenceOnly(any(), any(AnalysisContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        VariantAnalysisJobProcessor processor = new VariantAnalysisJobProcessor(repository, converter,
                mock(VcfNormalizer.class), mock(VcfQcService.class), interpretationService, properties,
                null, null, null, null, null, provider);
        processor.setExomiserEngineAdapter(adapter);

        processor.process(job.getId());

        assertEquals(VariantAnalysisStatus.READY, job.getStatus());
        verify(adapter).prepare(job, lifted, GenomeAssembly.GRCh38, trio);
        assertFalse(provider.inputFor(job).isPresent());
    }

    private VariantAnalysisJob job() throws Exception {
        Path input = temporary.resolve("input.vcf");
        Files.write(input, Collections.singletonList("##fileformat=VCFv4.2"));
        return new VariantAnalysisJob("job", "input.vcf", "sha", GenomeAssembly.GRCh38,
                AnalysisMode.WES, "sample", temporary, input);
    }

    private VariantAnalysisJob grch37Job() throws Exception {
        return grch37Job("grch37");
    }

    private VariantAnalysisJob grch37Job(String id) throws Exception {
        Path workspace = temporary.resolve(id);
        Files.createDirectories(workspace);
        Path input = workspace.resolve("input.vcf");
        Files.write(input, Collections.singletonList("##fileformat=VCFv4.2"));
        return new VariantAnalysisJob(id, "input.vcf", "sha", GenomeAssembly.GRCh37,
                AnalysisMode.WES, "sample", workspace, input);
    }
}
