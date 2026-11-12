package org.nbscn.varvis.variantanalysis.provider;

import org.nbscn.varvis.utils.CoordinateUtils;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

final class SpecialistProviderSupport {

    private SpecialistProviderSupport() {
    }

    static Map<NormalizedVariantKey, List<EvidenceItem>> single(
            NormalizedVariantKey key,
            EvidenceItem evidence) {
        return Collections.singletonMap(key, Collections.singletonList(evidence));
    }

    static List<EvidenceItem> single(EvidenceItem evidence) {
        return Collections.singletonList(evidence);
    }

    static boolean isMissense(VariantInterpretation variant) {
        return hasConsequence(variant, "missense");
    }

    static boolean isSpliceRelevant(VariantInterpretation variant) {
        return hasConsequence(
                variant,
                "splice",
                "intron",
                "synonymous",
                "stop_gained",
                "frameshift"
        );
    }

    static boolean hasConsequence(VariantInterpretation variant, String... fragments) {
        for (TranscriptConsequence consequence : variant.getConsequences()) {
            if (consequence.getConsequence() == null) {
                continue;
            }
            String value = consequence.getConsequence().toLowerCase(Locale.ROOT);
            for (String fragment : fragments) {
                if (value.contains(fragment)) {
                    return true;
                }
            }
        }
        return false;
    }

    static List<String> tabixRegions(NormalizedVariantKey key) {
        String chromosome = key.getChromosome();
        List<String> result = new ArrayList<String>();
        result.add(region(chromosome, key.getPosition()));
        String prefixed = "MT".equals(chromosome) ? "chrM" : "chr" + chromosome;
        if (!prefixed.equals(chromosome)) {
            result.add(region(prefixed, key.getPosition()));
        }
        return result;
    }

    static boolean matchesCoordinates(String chromosome,
                                      String position,
                                      String reference,
                                      String alternate,
                                      NormalizedVariantKey key) {
        try {
            return key.getChromosome().equals(CoordinateUtils.normalizeChromosome(chromosome))
                    && key.getPosition() == Integer.parseInt(position)
                    && key.getReference().equalsIgnoreCase(reference)
                    && key.getAlternate().equalsIgnoreCase(alternate);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    static Double parseDouble(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    static boolean isTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof TimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("timed out") || normalized.contains("timeout")) {
                    return true;
                }
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }

    private static String region(String chromosome, int position) {
        return chromosome + ":" + position + "-" + position;
    }
}
