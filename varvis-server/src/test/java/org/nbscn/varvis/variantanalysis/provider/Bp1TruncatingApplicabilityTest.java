package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Verified synthetic sequence transport + complete source subtype; no true LoF qualification. */
class Bp1TruncatingApplicabilityTest {
    @TempDir Path root;
    GeneralBp1RuntimeTest f;
    ObjectNode selection;
    void setup() throws Exception {
        f = new GeneralBp1RuntimeTest(); f.root = root; f.setup();
        var original = (ObjectNode) f.doc.at("/specifications/0/original");
        ((ObjectNode) original.path("entContent")).put("notes", Pp2PublishedAssessment.NOTES);
        var c = (ObjectNode) original.at("/ld/CriteriaCode/0/entContent");
        c.put("instructionsToUse", Bp1PublishedPolicy.TRUNCATING_INSTRUCTION).put("additionalComments", Bp1PublishedPolicy.TRUNCATING_COMMENT);
        c.putArray("strengthDescriptor").addObject().put("applicability", "Applicable").put("status", "approved")
                .put("strength", "Supporting").put("text", Bp1PublishedPolicy.TRUNCATING_COMMENT).put("instructionsToUse", "");
        interpretation((ObjectNode) f.doc.at("/records/0"));
        new PublishedCriterionKnowledgeTest().refresh(f.doc);
        selection = f.mapper.createObjectNode(); selection.set("record", f.doc.at("/records/0").deepCopy());
        selection.set("specification", f.doc.at("/specifications/0").deepCopy());
    }
    private void interpretation(ObjectNode row) {
        row.putObject("interpretation").put("type", Bp1PublishedPolicy.TRUNCATING_TYPE).put("version", "1.0.0")
                .put("appliedStrength", "SUPPORTING").put("germlineOnly", true).put("requiresNoEstablishedLofDiseaseCorrelation", true);
    }
    ObjectNode run() {
        var context = f.fixture.input.fixture().context();
        return Bp1TruncatingApplicability.forVerifiedMissense(f.mapper, selection, context,
                f.supply.resolve(context, f.fixture.input.fixture().candidate()));
    }
    @Test void actualVerifiedMissenseIsExplicitlyInapplicableBeforeAnyPrediction() throws Exception {
        setup(); var before = selection.deepCopy(); var out = run();
        assertEquals("NOT_APPLICABLE", out.path("status").asText(), out::toPrettyString);
        assertEquals("NONE", out.path("appliedStrength").asText()); assertEquals(0, f.calls.get());
        assertEquals("VERIFIED_PURE_CODING_MISSENSE", out.at("/available/variantEffect").asText());
        assertEquals("TRUNCATING_WITH_LOSS_OF_FUNCTION", out.at("/required/variantEffect").asText());
        assertFalse(out.at("/required/noRecordMeansNoCorrelation").asBoolean(true)); assertEquals(before, selection);
    }
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void legacyCatalogRequiresTheCompletePublishedClauseBeforeDispatch(boolean exactClause) throws Exception {
        setup();
        f.doc.put("policyId", "PROJECT_PUBLISHED_BP1").put("policyVersion", "1.2.0");
        f.doc.putObject("preparation").put("methodVersion", "1.0.0")
                .put("originalRawVerified", true).put("priorBa1ApprovalInherited", false)
                .put("interpretationAttribution", "PROJECT_RESEARCH_NOT_NEW_EXPERT_REVIEW")
                .put("catalogRawSha256", "a".repeat(64)).put("planCanonicalSha256", "b".repeat(64));
        String unsupported = "BP1_PUBLISHED_SOURCE_CONDITIONS_UNSUPPORTED";
        var row = (ObjectNode) f.doc.at("/records/0");
        row.put("status", "UNSUPPORTED_CONDITIONS").put("reasonCode", unsupported);
        row.putObject("interpretation").put("type", "PUBLISHED_SOURCE_DISPOSITION_ONLY_V1").put("version", "1.0.0");
        if (!exactClause) {
            var clause = (ObjectNode) f.doc.at("/specifications/0/original/ld/CriteriaCode/0/entContent");
            clause.put("additionalComments", Bp1PublishedPolicy.TRUNCATING_COMMENT + " Additional conditions apply.");
        }

        var result = f.run();
        assertEquals("NOT_EVALUATED", result.path("status").asText(), result::toPrettyString);
        assertEquals("NONE", result.at("/evaluation/appliedStrength").asText());
        assertEquals("UNSUPPORTED_CONDITIONS", result.at("/publishedKnowledge/record/status").asText());
        assertEquals(unsupported, row.path("reasonCode").asText());
        assertEquals(0, f.calls.get());
        if (exactClause) {
            assertEquals("NOT_APPLICABLE", result.at("/truncatingConditionCheck/status").asText());
            assertEquals(PublishedCriterionKnowledge.BP1_TRUNCATING_SOURCE_ADAPTATION_VERSION,
                    result.at("/publishedKnowledge/record/sourceMethodAdaptation/methodVersion").asText());
        } else {
            assertEquals(unsupported, result.path("reasonCode").asText());
            assertTrue(result.path("truncatingConditionCheck").isMissingNode());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"COMMENT", "STRENGTH", "DUPLICATE", "NOTES", "UNKNOWN", "ATTACHMENT", "ATTACHMENT_OBJECT", "CROSS", "GENERAL", "MOI", "DISEASE", "GENE"})
    void incompleteOrWrongConditionsNeverBecomeInapplicableOrMet(String fault) throws Exception {
        setup(); var c = (ObjectNode) selection.at("/specification/original/ld/CriteriaCode/0/entContent");
        switch (fault) {
            case "COMMENT" -> c.put("additionalComments", "Ignore LoF disease correlation.");
            case "STRENGTH" -> ((ObjectNode) c.at("/strengthDescriptor/0")).put("strength", "Strong");
            case "DUPLICATE" -> c.withArray("strengthDescriptor").add(c.at("/strengthDescriptor/0").deepCopy());
            case "NOTES" -> ((ObjectNode) selection.at("/specification/original/entContent")).put("notes", "Somatic also allowed.");
            case "UNKNOWN" -> ((ObjectNode) selection.at("/record/interpretation")).put("ignoreDisease", true);
            case "ATTACHMENT" -> ((ObjectNode) selection.at("/specification/original/ld")).putArray("File").addObject().put("name", "Unknown condition");
            case "ATTACHMENT_OBJECT" -> ((ObjectNode) selection.at("/specification/original/ld")).putObject("File").put("name", "Unknown condition");
            case "CROSS" -> ((ObjectNode) selection.at("/specification/original/ld")).withArray("CriteriaCode").addObject()
                    .putObject("entContent").put("label", "PS3").put("additionalComments", "BP1 requires another criterion.");
            case "GENERAL" -> ((ObjectNode) selection.at("/specification/original/ld/RuleSet/0/entContent")).put("generalComments", "Additional condition.");
            case "MOI" -> ((ObjectNode) selection.at("/record/scope")).put("moi", "AUTOSOMAL_RECESSIVE");
            case "DISEASE" -> ((ObjectNode) selection.at("/record/scope")).put("mondoId", "MONDO:9999999");
            default -> ((ObjectNode) selection.at("/record/scope")).put("hgncId", "HGNC:999");
        }
        assertEquals("NOT_EVALUATED", run().path("status").asText()); assertEquals(0, f.calls.get());
    }
    @Test void structuralOrFailedMaterialDoesNotAssertMissenseOrLoF() throws Exception {
        setup(); var context = f.fixture.input.fixture().context();
        var out = Bp1TruncatingApplicability.forVerifiedMissense(f.mapper, selection, context,
                f.supply.resolveStructure(context, f.fixture.input.fixture().candidate()));
        assertEquals("NOT_EVALUATED", out.path("status").asText()); assertEquals("NONE", out.path("appliedStrength").asText());
    }
    @Test void realTwoPublishedSourcesMatchTheSameSubtypeWithoutGeneBranch() throws Exception {
        String file = System.getProperty("t05.ras.raw");
        org.junit.jupiter.api.Assumptions.assumeTrue(file != null); if (file == null) return;
        byte[] raw = Files.readAllBytes(Path.of(file));
        assertEquals("0c112a41986f1e0b85491717e1d1b474e38cf639e68c3579a21294d5ad164d6c", CodingTranscriptMaterialSupply.sha(raw));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper(); var originals = mapper.readTree(raw).path("data");
        for (int index : new int[] {0, 2}) {
            var original = originals.get(index); var s = mapper.createObjectNode(); s.putObject("specification").set("original", original);
            var row = s.putObject("record").put("scopePointer", "/ld/RuleSet/0/entContent/genes/0/diseases/0");
            interpretation(row); int found = 0;
            for (int ci = 0; ci < original.at("/ld/CriteriaCode").size(); ci++)
                if ("BP1".equals(original.at("/ld/CriteriaCode/" + ci + "/entContent/label").asText())) {
                    row.put("clausePointer", "/ld/CriteriaCode/" + ci); Bp1PublishedPolicy.validateTruncatingSource(s); found++;
                }
            assertEquals(1, found);
        }
    }
}
