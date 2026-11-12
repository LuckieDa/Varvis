package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.VariantEnhancementTask;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.nbscn.varvis.variantanalysis.persistence.VariantEnhancementRepository;
import org.nbscn.varvis.variantanalysis.provider.AlphaGenomeProvider;
import org.nbscn.varvis.variantanalysis.ranking.VariantRankingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class VariantEnhancementProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(VariantEnhancementProcessor.class);

    private final VariantAnalysisJobRepository jobRepository;
    private final VariantEnhancementRepository enhancementRepository;
    private final AlphaGenomeProvider alphaGenomeProvider;
    private final VariantRankingService rankingService;

    public VariantEnhancementProcessor(VariantAnalysisJobRepository jobRepository,
                                       VariantEnhancementRepository enhancementRepository,
                                       AlphaGenomeProvider alphaGenomeProvider,
                                       VariantRankingService rankingService) {
        this.jobRepository = jobRepository;
        this.enhancementRepository = enhancementRepository;
        this.alphaGenomeProvider = alphaGenomeProvider;
        this.rankingService = rankingService;
    }

    @Async("variantAnalysisExecutor")
    public void process(String enhancementId) {
        VariantEnhancementTask task = enhancementRepository.findById(enhancementId).orElse(null);
        if (task == null) {
            return;
        }
        try {
            List<VariantInterpretation> interpretations = jobRepository.findInterpretations(
                    task.getAnalysisId(),
                    0,
                    jobRepository.countInterpretations(task.getAnalysisId())
            );
            interpretations.sort(Comparator.comparing(
                    variant -> variant == null ? null : variant.getRankingScore(),
                    Comparator.nullsLast(Comparator.reverseOrder())
            ));
            List<VariantInterpretation> candidates = selectCandidates(
                    interpretations,
                    task.getRequestedCandidateLimit()
            );
            task.running(candidates.size());
            enhancementRepository.save(task);

            int completed = 0;
            int unavailable = 0;
            for (VariantInterpretation candidate : candidates) {
                EvidenceItem evidence = alphaGenomeProvider.enhance(candidate);
                candidate.replaceEvidence(alphaGenomeProvider.source(), evidence);
                if ("APPLICABLE".equals(evidence.getApplicability())) {
                    completed++;
                } else {
                    unavailable++;
                }
            }
            rankingService.rank(interpretations);
            jobRepository.replaceInterpretations(task.getAnalysisId(), interpretations);
            task.complete(completed, unavailable);
            enhancementRepository.save(task);
        } catch (Exception ex) {
            LOGGER.warn("Variant enhancement task {} failed: {}", enhancementId, rootMessage(ex));
            task.fail(rootMessage(ex));
            enhancementRepository.save(task);
        }
    }

    private List<VariantInterpretation> selectCandidates(List<VariantInterpretation> interpretations, int limit) {
        List<VariantInterpretation> candidates = new ArrayList<VariantInterpretation>();
        for (VariantInterpretation interpretation : interpretations) {
            if (alphaGenomeProvider.isEligible(interpretation)
                    && alphaGenomeProvider.hasCaddPrescreenScore(interpretation)) {
                candidates.add(interpretation);
                if (candidates.size() >= limit) {
                    break;
                }
            }
        }
        return candidates;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
