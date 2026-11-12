package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import static org.junit.jupiter.api.Assertions.*;

/** Developer synthetic checks only: all source CDSs, substitutions and expected positions predate evaluation. */
class Pm1CriterionEvaluatorTest {
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json().build()
            .enable(SerializationFeature.INDENT_OUTPUT).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    @TempDir Path temporary;

    @ParameterizedTest
    @ValueSource(strings = {"plus-primary", "plus-left", "plus-right", "plus-single", "plus-excluded", "plus-adjacent",
            "minus-primary", "minus-right", "minus-single", "minus-excluded", "minus-adjacent", "minus-uncovered",
            "plus-start", "plus-stop", "minus-start", "minus-stop"})
    void fixedManualPositionsAndReviewedRegionDecisions(String id) throws Exception {
        var data = new Pm1GeneralTestFixture(mapper); JsonNode expected = data.row(id).path("expected");
        ObjectNode result = evaluate(id, data, data.document(), data.context(id), data.variant(id));
        String reason = expectedReason(id, expected);
        assertStatus(result, expected.path("status").asText(), reason);
        if (!id.contains("start") && !id.contains("stop")) {
            for (String field : List.of("cdsPosition", "proteinPosition", "refCodon", "altCodon", "refAminoAcid", "altAminoAcid"))
                assertEquals(expected.path(field), result.at("/resolvedInput/" + field), field + " / " + id);
        }
        assertEquals(expected.path("strength").asText(), result.at("/evaluation/appliedStrength").asText());
        assertEquals(1, result.path("positionCalculations").asInt());
        assertEquals(0, result.path("providerCalls").asInt());
        assertFalse(result.path("adoptionSupported").asBoolean(true));
        assertEquals(1, result.path("criterionEvaluations").size(), "Overlaps must never accumulate PM1");
        if ("MET".equals(expected.path("status").asText())) {
            assertEquals(expected.path("ruleId"), result.at("/resolvedInput/selectedRule/ruleId"));
            assertEquals(1, result.at("/evaluation/evidenceIds").size());
            assertIdentity(data, result);
        }
        assertEquals("ATGGAACCTGGCTTTAAATAA", data.models.get(0).path("cdsSequence").asText());
        assertEquals("ATGAAAGCTTGGCCATTTGGATAA", data.models.get(1).path("cdsSequence").asText());
        assertEquals(-1, data.models.get(1).path("strand").asInt());
    }

