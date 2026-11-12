package org.nbscn.varvis.variantanalysis.casevalidation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.nbscn.varvis.variantanalysis.ranking.RankStatus;

public class CaseValidationResult {

    private final String caseId;
    private final String runTimestamp;
    private final String gitCommit;
    private final String assembly;
    private final String probandSampleId;
    private final List<String> hpoTerms;
    private final int candidateCountNoHpo;
    private final int candidateCountWithHpo;
    private final int annotatedCandidateCountNoHpo;
    private final int annotatedCandidateCountWithHpo;
    private final int cacheHitCount;
    private final int cacheMissCount;
    private final int providerBatchCount;
    private final String providerName;
    private final String providerVersion;
    private final String cacheVersion;
    private final String optionsHash;
    private final String inputAssembly;
    private final String annotationAssembly;
    private final String resourceAssembly;
    private final String knowledgeAssembly;
    private final boolean liftoverApplied;
    private final String liftoverStatus;
    private final List<TruthVariantResult> truthVariantResults;
    private final List<TruthGeneResult> truthGeneResults;
    private final List<String> warnings;

    public CaseValidationResult(String caseId,
                                String runTimestamp,
                                String gitCommit,
                                String assembly,
                                String probandSampleId,
                                List<String> hpoTerms,
                                int candidateCountNoHpo,
                                int candidateCountWithHpo,
                                int annotatedCandidateCountNoHpo,
                                int annotatedCandidateCountWithHpo,
                                int cacheHitCount,
                                int cacheMissCount,
                                int providerBatchCount,
                                String providerVersion,
                                String optionsHash,
                                List<TruthVariantResult> truthVariantResults,
                                List<TruthGeneResult> truthGeneResults,
                                List<String> warnings) {
        this(
                caseId,
                runTimestamp,
                gitCommit,
                assembly,
                probandSampleId,
                hpoTerms,
                candidateCountNoHpo,
                candidateCountWithHpo,
                annotatedCandidateCountNoHpo,
                annotatedCandidateCountWithHpo,
                cacheHitCount,
                cacheMissCount,
                providerBatchCount,
                null,
                providerVersion,
                null,
                optionsHash,
                null,
                null,
                null,
                null,
                false,
                null,
                truthVariantResults,
                truthGeneResults,
                warnings
        );
    }

    public CaseValidationResult(String caseId,
                                String runTimestamp,
                                String gitCommit,
                                String assembly,
                                String probandSampleId,
                                List<String> hpoTerms,
                                int candidateCountNoHpo,
                                int candidateCountWithHpo,
                                int annotatedCandidateCountNoHpo,
                                int annotatedCandidateCountWithHpo,
                                int cacheHitCount,
                                int cacheMissCount,
                                int providerBatchCount,
                                String providerName,
                                String providerVersion,
                                String cacheVersion,
                                String optionsHash,
                                String inputAssembly,
                                String annotationAssembly,
                                String resourceAssembly,
                                String knowledgeAssembly,
                                boolean liftoverApplied,
                                String liftoverStatus,
                                List<TruthVariantResult> truthVariantResults,
                                List<TruthGeneResult> truthGeneResults,
                                List<String> warnings) {
        this.caseId = caseId;
        this.runTimestamp = runTimestamp;
        this.gitCommit = gitCommit;
        this.assembly = assembly;
        this.probandSampleId = probandSampleId;
        this.hpoTerms = immutableList(hpoTerms);
        this.candidateCountNoHpo = candidateCountNoHpo;
        this.candidateCountWithHpo = candidateCountWithHpo;
        this.annotatedCandidateCountNoHpo = annotatedCandidateCountNoHpo;
        this.annotatedCandidateCountWithHpo = annotatedCandidateCountWithHpo;
        this.cacheHitCount = cacheHitCount;
        this.cacheMissCount = cacheMissCount;
        this.providerBatchCount = providerBatchCount;
        this.providerName = providerName;
        this.providerVersion = providerVersion;
        this.cacheVersion = cacheVersion;
        this.optionsHash = optionsHash;
        this.inputAssembly = inputAssembly;
        this.annotationAssembly = annotationAssembly;
        this.resourceAssembly = resourceAssembly;
        this.knowledgeAssembly = knowledgeAssembly;
        this.liftoverApplied = liftoverApplied;
        this.liftoverStatus = liftoverStatus;
        this.truthVariantResults = immutableList(truthVariantResults);
        this.truthGeneResults = immutableList(truthGeneResults);
        this.warnings = immutableList(warnings);
    }

    public String getCaseId() {
        return caseId;
    }

    public String getRunTimestamp() {
        return runTimestamp;
    }

    public String getGitCommit() {
        return gitCommit;
    }

    public String getAssembly() {
        return assembly;
    }

    public String getProbandSampleId() {
        return probandSampleId;
    }

