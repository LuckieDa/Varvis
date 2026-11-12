package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparation;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** T05: native pinned CSPEC descriptors and synthetic peer outcomes. The projection is produced
 * by the real SelectedEvidencePreparation fixture, with its existing synthetic transport.
 * MAIN's module/sourceSnapshot/wrapper authentication is outside this mechanical helper test. */
class PublishedPm1CrossCriterionConditionsTest {
    private static final String GN006_TEXT = "*   Active site residues in PAH include: Tyr138, Arg158, Val245, Tyr268, Thr278, Pro279, Glu289, Ala300, Asp315, Phe331, Ala345, Gly346, Ser349, Tyr377\n*   Substrate binding residues in PAH are: 46-48, 63-69\n*   Cofactor binding residues in PAH are: His285, His290, Glu330, 246-266, 280-283, 322-326, 377-379\n*   Do not apply if PP3\\_Strong applies";
    private static final String GN006_DESCRIPTOR_SHA = "d3933230cf53fa0d147ee221612271b0a8df475338e483fe8c48e52c071de6ab";
    // raw-008-contents-0.json SHA b47be311e6b44f781297b4e8ef78bdaaec0fced164f8d8b9d5984007a1dd6fe4;
    // /data/3/ld/CriteriaCode/13/entContent/strengthDescriptor/3, independently reread before this test was written.
    private static final String GN112_TEXT = "Located in a mutational hot spot and/or critical and well-established functional domain (_e.g._ active site of an enzyme) without benign variation\u00a0\n\n*   The variant must be rare (meeting PM2\\_Supporting) in order to be considered for PM1.\n*   Met by a rare variant within the pore\u00a0helix (amino acids 300 to 320). This region is known to be a critical region of KCNQ1 and has been confirmed to show an absence of likely benign or benign variants listed in gnomAD.";
    private static final String GN112_DESCRIPTOR_SHA = "358c4b28a854099c84dee3262c563a6804566f4e8c215a5ddada997d73b80dcd";
    // raw-023-contents-75.json SHA 06c53a01bbc17e1ad05d1e6acc5a4363dcb8899cc9e9ffcb0242af95fbe57895;
    // /data/1/ld/CriteriaCode/18/entContent/strengthDescriptor/3, independently reread before this test was written.
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private ObjectNode projection;

    private ObjectNode projection() throws Exception {
        if (projection == null) {
            // Reuse a package-private service fixture without mocking SelectedEvidencePreparation
            // or replacing LegacyContextProjection. Its coordinator creates actual selected v3/v2 views.
            var type = Class.forName("org.nbscn.varvis.variantanalysis.service.SelectedEvidencePreparationTest");
            var constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
            var fixture = constructor.newInstance();
            ReflectionTestUtils.setField(java.util.Objects.requireNonNull(fixture), "temporary", temporary);
            Object prepared = Objects.requireNonNull(ReflectionTestUtils.invokeMethod(java.util.Objects.requireNonNull(fixture), "prepare", false, false, false, 1, true));
            SelectedEvidencePreparation selected = Objects.requireNonNull(ReflectionTestUtils.invokeMethod(prepared, "result"));
            VariantInterpretation candidate = Objects.requireNonNull(ReflectionTestUtils.invokeMethod(prepared, "original"));
            projection = selected.legacyProjection(candidate.getCandidateId(), "context-one").snapshot();
        }
        return projection;
    }

    private ObjectNode descriptor(String specification) {
        boolean requirePm2 = "GN112".equals(specification);
        var source = mapper.createObjectNode().put("applicability", "Applicable").put("id", "006")
                .put("instructionsToUse", "").put("status", "approved").put("strength", "Moderate")
                .put("strengthSepioID", "SEPIO:0000216").put("text", requirePm2 ? GN112_TEXT : GN006_TEXT)
                .put("type", "EvidenceLineStrength");
        source.putArray("specificationType").add(requirePm2 ? "Disease-specific" : "Gene-specific");
        if (requirePm2) source.put("defaultPoint", 2);
        return source;
    }

