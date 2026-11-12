package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EngineMetadata;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisDiagnosticResponse;

import java.nio.file.Paths;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class VariantAnalysisDiagnosticAssemblerTest {

    private final VariantAnalysisDiagnosticAssembler assembler =
            new VariantAnalysisDiagnosticAssembler();

    @Test
    void mapsLifecycleWithoutFailureDetails() {
        VariantAnalysisJob job = job();

        VariantAnalysisDiagnosticResponse uploaded = assembler.assemble(job);
        assertEquals(VariantAnalysisDiagnosticResponse.RunState.WAITING, uploaded.getRunState());
        assertEquals(VariantAnalysisDiagnosticResponse.Stage.UPLOADED, uploaded.getStage());
        assertEquals(VariantAnalysisDiagnosticResponse.FailureCode.NONE, uploaded.getFailureCode());

        job.transitionTo(VariantAnalysisStatus.RANKING);
        VariantAnalysisDiagnosticResponse running = assembler.assemble(job);
        assertEquals(VariantAnalysisDiagnosticResponse.RunState.RUNNING, running.getRunState());
        assertEquals(VariantAnalysisDiagnosticResponse.Stage.RANKING, running.getStage());

        job.transitionTo(VariantAnalysisStatus.READY);
        VariantAnalysisDiagnosticResponse ready = assembler.assemble(job);
        assertEquals(VariantAnalysisDiagnosticResponse.RunState.SUCCEEDED, ready.getRunState());
        assertEquals(VariantAnalysisDiagnosticResponse.NextAction.REVIEW_RESULTS, ready.getNextAction());
    }

    @Test
    void acceptsOnlyExactKnownPrefixAndDropsAllSuffixContent() throws Exception {
        VariantAnalysisJob job = job();
        job.fail("EXOMISER_TIMEOUT:D:\\secret\\patient-7.vcf sample=PROBAND-7 https://private.invalid");

        VariantAnalysisDiagnosticResponse response = assembler.assemble(job);
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(response);

        assertEquals(VariantAnalysisDiagnosticResponse.FailureCode.ENGINE_TIMEOUT,
                response.getFailureCode());
        assertEquals(VariantAnalysisDiagnosticResponse.RetryDisposition.RESUBMISSION_ALLOWED,
                response.getRetryDisposition());
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("patient-7"));
        assertFalse(json.contains("PROBAND-7"));
        assertFalse(json.contains("private.invalid"));
        assertFalse(json.contains("errorMessage"));
        assertFalse(json.contains("originalFileName"));
    }

    @Test
    void serializedContractContainsOnlyTheExplicitSafeFields() throws Exception {
        VariantAnalysisJob job = job();
        job.fail("EXOMISER_TIMEOUT:D:\\secret\\patient-7.vcf sample=PROBAND-7");

        Iterator<String> names = new ObjectMapper().findAndRegisterModules()
                .readTree(new ObjectMapper().findAndRegisterModules().writeValueAsString(assembler.assemble(job)))
                .fieldNames();
        Set<String> actual = new LinkedHashSet<String>();
        names.forEachRemaining(actual::add);

        assertEquals(Set.of(
                "schemaVersion",
                "runState",
                "stage",
                "failureCode",
                "failureCategory",
                "nextAction",
                "retryDisposition",
                "createdAt",
                "updatedAt"
        ), actual);
    }

    @Test
    void nearMatchAndUnstructuredFailuresFailClosed() {
        VariantAnalysisJob nearMatch = job();
        nearMatch.fail("EXOMISER_TIMEOUT_EXTRA:D:\\secret\\patient.vcf");
        VariantAnalysisDiagnosticResponse nearMatchResponse = assembler.assemble(nearMatch);

        VariantAnalysisJob unstructured = job();
        unstructured.fail("java.io.IOException at D:\\secret\\patient.vcf");
        VariantAnalysisDiagnosticResponse unstructuredResponse = assembler.assemble(unstructured);

        assertEquals(VariantAnalysisDiagnosticResponse.FailureCode.UNKNOWN_FAILURE,
                nearMatchResponse.getFailureCode());
        assertEquals(VariantAnalysisDiagnosticResponse.FailureCode.UNKNOWN_FAILURE,
                unstructuredResponse.getFailureCode());
        assertEquals(VariantAnalysisDiagnosticResponse.NextAction.CONTACT_OPERATOR,
                unstructuredResponse.getNextAction());
    }

    @Test
    void usesOnlyWhitelistedEngineFailureWhenJobFailureIsAbsent() {
        VariantAnalysisJob job = job();
        EngineMetadata metadata = EngineMetadata.exomiserFailed(
                "EXOMISER_TIMEOUT:/private/patient-7.vcf",
                "synthetic-version",
                "synthetic-fingerprint"
        );
        job.applyEngineMetadata(metadata);
        job.fail(null);

        VariantAnalysisDiagnosticResponse response = assembler.assemble(job);

        assertEquals(VariantAnalysisDiagnosticResponse.FailureCode.ENGINE_TIMEOUT,
                response.getFailureCode());
        assertEquals(VariantAnalysisDiagnosticResponse.FailureCategory.TIMEOUT,
                response.getFailureCategory());
    }

    @Test
    void unknownJobFailureDoesNotFallThroughToEngineMetadata() {
        VariantAnalysisJob job = job();
        job.applyEngineMetadata(EngineMetadata.exomiserFailed(
                "EXOMISER_TIMEOUT:/private/patient-7.vcf",
                "synthetic-version",
                "synthetic-fingerprint"
        ));
        job.fail("unclassified failure at D:\\private\\patient-7.vcf");

        VariantAnalysisDiagnosticResponse response = assembler.assemble(job);

        assertEquals(VariantAnalysisDiagnosticResponse.FailureCode.UNKNOWN_FAILURE,
                response.getFailureCode());
        assertEquals(VariantAnalysisDiagnosticResponse.FailureCategory.UNKNOWN,
                response.getFailureCategory());
    }

    @Test
    void recognizesExactRestartMarker() {
        VariantAnalysisJob job = job();
        job.fail("Application restarted before analysis completed");

        VariantAnalysisDiagnosticResponse response = assembler.assemble(job);

        assertEquals(VariantAnalysisDiagnosticResponse.FailureCode.APPLICATION_RESTARTED,
                response.getFailureCode());
        assertEquals(VariantAnalysisDiagnosticResponse.NextAction.RESUBMIT_ANALYSIS,
                response.getNextAction());
    }

    @Test
    void classifiesDirectTsvShapeFailureAsInvalidEngineOutput() {
        VariantAnalysisJob job = job();
        job.fail("TSV_COLUMN_COUNT_MISMATCH");

        VariantAnalysisDiagnosticResponse response = assembler.assemble(job);

        assertEquals(VariantAnalysisDiagnosticResponse.FailureCode.ENGINE_OUTPUT_INVALID,
                response.getFailureCode());
        assertEquals(VariantAnalysisDiagnosticResponse.FailureCategory.OUTPUT,
                response.getFailureCategory());
        assertEquals(VariantAnalysisDiagnosticResponse.NextAction.CONTACT_OPERATOR,
                response.getNextAction());
    }

    @Test
    void assemblingDoesNotMutateJobAndReturnsPersistedTimes() {
        VariantAnalysisJob job = job();
        job.fail("INVALID_INPUT:patient=PROBAND-7");
        Instant createdAt = job.getCreatedAt();
        Instant updatedAt = job.getUpdatedAt();
        VariantAnalysisStatus status = job.getStatus();
        String error = job.getErrorMessage();
        EngineMetadata metadata = job.getEngineMetadata();

        VariantAnalysisDiagnosticResponse response = assembler.assemble(job);

        assertEquals(createdAt, response.getCreatedAt());
        assertEquals(updatedAt, response.getUpdatedAt());
        assertEquals(status, job.getStatus());
        assertEquals(error, job.getErrorMessage());
        assertSame(metadata, job.getEngineMetadata());
        assertEquals(updatedAt, job.getUpdatedAt());
    }

    private VariantAnalysisJob job() {
        return new VariantAnalysisJob(
                "analysis-1",
                "patient-7.vcf",
                "synthetic-sha256",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "PROBAND-7",
                Paths.get("synthetic-workspace"),
                Paths.get("synthetic-upload.vcf")
        );
    }
}
