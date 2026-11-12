package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** T05 source semantics only. These are native descriptor excerpts, not fabricated adopted PM5 results.
 * GN032@6.0: raw-012-contents-20.json SHA 14bc809c7cc81fe63d672425229a5dd3b1bd8905260e8be7eeeac5a8b6b381ee,
 * /data/4/ld/CriteriaCode/22/entContent/strengthDescriptor/2.
 * GN035@6.0: raw-013-contents-25.json SHA c66c2ca82b3b26c643e6ed8ed1c4ca5c5f385608a834f63889f8f5e4c3bcd376,
 * /data/2/ld/CriteriaCode/26/entContent/strengthDescriptor/2.
 * Actual PM5 adoption, same-context resolution and missing-result handling belong to the central Facts adapter. */
class PublishedPm1Pm5ExclusionTest {
    private static final String SOURCE_TEXT = "Missense change at an amino acid residue where a different missense change determined to be pathogenic has been seen before.\n* \u22652 different missense changes affecting the amino acid residue.\n* Do not apply PM1 in these situations.";
    private static final String DESCRIPTOR_SHA = "76f91a35c8c0e5d07e3990e9de811b3f4fce198941ae6487f13d1be574876127";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    private ObjectNode descriptor() {
        var source = mapper.createObjectNode().put("applicability", "Applicable").put("id", "0056")
                .put("instructionsToUse", "").put("status", "approved").put("strength", "Strong")
                .put("strengthSepioID", "SEPIO:0000330").put("text", SOURCE_TEXT).put("type", "EvidenceLineStrength");
        source.putArray("specificationType").add("Strength"); return source;
    }
    private ObjectNode selection(String specification) {
        boolean tcf4 = "GN032".equals(specification); int pm1Index = tcf4 ? 8 : 9, pm5Index = tcf4 ? 22 : 26;
        String gene = tcf4 ? "TCF4" : "FOXG1";
        var selected = mapper.createObjectNode();
        selected.putObject("record").put("clausePointer", "/ld/CriteriaCode/" + pm1Index);
        var original = selected.putObject("specification").putObject("original").put("entId", specification);
        original.putObject("entContent").put("version", "6.0");
        var clauses = original.putObject("ld").putArray("CriteriaCode");
        for (int index = 0; index <= pm5Index; index++) clauses.addObject();
        var pm1 = ((ObjectNode) clauses.path(pm1Index)).putObject("entContent").put("label", "PM1").put("applicability", "Applicable");
        pm1.putArray("gene").add(gene);
        var pm5 = ((ObjectNode) clauses.path(pm5Index)).putObject("entContent").put("label", "PM5")
                .put("applicability", "Applicable").put("additionalComments", "");
        pm5.putArray("gene").add(gene);
        var strengths = pm5.putArray("strengthDescriptor"); strengths.addObject(); strengths.addObject(); strengths.add(descriptor());
        selected.withObject("/record").putObject("interpretation").putArray("crossCriterionRelations").addObject()
                .put("criterionId", "ACMG_AMP_2015:PM5").put("relation", "EXCLUDE_IF_MET_AT_LEAST").put("minimumStrength", "STRONG")
                .put("sourcePointer", "/ld/CriteriaCode/" + pm5Index + "/entContent/strengthDescriptor/2")
                .put("sourceSha256", DESCRIPTOR_SHA);
        return selected;
    }
    private ObjectNode relation(ObjectNode selected) { return (ObjectNode) selected.at("/record/interpretation/crossCriterionRelations/0"); }
    private ObjectNode source(ObjectNode selected) { return (ObjectNode) selected.at("/specification/original").at(relation(selected).path("sourcePointer").asText()); }
    private ObjectNode pm1(ObjectNode selected) { return (ObjectNode) selected.at("/specification/original").at(selected.at("/record/clausePointer").asText()).path("entContent"); }
    private ObjectNode pm5(ObjectNode selected) { return (ObjectNode) selected.at("/specification/original/ld/CriteriaCode/22/entContent"); }
    private void refresh(ObjectNode selected) { relation(selected).put("sourceSha256", adapter.canonicalSha256(source(selected))); }

    @Test void independentlyPinnedStrongDescriptorRetainsTheCompleteThreeSourceSentences() {
        assertEquals(DESCRIPTOR_SHA, adapter.canonicalSha256(descriptor()));
        assertTrue(SOURCE_TEXT.contains("\u22652 different missense changes affecting the amino acid residue."));
        assertTrue(SOURCE_TEXT.endsWith("Do not apply PM1 in these situations."));
    }

