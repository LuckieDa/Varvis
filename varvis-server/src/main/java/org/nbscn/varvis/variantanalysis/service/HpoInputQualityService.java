package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.exception.VariantAnalysisInputException;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.GenePhenotypePriorSummary;
import org.nbscn.varvis.variantanalysis.domain.HpoOntologyTermStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInput;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInputStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeRescueSummary;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/** Product-only HPO terminology quality gate. Benchmark submissions deliberately bypass this service. */
public final class HpoInputQualityService {

    static final int MAX_PRODUCT_RAW_ENTRIES = 400;
    static final int MAX_PRODUCT_ITEM_CHARACTERS = 4_096;
    static final int MAX_PRODUCT_TOTAL_CHARACTERS = 65_536;
    static final int MAX_PRODUCT_HPO_TOKENS = 400;

    public static final String EXCLUDED_OBSERVATIONAL_WARNING =
            "Explicitly absent HPO terms are captured as observational metadata and are not used for ranking.";

    private final Supplier<HpoTermResolver> resolverSupplier;
    private volatile HpoTermResolver resolver;

    public HpoInputQualityService(HpoTermResolver resolver) {
        this(new FixedResolverSupplier(resolver));
        this.resolver = resolver;
    }

    private HpoInputQualityService(Supplier<HpoTermResolver> resolverSupplier) {
        if (resolverSupplier == null) {
            throw new IllegalArgumentException("resolverSupplier is required");
        }
        this.resolverSupplier = resolverSupplier;
    }

    public static HpoInputQualityService fromProperties(VariantAnalysisProperties properties) {
        VariantAnalysisProperties.HpoOntology configured = properties == null
                ? new VariantAnalysisProperties.HpoOntology()
                : properties.getHpoOntology();
        Path resolvedFile;
        try {
            resolvedFile = configured.getFile();
        } catch (RuntimeException ex) {
            resolvedFile = null;
        }
        final Path file = resolvedFile;
        final String version = configured.getExpectedDataVersion();
        final String sha256 = configured.getExpectedSha256();
        return new HpoInputQualityService(new Supplier<HpoTermResolver>() {
            @Override
            public HpoTermResolver get() {
                return new LocalOboHpoTermResolver(file, version, sha256);
            }
        });
    }

    public PhenotypeAwareSummary summarizeProduct(List<String> hpoTerms,
                                                  List<String> hpoProvenance,
                                                  List<String> excludedHpoTerms) {
        enforceProductInputBudget(hpoTerms, hpoProvenance, excludedHpoTerms);
        PhenotypeAwareSummary positive = PhenotypeInputNormalizer.summarize(hpoTerms, hpoProvenance);
        PhenotypeAwareSummary excluded = PhenotypeInputNormalizer.summarize(excludedHpoTerms);
        if (!positive.hasAnyPhenotypeInput() && !excluded.hasAnyPhenotypeInput()) {
            return PhenotypeAwareSummary.disabled();
        }
        // Preserve the legacy VARVIS product contract for malformed positive input: capture it as
        // invalid observational metadata and let the existing Exomiser-specific gate reject it.
        // The new explicitly-absent field is strict because silently accepting malformed negative
        // assertions would make their clinical meaning ambiguous.
        if (excluded.getInvalidHpoCount() > 0) {
            throw invalid("HPO_INVALID_FORMAT: Each HPO term must use the HP:1234567 format.");
        }
        rejectConflict(positive.getValidHpoTerms(), excluded.getValidHpoTerms());

        HpoTermResolver activeResolver = resolver();
        boolean ontologyAvailable = activeResolver.isAvailable();
        List<String> warnings = new ArrayList<String>();
        warnings.addAll(positive.getWarnings());
        for (String warning : excluded.getWarnings()) {
            if (!PhenotypeAwareSummary.RANKING_NOT_APPLIED_WARNING.equals(warning)) {
                warnings.add(warning);
            }
        }
        if (!ontologyAvailable) {
            // Deliberate degraded product policy: never claim UNKNOWN without a verified complete index.
            warnings.add("HPO ontology is unavailable; well-formed identifiers were accepted without terminology lookup.");
        }
        if (excluded.hasAnyPhenotypeInput()) {
            warnings.add(EXCLUDED_OBSERVATIONAL_WARNING);
        }

        NormalizedPolarity normalizedPositive = normalizePolarity(
                positive.getPhenotypeInputs(), false, activeResolver, warnings);
        NormalizedPolarity normalizedExcluded = normalizePolarity(
                excluded.getPhenotypeInputs(), true, activeResolver, warnings);
        rejectConflict(normalizedPositive.validTerms, normalizedExcluded.validTerms);

        List<PhenotypeInput> allInputs = new ArrayList<PhenotypeInput>();
        allInputs.addAll(normalizedPositive.inputs);
        allInputs.addAll(normalizedExcluded.inputs);
        List<String> invalidTerms = new ArrayList<String>();
        invalidTerms.addAll(positive.getInvalidHpoTerms());
        invalidTerms.addAll(excluded.getInvalidHpoTerms());
        int duplicateCount = positive.getDuplicateHpoCount()
                + excluded.getDuplicateHpoCount()
                + normalizedPositive.additionalDuplicateCount
                + normalizedExcluded.additionalDuplicateCount;

        return new PhenotypeAwareSummary(
                positive.getInputHpoCount(),
                normalizedPositive.validTerms.size(),
                invalidTerms.size(),
                duplicateCount,
                normalizedPositive.validTerms,
                invalidTerms,
                warnings,
                allInputs,
                normalizedExcluded.validTerms,
                positive.isEnabled(),
                false,
                GenePhenotypePriorSummary.notGenerated(),
                0,
                Collections.emptyList(),
                PhenotypeRescueSummary.disabled()
        );
    }

