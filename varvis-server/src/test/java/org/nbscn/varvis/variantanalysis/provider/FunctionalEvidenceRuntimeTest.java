package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import static org.junit.jupiter.api.Assertions.*;

/** Public assay rows with synthetic genomic coordinates and stored annotations. No real VEP or clinical acceptance. */
class FunctionalEvidenceRuntimeTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final FunctionalEvidenceRuntime runtime = new FunctionalEvidenceRuntime(mapper);
    ObjectNode policy;
    ObjectNode facts;

    @BeforeEach void load() throws Exception {
        try (var input = getClass().getResourceAsStream("/acmg/generalization/functional-pten-developer-synthetic-model-v1.json")) {
            assertNotNull(input);
            JsonNode fixture = mapper.readTree(input);
            assertNotNull(fixture);
            policy = (ObjectNode) fixture.path("policy").deepCopy();
            facts = (ObjectNode) fixture.path("facts").deepCopy();
        }
        bind();
    }

    @Test void prospectivelyFixedPublicAssayRowsMeetModerateOnSyntheticDnaModel() throws Exception {
        for (String key : List.of("R130Q", "C124S", "G129E")) {
            ObjectNode result = run(key);
            assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
            assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText());
            assertEquals(1, result.path("providerCalls").asInt());
            assertEquals(1, result.path("evaluatorCalls").asInt());
            assertEquals(1, result.path("atomicEvidence").size());
            assertEquals(2, result.path("reviewRecords").size());
            assertEquals("REQUIRED", result.at("/evaluation/review/status").asText());
            assertFalse(result.path("classificationEnabled").asBoolean());
            assertFalse(result.path("adoptionSupported").asBoolean());
            assertEquals("ACMG_AMP_2015:BS3", result.at("/disabledCriteria/0/criterionId").asText());
            assertEquals("NATURAL_LOG_WT_NORMALIZED_FITNESS", result.at("/atomicEvidence/0/measurements/0/unit").asText());
        }
    }

    @Test void measuredG129eSurvivesNonemptyImputedAndConcordanceOnlyQuality() throws Exception {
        ObjectNode result = run("G129E");
        assertEquals("-5.4097635159999999", result.at("/atomicEvidence/0/measurements/0/rawValue").asText());
        assertEquals("-3.2014190450000002", result.at("/factRecord/sourceCells/K/raw").asText());
        assertEquals("Concordance Filter", result.at("/factRecord/sourceCells/J/raw").asText());
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText());
    }

    @Test void prospectivelyFixedAssayOnlyControlsKeepNegativeQualityAndMissingSeparate() {
        assertEquals("NOT_MET", FunctionalEvidenceRuntime.observedStatus(policy, row("D24E")));
        assertEquals("FUNCTIONAL_MEASURED_QUALITY_FAILED_OR_UNKNOWN", FunctionalEvidenceRuntime.observedStatus(policy, row("M1L")));
        assertEquals("FUNCTIONAL_ACCEPTABLE_MEASURED_SCORE_MISSING", FunctionalEvidenceRuntime.observedStatus(policy, row("M1A")));
    }

    @Test void exactDecimalBoundaryNeverRoundsForThreshold() throws Exception {
        for (String score : List.of("-1.11", "-1.1100000000000001", "-1.1099999999999999")) {
            ((ObjectNode) row("R130Q").at("/sourceCells/G")).put("raw", score);
            bind();
            assertEquals(score.equals("-1.1099999999999999") ? "NOT_MET" : "MET", run("R130Q").at("/evaluation/evaluationResult/status").asText());
        }
    }

    @Test void negativeResultIsOnlyThisAssayAndNeverBs3() throws Exception {
        ObjectNode result = run("D24E");
        assertEquals("NOT_MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("PS3", result.at("/evaluation/ruleBinding/currentGeneralDesignation").asText());
        assertFalse(result.has("bs3"));
    }

    @Test void measuredValueCannotComeFromKOrTextAndBooleanCannotComeFromTruthiness() throws Exception {
        for (String type : List.of("s", "n", "missing")) {
            ((ObjectNode) row("R130Q").at("/sourceCells/I")).put("type", type).put("raw", "1");
            bind();
            assertReason(run("R130Q"), "FUNCTIONAL_MEASURED_QUALITY_FAILED_OR_UNKNOWN");
        }
        ((ObjectNode) row("R130Q").at("/sourceCells/I")).put("type", "b").put("raw", "true");
        bind();
        assertReason(run("R130Q"), "FUNCTIONAL_MEASURED_QUALITY_FAILED_OR_UNKNOWN");
        ((ObjectNode) row("R130Q").at("/sourceCells/I")).put("raw", "1");
        for (String raw : List.of("NA", "NaN", "Infinity", "")) {
            ((ObjectNode) row("R130Q").at("/sourceCells/G")).put("type", "n").put("raw", raw);
            ((ObjectNode) row("R130Q").at("/sourceCells/K")).put("type", "n").put("raw", "-5");
            bind();
            assertReason(run("R130Q"), "FUNCTIONAL_ACCEPTABLE_MEASURED_SCORE_MISSING");
        }
    }

    @Test void policyCannotSilentlySwapMeasuredAndImputedSourceColumns() throws Exception {
        ((ObjectNode) policy.at("/assay/sourceColumns")).put("measuredScore", "K").put("imputedScore", "G");
        assertReason(run("R130Q"), "FUNCTIONAL_MEASUREMENT_COLUMN_BINDING_MISMATCH");
    }

    @Test void experimentalYeastCodonNeedNotEqualNativeAlternateCodon() throws Exception {
        ObjectNode record = row("R130Q");
        record.put("experimentalMutantCodon", "CAG");
        ((ObjectNode) record.at("/sourceCells/E")).put("raw", "CAG");
        bind();
        ObjectNode result = run("R130Q");
        assertEquals("CAA", result.at("/proteinIdentity/altCodon").asText());
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
    }

    @Test void wrongAssayCodonAndWrongRawProteinIdentityCannotTransfer() throws Exception {
        row("R130Q").put("experimentalMutantCodon", "GAG");
        ((ObjectNode) row("R130Q").at("/sourceCells/E")).put("raw", "GAG");
        bind();
        assertReason(run("R130Q"), "FUNCTIONAL_ASSAY_PROTEIN_IDENTITY_MISMATCH");
        load();
        ((ObjectNode) row("R130Q").at("/sourceCells/B")).put("raw", "Arg130Glu");
        bind();
        assertReason(run("R130Q"), "FUNCTIONAL_RAW_IDENTITY_CELL_MISMATCH");
    }

    @Test void sourceChangesAndWrongModelCannotReusePinnedFacts() throws Exception {
        ((ObjectNode) row("R130Q").at("/sourceCells/G")).put("raw", "-8");
        assertReason(run("R130Q"), "FUNCTIONAL_FACT_BUNDLE_IDENTITY_INVALID");
        load();
        ((ObjectNode) facts.path("nativeModel")).put("protein", "NP_000305.4");
        bind();
        assertReason(run("R130Q"), "FUNCTIONAL_MODEL_PROTEIN_MISMATCH");
    }

    @Test void duplicateStudyRowAndDuplicateProteinCannotIncreaseStrength() throws Exception {
        facts.withArray("records").add(row("R130Q").deepCopy());
        bind();
        assertReason(run("R130Q"), "FUNCTIONAL_FACT_RECORD_IDENTITY_INVALID");
    }

    @Test void unresolvedConflictOrUnreviewedConstructNeverEvaluates() throws Exception {
        ((ObjectNode) row("R130Q").path("conflictReview")).put("status", "CONFLICTING");
        bind();
        assertReason(run("R130Q"), "FUNCTIONAL_ASSAY_CONFLICT_REVIEW_REQUIRED");
        load();
        ((ObjectNode) facts.path("method")).put("constructTransfer", "ASSUMED_EQUIVALENT");
        bind();
        assertReason(run("R130Q"), "FUNCTIONAL_METHOD_OPERATOR_UNSUPPORTED");
    }

    @Test void unsupportedCalibrationStrengthAndAdditionalThresholdOperatorsRefuse() throws Exception {
        ((ObjectNode) facts.path("method")).put("calibrationStatus", "RECALIBRATED_FROM_CLINVAR_LABELS");
        bind();
        assertReason(run("R130Q"), "FUNCTIONAL_METHOD_OPERATOR_UNSUPPORTED");
        load();
        ((ObjectNode) policy.at("/assay/threshold")).put("upperBound", "-0.5");
        assertReason(run("R130Q"), "FUNCTIONAL_POLICY_OPERATOR_UNSUPPORTED");
        load();
        ((ObjectNode) policy.at("/assay/threshold")).put("strength", "VERY_STRONG");
        assertReason(run("R130Q"), "FUNCTIONAL_POLICY_OPERATOR_UNSUPPORTED");
    }

    @Test void receiptCannotBeReusedAcrossPolicyContextOrCriterion() throws Exception {
        VariantInterpretation candidate = candidate("R130Q");
        ObjectNode context = context(candidate);
        ObjectNode receipt = receipt(context);
        policy.put("policyVersion", "2");
        assertReason(runtime.evaluate(context, candidate, policy, receipt, facts), "FUNCTIONAL_SELECTION_POLICY_MISMATCH");
        load();
        receipt.put("contextId", "context-sha256-" + "b".repeat(64));
        assertReason(runtime.evaluate(context, candidate, policy, receipt, facts), "FUNCTIONAL_SELECTION_CONTEXT_MISMATCH");
        receipt = receipt(context);
        receipt.putArray("criterionIds").add("ACMG_AMP_2015:BS3");
        refreshReceipt(receipt);
        assertReason(runtime.evaluate(context, candidate, policy, receipt, facts), "FUNCTIONAL_SELECTION_IDENTITY_INVALID");
    }

    @Test void directoryGapsAndUnreviewedPoliciesCannotExecute() throws Exception {
        VariantInterpretation candidate = candidate("R130Q");
        ObjectNode context = context(candidate);
        ObjectNode receipt = receipt(context);
        receipt.put("outcome", "SPECIALIZED_POLICY_GAP").put("reasonCode", "APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED");
        refreshReceipt(receipt);
        assertReason(runtime.evaluate(context, candidate, policy, receipt, facts), "APPLICABLE_SPECIALIZED_CLAUSE_NOT_IMPLEMENTED");
        ((ObjectNode) policy.path("review")).put("status", "DRAFT");
        assertReason(run("R130Q"), "FUNCTIONAL_POLICY_IDENTITY_OR_REVIEW_INVALID");
    }

    @Test void wrongDnaAlleleAndProteinAnnotationCannotBorrowAnAssayRow() throws Exception {
        VariantInterpretation candidate = candidate("R130Q");
        ObjectNode other = context(candidate("G129E"));
        assertReason(runtime.evaluate(other, candidate, policy, receipt(other), facts), "FUNCTIONAL_INPUT_INTEGRITY_INVALID");
        ObjectNode context = context(candidate);
        candidate = candidate("R130Q", "NP_000305.3:p.Arg130Glu", "missense_variant");
        assertReason(runtime.evaluate(context, candidate, policy, receipt(context), facts), "FUNCTIONAL_CODING_PROTEIN_IDENTITY_MISMATCH");
    }

    @Test void splicingStartAndDuplicateAnnotationAreOutsideThisProteinAssayPath() throws Exception {
        assertReason(run("M1L"), "FUNCTIONAL_START_OR_NONCODING_EFFECT_NOT_SUPPORTED");
        VariantInterpretation candidate = candidate("R130Q");
        ObjectNode context = context(candidate);
        candidate = candidate("R130Q", row("R130Q").path("proteinHgvs").asText(), "missense_variant&splice_region_variant");
        assertReason(runtime.evaluate(context, candidate, policy, receipt(context), facts), "FUNCTIONAL_PROTEIN_EFFECT_NOT_SUPPORTED");
        candidate = candidate("R130Q");
        var original = candidate.getTranscriptAnnotations().get(0);
        candidate.applyTranscriptAnnotations(List.of(original, original));
        assertReason(runtime.evaluate(context, candidate, policy, receipt(context), facts), "FUNCTIONAL_SELECTED_TRANSCRIPT_NOT_UNIQUE");
    }

    @Test void minusStrandUsesSharedNativeMappingAndSameOriginalAssayRow() throws Exception {
        ((ObjectNode) facts.path("nativeModel")).put("strand", -1);
        bind();
        ObjectNode result = run("R130Q");
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(389, result.at("/proteinIdentity/cdsPosition").asInt());
        assertEquals("C", result.at("/proteinIdentity/allele/reference").asText());
        assertEquals("T", result.at("/proteinIdentity/allele/alternate").asText());
    }

    @Test void frequencyOnlySelectionAndWrongDiseaseRemainIndependentGates() throws Exception {
        VariantInterpretation candidate = candidate("R130Q");
        ObjectNode context = context(candidate);
        ((ObjectNode) context.path("transcript")).put("selectionPolicy", "PM2_CONTEXT_ONLY");
        assertReason(runtime.evaluate(context, candidate, policy, receipt(context), facts), "FUNCTIONAL_TRANSCRIPT_CONFIRMATION_REQUIRED");
        context = context(candidate);
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0005045");
        context.put("contextId", adapter.canonicalContextId(context));
        assertReason(runtime.evaluate(context, candidate, policy, receipt(context), facts), "FUNCTIONAL_POLICY_CONTEXT_MISMATCH");
    }

    @Test void originalPublishedRangesUseBoundsAndDoNotInterpolate() throws Exception {
        ((ObjectNode) policy.path("assay")).put("representation", "REVIEWED_PUBLISHED_RANGE").put("qualityGate", "REVIEWED_BOUND").remove("sourceColumns");
        ((ObjectNode) policy.at("/assay/threshold")).put("decimal", "40");
        ((ObjectNode) facts.path("method")).put("unit", "percent_of_study_WT");
        ((ObjectNode) policy.path("assay")).put("unit", "percent_of_study_WT");
        ObjectNode range = row("R130Q").putObject("publishedRange").put("lower", "20").put("upper", "40")
                .put("lowerInclusive", true).put("upperInclusive", true).put("reviewStatus", "CLEAR_FOR_RECORDED_BOUND_ONLY")
                .put("sourceLocator", "SYNTHETIC ENGINEERING RANGE ONLY").put("unit", "percent_of_study_WT");
        bind();
        assertEquals("MET", run("R130Q").at("/evaluation/evaluationResult/status").asText());
        range.put("upper", "41"); bind();
        assertReason(run("R130Q"), "FUNCTIONAL_RANGE_CROSSES_THRESHOLD");
        range.put("lower", "41"); bind();
        assertEquals("NOT_MET", run("R130Q").at("/evaluation/evaluationResult/status").asText());
    }

    @Test void strictOpenRangeEndpointsDoNotInventBoundaryValues() throws Exception {
        ((ObjectNode) policy.path("assay")).put("representation", "REVIEWED_PUBLISHED_RANGE").put("qualityGate", "REVIEWED_BOUND").remove("sourceColumns");
        ((ObjectNode) policy.at("/assay/threshold")).put("operator", "LT").put("decimal", "40");
        ((ObjectNode) facts.path("method")).put("unit", "percent_of_study_WT");
        ((ObjectNode) policy.path("assay")).put("unit", "percent_of_study_WT");
        ObjectNode range = row("R130Q").putObject("publishedRange").put("lower", "20").put("upper", "40")
                .put("lowerInclusive", true).put("upperInclusive", false).put("reviewStatus", "CLEAR_FOR_RECORDED_BOUND_ONLY").put("sourceLocator", "SYNTHETIC").put("unit", "percent_of_study_WT");
        assertEquals("MET", FunctionalEvidenceRuntime.observedStatus(policy, row("R130Q")));
        bind();
        ObjectNode result = run("R130Q");
        assertEquals("[20,40)", result.at("/atomicEvidence/0/measurements/0/rawValue").asText(), result.toPrettyString());
        assertTrue(result.at("/atomicEvidence/0/source/sourceLocators/0/locatorValue").asText().endsWith("#SYNTHETIC"));
        range.put("upperInclusive", true);
        assertEquals("FUNCTIONAL_RANGE_CROSSES_THRESHOLD", FunctionalEvidenceRuntime.observedStatus(policy, row("R130Q")));
    }

    @Test void registryBindingCannotMasqueradeAsSpecificationCatalog() throws Exception {
        ((ObjectNode) policy.path("registryBinding")).put("registrySha256", "b".repeat(64));
        assertReason(run("R130Q"), "FUNCTIONAL_REGISTRY_IDENTITY_INVALID");
    }

    @Test void pinnedCacheModelDoesNotBecomeFunctionalPolicyAuthority() throws Exception {
        OfflineCacheModelTestSupport.add((ObjectNode) facts.path("nativeModel"), policy);
        bind();
        ObjectNode result = run("R130Q");
        assertEquals("MET", result.at("/evaluation/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals("MODERATE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("1.0.5", FunctionalEvidenceRuntime.VERSION);
        assertFalse(FunctionalEvidencePolicy.sourcesValid(facts.at("/nativeModel/sourceRecords")));
    }

    @Test void cacheProofFailureAndMissingIndependentPolicySourceStopBeforeFactEvaluation() throws Exception {
        ObjectNode cache = OfflineCacheModelTestSupport.add((ObjectNode) facts.path("nativeModel"), policy);
        cache.put("sourceKind", "UNKNOWN").put("url", "https://example.invalid/not-a-cache"); bind();
        ObjectNode result = run("R130Q");
        assertReason(result, "FUNCTIONAL_MODEL_SOURCES_UNBOUND");
        assertEquals(0, result.path("providerCalls").asInt());
        load(); OfflineCacheModelTestSupport.add((ObjectNode) facts.path("nativeModel"), policy);
        policy.withArray("sources").remove(policy.path("sources").size() - 1); bind();
        assertReason(run("R130Q"), "FUNCTIONAL_MODEL_SOURCES_UNBOUND");
    }

    @Test void cacheRecordCannotBePromotedToAnAssayAuthority() throws Exception {
        ObjectNode cache = OfflineCacheModelTestSupport.add((ObjectNode) facts.path("nativeModel"), policy);
        policy.withArray("sources").add(cache.deepCopy()); bind();
        assertReason(run("R130Q"), "FUNCTIONAL_POLICY_SOURCES_INVALID");
        ((ObjectNode) policy.path("sources").path(policy.path("sources").size() - 1))
                .put("url", "https://example.invalid/not-a-scientific-source"); bind();
        assertReason(run("R130Q"), "FUNCTIONAL_MODEL_SOURCES_UNBOUND");
    }

    private ObjectNode run(String key) throws Exception {
        VariantInterpretation candidate = candidate(key);
        ObjectNode context = context(candidate);
        return runtime.evaluate(context, candidate, policy, receipt(context), facts);
    }

    private ObjectNode row(String key) {
        for (JsonNode row : facts.path("records")) if (key.equals(row.path("proteinVariantKey").asText())) return (ObjectNode) row;
        throw new IllegalArgumentException(key);
    }

    private void bind() {
        policy.put("nativeModelSha256", adapter.canonicalSha256(facts.path("nativeModel")));
        policy.put("annotationResourceSha256", adapter.canonicalSha256(facts.path("annotationResource")));
        policy.putObject("methodBinding").put("methodId", facts.at("/method/methodId").asText()).put("canonicalSha256", adapter.canonicalSha256(facts.path("method")));
        policy.putObject("factBundle").put("factBundleId", facts.path("factBundleId").asText()).put("version", facts.path("version").asText())
                .put("canonicalSha256", adapter.canonicalSha256(facts));
    }

    VariantInterpretation candidate(String key) {
        return candidate(key, row(key).path("proteinHgvs").asText(), "missense_variant");
    }

    private VariantInterpretation candidate(String key, String hgvsp, String consequence) {
        int c = switch (key) { case "R130Q" -> 389; case "C124S" -> 371; case "G129E" -> 386; case "D24E" -> 72; case "M1L" -> 1; default -> throw new IllegalArgumentException(key); };
        String alternate = switch (key) { case "R130Q", "G129E" -> "A"; case "C124S", "M1L" -> "C"; case "D24E" -> "G"; default -> throw new IllegalArgumentException(key); };
        String cds = facts.at("/nativeModel/cdsSequence").asText();
        String ref = cds.substring(c - 1, c);
        int start = (c - 1) / 3 * 3;
        String refCodon = cds.substring(start, start + 3);
        var altCodon = new StringBuilder(refCodon); altCodon.setCharAt((c - 1) % 3, alternate.charAt(0));
        boolean minus = facts.at("/nativeModel/strand").asInt() == -1;
        var variant = RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh38", "10", minus ? 2212 - c : 999 + c,
                minus ? TruncatingSequence.reverseComplement(ref) : ref, minus ? TruncatingSequence.reverseComplement(alternate) : alternate));
        variant.applyTranscriptAnnotations(List.of(new TranscriptConsequence("PTEN", "HGNC:9588", "NM_000314",
                "NM_000314.8:c." + c + ref + ">" + alternate, hgvsp, consequence,
                "HGNC:9588", "NM_000314", "RefSeq", "MODERATE", "1/1", null, true, true, false,
                key.substring(1, key.length() - 1), key.charAt(0) + "/" + key.charAt(key.length() - 1), refCodon + "/" + altCodon,
                null, null, null, "VEP_CACHED", "COMPLETED", "116.0", Instant.parse("2026-09-13T16:00:00Z"))));
        return variant;
    }

    ObjectNode context(VariantInterpretation candidate) throws Exception {
        ObjectNode context = RuntimeEvidenceV2ProviderWiringTest.syntheticContext(mapper, adapter, candidate.getObservation().getKey(), "NM_000314");
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:9588").put("symbol", "PTEN");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0017623").put("label", "PTEN hamartoma tumor syndrome");
        context.put("moi", "AUTOSOMAL_DOMINANT");
        ((ObjectNode) context.path("transcript")).put("version", 8).put("annotationSourceVersion", "116.0");
        context.put("contextId", adapter.canonicalContextId(context));
        return context;
    }

    private ObjectNode receipt(ObjectNode context) {
        ObjectNode receipt = mapper.createObjectNode().put("schemaVersion", AcmgSpecificationSelector.SCHEMA)
                .put("contextId", context.path("contextId").asText()).put("outcome", "SPECIALIZED_POLICY_SELECTED")
                .put("catalogId", "SYNTHETIC_REVIEWED_DEVELOPER_CATALOG").put("catalogVersion", "1").put("catalogSha256", "a".repeat(64))
                .put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("policyVersion").asText())
                .put("policySha256", adapter.canonicalSha256(policy));
        receipt.putArray("criterionIds").add(FunctionalEvidenceRuntime.PS3);
        refreshReceipt(receipt);
        return receipt;
    }

    private void refreshReceipt(ObjectNode receipt) {
        receipt.remove("selectionId");
        receipt.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(receipt));
    }

    private static void assertReason(ObjectNode result, String expected) {
        assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText(), result.toPrettyString());
        assertEquals(expected, result.at("/evaluationResult/reasonCode").asText(), result.toPrettyString());
        assertEquals(0, result.path("evaluatorCalls").asInt());
        assertFalse(result.has("evaluation"));
    }
}
