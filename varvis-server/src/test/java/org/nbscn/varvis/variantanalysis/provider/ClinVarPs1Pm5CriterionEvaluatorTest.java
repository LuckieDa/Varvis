package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClinVarPs1Pm5CriterionEvaluatorTest {

    private static final String PS1 = "ACMG_AMP_2015:PS1";
    private static final String PM5 = "ACMG_AMP_2015:PM5";
    private static final String SELECTION_ID = "cspec-selection-sha256-"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String PROFILE_ID = "CLINVAR_MISSENSE_PS1_PM5@1.0.0";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final ClinVarPs1Pm5CriterionEvaluator evaluator =
            new ClinVarPs1Pm5CriterionEvaluator(mapper);

    private ObjectNode context;
    private ObjectNode profile;
    private ObjectNode registry;

    @BeforeEach
    void setUp() throws Exception {
        profile = ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper);
        registry = ClinVarPs1Pm5CriterionEvaluator.loadSuccessorRegistry(mapper);
        try (InputStream input = getClass().getResourceAsStream(
                "/e2/runtime-evidence-v2-fixture.json")) {
            context = (ObjectNode) mapper.readTree(input).path("context").deepCopy();
        }
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:1097").put("symbol", "BRAF");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0021060")
                .put("label", "Synthetic RASopathy context");
        ((ObjectNode) context.path("transcript")).put("accession", "ENST00000646891")
                .put("version", 2).put("selectionPolicy", "SYNTHETIC_PINNED")
                .put("annotationSourceVersion", "VEP 116");
        setAllele(context, "GRCh38", "7", 140753336, "A", "T");
        rehashContext(context);
    }

    @Test
    void ps1MetAndPm5NotMetForSameAminoAcidPathogenicExpertAssertion() {
        ObjectNode atomic = assertionAtomic("ps1", "SAME_AMINO_ACID_CHANGE",
                "Pathogenic", "reviewed by expert panel", "MONDO:0021060", true);

        List<ObjectNode> result = evaluate(List.of(atomic));

        assertResult(criterion(result, PS1), "MET", "STRONG");
        assertResult(criterion(result, PM5), "NOT_MET", "MODERATE");
        assertEquals(List.of(atomic.path("evidenceId").asText()), textValues(
                criterion(result, PS1).path("evidenceIds")));
    }

    @Test
    void pm5MetAndPs1NotMetForSameResiduePathogenicPracticeGuidelineAssertion() {
        ObjectNode atomic = assertionAtomic("pm5", "SAME_RESIDUE_DIFFERENT_AMINO_ACID",
                "Pathogenic", "practice guideline", "MONDO:MONDO:0021060", true);

        List<ObjectNode> result = evaluate(List.of(atomic));

        assertResult(criterion(result, PS1), "NOT_MET", "STRONG");
        assertResult(criterion(result, PM5), "MET", "MODERATE");
    }

    @ParameterizedTest
    @CsvSource({
            "MONDO:0021060,MET",
            "MONDO:MONDO:0021060,MET",
            "MONDO:0000002,NOT_MET"
    })
    void conditionMatchingIsExactWithOnlyBoundedDoublePrefixNormalization(
            String rawCondition, String expected) {
        ObjectNode atomic = assertionAtomic("condition-" + expected,
                "SAME_RESIDUE_DIFFERENT_AMINO_ACID", "Pathogenic",
                "reviewed by expert panel", rawCondition, true);

        assertEquals(expected, status(criterion(evaluate(List.of(atomic)), PM5)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "MONDO:MONDO:MONDO:0021060",
            "prefix MONDO:0021060",
            "MONDO:0021060 suffix"
    })
    void conditionTokenMustBeOneCompleteAcceptedMondoForm(String rawCondition) {
        ObjectNode atomic = assertionAtomic("invalid-mondo-" + rawCondition,
                "SAME_AMINO_ACID_CHANGE", "Pathogenic",
                "reviewed by expert panel", rawCondition, true);

        assertBlocked(evaluate(List.of(atomic)),
                "ASSERTION_CONDITION_NOT_RELIABLY_RESOLVED", "UNKNOWN");
    }

    @Test
    void missingMondoConditionWithoutAnyReliablePeerIsNotEvaluated() {
        ObjectNode atomic = assertionAtomic("missing-mondo", "SAME_AMINO_ACID_CHANGE",
                "Pathogenic", "reviewed by expert panel", null, true);

        assertBlocked(evaluate(List.of(atomic)),
                "ASSERTION_CONDITION_NOT_RELIABLY_RESOLVED", "UNKNOWN");
    }

    @Test
    void nonMondoAndOtherMondoAssertionsDoNotPolluteAnExactMondoMatch() {
        ObjectNode exact = assertionAtomic("exact-mondo", "SAME_RESIDUE_DIFFERENT_AMINO_ACID",
                "Pathogenic", "reviewed by expert panel", "MONDO:0021060", true);
        ObjectNode medgenWithoutDate = assertionAtomic("medgen-no-date",
                "SAME_AMINO_ACID_CHANGE", "Pathogenic", "reviewed by expert panel",
                "MedGen:C5555857", false);
        ObjectNode otherMondoWithoutDate = assertionAtomic("other-mondo-no-date",
                "SAME_AMINO_ACID_CHANGE", "Pathogenic", "reviewed by expert panel",
                "MONDO:0000002", false);

        List<ObjectNode> result = evaluate(List.of(medgenWithoutDate, exact,
                otherMondoWithoutDate));

        assertResult(criterion(result, PS1), "NOT_MET", "STRONG");
        assertResult(criterion(result, PM5), "MET", "MODERATE");
    }

    @Test
    void nonMatchingMondoCannotResolveAnUnparseablePeer() {
        ObjectNode otherMondoWithoutDate = assertionAtomic("other-reliable-mondo",
                "SAME_AMINO_ACID_CHANGE", "Pathogenic", "reviewed by expert panel",
                "MONDO:0000002", false);
        ObjectNode unparseableWithoutDate = assertionAtomic("unparseable-peer",
                "SAME_AMINO_ACID_CHANGE", "Pathogenic", "reviewed by expert panel",
                "not provided", false);

        List<ObjectNode> result = evaluate(List.of(unparseableWithoutDate,
                otherMondoWithoutDate));

        assertBlocked(result, "ASSERTION_CONDITION_NOT_RELIABLY_RESOLVED", "UNKNOWN");
    }

    @ParameterizedTest
    @CsvSource({
            "Pathogenic,reviewed by expert panel,MET",
            "Pathogenic,practice guideline,MET",
            "Likely pathogenic,reviewed by expert panel,NOT_MET",
            "Pathogenic/Likely pathogenic,reviewed by expert panel,NOT_MET",
            "Pathogenic,criteria provided single submitter,NOT_MET"
    })
    void onlyExactPathogenicHighReviewAssertionsAreEligible(
            String classification, String review, String expected) {
        ObjectNode atomic = assertionAtomic("eligibility-" + expected + "-" + review,
                "SAME_AMINO_ACID_CHANGE", classification, review,
                "MONDO:0021060", true);

        assertEquals(expected, status(criterion(evaluate(List.of(atomic)), PS1)));
    }

    @Test
    void missingRequiredAssertionFieldIsNotEvaluated() {
        ObjectNode atomic = assertionAtomic("missing-date", "SAME_AMINO_ACID_CHANGE",
                "Pathogenic", "reviewed by expert panel", "MONDO:0021060", false);

        assertBlocked(evaluate(List.of(atomic)), "ASSERTION_REQUIRED_FIELD_MISSING", "UNKNOWN");
    }

    @Test
    void sameConditionOpposingHighReviewDirectionsRequireReview() {
        ObjectNode pathogenic = assertionAtomicWithVariation("conflict-pathogenic", "40389",
                "SAME_AMINO_ACID_CHANGE", "Pathogenic",
                "reviewed by expert panel", "MONDO:0021060", true);
        ObjectNode benign = assertionAtomicWithVariation("conflict-benign", "40389",
                "SAME_RESIDUE_DIFFERENT_AMINO_ACID", "Benign",
                "practice guideline", "MONDO:MONDO:0021060", true);

        List<ObjectNode> result = evaluate(List.of(benign, pathogenic));

        for (ObjectNode value : result) {
            assertEquals("NOT_EVALUATED", status(value));
            assertEquals("SAME_CONDITION_ASSERTION_CONFLICT",
                    value.path("evaluationResult").path("reasonCode").asText());
            assertEquals("PRESENT", value.path("conflict").path("status").asText());
            assertTrue(value.path("conflict").path("conflictIds").path(0).asText()
                    .matches("^conflict-sha256-[0-9a-f]{64}$"));
            assertEquals("REQUIRED", value.path("review").path("status").asText());
            assertEquals("NONE", value.path("review").path("decision").asText());
            assertTrue(value.path("review").path("reviewRecordIds").path(0).asText()
                    .matches("^review-sha256-[0-9a-f]{64}$"));
        }
    }

    @Test
    void sameConditionOpposingDirectionsOnDifferentComparatorsDoNotConflict() {
        ObjectNode pathogenic = assertionAtomic("different-comparator-pathogenic",
                "SAME_AMINO_ACID_CHANGE", "Pathogenic",
                "reviewed by expert panel", "MONDO:0021060", true);
        ObjectNode benign = assertionAtomic("different-comparator-benign",
                "SAME_RESIDUE_DIFFERENT_AMINO_ACID", "Benign",
                "practice guideline", "MONDO:0021060", true);

        List<ObjectNode> result = evaluate(List.of(benign, pathogenic));

        assertResult(criterion(result, PS1), "MET", "STRONG");
        assertResult(criterion(result, PM5), "NOT_MET", "MODERATE");
        for (ObjectNode value : result) {
            assertEquals("NONE", value.path("conflict").path("status").asText());
            assertEquals("NOT_REQUIRED", value.path("review").path("status").asText());
        }
    }

    @Test
    void exactComparatorAlleleIsConflictKeyWhenVariationIdIsMissing() {
        ObjectNode pathogenic = assertionAtomicWithoutVariation("allele-conflict-pathogenic",
                "SAME_AMINO_ACID_CHANGE", "Pathogenic",
                "reviewed by expert panel", "MONDO:0021060", true);
        ObjectNode benign = assertionAtomicWithoutVariation("allele-conflict-benign",
                "SAME_AMINO_ACID_CHANGE", "Benign",
                "practice guideline", "MONDO:0021060", true);

        List<ObjectNode> result = evaluate(List.of(pathogenic, benign));

        assertEquals("PRESENT", criterion(result, PS1).path("conflict").path("status").asText());
        assertEquals("NOT_EVALUATED", status(criterion(result, PS1)));
    }

    @Test
    void conflictFromAnotherConditionDoesNotContaminateExactCondition() {
        ObjectNode exact = assertionAtomic("exact-pathogenic", "SAME_AMINO_ACID_CHANGE",
                "Pathogenic", "reviewed by expert panel", "MONDO:0021060", true);
        ObjectNode other = assertionAtomic("other-benign", "SAME_AMINO_ACID_CHANGE",
                "Benign", "practice guideline", "MONDO:0000002", true);

        List<ObjectNode> result = evaluate(List.of(other, exact));

        assertResult(criterion(result, PS1), "MET", "STRONG");
        assertEquals("NONE", criterion(result, PS1).path("conflict").path("status").asText());
    }

    @Test
    void successfulNoRecordAndKnownButIneligibleAssertionsAreNotMet() {
        List<ObjectNode> noRecord = evaluate(List.of(noRecordAtomic()));
        assertResult(criterion(noRecord, PS1), "NOT_MET", "STRONG");
        assertResult(criterion(noRecord, PM5), "NOT_MET", "MODERATE");
        assertFalse(criterion(noRecord, PS1).path("evidenceIds").isEmpty());

        ObjectNode ineligible = assertionAtomic("known-ineligible", "SAME_AMINO_ACID_CHANGE",
                "Likely pathogenic", "reviewed by expert panel", "MONDO:0021060", true);
        List<ObjectNode> known = evaluate(List.of(ineligible));
        assertResult(criterion(known, PS1), "NOT_MET", "STRONG");
        assertResult(criterion(known, PM5), "NOT_MET", "MODERATE");
    }

    @Test
    void noComparatorAtomicCannotBeReportedAsNotMet() {
        assertBlocked(evaluate(List.of()), "COMPARATOR_ATOMIC_EVIDENCE_MISSING", "UNKNOWN");
    }

    @Test
    void nonGrch38AndNonMissenseOrSpliceRoutesAreNotApplicable() {
        ObjectNode grch37 = context.deepCopy();
        setAllele(grch37, "GRCh37", "7", 140453136, "A", "T");
        rehashContext(grch37);
        ObjectNode grch37Atomic = assertionAtomic(grch37, "grch37",
                "SAME_AMINO_ACID_CHANGE", "Pathogenic", "reviewed by expert panel",
                "MONDO:0021060", true, "missense_variant", "missense_variant", null);
        assertBlocked(evaluator.evaluate(grch37, List.of(grch37Atomic), profile, registry,
                SELECTION_ID), "OUTSIDE_NATIVE_GRCH38_SIMPLE_SNV", "NOT_APPLICABLE");

        for (String consequence : List.of(
                "synonymous_variant", "missense_variant&splice_region_variant")) {
            ObjectNode atomic = assertionAtomic("route-" + consequence,
                    "SAME_AMINO_ACID_CHANGE", "Pathogenic",
                    "reviewed by expert panel", "MONDO:0021060", true,
                    consequence, consequence, null);
            assertBlocked(evaluate(List.of(atomic)), "OUTSIDE_MISSENSE_NON_SPLICING_ROUTE",
                    "NOT_APPLICABLE");
        }
    }

    @Test
    void profileAndRegistryDriftAreNotEvaluated() {
        ObjectNode atomic = assertionAtomic("drift", "SAME_AMINO_ACID_CHANGE",
                "Pathogenic", "reviewed by expert panel", "MONDO:0021060", true);
        ObjectNode driftedProfile = profile.deepCopy();
        driftedProfile.put("asOf", "2026-09-05T00:00:00Z");
        ((ObjectNode) driftedProfile.path("criteria").path(0))
                .put("baseStrength", "INVALID_STRENGTH");
        List<ObjectNode> profileResult = evaluator.evaluate(context, List.of(atomic),
                driftedProfile, registry, SELECTION_ID);
        assertBlocked(profileResult, "PROFILE_HASH_MISMATCH", "UNKNOWN");
        assertFrozenRuleBindings(profileResult);

        ObjectNode driftedRegistry = registry.deepCopy();
        driftedRegistry.put("registryId", "acmg-rule-registry-drifted");
        ((ObjectNode) driftedRegistry.path("criteria").path(1).path("currentGeneral"))
                .put("strength", "INVALID_STRENGTH");
        List<ObjectNode> registryResult = evaluator.evaluate(context, List.of(atomic),
                profile, driftedRegistry, SELECTION_ID);
        assertBlocked(registryResult, "RULE_REGISTRY_BINDING_MISMATCH", "UNKNOWN");
        assertFrozenRuleBindings(registryResult);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CLINVAR_2026_03_21_GRCH38",
            "VEP_116_GRCH38",
            "CLINVAR_COMPARATOR_SIDECAR_2026_03_05_GRCH38"
    })
    void eachPinnedResourceIdentityDriftIsNotEvaluated(String resourceId) {
        ObjectNode atomic = assertionAtomic("resource-drift-" + resourceId,
                "SAME_AMINO_ACID_CHANGE", "Pathogenic",
                "reviewed by expert panel", "MONDO:0021060", true,
                "missense_variant", "missense_variant", resourceId);

        assertBlocked(evaluate(List.of(atomic)), "RESOURCE_IDENTITY_MISMATCH", "UNKNOWN");
    }

    @ParameterizedTest
    @CsvSource({"FAILED,COMPARATOR_RESOURCE_FAILED", "TIMEOUT,COMPARATOR_RESOURCE_TIMEOUT"})
    void providerFailureIsNotEvaluated(String execution, String reason) {
        assertBlocked(evaluate(List.of(failedAtomic(execution))), reason, "UNKNOWN");
    }

    @Test
    void outputOrderEvidenceIdsAndEvaluationIdsAreStable() {
        ObjectNode ps1 = assertionAtomic("stable-z", "SAME_AMINO_ACID_CHANGE",
                "Pathogenic", "reviewed by expert panel", "MONDO:0021060", true);
        ObjectNode pm5 = assertionAtomic("stable-a", "SAME_RESIDUE_DIFFERENT_AMINO_ACID",
                "Pathogenic", "practice guideline", "MONDO:MONDO:0021060", true);

        List<ObjectNode> first = evaluate(List.of(ps1, pm5));
        List<ObjectNode> second = evaluate(List.of(pm5, ps1, pm5));

        assertEquals(first, second);
        assertEquals(List.of(PS1, PM5), List.of(
                first.get(0).path("criterionId").asText(),
                first.get(1).path("criterionId").asText()));
        assertResult(first.get(0), "MET", "STRONG");
        assertResult(first.get(1), "MET", "MODERATE");
        for (ObjectNode value : first) {
            assertTrue(value.path("evaluationId").asText()
                    .matches("^evaluation-sha256-[0-9a-f]{64}$"));
            assertTrue(value.path("deterministicTrace").path("traceSha256").asText()
                    .matches("^[0-9a-f]{64}$"));
            assertEquals("COMPLETED",
                    value.path("deterministicTrace").path("executionStatus").asText());
        }
        assertNotEquals(first.get(0).path("evaluationId"), first.get(1).path("evaluationId"));
    }

    private List<ObjectNode> evaluate(List<ObjectNode> atomics) {
        return evaluator.evaluate(context, atomics, profile, registry, SELECTION_ID);
    }

    private ObjectNode assertionAtomic(String recordSuffix,
                                       String relation,
                                       String classification,
                                       String review,
                                       String condition,
                                       boolean includeDate) {
        return assertionAtomic(context, recordSuffix, relation, classification, review,
                condition, includeDate, "missense_variant", "missense_variant", null);
    }

    private ObjectNode assertionAtomicWithVariation(String recordSuffix,
                                                     String variationId,
                                                     String relation,
                                                     String classification,
                                                     String review,
                                                     String condition,
                                                     boolean includeDate) {
        return assertionAtomic(context, recordSuffix, relation, classification, review,
                condition, includeDate, "missense_variant", "missense_variant", null,
                variationId, true);
    }

    private ObjectNode assertionAtomicWithoutVariation(String recordSuffix,
                                                        String relation,
                                                        String classification,
                                                        String review,
                                                        String condition,
                                                        boolean includeDate) {
        return assertionAtomic(context, recordSuffix, relation, classification, review,
                condition, includeDate, "missense_variant", "missense_variant", null,
                null, false);
    }

    private ObjectNode assertionAtomic(String recordSuffix,
                                       String relation,
                                       String classification,
                                       String review,
                                       String condition,
                                       boolean includeDate,
                                       String targetConsequence,
                                       String comparatorConsequence,
                                       String driftedResourceId) {
        return assertionAtomic(context, recordSuffix, relation, classification, review,
                condition, includeDate, targetConsequence, comparatorConsequence,
                driftedResourceId);
    }

    private ObjectNode assertionAtomic(ObjectNode evidenceContext,
                                       String recordSuffix,
                                       String relation,
                                       String classification,
                                       String review,
                                       String condition,
                                       boolean includeDate,
                                       String targetConsequence,
                                       String comparatorConsequence,
                                       String driftedResourceId) {
        return assertionAtomic(evidenceContext, recordSuffix, relation, classification, review,
                condition, includeDate, targetConsequence, comparatorConsequence,
                driftedResourceId, recordSuffix, true);
    }

    private ObjectNode assertionAtomic(ObjectNode evidenceContext,
                                       String recordSuffix,
                                       String relation,
                                       String classification,
                                       String review,
                                       String condition,
                                       boolean includeDate,
                                       String targetConsequence,
                                       String comparatorConsequence,
                                       String driftedResourceId,
                                       String variationId,
                                       boolean includeVariationId) {
        ObjectNode carrier = baseCarrier(evidenceContext, "HIT", recordSuffix,
                driftedResourceId);
        addMeasurement(carrier, "clinvar.comparator.assembly", "GRCh38");
        addMeasurement(carrier, "clinvar.comparator.contig", "7");
        addMeasurement(carrier, "clinvar.comparator.position", "140753336");
        addMeasurement(carrier, "clinvar.comparator.reference", "A");
        addMeasurement(carrier, "clinvar.comparator.alternate", "C");
        if (includeVariationId) {
            addMeasurement(carrier, "clinvar.comparator.variationId", variationId);
        }
        addMeasurement(carrier, "clinvar.comparator.geneSymbols", "BRAF");
        addMeasurement(carrier, "vep.proteinRelation", relation);
        addMeasurement(carrier, "vep.target.consequence", targetConsequence);
        addMeasurement(carrier, "vep.comparator.consequence", comparatorConsequence);
        addMeasurement(carrier, "vep.target.transcriptId", "ENST00000646891.2");
        addMeasurement(carrier, "vep.target.proteinPosition", "600");
        addMeasurement(carrier, "vep.target.referenceAminoAcid", "V");
        addMeasurement(carrier, "vep.target.alternateAminoAcid", "E");
        addMeasurement(carrier, "vep.comparator.transcriptId", "ENST00000646891.2");
        addMeasurement(carrier, "vep.comparator.proteinPosition", "600");
        addMeasurement(carrier, "vep.comparator.referenceAminoAcid", "V");
        addMeasurement(carrier, "vep.comparator.alternateAminoAcid",
                "SAME_AMINO_ACID_CHANGE".equals(relation) ? "E" : "G");
        addMeasurement(carrier, "clinvar.assertion.scv", "SCV-" + recordSuffix);
        addMeasurement(carrier, "clinvar.assertion.classification", classification);
        addMeasurement(carrier, "clinvar.assertion.reviewStatus", review);
        if (includeDate) {
            addMeasurement(carrier, "clinvar.assertion.dateLastEvaluated", "2020-06-25");
        }
        addMeasurement(carrier, "clinvar.assertion.condition.submittedRaw", condition);
        addMeasurement(carrier, "clinvar.assertion.condition.reportedRaw", "RASopathy");
        addMeasurement(carrier, "clinvar.assertion.geneSymbol", "BRAF");
        addMeasurement(carrier, "clinvar.assertion.source.logicalName",
                "submission_summary_2026-03.txt.gz");
        addMeasurement(carrier, "clinvar.assertion.source.line",
                Integer.toString(Math.abs(recordSuffix.hashCode()) + 1));
        return adapter.adapt(evidenceContext, carrier);
    }

    private ObjectNode noRecordAtomic() {
        return adapter.adapt(context, baseCarrier(context, "NO_RECORD", "no-record", null));
    }

    private ObjectNode failedAtomic(String status) {
        ObjectNode carrier = baseCarrier(context, "NO_RECORD", "failure-" + status, null);
        ((ObjectNode) carrier.path("resourceExecution")).removeAll();
        ((ObjectNode) carrier.path("resourceExecution")).put("status", status)
                .put("reasonCode", "PROVIDER_" + status)
                .put("attemptedAt", "2026-09-04T00:00:00Z")
                .put("completedAt", "2026-09-04T00:00:01Z");
        ((ObjectNode) carrier.path("resourceAvailability")).removeAll();
        ((ObjectNode) carrier.path("resourceAvailability")).put("status", "UNKNOWN")
                .put("reasonCode", "NOT_CHECKED");
        ((ObjectNode) carrier.path("queryOutcome")).removeAll();
        ((ObjectNode) carrier.path("queryOutcome")).put("status", "NOT_QUERIED")
                .put("recordCount", 0);
        ((ObjectNode) carrier.path("resourceApplicability")).removeAll();
        ((ObjectNode) carrier.path("resourceApplicability")).put("status", "UNKNOWN")
                .put("reasonCode", "NOT_EVALUATED");
        return adapter.adapt(context, carrier);
    }

    private ObjectNode baseCarrier(ObjectNode evidenceContext,
                                   String queryStatus,
                                   String recordSuffix,
                                   String driftedResourceId) {
        JsonNode sidecar = resource("CLINVAR_COMPARATOR_SIDECAR_2026_03_05_GRCH38");
        ObjectNode carrier = mapper.createObjectNode();
        carrier.put("evidenceType", "CLINICAL_DATABASE");
        carrier.set("queryAllele",
                evidenceContext.path("variant").path("evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source");
        source.put("sourceId", sidecar.path("resourceId").asText());
        source.put("resourceVersion", sidecar.path("resourceVersion").asText());
        source.put("resourceSha256", driftedResourceId != null
                && driftedResourceId.equals(sidecar.path("resourceId").asText())
                ? "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                : sidecar.path("identityFingerprintSha256").asText());
        source.put("retrievedAt", "2026-09-04T00:00:02Z");
        ArrayNode records = source.putArray("sourceRecords");
        if ("HIT".equals(queryStatus)) {
            ObjectNode locator = locator("assertion:" + recordSuffix,
                    "SCV-" + recordSuffix, null);
            ObjectNode record = records.addObject();
            record.put("recordId", "SCV-" + recordSuffix);
            record.set("locator", locator);
        }
        ArrayNode locators = source.putArray("sourceLocators");
        for (JsonNode pinned : profile.path("resources")) {
            String resourceId = pinned.path("resourceId").asText();
            String hash = resourceId.equals(driftedResourceId)
                    ? "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
                    : pinned.path("identityFingerprintSha256").asText();
            locators.add(locator("resource-identity:" + resourceId + "@"
                    + pinned.path("assembly").asText(), resourceId, hash));
        }
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED")
                .put("attemptedAt", "2026-09-04T00:00:00Z")
                .put("completedAt", "2026-09-04T00:00:01Z");
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        ObjectNode fingerprint = mapper.createObjectNode();
        fingerprint.put("recordSuffix", recordSuffix);
        fingerprint.put("queryStatus", queryStatus);
        carrier.putObject("queryOutcome").put("status", queryStatus)
                .put("recordCount", records.size())
                .put("queryFingerprint", adapter.canonicalSha256(fingerprint));
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        carrier.putArray("measurements");
        carrier.put("producedAt", "2026-09-04T00:00:02Z");
        carrier.put("sourcePayloadSha256", adapter.canonicalSha256(fingerprint));
        return carrier;
    }

    private ObjectNode locator(String value, String recordId, String artifactHash) {
        ObjectNode locator = mapper.createObjectNode();
        locator.put("locatorType", "DATABASE_RECORD");
        locator.put("locatorValue", value);
        locator.put("sourceRecordId", recordId);
        if (artifactHash != null) locator.put("artifactSha256", artifactHash);
        locator.put("retrievedAt", "2026-09-04T00:00:02Z");
        return locator;
    }

    private void addMeasurement(ObjectNode carrier, String metricId, String value) {
        ObjectNode measurement = ((ArrayNode) carrier.path("measurements")).addObject();
        measurement.put("metricId", metricId);
        measurement.put("rawValue", value);
        measurement.put("normalizedValue", value);
        measurement.put("calibrationProfileId", PROFILE_ID);
    }

    private JsonNode resource(String id) {
        for (JsonNode value : profile.path("resources")) {
            if (id.equals(value.path("resourceId").asText())) return value;
        }
        throw new AssertionError("resource not found: " + id);
    }

    private void setAllele(ObjectNode value,
                           String assembly,
                           String contig,
                           int position,
                           String reference,
                           String alternate) {
        ObjectNode variant = (ObjectNode) value.path("variant");
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele")) {
            ObjectNode allele = (ObjectNode) variant.path(field);
            allele.put("assembly", assembly).put("contig", contig).put("position", position)
                    .put("reference", reference).put("alternate", alternate);
        }
        ((ObjectNode) variant.path("assemblyLineage")).removeAll();
        ((ObjectNode) variant.path("assemblyLineage")).put("mappingStatus", "NATIVE");
    }

    private void rehashContext(ObjectNode value) {
        ObjectNode variant = (ObjectNode) value.path("variant");
        ObjectNode normalizer = mapper.createObjectNode();
        JsonNode normalization = variant.path("normalization");
        for (String field : List.of(
                "method", "methodVersion", "referenceSequenceSha256", "refValidation")) {
            normalizer.set(field, normalization.path(field).deepCopy());
        }
        ObjectNode alleleProjection = mapper.createObjectNode();
        alleleProjection.set("normalizedAllele", variant.path("normalizedAllele").deepCopy());
        alleleProjection.set("normalizer", normalizer);
        String alleleId = "allele-sha256-" + adapter.canonicalSha256(alleleProjection);
        variant.put("alleleId", alleleId);

        ObjectNode projection = mapper.createObjectNode();
        projection.set("scope", value.path("scope").deepCopy());
        projection.put("alleleId", alleleId);
        projection.put("hgncId", value.path("gene").path("hgncId").asText());
        projection.put("mondoId", value.path("disease").path("mondoId").asText());
        projection.put("moi", value.path("moi").asText());
        ObjectNode transcript = projection.putObject("transcript");
        transcript.put("accession", value.path("transcript").path("accession").asText());
        transcript.set("version", value.path("transcript").path("version").deepCopy());
        projection.put("patientContextId",
                value.path("patientContext").path("patientContextId").asText());
        JsonNode familyId = value.path("patientContext").get("familyContextId");
        if (familyId == null) projection.putNull("familyContextId");
        else projection.set("familyContextId", familyId.deepCopy());
        value.put("contextId", "context-sha256-" + adapter.canonicalSha256(projection));
    }

    private ObjectNode criterion(List<ObjectNode> evaluations, String id) {
        return evaluations.stream().filter(value -> id.equals(value.path("criterionId").asText()))
                .findFirst().orElseThrow();
    }

    private String status(ObjectNode evaluation) {
        return evaluation.path("evaluationResult").path("status").asText();
    }

    private void assertResult(ObjectNode evaluation, String expected, String baseStrength) {
        assertEquals(expected, status(evaluation));
        assertEquals(baseStrength, evaluation.path("baseStrength").asText());
        assertEquals("MET".equals(expected) ? baseStrength : "NONE",
                evaluation.path("appliedStrength").asText());
        assertEquals("APPLICABLE",
                evaluation.path("criterionApplicability").path("status").asText());
    }

    private void assertBlocked(List<ObjectNode> evaluations,
                               String reason,
                               String applicability) {
        assertEquals(2, evaluations.size());
        for (ObjectNode evaluation : evaluations) {
            assertEquals(applicability,
                    evaluation.path("criterionApplicability").path("status").asText());
            assertEquals(reason,
                    evaluation.path("criterionApplicability").path("reasonCode").asText());
            assertEquals("NOT_EVALUATED", status(evaluation));
            assertEquals(reason,
                    evaluation.path("evaluationResult").path("reasonCode").asText());
            assertEquals("NONE", evaluation.path("appliedStrength").asText());
            assertEquals("NOT_RUN",
                    evaluation.path("deterministicTrace").path("executionStatus").asText());
        }
    }

    private void assertFrozenRuleBindings(List<ObjectNode> evaluations) {
        ObjectNode ps1 = criterion(evaluations, PS1);
        ObjectNode pm5 = criterion(evaluations, PM5);
        assertEquals("STRONG", ps1.path("baseStrength").asText());
        assertEquals("MODERATE", pm5.path("baseStrength").asText());
        assertEquals("acmg-rule-registry-e3-k1b-2026-09-04",
                ps1.path("ruleBinding").path("ruleRegistryId").asText());
        assertEquals(List.of("authority-clingen-svi-splicing-2023"),
                textValues(ps1.path("ruleBinding").path("authoritySourceIds")));
        assertEquals(List.of("authority-acmg-amp-2015-richards",
                        "authority-clingen-svi-splicing-2023"),
                textValues(pm5.path("ruleBinding").path("authoritySourceIds")));
        for (ObjectNode value : evaluations) {
            assertTrue(value.path("evaluationId").asText()
                    .matches("^evaluation-sha256-[0-9a-f]{64}$"));
        }
    }

    private List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        Collections.sort(values);
        return values;
    }
}
