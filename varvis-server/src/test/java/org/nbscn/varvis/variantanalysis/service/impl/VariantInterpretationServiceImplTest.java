package org.nbscn.varvis.variantanalysis.service.impl;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkAnalysisTelemetryManifest;
import org.nbscn.varvis.variantanalysis.benchmark.telemetry.BenchmarkTelemetryCollector;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VcfPreflightSummary;
import org.nbscn.varvis.variantanalysis.provider.VariantEvidenceProvider;
import org.nbscn.varvis.variantanalysis.ranking.TransparentVariantRankingService;
import org.nbscn.varvis.variantanalysis.service.AcmgReadyEvidenceMapper;
import org.nbscn.varvis.variantanalysis.service.BasicVariantAnnotator;
import org.nbscn.varvis.variantanalysis.service.PhenotypePriorGeneIntervalService;
import org.nbscn.varvis.variantanalysis.service.VariantInterpretationService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantInterpretationServiceImplTest {

    @Test
    void alphaMissenseAnnotationIsARankingInputAndCannotBeDisabledAsDisplayOnly() {
        List<Double> scores = new ArrayList<>();
        for (boolean includeAm : List.of(false, true)) {
            var first = variant("a-first", 100, "PASS", "0/1", "SNV");
            var second = variant("b-second", 200, "PASS", "0/1", "SNV");
            first.applyRanking("REVIEW", 0.99d, Collections.singletonMap("exomiserRawScore", 0.99d),
                    List.of("Engine rank 1"), List.of());
            second.applyRanking("REVIEW", 0.80d, Collections.singletonMap("exomiserRawScore", 0.80d),
                    List.of("Engine rank 2"), List.of());
            var properties = new VariantAnalysisProperties();
            List<VariantEvidenceProvider> providers = includeAm
                    ? List.of(new RecordingProvider("ALPHAMISSENSE", "COMPUTATIONAL", Map.of("b-second",
                            evidence("ALPHAMISSENSE", "COMPUTATIONAL", "APPLICABLE", 0.999d, "synthetic ranking fixture")))) : List.of();
            var service = new VariantInterpretationServiceImpl(new StaticAnnotator(List.of(first, second)),
                    providers, new TransparentVariantRankingService(properties));
            service.enrichEvidenceOnly(List.of(first, second), new AnalysisContext("synthetic-ranking-boundary", GenomeAssembly.GRCh38, AnalysisMode.WES));
            assertEquals(0.80d, second.getScoreBreakdown().get("exomiserRawScore"));
            scores.add(second.getRankingScore());
        }
        org.junit.jupiter.api.Assertions.assertNotEquals(scores.get(0), scores.get(1),
                "Removing AM changes the existing composite score even though Exomiser itself is unchanged");
    }

    @Test
    void enrichEvidenceOnlyAddsProviderEvidenceAndRetainsRawEngineScore() {
        VariantInterpretation engineRanked = variant("engine-ranked", 100, "PASS", "0/1", "SNV");
        engineRanked.applyRanking("REVIEW", 0.998d, Collections.singletonMap("exomiserRawScore", 0.998d),
                Collections.singletonList("Engine rank 1."), Collections.<String>emptyList());
        RecordingProvider clinVar = new RecordingProvider("CLINVAR", "CLINICAL");
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(Collections.singletonList(engineRanked)),
                Arrays.asList(clinVar, cadd),
                ignored -> {
                }
        );

        service.enrichEvidenceOnly(Collections.singletonList(engineRanked),
                new AnalysisContext("engine", GenomeAssembly.GRCh38, AnalysisMode.WES));

        assertEquals("REVIEW", engineRanked.getTier());
        assertEquals(0.998d, engineRanked.getScoreBreakdown().get("exomiserRawScore"));
        assertEquals(1.0d, engineRanked.getRankingScore());
        assertEquals(1.0d, engineRanked.getScoreBreakdown().get("engineEvidenceComposite"));
        assertTrue(hasApplicableEvidence(engineRanked, "CLINVAR"));
        assertTrue(hasApplicableEvidence(engineRanked, "CADD"));
        assertEquals(Collections.singletonList(engineRanked.getVariantId()), clinVar.queriedVariantIds);
        assertEquals(Collections.singletonList(engineRanked.getVariantId()), cadd.queriedVariantIds);
        assertTrue(engineRanked.getRationale().get(0).contains(
                "derived Exomiser-score candidate order"
        ));
        assertTrue(engineRanked.getRationale().get(0).contains(
                "native #RANK remains the gene/MOI group rank"
        ));
    }

    @Test
    void enginePrimaryCompositeRetainsExomiserLeadWhileExposingLocalEvidenceTier() {
        VariantInterpretation engineFirst = variant("engine-first", 100, "PASS", "0/1", "SNV");
        VariantInterpretation clinicalSecond = variant("clinical-second", 200, "PASS", "0/1", "SNV");
        engineFirst.applyRanking("REVIEW", 0.99d, Collections.singletonMap("exomiserRawScore", 0.99d),
                Collections.singletonList("Engine rank 1."), Collections.<String>emptyList());
        clinicalSecond.applyRanking("REVIEW", 0.80d, Collections.singletonMap("exomiserRawScore", 0.80d),
                Collections.singletonList("Engine rank 2."), Collections.<String>emptyList());
        Map<String, EvidenceItem> clinicalEvidence = Collections.singletonMap(
                "clinical-second", evidence("CLINVAR", "CLINICAL", "APPLICABLE", null, "Pathogenic"));
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(Arrays.asList(engineFirst, clinicalSecond)),
                Collections.singletonList(new RecordingProvider("CLINVAR", "CLINICAL", clinicalEvidence)),
                new TransparentVariantRankingService(properties)
        );
        service.enrichEvidenceOnly(Arrays.asList(engineFirst, clinicalSecond),
                new AnalysisContext("engine", GenomeAssembly.GRCh38, AnalysisMode.WES));

        assertEquals(0.99d, engineFirst.getScoreBreakdown().get("exomiserRawScore"));
        assertEquals(0.80d, clinicalSecond.getScoreBreakdown().get("exomiserRawScore"));
        assertTrue(hasApplicableEvidence(clinicalSecond, "CLINVAR"));
        assertTrue(engineFirst.getRankingScore() > clinicalSecond.getRankingScore());
        assertEquals(0.75d, engineFirst.getScoreBreakdown().get("exomiserPrimaryContribution"));
        assertEquals(0.25d, clinicalSecond.getScoreBreakdown().get("localEvidenceContribution"));

    }

    @Test
    void duplicateVariantRowsUseSourceOrderForEngineAndLocalTies() {
        VariantInterpretation first = variant("duplicate", 150, "PASS", "0/1", "SNV");
        VariantInterpretation second = variant("duplicate", 150, "PASS", "0/1", "SNV");
        first.applyRanking("REVIEW", 0.9d, Collections.singletonMap("exomiserRawScore", 0.9d),
                Collections.singletonList("Engine rank 1."), Collections.<String>emptyList());
        second.applyRanking("REVIEW", 0.9d, Collections.singletonMap("exomiserRawScore", 0.9d),
                Collections.singletonList("Engine rank 2."), Collections.<String>emptyList());
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(Arrays.asList(first, second)),
                Collections.<VariantEvidenceProvider>emptyList(),
                new TransparentVariantRankingService(properties)
        );
        service.enrichEvidenceOnly(
                Arrays.asList(first, second),
                new AnalysisContext("engine", GenomeAssembly.GRCh38, AnalysisMode.WES)
        );

        assertEquals(1.0d, first.getScoreBreakdown().get("exomiserRawRank"));
        assertEquals(2.0d, second.getScoreBreakdown().get("exomiserRawRank"));
        assertTrue(first.getRankingScore() > second.getRankingScore());
    }

    @Test
    void engineRankedIndelIsRoutedToConfiguredLocalEvidenceInsteadOfMarkedNotApplicable() {
        VariantInterpretation engineIndel = variant("engine-indel", 300, "PASS", "0/1", "INDEL");
        engineIndel.applyRanking("REVIEW", 0.91d, Collections.singletonMap("exomiserRawScore", 0.91d),
                Collections.singletonList("Engine rank 1."), Collections.<String>emptyList());
        RecordingProvider gnomad = new RecordingProvider("GNOMAD", "POPULATION");
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        RecordingProvider spliceAi = new RecordingProvider("SPLICEAI", "COMPUTATIONAL");
        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(Collections.singletonList(engineIndel)),
                Arrays.asList(gnomad, cadd, spliceAi),
                ignored -> {
                }
        );

        service.enrichEvidenceOnly(Collections.singletonList(engineIndel),
                new AnalysisContext("engine", GenomeAssembly.GRCh38, AnalysisMode.WES));

        assertEquals(Collections.singletonList("engine-indel"), gnomad.queriedVariantIds);
        assertEquals(Collections.singletonList("engine-indel"), cadd.queriedVariantIds);
        assertEquals(Collections.singletonList("engine-indel"), spliceAi.queriedVariantIds);
        assertTrue(hasApplicableEvidence(engineIndel, "GNOMAD"));
        assertTrue(hasApplicableEvidence(engineIndel, "CADD"));
        assertTrue(hasApplicableEvidence(engineIndel, "SPLICEAI"));
    }

    @Test
    void skipsHeavyEvidenceForReferenceOrFilteredVariantsWithoutDroppingClinicalLookup() {
        VariantInterpretation passCarried = variant("pass-carried", 100, "PASS", "0/1", "SNV");
        VariantInterpretation passReference = variant("pass-reference", 200, "PASS", "0/0", "SNV");
        VariantInterpretation filteredCarried = variant("filtered-carried", 300, "q10", "0/1", "SNV");
        List<VariantInterpretation> variants = Arrays.asList(passCarried, passReference, filteredCarried);
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        RecordingProvider clinVar = new RecordingProvider("CLINVAR", "CLINICAL");

        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Arrays.asList(cadd, clinVar),
                ignored -> {
                }
        );

        service.interpret(null, Collections.emptyList(),
                new AnalysisContext("analysis", GenomeAssembly.GRCh38, AnalysisMode.WES));

        assertEquals(Collections.singletonList(passCarried.getVariantId()), cadd.queriedVariantIds);
        assertEquals(
                Arrays.asList(passCarried.getVariantId(), filteredCarried.getVariantId()),
                clinVar.queriedVariantIds
        );
        assertTrue(hasSkippedEvidence(passReference, "CADD", "0/0"));
        assertTrue(hasSkippedEvidence(filteredCarried, "CADD", "FILTER"));
        assertTrue(hasSkippedEvidence(passReference, "CLINVAR", "0/0"));
        assertTrue(hasApplicableEvidence(filteredCarried, "CLINVAR"));
    }

    @Test
    void unknownFilterDoesNotSuppressConfiguredEvidenceProviders() {
        VariantInterpretation unknownFilter = variant("unknown-filter", 350, null, "0/1", "SNV");
        RecordingProvider gnomad = new RecordingProvider("GNOMAD", "POPULATION");
        RecordingProvider spliceAi = new RecordingProvider("SPLICEAI", "COMPUTATIONAL");
        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(Collections.singletonList(unknownFilter)),
                Arrays.asList(gnomad, spliceAi),
                ignored -> {
                }
        );

        service.enrichEvidenceOnly(
                Collections.singletonList(unknownFilter),
                new AnalysisContext("engine", GenomeAssembly.GRCh38, AnalysisMode.WES)
        );

        assertEquals(Collections.singletonList("unknown-filter"), gnomad.queriedVariantIds);
        assertEquals(Collections.singletonList("unknown-filter"), spliceAi.queriedVariantIds);
        assertTrue(hasApplicableEvidence(unknownFilter, "GNOMAD"));
        assertTrue(hasApplicableEvidence(unknownFilter, "SPLICEAI"));
    }

    @Test
    void limitsCaddQueriesToPriorityCandidatesAfterPopulationPreScreen() {
        VariantInterpretation common = variant("common", 100, "PASS", "0/1", "SNV");
        VariantInterpretation rareOne = variant("rare-one", 200, "PASS", "0/1", "SNV");
        VariantInterpretation rareTwo = variant("rare-two", 300, "PASS", "0/1", "SNV");
        List<VariantInterpretation> variants = Arrays.asList(common, rareOne, rareTwo);
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        RecordingProvider gnomad = new RecordingProvider(
                "GNOMAD",
                "POPULATION",
                evidenceByVariantId(
                        common, evidence("GNOMAD", "POPULATION", "APPLICABLE", 0.5d, "Maximum allele frequency"),
                        rareOne, evidence("GNOMAD", "POPULATION", "APPLICABLE", 0.0001d, "Maximum allele frequency"),
                        rareTwo, evidence("GNOMAD", "POPULATION", "APPLICABLE", 0.0002d, "Maximum allele frequency")
                )
        );
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getCadd().setMaxCandidates(2);

        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Arrays.asList(cadd, gnomad),
                ignored -> {
                },
                null,
                null,
                properties
        );

        service.interpret(null, Collections.emptyList(),
                new AnalysisContext("analysis", GenomeAssembly.GRCh38, AnalysisMode.WES));

        assertEquals(Arrays.asList(rareOne.getVariantId(), rareTwo.getVariantId()), cadd.queriedVariantIds);
        assertTrue(hasSkippedEvidence(common, "CADD", "large-file candidate limit"));
    }

    @Test
    void caddMaxCandidatesZeroKeepsAllEligibleVariantsQueryable() {
        VariantInterpretation first = variant("first", 100, "PASS", "0/1", "SNV");
        VariantInterpretation second = variant("second", 200, "PASS", "0/1", "SNV");
        VariantInterpretation third = variant("third", 300, "PASS", "0/1", "SNV");
        List<VariantInterpretation> variants = Arrays.asList(first, second, third);
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getCadd().setMaxCandidates(0);

        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Collections.singletonList(cadd),
                ignored -> {
                },
                null,
                null,
                properties
        );

        service.interpret(null, Collections.emptyList(),
                new AnalysisContext("analysis", GenomeAssembly.GRCh38, AnalysisMode.WES));

        assertEquals(
                Arrays.asList(first.getVariantId(), second.getVariantId(), third.getVariantId()),
                cadd.queriedVariantIds
        );
    }

    @Test
    void largeFileGateBucketsCheapFilteredVariantsAndSkipsExpensiveProviders() {
        VariantInterpretation notCarried = variant("not-carried", 100, "PASS", "0/0", "SNV");
        VariantInterpretation common = variant("common", 200, "PASS", "0/1", "SNV");
        common.addEvidence(Collections.singletonList(evidence(
                "GNOMAD",
                "POPULATION",
                "APPLICABLE",
                0.2d,
                "Maximum allele frequency"
        )));
        VariantInterpretation synonymous = variant(
                "synonymous",
                300,
                "PASS",
                "0/1",
                "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE1", "1", "NM_1", null, null,
                        "synonymous_variant"))
        );
        List<VariantInterpretation> variants = Arrays.asList(notCarried, common, synonymous);
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");

        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Collections.singletonList(cadd),
                ignored -> {
                },
                null,
                null,
                new VariantAnalysisProperties()
        );

        service.interpret(null, Collections.emptyList(), largeFileContext(variants.size()));

        assertTrue(cadd.queriedVariantIds.isEmpty());
        assertEquals(VariantAnalysisBucket.FILTERED_NOT_CARRIED, notCarried.getAnalysisBucket());
        assertEquals(VariantAnalysisBucket.FILTERED_COMMON, common.getAnalysisBucket());
        assertEquals(VariantAnalysisBucket.DEFERRED_LOW_PRIORITY, synonymous.getAnalysisBucket());
        assertTrue(notCarried.isSummaryOnly());
        assertTrue(common.isExpensiveAnnotationSkipped());
        assertTrue(synonymous.getExpensiveAnnotationSkippedReason().contains("LOW_PRIORITY"));
        assertTrue(hasSkippedEvidence(common, "CADD", "LARGE_FILE_COMMON_AF"));
    }

    @Test
    void largeFileGateOnlyTreatsCompleteReferenceGenotypesAsNotCarried() {
        VariantInterpretation unphasedReference = variant("unphased-reference", 100, "PASS", "0/0", "SNV");
        VariantInterpretation phasedReference = variant("phased-reference", 200, "PASS", "0|0", "SNV");
        VariantInterpretation missingAllele = variant("missing-allele", 300, "PASS", "0/.", "SNV");
        VariantInterpretation alternateAllele = variant("alternate-allele", 400, "PASS", "0/2", "SNV");
        VariantInterpretation malformed = variant("malformed", 500, "PASS", "00", "SNV");
        VariantInterpretation trailingSeparator = variant("trailing-separator", 600, "PASS", "0/", "SNV");
        List<VariantInterpretation> variants = Arrays.asList(
                unphasedReference, phasedReference, missingAllele, alternateAllele, malformed, trailingSeparator
        );

        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants), Collections.emptyList(), ignored -> {
                }, null, null, new VariantAnalysisProperties()
        );

        service.interpret(null, Collections.emptyList(), largeFileContext(variants.size()));

        assertEquals(VariantAnalysisBucket.FILTERED_NOT_CARRIED, unphasedReference.getAnalysisBucket());
        assertEquals(VariantAnalysisBucket.FILTERED_NOT_CARRIED, phasedReference.getAnalysisBucket());
        assertEquals(VariantAnalysisBucket.CANDIDATE, missingAllele.getAnalysisBucket());
        assertEquals(VariantAnalysisBucket.CANDIDATE, alternateAllele.getAnalysisBucket());
        assertEquals(VariantAnalysisBucket.CANDIDATE, malformed.getAnalysisBucket());
        assertEquals(VariantAnalysisBucket.CANDIDATE, trailingSeparator.getAnalysisBucket());
    }

    @Test
    void largeFileCandidateHardLimitKeepsPrioritySubsetQueryable() {
        VariantInterpretation first = variant("first", 100, "PASS", "0/1", "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE1", "1", "NM_1", null, null,
                        "missense_variant")));
        VariantInterpretation second = variant("second", 200, "PASS", "0/1", "INDEL",
                Collections.singletonList(new TranscriptConsequence("GENE2", "2", "NM_2", null, null,
                        "frameshift_variant")));
        VariantInterpretation third = variant("third", 300, "PASS", "0/1", "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE3", "3", "NM_3", null, null,
                        "missense_variant")));
        List<VariantInterpretation> variants = Arrays.asList(first, second, third);
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setCandidateHardLimit(2);
        properties.getLargeFile().setExpensiveAnnotationLimit(0);

        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Collections.singletonList(cadd),
                ignored -> {
                },
                null,
                null,
                properties
        );

        service.interpret(null, Collections.emptyList(), largeFileContext(variants.size()));

        assertEquals(2, cadd.queriedVariantIds.size());
        assertTrue(cadd.queriedVariantIds.contains(second.getVariantId()));
        assertTrue(oneVariantDeferredByCandidateLimit(variants));
    }

    @Test
    void largeFileCandidateHardLimitDoesNotUseInputOrderForBareCandidates() {
        VariantInterpretation lowerConfidence = variantWithQuality(
                "lower-confidence", 100, 25, 50, 0.30d
        );
        VariantInterpretation higherConfidence = variantWithQuality(
                "higher-confidence", 200, 120, 99, 0.50d
        );
        List<VariantInterpretation> variants = Arrays.asList(lowerConfidence, higherConfidence);
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setCandidateHardLimit(1);
        properties.getLargeFile().setExpensiveAnnotationLimit(0);
        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Collections.singletonList(cadd),
                ignored -> {
                },
                null,
                null,
                properties
        );

        service.interpret(null, Collections.emptyList(), largeFileContext(variants.size()));

        assertEquals(Collections.singletonList(higherConfidence.getVariantId()), cadd.queriedVariantIds);
        assertEquals(VariantAnalysisBucket.DEFERRED_NO_EXPENSIVE_ANNOTATION,
                lowerConfidence.getAnalysisBucket());
    }

    @Test
    void compactLargeFileGateReleasesSummaryOnlyRecordsBeforeProviderAnnotation() {
        VariantInterpretation lowest = variantWithQuality("lowest", 100, 15, 20, 0.20d);
        VariantInterpretation lower = variantWithQuality("lower", 200, 25, 30, 0.25d);
        VariantInterpretation middle = variantWithQuality("middle", 300, 40, 50, 0.35d);
        VariantInterpretation higher = variantWithQuality("higher", 400, 80, 80, 0.45d);
        VariantInterpretation highest = variantWithQuality("highest", 500, 120, 99, 0.50d);
        List<VariantInterpretation> variants = Arrays.asList(lowest, lower, middle, higher, highest);
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setVariantThreshold(1);
        properties.getLargeFile().setCandidateHardLimit(2);
        properties.getLargeFile().setExpensiveAnnotationLimit(0);
        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Collections.singletonList(cadd),
                ignored -> {
                },
                null,
                null,
                properties
        );

        List<VariantInterpretation> retained = service.interpret(
                null,
                Collections.emptyList(),
                largeFileContext(variants.size())
        );

        assertEquals(2, retained.size());
        assertEquals(2, cadd.queriedVariantIds.size());
        assertTrue(cadd.queriedVariantIds.contains(higher.getVariantId()));
        assertTrue(cadd.queriedVariantIds.contains(highest.getVariantId()));
    }

    @Test
    void largeFileCandidateHardLimitKeepsPhenotypePriorGeneIntervalRescueCandidate() {
        VariantInterpretation missense = variant("missense", 100, "PASS", "0/1", "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE1", "1", "NM_1", null, null,
                        "missense_variant")));
        VariantInterpretation frameshift = variant("frameshift", 200, "PASS", "0/1", "INDEL",
                Collections.singletonList(new TranscriptConsequence("GENE2", "2", "NM_2", null, null,
                        "frameshift_variant")));
        VariantInterpretation priorGeneBare = variant("prior-gene-bare", 300, "PASS", "0/1", "SNV");
        List<VariantInterpretation> variants = Arrays.asList(missense, frameshift, priorGeneBare);
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setCandidateHardLimit(2);
        properties.getLargeFile().setExpensiveAnnotationLimit(0);

        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Collections.singletonList(cadd),
                ignored -> {
                },
                null,
                null,
                properties,
                new StaticPhenotypePriorGeneIntervalService("prior-gene-bare")
        );

        BenchmarkTelemetryCollector telemetry = new BenchmarkTelemetryCollector(
                "attempt-1", repeat('a'), repeat('b'), Arrays.asList(
                priorGeneBare.getObservation().getKey(), missense.getObservation().getKey()));
        service.interpret(null, Collections.emptyList(),
                largeFileContext(variants.size(), summaryWithPrior("PPA2"), telemetry));

        assertEquals(2, cadd.queriedVariantIds.size());
        assertTrue(cadd.queriedVariantIds.contains(frameshift.getVariantId()));
        assertTrue(cadd.queriedVariantIds.contains(priorGeneBare.getVariantId()));
        assertTrue(priorGeneBare.getCheapGateReasons().toString()
                .contains("LARGE_FILE_GATE_PHENOTYPE_PRIOR_GENE_INTERVAL"));
        BenchmarkAnalysisTelemetryManifest manifest = telemetry.snapshot("analysis");
        assertEquals(Integer.valueOf(1), manifest.getCounts().get("phenotypeRescued").getValue());
        assertEquals(Integer.valueOf(1), manifest.getCounts().get("phenotypeDisplaced").getValue());
        assertEquals("RESCUED", manifest.getTruths().get(0).getStages()
                .get("PHENOTYPE_DISPOSITION").getStatus());
        assertEquals("DISPLACED", manifest.getTruths().get(1).getStages()
                .get("PHENOTYPE_DISPOSITION").getStatus());
        assertEquals(VariantAnalysisBucket.DEFERRED_NO_EXPENSIVE_ANNOTATION, missense.getAnalysisBucket());
    }

    @Test
    void phenotypePriorGeneIntervalRescueDoesNotOverrideLargeFileHardGates() {
        VariantInterpretation notCarried = variant("not-carried", 100, "PASS", "0/0", "SNV");
        VariantInterpretation qcFailed = variant("qc-failed", 200, "q10", "0/1", "SNV");
        VariantInterpretation candidate = variant("candidate", 300, "PASS", "0/1", "SNV");
        List<VariantInterpretation> variants = Arrays.asList(notCarried, qcFailed, candidate);
        RecordingProvider cadd = new RecordingProvider("CADD", "COMPUTATIONAL");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getLargeFile().setCandidateHardLimit(1);
        properties.getLargeFile().setExpensiveAnnotationLimit(0);

        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Collections.singletonList(cadd),
                ignored -> {
                },
                null,
                null,
                properties,
                new StaticPhenotypePriorGeneIntervalService("not-carried", "qc-failed", "candidate")
        );

        service.interpret(null, Collections.emptyList(),
                largeFileContext(variants.size(), summaryWithPrior("PPA2")));

        assertEquals(Collections.singletonList(candidate.getVariantId()), cadd.queriedVariantIds);
        assertEquals(VariantAnalysisBucket.FILTERED_NOT_CARRIED, notCarried.getAnalysisBucket());
        assertEquals(VariantAnalysisBucket.FILTERED_QC, qcFailed.getAnalysisBucket());
        assertEquals(VariantAnalysisBucket.CANDIDATE, candidate.getAnalysisBucket());
    }

    @Test
    void largeFileClinVarCompactBatchQueriesCandidatesAndPriorClinicalSignalsOnly() {
        VariantInterpretation candidate = variant("candidate", 100, "PASS", "0/1", "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE1", "1", "NM_1", null, null,
                        "missense_variant")));
        VariantInterpretation filteredNoSignal = variant("filtered-no-signal", 200, "q10", "0/1", "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE2", "2", "NM_2", null, null,
                        "missense_variant")));
        VariantInterpretation filteredClinicalSignal = variant("filtered-clinical-signal", 300, "q10", "0/1", "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE3", "3", "NM_3", null, null,
                        "missense_variant")));
        filteredClinicalSignal.addEvidence(Collections.singletonList(evidence(
                "CLINVAR",
                "CLINICAL",
                "APPLICABLE",
                null,
                "Pathogenic"
        )));
        VariantInterpretation deferred = variant("deferred", 400, "PASS", "0/1", "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE4", "4", "NM_4", null, null,
                        "synonymous_variant")));
        List<VariantInterpretation> variants = Arrays.asList(
                candidate,
                filteredNoSignal,
                filteredClinicalSignal,
                deferred
        );
        RecordingProvider clinVar = new RecordingProvider("CLINVAR", "CLINICAL");
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getClinVar().getCompactIndex().setEnabled(true);
        properties.getClinVar().getCompactIndex().setBatchSize(1);

        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Collections.singletonList(clinVar),
                ignored -> {
                },
                null,
                null,
                properties
        );

        service.interpret(null, Collections.emptyList(), largeFileContext(variants.size()));

        assertEquals(
                Arrays.asList(candidate.getVariantId(), filteredClinicalSignal.getVariantId()),
                clinVar.queriedVariantIds
        );
        assertEquals(Arrays.asList(1, 1), clinVar.queriedBatchSizes);
        assertTrue(hasSkippedEvidence(filteredNoSignal, "CLINVAR", "CLINVAR_COMPACT_CANDIDATE_ONLY"));
        assertTrue(hasSkippedEvidence(deferred, "CLINVAR", "CLINVAR_COMPACT_CANDIDATE_ONLY"));
    }

    @Test
    void largeFileAcmgReadySuggestionsOnlyRunForFullInterpretationCandidates() {
        VariantInterpretation candidate = variant("candidate", 100, "PASS", "0/1", "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE1", "1", "NM_1", null, null,
                        "frameshift_variant")));
        VariantInterpretation summaryOnly = variant("summary-only", 200, "PASS", "0/0", "SNV",
                Collections.singletonList(new TranscriptConsequence("GENE2", "2", "NM_2", null, null,
                        "frameshift_variant")));
        List<VariantInterpretation> variants = Arrays.asList(candidate, summaryOnly);

        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        VariantInterpretationService service = new VariantInterpretationServiceImpl(
                new StaticAnnotator(variants),
                Collections.emptyList(),
                ignored -> {
                },
                null,
                new AcmgReadyEvidenceMapper(properties),
                properties
        );

        service.interpret(null, Collections.emptyList(), largeFileContext(variants.size()));

        assertFalse(candidate.getAcmgEvidenceSuggestions().isEmpty());
        assertTrue(summaryOnly.isSummaryOnly());
        assertTrue(summaryOnly.getAcmgEvidenceSuggestions().isEmpty());
    }

    private static boolean hasSkippedEvidence(VariantInterpretation variant,
                                              String source,
                                              String expectedSummaryPart) {
        for (EvidenceItem evidence : variant.getEvidence()) {
            if (source.equals(evidence.getSource())
                    && "NOT_APPLICABLE".equals(evidence.getApplicability())
                    && evidence.getSummary().contains(expectedSummaryPart)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasApplicableEvidence(VariantInterpretation variant, String source) {
        for (EvidenceItem evidence : variant.getEvidence()) {
            if (source.equals(evidence.getSource())
                    && "APPLICABLE".equals(evidence.getApplicability())) {
                return true;
            }
        }
        return false;
    }

    private static VariantInterpretation variant(String id,
                                                 int position,
                                                 String filterStatus,
                                                 String genotype,
                                                 String variantType) {
        return variant(id, position, filterStatus, genotype, variantType, Collections.emptyList());
    }

    private static VariantInterpretation variant(String id,
                                                 int position,
                                                 String filterStatus,
                                                 String genotype,
                                                 String variantType,
                                                 List<TranscriptConsequence> consequences) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", position, "A", "G"),
                "proband",
                genotype,
                30,
                99,
                "18,12",
                0.4d,
                filterStatus,
                variantType,
                "chr1|" + position + "|A|G|1"
        );
        return new VariantInterpretation(id, observation, consequences, Collections.emptyList());
    }

    private static VariantInterpretation variantWithQuality(String id,
                                                            int position,
                                                            int depth,
                                                            int genotypeQuality,
                                                            double alleleBalance) {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", position, "A", "G"),
                "proband",
                "0/1",
                depth,
                genotypeQuality,
                "20,20",
                alleleBalance,
                "PASS",
                "SNV",
                "chr1|" + position + "|A|G|1"
        );
        return new VariantInterpretation(
                id,
                observation,
                Collections.<TranscriptConsequence>emptyList(),
                Collections.emptyList()
        );
    }

    private static AnalysisContext largeFileContext(int variantCount) {
        return largeFileContext(variantCount, PhenotypeAwareSummary.disabled());
    }

    private static AnalysisContext largeFileContext(int variantCount, PhenotypeAwareSummary phenotypeSummary) {
        return largeFileContext(variantCount, phenotypeSummary, BenchmarkTelemetryCollector.disabled());
    }

    private static AnalysisContext largeFileContext(int variantCount,
                                                    PhenotypeAwareSummary phenotypeSummary,
                                                    BenchmarkTelemetryCollector telemetry) {
        return new AnalysisContext(
                "analysis",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                new VcfPreflightSummary(
                        variantCount,
                        variantCount,
                        0,
                        1,
                        "proband",
                        variantCount,
                        0,
                        0,
                        0,
                        false,
                        false,
                        true,
                        Collections.singletonMap("PASS", variantCount),
                        true,
                        "test large-file mode"
                ),
                phenotypeSummary,
                telemetry
        );
    }

    private static String repeat(char value) {
        char[] chars = new char[64];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static PhenotypeAwareSummary summaryWithPrior(String geneSymbol) {
        GenePhenotypePrior prior = new GenePhenotypePrior(
                geneSymbol,
                null,
                0.99d,
                1,
                "TEST_PRIOR",
                "test",
                Collections.singletonList("HP:0000001"),
                Collections.singletonList("test phenotype prior"),
                GenePhenotypePriorStatus.AVAILABLE,
                Instant.EPOCH,
                Collections.emptyList()
        );
        GenePhenotypePriorSummary priorSummary = new GenePhenotypePriorSummary(
                true,
                "TEST_PRIOR",
                "test",
                GenePhenotypePriorStatus.AVAILABLE,
                1,
                1,
                1,
                Collections.singletonList(prior),
                Collections.emptyList(),
                Instant.EPOCH
        );
        return new PhenotypeAwareSummary(
                1,
                1,
                0,
                0,
                Collections.singletonList("HP:0000001"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                true,
                false,
                priorSummary
        );
    }

    private static boolean oneVariantDeferredByCandidateLimit(List<VariantInterpretation> variants) {
        int deferred = 0;
        for (VariantInterpretation variant : variants) {
            if (VariantAnalysisBucket.DEFERRED_NO_EXPENSIVE_ANNOTATION.equals(variant.getAnalysisBucket())) {
                assertTrue(variant.isSummaryOnly());
                assertTrue(variant.getExpensiveAnnotationSkippedReason().contains("CANDIDATE_HARD_LIMIT"));
                deferred++;
            } else {
                assertFalse(variant.isSummaryOnly());
            }
        }
        return deferred == 1;
    }

    private static Map<String, EvidenceItem> evidenceByVariantId(Object... entries) {
        Map<String, EvidenceItem> result = new LinkedHashMap<String, EvidenceItem>();
        for (int index = 0; index + 1 < entries.length; index += 2) {
            VariantInterpretation variant = (VariantInterpretation) entries[index];
            EvidenceItem evidence = (EvidenceItem) entries[index + 1];
            result.put(variant.getVariantId(), evidence);
        }
        return result;
    }

    private static EvidenceItem evidence(String source,
                                         String evidenceType,
                                         String applicability,
                                         Double rawScore,
                                         String label) {
        return new EvidenceItem(
                source,
                "test",
                evidenceType,
                applicability,
                rawScore,
                label,
                label + " test evidence",
                Collections.emptyMap()
        );
    }

    private static class StaticAnnotator implements BasicVariantAnnotator {

        private final List<VariantInterpretation> variants;

        private StaticAnnotator(List<VariantInterpretation> variants) {
            this.variants = variants;
        }

        @Override
        public List<VariantInterpretation> annotate(Path normalizedVcf,
                                                    List<VariantQcObservation> observations) {
            return variants;
        }
    }

    private static class RecordingProvider implements VariantEvidenceProvider {

        private final String source;
        private final String evidenceType;
        private final Map<String, EvidenceItem> evidenceByVariantId;
        private final List<String> queriedVariantIds = new ArrayList<String>();
        private final List<Integer> queriedBatchSizes = new ArrayList<Integer>();

        private RecordingProvider(String source, String evidenceType) {
            this(source, evidenceType, Collections.emptyMap());
        }

        private RecordingProvider(String source,
                                  String evidenceType,
                                  Map<String, EvidenceItem> evidenceByVariantId) {
            this.source = source;
            this.evidenceType = evidenceType;
            this.evidenceByVariantId = evidenceByVariantId;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public String version() {
            return "test";
        }

        @Override
        public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
                List<VariantInterpretation> variants,
                AnalysisContext context) {
            Map<NormalizedVariantKey, List<EvidenceItem>> result =
                    new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
            queriedBatchSizes.add(variants.size());
            for (VariantInterpretation variant : variants) {
                queriedVariantIds.add(variant.getVariantId());
                EvidenceItem evidence = evidenceByVariantId.get(variant.getVariantId());
                if (evidence == null) {
                    evidence = new EvidenceItem(
                            source,
                            "test",
                            evidenceType,
                            "APPLICABLE",
                            null,
                            source + " evidence",
                            "queried",
                            Collections.emptyMap()
                    );
                }
                result.put(
                        variant.getObservation().getKey(),
                        Collections.singletonList(evidence)
                );
            }
            return result;
        }
    }

    private static class StaticPhenotypePriorGeneIntervalService extends PhenotypePriorGeneIntervalService {

        private final Set<String> rescuedVariantIds;

        private StaticPhenotypePriorGeneIntervalService(String... rescuedVariantIds) {
            this.rescuedVariantIds = new HashSet<String>(Arrays.asList(rescuedVariantIds));
        }

        @Override
        public RescueEvaluation evaluateRescueMatches(
                List<VariantInterpretation> interpretations,
                AnalysisContext context) {
            Map<VariantInterpretation, GeneIntervalMatch> result =
                    new LinkedHashMap<VariantInterpretation, GeneIntervalMatch>();
            for (VariantInterpretation interpretation : interpretations) {
                if (rescuedVariantIds.contains(interpretation.getVariantId())) {
                    result.put(interpretation, new GeneIntervalMatch(
                            "PPA2",
                            "4",
                            106300000L,
                            106400000L,
                            1,
                            0.99d,
                            "TEST_INTERVAL"
                    ));
                }
            }
            return new RescueEvaluation(
                    result,
                    interpretations == null ? 0 : interpretations.size(),
                    "AVAILABLE",
                    rescuedVariantIds.size(),
                    "AVAILABLE",
                    "TEST_INTERVAL",
                    rescuedVariantIds.size(),
                    result.size(),
                    0,
                    context == null || context.getGenomeAssembly() == null
                            ? "UNRESOLVED"
                            : context.getGenomeAssembly().name(),
                    2,
                    Collections.<String>emptyList()
            );
        }
    }
}