    @Test void expressCoUseDoesNotRequireAnAdoptedPm5Result() {
        var selected = selection("GN032");
        source(selected).put("strength", "Moderate").put("text", "* Not mutually exclusive with PM1.");
        selected.withObject("/record/interpretation").putArray("crossCriterionRelations");
        assertDoesNotThrow(() -> PublishedPm1Pm5Exclusion.requireDeclared(selected, adapter));
        ((ObjectNode) pm5(selected).at("/strengthDescriptor/2")).put("instructionsToUse", "Apply an additional combination condition.");
        assertEquals("PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED", assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1Pm5Exclusion.requireDeclared(selected, adapter)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"GN032", "GN035"})
    void nativeSameSpecificationPm5StrongClauseCanDefineTheConditionalPm1Exclusion(String specification) {
        var selected = selection(specification); var before = selected.deepCopy();
        assertDoesNotThrow(() -> PublishedPm1Pm5Exclusion.validate(relation(selected), selected, adapter));
        assertDoesNotThrow(() -> PublishedPm1Pm5Exclusion.requireDeclared(selected, adapter));
        assertFalse(relation(selected).path("sourcePointer").asText().startsWith(selected.at("/record/clausePointer").asText() + "/"));
        assertEquals(before, selected);
    }

    @ParameterizedTest @ValueSource(strings = {"ABSENT", "EMPTY", "MATCH"})
    void sourceDiseaseCanBeInheritedOrMustMatchItsExplicitScope(String vector) {
        var selected = selection("GN032");
        if ("EMPTY".equals(vector)) pm5(selected).putArray("disease");
        if ("MATCH".equals(vector)) {
            selected.withObject("/record").putObject("scope").put("mondoId", "MONDO:0000001");
            pm5(selected).putArray("disease").add("MONDO:0000001");
        }
        assertDoesNotThrow(() -> PublishedPm1Pm5Exclusion.validate(relation(selected), selected, adapter));
        assertDoesNotThrow(() -> PublishedPm1Pm5Exclusion.requireDeclared(selected, adapter));
    }

