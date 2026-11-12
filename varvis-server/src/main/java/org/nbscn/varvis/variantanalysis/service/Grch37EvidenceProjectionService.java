package org.nbscn.varvis.variantanalysis.service;

import htsjdk.samtools.liftover.LiftOver;
import htsjdk.samtools.reference.ReferenceSequence;
import htsjdk.samtools.reference.ReferenceSequenceFile;
import htsjdk.samtools.reference.ReferenceSequenceFileFactory;
import htsjdk.samtools.util.Interval;
import htsjdk.samtools.util.SequenceUtil;
import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;
import org.nbscn.varvis.variantanalysis.provider.VariantEvidenceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Projects retained GRCh37 candidates to GRCh38 solely for querying GRCh38-only evidence resources.
 * The original analysis coordinates and VCF are never modified.
 */
@Service
public class Grch37EvidenceProjectionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(Grch37EvidenceProjectionService.class);
    private static final String PROJECTION_METHOD = "HTSJDK_LIFTOVER";
    private static final String PROJECTION_METHOD_VERSION = "htsjdk-liftover-v1";
    private static final Set<String> PROJECTED_PROVIDER_SOURCES = Collections.unmodifiableSet(
            new LinkedHashSet<String>(Arrays.asList("GNOMAD", "CADD", "SPLICEAI")));

    private final VariantAnalysisProperties properties;
    private final List<VariantEvidenceProvider> providers;

    public Grch37EvidenceProjectionService(VariantAnalysisProperties properties,
                                           List<VariantEvidenceProvider> providers) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
        this.providers = providers == null
                ? Collections.<VariantEvidenceProvider>emptyList()
                : new ArrayList<VariantEvidenceProvider>(providers);
    }

    public void apply(List<VariantInterpretation> interpretations, AnalysisContext context) {
        if (context == null || context.getGenomeAssembly() != GenomeAssembly.GRCh37
                || interpretations == null || interpretations.isEmpty()
                || !properties.getLiftOver().isEvidenceProjectionEnabled()) {
            return;
        }
        List<VariantInterpretation> candidates = eligibleCandidates(interpretations);
        if (candidates.isEmpty()) {
            return;
        }
        int cap = properties.getLiftOver().normalizedEvidenceProjectionMaxCandidates();
        candidates = applyAlleleGroupCap(candidates, cap);

        Path chain = properties.getLiftOver().getHg19ToHg38Chain();
        Path reference = properties.getNormalization().getGrch38Reference();
        if (!regularFile(chain) || !regularFile(reference) || !regularFile(fastaIndex(reference))) {
            String reason = !regularFile(chain)
                    ? "hg19-to-hg38 chain file is unavailable"
                    : "indexed GRCh38 reference FASTA is unavailable";
            for (VariantInterpretation interpretation : candidates) {
                replaceForAllProjectedSources(interpretation, "LIFTOVER_UNAVAILABLE", reason);
            }
            LOGGER.warn("GRCh37 evidence projection was not run: {}", reason);
            return;
        }

        Map<VariantInterpretation, ProjectionResult> projections = project(candidates, chain, reference);
        Map<NormalizedVariantKey, List<VariantInterpretation>> sourcesByProjectedKey =
                new LinkedHashMap<NormalizedVariantKey, List<VariantInterpretation>>();
        Set<NormalizedVariantKey> ambiguousProjectedKeys = new LinkedHashSet<NormalizedVariantKey>();
        List<VariantInterpretation> projectedVariants = new ArrayList<VariantInterpretation>();
        for (Map.Entry<VariantInterpretation, ProjectionResult> entry : projections.entrySet()) {
            VariantInterpretation source = entry.getKey();
            ProjectionResult result = entry.getValue();
            if (!result.isSuccess()) {
                replaceForAllProjectedSources(source, result.status, result.message);
                continue;
            }
            if (ambiguousProjectedKeys.contains(result.projectedKey)) {
                replaceForAllProjectedSources(source,
                        "LIFTOVER_AMBIGUOUS",
                        "Multiple distinct GRCh37 alleles projected to the same GRCh38 allele"
                );
                continue;
            }
            List<VariantInterpretation> matchingSources = sourcesByProjectedKey.get(result.projectedKey);
            if (matchingSources == null) {
                matchingSources = new ArrayList<VariantInterpretation>();
                matchingSources.add(source);
                sourcesByProjectedKey.put(result.projectedKey, matchingSources);
                projectedVariants.add(projectedVariant(source, result.projectedKey));
                continue;
            }
            if (sameSourceAllele(matchingSources.get(0), source)) {
                matchingSources.add(source);
                continue;
            }
            sourcesByProjectedKey.remove(result.projectedKey);
            ambiguousProjectedKeys.add(result.projectedKey);
            removeProjectedVariant(projectedVariants, result.projectedKey);
            for (VariantInterpretation duplicate : matchingSources) {
                replaceForAllProjectedSources(duplicate,
                        "LIFTOVER_AMBIGUOUS",
                        "Multiple distinct GRCh37 alleles projected to the same GRCh38 allele"
                );
            }
            replaceForAllProjectedSources(source,
                    "LIFTOVER_AMBIGUOUS",
                    "Multiple distinct GRCh37 alleles projected to the same GRCh38 allele"
            );
        }
        if (projectedVariants.isEmpty()) {
            return;
        }

        AnalysisContext projectedContext = new AnalysisContext(
                context.getAnalysisId(), GenomeAssembly.GRCh38, context.getAnalysisMode(),
                context.getPreflightSummary(), context.getPhenotypeSummary());
        for (VariantEvidenceProvider provider : projectedProviders()) {
            applyProvider(provider, projectedVariants, projectedContext, sourcesByProjectedKey, projections);
        }
        int projectedSourceCount = sourcesByProjectedKey.values().stream()
                .mapToInt(value -> value.size())
                .sum();
        LOGGER.info("GRCh37 candidate evidence projection completed: eligible={}, projected={}, failures={}",
                candidates.size(), projectedSourceCount, candidates.size() - projectedSourceCount);
    }

    private List<VariantInterpretation> eligibleCandidates(List<VariantInterpretation> interpretations) {
        List<VariantInterpretation> result = new ArrayList<VariantInterpretation>();
        for (VariantInterpretation interpretation : interpretations) {
            if (interpretation == null || interpretation.getObservation() == null
                    || interpretation.getObservation().getKey() == null
                    || interpretation.isSummaryOnly() || interpretation.isExpensiveAnnotationSkipped()) {
                continue;
            }
            result.add(interpretation);
        }
        return result;
    }

    private List<VariantInterpretation> applyAlleleGroupCap(
            List<VariantInterpretation> candidates,
            int cap) {
        if (cap <= 0 || candidates.isEmpty()) {
            return candidates;
        }
        Map<NormalizedVariantKey, List<VariantInterpretation>> candidatesByAllele =
                new LinkedHashMap<NormalizedVariantKey, List<VariantInterpretation>>();
        for (VariantInterpretation candidate : candidates) {
            candidatesByAllele.computeIfAbsent(
                    candidate.getObservation().getKey(),
                    ignored -> new ArrayList<VariantInterpretation>()
            ).add(candidate);
        }
        if (candidatesByAllele.size() <= cap) {
            return candidates;
        }
        List<VariantInterpretation> selected = new ArrayList<VariantInterpretation>();
        int alleleIndex = 0;
        for (List<VariantInterpretation> alleleCandidates : candidatesByAllele.values()) {
            if (alleleIndex++ < cap) {
                selected.addAll(alleleCandidates);
                continue;
            }
            for (VariantInterpretation interpretation : alleleCandidates) {
                replaceForAllProjectedSources(interpretation,
                        "LIFTOVER_SKIPPED_BY_CAP",
                        "GRCh37-to-GRCh38 evidence projection was skipped by the configured allele cap"
                );
            }
        }
        return selected;
    }

    private Map<VariantInterpretation, ProjectionResult> project(List<VariantInterpretation> candidates,
                                                                   Path chain,
                                                                   Path reference) {
        Map<VariantInterpretation, ProjectionResult> result =
                new LinkedHashMap<VariantInterpretation, ProjectionResult>();
        LiftOver liftOver;
        try {
            liftOver = new LiftOver(chain.toFile());
            liftOver.setLiftOverMinMatch(properties.getLiftOver().getMinMatch());
        } catch (RuntimeException ex) {
            for (VariantInterpretation candidate : candidates) {
                result.put(candidate, ProjectionResult.failure(
                        "LIFTOVER_UNAVAILABLE", "Unable to read hg19-to-hg38 chain: " + safeMessage(ex)));
            }
            return result;
        }
        try (ReferenceSequenceFile referenceFile = ReferenceSequenceFileFactory.getReferenceSequenceFile(reference)) {
            for (VariantInterpretation candidate : candidates) {
                result.put(candidate, projectOne(candidate.getObservation().getKey(), liftOver, referenceFile));
            }
        } catch (IOException | RuntimeException ex) {
            for (VariantInterpretation candidate : candidates) {
                if (!result.containsKey(candidate)) {
                    result.put(candidate, ProjectionResult.failure(
                            "LIFTOVER_UNAVAILABLE", "Unable to read GRCh38 reference: " + safeMessage(ex)));
                }
            }
        }
        return result;
    }

    private ProjectionResult projectOne(NormalizedVariantKey source,
                                        LiftOver liftOver,
                                        ReferenceSequenceFile referenceFile) {
        int end = source.getPosition() + source.getReference().length() - 1;
        Interval lifted = liftOver.liftOver(new Interval(chainContig(source.getChromosome()), source.getPosition(), end));
        if (lifted == null || lifted.length() != source.getReference().length()) {
            return ProjectionResult.failure("LIFTOVER_UNMAPPED",
                    "GRCh37 candidate could not be mapped as a complete allele to GRCh38");
        }
        String projectedReference = source.getReference();
        String projectedAlternate = source.getAlternate();
        if (lifted.isNegativeStrand()) {
            projectedReference = SequenceUtil.reverseComplement(projectedReference);
            projectedAlternate = SequenceUtil.reverseComplement(projectedAlternate);
        }
        String targetContig = lifted.getContig();
        String actualReference;
        try {
            ReferenceSequence sequence = referenceFile.getSubsequenceAt(targetContig, lifted.getStart(), lifted.getEnd());
            actualReference = new String(sequence.getBases()).toUpperCase(Locale.ROOT);
        } catch (RuntimeException ex) {
            return ProjectionResult.failure("LIFTOVER_UNMAPPED",
                    "Projected GRCh38 contig is not available in the configured reference");
        }
        if (!projectedReference.equals(actualReference)) {
            return ProjectionResult.failure("LIFTOVER_REF_MISMATCH",
                    "Projected GRCh38 REF does not match the configured reference FASTA");
        }
        return ProjectionResult.success(NormalizedVariantKey.canonical(
                GenomeAssembly.GRCh38,
                targetContig,
                lifted.getStart(),
                projectedReference,
                projectedAlternate
        ));
    }

    private VariantInterpretation projectedVariant(VariantInterpretation source, NormalizedVariantKey projectedKey) {
        VariantQcObservation original = source.getObservation();
        VariantQcObservation projectedObservation = new VariantQcObservation(
                projectedKey,
                original.getSampleId(),
                original.getGenotype(),
                original.getDepth(),
                original.getGenotypeQuality(),
                original.getAlleleDepths(),
                original.getAlleleBalance(),
                original.getFilterStatus(),
                original.getVariantType(),
                original.getOriginalRecord()
        );
        VariantInterpretation projected = new VariantInterpretation(
                source.getVariantId(),
                source.getCandidateIdentity().withCanonicalVariantId(projectedKey.canonicalId()),
                projectedObservation,
                source.getConsequences(),
                Collections.<EvidenceItem>emptyList()
        );
        projected.applyTranscriptAnnotations(source.getTranscriptAnnotations());
        projected.copyLargeFileClassificationFrom(source);
        return projected;
    }

    private void removeProjectedVariant(List<VariantInterpretation> projectedVariants,
                                        NormalizedVariantKey projectedKey) {
        for (int index = projectedVariants.size() - 1; index >= 0; index--) {
            VariantInterpretation candidate = projectedVariants.get(index);
            if (candidate.getObservation() != null
                    && projectedKey.equals(candidate.getObservation().getKey())) {
                projectedVariants.remove(index);
            }
        }
    }

    private boolean sameSourceAllele(VariantInterpretation left, VariantInterpretation right) {
        return left != null
                && right != null
                && left.getObservation() != null
                && right.getObservation() != null
                && left.getObservation().getKey() != null
                && left.getObservation().getKey().equals(right.getObservation().getKey());
    }

    private void applyProvider(VariantEvidenceProvider provider,
                               List<VariantInterpretation> projectedVariants,
                               AnalysisContext projectedContext,
                               Map<NormalizedVariantKey, List<VariantInterpretation>> sourcesByProjectedKey,
                               Map<VariantInterpretation, ProjectionResult> projections) {
        Map<NormalizedVariantKey, List<EvidenceItem>> evidenceByProjected;
        try {
            evidenceByProjected = provider.annotate(projectedVariants, projectedContext);
        } catch (RuntimeException ex) {
            for (List<VariantInterpretation> sources : sourcesByProjectedKey.values()) {
                for (VariantInterpretation source : sources) {
                    replaceEvidence(source, provider.source(), unavailable(provider.source(),
                            "LIFTOVER_PROVIDER_ERROR", provider.source()
                                    + " GRCh38 query failed: " + safeMessage(ex)));
                }
            }
            LOGGER.warn("GRCh37 projected {} lookup failed: {}", provider.source(), safeMessage(ex));
            return;
        }
        for (Map.Entry<NormalizedVariantKey, List<VariantInterpretation>> entry
                : sourcesByProjectedKey.entrySet()) {
            List<EvidenceItem> evidence = evidenceByProjected.get(entry.getKey());
            EvidenceItem selected = evidence == null || evidence.isEmpty()
                    ? unavailable(provider.source(), "LIFTOVER_PROVIDER_NO_RESULT",
                    provider.source() + " returned no GRCh38 evidence for the projected candidate")
                    : evidence.get(0);
            for (VariantInterpretation source : entry.getValue()) {
                ProjectionResult projection = projections.get(source);
                replaceEvidence(
                        source,
                        provider.source(),
                        withProjectionProvenance(
                                selected,
                                source.getObservation().getKey(),
                                projection.projectedKey
                        )
                );
            }
        }
    }

    private List<VariantEvidenceProvider> projectedProviders() {
        List<VariantEvidenceProvider> result = new ArrayList<VariantEvidenceProvider>();
        for (VariantEvidenceProvider provider : providers) {
            if (provider != null && PROJECTED_PROVIDER_SOURCES.contains(provider.source())) {
                result.add(provider);
            }
        }
        return result;
    }

    private void replaceForAllProjectedSources(VariantInterpretation interpretation,
                                               String status,
                                               String summary) {
        for (String source : PROJECTED_PROVIDER_SOURCES) {
            replaceEvidence(interpretation, source, unavailable(source, status, summary));
        }
    }

    private void replaceEvidence(VariantInterpretation interpretation, String source, EvidenceItem item) {
        interpretation.replaceEvidence(source, item.withVariantContext(interpretation.getObservation().getKey()));
    }

    private EvidenceItem withProjectionProvenance(EvidenceItem item,
                                                   NormalizedVariantKey source,
                                                   NormalizedVariantKey projected) {
        Map<String, Object> details = new LinkedHashMap<String, Object>(item.getDetails());
        details.put("coordinateProjection", "GRCH37_TO_GRCH38_VERIFIED");
        details.put("projectionMethod", PROJECTION_METHOD);
        details.put("projectionMethodVersion", PROJECTION_METHOD_VERSION);
        details.put("projectionStatus", "VERIFIED");
        details.put("requestedAssembly", GenomeAssembly.GRCh37.name());
        details.put("inputAssembly", GenomeAssembly.GRCh37.name());
        details.put("queryAssembly", GenomeAssembly.GRCh38.name());
        details.put("evidenceAssembly", GenomeAssembly.GRCh38.name());
        details.put("configuredResourceAssembly", GenomeAssembly.GRCh38.name());
        details.put("sourceVariant", source.canonicalId());
        details.put("projectedVariant", projected.canonicalId());
        details.put("projectionReferenceValidated", Boolean.TRUE);
        return item.withPresentation(item.getLabel(), item.getSummary(), details);
    }

    private EvidenceItem unavailable(String source, String status, String summary) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("status", status);
        details.put("coordinateProjection", "GRCH37_TO_GRCH38");
        details.put("projectionMethod", PROJECTION_METHOD);
        details.put("projectionMethodVersion", PROJECTION_METHOD_VERSION);
        details.put("projectionStatus", status);
        details.put("requestedAssembly", GenomeAssembly.GRCh37.name());
        details.put("inputAssembly", GenomeAssembly.GRCh37.name());
        details.put("queryAssembly", GenomeAssembly.GRCh38.name());
        details.put("evidenceAssembly", GenomeAssembly.GRCh38.name());
        details.put("interpretation", "Projection failure is not a no-record or low-risk result.");
        return new EvidenceItem(source, "liftOver", "COMPUTATIONAL", "UNAVAILABLE", null,
                status, summary, details);
    }

    private String chainContig(String chromosome) {
        String normalized = CoordinateUtils.normalizeChromosome(chromosome);
        return "MT".equals(normalized) ? "chrM" : "chr" + normalized;
    }

    private boolean regularFile(Path path) {
        return path != null && Files.isRegularFile(path);
    }

    private Path fastaIndex(Path fasta) {
        return fasta == null ? null : fasta.resolveSibling(fasta.getFileName() + ".fai");
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.trim().isEmpty() ? ex.getClass().getSimpleName() : message.trim();
    }

    private static final class ProjectionResult {
        private final NormalizedVariantKey projectedKey;
        private final String status;
        private final String message;

        private ProjectionResult(NormalizedVariantKey projectedKey, String status, String message) {
            this.projectedKey = projectedKey;
            this.status = status;
            this.message = message;
        }

        private static ProjectionResult success(NormalizedVariantKey key) {
            return new ProjectionResult(key, "AVAILABLE", null);
        }

        private static ProjectionResult failure(String status, String message) {
            return new ProjectionResult(null, status, message);
        }

        private boolean isSuccess() {
            return projectedKey != null;
        }
    }
}
