package org.nbscn.varvis.variantanalysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInput;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInputStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class PhenotypeInputNormalizer {

    private static final Pattern HPO_ID = Pattern.compile("^HP:\\d{7}$");
    private static final Pattern TOKEN_SPLITTER = Pattern.compile("[,;\\s]+");
    private static final String SOURCE_MANUAL = "MANUAL";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {
            };

    private PhenotypeInputNormalizer() {
    }

    public static PhenotypeAwareSummary summarize(List<String> hpoTerms) {
        return summarize(hpoTerms, Collections.<String>emptyList());
    }

    public static PhenotypeAwareSummary summarize(List<String> hpoTerms, List<String> hpoProvenance) {
        List<String> tokens = tokenize(hpoTerms);
        if (tokens.isEmpty()) {
            return PhenotypeAwareSummary.disabled();
        }

        ProvenanceParseResult provenance = parseProvenance(hpoProvenance);
        Set<String> seenValidTerms = new LinkedHashSet<String>();
        List<String> validTerms = new ArrayList<String>();
        List<String> invalidTerms = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>(provenance.warnings);
        List<PhenotypeInput> inputs = new ArrayList<PhenotypeInput>();
        int duplicateCount = 0;

        warnings.add(PhenotypeAwareSummary.RANKING_NOT_APPLIED_WARNING);

        for (String token : tokens) {
            String normalized = token.toUpperCase(Locale.ROOT);
            PhenotypeProvenance matchedProvenance = provenance.byHpoId.get(normalized);
            if (!HPO_ID.matcher(normalized).matches()) {
                invalidTerms.add(token);
                String warning = "Invalid HPO term format: " + token;
                warnings.add(warning);
                inputs.add(new PhenotypeInput(
                        token,
                        labelFor(matchedProvenance, null),
                        sourceFor(matchedProvenance),
                        PhenotypeInputStatus.INVALID_FORMAT,
                        confidenceFor(matchedProvenance),
                        matchedTermFor(matchedProvenance, token),
                        Collections.singletonList(warning)
                ));
                continue;
            }
            if (!seenValidTerms.add(normalized)) {
                duplicateCount++;
                String warning = "Duplicate HPO term ignored: " + normalized;
                warnings.add(warning);
                inputs.add(new PhenotypeInput(
                        normalized,
                        labelFor(matchedProvenance, normalized),
                        sourceFor(matchedProvenance),
                        PhenotypeInputStatus.DUPLICATE,
                        confidenceFor(matchedProvenance),
                        matchedTermFor(matchedProvenance, token),
                        Collections.singletonList(warning)
                ));
                continue;
            }
            validTerms.add(normalized);
            inputs.add(new PhenotypeInput(
                    normalized,
                    labelFor(matchedProvenance, normalized),
                    sourceFor(matchedProvenance),
                    PhenotypeInputStatus.VALID,
                    confidenceFor(matchedProvenance),
                    matchedTermFor(matchedProvenance, token),
                    Collections.emptyList()
            ));
        }

        return new PhenotypeAwareSummary(
                tokens.size(),
                validTerms.size(),
                invalidTerms.size(),
                duplicateCount,
                validTerms,
                invalidTerms,
                warnings,
                inputs,
                true,
                false
        );
    }

    private static ProvenanceParseResult parseProvenance(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ProvenanceParseResult(
                    Collections.<String, PhenotypeProvenance>emptyMap(),
                    Collections.<String>emptyList()
            );
        }
        Map<String, PhenotypeProvenance> byHpoId =
                new java.util.LinkedHashMap<String, PhenotypeProvenance>();
        List<String> warnings = new ArrayList<String>();
        for (String raw : values) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> map = OBJECT_MAPPER.readValue(raw, MAP_TYPE);
                String hpoId = uppercase(trimToNull(value(map, "hpoId")));
                if (hpoId == null || !HPO_ID.matcher(hpoId).matches()) {
                    warnings.add("Invalid HPO provenance entry ignored.");
                    continue;
                }
                byHpoId.put(hpoId, new PhenotypeProvenance(
                        trimToNull(value(map, "label")),
                        trimToNull(value(map, "source")),
                        trimToNull(value(map, "matchedTerm")),
                        parseConfidence(map.get("confidence"))
                ));
            } catch (Exception ex) {
                warnings.add("Invalid HPO provenance entry ignored.");
            }
        }
        return new ProvenanceParseResult(byHpoId, warnings);
    }

    private static String value(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Double parseConfidence(Object value) {
        if (value == null) {
            return 1.0d;
        }
        try {
            double numeric = value instanceof Number
                    ? ((Number) value).doubleValue()
                    : Double.parseDouble(String.valueOf(value));
            if (Double.isNaN(numeric) || Double.isInfinite(numeric)) {
                return 1.0d;
            }
            return Math.max(0.0d, Math.min(1.0d, numeric));
        } catch (NumberFormatException ex) {
            return 1.0d;
        }
    }

    private static String labelFor(PhenotypeProvenance provenance, String fallback) {
        return provenance == null || provenance.label == null ? fallback : provenance.label;
    }

    private static String sourceFor(PhenotypeProvenance provenance) {
        return provenance == null || provenance.source == null ? SOURCE_MANUAL : provenance.source;
    }

    private static Double confidenceFor(PhenotypeProvenance provenance) {
        return provenance == null || provenance.confidence == null ? 1.0d : provenance.confidence;
    }

    private static String matchedTermFor(PhenotypeProvenance provenance, String fallback) {
        return provenance == null || provenance.matchedTerm == null ? fallback : provenance.matchedTerm;
    }

    private static String uppercase(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private static List<String> tokenize(List<String> hpoTerms) {
        if (hpoTerms == null || hpoTerms.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> tokens = new ArrayList<String>();
        for (String raw : hpoTerms) {
            if (raw == null) {
                continue;
            }
            String[] parts = TOKEN_SPLITTER.split(raw.trim());
            for (String part : parts) {
                if (part != null && !part.trim().isEmpty()) {
                    tokens.add(part.trim());
                }
            }
        }
        return tokens;
    }

    private static class ProvenanceParseResult {

        private final Map<String, PhenotypeProvenance> byHpoId;
        private final List<String> warnings;

        private ProvenanceParseResult(Map<String, PhenotypeProvenance> byHpoId, List<String> warnings) {
            this.byHpoId = byHpoId;
            this.warnings = warnings;
        }
    }

    private static class PhenotypeProvenance {

        private final String label;
        private final String source;
        private final String matchedTerm;
        private final Double confidence;

        private PhenotypeProvenance(String label,
                                    String source,
                                    String matchedTerm,
                                    Double confidence) {
            this.label = label;
            this.source = source;
            this.matchedTerm = matchedTerm;
            this.confidence = confidence;
        }
    }
}
