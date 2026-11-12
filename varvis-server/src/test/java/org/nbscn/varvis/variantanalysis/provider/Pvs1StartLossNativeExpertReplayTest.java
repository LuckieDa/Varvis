package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit developer replay against archived public originals, never a patient run. */
@EnabledIfSystemProperty(named = "pvs1.nativeExpertRoot", matches = ".+")
class Pvs1StartLossNativeExpertReplayTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;

    @Test void nativeExclusionIsAcquiredBeforeDecisionAndSkipsUpstreamRequests() throws Exception {
        // Native expert original; deliberately synthetic sequence/allele geometry.
        // This verifies production acquisition/decision wiring, not a real patient allele.
        var helper = new Pvs1StartLossRuntimeTest(); var f = helper.fixture(1); var d = f.data();
        d.model.put("hgncId", "HGNC:6990").put("geneSymbol", "MECP2").put("transcript", "NM_004992.3");
        helper.helper.candidate(d, helper.helper.genomic(d.model, 1), "A", "G",
                helper.helper.annotation(d, "start_lost", "1A>G", "Met1Val", "1/2", "1", "M/V", "ATG/GTG"));
        d.context.withObject("transcript").put("version", 3);
        d.context.withObject("disease").put("mondoId", "MONDO:0010726"); d.context.put("moi", "X_LINKED_DOMINANT");
        d.context.put("contextId", helper.adapter.canonicalContextId(d.context));
        Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        var guard = PredictionSpecializationGuard.load(helper.mapper,
                root.resolve("local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json"),
                "b2d2e43216ea15758490a1eb97d55eccd8ded98494e60230102754091fde32e0",
                root.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture"));
        var technical = new Pvs1StartLossAutomaticPreparationTest(); technical.root = temporary;
        var supply = technical.technicalSupply(f);
        var sources = new Pvs1StartLossPublicSources(helper.mapper, true, (url, timeout, max) -> {
            throw new AssertionError("An explicit exclusion must not search upstream references");
        });
        var preparation = new Pvs1StartLossAutomaticPreparation(helper.mapper, () -> supply, sources, () -> guard);
        var prepared = preparation.prepareForEvaluation(d.context, d.candidate);
        var decision = prepared.decision(); assertNotNull(decision, prepared.preparation().toPrettyString());
        assertEquals("NOT_APPLICABLE", decision.at("/evaluation/criterionApplicability/status").asText());
        assertEquals("NONE", decision.at("/evaluation/appliedStrength").asText());
        assertFalse(preparation.originals().isEmpty());
        decision.removeAll(); var preserved = prepared.decision(); assertNotNull(preserved);
        assertFalse(preserved.isEmpty(), "returned JSON cannot mutate verified facts");
        preserved.set("startLossAutomaticPreparation", prepared.preparation());
        var archive = helper.mapper.createObjectNode(); archive.set("result", preserved);
        archive.set("startLossSourceDocuments", preparation.originals());
        var persistence = new Pvs1StartLossPersistenceTest();
        persistence.temporary = java.nio.file.Files.createDirectory(temporary.resolve("history"));
        persistence.saveAndRead(helper.mapper.writeValueAsString(Pvs1SelectedBatch.publicSummary(helper.mapper, preserved)),
                helper.mapper.writeValueAsString(archive));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) {
            var row = helper.mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PVS1")
                    .put("status", "NOT_APPLICABLE").put("appliedStrength", "NONE")
                    .put("reasonCode", "PVS1_START_LOSS_PUBLISHED_ALTERNATIVE_START_EXCLUSION");
            var result = prepared.decision(); assertNotNull(result);
            result.set("startLossAutomaticPreparation", prepared.preparation());
            row.putObject("details").set("pvs1Facts", Pvs1SelectedBatch.publicSummary(helper.mapper, result));
            java.nio.file.Files.createDirectories(Path.of(output));
            java.nio.file.Files.writeString(Path.of(output).resolve("NATIVE_EXPERT_SYNTHETIC_ALLELE.json"), helper.mapper.writeValueAsString(row));
        }
    }
    @Test void releasedOriginalsYieldScopedAlternativeAndAbsenceWithoutHandAuthoredFacts() throws Exception {
        var mapper = new ObjectMapper(); Path root = Path.of(System.getProperty("pvs1.nativeExpertRoot"));
        var guard = PredictionSpecializationGuard.load(mapper,
                root.resolve("local/resources/acmg/ba1/cspec-20260914-a01/snapshot.json"),
                "b2d2e43216ea15758490a1eb97d55eccd8ded98494e60230102754091fde32e0",
                root.resolve("local/development/handoffs/A01_BA1_SHARED_REVIEW_20260915/active/capture"));
        for (String[] input : new String[][]{
                {"HGNC:6990", "MECP2", "MONDO:0010726", "X_LINKED", "NM_004992", "3", "FOUND_FUNCTIONAL_ALTERNATIVE"},
                {"HGNC:9588", "PTEN", "MONDO:0017623", "AUTOSOMAL_DOMINANT", "NM_000314", "8", "NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET"}}) {
            var context = mapper.createObjectNode().put("moi", input[3]);
            context.putObject("gene").put("hgncId", input[0]).put("symbol", input[1]);
            context.putObject("disease").put("mondoId", input[2]);
            context.putObject("transcript").put("accession", input[4]).put("version", input[5]);
            var assertions = guard.startLossAssertions(mapper, context);
            var review = Pvs1StartLossTranscriptQualifier.withExpertAssertions(mapper.createObjectNode(), assertions);
            assertEquals(input[6], review.path("status").asText(), assertions.toPrettyString());
            assertFalse(assertions.path("sourceDocuments").isEmpty());
            for (var document : assertions.path("sourceDocuments").properties())
                assertEquals(document.getKey(), CodingTranscriptMaterialSupply.sha(document.getValue().path("rawJson").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
