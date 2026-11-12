package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Python CLI -> pinned Java knowledge/bundle readers -> unchanged core. Toy data only. */
@EnabledIfSystemProperty(named = "varvis.pvs1.python", matches = ".+")
public class Pvs1SpliceMaterialAssemblyTest {
    @TempDir Path temporary;
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    final RuntimeEvidenceV2Adapter adapter = new RuntimeEvidenceV2Adapter(mapper);

    ObjectNode write(Path root, String name, JsonNode value) throws Exception {
        byte[] raw = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        Path path = root.resolve(name);
        Files.write(path, raw, StandardOpenOption.CREATE_NEW);
        return mapper.createObjectNode().put("file", path.toString()).put("sha256", CodingTranscriptMaterialSupply.sha(raw));
    }

    @ParameterizedTest @ValueSource(strings = {"rna-nmd", "prediction-escape", "unreviewed", "ambiguous", "qualified-inframe", "qualified-negative", "qualified-topology", "qualified-composed"})
    void assembledMaterialsReachProductionCoreWithOriginalMeaning(String scenario) throws Exception {
        Path root = temporary;
        String artifacts = System.getProperty("varvis.pvs1.assembly.artifacts");
        if (artifacts != null) {
            Files.createDirectories(Path.of(artifacts));
            root = Files.createDirectory(Path.of(artifacts).resolve(scenario));
        }
        boolean escape = "prediction-escape".equals(scenario);
        var fixture = new Pvs1SpliceConsequenceTest();
        boolean qualified=scenario.startsWith("qualified-");
        var data = qualified ? new Pvs1SpliceRuntimeIntegrationTest().data(scenario.substring("qualified-".length()).toUpperCase(java.util.Locale.ROOT),1)
                : fixture.data(1, escape, escape);
        var input = data.input(); var policy = input.policy; var model = input.model; var outcome = data.material();
        if(qualified) {
            String cdsHash=CodingTranscriptMaterialSupply.sha(model.path("cdsSequence").asText());
            model.putObject("sequenceVerification").put("cdsSha256",cdsHash).put("fullSplicedTranscriptSha256",cdsHash);
        }
        byte[] sourceBytes = "TEST_ONLY source: fictional single RNA/predicted deletion and original scoped review.\n".getBytes(StandardCharsets.UTF_8);
        Files.write(root.resolve("source.txt"), sourceBytes, StandardOpenOption.CREATE_NEW);
        var source = ((ObjectNode) policy.path("scientificSources").path(0)).deepCopy()
                .put("sha256", CodingTranscriptMaterialSupply.sha(sourceBytes)).put("relativePath", "source.txt");
        policy.putArray("scientificSources").add(source.deepCopy());
        model.putArray("sourceRecords").add(source.deepCopy());
        String modelSha = adapter.canonicalSha256(model);
        policy.putObject("transcriptModelBinding").put("modelId", model.path("modelId").asText())
                .put("modelVersion", model.path("modelVersion").asText()).put("canonicalSha256", modelSha);
        outcome.put("transcriptModelSha256", modelSha);
        outcome.putArray("sourceRecords").add(source.deepCopy());
        if ("unreviewed".equals(scenario)) outcome.withObject("review").put("status", "UNREVIEWED");
        var candidate = mapper.createObjectNode().put("schemaVersion", "pvs1-truncating-nmd-bundle-candidate-v1");
        candidate.putArray("policies").add(policy.deepCopy());
        candidate.putObject("activation").put("defaultEnabled", false);
        candidate.putObject("runtimeResourceBinding").put("status", "TEST_ONLY");
        var catalog = candidate.putObject("catalog").put("schemaVersion", "acmg-specification-catalog-v1")
                .put("catalogId", "TEST_ONLY_SOURCE_CATALOG").put("catalogVersion", "1")
                .put("searchedAt", "2026-01-01T00:00:00Z").put("searchScope", "TEST_ONLY pinned toy source; no scientific adoption");
        catalog.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY");
        catalog.putArray("sources").add(source.deepCopy());
        var entry = catalog.putArray("entries").addObject().put("entryId", "TEST_ONLY_ENTRY")
                .put("criterionId", TruncatingNmdPvs1Runtime.CRITERION).put("searchStatus", "COMPLETE")
                .put("disposition", "SPECIALIZED_IMPLEMENTED").put("baseRuleRelationship", "TEST_ONLY full source restrictions");
        entry.set("scope", policy.path("scope").deepCopy());
        entry.putObject("review").put("status", "REVIEWED").put("reviewId", "TEST_ONLY");
        entry.putArray("sourceIds").add("synthetic-source");
        entry.putObject("policyBinding").put("policyId", policy.path("policyId").asText())
                .put("policyVersion", policy.path("policyVersion").asText()).put("policySha256", adapter.canonicalSha256(policy));
        candidate.put("catalogSha256", adapter.canonicalSha256(catalog));
        write(root, "model.json", model);
        candidate.putArray("transcriptModelResources").addObject().put("relativePath", "model.json").put("canonicalSha256", modelSha);
        var resources = candidate.putArray("spliceOutcomeResources");
        var outcomes = mapper.createArrayNode().add(outcome.deepCopy());
        if ("ambiguous".equals(scenario)) outcomes.add(outcome.deepCopy().put("outcomeId", "TEST_ONLY_SECOND_OUTCOME"));
        for (int i = 0; i < outcomes.size(); i++) {
            String name = "outcome-" + i + ".json";
            var ref = write(root, name, outcomes.get(i));
            resources.addObject().put("relativePath", name).put("sha256", ref.path("sha256").asText())
                    .put("canonicalSha256", adapter.canonicalSha256(outcomes.get(i)));
        }
        var facts = Pvs1KnowledgeCatalogTest.facts(mapper, List.of(policy));
        var factsRef = write(root, "mechanisms.json", facts);
        var materialsConfig = mapper.createObjectNode().put("schemaVersion", "pvs1-material-plan-input-v1");
        materialsConfig.set("mechanisms", factsRef);
        materialsConfig.putArray("candidates").add(write(root, "candidate.json", candidate).put("resourceRoot", root.toString()));
        var config = mapper.createObjectNode().put("schemaVersion", "pvs1-runtime-assembly-input-v1");
        config.set("materialsConfig", write(root, "materials-config.json", materialsConfig));
        config.set("resourceProfile", write(root, "profile.json", fixture.old.resourceProfile()));
        config.putObject("authorization").put("bindingId", "TEST_ONLY_PYTHON_JAVA")
                .put("boundAt", "2026-01-01T00:00:00Z").put("validUntil", "2099-01-01T00:00:00Z")
                .put("operator", "TEST_ONLY mechanical binding").put("purpose", "PROJECT_RESEARCH")
                .putArray("allowedDataClasses").add("SYNTHETIC");
        if(qualified) {
            config.put("schemaVersion","pvs1-source-qualified-assembly-input-v1");config.remove("authorization");
            config.putObject("release").put("bindingId","TEST_ONLY_QUALIFIED_SPLICE").put("assembledAt","2026-01-01T00:00:00Z");
            var qualification=config.putObject("sourceQualification").put("policyId","TEST_ONLY_UNCHANGED_SOURCES").put("version","1.0.0")
                    .put("purpose","PROJECT_RESEARCH").put("clinicalAcceptance",false).put("createsExpertReview",false);
            qualification.putArray("allowedDataClasses").add("SYNTHETIC").add("PRIVACY_BOUNDED_RUNTIME");
        }
        var configRef = write(root, "config.json", config);
        Path script = Path.of("../scripts/pvs1/assemble_runtime_bundle.py").toAbsolutePath().normalize();
        var command = List.of(System.getProperty("varvis.pvs1.python"), script.toString(), "--config",
                configRef.path("file").asText(), "--config-sha256", configRef.path("sha256").asText(),
                "--output-directory", root.resolve("assembled").toString());
        write(root, "command.json", mapper.valueToTree(command));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(root.resolve("python.log").toFile()).start();
        try { assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Bounded local Python preparation timed out"); }
        finally { if (process.isAlive()) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); } }
        assertEquals(0, process.exitValue(), Files.readString(root.resolve("python.log")));
        Path bundleFile = root.resolve("assembled/bundle.json"), useFile = root.resolve("assembled/knowledge-adoption.json");
        var bundleDoc = (ObjectNode) mapper.readTree(Files.readAllBytes(bundleFile));
        assertEquals(policy, bundleDoc.path("policies").path(0));
        assertEquals(outcomes, bundleDoc.at("/materials/pvs1SpliceOutcomes"));
        var knowledge = Pvs1KnowledgeCatalog.load(mapper, root.resolve("mechanisms.json"), factsRef.path("sha256").asText(),
                useFile, CodingTranscriptMaterialSupply.sha(Files.readAllBytes(useFile)));
        var assessment = knowledge.assess(input.context, policy);
        write(root, "knowledge-assessment.json", assessment);
        assertEquals("READY", assessment.path("status").asText(), assessment.toPrettyString());
        ReviewedRuleBundle bundle;
        if(qualified) {
            var supply=new Pvs1TranscriptMaterialSupplyTest() {
                @Override CodingTranscriptMaterialSupply.ReferenceSpan readReference(String contig,int start,int length) {
                    reads.incrementAndGet();var sequence=new StringBuilder();var window=outcome.path("referenceWindow");
                    for(int g=start;g<start+length;g++) {
                        int c=CodingTranscriptMapping.cdsPosition(model,g);
                        if(c>0)sequence.append(CodingTranscriptMapping.orientAllele(model,model.path("cdsSequence").asText().substring(c-1,c)));
                        else {
                            int offset=g-window.path("start").asInt();String witness=window.path("sequence").asText();
                            if(offset<0||offset>=witness.length())throw new AssertionError("Synthetic reference read outside fixed CDS or declared window");
                            sequence.append(witness.charAt(offset));
                        }
                    }
                    return new CodingTranscriptMaterialSupply.ReferenceSpan(sequence.toString(),mapper.createObjectNode()
                            .put("version",referenceVersion).put("expectedSha256",input.context.at("/variant/normalization/referenceSequenceSha256").asText()));
                }
            };supply.root=Files.createDirectory(root.resolve("current-reference"));
            supply.data=input;supply.referenceModel=model.deepCopy();
            bundle=ReviewedRuleBundle.loadPvs1Selected(mapper,bundleFile,CodingTranscriptMaterialSupply.sha(Files.readAllBytes(bundleFile)),
                    adapter.canonicalSha256(bundleDoc),new Pvs1TranscriptMaterialSupply(mapper,supply.write()),knowledge);
        } else bundle=ReviewedRuleBundle.loadPvs1(mapper, bundleFile, CodingTranscriptMaterialSupply.sha(Files.readAllBytes(bundleFile)), adapter.canonicalSha256(bundleDoc));
        var result = bundle.evaluate(TruncatingNmdPvs1Runtime.CRITERION, input.context, input.candidate);
        write(root, "result.json", result);
        var status = fixture.status(result);
        if(qualified) {
            assertEquals("READY",result.at("/knowledgeAssessment/status").asText(),result::toPrettyString);
            assertEquals(scenario.equals("qualified-negative")?"NOT_MET":"MET",status.path("status").asText(),result::toPrettyString);
            assertEquals(scenario.equals("qualified-negative")?"NONE":scenario.equals("qualified-inframe")?"MODERATE":"VERY_STRONG",result.at("/evaluation/appliedStrength").asText());
            assertEquals(outcome,result.path("spliceConsequenceMaterial"));
            assertEquals(Pvs1SelectedBatch.SPLICE_VERSION,Pvs1SelectedBatch.publicSummary(mapper,result).path("methodVersion").asText());
        } else if ("unreviewed".equals(scenario) || "ambiguous".equals(scenario)) {
            assertEquals("NOT_EVALUATED", status.path("status").asText(), result.toPrettyString());
            assertEquals("unreviewed".equals(scenario) ? "PVS1_SPLICE_OUTCOME_NOT_REVIEWED" : "PVS1_SPLICE_OUTCOMES_AMBIGUOUS", status.path("reasonCode").asText());
        } else {
            assertEquals("MET", status.path("status").asText(), result.toPrettyString());
            assertEquals(escape ? "STRONG" : "VERY_STRONG", result.at("/evaluation/appliedStrength").asText());
            assertEquals(escape ? 12 : 386, result.at("/resolvedInput/distanceToLastJunction").asInt());
            assertEquals(outcome, result.path("spliceConsequenceMaterial"));
        }
    }
}
