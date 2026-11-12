package org.nbscn.varvis.variantanalysis.benchmark.manifest;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;

@JsonPropertyOrder({
        "truthGene", "truthCanonicalIdentity", "matchedCanonicalIdentity", "matchStatus",
        "assembly", "ambiguityStatus", "rankingContractVersion", "rankStatus", "rank",
        "rankingUniverseSize"
})
public class BenchmarkTruthMatch {

    private String truthGene;
    private NormalizedVariantKey truthCanonicalIdentity;
    private NormalizedVariantKey matchedCanonicalIdentity;
    private String matchStatus;
    private String assembly;
    private String ambiguityStatus;
    private String rankingContractVersion;
    private RankStatus rankStatus;
    private Integer rank;
    private Integer rankingUniverseSize;

    public BenchmarkTruthMatch() {
    }

    public String getTruthGene() { return truthGene; }
    public void setTruthGene(String truthGene) { this.truthGene = truthGene; }
    public NormalizedVariantKey getTruthCanonicalIdentity() { return truthCanonicalIdentity; }
    public void setTruthCanonicalIdentity(NormalizedVariantKey truthCanonicalIdentity) { this.truthCanonicalIdentity = truthCanonicalIdentity; }
    public NormalizedVariantKey getMatchedCanonicalIdentity() { return matchedCanonicalIdentity; }
    public void setMatchedCanonicalIdentity(NormalizedVariantKey matchedCanonicalIdentity) { this.matchedCanonicalIdentity = matchedCanonicalIdentity; }
    public String getMatchStatus() { return matchStatus; }
    public void setMatchStatus(String matchStatus) { this.matchStatus = matchStatus; }
    public String getAssembly() { return assembly; }
    public void setAssembly(String assembly) { this.assembly = assembly; }
    public String getAmbiguityStatus() { return ambiguityStatus; }
    public void setAmbiguityStatus(String ambiguityStatus) { this.ambiguityStatus = ambiguityStatus; }
    public String getRankingContractVersion() { return rankingContractVersion; }
    public void setRankingContractVersion(String rankingContractVersion) { this.rankingContractVersion = rankingContractVersion; }
    public RankStatus getRankStatus() { return rankStatus; }
    public void setRankStatus(RankStatus rankStatus) { this.rankStatus = rankStatus; }
    public Integer getRank() { return rank; }
    public void setRank(Integer rank) { this.rank = rank; }
    public Integer getRankingUniverseSize() { return rankingUniverseSize; }
    public void setRankingUniverseSize(Integer rankingUniverseSize) { this.rankingUniverseSize = rankingUniverseSize; }
}
