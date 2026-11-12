package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.nbscn.varvis.variantanalysis.domain.AnalysisContext;
import org.nbscn.varvis.variantanalysis.domain.AnalysisMode;
import org.nbscn.varvis.variantanalysis.domain.EvidenceItem;
import org.nbscn.varvis.variantanalysis.domain.GenomeAssembly;
import org.nbscn.varvis.variantanalysis.domain.NormalizedVariantKey;
import org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence;
import org.nbscn.varvis.variantanalysis.domain.VariantInterpretation;
import org.nbscn.varvis.variantanalysis.domain.VariantQcObservation;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeEvidenceV2ProviderWiringTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void bindsManifestIdentityAndAdaptsProviderHit() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 12138, "C", "A");
        ObjectNode manifest = manifest("GNOMAD_EXOMES_V4_1_GRCH38_COMPACT", "GNOMAD",
                "GRCh38", "gnomAD exomes v4.1 GRCh38", null);
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest, "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT");

        ObjectNode evidence = wiring.annotateVariantProvider(
                syntheticContext(key),
                fixedProvider("GNOMAD", binding.getResourceVersion(), hit("GNOMAD", binding.getResourceVersion())),
                variant(key),
                analysisContext(key),
                binding
        ).get(0);

        assertEquals("GNOMAD_EXOMES_V4_1_GRCH38_COMPACT", evidence.path("source").path("sourceId").asText());
        assertEquals(binding.getIdentityFingerprintSha256(),
                evidence.path("source").path("resourceSha256").asText());
        assertEquals("SUCCEEDED", evidence.path("resourceExecution").path("status").asText());
        assertEquals("HIT", evidence.path("queryOutcome").path("status").asText());
        assertEquals("APPLICABLE", evidence.path("resourceApplicability").path("status").asText());
        assertEquals("UNDETERMINED", evidence.path("direction").asText());
        assertFalse(evidence.path("source").path("sourceLocators").get(0).has("artifactSha256"));
    }

    @Test
    void batchEntryInvokesProviderOnceAndAdaptsEachVariantThroughTheExistingCarrier() throws Exception {
        NormalizedVariantKey firstKey = key("GRCh38", 12138, "C", "A");
        NormalizedVariantKey secondKey = key("GRCh38", 12139, "G", "T");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("GNOMAD_EXOMES_V4_1_GRCH38_COMPACT", "GNOMAD", "GRCh38",
                        "gnomAD exomes v4.1 GRCh38", null),
                "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT");
        AtomicInteger invocationCount = new AtomicInteger();
        AtomicInteger queriedVariantCount = new AtomicInteger();
        VariantEvidenceProvider provider = new VariantEvidenceProvider() {
            @Override
            public String source() {
                return "GNOMAD";
            }

            @Override
            public String version() {
                return binding.getResourceVersion();
            }

            @Override
            public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
                    List<VariantInterpretation> variants,
                    AnalysisContext context) {
                invocationCount.incrementAndGet();
                queriedVariantCount.set(variants.size());
                Map<NormalizedVariantKey, List<EvidenceItem>> result =
                        new LinkedHashMap<NormalizedVariantKey, List<EvidenceItem>>();
                for (VariantInterpretation variant : variants) {
                    result.put(
                            variant.getObservation().getKey(),
                            Collections.singletonList(hit("GNOMAD", binding.getResourceVersion()))
                    );
                }
                return result;
            }
        };
        Map<NormalizedVariantKey, JsonNode> contexts =
                new LinkedHashMap<NormalizedVariantKey, JsonNode>();
        contexts.put(firstKey, syntheticContext(firstKey));
        contexts.put(secondKey, syntheticContext(secondKey));

        Map<NormalizedVariantKey, List<ObjectNode>> result = wiring.annotateVariantProviderBatch(
                contexts,
                provider,
                List.of(variant(firstKey), variant(secondKey)),
                analysisContext(firstKey),
                binding
        );

        assertEquals(1, invocationCount.get());
        assertEquals(2, queriedVariantCount.get());
        assertEquals(2, result.size());
        assertEquals("HIT", result.get(firstKey).get(0).path("queryOutcome").path("status").asText());
        assertEquals("HIT", result.get(secondKey).get(0).path("queryOutcome").path("status").asText());
    }

    @Test
    void allSitesBatchCallsOneRunnerAndPreservesTwoAllelesAtOneDeduplicatedLocus()
            throws Exception {
        GnomadPopulationInputProfile profile =
                GnomadPopulationInputProfile.loadExecutablePm2Supporting();
        NormalizedVariantKey firstKey = key("GRCh38", 12591, "C", "A");
        NormalizedVariantKey secondKey = key("GRCh38", 12591, "C", "G");
        Map<NormalizedVariantKey, JsonNode> contexts = new LinkedHashMap<>();
        contexts.put(firstKey, syntheticContext(firstKey));
        contexts.put(secondKey, syntheticContext(secondKey));
        GnomadAllSitesHailBatchRunner runner = mock(GnomadAllSitesHailBatchRunner.class);
        GnomadAllSitesHailBatchRunner.BatchResult batch =
                mock(GnomadAllSitesHailBatchRunner.BatchResult.class);
        AtomicReference<ObjectNode> captured = new AtomicReference<>();
        when(runner.run(any(ObjectNode.class))).thenAnswer(invocation -> {
            captured.set(((ObjectNode) invocation.getArgument(0)).deepCopy());
            return batch;
        });
        when(batch.getStatus()).thenReturn("SUCCEEDED");
        when(batch.getResult()).thenAnswer(ignored -> allSitesBatchResult(captured.get(), profile));

        Map<NormalizedVariantKey, List<ObjectNode>> result = wiring(clock)
                .annotateGnomadAllSitesBatch(contexts,
                        List.of(variant(firstKey), variant(secondKey)), runner, profile);

        verify(runner, times(1)).run(any(ObjectNode.class));
        assertEquals(2, captured.get().path("loci").size());
        assertEquals(captured.get().path("loci").get(0).path("canonicalContig"),
                captured.get().path("loci").get(1).path("canonicalContig"));
        assertEquals(captured.get().path("loci").get(0).path("position"),
                captured.get().path("loci").get(1).path("position"));
        assertEquals(2, result.size());
        for (List<ObjectNode> values : result.values()) {
            assertEquals(2, values.size());
            assertEquals("HIT", values.get(0).path("queryOutcome").path("status").asText());
            assertEquals("HIT", values.get(1).path("queryOutcome").path("status").asText());
            assertEquals("GENOMIC_REGION", values.get(0).path("source")
                    .path("sourceLocators").get(0).path("locatorType").asText());
        }
    }

    @Test
    void allSitesInvalidStrataOrFlagFailsTheWholeBatch() throws Exception {
        GnomadPopulationInputProfile profile =
                GnomadPopulationInputProfile.loadExecutablePm2Supporting();
        NormalizedVariantKey key = key("GRCh38", 12591, "C", "A");
        for (String mismatch : List.of("SELECTOR", "INDEX", "FLAG_TYPE")) {
            GnomadAllSitesHailBatchRunner runner = mock(GnomadAllSitesHailBatchRunner.class);
            GnomadAllSitesHailBatchRunner.BatchResult batch =
                    mock(GnomadAllSitesHailBatchRunner.BatchResult.class);
            AtomicReference<ObjectNode> captured = new AtomicReference<>();
            when(runner.run(any(ObjectNode.class))).thenAnswer(invocation -> {
                captured.set(((ObjectNode) invocation.getArgument(0)).deepCopy());
                return batch;
            });
            when(batch.getStatus()).thenReturn("SUCCEEDED");
            when(batch.getResult()).thenAnswer(ignored -> {
                ObjectNode result = allSitesBatchResult(captured.get(), profile);
                ObjectNode exomes = (ObjectNode) result.path("resources").get(0);
                if ("SELECTOR".equals(mismatch)) {
                    ((ObjectNode) exomes.path("strata").path("selector")).put("group", "raw");
                } else if ("INDEX".equals(mismatch)) {
                    ((ObjectNode) exomes.path("loci").get(0).path("measurement"))
                            .put("adjStrataIndex", 1);
                } else {
                    ((ObjectNode) exomes.path("loci").get(0).path("measurement")
                            .path("regionFlags")).put("outside_broad_capture_region", "unknown");
                }
                return result;
            });

            List<ObjectNode> values = wiring(clock).annotateGnomadAllSitesBatch(
                    Map.of(key, syntheticContext(key)), List.of(variant(key)), runner, profile)
                    .get(key);

            verify(runner, times(1)).run(any(ObjectNode.class));
            for (ObjectNode evidence : values) {
                assertEquals("FAILED", evidence.path("resourceExecution").path("status").asText());
                assertEquals("NOT_QUERIED", evidence.path("queryOutcome").path("status").asText());
                assertTrue(evidence.path("measurements").isEmpty());
            }
        }
    }

    @Test
    void allSitesEvidenceIdentityIgnoresOtherBatchLociAndExecutionTimes() throws Exception {
        GnomadPopulationInputProfile profile =
                GnomadPopulationInputProfile.loadExecutablePm2Supporting();
        NormalizedVariantKey key = key("GRCh38", 12591, "C", "A");
        NormalizedVariantKey otherAllele = key("GRCh38", 12591, "C", "G");

        AtomicReference<ObjectNode> firstRequest = new AtomicReference<>();
        GnomadAllSitesHailBatchRunner firstRunner = mock(GnomadAllSitesHailBatchRunner.class);
        GnomadAllSitesHailBatchRunner.BatchResult firstBatch =
                mock(GnomadAllSitesHailBatchRunner.BatchResult.class);
        when(firstRunner.run(any(ObjectNode.class))).thenAnswer(invocation -> {
            firstRequest.set(((ObjectNode) invocation.getArgument(0)).deepCopy());
            return firstBatch;
        });
        when(firstBatch.getStatus()).thenReturn("SUCCEEDED");
        when(firstBatch.getResult()).thenAnswer(ignored -> {
            ObjectNode result = allSitesBatchResult(firstRequest.get(), profile);
            ((ObjectNode) result.path("execution"))
                    .put("startedAt", "2026-09-01T00:00:00Z")
                    .put("completedAt", "2026-09-01T00:00:01Z");
            return result;
        });
        List<ObjectNode> first = wiring(clock).annotateGnomadAllSitesBatch(
                Map.of(key, syntheticContext(key)), List.of(variant(key)), firstRunner, profile)
                .get(key);

        AtomicReference<ObjectNode> secondRequest = new AtomicReference<>();
        GnomadAllSitesHailBatchRunner secondRunner = mock(GnomadAllSitesHailBatchRunner.class);
        GnomadAllSitesHailBatchRunner.BatchResult secondBatch =
                mock(GnomadAllSitesHailBatchRunner.BatchResult.class);
        when(secondRunner.run(any(ObjectNode.class))).thenAnswer(invocation -> {
            secondRequest.set(((ObjectNode) invocation.getArgument(0)).deepCopy());
            return secondBatch;
        });
        when(secondBatch.getStatus()).thenReturn("SUCCEEDED");
        when(secondBatch.getResult()).thenAnswer(ignored -> {
            ObjectNode result = allSitesBatchResult(secondRequest.get(), profile);
            ((ObjectNode) result.path("execution"))
                    .put("startedAt", "2026-09-02T00:00:00Z")
                    .put("completedAt", "2026-09-02T00:00:10Z");
            return result;
        });
        Map<NormalizedVariantKey, JsonNode> contexts = new LinkedHashMap<>();
        contexts.put(key, syntheticContext(key));
        contexts.put(otherAllele, syntheticContext(otherAllele));
        List<ObjectNode> second = wiring(clock).annotateGnomadAllSitesBatch(
                contexts, List.of(variant(key), variant(otherAllele)), secondRunner, profile)
                .get(key);

        assertEquals(first.size(), second.size());
        for (int index = 0; index < first.size(); index++) {
            assertEquals(first.get(index).path("provenance").path("sourcePayloadSha256"),
                    second.get(index).path("provenance").path("sourcePayloadSha256"));
            assertEquals(first.get(index).path("evidenceId"), second.get(index).path("evidenceId"));
        }
    }

    @Test
    void allSitesRunnerTimeoutRemainsTimeoutAndNeverBecomesNoRecord() throws Exception {
        GnomadPopulationInputProfile profile =
                GnomadPopulationInputProfile.loadExecutablePm2Supporting();
        NormalizedVariantKey key = key("GRCh38", 12591, "C", "A");
        GnomadAllSitesHailBatchRunner runner = mock(GnomadAllSitesHailBatchRunner.class);
        GnomadAllSitesHailBatchRunner.BatchResult batch =
                mock(GnomadAllSitesHailBatchRunner.BatchResult.class);
        when(runner.run(any(ObjectNode.class))).thenReturn(batch);
        when(batch.getStatus()).thenReturn("TIMEOUT");
        when(batch.getReasonCode()).thenReturn("HAIL_BATCH_TIMEOUT");

        List<ObjectNode> result = wiring(clock).annotateGnomadAllSitesBatch(
                Map.of(key, syntheticContext(key)), List.of(variant(key)), runner, profile).get(key);

        verify(runner, times(1)).run(any(ObjectNode.class));
        assertEquals(2, result.size());
        for (ObjectNode evidence : result) {
            assertEquals("TIMEOUT", evidence.path("resourceExecution").path("status").asText());
            assertEquals("NOT_QUERIED", evidence.path("queryOutcome").path("status").asText());
            assertTrue(evidence.path("measurements").isEmpty());
        }
    }

    @Test
    void noRecordRemainsSuccessfulAndEmpty() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 12138, "C", "G");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("GNOMAD_EXOMES_V4_1_GRCH38_COMPACT", "GNOMAD", "GRCh38",
                        "gnomAD exomes v4.1 GRCh38", null),
                "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT");

        ObjectNode evidence = wiring.annotateVariantProvider(
                syntheticContext(key), fixedProvider("GNOMAD", binding.getResourceVersion(), noRecord(
                        "GNOMAD", binding.getResourceVersion())), variant(key), analysisContext(key), binding
        ).get(0);

        assertEquals("SUCCEEDED", evidence.path("resourceExecution").path("status").asText());
        assertEquals("NO_RECORD", evidence.path("queryOutcome").path("status").asText());
        assertEquals(0, evidence.path("source").path("sourceRecords").size());
        assertEquals(0, evidence.path("measurements").size());
    }

    @Test
    void runtimeExceptionAndTimeoutAreIsolatedWithoutBecomingNoRecord() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 12138, "C", "A");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("GNOMAD_EXOMES_V4_1_GRCH38_COMPACT", "GNOMAD", "GRCh38",
                        "gnomAD exomes v4.1 GRCh38", null),
                "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT");

        ObjectNode failed = wiring.annotateVariantProvider(
                syntheticContext(key), throwingProvider(new IllegalStateException("local path must not escape")),
                variant(key), analysisContext(key), binding).get(0);
        ObjectNode timedOut = wiring.annotateVariantProvider(
                syntheticContext(key), throwingProvider(new CompletionException(new TimeoutException("deadline"))),
                variant(key), analysisContext(key), binding).get(0);

        assertEquals("FAILED", failed.path("resourceExecution").path("status").asText());
        assertEquals("PROVIDER_RUNTIME_EXCEPTION",
                failed.path("resourceExecution").path("reasonCode").asText());
        assertEquals("NOT_QUERIED", failed.path("queryOutcome").path("status").asText());
        assertEquals("TIMEOUT", timedOut.path("resourceExecution").path("status").asText());
        assertEquals("NOT_QUERIED", timedOut.path("queryOutcome").path("status").asText());
        assertFalse(failed.toString().contains("local path"));
    }

    @Test
    void retrievalTimesDoNotChangeStableEvidenceIdentity() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 12138, "C", "A");
        ObjectNode manifest = manifest("GNOMAD_EXOMES_V4_1_GRCH38_COMPACT", "GNOMAD",
                "GRCh38", "gnomAD exomes v4.1 GRCh38", null);
        RuntimeEvidenceV2ProviderWiring firstWiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring secondWiring = wiring(Clock.fixed(
                Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC));
        RuntimeEvidenceV2ProviderWiring.ResourceBinding firstBinding = firstWiring.bind(
                manifest, "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT");
        RuntimeEvidenceV2ProviderWiring.ResourceBinding secondBinding = secondWiring.bind(
                manifest, "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT");
        VariantEvidenceProvider provider = fixedProvider(
                "GNOMAD", firstBinding.getResourceVersion(), hit("GNOMAD", firstBinding.getResourceVersion()));

        ObjectNode first = firstWiring.annotateVariantProvider(syntheticContext(key), provider,
                variant(key), analysisContext(key), firstBinding).get(0);
        ObjectNode second = secondWiring.annotateVariantProvider(syntheticContext(key), provider,
                variant(key), analysisContext(key), secondBinding).get(0);

        assertEquals(first.path("evidenceId").asText(), second.path("evidenceId").asText());
        assertEquals(first.path("deduplicationKey").asText(), second.path("deduplicationKey").asText());
        assertFalse(first.path("provenance").path("producedAt").asText().equals(
                second.path("provenance").path("producedAt").asText()));
    }

    @Test
    void vepUsesManifestToolVersionAndExistingAdapter() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 1000000, "G", "A");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("VEP_116_GRCH38", "VEP", "GRCh38",
                        "VEP 116.0 / cache 116_GRCh38", "116.0"),
                "VEP_116_GRCH38");
        TranscriptConsequence consequence = new TranscriptConsequence(
                "SYNTH1", "ENSG000001", "ENST000001", "ENST000001.1:c.1G>A",
                "ENSP000001:p.A1T", "missense_variant"
        ).withAnnotationMetadata("VEP_CACHED", "COMPLETED", "116.0", clock.instant());
        TranscriptAnnotationProvider provider = new TranscriptAnnotationProvider() {
            @Override public String source() { return "VEP_CACHED"; }
            @Override public String version() { return "116.0"; }
            @Override public List<TranscriptConsequence> annotate(
                    VariantInterpretation ignored, AnalysisContext context) {
                return Collections.singletonList(consequence);
            }
        };

        ObjectNode evidence = wiring.annotateTranscriptProvider(
                syntheticContext(key, "ENST000001"), provider,
                variant(key), analysisContext(key), binding).get(0);

        assertEquals("VEP_116_GRCH38", evidence.path("source").path("sourceId").asText());
        assertEquals("VARIANT_CONSEQUENCE", evidence.path("evidenceType").asText());
        assertEquals("HIT", evidence.path("queryOutcome").path("status").asText());
        assertEquals("VEP:GRCh38:1:1000000:G:A:ENST000001.1",
                evidence.path("source").path("sourceRecords").get(0).path("recordId").asText());
    }

    @Test
    void vepCarriesExplicitBp7SynonymousPositionThroughTheExistingAdapter() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 1000000, "G", "A");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("VEP_116_GRCH38", "VEP", "GRCh38",
                        "VEP 116.0 / cache 116_GRCh38", "116.0"),
                "VEP_116_GRCH38");
        TranscriptConsequence consequence = new TranscriptConsequence(
                "SYNTH1", "ENSG000001", "ENST000001", "ENST000001.1:c.123C>T",
                "ENSP000001:p.A41=", "synonymous_variant"
        ).withBp7SynonymousPosition(false, 3)
                .withAnnotationMetadata("VEP_CACHED", "COMPLETED", "116.0", clock.instant());

        ObjectNode evidence = wiring.annotateTranscriptProvider(
                syntheticContext(key, "ENST000001"), fixedTranscriptProvider(consequence),
                variant(key), analysisContext(key), binding).get(0);

        assertEquals("ENST000001.1", measurement(evidence, "vep.selectedTranscriptId")
                .path("rawValue").asText());
        assertEquals("false", measurement(evidence, "vep.synonymousAtExonFirstBase")
                .path("rawValue").asText());
        assertEquals("3", measurement(evidence, "vep.synonymousDistanceFromExonEnd")
                .path("rawValue").asText());
    }

    @Test
    void vepCarriesExonAndBindsItToTheSourcePayloadHash() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 1000000, "G", "A");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("VEP_116_GRCH38", "VEP", "GRCh38",
                        "VEP 116.0 / cache 116_GRCh38", "116.0"),
                "VEP_116_GRCH38");

        ObjectNode first = wiring.annotateTranscriptProvider(
                syntheticContext(key, "ENST000001"),
                fixedTranscriptProvider(vepConsequenceWithExon("2/10")),
                variant(key), analysisContext(key), binding).get(0);
        ObjectNode second = wiring.annotateTranscriptProvider(
                syntheticContext(key, "ENST000001"),
                fixedTranscriptProvider(vepConsequenceWithExon("9/10")),
                variant(key), analysisContext(key), binding).get(0);

        assertEquals("2/10", measurement(first, "vep.exon").path("rawValue").asText());
        assertEquals("2/10", measurement(first, "vep.exon")
                .path("normalizedValue").asText());
        assertNotEquals(first.path("provenance").path("sourcePayloadSha256").asText(),
                second.path("provenance").path("sourcePayloadSha256").asText());
        assertNotEquals(first.path("evidenceId").asText(), second.path("evidenceId").asText());
        assertEquals(first.path("deduplicationKey").asText(),
                second.path("deduplicationKey").asText());
    }

    @Test
    void vepDerivesSimpleSignedIntronicOffsetsFromHgvsc() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 1000000, "G", "A");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("VEP_116_GRCH38", "VEP", "GRCh38",
                        "VEP 116.0 / cache 116_GRCh38", "116.0"),
                "VEP_116_GRCH38");
        Map<String, String> expectedOffsets = new LinkedHashMap<>();
        expectedOffsets.put("ENST000001.1:c.123+7A>G", "7");
        expectedOffsets.put("ENST000001.1:c.124-21C>T", "-21");

        for (Map.Entry<String, String> fixture : expectedOffsets.entrySet()) {
            TranscriptConsequence consequence = new TranscriptConsequence(
                    "SYNTH1", "ENSG000001", "ENST000001", fixture.getKey(),
                    null, "intron_variant"
            ).withAnnotationMetadata("VEP_CACHED", "COMPLETED", "116.0", clock.instant());

            ObjectNode evidence = wiring.annotateTranscriptProvider(
                    syntheticContext(key, "ENST000001"), fixedTranscriptProvider(consequence),
                    variant(key), analysisContext(key), binding).get(0);

            assertEquals(fixture.getValue(), measurement(evidence, "vep.intronicSignedOffset")
                    .path("rawValue").asText());
        }
    }

    @Test
    void vepOmitsSynonymousPositionMeasurementsWhenNoExplicitBoundaryWasDerived()
            throws Exception {
        NormalizedVariantKey key = key("GRCh38", 1000000, "G", "A");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("VEP_116_GRCH38", "VEP", "GRCh38",
                        "VEP 116.0 / cache 116_GRCh38", "116.0"),
                "VEP_116_GRCH38");
        TranscriptConsequence consequence = new TranscriptConsequence(
                "SYNTH1", "ENSG000001", "ENST000001", "ENST000001.1:c.123C>T",
                "ENSP000001:p.A41=", "synonymous_variant"
        ).withAnnotationMetadata("VEP_CACHED", "COMPLETED", "116.0", clock.instant());

        ObjectNode evidence = wiring.annotateTranscriptProvider(
                syntheticContext(key, "ENST000001"), fixedTranscriptProvider(consequence),
                variant(key), analysisContext(key), binding).get(0);

        assertTrue(measurement(evidence, "vep.synonymousAtExonFirstBase").isMissingNode());
        assertTrue(measurement(evidence, "vep.synonymousDistanceFromExonEnd").isMissingNode());
    }

    @Test
    void vepDoesNotBindAnotherTranscriptToTheRequestedContext() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 1000000, "G", "A");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("VEP_116_GRCH38", "VEP", "GRCh38",
                        "VEP 116.0 / cache 116_GRCh38", "116.0"),
                "VEP_116_GRCH38");
        TranscriptConsequence otherTranscript = new TranscriptConsequence(
                "SYNTH1", "ENSG000001", "ENST000002", "ENST000002:c.1G>A",
                "ENSP000002:p.A1T", "missense_variant"
        ).withAnnotationMetadata("VEP_CACHED", "COMPLETED", "116.0", clock.instant());
        TranscriptAnnotationProvider provider = new TranscriptAnnotationProvider() {
            @Override public String source() { return "VEP_CACHED"; }
            @Override public String version() { return "116.0"; }
            @Override public List<TranscriptConsequence> annotate(
                    VariantInterpretation ignored, AnalysisContext context) {
                return Collections.singletonList(otherTranscript);
            }
        };

        ObjectNode evidence = wiring.annotateTranscriptProvider(
                syntheticContext(key, "ENST000001"), provider,
                variant(key), analysisContext(key), binding).get(0);

        assertEquals("SUCCEEDED", evidence.path("resourceExecution").path("status").asText());
        assertEquals("NO_RECORD", evidence.path("queryOutcome").path("status").asText());
        assertEquals(0, evidence.path("source").path("sourceRecords").size());
    }

    @Test
    void vepRequiresExactTranscriptVersionAndGene() throws Exception {
        NormalizedVariantKey key = key("GRCh38", 1000000, "G", "A");
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                manifest("VEP_116_GRCH38", "VEP", "GRCh38",
                        "VEP 116.0 / cache 116_GRCh38", "116.0"),
                "VEP_116_GRCH38");
        TranscriptConsequence wrongVersion = new TranscriptConsequence(
                "SYNTH1", "ENSG000001", "ENST000001", "ENST000001.2:c.1G>A",
                "ENSP000001:p.A1T", "missense_variant"
        ).withAnnotationMetadata("VEP_CACHED", "COMPLETED", "116.0", clock.instant());
        TranscriptConsequence wrongGene = new TranscriptConsequence(
                "OTHER", "ENSG000002", "ENST000001", "ENST000001.1:c.1G>A",
                "ENSP000001:p.A1T", "missense_variant"
        ).withAnnotationMetadata("VEP_CACHED", "COMPLETED", "116.0", clock.instant());

        ObjectNode noRecord = wiring.annotateTranscriptProvider(
                syntheticContext(key, "ENST000001"), fixedTranscriptProvider(wrongVersion),
                variant(key), analysisContext(key), binding).get(0);
        ObjectNode invalid = wiring.annotateTranscriptProvider(
                syntheticContext(key, "ENST000001"), fixedTranscriptProvider(wrongGene),
                variant(key), analysisContext(key), binding).get(0);

        assertEquals("NO_RECORD", noRecord.path("queryOutcome").path("status").asText());
        assertEquals("FAILED", invalid.path("resourceExecution").path("status").asText());
        assertEquals("PROVIDER_PAYLOAD_INVALID",
                invalid.path("resourceExecution").path("reasonCode").asText());
        assertEquals("AVAILABLE", invalid.path("resourceAvailability").path("status").asText());
        assertEquals("NOT_QUERIED", invalid.path("queryOutcome").path("status").asText());
    }

    @Test
    void rejectsManifestFingerprintAndAssemblyDrift() throws Exception {
        RuntimeEvidenceV2ProviderWiring wiring = wiring(clock);
        ObjectNode invalidManifest = manifest("GNOMAD_EXOMES_V4_1_GRCH38_COMPACT", "GNOMAD",
                "GRCh38", "gnomAD exomes v4.1 GRCh38", null);
        ((ObjectNode) invalidManifest.withArray("resources").get(0).path("identity"))
                .put("release", "drifted");
        assertThrows(IllegalArgumentException.class, () -> wiring.bind(
                invalidManifest, "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT"));

        ObjectNode validManifest = manifest("GNOMAD_EXOMES_V4_1_GRCH38_COMPACT", "GNOMAD",
                "GRCh38", "gnomAD exomes v4.1 GRCh38", null);
        RuntimeEvidenceV2ProviderWiring.ResourceBinding binding = wiring.bind(
                validManifest, "GNOMAD_EXOMES_V4_1_GRCH38_COMPACT");
        NormalizedVariantKey grch37 = key("GRCh37", 12138, "C", "A");
        assertThrows(IllegalArgumentException.class, () -> wiring.annotateVariantProvider(
                syntheticContext(grch37), fixedProvider("GNOMAD", binding.getResourceVersion(),
                        hit("GNOMAD", binding.getResourceVersion())), variant(grch37),
                analysisContext(grch37), binding));

        NormalizedVariantKey grch38 = key("GRCh38", 12138, "C", "A");
        AnalysisContext wrongAnalysisContext = new AnalysisContext(
                "wrong-assembly", GenomeAssembly.GRCh37, AnalysisMode.WES);
        assertThrows(IllegalArgumentException.class, () -> wiring.annotateVariantProvider(
                syntheticContext(grch38), fixedProvider("GNOMAD", binding.getResourceVersion(),
                        hit("GNOMAD", binding.getResourceVersion())), variant(grch38),
                wrongAnalysisContext, binding));
    }

    @Test
    void projectsOneClinVarComparatorAssertionAgainstTheTargetAlleleAndThreeResources()
            throws Exception {
        NormalizedVariantKey target = key("GRCh38", "7", 140753336, "A", "T");
        NormalizedVariantKey comparatorAllele = key(
                "GRCh38", "7", 140753336, "A", "C");
        ObjectNode context = syntheticContext(mapper, adapter, target, "ENST00000646891");
        ClinVarConditionAwareComparatorInput.Result input = mock(
                ClinVarConditionAwareComparatorInput.Result.class);
        ClinVarConditionAwareComparatorInput.ComparatorRecord comparator = mock(
                ClinVarConditionAwareComparatorInput.ComparatorRecord.class);
        VepProteinRelationClassifier.ProteinChange targetProtein = mock(
                VepProteinRelationClassifier.ProteinChange.class);
        VepProteinRelationClassifier.ProteinChange observedProtein = mock(
                VepProteinRelationClassifier.ProteinChange.class);
        ObjectNode assertion = mapper.createObjectNode();
        assertion.put("scv", "SCV001424737.1");
        assertion.put("classification", "Pathogenic");
        assertion.put("reviewStatus", "reviewed by expert panel");
        assertion.put("dateLastEvaluated", "Jun 25, 2020");
        assertion.put("geneSymbol", "BRAF");
        assertion.putObject("condition")
                .put("submittedRaw", "MONDO:MONDO:0021060")
                .put("reportedRaw", "C5555857:RASopathy");
        assertion.putObject("sourceLocator")
                .put("logicalName", "submission_summary_2026-03.txt.gz")
                .put("line", 127127);

        when(input.getSchemaVersion()).thenReturn(
                ClinVarConditionAwareComparatorInput.RESULT_SCHEMA_VERSION);
        when(input.getStatus()).thenReturn("READY");
        when(input.getContextId()).thenReturn(context.path("contextId").asText());
        when(input.getTargetAllele()).thenReturn(target);
        when(input.getComparators()).thenReturn(List.of(comparator));
        when(comparator.getSchemaVersion()).thenReturn(
                ClinVarConditionAwareComparatorInput.RECORD_SCHEMA_VERSION);
        when(comparator.getExactAllele()).thenReturn(comparatorAllele);
        when(comparator.getVariationId()).thenReturn("40389");
        when(comparator.getConflictState()).thenReturn("CONFLICTING");
        when(comparator.getGeneSymbols()).thenReturn(List.of("BRAF"));
        when(comparator.getSelectedTargetConsequence()).thenReturn("missense_variant");
        when(comparator.getSelectedComparatorConsequence()).thenReturn("missense_variant");
        when(comparator.getProteinRelation()).thenReturn(
                "SAME_RESIDUE_DIFFERENT_AMINO_ACID");
        when(comparator.getTargetProteinChange()).thenReturn(targetProtein);
        when(comparator.getComparatorProteinChange()).thenReturn(observedProtein);
        when(comparator.getSubmissions()).thenReturn(mapper.createArrayNode().add(assertion));
        protein(targetProtein, "ENST00000646891.2", "600", "V", "E");
        protein(observedProtein, "ENST00000646891.2", "600", "V", "G");

        ObjectNode evidence = wiring(clock).projectClinVarComparatorEvidence(
                context, input, comparatorProfile()).get(0);

        ObjectNode queryProjection = mapper.createObjectNode();
        queryProjection.put("resourceId",
                "CLINVAR_COMPARATOR_SIDECAR_2026_03_05_GRCH38");
        queryProjection.set("queryAllele",
                context.path("variant").path("evidenceQueryAllele").deepCopy());
        assertEquals(adapter.canonicalSha256(queryProjection),
                evidence.path("queryOutcome").path("queryFingerprint").asText());
        assertEquals("C", measurement(evidence,
                "clinvar.comparator.alternate").path("rawValue").asText());
        assertEquals("CLINICAL_DATABASE", evidence.path("evidenceType").asText());
        assertEquals("HIT", evidence.path("queryOutcome").path("status").asText());
        assertEquals("CLINVAR_COMPARATOR_SIDECAR_2026_03_05_GRCH38",
                evidence.path("source").path("sourceId").asText());
        assertEquals("SCV001424737.1", evidence.path("source")
                .path("sourceRecords").path(0).path("recordId").asText());
        assertEquals(3, evidence.path("source").path("sourceLocators").size());
        for (JsonNode locator : evidence.path("source").path("sourceLocators")) {
            assertTrue(locator.path("locatorValue").asText()
                    .startsWith("resource-identity:"));
            assertEquals(64, locator.path("artifactSha256").asText().length());
        }
        assertEquals("SCV001424737.1", measurement(evidence,
                "clinvar.assertion.scv").path("rawValue").asText());
        assertEquals("CONFLICTING", measurement(evidence,
                "clinvar.comparator.recordConflictState").path("rawValue").asText());
        assertEquals("CLINVAR_2026_03_21_GRCH38", measurement(evidence,
                "resource.clinvarVcf.id").path("rawValue").asText());
        assertEquals("VEP_116_GRCH38", measurement(evidence,
                "resource.vep.id").path("rawValue").asText());
        assertEquals("CLINVAR_COMPARATOR_SIDECAR_2026_03_05_GRCH38", measurement(evidence,
                "resource.sidecar.id").path("rawValue").asText());
    }

    @Test
    void projectsComparatorNoRecordAsSuccessfulEmptyEvidence() throws Exception {
        NormalizedVariantKey target = key("GRCh38", "7", 140753336, "A", "T");
        ObjectNode context = syntheticContext(mapper, adapter, target, "ENST00000646891");
        ClinVarConditionAwareComparatorInput.Result input = comparatorInput(
                context, target, "NO_RECORD", "NO_BOUNDED_CLINVAR_COMPARATOR",
                "SUCCEEDED", "AVAILABLE", "NO_RECORD", "APPLICABLE");

        ObjectNode evidence = wiring(clock).projectClinVarComparatorEvidence(
                context, input, comparatorProfile()).get(0);

        assertEquals("SUCCEEDED", evidence.path("resourceExecution").path("status").asText());
        assertEquals("NO_RECORD", evidence.path("queryOutcome").path("status").asText());
        assertEquals(0, evidence.path("queryOutcome").path("recordCount").asInt(-1));
        assertTrue(evidence.path("measurements").isEmpty());
        assertTrue(evidence.path("source").path("sourceRecords").isEmpty());
        assertEquals(3, evidence.path("source").path("sourceLocators").size());
    }

    @Test
    void preservesComparatorFailureWithoutTurningItIntoNoRecord() throws Exception {
        NormalizedVariantKey target = key("GRCh38", "7", 140753336, "A", "T");
        ObjectNode context = syntheticContext(mapper, adapter, target, "ENST00000646891");
        ClinVarConditionAwareComparatorInput.Result input = comparatorInput(
                context, target, "NOT_EVALUATED", "CLINVAR_BOUNDED_QUERY_FAILED",
                "FAILED", "AVAILABLE", "NOT_QUERIED", "UNKNOWN");

        ObjectNode evidence = wiring(clock).projectClinVarComparatorEvidence(
                context, input, comparatorProfile()).get(0);

        assertEquals("FAILED", evidence.path("resourceExecution").path("status").asText());
        assertEquals("CLINVAR_BOUNDED_QUERY_FAILED",
                evidence.path("resourceExecution").path("reasonCode").asText());
        assertEquals("NOT_QUERIED", evidence.path("queryOutcome").path("status").asText());
        assertEquals("UNKNOWN", evidence.path("resourceApplicability").path("status").asText());
        assertTrue(evidence.path("measurements").isEmpty());
        assertTrue(evidence.path("source").path("sourceRecords").isEmpty());
        assertEquals(3, evidence.path("source").path("sourceLocators").size());
    }

    private RuntimeEvidenceV2ProviderWiring wiring(Clock value) {
        return new RuntimeEvidenceV2ProviderWiring(mapper, adapter, value);
    }

    private ClinVarConditionAwareComparatorInput.Result comparatorInput(
            ObjectNode context,
            NormalizedVariantKey target,
            String status,
            String reason,
            String execution,
            String availability,
            String query,
            String applicability) {
        ClinVarConditionAwareComparatorInput.Result input = mock(
                ClinVarConditionAwareComparatorInput.Result.class);
        ClinVarConditionAwareComparatorInput.DiscoverySummary discovery = mock(
                ClinVarConditionAwareComparatorInput.DiscoverySummary.class);
        when(input.getSchemaVersion()).thenReturn(
                ClinVarConditionAwareComparatorInput.RESULT_SCHEMA_VERSION);
        when(input.getContextId()).thenReturn(context.path("contextId").asText());
        when(input.getTargetAllele()).thenReturn(target);
        when(input.getStatus()).thenReturn(status);
        when(input.getReasonCode()).thenReturn(reason);
        when(input.getDiscovery()).thenReturn(discovery);
        when(discovery.getResourceExecutionStatus()).thenReturn(execution);
        when(discovery.getResourceAvailabilityStatus()).thenReturn(availability);
        when(discovery.getQueryOutcomeStatus()).thenReturn(query);
        when(discovery.getResourceApplicabilityStatus()).thenReturn(applicability);
        return input;
    }

    private void protein(VepProteinRelationClassifier.ProteinChange protein,
                         String transcript,
                         String position,
                         String reference,
                         String alternate) {
        when(protein.getTranscriptId()).thenReturn(transcript);
        when(protein.getProteinPosition()).thenReturn(position);
        when(protein.getReferenceAminoAcid()).thenReturn(reference);
        when(protein.getAlternateAminoAcid()).thenReturn(alternate);
    }

    private ObjectNode comparatorProfile() {
        ObjectNode profile = mapper.createObjectNode();
        profile.put("profileSchemaVersion", "clinvar-missense-ps1-pm5-profile-v1");
        ArrayNode resources = profile.putArray("resources");
        comparatorResource(resources,
                "BOUNDED_COMPARATOR_DISCOVERY_AND_ALLELE_IDENTITY_CROSSCHECK_ONLY",
                "CLINVAR_2026_03_21_GRCH38", "ClinVar 2026-03-21 GRCh38", "a");
        comparatorResource(resources, "SELECTED_TRANSCRIPT_PROTEIN_RELATION",
                "VEP_116_GRCH38", "VEP 116.0 / cache 116_GRCh38", "b");
        comparatorResource(resources, "CONDITION_LINKED_ASSERTION_SEMANTICS_ONLY",
                "CLINVAR_COMPARATOR_SIDECAR_2026_03_05_GRCH38",
                "ClinVar 2026-03-05 monthly tab-delimited archive", "c");
        return profile;
    }

    private void comparatorResource(ArrayNode resources,
                                    String role,
                                    String resourceId,
                                    String resourceVersion,
                                    String hashCharacter) {
        resources.addObject()
                .put("role", role)
                .put("resourceId", resourceId)
                .put("resourceVersion", resourceVersion)
                .put("assembly", "GRCh38")
                .put("identityFingerprintSha256", hashCharacter.repeat(64));
    }

    private ObjectNode manifest(String resourceId,
                                String provider,
                                String assembly,
                                String resourceVersion,
                                String toolVersion) {
        ObjectNode identity = mapper.createObjectNode();
        identity.put("provider", provider);
        identity.put("assembly", assembly);
        if (toolVersion == null) {
            identity.put("release", resourceVersion);
        } else {
            ObjectNode tool = mapper.createObjectNode();
            tool.put("name", "ensembl-vep");
            tool.put("version", toolVersion);
            identity.set("tool", tool);
        }
        ObjectNode resource = mapper.createObjectNode();
        resource.put("resourceId", resourceId);
        resource.put("provider", provider);
        resource.put("assembly", assembly);
        resource.put("resourceVersion", resourceVersion);
        resource.put("identityFingerprintSha256", adapter.canonicalSha256(identity));
        resource.set("identity", identity);
        ObjectNode manifest = mapper.createObjectNode();
        ArrayNode resources = mapper.createArrayNode();
        resources.add(resource);
        manifest.set("resources", resources);
        return manifest;
    }

    private VariantEvidenceProvider fixedProvider(String source, String version, EvidenceItem evidence) {
        return new VariantEvidenceProvider() {
            @Override public String source() { return source; }
            @Override public String version() { return version; }
            @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
                    List<VariantInterpretation> variants, AnalysisContext context) {
                return Collections.singletonMap(
                        variants.get(0).getObservation().getKey(), Collections.singletonList(evidence));
            }
        };
    }

    private VariantEvidenceProvider throwingProvider(RuntimeException failure) {
        return new VariantEvidenceProvider() {
            @Override public String source() { return "GNOMAD"; }
            @Override public String version() { return "gnomAD exomes v4.1 GRCh38"; }
            @Override public Map<NormalizedVariantKey, List<EvidenceItem>> annotate(
                    List<VariantInterpretation> variants, AnalysisContext context) {
                throw failure;
            }
        };
    }

    private TranscriptAnnotationProvider fixedTranscriptProvider(TranscriptConsequence consequence) {
        return new TranscriptAnnotationProvider() {
            @Override public String source() { return "VEP_CACHED"; }
            @Override public String version() { return "116.0"; }
            @Override public List<TranscriptConsequence> annotate(
                    VariantInterpretation ignored, AnalysisContext context) {
                return Collections.singletonList(consequence);
            }
        };
    }

    private TranscriptConsequence vepConsequenceWithExon(String exon) {
        return new TranscriptConsequence(
                "SYNTH1", "ENSG000001", "ENST000001",
                "ENST000001.1:c.1G>A", "ENSP000001.1:p.A1T",
                "stop_gained", "ENSG000001", "ENST000001", "Ensembl",
                "HIGH", exon, null, true, true, false, "1", "A/*", "Gca/Tga",
                null, null, null, "VEP_CACHED", "COMPLETED", "116.0", clock.instant());
    }

    private JsonNode measurement(ObjectNode evidence, String metricId) {
        for (JsonNode measurement : evidence.path("measurements")) {
            if (metricId.equals(measurement.path("metricId").asText())) {
                return measurement;
            }
        }
        return mapper.missingNode();
    }

    private EvidenceItem hit(String source, String version) {
        return new EvidenceItem(source, version, "POPULATION", "APPLICABLE", 0.009d,
                "Hit", "Exact provider hit", axes("SUCCEEDED", "AVAILABLE", "HIT", "APPLICABLE"));
    }

    private EvidenceItem noRecord(String source, String version) {
        return new EvidenceItem(source, version, "POPULATION", "NO_RECORD", null,
                "No record", "No exact provider record",
                axes("SUCCEEDED", "AVAILABLE", "NO_RECORD", "APPLICABLE"));
    }

    private Map<String, Object> axes(String execution,
                                     String availability,
                                     String query,
                                     String applicability) {
        Map<String, Object> details = new LinkedHashMap<String, Object>();
        details.put("resourceExecutionStatus", execution);
        details.put("resourceAvailabilityStatus", availability);
        details.put("queryOutcomeStatus", query);
        details.put("resourceApplicabilityStatus", applicability);
        return details;
    }

    static ObjectNode syntheticContext(ObjectMapper mapper,
                                       RuntimeEvidenceV2Adapter adapter,
                                       NormalizedVariantKey key) throws IOException {
        return syntheticContext(mapper, adapter, key, null);
    }

    static ObjectNode syntheticContext(ObjectMapper mapper,
                                       RuntimeEvidenceV2Adapter adapter,
                                       NormalizedVariantKey key,
                                       String transcriptAccession) throws IOException {
        JsonNode fixture = mapper.readTree(RuntimeEvidenceV2ProviderWiringTest.class.getResourceAsStream(
                "/e2/runtime-evidence-v2-fixture.json"));
        ObjectNode context = ((ObjectNode) fixture.path("context")).deepCopy();
        ObjectNode allele = mapper.createObjectNode();
        allele.put("assembly", key.getGenomeAssembly());
        allele.put("contig", key.getChromosome());
        allele.put("position", key.getPosition());
        allele.put("reference", key.getReference());
        allele.put("alternate", key.getAlternate());
        ObjectNode variant = (ObjectNode) context.path("variant");
        variant.set("inputAllele", allele.deepCopy());
        variant.set("normalizedAllele", allele.deepCopy());
        variant.set("evidenceQueryAllele", allele.deepCopy());

        ObjectNode alleleProjection = mapper.createObjectNode();
        alleleProjection.set("normalizedAllele", allele.deepCopy());
        ObjectNode normalizer = mapper.createObjectNode();
        JsonNode normalization = variant.path("normalization");
        for (String field : List.of("method", "methodVersion", "referenceSequenceSha256", "refValidation")) {
            normalizer.set(field, normalization.path(field).deepCopy());
        }
        alleleProjection.set("normalizer", normalizer);
        String alleleId = "allele-sha256-" + adapter.canonicalSha256(alleleProjection);
        variant.put("alleleId", alleleId);

        if (transcriptAccession != null) {
            ((ObjectNode) context.path("transcript")).put("accession", transcriptAccession);
        }

        ObjectNode contextProjection = mapper.createObjectNode();
        contextProjection.set("scope", context.path("scope").deepCopy());
        contextProjection.put("alleleId", alleleId);
        contextProjection.put("hgncId", context.path("gene").path("hgncId").asText());
        contextProjection.put("mondoId", context.path("disease").path("mondoId").asText());
        contextProjection.put("moi", context.path("moi").asText());
        ObjectNode transcript = mapper.createObjectNode();
        transcript.put("accession", context.path("transcript").path("accession").asText());
        transcript.set("version", context.path("transcript").path("version").deepCopy());
        contextProjection.set("transcript", transcript);
        contextProjection.put("patientContextId",
                context.path("patientContext").path("patientContextId").asText());
        contextProjection.set("familyContextId",
                context.path("patientContext").path("familyContextId").deepCopy());
        context.put("contextId", "context-sha256-" + adapter.canonicalSha256(contextProjection));
        return context;
    }

    private ObjectNode syntheticContext(NormalizedVariantKey key) throws IOException {
        return syntheticContext(mapper, adapter, key);
    }

    private ObjectNode syntheticContext(NormalizedVariantKey key,
                                        String transcriptAccession) throws IOException {
        return syntheticContext(mapper, adapter, key, transcriptAccession);
    }

    private ObjectNode allSitesBatchResult(ObjectNode request,
                                           GnomadPopulationInputProfile profile) {
        ObjectNode result = mapper.createObjectNode();
        result.put("resultSchemaVersion", GnomadAllSitesHailBatchRunner.RESULT_SCHEMA_VERSION);
        result.put("requestId", request.path("requestId").asText());
        result.put("status", "SUCCEEDED");
        result.put("observedAt", "2026-09-02T00:00:00Z");
        result.putObject("profile").put("profileId", profile.getProfileId())
                .put("profileVersion", profile.getProfileVersion())
                .put("profileSha256", profile.getProfileSha256());
        result.putObject("tool").put("hailVersion", "0.2.139")
                .put("pysparkVersion", "3.5.3");
        result.putObject("execution")
                .put("startedAt", "2026-09-01T23:59:58Z")
                .put("completedAt", "2026-09-01T23:59:59Z")
                .put("queryMode", "HAIL_FILTER_INTERVALS_BATCH_LOCI")
                .put("requestedLocusCount", request.path("loci").size())
                .put("uniqueLocusCount", 1)
                .put("hailInitializationCount", 1)
                .put("resourceQueryCount", 2);
        ArrayNode resources = result.putArray("resources");
        for (JsonNode resource : profile.getDocument().path("resources")) {
            if (GnomadPopulationInputProfile.JOINT_RESOURCE_ID.equals(
                    resource.path("resourceId").asText())) continue;
            boolean exomes = GnomadPopulationInputProfile.EXOME_ALL_SITES_RESOURCE_ID.equals(
                    resource.path("resourceId").asText());
            ObjectNode value = resources.addObject();
            value.put("resourceId", resource.path("resourceId").asText());
            value.put("resourceVersion", resource.path("resourceVersion").asText());
            value.put("assembly", "GRCh38");
            value.put("identityFingerprintSha256",
                    resource.path("identityFingerprintSha256").asText());
            value.put("officialTableUri", resource.path("identity").path("tableUri").asText());
            value.putObject("execution").put("queryMode", "HAIL_FILTER_INTERVALS_BATCH_LOCI");
            ObjectNode strata = value.putObject("strata");
            strata.putObject("selector").put("group", "adj");
            strata.put("selectedIndex", 0);
            strata.putObject("selectedMetadata").put("group", "adj");
            strata.put("selectedSampleCount", exomes ? 800000 : 150000);
            ArrayNode loci = value.putArray("loci");
            for (JsonNode requested : request.path("loci")) {
                ObjectNode locus = loci.addObject();
                locus.put("locusId", requested.path("locusId").asText());
                ObjectNode query = locus.putObject("queryLocus");
                query.put("assembly", "GRCh38");
                query.put("canonicalContig", requested.path("canonicalContig").asText());
                query.put("resourceContig", "chr" + requested.path("canonicalContig").asText());
                query.put("position", requested.path("position").asInt());
                ObjectNode axes = locus.putObject("axes");
                axes.putObject("resourceExecution").put("status", "SUCCEEDED");
                axes.putObject("resourceAvailability").put("status", "AVAILABLE");
                axes.putObject("queryOutcome").put("status", "HIT").put("recordCount", 1)
                        .put("queryFingerprint", exomes
                                ? "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                                : "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
                axes.putObject("resourceApplicability").put("status", "APPLICABLE");
                ObjectNode measurement = locus.putObject("measurement");
                measurement.put("adjAlleleNumber", exomes ? 10000 : 5000);
                measurement.put("adjStrataIndex", 0);
                if (exomes) {
                    ObjectNode flags = measurement.putObject("regionFlags");
                    flags.put("outside_broad_capture_region", false);
                    flags.put("outside_ukb_capture_region", false);
                    flags.put("outside_broad_calling_region", false);
                    flags.put("outside_ukb_calling_region", false);
                }
            }
        }
        ObjectNode boundary = result.putObject("boundary");
        boundary.put("indexedIntervalsOnly", true);
        boundary.put("fullHailTableDownloaded", false);
        boundary.put("derivedResourceCacheCreated", false);
        boundary.put("patientDataUsed", false);
        boundary.put("clinicalRuleEvaluationRun", false);
        boundary.put("machinePathsIncluded", false);
        return result;
    }

    static NormalizedVariantKey key(String assembly,
                                    int position,
                                    String reference,
                                    String alternate) {
        return key(assembly, "1", position, reference, alternate);
    }

    static NormalizedVariantKey key(String assembly,
                                    String chromosome,
                                    int position,
                                    String reference,
                                    String alternate) {
        return new NormalizedVariantKey(assembly, chromosome, position, reference, alternate);
    }

    static VariantInterpretation variant(NormalizedVariantKey key) {
        return new VariantInterpretation(
                key.canonicalId(),
                new VariantQcObservation(
                        key, "synthetic-proband", "0/1", 30, 99, "18,12", 0.4d,
                        "PASS", "SNV", "synthetic-vcf-record"
                ),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    static AnalysisContext analysisContext(NormalizedVariantKey key) {
        return new AnalysisContext("e2-1-synthetic-smoke",
                GenomeAssembly.valueOf(key.getGenomeAssembly()), AnalysisMode.WES);
    }
}
