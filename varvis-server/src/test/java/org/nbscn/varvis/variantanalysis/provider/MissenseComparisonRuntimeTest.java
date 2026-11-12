package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.persistence.AcmgBusinessStore;
import static org.junit.jupiter.api.Assertions.*;

/** Existing public HNF1A source rows, repackaged in an explicitly synthetic developer policy.
 * Stub raw SpliceAI responses are engineering tests, never new source or clinical acceptance. */
class MissenseComparisonRuntimeTest {
    @Test void ps1AutomaticScreenRetainsSameProteinRuleAndStrongResult() throws Exception {
        setup(false); automatedRnaPolicy();
        var result=run();assertEquals("MET",status(result),result.toPrettyString());
        assertEquals("STRONG",evaluation(result).path("appliedStrength").asText());
        assertFalse(result.at("/rnaApplicability/pairSpecificManualApprovalRequired").asBoolean(true));
        assertFalse(result.at("/rnaApplicability/normalRnaClaimed").asBoolean(true));
        assertEquals(2,result.path("spliceEvidence").size());
    }
    @Test void automatedRnaUsesSelectedPolicyThresholdRatherThanHardcodedDiseaseThreshold() throws Exception {
        setup(true); automatedRnaPolicy();
        targetScore = "0.15";
        ((ObjectNode) policy.path("splicePolicy")).put("maximumScore", "0.2"); bind();
        assertEquals("MET", status(run()));
        ((ObjectNode) policy.path("splicePolicy")).put("maximumScore", "0.1"); bind();
        reason("UNRESOLVED_SPLICE_MECHANISM");
    }
    private void automatedRnaPolicy() {
        policy.putObject("rnaApplicability").put("mode", MissenseRnaApplicability.MODE)
                .put("sourceCoverage", MissenseRnaApplicability.COVERAGE).set("sourceIds", policy.at("/splicePolicy/sourceIds").deepCopy());
        ((ObjectNode) policy.path("splicePolicy")).put("operator", "LT").put("maximumScore", "0.1");
        ((ObjectNode) material.path("rnaReview")).put("mode", MissenseRnaApplicability.MODE)
                .put("sourceCoverage", MissenseRnaApplicability.COVERAGE).put("status", MissenseRnaApplicability.STATUS).putArray("comparisons");
        bind();
    }

    @Test void policyRnaScreenEvaluatesWithoutManualPairButKeepsBothActualScores() throws Exception {
        setup(true); directResiduePolicy(); automatedRnaPolicy();
        ObjectNode result = run();
        assertEquals("MET", status(result), result.toPrettyString());
        assertFalse(result.at("/rnaApplicability/normalRnaClaimed").asBoolean(true));
        assertFalse(result.at("/rnaApplicability/pairSpecificManualApprovalRequired").asBoolean(true));
        assertEquals(2, result.path("spliceEvidence").size()); assertEquals(1, rawCalls);
    }

    @ParameterizedTest @ValueSource(strings={"TARGET_HIGH", "REFERENCE_HIGH", "EXACT_THRESHOLD", "MISSING_RAW", "TARGET_CONCERN", "REFERENCE_CONCERN", "NO_POLICY", "NORMAL_RNA", "COVERAGE", "MIXED_PAIR"})
    void automatedRnaDoesNotTurnMissingConflictingOrHighScoresIntoNormalRna(String fault) throws Exception {
        automatedRnaRejection(true,fault);
    }
    @ParameterizedTest @ValueSource(strings={"TARGET_HIGH", "REFERENCE_HIGH", "EXACT_THRESHOLD", "MISSING_RAW", "TARGET_CONCERN", "REFERENCE_CONCERN", "NO_POLICY", "NORMAL_RNA", "COVERAGE", "MIXED_PAIR"})
    void ps1AutomatedRnaRetainsBothSidesAndSourceConcernGates(String fault) throws Exception {
        automatedRnaRejection(false,fault);
    }
    private void automatedRnaRejection(boolean pm5,String fault) throws Exception {
        setup(pm5); if(pm5)directResiduePolicy(); automatedRnaPolicy();
        String expected = "MISSENSE_AUTOMATED_RNA_MATERIAL_INVALID";
        switch (fault) {
            case "TARGET_HIGH" -> { targetScore = "0.2"; expected = "UNRESOLVED_SPLICE_MECHANISM"; }
            case "REFERENCE_HIGH" -> { referenceScore = "0.2"; expected = "UNRESOLVED_SPLICE_MECHANISM"; }
            case "EXACT_THRESHOLD" -> { targetScore = "0.1"; expected = "UNRESOLVED_SPLICE_MECHANISM"; }
            case "MISSING_RAW" -> { rawFault = "MISSING"; expected = "SPLICE_EVIDENCE_UNAVAILABLE"; }
            case "TARGET_CONCERN", "REFERENCE_CONCERN" -> {
                ObjectNode concern = ((ObjectNode) material.path("rnaReview")).withArray("knownConcerns").addObject().put("reason", "TEST_ONLY ambiguous RNA report");
                concern.set("allele", fault.equals("TARGET_CONCERN") ? context.at("/variant/evidenceQueryAllele").deepCopy() : ref().path("allele").deepCopy());
                concern.set("sourceIds", policy.at("/splicePolicy/sourceIds").deepCopy()); expected = "PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW";
            }
            case "NO_POLICY" -> policy.remove("rnaApplicability");
            case "NORMAL_RNA" -> ((ObjectNode) material.path("rnaReview")).put("normalRnaClaimed", true);
            case "COVERAGE" -> ((ObjectNode) material.path("rnaReview")).put("sourceCoverage", "ALL_KNOWLEDGE");
            case "MIXED_PAIR" -> ((ObjectNode) material.path("rnaReview")).withArray("comparisons").addObject();
            default -> fail(fault);
        }
        bind(); reason(expected);
    }

    private void directResiduePolicy() {
        ObjectNode rule = (ObjectNode) policy.at("/rules/PM5");
        rule.put("mode", "SAME_RESIDUE_DIFFERENT_SUBSTITUTION_SINGLE_REFERENCE");
        rule.remove(List.of("matrixKind", "matrixSha256", "comparison", "failureStrengths"));
        material.remove(List.of("substitutionMatrix", "aminoAcidRelations"));
        bind();
    }

    @Test void directResidueRuleUsesClassificationWithoutInventingMatrixScores() throws Exception {
        setup(true); directResiduePolicy();
        ObjectNode result = run();
        assertEquals("MET", status(result), result.toPrettyString());
        assertEquals("SUPPORTING", evaluation(result).path("appliedStrength").asText());
        assertEquals("SAME_RESIDUE_DIFFERENT_SUBSTITUTION_SINGLE_REFERENCE", result.at("/proteinComparisons/0/ruleMode").asText());
        assertFalse(result.at("/proteinComparisons/0").has("matrixKind"));
        assertFalse(result.at("/proteinComparisons/0").has("targetSubstitutionScore"));
        assertEquals(1, rawCalls);
    }

    @Test void directResidueRulePReferenceIsModerateWithoutTripleWhitelist() throws Exception {
        setup(true); directResiduePolicy();
        ref().put("classification", "Pathogenic"); metric("clinvar.assertion.classification", "Pathogenic");
        ((ObjectNode) ref().path("independence")).put("classificationAfterExclusion", "PATHOGENIC"); bind();
        assertEquals("MODERATE", evaluation(run()).path("appliedStrength").asText());
    }

    @ParameterizedTest @ValueSource(strings={"matrixKind", "matrixSha256", "comparison", "failureStrengths"})
    void directResidueRuleRejectsMixedOperators(String field) throws Exception {
        setup(true); directResiduePolicy();
        ((ObjectNode) policy.at("/rules/PM5")).put(field, "UNEXPECTED"); bind();
        reason("MISSENSE_RULE_OPERATOR_UNSUPPORTED"); assertEquals(0, rawCalls);
    }

    @ParameterizedTest @ValueSource(strings={"REFERENCE", "TARGET", "REVIEW", "DEPENDENCY", "RAW"})
    void directResidueRuleDoesNotBypassQualificationOrSplicing(String fault) throws Exception {
        setup(true); directResiduePolicy(); String expected = "UNRESOLVED_SPLICE_MECHANISM";
        switch (fault) {
            case "REFERENCE" -> referenceScore = "0.2";
            case "TARGET" -> targetScore = "0.2";
            case "REVIEW" -> { ((ObjectNode) material.path("rnaReview")).putArray("comparisons"); expected = "RNA_REVIEW_NOT_AVAILABLE_FOR_COMPARISON"; }
            case "DEPENDENCY" -> { ((ObjectNode) ref().path("independence")).withArray("criteria").add("PM5"); expected = "REFERENCE_NOT_INDEPENDENT"; }
            case "RAW" -> { rawFault = "MISSING"; expected = "SPLICE_EVIDENCE_UNAVAILABLE"; }
            default -> fail(fault);
        }
        bind(); reason(expected);
    }

    @Test void directResidueModeCannotBeUsedAsPs1() {
        ((ObjectNode) policy.at("/rules/PS1")).put("mode", "SAME_RESIDUE_DIFFERENT_SUBSTITUTION_SINGLE_REFERENCE");
        bind(); reason("MISSENSE_RULE_OPERATOR_UNSUPPORTED"); assertEquals(0, rawCalls);
    }

    private ObjectNode separatedReference(boolean downgrade) {
        if (downgrade) {
            ref().put("classification", "Pathogenic"); metric("clinvar.assertion.classification", "Pathogenic");
            ((ObjectNode) ref().path("independence")).put("classificationAfterExclusion", "PATHOGENIC");
        }
        ObjectNode q = independentReference();
        ((ObjectNode) policy.path("referenceQualification")).put("classificationHandling", MissenseIndependentReference.RETAIN_OR_DOWNGRADE);
        q.put("schemaVersion", "missense-independent-reference-v2").put("adoptedClassification", "LIKELY_PATHOGENIC");
        ((ObjectNode) q.at("/evidence/1")).put("strength", "MODERATE");
        ((ObjectNode) ref().path("independence")).put("classificationAfterExclusion", "LIKELY_PATHOGENIC");
        bind(); return q;
    }

    @Test void independentlyDowngradedPs1UsesLpStrengthAndPreservesOriginal() {
        separatedReference(true); ObjectNode original = ref().path("sourceAssertion").deepCopy(); ObjectNode input = atomic();
        ObjectNode result = run(); assertEquals("MET", status(result), result.toPrettyString());
        assertEquals("MODERATE", evaluation(result).path("appliedStrength").asText());
        assertEquals("Pathogenic", result.at("/references/0/classification").asText());
        assertEquals("LIKELY_PATHOGENIC", result.at("/references/0/adoptedClassification").asText());
        assertEquals(original, result.at("/references/0/sourceAssertion")); assertEquals(input, atomic());
    }

    @Test void independentlyDowngradedPm5UsesLpStrength() throws Exception {
        setup(true); separatedReference(true); ObjectNode result = run();
        assertEquals("MET", status(result), result.toPrettyString());
        assertEquals("SUPPORTING", evaluation(result).path("appliedStrength").asText());
    }

