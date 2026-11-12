package org.nbscn.varvis.variantanalysis.service;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import org.nbscn.varvis.variantanalysis.config.AcmgBusinessConfig;
import org.nbscn.varvis.variantanalysis.controller.AcmgBusinessController;
import org.nbscn.varvis.variantanalysis.provider.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Support only, not a selected JUnit class. Reuses existing actual publication/H2/HTTP helpers. */
class Pm6BusinessFixture extends Ps2BusinessIntegrationTest {
    @Override void start(String name) throws Exception {
        byte[] raw;
        try (var in = getClass().getResourceAsStream("/pm6-denovo-core/" + name + "-bundle.json")) {
            assertNotNull(in); raw = in.readAllBytes();
        }
        bundle = (ObjectNode) mapper.readTree(raw); material = temporary.resolve("material.json"); Files.write(material, raw);
        env = new MockEnvironment().withProperty("variant-analysis.acmg-business.enabled", "true")
                .withProperty("variant-analysis.acmg-business.acceptance-scope", AcmgBusinessRuleScope.PM6_DEVELOPMENT);
        pins(raw); publish(name); wire();
        Files.write(archive.resolve("original-bundle-raw.json"), raw, StandardOpenOption.CREATE_NEW);
        artifact("original-pins", mapper.createObjectNode().put("rawSha256", sha(raw)).put("canonicalSha256", canonical(bundle)));
    }
    @Override void pins(byte[] raw) throws Exception {
        env.withProperty("variant-analysis.acmg-business.pm6-bundle-file", requireNonNull(material.toString()))
                .withProperty("variant-analysis.acmg-business.pm6-bundle-file-sha256", requireNonNull(sha(raw)))
                .withProperty("variant-analysis.acmg-business.pm6-bundle-sha256", requireNonNull(canonical(bundle)));
    }
    @Override void wire() {
        service = new AcmgBusinessConfig().acmgBusinessService(mapper, jobs, store, sources, properties, env);
        Object value = ReflectionTestUtils.getField(requireNonNull(service), "pm6Preparation"); assertInstanceOf(Pm6DeNovoContextPreparation.class, value);
        assertNull(ReflectionTestUtils.getField(requireNonNull(service), "ps2Preparation"));
        var preparation = spy((Pm6DeNovoContextPreparation) value);
        doAnswer(call -> { loads.incrementAndGet(); return call.callRealMethod(); }).when(preparation).load();
        doAnswer(call -> { prepares.incrementAndGet(); return call.callRealMethod(); }).when(preparation).prepare(any(), any(), any(), any(), any());
        doAnswer(call -> { assertTrue(store.runs(analysisId).stream().anyMatch(r -> "RUNNING".equals(r.status())));
            core.incrementAndGet(); return call.callRealMethod(); }).when(preparation).evaluate(any());
        ReflectionTestUtils.setField(requireNonNull(service), "pm6Preparation", preparation);
        for (String field : supplierFields()) ReflectionTestUtils.setField(requireNonNull(service), requireNonNull(field), (Supplier<Object>) () -> {
            forbidden.incrementAndGet(); throw new AssertionError("FORBIDDEN_SUPPLIER_" + field);
        });
        http = MockMvcBuilders.standaloneSetup(new AcmgBusinessController(service)).build();
    }
    static List<String> supplierFields() { return List.of("runtime", "ba1Runtime", "pm2Runtime", "pvs1Runtime", "missenseRuntime", "functionalRuntime", "pm1Runtime", "hnf1aFrequencyRuntime"); }
    @Override ObjectNode request(ObjectNode options) {
        ObjectNode r = mapper.createObjectNode().put("topN", 1).put("declarationVersion", AcmgBusinessRuleScope.PM6_DECLARATION)
                .put("sampleId", options.path("sampleId").asText()).put("dataClass", "SYNTHETIC"); r.putArray("hypotheses");
        ObjectNode patient = (ObjectNode) bundle.at("/context/patientContext").deepCopy(); patient.remove("affectedStatus"); r.set("patient", patient);
        r.set("pm6Selection", ((ObjectNode) options.path("pm6Selection")).deepCopy().put("confirmed", true)); return r;
    }
    @Override ObjectNode submit(ObjectNode request) throws Exception {
        ObjectNode saved = super.submit(request);
        var record = store.run(analysisId, saved.path("runId").asText()).orElseThrow();
        artifact("stored-run-record", mapper.valueToTree(record)); artifact("calls-after-request", counts());
        return saved;
    }
    static ObjectNode at(JsonNode n, String p) { return (ObjectNode) n.at(p); }
    @Override void replaceMaterial(String resource) throws Exception {
        if (resource.equals("alpha-unknown")) issue(resource, n -> at(n, "/observations/0/facts/clinical/applicability").put("singleTrioScope", "UNKNOWN"));
        else if (resource.equals("alpha-ft-conflict")) issue(resource, n -> {
            at(n, "/observations/0/facts/members/1/detection").put("sampleFormat", "GT:DP:GQ:AD:FT");
            at(n, "/observations/0/facts/records/1").put("rawSampleValue", "0/0:40:60:40,0:LowQual");
        });
        else throw new IllegalArgumentException("Unknown TEST_ONLY derivation " + resource);
    }
    void source(ObjectNode value) throws Exception {
        var projection = value.deepCopy(); projection.remove("sources");
        for (var item : value.path("sources")) {
            String raw = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(projection);
            ((ObjectNode) item).put("rawUtf8", raw).put("rawSha256", sha(raw.getBytes(StandardCharsets.UTF_8))).put("canonicalSha256", canonical(projection));
        }
    }
    /** TEST_ONLY issuance before prepare/load, never production signing or modification of fixed fixtures. */
    void issue(String label, java.util.function.Consumer<ObjectNode> change) throws Exception {
        var before = bundle.deepCopy(); change.accept(bundle);
        var facts = at(bundle, "/observations/0/facts"); var basis = at(bundle, "/observations/0/parentageBasis");
        source(facts); basis.put("factsSha256", canonical(facts)); source(basis);
        var assessment = at(bundle, "/observations/0/assessment");
        assessment.put("factsSha256", canonical(facts)).put("parentageBasisSha256", canonical(basis)).remove("contentSha256");
        assessment.put("contentSha256", canonical(assessment));
        at(bundle, "/policy").put("factsSha256", canonical(facts)).put("parentageBasisSha256", canonical(basis)).put("assessmentSha256", canonical(assessment));
        for (var e : bundle.at("/catalog/entries")) at(e, "/policyBinding").put("policySha256", canonical(bundle.path("policy")));
        bundle.put("catalogSha256", canonical(bundle.path("catalog")));
        artifact(label + "-before", before); saveNew(label, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(bundle));
    }
    void saveNew(String label, byte[] bytes) throws Exception {
        material = temporary.resolve(label + "-material.json"); Files.write(material, bytes, StandardOpenOption.CREATE_NEW);
        Files.write(archive.resolve(label + "-loader-bytes.json"), bytes, StandardOpenOption.CREATE_NEW);
        bundle = (ObjectNode) mapper.readTree(bytes); pins(bytes); wire();
        artifact(label + "-pins", mapper.createObjectNode().put("rawSha256", sha(bytes)).put("canonicalSha256", canonical(bundle)));
    }
    void verifyArtifact(ObjectNode saved) throws Exception {
        var coreValue = saved.at("/candidates/0/attempts/0/pm6"); var artifact = at(saved, "/bundles/0");
        assertEquals(coreValue, artifact.path("core")); assertEquals(bundle, coreValue.path("sourceBundle"));
        byte[] raw = mapper.writeValueAsBytes(coreValue);
        Files.write(archive.resolve("core-" + saved.path("runId").asText() + ".json"), raw, StandardOpenOption.CREATE_NEW);
        assertEquals(sha(raw), artifact.path("coreRawSha256").asText());
        var identity = artifact.deepCopy(); identity.remove(List.of("core", "bundleId"));
        assertEquals("pm6-business-artifact-sha256-" + canonical(identity), artifact.path("bundleId").asText());
        assertEquals(bundle.at("/observations/0/parentageBasis"), artifact.at("/preparation/materialBinding/parentageBasis"));
        assertEquals(canonical(bundle.at("/observations/0/parentageBasis")), artifact.at("/preparation/materialBinding/parentageBasisSha256").asText());
    }
}
