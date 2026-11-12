package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import static org.junit.jupiter.api.Assertions.*;

/** Toy K2R, publisher-shaped synthetic rows, and arithmetic boundaries only. */
class RevelPredictionObservationTest {
    final PredictionClauseRuntimeTest base = new PredictionClauseRuntimeTest();

    @ParameterizedTest @CsvSource({"0.7,GE,0.7,MET", "0.7,GT,0.7,NOT_MET", "0.700001,GT,0.7,MET",
            "0.4,LE,0.4,MET", "0.5,LT,0.5,NOT_MET", "0.499999,LT,0.5,MET", "0.55,GE,0.7,NOT_MET"})
    void exactReviewedComparatorsUseRevelAndNativeCdsProof(String score, String op, String threshold, String expected) throws Exception {
        Input input = input(score, op, threshold);
        ObjectNode result = base.run(input.fixture(), input.policy(), null);
        base.assertStatus(expected, result);
        assertEquals("REVEL", result.path("preselectedModel").asText());
        assertEquals("K2R", result.at("/resolvedInput/proteinVariantKey").asText());
        assertEquals("UNVERSIONED_SOURCE_NO_VERSION_EQUIVALENCE_CLAIM", result.at("/resolvedInput/sourceTranscriptVersionStatus").asText());
        assertEquals("GRCh38", result.at("/context/variant/evidenceQueryAllele/assembly").asText());
        assertEquals("GRCh37", result.at("/resolvedInput/sourceOriginalAssembly").asText());
    }

    @ParameterizedTest @CsvSource({"1,.,100,A,G,K,R,0.99,ENST000001", "1,90,101,A,G,K,R,0.99,ENST000001",
            "1,90,100,A,T,K,R,0.99,ENST000001", "1,90,100,A,G,K,Q,0.99,ENST000001",
            "1,90,100,A,G,K,R,.,ENST000001", "1,90,100,A,G,K,R,0.99,ENST000002"})
    void rejectsInvalidPublisherMappingAlleleProteinScoreAndTranscript(String contig, String old, String pos,
            String ref, String alt, String aaRef, String aaAlt, String score, String transcript) throws Exception {
        Input input = input("0.99", "GE", "0.7");
        ((ObjectNode) input.fixture().observation().at("/rawRecords/0")).put("rawCsv", String.join(",", contig, old, pos, ref, alt, aaRef, aaAlt, score, transcript));
        rebindEvidence(input.fixture());
        base.assertRefused(base.run(input.fixture(), input.policy(), null));
    }

    @Test void missingAndGrayRevelNeverUseAvailableAlternativeScore() throws Exception {
        Input input = input("0.55", "GE", "0.7");
        input.fixture().observation().putObject("unusedAlternativeModel").put("ALPHAMISSENSE", "0.99");
        base.assertStatus("NOT_MET", base.run(input.fixture(), input.policy(), null));
        ((ObjectNode) input.fixture().observation().at("/rawRecords/0")).put("rawCsv", "1,90,100,A,G,K,R,.,ENST000001");
        rebindEvidence(input.fixture());
        ObjectNode result = base.run(input.fixture(), input.policy(), null);
        base.assertRefused(result); assertEquals("REVEL", result.path("preselectedModel").asText());
    }

    @Test void duplicateConflictingRowsAndResourceOrModelTamperingAreNotScores() throws Exception {
        Input input = input("0.99", "GE", "0.7");
        ((ArrayNode) input.fixture().observation().path("rawRecords")).addObject().put("recordId", "synthetic-row-2")
                .put("rawCsv", "1,90,100,A,G,K,R,0.01,ENST000001");
        rebindEvidence(input.fixture());
        base.assertRefused(base.run(input.fixture(), input.policy(), null));
        input = input("0.99", "GE", "0.7");
        ((ObjectNode) input.fixture().observation().path("transcriptModel")).put("modelVersion", "2");
        base.assertRefused(base.run(input.fixture(), input.policy(), null));
        input = input("0.99", "GE", "0.7");
        ((ObjectNode) input.policy().path("resourceBinding")).put("sourceArtifactSha256", "b".repeat(64));
        base.assertRefused(base.run(input.fixture(), input.policy(), null));
    }

