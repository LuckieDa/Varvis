package org.nbscn.varvis.variantanalysis.provider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossRefSeqSequenceVerifierTest {
    @TempDir Path root;
    @Test @EnabledIfSystemProperty(named = "pvs1.nativeExpertRoot", matches = ".+")
    void capturedNcbiFastaKeepsItsOriginalBytesAndExactAccession() throws Exception {
        var helper = new Pvs1StartLossAutomaticPreparationTest();
        var file = Path.of(System.getProperty("pvs1.nativeExpertRoot"),
                "local/development/handoffs/acmg-six-framework-tasks-20261101/a1-closure-20261102/ncbi-mecp2-rna.fasta");
        byte[] raw = Files.readAllBytes(file);
        assertEquals("46d914d44f8e2f471b0af5e323f7e0ebce76d78cecccd80f796c0ce6009d2d7a", CodingTranscriptMaterialSupply.sha(raw));
        var source = new Pvs1StartLossPublicSources(helper.mapper, true,
                (u, t, max) -> new ProteinRegionHttpTransport.Response(200, Map.of(), raw));
        var snapshot = source.refseqSequences(List.of("NM_001110792.2"));
        String rna = snapshot.document().at("/sequences/NM_001110792.2").asText();
        assertEquals(10343, rna.length()); assertEquals("ATG", rna.substring(52, 55));
        assertEquals(new String(raw, StandardCharsets.UTF_8), source.originals().path(snapshot.receipt().path("sha256").asText()).path("rawText").asText());
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = Files.createDirectory(root.resolve("rna-history"));
        persistence.saveAndRead(helper.mapper.writeValueAsString(snapshot.receipt()), helper.mapper.writeValueAsString(source.originals()));
    }
    @ParameterizedTest @ValueSource(ints = {1, -1})
    void fullExactSequenceInBothOrientationsCanExcludeOnlyTheSameStart(int strand) throws Exception {
        var helper = new Pvs1StartLossAutomaticPreparationTest(); helper.root = root;
        var f = helper.base.fixture(strand); var model = f.data().model;
        var inventory = helper.mapper.createObjectNode().put("coverage", "NCBI_DATASETS_PRODUCT_REPORT").put("sourceCoverageComplete", true);
        var row = model.deepCopy().put("structureStatus", "AVAILABLE").put("sequenceVerified", false);
        String sequence = model.path("cdsSequence").asText();
        row.put("transcriptLength", sequence.length()).put("transcriptCdsStart", 1).put("transcriptCdsEnd", sequence.length());
        var exons = row.putArray("transcriptExons");
        for (var e : model.path("exons")) exons.addObject().put("genomicStart", e.path("genomicStart").asInt()).put("genomicEnd", e.path("genomicEnd").asInt());
        inventory.putArray("transcripts").add(row);
        var sources = new Pvs1StartLossPublicSources(helper.mapper, true, (url, timeout, max) -> {
            assertEquals("eutils.ncbi.nlm.nih.gov", url.getHost());
            return new ProteinRegionHttpTransport.Response(200, Map.of(), (">" + model.path("transcript").asText() + " description\n" + sequence + "\n").getBytes(StandardCharsets.UTF_8));
        });
        var verified = Pvs1StartLossRefSeqSequenceVerifier.verify(f.data().context, inventory, sources, helper.technicalSupply(f));
        assertTrue(verified.at("/transcripts/0/sequenceVerified").asBoolean(), verified.toPrettyString());
        assertEquals(1, verified.path("sequenceVerifiedCount").asInt());
        var review = Pvs1StartLossTranscriptQualifier.structural(helper.mapper, f.data().context, model, verified, "a".repeat(64));
        assertTrue(review.path("structuralReviewComplete").asBoolean());
        var combined = Pvs1StartLossTranscriptQualifier.combineStructural(review, review);
        assertTrue(combined.path("complete").asBoolean());
        assertEquals("NO_FUNCTIONAL_ALTERNATIVE_IN_ASSESSED_SET", combined.path("status").asText());
        assertFalse(inventory.at("/transcripts/0/sequenceVerified").asBoolean());
        assertTrue(sources.originals().iterator().next().has("rawText"));
        var assertions = helper.mapper.createObjectNode(); assertions.putArray("entries").addObject().put("status", "UNRESOLVED");
        assertFalse(Pvs1StartLossTranscriptQualifier.withExpertAssertions(combined, assertions).path("complete").asBoolean());
    }

    @ParameterizedTest @ValueSource(strings = {"wrongVersion", "wrongSequence", "duplicate", "nonFasta", "networkOff", "missingReferenceSha", "staleVerifiedFlag"})
    void sequenceSourceFailuresDoNotBecomeVerified(String change) throws Exception {
        var helper = new Pvs1StartLossAutomaticPreparationTest(); helper.root = root;
        var f = helper.base.fixture(1); var row = f.data().model.deepCopy().put("structureStatus", "AVAILABLE");
        String sequence = row.path("cdsSequence").asText(), tx = row.path("transcript").asText();
        row.put("transcriptLength", sequence.length()).put("transcriptCdsStart", 1).put("transcriptCdsEnd", sequence.length());
        row.set("transcriptExons", row.path("exons").deepCopy());
        var inventory = helper.mapper.createObjectNode(); inventory.putArray("transcripts").add(row);
        if (change.equals("missingReferenceSha")) f.data().context.withObject("variant").withObject("normalization").remove("referenceSequenceSha256");
        if (change.equals("staleVerifiedFlag")) row.put("structureStatus", "UNRESOLVED").put("sequenceVerified", true);
        var source = new Pvs1StartLossPublicSources(helper.mapper, !change.equals("networkOff"), (u, t, max) -> {
            String body = ">" + (change.equals("wrongVersion") ? "NM_999999.9" : tx) + "\n"
                    + (change.equals("wrongSequence") ? "G" + sequence.substring(1) : sequence) + "\n";
            if (change.equals("duplicate")) body += body;
            if (change.equals("nonFasta")) body = "<html>temporarily unavailable</html>";
            return new ProteinRegionHttpTransport.Response(200, Map.of(), body.getBytes(StandardCharsets.UTF_8));
        });
        var verified = Pvs1StartLossRefSeqSequenceVerifier.verify(f.data().context, inventory, source, helper.technicalSupply(f));
        assertFalse(verified.at("/transcripts/0/sequenceVerified").asBoolean(), verified.toPrettyString());
        if (!change.equals("staleVerifiedFlag")) assertFalse(verified.at("/transcripts/0/sequenceReasonCode").asText().isBlank());
        assertEquals(0, verified.path("sequenceVerifiedCount").asInt());
    }
}
