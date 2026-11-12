package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AcmgEvidenceSuggestion;
import org.nbscn.varvis.variantanalysis.domain.AnalysisVcfCall;
import org.nbscn.varvis.variantanalysis.domain.CanonicalVariantIdentity;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.ExomiserCandidateFeatures;
import org.nbscn.varvis.variantanalysis.domain.NormalizationAlleleMapping;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.ScoreBreakdownComponent;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisEvent;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisJob;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisStatus;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcSummary;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisExportBundleResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisProgressiveStatus;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisProviderStatusResponse;
import org.nbscn.varvis.variantanalysis.dto.VariantAnalysisVariantPageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VariantAnalysisExportService {

    private static final int DEFAULT_EXPORT_LIMIT = 50;
    private static final int MAX_JSON_EXPORT_LIMIT = 500;
    private static final int MAX_CSV_EXPORT_LIMIT = 5000;
    private static final Pattern VEP_EVENT_SUMMARY_PATTERN = Pattern.compile(
            "^VEP transcript annotation ([A-Z_]+): selected=(\\d+), processed=(\\d+), "
                    + "success=(\\d+), failed=(\\d+), cacheHit=(\\d+), elapsedMs=(\\d+)"
                    + "(?:, warnings=(\\d+))?.*$"
    );
    private static final Pattern NORMALIZATION_PROFILE_PATTERN = Pattern.compile(
            "^Reference normalization PASSED:.*\\bprofile=([^,]+).*$"
    );
    private static final String PHENOTYPE_SCORE_COMPONENT = "phenotype_support";
    private static final String PHENOTYPE_EVIDENCE_TYPE = "PHENOTYPE_GENE_PRIOR";
    private static final String PHENOTYPE_EXPORT_WARNING =
            "Phenotype support is used for candidate prioritization only. It is not a diagnosis.";
    private static final String[] CSV_HEADERS = {
            "rank",
            "tier",
            "chrom",
            "pos",
            "ref",
            "alt",
            "original_variant",
            "gene",
            "transcript",
            "transcript_source",
            "consequence",
            "transcript_annotation_status",
            "transcript_annotation_source",
            "genotype",
            "DP",
            "GQ",
            "FILTER",
            "analysis_bucket",
            "large_file_mode",
            "summary_only",
            "full_interpretation_available",
            "expensive_annotation_skipped",
            "expensive_annotation_skipped_reason",
            "cheap_gate_reasons",
            "analysis_stage",
            "ready_fast",
            "ready_full",
            "enrichment_status",
            "partial_evidence_warning",
            "ClinVar significance",
            "ClinVar review status",
            "ClinVar Variation ID",
            "ClinVar source",
            "gnomAD AF",
            "gnomAD AF_popmax",
            "gnomAD source",
            "CADD PHRED",
            "AlphaMissense score",
            "spliceai_status",
            "spliceai_max_delta_score",
            "spliceai_event",
            "spliceai_delta_position",
            "spliceai_gene",
            "spliceai_source",
            "total_score",
            "score_breakdown",
            "phenotype_support",
            "phenotype_score",
            "phenotype_score_boost",
            "phenotype_gene_prior_rank",
            "phenotype_gene_prior_source",
            "phenotype_matched_hpo_terms",
            "phenotype_evidence",
            "phenotype_warning",
            "trio_model",
            "trio_confidence",
            "trio_parental_origin",
            "trio_partner_variant_ids",
            "trio_evidence_only",
            "trio_ranking_applied",
            "trio_score_contribution",
            "trio_ranking_not_applied_reason",
            "tier_reason",
            "positive_reasons",
            "negative_reasons",
            "warnings",
            "filter_reasons",
            "acmg_ready_suggestions",
            "provider_unavailable_list",
            "candidate_id",
            "canonical_variant_id",
            "variant_id",
            "candidate_source",
            "candidate_source_ordinal",
            "candidate_gene",
            "candidate_moi",
            "candidate_declared_rank",
            "exomiser_engine_version",
            "exomiser_gene_symbol",
            "exomiser_gene_id",
            "exomiser_raw_moi",
            "exomiser_normalized_moi",
            "exomiser_gene_moi_group_rank",
            "exomiser_gene_moi_group_rank_semantics",
            "exomiser_gene_combined_score",
            "exomiser_gene_phenotype_score",
            "exomiser_gene_variant_score",
            "exomiser_variant_score",
            "exomiser_functional_class",
            "exomiser_hgvs",
            "exomiser_contributing_variant",
            "exomiser_source_record_id",
            "exomiser_variant_source_ordinal",
            "exomiser_gene_source_ordinal",
            "exomiser_gene_row_match_strategy",
            "exomiser_variant_artifact",
            "exomiser_gene_artifact",
            "exomiser_ranking_score_source_column",
            "exomiser_field_sources",
            "exomiser_missing_fields",
            "exomiser_invalid_fields",
            "analysis_vcf_call_schema_version",
            "analysis_vcf_call_match_status",
            "analysis_vcf_call_target_allele_call",
            "analysis_vcf_call_candidate_relative_genotype",
            "analysis_vcf_call_target_allele_index",
            "analysis_vcf_call_depth",
            "analysis_vcf_call_genotype_quality",
            "analysis_vcf_call_reference_depth",
            "analysis_vcf_call_target_allele_depth",
            "analysis_vcf_call_allele_balance",
            "analysis_vcf_call_filter_evaluation",
            "analysis_vcf_call_phasing_status",
            "analysis_vcf_call_phase_set_token",
            "analysis_vcf_call_matching_record_count",
            "analysis_vcf_call_matched_record_ordinal",
            "analysis_vcf_call_assembly",
            "analysis_vcf_call_artifact_role",
            "analysis_vcf_call_provenance",
            "analysis_vcf_call_missing_fields",
            "analysis_vcf_call_invalid_fields"
    };

    private final VariantAnalysisService variantAnalysisService;
    private final VariantAnalysisProperties properties;

    public VariantAnalysisExportService(VariantAnalysisService variantAnalysisService,
                                        VariantAnalysisProperties properties) {
        this.variantAnalysisService = variantAnalysisService;
        this.properties = properties;
    }

    public String exportCsv(String id,
                            Integer limit,
                            VariantInterpretationView view,
                            VariantInterpretationFilter filter) {
        int requestedLimit = normalizeCsvLimit(limit);
        int batchSize = Math.min(1000, requestedLimit);
        VariantInterpretationView normalizedView =
                view == null ? VariantInterpretationView.CANDIDATES : view;
        VariantInterpretationFilter normalizedFilter =
                filter == null ? VariantInterpretationFilter.empty() : filter;
        ExportContext context = exportContext(id, batchSize, normalizedView, normalizedFilter);
        StringBuilder builder = new StringBuilder();
        builder.append('\uFEFF');
        appendCsvRow(builder, headerValues());
        int rank = 1;
        VariantAnalysisProgressiveStatus progressive =
                VariantAnalysisProgressiveStatus.from(context.job, context.page.getCounts());
        VariantAnalysisVariantPageResponse page = context.page;
        int pageNumber = 1;
        while (page != null && rank <= requestedLimit) {
            for (VariantInterpretation variant : page.getVariants()) {
                if (rank > requestedLimit) {
                    break;
                }
                appendCsvRow(builder, csvValues(rank++, variant, progressive));
            }
            if (rank > requestedLimit
                    || rank > page.getTotal()
                    || page.getVariants() == null
                    || page.getVariants().isEmpty()) {
                break;
            }
            pageNumber++;
            page = variantAnalysisService.listVariants(
                    id,
                    pageNumber,
                    batchSize,
                    normalizedView,
                    normalizedFilter
            );
        }
        return builder.toString();
    }

    public VariantAnalysisExportBundleResponse exportJson(String id,
                                                          Integer limit,
                                                          VariantInterpretationView view,
                                                          VariantInterpretationFilter filter) {
        ExportContext context = exportContext(id, normalizeLimit(limit), view, filter);
        VariantAnalysisProviderStatusResponse providerStatus =
                VariantAnalysisProviderStatusResponse.from(properties);
        Map<String, Object> transcriptAnnotationSummary =
                transcriptAnnotationSummary(context.page.getVariants(), providerStatus, context.events);
        Map<String, Object> acmgReadySummary = acmgReadySummary(context.page.getVariants());
        VariantAnalysisProgressiveStatus progressive =
                VariantAnalysisProgressiveStatus.from(context.job, context.page.getCounts());
        return new VariantAnalysisExportBundleResponse(
                jobMetadata(context.job, progressive),
                reference(context.job),
                summary(
                        context.job,
                        context.page,
                        transcriptAnnotationSummary,
                        acmgReadySummary,
                        progressive
                ),
                providerStatus,
                providerVersions(providerStatus),
                ranking(),
                String.valueOf(transcriptAnnotationSummary.get("status")),
                transcriptAnnotationSummary,
                acmgReadySummary,
                candidates(
                        context.page.getVariants(),
                        latestNormalizationProfileId(context.events)
                ),
                unavailableResources(context.page.getVariants()),
                runtimeMetrics(context.events),
                Instant.now()
        );
    }

    public int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_EXPORT_LIMIT;
        }
        return Math.min(limit, MAX_JSON_EXPORT_LIMIT);
    }

    public int normalizeCsvLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return DEFAULT_EXPORT_LIMIT;
        }
        return Math.min(limit, MAX_CSV_EXPORT_LIMIT);
    }

    private ExportContext exportContext(String id,
                                        Integer limit,
                                        VariantInterpretationView view,
                                        VariantInterpretationFilter filter) {
        VariantAnalysisJob job = variantAnalysisService.get(id);
        if (job.getStatus() != VariantAnalysisStatus.READY) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Variant analysis must be ready before export"
            );
        }
        VariantInterpretationView normalizedView =
                view == null ? VariantInterpretationView.CANDIDATES : view;
        VariantInterpretationFilter normalizedFilter =
                filter == null ? VariantInterpretationFilter.empty() : filter;
        VariantAnalysisVariantPageResponse page = variantAnalysisService.listVariants(
                id,
                1,
                limit,
                normalizedView,
                normalizedFilter
        );
        return new ExportContext(job, page, variantAnalysisService.listEvents(id));
    }

    private List<String> headerValues() {
        List<String> values = new ArrayList<String>();
        Collections.addAll(values, CSV_HEADERS);
        return values;
    }

    private List<Object> csvValues(int rank,
                                   VariantInterpretation variant,
                                   VariantAnalysisProgressiveStatus progressive) {
        VariantQcObservation observation = variant.getObservation();
        NormalizedVariantKey key = observation == null ? null : observation.getKey();
        TranscriptConsequence consequence = firstConsequence(variant);
        TranscriptConsequence transcriptAnnotation = firstTranscriptAnnotation(variant);
        EvidenceItem clinVar = evidence(variant, "CLINVAR");
        EvidenceItem gnomad = evidence(variant, "GNOMAD");
        EvidenceItem spliceAi = evidence(variant, "SPLICEAI");
        EvidenceItem trio = evidence(variant, "TRIO");
        ExomiserCandidateFeatures exomiser = variant.getExomiserCandidateFeatures();
        AnalysisVcfCall analysisVcfCall = variant.getAnalysisVcfCall();
        PhenotypeExportView phenotype = phenotypeExportView(variant);
        return values(
                rank,
                variant.getTier(),
                keyValue(key, "chromosome"),
                key == null ? null : key.getPosition(),
                keyValue(key, "reference"),
                keyValue(key, "alternate"),
                observation == null ? null : sanitizeText(observation.getOriginalRecord()),
                consequence == null ? null : consequence.getGeneSymbol(),
                consequence == null ? null : consequence.getTranscriptAccession(),
                consequence == null ? null : consequence.getSource(),
                consequence == null ? null : consequence.getConsequence(),
                transcriptAnnotation == null ? null : transcriptAnnotation.getStatus(),
                transcriptAnnotation == null ? null : transcriptAnnotation.getSource(),
                observation == null ? null : observation.getGenotype(),
                observation == null ? null : observation.getDepth(),
                observation == null ? null : observation.getGenotypeQuality(),
                observation == null ? null : observation.getFilterStatus(),
                variant.getAnalysisBucket(),
                variant.isLargeFileMode(),
                variant.isSummaryOnly(),
                variant.isFullInterpretationAvailable(),
                variant.isExpensiveAnnotationSkipped(),
                variant.getExpensiveAnnotationSkippedReason(),
                join(variant.getCheapGateReasons()),
                progressive.getAnalysisStage(),
                progressive.isReadyFast(),
                progressive.isReadyFull(),
                progressive.getEnrichmentStatus(),
                progressive.getPartialEvidenceWarning(),
                detailOrLabel(clinVar, "clinicalSignificance", "value"),
                detail(clinVar, "reviewStatus"),
                detail(clinVar, "clinVarVariationId"),
                evidenceSource(clinVar),
                detail(gnomad, "alleleFrequency"),
                detailOrScore(gnomad, "populationMaximumAlleleFrequency", "maximumAlleleFrequency"),
                evidenceSource(gnomad),
                detailOrScore(evidence(variant, "CADD"), "phred"),
                evidenceScore(variant, "ALPHAMISSENSE"),
                spliceAi == null ? null : spliceAi.getApplicability(),
                detailOrScore(spliceAi, "maxDeltaScore", "maximumDeltaScore"),
                detail(spliceAi, "maxEvent"),
                detail(spliceAi, "maxDeltaPosition"),
                detailFirst(spliceAi, "gene", "geneSymbol"),
                evidenceSource(spliceAi),
                variant.getTotalScore(),
                scoreBreakdownText(variant),
                phenotype.supported ? "YES" : "NO",
                phenotype.phenotypeScore,
                phenotype.scoreBoost,
                phenotype.genePriorRank,
                phenotype.source,
                join(phenotype.matchedHpoTerms),
                join(phenotype.evidence),
                phenotype.warning,
                detail(trio, "model"),
                detail(trio, "confidence"),
                detail(trio, "parentalOrigin"),
                joinObject(detail(trio, "partnerVariantIds")),
                detail(trio, "evidenceOnly"),
                nestedDetail(trio, "trioRankingContribution", "applied"),
                nestedDetail(trio, "trioRankingContribution", "contributionScore"),
                nestedDetail(trio, "trioRankingContribution", "notAppliedReason"),
                variant.getTierReason(),
                join(variant.getPositiveReasons()),
                join(variant.getNegativeReasons()),
                join(variant.getWarnings()),
                join(variant.getFilterReasons()),
                acmgSuggestionsText(variant),
                join(unavailableResources(Collections.singletonList(variant))),
                variant.getCandidateId(),
                variant.getCanonicalVariantId(),
                variant.getVariantId(),
                variant.getCandidateIdentity().getSource(),
                variant.getCandidateIdentity().getSourceOrdinal(),
                variant.getCandidateIdentity().getGeneSymbol(),
                variant.getCandidateIdentity().getModeOfInheritance(),
                variant.getCandidateIdentity().getDeclaredRank(),
                exomiser == null ? null : sanitizeText(exomiser.getEngineVersion()),
                exomiser == null ? null : sanitizeText(exomiser.getGeneSymbol()),
                exomiser == null ? null : sanitizeText(exomiser.getEntrezGeneId()),
                exomiser == null ? null : sanitizeText(exomiser.getRawModeOfInheritance()),
                exomiser == null ? null : sanitizeText(exomiser.getNormalizedModeOfInheritance()),
                exomiser == null ? null : exomiser.getGeneMoiGroupRank(),
                exomiser == null ? null : sanitizeText(exomiser.getGeneMoiGroupRankSemantics()),
                exomiser == null ? null : finite(exomiser.getGeneCombinedScore()),
                exomiser == null ? null : finite(exomiser.getGenePhenotypeScore()),
                exomiser == null ? null : finite(exomiser.getGeneVariantScore()),
                exomiser == null ? null : finite(exomiser.getVariantScore()),
                exomiser == null ? null : sanitizeText(exomiser.getFunctionalClass()),
                exomiser == null ? null : sanitizeText(exomiser.getHgvs()),
                exomiser == null ? null : exomiser.getContributingVariant(),
                exomiser == null ? null : sanitizeText(exomiser.getSourceRecordId()),
                exomiser == null ? null : exomiser.getVariantSourceOrdinal(),
                exomiser == null ? null : exomiser.getGeneSourceOrdinal(),
                exomiser == null ? null : sanitizeText(exomiser.getGeneRowMatchStrategy()),
                exomiser == null ? null : sanitizeText(exomiser.getVariantArtifactName()),
                exomiser == null ? null : sanitizeText(exomiser.getGeneArtifactName()),
                exomiser == null ? null : sanitizeText(exomiser.getRankingScoreSourceColumn()),
                exomiser == null ? null : mapText(exomiser.getFieldSources()),
                exomiser == null ? null : sanitizedListText(exomiser.getMissingFields()),
                exomiser == null ? null : sanitizedListText(exomiser.getInvalidFields()),
                analysisVcfCall == null ? null : analysisVcfCall.getSchemaVersion(),
                analysisVcfCall == null ? null : enumName(analysisVcfCall.getMatchStatus()),
                analysisVcfCall == null ? null : enumName(analysisVcfCall.getTargetAlleleCall()),
                analysisVcfCall == null ? null : analysisVcfCall.getCandidateRelativeGenotype(),
                analysisVcfCall == null ? null : analysisVcfCall.getTargetAlleleIndex(),
                analysisVcfCall == null ? null : analysisVcfCall.getDepth(),
                analysisVcfCall == null ? null : analysisVcfCall.getGenotypeQuality(),
                analysisVcfCall == null ? null : analysisVcfCall.getReferenceDepth(),
                analysisVcfCall == null ? null : analysisVcfCall.getTargetAlleleDepth(),
                analysisVcfCall == null ? null : finite(analysisVcfCall.getAlleleBalance()),
                analysisVcfCall == null ? null : enumName(analysisVcfCall.getFilterEvaluation()),
                analysisVcfCall == null ? null : enumName(analysisVcfCall.getPhasingStatus()),
                analysisVcfCall == null ? null : analysisVcfCall.getPhaseSetToken(),
                analysisVcfCall == null ? null : analysisVcfCall.getMatchingRecordCount(),
                analysisVcfCall == null ? null : analysisVcfCall.getMatchedRecordOrdinal(),
                analysisVcfCall == null ? null : analysisVcfCall.getAssembly(),
                analysisVcfCall == null ? null : analysisVcfCall.getArtifactRole(),
                analysisVcfCall == null ? null : analysisVcfCall.getProvenance(),
                analysisVcfCall == null ? null : join(analysisVcfCall.getMissingFields()),
                analysisVcfCall == null ? null : join(analysisVcfCall.getInvalidFields())
        );
    }

    private Map<String, Object> jobMetadata(VariantAnalysisJob job,
                                            VariantAnalysisProgressiveStatus progressive) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("analysisId", job.getId());
        metadata.put("status", job.getStatus());
        metadata.put("analysisStage", progressive.getAnalysisStage());
        metadata.put("enrichmentStatus", progressive.getEnrichmentStatus());
        metadata.put("readyFastAt", progressive.getReadyFastAt());
        metadata.put("readyFullAt", progressive.getReadyFullAt());
        metadata.put("providerProgress", progressive.getProviderProgress());
        metadata.put("partialEvidenceWarning", progressive.getPartialEvidenceWarning());
        metadata.put("originalFileName", job.getOriginalFileName());
        metadata.put("fileSha256", job.getFileSha256());
        metadata.put("analysisMode", job.getAnalysisMode().name());
        metadata.put("requestedSampleId", job.getRequestedSampleId());
        metadata.put("selectedSampleId", job.getSelectedSampleId());
        metadata.put("createdAt", job.getCreatedAt());
        metadata.put("updatedAt", job.getUpdatedAt());
        return metadata;
    }

    private Map<String, Object> reference(VariantAnalysisJob job) {
        Map<String, Object> reference = new LinkedHashMap<String, Object>();
        reference.put("genomeBuild", job.getGenomeAssembly().name());
        reference.put("normalizationApplied", job.isNormalizationApplied());
        reference.put("normalizationEngine", job.getNormalizationEngine());
        return reference;
    }

    private Map<String, Object> summary(VariantAnalysisJob job,
                                        VariantAnalysisVariantPageResponse page,
                                        Map<String, Object> transcriptAnnotationSummary,
                                        Map<String, Object> acmgReadySummary,
                                        VariantAnalysisProgressiveStatus progressive) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        VariantQcSummary qc = job.getQcSummary();
        if (qc != null) {
            summary.put("inputVariantCount", qc.getInputSiteCount());
            summary.put("normalizedVariantCount", qc.getVariantCount());
            summary.put("passCount", qc.getPassCount());
            summary.put("filteredCount", qc.getFilteredCount());
            summary.put("sampleCount", qc.getSampleCount());
        }
        summary.put("candidateCount", page.getCandidateTotal());
        summary.put("frequencyFilteredCount", page.getFrequencyFilteredTotal());
        summary.put("allVariantCount", page.getAllTotal());
        summary.put("exportedCount", page.getVariants().size());
        summary.put("filteredTotalForRequest", page.getTotal());
        summary.put("exportView", page.getView());
        summary.put("populationFrequencyThreshold", page.getPopulationFrequencyThreshold());
        summary.put("transcriptAnnotationStatus", transcriptAnnotationSummary.get("status"));
        summary.put("acmgReadySummary", acmgReadySummary);
        summary.put("phenotypeSummary", job.getPhenotypeSummary());
        summary.put("preflightSummary", job.getPreflightSummary());
        summary.put("progressiveStatus", progressive.toMap());
        summary.put("analysisStage", progressive.getAnalysisStage());
        summary.put("enrichmentStatus", progressive.getEnrichmentStatus());
        summary.put("partialEvidenceWarning", progressive.getPartialEvidenceWarning());
        summary.put("analysisBucketCounts", page.getCounts().getBucketCounts());
        summary.put("bucketCounts", page.getCounts().getBucketCounts());
        summary.put("largeFileSummary", page.getLargeFileSummary());
        summary.put("persistencePolicy", persistencePolicy(page));
        return summary;
    }

    private Map<String, String> providerVersions(VariantAnalysisProviderStatusResponse status) {
        Map<String, String> versions = new LinkedHashMap<String, String>();
        putVersion(versions, status.getCadd());
        putVersion(versions, status.getClinVar());
        putVersion(versions, status.getGnomad());
        putVersion(versions, status.getSpliceAi());
        putVersion(versions, status.getAlphaMissense());
        putVersion(versions, status.getAlphaGenome());
        putVersion(versions, status.getVep());
        putVersion(versions, status.getMane());
        putVersion(versions, status.getLoftee());
        return versions;
    }

    private Map<String, Object> ranking() {
        VariantAnalysisProperties.Ranking ranking = properties.getRanking();
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("version", ranking.getVersion());
        values.put("caddReviewThreshold", ranking.getCaddReviewThreshold());
        values.put("caddHighlightThreshold", ranking.getCaddHighlightThreshold());
        values.put("spliceAiHighlightThreshold", ranking.getSpliceAiHighlightThreshold());
        values.put("alphaMissenseHighlightThreshold", ranking.getAlphaMissenseHighlightThreshold());
        values.put("commonAlleleFrequencyThreshold", ranking.getCommonAlleleFrequencyThreshold());
        values.put("veryCommonAlleleFrequencyThreshold", ranking.getVeryCommonAlleleFrequencyThreshold());
        values.put("minDepth", ranking.getMinDepth());
        values.put("minGenotypeQuality", ranking.getMinGenotypeQuality());
        values.put("minAlleleBalance", ranking.getMinAlleleBalance());
        return values;
    }

    private Map<String, Object> transcriptAnnotationSummary(
            List<VariantInterpretation> variants,
            VariantAnalysisProviderStatusResponse providerStatus,
            List<VariantAnalysisEvent> events) {
        int processed = 0;
        int success = 0;
        int failed = 0;
        int cacheHit = 0;
        List<String> warnings = new ArrayList<String>();
        for (VariantInterpretation variant : variants) {
            boolean sawVep = false;
            boolean sawSuccess = false;
            boolean sawFailure = false;
            for (TranscriptConsequence annotation : variant.getTranscriptAnnotations()) {
                String source = annotation.getSource();
                if (!"VEP_COMPUTED".equals(source) && !"VEP_CACHED".equals(source)) {
                    continue;
                }
                sawVep = true;
                if ("VEP_CACHED".equals(source)) {
                    cacheHit++;
                }
                if ("COMPLETED".equals(annotation.getStatus())) {
                    sawSuccess = true;
                } else {
                    sawFailure = true;
                    if (annotation.getConsequence() != null) {
                        warnings.add(sanitizeText(annotation.getConsequence()));
                    }
                }
            }
            if (sawVep) {
                processed++;
            }
            if (sawSuccess) {
                success++;
            }
            if (sawFailure) {
                failed++;
            }
        }
        String status = providerStatus.getVep() == null
                ? "NOT_REQUESTED"
                : providerStatus.getVep().getTranscriptAnnotationStatus();
        if (processed > 0) {
            if (failed > 0 && success > 0) {
                status = "PARTIAL";
            } else if (failed > 0) {
                status = "FAILED";
            } else {
                status = "COMPLETED";
            }
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("status", status);
        values.put("selectedCount", variants == null ? 0 : variants.size());
        values.put("processedCount", processed);
        values.put("successCount", success);
        values.put("failedCount", failed);
        values.put("cacheHitCount", cacheHit);
        values.put("warnings", warnings);
        if (providerStatus.getVep() != null) {
            values.put("readinessStatus", providerStatus.getVep().getStatus());
            values.put("executionEnabled", providerStatus.getVep().isExecutionEnabled());
            values.put("maxCandidates", providerStatus.getVep().getDefaultCandidateLimit());
            values.put("batchSize", providerStatus.getVep().getBatchSize());
        }
        Map<String, Object> eventSummary = latestVepEventSummary(events);
        if (!eventSummary.isEmpty()) {
            values.putAll(eventSummary);
        }
        return values;
    }

    private Map<String, Object> acmgReadySummary(List<VariantInterpretation> variants) {
        int totalCandidatesWithSuggestions = 0;
        int suggestedCount = 0;
        int manualReviewRequiredCount = 0;
        Map<String, Integer> codeCounts = new LinkedHashMap<String, Integer>();
        Map<String, Integer> statusCounts = new LinkedHashMap<String, Integer>();
        Set<String> ruleVersions = new LinkedHashSet<String>();
        if (variants != null) {
            for (VariantInterpretation variant : variants) {
                List<AcmgEvidenceSuggestion> suggestions = variant.getAcmgEvidenceSuggestions();
                if (suggestions == null || suggestions.isEmpty()) {
                    continue;
                }
                totalCandidatesWithSuggestions++;
                for (AcmgEvidenceSuggestion suggestion : suggestions) {
                    increment(codeCounts, suggestion.getCode());
                    increment(statusCounts, suggestion.getStatus());
                    if ("SUGGESTED".equals(suggestion.getStatus())) {
                        suggestedCount++;
                    }
                    if (suggestion.isManualReviewRequired()) {
                        manualReviewRequiredCount++;
                    }
                    if (suggestion.getRuleVersion() != null
                            && !suggestion.getRuleVersion().trim().isEmpty()) {
                        ruleVersions.add(suggestion.getRuleVersion());
                    }
                }
            }
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("totalCandidatesWithSuggestions", totalCandidatesWithSuggestions);
        values.put("suggestedCount", suggestedCount);
        values.put("manualReviewRequiredCount", manualReviewRequiredCount);
        values.put("codeCounts", codeCounts);
        values.put("statusCounts", statusCounts);
        values.put("ruleVersion", ruleVersions.size() == 1
                ? ruleVersions.iterator().next()
                : new ArrayList<String>(ruleVersions));
        values.put("disclaimer",
                "ACMG-ready evidence suggestions are evidence-direction aids only; "
                        + "this export does not provide a final clinical ACMG classification.");
        return values;
    }

    private void increment(Map<String, Integer> counts, String key) {
        String normalized = key == null || key.trim().isEmpty() ? "UNSPECIFIED" : key.trim();
        Integer current = counts.get(normalized);
        counts.put(normalized, current == null ? 1 : current + 1);
    }

    private Map<String, Object> latestVepEventSummary(List<VariantAnalysisEvent> events) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> latest = Collections.emptyMap();
        for (VariantAnalysisEvent event : events) {
            if (event == null || event.getMessage() == null) {
                continue;
            }
            Matcher matcher = VEP_EVENT_SUMMARY_PATTERN.matcher(event.getMessage());
            if (!matcher.matches()) {
                continue;
            }
            Map<String, Object> values = new LinkedHashMap<String, Object>();
            values.put("status", matcher.group(1));
            values.put("selectedCount", parseInt(matcher.group(2)));
            values.put("processedCount", parseInt(matcher.group(3)));
            values.put("successCount", parseInt(matcher.group(4)));
            values.put("failedCount", parseInt(matcher.group(5)));
            values.put("cacheHitCount", parseInt(matcher.group(6)));
            values.put("elapsedMillis", parseLong(matcher.group(7)));
            values.put("warningCount", matcher.group(8) == null ? 0 : parseInt(matcher.group(8)));
            values.put("summarySource", "EVENT_TIMELINE");
            latest = values;
        }
        return latest;
    }

    private int parseInt(String value) {
        return Integer.parseInt(value);
    }

    private long parseLong(String value) {
        return Long.parseLong(value);
    }

    private List<Map<String, Object>> candidates(List<VariantInterpretation> variants,
                                                 String referenceProfileId) {
        List<Map<String, Object>> candidates = new ArrayList<Map<String, Object>>();
        int rank = 1;
        for (VariantInterpretation variant : variants) {
            candidates.add(candidate(rank++, variant, referenceProfileId));
        }
        return candidates;
    }

    private Map<String, Object> candidate(int rank,
                                          VariantInterpretation variant,
                                          String referenceProfileId) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("rank", rank);
        values.put("candidateId", variant.getCandidateId());
        values.put("canonicalVariantId", variant.getCanonicalVariantId());
        values.put("variantId", variant.getVariantId());
        values.put("candidateIdentity", variant.getCandidateIdentity());
        Map<String, Object> exomiserFeatures = exomiserCandidateFeatures(
                variant.getExomiserCandidateFeatures()
        );
        if (exomiserFeatures != null) {
            values.put("exomiserCandidateFeatures", exomiserFeatures);
        }
        values.put("analysisVcfCallContext", analysisVcfCallContext(variant.getAnalysisVcfCall()));
        values.put("tier", variant.getTier());
        values.put("totalScore", variant.getTotalScore());
        values.put("tierReason", variant.getTierReason());
        values.put("analysisBucket", variant.getAnalysisBucket());
        values.put("largeFileMode", variant.isLargeFileMode());
        values.put("summaryOnly", variant.isSummaryOnly());
        values.put("fullInterpretationAvailable", variant.isFullInterpretationAvailable());
        values.put("expensiveAnnotationSkipped", variant.isExpensiveAnnotationSkipped());
        values.put("expensiveAnnotationSkippedReason", variant.getExpensiveAnnotationSkippedReason());
        values.put("cheapGateReasons", variant.getCheapGateReasons());
        values.put("largeFileDecision", largeFileDecision(variant));
        values.put("observation", observation(variant.getObservation()));
        values.put("canonicalIdentity", canonicalIdentity(variant.getObservation(), referenceProfileId));
        values.put("topConsequence", consequence(firstConsequence(variant)));
        values.put("consequences", consequences(variant.getConsequences()));
        values.put("transcriptAnnotations", consequences(variant.getTranscriptAnnotations()));
        values.put("evidence", evidence(variant.getEvidence()));
        values.put("scoreBreakdown", variant.getScoreBreakdown());
        values.put("scoreComponents", scoreComponents(variant.getScoreComponents()));
        values.put("phenotypeSupport", phenotypeSupport(phenotypeExportView(variant)));
        values.put("positiveReasons", variant.getPositiveReasons());
        values.put("negativeReasons", variant.getNegativeReasons());
        values.put("rationale", variant.getRationale());
        values.put("warnings", variant.getWarnings());
        values.put("filterReasons", variant.getFilterReasons());
        values.put("acmgEvidenceSuggestions", acmgEvidenceSuggestions(variant.getAcmgEvidenceSuggestions()));
        return values;
    }

    private Map<String, Object> exomiserCandidateFeatures(ExomiserCandidateFeatures features) {
        if (features == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("schemaVersion", sanitizeText(features.getSchemaVersion()));
        values.put("engineVersion", sanitizeText(features.getEngineVersion()));
        values.put("geneSymbol", sanitizeText(features.getGeneSymbol()));
        values.put("entrezGeneId", sanitizeText(features.getEntrezGeneId()));
        values.put("rawModeOfInheritance", sanitizeText(features.getRawModeOfInheritance()));
        values.put("normalizedModeOfInheritance", sanitizeText(features.getNormalizedModeOfInheritance()));
        values.put("geneMoiGroupRank", features.getGeneMoiGroupRank());
        values.put("geneMoiGroupRankSemantics", sanitizeText(features.getGeneMoiGroupRankSemantics()));
        values.put("geneCombinedScore", finite(features.getGeneCombinedScore()));
        values.put("genePhenotypeScore", finite(features.getGenePhenotypeScore()));
        values.put("geneVariantScore", finite(features.getGeneVariantScore()));
        values.put("variantScore", finite(features.getVariantScore()));
        values.put("functionalClass", sanitizeText(features.getFunctionalClass()));
        values.put("hgvs", sanitizeText(features.getHgvs()));
        values.put("contributingVariant", features.getContributingVariant());
        values.put("sourceRecordId", sanitizeText(features.getSourceRecordId()));
        values.put("variantSourceOrdinal", features.getVariantSourceOrdinal());
        values.put("geneSourceOrdinal", features.getGeneSourceOrdinal());
        values.put("geneRowMatchStrategy", sanitizeText(features.getGeneRowMatchStrategy()));
        values.put("variantArtifactName", sanitizeText(features.getVariantArtifactName()));
        values.put("geneArtifactName", sanitizeText(features.getGeneArtifactName()));
        values.put("rankingScoreSourceColumn", sanitizeText(features.getRankingScoreSourceColumn()));
        values.put("fieldSources", sanitizeValue(features.getFieldSources()));
        values.put("missingFields", sanitizeValue(features.getMissingFields()));
        values.put("invalidFields", sanitizeValue(features.getInvalidFields()));
        return values;
    }

    private Map<String, Object> analysisVcfCallContext(AnalysisVcfCall call) {
        if (call == null) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("schemaVersion", call.getSchemaVersion());
        values.put("matchStatus", enumName(call.getMatchStatus()));
        values.put("targetAlleleCall", enumName(call.getTargetAlleleCall()));
        values.put("candidateRelativeGenotype", call.getCandidateRelativeGenotype());
        values.put("targetAlleleIndex", call.getTargetAlleleIndex());
        values.put("depth", call.getDepth());
        values.put("genotypeQuality", call.getGenotypeQuality());
        values.put("referenceDepth", call.getReferenceDepth());
        values.put("targetAlleleDepth", call.getTargetAlleleDepth());
        values.put("alleleBalance", finite(call.getAlleleBalance()));
        values.put("filterEvaluation", enumName(call.getFilterEvaluation()));
        values.put("phasingStatus", enumName(call.getPhasingStatus()));
        values.put("phaseSetToken", call.getPhaseSetToken());
        values.put("matchingRecordCount", call.getMatchingRecordCount());
        values.put("matchedRecordOrdinal", call.getMatchedRecordOrdinal());
        values.put("assembly", call.getAssembly());
        values.put("artifactRole", call.getArtifactRole());
        values.put("provenance", call.getProvenance());
        values.put("missingFields", new ArrayList<String>(call.getMissingFields()));
        values.put("invalidFields", new ArrayList<String>(call.getInvalidFields()));
        return values;
    }

    private String enumName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private Map<String, Object> canonicalIdentity(VariantQcObservation observation,
                                                  String referenceProfileId) {
        if (observation == null || observation.getKey() == null) {
            return Collections.emptyMap();
        }
        CanonicalVariantIdentity identity = CanonicalVariantIdentityFactory.create(
                observation.getKey(), referenceProfileId
        );
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("schemaVersion", identity.getSchemaVersion());
        values.put("status", identity.getStatus());
        values.put("assembly", identity.getAssembly());
        values.put("referenceProfileId", sanitizeText(identity.getReferenceProfileId()));
        values.put("canonicalVariantId", sanitizeText(identity.getCanonicalVariantId()));
        values.put("varvisComputedIdentifier", identity.getVarvisComputedIdentifier());
        values.put("chromosome", identity.getChromosome());
        values.put("position", identity.getPosition());
        values.put("reference", identity.getReference());
        values.put("alternate", identity.getAlternate());
        values.put("interbaseStart", identity.getInterbaseStart());
        values.put("interbaseEnd", identity.getInterbaseEnd());
        values.put("refgetSequenceDigest", identity.getRefgetSequenceDigest());
        values.put("vrsComputedIdentifier", identity.getVrsComputedIdentifier());
        values.put("vrsCompatibilityStatus", identity.getVrsCompatibilityStatus());
        return values;
    }

    private String latestNormalizationProfileId(List<VariantAnalysisEvent> events) {
        String profileId = null;
        if (events == null) {
            return null;
        }
        for (VariantAnalysisEvent event : events) {
            Matcher matcher = NORMALIZATION_PROFILE_PATTERN.matcher(
                    event == null ? "" : String.valueOf(event.getMessage())
            );
            if (matcher.matches()) {
                String candidate = matcher.group(1).trim();
                profileId = "NOT_RECORDED".equalsIgnoreCase(candidate) ? null : candidate;
            }
        }
        return profileId;
    }

    private Map<String, Object> persistencePolicy(VariantAnalysisVariantPageResponse page) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("candidateInterpretations", "full");
        values.put("summaryOnlyInterpretations", "lightweight");
        values.put("fullInterpretationCount", page.getCounts().getFullInterpretationCount());
        values.put("summaryOnlyCount", page.getCounts().getSummaryOnlyCount());
        values.put("fullDetailUnavailableMeaning",
                "summaryOnly=true means detailed payload was intentionally compacted for scale; "
                        + "it is not a clinical classification.");
        return values;
    }

    private Map<String, Object> largeFileDecision(VariantInterpretation variant) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("analysisBucket", variant.getAnalysisBucket());
        values.put("largeFileMode", variant.isLargeFileMode());
        values.put("summaryOnly", variant.isSummaryOnly());
        values.put("fullInterpretationAvailable", variant.isFullInterpretationAvailable());
        values.put("expensiveAnnotationSkipped", variant.isExpensiveAnnotationSkipped());
        values.put("expensiveAnnotationSkippedReason", variant.getExpensiveAnnotationSkippedReason());
        values.put("cheapGateReasons", variant.getCheapGateReasons());
        values.put("safetyNote",
                "Large-file bucket and skipped flags describe analysis execution scope only; "
                        + "they are not benign/pathogenic classifications.");
        return values;
    }

    private Map<String, Object> observation(VariantQcObservation observation) {
        if (observation == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        NormalizedVariantKey key = observation.getKey();
        if (key != null) {
            values.put("genomeAssembly", key.getGenomeAssembly());
            values.put("chromosome", key.getChromosome());
            values.put("position", key.getPosition());
            values.put("reference", key.getReference());
            values.put("alternate", key.getAlternate());
        }
        values.put("sampleId", observation.getSampleId());
        values.put("genotype", observation.getGenotype());
        values.put("depth", observation.getDepth());
        values.put("genotypeQuality", observation.getGenotypeQuality());
        values.put("alleleDepths", observation.getAlleleDepths());
        values.put("alleleBalance", observation.getAlleleBalance());
        values.put("filterStatus", observation.getFilterStatus());
        values.put("variantType", observation.getVariantType());
        values.put("originalRecord", sanitizeText(observation.getOriginalRecord()));
        NormalizationAlleleMapping mapping = observation.normalizationMapping();
        values.put("canonicalVariantId", sanitizeText(mapping.getCanonicalVariantId()));
        values.put("rawRecordId", sanitizeText(mapping.getRawRecordId()));
        values.put("rawAlternateIndex", mapping.getRawAlternateIndex());
        values.put("normalizationMappingStatus", mapping.getStatus());
        return values;
    }

    private List<Map<String, Object>> consequences(List<TranscriptConsequence> consequences) {
        if (consequences == null || consequences.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (TranscriptConsequence consequence : consequences) {
            values.add(consequence(consequence));
        }
        return values;
    }

    private Map<String, Object> consequence(TranscriptConsequence consequence) {
        if (consequence == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("geneSymbol", consequence.getGeneSymbol());
        values.put("entrezGeneId", consequence.getEntrezGeneId());
        values.put("transcriptAccession", consequence.getTranscriptAccession());
        values.put("hgvsCoding", consequence.getHgvsCoding());
        values.put("hgvsProtein", consequence.getHgvsProtein());
        values.put("consequence", consequence.getConsequence());
        values.put("geneId", consequence.getGeneId());
        values.put("transcriptId", consequence.getTranscriptId());
        values.put("transcriptSource", consequence.getTranscriptSource());
        values.put("impact", consequence.getImpact());
        values.put("hgvsc", consequence.getHgvsc());
        values.put("hgvsp", consequence.getHgvsp());
        values.put("exon", consequence.getExon());
        values.put("intron", consequence.getIntron());
        values.put("canonical", consequence.getCanonical());
        values.put("maneSelect", consequence.getManeSelect());
        values.put("manePlusClinical", consequence.getManePlusClinical());
        values.put("proteinPosition", consequence.getProteinPosition());
        values.put("aminoAcids", consequence.getAminoAcids());
        values.put("codons", consequence.getCodons());
        values.put("lof", consequence.getLof());
        values.put("lofFilter", consequence.getLofFilter());
        values.put("lofFlags", consequence.getLofFlags());
        values.put("source", consequence.getSource());
        values.put("status", consequence.getStatus());
        values.put("version", consequence.getVersion());
        values.put("timestamp", consequence.getTimestamp());
        return values;
    }

    private List<Map<String, Object>> evidence(List<EvidenceItem> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (EvidenceItem item : evidence) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("schemaVersion", item.getSchemaVersion());
            value.put("id", item.getId());
            value.put("canonicalVariantId", item.getCanonicalVariantId());
            value.put("normalizedContig", item.getNormalizedContig());
            value.put("normalizedPosition", item.getNormalizedPosition());
            value.put("normalizedReference", item.getNormalizedReference());
            value.put("normalizedAlternate", item.getNormalizedAlternate());
            value.put("geneId", item.getGeneId());
            value.put("transcriptId", item.getTranscriptId());
            value.put("diseaseId", item.getDiseaseId());
            value.put("source", item.getSource());
            value.put("sourceRecordId", item.getSourceRecordId());
            value.put("sourceVersion", item.getSourceVersion());
            value.put("resourceVersion", item.getEffectiveResourceVersion());
            value.put("resourceSha256", item.getResourceSha256());
            value.put("assembly", item.getAssembly());
            value.put("evidenceType", item.getEvidenceType());
            value.put("applicability", item.getApplicability());
            value.put("status", item.getEffectiveStatus());
            value.put("rawScore", item.getRawScore());
            value.put("rawValue", item.getRawValue());
            value.put("normalizedValue", item.getNormalizedValue());
            value.put("calibrationProfile", item.getCalibrationProfile());
            value.put("evidenceStrength", item.getEvidenceStrength());
            value.put("label", item.getLabel());
            value.put("summary", item.getSummary());
            value.put("provenance", sanitizeDetails(item.getProvenance()));
            value.put("retrievedAt", item.getRetrievedAt());
            value.put("explanationCode", item.getExplanationCode());
            value.put("details", sanitizeDetails(item.getDetails()));
            values.add(value);
        }
        return values;
    }

    private Map<String, Object> phenotypeSupport(PhenotypeExportView phenotype) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("supported", phenotype.supported);
        values.put("phenotypeScore", phenotype.phenotypeScore);
        values.put("scoreBoost", phenotype.scoreBoost);
        values.put("genePriorRank", phenotype.genePriorRank);
        values.put("source", phenotype.source);
        values.put("matchedHpoTerms", phenotype.matchedHpoTerms);
        values.put("evidence", phenotype.evidence);
        values.put("warning", phenotype.warning);
        return values;
    }

    private List<Map<String, Object>> acmgEvidenceSuggestions(List<AcmgEvidenceSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> values = new ArrayList<Map<String, Object>>();
        for (AcmgEvidenceSuggestion suggestion : suggestions) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("code", suggestion.getCode());
            value.put("strength", suggestion.getStrength());
            value.put("status", suggestion.getStatus());
            value.put("sourceEvidence", suggestion.getSourceEvidence());
            value.put("explanation", suggestion.getExplanation());
            value.put("manualReviewRequired", suggestion.isManualReviewRequired());
            value.put("ruleVersion", suggestion.getRuleVersion());
            value.put("warnings", suggestion.getWarnings());
            values.add(value);
        }
        return values;
    }

    private Map<String, Object> scoreComponents(Map<String, ScoreBreakdownComponent> components) {
        if (components == null || components.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, ScoreBreakdownComponent> entry : components.entrySet()) {
            Map<String, Object> component = new LinkedHashMap<String, Object>();
            component.put("score", entry.getValue().getScore());
            component.put("reasons", entry.getValue().getReasons());
            values.put(entry.getKey(), component);
        }
        return values;
    }

    private PhenotypeExportView phenotypeExportView(VariantInterpretation variant) {
        if (variant == null) {
            return PhenotypeExportView.unsupported();
        }
        List<EvidenceItem> phenotypeEvidence = phenotypeEvidence(variant);
        ScoreBreakdownComponent component = variant.getScoreComponents() == null
                ? null
                : variant.getScoreComponents().get(PHENOTYPE_SCORE_COMPONENT);
        Double scoreBreakdownBoost = variant.getScoreBreakdown() == null
                ? null
                : variant.getScoreBreakdown().get(PHENOTYPE_SCORE_COMPONENT);
        boolean supported = !phenotypeEvidence.isEmpty()
                || component != null
                || scoreBreakdownBoost != null
                || hasPhenotypeSupportReason(variant);
        if (!supported) {
            return PhenotypeExportView.unsupported();
        }

        Double phenotypeScore = null;
        Double scoreBoost = null;
        Integer genePriorRank = null;
        String source = null;
        double bestEvidencePriority = Double.NEGATIVE_INFINITY;
        Set<String> matchedHpoTerms = new LinkedHashSet<String>();
        Set<String> evidenceSummaries = new LinkedHashSet<String>();

        for (EvidenceItem item : phenotypeEvidence) {
            Double itemScore = numericDetail(item, "phenotypeScore");
            if (itemScore == null) {
                itemScore = item.getRawScore();
            }
            if (itemScore == null) {
                itemScore = numericDetail(item, "score");
            }
            Double itemBoost = numericDetail(item, "scoreBoost");
            Integer itemRank = integerDetail(item, "phenotypeRank");
            double priority = itemScore == null
                    ? (itemBoost == null ? Double.NEGATIVE_INFINITY : itemBoost.doubleValue())
                    : itemScore.doubleValue();
            if (priority > bestEvidencePriority) {
                bestEvidencePriority = priority;
                phenotypeScore = itemScore;
                scoreBoost = itemBoost;
                genePriorRank = itemRank;
                source = sanitizeText(item.getSource());
            }
            addDetailValues(matchedHpoTerms, item.getDetails().get("matchedHpoTerms"));
            addDetailValues(evidenceSummaries, item.getDetails().get("evidence"));
            addText(evidenceSummaries, item.getSummary());
        }

        if (scoreBoost == null && component != null) {
            scoreBoost = component.getScore();
        }
        if (scoreBoost == null) {
            scoreBoost = scoreBreakdownBoost;
        }
        if (evidenceSummaries.isEmpty() && component != null) {
            addTextValues(evidenceSummaries, component.getReasons());
        }
        if (evidenceSummaries.isEmpty()) {
            addPhenotypeSupportReasons(evidenceSummaries, variant);
        }
        if (source == null && component != null) {
            source = PHENOTYPE_SCORE_COMPONENT;
        }

        return new PhenotypeExportView(
                true,
                phenotypeScore,
                scoreBoost,
                genePriorRank,
                source,
                new ArrayList<String>(matchedHpoTerms),
                new ArrayList<String>(evidenceSummaries),
                PHENOTYPE_EXPORT_WARNING
        );
    }

    private List<EvidenceItem> phenotypeEvidence(VariantInterpretation variant) {
        if (variant == null || variant.getEvidence() == null || variant.getEvidence().isEmpty()) {
            return Collections.emptyList();
        }
        List<EvidenceItem> values = new ArrayList<EvidenceItem>();
        for (EvidenceItem item : variant.getEvidence()) {
            if (item != null && PHENOTYPE_EVIDENCE_TYPE.equals(item.getEvidenceType())) {
                values.add(item);
            }
        }
        return values;
    }

    private boolean hasPhenotypeSupportReason(VariantInterpretation variant) {
        if (variant == null || variant.getPositiveReasons() == null) {
            return false;
        }
        for (String reason : variant.getPositiveReasons()) {
            String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
            if (normalized.contains("phenotype") && normalized.contains("support")) {
                return true;
            }
        }
        return false;
    }

    private void addPhenotypeSupportReasons(Set<String> values, VariantInterpretation variant) {
        if (variant == null || variant.getPositiveReasons() == null) {
            return;
        }
        for (String reason : variant.getPositiveReasons()) {
            String normalized = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
            if (normalized.contains("phenotype") && normalized.contains("support")) {
                addText(values, reason);
            }
        }
    }

    private Double numericDetail(EvidenceItem evidence, String key) {
        if (evidence == null || evidence.getDetails() == null) {
            return null;
        }
        Object value = sanitizeValue(evidence.getDetails().get(key));
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer integerDetail(EvidenceItem evidence, String key) {
        if (evidence == null || evidence.getDetails() == null) {
            return null;
        }
        Object value = sanitizeValue(evidence.getDetails().get(key));
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.valueOf(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private void addDetailValues(Set<String> values, Object rawValue) {
        Object value = sanitizeValue(rawValue);
        if (value instanceof Collection<?>) {
            for (Object item : (Collection<?>) value) {
                addText(values, item);
            }
        } else {
            addText(values, value);
        }
    }

    private void addTextValues(Set<String> values, Collection<String> rawValues) {
        if (rawValues == null) {
            return;
        }
        for (String value : rawValues) {
            addText(values, value);
        }
    }

    private void addText(Set<String> values, Object rawValue) {
        Object value = sanitizeValue(rawValue);
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isEmpty()) {
            values.add(text);
        }
    }

    private List<String> unavailableResources(List<VariantInterpretation> variants) {
        Set<String> resources = new LinkedHashSet<String>();
        for (VariantInterpretation variant : variants) {
            for (EvidenceItem item : variant.getEvidence()) {
                if ("UNAVAILABLE".equals(item.getApplicability())) {
                    resources.add(item.getSource());
                }
            }
        }
        return new ArrayList<String>(resources);
    }

    private Map<String, Object> runtimeMetrics(List<VariantAnalysisEvent> events) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("eventCount", events == null ? 0 : events.size());
        values.put("timeline", eventTimeline(events));
        if (events != null && !events.isEmpty()) {
            Instant first = events.get(0).getCreatedAt();
            Instant last = events.get(events.size() - 1).getCreatedAt();
            if (first != null && last != null) {
                values.put("elapsedMillis", Duration.between(first, last).toMillis());
            }
        }
        return values;
    }

    private List<Map<String, Object>> eventTimeline(List<VariantAnalysisEvent> events) {
        if (events == null || events.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> timeline = new ArrayList<Map<String, Object>>();
        for (VariantAnalysisEvent event : events) {
            Map<String, Object> value = new LinkedHashMap<String, Object>();
            value.put("status", event.getStatus());
            value.put("message", sanitizeText(event.getMessage()));
            value.put("createdAt", event.getCreatedAt());
            timeline.add(value);
        }
        return timeline;
    }

    private void appendCsvRow(StringBuilder builder, List<?> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(csvEscape(values.get(index)));
        }
        builder.append("\r\n");
    }

    private String csvEscape(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (value instanceof CharSequence && requiresSpreadsheetFormulaNeutralization(text)) {
            // RFC 4180 quoting does not prevent spreadsheet applications from evaluating a cell
            // as a formula.  Prefix uploader- or provider-controlled text centrally so every CSV
            // text column receives the same protection while numeric values remain numeric.
            text = "'" + text;
        }
        if (text.contains("\"") || text.contains(",") || text.contains("\r") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private boolean requiresSpreadsheetFormulaNeutralization(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        int index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        if (index >= text.length()) {
            return false;
        }
        char first = text.charAt(index);
        return first == '=' || first == '+' || first == '-' || first == '@';
    }

    private Object sanitizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (isSensitiveKey(key)) {
                    continue;
                }
                Object sanitizedValue = sanitizeValue(entry.getValue());
                if (sanitizedValue != null) {
                    sanitized.put(key, sanitizedValue);
                }
            }
            return sanitized;
        }
        if (value instanceof Collection<?>) {
            List<Object> sanitized = new ArrayList<Object>();
            for (Object item : (Collection<?>) value) {
                Object sanitizedValue = sanitizeValue(item);
                if (sanitizedValue != null) {
                    sanitized.add(sanitizedValue);
                }
            }
            return sanitized;
        }
        String text = String.valueOf(value);
        if (looksLikeLocalPath(text)) {
            return null;
        }
        return value;
    }

    private Double finite(Double value) {
        return value != null && Double.isFinite(value.doubleValue()) ? value : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeDetails(Map<String, Object> details) {
        Object sanitized = sanitizeValue(details);
        if (sanitized instanceof Map<?, ?>) {
            return (Map<String, Object>) sanitized;
        }
        return Collections.emptyMap();
    }

    private String sanitizeText(String text) {
        if (text == null) {
            return null;
        }
        return looksLikeLocalPath(text) ? "[local-path-redacted]" : text;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key == null ? "" : key.toLowerCase(Locale.ROOT);
        return normalized.contains("file")
                || normalized.contains("path")
                || normalized.contains("directory")
                || normalized.contains("workspace");
    }

    private boolean looksLikeLocalPath(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return false;
        }
        return value.matches("(?i).*[a-z]:\\\\.*")
                || value.matches(".*\\\\[^\\\\]+\\\\.*")
                || value.matches("^/(users|home|tmp|var|mnt|data|d|c)/.*");
    }

    private void putVersion(Map<String, String> versions,
                            VariantAnalysisProviderStatusResponse.ProviderStatus status) {
        if (status != null && status.getSource() != null && status.getVersion() != null) {
            versions.put(status.getSource(), status.getVersion());
        }
    }

    private EvidenceItem evidence(VariantInterpretation variant, String source) {
        for (EvidenceItem item : variant.getEvidence()) {
            if (source.equals(item.getSource())) {
                return item;
            }
        }
        return null;
    }

    private Object detail(EvidenceItem evidence, String key) {
        return evidence == null ? null : sanitizeValue(evidence.getDetails().get(key));
    }

    private Object detailOrLabel(EvidenceItem evidence, String primaryKey, String fallbackKey) {
        Object value = detail(evidence, primaryKey);
        if (value == null) {
            value = detail(evidence, fallbackKey);
        }
        return value == null && evidence != null ? evidence.getLabel() : value;
    }

    private Object detailFirst(EvidenceItem evidence, String... keys) {
        if (evidence == null) {
            return null;
        }
        for (String key : keys) {
            Object value = detail(evidence, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Object detailOrScore(EvidenceItem evidence, String... keys) {
        if (evidence == null) {
            return null;
        }
        for (String key : keys) {
            Object value = detail(evidence, key);
            if (value != null) {
                return value;
            }
        }
        return evidence.getRawScore();
    }

    private Object evidenceScore(VariantInterpretation variant, String source) {
        EvidenceItem item = evidence(variant, source);
        return item == null ? null : item.getRawScore();
    }

    private Object nestedDetail(EvidenceItem evidence, String parent, String key) {
        Object value = detail(evidence, parent);
        if (!(value instanceof Map)) {
            return null;
        }
        return ((Map<?, ?>) value).get(key);
    }

    private String joinObject(Object value) {
        if (value instanceof Collection) {
            List<String> values = new ArrayList<String>();
            for (Object item : (Collection<?>) value) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return join(values);
        }
        return value == null ? null : String.valueOf(value);
    }

    private String evidenceSource(EvidenceItem evidence) {
        if (evidence == null) {
            return null;
        }
        Object source = detail(evidence, "source");
        if (source != null) {
            return String.valueOf(source);
        }
        return evidence.getSourceVersion();
    }

    private TranscriptConsequence firstConsequence(VariantInterpretation variant) {
        return variant.getConsequences().isEmpty() ? null : variant.getConsequences().get(0);
    }

    private TranscriptConsequence firstTranscriptAnnotation(VariantInterpretation variant) {
        return variant.getTranscriptAnnotations().isEmpty() ? null : variant.getTranscriptAnnotations().get(0);
    }

    private String keyValue(NormalizedVariantKey key, String field) {
        if (key == null) {
            return null;
        }
        if ("chromosome".equals(field)) {
            return key.getChromosome();
        }
        if ("reference".equals(field)) {
            return key.getReference();
        }
        if ("alternate".equals(field)) {
            return key.getAlternate();
        }
        return null;
    }

    private String scoreBreakdownText(VariantInterpretation variant) {
        Map<String, Double> scores = variant.getScoreBreakdown();
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            parts.add(entry.getKey() + "=" + entry.getValue());
        }
        return join(parts);
    }

    private String acmgSuggestionsText(VariantInterpretation variant) {
        if (variant.getAcmgEvidenceSuggestions().isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<String>();
        for (AcmgEvidenceSuggestion suggestion : variant.getAcmgEvidenceSuggestions()) {
            if ("SUGGESTED".equals(suggestion.getStatus())) {
                parts.add(suggestion.getCode() + ":" + suggestion.getStatus() + ":" + suggestion.getStrength());
            }
        }
        return parts.isEmpty() ? null : join(parts);
    }

    private String mapText(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> keys = new ArrayList<String>(values.keySet());
        Collections.sort(keys);
        List<String> parts = new ArrayList<String>();
        for (String key : keys) {
            String sanitizedKey = sanitizeText(key);
            String sanitizedValue = sanitizeText(values.get(key));
            if (sanitizedKey != null && sanitizedValue != null) {
                parts.add(sanitizedKey + "=" + sanitizedValue);
            }
        }
        return parts.isEmpty() ? null : join(parts);
    }

    private String sanitizedListText(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> sanitized = new ArrayList<String>();
        for (String value : values) {
            String safe = sanitizeText(value);
            if (safe != null) {
                sanitized.add(safe);
            }
        }
        return sanitized.isEmpty() ? null : join(sanitized);
    }

    private String join(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join("; ", values);
    }

    private List<Object> values(Object... values) {
        List<Object> result = new ArrayList<Object>();
        Collections.addAll(result, values);
        return result;
    }

    private static class PhenotypeExportView {

        private final boolean supported;
        private final Double phenotypeScore;
        private final Double scoreBoost;
        private final Integer genePriorRank;
        private final String source;
        private final List<String> matchedHpoTerms;
        private final List<String> evidence;
        private final String warning;

        private PhenotypeExportView(boolean supported,
                                    Double phenotypeScore,
                                    Double scoreBoost,
                                    Integer genePriorRank,
                                    String source,
                                    List<String> matchedHpoTerms,
                                    List<String> evidence,
                                    String warning) {
            this.supported = supported;
            this.phenotypeScore = phenotypeScore;
            this.scoreBoost = scoreBoost;
            this.genePriorRank = genePriorRank;
            this.source = source;
            this.matchedHpoTerms = matchedHpoTerms == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(matchedHpoTerms));
            this.evidence = evidence == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(evidence));
            this.warning = warning;
        }

        private static PhenotypeExportView unsupported() {
            return new PhenotypeExportView(
                    false,
                    null,
                    null,
                    null,
                    null,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    null
            );
        }
    }

    private static class ExportContext {

        private final VariantAnalysisJob job;
        private final VariantAnalysisVariantPageResponse page;
        private final List<VariantAnalysisEvent> events;

        private ExportContext(VariantAnalysisJob job,
                              VariantAnalysisVariantPageResponse page,
                              List<VariantAnalysisEvent> events) {
            this.job = job;
            this.page = page;
            this.events = events == null ? Collections.emptyList() : events;
        }
    }
}
