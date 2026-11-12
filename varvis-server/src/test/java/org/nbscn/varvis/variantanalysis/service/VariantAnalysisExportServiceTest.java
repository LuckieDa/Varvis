package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.CandidateIdentity;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePrior;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorStatus;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationCounts;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcSummary;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisExportBundleResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisVariantPageResponse;
import org.springframework.web.server.ResponseStatusException;

import java.net.URL;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VariantAnalysisExportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void csvExportDefaultsToTopFiftyAndIncludesReviewFields() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        List<VariantInterpretation> variants = Collections.singletonList(exportVariant());
        stubReadyExport(analysisService, 50, variants);

        String csv = exportService.exportCsv(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        verify(analysisService).listVariants(
                eq("analysis"),
                eq(1),
                eq(50),
                eq(VariantInterpretationView.CANDIDATES),
                eq(VariantInterpretationFilter.empty())
        );
        assertTrue(csv.startsWith("\uFEFFrank,tier,chrom,pos"));
        assertTrue(csv.contains("KNOWN_CLINICAL"));
        assertTrue(csv.contains("clinical=100.0"));
        assertTrue(csv.contains("Likely_pathogenic"));
        assertTrue(csv.contains("LOCAL_GNOMAD"));
        assertTrue(csv.contains("spliceai_status,spliceai_max_delta_score,spliceai_event"));
        assertTrue(csv.contains("analysis_bucket,large_file_mode,summary_only"));
        assertTrue(csv.contains("analysis_stage,ready_fast,ready_full,enrichment_status"));
        assertTrue(csv.contains("READY_FULL,true,true,READY_FULL"));
        assertTrue(csv.contains("phenotype_support,phenotype_score,phenotype_score_boost"));
        assertTrue(csv.contains("phenotype_gene_prior_rank,phenotype_gene_prior_source"));
        assertTrue(csv.contains("trio_model,trio_confidence,trio_parental_origin"));
        assertTrue(csv.contains("DE_NOVO_CANDIDATE,SUPPORTED,NONE"));
        assertTrue(csv.contains("APPLICABLE,0.84,donor_loss,'-4,GENE1,LOCAL_SPLICEAI"));
        assertTrue(csv.contains(",NO,,,,,,,,DE_NOVO_CANDIDATE"));
        assertTrue(csv.contains("ClinVar supports pathogenicity"));
        assertTrue(csv.contains("PM2_candidate:SUGGESTED:SUPPORTING"));
        String header = csv.substring(0, csv.indexOf('\n')).replace("\uFEFF", "").trim();
        assertTrue(header.startsWith("rank,tier,chrom,pos,ref,alt,original_variant,gene"));
        assertTrue(header.contains("candidate_id,canonical_variant_id,variant_id,candidate_source,"
                + "candidate_source_ordinal,candidate_gene,candidate_moi,candidate_declared_rank,"
                + "exomiser_engine_version"));
        assertTrue(header.contains("exomiser_field_sources,exomiser_missing_fields,"
                + "exomiser_invalid_fields,analysis_vcf_call_schema_version"));
        assertTrue(header.endsWith("analysis_vcf_call_provenance,analysis_vcf_call_missing_fields,"
                + "analysis_vcf_call_invalid_fields"));
        assertFalse(header.contains("exomiser_variant_rank"));
        assertTrue(csv.contains(exportVariant().getCandidateId()));
        assertFalse(csv.toLowerCase().contains("low risk"));
        assertFalse(csv.toLowerCase().contains("benign"));
    }

    @Test
    void exportsDuplicateCanonicalRowsWithDistinctCandidateIds() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantQcObservation observation = exportVariant().getObservation();
        VariantInterpretation first = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        observation.getKey().canonicalId(), 1, "GENE1", "AUTOSOMAL_DOMINANT", 1
                ),
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
        VariantInterpretation second = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        observation.getKey().canonicalId(), 2, "GENE1", "AUTOSOMAL_RECESSIVE", 2
                ),
                observation,
                Collections.emptyList(),
                Collections.emptyList()
        );
        stubReadyExport(analysisService, 50, Arrays.asList(first, second));

        String csv = exportService.exportCsv(
                "analysis", 50, VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty());
        VariantAnalysisExportBundleResponse json = exportService.exportJson(
                "analysis", 50, VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty());

        assertTrue(csv.contains("exomiser-v1-00000001"));
        assertTrue(csv.contains("exomiser-v1-00000002"));
        assertEquals(2, json.getTopCandidates().size());
        assertEquals("exomiser-v1-00000001", json.getTopCandidates().get(0).get("candidateId"));
        assertEquals("exomiser-v1-00000002", json.getTopCandidates().get(1).get("candidateId"));
        assertEquals(
                json.getTopCandidates().get(0).get("canonicalVariantId"),
                json.getTopCandidates().get(1).get("canonicalVariantId")
        );
    }

    @Test
    void exportsTypedExomiserContextWithoutInventingAVariantRank() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantInterpretation variant = exomiserExportVariant();
        stubReadyExport(analysisService, 50, Collections.singletonList(variant));

        String csv = exportService.exportCsv(
                "analysis", 50, VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty());
        VariantAnalysisExportBundleResponse json = exportService.exportJson(
                "analysis", 50, VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty());

        String header = csv.substring(0, csv.indexOf('\n')).replace("\uFEFF", "").trim();
        assertTrue(header.contains("exomiser_gene_moi_group_rank"));
        assertTrue(header.contains("exomiser_gene_moi_group_rank_semantics"));
        assertTrue(header.contains("exomiser_ranking_score_source_column"));
        assertFalse(header.contains("exomiser_variant_rank"));
        assertTrue(csv.contains("EXOMISER_GENE_MOI_GROUP_NATIVE_RANK"));
        assertTrue(csv.contains("EXOMISER_VARIANT_SCORE"));
        assertTrue(csv.contains("missense_variant"));
        assertTrue(csv.contains("NM_000001.1:c.1A>G"));

        @SuppressWarnings("unchecked")
        Map<String, Object> features = (Map<String, Object>) json.getTopCandidates().get(0)
                .get("exomiserCandidateFeatures");
        assertNotNull(features);
        assertEquals("exomiser-candidate-features-v1", features.get("schemaVersion"));
        assertEquals("GENE1", features.get("geneSymbol"));
        assertEquals(4, features.get("geneMoiGroupRank"));
        assertEquals("EXOMISER_GENE_MOI_GROUP_NATIVE_RANK", features.get("geneMoiGroupRankSemantics"));
        assertEquals(0.81d, features.get("variantScore"));
        assertEquals("EXOMISER_VARIANT_SCORE", features.get("rankingScoreSourceColumn"));
    }

    @Test
    void appendsWhitelistedAnalysisVcfCallFactsToCsvAndCandidateJson() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        AnalysisVcfCall call = AnalysisVcfCall.matched(
                AnalysisVcfCall.TargetAlleleCall.TARGET_WITH_OTHER_ALT,
                "TARGET_ALT|OTHER_ALT",
                2,
                40,
                70,
                18,
                12,
                0.4d,
                AnalysisVcfCall.FilterEvaluation.PASS,
                AnalysisVcfCall.PhasingStatus.PHASE_SET,
                "0123456789abcdef01234567",
                9,
                GenomeAssembly.GRCh38,
                Collections.singletonList("DP"),
                Collections.singletonList("GQ")
        );
        VariantInterpretation variant = exportVariant().withAnalysisVcfCall(call);
        stubReadyExport(analysisService, 50, Collections.singletonList(variant));

        String csv = exportService.exportCsv(
                "analysis", 50, VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty());
        VariantAnalysisExportBundleResponse json = exportService.exportJson(
                "analysis", 50, VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty());

        String header = csv.substring(0, csv.indexOf('\n')).replace("\uFEFF", "").trim();
        assertTrue(header.endsWith("analysis_vcf_call_provenance,analysis_vcf_call_missing_fields,"
                + "analysis_vcf_call_invalid_fields"));
        assertTrue(csv.contains("MATCHED,TARGET_WITH_OTHER_ALT,TARGET_ALT|OTHER_ALT,2,40,70,18,12,0.4"));
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) json.getTopCandidates().get(0)
                .get("analysisVcfCallContext");
        assertNotNull(context);
        assertEquals("analysis-vcf-call-v1", context.get("schemaVersion"));
        assertEquals("MATCHED", context.get("matchStatus"));
        assertEquals("TARGET_ALT|OTHER_ALT", context.get("candidateRelativeGenotype"));
        assertEquals(18, context.get("referenceDepth"));
        assertEquals(12, context.get("targetAlleleDepth"));
        assertEquals("PHASE_SET", context.get("phasingStatus"));
        assertFalse(context.containsKey("sampleId"));
        assertFalse(context.containsKey("rawGenotype"));
        assertFalse(context.containsKey("originalRecord"));
        assertFalse(context.containsKey("rawPhaseSet"));
        assertFalse(context.containsKey("path"));
    }

    @Test
    void csvNeutralizesSpreadsheetFormulasInStructuredTextFields() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantInterpretation variant = exomiserExportVariant("=1+1");
        stubReadyExport(analysisService, 50, Collections.singletonList(variant));

        String csv = exportService.exportCsv(
                "analysis", 50, VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty());

        assertTrue(csv.contains(",'=1+1,"));
        assertFalse(csv.contains(",=1+1,"));
    }

    @Test
    void csvExportHonorsLimitAndEscapesCommaQuoteAndNewline() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantInterpretation variant = exportVariant();
        stubReadyExport(analysisService, 1, Collections.singletonList(variant));

        String csv = exportService.exportCsv(
                "analysis",
                1,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        verify(analysisService).listVariants(
                eq("analysis"),
                eq(1),
                eq(1),
                eq(VariantInterpretationView.CANDIDATES),
                eq(VariantInterpretationFilter.empty())
        );
        assertTrue(csv.contains("\"chr1,100,\"\"A\"\",G\nnote\""));
    }

    @Test
    void csvExportSupportsAllTwentyThousandPersistedRows() {
        assertEquals(5000, service(mock(VariantAnalysisService.class)).normalizeCsvLimit(50000));
    }

    @Test
    void csvExportReadsFilteredResultsInThousandRowBatches() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantInterpretationFilter filter = VariantInterpretationFilter.empty();
        when(analysisService.get("analysis")).thenReturn(readyJob());
        when(analysisService.listEvents("analysis")).thenReturn(Collections.emptyList());
        when(analysisService.listVariants(
                "analysis", 1, 1000, VariantInterpretationView.CANDIDATES, filter
        )).thenReturn(new VariantAnalysisVariantPageResponse(
                "analysis", VariantInterpretationView.CANDIDATES, 1, 1000,
                1500, 1500, 0, 1500, 0.01d, Collections.singletonList(exportVariant())
        ));
        when(analysisService.listVariants(
                "analysis", 2, 1000, VariantInterpretationView.CANDIDATES, filter
        )).thenReturn(new VariantAnalysisVariantPageResponse(
                "analysis", VariantInterpretationView.CANDIDATES, 2, 1000,
                1500, 1500, 0, 1500, 0.01d, Collections.singletonList(exportVariant())
        ));

        String csv = exportService.exportCsv(
                "analysis", 1500, VariantInterpretationView.CANDIDATES, filter
        );

        verify(analysisService).listVariants(
                "analysis", 1, 1000, VariantInterpretationView.CANDIDATES, filter
        );
        verify(analysisService).listVariants(
                "analysis", 2, 1000, VariantInterpretationView.CANDIDATES, filter
        );
        assertTrue(csv.contains("\n1,"));
        assertTrue(csv.contains("\n2,"));
    }

    @Test
    void jsonExportContainsStableBundleAndRedactsLocalPaths() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        stubReadyExport(analysisService, 50, Collections.singletonList(exportVariant()));

        VariantAnalysisExportBundleResponse bundle = exportService.exportJson(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        assertEquals("analysis", bundle.getJobMetadata().get("analysisId"));
        assertEquals("READY_FULL", bundle.getJobMetadata().get("analysisStage"));
        assertEquals("READY_FULL", bundle.getJobMetadata().get("enrichmentStatus"));
        assertEquals(true, ((Map<?, ?>) bundle.getJobMetadata().get("providerProgress")).get("largeFileMode"));
        assertEquals("GRCh38", bundle.getReference().get("genomeBuild"));
        assertEquals("tier-breakdown-v1", bundle.getRanking().get("version"));
        assertEquals("DISABLED", bundle.getTranscriptAnnotationStatus());
        assertEquals("DISABLED", bundle.getVepExecutionSummary().get("status"));
        assertEquals(1, bundle.getTopCandidates().size());
        assertTrue(bundle.getTopCandidates().get(0).containsKey("transcriptAnnotations"));
        assertTrue(bundle.getTopCandidates().get(0).containsKey("analysisBucket"));
        assertEquals(VariantAnalysisBucket.KNOWN_CLINICAL,
                bundle.getTopCandidates().get(0).get("analysisBucket"));
        assertEquals(false, bundle.getTopCandidates().get(0).get("summaryOnly"));
        assertEquals(true, bundle.getTopCandidates().get(0).get("fullInterpretationAvailable"));
        assertTrue(bundle.getTopCandidates().get(0).containsKey("largeFileDecision"));
        assertTrue(bundle.getTopCandidates().get(0).containsKey("acmgEvidenceSuggestions"));
        assertTrue(bundle.getTopCandidates().get(0).containsKey("phenotypeSupport"));
        Map<?, ?> canonicalIdentity =
                (Map<?, ?>) bundle.getTopCandidates().get(0).get("canonicalIdentity");
        assertEquals("varvis-canonical-v1", canonicalIdentity.get("schemaVersion"));
        assertEquals("REFERENCE_PROFILE_UNRESOLVED", canonicalIdentity.get("status"));
        assertEquals("REFERENCE_PROFILE_REQUIRED", canonicalIdentity.get("vrsCompatibilityStatus"));
        assertNull(canonicalIdentity.get("vrsComputedIdentifier"));
        Map<?, ?> observation = (Map<?, ?>) bundle.getTopCandidates().get(0).get("observation");
        assertEquals("GRCh38|1|100|A|G", observation.get("canonicalVariantId"));
        assertEquals("UNRESOLVED_ORIGINAL", observation.get("normalizationMappingStatus"));
        List<?> evidenceItems = (List<?>) bundle.getTopCandidates().get(0).get("evidence");
        Map<?, ?> exportedEvidence = (Map<?, ?>) evidenceItems.get(0);
        assertEquals(EvidenceItem.CURRENT_SCHEMA_VERSION, exportedEvidence.get("schemaVersion"));
        assertEquals("GRCh38|1|100|A|G", exportedEvidence.get("canonicalVariantId"));
        assertEquals("1", exportedEvidence.get("normalizedContig"));
        assertEquals(100, exportedEvidence.get("normalizedPosition"));
        assertEquals("A", exportedEvidence.get("normalizedReference"));
        assertEquals("G", exportedEvidence.get("normalizedAlternate"));
        assertEquals(exportedEvidence.get("sourceVersion"), exportedEvidence.get("resourceVersion"));
        assertEquals(exportedEvidence.get("applicability"), exportedEvidence.get("status"));
        Map<?, ?> clinVarDetails = (Map<?, ?>) exportedEvidence.get("details");
        assertEquals("Likely_pathogenic", clinVarDetails.get("aggregateClassification"));
        assertEquals("NO_CONFLICT", clinVarDetails.get("conflictState"));
        assertEquals(2, ((List<?>) clinVarDetails.get("conditions")).size());
        Map<?, ?> unsupportedPhenotype =
                (Map<?, ?>) bundle.getTopCandidates().get(0).get("phenotypeSupport");
        assertEquals(false, unsupportedPhenotype.get("supported"));
        assertFalse(String.valueOf(unsupportedPhenotype).toLowerCase().contains("benign"));
        assertFalse(String.valueOf(unsupportedPhenotype).toLowerCase().contains("low risk"));
        assertTrue(String.valueOf(bundle.getTopCandidates().get(0).get("transcriptAnnotations"))
                .contains("ANN_ONLY"));
        assertTrue(String.valueOf(bundle.getTopCandidates().get(0).get("acmgEvidenceSuggestions"))
                .contains("PM2_candidate"));
        Map<String, Object> exportedCandidate = bundle.getTopCandidates().get(0);
        assertEquals(exportVariant().getCandidateId(), exportedCandidate.get("candidateId"));
        assertEquals(exportVariant().getCanonicalVariantId(), exportedCandidate.get("canonicalVariantId"));
        assertEquals(exportVariant().getVariantId(), exportedCandidate.get("variantId"));
        assertTrue(exportedCandidate.containsKey("candidateIdentity"));
        assertEquals(1, bundle.getAcmgReadySummary().get("totalCandidatesWithSuggestions"));
        assertEquals(1, bundle.getAcmgReadySummary().get("suggestedCount"));
        assertEquals(0, bundle.getAcmgReadySummary().get("manualReviewRequiredCount"));
        assertTrue(String.valueOf(bundle.getAcmgReadySummary().get("codeCounts"))
                .contains("PM2_candidate"));
        assertTrue(String.valueOf(bundle.getAcmgReadySummary().get("disclaimer"))
                .contains("does not provide a final clinical ACMG classification"));
        assertEquals(bundle.getAcmgReadySummary(), bundle.getSummary().get("acmgReadySummary"));
        assertTrue(bundle.getSummary().containsKey("bucketCounts"));
        assertTrue(bundle.getSummary().containsKey("largeFileSummary"));
        assertTrue(bundle.getSummary().containsKey("persistencePolicy"));
        assertEquals("READY_FULL", bundle.getSummary().get("analysisStage"));
        assertEquals("READY_FULL", bundle.getSummary().get("enrichmentStatus"));
        assertTrue(bundle.getSummary().containsKey("progressiveStatus"));
        assertNotNull(bundle.getSummary().get("phenotypeSummary"));
        PhenotypeAwareSummary phenotypeSummary =
                (PhenotypeAwareSummary) bundle.getSummary().get("phenotypeSummary");
        assertFalse(phenotypeSummary.isEnabled());
        assertFalse(phenotypeSummary.isRankingApplied());
        assertNotNull(bundle.getProviderStatus());
        assertNotNull(bundle.getGeneratedAt());
        String exported = String.valueOf(bundle.getTopCandidates()) + bundle.getRuntimeMetrics();
        assertFalse(exported.contains("D:\\LSD"));
        assertFalse(exported.contains("sourceFile"));
    }

    @Test
    void jsonExportSafelyProjectsSyntheticLegacyEvidencePayload() throws Exception {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantInterpretation legacy = new ObjectMapper().readValue(
                legacyPayload(),
                VariantInterpretation.class
        );
        stubReadyExport(analysisService, 1, Collections.singletonList(legacy));

        VariantAnalysisExportBundleResponse bundle = exportService.exportJson(
                "analysis", 1, VariantInterpretationView.CANDIDATES, VariantInterpretationFilter.empty()
        );

        List<?> evidence = (List<?>) bundle.getTopCandidates().get(0).get("evidence");
        Map<?, ?> noRecord = (Map<?, ?>) evidence.get(0);
        Map<?, ?> unavailable = (Map<?, ?>) evidence.get(1);
        assertEquals("NO_RECORD", noRecord.get("status"));
        assertNull(noRecord.get("rawScore"));
        assertEquals(Collections.emptyMap(), noRecord.get("provenance"));
        assertEquals("UNAVAILABLE", unavailable.get("status"));
        assertNull(unavailable.get("rawScore"));
        assertEquals(Collections.emptyMap(), unavailable.get("provenance"));
        String exported = String.valueOf(evidence);
        assertFalse(exported.contains("undefined"));
        assertFalse(exported.contains("native_tabix"));
        assertFalse(exported.contains("VERIFIED"));
    }

    private String legacyPayload() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/variantanalysis/evidence/legacy-evidence-payload-v0.json")) {
            assertNotNull(input, "Synthetic legacy payload fixture must be present");
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void jsonExportBindsCanonicalIdentityToHistoricalNormalizationProfile() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        stubReadyExport(analysisService, 50, Collections.singletonList(exportVariant()));
        when(analysisService.listEvents("analysis")).thenReturn(Collections.singletonList(
                new VariantAnalysisEvent(
                        1L,
                        "analysis",
                        VariantAnalysisStatus.QC_EXTRACTING,
                        "Reference normalization PASSED: assembly=GRCh38, "
                                + "profile=grch38-profile-v1, toolVersion=1.23.1, rawRecords=1, "
                                + "normalizedAlleles=1, splitRecords=0, realignedRecords=0, "
                                + "skippedRecords=0, elapsedMs=10",
                        Instant.now()
                )
        ));

        VariantAnalysisExportBundleResponse bundle = exportService.exportJson(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        Map<?, ?> canonicalIdentity =
                (Map<?, ?>) bundle.getTopCandidates().get(0).get("canonicalIdentity");
        assertEquals("REFERENCE_PROFILE_BOUND", canonicalIdentity.get("status"));
        assertEquals("grch38-profile-v1", canonicalIdentity.get("referenceProfileId"));
        assertTrue(String.valueOf(canonicalIdentity.get("varvisComputedIdentifier"))
                .startsWith("varvis:canonical:"));
        assertNull(canonicalIdentity.get("vrsComputedIdentifier"));
    }

    @Test
    void jsonExportIncludesNoOpPhenotypeSummaryWhenHpoTermsWereCaptured() throws Exception {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(Arrays.asList(
                "HP:0001250",
                "hp:0001250",
                "abc"
        ));
        summary = summary.withGenePriorSummary(enabledLocalGenePriorProvider(1).summarize(summary));
        stubReadyExport(analysisService, 50, Collections.singletonList(exportVariant()), summary);

        VariantAnalysisExportBundleResponse bundle = exportService.exportJson(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        PhenotypeAwareSummary exported =
                (PhenotypeAwareSummary) bundle.getSummary().get("phenotypeSummary");
        assertTrue(exported.isEnabled());
        assertFalse(exported.isRankingApplied());
        assertEquals(3, exported.getInputHpoCount());
        assertEquals(1, exported.getValidHpoCount());
        assertEquals(1, exported.getInvalidHpoCount());
        assertEquals(1, exported.getDuplicateHpoCount());
        assertEquals(Collections.singletonList("HP:0001250"), exported.getValidHpoTerms());
        assertTrue(exported.getWarnings().contains(PhenotypeAwareSummary.RANKING_NOT_APPLIED_WARNING));
        assertEquals(GenePhenotypePriorStatus.AVAILABLE, exported.getGenePriorSummary().getStatus());
        assertEquals(1, exported.getGenePriorSummary().getGenesWithPrior());
        assertEquals("LOCAL_GENE_PRIOR", exported.getGenePriorSummary().getSource());
        assertEquals("MECP2", exported.getGenePriorSummary().getTopGenePriors().get(0).getGeneSymbol());
    }

    @Test
    void jsonAndCsvExportsRemainCompatibleWhenGenePriorUnavailable() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        Path missingFile = tempDir.resolve("missing-local-gene-prior.tsv");
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("HP:0001250")
        );
        summary = summary.withGenePriorSummary(missingLocalGenePriorProvider(missingFile).summarize(summary));
        stubReadyExport(analysisService, 50, Collections.singletonList(exportVariant()), summary);

        VariantAnalysisExportBundleResponse bundle = exportService.exportJson(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        PhenotypeAwareSummary exported =
                (PhenotypeAwareSummary) bundle.getSummary().get("phenotypeSummary");
        assertTrue(exported.isEnabled());
        assertFalse(exported.isRankingApplied());
        assertEquals(GenePhenotypePriorStatus.UNAVAILABLE, exported.getGenePriorSummary().getStatus());
        String warnings = String.valueOf(exported.getGenePriorSummary().getWarnings());
        assertTrue(warnings.contains("Provider unavailable does not imply low risk"));
        assertFalse(warnings.contains(missingFile.toString()));

        String csv = exportService.exportCsv(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        String header = csv.substring(0, csv.indexOf('\n')).replace("\uFEFF", "");
        assertTrue(header.startsWith("rank,tier,chrom,pos,ref,alt,original_variant,gene"));
        assertTrue(header.toLowerCase().contains("phenotype_support"));
        assertTrue(header.toLowerCase().contains("phenotype_gene_prior_source"));
        assertFalse(csv.contains(missingFile.toString()));
        assertFalse(csv.toLowerCase().contains("low risk"));
    }

    @Test
    void jsonExportIncludesPhenotypeRankingSummaryAndSupportedCandidateEvidence() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantInterpretation variant = exportVariant();
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("HP:0001250")
        ).withGenePriorSummary(genePriorSummary("GENE1", 0.95d, 1));
        summary = enabledPhenotypeReranker().apply(Collections.singletonList(variant), summary);
        stubReadyExport(analysisService, 50, Collections.singletonList(variant), summary);

        VariantAnalysisExportBundleResponse bundle = exportService.exportJson(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        PhenotypeAwareSummary exported =
                (PhenotypeAwareSummary) bundle.getSummary().get("phenotypeSummary");
        assertTrue(exported.isRankingApplied());
        assertEquals(1, exported.getCandidatesWithPhenotypeSupport());
        assertEquals("GENE1", exported.getTopPhenotypeSupportedCandidates().get(0).getGeneSymbol());
        assertEquals(GenePhenotypePriorStatus.AVAILABLE, exported.getGenePriorSummary().getStatus());
        Map<String, Object> candidate = bundle.getTopCandidates().get(0);
        assertTrue(String.valueOf(candidate.get("scoreComponents")).contains("phenotype_support"));
        assertTrue(String.valueOf(candidate.get("evidence")).contains("PHENOTYPE_GENE_PRIOR"));
        assertTrue(String.valueOf(candidate.get("positiveReasons")).contains("Gene GENE1 is supported"));
        Map<?, ?> phenotypeSupport = (Map<?, ?>) candidate.get("phenotypeSupport");
        assertEquals(true, phenotypeSupport.get("supported"));
        assertEquals(0.95d, (Double) phenotypeSupport.get("phenotypeScore"), 0.0001d);
        assertEquals(7.2d, (Double) phenotypeSupport.get("scoreBoost"), 0.0001d);
        assertEquals(1, phenotypeSupport.get("genePriorRank"));
        assertEquals("LOCAL_GENE_PRIOR", phenotypeSupport.get("source"));
        assertEquals(Collections.singletonList("HP:0001250"), phenotypeSupport.get("matchedHpoTerms"));
        assertTrue(String.valueOf(phenotypeSupport.get("evidence"))
                .contains("HP:0001250: Mock phenotype-gene association"));
        assertEquals(
                "Phenotype support is used for candidate prioritization only. It is not a diagnosis.",
                phenotypeSupport.get("warning")
        );
    }

    @Test
    void csvExportIncludesPhenotypeAwareFields() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantInterpretation variant = phenotypeSupportedExportVariant();
        stubReadyExport(analysisService, 50, Collections.singletonList(variant));

        String csv = exportService.exportCsv(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        String header = csv.substring(0, csv.indexOf('\n')).replace("\uFEFF", "");
        assertTrue(header.contains("phenotype_support"));
        assertTrue(header.contains("phenotype_score"));
        assertTrue(header.contains("phenotype_score_boost"));
        assertTrue(header.contains("phenotype_gene_prior_rank"));
        assertTrue(header.contains("phenotype_gene_prior_source"));
        assertTrue(header.contains("phenotype_matched_hpo_terms"));
        assertTrue(header.contains("phenotype_evidence"));
        assertTrue(header.contains("phenotype_warning"));
        assertTrue(csv.contains(",YES,0.95,7.2,1,LOCAL_GENE_PRIOR,HP:0001250; HP:0004322,"));
        assertTrue(csv.contains("\"HP:0001250: Mock, phenotype association; HP:0004322: second association; "
                + "Gene GENE1 is supported by phenotype prior; this is not a diagnosis.\""));
        assertTrue(csv.contains("Phenotype support is used for candidate prioritization only. "
                + "It is not a diagnosis."));
        assertFalse(csv.toLowerCase().contains("low risk"));
        assertFalse(csv.toLowerCase().contains("benign"));
    }

    @Test
    void csvAndJsonExportsCarryPhen2GeneImportPrior() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantInterpretation variant = exportVariant();
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(
                Collections.singletonList("HP:0001250")
        ).withGenePriorSummary(phen2GeneImportPriorSummary("GENE1", 0.89883d, 4));
        summary = enabledPhenotypeReranker().apply(Collections.singletonList(variant), summary);
        stubReadyExport(analysisService, 50, Collections.singletonList(variant), summary);

        String csv = exportService.exportCsv(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );
        VariantAnalysisExportBundleResponse bundle = exportService.exportJson(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        assertTrue(csv.contains(",YES,0.89883,7.2,4,PHEN2GENE_IMPORT,HP:0001250,"));
        Map<?, ?> phenotypeSupport =
                (Map<?, ?>) bundle.getTopCandidates().get(0).get("phenotypeSupport");
        assertEquals(true, phenotypeSupport.get("supported"));
        assertEquals("PHEN2GENE_IMPORT", phenotypeSupport.get("source"));
        assertEquals(4, phenotypeSupport.get("genePriorRank"));
        assertTrue(String.valueOf(phenotypeSupport.get("evidence"))
                .contains("Phen2Gene candidate gene prior"));
    }

    @Test
    void jsonExportUsesLatestVepEventSummary() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        stubReadyExport(analysisService, 50, Collections.singletonList(exportVariant()));
        when(analysisService.listEvents("analysis")).thenReturn(Arrays.asList(
                new VariantAnalysisEvent(1L, "analysis", VariantAnalysisStatus.READY,
                        "Variant analysis is ready", Instant.now()),
                new VariantAnalysisEvent(2L, "analysis", VariantAnalysisStatus.READY,
                        "VEP transcript annotation COMPLETED: selected=3, processed=3, success=2, "
                                + "failed=1, cacheHit=1, elapsedMs=99, warnings=1",
                        Instant.now())
        ));

        VariantAnalysisExportBundleResponse bundle = exportService.exportJson(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty()
        );

        assertEquals("COMPLETED", bundle.getTranscriptAnnotationStatus());
        assertEquals("EVENT_TIMELINE", bundle.getVepExecutionSummary().get("summarySource"));
        assertEquals(3, bundle.getVepExecutionSummary().get("selectedCount"));
        assertEquals(3, bundle.getVepExecutionSummary().get("processedCount"));
        assertEquals(2, bundle.getVepExecutionSummary().get("successCount"));
        assertEquals(1, bundle.getVepExecutionSummary().get("failedCount"));
        assertEquals(1, bundle.getVepExecutionSummary().get("cacheHitCount"));
        assertEquals(99L, bundle.getVepExecutionSummary().get("elapsedMillis"));
        assertEquals(1, bundle.getVepExecutionSummary().get("warningCount"));
    }

    @Test
    void exportReusesFilterParameters() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
        VariantInterpretationFilter filter = new VariantInterpretationFilter(
                "KNOWN_CLINICAL",
                true,
                false,
                "PLP",
                "GENE1",
                "PM2_candidate,PP3_candidate",
                "SUGGESTED",
                true,
                true,
                true,
                "GENE1",
                0.7d
        );
        stubReadyExport(analysisService, 50, Collections.singletonList(exportVariant()));

        exportService.exportJson("analysis", null, VariantInterpretationView.ALL, filter);

        verify(analysisService).listVariants(
                eq("analysis"),
                eq(1),
                eq(50),
                eq(VariantInterpretationView.ALL),
                eq(filter)
        );
    }

    @Test
    void csvExportReusesPhenotypeSupportFilterWithoutChangingHeader() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        VariantAnalysisExportService exportService = service(analysisService);
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
                true,
                null,
                null
        );
        VariantAnalysisJob job = readyJob();
        VariantInterpretation variant = phenotypeSupportedExportVariant();
        when(analysisService.get("analysis")).thenReturn(job);
        when(analysisService.listEvents("analysis")).thenReturn(Collections.emptyList());
        when(analysisService.listVariants(
                eq("analysis"),
                eq(1),
                eq(50),
                eq(VariantInterpretationView.CANDIDATES),
                eq(filter)
        )).thenReturn(page(VariantInterpretationView.CANDIDATES, Collections.singletonList(variant)));

        String csv = exportService.exportCsv(
                "analysis",
                null,
                VariantInterpretationView.CANDIDATES,
                filter
        );

        verify(analysisService).listVariants(
                eq("analysis"),
                eq(1),
                eq(50),
                eq(VariantInterpretationView.CANDIDATES),
                eq(filter)
        );
        String header = csv.substring(0, csv.indexOf('\n')).replace("\uFEFF", "");
        assertTrue(header.startsWith("rank,tier,chrom,pos"));
        assertTrue(header.toLowerCase().contains("phenotype_support"));
        assertTrue(csv.contains(",YES,0.95,7.2,1,LOCAL_GENE_PRIOR,"));
    }

    @Test
    void exportRejectsUnfinishedJob() {
        VariantAnalysisService analysisService = mock(VariantAnalysisService.class);
        when(analysisService.get("analysis")).thenReturn(uploadedJob());
        VariantAnalysisExportService exportService = service(analysisService);

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> exportService.exportCsv(
                        "analysis",
                        null,
                        VariantInterpretationView.CANDIDATES,
                        VariantInterpretationFilter.empty()
                )
        );

        assertEquals(409, ex.getStatusCode().value());
    }

    private VariantAnalysisExportService service(VariantAnalysisService analysisService) {
        return new VariantAnalysisExportService(analysisService, new VariantAnalysisProperties());
    }

    private LocalGenePhenotypePriorProvider enabledLocalGenePriorProvider(int maxGenes) throws Exception {
        VariantAnalysisProperties.PhenotypePrior properties = new VariantAnalysisProperties.PhenotypePrior();
        properties.setEnabled(true);
        properties.setSource("LOCAL_GENE_PRIOR");
        properties.setVersion("mock-v1");
        properties.setMaxGenes(maxGenes);
        URL resource = getClass().getResource("/variantanalysis/phenotype-prior/mock-gene-prior.tsv");
        assertNotNull(resource);
        properties.setLocalFile(Paths.get(resource.toURI()).toString());
        return new LocalGenePhenotypePriorProvider(properties);
    }

    private LocalGenePhenotypePriorProvider missingLocalGenePriorProvider(Path missingFile) {
        VariantAnalysisProperties.PhenotypePrior properties = new VariantAnalysisProperties.PhenotypePrior();
        properties.setEnabled(true);
        properties.setSource("LOCAL_GENE_PRIOR");
        properties.setVersion("mock-v1");
        properties.setMaxGenes(50);
        properties.setLocalFile(missingFile.toString());
        return new LocalGenePhenotypePriorProvider(properties);
    }

    private PhenotypeAwareRerankingService enabledPhenotypeReranker() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getPhenotypeRanking().setEnabled(true);
        return new PhenotypeAwareRerankingService(properties);
    }

    private GenePhenotypePriorSummary genePriorSummary(String geneSymbol, double score, int rank) {
        return genePriorSummary(
                geneSymbol,
                score,
                rank,
                Collections.singletonList("HP:0001250"),
                Collections.singletonList("HP:0001250: Mock phenotype-gene association")
        );
    }

    private GenePhenotypePriorSummary genePriorSummary(String geneSymbol,
                                                       double score,
                                                       int rank,
                                                       List<String> matchedHpoTerms,
                                                       List<String> evidence) {
        GenePhenotypePrior prior = new GenePhenotypePrior(
                geneSymbol,
                "ENSG000001",
                score,
                rank,
                "LOCAL_GENE_PRIOR",
                "mock-v1",
                matchedHpoTerms,
                evidence,
                GenePhenotypePriorStatus.AVAILABLE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Collections.emptyList()
        );
        return new GenePhenotypePriorSummary(
                true,
                "LOCAL_GENE_PRIOR",
                "mock-v1",
                GenePhenotypePriorStatus.AVAILABLE,
                1,
                1,
                1,
                Collections.singletonList(prior),
                Collections.emptyList(),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private GenePhenotypePriorSummary phen2GeneImportPriorSummary(String geneSymbol, double score, int rank) {
        GenePhenotypePrior prior = new GenePhenotypePrior(
                geneSymbol,
                "4204",
                score,
                rank,
                "PHEN2GENE_IMPORT",
                "phen2gene-import-v1",
                Collections.singletonList("HP:0001250"),
                Arrays.asList(
                        "Phen2Gene candidate gene prior",
                        "Phen2Gene status: SeedGene"
                ),
                GenePhenotypePriorStatus.AVAILABLE,
                Instant.parse("2026-01-01T00:00:00Z"),
                Collections.emptyList()
        );
        return new GenePhenotypePriorSummary(
                true,
                "PHEN2GENE_IMPORT",
                "phen2gene-import-v1",
                GenePhenotypePriorStatus.AVAILABLE,
                1,
                1,
                1,
                Collections.singletonList(prior),
                Collections.emptyList(),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }

    private VariantInterpretation phenotypeSupportedExportVariant() {
        VariantInterpretation variant = exportVariant();
        PhenotypeAwareSummary summary = PhenotypeInputNormalizer.summarize(Arrays.asList(
                "HP:0001250",
                "HP:0004322"
        )).withGenePriorSummary(genePriorSummary(
                "GENE1",
                0.95d,
                1,
                Arrays.asList("HP:0001250", "HP:0004322"),
                Arrays.asList(
                        "HP:0001250: Mock, phenotype association",
                        "HP:0004322: second association"
                )
        ));
        enabledPhenotypeReranker().apply(Collections.singletonList(variant), summary);
        return variant;
    }

    private void stubReadyExport(VariantAnalysisService analysisService,
                                 int expectedLimit,
                                 List<VariantInterpretation> variants) {
        stubReadyExport(analysisService, expectedLimit, variants, null);
    }

    private void stubReadyExport(VariantAnalysisService analysisService,
                                 int expectedLimit,
                                 List<VariantInterpretation> variants,
                                 PhenotypeAwareSummary phenotypeSummary) {
        VariantAnalysisJob job = readyJob();
        if (phenotypeSummary != null) {
            job.applyPhenotypeSummary(phenotypeSummary);
        }
        when(analysisService.get("analysis")).thenReturn(job);
        when(analysisService.listEvents("analysis")).thenReturn(Collections.singletonList(
                new VariantAnalysisEvent(1L, "analysis", VariantAnalysisStatus.READY,
                        "Completed in D:\\LSD\\private\\workspace", Instant.now())
        ));
        when(analysisService.listVariants(
                eq("analysis"),
                eq(1),
                eq(expectedLimit),
                eq(VariantInterpretationView.CANDIDATES),
                eq(VariantInterpretationFilter.empty())
        )).thenReturn(page(VariantInterpretationView.CANDIDATES, variants));
        when(analysisService.listVariants(
                eq("analysis"),
                eq(1),
                eq(expectedLimit),
                eq(VariantInterpretationView.ALL),
                org.mockito.ArgumentMatchers.any(VariantInterpretationFilter.class)
        )).thenReturn(page(VariantInterpretationView.ALL, variants));
    }

    private VariantAnalysisVariantPageResponse page(VariantInterpretationView view,
                                                    List<VariantInterpretation> variants) {
        return new VariantAnalysisVariantPageResponse(
                "analysis",
                view,
                1,
                variants.size(),
                variants.size(),
                1,
                0,
                variants.size(),
                counts(variants),
                0.01d,
                variants
        );
    }

    private VariantInterpretationCounts counts(List<VariantInterpretation> variants) {
        Map<String, Integer> bucketCounts = new LinkedHashMap<String, Integer>();
        for (VariantAnalysisBucket bucket : VariantAnalysisBucket.values()) {
            bucketCounts.put(bucket.name(), 0);
        }
        int summaryOnly = 0;
        int full = 0;
        int skipped = 0;
        int largeFile = 0;
        for (VariantInterpretation variant : variants) {
            VariantAnalysisBucket bucket = variant.getAnalysisBucket() == null
                    ? VariantAnalysisBucket.CANDIDATE
                    : variant.getAnalysisBucket();
            bucketCounts.put(bucket.name(), bucketCounts.get(bucket.name()) + 1);
            if (variant.isSummaryOnly()) {
                summaryOnly++;
            }
            if (variant.isFullInterpretationAvailable()) {
                full++;
            }
            if (variant.isExpensiveAnnotationSkipped()) {
                skipped++;
            }
            if (variant.isLargeFileMode()) {
                largeFile++;
            }
        }
        return new VariantInterpretationCounts(
                1,
                0,
                variants.size(),
                bucketCounts,
                summaryOnly,
                full,
                skipped,
                largeFile,
                true,
                false
        );
    }

    private VariantAnalysisJob readyJob() {
        VariantAnalysisJob job = uploadedJob();
        job.complete(
                "proband",
                true,
                "bcftools norm",
                new VariantQcSummary(1, 1, 1, 0, 1, 0, 0, 0, 1, 0, 0, 0),
                Collections.emptyList(),
                Collections.emptyList()
        );
        return job;
    }

    private VariantAnalysisJob uploadedJob() {
        return new VariantAnalysisJob(
                "analysis",
                "patient.vcf",
                "abc123",
                GenomeAssembly.GRCh38,
                AnalysisMode.WES,
                "proband",
                Paths.get("workspace"),
                Paths.get("workspace", "patient.vcf")
        );
    }

    private VariantInterpretation exportVariant() {
        VariantQcObservation observation = new VariantQcObservation(
                new NormalizedVariantKey("GRCh38", "1", 100, "A", "G"),
                "proband",
                "0/1",
                30,
                99,
                "16,14",
                0.46d,
                "PASS",
                "SNV",
                "chr1,100,\"A\",G\nnote"
        );
        VariantInterpretation variant = new VariantInterpretation(
                "GRCh38:1:100:A:G",
                observation,
                Collections.singletonList(
                        new TranscriptConsequence("GENE1", null, "NM_000001.1", "c.1A>G", "p.Lys1Arg", "missense")
                ),
                Arrays.asList(
                        clinVarEvidence(),
                        gnomadEvidence(),
                        caddEvidence(),
                        spliceAiEvidence(),
                        trioEvidence()
                )
        );
        variant.applyTranscriptAnnotations(Collections.singletonList(
                TranscriptConsequence.ann(
                        "GENE1",
                        null,
                        "NM_000001.1",
                        "transcript",
                        "missense",
                        "MODERATE",
                        "c.1A>G",
                        "p.Lys1Arg",
                        null,
                        null,
                        null,
                        null,
                        null,
                        Instant.now()
                ).withAnnotationMetadata("ANN_ONLY", "COMPLETED", "VCF_ANN", Instant.now())
        ));
        Map<String, Double> scoreBreakdown = new LinkedHashMap<String, Double>();
        scoreBreakdown.put("clinical", 100.0d);
        Map<String, ScoreBreakdownComponent> scoreComponents =
                new LinkedHashMap<String, ScoreBreakdownComponent>();
        scoreComponents.put(
                "clinical",
                new ScoreBreakdownComponent(100.0d, Collections.singletonList("ClinVar supports pathogenicity"))
        );
        variant.applyRanking(
                "KNOWN_CLINICAL",
                120.0d,
                scoreBreakdown,
                scoreComponents,
                "ClinVar pathogenic evidence",
                Collections.singletonList("ClinVar supports pathogenicity"),
                Collections.singletonList("No population conflict"),
                Collections.singletonList("ClinVar supports pathogenicity"),
                Collections.singletonList("manual review"),
                Collections.emptyList()
        );
        variant.applyAcmgEvidenceSuggestions(Collections.singletonList(
                new AcmgEvidenceSuggestion(
                        "PM2_candidate",
                        "SUPPORTING",
                        "SUGGESTED",
                        "GNOMAD",
                        "gnomAD frequency is below the configured rare-disease threshold.",
                        false,
                        "acmg-ready-v1",
                        Collections.emptyList()
                )
        ));
        variant.applyLargeFileClassification(
                VariantAnalysisBucket.KNOWN_CLINICAL,
                true,
                false,
                false,
                null,
                Collections.singletonList("LARGE_FILE_GATE_CANDIDATE: retained for standard annotation")
        );
        return variant;
    }

    private VariantInterpretation exomiserExportVariant() {
        return exomiserExportVariant("rs123");
    }

    private VariantInterpretation exomiserExportVariant(String sourceRecordId) {
        VariantInterpretation base = exportVariant();
        ExomiserCandidateFeatures features = new ExomiserCandidateFeatures(
                null,
                "15.1.0",
                "GENE1",
                "123",
                "AR",
                "AUTOSOMAL_RECESSIVE",
                4,
                null,
                sourceRecordId,
                0.81d,
                0.93d,
                0.82d,
                0.74d,
                "missense_variant",
                "NM_000001.1:c.1A>G",
                true,
                "result.variants.tsv",
                "result.genes.tsv",
                2,
                1,
                "EXACT_RANK_GENE_MOI",
                "EXOMISER_VARIANT_SCORE",
                Collections.singletonList("MAX_FREQ"),
                Collections.singletonList("P-VALUE"),
                Collections.singletonMap("geneSymbol", "VARIANT_ROW")
        );
        VariantInterpretation variant = new VariantInterpretation(
                CandidateIdentity.exomiser(
                        base.getObservation().getKey().canonicalId(), 2, "GENE1", "AR", 4
                ),
                base.getObservation(),
                base.getConsequences(),
                base.getEvidence(),
                features
        );
        variant.applyTranscriptAnnotations(base.getTranscriptAnnotations());
        variant.applyRanking(
                base.getPriorityTier(),
                base.getRankingScore(),
                base.getScoreBreakdown(),
                base.getScoreComponents(),
                base.getTierReason(),
                base.getPositiveReasons(),
                base.getNegativeReasons(),
                base.getRationale(),
                base.getWarnings(),
                base.getFilterReasons()
        );
        variant.applyAcmgEvidenceSuggestions(base.getAcmgEvidenceSuggestions());
        variant.copyLargeFileClassificationFrom(base);
        return variant;
    }

    private EvidenceItem clinVarEvidence() {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "LOCAL_CLINVAR");
        details.put("clinicalSignificance", "Likely_pathogenic");
        details.put("aggregateClassification", "Likely_pathogenic");
        details.put("reviewStatus", "criteria_provided");
        details.put("reviewLevel", "CRITERIA_PROVIDED");
        details.put("conflictState", "NO_CONFLICT");
        details.put("conditions", Arrays.asList(
                Collections.singletonMap("name", "Disease A"),
                Collections.singletonMap("name", "Disease B")
        ));
        details.put("clinVarVariationId", "12345");
        return new EvidenceItem(
                "CLINVAR",
                "local-test",
                "CLINICAL",
                "APPLICABLE",
                null,
                "Likely_pathogenic",
                "ClinVar local GRCh38 record",
                details
        );
    }

    private EvidenceItem gnomadEvidence() {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "LOCAL_GNOMAD");
        details.put("alleleFrequency", 0.0001d);
        details.put("populationMaximumAlleleFrequency", 0.0002d);
        details.put("sourceDataset", "exomes");
        details.put("sourceFile", "D:\\LSD\\Project\\Tool\\gnomad\\chr1.compact.tsv.bgz");
        return new EvidenceItem(
                "GNOMAD",
                "v4.1",
                "POPULATION",
                "APPLICABLE",
                0.0002d,
                "Maximum allele frequency",
                "Population frequency",
                details
        );
    }

    private EvidenceItem caddEvidence() {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("phred", 25.6d);
        return new EvidenceItem(
                "CADD",
                "v1.7",
                "COMPUTATIONAL",
                "APPLICABLE",
                25.6d,
                "CADD PHRED",
                "CADD PHRED score",
                details
        );
    }

    private EvidenceItem spliceAiEvidence() {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("source", "LOCAL_SPLICEAI");
        details.put("maxDeltaScore", 0.84d);
        details.put("maxEvent", "donor_loss");
        details.put("maxDeltaPosition", "-4");
        details.put("gene", "GENE1");
        return new EvidenceItem(
                "SPLICEAI",
                "v1.3.1",
                "COMPUTATIONAL",
                "APPLICABLE",
                0.84d,
                "SpliceAI maximum delta score",
                "SpliceAI delta score",
                details
        );
    }

    private EvidenceItem trioEvidence() {
        Map<String, Object> contribution = new LinkedHashMap<String, Object>();
        contribution.put("applied", Boolean.FALSE);
        contribution.put("contributionScore", 0.0d);
        contribution.put("notAppliedReason", "RANKING_DISABLED");
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("model", "DE_NOVO_CANDIDATE");
        details.put("confidence", "SUPPORTED");
        details.put("parentalOrigin", "NONE");
        details.put("partnerVariantIds", Collections.emptyList());
        details.put("evidenceOnly", Boolean.TRUE);
        details.put("trioRankingContribution", contribution);
        return new EvidenceItem(
                "TRIO",
                "VARVIS_TRIO_PIPELINE_V1",
                "SEGREGATION",
                "APPLICABLE",
                null,
                "DE_NOVO_CANDIDATE / SUPPORTED",
                "Trio segregation evidence",
                details
        );
    }
}
