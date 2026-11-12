package org.nbscn.varvis.variantanalysis.dto;

import java.time.Instant;
import java.util.List;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;

/** Exact-file history discovery only; contains no current-resource reuse approval. */
public record VariantAnalysisHistoryResponse(String matchBasis, boolean historyOnly, List<Match> matches) {
    public static VariantAnalysisHistoryResponse from(List<VariantAnalysisJob> jobs) {
        return new VariantAnalysisHistoryResponse("EXACT_FILE_SHA256", true, jobs.stream().map(job ->
                new Match(job.getId(), job.getStatus(), job.getGenomeAssembly().name(), job.getAnalysisMode().name(),
                        job.getRequestedSampleId(), job.getSelectedSampleId(), job.getCreatedAt(), job.getUpdatedAt())).toList());
    }

    public record Match(String id, VariantAnalysisStatus status, String genomeAssembly, String analysisMode,
                        String requestedSampleId, String selectedSampleId, Instant createdAt, Instant updatedAt) { }
}
