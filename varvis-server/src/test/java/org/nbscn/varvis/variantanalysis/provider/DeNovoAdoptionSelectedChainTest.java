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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Actual Python reader and registered original replay; invented families/knowledge, no clinical approval. */
class DeNovoAdoptionSelectedChainTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private record Prepared(DeNovoBatchFixture materials, JsonNode source, Path python, Path scripts, Path cache) { }

    private Prepared prepare(String kind, String scenario) throws Exception {
        Path python = Path.of(System.getProperty("denovo.test.python", "missing-python"));
        Path scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit existing Python required for normal source replay");
        Path output = temporary.resolve("m");
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B", "-c",
                "from test_round4_adoption_fixture import write_java_fixture; import sys; write_java_fixture(sys.argv[1], kind=sys.argv[2], scenario=sys.argv[3])",
                output.toString(), kind, scenario), scripts, Duration.ofSeconds(45));
        assertTrue(process.isSuccess(), process.getOutput());
        JsonNode source = mapper.readTree(Files.readAllBytes(output.resolve("java-round4-fixture.json")));
        assertEquals("SYNTHETIC_NORMAL_REGISTERED_" + kind, source.path("sourceClass").asText());
        assertEquals("NOT_RUN", source.path("criterionDecision").asText());
        JsonNode config = mapper.readTree(Files.readAllBytes(output.resolve(source.at("/coreContext/path").asText())));
        assertFalse(config.has("specialEventSources"), "No per-case special-event setup supplies the event");

        var f = new DeNovoBatchFixture(output);
        f.phenotype.removeAll().setAll((ObjectNode) source.path("policy"));
        JsonNode scope = source.at("/policy/scope"), allele = source.at("/core/values").path("/context/allele");
        assertEquals("AD", scope.path("inheritance").asText());
        boolean expert = !scenario.equals("GENERAL_ASSUMED");
        assertEquals(expert ? "APPLICABLE_IMPLEMENTED" : "GENERAL_ALLOWED", f.phenotype.path("expertPolicyStatus").asText());
        var target = (ObjectNode) f.report.path("target"); target.removeAll().setAll((ObjectNode) allele);
        target.put("hgncId", scope.path("hgncId").asText());
        for (var entry : f.catalog.path("entries")) {
            if (expert) ((ObjectNode) entry).put("disposition", "SPECIALIZED_IMPLEMENTED");
            setScope((ObjectNode) entry.path("scope"), scope, allele);
        }
        for (var policy : f.selectorPolicies.path("policies")) setScope((ObjectNode) policy.path("scope"), scope, allele);
        byte[] knowledge = source.at("/policySources/paper").asText().getBytes(StandardCharsets.UTF_8);
        Files.write(f.root.resolve("source.txt"), knowledge);
        String knowledgeSha = DeNovoBatchFixture.sha(knowledge);
        ((ObjectNode) f.manifest.at("/sources/0")).put("sha256", knowledgeSha);
        ((ObjectNode) f.catalog.at("/sources/0")).put("sha256", knowledgeSha);
        f.manifest.withArray("sources").addObject().put("sourceId", "paper").put("path", "source.txt").put("sha256", knowledgeSha);
        f.manifest.withArray("sources").addObject().put("sourceId", "registered-paper").put("path", "synthetic-literature.xml")
                .put("sha256", source.path("sourceSha256").asText());
        f.save();
        assertEquals(source.at("/core/policySha256"), f.manifest.at("/phenotypePolicies/0/sha256"));
        f.manifest.putArray("reports");
        f.manifest.putArray("literature").addObject().put("sourceId", "registered-paper").put("pmcid", source.path("pmcid").asText()).putArray("alleles");
        f.writeManifest();
        assertFalse(f.report.has("specialEvent"));
        return new Prepared(f, source, python, scripts, temporary.resolve("c"));
    }

    private static void setScope(ObjectNode target, JsonNode scope, JsonNode allele) {
        target.put("hgncId", scope.path("hgncId").asText()).put("mondoId", scope.path("diseaseId").asText())
                .put("moi", "AUTOSOMAL_DOMINANT").put("assembly", allele.path("assembly").asText());
    }

    private ObjectNode context(Prepared prepared) {
        var context = prepared.materials().context();
        ((ObjectNode) context.path("gene")).put("hgncId", prepared.source().at("/policy/scope/hgncId").asText()).put("symbol", "EXAMPLE");
        ((ObjectNode) context.path("disease")).put("mondoId", prepared.source().at("/policy/scope/diseaseId").asText());
        var adapter = new RuntimeEvidenceV2Adapter(mapper);
        context.put("contextId", adapter.canonicalVersionedContextId(context)); adapter.validateVersionedContext(context);
        return context;
    }

    private DeNovoLiteratureReader reader(Prepared prepared) throws Exception {
        var f = prepared.materials(); Path index = f.root.resolve("model-artifacts.json");
        return new DeNovoLiteratureReader(mapper, prepared.python(), prepared.scripts(), prepared.cache(), index,
                DeNovoBatchFixture.sha(Files.readAllBytes(index)))
                .withCoreContext(f.root, f.root.resolve(prepared.source().at("/coreContext/path").asText()), prepared.source().at("/coreContext/sha256").asText());
    }

    private DeNovoSelectedBatch load(Prepared prepared, DeNovoLiteratureReader reader) throws Exception {
        var f = prepared.materials(); Path manifest = f.root.resolve("manifest.json");
        return DeNovoSelectedBatch.load(mapper, manifest, DeNovoBatchFixture.sha(Files.readAllBytes(manifest)),
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION, reader);
    }

    @ParameterizedTest
    @CsvSource({"JSON,TISSUE_BOTH,STRONG", "JATS,TISSUE_BOTH,STRONG", "JSON,TISSUE_C1,MODERATE",
            "JATS,TISSUE_C1,MODERATE", "JSON,TISSUE_C2,MODERATE", "JATS,TISSUE_C2,MODERATE",
            "JSON,COMBINED_ASSUMED,SUPPORTING", "JATS,COMBINED_ASSUMED,SUPPORTING"})
    void sourceQualifiedMethodsPublishActualAdoptionWithoutChangingParentage(String kind, String scenario, String strength) throws Exception {
        var prepared = prepare(kind, scenario); var firstReader = reader(prepared); var module = load(prepared, firstReader);
        var context = context(prepared); var all = module.evaluateBound(context, mapper.createObjectNode());
        assertEquals(1, firstReader.executions());
        assertEquals("MET", all.at("/criteria/PS2/evaluationResult/status").asText(), all.toPrettyString());
        assertEquals(strength, all.at("/criteria/PS2/appliedStrength").asText());
        assertEquals("NOT_EVALUATED", all.at("/criteria/PM6/evaluationResult/status").asText());
        assertEquals("DENOVO_SHARED_EVENT_ASSIGNED_TO_PS2", all.at("/criteria/PM6/evaluationResult/reasonCode").asText());
        boolean combined = scenario.startsWith("COMBINED");
        assertEquals(combined ? "SVI_POINTS" : "EXPERT_FIXED_STRENGTH", all.at("/qualifiedResult/result/calculationMethod").asText());
        assertEquals(combined ? 2 : 0, all.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        assertEquals(!combined && !scenario.equals("TISSUE_C2"), all.at("/qualifiedResult/result/events/0/confirmedParentage").asBoolean());
        assertEquals(1, all.at("/qualifiedResult/result/events").size());
        assertEquals("DENOVO_SPECIALIZED_REQUIREMENTS_NOT_IMPLEMENTED", all.at("/specificationSelections/0/reasonCode").asText());
        assertEquals("SELECTED", all.at("/specializedPolicyAdoptions/0/status").asText());
        verifyOriginals(prepared, module, all, true);
        assertProof(all, prepared, true);
        assertEquals("COMPLETE", all.at("/publicSummary/adoptionProof/projectionStatus").asText());
        assertConditionSourceMappings(all, prepared);
        var proofEvent = all.at("/publicSummary/adoptionProof/events/0");
        assertEquals("QUALIFIED_CONTRIBUTION", proofEvent.path("status").asText());
        String rawSourceSha = prepared.source().at("/core/specialEventSource/discovery/sourceSha256").asText();
        assertTrue(proofEvent.findValuesAsText("sourceSha256").contains(rawSourceSha), "The original observation, not just derived artifact, must remain source-bound");
        if (combined) {
            assertEquals("SOURCE_BOUND_COMBINED_PS2", proofEvent.path("conditionBranch").asText());
            assertEquals("ASSUMED_NOT_TESTED", proofEvent.at("/combined/maternity").asText());
            assertEquals("ASSUMED_NOT_TESTED", proofEvent.at("/combined/paternity").asText());
            assertFalse(proofEvent.at("/combined/confirmedParentage").asBoolean());
        } else {
            assertEquals(scenario.equals("TISSUE_BOTH") ? "C1_AND_C2" : scenario.equals("TISSUE_C1") ? "C1_ONLY"
                    : "PARENTS_UNAVAILABLE_AND_C2", proofEvent.path("conditionBranch").asText());
            assertEquals(scenario.equals("TISSUE_C2") ? "NOT_ESTABLISHED" : "MET", proofEvent.at("/c1/status").asText());
            assertEquals(scenario.equals("TISSUE_C1") ? "NOT_ESTABLISHED" : "MET", proofEvent.at("/c2/status").asText());
            assertEquals(strength, proofEvent.path("adoptedStrength").asText());
            if (!scenario.equals("TISSUE_C1")) assertEquals(2, proofEvent.at("/c2/observations").size());
        }

        var samples = mapper.createObjectNode().put("evidenceLevel", "SYNTHETIC_ORIGINAL_NORMAL_PYTHON_JAVA_REPLAY")
                .put("clinicalApproval", "NONE").put("scenario", scenario).put("sourceFormat", kind);
        samples.set("all", all);
        for (String code : List.of("PS2", "PM6")) {
            var cachedReader = reader(prepared); var cached = load(prepared, cachedReader);
            var one = cached.evaluateBound(context, mapper.createObjectNode(), List.of("ACMG_AMP_2015:" + code));
            assertEquals(0, cachedReader.executions(), "Shared source facts must come from source-bound cache");
            assertEquals(all.path("qualifiedResult"), one.path("qualifiedResult"));
            assertEquals(all.at("/criteria/" + code), one.at("/criteria/" + code));
            assertEquals(1, one.path("criteria").size());
            assertEquals(all.at("/publicSummary/adoptionProof"), one.at("/publicSummary/adoptionProof"));
            samples.set(code + "Only", one);
        }
        saveActualSample(kind + "-" + scenario, samples);
    }

    @ParameterizedTest
    @CsvSource({"JSON,COMBINED_EXCLUDED,NOT_EVALUATED", "JATS,COMBINED_EXCLUDED,NOT_EVALUATED", "JSON,TISSUE_CROSS_TRIO,NOT_EVALUATED",
            "JSON,TISSUE_QUALITY,NOT_EVALUATED", "JATS,TISSUE_QUALITY,NOT_EVALUATED", "JSON,TISSUE_ORDINARY_PARENT_PRESENT,NOT_EVALUATED",
            "JSON,TISSUE_DUPLICATE_RECORD,NOT_EVALUATED", "JSON,TISSUE_C2_NO_DIFFERENTIAL,NOT_MET"})
    void rejectedSourceFactsDoNotPublishAnAdoptedExpertProof(String kind, String scenario, String expectedStatus) throws Exception {
        var prepared = prepare(kind, scenario); var firstReader = reader(prepared); var module = load(prepared, firstReader);
        var all = module.evaluateBound(context(prepared), mapper.createObjectNode());
        assertEquals(1, firstReader.executions());
        assertEquals(expectedStatus, all.at("/qualifiedResult/result/status").asText(), all.toPrettyString());
        assertEquals("NONE", all.at("/criteria/PS2/appliedStrength").asText());
        assertEquals(0, all.at("/qualifiedResult/result/effectiveQuarterPoints").asInt());
        assertProof(all, prepared, false);
        if (scenario.equals("COMBINED_EXCLUDED")) assertTrue(all.toString().contains("DENOVO_PARENTAGE_EXCLUDED"));
        if (scenario.equals("TISSUE_CROSS_TRIO")) assertTrue(all.toString().contains("DENOVO_EVENT_PERSON_TRIO_CONFLICT"));
        if (scenario.equals("TISSUE_QUALITY")) assertTrue(all.toString().contains("DENOVO_TARGET_TEST_QUALITY_FAILED"));
        if (scenario.equals("TISSUE_ORDINARY_PARENT_PRESENT")) assertTrue(all.toString().contains("CONFLICT"));
        if (scenario.equals("TISSUE_C2_NO_DIFFERENTIAL")) {
            var event = all.at("/publicSummary/adoptionProof/events/0");
            assertEquals("NOT_ESTABLISHED", event.at("/c1/status").asText());
            assertEquals("NOT_ESTABLISHED", event.at("/c2/status").asText());
            assertEquals(2, event.at("/c2/observations").size());
            assertEquals(20, event.at("/c2/observations/0/altReads").asInt());
            assertEquals(20, event.at("/c2/observations/1/altReads").asInt());
            assertFalse(event.has("adoptedStrength"));
            assertConditionSourceMappings(all, prepared);
        }
        verifyOriginals(prepared, module, all, !scenario.equals("TISSUE_DUPLICATE_RECORD") && !scenario.equals("TISSUE_ORDINARY_PARENT_PRESENT"));
        var sample = mapper.createObjectNode().put("evidenceLevel", "SYNTHETIC_ORIGINAL_NORMAL_PYTHON_JAVA_REPLAY")
                .put("clinicalApproval", "NONE").put("scenario", scenario).put("sourceFormat", kind);
        sample.set("all", all); saveActualSample(kind + "-" + scenario, sample);
    }

    private void assertProof(JsonNode result, Prepared prepared, boolean adopted) {
        var proof = result.at("/publicSummary/adoptionProof");
        assertEquals("denovo-adoption-proof-v1", proof.path("schemaVersion").asText());
        assertEquals(adopted ? "ADOPTED" : "NOT_ADOPTED", proof.path("status").asText(), proof.toPrettyString());
        assertEquals(prepared.source().at("/policy/specialEvents/mode").asText(), proof.at("/policy/eventMode").asText());
        assertEquals(prepared.source().at("/core/policySha256"), proof.at("/policy/phenotypePolicySha256"));
        if (adopted) assertFalse(proof.path("events").isEmpty());
        assertFalse(proof.toString().contains(DeNovoBatchFixture.PATIENT));
        assertFalse(proof.toString().contains("SYNTHETIC-lesion"));
        assertFalse(proof.toString().contains("SYNTHETIC-comparison"));
        assertFalse(proof.toString().contains(prepared.materials().root.toString()));
        assertTrue(result.at("/publicSummary/calculationBindings").isArray());
        assertTrue(result.at("/publicSummary/specificationSelections").isArray());
        assertTrue(result.at("/publicSummary/specializedPolicyAdoptions").isArray());
    }

    private void assertConditionSourceMappings(JsonNode result, Prepared prepared) {
        JsonNode core = prepared.source().path("core");
        var proof = result.at("/publicSummary/adoptionProof");
        assertEquals("NOT_RUN", core.path("criterionDecision").asText());
        assertEquals("NOT_RUN", result.at("/publicSummary/coreMaterials/0/sourceDiscovery/criterionDecision").asText());
        int originals = 0;
        for (JsonNode array : proof.path("events").findValues("sources")) for (JsonNode source : array) {
            String mapping = source.path("mapping").asText(), locator = source.path("locator").asText();
            if (mapping.equals("RETAINED_FACT")) {
                assertEquals(core.at("/specialEventSource/sourceSha256"), source.path("sourceSha256"));
                assertFalse(core.at(locator).isMissingNode(), "Retained fact locator must resolve: " + locator);
            } else if (mapping.equals("REPLAYED_ORIGINAL_FIELD")) {
                originals++;
                boolean exactField = false, exactBasis = false;
                for (JsonNode record : core.at("/sourceDiscovery/records")) for (JsonNode field : record.path("fields")) {
                    if (!source.path("fieldPath").equals(field.path("path"))
                            || !source.path("sourceSha256").equals(field.path("sourceSha256"))
                            || !source.path("kind").equals(field.path("kind")) || !source.path("locator").equals(field.path("locator"))) continue;
                    exactField = true;
                    for (JsonNode basis : core.path("fieldProofs").path("/extensions/specialEvent").at("/check/basis")) {
                        var pin = core.path("sourcePins").path(basis.path("sourceId").asText());
                        if (source.path("sourceSha256").equals(pin.path("sha256")) && source.path("role").equals(pin.path("role"))
                                && source.path("kind").equals(basis.path("kind")) && source.path("locator").equals(basis.path("locator"))
                                && field.path("quote").equals(basis.path("quote"))) exactBasis = true;
                    }
                }
                assertTrue(exactField && exactBasis, "Every public original locator must match an actually replayed field and source basis: " + source);
            }
        }
        assertTrue(originals > 0, "Condition proof must include actual original fields, not only a derived artifact");
    }

    private void verifyOriginals(Prepared prepared, DeNovoSelectedBatch module, JsonNode result, boolean sourceReady) throws Exception {
        String paperSha = prepared.source().path("sourceSha256").asText();
        assertArrayEquals(Files.readAllBytes(prepared.materials().root.resolve("synthetic-literature.xml")),
                Base64.getDecoder().decode(module.originals().path(paperSha).asText()));
        String resultSha = module.identity().path("literatureSourceResultSha256").asText();
        byte[] replayRaw = Base64.getDecoder().decode(module.originals().path(resultSha).asText());
        assertEquals(resultSha, DeNovoBatchFixture.sha(replayRaw));
        JsonNode facts = mapper.readTree(replayRaw).at("/documents/0/facts"), core = facts.at("/cases/0/coreEvaluation");
        assertEquals("NOT_RUN", core.path("criterionDecision").asText());
        if (sourceReady) assertEquals("READY_FOR_EVALUATION", core.path("status").asText(), core.toPrettyString());
        JsonNode archive = facts.path("coreAuditArchive"); byte[] auditRaw;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(archive.path("payload").asText())))) {
            auditRaw = gzip.readNBytes(16_777_217);
        }
        assertTrue(auditRaw.length <= 16_777_216);
        assertEquals(archive.path("sha256").asText(), DeNovoBatchFixture.sha(auditRaw));
        JsonNode originals = mapper.readTree(auditRaw).path("originalsBySha256");
        for (var pin : core.path("sourcePins")) {
            String sha = pin.path("sha256").asText(); assertTrue(originals.has(sha), sha);
            assertEquals(sha, DeNovoBatchFixture.sha(Base64.getDecoder().decode(originals.path(sha).asText())));
        }
        assertEquals("EXACT", result.at("/publicSummary/coreMaterials/0/selectionMatch").asText());
    }

    @Test void policySourceMutationFailsBeforeAnyExpertExecution() throws Exception {
        var prepared = prepare("JSON", "TISSUE_BOTH");
        Files.writeString(prepared.materials().root.resolve("source.txt"), "SYNTHETIC changed source after configuration pin", StandardCharsets.UTF_8);
        var failure = assertThrows(IllegalArgumentException.class, () -> load(prepared, reader(prepared)));
        assertTrue(failure.getMessage().contains("MISMATCH"), failure.getMessage());
    }

    @Test void assumedParentageWithoutCombinedClauseRemainsPm6InsteadOfInventingPs2Confirmation() throws Exception {
        var prepared = prepare("JSON", "GENERAL_ASSUMED"); var firstReader = reader(prepared); var module = load(prepared, firstReader);
        var context = context(prepared); var all = module.evaluateBound(context, mapper.createObjectNode());
        assertEquals("MET", all.at("/criteria/PM6/evaluationResult/status").asText(), all.toPrettyString());
        assertEquals("SUPPORTING", all.at("/criteria/PM6/appliedStrength").asText());
        assertEquals("NOT_EVALUATED", all.at("/criteria/PS2/evaluationResult/status").asText());
        assertEquals("DENOVO_SHARED_EVENT_ASSIGNED_TO_PM6", all.at("/criteria/PS2/evaluationResult/reasonCode").asText());
        assertFalse(all.at("/qualifiedResult/result/events/0/confirmedParentage").asBoolean());
        assertEquals("NOT_APPLICABLE", all.at("/publicSummary/adoptionProof/status").asText());
        var samples = mapper.createObjectNode().put("evidenceLevel", "SYNTHETIC_ORIGINAL_NORMAL_PYTHON_JAVA_REPLAY")
                .put("clinicalApproval", "NONE").put("scenario", "GENERAL_ASSUMED").put("sourceFormat", "JSON");
        samples.set("all", all);
        for (String code : List.of("PS2", "PM6")) {
            var cachedReader = reader(prepared); var cached = load(prepared, cachedReader);
            var one = cached.evaluateBound(context, mapper.createObjectNode(), List.of("ACMG_AMP_2015:" + code));
            assertEquals(all.path("qualifiedResult"), one.path("qualifiedResult"));
            assertEquals(all.at("/criteria/" + code), one.at("/criteria/" + code));
            assertEquals(1, one.path("criteria").size());
            samples.set(code + "Only", one);
        }
        saveActualSample("JSON-GENERAL_ASSUMED", samples);
    }

    private void saveActualSample(String name, ObjectNode result) throws Exception {
        String output = System.getProperty("denovo.round4.outputDir", "");
        if (output.isBlank()) return;
        Path target = Path.of(output).toAbsolutePath().normalize(); Files.createDirectories(target);
        mapper.writerWithDefaultPrettyPrinter().writeValue(target.resolve(name + ".json").toFile(), result);
    }
}
