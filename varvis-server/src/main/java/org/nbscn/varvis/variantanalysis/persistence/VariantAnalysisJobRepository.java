package org.nbscn.varvis.variantanalysis.persistence;

import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface VariantAnalysisJobRepository {

    /** Read-only prerequisite check; non-JDBC repositories have no SQL schema. */
    default void verifyPersistenceSchema() {
    }

    void save(VariantAnalysisJob job);

    Optional<VariantAnalysisJob> findById(String id);

    /** At most 20 recent saved jobs for exact file bytes, including active and failed jobs. Read-only history. */
    List<VariantAnalysisJob> findHistoryByFileSha256(String fileSha256);

    /**
     * Returns the newest coarse READY candidate. The service must still verify the complete
     * versioned reuse attestation before restoring this result.
     */
    Optional<VariantAnalysisJob> findReusableReadyJob(String fileSha256,
                                                      String genomeAssembly,
                                                      String analysisMode,
                                                      String requestedSampleId);

    /**
     * Returns coarse READY candidates in newest-first order. This is only a database prefilter;
     * its result set is not an equivalence class and must not drive deletion.
     */
    default List<VariantAnalysisJob> findReusableReadyJobs(String fileSha256,
                                                           String genomeAssembly,
                                                           String analysisMode,
                                                           String requestedSampleId) {
        Optional<VariantAnalysisJob> job = findReusableReadyJob(
                fileSha256,
                genomeAssembly,
                analysisMode,
                requestedSampleId
        );
        return job.isPresent() ? Collections.singletonList(job.get()) : Collections.emptyList();
    }

    List<VariantAnalysisJob> findAll();

    void replaceObservations(String id, List<VariantQcObservation> observations);

    List<VariantQcObservation> findObservations(String id, int offset, int limit);

    int countObservations(String id);

    void replaceInterpretations(String id, List<VariantInterpretation> interpretations);

    default List<VariantInterpretation> findInterpretationSummaries(String id, int offset, int limit) {
        return findInterpretationSummaries(id, offset, limit, VariantInterpretationView.ALL);
    }

    default List<VariantInterpretation> findInterpretationSummaries(String id,
                                                                    int offset,
                                                                    int limit,
                                                                    VariantInterpretationView view) {
        return findInterpretationSummaries(
                id,
                offset,
                limit,
                view,
                VariantInterpretationFilter.empty()
        );
    }

    List<VariantInterpretation> findInterpretationSummaries(String id,
                                                            int offset,
                                                            int limit,
                                                            VariantInterpretationView view,
                                                            VariantInterpretationFilter filter);

    List<VariantInterpretation> findInterpretations(String id, int offset, int limit);

    /**
     * Returns lightweight rows in the formal persisted ranking order. Implementations that do
     * not expose a persisted ranking source must return an empty page rather than inventing rank.
     */
    default List<PersistedRankingCandidate> findRankingPage(String id, int offset, int limit) {
        return Collections.emptyList();
    }

    Optional<VariantInterpretation> findInterpretation(String id, String variantId);

    Optional<VariantInterpretation> findCandidate(String id, String candidateId);

    List<VariantInterpretation> findInterpretationsByVariantId(String id, String variantId);

    void updateTranscriptAnnotations(String id, Map<String, List<TranscriptConsequence>> annotationsByVariantId);

    default int countInterpretations(String id) {
        return countInterpretations(id, VariantInterpretationView.ALL);
    }

    default int countInterpretations(String id, VariantInterpretationView view) {
        return countInterpretations(id, view, VariantInterpretationFilter.empty());
    }

    int countInterpretations(String id, VariantInterpretationView view, VariantInterpretationFilter filter);

    default VariantInterpretationCounts countInterpretationViews(String id) {
        return new VariantInterpretationCounts(
                countInterpretations(id, VariantInterpretationView.CANDIDATES),
                countInterpretations(id, VariantInterpretationView.FREQUENCY_FILTERED),
                countInterpretations(id, VariantInterpretationView.ALL)
        );
    }

    void appendEvent(String id, VariantAnalysisStatus status, String message);

    List<VariantAnalysisEvent> findEvents(String id);

    int failInterruptedJobs();

    void deleteById(String id);
}