    @ParameterizedTest @ValueSource(strings={"NO_OPT_IN", "UPGRADE", "WRONG_ADOPTED", "EXCLUSION", "OLD_SCHEMA"})
    void separatedClassificationCannotBypassReview(String change) throws Exception {
        if ("UPGRADE".equals(change)) setup(true);
        ObjectNode q = separatedReference(!"UPGRADE".equals(change));
        String expected = "REFERENCE_INDEPENDENT_CLASSIFICATION_MISMATCH";
        switch (change) {
            case "NO_OPT_IN" -> { ((ObjectNode) policy.path("referenceQualification")).remove("classificationHandling"); expected = "REFERENCE_ADOPTION_CLASSIFICATION_ROUTE_NOT_ENABLED"; }
            case "UPGRADE" -> { q.put("adoptedClassification", "PATHOGENIC"); expected = "REFERENCE_ADOPTION_CLASSIFICATION_CHANGE_UNSUPPORTED"; }
            case "WRONG_ADOPTED" -> q.put("adoptedClassification", "PATHOGENIC");
            case "EXCLUSION" -> ((ObjectNode) ref().path("independence")).put("classificationAfterExclusion", "PATHOGENIC");
            case "OLD_SCHEMA" -> { q.put("schemaVersion", "missense-independent-reference-v1"); expected = "REFERENCE_INDEPENDENT_REVIEW_MISSING"; }
            default -> throw new IllegalArgumentException(change);
        }
        bind(); reason(expected); assertEquals(0, rawCalls);
    }

    private void ceilingPolicy() {
        ObjectNode rule = (ObjectNode) policy.at("/rules/PM5");
        rule.put("matrixKind", "BLOSUM62").put("comparison", "TARGET_LTE_REFERENCE");
        ((ObjectNode) material.path("substitutionMatrix")).put("matrixKind", "BLOSUM62");
        rule.putObject("classificationCeiling").put("referenceClassification", "LIKELY_PATHOGENIC")
                .put("maximumTargetClassification", "LIKELY_PATHOGENIC").put("enforcement", "REFUSE_WITHOUT_FINAL_CLASSIFIER");
        bind();
    }

    @Test void actualLpWithFinalClassificationCeilingIsBlockedBeforeSplice() throws Exception {
        setup(true); separatedReference(true); ceilingPolicy();
        ObjectNode result = run(); assertEquals("NOT_EVALUATED", status(result));
        assertEquals("MISSENSE_LP_CLASSIFICATION_CEILING_NOT_IMPLEMENTED", evaluation(result).at("/evaluationResult/reasonCode").asText());
        assertEquals("REFUSE_WITHOUT_FINAL_CLASSIFIER", result.at("/classificationConstraint/enforcement").asText());
        assertEquals(0, rawCalls);
    }

    @Test void pReferenceNotBlockedByUnusedLpOption() throws Exception {
        setup(true); ref().put("classification", "Pathogenic"); metric("clinvar.assertion.classification", "Pathogenic");
        ((ObjectNode) ref().path("independence")).put("classificationAfterExclusion", "PATHOGENIC"); ceilingPolicy();
        ObjectNode result = run(); assertNotEquals("NOT_EVALUATED", status(result), result.toPrettyString());
        assertEquals(1, rawCalls);
    }

    @Test void ceilingCannotBeDowngradedToDisplayOnly() throws Exception {
        setup(true); ceilingPolicy(); ((ObjectNode) policy.at("/rules/PM5/classificationCeiling")).put("enforcement", "DISPLAY_ONLY");
        bind(); reason("MISSENSE_CLASSIFICATION_CEILING_POLICY_UNSUPPORTED"); assertEquals(0, rawCalls);
    }
    private ObjectNode independentReference() {
        metric("clinvar.assertion.reviewStatus", "criteria provided, single submitter");
        ObjectNode route = policy.putObject("referenceQualification").put("mode", "EXPERT_OR_REVIEWED_INDEPENDENT")
                .put("combination", "ACMG_2015_NON_LOF_V1").put("specificationVersion", "SYNTHETIC_SPEC_V1");
        sources(route);
        ObjectNode independence = (ObjectNode) ref().path("independence");
        independence.put("originalSpecificationVersion", "SYNTHETIC_SPEC_V1");
        independence.putArray("criteria").add("PS3").add("PS4");
        ObjectNode original = ref().putObject("sourceAssertion").put("scv", ref().path("scv").asText())
                .put("classification", ref().path("classification").asText()).put("reviewStatus", "criteria provided, single submitter")
                .put("dateLastEvaluated", ref().path("evaluationDate").asText());
        ObjectNode q = ref().putObject("qualification").put("schemaVersion", "missense-independent-reference-v1")
                .put("status", "REVIEWED").put("reviewId", "SYNTHETIC_REVIEW_ONLY")
                .put("combination", "ACMG_2015_NON_LOF_V1").put("specificationVersion", "SYNTHETIC_SPEC_V1")
                .put("sourceAssertionSha256", adapter.canonicalSha256(original))
                .put("conflictReview", "REVIEWED_NO_UNRESOLVED_CONFLICT")
                .put("independenceRationale", "Synthetic unrelated functional and case observations; no target/codon comparison.");
        sources(q);
        ArrayNode criteria = q.putArray("evidence");
        for (String code : List.of("PS3", "PS4")) {
            ObjectNode evidence = criteria.addObject().put("code", code).put("strength", "STRONG")
                    .put("independenceGroup", code).put("sourceLocator", "synthetic section " + code)
                    .put("rationale", "SYNTHETIC reviewed observation, not real scientific approval");
            sources(evidence);
        }
        if ("Likely Pathogenic".equalsIgnoreCase(ref().path("classification").asText().replace('_', ' ')))
            ((ObjectNode) q.at("/evidence/1")).put("strength", "MODERATE");
        bind(); return q;
    }

    @Test void reviewedOriginalEvidenceCanQualifyWithoutChangingClinvarReviewLabel() {
        independentReference(); ObjectNode before = atomic(); ObjectNode result = run();
        assertEquals("MET", status(result), result.toPrettyString());
        assertEquals(before, atomic());
        assertEquals("criteria provided, single submitter", metricValue(result.at("/atomicEvidence/0"), "clinvar.assertion.reviewStatus"));
        assertEquals("missense-independent-reference-v1", result.at("/references/0/qualification/schemaVersion").asText());
    }

    @Test void singleSubmitterLabelWithoutReviewedEvidenceRemainsBlocked() {
        metric("clinvar.assertion.reviewStatus", "criteria provided, single submitter");
        reason("REFERENCE_ASSERTION_CONFLICT"); assertEquals(0, rawCalls);
    }

    @ParameterizedTest @ValueSource(strings={"UNREVIEWED", "STALE", "WRONG_SCV", "NO_POLICY", "WRONG_SPEC", "LABEL_ONLY",
            "DUPLICATE", "SHARED_GROUP", "PS1", "PM5", "PP5", "BENIGN", "UNBOUND", "NO_LOCATOR", "BAD_STRENGTH",
            "INSUFFICIENT", "CLASSIFICATION", "CONFLICT_REVIEW", "CRITERIA_MISMATCH", "DE_NOVO_DUPLICATION"})
    void invalidIndependentEvidenceStopsBeforeSplice(String change) {
        ObjectNode q = independentReference(); ObjectNode row = (ObjectNode) q.at("/evidence/0");
        String expected;
        switch (change) {
            case "UNREVIEWED" -> { q.put("status", "UNREVIEWED"); expected = "REFERENCE_INDEPENDENT_REVIEW_MISSING"; }
            case "STALE" -> { q.put("sourceAssertionSha256", "f".repeat(64)); expected = "REFERENCE_INDEPENDENT_ASSERTION_BINDING_MISMATCH"; }
            case "WRONG_SCV" -> { ((ObjectNode) ref().path("sourceAssertion")).put("scv", "SCV999999999.1"); expected = "REFERENCE_INDEPENDENT_ASSERTION_BINDING_MISMATCH"; }
            case "NO_POLICY" -> { policy.remove("referenceQualification"); expected = "REFERENCE_INDEPENDENT_ROUTE_NOT_ENABLED"; }
            case "WRONG_SPEC" -> { q.put("specificationVersion", "OTHER"); expected = "REFERENCE_INDEPENDENT_SPECIFICATION_MISMATCH"; }
            case "LABEL_ONLY" -> { q.remove("evidence"); expected = "REFERENCE_INDEPENDENT_EVIDENCE_INCOMPLETE"; }
            case "DUPLICATE" -> { q.withArray("evidence").add(row.deepCopy()); expected = "REFERENCE_INDEPENDENT_EVIDENCE_INVALID_OR_DEPENDENT"; }
            case "SHARED_GROUP" -> { ((ObjectNode) q.at("/evidence/1")).put("independenceGroup", "PS3"); expected = "REFERENCE_INDEPENDENT_EVIDENCE_INVALID_OR_DEPENDENT"; }
            case "PS1", "PM5", "PP5", "BENIGN" -> { row.put("code", "BENIGN".equals(change) ? "BS3" : change); expected = "REFERENCE_INDEPENDENT_EVIDENCE_INVALID_OR_DEPENDENT"; }
            case "UNBOUND" -> { row.putArray("sourceIds").add("UNREGISTERED"); expected = "REFERENCE_INDEPENDENT_SOURCE_UNBOUND"; }
            case "NO_LOCATOR" -> { row.remove("sourceLocator"); expected = "REFERENCE_INDEPENDENT_EVIDENCE_INVALID_OR_DEPENDENT"; }
            case "BAD_STRENGTH" -> { row.put("strength", "VERY_STRONG"); expected = "REFERENCE_INDEPENDENT_STRENGTH_UNSUPPORTED"; }
            case "INSUFFICIENT" -> { row.put("strength", "SUPPORTING"); expected = "REFERENCE_INDEPENDENT_EVIDENCE_INSUFFICIENT"; }
            case "CLASSIFICATION" -> { row.put("strength", "MODERATE"); expected = "REFERENCE_INDEPENDENT_CLASSIFICATION_MISMATCH"; }
            case "CONFLICT_REVIEW" -> { q.put("conflictReview", "UNKNOWN"); expected = "REFERENCE_INDEPENDENT_REVIEW_MISSING"; }
            case "CRITERIA_MISMATCH" -> { ((ObjectNode) ref().path("independence")).putArray("criteria").add("PS3"); expected = "REFERENCE_INDEPENDENT_CRITERIA_MISMATCH"; }
            case "DE_NOVO_DUPLICATION" -> { row.put("code", "PS2"); ((ObjectNode) q.at("/evidence/1")).put("code", "PM6"); expected = "REFERENCE_INDEPENDENT_EVIDENCE_INVALID_OR_DEPENDENT"; }
            default -> throw new IllegalArgumentException(change);
        }
        bind(); reason(expected); assertEquals(0, rawCalls);
    }

    @Test void nonExpertNegativeAndUnknownDiseaseCannotBeHiddenByIndependentReview() {
        independentReference(); ObjectNode good = atomic();
        metric("clinvar.assertion.scv", "SCV999999999.1"); metric("clinvar.assertion.classification", "Uncertain significance");
        ObjectNode conflict = runtime().assess(context, candidate(), policy, receipt, material, List.of(good, atomic()));
        assertEquals("REFERENCE_ASSERTION_CONFLICT", evaluation(conflict).at("/evaluationResult/reasonCode").asText());
        metric("clinvar.assertion.condition.submittedRaw", "not provided"); metric("clinvar.assertion.condition.reportedRaw", "not provided");
        ObjectNode unknown = runtime().assess(context, candidate(), policy, receipt, material, List.of(good, atomic()));
        assertEquals("REFERENCE_CONFLICT_DISEASE_UNRESOLVED", evaluation(unknown).at("/evaluationResult/reasonCode").asText());
        assertEquals(0, rawCalls);
    }

    @Test void reviewedIndependentPm5StillRequiresBothSpliceGatesAndMatrix() throws Exception {
        setup(true); independentReference(); ObjectNode accepted = run(); assertEquals("MET", status(accepted), accepted.toPrettyString());
        assertEquals("GRANTHAM", accepted.at("/proteinComparisons/0/matrixKind").asText());
        referenceScore = "0.85"; reason("UNRESOLVED_SPLICE_MECHANISM");
    }