    @ParameterizedTest @ValueSource(strings = {"COVERAGE_MISSING", "COVERAGE_GAP", "MATERIAL_UNREVIEWED",
            "RULE_UNREVIEWED", "ORDINARY_DOMAIN", "QUALIFICATION_UNKNOWN", "QUALIFICATION_UNQUALIFIED", "BENIGN_UNSATISFIED", "BENIGN_UNKNOWN",
            "PATHOGENIC_UNSATISFIED", "PATHOGENIC_UNKNOWN", "SOURCE_SHA", "SOURCE_MISSING", "AUTHORITY_MISSING",
            "REVIEW_SCOPE", "REVIEW_SOURCE", "INVALID_INTERVAL", "OUT_OF_LENGTH", "REFERENCE_AA", "HIGHER_STRENGTH",
            "UNKNOWN_OPERATOR", "REGISTRY_SHA"})
    void absentKnowledgeOrUnqualifiedMaterialsDoNotInventPm1(String mode) throws Exception {
        var data = new Pm1GeneralTestFixture(mapper); ObjectNode material = data.regions.get(0);
        ObjectNode rule = (ObjectNode) material.at("/rules/0"); String status = "NOT_EVALUATED"; String reason;
        switch (mode) {
            case "COVERAGE_MISSING": material.putArray("reviewedCoverage"); reason = "PM1_REGION_COVERAGE_MISSING"; break;
            case "COVERAGE_GAP": ((ObjectNode) material.at("/reviewedCoverage/0")).put("start", 3); reason = "PM1_REGION_COVERAGE_INCOMPLETE"; break;
            case "MATERIAL_UNREVIEWED": ((ObjectNode) material.path("review")).put("status", "UNREVIEWED"); reason = "PM1_REGION_REVIEW_MISSING"; break;
            case "RULE_UNREVIEWED": ((ObjectNode) rule.path("review")).put("status", "UNREVIEWED"); reason = "PM1_REGION_REVIEW_MISSING"; break;
            case "ORDINARY_DOMAIN": rule.put("regionType", "ORDINARY_DOMAIN"); data.reReview(rule, 0); reason = "PM1_ORDINARY_DOMAIN_NOT_QUALIFIED"; break;
            case "QUALIFICATION_UNKNOWN": rule.put("qualification", "UNKNOWN"); data.reReview(rule, 0); reason = "PM1_REGION_QUALIFICATION_UNKNOWN"; break;
            case "QUALIFICATION_UNQUALIFIED": rule.put("qualification", "UNQUALIFIED"); data.reReview(rule, 0); status = "NOT_MET"; reason = "PM1_REGION_NOT_QUALIFIED"; break;
            case "BENIGN_UNSATISFIED": rule.put("benignDistribution", "NOT_SATISFIED"); data.reReview(rule, 0); status = "NOT_MET"; reason = "PM1_BENIGN_DISTRIBUTION_NOT_MET"; break;
            case "PATHOGENIC_UNSATISFIED": rule.put("pathogenicDistribution", "NOT_SATISFIED"); data.reReview(rule, 0); status = "NOT_MET"; reason = "PM1_PATHOGENIC_DISTRIBUTION_NOT_MET"; break;
            case "BENIGN_UNKNOWN": rule.put("benignDistribution", "UNKNOWN"); data.reReview(rule, 0); reason = "PM1_DISTRIBUTION_UNKNOWN"; break;
            case "PATHOGENIC_UNKNOWN": rule.put("pathogenicDistribution", "UNKNOWN"); data.reReview(rule, 0); reason = "PM1_DISTRIBUTION_UNKNOWN"; break;
            case "SOURCE_SHA": ((ObjectNode) material.at("/sourceRecords/0")).put("sha256", "b".repeat(64)); reason = "PM1_MATERIAL_SOURCE_INVALID"; break;
            case "SOURCE_MISSING": material.putArray("sourceRecords"); reason = "PM1_MATERIAL_SOURCE_INVALID"; break;
            case "AUTHORITY_MISSING": data.policies.get(0).putArray("authoritySourceIds"); reason = "PM1_POLICY_AUTHORITY_MISSING"; break;
            case "REVIEW_SCOPE": ((ObjectNode) rule.path("review")).put("scopeSha256", "b".repeat(64)); reason = "PM1_REGION_REVIEW_SCOPE_MISMATCH"; break;
            case "REVIEW_SOURCE": ((ObjectNode) rule.path("review")).putArray("sourceIds").add("UNBOUND_TEST_SOURCE"); reason = "PM1_REGION_REVIEW_SOURCE_MISSING"; break;
            case "INVALID_INTERVAL": rule.put("start", 5).put("end", 2); data.reReview(rule, 0); reason = "PM1_REGION_INTERVAL_INVALID"; break;
            case "OUT_OF_LENGTH": rule.put("end", 7); data.reReview(rule, 0); reason = "PM1_REGION_INTERVAL_INVALID"; break;
            case "REFERENCE_AA": rule = (ObjectNode) material.at("/rules/2"); rule.put("referenceAminoAcid", "R"); data.reReview(rule, 0); reason = "PM1_REFERENCE_RESIDUE_MISMATCH"; break;
            case "HIGHER_STRENGTH": rule.put("strength", "STRONG"); data.reReview(rule, 0); reason = "PM1_STRENGTH_UNSUPPORTED"; break;
            case "UNKNOWN_OPERATOR": rule.put("operator", "GREATER_THAN"); data.reReview(rule, 0); reason = "PM1_REGION_OPERATOR_UNSUPPORTED"; break;
            default: ((ObjectNode) data.profile.path("registryBinding")).put("registrySha256", "b".repeat(64)); reason = "PM1_REGISTRY_IDENTITY_INVALID";
        }
        ObjectNode result = evaluate(mode, data, data.document(), data.context(0), data.target(0));
        assertStatus(result, status, reason);
        if ("AUTHORITY_MISSING".equals(mode)) {
            assertFalse(result.has("evaluation"), "Missing authority cannot claim a complete criterion-evaluation-v2 envelope");
            assertEquals("NOT_EVALUATED", result.at("/evaluationResult/status").asText());
            assertEquals("PM1_POLICY_AUTHORITY_MISSING", result.at("/evaluationResult/reasonCode").asText());
            assertEquals(1, result.path("criterionEvaluations").size());
            JsonNode minimal = result.at("/criterionEvaluations/0");
            assertEquals(Pm1CriterionEvaluator.CRITERION, minimal.path("criterionId").asText());
            assertEquals(result.path("evaluationResult"), minimal.path("evaluationResult"));
            assertFalse(minimal.has("schemaVersion"));
            assertFalse(minimal.has("deterministicTrace"));
        }
    }

