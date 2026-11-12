package org.nbscn.varvis.variantanalysis.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.nbscn.varvis.variantanalysis.provider.DeNovoEvidenceCalculator.*;

/** Actual owned Python replay plus Java proof intake. Sources/reference/knowledge are synthetic.
 * Mapper representation may differ; numeric values, JSON types and ordered observations may not. */
class DeNovoCoreNumericProofTest {
    @TempDir(factory = DeNovoCurrentTestDirectory.class) Path temporary;
    private final ObjectMapper ordinary = new ObjectMapper();
    private final ObjectMapper spring = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private byte[] replay;
    private DeNovoApplicabilityPolicy policy;
    private Group group;
    private JsonNode allele;

    @BeforeEach void source() throws Exception {
        Path python = Path.of(System.getProperty("denovo.test.python", "missing-python"));
        Path scripts = Path.of(System.getProperty("denovo.test.scripts", "../scripts/denovo")).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(python), "Explicit installed Python required; skipped is not a replay pass");
        var target = ordinary.createObjectNode().put("hgncId", "HGNC:1748").put("geneSymbol", "CDH1")
                .put("diseaseId", "MONDO:0007648").put("inheritance", "AD");
        allele = target.putObject("allele").put("assembly", "GRCh38").put("contig", "11").put("position", 2)
                .put("reference", "T").put("alternate", "A");
        Path targetPath = temporary.resolve("target.json"); Files.write(targetPath, ordinary.writeValueAsBytes(target));
        Path output = temporary.resolve("originals");
        var process = new SystemExternalProcessRunner().run(List.of(python.toString(), "-E", "-s", "-B", "-c",
                "from test_current_business_fixture import write_java_fixture; import sys; write_java_fixture(sys.argv[1],sys.argv[2],kind='JSON',scenario='TISSUE_BOTH')",
                output.toString(), targetPath.toString()), scripts, Duration.ofSeconds(60));
        assertTrue(process.isSuccess(), process.getOutput());
        replay = Files.readAllBytes(output.resolve("java-round4-fixture.json"));
        JsonNode source = ordinary.readTree(replay);
        assertEquals("READY_FOR_EVALUATION", source.at("/core/status").asText());
        assertEquals("NOT_RUN", source.at("/core/criterionDecision").asText());
        var config = ordinary.readTree(Files.readAllBytes(output.resolve(source.at("/coreContext/path").asText())));
        byte[] policyRaw = Files.readAllBytes(output.resolve(config.at("/contexts/0/policy/path").asText()));
        policy = DeNovoApplicabilityPolicy.load(policyRaw, config.at("/contexts/0/policy/sha256").asText(),
                Map.of("paper", source.at("/policySources/paper").asText().getBytes(StandardCharsets.UTF_8)),
                DeNovoApplicabilityPolicy.Use.SYNTHETIC_VALIDATION);
        group = new Group("GRCh38:11:2:T:A", "HGNC:1748", "MONDO:0007648", "AD");
    }

    private DeNovoCoreFacts.Read read(ObjectNode core) {
        return DeNovoCoreFacts.read(core, allele, group, policy);
    }

    private ObjectNode core(ObjectMapper mapper) throws Exception { return (ObjectNode) mapper.readTree(replay).path("core"); }

    private void rejected(ObjectNode changed, String description) {
        var read = read(changed);
        assertNull(read.facts().special(), description);
        assertTrue(read.issues().contains("DENOVO_CORE_SPECIAL_EVENT_PROOF_INVALID"), description + read.issues());
    }

    @Test void exactPublishedDecimalsRemainQualifiedUnderBothDefaultAndApplicationMappers() throws Exception {
        var doubleCore = core(ordinary); var decimalCore = core(spring);
        assertTrue(doubleCore.at("/specialEvent/tissues/0/limit").isDouble());
        assertTrue(decimalCore.at("/specialEvent/tissues/0/limit").isBigDecimal());
        assertEquals(0, doubleCore.at("/specialEvent/tissues/0/limit").decimalValue()
                .compareTo(decimalCore.at("/specialEvent/tissues/0/limit").decimalValue()));
        for (var value : List.of(doubleCore, decimalCore)) {
            var result = read(value);
            assertTrue(result.issues().isEmpty(), result.issues().toString());
            var special = result.facts().special(); assertNotNull(special);
            assertEquals("POSTZYGOTIC", special.kind());
            assertEquals(2, special.tissueStrength(special.eventFacts(result.facts())), "Actual source conditions establish C1+C2 Strong");
            assertEquals(0, special.snapshot().at("/tissues/0/limit").decimalValue().compareTo(new java.math.BigDecimal("0.01")));
        }
    }

    @Test void changedNumbersTextCoercionAndOrderedTissueChangesCannotUseTheOriginalProof() throws Exception {
        for (var mapper : List.of(ordinary, spring)) for (String fault : List.of("fact-number", "event-and-fact-number", "almost-same-decimal", "numeric-text", "array-order")) {
            var changed = core(mapper); var event = (ObjectNode) changed.path("specialEvent");
            var fact = (ObjectNode) changed.path("fieldProofs").path("/extensions/specialEvent").path("fact").path("value");
            switch (fault) {
                case "fact-number" -> ((ObjectNode) fact.at("/tissues/0")).put("limit", 0.02);
                case "event-and-fact-number" -> { ((ObjectNode) event.at("/tissues/0")).put("limit", 0.02);
                    ((ObjectNode) fact.at("/tissues/0")).put("limit", 0.02); }
                case "almost-same-decimal" -> {
                    var distinct = new java.math.BigDecimal("0.0100000000000000000000000000001");
                    ((ObjectNode) event.at("/tissues/0")).put("limit", distinct);
                    ((ObjectNode) fact.at("/tissues/0")).put("limit", distinct);
                }
                case "numeric-text" -> { ((ObjectNode) event.at("/tissues/0")).put("limit", "0.01");
                    ((ObjectNode) fact.at("/tissues/0")).put("limit", "0.01"); }
                case "array-order" -> {
                    for (var object : List.of(event, fact)) {
                        var array = (ArrayNode) object.path("tissues"); var first = array.get(0).deepCopy();
                        array.set(0, array.get(1).deepCopy()); array.set(1, first);
                    }
                }
                default -> fail(fault);
            }
            rejected(changed, fault + " under " + mapper.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS));
        }
    }

    @Test void alteredCanonicalBytesSourceIdentityAndCitationTextRemainRejectedWithoutResigning() throws Exception {
        for (String fault : List.of("canonical-byte-change", "source-pin", "citation-number")) {
            var changed = core(spring); var proof = (ObjectNode) changed.path("fieldProofs").path("/extensions/specialEvent");
            switch (fault) {
                case "canonical-byte-change" -> proof.put("factCanonicalUtf8", proof.path("factCanonicalUtf8").asText() + " ");
                case "source-pin" -> ((ObjectNode) changed.path("specialEventSource")).put("sourceSha256", "0".repeat(64));
                case "citation-number" -> {
                    for (var citations : List.of(proof.path("fact").path("citations"), proof.path("check").path("basis")))
                        for (var citation : citations) if ("special-event-original".equals(citation.path("sourceId").asText())) {
                            var quoted = (ObjectNode) ordinary.readTree(citation.path("quote").asText());
                            ((ObjectNode) quoted.at("/tissues/0")).put("limit", 0.02);
                            ((ObjectNode) citation).put("quote", ordinary.writeValueAsString(quoted));
                        }
                }
                default -> fail(fault);
            }
            rejected(changed, fault);
        }
    }
}