    private ObjectNode selection(String specification) {
        boolean requirePm2 = "GN112".equals(specification);
        int clauseIndex = requirePm2 ? 18 : 13;
        var selection = mapper.createObjectNode();
        var record = selection.putObject("record").put("clausePointer", "/ld/CriteriaCode/" + clauseIndex);
        var relation = record.putObject("interpretation").putArray("crossCriterionRelations").addObject()
                .put("criterionId", "ACMG_AMP_2015:" + (requirePm2 ? "PM2" : "PP3"))
                .put("relation", requirePm2 ? PublishedPm1CrossCriterionConditions.REQUIRE : PublishedPm1CrossCriterionConditions.EXCLUDE)
                .put("minimumStrength", requirePm2 ? "SUPPORTING" : "STRONG")
                .put("sourcePointer", "/ld/CriteriaCode/" + clauseIndex + "/entContent/strengthDescriptor/3");
        var original = selection.putObject("specification").putObject("original");
        original.put("entId", specification);
        original.putObject("entContent").put("version", requirePm2 ? "1.0" : "2.0");
        var clauses = original.putObject("ld").putArray("CriteriaCode");
        for (int index = 0; index <= clauseIndex; index++) clauses.addObject();
        var descriptors = ((ObjectNode) clauses.path(clauseIndex)).putObject("entContent").put("label", "PM1").putArray("strengthDescriptor");
        for (int index = 0; index < 3; index++) descriptors.addObject();
        descriptors.add(descriptor(specification));
        relation.put("sourceSha256", requirePm2 ? GN112_DESCRIPTOR_SHA : GN006_DESCRIPTOR_SHA);
        return selection;
    }

    private ObjectNode relation(ObjectNode selection) { return (ObjectNode) selection.at("/record/interpretation/crossCriterionRelations/0"); }
    private ObjectNode source(ObjectNode selection) { return (ObjectNode) selection.at("/specification/original").at(relation(selection).path("sourcePointer").asText()); }
    private void rebindSource(ObjectNode selection) { relation(selection).put("sourceSha256", adapter.canonicalSha256(source(selection))); }

    private ObjectNode module(String criterion, String status, String strength) throws Exception {
        var selected = projection();
        var module = mapper.createObjectNode().put("syntheticPeerOutcomeVector", true);
        module.set("candidateId", selected.path("candidateId").deepCopy());
        module.set("hypothesisId", selected.path("hypothesisId").deepCopy());
        module.set("context", selected.path("evaluationContext").deepCopy());
        var evaluation = module.putObject("evaluation").put("criterionId", "ACMG_AMP_2015:" + criterion)
                .put("contextId", selected.at("/evaluationContext/contextId").asText()).put("appliedStrength", strength);
        evaluation.putObject("evaluationResult").put("status", status).put("reasonCode", "SYNTHETIC_PEER_OUTCOME_VECTOR");
        return module;
    }

    private PublishedPm1CrossCriterionConditions.Facts facts(ObjectNode module) throws Exception {
        return PublishedPm1CrossCriterionConditions.fromActualModule(mapper, projection(), module, "/evaluation", "/context");
    }
    private ObjectNode assess(ObjectNode selection, PublishedPm1CrossCriterionConditions.Facts facts) {
        return PublishedPm1CrossCriterionConditions.assess(mapper, selection, facts);
    }

    @Test void nativeDescriptorTextsMatchTheIndependentlyFrozenSourcePins() {
        assertEquals(GN006_DESCRIPTOR_SHA, adapter.canonicalSha256(descriptor("GN006")));
        assertEquals(GN112_DESCRIPTOR_SHA, adapter.canonicalSha256(descriptor("GN112")));
        assertTrue(GN006_TEXT.contains("Do not apply if PP3\\_Strong applies"));
        assertTrue(GN112_TEXT.contains("The variant must be rare (meeting PM2\\_Supporting) in order to be considered for PM1."));
        for (String specification : new String[]{"GN006", "GN112"}) {
            var selected = selection(specification);
            assertDoesNotThrow(() -> PublishedPm1CrossCriterionConditions.validate(relation(selected), selected, adapter));
        }
    }

