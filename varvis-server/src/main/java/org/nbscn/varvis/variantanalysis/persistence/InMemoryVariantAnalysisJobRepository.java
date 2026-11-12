package org.nbscn.varvis.variantanalysis.persistence;

import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.ranking.RankingContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryVariantAnalysisJobRepository implements VariantAnalysisJobRepository {

    private final ConcurrentMap<String, VariantAnalysisJob> jobs =
            new ConcurrentHashMap<String, VariantAnalysisJob>();
    private final ConcurrentMap<String, List<VariantInterpretation>> interpretations =
            new ConcurrentHashMap<String, List<VariantInterpretation>>();
    private final ConcurrentMap<String, List<VariantAnalysisEvent>> events =
            new ConcurrentHashMap<String, List<VariantAnalysisEvent>>();
    private final AtomicLong eventIds = new AtomicLong(1L);

    @Override
    public void save(VariantAnalysisJob job) {
        jobs.put(job.getId(), job);
    }

    @Override
    public Optional<VariantAnalysisJob> findById(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    @Override
    public Optional<VariantAnalysisJob> findReusableReadyJob(String fileSha256,
                                                            String genomeAssembly,
                                                            String analysisMode,
                                                            String requestedSampleId) {
        List<VariantAnalysisJob> reusableJobs = findReusableReadyJobs(
                fileSha256,
                genomeAssembly,
                analysisMode,
                requestedSampleId
        );
        return reusableJobs.isEmpty() ? Optional.empty() : Optional.of(reusableJobs.get(0));
    }

    @Override
    public List<VariantAnalysisJob> findReusableReadyJobs(String fileSha256,
                                                          String genomeAssembly,
                                                          String analysisMode,
                                                          String requestedSampleId) {
        List<VariantAnalysisJob> reusableJobs = new ArrayList<VariantAnalysisJob>();
        for (VariantAnalysisJob job : jobs.values()) {
            if (job.getStatus() != VariantAnalysisStatus.READY
                    || !job.getFileSha256().equals(fileSha256)
                    || !job.getGenomeAssembly().name().equals(genomeAssembly)
                    || !job.getAnalysisMode().name().equals(analysisMode)) {
                continue;
            }
            String currentSample = job.getRequestedSampleId();
            if (requestedSampleId == null ? currentSample == null : requestedSampleId.equals(currentSample)) {
                reusableJobs.add(job);
            }
        }
        reusableJobs.sort((left, right) -> right.getUpdatedAt().compareTo(left.getUpdatedAt()));
        return reusableJobs;
    }

    @Override
    public List<VariantAnalysisJob> findHistoryByFileSha256(String fileSha256) {
        return jobs.values().stream().filter(job -> job.getFileSha256().equals(fileSha256))
                .sorted(java.util.Comparator.comparing((VariantAnalysisJob job) -> job.getCreatedAt())
                        .thenComparing(job -> job.getId()).reversed())
                .limit(20).toList();
    }

    @Override
    public List<VariantAnalysisJob> findAll() {
        return new ArrayList<VariantAnalysisJob>(jobs.values());
    }

    @Override
    public void replaceObservations(String id, List<VariantQcObservation> observations) {
        // The in-memory implementation stores observations directly on the job.
    }

    @Override
    public List<VariantQcObservation> findObservations(String id, int offset, int limit) {
        VariantAnalysisJob job = jobs.get(id);
        if (job == null || offset >= job.getObservations().size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(offset + limit, job.getObservations().size());
        return new ArrayList<VariantQcObservation>(job.getObservations().subList(offset, toIndex));
    }

    @Override
    public int countObservations(String id) {
        VariantAnalysisJob job = jobs.get(id);
        return job == null ? 0 : job.getObservations().size();
    }

    @Override
    public void replaceInterpretations(String id, List<VariantInterpretation> values) {
        interpretations.put(id, ensureUniqueCandidateIds(values));
    }

    private List<VariantInterpretation> ensureUniqueCandidateIds(List<VariantInterpretation> values) {
        List<VariantInterpretation> source = values == null
                ? Collections.<VariantInterpretation>emptyList()
                : values;
        Map<String, Integer> counts = new java.util.LinkedHashMap<String, Integer>();
        for (VariantInterpretation interpretation : source) {
            if (interpretation == null) {
                throw new IllegalArgumentException("INTERPRETATION_REQUIRED");
            }
            counts.put(
                    interpretation.getCandidateId(),
                    counts.getOrDefault(interpretation.getCandidateId(), 0) + 1
            );
        }
        List<VariantInterpretation> normalized = new ArrayList<VariantInterpretation>(source.size());
        for (int index = 0; index < source.size(); index++) {
            VariantInterpretation interpretation = source.get(index);
            if (counts.get(interpretation.getCandidateId()) == 1) {
                normalized.add(interpretation);
                continue;
            }
            if (!"LEGACY".equals(interpretation.getCandidateIdentity().getSource())) {
                throw new IllegalStateException("DUPLICATE_CANDIDATE_ID:" + interpretation.getCandidateId());
            }
            normalized.add(interpretation.withCandidateIdentity(CandidateIdentity.legacy(
                    String.format(java.util.Locale.ROOT, "legacy-v1-row-%010d", index),
                    interpretation.getCanonicalVariantId()
            )));
        }
        return normalized;
    }

    @Override
    public List<VariantInterpretation> findInterpretations(String id, int offset, int limit) {
        List<VariantInterpretation> values = RankingContract.order(
                interpretations.getOrDefault(id, Collections.emptyList())
        );
        if (offset >= values.size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(offset + limit, values.size());
        return new ArrayList<VariantInterpretation>(values.subList(offset, toIndex));
    }

    @Override
    public List<PersistedRankingCandidate> findRankingPage(String id, int offset, int limit) {
        List<VariantInterpretation> original = interpretations.getOrDefault(id, Collections.emptyList());
        List<VariantInterpretation> values = RankingContract.order(original);
        Map<VariantInterpretation, Integer> persistedOrder =
                new IdentityHashMap<VariantInterpretation, Integer>();
        for (int index = 0; index < original.size(); index++) {
            persistedOrder.put(original.get(index), index);
        }
        if (offset >= values.size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(offset + limit, values.size());
        List<PersistedRankingCandidate> result = new ArrayList<PersistedRankingCandidate>();
        for (int index = offset; index < toIndex; index++) {
            result.add(PersistedRankingCandidate.from(values.get(index), persistedOrder.get(values.get(index))));
        }
        return result;
    }

    @Override
    public List<VariantInterpretation> findInterpretationSummaries(
            String id,
            int offset,
        int limit,
        VariantInterpretationView view,
        VariantInterpretationFilter filter) {
        List<VariantInterpretation> values = filteredInterpretations(id, view, filter);
        values = RankingContract.order(values);
        if (offset >= values.size()) {
            return Collections.emptyList();
        }
        int toIndex = Math.min(offset + limit, values.size());
        return new ArrayList<VariantInterpretation>(values.subList(offset, toIndex));
    }

    @Override
    public Optional<VariantInterpretation> findInterpretation(String id, String variantId) {
        List<VariantInterpretation> matching = findInterpretationsByVariantId(id, variantId);
        if (matching.size() > 1) {
            throw new AmbiguousVariantIdException(variantId);
        }
        return matching.isEmpty() ? Optional.empty() : Optional.of(matching.get(0));
    }

    @Override
    public Optional<VariantInterpretation> findCandidate(String id, String candidateId) {
        for (VariantInterpretation interpretation : interpretations.getOrDefault(id, Collections.emptyList())) {
            if (interpretation.getCandidateId().equals(candidateId)) {
                return Optional.of(interpretation);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<VariantInterpretation> findInterpretationsByVariantId(String id, String variantId) {
        List<VariantInterpretation> matching = new ArrayList<VariantInterpretation>();
        for (VariantInterpretation interpretation : interpretations.getOrDefault(id, Collections.emptyList())) {
            if (interpretation.getVariantId().equals(variantId)) {
                matching.add(interpretation);
            }
        }
        return matching;
    }

    @Override
    public void updateTranscriptAnnotations(String id,
                                            Map<String, List<TranscriptConsequence>> annotationsByVariantId) {
        if (annotationsByVariantId == null || annotationsByVariantId.isEmpty()) {
            return;
        }
        for (VariantInterpretation interpretation : interpretations.getOrDefault(id, Collections.emptyList())) {
            List<TranscriptConsequence> annotations = annotationsByVariantId.get(interpretation.getVariantId());
            if (annotations != null) {
                interpretation.applyTranscriptAnnotations(annotations);
            }
        }
    }

    @Override
    public int countInterpretations(String id,
                                    VariantInterpretationView view,
                                    VariantInterpretationFilter filter) {
        return filteredInterpretations(id, view, filter).size();
    }

    @Override
    public VariantInterpretationCounts countInterpretationViews(String id) {
        List<VariantInterpretation> values = interpretations.getOrDefault(id, Collections.emptyList());
        Map<String, Integer> bucketCounts = new java.util.LinkedHashMap<String, Integer>();
        for (VariantAnalysisBucket bucket : VariantAnalysisBucket.values()) {
            bucketCounts.put(bucket.name(), 0);
        }
        int candidates = 0;
        int frequencyFiltered = 0;
        int summaryOnly = 0;
        int fullInterpretation = 0;
        int skipped = 0;
        int largeFileMode = 0;
        int carriedAlt = 0;
        int notCarriedAlt = 0;
        for (VariantInterpretation interpretation : values) {
            boolean populationFiltered = VariantInterpretationFilter.isFilteredCommonTier(
                    interpretation.getPriorityTier()
            );
            boolean primaryFiltered = populationFiltered
                    || "FILTERED_QC".equals(interpretation.getPriorityTier());
            if (!primaryFiltered) {
                candidates++;
            }
            if (populationFiltered) {
                frequencyFiltered++;
            }
            VariantAnalysisBucket bucket = interpretation.getAnalysisBucket() == null
                    ? VariantAnalysisBucket.CANDIDATE
                    : interpretation.getAnalysisBucket();
            bucketCounts.put(bucket.name(), bucketCounts.get(bucket.name()) + 1);
            if (interpretation.isSummaryOnly()) {
                summaryOnly++;
            }
            if (interpretation.isFullInterpretationAvailable()) {
                fullInterpretation++;
            }
            if (interpretation.isExpensiveAnnotationSkipped()) {
                skipped++;
            }
            if (interpretation.isLargeFileMode()) {
                largeFileMode++;
            }
            VariantQcObservation observation = interpretation.getObservation();
            if (observation != null && carriesAlternateAllele(observation.getGenotype())) {
                carriedAlt++;
            } else {
                notCarriedAlt++;
            }
        }
        return new VariantInterpretationCounts(
                candidates,
                frequencyFiltered,
                values.size(),
                bucketCounts,
                summaryOnly,
                fullInterpretation,
                skipped,
                largeFileMode,
                carriedAlt,
                notCarriedAlt,
                true,
                false
        );
    }

    private List<VariantInterpretation> filteredInterpretations(String id,
                                                               VariantInterpretationView view,
                                                               VariantInterpretationFilter filter) {
        VariantInterpretationView normalized = view == null ? VariantInterpretationView.CANDIDATES : view;
        VariantInterpretationFilter normalizedFilter =
                filter == null ? VariantInterpretationFilter.empty() : filter;
        List<VariantInterpretation> values = interpretations.getOrDefault(id, Collections.emptyList());
        List<VariantInterpretation> result = new ArrayList<VariantInterpretation>();
        for (VariantInterpretation interpretation : values) {
            boolean populationFiltered = VariantInterpretationFilter.isFilteredCommonTier(
                    interpretation.getPriorityTier()
            );
            boolean primaryFiltered = populationFiltered
                    || "FILTERED_QC".equals(interpretation.getPriorityTier());
            boolean viewMatches = normalized == VariantInterpretationView.ALL
                    || (normalized == VariantInterpretationView.FREQUENCY_FILTERED && populationFiltered)
                    || (normalized == VariantInterpretationView.CANDIDATES && !primaryFiltered);
            if (viewMatches && normalizedFilter.matches(interpretation)) {
                result.add(interpretation);
            }
        }
        return result;
    }

    private boolean carriesAlternateAllele(String genotype) {
        if (genotype == null || genotype.trim().isEmpty()) {
            return false;
        }
        String[] alleles = genotype.trim().split("[/|]");
        for (String allele : alleles) {
            if (allele == null) {
                continue;
            }
            String value = allele.trim();
            if (value.isEmpty() || ".".equals(value)) {
                continue;
            }
            if (!"0".equals(value)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void appendEvent(String id, VariantAnalysisStatus status, String message) {
        events.computeIfAbsent(id, ignored -> new ArrayList<VariantAnalysisEvent>())
                .add(new VariantAnalysisEvent(eventIds.getAndIncrement(), id, status, message, Instant.now()));
    }

    @Override
    public List<VariantAnalysisEvent> findEvents(String id) {
        return new ArrayList<VariantAnalysisEvent>(events.getOrDefault(id, Collections.emptyList()));
    }

    @Override
    public int failInterruptedJobs() {
        return 0;
    }

    @Override
    public void deleteById(String id) {
        jobs.remove(id);
        interpretations.remove(id);
        events.remove(id);
    }
}