    @Test void independentQualificationDoesNotTurnSameChangeIntoPm5Support() {
        independentReference(); pm5 = true; bind(); ObjectNode result = run();
        assertEquals("NOT_MET", status(result), result.toPrettyString());
        assertEquals("NO_ELIGIBLE_REVIEWED_PROTEIN_COMPARISON", result.at("/scientificDecision/reasonCode").asText());
        assertEquals("NONE", evaluation(result).path("appliedStrength").asText());
    }

    private ObjectNode mappedCondition() {
        metric("clinvar.assertion.condition.submittedRaw", "OMIM:600496");
        metric("clinvar.assertion.condition.reportedRaw", "MedGen:C0342276");
        var row=ref().putArray("conditionMappings").addObject().put("schemaVersion","reviewed-clinvar-condition-mapping-v1")
                .put("status","REVIEWED_EXACT").put("relation","EXACT_DISEASE").put("reviewId","SYNTHETIC_EXACT_MAPPING_ONLY")
                .put("mondoId",context.at("/disease/mondoId").asText());
        row.putObject("sourceCondition").put("submittedRaw","OMIM:600496").put("reportedRaw","MedGen:C0342276");
        row.putArray("sourceIds").add("ENGINEERING_REPACKAGING"); bind();return row;
    }
    @Test void reviewedExactIdentifiersWorkWithoutChangingSourceAtomics() {
        mappedCondition();var before=atomic();var result=run();assertEquals("MET",status(result),result.toPrettyString());
        assertEquals(before,atomic());assertEquals("OMIM:600496",metricValue(result.at("/atomicEvidence/0"),"clinvar.assertion.condition.submittedRaw"));
        assertTrue(result.at("/references/0/conditionMappings").isArray());
    }
    private String metricValue(JsonNode atomic,String id){for(var row:atomic.path("measurements"))if(id.equals(row.path("metricId").asText()))return row.path("rawValue").asText();return "";}
    @ParameterizedTest @ValueSource(strings={"UNREVIEWED","BROADER","UNBOUND","STALE","DUPLICATE","EXPLICIT_MONDO","WRONG_TARGET"})
    void invalidOrAmbiguousConditionMappingsRefuse(String change) {
        var row=mappedCondition();String expected="REFERENCE_CONDITION_MAPPING_INVALID";
        switch(change){
            case "UNREVIEWED" -> row.put("status","UNREVIEWED");
            case "BROADER" -> row.put("relation","BROADER_DISEASE");
            case "UNBOUND" -> row.putArray("sourceIds").add("NOT_REGISTERED");
            case "STALE" -> {((ObjectNode)row.path("sourceCondition")).put("submittedRaw","OMIM:999999");expected="REFERENCE_DISEASE_OR_MECHANISM_MISMATCH";}
            case "DUPLICATE" -> {ref().withArray("conditionMappings").add(row.deepCopy());expected="REFERENCE_CONDITION_MAPPING_AMBIGUOUS";}
            case "EXPLICIT_MONDO" -> {((ObjectNode)row.path("sourceCondition")).put("submittedRaw","MONDO:0000001");expected="REFERENCE_CONDITION_MAPPING_EXPLICIT_MONDO_CONFLICT";}
            case "WRONG_TARGET" -> {row.put("mondoId","MONDO:0000001");expected="REFERENCE_DISEASE_OR_MECHANISM_MISMATCH";}
        }
        bind();reason(expected);assertEquals(0,rawCalls);
    }
    @Test void mappedExpertNegativeIsNotHiddenAndUnknownNegativeDiseaseBlocks() {
        mappedCondition();var good=atomic();metric("clinvar.assertion.scv","SCV999999999.1");metric("clinvar.assertion.classification","Benign");
        var same=runtime().assess(context,candidate(),policy,receipt,material,List.of(good,atomic()));
        assertEquals("REFERENCE_ASSERTION_CONFLICT",evaluation(same).at("/evaluationResult/reasonCode").asText());
        metric("clinvar.assertion.condition.submittedRaw","OMIM:999999");
        var unknown=runtime().assess(context,candidate(),policy,receipt,material,List.of(good,atomic()));
        assertEquals("REFERENCE_CONFLICT_DISEASE_UNRESOLVED",evaluation(unknown).at("/evaluationResult/reasonCode").asText());assertEquals(0,rawCalls);
    }
    @Test void separatelyReviewedOtherDiseaseDoesNotCreateASameDiseaseConflict() {
        var mapping=mappedCondition();var good=atomic();
        var other=mapping.deepCopy().put("mondoId","MONDO:0000001");
        other.putObject("sourceCondition").put("submittedRaw","OMIM:999999").put("reportedRaw","MedGen:C9999999");
        ref().withArray("conditionMappings").add(other);bind();
        metric("clinvar.assertion.scv","SCV999999999.1");metric("clinvar.assertion.classification","Benign");
        metric("clinvar.assertion.condition.submittedRaw","OMIM:999999");metric("clinvar.assertion.condition.reportedRaw","MedGen:C9999999");
        var result=runtime().assess(context,candidate(),policy,receipt,material,List.of(good,atomic()));
        assertEquals("MET",status(result),result.toPrettyString());
    }
    private static final String TX = "ENST00000257555.11";
    private static final String SPLICE = "e2f652e52955a0c6aaed434a3424cb69bb29413908d7dba8aaded016aa537542";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private ObjectNode context, material, policy, receipt, carrier, sourceRef;
    private TranscriptConsequence selected;
    private boolean pm5;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void nmdTranscriptMetadataDoesNotEraseVerifiedMissense(boolean usePm5) throws Exception {
        setup(usePm5); var original = run(); assertEquals("MET", status(original));
        org.springframework.test.util.ReflectionTestUtils.setField(requireNonNull(selected), "consequence", "missense_variant&NMD_transcript_variant");
        ((ObjectNode) ref().path("annotation")).put("consequence", "missense_variant&NMD_transcript_variant"); bind();
        var current = run(); assertEquals(status(original), status(current), current.toPrettyString());
        assertEquals(evaluation(original).path("appliedStrength"), evaluation(current).path("appliedStrength"));
    }
    private String targetScore = "0.01", referenceScore = "0.02", rawFault = "";
    private int rawCalls;