    public List<String> getHpoTerms() {
        return hpoTerms;
    }

    public int getCandidateCountNoHpo() {
        return candidateCountNoHpo;
    }

    public int getCandidateCountWithHpo() {
        return candidateCountWithHpo;
    }

    public int getAnnotatedCandidateCountNoHpo() {
        return annotatedCandidateCountNoHpo;
    }

    public int getAnnotatedCandidateCountWithHpo() {
        return annotatedCandidateCountWithHpo;
    }

    public int getCacheHitCount() {
        return cacheHitCount;
    }

    public int getCacheMissCount() {
        return cacheMissCount;
    }

    public int getProviderBatchCount() {
        return providerBatchCount;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderVersion() {
        return providerVersion;
    }

    public String getCacheVersion() {
        return cacheVersion;
    }

    public String getOptionsHash() {
        return optionsHash;
    }

    public String getInputAssembly() {
        return inputAssembly;
    }

    public String getAnnotationAssembly() {
        return annotationAssembly;
    }

    public String getResourceAssembly() {
        return resourceAssembly;
    }

    public String getKnowledgeAssembly() {
        return knowledgeAssembly;
    }

    public boolean isLiftoverApplied() {
        return liftoverApplied;
    }

    public String getLiftoverStatus() {
        return liftoverStatus;
    }

    public List<TruthVariantResult> getTruthVariantResults() {
        return truthVariantResults;
    }

    public List<TruthGeneResult> getTruthGeneResults() {
        return truthGeneResults;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    private static <T> List<T> immutableList(List<T> source) {
        return source == null || source.isEmpty()
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(source));
    }

    public static class TruthVariantResult {
        private final String chrom;
        private final int pos;
        private final String ref;
        private final String alt;
        private final String geneSymbol;
        private final boolean exactMatch;
        private final boolean geneLevelFallback;
        private final Integer rankNoHpo;
        private final Integer rankWithHpo;
        private final Integer rankDelta;
        private final RankStatus rankStatusNoHpo;
        private final RankStatus rankStatusWithHpo;
        private final Map<Integer, Boolean> hitNoHpo;
        private final Map<Integer, Boolean> hitWithHpo;
        private final String geneAnnotationStatus;
        private final boolean phenotypeSupportPresent;
        private final Double phenotypeScoreContribution;
        private final String hardGateStatus;
        private final CaseValidationMissReason missReason;
        private final String note;

        public TruthVariantResult(String chrom,
                                  int pos,
                                  String ref,
                                  String alt,
                                  String geneSymbol,
                                  boolean exactMatch,
                                  boolean geneLevelFallback,
                                  Integer rankNoHpo,
                                  Integer rankWithHpo,
                                  Integer rankDelta,
                                  Map<Integer, Boolean> hitNoHpo,
                                  Map<Integer, Boolean> hitWithHpo,
                                  String geneAnnotationStatus,
                                  boolean phenotypeSupportPresent,
                                  Double phenotypeScoreContribution,
                                  String hardGateStatus,
                                  CaseValidationMissReason missReason,
                                  String note) {
            this(
                    chrom, pos, ref, alt, geneSymbol, exactMatch, geneLevelFallback,
                    rankNoHpo, rankWithHpo, rankDelta,
                    rankNoHpo == null ? RankStatus.RANK_UNAVAILABLE : RankStatus.RANKED,
                    rankWithHpo == null ? RankStatus.RANK_UNAVAILABLE : RankStatus.RANKED,
                    hitNoHpo, hitWithHpo, geneAnnotationStatus, phenotypeSupportPresent,
                    phenotypeScoreContribution, hardGateStatus, missReason, note
            );
        }

        public TruthVariantResult(String chrom,
                                  int pos,
                                  String ref,
                                  String alt,
                                  String geneSymbol,
                                  boolean exactMatch,
                                  boolean geneLevelFallback,
                                  Integer rankNoHpo,
                                  Integer rankWithHpo,
                                  Integer rankDelta,
                                  RankStatus rankStatusNoHpo,
                                  RankStatus rankStatusWithHpo,
                                  Map<Integer, Boolean> hitNoHpo,
                                  Map<Integer, Boolean> hitWithHpo,
                                  String geneAnnotationStatus,
                                  boolean phenotypeSupportPresent,
                                  Double phenotypeScoreContribution,
                                  String hardGateStatus,
                                  CaseValidationMissReason missReason,
                                  String note) {
            this.chrom = chrom;
            this.pos = pos;
            this.ref = ref;
            this.alt = alt;
            this.geneSymbol = geneSymbol;
            this.exactMatch = exactMatch;
            this.geneLevelFallback = geneLevelFallback;
            this.rankNoHpo = rankNoHpo;
            this.rankWithHpo = rankWithHpo;
            this.rankDelta = rankDelta;
            this.rankStatusNoHpo = rankStatusNoHpo == null
                    ? RankStatus.RANK_UNAVAILABLE : rankStatusNoHpo;
            this.rankStatusWithHpo = rankStatusWithHpo == null
                    ? RankStatus.RANK_UNAVAILABLE : rankStatusWithHpo;
            this.hitNoHpo = hitNoHpo;
            this.hitWithHpo = hitWithHpo;
            this.geneAnnotationStatus = geneAnnotationStatus;
            this.phenotypeSupportPresent = phenotypeSupportPresent;
            this.phenotypeScoreContribution = phenotypeScoreContribution;
            this.hardGateStatus = hardGateStatus;
            this.missReason = missReason;
            this.note = note;
        }

        public String getChrom() {
            return chrom;
        }

        public int getPos() {
            return pos;
        }

        public String getRef() {
            return ref;
        }

        public String getAlt() {
            return alt;
        }

        public String getGeneSymbol() {
            return geneSymbol;
        }

        public boolean isExactMatch() {
            return exactMatch;
        }

        public boolean isGeneLevelFallback() {
            return geneLevelFallback;
        }

        public Integer getRankNoHpo() {
            return rankNoHpo;
        }

        public Integer getRankWithHpo() {
            return rankWithHpo;
        }

        public Integer getRankDelta() {
            return rankDelta;
        }

        public RankStatus getRankStatusNoHpo() {
            return rankStatusNoHpo;
        }

        public RankStatus getRankStatusWithHpo() {
            return rankStatusWithHpo;
        }

        public Map<Integer, Boolean> getHitNoHpo() {
            return hitNoHpo;
        }

        public Map<Integer, Boolean> getHitWithHpo() {
            return hitWithHpo;
        }

        public String getGeneAnnotationStatus() {
            return geneAnnotationStatus;
        }

        public boolean isPhenotypeSupportPresent() {
            return phenotypeSupportPresent;
        }

        public Double getPhenotypeScoreContribution() {
            return phenotypeScoreContribution;
        }

        public String getHardGateStatus() {
            return hardGateStatus;
        }

        public CaseValidationMissReason getMissReason() {
            return missReason;
        }

        public String getNote() {
            return note;
        }
    }

    public static class TruthGeneResult {
        private final String geneSymbol;
        private final Integer bestRankNoHpo;
        private final Integer bestRankWithHpo;
        private final Integer rankDelta;
        private final RankStatus rankStatusNoHpo;
        private final RankStatus rankStatusWithHpo;
        private final boolean phenotypeSupportPresent;
        private final CaseValidationMissReason missReason;

        public TruthGeneResult(String geneSymbol,
                               Integer bestRankNoHpo,
                               Integer bestRankWithHpo,
                               Integer rankDelta,
                               boolean phenotypeSupportPresent,
                               CaseValidationMissReason missReason) {
            this(
                    geneSymbol, bestRankNoHpo, bestRankWithHpo, rankDelta,
                    bestRankNoHpo == null ? RankStatus.RANK_UNAVAILABLE : RankStatus.RANKED,
                    bestRankWithHpo == null ? RankStatus.RANK_UNAVAILABLE : RankStatus.RANKED,
                    phenotypeSupportPresent, missReason
            );
        }

        public TruthGeneResult(String geneSymbol,
                               Integer bestRankNoHpo,
                               Integer bestRankWithHpo,
                               Integer rankDelta,
                               RankStatus rankStatusNoHpo,
                               RankStatus rankStatusWithHpo,
                               boolean phenotypeSupportPresent,
                               CaseValidationMissReason missReason) {
            this.geneSymbol = geneSymbol;
            this.bestRankNoHpo = bestRankNoHpo;
            this.bestRankWithHpo = bestRankWithHpo;
            this.rankDelta = rankDelta;
            this.rankStatusNoHpo = rankStatusNoHpo == null
                    ? RankStatus.RANK_UNAVAILABLE : rankStatusNoHpo;
            this.rankStatusWithHpo = rankStatusWithHpo == null
                    ? RankStatus.RANK_UNAVAILABLE : rankStatusWithHpo;
            this.phenotypeSupportPresent = phenotypeSupportPresent;
            this.missReason = missReason;
        }

        public String getGeneSymbol() {
            return geneSymbol;
        }

        public Integer getBestRankNoHpo() {
            return bestRankNoHpo;
        }

        public Integer getBestRankWithHpo() {
            return bestRankWithHpo;
        }

        public Integer getRankDelta() {
            return rankDelta;
        }

        public RankStatus getRankStatusNoHpo() {
            return rankStatusNoHpo;
        }

        public RankStatus getRankStatusWithHpo() {
            return rankStatusWithHpo;
        }

        public boolean isPhenotypeSupportPresent() {
            return phenotypeSupportPresent;
        }

        public CaseValidationMissReason getMissReason() {
            return missReason;
        }
    }
}