    private NormalizedPolarity normalizePolarity(List<PhenotypeInput> source,
                                                  boolean excluded,
                                                  HpoTermResolver activeResolver,
                                                  List<String> summaryWarnings) {
        List<PhenotypeInput> inputs = new ArrayList<PhenotypeInput>();
        List<String> validTerms = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        int additionalDuplicates = 0;
        for (PhenotypeInput input : source) {
            if (input == null) {
                continue;
            }
            if (input.getStatus() == PhenotypeInputStatus.INVALID_FORMAT) {
                if (excluded) {
                    throw invalid("HPO_INVALID_FORMAT: Each HPO term must use the HP:1234567 format.");
                }
                inputs.add(new PhenotypeInput(
                        input.getHpoId(),
                        input.getLabel(),
                        input.getSource(),
                        input.getStatus(),
                        input.getConfidence(),
                        input.getMatchedTerm(),
                        input.getWarnings(),
                        input.getRequestedHpoId(),
                        null,
                        null,
                        HpoOntologyTermStatus.UNAVAILABLE,
                        false
                ));
                continue;
            }
            HpoTermResolver.Resolution resolution = activeResolver.resolve(input.getHpoId());
            if (!resolution.isUsable()) {
                throw invalid(safeBlockingMessage(resolution));
            }
            String effective = normalize(resolution.getEffectiveHpoId());
            if (effective == null) {
                throw invalid("HPO_TERM_UNRESOLVED: The HPO term could not be normalized safely.");
            }
            boolean legacyDuplicate = input.getStatus() == PhenotypeInputStatus.DUPLICATE;
            boolean replacementDuplicate = !legacyDuplicate && !seen.add(effective);
            if (legacyDuplicate) {
                seen.add(effective);
            }
            PhenotypeInputStatus status = legacyDuplicate || replacementDuplicate
                    ? PhenotypeInputStatus.DUPLICATE
                    : PhenotypeInputStatus.VALID;
            if (replacementDuplicate) {
                additionalDuplicates++;
            }
            if (status == PhenotypeInputStatus.VALID) {
                validTerms.add(effective);
            }
            List<String> inputWarnings = new ArrayList<String>(input.getWarnings());
            if (resolution.getStatus() == HpoOntologyTermStatus.OBSOLETE_WITH_REPLACEMENT) {
                String warning = "Obsolete HPO term " + resolution.getRequestedHpoId()
                        + " was replaced by " + effective + ".";
                inputWarnings.add(warning);
                summaryWarnings.add(warning);
            } else if (resolution.getStatus() == HpoOntologyTermStatus.CURRENT_ALIAS) {
                String warning = "HPO alias " + resolution.getRequestedHpoId()
                        + " was normalized to primary identifier " + effective + ".";
                inputWarnings.add(warning);
                summaryWarnings.add(warning);
            }
            inputs.add(new PhenotypeInput(
                    effective,
                    input.getLabel(),
                    input.getSource(),
                    status,
                    input.getConfidence(),
                    input.getMatchedTerm(),
                    inputWarnings,
                    resolution.getRequestedHpoId(),
                    resolution.getReplacementHpoId(),
                    resolution.getLabel(),
                    resolution.getStatus(),
                    excluded
            ));
        }
        return new NormalizedPolarity(inputs, validTerms, additionalDuplicates);
    }

    private void enforceProductInputBudget(List<String> hpoTerms,
                                           List<String> hpoProvenance,
                                           List<String> excludedHpoTerms) {
        long rawEntries = listSize(hpoTerms)
                + listSize(hpoProvenance)
                + listSize(excludedHpoTerms);
        if (rawEntries > MAX_PRODUCT_RAW_ENTRIES) {
            throw inputLimitExceeded();
        }

        long totalCharacters = 0L;
        totalCharacters = validateCharacterBudget(hpoTerms, totalCharacters);
        totalCharacters = validateCharacterBudget(hpoProvenance, totalCharacters);
        validateCharacterBudget(excludedHpoTerms, totalCharacters);

        int tokens = countHpoTokenTransitions(hpoTerms, 0);
        countHpoTokenTransitions(excludedHpoTerms, tokens);
    }

