package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptAnnotationCacheKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.provider.AnnTranscriptAnnotationProvider;
import org.nbscn.varvis.variantanalysis.provider.VepReadinessReport;
import org.nbscn.varvis.variantanalysis.provider.VepResourceProfile;
import org.nbscn.varvis.variantanalysis.provider.VepResourceProfileResolution;
import org.nbscn.varvis.variantanalysis.provider.VepResourceProfileResolver;
import org.nbscn.varvis.variantanalysis.provider.VepResourceValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
public class TranscriptAnnotationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptAnnotationService.class);

    private final VariantAnalysisProperties properties;
    private final AnnTranscriptAnnotationProvider annProvider;
    private final TranscriptAnnotationCache cache;
    private final VepResourceValidator vepResourceValidator;
    private final VepResourceProfileResolver profileResolver;

    @Autowired
    public TranscriptAnnotationService(VariantAnalysisProperties properties,
                                       AnnTranscriptAnnotationProvider annProvider,
                                       TranscriptAnnotationCache cache,
                                       VepResourceValidator vepResourceValidator) {
        this.properties = properties == null ? new VariantAnalysisProperties() : properties;
        this.annProvider = annProvider;
        this.cache = cache;
        this.vepResourceValidator = vepResourceValidator == null
                ? new VepResourceValidator(this.properties)
                : vepResourceValidator;
        this.profileResolver = new VepResourceProfileResolver(this.properties);
    }

    public TranscriptAnnotationService(VariantAnalysisProperties properties,
                                       AnnTranscriptAnnotationProvider annProvider,
                                       TranscriptAnnotationCache cache) {
        this(
                properties,
                annProvider,
                cache,
                null
        );
    }

    public void annotate(List<VariantInterpretation> variants, AnalysisContext context) {
        if (variants == null || variants.isEmpty()) {
            return;
        }
        for (VariantInterpretation variant : variants) {
            variant.applyTranscriptAnnotations(annotations(variant, context));
        }
    }

    private List<TranscriptConsequence> annotations(VariantInterpretation variant, AnalysisContext context) {
        List<TranscriptConsequence> annAnnotations = annProvider.annotate(variant, context);
        if (hasCompletedAnnotation(annAnnotations)) {
            return annAnnotations;
        }
        return vepPlaceholderOrAnnFallback(annAnnotations, context);
    }

    private boolean hasCompletedAnnotation(List<TranscriptConsequence> annotations) {
        if (annotations == null || annotations.isEmpty()) {
            return false;
        }
        for (TranscriptConsequence annotation : annotations) {
            if (annotation != null && "COMPLETED".equals(annotation.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private List<TranscriptConsequence> vepPlaceholderOrAnnFallback(
            List<TranscriptConsequence> annAnnotations,
            AnalysisContext context) {
        GenomeAssembly assembly = context == null ? null : context.getGenomeAssembly();
        VepReadinessReport readiness = vepResourceValidator.validate(assembly);
        if (!readiness.isEnabled()) {
            return annAnnotations;
        }
        if (!"READY".equals(readiness.getStatus()) || !readiness.isExecutionEnabled()) {
            return Collections.singletonList(TranscriptConsequence.statusPlaceholder(
                    "No ANN transcript annotation was present in the normalized VCF; "
                            + readiness.getExplanation(),
                    "VEP_UNAVAILABLE",
                    "UNAVAILABLE",
                    readiness.getVepVersion(),
                    Instant.now()
            ));
        }
        return Collections.singletonList(TranscriptConsequence.statusPlaceholder(
                "No ANN transcript annotation was present in the normalized VCF; "
                        + "VEP enrichment is queued for ranked candidate variants.",
                "VEP_PENDING",
                "PENDING",
                readiness.getVepVersion(),
                Instant.now()
        ));
    }

    public TranscriptAnnotationCacheKey cacheKey(VariantInterpretation variant) {
        NormalizedVariantKey key = variant == null || variant.getObservation() == null
                ? null
                : variant.getObservation().getKey();
        VariantAnalysisProperties.Vep vep = properties.getVep();
        VepResourceProfile profile = resourceProfile(key);
        return TranscriptAnnotationCacheKey.from(
                key,
                vep.normalizedPreRankingProvider(),
                profile == null ? vep.getVersion() : profile.getVersion(),
                profile == null ? vep.getEnsemblCacheVersion() : profile.getEnsemblCacheVersion(),
                profile == null ? vep.getManeVersion() : profile.getManeVersion(),
                profile == null ? vep.getLofteeVersion() : profile.getLofteeVersion(),
                profile == null
                        ? vep.preRankingOptionsHash()
                        : vep.preRankingOptionsHash(
                        profile.getAssembly().name(),
                        profile.getVersion(),
                        profile.getEnsemblCacheVersion(),
                        profile.getManeVersion(),
                        profile.getLofteeVersion()
                )
        );
    }

    private VepResourceProfile resourceProfile(NormalizedVariantKey key) {
        VepResourceProfileResolution resolution = profileResolver.resolve(assembly(key));
        return resolution.isResolved() ? resolution.getProfile() : null;
    }

    private GenomeAssembly assembly(NormalizedVariantKey key) {
        if (key == null) {
            return null;
        }
        try {
            return GenomeAssembly.require(key.getGenomeAssembly());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public List<TranscriptConsequence> cached(VariantInterpretation variant) {
        if (!properties.getVep().isPreRankingCacheEnabled()) {
            return null;
        }
        try {
            return cache.get(cacheKey(variant));
        } catch (RuntimeException ex) {
            LOGGER.warn("Transcript annotation cache lookup failed; treating as cache miss: {}", safeMessage(ex));
            return null;
        }
    }

    public void cache(VariantInterpretation variant, List<TranscriptConsequence> annotations) {
        if (!properties.getVep().isPreRankingCacheEnabled()) {
            return;
        }
        try {
            cache.put(cacheKey(variant), annotations);
        } catch (RuntimeException ex) {
            LOGGER.warn("Transcript annotation cache write failed; continuing without persisted cache: {}",
                    safeMessage(ex));
        }
    }

    private String safeMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return ex.getClass().getSimpleName();
        }
        return message.replaceAll("(?i)[a-z]:\\\\[^\\s,;]+", "[local-path-redacted]");
    }
}