    @Test void overlappingConditionsRequireAnExplicitReviewedPriority() throws Exception {
        var data = new Pm1GeneralTestFixture(mapper);
        data.regions.get(0).putArray("precedence");
        ObjectNode conflict = evaluate("overlap-unresolved", data, data.document(), data.context("plus-excluded"), data.variant("plus-excluded"));
        assertStatus(conflict, "NOT_EVALUATED", "PM1_REGION_CONFLICT");
        assertEquals("PRESENT", conflict.at("/evaluation/conflict/status").asText());
        assertEquals("REQUIRED", conflict.at("/evaluation/review/status").asText());
        assertEquals(1, conflict.at("/evaluation/conflict/conflictIds").size());
        assertTrue(conflict.at("/evaluation/conflict/conflictIds/0").asText().matches("conflict-sha256-[0-9a-f]{64}"));
        ObjectNode repeatedConflict = evaluate("overlap-unresolved-repeat", data, data.document(), data.context("plus-excluded"), data.variant("plus-excluded"));
        assertEquals(conflict, repeatedConflict);
        data = new Pm1GeneralTestFixture(mapper); ObjectNode base = (ObjectNode) data.regions.get(0).at("/rules/0");
        ObjectNode overlap = base.deepCopy().put("ruleId", "TEST_ONLY_OVERLAP_SUPPORTING").put("strength", "SUPPORTING");
        data.reReview(overlap, 0); data.regions.get(0).withArray("rules").add(overlap);
        assertStatus(evaluate("overlap-different-strengths", data, data.document(), data.context(0), data.target(0)),
                "NOT_EVALUATED", "PM1_REGION_CONFLICT");
        ObjectNode precedence = data.regions.get(0).withArray("precedence").addObject()
                .put("higherRuleId", "TEST_ONLY_OVERLAP_SUPPORTING").put("lowerRuleId", "plus-region");
        data.reReview(precedence, 0);
        ObjectNode reviewed = evaluate("overlap-reviewed-lower-strength", data, data.document(), data.context(0), data.target(0));
        assertStatus(reviewed, "MET", "PM1_REVIEWED_REGION_MATCH");
        assertEquals("SUPPORTING", reviewed.at("/evaluation/appliedStrength").asText(), "Priority is not maximum strength");
        assertEquals(1, reviewed.path("criterionEvaluations").size());
        assertTrue(reviewed.path("resolvedInput").toString().contains("TEST_ONLY_OVERLAP_SUPPORTING"));
    }

