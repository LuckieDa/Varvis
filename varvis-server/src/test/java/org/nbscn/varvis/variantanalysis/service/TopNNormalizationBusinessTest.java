package org.nbscn.varvis.variantanalysis.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.nbscn.varvis.variantanalysis.domain.*;
import org.nbscn.varvis.variantanalysis.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.nbscn.varvis.variantanalysis.engine.ParsedCandidateFixture.*;

/** Actual publication/recovery, JDBC, BA1 wiring; synthetic ranking source and population transport. */
class TopNNormalizationBusinessTest {
    @TempDir Path temporary;
    @Test void queryNewKeyIsolatedFailureNoReplacementAndPersistentReadOnlyHistory() throws Exception {
        Assumptions.assumeTrue(System.getProperty("varvis.n01.bcftools") != null);
        var f = new Ba1BusinessIntegrationTest(); f.temporary = temporary; f.setup();
        try {
            var base = f.base;
            var n = new TopNSelectedNormalizationTest(); n.temporary = temporary;
            base.producer = n.fixture("ATCG").producer();
            base.expectedPublishedCount = 3;
            base.variantTsvOverride = tsv("1\t11\t2\tTC\tTA\tHBB\t3043\tAR\t0.8",
                    "2\t11\t3\tA\tAA\tHBB\t3043\tAR\t0.7", "3\t13\t3\tC\tT\tGJB2\t2706\tAR\t0.6");
            base.publish(false, GenomeAssembly.GRCh38, vcf("chr11\t2\t.\tTC\tTA\t.\tPASS\t.\tGT\t0|1",
                    "chr11\t3\t.\tA\tAA\t.\tPASS\t.\tGT\t0/1", "chr13\t3\t.\tC\tT\t.\tPASS\t.\tGT\t0/1"));
            var before = base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100));
            var delegate = (VariantEvidenceProvider) java.util.Objects.requireNonNull(org.springframework.test.util.ReflectionTestUtils.getField(java.util.Objects.requireNonNull(f.ba1), "provider"));
            // Replay a raw record at the *new* allele so reparsing also has to use that identity.
            Path script = temporary.resolve("synthetic-tabix.cmd");
            Files.writeString(script, Files.readString(script).replace("chr11\t2\t.\tT\tA", "chr11\t3\t.\tC\tA"));
            var keys = new ArrayList<NormalizedVariantKey>(); var calls = new AtomicInteger();
            VariantEvidenceProvider transport = new VariantEvidenceProvider() {
                public String source() { return delegate.source(); } public String version() { return delegate.version(); }
                public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(List<VariantInterpretation> variants, AnalysisContext context) {
                    calls.incrementAndGet(); keys.add(variants.get(0).getObservation().getKey()); return delegate.annotate(variants, context);
                }
            };
            f.ba1 = new Ba1AlleleRuntime(base.mapper, GnomadPopulationInputProfile.loadExecutableV2(), transport); f.wire();
            base.request.put("topN", 2);
            var result = f.post();
            n.archive("business-attempt", result);
            assertEquals(2, result.path("candidates").size()); assertEquals(1, calls.get(), result.toString());
            assertEquals(NormalizedVariantKey.canonical(GenomeAssembly.GRCh38, "11", 3, "C", "A"), keys.get(0));
            assertEquals("HIT", result.at("/candidates/0/ba1/evidence/queryOutcome/status").asText(), result.toString());
            assertEquals("REF_VALIDATION_NOT_MATCH", result.at("/candidates/1/ba1/blockers/0/reasonCode").asText());
            assertEquals(1, result.at("/ba1Calls/provider").asInt()); assertEquals(1, result.at("/ba1Calls/evaluator").asInt());
            assertEquals("COMPLETED_WITH_BLOCKERS", result.path("status").asText());
            assertEquals(before, base.mapper.valueToTree(base.jobs.findInterpretations(base.analysisId, 0, 100)));
            assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
            assertEquals(result, f.post()); assertEquals(1, calls.get());
            base.closeDatabase(); base.recreateRepositories(); f.wire();
            assertEquals(result, base.service.read(base.analysisId, result.path("runId").asText()));
            assertEquals(result, f.post()); assertEquals(1, calls.get());
            n.archive("business-result", result); n.archive("provider-request-keys", keys);
            n.archive("business-private", base.mapper.readTree(base.store.run(base.analysisId, result.path("runId").asText()).orElseThrow().privateJson()));
            base.request.put("topN", 3);
            var expanded = f.post(); assertEquals(2, calls.get(), expanded.toString());
            assertTrue(expanded.at("/candidates/0/ba1ReusedFromRunId").isTextual());
            var privateResult = base.mapper.readTree(base.store.run(base.analysisId, expanded.path("runId").asText()).orElseThrow().privateJson());
            var ref = base.mapper.treeToValue(expanded.at("/candidates/0/ba1/normalizationSource"), ExplicitContextSourceLedger.SourceRef.class);
            int resolved = 0;
            com.fasterxml.jackson.databind.JsonNode normalizationProof = null;
            for (var source : privateResult.at("/ba1Sources/0/receipts")) {
                var ledger = ExplicitContextSourceLedger.verifiedBytes(base.mapper, source.path("rawJson").asText().getBytes(java.nio.charset.StandardCharsets.UTF_8), source.path("sha256").asText());
                try { normalizationProof = ledger.resolve(ref); resolved++; } catch (IllegalArgumentException ignored) { }
            }
            assertEquals(1, resolved, privateResult.toString());
            n.archive("expanded-result", expanded); n.archive("expanded-private", privateResult);
            var candidate = base.jobs.findInterpretations(base.analysisId, 0, 1).get(0);
            var oldKey = candidate.getObservation().getKey();
            var incompatible = base.mapper.createObjectNode();
            var variant = incompatible.putObject("context").putObject("variant");
            variant.set("normalization", expanded.at("/candidates/0/ba1/normalization"));
            variant.putObject("evidenceQueryAllele").put("assembly", oldKey.getGenomeAssembly()).put("contig", oldKey.getChromosome())
                    .put("position", oldKey.getPosition()).put("reference", oldKey.getReference()).put("alternate", oldKey.getAlternate());
            var current = expanded.at("/candidates/0/ba1");
            var replay = f.ba1.evaluateNormalized(current.path("alleleId").asText(), candidate,
                    new AnalysisContext(base.analysisId, GenomeAssembly.GRCh38, AnalysisMode.WES), incompatible,
                    current.path("executionSourceSha256").asText(), base.mapper.createArrayNode(), current.path("evidenceQueryAllele"),
                    current.path("normalization"), normalizationProof);
            assertEquals(1, replay.path("providerCalls").asInt()); assertEquals(3, calls.get());
            assertEquals(keys.get(0), keys.get(2)); n.archive("incompatible-shared-observation-requery", replay);
            // Same variants, different reference identity: cached facts must not survive the change.
            base.producer = n.fixture("ATCGA").producer(); f.wire();
            base.request.put("topN", 1);
            var changedReference = f.post(); assertEquals(4, calls.get(), changedReference.toString());
            assertFalse(changedReference.at("/candidates/0").has("ba1ReusedFromRunId"));
            assertNotEquals(result.at("/candidates/0/ba1/alleleId"), changedReference.at("/candidates/0/ba1/alleleId"));
            n.archive("changed-reference-result", changedReference);
        } finally { f.close(); }
    }
}
