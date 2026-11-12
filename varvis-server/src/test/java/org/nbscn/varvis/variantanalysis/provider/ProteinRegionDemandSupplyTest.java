package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** All biological records are invented test-only data. No public endpoint is used. */
class ProteinRegionDemandSupplyTest {
    @TempDir Path root;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final UniProtProteinRegionSupplyTest fixture = new UniProtProteinRegionSupplyTest();
    final AtomicInteger calls = new AtomicInteger();
    String fault = "";
    ProteinRegionDemandSupply.Settings settings(boolean online) {
        return new ProteinRegionDemandSupply.Settings(root, "2026_03", "110.0", "0", online, 2, 12, 10, 2, 1_000_000);
    }
    ProteinRegionHttpTransport.Response fetch(java.net.URI uri, java.time.Duration timeout, int maximum) throws java.io.IOException {
        calls.incrementAndGet(); String url = uri.toString();
        if (fault.equals("timeout")) throw new java.io.IOException("PROTEIN_REGION_REQUEST_TIMEOUT");
        boolean uni = uri.getHost().equals("rest.uniprot.org");
        var headers = new java.util.HashMap<String,List<String>>();
        if (uni) headers.put("x-uniprot-release", List.of(fault.equals("release") ? "2025_01" : "2026_03"));
        else { headers.put("interpro-version", List.of("110.0")); headers.put("interpro-version-minor", List.of("0")); }
        byte[] body;
        if (url.contains("/search?")) {
            assertEquals("(xref:hgnc-4827) AND (organism_id:9606) AND (reviewed:true)",
                    java.net.URLDecoder.decode(uri.getRawQuery().split("&")[0].substring(6),java.nio.charset.StandardCharsets.UTF_8));
            headers.put("x-total-results", List.of(fault.equals("search-page") ? "2" : "1"));
            body = "P12345\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        } else if (uni) {
            var raw = fixture.raw("P12345");
            raw.putArray("features");
            if (fault.equals("pair")) ((ObjectNode) raw.at("/uniProtKBCrossReferences/1")).put("id", "ENST000001.2");
            if (fault.equals("sequence")) ((ObjectNode)raw.path("sequence")).put("value", "MEPGFL");
            body = mapper.writeValueAsBytes(raw);
        } else if (url.endsWith("/api/")) {
            var doc = mapper.createObjectNode(); var db = doc.putObject("databases");
            db.putObject("uniprot").put("version", "2026_03"); db.putObject("interpro").put("version", "110.0");
            body = mapper.writeValueAsBytes(doc);
        } else if (url.contains("/entry/interpro/")) {
            var doc = mapper.createObjectNode().put("count", 0).putNull("next").putNull("previous"); doc.putArray("results");
            if (fault.equals("entries-page")) doc.put("next", "https://example.invalid/next");
            body = mapper.writeValueAsBytes(doc);
        } else {
            var doc = mapper.createObjectNode(); var meta = doc.putObject("metadata");
            meta.put("accession", "P12345").put("is_fragment", false).put("source_database", "reviewed").put("sequence", "MEPGFK").put("length", 6);
            meta.putObject("source_organism").put("taxId", 9606);
            if (fault.equals("interpro-sequence")) meta.put("sequence", "MEPGFL");
            body = mapper.writeValueAsBytes(doc);
        }
        return new ProteinRegionHttpTransport.Response(fault.equals("404") ? 404 : 200, headers, body);
    }
    @Test void exactCacheSurvivesNewOfflineInstanceAndOtherVariantOfSameProtein() throws Exception {
        var supply = new ProteinRegionDemandSupply(mapper, settings(true), this::fetch);
        var packet = supply.obtain(fixture.model()); assertEquals(5, calls.get());
        var source = new UniProtProteinRegionSupply(mapper, packet.directory(), packet.manifestSha());
        var mapped = source.mapModel(fixture.model(), 2, 3); assertEquals("READY", mapped.path("status").asText());
        var combined = new CombinedProteinRegionSupply(mapper, packet.directory().resolve("combined.json"), packet.combinedSha()).resolve(mapped);
        assertTrue(combined.path("operationalSourceComplete").asBoolean(), combined.toPrettyString());
        assertFalse(combined.path("nonRepeatQualified").asBoolean(true));
        var other = fixture.model().put("unrelatedVariantDisplay", "not part of protein identity");
        assertEquals(packet.directory(), supply.obtain(other).directory());
        var offline = new ProteinRegionDemandSupply(mapper, settings(false), (u,t,b) -> { throw new AssertionError("No network on cache hit"); });
        assertEquals(packet.directory(), offline.obtain(fixture.model()).directory());
        assertEquals(0, offline.counts().path("httpRequests").asInt()); assertEquals(5, calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"release","search-page","pair","sequence","entries-page","interpro-sequence","404","timeout"})
    void incompleteConflictingOrUnavailableSourceNeverPublishesAndSameQueryDoesNotRetry(String fault) throws Exception {
        this.fault = fault; var supply = new ProteinRegionDemandSupply(mapper, settings(true), this::fetch);
        var first = assertThrows(IllegalArgumentException.class, () -> supply.obtain(fixture.model()));
        int before = calls.get();
        assertEquals(first.getMessage(), assertThrows(IllegalArgumentException.class, () -> supply.obtain(fixture.model())).getMessage());
        assertEquals(before, calls.get()); assertEquals(0, supply.counts().path("published").asInt());
        try (var files = Files.list(root)) { assertTrue(files.noneMatch(p -> p.getFileName().toString().matches("[a-f0-9]{64}"))); }
    }
    @Test void corruptedCacheIsRejectedWithoutOnlineReplacement() throws Exception {
        var supply = new ProteinRegionDemandSupply(mapper, settings(true), this::fetch);
        var packet = supply.obtain(fixture.model()); Files.writeString(packet.directory().resolve("protein.json"), "{}");
        assertEquals("PROTEIN_REGION_CACHE_BYTES_CHANGED", assertThrows(IllegalArgumentException.class, () -> supply.obtain(fixture.model())).getMessage());
        assertEquals(5, calls.get());
    }
    @Test void missingSourceFileInManifestAndChangedVersionsCannotBorrowCache() throws Exception {
        var supply = new ProteinRegionDemandSupply(mapper, settings(true), this::fetch); var packet = supply.obtain(fixture.model());
        var next = new ProteinRegionDemandSupply(mapper, new ProteinRegionDemandSupply.Settings(root,"2026_04","110.0","0",false,2,12,10,2,1_000_000), this::fetch);
        assertEquals("PROTEIN_REGION_DEMAND_NETWORK_DISABLED", assertThrows(IllegalArgumentException.class, () -> next.obtain(fixture.model())).getMessage());
        var ready = (ObjectNode)mapper.readTree(Files.readAllBytes(packet.directory().resolve("ready.json")));
        ((ObjectNode)ready.path("files")).remove("entries.json"); mapper.writeValue(packet.directory().resolve("ready.json").toFile(),ready);
        assertEquals("PROTEIN_REGION_CACHE_MANIFEST_MISSING", assertThrows(IllegalArgumentException.class, () -> supply.obtain(fixture.model())).getMessage());
    }
    @Test void requestAndByteLimitsApplyAcrossTheWholeProteinAcquisition() throws Exception {
        var supply = new ProteinRegionDemandSupply(mapper, new ProteinRegionDemandSupply.Settings(root,"2026_03","110.0","0",true,2,2,10,2,1024), this::fetch);
        assertEquals("PROTEIN_REGION_DEMAND_REQUEST_LIMIT", assertThrows(IllegalArgumentException.class, () -> supply.obtain(fixture.model())).getMessage());
        assertEquals(2,calls.get());
    }
    @Test void localSuccessOrCorruptionNeverStartsAcquisition() throws Exception {
        var supply = new ProteinRegionDemandSupply(mapper,settings(true),this::fetch);
        // Local branches must not even inspect the unused material.
        var data = new ProteinLengthChangeMaterialSupplyTest(); data.root = root.resolve("material"); Files.createDirectories(data.root);
        data.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var material = data.prebuilt().resolveProteinLengthChange(data.data.context,data.data.candidate);
        var ready = mapper.createObjectNode().put("status","READY");
        ready.putObject("source").put("release","2026_03");
        ready.putObject("sources").put("uniprotRelease","2026_03").put("interproRelease","110.0").put("interproMinor","0");
        assertEquals("LOCAL_SNAPSHOT",supply.resolve(material,ready,ready).acquisition().path("origin").asText());
        var broken = mapper.createObjectNode().put("status","UNAVAILABLE").put("reasonCode","PROTEIN_REGION_BYTES_CHANGED");
        assertEquals(broken,supply.resolve(material,broken,ready).annotations()); assertEquals(0,calls.get());
        ((ObjectNode)ready.path("source")).put("release","2025_01");
        assertEquals("PROTEIN_REGION_RELEASE_MISMATCH",supply.resolve(material,ready,ready).annotations().path("reasonCode").asText());
        assertEquals(0,calls.get());
    }
    @Test void acquiredFactsActuallyReachTheExistingOperationalPm4Policy() throws Exception {
        var data = new ProteinLengthChangeMaterialSupplyTest(); data.root = root.resolve("material"); Files.createDirectories(data.root);
        data.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var base = GeneralPm4TestSupport.create(mapper,root.resolve("base"),data.data.model,data::read,false,false,false);
        var guard = (PredictionSpecializationGuard)org.springframework.test.util.ReflectionTestUtils.getField(java.util.Objects.requireNonNull(base),"specializations");
        var supply = new ProteinRegionDemandSupply(mapper,settings(true),this::fetch);
        var runtime = new GeneralPm4Runtime(mapper,data.prebuilt(),null,null,new Pm4ProteinRegionAssessment(mapper),
                java.util.Objects.requireNonNull(guard),Pm4OperationalNonRepeatPolicy.MODE,supply);
        var result = runtime.evaluate(data.data.context,data.data.candidate,mapper.createObjectNode());
        assertEquals("MET",result.path("status").asText(),result.toPrettyString());
        assertEquals("NETWORK",result.at("/regionSupply/origin").asText());
        assertEquals("OPERATIONAL_INFERENCE",result.at("/publicSummary/qualificationBasis").asText());
        assertEquals("CACHE",runtime.evaluate(data.data.context,data.data.candidate,mapper.createObjectNode()).at("/regionSupply/origin").asText());
        assertEquals(5,calls.get());
    }
    @Test void bp3ReparsesTheSamePm4CacheWithoutAnotherTransportAndDoesNotBorrowPm4Inference() throws Exception {
        var data = new ProteinLengthChangeMaterialSupplyTest(); data.root = Files.createDirectory(root.resolve("material"));
        data.setup(1,105,"A","AGCT","6_7insGCT","Glu2_Pro3insAla");
        var material = data.prebuilt().resolveProteinLengthChange(data.data.context, data.data.candidate);
        var first = new ProteinRegionDemandSupply(mapper,settings(true),this::fetch);
        var missing = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "PROTEIN_REGION_LOCAL_NOT_CONFIGURED");
        var extra = mapper.createObjectNode().put("status", "UNAVAILABLE").put("reasonCode", "PM4_COMBINED_RESOURCE_NOT_CONFIGURED");
        var pm4 = first.resolve(material, missing, extra); assertEquals("READY", pm4.combined().path("status").asText());
        assertEquals(5, calls.get()); assertFalse(pm4.combined().has("bp3ProjectionVersion"));
        var offline = new ProteinRegionDemandSupply(mapper, settings(false), (u,t,b) -> { throw new AssertionError("BP3 must reuse cache"); });
        var bp3 = offline.resolveForBp3(material, missing, extra);
        assertEquals("CACHE", bp3.acquisition().path("origin").asText()); assertEquals(0, offline.counts().path("httpRequests").asInt());
        assertEquals(pm4.annotations(), bp3.annotations()); assertEquals(pm4.combined().path("sha256"), bp3.combined().path("sha256"));
        assertTrue(bp3.combined().has("functionalLeads"));
        var assessment = new Bp3ProteinRegionAssessment(mapper).assess(data.data.context, java.util.Objects.requireNonNull(material.model()), bp3.annotations(), bp3.combined());
        assertEquals("BP3_REPEAT_FUNCTION_KNOWLEDGE_REQUIRED", assessment.path("reasonCode").asText());
        assertEquals("UNKNOWN", assessment.path("regionState").asText());
    }
    @Test void rateLimitStopsOtherProteinsAndAnotherSessionWithoutRequests() throws Exception {
        var field = ProteinRegionDemandSupply.class.getDeclaredField("COOLDOWN_UNTIL"); field.setAccessible(true);
        var clock = (java.util.concurrent.atomic.AtomicLong)field.get(null); long prior = clock.get();
        try {
            var supply = new ProteinRegionDemandSupply(mapper,settings(true),(u,t,b)-> {
                calls.incrementAndGet(); return new ProteinRegionHttpTransport.Response(429,Map.of("retry-after",List.of("120")),new byte[0]);
            });
            assertEquals("PROTEIN_REGION_HTTP_429",assertThrows(IllegalArgumentException.class,()->supply.obtain(fixture.model())).getMessage());
            var second = new ProteinRegionDemandSupply(mapper,settings(true),this::fetch);
            assertEquals("PROTEIN_REGION_SERVICE_COOLDOWN",assertThrows(IllegalArgumentException.class,()->second.obtain(fixture.model())).getMessage());
            assertEquals(1,calls.get()); assertTrue(clock.get()>System.currentTimeMillis());
        } finally { clock.set(prior); }
    }

}