    private long listSize(List<String> values) {
        return values == null ? 0L : values.size();
    }

    private long validateCharacterBudget(List<String> values, long initialTotal) {
        long total = initialTotal;
        if (values == null) {
            return total;
        }
        for (String value : values) {
            int length = value == null ? 0 : value.length();
            if (length > MAX_PRODUCT_ITEM_CHARACTERS) {
                throw inputLimitExceeded();
            }
            total += length;
            if (total > MAX_PRODUCT_TOTAL_CHARACTERS) {
                throw inputLimitExceeded();
            }
        }
        return total;
    }

    /** Counts tokenizer-equivalent transitions without allocating a token array. */
    private int countHpoTokenTransitions(List<String> values, int initialCount) {
        int count = initialCount;
        if (values == null) {
            return count;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            boolean insideToken = false;
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (isTokenSeparator(current)) {
                    insideToken = false;
                } else if (!insideToken) {
                    insideToken = true;
                    count++;
                    if (count > MAX_PRODUCT_HPO_TOKENS) {
                        throw inputLimitExceeded();
                    }
                }
            }
        }
        return count;
    }

    private boolean isTokenSeparator(char value) {
        return value == ','
                || value == ';'
                || value == ' '
                || value == '\t'
                || value == '\n'
                || value == '\u000B'
                || value == '\f'
                || value == '\r';
    }

    private VariantAnalysisInputException inputLimitExceeded() {
        return new VariantAnalysisInputException(
                "HPO_INPUT_LIMIT_EXCEEDED", "HPO_INPUT_LIMIT_EXCEEDED");
    }

    private void rejectConflict(List<String> positive, List<String> excluded) {
        Set<String> positiveTerms = new LinkedHashSet<String>();
        for (String term : positive == null ? Collections.<String>emptyList() : positive) {
            String normalized = normalize(term);
            if (normalized != null) {
                positiveTerms.add(normalized);
            }
        }
        for (String term : excluded == null ? Collections.<String>emptyList() : excluded) {
            String normalized = normalize(term);
            if (normalized != null && positiveTerms.contains(normalized)) {
                throw invalid("HPO_POSITIVE_NEGATIVE_CONFLICT: The same normalized HPO term cannot be present and explicitly absent.");
            }
        }
    }

    private String safeBlockingMessage(HpoTermResolver.Resolution resolution) {
        HpoOntologyTermStatus status = resolution.getStatus();
        if (status == HpoOntologyTermStatus.UNKNOWN) {
            return "HPO_TERM_UNKNOWN: The configured ontology does not contain one of the submitted HPO terms.";
        }
        if (status == HpoOntologyTermStatus.OBSOLETE_AMBIGUOUS) {
            return "HPO_OBSOLETE_AMBIGUOUS: An obsolete HPO term has more than one replacement.";
        }
        return "HPO_OBSOLETE_UNRESOLVED: An obsolete HPO term has no single current replacement.";
    }

    private VariantAnalysisInputException invalid(String message) {
        return new VariantAnalysisInputException("INVALID_INPUT", message);
    }

    private String normalize(String value) {
        return value == null || value.trim().isEmpty()
                ? null
                : value.trim().toUpperCase(Locale.ROOT);
    }

    private HpoTermResolver resolver() {
        HpoTermResolver current = resolver;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = resolver;
            if (current == null) {
                current = resolverSupplier.get();
                if (current == null) {
                    throw new IllegalStateException("HPO resolver supplier returned null");
                }
                resolver = current;
            }
        }
        return current;
    }

    private static final class FixedResolverSupplier implements Supplier<HpoTermResolver> {
        private final HpoTermResolver resolver;

        private FixedResolverSupplier(HpoTermResolver resolver) {
            if (resolver == null) {
                throw new IllegalArgumentException("resolver is required");
            }
            this.resolver = resolver;
        }

        @Override
        public HpoTermResolver get() {
            return resolver;
        }
    }

    private static final class NormalizedPolarity {
        private final List<PhenotypeInput> inputs;
        private final List<String> validTerms;
        private final int additionalDuplicateCount;

        private NormalizedPolarity(List<PhenotypeInput> inputs,
                                   List<String> validTerms,
                                   int additionalDuplicateCount) {
            this.inputs = inputs;
            this.validTerms = validTerms;
            this.additionalDuplicateCount = additionalDuplicateCount;
        }
    }
}
