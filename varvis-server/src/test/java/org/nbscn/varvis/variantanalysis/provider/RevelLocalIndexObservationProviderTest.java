package org.nbscn.varvis.variantanalysis.provider;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RevelLocalIndexObservationProviderTest {
    @Test void technicalEnstBridgeReadsFactsWithoutClaimingReviewedClinicalPolicy() throws Exception {
        var provider = load(); var request = fixture.request(provider);
        ((ObjectNode) request.path("revelTranscriptBinding")).put("status", GeneralRevelPolicy.BRIDGE);
        request.remove("lookupId"); request.put("lookupId", "prediction-lookup-sha256-" + fixture.inputs.base.adapter.canonicalSha256(request));
        var result = provider.lookupSnapshot(request);
        assertEquals("HIT", result.path("status").asText(), result.toPrettyString());
        ((ObjectNode) request.path("revelTranscriptBinding")).put("accession", "ENST999999");
        request.remove("lookupId"); request.put("lookupId", "prediction-lookup-sha256-" + fixture.inputs.base.adapter.canonicalSha256(request));
        assertEquals(0, provider.lookupSnapshot(request).path("queryAttempts").asInt());
    }
    @TempDir Path root;
    final RevelPublisherIndexObservationProviderTest fixture = new RevelPublisherIndexObservationProviderTest();
    Path tool;
    @BeforeEach void setup() throws Exception { tool = RevelLocalIndexFixture.create(root); }
    RevelPublisherIndexObservationProvider load() throws Exception {
        var mapper = fixture.inputs.base.mapper;
        return RevelPublisherIndexObservationProvider.loadLocal(mapper, root, root.resolve("review.json"),
                RevelLocalIndexFixture.sha(root.resolve("review.json")), fixture.inputs.base.adapter.canonicalSha256(mapper.readTree(root.resolve("review.json").toFile())));
    }
    @Test void actualIndexedTransportPreservesAllRowsAndCountsWithoutAnExecutable() throws Exception {
        Files.delete(tool); // local mode must not attempt to open/execute the builder's old tool.
        var provider = load(); var result = provider.lookupSnapshot(fixture.request(provider));
        assertEquals("HIT", result.path("status").asText(), result.toPrettyString());
        assertEquals(4, result.path("coordinateRecords").size()); assertEquals(2, result.path("rawRecords").size());
        assertEquals(1, result.path("physicalLocalQueries").asInt()); assertEquals(0, result.path("physicalTabixCalls").asInt());
        assertTrue(result.path("compressedDataBytesRead").asLong() > 0);
        assertFalse(result.toString().contains(root.toString()));
        var external = new RevelPublisherIndexObservationProvider(fixture.inputs.base.mapper, root,
                RevelLocalIndexFixture.create(root), root.resolve("review.json"), RevelLocalIndexFixture.sha(root.resolve("review.json")),
                fixture.inputs.base.adapter.canonicalSha256(fixture.inputs.base.mapper.readTree(root.resolve("review.json").toFile())),
                RevelPublisherIndexObservationProviderTest.returning(""));
        assertNotEquals(external.executionIdentity(), provider.executionIdentity());
    }
    @Test void migratedMetadataRequiresMatchingFullBytesAndPreservesHistoricalBinding() throws Exception {
        var original = load().resourceBinding(); byte[] review = Files.readAllBytes(root.resolve("review.json"));
        for (String name : new String[]{"manifest.json", RevelPublisherIndexManifest.DATA, RevelPublisherIndexManifest.INDEX, RevelPublisherIndexManifest.EXCLUDED}) {
            Path path = root.resolve(name); Files.setLastModifiedTime(path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 10000));
        }
        var provider = load(); assertEquals(original, provider.resourceBinding());
        assertArrayEquals(review, Files.readAllBytes(root.resolve("review.json")));
        assertEquals("HIT", provider.lookupSnapshot(fixture.request(provider)).path("status").asText());
        Path data = root.resolve(RevelPublisherIndexManifest.DATA); byte[] changed = Files.readAllBytes(data); changed[20] ^= 1; Files.write(data, changed);
        assertEquals("REVEL_RELOCATED_ARTIFACT_SHA_MISMATCH", assertThrows(java.io.IOException.class, this::load).getMessage());
    }
    @Test void changedAfterLoadAndCancellationCannotReturnNegativeOrPartialEvidence() throws Exception {
        var provider = load(); ObjectNode request = fixture.request(provider);
        Thread.currentThread().interrupt();
        try {
            var blocked = provider.lookupSnapshot(request);
            assertEquals("REVEL_QUERY_INTERRUPTED", blocked.path("reasonCode").asText());
            assertEquals(0, blocked.path("physicalLocalQueries").asInt()); assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        Files.writeString(root.resolve(RevelPublisherIndexManifest.DATA), "damaged");
        fixture.assertUnavailable(provider.lookupSnapshot(request));
    }
    @Test void noRecordMeansNoScoreAndTimeoutAndInvalidRegionAreErrors() throws Exception {
        var query = new RevelLocalIndexQuery(); Path data = root.resolve(RevelPublisherIndexManifest.DATA), index = root.resolve(RevelPublisherIndexManifest.INDEX);
        assertEquals("", query.query(data, index, "1:101-101", Duration.ofSeconds(1)).output());
        assertEquals("REVEL_QUERY_POINT_REQUIRED", assertThrows(java.io.IOException.class,
                () -> query.query(data, index, "1:100-101", Duration.ofSeconds(1))).getMessage());
        AtomicLong time = new AtomicLong();
        assertEquals("REVEL_QUERY_TIMEOUT", assertThrows(java.io.IOException.class,
                () -> query.query(data, index, "1:100-100", Duration.ofMillis(1), () -> time.getAndAdd(2_000_000))).getMessage());
    }
    @ParameterizedTest @ValueSource(strings = {"valid", "cdsRef", "codons", "aminoAcid", "transcript"})
    void existingBundleConsumesLocalRowsAndStillRequiresExactCodingFacts(String kind) throws Exception {
        var provider = load(); var input = fixture.inputs.input("0.7", "GE", "0.7");
        input.policy().set("resourceBinding", provider.resourceBinding());
        var model = (ObjectNode) input.fixture().observation().path("transcriptModel");
        var candidate = input.fixture().candidate(); var mapper = fixture.inputs.base.mapper;
        if ("cdsRef".equals(kind)) model.put("cdsSequence", "ATGACATAA");
        if (!"valid".equals(kind) && !"cdsRef".equals(kind)) {
            ObjectNode tx = mapper.valueToTree(candidate.getTranscriptAnnotations().get(0));
            // Use the known input DTO fields, excluding derived getters.
            tx.retain("geneSymbol", "transcriptAccession", "transcriptId", "hgvsCoding", "hgvsProtein", "consequence", "exon", "proteinPosition", "aminoAcids", "codons", "source", "status", "version", "timestamp");
            if ("codons".equals(kind)) tx.put("codons", "AAA/ATA");
            if ("aminoAcid".equals(kind)) tx.put("aminoAcids", "K/N");
            if ("transcript".equals(kind)) tx.put("hgvsCoding", "ENST000001.5:c.5A>G");
            candidate.applyTranscriptAnnotations(java.util.List.of(mapper.treeToValue(tx, org.nbscn.varvis.variantanalysis.domain.TranscriptConsequence.class)));
        }
        ((ObjectNode) input.policy().path("transcriptModelBinding")).put("canonicalSha256", fixture.inputs.base.adapter.canonicalSha256(model));
        var document = fixture.bundles.document(input.policy());
        ((ObjectNode) document.at("/materials/transcriptModels")).set(input.policy().at("/transcriptModelBinding/canonicalSha256").asText(), model);
        var result = fixture.bundles.load(document).evaluate(input.fixture().context(), candidate, provider);
        if ("valid".equals(kind)) fixture.bundles.assertStatus(result, "PP3", "MET");
        else {
            assertEquals("NOT_EVALUATED", result.at("/clauses/PP3/result/evaluationResult/status").asText(), result.toPrettyString());
            assertEquals(0, result.at("/executionCounts/completedClauseEvaluations").asInt());
        }
    }

    @Test void configuredReviewedRouteUsesLocalTransportWithoutTabixProperty() throws Exception {
        var provider = load(); var input = fixture.inputs.input("0.7", "GE", "0.7");
        input.policy().set("resourceBinding", provider.resourceBinding());
        var document = fixture.bundles.document(input.policy());
        ((ObjectNode) document.at("/materials/transcriptModels")).set(input.policy().at("/transcriptModelBinding/canonicalSha256").asText(),
                input.fixture().observation().path("transcriptModel"));
        var mapper = fixture.inputs.base.mapper; Path bundle = root.resolve("bundle.json"); Files.write(bundle, mapper.writeValueAsBytes(document));
        String p = "variant-analysis.acmg-business.";
        var env = new org.springframework.mock.env.MockEnvironment().withProperty(p + "enabled", "true")
                .withProperty(p + "reviewed-prediction-policy-enabled", "true").withProperty(p + "reviewed-prediction-policy-file", requireNonNull(bundle.toString()))
                .withProperty(p + "reviewed-prediction-policy-file-sha256", requireNonNull(RevelLocalIndexFixture.sha(bundle)))
                .withProperty(p + "reviewed-prediction-policy-sha256", requireNonNull(fixture.inputs.base.adapter.canonicalSha256(document)))
                .withProperty(p + "revel-publisher-transport", "LOCAL_HTSJDK").withProperty(p + "revel-publisher-index-directory", requireNonNull(root.toString()))
                .withProperty(p + "revel-publisher-review-file", requireNonNull(root.resolve("review.json").toString()))
                .withProperty(p + "revel-publisher-review-file-sha256", requireNonNull(RevelLocalIndexFixture.sha(root.resolve("review.json"))))
                .withProperty(p + "revel-publisher-review-sha256", requireNonNull(fixture.inputs.base.adapter.canonicalSha256(mapper.readTree(root.resolve("review.json").toFile()))))
                .withProperty(p + "alphamissense-enabled", "false").withProperty(p + "spliceai-enabled", "false");
        Object setup = org.springframework.test.util.ReflectionTestUtils.invokeMethod(org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig.class,
                "reviewedPrediction", mapper, new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties(), env);
        ReviewedPredictionBusinessRuntime runtime = org.springframework.test.util.ReflectionTestUtils.invokeMethod(requireNonNull(setup), "runtime");
        ReviewedPredictionObservationRouter router = org.springframework.test.util.ReflectionTestUtils.invokeMethod(requireNonNull(setup), "router");
        assertNotNull(runtime); assertNotNull(router);
        assertEquals(RevelLocalIndexQuery.METHOD, router.identity().at("/REVEL/executionIdentity/method").asText());
        var prepared = runtime.prepare(input.fixture().context());
        var session = router.open(java.util.List.of(new ReviewedPredictionObservationRouter.Input(prepared,input.fixture().candidate(),java.util.List.of())),
                new org.nbscn.varvis.variantanalysis.domain.AnalysisContext("local-revel-test",org.nbscn.varvis.variantanalysis.domain.GenomeAssembly.GRCh38,org.nbscn.varvis.variantanalysis.domain.AnalysisMode.WES));
        var result = runtime.execute(prepared,input.fixture().candidate(),session);
        fixture.bundles.assertStatus(result,"PP3","MET"); assertEquals(1,result.path("providerCalls").asInt());
        env.withProperty(p + "revel-publisher-transport", "UNSUPPORTED_TRANSPORT");
        Object invalid = org.springframework.test.util.ReflectionTestUtils.invokeMethod(org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig.class,
                "reviewedPrediction",mapper,new org.nbscn.varvis.variantanalysis.config.VariantAnalysisProperties(),env);
        ReviewedPredictionObservationRouter refused = org.springframework.test.util.ReflectionTestUtils.invokeMethod(requireNonNull(invalid),"router");
        assertNotNull(refused); assertEquals("UNAVAILABLE",refused.identity().at("/REVEL/status").asText());
    }
}
