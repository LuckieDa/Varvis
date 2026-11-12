package org.nbscn.varvis.variantanalysis.persistence;

import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;

import java.util.List;

/**
 * Reads a truth variant's rank without using UI pages, CSV export, or evidence payloads.
 */
public class VariantAnalysisRankReader {

    private static final int DEFAULT_PAGE_SIZE = 500;

    private final VariantAnalysisJobRepository repository;
    private final int pageSize;

    public VariantAnalysisRankReader(VariantAnalysisJobRepository repository) {
        this(repository, DEFAULT_PAGE_SIZE);
    }

    public VariantAnalysisRankReader(VariantAnalysisJobRepository repository, int pageSize) {
        this.repository = repository;
        this.pageSize = pageSize > 0 ? pageSize : DEFAULT_PAGE_SIZE;
    }

    public VariantRankLookup find(String analysisId, NormalizedVariantKey truthKey) {
        if (repository == null || !hasText(analysisId) || truthKey == null) {
            return new VariantRankLookup(RankStatus.RANK_UNAVAILABLE, null, 0, 0, null);
        }
        final int universeSize;
        try {
            universeSize = repository.countInterpretations(analysisId, VariantInterpretationView.ALL);
        } catch (RuntimeException unavailable) {
            return new VariantRankLookup(RankStatus.RANK_UNAVAILABLE, null, 0, 0, null);
        }
        if (universeSize < 0) {
            return new VariantRankLookup(RankStatus.RANK_UNAVAILABLE, null, universeSize, 0, null);
        }
        PersistedRankingCandidate match = null;
        Integer matchRank = null;
        int scanned = 0;
        for (int offset = 0; offset < universeSize; offset += pageSize) {
            List<PersistedRankingCandidate> page;
            try {
                page = repository.findRankingPage(analysisId, offset, pageSize);
            } catch (RuntimeException unavailable) {
                return new VariantRankLookup(RankStatus.RANK_UNAVAILABLE, null, universeSize, scanned, match);
            }
            if (page == null) {
                return new VariantRankLookup(RankStatus.RANK_UNAVAILABLE, null, universeSize, scanned, match);
            }
            if (page.isEmpty()) {
                return new VariantRankLookup(RankStatus.RANK_UNAVAILABLE, null, universeSize, scanned, match);
            }
            for (int index = 0; index < page.size(); index++) {
                PersistedRankingCandidate candidate = page.get(index);
                scanned++;
                if (candidate == null || candidate.getVariantOrder() == null) {
                    return new VariantRankLookup(RankStatus.RANK_UNAVAILABLE, null, universeSize, scanned, match);
                }
                if (truthKey.equals(candidate.getKey())) {
                    if (match != null) {
                        return new VariantRankLookup(RankStatus.RANK_UNAVAILABLE, null, universeSize, scanned, null);
                    }
                    match = candidate;
                    matchRank = offset + index + 1;
                }
            }
            if (page.size() < pageSize && scanned < universeSize) {
                return new VariantRankLookup(RankStatus.RANK_UNAVAILABLE, null, universeSize, scanned, match);
            }
        }
        if (match == null) {
            return new VariantRankLookup(
                    RankStatus.NOT_IN_RANKING_UNIVERSE,
                    null,
                    universeSize,
                    scanned,
                    null
            );
        }
        return new VariantRankLookup(RankStatus.RANKED, matchRank, universeSize, scanned, match);
    }

    public int getPageSize() {
        return pageSize;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
