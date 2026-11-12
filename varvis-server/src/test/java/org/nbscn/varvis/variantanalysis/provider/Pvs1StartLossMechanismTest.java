package org.nbscn.varvis.variantanalysis.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class Pvs1StartLossMechanismTest {
    @org.junit.jupiter.api.io.TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode context() {
        var c = mapper.createObjectNode().put("moi", "AUTOSOMAL_RECESSIVE");
        c.putObject("gene").put("hgncId", "HGNC:1").put("symbol", "TEST");
        c.putObject("disease").put("mondoId", "MONDO:0000001");
        c.putObject("transcript").put("accession", "NM_000001").put("version", 1);
        c.putObject("variant").putObject("evidenceQueryAllele").put("assembly", "GRCh38").put("contig", "1")
                .put("position", 100).put("reference", "A").put("alternate", "G");
        return c;
    }
    private ObjectNode record() {
        var r = mapper.createObjectNode().put("recordId", "G2P00001:1").put("sourceRow", 1);
        r.putObject("sourceRecord").put("gene symbol", "TEST").put("hgnc id", "1")
                .put("disease MONDO", "MONDO:0000001").put("allelic requirement", "biallelic_autosomal")
                .put("confidence", "definitive").put("molecular mechanism", "loss of function")
                .put("molecular mechanism support", "evidence").put("molecular mechanism evidence", "123 -> function: biochemical")
                .put("variant consequence", "absent gene product").put("variant types", "frameshift_variant; missense_variant")
                .put("publications", "123; 456").put("date of last review", "2026-01-27 12:40:55+00:00");
        return r;
    }
    private ObjectNode assess(ObjectNode... r) {
        return Pvs1StartLossMechanism.assess(mapper, context(), List.of(r), mapper.createObjectNode());
    }
    @Test void boundCuratedMechanismIsOnlyOnePrerequisiteAndOriginalRestrictionsArePreserved() {
        var r = record(); var out = assess(r);
        assertEquals("CURATED_MECHANISM_SUPPORTED", out.path("status").asText());
        assertFalse(out.path("runtimeEligible").asBoolean(true));
        assertFalse(out.path("transcriptRelevanceEstablished").asBoolean(true));
        assertFalse(out.path("primaryExperimentsReevaluated").asBoolean(true));
        assertEquals(r.path("sourceRecord"), out.at("/findings/0/sourceRecord"));
        ((ObjectNode) out.at("/findings/0/sourceRecord")).put("comments", "changed");
        assertFalse(r.path("sourceRecord").has("comments"));
    }
    @ParameterizedTest @CsvSource(delimiter='|', value={
            "molecular mechanism support|inferred|INFERRED_ONLY",
            "confidence|limited|ASSOCIATION_INSUFFICIENT",
            "variant consequence|altered gene product structure|NULL_SCOPE",
            "variant types|missense_variant|NULL_SCOPE",
            "molecular mechanism evidence|999 -> function: biochemical|REFERENCES",
            "date of last review|unknown|DATE",
            "comments|Only specific missense variants are known|QUALIFIERS",
            "cross cutting modifier|imprinting|QUALIFIERS",
            "gene symbol|OTHER|IDENTITY"})
    void absentProductAndSourceLabelCannotBypassScientificScope(String key, String value, String ignored) {
        var r = record(); ((ObjectNode) r.path("sourceRecord")).put(key, value);
        var out = assess(r);
        assertEquals("UNRESOLVED", out.path("status").asText(), out.toPrettyString());
        assertNotEquals("PVS1_START_LOSS_CURATED_LOF_MECHANISM", out.at("/findings/0/reasonCode").asText());
    }
    @Test void conflictingOrUnresolvedSameBackgroundCannotBeDiscardedForPositiveRecord() {
        var other = record(); ((ObjectNode) other.path("sourceRecord")).put("molecular mechanism", "gain of function");
        assertEquals("CONFLICT", assess(record(), other).path("status").asText());
        ((ObjectNode) other.path("sourceRecord")).put("molecular mechanism", "undetermined");
        assertEquals("UNRESOLVED", assess(record(), other).path("status").asText());
        ((ObjectNode) other.path("sourceRecord")).put("allelic requirement", "monoallelic_autosomal");
        assertEquals("CURATED_MECHANISM_SUPPORTED", assess(record(), other).path("status").asText());
    }
    @Test void nativePinnedCatalogHasCuratedMechanismAndDoesNotPromoteInferredRecord() throws Exception {
        String configured = System.getProperty("pvs1.nativeExpertRoot");
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null);
        Path root = Path.of(configured); var properties = new java.util.Properties();
        try (var reader = java.nio.file.Files.newBufferedReader(root.resolve("local/runtime/bp7-evidence-workbench/evidence-overrides.properties"))) {
            properties.load(reader);
        }
        String prefix = "variant-analysis.acmg-business.";
        var catalog = Pvs1KnowledgeCatalog.load(mapper,
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-source-file")), properties.getProperty(prefix + "pvs1-knowledge-source-sha256"),
                Path.of(properties.getProperty(prefix + "pvs1-knowledge-adoption-file")), properties.getProperty(prefix + "pvs1-knowledge-adoption-sha256"));
        var c = context(); c.withObject("gene").put("hgncId", "HGNC:327").put("symbol", "AGPS");
        c.withObject("disease").put("mondoId", "MONDO:0010823");
        var out = catalog.startLossMechanism(c);
        assertEquals("CURATED_MECHANISM_SUPPORTED", out.path("status").asText(), out.toPrettyString());
        assertEquals("G2P00018:17", out.at("/findings/0/recordId").asText());
        var prepared = mapper.createObjectNode().put("schemaVersion", "pvs1-start-loss-automatic-preparation-v1")
                .put("methodVersion", "1.2.0").put("status", "INCOMPLETE").put("runtimeEligible", false)
                .put("scientificEvaluationPerformed", false);
        prepared.putArray("issues").add("PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
        prepared.putObject("upstreamReview").put("status", "INCOMPLETE").put("reasonCode", "PVS1_START_LOSS_AUTO_UPSTREAM_COVERAGE_INCOMPLETE");
        prepared.set("mechanismAssessment", out);
        var publicView = Pvs1StartLossAutomaticPreparation.publicSummary(mapper, prepared);
        assertFalse(publicView.at("/mechanismAssessment/findings/0").has("sourceRecord"));
        assertEquals("evidence", publicView.at("/mechanismAssessment/findings/0/sourceSupport").asText());
        assertEquals("CURATED_MECHANISM_SUPPORTED", publicView.at("/mechanismAssessment/status").asText());
        var persistence = new Pvs1StartLossPersistenceTest(); persistence.temporary = temporary;
        persistence.saveAndRead(mapper.writeValueAsString(publicView), mapper.writeValueAsString(prepared));
        String output = System.getProperty("varvis.pvs1.startloss.output");
        if (output != null) java.nio.file.Files.writeString(Path.of(output).resolve("AUTO_NATIVE_MECHANISM.json"), mapper.writeValueAsString(publicView));
        c.withObject("gene").put("hgncId", "HGNC:4136").put("symbol", "GAMT");
        c.withObject("disease").put("mondoId", "MONDO:0012999");
        out = catalog.startLossMechanism(c);
        assertEquals("UNRESOLVED", out.path("status").asText());
        assertTrue(out.path("findings").toString().contains("PVS1_START_LOSS_MECHANISM_INFERRED_ONLY"));
    }
}
