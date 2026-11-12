package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AlphaGenomeProvider {

    private final VariantAnalysisProperties properties;
    private final AlphaGenomeClient client;
    private final ConcurrentMap<NormalizedVariantKey, EvidenceItem> cache =
            new ConcurrentHashMap<NormalizedVariantKey, EvidenceItem>();

    public AlphaGenomeProvider(VariantAnalysisProperties properties, AlphaGenomeClient client) {
        this.properties = properties;
        this.client = client;
    }

    public String source() {
        return "ALPHAGENOME";
    }

    public String version() {
        return properties.getAlphaGenome().getVersion();
    }

    public boolean isEligible(VariantInterpretation variant) {
        for (org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence consequence
                : variant.getConsequences()) {
            if (consequence.getConsequence() == null) {
                continue;
            }
            String value = consequence.getConsequence().toLowerCase(Locale.ROOT);
            if (value.contains("splice")
                    || value.contains("intron")
                    || value.contains("intergenic")
                    || value.contains("upstream")
                    || value.contains("downstream")
                    || value.contains("utr")
                    || value.contains("regulatory")
                    || value.contains("non_coding")) {
                return true;
            }
        }
        return false;
    }

    public EvidenceItem enhance(VariantInterpretation variant) {
        if (!isEligible(variant)) {
            return EvidenceItem.notApplicable(
                    source(), version(), "MECHANISTIC",
                    "AlphaGenome is queried only for noncoding or splice-related candidate variants"
            );
        }
        if (!properties.getAlphaGenome().isEnabled()) {
            return EvidenceItem.unavailable(
                    source(), version(), "MECHANISTIC", "AlphaGenome remote lookup is disabled"
            );
        }
        NormalizedVariantKey key = variant.getObservation().getKey();
        EvidenceItem cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Exception failure = null;
        int maxAttempts = Math.max(1, properties.getAlphaGenome().getMaxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Map<String, Object> details = new LinkedHashMap<String, Object>(client.score(key));
                details.put("privacyScope", "De-identified candidate coordinate and alleles only");
                details.put("attemptCount", attempt);
                EvidenceItem evidence = new EvidenceItem(
                        source(),
                        version(),
                        "MECHANISTIC",
                        "APPLICABLE",
                        null,
                        "AlphaGenome REF/ALT track summary",
                        "AlphaGenome output is mechanistic support, not a pathogenicity probability or clinical conclusion",
                        details
                );
                cache.putIfAbsent(key, evidence);
                return evidence;
            } catch (IOException | RuntimeException ex) {
                failure = ex;
            }
        }
        return EvidenceItem.unavailable(
                source(),
                version(),
                "MECHANISTIC",
                "AlphaGenome remote lookup failed after " + maxAttempts + " attempt(s): "
                        + SpecialistProviderSupport.rootMessage(failure)
        );
    }

    public boolean hasCaddPrescreenScore(VariantInterpretation variant) {
        for (EvidenceItem evidence : variant.getEvidence()) {
            if ("CADD".equals(evidence.getSource()) && "APPLICABLE".equals(evidence.getApplicability())) {
                return true;
            }
        }
        return false;
    }
}
