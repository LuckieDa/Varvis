package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.service.ExplicitContextSourceLedger;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixed public archive replay, never patient input, new network execution or source reconstruction. */
@EnabledIfSystemProperty(named = "varvis.bs1.publicArchive", matches = ".+")
class Bs1PublicObservationTest {
    @TempDir Path temporary;
    @Test @EnabledIfSystemProperty(named = "varvis.bs1.convertedArchive", matches = ".+")
    void existingPublicConversionReceiptsRetainOriginalCoordinatesInSuccessorIdentity() throws Exception {
        var mapper = new ObjectMapper(); var adapter = new RuntimeEvidenceV2Adapter(mapper);
        Path root = Path.of(System.getProperty("varvis.bs1.convertedArchive"));
        byte[] response = Files.readAllBytes(root.resolve("new-run-get.json")), proofBytes = Files.readAllBytes(root.resolve("pm2-production-proof.json"));
        assertEquals("9a17657096894f411b9f27146e07240919ec772b56c9debbeda03144fe2b39a6", hash(response));
        assertEquals("8bf8950852f32715d588826ed6f64370c913e11493b9d6695ec527503c174534", hash(proofBytes));
        var saved = mapper.readTree(response); var proof = mapper.readTree(proofBytes);
        assertEquals("834cf634-70fe-492e-a13a-1f8030554fc0", saved.path("runId").asText());
        var ledgers = new HashMap<String, ExplicitContextSourceLedger>(); int ordinal = 0;
        for (var receipt : proof.path("pm2ConvertedContextReceipts")) {
            Path file = temporary.resolve("receipt-" + ++ordinal + ".json"); Files.writeString(file, receipt.path("rawJson").asText());
            ledgers.put(receipt.path("source").asText(), ExplicitContextSourceLedger.load(mapper, file, receipt.path("sha256").asText()));
        }
        assertEquals(5, ledgers.size());
        var row = proof.at("/pm2ConvertedContextProduction/inputs/0");
        var ref = mapper.treeToValue(row.at("/normalization/source"), ExplicitContextSourceLedger.SourceRef.class);
        var ledger = Objects.requireNonNull(ledgers.get(ref.source())); var normalization = ledger.resolve(ref);
        assertEquals(row.path("candidateId"), normalization.path("candidateId")); assertEquals(row.path("hypothesisId"), normalization.path("hypothesisId"));
        assertEquals(row.path("conversionEvidence"), normalization.path("conversionEvidence"));
        for (String field : List.of("reference", "originalReference")) {
            var pointer = mapper.treeToValue(normalization.path(field), ExplicitContextSourceLedger.SourceRef.class);
            var reference = Objects.requireNonNull(ledgers.get(pointer.source())).resolve(pointer);
            assertEquals(reference.path("expectedSha256"), reference.path("observedSha256"));
            assertEquals(reference.path("observedSha256"), row.path("conversionEvidence").path(field.equals("reference") ? "targetReferenceSha256" : "originalReferenceSha256"));
        }
        var context = (ObjectNode) saved.at("/candidates/0/attempts/0/context").deepCopy(); adapter.validateCompleteContext(context);
        assertEquals(normalization.path("originalAllele"), context.at("/variant/inputAllele"));
        assertEquals(normalization.path("normalizedAllele"), context.at("/variant/evidenceQueryAllele"));
        var oldVariant = context.path("variant").deepCopy(); context.put("schemaVersion", "evidence-context-v3"); context.remove("transcript");
        context.put("contextId", adapter.canonicalVersionedContextId(context)); adapter.validateVersionedContext(context);
        assertEquals(oldVariant, context.path("variant"));
        assertEquals("BS1_POLICY_NO_RECORD", AcmgSpecificationSelector.selectBs1(mapper, Bs1Runtime.policies(mapper), context).path("reasonCode").asText());
        // This is receipt/identity replay: no new FASTA reads, coordinate conversion or scientific query.
    }
    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
    @Test void verifyOriginalAtomicIdentityAndReevaluateExactlyThreeArchivedPublicAlleles() throws Exception {
        var mapper = new ObjectMapper(); var adapter = new RuntimeEvidenceV2Adapter(mapper);
        Path input = Path.of(System.getProperty("varvis.bs1.publicArchive"));
        byte[] raw = Files.readAllBytes(input);
        String sha = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));
        assertEquals("34838534ce4aba9071b47ce1e10f31e528aefa1167bb92cb63d847e3fd48aa10", sha);
        var archived = mapper.readTree(raw);
        assertEquals("e0e02f69-c69d-4f13-ab3e-bca6b603e9b3", archived.path("runId").asText());
        String source = archived.path("executionSourceSha256").asText();
        assertEquals("77bddcbcd55360b85ffb1d01b913b3c3b30f81fd5d0ba46c388d228b15583f90", source);
        var historicalPolicy = (ObjectNode) Bs1Runtime.policies(mapper).get(0).deepCopy();
        historicalPolicy.withObject("/qualityContract/releaseSemantics").remove("jointFilterDefinition");
        historicalPolicy.withObject("/qualityContract/releaseSemantics").put("missingSubtype", "REQUIRED").put("subtypeDot", "UNKNOWN_REQUIRES_NATIVE");
        var runtime = new Bs1Runtime(mapper, List.of(historicalPolicy), mock(VariantEvidenceProvider.class), null);
        var report = mapper.createObjectNode().put("schemaVersion", "bs1-public-replay-v1")
                .put("archiveSha256", sha).put("archiveRunId", archived.path("runId").asText())
                .put("executionSourceSha256", source).put("newNetworkCalls", 0).put("nativeCalls", 0).put("allSitesCalls", 0)
                .put("coverage", "PINNED_ARCHIVED_RAW_AND_ATOMIC_IDENTITY_REPLAY_NOT_NEW_SOURCE_RECOVERY")
                .put("formalEnabled", false);
        var rows = report.putArray("results"); var positions = new HashSet<Integer>();
        for (var row : archived.path("candidates")) for (var attempt : row.path("attempts")) {
            var old = attempt.path("pm2"); int pos = old.at("/context/variant/evidenceQueryAllele/position").asInt();
            if (!Set.of(120988847, 120993678, 120989017).contains(pos)) continue;
            assertTrue(positions.add(pos)); assertEquals("SYNTHETIC", old.at("/context/dataClass").asText());
            var observation = PopulationObservation.restore(mapper, old, source, "SYNTHETIC");
            var context = (ObjectNode) old.path("context").deepCopy(); context.put("schemaVersion", "evidence-context-v3"); context.remove("transcript");
            context.put("contextId", adapter.canonicalVersionedContextId(context));
            var result = runtime.replay(context, observation, source);
            var summary = rows.addObject(); summary.set("queryAllele", observation.path("queryAllele"));
            summary.put("origin", observation.path("origin").asText()).put("observedAt", observation.path("observedAt").asText());
            summary.put("observationId", observation.path("observationId").asText()).put("rawRecordSha256", observation.path("rawRecordSha256").asText());
            summary.set("evaluation", result.path("evaluation")); summary.set("qualityDecision", result.path("qualityDecision"));
            assertEquals("NOT_EVALUATED", result.at("/evaluation/evaluationResult/status").asText());
            assertEquals(pos == 120989017 ? "BS1_NO_RECORD" : "BS1_REQUIRED_FILTER_INFO_MISSING",
                    result.at("/evaluation/evaluationResult/reasonCode").asText(), result.toString());
            assertEquals(old.at("/evidence/provenance/producedAt"), observation.path("observedAt"));
        }
        assertEquals(3, positions.size()); assertEquals(0, runtime.counts().path("provider").asInt());
        String output = System.getProperty("varvis.bs1.publicOutput");
        if (output != null) Files.writeString(Path.of(output), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertEquals(sha, HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));
    }
}
