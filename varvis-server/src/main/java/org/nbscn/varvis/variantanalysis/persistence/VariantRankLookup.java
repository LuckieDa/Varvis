package org.nbscn.varvis.variantanalysis.persistence;

import org.nbscn.varvis.variantanalysis.ranking.RankStatus;

/** Result of a bounded, assembly-aware lookup in the complete persisted ranking universe. */
public final class VariantRankLookup {

    private final RankStatus status;
    private final Integer rank;
    private final int rankingUniverseSize;
    private final int scannedCount;
    private final PersistedRankingCandidate candidate;

    public VariantRankLookup(RankStatus status,
                             Integer rank,
                             int rankingUniverseSize,
                             int scannedCount,
                             PersistedRankingCandidate candidate) {
        this.status = status;
        this.rank = status == RankStatus.RANKED ? rank : null;
        this.rankingUniverseSize = rankingUniverseSize;
        this.scannedCount = scannedCount;
        this.candidate = candidate;
    }

    public RankStatus getStatus() {
        return status;
    }

    public Integer getRank() {
        return rank;
    }

    public int getRankingUniverseSize() {
        return rankingUniverseSize;
    }

    public int getScannedCount() {
        return scannedCount;
    }

    public PersistedRankingCandidate getCandidate() {
        return candidate;
    }
}