    @ParameterizedTest @CsvSource({"CRITERIA_OBJECT,PM1_POLICY_INVALID", "TRANSCRIPTS_OBJECT,PM1_POLICY_CONTEXT_MISMATCH"})
    void objectShapedArraysCannotMasqueradeAsTheLimitedPolicyContract(String mode, String reason) throws Exception {
        var data = new Pm1GeneralTestFixture(mapper); ObjectNode document = data.document(), context = data.context(0);
        var selector = new AcmgSpecificationSelector(mapper, (ObjectNode) document.path("catalog"), document.path("catalogSha256").asText(),
                (ArrayNode) document.path("policies"));
        ObjectNode selection = selector.select(context, Pm1CriterionEvaluator.CRITERION).receipt();
        ObjectNode policy = data.policies.get(0);
        if ("CRITERIA_OBJECT".equals(mode)) policy.putObject("criterionIds").put("0", Pm1CriterionEvaluator.CRITERION);
        else {
            JsonNode transcript = policy.at("/scope/transcripts/0").deepCopy();
            ((ObjectNode) policy.path("scope")).putObject("transcripts").set("0", transcript);
        }
        selection.set("criterionIds", policy.path("criterionIds").deepCopy());
        selection.put("policySha256", data.canonicalSha256(policy)); selection.remove("selectionId");
        selection.put("selectionId", "cspec-selection-sha256-" + data.canonicalSha256(selection));
        Path directory = artifactDirectory(mode); Files.createDirectories(directory);
        ObjectNode input = mapper.createObjectNode(); input.set("fixedFacts", data.fixedFacts()); input.set("policy", policy);
        input.set("context", context); input.set("selection", selection); input.set("model", data.models.get(0));
        input.set("regions", data.regions.get(0)); input.set("resourceProfile", data.profile);
        mapper.writeValue(directory.resolve("input-before-evaluation.json").toFile(), input);
        ObjectNode result = new Pm1CriterionEvaluator(mapper).evaluate(context, data.target(0), policy, selection,
                data.models.get(0), data.regions.get(0), data.profile);
        mapper.writeValue(directory.resolve("actual-output.json").toFile(), result);
        assertStatus(result, "NOT_EVALUATED", reason);
        assertEquals(0, result.path("positionCalculations").asInt());
    }

    @ParameterizedTest @CsvSource({
            "hgvsCoding,NM_000518.5:c.5A>C,CODING_MISSENSE_GENOMIC_CODING_IDENTITY_MISMATCH",
            "hgvsProtein,NP_000509.2:p.Glu2Asp,CODING_MISSENSE_CODING_PROTEIN_IDENTITY_MISMATCH",
            "proteinPosition,4,CODING_MISSENSE_CODING_PROTEIN_IDENTITY_MISMATCH",
            "codons,GAA/GAG,CODING_MISSENSE_CODING_PROTEIN_IDENTITY_MISMATCH",
            "aminoAcids,E/K,CODING_MISSENSE_CODING_PROTEIN_IDENTITY_MISMATCH",
            "exon,2/2,CODING_MISSENSE_EXON_IDENTITY_MISMATCH",
            "consequence,missense_variant&splice_region_variant,CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED",
            "consequence,missense_variant&splice_acceptor_variant,CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED",
            "consequence,missense_variant&stop_gained,CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED",
            "consequence,missense_variant&synonymous_variant,CODING_MISSENSE_PROTEIN_EFFECT_NOT_SUPPORTED",
            "version,115.0,PM1_SELECTED_TRANSCRIPT_NOT_UNIQUE",
            "transcriptAccession,NM_000518.4,PM1_SELECTED_TRANSCRIPT_NOT_UNIQUE",
            "transcriptId,NM_000518.4,CODING_MISSENSE_ANNOTATION_IDENTITY_MISMATCH"})
    void independentGenomicResolutionRejectsConflictingStoredAnnotations(String field, String value, String reason) throws Exception {
        var data = new Pm1GeneralTestFixture(mapper); var candidate = data.target(0);
        ObjectNode annotation = (ObjectNode) data.row("plus-primary").path("annotation"); annotation.put(field, value);
        candidate.applyTranscriptAnnotations(List.of(mapper.treeToValue(annotation, TranscriptConsequence.class)));
        assertStatus(evaluate("annotation-" + field + "-" + value.replaceAll("[^a-zA-Z0-9]", "_"),
                data, data.document(), data.context(0), candidate), "NOT_EVALUATED", reason);
    }

