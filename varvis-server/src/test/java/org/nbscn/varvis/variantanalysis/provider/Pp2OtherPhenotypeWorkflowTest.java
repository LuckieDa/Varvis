package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real reader/transport/opaque sequence mechanics, synthetic public responses; never a real HCM PP2 result. */
class Pp2OtherPhenotypeWorkflowTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    private record Input(Pp2OtherPhenotypeSource source, Pp2OtherPhenotypeSource.Observed observed, ObjectNode context,
            ObjectNode binding, CodingTranscriptMaterialSupply.Material material, AtomicInteger calls,
            Pp2OtherPhenotypeSource.DiscoveryTarget target) { }

    private Input setup() throws Exception {
        String code = System.getProperty("varvis.pp2.projectRoot"), interpreter = System.getProperty("varvis.pp2.python");
        assertNotNull(code, "Actual current source root is required; no skip is acceptance");
        assertNotNull(interpreter, "Actual existing Python interpreter is required");
        var fixture = new CodingTranscriptMaterialSupplyTest(); fixture.root = Files.createDirectory(root.resolve("technical-model"));
        var supply = fixture.setup(); var material = fixture.resolve(supply); assertTrue(material.ready(), material.snapshot().toString());
        var context = fixture.input.fixture().context().deepCopy(); var model = material.model(); assertNotNull(model);
        var association = material.snapshot().path("alleleAssociation");
        var binding = mapper.createObjectNode().put("criterionId", "ACMG_AMP_2015:PP2")
                .put("fixtureMeaning", "SYNTHETIC_SOURCE_SCOPE_NOT_REAL_HCM_GENE_APPROVAL")
                .put("preparedContextSha256", adapter.canonicalSha256(context)).put("executionContextSha256", adapter.canonicalSha256(context))
                .put("executionModelSha256", adapter.canonicalSha256(model)).put("alleleAssociationSha256", adapter.canonicalSha256(association));
        var publicCandidate = mapper.createObjectNode().put("scope", "PUBLIC_VARIANT_DISCOVERY_HINTS_ONLY");
        publicCandidate.putArray("transcriptAnnotations").addObject().put("geneSymbol", context.at("/gene/symbol").asText())
                .put("versionedTranscriptId", material.snapshot().path("transcript").asText())
                .put("hgvsCoding", association.path("hgvsc").asText()).put("hgvsProtein", association.path("hgvsp").asText());
        Path cache = Files.createDirectory(root.resolve("cache"));
        for (String term : List.of(association.path("hgvsc").asText(), association.path("hgvsp").asText())) {
            String url = "https://www.ncbi.nlm.nih.gov/research/litvar2-api/variant/autocomplete/?query="
                    + java.net.URLEncoder.encode(context.at("/gene/symbol").asText() + " " + term, StandardCharsets.UTF_8) + "&limit=100";
            byte[] raw = "[]".getBytes(StandardCharsets.UTF_8); String pin = CodingTranscriptMaterialSupply.sha(raw);
            Files.write(cache.resolve(pin + ".body"), raw);
            var metadata = mapper.createObjectNode().put("url", url).put("sha256", pin)
                    .put("retrievedEpoch", Instant.now().getEpochSecond()).put("retrievedAt", "SYNTHETIC_TRANSPORT_CACHE");
            Files.write(cache.resolve(CodingTranscriptMaterialSupply.sha(url.getBytes(StandardCharsets.UTF_8)) + ".json"), mapper.writeValueAsBytes(metadata));
        }
        var config = mapper.createObjectNode().put("schemaVersion", "denovo-public-literature-workflow-v1").put("enabled", true)
                .put("projectRoot", code).put("python", interpreter).put("searchCache", cache.toString())
                .put("allowPublicNetwork", false).put("allowModel", false);
        byte[] bytes = mapper.writeValueAsBytes(config); Path file = root.resolve("workflow.json"); Files.write(file, bytes);
        var calls = new AtomicInteger();
        var source = Pp2OtherPhenotypeSource.forWorkflow(mapper, file, CodingTranscriptMaterialSupply.sha(bytes), root.resolve("work"),
                () -> new Pvs1StartLossPublicSources(mapper, true, (uri, timeout, maximum) -> {
                    calls.incrementAndGet(); assertTrue(uri.getPath().endsWith("/summary/classifications"));
                    var body = mapper.createObjectNode(); body.putArray("data"); body.putObject("status").put("code", 200);
                    return new ProteinRegionHttpTransport.Response(200, java.util.Map.of(), mapper.writeValueAsBytes(body));
                }));
        var target = new Pp2OtherPhenotypeSource.DiscoveryTarget("candidate", "hypothesis", (ObjectNode) context.at("/variant/evidenceQueryAllele"),
                context.at("/gene/hgncId").asText(), context.at("/gene/symbol").asText(), context.at("/disease/mondoId").asText(),
                context.path("moi").asText(), publicCandidate);
        var observed = source.observe(List.of(target));
        assertEquals("READ", observed.identity().path("readerStatus").asText(), observed.privateArchive().at("/readerFacts").toString());
        return new Input(source, observed, context, binding, material, calls, target);
    }

    @Test void automaticBoundedScopeNeedsActualOpaqueAliasMaterialAndNeverScoresAClinicalCriterion() throws Exception {
        var input = setup(); assertEquals(2, input.calls().get());
        var pending = input.source().capture(input.observed(), input.context(), "candidate", "hypothesis", input.binding()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, pending.outcome());
        assertTrue(pending.snapshot().path("pending").toString().contains("TARGET_ALIAS_IDENTITY_NOT_VERIFIED"));
        var value = input.source().capture(input.observed(), input.context(), "candidate", "hypothesis", input.binding(), input.material()).review();
        assertEquals(Pp2OtherPhenotypeSource.Outcome.NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE, value.outcome(), value.snapshot().toPrettyString());
        assertTrue(value.snapshot().path("qualifiedEvidence").isEmpty()); assertFalse(value.snapshot().has("appliedStrength"));
        assertTrue(value.matches(input.observed(), input.context(), "candidate", "hypothesis", input.binding()));
        assertEquals(2, input.calls().get(), "capture does not acquire a second source owner");
        var archive = input.observed().privateArchive();
        assertEquals(archive.at("/readerFacts/inputRawSha256"), archive.at("/observation/readerInputSha256"));
        assertEquals(archive.at("/observation/configurationObservation/readerInputRawSha256"), archive.at("/observation/readerInputSha256"));
        assertNotEquals(archive.at("/observation/discoveryTargetsSha256"), archive.at("/observation/readerInputSha256"),
                "A declaration hash is not the complete native-reader packet hash");
        assertEquals(1, archive.at("/readerFacts/nativeCoverage").size());
        for (var read : archive.at("/observation/readDispositions"))
            assertTrue(archive.path("originals").has(read.path("archivedRawSha256").asText()));
        var savedBatch = mapper.createObjectNode().put("sourceSnapshotSha256", "a".repeat(64));
        savedBatch.set("pp2OtherPhenotypeSourceIdentity", input.observed().identity());
        var retained = Pp2OtherPhenotypeArchive.create(mapper, input.observed(), savedBatch);
        savedBatch.set("pp2OtherPhenotypeArchiveSha256", retained.path("archiveSha256"));
        assertEquals(retained, Pp2OtherPhenotypeArchive.verifyAndExport(mapper, retained, savedBatch),
                "The actual workflow READ packet must survive the real private archive reader");
        boolean nativeReceipt = false, cachedQueryReceipt = false;
        for (var original : archive.path("originals")) for (var receipt : original.path("provenance")) {
            if (receipt.has("capturedAt") && receipt.path("sourceUrl").asText().startsWith(Pvs1StartLossPublicSources.BASE)) nativeReceipt = true;
            if (receipt.has("retrievedAt") && "CACHE_HIT".equals(receipt.path("status").asText())) cachedQueryReceipt = true;
        }
        assertTrue(nativeReceipt, "Actual native transport receipt remains in the private source archive");
        assertTrue(cachedQueryReceipt, "The actual cached HTTP retrieval time is not discarded");
    }

    @Test void wrongActualModelOrContextBindingCannotClearEvenWhenAllPublicQueriesHaveNoHits() throws Exception {
        var input = setup();
        for (String field : List.of("preparedContextSha256", "executionModelSha256", "alleleAssociationSha256")) {
            var altered = input.binding().deepCopy(); altered.put(field, "a".repeat(64));
            var value = input.source().capture(input.observed(), input.context(), "candidate", "hypothesis", altered, input.material()).review();
            assertEquals(Pp2OtherPhenotypeSource.Outcome.UNKNOWN, value.outcome(), field);
        }
        assertEquals(Pp2OtherPhenotypeSource.Outcome.INVALID,
                input.source().capture(input.observed(), input.context(), "candidate", "foreign", input.binding(), input.material()).review().outcome());
    }
    @Test void actualTransportReceiptTimesAreRetainedPrivatelyWithoutChangingTheSemanticRequestIdentity() throws Exception {
        var input = setup(); var repeated = input.source().observe(List.of(input.target()));
        assertEquals(input.observed().identity(), repeated.identity());
        assertEquals(input.observed().snapshot(), repeated.snapshot());
        assertFalse(repeated.snapshot().toString().contains("capturedAt"));
        assertFalse(repeated.snapshot().toString().contains("retrievedAt"));
        assertTrue(repeated.privateArchive().path("originals").toString().contains("capturedAt"));
        assertTrue(repeated.privateArchive().path("originals").toString().contains("retrievedAt"));
        assertEquals(4, input.calls().get(), "Two actual bounded native traversals in each source observation");
    }
    @Test void actualAssessedEntryIssuesZeroWorkAuditAndCannotRestoreOrCrossBindAuthority() throws Exception {
        var input = setup(); assertNull(input.source().derivedArchive(input.observed()));
        var assessed = input.source().assess(input.observed(), input.context(), "candidate", "hypothesis", input.binding(), input.material());
        var review = assessed.review(); assertEquals(Pp2OtherPhenotypeSource.Outcome.NO_SUPPORT_IN_COMPLETED_ASSESSED_SCOPE, review.outcome());
        assertTrue(review.matches(input.observed(), input.context(), "candidate", "hypothesis", input.binding()));
        assertFalse(review.matches(input.observed(), input.context(), "candidate", "foreign", input.binding()));
        var archive = input.source().derivedArchive(input.observed()); assertNotNull(archive); assertEquals(1, archive.path("assessments").size());
        assertEquals(review.snapshot().at("/binding/derivedAssessmentSha256"), archive.at("/assessments/0/assessmentSha256"));
        assertEquals(0, review.snapshot().at("/literatureAssessment/assessedPapers").asInt());
        assertEquals(review.snapshot(), input.source().assess(input.observed(), input.context(), "candidate", "hypothesis", input.binding(), input.material()).snapshot());
        assertEquals(1, java.util.Objects.requireNonNull(input.source().derivedArchive(input.observed())).path("assessments").size());
        assertThrows(java.io.IOException.class, () -> mapper.readValue(mapper.writeValueAsBytes(assessed.snapshot()), Pp2OtherPhenotypeSource.Assessed.class));
        var foreign = input.source().observe(List.of(input.target()));
        assertTrue(input.source().derivedArchive(foreign) == null, "A second actual observation does not borrow issued handles");
    }
    @Test void realAfterClaimWorkerReadsKnownPaperButDoesNotTurnDisabledExtractionIntoNoSupport() throws Exception {
        var input = setup(); Path cache = root.resolve("reading-cache"), library = root.resolve("reading-library");
        Files.createDirectory(cache); Files.createDirectory(library);
        byte[] xml = ("<article><front><article-meta><article-id pub-id-type=\"pmcid\">PMC123</article-id>"
                + "<article-id pub-id-type=\"pmid\">456</article-id><title-group><article-title>Actual synthetic public paper</article-title>"
                + "</title-group></article-meta></front><body><p>Case 1 was studied. No parents were tested.</p></body></article>").getBytes(StandardCharsets.UTF_8);
        var metadata = mapper.createObjectNode().put("hitCount", 1); metadata.putObject("resultList").putArray("result").addObject()
                .put("source", "MED").put("id", "456").put("pmcid", "PMC123").put("title", "Actual synthetic public paper")
                .put("isOpenAccess", "Y").put("hasSuppl", "N");
        cache(cache, "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=EXT_ID%3A456+AND+SRC%3AMED&format=json&resultType=core&pageSize=25&cursorMark=%2A", mapper.writeValueAsBytes(metadata));
        cache(cache, "https://www.ebi.ac.uk/europepmc/webservices/rest/PMC123/fullTextXML", xml);
        var model = mapper.createObjectNode().put("schemaVersion", "denovo-model-config-v1").put("enabled", true).put("pricingConfirmed", true)
                .put("protocol", "openai-chat-completions-json-schema").put("endpoint", "https://example.invalid/v1/chat/completions")
                .put("model", "gemini-3-flash").put("expectedResponseModel", "gemini-3-flash").put("maxCalls", 8).put("maxOutputTokens", 8192)
                .put("maxBilledOutputTokens", 16384).put("timeoutSeconds", 1).put("maxRequestBytes", 262144).put("maxResponseBytes", 1048576)
                .put("inputCreditsPerMillion", 1).put("outputCreditsPerMillion", 6).put("creditsPerRmb", 3).put("budgetRmb", 16.66)
                .put("apiKeyEnvironment", "VARVIS_SYNTHETIC_NO_CREDENTIALS");
        Path modelPath = root.resolve("reading-model.json"); Files.write(modelPath, mapper.writeValueAsBytes(model));
        var cfg = mapper.createObjectNode().put("schemaVersion", "denovo-public-literature-workflow-v1").put("enabled", true)
                .put("projectRoot", System.getProperty("varvis.pp2.projectRoot")).put("python", System.getProperty("varvis.pp2.python"))
                .put("searchCache", cache.toString()).put("library", library.toString()).put("modelConfig", modelPath.toString())
                .put("modelBudget", root.resolve("reading-budget").toString()).put("allowPublicNetwork", false).put("allowModel", false);
        Path config = root.resolve("reading-config.json"); byte[] cfgRaw = mapper.writeValueAsBytes(cfg); Files.write(config, cfgRaw);
        var workflow = new Pp2OtherPhenotypeWorkflow(config, CodingTranscriptMaterialSupply.sha(cfgRaw), root.resolve("afterclaim"),
                () -> { throw new AssertionError("Afterclaim reader cannot open a second native owner"); });
        var known = mapper.createArrayNode(); known.addObject().put("pmid", "456").put("sourceRawSha256", "a".repeat(64));
        var reading = workflow.readLiterature(mapper, (ObjectNode)input.observed().privateArchive().path("methodSources"), mapper.createArrayNode(), known);
        assertEquals(1, reading.ledger().path("papers").size(), reading.ledger().toPrettyString());
        assertEquals("PMC123", reading.ledger().at("/papers/0/pmcid").asText());
        assertEquals(0, reading.ledger().path("modelCalls").asInt());
        assertTrue(reading.ledger().path("pending").toString().contains("MODEL_DISABLED_CACHE_MISSING"));
        assertFalse(reading.ledger().path("readingComplete").asBoolean());
        assertTrue(reading.ledger().path("originals").has(CodingTranscriptMaterialSupply.sha(xml)));
    }
    @Test void completeExistingReaderImplementationIsInPrecacheIdentityAndCannotDriftBeforeReading() throws Exception {
        var input = setup(); var archive = input.observed().privateArchive();
        var methods = (ObjectNode)archive.path("methodSources");
        String pin = CodingTranscriptMaterialSupply.sha(Files.readAllBytes(Path.of(System.getProperty("varvis.pp2.projectRoot"))
                .resolve("scripts/denovo/bundle_model_transport.py")));
        assertEquals(pin, methods.at("/derivedReaderImplementation/bundle_model_transport.py").asText());
        assertEquals(adapter.canonicalSha256(methods), input.observed().identity().path("methodSourcesSha256").asText());
        var changed = methods.deepCopy(); ((ObjectNode)changed.path("derivedReaderImplementation")).put("bundle_model_transport.py", "a".repeat(64));
        Path config = root.resolve("workflow.json");
        var workflow = new Pp2OtherPhenotypeWorkflow(config, CodingTranscriptMaterialSupply.sha(Files.readAllBytes(config)), root.resolve("not-called"),
                () -> { throw new AssertionError("No second source owner"); });
        var result = workflow.readLiterature(mapper, changed, mapper.createArrayNode(), mapper.createArrayNode());
        assertTrue(result.ledger().path("pending").toString().contains("OBSERVED_IMPLEMENTATION_CHANGED"));
        assertFalse(Files.exists(root.resolve("not-called")), "Method mismatch is detected before any reader/model work");
    }
    private void cache(Path root, String url, byte[] raw) throws Exception {
        String pin = CodingTranscriptMaterialSupply.sha(raw); Files.write(root.resolve(pin + ".body"), raw);
        var value = mapper.createObjectNode().put("url", url).put("sha256", pin).put("retrievedEpoch", Instant.now().getEpochSecond()).put("retrievedAt", "SYNTHETIC_SOURCE_ONLY");
        Files.write(root.resolve(CodingTranscriptMaterialSupply.sha(url.getBytes(StandardCharsets.UTF_8)) + ".json"), mapper.writeValueAsBytes(value));
    }

}
