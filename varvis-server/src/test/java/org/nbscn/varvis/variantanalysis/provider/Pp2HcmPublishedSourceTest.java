package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Existing normative source and explicit malformed-source controls, not variant adoption. */
class Pp2HcmPublishedSourceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    ObjectNode selection() throws Exception {
        String file = System.getProperty("t05.hcm.knowledge");
        org.junit.jupiter.api.Assumptions.assumeTrue(file != null, "Explicit pinned HCM compiler output required");
        if (file == null) throw new IllegalStateException("HCM_KNOWLEDGE_PATH_REQUIRED");
        var document = mapper.readTree(Files.readAllBytes(Path.of(file)));
        if (document == null || !document.isObject()) throw new IllegalStateException("HCM_KNOWLEDGE_OBJECT_REQUIRED");
        assertEquals(1, document.path("records").size());
        assertEquals("GN100@1.0", document.at("/records/0/specificationKey").asText());
        var out = mapper.createObjectNode().put("status", "READY").put("declaredTranscript", "NM_001018005.2")
                .put("executionTranscript", "NM_001018005.2");
        out.set("record", document.at("/records/0").deepCopy());
        out.set("specification", document.at("/specifications/0").deepCopy()); return out;
    }
    void refresh(ObjectNode selection) {
        var original = selection.at("/specification/original");
        ((ObjectNode) selection.path("specification")).put("entitySha256", adapter.canonicalSha256(original));
        ((ObjectNode) selection.path("record")).put("clauseSha256", adapter.canonicalSha256(original.at(selection.at("/record/clausePointer").asText())));
    }

    @Test void actualPythonCompiledMethodReplaysAllNativeConditionsWithoutVariantClearance() throws Exception {
        var source = selection(); var before = source.deepCopy();
        var method = Pp2HcmPublishedSource.inspect(mapper, source);
        assertTrue(method.recognised(), method.snapshot().toPrettyString());
        assertNull(Pp2PublishedAssessment.validate(source, true));
        assertEquals("PP2_HCM_GERMLINE_SCOPE_REQUIRED", Pp2PublishedAssessment.validate(source, false));
        var interpretation = method.snapshot().path("sourceInterpretation");
        assertEquals("TPM1", interpretation.at("/sourceScope/geneSymbol").asText());
        assertEquals("HGNC:12010", interpretation.at("/sourceScope/hgncId").asText());
        assertEquals("MONDO:0005045", interpretation.at("/sourceScope/mondoId").asText());
        assertEquals("NM_001018005.2", interpretation.at("/sourceScope/preferredTranscript").asText());
        assertEquals("ENST00000403994", interpretation.at("/sourceScope/unversionedSourceEnsembl").asText());
        assertEquals("SUPPORTING", interpretation.path("appliedStrength").asText());
        assertTrue(interpretation.path("requiresQualifiedVariantOtherPhenotypeReview").asBoolean());
        assertFalse(method.snapshot().path("scoringPermissionGranted").asBoolean(true));
        assertFalse(method.snapshot().path("otherPhenotypeReviewGranted").asBoolean(true));
        for (String field : List.of("threshold", "AF", "requiresPm2", "otherPhenotypeClear", "review")) assertFalse(interpretation.has(field));
        assertEquals(before, source);
        var snapshot = method.snapshot(); snapshot.put("status", "CHANGED"); assertTrue(method.recognised());
    }

    @Test void inactivePm1DiscussionDoesNotBecomeActiveDependencyOrTnni3Scope() throws Exception {
        var source = selection();
        assertTrue(Pp2PublishedAssessment.entirelyInactive(source.at("/specification/original/ld/CriteriaCode/20/entContent")));
        assertTrue(source.at("/specification/original/ld/CriteriaCode/6/entContent/additionalComments").asText().contains("TNNI3"));
        assertTrue(Pp2HcmPublishedSource.inspect(mapper, source).recognised());
        ((ObjectNode) source.at("/record/scope")).put("hgncId", "HGNC:11947");
        assertFalse(Pp2HcmPublishedSource.inspect(mapper, source).recognised());
    }

    @Test void changedConditionsAttachmentsScopeOrExtraPp2AreNotSilentlyIgnored() throws Exception {
        for (String change : List.of("TEXT", "NOTES", "FILE", "GENE", "TX", "CROSS", "DUPLICATE", "STRONG")) {
            var source = selection(); var original = (ObjectNode) source.at("/specification/original");
            var clause = (ObjectNode) original.at("/ld/CriteriaCode/6/entContent");
            switch (change) {
                case "TEXT" -> ((ObjectNode) clause.at("/strengthDescriptor/4")).put("text", "Only apply if PM2 is met.");
                case "NOTES" -> ((ObjectNode) original.path("entContent")).put("notes", "Unknown additional condition.");
                case "FILE" -> ((ObjectNode) original.path("ld")).putArray("File").addObject().put("fileName", "restriction.pdf");
                case "GENE" -> clause.putArray("gene").add("OTHER");
                case "TX" -> ((ObjectNode) original.at("/ld/RuleSet/0/entContent/genes/0")).put("preferredTranscript", "NM_001018005.3");
                case "CROSS" -> ((ObjectNode) original.at("/ld/CriteriaCode/7/entContent")).put("additionalComments", "pp2 requires another condition.");
                case "DUPLICATE" -> ((ObjectNode) original.path("ld")).withArray("CriteriaCode").add(original.at("/ld/CriteriaCode/6").deepCopy());
                case "STRONG" -> ((ObjectNode) clause.at("/strengthDescriptor/4")).put("strength", "Strong");
                default -> fail(change);
            }
            refresh(source); assertFalse(Pp2HcmPublishedSource.inspect(mapper, source).recognised(), change);
        }
    }

    @Test void exactInterpretationAndNativeSourcePinsAreRequired() throws Exception {
        for (String change : List.of("RAW", "ENTITY", "CLAUSE", "INTERPRETATION")) {
            var source = selection();
            switch (change) {
                case "RAW" -> ((ObjectNode) source.at("/specification/source")).put("rawSha256", "bad");
                case "ENTITY" -> ((ObjectNode) source.path("specification")).put("entitySha256", "0".repeat(64));
                case "CLAUSE" -> ((ObjectNode) source.path("record")).put("clauseSha256", "0".repeat(64));
                case "INTERPRETATION" -> ((ObjectNode) source.at("/record/interpretation")).put("otherPhenotypeClear", true);
                default -> fail(change);
            }
            assertFalse(Pp2HcmPublishedSource.inspect(mapper, source).recognised(), change);
        }
    }

    @Test void policyBindingTracksRealScopeAndExecutionTranscriptWithoutGrantingReview() throws Exception {
        var source = selection(); var binding = Pp2HcmPublishedSource.policyBinding(mapper, source);
        assertEquals(source.at("/record/scope"), binding.path("scope"));
        assertEquals("NM_001018005.2", binding.path("declaredTranscript").asText());
        source.put("executionTranscript", "ENST00000403994.9");
        var bridged = Pp2HcmPublishedSource.policyBinding(mapper, source);
        assertNotEquals(adapter.canonicalSha256(binding), adapter.canonicalSha256(bridged));
        source.put("executionTranscript", "");
        assertThrows(IllegalArgumentException.class, () -> Pp2HcmPublishedSource.policyBinding(mapper, source));
    }
}
