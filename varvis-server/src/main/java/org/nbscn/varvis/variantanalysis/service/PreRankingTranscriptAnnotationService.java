package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantAnalysisBucket;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.VepReadinessReport;
import org.nbscn.varvis.variantanalysis.provider.VepResourceProfile;
import org.nbscn.varvis.variantanalysis.provider.VepResourceProfileResolution;
import org.nbscn.varvis.variantanalysis.provider.VepResourceProfileResolver;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PreRankingTranscriptAnnotationService {

    private static final String SOURCE = "PRE_RANKING_VEP";
    private static final String CACHED_SOURCE = "PRE_RANKING_VEP_CACHED";
    private static final Pattern PHENOTYPE_RESCUE_RANK =
            Pattern.compile("phenotype-prior gene rank\\s+(\\d+)", Pattern.CASE_INSENSITIVE);

    private final VariantAnalysisProperties properties;
    private final VepResourceValidator resourceValidator;
    private final VepTranscriptAnnotationEnrichmentService vepAnnotationService;
    private final VepResourceProfileResolver profileResolver;

    public PreRankingTranscriptAnnotationService(
            VariantAnalysisProperties properties,
            VepResourceValidator resourceValidator,
            VepTranscriptAnnotationEnrichmentService vepAnnotationService
    ) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
        this.resourceValidator = resourceValidator;
        this.vepAnnotationService = vepAnnotationService;
        this.profileResolver = new VepResourceProfileResolver(this.properties);
    }

    public PreRankingAnnotationSummary annotateCandidates(List<VariantInterpretation> variants,
                                                          AnalysisContext context) {
        long started = System.nanoTime();
        VariantAnalysisProperties.Vep vep = properties.getVep();
        GenomeAssembly assembly = context == null ? null : context.getGenomeAssembly();
        if (!vep.isPreRankingEnabled()) {
            return PreRankingAnnotationSummary.skipped("SKIPPED", "Pre-ranking VEP annotation is disabled.");
        }
        int totalCandidates = variants == null ? 0 : variants.size();
        int alreadyAnnotated = alreadyAnnotatedCount(variants);
        int hardFiltered = hardFilteredCount(variants);
        List<VariantInterpretation> eligible = eligibleCandidates(variants);
        int cap = vep.normalizedPreRankingMaxCandidates();
        int skippedByCap = Math.max(0, eligible.size() - cap);
        Map<String, Integer> skippedByReason = skippedByReason(skippedByCap);
        List<VariantInterpretation> selected = cap == 0
                ? Collections.emptyList()
                : eligible.subList(0, Math.min(cap, eligible.size()));
        if (context != null) {
            context.getBenchmarkTelemetry().observeVepSelection(eligible, selected, cap);
        }
        if (eligible.isEmpty()) {
            return new PreRankingAnnotationSummary(
                    "NOT_REQUESTED",
                    0,
                    0,
                    0,
                    0,
                    0,
                    skippedByCap,
                    0,
                    elapsedMillis(started),
                    false,
                    vep.normalizedPreRankingProvider(),
                    providerVersion(assembly),
                    cacheVersion(assembly),
                    optionsHash(assembly),
                    skippedByReason,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    totalCandidates,
                    eligible.size(),
                    cap,
                    alreadyAnnotated,
                    hardFiltered,
                    Collections.emptyList()
            );
        }
        if (selected.isEmpty()) {
            return new PreRankingAnnotationSummary(
                    "SKIPPED_BY_CAP",
                    0,
                    0,
                    0,
                    0,
                    0,
                    eligible.size(),
                    0,
                    elapsedMillis(started),
                    false,
                    vep.normalizedPreRankingProvider(),
                    providerVersion(assembly),
                    cacheVersion(assembly),
                    optionsHash(assembly),
                    skippedByReason,
                    0,
                    0,
                    0,
                    0,
                    eligible.size(),
                    0,
                    totalCandidates,
                    eligible.size(),
                    cap,
                    alreadyAnnotated,
                    hardFiltered,
                    Collections.singletonList("Pre-ranking transcript annotation cap is 0.")
            );
        }
        VepReadinessReport readiness = resourceValidator == null ? null : resourceValidator.validate(assembly);
        if (!readyForExecution(readiness)) {
            String explanation = readiness == null
                    ? "VEP readiness could not be evaluated."
                    : readiness.getExplanation();
            String status = unavailableStatus(readiness);
            applyPlaceholders(selected, status, explanation, readinessVersion(readiness, assembly));
            return unavailableSummary(
                    status,
                    selected.size(),
                    skippedByCap,
                    elapsedMillis(started),
                    true,
                    totalCandidates,
                    eligible.size(),
                    cap,
                    skippedByReason,
                    alreadyAnnotated,
                    hardFiltered,
                    explanation,
                    assembly
            );
        }
        if (vepAnnotationService == null) {
            applyPlaceholders(
                    selected,
                    "UNAVAILABLE",
                    "VEP annotation service is not configured.",
                    providerVersion(assembly)
            );
            return unavailableSummary(
                    "UNAVAILABLE",
                    selected.size(),
                    skippedByCap,
                    elapsedMillis(started),
                    true,
                    totalCandidates,
                    eligible.size(),
                    cap,
                    skippedByReason,
                    alreadyAnnotated,
                    hardFiltered,
                    "VEP annotation service is not configured.",
                    assembly
            );
        }

        VepTranscriptAnnotationEnrichmentService.VepAnnotationResult result =
                vepAnnotationService.annotateCandidates(
                        selected,
                        SOURCE,
                        CACHED_SOURCE,
                        SOURCE,
                        "PROVIDER_ERROR",
                        "TIMEOUT",
                        vep.normalizedPreRankingBatchSize(),
                        vep.normalizedPreRankingTimeout(),
                        vep.getPreRankingTempDir(),
                        assembly
                );
        int annotationRows = applyAnnotations(selected, result.getAnnotationsByVariantId());
        VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary vepSummary = result.getSummary();
        List<String> warnings = new ArrayList<String>(vepSummary.getWarnings());
        if (skippedByCap > 0) {
            warnings.add("PRE_RANKING_CANDIDATE_CAP_EXCEEDED: eligible=" + eligible.size()
                    + ", cap=" + cap
                    + ", selected=" + selected.size()
                    + ", skipped=" + skippedByCap
                    + ", assembly=" + (assembly == null ? "UNKNOWN" : assembly.name()));
        }
        boolean failOpenUsed = failOpenUsed(vepSummary);
        if (failOpenUsed && !vep.isPreRankingFailOpen()) {
            throw new IllegalStateException("Pre-ranking transcript annotation failed and fail-open is disabled: "
                    + statusFromVepSummary(vepSummary));
        }
        return new PreRankingAnnotationSummary(
                statusFromVepSummary(vepSummary),
                selected.size(),
                vepSummary.getProcessedCount(),
                vepSummary.getSuccessCount(),
                vepSummary.getFailedCount(),
                vepSummary.getCacheHitCount(),
                skippedByCap,
                annotationRows,
                elapsedMillis(started),
                failOpenUsed,
                vep.normalizedPreRankingProvider(),
                providerVersion(assembly),
                cacheVersion(assembly),
                optionsHash(assembly),
                skippedByReason,
                vepSummary.getCacheMissCount(),
                vepSummary.getProviderBatchCount(),
                vepSummary.getProviderTimeoutCount(),
                vepSummary.getProviderErrorCount(),
                vepSummary.getUnavailableCount(),
                vepSummary.getAnnotatedAvailableCount(),
                totalCandidates,
                eligible.size(),
                cap,
                alreadyAnnotated,
                hardFiltered,
                warnings
        );
    }

    private int alreadyAnnotatedCount(List<VariantInterpretation> variants) {
        if (variants == null || variants.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (VariantInterpretation variant : variants) {
            if (variant != null && hasUsableGeneAnnotation(variant)) {
                count++;
            }
        }
        return count;
    }

    private int hardFilteredCount(List<VariantInterpretation> variants) {
        if (variants == null || variants.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (VariantInterpretation variant : variants) {
            if (variant == null || variant.getObservation() == null) {
                continue;
            }
            if (hardGateApplies(variant)
                    || variant.isSummaryOnly()
                    || variant.isExpensiveAnnotationSkipped()
                    || !carriesAlternateAllele(variant.getObservation().getGenotype())) {
                count++;
            }
        }
        return count;
    }

    private List<VariantInterpretation> eligibleCandidates(List<VariantInterpretation> variants) {
        if (variants == null || variants.isEmpty()) {
            return Collections.emptyList();
        }
        List<VariantInterpretation> eligible = new ArrayList<VariantInterpretation>();
        for (VariantInterpretation variant : variants) {
            if (isEligible(variant)) {
                eligible.add(variant);
            }
        }
        Collections.sort(eligible, candidateComparator());
        return eligible;
    }

    private boolean isEligible(VariantInterpretation variant) {
        if (variant == null || variant.getObservation() == null) {
            return false;
        }
        if (hasUsableGeneAnnotation(variant)) {
            return false;
        }
        if (variant.isSummaryOnly() || variant.isExpensiveAnnotationSkipped()) {
            return false;
        }
        if (!carriesAlternateAllele(variant.getObservation().getGenotype())) {
            return false;
        }
        if (hardGateApplies(variant)) {
            return false;
        }
        return true;
    }

    private Comparator<VariantInterpretation> candidateComparator() {
        return new Comparator<VariantInterpretation>() {
            @Override
            public int compare(VariantInterpretation left, VariantInterpretation right) {
                int clinical = Boolean.compare(hasClinicalSignal(right), hasClinicalSignal(left));
                if (clinical != 0) {
                    return clinical;
                }
                int phenotypeRescue = Integer.compare(
                        phenotypeRescueRank(left),
                        phenotypeRescueRank(right)
                );
                if (phenotypeRescue != 0) {
                    return phenotypeRescue;
                }
                int pass = Boolean.compare(isPassOrUnknown(right), isPassOrUnknown(left));
                if (pass != 0) {
                    return pass;
                }
                int consequence = Integer.compare(consequencePriority(right), consequencePriority(left));
                if (consequence != 0) {
                    return consequence;
                }
                int score = Double.compare(scoreOrNegative(right.getRankingScore()), scoreOrNegative(left.getRankingScore()));
                if (score != 0) {
                    return score;
                }
                int confidence = Long.compare(candidateConfidence(right), candidateConfidence(left));
                return confidence != 0 ? confidence : compareKeys(left, right);
            }
        };
    }

    private int consequencePriority(VariantInterpretation variant) {
        String text = upper(consequenceText(variant));
        if (text.contains("STOP_GAINED") || text.contains("FRAMESHIFT")
                || text.contains("SPLICE_ACCEPTOR") || text.contains("SPLICE_DONOR")
                || text.contains("START_LOST") || text.contains("STOP_LOST")) {
            return 3;
        }
        if (text.contains("MISSENSE")) {
            return 2;
        }
        return 1;
    }

    private String consequenceText(VariantInterpretation variant) {
        StringBuilder builder = new StringBuilder();
        appendConsequences(builder, variant == null ? null : variant.getConsequences());
        appendConsequences(builder, variant == null ? null : variant.getTranscriptAnnotations());
        return builder.toString();
    }

    private void appendConsequences(StringBuilder builder, List<TranscriptConsequence> consequences) {
        if (consequences == null || consequences.isEmpty()) {
            return;
        }
        for (TranscriptConsequence consequence : consequences) {
            if (consequence != null && hasText(consequence.getConsequence())) {
                if (builder.length() > 0) {
                    builder.append('|');
                }
                builder.append(consequence.getConsequence());
            }
        }
    }

    private boolean hardGateApplies(VariantInterpretation variant) {
        String tier = upper(variant.getPriorityTier());
        if ("FILTERED_COMMON".equals(tier) || "FILTERED_QC".equals(tier)
                || "BENIGN_LIKELY".equals(tier)) {
            return true;
        }
        VariantAnalysisBucket bucket = variant.getAnalysisBucket();
        return bucket == VariantAnalysisBucket.FILTERED_COMMON
                || bucket == VariantAnalysisBucket.FILTERED_QC
                || bucket == VariantAnalysisBucket.FILTERED_NOT_CARRIED;
    }

    private boolean hasUsableGeneAnnotation(VariantInterpretation variant) {
        return hasGene(variant.getConsequences()) || hasGene(variant.getTranscriptAnnotations());
    }

    private boolean hasGene(List<TranscriptConsequence> annotations) {
        if (annotations == null) {
            return false;
        }
        for (TranscriptConsequence annotation : annotations) {
            if (annotation != null && hasText(annotation.getGeneSymbol())) {
                return true;
            }
        }
        return false;
    }

    private boolean carriesAlternateAllele(String genotype) {
        if (!hasText(genotype)) {
            return true;
        }
        String normalized = genotype.trim().replace('|', '/');
        if (normalized.contains(".")) {
            return true;
        }
        String[] alleles = normalized.split("/");
        for (String allele : alleles) {
            if (!"0".equals(allele)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasClinicalSignal(VariantInterpretation variant) {
        for (EvidenceItem evidence : variant.getEvidence()) {
            if (!"CLINVAR".equals(evidence.getSource())) {
                continue;
            }
            String label = upper(evidence.getLabel());
            String applicability = upper(evidence.getApplicability());
            if ("CONFLICTING".equals(applicability) || label.contains("PATHOGENIC")
                    || label.contains("CONFLICT") || label.contains("VUS")
                    || label.contains("UNCERTAIN")) {
                return true;
            }
        }
        return false;
    }

    private boolean isPassOrUnknown(VariantInterpretation variant) {
        VariantQcObservation observation = variant.getObservation();
        String filter = observation == null ? null : observation.getFilterStatus();
        if (filter == null) {
            return true;
        }
        String normalizedFilter = filter.trim();
        return normalizedFilter.isEmpty() || "PASS".equalsIgnoreCase(normalizedFilter);
    }

    private int phenotypeRescueRank(VariantInterpretation variant) {
        if (variant == null) {
            return Integer.MAX_VALUE;
        }
        for (String reason : variant.getCheapGateReasons()) {
            Matcher matcher = PHENOTYPE_RESCUE_RANK.matcher(reason == null ? "" : reason);
            if (!matcher.find()) {
                continue;
            }
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return Integer.MAX_VALUE;
            }
        }
        return Integer.MAX_VALUE;
    }

    private long candidateConfidence(VariantInterpretation variant) {
        VariantQcObservation observation = variant == null ? null : variant.getObservation();
        if (observation == null) {
            return 0L;
        }
        long genotypeQuality = observation.getGenotypeQuality() == null
                ? 0L
                : Math.min(999L, Math.max(0L, observation.getGenotypeQuality().longValue()));
        long depth = observation.getDepth() == null
                ? 0L
                : Math.min(500L, Math.max(0L, observation.getDepth().longValue()));
        Double alleleBalance = observation.getAlleleBalance();
        long balance = alleleBalance == null
                || Double.isNaN(alleleBalance.doubleValue())
                || Double.isInfinite(alleleBalance.doubleValue())
                ? 0L
                : Math.max(0L, Math.round(
                        (1.0d - Math.min(1.0d, Math.abs(alleleBalance.doubleValue() - 0.5d))) * 999.0d
                ));
        return genotypeQuality * 1_000_000L + depth * 1_000L + balance;
    }

    private int compareKeys(VariantInterpretation left, VariantInterpretation right) {
        NormalizedVariantKey leftKey = left == null || left.getObservation() == null
                ? null
                : left.getObservation().getKey();
        NormalizedVariantKey rightKey = right == null || right.getObservation() == null
                ? null
                : right.getObservation().getKey();
        if (leftKey == rightKey) {
            return 0;
        }
        if (leftKey == null) {
            return 1;
        }
        if (rightKey == null) {
            return -1;
        }
        int assembly = safeKeyPart(leftKey.getGenomeAssembly())
                .compareTo(safeKeyPart(rightKey.getGenomeAssembly()));
        if (assembly != 0) {
            return assembly;
        }
        int chromosome = safeKeyPart(leftKey.getChromosome())
                .compareTo(safeKeyPart(rightKey.getChromosome()));
        if (chromosome != 0) {
            return chromosome;
        }
        int position = Integer.compare(leftKey.getPosition(), rightKey.getPosition());
        if (position != 0) {
            return position;
        }
        int reference = safeKeyPart(leftKey.getReference())
                .compareTo(safeKeyPart(rightKey.getReference()));
        return reference != 0
                ? reference
                : safeKeyPart(leftKey.getAlternate()).compareTo(safeKeyPart(rightKey.getAlternate()));
    }

    private String safeKeyPart(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private double scoreOrNegative(Double score) {
        return score == null ? Double.NEGATIVE_INFINITY : score.doubleValue();
    }

    private boolean readyForExecution(VepReadinessReport readiness) {
        return readiness != null
                && readiness.isEnabled()
                && readiness.isExecutionEnabled()
                && "READY".equals(readiness.getStatus());
    }

    private String unavailableStatus(VepReadinessReport readiness) {
        if (readiness == null) {
            return "UNAVAILABLE";
        }
        return "ASSEMBLY_UNSUPPORTED".equals(readiness.getStatus())
                ? "ASSEMBLY_UNSUPPORTED"
                : "UNAVAILABLE";
    }

    private String readinessVersion(VepReadinessReport readiness, GenomeAssembly assembly) {
        if (readiness != null && hasText(readiness.getVepVersion())) {
            return readiness.getVepVersion();
        }
        return providerVersion(assembly);
    }

    private String providerVersion(GenomeAssembly assembly) {
        VepResourceProfile profile = resourceProfile(assembly);
        return profile == null ? properties.getVep().getVersion() : profile.getVersion();
    }

    private String cacheVersion(GenomeAssembly assembly) {
        VepResourceProfile profile = resourceProfile(assembly);
        return profile == null ? properties.getVep().getEnsemblCacheVersion() : profile.getEnsemblCacheVersion();
    }

    private String optionsHash(GenomeAssembly assembly) {
        VepResourceProfile profile = resourceProfile(assembly);
        if (profile == null) {
            return properties.getVep().preRankingOptionsHash();
        }
        return properties.getVep().preRankingOptionsHash(
                profile.getAssembly().name(),
                profile.getVersion(),
                profile.getEnsemblCacheVersion(),
                profile.getManeVersion(),
                profile.getLofteeVersion()
        );
    }

    private VepResourceProfile resourceProfile(GenomeAssembly assembly) {
        VepResourceProfileResolution resolution = profileResolver.resolve(assembly);
        return resolution.isResolved() ? resolution.getProfile() : null;
    }

    private void applyPlaceholders(List<VariantInterpretation> variants,
                                   String status,
                                   String summary,
                                   String providerVersion) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        for (VariantInterpretation variant : variants) {
            variant.applyTranscriptAnnotations(Collections.singletonList(TranscriptConsequence.statusPlaceholder(
                    summary,
                    SOURCE,
                    status,
                    providerVersion,
                    Instant.now()
            )));
        }
    }

    private int applyAnnotations(List<VariantInterpretation> selected,
                                 Map<String, List<TranscriptConsequence>> annotationsByVariantId) {
        if (selected == null || selected.isEmpty()
                || annotationsByVariantId == null || annotationsByVariantId.isEmpty()) {
            return 0;
        }
        int applied = 0;
        for (VariantInterpretation variant : selected) {
            List<TranscriptConsequence> annotations = annotationsByVariantId.get(variant.getVariantId());
            if (annotations == null || annotations.isEmpty()) {
                continue;
            }
            variant.applyTranscriptAnnotations(annotations);
            applied += annotations.size();
        }
        return applied;
    }

    private PreRankingAnnotationSummary unavailableSummary(String status,
                                                           int selected,
                                                           int skippedByCap,
                                                           long elapsedMillis,
                                                           boolean failOpenUsed,
                                                           int totalCandidates,
                                                           int eligibleCandidates,
                                                           int preRankingCandidateCap,
                                                           Map<String, Integer> skippedByReason,
                                                           int alreadyAnnotatedCount,
                                                           int hardFilteredCount,
                                                           String warning,
                                                           GenomeAssembly assembly) {
        VariantAnalysisProperties.Vep vep = properties.getVep();
        if (failOpenUsed && !vep.isPreRankingFailOpen()) {
            throw new IllegalStateException("Pre-ranking transcript annotation failed and fail-open is disabled: "
                    + status);
        }
        return new PreRankingAnnotationSummary(
                status,
                selected,
                0,
                0,
                0,
                0,
                skippedByCap,
                selected,
                elapsedMillis,
                failOpenUsed,
                vep.normalizedPreRankingProvider(),
                providerVersion(assembly),
                cacheVersion(assembly),
                optionsHash(assembly),
                skippedByReason,
                0,
                0,
                0,
                0,
                selected,
                0,
                totalCandidates,
                eligibleCandidates,
                preRankingCandidateCap,
                alreadyAnnotatedCount,
                hardFilteredCount,
                warning == null ? Collections.emptyList() : Collections.singletonList(warning)
        );
    }

    private String statusFromVepSummary(
            VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary summary) {
        if (summary == null) {
            return "PROVIDER_ERROR";
        }
        if (summary.getProviderTimeoutCount() > 0 && summary.getSuccessCount() == 0) {
            return "TIMEOUT";
        }
        if (summary.getProviderErrorCount() > 0 && summary.getSuccessCount() == 0) {
            return "PROVIDER_ERROR";
        }
        if (summary.getAnnotatedAvailableCount() > 0 && summary.getFailedCount() == 0) {
            return "AVAILABLE";
        }
        if (summary.getSuccessCount() > 0) {
            return "PARTIAL";
        }
        if (containsIgnoreCase(summary.getWarnings(), "timed out")) {
            return "TIMEOUT";
        }
        if (summary.getFailedCount() > 0) {
            return "PROVIDER_ERROR";
        }
        return "UNAVAILABLE";
    }

    private boolean failOpenUsed(VepTranscriptAnnotationEnrichmentService.VepEnrichmentSummary summary) {
        if (summary == null) {
            return true;
        }
        return summary.getFailedCount() > 0
                || summary.getProviderTimeoutCount() > 0
                || summary.getProviderErrorCount() > 0;
    }

    private Map<String, Integer> skippedByReason(int skippedByCap) {
        if (skippedByCap <= 0) {
            return Collections.emptyMap();
        }
        Map<String, Integer> reasons = new LinkedHashMap<String, Integer>();
        reasons.put("CANDIDATE_CAP", skippedByCap);
        return Collections.unmodifiableMap(reasons);
    }

    private boolean containsIgnoreCase(List<String> values, String needle) {
        if (values == null || needle == null) {
            return false;
        }
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private long elapsedMillis(long startedAtNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    public static class PreRankingAnnotationSummary {

        private final String status;
        private final int totalCandidateCount;
        private final int eligibleCandidateCount;
        private final int selectedCount;
        private final int processedCount;
        private final int successCount;
        private final int failedCount;
        private final int cacheHitCount;
        private final int skippedByCapCount;
        private final int annotationRowsApplied;
        private final long elapsedMillis;
        private final boolean failOpenUsed;
        private final String providerName;
        private final String providerVersion;
        private final String cacheVersion;
        private final String optionsHash;
        private final Map<String, Integer> skippedByReason;
        private final int cacheMissCount;
        private final int providerBatchCount;
        private final int providerTimeoutCount;
        private final int providerErrorCount;
        private final int unavailableCount;
        private final int annotatedAvailableCount;
        private final int preRankingCandidateCap;
        private final int alreadyAnnotatedCount;
        private final int hardFilteredCount;
        private final List<String> warnings;

        public PreRankingAnnotationSummary(String status,
                                           int selectedCount,
                                           int processedCount,
                                           int successCount,
                                           int failedCount,
                                           int cacheHitCount,
                                           int skippedByCapCount,
                                           int annotationRowsApplied,
                                           long elapsedMillis,
                                           List<String> warnings) {
            this(
                    status,
                    selectedCount,
                    processedCount,
                    successCount,
                    failedCount,
                    cacheHitCount,
                    skippedByCapCount,
                    annotationRowsApplied,
                    elapsedMillis,
                    false,
                    "vep",
                    null,
                    null,
                    null,
                    Collections.<String, Integer>emptyMap(),
                    warnings
            );
        }

        public PreRankingAnnotationSummary(String status,
                                           int selectedCount,
                                           int processedCount,
                                           int successCount,
                                           int failedCount,
                                           int cacheHitCount,
                                           int skippedByCapCount,
                                           int annotationRowsApplied,
                                           long elapsedMillis,
                                           boolean failOpenUsed,
                                           String providerName,
                                           String providerVersion,
                                           String cacheVersion,
                                           String optionsHash,
                                           Map<String, Integer> skippedByReason,
                                           List<String> warnings) {
            this(
                    status,
                    selectedCount,
                    processedCount,
                    successCount,
                    failedCount,
                    cacheHitCount,
                    skippedByCapCount,
                    annotationRowsApplied,
                    elapsedMillis,
                    failOpenUsed,
                    providerName,
                    providerVersion,
                    cacheVersion,
                    optionsHash,
                    skippedByReason,
                    0,
                    0,
                    0,
                    0,
                    Math.max(0, selectedCount - successCount),
                    successCount,
                    Math.max(0, selectedCount + skippedByCapCount),
                    Math.max(0, selectedCount + skippedByCapCount),
                    Math.max(0, selectedCount + skippedByCapCount),
                    0,
                    0,
                    warnings
            );
        }

        public PreRankingAnnotationSummary(String status,
                                           int selectedCount,
                                           int processedCount,
                                           int successCount,
                                           int failedCount,
                                           int cacheHitCount,
                                           int skippedByCapCount,
                                           int annotationRowsApplied,
                                           long elapsedMillis,
                                           boolean failOpenUsed,
                                           String providerName,
                                           String providerVersion,
                                           String cacheVersion,
                                           String optionsHash,
                                           Map<String, Integer> skippedByReason,
                                           int cacheMissCount,
                                           int providerBatchCount,
                                           int providerTimeoutCount,
                                           int providerErrorCount,
                                           int unavailableCount,
                                           int annotatedAvailableCount,
                                           List<String> warnings) {
            this(
                    status,
                    selectedCount,
                    processedCount,
                    successCount,
                    failedCount,
                    cacheHitCount,
                    skippedByCapCount,
                    annotationRowsApplied,
                    elapsedMillis,
                    failOpenUsed,
                    providerName,
                    providerVersion,
                    cacheVersion,
                    optionsHash,
                    skippedByReason,
                    cacheMissCount,
                    providerBatchCount,
                    providerTimeoutCount,
                    providerErrorCount,
                    unavailableCount,
                    annotatedAvailableCount,
                    Math.max(0, selectedCount + skippedByCapCount),
                    Math.max(0, selectedCount + skippedByCapCount),
                    Math.max(0, selectedCount + skippedByCapCount),
                    0,
                    0,
                    warnings
            );
        }

        public PreRankingAnnotationSummary(String status,
                                           int selectedCount,
                                           int processedCount,
                                           int successCount,
                                           int failedCount,
                                           int cacheHitCount,
                                           int skippedByCapCount,
                                           int annotationRowsApplied,
                                           long elapsedMillis,
                                           boolean failOpenUsed,
                                           String providerName,
                                           String providerVersion,
                                           String cacheVersion,
                                           String optionsHash,
                                           Map<String, Integer> skippedByReason,
                                           int cacheMissCount,
                                           int providerBatchCount,
                                           int providerTimeoutCount,
                                           int providerErrorCount,
                                           int unavailableCount,
                                           int annotatedAvailableCount,
                                           int totalCandidateCount,
                                           int eligibleCandidateCount,
                                           int preRankingCandidateCap,
                                           int alreadyAnnotatedCount,
                                           int hardFilteredCount,
                                           List<String> warnings) {
            this.status = status;
            this.totalCandidateCount = Math.max(0, totalCandidateCount);
            this.eligibleCandidateCount = Math.max(0, eligibleCandidateCount);
            this.selectedCount = selectedCount;
            this.processedCount = processedCount;
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.cacheHitCount = cacheHitCount;
            this.skippedByCapCount = skippedByCapCount;
            this.annotationRowsApplied = annotationRowsApplied;
            this.elapsedMillis = elapsedMillis;
            this.failOpenUsed = failOpenUsed;
            this.providerName = providerName;
            this.providerVersion = providerVersion;
            this.cacheVersion = cacheVersion;
            this.optionsHash = optionsHash;
            this.skippedByReason = skippedByReason == null || skippedByReason.isEmpty()
                    ? Collections.<String, Integer>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(skippedByReason));
            this.cacheMissCount = cacheMissCount;
            this.providerBatchCount = providerBatchCount;
            this.providerTimeoutCount = providerTimeoutCount;
            this.providerErrorCount = providerErrorCount;
            this.unavailableCount = unavailableCount;
            this.annotatedAvailableCount = annotatedAvailableCount;
            this.preRankingCandidateCap = Math.max(0, preRankingCandidateCap);
            this.alreadyAnnotatedCount = Math.max(0, alreadyAnnotatedCount);
            this.hardFilteredCount = Math.max(0, hardFilteredCount);
            this.warnings = warnings == null || warnings.isEmpty()
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(warnings));
        }

        public static PreRankingAnnotationSummary skipped(String status, String warning) {
            return new PreRankingAnnotationSummary(
                    status,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    warning == null ? Collections.emptyList() : Collections.singletonList(warning)
            );
        }

        public boolean isRankingRefreshRecommended() {
            return successCount > 0;
        }

        public String eventMessage() {
            return "Pre-ranking transcript annotation " + status
                    + ": totalCandidates=" + totalCandidateCount
                    + ", inputVariantCount=" + totalCandidateCount
                    + ", normalizedVariantCount=" + totalCandidateCount
                    + ", hardFilteredCount=" + hardFilteredCount
                    + ", eligibleCandidates=" + eligibleCandidateCount
                    + ", eligibleBeforeAnnotationCount=" + eligibleCandidateCount
                    + ", alreadyAnnotatedCount=" + alreadyAnnotatedCount
                    + ", preRankingCandidateCap=" + preRankingCandidateCap
                    + ", selected=" + selectedCount
                    + ", selectedForVepCount=" + selectedCount
                    + ", processed=" + processedCount
                    + ", providerVariantCount=" + processedCount
                    + ", success=" + successCount
                    + ", failed=" + failedCount
                    + ", cacheHit=" + cacheHitCount
                    + ", cacheMiss=" + cacheMissCount
                    + ", batches=" + providerBatchCount
                    + ", timeouts=" + providerTimeoutCount
                    + ", providerErrors=" + providerErrorCount
                    + ", unavailable=" + unavailableCount
                    + ", annotatedAvailable=" + annotatedAvailableCount
                    + ", skippedByCap=" + skippedByCapCount
                    + ", annotationRows=" + annotationRowsApplied
                    + ", failOpenUsed=" + failOpenUsed
                    + ", provider=" + providerName
                    + ", providerVersion=" + providerVersion
                    + ", cacheVersion=" + cacheVersion
                    + ", optionsHash=" + optionsHash
                    + ", elapsedMs=" + elapsedMillis
                    + (warnings.isEmpty() ? "" : ", warnings=" + warnings.size());
        }

        public String getStatus() {
            return status;
        }

        public int getTotalCandidateCount() {
            return totalCandidateCount;
        }

        public int getEligibleCandidateCount() {
            return eligibleCandidateCount;
        }

        public int getSelectedCount() {
            return selectedCount;
        }

        public int getProcessedCount() {
            return processedCount;
        }

        public int getSuccessCount() {
            return successCount;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public int getCacheHitCount() {
            return cacheHitCount;
        }

        public int getCacheMissCount() {
            return cacheMissCount;
        }

        public int getSkippedByCapCount() {
            return skippedByCapCount;
        }

        public int getAnnotationRowsApplied() {
            return annotationRowsApplied;
        }

        public long getElapsedMillis() {
            return elapsedMillis;
        }

        public boolean isFailOpenUsed() {
            return failOpenUsed;
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

        public Map<String, Integer> getSkippedByReason() {
            return skippedByReason;
        }

        public int getProviderBatchCount() {
            return providerBatchCount;
        }

        public int getProviderTimeoutCount() {
            return providerTimeoutCount;
        }

        public int getProviderErrorCount() {
            return providerErrorCount;
        }

        public int getUnavailableCount() {
            return unavailableCount;
        }

        public int getAnnotatedAvailableCount() {
            return annotatedAvailableCount;
        }

        public int getPreRankingCandidateCap() {
            return preRankingCandidateCap;
        }

        public int getAlreadyAnnotatedCount() {
            return alreadyAnnotatedCount;
        }

        public int getHardFilteredCount() {
            return hardFilteredCount;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }
}