    @Test void duplicateAndMissingStoredTranscriptCannotPassAndNonSnvCannotUseProteinClaims() throws Exception {
        var data = new Pm1GeneralTestFixture(mapper); var candidate = data.target(0);
        candidate.applyTranscriptAnnotations(List.of(data.annotation(0), data.annotation(0)));
        assertStatus(evaluate("duplicate-annotation", data, data.document(), data.context(0), candidate),
                "NOT_EVALUATED", "PM1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
        candidate.applyTranscriptAnnotations(List.of());
        assertStatus(evaluate("missing-annotation", data, data.document(), data.context(0), candidate),
                "NOT_EVALUATED", "PM1_SELECTED_TRANSCRIPT_NOT_UNIQUE");
        ObjectNode context = data.context(0); ObjectNode variant = (ObjectNode) context.path("variant");
        for (String field : List.of("inputAllele", "normalizedAllele", "evidenceQueryAllele"))
            ((ObjectNode) variant.path(field)).put("alternate", "AC");
        variant.put("variantType", "SMALL_INSERTION");
        variant.put("alleleId", new RuntimeEvidenceV2Adapter(mapper).canonicalAlleleId(variant.path("normalizedAllele"), variant.path("normalization")));
        data.refreshContext(context);
        candidate = RuntimeEvidenceV2ProviderWiringTest.variant(new NormalizedVariantKey("GRCh38", "11", 105, "A", "AC"));
        candidate.applyTranscriptAnnotations(List.of(data.annotation(0)));
        assertStatus(evaluate("non-snv", data, data.document(), context, candidate), "NOT_EVALUATED", "POLICY_CONTEXT_SCOPE_MISMATCH");
        // Exercise the public evaluator too: a syntactically valid prior selection must not bypass native SNV resolution.
        ObjectNode document = data.document();
        var selector = new AcmgSpecificationSelector(mapper, (ObjectNode) document.path("catalog"), document.path("catalogSha256").asText(),
                (ArrayNode) document.path("policies"));
        ObjectNode selection = selector.select(data.context(0), Pm1CriterionEvaluator.CRITERION).receipt();
        selection.put("contextId", context.path("contextId").asText()); selection.remove("selectionId");
        selection.put("selectionId", "cspec-selection-sha256-" + data.canonicalSha256(selection));
        ObjectNode directInput = mapper.createObjectNode(); directInput.set("context", context); directInput.set("selection", selection);
        Path directory = artifactDirectory("non-snv-direct"); Files.createDirectories(directory);
        mapper.writeValue(directory.resolve("input-before-evaluation.json").toFile(), directInput);
        ObjectNode direct = new Pm1CriterionEvaluator(mapper).evaluate(context, candidate, data.policies.get(0), selection,
                data.models.get(0), data.regions.get(0), data.profile);
        mapper.writeValue(directory.resolve("actual-output.json").toFile(), direct);
        assertStatus(direct, "NOT_EVALUATED", "CODING_MISSENSE_NATIVE_SNV_REQUIRED");
    }

    @ParameterizedTest @ValueSource(strings = {"DISEASE", "MOI", "TRANSCRIPT"})
    void sameGeneDoesNotExtendTheReviewedScope(String mode) throws Exception {
        var data = new Pm1GeneralTestFixture(mapper); ObjectNode context = data.context(0);
        if ("DISEASE".equals(mode)) ((ObjectNode) context.path("disease")).put("mondoId", "MONDO:0019497");
        else if ("MOI".equals(mode)) context.put("moi", "AUTOSOMAL_DOMINANT");
        else ((ObjectNode) context.path("transcript")).put("version", 4);
        data.refreshContext(context);
        ObjectNode result = evaluate("scope-" + mode, data, data.document(), context, data.target(0));
        assertStatus(result, "NOT_EVALUATED", "CLAUSE_SEARCH_SCOPE_NOT_COVERED");
        assertFalse(ReviewedRuleBundle.usesPreexistingClause(result));
        assertEquals(0, result.path("positionCalculations").asInt());
    }

    @Test void deterministicTraceBindsMaterialSourceAndActualIndependentPosition() throws Exception {
        var data = new Pm1GeneralTestFixture(mapper); ObjectNode document = data.document();
        ObjectNode result = evaluate("deterministic-first", data, document, data.context(1), data.target(1));
        ObjectNode repeated = evaluate("deterministic-second", data, document, data.context(1), data.target(1));
        assertEquals(result, repeated); assertIdentity(data, result);
        assertEquals(data.policies.get(1), result.path("policy"));
        assertEquals("TTG", result.at("/resolvedInput/altCodon").asText());
        assertEquals(11, result.at("/resolvedInput/cdsPosition").asInt());
        assertEquals(4, result.at("/resolvedInput/proteinPosition").asInt());
        assertTrue(result.toString().contains(data.source(1).path("sha256").asText()));
        assertEquals(data.canonicalSha256(data.models.get(1)), result.at("/resolvedInput/nativeModelSha256").asText());
    }

    private ObjectNode evaluate(String label, Pm1GeneralTestFixture data, ObjectNode document,
                                ObjectNode context, VariantInterpretation candidate) throws Exception {
        Path directory = artifactDirectory(label);
        Files.createDirectories(directory);
        ObjectNode input = mapper.createObjectNode();
        input.set("fixedFacts", data.fixedFacts()); input.set("bundle", document); input.set("context", context);
        ArrayNode annotations = input.putArray("storedAnnotations");
        for (TranscriptConsequence annotation : candidate.getTranscriptAnnotations()) {
            ObjectNode json = mapper.valueToTree(annotation);
            json.put("timestamp", annotation.getTimestamp() == null ? null : annotation.getTimestamp().toString());
            annotations.add(json);
        }
        input.put("candidateAlleleId", candidate.getObservation().getKey().canonicalId());
        mapper.writeValue(directory.resolve("input-before-evaluation.json").toFile(), input);
        ObjectNode result = data.load(directory, document).evaluate(Pm1CriterionEvaluator.CRITERION, context, candidate);
        mapper.writeValue(directory.resolve("actual-output.json").toFile(), result);
        return result;
    }
    private Path artifactDirectory(String label) {
        String configured = System.getProperty("varvis.pm1.artifacts");
        return (configured == null ? temporary : Path.of(configured).resolve("provider")).resolve(label);
    }
    private static String expectedReason(String id, JsonNode expected) {
        if (id.contains("start") || id.contains("stop")) return "CODING_MISSENSE_START_OR_NONCODING_EFFECT_NOT_SUPPORTED";
        if (id.contains("uncovered")) return "PM1_REGION_COVERAGE_INCOMPLETE";
        if (id.contains("excluded")) return "PM1_EXPLICIT_EXCLUSION";
        return "MET".equals(expected.path("status").asText()) ? "PM1_REVIEWED_REGION_MATCH" : "PM1_COVERED_POSITION_NOT_SUPPORTED";
    }
    private static void assertStatus(ObjectNode value, String expected, String reason) {
        JsonNode result = value.has("evaluation") ? value.at("/evaluation/evaluationResult") : value.path("evaluationResult");
        assertEquals(expected, result.path("status").asText(), value.toPrettyString());
        assertEquals(reason, result.path("reasonCode").asText(), value.toPrettyString());
    }
    private static void assertIdentity(Pm1GeneralTestFixture data, ObjectNode value) {
        ObjectNode evaluation = ((ObjectNode) value.path("evaluation")).deepCopy();
        ObjectNode trace = ((ObjectNode) evaluation.path("deterministicTrace")).deepCopy();
        String traceSha = trace.path("traceSha256").asText(); trace.remove("traceSha256");
        assertEquals(data.canonicalSha256(trace), traceSha);
        String evaluationId = evaluation.path("evaluationId").asText(); evaluation.remove(List.of("evaluationId", "conflict", "review"));
        assertEquals("evaluation-sha256-" + data.canonicalSha256(evaluation), evaluationId);
    }
}