    @ParameterizedTest @ValueSource(strings = {"OTHER_DISEASE", "MISSING_TARGET", "BAD_TYPE", "BAD_ENTRY", "NULL"})
    void explicitDiseaseRestrictionsCannotBeBorrowedFromAnotherDiseaseOrAnUnresolvedScope(String vector) {
        var selected = selection("GN032");
        selected.withObject("/record").putObject("scope").put("mondoId", "MONDO:0000001");
        pm5(selected).putArray("disease").add("MONDO:0000001");
        switch (vector) {
            case "OTHER_DISEASE" -> pm5(selected).putArray("disease").add("MONDO:0000002");
            case "MISSING_TARGET" -> selected.withObject("/record").remove("scope");
            case "BAD_TYPE" -> pm5(selected).put("disease", "MONDO:0000001");
            case "BAD_ENTRY" -> pm5(selected).withArray("disease").add("UNKNOWN_DISEASE");
            case "NULL" -> pm5(selected).putNull("disease");
            default -> throw new AssertionError(vector);
        }
        assertEquals("PM1_PUBLISHED_PM5_DISEASE_SCOPE_MISMATCH", assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1Pm5Exclusion.validate(relation(selected), selected, adapter)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"MISSING_ARRAY", "EMPTY_ARRAY", "UNRELATED_RELATION"})
    void aCompiledArtifactCannotOmitTheMandatorySourceExclusion(String vector) {
        var selected = selection("GN032"); var interpretation = selected.withObject("/record/interpretation");
        switch (vector) {
            case "MISSING_ARRAY" -> interpretation.remove("crossCriterionRelations");
            case "EMPTY_ARRAY" -> interpretation.putArray("crossCriterionRelations");
            case "UNRELATED_RELATION" -> interpretation.putArray("crossCriterionRelations").addObject()
                    .put("criterionId", "ACMG_AMP_2015:PM2").put("relation", "NOT_MUTUALLY_EXCLUSIVE");
            default -> throw new AssertionError(vector);
        }
        assertEquals("PM1_PUBLISHED_REQUIRED_PEER_CONDITION_OMITTED", assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1Pm5Exclusion.requireDeclared(selected, adapter)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"APPENDED", "UNKNOWN_PM1", "MISSING_EXCLUSION", "OVERLAPPING_GENE", "MISSING_GENE", "WRONG_HASH"})
    void sourceDiscoveryRejectsUnknownOrPartiallyRemovedActiveRestrictions(String vector) {
        var selected = selection("GN032");
        String expected = "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED";
        switch (vector) {
            case "APPENDED" -> { source(selected).put("text", SOURCE_TEXT + " Another unimplemented condition."); refresh(selected); }
            case "UNKNOWN_PM1" -> { source(selected).put("text", "Apply PM1 only after another unimplemented condition."); refresh(selected); }
            case "MISSING_EXCLUSION" -> { source(selected).put("text", SOURCE_TEXT.replace("* Do not apply PM1 in these situations.", "")); refresh(selected); }
            case "OVERLAPPING_GENE" -> { pm5(selected).withArray("gene").add("OTHER_GENE"); expected = "PM1_PUBLISHED_PM5_GENE_SCOPE_MISMATCH"; }
            case "MISSING_GENE" -> { pm5(selected).remove("gene"); expected = "PM1_PUBLISHED_PM5_GENE_SCOPE_MISMATCH"; }
            case "WRONG_HASH" -> { relation(selected).put("sourceSha256", "0".repeat(64)); expected = "PM1_PUBLISHED_PM5_SOURCE_INVALID"; }
            default -> throw new AssertionError(vector);
        }
        assertEquals(expected, assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1Pm5Exclusion.requireDeclared(selected, adapter)).getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"INACTIVE_DESCRIPTOR", "INACTIVE_CLAUSE", "UNRELATED_GENE", "OTHER_DISEASE"})
    void explicitlyInapplicableOrUnrelatedSourceBranchesDoNotInventACurrentRestriction(String vector) {
        var selected = selection("GN032");
        switch (vector) {
            case "INACTIVE_DESCRIPTOR" -> source(selected).put("applicability", "Not Applicable");
            case "INACTIVE_CLAUSE" -> { pm5(selected).put("applicability", "Not Applicable"); source(selected).put("applicability", "Not Applicable"); }
            case "UNRELATED_GENE" -> pm5(selected).putArray("gene").add("OTHER_GENE");
            case "OTHER_DISEASE" -> {
                selected.withObject("/record").putObject("scope").put("mondoId", "MONDO:0000001");
                pm5(selected).putArray("disease").add("MONDO:0000002");
            }
            default -> throw new AssertionError(vector);
        }
        selected.withObject("/record/interpretation").putArray("crossCriterionRelations");
        assertDoesNotThrow(() -> PublishedPm1Pm5Exclusion.requireDeclared(selected, adapter));
    }

    @ParameterizedTest @ValueSource(strings = {"CLAUSE_EMPTY_DESCRIPTORS", "CLAUSE_INACTIVE_DESCRIPTORS", "DESCRIPTOR_INSTRUCTION_ONLY", "INACTIVE_CLAUSE_ACTIVE_EXCLUSION"})
    void unknownOrContradictorySourceRestrictionCannotDisappearDuringScanning(String fault) {
        var selected = selection("GN032"); var nativeDescriptor = source(selected);
        String expected = "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED";
        switch (fault) {
            case "CLAUSE_EMPTY_DESCRIPTORS" -> { pm5(selected).put("instructionsToUse", "PM1 requires an additional source condition."); pm5(selected).putArray("strengthDescriptor"); }
            case "CLAUSE_INACTIVE_DESCRIPTORS" -> { pm5(selected).put("additionalComments", "PM1 requires an additional source condition."); nativeDescriptor.put("applicability", "Not Applicable"); }
            case "DESCRIPTOR_INSTRUCTION_ONLY" -> { nativeDescriptor.put("text", "Unrelated supporting material.").put("instructionsToUse", "Do not apply PM1 with this result."); }
            case "INACTIVE_CLAUSE_ACTIVE_EXCLUSION" -> { pm5(selected).put("applicability", "Not Applicable"); expected = "PM1_PUBLISHED_PM5_SOURCE_INVALID"; }
            default -> throw new AssertionError(fault);
        }
        ((ObjectNode) selected.path("record")).putObject("interpretation").putArray("crossCriterionRelations");
        var failure = assertThrows(IllegalArgumentException.class, () -> PublishedPm1Pm5Exclusion.requireDeclared(selected, adapter));
        assertEquals(expected, failure.getMessage());
    }

    @ParameterizedTest @ValueSource(strings = {"CRITERION", "RELATION", "THRESHOLD", "EXTRA_RELATION", "HASH", "OTHER_SPEC_POINTER",
            "PM1_POINTER", "WRONG_SOURCE_LABEL", "INACTIVE_PM5", "INACTIVE_DESCRIPTOR", "UNAPPROVED", "MODERATE", "GENE", "MISSING_GENE",
            "DUPLICATE_GENE", "MISSING_EXCLUSION", "MISSING_COUNT", "CHANGED_COUNT", "EXTRA_TEXT", "INSTRUCTIONS", "CLAUSE_COMMENTS", "EXTRA_DESCRIPTOR"})
    void sourceIdentityFullConditionAndSameGeneDomainCannotBeReplacedOrPartiallyQuoted(String mutation) {
        var selected = selection("GN032"); var relation = relation(selected);
        String expected = "PM1_PUBLISHED_PM5_SOURCE_INVALID";
        switch (mutation) {
            case "CRITERION" -> { relation.put("criterionId", "ACMG_AMP_2015:PP3"); expected = "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED"; }
            case "RELATION" -> { relation.put("relation", "REQUIRE_MET_AT_LEAST"); expected = "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED"; }
            case "THRESHOLD" -> { relation.put("minimumStrength", "MODERATE"); expected = "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED"; }
            case "EXTRA_RELATION" -> { relation.put("ignoreMissingReferenceCount", true); expected = "PM1_PUBLISHED_CROSS_CRITERION_CONDITION_UNSUPPORTED"; }
            case "HASH" -> relation.put("sourceSha256", "0".repeat(64));
            case "OTHER_SPEC_POINTER" -> relation.put("sourcePointer", "/otherSpecification/ld/CriteriaCode/22/entContent/strengthDescriptor/2");
            case "PM1_POINTER" -> selected.withObject("/record").put("clausePointer", "/not-a-pm1-clause");
            case "WRONG_SOURCE_LABEL" -> pm5(selected).put("label", "PM1");
            case "INACTIVE_PM5" -> pm5(selected).put("applicability", "Not Applicable");
            case "INACTIVE_DESCRIPTOR" -> { source(selected).put("applicability", "Not Applicable"); refresh(selected); }
            case "UNAPPROVED" -> { source(selected).put("status", "not approved"); refresh(selected); }
            case "MODERATE" -> { source(selected).put("strength", "Moderate"); refresh(selected); }
            case "GENE" -> { pm5(selected).putArray("gene").add("OTHER_GENE"); expected = "PM1_PUBLISHED_PM5_GENE_SCOPE_MISMATCH"; }
            case "MISSING_GENE" -> { pm1(selected).remove("gene"); expected = "PM1_PUBLISHED_PM5_GENE_SCOPE_MISMATCH"; }
            case "DUPLICATE_GENE" -> { pm5(selected).withArray("gene").add("TCF4"); expected = "PM1_PUBLISHED_PM5_GENE_SCOPE_MISMATCH"; }
            case "MISSING_EXCLUSION" -> { source(selected).put("text", SOURCE_TEXT.replace("* Do not apply PM1 in these situations.", "")); refresh(selected); expected = "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED"; }
            case "MISSING_COUNT" -> { source(selected).put("text", "Do not apply PM1 in these situations."); refresh(selected); expected = "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED"; }
            case "CHANGED_COUNT" -> { source(selected).put("text", SOURCE_TEXT.replace("\u22652", "\u22653")); refresh(selected); expected = "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED"; }
            case "EXTRA_TEXT" -> { source(selected).put("text", SOURCE_TEXT + " Additional unimplemented condition."); refresh(selected); expected = "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED"; }
            case "INSTRUCTIONS" -> { source(selected).put("instructionsToUse", "Apply only after another unimplemented condition."); refresh(selected); expected = "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED"; }
            case "CLAUSE_COMMENTS" -> { pm5(selected).put("additionalComments", "Another unimplemented condition."); expected = "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED"; }
            case "EXTRA_DESCRIPTOR" -> { source(selected).put("newCondition", "Not implemented"); refresh(selected); expected = "PM1_PUBLISHED_PM5_SOURCE_SEMANTICS_UNSUPPORTED"; }
            default -> throw new AssertionError(mutation);
        }
        assertEquals(expected, assertThrows(IllegalArgumentException.class,
                () -> PublishedPm1Pm5Exclusion.validate(relation, selected, adapter)).getMessage());
    }
}
