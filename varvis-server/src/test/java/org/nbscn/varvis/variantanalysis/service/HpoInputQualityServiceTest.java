package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.Test;
import org.nbscn.varvis.exception.VariantAnalysisInputException;
import org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties;
import org.nbscn.varvis.variantanalysis.domain.HpoOntologyTermStatus;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeAwareSummary;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInput;
import org.nbscn.varvis.variantanalysis.domain.PhenotypeInputStatus;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HpoInputQualityServiceTest {

    @Test
    void currentPositiveAndNegativeTermsRemainStrictlySeparated() {
        StubResolver resolver = new StubResolver(true)
                .current("HP:0000001", "Present term")
                .current("HP:0000002", "Absent term");
        PhenotypeAwareSummary summary = service(resolver).summarizeProduct(
                Collections.singletonList("HP:0000001"),
                Collections.emptyList(),
                Collections.singletonList("HP:0000002")
        );

        assertTrue(summary.isEnabled());
        assertEquals(1, summary.getInputHpoCount());
        assertEquals(1, summary.getValidHpoCount());
        assertEquals(Collections.singletonList("HP:0000001"), summary.getValidHpoTerms());
        assertEquals(Collections.singletonList("HP:0000002"), summary.getExcludedHpoTerms());
        assertEquals(1, summary.getExcludedHpoCount());
        assertFalse(summary.getPhenotypeInputs().get(0).isExcluded());
        assertTrue(summary.getPhenotypeInputs().get(1).isExcluded());
        assertEquals(HpoOntologyTermStatus.CURRENT,
                summary.getPhenotypeInputs().get(1).getOntologyStatus());
    }

    @Test
    void unavailableOntologyAcceptsWellFormedTermsWithoutInventingUnknownStatus() {
        StubResolver resolver = new StubResolver(false);
        PhenotypeAwareSummary summary = service(resolver).summarizeProduct(
                Collections.singletonList("HP:9999999"),
                Collections.emptyList(),
                Collections.emptyList()
        );

        assertEquals(Collections.singletonList("HP:9999999"), summary.getValidHpoTerms());
        assertEquals(HpoOntologyTermStatus.UNAVAILABLE,
                summary.getPhenotypeInputs().get(0).getOntologyStatus());
        assertTrue(summary.getWarnings().stream().anyMatch(value -> value.contains("ontology is unavailable")));
        assertFalse(summary.getWarnings().stream().anyMatch(value -> value.contains("\\") || value.contains("/")));
    }

    @Test
    void availableOntologyBlocksUnknownAndUnresolvedObsoleteTerms() {
        StubResolver resolver = new StubResolver(true)
                .unresolved("HP:0000003", HpoOntologyTermStatus.OBSOLETE_UNRESOLVED);

        VariantAnalysisInputException unknown = assertThrows(
                VariantAnalysisInputException.class,
                () -> service(resolver).summarizeProduct(
                        Collections.singletonList("HP:9999999"), null, null)
        );
        assertTrue(unknown.getMessage().contains("HPO_TERM_UNKNOWN"));

        VariantAnalysisInputException obsolete = assertThrows(
                VariantAnalysisInputException.class,
                () -> service(resolver).summarizeProduct(
                        Collections.singletonList("HP:0000003"), null, null)
        );
        assertTrue(obsolete.getMessage().contains("HPO_OBSOLETE_UNRESOLVED"));
    }

    @Test
    void obsoleteReplacementIsNormalizedAndAudited() {
        StubResolver resolver = new StubResolver(true)
                .current("HP:0000001", "Current label")
                .replacement("HP:0000002", "HP:0000001", "Current label");
        PhenotypeAwareSummary summary = service(resolver).summarizeProduct(
                Collections.singletonList("HP:0000002"), null, null);

        assertEquals(Collections.singletonList("HP:0000001"), summary.getValidHpoTerms());
        PhenotypeInput input = summary.getPhenotypeInputs().get(0);
        assertEquals("HP:0000002", input.getRequestedHpoId());
        assertEquals("HP:0000001", input.getHpoId());
        assertEquals("HP:0000001", input.getReplacementHpoId());
        assertEquals("Current label", input.getOntologyLabel());
        assertEquals(HpoOntologyTermStatus.OBSOLETE_WITH_REPLACEMENT, input.getOntologyStatus());
        assertTrue(input.getWarnings().stream().anyMatch(value -> value.contains("was replaced")));
    }

    @Test
    void directAndReplacementInducedPositiveNegativeConflictsAreBlocked() {
        StubResolver resolver = new StubResolver(true)
                .current("HP:0000001", "Current")
                .replacement("HP:0000002", "HP:0000001", "Current");

        VariantAnalysisInputException direct = assertThrows(
                VariantAnalysisInputException.class,
                () -> service(resolver).summarizeProduct(
                        Collections.singletonList("HP:0000001"), null,
                        Collections.singletonList("HP:0000001"))
        );
        assertTrue(direct.getMessage().contains("HPO_POSITIVE_NEGATIVE_CONFLICT"));

        VariantAnalysisInputException replacement = assertThrows(
                VariantAnalysisInputException.class,
                () -> service(resolver).summarizeProduct(
                        Collections.singletonList("HP:0000001"), null,
                        Collections.singletonList("HP:0000002"))
        );
        assertTrue(replacement.getMessage().contains("HPO_POSITIVE_NEGATIVE_CONFLICT"));
    }

    @Test
    void replacementDuplicatesAreDeduplicatedAndExcludedOnlyInputRemainsObservable() {
        StubResolver resolver = new StubResolver(true)
                .current("HP:0000001", "Current")
                .replacement("HP:0000002", "HP:0000001", "Current")
                .current("HP:0000003", "Absent");
        PhenotypeAwareSummary deduplicated = service(resolver).summarizeProduct(
                Arrays.asList("HP:0000001", "HP:0000002"), null, null);

        assertEquals(Collections.singletonList("HP:0000001"), deduplicated.getValidHpoTerms());
        assertEquals(1, deduplicated.getDuplicateHpoCount());
        assertEquals(PhenotypeInputStatus.DUPLICATE,
                deduplicated.getPhenotypeInputs().get(1).getStatus());

        PhenotypeAwareSummary excludedOnly = service(resolver).summarizeProduct(
                null, null, Collections.singletonList("HP:0000003"));
        assertFalse(excludedOnly.isEnabled());
        assertTrue(excludedOnly.hasAnyPhenotypeInput());
        assertEquals(0, excludedOnly.getInputHpoCount());
        assertTrue(excludedOnly.getValidHpoTerms().isEmpty());
        assertEquals(Collections.singletonList("HP:0000003"), excludedOnly.getExcludedHpoTerms());
        assertTrue(excludedOnly.getPhenotypeInputs().get(0).isExcluded());
    }

    @Test
    void invalidConfiguredPathDegradesToUnavailableWithoutBreakingProductInput() {
        VariantAnalysisProperties properties = new VariantAnalysisProperties();
        properties.getHpoOntology().setFile("invalid" + '\0' + "path.obo");
        properties.getHpoOntology().setExpectedDataVersion("synthetic-v1");
        properties.getHpoOntology().setExpectedSha256(String.valueOf('a').repeat(64));

        PhenotypeAwareSummary summary = HpoInputQualityService.fromProperties(properties)
                .summarizeProduct(Collections.singletonList("HP:0000001"), null, null);

        assertEquals(HpoOntologyTermStatus.UNAVAILABLE,
                summary.getPhenotypeInputs().get(0).getOntologyStatus());
        assertTrue(summary.getWarnings().stream().anyMatch(value -> value.contains("ontology is unavailable")));
    }

    @Test
    void malformedPositiveRemainsCapturedButMalformedExcludedTermIsBlocked() {
        StubResolver resolver = new StubResolver(true);

        PhenotypeAwareSummary positive = service(resolver).summarizeProduct(
                Collections.singletonList("not-an-hpo-id"), null, null);

        assertTrue(positive.isEnabled());
        assertEquals(1, positive.getInputHpoCount());
        assertEquals(0, positive.getValidHpoCount());
        assertEquals(1, positive.getInvalidHpoCount());
        assertEquals(PhenotypeInputStatus.INVALID_FORMAT,
                positive.getPhenotypeInputs().get(0).getStatus());
        assertEquals(HpoOntologyTermStatus.UNAVAILABLE,
                positive.getPhenotypeInputs().get(0).getOntologyStatus());

        VariantAnalysisInputException excluded = assertThrows(
                VariantAnalysisInputException.class,
                () -> service(resolver).summarizeProduct(
                        null, null, Collections.singletonList("not-an-hpo-id"))
        );
        assertTrue(excluded.getMessage().contains("HPO_INVALID_FORMAT"));
    }

    @Test
    void currentAliasIsAuditedAndConflictsAfterPrimaryNormalization() {
        StubResolver resolver = new StubResolver(true)
                .current("HP:0000001", "Primary")
                .alias("HP:0000101", "HP:0000001", "Primary");

        PhenotypeAwareSummary aliasOnly = service(resolver).summarizeProduct(
                Collections.singletonList("HP:0000101"), null, null);
        PhenotypeInput input = aliasOnly.getPhenotypeInputs().get(0);
        assertEquals(Collections.singletonList("HP:0000001"), aliasOnly.getValidHpoTerms());
        assertEquals("HP:0000101", input.getRequestedHpoId());
        assertEquals("HP:0000001", input.getHpoId());
        assertEquals("HP:0000001", input.getReplacementHpoId());
        assertEquals(HpoOntologyTermStatus.CURRENT_ALIAS, input.getOntologyStatus());
        assertTrue(input.getWarnings().stream().anyMatch(value -> value.contains("alias")));

        VariantAnalysisInputException conflict = assertThrows(
                VariantAnalysisInputException.class,
                () -> service(resolver).summarizeProduct(
                        Collections.singletonList("HP:0000001"), null,
                        Collections.singletonList("HP:0000101"))
        );
        assertTrue(conflict.getMessage().contains("HPO_POSITIVE_NEGATIVE_CONFLICT"));
    }

    @Test
    void productInputBudgetIsBoundedBeforeResolverAccess() {
        PhenotypeAwareSummary atBoundary = service(new StubResolver(false)).summarizeProduct(
                Collections.nCopies(HpoInputQualityService.MAX_PRODUCT_RAW_ENTRIES, "HP:0000001"),
                null,
                null
        );
        assertEquals(HpoInputQualityService.MAX_PRODUCT_RAW_ENTRIES,
                atBoundary.getInputHpoCount());

        PhenotypeAwareSummary itemCharacterBoundary = service(new StubResolver(false)).summarizeProduct(
                Collections.singletonList(repeat('x',
                        HpoInputQualityService.MAX_PRODUCT_ITEM_CHARACTERS)),
                null,
                null
        );
        assertEquals(1, itemCharacterBoundary.getInvalidHpoCount());

        List<String> atTotalCharacterBoundary = new ArrayList<String>(Collections.nCopies(
                HpoInputQualityService.MAX_PRODUCT_TOTAL_CHARACTERS
                        / HpoInputQualityService.MAX_PRODUCT_ITEM_CHARACTERS,
                repeat('x', HpoInputQualityService.MAX_PRODUCT_ITEM_CHARACTERS)
        ));
        PhenotypeAwareSummary totalCharacterBoundary = service(new StubResolver(false)).summarizeProduct(
                atTotalCharacterBoundary, null, null);
        assertEquals(atTotalCharacterBoundary.size(), totalCharacterBoundary.getInvalidHpoCount());

        HpoInputQualityService limited = service(new ThrowingResolver());
        assertInputLimit(() -> limited.summarizeProduct(
                Collections.<String>nCopies(
                        HpoInputQualityService.MAX_PRODUCT_RAW_ENTRIES + 1, null),
                null,
                null
        ));
        assertInputLimit(() -> limited.summarizeProduct(
                Collections.singletonList(repeat('x',
                        HpoInputQualityService.MAX_PRODUCT_ITEM_CHARACTERS + 1)),
                null,
                null
        ));
        assertInputLimit(() -> limited.summarizeProduct(
                null,
                Collections.singletonList(repeat('x',
                        HpoInputQualityService.MAX_PRODUCT_ITEM_CHARACTERS + 1)),
                null
        ));

        List<String> halfCharacterBudget = Collections.nCopies(
                HpoInputQualityService.MAX_PRODUCT_TOTAL_CHARACTERS
                        / HpoInputQualityService.MAX_PRODUCT_ITEM_CHARACTERS / 2,
                repeat('x', HpoInputQualityService.MAX_PRODUCT_ITEM_CHARACTERS)
        );
        assertInputLimit(() -> limited.summarizeProduct(
                halfCharacterBudget,
                halfCharacterBudget,
                Collections.singletonList("x")
        ));

        List<String> overTokenCount = Arrays.asList(
                joinedHpoTokens(201),
                joinedHpoTokens(200)
        );
        assertInputLimit(() -> limited.summarizeProduct(overTokenCount, null, null));
    }

    private HpoInputQualityService service(HpoTermResolver resolver) {
        return new HpoInputQualityService(resolver);
    }

    private static final class StubResolver implements HpoTermResolver {
        private final boolean available;
        private final Map<String, Resolution> resolutions = new LinkedHashMap<String, Resolution>();

        private StubResolver(boolean available) {
            this.available = available;
        }

        private StubResolver current(String id, String label) {
            resolutions.put(id, new Resolution(id, id, null, label,
                    HpoOntologyTermStatus.CURRENT, true, null));
            return this;
        }

        private StubResolver replacement(String requested, String replacement, String label) {
            resolutions.put(requested, new Resolution(requested, replacement, replacement, label,
                    HpoOntologyTermStatus.OBSOLETE_WITH_REPLACEMENT, true, "HPO_OBSOLETE_REPLACED"));
            return this;
        }

        private StubResolver alias(String requested, String primary, String label) {
            resolutions.put(requested, new Resolution(requested, primary, primary, label,
                    HpoOntologyTermStatus.CURRENT_ALIAS, true, "HPO_ALIAS_NORMALIZED"));
            return this;
        }

        private StubResolver unresolved(String requested, HpoOntologyTermStatus status) {
            resolutions.put(requested, new Resolution(requested, null, null, null,
                    status, false, "HPO_OBSOLETE_UNRESOLVED"));
            return this;
        }

        @Override
        public Resolution resolve(String hpoId) {
            if (!available) {
                return new Resolution(hpoId, hpoId, null, null,
                        HpoOntologyTermStatus.UNAVAILABLE, true, "HPO_ONTOLOGY_UNAVAILABLE");
            }
            Resolution resolution = resolutions.get(hpoId);
            return resolution == null
                    ? new Resolution(hpoId, null, null, null,
                    HpoOntologyTermStatus.UNKNOWN, false, "HPO_TERM_UNKNOWN")
                    : resolution;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public String getDataVersion() {
            return available ? "synthetic-v1" : "unavailable";
        }
    }

    private void assertInputLimit(Runnable action) {
        VariantAnalysisInputException error = assertThrows(
                VariantAnalysisInputException.class, action::run);
        assertEquals("HPO_INPUT_LIMIT_EXCEEDED", error.getErrorCode());
        assertEquals("HPO_INPUT_LIMIT_EXCEEDED", error.getMessage());
    }

    private String joinedHpoTokens(int count) {
        return String.join(",", Collections.nCopies(count, "HP:0000001"));
    }

    private String repeat(char value, int count) {
        return String.valueOf(value).repeat(count);
    }

    private static final class ThrowingResolver implements HpoTermResolver {
        @Override
        public Resolution resolve(String hpoId) {
            throw new AssertionError("Over-limit input must not resolve HPO terms");
        }

        @Override
        public boolean isAvailable() {
            throw new AssertionError("Over-limit input must not inspect the HPO resolver");
        }

        @Override
        public String getDataVersion() {
            throw new AssertionError("Over-limit input must not inspect the HPO resolver");
        }
    }
}
