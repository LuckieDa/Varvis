package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Source state semantics only; never grants the unsupported HCM PP2 interpretation. */
class Pp2InactiveCrossReferenceTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode selection(ObjectNode other) throws Exception {
        var f = new GeneralPp2RuntimeTest(); f.root = root; f.setup();
        var doc = f.document();
        ((ObjectNode) doc.at("/specifications/0/original/ld")).withArray("CriteriaCode").addObject().set("entContent", other);
        f.refresh(doc);
        var out = mapper.createObjectNode();
        out.set("record", doc.at("/records/0")); out.set("specification", doc.at("/specifications/0"));
        return out;
    }

    private ObjectNode retired() {
        var c = mapper.createObjectNode().put("label", "PM1").put("applicability", "Not applicable")
                .put("additionalComments", "TEST_ONLY historical enrichment discussion (see PP2 specifications).");
        c.putArray("strengthDescriptor").addObject().put("applicability", "Not Applicable")
                .put("text", "TEST_ONLY PP2 reference in an inactive strength");
        return c;
    }

    @Test void fullyInactiveReferenceDoesNotCreateDependency() throws Exception {
        var source = selection(retired()); var before = source.deepCopy();
        assertNull(Pp2PublishedAssessment.validate(source, true));
        assertEquals(before, source);
        assertEquals("PP2_GERMLINE_SCOPE_NOT_DECLARED", Pp2PublishedAssessment.validate(source, false));
    }

    @ParameterizedTest @ValueSource(strings = {"Applicable", "Unknown", "", "Not applicable with restrictions"})
    void unknownOrActiveClauseStillBlocks(String state) throws Exception {
        var c = retired().put("applicability", state);
        assertEquals("PP2_PUBLISHED_CROSS_RULE_CONDITION_UNSUPPORTED", Pp2PublishedAssessment.validate(selection(c), true));
    }

    @ParameterizedTest @ValueSource(strings = {"Applicable", "Unknown", ""})
    void inactiveClauseWithUnresolvedOrActiveStrengthStillBlocks(String state) throws Exception {
        var c = retired(); ((ObjectNode) c.at("/strengthDescriptor/0")).put("applicability", state);
        assertEquals("PP2_PUBLISHED_CROSS_RULE_CONDITION_UNSUPPORTED", Pp2PublishedAssessment.validate(selection(c), true));
    }

    @Test void missingStrengthListIsNotProofOfRetirement() throws Exception {
        var c = retired(); c.remove("strengthDescriptor");
        assertEquals("PP2_PUBLISHED_CROSS_RULE_CONDITION_UNSUPPORTED", Pp2PublishedAssessment.validate(selection(c), true));
    }

    @Test void skippedReferenceDoesNotBypassOwnUnsupportedConditions() throws Exception {
        var source = selection(retired());
        ((ObjectNode) source.at("/specification/original/ld/CriteriaCode/0/entContent/strengthDescriptor/0"))
                .put("text", "TEST_ONLY unimplemented enrichment condition");
        assertEquals("PP2_PUBLISHED_ACTIVE_CLAUSE_UNSUPPORTED", Pp2PublishedAssessment.validate(source, true));
    }

    @Test void fixedOriginalRetiredClauseHasTheRequiredTwoLevelState() throws Exception {
        String file = System.getProperty("t05.gn100.raw");
        org.junit.jupiter.api.Assumptions.assumeTrue(file != null, "Explicit pinned original path is required");
        if (file == null) return;
        byte[] raw = Files.readAllBytes(Path.of(file));
        assertEquals("4dc9861b487b1ff09e348aa80979b67bd410184ded2471597c0ae30fbee3937d", CodingTranscriptMaterialSupply.sha(raw));
        var original = mapper.readTree(raw).at("/data/4");
        assertEquals("GN100", original.path("entId").asText());
        var c = original.at("/ld/CriteriaCode/20/entContent");
        assertEquals("PM1", c.path("label").asText()); assertTrue(c.path("additionalComments").asText().contains("PP2"));
        assertTrue(Pp2PublishedAssessment.entirelyInactive(c));
        // This is only a source-state check: the source's separate PP2 method is not adopted here.
    }
}