    @BeforeEach void setup() throws Exception { setup(false); }
    private void setup(boolean different) throws Exception {
        pm5 = different;
        JsonNode base = read("/e2/runtime-evidence-v2-fixture.json");
        context = (ObjectNode) base.path("context").deepCopy(); carrier = (ObjectNode) base.path("carrier").deepCopy();
        ((ObjectNode) context.path("gene")).put("hgncId", "HGNC:11621").put("symbol", "HNF1A");
        ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0015967");
        ((ObjectNode) context.path("transcript")).put("accession", "ENST00000257555").put("version", 11).put("annotationSourceVersion", "116.0");
        ObjectNode a = allele().put("contig", "12").put("position", pm5 ? 120993611 : 120978826).put("reference", "G").put("alternate", "C");
        for (String field : List.of("inputAllele", "normalizedAllele")) ((ObjectNode) context.path("variant")).set(field, a.deepCopy());
        ((ObjectNode) context.path("variant")).put("alleleId", adapter.canonicalAlleleId(a, context.at("/variant/normalization")));
        context.put("contextId", adapter.canonicalContextId(context));
        ObjectNode targetTx = annotation(pm5 ? "c.618G>C" : "c.58G>C", pm5 ? "Trp206Cys" : "Gly20Arg", pm5 ? "3/10" : "1/10", pm5 ? "206" : "20", pm5 ? "W/C" : "G/R", pm5 ? "tgG/tgC" : "Ggg/Cgg");
        selected = mapper.treeToValue(targetTx, TranscriptConsequence.class).withAnnotationMetadata("VEP", "COMPLETED", "116.0", Instant.parse("2026-09-11T01:00:00Z"));
        sourceRef = (ObjectNode) Hnf1aPs1Pm5Runtime.read(mapper, "reference-audit.json").path("references").path(pm5 ? 1 : 0).deepCopy();
        policy = mapper.createObjectNode().put("schemaVersion", "missense-comparison-policy-v1").put("policyId", "SYNTHETIC_HNF1A_REPACKAGING_ONLY").put("policyVersion", "1");
        policy.putArray("criterionIds").add(MissenseComparisonRuntime.PS1).add(MissenseComparisonRuntime.PM5);
        policy.putObject("review").put("status", "REVIEWED_IMPLEMENTED").put("reviewId", "SYNTHETIC_ENGINEERING_REVIEW");
        ObjectNode scope = policy.putObject("scope").put("hgncId", "HGNC:11621").put("geneSymbol", "HNF1A").put("mondoId", "MONDO:0015967")
                .put("moi", "AUTOSOMAL_DOMINANT").put("assembly", "GRCh38").put("protein", "ENSP00000257555.5")
                .put("variantType", "NATIVE_NONPROJECTED_SNV").put("supportedEffects", "MISSENSE_EXCLUDING_START_AND_SPLICE");
        scope.putArray("transcripts").addObject().put("accession", "ENST00000257555").put("version", 11);
        ArrayNode sources = policy.putArray("sources");
        sources.addObject().put("sourceId", "ENGINEERING_REPACKAGING").put("version", "synthetic-only-v1")
                .put("url", "https://example.org/synthetic-policy-not-production").put("sha256", "a".repeat(64));
        for (JsonNode artifact : sourceRef.path("sourceArtifacts")) sources.addObject().put("sourceId", artifact.path("resource").asText())
                .put("version", sourceRef.path("erepoVersion").asText()).put("url", artifact.path("sourceUrl").asText()).put("sha256", artifact.path("sha256").asText());
        String registryPath = "/acmg/rule-registry-v2/acmg-rule-registry-hnf1a-ps1-pm5-2026-09-11.json";
        policy.putObject("registryBinding").put("registryId", read(registryPath).path("registryId").asText())
                .put("registryClasspathResource", registryPath).put("registrySha256", ClinVarMissenseBusinessArtifacts.resourceSha(registryPath));
        policy.put("comparatorProfileSha256", adapter.canonicalSha256(ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper)))
                .put("overlapPolicy", "PS1_PRECEDENCE_NO_ADDITIVE_REFERENCE_SUPPORT");
        ObjectNode sp = policy.putObject("splicePolicy").put("method", "SpliceAI_RAW").put("operator", "LTE").put("maximumScore", "0.1")
                .put("distanceBp", 4999).put("mask", 0).put("aboveThresholdOutcome", "NOT_EVALUATED").put("resourceSha256", SPLICE).put("basis", "SYNTHETIC_REPACKAGING_OF_EXTRA_CONSERVATIVE_GATE"); sources(sp);
        ObjectNode ps = policy.putObject("rules").putObject("PS1").put("mode", "SAME_PROTEIN_CHANGE_SINGLE_REFERENCE"); sources(ps);
        ps.putObject("classificationStrengths").put("PATHOGENIC", "STRONG").put("LIKELY_PATHOGENIC", "MODERATE");
        ObjectNode pm = ((ObjectNode) policy.path("rules")).putObject("PM5").put("mode", "SUBSTITUTION_MATRIX_SINGLE_REFERENCE").put("matrixKind", "GRANTHAM").put("comparison", "TARGET_GTE_REFERENCE"); sources(pm);
        pm.putObject("classificationStrengths").put("PATHOGENIC", "MODERATE").put("LIKELY_PATHOGENIC", "SUPPORTING");
        pm.putObject("failureStrengths").put("PATHOGENIC", "SUPPORTING").put("LIKELY_PATHOGENIC", "SUPPORTING");
        material = mapper.createObjectNode().put("schemaVersion", "reviewed-missense-materials-v1").put("materialId", "SYNTHETIC_HNF1A_INPUTS").put("version", "1");
        material.putObject("review").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_ENGINEERING_REVIEW"); material.set("scope", scope.deepCopy());
        ObjectNode model = (ObjectNode) read("/acmg/pvs1-hnf1a-transcript-model-v1.json");
        model.put("schemaVersion", "native-coding-transcript-model-v1").put("modelId", "SYNTHETIC_REPACKAGING_OF_PINNED_HNF1A_MODEL").put("modelVersion", "1").put("geneSymbol", "HNF1A");
        model.putArray("sourceRecords").add(sources.path(0).deepCopy()); material.set("nativeModel", model);
        material.set("annotationResource", read("/acmg/spliceai-business-vep-identity-v1.json").at("/resources/0").deepCopy());
        ObjectNode audit = material.putObject("referenceAudit").put("status", "REVIEWED").put("reviewId", "SYNTHETIC_REPACKAGING"); sources(audit);
        ObjectNode ref = audit.putArray("references").addObject();
        for (String field : List.of("allele", "scv", "variationId", "classification", "evaluationDate", "hgncId", "geneSymbol", "mondoId")) ref.set(field, sourceRef.path(field).deepCopy());
        ref.put("referenceId", "REFERENCE_1").put("assertionStatus", "ACTIVE").put("moi", "AUTOSOMAL_DOMINANT"); sources(ref);
        JsonNode vep = sourceRef.path("selectedVep");
        ref.set("annotation", annotation(vep.path("hgvsc").asText().split(":")[1], vep.path("hgvsp").asText().split(":p\\.")[1], vep.path("exon").asText(), vep.path("protein_start").asText(), vep.path("amino_acids").asText(), vep.path("codons").asText()));
        ObjectNode independence = ref.putObject("independence").put("status", "REVIEWED_SUFFICIENT_WITHOUT_PS1_PM5").put("reviewId", "SYNTHETIC_REPACKAGING")
                .put("originalSpecificationVersion", sourceRef.path("originalSpecificationVersion").asText()).put("classificationAfterExclusion", sourceRef.path("classification").asText()).put("dependencyReview", "REVIEWED_NO_COMPARISON_DEPENDENCY");
        sources(independence); independence.set("criteria", sourceRef.path("independentCriteria").deepCopy()); independence.putArray("dependencyAlleles");
        ObjectNode rna = material.putObject("rnaReview").put("status", "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED").put("rnaEffect", "UNKNOWN").put("normalRnaClaimed", false).put("reviewId", "SYNTHETIC_REPACKAGING"); sources(rna); rna.putArray("knownConcerns");
        ObjectNode comparison = rna.putArray("comparisons").addObject().put("status", "REVIEWED_NO_CONTRADICTORY_REPORT_IDENTIFIED").put("reviewId", "SYNTHETIC_REPACKAGING"); sources(comparison);
        comparison.set("targetAllele", allele().deepCopy()); comparison.set("referenceAllele", ref.path("allele").deepCopy());
        ObjectNode proof = material.putObject("spliceAnnotationProof").put("status", "FULL_EXON_BOUNDARIES_MATCH").put("reviewId", "SYNTHETIC_REPACKAGING").put("resourceSha256", SPLICE).put("transcript", TX); sources(proof); proof.set("exons", model.path("exons").deepCopy());
        ObjectNode matrix = material.putObject("substitutionMatrix").put("matrixKind", "GRANTHAM").put("reviewId", "SYNTHETIC_REPACKAGING"); sources(matrix); matrix.set("matrix", Hnf1aPs1Pm5Runtime.read(mapper, "grantham.json").path("matrix"));
        bind(); makeCarrier();
    }

    @Test void publicSourceG20ComparisonUsesIndependentStrongReference() { ObjectNode result = run(); assertEquals("MET", status(result), result.toPrettyString()); assertEquals("STRONG", evaluation(result).path("appliedStrength").asText()); assertEquals(2, result.path("spliceEvidence").size()); assertEquals(1, rawCalls); assertFalse(result.path("classificationEnabled").asBoolean(true)); }
    @Test void conflictingSerializedHgvsAliasCannotChangeApprovedReferenceAnnotation() {
        ((ObjectNode) ref().path("annotation")).put("hgvsc", "ENST00000257555.11:c.999A>C");
        bind(); reason("REFERENCE_ANNOTATION_INVALID"); assertEquals(0, rawCalls);
    }
    @Test void w206DifferentSubstitutionUsesExistingGranthamLpSupporting() throws Exception { setup(true); ObjectNode result = run(); assertEquals("MET", status(result), result.toPrettyString()); assertEquals("SUPPORTING", evaluation(result).path("appliedStrength").asText()); assertEquals(215, result.at("/proteinComparisons/0/targetSubstitutionScore").asInt()); assertEquals(101, result.at("/proteinComparisons/0/referenceSubstitutionScore").asInt()); }
    @Test void auditedTriplesUseSameSharedMechanics() throws Exception { setup(true); ObjectNode rule = (ObjectNode) policy.at("/rules/PM5"); rule.put("mode", "AUDITED_AMINO_ACID_TRIPLES_SINGLE_REFERENCE"); rule.remove(List.of("matrixKind", "matrixSha256", "comparison", "failureStrengths")); ObjectNode relation = material.putArray("aminoAcidRelations").addObject().put("referenceAminoAcid", "W").put("targetAlternate", "C").put("comparatorAlternate", "R").put("reviewId", "SYNTHETIC_TRIPLE"); sources(relation); bind(); assertEquals("MET", status(run())); }
    @Test void syntheticBlosumOperatorUsesLowerScoresAndPOnly() throws Exception {
        setup(true); ObjectNode ref = ref(); ref.put("classification", "Pathogenic"); ((ObjectNode) ref.path("independence")).put("classificationAfterExclusion", "Pathogenic"); metric("clinvar.assertion.classification", "Pathogenic");
        ObjectNode rule = (ObjectNode) policy.at("/rules/PM5"); rule.put("matrixKind", "BLOSUM62").put("comparison", "TARGET_LTE_REFERENCE"); rule.putObject("classificationStrengths").put("PATHOGENIC", "MODERATE"); rule.putObject("failureStrengths").put("PATHOGENIC", "NONE");
        ObjectNode matrix = (ObjectNode) material.path("substitutionMatrix"); matrix.put("matrixKind", "BLOSUM62").put("reviewId", "SYNTHETIC_DIRECTION_TEST_NOT_REAL_BLOSUM");
        putMatrix("W", "C", -3); putMatrix("W", "R", -2); bind(); assertEquals("MET", status(run()));
        putMatrix("W", "C", -1); bind(); assertEquals("NOT_MET", status(run()));
    }
    @Test void lpBlosumCannotBypassUnimplementedClassificationCeiling() throws Exception { setup(true); ((ObjectNode) policy.at("/rules/PM5")).put("matrixKind", "BLOSUM62").put("comparison", "TARGET_LTE_REFERENCE"); ((ObjectNode) material.path("substitutionMatrix")).put("matrixKind", "BLOSUM62"); bind(); reason("MISSENSE_LP_CLASSIFICATION_CEILING_NOT_IMPLEMENTED"); }
    @ParameterizedTest @ValueSource(strings={"PS1", "PM5_SUPPORTING", "ACMG_AMP_2015:PS1_MODERATE", "ACMG_AMP_2015:PM5"})
    void derivedClassificationsNeverBecomeIndependentTruth(String criterion) { ((ObjectNode) ref().path("independence")).withArray("criteria").add(criterion); bind(); reason("REFERENCE_NOT_INDEPENDENT"); assertEquals(0, rawCalls); }
    @Test void targetDependencyAndUnknownGraphsAreNotIndependent() { ((ObjectNode) ref().path("independence")).withArray("dependencyAlleles").add(allele().deepCopy()); bind(); reason("REFERENCE_CIRCULAR_OR_TARGET_DEPENDENT"); }
    @Test void exactWithdrawnAssertionCannotBeUsed() { ref().put("assertionStatus", "WITHDRAWN"); bind(); reason("REFERENCE_WITHDRAWN_OR_STATUS_UNREVIEWED"); }
    @Test void dateAndScvVersionAreNotInterchangeable() { metric("clinvar.assertion.dateLastEvaluated", "2026-01-01"); reason("REFERENCE_ASSERTION_VERSION_MISMATCH"); }
    @Test void unauditedClinvarLabelIsNotAReference() { metric("clinvar.assertion.scv", "SCV002032337.2"); reason("INDEPENDENT_REFERENCE_EVIDENCE_MISSING"); }
    @Test void wrongDiseaseCannotHideBehindReportedMatch() { metric("clinvar.assertion.condition.submittedRaw", "MONDO:0000001"); reason("REFERENCE_DISEASE_OR_MECHANISM_MISMATCH"); }
    @Test void contradictoryExpertAssertionIsRetainedAndBlocks() { ObjectNode good = atomic(); metric("clinvar.assertion.scv", "SCV999999999.1"); metric("clinvar.assertion.classification", "Benign"); ObjectNode result = runtime().assess(context, candidate(), policy, receipt, material, List.of(good, atomic())); assertEquals("REFERENCE_ASSERTION_CONFLICT", evaluation(result).at("/evaluationResult/reasonCode").asText()); }
    @Test void fullReferenceDnaProteinIdentityCannotBeReplacedWithMatchingText() { ((ObjectNode) ref().path("annotation")).put("codons", "GGA/AGA"); bind(); reason("CODING_MISSENSE_CODING_PROTEIN_IDENTITY_MISMATCH"); }
    @Test void forgedAtomicIdIsRejectedBeforeScientificEvaluation() { ObjectNode a = atomic(); a.put("evidenceId", "evidence-sha256-" + "c".repeat(64)); ObjectNode r = runtime().assess(context, candidate(), policy, receipt, material, List.of(a)); assertEquals("MISSENSE_INPUT_INTEGRITY_INVALID", evaluation(r).at("/evaluationResult/reasonCode").asText()); }
    @Test void duplicateRowsDoNotAddStrengthOrQueries() { ObjectNode a = atomic(); ObjectNode r = runtime().assess(context, candidate(), policy, receipt, material, List.of(a, a.deepCopy())); assertEquals("MET", status(r)); assertEquals(1, r.path("references").size()); assertEquals(1, r.at("/scientificDecision/evidenceIds").size()); assertEquals(2, r.path("spliceEvidence").size()); }
    @Test void inclusiveSpliceBoundaryPassesButStrictBoundaryDoesNot() { targetScore = "0.1"; referenceScore = "0.1"; assertEquals("MET", status(run())); ((ObjectNode) policy.path("splicePolicy")).put("operator", "LT"); bind(); reason("UNRESOLVED_SPLICE_MECHANISM"); }
    @Test void bothSidesMustPassSplice() { referenceScore = "0.10000001"; reason("UNRESOLVED_SPLICE_MECHANISM"); referenceScore = "0.01"; targetScore = "0.10000001"; reason("UNRESOLVED_SPLICE_MECHANISM"); }
    @Test void cachedVepReferenceRetainsExactVersionAndFullMaterialChecks() {
        ((ObjectNode) ref().path("annotation")).put("source", "VEP_CACHED"); bind();
        assertEquals("MET", status(run()));
        ((ObjectNode) ref().path("annotation")).put("version", "115.0"); bind();
        reason("REFERENCE_ANNOTATION_INVALID");
        assertEquals(1, rawCalls);
    }
    @Test void nativeRawRequiresItsOwnMaterialPinAndStillHonoursRnaReview() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var bridge = new MissenseNativeSpliceBridge(mapper, (contig,start,length) -> new CodingTranscriptMaterialSupply.ReferenceSpan(
                "G".repeat(length), mapper.createObjectNode().put("expectedSha256", context.at("/variant/normalization/referenceSequenceSha256").asText())),
                mapper.createObjectNode().put("purpose", "SYNTHETIC_ENGINEERING_ONLY"), java.time.Duration.ofSeconds(60), System::nanoTime, (request,budget) -> {
                    calls.incrementAndGet(); var out=mapper.createObjectNode().put("status","SUCCEEDED").put("annotationFileSha256","a".repeat(64));
                    var results=out.putArray("results");
                    for(var unit:request.path("variants")) {
                        var selection=unit.path("selectedAnnotation"); var row=results.addObject().put("status","HIT").put("exactAlleleMatch",true)
                                .put("annotationFileSha256","a".repeat(64));
                        row.set("queryId",unit.path("queryId"));row.set("allele",unit.path("allele"));row.set("selectedAnnotation",selection);
                        row.putObject("referenceWindow").put("start",unit.at("/allele/position").asInt()-9999).put("length",19999)
                                .put("sha256",CodingTranscriptMaterialSupply.sha("G".repeat(19999)));
                        var ann=selection.deepCopy();row.putArray("annotationRows").add(ann);
                        var score=row.putArray("annotations").addObject().put("geneSymbol","HNF1A").put("allele",unit.at("/allele/alternate").asText());
                        var ds=score.putObject("deltaScores");var dp=score.putObject("deltaPositions");
                        for(String kind:List.of("AG","AL","DG","DL")){ds.put("DS_"+kind,"0.01");dp.put("DP_"+kind,0);}
                    }
                    return out;
                });
        var runtime=MissenseComparisonRuntime.withNativeRaw(mapper,null,bridge);
        var refused=runtime.assess(context,candidate(),policy,receipt,material,List.of(atomic()));
        assertEquals("MISSENSE_SPLICE_EXECUTION_RESOURCE_MISMATCH",evaluation(refused).at("/evaluationResult/reasonCode").asText());
        assertEquals(0,calls.get());
        // Synthetic test materials only: not a runtime conversion or approval of any historical file.
        ((ObjectNode)material.path("nativeModel")).put("geneId","ENSG00000135100");
        ((ObjectNode)policy.path("splicePolicy")).put("resourceSha256",bridge.resourceSha256());
        ((ObjectNode)material.path("spliceAnnotationProof")).put("resourceSha256",bridge.resourceSha256());bind();
        var accepted=runtime.assess(context,candidate(),policy,receipt,material,List.of(atomic()));
        assertEquals("MET",status(accepted),accepted.toPrettyString());assertEquals(1,calls.get());
        assertEquals(bridge.resourceSha256(),accepted.at("/rawSpliceBatch/nativeResourceSha256").asText());
        assertEquals("MET",status(runtime.assess(context,candidate(),policy,receipt,material,List.of(atomic()))));assertEquals(1,calls.get());
        ((ObjectNode)material.path("rnaReview")).withArray("knownConcerns").addObject().set("allele",allele().deepCopy());bind();
        var blocked=runtime.assess(context,candidate(),policy,receipt,material,List.of(atomic()));
        assertEquals("PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW",evaluation(blocked).at("/evaluationResult/reasonCode").asText());assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"MISSING", "WRONG_REQUEST", "WRONG_ALLELE", "WRONG_GENE", "MISSING_DS"}) void incompleteRawEvidenceIsNeverNormalSplicing(String fault) { rawFault = fault; reason("SPLICE_EVIDENCE_UNAVAILABLE"); }
    @Test void unresolvedPublicRnaConcernBlocksBeforeRawQueries() { ((ObjectNode) material.path("rnaReview")).withArray("knownConcerns").addObject().set("allele", allele().deepCopy()); bind(); reason("PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW"); assertEquals(0, rawCalls); }
    @Test void explicitNormalRnaClaimAndMissingPairReviewAreRejected() { ((ObjectNode) material.path("rnaReview")).put("normalRnaClaimed", true); bind(); reason("MISSENSE_RNA_REVIEW_MISSING"); }
    @Test void unknownSpecializedOperatorsAreNotSilentlyIgnored() { ((ObjectNode) policy.at("/rules/PS1")).put("maximumCombinedPoints", 4); bind(); reason("MISSENSE_RULE_OPERATOR_UNSUPPORTED"); }
    @Test void topLevelExtraConditionsCannotBeSilentlyIgnored() { policy.putArray("additionalRequirements").add("LP_CANNOT_REACH_PATHOGENIC"); bind(); reason("MISSENSE_POLICY_OPERATOR_UNSUPPORTED"); }
    @Test void selfReferenceNeverSuppliesEvidence() throws Exception {
        allele().put("alternate", "A"); for (String field : List.of("inputAllele", "normalizedAllele")) ((ObjectNode) context.path("variant")).set(field, allele().deepCopy());
        ((ObjectNode) context.path("variant")).put("alleleId", adapter.canonicalAlleleId(allele(), context.at("/variant/normalization"))); context.put("contextId", adapter.canonicalContextId(context));
        selected = mapper.treeToValue(annotation("c.58G>A", "Gly20Arg", "1/10", "20", "G/R", "Ggg/Agg"), TranscriptConsequence.class).withAnnotationMetadata("VEP", "COMPLETED", "116.0", Instant.parse("2026-09-11T01:00:00Z"));
        carrier.set("queryAllele", allele().deepCopy()); bind(); reason("REFERENCE_SELF_COMPARISON");
    }
    @Test void matrixExtraAxesAreNotAcceptedAsMissense() throws Exception { setup(true); ((ObjectNode) material.at("/substitutionMatrix/matrix/W")).put("X", 0); bind(); reason("MISSENSE_SUBSTITUTION_MATRIX_INVALID"); }
    @Test void materialRevisionChangesEvaluationIdentity() { JsonNode first = evaluation(run()).path("evaluationId"); material.put("version", "2"); bind(); assertNotEquals(first, evaluation(run()).path("evaluationId")); }
    @Test void malformedReceiptCannotActivatePolicy() { receipt.put("policySha256", "b".repeat(64)); reason("MISSENSE_SELECTION_IDENTITY_INVALID"); }
    @Test void noReferenceRecordsRemainAnExplicitGap() { ObjectNode r = runtime().assess(context, candidate(), policy, receipt, material, List.of()); assertEquals("INDEPENDENT_REFERENCE_EVIDENCE_MISSING", evaluation(r).at("/evaluationResult/reasonCode").asText()); }
    @Test void productionEntryRequiresComparatorAndDoesNotPretendToQuery() { ObjectNode r = runtime().evaluate(context, candidate(), policy, receipt, material); assertEquals("MISSENSE_COMPARATOR_INPUT_UNAVAILABLE", evaluation(r).at("/evaluationResult/reasonCode").asText()); assertEquals(0, r.path("providerCalls").asInt()); }
    @Test void overlapIncludesNegativeMembersInContextAndTargetChecks() {
        ObjectNode positive = run(); pm5 = true; bind(); ObjectNode negative = run(); assertEquals("NOT_MET", status(negative));
        assertEquals(MissenseComparisonRuntime.PS1, runtime().summarizeOverlap(List.of(positive, negative)).path("preferredComparisonCriterion").asText());
        ((ObjectNode) negative.path("policySelection")).put("contextId", "OTHER_CONTEXT"); assertThrows(IllegalArgumentException.class, () -> runtime().summarizeOverlap(List.of(positive, negative)));
        ((ObjectNode) negative.path("policySelection")).put("contextId", context.path("contextId").asText());
        ((ObjectNode) negative.path("targetIdentity")).put("proteinVariantKey", "G20W"); assertThrows(IllegalArgumentException.class, () -> runtime().summarizeOverlap(List.of(positive, negative)));
    }

    /** T05: the source count is protein changes; all additional source rows here are synthetic. */
    private void t05CountPolicy() throws Exception {
        setup(true);
        var rule = (ObjectNode) policy.at("/rules/PM5");
        rule.put("mode", MissenseComparisonPolicy.COUNTED_GRANTHAM);
        var count = rule.putObject("countRule").put("countedClassification", "PATHOGENIC")
                .put("countBy", "DISTINCT_REFERENCE_PROTEIN_SUBSTITUTION").put("minimum", 2).put("strength", "STRONG");
        sources(count);
        ref().put("classification", "Pathogenic");
        ((ObjectNode) ref().path("independence")).put("classificationAfterExclusion", "Pathogenic");
        metric("clinvar.assertion.classification", "Pathogenic");
        bind();
    }

    private ObjectNode t05AdditionalReference(String id, String classification, String c, String p,
            int position, String reference, String alternate, String aa, String codons) {
        var added = ref().deepCopy();
        added.put("referenceId", id).put("scv", "SCV90000000" + id.substring(id.length() - 1) + ".1")
                .put("variationId", "90000000" + id.substring(id.length() - 1)).put("classification", classification);
        ((ObjectNode) added.path("independence")).put("classificationAfterExclusion", classification);
        ((ObjectNode) added.path("allele")).put("position", position).put("reference", reference).put("alternate", alternate);
        added.set("annotation", annotation(c, p, "3/10", "206", "W/" + aa, codons));
        ((ArrayNode) material.at("/referenceAudit/references")).add(added);
        var pair = ((ObjectNode) material.at("/rnaReview/comparisons/0")).deepCopy();
        pair.set("referenceAllele", added.path("allele").deepCopy());
        ((ArrayNode) material.at("/rnaReview/comparisons")).add(pair);
        var source = carrier.deepCopy();
        for (var measurement : source.path("measurements")) {
            var row = (ObjectNode) measurement;
            String key = row.path("metricId").asText();
            for (String field : List.of("position", "reference", "alternate"))
                if (("clinvar.comparator." + field).equals(key)) row.put("rawValue", added.path("allele").path(field).asText());
            if ("clinvar.comparator.variationId".equals(key)) row.set("rawValue", added.path("variationId").deepCopy());
            if ("clinvar.assertion.scv".equals(key)) row.set("rawValue", added.path("scv").deepCopy());
            if ("clinvar.assertion.classification".equals(key)) row.put("rawValue", classification);
            if ("clinvar.assertion.source.line".equals(key)) row.put("rawValue", id.substring(id.length() - 1));
            if ("vep.comparator.alternateAminoAcid".equals(key)) row.put("rawValue", aa);
            if ("vep.proteinRelation".equals(key)) row.put("rawValue", "C".equals(aa)
                    ? "SAME_AMINO_ACID_CHANGE" : "SAME_RESIDUE_DIFFERENT_AMINO_ACID");
        }
        bind();
        return adapter.adapt(context, source);
    }

    private ObjectNode t05LeucineReference(String classification) {
        // Literal independent sequence vector: TGG -> TTG at c.617G>T is W206L.
        return t05AdditionalReference("T05_REFERENCE_2", classification, "c.617G>T", "Trp206Leu",
                120993610, "G", "T", "L", "tGg/tTg");
    }

    private ObjectNode t05Assess(List<ObjectNode> atomics) {
        return runtime().assess(context, candidate(), policy, receipt, material, atomics);
    }

    @Test void t05TwoDistinctIndependentPathogenicChangesProduceOneStrongCriterion() throws Exception {
        t05CountPolicy(); var second = t05LeucineReference("Pathogenic");
        var result = t05Assess(List.of(atomic(), second));
        assertEquals("MET", status(result), result::toPrettyString);
        assertEquals("STRONG", evaluation(result).path("appliedStrength").asText());
        assertEquals(2, result.at("/scientificDecision/evidenceIds").size());
        assertEquals(2, result.at("/scientificDecision/selectedReferenceIds").size());
        assertEquals(2, result.at("/pathogenicSubstitutionCount/distinctPathogenicSubstitutions").asInt());
        assertEquals(1, result.at("/comparisonTrace/comparisonStrengthCount").asInt());
        assertEquals(3, result.path("spliceEvidence").size());
        assertFalse(result.path("classificationEnabled").asBoolean(true));
    }

    @Test void t05StrongCountHasNoGranthamRestrictionAbsentFromItsSourceClause() throws Exception {
        t05CountPolicy(); var second = t05LeucineReference("Pathogenic");
        putMatrix("W", "C", 10); putMatrix("W", "R", 101); putMatrix("W", "L", 61); bind();
        var result = t05Assess(List.of(atomic(), second));
        assertEquals("STRONG", evaluation(result).path("appliedStrength").asText(), result::toPrettyString);
        for (var comparison : result.path("proteinComparisons")) assertEquals("SUPPORTING", comparison.path("appliedStrength").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"P_ONLY", "P_LOWER", "EQUAL", "P_LP", "LP_LP"})
    void t05InsufficientDistinctPChangesRetainTheSourceSingleReferenceFallback(String vector) throws Exception {
        t05CountPolicy();
        List<ObjectNode> inputs;
        if ("LP_LP".equals(vector)) {
            ref().put("classification", "Likely Pathogenic");
            ((ObjectNode) ref().path("independence")).put("classificationAfterExclusion", "Likely Pathogenic");
            metric("clinvar.assertion.classification", "Likely Pathogenic"); bind();
        }
        if (List.of("P_LP", "LP_LP").contains(vector)) inputs = List.of(atomic(), t05LeucineReference("Likely Pathogenic"));
        else inputs = List.of(atomic());
        if ("P_LOWER".equals(vector)) { putMatrix("W", "C", 100); bind(); }
        if ("EQUAL".equals(vector)) { putMatrix("W", "C", 101); bind(); }
        var result = t05Assess(inputs);
        assertEquals("MET", status(result), result::toPrettyString);
        assertEquals(List.of("P_LOWER", "LP_LP").contains(vector) ? "SUPPORTING" : "MODERATE", evaluation(result).path("appliedStrength").asText());
        assertEquals(1, result.at("/scientificDecision/evidenceIds").size());
        assertFalse(result.at("/pathogenicSubstitutionCount/countRuleSatisfied").asBoolean());
    }

    @Test void t05OriginallyPathogenicButIndependentlyAdoptedLpDoesNotCountAsPathogenic() throws Exception {
        t05CountPolicy(); var second = t05LeucineReference("Pathogenic");
        separatedReference(true);
        var result = t05Assess(List.of(atomic(), second));
        assertEquals("MET", status(result), result::toPrettyString);
        assertEquals("MODERATE", evaluation(result).path("appliedStrength").asText());
        assertEquals(1, result.at("/pathogenicSubstitutionCount/distinctPathogenicSubstitutions").asInt());
        assertFalse(result.at("/pathogenicSubstitutionCount/countRuleSatisfied").asBoolean());
        assertEquals("Pathogenic", result.at("/references/0/classification").asText());
        assertEquals("LIKELY_PATHOGENIC", result.at("/references/0/adoptedClassification").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"DUPLICATE_ATOMIC", "SAME_ALLELE", "SAME_PROTEIN", "TARGET_PROTEIN"})
    void t05RepeatedDnaOrProteinFactsCannotMakeTwoPathogenicChanges(String vector) throws Exception {
        t05CountPolicy();
        ObjectNode second;
        if ("DUPLICATE_ATOMIC".equals(vector)) second = atomic().deepCopy();
        else if ("TARGET_PROTEIN".equals(vector)) second = t05AdditionalReference("T05_REFERENCE_2", "Pathogenic",
                "c.618G>T", "Trp206Cys", 120993611, "G", "T", "C", "tgG/tgT");
        else second = t05AdditionalReference("T05_REFERENCE_2", "Pathogenic", "c.616T>C", "Trp206Arg",
                120993609, "T", "C", "R", "Tgg/Cgg");
        if ("SAME_ALLELE".equals(vector)) {
            var added = (ObjectNode) material.at("/referenceAudit/references/1");
            added.set("allele", ref().path("allele").deepCopy()); added.set("annotation", ref().path("annotation").deepCopy());
            var metrics = (ArrayNode) second.path("measurements");
            for (var row : metrics) if ("clinvar.comparator.alternate".equals(row.path("metricId").asText())) ((ObjectNode) row).put("rawValue", "A");
            // Re-adapt changed carrier measurements to preserve the canonical atomic identity.
            var source = carrier.deepCopy(); source.set("measurements", metrics.deepCopy()); second = adapter.adapt(context, source); bind();
        }
        var result = t05Assess(List.of(atomic(), second));
        assertEquals("MET", status(result), result::toPrettyString);
        assertEquals("MODERATE", evaluation(result).path("appliedStrength").asText());
        assertEquals(1, result.at("/pathogenicSubstitutionCount/distinctPathogenicSubstitutions").asInt());
        assertEquals(1, result.at("/scientificDecision/evidenceIds").size());
    }

    @Test void t05OneScvCannotIdentifyTwoDifferentReferenceAlleles() throws Exception {
        t05CountPolicy(); var second = t05LeucineReference("Pathogenic");
        var added = (ObjectNode) material.at("/referenceAudit/references/1");
        added.set("scv", ref().path("scv").deepCopy()); bind();
        var result = t05Assess(List.of(atomic(), second));
        assertEquals("NOT_EVALUATED", status(result), result::toPrettyString);
        assertEquals("MISSENSE_REFERENCE_AUDIT_AMBIGUOUS", evaluation(result).at("/evaluationResult/reasonCode").asText());
        assertEquals(0, rawCalls);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void t05RepeatedAlleleWithPAndLpAssertionsHasOrderIndependentCount(boolean lpFirst) throws Exception {
        t05CountPolicy(); var otherChange = t05LeucineReference("Pathogenic");
        var repeatedLp = t05AdditionalReference("T05_REFERENCE_3", "Likely Pathogenic", "c.616T>A", "Trp206Arg",
                120993609, "T", "A", "R", "Tgg/Agg");
        var pairs = (ArrayNode) material.at("/rnaReview/comparisons"); pairs.remove(pairs.size() - 1); bind();
        var firstP = atomic();
        var result = t05Assess(lpFirst ? List.of(repeatedLp, otherChange, firstP) : List.of(firstP, otherChange, repeatedLp));
        assertEquals("STRONG", result.at("/scientificDecision/appliedStrength").asText(), result::toPrettyString);
        assertEquals(2, result.at("/pathogenicSubstitutionCount/distinctPathogenicSubstitutions").asInt());
        assertEquals(2, result.at("/scientificDecision/evidenceIds").size());
        assertTrue(result.at("/scientificDecision/evidenceIds").toString().contains(firstP.path("evidenceId").asText()));
        assertTrue(MissenseComparisonRuntime.validCountedDecision(mapper, policy, result.path("scientificDecision"),
                result.path("comparisonTrace"), List.of(firstP, otherChange, repeatedLp)));
        // The common evaluator's two-contributor acceptance is a separate MAIN-owned dependency.
    }

    @ParameterizedTest @ValueSource(strings = {"BASELINE", "ONE_ID", "REPEATED_ID", "WRONG_ID", "MODEL", "CLASSIFICATION",
            "PROTEIN", "PROOF_HASH", "TRACE_COUNT", "REFERENCE_ID", "OLD_MODE"})
    void t05EvaluatorBridgeRequiresTheActualBoundTwoContributorProof(String fault) throws Exception {
        t05CountPolicy(); var second = t05LeucineReference("Pathogenic"); var inputs = List.of(atomic(), second);
        var result = t05Assess(inputs); var decision = (ObjectNode) result.path("scientificDecision");
        var trace = (ObjectNode) result.path("comparisonTrace");
        switch (fault) {
            case "BASELINE" -> { }
            case "ONE_ID" -> ((ArrayNode) decision.path("evidenceIds")).remove(1);
            case "REPEATED_ID" -> ((ArrayNode) decision.path("evidenceIds")).set(1, decision.at("/evidenceIds/0"));
            case "WRONG_ID" -> ((ArrayNode) decision.path("evidenceIds")).set(1, mapper.getNodeFactory().textNode("not-an-actual-input"));
            case "MODEL" -> ((ObjectNode) decision.at("/pathogenicSubstitutionCount/eligibleSubstitutions/1")).put("nativeModelSha256", "0".repeat(64));
            case "CLASSIFICATION" -> ((ObjectNode) decision.at("/pathogenicSubstitutionCount/eligibleSubstitutions/1")).put("adoptedClassification", "LIKELY_PATHOGENIC");
            case "PROTEIN" -> ((ObjectNode) decision.at("/pathogenicSubstitutionCount/eligibleSubstitutions/1")).set("proteinVariantKey", decision.at("/pathogenicSubstitutionCount/eligibleSubstitutions/0/proteinVariantKey"));
            case "PROOF_HASH" -> trace.put("pathogenicSubstitutionCountSha256", "0".repeat(64));
            case "TRACE_COUNT" -> trace.put("referenceStrengthCount", 1);
            case "REFERENCE_ID" -> ((ArrayNode) decision.path("selectedReferenceIds")).set(1, decision.at("/selectedReferenceIds/0"));
            case "OLD_MODE" -> { ((ObjectNode) policy.at("/rules/PM5")).put("mode", "SUBSTITUTION_MATRIX_SINGLE_REFERENCE").remove("countRule"); }
            default -> throw new AssertionError(fault);
        }
        if (List.of("MODEL", "CLASSIFICATION", "PROTEIN").contains(fault))
            trace.put("pathogenicSubstitutionCountSha256", adapter.canonicalSha256(decision.path("pathogenicSubstitutionCount")));
        assertEquals("BASELINE".equals(fault), MissenseComparisonRuntime.validCountedDecision(mapper, policy, decision, trace, inputs));
        var adopted = new ClinVarPs1Pm5CriterionEvaluator(mapper).evaluateReviewedPolicy(context, inputs,
                "ACMG_AMP_2015:PM5", policy, receipt, decision, trace,
                ClinVarMissenseBusinessRuntime.read(mapper, policy.at("/registryBinding/registryClasspathResource").asText()));
        assertEquals("BASELINE".equals(fault) ? "MET" : "NOT_EVALUATED", adopted.at("/evaluationResult/status").asText(), adopted::toPrettyString);
    }

    @Test void t05MoreReferencesAndInputOrderDoNotAddAnotherCriterionOrChooseAnUnstablePair() throws Exception {
        t05CountPolicy(); var second = t05LeucineReference("Pathogenic");
        var third = t05AdditionalReference("T05_REFERENCE_3", "Pathogenic", "c.616T>G", "Trp206Gly",
                120993609, "T", "G", "G", "Tgg/Ggg");
        var first = atomic(); var forward = t05Assess(List.of(first, second, third)); var reversed = t05Assess(List.of(third, second, first));
        assertEquals("STRONG", evaluation(forward).path("appliedStrength").asText(), forward::toPrettyString);
        assertEquals(forward.path("scientificDecision"), reversed.path("scientificDecision"));
        assertEquals(forward.path("pathogenicSubstitutionCount"), reversed.path("pathogenicSubstitutionCount"));
        assertEquals(3, forward.at("/pathogenicSubstitutionCount/distinctPathogenicSubstitutions").asInt());
        assertEquals(2, forward.at("/scientificDecision/evidenceIds").size());
    }

    @ParameterizedTest @ValueSource(strings = {"DEPENDENT", "TARGET_DEPENDENCY", "DISEASE", "RNA", "SPLICE", "SOURCE"})
    void t05CountingDoesNotBypassOriginalReferenceAndRnaGates(String fault) throws Exception {
        t05CountPolicy(); var second = t05LeucineReference("Pathogenic");
        var added = (ObjectNode) material.at("/referenceAudit/references/1");
        String expected;
        switch (fault) {
            case "DEPENDENT" -> { ((ObjectNode) added.path("independence")).withArray("criteria").add("PM5"); expected = "REFERENCE_NOT_INDEPENDENT"; }
            case "TARGET_DEPENDENCY" -> { ((ObjectNode) added.path("independence")).withArray("dependencyAlleles").add(allele().deepCopy()); expected = "REFERENCE_CIRCULAR_OR_TARGET_DEPENDENT"; }
            case "DISEASE" -> { added.put("mondoId", "MONDO:9999999"); expected = "REFERENCE_DISEASE_OR_MECHANISM_MISMATCH"; }
            case "RNA" -> { ((ObjectNode) material.path("rnaReview")).withArray("knownConcerns").addObject().set("allele", added.path("allele").deepCopy()); expected = "PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW"; }
            case "SPLICE" -> { referenceScore = "0.2"; expected = "UNRESOLVED_SPLICE_MECHANISM"; }
            case "SOURCE" -> { added.putArray("sourceIds").add("UNREGISTERED"); expected = "MISSENSE_REFERENCE_AUDIT_IDENTITY_INVALID"; }
            default -> throw new AssertionError(fault);
        }
        bind(); var result = t05Assess(List.of(atomic(), second));
        assertEquals("NOT_EVALUATED", status(result), result::toPrettyString);
        assertEquals(expected, evaluation(result).at("/evaluationResult/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING", "MINIMUM", "FRACTIONAL", "STRENGTH", "CLASSIFICATION", "UNIT", "SOURCE", "EXTRA", "BASE_STRONG", "OLD_MODE", "PS1"})
    void t05CountModeRequiresItsClosedSourceBoundContract(String fault) throws Exception {
        t05CountPolicy(); var rule = (ObjectNode) policy.at("/rules/PM5"); var count = (ObjectNode) rule.path("countRule");
        switch (fault) {
            case "MISSING" -> rule.remove("countRule");
            case "MINIMUM" -> count.put("minimum", 1);
            case "FRACTIONAL" -> count.put("minimum", 2.5);
            case "STRENGTH" -> count.put("strength", "VERY_STRONG");
            case "CLASSIFICATION" -> count.put("countedClassification", "LIKELY_PATHOGENIC");
            case "UNIT" -> count.put("countBy", "SCV");
            case "SOURCE" -> count.putArray("sourceIds").add("UNREGISTERED");
            case "EXTRA" -> count.put("ignoreSplicing", true);
            case "BASE_STRONG" -> ((ObjectNode) rule.path("classificationStrengths")).put("PATHOGENIC", "STRONG");
            case "OLD_MODE" -> rule.put("mode", "SUBSTITUTION_MATRIX_SINGLE_REFERENCE");
            case "PS1" -> { pm5 = false; ((ObjectNode) policy.path("rules")).set("PS1", rule.deepCopy()); }
            default -> throw new AssertionError(fault);
        }
        if ("FRACTIONAL".equals(fault)) {
            assertEquals("MISSENSE_RULE_OPERATOR_UNSUPPORTED", MissenseComparisonPolicy.issue(policy, MissenseComparisonRuntime.PM5));
            assertThrows(IllegalArgumentException.class, this::bind);
            assertEquals(0, rawCalls); return;
        }
        bind(); reason("MISSENSE_RULE_OPERATOR_UNSUPPORTED"); assertEquals(0, rawCalls);
    }

    @Test void t05OldSingleReferenceModeCannotGainStrengthFromTheSameTwoProteinChanges() throws Exception {
        t05CountPolicy(); var second = t05LeucineReference("Pathogenic");
        var rule = (ObjectNode) policy.at("/rules/PM5"); rule.put("mode", "SUBSTITUTION_MATRIX_SINGLE_REFERENCE"); rule.remove("countRule"); bind();
        var result = t05Assess(List.of(atomic(), second));
        assertEquals("MODERATE", evaluation(result).path("appliedStrength").asText(), result::toPrettyString);
        assertEquals(1, result.at("/scientificDecision/evidenceIds").size()); assertFalse(result.has("pathogenicSubstitutionCount"));
    }

    /** T05 GN032/GN035 family: a comparison matrix is optional in the native source, not a gate. */
    private void t05DirectCountPolicy() throws Exception {
        t05CountPolicy(); var rule = (ObjectNode) policy.at("/rules/PM5");
        rule.put("mode", MissenseComparisonPolicy.COUNTED_DIRECT);
        rule.remove(List.of("classificationStrengths", "failureStrengths", "matrixKind", "matrixSha256", "comparison", "classificationCeiling"));
        material.remove("substitutionMatrix"); bind();
    }

    @ParameterizedTest @ValueSource(strings = {"P", "P_LP", "LP", "LP_LP", "TWO_P"})
    void t05DirectCountUsesOnlyIndependentPathogenicChangesAndNoOptionalMatrix(String vector) throws Exception {
        t05DirectCountPolicy();
        if (vector.startsWith("LP")) {
            ref().put("classification", "Likely Pathogenic");
            ((ObjectNode) ref().path("independence")).put("classificationAfterExclusion", "Likely Pathogenic");
            metric("clinvar.assertion.classification", "Likely Pathogenic"); bind();
        }
        var inputs = new java.util.ArrayList<ObjectNode>(); inputs.add(atomic());
        if (vector.endsWith("_LP")) inputs.add(t05LeucineReference("Likely Pathogenic"));
        if ("TWO_P".equals(vector)) inputs.add(t05LeucineReference("Pathogenic"));
        var result = t05Assess(inputs); boolean onlyLp = vector.startsWith("LP"), strong = "TWO_P".equals(vector);
        assertEquals(onlyLp ? "NONE" : strong ? "STRONG" : "MODERATE", result.at("/scientificDecision/appliedStrength").asText(), result::toPrettyString);
        assertEquals(onlyLp ? 0 : strong ? 2 : 1, result.at("/pathogenicSubstitutionCount/distinctPathogenicSubstitutions").asInt());
        assertFalse(material.has("substitutionMatrix"));
        for (var comparison : result.path("proteinComparisons")) {
            assertFalse(comparison.has("matrixKind")); assertFalse(comparison.has("targetSubstitutionScore"));
            assertEquals(MissenseComparisonPolicy.COUNTED_DIRECT, comparison.path("ruleMode").asText());
        }
        if (strong) assertTrue(MissenseComparisonRuntime.validCountedDecision(mapper, policy, result.path("scientificDecision"), result.path("comparisonTrace"), inputs));
        // Public adoption still exercises the MAIN-owned evaluator; do not weaken this expected Strong.
        assertEquals(onlyLp ? "NOT_MET" : "MET", status(result), result::toPrettyString);
        assertEquals(onlyLp ? "NONE" : strong ? "STRONG" : "MODERATE", evaluation(result).path("appliedStrength").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"DUPLICATE", "SAME_PROTEIN", "P_FIRST", "LP_FIRST"})
    void t05DirectCountRetainsProteinAndMixedClassificationDeduplication(String vector) throws Exception {
        t05DirectCountPolicy(); var first = atomic(); ObjectNode second;
        if ("DUPLICATE".equals(vector)) second = first.deepCopy();
        else if ("SAME_PROTEIN".equals(vector)) second = t05AdditionalReference("T05_REFERENCE_2", "Pathogenic",
                "c.616T>C", "Trp206Arg", 120993609, "T", "C", "R", "Tgg/Cgg");
        else second = t05AdditionalReference("T05_REFERENCE_2", "Likely Pathogenic",
                "c.616T>A", "Trp206Arg", 120993609, "T", "A", "R", "Tgg/Agg");
        if (List.of("P_FIRST", "LP_FIRST").contains(vector)) {
            var pairs = (ArrayNode) material.at("/rnaReview/comparisons"); pairs.remove(pairs.size() - 1); bind();
        }
        var result = t05Assess("LP_FIRST".equals(vector) ? List.of(second, first) : List.of(first, second));
        assertEquals("MET", status(result), result::toPrettyString);
        assertEquals("MODERATE", evaluation(result).path("appliedStrength").asText());
        assertEquals(1, result.at("/pathogenicSubstitutionCount/distinctPathogenicSubstitutions").asInt());
        assertEquals(1, result.at("/scientificDecision/evidenceIds").size());
        assertEquals(first.path("evidenceId"), result.at("/scientificDecision/evidenceIds/0"));
    }

    @Test void t05DirectCountDoesNotUpgradeAnOriginalPThatWasIndependentlyAdoptedAsLp() throws Exception {
        t05DirectCountPolicy(); var second = t05LeucineReference("Pathogenic"); separatedReference(true);
        var result = t05Assess(List.of(atomic(), second));
        assertEquals("MET", status(result), result::toPrettyString);
        assertEquals("MODERATE", evaluation(result).path("appliedStrength").asText());
        assertEquals(1, result.at("/pathogenicSubstitutionCount/distinctPathogenicSubstitutions").asInt());
    }

    @Test void t05DirectCountMoreThanTwoPathogenicChangesUsesOneStableStrongPair() throws Exception {
        t05DirectCountPolicy(); var second = t05LeucineReference("Pathogenic");
        var third = t05AdditionalReference("T05_REFERENCE_3", "Pathogenic", "c.616T>G", "Trp206Gly", 120993609, "T", "G", "G", "Tgg/Ggg");
        var first = atomic(); var forward = t05Assess(List.of(first, second, third)); var reversed = t05Assess(List.of(third, second, first));
        assertEquals(forward.path("scientificDecision"), reversed.path("scientificDecision"));
        assertEquals(forward.path("pathogenicSubstitutionCount"), reversed.path("pathogenicSubstitutionCount"));
        assertEquals(3, forward.at("/pathogenicSubstitutionCount/distinctPathogenicSubstitutions").asInt());
        assertEquals(2, forward.at("/scientificDecision/evidenceIds").size());
        assertEquals(1, forward.at("/comparisonTrace/comparisonStrengthCount").asInt());
        assertEquals("STRONG", evaluation(forward).path("appliedStrength").asText(), forward::toPrettyString);
    }

    @ParameterizedTest @ValueSource(strings = {"DEPENDENT", "TARGET_DEPENDENCY", "DISEASE", "SOURCE", "RNA", "SPLICE"})
    void t05DirectCountPreservesOriginalIndependentSourceAndRnaRejections(String fault) throws Exception {
        t05DirectCountPolicy(); var second = t05LeucineReference("Pathogenic"); var reference = (ObjectNode) material.at("/referenceAudit/references/1");
        String expected;
        switch (fault) {
            case "DEPENDENT" -> { ((ObjectNode) reference.path("independence")).withArray("criteria").add("PS1"); expected = "REFERENCE_NOT_INDEPENDENT"; }
            case "TARGET_DEPENDENCY" -> { ((ObjectNode) reference.path("independence")).withArray("dependencyAlleles").add(allele().deepCopy()); expected = "REFERENCE_CIRCULAR_OR_TARGET_DEPENDENT"; }
            case "DISEASE" -> { reference.put("mondoId", "MONDO:9999999"); expected = "REFERENCE_DISEASE_OR_MECHANISM_MISMATCH"; }
            case "SOURCE" -> { reference.putArray("sourceIds").add("UNREGISTERED"); expected = "MISSENSE_REFERENCE_AUDIT_IDENTITY_INVALID"; }
            case "RNA" -> { ((ObjectNode) material.path("rnaReview")).withArray("knownConcerns").addObject().set("allele", reference.path("allele").deepCopy()); expected = "PUBLIC_SPLICE_CONCERN_REQUIRES_REVIEW"; }
            case "SPLICE" -> { referenceScore = "0.2"; expected = "UNRESOLVED_SPLICE_MECHANISM"; }
            default -> throw new AssertionError(fault);
        }
        bind(); var result = t05Assess(List.of(atomic(), second));
        assertEquals("NOT_EVALUATED", status(result), result::toPrettyString);
        assertEquals(expected, evaluation(result).at("/evaluationResult/reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"classificationStrengths", "failureStrengths", "matrixKind", "matrixSha256", "comparison", "classificationCeiling", "missing-count", "unbound-source", "wrong-minimum", "PS1"})
    void t05DirectCountCannotInventLpSupportingOrOptionalScoreRequirements(String field) throws Exception {
        t05DirectCountPolicy(); var rule = (ObjectNode) policy.at("/rules/PM5");
        switch (field) {
            case "missing-count" -> rule.remove("countRule");
            case "unbound-source" -> ((ObjectNode) rule.path("countRule")).putArray("sourceIds").add("UNREGISTERED");
            case "wrong-minimum" -> ((ObjectNode) rule.path("countRule")).put("minimum", 3);
            case "PS1" -> { pm5 = false; ((ObjectNode) policy.path("rules")).set("PS1", rule.deepCopy()); }
            default -> rule.put(field, "UNSUPPORTED_IN_THIS_FINITE_SOURCE_FAMILY");
        }
        bind(); reason("MISSENSE_RULE_OPERATOR_UNSUPPORTED"); assertEquals(0, rawCalls);
    }

    private void putMatrix(String a, String b, int score) { ((ObjectNode) material.at("/substitutionMatrix/matrix").path(a)).put(b, score); ((ObjectNode) material.at("/substitutionMatrix/matrix").path(b)).put(a, score); }
    private ObjectNode annotation(String c, String p, String exon, String pos, String aa, String codons) { return mapper.createObjectNode().put("geneSymbol", "HNF1A").put("transcriptAccession", TX).put("transcriptId", TX).put("hgvsCoding", TX + ":" + c).put("hgvsProtein", "ENSP00000257555.5:p." + p).put("consequence", "missense_variant").put("source", "VEP").put("version", "116.0").put("status", "COMPLETED").put("exon", exon).put("proteinPosition", pos).put("aminoAcids", aa).put("codons", codons); }
    private void sources(ObjectNode node) { node.putArray("sourceIds").add("ENGINEERING_REPACKAGING"); }
    private void bind() {
        String modelHash = adapter.canonicalSha256(material.path("nativeModel")); policy.put("nativeModelSha256", modelHash); ((ObjectNode) material.path("spliceAnnotationProof")).put("modelSha256", modelHash);
        policy.put("annotationResourceSha256", adapter.canonicalSha256(material.path("annotationResource"))).put("spliceAnnotationProofSha256", adapter.canonicalSha256(material.path("spliceAnnotationProof")));
        ObjectNode pm = (ObjectNode) policy.at("/rules/PM5"); if ("SUBSTITUTION_MATRIX_SINGLE_REFERENCE".equals(pm.path("mode").asText())
                || MissenseComparisonPolicy.COUNTED_GRANTHAM.equals(pm.path("mode").asText())) pm.put("matrixSha256", adapter.canonicalSha256(material.path("substitutionMatrix")));
        policy.put("materialsSha256", adapter.canonicalSha256(material));
        receipt = mapper.createObjectNode().put("schemaVersion", AcmgSpecificationSelector.SCHEMA).put("contextId", context.path("contextId").asText()).put("outcome", "SPECIALIZED_POLICY_SELECTED").put("policyId", policy.path("policyId").asText()).put("policyVersion", policy.path("policyVersion").asText()).put("policySha256", adapter.canonicalSha256(policy)).put("catalogId", "SYNTHETIC_DEVELOPER_CATALOG").put("catalogVersion", "1").put("catalogSha256", "d".repeat(64));
        receipt.putArray("criterionIds").add(pm5 ? MissenseComparisonRuntime.PM5 : MissenseComparisonRuntime.PS1); receipt.put("selectionId", "cspec-selection-sha256-" + adapter.canonicalSha256(receipt));
    }
    private void makeCarrier() {
        carrier.put("evidenceType", "CLINICAL_DATABASE"); carrier.set("queryAllele", allele().deepCopy()); carrier.putArray("measurements");
        ObjectNode source = (ObjectNode) carrier.path("source"); ArrayNode locators = source.putArray("sourceLocators");
        for (JsonNode res : ClinVarPs1Pm5CriterionEvaluator.loadProfile(mapper).path("resources")) {
            if (res.path("resourceId").asText().startsWith("CLINVAR_COMPARATOR")) source.put("sourceId", res.path("resourceId").asText()).put("resourceVersion", res.path("resourceVersion").asText()).put("resourceSha256", res.path("identityFingerprintSha256").asText());
            locators.addObject().put("locatorType", "REPOSITORY_ARTIFACT").put("locatorValue", "resource-identity:" + res.path("resourceId").asText() + "@GRCh38").put("artifactSha256", res.path("identityFingerprintSha256").asText()).put("sourceRecordId", "SYNTHETIC_ROW").put("retrievedAt", "2026-09-11T01:00:00Z");
        }
        for (String field : List.of("assembly", "contig", "position", "reference", "alternate")) metric("clinvar.comparator." + field, ref().path("allele").path(field).asText());
        metric("clinvar.comparator.geneSymbols", "HNF1A"); metric("clinvar.comparator.variationId", ref().path("variationId").asText());
        metric("clinvar.assertion.scv", ref().path("scv").asText()); metric("clinvar.assertion.classification", ref().path("classification").asText()); metric("clinvar.assertion.reviewStatus", "reviewed by expert panel"); metric("clinvar.assertion.dateLastEvaluated", ref().path("evaluationDate").asText());
        for (String kind : List.of("submittedRaw", "reportedRaw")) metric("clinvar.assertion.condition." + kind, "MONDO:0015967");
        metric("clinvar.assertion.source.logicalName", "submission_summary_2026-03.txt.gz"); metric("clinvar.assertion.source.line", "1"); metric("vep.proteinRelation", pm5 ? "SAME_RESIDUE_DIFFERENT_AMINO_ACID" : "SAME_AMINO_ACID_CHANGE");
        for (String role : List.of("target", "comparator")) { metric("vep." + role + ".transcriptId", TX); metric("vep." + role + ".proteinPosition", sourceRef.path("proteinPosition").asText()); metric("vep." + role + ".referenceAminoAcid", sourceRef.path("referenceAminoAcid").asText()); metric("vep." + role + ".alternateAminoAcid", pm5 && "target".equals(role) ? "C" : sourceRef.path("alternateAminoAcid").asText()); }
    }
    private void metric(String id, String value) { for (JsonNode m : carrier.path("measurements")) if (id.equals(m.path("metricId").asText())) { ((ObjectNode) m).put("rawValue", value); return; } carrier.withArray("measurements").addObject().put("metricId", id).put("rawValue", value); }
    private ObjectNode atomic() { return adapter.adapt(context, carrier); }
    private ObjectNode ref() { return (ObjectNode) material.at("/referenceAudit/references/0"); }
    private ObjectNode allele() { return (ObjectNode) context.at("/variant/evidenceQueryAllele"); }
    private VariantInterpretation candidate() { var key = new NormalizedVariantKey(allele().path("assembly").asText(), allele().path("contig").asText(), allele().path("position").asInt(), allele().path("reference").asText(), allele().path("alternate").asText()); var base = RuntimeEvidenceV2ProviderWiringTest.variant(key); return new VariantInterpretation(base.getVariantId(), base.getObservation(), List.of(selected), List.of()); }
    @Test void pinnedCacheModelPreservesReferenceAndSpliceEvaluation() {
        OfflineCacheModelTestSupport.add((ObjectNode) material.path("nativeModel"), policy); bind();
        ObjectNode result = run();
        assertEquals("MET", status(result), result.toPrettyString());
        assertEquals("STRONG", evaluation(result).path("appliedStrength").asText());
        assertEquals(1, rawCalls);
        assertFalse(MissenseComparisonPolicy.sourcesValid(material.at("/nativeModel/sourceRecords")));
    }

    @Test void incompleteCachedProofAndMissingRnaPolicySourceRefuseBeforeSplice() throws Exception {
        ObjectNode cache = OfflineCacheModelTestSupport.add((ObjectNode) material.path("nativeModel"), policy);
        cache.remove("apiSourceSha256"); bind();
        reason("MISSENSE_MODEL_SOURCE_UNBOUND"); assertEquals(0, rawCalls);
        setup(false); OfflineCacheModelTestSupport.add((ObjectNode) material.path("nativeModel"), policy);
        policy.withArray("sources").remove(policy.path("sources").size() - 1); bind();
        reason("MISSENSE_MODEL_SOURCE_UNBOUND"); assertEquals(0, rawCalls);
    }

    @Test void cacheCannotAuthorizeAClinicalReference() {
        ObjectNode cache = OfflineCacheModelTestSupport.add((ObjectNode) material.path("nativeModel"), policy);
        policy.withArray("sources").add(cache.deepCopy()); bind();
        reason("MISSENSE_POLICY_SOURCES_INVALID"); assertEquals(0, rawCalls);
        ((ObjectNode) policy.path("sources").path(policy.path("sources").size() - 1))
                .put("url", "https://example.invalid/not-a-scientific-source"); bind();
        reason("MISSENSE_MODEL_SOURCE_UNBOUND"); assertEquals(0, rawCalls);
    }

    private ObjectNode run() { return runtime().assess(context, candidate(), policy, receipt, material, List.of(atomic())); }
    private void reason(String reason) { ObjectNode result = run(); assertEquals("NOT_EVALUATED", status(result), result.toPrettyString()); assertEquals(reason, evaluation(result).at("/evaluationResult/reasonCode").asText(), result.toPrettyString()); }
    private JsonNode evaluation(JsonNode result) { return result.at("/criterionEvaluations/0"); }
    private String status(JsonNode result) { return evaluation(result).at("/evaluationResult/status").asText(); }
    private JsonNode read(String path) throws Exception { try (InputStream in = getClass().getResourceAsStream(path)) { assertNotNull(in); JsonNode result = mapper.readTree(in); assertNotNull(result); return result; } }
    private MissenseComparisonRuntime runtime() { return new MissenseComparisonRuntime(mapper, null, queries -> {
        rawCalls++; if ("MISSING".equals(rawFault)) return SpliceAiRawBatchRunner.BatchResult.failed("SYNTHETIC_MISSING", null);
        ArrayNode requested = mapper.createArrayNode(); ObjectNode raw = mapper.createObjectNode().put("resultSchemaVersion", SpliceAiRawBatchRunner.RESULT_SCHEMA_VERSION).put("status", "SUCCEEDED"); ArrayNode rows = raw.putArray("results");
        for (var q : queries) {
            var k = q.getKey(); ObjectNode a = mapper.createObjectNode().put("assembly", k.getGenomeAssembly()).put("contig", k.getChromosome()).put("position", k.getPosition()).put("reference", k.getReference()).put("alternate", k.getAlternate());
            ObjectNode request = requested.addObject().put("queryId", q.getQueryId()); request.set("allele", a); request.put("geneSymbol", q.getGeneSymbol()).put("selectedTranscriptId", q.getSelectedTranscriptId());
            ObjectNode row = rows.addObject().put("queryId", q.getQueryId()).put("status", "HIT").put("exactAlleleMatch", true); row.set("allele", a.deepCopy());
            if ("WRONG_ALLELE".equals(rawFault)) ((ObjectNode) row.path("allele")).put("alternate", "T");
            ObjectNode scores = row.putArray("annotations").addObject().put("geneSymbol", "WRONG_GENE".equals(rawFault) ? "OTHER" : q.getGeneSymbol()).putObject("deltaScores");
            for (String f : List.of("DS_AG", "DS_AL", "DS_DG", "DS_DL")) scores.put(f, "TARGET".equals(q.getQueryId()) ? targetScore : referenceScore);
            if ("MISSING_DS".equals(rawFault)) scores.remove("DS_AG");
        }
        try { raw.put("requestId", "spliceai-batch-sha256-" + AcmgBusinessStore.sha(mapper.writeValueAsString(requested))); } catch (Exception failure) { throw new IllegalStateException(failure); }
        if ("WRONG_REQUEST".equals(rawFault)) raw.put("requestId", "spliceai-batch-sha256-" + "f".repeat(64));
        return SpliceAiRawBatchRunner.BatchResult.succeeded(raw, 0);
    }, SPLICE); }
}
