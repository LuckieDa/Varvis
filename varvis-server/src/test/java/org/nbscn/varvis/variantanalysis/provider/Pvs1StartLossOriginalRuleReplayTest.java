package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossOriginalRuleReplayTest {
    final ObjectMapper mapper = new ObjectMapper();
    @org.junit.jupiter.api.io.TempDir Path temporary;
    record Fixture(ObjectNode summary, ObjectNode original, PredictionSpecializationGuard.ReferenceSpecification specification) { }
    Fixture nativeFixture() throws Exception {
        String root = System.getProperty("pvs1.nativeExpertRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(root != null, "Native source root not configured");
        Path base = Path.of(root), folder = base.resolve("local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        byte[] raw = Files.readAllBytes(folder.resolve("erepo-pten-independent-lead.json"));
        assertEquals("e2593bf5cd3460f23bfc62d8f692aa00b35af05f152b02a31505498de35b3d3e", CodingTranscriptMaterialSupply.sha(raw));
        var original = (ObjectNode) mapper.readTree(raw);
        byte[] summaryRaw = Files.readAllBytes(folder.resolve("erepo-pten-page1.json"));
        assertEquals("46946044dc7911114d14a0d476a9c31d7d55ab063b8cff44b84c801341f27fe1", CodingTranscriptMaterialSupply.sha(summaryRaw));
        ObjectNode summary = null;
        for (var row : mapper.readTree(summaryRaw).path("data"))
            if (row.path("uuid").equals(original.path("uuid"))) summary = (ObjectNode) row;
        assertNotNull(summary);
        var context = mapper.createObjectNode().put("moi", "AUTOSOMAL_DOMINANT");
        context.putObject("gene").put("hgncId", "HGNC:9588");
        context.putObject("disease").put("mondoId", "MONDO:0017623");
        context.putObject("transcript").put("accession", "NM_000314").put("version", 8);
        var guard = PredictionSpecializationGuard.load(mapper, base.resolve("local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json"),
                "b2d2e43216ea15758490a1eb97d55eccd8ded98494e60230102754091fde32e0",
                base.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture"));
        var spec = guard.referenceSpecification(context, original.path("assertionMethod"));
        assertNotNull(spec);
        assertNull(guard.referenceSpecification(context.deepCopy().put("moi", "AUTOSOMAL_RECESSIVE"), original.path("assertionMethod")));
        return new Fixture(summary, original, spec);
    }
    @Test void nativeSummaryAndOriginalGuidelineResolveActualStrengthAndClassification() throws Exception {
        var f = nativeFixture();
        var out = Pvs1StartLossOriginalRuleReplay.replay(mapper, f.summary(), f.original(), f.specification());
        assertEquals("ORIGINAL_CLASSIFICATION_REPLAYED", out.path("status").asText(), out.toPrettyString());
        assertEquals("Pathogenic", out.at("/combination/classification").asText());
        assertEquals("Pathogenic Very Strong", out.at("/actualAppliedCriteria/0/sourceAppliedStrength").asText());
        assertFalse(out.path("independenceEstablished").asBoolean());
        var assessment = Pvs1StartLossAutomaticPreparation.assessment(mapper, f.summary(), f.original());
        Pvs1StartLossOriginalRuleReplay.attach(assessment, out);
        assertTrue(assessment.at("/sourceEvidenceAudit/combinationReplayed").asBoolean());
        assertFalse(assessment.at("/sourceEvidenceAudit/unresolvedReasons").toString().contains("ACTUAL_APPLIED_STRENGTH_UNRESOLVED"));
        assertFalse(assessment.path("independenceEstablished").asBoolean());
    }
    @Test void mismatchedVersionAndAlteredActualCodeAreNotRepairedFromDefaults() throws Exception {
        var f = nativeFixture();
        f.original().withObject("assertionMethod").put("version", "3.1.0");
        assertEquals("ORIGINAL_GUIDELINE_BINDING_MISMATCH", Pvs1StartLossOriginalRuleReplay.replay(mapper, f.summary(), f.original(), f.specification()).path("reasonCode").asText());
        f.original().withObject("assertionMethod").put("version", "3.2.0");
        f.summary().withArray("metCodes").remove(0);
        assertEquals("SOURCE_APPLIED_CODES_CONFLICT", Pvs1StartLossOriginalRuleReplay.replay(mapper, f.summary(), f.original(), f.specification()).path("reasonCode").asText());
    }
    @Test void explicitLineStrengthDisagreementCannotBeHiddenBySummary() throws Exception {
        var f = nativeFixture();
        ((ObjectNode) f.original().at("/evidenceLine/0")).putObject("evidenceStrength").put("label", "Pathogenic Supporting");
        var out = Pvs1StartLossOriginalRuleReplay.replay(mapper, f.summary(), f.original(), f.specification());
        assertEquals("SOURCE_APPLIED_STRENGTH_CONFLICT", out.path("reasonCode").asText());
        assertFalse(out.has("actualAppliedCriteria"));
    }
    @Test void nativeRuleGroundsAndAllOriginalsSurviveAnotherJvmHistoryAndExport() throws Exception {
        var f = nativeFixture();
        var assessment = Pvs1StartLossAutomaticPreparation.assessment(mapper, f.summary(), f.original());
        Pvs1StartLossOriginalRuleReplay.attach(assessment, Pvs1StartLossOriginalRuleReplay.replay(mapper, f.summary(), f.original(), f.specification()));
        assessment.set("curatedGrounds", Pvs1StartLossCuratedGrounds.assess(mapper, f.summary(), f.original(), f.specification()));
        var preparation = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-automatic-preparation-v1")
                .put("methodVersion", "1.2.0").put("runtimeEligible", false).put("scientificEvaluationPerformed", false).put("status", "INCOMPLETE");
        preparation.putArray("issues").add("PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
        var upstream = preparation.putObject("upstreamReview").put("reasonCode", "PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
        upstream.putArray("records").addObject().put("recordId", f.summary().path("uuid").asText())
                .put("reasonCode", "CLASSIFICATION_INDEPENDENCE_REQUIRES_SCIENTIFIC_FACTS").set("nativeAssessment", assessment);
        var archive = mapper.createObjectNode(); archive.set("preparation", preparation);
        var originals = archive.putObject("originals");
        Path folder = Path.of(System.getProperty("pvs1.nativeExpertRoot"), "local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102");
        for (String file : java.util.List.of("erepo-pten-page1.json", "erepo-pten-independent-lead.json")) {
            byte[] raw = Files.readAllBytes(folder.resolve(file));
            originals.putObject(CodingTranscriptMaterialSupply.sha(raw)).put("rawJson", new String(raw, java.nio.charset.StandardCharsets.UTF_8));
        }
        originals.putObject(f.specification().source().path("rawSha256").asText()).put("rawJson", f.specification().raw());
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(mapper.writeValueAsString(preparation), mapper.writeValueAsString(archive));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            Files.createDirectories(Path.of(output));
            Files.writeString(Path.of(output).resolve("AUTO_NATIVE_ORIGINAL_REPLAY.json"), mapper.writeValueAsString(preparation));
        }
    }
}
