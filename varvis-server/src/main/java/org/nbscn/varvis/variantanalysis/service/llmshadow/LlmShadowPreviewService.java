package org.nbscn.varvis.variantanalysis.service.llmshadow;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationFilter;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretationView;
import org.nbscn.varvis.variantanalysis.dto.LlmShadowPreviewResponse;
import org.nbscn.varvis.variantanalysis.dto.LlmShadowPreviewResponse.Provenance;
import org.nbscn.varvis.variantanalysis.dto.LlmShadowPreviewResponse.Status;
import org.nbscn.varvis.variantanalysis.persistence.VariantAnalysisJobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

@Service
public class LlmShadowPreviewService {

    private final VariantAnalysisProperties properties;
    private final VariantAnalysisJobRepository repository;
    private final List<LlmShadowProvider> providers;
    private final ObjectProvider<LlmShadowProvider> providerSource;
    private final LlmShadowInputAssembler assembler;

    @Autowired
    public LlmShadowPreviewService(VariantAnalysisProperties properties,
                                   VariantAnalysisJobRepository repository,
                                   ObjectProvider<LlmShadowProvider> providers) {
        this.properties = properties;
        this.repository = repository;
        this.providers = Collections.emptyList();
        this.providerSource = providers;
        this.assembler = new LlmShadowInputAssembler();
    }

    LlmShadowPreviewService(VariantAnalysisProperties properties,
                            VariantAnalysisJobRepository repository,
                            List<LlmShadowProvider> providers) {
        this(properties, repository, providers, new LlmShadowInputAssembler());
    }

    LlmShadowPreviewService(VariantAnalysisProperties properties,
                            VariantAnalysisJobRepository repository,
                            List<LlmShadowProvider> providers,
                            LlmShadowInputAssembler assembler) {
        this.properties = properties;
        this.repository = repository;
        this.providers = providers == null ? Collections.<LlmShadowProvider>emptyList() : providers;
        this.providerSource = null;
        this.assembler = assembler;
    }

    public LlmShadowPreviewResponse preview(String analysisId) {
        VariantAnalysisProperties.LlmShadow config = properties.getLlmShadow();
        String schemaVersion = config.normalizedPromptSchemaVersion();
        if (!config.isEnabled()) {
            return response(Status.DISABLED, null, "NONE", schemaVersion, 0, null);
        }

        String configuredProvider = normalizedProvider(config.getProvider());
        if ("NONE".equals(configuredProvider)) {
            return response(Status.PROVIDER_UNAVAILABLE, null, "NONE", schemaVersion, 0, null);
        }

        LlmShadowProvider provider;
        try {
            provider = findProvider(configuredProvider);
            if (provider == null || provider.externalTransmission()) {
                return response(Status.PROVIDER_UNAVAILABLE, null, configuredProvider, schemaVersion, 0, null);
            }
        } catch (RuntimeException ignored) {
            return response(Status.PROVIDER_UNAVAILABLE, null, configuredProvider, schemaVersion, 0, null);
        }

        if (!repository.findById(analysisId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Variant analysis not found");
        }
        int candidateLimit = config.normalizedCandidateLimit();
        List<VariantInterpretation> variants = repository.findInterpretationSummaries(
                analysisId,
                0,
                candidateLimit,
                VariantInterpretationView.CANDIDATES,
                VariantInterpretationFilter.empty());
        if (variants == null) {
            variants = Collections.emptyList();
        } else if (variants.size() > candidateLimit) {
            variants = variants.subList(0, candidateLimit);
        }
        LlmShadowInput input = assembler.assemble(schemaVersion, variants);
        String fingerprint = fingerprint(input);
        try {
            String output = boundedPlainText(provider.preview(input), config.normalizedMaxOutputCharacters());
            if (output == null) {
                return response(Status.PROVIDER_UNAVAILABLE, null, configuredProvider, schemaVersion,
                        input.getCandidates().size(), fingerprint);
            }
            return response(Status.PREVIEW_READY, output, configuredProvider, schemaVersion,
                    input.getCandidates().size(), fingerprint);
        } catch (RuntimeException ignored) {
            return response(Status.PROVIDER_UNAVAILABLE, null, configuredProvider, schemaVersion,
                    input.getCandidates().size(), fingerprint);
        }
    }

    private LlmShadowProvider findProvider(String configuredProvider) {
        Iterator<LlmShadowProvider> candidates = providerSource == null
                ? providers.iterator() : providerSource.orderedStream().iterator();
        LlmShadowProvider match = null;
        while (candidates.hasNext()) {
            LlmShadowProvider provider = candidates.next();
            if (provider != null && configuredProvider.equals(normalizedProvider(provider.providerName()))) {
                if (match != null) {
                    return null;
                }
                match = provider;
            }
        }
        return match;
    }

    private LlmShadowPreviewResponse response(Status status, String text, String provider,
                                              String schemaVersion, int candidateCount,
                                              String fingerprint) {
        return new LlmShadowPreviewResponse(status, text,
                new Provenance(provider, schemaVersion, candidateCount, fingerprint));
    }

    private String normalizedProvider(String provider) {
        if (provider == null) return "NONE";
        String value = provider.trim().toUpperCase(Locale.ROOT);
        return value.length() >= 1 && value.length() <= 40 && value.matches("[A-Z0-9_.-]+")
                ? value : "NONE";
    }

    private String boundedPlainText(String output, int limit) {
        if (output == null) return null;
        StringBuilder safe = new StringBuilder(Math.min(output.length(), limit));
        for (int index = 0; index < output.length() && safe.length() < limit;) {
            int value = output.codePointAt(index);
            index += Character.charCount(value);
            if (value == '<' || value == '>') continue;
            int width = Character.charCount(value);
            if (safe.length() + width > limit) break;
            if (value == '\r' || value == '\n' || value == '\t' || !Character.isISOControl(value)) {
                safe.appendCodePoint(value);
            }
        }
        String normalized = safe.toString().trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String fingerprint(LlmShadowInput input) {
        StringBuilder canonical = new StringBuilder(input.getSchemaVersion());
        for (LlmShadowInput.Candidate candidate : input.getCandidates()) {
            canonical.append('|').append(candidate.getCandidateOrdinal())
                    .append('|').append(candidate.getCompleteness())
                    .append('|').append(candidate.getAvailabilityMeaning());
            candidate.getComponents().stream()
                    .sorted(Comparator.comparing((LlmShadowInput.Component value) -> value.getKey()))
                    .forEach(component -> canonical.append('|').append(component.getKey())
                            .append('=').append(Double.toHexString(component.getScore()))
                            .append(':').append(component.getDirection()));
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