    @ParameterizedTest @CsvSource({
            "GN112,PM2,MET,SUPPORTING,READY", "GN112,PM2,MET,MODERATE,READY",
            "GN112,PM2,MET,STRONG,READY", "GN112,PM2,MET,VERY_STRONG,READY",
            "GN112,PM2,NOT_MET,NONE,NOT_MET", "GN112,PM2,NOT_EVALUATED,NONE,NOT_EVALUATED",
            "GN112,PM2,MISSING,NONE,NOT_EVALUATED", "GN006,PP3,MET,SUPPORTING,READY",
            "GN006,PP3,MET,MODERATE,READY", "GN006,PP3,MET,STRONG,NOT_MET",
            "GN006,PP3,MET,VERY_STRONG,NOT_MET", "GN006,PP3,NOT_MET,NONE,READY",
            "GN006,PP3,NOT_EVALUATED,NONE,NOT_EVALUATED", "GN006,PP3,MISSING,NONE,NOT_EVALUATED"})
    void independentSourceTruthMatrixRequiresActualAdoptedPeerOutcome(String specification, String criterion,
            String status, String strength, String expected) throws Exception {
        var selected = selection(specification);
        var supplied = "MISSING".equals(status) ? null : facts(module(criterion, status, strength));
        var result = assess(selected, supplied);
        assertEquals(expected, result.path("status").asText(), result::toPrettyString);
        assertFalse(result.path("selectionAbsenceMeansNegative").asBoolean(true));
        assertEquals("READY".equals(expected) ? "MET" : "NOT_MET".equals(expected) ? "NOT_MET" : "UNAVAILABLE",
                result.at("/checks/0/status").asText());
        if ("NOT_EVALUATED".equals(expected)) {
            assertEquals("PM1_PUBLISHED_PEER_RESULT_UNAVAILABLE", result.path("reasonCode").asText());
            assertEquals("ACTUAL_SAME_CONTEXT_ADOPTED_CRITERION_RESULT", result.at("/checks/0/missingInformation").asText());
        } else if ("NOT_MET".equals(expected))
            assertEquals("PM2".equals(criterion) ? "PM1_PUBLISHED_REQUIRED_PEER_NOT_MET" : "PM1_PUBLISHED_PEER_EXCLUSION_APPLIES",
                    result.path("reasonCode").asText());
        else assertEquals("PM1_PUBLISHED_PEER_CONDITIONS_MET", result.path("reasonCode").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"CONTEXT", "EVALUATION_CONTEXT", "CANDIDATE", "HYPOTHESIS", "PLAN_HYPOTHESIS",
            "POINTER", "MISSING_CONTEXT", "MISSING_EVALUATION_CONTEXT", "CRITERION", "STATUS", "MET_NONE", "NEGATIVE_STRENGTH", "PROJECTION_BINDING", "REHASHED_PROJECTION_CONTEXT"})
    void moduleIdentityAndOutcomeMustMatchTheActualSelectedProjection(String mutation) throws Exception {
        var selected = projection(); var actual = module("PM2", "MET", "SUPPORTING");
        String evaluationPointer = "/evaluation", contextPointer = "/context", expected;
        switch (mutation) {
            case "CONTEXT" -> { ((ObjectNode) actual.path("context")).put("contextId", "OTHER_CONTEXT"); expected = "PM1_PEER_CONTEXT_MISMATCH"; }
            case "EVALUATION_CONTEXT" -> { ((ObjectNode) actual.path("evaluation")).put("contextId", "OTHER_CONTEXT"); expected = "PM1_PEER_EVALUATION_CONTEXT_MISMATCH"; }
            case "CANDIDATE" -> { actual.put("candidateId", "OTHER_CANDIDATE"); expected = "PM1_PEER_CANDIDATE_OR_HYPOTHESIS_MISMATCH"; }
            case "HYPOTHESIS" -> { actual.put("hypothesisId", "OTHER_HYPOTHESIS"); expected = "PM1_PEER_CANDIDATE_OR_HYPOTHESIS_MISMATCH"; }
            case "PLAN_HYPOTHESIS" -> {
                actual.remove("candidateId"); actual.remove("hypothesisId");
                var plan = actual.putObject("plan"); plan.set("candidateId", selected.path("candidateId").deepCopy()); plan.put("hypothesisId", "OTHER_HYPOTHESIS");
                expected = "PM1_PEER_CANDIDATE_OR_HYPOTHESIS_MISMATCH";
            }
            case "POINTER" -> { evaluationPointer = "evaluation"; expected = "PM1_PEER_POINTER_INVALID"; }
            case "MISSING_CONTEXT" -> { contextPointer = "/missing"; expected = "PM1_PEER_CONTEXT_MISMATCH"; }
            case "MISSING_EVALUATION_CONTEXT" -> { ((ObjectNode) actual.path("evaluation")).remove("contextId"); expected = "PM1_PEER_EVALUATION_CONTEXT_MISMATCH"; }
            case "CRITERION" -> { ((ObjectNode) actual.path("evaluation")).put("criterionId", "ACMG_AMP_2015:PM1"); expected = "PM1_PEER_CRITERION_UNSUPPORTED"; }
            case "STATUS" -> { ((ObjectNode) actual.at("/evaluation/evaluationResult")).put("status", "UNAVAILABLE"); expected = "PM1_PEER_STATUS_INVALID"; }
            case "MET_NONE" -> { ((ObjectNode) actual.path("evaluation")).put("appliedStrength", "NONE"); expected = "PM1_PEER_STRENGTH_INVALID"; }
            case "NEGATIVE_STRENGTH" -> { ((ObjectNode) actual.at("/evaluation/evaluationResult")).put("status", "NOT_MET"); expected = "PM1_PEER_STRENGTH_INVALID"; }
            case "PROJECTION_BINDING" -> { selected.put("bindingSha256", "0".repeat(64)); expected = "PM1_PEER_PROJECTION_BINDING_INVALID"; }
            case "REHASHED_PROJECTION_CONTEXT" -> {
                var context = (ObjectNode) selected.path("evaluationContext"); context.put("moi", "AUTOSOMAL_DOMINANT");
                context.put("contextId", adapter.canonicalContextId(context));
                var copy = selected.deepCopy(); copy.remove("bindingSha256"); selected.put("bindingSha256", adapter.canonicalSha256(copy));
                expected = "PM1_PEER_PROJECTION_CONTEXT_MISMATCH";
            }
            default -> throw new AssertionError(mutation);
        }
        String atEvaluation = evaluationPointer, atContext = contextPointer;
        var failure = assertThrows(IllegalArgumentException.class, () -> PublishedPm1CrossCriterionConditions.fromActualModule(
                mapper, selected, actual, atEvaluation, atContext));
        assertEquals(expected, failure.getMessage());
    }

