package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Actual registered-source/Python reader/selected-batch chain; identity, family and transport are synthetic. */
class DeNovoDiscoverySelectedChainTest {
    private static final String DISCOVERY = "denovo-source-event-discovery-v1";
    private static final String REPLAY = "BOUNDED_REGISTERED_EVENT_DISCOVERY_REPLAY";
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    private record Prepared(DeNovoBatchFixture materials, JsonNode source, Path python, Path scripts, Path cache) { }

    private Prepared prepare(String kind, String partnerStatus) throws Exception {
        Path python = Path.of(System.getProperty("denovo.test.python", "missing-python"));
        Path scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required for the actual registered-source chain");
        Path output = temporary.resolve(kind + "-" + partnerStatus);
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B", "-c",
                "from test_core_source_event_discovery import write_java_fixture; import sys; write_java_fixture(sys.argv[1], kind=sys.argv[2], reported_partner_status=sys.argv[3])",
                output.toString(), kind, partnerStatus), scripts, Duration.ofSeconds(45));
        assertTrue(process.isSuccess(), process.getOutput());
        JsonNode source = mapper.readTree(Files.readAllBytes(output.resolve("java-discovery-fixture.json")));
        assertEquals("SYNTHETIC_NORMAL_REGISTERED_" + kind, source.path("sourceClass").asText());
        assertEquals("NOT_RUN", source.path("criterionDecision").asText());
        JsonNode options = mapper.readTree(Files.readAllBytes(output.resolve(source.at("/coreContext/path").asText())));
        assertFalse(options.has("specialEventSources"), "The event must be discovered from the registered source without a case-key configuration");

        var f = new DeNovoBatchFixture(output);
        f.phenotype.removeAll().setAll((ObjectNode) source.path("policy"));
        JsonNode scope = source.at("/policy/scope"), allele = source.at("/core/values").path("/context/allele");
        assertEquals("AR", scope.path("inheritance").asText());
        assertTrue(scope.path("diseaseId").asText().matches("MONDO:[0-9]{7}"));
        var target = (ObjectNode) f.report.path("target"); target.removeAll().setAll((ObjectNode) allele);
        target.put("hgncId", scope.path("hgncId").asText());
        for (JsonNode entry : f.catalog.path("entries")) setScope((ObjectNode) entry.path("scope"), scope, allele);
        for (JsonNode policy : f.selectorPolicies.path("policies")) setScope((ObjectNode) policy.path("scope"), scope, allele);

        byte[] knowledge = source.at("/policySources/paper").asText().getBytes(StandardCharsets.UTF_8);
        Files.write(f.root.resolve("source.txt"), knowledge);
        String knowledgeSha = DeNovoBatchFixture.sha(knowledge);
        ((ObjectNode) f.manifest.at("/sources/0")).put("sha256", knowledgeSha);
        ((ObjectNode) f.catalog.at("/sources/0")).put("sha256", knowledgeSha);
        f.manifest.withArray("sources").addObject().put("sourceId", "paper").put("path", "source.txt").put("sha256", knowledgeSha);
        f.manifest.withArray("sources").addObject().put("sourceId", "registered-paper").put("path", "synthetic-literature.xml")
                .put("sha256", source.path("sourceSha256").asText());
        f.save();
        assertEquals(source.at("/core/policySha256"), f.manifest.at("/phenotypePolicies/0/sha256"), "Retain the actual helper policy bytes and pin");
        f.manifest.putArray("reports");
        f.manifest.putArray("literature").addObject().put("sourceId", "registered-paper").put("pmcid", source.path("pmcid").asText()).putArray("alleles");
        f.writeManifest();
        assertFalse(f.report.has("specialEvent"), "The local fixture report is not used to supply a hand-written event");
        return new Prepared(f, source, python, scripts, temporary.resolve("cache-" + kind + "-" + partnerStatus));
    }

    private static void setScope(ObjectNode target, JsonNode scope, JsonNode allele) {
        target.put("hgncId", scope.path("hgncId").asText()).put("mondoId", scope.path("diseaseId").asText())
                .put("moi", "AUTOSOMAL_RECESSIVE").put("assembly", allele.path("assembly").asText());
    }

    private ObjectNode context(Prepared prepared) {
        var f = prepared.materials(); var context = f.context().put("moi", "AUTOSOMAL_RECESSIVE");
        ((ObjectNode) context.path("gene")).put("hgncId", prepared.source().at("/policy/scope/hgncId").asText()).put("symbol", "EXAMPLE");
        ((ObjectNode) context.path("disease")).put("mondoId", prepared.source().at("/policy/scope/diseaseId").asText());
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        context.put("contextId", adapter.canonicalVersionedContextId(context)); adapter.validateVersionedContext(context);
        return context;
    }

    private DeNovoLiteratureReader reader(Prepared prepared) throws Exception {
        var f = prepared.materials(); Path index = f.root.resolve("model-artifacts.json");
        return new DeNovoLiteratureReader(f.mapper, prepared.python(), prepared.scripts(), prepared.cache(), index,
                DeNovoBatchFixture.sha(Files.readAllBytes(index)))
                .withCoreContext(f.root, f.root.resolve(prepared.source().at("/coreContext/path").asText()), prepared.source().at("/coreContext/sha256").asText());
    }

    private DeNovoSelectedBatch load(Prepared prepared, DeNovoLiteratureReader reader) throws Exception {
        var f = prepared.materials(); Path manifest = f.root.resolve("manifest.json");
        return DeNovoSelectedBatch.load(f.mapper, manifest, DeNovoBatchFixture.sha(Files.readAllBytes(manifest)),
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION, reader);
    }

    @ParameterizedTest
    @CsvSource({"JATS,UNRESOLVED,NOT_EVALUATED", "JSON,UNRESOLVED,NOT_EVALUATED",
            "JATS,NONE_IN_CHECKED_SCOPE,NOT_MET", "JSON,NONE_IN_CHECKED_SCOPE,NOT_MET"})
    void registeredDiscoveryReachesNormalSelectedReaderWithSharedFactsCacheAndOriginals(String kind, String partnerStatus, String expected) throws Exception {
        var prepared = prepare(kind, partnerStatus); var firstReader = reader(prepared); var first = load(prepared, firstReader);
        var context = context(prepared); var all = first.evaluateBound(context, mapper.createObjectNode());
        assertEquals(1, firstReader.executions(), "The first material load must execute the actual Python reader");
        assertEquals("SOURCE_REPLAYED_FOR_PER_TARGET_QUALIFICATION", first.identity().path("literatureQualification").asText(), all.toPrettyString());
        assertEquals("SYNTHETIC_MODEL_REPLAY_ONLY", first.identity().path("modelValidation").asText());
        assertEquals(expected, all.at("/qualifiedResult/result/status").asText(), all.toPrettyString());
        assertEquals("NONE", all.at("/qualifiedResult/result/code").asText());
        assertEquals("NONE", all.at("/criteria/PS2/appliedStrength").asText());
        assertEquals("NONE", all.at("/criteria/PM6/appliedStrength").asText());
        if ("UNRESOLVED".equals(partnerStatus)) {
            assertTrue(all.toString().contains("DENOVO_AR_PARTNER_REVIEW_REQUIRED"));
        } else {
            // The source-pinned HIGH_HETEROGENEITY condition gives 0.5 raw points;
            // the AR one-level reduction is below Supporting, with facts retained.
            assertEquals(2, all.at("/qualifiedResult/result/rawQuarterPoints").asInt());
            assertEquals(2, all.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
            assertEquals("ONE_LEVEL_AR_NO_SECOND_P_LP", all.at("/qualifiedResult/result/strengthAdjustment").asText());
            assertEquals("DENOVO_AR_ONE_LEVEL_REDUCTION", all.at("/qualifiedResult/result/reasonCode").asText());
        }
        assertReplayedOriginals(prepared, first, all, kind, partnerStatus);

        for (String code : List.of("PS2", "PM6")) {
            var cachedReader = reader(prepared); var cached = load(prepared, cachedReader);
            var one = cached.evaluateBound(context, mapper.createObjectNode(), List.of("ACMG_AMP_2015:" + code));
            assertEquals(0, cachedReader.executions(), "A new reader must reuse the source-bound cache");
            assertEquals(all.path("qualifiedResult"), one.path("qualifiedResult"));
            assertEquals(all.at("/criteria/" + code), one.at("/criteria/" + code));
            assertEquals(1, one.path("criteria").size());
            assertEquals(all.path("coreMaterials"), one.path("coreMaterials"));
            assertEquals(first.originals(), cached.originals());
        }
        var reopenedReader = reader(prepared); var reopened = load(prepared, reopenedReader);
        assertEquals(0, reopenedReader.executions());
        assertEquals(all, reopened.evaluateBound(context, mapper.createObjectNode()));
        assertEquals(first.identity(), reopened.identity());
    }

    private void assertReplayedOriginals(Prepared prepared, DeNovoSelectedBatch module, JsonNode result, String kind, String partnerStatus) throws Exception {
        String paperSha = prepared.source().path("sourceSha256").asText();
        assertArrayEquals(Files.readAllBytes(prepared.materials().root.resolve("synthetic-literature.xml")),
                Base64.getDecoder().decode(module.originals().path(paperSha).asText()));
        String resultSha = module.identity().path("literatureSourceResultSha256").asText();
        assertTrue(resultSha.matches("[0-9a-f]{64}"));
        byte[] replayRaw = Base64.getDecoder().decode(module.originals().path(resultSha).asText());
        assertEquals(resultSha, DeNovoBatchFixture.sha(replayRaw));
        JsonNode replay = mapper.readTree(replayRaw), facts = replay.at("/documents/0/facts"), core = facts.at("/cases/0/coreEvaluation");
        assertEquals("READY_FOR_EVALUATION", core.path("status").asText(), core.toPrettyString());
        assertEquals("NOT_RUN", core.path("criterionDecision").asText());
        assertEquals(prepared.source().at("/core/specialEvent"), core.path("specialEvent"));
        assertEquals(partnerStatus, core.at("/specialEvent/partner/status").asText());
        assertEquals(REPLAY, core.path("fieldProofs").path("/extensions/specialEvent").at("/check/method").asText());
        assertEquals(kind, core.at("/specialEventSource/discovery/format").asText());
        assertEquals(DISCOVERY, core.at("/sourceDiscovery/version").asText());
        assertTrue(core.at("/sourceDiscovery/records").findValues("isEventCandidate").stream().anyMatch(mappedItem -> mappedItem.asBoolean()));
        assertEquals("EXACT", result.at("/publicSummary/coreMaterials/0/selectionMatch").asText());
        assertEquals(DISCOVERY, result.at("/publicSummary/coreMaterials/0/sourceDiscovery/version").asText());

        JsonNode archive = facts.path("coreAuditArchive"); byte[] auditRaw;
        assertEquals("gzip+base64", archive.path("encoding").asText());
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(archive.path("payload").asText())))) {
            auditRaw = gzip.readNBytes(16_777_217);
        }
        assertTrue(auditRaw.length <= 16_777_216);
        assertEquals(archive.path("uncompressedBytes").asInt(), auditRaw.length);
        assertEquals(archive.path("sha256").asText(), DeNovoBatchFixture.sha(auditRaw));
        JsonNode originals = mapper.readTree(auditRaw).path("originalsBySha256");
        for (var pin : core.path("sourcePins")) {
            String sha = pin.path("sha256").asText(); assertTrue(originals.has(sha), sha);
            assertEquals(sha, DeNovoBatchFixture.sha(Base64.getDecoder().decode(originals.path(sha).asText())));
        }
        String observationSource = core.at("/specialEventSource/discovery/sourceSha256").asText();
        byte[] observationOriginal = Base64.getDecoder().decode(originals.path(observationSource).asText());
        assertEquals(observationSource, DeNovoBatchFixture.sha(observationOriginal));
        if ("JATS".equals(kind)) assertArrayEquals(Files.readAllBytes(prepared.materials().root.resolve("synthetic-literature.xml")), observationOriginal);
        else assertEquals(partnerStatus, mapper.readTree(observationOriginal).at("/cases/0/specialEvent/partner/status").asText());
    }
}
