package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MissenseExpertClassificationAdoptionTest {
    void unresolvedExactSubmission() {
        automated();
        reference.withObject("sourceAssertion").putObject("condition").put("submittedRaw", "Orphanet:ORPHA1").put("reportedRaw", "C1:example");
        row.put("bindingStatus", "EXACT_RECORD_BOUND_CONDITION_UNRESOLVED").put("erepoUuid", "test-uuid").put("erepoVersion", "1");
        row.putArray("gapReasons").add("EXACT_SCV_DISEASE_MAPPING_UNRESOLVED");
        row.putObject("originalExpertSummary").put("uuid", "test-uuid").put("docVersion", "1").put("cvId", "1")
                .put("mondoId", "MONDO:0000001").put("classification", "Pathogenic").put("retracted", false);
        var original = row.withObject("originalAssertion");
        original.putArray("condition").addObject().put("tag", "XRef").putObject("attributes").put("DB", "Orphanet").put("ID", "ORPHA1");
        original.putArray("submissionIdentity").addObject().putObject("attributes").put("localKey", "test-uuid|Orphanet:ORPHA1");
    }

    @Test void exactOriginalSubmissionDerivesConditionWithoutFuzzyDiseaseMapping() {
        unresolvedExactSubmission(); var original = row.deepCopy(); bind();
        assertNull(MissenseExpertReferenceEvidence.issue(reference, policy));
        assertEquals("SOURCE_VERIFIED_EXACT", reference.at("/conditionMappings/0/status").asText());
        assertEquals(original, reference.at("/expertEvidence/record"));
    }

    @ParameterizedTest @ValueSource(strings = {"KEY", "XREF", "CONDITION", "UUID", "VERSION", "VARIANT", "DISEASE"})
    void unresolvedDiseaseCannotBeDischargedByAnotherRecord(String fault) {
        unresolvedExactSubmission();
        switch (fault) {
            case "KEY" -> ((ObjectNode) row.at("/originalAssertion/submissionIdentity/0/attributes")).put("localKey", "other|Orphanet:ORPHA1");
            case "XREF" -> row.withObject("originalAssertion").withArray("condition").addObject().put("tag", "XRef").putObject("attributes").put("DB", "Orphanet").put("ID", "ORPHA2");
            case "CONDITION" -> reference.withObject("sourceAssertion").withObject("condition").put("submittedRaw", "Orphanet:ORPHA2");
            case "UUID" -> row.withObject("originalExpertSummary").put("uuid", "other");
            case "VERSION" -> row.withObject("originalExpertSummary").put("docVersion", "2");
            case "VARIANT" -> row.withObject("originalExpertSummary").put("cvId", "2");
            case "DISEASE" -> row.withObject("originalExpertSummary").put("mondoId", "MONDO:0000002");
            default -> fail(fault);
        }
        assertThrows(IllegalArgumentException.class, this::bind);
    }

    @Test void derivedConditionCannotBeChangedAfterSourceBinding() {
        unresolvedExactSubmission(); bind();
        ((ObjectNode) reference.at("/conditionMappings/0")).put("mondoId", "MONDO:0000002");
        assertEquals("EXPERT_DERIVED_CONDITION_BINDING_INVALID", MissenseExpertReferenceEvidence.issue(reference, policy));
    }

    void automated() {
        reference.put("expertClassificationAdoption", MissenseExpertClassificationAdoption.SOURCE_MODE);
        reference.remove("independence");
        ((ObjectNode) policy.path("expertSourceAdoption")).put("mode", MissenseExpertClassificationAdoption.SOURCE_MODE)
                .put("versionHandling", "PRESERVE_ORIGINAL_NOT_RECLASSIFIED");
    }

    @Test void explicitPolicyDerivesReferenceFactsWithoutInventingAnotherHumanReview() {
        automated(); var before = row.deepCopy(); bind();
        assertNull(MissenseExpertReferenceEvidence.issue(reference, policy));
        assertEquals(MissenseExpertClassificationAdoption.SOURCE_STATUS, reference.at("/independence/status").asText());
        assertEquals("TEST_ONLY@1", reference.at("/independence/originalSpecificationVersion").asText());
        assertEquals(before, reference.at("/expertEvidence/record"));
    }

    @Test void automaticSourceModeCannotBorrowLegacyPolicyOptIn() {
        automated(); bind(); ((ObjectNode) policy.path("expertSourceAdoption")).put("mode", MissenseExpertClassificationAdoption.MODE).remove("versionHandling");
        assertEquals("EXPERT_CLASSIFICATION_ROUTE_MISMATCH", MissenseExpertReferenceEvidence.issue(reference, policy));
    }

    @ParameterizedTest @CsvSource({"PM5", "PS1", "RETRACTED", "NONEXPERT", "INCOMPLETE", "SOURCE_CHANGED"})
    void automaticSourceQualificationRejectsInsufficientOriginalEvidence(String fault) {
        automated();
        switch(fault) {
            case "PM5", "PS1" -> ((ObjectNode) row.at("/sourceAppliedCriteriaStatements/0/criteria/0")).put("code", fault).put("original", fault);
            case "RETRACTED" -> ((ObjectNode) row.path("originalAssertion")).put("recordStatus", "replaced");
            case "NONEXPERT" -> ((ObjectNode) row.path("originalAssertion")).put("reviewStatus", "criteria provided, single submitter");
            case "INCOMPLETE" -> row.put("sourceAppliedCriteriaStatus", "UNPARSED_SOURCE_STATEMENT");
            case "SOURCE_CHANGED" -> ((ObjectNode) row.path("originalAssertion")).put("scv", "SCV99999.1");
            default -> fail(fault);
        }
        assertThrows(IllegalArgumentException.class, this::bind);
    }

    void methodCitation() {
        ((ObjectNode) row.at("/sourceAppliedCriteriaStatements/0")).putArray("specificationVersionMentions");
        var binding = reference.putObject("originalSpecificationBinding").put("specificationVersion", "TEST_ONLY@1")
                .put("assertionMethodLabel", "Fictional original guidelines v1")
                .put("url", "https://example.invalid/original-v1.pdf").put("sourceId", "METHOD_SOURCE");
        var method = row.withObject("specification").putArray("originalClinVarMethod").addObject().put("tag", "AttributeSet");
        var children = method.putArray("children");
        children.addObject().put("tag", "Attribute").put("text", binding.path("assertionMethodLabel").asText())
                .putObject("attributes").put("Type", "AssertionMethod");
        children.addObject().put("tag", "Citation").putArray("children").addObject().put("tag", "URL")
                .put("text", binding.path("url").asText());
        reference.withArray("sourceIds").add("METHOD_SOURCE");
        policy.withArray("sources").addObject().put("sourceId", "METHOD_SOURCE").put("version", "TEST_ONLY@1")
                .put("url", binding.path("url").asText()).put("sha256", "c".repeat(64));
    }

    @Test void exactOriginalMethodCitationCanBindVersionWithoutInventingNarrativeVersion() {
        methodCitation(); var original = row.deepCopy(); bind();
        assertNull(MissenseExpertReferenceEvidence.issue(reference, policy));
        assertEquals(original, reference.at("/expertEvidence/record"));
        assertTrue(reference.at("/expertEvidence/record/sourceAppliedCriteriaStatements/0/specificationVersionMentions").isEmpty());
    }

    @Test void methodBindingCannotSilentlyEnableAnUnselectedAdoptionRoute() {
        methodCitation(); bind(); reference.remove("expertClassificationAdoption");
        assertEquals("EXPERT_ORIGINAL_SPECIFICATION_ROUTE_REQUIRED", MissenseExpertReferenceEvidence.issue(reference, policy));
    }

    @ParameterizedTest @CsvSource({
        "NO_BINDING,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH", "WRONG_VERSION,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH",
        "WRONG_LABEL,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH", "WRONG_URL,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH",
        "OTHER_ATTRIBUTE,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH", "SEPARATE_CITATION,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH",
        "COMPETING_METHOD,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH", "NARRATIVE_CONFLICT,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH",
        "STRUCTURED_VERSION,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH", "SUMMARY_VERSION,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH",
        "MISSING_VERSION,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH", "EXTRA_FIELD,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH"})
    void originalMethodBindingMustBeExactAndUnambiguous(String fault, String expected) {
        methodCitation(); var b = (ObjectNode) reference.path("originalSpecificationBinding");
        var method = (ObjectNode) row.at("/specification/originalClinVarMethod/0");
        switch (fault) {
            case "NO_BINDING" -> reference.remove("originalSpecificationBinding");
            case "WRONG_VERSION" -> b.put("specificationVersion", "NEW@2");
            case "WRONG_LABEL" -> b.put("assertionMethodLabel", "Different v1");
            case "WRONG_URL" -> b.put("url", "https://example.invalid/current-v2.pdf");
            case "OTHER_ATTRIBUTE" -> ((ObjectNode) method.at("/children/0/attributes")).put("Type", "ModeOfInheritance");
            case "SEPARATE_CITATION" -> { var citation = method.withArray("children").remove(1); row.withObject("specification").withArray("originalClinVarMethod").add(citation); }
            case "COMPETING_METHOD" -> row.withObject("specification").withArray("originalClinVarMethod").add(method.deepCopy());
            case "NARRATIVE_CONFLICT" -> ((ObjectNode) row.at("/sourceAppliedCriteriaStatements/0")).putArray("specificationVersionMentions").add("2.0");
            case "STRUCTURED_VERSION" -> row.withObject("specification").putObject("originalAssertionMethod").put("version", "2.0");
            case "SUMMARY_VERSION" -> row.withObject("specification").putArray("narrativeVersionMentions").add("2.0");
            case "MISSING_VERSION" -> { b.remove("specificationVersion"); ((ObjectNode) reference.path("independence")).remove("originalSpecificationVersion"); }
            case "EXTRA_FIELD" -> b.put("assumeEquivalent", true);
            default -> fail(fault);
        }
        assertEquals(expected, MissenseExpertClassificationAdoption.sourceIssue(reference, row));
    }

    @ParameterizedTest @CsvSource({"ABSENT", "URL", "HASH", "VERSION"})
    void methodCitationAlsoRequiresRegisteredPinnedSource(String fault) {
        methodCitation(); bind(); var source = (ObjectNode) policy.at("/sources/1");
        switch (fault) {
            case "ABSENT" -> policy.withArray("sources").remove(1);
            case "URL" -> source.put("url", "https://example.invalid/current-v2.pdf");
            case "HASH" -> source.put("sha256", "");
            case "VERSION" -> source.put("version", "");
            default -> fail(fault);
        }
        assertEquals("EXPERT_ORIGINAL_SPECIFICATION_SOURCE_UNBOUND", MissenseExpertReferenceEvidence.issue(reference, policy));
    }

    final ObjectMapper mapper = new ObjectMapper();
    final ObjectNode reference = mapper.createObjectNode().put("variationId", "1").put("geneSymbol", "TEST_ONLY")
            .put("mondoId", "MONDO:0000001").put("classification", "PATHOGENIC").put("expertEvidenceReferenceId", "TEST_ONLY_EXPERT")
            .put("expertClassificationAdoption", MissenseExpertClassificationAdoption.MODE);
    final ObjectNode row, policy = mapper.createObjectNode();
    MissenseExpertClassificationAdoptionTest() {
        reference.putObject("allele").put("assembly", "GRCh38").put("contig", "1").put("position", 1).put("reference", "A").put("alternate", "G");
        reference.putArray("sourceIds").add("TEST_SOURCE");
        reference.putObject("sourceAssertion").put("scv", "SCV00001.1").put("classification", "Pathogenic")
                .put("reviewStatus", "reviewed by expert panel").put("dateLastEvaluated", "2026-01-02");
        var prior = reference.putObject("independence").put("status", "REVIEWED_SUFFICIENT_WITHOUT_PS1_PM5")
                .put("reviewId", "TEST_ONLY_REVIEW").put("classificationAfterExclusion", "PATHOGENIC")
                .put("originalSpecificationVersion", "TEST_ONLY@1").put("dependencyReview", "REVIEWED_NO_COMPARISON_DEPENDENCY");
        prior.putArray("dependencyAlleles"); prior.putArray("criteria").add("PS3"); prior.putArray("sourceIds").add("TEST_SOURCE");
        row = MissenseReviewedReuseFixture.expertRow(mapper, reference, reference.path("sourceAssertion"), "MONDO:0000001", "TEST_ONLY", "TEST_SOURCE");
        policy.putArray("sources").addObject().put("sourceId", "TEST_SOURCE");
        policy.putObject("expertSourceAdoption").put("mode", MissenseExpertClassificationAdoption.MODE).putArray("sourceIds").add("TEST_SOURCE");
    }
    void bind() {
        var audit = mapper.createObjectNode(); audit.putArray("references").add(reference);
        var packet = mapper.createObjectNode().put("schemaVersion", "missense-expert-reference-evidence-v1").put("draft", true)
                .put("reviewStatus", "UNREVIEWED").put("scientificAdoption", false); packet.putArray("references").add(row);
        MissenseExpertReferenceEvidence.bind(audit, packet, mapper.createObjectNode().put("rawSha256", "a".repeat(64))
                .put("canonicalSha256", "b".repeat(64)), Set.of("TEST_SOURCE"));
    }
    @Test void exactExpertOriginalSurvivesWithoutRecombinationAndKeepsWholeRecord() {
        bind(); assertNull(MissenseExpertReferenceEvidence.issue(reference, policy));
        assertEquals(row, reference.at("/expertEvidence/record")); assertFalse(reference.has("qualification"));
    }
    @Test void actualSourceCapitalizationDoesNotChangeClassification() {
        reference.put("classification", "LIKELY_PATHOGENIC");
        ((ObjectNode)reference.path("sourceAssertion")).put("classification", "Likely Pathogenic");
        ((ObjectNode)row.path("originalAssertion")).put("classification", "Likely Pathogenic");
        ((ObjectNode)reference.path("independence")).put("classificationAfterExclusion", "LIKELY_PATHOGENIC");
        bind(); assertNull(MissenseExpertReferenceEvidence.issue(reference, policy));
        assertEquals("Likely Pathogenic", reference.at("/expertEvidence/record/originalAssertion/classification").asText());
    }
    @Test void xmlAndSummaryDateFormattingPreservesBothWholeOriginals() {
        reference.put("classification", "LIKELY_PATHOGENIC");
        ((ObjectNode) reference.path("independence")).put("classificationAfterExclusion", "LIKELY_PATHOGENIC");
        ((ObjectNode) row.path("originalAssertion")).put("classification", "Likely Pathogenic");
        ((ObjectNode) reference.path("sourceAssertion")).put("classification", "Likely pathogenic")
                .put("dateLastEvaluated", "Jan 2, 2026");
        var xml = row.deepCopy(); var summary = reference.path("sourceAssertion").deepCopy();
        bind(); assertNull(MissenseExpertReferenceEvidence.issue(reference, policy));
        assertEquals(xml, reference.at("/expertEvidence/record"));
        assertEquals(summary, reference.path("sourceAssertion"));
    }
    @ParameterizedTest @CsvSource({
        "dateLastEvaluated,'Jan 3, 2026'", "dateLastEvaluated,'Feb 30, 2026'",
        "scv,SCV00001.2", "classification,Benign", "reviewStatus,criteria provided"})
    void differentScientificFactsStillReject(String key, String value) {
        ((ObjectNode) reference.path("sourceAssertion")).put(key, value);
        var error = assertThrows(IllegalArgumentException.class, this::bind);
        assertEquals("EXPERT_EVIDENCE_ASSERTION_MISMATCH", error.getMessage());
    }
    @ParameterizedTest @CsvSource({
        "NO_POLICY,EXPERT_CLASSIFICATION_ROUTE_NOT_ENABLED", "POLICY_SOURCE,EXPERT_CLASSIFICATION_POLICY_INVALID",
        "NO_PACKET,EXPERT_EVIDENCE_BINDING_INVALID"})
    void explicitPolicyAndBoundPacketStillRequired(String fault, String expected) {
        bind();
        if ("NO_POLICY".equals(fault)) policy.remove("expertSourceAdoption");
        else if ("NO_PACKET".equals(fault)) reference.remove("expertEvidence");
        else ((ObjectNode) policy.path("expertSourceAdoption")).putArray("sourceIds").add("OTHER");
        assertEquals(expected, MissenseExpertReferenceEvidence.issue(reference, policy));
    }
    @ParameterizedTest @CsvSource({
        "DOUBLE_ROUTE,EXPERT_CLASSIFICATION_ROUTE_AMBIGUOUS", "CLASS_CHANGED,EXPERT_ORIGINAL_CLASSIFICATION_NOT_PRESERVED",
        "NO_EXPERT,EXPERT_ORIGINAL_CLASSIFICATION_NOT_PRESERVED", "RETRACTED,EXPERT_ORIGINAL_CLASSIFICATION_NOT_PRESERVED",
        "NO_PRIOR,EXPERT_PRIOR_INDEPENDENCE_REVIEW_REQUIRED", "DEPENDENCY,EXPERT_PRIOR_INDEPENDENCE_REVIEW_REQUIRED",
        "DISEASE,EXPERT_SOURCE_DISEASE_MISMATCH", "GAP,EXPERT_SOURCE_GAPS_UNRESOLVED",
        "NO_LIST,EXPERT_COMPLETE_FINAL_CRITERIA_REQUIRED", "MULTIPLE_LISTS,EXPERT_COMPLETE_FINAL_CRITERIA_REQUIRED",
        "SPEC,EXPERT_ORIGINAL_SPECIFICATION_MISMATCH", "LOCATOR,EXPERT_FINAL_CRITERIA_LOCATOR_MISMATCH",
        "FALSE_TOKEN,EXPERT_FINAL_CRITERIA_LOCATOR_MISMATCH", "PRIOR_CRITERIA,EXPERT_PRIOR_CRITERIA_MISMATCH",
        "PS1,EXPERT_FINAL_CRITERIA_UNSUPPORTED_OR_COMPARISON_DEPENDENT", "SUMMARY_PS1,EXPERT_SOURCE_CRITERIA_CONFLICT",
        "MET_PM5,EXPERT_SOURCE_CRITERIA_CONFLICT", "SUFFIX,EXPERT_FINAL_CRITERIA_UNSUPPORTED_OR_COMPARISON_DEPENDENT",
        "SUMMARY_RETRACTED,EXPERT_SOURCE_SUMMARY_CONFLICT", "SUMMARY_STRENGTH,EXPERT_SOURCE_CRITERIA_CONFLICT"})
    void sourceChangesCannotShortcutQualification(String fault, String expected) {
        switch (fault) {
            case "DOUBLE_ROUTE" -> reference.putObject("qualification");
            case "CLASS_CHANGED" -> reference.put("classification", "LIKELY_PATHOGENIC");
            case "NO_EXPERT" -> ((ObjectNode) row.path("originalAssertion")).put("reviewStatus", "single submitter");
            case "RETRACTED" -> ((ObjectNode) row.path("originalAssertion")).put("recordStatus", "retracted");
            case "NO_PRIOR" -> ((ObjectNode) reference.path("independence")).put("status", "UNREVIEWED");
            case "DEPENDENCY" -> reference.withObject("independence").withArray("dependencyAlleles").add(reference.path("allele"));
            case "DISEASE" -> reference.put("mondoId", "MONDO:0000002");
            case "GAP" -> row.withArray("gapReasons").add("UNKNOWN_NEW_GAP");
            case "NO_LIST" -> row.put("sourceAppliedCriteriaStatus", "NOT_FOUND");
            case "MULTIPLE_LISTS" -> row.withArray("sourceAppliedCriteriaStatements").add(row.at("/sourceAppliedCriteriaStatements/0").deepCopy());
            case "SPEC" -> ((ObjectNode) reference.path("independence")).put("originalSpecificationVersion", "NEW@2");
            case "LOCATOR" -> ((ObjectNode) row.at("/sourceAppliedCriteriaStatements/0")).put("xpath", "/OTHER");
            case "FALSE_TOKEN" -> { var c = (ObjectNode) row.at("/sourceAppliedCriteriaStatements/0/criteria/0"); c.put("code", "PS4").put("original", "PS4"); }
            case "PRIOR_CRITERIA" -> reference.withObject("independence").withArray("criteria").add("PS4");
            case "PS1" -> row.withArray("appliedComparisonCriteria").add("PS1");
            case "SUMMARY_PS1" -> row.withArray("summaryMetCriteria").addObject().put("code", "PS1");
            case "MET_PM5" -> row.withArray("criterionAssessments").addObject().put("code", "PM5").put("outcome", "Met");
            case "SUFFIX" -> ((ObjectNode) row.at("/sourceAppliedCriteriaStatements/0/criteria/0")).put("strengthSuffix", "Strong");
            case "SUMMARY_RETRACTED" -> row.putObject("originalExpertSummary").put("retracted", true).put("classification", "Pathogenic").put("mondoId", "MONDO:0000001");
            case "SUMMARY_STRENGTH" -> row.withArray("summaryMetCriteria").addObject().put("code", "PS3").put("original", "PS3_Strong");
            default -> fail(fault);
        }
        assertEquals(expected, MissenseExpertClassificationAdoption.sourceIssue(reference, row));
    }
    @Test void notMetComparisonAndMissingLabelRemainVisibleWithoutBecomingEvidence() {
        row.putNull("originalExpertSummary");
        row.withArray("gapReasons").add("SUMMARY_VERSION_RETRACTION_AND_FINAL_CRITERIA_NOT_BOUND");
        row.withArray("gapReasons").add("SEPIO_CRITERION_LABEL_MISSING");
        row.withArray("criterionAssessments").addObject().put("code", "PS1").put("outcome", "Not Met");
        row.withArray("criterionAssessments").addObject().putNull("code").put("outcome", "Met").put("defaultStrength", "Strong");
        bind(); assertNull(MissenseExpertReferenceEvidence.issue(reference, policy));
        assertEquals(row, reference.at("/expertEvidence/record"));
    }
    @Test void fullSourceLocatorsAreAllowedWithoutAllowingFilesystemPaths() {
        assertDoesNotThrow(() -> MissenseMaterialPreparation.noPrivatePaths(row));
        assertDoesNotThrow(() -> MissenseMaterialPreparation.noPrivatePaths(mapper.createObjectNode().put("pointer", "/data/evidenceLine/0/criterion")));
        for (String path : Set.of("C:/Users/private.txt", "file:///D:/secret", "\\\\server\\share", "/Users/private", "/data/../private", "/data/C:/secret"))
            for (String field : Set.of("xpath", "pointer", "other"))
                assertThrows(IllegalArgumentException.class, () -> MissenseMaterialPreparation.noPrivatePaths(mapper.createObjectNode().put(field, path)));
        assertThrows(IllegalArgumentException.class, () -> MissenseMaterialPreparation.noPrivatePaths(mapper.createObjectNode().put("other", "/data/secret")));
    }
}