    @Test void originalSelectedContextAndPlanCandidateIdentityAreLegitimateAlternativePointers() throws Exception {
        var selected = projection(); var actual = module("PM2", "MET", "SUPPORTING");
        actual.set("context", selected.path("originalContext").deepCopy());
        ((ObjectNode) actual.path("evaluation")).put("contextId", selected.at("/originalContext/contextId").asText());
        var plan = actual.putObject("plan"); plan.set("candidateId", Objects.requireNonNull(actual.remove("candidateId"))); plan.set("hypothesisId", Objects.requireNonNull(actual.remove("hypothesisId")));
        var supplied = facts(actual);
        assertEquals("READY", assess(selection("GN112"), supplied).path("status").asText());
        assertTrue(PublishedPm1CrossCriterionConditions.sameContext(supplied, selected.path("evaluationContext"), mapper));
    }

    @Test void nestedPp3CriterionWithoutOwnContextIdRequiresTheActualProjectionInItsModule() throws Exception {
        var actual = module("PP3", "MET", "STRONG");
        var evaluation = (ObjectNode) Objects.requireNonNull(actual.remove("evaluation")); evaluation.remove("contextId");
        actual.remove("context"); actual.putObject("criteria").set("ACMG_AMP_2015:PP3", evaluation);
        actual.set("sourceProjection", projection().deepCopy());
        String pointer = "/criteria/ACMG_AMP_2015:PP3", context = "/sourceProjection/evaluationContext";
        var supplied = PublishedPm1CrossCriterionConditions.fromActualModule(mapper, projection(), actual, pointer, context);
        assertEquals("NOT_MET", assess(selection("GN006"), supplied).path("status").asText());
        ((ObjectNode) actual.path("sourceProjection")).put("bindingSha256", "f".repeat(64));
        assertEquals("PM1_PEER_EVALUATION_CONTEXT_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1CrossCriterionConditions.fromActualModule(mapper, projection(), actual, pointer, context)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"HASH", "POINTER", "INACTIVE", "UNAPPROVED", "STRENGTH", "PHRASE", "EXTRA_SOURCE_CONDITION", "NEGATED_SOURCE", "OPERATOR", "THRESHOLD", "CRITERION", "UNKNOWN_FIELD", "DUPLICATE"})
    void wrongSourceOrUnsupportedConditionNeverBecomesAnExecutableRequirement(String mutation) {
        var selected = selection("GN006"); var rule = relation(selected);
        String expected = "PM1_PUBLISHED_PEER_SOURCE_INVALID";
        switch (mutation) {
            case "HASH" -> rule.put("sourceSha256", "f".repeat(64));
            case "POINTER" -> rule.put("sourcePointer", "/ld/CriteriaCode/13/entContent/strengthDescriptor/2");
            case "INACTIVE" -> { source(selected).put("applicability", "Not Applicable"); rebindSource(selected); }
            case "UNAPPROVED" -> { source(selected).put("status", "not approved"); rebindSource(selected); }
            case "STRENGTH" -> { source(selected).put("strength", "Strong"); rebindSource(selected); }
            case "PHRASE" -> { source(selected).put("text", "Synthetic unrelated text without the original condition"); rebindSource(selected); expected = "PM1_PUBLISHED_PEER_SOURCE_SEMANTICS_UNSUPPORTED"; }
            case "EXTRA_SOURCE_CONDITION" -> { source(selected).put("text", source(selected).path("text").asText() + " Only with another unimplemented condition."); rebindSource(selected); expected = "PM1_PUBLISHED_PEER_SOURCE_SEMANTICS_UNSUPPORTED"; }
            case "NEGATED_SOURCE" -> { source(selected).put("text", "Do not use the following rule: " + source(selected).path("text").asText()); rebindSource(selected); expected = "PM1_PUBLISHED_PEER_SOURCE_SEMANTICS_UNSUPPORTED"; }
            case "OPERATOR" -> { rule.put("relation", "ASSUME_NOT_SELECTED_IS_NEGATIVE"); expected = "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED"; }
            case "THRESHOLD" -> { rule.put("minimumStrength", "SUPPORTING"); expected = "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED"; }
            case "CRITERION" -> { rule.put("criterionId", "ACMG_AMP_2015:PM2"); expected = "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED"; }
            case "UNKNOWN_FIELD" -> { rule.put("approvedByRequest", true); expected = "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED"; }
            case "DUPLICATE" -> { ((ArrayNode) selected.at("/record/interpretation/crossCriterionRelations")).add(rule.deepCopy()); expected = "PM1_PUBLISHED_PEER_CONDITION_DUPLICATE"; }
            default -> throw new AssertionError(mutation);
        }
        var result = assess(selected, null);
        assertEquals("NOT_EVALUATED", result.path("status").asText()); assertEquals(expected, result.path("reasonCode").asText());
    }

    @Test void repeatedSameModuleIsIdempotentButDifferentModuleBodiesCannotReplaceTheSameCriterion() throws Exception {
        var actual = module("PM2", "MET", "SUPPORTING");
        var first = facts(actual); var duplicate = facts(actual.deepCopy());
        var combined = PublishedPm1CrossCriterionConditions.combine(first, duplicate);
        assertEquals(1, assess(selection("GN112"), combined).path("checks").size());
        var otherModule = actual.deepCopy().put("otherActualModuleBody", true);
        assertEquals("PM1_PEER_RESULT_CONFLICT", assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1CrossCriterionConditions.combine(first, facts(otherModule))).getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"GN006", "GN112"})
    void sourceConditionCannotDisappearWhenTheInterpretedRelationIsOmitted(String spec) {
        var selected = selection(spec); var rule = relation(selected).deepCopy(); var nativeSource = source(selected).deepCopy();
        ((ArrayNode) selected.at("/record/interpretation/crossCriterionRelations")).removeAll();
        var failed = assertThrows(IllegalArgumentException.class, () -> PublishedPm1CrossCriterionConditions.requireDeclaredSourceCondition(
                rule, nativeSource, selected, adapter));
        assertEquals("PM1_PUBLISHED_REQUIRED_PEER_CONDITION_OMITTED", failed.getMessage());
    }

    @Test void differentPeerCriteriaCanShareOneProjectionWithoutBorrowingEachOthersDecision() throws Exception {
        var pm2 = facts(module("PM2", "MET", "SUPPORTING")); var pp3 = facts(module("PP3", "MET", "STRONG"));
        var combined = PublishedPm1CrossCriterionConditions.combine(pm2, pp3);
        assertEquals("READY", assess(selection("GN112"), combined).path("status").asText());
        assertEquals("NOT_MET", assess(selection("GN006"), combined).path("status").asText());
        assertEquals("NOT_EVALUATED", assess(selection("GN006"), pm2).path("status").asText());
        assertEquals("NOT_EVALUATED", assess(selection("GN112"), pp3).path("status").asText());
    }

    @ParameterizedTest @CsvSource({"MET,STRONG,NOT_MET", "MET,MODERATE,READY", "NOT_MET,NONE,READY", "NOT_EVALUATED,NONE,NOT_EVALUATED"})
    void pm5StrongExclusionUsesTheAdoptedSameContextDecision(String status, String strength, String expected) throws Exception {
        var factory = new PublishedPm1Pm5ExclusionTest();
        ObjectNode selected = Objects.requireNonNull(ReflectionTestUtils.invokeMethod(factory, "selection", "GN032"));
        var actual = module("PM5", status, strength);
        var out = assess(selected, facts(actual));
        assertEquals(expected, out.path("status").asText(), out::toPrettyString);
        assertEquals("NOT_EVALUATED", assess(selected, null).path("status").asText());
        // This is a synthetic peer-outcome vector, not a claim of a qualified real TCF4 reference.
    }

    @Test void actualReferenceContainerPointersBindNestedPm5Evaluation() throws Exception {
        var projected = projection(); var actual = module("PM5", "MET", "STRONG");
        var nested = mapper.createObjectNode(); nested.set("context", actual.path("context"));
        nested.putArray("criterionEvaluations").add(actual.path("evaluation"));
        actual.remove("context"); actual.remove("evaluation"); actual.putObject("evaluations").set("PM5", nested);
        actual.set("sourceProjection", projected.deepCopy());
        var peers = PublishedPm1CrossCriterionConditions.fromActualModule(mapper, projected, actual,
                "/evaluations/PM5/criterionEvaluations/0", "/evaluations/PM5/context");
        var factory = new PublishedPm1Pm5ExclusionTest();
        ObjectNode selected = Objects.requireNonNull(ReflectionTestUtils.invokeMethod(factory, "selection", "GN032"));
        assertEquals("NOT_MET", assess(selected, peers).path("status").asText());
    }

    @Test void actualModuleAndProjectionAreCopiedAndReturnedAssessmentCannotMutateCapturedFacts() throws Exception {
        var selected = projection(); var context = selected.path("evaluationContext").deepCopy();
        var actual = module("PM2", "MET", "SUPPORTING"); var supplied = facts(actual); var clause = selection("GN112");
        var before = assess(clause, supplied); var baseline = before.deepCopy();
        actual.removeAll(); selected.removeAll();
        ((ObjectNode) before.path("binding")).removeAll();
        ((ObjectNode) before.at("/checks/0/available")).put("status", "NOT_MET");
        assertEquals(baseline, assess(clause, supplied));
        assertTrue(PublishedPm1CrossCriterionConditions.sameContext(supplied, context, mapper));
        var other = context.deepCopy(); ((ObjectNode) other).put("contextId", "OTHER_CONTEXT");
        assertFalse(PublishedPm1CrossCriterionConditions.sameContext(supplied, other, mapper));
    }

    private ObjectNode sourceLocationRule(ObjectNode selected, int start, int end, String reference) {
        var sourceBinding = relation(selected);
        var rule = mapper.createObjectNode().put("ruleId", "SYNTHETIC_SOURCE_ATOM")
                .put("start", start).put("end", end).put("effect", "SUPPORT").put("strength", "MODERATE")
                .put("regionType", "NONE".equals(reference) ? "CRITICAL_FUNCTIONAL_REGION" : "CRITICAL_RESIDUE");
        rule.set("sourcePointer", sourceBinding.path("sourcePointer").deepCopy());
        rule.set("sourceSha256", sourceBinding.path("sourceSha256").deepCopy());
        var residues = rule.putArray("referenceResidues");
        if (!"NONE".equals(reference)) residues.addObject().put("position", start).put("aminoAcid", reference);
        return rule;
    }

    private ObjectNode sourceLocationSelection(String spec, int start, int end, String reference) {
        var selected = selection(spec); var interpretation = (ObjectNode) selected.at("/record/interpretation");
        interpretation.put("type", PublishedPm1RegionAssessment.TYPE).put("version", PublishedPm1RegionAssessment.VERSION)
                .put("consequenceScope", "PURE_MISSENSE_SNV").put("qualificationBasis", "PUBLISHED_EXPERT_SPECIFICATION");
        interpretation.putObject("proteinIdentity").put("mode", "SOURCE_TRANSCRIPT_DEFINED");
        interpretation.putArray("rules").add(sourceLocationRule(selected, start, end, reference));
        interpretation.putArray("precedence"); interpretation.putArray("residualConditions");
        return selected;
    }

    private ObjectNode literalLocationModel() {
        // Engineering CDS: literal Tyr138 supplies the source residue. No real KCNQ1/PAH sequence claim.
        var codons = new java.util.ArrayList<>(java.util.Collections.nCopies(400, "AAA"));
        codons.set(0, "ATG"); codons.set(137, "TAT"); codons.set(376, "TAT");
        return mapper.createObjectNode().put("proteinLength", 400).put("cdsSequence", String.join("", codons) + "TAA");
    }

    private ObjectNode assessLocation(ObjectNode selected, int position, PublishedPm1CrossCriterionConditions.Facts peers) throws Exception {
        return new PublishedPm1RegionAssessment(mapper).assess(selected, literalLocationModel(),
                mapper.createObjectNode().put("proteinPosition", position), (ObjectNode) projection().path("evaluationContext"), peers);
    }

    @ParameterizedTest @CsvSource({
            "GN112,300,320,NONE", "GN006,138,138,Y", "GN006,158,158,R", "GN006,245,245,V",
            "GN006,268,268,Y", "GN006,278,278,T", "GN006,279,279,P", "GN006,289,289,E",
            "GN006,300,300,A", "GN006,315,315,D", "GN006,331,331,F", "GN006,345,345,A",
            "GN006,346,346,G", "GN006,349,349,S", "GN006,377,377,Y", "GN006,46,48,NONE",
            "GN006,63,69,NONE", "GN006,285,285,H", "GN006,290,290,H", "GN006,330,330,E",
            "GN006,246,266,NONE", "GN006,280,283,NONE", "GN006,322,326,NONE", "GN006,377,379,NONE"})
    void eachLiteralSourceAtomHasExactlyOnePermittedCompiledGeometry(String spec, int start, int end, String reference) {
        var selected = selection(spec); var rule = sourceLocationRule(selected, start, end, reference);
        assertDoesNotThrow(() -> PublishedPm1CrossCriterionConditions.requireDeclaredSourceCondition(rule, source(selected), selected, adapter));
    }

    @ParameterizedTest @CsvSource({
            "GN112,299,320", "GN112,300,321", "GN112,299,321", "GN112,301,319", "GN112,300,300", "GN112,46,48",
            "GN006,45,48", "GN006,46,49", "GN006,47,48", "GN006,46,46", "GN006,46,69", "GN006,62,69",
            "GN006,246,265", "GN006,280,284", "GN006,322,325", "GN006,378,379", "GN006,137,139", "GN006,137,137"})
    void sourceHashCannotAuthorizeAnInventedNarrowedWidenedOrCombinedInterval(String spec, int start, int end) {
        var selected = selection(spec); var rule = sourceLocationRule(selected, start, end, "NONE");
        var failure = assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1CrossCriterionConditions.requireDeclaredSourceCondition(rule, source(selected), selected, adapter));
        assertEquals("PM1_PUBLISHED_PEER_SOURCE_REGION_MISMATCH", failure.getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"WRONG_AA", "MISSING_AA", "MISSING_LIST", "WRONG_POSITION", "EXTRA_RESIDUE", "WRONG_REGION_TYPE", "FRACTIONAL_POSITION"})
    void sourceResidueRequiresItsExactReferenceAminoAcidAndCriticalResidueShape(String mutation) {
        var selected = selection("GN006"); var rule = sourceLocationRule(selected, 138, 138, "Y");
        var residues = (ArrayNode) rule.path("referenceResidues"); var residue = (ObjectNode) residues.get(0);
        switch (mutation) {
            case "WRONG_AA" -> residue.put("aminoAcid", "F");
            case "MISSING_AA" -> residue.remove("aminoAcid");
            case "MISSING_LIST" -> rule.remove("referenceResidues");
            case "WRONG_POSITION" -> residue.put("position", 137);
            case "EXTRA_RESIDUE" -> residues.add(residue.deepCopy());
            case "WRONG_REGION_TYPE" -> rule.put("regionType", "CRITICAL_FUNCTIONAL_REGION");
            case "FRACTIONAL_POSITION" -> residue.put("position", 138.5);
            default -> throw new AssertionError(mutation);
        }
        assertEquals("PM1_PUBLISHED_PEER_SOURCE_RESIDUE_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1CrossCriterionConditions.requireDeclaredSourceCondition(rule, source(selected), selected, adapter)).getMessage());
    }

    @ParameterizedTest @CsvSource({
            "GN112,300,320,NONE,299,NOT_EVALUATED", "GN112,300,320,NONE,300,MET",
            "GN112,300,320,NONE,320,MET", "GN112,300,320,NONE,321,NOT_EVALUATED",
            "GN006,46,48,NONE,45,NOT_EVALUATED", "GN006,46,48,NONE,46,MET",
            "GN006,46,48,NONE,48,MET", "GN006,46,48,NONE,49,NOT_EVALUATED",
            "GN006,138,138,Y,137,NOT_EVALUATED", "GN006,138,138,Y,138,MET", "GN006,138,138,Y,139,NOT_EVALUATED"})
    void exactSourceGeometryAndActualPeerFactsPreserveInclusiveSourceBoundaries(String spec, int start, int end,
            String reference, int observed, String expected) throws Exception {
        var selected = sourceLocationSelection(spec, start, end, reference);
        var peers = facts(module("GN112".equals(spec) ? "PM2" : "PP3", "GN112".equals(spec) ? "MET" : "NOT_MET",
                "GN112".equals(spec) ? "SUPPORTING" : "NONE"));
        var out = assessLocation(selected, observed, peers);
        assertEquals(expected, out.path("status").asText(), out::toPrettyString);
        assertEquals("MET".equals(expected) ? "MODERATE" : "NONE", out.path("appliedStrength").asText());
    }

    @ParameterizedTest @CsvSource({"GN112,299,321", "GN112,301,319", "GN006,45,49", "GN006,47,48", "GN006,46,69"})
    void passingPeerCannotRescueIncorrectlyCompiledSourceGeometry(String spec, int start, int end) throws Exception {
        var selected = sourceLocationSelection(spec, start, end, "NONE");
        var peers = facts(module("GN112".equals(spec) ? "PM2" : "PP3", "GN112".equals(spec) ? "MET" : "NOT_MET",
                "GN112".equals(spec) ? "SUPPORTING" : "NONE"));
        var out = assessLocation(selected, start, peers);
        assertEquals("NOT_EVALUATED", out.path("status").asText()); assertEquals("NONE", out.path("appliedStrength").asText());
        assertEquals("PM1_PUBLISHED_PEER_SOURCE_REGION_MISMATCH", out.path("reasonCode").asText());
    }

    @Test void aSourceCorrectReferenceResidueStillHasToAgreeWithTheCompleteCds() throws Exception {
        var selected = sourceLocationSelection("GN006", 138, 138, "Y"); var model = literalLocationModel();
        String cds = model.path("cdsSequence").asText(); model.put("cdsSequence", cds.substring(0, 411) + "TTT" + cds.substring(414));
        var out = new PublishedPm1RegionAssessment(mapper).assess(selected, model, mapper.createObjectNode().put("proteinPosition", 138));
        assertEquals("NOT_EVALUATED", out.path("status").asText());
        assertEquals("PM1_PUBLISHED_REFERENCE_RESIDUE_MISMATCH", out.path("reasonCode").asText());
    }

    @Test void theSameFiniteSourceGrammarUsesItsOwnCoordinatesAndNotGeneOrCoordinateSwitches() {
        var selected = selection("GN112");
        source(selected).put("text", GN112_TEXT.replace("300 to 320", "101 to 103").replace("KCNQ1", "SYNTHETIC_GENE"));
        rebindSource(selected); var exact = sourceLocationRule(selected, 101, 103, "NONE");
        assertDoesNotThrow(() -> PublishedPm1CrossCriterionConditions.requireDeclaredSourceCondition(exact, source(selected), selected, adapter));
        var stale = sourceLocationRule(selected, 300, 320, "NONE");
        assertEquals("PM1_PUBLISHED_PEER_SOURCE_REGION_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1CrossCriterionConditions.requireDeclaredSourceCondition(stale, source(selected), selected, adapter)).getMessage());
    }

    @Test void unconditionalLegacySourceDoesNotAcquireTheNewConditionalGeometryContract() throws Exception {
        var selected = sourceLocationSelection("GN006", 138, 138, "Y");
        source(selected).put("text", "SYNTHETIC unconditional critical residue description for the existing legacy operator.");
        rebindSource(selected);
        ((ObjectNode) selected.at("/record/interpretation/rules/0")).set("sourceSha256", relation(selected).path("sourceSha256").deepCopy());
        ((ArrayNode) selected.at("/record/interpretation/crossCriterionRelations")).removeAll();
        assertEquals("MET", assessLocation(selected, 138, null).path("status").asText());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sameSourceResidueAndCofactorOverlapAreOneSupportInEitherInputOrder(boolean reverse) throws Exception {
        var selected = sourceLocationSelection("GN006", 377, 379, "NONE");
        var rules = (ArrayNode) selected.at("/record/interpretation/rules");
        ((ObjectNode) rules.get(0)).put("ruleId", "source-cofactor-interval");
        var residue = sourceLocationRule(selected, 377, 377, "Y").put("ruleId", "source-active-residue");
        if (reverse) rules.insert(0, residue); else rules.add(residue);
        var peers = facts(module("PP3", "NOT_MET", "NONE"));
        var out = assessLocation(selected, 377, peers);
        assertEquals("MET", out.path("status").asText(), out::toPrettyString);
        assertEquals("MODERATE", out.path("appliedStrength").asText()); assertEquals(2, out.path("matchedRules").size());
        assertEquals("source-active-residue", out.at("/selectedRule/ruleId").asText());
        assertEquals(1, out.at("/sourceOverlapResolution/criterionApplications").asInt());
        assertFalse(out.at("/sourceOverlapResolution/scientificPrecedenceClaimed").asBoolean(true));
        assertEquals("NOT_MET", assessLocation(selected, 377, facts(module("PP3", "MET", "STRONG"))).path("status").asText());
    }
}