    Input input(String score, String op, String threshold) throws Exception {
        var f = base.alpha("0.99");
        ObjectNode tx = base.mapper.createObjectNode().put("geneSymbol", "SYNTH1").put("transcriptAccession", "ENST000001")
                .put("transcriptId", "ENST000001").put("hgvsCoding", "ENST000001.4:c.5A>G")
                .put("hgvsProtein", "NP_000001.1:p.Lys2Arg").put("consequence", "missense_variant").put("exon", "1/1")
                .put("proteinPosition", "2").put("aminoAcids", "K/R").put("codons", "AAA/AGA")
                .put("source", "VEP_SYNTHETIC").put("status", "COMPLETED").put("version", "116.0").put("timestamp", "2026-09-01T00:00:00Z");
        f.candidate().applyTranscriptAnnotations(List.of(base.mapper.treeToValue(tx, TranscriptConsequence.class)));
        ObjectNode model = f.observation().putObject("transcriptModel").put("schemaVersion", "native-coding-transcript-model-v1")
                .put("modelId", "synthetic-revel-model").put("modelVersion", "1").put("hgncId", "HGNC:1")
                .put("geneSymbol", "SYNTH1").put("assembly", "GRCh38").put("contig", "1").put("strand", 1)
                .put("transcript", "ENST000001.4").put("protein", "NP_000001.1").put("cdsSequence", "ATGAAATAA")
                .put("cdsLength", 9).put("proteinLength", 2);
        model.putArray("exons").addObject().put("ordinal", 1).put("genomicStart", 96).put("genomicEnd", 104).put("cdsStart", 1).put("cdsEnd", 9);
        model.putArray("sourceRecords").addObject().put("sourceId", "synthetic-source").put("version", "1")
                .put("url", "https://example.invalid/synthetic").put("sha256", "a".repeat(64));
        f.observation().putArray("rawRecords").addObject().put("recordId", "synthetic-row-1")
                .put("rawCsv", "1,90,100,A,G,K,R," + score + ",ENST000001");
        rebindEvidence(f);
        ObjectNode policy = base.policy(f, op.startsWith("G") ? "PP3" : "BP4", "REVEL", op, threshold);
        ((ObjectNode) policy.path("resourceBinding")).put("sourceArtifactSha256", "a".repeat(64));
        policy.putObject("revelTranscriptBinding").put("accession", "ENST000001")
                .put("status", "REVIEWED_ACCESSION_BRIDGE_WITH_ALLELE_SPECIFIC_SEQUENCE_CHECK").putArray("sourceIds").add("synthetic-source");
        policy.putObject("transcriptModelBinding").put("modelId", model.path("modelId").asText())
                .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", base.adapter.canonicalSha256(model));
        return new Input(f, policy);
    }

    void rebindEvidence(PredictionClauseRuntimeTest.Fixture f) {
        ObjectNode carrier = base.mapper.createObjectNode().put("evidenceType", "COMPUTATIONAL_PREDICTION")
                .put("producedAt", "2026-09-13T14:00:00Z").put("sourcePayloadSha256", base.adapter.canonicalSha256(f.observation().path("rawRecords")));
        carrier.set("queryAllele", f.context().at("/variant/evidenceQueryAllele").deepCopy());
        ObjectNode source = carrier.putObject("source").put("sourceId", "REVEL_SYNTHETIC_RAW")
                .put("resourceVersion", "1.3-synthetic").put("resourceSha256", "c".repeat(64)).put("retrievedAt", "2026-09-13T14:00:00Z");
        ObjectNode locator = base.mapper.createObjectNode().put("locatorType", "TABLE").put("locatorValue", "synthetic-revel-row")
                .put("artifactSha256", "a".repeat(64)).put("retrievedAt", "2026-09-13T14:00:00Z");
        source.putArray("sourceLocators").add(locator);
        ArrayNode records = source.putArray("sourceRecords");
        for (JsonNode row : f.observation().path("rawRecords")) records.addObject().put("recordId", row.path("recordId").asText()).set("locator", locator.deepCopy());
        carrier.putObject("resourceExecution").put("status", "SUCCEEDED").put("attemptedAt", "2026-09-13T14:00:00Z")
                .put("completedAt", "2026-09-13T14:00:00Z");
        carrier.putObject("resourceAvailability").put("status", "AVAILABLE");
        carrier.putObject("queryOutcome").put("status", "HIT").put("recordCount", records.size())
                .put("queryFingerprint", base.adapter.canonicalSha256(f.context().at("/variant/evidenceQueryAllele")));
        carrier.putObject("resourceApplicability").put("status", "APPLICABLE");
        String rawScore = f.observation().at("/rawRecords/0/rawCsv").asText().split(",", -1)[7];
        String normalized = rawScore.matches("[01](?:\\.[0-9]+)?") ? new BigDecimal(rawScore).stripTrailingZeros().toPlainString() : rawScore;
        carrier.putArray("measurements").addObject().put("metricId", "revel.score").put("rawValue", rawScore)
                .put("normalizedValue", normalized).put("unit", "dimensionless").put("calibrationProfileId", RevelPredictionObservation.INPUT_PROFILE);
        f.observation().set("evidence", base.adapter.adapt(f.context(), carrier));
    }
    record Input(PredictionClauseRuntimeTest.Fixture fixture, ObjectNode policy) { }
}
