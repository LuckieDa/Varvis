package org.nbscn.varvis.variantanalysis.persistence;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemoryVariantAnalysisJobRepositoryTest {

    @Test
    void separatesRareCandidatesFromPopulationFilteredVariants() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantInterpretation candidate = variant("candidate", "HIGH_PRIORITY", 20.0d);
        VariantInterpretation filtered = variant("filtered", "FILTERED_COMMON", -900.0d);
        VariantInterpretation qcFiltered = variant("qc-filtered", "FILTERED_QC", -800.0d);
        repository.replaceInterpretations("analysis", Arrays.asList(candidate, filtered, qcFiltered));

        assertEquals(1, repository.countInterpretations("analysis", VariantInterpretationView.CANDIDATES));
        assertEquals(1, repository.countInterpretations("analysis", VariantInterpretationView.FREQUENCY_FILTERED));
        assertEquals(3, repository.countInterpretations("analysis", VariantInterpretationView.ALL));
        assertEquals(
                "candidate",
                repository.findInterpretationSummaries(
                        "analysis",
                        0,
                        10,
                        VariantInterpretationView.CANDIDATES
                ).get(0).getVariantId()
        );
        assertEquals(
                "filtered",
                repository.findInterpretationSummaries(
                        "analysis",
                        0,
                        10,
                        VariantInterpretationView.FREQUENCY_FILTERED
                ).get(0).getVariantId()
        );
    }

    @Test
    void filtersInterpretationSummariesBeforePagingAndCounting() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantInterpretation known = variant(
                "known",
                "KNOWN_CLINICAL",
                120.0d,
                "GENEA",
                clinVar("Likely_pathogenic", "12345")
        );
        VariantInterpretation review = variant(
                "review",
                "REVIEW",
                20.0d,
                "GENEB",
                clinVar("Uncertain_significance", "67890")
        );
        VariantInterpretation common = variant(
                "common",
                "FILTERED_COMMON",
                -1000.0d,
                "GENEC",
                Collections.emptyList()
        );
        VariantInterpretation qc = variant(
                "qc",
                "FILTERED_QC",
                -800.0d,
                "GENED",
                Collections.emptyList()
        );
        repository.replaceInterpretations("analysis", Arrays.asList(known, review, common, qc));

        assertEquals(
                4,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        VariantInterpretationFilter.empty()
                )
        );
        assertEquals(
                "known",
                repository.findInterpretationSummaries(
                        "analysis",
                        0,
                        10,
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter("KNOWN_CLINICAL", false, false, null, null)
                ).get(0).getVariantId()
        );
        assertEquals(
                3,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(null, true, false, null, null)
                )
        );
        assertEquals(
                3,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(null, false, true, null, null)
                )
        );
        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(null, false, false, "PLP", null)
                )
        );
        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(null, false, false, "VUS", null)
                )
        );
        assertEquals(
                "review",
                repository.findInterpretationSummaries(
                        "analysis",
                        0,
                        10,
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(null, false, false, null, "GENEB")
                ).get(0).getVariantId()
        );
        assertEquals(
                "known",
                repository.findInterpretationSummaries(
                        "analysis",
                        0,
                        10,
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(null, false, false, null, "12345")
                ).get(0).getVariantId()
        );
    }

    @Test
    void geneLikeSearchRequiresExactGeneSymbolMatch() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantInterpretation exactPpa2 = variant("exact-ppa2", "UNRESOLVED", 5.0d);
        exactPpa2.applyTranscriptAnnotations(Collections.singletonList(
                transcriptAnnotation("PPA2", "missense_variant")
        ));
        VariantInterpretation substringPappa2 = variant("substring-gene", "UNRESOLVED", 5.0d);
        substringPappa2.applyTranscriptAnnotations(Collections.singletonList(
                transcriptAnnotation("PAPPA2", "intron_variant")
        ));
        VariantInterpretation coordinateHit = variant(
                "GRCh37:4:106000:A:G",
                "UNRESOLVED",
                5.0d
        );
        repository.replaceInterpretations(
                "analysis",
                Arrays.asList(exactPpa2, substringPappa2, coordinateHit)
        );

        assertEquals(
                "exact-ppa2",
                repository.findInterpretationSummaries(
                        "analysis",
                        0,
                        10,
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(null, false, false, null, "PPA2")
                ).get(0).getVariantId()
        );
        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(null, false, false, null, "PPA2")
                )
        );
        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(null, false, false, null, "4:106000")
                )
        );
    }

    @Test
    void findsExactExomiserCandidateGeneWithoutTranscriptConsequences() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "2", 200, "C", "T"),
                "proband",
                null,
                null,
                null,
                null,
                null,
                "PASS",
                "SNV",
                null
        );
        VariantInterpretation exact = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        observation.getKey().canonicalId(), 1, "PPA2", "AD", 1
                ),
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
        VariantInterpretation substring = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        observation.getKey().canonicalId(), 2, "PAPPA2", "AD", 2
                ),
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
        exact.applyRanking("REVIEW", 0.9d, Collections.emptyList(), Collections.emptyList());
        substring.applyRanking("REVIEW", 0.8d, Collections.emptyList(), Collections.emptyList());
        repository.replaceInterpretations("analysis", Arrays.asList(exact, substring));

        VariantInterpretationFilter filter = new VariantInterpretationFilter(
                null, false, false, null, "PPA2"
        );

        assertEquals(
                1,
                repository.countInterpretations("analysis", VariantInterpretationView.ALL, filter)
        );
        assertEquals(
                exact.getCandidateId(),
                repository.findInterpretationSummaries(
                        "analysis", 0, 10, VariantInterpretationView.ALL, filter
                ).get(0).getCandidateId()
        );
    }

    @Test
    void searchesWhitelistedAnalysisVcfCallFactsWithoutChangingExactGeneSearch() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        AnalysisVcfCall matchedCall = AnalysisVcfCall.matched(
                AnalysisVcfCall.TargetAlleleCall.HET_TARGET,
                "REF/TARGET_ALT",
                1,
                31,
                62,
                20,
                11,
                11.0d / 31.0d,
                AnalysisVcfCall.FilterEvaluation.FILTERED,
                AnalysisVcfCall.PhasingStatus.UNPHASED,
                null,
                4,
                GenomeAssembly.GRCh38,
                Collections.singletonList("DP"),
                Collections.singletonList("GQ")
        );
        VariantInterpretation matched = variant(
                "call-matched", "REVIEW", 10.0d, "PAPPA2", Collections.emptyList()
        ).withAnalysisVcfCall(matchedCall);
        VariantInterpretation notFound = variant(
                "call-not-found", "REVIEW", 9.0d, "OTHER", Collections.emptyList()
        ).withAnalysisVcfCall(AnalysisVcfCall.notFound(GenomeAssembly.GRCh38));
        repository.replaceInterpretations("analysis", Arrays.asList(matched, notFound));

        assertEquals(1, callSearchCount(repository, "HET_TARGET"));
        assertEquals(1, callSearchCount(repository, "REF/TARGET_ALT"));
        assertEquals(1, callSearchCount(repository, "FILTERED"));
        assertEquals(1, callSearchCount(repository, "DP"));
        assertEquals(1, callSearchCount(repository, "NOT_FOUND"));
        assertEquals(0, callSearchCount(repository, "PPA2"));
        assertEquals(1, callSearchCount(repository, "PAPPA2"));
    }

    @Test
    void filtersAcmgReadySuggestionsBeforePagingAndCounting() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantInterpretation pm2 = variant("pm2", "HIGH_PRIORITY", 80.0d);
        pm2.applyAcmgEvidenceSuggestions(Collections.singletonList(
                acmg("PM2_candidate", "SUGGESTED", true)
        ));
        VariantInterpretation bp4 = variant("bp4", "REVIEW", 30.0d);
        bp4.applyAcmgEvidenceSuggestions(Collections.singletonList(
                acmg("BP4_candidate", "NOT_EVALUATED", false)
        ));
        VariantInterpretation none = variant("none", "REVIEW", 20.0d);
        repository.replaceInterpretations("analysis", Arrays.asList(pm2, bp4, none));

        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(
                                null,
                                false,
                                false,
                                null,
                                null,
                                "PM2_candidate",
                                null,
                                null,
                                null
                        )
                )
        );
        assertEquals(
                "pm2",
                repository.findInterpretationSummaries(
                        "analysis",
                        0,
                        10,
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(
                                null,
                                false,
                                false,
                                null,
                                null,
                                null,
                                "SUGGESTED",
                                null,
                                null
                        )
                ).get(0).getVariantId()
        );
        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(
                                null,
                                false,
                                false,
                                null,
                                null,
                                null,
                                null,
                                true,
                                null
                        )
                )
        );
        assertEquals(
                2,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(
                                null,
                                false,
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                true
                        )
                )
        );
        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        new VariantInterpretationFilter(
                                null,
                                false,
                                false,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false
                        )
                )
        );
    }

    @Test
    void filtersPhenotypeSupportBeforePagingAndCounting() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantInterpretation mecp2 = phenotypeSupportedVariant(
                "mecp2",
                "HIGH_PRIORITY",
                90.0d,
                "MECP2",
                0.95d
        );
        VariantInterpretation scn1a = phenotypeSupportedVariant(
                "scn1a",
                "REVIEW",
                80.0d,
                "SCN1A",
                0.72d
        );
        VariantInterpretation unsupported = variant(
                "unsupported",
                "REVIEW",
                70.0d,
                "GENE3",
                Collections.emptyList()
        );
        VariantInterpretation commonSupported = phenotypeSupportedVariant(
                "common-supported",
                "FILTERED_COMMON",
                -900.0d,
                "MECP2",
                0.99d
        );
        repository.replaceInterpretations(
                "analysis",
                Arrays.asList(mecp2, scn1a, unsupported, commonSupported)
        );

        assertEquals(
                3,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.CANDIDATES,
                        VariantInterpretationFilter.empty()
                )
        );
        assertEquals(
                2,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.CANDIDATES,
                        phenotypeFilter(true, null, null)
                )
        );
        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.CANDIDATES,
                        phenotypeFilter(false, null, null)
                )
        );
        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.CANDIDATES,
                        phenotypeFilter(null, "mecp2", null)
                )
        );
        assertEquals(
                1,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.CANDIDATES,
                        phenotypeFilter(null, null, 0.9d)
                )
        );
        assertEquals(
                3,
                repository.countInterpretations(
                        "analysis",
                        VariantInterpretationView.ALL,
                        phenotypeFilter(true, null, null)
                )
        );
        assertEquals(
                "scn1a",
                repository.findInterpretationSummaries(
                        "analysis",
                        1,
                        1,
                        VariantInterpretationView.CANDIDATES,
                        phenotypeFilter(true, null, null)
                ).get(0).getVariantId()
        );
        assertEquals(
                "mecp2",
                repository.findInterpretationSummaries(
                        "analysis",
                        0,
                        10,
                        VariantInterpretationView.CANDIDATES,
                        new VariantInterpretationFilter(
                                "HIGH_PRIORITY",
                                false,
                                false,
                                null,
                                "MECP2",
                                null,
                                null,
                                null,
                                null,
                                true,
                                "MECP2",
                                0.9d
                        )
                ).get(0).getVariantId()
        );
        assertEquals("REVIEW", unsupported.getTier());
    }

    @Test
    void filtersLargeFileExecutionBucketsBeforePagingAndCounting() {
        InMemoryVariantAnalysisJobRepository repository = new InMemoryVariantAnalysisJobRepository();
        VariantInterpretation candidate = variant("candidate", "HIGH_PRIORITY", 90.0d);
        candidate.applyLargeFileClassification(
                VariantAnalysisBucket.HIGH_PRIORITY,
                true,
                false,
                false,
                null,
                Collections.singletonList("retained")
        );
        VariantInterpretation skipped = variant("skipped", "FILTERED_COMMON", -900.0d);
        skipped.applyLargeFileClassification(
                VariantAnalysisBucket.FILTERED_COMMON,
                true,
                true,
                true,
                "LARGE_FILE_COMMON_AF: common population frequency from available evidence",
                Collections.singletonList("common")
        );
        skipped.applyFullInterpretationAvailable(false);
        repository.replaceInterpretations("analysis", Arrays.asList(candidate, skipped));
        VariantInterpretationCounts counts = repository.countInterpretationViews("analysis");

        VariantInterpretationFilter filter = new VariantInterpretationFilter(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "FILTERED_COMMON",
                true,
                true,
                true
        );

        assertEquals(
                1,
                repository.countInterpretations("analysis", VariantInterpretationView.ALL, filter)
        );
        assertEquals(
                "skipped",
                repository.findInterpretationSummaries(
                        "analysis",
                        0,
                        10,
                        VariantInterpretationView.ALL,
                        filter
                ).get(0).getVariantId()
        );
        assertEquals(1, counts.getBucketCounts().get("HIGH_PRIORITY").intValue());
        assertEquals(1, counts.getBucketCounts().get("FILTERED_COMMON").intValue());
        assertEquals(1, counts.getSummaryOnlyCount());
        assertEquals(1, counts.getFullInterpretationCount());
        assertEquals(1, counts.getExpensiveAnnotationSkippedCount());
    }

    private VariantInterpretation variant(String id, String tier, double score) {
        return variant(id, tier, score, null, Collections.emptyList());
    }

    private int callSearchCount(InMemoryVariantAnalysisJobRepository repository, String search) {
        return repository.countInterpretations(
                "analysis",
                VariantInterpretationView.ALL,
                new VariantInterpretationFilter(null, false, false, null, search)
        );
    }

    private VariantInterpretation variant(String id,
                                          String tier,
                                          double score,
                                          String gene,
                                          EvidenceItem evidence) {
        return variant(id, tier, score, gene, Collections.singletonList(evidence));
    }

    private VariantInterpretation variant(String id,
                                          String tier,
                                          double score,
                                          String gene,
                                          java.util.List<EvidenceItem> evidence) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "G"),
                "proband",
                "0/1",
                30,
                99,
                "18,12",
                0.4d,
                "PASS",
                "SNV",
                "chr1|100|A|G|1"
        );
        VariantInterpretation interpretation = new VariantInterpretation(
                id,
                observation,
                Collections.singletonList(new TranscriptConsequence(gene, null, null, null, null, "SNV")),
                evidence
        );
        interpretation.applyRanking(tier, score, Collections.emptyList(), Collections.emptyList());
        return interpretation;
    }

    private TranscriptConsequence transcriptAnnotation(String gene, String consequence) {
        return new TranscriptConsequence(
                gene,
                null,
                "ENST000001",
                null,
                null,
                consequence,
                "ENSG000001",
                "ENST000001",
                "protein_coding",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "VEP",
                "COMPLETED",
                "test",
                null
        );
    }

    private VariantInterpretation phenotypeSupportedVariant(String id,
                                                            String tier,
                                                            double score,
                                                            String gene,
                                                            double phenotypeScore) {
        VariantInterpretation interpretation = variant(
                id,
                tier,
                score,
                gene,
                phenotypeEvidence(gene, phenotypeScore)
        );
        Map<String, Double> scoreBreakdown = new LinkedHashMap<String, Double>();
        scoreBreakdown.put("phenotype_support", 0.5d);
        Map<String, ScoreBreakdownComponent> scoreComponents =
                new LinkedHashMap<String, ScoreBreakdownComponent>();
        scoreComponents.put(
                "phenotype_support",
                new ScoreBreakdownComponent(
                        0.5d,
                        Collections.singletonList(
                                "Gene " + gene + " matched phenotype prior; candidate prioritization only."
                        )
                )
        );
        interpretation.applyRanking(
                tier,
                score,
                scoreBreakdown,
                scoreComponents,
                "test tier reason",
                Collections.singletonList("Gene " + gene + " is supported by phenotype prior from HPO terms."),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        return interpretation;
    }

    private EvidenceItem clinVar(String label, String variationId) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "LOCAL_CLINVAR");
        details.put("value", label);
        details.put("clinVarVariationId", variationId);
        return new EvidenceItem(
                "CLINVAR",
                "test",
                "CLINICAL",
                "APPLICABLE",
                null,
                label,
                "ClinVar test evidence",
                details
        );
    }

    private EvidenceItem phenotypeEvidence(String gene, double phenotypeScore) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("geneSymbol", gene);
        details.put("phenotypeScore", phenotypeScore);
        details.put("phenotypeRank", 1);
        return new EvidenceItem(
                "LOCAL_GENE_PRIOR",
                "mock-v1",
                "PHENOTYPE_GENE_PRIOR",
                "APPLICABLE",
                phenotypeScore,
                "Gene phenotype prior",
                "Gene " + gene + " is supported by phenotype prior; this is not a diagnosis.",
                details
        );
    }

    private VariantInterpretationFilter phenotypeFilter(Boolean hasSupport, String gene, Double minScore) {
        return new VariantInterpretationFilter(
                null,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                hasSupport,
                gene,
                minScore
        );
    }

    private AcmgEvidenceSuggestion acmg(String code, String status, boolean manualReviewRequired) {
        return new AcmgEvidenceSuggestion(
                code,
                "SUPPORTING",
                status,
                "test",
                "ACMG-ready test suggestion",
                manualReviewRequired,
                "acmg-ready-v1",
                Collections.emptyList()
        );
    }
}
